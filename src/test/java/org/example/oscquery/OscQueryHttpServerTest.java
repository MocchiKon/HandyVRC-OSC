package org.example.oscquery;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.net.DatagramSocket;
import java.net.ServerSocket;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;

import static org.assertj.core.api.Assertions.assertThat;

class OscQueryHttpServerTest
{
    private static final ObjectMapper OBJECT_MAPPER = new ObjectMapper();
    private static final int OSC_PORT = 4711;

    private OscQueryHttpServer server;
    private HttpClient httpClient;

    @BeforeEach
    void setUp() throws IOException
    {
        server = new OscQueryHttpServer(0, "127.0.0.1", OSC_PORT);
        httpClient = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(2)).build();
    }

    @AfterEach
    void tearDown()
    {
        server.close();
    }

    @Test
    void servesTheAvatarPathVrchatSendsDataTo() throws Exception
    {
        JsonNode tree = OBJECT_MAPPER.readTree(get("http://127.0.0.1:" + server.getPort() + "/"));

        assertThat(tree.path("FULL_PATH").asText()).isEqualTo("/");
        assertThat(tree.path("CONTENTS").path("avatar").path("FULL_PATH").asText()).isEqualTo("/avatar");
    }

    @Test
    void servesHostInfoWithTheOscPort() throws Exception
    {
        JsonNode hostInfo = OBJECT_MAPPER.readTree(get("http://127.0.0.1:" + server.getPort() + "/?HOST_INFO"));

        assertThat(hostInfo.path("OSC_PORT").asInt()).isEqualTo(OSC_PORT);
        assertThat(hostInfo.path("OSC_IP").asText()).isEqualTo("127.0.0.1");
        assertThat(hostInfo.path("OSC_TRANSPORT").asText()).isEqualTo("UDP");
        assertThat(hostInfo.path("NAME").asText()).isEqualTo(OscQueryService.SERVICE_NAME);
    }

    @Test
    void answersUnknownPathsWithNotFound() throws Exception
    {
        HttpResponse<String> response = httpClient.send(
                HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + server.getPort() + "/not/there")).build(),
                HttpResponse.BodyHandlers.ofString());

        assertThat(response.statusCode()).isEqualTo(404);
    }

    @Test
    void usesTheGivenPortForHttpSoThatItMatchesTheOscPort() throws IOException
    {
        int freePort = OscQueryService.findFreePort();
        try (var freeServer = new OscQueryHttpServer(freePort, "127.0.0.1", freePort))
        {
            assertThat(freeServer.getPort()).isEqualTo(freePort);
            // The same port number stays available for the UDP OSC listener
            try (var udpSocket = new DatagramSocket(freePort))
            {
                assertThat(udpSocket.getLocalPort()).isEqualTo(freePort);
            }
        }
    }

    @Test
    void findsOnlyPortsThatAreFreeForTcpAndUdp() throws Exception
    {
        int port = OscQueryService.findFreePort();

        try (var tcpSocket = new ServerSocket(port); var udpSocket = new DatagramSocket(port))
        {
            assertThat(tcpSocket.getLocalPort()).isEqualTo(port);
            assertThat(udpSocket.getLocalPort()).isEqualTo(port);
        }
    }

    private String get(String url) throws IOException, InterruptedException
    {
        HttpResponse<String> response = httpClient.send(HttpRequest.newBuilder(URI.create(url))
                .timeout(Duration.ofSeconds(2))
                .GET()
                .build(), HttpResponse.BodyHandlers.ofString());
        assertThat(response.statusCode()).isEqualTo(200);
        return response.body();
    }
}
