package org.example.oscquery;

import java.io.ByteArrayOutputStream;
import java.net.InetAddress;
import java.nio.charset.StandardCharsets;
import java.util.*;

/**
 * The part of the DNS wire format that is needed to advertise this app over mDNS (DNS-SD), built by hand.
 * <p>
 * The record layout is not a detail - it is what makes VRChat find this app. VRChat discovers OSC receivers with
 * the mDNS client of the OSCQuery library it ships ({@code MeaModDiscovery}). That client builds a service from
 * <b>one single response packet</b>: it looks for the {@code _oscjson._tcp.local.} service type in the
 * <i>answer</i> section, and for the SRV record (the port) and the A record (the address) in the <i>additional</i>
 * section of that same packet - without an A record in the same packet it drops the service.
 * <p>
 * JmDNS, the mDNS library this app uses to look for VRChat, cannot send such a packet: it puts PTR, SRV and TXT
 * all into the answer section and sends the A record in a packet of its own. That is a valid DNS-SD response
 * (avahi-browse, Bonjour and JmDNS itself are happy with it) but VRChat cannot read it, which is why its OSCQuery
 * mode never received anything. This class sends the packets the way the OSCQuery receivers that are known to work
 * do (VRCFaceTracking, OscGoesBrrr, VRCOSC, vrchat_osc):
 * <pre>
 * answers:     _oscjson._tcp.local.                 PTR HandyVRC-OSC._oscjson._tcp.local.
 * additionals: HandyVRC-OSC._oscjson._tcp.local.    SRV 0 0 62085 HandyVRC-OSC.local.
 *              HandyVRC-OSC._oscjson._tcp.local.    TXT "txtvers=1"
 *              HandyVRC-OSC.local.                  A 192.168.1.11
 * </pre>
 */
final class MdnsMessage
{
    static final String OSC_QUERY_SERVICE_TYPE = "_oscjson._tcp.local.";
    static final String OSC_SERVICE_TYPE = "_osc._udp.local.";
    /** The service type browsers query to list every service on the network (needs no VRChat support). */
    static final String SERVICE_ENUMERATION_TYPE = "_services._dns-sd._udp.local.";

    static final int TYPE_A = 1;
    static final int TYPE_PTR = 12;
    static final int TYPE_TXT = 16;
    static final int TYPE_SRV = 33;
    static final int TYPE_ANY = 255;

    private static final int CLASS_IN = 1;
    /** Set by a question to ask for a direct (unicast) answer instead of a multicast one. */
    private static final int CLASS_UNICAST_RESPONSE = 0x8000;
    /** Set by a record to tell clients that no other host has a record with this name. */
    private static final int CLASS_CACHE_FLUSH = 0x8000;
    private static final int FLAGS_AUTHORITATIVE_RESPONSE = 0x8400;
    private static final int HEADER_SIZE = 12;
    private static final int MAX_LABEL_LENGTH = 63;
    private static final String TXT_VERSION = "txtvers=1";

    /**
     * Everything the advertisement needs to know about this app.
     *
     * @param name          instance name, advertised to VRChat and shown in its "sending data to" notification
     * @param hostName      mDNS host name the A record is published for
     * @param address       address VRChat is told to send its OSC messages to
     * @param oscQueryPort  port of the OSCQuery HTTP server
     * @param oscPort       port of the OSC listener
     */
    record Service(String name, String hostName, InetAddress address, int oscQueryPort, int oscPort)
    {
        Service(String name, InetAddress address, int oscQueryPort, int oscPort)
        {
            this(name, name + ".local.", address, oscQueryPort, oscPort);
        }

        String oscQueryInstance()
        {
            return name + "." + OSC_QUERY_SERVICE_TYPE;
        }

        String oscInstance()
        {
            return name + "." + OSC_SERVICE_TYPE;
        }
    }

    /** A question of a received query, for example {@code _oscjson._tcp.local.} of type {@code PTR}. */
    record Question(String name, int type, boolean unicastResponse)
    {
    }

    /** A ready-to-send answer to a query, or null when the query does not ask for anything this app offers. */
    record Reply(byte[] packet, boolean unicast)
    {
    }

    private MdnsMessage()
    {
    }

    /**
     * @param serviceType {@link #OSC_QUERY_SERVICE_TYPE} or {@link #OSC_SERVICE_TYPE}
     * @param port        the port that belongs to that service (OSCQuery HTTP port or OSC port)
     * @param ttlSeconds  how long clients may cache the records; 0 is the goodbye sent when the app stops
     * @return the unsolicited announcement VRChat and other mDNS clients pick this app up from
     */
    static byte[] serviceAnnouncement(Service service, String serviceType, int port, int ttlSeconds)
    {
        String instance = service.name() + "." + serviceType;
        Builder builder = new Builder(0, FLAGS_AUTHORITATIVE_RESPONSE);
        builder.answerPtr(serviceType, instance, ttlSeconds);
        builder.additionalSrv(instance, service.hostName(), port, ttlSeconds);
        builder.additionalTxt(instance, ttlSeconds, TXT_VERSION);
        builder.additionalAddress(service.hostName(), service.address(), ttlSeconds);
        return builder.build();
    }

