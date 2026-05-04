package org.example.processor;

import lombok.SneakyThrows;
import lombok.extern.slf4j.Slf4j;
import org.example.config.ConfigProperties;
import org.example.handy.common.HandyBaseResponseWithError;
import org.example.handy.common.HandyClient;
import org.example.handy.common.dto.*;
import org.example.handy.v3.HandyModeV3;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Optional;
import java.util.function.Consumer;
import java.util.stream.Collectors;

import static org.example.Main.delayedClosingWithLog;

@Slf4j
public class HspParameterProcessor implements ParameterProcessor
{
    public static final int HSP_POINTS_PER_MSG_LIMIT = 100;
    private final long INIT_TIME_MS;
    private final List<HspPoint> hspPoints = Collections.synchronizedList(new ArrayList<>(20));
    private final HandyClient handyClient;

    private int lastPosition = 100;

    private int timeOffsetMs;
    private int timeBetweenMessages; // Difference between TIME_OFFSET_MS and this should account for delay to reach handy so that 1st point gets played
    private int minimalValueChange;
    private float penetratorLength;
    private SpsType spsType;

    private Consumer<Integer> onValueChange;

    public HspParameterProcessor(HandyClient handyClient, ConfigProperties config)
    {
        this.handyClient = handyClient;
        setupProperties(config);
        HandyBaseResponseWithError response = this.handyClient.changeMode(HandyModeV3.HSP);
        if (response.error() != null)
        {
            delayedClosingWithLog("Could not change Handy mode to HSP (reason: %s). Closing app...".formatted(response.error().message()));
        }
        HandySetupResponse setupResponse = this.handyClient.hspSetup();
        if (setupResponse.error() != null)
        {
            delayedClosingWithLog("Could not setup HSP stream (reason: %s). Closing app...".formatted(setupResponse.error().message()));
        }
        syncClock();
        this.INIT_TIME_MS = System.currentTimeMillis();
        HandyBaseResponseWithError playResponse = this.handyClient.hspPlay(0, 0, false);
        if (playResponse.error() != null)
        {
            delayedClosingWithLog("Could not play HSP stream (reason: %s). Closing app...".formatted(playResponse.error().message()));
        }
        this.handyClient.setSliderSettings(config.sliderMin(), config.sliderMax());
        Optional<SliderSettingsResult> sliderSettings = this.handyClient.getSliderSettings();
        sliderSettings.ifPresent(s -> log.info("Slider settings min={}, max={}", s.min(), s.max()));
    }

    @Override
    public void setValueChangeListener(Consumer<Integer> onValueChange)
    {
        this.onValueChange = onValueChange;
    }

    @Override
    public long syncClock()
    {
        return handyClient.syncClock();
    }

    private void setupProperties(ConfigProperties config)
    {
        synchronized (hspPoints)
        {
            this.timeOffsetMs = config.pointsOffset();
            this.timeBetweenMessages = config.sendMessageEveryMs();
            this.minimalValueChange = config.minimalValueChange();
            this.spsType = config.spsType();
            this.penetratorLength = config.penetratorLength();
        }
    }

    @Override
    public void actOnValueChange(Float value)
    {
        synchronized (hspPoints)
        {
            float floatPosition = 1.f - calculatePenetration(value);
            int position = (int) (floatPosition * 100); // 100 = top, 0 = bottom (Handy API)
            int positionChange = Math.abs(position - lastPosition);
            if (minimalValueChange > positionChange)
            {
                return;
            }
            lastPosition = position;
            int t = (int) (System.currentTimeMillis() - INIT_TIME_MS + timeOffsetMs);
            hspPoints.add(new HspPoint(t, position));
            onValueChange.accept(100 - position); // Penetration amount, 100 means fully inserted
        }
    }

    /**
     * @param value Value in 0-1 range (for penetrator, 1 = fully inserted and 0 = fully out).
     * @return Penetration amount in 0-1 range (1 = fully inserted).
     * For penetrator, it returns the value as is. For orifice, it calculates penetration based on exposed length and penetrator length.
     */
    private Float calculatePenetration(Float value)
    {
        if (spsType == SpsType.PENETRATOR)
        {
            return value;
        }
        float exposedLength = 1.f - value;
        float exposedRatio = exposedLength / penetratorLength;
        float penetrationValue = Math.max(1.f - exposedRatio, 0.f); // Prevent negative values
        return Math.min(penetrationValue, 1.f); // Cap at 1.f
    }

    @Override
    public void refreshConfig(ConfigProperties configProperties)
    {
        setupProperties(configProperties);
    }

    @Override
    public void run()
    {
        new Thread(this::runSendingLogicInInfiniteLoop).start();
    }

