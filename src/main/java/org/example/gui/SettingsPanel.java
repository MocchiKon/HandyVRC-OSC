package org.example.gui;

import org.example.config.ConfigProperties;
import org.example.config.ConnectionType;
import org.example.config.InteractWith;
import org.example.diagnostics.DeviceProfile;
import org.example.processor.ParameterProcessorType;
import org.example.processor.SpsType;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import javax.swing.*;
import javax.swing.event.DocumentEvent;
import javax.swing.event.DocumentListener;
import java.awt.*;
import java.lang.reflect.RecordComponent;
import java.util.List;
import java.util.Objects;
import java.util.function.Consumer;
import java.util.function.UnaryOperator;

import static org.example.gui.SettingRows.*;

/**
 * The "Basic" and "Advanced" settings pages. Every change is reported right away (with the complete new settings)
 * to the change listener, which saves them.
 * <p>
 * The change is also logged, but after a short delay: a slider reports every step of a drag and only its final value
 * is useful. The Advanced page only shows the options of the processing mode selected on the Basic page.
 */
public class SettingsPanel extends JPanel
{
    private static final String BASIC = "basic";
    private static final String ADVANCED = "advanced";
    private static final String LOGS = "logs";
    private static final List<String> LOG_LEVELS = List.of("TRACE", "DEBUG", "INFO", "WARN", "ERROR");
    private static final Logger log = LoggerFactory.getLogger(SettingsPanel.class);
    /** A slider reports every pixel, so the changed settings are logged once the user stopped moving it. */
    private static final int SETTING_LOG_DELAY_MS = 350;
    private static final String CALIBRATION_TOOLTIP = "Sends known full-stroke and short slow moves, records the"
            + " device response in logs/diagnostics/ and writes the profile to profiles/. Takes about four minutes,"
            + " needs a direct Bluetooth connection, and temporarily stops the running session. The measured limits"
            + " are used automatically afterwards.";
    private static final String CALIBRATION_INTIFACE_TOOLTIP = "Calibration needs a Direct Bluetooth connection:"
            + " Intiface cannot report the slider position. Change Connection to Direct on the Basic tab.";

    private ConfigProperties config;
    /** The settings as they were when the last change was logged, used to find what changed. */
    private ConfigProperties loggedConfig;
    /** The newest calibration profile, shown in the speed fields when the measured limits are used. */
    private DeviceProfile deviceProfile;
    private final Consumer<ConfigProperties> changeListener;
    private final Runnable onCalibrate;
    private final Timer settingLogTimer = new Timer(SETTING_LOG_DELAY_MS, e -> logSettingChangesWhenIdle());
    /** True while the controls are set from the settings, so that doing so does not count as a change. */
    private boolean updating;

    // Basic
    private final SegmentedControl<ConnectionType> connection;
    private final WrappingText connectionHint = new WrappingText("", Theme.regular(12f), Theme.TEXT_MUTED);
    private final ModernTextField intifaceAddress = new ModernTextField("", 20);
    private final WrappingText intifaceAddressError = new WrappingText("", Theme.regular(12f), Theme.ERROR);
    private JPanel intifaceAddressRow;
    private final SegmentedControl<SpsType> spsType;
    private final SegmentedControl<InteractWith> interactWith;
    private final SegmentedControl<ParameterProcessorType> algorithm;
    private final ModernCheckBox customAvatarParameter = new ModernCheckBox("Use a custom avatar parameter");
    private final ModernTextField avatarParameter = new ModernTextField("", 20);
    private final WrappingText avatarParameterError = new WrappingText("", Theme.regular(12f), Theme.ERROR);
    /** What the user typed as custom parameter, restored when the check box is checked again. */
    private String customAvatarParameterText = "";
    private final ModernSlider strokeRange = new ModernSlider(0, 100, 1, 0, 100, 5);
    private final ModernSlider fullyPenetratedAt = new ModernSlider(10, 100, 1, 85);
    private final ToggleSwitch reverseMovement = new ToggleSwitch(false);
    private final ToggleSwitch useOscQuery = new ToggleSwitch(true);
    private final ModernTextField listenOnPort = new ModernTextField("", 6);
    private final JLabel listenOnPortHint = new JLabel("Picked automatically while OSCQuery is on");

    // Advanced
    private final WrappingText advancedModeHint = new WrappingText("", Theme.regular(12.5f), Theme.TEXT_MUTED);
    private final RoundedPanel smoothedSection = section("Smoothed mode");
    private final RoundedPanel directSection = section("Direct mode");
    private final ModernSlider speedChangeThreshold = new ModernSlider(5, 100, 1, 15);
    private final ModernSlider speedStopThreshold = new ModernSlider(1, 50, 1, 15);
    private final ModernSlider divergenceThreshold = new ModernSlider(5, 60, 1, 20);
    private final ModernSlider speedMeasureWindow = new ModernSlider(50, 500, 10, 100);
    private final ToggleSwitch useDeviceProfile = new ToggleSwitch(true);
    private final ModernSlider minSliderSpeed = new ModernSlider(0, 100, 1, 30);
    private final ModernSlider maxSliderSpeed = new ModernSlider(0, 2000, 10, 0);
    /** Tenths of a percent, so that half percent steps are possible. */
    private final ModernSlider reversalMinDistance = new ModernSlider(0, 100, 5, 20);
    private final ToggleSwitch logDevicePosition = new ToggleSwitch(false);
    private final ModernSlider deviceLogInterval = new ModernSlider(20, 2000, 10, 200);
    private final ToggleSwitch diagnosticTrace = new ToggleSwitch(false);
    /** The two diagnostic rows are hidden until position logging is on. */
    private JComponent deviceLogIntervalRow;
    private JComponent deviceLogIntervalSpacer;
    private JComponent diagnosticTraceRow;
    private JComponent diagnosticTraceSpacer;
    /** Button moved here from Test mode; only shown for Smoothed mode with the measured profile enabled. */
    private ModernButton calibrateButton;
    private JComponent calibrateButtonRow;
    private JComponent calibrateButtonSpacer;
    private final ModernSlider minimalValueChange = new ModernSlider(0, 20, 1, 3);
    private final ToggleSwitch pauseOnStarving = new ToggleSwitch(true);
    private final ModernSlider sendMessageEvery = new ModernSlider(0, 200, 5, 0);
    private final ToggleSwitch checkForUpdates = new ToggleSwitch(true);
    private final SegmentedControl<String> logLevel = new SegmentedControl<>(List.of(
            new SegmentedControl.Option<>("TRACE", "Trace",
                    "Everything, including every value received from VRChat. Very detailed; slows the app down a little."),
            new SegmentedControl.Option<>("DEBUG", "Debug",
                    "Details of what the selected mode does (for example every command sent to the device). Useful"
                            + " for tuning the Advanced settings."),
            new SegmentedControl.Option<>("INFO", "Info", "Normal messages: connection, settings, problems. Default."),
            new SegmentedControl.Option<>("WARN", "Warn", "Only warnings and errors."),
            new SegmentedControl.Option<>("ERROR", "Error", "Only errors.")),
            "INFO", true);

