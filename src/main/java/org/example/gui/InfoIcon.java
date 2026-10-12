package org.example.gui;

import javax.swing.*;
import java.awt.*;
import java.awt.event.MouseAdapter;
import java.awt.event.MouseEvent;
import java.awt.geom.Ellipse2D;

/** Small "i" in a circle next to a setting name, showing that hovering explains the setting. */
public class InfoIcon extends JComponent
{
    private static final int SIZE = Math.round(15 * Theme.FONT_SCALE);
    private boolean hover;

    public InfoIcon()
    {
        setOpaque(false);
        addMouseListener(new MouseAdapter()
        {
            @Override
            public void mouseEntered(MouseEvent e)
            {
                hover = true;
                repaint();
            }

            @Override
            public void mouseExited(MouseEvent e)
            {
                hover = false;
                repaint();
            }
        });
    }

    @Override
    public Dimension getPreferredSize()
    {
        return new Dimension(SIZE + 2, SIZE + 2);
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
        float x = (getWidth() - SIZE) / 2f;
        float y = (getHeight() - SIZE) / 2f;
        Color color = hover ? Theme.ACCENT_LIGHT : Theme.TEXT_MUTED;
        g.setColor(color);
        g.setStroke(new BasicStroke(1.3f));
        g.draw(new Ellipse2D.Float(x + 0.5f, y + 0.5f, SIZE - 1, SIZE - 1));
        float dot = SIZE * 0.15f;
        g.fill(new Ellipse2D.Float(x + (SIZE - dot) / 2f, y + SIZE * 0.22f, dot, dot));
        g.setStroke(new BasicStroke(1.8f, BasicStroke.CAP_ROUND, BasicStroke.JOIN_ROUND));
        g.drawLine(Math.round(x + SIZE / 2f), Math.round(y + SIZE * 0.48f), Math.round(x + SIZE / 2f), Math.round(y + SIZE * 0.72f));
        g.dispose();
    }
}
