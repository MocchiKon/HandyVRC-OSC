package org.example.oscquery;

import lombok.extern.slf4j.Slf4j;

import javax.jmdns.JmDNS;
import javax.jmdns.ServiceEvent;
import javax.jmdns.ServiceInfo;
import javax.jmdns.ServiceListener;
import java.io.Closeable;
import java.io.IOException;
import java.net.*;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.function.Consumer;

/**
 * Announces this app to VRChat over mDNS/Zeroconf so that VRChat starts sending OSC messages to the OSC port of
 * this app, and discovers the OSCQuery services of other applications (most importantly VRChat itself).
 * <p>
 * This is what makes a port collision impossible: VRChat is told where to send messages instead of having to use
 * the fixed port 9001, so another application listening on that port does not matter anymore.
 * <p>
 * Two mDNS libraries are involved, for a reason: the advertisement has to use the record layout VRChat understands
 * and is therefore sent by {@link MdnsAdvertisement}, while the discovery of other services (used to read and
 * validate the avatar parameters of VRChat) is done by JmDNS, which handles the caching and the address resolution
 * of the services it finds. See {@link MdnsMessage} for what VRChat expects from the advertisement.
 */
@Slf4j
public class OscQueryService implements Closeable
{
    public static final String SERVICE_NAME = "HandyVRC-OSC";
    public static final String OSC_QUERY_SERVICE_TYPE = MdnsMessage.OSC_QUERY_SERVICE_TYPE;
    public static final String OSC_SERVICE_TYPE = MdnsMessage.OSC_SERVICE_TYPE;

    /** How long mDNS resolution of a discovered service may take. */
    private static final long RESOLVE_TIMEOUT_MS = 2000;
    /** Only used to find out which interface the default route uses, no packet is sent to it. */
    private static final String ROUTE_PROBE_ADDRESS = "8.8.8.8";
    private static final int MAX_PORT_ATTEMPTS = 20;

    private final JmDNS jmDns;
    private final OscQueryHttpServer httpServer;
    private final MdnsAdvertisement advertisement;
    private final String ourServiceName;

    private final List<Consumer<OscQueryServiceProfile>> discoveryListeners = new CopyOnWriteArrayList<>();
    private final Map<String, OscQueryServiceProfile> discoveredServices = new ConcurrentHashMap<>();
    private volatile boolean closed;

    private OscQueryService(JmDNS jmDns, OscQueryHttpServer httpServer, MdnsAdvertisement advertisement,
                            String ourServiceName)
    {
        this.jmDns = jmDns;
        this.httpServer = httpServer;
        this.advertisement = advertisement;
        this.ourServiceName = ourServiceName;
    }

    /**
     * Starts the OSCQuery HTTP server and announces this app as OSCQuery service and as OSC service.
     *
     * @param port port the OSC listener of this app is bound to on UDP; the HTTP server is bound to it on TCP
     * @return the running service (only call this once per port)
     */
    public static OscQueryService start(int port) throws IOException
    {
        InetAddress localAddress = findLocalAddress();
        OscQueryHttpServer httpServer = null;
        MdnsAdvertisement advertisement = null;
        JmDNS jmDns = null;
        try
        {
            // The HTTP server has to answer before the service is announced, VRChat queries it right away
            httpServer = new OscQueryHttpServer(port, localAddress.getHostAddress(), port);
            advertisement = MdnsAdvertisement.start(SERVICE_NAME, localAddress, httpServer.getPort(), port);
            jmDns = startDiscoveryClient(localAddress);

            var service = new OscQueryService(jmDns, httpServer, advertisement, SERVICE_NAME);
            service.startDiscovery();
            Runtime.getRuntime().addShutdownHook(new Thread(service::close, "OSCQuery-Shutdown"));
            log.info("OSCQuery: announced '{}' on {} - VRChat can send OSC messages to UDP port {} (OSCQuery HTTP"
                            + " server uses the same port number on TCP)",
                    service.ourServiceName, localAddress.getHostAddress(), port);
            return service;
        }
        catch (IOException | RuntimeException e)
        {
            closeQuietly(advertisement);
            closeQuietly(jmDns);
            if (httpServer != null)
            {
                httpServer.close();
            }
            throw e;
        }
    }

    /**
     * @return the mDNS client that looks for VRChat's own OSCQuery service, or null when it cannot be started
     */
    private static JmDNS startDiscoveryClient(InetAddress address)
    {
        try
        {
            return createJmDns(address);
        }
        catch (IOException e)
        {
            // The app receives the avatar parameters without it as well, only their validation is skipped then
            log.warn("Could not start the mDNS client that looks for VRChat's OSCQuery service ({}). The avatar"
                    + " parameters are still received.", e.getMessage());
            return null;
        }
    }

    /**
     * Registers a listener that is called for every OSCQuery service found on the local network. Services that
     * were discovered before are reported immediately, and every service is only reported once (unless its
     * address or port changes).
     */
    public void addDiscoveryListener(Consumer<OscQueryServiceProfile> listener)
    {
        discoveryListeners.add(listener);
        List.copyOf(discoveredServices.values()).forEach(listener);
    }

    public void removeDiscoveryListener(Consumer<OscQueryServiceProfile> listener)
    {
        discoveryListeners.remove(listener);
    }

    @Override
    public void close()
    {
        if (closed)
        {
            return;
        }
        closed = true;
        // Says goodbye to the network instead of leaving a stale service behind, then stops the announcements
        advertisement.close();
        // JmDNS is only used to find other services, this app is advertised by MdnsAdvertisement
        closeQuietly(jmDns);
        httpServer.close();
    }

