package tw.exilecore.client;

import com.google.gson.JsonObject;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.input.KeyEvent;
import net.minecraft.client.input.MouseButtonEvent;
import net.minecraft.network.chat.Component;
import org.lwjgl.glfw.GLFW;
import tw.exilecore.client.core.ExchangeModel;
import tw.exilecore.client.core.Rich;
import tw.exilecore.client.core.TreePainter;
import tw.exilecore.client.core.UiPanels;
import tw.exilecore.client.core.UiWire;

import java.util.ArrayList;
import java.util.List;

/**
 * 2026-11-02 照站長：通貨交易所的原生畫面（照 PoE2 的交易所）：左邊「我要」、右邊「我有」各一個可以搜尋的通貨清單，
 * 中間是這一對的行情（最近成交、參考價）、兩欄深度表（可以馬上換到的／排隊中的出價）、數量（打一邊另一邊照市價自動算，
 * ±5% 微調）、下單（先確認）、我的掛單（可以取消）。下單、取消照舊走伺服器的 MarketService（跟箱子選單同一條路）。
 * 這次之前交易所只有箱子選單（沒裝模組的照舊是箱子選單）。
 */
final class ExchangeScreen extends Screen {

    private static final String KIND = "exchange";
    private static final int ROW = 18;
    private static final int LINE = 11;

    private ExchangeModel model;
    private EditBox wantSearch;
    private EditBox giveSearch;
    private EditBox wantBox;
    private EditBox giveBox;
    private int wantScroll;
    private int giveScroll;
    /** 最後打的是哪一格（「照市價」用它算另一格）。 */
    private String edited = "want";
    private boolean confirming;
    private double[] confirmRects;
    private String cancelArmed;
    private long cancelAt;
    private String message = "";
    private long messageAt;

    // 這一幀的版面（點擊判斷用）
    private int colW;
    private int listTop;
    private int listBottom;
    private int midX0;
    private int midX1;
    private int depthTop;
    private int depthRows;
    private int ordersTop;
    private final List<double[]> cancelRects = new ArrayList<>();
    private final List<String> cancelIds = new ArrayList<>();

    ExchangeScreen(final ExchangeModel model) {
        super(Component.literal("通貨交易所"));
        this.model = model;
        if (!model.message.isEmpty()) {
            showMessage(model.message);
        }
    }

