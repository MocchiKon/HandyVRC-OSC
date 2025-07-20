package org.example.processor;

import lombok.SneakyThrows;
import lombok.extern.slf4j.Slf4j;
import org.example.ConfigProperties;
import org.example.handy.common.HandyBaseResponseWithError;
import org.example.handy.v3.HandyClientV3;
import org.example.handy.v3.HandyModeV3;
import org.example.handy.v3.dto.*;

import java.time.Duration;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Optional;
import java.util.function.Consumer;
import java.util.stream.Collectors;

@Slf4j
public class HspParameterProcessor implements ParameterProcessor
{
    public static final int HSP_POINTS_PER_MSG_LIMIT = 100;
    private final long INIT_TIME_MS;
    private final List<HspPoint> hspPoints = Collections.synchronizedList(new ArrayList<>(20));
    private final HandyClientV3 handyClient;

    private int lastPosition = 100;

    private int timeOffsetMs;
    private int timeBetweenMessages; // Difference between TIME_OFFSET_MS and this should account for delay to reach handy so that 1st point gets played
    private int minimalValueChange;
    private float penetratorLength;
    private SpsType spsType;
    private boolean waitForApiResponse;

    private Consumer<Integer> onValueChange;

    public HspParameterProcessor(HandyClientV3 handyClient, ConfigProperties config)
    {
        this.handyClient = handyClient;
        setupProperties(config);
        HandyBaseResponseWithError response = this.handyClient.changeMode(HandyModeV3.HSP);
        if (response.error() != null)
        {
            log.error("Could not change Handy mode to HSP (reason: {}). Closing app...", response.error().message());
            System.exit(1); // TODO For every exit(1) add Error Dialog (static method in main)
        }
        HandySetupResponse setupResponse = this.handyClient.hspSetup();
        if (setupResponse.error() != null)
        {
            log.error("Could not setup HSP stream (reason: {}). Closing app...", setupResponse.error().message());
            System.exit(1);
        }
        this.INIT_TIME_MS = System.currentTimeMillis();
        HandyBaseResponseWithError playResponse = this.handyClient.hspPlay(0, 0, false);
        if (playResponse.error() != null)
        {
            log.error("Could not play HSP stream (reason: {}). Closing app...", playResponse.error().message());
            System.exit(1);
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

    private void setupProperties(ConfigProperties config)
    {
        synchronized (hspPoints)
        {
            this.timeOffsetMs = config.pointsOffset();
            this.timeBetweenMessages = config.sendMessageEveryMs();
            this.minimalValueChange = config.minimalValueChange();
            this.spsType = config.spsType();
            this.penetratorLength = config.penetratorLength();
            this.waitForApiResponse = config.waitForApiResponse();
        }
    }

    @Override
    public void actOnValueChange(Float value)
    {
        synchronized (hspPoints)
        {
            int position = (int) ((1.f - calculatePenetration(value)) * 100); // 100 = top, 0 = bottom
            int positionChange = Math.abs(position - lastPosition);
            if (minimalValueChange > positionChange)
            {
                return;
            }
            lastPosition = position;
            int t = (int) (System.currentTimeMillis() - INIT_TIME_MS + timeOffsetMs);
            hspPoints.add(new HspPoint(t, position));
            onValueChange.accept(100 - position); // 0 = top, 100 = bottom
        }
    }

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
        Thread requestThread = Thread.startVirtualThread(() -> sendHspMessage(hspPointsCopy));
        if (waitForApiResponse) // TODO Remove?
        {
            long start = System.currentTimeMillis();
            boolean hasCompleted = requestThread.join(Duration.of(330, ChronoUnit.MILLIS)); // TODO New param in config or take pointsOffset OR pointsOffset - delay to prevent points skipping
            // TODO Calculate delay dynamically if not set in config? (new property)
            long end = System.currentTimeMillis();
            log.trace("Joining took {} ms", end - start); // TODO Delete this
            if (!hasCompleted)
            {
                log.warn("Skipping waiting fo");
            }
        }
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
