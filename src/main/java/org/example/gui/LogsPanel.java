package org.example.gui;

import ch.qos.logback.classic.Level;
import lombok.extern.slf4j.Slf4j;

import javax.swing.*;
import javax.swing.text.*;
import java.awt.*;
import java.awt.datatransfer.StringSelection;
import java.io.File;
import java.util.List;
import java.util.function.Supplier;

/**
 * The "Logs" tab: the messages the app logs, colored by level, newest at the bottom. Only the newest
 * {@link #MAX_LINES} lines are kept; everything is still written to the log files.
 */
@Slf4j
class LogsPanel extends JPanel
{
    private static final int MAX_LINES = 3_000;
    private static final int REFRESH_MS = 200;
    private static final File LOG_FOLDER = new File("logs");

    private final JTextPane text = new JTextPane();
    private final JScrollPane scrollPane;
    private final ToggleSwitch autoScroll = new ToggleSwitch(true);
    private final WrappingText levelHint = new WrappingText("", Theme.regular(12f), Theme.TEXT_MUTED);
    private final Supplier<String> logLevel;
    private final SimpleAttributeSet[] styles = new SimpleAttributeSet[5];
    private int lineCount;

    /** @param logLevel the configured logging level, shown as a hint */
    LogsPanel(Supplier<String> logLevel)
    {
        super(new BorderLayout(0, 10));
        this.logLevel = logLevel;
        setOpaque(false);

        text.setEditable(false);
        text.setOpaque(false);
        text.setFont(Theme.mono(11.5f));
        text.setForeground(Theme.TEXT);
        text.setCaretColor(Theme.TEXT);
        text.setSelectionColor(Theme.ACCENT_DARK);
        text.setSelectedTextColor(Theme.TEXT);
        text.setBorder(BorderFactory.createEmptyBorder(10, 12, 10, 12));
        // Scrolling is done explicitly (only while auto-scroll is on), not by the caret
        ((DefaultCaret) text.getCaret()).setUpdatePolicy(DefaultCaret.NEVER_UPDATE);
        styles[0] = style(Theme.ERROR);
        styles[1] = style(Theme.WARNING);
        styles[2] = style(Theme.TEXT);
        styles[3] = style(Theme.TEXT_SECONDARY);
        styles[4] = style(Theme.TEXT_MUTED);

        var console = new RoundedPanel(new BorderLayout(), Theme.HEADER, Theme.RADIUS * 2);
        console.setBorderColor(Theme.withAlpha(Theme.BORDER, 150));
        console.setBorder(BorderFactory.createEmptyBorder(2, 2, 2, 2));
        scrollPane = new JScrollPane(text, ScrollPaneConstants.VERTICAL_SCROLLBAR_ALWAYS,
                ScrollPaneConstants.HORIZONTAL_SCROLLBAR_NEVER);
        scrollPane.setBorder(BorderFactory.createEmptyBorder());
        scrollPane.setOpaque(false);
        scrollPane.getViewport().setOpaque(false);
        scrollPane.getVerticalScrollBar().setUI(new ThinScrollBarUI());
        scrollPane.getVerticalScrollBar().setOpaque(false);
        scrollPane.getVerticalScrollBar().setUnitIncrement(18);
        // Scrolling up to read something pauses auto-scroll, scrolling back to the bottom resumes it
        scrollPane.addMouseWheelListener(e -> SwingUtilities.invokeLater(() -> autoScroll.setSelected(isAtBottom())));
        console.add(scrollPane, BorderLayout.CENTER);

        add(buildToolbar(), BorderLayout.NORTH);
        add(console, BorderLayout.CENTER);
        new Timer(REFRESH_MS, e -> refresh()).start();
        refresh();
    }

