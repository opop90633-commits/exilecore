package tw.exilecore.client;

import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.input.MouseButtonEvent;
import net.minecraft.network.chat.Component;
import tw.exilecore.client.core.HudDrag;

/**
 * 2026-11-02 照站長（C 組）：拖動 HUD 元件的編輯模式。每個註冊過的元件（{@link HudDrag#elements}）畫一個框和名字，
 * 左鍵拖動（吸附 4 格，按著 Shift 不吸）、右鍵回到原本的位置；「全部重設」、「完成」（存檔）。
 * 這次之前位置只能改 json 檔（而且有球時技能列的位置是固定的）。
 */
final class HudEditScreen extends Screen {

    static final int SNAP = 4;

    private final Screen parent;
    private String dragging;
    private double grabX;
    private double grabY;
    private int startDx;
    private int startDy;

    HudEditScreen(final Screen parent) {
        super(Component.literal("拖動 HUD 位置"));
        this.parent = parent;
    }

    @Override
    protected void init() {
        addRenderableWidget(Button.builder(Component.literal("全部重設"), b -> {
            HudDrag.resetAll();
            ExileCoreClient.saveConfig();
        }).bounds(this.width / 2 - 102, this.height / 2 + 30, 100, 20).build());
        addRenderableWidget(Button.builder(Component.literal("完成"), b -> onClose()).bounds(this.width / 2 + 2, this.height / 2 + 30, 100, 20).build());
    }

    @Override
    public void extractBackground(final GuiGraphicsExtractor g, final int mouseX, final int mouseY, final float delta) {
        // 不畫暗色背景：要看得到 HUD
    }

    @Override
    public void extractRenderState(final GuiGraphicsExtractor g, final int mouseX, final int mouseY, final float delta) {
        for (final HudDrag.Element element : HudDrag.elements()) {
            final HudDrag.Rect rect = HudDrag.bounds(element.id(), this.width, this.height);
            if (rect == null) {
                continue;
            }
            final boolean hot = element.id().equals(dragging) || dragging == null && rect.contains(mouseX, mouseY);
            g.fill(rect.x(), rect.y(), rect.right(), rect.bottom(), hot ? 0x6040A0FF : 0x30FFFFFF);
            g.outline(rect.x(), rect.y(), rect.width(), rect.height(), hot ? 0xFF60C0FF : 0xC0FFFFFF);
            final int[] offset = HudDrag.offset(element.id());
            final String label = element.label() + (offset[0] != 0 || offset[1] != 0 ? " *" : "");
            g.pose().pushMatrix();
            g.pose().translate(rect.x() + 2, rect.y() + 2);
            g.pose().scale(0.6f, 0.6f);
            g.text(this.font, label, 0, 0, 0xFFFFFFFF, true);
            g.pose().popMatrix();
        }
        g.centeredText(this.font, Component.literal("左鍵拖動、右鍵讓這一個回到原位（按著 Shift 不吸附）"), this.width / 2, this.height / 2 + 16, 0xFFE0E0E0);
        // 2026-11-02 照站長：滑鼠指著的元件在畫面中間寫出完整的名字（框太小時框裡的字看不清楚）
        final HudDrag.Element hover = dragging != null ? HudDrag.element(dragging) : at(mouseX, mouseY);
        if (hover != null) {
            final String text = hover.label() + (HudDrag.moved(hover.id()) ? "（拖過，右鍵回到原位）" : "");
            g.centeredText(this.font, Component.literal(text), this.width / 2, this.height / 2 + 4, 0xFF60C0FF);
        }
        super.extractRenderState(g, mouseX, mouseY, delta);
    }

    private HudDrag.Element at(final double x, final double y) {
        HudDrag.Element found = null;
        for (final HudDrag.Element element : HudDrag.elements()) {
            final HudDrag.Rect rect = HudDrag.bounds(element.id(), this.width, this.height);
            if (rect != null && rect.contains(x, y)) {
                found = element; // 後註冊的疊在上面
            }
        }
        return found;
    }

    @Override
    public boolean mouseClicked(final MouseButtonEvent event, final boolean doubleClick) {
        if (super.mouseClicked(event, doubleClick)) {
            return true;
        }
        final HudDrag.Element element = at(event.x(), event.y());
        if (element == null) {
            return false;
        }
        if (event.button() == 1) {
            HudDrag.reset(element.id());
            ExileCoreClient.saveConfig();
            return true;
        }
        dragging = element.id();
        grabX = event.x();
        grabY = event.y();
        // 2026-11-02：側邊面板拖出來時偏移改從面板群的左上角算（HudDrag.dragStart），一開始拖不會跳
        final int[] offset = HudDrag.dragStart(element.id(), this.width, this.height);
        startDx = offset[0];
        startDy = offset[1];
        return true;
    }

    @Override
    public boolean mouseDragged(final MouseButtonEvent event, final double dx, final double dy) {
        if (dragging == null) {
            return super.mouseDragged(event, dx, dy);
        }
        final HudDrag.Element element = HudDrag.element(dragging);
        final HudDrag.Rect base = element == null ? null : HudDrag.base(element.id(), this.width, this.height);
        if (base == null) {
            return true;
        }
        final boolean free = this.minecraft.hasShiftDown();
        int nx = startDx + (int) Math.round(event.x() - grabX);
        int ny = startDy + (int) Math.round(event.y() - grabY);
        if (!free) {
            nx = HudDrag.snap(nx, SNAP);
            ny = HudDrag.snap(ny, SNAP);
        }
        final int[] clamped = HudDrag.clampOffset(base, nx, ny, this.width, this.height);
        HudDrag.setOffset(dragging, clamped[0], clamped[1]);
        return true;
    }

    @Override
    public boolean mouseReleased(final MouseButtonEvent event) {
        if (dragging != null) {
            dragging = null;
            ExileCoreClient.saveConfig();
            return true;
        }
        return super.mouseReleased(event);
    }

    @Override
    public void onClose() {
        ExileCoreClient.saveConfig();
        this.minecraft.gui.setScreen(parent);
    }

    @Override
    public boolean isPauseScreen() {
        return false;
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
