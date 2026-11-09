package tw.exilecore.client;

import com.google.gson.JsonObject;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;
import tw.exilecore.client.core.UiWire;

/**
 * 2026-11-09 照站長「模組右上新增打開網頁天賦規劃器與匯入代碼」：貼網頁規劃器的代碼（或整個網址）送給伺服器，
 * 伺服器照遊戲裡的「匯入規劃代碼」先跳確認（要配哪些、退哪些、花多少金幣；兩組武器組天賦一起帶），確認了才套用。
 * 這次之前模組的天賦畫面沒有匯入，要回箱子選單或打 /tree import。
 */
final class ImportCodeScreen extends Screen {

    /** 代碼最長幾個字（網址帶珠寶、武器組會比較長；封包上限 16384 位元組）。 */
    static final int MAX_CODE = 8000;

    private final Screen parent;
    private final String kind;
    private EditBox box;

    ImportCodeScreen(final Screen parent, final String kind) {
        super(Component.literal("匯入天賦規劃"));
        this.parent = parent;
        this.kind = kind;
    }

    @Override
    protected void init() {
        final int w = Math.min(360, width - 40);
        final int left = (width - w) / 2;
        final int y = height / 2 - 20;
        box = new EditBox(font, left, y, w, 20, Component.literal("代碼"));
        box.setMaxLength(MAX_CODE);
        box.setHint(Component.literal("貼上網頁規劃器的代碼或整個網址（Ctrl＋V）"));
        addRenderableWidget(box);
        setInitialFocus(box);
        final int bw = Math.min(100, (w - 8) / 2);
        addRenderableWidget(Button.builder(Component.literal("匯入"), b -> submit()).bounds(width / 2 - bw - 4, y + 30, bw, 20).build());
        addRenderableWidget(Button.builder(Component.literal("取消"), b -> onClose()).bounds(width / 2 + 4, y + 30, bw, 20).build());
    }

    private void submit() {
        final String code = box.getValue().trim();
        if (code.isEmpty()) {
            return;
        }
        final JsonObject extra = new JsonObject();
        extra.addProperty("code", code);
        UiNet.send(UiWire.action(kind, "import", extra));
        minecraft.gui.setScreen(parent); // 伺服器會跳確認框（蓋在天賦樹上）；套用後天賦樹自動更新
    }

    @Override
    public void extractRenderState(final GuiGraphicsExtractor g, final int mouseX, final int mouseY, final float delta) {
        super.extractRenderState(g, mouseX, mouseY, delta);
        g.centeredText(this.font, this.title, this.width / 2, height / 2 - 52, 0xFFF5B942);
        g.centeredText(this.font, Component.literal("下一步會先列出要配哪些、退哪些、花多少金幣，確認了才套用（兩組武器組天賦一起帶）"),
                this.width / 2, height / 2 - 36, 0xFFA0A0A0);
    }

    @Override
    public void onClose() {
        minecraft.gui.setScreen(parent);
    }
}
