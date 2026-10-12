package org.example.oscquery;

import org.junit.jupiter.api.Test;

import javax.jmdns.impl.DNSIncoming;
import javax.jmdns.impl.DNSRecord;
import javax.jmdns.impl.constants.DNSRecordType;
import java.io.IOException;
import java.net.DatagramPacket;
import java.net.InetAddress;
import java.net.UnknownHostException;
import java.util.Collection;
import java.util.List;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The record layout these tests pin down is the reason OSCQuery mode did not work before: VRChat builds a service
 * from a single mDNS packet and needs the service type in the answer section and the SRV (port) plus the A record
 * (address) in the additional section of that very packet. The JmDNS advertisement that was used before put
 * everything into the answer section and sent the A record in a separate packet, which no DNS-SD client complains
 * about but VRChat silently ignored.
 * <p>
 * The packets are parsed with JmDNS here on purpose: an independent parser proves that what is sent is a valid
 * DNS-SD response and not just something this code happens to understand.
 */
class MdnsMessageTest
{
    private static final InetAddress ADDRESS = address("192.168.1.11");
    private static final int OSC_QUERY_PORT = 62085;
    private static final int OSC_PORT = 62085;
    private static final int TTL_SECONDS = 120;

    @Test
    void announcementPutsTheServiceTypeInTheAnswersAndTheRestInTheAdditionals() throws Exception
    {
        DNSIncoming message = parse(announcement(MdnsMessage.OSC_QUERY_SERVICE_TYPE, OSC_QUERY_PORT));

        assertThat(names(message.getAnswers())).containsExactly(MdnsMessage.OSC_QUERY_SERVICE_TYPE);
        assertThat(types(message.getAdditionals())).containsExactlyInAnyOrder(DNSRecordType.TYPE_SRV,
                DNSRecordType.TYPE_TXT, DNSRecordType.TYPE_A);
    }

    /** This is the check VRChat's mDNS client ({@code MeaModDiscovery.AddMatchedService}) does. */
    @Test
    void vrchatCanBuildAServiceProfileFromOneAnnouncementPacket() throws Exception
    {
        byte[] packet = announcement(MdnsMessage.OSC_QUERY_SERVICE_TYPE, OSC_QUERY_PORT);
        DNSIncoming message = parse(packet);

        // The service type has to appear in the answer section, otherwise the packet is not even looked at
        assertThat(names(message.getAnswers())).contains(MdnsMessage.OSC_QUERY_SERVICE_TYPE);
        // The instance name, the type and the port come from the SRV record...
        DNSRecord.Service srv = serviceRecord(message.getAdditionals());
        assertThat(srv.getName()).isEqualTo("HandyVRC-OSC._oscjson._tcp.local.");
        assertThat(srv.getPort()).isEqualTo(OSC_QUERY_PORT);
        // ...and the address from the A record of the same packet (without it VRChat drops the service)
        assertThat(types(message.getAdditionals())).contains(DNSRecordType.TYPE_A);
        assertThat(containsBytes(packet, ADDRESS.getAddress())).isTrue();
    }

    @Test
    void announcesTheOscServiceWithTheOscPort() throws Exception
    {
        var service = service();
        DNSIncoming oscMessage = parse(MdnsMessage.serviceAnnouncement(service, MdnsMessage.OSC_SERVICE_TYPE,
                service.oscPort(), TTL_SECONDS));

        assertThat(names(oscMessage.getAnswers())).containsExactly(MdnsMessage.OSC_SERVICE_TYPE);
        assertThat(serviceRecord(oscMessage.getAdditionals()).getName()).isEqualTo("HandyVRC-OSC._osc._udp.local.");
        assertThat(serviceRecord(oscMessage.getAdditionals()).getPort()).isEqualTo(OSC_PORT);
    }

    @Test
    void announcementUsesTheHostNameTheAddressBelongsTo() throws Exception
    {
        DNSIncoming message = parse(announcement(MdnsMessage.OSC_QUERY_SERVICE_TYPE, OSC_QUERY_PORT));

        assertThat(names(message.getAdditionals())).contains("HandyVRC-OSC._oscjson._tcp.local.",
                "HandyVRC-OSC.local.");
    }

    @Test
    void goodbyeAnnouncementHasATimeToLiveOfZero() throws Exception
    {
        var service = service();
        DNSIncoming message = parse(MdnsMessage.serviceAnnouncement(service, MdnsMessage.OSC_QUERY_SERVICE_TYPE,
                service.oscQueryPort(), 0));

        // JmDNS does not expose the time to live, but this is what tells clients to drop the service
        assertThat(serviceRecord(message.getAdditionals()).toString()).contains("ttl: '0/0'");
    }

    @Test
    void answersAQueryForTheServiceWithTheSameLayout() throws Exception
    {
        byte[] query = MdnsMessage.query(0, MdnsMessage.OSC_QUERY_SERVICE_TYPE, MdnsMessage.TYPE_ANY, false);

        MdnsMessage.Reply reply = MdnsMessage.answer(service(), query, query.length, TTL_SECONDS, false);

        assertThat(reply).isNotNull();
        assertThat(reply.unicast()).isFalse();
        DNSIncoming response = parse(reply.packet());
        assertThat(response.getQuestions()).hasSize(1);
        assertThat(names(response.getAnswers())).contains(MdnsMessage.OSC_QUERY_SERVICE_TYPE);
        assertThat(types(response.getAdditionals())).containsExactlyInAnyOrder(DNSRecordType.TYPE_SRV,
                DNSRecordType.TYPE_TXT, DNSRecordType.TYPE_A);
        assertThat(serviceRecord(response.getAdditionals()).getPort()).isEqualTo(OSC_QUERY_PORT);
    }