    @Override
    protected void init() {
        colW = Math.max(110, Math.min(160, width / 5));
        midX0 = 8 + colW + 10;
        midX1 = width - 8 - colW - 10;
        listTop = 44;
        listBottom = height - 24;
        final String oldWant = wantSearch == null ? "" : wantSearch.getValue();
        final String oldGive = giveSearch == null ? "" : giveSearch.getValue();
        wantSearch = new EditBox(font, 8, 24, colW, 16, Component.literal("搜尋"));
        wantSearch.setHint(Component.literal("搜尋通貨"));
        wantSearch.setMaxLength(30);
        wantSearch.setValue(oldWant);
        wantSearch.setResponder(s -> wantScroll = 0);
        addRenderableWidget(wantSearch);
        giveSearch = new EditBox(font, width - 8 - colW, 24, colW, 16, Component.literal("搜尋"));
        giveSearch.setHint(Component.literal("搜尋通貨"));
        giveSearch.setMaxLength(30);
        giveSearch.setValue(oldGive);
        giveSearch.setResponder(s -> giveScroll = 0);
        addRenderableWidget(giveSearch);

        depthTop = 50;
        depthRows = height < 300 ? 5 : 8;
        final int amountY = depthTop + 12 + depthRows * LINE + 8;
        final int boxW = 56;
        final int midW = midX1 - midX0;
        final int labelW = font.width("我要 ") + 2;
        int x = midX0;
        wantBox = new EditBox(font, x + labelW, amountY, boxW, 16, Component.literal("我要"));
        wantBox.setMaxLength(6);
        wantBox.setValue(String.valueOf(model.wantAmount));
        wantBox.setResponder(s -> edited = "want");
        addRenderableWidget(wantBox);
        x += labelW + boxW + 26;
        giveBox = new EditBox(font, x + labelW, amountY, boxW, 16, Component.literal("我給"));
        giveBox.setMaxLength(6);
        giveBox.setValue(String.valueOf(model.giveAmount));
        giveBox.setResponder(s -> edited = "give");
        addRenderableWidget(giveBox);

        final List<Button> buttons = new ArrayList<>();
        buttons.add(button("照市價", "打一格、另一格照現在能馬上換到的價格算", b -> quote()));
        buttons.add(button("用這兩個數", "照你打的兩個數字自訂比例（可能要排隊）", b -> custom()));
        buttons.add(button("參考價", "照最近的成交算", b -> UiNet.send(UiWire.action(KIND, "ref"))));
        buttons.add(button("−5%", "我給的少一點（比較慢成交）", b -> nudge(false)));
        buttons.add(button("+5%", "我給的多一點（比較快成交）", b -> nudge(true)));
        buttons.add(button("下單", "下單前會再確認一次", b -> place()));
        int bx = midX0;
        int by = amountY + 20;
        for (final Button button : buttons) {
            final int w = Math.max(30, font.width(button.getMessage()) + 10);
            if (bx + w > midX0 + midW) {
                bx = midX0;
                by += 20;
            }
            button.setX(bx);
            button.setY(by);
            button.setWidth(w);
            button.setHeight(18);
            addRenderableWidget(button);
            bx += w + 3;
        }
        ordersTop = by + 22 + 3 * LINE + 6;
        final Button swap = button("⇄", "我要、我有對調", b -> pair(model.want, model.give));
        swap.setX(midX0 + midW / 2 - 9);
        swap.setY(8);
        swap.setWidth(18);
        swap.setHeight(16);
        addRenderableWidget(swap);
        final Button chest = button("箱子選單", "改用原本的箱子選單", b -> UiNet.send(UiWire.action(KIND, "chest")));
        final int cw = font.width(chest.getMessage()) + 10;
        chest.setX(midX1 - cw);
        chest.setY(height - 20);
        chest.setWidth(cw);
        chest.setHeight(16);
        addRenderableWidget(chest);
    }

    private Button button(final String label, final String tip, final Button.OnPress press) {
        return Button.builder(Component.literal(label), press)
                .tooltip(net.minecraft.client.gui.components.Tooltip.create(Component.literal(tip))).build();
    }

    void update(final ExchangeModel fresh) {
        model = fresh;
        if (!fresh.message.isEmpty()) {
            showMessage(fresh.message);
        }
        if (wantBox != null && !wantBox.isFocused()) {
            wantBox.setValue(String.valueOf(fresh.wantAmount));
        }
        if (giveBox != null && !giveBox.isFocused()) {
            giveBox.setValue(String.valueOf(fresh.giveAmount));
        }
        if (confirming && (!fresh.ready())) {
            confirming = false;
        }
    }

    private void showMessage(final String text) {
        message = text;
        messageAt = System.currentTimeMillis();
    }

    // ---- 動作 ----

    private void pair(final String give, final String want) {
        final JsonObject extra = new JsonObject();
        if (give != null) {
            extra.addProperty("give", give);
        }
        if (want != null) {
            extra.addProperty("want", want);
        }
        confirming = false;
        UiNet.send(UiWire.action(KIND, "pair", extra));
    }

    private void pickWant(final String id) {
        pair(id.equals(model.give) ? model.want : model.give, id);
    }

    private void pickGive(final String id) {
        pair(id, id.equals(model.want) ? model.give : model.want);
    }

    private void quote() {
        if (!model.ready()) {
            showMessage("<yellow>先選「我要」和「我有」的通貨");
            return;
        }
        final EditBox box = "give".equals(edited) ? giveBox : wantBox;
        final int amount = ExchangeModel.parseAmount(box.getValue(), model.max);
        if (amount < 0) {
            showMessage("<red>數量打錯了");
            return;
        }
        final JsonObject extra = new JsonObject();
        extra.addProperty("mode", edited);
        extra.addProperty("amount", amount);
        unfocus();
        UiNet.send(UiWire.action(KIND, "quote", extra));
    }

