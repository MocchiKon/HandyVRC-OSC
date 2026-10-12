package org.example.config;

import lombok.Builder;
import org.example.processor.HdspSmoothedParameterProcessor;
import org.example.processor.ParameterProcessorType;
import org.example.processor.SpsType;

import java.net.URI;
import java.net.URISyntaxException;
import java.util.Locale;

/**
 * Settings of the app. They are edited in the GUI and stored in app.properties (see {@link ConfigLoader}).
 * <p>
 * The device is driven with one of the HDSP algorithms, either over a direct Bluetooth connection or through
 * Intiface Central (see {@link ConnectionType}).
 */
@Builder(toBuilder = true)
public record ConfigProperties(
        // Basic
        ConnectionType connection,
        // WebSocket address of the Intiface Central server, only used with the INTIFACE connection
        String intifaceAddress,
        SpsType spsType,
        // Whose contacts move the device, selects the default avatar parameter
        InteractWith interactWith,
        ParameterProcessorType processingAlgorithm,
        // Effective avatar parameter: the custom one, or the default for spsType and interactWith when not customized
        String avatarParameter,
        // True when the user entered the avatar parameter instead of using the default for spsType and interactWith
        boolean customAvatarParameter,
        Float sliderMin,
        Float sliderMax,
        Float fullyPenetratedAtValue,
        // Reverses the slider movement: fully penetrated is the top of the stroke instead of the bottom
        boolean reverseMovement,
        int listenOnPort,
        boolean useOscQuery,

        // Advanced (all modes)
        int sendMessageEveryMs,
        boolean checkForUpdates,

        // Advanced (DIRECT only)
        int minimalValueChange,
        boolean pauseOnStarving,

        // Advanced (SMOOTHED only)
        Integer hdspSpeedChangeThresholdPercent,
        Integer hdspSpeedStopThresholdPercent,
        Integer hdspDivergenceThresholdPercent,
        Integer hdspSpeedMeasureWindowMs,
        Integer hdspMinSliderSpeed,
        Float hdspReversalMinDistancePercent,
        boolean hdspLogDevicePosition,

        // Not editable in the GUI, kept so that a log level set in app.properties survives saving
        String logLevel
)
{
    public static final String DEFAULT_PENETRATOR_PARAMETER = "/avatar/parameters/OGB/Pen/*/PenOthers";
    public static final String DEFAULT_ORIFICE_PARAMETER = "/avatar/parameters/OGB/Orf/*/PenOthersNewRoot";
    public static final float DEFAULT_FULLY_PENETRATED_AT_VALUE = 85f;
    public static final int DEFAULT_LISTEN_ON_PORT = 9001;
    public static final int DEFAULT_MINIMAL_VALUE_CHANGE = 3;
    public static final String DEFAULT_INTIFACE_ADDRESS = "ws://127.0.0.1:12345";

    private static final String NEW_ROOT_SUFFIX = "NewRoot";
    private static final String NEW_TIP_SUFFIX = "NewTip";

    /** Settings used when app.properties does not exist yet, and by "Restore defaults" in the GUI. */
    public static ConfigProperties defaults()
    {
        return ConfigProperties.builder()
                .connection(ConnectionType.DIRECT)
                .intifaceAddress(DEFAULT_INTIFACE_ADDRESS)
                .spsType(SpsType.PENETRATOR)
                .interactWith(InteractWith.OTHERS)
                .processingAlgorithm(ParameterProcessorType.HDSP_SMOOTHED)
                .avatarParameter(defaultAvatarParameter(SpsType.PENETRATOR, InteractWith.OTHERS))
                .customAvatarParameter(false)
                .sliderMin(0f)
                .sliderMax(1f)
                .fullyPenetratedAtValue(DEFAULT_FULLY_PENETRATED_AT_VALUE)
                .reverseMovement(false)
                .listenOnPort(DEFAULT_LISTEN_ON_PORT)
                .useOscQuery(true)
                .sendMessageEveryMs(0)
                .checkForUpdates(true)
                .minimalValueChange(DEFAULT_MINIMAL_VALUE_CHANGE)
                .pauseOnStarving(false)
                .hdspSpeedChangeThresholdPercent(HdspSmoothedParameterProcessor.DEFAULT_SPEED_CHANGE_THRESHOLD_PERCENT)
                .hdspSpeedStopThresholdPercent(HdspSmoothedParameterProcessor.DEFAULT_SPEED_STOP_THRESHOLD_PERCENT)
                .hdspDivergenceThresholdPercent(HdspSmoothedParameterProcessor.DEFAULT_DIVERGENCE_THRESHOLD_PERCENT)
                .hdspSpeedMeasureWindowMs(HdspSmoothedParameterProcessor.DEFAULT_SPEED_MEASURE_WINDOW_MS)
                .hdspMinSliderSpeed(HdspSmoothedParameterProcessor.DEFAULT_MIN_SLIDER_SPEED_MM_PER_SECOND)
                .hdspReversalMinDistancePercent(HdspSmoothedParameterProcessor.DEFAULT_REVERSAL_MIN_DISTANCE_PERCENT)
                .hdspLogDevicePosition(false)
                .logLevel("INFO")
                .build();
    }

    /**
     * @return the parameter used unless a custom one is set, for example '/avatar/parameters/OGB/Pen/*&#47;PenSelf'
     * (PENETRATOR, SELF) or '/avatar/parameters/OGB/Orf/*&#47;{PenSelf,PenOthers}NewRoot' (ORIFICE, BOTH)
     */
    public static String defaultAvatarParameter(SpsType spsType, InteractWith interactWith)
    {
        InteractWith who = interactWith == null ? InteractWith.OTHERS : interactWith;
        return spsType == SpsType.ORIFICE
                ? "/avatar/parameters/OGB/Orf/*/" + who.parameterName() + NEW_ROOT_SUFFIX
                : "/avatar/parameters/OGB/Pen/*/" + who.parameterName();
    }

    /**
     * OSC parameter that carries the tip proximity used to auto-detect the penetrator length (ORIFICE only). It is
     * derived from the root parameter by replacing its 'NewRoot' suffix with 'NewTip' (for example
     * '/avatar/parameters/OGB/Orf/&lt;socket&gt;/PenOthersNewRoot' becomes
     * '/avatar/parameters/OGB/Orf/&lt;socket&gt;/PenOthersNewTip').
     *
     * @return the tip parameter, or null when it is not needed (PENETRATOR) or cannot be derived
     */
    public String penetratorTipParameter()
    {
        return penetratorTipParameter(spsType, avatarParameter);
    }

    public static String penetratorTipParameter(SpsType spsType, String avatarParameter)
    {
        if (spsType != SpsType.ORIFICE || avatarParameter == null || !avatarParameter.endsWith(NEW_ROOT_SUFFIX))
        {
            return null;
        }
        return avatarParameter.substring(0, avatarParameter.length() - NEW_ROOT_SUFFIX.length()) + NEW_TIP_SUFFIX;
    }

    /**
     * @return a message explaining why the app cannot run with this avatar parameter, or null when it can
     */
    public static String validateAvatarParameter(SpsType spsType, String avatarParameter)
    {
        if (avatarParameter == null || avatarParameter.isBlank())
        {
            return "The avatar parameter is empty.";
        }
        if (spsType == SpsType.ORIFICE && penetratorTipParameter(spsType, avatarParameter) == null)
        {
            return "In Orifice mode the avatar parameter has to end with 'NewRoot' (for example "
                    + DEFAULT_ORIFICE_PARAMETER + "), the matching 'NewTip' parameter is used to detect the"
                    + " penetrator length.";
        }
        return null;
    }

    /**
     * @return the WebSocket address of the Intiface Central server ('ws://' is added when the scheme is left out),
     * or null when the address is not valid
     */
    public static URI intifaceUri(String address)
    {
        if (address == null || address.isBlank())
        {
            return null;
        }
        String trimmed = address.trim();
        try
        {
            URI uri = new URI(trimmed.contains("://") ? trimmed : "ws://" + trimmed);
            String scheme = uri.getScheme() == null ? "" : uri.getScheme().toLowerCase(Locale.ROOT);
            return (scheme.equals("ws") || scheme.equals("wss")) && uri.getHost() != null ? uri : null;
        }
        catch (URISyntaxException e)
        {
            return null;
        }
    }

    /**
     * @return a message explaining why the app cannot connect to Intiface Central with this address, or null when
     * it can
     */
    public static String validateIntifaceAddress(String address)
    {
        return intifaceUri(address) == null
                ? "The Intiface address is not valid. Use the server address Intiface Central shows, for example "
                        + DEFAULT_INTIFACE_ADDRESS + "."
                : null;
    }

    /**
     * @return a message explaining why the app cannot run with these settings, or null when it can
     */
    public String validate()
    {
        if (connection == ConnectionType.INTIFACE)
        {
            String error = validateIntifaceAddress(intifaceAddress);
            if (error != null)
            {
                return error;
            }
        }
        return validateAvatarParameter(spsType, avatarParameter);
    }

    public String toLoggableString()
    {
        return toString();
    }
}
