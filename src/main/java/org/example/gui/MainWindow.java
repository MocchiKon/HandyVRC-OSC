package org.example.gui;

import ch.qos.logback.classic.Level;
import lombok.extern.slf4j.Slf4j;
import org.example.AppController;
import org.example.config.ConfigLoader;
import org.example.config.ConfigProperties;
import org.example.processor.SpsType;
import org.example.update.AppVersionProvider;

import javax.swing.*;
import java.awt.*;
import java.awt.event.WindowAdapter;
import java.awt.event.WindowEvent;
import java.awt.geom.RoundRectangle2D;
import java.awt.image.BufferedImage;
import java.io.IOException;
import java.util.List;

/**
 * Main window: the current penetration value, Start/Stop, and the settings. Settings are saved as soon as they
 * change; while the app is running, changed settings are applied with "Apply changes" (which restarts processing
 * without reconnecting the device).
 */
@Slf4j
public class MainWindow
{
    private static final String TITLE = "HandyVRC-OSC";
    private static final int SAVE_DELAY_MS = 400;

    private final ConfigLoader configLoader;
    private JFrame frame;
    private final AppController controller;
    private final PositionGauge gauge = new PositionGauge();
    private final StatusPill statusPill = new StatusPill();
    private final WrappingText statusMessage = new WrappingText("", Theme.regular(12.5f), Theme.TEXT_SECONDARY);
    private final ModernButton startStopButton = new ModernButton("Start", ModernButton.Style.PRIMARY);
    private final ModernButton applyButton = new ModernButton("Apply changes", ModernButton.Style.PRIMARY);
    private final ModernButton testModeButton = new ModernButton("Test mode", ModernButton.Style.SECONDARY);
    private final IconButton detachButton = new IconButton(IconButton.Glyph.DETACH,
            "Shows the penetration value in its own small window, for example to keep it next to VRChat.");
    private final IconButton attachButton = new IconButton(IconButton.Glyph.ATTACH,
            "Puts the penetration value back into this window.");
    private final CardLayout gaugeSlots = new CardLayout();
    private final JPanel gaugeSlot = new JPanel(gaugeSlots);
    private final JPanel attachedGaugeHolder = new JPanel(new BorderLayout());
    /** The window the penetration value is shown in while it is detached, null while it is attached. */
    private JFrame gaugeWindow;
    private Rectangle gaugeWindowBounds;
    private TestModeWindow testWindow;
    private final JLabel footer = new JLabel();
    private final SettingsPanel settings;
    private final Timer saveTimer;

    private AppController.Status status;
    /** Settings the running session was started with. */
    private ConfigProperties appliedConfig;

    MainWindow(ConfigLoader configLoader, ConfigProperties config)
    {
        this.configLoader = configLoader;
        this.controller = new AppController(
                newStatus -> SwingUtilities.invokeLater(() -> showStatus(newStatus)),
                gauge::setValue);
        this.status = controller.getStatus();
        this.settings = new SettingsPanel(config, this::onSettingsChanged);
        this.saveTimer = new Timer(SAVE_DELAY_MS, e -> saveSettings());
        saveTimer.setRepeats(false);
    }

    public static void open(ConfigLoader configLoader, ConfigProperties config)
    {
        Theme.installDefaults();
        new MainWindow(configLoader, config).show();
    }

    private void show()
    {
        frame = new JFrame(TITLE);
        frame.setIconImages(appIcons());
        frame.setDefaultCloseOperation(WindowConstants.DO_NOTHING_ON_CLOSE);
        frame.addWindowListener(new WindowAdapter()
        {
            @Override
            public void windowClosing(WindowEvent e)
            {
                exit();
            }
        });

        frame.setContentPane(buildContent());
        Rectangle screen = GraphicsEnvironment.getLocalGraphicsEnvironment().getMaximumWindowBounds();
        frame.setSize(Math.min(640, screen.width), Math.min(960, screen.height - 20));
        frame.setMinimumSize(new Dimension(520, 580));
        frame.setLocationRelativeTo(null);
        frame.setVisible(true);
        startStopButton.requestFocusInWindow();
    }

