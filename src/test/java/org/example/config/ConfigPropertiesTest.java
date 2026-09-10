package org.example.config;

import org.example.processor.ParameterProcessorType;
import org.example.processor.SpsType;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class ConfigPropertiesTest
{
    @Test
    void toLoggableStringMasksDeviceConnectionKeyAndHandyApplicationId()
    {
        var config = ConfigProperties.builder()
                .deviceConnectionKey("Abc123Key")
                .handyApplicationId("Xyz987App")
                .avatarParameter("/avatar/parameters/OGB/Pen/*/PenOthers")
                .processingAlgorithm(ParameterProcessorType.HSP)
                .connectionMode(ConnectionMode.API)
                .testMode(false)
                .savePointsToFile(false)
                .clamp(true)
                .pauseOnStarving(false)
                .handyApiVersion(3)
                .listenOnPort(9001)
                .waitForApiResponse(true)
                .pointsOffset(5)
                .sendMessageEveryMs(200)
                .minimalValueChange(2)
                .penetratorLength(0.5f)
                .sliderMin(0.0f)
                .sliderMax(1.0f)
                .spsType(SpsType.PENETRATOR)
                .build();

        var loggable = config.toLoggableString();

        assertThat(loggable)
                .doesNotContain("Abc123Key")
                .doesNotContain("Xyz987App")
                .contains("deviceConnectionKey='A***'")
                .contains("handyApplicationId='X***'")
                .contains("avatarParameter='/avatar/parameters/OGB/Pen/*/PenOthers'")
                .contains("connectionMode=API")
                .contains("spsType=PENETRATOR");
    }

    @Test
    void toLoggableStringKeepsNullSensitiveValuesUntouched()
    {
        var config = ConfigProperties.builder()
                .connectionMode(ConnectionMode.BLUETOOTH)
                .build();

        assertThat(config.toLoggableString())
                .contains("deviceConnectionKey='null'")
                .contains("handyApplicationId='null'");
    }
}