package org.example.processor;

import org.example.config.ConfigProperties;

import java.util.function.Consumer;

public interface ParameterProcessor
{
    void actOnValueChange(Float value);
    void run();
    void setValueChangeListener(Consumer<Integer> onValueChange);
}
