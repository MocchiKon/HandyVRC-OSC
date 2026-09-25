package org.example;

import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;

/**
 * Builds raw OSC packets for tests. VRChat sends addresses that contain characters the OSC specification
 * forbids, which {@code OSCMessage} refuses to create, so the packets have to be assembled from bytes.
 */
final class OscTestPackets
{
    private static final long IMMEDIATE_TIME_TAG = 1L;

    private OscTestPackets()
    {
    }

    /** @return a raw OSC message with a single float argument */
    static byte[] message(String address, float value)
    {
        ByteBuffer buffer = ByteBuffer.allocate(512);
        putString(buffer, address);
        putString(buffer, ",f");
        buffer.putFloat(value);
        return toByteArray(buffer);
    }

    /** @return a raw OSC bundle containing the given raw messages */
    static byte[] bundle(byte[]... messages)
    {
        ByteBuffer buffer = ByteBuffer.allocate(4096);
        putString(buffer, "#bundle"); // putString appends the terminating zero the bundle header needs
        buffer.putLong(IMMEDIATE_TIME_TAG);
        for (byte[] message : messages)
        {
            buffer.putInt(message.length);
            buffer.put(message);
        }
        return toByteArray(buffer);
    }

    private static void putString(ByteBuffer buffer, String value)
    {
        buffer.put(value.getBytes(StandardCharsets.UTF_8));
        buffer.put((byte) 0);
        while (buffer.position() % 4 != 0)
        {
            buffer.put((byte) 0);
        }
    }

    private static byte[] toByteArray(ByteBuffer buffer)
    {
        buffer.flip();
        byte[] bytes = new byte[buffer.remaining()];
        buffer.get(bytes);
        return bytes;
    }
}
