package org.example.handy.common.dto;

/**
 * A single timed movement target produced from OSC input. It is shared by all streaming processors,
 * regardless of the protocol used to deliver it to the device.
 * <p>
 * The meaning of {@code t} depends on the processor that created the point:
 * <ul>
 *   <li>HSP: timestamp relative to the start of the device stream. Points are uploaded in batches and the
 *   device itself schedules when each of them is played.</li>
 *   <li>HDSP: timestamp of when the point was generated (relative to processor start). HDSP has no
 *   device-side scheduling, so only the difference between consecutive timestamps is used to derive how
 *   long the device should take to reach the target.</li>
 * </ul>
 *
 * @param t time of the point in milliseconds (see above)
 * @param x target position in the 0-100 range (100 = top, 0 = bottom)
 */
public record MovementPoint(int t, int x) {
}
