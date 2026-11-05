package org.example.handy.wifi;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import lombok.extern.slf4j.Slf4j;
import org.example.diagnostics.DiagnosticTrace;
import org.example.handy.common.*;
import org.example.handy.common.dto.SliderSettingsResult;
import org.example.handy.common.dto.SliderState;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;

/**
 * Drives the Handy through the Handy servers (Handy API v3 over HTTPS), using HDSP just like the Bluetooth client.
 * <p>
 * Movement commands are queued instead of being sent on the caller's thread: the processor produces a new position
 * every 20-50 ms, while one HTTPS round trip is slower than that. The queue holds only the newest command, so the
 * device always moves to the latest position and stale positions are dropped instead of being replayed late.
 * <p>
 * A movement command that fails on the network is retried once on a fresh connection and, when it still fails, logged
 * and skipped; the app's regular connection check stops the session when the device is really gone.
 * {@link #hdspStop()} is sent through the same queue, so it cannot overtake a command that is still in flight.
 */
@Slf4j
public class HandyWifiClient extends HandyClient
{
    /** Handy API v3 device mode for HDSP. */
    private static final int HDSP_MODE = 2;
    /** How long a stop waits for a movement command that is already in flight. */
    private static final long STOP_TIMEOUT_SECONDS = 5;

    private final HandyWifiApi api;
    private final String connectionKey;
    private final ObjectMapper mapper = new ObjectMapper();
    private final XptPump pump;
    private final AtomicBoolean closed = new AtomicBoolean();
    private final RateLimitedLogger commandWarnings = new RateLimitedLogger(log);
    private final RateLimitedLogger statusWarnings = new RateLimitedLogger(log);

    HandyWifiClient(HandyWifiApi api, String connectionKey)
    {
        this.api = api;
        this.connectionKey = connectionKey;
        this.pump = new XptPump();
        this.pump.start();
    }

    /**
     * Checks that the device is online at the Handy servers and that the connection key and application ID are
     * accepted. Called by the connector before the client is handed to the app, so that a bad key is reported as a
     * clear start-up error instead of a session that silently does nothing.
     */
    public void verifyConnected() throws IOException
    {
        JsonNode response = api.get("connected");
        boolean connected = HandyWifiApi.result(response).path("connected").asBoolean(false);
        if (!connected)
        {
            throw new IOException("The Handy is not connected to the Handy servers. Turn on the Handy, connect it"
                    + " to Wi-Fi in the Handy app (or on handyfeeling.com), check the connection key, and press"
                    + " Start again.");
        }
        log.info("Handy connected through the Handy servers");
        try
        {
            JsonNode info = HandyWifiApi.result(api.get("info"));
            log.info("Handy firmware {}, model {}", info.path("fw_version").asText("unknown"),
                    info.path("hw_model_name").asText("unknown"));
            if (info.path("fw_status").asInt(0) == 2)
            {
                log.warn("The Handy firmware is too old for the Wi-Fi connection: the Handy API v3 needs Firmware 4."
                        + " Update the Handy in the Handy app.");
            }
        }
        catch (IOException | RuntimeException e)
        {
            // The connection itself works; the details are only a bonus for the log.
            log.warn("Could not read the Handy information: {}", e.getMessage());
        }
    }

    @Override
    public HandyBaseResponseWithError changeMode(DeviceMode mode)
    {
        if (mode != DeviceMode.HDSP)
        {
            return new HandyBaseResponseWithError(new HandyError(0, "UNSUPPORTED_MODE",
                    "The Wi-Fi connection only supports HDSP.", true));
        }
        try
        {
            ObjectNode body = mapper.createObjectNode().put("mode", HDSP_MODE);
            api.put("mode2", body);
            // A new processor starts the movement again: allow HDSP commands after a previous stop.
            pump.resume();
            log.info("Handy switched to HDSP through the Handy servers");
            return new HandyBaseResponseWithError(null);
        }
        catch (HandyWifiApi.HandyApiException e)
        {
            commandWarnings.error("Could not switch the Handy to HDSP: " + e.getMessage());
            return new HandyBaseResponseWithError(e.error());
        }
        catch (IOException e)
        {
            commandWarnings.error("Could not switch the Handy to HDSP: " + e.getMessage());
            return new HandyBaseResponseWithError(new HandyError(0, "WIFI_MODE_FAILED", e.getMessage(), false));
        }
    }

    @Override
    public boolean checkConnectionStatus()
    {
        try
        {
            return HandyWifiApi.result(api.get("connected")).path("connected").asBoolean(false);
        }
        catch (IOException | RuntimeException e)
        {
            statusWarnings.warn("Could not check the connection to the Handy servers: " + e.getMessage());
            return false;
        }
    }

