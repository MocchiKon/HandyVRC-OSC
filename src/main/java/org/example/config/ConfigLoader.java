package org.example.config;

import ch.qos.logback.classic.Level;
import lombok.SneakyThrows;
import lombok.extern.slf4j.Slf4j;
import org.apache.commons.lang3.EnumUtils;
import org.apache.commons.lang3.StringUtils;
import org.example.Main;
import org.example.processor.ParameterProcessorType;
import org.example.processor.SpsType;

import java.io.FileInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Optional;
import java.util.Properties;

import static org.example.Main.delayedClosingWithLog;

@Slf4j
public class ConfigLoader
{
    private static final String NEW_ROOT_SUFFIX = "NewRoot";
    private static final String NEW_TIP_SUFFIX = "NewTip";

    private final Path appConfigPath;

    @SneakyThrows
    public ConfigLoader(Path appConfigPath)
    {
        this.appConfigPath = appConfigPath;
    }

    public ConfigProperties readOrInitConfig() throws IOException
    {
        if (Files.notExists(appConfigPath))
        {
            try (InputStream defaultFile = Main.class.getResourceAsStream("/app.properties"))
            {
                if (defaultFile == null)
                {
                    delayedClosingWithLog("Missing config file!");
                }
                Files.write(appConfigPath, defaultFile.readAllBytes());
            }
            catch (Exception e)
            {
                delayedClosingWithLog("Failed initializing config!", e);
            }
        }
        return readConfigPropertiesAndSetLoggingLevel();
    }

    public static void setLoggingLevel(Level level)
    {
        ch.qos.logback.classic.Logger root = (ch.qos.logback.classic.Logger) org.slf4j.LoggerFactory.getLogger(ch.qos.logback.classic.Logger.ROOT_LOGGER_NAME);
        root.setLevel(level);
    }

    private ConfigProperties readConfigPropertiesAndSetLoggingLevel() throws IOException
    {
        Properties properties = new Properties();
        try (var file = new FileInputStream(appConfigPath.toString()))
        {
            properties.load(file);
        }
        Level logLevel = Level.toLevel(getPropertyOrDefault(properties, "logLevel", "INFO"));
        setLoggingLevel(logLevel);

        String processingAlgorithmProperty = getPropertyOrDefault(properties, "processingAlgorithm", "HSP");
        var processingAlgorithm = EnumUtils.getEnum(ParameterProcessorType.class, processingAlgorithmProperty.toUpperCase());
        if (processingAlgorithm == null)
        {
            delayedClosingWithLog("No '%s' algorithm available! Check your config file, closing app...".formatted(processingAlgorithmProperty));
        }
        var spsType = EnumUtils.getEnum(SpsType.class, getPropertyOrCloseAppWhenBlank(properties, "spsType").toUpperCase());
        if (spsType == null)
        {
            delayedClosingWithLog("No '%s' spsType available! Check your config file, closing app...".formatted(spsType));
        }
        var connectionModeStr = getPropertyOrDefault(properties, "connectionMode", "API").toUpperCase();
        var connectionMode = EnumUtils.getEnum(ConnectionMode.class, connectionModeStr);
        if (connectionMode == null)
        {
            log.warn("Unknown connectionMode '{}', defaulting to API", connectionModeStr);
            connectionMode = ConnectionMode.API;
        }
        String algorithmConnectionError = validateConnectionMode(processingAlgorithm, connectionMode);
        if (algorithmConnectionError != null)
        {
            delayedClosingWithLog(algorithmConnectionError);
        }
        boolean testMode = Boolean.parseBoolean(getPropertyOrDefault(properties, "testMode", "false"));
        boolean checkForUpdates = Boolean.parseBoolean(getPropertyOrDefault(properties, "checkForUpdates", "true"));
        boolean savePointsToFile = Boolean.parseBoolean(getPropertyOrDefault(properties, "savePointsToFile", "false"));
        boolean clamp = Boolean.parseBoolean(getPropertyOrDefault(properties, "clamp", "false"));
        boolean pauseOnStarving = Boolean.parseBoolean(getPropertyOrDefault(properties, "pauseOnStarving", "false"));
        boolean hdspTiming = Boolean.parseBoolean(getPropertyOrDefault(properties, "hdspTiming", "false"));

        boolean isApiMode = connectionMode == ConnectionMode.API;

        String avatarParameter = getProperty(properties, "avatarParameter").orElseGet(() -> pickDefaultAvatarParameter(spsType));
        String penetratorTipParameter = null;
        if (spsType == SpsType.ORIFICE)
        {
            penetratorTipParameter = findPenetratorTipParameter(properties, avatarParameter);
            if (penetratorTipParameter == null)
            {
                delayedClosingWithLog(("The tip proximity parameter needed to auto-detect the penetrator length could not be "
                        + "derived from avatarParameter '%s' (its 'NewRoot' suffix is replaced with 'NewTip'). Either use a "
                        + "'NewRoot' avatar parameter (for example '/avatar/parameters/OGB/Orf/*/PenOthersNewRoot') or set "
                        + "'penetratorTipParameter' in the config. Closing app...")
                        .formatted(avatarParameter));
            }
            log.info("Penetrator length will be auto-detected from root proximity '{}' and tip proximity '{}'", avatarParameter, penetratorTipParameter);
        }

        var config = ConfigProperties.builder()
                .connectionMode(connectionMode)
                .testMode(testMode)
                .checkForUpdates(checkForUpdates)
                .savePointsToFile(savePointsToFile)
                .clamp(clamp)
                .pauseOnStarving(pauseOnStarving)
                .hdspTiming(hdspTiming)
                .listenOnPort(Integer.parseInt(getPropertyOrDefault(properties, "listenOnPort", "9001")))
                .handyApplicationId(isApiMode ? getPropertyOrCloseAppWhenBlank(properties, "handyApplicationId") : null)
                .processingAlgorithm(processingAlgorithm)
                .avatarParameter(avatarParameter)
                .deviceConnectionKey(isApiMode ? getPropertyOrCloseAppWhenBlank(properties, "deviceConnectionKey") : null)
                .waitForApiResponse(Boolean.parseBoolean(getPropertyOrDefault(properties, "waitForApiResponse", "false")))
                .pointsOffset(getProperty(properties, "pointsOffset").map(Integer::parseInt).orElse(null))
                .sendMessageEveryMs(Integer.parseInt(getPropertyOrCloseAppWhenBlank(properties, "sendMessageEveryMs")))
                .minimalValueChange(Integer.parseInt(getPropertyOrDefault(properties, "minimalValueChange", "2")))
                .penetratorTipParameter(penetratorTipParameter)
                .sliderMin(getProperty(properties, "sliderMin").map(Float::parseFloat).orElse(null))
                .sliderMax(getProperty(properties, "sliderMax").map(Float::parseFloat).orElse(null))
                .spsType(spsType)
                .build();
        log.info("Loaded config: {}", config.toLoggableString());
        return config;
    }