    private void runSendingLogicInInfiniteLoop()
    {
        long lastMessageSentMs = 0;
        while (true)
        {
            try
            {
                lastMessageSentMs = trySendingMessage(lastMessageSentMs);
                delayUntilNextMessageCanBeSent(lastMessageSentMs);
            }
            catch (Exception e)
            {
                log.error("Caught exception!", e);
            }
        }
    }

    private void delayUntilNextMessageCanBeSent(long lastMessageSentMs)
    {
        long timeUntilNextMsg = getTimeUntilNextMsg(lastMessageSentMs);
        if (timeUntilNextMsg <= 0)
        {
            sleepSafe(5); // Small sleep to avoid heavy CPU usage when no points to send
            return;
        }
        sleepSafe(timeUntilNextMsg);
    }

    private void sleepSafe(long sleepMs)
    {
        try
        {
            Thread.sleep(sleepMs);
        }
        catch (InterruptedException e)
        {
            log.error("Error while sleeping: {}", e.getMessage());
        }
    }

    @SneakyThrows
    private long trySendingMessage(long lastMessageSentMs)
    {
        if (hspPoints.isEmpty() || getTimeUntilNextMsg(lastMessageSentMs) > 5)
        {
            return lastMessageSentMs;
        }
        List<HspPoint> hspPointsCopy = getAndClearHspPoints();
        lastMessageSentMs = System.currentTimeMillis();
        Thread.startVirtualThread(() -> sendHspMessage(hspPointsCopy));
        return lastMessageSentMs;
    }

    private List<HspPoint> getAndClearHspPoints()
    {
        List<HspPoint> hspPointsCopy;
        synchronized (hspPoints)
        {
            hspPointsCopy = new ArrayList<>(hspPoints);
            hspPoints.clear();
        }
        if (hspPointsCopy.size() >= HSP_POINTS_PER_MSG_LIMIT)
        {
            hspPointsCopy = hspPointsCopy.stream()
                    .skip(HSP_POINTS_PER_MSG_LIMIT - hspPointsCopy.size()) // Skip points over limit
                    .collect(Collectors.toCollection(ArrayList::new));
            log.warn("Skipped some points before sending {}", hspPointsCopy);
        }
        if (hspPointsCopy.get(0).t() <= (System.currentTimeMillis() - INIT_TIME_MS))
        {
            log.error("Some points are outdated before sending! hspPointsCopy={}, currentTime={}", hspPointsCopy, System.currentTimeMillis() - INIT_TIME_MS);
        }
        return hspPointsCopy;
    }

    private void sendHspMessage(List<HspPoint> hspPointsCopy)
    {
        try
        {
            HandyHspAddResponse response = handyClient.hspAdd(new HspAddRequest(hspPointsCopy, false));
            if (response.error() != null)
            {
                log.error("Error when sending command to Handy! (reason: {})", response.error().message());
            }
            else if (response.result() != null)
            {
                int currentTimeResponse = response.result().current_time();
                int firstPointTime = hspPointsCopy.getFirst().t();
                log.trace("[HSP] Successfully sent command (hspPoints={}, timeOffsetLeeway={}, response={})", hspPointsCopy, firstPointTime - currentTimeResponse, response);
                logPotentialIssues(hspPointsCopy, firstPointTime, response.result().last_point_time(), currentTimeResponse, response.result().first_point_time());
            }
        }
        catch (Exception e)
        {
            if (e.getMessage().contains("GOAWAY received"))
            {
                log.warn("Recieved GOAWAY, dropped points={}", hspPointsCopy);
                return;
            }
            log.error("Exception when sending hsp points: {}", e.getMessage());
        }
    }

    private void logPotentialIssues(List<HspPoint> hspPointsCopy, int firstPointTime, Integer lastPointTimeResponse, int currentTimeResponse, Integer firstPointTimeResponse)
    {
        if (lastPointTimeResponse == null || firstPointTimeResponse == null)
        {
            return;
        }
        if (currentTimeResponse >= lastPointTimeResponse)
        {
            log.warn("All points skipped! (current_time={}, last_point_time={})", currentTimeResponse, lastPointTimeResponse);
        }
        if (firstPointTimeResponse >= lastPointTimeResponse)
        {
            log.warn("First point time is later than last point time! (first_point_time={}, last_point_time={})", firstPointTimeResponse, lastPointTimeResponse);
        }
        if (firstPointTime - currentTimeResponse <= 0)
        {
            log.warn("Skipped some points! (1stSentPointTime={}, last_point_time={}, current_time={})", firstPointTime, lastPointTimeResponse, currentTimeResponse);
        }
    }

    private long getTimeUntilNextMsg(long lastMessageSentMs)
    {
        return timeBetweenMessages - (System.currentTimeMillis() - lastMessageSentMs);
    }
}
