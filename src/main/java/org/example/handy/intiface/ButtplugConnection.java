package org.example.handy.intiface;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import lombok.extern.slf4j.Slf4j;
import org.example.handy.common.RateLimitedLogger;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.WebSocket;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Client side of the Buttplug protocol (message spec version 3) that Intiface Central speaks over a WebSocket.
 * <p>
 * Every message is a JSON array of objects, each object holding one message keyed by its type, for example
 * {@code [{"LinearCmd":{"Id":5,"DeviceIndex":0,"Vectors":[...]}}]}. The client numbers its requests (Id 1 and up)
 * and the server answers each with a message carrying the same Id ({@code Ok}, {@code Error} or the requested
 * data). Messages with Id 0 are events the server sends on its own, such as {@code DeviceAdded}.
 */
@Slf4j
public class ButtplugConnection implements AutoCloseable
{
    static final int MESSAGE_VERSION = 3;
    private static final long CONNECT_TIMEOUT_MS = 5_000;
    private static final long REQUEST_TIMEOUT_MS = 5_000;

    /** Sends the text messages of a connection; the WebSocket in the app, a double in the tests. */
    interface Transport
    {
        void send(String text) throws IOException;

        void close();
    }

    /**
     * A device the server knows about.
     *
     * @param linearFeatures number of linear (position) actuators, 0 when the device cannot be moved to a position
     */
    public record Device(int index, String name, int linearFeatures)
    {
    }

    private final ObjectMapper json = new ObjectMapper();
    private final AtomicInteger nextId = new AtomicInteger(1);
    private final Map<Integer, CompletableFuture<JsonNode>> pending = new ConcurrentHashMap<>();
    private final Map<Integer, Device> devices = new ConcurrentHashMap<>();
    /** Movement commands are sent continuously, a failing one would otherwise flood the log. */
    private final RateLimitedLogger commandErrors = new RateLimitedLogger(log);
    private Transport transport;
    private volatile boolean open = true;
    private ScheduledExecutorService pinger;

    ButtplugConnection()
    {
    }

    /**
     * Connects to the server and introduces the client.
     *
     * @param uri        address of the server, for example ws://127.0.0.1:12345
     * @param clientName name the server shows for this client
     */
    public static ButtplugConnection connect(URI uri, String clientName) throws IOException
    {
        var connection = new ButtplugConnection();
        WebSocket webSocket;
        try
        {
            webSocket = HttpClient.newHttpClient()
                    .newWebSocketBuilder()
                    .connectTimeout(Duration.ofMillis(CONNECT_TIMEOUT_MS))
                    .buildAsync(uri, new Listener(connection))
                    .get(CONNECT_TIMEOUT_MS, TimeUnit.MILLISECONDS);
        }
        catch (ExecutionException | TimeoutException e)
        {
            throw new IOException("Could not reach Intiface Central at %s. Start Intiface Central and its server,"
                    .formatted(uri) + " then press Start again.", e);
        }
        catch (InterruptedException e)
        {
            Thread.currentThread().interrupt();
            throw new IOException("Interrupted while connecting to Intiface Central", e);
        }
        connection.transport = new WebSocketTransport(webSocket);
        try
        {
            connection.handshake(clientName);
        }
        catch (IOException | RuntimeException e)
        {
            connection.close();
            throw e;
        }
        return connection;
    }

    /** Sends RequestServerInfo and starts pinging when the server asks for it. */
    void handshake(String clientName) throws IOException
    {
        ObjectNode request = json.createObjectNode()
                .put("ClientName", clientName)
                .put("MessageVersion", MESSAGE_VERSION);
        JsonNode serverInfo = request("RequestServerInfo", request, "ServerInfo");
        long maxPingTimeMs = serverInfo.path("MaxPingTime").asLong(0);
        log.info("Connected to {} (Buttplug message version {}, ping every {})",
                serverInfo.path("ServerName").asText("Intiface"), serverInfo.path("MessageVersion").asInt(),
                maxPingTimeMs > 0 ? maxPingTimeMs / 2 + "ms" : "never");
        if (maxPingTimeMs > 0)
        {
            startPinging(Math.max(maxPingTimeMs / 2, 50));
        }
    }

    /** Uses the given transport (tests only). */
    void setTransport(Transport transport)
    {
        this.transport = transport;
    }

    public boolean isOpen()
    {
        return open;
    }

    /** @return the devices the server currently has connected */
    public Collection<Device> devices()
    {
        return List.copyOf(devices.values());
    }

    public boolean hasDevice(int index)
    {
        return devices.containsKey(index);
    }

    /** Asks the server for the devices it has connected already. */
    public void refreshDeviceList() throws IOException
    {
        JsonNode list = request("RequestDeviceList", json.createObjectNode(), "DeviceList");
        for (JsonNode device : list.path("Devices"))
        {
            addDevice(device);
        }
    }

