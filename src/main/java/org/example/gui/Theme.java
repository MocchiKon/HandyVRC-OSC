package org.example.gui;

import javax.swing.*;
import javax.swing.border.Border;
import javax.swing.plaf.BorderUIResource;
import javax.swing.plaf.ColorUIResource;
import javax.swing.plaf.FontUIResource;
import java.awt.*;
import java.util.Arrays;
import java.util.List;
import java.util.Set;

/**
 * Colors, fonts and shared painting helpers of the GUI: a dark blue background, bright blue accents and white text.
 */
public final class Theme
{
    // Dark blues (background and surfaces)
    public static final Color BACKGROUND = new Color(0x0A1224);
    public static final Color HEADER = new Color(0x070D1B);
    public static final Color SURFACE = new Color(0x111D36);
    public static final Color SURFACE_RAISED = new Color(0x182847);
    public static final Color SURFACE_HOVER = new Color(0x1F3359);
    public static final Color BORDER = new Color(0x253A63);
    public static final Color TRACK = new Color(0x22365C);

    // Bright blues (accents)
    public static final Color ACCENT = new Color(0x2F8CFF);
    public static final Color ACCENT_LIGHT = new Color(0x62AAFF);
    public static final Color ACCENT_DARK = new Color(0x1A64D6);
    public static final Color ACCENT_GLOW = new Color(0x2F, 0x8C, 0xFF, 70);

    // Whites
    public static final Color TEXT = Color.WHITE;
    public static final Color TEXT_SECONDARY = new Color(0xB4C4E0);
    public static final Color TEXT_MUTED = new Color(0x7489AE);
    public static final Color TEXT_DISABLED = new Color(0x4D5F82);

    public static final Color ERROR = new Color(0xFF6B81);
    public static final Color ERROR_BACKGROUND = new Color(0x3A1730);
    public static final Color WARNING = new Color(0xFFC46B);

    public static final int RADIUS = 14;
    public static final int CONTROL_RADIUS = 10;

    private static final List<String> PREFERRED_FONTS =
            List.of("Segoe UI", "Inter", "SF Pro Text", "Helvetica Neue", "Noto Sans", "Ubuntu", "DejaVu Sans");
    private static String fontFamily;

    private Theme()
    {
    }

    /** Smooth text everywhere; has to be called before the first window is created. */
    public static void prepareRendering()
    {
        System.setProperty("awt.useSystemAAFontSettings", "lcd");
        System.setProperty("swing.aatext", "true");
    }

    /**
     * Every font size of the GUI is multiplied by this, so the text size can be tuned in one place. The sizes in the
     * code are the compact design sizes, which were too small to read comfortably.
     */
    public static final float FONT_SCALE = 1.0f;

    public static Font font(int style, float size)
    {
        return new Font(fontFamily(), style, 1).deriveFont(style, size * FONT_SCALE);
    }

    public static Font regular(float size)
    {
        return font(Font.PLAIN, size);
    }

    public static Font bold(float size)
    {
        return font(Font.BOLD, size);
    }

    private static final List<String> PREFERRED_MONO_FONTS =
            List.of("JetBrains Mono", "Cascadia Mono", "Consolas", "SF Mono", "Ubuntu Mono", "DejaVu Sans Mono");
    private static String monoFontFamily;

    /** Monospaced font (for the logs). */
    public static Font mono(float size)
    {
        return new Font(monoFontFamily(), Font.PLAIN, 1).deriveFont(size * FONT_SCALE);
    }

    private static synchronized String monoFontFamily()
    {
        if (monoFontFamily == null)
        {
            Set<String> available = Set.copyOf(Arrays.asList(
                    GraphicsEnvironment.getLocalGraphicsEnvironment().getAvailableFontFamilyNames()));
            monoFontFamily = PREFERRED_MONO_FONTS.stream().filter(available::contains).findFirst().orElse(Font.MONOSPACED);
        }
        return monoFontFamily;
    }

