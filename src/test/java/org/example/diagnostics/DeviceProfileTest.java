package org.example.diagnostics;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDateTime;

import static org.assertj.core.api.Assertions.assertThat;

class DeviceProfileTest
{
    @TempDir
    Path directory;

    @Test
    void readsTheLimits() throws Exception
    {
        Path file = write("device-profile-1.json", """
                {
                  "deviceId": "AA:BB",
                  "calibratedAt": "%s",
                  "strokeMm": 100,
                  "motionStartMs": 137.4,
                  "maxSpeedUpMmPerSecond": 431.4,
                  "maxSpeedDownMmPerSecond": 373.7,
                  "suggestedMaxSliderSpeedMmPerSecond": 370,
                  "suggestedMinSliderSpeedMmPerSecond": 30
                }
                """.formatted(LocalDateTime.now()));

        DeviceProfile profile = DeviceProfile.loadLatest(directory, "AA:BB").orElseThrow();

        assertThat(profile.motionStartMs()).isEqualTo(137.4);
        assertThat(profile.plannedMaxMmPerSecond()).isEqualTo(370);
        assertThat(profile.plannedMinMmPerSecond()).isEqualTo(30);
    }

    @Test
    void matchingDeviceWinsOverANewerOtherDevice() throws Exception
    {
        write("device-profile-old.json", profile("AA:BB", 300, 100));
        Thread.sleep(10);
        write("device-profile-new.json", profile("CC:DD", 700, 1));

        DeviceProfile profile = DeviceProfile.loadLatest(directory, "AA:BB").orElseThrow();

        assertThat(profile.deviceId()).isEqualTo("AA:BB");
        assertThat(profile.maxSpeedUpMmPerSecond()).isEqualTo(300);
    }

    @Test
    void withoutASuggestionTheLowerMeasuredDirectionIsUsed() throws Exception
    {
        write("device-profile-1.json", """
                {
                  "deviceId": "AA:BB",
                  "calibratedAt": "%s",
                  "maxSpeedUpMmPerSecond": 400,
                  "maxSpeedDownMmPerSecond": 200
                }
                """.formatted(LocalDateTime.now()));

        DeviceProfile profile = DeviceProfile.loadLatest(directory, "AA:BB").orElseThrow();

        assertThat(profile.plannedMaxMmPerSecond()).isEqualTo(200);
    }

    @Test
    void anotherDeviceProfileIsNotUsedForThisDevice() throws Exception
    {
        write("device-profile-other.json", profile("CC:DD", 700, 1));

        assertThat(DeviceProfile.loadLatest(directory, "AA:BB")).isEmpty();
    }

    @Test
    void profilesWithoutAnIdAreUsedAsAFallback() throws Exception
    {
        write("device-profile-old.json", profile("", 350, 30));

        DeviceProfile profile = DeviceProfile.loadLatest(directory, "AA:BB").orElseThrow();

        assertThat(profile.maxSpeedUpMmPerSecond()).isEqualTo(350);
    }

    private Path write(String name, String content) throws Exception
    {
        Path file = directory.resolve(name);
        Files.writeString(file, content);
        return file;
    }

    private static String profile(String deviceId, double maxUp, double min)
    {
        return """
                {
                  "deviceId": "%s",
                  "calibratedAt": "%s",
                  "maxSpeedUpMmPerSecond": %s,
                  "maxSpeedDownMmPerSecond": %s,
                  "suggestedMaxSliderSpeedMmPerSecond": %s,
                  "suggestedMinSliderSpeedMmPerSecond": %s
                }
                """.formatted(deviceId, LocalDateTime.now(), maxUp, maxUp, maxUp, min);
    }
}