    public void startScanning() throws IOException
    {
        request("StartScanning", json.createObjectNode(), "Ok");
    }

    public void stopScanning() throws IOException
    {
        request("StopScanning", json.createObjectNode(), "Ok");
    }

    /**
     * Moves every linear actuator of the device to the position in the given time. Does not wait for the answer
     * (commands are sent continuously, waiting would stall the stream); a failure is logged.
     *
     * @param position 0.0 (bottom) to 1.0 (top)
     */
    public void linear(Device device, double position, int durationMs) throws IOException
    {
        ArrayNode vectors = json.createArrayNode();
        for (int feature = 0; feature < Math.max(device.linearFeatures(), 1); feature++)
        {
            vectors.addObject()
                    .put("Index", feature)
                    .put("Duration", Math.max(durationMs, 0))
                    .put("Position", Math.clamp(position, 0.0, 1.0));
        }
        ObjectNode command = json.createObjectNode().put("DeviceIndex", device.index());
        command.set("Vectors", vectors);
        sendWithoutWaiting("LinearCmd", command);
    }

    /** Stops the device. Does not wait for the answer. */
    public void stopDevice(Device device) throws IOException
    {
        sendWithoutWaiting("StopDeviceCmd", json.createObjectNode().put("DeviceIndex", device.index()));
    }

    /**
     * Sends a request and waits for its answer.
     *
     * @param expectedType type of the answer, for example "Ok"
     * @return the fields of the answer
     */
    JsonNode request(String type, ObjectNode fields, String expectedType) throws IOException
    {
        CompletableFuture<JsonNode> answer = send(type, fields);
        JsonNode message;
        try
        {
            message = answer.get(REQUEST_TIMEOUT_MS, TimeUnit.MILLISECONDS);
        }
        catch (TimeoutException e)
        {
            throw new IOException("Intiface Central did not answer " + type + " within " + REQUEST_TIMEOUT_MS + "ms", e);
        }
        catch (ExecutionException e)
        {
            throw new IOException(type + " failed: " + e.getCause().getMessage(), e.getCause());
        }
        catch (InterruptedException e)
        {
            Thread.currentThread().interrupt();
            throw new IOException("Interrupted while waiting for Intiface Central", e);
        }
        String answerType = message.fieldNames().next();
        if (!answerType.equals(expectedType))
        {
            throw new IOException("Intiface Central answered %s with %s instead of %s".formatted(type, answerType, expectedType));
        }
        return message.get(answerType);
    }

    private void sendWithoutWaiting(String type, ObjectNode fields) throws IOException
    {
        send(type, fields).whenComplete((answer, error) ->
        {
            if (error != null)
            {
                commandErrors.warn("[Intiface] %s failed: %s".formatted(type, error.getMessage()));
            }
        });
    }

    private CompletableFuture<JsonNode> send(String type, ObjectNode fields) throws IOException
    {
        if (!open)
        {
            throw new IOException("The connection to Intiface Central is closed");
        }
        int id = nextId.getAndIncrement();
        fields.put("Id", id);
        ArrayNode messages = json.createArrayNode();
        messages.addObject().set(type, fields);
        var answer = new CompletableFuture<JsonNode>();
        pending.put(id, answer);
        try
        {
            String text = json.writeValueAsString(messages);
            transport.send(text);
            if (log.isTraceEnabled())
            {
                log.trace("[Intiface] Sent {}", text);
            }
        }
        catch (IOException | RuntimeException e)
        {
            pending.remove(id);
            throw e;
        }
        return answer;
    }

    /** Handles a complete text message from the server. */
    void onText(String text)
    {
        JsonNode messages;
        try
        {
            messages = json.readTree(text);
        }
        catch (IOException e)
        {
            log.warn("[Intiface] Could not read a message from Intiface Central: {}", e.getMessage());
            return;
        }
        for (JsonNode message : messages)
        {
            if (!message.isObject() || message.isEmpty())
            {
                continue;
            }
            String type = message.fieldNames().next();
            JsonNode fields = message.get(type);
            int id = fields.path("Id").asInt(0);
            CompletableFuture<JsonNode> answer = id != 0 ? pending.remove(id) : null;
            if (answer != null)
            {
                if (type.equals("Error"))
                {
                    answer.completeExceptionally(new IOException(fields.path("ErrorMessage").asText("unknown error")));
                }
                else
                {
                    answer.complete(message);
                }
                continue;
            }
            onEvent(type, fields);
        }
    }

