package tw.exilecore.client;

import com.google.gson.JsonObject;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.input.KeyEvent;
import net.minecraft.client.input.MouseButtonEvent;
import net.minecraft.network.chat.Component;
import org.lwjgl.glfw.GLFW;
import tw.exilecore.client.core.AtlasModel;
import tw.exilecore.client.core.AtlasPainter;
import tw.exilecore.client.core.Camera;
import tw.exilecore.client.core.HexGrid;
import tw.exilecore.client.core.TreePainter;
import tw.exilecore.client.core.UiPanels;
import tw.exilecore.client.core.UiWire;

import java.util.ArrayList;
import java.util.List;

/**
 * 2026-11-02 照站長（D 組）：星圖的原生畫面：真正的六角格（這次之前的箱子選單用「奇數列往右半格」模擬，一次 45 格）。
 * 滾輪縮放、拖曳移動；滑鼠移到格子上看說明（跟箱子選單同一份）；左鍵挑背包、倉庫的地圖開這一格（挑地圖照舊是箱子選單），
 * Shift＋左鍵只指定下次開這種地圖用這一格，右鍵取消指定。塔、城塞照舊從地圖裝置開。
 */
final class AtlasScreen extends Screen {

    private AtlasModel model;
    private final Camera camera = new Camera();
    private boolean placed;
    private String message = "";
    private long messageAt;
    private double pressX;
    private double pressY;
    private int pressButton = -1;
    private boolean dragged;

    AtlasScreen(final AtlasModel model) {
        super(Component.literal("星圖"));
        this.model = model;
        if (!model.message.isEmpty()) {
            showMessage(model.message);
        }
    }

    @Override
    protected void init() {
        camera.viewport(0, 0, width, height);
        camera.minScale = 3;
        camera.maxScale = 48;
        if (!placed) {
            placed = true;
            camera.center(HexGrid.x(model.focus[0], model.focus[1]), HexGrid.y(model.focus[0], model.focus[1]));
            camera.scale = 16;
        }
        final List<Button> buttons = new ArrayList<>();
        buttons.add(Button.builder(Component.literal("全貌"), b -> camera.fit(model.minX - 2, model.minY - 2, model.maxX + 2, model.maxY + 2,
                width, height - 50, 10)).build());
        buttons.add(Button.builder(Component.literal("營地"), b -> camera.center(0, 0)).build());
        buttons.add(Button.builder(Component.literal("上次的地圖"), b -> camera.center(HexGrid.x(model.focus[0], model.focus[1]),
                HexGrid.y(model.focus[0], model.focus[1]))).build());
        buttons.add(Button.builder(Component.literal("星圖天賦"), b -> UiNet.requestOpen("atlastree")).build());
        buttons.add(Button.builder(Component.literal("箱子選單"), b -> UiNet.send(UiWire.action("atlas", "chest"))).build());
        int x = width - 8;
        for (int i = buttons.size() - 1; i >= 0; i--) {
            final Button button = buttons.get(i);
            final int w = Math.max(36, font.width(button.getMessage()) + 12);
            x -= w;
            button.setX(x);
            button.setY(8);
            button.setWidth(w);
            button.setHeight(18);
            addRenderableWidget(button);
            x -= 4;
        }
    }

    void update(final AtlasModel fresh) {
        model = fresh;
        if (!fresh.message.isEmpty()) {
            showMessage(fresh.message);
        }
    }

    private void showMessage(final String text) {
        message = text;
        messageAt = System.currentTimeMillis();
    }

    private AtlasModel.Cell hovered(final double mx, final double my) {
        for (final var child : children()) {
            if (child.isMouseOver(mx, my)) {
                return null;
            }
        }
        return AtlasPainter.at(model, camera.toWorldX(mx), camera.toWorldY(my));
    }

    @Override
    public void extractBackground(final GuiGraphicsExtractor g, final int mouseX, final int mouseY, final float delta) {
        g.fill(0, 0, width, height, TreePainter.BACKGROUND);
    }

