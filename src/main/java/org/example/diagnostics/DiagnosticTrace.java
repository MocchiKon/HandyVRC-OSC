package org.example.diagnostics;

import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.extern.slf4j.Slf4j;

import java.io.BufferedWriter;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Structured JSONL trace of one session, used to analyze what the app and the device did without parsing the text
 * log. Every event has a wall-clock time in milliseconds since the epoch ({@code wallMs}), a monotonic time in
 * milliseconds ({@code monoMs}) and a {@code type}. The trace is diagnostic only: when it cannot be written, the
 * movement keeps running and the error is reported in the normal log.
 * <p>
 * There is at most one active trace. It is started by the processor when {@code hdspDiagnosticTrace} is enabled and
 * is also written by the BLE client, which records the commands it sends and the notifications it receives. Events
 * are queued and written by a separate thread: the BLE reader thread must not block on file I/O, and the sending
 * thread must not be delayed by it either.
 */
@Slf4j
public final class DiagnosticTrace implements AutoCloseable
{
    private static final ObjectMapper JSON = new ObjectMapper();
    private static final DateTimeFormatter FILE_TIME = DateTimeFormatter.ofPattern("yyyyMMdd-HHmmss-SSS");
    private static final int QUEUE_CAPACITY = 20_000;
    private static final Map<String, Object> END = Map.of();

    private static volatile DiagnosticTrace active;
    private static final AtomicInteger SEQUENCE = new AtomicInteger();

    private final Path path;
    private final BufferedWriter writer;
    private final BlockingQueue<Map<String, Object>> queue = new LinkedBlockingQueue<>(QUEUE_CAPACITY);
    private final Thread writerThread;
    private volatile boolean closed;
    private volatile boolean failing;

    private DiagnosticTrace(Path path) throws IOException
    {
        this.path = path;
        Files.createDirectories(path.getParent());
        this.writer = Files.newBufferedWriter(path, StandardCharsets.UTF_8);
        this.writerThread = new Thread(this::writeLoop, "diagnostic-trace-writer");
        this.writerThread.setDaemon(true);
        this.writerThread.start();
    }

    /**
     * Starts a new trace in the given directory and makes it the active one. An earlier trace (a session that was
     * not stopped cleanly) is closed first, so only one file is written at a time.
     *
     * @param directory directory the file is created in
     * @param session   fields of the session event that is written first (configuration, start time, ...)
     * @return the started trace
     */
    public static synchronized DiagnosticTrace start(Path directory, Map<String, Object> session) throws IOException
    {
        if (active != null)
        {
            active.close();
        }
        Path file = directory.resolve("trace-" + LocalDateTime.now().format(FILE_TIME) + "-"
                + SEQUENCE.incrementAndGet() + ".jsonl");
        DiagnosticTrace trace = new DiagnosticTrace(file);
        trace.event("session", session);
        active = trace;
        return trace;
    }

    /** @return the trace of the running session, or null when diagnostics tracing is disabled */
    public static DiagnosticTrace active()
    {
        return active;
    }

    public Path path()
    {
        return path;
    }

    /** Queues an event with the given fields; returns immediately. */
    public void event(String type, Map<String, Object> fields)
    {
        Map<String, Object> event = new LinkedHashMap<>();
        event.put("wallMs", System.currentTimeMillis());
        event.put("monoMs", System.nanoTime() / 1_000_000L);
        event.put("type", type);
        if (fields != null)
        {
            event.putAll(fields);
        }
        if (!closed && !failing && !queue.offer(event))
        {
            log.warn("Diagnostic trace queue is full, dropping a {} event", type);
        }
    }

    /**
     * Queues an event from alternating keys and values, for example
     * {@code event("device", "position", 0.5, "rttMs", 100)}.
     */
    public void event(String type, Object... keyValues)
    {
        if (keyValues.length % 2 != 0)
        {
            log.warn("Diagnostic trace event {} has an odd number of fields, ignoring it", type);
            return;
        }
        Map<String, Object> fields = new LinkedHashMap<>();
        for (int i = 0; i < keyValues.length; i += 2)
        {
            fields.put(String.valueOf(keyValues[i]), keyValues[i + 1]);
        }
        event(type, fields);
    }

    private void writeLoop()
    {
        try
        {
            while (true)
            {
                Map<String, Object> event = queue.take();
                if (event == END)
                {
                    return;
                }
                if (failing)
                {
                    continue;
                }
                try
                {
                    writer.write(JSON.writeValueAsString(event));
                    writer.newLine();
                    writer.flush();
                }
                catch (IOException e)
                {
                    failing = true;
                    log.warn("Could not write the diagnostic trace to {}: {}", path, e.getMessage());
                }
            }
        }
        catch (InterruptedException e)
        {
            Thread.currentThread().interrupt();
        }
    }

    @Override
    public synchronized void close()
    {
        if (closed)
        {
            return;
        }
        closed = true;
        if (active == this)
        {
            active = null;
        }
        try
        {
            queue.offer(END, 2, TimeUnit.SECONDS);
        }
        catch (InterruptedException e)
        {
            Thread.currentThread().interrupt();
        }
        try
        {
            writerThread.join(2_000);
        }
        catch (InterruptedException e)
        {
            Thread.currentThread().interrupt();
        }
        try
        {
            writer.close();
        }
        catch (IOException e)
        {
            log.warn("Could not close the diagnostic trace {}: {}", path, e.getMessage());
        }
    }
}
