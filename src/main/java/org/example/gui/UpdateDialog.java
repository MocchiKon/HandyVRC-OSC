package org.example.gui;

import javax.swing.*;
import java.awt.*;
import java.awt.event.KeyEvent;
import java.awt.event.MouseAdapter;
import java.awt.event.MouseEvent;
import java.util.Arrays;

/**
 * Tells the user that a newer version was released, styled like the rest of the GUI (the stock option pane cannot
 * be themed properly: its Metal buttons ignore the dark colors).
 */
public final class UpdateDialog
{
    private UpdateDialog()
    {
    }

    /**
     * Shows the dialog and waits until it is closed; has to be called on the event dispatch thread.
     *
     * @param onOpenDownloadPage called when the user asks to open the download page
     */
    public static void show(String latestVersion, String currentVersion, String downloadUrl, Runnable onOpenDownloadPage)
    {
        Window owner = Arrays.stream(Window.getWindows())
                .filter(window -> window instanceof Frame && window.isShowing())
                .findFirst()
                .orElse(null);
        var dialog = new JDialog(owner, "Update available", Dialog.ModalityType.APPLICATION_MODAL);
        dialog.setDefaultCloseOperation(WindowConstants.DISPOSE_ON_CLOSE);
        if (owner != null)
        {
            dialog.setIconImages(owner.getIconImages());
        }

        var openButton = new ModernButton("Open download page", ModernButton.Style.PRIMARY);
        openButton.addActionListener(e ->
        {
            dialog.dispose();
            onOpenDownloadPage.run();
        });
        var closeButton = new ModernButton("Close", ModernButton.Style.SECONDARY);
        closeButton.addActionListener(e -> dialog.dispose());

        dialog.setContentPane(content(latestVersion, currentVersion, downloadUrl, onOpenDownloadPage, openButton, closeButton));
        dialog.getRootPane().setDefaultButton(openButton);
        dialog.getRootPane().registerKeyboardAction(e -> dialog.dispose(),
                KeyStroke.getKeyStroke(KeyEvent.VK_ESCAPE, 0), JComponent.WHEN_IN_FOCUSED_WINDOW);
        dialog.setResizable(false);
        dialog.pack();
        dialog.setLocationRelativeTo(owner);
        openButton.requestFocusInWindow();
        dialog.setVisible(true);
    }

    /** The dialog content (package-private so that it can be rendered without a window). */
    static JComponent content(String latestVersion, String currentVersion, String downloadUrl,
                               Runnable onOpenDownloadPage, JButton openButton, JButton closeButton)
    {
        var root = new JPanel(new BorderLayout(0, 18));
        root.setBackground(Theme.BACKGROUND);
        root.setBorder(BorderFactory.createEmptyBorder(22, 24, 20, 24));

        var text = new JPanel();
        text.setOpaque(false);
        text.setLayout(new BoxLayout(text, BoxLayout.Y_AXIS));

        var title = label("A new version is available", Theme.bold(16f), Theme.TEXT);
        text.add(title);
        text.add(Box.createVerticalStrut(6));
        text.add(label("HandyVRC-OSC " + latestVersion + " has been released.", Theme.regular(12.5f),
                Theme.TEXT_SECONDARY));
        text.add(Box.createVerticalStrut(16));

        var versions = new RoundedPanel(new GridBagLayout(), Theme.SURFACE, Theme.RADIUS);
        versions.setBorderColor(Theme.BORDER);
        versions.setBorder(BorderFactory.createEmptyBorder(12, 16, 12, 16));
        versions.setAlignmentX(Component.LEFT_ALIGNMENT);
        addVersionRow(versions, 0, "Newest version", latestVersion, Theme.ACCENT_LIGHT);
        addVersionRow(versions, 1, "Your version", currentVersion, Theme.TEXT);
        text.add(versions);
        text.add(Box.createVerticalStrut(12));

        var link = label(downloadUrl, Theme.regular(11.5f), Theme.ACCENT_LIGHT);
        link.setCursor(Cursor.getPredefinedCursor(Cursor.HAND_CURSOR));
        link.setToolTipText(Theme.tooltip("Open the download page in your browser"));
        link.addMouseListener(new MouseAdapter()
        {
            @Override
            public void mouseClicked(MouseEvent e)
            {
                onOpenDownloadPage.run();
            }

            @Override
            public void mouseEntered(MouseEvent e)
            {
                link.setForeground(Theme.TEXT);
            }

            @Override
            public void mouseExited(MouseEvent e)
            {
                link.setForeground(Theme.ACCENT_LIGHT);
            }
        });
        text.add(link);
        text.add(Box.createVerticalStrut(14));
        text.add(label("You can turn this startup check off with 'Check for updates' in the Advanced settings.",
                Theme.regular(11f), Theme.TEXT_MUTED));
        root.add(text, BorderLayout.CENTER);

        var buttons = new JPanel(new FlowLayout(FlowLayout.RIGHT, 0, 0));
        buttons.setOpaque(false);
        buttons.add(closeButton);
        buttons.add(Box.createHorizontalStrut(10));
        buttons.add(openButton);
        root.add(buttons, BorderLayout.SOUTH);
        return root;
    }

    private static void addVersionRow(JPanel panel, int row, String name, String version, Color versionColor)
    {
        var constraints = new GridBagConstraints();
        constraints.gridy = row;
        constraints.anchor = GridBagConstraints.WEST;
        constraints.insets = new Insets(row == 0 ? 0 : 6, 0, 0, 0);
        constraints.gridx = 0;
        constraints.weightx = 1;
        panel.add(label(name, Theme.regular(12.5f), Theme.TEXT_SECONDARY), constraints);
        constraints.gridx = 1;
        constraints.weightx = 0;
        constraints.anchor = GridBagConstraints.EAST;
        constraints.insets.left = 24;
        panel.add(label(version, Theme.bold(12.5f), versionColor), constraints);
    }

    private static JLabel label(String text, Font font, Color color)
    {
        var label = new JLabel(text);
        label.setFont(font);
        label.setForeground(color);
        label.setAlignmentX(Component.LEFT_ALIGNMENT);
        return label;
    }
}
