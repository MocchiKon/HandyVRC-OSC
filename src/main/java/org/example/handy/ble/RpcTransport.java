package org.example.handy.ble;

import java.util.concurrent.TimeoutException;

/**
 * Raw message transport used by {@link HandyRpcClient}: it writes one payload per request and hands over the
 * payloads the device sends back.
 * <p>
 * Implementations have to support <b>exactly one reader</b> (the RPC client owns a single reader thread that
 * demultiplexes all incoming messages, so no message can be stolen by another waiter) and may be called from
 * several writer threads.
 */
public interface RpcTransport
{
    /** Writes a single message to the device. */
    void write(byte[] payload);

    /**
     * Blocks until the device sends the next message.
     *
     * @param timeoutMs how long to wait for a message
     * @return the raw message payload
     * @throws TimeoutException when no message arrived within the timeout; the caller may simply retry
     */
    byte[] readMessage(long timeoutMs) throws TimeoutException, InterruptedException;
}
