package org.example.config;

import org.example.processor.ParameterProcessorType;
import org.example.processor.SpsType;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class ConfigLoaderTest
{
    @Test
    void derivesPenetratorTipParameterForOrificeFromAvatarParameter() throws IOException
    {
        var config = loadConfig("""
                connectionMode=BLUETOOTH
                spsType=ORIFICE
                avatarParameter=/avatar/parameters/OGB/Orf/1/PenOthersNewRoot
                sendMessageEveryMs=0
                """);

        assertThat(config.spsType()).isEqualTo(SpsType.ORIFICE);
        assertThat(config.penetratorTipParameter()).isEqualTo("/avatar/parameters/OGB/Orf/1/PenOthersNewTip");
    }

    @Test
    void usesExplicitPenetratorTipParameterForOrifice() throws IOException
    {
        var config = loadConfig("""
                connectionMode=BLUETOOTH
                spsType=ORIFICE
                avatarParameter=/avatar/parameters/OGB/Orf/1/PenOthersNewRoot
                penetratorTipParameter=/avatar/parameters/CustomTip
                sendMessageEveryMs=0
                """);

        assertThat(config.penetratorTipParameter()).isEqualTo("/avatar/parameters/CustomTip");
    }

    @Test
    void doesNotResolvePenetratorTipParameterForPenetrator() throws IOException
    {
        var config = loadConfig("""
                connectionMode=BLUETOOTH
                spsType=PENETRATOR
                avatarParameter=/avatar/parameters/OGB/Pen/1/PenOthers
                sendMessageEveryMs=0
                """);

        assertThat(config.penetratorTipParameter()).isNull();
    }

    @Test
    void ignoresLegacyManuallyConfiguredPenetratorLength() throws IOException
    {
        var config = loadConfig("""
                connectionMode=BLUETOOTH
                spsType=ORIFICE
                avatarParameter=/avatar/parameters/OGB/Orf/1/PenOthersNewRoot
                penetratorLength=0.07
                sendMessageEveryMs=0
                """);

        // Length is auto-detected now, so the app must still start without any manual value
        assertThat(config.penetratorTipParameter()).isEqualTo("/avatar/parameters/OGB/Orf/1/PenOthersNewTip");
    }

    @Test
    void loadsHdspAlgorithmForBluetooth() throws IOException
    {
        var config = loadConfig("""
                connectionMode=BLUETOOTH
                processingAlgorithm=HDSP
                hdspTiming=true
                spsType=PENETRATOR
                sendMessageEveryMs=0
                """);

        assertThat(config.processingAlgorithm()).isEqualTo(ParameterProcessorType.HDSP);
        assertThat(config.connectionMode()).isEqualTo(ConnectionMode.BLUETOOTH);
        assertThat(config.hdspTiming()).isTrue();
    }

    @Test
    void checksForUpdatesByDefault() throws IOException
    {
        // an app.properties written by an older version has no checkForUpdates property, the check must still run
        var config = loadConfig("""
                connectionMode=BLUETOOTH
                spsType=PENETRATOR
                sendMessageEveryMs=0
                """);

        assertThat(config.checkForUpdates()).isTrue();
    }

    @Test
    void updateCheckCanBeDisabledByProperty() throws IOException
    {
        var config = loadConfig("""
                connectionMode=BLUETOOTH
                spsType=PENETRATOR
                sendMessageEveryMs=0
                checkForUpdates=false
                """);

        assertThat(config.checkForUpdates()).isFalse();
    }

    @Test
    void hspIsTheDefaultAlgorithm()
    {
        assertThat(ConfigLoader.validateConnectionMode(ParameterProcessorType.HSP, ConnectionMode.API)).isNull();
        assertThat(ConfigLoader.validateConnectionMode(ParameterProcessorType.HSP, ConnectionMode.BLUETOOTH)).isNull();
    }

    @Test
    void hdspIsOnlyValidWithBluetooth()
    {
        for (var algorithm : List.of(ParameterProcessorType.HDSP, ParameterProcessorType.HDSP_SMOOTHED))
        {
            assertThat(ConfigLoader.validateConnectionMode(algorithm, ConnectionMode.BLUETOOTH)).isNull();
            assertThat(ConfigLoader.validateConnectionMode(algorithm, ConnectionMode.API))
                    .contains(algorithm.name())
                    .contains("Bluetooth");
        }
    }

    @Test
    void usesOscQueryByDefault() throws IOException
    {
        // an app.properties written by an older version has no useOscQuery property, OSCQuery must still be used
        var config = loadConfig("""
                connectionMode=BLUETOOTH
                spsType=PENETRATOR
                sendMessageEveryMs=0
                """);

        assertThat(config.useOscQuery()).isTrue();
    }

    @Test
    void oscQueryCanBeDisabledToListenOnTheConfiguredPortOnly() throws IOException
    {
        var config = loadConfig("""
                connectionMode=BLUETOOTH
                spsType=PENETRATOR
                sendMessageEveryMs=0
                listenOnPort=9123
                useOscQuery=false
                """);

        assertThat(config.useOscQuery()).isFalse();
        assertThat(config.listenOnPort()).isEqualTo(9123);
    }

    private ConfigProperties loadConfig(String configContent) throws IOException
    {
        Path configPath = Files.createTempFile("app", ".properties");
        configPath.toFile().deleteOnExit();
        Files.writeString(configPath, configContent);
        return new ConfigLoader(configPath).readOrInitConfig();
    }
}
