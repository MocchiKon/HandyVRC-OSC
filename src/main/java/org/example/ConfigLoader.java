package org.example;

import ch.qos.logback.classic.Level;
import lombok.SneakyThrows;
import lombok.extern.slf4j.Slf4j;
import org.apache.commons.lang3.EnumUtils;
import org.apache.commons.lang3.StringUtils;
import org.example.processor.ParameterProcessorType;
import org.example.processor.SpsType;

import javax.swing.*;
import java.io.FileInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.attribute.FileTime;
import java.util.Optional;
import java.util.Properties;
import java.util.function.Consumer;
import java.util.function.Function;

@Slf4j
public class ConfigLoader
{
    private static final int RELOAD_EVERY_MS = 10_000;
    private final Path appConfigPath;
    private FileTime lastModifiedTime;

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
                    log.error("Missing config file!");
                    System.exit(1);
                }
                Files.write(appConfigPath, defaultFile.readAllBytes());
            }
            catch (Exception e)
            {
                log.error("Failed initializing config!", e);
                System.exit(1);
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
        var processingAlgorithm = EnumUtils.getEnum(ParameterProcessorType.class, processingAlgorithmProperty);
        if (processingAlgorithm == null)
        {
            log.error("No '{}' algorithm available! Check your config file, closing app...", processingAlgorithmProperty);
            System.exit(1);
        }
        var spsType = EnumUtils.getEnum(SpsType.class, getPropertyOrCloseAppWhenBlank(properties, "spsType").toUpperCase());
        if (spsType == null)
        {
            log.error("No '{}' spsType available! Check your config file, closing app...", spsType);
            System.exit(1);
        }
        // TODO Log loaded config (without keys)
        return ConfigProperties.builder()
                .listenOnPort(Integer.parseInt(getPropertyOrDefault(properties, "listenOnPort", "9001")))
                .handyApplicationId(getPropertyOrCloseAppWhenBlank(properties, "handyApplicationId"))
                .processingAlgorithm(processingAlgorithm)
                .avatarParameter(getProperty(properties, "avatarParameter").orElseGet(() -> pickDefaultAvatarParameter(spsType)))
                .deviceConnectionKey(getPropertyOrCloseAppWhenBlank(properties, "deviceConnectionKey"))
                .waitForApiResponse(Boolean.parseBoolean(getPropertyOrDefault(properties, "waitForApiResponse", "false")))
                .pointsOffset(Integer.parseInt(getPropertyOrCloseAppWhenBlank(properties, "pointsOffset")))
                .sendMessageEveryMs(Integer.parseInt(getPropertyOrCloseAppWhenBlank(properties, "sendMessageEveryMs")))
                .minimalValueChange(Integer.parseInt(getPropertyOrDefault(properties, "minimalValueChange", "2")))
                .penetratorLength(spsType == SpsType.ORIFICE ? Float.parseFloat(getPropertyOrCloseAppWhenBlank(properties, "penetratorLength")) : 0.f)
                .sliderMin(getProperty(properties, "sliderMin").map(Float::parseFloat).orElse(null))
                .sliderMax(getProperty(properties, "sliderMax").map(Float::parseFloat).orElse(null))
                .spsType(spsType)
                .build();
    }

    private String pickDefaultAvatarParameter(SpsType spsType)
    {
        if (spsType == null)
        {
            log.error("Missing spsType, cannot pick default avatar parameter", spsType);
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
            log.error("Missing '{}' property in config file! Closing app...", propertyName);
            JOptionPane.showMessageDialog(null, "Missing '%s' property in config file!".formatted(propertyName), "Error", JOptionPane.ERROR_MESSAGE);
            System.exit(1);
        }
        return value;
    }

    @SneakyThrows
    public void runReloading(Consumer<ConfigProperties> propertiesConsumer)
    {
        lastModifiedTime = Files.getLastModifiedTime(appConfigPath, LinkOption.NOFOLLOW_LINKS);
        new Thread(() -> tryReloadingConfig(propertiesConsumer)).start();
    }

    private void tryReloadingConfig(Consumer<ConfigProperties> propertiesConsumer)
    {
        try
        {
            while (true)
            {
                tryReloadingConfig().ifPresent(propertiesConsumer);
                delayUntilNextReload();
            }
        }
        catch (Exception e)
        {
            log.error("Caught exception during config reload!", e);
        }
    }

    @SneakyThrows
    private Optional<ConfigProperties> tryReloadingConfig()
    {
        if (Files.notExists(appConfigPath))
        {
            return Optional.empty();
        }
        FileTime modifiedTime = Files.getLastModifiedTime(appConfigPath, LinkOption.NOFOLLOW_LINKS);
        if (modifiedTime.equals(lastModifiedTime))
        {
            return Optional.empty();
        }
        log.info("Reloading configuration...");
        lastModifiedTime = modifiedTime;
        return Optional.of(readConfigPropertiesAndSetLoggingLevel());
    }

    private void delayUntilNextReload()
    {
        try
        {
            Thread.sleep(RELOAD_EVERY_MS);
        }
        catch (InterruptedException e)
        {
            log.error("Error while sleeping: {}", e.getMessage());
        }
    }
}