    /** 送出兩格的數字；回傳 true = 跟伺服器記的一樣（不用送）。 */
    private boolean custom() {
        final int give = ExchangeModel.parseAmount(giveBox.getValue(), model.max);
        final int want = ExchangeModel.parseAmount(wantBox.getValue(), model.max);
        if (give < 0 || want < 0) {
            showMessage("<red>數量打錯了");
            return false;
        }
        if (give == model.giveAmount && want == model.wantAmount) {
            return true;
        }
        final JsonObject extra = new JsonObject();
        extra.addProperty("give", give);
        extra.addProperty("want", want);
        unfocus();
        UiNet.send(UiWire.action(KIND, "amounts", extra));
        return false;
    }

    private void nudge(final boolean up) {
        final JsonObject extra = new JsonObject();
        extra.addProperty("up", up);
        unfocus();
        UiNet.send(UiWire.action(KIND, "nudge", extra));
    }

    private void place() {
        if (!model.ready()) {
            showMessage("<yellow>先選「我要」和「我有」的通貨");
            return;
        }
        if (!custom()) {
            showMessage("<yellow>數量更新了，看一下預覽再按一次下單");
            return;
        }
        confirming = true;
    }

    private void confirmPlace() {
        confirming = false;
        final JsonObject extra = new JsonObject();
        extra.addProperty("give", model.giveAmount);
        extra.addProperty("want", model.wantAmount);
        UiNet.send(UiWire.action(KIND, "place", extra));
    }

    private void unfocus() {
        wantBox.setFocused(false);
        giveBox.setFocused(false);
        setFocused(null);
    }

    // ---- 畫 ----

    @Override
    public void extractBackground(final GuiGraphicsExtractor g, final int mouseX, final int mouseY, final float delta) {
        g.fill(0, 0, width, height, TreePainter.BACKGROUND);
    }

    @Override
    public void extractRenderState(final GuiGraphicsExtractor g, final int mouseX, final int mouseY, final float delta) {
        final GuiCanvas canvas = new GuiCanvas(g, font);
        String[] tip = null;
        final ExchangeModel.Currency hoverWant = column(g, canvas, "<#f5b942>我要", 8, model.picker(wantSearch.getValue()), wantScroll, model.want,
                mouseX, mouseY);
        final ExchangeModel.Currency hoverGive = column(g, canvas, "<#f5b942>我有", width - 8 - colW, model.picker(giveSearch.getValue()), giveScroll,
                model.give, mouseX, mouseY);
        middle(g, canvas, mouseX, mouseY);
        UiPanels.hint(canvas, "<gray>左邊點<yellow>我要<gray>的、右邊點<yellow>我有<gray>的・數字框打完按 <yellow>Enter<gray> 照市價算・點排隊中的價位跟他一樣出價", width, height);
        final long age = System.currentTimeMillis() - messageAt;
        if (age < 5000) {
            UiPanels.toast(canvas, message, width, height, age < 4000 ? 1 : (5000 - age) / 1000.0);
        }
        super.extractRenderState(g, mouseX, mouseY, delta);
        final ExchangeModel.Currency hover = hoverWant != null ? hoverWant : hoverGive;
        if (hover != null && !confirming) {
            final List<String> lines = new ArrayList<>();
            lines.add("<white>" + hover.name());
            lines.add("<gray>你有 <white>" + String.format("%,d", hover.have()) + "<gray> 個");
            if (!hover.group().isEmpty()) {
                lines.add("<dark_gray>" + hover.group());
            }
            UiPanels.tooltip(canvas, lines, mouseX, mouseY, width, height);
        }
        if (confirming) {
            g.fill(0, 0, width, height, 0x88000000);
            confirmRects = UiPanels.confirm(canvas, confirmLines(), width, height);
        } else {
            confirmRects = null;
        }
    }