    private final CardLayout pages = new CardLayout();
    private final JPanel pageHolder = new JPanel(pages);

    public SettingsPanel(ConfigProperties initialConfig, Consumer<ConfigProperties> changeListener,
                         Runnable onCalibrate)
    {
        super(new BorderLayout(0, 12));
        this.config = initialConfig;
        this.changeListener = changeListener;
        this.onCalibrate = onCalibrate;
        this.settingLogTimer.setRepeats(false);
        setOpaque(false);

        connection = new SegmentedControl<>(List.of(
                new SegmentedControl.Option<>(ConnectionType.DIRECT, ConnectionType.DIRECT.getLabel(),
                        "Recommended. The app connects to your Handy over Bluetooth itself. Lowest latency, and the"
                                + " stroke range is set on the device."),
                new SegmentedControl.Option<>(ConnectionType.INTIFACE, ConnectionType.INTIFACE.getLabel(),
                        "The app sends the movement to Intiface Central, which connects to the device. Start"
                                + " Intiface Central and its server before pressing Start. Other strokers Intiface"
                                + " supports might work as well, but only the Handy is tested.")),
                initialConfig.connection(), false);
        spsType = new SegmentedControl<>(List.of(
                new SegmentedControl.Option<>(SpsType.PENETRATOR, "Penetrator",
                        "Your avatar has an SPS penetrator. The device follows how deep it is inside an orifice."),
                new SegmentedControl.Option<>(SpsType.ORIFICE, "Orifice",
                        "Your avatar has an SPS orifice (can be used to turn The Handy into thrusting machine). The"
                                + " device follows how deep a penetrator is inside it; the penetrator length is"
                                + " detected automatically.")),
                initialConfig.spsType(), false);
        interactWith = new SegmentedControl<>(List.of(
                new SegmentedControl.Option<>(InteractWith.SELF, "Self",
                        "Only your own body moves the device, for example your penetrator in your own orifice."
                                + " Handy for trying it out alone."),
                new SegmentedControl.Option<>(InteractWith.OTHERS, "Others",
                        "Only other players move the device. Default."),
                new SegmentedControl.Option<>(InteractWith.BOTH, "Both",
                        "You and other players move the device.")),
                initialConfig.interactWith(), false);
        algorithm = new SegmentedControl<>(List.of(
                new SegmentedControl.Option<>(ParameterProcessorType.HDSP, ParameterProcessorType.HDSP.getLabel(),
                        "Every movement is sent to the device the moment it arrives (1:1). Lowest latency, but"
                                + " jitter of your avatar is passed on to the device as well."),
                new SegmentedControl.Option<>(ParameterProcessorType.HDSP_SMOOTHED, ParameterProcessorType.HDSP_SMOOTHED.getLabel(),
                        "Recommended. Measures how fast and in which direction you move and moves the device at that"
                                + " speed, stopping where you stop. Smooth movement without overshooting.")),
                initialConfig.processingAlgorithm(), false);

        var tabs = new SegmentedControl<>(List.of(
                new SegmentedControl.Option<>(BASIC, "Basic", "The settings most people need."),
                new SegmentedControl.Option<>(ADVANCED, "Advanced",
                        "Fine tuning of the selected mode. The defaults work well for most people."),
                new SegmentedControl.Option<>(LOGS, "Logs",
                        "What the app is doing, as it happens. Useful when something does not work.")),
                BASIC, true);
        tabs.setSelectionListener(page -> pages.show(pageHolder, page));
        add(tabs, BorderLayout.NORTH);

        pageHolder.setOpaque(false);
        pageHolder.add(scrollable(buildBasicPage()), BASIC);
        pageHolder.add(scrollable(buildAdvancedPage()), ADVANCED);
        pageHolder.add(new LogsPanel(() -> config.logLevel()), LOGS);
        add(pageHolder, BorderLayout.CENTER);

        installListeners();
        installSliderCommitListeners();
        setConfig(initialConfig);
        refreshDeviceProfile();
    }

    public ConfigProperties getConfig()
    {
        return config;
    }

