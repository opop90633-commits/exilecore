package tw.exilecore.client;

import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import tw.exilecore.client.core.HudData;
import tw.exilecore.client.core.HudLayout;

import java.util.List;

/**
 * 2026-11-02 照站長（1.4）：戰鬥資源畫在魔力球外側（以前是畫面上方那條 Boss 血條的名稱，「│」接成一長串）：
 * 最上面是怒氣的細條（滿了變紅、消退中變灰），下面每種充能一排點點（實心＝有、空心＝上限），再下面是其他資源（連擊、灌注、熱度、三相…）。
 * 右邊放不下（GUI 縮放大）時只畫細條與點點、其他資源縮小成一行。
 */
final class ResourceRenderer {

    private ResourceRenderer() {
    }

    private static final int RAGE = 0xFFFF6A3D;
    private static final int RAGE_MAX = 0xFFFF3B1F;
    private static final int RAGE_DECAY = 0xFFB0B0B0;

    static void extract(final GuiGraphicsExtractor g, final Font font, final HudData.Resources res) {
        final HudLayout.Box box = HudLayout.resources(g.guiWidth(), g.guiHeight(), OrbRenderer.size());
        if (box.w() < 24) {
            return;
        }
        final boolean compact = box.w() < HudLayout.COMPACT_RES;
        int y = box.y() + 1;
        if (res.rage() > 0) {
            final int barWidth = compact ? box.w() - 2 : 64;
            final double fraction = Math.min(1, res.rage() / (double) Math.max(1, res.rageMax()));
            final int color = res.decaying() ? RAGE_DECAY : res.rage() >= res.rageMax() ? RAGE_MAX : RAGE;
            g.fill(box.x(), y, box.x() + barWidth, y + 3, 0xC0200806);
            g.fill(box.x(), y, box.x() + (int) Math.round(barWidth * fraction), y + 3, color);
            g.outline(box.x() - 1, y - 1, barWidth + 2, 5, 0xFF000000);
            if (!compact) {
                label(g, font, "怒氣 " + res.rage() + "/" + res.rageMax() + (res.rage() >= res.rageMax() ? " MAX" : res.decaying() ? " 消退" : ""),
                        box.x() + barWidth + 4, y - 0.5f, color, 0.5f);
            }
            y += 6;
        }
        for (final HudData.Charge charge : res.charges()) {
            if (y + 4 > box.bottom()) {
                break;
            }
            final int color = 0xFF000000 | charge.color();
            int x = box.x();
            if (!compact && !charge.symbol().isEmpty()) {
                label(g, font, charge.symbol(), x, y - 1, color, 0.6f);
                x += Math.round(font.width(charge.symbol()) * 0.6f) + 2;
            }
            final int max = Math.max(charge.count(), charge.max());
            for (int i = 0; i < max && x + 3 <= box.right(); i++, x += 5) {
                if (i < charge.count()) {
                    g.fill(x, y, x + 4, y + 4, 0xFF000000);
                    g.fill(x, y, x + 3, y + 3, color);
                } else {
                    g.outline(x, y, 3, 3, (color & 0x00FFFFFF) | 0x90000000);
                }
            }
            y += 6;
        }
        final List<List<HudData.Part>> lines = HudData.segments(res.other());
        final float scale = compact ? 0.5f : 0.75f;
        final int step = compact ? 5 : 8;
        for (final List<HudData.Part> line : lines) {
            if (y + step > box.bottom() + 1) {
                break;
            }
            g.pose().pushMatrix();
            g.pose().translate(box.x(), y);
            g.pose().scale(scale, scale);
            g.text(font, Parts.fit(font, line, Math.round((box.w() - 2) / scale)), 0, 0, 0xFFFFFFFF, true);
            g.pose().popMatrix();
            y += step;
        }
    }

    private static void label(final GuiGraphicsExtractor g, final Font font, final String text, final float x, final float y, final int color,
                              final float scale) {
        g.pose().pushMatrix();
        g.pose().translate(x, y);
        g.pose().scale(scale, scale);
        g.text(font, text, 0, 0, color, true);
        g.pose().popMatrix();
    }
}
