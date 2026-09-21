package org.example.handy.common.dto;

/**
 * State of the HSP stream (and of the device-side point buffer) that the device reports for HSP commands.
 * <p>
 * Only {@code current_time} is always available; the remaining fields are {@code null} when the source of the
 * state does not report them (the BLE device state does not use proto3 presence, so a value that was not filled
 * in is indistinguishable from a real zero - the fields are nullable here so that a missing value is never
 * confused with a meaningful one).
 *
 * @param current_time     current play time of the device (ms, in the timeline of the sent points)
 * @param first_point_time time of the first point in the device buffer
 * @param last_point_time  time of the last point in the device buffer
 * @param points           number of points currently in the device buffer
 * @param max_points       size of the device buffer (points that do not fit are dropped by the device)
 * @param current_point    index of the point that is currently playing (-1 when not playing)
 * @param play_state       state of the stream
 */
public record HspState(
        int current_time,
        Integer first_point_time,
        Integer last_point_time,
        Integer points,
        Integer max_points,
        Integer current_point,
        HspPlayState play_state
)
{
    /** State with only the timing fields known (used for mocks and for sources that report nothing else). */
    public HspState(int current_time, Integer first_point_time, Integer last_point_time)
    {
        this(current_time, first_point_time, last_point_time, null, null, null, null);
    }

    public boolean isPlaying()
    {
        return play_state == HspPlayState.PLAYING;
    }
}
