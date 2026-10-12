package org.example.gui;

import org.example.AppController;

import javax.swing.*;
import java.awt.*;
import java.awt.geom.Ellipse2D;
import java.awt.geom.RoundRectangle2D;

/** Rounded badge with a colored dot that shows whether the app is stopped, connecting, running or failed. */
public class StatusPill extends JComponent
{
    private AppController.State state = AppController.State.STOPPED;
    private boolean testMode;
    private float pulse;
    private final Timer pulseTimer;

    public StatusPill()
    {
        setOpaque(false);
        setFont(Theme.bold(12f));
        pulseTimer = new Timer(40, e ->
        {
            pulse = (pulse + 0.05f) % 1f;
            repaint();
        });
    }

    public void setState(AppController.State state, boolean testMode)
    {
        this.state = state;
        this.testMode = testMode;
        if (state == AppController.State.CONNECTING || state == AppController.State.RUNNING
                || state == AppController.State.STOPPING)
        {
            pulseTimer.start();
        }
        else
        {
            pulseTimer.stop();
        }
        revalidate();
        repaint();
    }

    private String label()
    {
        return switch (state)
        {
            case STOPPED -> "Stopped";
            case CONNECTING -> "Connecting";
            case RUNNING -> testMode ? "Test mode" : "Running";
            case STOPPING -> "Stopping";
            case ERROR -> "Error";
        };
    }

    private Color dotColor()
    {
        return switch (state)
        {
            case STOPPED -> Theme.TEXT_MUTED;
            case CONNECTING, STOPPING -> Theme.ACCENT_LIGHT;
            case RUNNING -> Theme.ACCENT;
            case ERROR -> Theme.ERROR;
        };
    }

    @Override
    public Dimension getPreferredSize()
    {
        FontMetrics metrics = getFontMetrics(getFont());
        return new Dimension(metrics.stringWidth(label()) + 40, metrics.getHeight() + 10);
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
        int height = getHeight();
        Color background = state == AppController.State.ERROR ? Theme.ERROR_BACKGROUND
                : state == AppController.State.RUNNING ? Theme.withAlpha(Theme.ACCENT, 45) : Theme.SURFACE;
        g.setColor(background);
        g.fill(new RoundRectangle2D.Float(0, 0, getWidth(), height, height, height));
        g.setColor(state == AppController.State.RUNNING ? Theme.withAlpha(Theme.ACCENT, 120) : Theme.BORDER);
        g.draw(new RoundRectangle2D.Float(0.5f, 0.5f, getWidth() - 1, height - 1, height, height));

        float dot = 8;
        float dotX = 13;
        float dotY = height / 2f - dot / 2;
        if (state == AppController.State.CONNECTING || state == AppController.State.RUNNING
                || state == AppController.State.STOPPING)
        {
            float ring = dot + 10 * pulse;
            g.setColor(Theme.withAlpha(dotColor(), (int) (140 * (1 - pulse))));
            g.fill(new Ellipse2D.Float(dotX + dot / 2 - ring / 2, height / 2f - ring / 2, ring, ring));
        }
        g.setColor(dotColor());
        g.fill(new Ellipse2D.Float(dotX, dotY, dot, dot));

        g.setFont(getFont());
        FontMetrics metrics = g.getFontMetrics();
        g.setColor(state == AppController.State.ERROR ? Theme.ERROR : Theme.TEXT);
        g.drawString(label(), dotX + dot + 9, (height - metrics.getHeight()) / 2 + metrics.getAscent());
        g.dispose();
    }
}
