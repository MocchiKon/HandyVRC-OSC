package org.example.handy.v3;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.ServerSocket;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Minimal HTTP server for the API client tests. It answers every request with a fixed JSON body, counts how many
 * requests it serves on each connection and closes a connection once it has served the configured number of
 * requests, which is what the real API server does (it recycles a connection after 100 requests). The first
 * connection can also be dropped without an answer, the way a request that runs into a recycled connection looks.
 */
final class FakeHandyApiServer implements AutoCloseable
{
    private static final String RESPONSE_BODY = "{\"result\":{\"points\":42}}";

    private final ServerSocket serverSocket = new ServerSocket(0);
    private final boolean dropFirstAppConnection;
    private final AtomicInteger connections = new AtomicInteger();
    private final AtomicInteger droppedConnections = new AtomicInteger();
    private final List<AtomicInteger> requestsPerConnectionCount = new CopyOnWriteArrayList<>();
    private final int requestsPerConnection;

    FakeHandyApiServer(int requestsPerConnection, boolean dropFirstAppConnection) throws IOException
    {
        this.requestsPerConnection = requestsPerConnection;
        this.dropFirstAppConnection = dropFirstAppConnection;
        Thread.ofVirtual().name("fake-handy-api").start(this::acceptConnections);
    }

    String baseUri()
    {
        return "http://localhost:" + serverSocket.getLocalPort();
    }

    /** Requests of the app that the server read, including the ones of a connection it dropped. */
    int requests()
    {
        return requestsPerConnectionCount.stream().mapToInt(AtomicInteger::get).sum();
    }

    int connections()
    {
        return connections.get();
    }

    int maxRequestsOnOneConnection()
    {
        return requestsPerConnectionCount.stream().mapToInt(AtomicInteger::get).max().orElse(0);
    }

    @Override
    public void close() throws IOException
    {
        serverSocket.close();
    }

    private void acceptConnections()
    {
        while (!serverSocket.isClosed())
        {
            try
            {
                Socket socket = serverSocket.accept();
                Thread.ofVirtual().name("fake-handy-api-connection").start(() -> serve(socket));
            }
            catch (IOException e)
            {
                return; // the server socket was closed, the test is over
            }
        }
    }

    private void serve(Socket socket)
    {
        try (socket)
        {
            socket.setTcpNoDelay(true); // a response in two small writes would otherwise wait ~40ms for the ACK
            connections.incrementAndGet();
            var requestsOnConnection = new AtomicInteger();
            requestsPerConnectionCount.add(requestsOnConnection);
            while (true)
            {
                String headers = readHeaders(socket.getInputStream());
                if (headers == null)
                {
                    return; // the client closed the connection
                }
                socket.getInputStream().readNBytes(contentLength(headers));

                int served = requestsOnConnection.incrementAndGet();
                if (dropFirstAppConnection && droppedConnections.incrementAndGet() == 1)
                {
                    return; // close the connection without an answer
                }

                boolean lastRequestOnConnection = served >= requestsPerConnection;
                respond(socket.getOutputStream(), lastRequestOnConnection);
                if (lastRequestOnConnection)
                {
                    return; // this connection served its requests, the server stops using it
                }
            }
        }
        catch (IOException e)
        {
            // the client or the test closed the connection
        }
    }

    private static int contentLength(String headers)
    {
        return headers.lines()
                .filter(line -> line.toLowerCase(Locale.ROOT).startsWith("content-length:"))
                .mapToInt(line -> Integer.parseInt(line.substring(line.indexOf(':') + 1).trim()))
                .findFirst()
                .orElse(0);
    }

    /** The request line and headers, or null when the client closed the connection. */
    private static String readHeaders(InputStream in) throws IOException
    {
        var headers = new StringBuilder();
        String line;
        while ((line = readLine(in)) != null)
        {
            if (line.isEmpty())
            {
                return headers.toString();
            }
            headers.append(line).append('\n');
        }
        return null;
    }

    private static String readLine(InputStream in) throws IOException
    {
        var line = new ByteArrayOutputStream();
        int read;
        while ((read = in.read()) != -1 && read != '\n')
        {
            if (read != '\r')
            {
                line.write(read);
            }
        }
        return read == -1 && line.size() == 0 ? null : line.toString(StandardCharsets.US_ASCII);
    }

    private static void respond(OutputStream out, boolean lastRequestOnConnection) throws IOException
    {
        byte[] body = RESPONSE_BODY.getBytes(StandardCharsets.UTF_8);
        byte[] headers = ("HTTP/1.1 200 OK\r\n"
                + "Content-Type: application/json\r\n"
                + "Content-Length: " + body.length + "\r\n"
                + (lastRequestOnConnection ? "Connection: close\r\n" : "")
                + "\r\n").getBytes(StandardCharsets.US_ASCII);
        out.write(headers);
        out.write(body);
        out.flush();
    }
}
