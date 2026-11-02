package org.example.handy.ble;

import lombok.extern.slf4j.Slf4j;
import org.simplejavable.*;

import java.util.HexFormat;
import java.util.List;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Thin BLE transport around the Handy peripheral: it writes request payloads and queues every notification
 * (responses and device notifications alike) it receives.
 * <p>
 * The received messages are queued as-is and must be consumed by a single reader (see {@link HandyRpcClient}),
 * which routes each message to the request that is waiting for it. Keeping the queue free of any demultiplexing
 * logic here is what makes it impossible for one caller to consume (and thereby lose) another caller's response.
 */
@Slf4j
public class HandyBleAdapter implements RpcTransport
{
    public static final String SERVICE_UUID = "77834d26-40f7-11ee-be56-0242ac120002";
    public static final String TX_UUID = "77835032-40f7-11ee-be56-0242ac120002";
    public static final String RX_UUID = "77835410-40f7-11ee-be56-0242ac120002";

    public static final BluetoothUUID SERVICE_BLUETOOTH_UUID = new BluetoothUUID(SERVICE_UUID);
    public static final BluetoothUUID TX_BLUETOOTH_UUID = new BluetoothUUID(TX_UUID);
    public static final BluetoothUUID RX_BLUETOOTH_UUID = new BluetoothUUID(RX_UUID);

    /** How long a scan looks for the Handy before the app gives up. */
    public static final long DEFAULT_SCAN_TIMEOUT_MS = 15_000;

    private static final HexFormat HEX = HexFormat.of();

    private volatile Peripheral handy;
    private final BlockingQueue<byte[]> rxQueue = new LinkedBlockingQueue<>();
    /** The first message of a connection is logged at INFO, so that a log shows whether the device answered at all. */
    private final AtomicBoolean firstMessageLogged = new AtomicBoolean();

    /** Scans for the Handy with the default timeout and connects to it. */
    public void connect()
    {
        connect(DEFAULT_SCAN_TIMEOUT_MS);
    }

    /**
     * Scans for the Handy and connects to it.
     *
     * @param scanTimeoutMs how long to look for the Handy before reporting that it was not found
     */
    public void connect(long scanTimeoutMs)
    {
        List<Adapter> adapters = Adapter.getAdapters();
        log.info("Available Bluetooth adapters: {}", adapters.size());
        if (adapters.isEmpty())
        {
            throw new IllegalStateException("No Bluetooth adapter found");
        }
        Adapter adapter = adapters.get(0);

        Peripheral found;
        try
        {
            adapter.setEventListener(new Adapter.EventListener()
            {
                @Override
                public void onScanFound(Peripheral peripheral)
                {
                    if (isHandy(peripheral)) handy = peripheral;
                }
            });
            log.info("Scanning for BLE devices...");
            long start = System.currentTimeMillis();
            adapter.scanStart();
            while (handy == null && adapter.getScanIsActive() && (System.currentTimeMillis() - start) < scanTimeoutMs)
            {
                Thread.sleep(50);
            }
            long end = System.currentTimeMillis();
            log.info("Finished scanning. Took {}", (end - start));

            // Read the results before the scan is stopped: some backends drop them together with the scan.
            found = adapter.scanGetResults().stream()
                    .filter(this::isHandy)
                    .findFirst()
                    .orElse(handy);
        }
        catch (Exception e)
        {
            throw new RuntimeException("BLE scan failed", e);
        }
        finally
        {
            // A scan that keeps running holds the radio and, on some Bluetooth stacks, delays or breaks the
            // connection and its notifications. It is stopped before connecting, also when the Handy was found early.
            stopScan(adapter);
        }

        if (found == null)
        {
            throw new IllegalStateException("Handy not found. Make sure it is turned on, nearby and not connected to another app.");
        }
        handy = found;
        logFound(found);

        try
        {
            handy.connect();
        }
        catch (RuntimeException e)
        {
            throw new IllegalStateException("Could not connect to the Handy over Bluetooth: %s".formatted(e.getMessage()), e);
        }
        log.info("BLE connected to '{}' ({}), MTU {} bytes", nameOf(handy), addressOf(handy), getMtu());
        logGatt(handy);

        try
        {
            handy.notify(SERVICE_BLUETOOTH_UUID, RX_BLUETOOTH_UUID, this::onMessage);
        }
        catch (RuntimeException e)
        {
            throw new IllegalStateException("Could not subscribe to the Handy's BLE notifications: %s"
                    .formatted(e.getMessage()), e);
        }

        log.info("Connected and subscribed to BLE notifications.");
    }

    public static boolean isBluetoothEnabled()
    {
        return Adapter.isBluetoothEnabled();
    }

    private boolean isHandy(Peripheral peripheral)
    {
        return peripheral.services()
                .stream()
                .anyMatch(s -> s.uuid().equals(SERVICE_UUID));
    }

    private static void stopScan(Adapter adapter)
    {
        try
        {
            if (adapter.getScanIsActive())
            {
                adapter.scanStop();
            }
        }
        catch (Exception e)
        {
            // Not being able to stop the scan must not abort a connect that may still work.
            log.warn("Could not stop the BLE scan: {}", e.getMessage());
        }
    }

    /**
     * Logs which peripheral was picked, so that a support log shows whether it is a Handy and which one. Firmware 4
     * devices advertise as {@code ohd_hwX_...} (X is the model), older ones as "The Handy".
     */
    private static void logFound(Peripheral peripheral)
    {
        log.info("Found Handy: name='{}', address={}, RSSI={} dBm", nameOf(peripheral), addressOf(peripheral),
                peripheral.getRssi());
    }

