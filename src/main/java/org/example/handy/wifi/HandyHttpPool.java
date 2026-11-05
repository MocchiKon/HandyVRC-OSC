package org.example.handy.wifi;

import lombok.extern.slf4j.Slf4j;

import java.io.IOException;
import java.net.CookieManager;
import java.net.CookiePolicy;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.concurrent.Executors;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Keeps the HTTP connections to the Handy servers warm and rotates them before the servers drop them.
 * <p>
 * The Handy servers close an HTTP connection after roughly 100 requests (HTTP/2 GOAWAY). A command that is sent just
 * as that happens can fail even though the connection still looked healthy, which would lose a movement command. The
 * pool therefore holds two independent {@link HttpClient}s: the <em>active</em> one that serves requests, and a
 * <em>standby</em> one that is already connected and warmed up. Before the active connection reaches the server
 * limit it is replaced by the standby, and a new standby is prepared in the background. The standby is kept alive
 * with a cheap, unauthenticated request so that it is ready when it is promoted.
 * <p>
 * A request that fails with an I/O error (for example a GOAWAY) is retried by the caller on a freshly opened
 * connection. Movement commands are idempotent, so retrying one is safe.
 */
@Slf4j
final class HandyHttpPool implements AutoCloseable
{
    /** Stay clearly below the ~100 request limit of the Handy servers. */
    static final int DEFAULT_REQUESTS_PER_CONNECTION = 80;
    /** Even when a new connection cannot be prepared, the current one is not used beyond this. */
    private static final int HARD_LIMIT = 95;
    private static final Duration WARM_UP_TIMEOUT = Duration.ofSeconds(10);
    private static final long KEEP_ALIVE_INTERVAL_MS = 25_000;
    private static final long KEEP_ALIVE_AFTER_IDLE_MS = 20_000;
    private static final long STANDBY_RETRY_DELAY_MS = 10_000;

    /** Creates one independent HttpClient, which owns one connection pool of its own. */
    @FunctionalInterface
    interface ClientFactory
    {
        HttpClient create();
    }

    /** One HTTP connection pool together with the bookkeeping of how much it has been used. */
    static final class Slot
    {
        final HttpClient client;
        final AtomicInteger requests = new AtomicInteger();
        final AtomicInteger inFlight = new AtomicInteger();
        final AtomicBoolean closed = new AtomicBoolean();
        volatile long lastRequestAtNanos;
        volatile boolean retired;

        Slot(HttpClient client)
        {
            this.client = client;
        }

        void recordRequest()
        {
            requests.incrementAndGet();
            lastRequestAtNanos = System.nanoTime();
        }
    }

    private final URI baseUri;
    private final ClientFactory clientFactory;
    private final int requestsPerConnection;
    private final boolean maintenanceEnabled;
    private final CookieManager cookies = new CookieManager(null, CookiePolicy.ACCEPT_ORIGINAL_SERVER);
    private final ScheduledExecutorService maintenance;
    private final Object lock = new Object();

    private Slot active;
    private Slot standby;
    private boolean creatingStandby;
    private boolean closing;
    private int rotations;

    HandyHttpPool(URI baseUri) throws IOException
    {
        this(baseUri, null, DEFAULT_REQUESTS_PER_CONNECTION, true);
    }

    /**
     * @param baseUri               API base URI, normally {@link HandyWifiApi#DEFAULT_BASE_URI}
     * @param clientFactory         creates the independent HTTP clients (tests use this to inspect them)
     * @param requestsPerConnection how many requests may be sent over one connection before it is rotated
     * @param maintenanceEnabled    whether the standby connection is created and kept warm in the background
     */
    HandyHttpPool(URI baseUri, ClientFactory clientFactory, int requestsPerConnection, boolean maintenanceEnabled)
            throws IOException
    {
        String base = baseUri.toString();
        this.baseUri = URI.create(base.endsWith("/") ? base : base + "/");
        this.clientFactory = clientFactory != null ? clientFactory : this::newHttpClient;
        this.requestsPerConnection = Math.max(1, requestsPerConnection);
        this.maintenanceEnabled = maintenanceEnabled;
        this.maintenance = Executors.newSingleThreadScheduledExecutor(runnable ->
        {
            Thread thread = new Thread(runnable, "handy-wifi-http");
            thread.setDaemon(true);
            return thread;
        });
        try
        {
            this.active = openWarmSlot();
        }
        catch (IOException | RuntimeException e)
        {
            maintenance.shutdownNow();
            throw e;
        }
        if (maintenanceEnabled)
        {
            maintenance.scheduleWithFixedDelay(this::keepAlive, KEEP_ALIVE_INTERVAL_MS, KEEP_ALIVE_INTERVAL_MS,
                    TimeUnit.MILLISECONDS);
            scheduleStandbyCreation();
        }
    }

