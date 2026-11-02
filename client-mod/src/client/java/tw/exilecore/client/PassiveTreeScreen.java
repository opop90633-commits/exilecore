package tw.exilecore.client;

import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.input.KeyEvent;
import net.minecraft.client.input.MouseButtonEvent;
import net.minecraft.network.chat.Component;
import org.lwjgl.glfw.GLFW;
import tw.exilecore.client.core.Camera;
import tw.exilecore.client.core.TreeGuard;
import tw.exilecore.client.core.TreeModel;
import tw.exilecore.client.core.TreePainter;
import tw.exilecore.client.core.UiPanels;
import tw.exilecore.client.core.UiWire;

import java.util.ArrayList;
import java.util.BitSet;
import java.util.List;

/**
 * 2026-11-02 照站長（D 組）：天賦樹的原生畫面（也拿來畫星圖天賦 atlastree、昇華天賦 asc）。這次之前只有箱子選單（9×5 的視野加捲動按鈕）。
 * <ul>
 *   <li>滾輪縮放（滑鼠指著的地方不動）、按住拖曳移動；「全貌」看整棵樹、「起點」回到職業起點。</li>
 *   <li>滑鼠移到還沒配的節點上就畫出路徑預覽（藍色），說明寫要幾點；左鍵整條配上（PoE2 的做法，不用點兩次）。右鍵退點。</li>
 *   <li>配了的屬性點左鍵換屬性、配了的珠寶孔左鍵放／拿珠寶（選珠寶照舊是箱子選單）。</li>
 *   <li>右上角搜尋：符合的節點畫青色的圈，Enter 跳到下一個（Shift＋Enter 上一個）。</li>
 *   <li>2026-11-02 照站長：退點要花金幣時先跳確認（退哪個、花多少、你有多少），再按一次右鍵或「確認」才退，點別的地方或 Esc 取消（2026-11-02 照站長：這次之前移開滑鼠就取消，確認框的按鈕點不到）；
 *       整條路一次配到 confirmPathPoints（設定檔，預設 5）點以上也先確認。路徑預覽的說明多了「整條路加起來的能力」（伺服器照箱子選單算的）。</li>
 *   <li>配點、退點都送給伺服器，伺服器照原本的規則檢查、做完回最新的狀態；結果寫在下面。</li>
 * </ul>
 */
final class PassiveTreeScreen extends Screen {

    private TreeModel model;
    private final Camera camera = new Camera();
    private final TreePainter.View view = new TreePainter.View();
    private boolean placed;
    private EditBox search;
    private List<Integer> hits = List.of();
    private int hitIndex = -1;
    private String message = "";
    private long messageAt;
    // 拖曳
    private double pressX;
    private double pressY;
    private int pressButton = -1;
    private boolean dragged;
    /** 要再按一次確認的動作（全部退回）。 */
    private String confirm;
    private long confirmAt;
    /** 2026-11-02 照站長：等確認的退點（花金幣）、整條路配點（{@link TreeGuard}）；確認框的位置。 */
    private TreeGuard.Pending pending;
    private double[] confirmRects;
    /** 2026-11-02 照站長：在天賦樹上直接選珠寶：選給哪個孔（節點索引，-1 = 沒開）、清單的位置。這次之前點空孔開箱子選單。 */
    private int pickSocket = -1;
    /** 2026-11-02 照站長：右上的組別鈕（月卡切換天賦組、星圖天賦組）；這次之前要回箱子選單換。 */
    private final List<Button> setButtons = new ArrayList<>();
    private String setsShown = "";
    private double[] pickRects;
    /** 2026-11-02 照站長：「整條路加起來的能力」（路徑代號 → 伺服器回的幾行），最近問過的留 64 條。 */
    private final java.util.Map<String, List<String>> summaries = new java.util.LinkedHashMap<>(16, 0.75f, true) {
        @Override
        protected boolean removeEldestEntry(final java.util.Map.Entry<String, List<String>> eldest) {
            return size() > 64;
        }
    };
    private String summaryKey = "";
    private long summaryKeyAt;
    private final java.util.Set<String> asked = new java.util.HashSet<>();

    PassiveTreeScreen(final TreeModel model) {
        super(Component.literal(title(model.data.kind)));
        this.model = model;
        if (!model.state.message.isEmpty()) {
            showMessage(model.state.message);
        }
    }

