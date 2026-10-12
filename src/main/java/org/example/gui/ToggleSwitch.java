package org.example.gui;

import javax.swing.*;
import javax.swing.event.ChangeEvent;
import javax.swing.event.ChangeListener;
import java.awt.*;
import java.awt.event.*;
import java.awt.geom.Ellipse2D;
import java.awt.geom.RoundRectangle2D;

/** On/off switch with a sliding knob, used for the boolean settings. */
public class ToggleSwitch extends JComponent
{
    private static final int WIDTH = 42;
    private static final int HEIGHT = 24;

    private boolean selected;
    private boolean hover;
    /** Knob position, 0 (off) to 1 (on); animated towards {@link #selected}. */
    private float knob;
    private final Timer animation;

    public ToggleSwitch(boolean selected)
    {
        this.selected = selected;
        this.knob = selected ? 1 : 0;
        setOpaque(false);
        setFocusable(true);
        setCursor(Cursor.getPredefinedCursor(Cursor.HAND_CURSOR));
        animation = new Timer(15, e -> animate());
        addMouseListener(new MouseAdapter()
        {
            @Override
            public void mouseClicked(MouseEvent e)
            {
                if (isEnabled() && SwingUtilities.isLeftMouseButton(e))
                {
                    requestFocusInWindow();
                    setSelected(!ToggleSwitch.this.selected);
                }
            }

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
        addKeyListener(new KeyAdapter()
        {
            @Override
            public void keyPressed(KeyEvent e)
            {
                if (isEnabled() && (e.getKeyCode() == KeyEvent.VK_SPACE || e.getKeyCode() == KeyEvent.VK_ENTER))
                {
                    setSelected(!ToggleSwitch.this.selected);
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
            }
        });
    }

    public boolean isSelected()
    {
        return selected;
    }

    public void setSelected(boolean selected)
    {
        if (this.selected == selected)
        {
            return;
        }
        this.selected = selected;
        if (isShowing())
        {
            animation.start();
        }
        else
        {
            knob = selected ? 1 : 0;
        }
        repaint();
        var event = new ChangeEvent(this);
        for (ChangeListener listener : listenerList.getListeners(ChangeListener.class))
        {
            listener.stateChanged(event);
        }
    }

    public void addChangeListener(ChangeListener listener)
    {
        listenerList.add(ChangeListener.class, listener);
    }

    private void animate()
    {
        float target = selected ? 1 : 0;
        knob += (target - knob) * 0.35f;
        if (Math.abs(target - knob) < 0.02f)
        {
            knob = target;
            animation.stop();
        }
        repaint();
    }

    @Override
    public Dimension getPreferredSize()
    {
        return new Dimension(WIDTH + 4, HEIGHT + 4);
    }

    @Override
    public Dimension getMinimumSize()
    {
        return getPreferredSize();
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
        float x = (getWidth() - WIDTH) / 2f;
        float y = (getHeight() - HEIGHT) / 2f;
        boolean enabled = isEnabled();

        Color off = hover && enabled ? Theme.SURFACE_HOVER : Theme.TRACK;
        Color on = hover && enabled ? Theme.ACCENT_LIGHT : Theme.ACCENT;
        g.setColor(enabled ? Theme.mix(off, on, knob) : Theme.SURFACE_RAISED);
        g.fill(new RoundRectangle2D.Float(x, y, WIDTH, HEIGHT, HEIGHT, HEIGHT));
        if (isFocusOwner())
        {
            g.setColor(Theme.withAlpha(Theme.ACCENT_LIGHT, 170));
            g.setStroke(new BasicStroke(1.5f));
            g.draw(new RoundRectangle2D.Float(x - 2, y - 2, WIDTH + 4, HEIGHT + 4, HEIGHT + 4, HEIGHT + 4));
        }

        float knobSize = HEIGHT - 6;
        float knobX = x + 3 + (WIDTH - knobSize - 6) * knob;
        g.setColor(enabled ? Theme.TEXT : Theme.TEXT_DISABLED);
        g.fill(new Ellipse2D.Float(knobX, y + 3, knobSize, knobSize));
        g.dispose();
    }
}
