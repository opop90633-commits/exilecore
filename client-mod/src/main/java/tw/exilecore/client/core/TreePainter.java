package tw.exilecore.client.core;

import java.util.ArrayList;
import java.util.BitSet;
import java.util.List;

/**
 * 2026-11-02 照站長（D 組）：天賦樹、星圖天賦、昇華天賦的原生畫面怎麼畫（遊戲與預覽圖同一份）。純 Java。
 * 節點照真正的座標畫、連線是直線；顏色跟資源包版的星盤一樣：已配金色、能配亮白、路徑預覽藍色、點數不夠橘色、連不到暗灰、
 * 搜尋結果青色的圈。放大到看得清楚時核心、基石寫名字。
 * 2026-11-02 照站長：觀星「不見之途」專屬的節點用深紫（還不能配）／亮紫（能配）加星光外框（已配照舊金色，外框留著），
 * 虛實交織的錨點基石畫範圍圈；這次之前跟一般天賦一樣、也沒有範圍圈。箱子選單（PassiveMenu）、網頁規劃器同一組顏色。
 */
public final class TreePainter {

    public static final int GOLD = 0xFFF5C04A;
    public static final int EDGE_GOLD = 0xFFE6A82E;
    public static final int BRIGHT = 0xFFE8E8F2;
    public static final int EDGE_LIT = 0xFF9C9CB8;
    public static final int DIM = 0xFF5E5E74;
    public static final int EDGE_DIM = 0xFF33354A;
    public static final int BLUE = 0xFF6FA8FF;
    public static final int EDGE_BLUE = 0xFF5A8EE8;
    public static final int CYAN = 0xFF55FFFF;
    public static final int ORANGE = 0xFFFFAA55;
    public static final int BLOCKED = 0xFF7A4A4A;
    public static final int SOCKET = 0xFFC77DFF;
    public static final int BACKGROUND = 0xFF07080F;
    /** 2026-11-02：不見之途專屬：還不能配的深紫、能配的亮紫、星光外框。 */
    public static final int UNSEEN_DEEP = 0xFF4B1D8F;
    public static final int UNSEEN_LIT = 0xFF8A5CFF;
    public static final int UNSEEN_STAR = 0xFFE9DEFF;
    /** 2026-11-02：虛實交織的範圍圈。 */
    public static final int ENTWINED_RING = 0x99B48CFF;
    /** 2026-11-02：範圍珠寶的範圍圈（外圈亮、內圈淡）。 */
    public static final int JEWEL_RING = 0xCCC77DFF;
    public static final int JEWEL_RING_INNER = 0x55C77DFF;

    /** 畫面上這一刻的狀態（滑鼠指著哪個、路徑預覽、搜尋結果）。 */
    public static final class View {
        public int hover = -1;
        public List<Integer> path = List.of();
        public final BitSet pathBits = new BitSet();
        public BitSet hits = new BitSet();
        /** 現在跳到的搜尋結果（畫比較粗的圈）。 */
        public int focus = -1;
        /** 2026-11-02：路徑預覽「整條路加起來的能力」（伺服器算的；null = 還在問）。 */
        public List<String> summary = List.of();

        public void setPath(final List<Integer> path) {
            this.path = path;
            pathBits.clear();
            for (final int i : path) {
                pathBits.set(i);
            }
        }
    }

    private TreePainter() {
    }

    /** 節點的半徑（世界單位）。 */
    public static double radius(final TreeModel.Data data, final int i) {
        final double u = data.unit;
        return switch (data.type[i]) {
            case 'n' -> 0.34 * u;
            case 'k' -> 0.48 * u;
            case 'o' -> 0.52 * u;
            case 'j' -> 0.32 * u;
            default -> 0.22 * u;
        };
    }

    /** 畫面上最小幾個像素（縮小看全貌時也看得到）。 */
    static double minPixels(final char type) {
        return switch (type) {
            case 'n' -> 1.8;
            case 'k', 'o' -> 2.8;
            case 'j' -> 2.0;
            default -> 1.0;
        };
    }

