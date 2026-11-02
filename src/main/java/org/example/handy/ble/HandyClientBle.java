package org.example.handy.ble;

import dev.handy.proto.HandyRpc;
import dev.handy.proto.Messages;
import lombok.extern.slf4j.Slf4j;
import org.example.diagnostics.DiagnosticTrace;
import org.example.handy.common.*;
import org.example.handy.common.dto.SliderSettingsResult;
import org.example.handy.common.dto.SliderState;

import java.util.Optional;
import java.util.concurrent.TimeoutException;

@Slf4j
public class HandyClientBle extends HandyClient
{
    /** How long the device gets to answer the first requests of a new connection. */
    private static final long HANDSHAKE_TIMEOUT_MS = 5_000;

    private final HandyBleAdapter ble;
    private final HandyRpcClient rpc;
    private final RateLimitedLogger notificationWarnings = new RateLimitedLogger(log);
    /** One failed HDSP command produces one log line per try, which would flood the log while the device is broken. */
    private final RateLimitedLogger commandWarnings = new RateLimitedLogger(log);
    /**
     * False when the device failed the start-up check. The optional queries then fail immediately instead of waiting
     * for their timeouts (the movement commands are fire-and-forget and are still sent).
     */
    private volatile boolean responsive = true;
    /** Time of the last XPT command, used to say how long after it a notification arrived. */
    private volatile long lastXptAtNanos;
    private volatile float lastXp = Float.NaN;
    private volatile int lastT;
    private volatile boolean lastStop;

    public HandyClientBle(HandyBleAdapter ble)
    {
        this.ble = ble;
        this.rpc = new HandyRpcClient(ble);
        this.rpc.setNotificationListener(this::onNotification);
    }

    /**
     * Makes sure the connected device actually answers requests, and logs what it is. A Handy that advertises and
     * connects but does not answer (still waking up, in Wi-Fi mode, with a stale Bluetooth cache, or not a Handy at
     * all) would otherwise fail later on some request with a timeout that says nothing about the cause. The movement
     * commands themselves are fire-and-forget, so a device that answers this check can be driven.
     */
    public void verifyResponsive()
    {
        verifyResponsive(HANDSHAKE_TIMEOUT_MS);
    }

    /** Package-private variant that takes the timeout explicitly, used by the tests. */
    void verifyResponsive(long timeoutMs)
    {
        log.info("Checking that the Handy answers (device '{}', MTU {} bytes)...", ble.getPeripheralName(), ble.getMtu());
        try
        {
            HandyRpc.Response mode = rpc.sendRequest(HandyRpc.Request.newBuilder()
                    .setRequestModeGet(Messages.RequestModeGet.getDefaultInstance()), timeoutMs);
            responsive = true;
            log.info("Handy answered: mode={}", mode.getResponseModeGet().getMode());
        }
        catch (TimeoutException e)
        {
            responsive = false;
            throw new IllegalStateException("The Handy is connected but does not answer. Power-cycle it"
                    + " and check that its LED is blue (Bluetooth mode)"
                    + " then press Start again.", e);
        }
        catch (HandyDeviceException e)
        {
            // The device answered, it just did not accept this request: the optional queries still make sense.
            throw new IllegalStateException("The Handy answered the connection but refused a request: %s"
                    .formatted(e.getMessage()), e);
        }
        catch (Exception e)
        {
            responsive = false;
            throw new IllegalStateException("Could not talk to the Handy over Bluetooth: %s".formatted(e.getMessage()), e);
        }

        try
        {
            HandyRpc.Response response = rpc.sendRequest(HandyRpc.Request.newBuilder()
                    .setRequestCapabilitiesGet(Messages.RequestCapabilitiesGet.getDefaultInstance()), timeoutMs);
            if (response.hasResponseCapabilitiesGet())
            {
                var capabilities = response.getResponseCapabilitiesGet();
                log.info("Handy capabilities: sliders={}, battery={}, batteryDriver={}, bleMtu={}, vulvaOriented={}",
                        capabilities.getSlider(), capabilities.getBattery(), capabilities.getBatteryDriver(),
                        capabilities.getBleMtu(), capabilities.getVulvaOriented());
                if (capabilities.getSlider() == 0)
                {
                    log.warn("The connected device reports no slider. The app needs a Handy; another Ohdoki device"
                            + " nearby (an Oh!) can be picked up by the scan as well.");
                }
            }
        }
        catch (Exception e)
        {
            // Only the answer to the mode request above matters; the details are a bonus for the log.
            log.warn("Could not read the Handy capabilities: {}", e.getMessage());
        }
    }

