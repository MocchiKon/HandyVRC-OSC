package org.example.handy.intiface;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import lombok.extern.slf4j.Slf4j;
import org.example.handy.common.RateLimitedLogger;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.WebSocket;
import java.time.Duration;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Client side of the Buttplug protocol that Intiface Central speaks over a WebSocket. The app uses message
 * specification version 4, which sends positions as an {@code OutputCmd} with the {@code HwPositionWithDuration}
 * output type.
 * <p>
 * Every message is a JSON array of objects, each object holding one message keyed by its type, for example
 * {@code [{"OutputCmd":{"Id":5,"DeviceIndex":0,"FeatureIndex":0,"Command":{...}}}}]}. The client numbers its
 * requests (Id 1 and up) and the server answers each with a message carrying the same Id ({@code Ok}, {@code Error}
 * or the requested data). Messages with Id 0 are events the server sends on its own, such as {@code DeviceList} and
 * {@code ScanningFinished}.
 */
@Slf4j
public class ButtplugConnection implements AutoCloseable
{
    static final int PROTOCOL_VERSION_MAJOR = 4;
    static final int PROTOCOL_VERSION_MINOR = 0;
    private static final long CONNECT_TIMEOUT_MS = 5_000;
    private static final long REQUEST_TIMEOUT_MS = 5_000;

    /** Sends the text messages of a connection; the WebSocket in the app, a double in the tests. */
    interface Transport
    {
        void send(String text) throws IOException;

        void close();
    }

    /**
     * A device the server knows about.
     *
     * @param linearFeatures number of features that can move to a position, 0 when the device cannot be moved
     * @param displayName    the name the user gave the device in Intiface, empty when it has none
     * @param featureIndex   feature used for position commands, -1 when the device has no position feature
     * @param valueMin       lowest position value of the feature
     * @param valueMax       highest position value of the feature
     * @param durationMin    shortest move duration (ms) of the feature
     * @param durationMax    longest move duration (ms) of the feature
     */
    public record Device(int index, String name, int linearFeatures, String displayName, int featureIndex,
                         int valueMin, int valueMax, int durationMin, int durationMax)
    {
        public Device
        {
            name = name == null ? "Unknown device" : name;
            displayName = displayName == null ? "" : displayName;
            if (valueMax < valueMin)
            {
                int swap = valueMin;
                valueMin = valueMax;
                valueMax = swap;
            }
            if (durationMax < durationMin)
            {
                int swap = durationMin;
                durationMin = durationMax;
                durationMax = swap;
            }
        }

        /** Convenience constructor for tests. */
        public Device(int index, String name, int linearFeatures)
        {
            this(index, name, linearFeatures, "", linearFeatures > 0 ? 0 : -1, 0, 100, 0, 100_000);
        }

        /** @return the name to show in the device list, with the user's display name when there is one */
        public String displayLabel()
        {
            return displayName.isBlank() || displayName.equalsIgnoreCase(name)
                    ? name
                    : displayName + " (" + name + ")";
        }

        public boolean hasPositionControl()
        {
            return linearFeatures > 0 && featureIndex >= 0;
        }

        /**
         * @param normalizedPosition position in the 0.0-1.0 range of the stroke
         * @return the value the device feature expects for that position
         */
        public int positionValue(double normalizedPosition)
        {
            double clamped = Math.clamp(normalizedPosition, 0.0, 1.0);
            int value = valueMin + (int) Math.round(clamped * (valueMax - valueMin));
            return Math.clamp(value, valueMin, valueMax);
        }

        /** @return the requested duration clamped to the range the device advertises */
        public int durationMs(int requestedMs)
        {
            return Math.clamp(requestedMs, durationMin, durationMax);
        }
    }

