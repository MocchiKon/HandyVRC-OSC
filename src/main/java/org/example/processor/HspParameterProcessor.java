package org.example.processor;

import handy.model.DeviceModeValue;
import lombok.extern.slf4j.Slf4j;
import org.example.config.ConfigProperties;
import org.example.handy.common.ConnectionRetry;
import org.example.handy.common.HandyBaseResponseWithError;
import org.example.handy.common.HandyClient;
import org.example.handy.common.RateLimitedLogger;
import org.example.handy.common.dto.*;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Optional;
import java.util.stream.Collectors;

import static org.example.Main.delayedClosingWithLog;

@Slf4j
public class HspParameterProcessor extends AbstractStreamingParameterProcessor
{
    public static final int HSP_POINTS_PER_MSG_LIMIT = 100;

    private boolean pauseOnStarving;

    /** Repeated stream failures are summarized instead of logging one error per sent batch. */
    private final RateLimitedLogger hspAddErrors = new RateLimitedLogger(log);

    // clamp points
    private boolean clamp;
    private Integer lastX = null;
    private int lastDir = 0;

    // Tests only
    HspParameterProcessor(ConfigProperties config)
    {
        super(null, config);
        setupHspProperties(config);
        this.timeOffsetMs = config.pointsOffset() != null ? config.pointsOffset() : 0;
    }

    public HspParameterProcessor(HandyClient handyClient, ConfigProperties config)
    {
        super(handyClient, config);
        setupHspProperties(config);

        HandyBaseResponseWithError response = handyClient.changeMode(DeviceModeValue.HSP);
        if (response.error() != null)
        {
            delayedClosingWithLog("Could not change Handy mode to HSP (reason: %s). Closing app...".formatted(response.error().message()));
        }
        HandySetupResponse setupResponse = handyClient.hspSetup();
        if (setupResponse.error() != null)
        {
            delayedClosingWithLog("Could not setup HSP stream (reason: %s). Closing app...".formatted(setupResponse.error().message()));
        }
        HandyBaseResponseWithError flushResponse = handyClient.hspFlush();
        if (flushResponse.error() != null)
        {
            delayedClosingWithLog("Could not flush HSP stream (reason: %s). Closing app...".formatted(flushResponse.error().message()));
        }

        this.timeOffsetMs = resolvePointsOffset(config);
        this.initTimeMs = System.currentTimeMillis();
        HandyBaseResponseWithError playResponse = handyClient.hspPlay(0, 0, pauseOnStarving);
        if (playResponse.error() != null)
        {
            delayedClosingWithLog("Could not play HSP stream (reason: %s). Closing app...".formatted(playResponse.error().message()));
        }

        if (config.sliderMin() != null || config.sliderMax() != null)
        {
            handyClient.setSliderSettings(config.sliderMin(), config.sliderMax());
        }
        Optional<SliderSettingsResult> sliderSettings = handyClient.getSliderSettings();
        sliderSettings.ifPresent(s -> log.info("Slider settings min={}, max={}", s.min(), s.max()));
    }

    private void setupHspProperties(ConfigProperties config)
    {
        this.pauseOnStarving = config.pauseOnStarving();
        this.clamp = config.clamp();
    }

    /**
     * Resolves the points offset (delay applied to every point) to use.
     * If the user set 'pointsOffset' in the config it is used as-is; otherwise it is auto-calculated
     * from the measured message delay so that points arrive in time: sendMessageEveryMs + messageDelay.
     */
    private int resolvePointsOffset(ConfigProperties config)
    {
        if (config.pointsOffset() != null)
        {
            return config.pointsOffset();
        }
        long messageDelay = handyClient.calculateMessageDelay();
        int autoOffset = (int) (config.sendMessageEveryMs() + messageDelay);
        log.info("'pointsOffset' not specified, auto-calculated to {}ms (sendMessageEveryMs={}ms + messageDelay={}ms)",
                autoOffset, config.sendMessageEveryMs(), messageDelay);
        return autoOffset;
    }

