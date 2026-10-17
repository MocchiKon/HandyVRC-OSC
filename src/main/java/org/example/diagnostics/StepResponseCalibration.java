package org.example.diagnostics;

import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.extern.slf4j.Slf4j;
import org.example.handy.common.DeviceMode;
import org.example.handy.common.HandyClient;
import org.example.handy.common.dto.SliderSettingsResult;
import org.example.handy.common.dto.SliderState;

import java.io.BufferedWriter;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.*;
import java.util.concurrent.*;
import java.util.function.BooleanSupplier;
import java.util.function.Consumer;

/**
 * Measures how the device executes a known move: it sends one HDSP command and reads the slider position as fast as
 * the device answers it. Repeating the same move several times and firing the state requests in parallel gives a
 * step-response curve at a much better resolution than the normal 200ms device log.
 * <p>
 * The result is a CSV file in {@code logs/diagnostics/} with one row per state sample (command time, request/response
 * time, round-trip time, position, speed) and a device-profile JSON in {@code profiles/}. The profile summarizes the measured
 * motion start, the maximum/minimum speed per direction and suggested maximum/minimum device speed settings.
 * <p>
 * This is a diagnostic tool, not part of normal processing. It needs a direct Bluetooth connection, because Intiface
 * cannot report the slider position. No other commands are sent while it runs, so its state requests cannot delay a
 * live movement stream.
 */
@Slf4j
public final class StepResponseCalibration
{
    /** Delay between two state requests of the burst. Each request is a Bluetooth round trip. */
    private static final int SAMPLE_INTERVAL_MS = 40;
    /** How many state requests may be in flight at once (the round trip is about 100ms). */
    private static final int SAMPLE_WORKERS = 5;
    /**
     * Move durations (ms for a full stroke) that are measured: short ones for the maximum speed (a Handy 2 Pro
     * with overclocking can follow shorter moves than a Handy 1), long ones for the minimum speed.
     */
    private static final int[] MOVE_DURATIONS_MS = {80, 120, 125, 150, 250, 400, 700, 1_200, 2_500, 5_000};
    private static final ObjectMapper JSON = new ObjectMapper();
    /**
     * Slow tests use a short distance (a tenth of the stroke, 10 mm on a 100 mm stroke) so that a device that can
     * move at 1 mm/s can be measured without a 100 second full stroke.
     */
    private static final double LOW_SPEED_TEST_DISTANCE = 0.10;
    private static final int[] LOW_SPEED_MM_PER_SECOND = {1, 2, 5, 10, 20, 30};
    private static final int LOW_SPEED_REPETITIONS = 2;
    /** Repetitions per duration; the direction alternates, so half of them go up and half go down. */
    private static final int REPETITIONS = 8;
    /** How long the position is sampled after the move was supposed to end. */
    private static final int SAMPLE_TAIL_MS = 600;
    /** Time the device gets to settle at the start of a move. */
    private static final int SETTLE_TIMEOUT_MS = 5_000;
    private static final double SETTLED_TOLERANCE = 0.03;

    private static final DateTimeFormatter FILE_TIME = DateTimeFormatter.ofPattern("yyyyMMdd-HHmmss-SSS");

    private StepResponseCalibration()
    {
    }

    /**
     * Runs the calibration and writes the CSV file.
     *
     * @param client   connected device
     * @param progress receives a short message before every move
     * @param cancelled checked between moves so that the app can stop the calibration
     * @return the written file
     */
    public static Path run(HandyClient client, Consumer<String> progress, BooleanSupplier cancelled) throws Exception
    {
        return run(client, Path.of("logs", "diagnostics"), DeviceProfile.PROFILES_DIRECTORY, progress, cancelled,
                MOVE_DURATIONS_MS, REPETITIONS, SAMPLE_INTERVAL_MS, SAMPLE_WORKERS, SAMPLE_TAIL_MS, true);
    }

    /** Package-private variant that writes both files to one directory, used by the tests. */
    static Path run(HandyClient client, Path directory, Consumer<String> progress, BooleanSupplier cancelled,
                    int[] durations, int repetitions, int sampleIntervalMs, int sampleWorkers, int sampleTailMs,
                    boolean lowSpeedTests)
            throws Exception
    {
        return run(client, directory, directory, progress, cancelled, durations, repetitions, sampleIntervalMs,
                sampleWorkers, sampleTailMs, lowSpeedTests);
    }