    @Override
    public void extractRenderState(final GuiGraphicsExtractor g, final int mouseX, final int mouseY, final float delta) {
        camera.viewport(0, 0, width, height);
        final GuiCanvas canvas = new GuiCanvas(g, font);
        final AtlasModel.Cell hover = hovered(mouseX, mouseY);
        AtlasPainter.paint(canvas, model, camera, hover, 0, 0, width, height);
        final List<String> info = new ArrayList<>(model.info);
        if (hover != null) {
            info.add("<dark_gray>滑鼠：" + HexGrid.where(hover.q(), hover.r()));
        }
        if (model.cut) {
            info.add("<dark_gray>格子太多，只畫了離上次打完的地圖近的");
        }
        UiPanels.panel(canvas, "<#f5b942>星圖", info, 8, 8, Math.min(300, width / 3));
        UiPanels.hint(canvas, "<gray>滾輪縮放・拖曳移動・<yellow>左鍵<gray>挑地圖開這一格・<yellow>Shift＋左鍵<gray>只指定・<yellow>右鍵<gray>取消指定",
                width, height);
        final long age = System.currentTimeMillis() - messageAt;
        if (age < 5000) {
            UiPanels.toast(canvas, message, width, height, age < 4000 ? 1 : (5000 - age) / 1000.0);
        }
        super.extractRenderState(g, mouseX, mouseY, delta);
        if (hover != null) {
            UiPanels.tooltip(canvas, AtlasPainter.tooltip(hover), mouseX, mouseY, width, height);
        }
    }

    @Override
    public boolean mouseClicked(final MouseButtonEvent event, final boolean doubleClick) {
        if (super.mouseClicked(event, doubleClick)) {
            return true;
        }
        pressX = event.x();
        pressY = event.y();
        pressButton = event.button();
        dragged = false;
        return true;
    }

    @Override
    public boolean mouseDragged(final MouseButtonEvent event, final double dx, final double dy) {
        if (pressButton < 0) {
            return super.mouseDragged(event, dx, dy);
        }
        if (!dragged && Math.hypot(event.x() - pressX, event.y() - pressY) > 3) {
            dragged = true;
        }
        if (dragged) {
            camera.pan(dx, dy);
            camera.keepInside(model.minX - 6, model.minY - 6, model.maxX + 6, model.maxY + 6);
        }
        return true;
    }

    @Override
    public boolean mouseReleased(final MouseButtonEvent event) {
        if (pressButton < 0 || event.button() != pressButton) {
            return super.mouseReleased(event);
        }
        final boolean click = !dragged;
        pressButton = -1;
        dragged = false;
        if (!click) {
            return true;
        }
        final AtlasModel.Cell cell = hovered(event.x(), event.y());
        if (cell == null || cell.fog() || "camp".equals(cell.kind())) {
            return true;
        }
        final String mode = event.button() == GLFW.GLFW_MOUSE_BUTTON_RIGHT ? "clear" : event.hasShiftDown() ? "mark" : "open";
        if (event.button() != GLFW.GLFW_MOUSE_BUTTON_LEFT && event.button() != GLFW.GLFW_MOUSE_BUTTON_RIGHT) {
            return true;
        }
        final JsonObject extra = new JsonObject();
        extra.addProperty("q", cell.q());
        extra.addProperty("r", cell.r());
        extra.addProperty("mode", mode);
        UiNet.send(UiWire.action("atlas", "pick", extra));
        return true;
    }

    @Override
    public boolean mouseScrolled(final double mouseX, final double mouseY, final double scrollX, final double scrollY) {
        if (scrollY != 0) {
            camera.zoomAt(mouseX, mouseY, Math.pow(1.18, scrollY));
        }
        return true;
    }

    @Override
    public boolean keyPressed(final KeyEvent event) {
        if (UiNet.closeKey(this, event) || UiNet.openAtlas.matches(event)) {
            onClose();
            return true;
        }
        return super.keyPressed(event);
    }

    @Override
    public boolean isPauseScreen() {
        return false;
    }

    @Override
    public void removed() {
        UiNet.send(UiWire.action("atlas", "close"));
        super.removed();
    }
}
