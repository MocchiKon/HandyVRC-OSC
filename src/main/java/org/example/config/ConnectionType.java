package org.example.config;

import lombok.Getter;
import lombok.RequiredArgsConstructor;

/** How the app reaches the device. */
@Getter
@RequiredArgsConstructor
public enum ConnectionType
{
    /** The app connects to the Handy over Bluetooth itself. Default and recommended. */
    DIRECT("Direct"),
    /**
     * The app sends the movement to Intiface Central (Buttplug protocol over a WebSocket), which connects to the
     * device. Other strokers Intiface supports might work as well, but only the Handy is tested.
     */
    INTIFACE("Intiface");

    /** Name shown to the user. */
    private final String label;
}