    /** 一欄通貨清單；回傳滑鼠底下的那一個。 */
    private ExchangeModel.Currency column(final GuiGraphicsExtractor g, final GuiCanvas canvas, final String title, final int x,
                                          final List<ExchangeModel.Currency> list, final int scroll, final String selected, final int mx, final int my) {
        canvas.rect(x - 2, 6, x + colW + 2, listBottom + 2, UiPanels.PANEL_BG);
        canvas.frame(x - 2, 6, x + colW + 2, listBottom + 2, UiPanels.PANEL_BORDER);
        canvas.runs(Rich.parse(title, 0xFFFFFF), x + 2, 11, 1, true);
        final int visible = Math.max(1, (listBottom - listTop) / ROW);
        ExchangeModel.Currency hover = null;
        canvas.clip(x - 2, listTop, x + colW + 2, listBottom);
        for (int i = 0; i < visible && scroll + i < list.size(); i++) {
            final ExchangeModel.Currency c = list.get(scroll + i);
            final int y = listTop + i * ROW;
            final boolean over = mx >= x && mx < x + colW && my >= y && my < y + ROW && !confirming;
            if (c.id().equals(selected)) {
                canvas.rect(x, y, x + colW, y + ROW, 0x66F5B942);
            } else if (over) {
                canvas.rect(x, y, x + colW, y + ROW, 0x33FFFFFF);
            }
            if (over) {
                hover = c;
            }
            g.item(HudRenderer.icon(c.item(), c.model()), x + 1, y + 1);
            final String have = c.have() > 0 ? String.format("%,d", c.have()) : "";
            final int haveW = font.width(have);
            String name = c.name();
            while (name.length() > 1 && font.width(name) > colW - 22 - haveW - 4) {
                name = name.substring(0, name.length() - 1);
            }
            canvas.text(name, x + 20, y + 5, c.have() > 0 ? 0xFFFFFFFF : 0xFF9A9AA8, 1, true);
            if (!have.isEmpty()) {
                canvas.text(have, x + colW - haveW - 2, y + 5, 0xFFF5C04A, 1, true);
            }
        }
        canvas.unclip();
        if (list.isEmpty()) {
            canvas.text("找不到", x + 4, listTop + 4, 0xFF777777, 1, true);
        } else if (list.size() > visible) {
            final double frac = (double) visible / list.size();
            final double h = (listBottom - listTop) * frac;
            final double top = listTop + (listBottom - listTop - h) * scroll / Math.max(1, list.size() - visible);
            canvas.rect(x + colW, top, x + colW + 2, top + h, 0xAAFFFFFF);
        }
        return hover;
    }

    private void line(final GuiCanvas canvas, final String text, final double x, final double y) {
        canvas.runs(Rich.parse(text, 0xAAAAAA), x, y, 1, true);
    }

