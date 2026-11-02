package tw.exilecore.client;

import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.MutableComponent;
import net.minecraft.network.chat.TextColor;
import net.minecraft.world.effect.MobEffectInstance;
import tw.exilecore.client.core.HudData;
import tw.exilecore.client.core.HudLayout;
import tw.exilecore.client.core.MapPanelLayout;
import tw.exilecore.client.core.ModConfig;
import tw.exilecore.client.core.WorldCodec;

import java.util.List;

/**
 * 2026-11-02 照站長（模組 1.4 B 組）：右上角的地圖資訊框（exilecore:map）：名稱、階級、怪物等級、剩幾門／幾次、探索與清除比例、
 * Boss、地圖詞綴、事件與進度。按 F6 收起（只剩名稱與一行摘要）。原版的狀態效果圖示在右上角時往下讓開。
 * 這次之前進圖只有 2.2 秒的大標題。
 */
final class MapPanelRenderer {

    private MapPanelRenderer() {
    }

    static void extract(final GuiGraphicsExtractor g) {
        final ModConfig config = ExileCoreClient.config;
        final WorldCodec.MapPanel panel = WorldHudClient.map;
        final Minecraft mc = Minecraft.getInstance();
        if (panel == null || !ExileCoreClient.connected || !config.hudEnabled || !config.mapPanel || mc.player == null
                || mc.gui.hud.isHidden()) {
            return;
        }
        final Font font = mc.font;
        final int width = MapPanelLayout.WIDTH;
        final int x0 = g.guiWidth() + config.mapPanelX - width;
        // 2026-11-02（C 組合併）：接在小地圖下面（小地圖沒畫時照原本貼著右上角／狀態效果圖示下面）
        final int y0 = top(g.guiWidth(), g.guiHeight(), config, mc);
        final List<MapPanelLayout.Row> rows = rows(panel, config, font, g.guiHeight(), y0);
        final int height = MapPanelLayout.totalHeight(rows);
        g.fill(x0, y0, x0 + width, y0 + height, 0xA0000000);
        g.outline(x0, y0, width, height, 0xFF3A3A48);
        int y = y0 + 3;
        for (final MapPanelLayout.Row row : rows) {
            final float s = row.small() ? MapPanelLayout.SMALL : 1f;
            final int inner = Math.round((width - 6) / s);
            g.pose().pushMatrix();
            g.pose().translate(x0 + 3, y);
            g.pose().scale(s, s);
            if (!row.left().isEmpty()) {
                g.text(font, component(row.left()), 0, 0, 0xFFFFFFFF, true);
            }
            if (!row.right().isEmpty()) {
                final MutableComponent right = component(row.right());
                g.text(font, right, inner - font.width(right), 0, 0xFFFFFFFF, true);
            }
            g.pose().popMatrix();
            y += row.small() ? 7 : 10;
            if (row.progress() >= 0) {
                final int full = width - 6;
                g.fill(x0 + 3, y, x0 + 3 + full, y + 2, 0xFF202020);
                g.fill(x0 + 3, y, x0 + 3 + Math.round(full * Math.min(100, row.progress()) / 100f), y + 2, row.barColor());
                y += 3;
            }
        }
        if (config.mapPanelCollapsed) {
            return;
        }
        // 收起的按鍵提示（小字，框的左下外面）
        final String hint = keyHint();
        if (!hint.isEmpty()) {
            g.pose().pushMatrix();
            g.pose().translate(x0 + width - 2, y0 + height + 1);
            g.pose().scale(0.5f, 0.5f);
            g.text(font, hint, -font.width(hint), 0, 0x90FFFFFF, true);
            g.pose().popMatrix();
        }
    }