    /** @return the active connection, rotated first when it has served its share of requests */
    Slot acquire() throws IOException
    {
        synchronized (lock)
        {
            if (closing)
            {
                throw new IOException("The Wi-Fi connection to the Handy is closed");
            }
            if (active == null)
            {
                active = openWarmSlot();
            }
            if (active.requests.get() >= requestsPerConnection)
            {
                rotateLocked();
            }
            Slot slot = active;
            slot.recordRequest();
            slot.inFlight.incrementAndGet();
            return slot;
        }
    }

    void release(Slot slot)
    {
        if (slot.inFlight.decrementAndGet() == 0 && slot.retired)
        {
            closeWhenIdle(slot);
        }
    }

    /** Marks a connection that failed as unusable; the next request opens a fresh, warm connection. */
    void invalidate(Slot slot)
    {
        boolean wasActive;
        boolean wasStandby;
        synchronized (lock)
        {
            wasActive = slot == active;
            wasStandby = slot == standby;
            if (wasActive)
            {
                active = null;
            }
            if (wasStandby)
            {
                standby = null;
            }
            slot.retired = true;
        }
        closeWhenIdle(slot);
        if (wasActive || wasStandby)
        {
            scheduleStandbyCreation();
        }
    }

    /** Package-private for tests: how often the active connection has been replaced. */
    int rotations()
    {
        synchronized (lock)
        {
            return rotations;
        }
    }

    @Override
    public void close()
    {
        Slot oldActive;
        Slot oldStandby;
        synchronized (lock)
        {
            if (closing)
            {
                return;
            }
            closing = true;
            oldActive = active;
            oldStandby = standby;
            active = null;
            standby = null;
        }
        maintenance.shutdownNow();
        closeNow(oldActive);
        closeNow(oldStandby);
    }

    private HttpClient newHttpClient()
    {
        return HttpClient.newBuilder()
                .connectTimeout(Duration.ofSeconds(5))
                .version(HttpClient.Version.HTTP_2)
                .followRedirects(HttpClient.Redirect.NORMAL)
                .cookieHandler(cookies)
                .build();
    }

    private Slot openWarmSlot() throws IOException
    {
        Slot slot = new Slot(clientFactory.create());
        try
        {
            warm(slot);
            return slot;
        }
        catch (IOException | RuntimeException e)
        {
            slot.retired = true;
            closeWhenIdle(slot);
            throw e;
        }
    }

    /**
     * Establishes the TCP/TLS/HTTP connection and leaves it open in the client's pool. {@code /servertime} is used
     * because it needs no authentication (and therefore also does not spend a device request).
     */
    private void warm(Slot slot) throws IOException
    {
        HttpRequest request = HttpRequest.newBuilder(baseUri.resolve("servertime"))
                .timeout(WARM_UP_TIMEOUT)
                .header("Accept", "application/json")
                .GET()
                .build();
        slot.recordRequest();
        HttpResponse<Void> response;
        try
        {
            response = slot.client.send(request, HttpResponse.BodyHandlers.discarding());
        }
        catch (InterruptedException e)
        {
            Thread.currentThread().interrupt();
            throw new IOException("Interrupted while connecting to the Handy servers", e);
        }
        if (response.statusCode() < 200 || response.statusCode() >= 300)
        {
            throw new IOException("The Handy servers answered the connection check with HTTP " + response.statusCode());
        }
    }