    private void middle(final GuiGraphicsExtractor g, final GuiCanvas canvas, final int mx, final int my) {
        final int cx = (midX0 + midX1) / 2;
        // 這一對：「我要」的圖示在左、「我有」的在右，中間是對調鈕
        if (model.want != null) {
            final ExchangeModel.Currency c = model.byId.get(model.want);
            if (c != null) {
                g.item(HudRenderer.icon(c.item(), c.model()), cx - 32, 8);
            }
            final String name = model.name(model.want);
            canvas.text(name, cx - 36 - font.width(name), 12, 0xFFFFFFFF, 1, true);
        } else {
            canvas.text("← 選我要的", cx - 16 - font.width("← 選我要的"), 12, 0xFF888888, 1, true);
        }
        if (model.give != null) {
            final ExchangeModel.Currency c = model.byId.get(model.give);
            if (c != null) {
                g.item(HudRenderer.icon(c.item(), c.model()), cx + 16, 8);
            }
            canvas.text(model.name(model.give), cx + 36, 12, 0xFFFFFFFF, 1, true);
        } else {
            canvas.text("選我有的 →", cx + 16, 12, 0xFF888888, 1, true);
        }
        if (!model.ready()) {
            line(canvas, "<gray>左邊選你要的通貨、右邊選你拿來換的通貨", midX0, depthTop);
            orders(canvas, mx, my);
            return;
        }
        final String market = model.marketText();
        line(canvas, market.isEmpty() ? "<dark_gray>最近沒有成交" : "<gray>最近成交 <white>" + market
                + (model.market != null && model.market.vol24() > 0 ? "<dark_gray>・24 小時 " + model.market.count24() + " 筆" : ""), midX0, 28);
        if (model.ref != null) {
            line(canvas, "<gray>參考價 <white>" + ExchangeModel.ratio(model.name(model.want), model.ref[0], model.name(model.give), model.ref[1]),
                    midX0, 38);
        }
        // 深度表
        final int half = (midX1 - midX0) / 2;
        canvas.rect(midX0 - 2, depthTop - 2, midX1 + 2, depthTop + 12 + depthRows * LINE + 2, UiPanels.PANEL_BG);
        line(canvas, "<green>可以馬上換到的", midX0, depthTop);
        line(canvas, "<aqua>排隊中的出價（點了跟他一樣）", midX0 + half + 2, depthTop);
        depthColumn(canvas, model.asks, midX0, half - 4, false, mx, my);
        depthColumn(canvas, model.bids, midX0 + half + 2, half - 4, true, mx, my);
        // 數量
        final int amountY = wantBox.getY();
        canvas.text("我要", midX0, amountY + 4, 0xFFF5B942, 1, true);
        canvas.text("我給", giveBox.getX() - font.width("我要 ") - 2, amountY + 4, 0xFFF5B942, 1, true);
        canvas.text("有 " + String.format("%,d", model.have(model.give)), giveBox.getX() + giveBox.getWidth() + 4, amountY + 4, 0xFF9A9AA8, 1, true);
        // 預覽
        final int previewY = ordersTop - 3 * LINE - 6;
        final List<String> preview = new ArrayList<>();
        preview.add("<gray>比例 <white>" + ExchangeModel.ratio(model.name(model.want), model.giveAmount, model.name(model.give), model.wantAmount)
                + (model.source.isEmpty() ? "" : "<dark_gray>（" + model.source + "）"));
        if (model.preview != null) {
            final ExchangeModel.Preview p = model.preview;
            String first = p.received() > 0
                    ? "<green>馬上換到 " + p.received() + " " + model.name(model.want) + "<gray>（付 " + p.paid() + " " + model.name(model.give) + "）"
                    : "<yellow>現在沒有人照這個價賣，會整張排隊";
            if (p.fee() > 0) {
                first += "<gray>・手續費 " + String.format("%,d", p.fee()) + " 金幣";
            }
            preview.add(first);
            if (p.resting()) {
                preview.add("<aqua>剩下 " + p.restGive() + " " + model.name(model.give) + " 掛著等 " + p.restWant() + " " + model.name(model.want));
            } else if (p.refund() > 0) {
                preview.add("<gray>退回 " + p.refund() + " " + model.name(model.give) + "（零頭）");
            }
        }
        for (int i = 0; i < preview.size(); i++) {
            line(canvas, preview.get(i), midX0, previewY + i * LINE);
        }
        orders(canvas, mx, my);
    }

    private void depthColumn(final GuiCanvas canvas, final List<ExchangeModel.Level> levels, final int x, final int w, final boolean clickable,
                             final int mx, final int my) {
        if (levels.isEmpty()) {
            canvas.text("沒有", x, depthTop + 12, 0xFF666666, 1, true);
            return;
        }
        for (int i = 0; i < Math.min(depthRows, levels.size()); i++) {
            final ExchangeModel.Level level = levels.get(i);
            final int y = depthTop + 12 + i * LINE;
            if (clickable && !confirming && mx >= x && mx < x + w && my >= y - 1 && my < y + LINE - 1) {
                canvas.rect(x - 1, y - 1, x + w, y + LINE - 1, 0x33FFFFFF);
            }
            String text = model.levelText(level);
            final String stock = "×" + level.stock() + (level.mine() ? " ★" : "");
            final int sw = font.width(stock);
            while (text.length() > 1 && font.width(text) > w - sw - 6) {
                text = text.substring(0, text.length() - 1);
            }
            canvas.text(text, x, y, level.mine() ? 0xFFF5C04A : 0xFFDDDDDD, 1, true);
            canvas.text(stock, x + w - sw, y, 0xFF9A9AA8, 1, true);
        }
    }

