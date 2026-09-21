package org.example.handy.common;

import org.junit.jupiter.api.Test;
import org.slf4j.Logger;

import java.lang.reflect.Proxy;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;

import static org.assertj.core.api.Assertions.assertThat;

class RateLimitedLoggerTest
{
    private final List<String> logged = new CopyOnWriteArrayList<>();
    private final Logger logger = recordingLogger(logged);

    @Test
    void logsTheFirstOccurrenceImmediately()
    {
        var limiter = new RateLimitedLogger(logger, 10_000);

        limiter.warn("points were skipped");

        assertThat(logged).containsExactly("warn: points were skipped");
    }

    @Test
    void summarizesTheOccurrencesSuppressedWithinTheInterval()
    {
        var limiter = new RateLimitedLogger(logger, 60);

        limiter.warn("first");
        limiter.warn("suppressed");
        limiter.warn("suppressed");
        sleep(80);
        limiter.warn("after the interval");

        assertThat(logged).containsExactly(
                "warn: first",
                "warn: after the interval (+2 similar message(s) suppressed)");
    }

    @Test
    void keepsTheLevelOfTheMessage()
    {
        var limiter = new RateLimitedLogger(logger, 10_000);

        limiter.error("sending points failed");

        assertThat(logged).containsExactly("error: sending points failed");
    }

    private static void sleep(long ms)
    {
        try
        {
            Thread.sleep(ms);
        }
        catch (InterruptedException e)
        {
            Thread.currentThread().interrupt();
        }
    }

    private static Logger recordingLogger(List<String> messages)
    {
        return (Logger) Proxy.newProxyInstance(RateLimitedLoggerTest.class.getClassLoader(), new Class<?>[]{Logger.class},
                (proxy, method, args) ->
                {
                    switch (method.getName())
                    {
                        case "warn", "error", "info", "debug", "trace" ->
                        {
                            messages.add("%s: %s".formatted(method.getName(), args[0]));
                            return null;
                        }
                        case "isWarnEnabled", "isErrorEnabled", "isInfoEnabled", "isDebugEnabled", "isTraceEnabled" -> {
                            return true;
                        }
                        case "getName" -> {
                            return "recording-logger";
                        }
                        default -> {
                            return method.getReturnType() == boolean.class ? false : null;
                        }
                    }
                });
    }
}
