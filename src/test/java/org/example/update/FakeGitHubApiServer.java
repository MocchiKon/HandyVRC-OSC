package org.example.update;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;

import java.io.IOException;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Minimal stand-in for the GitHub releases API. It answers every request with a configurable status and body,
 * can leave a request unanswered (a hanging connection), and remembers what the last request looked like.
 */
final class FakeGitHubApiServer implements AutoCloseable
{
    private static final int HANG_MS = 10_000;

    private final HttpServer server;
    private final ExecutorService executor = Executors.newVirtualThreadPerTaskExecutor();
    private final AtomicInteger requests = new AtomicInteger();
    private volatile int status = 200;
    private volatile String responseBody = "[]";
    private volatile boolean hang;
    private volatile String lastUserAgent;
    private volatile String lastAccept;
    private volatile String lastRequestTarget;

    FakeGitHubApiServer() throws IOException
    {
        server = HttpServer.create(new InetSocketAddress(InetAddress.getLoopbackAddress(), 0), 0);
        server.createContext("/", this::handle);
        server.setExecutor(executor);
        server.start();
    }

    URI releasesUri()
    {
        return URI.create("http://localhost:" + server.getAddress().getPort() + "/repos/MocchiKon/HandyVRC-OSC/releases");
    }

    FakeGitHubApiServer respondWith(int status, String body)
    {
        this.status = status;
        this.responseBody = body;
        return this;
    }

    /** Accepts requests but never answers them, the way a connection that hangs looks to the app. */
    FakeGitHubApiServer hang()
    {
        this.hang = true;
        return this;
    }

    int requests()
    {
        return requests.get();
    }

    String lastUserAgent()
    {
        return lastUserAgent;
    }

    String lastAccept()
    {
        return lastAccept;
    }

    String lastRequestTarget()
    {
        return lastRequestTarget;
    }

    @Override
    public void close()
    {
        server.stop(0);
        executor.shutdownNow(); // also interrupts a request that is hanging on purpose
    }

    private void handle(HttpExchange exchange) throws IOException
    {
        requests.incrementAndGet();
        lastUserAgent = exchange.getRequestHeaders().getFirst("User-Agent");
        lastAccept = exchange.getRequestHeaders().getFirst("Accept");
        lastRequestTarget = exchange.getRequestURI().toString();
        try
        {
            if (hang)
            {
                sleepQuietly();
                return; // no answer is ever sent
            }
            byte[] body = responseBody.getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().add("Content-Type", "application/json");
            exchange.sendResponseHeaders(status, body.length);
            exchange.getResponseBody().write(body);
        }
        finally
        {
            exchange.close();
        }
    }

    private static void sleepQuietly()
    {
        try
        {
            Thread.sleep(HANG_MS);
        }
        catch (InterruptedException e)
        {
            Thread.currentThread().interrupt();
        }
    }
}