    /**
     * Runs the calibration, writing the sample CSV to {@code directory} and the device profile to
     * {@code profileDirectory}.
     */
    static Path run(HandyClient client, Path directory, Path profileDirectory, Consumer<String> progress,
                    BooleanSupplier cancelled, int[] durations, int repetitions, int sampleIntervalMs, int sampleWorkers,
                    int sampleTailMs, boolean lowSpeedTests)
            throws Exception
    {
        if (client.getSliderState().isEmpty())
        {
            throw new IllegalStateException("Step-response calibration needs a direct Bluetooth connection:"
                    + " the slider position cannot be read through Intiface.");
        }
        var modeResponse = client.changeMode(DeviceMode.HDSP);
        if (modeResponse.error() != null)
        {
            throw new IllegalStateException("Could not switch the device to HDSP: " + modeResponse.error().message());
        }
        Optional<SliderSettingsResult> sliderSettings = client.getSliderSettings();
        String deviceId = client.getDeviceIdentifier();
        int lowSpeedRuns = lowSpeedTests ? LOW_SPEED_MM_PER_SECOND.length * LOW_SPEED_REPETITIONS : 0;
        int runs = durations.length * repetitions + lowSpeedRuns;
        int run = 0;
        List<RunResult> results = new ArrayList<>();

        Files.createDirectories(directory);
        Files.createDirectories(profileDirectory);
        Path file = directory.resolve("step-response-" + LocalDateTime.now().format(FILE_TIME) + ".csv");

        try (BufferedWriter writer = Files.newBufferedWriter(file, StandardCharsets.UTF_8);
             ScheduledExecutorService scheduler = Executors.newSingleThreadScheduledExecutor(
                     runnable -> daemon("step-response-scheduler", runnable));
             ExecutorService workers = Executors.newFixedThreadPool(sampleWorkers,
                     runnable -> daemon("step-response-sampler", runnable)))
        {
            writeHeader(writer, sliderSettings.orElse(null), runs, durations, repetitions);
            // Start at the bottom; every move then alternates direction, so no separate return move is needed.
            moveTo(client, 0f, 800);
            waitForPosition(client, 0f, SETTLE_TIMEOUT_MS);

            for (int duration : durations)
            {
                for (int repetition = 0; repetition < repetitions; repetition++)
                {
                    if (cancelled.getAsBoolean() || Thread.currentThread().isInterrupted())
                    {
                        log.info("Step-response calibration cancelled");
                        return file;
                    }
                    boolean up = repetition % 2 == 0;
                    float target = up ? 1f : 0f;
                    float expectedStart = up ? 0f : 1f;
                    double startPosition = waitForPosition(client, expectedStart, duration + SETTLE_TIMEOUT_MS);
                    run++;
                    recordMove(client, writer, scheduler, workers, results, run, runs, up, false, 0, duration,
                            startPosition, target, sampleTailMs, sampleIntervalMs, cancelled, progress);
                }
            }
            if (lowSpeedTests)
            {
                // Low speeds are measured over a short distance: a 1 mm/s full stroke would take 100 seconds.
                double strokeMm = sliderSettings.map(SliderSettingsResult::strokeMm).orElse(100f);
                moveTo(client, 0.45f, 500);
                waitForPosition(client, 0.45f, SETTLE_TIMEOUT_MS);
                for (int speedMmPerSecond : LOW_SPEED_MM_PER_SECOND)
                {
                    if (cancelled.getAsBoolean() || Thread.currentThread().isInterrupted())
                    {
                        log.info("Step-response calibration cancelled");
                        return file;
                    }
                    int duration = Math.max(1, (int) Math.round(
                            LOW_SPEED_TEST_DISTANCE * strokeMm / speedMmPerSecond * 1000.0));
                    for (int repetition = 0; repetition < LOW_SPEED_REPETITIONS; repetition++)
                    {
                        boolean up = repetition % 2 == 0;
                        float start = up ? 0.45f : 0.55f;
                        float target = up ? 0.55f : 0.45f;
                        double startPosition = waitForPosition(client, start, SETTLE_TIMEOUT_MS);
                        run++;
                        recordMove(client, writer, scheduler, workers, results, run, runs, up, true, speedMmPerSecond,
                                duration, startPosition, target, sampleTailMs, sampleIntervalMs, cancelled, progress);
                    }
                }
            }
            // Leave the device at the bottom, where it is out of the way.
            moveTo(client, 0f, 800);
            waitForPosition(client, 0f, SETTLE_TIMEOUT_MS);

            String profileName = file.getFileName().toString()
                    .replace("step-response-", "device-profile-")
                    .replace(".csv", ".json");
            Path profile = profileDirectory.resolve(profileName);
            writeProfile(profile, sliderSettings.orElse(null), results, durations, repetitions, deviceId);
            log.info("Device profile written to {}", profile.toAbsolutePath());
        }
        log.info("Step-response calibration written to {}", file.toAbsolutePath());
        return file;
    }