    /**
     * @return the announcement that lists the service types of this app, so that browsers which enumerate all
     * services on the network (avahi-browse, JmDNS) find them as well
     */
    static byte[] serviceEnumeration(Service service, int ttlSeconds)
    {
        Builder builder = new Builder(0, FLAGS_AUTHORITATIVE_RESPONSE);
        builder.answerPtr(SERVICE_ENUMERATION_TYPE, OSC_QUERY_SERVICE_TYPE, ttlSeconds);
        builder.answerPtr(SERVICE_ENUMERATION_TYPE, OSC_SERVICE_TYPE, ttlSeconds);
        return builder.build();
    }

    /**
     * @param ttlSeconds    how long clients may cache the answer
     * @param legacyUnicast whether the query came from a client that does not listen on the mDNS port, which
     *                      always has to be answered directly (RFC 6762 section 6.7)
     * @return the response to a query, or null when nothing this app offers was asked for
     */
    static Reply answer(Service service, byte[] packet, int length, int ttlSeconds, boolean legacyUnicast)
    {
        if (!isQuery(packet, length))
        {
            return null;
        }
        List<Question> questions = readQuestions(packet, length);
        if (questions.isEmpty())
        {
            return null;
        }
        boolean wantsOscQuery = false;
        boolean wantsOsc = false;
        boolean wantsEnumeration = false;
        boolean wantsOscQueryDetails = false;
        boolean wantsOscDetails = false;
        boolean wantsAddress = false;
        for (Question question : questions)
        {
            if (isPtrorAny(question) && matches(question, OSC_QUERY_SERVICE_TYPE))
            {
                wantsOscQuery = true;
            }
            else if (isPtrorAny(question) && matches(question, OSC_SERVICE_TYPE))
            {
                wantsOsc = true;
            }
            else if (isPtrorAny(question) && matches(question, SERVICE_ENUMERATION_TYPE))
            {
                wantsEnumeration = true;
            }
            else if (matches(question, service.oscQueryInstance()))
            {
                wantsOscQueryDetails = isType(question, TYPE_SRV) || isType(question, TYPE_TXT);
            }
            else if (matches(question, service.oscInstance()))
            {
                wantsOscDetails = isType(question, TYPE_SRV) || isType(question, TYPE_TXT);
            }
            else if (isType(question, TYPE_A) && matches(question, service.hostName()))
            {
                wantsAddress = true;
            }
        }
        if (!wantsOscQuery && !wantsOsc && !wantsEnumeration && !wantsOscQueryDetails && !wantsOscDetails && !wantsAddress)
        {
            return null;
        }

        // A unicast answer goes back to the client that asked for it, and carries its query id
        boolean unicast = legacyUnicast || questions.stream().anyMatch(Question::unicastResponse);
        Builder builder = new Builder(unicast ? readId(packet, length) : 0, FLAGS_AUTHORITATIVE_RESPONSE);
        questions.forEach(builder::question);

        boolean addressAdded = false;
        if (wantsEnumeration)
        {
            builder.answerPtr(SERVICE_ENUMERATION_TYPE, OSC_QUERY_SERVICE_TYPE, ttlSeconds);
            builder.answerPtr(SERVICE_ENUMERATION_TYPE, OSC_SERVICE_TYPE, ttlSeconds);
        }
        if (wantsOscQuery)
        {
            builder.answerPtr(OSC_QUERY_SERVICE_TYPE, service.oscQueryInstance(), ttlSeconds);
            builder.additionalSrv(service.oscQueryInstance(), service.hostName(), service.oscQueryPort(), ttlSeconds);
            builder.additionalTxt(service.oscQueryInstance(), ttlSeconds, TXT_VERSION);
            addressAdded = true;
        }
        if (wantsOsc)
        {
            builder.answerPtr(OSC_SERVICE_TYPE, service.oscInstance(), ttlSeconds);
            builder.additionalSrv(service.oscInstance(), service.hostName(), service.oscPort(), ttlSeconds);
            builder.additionalTxt(service.oscInstance(), ttlSeconds, TXT_VERSION);
            addressAdded = true;
        }
        if (wantsOscQueryDetails)
        {
            builder.answerSrv(service.oscQueryInstance(), service.hostName(), service.oscQueryPort(), ttlSeconds);
            builder.answerTxt(service.oscQueryInstance(), ttlSeconds, TXT_VERSION);
            addressAdded = true;
        }
        if (wantsOscDetails)
        {
            builder.answerSrv(service.oscInstance(), service.hostName(), service.oscPort(), ttlSeconds);
            builder.answerTxt(service.oscInstance(), ttlSeconds, TXT_VERSION);
            addressAdded = true;
        }
        if (wantsAddress)
        {
            builder.answerAddress(service.hostName(), service.address(), ttlSeconds);
        }
        else if (addressAdded)
        {
            builder.additionalAddress(service.hostName(), service.address(), ttlSeconds);
        }
        return new Reply(builder.build(), unicast);
    }

