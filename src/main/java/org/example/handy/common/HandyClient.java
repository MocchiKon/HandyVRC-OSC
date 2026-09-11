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
public abstract class HandyClient
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
     * Estimates the one-way message delay (network latency) to the device/server in milliseconds.
     * Can be used to auto-calculate the points offset applied to points sent to the device.
     */
    public long calculateMessageDelay()
    {
        try
        {
            log.info("Measuring message delay...");
            final int SYNC_SAMPLES = 30;
            long[] samples = new long[SYNC_SAMPLES];

            for (int i = 0; i < SYNC_SAMPLES; i++)
            {
                long tSend = System.currentTimeMillis();
                sendRequestForMessageDelayCalc();
                long tReceive = System.currentTimeMillis();
                long rtd = tReceive - tSend;
                samples[i] = rtd;
            }

            long rtdSum = 0;
            long minRtd = Long.MAX_VALUE;
            long maxRtd = Long.MIN_VALUE;
            for (long sample : samples)
            {
                rtdSum += sample;
                if (sample < minRtd) minRtd = sample;
                if (sample > maxRtd) maxRtd = sample;
            }

            long avgRtd = Math.round(rtdSum / (double) SYNC_SAMPLES);
            long messageDelay = avgRtd / 2; // one-way latency

            log.info("RTD samples (ms): {}", Arrays.toString(samples));
            log.info("Message delay: messageDelay={}ms, avgRtd={}ms, minRtd={}ms, maxRtd={}ms (from {} samples)",
                    messageDelay, avgRtd, minRtd, maxRtd, SYNC_SAMPLES);
//            return messageDelay + (int) (messageDelay * 0.5f); // Add some extra leeway
            return messageDelay;
        }
        catch (Exception e)
        {
            throw new RuntimeException("Message delay measurement failed", e);
        }
    }

    public abstract void sendRequestForMessageDelayCalc();
}
