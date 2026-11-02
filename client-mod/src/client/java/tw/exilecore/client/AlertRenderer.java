package tw.exilecore.client;

import net.minecraft.client.DeltaTracker;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.network.chat.Component;
import tw.exilecore.client.core.AlertData;
import tw.exilecore.client.core.HudDrag;
import tw.exilecore.client.core.ModConfig;

import java.util.List;

/**
 * 2026-11-02 照站長（C 組）：Boss 預警畫在畫面上半部的固定一排（Boss 血條下面，可以同時好幾則；優先度高的在中間），
 * 有座標的加一個箭頭（危險區＝往外逃的方向、安全區＝往那裡走）與距離；換階段的機制清單畫在左上角（這個階段要做什麼＋剩餘時間）。
 * 這次之前是原版的大標題（只有一個槽，後送的蓋掉前面的）。兩個都可以在設定畫面拖動（alerts、mechanics）。
 */
final class AlertRenderer {

    private AlertRenderer() {
    }

    static final String TOP_ID = "alerts";
    static final String SIDE_ID = "mechanics";
    static final int CARD_HEIGHT = 26;
    static final int CARD_MAX = 170;
    static final int SIDE_WIDTH = 132;

    private static final int[] PRIORITY_COLOR = {0xFFA0A0A0, 0xFFFFC040, 0xFFFF8030, 0xFFFF3030};

    static boolean active() {
        final Minecraft mc = Minecraft.getInstance();
        return ExileCoreClient.connected && ExileCoreClient.config.showAlerts && mc.player != null && !mc.gui.hud.isHidden();
    }

    /** 上面那排的範圍（沒拖過）：畫面上方正中間、往下 alertsY 格。 */
    static HudDrag.Rect topBounds(final int width, final int height) {
        // 2026-11-02（C 組合併）：兩邊讓開右上角的地圖資訊框、側邊面板（寬 150）：畫面中間往兩邊對稱
        final int w = Math.max(CARD_MAX, Math.min(CARD_MAX * 2 + 4, 2 * (width / 2 - tw.exilecore.client.core.MapPanelLayout.WIDTH - 8)));
        // 2026-11-02（C 組合併）：在目標框（A 組推到 Boss 血條下面）的下面，不疊在一起
        final ModConfig config = ExileCoreClient.config;
        final int below = tw.exilecore.client.core.HudLayout.targetY(config.targetY, HudRenderer.bossBars(), height)
                + Math.round(tw.exilecore.client.core.HudLayout.TARGET_HEIGHT * config.scale) + 2;
        return new HudDrag.Rect(width / 2 - w / 2, Math.max(config.alertsY, below), w, CARD_HEIGHT * 2 + 2);
    }

    /** 機制清單的範圍（沒拖過）：左上角。 */
    static HudDrag.Rect sideBounds(final int width, final int height) {
        return new HudDrag.Rect(4, 4, SIDE_WIDTH, 64);
    }

    static void extract(final GuiGraphicsExtractor g, final DeltaTracker delta) {
        if (!active()) {
            return;
        }
        final long now = FeedsClient.ticks;
        final Font font = Minecraft.getInstance().font;
        final List<AlertData.Shown> top = FeedsClient.ALERTS.top(now);
        if (!top.isEmpty()) {
            HudDragBinding.begin(g, TOP_ID);
            drawTop(g, font, top, now);
            HudDragBinding.end(g);
        }
        final List<AlertData.Shown> side = FeedsClient.ALERTS.side(now);
        if (!side.isEmpty()) {
            HudDragBinding.begin(g, SIDE_ID);
            drawSide(g, font, side.getFirst(), now);
            HudDragBinding.end(g);
        }
    }

    /** 一排卡片：最要緊的在中間，其他的往兩邊排；放不下的換到第二排。 */
    private static void drawTop(final GuiGraphicsExtractor g, final Font font, final List<AlertData.Shown> list, final long now) {
        final ModConfig config = ExileCoreClient.config;
        final HudDrag.Rect area = topBounds(g.guiWidth(), g.guiHeight());
        final int[] widths = new int[list.size()];
        for (int i = 0; i < list.size(); i++) {
            widths[i] = cardWidth(font, list.get(i), now);
        }
        // 排序：0 在中間，1 右邊，2 左邊，3 再右邊…；寬度超過範圍的換到第二排
        int row = 0;
        int index = 0;
        while (index < list.size() && row < 2) {
            int total = widths[index];
            int end = index + 1;
            while (end < list.size() && total + 4 + widths[end] <= area.width()) {
                total += 4 + widths[end];
                end++;
            }
            int x = area.x() + (area.width() - total) / 2;
            final int y = area.y() + row * (CARD_HEIGHT + 2);
            // 把這一排照「中間、右、左」排好位置
            final int count = end - index;
            final int[] order = new int[count];
            for (int k = 0; k < count; k++) {
                order[k] = index + k;
            }
            final int[] seats = AlertData.seats(count);
            final int[] placed = new int[count];
            for (int k = 0; k < count; k++) {
                placed[seats[k]] = order[k];
            }
            for (int k = 0; k < count; k++) {
                final int i = placed[k];
                drawCard(g, font, list.get(i), x, y, widths[i], now, config);
                x += widths[i] + 4;
            }
            index = end;
            row++;
        }
    }

