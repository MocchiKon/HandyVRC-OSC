package org.example.handy.intiface;

import lombok.extern.slf4j.Slf4j;
import org.example.handy.common.DeviceMode;
import org.example.handy.common.HandyBaseResponseWithError;
import org.example.handy.common.HandyClient;
import org.example.handy.common.HandyError;
import org.example.handy.common.dto.SliderSettingsResult;

import java.io.IOException;
import java.net.URI;
import java.util.Optional;

/**
 * Drives the device through Intiface Central (Buttplug protocol) instead of a direct Bluetooth connection.
 * <p>
 * An HDSP move (go to a position in a given time) is the same thing as a Buttplug {@code LinearCmd}, so the
 * processors work unchanged. Buttplug has no stroke zone and cannot report the slider position, so the stroke
 * range is applied here by mapping the positions into it, and {@link #getSliderState()} reports nothing.
 * <p>
 * The first device Intiface offers that can be moved to a position is used, which is not necessarily a Handy:
 * other strokers might work as well, but only the Handy is tested.
 */
@Slf4j
public class HandyClientIntiface extends HandyClient
{
    static final String CLIENT_NAME = "HandyVRC-OSC";
    private static final long DEVICE_SEARCH_TIMEOUT_MS = 15_000;

    private final ButtplugConnection connection;
    private final ButtplugConnection.Device device;
    private volatile float strokeMin = 0f;
    private volatile float strokeMax = 1f;

    HandyClientIntiface(ButtplugConnection connection, ButtplugConnection.Device device)
    {
        this.connection = connection;
        this.device = device;
    }

    /**
     * Connects to Intiface Central and picks the device to move: one it has connected already, otherwise the first
     * one it finds while scanning.
     *
     * @param uri address of the Intiface Central server, for example ws://127.0.0.1:12345
     */
    public static HandyClientIntiface connect(URI uri) throws IOException
    {
        ButtplugConnection connection = ButtplugConnection.connect(uri, CLIENT_NAME);
        try
        {
            ButtplugConnection.Device device = findDevice(connection);
            log.info("Using {} through Intiface Central", device.name());
            return new HandyClientIntiface(connection, device);
        }
        catch (IOException | RuntimeException e)
        {
            connection.close();
            throw e;
        }
    }

    private static ButtplugConnection.Device findDevice(ButtplugConnection connection) throws IOException
    {
        connection.refreshDeviceList();
        Optional<ButtplugConnection.Device> device = usableDevice(connection);
        if (device.isPresent())
        {
            return device.get();
        }
        log.info("Intiface Central has no usable device connected yet, scanning...");
        connection.startScanning();
        long start = System.currentTimeMillis();
        try
        {
            while (device.isEmpty() && connection.isOpen() && System.currentTimeMillis() - start < DEVICE_SEARCH_TIMEOUT_MS)
            {
                Thread.sleep(100);
                device = usableDevice(connection);
            }
        }
        catch (InterruptedException e)
        {
            Thread.currentThread().interrupt();
        }
        finally
        {
            stopScanning(connection);
        }
        return device.orElseThrow(() -> new IllegalStateException("Intiface Central did not find a device that can be"
                + " moved to a position. Turn on your device, check that Intiface Central shows it, and press Start again."));
    }

    private static Optional<ButtplugConnection.Device> usableDevice(ButtplugConnection connection)
    {
        return connection.devices().stream()
                .filter(device -> device.linearFeatures() > 0)
                .findFirst();
    }

    private static void stopScanning(ButtplugConnection connection)
    {
        if (!connection.isOpen())
        {
            return;
        }
        try
        {
            connection.stopScanning();
        }
        catch (IOException e)
        {
            log.debug("Could not stop scanning: {}", e.getMessage());
        }
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
    public HandyBaseResponseWithError changeMode(DeviceMode mode)
    {
        // Buttplug has no modes, every LinearCmd is a move to a position
        return new HandyBaseResponseWithError(null);
    }

    @Override
    public boolean checkConnectionStatus()
    {
        return connection.isOpen() && connection.hasDevice(device.index());
    }

    /** Buttplug has no stroke zone, so the positions are mapped into the range instead (see {@link #hdspXpt}). */
    @Override
    public void setSliderSettings(Float min, Float max)
    {
        strokeMin = min != null ? min : 0f;
        strokeMax = max != null ? max : 1f;
        log.info("Intiface: stroke range {} - {} is applied by the app", strokeMin, strokeMax);
    }

    @Override
    public Optional<SliderSettingsResult> getSliderSettings()
    {
        return Optional.of(new SliderSettingsResult(String.valueOf(strokeMin), String.valueOf(strokeMax)));
    }

    /**
     * Sends the move as a LinearCmd. Buttplug cannot keep the slider moving past the target, so 'stopOnTarget'
     * does not apply: the device always stops at the target.
     */
    @Override
    public HandyBaseResponseWithError hdspXpt(float xp, int t, boolean stopOnTarget)
    {
        try
        {
            double position = toDevicePosition(xp, strokeMin, strokeMax);
            connection.linear(device, position, t);
            log.trace("[Intiface] Sent LinearCmd (position={}, duration={}ms)", position, t);
            return new HandyBaseResponseWithError(null);
        }
        catch (Exception e)
        {
            return new HandyBaseResponseWithError(new HandyError(0, "LINEAR_CMD_FAILED", e.getMessage(), connection.isOpen()));
        }
    }

    /**
     * @param xp position in the 0.0-1.0 range of the stroke range
     * @return the same position in the 0.0-1.0 range of the whole stroke
     */
    static double toDevicePosition(float xp, float strokeMin, float strokeMax)
    {
        return strokeMin + Math.clamp(xp, 0f, 1f) * (strokeMax - strokeMin);
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
