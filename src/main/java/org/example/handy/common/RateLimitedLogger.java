package org.example.handy.common;

import org.slf4j.Logger;

import java.util.concurrent.atomic.AtomicLong;

/**
 * Logs a repeatedly occurring message at most once per interval and reports how many occurrences were
 * suppressed in the meantime.
 * <p>
 * Streaming to the device produces one log-worthy event per sent batch (up to dozens per second), and a problem
 * that lasts for a while would otherwise flood the log file with the same message while hiding everything else.
 * The first occurrence is always logged immediately, later ones are summarized.
 */
public final class RateLimitedLogger
{
    /** Default interval: a message that keeps repeating is summarized once per second. */
    public static final long DEFAULT_INTERVAL_MS = 1_000;

    private final Logger logger;
    private final long intervalMs;
    private final AtomicLong windowStartMs = new AtomicLong();
    private final AtomicLong suppressedCount = new AtomicLong();

    public RateLimitedLogger(Logger logger)
    {
        this(logger, DEFAULT_INTERVAL_MS);
    }

    public RateLimitedLogger(Logger logger, long intervalMs)
    {
        this.logger = logger;
        this.intervalMs = intervalMs;
    }

    public void warn(String message)
    {
        if (shouldLog())
        {
            logger.warn(withSuppressedCount(message));
        }
    }

    public void error(String message)
    {
        if (shouldLog())
        {
            logger.error(withSuppressedCount(message));
        }
    }

    private boolean shouldLog()
    {
        long now = System.currentTimeMillis();
        long windowStart = windowStartMs.get();
        if (now - windowStart < intervalMs)
        {
            suppressedCount.incrementAndGet();
            return false;
        }
        if (windowStartMs.compareAndSet(windowStart, now))
        {
            return true;
        }
        suppressedCount.incrementAndGet();
        return false;
    }

    private String withSuppressedCount(String message)
    {
        long suppressed = suppressedCount.getAndSet(0);
        return suppressed == 0 ? message : "%s (+%d similar message(s) suppressed)".formatted(message, suppressed);
    }
}
