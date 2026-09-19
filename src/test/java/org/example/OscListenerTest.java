package org.example;

import com.illposed.osc.OSCBundle;
import com.illposed.osc.OSCMessage;
import com.illposed.osc.OSCPacket;
import com.illposed.osc.transport.OSCPortOut;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.net.DatagramSocket;
import java.net.InetAddress;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;

import static org.assertj.core.api.Assertions.assertThat;

class OscListenerTest
{
    private static final InetAddress LOOPBACK = InetAddress.getLoopbackAddress();
    private static final String SOCKET = "/avatar/parameters/OGB/Orf/Blowjob/";
    /** Same wildcard style as the app configuration, so that pattern matching is covered as well. */
    private static final String ROOT_PARAMETER = "/avatar/parameters/OGB/Orf/*/PenSelfNewRoot";
    private static final String TIP_PARAMETER = "/avatar/parameters/OGB/Orf/*/PenSelfNewTip";
    private static final int RECEIVE_TIMEOUT_MS = 2000;

    private final List<Map<String, Float>> receivedValues = new CopyOnWriteArrayList<>();
    private OscListener listener;
    private OSCPortOut oscSender;

    @BeforeEach
    void setUp() throws IOException
    {
        // A port that was free a moment ago can be taken by another process, so retry a few times
        for (int attempt = 0; ; attempt++)
        {
            try
            {
                int port = findFreeUdpPort();
                listener = new OscListener(port);
                oscSender = new OSCPortOut(LOOPBACK, port);
                break;
            }
            catch (IOException e)
            {
                if (listener != null)
                {
                    listener.close();
                    listener = null;
                }
                if (attempt >= 4)
                {
                    throw e;
                }
            }
        }
        listener.registerPacketListener(List.of(ROOT_PARAMETER, TIP_PARAMETER), receivedValues::add);
    }

    @AfterEach
    void tearDown() throws IOException
    {
        listener.close();
        oscSender.close();
    }

    @Test
    void handlesRootAndTipSentTogetherInOnePacket() throws Exception
    {
        sendPackets(
                new OSCMessage(SOCKET + "PenSelfNewRoot", List.of(0.78796864f)),
                new OSCMessage(SOCKET + "PenSelfNewTip", List.of(0.9566813f)),
                new OSCMessage("/avatar/parameters/UnrelatedParameter", List.of(1.f))
        );

        assertThat(awaitValues(1)).containsExactly(Map.of(
                ROOT_PARAMETER, 0.78796864f,
                TIP_PARAMETER, 0.9566813f
        ));
    }

    @Test
    void handlesRootAndTipSentInSeparatePackets() throws Exception
    {
        sendPackets(new OSCMessage(SOCKET + "PenSelfNewRoot", List.of(0.5f)));
        sendPackets(new OSCMessage(SOCKET + "PenSelfNewTip", List.of(0.6f)));

        assertThat(awaitValues(2)).containsExactlyInAnyOrder(
                Map.of(ROOT_PARAMETER, 0.5f),
                Map.of(TIP_PARAMETER, 0.6f)
        );
    }

    @Test
    void convertsNonFloatArgumentsToFloat() throws Exception
    {
        sendPackets(new OSCMessage(SOCKET + "PenSelfNewRoot", List.of(1)));

        assertThat(awaitValues(1)).containsExactly(Map.of(ROOT_PARAMETER, 1.f));
    }

    @Test
    void ignoresPacketsWithoutRegisteredParameters() throws Exception
    {
        sendPackets(new OSCMessage("/avatar/parameters/VF11_Blowjob/Self/Contact/Root", List.of(0.9f)));

        Thread.sleep(200); // Give the listener a chance to (not) call us

        assertThat(receivedValues).isEmpty();
    }

    private List<Map<String, Float>> awaitValues(int expectedCount) throws InterruptedException
    {
        long deadline = System.currentTimeMillis() + RECEIVE_TIMEOUT_MS;
        while (receivedValues.size() < expectedCount && System.currentTimeMillis() < deadline)
        {
            Thread.sleep(10);
        }
        return List.copyOf(receivedValues);
    }

    private void sendPackets(OSCPacket... packets) throws Exception
    {
        // A single packet is sent as a plain message, multiple ones as one bundle (like VRChat does)
        oscSender.send(packets.length == 1 ? packets[0] : new OSCBundle(List.of(packets)));
    }

    private static int findFreeUdpPort() throws IOException
    {
        try (DatagramSocket socket = new DatagramSocket(0))
        {
            return socket.getLocalPort();
        }
    }
}
