package org.example.gui;

import javax.swing.*;
import java.awt.*;
import java.awt.event.KeyEvent;
import java.util.Arrays;

/**
 * Small confirmation dialog styled like the rest of the app. The stock {@link JOptionPane} cannot be themed: its
 * Metal buttons ignore the dark colors, and a long message is cut off instead of wrapped.
 */
final class ConfirmDialog
{
    private static final int MESSAGE_WIDTH = 520;

    private ConfirmDialog()
    {
    }

    /**
     * Shows the dialog and waits until it is closed; has to be called on the event dispatch thread.
     *
     * @param parent      component the dialog is centered on
     * @param title       window title
     * @param heading     emphasized first line
     * @param message     body text; line breaks are kept and long lines wrap
     * @param confirmText text of the confirming button
     * @return true when the user confirmed
     */
    static boolean show(Component parent, String title, String heading, String message, String confirmText)
    {
        Window owner = SwingUtilities.getWindowAncestor(parent);
        if (owner == null)
        {
            owner = Arrays.stream(Window.getWindows())
                    .filter(window -> window instanceof Frame && window.isShowing())
                    .findFirst()
                    .orElse(null);
        }
        var dialog = new JDialog(owner, title, Dialog.ModalityType.APPLICATION_MODAL);
        dialog.setDefaultCloseOperation(WindowConstants.DISPOSE_ON_CLOSE);
        if (owner != null)
        {
            dialog.setIconImages(owner.getIconImages());
        }

        boolean[] confirmed = {false};
        var confirmButton = new ModernButton(confirmText, ModernButton.Style.PRIMARY);
        confirmButton.addActionListener(e ->
        {
            confirmed[0] = true;
            dialog.dispose();
        });
        var cancelButton = new ModernButton("Cancel", ModernButton.Style.SECONDARY);
        cancelButton.addActionListener(e -> dialog.dispose());

        dialog.setContentPane(content(heading, message, confirmButton, cancelButton));
        dialog.getRootPane().setDefaultButton(confirmButton);
        dialog.getRootPane().registerKeyboardAction(e -> dialog.dispose(),
                KeyStroke.getKeyStroke(KeyEvent.VK_ESCAPE, 0), JComponent.WHEN_IN_FOCUSED_WINDOW);
        dialog.setResizable(false);
        dialog.pack();
        dialog.setLocationRelativeTo(owner);
        cancelButton.requestFocusInWindow();
        dialog.setVisible(true);
        return confirmed[0];
    }

    /** The dialog content (package-private so that it can be rendered without a window). */
    static JComponent content(String heading, String message, JButton confirmButton, JButton cancelButton)
    {
        var root = new JPanel(new BorderLayout(0, 18));
        root.setBackground(Theme.BACKGROUND);
        root.setBorder(BorderFactory.createEmptyBorder(22, 24, 20, 24));

        var text = new JPanel();
        text.setOpaque(false);
        text.setLayout(new BoxLayout(text, BoxLayout.Y_AXIS));

        var headingLabel = new JLabel(heading);
        headingLabel.setFont(Theme.bold(16f));
        headingLabel.setForeground(Theme.TEXT);
        headingLabel.setAlignmentX(Component.LEFT_ALIGNMENT);
        text.add(headingLabel);
        text.add(Box.createVerticalStrut(8));

        var messageLabel = new JLabel(html(message));
        messageLabel.setFont(Theme.regular(12.5f));
        messageLabel.setForeground(Theme.TEXT_SECONDARY);
        messageLabel.setAlignmentX(Component.LEFT_ALIGNMENT);
        text.add(messageLabel);

        Icon warning = UIManager.getIcon("OptionPane.warningIcon");
        if (warning != null)
        {
            var icon = new JLabel(warning);
            icon.setVerticalAlignment(SwingConstants.TOP);
            icon.setBorder(BorderFactory.createEmptyBorder(2, 0, 0, 14));
            var body = new JPanel(new BorderLayout());
            body.setOpaque(false);
            body.add(icon, BorderLayout.WEST);
            body.add(text, BorderLayout.CENTER);
            root.add(body, BorderLayout.CENTER);
        }
        else
        {
            root.add(text, BorderLayout.CENTER);
        }

        var buttons = new JPanel(new FlowLayout(FlowLayout.RIGHT, 0, 0));
        buttons.setOpaque(false);
        buttons.add(cancelButton);
        buttons.add(Box.createHorizontalStrut(10));
        buttons.add(confirmButton);
        root.add(buttons, BorderLayout.SOUTH);
        return root;
    }

    private static String html(String message)
    {
        String escaped = message.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;");
        return "<html><div style='width:" + MESSAGE_WIDTH + "px'>" + escaped.replace("\n", "<br>")
                + "</div></html>";
    }
}
