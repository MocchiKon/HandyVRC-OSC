package org.example.handy.wifi;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import org.example.config.ConfigProperties;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class HandyWifiClientTest
{
    private static final ObjectMapper MAPPER = new ObjectMapper();

    private HttpServer server;
    private URI baseUri;
    private final List<RecordedRequest> requests = new CopyOnWriteArrayList<>();
    private final Map<String, Response> overrides = new ConcurrentHashMap<>();
    private final AtomicBoolean dropNextConnected = new AtomicBoolean();

    @BeforeEach
    void startServer() throws IOException
    {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/", this::handle);
        server.start();
        baseUri = URI.create("http://127.0.0.1:" + server.getAddress().getPort() + "/");
    }

    @AfterEach
    void stopServer()
    {
        server.stop(0);
    }

    @Test
    void connectsWithTheApplicationIdAndConnectionKey() throws Exception
    {
        try (HandyWifiClient client = (HandyWifiClient) HandyWifiConnector.connect(baseUri, "Key12345", "app-id"))
        {
            assertThat(client.getDeviceIdentifier()).startsWith("wifi:").doesNotContain("Key12345");
        }

        RecordedRequest connected = last("/connected");
        assertThat(connected.method()).isEqualTo("GET");
        assertThat(connected.header("x-api-key")).containsExactly("app-id");
        assertThat(connected.header("x-connection-key")).containsExactly("Key12345");
        assertThat(connected.header("user-agent")).containsExactly("HandyVRC-OSC");
        assertThat(last("/info").header("x-api-key")).containsExactly("app-id");
    }

    @Test
    void blankApplicationIdUsesTheDefault() throws Exception
    {
        try (HandyWifiClient client = (HandyWifiClient) HandyWifiConnector.connect(baseUri, "Key12345", " "))
        {
            assertThat(client).isNotNull();
        }

        assertThat(last("/connected").header("x-api-key"))
                .containsExactly(ConfigProperties.DEFAULT_HANDY_APPLICATION_ID);
    }

    @Test
    void rejectsAMissingOrMalformedConnectionKey()
    {
        assertThatThrownBy(() -> HandyWifiConnector.connect(baseUri, " ", "app-id"))
                .hasMessageContaining("connection key is required");
        assertThatThrownBy(() -> HandyWifiConnector.connect(baseUri, "not a key", "app-id"))
                .hasMessageContaining("not valid");
    }

    @Test
    void reportsNotConnectedWithAnExplanation()
    {
        overrides.put("/connected", new Response(200, "{\"result\":{\"connected\":false}}"));

        assertThatThrownBy(() -> HandyWifiConnector.connect(baseUri, "Key12345", "app-id"))
                .hasMessageContaining("not connected to the Handy servers")
                .hasMessageContaining("connection key");
    }

    @Test
    void reportsARejectedApplicationId() throws Exception
    {
        overrides.put("/connected", new Response(401, "{\"error\":{\"name\":\"\",\"message\":\"Unauthenticated\"}}"));

        assertThatThrownBy(() -> HandyWifiConnector.connect(baseUri, "Key12345", "broken"))
                .hasMessageContaining("application ID was rejected")
                .hasMessageContaining("Wi-Fi settings");
    }

    @Test
    void sendsHdspCommandsWithTheExpectedBody() throws Exception
    {
        try (HandyWifiClient client = connectedClient())
        {
            client.hdspXpt(0.25f, 120, true);
            awaitRequests("/hdsp/xpt", 1, 2_000);
            client.hdspStop();

            RecordedRequest xpt = last("/hdsp/xpt");
            JsonNode body = MAPPER.readTree(xpt.body());
            // Despite the API docs calling xp a percentage, the Wi-Fi API actually takes 0.0-1.0
            // (0 = bottom, 1 = top), so the normalized position must not be multiplied by 100.
            assertThat(body.path("xp").asDouble()).isEqualTo(0.25);
            assertThat(body.path("t").asInt()).isEqualTo(120);
            assertThat(body.path("stop_on_target").asBoolean()).isTrue();
            assertThat(body.path("immediate_rsp").asBoolean()).isTrue();
            assertThat(last("/hdsp/stop").method()).isEqualTo("PUT");
        }
    }

    @Test
    void readsTheStrokeRangeAndSliderState() throws Exception
    {
        try (HandyWifiClient client = connectedClient())
        {
            assertThat(client.getSliderSettings()).hasValueSatisfying(settings ->
            {
                assertThat(settings.min()).isEqualTo("0.0");
                assertThat(settings.max()).isEqualTo("1.0");
                assertThat(settings.minMm()).isEqualTo(10f);
                assertThat(settings.maxMm()).isEqualTo(100f);
                assertThat(settings.strokeMm()).isEqualTo(90f);
            });
            assertThat(client.getSliderState()).hasValueSatisfying(state ->
            {
                assertThat(state.position()).isEqualTo(0.5f);
                assertThat(state.positionMm()).isEqualTo(55f);
                assertThat(state.speedMmPerSecond()).isEqualTo(100f);
            });
        }
    }

    @Test
    void acceptsTheUppercaseResultSomeDeviceEndpointsReturn() throws Exception
    {
        overrides.put("/slider/stroke", new Response(200,
                "{\"Result\":{\"min\":0.1,\"max\":0.9,\"min_absolute\":10.0,\"max_absolute\":100.0}}"));

        try (HandyWifiClient client = connectedClient())
        {
            assertThat(client.getSliderSettings()).hasValueSatisfying(settings ->
            {
                assertThat(settings.min()).isEqualTo("0.1");
                assertThat(settings.max()).isEqualTo("0.9");
            });
        }
    }

    @Test
    void returnsTheDeviceErrorWhenTheModeCannotBeChanged() throws Exception
    {
        overrides.put("/mode2", new Response(200,
                "{\"error\":{\"code\":1001,\"name\":\"DeviceNotConnected\",\"message\":\"Device not connected\","
                        + "\"connected\":false}}"));

        try (HandyWifiClient client = connectedClient())
        {
            var response = client.changeMode(org.example.handy.common.DeviceMode.HDSP);

            assertThat(response.error()).isNotNull();
            assertThat(response.error().code()).isEqualTo(1001);
            assertThat(response.error().message()).isEqualTo("Device not connected");
        }
    }

    @Test
    void doesNotSendMovementAfterStopUntilTheNextModeChange() throws Exception
    {
        try (HandyWifiClient client = connectedClient())
        {
            client.hdspXpt(0.2f, 100, false);
            client.hdspStop();
            int afterStop = count("/hdsp/xpt");

            // The processor may still produce one last value while it shuts down; it must not move the device.
            client.hdspXpt(0.9f, 100, false);
            Thread.sleep(200);
            assertThat(count("/hdsp/xpt")).isEqualTo(afterStop);

            // A new session puts the device back into HDSP and starts sending again.
            client.changeMode(org.example.handy.common.DeviceMode.HDSP);
            client.hdspXpt(0.8f, 100, false);
            awaitRequests("/hdsp/xpt", afterStop + 1, 2_000);
            assertThat(count("/hdsp/xpt")).isEqualTo(afterStop + 1);
        }
    }

    @Test
    void retriesARequestAfterTheConnectionWasDropped() throws Exception
    {
        dropNextConnected.set(true);

        try (HandyWifiClient client = connectedClient())
        {
            assertThat(client.checkConnectionStatus()).isTrue();
        }

        assertThat(requests.stream().filter(request -> request.path().equals("/connected")).count())
                .isGreaterThanOrEqualTo(3);
    }

    @Test
    void rotatesTheConnectionBeforeTheServerLimit() throws Exception
    {
        AtomicInteger created = new AtomicInteger();
        HttpResponse.BodyHandler<Void> discarding = HttpResponse.BodyHandlers.discarding();
        HttpRequest warmUp = HttpRequest.newBuilder(baseUri.resolve("servertime")).GET().build();
        try (HandyHttpPool pool = new HandyHttpPool(baseUri, () ->
        {
            created.incrementAndGet();
            return HttpClient.newBuilder().version(HttpClient.Version.HTTP_2).build();
        }, 3, false))
        {
            for (int i = 0; i < 6; i++)
            {
                HandyHttpPool.Slot slot = pool.acquire();
                try
                {
                    slot.client.send(warmUp, discarding);
                }
                finally
                {
                    pool.release(slot);
                }
            }

            assertThat(pool.rotations()).isGreaterThanOrEqualTo(2);
            assertThat(created.get()).isGreaterThanOrEqualTo(3);
        }
    }

    private HandyWifiClient connectedClient() throws IOException
    {
        return (HandyWifiClient) HandyWifiConnector.connect(baseUri, "Key12345", "app-id");
    }

    private int count(String path)
    {
        int count = 0;
        for (RecordedRequest request : requests)
        {
            if (request.path().equals(path))
            {
                count++;
            }
        }
        return count;
    }

    private void awaitRequests(String path, int expected, long timeoutMs) throws InterruptedException
    {
        long deadline = System.nanoTime() + timeoutMs * 1_000_000L;
        while (count(path) < expected && System.nanoTime() < deadline)
        {
            Thread.sleep(10);
        }
    }

    private RecordedRequest last(String path)
    {
        List<RecordedRequest> matching = new ArrayList<>();
        for (RecordedRequest request : requests)
        {
            if (request.path().equals(path))
            {
                matching.add(request);
            }
        }
        assertThat(matching).as("requests to %s", path).isNotEmpty();
        return matching.getLast();
    }

    private void handle(HttpExchange exchange) throws IOException
    {
        String path = exchange.getRequestURI().getPath();
        String body = new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8);
        Map<String, List<String>> headers = new HashMap<>();
        exchange.getRequestHeaders().forEach((name, values) ->
                headers.put(name.toLowerCase(Locale.ROOT), List.copyOf(values)));
        requests.add(new RecordedRequest(exchange.getRequestMethod(), path, headers, body));

        if (path.equals("/connected") && dropNextConnected.compareAndSet(true, false))
        {
            exchange.close();
            return;
        }

        Response override = overrides.get(path);
        Response response = override != null ? override : defaultResponse(path, exchange.getRequestMethod());
        byte[] bytes = response.body().getBytes(StandardCharsets.UTF_8);
        exchange.getResponseHeaders().add("Content-Type", "application/json");
        exchange.sendResponseHeaders(response.status(), bytes.length);
        exchange.getResponseBody().write(bytes);
        exchange.close();
    }

    private static Response defaultResponse(String path, String method)
    {
        return switch (path)
        {
            case "/servertime" -> new Response(200, "{\"server_time\":1}");
            case "/connected" -> new Response(200, "{\"result\":{\"connected\":true}}");
            case "/info" -> new Response(200, "{\"result\":{\"fw_version\":\"4.0.0\",\"hw_model_name\":\"H01\"}}");
            case "/mode2" -> new Response(200, "{\"result\":{\"mode\":2,\"mode_session_id\":1}}");
            case "/hdsp/xpt", "/hdsp/stop" -> new Response(200, "{\"result\":\"ok\"}");
            case "/slider/stroke" -> method.equals("GET")
                    ? new Response(200, "{\"result\":{\"min\":0.0,\"max\":1.0,\"min_absolute\":10.0,"
                    + "\"max_absolute\":100.0}}")
                    : new Response(200, "{\"result\":{\"min\":0.2,\"max\":0.8,\"min_absolute\":10.0,"
                    + "\"max_absolute\":100.0}}");
            case "/slider/state" -> new Response(200, "{\"result\":{\"position\":0.5,"
                    + "\"position_absolute\":55.0,\"speed_absolute\":100.0}}");
            default -> new Response(404, "{\"error\":{\"code\":404,\"name\":\"NotFound\","
                    + "\"message\":\"not found\",\"connected\":false}}");
        };
    }

    private record Response(int status, String body)
    {
    }

    private record RecordedRequest(String method, String path, Map<String, List<String>> headers, String body)
    {
        List<String> header(String name)
        {
            return headers.getOrDefault(name.toLowerCase(Locale.ROOT), Collections.emptyList());
        }
    }
}
