package org.example.handy.ble;

import lombok.extern.slf4j.Slf4j;
import org.simplejavable.*;

import java.util.List;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

@Slf4j
public class HandyBleAdapter
{
    public static final String SERVICE_UUID = "77834d26-40f7-11ee-be56-0242ac120002";
    public static final String TX_UUID = "77835032-40f7-11ee-be56-0242ac120002";
    public static final String RX_UUID = "77835410-40f7-11ee-be56-0242ac120002";

    public static final BluetoothUUID SERVICE_BLUETOOTH_UUID = new BluetoothUUID(SERVICE_UUID);
    public static final BluetoothUUID TX_BLUETOOTH_UUID = new BluetoothUUID(TX_UUID);
    public static final BluetoothUUID RX_BLUETOOTH_UUID = new BluetoothUUID(RX_UUID);

    private Peripheral handy;
    private final BlockingQueue<byte[]> rxQueue = new LinkedBlockingQueue<>();

    public void connect()
    {
        List<Adapter> adapters = Adapter.getAdapters();
        log.info("Available Bluetooth adapters: {}", adapters.size());
        Adapter adapter = adapters.get(0);
        try
        {
            adapter.setEventListener(new Adapter.EventListener()
            {
                @Override
                public void onScanFound(Peripheral peripheral)
                {
                    if (isHandy(peripheral))
                    {
                        handy = peripheral;
                        log.info("Found Bluetooth adapter: {}", peripheral);
                    }
                }
            });
            log.info("Scanning for BLE devices...");
            long start = System.currentTimeMillis();
            adapter.scanStart();
            while (handy == null && adapter.getScanIsActive() && (System.currentTimeMillis() - start) < 10_000) // Scan for 10s max
            {
                Thread.sleep(100);
            }
            long end = System.currentTimeMillis();
            log.info("Finished scanning. Took {}", (end - start));
        }
        catch (Exception e)
        {
            throw new RuntimeException("BLE scan failed", e);
        }

        handy = adapter.scanGetResults().stream()
                .filter(this::isHandy)
                .findFirst()
                .orElseThrow(() -> new RuntimeException("Handy not found via BLE"));

        log.info("Found Handy: {} ({})", handy.getIdentifier(), handy.getAddress());
        handy.connect();

        handy.notify(SERVICE_BLUETOOTH_UUID, RX_BLUETOOTH_UUID, rxQueue::offer);

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

    public void write(byte[] payload)
    {
        handy.writeCommand(SERVICE_BLUETOOTH_UUID, TX_BLUETOOTH_UUID, payload);
    }

    /**
     * Waits for a notification response with timeout.
     */
    public byte[] waitForResponse(long timeoutMs) throws TimeoutException, InterruptedException
    {
        byte[] data = rxQueue.poll(timeoutMs, TimeUnit.MILLISECONDS);
        if (data == null)
        {
            throw new TimeoutException("No BLE response received within " + timeoutMs + "ms");
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
}
