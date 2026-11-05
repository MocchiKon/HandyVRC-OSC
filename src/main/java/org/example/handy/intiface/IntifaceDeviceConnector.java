package org.example.handy.intiface;

import lombok.NoArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.example.handy.common.HandyClient;

import java.io.IOException;
import java.net.URI;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.stream.Collectors;

/**
 * Connects to Intiface Central, picks the device the user selected, and creates the client that drives it with the
 * Buttplug {@code HwPositionWithDuration} output type (see {@link GenericLinearDevice}).
 */
@Slf4j
@NoArgsConstructor(access = lombok.AccessLevel.PRIVATE)
public final class IntifaceDeviceConnector
{
    public static final String CLIENT_NAME = "HandyVRC-OSC";
    /** Shown in the settings and with the running status when a Handy is driven through Intiface Central. */
    public static final String HANDY_WARNING = "Bluetooth connection is strongly recommended for The Handy. When using"
            + " Intiface the stroke range setting is ignored (the device keeps its full stroke), stop-on-target does"
            + " not work, and slider movement will not be as good.";
    static final long DEVICE_SEARCH_TIMEOUT_MS = 15_000;
    /** How long listing the devices scans for a device when none is connected yet. */
    public static final long DISCOVERY_SCAN_TIMEOUT_MS = 5_000;
    private static final long SCAN_POLL_MS = 100;

    /**
     * Connects to Intiface Central and returns the client for the selected device.
     *
     * @param uri                  address of the Intiface Central server
     * @param preferredDeviceIndex Intiface device index of the selected device, negative to use the first device
     *                             that can be moved
     * @param preferredDeviceName  display label of the selected device, used to find it again when its index changed
     */
    public static HandyClient connect(URI uri, int preferredDeviceIndex, String preferredDeviceName) throws IOException
    {
        ButtplugConnection connection = ButtplugConnection.connect(uri, CLIENT_NAME);
        try
        {
            ButtplugConnection.Device device = findDevice(connection, preferredDeviceIndex, preferredDeviceName);
            log.info("Using {} through Intiface Central", device.displayLabel());
            if (isHandy(device.name()))
            {
                log.warn(HANDY_WARNING);
            }
            return new GenericLinearDevice(connection, device);
        }
        catch (IOException | RuntimeException e)
        {
            connection.close();
            throw e;
        }
    }