    private void orders(final GuiCanvas canvas, final int mx, final int my) {
        cancelRects.clear();
        cancelIds.clear();
        line(canvas, "<#f5b942>我的掛單 <dark_gray>" + model.orders.size() + (model.maxOrders > 0 ? "/" + model.maxOrders : ""), midX0, ordersTop);
        if (model.orders.isEmpty()) {
            line(canvas, "<dark_gray>沒有", midX0, ordersTop + LINE + 1);
            return;
        }
        final int rows = Math.max(1, (height - 24 - ordersTop - LINE - 2) / LINE);
        for (int i = 0; i < Math.min(rows, model.orders.size()); i++) {
            final ExchangeModel.Order o = model.orders.get(i);
            final int y = ordersTop + LINE + 1 + i * LINE;
            final boolean armed = o.id().equals(cancelArmed) && System.currentTimeMillis() - cancelAt < 4000;
            final String label = armed ? "[再按確認]" : "[取消]";
            final int lw = font.width(label);
            final double lx = midX1 - lw;
            final boolean over = !confirming && mx >= lx && mx < midX1 && my >= y - 1 && my < y + LINE - 1;
            String text = o.giveTotal() + " " + model.name(o.give()) + " → " + o.wantTotal() + " " + model.name(o.want())
                    + "・換到 " + o.taken() + "・剩 " + o.remaining() + (o.ago().isEmpty() ? "" : "・" + o.ago()) + (o.confirmed() ? "" : "（確認中）");
            while (text.length() > 1 && font.width(text) > midX1 - midX0 - lw - 6) {
                text = text.substring(0, text.length() - 1);
            }
            canvas.text(text, midX0, y, 0xFFCCCCCC, 1, true);
            canvas.text(label, lx, y, over ? 0xFFFF7777 : armed ? 0xFFFFAA55 : 0xFFAA5555, 1, true);
            cancelRects.add(new double[] {lx, y - 1, midX1, y + LINE - 1});
            cancelIds.add(o.id());
        }
    }

    private List<String> confirmLines() {
        final List<String> lines = new ArrayList<>();
        lines.add("<#f5b942>確認下單");
        lines.add("<white>拿 " + String.format("%,d", model.giveAmount) + " " + model.name(model.give) + " 換 " + String.format("%,d", model.wantAmount)
                + " " + model.name(model.want));
        lines.add("<gray>比例 " + ExchangeModel.ratio(model.name(model.want), model.giveAmount, model.name(model.give), model.wantAmount));
        if (model.preview != null) {
            final ExchangeModel.Preview p = model.preview;
            if (p.received() > 0) {
                lines.add("<green>馬上換到 " + p.received() + " " + model.name(model.want));
            }
            if (p.resting()) {
                lines.add("<aqua>剩下的掛單排隊（之後可以取消）");
            }
            if (p.fee() > 0) {
                lines.add("<gray>手續費 " + String.format("%,d", p.fee()) + " 金幣");
            }
        }
        return lines;
    }

    // ---- 輸入 ----

