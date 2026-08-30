package ru.pleeey.bwsutil.client.overlay;

import net.minecraft.client.gui.GuiGraphics;

/**
 * Примитивы прицела, общие для оверлея и модулей наведения.
 *
 * <p>Вынесены отдельно, потому что метку упреждения рисует модуль наведения, а не сам оверлей:
 * модуль подменяем, а вид метки должен оставаться одним и тем же.</p>
 */
public final class ScopeGfx {

    private ScopeGfx() {}

    /** Ромбовидная метка упреждения (◇). */
    public static void drawLeadDiamond(GuiGraphics g, int x, int y, int color, int outlineColor) {
        int r = 6;
        // Тень (каждая диагональная грань смещена на 1px)
        for (int i = 0; i <= r; i++) {
            int hw = r - i;
            g.fill(x - hw - 1, y - i - 1, x - hw + 1, y - i + 1, outlineColor);
            g.fill(x + hw,     y - i - 1, x + hw + 2, y - i + 1, outlineColor);
            g.fill(x - hw - 1, y + i - 1, x - hw + 1, y + i + 1, outlineColor);
            g.fill(x + hw,     y + i - 1, x + hw + 2, y + i + 1, outlineColor);
        }
        // Цветные пиксели граней ромба
        for (int i = 0; i <= r; i++) {
            int hw = r - i;
            g.fill(x - hw, y - i, x - hw + 1, y - i + 1, color);
            g.fill(x + hw, y - i, x + hw + 1, y - i + 1, color);
            g.fill(x - hw, y + i, x - hw + 1, y + i + 1, color);
            g.fill(x + hw, y + i, x + hw + 1, y + i + 1, color);
        }
        // Яркий центр
        g.fill(x - 1, y - 1, x + 2, y + 2, outlineColor);
        g.fill(x, y, x + 1, y + 1, brighten(color));
    }

    /** Подпись с чёрной тенью в один пиксель. */
    public static void drawShadowed(GuiGraphics g, net.minecraft.client.gui.Font font,
                                    String text, int x, int y, int color) {
        g.drawString(font, text, x + 1, y + 1, 0xFF000000, false);
        g.drawString(font, text, x, y, color, false);
    }

    public static int applyAlpha(int argb, float factor) {
        int a = (int) (((argb >> 24) & 0xFF) * factor);
        return (argb & 0x00FFFFFF) | (a << 24);
    }

    public static int brighten(int argb) {
        int r  = Math.min(255, ((argb >> 16) & 0xFF) + 80);
        int gv = Math.min(255, ((argb >>  8) & 0xFF) + 80);
        int b  = Math.min(255, ( argb        & 0xFF) + 80);
        return (argb & 0xFF000000) | (r << 16) | (gv << 8) | b;
    }
}
