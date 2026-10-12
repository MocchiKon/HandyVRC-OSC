package org.example.handy.common.dto;

/**
 * A single timed movement target produced from OSC input by the streaming processors.
 * <p>
 * {@code t} is the timestamp of when the point was generated (relative to processor start). HDSP has no
 * device-side scheduling, so only the difference between consecutive timestamps is used to derive how
 * long the device should take to reach the target.
 *
 * @param t time of the point in milliseconds (see above)
 * @param x target position in the 0-100 range (100 = top, 0 = bottom)
 */
public record MovementPoint(int t, int x) {
}
