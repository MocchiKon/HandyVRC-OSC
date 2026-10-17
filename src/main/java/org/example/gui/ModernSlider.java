package org.example.gui;

import javax.swing.*;
import javax.swing.event.ChangeEvent;
import javax.swing.event.ChangeListener;
import java.awt.*;
import java.awt.event.*;
import java.awt.geom.Ellipse2D;
import java.awt.geom.RoundRectangle2D;
import java.util.ArrayList;
import java.util.List;

/**
 * Slider with a slim rounded track and round thumbs. In range mode it has two thumbs (a lower and an upper value)
 * that keep at least {@code minGap} apart. Values are integers; a fractional setting is shown scaled.
 * <p>
 * Keyboard: the arrow keys move by one step, Page Up/Down by ten steps, Home/End to the ends. In range mode Tab
 * inside the slider is not used, the thumb that was used last (or is nearest to the click) is the one that moves.
 */
public class ModernSlider extends JComponent
{
    private static final int THUMB_SIZE = 18;
    private static final int TRACK_HEIGHT = 6;
    private static final int PADDING = THUMB_SIZE / 2 + 4;

    private final int min;
    private final int max;
    private final int step;
    private final boolean range;
    private final int minGap;

    private int low;
    private int high;
    private boolean lowActive;
    private boolean dragging;
    private final List<Runnable> commitListeners = new ArrayList<>();
    private int hoverThumb = -1; // -1 none, 0 low, 1 high

    /** Single value slider. */
    public ModernSlider(int min, int max, int step, int value)
    {
        this(min, max, step, min, value, false, 0);
    }

    /** Range slider with two thumbs. */
    public ModernSlider(int min, int max, int step, int low, int high, int minGap)
    {
        this(min, max, step, low, high, true, minGap);
    }

    private ModernSlider(int min, int max, int step, int low, int high, boolean range, int minGap)
    {
        this.min = min;
        this.max = max;
        this.step = Math.max(1, step);
        this.range = range;
        this.minGap = minGap;
        this.low = low;
        this.high = high;
        setOpaque(false);
        setFocusable(true);
        setCursor(Cursor.getPredefinedCursor(Cursor.HAND_CURSOR));
        installListeners();
    }

    public int getValue()
    {
        return high;
    }

    public int getMinimumValue()
    {
        return min;
    }

    public int getMaximumValue()
    {
        return max;
    }

    public int getLowValue()
    {
        return low;
    }

    public int getHighValue()
    {
        return high;
    }

    public void setValue(int value)
    {
        setValues(low, value);
    }

    public void setValues(int newLow, int newHigh)
    {
        int clampedHigh = snap(Math.clamp(newHigh, min, max));
        int clampedLow = range ? snap(Math.clamp(newLow, min, max)) : min;
        if (range && clampedHigh - clampedLow < minGap)
        {
            if (lowActive)
            {
                clampedLow = Math.max(min, clampedHigh - minGap);
                clampedHigh = Math.max(clampedHigh, clampedLow + minGap);
            }
            else
            {
                clampedHigh = Math.min(max, clampedLow + minGap);
                clampedLow = Math.min(clampedLow, clampedHigh - minGap);
            }
        }
        if (clampedLow == low && clampedHigh == high)
        {
            return;
        }
        low = clampedLow;
        high = clampedHigh;
        repaint();
        fireStateChanged();
    }

    public void addChangeListener(ChangeListener listener)
    {
        listenerList.add(ChangeListener.class, listener);
    }

    /**
     * Adds a listener that is called when the user finishes changing the value: on mouse release after a drag, or
     * after a key press. Programmatic {@link #setValue(int)} does not call it.
     */
    public void addCommitListener(Runnable listener)
    {
        commitListeners.add(listener);
    }

    /** @return true while the user holds the mouse button on this slider */
    public boolean isDragging()
    {
        return dragging;
    }

    private void fireCommit()
    {
        for (Runnable listener : List.copyOf(commitListeners))
        {
            listener.run();
        }
    }

    private void fireStateChanged()
    {
        var event = new ChangeEvent(this);
        for (ChangeListener listener : listenerList.getListeners(ChangeListener.class))
        {
            listener.stateChanged(event);
        }
    }

    private int snap(int value)
    {
        return Math.clamp(min + Math.round((value - min) / (float) step) * step, min, max);
    }

    @Override
    public Dimension getPreferredSize()
    {
        return new Dimension(220, THUMB_SIZE + 12);
    }

    @Override
    public Dimension getMinimumSize()
    {
        return new Dimension(80, THUMB_SIZE + 12);
    }

    private float xFor(int value)
    {
        float usable = getWidth() - 2f * PADDING;
        return PADDING + usable * (value - min) / (float) (max - min);
    }

    private int valueAt(int x)
    {
        float usable = getWidth() - 2f * PADDING;
        float ratio = Math.clamp((x - PADDING) / usable, 0f, 1f);
        return snap(Math.round(min + ratio * (max - min)));
    }