    private JComponent buildToolbar()
    {
        var autoScrollLabel = new JLabel("Auto-scroll");
        autoScrollLabel.setFont(Theme.regular(12.5f));
        autoScrollLabel.setForeground(Theme.TEXT_SECONDARY);
        var autoScrollRow = new JPanel(new FlowLayout(FlowLayout.LEFT, 8, 0));
        autoScrollRow.setOpaque(false);
        autoScrollRow.add(autoScroll);
        autoScrollRow.add(autoScrollLabel);
        autoScrollRow.add(new InfoIcon());
        SettingRows.applyTooltip(autoScrollRow, Theme.tooltip(
                "Keeps the newest message in view. Scrolling up pauses it, scrolling back to the bottom resumes it."));
        autoScroll.addChangeListener(e ->
        {
            if (autoScroll.isSelected())
            {
                scrollToBottom();
            }
        });

        var copy = new ModernButton("Copy", ModernButton.Style.GHOST);
        copy.addActionListener(e -> Toolkit.getDefaultToolkit().getSystemClipboard()
                .setContents(new StringSelection(text.getText()), null));
        var clear = new ModernButton("Clear", ModernButton.Style.GHOST);
        clear.addActionListener(e ->
        {
            text.setText("");
            lineCount = 0;
        });
        var openFolder = new ModernButton("Open log folder", ModernButton.Style.GHOST);
        openFolder.setEnabled(Desktop.isDesktopSupported() && Desktop.getDesktop().isSupported(Desktop.Action.OPEN));
        openFolder.addActionListener(e -> openLogFolder());

        var buttons = new JPanel(new FlowLayout(FlowLayout.RIGHT, 2, 0));
        buttons.setOpaque(false);
        buttons.add(copy);
        buttons.add(clear);
        buttons.add(openFolder);

        var row = new JPanel(new BorderLayout());
        row.setOpaque(false);
        row.add(autoScrollRow, BorderLayout.WEST);
        row.add(buttons, BorderLayout.EAST);

        var toolbar = new JPanel(new BorderLayout(0, 6));
        toolbar.setOpaque(false);
        levelHint.setBorder(BorderFactory.createEmptyBorder(0, 4, 0, 4));
        toolbar.add(levelHint, BorderLayout.NORTH);
        toolbar.add(row, BorderLayout.CENTER);
        return toolbar;
    }

    private void refresh()
    {
        levelHint.setText("Showing messages of level %s and above. The logging level is set on the Advanced tab."
                .formatted(logLevel.get()));
        List<LogCapture.Entry> entries = LogCapture.get().drain();
        long dropped = LogCapture.get().takeDroppedCount();
        if (entries.isEmpty() && dropped == 0)
        {
            return;
        }
        StyledDocument document = text.getStyledDocument();
        try
        {
            if (dropped > 0)
            {
                document.insertString(document.getLength(),
                        "... %d messages skipped here (too many at once), see the log file ...\n".formatted(dropped), styles[1]);
                lineCount++;
            }
            for (LogCapture.Entry entry : entries)
            {
                document.insertString(document.getLength(), entry.text(), styleFor(entry.level()));
                lineCount += countLines(entry.text());
            }
            trim(document);
        }
        catch (BadLocationException e)
        {
            log.debug("Could not show log messages: {}", e.getMessage());
        }
        if (autoScroll.isSelected())
        {
            scrollToBottom();
        }
    }

    /** Removes the oldest lines, so that the view never holds more than {@link #MAX_LINES}. */
    private void trim(StyledDocument document) throws BadLocationException
    {
        if (lineCount <= MAX_LINES)
        {
            return;
        }
        Element root = document.getDefaultRootElement();
        int linesToRemove = Math.min(lineCount - MAX_LINES, root.getElementCount() - 1);
        if (linesToRemove <= 0)
        {
            return;
        }
        int end = root.getElement(linesToRemove - 1).getEndOffset();
        document.remove(0, end);
        lineCount -= linesToRemove;
    }

    private void scrollToBottom()
    {
        SwingUtilities.invokeLater(() ->
        {
            JScrollBar bar = scrollPane.getVerticalScrollBar();
            bar.setValue(bar.getMaximum());
        });
    }

    private boolean isAtBottom()
    {
        JScrollBar bar = scrollPane.getVerticalScrollBar();
        return bar.getValue() + bar.getVisibleAmount() >= bar.getMaximum() - 4;
    }

    private void openLogFolder()
    {
        try
        {
            LOG_FOLDER.mkdirs();
            Desktop.getDesktop().open(LOG_FOLDER);
        }
        catch (Exception e)
        {
            log.warn("Could not open the log folder {}: {}", LOG_FOLDER.getAbsolutePath(), e.getMessage());
        }
    }

    private SimpleAttributeSet styleFor(Level level)
    {
        return switch (level.toInt())
        {
            case Level.ERROR_INT -> styles[0];
            case Level.WARN_INT -> styles[1];
            case Level.INFO_INT -> styles[2];
            case Level.DEBUG_INT -> styles[3];
            default -> styles[4];
        };
    }

    private static SimpleAttributeSet style(Color color)
    {
        var style = new SimpleAttributeSet();
        StyleConstants.setForeground(style, color);
        return style;
    }

    private static int countLines(String text)
    {
        int lines = 0;
        for (int i = 0; i < text.length(); i++)
        {
            if (text.charAt(i) == '\n')
            {
                lines++;
            }
        }
        return Math.max(lines, 1);
    }
}