    private static void recordMove(HandyClient client, BufferedWriter writer, ScheduledExecutorService scheduler,
                                   ExecutorService workers, List<RunResult> results, int run, int totalRuns, boolean up,
                                   boolean lowSpeed, double lowSpeedMmPerSecond, int duration, double startPosition,
                                   float target, int sampleTailMs, int sampleIntervalMs, BooleanSupplier cancelled,
                                   Consumer<String> progress)
            throws Exception
    {
        String label = lowSpeed ? "low" : "full";
        progress.accept("Step-response calibration: run %d/%d (%s %dms, %s)"
                .formatted(run, totalRuns, label, duration, up ? "up" : "down"));
        log.info("Step-response calibration: run {}/{} ({} {}ms, {}) from {}% to {}%", run, totalRuns, label, duration,
                up ? "up" : "down", "%.1f".formatted(startPosition * 100), "%.1f".formatted(target * 100));

        long commandWallMs = System.currentTimeMillis();
        long commandMonoMs = System.nanoTime() / 1_000_000L;
        var response = client.hdspXpt(target, duration, true);
        if (response.error() != null)
        {
            throw new IllegalStateException("Could not send the calibration move: " + response.error().message());
        }
        List<Sample> samples = sampleBurst(client, scheduler, workers, duration + sampleTailMs, sampleIntervalMs,
                cancelled);
        samples.sort(Comparator.comparingLong(Sample::responseWallMs));
        for (int i = 0; i < samples.size(); i++)
        {
            writeSample(writer, run, lowSpeed, up, duration, startPosition, target, commandWallMs, commandMonoMs, i,
                    samples.get(i));
        }
        writer.flush();
        results.add(new RunResult(run, up, lowSpeed, lowSpeedMmPerSecond, duration, startPosition, target,
                commandWallMs, commandMonoMs, samples));
    }