    static String title(final String kind) {
        return switch (kind) {
            case "atlastree" -> "星圖天賦";
            case "asc" -> "昇華天賦";
            default -> "天賦樹";
        };
    }

    String kind() {
        return model.data.kind;
    }

    private boolean passive() {
        return "passive".equals(kind());
    }

    @Override
    protected void init() {
        camera.viewport(0, 0, width, height);
        final double unit = model.data.unit;
        camera.maxScale = 45 / unit;
        if (!placed) {
            placed = true;
            final double fit = fitScale();
            camera.minScale = Math.min(fit * 0.7, camera.maxScale);
            if (passive() && model.state.start >= 0) {
                camera.center(model.data.x[model.state.start], model.data.y[model.state.start]);
                camera.scale = Math.max(fit, Math.min(camera.maxScale, 9 / unit));
            } else {
                fitAll();
            }
        } else {
            camera.minScale = Math.min(fitScale() * 0.7, camera.maxScale);
        }
        final int boxWidth = Math.min(160, width / 4);
        search = new EditBox(font, width - boxWidth - 8, 8, boxWidth, 18, Component.literal("搜尋"));
        search.setHint(Component.literal("搜尋（名稱、說明）"));
        search.setMaxLength(40);
        search.setResponder(this::onSearch);
        addRenderableWidget(search);
        if (model.state.search != null && !model.state.search.isEmpty()) {
            search.setValue(model.state.search);
            onSearch(model.state.search);
            jump(1);
        }
        final List<Button> buttons = new ArrayList<>();
        buttons.add(Button.builder(Component.literal("全貌"), b -> fitAll()).build());
        if (model.state.start >= 0) {
            buttons.add(Button.builder(Component.literal("起點"), b -> {
                camera.center(model.data.x[model.state.start], model.data.y[model.state.start]);
            }).build());
        }
        switch (kind()) {
            case "passive" -> buttons.add(Button.builder(Component.literal("昇華"), b -> UiNet.requestOpen("asc")).build());
            case "asc" -> buttons.add(Button.builder(Component.literal("天賦樹"), b -> UiNet.requestOpen("passive")).build());
            case "atlastree" -> {
                buttons.add(Button.builder(Component.literal("星圖"), b -> UiNet.requestOpen("atlas")).build());
                buttons.add(Button.builder(Component.literal("全部退回"), b -> confirmThen("reset", "<yellow>再按一次「全部退回」確認（免費）",
                        () -> UiNet.send(UiWire.action(kind(), "reset")))).build());
            }
            default -> {
            }
        }
        buttons.add(Button.builder(Component.literal("箱子選單"), b -> UiNet.send(UiWire.action(kind(), "chest"))).build());
        int x = width - 8;
        for (int i = buttons.size() - 1; i >= 0; i--) {
            final Button button = buttons.get(i);
            final int w = Math.max(36, font.width(button.getMessage()) + 12);
            x -= w;
            button.setX(x);
            button.setY(30);
            button.setWidth(w);
            button.setHeight(18);
            addRenderableWidget(button);
            x -= 4;
        }
        setsShown = "";
        placeSetButtons();
    }

    /** 組別鈕：在上面那排按鈕下面、靠右，「組 1 2 3…」；用中的那組按不下去，沒有月卡時其他組灰字（按了伺服器會說要月卡）。 */
    private void placeSetButtons() {
        final String key = model.state.setCount + ":" + model.state.activeSet + ":" + model.state.card + ":" + width;
        if (key.equals(setsShown)) {
            return;
        }
        setsShown = key;
        for (final Button button : setButtons) {
            removeWidget(button);
        }
        setButtons.clear();
        if (model.state.setCount <= 1) {
            return;
        }
        int x = width - 8;
        for (int set = model.state.setCount - 1; set >= 0; set--) {
            final int target = set;
            final boolean current = set == model.state.activeSet;
            final boolean allowed = model.state.card || set == 0;
            final Component label = Component.literal(current ? "▶ " + (set + 1) : String.valueOf(set + 1))
                    .withStyle(current ? net.minecraft.ChatFormatting.GOLD : allowed ? net.minecraft.ChatFormatting.WHITE : net.minecraft.ChatFormatting.DARK_GRAY);
            final Button button = Button.builder(label, b -> {
                final com.google.gson.JsonObject extra = new com.google.gson.JsonObject();
                extra.addProperty("set", target);
                UiNet.send(UiWire.action(kind(), "set", extra));
            }).tooltip(net.minecraft.client.gui.components.Tooltip.create(Component.literal(current ? "第 " + (set + 1) + " 組（使用中）"
                    : allowed ? "換成第 " + (set + 1) + " 組" : "第 " + (set + 1) + " 組：要有月卡才能換（沒有月卡可以切回第 1 組）"))).build();
            final int w = current ? 30 : 22;
            x -= w;
            button.setX(x);
            button.setY(52);
            button.setWidth(w);
            button.setHeight(16);
            button.active = !current;
            addRenderableWidget(button);
            setButtons.add(button);
            x -= 3;
        }
        final Button title = Button.builder(Component.literal(kind().equals("atlastree") ? "星圖天賦組" : "天賦組"), b -> { }).build();
        final int w = font.width(title.getMessage()) + 10;
        title.setX(x - w);
        title.setY(52);
        title.setWidth(w);
        title.setHeight(16);
        title.active = false;
        addRenderableWidget(title);
        setButtons.add(title);
    }