    /** Everything inside the window (package-private so that it can be rendered without a window). */
    JPanel buildContent()
    {
        var root = new JPanel(new BorderLayout());
        root.setBackground(Theme.BACKGROUND);
        root.add(buildHeader(), BorderLayout.NORTH);

        var content = new JPanel(new BorderLayout(0, 16));
        content.setOpaque(false);
        content.setBorder(BorderFactory.createEmptyBorder(16, 16, 0, 10));
        content.add(buildHero(), BorderLayout.NORTH);
        content.add(settings, BorderLayout.CENTER);
        root.add(content, BorderLayout.CENTER);
        root.add(buildFooter(), BorderLayout.SOUTH);
        showStatus(status);
        return root;
    }

    private JComponent buildHeader()
    {
        var header = new JPanel(new BorderLayout())
        {
            @Override
            protected void paintComponent(Graphics graphics)
            {
                super.paintComponent(graphics);
                graphics.setColor(Theme.withAlpha(Theme.BORDER, 160));
                graphics.fillRect(0, getHeight() - 1, getWidth(), 1);
            }
        };
        header.setBackground(Theme.HEADER);
        header.setBorder(BorderFactory.createEmptyBorder(12, 18, 12, 18));

        var brand = new JPanel();
        brand.setOpaque(false);
        brand.setLayout(new BoxLayout(brand, BoxLayout.X_AXIS));
        brand.add(new JLabel(new ImageIcon(appIcon(28))));
        brand.add(Box.createHorizontalStrut(10));
        var title = new JLabel(TITLE);
        title.setFont(Theme.bold(16f));
        title.setForeground(Theme.TEXT);
        brand.add(title);
        brand.add(Box.createHorizontalStrut(8));
        var version = new JLabel("v" + AppVersionProvider.currentVersion().map(Object::toString).orElse("dev"));
        version.setFont(Theme.regular(12f));
        version.setForeground(Theme.TEXT_MUTED);
        brand.add(version);
        header.add(brand, BorderLayout.WEST);

        var pillHolder = new JPanel(new GridBagLayout());
        pillHolder.setOpaque(false);
        pillHolder.add(statusPill);
        header.add(pillHolder, BorderLayout.EAST);
        return header;
    }

    private JComponent buildHero()
    {
        var hero = new RoundedPanel(new GridBagLayout(), Theme.SURFACE, Theme.RADIUS * 2);
        hero.setBorderColor(Theme.withAlpha(Theme.BORDER, 150));
        hero.setBorder(BorderFactory.createEmptyBorder(18, 18, 18, 18));

        // The gauge, or a note while it is shown in its own window
        attachedGaugeHolder.setOpaque(false);
        attachedGaugeHolder.add(gauge, BorderLayout.CENTER);
        var detachedNote = new JLabel("The penetration value is shown in its own window.");
        detachedNote.setFont(Theme.regular(12.5f));
        detachedNote.setForeground(Theme.TEXT_MUTED);
        gaugeSlot.setOpaque(false);
        gaugeSlot.add(attachedGaugeHolder, "attached");
        gaugeSlot.add(detachedNote, "detached");

        var constraints = new GridBagConstraints();
        constraints.gridx = 0;
        constraints.gridy = 0;
        constraints.weightx = 1;
        constraints.fill = GridBagConstraints.HORIZONTAL;
        hero.add(gaugeSlot, constraints);

        detachButton.addActionListener(e -> detachGauge());
        attachButton.addActionListener(e -> attachGauge());
        attachButton.setVisible(false);
        var corner = new JPanel(new FlowLayout(FlowLayout.RIGHT, 0, 0));
        corner.setOpaque(false);
        corner.add(detachButton);
        corner.add(attachButton);
        constraints.gridx = 1;
        constraints.weightx = 0;
        constraints.fill = GridBagConstraints.NONE;
        constraints.anchor = GridBagConstraints.NORTHEAST;
        hero.add(corner, constraints);

        constraints.gridx = 0;
        constraints.gridy = 1;
        constraints.gridwidth = 2;
        constraints.weightx = 1;
        constraints.fill = GridBagConstraints.HORIZONTAL;
        constraints.anchor = GridBagConstraints.WEST;
        constraints.insets = new Insets(14, 0, 0, 0);
        hero.add(statusMessage, constraints);

        startStopButton.addActionListener(e -> onStartStop());
        applyButton.setGlyph(ModernButton.Glyph.REFRESH);
        applyButton.setVisible(false);
        applyButton.addActionListener(e -> applyChanges());
        testModeButton.addActionListener(e -> openTestMode());
        var buttons = new JPanel(new FlowLayout(FlowLayout.RIGHT, 8, 0));
        buttons.setOpaque(false);
        buttons.add(applyButton);
        buttons.add(testModeButton);
        buttons.add(startStopButton);

        constraints.gridy = 2;
        constraints.fill = GridBagConstraints.NONE;
        constraints.anchor = GridBagConstraints.EAST;
        constraints.insets = new Insets(12, 0, 0, 0);
        hero.add(buttons, constraints);
        return hero;
    }

