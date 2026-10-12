package org.example.processor;

import lombok.Getter;
import lombok.RequiredArgsConstructor;

@Getter
@RequiredArgsConstructor
public enum ParameterProcessorType
{
    /** Direct streaming (HDSP): every point is forwarded to the device as it arrives. */
    HDSP("Direct"),
    /**
     * Direct streaming (HDSP) with motion extrapolation: the input points are not forwarded 1:1, the slider is
     * moved at the measured input speed towards the top/bottom of the stroke and the speed is corrected only when
     * the input speed changes significantly. Default.
     */
    HDSP_SMOOTHED("Smoothed");

    /** Name shown to the user. */
    private final String label;
}
