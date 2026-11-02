package tw.exilecore.client.core;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

import javax.imageio.ImageIO;
import java.awt.AlphaComposite;
import java.awt.Color;
import java.awt.Font;
import java.awt.FontMetrics;
import java.awt.Graphics2D;
import java.awt.RenderingHints;
import java.awt.geom.AffineTransform;
import java.awt.geom.Rectangle2D;
import java.awt.image.BufferedImage;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.BitSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/**
 * 2026-11-02 照站長（D 組）：原生畫面的預覽圖（client-mod/dist/preview-14d-*.png，1920×1080，GUI 縮放 2）。
 * 用跟遊戲裡同一份畫法（{@link TreePainter}、{@link AtlasPainter}、{@link UiPanels}），只是畫布換成 Java2D、字型換成 Noto Sans CJK。
 * 不是測試（沒有 @Test）：手動跑 {@code java ... PreviewD 資料夾 輸出資料夾}，資料夾裡放插件產生的 passive.json、atlastree.json、atlas.json。
 */
public final class PreviewD {

    static final int SCALE = 2;
    static final int W = 1920 / SCALE;
    static final int H = 1080 / SCALE;

    /** Java2D 的畫布（GUI 像素 × 2）。 */
    static final class AwtCanvas implements UiCanvas {
        final Graphics2D g;
        final Font font;
        final Font symbols;
        final FontMetrics metrics;

        AwtCanvas(final Graphics2D g) {
            this.g = g;
            font = new Font("Noto Sans CJK TC", Font.PLAIN, 8);
            symbols = new Font("DejaVu Sans", Font.PLAIN, 8); // ☠ 這種符號 Noto CJK 沒有（遊戲的字型有）
            g.setFont(font);
            metrics = g.getFontMetrics();
        }

        private void color(final int argb) {
            g.setColor(new Color(argb, true));
        }

        @Override
        public void rect(final double x0, final double y0, final double x1, final double y1, final int argb) {
            color(argb);
            g.fill(new Rectangle2D.Double(x0, y0, x1 - x0, y1 - y0));
        }

        @Override
        public void quad(final double cx, final double cy, final double w, final double h, final double angle, final int argb) {
            final AffineTransform saved = g.getTransform();
            g.translate(cx, cy);
            g.rotate(angle);
            color(argb);
            g.fill(new Rectangle2D.Double(-w / 2, -h / 2, w, h));
            g.setTransform(saved);
        }

        @Override
        public void text(final String text, final double x, final double y, final int argb, final double scale, final boolean shadow) {
            final AffineTransform saved = g.getTransform();
            g.translate(x, y);
            g.scale(scale, scale);
            if (shadow) {
                final int r = ((argb >> 16) & 0xFF) / 4;
                final int gg = ((argb >> 8) & 0xFF) / 4;
                final int b = (argb & 0xFF) / 4;
                color((argb & 0xFF000000) | (r << 16) | (gg << 8) | b);
                runs(text, 0.6f, metrics.getAscent() - 0.4f);
            }
            color(argb);
            runs(text, 0f, metrics.getAscent() - 1f);
            g.setFont(font);
            g.setTransform(saved);
        }

        /**
         * 2026-11-02：Noto CJK 沒有的字（☠、✧…）一段一段換 DejaVu 畫（這次之前整行換 DejaVu，同一行的中文變成方塊）。
         */
        private void runs(final String text, final float x, final float y) {
            if (font.canDisplayUpTo(text) == -1) {
                g.setFont(font);
                g.drawString(text, x, y);
                return;
            }
            float at = x;
            int i = 0;
            while (i < text.length()) {
                final boolean cjk = font.canDisplay(text.codePointAt(i));
                int j = i;
                while (j < text.length() && font.canDisplay(text.codePointAt(j)) == cjk) {
                    j += Character.charCount(text.codePointAt(j));
                }
                final String part = text.substring(i, j);
                g.setFont(cjk ? font : symbols);
                g.drawString(part, at, y);
                at += g.getFontMetrics().stringWidth(part);
                i = j;
            }
        }

        @Override
        public int width(final String text) {
            return (int) Math.ceil(metrics.stringWidth(text));
        }

        @Override
        public int fontHeight() {
            return 9;
        }

        @Override
        public void clip(final int x0, final int y0, final int x1, final int y1) {
            g.setClip(x0, y0, x1 - x0, y1 - y0);
        }