    /** Shows the given settings without reporting them as a change. */
    public void setConfig(ConfigProperties newConfig)
    {
        settingLogTimer.stop();
        config = newConfig;
        updating = true;
        try
        {
            connection.setSelected(newConfig.connection());
            intifaceAddress.setText(newConfig.intifaceAddress());
            intifaceAddress.setCaretPosition(0);
            spsType.setSelected(newConfig.spsType());
            interactWith.setSelected(newConfig.interactWith());
            algorithm.setSelected(newConfig.processingAlgorithm());
            customAvatarParameter.setSelected(newConfig.customAvatarParameter());
            if (newConfig.customAvatarParameter())
            {
                customAvatarParameterText = newConfig.avatarParameter();
            }
            avatarParameter.setText(newConfig.avatarParameter());
            avatarParameter.setCaretPosition(0);
            strokeRange.setValues(Math.round(orDefault(newConfig.sliderMin(), 0f) * 100),
                    Math.round(orDefault(newConfig.sliderMax(), 1f) * 100));
            fullyPenetratedAt.setValue(Math.round(orDefault(newConfig.fullyPenetratedAtValue(),
                    ConfigProperties.DEFAULT_FULLY_PENETRATED_AT_VALUE)));
            reverseMovement.setSelected(newConfig.reverseMovement());
            useOscQuery.setSelected(newConfig.useOscQuery());
            listenOnPort.setText(String.valueOf(newConfig.listenOnPort()));
            listenOnPort.setInvalid(false);

            ConfigProperties defaults = ConfigProperties.defaults();
            speedChangeThreshold.setValue(orDefault(newConfig.hdspSpeedChangeThresholdPercent(), defaults.hdspSpeedChangeThresholdPercent()));
            speedStopThreshold.setValue(orDefault(newConfig.hdspSpeedStopThresholdPercent(), defaults.hdspSpeedStopThresholdPercent()));
            divergenceThreshold.setValue(orDefault(newConfig.hdspDivergenceThresholdPercent(), defaults.hdspDivergenceThresholdPercent()));
            speedMeasureWindow.setValue(orDefault(newConfig.hdspSpeedMeasureWindowMs(), defaults.hdspSpeedMeasureWindowMs()));
            useDeviceProfile.setSelected(newConfig.hdspUseDeviceProfile());
            minSliderSpeed.setValue(orDefault(newConfig.hdspMinSliderSpeed(), defaults.hdspMinSliderSpeed()));
            maxSliderSpeed.setValue(orDefault(newConfig.hdspMaxSliderSpeed(), defaults.hdspMaxSliderSpeed()));
            updateProfileControls();
            reversalMinDistance.setValue(Math.round(orDefault(newConfig.hdspReversalMinDistancePercent(),
                    defaults.hdspReversalMinDistancePercent()) * 10));
            logDevicePosition.setSelected(newConfig.hdspLogDevicePosition());
            deviceLogInterval.setValue(orDefault(newConfig.hdspDevicePositionLogIntervalMs(),
                    defaults.hdspDevicePositionLogIntervalMs()));
            diagnosticTrace.setSelected(newConfig.hdspDiagnosticTrace());
            minimalValueChange.setValue(newConfig.minimalValueChange());
            pauseOnStarving.setSelected(newConfig.pauseOnStarving());
            sendMessageEvery.setValue(newConfig.sendMessageEveryMs());
            checkForUpdates.setSelected(newConfig.checkForUpdates());
            String level = newConfig.logLevel() == null ? "INFO" : newConfig.logLevel().toUpperCase(java.util.Locale.ROOT);
            logLevel.setSelected(LOG_LEVELS.contains(level) ? level : "INFO");
        }
        finally
        {
            updating = false;
        }
        refreshDependentControls();
        loggedConfig = config;
        updateProfileDisplay();
    }

    private void update(UnaryOperator<ConfigProperties.ConfigPropertiesBuilder> change)
    {
        if (updating)
        {
            return;
        }
        config = change.apply(config.toBuilder()).build();
        refreshDependentControls();
        changeListener.accept(config);
        settingLogTimer.restart();
    }

    /**
     * Logs any change that is not a slider drag. Sliders are committed on mouse release (or key press), so that
     * holding a slider still does not log a value that is not final yet.
     */
    private void logSettingChangesWhenIdle()
    {
        if (sliders().stream().anyMatch(ModernSlider::isDragging))
        {
            settingLogTimer.restart();
            return;
        }
        logSettingChanges(loggedConfig, config, "Setting changed");
        loggedConfig = config;
    }

    /** Called when a slider is released (or changed with the keyboard): logs its final value immediately. */
    private void commitSliderChange()
    {
        settingLogTimer.stop();
        logSettingChanges(loggedConfig, config, "Setting changed");
        loggedConfig = config;
    }

    private void logSettingChanges(ConfigProperties before, ConfigProperties after, String prefix)
    {
        if (before == null || before == after)
        {
            return;
        }
        for (RecordComponent component : ConfigProperties.class.getRecordComponents())
        {
            try
            {
                Object oldValue = component.getAccessor().invoke(before);
                Object newValue = component.getAccessor().invoke(after);
                if (!Objects.equals(oldValue, newValue))
                {
                    log.info("{}: {} = {} (was {})", prefix, component.getName(), settingValue(newValue),
                            settingValue(oldValue));
                }
            }
            catch (ReflectiveOperationException e)
            {
                log.debug("Could not compare the setting {}: {}", component.getName(), e.getMessage());
            }
        }
    }

    private static String settingValue(Object value)
    {
        if (value == null)
        {
            return "null";
        }
        if (value instanceof String text)
        {
            return text.isBlank() ? "(empty)" : text;
        }
        return String.valueOf(value);
    }

    /** Updates everything that depends on other settings (visible options, defaults, validation). */
    private void refreshDependentControls()
    {
        boolean intiface = config.connection() == ConnectionType.INTIFACE;
        connectionHint.setText(intiface
                ? "Start Intiface Central and its server before pressing Start."
                : "Recommended: connects to your Handy over Bluetooth.");
        intifaceAddressRow.setVisible(intiface);
        String addressError = intiface ? ConfigProperties.validateIntifaceAddress(config.intifaceAddress()) : null;
        intifaceAddressError.setText(addressError == null ? "" : addressError);
        intifaceAddressError.setVisible(addressError != null);
        intifaceAddress.setInvalid(addressError != null);

        boolean smoothed = config.processingAlgorithm() == ParameterProcessorType.HDSP_SMOOTHED;
        smoothedSection.setVisible(smoothed);
        directSection.setVisible(!smoothed);
        updateDiagnosticsVisibility();
        updateCalibrateVisibility();
        advancedModeHint.setText("Options of %s mode (the mode is selected on the Basic tab)."
                .formatted(config.processingAlgorithm().getLabel()));

        // With OSCQuery the app picks a free port itself; the configured one is only the fallback when OSCQuery
        // cannot be started, so it is only editable when OSCQuery is off
        listenOnPort.setEnabled(!config.useOscQuery());
        listenOnPortHint.setVisible(config.useOscQuery());

        boolean custom = customAvatarParameter.isSelected();
        avatarParameter.setEnabled(custom);
        // A custom parameter decides on its own whose contacts it follows
        interactWith.setEnabled(!custom);
        if (!custom)
        {
            String defaultParameter = defaultAvatarParameter();
            if (!avatarParameter.getText().equals(defaultParameter))
            {
                boolean wasUpdating = updating;
                updating = true;
                avatarParameter.setText(defaultParameter);
                avatarParameter.setCaretPosition(0);
                updating = wasUpdating;
            }
        }
        String error = custom ? ConfigProperties.validateAvatarParameter(config.spsType(), config.avatarParameter()) : null;
        avatarParameterError.setText(error == null ? "" : error);
        avatarParameterError.setVisible(error != null);
        avatarParameter.setInvalid(error != null);
        revalidate();
        repaint();
    }

