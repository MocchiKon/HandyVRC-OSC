package org.example.oscquery;

import java.net.InetAddress;
import java.util.List;

/**
 * An OSCQuery service (another OSC application) that was found on the local network via mDNS.
 *
 * @param name      instance name the service advertised (VRChat advertises itself as {@code VRChat-Client-XXXXXX})
 * @param addresses addresses the service can be reached at, best first
 * @param port      TCP port of the HTTP server that serves the OSCQuery tree
 */
public record OscQueryServiceProfile(String name, List<InetAddress> addresses, int port)
{
    @Override
    public String toString()
    {
        return "%s (%s:%d)".formatted(name, addresses.isEmpty() ? "?" : addresses.getFirst().getHostAddress(), port);
    }
}
