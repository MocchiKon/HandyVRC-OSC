package org.example.handy.ble;

import lombok.extern.slf4j.Slf4j;
import org.simplejavable.Adapter;
import org.simplejavable.BluetoothUUID;
import org.simplejavable.Peripheral;

import java.util.List;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

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

    private volatile Peripheral handy;
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
                    if (isHandy(peripheral)) handy = peripheral;
                }
            });
            log.info("Scanning for BLE devices...");
            long start = System.currentTimeMillis();
            adapter.scanStart();
            while (handy == null && adapter.getScanIsActive() && (System.currentTimeMillis() - start) < 15_000) // Scan for 15s max
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

        log.info("Found Handy");
        handy.connect();

        handy.notify(SERVICE_BLUETOOTH_UUID, RX_BLUETOOTH_UUID, e -> {
            // Keep this callback cheap: it runs on the BLE stack's thread and every message the device sends
            // (responses as well as notifications) goes through here.
            rxQueue.offer(e);
            if (log.isTraceEnabled())
            {
                log.trace("BLE message received ({} bytes, {} queued)", e.length, rxQueue.size());
            }
        });

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

    @Override
    public void write(byte[] payload)
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
}