    private double fitScale() {
        final double margin = model.data.unit;
        final double spanX = Math.max(1, model.data.maxX - model.data.minX + 2 * margin);
        final double spanY = Math.max(1, model.data.maxY - model.data.minY + 2 * margin);
        return Math.min((width - 20) / spanX, (height - 70) / spanY);
    }

    private void fitAll() {
        final double margin = model.data.unit;
        camera.viewport(0, 0, width, height);
        camera.fit(model.data.minX - margin, model.data.minY - margin, model.data.maxX + margin, model.data.maxY + margin, width, height - 50, 10);
    }

    void update(final TreeModel fresh) {
        this.model = fresh;
        placeSetButtons(); // 換了組、月卡到期：組別鈕跟著換
        // 退掉了、配好了：確認框收起來（每秒的狀態更新不收，玩家還在看）
        if (pending != null && (pending.refund() != fresh.state.owned.get(pending.node()))) {
            pending = null;
        }
        if (search != null) {
            onSearch(search.getValue());
        }
        if (!fresh.state.message.isEmpty()) {
            showMessage(fresh.state.message);
        }
    }

    private void showMessage(final String text) {
        message = text;
        messageAt = System.currentTimeMillis();
    }

    private void onSearch(final String text) {
        hits = model.search(text);
        final BitSet bits = new BitSet();
        hits.forEach(bits::set);
        view.hits = bits;
        hitIndex = -1;
        view.focus = -1;
    }

    private void jump(final int step) {
        if (hits.isEmpty()) {
            return;
        }
        hitIndex = hitIndex < 0 ? (step > 0 ? 0 : hits.size() - 1) : Math.floorMod(hitIndex + step, hits.size());
        final int node = hits.get(hitIndex);
        view.focus = node;
        camera.center(model.data.x[node], model.data.y[node]);
        camera.scale = Math.max(camera.scale, Math.min(camera.maxScale, 14 / model.data.unit));
    }

    private void confirmThen(final String key, final String prompt, final Runnable action) {
        final long now = System.currentTimeMillis();
        if (key.equals(confirm) && now - confirmAt < 4000) {
            confirm = null;
            action.run();
            return;
        }
        confirm = key;
        confirmAt = now;
        showMessage(prompt);
    }

    // ---- 畫 ----

    @Override
    public void extractBackground(final GuiGraphicsExtractor g, final int mouseX, final int mouseY, final float delta) {
        g.fill(0, 0, width, height, TreePainter.BACKGROUND);
    }

    private int hovered(final double mx, final double my) {
        final double wx = camera.toWorldX(mx);
        final double wy = camera.toWorldY(my);
        final int node = model.nearest(wx, wy, Math.max(model.data.unit * 0.55, 7 / camera.scale));
        if (node < 0) {
            return -1;
        }
        final double r = TreePainter.pixels(model.data, node, camera) + 3;
        final double dx = camera.toScreenX(model.data.x[node]) - mx;
        final double dy = camera.toScreenY(model.data.y[node]) - my;
        return dx * dx + dy * dy <= Math.max(r * r, 36) ? node : -1;
    }

    private boolean overWidget(final double mx, final double my) {
        for (final var child : children()) {
            if (child.isMouseOver(mx, my)) {
                return true;
            }
        }
        return false;
    }

