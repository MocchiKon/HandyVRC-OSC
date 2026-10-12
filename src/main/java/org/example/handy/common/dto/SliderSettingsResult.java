package org.example.handy.common.dto;

/**
 * Stroke zone of the slider.
 *
 * @param min start of the zone in the 0.0-1.0 range of the slider
 * @param max end of the zone in the 0.0-1.0 range of the slider
 * @param minMm absolute position of the start of the zone in millimeters, null when the device does not report it
 * @param maxMm absolute position of the end of the zone in millimeters, null when the device does not report it
 */
public record SliderSettingsResult(String min, String max, Float minMm, Float maxMm)
{
    public SliderSettingsResult(String min, String max)
    {
        this(min, max, null, null);
    }

    /** @return length of the stroke zone in millimeters, null when it is not known */
    public Float strokeMm()
    {
        return minMm == null || maxMm == null || maxMm <= minMm ? null : maxMm - minMm;
    }
}