    private static int cardWidth(final Font font, final AlertData.Shown shown, final long now) {
        final AlertData.Alert alert = shown.alert();
        int text = Math.max(font.width(FeedText.component(alert.title())), Math.round(font.width(FeedText.component(alert.sub())) * 0.75f));
        if (shown.countdownLeft(now) > 0) {
            text += 24;
        }
        if (alert.at() != null) {
            text += 16;
        }
        return Math.max(60, Math.min(CARD_MAX, text + 10));
    }

    private static void drawCard(final GuiGraphicsExtractor g, final Font font, final AlertData.Shown shown, final int x, final int y, final int width,
                                 final long now, final ModConfig config) {
        final AlertData.Alert alert = shown.alert();
        final int accent = PRIORITY_COLOR[Math.max(0, Math.min(3, alert.priority()))];
        // 最高優先度的邊框閃一下（每 10 tick）
        final boolean flash = alert.priority() >= 3 && (now / 5) % 2 == 0;
        g.fill(x, y, x + width, y + CARD_HEIGHT, 0xB0100808);
        g.fill(x, y, x + 2, y + CARD_HEIGHT, accent);
        g.outline(x, y, width, CARD_HEIGHT, flash ? accent : 0xFF3A2A20);
        int left = x + 5;
        int right = x + width - 4;
        if (alert.at() != null) {
            final LocalPlayer player = Minecraft.getInstance().player;
            if (player != null) {
                final double degrees = AlertData.arrowDegrees(player.getX(), player.getZ(), player.getYRot(), alert.at()[0], alert.at()[2], alert.safe());
                FeedText.arrow(g, font, left + 5, y + 10, degrees, alert.safe() ? 0xFF45FF6A : 0xFFFF5555, 1.3f);
                final double distance = Math.hypot(alert.at()[0] - player.getX(), alert.at()[2] - player.getZ());
                FeedText.centered(g, font, Component.literal(Math.round(distance) + " 格"), left + 5, y + 18, 0.5f);
            }
            left += 15;
        }
        final long left0 = shown.countdownLeft(now);
        if (left0 > 0) {
            final String seconds = AlertData.countdownText(left0);
            g.pose().pushMatrix();
            g.pose().translate(right, y + 5);
            g.pose().scale(1.4f, 1.4f);
            g.text(font, seconds, -font.width(seconds), 0, left0 <= 40 ? 0xFFFF5555 : 0xFFFFFFFF, true);
            g.pose().popMatrix();
            right -= 26;
        }
        g.enableScissor(left, y, right, y + CARD_HEIGHT);
        g.text(font, FeedText.component(alert.title()), left, y + 4, 0xFFFFFFFF, true);
        if (!alert.sub().isEmpty()) {
            FeedText.text(g, font, FeedText.component(alert.sub()), left, y + 15, 0.75f);
        }
        g.disableScissor();
    }

    /** 機制清單：標題、每一行一個點、最下面剩餘時間的條。 */
    private static void drawSide(final GuiGraphicsExtractor g, final Font font, final AlertData.Shown shown, final long now) {
        final AlertData.Alert alert = shown.alert();
        final HudDrag.Rect area = sideBounds(g.guiWidth(), g.guiHeight());
        final int x = area.x();
        final int y = area.y();
        final int width = area.width();
        int height = 13;
        for (final List<tw.exilecore.client.core.HudData.Part> line : alert.lines()) {
            height += FeedText.wrappedHeight(font, Component.literal("• ").append(FeedText.component(line)), width - 8, 0.7f) + 1;
        }
        final long left = shown.countdownLeft(now);
        if (alert.countdown() > 0) {
            height += 10;
        }
        g.fill(x, y, x + width, y + height + 3, 0xA0080810);
        g.outline(x, y, width, height + 3, 0xFF5A4630);
        FeedText.text(g, font, FeedText.component(alert.title()), x + 4, y + 3, 0.85f);
        int dy = y + 14;
        for (final List<tw.exilecore.client.core.HudData.Part> line : alert.lines()) {
            dy += FeedText.wrapped(g, font, Component.literal("• ").withStyle(s -> s.withColor(0xFFD27F)).append(FeedText.component(line)),
                    x + 4, dy, width - 8, 0.7f) + 1;
        }
        if (alert.countdown() > 0) {
            final double fraction = alert.countdown() <= 0 ? 0 : left / (double) alert.countdown();
            g.fill(x + 4, dy + 2, x + width - 4, dy + 5, 0xFF301010);
            g.fill(x + 4, dy + 2, x + 4 + (int) Math.round((width - 8) * fraction), dy + 5, left <= 100 ? 0xFFFF5050 : 0xFFFFC040);
            final String text = left > 0 ? "剩 " + AlertData.countdownText(left) + " 秒" : "時間到";
            FeedText.text(g, font, Component.literal(text), x + width - 4 - font.width(text) * 0.6f, dy - 3, 0.6f);
        }
    }
}
