package org.example.gui;

import javax.swing.*;
import java.awt.*;
import java.awt.geom.Path2D;
import java.awt.geom.RoundRectangle2D;

/** Check box with a rounded box that is filled with the accent color and a white check mark when checked. */
public class ModernCheckBox extends JCheckBox
{
    private static final int BOX = 18;

    public ModernCheckBox(String text)
    {
        super(text);
        setOpaque(false);
        setFocusPainted(false);
        setRolloverEnabled(true);
        setForeground(Theme.TEXT);
        setFont(Theme.regular(13.5f));
        setIconTextGap(10);
        setBorder(BorderFactory.createEmptyBorder(2, 0, 2, 0));
        setCursor(Cursor.getPredefinedCursor(Cursor.HAND_CURSOR));
        setIcon(new BoxIcon());
    }

    private class BoxIcon implements Icon
    {
        @Override
        public void paintIcon(Component component, Graphics graphics, int x, int y)
        {
            Graphics2D g = Theme.smooth(graphics);
            ButtonModel model = getModel();
            boolean enabled = isEnabled();
            var box = new RoundRectangle2D.Float(x + 0.5f, y + 0.5f, BOX - 1, BOX - 1, 7, 7);
            if (isSelected())
            {
                g.setColor(enabled ? (model.isRollover() ? Theme.ACCENT_LIGHT : Theme.ACCENT) : Theme.TEXT_DISABLED);
                g.fill(box);
                var check = new Path2D.Float();
                check.moveTo(x + 4.5f, y + 9.5f);
                check.lineTo(x + 7.8f, y + 12.8f);
                check.lineTo(x + 13.5f, y + 5.5f);
                g.setColor(Theme.TEXT);
                g.setStroke(new BasicStroke(2.2f, BasicStroke.CAP_ROUND, BasicStroke.JOIN_ROUND));
                g.draw(check);
            }
            else
            {
                g.setColor(Theme.HEADER);
                g.fill(box);
                g.setColor(enabled && model.isRollover() ? Theme.ACCENT : Theme.BORDER);
                g.setStroke(new BasicStroke(1.5f));
                g.draw(box);
            }
            if (isFocusOwner())
            {
                g.setColor(Theme.withAlpha(Theme.ACCENT_LIGHT, 150));
                g.setStroke(new BasicStroke(1.2f));
                g.draw(new RoundRectangle2D.Float(x - 2, y - 2, BOX + 3, BOX + 3, 10, 10));
            }
            g.dispose();
        }

        @Override
        public int getIconWidth()
        {
            return BOX + 2;
        }

        @Override
        public int getIconHeight()
        {
            return BOX + 2;
        }
    }
}
