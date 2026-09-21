package org.example.processor;

import handy.model.DeviceModeValue;
import lombok.extern.slf4j.Slf4j;
import org.example.config.ConfigProperties;
import org.example.handy.common.HandyBaseResponseWithError;
import org.example.handy.common.HandyClient;
import org.example.handy.common.MessageDelayStats;
import org.example.handy.common.dto.MovementPoint;
import org.example.handy.common.dto.SliderSettingsResult;

import java.util.List;
import java.util.Optional;

import static org.example.Main.delayedClosingWithLog;

/**
 * HDSP (Handy Direct Streaming Protocol) processor. Unlike {@link HspParameterProcessor}, which uploads
 * timestamped point batches that the device schedules itself (HSP), HDSP has no device-side scheduling:
 * the newest position is sent together with the time the device should take to reach it (an XPT command).
 * Because of that it is a best-effort, low-latency protocol that only makes sense over a direct Bluetooth
 * connection, so it is rejected for API connections.
 * <p>
 * When {@code hdspTiming} is enabled the processor additionally schedules each command so that it reaches the
 * device right when the previously requested move finishes, avoiding interrupting a move that is still running.
 */
@Slf4j
public class HdspParameterProcessor extends AbstractStreamingParameterProcessor
{
    /**
     * HDSP sends one command per position (instead of the point batches HSP uses), so a minimum interval is
     * enforced to avoid flooding the BLE connection. 20ms keeps the movement smooth without saturating BLE.
     */
    public static final int HDSP_MIN_SEND_INTERVAL_MS = 20;
    /**
     * Upper bound for a single move. A longer gap between updates is handled by the idle stop and by the
     * "point after stopping" rule, so a move should never need to be longer than the idle timeout.
     */
    public static final int HDSP_MAX_MOVE_DURATION_MS = 200;
    /** Input is considered stopped (and the device is told to stop) when no new point was produced for this long. */
    public static final int HDSP_IDLE_STOP_TIMEOUT_MS = 200;
    /** How often the send loop wakes up while timing is enabled (to hit the scheduled send closely). */
    private static final int HDSP_TIMING_POLL_MS = 5;
    /** Upper bound for the delay-jitter safety margin, so a noisy measurement cannot add huge gaps. */
    private static final long HDSP_MAX_JITTER_MARGIN_MS = 50;

    private final boolean hdspTiming;
    /** Measured one-way delay to the device (0 when timing is disabled or could not be measured). */
    private long messageDelayMs;
    /** Measured delay variation used as a safety margin when scheduling (0 when timing is disabled). */
    private long messageJitterMarginMs;

    private MovementPoint lastSentPoint;
    /** True after an idle stop was sent and until the next point is sent. */
    private boolean stopped;
    /** App-time from which the next command may be sent (used by the timing schedule only). */
    private long nextSendAllowedAtMs;

    // Tests only
    HdspParameterProcessor(ConfigProperties config)
    {
        super(null, config);
        this.hdspTiming = config.hdspTiming();
    }

    public HdspParameterProcessor(HandyClient handyClient, ConfigProperties config)
    {
        super(handyClient, config);
        this.hdspTiming = config.hdspTiming();

        HandyBaseResponseWithError response = handyClient.changeMode(DeviceModeValue.HDSP);
        if (response.error() != null)
        {
            delayedClosingWithLog("Could not change Handy mode to HDSP (reason: %s). Closing app...".formatted(response.error().message()));
        }
        if (config.pointsOffset() != null)
        {
            log.warn("'pointsOffset' is ignored in HDSP mode (HDSP is a direct protocol without device-side buffering)");
        }
        if (hdspTiming)
        {
            MessageDelayStats delayStats = measureMessageDelay();
            this.messageDelayMs = delayStats.delayMs();
            this.messageJitterMarginMs = Math.min(delayStats.jitterMs(), HDSP_MAX_JITTER_MARGIN_MS);
        }

        if (config.sliderMin() != null || config.sliderMax() != null)
        {
            handyClient.setSliderSettings(config.sliderMin(), config.sliderMax());
        }
        Optional<SliderSettingsResult> sliderSettings = handyClient.getSliderSettings();
        sliderSettings.ifPresent(s -> log.info("Slider settings min={}, max={}", s.min(), s.max()));
    }

    private MessageDelayStats measureMessageDelay()
    {
        try
        {
            MessageDelayStats delayStats = handyClient.calculateMessageDelayStats();
            log.info("HDSP timing enabled: messageDelay={}ms, jitter={}ms (using a {}ms safety margin)",
                    delayStats.delayMs(), delayStats.jitterMs(), Math.min(delayStats.jitterMs(), HDSP_MAX_JITTER_MARGIN_MS));
            return delayStats;
        }
        catch (Exception e)
        {
            log.error("Could not measure message delay for HDSP timing, falling back to 0ms: {}", e.getMessage());
            return new MessageDelayStats(0, 0);
        }
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
            return canSendNow(now) ? sendNewestPoint(now) : lastMessageSentMs;
        }

