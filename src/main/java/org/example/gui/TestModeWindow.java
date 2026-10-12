package org.example.gui;

import org.example.AppController;
import org.example.processor.SpsType;

import javax.swing.*;
import java.awt.*;
import java.awt.event.WindowAdapter;
import java.awt.event.WindowEvent;
import java.util.List;
import java.util.function.Consumer;

import static org.example.gui.SettingRows.*;

/**
 * Test mode: the user moves a penetrator or an orifice with the mouse (or lets it stroke on its own), and the
 * resulting depth is sent through the selected mode to the device, exactly like a value from VRChat would be. That
 * way the mode, its settings and the connection to the device can be tried out without VRChat.
 */
class TestModeWindow
{
    /** How often the depth is sent: about the frame interval of VRChat, which sends the avatar parameters per frame. */
    private static final int SAMPLE_INTERVAL_MS = 22;

    private JDialog dialog;
    private final JComponent content;
    private final Consumer<Float> inputListener;
    private final TestScene scene = new TestScene();
    private final SegmentedControl<SpsType> movedPart;
    private final JLabel depthValue = new JLabel();
    private final ToggleSwitch autoStroke = new ToggleSwitch(false);
    private final ModernSlider strokeSpeed = new ModernSlider(10, 150, 5, 40);
    private final WrappingText statusText = new WrappingText("", Theme.regular(12.5f), Theme.TEXT_SECONDARY);
    private final ModernButton stopButton = new ModernButton("Stop test", ModernButton.Style.SECONDARY);
    private final Timer sampler;

    private float lastSent = Float.NaN;
    /** Position within the automatic stroke, 0-1 (0 = outside). */
    private double strokePhase;
    private boolean closing;

    /**
     * @param onPartChange called with the newly selected part (the session has to be restarted with that SPS type)
     * @param onStopOrRetry called when the user stops the test (true) or retries a test that failed (false)
     * @param inputListener receives the depth (0-1) to send to the device
     */
    TestModeWindow(SpsType initialPart, Consumer<SpsType> onPartChange, Consumer<Boolean> onStopOrRetry,
                   Consumer<Float> inputListener)
    {
        this.inputListener = inputListener;
        movedPart = new SegmentedControl<>(List.of(
                new SegmentedControl.Option<>(SpsType.PENETRATOR, "Penetrator",
                        "You move a penetrator into a fixed orifice. Tests the Penetrator SPS type: the depth is sent"
                                + " as the penetration value."),
                new SegmentedControl.Option<>(SpsType.ORIFICE, "Orifice",
                        "You move an orifice onto a fixed penetrator. Tests the Orifice SPS type: the depth is sent as"
                                + " the root and tip proximity of the penetrator, so the penetrator length detection is"
                                + " tested as well.")),
                initialPart, false);
        movedPart.setSelectionListener(part ->
        {
            scene.setMoved(part);
            lastSent = Float.NaN;
            onPartChange.accept(part);
        });
        scene.setMoved(initialPart);
        scene.setUserMoveListener(() -> autoStroke.setSelected(false)); // Taking over by hand stops the automatic strokes
        stopButton.addActionListener(e -> onStopOrRetry.accept(stopButton.getText().startsWith("Stop")));

        content = buildContent();
        sampler = new Timer(SAMPLE_INTERVAL_MS, e -> sample());
        sampler.start();
    }

    /** @param onClose called when the user closes the window */
    void show(Window owner, Runnable onClose)
    {
        dialog = new JDialog(owner, "Test mode", Dialog.ModalityType.MODELESS);
        dialog.setDefaultCloseOperation(WindowConstants.DO_NOTHING_ON_CLOSE);
        dialog.addWindowListener(new WindowAdapter()
        {
            @Override
            public void windowClosing(WindowEvent e)
            {
                close();
                onClose.run();
            }
        });
        dialog.setContentPane(content);
        dialog.setIconImages(owner.getIconImages());
        Rectangle screen = GraphicsEnvironment.getLocalGraphicsEnvironment().getMaximumWindowBounds();
        dialog.setSize(Math.min(680, screen.width), Math.min(840, screen.height - 20));
        dialog.setMinimumSize(new Dimension(480, 560));
        dialog.setLocationRelativeTo(owner);
        dialog.setVisible(true);
        scene.requestFocusInWindow();
    }

    /** The window content (package-private so that it can be rendered without a window). */
    JComponent content()
    {
        return content;
    }

