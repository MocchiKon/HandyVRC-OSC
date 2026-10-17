package org.example.config;

import ch.qos.logback.classic.Level;
import lombok.extern.slf4j.Slf4j;
import org.apache.commons.lang3.StringUtils;
import org.example.processor.HdspSmoothedParameterProcessor;
import org.example.processor.ParameterProcessorType;
import org.example.processor.SpsType;

import java.io.IOException;
import java.io.Reader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.Locale;
import java.util.Optional;
import java.util.Properties;
import java.util.function.Function;

/**
 * Reads and writes app.properties. The settings are edited in the GUI, so the file is mostly written by the app,
 * but it stays a plain, commented properties file that can be edited by hand.
 * <p>
 * Reading is lenient: a missing, invalid or out of range value (for example from a file written by an older
 * version) falls back to its default with a warning instead of preventing the app from starting.
 */
@Slf4j
public class ConfigLoader
{
    private final Path appConfigPath;

    public ConfigLoader(Path appConfigPath)
    {
        this.appConfigPath = appConfigPath;
    }

    public Path getPath()
    {
        return appConfigPath;
    }

    /**
     * @return the settings from app.properties, or the default settings (which are written to app.properties)
     * when the file does not exist yet
     */
    public ConfigProperties readOrInitConfig() throws IOException
    {
        if (Files.notExists(appConfigPath))
        {
            ConfigProperties defaults = ConfigProperties.defaults();
            save(defaults);
            log.info("Created {} with the default settings", appConfigPath);
            setLoggingLevel(Level.toLevel(defaults.logLevel()));
            return defaults;
        }
        Properties properties = new Properties();
        try (Reader reader = Files.newBufferedReader(appConfigPath, StandardCharsets.UTF_8))
        {
            properties.load(reader);
        }
        ConfigProperties config = parse(properties);
        setLoggingLevel(Level.toLevel(config.logLevel()));
        log.info("Loaded config: {}", config.toLoggableString());
        return config;
    }

    public static void setLoggingLevel(Level level)
    {
        ch.qos.logback.classic.Logger root = (ch.qos.logback.classic.Logger) org.slf4j.LoggerFactory.getLogger(ch.qos.logback.classic.Logger.ROOT_LOGGER_NAME);
        root.setLevel(level);
    }

