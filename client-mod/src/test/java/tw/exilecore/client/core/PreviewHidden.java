package tw.exilecore.client.core;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

import javax.imageio.ImageIO;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

/**
 * 2026-11-02 照站長：原生天賦樹的「不見之途專屬天賦」顏色與「虛實交織」跳點的示意圖（跟遊戲同一份畫法 {@link TreePainter}）。
 * 不是測試（沒有 @Test）：手動跑 {@code java ... PreviewHidden 資料夾 輸出.png}，資料夾裡放插件產生的 passive.json（TreeFeed.staticJson）
 * 與 passive-state.json（觀星、配了不見之途與虛實交織、配到一個基石、跳點配了一個的狀態）。
 * 第三個參數（可省略）是滑鼠指著的節點代號。
 */
public final class PreviewHidden {

    private PreviewHidden() {
    }

    public static void main(final String[] args) throws Exception {
        final Path in = Path.of(args[0]);
        final byte[] raw = Files.readAllBytes(in.resolve("passive.json"));
        final TreeModel.Data data = TreeModel.parseData("passive", UiWire.hash(raw), new String(raw, StandardCharsets.UTF_8));
        final JsonObject stateJson = JsonParser.parseString(Files.readString(in.resolve("passive-state.json"))).getAsJsonObject();
        final TreeModel model = new TreeModel(data, TreeModel.parseState(stateJson, data));
        int anchor = model.state.anchors.nextSetBit(0);
        if (anchor < 0) {
            anchor = model.state.start;
        }
        final Camera camera = new Camera(data.x[anchor], data.y[anchor], 26 / data.unit);
        camera.viewport(0, 0, PreviewD.W, PreviewD.H);
        final TreePainter.View view = new TreePainter.View();
        int hover = args.length > 2 ? data.indexOf(args[2]) : -1;
        if (hover < 0) {
            for (int i = 0; i < data.size(); i++) {
                if (data.unseen[i] && model.jump(i)) {
                    hover = i;
                    break;
                }
            }
        }
        view.hover = hover;
        if (hover >= 0) {
            view.setPath(model.path(hover, false));
        }
        final int mx = hover < 0 ? -1 : (int) camera.toScreenX(data.x[hover]);
        final int my = hover < 0 ? -1 : (int) camera.toScreenY(data.y[hover]);
        ImageIO.write(PreviewD.image(c -> PreviewD.treeScene(c, model, camera, view, "", List.of(), mx, my, null)), "png",
                Path.of(args[1]).toFile());
    }
}