    @Override
    public void extractRenderState(final GuiGraphicsExtractor g, final int mouseX, final int mouseY, final float delta) {
        camera.viewport(0, 0, width, height);
        final GuiCanvas canvas = new GuiCanvas(g, font);
        final int hover = overWidget(mouseX, mouseY) ? -1 : hovered(mouseX, mouseY);
        view.hover = hover;
        if (pending != null && !pending.path().isEmpty()) {
            view.setPath(pending.path());
        } else if (hover >= 0 && !model.state.owned.get(hover)) {
            view.setPath(model.path(hover, "asc".equals(kind())));
        } else {
            view.setPath(List.of());
        }
        updateSummary();
        TreePainter.paint(canvas, model, camera, view, 0, 0, width, height);
        final List<String> info = new ArrayList<>();
        if (!model.state.title.isEmpty()) {
            info.add(model.state.title);
        }
        info.addAll(model.state.info);
        if (!view.path.isEmpty()) {
            info.add("<#77aaff>路徑預覽：" + view.path.size() + " 個節點" + ("atlastree".equals(kind()) ? "，共 " + model.cost(view.path) + " 點" : ""));
        }
        if (!hits.isEmpty()) {
            info.add("<#55ffff>搜尋結果 " + (hitIndex < 0 ? "共 " + hits.size() + " 個" : (hitIndex + 1) + "/" + hits.size()) + "<dark_gray>（Enter 下一個）");
        } else if (search != null && !search.getValue().isBlank()) {
            info.add("<red>沒有符合「" + search.getValue().replace("<", "\\<") + "」的節點");
        }
        UiPanels.panel(canvas, "<#f5b942>" + title(kind()) + "　<white>剩 " + model.state.points + " 點", info, 8, 8, Math.min(260, width / 3));
        UiPanels.hint(canvas, "<gray>滾輪縮放・拖曳移動・<yellow>左鍵<gray>配點（遠的整條配上）・<yellow>右鍵<gray>退點・Enter 跳到下一個搜尋結果",
                width, height);
        final long age = System.currentTimeMillis() - messageAt;
        if (age < 5000) {
            UiPanels.toast(canvas, message, width, height, age < 4000 ? 1 : (5000 - age) / 1000.0);
        }
        super.extractRenderState(g, mouseX, mouseY, delta);
        if (pickSocket >= 0) {
            final List<TreeModel.State.Jewel> bag = model.state.bag == null ? List.of() : model.state.bag;
            if (!model.state.owned.get(pickSocket) || model.state.jewels.get(pickSocket)) {
                pickSocket = -1; // 孔被退掉、或已經放好了
                pickRects = null;
            } else {
                final List<String> rows = new ArrayList<>();
                for (final TreeModel.State.Jewel jewel : bag) {
                    rows.add(jewel.problem().isEmpty() ? jewel.name() : "<dark_gray>" + jewel.name());
                }
                final int row = UiPanels.pickerRow(pickRects, mouseX, mouseY);
                pickRects = UiPanels.picker(canvas, "<#c77dff>選一顆珠寶放進「" + model.name(pickSocket) + "<#c77dff>」", rows, row, width, height);
                if (row >= 0 && row < bag.size()) {
                    final TreeModel.State.Jewel jewel = bag.get(row);
                    final List<String> tip = new ArrayList<>();
                    tip.add(jewel.name());
                    tip.addAll(jewel.lines());
                    tip.add("");
                    tip.add(jewel.problem().isEmpty() ? "<yellow>左鍵：放進珠寶孔" : "<red>" + jewel.problem());
                    UiPanels.tooltip(canvas, tip, mouseX, mouseY, width, height);
                }
                return;
            }
        }
        if (pending != null) {
            confirmRects = UiPanels.confirm(canvas, pending.lines(), width, height);
        } else {
            confirmRects = null;
            if (hover >= 0) {
                UiPanels.tooltip(canvas, TreePainter.tooltip(model, hover, view), mouseX, mouseY, width, height);
            }
        }
    }

    private static String key(final List<String> ids) {
        return String.join(",", ids);
    }

    private List<String> ids(final List<Integer> path) {
        final List<String> ids = new ArrayList<>();
        for (final int step : path) {
            ids.add(model.data.ids[step]);
        }
        return ids;
    }

