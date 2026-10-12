package org.example.processor;

import lombok.extern.slf4j.Slf4j;
import org.example.config.ConfigProperties;
import org.example.handy.common.DeviceMode;
import org.example.handy.common.HandyBaseResponseWithError;
import org.example.handy.common.HandyClient;
import org.example.handy.common.RateLimitedLogger;
import org.example.handy.common.dto.MovementPoint;
import org.example.handy.common.dto.SliderSettingsResult;

import java.util.List;
import java.util.Optional;

/**
 * HDSP (Handy Direct Streaming Protocol) processor. HDSP has no device-side scheduling: the newest position
 * is sent together with the time the device should take to reach it (an XPT command). Because of that it is a best-effort, low-latency protocol that works best over a direct Bluetooth
 * connection (through Intiface every move is sent as a Buttplug LinearCmd).
 * <p>
 * The positions used inside the app are percentages (0-100), but HDSP works with normalized positions
 * (0.0-1.0 of the configured stroke zone). The conversion is done here, before the command is handed to a
 * client, because it is a property of the protocol and not of the connection the command is sent over.
 */
@Slf4j
public class HdspParameterProcessor extends AbstractStreamingParameterProcessor
{
    /**
     * HDSP sends one command per position, so a minimum interval is
     * enforced to avoid flooding the BLE connection. 20ms keeps the movement smooth without saturating BLE.
     */
    public static final int HDSP_MIN_SEND_INTERVAL_MS = 20;
    /**
     * Upper bound for a single move. A longer gap between updates is handled by the idle stop and by the
     * "point after stopping" rule, so a move should never need to be longer than the idle timeout.
     */
    public static final int HDSP_MAX_MOVE_DURATION_MS = 200;
    /** Input is considered stopped (and the device is told to stop) when no new point was produced for this long. */
    public static final int HDSP_IDLE_STOP_TIMEOUT_MS = 150;

    private MovementPoint lastSentPoint;
    /** True after an idle stop was sent and until the next point is sent. */
    private boolean stopped;
    private boolean pauseOnStarving;
    /** One command is sent per position, so an out of range position would otherwise flood the log. */
    private final RateLimitedLogger positionWarnings = new RateLimitedLogger(log);

    // Tests only
    HdspParameterProcessor(ConfigProperties config)
    {
        super(null, config);
    }

    public HdspParameterProcessor(HandyClient handyClient, ConfigProperties config)
    {
        super(handyClient, config);
        this.pauseOnStarving = config.pauseOnStarving();

        HandyBaseResponseWithError response = handyClient.changeMode(DeviceMode.HDSP);
        if (response.error() != null)
        {
            throw new IllegalStateException("Could not change Handy mode to HDSP (reason: %s)".formatted(response.error().message()));
        }

        if (config.sliderMin() != null || config.sliderMax() != null)
        {
            handyClient.setSliderSettings(config.sliderMin(), config.sliderMax());
        }
        Optional<SliderSettingsResult> sliderSettings = handyClient.getSliderSettings();
        sliderSettings.ifPresent(s -> log.info("Slider settings min={}, max={}", s.min(), s.max()));
    }

    @Override
    protected long getSendIntervalMs()
    {
        return Math.max(super.getSendIntervalMs(), HDSP_MIN_SEND_INTERVAL_MS);
    }

    @Override
    protected long trySendingMessage(long lastMessageSentMs)
    {
        long now = System.currentTimeMillis();

        if (!points.isEmpty())
        {
            return sendNewestPoint(now);
        }

        // No points waiting: when the input goes quiet, tell the device to stop at the last known position.
        // Commented out because it makes slider bounce back too much
        // if (shouldStopForIdle(now))
        // {
        //     sendIdleStop();
        //     return now;
        // }
        return lastMessageSentMs;
    }

