package org.example.processor;

public enum ParameterProcessorType
{
    /** Buffered streaming (HSP). Works over both API and Bluetooth. Default. */
    HSP,
    /** Direct streaming (HDSP). Bluetooth only. */
    HDSP,
    /**
     * Direct streaming (HDSP) with motion extrapolation: the input points are not forwarded 1:1, the slider is
     * moved at the measured input speed towards the top/bottom of the stroke and the speed is corrected only when
     * the input speed changes significantly. Bluetooth only.
     */
    HDSP_SMOOTHED,
}
