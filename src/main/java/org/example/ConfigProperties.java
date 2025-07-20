package org.example;

import lombok.Builder;
import org.example.processor.ParameterProcessorType;
import org.example.processor.SpsType;

@Builder
public record ConfigProperties(
        String deviceConnectionKey,
        String avatarParameter,
        String handyApplicationId,
        ParameterProcessorType processingAlgorithm,
        int handyApiVersion,
        int listenOnPort,
        boolean waitForApiResponse,
        int pointsOffset,
        int sendMessageEveryMs,
        int minimalValueChange,
        float penetratorLength,
        Float sliderMin,
        Float sliderMax,
        SpsType spsType
)
{
}