    private long sendNewestPoint(long now)
    {
        List<MovementPoint> drainedPoints = drainPoints();
        MovementPoint targetPoint = drainedPoints.getLast();

        boolean afterIdleStop = stopped;
        Integer lastSentPointTime = lastSentPoint != null ? lastSentPoint.t() : null;
        int durationMs = resolveMoveDurationMs(lastSentPointTime, targetPoint.t(), getSendIntervalMs(), afterIdleStop);

        sendHdspMove(targetPoint, durationMs, pauseOnStarving);
        lastSentPoint = targetPoint;
        lastPointActivityMs = now;
        stopped = false;
        return now;
    }

    private boolean shouldStopForIdle(long now)
    {
        // lastPointActivityMs is refreshed when a point is generated and when a movement is sent
        return !stopped
                && lastSentPoint != null
                && (now - lastPointActivityMs) >= HDSP_IDLE_STOP_TIMEOUT_MS;
    }

    private void sendIdleStop()
    {
        int durationMs = clampDuration(getSendIntervalMs());
        log.debug("[HDSP] No new points for {}ms, stopping at last known position {}%",
                HDSP_IDLE_STOP_TIMEOUT_MS, lastSentPoint.x());
        sendHdspMove(lastSentPoint, durationMs, true);
        stopped = true;
    }

    /**
     * @param lastSentPointTime timestamp of the previously sent position (null when nothing was sent yet)
     * @param targetPointTime timestamp of the position that should be reached now
     * @param sendIntervalMs effective minimum interval between two commands
     * @param afterIdleStop whether the target is the first point after an idle stop
     * @return time in milliseconds the device should take to reach the target position
     */
    static int resolveMoveDurationMs(Integer lastSentPointTime, int targetPointTime, long sendIntervalMs, boolean afterIdleStop)
    {
        if (lastSentPointTime == null || afterIdleStop)
        {
            // First point and first point after a stop: there is no usable previous timing to derive a
            // duration from (and the timeline is broken by the stop), so fall back to the fixed send interval.
            return clampDuration(sendIntervalMs);
        }
        return clampDuration(targetPointTime - lastSentPointTime);
    }

    private static int clampDuration(long durationMs)
    {
        return Math.clamp(durationMs, 1, HDSP_MAX_MOVE_DURATION_MS);
    }

    private void sendHdspMove(MovementPoint targetPoint, int durationMs, boolean stopOnTarget)
    {
        try
        {
            // Positions leave the processor normalized (0.0-1.0), which is the unit HDSP uses, so that no client
            // has to know about the percentage positions used inside the app.
            float normalizedXp = toNormalizedPosition(targetPoint.x());
            HandyBaseResponseWithError response = handyClient.hdspXpt(normalizedXp, durationMs, stopOnTarget);
            if (response.error() != null)
            {
                log.error("Error when sending HDSP command to Handy! (reason: {})", response.error().message());
            }
            else
            {
                log.trace("[HDSP] Successfully sent command (xp={} ({}%), t={}ms, stopOnTarget={})",
                        normalizedXp, targetPoint.x(), durationMs, stopOnTarget);
            }
        }
        catch (Exception e)
        {
            log.error("Exception when sending HDSP command: {}", e.getMessage());
        }
    }

    /**
     * Converts a percentage position (0-100, the unit used inside the app) into the normalized position
     * (0.0-1.0 of the stroke zone) that the HDSP protocol uses.
     * <p>
     * The device silently caps positions outside its range, so an out of range position (which out of range OSC
     * values can produce) would end up as a move to the end of the stroke instead of the requested one; such
     * positions are therefore clamped and reported.
     *
     * @param percentPosition position in the 0-100 range
     * @return the same position in the 0.0-1.0 range
     */
    float toNormalizedPosition(float percentPosition)
    {
        if (percentPosition < 0f || percentPosition > 100f)
        {
            positionWarnings.warn("[HDSP] Position %s%% is outside the 0-100%% range, clamping it".formatted(percentPosition));
        }
        return Math.clamp(percentPosition / 100f, 0f, 1f);
    }
}