    /** 路徑預覽兩個節點以上：拿快取的「整條路加起來」，沒有的話滑鼠停 0.15 秒後問伺服器（昇華天賦沒有路徑）。 */
    private void updateSummary() {
        if (view.path.size() <= 1 || "asc".equals(kind())) {
            view.summary = List.of();
            return;
        }
        final List<String> ids = ids(view.path);
        final String key = key(ids);
        final List<String> known = summaries.get(key);
        view.summary = known;
        if (known != null) {
            return;
        }
        final long now = System.currentTimeMillis();
        if (!key.equals(summaryKey)) {
            summaryKey = key;
            summaryKeyAt = now;
        } else if (now - summaryKeyAt >= 150 && asked.add(key)) {
            final com.google.gson.JsonObject extra = new com.google.gson.JsonObject();
            final com.google.gson.JsonArray array = new com.google.gson.JsonArray();
            ids.forEach(array::add);
            extra.add("path", array);
            UiNet.send(UiWire.action(kind(), "sum", extra));
        }
    }

    /** 伺服器回的「整條路加起來」。 */
    void onSummary(final List<String> path, final List<String> lines) {
        final String key = key(path);
        summaries.put(key, lines);
        asked.remove(key);
    }

    private void runPending() {
        final TreeGuard.Pending done = pending;
        pending = null;
        if (done.refund()) {
            UiNet.send(UiWire.actionId(kind(), "refund", model.data.ids[done.node()]));
        } else {
            UiNet.send(UiWire.actionPath(kind(), ids(done.path())));
        }
    }

    // ---- 滑鼠、鍵盤 ----

