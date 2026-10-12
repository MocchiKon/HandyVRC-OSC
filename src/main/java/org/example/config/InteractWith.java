package org.example.config;

/**
 * Whose SPS contacts move the device. Picks the default avatar parameter (see
 * {@link ConfigProperties#defaultAvatarParameter}), so that a custom parameter is only needed for unusual avatars.
 */
public enum InteractWith
{
    /** Only your own body, for example your penetrator in your own orifice (also handy for testing alone). */
    SELF("PenSelf"),
    /** Only other players. */
    OTHERS("PenOthers"),
    /** Yourself and other players, as an OSC pattern that matches both parameters. */
    BOTH("{PenSelf,PenOthers}");

    private final String parameterName;

    InteractWith(String parameterName)
    {
        this.parameterName = parameterName;
    }

    /** @return name of the SPS parameter (or OSC pattern) without the 'NewRoot' suffix of the orifice parameters */
    public String parameterName()
    {
        return parameterName;
    }
}