    static ConfigProperties parse(Properties properties)
    {
        ConfigProperties defaults = ConfigProperties.defaults();

        ConnectionType connection = getEnum(properties, "connection", ConnectionType.class, defaults.connection());
        SpsType spsType = getEnum(properties, "spsType", SpsType.class, defaults.spsType());
        InteractWith interactWith = getEnum(properties, "interactWith", InteractWith.class, defaults.interactWith());
        ParameterProcessorType processingAlgorithm = getProperty(properties, "processingAlgorithm")
                .map(value -> parseProcessingAlgorithm(value, defaults.processingAlgorithm()))
                .orElse(defaults.processingAlgorithm());

        Optional<String> customAvatarParameter = getProperty(properties, "avatarParameter");
        String avatarParameter = customAvatarParameter.orElseGet(() -> ConfigProperties.defaultAvatarParameter(spsType, interactWith));

        Float sliderMin = getFloat(properties, "sliderMin", defaults.sliderMin(), 0f, 1f);
        Float sliderMax = getFloat(properties, "sliderMax", defaults.sliderMax(), 0f, 1f);
        if (sliderMin >= sliderMax)
        {
            log.warn("'sliderMin' ({}) has to be lower than 'sliderMax' ({}), using the full stroke", sliderMin, sliderMax);
            sliderMin = defaults.sliderMin();
            sliderMax = defaults.sliderMax();
        }

        return ConfigProperties.builder()
                .connection(connection)
                .intifaceAddress(getProperty(properties, "intifaceAddress").orElse(defaults.intifaceAddress()))
                .spsType(spsType)
                .interactWith(interactWith)
                .processingAlgorithm(processingAlgorithm)
                .avatarParameter(avatarParameter)
                .customAvatarParameter(customAvatarParameter.isPresent())
                .sliderMin(sliderMin)
                .sliderMax(sliderMax)
                .fullyPenetratedAtValue(getFloat(properties, "fullyPenetratedAtValue", defaults.fullyPenetratedAtValue(), 1f, 100f))
                .reverseMovement(getBoolean(properties, "reverseMovement", defaults.reverseMovement()))
                .listenOnPort(getInt(properties, "listenOnPort", defaults.listenOnPort(), 1, 65535))
                .useOscQuery(getBoolean(properties, "useOscQuery", defaults.useOscQuery()))
                .sendMessageEveryMs(getInt(properties, "sendMessageEveryMs", defaults.sendMessageEveryMs(), 0, 10_000))
                .checkForUpdates(getBoolean(properties, "checkForUpdates", defaults.checkForUpdates()))
                .minimalValueChange(getInt(properties, "minimalValueChange", defaults.minimalValueChange(), 0, 100))
                .pauseOnStarving(getBoolean(properties, "pauseOnStarving", defaults.pauseOnStarving()))
                .hdspSpeedChangeThresholdPercent(getInt(properties, "hdspSpeedChangeThresholdPercent", defaults.hdspSpeedChangeThresholdPercent(), 1, 1000))
                .hdspSpeedStopThresholdPercent(getInt(properties, "hdspSpeedStopThresholdPercent", defaults.hdspSpeedStopThresholdPercent(), 1, 100))
                .hdspDivergenceThresholdPercent(getInt(properties, "hdspDivergenceThresholdPercent", defaults.hdspDivergenceThresholdPercent(), 1, 100))
                .hdspSpeedMeasureWindowMs(getInt(properties, "hdspSpeedMeasureWindowMs", defaults.hdspSpeedMeasureWindowMs(), 1, 10_000))
                .hdspMinSliderSpeed(getInt(properties, "hdspMinSliderSpeed", defaults.hdspMinSliderSpeed(), 0, 1000))
                .hdspMaxSliderSpeed(getInt(properties, "hdspMaxSliderSpeed", defaults.hdspMaxSliderSpeed(), 0, 2000))
                .hdspUseDeviceProfile(getBoolean(properties, "hdspUseDeviceProfile", defaults.hdspUseDeviceProfile()))
                .hdspReversalMinDistancePercent(getFloat(properties, "hdspReversalMinDistancePercent", defaults.hdspReversalMinDistancePercent(), 0f, 100f))
                .hdspLogDevicePosition(getBoolean(properties, "hdspLogDevicePosition", defaults.hdspLogDevicePosition()))
                .hdspDevicePositionLogIntervalMs(getInt(properties, "hdspDevicePositionLogIntervalMs",
                        defaults.hdspDevicePositionLogIntervalMs(),
                        HdspSmoothedParameterProcessor.MIN_DEVICE_POSITION_LOG_INTERVAL_MS,
                        HdspSmoothedParameterProcessor.MAX_DEVICE_POSITION_LOG_INTERVAL_MS))
                .hdspDiagnosticTrace(getBoolean(properties, "hdspDiagnosticTrace", defaults.hdspDiagnosticTrace()))
                .logLevel(getProperty(properties, "logLevel").orElse(defaults.logLevel()))
                .build();
    }

    /**
     * Accepts the names written to the file (HDSP, HDSP_SMOOTHED) as well as the names shown in the GUI (DIRECT,
     * SMOOTHED). HSP is not supported any more, so a file written by an older version falls back to the default.
     */
    private static ParameterProcessorType parseProcessingAlgorithm(String value, ParameterProcessorType defaultValue)
    {
        String normalized = value.trim().toUpperCase(Locale.ROOT);
        for (ParameterProcessorType type : ParameterProcessorType.values())
        {
            if (type.name().equals(normalized) || type.getLabel().toUpperCase(Locale.ROOT).equals(normalized))
            {
                return type;
            }
        }
        log.warn("Processing algorithm '{}' is not supported, using {} instead", value, defaultValue.getLabel());
        return defaultValue;
    }

