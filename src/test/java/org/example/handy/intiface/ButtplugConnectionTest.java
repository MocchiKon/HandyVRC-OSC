package org.example.handy.intiface;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.function.Function;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class ButtplugConnectionTest
{
    private static final ObjectMapper JSON = new ObjectMapper();
    private static final String STROKER = """
            {"DeviceName":"The Handy","DeviceIndex":3,"DeviceMessageTimingGap":100,
             "DeviceFeatures":{"0":{"FeatureDescription":"","FeatureIndex":0,
               "Output":{"HwPositionWithDuration":{"Value":[0,100],"Duration":[0,100000]}}}}}""";
    private static final String VIBRATOR = """
            {"DeviceName":"Some vibrator","DeviceIndex":1,"DeviceMessageTimingGap":100,
             "DeviceFeatures":{"0":{"FeatureDescription":"","FeatureIndex":0,
               "Output":{"Vibrate":{"Value":[0,20]}}}}}""";

    private final ButtplugConnection connection = new ButtplugConnection();
    private final FakeServer server = new FakeServer(connection);

    @Test
    void introducesItselfWithProtocolVersion4() throws IOException
    {
        server.answer = request -> "[{\"ServerInfo\":{\"Id\":%d,\"ServerName\":\"Intiface\","
                .formatted(request.path("Id").asInt())
                + "\"ProtocolVersionMajor\":4,\"ProtocolVersionMinor\":0,\"MaxPingTime\":0}}]";

        connection.handshake("HandyVRC-OSC");

        JsonNode request = server.sent("RequestServerInfo");
        assertThat(request.path("Id").asInt()).isPositive();
        assertThat(request.path("ClientName").asText()).isEqualTo("HandyVRC-OSC");
        assertThat(request.path("ProtocolVersionMajor").asInt()).isEqualTo(4);
        assertThat(request.path("ProtocolVersionMinor").asInt()).isZero();
    }

    @Test
    void rejectsAServerThatDoesNotSupportProtocolVersion4()
    {
        server.answer = request -> "[{\"ServerInfo\":{\"Id\":%d,\"ServerName\":\"Intiface\","
                .formatted(request.path("Id").asInt())
                + "\"ProtocolVersionMajor\":3,\"ProtocolVersionMinor\":0,\"MaxPingTime\":0}}]";

        assertThatThrownBy(() -> connection.handshake("HandyVRC-OSC"))
                .hasMessageContaining("protocol v3.0")
                .hasMessageContaining("needs v4");
    }

    @Test
    void errorAnswerFailsTheRequest()
    {
        server.answer = request -> "[{\"Error\":{\"Id\":%d,\"ErrorMessage\":\"Version mismatch\",\"ErrorCode\":1}}]"
                .formatted(request.path("Id").asInt());

        assertThatThrownBy(() -> connection.handshake("HandyVRC-OSC")).hasMessageContaining("Version mismatch");
    }

    @Test
    void tracksDevicesFromTheDeviceListAndEvents() throws IOException
    {
        server.answer = request -> deviceList(request.path("Id").asInt(), "\"1\":" + VIBRATOR, "\"3\":" + STROKER);
        connection.refreshDeviceList();

        assertThat(connection.devices()).containsExactlyInAnyOrder(
                new ButtplugConnection.Device(1, "Some vibrator", 0),
                new ButtplugConnection.Device(3, "The Handy", 1));

        // Device changes arrive as complete DeviceList events in protocol v4
        connection.onText("[{\"DeviceList\":{\"Id\":0,\"Devices\":{\"3\":%s}}}]".formatted(STROKER));

        assertThat(connection.hasDevice(3)).isTrue();
        assertThat(connection.hasDevice(1)).isFalse();
    }

    @Test
    void readsTheDisplayNameAndPositionRangeOfADevice() throws IOException
    {
        String device = """
                {"DeviceName":"The Handy 2 Pro","DeviceDisplayName":"My Handy","DeviceIndex":4,
                 "DeviceMessageTimingGap":100,
                 "DeviceFeatures":{"0":{"FeatureDescription":"","FeatureIndex":0,
                   "Output":{"HwPositionWithDuration":{"Value":[0,15],"Duration":[10,100000]}}}}}""";
        server.answer = request -> deviceList(request.path("Id").asInt(), "\"4\":" + device);

        connection.refreshDeviceList();

        ButtplugConnection.Device parsed = connection.devices().iterator().next();
        assertThat(parsed.displayLabel()).isEqualTo("My Handy (The Handy 2 Pro)");
        assertThat(parsed.featureIndex()).isZero();
        assertThat(parsed.valueMin()).isZero();
        assertThat(parsed.valueMax()).isEqualTo(15);
        assertThat(parsed.positionValue(0.5)).isEqualTo(8);
        assertThat(parsed.positionValue(1.0)).isEqualTo(15);
        assertThat(parsed.durationMs(1)).isEqualTo(10);
        assertThat(parsed.durationMs(200_000)).isEqualTo(100_000);
    }

    @Test
    void sendsMovesAsHwPositionWithDurationCommands() throws IOException
    {
        var device = new ButtplugConnection.Device(3, "The Handy", 1);
        var client = new GenericLinearDevice(connection, device);

        assertThat(client.hdspXpt(0.25f, 120, true).error()).isNull();
        client.hdspStop();

        JsonNode output = server.sent("OutputCmd");
        assertThat(output.path("DeviceIndex").asInt()).isEqualTo(3);
        assertThat(output.path("FeatureIndex").asInt()).isZero();
        JsonNode move = output.path("Command").path("HwPositionWithDuration");
        assertThat(move.path("Value").asInt()).isEqualTo(25);
        assertThat(move.path("Duration").asInt()).isEqualTo(120);
        assertThat(server.sent("StopCmd").path("DeviceIndex").asInt()).isEqualTo(3);
    }

    @Test
    void leavesTheDeviceOnItsFullStrokeBecauseIntifaceHasNoStrokeRange() throws IOException
    {
        var client = new GenericLinearDevice(connection, new ButtplugConnection.Device(0, "The Handy", 1));
        assertThat(client.supportsSliderSettings()).isFalse();
        assertThat(client.supportsSliderState()).isFalse();
        assertThat(client.getSliderSettings()).isEmpty();

        // Even a stroke range from the settings must not shrink the positions: Buttplug has no stroke zone, and the
        // app must not silently enforce one by mapping the positions into it.
        client.setSliderSettings(0.2f, 0.6f);
        client.hdspXpt(0.5f, 50, false);

        JsonNode move = server.sent("OutputCmd").path("Command").path("HwPositionWithDuration");
        assertThat(move.path("Value").asInt()).isEqualTo(50);
    }

    @Test
    void mapsPositionsToTheRangeTheDeviceAdvertises() throws IOException
    {
        var device = new ButtplugConnection.Device(3, "The Handy", 1, "", 0, 0, 15, 0, 100_000);
        var client = new GenericLinearDevice(connection, device);

        client.hdspXpt(0.5f, 50, false);
        client.hdspXpt(1f, 50, false);
        client.hdspXpt(0f, 50, false);

        var moves = server.sentAll("OutputCmd");
        assertThat(moves).hasSize(3);
        assertThat(moves.get(0).path("Command").path("HwPositionWithDuration").path("Value").asInt()).isEqualTo(8);
        assertThat(moves.get(1).path("Command").path("HwPositionWithDuration").path("Value").asInt()).isEqualTo(15);
        assertThat(moves.get(2).path("Command").path("HwPositionWithDuration").path("Value").asInt()).isZero();
    }

    @Test
    void reportsTheConnectionAsLostWhenTheDeviceOrTheServerGoesAway()
    {
        connection.onText("[{\"DeviceList\":{\"Id\":0,\"Devices\":{\"3\":%s}}}]".formatted(STROKER));
        var client = new GenericLinearDevice(connection, new ButtplugConnection.Device(3, "The Handy", 1));
        assertThat(client.checkConnectionStatus()).isTrue();

        connection.onText("[{\"DeviceList\":{\"Id\":0,\"Devices\":{}}}]");
        assertThat(client.checkConnectionStatus()).isFalse();

        connection.onText("[{\"DeviceList\":{\"Id\":0,\"Devices\":{\"3\":%s}}}]".formatted(STROKER));
        connection.onClosed("server stopped");
        assertThat(client.checkConnectionStatus()).isFalse();
        assertThat(client.hdspXpt(0.5f, 50, false).error()).isNotNull();
    }

    private static String deviceList(long id, String... devices)
    {
        return "[{\"DeviceList\":{\"Id\":%d,\"Devices\":{%s}}}]".formatted(id, String.join(",", devices));
    }

    /** Records what the client sends and answers requests the way the test wants. */
    private static final class FakeServer implements ButtplugConnection.Transport
    {
        private final ButtplugConnection connection;
        private final List<JsonNode> sent = new ArrayList<>();
        /** Answer to a request (its fields), null for no answer. */
        Function<JsonNode, String> answer = request -> null;

        FakeServer(ButtplugConnection connection)
        {
            this.connection = connection;
            connection.setTransport(this);
        }

        @Override
        public void send(String text) throws IOException
        {
            JsonNode message = JSON.readTree(text).get(0);
            sent.add(message);
            String reply = answer.apply(message.elements().next());
            if (reply != null)
            {
                connection.onText(reply);
            }
        }

        @Override
        public void close()
        {
        }

        /** @return the fields of the last sent message of this type */
        JsonNode sent(String type)
        {
            return sentAll(type).getLast();
        }

        /** @return the fields of every sent message of this type */
        List<JsonNode> sentAll(String type)
        {
            return sent.stream()
                    .filter(message -> message.has(type))
                    .map(message -> message.get(type))
                    .toList();
        }
    }
}
