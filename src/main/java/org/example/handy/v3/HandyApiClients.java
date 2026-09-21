package org.example.handy.v3;

import handy.api.HspApi;
import handy.api.InfoApi;
import handy.api.SliderApi;
import handy.api.UtilsApi;
import handy.invoker.ApiClient;
import lombok.extern.slf4j.Slf4j;
import org.example.handy.common.ConnectionRetry;
import org.example.handy.common.RateLimitedLogger;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

/**
 * The API objects (HSP, info, slider, utils) that talk to the Handy API over one HTTP connection, and the renewal
 * of that connection before the API server closes it.
 * <p>
 * The server serves a limited number of requests on one connection (measured: 100, signalled as an HTTP/2 GOAWAY
 * frame with {@code lastStreamId=199} or as {@code Connection: close} for HTTP/1.1) and then stops using it.
 * Requests that are in flight at that moment can only continue on a new connection, and establishing that
 * connection costs a TCP + TLS handshake (measured: ~160 ms at 40 ms round trip time, plus part of it for the
 * request that follows) - during which the points of the affected batches are late and may be skipped by the
 * device. A replacement connection is therefore opened and warmed up in the background while the current one is
 * still serving requests, and the callers are moved to it before the limit is reached, so that no batch has to
 * wait for a handshake.
 * <p>
 * The renewal cannot be perfect (the server limit can change, a connection can die for other reasons), so every
 * call still goes through {@link ConnectionRetry} and is repeated on a new connection if it fails at connection
 * level, and a connection that is given up before its renewal is reported in the log.
 */
@Slf4j
final class HandyApiClients
{
    /**
     * Requests the API server serves on one connection before it stops using it (measured on 2026-09-21).
     * A server that changed this shows up in the log (see {@link #warnWhenClosedBeforeItsRenewal(Connection)});
     * whether the assumption still holds can be checked with the on demand test {@code ServerRequestLimitTest}.
     */
    static final int SERVER_REQUEST_LIMIT = 100;

    /** A replacement connection is opened this many requests before the limit, so its handshake can finish in time. */
    private static final int WARM_UP_MARGIN = 20;

    /** Callers are moved to the replacement this many requests before the server would close the current one. */
    private static final int SWITCH_MARGIN = 5;

    /** Cheapest endpoint of the API, used to open a connection (TCP + TLS handshake) for a replacement. */
    private static final String WARM_UP_PATH = "/servertime";

    private final String applicationId;
    private final String baseUri;
    private final AtomicReference<Connection> active;
    /** A replacement connection that cannot be warmed up is tried again, but its warning is not logged per request. */
    private final RateLimitedLogger warmUpWarnings = new RateLimitedLogger(log);
    private volatile boolean closed;

    HandyApiClients(String applicationId)
    {
        this(applicationId, null);
    }

    /** Tests only: sends the requests to the given base URI instead of the real API. */
    HandyApiClients(String applicationId, String baseUri)
    {
        this.applicationId = applicationId;
        this.baseUri = baseUri;
        this.active = new AtomicReference<>(new Connection());
    }

    /** Tests only: uses the given API objects and never renews (or closes) their connection. */
    HandyApiClients(Clients clients)
    {
        this.applicationId = null;
        this.baseUri = null;
        this.active = new AtomicReference<>(new Connection(clients));
    }

    /** The API objects of one connection; the ones that an operation does not use are null. */
    record Clients(HspApi hspApi, InfoApi infoApi, SliderApi sliderApi, UtilsApi utilsApi)
    {
    }

    /** A call to the API that runs on the API objects of the connection it is given. */
    @FunctionalInterface
    interface ApiCall<T>
    {
        T execute(Clients clients) throws Exception;
    }

    /**
     * Runs an API call on the connection that is currently in use and repeats it once if the connection was
     * recycled or lost in the meantime.
     */
    <T> T call(String action, ApiCall<T> request) throws Exception
    {
        AtomicReference<Connection> usedConnection = new AtomicReference<>();
        return ConnectionRetry.call(action, () ->
        {
            Connection connection = acquire();
            usedConnection.set(connection);
            try
            {
                return request.execute(connection.clients);
            }
            finally
            {
                release(connection);
            }
        }, failure -> warnWhenClosedBeforeItsRenewal(usedConnection.get()));
    }

    /** Closes the connection that is in use and a replacement that was already warmed up. */
    void close()
    {
        closed = true;
        Connection connection = active.get();
        closeInBackground(connection);
        closeInBackground(connection.replacement);
    }

    /** Requests after which a replacement connection is opened (and warmed up) for the current one. */
    private int warmUpAfterRequests()
    {
        return Math.max(1, SERVER_REQUEST_LIMIT - WARM_UP_MARGIN);
    }

    /** Requests after which the callers are moved to the warmed up replacement connection. */
    private int switchAfterRequests()
    {
        return Math.max(2, SERVER_REQUEST_LIMIT - SWITCH_MARGIN);
    }

    /**
     * Returns the connection to use for one request. The renewal is driven from here because the server limit
     * counts requests and not time, so the requests themselves are the only reliable clock for it.
     */
    private Connection acquire()
    {
        for (; ; )
        {
            Connection connection = active.get();
            connection.requestsInFlight.incrementAndGet();
            if (connection.retired)
            {
                // the connection was replaced between reading it and counting this request: use the new one
                release(connection);
                continue;
            }

            int requestNumber = connection.requests.incrementAndGet();
            if (requestNumber >= warmUpAfterRequests())
            {
                warmUpReplacement(connection);
            }
            if (requestNumber >= switchAfterRequests())
            {
                switchToReplacement(connection);
            }
            return connection;
        }
    }