    private void installListeners()
    {
        connection.setSelectionListener(value -> update(b -> b.connection(value)));
        onTextChange(intifaceAddress, () -> update(b -> b.intifaceAddress(intifaceAddress.getText().trim())));
        spsType.setSelectionListener(value -> update(b -> b.spsType(value)
                .avatarParameter(customAvatarParameter.isSelected() ? config.avatarParameter()
                        : ConfigProperties.defaultAvatarParameter(value, config.interactWith()))));
        interactWith.setSelectionListener(value -> update(b -> b.interactWith(value)
                .avatarParameter(customAvatarParameter.isSelected() ? config.avatarParameter()
                        : ConfigProperties.defaultAvatarParameter(config.spsType(), value))));
        algorithm.setSelectionListener(value -> update(b -> b.processingAlgorithm(value)));

        customAvatarParameter.addItemListener(e ->
        {
            if (updating)
            {
                return;
            }
            if (customAvatarParameter.isSelected())
            {
                avatarParameter.setEnabled(true);
                // Start from the default, which usually only needs a small change (for example the socket name)
                String text = customAvatarParameterText.isBlank()
                        ? defaultAvatarParameter()
                        : customAvatarParameterText;
                updating = true;
                avatarParameter.setText(text);
                updating = false;
                avatarParameter.requestFocusInWindow();
                applyCustomAvatarParameter();
            }
            else
            {
                customAvatarParameterText = avatarParameter.getText().trim();
                update(b -> b.customAvatarParameter(false).avatarParameter(defaultAvatarParameter()));
            }
        });
        onTextChange(avatarParameter, () ->
        {
            if (customAvatarParameter.isSelected())
            {
                customAvatarParameterText = avatarParameter.getText().trim();
                applyCustomAvatarParameter();
            }
        });

        strokeRange.addChangeListener(e -> update(b -> b
                .sliderMin(strokeRange.getLowValue() / 100f)
                .sliderMax(strokeRange.getHighValue() / 100f)));
        fullyPenetratedAt.addChangeListener(e -> update(b -> b.fullyPenetratedAtValue((float) fullyPenetratedAt.getValue())));
        reverseMovement.addChangeListener(e -> update(b -> b.reverseMovement(reverseMovement.isSelected())));
        useOscQuery.addChangeListener(e -> update(b -> b.useOscQuery(useOscQuery.isSelected())));
        onTextChange(listenOnPort, () ->
        {
            Integer port = parsePort(listenOnPort.getText());
            listenOnPort.setInvalid(port == null);
            if (port != null)
            {
                update(b -> b.listenOnPort(port));
            }
        });

        speedChangeThreshold.addChangeListener(e -> update(b -> b.hdspSpeedChangeThresholdPercent(speedChangeThreshold.getValue())));
        speedStopThreshold.addChangeListener(e -> update(b -> b.hdspSpeedStopThresholdPercent(speedStopThreshold.getValue())));
        divergenceThreshold.addChangeListener(e -> update(b -> b.hdspDivergenceThresholdPercent(divergenceThreshold.getValue())));
        speedMeasureWindow.addChangeListener(e -> update(b -> b.hdspSpeedMeasureWindowMs(speedMeasureWindow.getValue())));
        useDeviceProfile.addChangeListener(e ->
        {
            update(b -> b.hdspUseDeviceProfile(useDeviceProfile.isSelected()));
            refreshDeviceProfile();
        });
        minSliderSpeed.addChangeListener(e -> update(b -> b.hdspMinSliderSpeed(minSliderSpeed.getValue())));
        maxSliderSpeed.addChangeListener(e -> update(b -> b.hdspMaxSliderSpeed(maxSliderSpeed.getValue())));
        reversalMinDistance.addChangeListener(e -> update(b -> b.hdspReversalMinDistancePercent(reversalMinDistance.getValue() / 10f)));
        logDevicePosition.addChangeListener(e ->
        {
            update(b -> b.hdspLogDevicePosition(logDevicePosition.isSelected()));
            if (!logDevicePosition.isSelected() && diagnosticTrace.isSelected())
            {
                diagnosticTrace.setSelected(false);
            }
            updateDiagnosticsVisibility();
        });
        deviceLogInterval.addChangeListener(e -> update(b -> b.hdspDevicePositionLogIntervalMs(deviceLogInterval.getValue())));
        diagnosticTrace.addChangeListener(e -> update(b -> b.hdspDiagnosticTrace(diagnosticTrace.isSelected())));
        minimalValueChange.addChangeListener(e -> update(b -> b.minimalValueChange(minimalValueChange.getValue())));
        pauseOnStarving.addChangeListener(e -> update(b -> b.pauseOnStarving(pauseOnStarving.isSelected())));
        sendMessageEvery.addChangeListener(e -> update(b -> b.sendMessageEveryMs(sendMessageEvery.getValue())));
        checkForUpdates.addChangeListener(e -> update(b -> b.checkForUpdates(checkForUpdates.isSelected())));
        logLevel.setSelectionListener(value -> update(b -> b.logLevel(value)));
    }

