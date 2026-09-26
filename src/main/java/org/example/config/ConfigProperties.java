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
        boolean checkForUpdates,
        boolean savePointsToFile,
        boolean clamp,
        boolean pauseOnStarving,
        boolean hdspTiming,
        Integer hdspSpeedChangeThresholdPercent,
        Integer hdspSpeedStopThresholdPercent,
        Integer hdspDivergenceThresholdPercent,
        Integer hdspSpeedMeasureWindowMs,
        int handyApiVersion,
        int listenOnPort,
        boolean useOscQuery,
        boolean waitForApiResponse,
        Integer pointsOffset,
        int sendMessageEveryMs,
        int minimalValueChange,
        String penetratorTipParameter,
        Float sliderMin,
        Float sliderMax,
        Float fullyPenetratedAtValue,
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
                ", checkForUpdates=" + checkForUpdates +
                ", savePointsToFile=" + savePointsToFile +
                ", clamp=" + clamp +
                ", pauseOnStarving=" + pauseOnStarving +
                ", hdspTiming=" + hdspTiming +
                ", hdspSpeedChangeThresholdPercent=" + hdspSpeedChangeThresholdPercent +
                ", hdspSpeedStopThresholdPercent=" + hdspSpeedStopThresholdPercent +
                ", hdspDivergenceThresholdPercent=" + hdspDivergenceThresholdPercent +
                ", hdspSpeedMeasureWindowMs=" + hdspSpeedMeasureWindowMs +
                ", handyApiVersion=" + handyApiVersion +
                ", listenOnPort=" + listenOnPort +
                ", useOscQuery=" + useOscQuery +
                ", waitForApiResponse=" + waitForApiResponse +
                ", pointsOffset=" + pointsOffset +
                ", sendMessageEveryMs=" + sendMessageEveryMs +
                ", minimalValueChange=" + minimalValueChange +
                ", penetratorTipParameter='" + penetratorTipParameter + '\'' +
                ", sliderMin=" + sliderMin +
                ", sliderMax=" + sliderMax +
                ", fullyPenetratedAtValue=" + fullyPenetratedAtValue +
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