    @Override
    public boolean mouseClicked(final MouseButtonEvent event, final boolean doubleClick) {
        if (super.mouseClicked(event, doubleClick)) {
            return true;
        }
        if (search != null) {
            search.setFocused(false);
            setFocused(null);
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
            final double m = model.data.unit * 4;
            camera.keepInside(model.data.minX - m, model.data.minY - m, model.data.maxX + m, model.data.maxY + m);
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
        if (click && pickSocket >= 0) {
            final int row = UiPanels.pickerRow(pickRects, event.x(), event.y());
            final List<TreeModel.State.Jewel> bag = model.state.bag == null ? List.of() : model.state.bag;
            if (event.button() == GLFW.GLFW_MOUSE_BUTTON_LEFT && row >= 0 && row < bag.size()) {
                final TreeModel.State.Jewel jewel = bag.get(row);
                if (!jewel.problem().isEmpty()) {
                    showMessage("<red>" + jewel.problem());
                    return true;
                }
                final com.google.gson.JsonObject extra = new com.google.gson.JsonObject();
                extra.addProperty("id", model.data.ids[pickSocket]);
                extra.addProperty("from", jewel.key());
                UiNet.send(UiWire.action(kind(), "use", extra));
            } else if (UiPanels.inside(pickRects, 0, event.x(), event.y()) && !UiPanels.inside(pickRects, 1, event.x(), event.y())) {
                return true; // 點在清單框裡的空白：不關
            }
            pickSocket = -1; // 選好了、按「取消」、或點外面：關掉
            pickRects = null;
            return true;
        }
        if (click && pending != null && event.button() == GLFW.GLFW_MOUSE_BUTTON_LEFT) {
            if (UiPanels.inside(confirmRects, 1, event.x(), event.y())) {
                runPending();
                return true;
            }
            if (UiPanels.inside(confirmRects, 2, event.x(), event.y())) {
                pending = null;
                showMessage("<gray>取消了");
                return true;
            }
            if (UiPanels.inside(confirmRects, 0, event.x(), event.y())) {
                return true;
            }
        }
        if (click) {
            clickAt(event.x(), event.y(), event.button(), event.hasShiftDown());
        }
        return true;
    }

    @Override
    public boolean mouseScrolled(final double mouseX, final double mouseY, final double scrollX, final double scrollY) {
        if (scrollY != 0) {
            camera.zoomAt(mouseX, mouseY, Math.pow(1.18, scrollY));
        }
        return true;
    }

    private void clickAt(final double mx, final double my, final int button, final boolean shift) {
        final int node = hovered(mx, my);
        if (pending != null && node == pending.node()
                && (pending.refund() ? button == GLFW.GLFW_MOUSE_BUTTON_RIGHT : button == GLFW.GLFW_MOUSE_BUTTON_LEFT)) {
            runPending(); // 同一個節點再按一次：確認
            return;
        }
        pending = null; // 點別的地方：取消
        if (node < 0) {
            return;
        }
        final String id = model.data.ids[node];
        final boolean owned = model.state.owned.get(node);
        if (button == GLFW.GLFW_MOUSE_BUTTON_RIGHT) {
            if (!owned || model.data.isStart(node)) {
                return;
            }
            if (model.state.fixed.get(node)) {
                showMessage("<red>退掉會讓其他節點斷線，先退外面的");
                return;
            }
            pending = TreeGuard.refund(model, node); // 2026-11-02 照站長：花金幣的先確認
            if (pending != null) {
                return;
            }
            UiNet.send(UiWire.actionId(kind(), "refund", id));
            return;
        }
        if (button != GLFW.GLFW_MOUSE_BUTTON_LEFT) {
            return;
        }
        if ("asc".equals(kind()) && shift && model.data.skill[node]) {
            UiNet.send(UiWire.actionId(kind(), "skill", id));
            return;
        }
        if (owned) {
            if (model.data.type[node] == 'j' && !model.state.jewels.get(node) && model.state.bag != null) {
                pickSocket = node; // 2026-11-02 照站長：空的珠寶孔在這裡直接選（伺服器有列背包的珠寶時；舊伺服器照舊開箱子選單）
                pickRects = null;
                return;
            }
            if (model.state.use.containsKey(id) || model.data.type[node] == 'j') {
                UiNet.send(UiWire.actionId(kind(), "use", id));
            }
            return;
        }
        if ("asc".equals(kind())) {
            UiNet.send(UiWire.actionId(kind(), "alloc", id));
            return;
        }
        final List<Integer> path = model.path(node, false);
        if (path.isEmpty()) {
            showMessage(model.state.blocked.get(node) ? "<red>被解鎖條件擋住" : "<red>走不到那裡");
            return;
        }
        final int need = "atlastree".equals(kind()) ? model.cost(path) : path.size();
        if (need > model.state.points) {
            showMessage("<red>要 " + need + " 點，你有 " + model.state.points + " 點");
            return;
        }
        if (TreeGuard.pathNeedsConfirm(need, ExileCoreClient.config.confirmPathPoints)) {
            pending = TreeGuard.path(model, node, path, need); // 2026-11-02 照站長：一次配很多點先確認
            return;
        }
        UiNet.send(UiWire.actionPath(kind(), ids(path)));
    }

    @Override
    public boolean keyPressed(final KeyEvent event) {
        if (pickSocket >= 0 && event.key() == GLFW.GLFW_KEY_ESCAPE) {
            pickSocket = -1; // Esc：先關掉選珠寶的清單
            pickRects = null;
            return true;
        }
                if (pending != null && event.key() == GLFW.GLFW_KEY_ESCAPE) {
            pending = null; // Esc：先取消確認框，不關畫面
            showMessage("<gray>取消了");
            return true;
        }
        if (search != null && search.isFocused() && (event.key() == GLFW.GLFW_KEY_ENTER || event.key() == GLFW.GLFW_KEY_KP_ENTER)) {
            jump(event.hasShiftDown() ? -1 : 1);
            return true;
        }
        if (!(search != null && search.isFocused())) {
            if (event.key() == GLFW.GLFW_KEY_ENTER || event.key() == GLFW.GLFW_KEY_KP_ENTER) {
                jump(event.hasShiftDown() ? -1 : 1);
                return true;
            }
            if (event.key() == GLFW.GLFW_KEY_F && event.hasControlDown() && search != null) {
                setFocused(search);
                search.setFocused(true);
                return true;
            }
            if (UiNet.closeKey(this, event) || UiNet.openTree.matches(event) && passive() || UiNet.openAtlas.matches(event) && "atlastree".equals(kind())) {
                onClose(); // 同一個鍵再按一次關掉
                return true;
            }
        }
        return super.keyPressed(event);
    }

    @Override
    public boolean isPauseScreen() {
        return false;
    }

    /** 畫面關了（Esc、被別的畫面換掉，例如伺服器開了選珠寶的箱子選單）：跟伺服器說，有配點的話伺服器存檔。 */
    @Override
    public void removed() {
        UiNet.send(UiWire.action(kind(), "close"));
        super.removed();
    }
}