    @Override
    public void close()
    {
        // Fails the requests that still wait for a response before the connection goes away
        rpc.close();
        ble.disconnect();
    }

    @Override
    public HandyBaseResponseWithError changeMode(DeviceMode mode)
    {
        // BLE does not require explicit mode change - mode is implicit in the commands sent
        log.debug("BLE: skipping changeMode({}), mode is implicit", mode);
        return new HandyBaseResponseWithError(null);
    }

    @Override
    public boolean checkConnectionStatus()
    {
        return ble.isConnected();
    }

    @Override
    public String getDeviceIdentifier()
    {
        return ble.getIdentifier();
    }

    @Override
    public HandyBaseResponseWithError hdspXpt(float xp, int t, boolean stopOnTarget)
    {
        try
        {
            // Remember the command before writing it, so that a notification which arrives while the write is
            // still in flight is already attributed to it.
            lastXp = xp;
            lastT = t;
            lastStop = stopOnTarget;
            lastXptAtNanos = System.nanoTime();
            // Fire-and-forget: HDSP commands are sent continuously, waiting for a response would stall the stream.
            // The position is already normalized (0.0-1.0) by the processor, which owns the HDSP position unit.
            rpc.sendRequestFireAndForget(HandyRpc.Request.newBuilder()
                    .setRequestHdspXpTSet(Messages.RequestHdspXpTSet.newBuilder()
                            .setXp(xp)
                            .setT(t)
                            .setStopOnTarget(stopOnTarget)
                            .build()));

            DiagnosticTrace trace = DiagnosticTrace.active();
            if (trace != null)
            {
                trace.event("xpt", "xp", xp, "t", t, "stopOnTarget", stopOnTarget);
            }
            log.trace("[BLE HDSP] Sent XPT (xp={}, t={}ms, stopOnTarget={})", xp, t, stopOnTarget);
            return new HandyBaseResponseWithError(null);
        }
        catch (Exception e)
        {
            commandWarnings.error("Exception when sending BLE HDSP XPT command: " + e.getMessage());
            return new HandyBaseResponseWithError(new HandyError(0, "HDSP_XPT_FAILED", e.getMessage(), true));
        }
    }

    @Override
    public void hdspStop()
    {
        try
        {
            rpc.sendRequestFireAndForget(HandyRpc.Request.newBuilder()
                    .setRequestHdspStop(Messages.RequestHdspStop.newBuilder().build()));
            log.debug("[BLE HDSP] Sent stop");
        }
        catch (Exception e)
        {
            log.warn("Could not stop the HDSP move: {}", e.getMessage());
        }
    }

    @Override
    public void setSliderSettings(Float min, Float max)
    {
        try
        {
            log.info("BLE SliderSet...");

            HandyRpc.Response resp = rpc.sendRequest(HandyRpc.Request.newBuilder()
                    .setRequestSliderStrokeSet(Messages.RequestSliderStrokeSet.newBuilder()
                            .setMin(min)
                            .setMax(max)
                            .build()));

            if (resp.hasResponseSliderStrokeSet())
            {
                var state = resp.getResponseSliderStrokeSet();
                log.info("SliderSet Response {}", state);
            }
        }
        catch (Exception e)
        {
            throw new RuntimeException("BLE SliderSet failed", e);
        }
    }

    @Override
    public Optional<SliderSettingsResult> getSliderSettings()
    {
        try
        {
            log.info("BLE SliderGet...");

            HandyRpc.Response resp = rpc.sendRequest(HandyRpc.Request.newBuilder()
                    .setRequestSliderStrokeGet(Messages.RequestSliderStrokeGet.newBuilder()
                            .build()));

            if (resp.hasResponseSliderStrokeGet())
            {
                var state = resp.getResponseSliderStrokeGet();
                log.info("SliderGet Response {}", state);
                return Optional.of(new SliderSettingsResult(String.valueOf(state.getMin()), String.valueOf(state.getMax()),
                        state.getMinAbsolute(), state.getMaxAbsolute()));
            }
            return Optional.empty();
        }
        catch (Exception e)
        {
            throw new RuntimeException("BLE SliderGet failed", e);
        }
    }

