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
    /** Detached: shown alone in its own window, as a bar that fills the window with the number below it. */
    private boolean detached;

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

    public void setDetached(boolean detached)
    {
        this.detached = detached;
        revalidate();
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
        if (detached)
        {
            return new Dimension(140, 300);
        }
        // Caption, big number and hint stacked, as painted below
        int height = getFontMetrics(Theme.bold(11f)).getHeight() + getFontMetrics(Theme.bold(64f)).getAscent()
                + getFontMetrics(Theme.regular(12f)).getHeight() + 16;
        return new Dimension(260, Math.max(150, height));
    }

    @Override
    protected void paintComponent(Graphics graphics)
    {
        Graphics2D g = Theme.smooth(graphics);
        if (detached)
        {
            paintDetached(g);
            g.dispose();
            return;
        }
        int height = getHeight();
        float barX = 2;
        float barY = 4;
        float barHeight = height - 8;
        paintBar(g, barX, barY, BAR_WIDTH, barHeight);

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

    /** The number below a bar that takes the rest of the window; both scale with the window. */
    private void paintDetached(Graphics2D g)
    {
        int width = getWidth();
        int height = getHeight();
        float fontSize = Math.clamp(Math.min(width * 0.36f, height * 0.16f), 18f, 120f);
        g.setFont(Theme.bold(11f).deriveFont(fontSize));
        FontMetrics metrics = g.getFontMetrics();
        int numberHeight = metrics.getAscent() - metrics.getAscent() / 10;
        float barWidth = Math.clamp(width * 0.42f, 24f, 90f);
        float barX = (width - barWidth) / 2f;
        float barY = 6;
        float barHeight = Math.max(20, height - numberHeight - 26 - barY);
        paintBar(g, barX, barY, barWidth, barHeight);

        String value = String.valueOf(Math.round(shown));
        g.setColor(active ? Theme.TEXT : Theme.TEXT_SECONDARY);
        g.drawString(value, (width - metrics.stringWidth(value)) / 2, (int) (barY + barHeight + 14 + numberHeight));
    }

    /** The stroke as a capsule, filled from the bottom up to the current position. */
    private void paintBar(Graphics2D g, float x, float y, float width, float height)
    {
        float arc = Math.min(18, width * 0.4f);
        var bar = new RoundRectangle2D.Float(x, y, width, height, arc, arc);
        g.setColor(Theme.HEADER);
        g.fill(bar);
        float fillHeight = Math.max(0, height * shown / 100f);
        if (fillHeight > 0)
        {
            Shape oldClip = g.getClip();
            g.clip(bar);
            Color top = active ? Theme.ACCENT_LIGHT : Theme.TEXT_DISABLED;
            Color bottom = active ? Theme.ACCENT_DARK : Theme.SURFACE_RAISED;
            g.setPaint(new GradientPaint(0, y + height - fillHeight, top, 0, y + height, bottom));
            g.fill(new Rectangle.Float(x, y + height - fillHeight, width, fillHeight));
            // Marker at the current position
            g.setColor(active ? Theme.TEXT : Theme.TEXT_MUTED);
            g.fill(new RoundRectangle2D.Float(x + width * 0.18f, y + height - fillHeight - 1.5f + (fillHeight >= height - 2 ? 4 : 0),
                    width * 0.64f, 3, 3, 3));
            g.setClip(oldClip);
        }
        g.setColor(Theme.BORDER);
        g.draw(new RoundRectangle2D.Float(x + 0.5f, y + 0.5f, width - 1, height - 1, arc, arc));
    }
}
