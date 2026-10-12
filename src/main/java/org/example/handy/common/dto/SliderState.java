package org.example.handy.common.dto;

/**
 * Position and speed of the slider as the device reports them.
 *
 * @param position position in the 0.0-1.0 range of the stroke
 * @param positionMm absolute position in millimeters
 * @param speedMmPerSecond absolute speed in millimeters per second
 */
public record SliderState(float position, float positionMm, float speedMmPerSecond)
{
}