    private static void writeProfile(Path file, SliderSettingsResult settings, List<RunResult> results,
                                     int[] durations, int repetitions, String deviceId)
    {
        double strokeMm = settings != null && settings.strokeMm() != null ? settings.strokeMm() : 100.0;
        List<RunMetrics> metrics = new ArrayList<>();
        for (RunResult result : results)
        {
            metrics.add(measure(result, strokeMm));
        }
        List<Integer> speedDurations = new ArrayList<>();
        for (int duration : durations)
        {
            if (duration <= 400)
            {
                speedDurations.add(duration);
            }
        }
        int slowestDuration = durations.length == 0 ? 0 : durations[durations.length - 1];
        double maxUp = maxMedianSpeed(metrics, true, speedDurations);
        double maxDown = maxMedianSpeed(metrics, false, speedDurations);
        double peakUp = maxMedianPeak(metrics, true, speedDurations);
        double peakDown = maxMedianPeak(metrics, false, speedDurations);
        double minUp = medianSpeed(metrics, true, slowestDuration);
        double minDown = medianSpeed(metrics, false, slowestDuration);
        double slowPlannedUp = medianPlannedSpeed(metrics, true, slowestDuration);
        double slowPlannedDown = medianPlannedSpeed(metrics, false, slowestDuration);
        double latency = medianMetric(metrics, RunMetrics::motionStartMs, 50, 500);
        double firstMotion = medianMetric(metrics, RunMetrics::firstMotionMs, 0, 2_000);
        double lastStill = medianMetric(metrics, RunMetrics::lastStillMs, 0, 2_000);
        double ratioUp = medianRatio(metrics, true, 400, 1_200);
        double ratioDown = medianRatio(metrics, false, 400, 1_200);
        double overshootUp = medianMetric(metrics, RunMetrics::overshootPercent, 0, 100, true);
        double overshootDown = medianMetric(metrics, RunMetrics::overshootPercent, 0, 100, false);

        double suggestedMax = roundDownTo10(Math.min(maxUp, maxDown));
        boolean slowUpAtLimit = slowPlannedUp > 0 && minUp > slowPlannedUp * 1.15;
        boolean slowDownAtLimit = slowPlannedDown > 0 && minDown > slowPlannedDown * 1.15;
        double suggestedMinFromFull = slowUpAtLimit || slowDownAtLimit
                ? roundUpTo10(Math.max(slowUpAtLimit ? minUp : 0, slowDownAtLimit ? minDown : 0)) : 0;
        List<RunMetrics> lowMetrics = metrics.stream().filter(RunMetrics::lowSpeed).toList();
        double suggestedMinFromLow = suggestedMinFromLow(lowMetrics);
        double suggestedMin = suggestedMinFromLow > 0 ? suggestedMinFromLow : suggestedMinFromFull;

        Map<String, Object> profile = new LinkedHashMap<>();
        profile.put("deviceId", deviceId == null ? "" : deviceId);
        profile.put("calibratedAt", LocalDateTime.now().toString());
        profile.put("runs", results.size());
        profile.put("repetitions", repetitions);
        profile.put("durationsMs", durations);
        profile.put("strokeMm", strokeMm);
        if (settings != null)
        {
            profile.put("sliderMinMm", settings.minMm());
            profile.put("sliderMaxMm", settings.maxMm());
        }
        profile.put("motionStartMs", rounded(latency));
        profile.put("firstMotionSeenMs", rounded(firstMotion));
        profile.put("lastStationarySeenMs", rounded(lastStill));
        profile.put("maxSpeedUpMmPerSecond", rounded(maxUp));
        profile.put("maxSpeedDownMmPerSecond", rounded(maxDown));
        profile.put("peakSpeedUpMmPerSecond", rounded(peakUp));
        profile.put("peakSpeedDownMmPerSecond", rounded(peakDown));
        profile.put("slowestTestedSpeedUpMmPerSecond", rounded(minUp));
        profile.put("slowestTestedSpeedDownMmPerSecond", rounded(minDown));
        profile.put("slowestTestedPlannedUpMmPerSecond", rounded(slowPlannedUp));
        profile.put("slowestTestedPlannedDownMmPerSecond", rounded(slowPlannedDown));
        profile.put("lowSpeedTestDistanceMm", rounded(LOW_SPEED_TEST_DISTANCE * strokeMm));
        List<Map<String, Object>> lowResults = new ArrayList<>();
        for (int speed : LOW_SPEED_MM_PER_SECOND)
        {
            Map<String, Object> low = new LinkedHashMap<>();
            double actualUp = medianLow(lowMetrics, true, speed);
            double actualDown = medianLow(lowMetrics, false, speed);
            low.put("plannedSpeedMmPerSecond", speed);
            low.put("actualUpMmPerSecond", rounded(actualUp));
            low.put("actualDownMmPerSecond", rounded(actualDown));
            low.put("followedUp", followed(actualUp, speed));
            low.put("followedDown", followed(actualDown, speed));
            lowResults.add(low);
        }
        profile.put("lowSpeedTestResults", lowResults);
        profile.put("speedRatioUp", rounded(ratioUp));
        profile.put("speedRatioDown", rounded(ratioDown));
        profile.put("overshootPercentUp", rounded(overshootUp));
        profile.put("overshootPercentDown", rounded(overshootDown));
        profile.put("suggestedMaxSliderSpeedMmPerSecond", rounded(suggestedMax));
        profile.put("suggestedMinSliderSpeedMmPerSecond", rounded(suggestedMin));
        profile.put("note", "The app uses this profile automatically (Advanced > Smoothed mode > Use calibrated"
                + " device limits). The suggested maximum is the lower of the two measured directions, so a single"
                + " speed is safe for both. A low-speed result with followedUp/Down=false means the device ignored"
                + " the requested speed and moved at its own minimum, so that row is not a measurement of the"
                + " requested speed. A suggested minimum of 0 means the device followed the slowest tested move; it"
                + " may still have a lower minimum. The app's shortest planned move is 120 ms, so a full stroke is"
                + " limited to about 833 mm/s even if the measured maximum is higher.");
        try
        {
            JSON.writerWithDefaultPrettyPrinter().writeValue(file.toFile(), profile);
            log.info("Device profile: motion starts about {}ms after the command (first motion seen at {}ms);"
                            + " max speed {} up / {} down mm/s; slowest tested {} up / {} down mm/s;"
                            + " suggested max={} mm/s, min={} mm/s",
                    rounded(latency), rounded(firstMotion), rounded(maxUp), rounded(maxDown), rounded(minUp),
                    rounded(minDown), rounded(suggestedMax), rounded(suggestedMin));
        }
        catch (IOException e)
        {
            log.warn("Could not write the device profile to {}: {}", file, e.getMessage());
        }
    }