    /** 畫面上的半徑（像素）。 */
    public static double pixels(final TreeModel.Data data, final int i, final Camera camera) {
        return Math.max(minPixels(data.type[i]), radius(data, i) * camera.scale);
    }

    public static int nodeColor(final TreeModel model, final int i, final TreeModel.NodeState state) {
        final TreeModel.Data data = model.data;
        if (state == TreeModel.NodeState.OWNED && data.type[i] == 'j') {
            return SOCKET;
        }
        if (state == TreeModel.NodeState.OWNED) {
            // 2026-11-02 照站長：配了的屬性點照選的屬性上色（力量紅、敏捷綠、智慧藍）；這次之前一律金色
            final int attr = attributeColor(model.state.attrs.get(data.ids[i]));
            if (attr != 0) {
                return attr;
            }
        }
        if (data.unseen[i]) {
            switch (state) {
                case AVAILABLE, POOR -> {
                    return UNSEEN_LIT;
                }
                case BLOCKED, LOCKED -> {
                    return UNSEEN_DEEP;
                }
                default -> {
                }
            }
        }
        return switch (state) {
            case OWNED -> GOLD;
            case PATH -> BLUE;
            case AVAILABLE -> BRIGHT;
            case POOR -> ORANGE;
            case BLOCKED -> BLOCKED;
            case LOCKED -> DIM;
        };
    }

    /** 屬性點選的屬性的顏色（PoE 的力量紅、敏捷綠、智慧藍；智慧之路多的三種：傷害橘、防禦灰白、消耗效率紫）；沒選 0。 */
    public static int attributeColor(final String key) {
        if (key == null) {
            return 0;
        }
        return switch (key) {
            case "str" -> 0xFFFF5A4A;
            case "dex" -> 0xFF5AE05A;
            case "int" -> 0xFF5A9CFF;
            case "dmg" -> 0xFFFF9A3C;
            case "def" -> 0xFFD8D8E8;
            case "cost" -> 0xFFC48CFF;
            default -> 0;
        };
    }

    static int edgeColor(final TreeModel.NodeState a, final TreeModel.NodeState b) {
        if (a == TreeModel.NodeState.OWNED && b == TreeModel.NodeState.OWNED) {
            return EDGE_GOLD;
        }
        final boolean onA = a == TreeModel.NodeState.OWNED || a == TreeModel.NodeState.PATH;
        final boolean onB = b == TreeModel.NodeState.OWNED || b == TreeModel.NodeState.PATH;
        if (onA && onB) {
            return EDGE_BLUE;
        }
        final boolean litA = a == TreeModel.NodeState.AVAILABLE || a == TreeModel.NodeState.POOR;
        final boolean litB = b == TreeModel.NodeState.AVAILABLE || b == TreeModel.NodeState.POOR;
        if (a == TreeModel.NodeState.OWNED && litB || b == TreeModel.NodeState.OWNED && litA) {
            return EDGE_LIT;
        }
        return EDGE_DIM;
    }

    static int rank(final int color) {
        return color == EDGE_GOLD ? 3 : color == EDGE_BLUE ? 2 : color == EDGE_LIT ? 1 : 0;
    }

