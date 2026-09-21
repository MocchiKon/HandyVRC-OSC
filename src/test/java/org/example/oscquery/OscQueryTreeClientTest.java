package org.example.oscquery;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.io.OutputStream;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class OscQueryTreeClientTest
{
    /** Trimmed down version of the tree VRChat serves over OSCQuery. */
    private static final String VRCHAT_TREE = """
            {
              "FULL_PATH": "/",
              "ACCESS": 0,
              "CONTENTS": {
                "avatar": {
                  "FULL_PATH": "/avatar",
                  "ACCESS": 0,
                  "CONTENTS": {
                    "change": {"FULL_PATH": "/avatar/change", "TYPE": "s", "ACCESS": 1, "VALUE": [""]},
                    "parameters": {
                      "FULL_PATH": "/avatar/parameters",
                      "ACCESS": 0,
                      "CONTENTS": {
                        "MuteSelf": {"FULL_PATH": "/avatar/parameters/MuteSelf", "TYPE": "T", "ACCESS": 3, "VALUE": [false]},
                        "OGB": {
                          "FULL_PATH": "/avatar/parameters/OGB",
                          "ACCESS": 0,
                          "CONTENTS": {
                            "Orf": {
                              "FULL_PATH": "/avatar/parameters/OGB/Orf",
                              "ACCESS": 0,
                              "CONTENTS": {
                                "Blowjob": {
                                  "FULL_PATH": "/avatar/parameters/OGB/Orf/Blowjob",
                                  "ACCESS": 0,
                                  "CONTENTS": {
                                    "PenOthersNewRoot": {"FULL_PATH": "/avatar/parameters/OGB/Orf/Blowjob/PenOthersNewRoot", "TYPE": "f", "ACCESS": 1},
                                    "PenOthersNewTip": {"FULL_PATH": "/avatar/parameters/OGB/Orf/Blowjob/PenOthersNewTip", "TYPE": "f", "ACCESS": 1}
                                  }
                                }
                              }
                            }
                          }
                        }
                      }
                    }
                  }
                },
                "tracking": {"FULL_PATH": "/tracking", "ACCESS": 0, "CONTENTS": {
                  "vrsystem": {"FULL_PATH": "/tracking/vrsystem", "ACCESS": 0}
                }}
              }
            }
            """;

    private HttpServer httpServer;
    private int port;

    @BeforeEach
    void setUp() throws IOException
    {
        httpServer = HttpServer.create(new InetSocketAddress(InetAddress.getLoopbackAddress(), 0), 0);
        port = httpServer.getAddress().getPort();
        httpServer.createContext("/", exchange ->
        {
            byte[] body = VRCHAT_TREE.getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().add("Content-Type", "application/json");
            exchange.sendResponseHeaders(200, body.length);
            try (OutputStream out = exchange.getResponseBody())
            {
                out.write(body);
            }
        });
        httpServer.start();
    }

    @AfterEach
    void tearDown()
    {
        httpServer.stop(0);
    }

    @Test
    void readsAvatarParametersFromAService() throws Exception
    {
        var client = new OscQueryTreeClient(Duration.ofSeconds(2));

        List<OscQueryNode> parameters = client.fetchAvatarParameters(InetAddress.getLoopbackAddress(), port);

        assertThat(parameters).extracting(OscQueryNode::path).containsExactlyInAnyOrder(
                "/avatar/parameters/MuteSelf",
                "/avatar/parameters/OGB/Orf/Blowjob/PenOthersNewRoot",
                "/avatar/parameters/OGB/Orf/Blowjob/PenOthersNewTip");
        assertThat(parameters)
                .filteredOn(node -> node.path().endsWith("PenOthersNewRoot"))
                .singleElement()
                .satisfies(node ->
                {
                    assertThat(node.type()).isEqualTo("f");
                    assertThat(node.access()).isEqualTo(1);
                });
    }

    @Test
    void parsesParametersWithoutFullPathFromTheirParentNode() throws IOException
    {
        JsonNode tree = new ObjectMapper().readTree("""
                {"FULL_PATH": "/", "CONTENTS": {"avatar": {"CONTENTS": {"parameters": {"CONTENTS": {
                  "MyParam": {"TYPE": "f", "ACCESS": 3}}}}}}}
                """);

        assertThat(OscQueryTreeClient.parseAvatarParameters(tree))
                .extracting(OscQueryNode::path)
                .containsExactly("/avatar/parameters/MyParam");
    }

    @Test
    void ignoresEverythingOutsideOfAvatarParameters() throws IOException
    {
        JsonNode tree = new ObjectMapper().readTree(VRCHAT_TREE);

        assertThat(OscQueryTreeClient.parseAvatarParameters(tree))
                .extracting(OscQueryNode::path)
                .allMatch(path -> path.startsWith("/avatar/parameters/"));
    }

    @Test
    void returnsNoParametersWhenTheServiceCannotBeReached()
    {
        var client = new OscQueryTreeClient(Duration.ofMillis(500));
        var unreachable = new OscQueryServiceProfile("VRChat-Client-Test",
                List.of(InetAddress.getLoopbackAddress()), 1); // port 1 is never an OSCQuery service

        assertThat(client.fetchAvatarParameters(unreachable)).isEmpty();
    }
}
