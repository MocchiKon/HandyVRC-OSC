package org.example.gui;

import javax.swing.*;
import java.awt.*;
import java.awt.geom.Path2D;
import java.awt.geom.RoundRectangle2D;

/** Small square button that only shows a glyph (its meaning is given by the tooltip). */
public class IconButton extends JButton
{
    public enum Glyph
    {
        /** Square with an arrow out of its corner: show in a separate window. */
        DETACH,
        /** Arrow into a square: put back into the main window. */
        ATTACH,
        /** Pin: keep the window above the other windows. */
        PIN
    }

    private final Glyph glyph;
    private boolean toggled;

    public IconButton(Glyph glyph, String tooltip)
    {
        this.glyph = glyph;
        setToolTipText(Theme.tooltip(tooltip));
        setContentAreaFilled(false);
        setBorderPainted(false);
        setFocusPainted(false);
        setOpaque(false);
        setRolloverEnabled(true);
        setCursor(Cursor.getPredefinedCursor(Cursor.HAND_CURSOR));
    }

    /** A toggled button (for example an active pin) is drawn highlighted. */
    public void setToggled(boolean toggled)
    {
        this.toggled = toggled;
        repaint();
    }

    @Override
    public Dimension getPreferredSize()
    {
        int size = Math.round(28 * Theme.FONT_SCALE);
        return new Dimension(size, size);
    }

    @Override
    public Dimension getMaximumSize()
    {
        return getPreferredSize();
    }

    @Override
    protected void paintComponent(Graphics graphics)
    {
        Graphics2D g = Theme.smooth(graphics);
        boolean hover = getModel().isRollover();
        if (hover || getModel().isPressed() || toggled)
        {
            g.setColor(getModel().isPressed() ? Theme.SURFACE_RAISED : toggled ? Theme.withAlpha(Theme.ACCENT, 60) : Theme.SURFACE_HOVER);
            g.fill(new RoundRectangle2D.Float(0, 0, getWidth(), getHeight(), 10, 10));
        }
        if (isFocusOwner())
        {
            g.setColor(Theme.withAlpha(Theme.ACCENT_LIGHT, 150));
            g.draw(new RoundRectangle2D.Float(0.5f, 0.5f, getWidth() - 1, getHeight() - 1, 10, 10));
        }
        g.setColor(hover || toggled ? Theme.ACCENT_LIGHT : Theme.TEXT_SECONDARY);
        g.setStroke(new BasicStroke(1.7f, BasicStroke.CAP_ROUND, BasicStroke.JOIN_ROUND));
        float s = Math.min(getWidth(), getHeight()) * 0.5f;
        float x = (getWidth() - s) / 2f;
        float y = (getHeight() - s) / 2f;
        switch (glyph)
        {
            case DETACH ->
            {
                // Open box (bottom left) and an arrow leaving it towards the top right corner
                var box = new Path2D.Float();
                box.moveTo(x + s * 0.45f, y + s * 0.1f);
                box.lineTo(x, y + s * 0.1f);
                box.lineTo(x, y + s);
                box.lineTo(x + s * 0.9f, y + s);
                box.lineTo(x + s * 0.9f, y + s * 0.55f);
                g.draw(box);
                arrow(g, x + s * 0.4f, y + s * 0.6f, x + s * 1.05f, y - s * 0.05f);
            }
            case ATTACH ->
            {
                var box = new Path2D.Float();
                box.moveTo(x + s * 0.45f, y + s * 0.1f);
                box.lineTo(x, y + s * 0.1f);
                box.lineTo(x, y + s);
                box.lineTo(x + s * 0.9f, y + s);
                box.lineTo(x + s * 0.9f, y + s * 0.55f);
                g.draw(box);
                arrow(g, x + s * 1.05f, y - s * 0.05f, x + s * 0.4f, y + s * 0.6f);
            }
            case PIN ->
            {
                var head = new Path2D.Float();
                head.moveTo(x + s * 0.3f, y);
                head.lineTo(x + s * 0.7f, y);
                head.lineTo(x + s * 0.62f, y + s * 0.4f);
                head.lineTo(x + s * 0.85f, y + s * 0.62f);
                head.lineTo(x + s * 0.15f, y + s * 0.62f);
                head.lineTo(x + s * 0.38f, y + s * 0.4f);
                head.closePath();
                if (toggled)
                {
                    g.fill(head);
                }
                g.draw(head);
                g.draw(new java.awt.geom.Line2D.Float(x + s * 0.5f, y + s * 0.62f, x + s * 0.5f, y + s * 1.05f));
            }
        }
        g.dispose();
    }

    private static void arrow(Graphics2D g, float fromX, float fromY, float toX, float toY)
    {
        g.draw(new java.awt.geom.Line2D.Float(fromX, fromY, toX, toY));
        double angle = Math.atan2(toY - fromY, toX - fromX);
        float head = 5f * Theme.FONT_SCALE;
        var path = new Path2D.Float();
        path.moveTo(toX - head * Math.cos(angle - Math.PI / 5), toY - head * Math.sin(angle - Math.PI / 5));
        path.lineTo(toX, toY);
        path.lineTo(toX - head * Math.cos(angle + Math.PI / 5), toY - head * Math.sin(angle + Math.PI / 5));
        g.draw(path);
    }
}