        @Override
        public void unclip() {
            g.setClip(null);
        }
    }

    static BufferedImage image(final java.util.function.Consumer<AwtCanvas> paint) {
        final BufferedImage image = new BufferedImage(W * SCALE, H * SCALE, BufferedImage.TYPE_INT_RGB);
        final Graphics2D g = image.createGraphics();
        g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
        g.setRenderingHint(RenderingHints.KEY_TEXT_ANTIALIASING, RenderingHints.VALUE_TEXT_ANTIALIAS_ON);
        g.setComposite(AlphaComposite.SrcOver);
        g.scale(SCALE, SCALE);
        g.setColor(new Color(TreePainter.BACKGROUND));
        g.fillRect(0, 0, W, H);
        paint.accept(new AwtCanvas(g));
        g.dispose();
        return image;
    }

    // ---- 假的配點 ----

    static JsonArray array(final Iterable<String> ids) {
        final JsonArray array = new JsonArray();
        ids.forEach(array::add);
        return array;
    }

    /** 從起點往幾個核心配過去（照路徑預覽的走法），回傳配了哪些。 */
    static Set<String> build(final TreeModel.Data data, final String start, final int count) {
        final Set<String> owned = new LinkedHashSet<>();
        final double sx = data.x[data.indexOf(start)];
        final double sy = data.y[data.indexOf(start)];
        final List<Integer> notables = new ArrayList<>();
        for (int i = 0; i < data.size(); i++) {
            if (data.type[i] == 'n' && Math.hypot(data.x[i] - sx, data.y[i] - sy) < 3800) {
                notables.add(i);
            }
        }
        notables.sort((a, b) -> Double.compare(Math.hypot(data.x[a] - sx, data.y[a] - sy), Math.hypot(data.x[b] - sx, data.y[b] - sy)));
        for (int k = 0; k < notables.size() && owned.size() < count; k += 2) {
            final TreeModel model = model(data, start, owned, 0);
            for (final int step : model.path(notables.get(k), false)) {
                owned.add(data.ids[step]);
            }
        }
        return owned;
    }

    static TreeModel model(final TreeModel.Data data, final String start, final Set<String> owned, final int points) {
        final JsonObject state = new JsonObject();
        state.addProperty("kind", data.kind);
        state.addProperty("start", start);
        state.addProperty("class", "witch");
        state.add("owned", array(owned));
        final Set<String> avail = new LinkedHashSet<>();
        final int s = data.indexOf(start);
        final BitSet from = new BitSet();
        from.set(s);
        owned.forEach(id -> from.set(data.indexOf(id)));
        for (int i = from.nextSetBit(0); i >= 0; i = from.nextSetBit(i + 1)) {
            for (final int n : data.neighbours[i]) {
                if (!from.get(n) && !data.isStart(n)) {
                    avail.add(data.ids[n]);
                }
            }
        }
        state.add("avail", array(avail));
        state.addProperty("points", points);
        state.addProperty("refund", "<yellow>右鍵退點（3,200 金幣，你有 48,500）");
        final JsonArray info = new JsonArray();
        info.add("<gray>可用 <white>" + points + "</white>　已配置 <white>" + owned.size());
        info.add("<gray>金幣 <#f5b942>48,500");
        info.add("<gray>退一點 <#f5b942>3,200 金幣");
        info.add("<gray>女巫 Lv.68　<dark_gray>每升一級 +1 點");
        state.add("info", info);
        return new TreeModel(data, TreeModel.parseState(state, data));
    }

    static void chrome(final UiCanvas canvas, final String search, final String... buttons) {
        final int boxWidth = 160;
        canvas.rect(W - boxWidth - 8, 8, W - 8, 26, 0xFF000000);
        canvas.frame(W - boxWidth - 8, 8, W - 8, 26, 0xFFA0A0A0);
        canvas.text(search.isEmpty() ? "搜尋（名稱、說明）" : search, W - boxWidth - 4, 13, search.isEmpty() ? 0xFF707070 : 0xFFE0E0E0, 1, false);
        double x = W - 8;
        for (int i = buttons.length - 1; i >= 0; i--) {
            final int w = Math.max(36, canvas.width(buttons[i]) + 12);
            x -= w;
            UiPanels.button(canvas, buttons[i], x, 30, w, 18);
            x -= 4;
        }
    }

