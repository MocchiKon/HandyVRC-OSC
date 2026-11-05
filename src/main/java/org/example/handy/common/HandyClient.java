package org.example.handy.common;

import org.example.handy.common.dto.SliderSettingsResult;
import org.example.handy.common.dto.SliderState;

import java.util.Optional;

public abstract class HandyClient implements AutoCloseable
{
    public abstract HandyBaseResponseWithError changeMode(DeviceMode mode);
    public abstract boolean checkConnectionStatus();

    /**
     * @return a stable identifier of the connected device (a Bluetooth address, a Wi-Fi connection key, or a device
     * name), used to pick the calibration profile that belongs to it; empty when the connection cannot provide one
     */
    public String getDeviceIdentifier()
    {
        return "";
    }

    public abstract void setSliderSettings(Float min, Float max);
    public abstract Optional<SliderSettingsResult> getSliderSettings();

    /**
     * @return true when the connection can apply and read the stroke range on the device itself. Intiface cannot
     * (Buttplug has no stroke zone), so the stroke range setting is not used there. Bluetooth and Wi-Fi can.
     */
    public boolean supportsSliderSettings()
    {
        return true;
    }

    /**
     * @return true when the connected device can report its position. The Bluetooth and Wi-Fi connections can;
     * the device position log is not available through Intiface.
     */
    public boolean supportsSliderState()
    {
        return true;
    }

    /**
     * HDSP (Handy Direct Streaming Protocol): moves the slider to the given position using the given duration
     * (in ms) for the move.
     * <p>
     * The position is the normalized HDSP position in the 0.0-1.0 range of the configured stroke zone, which is
     * the unit used inside the app. The HDSP processor converts the percentage positions used in the rest of the
     * app before calling this, so an implementation only has to translate the command into its own wire format (the
     * BLE protocol and the Wi-Fi API take the normalized position as it is, Intiface sends it as a Buttplug
     * {@code HwPositionWithDuration} command). The Wi-Fi API documentation describes {@code xp} as a percentage, but
     * the service actually accepts 0.0-1.0 (0 = bottom, 1 = top), like the Bluetooth client.
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
     * Reads the position and speed the slider has right now. Bluetooth and Wi-Fi can read it; the default
     * implementation reports nothing.
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