    @Override
    public boolean mouseClicked(final MouseButtonEvent event, final boolean doubleClick) {
        final double mx = event.x();
        final double my = event.y();
        if (confirming) {
            if (UiPanels.inside(confirmRects, 1, mx, my)) {
                confirmPlace();
            } else if (UiPanels.inside(confirmRects, 2, mx, my) || !UiPanels.inside(confirmRects, 0, mx, my)) {
                confirming = false;
            }
            return true;
        }
        if (super.mouseClicked(event, doubleClick)) {
            return true;
        }
        if (event.button() != GLFW.GLFW_MOUSE_BUTTON_LEFT) {
            return false;
        }
        final ExchangeModel.Currency want = at(8, model.picker(wantSearch.getValue()), wantScroll, mx, my);
        if (want != null) {
            pickWant(want.id());
            return true;
        }
        final ExchangeModel.Currency give = at(width - 8 - colW, model.picker(giveSearch.getValue()), giveScroll, mx, my);
        if (give != null) {
            pickGive(give.id());
            return true;
        }
        if (model.ready()) {
            final int half = (midX1 - midX0) / 2;
            final int bx = midX0 + half + 2;
            if (mx >= bx && mx < midX1 && my >= depthTop + 11) {
                final int index = (int) ((my - depthTop - 11) / LINE);
                if (index >= 0 && index < Math.min(depthRows, model.bids.size())) {
                    final JsonObject extra = new JsonObject();
                    extra.addProperty("index", index);
                    UiNet.send(UiWire.action(KIND, "match", extra));
                    return true;
                }
            }
        }
        for (int i = 0; i < cancelRects.size(); i++) {
            final double[] r = cancelRects.get(i);
            if (mx >= r[0] && mx < r[2] && my >= r[1] && my < r[3]) {
                final String id = cancelIds.get(i);
                if (id.equals(cancelArmed) && System.currentTimeMillis() - cancelAt < 4000) {
                    cancelArmed = null;
                    final JsonObject extra = new JsonObject();
                    extra.addProperty("id", id);
                    UiNet.send(UiWire.action(KIND, "cancel", extra));
                } else {
                    cancelArmed = id;
                    cancelAt = System.currentTimeMillis();
                }
                return true;
            }
        }
        unfocus();
        return false;
    }

    private ExchangeModel.Currency at(final int x, final List<ExchangeModel.Currency> list, final int scroll, final double mx, final double my) {
        if (mx < x || mx >= x + colW || my < listTop || my >= listBottom) {
            return null;
        }
        final int index = scroll + (int) ((my - listTop) / ROW);
        return index >= 0 && index < list.size() ? list.get(index) : null;
    }

    @Override
    public boolean mouseScrolled(final double mouseX, final double mouseY, final double scrollX, final double scrollY) {
        final int step = scrollY > 0 ? -1 : scrollY < 0 ? 1 : 0;
        final int visible = Math.max(1, (listBottom - listTop) / ROW);
        if (mouseX >= 8 && mouseX < 8 + colW) {
            wantScroll = clampScroll(wantScroll + step * 2, model.picker(wantSearch.getValue()).size(), visible);
            return true;
        }
        if (mouseX >= width - 8 - colW && mouseX < width - 8) {
            giveScroll = clampScroll(giveScroll + step * 2, model.picker(giveSearch.getValue()).size(), visible);
            return true;
        }
        return super.mouseScrolled(mouseX, mouseY, scrollX, scrollY);
    }

    private static int clampScroll(final int value, final int size, final int visible) {
        return Math.max(0, Math.min(value, Math.max(0, size - visible)));
    }

    @Override
    public boolean keyPressed(final KeyEvent event) {
        if (confirming) {
            if (event.key() == GLFW.GLFW_KEY_ENTER || event.key() == GLFW.GLFW_KEY_KP_ENTER) {
                confirmPlace();
                return true;
            }
            if (event.key() == GLFW.GLFW_KEY_ESCAPE) {
                confirming = false;
                return true;
            }
        }
        if ((event.key() == GLFW.GLFW_KEY_ENTER || event.key() == GLFW.GLFW_KEY_KP_ENTER) && (wantBox.isFocused() || giveBox.isFocused())) {
            edited = giveBox.isFocused() ? "give" : "want";
            quote();
            return true;
        }
        if (UiNet.closeKey(this, event) || (UiNet.openExchange.matches(event) && !(getFocused() instanceof EditBox box && box.isFocused()))) {
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
        UiNet.send(UiWire.action(KIND, "close"));
        super.removed();
    }
}
