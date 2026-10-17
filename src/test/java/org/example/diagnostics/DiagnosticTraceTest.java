package org.example.diagnostics;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class DiagnosticTraceTest
{
    @TempDir
    Path tempDir;

    @Test
    void writesOneJsonEventPerLineWithMonotonicAndWallClockTimes() throws Exception
    {
        Map<String, Object> session = new LinkedHashMap<>();
        session.put("algorithm", "HDSP_SMOOTHED");
        session.put("speedWindow", 100);
        DiagnosticTrace trace = DiagnosticTrace.start(tempDir, session);
        try
        {
            trace.event("value", "position", 0.25, "raw", 0.25);
            trace.event("move", Map.of("kind", "ENDPOINT", "target", 1.0));
        }
        finally
        {
            trace.close();
        }

        List<String> lines = Files.readAllLines(trace.path());
        assertThat(lines).hasSize(3);
        ObjectMapper json = new ObjectMapper();
        JsonNode sessionEvent = json.readTree(lines.get(0));
        assertThat(sessionEvent.get("type").asText()).isEqualTo("session");
        assertThat(sessionEvent.get("algorithm").asText()).isEqualTo("HDSP_SMOOTHED");
        assertThat(sessionEvent.get("wallMs").asLong()).isPositive();
        assertThat(sessionEvent.get("monoMs").asLong()).isPositive();

        JsonNode value = json.readTree(lines.get(1));
        assertThat(value.get("type").asText()).isEqualTo("value");
        assertThat(value.get("position").asDouble()).isEqualTo(0.25);
        assertThat(value.get("raw").asDouble()).isEqualTo(0.25);

        JsonNode move = json.readTree(lines.get(2));
        assertThat(move.get("type").asText()).isEqualTo("move");
        assertThat(move.get("kind").asText()).isEqualTo("ENDPOINT");
        assertThat(move.get("target").asDouble()).isEqualTo(1.0);
    }

    @Test
    void startsAndClosesTheSingleActiveTrace() throws Exception
    {
        DiagnosticTrace first = DiagnosticTrace.start(tempDir, Map.of("session", 1));
        assertThat(DiagnosticTrace.active()).isSameAs(first);
        DiagnosticTrace second = DiagnosticTrace.start(tempDir, Map.of("session", 2));
        assertThat(DiagnosticTrace.active()).isSameAs(second);
        assertThat(first.path()).isNotEqualTo(second.path());
        second.close();
        assertThat(DiagnosticTrace.active()).isNull();
    }
}
