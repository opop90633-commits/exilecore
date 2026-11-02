package tw.exilecore.client;

import net.minecraft.client.gui.Font;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.MutableComponent;
import net.minecraft.network.chat.TextColor;
import tw.exilecore.client.core.HudData;

import java.util.List;

/** 2026-11-02（1.4）：伺服器的字段（一段字、一個顏色）換成原版的文字元件；太寬的截掉、後面寫「…」。 */
final class Parts {

    private Parts() {
    }

    static MutableComponent of(final List<HudData.Part> parts) {
        final MutableComponent out = Component.empty();
        for (final HudData.Part part : parts) {
            out.append(Component.literal(part.text()).withStyle(style -> style.withColor(TextColor.fromRgb(part.color() & 0xFFFFFF))));
        }
        return out;
    }

    /** 不超過 maxWidth 寬（GUI 像素，原版字型）；截掉的後面寫「…」。 */
    static MutableComponent fit(final Font font, final List<HudData.Part> parts, final int maxWidth) {
        final MutableComponent full = of(parts);
        if (font.width(full) <= maxWidth) {
            return full;
        }
        final int ellipsis = font.width("…");
        final MutableComponent out = Component.empty();
        int used = 0;
        for (final HudData.Part part : parts) {
            final int width = font.width(part.text());
            final TextColor color = TextColor.fromRgb(part.color() & 0xFFFFFF);
            if (used + width <= maxWidth - ellipsis) {
                out.append(Component.literal(part.text()).withStyle(style -> style.withColor(color)));
                used += width;
                continue;
            }
            final String cut = font.plainSubstrByWidth(part.text(), Math.max(0, maxWidth - ellipsis - used));
            out.append(Component.literal(cut + "…").withStyle(style -> style.withColor(color)));
            break;
        }
        return out;
    }

    /** ARGB 顏色乘上透明度（太淡的回傳 0：原版的字 alpha 太小會變成不透明，乾脆不畫）。 */
    static int alpha(final int argb, final double alpha) {
        final int a = (int) Math.round(((argb >>> 24) & 0xFF) * Math.max(0, Math.min(1, alpha)));
        return a < 8 ? 0 : a << 24 | argb & 0xFFFFFF;
    }
}