    /** Replaces the active connection with the warm standby, or opens a new one when no standby is ready. */
    private void rotateLocked() throws IOException
    {
        Slot old = active;
        Slot replacement = standby;
        standby = null;
        if (replacement == null)
        {
            try
            {
                replacement = openWarmSlot();
            }
            catch (IOException | RuntimeException e)
            {
                if (old != null && old.requests.get() >= HARD_LIMIT)
                {
                    throw new IOException("Could not open a new connection to the Handy servers", e);
                }
                // The old connection has not reached the hard limit yet: use it for a few more requests while the
                // standby creation is retried in the background.
                log.warn("Could not prepare a new Handy server connection, keeping the current one: {}",
                        e.getMessage());
                return;
            }
        }
        active = replacement;
        if (old != null)
        {
            old.retired = true;
            closeWhenIdle(old);
            log.debug("Rotated the Handy server connection after {} request(s)", old.requests.get());
        }
        rotations++;
        scheduleStandbyCreation();
    }

    private void scheduleStandbyCreation()
    {
        synchronized (lock)
        {
            if (!maintenanceEnabled || closing || creatingStandby || standby != null)
            {
                return;
            }
            creatingStandby = true;
        }
        try
        {
            maintenance.execute(this::createStandby);
        }
        catch (RejectedExecutionException e)
        {
            // The pool is closing.
            synchronized (lock)
            {
                creatingStandby = false;
            }
        }
    }

    private void createStandby()
    {
        Slot created = null;
        try
        {
            created = openWarmSlot();
        }
        catch (IOException | RuntimeException e)
        {
            log.debug("Could not prepare a warm spare Handy server connection: {}", e.getMessage());
        }
        boolean retry;
        synchronized (lock)
        {
            creatingStandby = false;
            retry = !closing && standby == null;
            if (created != null && standby == null)
            {
                standby = created;
                retry = false;
            }
            else if (created != null)
            {
                created.retired = true;
                closeWhenIdle(created);
            }
        }
        if (retry)
        {
            try
            {
                maintenance.schedule(this::scheduleStandbyCreation, STANDBY_RETRY_DELAY_MS, TimeUnit.MILLISECONDS);
            }
            catch (RejectedExecutionException e)
            {
                // The app is closing.
            }
        }
    }

    /**
     * Keeps the standby connection open while the app is not sending commands, and replaces it when it has served
     * enough requests itself. The active connection is in constant use during a session, and the connection check of
     * the app keeps it warm while the session is running.
     */
    private void keepAlive()
    {
        Slot toWarm;
        synchronized (lock)
        {
            if (closing)
            {
                return;
            }
            toWarm = standby;
            if (toWarm != null && toWarm.requests.get() >= requestsPerConnection)
            {
                standby = null;
                toWarm.retired = true;
                closeWhenIdle(toWarm);
                scheduleStandbyCreation();
                toWarm = null;
            }
            else if (toWarm != null
                    && System.nanoTime() - toWarm.lastRequestAtNanos < KEEP_ALIVE_AFTER_IDLE_MS * 1_000_000L)
            {
                toWarm = null;
            }
        }
        if (toWarm == null)
        {
            return;
        }
        try
        {
            warm(toWarm);
        }
        catch (IOException | RuntimeException e)
        {
            log.debug("Could not keep the warm Handy server connection alive: {}", e.getMessage());
            invalidate(toWarm);
        }
    }

    private void closeWhenIdle(Slot slot)
    {
        if (slot == null || slot.inFlight.get() != 0)
        {
            return;
        }
        if (!slot.closed.compareAndSet(false, true))
        {
            return;
        }
        Thread thread = new Thread(() ->
        {
            try
            {
                slot.client.close();
            }
            catch (Exception e)
            {
                log.debug("Could not close a Handy server connection: {}", e.getMessage());
            }
        }, "handy-wifi-http-close");
        thread.setDaemon(true);
        thread.start();
    }

    private void closeNow(Slot slot)
    {
        if (slot == null || !slot.closed.compareAndSet(false, true))
        {
            return;
        }
        try
        {
            slot.client.close();
        }
        catch (Exception e)
        {
            log.debug("Could not close a Handy server connection: {}", e.getMessage());
        }
    }
}