    private static synchronized String fontFamily()
    {
        if (fontFamily == null)
        {
            Set<String> available = Set.copyOf(Arrays.asList(
                    GraphicsEnvironment.getLocalGraphicsEnvironment().getAvailableFontFamilyNames()));
            fontFamily = PREFERRED_FONTS.stream().filter(available::contains).findFirst().orElse(Font.SANS_SERIF);
        }
        return fontFamily;
    }

    /** Styles the parts of Swing that are not painted by the components of this package (tooltips, dialogs). */
    public static void installDefaults()
    {
        UIManager.put("swing.boldMetal", Boolean.FALSE);
        UIManager.put("ToolTip.background", new ColorUIResource(SURFACE_RAISED));
        UIManager.put("ToolTip.foreground", new ColorUIResource(TEXT));
        UIManager.put("ToolTip.font", new FontUIResource(regular(12.5f)));
        Border tooltipBorder = BorderFactory.createCompoundBorder(
                BorderFactory.createLineBorder(ACCENT_DARK, 1),
                BorderFactory.createEmptyBorder(7, 10, 7, 10));
        UIManager.put("ToolTip.border", new BorderUIResource(tooltipBorder));
        UIManager.put("ToolTip.borderInactive", new BorderUIResource(tooltipBorder));

        UIManager.put("OptionPane.background", new ColorUIResource(SURFACE));
        UIManager.put("Panel.background", new ColorUIResource(SURFACE));
        UIManager.put("OptionPane.messageForeground", new ColorUIResource(TEXT));
        UIManager.put("OptionPane.messageFont", new FontUIResource(regular(13f)));
        UIManager.put("OptionPane.buttonFont", new FontUIResource(bold(12.5f)));
        UIManager.put("Button.background", new ColorUIResource(ACCENT));
        UIManager.put("Button.foreground", new ColorUIResource(TEXT));
        UIManager.put("Button.select", new ColorUIResource(ACCENT_DARK));
        UIManager.put("Button.focus", new ColorUIResource(ACCENT_LIGHT));
        UIManager.put("TextField.selectionBackground", new ColorUIResource(ACCENT_DARK));
        UIManager.put("TextField.selectionForeground", new ColorUIResource(TEXT));

        ToolTipManager.sharedInstance().setInitialDelay(200);
        ToolTipManager.sharedInstance().setReshowDelay(100);
        ToolTipManager.sharedInstance().setDismissDelay(30_000);
    }

    /** Wraps a tooltip so that a long text is shown as a paragraph instead of one very wide line. */
    public static String tooltip(String text)
    {
        if (text == null)
        {
            return null;
        }
        String html = text.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;").replace("\n", "<br>");
        return text.length() > 60
                ? "<html><p width=\"" + Math.round(280 * FONT_SCALE) + "\">" + html + "</p></html>"
                : "<html>" + html + "</html>";
    }

    public static Graphics2D smooth(Graphics graphics)
    {
        Graphics2D g = (Graphics2D) graphics.create();
        g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
        g.setRenderingHint(RenderingHints.KEY_STROKE_CONTROL, RenderingHints.VALUE_STROKE_PURE);
        g.setRenderingHint(RenderingHints.KEY_TEXT_ANTIALIASING, RenderingHints.VALUE_TEXT_ANTIALIAS_ON);
        g.setRenderingHint(RenderingHints.KEY_RENDERING, RenderingHints.VALUE_RENDER_QUALITY);
        return g;
    }

    public static Color withAlpha(Color color, int alpha)
    {
        return new Color(color.getRed(), color.getGreen(), color.getBlue(), alpha);
    }

    public static Color mix(Color from, Color to, double ratio)
    {
        double r = Math.clamp(ratio, 0, 1);
        return new Color(
                (int) Math.round(from.getRed() + (to.getRed() - from.getRed()) * r),
                (int) Math.round(from.getGreen() + (to.getGreen() - from.getGreen()) * r),
                (int) Math.round(from.getBlue() + (to.getBlue() - from.getBlue()) * r),
                (int) Math.round(from.getAlpha() + (to.getAlpha() - from.getAlpha()) * r));
    }
}
