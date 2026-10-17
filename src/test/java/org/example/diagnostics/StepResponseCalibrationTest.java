package org.example.diagnostics;

import org.example.handy.common.DeviceMode;
import org.example.handy.common.HandyBaseResponseWithError;
import org.example.handy.common.HandyClient;
import org.example.handy.common.dto.SliderSettingsResult;
import org.example.handy.common.dto.SliderState;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;

class StepResponseCalibrationTest
{
    @TempDir
    Path tempDir;

    @Test
    void writesASampleForEveryCalibrationRunAndMovesTheDevice() throws Exception
    {
        var client = new FakeClient();
        List<String> progress = new ArrayList<>();

        Path file = StepResponseCalibration.run(client, tempDir, progress::add, () -> false,
                new int[]{40}, 2, 10, 2, 20, false);

        assertThat(file).exists();
        Path profile = file.resolveSibling(file.getFileName().toString()
                .replace("step-response-", "device-profile-").replace(".csv", ".json"));
        assertThat(profile).exists();
        assertThat(Files.readString(profile)).contains("suggestedMaxSliderSpeedMmPerSecond")
                .contains("suggestedMinSliderSpeedMmPerSecond")
                .contains("strokeMm");
        List<String> lines = Files.readAllLines(file);
        assertThat(lines.getFirst()).isEqualTo("# HandyVRC-OSC step-response calibration");
        assertThat(lines).anySatisfy(line -> assertThat(line).contains(",up,40,"));
        assertThat(lines).anySatisfy(line -> assertThat(line).contains(",down,40,"));
        // The CSV always uses dots for decimals, whatever locale the app runs in
        assertThat(lines).anySatisfy(line -> assertThat(line).contains("0.000000,1.000000"));
        assertThat(lines.stream().filter(line -> !line.startsWith("#") && !line.startsWith("run,")).count())
                .isGreaterThan(2);
        // The initial move to the bottom, the two calibration moves and the final move to the bottom
        assertThat(client.commands).hasSize(4);
        assertThat(client.commands.get(0).target()).isEqualTo(0f);
        assertThat(client.commands.get(1).target()).isEqualTo(1f);
        assertThat(client.commands.get(2).target()).isEqualTo(0f);
        assertThat(client.commands.get(3).target()).isEqualTo(0f);
        assertThat(progress).anySatisfy(message -> assertThat(message).contains("1/2"));
    }

    @Test
    void writesTheProfileToItsOwnDirectory() throws Exception
    {
        var client = new FakeClient();
        Path csvDirectory = tempDir.resolve("logs").resolve("diagnostics");
        Path profileDirectory = tempDir.resolve("profiles");

        Path csv = StepResponseCalibration.run(client, csvDirectory, profileDirectory, message -> { }, () -> false,
                new int[]{40}, 2, 10, 2, 20, false);

        assertThat(csv).exists();
        assertThat(csv.getParent()).isEqualTo(csvDirectory);
        Path profile = profileDirectory.resolve(csv.getFileName().toString()
                .replace("step-response-", "device-profile-").replace(".csv", ".json"));
        assertThat(profile).exists();
        assertThat(Files.readString(profile)).contains("suggestedMaxSliderSpeedMmPerSecond");
    }

    @Test
    void stopsWhenCancelled() throws Exception
    {
        var client = new FakeClient();
        Path file = StepResponseCalibration.run(client, tempDir, message -> { }, () -> true,
                new int[]{40, 40}, 2, 10, 1, 20, false);

        assertThat(file).exists();
        // The cancelled flag is checked before the first run: only the initial move to the bottom was sent
        assertThat(client.commands).hasSize(1);
    }

    private record Command(float target, int durationMs, boolean stopOnTarget)
    {
    }

    private static final class FakeClient extends HandyClient
    {
        private final AtomicReference<Float> position = new AtomicReference<>(0.5f);
        final List<Command> commands = new ArrayList<>();

        @Override
        public HandyBaseResponseWithError changeMode(DeviceMode mode)
        {
            return new HandyBaseResponseWithError(null);
        }

        @Override
        public boolean checkConnectionStatus()
        {
            return true;
        }

        @Override
        public void setSliderSettings(Float min, Float max)
        {
        }

        @Override
        public Optional<SliderSettingsResult> getSliderSettings()
        {
            return Optional.of(new SliderSettingsResult("0.0", "1.0", 5f, 105f));
        }

        @Override
        public Optional<SliderState> getSliderState()
        {
            float value = position.get();
            return Optional.of(new SliderState(value, 5f + value * 100f, 0f));
        }

        @Override
        public HandyBaseResponseWithError hdspXpt(float xp, int t, boolean stopOnTarget)
        {
            commands.add(new Command(xp, t, stopOnTarget));
            position.set(xp);
            return new HandyBaseResponseWithError(null);
        }
    }
}
