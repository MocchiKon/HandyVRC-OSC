package org.example.gui;

import javax.swing.*;
import javax.swing.plaf.basic.BasicComboBoxUI;
import java.awt.*;
import java.awt.geom.Path2D;
import java.awt.geom.RoundRectangle2D;

/** Read-only dropdown with the dark rounded look of the other settings controls. */
public class ModernComboBox<T> extends JComboBox<T>
{
    public ModernComboBox()
    {
        setOpaque(false);
        setFocusable(true);
        setFont(Theme.regular(13.5f));
        setForeground(Theme.TEXT);
        setBackground(Theme.HEADER);
        setBorder(BorderFactory.createEmptyBorder(2, 6, 2, 2));
        setMaximumRowCount(10);
        setCursor(Cursor.getPredefinedCursor(Cursor.HAND_CURSOR));
        setRenderer(new Renderer());
        setUI(new BasicComboBoxUI()
        {
            @Override
            protected JButton createArrowButton()
            {
                var button = new JButton()
                {
                    @Override
                    protected void paintComponent(Graphics graphics)
                    {
                        Graphics2D g = Theme.smooth(graphics);
                        int centerX = getWidth() / 2;
                        int centerY = getHeight() / 2;
                        var chevron = new Path2D.Float();
                        chevron.moveTo(centerX - 4.5f, centerY - 2.5f);
                        chevron.lineTo(centerX, centerY + 2.5f);
                        chevron.lineTo(centerX + 4.5f, centerY - 2.5f);
                        g.setColor(isEnabled() ? Theme.ACCENT_LIGHT : Theme.TEXT_DISABLED);
                        g.setStroke(new BasicStroke(1.8f, BasicStroke.CAP_ROUND, BasicStroke.JOIN_ROUND));
                        g.draw(chevron);
                        g.dispose();
                    }
                };
                button.setOpaque(false);
                button.setContentAreaFilled(false);
                button.setBorderPainted(false);
                button.setFocusable(false);
                button.setCursor(Cursor.getPredefinedCursor(Cursor.HAND_CURSOR));
                button.setBorder(BorderFactory.createEmptyBorder(0, 10, 0, 10));
                return button;
            }

            @Override
            public void paintCurrentValueBackground(Graphics graphics, Rectangle bounds, boolean hasFocus)
            {
                // The rounded background is painted by ModernComboBox itself.
            }

            /**
             * Paints the selected value with the theme text color. The default implementation replaces the
             * renderer's color with the list selection color whenever the combo has focus; in this theme that is
             * {@code #333333}, which made the selected device name look grey after it was picked.
             */
            @Override
            public void paintCurrentValue(Graphics graphics, Rectangle bounds, boolean hasFocus)
            {
                ListCellRenderer<Object> renderer = comboBox.getRenderer();
                Component value = renderer.getListCellRendererComponent(listBox, comboBox.getSelectedItem(), -1,
                        false, false);
                value.setFont(comboBox.getFont());
                value.setForeground(comboBox.isEnabled() ? Theme.TEXT : Theme.TEXT_DISABLED);
                value.setBackground(comboBox.getBackground());
                Rectangle area = new Rectangle(bounds);
                if (padding != null)
                {
                    area.x += padding.left;
                    area.y += padding.top;
                    area.width -= padding.left + padding.right;
                    area.height -= padding.top + padding.bottom;
                }
                currentValuePane.paintComponent(graphics, value, comboBox, area.x, area.y, area.width, area.height,
                        value instanceof JPanel);
            }
        });
        addFocusListener(new java.awt.event.FocusAdapter()
        {
            @Override
            public void focusGained(java.awt.event.FocusEvent e)
            {
                repaint();
            }

            @Override
            public void focusLost(java.awt.event.FocusEvent e)
            {
                repaint();
            }
        });
    }

    @Override
    protected void paintComponent(Graphics graphics)
    {
        Graphics2D g = Theme.smooth(graphics);
        var shape = new RoundRectangle2D.Float(0.5f, 0.5f, getWidth() - 1f, getHeight() - 1f,
                Theme.CONTROL_RADIUS * 2, Theme.CONTROL_RADIUS * 2);
        g.setColor(isEnabled() ? Theme.HEADER : Theme.withAlpha(Theme.HEADER, 140));
        g.fill(shape);
        g.dispose();
        super.paintComponent(graphics);

        Graphics2D border = Theme.smooth(graphics);
        border.setColor(isFocusOwner() ? Theme.ACCENT : Theme.BORDER);
        border.setStroke(new BasicStroke(isFocusOwner() ? 1.5f : 1f));
        border.draw(shape);
        border.dispose();
    }

    /**
     * Dark list entries. Every entry uses the same white text (the highlighted row is shown by its background), so a
     * selected device never looks disabled or muted next to the other choices. The enabled state is taken from the
     * combo itself: the internal list the renderer is asked for may not follow it, which used to leave the selected
     * value grey after a device was picked.
     */
    private final class Renderer extends DefaultListCellRenderer
    {
        @Override
        public Component getListCellRendererComponent(JList<?> list, Object value, int index, boolean selected,
                                                      boolean hasFocus)
        {
            JLabel label = (JLabel) super.getListCellRendererComponent(list, value, index, selected, hasFocus);
            label.setFont(Theme.regular(13.5f));
            label.setBorder(BorderFactory.createEmptyBorder(6, 8, 6, 8));
            label.setEnabled(ModernComboBox.this.isEnabled());
            label.setForeground(ModernComboBox.this.isEnabled() ? Theme.TEXT : Theme.TEXT_DISABLED);
            if (index < 0)
            {
                // The value shown in the closed combo: the rounded background belongs to the combo itself.
                label.setOpaque(false);
            }
            else
            {
                label.setOpaque(true);
                label.setBackground(selected ? Theme.SURFACE_HOVER : Theme.SURFACE_RAISED);
            }
            return label;
        }
    }
}
