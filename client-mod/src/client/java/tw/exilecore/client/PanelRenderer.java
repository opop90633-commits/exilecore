package tw.exilecore.client;

import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.network.chat.MutableComponent;
import tw.exilecore.client.core.HudData;
import tw.exilecore.client.core.HudLayout;
import tw.exilecore.client.core.Panels;

import java.util.HashSet;
import java.util.List;

/**
 * 2026-11-02 照站長（1.4）：右邊的側邊面板（以前是記分板的側欄，最多 15 行、全部擠在一張）：戰鬥數據、狀態、召喚物、新手引導、任務、撿到，
 * 各自一個小面板，由上往下排；放不下的寫「…+N」，按「收起／展開側邊面板」（F10）全部只剩標題（設定 panelsCollapsed、collapsedPanels）。
 * 寬度不碰到畫面中間的 Boss 血條（{@link HudLayout#panelWidth}）。
 */
final class PanelRenderer {

    private PanelRenderer() {
    }

    /** 伺服器最後送的面板。 */
    static volatile List<Panels.Panel> server = List.of();

    static void onPacket(final byte[] data) {
        final List<Panels.Panel> parsed = Panels.parse(data);
        if (parsed != null) {
            server = parsed;
        }
    }

    /** 2026-11-02（1.4）：增益列放不下時改到面板的光環（HudRenderer 每一幀先決定）。 */
    static List<String> auras = List.of();

    static void clear() {
        server = List.of();
        auras = List.of();
    }

    /**
     * 2026-11-02 照站長：面板群這一幀的範圍：從畫面高度 40% 與地圖資訊框的下緣（{@link tw.exilecore.client.core.HudDrag#bounds}，含拖過的偏移）
     * 比較低的那個開始；面板群自己被拖過就照拖過的位置（不再讓）。這次之前一律從 40% 開始，地圖資訊框長的時候疊在一起。
     */
    static HudLayout.Box area(final int guiWidth, final int guiHeight) {
        final HudLayout.Box base = HudLayout.panels(guiWidth, guiHeight);
        final int[] dragged = tw.exilecore.client.core.HudDrag.offset(HudDragBinding.PANELS);
        if (dragged[0] != 0 || dragged[1] != 0) {
            return base;
        }
        final tw.exilecore.client.core.HudDrag.Rect map = tw.exilecore.client.core.HudDrag.bounds(HudDragBinding.MAP_PANEL, guiWidth, guiHeight);
        final int top = HudLayout.panelsTop(guiWidth, guiHeight, map == null ? null : new HudLayout.Box(map.x(), map.y(), map.width(), map.height()));
        return new HudLayout.Box(base.x(), top, base.w(), Math.max(0, base.bottom() - top));
    }

    /** 2026-11-02 照站長：每個面板這一幀畫在哪（含整群的偏移；拖動畫面畫框用）。 */
    private static final java.util.Map<String, tw.exilecore.client.core.HudDrag.Rect> LAST = new java.util.HashMap<>();

    /** 2026-11-02 照站長：每個面板標題列這一幀在畫面上的實際位置（點標題收起／展開用）；這次之前標題列點不了。 */
    private static final java.util.Map<String, tw.exilecore.client.core.HudDrag.Rect> HEADERS = new java.util.LinkedHashMap<>();

    /**
     * 2026-11-02 照站長：打開聊天（T）時滑鼠點面板的標題列：收起↔展開那一個面板（記在設定 collapsedPanels）；
     * 整群收起（F10）時點任何一個標題先整群展開。點到了回傳 true（這一下不再交給聊天畫面）。
     */
    static boolean click(final double mouseX, final double mouseY) {
        for (final java.util.Map.Entry<String, tw.exilecore.client.core.HudDrag.Rect> entry : HEADERS.entrySet()) {
            final tw.exilecore.client.core.HudDrag.Rect r = entry.getValue();
            if (mouseX < r.x() || mouseX >= r.x() + r.width() || mouseY < r.y() || mouseY >= r.y() + r.height()) {
                continue;
            }
            final var config = ExileCoreClient.config;
            if (config.panelsCollapsed) {
                config.panelsCollapsed = false;
            } else if (!config.collapsedPanels.remove(entry.getKey())) {
                config.collapsedPanels.add(entry.getKey());
            }
            ExileCoreClient.saveConfig();
            final var mc = net.minecraft.client.Minecraft.getInstance();
            mc.getSoundManager().play(net.minecraft.client.resources.sounds.SimpleSoundInstance.forUI(
                    net.minecraft.sounds.SoundEvents.UI_BUTTON_CLICK, 1.0f));
            return true;
        }
        return false;
    }

    /** 面板 id 在 HudDrag 的代號。 */
    static String dragId(final String panel) {
        return "panel." + panel;
    }

    /** 這個面板這一幀畫在哪（沒拖出來時在面板群裡）；沒畫 null。 */
    static tw.exilecore.client.core.HudDrag.Rect bounds(final String panel) {
        return LAST.get(panel);
    }

    /** 拖出來的面板從哪裡算偏移：面板群（沒拖過）的左上角。 */
    static tw.exilecore.client.core.HudDrag.Rect home(final int guiWidth, final int guiHeight) {
        final HudLayout.Box box = HudLayout.panels(guiWidth, guiHeight);
        return new tw.exilecore.client.core.HudDrag.Rect(box.x(), box.y(), box.w(), Panels.HEADER);
    }