    @Override
    public Optional<SliderState> getSliderState()
    {
        try
        {
            HandyRpc.Response resp = rpc.sendRequest(HandyRpc.Request.newBuilder()
                    .setRequestSliderStateGet(Messages.RequestSliderStateGet.getDefaultInstance()));
            if (resp.hasResponseSliderStateGet())
            {
                var state = resp.getResponseSliderStateGet();
                return Optional.of(new SliderState(state.getPosition(), state.getPositionAbsolute(), state.getSpeedAbsolute()));
            }
            return Optional.empty();
        }
        catch (Exception e)
        {
            throw new RuntimeException("BLE SliderStateGet failed", e);
        }
    }

    /**
     * Handles the notifications the device pushes. The low-memory and error ones explain why commands are dropped
     * or responses are missing, so they are reported instead of being thrown away by whichever request happens to
     * be waiting.
     */
    private void onNotification(HandyRpc.Notification notification)
    {
        long sinceXptMs = lastXptAtNanos == 0 ? -1 : (System.nanoTime() - lastXptAtNanos) / 1_000_000L;
        String sinceXpt = sinceXptMs < 0
                ? "no XPT yet"
                : "+%dms after XPT xp=%s t=%dms stopOnTarget=%s".formatted(sinceXptMs, lastXp, lastT, lastStop);
        DiagnosticTrace trace = DiagnosticTrace.active();
        if (trace != null)
        {
            trace.event("notification",
                    "notification", notification.getNotificationCase().name(),
                    "sinceXptMs", sinceXptMs,
                    "lastXp", lastXptAtNanos == 0 ? null : lastXp,
                    "lastT", lastXptAtNanos == 0 ? null : lastT,
                    "lastStopOnTarget", lastXptAtNanos == 0 ? null : lastStop);
        }
        switch (notification.getNotificationCase())
        {
            case NOTIFICATION_BLE_STATUS_CHANGED -> log.info("[BLE] Device BLE state: {}",
                    notification.getNotificationBleStatusChanged().getState());
            case NOTIFICATION_HDSP_CHANGED -> log.debug("[BLE] Notification: NOTIFICATION_HDSP_CHANGED ({})", sinceXpt);
            case NOTIFICATION_MODE_CHANGED -> log.debug("[BLE] Notification: NOTIFICATION_MODE_CHANGED ({})", sinceXpt);
            case NOTIFICATION_STROKE_CHANGED -> log.debug("[BLE] Notification: NOTIFICATION_STROKE_CHANGED ({})", sinceXpt);
            case NOTIFICATION_LOW_MEMORY_ERROR -> notificationWarnings.warn(
                    "[BLE] Device dropped a message of %d bytes because it ran out of memory (available heap %d, largest free block %d)"
                            .formatted(notification.getNotificationLowMemoryError().getDiscardedMsgSize(),
                                    notification.getNotificationLowMemoryError().getAvailableHeap(),
                                    notification.getNotificationLowMemoryError().getLargestFreeBlock()));
            case NOTIFICATION_LOW_MEMORY_WARNING -> notificationWarnings.warn(
                    "[BLE] Device memory is running low (available heap %d)".formatted(notification.getNotificationLowMemoryWarning().getAvailableHeap()));
            case NOTIFICATION_ERROR -> notificationWarnings.warn(
                    "[BLE] Device reported an error: code=%d msg=%s".formatted(notification.getNotificationError().getCode(),
                            notification.getNotificationError().getMessage()));
            case NOTIFICATION_TEMP_HIGH -> notificationWarnings.warn("[BLE] Device is overheating and may stop moving");
            case NOTIFICATION_SLIDER_BLOCKED -> notificationWarnings.warn("[BLE] Slider is blocked ({})".formatted(sinceXpt));
            case NOTIFICATION_IDLE_TIMEOUT -> log.warn("[BLE] Device is idle and will disconnect/sleep soon ({})", sinceXpt);
            // Seen at DEBUG, so that a log shows which notifications arrived without flooding it during a session.
            default -> log.debug("[BLE] Notification: {} ({})", notification.getNotificationCase(), sinceXpt);
        }
    }
}
