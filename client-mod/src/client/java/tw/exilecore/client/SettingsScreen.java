package tw.exilecore.client;

import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.components.AbstractSliderButton;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.CycleButton;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;
import tw.exilecore.client.core.ModConfig;

import java.util.List;
import java.util.function.Consumer;
import java.util.function.Function;

/**
 * 2026-11-02 照站長（C 組）：模組的設定畫面（預設 O）：各功能的開關、HUD 縮放、小地圖大小、拖動 HUD 位置（{@link HudEditScreen}）、
 * 重設預設值。這次之前只能改 config/exilecore_client.json。改了馬上存檔；影響伺服器的開關（HUD、生命球、特效、預警、小地圖、死亡回顧、說明欄）重新打招呼。
 */
final class SettingsScreen extends Screen {

    private final Screen parent;

    /** 一個開關：名字、讀、寫。 */
    private record Toggle(String label, Function<ModConfig, Boolean> get, java.util.function.BiConsumer<ModConfig, Boolean> set) {
    }

    private static final List<Toggle> TOGGLES = List.of(
            new Toggle("星骸 HUD", c -> c.hudEnabled, (c, v) -> c.hudEnabled = v),
            new Toggle("技能列", c -> c.showSkillBar, (c, v) -> c.showSkillBar = v),
            new Toggle("增益列", c -> c.showBuffs, (c, v) -> c.showBuffs = v),
            new Toggle("目標框", c -> c.showTarget, (c, v) -> c.showTarget = v),
            new Toggle("取代原版愛心", c -> c.replaceHearts, (c, v) -> c.replaceHearts = v),
            new Toggle("生命球、魔力球", c -> c.orbs, (c, v) -> c.orbs = v),
            new Toggle("球裡的字粗體", c -> c.orbBold, (c, v) -> c.orbBold = v), // 2026-11-02 照站長
            new Toggle("技能格寫按鍵", c -> c.showKeyLabels, (c, v) -> c.showKeyLabels = v),
            new Toggle("特效改由模組繪製", c -> c.drawEffects, (c, v) -> c.drawEffects = v),
            new Toggle("提示列", c -> c.showNotices, (c, v) -> c.showNotices = v),
            new Toggle("側邊面板", c -> c.showPanels, (c, v) -> c.showPanels = v),
            new Toggle("戰鬥資源", c -> c.showResources, (c, v) -> c.showResources = v),
            new Toggle("異常、守護", c -> c.showStatus, (c, v) -> c.showStatus = v),
            new Toggle("經驗條的字", c -> c.showXp, (c, v) -> c.showXp = v),
            new Toggle("光環、召喚物、撿到的", c -> c.showSides, (c, v) -> c.showSides = v),
            new Toggle("地圖資訊框", c -> c.mapPanel, (c, v) -> c.mapPanel = v),
            new Toggle("傷害數字", c -> c.damageNumbers, (c, v) -> c.damageNumbers = v),
            new Toggle("怪物頭上血條", c -> c.mobBars, (c, v) -> c.mobBars = v),
            new Toggle("Boss 預警", c -> c.showAlerts, (c, v) -> c.showAlerts = v),
            new Toggle("小地圖", c -> c.showMinimap, (c, v) -> c.showMinimap = v),
            new Toggle("地圖顯示沒走過的房間", c -> c.mapShowAll, (c, v) -> c.mapShowAll = v), // 2026-11-02 照站長
            new Toggle("地圖標示怪物", c -> c.mapMonsters, (c, v) -> c.mapMonsters = v),
            new Toggle("死亡回顧面板", c -> c.showDeathPanel, (c, v) -> c.showDeathPanel = v),
            new Toggle("精簡的裝備說明", c -> c.compactTooltips, (c, v) -> c.compactTooltips = v),
            new Toggle("自動更新", c -> c.autoUpdate, (c, v) -> c.autoUpdate = v),
            // 2026-11-02 照站長：側邊面板一個一個關（關掉的整個不畫）；這次之前只能點標題收起
            panel("quest", "面板：任務"),
            panel("guide", "面板：新手引導"),
            panel("combat", "面板：戰鬥數據"),
            panel("status", "面板：狀態"),
            panel("minions", "面板：召喚物"),
            panel("drops", "面板：撿到東西"));

    private static Toggle panel(final String id, final String label) {
        return new Toggle(label, c -> c.hiddenPanels == null || !c.hiddenPanels.contains(id), (c, v) -> {
            if (c.hiddenPanels == null) {
                c.hiddenPanels = new java.util.ArrayList<>();
            }
            c.hiddenPanels.remove(id);
            if (!v) {
                c.hiddenPanels.add(id);
            }
        });
    }

    SettingsScreen(final Screen parent) {
        super(Component.literal("星骸輔助模組設定"));
        this.parent = parent;
    }

