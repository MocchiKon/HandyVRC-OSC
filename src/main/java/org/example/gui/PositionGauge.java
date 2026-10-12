package org.example.gui;

import javax.swing.*;
import java.awt.*;
import java.awt.geom.RoundRectangle2D;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Shows the penetration value (the position sent to the device, 100 = top of the stroke, 0 = bottom) as a big
 * number next to a vertical bar that pictures the stroke.
 * <p>
 * {@link #setValue} may be called from any thread and as often as values arrive; the gauge repaints at a fixed
 * frame rate and eases the bar towards the newest value, so a burst of values never floods the event queue.
 */
public class PositionGauge extends JComponent
{
    private static final int BAR_WIDTH = 46;

    private final AtomicInteger target = new AtomicInteger(100);
    private float shown = 100;
    private boolean active;

    public PositionGauge()
    {
        setOpaque(false);
        new Timer(16, e -> animate()).start();
    }

    /** @param value position 0-100, may be called from any thread */
    public void setValue(int value)
    {
        target.set(Math.clamp(value, 0, 100));
    }

    /** An inactive gauge (the app is stopped) is drawn dimmed. */
    public void setActive(boolean active)
    {
        this.active = active;
        repaint();
    }

    private void animate()
    {
        float goal = target.get();
        if (Math.abs(goal - shown) < 0.05f)
        {
            if (shown != goal)
            {
                shown = goal;
                repaint();
            }
            return;
        }
        shown += (goal - shown) * 0.35f;
        repaint();
    }

    @Override
    public Dimension getPreferredSize()
    {
        // Caption, big number and hint stacked, as painted below
        int height = getFontMetrics(Theme.bold(11f)).getHeight() + getFontMetrics(Theme.bold(64f)).getAscent()
                + getFontMetrics(Theme.regular(12f)).getHeight() + 16;
        return new Dimension(260, Math.max(150, height));
    }

    @Override
    protected void paintComponent(Graphics graphics)
    {
        Graphics2D g = Theme.smooth(graphics);
        int height = getHeight();
        float barX = 2;
        float barY = 4;
        float barHeight = height - 8;
        var bar = new RoundRectangle2D.Float(barX, barY, BAR_WIDTH, barHeight, 18, 18);

        g.setColor(Theme.HEADER);
        g.fill(bar);
        float fillHeight = Math.max(0, barHeight * shown / 100f);
        if (fillHeight > 0)
        {
            Shape oldClip = g.getClip();
            g.clip(bar);
            Color top = active ? Theme.ACCENT_LIGHT : Theme.TEXT_DISABLED;
            Color bottom = active ? Theme.ACCENT_DARK : Theme.SURFACE_RAISED;
            g.setPaint(new GradientPaint(0, barY + barHeight - fillHeight, top, 0, barY + barHeight, bottom));
            g.fill(new Rectangle.Float(barX, barY + barHeight - fillHeight, BAR_WIDTH, fillHeight));
            // Marker at the current position
            g.setColor(active ? Theme.TEXT : Theme.TEXT_MUTED);
            g.fill(new RoundRectangle2D.Float(barX + 8, barY + barHeight - fillHeight - 1.5f + (fillHeight >= barHeight - 2 ? 4 : 0),
                    BAR_WIDTH - 16, 3, 3, 3));
            g.setClip(oldClip);
        }
        g.setColor(Theme.BORDER);
        g.draw(new RoundRectangle2D.Float(barX + 0.5f, barY + 0.5f, BAR_WIDTH - 1, barHeight - 1, 18, 18));

        int textX = (int) (barX + BAR_WIDTH + 22);
        g.setFont(Theme.bold(11f));
        g.setColor(Theme.TEXT_MUTED);
        int captionBaseline = 6 + g.getFontMetrics().getAscent();
        g.drawString("PENETRATION VALUE", textX, captionBaseline);

        g.setFont(Theme.bold(64f));
        g.setColor(active ? Theme.TEXT : Theme.TEXT_SECONDARY);
        FontMetrics big = g.getFontMetrics();
        String value = String.valueOf(Math.round(shown));
        // The ascent includes room for accents that digits do not have, hence the small overlap
        int valueBaseline = captionBaseline + big.getAscent() - big.getAscent() / 10;
        g.drawString(value, textX - 3, valueBaseline);

        g.setFont(Theme.regular(12f));
        g.setColor(Theme.TEXT_MUTED);
        FontMetrics small = g.getFontMetrics();
        g.drawString("100 = top of the stroke  ·  0 = bottom", textX,
                Math.min(height - small.getDescent() - 2, valueBaseline + small.getHeight() + 6));
        g.dispose();
    }
}
