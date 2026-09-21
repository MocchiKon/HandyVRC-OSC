package org.example.handy.common.dto;

/**
 * State of the HSP stream on the device, as reported in HSP responses and notifications.
 */
public enum HspPlayState
{
    /** The stream was not set up on the device, so points cannot be played at all. */
    NOT_INITIALIZED,
    PLAYING,
    /** The stream reached the end of its buffer and is not playing (until a play command follows). */
    STOPPED,
    PAUSED,
    /** The device ran out of points while playing: points arrive too late or too slowly. */
    STARVING,
    /** A state this app does not know yet (a newer firmware may add more). */
    UNKNOWN
}
