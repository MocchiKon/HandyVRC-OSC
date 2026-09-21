package org.example.handy.common;

import handy.model.DeviceModeValue;
import lombok.extern.slf4j.Slf4j;
import org.example.handy.common.dto.HandyHspAddResponse;
import org.example.handy.common.dto.HandySetupResponse;
import org.example.handy.common.dto.HspAddRequest;
import org.example.handy.common.dto.SliderSettingsResult;

import java.util.Arrays;
import java.util.Optional;

@Slf4j
public abstract class HandyClient implements AutoCloseable
{
    public abstract HandyBaseResponseWithError changeMode(DeviceModeValue mode);
    public abstract boolean checkConnectionStatus();
    public abstract HandySetupResponse hspSetup();
    public abstract HandyBaseResponseWithError hspFlush();
    public abstract HandyBaseResponseWithError hspPlay(long startTime, long serverTime, boolean pauseOnStarving);
    public abstract HandyHspAddResponse hspAdd(HspAddRequest requestBody);
    public abstract void setSliderSettings(Float min, Float max);
    public abstract Optional<SliderSettingsResult> getSliderSettings();

    /**
     * HDSP (Handy Direct Streaming Protocol): moves the slider to the given percent position (0-100) using the
     * given duration (in ms) for the move.
     * <p>
     * HDSP is only available over a direct Bluetooth connection. The default implementation rejects it so that
     * API clients cannot accidentally use it.
     *
     * @param xp percent position in 0-100 range (relative to the configured stroke zone)
     * @param t time in milliseconds the device should take to reach the position
     * @param stopOnTarget stop the slider motor when the target position is reached
     */
    public HandyBaseResponseWithError hdspXpt(float xp, int t, boolean stopOnTarget)
    {
        throw new UnsupportedOperationException("HDSP is only supported over a Bluetooth (BLE) connection");
    }

    /**
     * Estimates the one-way message delay (network latency) to the device/server in milliseconds.
     * Can be used to auto-calculate the points offset applied to points sent to the device.
     */
    public long calculateMessageDelay()
    {
        return calculateMessageDelayStats().delayMs();
    }

    /**
     * Measures the round-trip delay to the device/server a few times and derives the one-way message delay
     * and its variation.
     * <p>
     * Outliers are rejected before the statistics are calculated: the lowest and highest samples are trimmed
     * so that a single scheduling hiccup cannot skew the result.
     */
    public MessageDelayStats calculateMessageDelayStats()
    {
        try
        {
            log.info("Measuring message delay...");
            final int SYNC_SAMPLES = 30;
            long[] rtdSamples = new long[SYNC_SAMPLES];

            for (int i = 0; i < SYNC_SAMPLES; i++)
            {
                long tSend = System.currentTimeMillis();
                sendRequestForMessageDelayCalc();
                long tReceive = System.currentTimeMillis();
                rtdSamples[i] = tReceive - tSend;
            }

            log.info("RTD samples (ms): {}", Arrays.toString(rtdSamples));
            MessageDelayStats stats = toMessageDelayStats(rtdSamples);
            log.info("Message delay: messageDelay={}ms, jitter={}ms (from {} samples, outliers rejected)",
                    stats.delayMs(), stats.jitterMs(), SYNC_SAMPLES);
//            return messageDelay + (int) (messageDelay * 0.5f); // Add some extra leeway
            return stats;
        }
        catch (Exception e)
        {
            throw new RuntimeException("Message delay measurement failed", e);
        }
    }

    /**
     * Converts raw round-trip-delay samples into one-way delay statistics, rejecting outliers by trimming the
     * lowest and highest samples (10% of the samples on each side, at least one when possible).
     *
     * @param rtdSamples round-trip delay samples in milliseconds
     * @return average one-way delay and one-way jitter (spread of the remaining samples)
     */
    static MessageDelayStats toMessageDelayStats(long[] rtdSamples)
    {
        if (rtdSamples == null || rtdSamples.length == 0)
        {
            throw new IllegalArgumentException("At least one message delay sample is required");
        }
        long[] sorted = rtdSamples.clone();
        Arrays.sort(sorted);

        int trimCount = sorted.length >= 3 ? Math.max(1, sorted.length / 10) : 0;
        int from = trimCount;
        int to = sorted.length - trimCount; // exclusive

        long rtdSum = 0;
        long minRtd = Long.MAX_VALUE;
        long maxRtd = Long.MIN_VALUE;
        for (int i = from; i < to; i++)
        {
            long rtd = sorted[i];
            rtdSum += rtd;
            if (rtd < minRtd) minRtd = rtd;
            if (rtd > maxRtd) maxRtd = rtd;
        }

        int usedSamples = to - from;
        long avgRtd = Math.round(rtdSum / (double) usedSamples);
        long oneWayDelay = avgRtd / 2;
        long oneWayJitter = (maxRtd - minRtd) / 2;
        return new MessageDelayStats(oneWayDelay, oneWayJitter);
    }

    public abstract void sendRequestForMessageDelayCalc();

    /**
     * Releases the resources the client holds (for example the reader thread that receives BLE responses).
     * The default implementation does nothing.
     */
    @Override
    public void close()
    {
    }
}
