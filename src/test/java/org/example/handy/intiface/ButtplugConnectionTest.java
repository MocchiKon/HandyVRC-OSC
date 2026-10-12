package org.example.handy.intiface;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.function.Function;

import static org.assertj.core.api.Assertions.*;

class ButtplugConnectionTest
{
    private static final ObjectMapper JSON = new ObjectMapper();
    private static final String STROKER = """
            {"DeviceName":"The Handy","DeviceIndex":3,"DeviceMessages":{
              "LinearCmd":[{"FeatureDescriptor":"","StepCount":100,"ActuatorType":"Position"}],
              "StopDeviceCmd":{}}}""";
    private static final String VIBRATOR = """
            {"DeviceName":"Some vibrator","DeviceIndex":1,"DeviceMessages":{
              "ScalarCmd":[{"FeatureDescriptor":"","StepCount":20,"ActuatorType":"Vibrate"}],
              "StopDeviceCmd":{}}}""";

    private final ButtplugConnection connection = new ButtplugConnection();
    private final FakeServer server = new FakeServer(connection);

    @Test
    void introducesItselfWithMessageVersion3() throws IOException
    {
        server.answer = request -> "[{\"ServerInfo\":{\"Id\":%d,\"ServerName\":\"Intiface\",\"MessageVersion\":3,\"MaxPingTime\":0}}]"
                .formatted(request.path("Id").asInt());

        connection.handshake("HandyVRC-OSC");

        JsonNode request = server.sent("RequestServerInfo");
        assertThat(request.path("Id").asInt()).isPositive();
        assertThat(request.path("ClientName").asText()).isEqualTo("HandyVRC-OSC");
        assertThat(request.path("MessageVersion").asInt()).isEqualTo(3);
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
        server.answer = request -> "[{\"DeviceList\":{\"Id\":%d,\"Devices\":[%s]}}]".formatted(request.path("Id").asInt(), VIBRATOR);
        connection.refreshDeviceList();
        connection.onText("[{\"DeviceAdded\":%s}]".formatted(STROKER));

        assertThat(connection.devices()).containsExactlyInAnyOrder(
                new ButtplugConnection.Device(1, "Some vibrator", 0),
                new ButtplugConnection.Device(3, "The Handy", 1));

        connection.onText("[{\"DeviceRemoved\":{\"Id\":0,\"DeviceIndex\":3}}]");

        assertThat(connection.hasDevice(3)).isFalse();
        assertThat(connection.hasDevice(1)).isTrue();
    }

    @Test
    void sendsMovesAsLinearCommands() throws IOException
    {
        var client = new HandyClientIntiface(connection, new ButtplugConnection.Device(3, "The Handy", 1));

        assertThat(client.hdspXpt(0.25f, 120, true).error()).isNull();
        client.hdspStop();

        JsonNode linear = server.sent("LinearCmd");
        assertThat(linear.path("DeviceIndex").asInt()).isEqualTo(3);
        assertThat(linear.path("Vectors")).hasSize(1);
        assertThat(linear.path("Vectors").get(0).path("Index").asInt()).isZero();
        assertThat(linear.path("Vectors").get(0).path("Duration").asInt()).isEqualTo(120);
        assertThat(linear.path("Vectors").get(0).path("Position").asDouble()).isCloseTo(0.25, within(1e-6));
        assertThat(server.sent("StopDeviceCmd").path("DeviceIndex").asInt()).isEqualTo(3);
    }

    @Test
    void mapsPositionsIntoTheStrokeRange() throws IOException
    {
        var client = new HandyClientIntiface(connection, new ButtplugConnection.Device(0, "The Handy", 1));
        client.setSliderSettings(0.2f, 0.6f);

        client.hdspXpt(0.5f, 50, false);

        assertThat(server.sent("LinearCmd").path("Vectors").get(0).path("Position").asDouble()).isCloseTo(0.4, within(1e-6));
        assertThat(HandyClientIntiface.toDevicePosition(0f, 0.2f, 0.6f)).isCloseTo(0.2, within(1e-6));
        assertThat(HandyClientIntiface.toDevicePosition(1.5f, 0.2f, 0.6f)).isCloseTo(0.6, within(1e-6));
    }

    @Test
    void reportsTheConnectionAsLostWhenTheDeviceOrTheServerGoesAway()
    {
        connection.onText("[{\"DeviceAdded\":%s}]".formatted(STROKER));
        var client = new HandyClientIntiface(connection, new ButtplugConnection.Device(3, "The Handy", 1));
        assertThat(client.checkConnectionStatus()).isTrue();

        connection.onText("[{\"DeviceRemoved\":{\"Id\":0,\"DeviceIndex\":3}}]");
        assertThat(client.checkConnectionStatus()).isFalse();

        connection.onText("[{\"DeviceAdded\":%s}]".formatted(STROKER));
        connection.onClosed("server stopped");
        assertThat(client.checkConnectionStatus()).isFalse();
        assertThat(client.hdspXpt(0.5f, 50, false).error()).isNotNull();
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
            return sent.reversed().stream()
                    .filter(message -> message.has(type))
                    .map(message -> message.get(type))
                    .findFirst()
                    .orElseThrow(() -> new AssertionError("No " + type + " sent, sent: " + sent));
        }
    }
}
