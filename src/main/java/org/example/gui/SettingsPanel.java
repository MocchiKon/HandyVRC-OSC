package org.example.gui;

import org.example.config.ConfigProperties;
import org.example.config.InteractWith;
import org.example.processor.ParameterProcessorType;
import org.example.processor.SpsType;

import javax.swing.*;
import javax.swing.event.DocumentEvent;
import javax.swing.event.DocumentListener;
import java.awt.*;
import java.util.List;
import java.util.function.Consumer;
import java.util.function.UnaryOperator;

import static org.example.gui.SettingRows.*;

/**
 * The "Basic" and "Advanced" settings pages. Every change is reported right away (with the complete new settings)
 * to the change listener, which saves them.
 * <p>
 * The Advanced page only shows the options of the processing mode selected on the Basic page.
 */
public class SettingsPanel extends JPanel
{
    private static final String BASIC = "basic";
    private static final String ADVANCED = "advanced";
    private static final String LOGS = "logs";
    private static final List<String> LOG_LEVELS = List.of("TRACE", "DEBUG", "INFO", "WARN", "ERROR");

    private ConfigProperties config;
    private final Consumer<ConfigProperties> changeListener;
    /** True while the controls are set from the settings, so that doing so does not count as a change. */
    private boolean updating;

    // Basic
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
    private final ModernSlider speedChangeThreshold = new ModernSlider(5, 100, 1, 25);
    private final ModernSlider speedStopThreshold = new ModernSlider(1, 50, 1, 15);
    private final ModernSlider divergenceThreshold = new ModernSlider(5, 60, 1, 20);
    private final ModernSlider speedMeasureWindow = new ModernSlider(50, 500, 10, 150);
    private final ModernSlider minSliderSpeed = new ModernSlider(0, 100, 1, 30);
    /** Tenths of a percent, so that half percent steps are possible. */
    private final ModernSlider reversalMinDistance = new ModernSlider(0, 100, 5, 20);
    private final ToggleSwitch logDevicePosition = new ToggleSwitch(false);
    private final ModernSlider minimalValueChange = new ModernSlider(0, 20, 1, 3);
    private final ToggleSwitch pauseOnStarving = new ToggleSwitch(false);
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

    public SettingsPanel(ConfigProperties initialConfig, Consumer<ConfigProperties> changeListener)
    {
        super(new BorderLayout(0, 12));
        this.config = initialConfig;
        this.changeListener = changeListener;
        setOpaque(false);

        spsType = new SegmentedControl<>(List.of(
                new SegmentedControl.Option<>(SpsType.PENETRATOR, "Penetrator",
                        "Your avatar has an SPS penetrator. The device follows how deep it is inside an orifice."),
                new SegmentedControl.Option<>(SpsType.ORIFICE, "Orifice",
                        "Your avatar has an SPS orifice (also useful when someone else runs this app for you). The"
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
        setConfig(initialConfig);
    }

    public ConfigProperties getConfig()
    {
        return config;
    }

    /** Shows the given settings without reporting them as a change. */
    public void setConfig(ConfigProperties newConfig)
    {
        config = newConfig;
        updating = true;
        try
        {
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
            minSliderSpeed.setValue(orDefault(newConfig.hdspMinSliderSpeed(), defaults.hdspMinSliderSpeed()));
            reversalMinDistance.setValue(Math.round(orDefault(newConfig.hdspReversalMinDistancePercent(),
                    defaults.hdspReversalMinDistancePercent()) * 10));
            logDevicePosition.setSelected(newConfig.hdspLogDevicePosition());
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
    }

    /** Updates everything that depends on other settings (visible options, defaults, validation). */
    private void refreshDependentControls()
    {
        boolean smoothed = config.processingAlgorithm() == ParameterProcessorType.HDSP_SMOOTHED;
        smoothedSection.setVisible(smoothed);
        directSection.setVisible(!smoothed);
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
        minSliderSpeed.addChangeListener(e -> update(b -> b.hdspMinSliderSpeed(minSliderSpeed.getValue())));
        reversalMinDistance.addChangeListener(e -> update(b -> b.hdspReversalMinDistancePercent(reversalMinDistance.getValue() / 10f)));
        logDevicePosition.addChangeListener(e -> update(b -> b.hdspLogDevicePosition(logDevicePosition.isSelected())));
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
                        + " halfway. 100% turns this off.",
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
                        + " more, longer is steadier but lags behind. 150 ms suits strokes up to about one per second,"
                        + " try 100 ms for faster strokes.",
                speedMeasureWindow, value -> value + " ms"));
        addRow(smoothedSection, sliderRow("Minimum device speed",
                "Slowest speed the device moves with. A Handy does not move slower than about 30 mm/s, so slower"
                        + " movements are followed in short pulses instead. Raise it if the device runs past slow"
                        + " movements; 0 turns the pulses off.",
                minSliderSpeed, value -> value == 0 ? "Off" : value + " mm/s"));
        addRow(smoothedSection, sliderRow("Reversal distance",
                "How far (in % of the stroke) you have to move back before it counts as a change of direction."
                        + " Until then the device just stops, so the jitter of a slow movement cannot make it reverse."
                        + " Raise it if the device twitches back and forth while you move slowly.",
                reversalMinDistance, value -> "%.1f%%".formatted(value / 10f)));
        addRow(smoothedSection, toggleRow("Log device position", "Diagnostics, leave off for normal use",
                "Logs the position the device reports (log level DEBUG) every 200 ms next to the position the app"
                        + " believes it is at. Every report is an extra Bluetooth request.",
                logDevicePosition));
        page.add(smoothedSection);

        addRow(directSection, sliderRow("Minimal value change",
                "Smallest change of the position (in % of the stroke) that is sent to the device. Higher values"
                        + " filter out more jitter but make small movements coarser.",
                minimalValueChange, value -> value + "%"));
        addRow(directSection, toggleRow("Pause on starving", "Stop at every position instead of moving on",
                "The device stops when it reaches a position, instead of carrying on while it waits for the next"
                        + " one. Avoids overshooting, but the movement can feel choppier.",
                pauseOnStarving));
        page.add(directSection);
        page.add(Box.createVerticalStrut(12));

        var general = section("General");
        addRow(general, sliderRow("Send interval",
                "Minimum time between two commands sent to the device. At least 20 ms are always kept to not flood"
                        + " the Bluetooth connection. In Smoothed mode this only spaces out speed corrections, stops"
                        + " are always sent right away.",
                sendMessageEvery, value -> value <= 20 ? "20 ms (minimum)" : value + " ms"));
        addRow(general, toggleRow("Check for updates", "Look for a new version on startup",
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
                .hdspMinSliderSpeed(defaults.hdspMinSliderSpeed())
                .hdspReversalMinDistancePercent(defaults.hdspReversalMinDistancePercent())
                .hdspLogDevicePosition(defaults.hdspLogDevicePosition())
                .minimalValueChange(defaults.minimalValueChange())
                .pauseOnStarving(defaults.pauseOnStarving())
                .sendMessageEveryMs(defaults.sendMessageEveryMs())
                .checkForUpdates(defaults.checkForUpdates())
                .logLevel(defaults.logLevel())
                .build()));
        return page;
    }

    private JComponent restoreDefaultsButton(java.util.function.Function<ConfigProperties, ConfigProperties> reset)
    {
        var button = new ModernButton("Restore defaults", ModernButton.Style.GHOST);
        button.addActionListener(e ->
        {
            ConfigProperties restored = reset.apply(ConfigProperties.defaults());
            setConfig(restored);
            changeListener.accept(restored);
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
