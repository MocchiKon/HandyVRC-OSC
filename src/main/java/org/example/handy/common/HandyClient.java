package org.example.handy.common;

import org.example.handy.common.dto.SliderSettingsResult;
import org.example.handy.common.dto.SliderState;

import java.util.Optional;

public abstract class HandyClient implements AutoCloseable
{
    public abstract HandyBaseResponseWithError changeMode(DeviceMode mode);
    public abstract boolean checkConnectionStatus();
    public abstract void setSliderSettings(Float min, Float max);
    public abstract Optional<SliderSettingsResult> getSliderSettings();

    /**
     * HDSP (Handy Direct Streaming Protocol): moves the slider to the given position using the given duration
     * (in ms) for the move.
     * <p>
     * The position is the normalized HDSP position in the 0.0-1.0 range of the configured stroke zone, which is
     * the unit the device protocol uses. The HDSP processor converts the percentage positions used in the rest
     * of the app before calling this, so an implementation only has to translate the command into its own wire
     * format (the BLE protocol takes the position as it is, Intiface sends it as a Buttplug LinearCmd).
     * <p>
     * The default implementation rejects it.
     *
     * @param xp normalized position in the 0.0-1.0 range (relative to the configured stroke zone)
     * @param t time in milliseconds the device should take to reach the position
     * @param stopOnTarget stop the slider motor when the target position is reached
     */
    public HandyBaseResponseWithError hdspXpt(float xp, int t, boolean stopOnTarget)
    {
        throw new UnsupportedOperationException("HDSP is not supported by " + getClass().getSimpleName());
    }

    /**
     * Stops the HDSP move the slider is executing, so that it does not keep moving after the app stopped sending
     * commands. The default implementation does nothing.
     */
    public void hdspStop()
    {
    }

    /**
     * Reads the position and speed the slider has right now. Only the direct Bluetooth connection can read it; the
     * default implementation reports nothing.
     */
    public Optional<SliderState> getSliderState()
    {
        return Optional.empty();
    }

    /**
     * Disconnects from the device and releases the resources the client holds (for example the reader thread
     * that receives BLE responses). The default implementation does nothing.
     */
    @Override
    public void close()
    {
    }
}