    @Override
    protected long trySendingMessage(long lastMessageSentMs)
    {
        if (points.isEmpty() || getTimeUntilNextMsg(lastMessageSentMs) > 5)
        {
            return lastMessageSentMs;
        }
        List<MovementPoint> hspPointsCopy = getAndClearMovementPoints();
        savePointsToFileIfRequested(hspPointsCopy);
        lastMessageSentMs = System.currentTimeMillis();
        Thread.startVirtualThread(() -> sendHspMessage(hspPointsCopy));
        return lastMessageSentMs;
    }

    private List<MovementPoint> getAndClearMovementPoints()
    {
        List<MovementPoint> hspPointsCopy = drainPoints();

        if (clamp)
        {
            hspPointsCopy = clampPoints(hspPointsCopy);
        }

        if (hspPointsCopy.size() >= HSP_POINTS_PER_MSG_LIMIT)
        {
            hspPointsCopy = hspPointsCopy.stream()
                    .skip(HSP_POINTS_PER_MSG_LIMIT - hspPointsCopy.size()) // Skip points over limit
                    .collect(Collectors.toCollection(ArrayList::new));
            log.warn("Skipped some points before sending {}", hspPointsCopy);
        }
        if (hspPointsCopy.get(0).t() <= (System.currentTimeMillis() - initTimeMs))
        {
            log.error("Some points are outdated before sending! hspPointsCopy={}, currentTime={}", hspPointsCopy, System.currentTimeMillis() - initTimeMs);
        }
        return hspPointsCopy;
    }

    // Collapse intermediate points that continue in the same direction:
    // keep only turning points (and the last point of a monotonic run).
    List<MovementPoint> clampPoints(List<MovementPoint> points)
    {
        points.sort(Comparator.comparing(MovementPoint::t));
        List<MovementPoint> result = new ArrayList<>();

        for (MovementPoint p : points)
        {
            if (lastX == null)
            {
                result.add(p);
                lastX = p.x();
                continue;
            }

            int dir = Integer.compare(p.x() - lastX, 0);
            if (dir == 0) continue;

            boolean isTurn = (lastDir != 0 && dir != lastDir);

            if (isTurn || result.isEmpty()) // turning point OR first emission in this message
            {
                result.add(p);
            }
            else // continuing same direction within this message
            {
                result.set(result.size() - 1, p);
            }

            lastDir = dir;
            lastX = p.x();
        }

        return result;
    }

    private void sendHspMessage(List<MovementPoint> hspPointsCopy)
    {
        try
        {
            HandyHspAddResponse response = handyClient.hspAdd(new HspAddRequest(hspPointsCopy, false));
            if (response.error() != null)
            {
                // A problem that lasts (device not responding, points rejected) would otherwise flood the log
                // with one entry per batch, so repeats are summarized.
                hspAddErrors.error("Error when sending command to Handy! (reason: %s)".formatted(response.error().message()));
            }
            else if (response.result() != null)
            {
                int currentTimeResponse = response.result().current_time();
                int firstPointTime = hspPointsCopy.getFirst().t();
                log.trace("[HSP] Successfully sent command (hspPoints={}, timeOffsetLeeway={}, response={})", hspPointsCopy, firstPointTime - currentTimeResponse, response);
                // NOTE: the returned state has already been checked against the sent points by the client that
                // received it (skipped points, starving stream, saturated buffer) and problems are reported there.
            }
        }
        catch (Exception e)
        {
            if (ConnectionRetry.isConnectionFailure(e))
            {
                // The client has already repeated the batch once on a new connection, so the API connection is
                // really gone and the points of this batch are lost
                log.warn("Lost connection to the Handy API, dropped points={} (reason: {})", hspPointsCopy, e.getMessage());
                return;
            }
            log.error("Exception when sending hsp points: {}", e.getMessage());
        }
    }
}