    /** 畫整棵樹（x0..x1, y0..y1 是畫面的範圍，外面的不畫）。 */
    public static void paint(final UiCanvas canvas, final TreeModel model, final Camera camera, final View view, final int x0, final int y0,
                             final int x1, final int y1) {
        final TreeModel.Data data = model.data;
        final int n = data.size();
        final TreeModel.NodeState[] states = new TreeModel.NodeState[n];
        final double[] sx = new double[n];
        final double[] sy = new double[n];
        for (int i = 0; i < n; i++) {
            states[i] = model.stateOf(i, view.pathBits);
            sx[i] = camera.toScreenX(data.x[i]);
            sy[i] = camera.toScreenY(data.y[i]);
        }
        final double width = Math.max(1, Math.min(4, data.unit * 0.07 * camera.scale));
        // 2026-11-02 照站長：範圍珠寶、永恆珠寶的範圍（孔為中心的圈，裡面淡淡塗一層）；這次之前沒畫
        for (final java.util.Map.Entry<String, Double> entry : model.state.jewelRadius.entrySet()) {
            final int socket = data.indexOf(entry.getKey());
            if (socket < 0 || !model.visible(socket)) {
                continue;
            }
            final double r = entry.getValue() * camera.scale;
            circle(canvas, sx[socket], sy[socket], r, Math.max(1, width * 0.8), JEWEL_RING, x0, y0, x1, y1);
            circle(canvas, sx[socket], sy[socket], r - Math.max(1.5, width), Math.max(1, width * 0.5), JEWEL_RING_INNER, x0, y0, x1, y1);
        }
        // 2026-11-02 照站長：虛實交織的範圍圈（錨點基石的中半徑；圈裡沒相連的非基石可以直接配）
        if (data.jumpRadius > 0) {
            for (int i = model.state.anchors.nextSetBit(0); i >= 0; i = model.state.anchors.nextSetBit(i + 1)) {
                circle(canvas, sx[i], sy[i], data.jumpRadius * camera.scale, Math.max(1, width * 0.6), ENTWINED_RING, x0, y0, x1, y1);
            }
        }
        // 連線：暗的先畫，亮的蓋在上面
        for (int pass = 0; pass < 4; pass++) {
            for (int k = 0; k + 1 < data.links.length; k += 2) {
                final int a = data.links[k];
                final int b = data.links[k + 1];
                if (!model.visible(a) || !model.visible(b)) {
                    continue;
                }
                if (Math.max(sx[a], sx[b]) < x0 || Math.min(sx[a], sx[b]) > x1 || Math.max(sy[a], sy[b]) < y0 || Math.min(sy[a], sy[b]) > y1) {
                    continue;
                }
                final int color = edgeColor(states[a], states[b]);
                if (rank(color) != pass) {
                    continue;
                }
                canvas.line(sx[a], sy[a], sx[b], sy[b], color == EDGE_DIM ? Math.max(1, width * 0.7) : width, color);
            }
        }
        // 節點
        for (int i = 0; i < n; i++) {
            if (!model.visible(i)) {
                continue;
            }
            final double r = pixels(data, i, camera);
            if (sx[i] + r < x0 || sx[i] - r > x1 || sy[i] + r < y0 || sy[i] - r > y1) {
                continue;
            }
            final TreeModel.NodeState state = states[i];
            final int color = nodeColor(model, i, state);
            final char type = data.type[i];
            if (state == TreeModel.NodeState.OWNED && r >= 2.5) {
                canvas.dot(sx[i], sy[i], r * 1.6, (color & 0xFFFFFF) | 0x40000000); // 光暈
            } else if (state == TreeModel.NodeState.PATH && r >= 2.5) {
                canvas.dot(sx[i], sy[i], r * 1.5, 0x406FA8FF);
            }
            if (view.hits.get(i)) {
                final double ring = Math.max(r + 2, r * 1.45) + (i == view.focus ? 1.5 : 0);
                canvas.dot(sx[i], sy[i], ring, i == view.focus ? 0xFFFFFFFF : CYAN);
                canvas.dot(sx[i], sy[i], ring - Math.max(1, r * 0.25), BACKGROUND);
            }
            if (data.unseen[i]) {
                // 2026-11-02：不見之途專屬的星光外框（細圈＋四個小星點）
                final double ring = Math.max(r + 1.5, r * 1.3);
                canvas.dot(sx[i], sy[i], ring, UNSEEN_STAR);
                canvas.dot(sx[i], sy[i], ring - Math.max(0.8, r * 0.14), BACKGROUND);
                if (r >= 3) {
                    final double far = ring + Math.max(1.5, r * 0.25);
                    final double size = Math.max(1, r * 0.22);
                    for (int k = 0; k < 4; k++) {
                        final double a = Math.PI / 4 + k * Math.PI / 2;
                        canvas.quad(sx[i] + Math.cos(a) * far, sy[i] + Math.sin(a) * far, size, size, Math.PI / 4, UNSEEN_STAR);
                    }
                }
            }
            // 2026-11-09 照站長「武器組天賦用顯眼點的顏色」：第 I 組亮紅、第 II 組亮青；正在用那一組實心粗圈，另一組虛線圈（切過去才生效）
            final int weaponSet = model.weaponSetOf(i);
            if (weaponSet != 0) {
                final int ringColor = weaponSet == 2 ? TreeModel.WEAPON_SET_2 : TreeModel.WEAPON_SET_1;
                final double ring = Math.max(r + 2.5, r * 1.5);
                if (weaponSet == model.state.weaponSet) {
                    canvas.dot(sx[i], sy[i], ring, ringColor);
                    canvas.dot(sx[i], sy[i], ring - Math.max(1.2, r * 0.3), BACKGROUND);
                } else {
                    dashedCircle(canvas, sx[i], sy[i], ring, Math.max(1, r * 0.18), ringColor, x0, y0, x1, y1);
                }
            }
            if (i == view.hover) {
                canvas.dot(sx[i], sy[i], r + Math.max(1.5, r * 0.3), 0xFFFFFFFF);
            }
            if (type == 'n' || type == 'k' || type == 'o' || type == 'j') {
                // 核心、基石、起點、珠寶孔：外框（主幹有顏色的照主幹的顏色）
                final int frame = data.color[i] != 0 ? 0xFF000000 | data.color[i]
                        : type == 'k' ? 0xFFB070D0 : type == 'j' ? 0xFF9A5AC0 : type == 'o' ? 0xFF77CCFF : 0xFFB89A50;
                canvas.dot(sx[i], sy[i], r, state == TreeModel.NodeState.LOCKED ? darken(frame) : frame);
                canvas.dot(sx[i], sy[i], r * 0.72, color);
                if (type == 'j' && model.state.jewels.get(i)) {
                    canvas.dot(sx[i], sy[i], r * 0.4, 0xFFFFFFFF);
                }
            } else {
                canvas.dot(sx[i], sy[i], r, color);
            }
        }
        // 名字：放大到看得清楚的時候
        for (int i = 0; i < n; i++) {
            if (!model.visible(i)) {
                continue;
            }
            final char type = data.type[i];
            final double r = pixels(data, i, camera);
            final boolean big = type == 'n' || type == 'k' || type == 'o';
            final boolean few = n <= 400; // 星圖天賦、昇華天賦：節點少，小的也寫
            if (!(big && r >= 5.5 || few && r >= 6)) {
                continue;
            }
            if (sx[i] < x0 - 60 || sx[i] > x1 + 60 || sy[i] < y0 - 20 || sy[i] > y1 + 20) {
                continue;
            }
            final String label = Rich.plain(model.name(i));
            final double scale = r >= 10 ? 1.0 : 0.75;
            final double w = canvas.width(label) * scale;
            final int color = states[i] == TreeModel.NodeState.OWNED ? 0xFFF5D58A : data.unseen[i] ? 0xFFD9C8FF
                    : states[i] == TreeModel.NodeState.LOCKED ? 0xFF8A8AA0 : 0xFFE0E0E8;
            canvas.text(label, sx[i] - w / 2, sy[i] + r + 2, color, scale, true);
        }
    }