    /** A blank custom parameter means "use the default", so the check box alone changes nothing. */
    private void applyCustomAvatarParameter()
    {
        String text = avatarParameter.getText().trim();
        update(b -> text.isEmpty()
                ? b.customAvatarParameter(false).avatarParameter(defaultAvatarParameter())
                : b.customAvatarParameter(true).avatarParameter(text));
    }

    private String defaultAvatarParameter()
    {
        return ConfigProperties.defaultAvatarParameter(config.spsType(), config.interactWith());
    }

    private JComponent buildBasicPage()
    {
        var page = pageContainer();

        var device = section("Device");
        var connectionRow = verticalRow();
        connectionRow.add(title("Connection"));
        connectionRow.add(Box.createVerticalStrut(8));
        connectionRow.add(connection);
        connectionRow.add(Box.createVerticalStrut(6));
        connectionRow.add(connectionHint);
        for (Component component : connectionRow.getComponents())
        {
            ((JComponent) component).setAlignmentX(Component.LEFT_ALIGNMENT);
        }
        applyTooltip(connectionRow, Theme.tooltip("How the app reaches your device.\n\n" + connection.describeOptions()));
        addRow(device, connectionRow);
        intifaceAddress.setFont(Theme.font(Font.PLAIN, 12.5f));
        intifaceAddressError.setVisible(false);
        // The row brings its own gap instead of using addRow, so that nothing is left over while it is hidden
        intifaceAddressRow = verticalRow();
        intifaceAddressRow.add(Box.createVerticalStrut(12));
        intifaceAddressRow.add(title("Intiface address"));
        intifaceAddressRow.add(Box.createVerticalStrut(8));
        intifaceAddressRow.add(intifaceAddress);
        intifaceAddressRow.add(Box.createVerticalStrut(6));
        intifaceAddressRow.add(intifaceAddressError);
        for (Component component : intifaceAddressRow.getComponents())
        {
            ((JComponent) component).setAlignmentX(Component.LEFT_ALIGNMENT);
        }
        applyTooltip(intifaceAddressRow, Theme.tooltip("Address of the Intiface Central server, as Intiface Central"
                + " shows it. " + ConfigProperties.DEFAULT_INTIFACE_ADDRESS + " unless you changed the server port"
                + " there."));
        intifaceAddressRow.setAlignmentX(Component.LEFT_ALIGNMENT);
        device.add(intifaceAddressRow);
        page.add(device);
        page.add(Box.createVerticalStrut(12));

        var avatar = section("Avatar");
        addRow(avatar, controlRow("SPS type",
                "Which SPS component of your avatar moves the device: your own penetrator, or the orifice someone"
                        + " else penetrates.", spsType));
        addRow(avatar, controlRow("Interact with",
                "Whose SPS contacts move the device: only you, only other players, or both. Not used with a custom"
                        + " avatar parameter.", interactWith));
        avatarParameter.setFont(Theme.font(Font.PLAIN, 12.5f));
        avatarParameterError.setVisible(false);
        var avatarParameterRow = verticalRow();
        avatarParameterRow.add(title("Avatar parameter"));
        avatarParameterRow.add(Box.createVerticalStrut(6));
        avatarParameterRow.add(customAvatarParameter);
        avatarParameterRow.add(Box.createVerticalStrut(8));
        avatarParameterRow.add(avatarParameter);
        avatarParameterRow.add(Box.createVerticalStrut(6));
        avatarParameterRow.add(avatarParameterError);
        for (Component component : avatarParameterRow.getComponents())
        {
            ((JComponent) component).setAlignmentX(Component.LEFT_ALIGNMENT);
        }
        applyTooltip(avatarParameterRow, Theme.tooltip(
                "OSC address of the SPS parameter that drives the device. It follows 'SPS type' and 'Interact"
                        + " with', so you only need 'Use a custom avatar parameter' when your avatar uses a different"
                        + " parameter, for example:\n"
                        + "Penetrator: " + ConfigProperties.DEFAULT_PENETRATOR_PARAMETER + "\n"
                        + "Orifice: " + ConfigProperties.DEFAULT_ORIFICE_PARAMETER + "\n"
                        + "'*' matches any name. In Orifice mode the parameter has to end with 'NewRoot', the matching"
                        + " 'NewTip' parameter is found automatically."));
        addRow(avatar, avatarParameterRow);
        page.add(avatar);
        page.add(Box.createVerticalStrut(12));

        var movement = section("Movement");
        addRow(movement, controlRow("Mode",
                "How the movement of your avatar is turned into device movement.",
                algorithm));
        addRow(movement, rangeRow("Stroke range",
                "The part of the stroke the device uses: drag the left handle to raise the bottom, the right handle"
                        + " to lower the top. Your whole movement is mapped onto this range.",
                strokeRange, (low, high) -> "%d%% – %d%%".formatted(low, high)));
        addRow(movement, sliderRow("Fully penetrated at",
                "Penetration that counts as fully penetrated, so that you reach the bottom of the stroke without"
                        + " having to go all the way in. With 50% a penetration of 25% already moves the device"
                        + " halfway. 100% turns this off. Recommended to set it to ~85% to avoid movement reversal when going past penetrator base.",
                fullyPenetratedAt, value -> value + "%"));
        addRow(movement, toggleRow("Reverse movement", null,
                "Turns the movement upside down: the device moves up as you go in and down as you pull out,"
                        + " instead of the other way round. Useful when the device is mounted upside down.",
                reverseMovement));
        page.add(movement);
        page.add(Box.createVerticalStrut(12));

        var osc = section("VRChat connection");
        addRow(osc, toggleRow("Use OSCQuery", "Recommended: VRChat finds the app on its own",
                "The app announces itself to VRChat (OSCQuery over mDNS) and receives OSC on a free port it picks"
                        + " itself, so it keeps working when another OSC app already uses the OSC port, and several"
                        + " OSC apps can run at the same time. Needs VRChat 2023.3.1 or newer. If VRChat does not"
                        + " send anything after turning this on, restart VRChat.",
                useOscQuery));
        var portHolder = new JPanel(new FlowLayout(FlowLayout.LEFT, 0, 0));
        portHolder.setOpaque(false);
        portHolder.add(listenOnPort);
        portHolder.add(Box.createHorizontalStrut(12));
        listenOnPortHint.setFont(Theme.regular(12f));
        listenOnPortHint.setForeground(Theme.TEXT_MUTED);
        portHolder.add(listenOnPortHint);
        addRow(osc, controlRow("OSC port",
                "Port VRChat sends OSC messages to (9001 unless you changed it in VRChat). Only editable while"
                        + " 'Use OSCQuery' is off: with OSCQuery the app picks a free port itself and tells VRChat about"
                        + " it, and this port is only used if OSCQuery cannot be started.",
                portHolder));
        page.add(osc);
        page.add(Box.createVerticalStrut(12));
        page.add(restoreDefaultsButton(defaults -> config.toBuilder()
                .connection(defaults.connection())
                .intifaceAddress(defaults.intifaceAddress())
                .spsType(defaults.spsType())
                .interactWith(defaults.interactWith())
                .processingAlgorithm(defaults.processingAlgorithm())
                .avatarParameter(defaults.avatarParameter())
                .customAvatarParameter(false)
                .sliderMin(defaults.sliderMin())
                .sliderMax(defaults.sliderMax())
                .fullyPenetratedAtValue(defaults.fullyPenetratedAtValue())
                .reverseMovement(defaults.reverseMovement())
                .useOscQuery(defaults.useOscQuery())
                .listenOnPort(defaults.listenOnPort())
                .build()));
        return page;
    }

