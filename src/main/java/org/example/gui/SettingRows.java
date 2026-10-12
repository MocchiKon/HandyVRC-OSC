package org.example.gui;

import javax.swing.*;
import java.awt.*;
import java.awt.font.TextAttribute;
import java.util.Map;
import java.util.function.IntFunction;

/** Builds the cards and the rows (name, explanation tooltip and control) of the settings pages. */
final class SettingRows
{
    private static final int ROW_GAP = 16;

    private SettingRows()
    {
    }

    /** Card with a small uppercase title; rows are added with {@link #addRow}. */
    static RoundedPanel section(String title)
    {
        var section = new RoundedPanel(null, Theme.SURFACE, Theme.RADIUS * 2);
        section.setBorderColor(Theme.withAlpha(Theme.BORDER, 150));
        section.setLayout(new BoxLayout(section, BoxLayout.Y_AXIS));
        section.setBorder(BorderFactory.createEmptyBorder(16, 18, 18, 18));
        section.setAlignmentX(Component.LEFT_ALIGNMENT);

        var label = new JLabel(title.toUpperCase());
        label.setFont(Theme.bold(11f).deriveFont(Map.of(TextAttribute.TRACKING, 0.08)));
        label.setForeground(Theme.ACCENT_LIGHT);
        label.setAlignmentX(Component.LEFT_ALIGNMENT);
        label.setBorder(BorderFactory.createEmptyBorder(0, 0, 4, 0));
        section.add(label);
        return section;
    }

    static void addRow(JComponent section, JComponent row)
    {
        section.add(Box.createVerticalStrut(ROW_GAP - 4));
        row.setAlignmentX(Component.LEFT_ALIGNMENT);
        section.add(row);
    }

    /** Setting name followed by the info icon. */
    static JPanel title(String name)
    {
        var panel = new JPanel()
        {
            @Override
            public Dimension getMaximumSize()
            {
                return getPreferredSize();
            }
        };
        panel.setOpaque(false);
        panel.setLayout(new BoxLayout(panel, BoxLayout.X_AXIS));
        var label = new JLabel(name);
        label.setFont(Theme.font(Font.PLAIN, 13.5f));
        label.setForeground(Theme.TEXT);
        panel.add(label);
        panel.add(Box.createHorizontalStrut(6));
        panel.add(new InfoIcon());
        return panel;
    }

    static WrappingText description(String text)
    {
        return new WrappingText(text, Theme.regular(12f), Theme.TEXT_MUTED);
    }

    /** Name and current value on one line, the slider below. */
    static JPanel sliderRow(String name, String tooltip, ModernSlider slider, IntFunction<String> valueText)
    {
        var header = new JPanel(new BorderLayout());
        header.setOpaque(false);
        header.add(title(name), BorderLayout.WEST);
        var value = new JLabel();
        value.setFont(Theme.bold(13f));
        value.setForeground(Theme.ACCENT_LIGHT);
        header.add(value, BorderLayout.EAST);
        Runnable updateValue = () -> value.setText(valueText.apply(slider.getValue()));
        updateValue.run();
        slider.addChangeListener(e -> updateValue.run());

        var row = verticalRow();
        row.add(header);
        row.add(Box.createVerticalStrut(2));
        row.add(slider);
        return finish(row, tooltip);
    }

    /** Range slider row: the value text is built from both ends of the range. */
    static JPanel rangeRow(String name, String tooltip, ModernSlider slider, java.util.function.BiFunction<Integer, Integer, String> valueText)
    {
        return sliderRow(name, tooltip, slider, ignored -> valueText.apply(slider.getLowValue(), slider.getHighValue()));
    }

    /** Name and a short description on the left, the switch on the right. */
    static JPanel toggleRow(String name, String descriptionText, String tooltip, ToggleSwitch toggle)
    {
        var texts = verticalRow();
        texts.add(title(name));
        if (descriptionText != null)
        {
            texts.add(Box.createVerticalStrut(2));
            texts.add(description(descriptionText));
        }
        for (Component child : texts.getComponents())
        {
            ((JComponent) child).setAlignmentX(Component.LEFT_ALIGNMENT);
        }
        var row = new JPanel(new BorderLayout(12, 0));
        row.setOpaque(false);
        row.add(texts, BorderLayout.CENTER);
        var toggleHolder = new JPanel(new GridBagLayout());
        toggleHolder.setOpaque(false);
        toggleHolder.add(toggle);
        row.add(toggleHolder, BorderLayout.EAST);
        return finish(row, tooltip);
    }

    /** Name on the first line, the control (stretched to the full width) below. */
    static JPanel controlRow(String name, String tooltip, JComponent control)
    {
        if (control instanceof SegmentedControl<?> options)
        {
            // The options are explained in the info tooltip of the row, not when hovering over them
            tooltip = tooltip + "\n\n" + options.describeOptions();
        }
        var row = verticalRow();
        row.add(title(name));
        row.add(Box.createVerticalStrut(8));
        row.add(control);
        return finish(row, tooltip);
    }

    static JPanel verticalRow()
    {
        var row = new JPanel()
        {
            @Override
            public Dimension getMaximumSize()
            {
                return new Dimension(Integer.MAX_VALUE, getPreferredSize().height);
            }
        };
        row.setOpaque(false);
        row.setLayout(new BoxLayout(row, BoxLayout.Y_AXIS));
        return row;
    }

    private static JPanel finish(JPanel row, String tooltip)
    {
        for (Component child : row.getComponents())
        {
            if (child instanceof JComponent component)
            {
                component.setAlignmentX(Component.LEFT_ALIGNMENT);
            }
        }
        applyTooltip(row, Theme.tooltip(tooltip));
        return row;
    }

    /**
     * Sets the explanation of a setting on the info icon(s) inside it. Only the icon shows it: a tooltip on the
     * name, the value and the control as well would pop up (and move along) wherever the mouse goes.
     */
    static void applyTooltip(Component component, String tooltip)
    {
        if (component instanceof InfoIcon icon)
        {
            icon.setToolTipText(tooltip);
        }
        if (component instanceof Container container)
        {
            for (Component child : container.getComponents())
            {
                applyTooltip(child, tooltip);
            }
        }
    }
}