    private static RunMetrics measure(RunResult result, double strokeMm)
    {
        List<Sample> samples = new ArrayList<>(result.samples());
        samples.sort(Comparator.comparingLong(Sample::responseMonoMs));
        double start = result.startPosition();
        double target = result.target();
        double travel = Math.abs(target - start);
        double sign = target >= start ? 1 : -1;
        double plannedSpeed = result.duration() <= 0 ? 0 : travel * strokeMm / (result.duration() / 1000.0);
        List<Double> times = new ArrayList<>();
        List<Double> positions = new ArrayList<>();
        Sample firstMotion = null;
        Sample lastStill = null;
        for (Sample sample : samples)
        {
            if (sample.position() == null)
            {
                continue;
            }
            double t = sample.responseMonoMs() - result.commandMonoMs();
            double moved = (sample.position() - start) * sign;
            if (moved > 0.01 * travel && firstMotion == null)
            {
                firstMotion = sample;
            }
            if (firstMotion == null && moved <= 0.005 * travel)
            {
                lastStill = sample;
            }
            times.add(t);
            positions.add(moved);
        }
        double firstMs = firstMotion == null ? Double.NaN : firstMotion.responseMonoMs() - result.commandMonoMs();
        double lastStillMs = lastStill == null ? Double.NaN : lastStill.responseMonoMs() - result.commandMonoMs();
        double motionStart = Double.NaN;
        List<Double> earlyT = new ArrayList<>();
        List<Double> earlyX = new ArrayList<>();
        for (int i = 0; i < samples.size() && earlyT.size() < 4; i++)
        {
            Sample sample = samples.get(i);
            if (sample.position() == null)
            {
                continue;
            }
            double moved = (sample.position() - start) * sign;
            if (moved > 0.03 * travel && moved < 0.35 * travel)
            {
                earlyT.add((double) (sample.responseMonoMs() - result.commandMonoMs()));
                earlyX.add(moved);
            }
        }
        if (earlyT.size() >= 2)
        {
            double slope = slope(earlyT, earlyX);
            if (slope > 0)
            {
                double sum = 0;
                for (int i = 0; i < earlyT.size(); i++)
                {
                    sum += earlyT.get(i) - earlyX.get(i) / slope;
                }
                motionStart = sum / earlyT.size();
            }
        }
        if (Double.isNaN(motionStart) && !Double.isNaN(firstMs))
        {
            motionStart = Double.isNaN(lastStillMs) ? firstMs : (firstMs + lastStillMs) / 2;
        }
        List<Double> midT = new ArrayList<>();
        List<Double> midX = new ArrayList<>();
        double peak = 0;
        for (int i = 0; i < samples.size(); i++)
        {
            Sample sample = samples.get(i);
            if (sample.position() == null)
            {
                continue;
            }
            double t = sample.responseMonoMs() - result.commandMonoMs();
            double moved = (sample.position() - start) * sign;
            if (moved > 0.15 * travel && moved < 0.85 * travel)
            {
                midT.add(t);
                midX.add(moved);
            }
            for (int j = i + 1; j < samples.size() && j <= i + 8; j++)
            {
                Sample next = samples.get(j);
                if (next.position() == null)
                {
                    continue;
                }
                double dt = (next.responseMonoMs() - sample.responseMonoMs()) / 1000.0;
                if (dt < 0.04 || dt > 0.2)
                {
                    continue;
                }
                double v = ((next.position() - sample.position()) * sign) / dt * strokeMm;
                peak = Math.max(peak, v);
            }
        }
        double actualSpeed = midT.size() >= 2 ? slope(midT, midX) * strokeMm * 1000 : 0;
        double overshoot = 0;
        for (Sample sample : samples)
        {
            if (sample.position() != null)
            {
                overshoot = Math.max(overshoot, (sample.position() - target) * sign * 100);
            }
        }
        return new RunMetrics(result.run(), result.up(), result.lowSpeed(), result.lowSpeedMmPerSecond(),
                result.duration(), plannedSpeed, actualSpeed, peak, firstMs, lastStillMs, motionStart, overshoot,
                plannedSpeed > 0 ? actualSpeed / plannedSpeed : 0);
    }

