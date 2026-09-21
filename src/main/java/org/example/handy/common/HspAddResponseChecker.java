package org.example.handy.common;

import org.example.handy.common.dto.HspPlayState;
import org.example.handy.common.dto.HspState;
import org.example.handy.common.dto.MovementPoint;

import java.util.ArrayList;
import java.util.List;

/**
 * Checks the {@link HspState} the device returns for a batch of added points against the points that were sent.
 * <p>
 * The device accepts an HSP add as soon as it is parsed, even when the points in it are already in the past or
 * when its point buffer is full and the points are dropped, so a command that "succeeded" can still mean that
 * nothing was played. The checks here detect those cases:
 * <ul>
 *     <li>points that the device skipped because the play position already passed them (the batch arrived too
 *     late - the usual symptom of a too small {@code pointsOffset} or of a stalled connection),</li>
 *     <li>a stream that is not playing (not initialized, stopped, paused) or that ran dry (starving),</li>
 *     <li>a device buffer that is full (new points get dropped) or empty right after the batch was added.</li>
 * </ul>
 * A {@code null} state and a stream that is not initialized are hard failures, everything else is reported as a
 * warning because the stream keeps running.
 */
public final class HspAddResponseChecker
{
    /** Buffer level (in points) at or below which the device is about to run dry. */
    private static final int LOW_BUFFER_POINTS = 1;

    private HspAddResponseChecker()
    {
    }

    public static HspAddCheck check(List<MovementPoint> sentPoints, HspState state)
    {
        int sent = sentPoints == null ? 0 : sentPoints.size();
        if (state == null)
        {
            return new HspAddCheck(problem("HSP_ADD_NO_STATE",
                            "The device did not report an HSP state for the added points, so they could not be verified"),
                    List.of(), sent, 0, null, null, null, null);
        }

        List<String> warnings = new ArrayList<>();
        int currentTime = state.current_time();
        int skipped = 0;
        Long leewayMs = null;
        Integer firstSentTime = null;
        Integer lastSentTime = null;

        if (sent > 0)
        {
            firstSentTime = sentPoints.stream().mapToInt(MovementPoint::t).min().orElse(currentTime);
            lastSentTime = sentPoints.stream().mapToInt(MovementPoint::t).max().orElse(currentTime);
            leewayMs = (long) firstSentTime - currentTime;
            skipped = countSkippedPoints(sentPoints, currentTime);

            if (skipped == sent)
            {
                warnings.add("the device skipped all %d point(s) of the batch: the play position (%dms) already passed the end of the batch (%dms), increase 'pointsOffset' or send points earlier"
                        .formatted(sent, currentTime, lastSentTime));
            }
            else if (skipped > 0)
            {
                warnings.add("%d of %d point(s) of the batch were skipped: the play position (%dms) already passed them (first sent point at %dms, leeway %dms)"
                        .formatted(skipped, sent, currentTime, firstSentTime, leewayMs));
            }
        }

        HandyError error = checkPlayState(state.play_state(), warnings);
        checkBuffer(state, sent, skipped, lastSentTime, warnings);

        return new HspAddCheck(error, List.copyOf(warnings), sent, skipped, leewayMs, state.play_state(),
                state.points(), state.max_points());
    }

    private static int countSkippedPoints(List<MovementPoint> sentPoints, int currentTime)
    {
        int skipped = 0;
        for (MovementPoint point : sentPoints)
        {
            // The device plays the points in time order; a point at or before the current play time is not reached anymore.
            if (point.t() <= currentTime)
            {
                skipped++;
            }
        }
        return skipped;
    }

    private static HandyError checkPlayState(HspPlayState playState, List<String> warnings)
    {
        if (playState == null)
        {
            return null; // The source did not report a play state - nothing can be judged
        }
        return switch (playState)
        {
            case NOT_INITIALIZED -> problem("HSP_NOT_INITIALIZED",
                    "The HSP stream is not initialized on the device (play_state=NOT_INITIALIZED), the added points will not be played");
            case STOPPED -> {
                warnings.add("the device is not playing (play_state=STOPPED): the points stay buffered until a play command is sent");
                yield null;
            }
            case PAUSED -> {
                warnings.add("the device is paused (play_state=PAUSED): the points stay buffered until the stream is resumed");
                yield null;
            }
            case STARVING -> {
                warnings.add("the device ran out of points (play_state=STARVING): points arrive too late, increase 'pointsOffset'");
                yield null;
            }
            case PLAYING, UNKNOWN -> null;
        };
    }

    private static void checkBuffer(HspState state, int sent, int skipped, Integer lastSentTime, List<String> warnings)
    {
        Integer bufferedPoints = state.points();
        Integer maxPoints = state.max_points();
        boolean bufferSizeKnown = maxPoints != null && maxPoints > 0;

        if (bufferSizeKnown && bufferedPoints != null && bufferedPoints >= maxPoints)
        {
            warnings.add("the device point buffer is full (%d/%d): points that do not fit are dropped, the device cannot keep up with the stream"
                    .formatted(bufferedPoints, maxPoints));
        }
        if (bufferSizeKnown && bufferedPoints != null && bufferedPoints <= LOW_BUFFER_POINTS && state.isPlaying())
        {
            warnings.add("the device buffer holds %d point(s) right after adding %d: the stream is about to starve"
                    .formatted(bufferedPoints, sent));
        }
        if (sent > 0 && skipped < sent && lastSentTime != null && state.last_point_time() != null
                && state.last_point_time() > 0 && state.last_point_time() < lastSentTime)
        {
            warnings.add("the device buffer ends at %dms while the batch ends at %dms: the end of the batch was not buffered (buffer full?)"
                    .formatted(state.last_point_time(), lastSentTime));
        }
        if (state.first_point_time() != null && state.last_point_time() != null
                && state.first_point_time() > state.last_point_time())
        {
            warnings.add("the device reported an inconsistent buffer (first_point_time=%dms > last_point_time=%dms)"
                    .formatted(state.first_point_time(), state.last_point_time()));
        }
    }

    private static HandyError problem(String name, String message)
    {
        return new HandyError(0, name, message, true);
    }
}
