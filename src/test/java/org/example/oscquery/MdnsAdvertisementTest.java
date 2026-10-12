package org.example.oscquery;

import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.Test;

import javax.jmdns.impl.DNSIncoming;
import javax.jmdns.impl.DNSRecord;
import javax.jmdns.impl.constants.DNSRecordType;
import java.io.IOException;
import java.net.*;
import java.util.Collection;
import java.util.function.Predicate;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Checks the advertisement on the wire: the announcement has to leave the machine on its own, and queries from
 * other mDNS clients have to be answered with the same record layout. Both are what VRChat needs to find this app
 * while it is already running.
 * <p>
 * The test needs multicast on the interface of the local address. Where that is not available (some containers,
 * machines without a network) it is skipped instead of failing - the layout itself is covered by
 * {@link MdnsMessageTest}, which needs no network at all.
 */
class MdnsAdvertisementTest
{
    private static final String SERVICE_NAME = "HandyVRC-OSC-Test";
    private static final long TIMEOUT_MS = 5_000;

    @Test
    void announcesItselfAndAnswersQueriesOnTheMdnsPort() throws Exception
    {
        InetAddress address = OscQueryService.findLocalAddress();
        NetworkInterface networkInterface = NetworkInterface.getByInetAddress(address);
        Assumptions.assumeTrue(networkInterface != null && networkInterface.supportsMulticast(),
                "mDNS needs multicast on " + address.getHostAddress());

        int port = OscQueryService.findFreePort();
        try (MulticastSocket client = openMdnsClient(networkInterface);
             MdnsAdvertisement advertisement = MdnsAdvertisement.start(SERVICE_NAME, address, port, port))
        {
            // Nobody asked for this: the announcement is what makes VRChat find an app that starts while it runs
            DNSIncoming announcement = receive(client, message -> message.getQuestions().isEmpty()
                    && hasServicePort(message, port));
            Assumptions.assumeTrue(announcement != null, "multicast loopback does not work on "
                    + address.getHostAddress());
            assertThat(names(announcement.getAnswers())).contains(MdnsMessage.OSC_QUERY_SERVICE_TYPE);
            assertThat(types(announcement.getAdditionals())).contains(DNSRecordType.TYPE_SRV, DNSRecordType.TYPE_A);
            assertThat(serviceRecord(announcement.getAdditionals()).getName())
                    .isEqualTo(SERVICE_NAME + "." + MdnsMessage.OSC_QUERY_SERVICE_TYPE);
            assertThat(serviceRecord(announcement.getAdditionals()).getPort()).isEqualTo(port);
            assertThat(names(announcement.getAdditionals())).contains(SERVICE_NAME + ".local.");

            // A client that asks for the service (VRChat does this when it starts) gets the same layout back
            byte[] query = MdnsMessage.query(0, MdnsMessage.OSC_QUERY_SERVICE_TYPE, MdnsMessage.TYPE_ANY, false);
            client.send(new DatagramPacket(query, query.length, mdnsGroup(), MdnsAdvertisement.getMdnsPort()));

            DNSIncoming response = receive(client, message -> !message.getQuestions().isEmpty()
                    && hasServicePort(message, port));
            assertThat(response).as("answer to the query").isNotNull();
            assertThat(names(response.getAnswers())).contains(MdnsMessage.OSC_QUERY_SERVICE_TYPE);
            assertThat(types(response.getAdditionals())).contains(DNSRecordType.TYPE_SRV, DNSRecordType.TYPE_A);
        }
    }

    private static MulticastSocket openMdnsClient(NetworkInterface networkInterface) throws IOException
    {
        MulticastSocket socket = new MulticastSocket(null);
        socket.setReuseAddress(true);
        socket.bind(new InetSocketAddress(MdnsAdvertisement.getMdnsPort()));
        socket.setNetworkInterface(networkInterface);
        socket.joinGroup(new InetSocketAddress(mdnsGroup(), MdnsAdvertisement.getMdnsPort()), networkInterface);
        socket.setSoTimeout(500);
        return socket;
    }

    /** @return the first packet that matches, or null when nothing arrived within the timeout */
    private static DNSIncoming receive(MulticastSocket socket, Predicate<DNSIncoming> matches) throws IOException
    {
        long deadline = System.currentTimeMillis() + TIMEOUT_MS;
        byte[] buffer = new byte[2048];
        while (System.currentTimeMillis() < deadline)
        {
            DatagramPacket packet = new DatagramPacket(buffer, buffer.length);
            try
            {
                socket.receive(packet);
            }
            catch (IOException e)
            {
                continue; // Read timeout, look again until the deadline
            }
            try
            {
                DNSIncoming message = parse(java.util.Arrays.copyOf(packet.getData(), packet.getLength()));
                if (matches.test(message))
                {
                    return message;
                }
            }
            catch (IOException e)
            {
                // Not a DNS packet, ignore it
            }
        }
        return null;
    }

    private static boolean hasServicePort(DNSIncoming message, int port)
    {
        return message.getAllAnswers().stream()
                .filter(DNSRecord.Service.class::isInstance)
                .map(DNSRecord.Service.class::cast)
                .anyMatch(record -> record.getPort() == port);
    }

    private static DNSRecord.Service serviceRecord(Collection<? extends DNSRecord> records)
    {
        return records.stream()
                .filter(DNSRecord.Service.class::isInstance)
                .map(DNSRecord.Service.class::cast)
                .findFirst()
                .orElseThrow(() -> new AssertionError("no SRV record in " + names(records)));
    }

    private static Collection<String> names(Collection<? extends DNSRecord> records)
    {
        return records.stream().map(DNSRecord::getName).toList();
    }

    private static Collection<DNSRecordType> types(Collection<? extends DNSRecord> records)
    {
        return records.stream().map(DNSRecord::getRecordType).toList();
    }

    private static DNSIncoming parse(byte[] packet) throws IOException
    {
        return new DNSIncoming(new DatagramPacket(packet, packet.length));
    }

    private static InetAddress mdnsGroup() throws IOException
    {
        return InetAddress.getByName("224.0.0.251");
    }
}
