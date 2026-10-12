package org.example.config;

import org.example.processor.ParameterProcessorType;
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
                spsType=ORIFICE
                avatarParameter=/avatar/parameters/OGB/Orf/1/PenOthersNewRoot
                """);

        assertThat(config.spsType()).isEqualTo(SpsType.ORIFICE);
        assertThat(config.customAvatarParameter()).isTrue();
        assertThat(config.penetratorTipParameter()).isEqualTo("/avatar/parameters/OGB/Orf/1/PenOthersNewTip");
        assertThat(config.validate()).isNull();
    }

    @Test
    void ignoresLegacyPenetratorTipParameter() throws IOException
    {
        var config = loadConfig("""
                spsType=ORIFICE
                avatarParameter=/avatar/parameters/OGB/Orf/1/PenOthersNewRoot
                penetratorTipParameter=/avatar/parameters/CustomTip
                """);

        assertThat(config.penetratorTipParameter()).isEqualTo("/avatar/parameters/OGB/Orf/1/PenOthersNewTip");
    }

    @Test
    void orificeParameterWithoutNewRootSuffixIsReportedAsInvalid() throws IOException
    {
        var config = loadConfig("""
                spsType=ORIFICE
                avatarParameter=/avatar/parameters/Something
                """);

        assertThat(config.penetratorTipParameter()).isNull();
        assertThat(config.validate()).contains("NewRoot");
    }

    @Test
    void doesNotResolvePenetratorTipParameterForPenetrator() throws IOException
    {
        var config = loadConfig("""
                spsType=PENETRATOR
                avatarParameter=/avatar/parameters/OGB/Pen/1/PenOthers
                """);

        assertThat(config.penetratorTipParameter()).isNull();
        assertThat(config.validate()).isNull();
    }

    @Test
    void usesDefaultAvatarParameterOfTheSpsTypeWhenBlank() throws IOException
    {
        var orifice = loadConfig("""
                spsType=ORIFICE
                avatarParameter=
                """);
        var penetrator = loadConfig("""
                spsType=PENETRATOR
                """);

        assertThat(orifice.customAvatarParameter()).isFalse();
        assertThat(orifice.avatarParameter()).isEqualTo(ConfigProperties.DEFAULT_ORIFICE_PARAMETER);
        assertThat(orifice.penetratorTipParameter()).isEqualTo("/avatar/parameters/OGB/Orf/*/PenOthersNewTip");
        assertThat(penetrator.customAvatarParameter()).isFalse();
        assertThat(penetrator.avatarParameter()).isEqualTo(ConfigProperties.DEFAULT_PENETRATOR_PARAMETER);
    }

    @Test
    void smoothedIsTheDefaultAlgorithm() throws IOException
    {
        var config = loadConfig("""
                spsType=PENETRATOR
                """);

        assertThat(config.processingAlgorithm()).isEqualTo(ParameterProcessorType.HDSP_SMOOTHED);
    }

    @Test
    void loadsHdspAlgorithm() throws IOException
    {
        var config = loadConfig("""
                processingAlgorithm=HDSP
                """);

        assertThat(config.processingAlgorithm()).isEqualTo(ParameterProcessorType.HDSP);
    }

    @Test
    void acceptsTheAlgorithmNamesShownInTheGui() throws IOException
    {
        assertThat(loadConfig("processingAlgorithm=direct").processingAlgorithm()).isEqualTo(ParameterProcessorType.HDSP);
        assertThat(loadConfig("processingAlgorithm=Smoothed").processingAlgorithm()).isEqualTo(ParameterProcessorType.HDSP_SMOOTHED);
    }

    @Test
    void hspFromAnOlderVersionFallsBackToTheDefaultAlgorithm() throws IOException
    {
        var config = loadConfig("""
                connectionMode=API
                processingAlgorithm=HSP
                spsType=PENETRATOR
                """);

        assertThat(config.processingAlgorithm()).isEqualTo(ParameterProcessorType.HDSP_SMOOTHED);
    }

    @Test
    void invalidValuesFallBackToTheirDefaults() throws IOException
    {
        var config = loadConfig("""
                spsType=SOMETHING
                listenOnPort=abc
                useOscQuery=maybe
                sendMessageEveryMs=-5
                hdspSpeedChangeThresholdPercent=0
                """);
        var defaults = ConfigProperties.defaults();

        assertThat(config.spsType()).isEqualTo(defaults.spsType());
        assertThat(config.listenOnPort()).isEqualTo(defaults.listenOnPort());
        assertThat(config.useOscQuery()).isEqualTo(defaults.useOscQuery());
        assertThat(config.sendMessageEveryMs()).isEqualTo(defaults.sendMessageEveryMs());
        assertThat(config.hdspSpeedChangeThresholdPercent()).isEqualTo(defaults.hdspSpeedChangeThresholdPercent());
    }

    @Test
    void checksForUpdatesByDefault() throws IOException
    {
        // an app.properties written by an older version has no checkForUpdates property, the check must still run
        var config = loadConfig("""
                spsType=PENETRATOR
                """);

        assertThat(config.checkForUpdates()).isTrue();
    }

    @Test
    void updateCheckCanBeDisabledByProperty() throws IOException
    {
        var config = loadConfig("""
                spsType=PENETRATOR
                checkForUpdates=false
                """);

        assertThat(config.checkForUpdates()).isFalse();
    }

    @Test
    void usesOscQueryByDefault() throws IOException
    {
        // an app.properties written by an older version has no useOscQuery property, OSCQuery must still be used
        var config = loadConfig("""
                spsType=PENETRATOR
                """);

        assertThat(config.useOscQuery()).isTrue();
    }

    @Test
    void oscQueryCanBeDisabledToListenOnTheConfiguredPortOnly() throws IOException
    {
        var config = loadConfig("""
                spsType=PENETRATOR
                listenOnPort=9123
                useOscQuery=false
                """);

        assertThat(config.useOscQuery()).isFalse();
        assertThat(config.listenOnPort()).isEqualTo(9123);
    }

    @Test
    void loadsFullyPenetratedAtValue() throws IOException
    {
        var config = loadConfig("""
                fullyPenetratedAtValue=50
                """);

        assertThat(config.fullyPenetratedAtValue()).isEqualTo(50f);
    }

    @Test
    void fullyPenetratedAtValueDefaultsTo85Percent() throws IOException
    {
        assertThat(loadConfig("spsType=PENETRATOR").fullyPenetratedAtValue()).isEqualTo(85f);
        assertThat(loadConfig("fullyPenetratedAtValue=0").fullyPenetratedAtValue()).isEqualTo(85f);
        assertThat(loadConfig("fullyPenetratedAtValue=101").fullyPenetratedAtValue()).isEqualTo(85f);
    }

    @Test
    void sliderRangeHasToBeAscending() throws IOException
    {
        var valid = loadConfig("""
                sliderMin=0.2
                sliderMax=0.8
                """);
        var invalid = loadConfig("""
                sliderMin=0.8
                sliderMax=0.2
                """);

        assertThat(valid.sliderMin()).isEqualTo(0.2f);
        assertThat(valid.sliderMax()).isEqualTo(0.8f);
        assertThat(invalid.sliderMin()).isEqualTo(0f);
        assertThat(invalid.sliderMax()).isEqualTo(1f);
    }

    @Test
    void loadsReversalMinDistance() throws IOException
    {
        assertThat(loadConfig("hdspReversalMinDistancePercent=3.5").hdspReversalMinDistancePercent()).isEqualTo(3.5f);
        assertThat(loadConfig("spsType=PENETRATOR").hdspReversalMinDistancePercent()).isEqualTo(2f);
    }

    @Test
    void createsTheFileWithTheDefaultsWhenItIsMissing() throws IOException
    {
        Path directory = Files.createTempDirectory("config");
        Path configPath = directory.resolve("app.properties");

        var config = new ConfigLoader(configPath).readOrInitConfig();

        assertThat(config).isEqualTo(ConfigProperties.defaults());
        assertThat(configPath).exists();
        assertThat(new ConfigLoader(configPath).readOrInitConfig()).isEqualTo(ConfigProperties.defaults());
    }

    @Test
    void savedSettingsAreReadBackUnchanged() throws IOException
    {
        var config = ConfigProperties.defaults().toBuilder()
                .spsType(SpsType.ORIFICE)
                .processingAlgorithm(ParameterProcessorType.HDSP)
                .avatarParameter("/avatar/parameters/OGB/Orf/My Socket/PenSelfNewRoot")
                .customAvatarParameter(true)
                .sliderMin(0.1f)
                .sliderMax(0.95f)
                .fullyPenetratedAtValue(70f)
                .listenOnPort(9123)
                .useOscQuery(false)
                .sendMessageEveryMs(40)
                .checkForUpdates(false)
                .minimalValueChange(5)
                .pauseOnStarving(true)
                .hdspSpeedChangeThresholdPercent(30)
                .hdspSpeedStopThresholdPercent(10)
                .hdspDivergenceThresholdPercent(25)
                .hdspSpeedMeasureWindowMs(120)
                .hdspMinSliderSpeed(0)
                .hdspReversalMinDistancePercent(2.5f)
                .hdspLogDevicePosition(true)
                .logLevel("DEBUG")
                .build();
        Path configPath = Files.createTempFile("app", ".properties");
        configPath.toFile().deleteOnExit();
        var loader = new ConfigLoader(configPath);

        loader.save(config);
        var loaded = loader.readOrInitConfig();
        ConfigLoader.setLoggingLevel(ch.qos.logback.classic.Level.INFO);

        assertThat(loaded).isEqualTo(config);
    }

    @Test
    void defaultAvatarParameterIsNotWrittenToTheFile() throws IOException
    {
        assertThat(ConfigLoader.format(ConfigProperties.defaults())).contains("\navatarParameter=\n");
    }

    private ConfigProperties loadConfig(String configContent) throws IOException
    {
        Path configPath = Files.createTempFile("app", ".properties");
        configPath.toFile().deleteOnExit();
        Files.writeString(configPath, configContent);
        return new ConfigLoader(configPath).readOrInitConfig();
    }
}