    static void treeScene(final AwtCanvas canvas, final TreeModel model, final Camera camera, final TreePainter.View view, final String search,
                          final List<Integer> hits, final int mx, final int my, final String toast) {
        treeScene(canvas, model, camera, view, search, hits, mx, my, toast, null);
    }

    static void treeScene(final AwtCanvas canvas, final TreeModel model, final Camera camera, final TreePainter.View view, final String search,
                          final List<Integer> hits, final int mx, final int my, final String toast, final TreeGuard.Pending pending) {
        TreePainter.paint(canvas, model, camera, view, 0, 0, W, H);
        final List<String> info = new ArrayList<>(model.state.info);
        if (!view.path.isEmpty()) {
            info.add("<#77aaff>路徑預覽：" + view.path.size() + " 個節點" + ("atlastree".equals(model.data.kind) ? "，共 " + model.cost(view.path) + " 點" : ""));
        }
        if (!hits.isEmpty()) {
            info.add("<#55ffff>搜尋結果 共 " + hits.size() + " 個<dark_gray>（Enter 下一個）");
        }
        final String title = PassiveTitle.of(model.data.kind);
        UiPanels.panel(canvas, "<#f5b942>" + title + "　<white>剩 " + model.state.points + " 點", info, 8, 8, Math.min(260, W / 3));
        UiPanels.hint(canvas, "<gray>滾輪縮放・拖曳移動・<yellow>左鍵<gray>配點（遠的整條配上）・<yellow>右鍵<gray>退點・Enter 跳到下一個搜尋結果", W, H);
        if (toast != null) {
            UiPanels.toast(canvas, toast, W, H, 1);
        }
        if ("atlastree".equals(model.data.kind)) {
            chrome(canvas, search, "全貌", "起點", "星圖", "全部退回", "箱子選單");
        } else {
            chrome(canvas, search, "全貌", "起點", "昇華", "箱子選單");
        }
        if (pending != null) {
            UiPanels.confirm(canvas, pending.lines(), W, H);
        } else if (view.hover >= 0) {
            UiPanels.tooltip(canvas, TreePainter.tooltip(model, view.hover, view), mx, my, W, H);
        }
    }

    /** 標題（跟 PassiveTreeScreen.title 一樣）。 */
    static final class PassiveTitle {
        static String of(final String kind) {
            return switch (kind) {
                case "atlastree" -> "星圖天賦";
                case "asc" -> "昇華天賦";
                default -> "天賦樹";
            };
        }
    }

