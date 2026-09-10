package org.example.config;

import lombok.Builder;
import org.apache.commons.lang3.StringUtils;
import org.example.processor.ParameterProcessorType;
import org.example.processor.SpsType;

@Builder
public record ConfigProperties(
        String deviceConnectionKey,
        String avatarParameter,
        String handyApplicationId,
        ParameterProcessorType processingAlgorithm,
        ConnectionMode connectionMode,
        boolean testMode,
        boolean savePointsToFile,
        boolean clamp,
        boolean pauseOnStarving,
        int handyApiVersion,
        int listenOnPort,
        boolean waitForApiResponse,
        Integer pointsOffset,
        int sendMessageEveryMs,
        int minimalValueChange,
        float penetratorLength,
        Float sliderMin,
        Float sliderMax,
        SpsType spsType
)
{
    /**
     * Returns a log-friendly representation of this config where
     * {@code deviceConnectionKey} and {@code handyApplicationId} values are masked:
     * only the first character is shown, the rest is replaced by asterisks.
     */
    public String toLoggableString()
    {
        return "ConfigProperties[" +
                "deviceConnectionKey='" + maskValue(deviceConnectionKey) + '\'' +
                ", avatarParameter='" + avatarParameter + '\'' +
                ", handyApplicationId='" + maskValue(handyApplicationId) + '\'' +
                ", processingAlgorithm=" + processingAlgorithm +
                ", connectionMode=" + connectionMode +
                ", testMode=" + testMode +
                ", savePointsToFile=" + savePointsToFile +
                ", clamp=" + clamp +
                ", pauseOnStarving=" + pauseOnStarving +
                ", handyApiVersion=" + handyApiVersion +
                ", listenOnPort=" + listenOnPort +
                ", waitForApiResponse=" + waitForApiResponse +
                ", pointsOffset=" + pointsOffset +
                ", sendMessageEveryMs=" + sendMessageEveryMs +
                ", minimalValueChange=" + minimalValueChange +
                ", penetratorLength=" + penetratorLength +
                ", sliderMin=" + sliderMin +
                ", sliderMax=" + sliderMax +
                ", spsType=" + spsType +
                ']';
    }

    private static String maskValue(String value)
    {
        if (StringUtils.isBlank(value))
        {
            return value;
        }
        return value.charAt(0) + "*".repeat(3);
    }
}
