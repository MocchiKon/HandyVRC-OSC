package org.example.oscquery;

import lombok.extern.slf4j.Slf4j;

import java.io.Closeable;
import java.io.IOException;
import java.net.*;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Announces this app as an OSC receiver over mDNS, so that VRChat finds it and sends the avatar parameters to it.
 * <p>
 * The packets are built by {@link MdnsMessage}, which explains why this app cannot use JmDNS for the
 * advertisement: VRChat only understands the record layout that the OSCQuery receivers known to work send.
 * JmDNS is still used by {@link OscQueryService} to <i>find</i> VRChat's OSCQuery service, which works fine.
 * <p>
 * The service is announced when the app starts and then re-announced regularly: VRChat queries for OSC services
 * only when it starts (or when a network interface appears), so an app that starts while VRChat is already running
 * has to make itself known with unsolicited announcements. Queries that do arrive are answered as well, and a
 * goodbye (the records with a time to live of 0) is sent when the app stops, so VRChat stops sending right away.
 */
@Slf4j
public class MdnsAdvertisement implements Closeable
{
    private static final InetAddress MDNS_GROUP = mdnsGroup();
    private static final int MDNS_PORT = 5353;
    /** How long clients may cache the records; also used for the answers to queries. */
    private static final int TTL_SECONDS = 120;
    /**
     * VRChat re-announces its own service about every 10 seconds; the same order of magnitude keeps this app
     * visible without noticeable traffic (the records are a few hundred bytes).
     */
    private static final long ANNOUNCE_INTERVAL_MS = 5_000;
    private static final int RECEIVE_BUFFER_SIZE = 2048;

    private final MdnsMessage.Service service;
    private final MulticastSocket socket;
    private final Thread announcer;
    private final Thread queryResponder;
    private final AtomicBoolean closed = new AtomicBoolean();

    /**
     * @param serviceName  instance name of the service, for example {@code HandyVRC-OSC}
     * @param address      address on which the OSC listener and the OSCQuery HTTP server can be reached
     * @param oscQueryPort port of the OSCQuery HTTP server
     * @param oscPort      port of the OSC listener
     */
    public static MdnsAdvertisement start(String serviceName, InetAddress address, int oscQueryPort, int oscPort)
            throws IOException
    {
        var advertisement = new MdnsAdvertisement(new MdnsMessage.Service(serviceName, address, oscQueryPort, oscPort));
        if (!advertisement.isUsable())
        {
            advertisement.close();
            throw new IOException("Could not open a socket for the mDNS announcement");
        }
        return advertisement;
    }

    private MdnsAdvertisement(MdnsMessage.Service service) throws IOException
    {
        this.service = service;
        this.socket = openSocket(service.address());
        this.announcer = new Thread(this::announceLoop, "OSCQuery-mDNS-announcer");
        this.announcer.setDaemon(true);
        this.announcer.start();
        this.queryResponder = socket == null ? null : new Thread(this::answerLoop, "OSCQuery-mDNS-responder");
        if (queryResponder != null)
        {
            queryResponder.setDaemon(true);
            queryResponder.start();
        }
    }

    @Override
    public void close()
    {
        if (!closed.compareAndSet(false, true))
        {
            return;
        }
        try
        {
            // A goodbye makes VRChat and other clients forget the service immediately instead of waiting for the TTL
            send(MdnsMessage.serviceAnnouncement(service, MdnsMessage.OSC_QUERY_SERVICE_TYPE, service.oscQueryPort(), 0));
            send(MdnsMessage.serviceAnnouncement(service, MdnsMessage.OSC_SERVICE_TYPE, service.oscPort(), 0));
        }
        catch (Exception e)
        {
            log.debug("Could not send the mDNS goodbye: {}", e.getMessage());
        }
        if (socket != null)
        {
            socket.close();
        }
        announcer.interrupt();
        if (queryResponder != null)
        {
            queryResponder.interrupt();
        }
    }

    private void announceLoop()
    {
        while (!closed.get())
        {
            try
            {
                send(MdnsMessage.serviceAnnouncement(service, MdnsMessage.OSC_QUERY_SERVICE_TYPE, service.oscQueryPort(),
                        TTL_SECONDS));
                send(MdnsMessage.serviceAnnouncement(service, MdnsMessage.OSC_SERVICE_TYPE, service.oscPort(),
                        TTL_SECONDS));
                // Browsers that list every service on the network (avahi-browse, JmDNS) ask for these
                send(MdnsMessage.serviceEnumeration(service, TTL_SECONDS));
            }
            catch (Exception e)
            {
                log.debug("Could not announce the OSCQuery service: {}", e.getMessage());
            }
            sleep(ANNOUNCE_INTERVAL_MS);
        }
    }