    private void startDiscovery()
    {
        if (jmDns == null)
        {
            return; // No mDNS client, so no other service can be found (and the advertisement does not need one)
        }
        jmDns.addServiceListener(OSC_QUERY_SERVICE_TYPE, new ServiceListener()
        {
            @Override
            public void serviceAdded(ServiceEvent event)
            {
                // Resolution is asynchronous: the result arrives through serviceResolved
                jmDns.requestServiceInfo(event.getType(), event.getName(), true, RESOLVE_TIMEOUT_MS);
            }

            @Override
            public void serviceRemoved(ServiceEvent event)
            {
                discoveredServices.remove(event.getName());
            }

            @Override
            public void serviceResolved(ServiceEvent event)
            {
                onServiceResolved(event.getInfo());
            }
        });

        // Services that were announced before this app started listening
        for (ServiceInfo info : jmDns.list(OSC_QUERY_SERVICE_TYPE))
        {
            if (info.getInetAddresses().length > 0)
            {
                onServiceResolved(info);
            }
            else
            {
                jmDns.requestServiceInfo(info.getType(), info.getName(), true, RESOLVE_TIMEOUT_MS);
            }
        }
    }

    private void onServiceResolved(ServiceInfo info)
    {
        if (info == null || info.getName() == null || info.getName().equals(ourServiceName))
        {
            return; // Our own advertisement
        }
        List<InetAddress> addresses = collectAddresses(info);
        if (addresses.isEmpty())
        {
            return;
        }
        var profile = new OscQueryServiceProfile(info.getName(), addresses, info.getPort());
        if (profile.equals(discoveredServices.put(info.getName(), profile)))
        {
            return; // Already known
        }
        log.debug("Discovered OSCQuery service {}", profile);
        discoveryListeners.forEach(listener -> listener.accept(profile));
    }

    private static List<InetAddress> collectAddresses(ServiceInfo info)
    {
        List<InetAddress> addresses = new ArrayList<>();
        for (Inet4Address address : info.getInet4Addresses())
        {
            addresses.add(address);
        }
        for (InetAddress address : info.getInetAddresses())
        {
            if (!(address instanceof Inet4Address))
            {
                addresses.add(address);
            }
        }
        return addresses;
    }

    /**
     * @return a port number that is free for both TCP (OSCQuery HTTP server) and UDP (OSC listener), so that the
     * OSC destination port is the same no matter whether VRChat reads it from HOST_INFO or from the OSC service
     */
    public static int findFreePort() throws IOException
    {
        for (int attempt = 0; attempt < MAX_PORT_ATTEMPTS; attempt++)
        {
            int port;
            try (ServerSocket tcpSocket = new ServerSocket(0))
            {
                port = tcpSocket.getLocalPort();
            }
            try (DatagramSocket udpSocket = new DatagramSocket(port))
            {
                return port;
            }
            catch (IOException e)
            {
                log.debug("Port {} is not free for UDP, trying another one", port);
            }
        }
        throw new IOException("Could not find a port that is free for both TCP and UDP");
    }

    /**
     * @return the local address used for the announcement. The interface of the default route is preferred, so
     * that VRChat running on another device (for example a Quest) can reach this app as well. Loopback is only
     * used when the machine has no network at all.
     */
    public static InetAddress findLocalAddress()
    {
        try (DatagramSocket socket = new DatagramSocket())
        {
            // Connecting a UDP socket does not send anything, it only makes the OS reveal the default route
            socket.connect(InetAddress.getByName(ROUTE_PROBE_ADDRESS), 53);
            InetAddress address = socket.getLocalAddress();
            if (isUsableAddress(address))
            {
                return address;
            }
        }
        catch (Exception e)
        {
            log.debug("Could not determine the local address from the default route: {}", e.getMessage());
        }
        try
        {
            InetAddress address = InetAddress.getLocalHost();
            if (isUsableAddress(address))
            {
                return address;
            }
        }
        catch (UnknownHostException e)
        {
            log.debug("Could not resolve the local host name: {}", e.getMessage());
        }
        log.warn("No local network address found, announcing on loopback only - other devices cannot reach this app");
        return loopbackAddress();
    }

    private static boolean isUsableAddress(InetAddress address)
    {
        return address != null && !address.isAnyLocalAddress() && !address.isLoopbackAddress();
    }

    private static JmDNS createJmDns(InetAddress address) throws IOException
    {
        try
        {
            return JmDNS.create(address, SERVICE_NAME);
        }
        catch (IOException e)
        {
            if (address.isLoopbackAddress())
            {
                throw e;
            }
            log.warn("Could not start mDNS on {} ({}), retrying on loopback", address.getHostAddress(), e.getMessage());
            return JmDNS.create(loopbackAddress(), SERVICE_NAME);
        }
    }

    /** {@code InetAddress.getLoopbackAddress()} may be an IPv6 address, which mDNS and VRChat handle badly. */
    private static InetAddress loopbackAddress()
    {
        try
        {
            return InetAddress.getByName("127.0.0.1");
        }
        catch (UnknownHostException e)
        {
            throw new IllegalStateException("127.0.0.1 does not resolve", e);
        }
    }

    private static void closeQuietly(Closeable closeable)
    {
        if (closeable == null)
        {
            return;
        }
        try
        {
            closeable.close();
        }
        catch (IOException e)
        {
            log.debug("Could not close after a failed start: {}", e.getMessage());
        }
    }
}