    private final ObjectMapper json = new ObjectMapper();
    private final AtomicInteger nextId = new AtomicInteger(1);
    private final Map<Integer, CompletableFuture<JsonNode>> pending = new ConcurrentHashMap<>();
    /** Replaced as a whole when a DeviceList arrives, so readers always see a complete list. */
    private volatile Map<Integer, Device> devices = Map.of();
    /** Movement commands are sent continuously, a failing one would otherwise flood the log. */
    private final RateLimitedLogger commandErrors = new RateLimitedLogger(log);
    private Transport transport;
    private volatile boolean open = true;
    private ScheduledExecutorService pinger;
    /** Address the connection was opened with, null for a connection created by a test. */
    private URI uri;

    ButtplugConnection()
    {
        this(null);
    }

    /** @param uri address the connection is considered to belong to (tests use a fake transport) */
    ButtplugConnection(URI uri)
    {
        this.uri = uri;
    }

    /**
     * Connects to the server and introduces the client.
     *
     * @param uri        address of the server, for example ws://127.0.0.1:12345
     * @param clientName name the server shows for this client
     */
    public static ButtplugConnection connect(URI uri, String clientName) throws IOException
    {
        var connection = new ButtplugConnection(uri);
        WebSocket webSocket;
        try
        {
            webSocket = HttpClient.newHttpClient()
                    .newWebSocketBuilder()
                    .connectTimeout(Duration.ofMillis(CONNECT_TIMEOUT_MS))
                    .buildAsync(uri, new Listener(connection))
                    .get(CONNECT_TIMEOUT_MS, TimeUnit.MILLISECONDS);
        }
        catch (ExecutionException | TimeoutException e)
        {
            throw new IOException("Could not reach Intiface Central at %s. Start Intiface Central and its server,"
                    .formatted(uri) + " then press Start again.", e);
        }
        catch (InterruptedException e)
        {
            Thread.currentThread().interrupt();
            throw new IOException("Interrupted while connecting to Intiface Central", e);
        }
        connection.transport = new WebSocketTransport(webSocket);
        try
        {
            connection.handshake(clientName);
        }
        catch (IOException | RuntimeException e)
        {
            connection.close();
            throw e;
        }
        return connection;
    }

    /** Sends RequestServerInfo and starts pinging when the server asks for it. */
    void handshake(String clientName) throws IOException
    {
        ObjectNode request = json.createObjectNode()
                .put("ClientName", clientName)
                .put("ProtocolVersionMajor", PROTOCOL_VERSION_MAJOR)
                .put("ProtocolVersionMinor", PROTOCOL_VERSION_MINOR);
        JsonNode serverInfo;
        try
        {
            serverInfo = request("RequestServerInfo", request, "ServerInfo");
        }
        catch (IOException e)
        {
            if (e.getCause() instanceof TimeoutException)
            {
                throw new IOException("Intiface Central did not accept Buttplug protocol v" + PROTOCOL_VERSION_MAJOR
                        + ". Update Intiface Central to a version that supports it and start its server again.", e);
            }
            throw e;
        }
        int major = serverInfo.path("ProtocolVersionMajor").asInt(0);
        int minor = serverInfo.path("ProtocolVersionMinor").asInt(0);
        if (major < PROTOCOL_VERSION_MAJOR)
        {
            throw new IOException("Intiface Central reports Buttplug protocol v%d.%d, but this app needs v%d or"
                    .formatted(major, minor, PROTOCOL_VERSION_MAJOR) + " newer. Update Intiface Central.");
        }
        long maxPingTimeMs = serverInfo.path("MaxPingTime").asLong(0);
        log.info("Connected to {} (Buttplug protocol v{}.{}, ping every {})",
                serverInfo.path("ServerName").asText("Intiface"), major, minor,
                maxPingTimeMs > 0 ? maxPingTimeMs / 2 + "ms" : "never");
        if (maxPingTimeMs > 0)
        {
            startPinging(Math.max(maxPingTimeMs / 2, 50));
        }
    }

    /** Uses the given transport (tests only). */
    void setTransport(Transport transport)
    {
        this.transport = transport;
    }

    public boolean isOpen()
    {
        return open;
    }

    /** @return the address this connection was opened with, null when it was created by a test */
    public URI uri()
    {
        return uri;
    }