    /** 2026-11-02：畫一個圈（48 段直線；整個圈在畫面外就不畫）。 */
    static void circle(final UiCanvas canvas, final double cx, final double cy, final double radius, final double width, final int argb,
                       final int x0, final int y0, final int x1, final int y1) {
        if (radius < 2 || cx + radius < x0 || cx - radius > x1 || cy + radius < y0 || cy - radius > y1) {
            return;
        }
        final int segments = 48;
        for (int k = 0; k < segments; k++) {
            final double a = 2 * Math.PI * k / segments;
            final double b = 2 * Math.PI * (k + 1) / segments;
            canvas.line(cx + Math.cos(a) * radius, cy + Math.sin(a) * radius, cx + Math.cos(b) * radius, cy + Math.sin(b) * radius, width, argb);
        }
    }

    /** 2026-11-09：虛線圈（另一組的武器組天賦）。 */
    static void dashedCircle(final UiCanvas canvas, final double cx, final double cy, final double radius, final double width, final int argb,
                             final int x0, final int y0, final int x1, final int y1) {
        if (radius < 2 || cx + radius < x0 || cx - radius > x1 || cy + radius < y0 || cy - radius > y1) {
            return;
        }
        final int segments = 24;
        for (int k = 0; k < segments; k += 2) {
            final double a = 2 * Math.PI * k / segments;
            final double b = 2 * Math.PI * (k + 1) / segments;
            canvas.line(cx + Math.cos(a) * radius, cy + Math.sin(a) * radius, cx + Math.cos(b) * radius, cy + Math.sin(b) * radius, width, argb);
        }
    }

