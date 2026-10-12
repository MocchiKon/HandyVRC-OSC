package org.example.config;

import org.example.processor.ParameterProcessorType;
import org.example.processor.SpsType;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class ConfigPropertiesTest
{
    @Test
    void defaultsUseSmoothedModeAndTheDefaultAvatarParameter()
    {
        var defaults = ConfigProperties.defaults();

        assertThat(defaults.processingAlgorithm()).isEqualTo(ParameterProcessorType.HDSP_SMOOTHED);
        assertThat(defaults.fullyPenetratedAtValue()).isEqualTo(85f);
        assertThat(defaults.customAvatarParameter()).isFalse();
        assertThat(defaults.avatarParameter()).isEqualTo(ConfigProperties.defaultAvatarParameter(defaults.spsType(), defaults.interactWith()));
        assertThat(defaults.validate()).isNull();
    }

    @Test
    void defaultAvatarParameterFollowsInteractWith()
    {
        assertThat(ConfigProperties.defaultAvatarParameter(SpsType.PENETRATOR, InteractWith.OTHERS))
                .isEqualTo(ConfigProperties.DEFAULT_PENETRATOR_PARAMETER);
        assertThat(ConfigProperties.defaultAvatarParameter(SpsType.ORIFICE, InteractWith.OTHERS))
                .isEqualTo(ConfigProperties.DEFAULT_ORIFICE_PARAMETER);
        assertThat(ConfigProperties.defaultAvatarParameter(SpsType.PENETRATOR, InteractWith.SELF))
                .isEqualTo("/avatar/parameters/OGB/Pen/*/PenSelf");
        assertThat(ConfigProperties.defaultAvatarParameter(SpsType.ORIFICE, InteractWith.SELF))
                .isEqualTo("/avatar/parameters/OGB/Orf/*/PenSelfNewRoot");
        assertThat(ConfigProperties.defaultAvatarParameter(SpsType.PENETRATOR, InteractWith.BOTH))
                .isEqualTo("/avatar/parameters/OGB/Pen/*/{PenSelf,PenOthers}");
    }

    @Test
    void orificeParameterForBothHasAMatchingTipParameter()
    {
        String root = ConfigProperties.defaultAvatarParameter(SpsType.ORIFICE, InteractWith.BOTH);

        assertThat(root).isEqualTo("/avatar/parameters/OGB/Orf/*/{PenSelf,PenOthers}NewRoot");
        assertThat(ConfigProperties.penetratorTipParameter(SpsType.ORIFICE, root))
                .isEqualTo("/avatar/parameters/OGB/Orf/*/{PenSelf,PenOthers}NewTip");
        assertThat(ConfigProperties.validateAvatarParameter(SpsType.ORIFICE, root)).isNull();
    }

    @Test
    void tipParameterIsOnlyDerivedForOrifice()
    {
        String root = "/avatar/parameters/OGB/Orf/*/PenOthersNewRoot";

        assertThat(ConfigProperties.penetratorTipParameter(SpsType.ORIFICE, root))
                .isEqualTo("/avatar/parameters/OGB/Orf/*/PenOthersNewTip");
        assertThat(ConfigProperties.penetratorTipParameter(SpsType.PENETRATOR, root)).isNull();
    }

    @Test
    void emptyAvatarParameterIsInvalid()
    {
        assertThat(ConfigProperties.validateAvatarParameter(SpsType.PENETRATOR, " ")).isNotNull();
    }

    @Test
    void toLoggableStringContainsTheSettings()
    {
        var config = ConfigProperties.defaults().toBuilder()
                .avatarParameter("/avatar/parameters/OGB/Pen/*/PenOthers")
                .build();

        assertThat(config.toLoggableString())
                .contains("avatarParameter=/avatar/parameters/OGB/Pen/*/PenOthers")
                .contains("spsType=PENETRATOR");
    }
}
