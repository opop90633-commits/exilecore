package tw.exilecore.client;

import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.MutableComponent;
import net.minecraft.network.chat.TextColor;
import net.minecraft.util.FormattedCharSequence;
import tw.exilecore.client.core.HudData;

import java.util.List;

/** 2026-11-02（C 組）：把伺服器拆好顏色的字（{@link HudData.Part}）畫出來的小工具。 */
final class FeedText {

    private FeedText() {
    }

    static MutableComponent component(final List<HudData.Part> parts) {
        final MutableComponent out = Component.empty();
        for (final HudData.Part part : parts) {
            out.append(Component.literal(part.text()).withStyle(style -> style.withColor(TextColor.fromRgb(part.color()))));
        }
        return out;
    }

    /** 縮放 scale 畫一行（左上角 x, y）。 */
    static void text(final GuiGraphicsExtractor g, final Font font, final Component text, final float x, final float y, final float scale) {
        g.pose().pushMatrix();
        g.pose().translate(x, y);
        g.pose().scale(scale, scale);
        g.text(font, text, 0, 0, 0xFFFFFFFF, true);
        g.pose().popMatrix();
    }

    /** 縮放 scale、置中畫一行。 */
    static void centered(final GuiGraphicsExtractor g, final Font font, final Component text, final float cx, final float y, final float scale) {
        g.pose().pushMatrix();
        g.pose().translate(cx, y);
        g.pose().scale(scale, scale);
        g.text(font, text, -font.width(text) / 2, 0, 0xFFFFFFFF, true);
        g.pose().popMatrix();
    }

    /** 縮放 scale 換行畫（寬 width 是畫出來的寬）；回傳畫了多高。 */
    static int wrapped(final GuiGraphicsExtractor g, final Font font, final Component text, final int x, final int y, final int width, final float scale) {
        final List<FormattedCharSequence> lines = font.split(text, Math.max(10, Math.round(width / scale)));
        g.pose().pushMatrix();
        g.pose().translate(x, y);
        g.pose().scale(scale, scale);
        int dy = 0;
        for (final FormattedCharSequence line : lines) {
            g.text(font, line, 0, dy, 0xFFFFFFFF, true);
            dy += font.lineHeight;
        }
        g.pose().popMatrix();
        return Math.round(dy * scale);
    }

    /** 換行之後的高度（畫之前先量）。 */
    static int wrappedHeight(final Font font, final Component text, final int width, final float scale) {
        return Math.round(font.split(text, Math.max(10, Math.round(width / scale))).size() * font.lineHeight * scale);
    }

    /** 轉 degrees 度的箭頭（0 = 朝上，正的往右轉），中心 (cx, cy)。 */
    static void arrow(final GuiGraphicsExtractor g, final Font font, final float cx, final float cy, final double degrees, final int color,
                      final float scale) {
        g.pose().pushMatrix();
        g.pose().translate(cx, cy);
        g.pose().rotate((float) Math.toRadians(degrees));
        g.pose().scale(scale, scale);
        final String glyph = "▲";
        g.text(font, glyph, -font.width(glyph) / 2, -4, color, true);
        g.pose().popMatrix();
    }
}