    private JComponent buildAdvancedPage()
    {
        var page = pageContainer();

        advancedModeHint.setBorder(BorderFactory.createEmptyBorder(0, 4, 10, 4));
        advancedModeHint.setAlignmentX(Component.LEFT_ALIGNMENT);
        page.add(advancedModeHint);

        addRow(smoothedSection, sliderRow("Speed change threshold",
                "How much your speed has to change before the device speed is corrected. Higher values send fewer"
                        + " corrections (smoother, but speed changes are followed later), lower values follow your"
                        + " speed more closely.",
                speedChangeThreshold, value -> value + "%"));
        addRow(smoothedSection, sliderRow("Stop threshold",
                "Speed (in % of the fastest speed of the current movement) below which you count as stopped, which"
                        + " stops the device where you stopped. Movements slower than 4%/s always count as a stop.",
                speedStopThreshold, value -> value + "%"));
        addRow(smoothedSection, sliderRow("Divergence threshold",
                "How far (in % of the stroke) the device may fall behind you before it is brought back, even when"
                        + " your speed did not change enough to cross the speed change threshold.",
                divergenceThreshold, value -> value + "%"));
        addRow(smoothedSection, sliderRow("Speed measure window",
                "Time span your speed is measured over. Shorter follows speed changes faster but reacts to jitter"
                        + " more, longer is steadier but lags behind. 100 ms is a good default for fast strokes;"
                        + " raise it only if a slow movement makes the device twitch.",
                speedMeasureWindow, value -> value + " ms"));
        addRow(smoothedSection, toggleRow("Use calibrated device limits", null,
                "Reads the newest device-profile JSON written by Calibrate device and uses its measured minimum"
                        + " speed, single maximum speed and motion start. This is what makes a Handy 1, a Handy 2 Pro"
                        + " or a worn Handy 1 drive correctly without copying numbers. Turn it off to use the two"
                        + " speed values below.",
                useDeviceProfile));
        calibrateButton = new ModernButton("Calibrate device", ModernButton.Style.GHOST);
        calibrateButton.setToolTipText(Theme.tooltip(CALIBRATION_TOOLTIP));
        calibrateButton.addActionListener(e -> confirmCalibration());
        calibrateButtonRow = controlRow("Device calibration",
                "Measure the device so the app can use its real minimum and maximum speed.", calibrateButton);
        calibrateButtonSpacer = addRow(smoothedSection, calibrateButtonRow);
        addRow(smoothedSection, sliderRow("Minimum device speed",
                "Used only when 'Use calibrated device limits' is off or calibration wasn't performed. Slowest speed the device moves with. A Handy 1"
                        + " does not move much slower than about 33 mm/s, so slower movements are followed in short"
                        + " pulses instead. A Handy 2 Pro with overclocking can move slower; its calibration measures"
                        + " that automatically.",
                minSliderSpeed, value -> value == 0 ? "Off" : value + " mm/s"));
        addRow(smoothedSection, sliderRow("Maximum device speed",
                "Used only when 'Use calibrated device limits' is off or calibration wasn't performed. Highest speed the device can move (mm/s); 0"
                        + " leaves it unlimited. A Handy 1 measures about 370-430 mm/s, a Handy 2 or 2 Pro is higher;"
                        + " the calibration measures the single lower-direction limit automatically.",
                maxSliderSpeed, value -> value == 0 ? "No limit" : value + " mm/s"));
        addRow(smoothedSection, sliderRow("Reversal distance",
                "How far (in % of the stroke) you have to move back before it counts as a change of direction."
                        + " Until then the device just stops, so the jitter of a slow movement cannot make it reverse."
                        + " Raise it if the device twitches back and forth while you move slowly.",
                reversalMinDistance, value -> "%.1f%%".formatted(value / 10f)));
        addRow(smoothedSection, toggleRow("Log device position", "Diagnostics, leave off for normal use",
                "Logs the position the device reports (log level DEBUG) next to the position the app believes it"
                        + " is at, with the active move and the newest input value. Every report is an extra Bluetooth"
                        + " request. Direct connection only: Intiface cannot report the position.",
                logDevicePosition));
        deviceLogIntervalRow = sliderRow("Device log interval",
                "How often the device position is read while logging. Each read is a Bluetooth round trip (about"
                        + " 100 ms), so the time between two samples is this interval plus that round trip. A short"
                        + " interval adds traffic and can delay the movement commands; use 50 ms only for a short"
                        + " diagnostic run. 200 ms is the normal setting.",
                deviceLogInterval, value -> value + " ms");
        deviceLogIntervalSpacer = addRow(smoothedSection, deviceLogIntervalRow);
        diagnosticTraceRow = toggleRow("Write diagnostic trace", "Structured data for analysis",
                "Writes values, commands, device samples and Bluetooth notifications to a JSONL file in"
                        + " logs/diagnostics/. The file is easier to analyze than the text log and is only written"
                        + " while this is on.",
                diagnosticTrace);
        diagnosticTraceSpacer = addRow(smoothedSection, diagnosticTraceRow);
        page.add(smoothedSection);

        addRow(directSection, sliderRow("Minimal value change",
                "Smallest change of the position (in % of the stroke) that is sent to the device. Higher values"
                        + " filter out more jitter but make small movements coarser.",
                minimalValueChange, value -> value + "%"));
        addRow(directSection, toggleRow("Pause on starving", "Stop at every position instead of moving on (on by default)",
                "Direct mode only. The device stops when it reaches a position, instead of carrying on while it waits"
                        + " for the next one. Avoids overshooting, but the movement can feel choppier.",
                pauseOnStarving));
        page.add(directSection);
        page.add(Box.createVerticalStrut(12));

        var general = section("General");
        addRow(general, sliderRow("Send interval",
                "Minimum time between two commands sent to the device. At least 20 ms are always kept to not flood"
                        + " the device connection. In Smoothed mode this only spaces out speed corrections, stops"
                        + " are always sent right away.",
                sendMessageEvery, value -> value <= 20 ? "20 ms (minimum)" : value + " ms"));
        addRow(general, toggleRow("Check for updates", null,
                "Checks GitHub on startup whether a newer version was released and lets you know. The check runs in"
                        + " the background and never blocks the app.",
                checkForUpdates));
        addRow(general, controlRow("Logging level",
                "How detailed the messages on the Logs tab and in the log files are. Applies right away.",
                logLevel));
        page.add(general);
        page.add(Box.createVerticalStrut(12));
        page.add(restoreDefaultsButton(defaults -> config.toBuilder()
                .hdspSpeedChangeThresholdPercent(defaults.hdspSpeedChangeThresholdPercent())
                .hdspSpeedStopThresholdPercent(defaults.hdspSpeedStopThresholdPercent())
                .hdspDivergenceThresholdPercent(defaults.hdspDivergenceThresholdPercent())
                .hdspSpeedMeasureWindowMs(defaults.hdspSpeedMeasureWindowMs())
                .hdspUseDeviceProfile(defaults.hdspUseDeviceProfile())
                .hdspMinSliderSpeed(defaults.hdspMinSliderSpeed())
                .hdspMaxSliderSpeed(defaults.hdspMaxSliderSpeed())
                .hdspReversalMinDistancePercent(defaults.hdspReversalMinDistancePercent())
                .hdspLogDevicePosition(defaults.hdspLogDevicePosition())
                .hdspDevicePositionLogIntervalMs(defaults.hdspDevicePositionLogIntervalMs())
                .hdspDiagnosticTrace(defaults.hdspDiagnosticTrace())
                .minimalValueChange(defaults.minimalValueChange())
                .pauseOnStarving(defaults.pauseOnStarving())
                .sendMessageEveryMs(defaults.sendMessageEveryMs())
                .checkForUpdates(defaults.checkForUpdates())
                .logLevel(defaults.logLevel())
                .build()));
        return page;
    }

