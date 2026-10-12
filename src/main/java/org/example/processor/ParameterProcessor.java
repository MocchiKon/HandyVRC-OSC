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
    /** Starts sending commands to the device (in the background). */
    void run();
    /** Stops sending commands to the device. A stopped processor cannot be started again. */
    void stop();
    void setValueChangeListener(Consumer<Integer> onValueChange);
}
