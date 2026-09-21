package org.example.handy.ble;

import com.google.protobuf.InvalidProtocolBufferException;
import dev.handy.proto.HandyRpc;
import lombok.extern.slf4j.Slf4j;

import java.util.Map;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Protobuf RPC request/response communication on top of a raw message transport (BLE).
 * <p>
 * A single reader thread owns the receive path and dispatches every incoming message by its type:
 * <ul>
 *     <li>Responses are matched to the request that waits for them <b>by request id</b>, so responses that
 *     arrive out of order, delayed, or for a request that already timed out are handled correctly and can never
 *     be handed to the wrong caller. A response whose id is not awaited is discarded (the late response of a
 *     timed-out request, a response to a fire-and-forget request, or a message from a previous connection).</li>
 *     <li>Notifications are handed to the registered {@link NotificationListener} instead of being consumed and
 *     thrown away by whoever happens to wait for a response.</li>
 * </ul>
 * Requests may be sent from any thread; ids are allocated atomically. A request is registered before it is
 * written, so an immediate response cannot be missed.
 */
@Slf4j
public class HandyRpcClient implements AutoCloseable
{
    private static final long DEFAULT_TIMEOUT_MS = 10_000; // 10s
    /**
     * Requests that do not wait for their response (fire and forget, for example continuously streamed HDSP
     * commands) are sent with this id so that the device's response can be recognized and dropped without a
     * response slot ever being registered for it.
     */
    public static final int NO_COMPLETION_ID = Integer.MAX_VALUE;
    /** How long a single read may block before the reader thread re-checks whether it should stop. */
    private static final long READ_POLL_MS = 250;

    private final RpcTransport transport;
    private final AtomicInteger messageIdCounter = new AtomicInteger(1);
    private final Map<Integer, PendingRequest> pendingRequests = new ConcurrentHashMap<>();
    private final AtomicBoolean running = new AtomicBoolean(true);
    private final Thread readerThread;
    private volatile NotificationListener notificationListener;

    /** A request whose response is still awaited by some caller. */
    private record PendingRequest(CompletableFuture<HandyRpc.Response> future, long sentAtMs, String description)
    {
    }

    @FunctionalInterface
    public interface NotificationListener
    {
        /**
         * Called on the reader thread when the device sends a notification (in contrast to a response).
         * Implementations must not block, otherwise they delay every pending response.
         */
        void onNotification(HandyRpc.Notification notification);
    }

    public HandyRpcClient(RpcTransport transport)
    {
        this.transport = transport;
        this.readerThread = new Thread(this::readLoop, "handy-ble-rpc-reader");
        this.readerThread.setDaemon(true);
        this.readerThread.start();
    }

    public void setNotificationListener(NotificationListener notificationListener)
    {
        this.notificationListener = notificationListener;
    }

    /**
     * Sends a request and waits for the matching response, turning a device-reported error into an exception.
     */
    public HandyRpc.Response sendRequest(HandyRpc.Request.Builder requestBuilder) throws Exception
    {
        return sendRequest(requestBuilder, DEFAULT_TIMEOUT_MS);
    }

    /**
     * Sends a request and waits for the matching response, turning a device-reported error into an exception.
     *
     * @param timeoutMs how long to wait for the response before giving up
     */
    public HandyRpc.Response sendRequest(HandyRpc.Request.Builder requestBuilder, long timeoutMs) throws Exception
    {
        HandyRpc.Response response = sendRequestRaw(requestBuilder, timeoutMs);
        if (hasError(response))
        {
            throw new HandyDeviceException(response.getError());
        }
        return response;
    }

    /**
     * Sends a request and waits for the matching response without turning a device error into an exception, so
     * that the caller can decide how to report it (for example as an error inside its own response object).
     *
     * @return the response with the same id as the request, or a {@link TimeoutException} when it did not arrive
     */
    public HandyRpc.Response sendRequestRaw(HandyRpc.Request.Builder requestBuilder, long timeoutMs) throws Exception
    {
        if (!running.get())
        {
            throw new IllegalStateException("RPC client is closed");
        }
        int id = nextId();
        requestBuilder.setId(id);
        String description = requestBuilder.getParamsCase().name();

        CompletableFuture<HandyRpc.Response> responseFuture = new CompletableFuture<>();
        // Register before writing: the response may be pushed back by the device before write() returns.
        pendingRequests.put(id, new PendingRequest(responseFuture, System.currentTimeMillis(), description));

        HandyRpc.RpcMessage rpcMessage = HandyRpc.RpcMessage.newBuilder()
                .setType(HandyRpc.MessageType.MESSAGE_TYPE_REQUEST)
                .setRequest(requestBuilder.build())
                .build();
        try
        {
            transport.write(rpcMessage.toByteArray());
        }
        catch (RuntimeException e)
        {
            pendingRequests.remove(id);
            throw e;
        }

        try
        {
            return responseFuture.get(timeoutMs, TimeUnit.MILLISECONDS);
        }
        catch (TimeoutException e)
        {
            // The pending entry is removed so that a late response is recognized as unawaited and dropped
            // instead of being handed to an unrelated later request.
            pendingRequests.remove(id);
            throw new TimeoutException("Request %d (%s) timed out after %dms".formatted(id, description, timeoutMs));
        }
        catch (InterruptedException e)
        {
            pendingRequests.remove(id);
            Thread.currentThread().interrupt();
            throw e;
        }
        catch (ExecutionException e)
        {
            pendingRequests.remove(id);
            if (e.getCause() instanceof RuntimeException cause)
            {
                throw cause;
            }
            throw e;
        }
    }