    /** The log interval and the trace are only useful (and only shown) while the device position is logged. */
    private void updateDiagnosticsVisibility()
    {
        boolean visible = logDevicePosition.isSelected();
        boolean changed = setRowVisible(deviceLogIntervalRow, deviceLogIntervalSpacer, visible);
        changed |= setRowVisible(diagnosticTraceRow, diagnosticTraceSpacer, visible);
        if (changed && smoothedSection.getParent() != null)
        {
            smoothedSection.getParent().revalidate();
            smoothedSection.getParent().repaint();
        }
    }

    private static boolean setRowVisible(JComponent row, JComponent spacer, boolean visible)
    {
        boolean changed = false;
        if (row != null && row.isVisible() != visible)
        {
            row.setVisible(visible);
            changed = true;
        }
        if (spacer != null && spacer.isVisible() != visible)
        {
            spacer.setVisible(visible);
            changed = true;
        }
        return changed;
    }

    /** All sliders whose final value is logged when the user releases them. */
    private List<ModernSlider> sliders()
    {
        return List.of(strokeRange, fullyPenetratedAt, speedChangeThreshold, speedStopThreshold, divergenceThreshold,
                speedMeasureWindow, minSliderSpeed, maxSliderSpeed, reversalMinDistance, deviceLogInterval,
                minimalValueChange, sendMessageEvery);
    }

    private void installSliderCommitListeners()
    {
        for (ModernSlider slider : sliders())
        {
            slider.addCommitListener(this::commitSliderChange);
        }
    }

    /** Loads the newest profile and shows its values in the speed fields while the measured limits are used. */
    public void refreshDeviceProfile()
    {
        deviceProfile = DeviceProfile.loadLatest("").orElse(null);
        updateProfileDisplay();
    }

    /**
     * Shows the profile values in the two speed fields while "Use calibrated device limits" is on. The values are
     * display-only; the manual values stay in the settings and are restored when the toggle is turned off.
     */
    private void updateProfileDisplay()
    {
        boolean override = useDeviceProfile.isSelected() && deviceProfile != null;
        boolean wasUpdating = updating;
        updating = true;
        try
        {
            ConfigProperties defaults = ConfigProperties.defaults();
            if (override)
            {
                minSliderSpeed.setValue(Math.clamp((int) Math.round(deviceProfile.plannedMinMmPerSecond()),
                        minSliderSpeed.getMinimumValue(), minSliderSpeed.getMaximumValue()));
                maxSliderSpeed.setValue(Math.clamp((int) Math.round(deviceProfile.plannedMaxMmPerSecond()),
                        maxSliderSpeed.getMinimumValue(), maxSliderSpeed.getMaximumValue()));
            }
            else
            {
                minSliderSpeed.setValue(orDefault(config.hdspMinSliderSpeed(), defaults.hdspMinSliderSpeed()));
                maxSliderSpeed.setValue(orDefault(config.hdspMaxSliderSpeed(), defaults.hdspMaxSliderSpeed()));
            }
        }
        finally
        {
            updating = wasUpdating;
        }
        updateProfileControls();
    }