    static int darken(final int argb) {
        final int r = ((argb >> 16) & 0xFF) * 45 / 100;
        final int g = ((argb >> 8) & 0xFF) * 45 / 100;
        final int b = (argb & 0xFF) * 45 / 100;
        return (argb & 0xFF000000) | (r << 16) | (g << 8) | b;
    }

    // ---- 說明 ----

    static String typeName(final char type) {
        return switch (type) {
            case 'n' -> "<#f5b942>核心";
            case 'k' -> "<#ff55ff>基石";
            case 'o' -> "<#77ccff>起點";
            case 'j' -> "<#c77dff>珠寶孔";
            default -> "<gray>小點";
        };
    }

    static String titleColor(final char type) {
        return switch (type) {
            case 'n' -> "<#f5b942>";
            case 'k' -> "<#ff55ff>";
            case 'o' -> "<#77ccff>";
            case 'j' -> "<#c77dff>";
            default -> "<white>";
        };
    }

    /** 滑鼠移到節點上的說明（第一行是標題）。 */
    public static List<String> tooltip(final TreeModel model, final int i, final View view) {
        final TreeModel.Data data = model.data;
        final TreeModel.State state = model.state;
        final List<String> lines = new ArrayList<>();
        final TreeModel.NodeState nodeState = model.stateOf(i, view.pathBits);
        final String kind = data.kind;
        final String id = data.ids[i];
        if ("asc".equals(kind)) {
            // 昇華天賦：說明（含能不能配、退點的花費）插件寫好了
            lines.add((nodeState == TreeModel.NodeState.OWNED ? "<green>✔ " : "") + model.name(i));
            lines.addAll(model.lines(i));
            return lines;
        }
        final boolean replaced = state.over.containsKey(id);
        if ("atlastree".equals(kind)) {
            final String color = switch (nodeState) {
                case OWNED -> "<green>✔ ";
                case AVAILABLE -> "<yellow>";
                case POOR -> "<#ffaa55>";
                default -> "<gray>";
            };
            lines.add(color + model.name(i) + (data.type[i] == 'k' ? " <#c48bff>✦" : data.type[i] == 'n' ? " <#f5b942>★" : ""));
            lines.addAll(model.lines(i));
        } else {
            lines.add(titleColor(data.type[i]) + model.name(i));
            lines.add(typeName(data.type[i]) + (view.hits.get(i) ? " <#55ffff>★ 搜尋結果" : ""));
            if (data.unseen[i]) {
                lines.add("<#b48cff>✧ 不見之途專屬天賦（觀星）");
            }
            for (final String line : model.lines(i)) {
                lines.add(replaced ? line : "<white>" + line);
            }
            if (!replaced) {
                for (final String line : data.explain.get(i)) {
                    lines.add("<#9fb4c7>" + line);
                }
            }
        }
        // 2026-11-09 照站長「client mod 的天賦畫面也要能點武器組的組」：武器組天賦寫是哪一組、生效了沒
        if (state.weaponSet > 0) {
            final boolean one = state.weapon1.get(i);
            final boolean two = state.weapon2.get(i);
            for (int set = 1; set <= 2; set++) {
                if (set == 1 ? one : two) {
                    lines.add(TreeModel.weaponSetTag(set) + "⚔ " + TreeModel.weaponSetName(set) + "的武器組天賦"
                            + (set == state.weaponSet ? " <dark_gray>（只在這一組武器生效）" : " <gray>（切過去才生效）"));
                }
            }
        }
        lines.add("");
        final int points = state.points;
        if (data.isStart(i)) {
            if ("atlastree".equals(kind)) {
                lines.removeLast(); // 星核的說明已經寫了
            } else {
                lines.add(i == state.start ? "<gray>你的職業起點，永遠已配置" : "<dark_gray>別的職業的起點");
            }
            return lines;
        }
        switch (nodeState) {
            case OWNED -> {
                final String use = state.use.get(id);
                if (use != null && !use.isEmpty()) {
                    lines.add(use);
                }
                if (state.fixed.get(i)) {
                    lines.add("<green>已配置 <dark_gray>（退掉會切斷路徑）");
                } else {
                    lines.add(state.refund);
                }
            }
            case AVAILABLE -> {
                if ("atlastree".equals(kind)) {
                    lines.add("<yellow>左鍵點<gray>（要 " + data.cost[i] + " 點，你還有 " + points + " 點）");
                } else {
                    lines.add(points > 0 ? "<yellow>左鍵配置（剩 " + points + " 點）" : "<red>天賦點不夠，升級會給");
                    if (model.jump(i)) {
                        lines.add("<#c9b3ff>虛實交織：在已配基石的範圍內，不用相連就能配");
                    }
                    if (data.type[i] == 'j') {
                        lines.add("<dark_gray>配了之後才能放珠寶");
                    }
                }
            }
            case POOR -> lines.add("<red>天賦點不夠：要 " + data.cost[i] + " 點，你還有 " + points + " 點");
            case BLOCKED -> lines.add(data.unseen[i] ? "<red>只有觀星配了昇華天賦「不見之途」才能配"
                    : "<red>被解鎖條件擋住：要先配說明寫的核心（或觀星的「不見之途」）");
            case PATH, LOCKED -> {
                final List<Integer> path = view.hover == i ? view.path : model.path(i, false);
                if (model.jump(i)) {
                    lines.add("<#c9b3ff>虛實交織：在已配基石的範圍內，不用相連就能配");
                } else if (path.size() > 1 && model.jump(path.getFirst())) {
                    lines.add("<#c9b3ff>虛實交織：從範圍內的跳點接過去");
                }
                if (path.isEmpty()) {
                    lines.add("<dark_gray>走不到那裡");
                } else if ("atlastree".equals(kind)) {
                    final int cost = model.cost(path);
                    lines.add(cost <= points ? "<#77aaff>左鍵：把路上的 " + path.size() + " 個一起點（共 " + cost + " 點，你還有 " + points + " 點）"
                            : "<red>路上 " + path.size() + " 個共 " + cost + " 點，你還有 " + points + " 點");
                } else {
                    lines.add(path.size() <= points ? "<#77aaff>左鍵：整條路配上（共 " + path.size() + " 點，剩 " + points + "）"
                            : "<red>這條路要 " + path.size() + " 點，你只有 " + points + " 點");
                }
                if (view.hover == i && path.size() > 1) {
                    // 2026-11-02 照站長：整條路加起來的能力（伺服器照箱子選單那一段算的）
                    if (view.summary == null) {
                        lines.add("");
                        lines.add("<#77aaff>整條路加起來：<dark_gray>計算中…");
                    } else if (!view.summary.isEmpty()) {
                        lines.add("");
                        lines.addAll(view.summary);
                    }
                }
            }
            default -> {
            }
        }
        return lines;
    }
}