    private void onEvent(String type, JsonNode fields)
    {
        switch (type)
        {
            case "DeviceAdded" -> addDevice(fields);
            case "DeviceRemoved" ->
            {
                Device removed = devices.remove(fields.path("DeviceIndex").asInt(-1));
                if (removed != null)
                {
                    log.warn("[Intiface] {} disconnected", removed.name());
                }
            }
            case "ScanningFinished" -> log.debug("[Intiface] Scanning finished");
            case "Error" -> log.warn("[Intiface] Intiface Central reported an error: {}", fields.path("ErrorMessage").asText());
            default -> log.trace("[Intiface] Ignoring {}", type);
        }
    }

    private void addDevice(JsonNode fields)
    {
        JsonNode linear = fields.path("DeviceMessages").path("LinearCmd");
        // Message version 3 lists one entry per actuator, older versions give a feature count
        int linearFeatures = linear.isArray() ? linear.size() : linear.path("FeatureCount").asInt(0);
        var device = new Device(fields.path("DeviceIndex").asInt(), fields.path("DeviceName").asText("Unknown device"),
                linearFeatures);
        if (devices.put(device.index(), device) == null)
        {
            log.info("[Intiface] Device available: {} ({})", device.name(),
                    linearFeatures > 0 ? "can be moved to a position" : "no position control, not usable");
        }
    }

    private void startPinging(long intervalMs)
    {
        pinger = Executors.newSingleThreadScheduledExecutor(runnable ->
        {
            Thread thread = new Thread(runnable, "intiface-ping");
            thread.setDaemon(true);
            return thread;
        });
        pinger.scheduleAtFixedRate(() ->
        {
            try
            {
                sendWithoutWaiting("Ping", json.createObjectNode());
            }
            catch (IOException e)
            {
                log.debug("[Intiface] Could not ping: {}", e.getMessage());
            }
        }, intervalMs, intervalMs, TimeUnit.MILLISECONDS);
    }

    /** Called when the server closed the connection or it broke. */
    void onClosed(String reason)
    {
        if (open)
        {
            log.warn("[Intiface] Connection to Intiface Central closed: {}", reason);
        }
        shutDown();
    }

    private void shutDown()
    {
        open = false;
        if (pinger != null)
        {
            pinger.shutdownNow();
        }
        var failed = new ArrayList<>(pending.values());
        pending.clear();
        failed.forEach(answer -> answer.completeExceptionally(new IOException("The connection to Intiface Central was closed")));
        devices.clear();
    }

    @Override
    public void close()
    {
        boolean wasOpen = open;
        shutDown();
        if (wasOpen && transport != null)
        {
            transport.close();
            log.info("Disconnected from Intiface Central");
        }
    }

    private static final class WebSocketTransport implements Transport
    {
        private final WebSocket webSocket;

        WebSocketTransport(WebSocket webSocket)
        {
            this.webSocket = webSocket;
        }

        /**
         * Synchronized and waits until the message is handed over, because a WebSocket only accepts the next
         * message once the previous one is sent.
         */
        @Override
        public synchronized void send(String text) throws IOException
        {
            try
            {
                webSocket.sendText(text, true).get(REQUEST_TIMEOUT_MS, TimeUnit.MILLISECONDS);
            }
            catch (ExecutionException | TimeoutException e)
            {
                throw new IOException("Could not send to Intiface Central: " + e.getMessage(), e);
            }
            catch (InterruptedException e)
            {
                Thread.currentThread().interrupt();
                throw new IOException("Interrupted while sending to Intiface Central", e);
            }
        }

        @Override
        public void close()
        {
            try
            {
                webSocket.sendClose(WebSocket.NORMAL_CLOSURE, "").get(1, TimeUnit.SECONDS);
            }
            catch (Exception e)
            {
                webSocket.abort();
            }
        }
    }

    /** Collects the parts of each text message and hands complete messages to the connection. */
    private static final class Listener implements WebSocket.Listener
    {
        private final ButtplugConnection connection;
        private final StringBuilder partial = new StringBuilder();

        Listener(ButtplugConnection connection)
        {
            this.connection = connection;
        }

        @Override
        public CompletionStage<?> onText(WebSocket webSocket, CharSequence data, boolean last)
        {
            partial.append(data);
            if (last)
            {
                String text = partial.toString();
                partial.setLength(0);
                if (log.isTraceEnabled())
                {
                    log.trace("[Intiface] Received {}", text);
                }
                connection.onText(text);
            }
            webSocket.request(1);
            return null;
        }

        @Override
        public CompletionStage<?> onClose(WebSocket webSocket, int statusCode, String reason)
        {
            connection.onClosed("status %d %s".formatted(statusCode, reason).trim());
            return null;
        }

        @Override
        public void onError(WebSocket webSocket, Throwable error)
        {
            connection.onClosed(String.valueOf(error.getMessage()));
        }
    }
}