    /** @return the devices the server currently has connected, in the order of their Intiface device index */
    public Collection<Device> devices()
    {
        return devices.values().stream()
                .sorted(Comparator.comparingInt(Device::index))
                .toList();
    }

    public boolean hasDevice(int index)
    {
        return devices.containsKey(index);
    }

    /** Asks the server for the devices it has connected already. */
    public void refreshDeviceList() throws IOException
    {
        JsonNode list = request("RequestDeviceList", json.createObjectNode(), "DeviceList");
        replaceDevices(list);
    }

    public void startScanning() throws IOException
    {
        request("StartScanning", json.createObjectNode(), "Ok");
    }

    public void stopScanning() throws IOException
    {
        request("StopScanning", json.createObjectNode(), "Ok");
    }

    /**
     * Moves the device's position feature to the given position in the given time using a Buttplug
     * {@code HwPositionWithDuration} command. Does not wait for the answer (commands are sent continuously,
     * waiting would stall the stream); a failure is logged.
     *
     * @param normalizedPosition 0.0 (bottom) to 1.0 (top) of the device's advertised position range
     */
    public void hwPositionWithDuration(Device device, double normalizedPosition, int durationMs) throws IOException
    {
        if (!device.hasPositionControl())
        {
            throw new IOException("The device '%s' cannot be moved to a position".formatted(device.name()));
        }
        ObjectNode output = json.createObjectNode()
                .put("Value", device.positionValue(normalizedPosition))
                .put("Duration", device.durationMs(durationMs));
        ObjectNode command = json.createObjectNode();
        command.set("HwPositionWithDuration", output);
        ObjectNode fields = json.createObjectNode()
                .put("DeviceIndex", device.index())
                .put("FeatureIndex", device.featureIndex());
        fields.set("Command", command);
        sendWithoutWaiting("OutputCmd", fields);
    }

    /** Stops all outputs of the device. Does not wait for the answer. */
    public void stopDevice(Device device) throws IOException
    {
        sendWithoutWaiting("StopCmd", json.createObjectNode().put("DeviceIndex", device.index()));
    }

    /**
     * Sends a request and waits for its answer.
     *
     * @param expectedType type of the answer, for example "Ok"
     * @return the fields of the answer
     */
    JsonNode request(String type, ObjectNode fields, String expectedType) throws IOException
    {
        CompletableFuture<JsonNode> answer = send(type, fields);
        JsonNode message;
        try
        {
            message = answer.get(REQUEST_TIMEOUT_MS, TimeUnit.MILLISECONDS);
        }
        catch (TimeoutException e)
        {
            throw new IOException("Intiface Central did not answer " + type + " within " + REQUEST_TIMEOUT_MS + "ms", e);
        }
        catch (ExecutionException e)
        {
            throw new IOException(type + " failed: " + e.getCause().getMessage(), e.getCause());
        }
        catch (InterruptedException e)
        {
            Thread.currentThread().interrupt();
            throw new IOException("Interrupted while waiting for Intiface Central", e);
        }
        String answerType = message.fieldNames().next();
        if (!answerType.equals(expectedType))
        {
            throw new IOException("Intiface Central answered %s with %s instead of %s".formatted(type, answerType, expectedType));
        }
        return message.get(answerType);
    }

    private void sendWithoutWaiting(String type, ObjectNode fields) throws IOException
    {
        send(type, fields).whenComplete((answer, error) ->
        {
            if (error != null)
            {
                commandErrors.warn("[Intiface] %s failed: %s".formatted(type, error.getMessage()));
            }
        });
    }

    private CompletableFuture<JsonNode> send(String type, ObjectNode fields) throws IOException
    {
        if (!open)
        {
            throw new IOException("The connection to Intiface Central is closed");
        }
        int id = nextId.getAndIncrement();
        fields.put("Id", id);
        ArrayNode messages = json.createArrayNode();
        messages.addObject().set(type, fields);
        var answer = new CompletableFuture<JsonNode>();
        pending.put(id, answer);
        try
        {
            String text = json.writeValueAsString(messages);
            transport.send(text);
            if (log.isTraceEnabled())
            {
                log.trace("[Intiface] Sent {}", text);
            }
        }
        catch (IOException | RuntimeException e)
        {
            pending.remove(id);
            throw e;
        }
        return answer;
    }

