package org.example.handy.intiface;

import lombok.extern.slf4j.Slf4j;
import org.example.handy.common.DeviceMode;
import org.example.handy.common.HandyBaseResponseWithError;
import org.example.handy.common.HandyClient;
import org.example.handy.common.HandyError;
import org.example.handy.common.dto.SliderSettingsResult;

import java.util.Optional;

/**
 * Drives any stroker Intiface Central offers through a Buttplug {@code OutputCmd} with the
 * {@code HwPositionWithDuration} output type.
 * <p>
 * An HDSP move (go to a position in a given time) maps to {@code HwPositionWithDuration}, so the processors work
 * unchanged. Buttplug has no stroke zone and cannot report the slider position, so the app does not apply a stroke
 * range in this mode (the device keeps its full stroke) and {@link #getSliderState()} reports nothing.
 */
@Slf4j
public class GenericLinearDevice extends HandyClient
{
    private final ButtplugConnection connection;
    private final ButtplugConnection.Device device;

    GenericLinearDevice(ButtplugConnection connection, ButtplugConnection.Device device)
    {
        this.connection = connection;
        this.device = device;
    }

    public ButtplugConnection.Device device()
    {
        return device;
    }

    /** @return the connection this device is driven over, used to list devices without opening a second client */
    public ButtplugConnection connection()
    {
        return connection;
    }

    public String getDeviceName()
    {
        return device.name();
    }

    @Override
    public String getDeviceIdentifier()
    {
        return device.name() == null ? "" : device.name();
    }

    @Override
    public boolean checkConnectionStatus()
    {
        return connection.isOpen() && connection.hasDevice(device.index());
    }

    @Override
    public HandyBaseResponseWithError changeMode(DeviceMode mode)
    {
        // Buttplug has no modes, every position command is a move to a position
        return new HandyBaseResponseWithError(null);
    }

    /**
     * Buttplug has no stroke zone. The app used to map the positions into the configured range here, which silently
     * shortened every stroke even though the setting says it is applied on the device; it is now left to the device,
     * so the stroke range setting is not applied in Intiface mode at all.
     */
    @Override
    public void setSliderSettings(Float min, Float max)
    {
        log.debug("Ignoring the stroke range through Intiface: the device keeps its full stroke");
    }

    @Override
    public boolean supportsSliderSettings()
    {
        return false;
    }

    @Override
    public Optional<SliderSettingsResult> getSliderSettings()
    {
        return Optional.empty();
    }

    @Override
    public boolean supportsSliderState()
    {
        return false;
    }

    /**
     * Sends the move as an {@code HwPositionWithDuration} command. Intiface does not expose the Handy's
     * stop-on-target flag, so 'stopOnTarget' does not apply here.
     */
    @Override
    public HandyBaseResponseWithError hdspXpt(float xp, int t, boolean stopOnTarget)
    {
        try
        {
            double position = Math.clamp(xp, 0f, 1f);
            connection.hwPositionWithDuration(device, position, t);
            log.trace("[Intiface] Sent HwPositionWithDuration (position={}, duration={}ms)", position, t);
            return new HandyBaseResponseWithError(null);
        }
        catch (Exception e)
        {
            return new HandyBaseResponseWithError(new HandyError(0, "OUTPUT_CMD_FAILED", e.getMessage(),
                    connection.isOpen()));
        }
    }

    @Override
    public void hdspStop()
    {
        try
        {
            connection.stopDevice(device);
        }
        catch (Exception e)
        {
            log.warn("Could not stop the device: {}", e.getMessage());
        }
    }

    @Override
    public void close()
    {
        connection.close();
    }
}
