package org.example.oscquery;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import lombok.extern.slf4j.Slf4j;

import java.io.Closeable;
import java.io.IOException;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * Minimal OSCQuery HTTP server (see https://github.com/Vidvox/OSCQueryProposal) that tells VRChat where to send
 * OSC messages.
 * <p>
 * VRChat sends avatar data to every OSCQuery service whose address tree contains {@code /avatar}, and it sends it
 * to the host/port found in the {@code HOST_INFO} of that service. This server therefore serves
 * <ul>
 *     <li>{@code GET /} - the (tiny) OSC address tree with the {@code /avatar} path VRChat sends to,</li>
 *     <li>{@code GET /?HOST_INFO} - the address and UDP port of the OSC listener of this app.</li>
 * </ul>
 * The tree is intentionally kept empty below {@code /avatar} so that the app can be told apart from VRChat's own
 * OSCQuery service (which lists all avatar parameters) when parameters are scanned.
 * <p>
 * The server only answers the two requests VRChat makes, so it does not affect OSC message handling in any way.
 */
@Slf4j
public class OscQueryHttpServer implements Closeable
{
    private static final String CONTENT_TYPE_JSON = "application/json";
    private static final String HOST_INFO_QUERY = "HOST_INFO";
    private static final String NAME = "HandyVRC-OSC";
    private static final int BACKLOG = 4;

    private static final ObjectMapper OBJECT_MAPPER = new ObjectMapper();

    private final HttpServer server;
    private final int port;
    private final byte[] rootTreeResponse;
    private final byte[] hostInfoResponse;

    /**
     * Starts an OSCQuery HTTP server on all local interfaces.
     * <p>
     * The OSC listener of this app is bound to {@code oscPort} on UDP, the HTTP server to the very same port
     * number on TCP. Using one port number for both is intentional: VRChat is reported to take the destination
     * port either from HOST_INFO or from the advertised OSC service (SRV record), and this way both are the same.
     *
     * @param httpPort TCP port to serve on (0 = any free port)
     * @param oscIp    address the OSC listener of this app can be reached at (advertised in HOST_INFO)
     * @param oscPort  UDP port the OSC listener of this app is bound to
     */
    public OscQueryHttpServer(int httpPort, String oscIp, int oscPort) throws IOException
    {
        this.server = HttpServer.create(new InetSocketAddress(httpPort), BACKLOG);
        this.port = server.getAddress().getPort();
        this.rootTreeResponse = buildRootTree();
        this.hostInfoResponse = buildHostInfo(oscIp, oscPort);
        server.createContext("/", this::handle);
        // Two threads at most: requests are tiny, but a keep-alive connection must not block the other one
        ExecutorService executor = Executors.newFixedThreadPool(2, runnable ->
        {
            Thread thread = new Thread(runnable, "OSCQuery-HTTP");
            thread.setDaemon(true);
            return thread;
        });
        server.setExecutor(executor);
        server.start();
        log.info("OSCQuery HTTP server listening on port {}", port);
    }

    /** @return the TCP port the OSCQuery HTTP server is listening on */
    public int getPort()
    {
        return port;
    }

    @Override
    public void close()
    {
        server.stop(0);
    }

    private void handle(HttpExchange exchange) throws IOException
    {
        try (exchange)
        {
            String query = exchange.getRequestURI().getQuery();
            if (query != null && query.toUpperCase().contains(HOST_INFO_QUERY))
            {
                respond(exchange, 200, hostInfoResponse);
                return;
            }
            String path = exchange.getRequestURI().getPath();
            if (path == null || path.isEmpty() || "/".equals(path) || "/avatar".equals(path))
            {
                respond(exchange, 200, rootTreeResponse);
                return;
            }
            respond(exchange, 404, "OSC Path not found".getBytes(StandardCharsets.UTF_8));
        }
        catch (Exception e)
        {
            // Never let an exception escape into the HTTP server thread
            log.warn("Failed to answer OSCQuery request: {}", e.getMessage());
        }
    }

    private void respond(HttpExchange exchange, int status, byte[] body) throws IOException
    {
        exchange.getResponseHeaders().add("Content-Type", CONTENT_TYPE_JSON);
        exchange.getResponseHeaders().add("Cache-Control", "no-cache");
        exchange.sendResponseHeaders(status, body.length);
        try (OutputStream out = exchange.getResponseBody())
        {
            out.write(body);
        }
    }

    /**
     * The OSC address tree VRChat reads to decide what to send. Only {@code /avatar} is needed: VRChat then sends
     * {@code /avatar/change} and all {@code /avatar/parameters/*} messages, and the concrete parameters are
     * configured in app.properties rather than here.
     */
    private static byte[] buildRootTree()
    {
        ObjectNode root = OBJECT_MAPPER.createObjectNode();
        root.put("FULL_PATH", "/");
        root.put("ACCESS", 0);
        ObjectNode avatar = root.putObject("CONTENTS").putObject("avatar");
        avatar.put("FULL_PATH", "/avatar");
        avatar.put("ACCESS", 0);
        return toJson(root);
    }

    private static byte[] buildHostInfo(String oscIp, int oscPort)
    {
        ObjectNode hostInfo = OBJECT_MAPPER.createObjectNode();
        hostInfo.put("NAME", NAME);
        hostInfo.put("OSC_IP", oscIp);
        hostInfo.put("OSC_PORT", oscPort);
        hostInfo.put("OSC_TRANSPORT", "UDP");
        ObjectNode extensions = hostInfo.putObject("EXTENSIONS");
        extensions.put("ACCESS", true);
        extensions.put("TYPE", true);
        extensions.put("VALUE", false);
        return toJson(hostInfo);
    }

    private static byte[] toJson(ObjectNode node)
    {
        try
        {
            return OBJECT_MAPPER.writeValueAsBytes(node);
        }
        catch (IOException e)
        {
            throw new IllegalStateException("Could not serialize OSCQuery response", e);
        }
    }
}