    /** @return the questions of a query; empty for a response or a malformed packet */
    static List<Question> readQuestions(byte[] packet, int length)
    {
        List<Question> questions = new ArrayList<>();
        if (length < HEADER_SIZE)
        {
            return questions;
        }
        int questionCount = readU16(packet, 4);
        int offset = HEADER_SIZE;
        for (int i = 0; i < questionCount && offset < length; i++)
        {
            Name name = readName(packet, length, offset);
            if (name == null || name.nextOffset() + 4 > length)
            {
                break;
            }
            int type = readU16(packet, name.nextOffset());
            int recordClass = readU16(packet, name.nextOffset() + 2);
            questions.add(new Question(name.value(), type, (recordClass & CLASS_UNICAST_RESPONSE) != 0));
            offset = name.nextOffset() + 4;
        }
        return questions;
    }

    static boolean isQuery(byte[] packet, int length)
    {
        return length >= HEADER_SIZE && (packet[2] & 0x80) == 0;
    }

    /** Builds a query packet. Needed to answer questions in tests; this app itself only advertises. */
    static byte[] query(int id, String name, int type, boolean unicastResponse)
    {
        Builder builder = new Builder(id, 0);
        builder.question(new Question(name, type, unicastResponse));
        return builder.build();
    }

    private static boolean matches(Question question, String name)
    {
        return stripTrailingDot(question.name()).equalsIgnoreCase(stripTrailingDot(name));
    }

    private static boolean isPtrorAny(Question question)
    {
        return question.type() == TYPE_PTR || question.type() == TYPE_ANY;
    }

    private static boolean isType(Question question, int type)
    {
        return question.type() == type || question.type() == TYPE_ANY;
    }

    private static String stripTrailingDot(String name)
    {
        return name.endsWith(".") ? name.substring(0, name.length() - 1) : name;
    }

    private static int readId(byte[] packet, int length)
    {
        return length >= 2 ? readU16(packet, 0) : 0;
    }

    private static int readU16(byte[] packet, int offset)
    {
        return ((packet[offset] & 0xFF) << 8) | (packet[offset + 1] & 0xFF);
    }

    private record Name(String value, int nextOffset)
    {
    }

    /** Reads a (possibly compressed) domain name, see RFC 1035 section 4.1.4. */
    private static Name readName(byte[] packet, int length, int offset)
    {
        StringBuilder name = new StringBuilder();
        int current = offset;
        int nextOffset = -1;
        while (current < length)
        {
            int labelLength = packet[current] & 0xFF;
            if (labelLength == 0)
            {
                current++;
                return new Name(name.toString(), nextOffset < 0 ? current : nextOffset);
            }
            if ((labelLength & 0xC0) == 0xC0)
            {
                if (current + 1 >= length)
                {
                    return null;
                }
                if (nextOffset < 0)
                {
                    nextOffset = current + 2;
                }
                current = ((labelLength & 0x3F) << 8) | (packet[current + 1] & 0xFF);
                continue;
            }
            if (current + 1 + labelLength > length)
            {
                return null;
            }
            if (!name.isEmpty())
            {
                name.append('.');
            }
            name.append(new String(packet, current + 1, labelLength, StandardCharsets.UTF_8));
            current += 1 + labelLength;
        }
        return null;
    }

    /** Builds one mDNS packet; the sections are what VRChat reads, so records are added to them explicitly. */
    private static final class Builder
    {
        private final ByteArrayOutputStream out = new ByteArrayOutputStream(256);
        private final Map<String, Integer> writtenNames = new HashMap<>();
        /** Places in the packet that are only known once everything is written: {offset, value} of a 16 bit field. */
        private final List<int[]> patches = new ArrayList<>();
        private int questionCount;
        private int answerCount;
        private int additionalCount;

        Builder(int id, int flags)
        {
            writeU16(id);
            writeU16(flags);
            writeU16(0); // QDCOUNT
            writeU16(0); // ANCOUNT
            writeU16(0); // NSCOUNT
            writeU16(0); // ARCOUNT
        }

