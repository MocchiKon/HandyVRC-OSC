package org.example.handy.common;

/**
 * Result of the message delay measurement.
 *
 * @param delayMs average one-way delay to the device in milliseconds
 * @param jitterMs one-way variation (spread) of the delay in milliseconds, after outliers were rejected.
 *                 Useful for scheduling: a stable delay can be compensated exactly, but a delay that
 *                 fluctuates needs a safety margin to avoid arriving earlier than planned.
 */
public record MessageDelayStats(
        long delayMs,
        long jitterMs
)
{
}
