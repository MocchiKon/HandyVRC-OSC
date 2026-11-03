package org.example.gui;

import org.junit.jupiter.api.Test;

import javax.swing.*;
import javax.swing.plaf.basic.BasicComboBoxUI;
import java.awt.*;
import java.awt.image.BufferedImage;

import static org.assertj.core.api.Assertions.assertThat;

class ModernComboBoxTest
{
    @Test
    void showsTheSelectedValueAndEveryListEntryInWhite() throws Exception
    {
        SwingUtilities.invokeAndWait(() ->
        {
            var combo = new ModernComboBox<String>();
            combo.addItem("First available device");
            combo.addItem("MyTest1 (Simulated Stroker)");
            combo.setSelectedIndex(1);
            var list = new JList<>(new String[]{"First available device", "MyTest1 (Simulated Stroker)"});

            assertThat(foreground(combo, list, "MyTest1 (Simulated Stroker)", -1, false)).isEqualTo(Theme.TEXT);
            assertThat(foreground(combo, list, "MyTest1 (Simulated Stroker)", 1, true)).isEqualTo(Theme.TEXT);
            assertThat(foreground(combo, list, "First available device", 0, false)).isEqualTo(Theme.TEXT);
        });
    }

    @Test
    void paintsTheFocusedValueInWhite() throws Exception
    {
        SwingUtilities.invokeAndWait(() ->
        {
            var combo = new ModernComboBox<String>();
            combo.addItem("First available device");
            combo.addItem("MyTest1 (Simulated Stroker)");
            combo.setSelectedIndex(1);
            Color[] paintedForeground = new Color[1];
            @SuppressWarnings("unchecked")
            ListCellRenderer<Object> original = (ListCellRenderer<Object>) combo.getRenderer();
            // Swing replaces the renderer colors after it asked the renderer, so the color has to be recorded when
            // the label is painted, not when the renderer returns it.
            combo.setRenderer((list, value, index, selected, hasFocus) ->
            {
                JLabel label = (JLabel) original.getListCellRendererComponent(list, value, index, selected, hasFocus);
                return new JLabel(label.getText())
                {
                    @Override
                    public void paint(Graphics graphics)
                    {
                        paintedForeground[0] = getForeground();
                        super.paint(graphics);
                    }
                };
            });
            var image = new BufferedImage(300, 40, BufferedImage.TYPE_INT_ARGB);
            Graphics graphics = image.createGraphics();
            ((BasicComboBoxUI) combo.getUI())
                    .paintCurrentValue(graphics, new Rectangle(0, 0, 300, 40), true);
            graphics.dispose();

            assertThat(paintedForeground[0]).isEqualTo(Theme.TEXT);
        });
    }

    @Test
    void usesTheDisabledColorOnlyWhenTheComboIsDisabled() throws Exception
    {
        SwingUtilities.invokeAndWait(() ->
        {
            var combo = new ModernComboBox<String>();
            combo.addItem("First available device");
            combo.setEnabled(false);
            var list = new JList<>(new String[]{"First available device"});

            assertThat(foreground(combo, list, "First available device", -1, false)).isEqualTo(Theme.TEXT_DISABLED);
        });
    }

    private static java.awt.Color foreground(ModernComboBox<String> combo, JList<String> list, Object value, int index,
                                            boolean selected)
    {
        @SuppressWarnings("unchecked")
        ListCellRenderer<Object> renderer = (ListCellRenderer<Object>) combo.getRenderer();
        JLabel label = (JLabel) renderer.getListCellRendererComponent(list, value, index, selected, false);
        return label.getForeground();
    }
}
