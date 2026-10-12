package org.example.gui;

import javax.swing.*;
import java.awt.*;
import java.awt.geom.Path2D;
import java.awt.geom.RoundRectangle2D;

/** Flat, rounded button: a filled bright blue one for the main action, an outlined one for the others. */
public class ModernButton extends JButton
{
    public enum Style
    {
        PRIMARY, SECONDARY, GHOST
    }

    public enum Glyph
    {
        NONE, PLAY, STOP, REFRESH
    }

    private Style style;
    private Glyph glyph = Glyph.NONE;

    public ModernButton(String text, Style style)
    {
        super(text);
        this.style = style;
        setFont(Theme.bold(style == Style.GHOST ? 12.5f : 14f));
        setForeground(Theme.TEXT);
        setContentAreaFilled(false);
        setBorderPainted(false);
        setFocusPainted(false);
        setOpaque(false);
        setRolloverEnabled(true);
        setCursor(Cursor.getPredefinedCursor(Cursor.HAND_CURSOR));
        setBorder(BorderFactory.createEmptyBorder(style == Style.GHOST ? 6 : 11, 18, style == Style.GHOST ? 6 : 11, 18));
    }

    public void setStyle(Style style)
    {
        this.style = style;
        repaint();
    }

    public void setGlyph(Glyph glyph)
    {
        this.glyph = glyph;
        revalidate();
        repaint();
    }

    @Override
    public Dimension getPreferredSize()
    {
        Dimension size = super.getPreferredSize();
        if (glyph != Glyph.NONE)
        {
            size.width += 22;
        }
        return size;
    }

    @Override
    protected void paintComponent(Graphics graphics)
    {
        Graphics2D g = Theme.smooth(graphics);
        ButtonModel model = getModel();
        boolean enabled = isEnabled();
        boolean hover = enabled && model.isRollover();
        boolean pressed = enabled && model.isPressed();
        var shape = new RoundRectangle2D.Float(0.5f, 0.5f, getWidth() - 1f, getHeight() - 1f,
                Theme.CONTROL_RADIUS * 2, Theme.CONTROL_RADIUS * 2);

        Color textColor = enabled ? Theme.TEXT : Theme.TEXT_DISABLED;
        switch (style)
        {
            case PRIMARY ->
            {
                Color top = !enabled ? Theme.SURFACE_RAISED : pressed ? Theme.ACCENT_DARK : hover ? Theme.ACCENT_LIGHT : Theme.ACCENT;
                Color bottom = !enabled ? Theme.SURFACE_RAISED : pressed ? Theme.ACCENT_DARK : Theme.ACCENT_DARK;
                g.setPaint(new GradientPaint(0, 0, top, 0, getHeight(), bottom));
                g.fill(shape);
            }
            case SECONDARY ->
            {
                g.setColor(pressed ? Theme.SURFACE_RAISED : hover ? Theme.SURFACE_HOVER : Theme.SURFACE);
                g.fill(shape);
                g.setColor(enabled ? (hover ? Theme.ACCENT : Theme.BORDER) : Theme.SURFACE_RAISED);
                g.draw(shape);
            }
            case GHOST ->
            {
                if (hover || pressed)
                {
                    g.setColor(pressed ? Theme.SURFACE_RAISED : Theme.SURFACE_HOVER);
                    g.fill(shape);
                }
                textColor = enabled ? (hover ? Theme.TEXT : Theme.ACCENT_LIGHT) : Theme.TEXT_DISABLED;
            }
        }
        if (isFocusOwner())
        {
            g.setColor(Theme.withAlpha(Theme.ACCENT_LIGHT, 160));
            g.setStroke(new BasicStroke(1.5f));
            g.draw(new RoundRectangle2D.Float(2f, 2f, getWidth() - 4f, getHeight() - 4f,
                    Theme.CONTROL_RADIUS * 2 - 3, Theme.CONTROL_RADIUS * 2 - 3));
        }

        g.setFont(getFont());
        FontMetrics metrics = g.getFontMetrics();
        int textWidth = metrics.stringWidth(getText());
        int glyphSize = glyph == Glyph.NONE ? 0 : 12;
        int gap = glyph == Glyph.NONE ? 0 : 10;
        int x = (getWidth() - textWidth - glyphSize - gap) / 2;
        int textY = (getHeight() - metrics.getHeight()) / 2 + metrics.getAscent();
        g.setColor(textColor);
        if (glyph != Glyph.NONE)
        {
            paintGlyph(g, x, getHeight() / 2 - glyphSize / 2, glyphSize);
        }
        g.drawString(getText(), x + glyphSize + gap, textY);
        g.dispose();
    }

    private void paintGlyph(Graphics2D g, int x, int y, int size)
    {
        switch (glyph)
        {
            case PLAY ->
            {
                var triangle = new Path2D.Float();
                triangle.moveTo(x + 1, y);
                triangle.lineTo(x + size, y + size / 2f);
                triangle.lineTo(x + 1, y + size);
                triangle.closePath();
                g.fill(triangle);
            }
            case STOP -> g.fill(new RoundRectangle2D.Float(x + 1, y + 1, size - 2, size - 2, 4, 4));
            case REFRESH ->
            {
                g.setStroke(new BasicStroke(2f, BasicStroke.CAP_ROUND, BasicStroke.JOIN_ROUND));
                g.drawArc(x + 1, y + 1, size - 2, size - 2, 60, 270);
                var head = new Path2D.Float();
                head.moveTo(x + size - 1, y - 1);
                head.lineTo(x + size - 1, y + 5);
                head.lineTo(x + size - 7, y + 4);
                head.closePath();
                g.fill(head);
            }
            default ->
            {
            }
        }
    }
}
