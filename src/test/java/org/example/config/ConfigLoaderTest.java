package org.example.config;

import org.example.processor.SpsType;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

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

    private ConfigProperties loadConfig(String configContent) throws IOException
    {
        Path configPath = Files.createTempFile("app", ".properties");
        configPath.toFile().deleteOnExit();
        Files.writeString(configPath, configContent);
        return new ConfigLoader(configPath).readOrInitConfig();
    }
}
