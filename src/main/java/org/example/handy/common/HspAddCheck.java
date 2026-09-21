package org.example.handy.common;

import org.example.handy.common.dto.HspPlayState;

import java.util.List;

/**
 * Outcome of checking the device state that was returned for a batch of added HSP points
 * (see {@link HspAddResponseChecker}).
 *
 * @param error          hard problem: the batch cannot be played or could not be verified at all; {@code null} when the
 *                       batch was accepted (warnings may still be present)
 * @param warnings       soft problems that degrade the movement (skipped points, starving stream, saturated buffer)
 * @param sentPoints     number of points the batch contained
 * @param skippedPoints  number of those points that were already in the past when the device handled the batch
 * @param leewayMs       time between the first point of the batch and the device's current play time
 *                       ({@code firstSentPointTime - current_time}); {@code <= 0} means the batch arrived too late.
 *                       {@code null} when nothing was sent
 * @param playState      play state the device reported
 * @param bufferedPoints points in the device buffer after the batch was added
 * @param maxPoints      size of the device buffer
 */
public record HspAddCheck(
        HandyError error,
        List<String> warnings,
        int sentPoints,
        int skippedPoints,
        Long leewayMs,
        HspPlayState playState,
        Integer bufferedPoints,
        Integer maxPoints
)
{
    public boolean isOk()
    {
        return error == null && warnings.isEmpty();
    }

    public boolean hasProblems()
    {
        return !isOk();
    }
}