    @Override
    public String getDeviceIdentifier()
    {
        // Do not expose the connection key in the calibration profile or in a log. The hash is stable, so the
        // same device still gets the same profile, and a Bluetooth profile is not mixed up with a Wi-Fi one.
        return connectionKey == null || connectionKey.isBlank() ? "" : "wifi:" + fingerprint(connectionKey);
    }

    private static String fingerprint(String text)
    {
        try
        {
            byte[] digest = MessageDigest.getInstance("SHA-256").digest(text.getBytes(StandardCharsets.UTF_8));
            var hex = new StringBuilder();
            for (int i = 0; i < 6; i++)
            {
                hex.append("%02x".formatted(digest[i]));
            }
            return hex.toString();
        }
        catch (NoSuchAlgorithmException e)
        {
            // SHA-256 is part of every Java runtime; this only keeps the code compiling on an exotic one.
            return Integer.toHexString(text.hashCode());
        }
    }

    @Override
    public void setSliderSettings(Float min, Float max)
    {
        float low = min == null ? 0f : Math.clamp(min, 0f, 1f);
        float high = max == null ? 1f : Math.clamp(max, 0f, 1f);
        if (high <= low)
        {
            throw new IllegalArgumentException("The top of the stroke range has to be above the bottom");
        }
        try
        {
            api.put("slider/stroke", mapper.createObjectNode().put("min", low).put("max", high));
            log.info("Wi-Fi slider stroke set to {} - {}", low, high);
        }
        catch (IOException e)
        {
            throw new RuntimeException("Could not set the stroke range through the Handy servers: " + e.getMessage(), e);
        }
    }

    @Override
    public Optional<SliderSettingsResult> getSliderSettings()
    {
        try
        {
            JsonNode result = HandyWifiApi.result(api.get("slider/stroke"));
            if (!result.hasNonNull("min") || !result.hasNonNull("max"))
            {
                return Optional.empty();
            }
            return Optional.of(new SliderSettingsResult(result.get("min").asText(), result.get("max").asText(),
                    floatOrNull(result, "min_absolute"), floatOrNull(result, "max_absolute")));
        }
        catch (IOException e)
        {
            throw new RuntimeException("Could not read the stroke range through the Handy servers: " + e.getMessage(), e);
        }
    }

    @Override
    public Optional<SliderState> getSliderState()
    {
        try
        {
            JsonNode result = HandyWifiApi.result(api.get("slider/state"));
            if (!result.hasNonNull("position"))
            {
                return Optional.empty();
            }
            return Optional.of(new SliderState(
                    (float) result.path("position").asDouble(),
                    (float) result.path("position_absolute").asDouble(),
                    (float) result.path("speed_absolute").asDouble()));
        }
        catch (IOException e)
        {
            throw new RuntimeException("Could not read the slider position through the Handy servers: " + e.getMessage(), e);
        }
    }

    @Override
    public HandyBaseResponseWithError hdspXpt(float xp, int t, boolean stopOnTarget)
    {
        if (closed.get())
        {
            return new HandyBaseResponseWithError(new HandyError(0, "WIFI_CLOSED",
                    "The Wi-Fi connection is closed", false));
        }
        float position = Math.clamp(xp, 0f, 1f);
        int durationMs = Math.max(1, t);
        pump.submit(position, durationMs, stopOnTarget);
        DiagnosticTrace trace = DiagnosticTrace.active();
        if (trace != null)
        {
            trace.event("xpt", "xp", position, "t", durationMs, "stopOnTarget", stopOnTarget);
        }
        log.trace("[Wi-Fi HDSP] Queued XPT (xp={}, t={}ms, stopOnTarget={})", position, durationMs, stopOnTarget);
        return new HandyBaseResponseWithError(null);
    }

    @Override
    public void hdspStop()
    {
        if (closed.get())
        {
            return;
        }
        pump.stop(STOP_TIMEOUT_SECONDS, TimeUnit.SECONDS);
    }

    @Override
    public void close()
    {
        if (!closed.compareAndSet(false, true))
        {
            return;
        }
        pump.close();
        api.close();
    }

    private static Float floatOrNull(JsonNode node, String field)
    {
        JsonNode value = node.get(field);
        return value == null || !value.isNumber() ? null : (float) value.asDouble();
    }

    /** Newest-command-wins queue that sends the HDSP commands one after another. */
    private final class XptPump implements AutoCloseable
    {
        private final AtomicReference<Command> pending = new AtomicReference<>();
        private final Object signal = new Object();
        private final Thread thread;
        private volatile boolean stopped;
        /** True from {@link #stop} until the next {@link #resume}: no new movement command may overtake a stop. */
        private volatile boolean stopping;

        XptPump()
        {
            thread = new Thread(this::run, "handy-wifi-xpt");
            thread.setDaemon(true);
        }

        void start()
        {
            thread.start();
        }

        void resume()
        {
            stopping = false;
        }