    private static double slope(List<Double> xs, List<Double> ys)
    {
        int n = xs.size();
        if (n < 2)
        {
            return 0;
        }
        double mx = 0;
        double my = 0;
        for (int i = 0; i < n; i++)
        {
            mx += xs.get(i);
            my += ys.get(i);
        }
        mx /= n;
        my /= n;
        double covariance = 0;
        double variance = 0;
        for (int i = 0; i < n; i++)
        {
            double dx = xs.get(i) - mx;
            covariance += dx * (ys.get(i) - my);
            variance += dx * dx;
        }
        return variance <= 0 ? 0 : covariance / variance;
    }

    private static double suggestedMinFromLow(List<RunMetrics> lowMetrics)
    {
        if (lowMetrics.isEmpty())
        {
            return 0;
        }
        List<Integer> speeds = new ArrayList<>();
        for (int speed : LOW_SPEED_MM_PER_SECOND)
        {
            speeds.add(speed);
        }
        for (int speed : speeds)
        {
            double up = medianLow(lowMetrics, true, speed);
            double down = medianLow(lowMetrics, false, speed);
            if (followed(up, speed) || followed(down, speed))
            {
                return speed;
            }
        }
        double slowestActual = lowMetrics.stream()
                .mapToDouble(RunMetrics::actualSpeed)
                .filter(value -> value > 0)
                .min()
                .orElse(0);
        return roundUpTo10(slowestActual);
    }

    /**
     * @return true when the device moved at about the speed it was asked for. A device that cannot move that slowly
     * ignores the requested duration and moves at its own minimum speed instead; reporting that as the measured
     * speed would look like a very fast slow-move.
     */
    private static boolean followed(double actual, int planned)
    {
        return actual > 0 && actual <= planned * 1.25 + 2;
    }

    private static double medianLow(List<RunMetrics> metrics, boolean up, int speed)
    {
        return median(metrics.stream().filter(r -> r.lowSpeed() && r.up() == up
                        && Math.abs(r.lowSpeedMmPerSecond() - speed) < 0.01)
                .map(RunMetrics::actualSpeed).toList());
    }

    private static double medianSpeed(List<RunMetrics> metrics, boolean up, int duration)
    {
        return median(metrics.stream().filter(r -> !r.lowSpeed() && r.up() == up && r.duration() == duration)
                .map(RunMetrics::actualSpeed).toList());
    }

    private static double medianPlannedSpeed(List<RunMetrics> metrics, boolean up, int duration)
    {
        return median(metrics.stream().filter(r -> !r.lowSpeed() && r.up() == up && r.duration() == duration)
                .map(RunMetrics::plannedSpeed).toList());
    }

    private static double maxMedianSpeed(List<RunMetrics> metrics, boolean up, List<Integer> durations)
    {
        double max = 0;
        for (int duration : durations)
        {
            max = Math.max(max, medianSpeed(metrics, up, duration));
        }
        return max;
    }

    private static double maxMedianPeak(List<RunMetrics> metrics, boolean up, List<Integer> durations)
    {
        double max = 0;
        for (int duration : durations)
        {
            max = Math.max(max, median(metrics.stream().filter(r -> !r.lowSpeed() && r.up() == up
                    && r.duration() == duration).map(RunMetrics::peakSpeed).toList()));
        }
        return max;
    }

    private static double medianRatio(List<RunMetrics> metrics, boolean up, int minDuration, int maxDuration)
    {
        return median(metrics.stream().filter(r -> !r.lowSpeed() && r.up() == up
                && r.duration() >= minDuration && r.duration() <= maxDuration).map(RunMetrics::ratio).toList());
    }

    private static double medianMetric(List<RunMetrics> metrics, java.util.function.ToDoubleFunction<RunMetrics> getter,
                                       double min, double max)
    {
        return medianMetric(metrics, getter, min, max, null);
    }

