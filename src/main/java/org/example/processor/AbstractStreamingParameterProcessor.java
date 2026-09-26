package org.example.processor;

import lombok.extern.slf4j.Slf4j;
import org.example.config.ConfigProperties;
import org.example.handy.common.HandyClient;
import org.example.handy.common.dto.MovementPoint;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.UUID;
import java.util.function.Consumer;
import java.util.stream.Collectors;

/**
 * Shared logic for processors that turn OSC values into a stream of timed device positions.
 * Subclasses decide how the collected points are actually delivered to the device
 * (buffered by the device in HSP, sent directly in HDSP).
 */
@Slf4j
public abstract class AbstractStreamingParameterProcessor implements ParameterProcessor
{
    protected final List<MovementPoint> points = Collections.synchronizedList(new ArrayList<>(20));
    protected final HandyClient handyClient;

    protected long initTimeMs;
    protected int timeOffsetMs;
    protected int timeBetweenMessages;
    /** Time (app clock) of the last point activity: a point was generated or a movement command was sent. */
    protected volatile long lastPointActivityMs;

    private int lastPosition = 100;
    private int minimalValueChange;
    private SpsType spsType;
    /**
     * Penetration value (in percent of the received value, so 100 means no mapping) that counts as fully
     * penetrated. Null when the mapping is disabled.
     */
    private final Float fullyPenetratedAtValue;
    /** Used to auto-detect the penetrator length. Null unless spsType is ORIFICE. */
    protected PenetratorLengthDetector penetratorLengthDetector;

    /** Notified with the position (0-100) of every produced point; used to drive the UI. */
    protected Consumer<Integer> onValueChange;

    // Recording points
    private boolean savePointsToFile;
    private Integer firstRecordedPointTime;
    private final static String POINTS_FILE_NAME = "handy_points-%s.csv".formatted(UUID.randomUUID());

    protected AbstractStreamingParameterProcessor(HandyClient handyClient, ConfigProperties config)
    {
        this.handyClient = handyClient;
        this.initTimeMs = System.currentTimeMillis();
        this.timeBetweenMessages = config.sendMessageEveryMs();
        this.minimalValueChange = config.minimalValueChange();
        this.spsType = config.spsType();
        this.fullyPenetratedAtValue = resolveFullyPenetratedAtValue(config);
        // Penetrator length is only needed (and only auto-detected) for orifice
        this.penetratorLengthDetector = spsType == SpsType.ORIFICE ? new PenetratorLengthDetector() : null;
        this.savePointsToFile = config.savePointsToFile();
    }

    @Override
    public void setValueChangeListener(Consumer<Integer> onValueChange)
    {
        this.onValueChange = onValueChange;
    }

    @Override
    public void actOnValueChange(Float value)
    {
        addPositionPoint(toDevicePosition(value));
    }

    /**
     * @param penetration Penetration amount in the 0-1 range (1 = fully penetrated): the value received for the
     * configured avatar parameter in PENETRATOR mode, or the value calculated from the root proximity in ORIFICE mode.
     * @return position in the 0-1 range used by the device (1 = fully out/top of the stroke, 0 = fully in/bottom)
     */
    protected float toDevicePosition(Float penetration)
    {
        return 1.f - mapToFullyPenetrated(penetration);
    }

    /**
     * Maps a penetration value onto the full stroke: 'fullyPenetratedAtValue' counts as fully penetrated (100%)
     * and values above it are capped at 100%, so with 50 a penetration of 10% becomes 20% and 50% becomes 100%.
     * Only the value is mapped, the slider movement is not limited (that is what sliderMin/sliderMax do).
     *
     * @param penetration Penetration amount in the 0-1 range
     * @return Mapped penetration amount in the 0-1 range, unchanged when 'fullyPenetratedAtValue' is not set
     */
    private float mapToFullyPenetrated(Float penetration)
    {
        if (fullyPenetratedAtValue == null)
        {
            return penetration;
        }
        return Math.clamp(penetration / (fullyPenetratedAtValue / 100f), 0f, 1f);
    }

    /** A value that cannot be used as a percentage (0 or less) disables the mapping (ConfigLoader warns about it). */
    private static Float resolveFullyPenetratedAtValue(ConfigProperties config)
    {
        return config.fullyPenetratedAtValue() != null && config.fullyPenetratedAtValue() > 0
                ? config.fullyPenetratedAtValue()
                : null;
    }