    /**
     * 2026-11-02 照站長：面板群（整群拖：{@link HudDragBinding#PANELS}）照舊由上往下排；被拖出來的面板（{@code panel.<id>}，
     * 拖動畫面裡一個一個拖）不排在群裡，各自畫在拖到的位置（離開群以後位置不會因為上面的面板出現、消失跟著跑）。
     * 拖動畫面打開時沒有內容的面板也畫一個示意（{@link Panels#placeholders}），才拖得到。這次之前整群一起拖。
     */
    static void extract(final GuiGraphicsExtractor g, final Font font, final HudData data, final long elapsed) {
        LAST.clear();
        HEADERS.clear();
        List<Panels.Panel> panels = Panels.merge(Panels.withAuras(server, auras), data.sides(), elapsed);
        if (net.minecraft.client.Minecraft.getInstance().gui.screen() instanceof HudEditScreen) {
            panels = Panels.placeholders(panels);
        }
        final var hidden = ExileCoreClient.config.hiddenPanels;
        if (hidden != null && !hidden.isEmpty()) {
            // 2026-11-02 照站長：設定裡關掉的面板整個不畫（拖動畫面也不畫）
            panels = panels.stream().filter(panel -> !hidden.contains(panel.id())).toList();
        }
        if (panels.isEmpty()) {
            return;
        }
        final List<Panels.Panel> grouped = new java.util.ArrayList<>();
        final List<Panels.Panel> loose = new java.util.ArrayList<>();
        for (final Panels.Panel panel : panels) {
            (tw.exilecore.client.core.HudDrag.moved(dragId(panel.id())) ? loose : grouped).add(panel);
        }
        final var config = ExileCoreClient.config;
        final HudLayout.Box box = area(g.guiWidth(), g.guiHeight());
        final int x = box.x() - config.panelsX;
        final int width = box.w();
        final int[] group = tw.exilecore.client.core.HudDrag.offset(HudDragBinding.PANELS);
        HudDragBinding.begin(g, HudDragBinding.PANELS);
        final List<Panels.Placed> placed = Panels.layout(grouped, box.y() + config.panelsY, box.bottom(), config.panelsCollapsed,
                new HashSet<>(config.collapsedPanels));
        for (final Panels.Placed item : placed) {
            draw(g, font, item, x, width);
            LAST.put(item.panel().id(), new tw.exilecore.client.core.HudDrag.Rect(x + group[0], item.y() + group[1], width, item.height()));
            HEADERS.put(item.panel().id(), new tw.exilecore.client.core.HudDrag.Rect(x + group[0], item.y() + group[1], width, Panels.HEADER));
        }
        HudDragBinding.end(g);
        for (final Panels.Panel panel : loose) {
            final String id = dragId(panel.id());
            final int[] offset = tw.exilecore.client.core.HudDrag.offset(id);
            final tw.exilecore.client.core.HudDrag.Rect home = home(g.guiWidth(), g.guiHeight());
            final int px = home.x() + offset[0];
            final int py = home.y() + offset[1];
            final List<Panels.Placed> one = Panels.layout(List.of(panel), py, Math.max(py + Panels.HEADER, g.guiHeight() - 2),
                    config.panelsCollapsed, new HashSet<>(config.collapsedPanels));
            for (final Panels.Placed item : one) {
                draw(g, font, item, px, home.width());
                LAST.put(panel.id(), new tw.exilecore.client.core.HudDrag.Rect(px - offset[0], py - offset[1], home.width(), item.height()));
                HEADERS.put(panel.id(), new tw.exilecore.client.core.HudDrag.Rect(px, item.y(), home.width(), Panels.HEADER));
            }
        }
    }

    private static void draw(final GuiGraphicsExtractor g, final Font font, final Panels.Placed item, final int x, final int width) {
        final int y = item.y();
        g.fill(x, y, x + width, y + Panels.HEADER, 0xD0181410);
        g.fill(x, y + Panels.HEADER - 1, x + width, y + Panels.HEADER, 0x60C8A060);
        final String mark = item.collapsed() ? (item.hidden() > 0 ? "▸ " + item.hidden() : "▸") : "▾";
        final int markWidth = font.width(mark);
        final MutableComponent title = Parts.fit(font, item.panel().title(), width - 6 - markWidth);
        g.text(font, title, x + 3, y + 2, 0xFFFFFFFF, true);
        g.text(font, mark, x + width - 2 - markWidth, y + 2, 0xFF8A8A8A, true);
        if (item.height() <= Panels.HEADER) {
            return;
        }
        g.fill(x, y + Panels.HEADER, x + width, y + item.height(), 0x90000000);
        int line = y + Panels.HEADER + Panels.PAD;
        for (int i = 0; i < item.lines(); i++, line += Panels.LINE) {
            g.text(font, Parts.fit(font, item.panel().lines().get(i), width - 6), x + 3, line, 0xFFFFFFFF, true);
        }
        if (item.hidden() > 0) {
            g.text(font, "…+" + item.hidden(), x + 3, line, 0xFF8A8A8A, true);
        }
    }
}