    private static double medianMetric(List<RunMetrics> metrics, java.util.function.ToDoubleFunction<RunMetrics> getter,
                                       double min, double max, Boolean up)
    {
        return median(metrics.stream().filter(r -> (up == null || r.up() == up)
                        && Double.isFinite(getter.applyAsDouble(r))
                        && getter.applyAsDouble(r) >= min && getter.applyAsDouble(r) <= max)
                .map(getter::applyAsDouble).toList());
    }

    private static double median(List<Double> values)
    {
        if (values.isEmpty())
        {
            return 0;
        }
        List<Double> sorted = new ArrayList<>(values);
        sorted.sort(Double::compare);
        int middle = sorted.size() / 2;
        return sorted.size() % 2 == 0 ? (sorted.get(middle - 1) + sorted.get(middle)) / 2 : sorted.get(middle);
    }

    private static double roundDownTo10(double value)
    {
        // Add a hair so that a measured 799.999999 rounds down to 800, not 790.
        return value <= 0 ? 0 : Math.floor((value + 0.01) / 10.0) * 10;
    }

    private static double roundUpTo10(double value)
    {
        // Subtract a hair so that a measured 30.0000000001 rounds to 30, while 30.1 still rounds up to 40.
        return value <= 0 ? 0 : Math.ceil((value - 0.01) / 10.0) * 10;
    }

    private static double rounded(double value)
    {
        return Double.isFinite(value) ? Math.round(value * 10.0) / 10.0 : 0;
    }

    /** One calibration move with all samples, used to build the device profile. */
    private record RunResult(int run, boolean up, boolean lowSpeed, double lowSpeedMmPerSecond, int duration,
                            double startPosition, double target, long commandWallMs, long commandMonoMs,
                            List<Sample> samples)
    {
    }

    /** Measurements of one move: speeds in mm/s, times in milliseconds, overshoot in percent. */
    private record RunMetrics(int run, boolean up, boolean lowSpeed, double lowSpeedMmPerSecond, int duration,
                              double plannedSpeed, double actualSpeed, double peakSpeed, double firstMotionMs,
                              double lastStillMs, double motionStartMs, double overshootPercent, double ratio)
    {
    }

    private static Thread daemon(String name, Runnable runnable)
    {
        Thread thread = new Thread(runnable, name);
        thread.setDaemon(true);
        return thread;
    }

    private static void writeHeader(BufferedWriter writer, SliderSettingsResult settings, int runs, int[] durations,
                                    int repetitions) throws IOException
    {
        writer.write("# HandyVRC-OSC step-response calibration\n");
        writer.write("# started=%s\n".formatted(LocalDateTime.now()));
        writer.write("# runs=%d, durationsMs=%s, repetitions=%d\n".formatted(runs, java.util.Arrays.toString(durations),
                repetitions));
        if (settings != null)
        {
            writer.write("# sliderMinMm=%s, sliderMaxMm=%s, strokeMm=%s\n".formatted(settings.minMm(), settings.maxMm(),
                    settings.strokeMm()));
        }
        writer.write("# full rows are full-stroke moves; low rows are short-distance slow moves\n");
        writer.write("run,test,direction,plannedDurationMs,startPosition,target,commandWallMs,commandMonoMs,"
                + "sampleIndex,requestWallMs,requestMonoMs,responseWallMs,responseMonoMs,rttMs,position,positionMm,"
                + "reportedSpeedMmS\n");
    }

    private static void writeSample(BufferedWriter writer, int run, boolean lowSpeed, boolean up, int duration,
                                    double startPosition, float target, long commandWallMs, long commandMonoMs, int index,
                                    Sample sample) throws IOException
    {
        writer.write(String.format(Locale.ROOT,
                "%d,%s,%s,%d,%.6f,%.6f,%d,%d,%d,%d,%d,%d,%d,%d,%s,%s,%s%n",
                run, lowSpeed ? "low" : "full", up ? "up" : "down", duration, startPosition, target,
                commandWallMs, commandMonoMs, index,
                sample.requestWallMs(), sample.requestMonoMs(), sample.responseWallMs(), sample.responseMonoMs(),
                sample.rttMs(),
                sample.position() == null ? "" : String.format(Locale.ROOT, "%.6f", sample.position()),
                sample.positionMm() == null ? "" : String.format(Locale.ROOT, "%.3f", sample.positionMm()),
                sample.speedMmPerSecond() == null ? "" : String.format(Locale.ROOT, "%.2f", sample.speedMmPerSecond())));
    }