    @Override
    protected void init() {
        final ModConfig config = ExileCoreClient.config;
        // 畫面夠寬排三欄（GUI 縮放大的時候也放得下），不然兩欄
        final int columns = this.width >= 3 * 142 + 16 ? 3 : 2;
        final int columnWidth = columns == 3 ? 140 : 150;
        final int total = columns * columnWidth + (columns - 1) * 4;
        final int start = this.width / 2 - total / 2;
        int y = 26;
        for (int i = 0; i < TOGGLES.size(); i++) {
            final Toggle toggle = TOGGLES.get(i);
            final int x = start + (i % columns) * (columnWidth + 4);
            addRenderableWidget(CycleButton.onOffBuilder(toggle.get().apply(config)).create(x, y, columnWidth, 20, Component.literal(toggle.label()),
                    (button, value) -> {
                        toggle.set().accept(ExileCoreClient.config, value);
                        changed();
                    }));
            if (i % columns == columns - 1) {
                y += 21;
            }
        }
        if (TOGGLES.size() % columns != 0) {
            y += 21;
        }
        final int left = this.width / 2 - 154;
        final int right = this.width / 2 + 4;
        y += 3;
        addRenderableWidget(new Slider(left, y, 150, "HUD 縮放", 0.5, 3.0, 0.1, config.scale, v -> {
            ExileCoreClient.config.scale = v.floatValue();
            ExileCoreClient.saveConfig();
        }, v -> String.format(java.util.Locale.ROOT, "%.1f×", v)));
        addRenderableWidget(new Slider(right, y, 150, "小地圖大小", 40, 160, 4, config.minimapSize, v -> {
            ExileCoreClient.config.minimapSize = (int) Math.round(v);
            ExileCoreClient.saveConfig();
        }, v -> Math.round(v) + ""));
        y += 24;
        // 2026-11-02 照站長：大地圖不透明度、球的大小一排；拖動、重設、完成三個並排（開關多了，GUI 縮放 3 也放得下）
        addRenderableWidget(new Slider(left, y, 150, "大地圖不透明度", 20, 100, 5, config.bigMapOpacity, v -> {
            ExileCoreClient.config.bigMapOpacity = (int) Math.round(v);
            ExileCoreClient.saveConfig();
        }, v -> Math.round(v) + "%"));
        addRenderableWidget(new Slider(right, y, 150, "血魔球大小", 0.8, 1.9, 0.1, config.orbScale, v -> {
            ExileCoreClient.config.orbScale = v.floatValue();
            ExileCoreClient.saveConfig();
        }, v -> String.format(java.util.Locale.ROOT, "%.1f×", v)));
        y += 24;
        final int buttonY = Math.min(y, this.height - 24);
        addRenderableWidget(Button.builder(Component.literal("拖動 HUD 位置…"), b -> this.minecraft.gui.setScreen(new HudEditScreen(this)))
                .bounds(left, buttonY, 100, 20).build());
        addRenderableWidget(Button.builder(Component.literal("重設預設值"), b -> {
            ExileCoreClient.config.resetDisplay();
            changed();
            rebuildWidgets();
        }).bounds(left + 104, buttonY, 100, 20).build());
        addRenderableWidget(Button.builder(Component.literal("完成"), b -> onClose()).bounds(left + 208, buttonY, 100, 20).build());
    }

    private static void changed() {
        ExileCoreClient.saveConfig();
        ExileCoreClient.resendHello();
    }

    @Override
    public void extractRenderState(final GuiGraphicsExtractor g, final int mouseX, final int mouseY, final float delta) {
        super.extractRenderState(g, mouseX, mouseY, delta);
        g.centeredText(this.font, this.title, this.width / 2, 6, 0xFFF5B942);
        final String keys = "按鍵：" + FeedsClient.settingsKeyName() + " 設定　" + FeedsClient.mapKeyName() + " 大地圖　" + FeedsClient.deathKeyName()
                + " 死亡回顧（在「選項 → 控制 → 按鍵設定 → 星骸」改）";
        g.pose().pushMatrix();
        g.pose().translate(this.width / 2f, 17);
        g.pose().scale(0.6f, 0.6f);
        g.centeredText(this.font, keys, 0, 0, 0xFF909090);
        g.pose().popMatrix();
    }

    @Override
    public void onClose() {
        this.minecraft.gui.setScreen(parent);
    }

    @Override
    public boolean isPauseScreen() {
        return false;
    }

    /** 一個數值滑桿（min～max，每 step 一格）。 */
    private static final class Slider extends AbstractSliderButton {
        private final String label;
        private final double min;
        private final double max;
        private final double step;
        private final Consumer<Double> apply;
        private final Function<Double, String> format;

        Slider(final int x, final int y, final int width, final String label, final double min, final double max, final double step, final double current,
               final Consumer<Double> apply, final Function<Double, String> format) {
            super(x, y, width, 20, Component.empty(), (current - min) / (max - min));
            this.label = label;
            this.min = min;
            this.max = max;
            this.step = step;
            this.apply = apply;
            this.format = format;
            updateMessage();
        }

        private double current() {
            final double raw = min + value * (max - min);
            return Math.max(min, Math.min(max, Math.round(raw / step) * step));
        }

        @Override
        protected void updateMessage() {
            setMessage(Component.literal(label + "：" + format.apply(current())));
        }

        @Override
        protected void applyValue() {
            apply.accept(current());
        }
    }

    @Override
    public boolean keyPressed(final net.minecraft.client.input.KeyEvent event) {
        if (UiNet.closeKey(this, event)) {
            onClose(); // 2026-11-02 照站長：按 E 也能離開
            return true;
        }
        return super.keyPressed(event);
    }
}