    /**
     * Writes the settings to app.properties. The file is replaced in one step, so a crash while saving cannot
     * leave a half written file behind.
     */
    public void save(ConfigProperties config) throws IOException
    {
        Path parent = appConfigPath.toAbsolutePath().getParent();
        Path tempFile = Files.createTempFile(parent, "app", ".properties.tmp");
        try
        {
            Files.writeString(tempFile, format(config), StandardCharsets.UTF_8);
            try
            {
                Files.move(tempFile, appConfigPath, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
            }
            catch (IOException e)
            {
                Files.move(tempFile, appConfigPath, StandardCopyOption.REPLACE_EXISTING);
            }
        }
        finally
        {
            Files.deleteIfExists(tempFile);
        }
    }

    static String format(ConfigProperties config)
    {
        var out = new StringBuilder();
        out.append("""
                # HandyVRC-OSC settings. They are edited in the app window, which rewrites this file whenever a setting
                # changes. You can still edit it by hand while the app is closed.

                """);
        section(out, "Basic");
        entry(out, "connection", config.connection(),
                "DIRECT (default, recommended: the app connects to the Handy over Bluetooth) or INTIFACE (through Intiface Central).");
        entry(out, "intifaceAddress", config.intifaceAddress(),
                "WebSocket address of the Intiface Central server. Only used when connection=INTIFACE.");
        entry(out, "spsType", config.spsType(),
                "PENETRATOR or ORIFICE: which SPS component of your avatar moves the device.");
        entry(out, "interactWith", config.interactWith(),
                "SELF, OTHERS or BOTH: whose SPS contacts move the device (you, other players, or both).");
        entry(out, "processingAlgorithm", config.processingAlgorithm(),
                "HDSP_SMOOTHED (shown as 'Smoothed', default) or HDSP (shown as 'Direct').");
        entry(out, "avatarParameter", config.customAvatarParameter() ? config.avatarParameter() : "",
                "Custom avatar parameter. Blank uses the default for spsType and interactWith, for example:",
                "PENETRATOR - " + ConfigProperties.DEFAULT_PENETRATOR_PARAMETER,
                "ORIFICE    - " + ConfigProperties.DEFAULT_ORIFICE_PARAMETER + " (has to end with 'NewRoot')");
        entry(out, "sliderMin", config.sliderMin(), "Bottom of the stroke the device uses (0.0 - 1.0).");
        entry(out, "sliderMax", config.sliderMax(), "Top of the stroke the device uses (0.0 - 1.0).");
        entry(out, "fullyPenetratedAtValue", config.fullyPenetratedAtValue(),
                "Penetration (in %) that counts as fully penetrated, so that the full stroke is used.");
        entry(out, "reverseMovement", config.reverseMovement(),
                "Reverses the slider movement: fully penetrated moves the device to the top instead of the bottom.");
        entry(out, "listenOnPort", config.listenOnPort(),
                "OSC port. Only used when useOscQuery=false or when OSCQuery could not be started.");
        entry(out, "useOscQuery", config.useOscQuery(),
                "Lets VRChat know where to send OSC messages, so that other OSC apps cannot block the port.");
        section(out, "Advanced");
        entry(out, "sendMessageEveryMs", config.sendMessageEveryMs(), "Minimum time (ms) between two commands sent to the device.");
        entry(out, "checkForUpdates", config.checkForUpdates(), "Checks GitHub for a newer version on startup.");
        entry(out, "minimalValueChange", config.minimalValueChange(), "(Direct only) Smallest position change (in %) that is sent to the device.");
        entry(out, "pauseOnStarving", config.pauseOnStarving(), "(Direct only, on by default) Stops the device at every position instead of moving on.");
        entry(out, "hdspSpeedChangeThresholdPercent", config.hdspSpeedChangeThresholdPercent(), "(Smoothed only) Speed change (in %) that corrects the movement.");
        entry(out, "hdspSpeedStopThresholdPercent", config.hdspSpeedStopThresholdPercent(), "(Smoothed only) Speed (in % of the peak speed) that counts as a stop.");
        entry(out, "hdspDivergenceThresholdPercent", config.hdspDivergenceThresholdPercent(), "(Smoothed only) Distance (in % of the stroke) the device may fall behind.");
        entry(out, "hdspSpeedMeasureWindowMs", config.hdspSpeedMeasureWindowMs(), "(Smoothed only) Time span (ms) the speed is measured over.");
        entry(out, "hdspMinSliderSpeed", config.hdspMinSliderSpeed(), "(Smoothed only) Slowest speed (mm/s) the device moves with, 0 disables it.");
        entry(out, "hdspMaxSliderSpeed", config.hdspMaxSliderSpeed(), "(Smoothed only) Highest speed (mm/s) the device can move, 0 leaves it unlimited.");
        entry(out, "hdspUseDeviceProfile", config.hdspUseDeviceProfile(),
                "(Smoothed only) Uses the minimum speed, maximum speed and motion start measured by the newest"
                        + " calibration in logs/diagnostics/ instead of the two settings above. Calibrate the device"
                        + " once per device and after a large mechanical change.");
        entry(out, "hdspReversalMinDistancePercent", config.hdspReversalMinDistancePercent(), "(Smoothed only) Distance (in % of the stroke) a movement has to go back to count as a reversal.");
        entry(out, "hdspLogDevicePosition", config.hdspLogDevicePosition(), "(Smoothed only) Logs the device position (diagnostics).");
        entry(out, "hdspDevicePositionLogIntervalMs", config.hdspDevicePositionLogIntervalMs(),
                "(Smoothed only) How often (ms) the device position is read while logging. The actual period is this"
                        + " plus the ~100 ms round trip; use 50 for a short diagnostic run, not permanently.");
        section(out, "Diagnostics");
        entry(out, "hdspDiagnosticTrace", config.hdspDiagnosticTrace(),
                "(Smoothed only) Writes a structured JSONL trace of values, commands, device samples and BLE"
                        + " notifications to logs/diagnostics/.");
        entry(out, "logLevel", config.logLevel(), "TRACE, DEBUG, INFO, WARN or ERROR.");
        return out.toString();
    }

    private static void section(StringBuilder out, String name)
    {
        out.append("########## ").append(name).append(" ##########\n\n");
    }

    private static void entry(StringBuilder out, String key, Object value, String... comments)
    {
        for (String comment : comments)
        {
            out.append("# ").append(comment).append('\n');
        }
        out.append(key).append('=').append(value == null ? "" : escape(String.valueOf(value))).append("\n\n");
    }

    /** Escapes the characters that have a meaning in a properties file value. */
    private static String escape(String value)
    {
        var escaped = new StringBuilder();
        for (char c : value.toCharArray())
        {
            switch (c)
            {
                case '\\' -> escaped.append("\\\\");
                case '\n' -> escaped.append("\\n");
                case '\r' -> escaped.append("\\r");
                case '\t' -> escaped.append("\\t");
                default -> escaped.append(c);
            }
        }
        if (!escaped.isEmpty() && escaped.charAt(0) == ' ')
        {
            escaped.insert(0, '\\');
        }
        return escaped.toString();
    }

    private static <E extends Enum<E>> E getEnum(Properties properties, String name, Class<E> type, E defaultValue)
    {
        return parse(properties, name, defaultValue, value -> Enum.valueOf(type, value.trim().toUpperCase(Locale.ROOT)));
    }

    private static boolean getBoolean(Properties properties, String name, boolean defaultValue)
    {
        return parse(properties, name, defaultValue, value ->
        {
            String normalized = value.trim().toLowerCase(Locale.ROOT);
            if (!normalized.equals("true") && !normalized.equals("false"))
            {
                throw new IllegalArgumentException("not true/false");
            }
            return Boolean.parseBoolean(normalized);
        });
    }

    private static int getInt(Properties properties, String name, int defaultValue, int min, int max)
    {
        int value = parse(properties, name, defaultValue, v -> Integer.parseInt(v.trim()));
        if (value < min || value > max)
        {
            log.warn("'{}' ({}) has to be between {} and {}, using {} instead", name, value, min, max, defaultValue);
            return defaultValue;
        }
        return value;
    }

    private static Float getFloat(Properties properties, String name, Float defaultValue, float min, float max)
    {
        float value = parse(properties, name, defaultValue, v -> Float.parseFloat(v.trim()));
        if (Float.isNaN(value) || value < min || value > max)
        {
            log.warn("'{}' ({}) has to be between {} and {}, using {} instead", name, value, min, max, defaultValue);
            return defaultValue;
        }
        return value;
    }

    private static <T> T parse(Properties properties, String name, T defaultValue, Function<String, T> parser)
    {
        Optional<String> value = getProperty(properties, name);
        if (value.isEmpty())
        {
            return defaultValue;
        }
        try
        {
            return parser.apply(value.get());
        }
        catch (RuntimeException e)
        {
            log.warn("Invalid value '{}' of '{}', using {} instead", value.get(), name, defaultValue);
            return defaultValue;
        }
    }

    private static Optional<String> getProperty(Properties properties, String name)
    {
        String value = properties.getProperty(name);
        return StringUtils.isBlank(value) ? Optional.empty() : Optional.of(value.trim());
    }
}
