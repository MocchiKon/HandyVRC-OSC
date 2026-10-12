package org.example.gui;

import javax.swing.*;
import java.awt.*;
import java.awt.event.FocusAdapter;
import java.awt.event.FocusEvent;
import java.awt.geom.RoundRectangle2D;

/** Text field with a rounded dark background; the outline turns blue while focused and red while invalid. */
public class ModernTextField extends JTextField
{
    private boolean invalid;

    public ModernTextField(String text, int columns)
    {
        super(text, columns);
        setOpaque(false);
        setFont(Theme.regular(13.5f));
        setForeground(Theme.TEXT);
        setDisabledTextColor(Theme.TEXT_MUTED);
        setCaretColor(Theme.TEXT);
        setSelectionColor(Theme.ACCENT_DARK);
        setSelectedTextColor(Theme.TEXT);
        setBorder(BorderFactory.createEmptyBorder(9, 12, 9, 12));
        addFocusListener(new FocusAdapter()
        {
            @Override
            public void focusGained(FocusEvent e)
            {
                repaint();
            }

            @Override
            public void focusLost(FocusEvent e)
            {
                repaint();
            }
        });
    }

    public void setInvalid(boolean invalid)
    {
        if (this.invalid != invalid)
        {
            this.invalid = invalid;
            repaint();
        }
    }

    @Override
    protected void paintComponent(Graphics graphics)
    {
        Graphics2D g = Theme.smooth(graphics);
        var shape = new RoundRectangle2D.Float(0.5f, 0.5f, getWidth() - 1f, getHeight() - 1f,
                Theme.CONTROL_RADIUS * 2, Theme.CONTROL_RADIUS * 2);
        g.setColor(isEnabled() ? Theme.HEADER : Theme.withAlpha(Theme.HEADER, 140));
        g.fill(shape);
        g.setColor(invalid ? Theme.ERROR : isFocusOwner() ? Theme.ACCENT : Theme.BORDER);
        g.setStroke(new BasicStroke(isFocusOwner() || invalid ? 1.5f : 1f));
        g.draw(shape);
        g.dispose();
        super.paintComponent(graphics);
    }
}
