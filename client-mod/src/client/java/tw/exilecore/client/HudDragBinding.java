package tw.exilecore.client;

import net.minecraft.client.gui.GuiGraphicsExtractor;
import tw.exilecore.client.core.HudDrag;
import tw.exilecore.client.core.HudLayout;
import tw.exilecore.client.core.ModConfig;
import tw.exilecore.client.core.OrbLayout;

/**
 * 2026-11-02 照站長（C 組）：把現有的 HUD 元件註冊到可拖動的機制（{@link HudDrag}），以及畫的時候套用拖過的偏移。
 * <p>
 * 用法（各 renderer）：開機時 {@code HudDrag.register("代號", "顯示的名字", (w, h) -> 這一幀原本畫在哪)}；
 * 畫的時候 {@code HudDragBinding.begin(g, "代號"); …原本的畫法…; HudDragBinding.end(g);}——整個元件平移拖過的偏移，原本的位置算法不用改。
 */
final class HudDragBinding {

    private HudDragBinding() {
    }

    static final String SKILL_BAR = "skill_bar";
    static final String BUFFS = "buffs";
    static final String TARGET = "target";
    static final String ORBS = "orbs";
    // 2026-11-02（C 組合併）：A 組的提示列、側邊面板、異常與守護、戰鬥資源、經驗字，B 組的地圖資訊框
    static final String NOTICES = "notices";
    static final String PANELS = "panels";
    static final String STATUS = "status";
    static final String RESOURCES = "resources";
    static final String XP = "xp";
    static final String MAP_PANEL = "map_panel";
    /** 2026-11-02 照站長：左下角光環那一行（這次之前跟著增益列一起拖）；側邊面板一個一個拖見 PanelRenderer.dragId。 */
    static final String AURAS = "auras";

    /** 開始畫一個元件：套用它拖過的偏移（一定要配一個 {@link #end}）。 */
    static void begin(final GuiGraphicsExtractor g, final String id) {
        final int[] offset = HudDrag.offset(id);
        g.pose().pushMatrix();
        g.pose().translate(offset[0], offset[1]);
    }

    static void end(final GuiGraphicsExtractor g) {
        g.pose().popMatrix();
    }

    /** 註冊現有的元件（技能列、增益列、目標框、生命球）與 C 組的（預警、機制清單、小地圖）。 */
    static void registerDefaults() {
        HudDrag.register(SKILL_BAR, "技能列", HudDragBinding::skillBar);
        HudDrag.register(BUFFS, "增益列", HudDragBinding::buffs);
        HudDrag.register(TARGET, "目標框", HudDragBinding::target);
        HudDrag.register(ORBS, "生命球、魔力球", HudDragBinding::orbs);
        HudDrag.register(AlertRenderer.TOP_ID, "Boss 預警", AlertRenderer::topBounds);
        HudDrag.register(AlertRenderer.SIDE_ID, "機制清單", AlertRenderer::sideBounds);
        HudDrag.register(MinimapRenderer.ID, "小地圖", MinimapRenderer::bounds);
        // A 組（區塊照 core/HudLayout 算）
        HudDrag.register(NOTICES, "提示列", (w, h) -> box(HudLayout.notices(w, h, HudLayout.noticeLines(h))));
        HudDrag.register(PANELS, "側邊面板", (w, h) -> box(PanelRenderer.area(w, h)));
        HudDrag.register(STATUS, "異常、守護", (w, h) -> orbsOn() ? box(HudLayout.statusIcons(w, h, OrbRenderer.size(), 6)) : null);
        HudDrag.register(RESOURCES, "戰鬥資源", (w, h) -> orbsOn() ? box(HudLayout.resources(w, h, OrbRenderer.size())) : null);
        HudDrag.register(XP, "經驗字", (w, h) -> orbsOn() ? new HudDrag.Rect(w / 2 - 91, h - 30, 182, 6) : null);
        // B 組
        HudDrag.register(MAP_PANEL, "地圖資訊框", MapPanelRenderer::bounds);
        // 2026-11-02 照站長：畫面上所有 HUD 元件都能拖——光環那一行、側邊面板一個一個（召喚物、撿到東西…；拖出來就離開面板群）
        HudDrag.register(AURAS, "光環", (w, h) -> orbsOn() ? box(HudLayout.auraStrip(w, h, OrbRenderer.size())) : null);
        for (final java.util.Map.Entry<String, String> panel : tw.exilecore.client.core.Panels.LABELS.entrySet()) {
            final String id = panel.getKey();
            HudDrag.register(PanelRenderer.dragId(id), panel.getValue(), (w, h) -> PanelRenderer.bounds(id), PanelRenderer::home);
        }
    }

    private static boolean orbsOn() {
        return ExileCoreClient.config.orbs && ExileCoreClient.config.replaceHearts;
    }

    private static HudDrag.Rect box(final HudLayout.Box box) {
        return box == null || box.w() <= 0 || box.h() <= 0 ? null : new HudDrag.Rect(box.x(), box.y(), box.w(), box.h());
    }

    private static int slotCount() {
        return ExileCoreClient.hud == null || ExileCoreClient.hud.slots().isEmpty() ? tw.exilecore.client.core.Protocol.SLOTS
                : ExileCoreClient.hud.slots().size();
    }

    /** 跟 HudRenderer.skillBar 一樣的算法。 */
    private static HudDrag.Rect skillBar(final int w, final int h) {
        final ModConfig config = ExileCoreClient.config;
        final int count = slotCount();
        final int width = Math.round((count * 20 - 2) * config.scale);
        final int height = Math.round((18 + 5) * config.scale);
        if (ExileCoreClient.config.orbs && ExileCoreClient.config.replaceHearts) {
            return new HudDrag.Rect(w / 2 - width / 2, h - HudRenderer.DOCK_BOTTOM - Math.round(18 * config.scale), width, Math.round(18 * config.scale));
        }
        int x0 = w / 2 + config.skillBarX;
        int y0 = h + config.skillBarY;
        if (config.skillBarX > 0 && x0 + width > w - 2) {
            x0 = w / 2 - width / 2;
            y0 = h - 60 - Math.round(18 * config.scale);
        }
        return new HudDrag.Rect(x0, y0, width, height);
    }

    /**
     * 增益列：這一幀實際畫的範圍；沒有增益（或還沒畫過）時是預設的範圍（聊天框上面，HudLayout.buffArea）。
     * 2026-11-02 照站長：這次之前是「左球外側兩排」的大約範圍，跟實際畫的地方對不上（框住的不是增益）。
     */
    private static HudDrag.Rect buffs(final int w, final int h) {
        final HudLayout.Box drawn = HudRenderer.buffBox;
        return box(drawn != null ? drawn : HudRenderer.defaultBuffArea(w, h));
    }

    private static HudDrag.Rect target(final int w, final int h) {
        final ModConfig config = ExileCoreClient.config;
        final int width = Math.round(HudLayout.TARGET_WIDTH * config.scale);
        return new HudDrag.Rect(w / 2 + config.targetX - width / 2, config.targetY - Math.round(2 * config.scale), width, Math.round(32 * config.scale));
    }

    private static HudDrag.Rect orbs(final int w, final int h) {
        final int size = OrbRenderer.size();
        final int left = OrbLayout.leftX(w, size);
        return new HudDrag.Rect(left, OrbLayout.top(h, size), OrbLayout.rightX(w) + size - left, size);
    }
}
