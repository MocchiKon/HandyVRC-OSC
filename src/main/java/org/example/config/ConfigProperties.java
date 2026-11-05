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
 * The device is driven with one of the HDSP algorithms, over a direct Bluetooth connection, through the Handy
 * servers (Wi-Fi), or through Intiface Central (see {@link ConnectionType}).
 */
@Builder(toBuilder = true)
public record ConfigProperties(
        // Basic
        ConnectionType connection,
        // Connection key of the Handy shown in the Handy app, only used with the WIFI connection
        String connectionKey,
        // Application ID (API key) used to authenticate with the Handy servers, only used with the WIFI connection
        String handyApplicationId,
        // WebSocket address of the Intiface Central server, only used with the INTIFACE connection
        String intifaceAddress,
        // Intiface device index of the selected device, -1 for the first usable one. The index is what makes the
        // selection unambiguous: several devices can share the same device name (for example two simulated ones).
        Integer intifaceDeviceIndex,
        // Display label of the selected device, kept so that the list can still show it while it is not connected to
        // Intiface Central.
        String intifaceDeviceName,
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

        // Advanced (HDSP movement mode, shown as 'Direct', only)
        int sendMessageEveryMsDirect,
        // Advanced (SMOOTHED only)
        int sendMessageEveryMsSmoothed,
        boolean checkForUpdates,

        // Advanced (HDSP movement mode, shown as 'Direct', only)
        int minimalValueChange,
        boolean pauseOnStarving,

        // Advanced (SMOOTHED only)
        Integer hdspSpeedChangeThresholdPercent,
        Integer hdspSpeedStopThresholdPercent,
        Integer hdspDivergenceThresholdPercent,
        Integer hdspSpeedMeasureWindowMs,
        Integer hdspMinSliderSpeed,
        // Highest speed the device can move (mm/s), 0 leaves it unlimited
        Integer hdspMaxSliderSpeed,
        // Uses the limits measured by the newest device calibration instead of the two settings above
        boolean hdspUseDeviceProfile,
        Float hdspReversalMinDistancePercent,
        boolean hdspLogDevicePosition,
        // How often the device position is read while hdspLogDevicePosition is on (20-2000ms)
        Integer hdspDevicePositionLogIntervalMs,
        // Writes a structured JSONL trace of the values, commands and device samples for diagnostics
        boolean hdspDiagnosticTrace,

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
    /**
     * Application ID (API key) issued for this app. It is sent as the {@code X-Api-Key} header to the Handy servers;
     * users only need to change it when they were given a different one.
     */
    public static final String DEFAULT_HANDY_APPLICATION_ID = "oscT9zwCSnAKDYMthQ4yY_5m8jMUYY-W";

    private static final String NEW_ROOT_SUFFIX = "NewRoot";
    private static final String NEW_TIP_SUFFIX = "NewTip";
    /** Connection keys are 5-64 letters or digits according to the Handy API. */
    private static final String CONNECTION_KEY_PATTERN = "[A-Za-z0-9]{5,64}";

    /** Settings used when app.properties does not exist yet, and by "Restore defaults" in the GUI. */
    public static ConfigProperties defaults()
    {
        return ConfigProperties.builder()
                .connection(ConnectionType.DIRECT)
                .connectionKey("")
                .handyApplicationId(DEFAULT_HANDY_APPLICATION_ID)
                .intifaceAddress(DEFAULT_INTIFACE_ADDRESS)
                .intifaceDeviceIndex(-1)
                .intifaceDeviceName("")
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
                .sendMessageEveryMsDirect(50)   // HDSP ('Direct') movement mode: default 50 ms, minimum 20 ms
                .sendMessageEveryMsSmoothed(20) // Smoothed mode: default 20 ms, minimum 20 ms
                .checkForUpdates(true)
                .minimalValueChange(DEFAULT_MINIMAL_VALUE_CHANGE)
                .pauseOnStarving(true)
                .hdspSpeedChangeThresholdPercent(HdspSmoothedParameterProcessor.DEFAULT_SPEED_CHANGE_THRESHOLD_PERCENT)
                .hdspSpeedStopThresholdPercent(HdspSmoothedParameterProcessor.DEFAULT_SPEED_STOP_THRESHOLD_PERCENT)
                .hdspDivergenceThresholdPercent(HdspSmoothedParameterProcessor.DEFAULT_DIVERGENCE_THRESHOLD_PERCENT)
                .hdspSpeedMeasureWindowMs(HdspSmoothedParameterProcessor.DEFAULT_SPEED_MEASURE_WINDOW_MS)
                .hdspMinSliderSpeed(HdspSmoothedParameterProcessor.DEFAULT_MIN_SLIDER_SPEED_MM_PER_SECOND)
                .hdspMaxSliderSpeed(0)
                .hdspUseDeviceProfile(true)
                .hdspReversalMinDistancePercent(HdspSmoothedParameterProcessor.DEFAULT_REVERSAL_MIN_DISTANCE_PERCENT)
                .hdspLogDevicePosition(false)
                .hdspDevicePositionLogIntervalMs(HdspSmoothedParameterProcessor.DEFAULT_DEVICE_POSITION_LOG_INTERVAL_MS)
                .hdspDiagnosticTrace(false)
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
     * @return a message explaining why the app cannot connect to the Handy servers with this connection key, or null
     * when the key can be used
     */
    public static String validateConnectionKey(String connectionKey)
    {
        if (connectionKey == null || connectionKey.isBlank())
        {
            return "The connection key is required for the Wi-Fi connection. Open the Handy app (or"
                    + " handyfeeling.com), connect the Handy to Wi-Fi, and copy the connection key shown there.";
        }
        if (!connectionKey.trim().matches(CONNECTION_KEY_PATTERN))
        {
            return "The connection key is not valid. It should be the 5-64 character key of the Handy (letters and"
                    + " digits only).";
        }
        return null;
    }

    /**
     * @return the application ID used with the Handy servers, falling back to
     * {@link #DEFAULT_HANDY_APPLICATION_ID} when it is blank
     */
    public String effectiveHandyApplicationId()
    {
        return handyApplicationId == null || handyApplicationId.isBlank()
                ? DEFAULT_HANDY_APPLICATION_ID
                : handyApplicationId.trim();
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
        if (connection == ConnectionType.WIFI)
        {
            String error = validateConnectionKey(connectionKey);
            if (error != null)
            {
                return error;
            }
        }
        else if (connection == ConnectionType.INTIFACE)
        {
            String error = validateIntifaceAddress(intifaceAddress);
            if (error != null)
            {
                return error;
            }
        }
        return validateAvatarParameter(spsType, avatarParameter);
    }

    /**
     * Like {@link #toString()}, but the connection key and the application ID are masked: the settings are logged
     * (and users attach logs to bug reports) while the key is a secret of the device.
     */
    public String toLoggableString()
    {
        String text = toString();
        text = maskField(text, "connectionKey", connectionKey);
        return maskField(text, "handyApplicationId", handyApplicationId);
    }

    /**
     * Masks a secret for a log: only its first character is kept, followed by exactly three asterisks. The length
     * and the remaining characters are never shown, so a log cannot be used to reconstruct the secret.
     *
     * @return for example {@code N***}; {@code ***} for a blank secret
     */
    public static String maskSecret(String secret)
    {
        if (secret == null || secret.isBlank())
        {
            return "***";
        }
        if (secret.trim().length() == 1)
        {
            return secret.trim() + "***";
        }
        return secret.trim().substring(0, 2) + "***";
    }

    /**
     * Replaces exactly the value of one field in the record's {@code toString()}. Replacing the bare secret in the
     * whole text would also replace it in other fields (and would fail for a very short secret).
     */
    private static String maskField(String text, String field, String secret)
    {
        if (secret == null || secret.isBlank())
        {
            return text;
        }
        String masked = maskSecret(secret);
        return text.replace(field + "=" + secret, field + "=" + masked)
                .replace(field + "=" + secret.trim(), field + "=" + masked);
    }
}
