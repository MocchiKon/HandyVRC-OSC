package org.example.handy.intiface;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.net.URI;
import java.util.ArrayList;
import java.util.List;
import java.util.function.Function;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class IntifaceDeviceConnectorTest
{
    private static final ObjectMapper JSON = new ObjectMapper();
    private static final String HANDY = """
            {"DeviceName":"The Handy 2 Pro","DeviceIndex":3,"DeviceMessageTimingGap":100,
             "DeviceFeatures":{"0":{"FeatureDescription":"","FeatureIndex":0,
               "Output":{"HwPositionWithDuration":{"Value":[0,100],"Duration":[0,100000]}}}}}""";
    private static final String SOLACE = """
            {"DeviceName":"Lovense Solace","DeviceIndex":1,"DeviceMessageTimingGap":100,
             "DeviceFeatures":{"0":{"FeatureDescription":"","FeatureIndex":0,
               "Output":{"HwPositionWithDuration":{"Value":[0,100],"Duration":[0,100000]}}}}}""";
    private static final String VIBRATOR = """
            {"DeviceName":"Some vibrator","DeviceIndex":2,"DeviceMessageTimingGap":100,
             "DeviceFeatures":{"0":{"FeatureDescription":"","FeatureIndex":0,
               "Output":{"Vibrate":{"Value":[0,20]}}}}}""";
    /** Simulated devices can share the device name, only the index tells them apart. */
    private static final String SIMULATED_1 = """
            {"DeviceName":"Simulated Stroker","DeviceDisplayName":"MyTest1","DeviceIndex":3,
             "DeviceMessageTimingGap":100,
             "DeviceFeatures":{"0":{"FeatureDescription":"Linear Axis","FeatureIndex":0,
               "Output":{"HwPositionWithDuration":{"Value":[0,1000],"Duration":[0,100000]}}}}}""";
    private static final String SIMULATED_2 = """
            {"DeviceName":"Simulated Stroker","DeviceDisplayName":"MyTest2","DeviceIndex":2,
             "DeviceMessageTimingGap":100,
             "DeviceFeatures":{"0":{"FeatureDescription":"Linear Axis","FeatureIndex":0,
               "Output":{"HwPositionWithDuration":{"Value":[0,1000],"Duration":[0,100000]}}}}}""";

    private final ButtplugConnection connection = new ButtplugConnection();
    private final FakeServer server = new FakeServer(connection);

    @Test
    void recognizesTheHandyModelNames()
    {
        assertThat(IntifaceDeviceConnector.isHandy("The Handy")).isTrue();
        assertThat(IntifaceDeviceConnector.isHandy("The Handy 2 Standard")).isTrue();
        assertThat(IntifaceDeviceConnector.isHandy("The Handy 2 Pro")).isTrue();
        assertThat(IntifaceDeviceConnector.isHandy("Lovense Solace")).isFalse();
        assertThat(IntifaceDeviceConnector.isHandy("The Oh!")).isFalse();
        assertThat(IntifaceDeviceConnector.isHandy(null)).isFalse();
        assertThat(IntifaceDeviceConnector.isHandy("")).isFalse();
    }

    @Test
    void findsTheSelectedDeviceByItsName() throws IOException
    {
        answerWithDevices("\"3\":" + HANDY, "\"1\":" + SOLACE);

        var device = IntifaceDeviceConnector.findDevice(connection, "Lovense Solace");

        assertThat(device.name()).isEqualTo("Lovense Solace");
        assertThat(device.index()).isEqualTo(1);
    }

    @Test
    void usesTheFirstUsableDeviceByIndexWhenNothingIsSelected() throws IOException
    {
        answerWithDevices("\"3\":" + HANDY, "\"1\":" + SOLACE);

        var device = IntifaceDeviceConnector.findDevice(connection, "");

        assertThat(device.name()).isEqualTo("Lovense Solace");
    }

    @Test
    void reusesTheConnectionTheAppAlreadyHolds() throws IOException
    {
        URI uri = URI.create("ws://127.0.0.1:12345");
        var existingConnection = new ButtplugConnection(uri);
        var existingServer = new FakeServer(existingConnection);
        existingServer.answer = request -> "[{\"DeviceList\":{\"Id\":%d,\"Devices\":{\"3\":%s}}}]"
                .formatted(request.path("Id").asInt(), HANDY);
        var client = new GenericLinearDevice(existingConnection,
                new ButtplugConnection.Device(3, "The Handy 2 Pro", 1));

        var devices = IntifaceDeviceConnector.discover(client, uri);

        assertThat(devices).extracting(ButtplugConnection.Device::index).containsExactly(3);
        // The device list was requested on the existing connection; no new client was opened for it.
        assertThat(existingServer.sent).anyMatch(message -> message.has("RequestDeviceList"));
    }

    @Test
    void listsTheDevicesOnAnExistingConnectionWithoutScanningAgain() throws IOException
    {
        server.answer = request -> "[{\"DeviceList\":{\"Id\":%d,\"Devices\":{\"3\":%s}}}]"
                .formatted(request.path("Id").asInt(), HANDY);

        var devices = IntifaceDeviceConnector.listDevices(connection);

        assertThat(devices).extracting(ButtplugConnection.Device::index).containsExactly(3);
        assertThat(server.sent).noneMatch(message -> message.has("StartScanning"));
    }

    @Test
    void ignoresDevicesWithoutPositionControlWhenNothingIsSelected() throws IOException
    {
        answerWithDevices("\"2\":" + VIBRATOR, "\"1\":" + SOLACE);

        var device = IntifaceDeviceConnector.findDevice(connection, null);

        assertThat(device.name()).isEqualTo("Lovense Solace");
    }

    @Test
    void usesTheIndexToTellApartDevicesThatHaveTheSameName() throws IOException
    {
        answerWithDevices("\"2\":" + SIMULATED_2, "\"3\":" + SIMULATED_1);

        // Both devices are called 'Simulated Stroker'; only the index can pick the second one.
        var device = IntifaceDeviceConnector.findDevice(connection, 2, "Simulated Stroker");

        assertThat(device.index()).isEqualTo(2);
        assertThat(device.displayLabel()).isEqualTo("MyTest2 (Simulated Stroker)");
    }

    @Test
    void findsTheSelectedDeviceByItsDisplayLabelWhenTheIndexChanged() throws IOException
    {
        answerWithDevices("\"2\":" + SIMULATED_2, "\"3\":" + SIMULATED_1);

        // The device reconnected and got another index: the display label still finds it.
        var device = IntifaceDeviceConnector.findDevice(connection, 7, "MyTest1 (Simulated Stroker)");

        assertThat(device.index()).isEqualTo(3);
    }

    @Test
    void findsTheSelectedDeviceByItsDisplayLabelWhenTheIndexIsUnknown() throws IOException
    {
        answerWithDevices("\"2\":" + SIMULATED_2, "\"3\":" + SIMULATED_1);

        var device = IntifaceDeviceConnector.findDevice(connection, "MyTest1 (Simulated Stroker)");

        assertThat(device.index()).isEqualTo(3);
    }

    @Test
    void reportsASelectedDeviceThatCannotBeMovedWithoutScanning() throws IOException
    {
        answerWithDevices("\"2\":" + VIBRATOR);

        assertThatThrownBy(() -> IntifaceDeviceConnector.findDevice(connection, "Some vibrator"))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("cannot be moved to a position");
        // Only the device list was requested; a scan would take the full search timeout
        assertThat(server.sent).noneMatch(message -> message.has("StartScanning"));
    }

    private void answerWithDevices(String... devices) throws IOException
    {
        server.answer = request -> "[{\"DeviceList\":{\"Id\":%d,\"Devices\":{%s}}}]"
                .formatted(request.path("Id").asInt(), String.join(",", devices));
        connection.refreshDeviceList();
    }

    /** Records what the client sends and answers the requests the test wants. */
    private static final class FakeServer implements ButtplugConnection.Transport
    {
        private final ButtplugConnection connection;
        private final List<JsonNode> sent = new ArrayList<>();
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
            JsonNode fields = message.elements().next();
            String reply = answer.apply(fields);
            if (reply != null)
            {
                connection.onText(reply);
            }
        }

        @Override
        public void close()
        {
        }
    }
}
