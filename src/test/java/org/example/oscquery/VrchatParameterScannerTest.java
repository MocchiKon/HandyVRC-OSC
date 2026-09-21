package org.example.oscquery;

import com.sun.net.httpserver.HttpServer;
import org.example.config.ConfigProperties;
import org.example.processor.ParameterProcessorType;
import org.example.processor.SpsType;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.io.OutputStream;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;

import static org.assertj.core.api.Assertions.assertThat;

class VrchatParameterScannerTest
{
    private static final String ONE_PENETRATOR = """
            {"FULL_PATH":"/","ACCESS":0,"CONTENTS":{"avatar":{"FULL_PATH":"/avatar","ACCESS":0,"CONTENTS":{"parameters":{"FULL_PATH":"/avatar/parameters","ACCESS":0,"CONTENTS":{"OGB":{"FULL_PATH":"/avatar/parameters/OGB","ACCESS":0,"CONTENTS":{"Orf":{"FULL_PATH":"/avatar/parameters/OGB/Orf","ACCESS":0,"CONTENTS":{"Blowjob":{"FULL_PATH":"/avatar/parameters/OGB/Orf/Blowjob","ACCESS":0,"CONTENTS":{"PenOthersNewRoot":{"FULL_PATH":"/avatar/parameters/OGB/Orf/Blowjob/PenOthersNewRoot","TYPE":"f","ACCESS":1},"PenOthersNewTip":{"FULL_PATH":"/avatar/parameters/OGB/Orf/Blowjob/PenOthersNewTip","TYPE":"f","ACCESS":1}}}}}}}}}}}}}
            """;
    private static final String TWO_PENETRATORS = """
            {"FULL_PATH":"/","ACCESS":0,"CONTENTS":{"avatar":{"FULL_PATH":"/avatar","ACCESS":0,"CONTENTS":{"parameters":{"FULL_PATH":"/avatar/parameters","ACCESS":0,"CONTENTS":{"OGB":{"FULL_PATH":"/avatar/parameters/OGB","ACCESS":0,"CONTENTS":{"Orf":{"FULL_PATH":"/avatar/parameters/OGB/Orf","ACCESS":0,"CONTENTS":{"Blowjob":{"FULL_PATH":"/avatar/parameters/OGB/Orf/Blowjob","ACCESS":0,"CONTENTS":{"PenOthersNewRoot":{"FULL_PATH":"/avatar/parameters/OGB/Orf/Blowjob/PenOthersNewRoot","TYPE":"f","ACCESS":1},"PenOthersNewTip":{"FULL_PATH":"/avatar/parameters/OGB/Orf/Blowjob/PenOthersNewTip","TYPE":"f","ACCESS":1}}},"Handjob":{"FULL_PATH":"/avatar/parameters/OGB/Orf/Handjob","ACCESS":0,"CONTENTS":{"PenOthersNewRoot":{"FULL_PATH":"/avatar/parameters/OGB/Orf/Handjob/PenOthersNewRoot","TYPE":"f","ACCESS":1},"PenOthersNewTip":{"FULL_PATH":"/avatar/parameters/OGB/Orf/Handjob/PenOthersNewTip","TYPE":"f","ACCESS":1}}}}}}}}}}}}}
            """;

    private HttpServer httpServer;
    private int port;
    private volatile String servedTree = ONE_PENETRATOR;
    private final List<List<OscQueryNode>> reports = new CopyOnWriteArrayList<>();
    private VrchatParameterScanner scanner;

    @BeforeEach
    void setUp() throws IOException
    {
        httpServer = HttpServer.create(new InetSocketAddress(InetAddress.getLoopbackAddress(), 0), 0);
        port = httpServer.getAddress().getPort();
        httpServer.createContext("/", exchange ->
        {
            byte[] body = servedTree.getBytes(StandardCharsets.UTF_8);
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
        if (scanner != null)
        {
            scanner.close();
        }
        httpServer.stop(0);
    }

    @Test
    void reportsTheAvatarParametersItReadFromADiscoveredService() throws Exception
    {
        startScannerWithDiscoveredService();

        awaitReport();
        assertThat(reports.getFirst()).extracting(OscQueryNode::path).containsExactlyInAnyOrder(
                "/avatar/parameters/OGB/Orf/Blowjob/PenOthersNewRoot",
                "/avatar/parameters/OGB/Orf/Blowjob/PenOthersNewTip");
    }

    @Test
    void doesNotReportUnchangedParametersAgain() throws Exception
    {
        startScannerWithDiscoveredService();
        awaitReport();

        scanner.scanOnce();
        scanner.scanOnce();

        assertThat(reports).hasSize(1);
    }

    @Test
    void reportsAgainWhenTheAvatarParametersChanged() throws Exception
    {
        startScannerWithDiscoveredService();
        awaitReport();

        servedTree = TWO_PENETRATORS;
        scanner.scanOnce();

        assertThat(reports).hasSize(2);
        assertThat(reports.getLast()).extracting(OscQueryNode::path)
                .contains("/avatar/parameters/OGB/Orf/Handjob/PenOthersNewRoot");
    }

    private void startScannerWithDiscoveredService()
    {
        var profile = new OscQueryServiceProfile("VRChat-Client-Test", List.of(InetAddress.getLoopbackAddress()), port);
        // The service is "discovered" before the scanner takes its first look, just like a running VRChat
        scanner = VrchatParameterScanner.start(listener -> listener.accept(profile), config(),
                (reportedProfile, parameters) ->
                {
                    assertThat(reportedProfile).isEqualTo(profile);
                    reports.add(parameters);
                });
    }

    private void awaitReport() throws InterruptedException
    {
        long deadline = System.currentTimeMillis() + 5000;
        while (reports.isEmpty() && System.currentTimeMillis() < deadline)
        {
            Thread.sleep(20);
        }
        assertThat(reports).as("avatar parameters reported by the scanner").isNotEmpty();
    }

    private static ConfigProperties config()
    {
        return ConfigProperties.builder()
                .avatarParameter("/avatar/parameters/OGB/Orf/*/PenOthersNewRoot")
                .penetratorTipParameter("/avatar/parameters/OGB/Orf/*/PenOthersNewTip")
                .spsType(SpsType.ORIFICE)
                .processingAlgorithm(ParameterProcessorType.HSP)
                .sendMessageEveryMs(0)
                .build();
    }
}