    /** Moves the gauge into its own small window, which stays above the other windows unless unpinned. */
    private void detachGauge()
    {
        if (gaugeWindow != null)
        {
            gaugeWindow.toFront();
            return;
        }
        attachedGaugeHolder.remove(gauge);
        gauge.setDetached(true);
        gaugeSlots.show(gaugeSlot, "detached");
        detachButton.setVisible(false);
        attachButton.setVisible(true);

        gaugeWindow = new JFrame("Penetration value");
        gaugeWindow.setIconImages(appIcons());
        gaugeWindow.setDefaultCloseOperation(WindowConstants.DO_NOTHING_ON_CLOSE);
        gaugeWindow.addWindowListener(new WindowAdapter()
        {
            @Override
            public void windowClosing(WindowEvent e)
            {
                attachGauge();
            }
        });
        gaugeWindow.setAlwaysOnTop(true);

        var pin = new IconButton(IconButton.Glyph.PIN, "Keeps this window above all other windows. Click to turn it off.");
        pin.setToggled(true);
        pin.addActionListener(e ->
        {
            boolean onTop = !gaugeWindow.isAlwaysOnTop();
            gaugeWindow.setAlwaysOnTop(onTop);
            pin.setToggled(onTop);
        });
        var attach = new IconButton(IconButton.Glyph.ATTACH, "Puts the penetration value back into the main window.");
        attach.addActionListener(e -> attachGauge());
        var bar = new JPanel(new FlowLayout(FlowLayout.RIGHT, 2, 0));
        bar.setOpaque(false);
        bar.add(pin);
        bar.add(attach);

        var content = new JPanel(new BorderLayout(0, 4));
        content.setBackground(Theme.BACKGROUND);
        content.setBorder(BorderFactory.createEmptyBorder(6, 10, 12, 10));
        content.add(bar, BorderLayout.NORTH);
        content.add(gauge, BorderLayout.CENTER);
        gaugeWindow.setContentPane(content);
        gaugeWindow.setMinimumSize(new Dimension(110, 220));
        if (gaugeWindowBounds != null)
        {
            gaugeWindow.setBounds(gaugeWindowBounds);
        }
        else
        {
            gaugeWindow.setSize(Math.round(170 * Theme.FONT_SCALE), Math.round(330 * Theme.FONT_SCALE));
            Rectangle main = frame.getBounds();
            Rectangle screen = GraphicsEnvironment.getLocalGraphicsEnvironment().getMaximumWindowBounds();
            int x = main.x + main.width + 8;
            gaugeWindow.setLocation(x + gaugeWindow.getWidth() <= screen.x + screen.width ? x
                    : Math.max(screen.x, main.x - gaugeWindow.getWidth() - 8), main.y);
        }
        gaugeWindow.setVisible(true);
    }