    private JComponent buildContent()
    {
        var root = new JPanel(new BorderLayout(0, 14));
        root.setBackground(Theme.BACKGROUND);
        root.setBorder(BorderFactory.createEmptyBorder(18, 18, 18, 18));

        var intro = new WrappingText("Move your Handy without VRChat: drag the highlighted part (or use the arrow keys"
                + " or the mouse wheel). The depth is handled with the settings of the main window (mode, stroke"
                + " range, fully penetrated at and the Advanced settings), just like a value from VRChat.",
                Theme.regular(12.5f), Theme.TEXT_SECONDARY);
        root.add(intro, BorderLayout.NORTH);

        var card = section("Test");
        addRow(card, controlRow("You move the",
                "Which part you move. The other one stays where it is.", movedPart));

        var sceneHolder = new RoundedPanel(new BorderLayout(), Theme.HEADER, Theme.RADIUS * 2);
        sceneHolder.setBorderColor(Theme.withAlpha(Theme.BORDER, 150));
        sceneHolder.setBorder(BorderFactory.createEmptyBorder(8, 8, 4, 8));
        sceneHolder.add(scene, BorderLayout.CENTER);
        sceneHolder.setAlignmentX(Component.LEFT_ALIGNMENT);
        card.add(Box.createVerticalStrut(14));
        card.add(sceneHolder);

        var depthRow = new JPanel(new BorderLayout());
        depthRow.setOpaque(false);
        depthRow.add(title("Depth"), BorderLayout.WEST);
        depthValue.setFont(Theme.bold(15f));
        depthValue.setForeground(Theme.ACCENT_LIGHT);
        depthRow.add(depthValue, BorderLayout.EAST);
        applyTooltip(depthRow, Theme.tooltip("How deep the penetrator is inside the orifice: the value sent to the"
                + " selected mode. The settings of the main window (for example Stroke range and Fully penetrated at)"
                + " apply to it."));
        depthRow.setMaximumSize(new Dimension(Integer.MAX_VALUE, depthRow.getPreferredSize().height));
        addRow(card, depthRow);

        addRow(card, toggleRow("Automatic strokes", "Strokes in and out on their own",
                "Moves the part all the way in and out continuously at the speed below, to watch the device follow a"
                        + " steady movement. Moving the part by hand turns it off.",
                autoStroke));
        addRow(card, sliderRow("Stroke speed",
                "How many strokes (in and out) per minute the automatic strokes make.",
                strokeSpeed, value -> value + " / min"));

        var scroll = new JScrollPane(card, ScrollPaneConstants.VERTICAL_SCROLLBAR_AS_NEEDED,
                ScrollPaneConstants.HORIZONTAL_SCROLLBAR_NEVER);
        scroll.setBorder(BorderFactory.createEmptyBorder());
        scroll.setOpaque(false);
        scroll.getViewport().setOpaque(false);
        scroll.getVerticalScrollBar().setUI(new ThinScrollBarUI());
        root.add(scroll, BorderLayout.CENTER);

        var bottom = new JPanel(new BorderLayout(12, 0));
        bottom.setOpaque(false);
        bottom.add(statusText, BorderLayout.CENTER);
        var buttonHolder = new JPanel(new GridBagLayout());
        buttonHolder.setOpaque(false);
        buttonHolder.add(stopButton);
        bottom.add(buttonHolder, BorderLayout.EAST);
        root.add(bottom, BorderLayout.SOUTH);
        return root;
    }

    SpsType getMovedPart()
    {
        return movedPart.getSelected();
    }

    void toFront()
    {
        if (dialog == null)
        {
            return;
        }
        dialog.toFront();
        dialog.requestFocus();
    }

    /**
     * Shows the state of the test session. The window closes itself when the app was stopped or started normally
     * (from the main window).
     */
    void showStatus(AppController.Status status)
    {
        if (closing)
        {
            return;
        }
        if (status.state() == AppController.State.STOPPED
                || (status.state() == AppController.State.RUNNING && !status.testMode()))
        {
            close();
            return;
        }
        statusText.setText(status.state() == AppController.State.RUNNING
                ? "Running: your Handy follows the depth." : status.message());
        statusText.setForeground(status.state() == AppController.State.ERROR ? Theme.ERROR : Theme.TEXT_SECONDARY);
        boolean failed = status.state() == AppController.State.ERROR;
        stopButton.setText(failed ? "Retry" : "Stop test");
        stopButton.setStyle(failed ? ModernButton.Style.PRIMARY : ModernButton.Style.SECONDARY);
        stopButton.setEnabled(status.state() != AppController.State.CONNECTING
                && status.state() != AppController.State.STOPPING);
        if (status.state() == AppController.State.RUNNING)
        {
            lastSent = Float.NaN; // A new session starts from scratch, so the current depth is sent again
        }
    }

    void close()
    {
        closing = true;
        sampler.stop();
        if (dialog != null)
        {
            dialog.dispose();
        }
    }

    boolean isClosed()
    {
        return closing;
    }

    /** Runs every {@link #SAMPLE_INTERVAL_MS}: advances the automatic strokes and sends the depth when it changed. */
    private void sample()
    {
        if (autoStroke.isSelected())
        {
            strokePhase = (strokePhase + SAMPLE_INTERVAL_MS / 60_000.0 * strokeSpeed.getValue()) % 1.0;
            scene.setDepth((float) (0.5 - 0.5 * Math.cos(2 * Math.PI * strokePhase)));
        }
        float insertion = scene.getInsertion();
        depthValue.setText(Math.round(insertion * 100) + "%");
        // VRChat only sends a value once it changed, so the same is done here
        if (insertion != lastSent)
        {
            lastSent = insertion;
            inputListener.accept(insertion);
        }
    }
}