        Builder question(Question question)
        {
            writeName(question.name());
            writeU16(question.type());
            writeU16(CLASS_IN | (question.unicastResponse() ? CLASS_UNICAST_RESPONSE : 0));
            questionCount++;
            return this;
        }

        Builder answerPtr(String name, String alias, int ttlSeconds)
        {
            return record(true, name, TYPE_PTR, ttlSeconds, false, () -> writeName(alias));
        }

        Builder answerSrv(String instance, String hostName, int port, int ttlSeconds)
        {
            return record(true, instance, TYPE_SRV, ttlSeconds, true, () -> writeSrvData(hostName, port));
        }

        Builder answerTxt(String instance, int ttlSeconds, String... entries)
        {
            return record(true, instance, TYPE_TXT, ttlSeconds, true, () -> writeTxtData(entries));
        }

        Builder additionalSrv(String instance, String hostName, int port, int ttlSeconds)
        {
            return record(false, instance, TYPE_SRV, ttlSeconds, true, () -> writeSrvData(hostName, port));
        }

        Builder additionalTxt(String instance, int ttlSeconds, String... entries)
        {
            return record(false, instance, TYPE_TXT, ttlSeconds, true, () -> writeTxtData(entries));
        }

        Builder additionalAddress(String hostName, InetAddress address, int ttlSeconds)
        {
            return record(false, hostName, TYPE_A, ttlSeconds, true, () -> writeBytes(address.getAddress()));
        }

        Builder answerAddress(String hostName, InetAddress address, int ttlSeconds)
        {
            return record(true, hostName, TYPE_A, ttlSeconds, true, () -> writeBytes(address.getAddress()));
        }

        byte[] build()
        {
            byte[] packet = out.toByteArray();
            patches.forEach(patch -> writeU16(packet, patch[0], patch[1]));
            writeU16(packet, 4, questionCount);
            writeU16(packet, 6, answerCount);
            writeU16(packet, 10, additionalCount);
            return packet;
        }

        private Builder record(boolean answer, String name, int type, int ttlSeconds, boolean unique, Runnable data)
        {
            writeName(name);
            writeU16(type);
            writeU16(CLASS_IN | (unique ? CLASS_CACHE_FLUSH : 0));
            writeU32(ttlSeconds);
            int lengthOffset = out.size();
            patches.add(new int[] {lengthOffset, 0});
            writeU16(0);
            int dataStart = out.size();
            data.run();
            patches.getLast()[1] = out.size() - dataStart;
            if (answer)
            {
                answerCount++;
            }
            else
            {
                additionalCount++;
            }
            return this;
        }

        private void writeSrvData(String hostName, int port)
        {
            writeU16(0); // priority
            writeU16(0); // weight
            writeU16(port);
            writeName(hostName);
        }

        private void writeTxtData(String[] entries)
        {
            for (String entry : entries)
            {
                byte[] bytes = entry.getBytes(StandardCharsets.UTF_8);
                writeByte(bytes.length);
                writeBytes(bytes);
            }
        }

        /** Writes a domain name and reuses names that were already written, see RFC 1035 section 4.1.4. */
        private void writeName(String name)
        {
            String rest = stripTrailingDot(name);
            while (!rest.isEmpty())
            {
                Integer offset = writtenNames.get(rest.toLowerCase(Locale.ROOT));
                if (offset != null)
                {
                    writeU16(0xC000 | offset);
                    return;
                }
                if (out.size() <= 0x3FFF)
                {
                    writtenNames.put(rest.toLowerCase(Locale.ROOT), out.size());
                }
                int dot = rest.indexOf('.');
                String label = dot < 0 ? rest : rest.substring(0, dot);
                byte[] bytes = label.getBytes(StandardCharsets.UTF_8);
                if (bytes.length > MAX_LABEL_LENGTH)
                {
                    throw new IllegalArgumentException("DNS label is too long: " + label);
                }
                writeByte(bytes.length);
                writeBytes(bytes);
                rest = dot < 0 ? "" : rest.substring(dot + 1);
            }
            writeByte(0);
        }

        private void writeByte(int value)
        {
            out.write(value);
        }

        private void writeBytes(byte[] bytes)
        {
            out.writeBytes(bytes);
        }

        private void writeU16(int value)
        {
            out.write((value >> 8) & 0xFF);
            out.write(value & 0xFF);
        }

        private void writeU32(int value)
        {
            out.write((value >> 24) & 0xFF);
            out.write((value >> 16) & 0xFF);
            out.write((value >> 8) & 0xFF);
            out.write(value & 0xFF);
        }

        private static void writeU16(byte[] packet, int offset, int value)
        {
            packet[offset] = (byte) ((value >> 8) & 0xFF);
            packet[offset + 1] = (byte) (value & 0xFF);
        }
    }
}