    /** Puts the gauge back into the main window and closes its own window. */
    private void attachGauge()
    {
        if (gaugeWindow == null)
        {
            return;
        }
        gaugeWindowBounds = gaugeWindow.getBounds();
        gaugeWindow.getContentPane().remove(gauge);
        gaugeWindow.dispose();
        gaugeWindow = null;
        gauge.setDetached(false);
        attachedGaugeHolder.add(gauge, BorderLayout.CENTER);
        gaugeSlots.show(gaugeSlot, "attached");
        detachButton.setVisible(true);
        attachButton.setVisible(false);
        attachedGaugeHolder.revalidate();
        attachedGaugeHolder.repaint();
    }

    /** Opens the test mode window and starts a test session (a running session is stopped first). */
    private void openTestMode()
    {
        if (testWindow != null && !testWindow.isClosed())
        {
            testWindow.toFront();
            return;
        }
        flushPendingSave();
        testWindow = new TestModeWindow(settings.getConfig().spsType(),
                part -> startTest(),
                stop ->
                {
                    if (stop)
                    {
                        controller.stop();
                    }
                    else
                    {
                        startTest();
                    }
                },
                controller::feedTestInput);
        testWindow.show(frame, () ->
        {
            testWindow = null;
            controller.stop();
        });
        startTest();
    }

    private void startTest()
    {
        ConfigProperties config = effectiveConfig();
        String error = config.validate();
        if (error != null)
        {
            showStatus(new AppController.Status(AppController.State.ERROR, error, true));
            return;
        }
        appliedConfig = config;
        controller.startTest(config);
    }

    /**
     * The settings, with the SPS type of the test mode window while it is open. Test mode does not listen for OSC,
     * so the avatar parameter is replaced by the default of that SPS type: the configured one may belong to the
     * other type (a penetrator parameter is rejected in Orifice mode).
     */
    private ConfigProperties effectiveConfig()
    {
        ConfigProperties config = settings.getConfig();
        if (testWindow == null || testWindow.isClosed())
        {
            return config;
        }
        SpsType part = testWindow.getMovedPart();
        return config.toBuilder()
                .spsType(part)
                .avatarParameter(ConfigProperties.defaultAvatarParameter(part, config.interactWith()))
                .customAvatarParameter(false)
                .build();
    }

    private JComponent buildFooter()
    {
        footer.setFont(Theme.regular(11.5f));
        footer.setForeground(Theme.TEXT_MUTED);
        footer.setBorder(BorderFactory.createEmptyBorder(8, 18, 10, 18));
        footer.setText("Settings are saved automatically to " + configLoader.getPath().getFileName());
        return footer;
    }

    private void onStartStop()
    {
        switch (status.state())
        {
            case STOPPED, ERROR -> start();
            case RUNNING -> controller.stop();
            case CONNECTING ->
            {
                // The button is disabled while connecting
            }
        }
    }

    private void start()
    {
        flushPendingSave();
        appliedConfig = settings.getConfig();
        String error = appliedConfig.validate();
        if (error != null)
        {
            showStatus(new AppController.Status(AppController.State.ERROR, error));
            return;
        }
        controller.start(appliedConfig);
    }

    private void applyChanges()
    {
        flushPendingSave();
        ConfigProperties config = effectiveConfig();
        String error = config.validate();
        if (error != null)
        {
            showMessage(error, true);
            return;
        }
        appliedConfig = config;
        controller.restart(config);
        updatePendingChanges();
    }

    private void onSettingsChanged(ConfigProperties config)
    {
        // The logging level applies right away, it needs no restart
        ConfigLoader.setLoggingLevel(Level.toLevel(config.logLevel(), Level.INFO));
        saveTimer.restart();
        updatePendingChanges();
    }

    private void flushPendingSave()
    {
        if (saveTimer.isRunning())
        {
            saveTimer.stop();
            saveSettings();
        }
    }