    @Test
    void answersAQueryThatAsksForADirectAnswerUnicastWithTheQueryId() throws Exception
    {
        byte[] query = MdnsMessage.query(4711, MdnsMessage.OSC_QUERY_SERVICE_TYPE, MdnsMessage.TYPE_PTR, true);

        MdnsMessage.Reply reply = MdnsMessage.answer(service(), query, query.length, TTL_SECONDS, false);

        assertThat(reply).isNotNull();
        assertThat(reply.unicast()).isTrue();
        assertThat(parse(reply.packet()).getId()).isEqualTo(4711);
    }

    @Test
    void answersAQueryFromAClientThatDoesNotListenOnTheMdnsPortDirectly() throws Exception
    {
        byte[] query = MdnsMessage.query(23, MdnsMessage.OSC_QUERY_SERVICE_TYPE, MdnsMessage.TYPE_PTR, false);

        MdnsMessage.Reply reply = MdnsMessage.answer(service(), query, query.length, TTL_SECONDS, true);

        assertThat(reply).isNotNull();
        assertThat(reply.unicast()).isTrue();
        assertThat(parse(reply.packet()).getId()).isEqualTo(23);
    }

    @Test
    void answersAQueryForItsHostNameWithTheAddress() throws Exception
    {
        byte[] query = MdnsMessage.query(0, "HandyVRC-OSC.local.", MdnsMessage.TYPE_A, false);

        MdnsMessage.Reply reply = MdnsMessage.answer(service(), query, query.length, TTL_SECONDS, false);

        assertThat(reply).isNotNull();
        assertThat(types(parse(reply.packet()).getAnswers())).contains(DNSRecordType.TYPE_A);
    }

    @Test
    void answersAQueryForTheServiceInstanceWithPortAndAddress() throws Exception
    {
        byte[] query = MdnsMessage.query(0, "HandyVRC-OSC._oscjson._tcp.local.", MdnsMessage.TYPE_SRV, false);

        MdnsMessage.Reply reply = MdnsMessage.answer(service(), query, query.length, TTL_SECONDS, false);

        assertThat(reply).isNotNull();
        DNSIncoming response = parse(reply.packet());
        assertThat(serviceRecord(response.getAnswers()).getPort()).isEqualTo(OSC_QUERY_PORT);
        assertThat(types(response.getAdditionals())).contains(DNSRecordType.TYPE_A);
    }

    @Test
    void ignoresQueriesForOtherServices()
    {
        byte[] query = MdnsMessage.query(0, "_googlecast._tcp.local.", MdnsMessage.TYPE_PTR, false);

        assertThat(MdnsMessage.answer(service(), query, query.length, TTL_SECONDS, false)).isNull();
    }

    @Test
    void ignoresPacketsThatAreNotQueries() throws Exception
    {
        byte[] announcement = announcement(MdnsMessage.OSC_QUERY_SERVICE_TYPE, OSC_QUERY_PORT);

        assertThat(MdnsMessage.answer(service(), announcement, announcement.length, TTL_SECONDS, false)).isNull();
    }

    @Test
    void listsTheServiceTypesForBrowsersThatEnumerateThem() throws Exception
    {
        DNSIncoming message = parse(MdnsMessage.serviceEnumeration(service(), TTL_SECONDS));

        assertThat(names(message.getAnswers())).containsOnly(MdnsMessage.SERVICE_ENUMERATION_TYPE);
        assertThat(aliases(message.getAnswers())).containsExactlyInAnyOrder(MdnsMessage.OSC_QUERY_SERVICE_TYPE,
                MdnsMessage.OSC_SERVICE_TYPE);
    }

    private static MdnsMessage.Service service()
    {
        return new MdnsMessage.Service("HandyVRC-OSC", ADDRESS, OSC_QUERY_PORT, OSC_PORT);
    }

    private static byte[] announcement(String serviceType, int port)
    {
        return MdnsMessage.serviceAnnouncement(service(), serviceType, port, TTL_SECONDS);
    }

    private static DNSIncoming parse(byte[] packet) throws IOException
    {
        return new DNSIncoming(new DatagramPacket(packet, packet.length));
    }

    private static DNSRecord.Service serviceRecord(Collection<? extends DNSRecord> records)
    {
        return records.stream()
                .filter(DNSRecord.Service.class::isInstance)
                .map(DNSRecord.Service.class::cast)
                .findFirst()
                .orElseThrow(() -> new AssertionError("no SRV record in " + names(records)));
    }

    private static List<String> names(Collection<? extends DNSRecord> records)
    {
        return records.stream().map(DNSRecord::getName).toList();
    }

    private static Set<DNSRecordType> types(Collection<? extends DNSRecord> records)
    {
        return records.stream().map(DNSRecord::getRecordType).collect(java.util.stream.Collectors.toSet());
    }

    /** The name a PTR record points to; JmDNS does not expose it, its string form is stable enough for a test. */
    private static List<String> aliases(Collection<? extends DNSRecord> records)
    {
        return records.stream().map(record -> record.toString().replaceAll(".*alias: '([^']+)'.*", "$1")).toList();
    }

    private static boolean containsBytes(byte[] packet, byte[] value)
    {
        outer:
        for (int i = 0; i <= packet.length - value.length; i++)
        {
            for (int j = 0; j < value.length; j++)
            {
                if (packet[i + j] != value[j])
                {
                    continue outer;
                }
            }
            return true;
        }
        return false;
    }

    private static InetAddress address(String host)
    {
        try
        {
            return InetAddress.getByName(host);
        }
        catch (UnknownHostException e)
        {
            throw new IllegalStateException(e);
        }
    }
}
