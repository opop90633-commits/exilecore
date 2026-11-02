package tw.exilecore.client.core;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * 2026-11-02 照站長（D 組）：星圖原生畫面的資料（插件 AtlasFeed 送的「看得到的格子」）。純 Java（測試用）。
 * 每個角色的星圖是程序生成的，沒有靜態資料：每次都帶營地、解鎖的格子與旁邊的迷霧，每格的名稱、說明是插件照箱子選單寫好的。
 */
public final class AtlasModel {

    /** 一格。kind：camp、map、boss、tower、citadel、fog；ring：corrupt、purify、tablet、range（沒有是空字串）。 */
    public record Cell(int q, int r, String kind, boolean done, int tier, String ring, boolean chosen, String name,
                       List<String> lore) {
        public boolean fog() {
            return "fog".equals(kind);
        }
    }

    public final List<Cell> cells;
    /** 塔（完成的與看得到還沒打的）的位置與範圍的半徑：畫塔範圍的大外框。 */
    public final List<int[]> towers;
    public final int radius;
    public final Map<Long, Cell> byKey;
    public final int[] focus;
    public final List<String> info;
    public final int points;
    public final String message;
    public final boolean open;
    /** 格子太多、只送了離中心近的。 */
    public final boolean cut;
    public final double minX;
    public final double minY;
    public final double maxX;
    public final double maxY;

    public AtlasModel(final JsonObject root) {
        cells = new ArrayList<>();
        byKey = new HashMap<>();
        double x0 = 0;
        double y0 = 0;
        double x1 = 0;
        double y1 = 0;
        final JsonArray array = root.has("cells") ? root.getAsJsonArray("cells") : new JsonArray();
        for (final JsonElement element : array) {
            final JsonObject object = element.getAsJsonObject();
            final int q = object.get("q").getAsInt();
            final int r = object.get("r").getAsInt();
            final Cell cell = new Cell(q, r, string(object, "k", "map"), bool(object, "done"), object.has("t") ? object.get("t").getAsInt() : 0,
                    string(object, "ring", ""), bool(object, "ch"), string(object, "n", ""), TreeModel.strings(object.get("l")));
            cells.add(cell);
            byKey.put(key(q, r), cell);
            x0 = Math.min(x0, HexGrid.x(q, r));
            y0 = Math.min(y0, HexGrid.y(q, r));
            x1 = Math.max(x1, HexGrid.x(q, r));
            y1 = Math.max(y1, HexGrid.y(q, r));
        }
        minX = x0;
        minY = y0;
        maxX = x1;
        maxY = y1;
        if (root.has("focus") && root.get("focus").isJsonArray() && root.getAsJsonArray("focus").size() == 2) {
            focus = new int[] {root.getAsJsonArray("focus").get(0).getAsInt(), root.getAsJsonArray("focus").get(1).getAsInt()};
        } else {
            focus = new int[] {0, 0};
        }
        towers = new ArrayList<>();
        if (root.has("towers") && root.get("towers").isJsonArray()) {
            for (final JsonElement element : root.getAsJsonArray("towers")) {
                final JsonArray at = element.getAsJsonArray();
                towers.add(new int[] {at.get(0).getAsInt(), at.get(1).getAsInt()});
            }
        }
        radius = root.has("radius") ? root.get("radius").getAsInt() : 0;
        info = TreeModel.strings(root.get("info"));
        points = root.has("points") ? root.get("points").getAsInt() : 0;
        message = string(root, "msg", "");
        open = bool(root, "open");
        cut = bool(root, "cut");
    }

    public static long key(final int q, final int r) {
        return ((long) q << 32) ^ (r & 0xffffffffL);
    }

    public Cell at(final int q, final int r) {
        return byKey.get(key(q, r));
    }

    /** 這個座標在不在某座塔的範圍內（營地不算，跟箱子選單一樣）。空格、迷霧也算，框才連得起來。 */
    public boolean inRange(final int q, final int r) {
        if (q == 0 && r == 0) {
            return false;
        }
        for (final int[] tower : towers) {
            if (HexGrid.distance(q - tower[0], r - tower[1]) <= radius) {
                return true;
            }
        }
        return false;
    }

    /** 塔範圍內的所有座標（不重複）。 */
    public List<int[]> rangeCells() {
        final List<int[]> out = new ArrayList<>();
        final java.util.Set<Long> seen = new java.util.HashSet<>();
        for (final int[] tower : towers) {
            for (int dq = -radius; dq <= radius; dq++) {
                for (int dr = Math.max(-radius, -dq - radius); dr <= Math.min(radius, -dq + radius); dr++) {
                    final int q = tower[0] + dq;
                    final int r = tower[1] + dr;
                    if (inRange(q, r) && seen.add(key(q, r))) {
                        out.add(new int[] {q, r});
                    }
                }
            }
        }
        return out;
    }

    /** (q, r) 往第 direction 個方向要不要畫塔範圍的框（自己在範圍內、鄰居不在）。 */
    public boolean border(final int q, final int r, final int direction) {
        return inRange(q, r) && !inRange(q + HexGrid.DIRECTIONS[direction][0], r + HexGrid.DIRECTIONS[direction][1]);
    }

    /** 兩格之間要不要畫連線：兩格都不是迷霧（解鎖了或營地）。 */
    public static boolean linked(final Cell a, final Cell b) {
        return a != null && b != null && !a.fog() && !b.fog();
    }

    private static String string(final JsonObject object, final String key, final String fallback) {
        final JsonElement element = object.get(key);
        return element == null || !element.isJsonPrimitive() ? fallback : element.getAsString();
    }

    private static boolean bool(final JsonObject object, final String key) {
        return object.has(key) && object.get(key).isJsonPrimitive() && object.get(key).getAsBoolean();
    }
}