    /**
     * HDSP is a direct streaming protocol without device-side buffering, so it only works over a low latency
     * Bluetooth connection. HSP keeps working over both API and Bluetooth.
     *
     * @return an error message when the selected algorithm cannot be used with the selected connection mode,
     * or null when the combination is valid
     */
    static String validateConnectionMode(ParameterProcessorType processingAlgorithm, ConnectionMode connectionMode)
    {
        if (processingAlgorithm == ParameterProcessorType.HDSP && connectionMode != ConnectionMode.BLUETOOTH)
        {
            return ("Processing algorithm HDSP is only supported with Bluetooth! Set connectionMode=BLUETOOTH "
                    + "or use processingAlgorithm=HSP (default). Closing app...");
        }
        return null;
    }

    /**
     * Resolves the OSC parameter that carries the tip proximity used to auto-detect the penetrator length.
     * Uses 'penetratorTipParameter' when provided, otherwise derives it from the configured root parameter
     * by replacing its 'NewRoot' suffix with 'NewTip' (for example
     * '/avatar/parameters/OGB/Orf/&lt;socket&gt;/PenOthersNewRoot' becomes
     * '/avatar/parameters/OGB/Orf/&lt;socket&gt;/PenOthersNewTip').
     *
     * @return resolved tip parameter or null when it cannot be determined
     */
    private String findPenetratorTipParameter(Properties properties, String avatarParameter)
    {
        Optional<String> configured = getProperty(properties, "penetratorTipParameter");
        if (configured.isPresent())
        {
            return configured.get();
        }
        if (avatarParameter != null && avatarParameter.endsWith(NEW_ROOT_SUFFIX))
        {
            return avatarParameter.substring(0, avatarParameter.length() - NEW_ROOT_SUFFIX.length()) + NEW_TIP_SUFFIX;
        }
        return null;
    }

    private String pickDefaultAvatarParameter(SpsType spsType)
    {
        if (spsType == null)
        {
            log.error("Missing spsType, cannot pick default avatar parameter");
        }
        return switch (spsType)
        {
            case ORIFICE -> "/avatar/parameters/OGB/Orf/*/PenOthersNewRoot";
            case PENETRATOR -> "/avatar/parameters/OGB/Pen/*/PenOthers";
        };
    }

    private Optional<String> getProperty(Properties properties, String propertyName)
    {
        return Optional.ofNullable(getPropertyOrDefault(properties, propertyName, null));
    }

    private String getPropertyOrDefault(Properties properties, String propertyName, String defaultValue)
    {
        String value = properties.getProperty(propertyName, defaultValue);
        if (StringUtils.isBlank(value))
        {
            return defaultValue;
        }
        return value;
    }

    private String getPropertyOrCloseAppWhenBlank(Properties properties, String propertyName)
    {
        String value = properties.getProperty(propertyName);
        if (StringUtils.isBlank(value))
        {
            delayedClosingWithLog("Missing '%s' property in config file! Closing app...".formatted(propertyName));
        }
        return value;
    }
}