    public static void main(final String[] args) throws Exception {
        final Path in = Path.of(args[0]);
        final Path out = Path.of(args[1]);
        Files.createDirectories(out);
        // ---- 天賦樹 ----
        final byte[] raw = Files.readAllBytes(in.resolve("passive.json"));
        final TreeModel.Data data = TreeModel.parseData("passive", UiWire.hash(raw), new String(raw, StandardCharsets.UTF_8));
        final String start = data.starts.get("witch");
        final Set<String> owned = build(data, start, 70);
        final TreeModel model = model(data, start, owned, 9);
        // 1. 全貌＋搜尋
        {
            final Camera camera = new Camera();
            camera.viewport(0, 0, W, H);
            camera.fit(data.minX - 160, data.minY - 160, data.maxX + 160, data.maxY + 160, W, H - 50, 10);
            final TreePainter.View view = new TreePainter.View();
            final List<Integer> hits = model.search("最大生命");
            hits.forEach(view.hits::set);
            ImageIO.write(image(c -> treeScene(c, model, camera, view, "最大生命", hits, -1, -1, null)), "png",
                    out.resolve("preview-14d-tree-overview.png").toFile());
        }
        final int s = data.indexOf(start);
        // 2. 放大局部＋滑鼠說明（指著一個能配的核心或旁邊的節點）
        {
            final Camera camera = new Camera(data.x[s], data.y[s], 22 / data.unit);
            camera.viewport(0, 0, W, H);
            int target = -1;
            double best = Double.MAX_VALUE;
            for (int i = 0; i < data.size(); i++) {
                if ((data.type[i] == 'n') && model.state.owned.get(i)) {
                    final double d = Math.hypot(data.x[i] - data.x[s], data.y[i] - data.y[s]);
                    if (d < best && d > 600) {
                        best = d;
                        target = i;
                    }
                }
            }
            camera.center((data.x[s] + data.x[target]) / 2, (data.y[s] + data.y[target]) / 2);
            final TreePainter.View view = new TreePainter.View();
            view.hover = target;
            final int mx = (int) camera.toScreenX(data.x[target]);
            final int my = (int) camera.toScreenY(data.y[target]);
            ImageIO.write(image(c -> treeScene(c, model, camera, view, "", List.of(), mx, my, null)), "png",
                    out.resolve("preview-14d-tree-zoom.png").toFile());
            // 2026-11-02：右鍵退點的確認（花金幣）
            final JsonObject paid = new JsonObject();
            paid.addProperty("start", start);
            paid.addProperty("class", "witch");
            paid.add("owned", array(owned));
            paid.addProperty("points", 9);
            paid.addProperty("rcost", 3200);
            paid.addProperty("gold", 48500);
            paid.add("info", array(model.state.info));
            final TreeModel paidModel = new TreeModel(data, TreeModel.parseState(paid, data));
            final TreeGuard.Pending refund = TreeGuard.refund(paidModel, target);
            ImageIO.write(image(c -> treeScene(c, paidModel, camera, view, "", List.of(), mx, my, null, refund)), "png",
                    out.resolve("preview-14d-tree-refund.png").toFile());
        }
        // 3. 路徑預覽：指著一個遠一點、還沒連到的核心
        {
            int target = -1;
            for (int i = 0; i < data.size(); i++) {
                if (data.type[i] == 'n' && !model.state.owned.get(i)) {
                    final List<Integer> path = model.path(i, false);
                    if (path.size() >= 5 && path.size() <= 7) {
                        target = i;
                        break;
                    }
                }
            }
            final TreePainter.View view = new TreePainter.View();
            view.hover = target;
            view.setPath(model.path(target, false));
            // 2026-11-02：「整條路加起來」是插件算的：先寫出路徑（path.txt），插件那邊算好 sum.json 再跑一次就畫得出來
            final List<String> pathIds = new ArrayList<>();
            view.path.forEach(i -> pathIds.add(data.ids[i]));
            Files.writeString(in.resolve("path.txt"), String.join(",", pathIds));
            if (Files.isRegularFile(in.resolve("sum.json"))) {
                view.summary = new ArrayList<>();
                JsonParser.parseString(Files.readString(in.resolve("sum.json"))).getAsJsonArray().forEach(e -> view.summary.add(e.getAsString()));
            }
            final int first = view.path.getFirst();
            final Camera camera = new Camera((data.x[first] + data.x[target]) / 2, (data.y[first] + data.y[target]) / 2, 12 / data.unit);
            camera.viewport(0, 0, W, H);
            final int mx = (int) camera.toScreenX(data.x[target]);
            final int my = (int) camera.toScreenY(data.y[target]);
            ImageIO.write(image(c -> treeScene(c, model, camera, view, "", List.of(), mx, my, "<green>配了 3 點：寒冰之心")), "png",
                    out.resolve("preview-14d-tree-path.png").toFile());
        }
        // ---- 星圖天賦 ----
        {
            final byte[] atRaw = Files.readAllBytes(in.resolve("atlastree.json"));
            final TreeModel.Data at = TreeModel.parseData("atlastree", UiWire.hash(atRaw), new String(atRaw, StandardCharsets.UTF_8));
            final Set<String> atOwned = new LinkedHashSet<>();
            final JsonObject seed = new JsonObject();
            seed.addProperty("start", "@root");
            TreeModel tmp = new TreeModel(at, TreeModel.parseState(seed, at));
            final List<Integer> order = new ArrayList<>();
            for (int i = 0; i < at.size(); i++) {
                if (at.type[i] == 'n' && at.x[i] >= -100 && at.y[i] <= 100) { // 右上那一區點出去
                    order.add(i);
                }
            }
            order.sort((p, q) -> Double.compare(Math.hypot(at.x[p], at.y[p]), Math.hypot(at.x[q], at.y[q])));
            for (final int i : order) {
                if (atOwned.size() >= 26) {
                    break;
                }
                {
                    for (final int step : tmp.path(i, false)) {
                        atOwned.add(at.ids[step]);
                    }
                    final JsonObject st = new JsonObject();
                    st.addProperty("start", "@root");
                    st.add("owned", array(atOwned));
                    tmp = new TreeModel(at, TreeModel.parseState(st, at));
                }
            }
            final JsonObject state = new JsonObject();
            state.addProperty("kind", "atlastree");
            state.addProperty("start", "@root");
            state.add("owned", array(atOwned));
            final Set<String> avail = new LinkedHashSet<>();
            final Set<String> poor = new LinkedHashSet<>();
            final BitSet from = new BitSet();
            from.set(0);
            atOwned.forEach(id -> from.set(at.indexOf(id)));
            for (int i = from.nextSetBit(0); i >= 0; i = from.nextSetBit(i + 1)) {
                for (final int n : at.neighbours[i]) {
                    if (!from.get(n)) {
                        (at.cost[n] <= 4 ? avail : poor).add(at.ids[n]);
                    }
                }
            }
            state.add("avail", array(avail));
            state.add("poor", array(poor));
            state.addProperty("points", 4);
            state.addProperty("refund", "<yellow>右鍵退回（免費，連不回中心的一起退）");
            final JsonArray info = new JsonArray();
            info.add("<gray>天賦點：還沒用 <white>4<gray> / 總共 58");
            info.add("<gray>第 1 組使用中<dark_gray>（換組用箱子選單，月卡）");
            info.add("<green>• 地圖的物品稀有度 +18%");
            info.add("<green>• 地圖怪掉地圖的機率增加 20%");
            info.add("<green>• 怪物群大小 +6%");
            state.add("info", info);
            final TreeModel atlasTree = new TreeModel(at, TreeModel.parseState(state, at));
            final Camera camera = new Camera();
            camera.viewport(0, 0, W, H);
            camera.fit(at.minX - 160, at.minY - 160, at.maxX + 160, at.maxY + 160, W, H - 50, 10);
            camera.zoomAt(W / 2.0, H / 2.0, 1.6);
            final TreePainter.View view = new TreePainter.View();
            int target = -1;
            for (int i = 0; i < at.size(); i++) {
                if (at.type[i] == 'n' && !atlasTree.state.owned.get(i) && atlasTree.path(i, false).size() >= 2) {
                    target = i;
                    break;
                }
            }
            view.hover = target;
            view.setPath(atlasTree.path(target, false));
            final int mx = (int) camera.toScreenX(at.x[target]);
            final int my = (int) camera.toScreenY(at.y[target]);
            ImageIO.write(image(c -> treeScene(c, atlasTree, camera, view, "", List.of(), mx, my, null)), "png",
                    out.resolve("preview-14d-atlastree.png").toFile());
        }
        // ---- 星圖 ----
        {
            final AtlasModel atlas = new AtlasModel(JsonParser.parseString(Files.readString(in.resolve("atlas.json"))).getAsJsonObject());
            final Camera camera = new Camera(HexGrid.x(2, -1), HexGrid.y(2, -1), 30);
            camera.viewport(0, 0, W, H);
            AtlasModel.Cell hover = null;
            for (final AtlasModel.Cell cell : atlas.cells) {
                if (cell.chosen()) {
                    hover = cell;
                }
            }
            final AtlasModel.Cell shown = hover;
            final int mx = (int) camera.toScreenX(HexGrid.x(shown.q(), shown.r()));
            final int my = (int) camera.toScreenY(HexGrid.y(shown.q(), shown.r()));
            ImageIO.write(image(c -> {
                AtlasPainter.paint(c, atlas, camera, shown, 0, 0, W, H);
                final List<String> info = new ArrayList<>(atlas.info);
                info.add("<dark_gray>滑鼠：" + HexGrid.where(shown.q(), shown.r()));
                UiPanels.panel(c, "<#f5b942>星圖", info, 8, 8, Math.min(300, W / 3));
                UiPanels.hint(c, "<gray>滾輪縮放・拖曳移動・<yellow>左鍵<gray>挑地圖開這一格・<yellow>Shift＋左鍵<gray>只指定・<yellow>右鍵<gray>取消指定", W, H);
                double x = W - 8;
                final String[] buttons = {"全貌", "營地", "上次的地圖", "星圖天賦", "箱子選單"};
                for (int i = buttons.length - 1; i >= 0; i--) {
                    final int w = Math.max(36, c.width(buttons[i]) + 12);
                    x -= w;
                    UiPanels.button(c, buttons[i], x, 8, w, 18);
                    x -= 4;
                }
                UiPanels.tooltip(c, AtlasPainter.tooltip(shown), mx, my, W, H);
            }), "png", out.resolve("preview-14d-atlas.png").toFile());
        }
        System.out.println("預覽圖：" + out);
    }
}