    /** Handles a complete text message from the server. */
    void onText(String text)
    {
        JsonNode messages;
        try
        {
            messages = json.readTree(text);
        }
        catch (IOException e)
        {
            log.warn("[Intiface] Could not read a message from Intiface Central: {}", e.getMessage());
            return;
        }
        for (JsonNode message : messages)
        {
            if (!message.isObject() || message.isEmpty())
            {
                continue;
            }
            String type = message.fieldNames().next();
            JsonNode fields = message.get(type);
            int id = fields.path("Id").asInt(0);
            CompletableFuture<JsonNode> answer = id != 0 ? pending.remove(id) : null;
            if (answer != null)
            {
                if (type.equals("Error"))
                {
                    answer.completeExceptionally(new IOException(fields.path("ErrorMessage").asText("unknown error")));
                }
                else
                {
                    answer.complete(message);
                }
                continue;
            }
            onEvent(type, fields);
        }
    }

    private void onEvent(String type, JsonNode fields)
    {
        switch (type)
        {
            // In protocol v4 the server sends the complete device list instead of DeviceAdded/DeviceRemoved events.
            case "DeviceList" -> replaceDevices(fields);
            case "ScanningFinished" -> log.debug("[Intiface] Scanning finished");
            case "Error" -> log.warn("[Intiface] Intiface Central reported an error: {}", fields.path("ErrorMessage").asText());
            default -> log.trace("[Intiface] Ignoring {}", type);
        }
    }

    /** Replaces the known devices with the complete list the server sent. */
    private void replaceDevices(JsonNode list)
    {
        var updated = new LinkedHashMap<Integer, Device>();
        JsonNode devicesNode = list.path("Devices");
        var deviceNodes = new ArrayList<JsonNode>();
        devicesNode.forEach(deviceNodes::add);
        for (JsonNode deviceNode : deviceNodes)
        {
            Device device = parseDevice(deviceNode);
            updated.put(device.index(), device);
        }

        for (Device removed : devices.values())
        {
            if (!updated.containsKey(removed.index()))
            {
                log.warn("[Intiface] {} disconnected", removed.displayLabel());
            }
        }
        for (Device device : updated.values())
        {
            Device previous = devices.get(device.index());
            if (previous == null)
            {
                log.info("[Intiface] Device available: {} ({})", device.displayLabel(), describe(device));
            }
            else if (!previous.equals(device))
            {
                log.info("[Intiface] Device updated: {} ({})", device.displayLabel(), describe(device));
            }
        }
        devices = Map.copyOf(updated);
    }

    private static String describe(Device device)
    {
        if (!device.hasPositionControl())
        {
            return "no position control, not usable";
        }
        return "can be moved to a position, values %d-%d, duration %d-%dms".formatted(device.valueMin(),
                device.valueMax(), device.durationMin(), device.durationMax());
    }

    private static Device parseDevice(JsonNode fields)
    {
        int index = fields.path("DeviceIndex").asInt();
        String name = fields.path("DeviceName").asText("Unknown device");
        String displayName = fields.path("DeviceDisplayName").asText("");
        int linearFeatures = 0;
        int featureIndex = -1;
        int valueMin = 0;
        int valueMax = 100;
        int durationMin = 0;
        int durationMax = 100_000;
        var featureNodes = new ArrayList<JsonNode>();
        fields.path("DeviceFeatures").forEach(featureNodes::add);
        for (JsonNode feature : featureNodes)
        {
            JsonNode output = feature.path("Output").path("HwPositionWithDuration");
            if (!output.isObject())
            {
                continue;
            }
            linearFeatures++;
            if (featureIndex >= 0)
            {
                continue;
            }
            featureIndex = feature.path("FeatureIndex").asInt(0);
            JsonNode value = output.path("Value");
            if (value.isArray() && value.size() == 2)
            {
                valueMin = value.get(0).asInt();
                valueMax = value.get(1).asInt();
            }
            JsonNode duration = output.path("Duration");
            if (duration.isArray() && duration.size() == 2)
            {
                durationMin = duration.get(0).asInt();
                durationMax = duration.get(1).asInt();
            }
        }
        return new Device(index, name, linearFeatures, displayName, featureIndex, valueMin, valueMax, durationMin,
                durationMax);
    }