    private void saveSettings()
    {
        try
        {
            configLoader.save(settings.getConfig());
            footer.setForeground(Theme.TEXT_MUTED);
            footer.setText("Settings are saved automatically to " + configLoader.getPath().getFileName());
        }
        catch (IOException e)
        {
            log.error("Could not save the settings to {}", configLoader.getPath(), e);
            footer.setForeground(Theme.ERROR);
            footer.setText("Could not save the settings: " + e.getMessage());
        }
    }

    private void showStatus(AppController.Status newStatus)
    {
        status = newStatus;
        statusPill.setState(newStatus.state(), newStatus.testMode());
        if (testWindow != null)
        {
            testWindow.showStatus(newStatus);
            if (testWindow.isClosed())
            {
                testWindow = null;
            }
        }
        testModeButton.setEnabled(newStatus.state() != AppController.State.CONNECTING);
        gauge.setActive(newStatus.state() == AppController.State.RUNNING);
        showMessage(newStatus.message(), newStatus.state() == AppController.State.ERROR);

        switch (newStatus.state())
        {
            case STOPPED, ERROR ->
            {
                startStopButton.setText("Start");
                startStopButton.setGlyph(ModernButton.Glyph.PLAY);
                startStopButton.setStyle(ModernButton.Style.PRIMARY);
                startStopButton.setEnabled(true);
            }
            case CONNECTING ->
            {
                startStopButton.setText("Starting...");
                startStopButton.setGlyph(ModernButton.Glyph.NONE);
                startStopButton.setEnabled(false);
            }
            case RUNNING ->
            {
                startStopButton.setText("Stop");
                startStopButton.setGlyph(ModernButton.Glyph.STOP);
                startStopButton.setStyle(ModernButton.Style.SECONDARY);
                startStopButton.setEnabled(true);
            }
        }
        updatePendingChanges();
    }

    private void showMessage(String message, boolean error)
    {
        statusMessage.setForeground(error ? Theme.ERROR : Theme.TEXT_SECONDARY);
        statusMessage.setText(message);
        statusMessage.setVisible(message != null && !message.isEmpty());
    }

    private void updatePendingChanges()
    {
        // The logging level applies right away, so it is no pending change
        boolean pending = status.state() == AppController.State.RUNNING && appliedConfig != null
                && !appliedConfig.toBuilder().logLevel(null).build()
                        .equals(effectiveConfig().toBuilder().logLevel(null).build());
        applyButton.setVisible(pending);
    }

    private void exit()
    {
        flushPendingSave();
        if (testWindow != null)
        {
            testWindow.close();
        }
        if (gaugeWindow != null)
        {
            gaugeWindow.dispose();
        }
        frame.setVisible(false);
        // Stopping waits for the device to be stopped and disconnected, which must not block the event thread
        Thread exitThread = new Thread(() ->
        {
            controller.close();
            System.exit(0);
        }, "exit");
        exitThread.start();
    }

    private static List<Image> appIcons()
    {
        return List.of(appIcon(16), appIcon(32), appIcon(48), appIcon(64));
    }

    /** Rounded bright blue square with a white stroke bar: the app icon, drawn so that it is sharp at every size. */
    static BufferedImage appIcon(int size)
    {
        var image = new BufferedImage(size, size, BufferedImage.TYPE_INT_ARGB);
        Graphics2D g = Theme.smooth(image.getGraphics());
        float arc = size * 0.32f;
        g.setPaint(new GradientPaint(0, 0, Theme.ACCENT_LIGHT, size, size, Theme.ACCENT_DARK));
        g.fill(new RoundRectangle2D.Float(0, 0, size, size, arc, arc));
        float barWidth = size * 0.22f;
        float barX = (size - barWidth) / 2f;
        g.setColor(Theme.withAlpha(Theme.HEADER, 110));
        g.fill(new RoundRectangle2D.Float(barX, size * 0.18f, barWidth, size * 0.64f, barWidth, barWidth));
        g.setColor(Theme.TEXT);
        g.fill(new RoundRectangle2D.Float(barX, size * 0.42f, barWidth, size * 0.40f, barWidth, barWidth));
        g.dispose();
        return image;
    }
}