    private void answerLoop()
    {
        byte[] buffer = new byte[RECEIVE_BUFFER_SIZE];
        while (!closed.get())
        {
            DatagramPacket packet = new DatagramPacket(buffer, buffer.length);
            try
            {
                socket.receive(packet);
            }
            catch (IOException e)
            {
                if (closed.get())
                {
                    return;
                }
                log.debug("Could not read an mDNS query: {}", e.getMessage());
                continue;
            }
            try
            {
                answer(packet);
            }
            catch (Exception e)
            {
                log.debug("Could not answer an mDNS query: {}", e.getMessage());
            }
        }
    }

    private void answer(DatagramPacket query) throws IOException
    {
        // A client that does not listen on the mDNS port (a "legacy" unicast query) expects a direct answer
        boolean legacyUnicast = query.getPort() != MDNS_PORT;
        MdnsMessage.Reply reply = MdnsMessage.answer(service, query.getData(), query.getLength(), TTL_SECONDS,
                legacyUnicast);
        if (reply == null)
        {
            return;
        }
        if (reply.unicast())
        {
            socket.send(new DatagramPacket(reply.packet(), reply.packet().length, query.getSocketAddress()));
            return;
        }
        send(reply.packet());
    }

    private void send(byte[] packet) throws IOException
    {
        if (socket == null)
        {
            return;
        }
        socket.send(new DatagramPacket(packet, packet.length, MDNS_GROUP, MDNS_PORT));
    }

    private void sleep(long millis)
    {
        try
        {
            Thread.sleep(millis);
        }
        catch (InterruptedException e)
        {
            Thread.currentThread().interrupt();
            closed.set(true);
        }
    }

    /**
     * Binds the mDNS port and joins the mDNS multicast group on the interface of the announced address, so that
     * queries can be answered. When the port cannot be used (another application may have taken it exclusively),
     * the announcements are still sent - they are what VRChat needs - and only the answers to its queries are lost.
     *
     * @return the socket, or null when no socket could be opened at all
     */
    private static MulticastSocket openSocket(InetAddress address)
    {
        try
        {
            MulticastSocket socket = new MulticastSocket(null);
            socket.setReuseAddress(true);
            socket.bind(new InetSocketAddress(MDNS_PORT));
            NetworkInterface networkInterface = NetworkInterface.getByInetAddress(address);
            if (networkInterface != null)
            {
                socket.setNetworkInterface(networkInterface);
                socket.joinGroup(new InetSocketAddress(MDNS_GROUP, MDNS_PORT), networkInterface);
            }
            else
            {
                socket.setInterface(address);
                socket.joinGroup(new InetSocketAddress(MDNS_GROUP, MDNS_PORT), null);
            }
            return socket;
        }
        catch (IOException | RuntimeException e)
        {
            log.warn("Could not listen for mDNS queries on port {} ({}). The OSCQuery service is still announced,"
                    + " but queries (for example from a VRChat that starts later) cannot be answered.", MDNS_PORT,
                    e.getMessage());
            return sendOnlySocket(address);
        }
    }

    private static MulticastSocket sendOnlySocket(InetAddress address)
    {
        try
        {
            MulticastSocket socket = new MulticastSocket();
            socket.setInterface(address);
            return socket;
        }
        catch (IOException | RuntimeException e)
        {
            log.warn("Could not open a socket for the mDNS announcement: {}", e.getMessage());
            return null;
        }
    }

    private static InetAddress mdnsGroup()
    {
        try
        {
            return InetAddress.getByName("224.0.0.251");
        }
        catch (UnknownHostException e)
        {
            throw new IllegalStateException("224.0.0.251 does not resolve", e);
        }
    }

    /** Visible for tests: whether the socket could be opened at all (only then can anything be sent). */
    boolean isUsable()
    {
        return socket != null;
    }

    /** Visible for tests: the mDNS port the announcements are sent to and the queries are read from. */
    static int getMdnsPort()
    {
        return MDNS_PORT;
    }
}
