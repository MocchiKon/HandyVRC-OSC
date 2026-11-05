package org.example.config;

import lombok.Getter;
import lombok.RequiredArgsConstructor;

/** How the app reaches the device. */
@Getter
@RequiredArgsConstructor
public enum ConnectionType
{
    /**
     * The app connects to the Handy over Bluetooth itself. Default and recommended. The enum name stays DIRECT
     * because it is written to app.properties; only the label shown to the user is "Bluetooth".
     */
    DIRECT("Bluetooth"),
    /**
     * The app connects to the Handy through the Handy servers over the internet (Handy API v3). The device has to be
     * connected to Wi-Fi, and the user enters its connection key and (usually the default) application ID.
     */
    WIFI("Wi-Fi"),
    /**
     * The app sends the movement to Intiface Central (Buttplug protocol over a WebSocket), which connects to the
     * device. Other strokers Intiface supports might work as well, but only the Handy is tested.
     */
    INTIFACE("Intiface");

    /** Name shown to the user. */
    private final String label;
}