        // No points waiting: when the input goes quiet, tell the device to stop at the last known position.
        if (shouldStopForIdle(now))
        {
            sendIdleStop();
            return now;
        }
        return lastMessageSentMs;
    }

    private boolean canSendNow(long now)
    {
        // The first point and the first point after an idle stop have no reliable timing, so they are sent
        // immediately and are allowed to interrupt the current move.
        if (lastSentPoint == null || stopped)
        {
            return true;
        }
        return !hdspTiming || now >= nextSendAllowedAtMs;
    }

    private long sendNewestPoint(long now)
    {
        List<MovementPoint> drainedPoints = drainPoints();
        MovementPoint targetPoint = drainedPoints.getLast();
        savePointsToFileIfRequested(drainedPoints);

        boolean afterIdleStop = stopped;
        Integer lastSentPointTime = lastSentPoint != null ? lastSentPoint.t() : null;
        int durationMs = resolveMoveDurationMs(lastSentPointTime, targetPoint.t(), getSendIntervalMs(), afterIdleStop);

        sendHdspMove(targetPoint, durationMs, false);
        lastSentPoint = targetPoint;
        lastPointActivityMs = now;
        stopped = false;
        scheduleNextSend(now, durationMs);
        return now;
    }

    /**
     * Schedules the next regular send. With timing enabled the next command is sent so that it reaches the
     * device when the current move finishes: the device finishes at
     * {@code sendAtMs + messageDelayMs + durationMs} and the command needs {@code messageDelayMs} to arrive.
     * For a stable delay the two delay terms cancel, so the jitter margin is what actually protects the move:
     * if the delay shrinks, the command would otherwise arrive before the move has finished.
     */
    private void scheduleNextSend(long sendAtMs, int durationMs)
    {
        if (!hdspTiming)
        {
            return;
        }
        long deviceExpectedFreeAtMs = sendAtMs + messageDelayMs + durationMs;
        nextSendAllowedAtMs = deviceExpectedFreeAtMs - messageDelayMs + messageJitterMarginMs;
        log.trace("[HDSP] Move scheduled (sentAt={}, duration={}ms, expectedFreeAt={}, nextSendAt={})",
                sendAtMs, durationMs, deviceExpectedFreeAtMs, nextSendAllowedAtMs);    }

    private boolean shouldStopForIdle(long now)
    {
        // lastPointActivityMs is refreshed when a point is generated and when a movement is sent, so a point
        // that had to wait for the device (timing mode) is still given its full move time before being stopped.
        return !stopped
                && lastSentPoint != null
                && (now - lastPointActivityMs) >= HDSP_IDLE_STOP_TIMEOUT_MS;
    }

    private void sendIdleStop()
    {
        int durationMs = clampDuration(getSendIntervalMs());
        log.debug("[HDSP] No new points for {}ms, stopping at last known position {}",
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
        return (int) Math.max(1, Math.min(durationMs, HDSP_MAX_MOVE_DURATION_MS));
    }

    @Override
    protected void sleepUntilNextSend(long lastMessageSentMs)
    {
        if (!hdspTiming)
        {
            super.sleepUntilNextSend(lastMessageSentMs);
            return;
        }
        // While a command is waiting to be sent, wake up close to its scheduled time. Otherwise just poll at a
        // low rate so that a quiet input is noticed (and stopped) without busy-waiting.
        long waitMs = points.isEmpty()
                ? HDSP_TIMING_POLL_MS
                : nextSendAllowedAtMs - System.currentTimeMillis();
        sleepSafe(Math.max(1, Math.min(waitMs, HDSP_TIMING_POLL_MS)));
    }

    private void sendHdspMove(MovementPoint targetPoint, int durationMs, boolean stopOnTarget)
    {
        try
        {
            HandyBaseResponseWithError response = handyClient.hdspXpt(targetPoint.x(), durationMs, stopOnTarget);
            if (response.error() != null)
            {
                log.error("Error when sending HDSP command to Handy! (reason: {})", response.error().message());
            }
            else
            {
                log.trace("[HDSP] Successfully sent command (xp={}, t={}ms, stopOnTarget={})", targetPoint.x(), durationMs, stopOnTarget);
            }
        }
        catch (Exception e)
        {
            log.error("Exception when sending HDSP command: {}", e.getMessage());
        }
    }
}