    private void installListeners()
    {
        var mouse = new MouseAdapter()
        {
            @Override
            public void mousePressed(MouseEvent e)
            {
                if (!isEnabled() || !SwingUtilities.isLeftMouseButton(e))
                {
                    return;
                }
                requestFocusInWindow();
                if (range)
                {
                    float lowDistance = Math.abs(e.getX() - xFor(low));
                    float highDistance = Math.abs(e.getX() - xFor(high));
                    // Both thumbs can sit on the same spot: then the direction of the click picks one
                    lowActive = lowDistance == highDistance ? e.getX() < xFor(low) : lowDistance < highDistance;
                }
                dragging = true;
                moveActiveThumbTo(valueAt(e.getX()));
            }

            @Override
            public void mouseDragged(MouseEvent e)
            {
                if (dragging)
                {
                    moveActiveThumbTo(valueAt(e.getX()));
                }
            }

            @Override
            public void mouseReleased(MouseEvent e)
            {
                boolean wasDragging = dragging;
                dragging = false;
                repaint();
                if (wasDragging)
                {
                    fireCommit();
                }
            }

            @Override
            public void mouseMoved(MouseEvent e)
            {
                int thumb = thumbAt(e.getX(), e.getY());
                if (thumb != hoverThumb)
                {
                    hoverThumb = thumb;
                    repaint();
                }
            }

            @Override
            public void mouseExited(MouseEvent e)
            {
                hoverThumb = -1;
                repaint();
            }
        };
        addMouseListener(mouse);
        addMouseMotionListener(mouse);
        addKeyListener(new KeyAdapter()
        {
            @Override
            public void keyPressed(KeyEvent e)
            {
                if (!isEnabled())
                {
                    return;
                }
                int current = range && lowActive ? low : high;
                Integer target = switch (e.getKeyCode())
                {
                    case KeyEvent.VK_LEFT, KeyEvent.VK_DOWN -> current - step;
                    case KeyEvent.VK_RIGHT, KeyEvent.VK_UP -> current + step;
                    case KeyEvent.VK_PAGE_DOWN -> current - 10 * step;
                    case KeyEvent.VK_PAGE_UP -> current + 10 * step;
                    case KeyEvent.VK_HOME -> min;
                    case KeyEvent.VK_END -> max;
                    default -> null;
                };
                if (target != null)
                {
                    moveActiveThumbTo(target);
                    fireCommit();
                    e.consume();
                }
            }
        });
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
                if (dragging)
                {
                    dragging = false;
                    fireCommit();
                }
            }
        });
    }

    private int thumbAt(int x, int y)
    {
        int centerY = getHeight() / 2;
        if (Math.abs(y - centerY) > THUMB_SIZE)
        {
            return -1;
        }
        if (Math.abs(x - xFor(high)) <= THUMB_SIZE / 2f + 2)
        {
            return 1;
        }
        if (range && Math.abs(x - xFor(low)) <= THUMB_SIZE / 2f + 2)
        {
            return 0;
        }
        return -1;
    }

    private void moveActiveThumbTo(int value)
    {
        if (range && lowActive)
        {
            setValues(Math.min(value, high - minGap), high);
        }
        else if (range)
        {
            setValues(low, Math.max(value, low + minGap));
        }
        else
        {
            setValues(min, value);
        }
    }

    @Override
    protected void paintComponent(Graphics graphics)
    {
        Graphics2D g = Theme.smooth(graphics);
        boolean enabled = isEnabled();
        float centerY = getHeight() / 2f;
        float left = PADDING;
        float right = getWidth() - PADDING;

        g.setColor(enabled ? Theme.TRACK : Theme.SURFACE_RAISED);
        g.fill(new RoundRectangle2D.Float(left, centerY - TRACK_HEIGHT / 2f, right - left, TRACK_HEIGHT, TRACK_HEIGHT, TRACK_HEIGHT));

        float fillStart = range ? xFor(low) : left;
        float fillEnd = xFor(high);
        if (enabled)
        {
            g.setPaint(new GradientPaint(fillStart, 0, Theme.ACCENT_DARK, Math.max(fillEnd, fillStart + 1), 0, Theme.ACCENT_LIGHT));
        }
        else
        {
            g.setColor(Theme.TEXT_DISABLED);
        }
        g.fill(new RoundRectangle2D.Float(fillStart, centerY - TRACK_HEIGHT / 2f, Math.max(fillEnd - fillStart, TRACK_HEIGHT),
                TRACK_HEIGHT, TRACK_HEIGHT, TRACK_HEIGHT));

        if (range)
        {
            paintThumb(g, xFor(low), centerY, enabled, hoverThumb == 0 || (dragging && lowActive), isFocusOwner() && lowActive);
        }
        paintThumb(g, xFor(high), centerY, enabled, hoverThumb == 1 || (dragging && !(range && lowActive)),
                isFocusOwner() && !(range && lowActive));
        g.dispose();
    }

    private void paintThumb(Graphics2D g, float x, float y, boolean enabled, boolean highlighted, boolean focused)
    {
        if (enabled && (highlighted || focused))
        {
            float halo = THUMB_SIZE + 10;
            g.setColor(Theme.ACCENT_GLOW);
            g.fill(new Ellipse2D.Float(x - halo / 2, y - halo / 2, halo, halo));
        }
        g.setColor(enabled ? Theme.TEXT : Theme.TEXT_DISABLED);
        g.fill(new Ellipse2D.Float(x - THUMB_SIZE / 2f, y - THUMB_SIZE / 2f, THUMB_SIZE, THUMB_SIZE));
        g.setColor(enabled ? Theme.ACCENT : Theme.SURFACE_RAISED);
        g.setStroke(new BasicStroke(3.5f));
        float inner = THUMB_SIZE - 7;
        g.draw(new Ellipse2D.Float(x - inner / 2, y - inner / 2, inner, inner));
    }
}