    /** The two speed values are manual only when there is no measured profile to show. */
    private void updateProfileControls()
    {
        boolean manual = !useDeviceProfile.isSelected() || deviceProfile == null;
        minSliderSpeed.setEnabled(manual);
        maxSliderSpeed.setEnabled(manual);
    }

    /** The calibration is only offered for Smoothed mode with the measured limits enabled. */
    private void updateCalibrateVisibility()
    {
        if (calibrateButton == null)
        {
            return;
        }
        boolean visible = config.processingAlgorithm() == ParameterProcessorType.HDSP_SMOOTHED
                && config.hdspUseDeviceProfile();
        boolean direct = config.connection() == ConnectionType.DIRECT;
        String tooltip = Theme.tooltip(direct ? CALIBRATION_TOOLTIP : CALIBRATION_INTIFACE_TOOLTIP);
        calibrateButton.setEnabled(direct);
        calibrateButton.setToolTipText(tooltip);
        applyTooltip(calibrateButtonRow, tooltip);
        boolean changed = setRowVisible(calibrateButtonRow, calibrateButtonSpacer, visible);
        if (changed && smoothedSection.getParent() != null)
        {
            smoothedSection.getParent().revalidate();
            smoothedSection.getParent().repaint();
        }
    }

    private void confirmCalibration()
    {
        if (config.connection() != ConnectionType.DIRECT)
        {
            return;
        }
        boolean confirmed = ConfirmDialog.show(this, "Step-response calibration", "Run the calibration?",
                "The device will make a series of known moves while the app measures its response.\n"
                        + "Keep the device clear; this takes about four minutes and stops the running session"
                        + " temporarily.\n"
                        + "The step-response CSV is written to logs/diagnostics/ and the device profile to profiles/.",
                "Start calibration");
        if (confirmed && onCalibrate != null)
        {
            onCalibrate.run();
        }
    }

    private JComponent restoreDefaultsButton(java.util.function.Function<ConfigProperties, ConfigProperties> reset)
    {
        var button = new ModernButton("Restore defaults", ModernButton.Style.GHOST);
        button.addActionListener(e ->
        {
            ConfigProperties before = config;
            log.info("Restoring default settings");
            ConfigProperties restored = reset.apply(ConfigProperties.defaults());
            setConfig(restored);
            changeListener.accept(restored);
            logSettingChanges(before, restored, "Restore defaults changed");
        });
        var holder = new JPanel(new FlowLayout(FlowLayout.RIGHT, 0, 0));
        holder.setOpaque(false);
        holder.setAlignmentX(Component.LEFT_ALIGNMENT);
        holder.add(button);
        holder.setMaximumSize(new Dimension(Integer.MAX_VALUE, button.getPreferredSize().height));
        return holder;
    }

    private static JPanel pageContainer()
    {
        var page = new JPanel();
        page.setOpaque(false);
        page.setLayout(new BoxLayout(page, BoxLayout.Y_AXIS));
        page.setBorder(BorderFactory.createEmptyBorder(0, 0, 8, 6));
        return page;
    }

    private static JScrollPane scrollable(JComponent page)
    {
        var view = new ScrollableColumn();
        view.add(page, BorderLayout.NORTH);
        var scrollPane = new JScrollPane(view, ScrollPaneConstants.VERTICAL_SCROLLBAR_AS_NEEDED,
                ScrollPaneConstants.HORIZONTAL_SCROLLBAR_NEVER);
        scrollPane.setBorder(BorderFactory.createEmptyBorder());
        scrollPane.setOpaque(false);
        scrollPane.getViewport().setOpaque(false);
        scrollPane.getVerticalScrollBar().setUI(new ThinScrollBarUI());
        scrollPane.getVerticalScrollBar().setOpaque(false);
        scrollPane.getVerticalScrollBar().setUnitIncrement(18);
        return scrollPane;
    }

    private static void onTextChange(JTextField field, Runnable action)
    {
        field.getDocument().addDocumentListener(new DocumentListener()
        {
            @Override
            public void insertUpdate(DocumentEvent e)
            {
                action.run();
            }

            @Override
            public void removeUpdate(DocumentEvent e)
            {
                action.run();
            }

            @Override
            public void changedUpdate(DocumentEvent e)
            {
                action.run();
            }
        });
    }

    private Integer parsePort(String text)
    {
        try
        {
            int port = Integer.parseInt(text.trim());
            return port >= 1 && port <= 65535 ? port : null;
        }
        catch (NumberFormatException e)
        {
            return null;
        }
    }

    private static <T> T orDefault(T value, T defaultValue)
    {
        return value != null ? value : defaultValue;
    }

    /** Column that always has the width of the scroll pane, so that only vertical scrolling is needed. */
    private static class ScrollableColumn extends JPanel implements Scrollable
    {
        ScrollableColumn()
        {
            super(new BorderLayout());
            setOpaque(false);
        }

        @Override
        public Dimension getPreferredScrollableViewportSize()
        {
            return getPreferredSize();
        }

        @Override
        public int getScrollableUnitIncrement(Rectangle visibleRect, int orientation, int direction)
        {
            return 18;
        }

        @Override
        public int getScrollableBlockIncrement(Rectangle visibleRect, int orientation, int direction)
        {
            return Math.max(18, visibleRect.height - 36);
        }

        @Override
        public boolean getScrollableTracksViewportWidth()
        {
            return true;
        }

        @Override
        public boolean getScrollableTracksViewportHeight()
        {
            return getParent() != null && getParent().getHeight() > getPreferredSize().height;
        }
    }
}