    /**
     * 2026-11-02 照站長：這一幀要畫的行：太長（下緣超過畫面高度 {@value HudLayout#MAP_PANEL_LIMIT} 成，下面是側邊面板）就縮短，
     * 地圖詞綴、事件只留前幾行，最後寫「…F6 展開」；連這樣都放不下就只畫摘要。這次之前全部畫出來，跟側邊面板疊在一起。
     */
    static List<MapPanelLayout.Row> rows(final WorldCodec.MapPanel panel, final ModConfig config, final Font font, final int guiHeight,
                                         final int top) {
        final List<MapPanelLayout.Row> rows = MapPanelLayout.layout(panel, config.mapPanelCollapsed, font::width);
        if (config.mapPanelCollapsed) {
            return rows;
        }
        final String key = WorldHudClient.toggleMapPanel == null || WorldHudClient.toggleMapPanel.isUnbound() ? ""
                : WorldHudClient.toggleMapPanel.getTranslatedKeyMessage().getString() + " ";
        return MapPanelLayout.fit(rows, MapPanelLayout.layout(panel, true, font::width), MapPanelLayout.keepRows(panel),
                Math.max(20, HudLayout.mapPanelLimit(guiHeight) - top), key + "展開");
    }

    private static String keyHint() {
        if (WorldHudClient.toggleMapPanel == null || WorldHudClient.toggleMapPanel.isUnbound()) {
            return "";
        }
        return WorldHudClient.toggleMapPanel.getTranslatedKeyMessage().getString() + " 收起";
    }

    private static MutableComponent component(final List<HudData.Part> parts) {
        final MutableComponent out = Component.empty();
        for (final HudData.Part part : parts) {
            out.append(Component.literal(part.text()).withStyle(style -> style.withColor(TextColor.fromRgb(part.color()))));
        }
        return out;
    }

    /** 2026-11-02（C 組合併）：框的上緣：原本的位置（狀態效果圖示下面），但至少在小地圖下面 3 格。 */
    static int top(final int guiWidth, final int guiHeight, final ModConfig config, final Minecraft mc) {
        final int own = config.mapPanelY + effectsOffset(mc);
        final tw.exilecore.client.core.HudDrag.Rect minimap = tw.exilecore.client.core.HudDrag.bounds(MinimapRenderer.ID, guiWidth, guiHeight);
        final int left = guiWidth + config.mapPanelX - MapPanelLayout.WIDTH;
        final boolean above = minimap != null && minimap.x() < left + MapPanelLayout.WIDTH && left < minimap.right() && minimap.y() < own + 40;
        return above ? Math.max(own, minimap.bottom() + 3) : own; // 小地圖被拖到別的地方（不在框的上面）就不讓
    }

    /** 2026-11-02（C 組合併）：這一幀框的範圍（設定畫面拖動用）；不畫 null。 */
    static tw.exilecore.client.core.HudDrag.Rect bounds(final int guiWidth, final int guiHeight) {
        final ModConfig config = ExileCoreClient.config;
        final WorldCodec.MapPanel panel = WorldHudClient.map;
        final Minecraft mc = Minecraft.getInstance();
        if (panel == null || mc.player == null || !config.mapPanel) {
            return mc.gui.screen() instanceof HudEditScreen && config.mapPanel
                    ? new tw.exilecore.client.core.HudDrag.Rect(guiWidth + config.mapPanelX - MapPanelLayout.WIDTH, config.mapPanelY, MapPanelLayout.WIDTH, 40)
                    : null;
        }
        final int top = top(guiWidth, guiHeight, config, mc);
        final int height = MapPanelLayout.totalHeight(rows(panel, config, mc.font, guiHeight, top));
        return new tw.exilecore.client.core.HudDrag.Rect(guiWidth + config.mapPanelX - MapPanelLayout.WIDTH, top, MapPanelLayout.WIDTH, height);
    }

    /** 原版右上角的狀態效果圖示佔了幾排（有益的一排、有害的一排，各 26 像素）。 */
    static int effectsOffset(final Minecraft mc) {
        boolean good = false;
        boolean bad = false;
        for (final MobEffectInstance effect : mc.player.getActiveEffects()) {
            if (!effect.showIcon()) {
                continue;
            }
            if (effect.getEffect().value().isBeneficial()) {
                good = true;
            } else {
                bad = true;
            }
        }
        return (good ? 26 : 0) + (bad ? 26 : 0);
    }
}