    /**
     * Sends a request without waiting for a response (fire and forget). The device still answers it, but the
     * response is only logged (at trace level) and never awaited.
     */
    public void sendRequestFireAndForget(HandyRpc.Request.Builder requestBuilder)
    {
        requestBuilder.setId(NO_COMPLETION_ID);

        HandyRpc.RpcMessage rpcMessage = HandyRpc.RpcMessage.newBuilder()
                .setType(HandyRpc.MessageType.MESSAGE_TYPE_REQUEST)
                .setRequest(requestBuilder.build())
                .build();

        transport.write(rpcMessage.toByteArray());
    }

    /** Number of requests whose response has not arrived yet; useful for diagnostics. */
    public int getPendingRequestCount()
    {
        return pendingRequests.size();
    }

    /**
     * Stops the reader thread and fails every request that is still waiting, so that no caller can hang on a
     * connection that is gone.
     */
    @Override
    public void close()
    {
        if (!running.compareAndSet(true, false))
        {
            return;
        }
        readerThread.interrupt();
        IllegalStateException closed = new IllegalStateException("RPC client is closed");
        pendingRequests.values().forEach(pending -> pending.future().completeExceptionally(closed));
        pendingRequests.clear();
    }

    private int nextId()
    {
        // Skip 0 and the fire-and-forget id, and stay positive after the (practically unreachable) overflow.
        return messageIdCounter.getAndUpdate(current ->
        {
            int next = current + 1;
            return next <= 0 || next == NO_COMPLETION_ID ? 1 : next;
        });
    }

    private void readLoop()
    {
        log.debug("RPC reader started");
        while (running.get())
        {
            byte[] data;
            try
            {
                data = transport.readMessage(READ_POLL_MS);
            }
            catch (TimeoutException e)
            {
                continue; // Nothing arrived - just re-check whether the client is still running
            }
            catch (InterruptedException e)
            {
                Thread.currentThread().interrupt();
                break;
            }
            catch (Exception e)
            {
                // A broken transport must not kill the reader: report it and keep reading.
                log.error("Could not read from the device: {}", e.getMessage());
                continue;
            }
            handleMessage(data);
        }
        log.debug("RPC reader stopped");
    }

    private void handleMessage(byte[] data)
    {
        HandyRpc.RpcMessage message;
        try
        {
            message = HandyRpc.RpcMessage.parseFrom(data);
        }
        catch (InvalidProtocolBufferException e)
        {
            // Truncated, fragmented or unrelated payload: it cannot be answered or matched, dropping it keeps
            // the connection usable instead of failing the request that happens to wait right now.
            log.warn("Dropping {} bytes that are not a (complete) RPC message: {}", data.length, e.getMessage());
            return;
        }

        switch (message.getType())
        {
            case MESSAGE_TYPE_RESPONSE -> handleResponse(message.getResponse());
            case MESSAGE_TYPE_NOTIFICATION -> handleNotification(message.getNotification());
            default -> log.debug("Ignoring message of type {}", message.getType());
        }
    }

    private void handleResponse(HandyRpc.Response response)
    {
        int id = response.getId();
        if (id == NO_COMPLETION_ID)
        {
            log.trace("Ignoring response {} of a fire-and-forget request", response.getResultCase());
            return;
        }

        PendingRequest pending = pendingRequests.remove(id);
        if (pending == null)
        {
            log.debug("Discarding response {} ({}): no request waits for it anymore (late, timed out or from a previous connection)",
                    id, response.getResultCase());
            return;
        }
        log.trace("Matched response {} to {} after {}ms ({} request(s) still waiting)",
                id, pending.description(), System.currentTimeMillis() - pending.sentAtMs(), pendingRequests.size());
        pending.future().complete(response);
    }

    private void handleNotification(HandyRpc.Notification notification)
    {
        NotificationListener listener = this.notificationListener;
        if (listener == null)
        {
            log.trace("Received notification {} but no listener is registered", notification.getNotificationCase());
            return;
        }
        try
        {
            listener.onNotification(notification);
        }
        catch (RuntimeException e)
        {
            log.error("Notification listener failed for {}: {}", notification.getNotificationCase(), e.getMessage());
        }
    }

    private static boolean hasError(HandyRpc.Response response)
    {
        return response.hasError() && response.getError().getCode() != 0;
    }
}