    private void startPinging(long intervalMs)
    {
        pinger = Executors.newSingleThreadScheduledExecutor(runnable ->
        {
            Thread thread = new Thread(runnable, "intiface-ping");
            thread.setDaemon(true);
            return thread;
        });
        pinger.scheduleAtFixedRate(() ->
        {
            try
            {
                sendWithoutWaiting("Ping", json.createObjectNode());
            }
            catch (IOException e)
            {
                log.debug("[Intiface] Could not ping: {}", e.getMessage());
            }
        }, intervalMs, intervalMs, TimeUnit.MILLISECONDS);
    }

    /** Called when the server closed the connection or it broke. */
    void onClosed(String reason)
    {
        if (open)
        {
            log.warn("[Intiface] Connection to Intiface Central closed: {}", reason);
        }
        shutDown();
    }

    private void shutDown()
    {
        open = false;
        if (pinger != null)
        {
            pinger.shutdownNow();
        }
        var failed = new ArrayList<>(pending.values());
        pending.clear();
        failed.forEach(answer -> answer.completeExceptionally(new IOException("The connection to Intiface Central was closed")));
        devices = Map.of();
    }

    @Override
    public void close()
    {
        boolean wasOpen = open;
        shutDown();
        if (wasOpen && transport != null)
        {
            transport.close();
            log.info("Disconnected from Intiface Central");
        }
    }

    private static final class WebSocketTransport implements Transport
    {
        private final WebSocket webSocket;

        WebSocketTransport(WebSocket webSocket)
        {
            this.webSocket = webSocket;
        }

        /**
         * Synchronized and waits until the message is handed over, because a WebSocket only accepts the next
         * message once the previous one is sent.
         */
        @Override
        public synchronized void send(String text) throws IOException
        {
            try
            {
                webSocket.sendText(text, true).get(REQUEST_TIMEOUT_MS, TimeUnit.MILLISECONDS);
            }
            catch (ExecutionException | TimeoutException e)
            {
                throw new IOException("Could not send to Intiface Central: " + e.getMessage(), e);
            }
            catch (InterruptedException e)
            {
                Thread.currentThread().interrupt();
                throw new IOException("Interrupted while sending to Intiface Central", e);
            }
        }

        @Override
        public void close()
        {
            try
            {
                webSocket.sendClose(WebSocket.NORMAL_CLOSURE, "").get(1, TimeUnit.SECONDS);
            }
            catch (Exception e)
            {
                webSocket.abort();
            }
        }
    }

    /** Collects the parts of each text message and hands complete messages to the connection. */
    private static final class Listener implements WebSocket.Listener
    {
        private final ButtplugConnection connection;
        private final StringBuilder partial = new StringBuilder();

        Listener(ButtplugConnection connection)
        {
            this.connection = connection;
        }

        @Override
        public CompletionStage<?> onText(WebSocket webSocket, CharSequence data, boolean last)
        {
            partial.append(data);
            if (last)
            {
                String text = partial.toString();
                partial.setLength(0);
                if (log.isTraceEnabled())
                {
                    log.trace("[Intiface] Received {}", text);
                }
                connection.onText(text);
            }
            webSocket.request(1);
            return null;
        }

        @Override
        public CompletionStage<?> onClose(WebSocket webSocket, int statusCode, String reason)
        {
            connection.onClosed("status %d %s".formatted(statusCode, reason).trim());
            return null;
        }

        @Override
        public void onError(WebSocket webSocket, Throwable error)
        {
            connection.onClosed(String.valueOf(error.getMessage()));
        }
    }
}