    /**
     * Logs the services and characteristics the device offers. The transport depends on the TX characteristic
     * accepting write commands and the RX characteristic sending notifications, so a support log should show that
     * before anything is sent.
     */
    private static void logGatt(Peripheral peripheral)
    {
        Characteristic tx = null;
        Characteristic rx = null;
        try
        {
            for (Service service : peripheral.services())
            {
                for (Characteristic characteristic : service.characteristics())
                {
                    log.debug("BLE service {} characteristic {} (read={}, write={}, writeWithoutResponse={}, notify={},"
                                    + " indicate={})", service.uuid(), characteristic.uuid(), characteristic.canRead(),
                            characteristic.canWriteRequest(), characteristic.canWriteCommand(), characteristic.canNotify(),
                            characteristic.canIndicate());
                    if (characteristic.uuid().equalsIgnoreCase(TX_UUID)) tx = characteristic;
                    if (characteristic.uuid().equalsIgnoreCase(RX_UUID)) rx = characteristic;
                }
            }
        }
        catch (RuntimeException e)
        {
            log.warn("Could not read the BLE services of the Handy: {}", e.getMessage());
            return;
        }
        if (tx == null || rx == null)
        {
            // Some backends only list the advertised services right after connecting. The connection is used
            // anyway; if the characteristics are really missing, subscribing to the notifications fails right below.
            log.info("The Handy's GATT services did not show the RPC characteristics yet (TX {} found: {}, RX {}"
                    + " found: {})", TX_UUID, tx != null, RX_UUID, rx != null);
            return;
        }
        log.info("BLE RPC characteristics: TX write={}, writeWithoutResponse={}; RX notify={}, indicate={}",
                tx.canWriteRequest(), tx.canWriteCommand(), rx.canNotify(), rx.canIndicate());
        if (!tx.canWriteCommand() && tx.canWriteRequest())
        {
            log.warn("The Handy's TX characteristic does not accept write commands, only write requests");
        }
        if (!rx.canNotify())
        {
            log.warn("The Handy's RX characteristic does not support notifications");
        }
    }

    /** @return the advertised name of the connected device, or an empty string when it is not known */
    public String getPeripheralName()
    {
        return handy == null ? "" : nameOf(handy);
    }

    /** @return the negotiated BLE MTU in bytes, 0 when the connection does not report one */
    public int getMtu()
    {
        try
        {
            return handy == null ? 0 : handy.getMtu();
        }
        catch (RuntimeException e)
        {
            return 0;
        }
    }

    private static String nameOf(Peripheral peripheral)
    {
        String name = peripheral.getIdentifier();
        return name == null || name.isBlank() ? "(unnamed)" : name;
    }

    private static String addressOf(Peripheral peripheral)
    {
        return peripheral.getAddress() == null ? "(no address)" : peripheral.getAddress().toString();
    }

    /**
     * Keeps this callback cheap: it runs on the BLE stack's thread and every message the device sends (responses
     * as well as notifications) goes through here. The first message is logged with its bytes, which is what tells a
     * silent device apart from one that answers with something the app cannot use.
     */
    private void onMessage(byte[] data)
    {
        rxQueue.offer(data);
        if (firstMessageLogged.compareAndSet(false, true))
        {
            log.info("First BLE message from the Handy ({} bytes): {}", data.length, HEX.formatHex(data));
        }
        else if (log.isTraceEnabled())
        {
            log.trace("BLE message received ({} bytes, {} queued)", data.length, rxQueue.size());
        }
    }

    /**
     * Writes a message to the device. Synchronized because more than one thread can send (the movement commands
     * and, for diagnostics, the device position requests), and the BLE library does not promise that concurrent
     * writes to one peripheral are safe.
     */
    @Override
    public synchronized void write(byte[] payload)
    {
        if (!isConnected())
        {
            throw new IllegalStateException("Handy is not connected over BLE, cannot write " + payload.length + " bytes");
        }
        handy.writeCommand(SERVICE_BLUETOOTH_UUID, TX_BLUETOOTH_UUID, payload);
    }

    /**
     * Waits for the next message the device sends. Intended to be called by the single RPC reader thread only,
     * because the first caller that polls wins the message.
     */
    @Override
    public byte[] readMessage(long timeoutMs) throws TimeoutException, InterruptedException
    {
        byte[] data = rxQueue.poll(timeoutMs, TimeUnit.MILLISECONDS);
        if (data == null)
        {
            throw new TimeoutException("No BLE message received within " + timeoutMs + "ms");
        }
        return data;
    }

    public void disconnect()
    {
        if (handy != null && handy.isConnected())
        {
            handy.unsubscribe(SERVICE_BLUETOOTH_UUID, RX_BLUETOOTH_UUID);
            handy.disconnect();
            log.info("BLE disconnected.");
        }
    }

    public boolean isConnected()
    {
        return handy != null && handy.isConnected();
    }

    /** @return the Bluetooth address of the connected device, or its advertised name when the address is empty */
    public String getIdentifier()
    {
        if (handy == null)
        {
            return "";
        }
        String address = handy.getAddress() == null ? "" : handy.getAddress().toString();
        return address.isBlank() ? handy.getIdentifier() : address;
    }
}
