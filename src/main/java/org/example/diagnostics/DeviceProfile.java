package org.example.diagnostics;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.extern.slf4j.Slf4j;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Comparator;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.stream.Stream;

/**
 * What a calibration measured on one device: how fast it can move in each direction, how slowly it can move, and
 * how long it takes from a command to the first movement.
 * <p>
 * The values are read from the {@code device-profile-*.json} files the step-response calibration writes. The app
 * loads the newest profile automatically, so a Handy 1, a Handy 2 Pro or another example of the same model can be
 * driven with its own measured limits instead of a value compiled into the app.
 */
@Slf4j
public record DeviceProfile(
        String deviceId,
        String calibratedAt,
        double strokeMm,
        double motionStartMs,
        double maxSpeedUpMmPerSecond,
        double maxSpeedDownMmPerSecond,
        double suggestedMaxSliderSpeedMmPerSecond,
        double suggestedMinSliderSpeedMmPerSecond)
{
    /** Where calibrations write their profiles, relative to the app's working directory. */
    public static final Path PROFILES_DIRECTORY = Path.of("profiles");
    /** Where older builds wrote profiles; still read so that an existing calibration is not lost. */
    public static final Path LEGACY_DIRECTORY = Path.of("logs", "diagnostics");

    private static final ObjectMapper JSON = new ObjectMapper();
    private static final String FILE_PREFIX = "device-profile-";
    private static final String FILE_SUFFIX = ".json";

    /**
     * @return the single maximum speed the app should plan with: the calibration's suggestion (the lower of the two
     * measured directions), or the lower measured direction when an older profile has no suggestion
     */
    public double plannedMaxMmPerSecond()
    {
        if (suggestedMaxSliderSpeedMmPerSecond > 0)
        {
            return suggestedMaxSliderSpeedMmPerSecond;
        }
        double lowerDirection = Math.min(maxSpeedUpMmPerSecond, maxSpeedDownMmPerSecond);
        return Math.max(0, lowerDirection);
    }

    /** @return the slowest speed the app should plan with, 0 when the device followed the slowest test */
    public double plannedMinMmPerSecond()
    {
        return Math.max(0, suggestedMinSliderSpeedMmPerSecond);
    }

    /**
     * Loads the newest profile for the connected device, first from {@link #PROFILES_DIRECTORY} and then from the
     * old {@link #LEGACY_DIRECTORY}.
     */
    public static Optional<DeviceProfile> loadLatest(String deviceId)
    {
        return loadLatest(PROFILES_DIRECTORY, LEGACY_DIRECTORY, deviceId);
    }

    /** Loads the profile from the first directory that has one (see {@link #loadLatest(String)}). */
    public static Optional<DeviceProfile> loadLatest(Path primaryDirectory, Path legacyDirectory, String deviceId)
    {
        Optional<DeviceProfile> profile = loadLatest(primaryDirectory, deviceId);
        return profile.isPresent() ? profile : loadLatest(legacyDirectory, deviceId);
    }

    /**
     * Loads the profile that fits the connected device. A profile with the same device id wins; a profile written
     * before the id was stored is used as a fallback. A device with a different id does not silently get another
     * device's limits.
     *
     * @param directory directory the calibration wrote to (usually {@code profiles})
     * @param deviceId  identifier of the connected device, blank when it cannot be read
     */
    public static Optional<DeviceProfile> loadLatest(Path directory, String deviceId)
    {
        if (directory == null || !Files.isDirectory(directory))
        {
            return Optional.empty();
        }
        List<Path> files;
        try (Stream<Path> stream = Files.list(directory))
        {
            files = stream
                    .filter(path -> path.getFileName().toString().startsWith(FILE_PREFIX))
                    .filter(path -> path.getFileName().toString().endsWith(FILE_SUFFIX))
                    .sorted(Comparator.comparingLong(DeviceProfile::lastModified).reversed())
                    .toList();
        }
        catch (IOException e)
        {
            log.warn("Could not list the device profiles in {}: {}", directory, e.getMessage());
            return Optional.empty();
        }

        String wanted = deviceId == null ? "" : deviceId.trim();
        DeviceProfile newestWithoutId = null;
        DeviceProfile newestAny = null;
        for (Path file : files)
        {
            DeviceProfile profile = read(file).orElse(null);
            if (profile == null)
            {
                continue;
            }
            if (newestAny == null)
            {
                newestAny = profile;
            }
            if (profile.deviceId() == null || profile.deviceId().isBlank())
            {
                if (newestWithoutId == null)
                {
                    newestWithoutId = profile;
                }
                continue;
            }
            if (!wanted.isEmpty() && wanted.equals(profile.deviceId()))
            {
                return Optional.of(profile);
            }
        }
        if (!wanted.isEmpty())
        {
            // A profile without an id cannot be attributed to another device, so it is safe to use as a fallback.
            if (newestWithoutId != null)
            {
                log.info("No device profile matches this device; using the newest profile without a device id");
                return Optional.of(newestWithoutId);
            }
            return Optional.empty();
        }
        if (newestAny != null)
        {
            return Optional.of(newestAny);
        }
        return Optional.empty();
    }

    /** Reads one profile file, tolerating fields an older build did not write. */
    static Optional<DeviceProfile> read(Path file)
    {
        try
        {
            return Optional.of(fromJson(JSON.readTree(file.toFile())));
        }
        catch (IOException | RuntimeException e)
        {
            log.warn("Could not read the device profile {}: {}", file, e.getMessage());
            return Optional.empty();
        }
    }

    static DeviceProfile fromJson(JsonNode root)
    {
        Objects.requireNonNull(root, "root");
        return new DeviceProfile(
                text(root, "deviceId"),
                text(root, "calibratedAt"),
                number(root, "strokeMm", 100),
                number(root, "motionStartMs", 0),
                number(root, "maxSpeedUpMmPerSecond", 0),
                number(root, "maxSpeedDownMmPerSecond", 0),
                number(root, "suggestedMaxSliderSpeedMmPerSecond", 0),
                number(root, "suggestedMinSliderSpeedMmPerSecond", 0));
    }

    private static long lastModified(Path file)
    {
        try
        {
            return Files.getLastModifiedTime(file).toMillis();
        }
        catch (IOException e)
        {
            return Long.MIN_VALUE;
        }
    }

    private static String text(JsonNode root, String field)
    {
        JsonNode node = root.get(field);
        return node == null || node.isNull() ? "" : node.asText("");
    }

    private static double number(JsonNode root, String field, double defaultValue)
    {
        JsonNode node = root.get(field);
        return node == null || !node.isNumber() ? defaultValue : node.asDouble();
    }
}