        void submit(float xp, int t, boolean stopOnTarget)
        {
            if (stopped || stopping)
            {
                return;
            }
            pending.set(new Xpt(xp, t, stopOnTarget));
            synchronized (signal)
            {
                signal.notifyAll();
            }
        }

        /** Queues the stop behind a command that may still be in flight, then waits (briefly) for it. */
        void stop(long timeout, TimeUnit unit)
        {
            if (stopped)
            {
                return;
            }
            stopping = true;
            Stop command = new Stop();
            Command previous = pending.getAndSet(command);
            if (previous instanceof Stop earlier)
            {
                earlier.done.complete(null);
            }
            synchronized (signal)
            {
                signal.notifyAll();
            }
            try
            {
                command.done.get(timeout, unit);
            }
            catch (TimeoutException e)
            {
                log.warn("The stop command to the Handy servers is taking longer than expected");
            }
            catch (InterruptedException e)
            {
                Thread.currentThread().interrupt();
            }
            catch (Exception e)
            {
                log.debug("Could not stop the Handy through the Handy servers: {}", e.getMessage());
            }
        }

        private void run()
        {
            while (!stopped)
            {
                Command command = pending.getAndSet(null);
                if (command == null)
                {
                    synchronized (signal)
                    {
                        if (pending.get() == null && !stopped)
                        {
                            try
                            {
                                signal.wait(500);
                            }
                            catch (InterruptedException e)
                            {
                                Thread.currentThread().interrupt();
                                return;
                            }
                        }
                    }
                    continue;
                }
                if (command instanceof Xpt xpt)
                {
                    sendXpt(xpt);
                }
                else if (command instanceof Stop stop)
                {
                    try
                    {
                        sendStop();
                    }
                    finally
                    {
                        stop.done.complete(null);
                    }
                }
            }
            Command remaining = pending.getAndSet(null);
            if (remaining instanceof Stop stop)
            {
                stop.done.complete(null);
            }
        }

        private void sendXpt(Xpt xpt)
        {
            Command newer = pending.get();
            if (newer != null)
            {
                // A newer position or a stop is already waiting. Sending this stale command first would only add
                // latency; the latest position wins.
                log.trace("[Wi-Fi HDSP] Skipped a stale XPT (xp={})", xpt.xp());
                return;
            }
            ObjectNode body = mapper.createObjectNode()
                    // The API docs describe xp as a percent, but the Wi-Fi API actually accepts the normalized
                    // position (0 = bottom, 1 = top), like the Bluetooth client. The processor already passes
                    // the position normalized, so only the Wi-Fi client uses it as it is.
                    .put("xp", xpt.xp())
                    .put("t", xpt.t())
                    .put("stop_on_target", xpt.stopOnTarget())
                    .put("immediate_rsp", true);
            try
            {
                api.put("hdsp/xpt", body);
            }
            catch (IOException e)
            {
                if (stopped || Thread.currentThread().isInterrupted())
                {
                    log.debug("Dropped a movement command while closing the Wi-Fi connection");
                }
                else
                {
                    commandWarnings.error("Could not send a movement command to the Handy servers: " + e.getMessage());
                }
            }
        }

        private void sendStop()
        {
            try
            {
                api.put("hdsp/stop", mapper.createObjectNode());
            }
            catch (HandyWifiApi.HandyApiException e)
            {
                if (e.status() == 404)
                {
                    // The stop endpoint is not part of the published spec. If a server ever removes it, switch the
                    // device to idle instead (the next session puts it back into HDSP).
                    try
                    {
                        api.put("mode2", mapper.createObjectNode().put("mode", 7));
                        return;
                    }
                    catch (IOException fallbackError)
                    {
                        commandWarnings.warn("Could not stop the Handy through the Handy servers: "
                                + fallbackError.getMessage());
                        return;
                    }
                }
                commandWarnings.warn("Could not stop the Handy through the Handy servers: " + e.getMessage());
            }
            catch (IOException e)
            {
                if (stopped || Thread.currentThread().isInterrupted())
                {
                    log.debug("Dropped the stop command while closing the Wi-Fi connection");
                }
                else
                {
                    commandWarnings.warn("Could not stop the Handy through the Handy servers: " + e.getMessage());
                }
            }
        }

        @Override
        public void close()
        {
            stopped = true;
            synchronized (signal)
            {
                signal.notifyAll();
            }
            thread.interrupt();
            try
            {
                thread.join(2_000);
            }
            catch (InterruptedException e)
            {
                Thread.currentThread().interrupt();
            }
        }
    }

    private sealed interface Command permits Xpt, Stop
    {
    }

    private record Xpt(float xp, int t, boolean stopOnTarget) implements Command
    {
    }

    private static final class Stop implements Command
    {
        final CompletableFuture<Void> done = new CompletableFuture<>();
    }
}
