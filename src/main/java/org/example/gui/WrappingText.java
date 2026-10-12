package org.example.gui;

import javax.swing.*;
import javax.swing.text.View;
import java.awt.*;
import java.awt.event.ComponentAdapter;
import java.awt.event.ComponentEvent;

/**
 * Read-only text that wraps at the width it is given, for messages that can be longer than one line (a JLabel would
 * cut them off). It never asks for more width than {@link #MIN_WIDTH}, so it cannot widen the layout it is in, and
 * its height follows the width it actually gets.
 */
public class WrappingText extends JTextArea
{
    private static final int MIN_WIDTH = 120;
    private int laidOutWidth = -1;

    public WrappingText(String text, Font font, Color color)
    {
        super(text);
        setFont(font);
        setForeground(color);
        setDisabledTextColor(color);
        setOpaque(false);
        setEditable(false);
        setFocusable(false);
        setLineWrap(true);
        setWrapStyleWord(true);
        setBorder(BorderFactory.createEmptyBorder());
        setHighlighter(null);
        addComponentListener(new ComponentAdapter()
        {
            @Override
            public void componentResized(ComponentEvent e)
            {
                if (getWidth() != laidOutWidth)
                {
                    laidOutWidth = getWidth();
                    revalidate(); // The height depends on the width
                }
            }
        });
    }

    /**
     * Read-only text has no caret to keep visible: without this, every {@link #setText} would scroll the enclosing
     * scroll pane to this text.
     */
    @Override
    public void scrollRectToVisible(Rectangle rectangle)
    {
    }

    @Override
    public void setForeground(Color color)
    {
        super.setForeground(color);
        setDisabledTextColor(color);
    }

    @Override
    public Dimension getPreferredSize()
    {
        int width = getWidth() > 0 ? getWidth() : MIN_WIDTH * 3;
        Insets insets = getInsets();
        View view = getUI().getRootView(this);
        view.setSize(width - insets.left - insets.right, Integer.MAX_VALUE);
        int height = (int) Math.ceil(view.getPreferredSpan(View.Y_AXIS)) + insets.top + insets.bottom;
        return new Dimension(MIN_WIDTH, height);
    }

    @Override
    public Dimension getMinimumSize()
    {
        return new Dimension(MIN_WIDTH, getPreferredSize().height);
    }

    @Override
    public Dimension getMaximumSize()
    {
        return new Dimension(Integer.MAX_VALUE, getPreferredSize().height);
    }
}
