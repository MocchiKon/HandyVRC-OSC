package org.example.gui;

import javax.swing.*;
import java.awt.*;
import java.awt.geom.RoundRectangle2D;

/** Panel with a rounded, filled background and an optional hairline border (the "cards" of the GUI). */
public class RoundedPanel extends JPanel
{
    private final int radius;
    private Color borderColor;

    public RoundedPanel(LayoutManager layout, Color background, int radius)
    {
        super(layout);
        this.radius = radius;
        setBackground(background);
        setOpaque(false);
    }

    public void setBorderColor(Color borderColor)
    {
        this.borderColor = borderColor;
        repaint();
    }

    @Override
    protected void paintComponent(Graphics graphics)
    {
        Graphics2D g = Theme.smooth(graphics);
        var shape = new RoundRectangle2D.Float(0.5f, 0.5f, getWidth() - 1f, getHeight() - 1f, radius, radius);
        g.setColor(getBackground());
        g.fill(shape);
        if (borderColor != null)
        {
            g.setColor(borderColor);
            g.draw(shape);
        }
        g.dispose();
    }
}
