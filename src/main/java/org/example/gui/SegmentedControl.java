package org.example.gui;

import javax.swing.*;
import java.awt.*;
import java.awt.event.*;
import java.awt.geom.RoundRectangle2D;
import java.util.List;
import java.util.function.Consumer;

/**
 * A row of mutually exclusive options (like radio buttons) shown as one rounded bar, the selected option filled
 * with the accent color. Every option can have its own tooltip.
 */
public class SegmentedControl<T> extends JComponent
{
    /**
     * @param tooltip explanation of the option, shown in the info tooltip of the setting (null for none)
     */
    public record Option<T>(T value, String label, String tooltip) {}

    private final List<Option<T>> options;
    private final boolean compact;
    private int selectedIndex;
    private int hoverIndex = -1;
    private Consumer<T> selectionListener = value -> {};

    public SegmentedControl(List<Option<T>> options, T selected, boolean compact)
    {
        this.options = List.copyOf(options);
        this.compact = compact;
        this.selectedIndex = Math.max(0, indexOf(selected));
        setOpaque(false);
        setFocusable(true);
        setFont(Theme.bold(compact ? 12.5f : 13f));
        setCursor(Cursor.getPredefinedCursor(Cursor.HAND_CURSOR));
        installListeners();
    }

    public void setSelectionListener(Consumer<T> selectionListener)
    {
        this.selectionListener = selectionListener;
    }

    public T getSelected()
    {
        return options.get(selectedIndex).value();
    }

    /** Selects an option without notifying the selection listener. */
    public void setSelected(T value)
    {
        int index = indexOf(value);
        if (index >= 0)
        {
            selectedIndex = index;
            repaint();
        }
    }

    private void select(int index)
    {
        if (index < 0 || index >= options.size() || index == selectedIndex)
        {
            return;
        }
        selectedIndex = index;
        repaint();
        selectionListener.accept(getSelected());
    }

    private int indexOf(T value)
    {
        for (int i = 0; i < options.size(); i++)
        {
            if (options.get(i).value().equals(value))
            {
                return i;
            }
        }
        return -1;
    }

    private int indexAt(int x)
    {
        if (x < 0 || x >= getWidth())
        {
            return -1;
        }
        return Math.min(options.size() - 1, x * options.size() / Math.max(1, getWidth()));
    }

    /** @return every option with its explanation ("Label: explanation"), one per line */
    public String describeOptions()
    {
        return options.stream()
                .filter(option -> option.tooltip() != null)
                .map(option -> option.label() + ": " + option.tooltip())
                .collect(java.util.stream.Collectors.joining("\n\n"));
    }

    private void installListeners()
    {
        var mouse = new MouseAdapter()
        {
            @Override
            public void mousePressed(MouseEvent e)
            {
                if (isEnabled() && SwingUtilities.isLeftMouseButton(e))
                {
                    requestFocusInWindow();
                    select(indexAt(e.getX()));
                }
            }

            @Override
            public void mouseMoved(MouseEvent e)
            {
                int index = indexAt(e.getX());
                if (index != hoverIndex)
                {
                    hoverIndex = index;
                    repaint();
                }
            }

            @Override
            public void mouseExited(MouseEvent e)
            {
                hoverIndex = -1;
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
                if (e.getKeyCode() == KeyEvent.VK_LEFT)
                {
                    select(selectedIndex - 1);
                    e.consume();
                }
                else if (e.getKeyCode() == KeyEvent.VK_RIGHT)
                {
                    select(selectedIndex + 1);
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

    @Override
    public Dimension getPreferredSize()
    {
        FontMetrics metrics = getFontMetrics(getFont());
        int widest = options.stream().mapToInt(option -> metrics.stringWidth(option.label())).max().orElse(40);
        return new Dimension((widest + 36) * options.size() + 8, metrics.getHeight() + (compact ? 16 : 22));
    }

    @Override
    public Dimension getMaximumSize()
    {
        return new Dimension(Integer.MAX_VALUE, getPreferredSize().height);
    }

    @Override
    protected void paintComponent(Graphics graphics)
    {
        Graphics2D g = Theme.smooth(graphics);
        int width = getWidth();
        int height = getHeight();
        boolean enabled = isEnabled();
        int radius = height - 4;

        g.setColor(Theme.HEADER);
        g.fill(new RoundRectangle2D.Float(0, 0, width, height, radius + 4, radius + 4));
        g.setColor(isFocusOwner() ? Theme.withAlpha(Theme.ACCENT_LIGHT, 150) : Theme.BORDER);
        g.draw(new RoundRectangle2D.Float(0.5f, 0.5f, width - 1, height - 1, radius + 4, radius + 4));

        float segment = (width - 8) / (float) options.size();
        g.setFont(getFont());
        FontMetrics metrics = g.getFontMetrics();
        for (int i = 0; i < options.size(); i++)
        {
            float x = 4 + i * segment;
            var shape = new RoundRectangle2D.Float(x, 4, segment, height - 8, radius - 4, radius - 4);
            boolean selected = i == selectedIndex;
            if (selected)
            {
                g.setPaint(enabled
                        ? new GradientPaint(0, 4, Theme.ACCENT, 0, height - 4, Theme.ACCENT_DARK)
                        : new GradientPaint(0, 4, Theme.SURFACE_RAISED, 0, height - 4, Theme.SURFACE_RAISED));
                g.fill(shape);
            }
            else if (enabled && i == hoverIndex)
            {
                g.setColor(Theme.SURFACE_HOVER);
                g.fill(shape);
            }
            String label = options.get(i).label();
            g.setColor(!enabled ? Theme.TEXT_DISABLED : selected ? Theme.TEXT : Theme.TEXT_SECONDARY);
            int textX = Math.round(x + (segment - metrics.stringWidth(label)) / 2f);
            int textY = (height - metrics.getHeight()) / 2 + metrics.getAscent();
            g.drawString(label, textX, textY);
        }
        g.dispose();
    }
}
