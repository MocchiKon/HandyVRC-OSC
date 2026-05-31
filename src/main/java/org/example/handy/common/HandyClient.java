package org.example.handy.common;

import org.example.handy.common.dto.HandyHspAddResponse;
import org.example.handy.common.dto.HandySetupResponse;
import org.example.handy.common.dto.HspAddRequest;
import org.example.handy.common.dto.SliderSettingsResult;

import java.util.Optional;

public interface HandyClient
{
    HandyBaseResponseWithError changeMode(int mode);
    boolean checkConnectionStatus();
    HandySetupResponse hspSetup();
    HandyBaseResponseWithError hspPlay(long startTime, long serverTime, boolean pauseOnStarving);
    HandyHspAddResponse hspAdd(HspAddRequest requestBody);
    void setSliderSettings(Float min, Float max);
    Optional<SliderSettingsResult> getSliderSettings();

    /**
     * Estimates the one-way message delay (network latency) to the device/server in milliseconds.
     * Can be used to auto-calculate the points offset applied to points sent to the device.
     */
    long calculateMessageDelay();
}
