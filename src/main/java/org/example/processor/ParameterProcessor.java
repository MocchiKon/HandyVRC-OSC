package org.example.processor;

import java.util.function.Consumer;

public interface ParameterProcessor
{
    void actOnValueChange(Float value);
    /**
     * Handles the SPS proximity values that were received in a single OSC packet: the proximity of the penetrator
     * root and/or tip (null when that parameter was not part of the packet). Together they are used to auto-detect
     * the penetrator length. Only used when the configured spsType is ORIFICE.
     */
    void actOnProximityChange(Float rootProximity, Float tipProximity);
    void run();
    void setValueChangeListener(Consumer<Integer> onValueChange);
}
