package org.example.processor;

import org.example.config.ConfigProperties;

import java.util.function.Consumer;

public interface ParameterProcessor
{
    void actOnValueChange(Float value);
    void run();
    void refreshConfig(ConfigProperties configProperties);
    void setValueChangeListener(Consumer<Integer> onValueChange);

    /**
     * Synchronizes the clock with the device/server.
     * Returns the estimated offset in milliseconds. Pass it as serverTime when sending 'play' for sync protocols like HSP
     */
    long syncClock();
}
