package org.example.gui;

import javax.swing.*;
import javax.swing.plaf.basic.BasicScrollBarUI;
import java.awt.*;
import java.awt.geom.RoundRectangle2D;

/** Slim scroll bar without arrow buttons, matching the dark theme. */
public class ThinScrollBarUI extends BasicScrollBarUI
{
    @Override
    protected void configureScrollBarColors()
    {
        trackColor = Theme.BACKGROUND;
        thumbColor = Theme.BORDER;
    }

    @Override
    protected JButton createDecreaseButton(int orientation)
    {
        return invisibleButton();
    }

    @Override
    protected JButton createIncreaseButton(int orientation)
    {
        return invisibleButton();
    }

    private static JButton invisibleButton()
    {
        var button = new JButton();
        button.setPreferredSize(new Dimension(0, 0));
        button.setMinimumSize(new Dimension(0, 0));
        button.setMaximumSize(new Dimension(0, 0));
        return button;
    }

    @Override
    public Dimension getPreferredSize(JComponent component)
    {
        return new Dimension(10, 10);
    }

    @Override
    protected void paintTrack(Graphics g, JComponent component, Rectangle bounds)
    {
        g.setColor(Theme.BACKGROUND);
        g.fillRect(bounds.x, bounds.y, bounds.width, bounds.height);
    }

    @Override
    protected void paintThumb(Graphics graphics, JComponent component, Rectangle bounds)
    {
        if (bounds.isEmpty() || !scrollbar.isEnabled())
        {
            return;
        }
        Graphics2D g = Theme.smooth(graphics);
        g.setColor(isDragging || isThumbRollover() ? Theme.ACCENT_DARK : Theme.BORDER);
        g.fill(new RoundRectangle2D.Float(bounds.x + 3, bounds.y + 2, bounds.width - 5, bounds.height - 4, 6, 6));
        g.dispose();
    }
}
