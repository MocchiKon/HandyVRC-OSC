package org.example.handy.ble;

import dev.handy.proto.HandyRpc;
import lombok.extern.slf4j.Slf4j;

import java.util.concurrent.TimeoutException;

/**
 * Wraps HandyBleClient to provide protobuf RPC request/response communication.
 */
@Slf4j
public class HandyRpcClient
{
    private static final long DEFAULT_TIMEOUT_MS = 10_000; // 10s
    private static final int NO_COMPLETION_ID = Integer.MAX_VALUE;

    private final HandyBleAdapter ble;
    private int messageIdCounter = 1;

    public HandyRpcClient(HandyBleAdapter ble)
    {
        this.ble = ble;
    }

    private int nextId()
    {
        return messageIdCounter++;
    }

    /**
     * Sends a request and waits for the response.
     */
    public HandyRpc.Response sendRequest(HandyRpc.Request.Builder requestBuilder) throws Exception
    {
        int id = nextId();
        requestBuilder.setId(id);

        HandyRpc.RpcMessage rpcMessage = HandyRpc.RpcMessage.newBuilder()
                .setType(HandyRpc.MessageType.MESSAGE_TYPE_REQUEST)
                .setRequest(requestBuilder.build())
                .build();

        byte[] payload = rpcMessage.toByteArray();
        ble.write(payload);

        // Wait for matching response
        long deadline = System.currentTimeMillis() + DEFAULT_TIMEOUT_MS;
        while (System.currentTimeMillis() < deadline)
        {
            long remaining = deadline - System.currentTimeMillis();
            if (remaining <= 0) break;

            byte[] data = ble.waitForResponse(remaining);
            HandyRpc.RpcMessage response = HandyRpc.RpcMessage.parseFrom(data);

            if (response.getType() == HandyRpc.MessageType.MESSAGE_TYPE_RESPONSE)
            {
                HandyRpc.Response resp = response.getResponse();
                if (resp.getId() == id)
                {
                    if (resp.hasError() && resp.getError().getCode() != 0)
                    {
                        throw new RuntimeException("Device error: code=" + resp.getError().getCode()
                                + " msg=" + resp.getError().getMessage());
                    }
                    return resp;
                }
                else // Not our response (could be from a previous timed-out request), discard
                {
                    log.info("Received response with id {}, expected {}, discarding", resp.getId(), id);
                }
            }
            else // Notification or other message type, discard and keep waiting
            {
                log.info("Received response with id {}, expected {}, discarding", id, id);
            }
        }
        throw new TimeoutException("Request " + id + " timed out");
    }

    /**
     * Sends a request without waiting for a response (fire and forget).
     */
    public void sendRequestFireAndForget(HandyRpc.Request.Builder requestBuilder)
    {
        requestBuilder.setId(NO_COMPLETION_ID);

        HandyRpc.RpcMessage rpcMessage = HandyRpc.RpcMessage.newBuilder()
                .setType(HandyRpc.MessageType.MESSAGE_TYPE_REQUEST)
                .setRequest(requestBuilder.build())
                .build();

        ble.write(rpcMessage.toByteArray());
    }
}
