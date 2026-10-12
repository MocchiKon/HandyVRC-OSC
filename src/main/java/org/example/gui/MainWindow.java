package org.example.gui;

import lombok.extern.slf4j.Slf4j;
import org.example.AppController;
import org.example.config.ConfigLoader;
import org.example.config.ConfigProperties;
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
    private final WrappingText pendingChanges = new WrappingText("Settings changed. Apply them to use them now.",
            Theme.bold(12f), Theme.ACCENT_LIGHT);
    private final ModernButton startStopButton = new ModernButton("Start", ModernButton.Style.PRIMARY);
    private final ModernButton applyButton = new ModernButton("Apply changes", ModernButton.Style.PRIMARY);
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
        frame.setSize(Math.min(640, screen.width), Math.min(1000, screen.height - 20));
        frame.setMinimumSize(new Dimension(520, 600));
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
        statusPill.setToolTipText(Theme.tooltip("Whether the app is connected to your Handy and moving it."));
        header.add(pillHolder, BorderLayout.EAST);
        return header;
    }

    private JComponent buildHero()
    {
        var hero = new RoundedPanel(new GridBagLayout(), Theme.SURFACE, Theme.RADIUS * 2);
        hero.setBorderColor(Theme.withAlpha(Theme.BORDER, 150));
        hero.setBorder(BorderFactory.createEmptyBorder(18, 18, 18, 18));
        gauge.setToolTipText(Theme.tooltip("Current penetration value collected via OSC and sent to the device"
                + " (100 = top of the stroke, 0 = bottom)."));

        var constraints = new GridBagConstraints();
        constraints.gridx = 0;
        constraints.gridy = 0;
        constraints.gridwidth = 2;
        constraints.weightx = 1;
        constraints.fill = GridBagConstraints.HORIZONTAL;
        hero.add(gauge, constraints);

        pendingChanges.setVisible(false);
        var messages = new JPanel();
        messages.setOpaque(false);
        messages.setLayout(new BoxLayout(messages, BoxLayout.Y_AXIS));
        messages.add(statusMessage);
        messages.add(Box.createVerticalStrut(4));
        messages.add(pendingChanges);
        statusMessage.setAlignmentX(Component.LEFT_ALIGNMENT);
        pendingChanges.setAlignmentX(Component.LEFT_ALIGNMENT);

        constraints.gridy = 1;
        constraints.gridwidth = 1;
        constraints.insets = new Insets(16, 0, 0, 12);
        constraints.anchor = GridBagConstraints.WEST;
        hero.add(messages, constraints);

        startStopButton.addActionListener(e -> onStartStop());
        applyButton.setGlyph(ModernButton.Glyph.REFRESH);
        applyButton.setVisible(false);
        applyButton.setToolTipText(Theme.tooltip("Restarts with the changed settings. The device stays connected."));
        applyButton.addActionListener(e -> applyChanges());
        var buttons = new JPanel(new FlowLayout(FlowLayout.RIGHT, 8, 0));
        buttons.setOpaque(false);
        buttons.add(applyButton);
        buttons.add(startStopButton);

        constraints.gridx = 1;
        constraints.weightx = 0;
        constraints.fill = GridBagConstraints.NONE;
        constraints.insets = new Insets(16, 0, 0, 0);
        constraints.anchor = GridBagConstraints.EAST;
        hero.add(buttons, constraints);
        return hero;
    }

    private JComponent buildFooter()
    {
        footer.setFont(Theme.regular(11.5f));
        footer.setForeground(Theme.TEXT_MUTED);
        footer.setBorder(BorderFactory.createEmptyBorder(8, 18, 10, 18));
        footer.setText("Settings are saved automatically");
        footer.setToolTipText(Theme.tooltip("Saved to " + configLoader.getPath().toAbsolutePath()
                + ". Details about what the app does are written to the console and the log files."));
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
        ConfigProperties config = settings.getConfig();
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
            footer.setText("Settings are saved automatically");
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
        statusPill.setState(newStatus.state());
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
                startStopButton.setToolTipText(Theme.tooltip(
                        "Connects to your Handy over Bluetooth and starts following your avatar."));
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
                startStopButton.setToolTipText(Theme.tooltip(
                        "Stops moving the device. It stays connected, so starting again is quick."));
            }
        }
        updatePendingChanges();
    }

    private void showMessage(String message, boolean error)
    {
        statusMessage.setForeground(error ? Theme.ERROR : Theme.TEXT_SECONDARY);
        statusMessage.setText(message);
    }

    private void updatePendingChanges()
    {
        boolean pending = status.state() == AppController.State.RUNNING && appliedConfig != null
                && !appliedConfig.equals(settings.getConfig());
        pendingChanges.setVisible(pending);
        applyButton.setVisible(pending);
    }

    private void exit()
    {
        flushPendingSave();
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