    /**
     * Handles the root and tip proximity of a penetrator that were received in the same OSC packet (VRChat sends
     * both of them together, but a parameter that did not change is not sent at all, hence the nullable values).
     * They are used to auto-detect the penetrator length, while the root proximity also drives the movement.
     */
    @Override
    public void actOnProximityChange(Float rootProximity, Float tipProximity)
    {
        if (penetratorLengthDetector == null)
        {
            return; // Only orifice mode needs the penetrator length
        }
        penetratorLengthDetector.update(rootProximity, tipProximity);
        if (rootProximity != null)
        {
            addPositionPoint(toDevicePosition(calculatePenetration(rootProximity)));
        }
    }

    /**
     * @param floatPosition Position in 0-1 range where 1 = fully out and 0 = fully inserted.
     */
    private void addPositionPoint(float floatPosition)
    {
        synchronized (points)
        {
            int position = (int) (floatPosition * 100); // 100 = top, 0 = bottom (Handy API)
            int positionChange = Math.abs(position - lastPosition);
            if (minimalValueChange > positionChange)
            {
                return;
            }
            lastPosition = position;
            long now = nowMs();
            int t = (int) (now - initTimeMs + timeOffsetMs);
            points.add(new MovementPoint(t, position));
            lastPointActivityMs = now;
            onValueChange.accept(position);
        }
    }

    /**
     * @param value Root proximity in 0-1 range (1 = fully inserted and 0 = fully out).
     * @return Penetration amount in 0-1 range (1 = fully inserted).
     * Calculated from the exposed length and the auto-detected penetrator length. Until the length is detected
     * it returns 0, so that the device stays in place instead of moving based on a wrong length.
     */
    protected Float calculatePenetration(Float value)
    {
        Float penetratorLength = penetratorLengthDetector.getLength();
        if (penetratorLength == null)
        {
            // Penetrator length is not known yet (root and tip proximity were not received together yet)
            return 0.f;
        }
        float exposedLength = 1.f - value;
        float exposedRatio = exposedLength / penetratorLength;
        float penetrationValue = Math.max(1.f - exposedRatio, 0.f); // Prevent negative values
        return Math.min(penetrationValue, 1.f); // Cap at 1.f
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

    /**
     * Sends (at most) one message built from the points collected so far and returns the time it was sent.
     * Called repeatedly from the sending loop.
     *
     * @return timestamp of the last sent message (unchanged when nothing was sent)
     */
    protected abstract long trySendingMessage(long lastMessageSentMs);

    private void delayUntilNextMessageCanBeSent(long lastMessageSentMs)
    {
        sleepUntilNextSend(lastMessageSentMs);
    }

    /**
     * Sleeps until the next message may be sent. The default implementation respects the configured send
     * interval; subclasses with a custom schedule (for example HDSP timing) can override it.
     */
    protected void sleepUntilNextSend(long lastMessageSentMs)
    {
        long timeUntilNextMsg = getTimeUntilNextMsg(lastMessageSentMs);
        if (timeUntilNextMsg <= 0)
        {
            sleepSafe(5); // Small sleep to avoid heavy CPU usage when no points to send
            return;
        }
        sleepSafe(timeUntilNextMsg);
    }

    protected void sleepSafe(long sleepMs)
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

    protected long getTimeUntilNextMsg(long lastMessageSentMs)
    {
        long timeElapsedSinceLastMessage = nowMs() - lastMessageSentMs;
        return getSendIntervalMs() - timeElapsedSinceLastMessage;
    }

    /**
     * Current app time in milliseconds. Every timing decision goes through this method, so that tests can replace
     * the clock instead of sleeping.
     */
    protected long nowMs()
    {
        return System.currentTimeMillis();
    }

    /**
     * Minimum time between two messages sent to the device. Defaults to 'sendMessageEveryMs',
     * subclasses can override it to enforce a larger interval.
     */
    protected long getSendIntervalMs()
    {
        return timeBetweenMessages;
    }

    /**
     * @return all points collected since the last call (and clears them)
     */
    protected List<MovementPoint> drainPoints()
    {
        synchronized (points)
        {
            List<MovementPoint> pointsCopy = new ArrayList<>(points);
            points.clear();
            return pointsCopy;
        }
    }

    protected void savePointsToFileIfRequested(List<MovementPoint> pointsToSave)
    {
        if (!savePointsToFile) return;
        if (firstRecordedPointTime == null) firstRecordedPointTime = pointsToSave.getFirst().t();

        byte[] data = pointsToSave.stream()
                .map(point -> "\n%s,%s".formatted(point.t() - firstRecordedPointTime, point.x()))
                .collect(Collectors.joining())
                .getBytes();
        try
        {
            Files.write(Path.of(POINTS_FILE_NAME), data, StandardOpenOption.CREATE, StandardOpenOption.APPEND);
        }
        catch (IOException e)
        {
            log.error("Failed to save points {} to file: {}", pointsToSave, e.getMessage());
        }
    }
}