    /**
     * Finds the device to use: the selected one by its index, or by its saved display label when Intiface gave it a
     * new index, otherwise the first one that can be moved to a position. Scans when it is not connected yet.
     */
    static ButtplugConnection.Device findDevice(ButtplugConnection connection, int preferredDeviceIndex,
                                                String preferredDeviceName) throws IOException
    {
        String preferred = preferredDeviceName == null ? "" : preferredDeviceName.trim();
        connection.refreshDeviceList();
        Optional<ButtplugConnection.Device> device = matchingDevice(connection, preferredDeviceIndex, preferred);
        if (device.isPresent())
        {
            return device.get();
        }
        if (knownWithoutPositionControl(connection, preferredDeviceIndex, preferred))
        {
            throw new IllegalStateException(notFoundMessage(connection, preferredDeviceIndex, preferred));
        }
        if (preferredDeviceIndex >= 0)
        {
            log.info("The selected device '{}' is not connected yet, scanning...",
                    describeSelection(preferredDeviceIndex, preferred));
        }
        else
        {
            log.info("Intiface Central has no usable device connected yet, scanning...");
        }
        connection.startScanning();
        long start = System.currentTimeMillis();
        try
        {
            while (device.isEmpty() && connection.isOpen()
                    && System.currentTimeMillis() - start < DEVICE_SEARCH_TIMEOUT_MS)
            {
                Thread.sleep(SCAN_POLL_MS);
                device = matchingDevice(connection, preferredDeviceIndex, preferred);
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
        return device.orElseThrow(
                () -> new IllegalStateException(notFoundMessage(connection, preferredDeviceIndex, preferred)));
    }

    /**
     * @return the selected device. The index is checked first because names are not unique. The saved display label
     * is only used when there is a selected index, so that it can still find the device after Intiface gave it a new
     * index; a name without an index means "first available device".
     */
    private static Optional<ButtplugConnection.Device> matchingDevice(ButtplugConnection connection,
                                                                      int preferredDeviceIndex, String preferred)
    {
        List<ButtplugConnection.Device> movable = connection.devices().stream()
                .filter(ButtplugConnection.Device::hasPositionControl)
                .toList();
        if (preferredDeviceIndex < 0)
        {
            return movable.stream().findFirst();
        }
        if (!preferred.isEmpty())
        {
            Optional<ButtplugConnection.Device> byIndexAndName = movable.stream()
                    .filter(device -> device.index() == preferredDeviceIndex && matchesName(device, preferred))
                    .findFirst();
            if (byIndexAndName.isPresent())
            {
                return byIndexAndName;
            }
            Optional<ButtplugConnection.Device> byName = movable.stream()
                    .filter(device -> matchesName(device, preferred))
                    .findFirst();
            if (byName.isPresent())
            {
                return byName;
            }
        }
        return movable.stream()
                .filter(device -> device.index() == preferredDeviceIndex)
                .findFirst();
    }

    private static boolean matchesName(ButtplugConnection.Device device, String name)
    {
        return device.displayLabel().equalsIgnoreCase(name) || device.name().equalsIgnoreCase(name);
    }

    private static String notFoundMessage(ButtplugConnection connection, int preferredDeviceIndex, String preferred)
    {
        String selection = describeSelection(preferredDeviceIndex, preferred);
        if (knownWithoutPositionControl(connection, preferredDeviceIndex, preferred))
        {
            return "The device '%s' cannot be moved to a position through Intiface Central. Choose a stroker"
                    .formatted(selection);
        }
        if (hasSelection(preferredDeviceIndex))
        {
            return ("The device '%s' selected in the app is not connected to Intiface Central. Connect it there,"
                    + " press Refresh in the Device section, and press Start again. Devices Intiface Central reports: %s")
                    .formatted(selection, availableDevices(connection));
        }
        return "Intiface Central did not find a device that can be moved to a position. Turn on your device, check"
                + " that Intiface Central shows it, and press Start again.";
    }

    private static boolean knownWithoutPositionControl(ButtplugConnection connection, int preferredDeviceIndex,
                                                       String preferred)
    {
        if (preferredDeviceIndex < 0)
        {
            return false;
        }
        return connection.devices().stream()
                .filter(device -> !device.hasPositionControl())
                .anyMatch(device -> device.index() == preferredDeviceIndex
                        || (!preferred.isEmpty() && matchesName(device, preferred)));
    }

    private static boolean hasSelection(int preferredDeviceIndex)
    {
        return preferredDeviceIndex >= 0;
    }

    private static String describeSelection(int preferredDeviceIndex, String preferred)
    {
        return !preferred.isEmpty() ? preferred : "device " + preferredDeviceIndex;
    }

    private static String availableDevices(ButtplugConnection connection)
    {
        String names = connection.devices().stream()
                .map(ButtplugConnection.Device::displayLabel)
                .sorted(String.CASE_INSENSITIVE_ORDER)
                .distinct()
                .collect(Collectors.joining(", "));
        return names.isEmpty() ? "none" : names;
    }

    /**
     * Lists the devices Intiface Central knows, for the device dropdown in the settings. Scans briefly when no device
     * is connected yet, so the list is not empty right after starting Intiface Central.
     */
    public static List<ButtplugConnection.Device> discover(URI uri) throws IOException
    {
        ButtplugConnection connection = ButtplugConnection.connect(uri, CLIENT_NAME);
        try
        {
            return listDevices(connection);
        }
        finally
        {
            connection.close();
        }
    }

    /**
     * Lists the devices for the settings, reusing the connection the app already holds to the same server when
     * there is one. Intiface Central may refuse a second client, and then every Refresh fails until the app is
     * restarted.
     *
     * @param current the client the app is connected with, or null
     * @param uri     address of the Intiface Central server from the settings
     */
    public static List<ButtplugConnection.Device> discover(HandyClient current, URI uri) throws IOException
    {
        if (current instanceof GenericLinearDevice generic && generic.connection().isOpen()
                && uri.equals(generic.connection().uri()))
        {
            return listDevices(generic.connection());
        }
        return discover(uri);
    }

    /**
     * Reads the devices of an already connected Intiface connection. The settings use this while the app holds a
     * connection to the device: opening a second client for the same server can make Intiface Central refuse it (and
     * then every Refresh fails until the app is restarted).
     */
    public static List<ButtplugConnection.Device> listDevices(ButtplugConnection connection) throws IOException
    {
        connection.refreshDeviceList();
        if (connection.devices().stream().noneMatch(ButtplugConnection.Device::hasPositionControl))
        {
            scanForDevices(connection);
        }
        return connection.devices().stream()
                .sorted(Comparator.comparing(ButtplugConnection.Device::displayLabel,
                        String.CASE_INSENSITIVE_ORDER))
                .toList();
    }

    private static void scanForDevices(ButtplugConnection connection)
    {
        try
        {
            connection.startScanning();
        }
        catch (IOException e)
        {
            log.warn("Could not scan for devices in Intiface Central: {}", e.getMessage());
            return;
        }
        try
        {
            long start = System.currentTimeMillis();
            while (System.currentTimeMillis() - start < DISCOVERY_SCAN_TIMEOUT_MS && connection.isOpen()
                    && connection.devices().stream().noneMatch(ButtplugConnection.Device::hasPositionControl))
            {
                Thread.sleep(SCAN_POLL_MS);
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

    /**
     * @return true when the device name belongs to a Handy. The Handy 2 and the Handy 2 Pro report names like
     * "The Handy 2 Pro" here, so matching the brand name covers all of them.
     */
    public static boolean isHandy(String deviceName)
    {
        return deviceName != null && deviceName.toLowerCase(Locale.ROOT).contains("handy");
    }
}
