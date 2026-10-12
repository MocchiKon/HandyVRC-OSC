package org.example.gui;

import org.example.processor.SpsType;

import javax.swing.*;
import java.awt.*;
import java.awt.event.*;
import java.awt.geom.Ellipse2D;
import java.awt.geom.Rectangle2D;
import java.awt.geom.RoundRectangle2D;

/**
 * A penetrator and an orifice side by side, one of which the user drags (with the mouse, the arrow keys or the
 * mouse wheel): the penetrator in PENETRATOR mode, the orifice in ORIFICE mode. How deep the penetrator is inside
 * the orifice is the value the test mode sends to the processor.
 */
class TestScene extends JComponent
{
    /** How far (in penetrator lengths) the moved part can be pulled away from the other one. */
    private static final float MIN_DEPTH = -0.35f;
    private static final float KEY_STEP = 0.02f;

    private SpsType moved = SpsType.PENETRATOR;
    /** Depth in penetrator lengths: below 0 the penetrator is outside, 1 is fully inside. */
    private float depth = -0.2f;
    private boolean hover;
    private boolean dragging;
    private int lastDragX;
    private Runnable userMoveListener = () -> {};

    TestScene()
    {
        setOpaque(false);
        setFocusable(true);
        setCursor(Cursor.getPredefinedCursor(Cursor.HAND_CURSOR));
        var mouse = new MouseAdapter()
        {
            @Override
            public void mousePressed(MouseEvent e)
            {
                requestFocusInWindow();
                dragging = true;
                lastDragX = e.getX();
                setCursor(Cursor.getPredefinedCursor(Cursor.MOVE_CURSOR));
                repaint();
            }

            @Override
            public void mouseDragged(MouseEvent e)
            {
                if (!dragging)
                {
                    return;
                }
                float change = (e.getX() - lastDragX) / penetratorLength();
                lastDragX = e.getX();
                moveBy(moved == SpsType.PENETRATOR ? change : -change);
            }

            @Override
            public void mouseReleased(MouseEvent e)
            {
                dragging = false;
                setCursor(Cursor.getPredefinedCursor(Cursor.HAND_CURSOR));
                repaint();
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
        };
        addMouseListener(mouse);
        addMouseMotionListener(mouse);
        addMouseWheelListener(e -> moveBy((float) -e.getPreciseWheelRotation() * KEY_STEP * 1.5f));
        addKeyListener(new KeyAdapter()
        {
            @Override
            public void keyPressed(KeyEvent e)
            {
                // The arrow keys move the part the user controls in the direction of the arrow
                int direction = switch (e.getKeyCode())
                {
                    case KeyEvent.VK_RIGHT -> 1;
                    case KeyEvent.VK_LEFT -> -1;
                    default -> 0;
                };
                if (direction != 0)
                {
                    moveBy((moved == SpsType.PENETRATOR ? direction : -direction) * KEY_STEP);
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

    /** Called when the user moved the part (not when {@link #setDepth} did). */
    void setUserMoveListener(Runnable userMoveListener)
    {
        this.userMoveListener = userMoveListener;
    }

    void setMoved(SpsType moved)
    {
        this.moved = moved;
        repaint();
    }

    void setDepth(float depth)
    {
        this.depth = Math.clamp(depth, MIN_DEPTH, 1f);
        repaint();
    }

    /** @return how deep the penetrator is inside, 0 (outside) to 1 (fully inside) */
    float getInsertion()
    {
        return Math.clamp(depth, 0f, 1f);
    }

    private void moveBy(float change)
    {
        setDepth(depth + change);
        userMoveListener.run();
    }

    private float penetratorLength()
    {
        return getWidth() * 0.36f;
    }

    @Override
    public Dimension getPreferredSize()
    {
        return new Dimension(460, Math.round(150 * Theme.FONT_SCALE));
    }

    @Override
    protected void paintComponent(Graphics graphics)
    {
        Graphics2D g = Theme.smooth(graphics);
        int width = getWidth();
        float length = penetratorLength();
        g.setFont(Theme.regular(11.5f));
        FontMetrics metrics = g.getFontMetrics();
        float labelSpace = metrics.getHeight() + 10;
        float centerY = (getHeight() - labelSpace) / 2f + 4;
        float thickness = Math.clamp((getHeight() - labelSpace) * 0.2f, 12f, 34f);
        float tunnelHeight = thickness * 1.9f;
        float tunnelLength = length * 1.05f;

        float entryX;
        float tipX;
        if (moved == SpsType.PENETRATOR)
        {
            entryX = width * 0.5f;
            tipX = entryX + depth * length;
        }
        else
        {
            tipX = width * 0.06f + length;
            entryX = tipX - depth * length;
        }
        float baseX = tipX - length;
        boolean penetratorMoved = moved == SpsType.PENETRATOR;
        boolean highlight = hover || dragging || isFocusOwner();

        // Orifice body with its channel
        var body = new RoundRectangle2D.Float(entryX, centerY - tunnelHeight / 2, tunnelLength, tunnelHeight,
                tunnelHeight * 0.6f, tunnelHeight * 0.6f);
        g.setPaint(new GradientPaint(0, centerY - tunnelHeight / 2, Theme.SURFACE_HOVER, 0, centerY + tunnelHeight / 2, Theme.SURFACE_RAISED));
        g.fill(body);
        g.setColor(!penetratorMoved && highlight ? Theme.ACCENT_LIGHT : Theme.BORDER);
        g.setStroke(new BasicStroke(!penetratorMoved && highlight ? 2f : 1.2f));
        g.draw(body);
        g.setColor(Theme.HEADER);
        g.fill(new RoundRectangle2D.Float(entryX + 2, centerY - thickness * 0.62f, tunnelLength - thickness * 0.8f,
                thickness * 1.24f, thickness, thickness));

        // Penetrator: the part inside the orifice is drawn see-through
        Shape oldClip = g.getClip();
        g.clip(new Rectangle2D.Float(0, 0, entryX, getHeight()));
        paintPenetrator(g, baseX, tipX, centerY, thickness, 255, penetratorMoved && highlight);
        g.setClip(oldClip);
        g.clip(new Rectangle2D.Float(entryX, 0, width, getHeight()));
        paintPenetrator(g, baseX, tipX, centerY, thickness, 110, false);
        g.setClip(oldClip);

        // Entrance of the orifice, in front of the penetrator
        g.setColor(Theme.ACCENT_LIGHT);
        g.setStroke(new BasicStroke(2.5f));
        g.draw(new Ellipse2D.Float(entryX - thickness * 0.35f, centerY - tunnelHeight / 2 + 1, thickness * 0.7f, tunnelHeight - 2));

        // Labels
        float labelY = getHeight() - 6;
        // Under the base of the penetrator and under the far end of the orifice, so they never overlap
        paintLabel(g, "Penetrator" + (penetratorMoved ? " (drag me)" : ""), baseX - thickness * 0.85f, labelY, penetratorMoved, false);
        paintLabel(g, "Orifice" + (penetratorMoved ? "" : " (drag me)"), entryX + tunnelLength, labelY, !penetratorMoved, true);
        g.dispose();
    }

    private void paintPenetrator(Graphics2D g, float baseX, float tipX, float centerY, float thickness, int alpha,
                                 boolean highlight)
    {
        float baseSize = thickness * 1.7f;
        var shaft = new RoundRectangle2D.Float(baseX, centerY - thickness / 2, tipX - baseX, thickness, thickness, thickness);
        var base = new Ellipse2D.Float(baseX - baseSize / 2, centerY - baseSize / 2, baseSize, baseSize);
        if (highlight)
        {
            g.setColor(Theme.ACCENT_GLOW);
            g.setStroke(new BasicStroke(8f, BasicStroke.CAP_ROUND, BasicStroke.JOIN_ROUND));
            g.draw(shaft);
            g.draw(base);
        }
        g.setPaint(new GradientPaint(0, centerY - baseSize / 2, Theme.withAlpha(Theme.ACCENT_LIGHT, alpha),
                0, centerY + baseSize / 2, Theme.withAlpha(Theme.ACCENT_DARK, alpha)));
        g.fill(base);
        g.setPaint(new GradientPaint(0, centerY - thickness / 2, Theme.withAlpha(Theme.ACCENT_LIGHT, alpha),
                0, centerY + thickness / 2, Theme.withAlpha(Theme.ACCENT_DARK, alpha)));
        g.fill(shaft);
    }

    /** @param anchorX left end of the text, or its right end when {@code alignRight} */
    private void paintLabel(Graphics2D g, String text, float anchorX, float baseline, boolean moved, boolean alignRight)
    {
        FontMetrics metrics = g.getFontMetrics();
        float textWidth = metrics.stringWidth(text);
        float x = Math.clamp(alignRight ? anchorX - textWidth : anchorX, 2, getWidth() - textWidth - 2);
        g.setColor(moved ? Theme.ACCENT_LIGHT : Theme.TEXT_MUTED);
        g.drawString(text, x, baseline);
    }
}
