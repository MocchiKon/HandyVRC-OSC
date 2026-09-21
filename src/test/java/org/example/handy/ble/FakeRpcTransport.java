package org.example.handy.ble;

import dev.handy.proto.HandyRpc;

import java.util.List;
import java.util.concurrent.*;

/**
 * Transport double for the RPC tests: it remembers everything that was written as a request and lets the test
 * push messages back as if the device had sent them.
 */
class FakeRpcTransport implements RpcTransport
{
    private final BlockingQueue<byte[]> incoming = new LinkedBlockingQueue<>();
    private final List<HandyRpc.RpcMessage> written = new CopyOnWriteArrayList<>();

    @Override
    public void write(byte[] payload)
    {
        try
        {
            written.add(HandyRpc.RpcMessage.parseFrom(payload));
        }
        catch (Exception e)
        {
            throw new IllegalStateException("Test transport received an unparseable payload", e);
        }
    }

    @Override
    public byte[] readMessage(long timeoutMs) throws TimeoutException, InterruptedException
    {
        byte[] data = incoming.poll(timeoutMs, TimeUnit.MILLISECONDS);
        if (data == null)
        {
            throw new TimeoutException("No message queued in the test transport");
        }
        return data;
    }

    /** Pushes a message as if the device had sent it. */
    void receive(HandyRpc.RpcMessage message)
    {
        incoming.add(message.toByteArray());
    }

    /** Pushes raw bytes as if the device had sent them (used for unparseable payloads). */
    void receiveRaw(byte[] data)
    {
        incoming.add(data);
    }

    List<HandyRpc.RpcMessage> written()
    {
        return List.copyOf(written);
    }

    /** Waits until at least {@code count} requests were written and returns them all. */
    List<HandyRpc.RpcMessage> awaitWritten(int count, long timeoutMs) throws InterruptedException
    {
        long deadline = System.currentTimeMillis() + timeoutMs;
        while (written.size() < count && System.currentTimeMillis() < deadline)
        {
            Thread.sleep(5);
        }
        if (written.size() < count)
        {
            throw new AssertionError("Expected %d written request(s) but got %d".formatted(count, written.size()));
        }
        return written();
    }
}