    private void release(Connection connection)
    {
        if (connection.requestsInFlight.decrementAndGet() == 0 && connection.retired)
        {
            closeInBackground(connection);
        }
    }

    /**
     * Warns when a connection had to be given up long before its renewal would have replaced it: the server then
     * serves fewer requests on one connection than the limit this client works with. The requests that ran into
     * that are repeated by {@link ConnectionRetry}, so nothing is lost, but the limit
     * {@link #SERVER_REQUEST_LIMIT} (and with it the renewal) may have to be adjusted.
     */
    private void warnWhenClosedBeforeItsRenewal(Connection connection)
    {
        if (connection == null || !connection.renewable())
        {
            return; // a connection that is not renewed (a test double) is not judged against the server limit
        }
        int servedRequests = connection.requests.get();
        if (servedRequests < switchAfterRequests())
        {
            log.warn("The Handy API closed a connection after {} request(s) although {} were expected before the renewal: "
                            + "it may serve fewer requests on one connection than the assumed {}",
                    servedRequests, switchAfterRequests(), SERVER_REQUEST_LIMIT);
        }
    }

    /** Opens a replacement connection and sends one request over it, so that it is ready before it is needed. */
    private void warmUpReplacement(Connection connection)
    {
        if (!connection.renewable() || closed || !connection.replacementRequested.compareAndSet(false, true))
        {
            return;
        }
        Thread.ofVirtual().name("handy-api-warm-up").start(() ->
        {
            try
            {
                Connection replacement = new Connection();
                replacement.warmUp();
                if (closed || active.get() != connection)
                {
                    closeInBackground(replacement); // the connection it was meant to replace is gone already
                    return;
                }
                connection.replacement = replacement;
                log.debug("Opened a replacement API connection after {} request(s) on the current one", connection.requests.get());
            }
            catch (Exception e)
            {
                // the current connection keeps serving; one of the next requests starts another attempt
                connection.replacementRequested.set(false);
                warmUpWarnings.warn("Could not open a replacement API connection (reason: %s)".formatted(e.getMessage()));
            }
        });
    }

    private void switchToReplacement(Connection connection)
    {
        Connection replacement = connection.replacement;
        if (replacement == null || !active.compareAndSet(connection, replacement))
        {
            return; // not warmed up yet (or replaced already): one of the next requests tries again
        }
        connection.retired = true;
        connection.replacement = null;
        if (connection.requestsInFlight.get() == 0)
        {
            closeInBackground(connection);
        }
        log.debug("Moved to the replacement API connection after {} request(s) on the previous one", connection.requests.get());
    }

    private static void closeInBackground(Connection connection)
    {
        if (connection != null && connection.closeRequested.compareAndSet(false, true))
        {
            // closing waits for the requests that are still in flight, so it must not block the caller
            Thread.ofVirtual().name("handy-api-close").start(connection::close);
        }
    }

    /** One HTTP connection to the API together with the API objects that use it. */
    private final class Connection
    {
        private final HttpClient httpClient;
        private final Clients clients;
        private final URI warmUpUri;
        private final AtomicInteger requests = new AtomicInteger();
        private final AtomicInteger requestsInFlight = new AtomicInteger();
        private final AtomicBoolean replacementRequested = new AtomicBoolean();
        private final AtomicBoolean closeRequested = new AtomicBoolean();
        private volatile Connection replacement;
        private volatile boolean retired;

        /** A connection of its own, with the API objects that use it. */
        private Connection()
        {
            this.httpClient = HttpClient.newBuilder().build();
            ApiClient apiClient = new SharedHttpClientApiClient(httpClient);
            HandyApiClientAuth.applyApiKey(apiClient, applicationId);
            if (baseUri != null)
            {
                apiClient.updateBaseUri(baseUri);
            }
            this.clients = new Clients(new HspApi(apiClient), new InfoApi(apiClient), new SliderApi(apiClient), new UtilsApi(apiClient));
            this.warmUpUri = URI.create(apiClient.getBaseUri() + WARM_UP_PATH);
        }

        /** The API objects a test provided: there is no connection to warm up and nothing to close. */
        private Connection(Clients clients)
        {
            this.httpClient = null;
            this.clients = clients;
            this.warmUpUri = null;
        }

        private boolean renewable()
        {
            return httpClient != null;
        }

        /** Establishes the connection (TCP + TLS handshake) with a request whose response is not used. */
        private void warmUp() throws Exception
        {
            requests.incrementAndGet();
            requestsInFlight.incrementAndGet();
            try
            {
                HttpRequest request = HttpRequest.newBuilder(warmUpUri)
                        .header(HandyApiClientAuth.API_KEY_HEADER, applicationId)
                        .GET()
                        .build();
                httpClient.send(request, HttpResponse.BodyHandlers.discarding());
            }
            finally
            {
                requestsInFlight.decrementAndGet();
            }
        }

        private void close()
        {
            if (httpClient != null)
            {
                httpClient.close();
            }
        }
    }
}
