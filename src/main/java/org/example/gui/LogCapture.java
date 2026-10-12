package org.example.gui;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.LoggerContext;
import ch.qos.logback.classic.PatternLayout;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.AppenderBase;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Logback appender that keeps the log messages for the Logs tab. Logging must never slow the app down (at TRACE
 * level every received OSC value is logged), so a message is only formatted and queued here; the Logs tab takes
 * the queued messages a few times per second. When the queue is full, messages are dropped and counted.
 */
public final class LogCapture extends AppenderBase<ILoggingEvent>
{
    private static final int CAPACITY = 5_000;
    private static final LogCapture INSTANCE = new LogCapture();

    /** @param text the formatted message, ending with a line break */
    public record Entry(Level level, String text) {}

    private final BlockingQueue<Entry> pending = new ArrayBlockingQueue<>(CAPACITY);
    private final AtomicLong dropped = new AtomicLong();
    private PatternLayout layout;

    private LogCapture()
    {
    }

    /** Starts capturing; call as early as possible, so that the startup messages are shown as well. */
    public static synchronized void install()
    {
        if (INSTANCE.isStarted())
        {
            return;
        }
        if (!(LoggerFactory.getILoggerFactory() instanceof LoggerContext context))
        {
            return;
        }
        var layout = new PatternLayout();
        layout.setContext(context);
        layout.setPattern("%d{HH:mm:ss.SSS} %-5level %logger{0} - %msg%n%ex{8}");
        layout.start();
        INSTANCE.layout = layout;
        INSTANCE.setContext(context);
        INSTANCE.setName("GUI");
        INSTANCE.start();
        context.getLogger(Logger.ROOT_LOGGER_NAME).addAppender(INSTANCE);
    }

    public static LogCapture get()
    {
        return INSTANCE;
    }

    @Override
    protected void append(ILoggingEvent event)
    {
        if (!pending.offer(new Entry(event.getLevel(), layout.doLayout(event).replace("\r", ""))))
        {
            dropped.incrementAndGet();
        }
    }

    /** @return the messages logged since the last call */
    public List<Entry> drain()
    {
        List<Entry> entries = new ArrayList<>();
        pending.drainTo(entries);
        return entries;
    }

    /** @return how many messages were dropped since the last call because they came in too fast */
    public long takeDroppedCount()
    {
        return dropped.getAndSet(0);
    }
}