    /**
     * Sends state requests every {@link #SAMPLE_INTERVAL_MS} for the given duration without waiting for the previous
     * response (the RPC client matches the responses by id). This is what makes the samples much denser than the
     * normal synchronous device log: the round trip is about 100ms, but five requests can be in flight.
     */
    private static List<Sample> sampleBurst(HandyClient client, ScheduledExecutorService scheduler,
                                            ExecutorService workers, int durationMs, int sampleIntervalMs,
                                            BooleanSupplier cancelled) throws Exception
    {
        int count = Math.max(1, durationMs / sampleIntervalMs);
        List<CompletableFuture<Sample>> futures = new ArrayList<>(count);
        for (int i = 0; i < count; i++)
        {
            CompletableFuture<Sample> future = new CompletableFuture<>();
            futures.add(future);
            long delayMs = (long) i * sampleIntervalMs;
            scheduler.schedule(() ->
            {
                if (cancelled.getAsBoolean() || Thread.currentThread().isInterrupted())
                {
                    future.complete(new Sample(-1, -1, -1, -1, null, null, null));
                    return;
                }
                workers.execute(() -> future.complete(sample(client)));
            }, delayMs, TimeUnit.MILLISECONDS);
        }
        CompletableFuture.allOf(futures.toArray(CompletableFuture[]::new))
                .get(durationMs + 10_000L, TimeUnit.MILLISECONDS);
        List<Sample> samples = new ArrayList<>(count);
        for (CompletableFuture<Sample> future : futures)
        {
            Sample sample = future.getNow(null);
            if (sample != null && sample.position() != null)
            {
                samples.add(sample);
            }
        }
        return samples;
    }

    private static Sample sample(HandyClient client)
    {
        long requestWallMs = System.currentTimeMillis();
        long requestMonoMs = System.nanoTime() / 1_000_000L;
        try
        {
            Optional<SliderState> state = client.getSliderState();
            long responseWallMs = System.currentTimeMillis();
            long responseMonoMs = System.nanoTime() / 1_000_000L;
            if (state.isEmpty())
            {
                return new Sample(requestWallMs, requestMonoMs, responseWallMs, responseMonoMs, null, null, null);
            }
            return new Sample(requestWallMs, requestMonoMs, responseWallMs, responseMonoMs,
                    state.get().position(), state.get().positionMm(), state.get().speedMmPerSecond());
        }
        catch (Exception e)
        {
            long responseWallMs = System.currentTimeMillis();
            long responseMonoMs = System.nanoTime() / 1_000_000L;
            log.debug("Calibration state request failed: {}", e.getMessage());
            return new Sample(requestWallMs, requestMonoMs, responseWallMs, responseMonoMs, null, null, null);
        }
    }

    private static void moveTo(HandyClient client, float target, int durationMs) throws Exception
    {
        var response = client.hdspXpt(target, durationMs, true);
        if (response.error() != null)
        {
            throw new IllegalStateException("Could not move the device: " + response.error().message());
        }
    }

    /** @return the position the device reports, or NaN when it could not be read within the timeout */
    private static double waitForPosition(HandyClient client, float target, long timeoutMs) throws InterruptedException
    {
        long deadline = System.currentTimeMillis() + timeoutMs;
        double last = Double.NaN;
        while (System.currentTimeMillis() < deadline)
        {
            Optional<SliderState> state = client.getSliderState();
            if (state.isPresent())
            {
                last = state.get().position();
                if (Math.abs(last - target) <= SETTLED_TOLERANCE)
                {
                    return last;
                }
            }
            Thread.sleep(100);
        }
        log.warn("Device did not reach {}% within {}ms (last position {})", target * 100, timeoutMs,
                Double.isNaN(last) ? "unknown" : String.format(Locale.ROOT, "%.1f%%", last * 100));
        return Double.isNaN(last) ? target : last;
    }

    /** One slider-state sample, with null position when the request failed. */
    private record Sample(long requestWallMs, long requestMonoMs, long responseWallMs, long responseMonoMs,
                          Float position, Float positionMm, Float speedMmPerSecond)
    {
        long rttMs()
        {
            return responseWallMs - requestWallMs;
        }
    }
}
