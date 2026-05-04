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
     * Synchronizes the clock with the device/server.
     * Returns the estimated offset in milliseconds.
     */
    long syncClock();
}
