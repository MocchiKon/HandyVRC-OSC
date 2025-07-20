package org.example.processor;

import org.example.ConfigProperties;

import java.util.function.Consumer;

public interface ParameterProcessor
{
    void actOnValueChange(Float value);
    void run();
    void refreshConfig(ConfigProperties configProperties);
    void setValueChangeListener(Consumer<Integer> onValueChange);
}
