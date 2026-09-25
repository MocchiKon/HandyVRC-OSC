package org.example;

import org.junit.jupiter.api.Test;

import java.net.DatagramPacket;
import java.net.DatagramSocket;
import java.net.InetAddress;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;

import static org.assertj.core.api.Assertions.assertThat;

class OscListenerInvalidAddressTest
{
    private static final String ROOT_PATTERN = "/avatar/parameters/OGB/Orf/*/PenSelfNewRoot";
    private static final String TIP_PATTERN = "/avatar/parameters/OGB/Orf/*/PenSelfNewTip";
    private static final long RECEIVE_TIMEOUT_MS = 2000;

    @Test
    void receivesTheOtherParametersOfABundleWithAnInvalidAddress() throws Exception
    {
        List<Map<String, Float>> receivedValues = new CopyOnWriteArrayList<>();
        int port = findFreeUdpPort();
        OscListener listener = new OscListener(port);
        try
        {
            listener.registerPacketListener(List.of(ROOT_PATTERN, TIP_PATTERN), receivedValues::add);

            byte[] packet = OscTestPackets.bundle(
                    OscTestPackets.message("/avatar/parameters/OGB/Orf/Big Pen/PenSelfNewRoot", 0.5f),
                    OscTestPackets.message("/avatar/parameters/OGB/Orf/Blowjob/PenSelfNewTip", 0.6f));
            send(port, packet);

            assertThat(awaitValues(receivedValues))
                    .containsExactly(Map.of(ROOT_PATTERN, 0.5f, TIP_PATTERN, 0.6f));
        }
        finally
        {
            listener.close();
        }
    }

    @Test
    void receivesAParameterWhoseAddressContainsASpace() throws Exception
    {
        List<Map<String, Float>> receivedValues = new CopyOnWriteArrayList<>();
        int port = findFreeUdpPort();
        OscListener listener = new OscListener(port);
        try
        {
            listener.registerPacketListener(List.of(ROOT_PATTERN), receivedValues::add);

            send(port, OscTestPackets.message("/avatar/parameters/OGB/Orf/Big Pen/PenSelfNewRoot", 0.5f));

            assertThat(awaitValues(receivedValues)).containsExactly(Map.of(ROOT_PATTERN, 0.5f));
        }
        finally
        {
            listener.close();
        }
    }

    private static void send(int port, byte[] packet) throws Exception
    {
        try (DatagramSocket socket = new DatagramSocket())
        {
            socket.send(new DatagramPacket(packet, packet.length, InetAddress.getLoopbackAddress(), port));
        }
    }

    private static List<Map<String, Float>> awaitValues(List<Map<String, Float>> receivedValues)
            throws InterruptedException
    {
        long deadline = System.currentTimeMillis() + RECEIVE_TIMEOUT_MS;
        while (receivedValues.isEmpty() && System.currentTimeMillis() < deadline)
        {
            Thread.sleep(10);
        }
        return List.copyOf(receivedValues);
    }

    private static int findFreeUdpPort() throws Exception
    {
        try (DatagramSocket socket = new DatagramSocket(0))
        {
            return socket.getLocalPort();
        }
    }
}
