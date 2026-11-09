package tw.exilecore.client.core;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.BitSet;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.PriorityQueue;

/**
 * 2026-11-02 照站長（D 組）：天賦樹、星圖天賦、昇華天賦的原生畫面的資料（插件 TreeFeed／AtlasFeed／AscFeed 送的）。純 Java（測試用）。
 * <ul>
 *   <li>{@link Data}：靜態資料（節點、座標、連線、說明、職業變體）；天賦樹照雜湊快取（{@code TreeCache}）。</li>
 *   <li>{@link State}：已配、能配、被擋住、藏起來、退了會斷線的、剩幾點、說明面板、這次動作的結果。</li>
 *   <li>{@link #path}：路徑預覽（從已配的往外、照點數最少的路走到目標；跟插件的 PassiveTree.pathTo、AtlasTree.pathTo 一樣的規則：
 *       不經過別的職業的起點、不經過被解鎖條件擋住的節點）。真正配點時插件會一步一步再檢查一次。
 *       2026-11-02 照站長：觀星「虛實交織」的跳點（伺服器的 avail 裡沒相連的）也是出發點，跳點旁邊的從它接過去（跟插件的 pathTo 一樣）。</li>
 *   <li>{@link #search}：名稱或說明含這段字（不分大小寫）的節點，基石、核心排前面，同一種照離起點近的。</li>
 * </ul>
 */
public final class TreeModel {

    /** 一個節點在畫面上的狀態。 */
    public enum NodeState {
        /** 已配置（含自己的起點）。 */
        OWNED,
        /** 路徑預覽上。 */
        PATH,
        /** 現在就能配。 */
        AVAILABLE,
        /** 連得到但點數不夠（星圖天賦）。 */
        POOR,
        /** 被解鎖條件擋住。 */
        BLOCKED,
        /** 還連不到。 */
        LOCKED
    }

    /** 靜態資料。 */
    public static final class Data {
        public final String kind;
        public final long hash;
        public final double unit;
        public final String[] ids;
        public final double[] x;
        public final double[] y;
        /** 種類：s 小點、n 核心（大天賦）、k 基石（核心）、o 起點、j 珠寶孔。 */
        public final char[] type;
        public final String[] names;
        public final List<List<String>> lines;
        public final List<List<String>> explain;
        public final int[] cost;
        /** 主幹的顏色（0 = 沒有）。 */
        public final int[] color;
        /** 屬性點的數值（0 = 不是屬性點）。 */
        public final int[] attr;
        public final boolean[] skill;
        /** 2026-11-02 照站長：觀星「不見之途」專屬的節點（插件的 "u"；畫深紫加星光外框）。 */
        public final boolean[] unseen;
        /** 2026-11-02：虛實交織的半徑（世界單位；插件的 "jr"，0 = 沒有）。 */
        public final double jumpRadius;
        public final int[][] neighbours;
        /** 連線（索引兩兩一組）。 */
        public final int[] links;
        public final Map<String, Integer> index;
        public final Map<String, String> starts;
        /** 節點代號 → 職業 → {名稱, 說明行…}。 */
        public final Map<String, Map<String, List<String>>> variants;
        public final double minX;
        public final double minY;
        public final double maxX;
        public final double maxY;

        private Data(final String kind, final long hash, final JsonObject root) {
            this.kind = kind;
            this.hash = hash;
            this.unit = root.has("unit") ? root.get("unit").getAsDouble() : 160;
            final JsonArray nodes = root.getAsJsonArray("nodes");
            final int n = nodes.size();
            ids = new String[n];
            x = new double[n];
            y = new double[n];
            type = new char[n];
            names = new String[n];
            lines = new ArrayList<>(n);
            explain = new ArrayList<>(n);
            cost = new int[n];
            color = new int[n];
            attr = new int[n];
            skill = new boolean[n];
            unseen = new boolean[n];
            jumpRadius = root.has("jr") ? root.get("jr").getAsDouble() : 0;
            index = new HashMap<>(n * 2);
            double x0 = Double.MAX_VALUE;
            double y0 = Double.MAX_VALUE;
            double x1 = -Double.MAX_VALUE;
            double y1 = -Double.MAX_VALUE;
            for (int i = 0; i < n; i++) {
                final JsonObject node = nodes.get(i).getAsJsonObject();
                ids[i] = node.get("i").getAsString();
                x[i] = node.get("x").getAsDouble();
                y[i] = node.get("y").getAsDouble();
                final String k = node.has("k") ? node.get("k").getAsString() : "s";
                type[i] = k.isEmpty() ? 's' : k.charAt(0);
                names[i] = node.has("n") ? node.get("n").getAsString() : ids[i];
                lines.add(strings(node.get("d")));
                explain.add(strings(node.get("e")));
                cost[i] = node.has("c") ? node.get("c").getAsInt() : 1;
                color[i] = node.has("col") ? parseColor(node.get("col").getAsString()) : 0;
                attr[i] = node.has("a") ? node.get("a").getAsInt() : 0;
                skill[i] = node.has("skill") && node.get("skill").getAsBoolean();
                unseen[i] = node.has("u") && node.get("u").getAsInt() != 0;
                index.put(ids[i], i);
                x0 = Math.min(x0, x[i]);
                y0 = Math.min(y0, y[i]);
                x1 = Math.max(x1, x[i]);
                y1 = Math.max(y1, y[i]);
            }
            minX = n == 0 ? 0 : x0;
            minY = n == 0 ? 0 : y0;
            maxX = n == 0 ? 0 : x1;
            maxY = n == 0 ? 0 : y1;
            final JsonArray linkArray = root.has("links") ? root.getAsJsonArray("links") : new JsonArray();
            final List<Integer> pairs = new ArrayList<>();
            final int[] degree = new int[n];
            for (int i = 0; i + 1 < linkArray.size(); i += 2) {
                final int a = linkArray.get(i).getAsInt();
                final int b = linkArray.get(i + 1).getAsInt();
                if (a < 0 || b < 0 || a >= n || b >= n || a == b) {
                    continue;
                }
                pairs.add(a);
                pairs.add(b);
                degree[a]++;
                degree[b]++;
            }
            links = pairs.stream().mapToInt(Integer::intValue).toArray();
            neighbours = new int[n][];
            for (int i = 0; i < n; i++) {
                neighbours[i] = new int[degree[i]];
            }
            final int[] fill = new int[n];
            for (int i = 0; i < links.length; i += 2) {
                neighbours[links[i]][fill[links[i]]++] = links[i + 1];
                neighbours[links[i + 1]][fill[links[i + 1]]++] = links[i];
            }
            starts = new HashMap<>();
            if (root.has("starts") && root.get("starts").isJsonObject()) {
                for (final Map.Entry<String, JsonElement> entry : root.getAsJsonObject("starts").entrySet()) {
                    starts.put(entry.getKey(), entry.getValue().getAsString());
                }
            }
            variants = new HashMap<>();
            if (root.has("var") && root.get("var").isJsonObject()) {
                for (final Map.Entry<String, JsonElement> entry : root.getAsJsonObject("var").entrySet()) {
                    final Map<String, List<String>> byClass = new HashMap<>();
                    for (final Map.Entry<String, JsonElement> variant : entry.getValue().getAsJsonObject().entrySet()) {
                        final JsonObject object = variant.getValue().getAsJsonObject();
                        final List<String> shown = new ArrayList<>();
                        shown.add(object.has("n") ? object.get("n").getAsString() : "");
                        shown.addAll(strings(object.get("d")));
                        byClass.put(variant.getKey(), shown);
                    }
                    variants.put(entry.getKey(), byClass);
                }
            }
        }

        public int size() {
            return ids.length;
        }

        public int indexOf(final String id) {
            final Integer i = id == null ? null : index.get(id);
            return i == null ? -1 : i;
        }

        /** 起點種類（別的職業的起點不能經過）。 */
        public boolean isStart(final int i) {
            return type[i] == 'o';
        }
    }

    /** 動態狀態。 */
    public static final class State {
        public final String kind;
        public final long staticHash;
        public final boolean open;
        public final int start;
        public final String classId;
        public final BitSet owned;
        public final BitSet avail;
        public final BitSet poor;
        public final BitSet blocked;
        public final BitSet hidden;
        public final BitSet fixed;
        public final BitSet seeds;
        public final BitSet jewels;
        /** 2026-11-02 照站長：虛實交織的錨點（連得到的已配基石；畫範圍圈）。 */
        public final BitSet anchors;
        public final int points;
        public final String title;
        public final List<String> info;
        public final String refund;
        public final Map<String, String> use;
        /** 2026-11-02 照站長：配了的屬性點選了哪個（str、dex、int、dmg、def、cost；伺服器的 "attrs"，畫顏色用）。 */
        public final Map<String, String> attrs;
        /** 2026-11-02 照站長：放了範圍珠寶、永恆珠寶的孔 → 範圍半徑（世界單位；伺服器的 "jrad"）。 */
        public final Map<String, Double> jewelRadius;
        /** 2026-11-02 照站長：有空的珠寶孔時伺服器列的背包珠寶（在天賦樹上直接選）；null = 伺服器沒列（舊版或沒有空孔）。 */
        public final List<Jewel> bag;
        /** 2026-11-02 照站長：組別（月卡切換）：幾組、用哪一組（0 起算）、有沒有月卡；setCount 0 = 伺服器沒給（不畫組別鈕）。 */
        public final int setCount;
        public final int activeSet;
        public final boolean card;
        /**
         * 2026-11-09 照站長「client mod 的天賦畫面也要能點武器組的組」：武器組天賦——正在用第幾組（1、2；0 = 伺服器沒給，舊伺服器不畫武器組）、
         * 每組上限、兩組用量、兩組的節點（另一組的不在 owned 裡）。這次之前模組不知道武器組，武器組天賦當一般節點畫。
         */
        public final int weaponSet;
        public final int weaponCap;
        public final int weaponUsed1;
        public final int weaponUsed2;
        public final BitSet weapon1;
        public final BitSet weapon2;
        /** 換掉名稱、說明的節點（征服、珠寶）：代號 → {名稱, 說明行…}。 */
        public final Map<String, List<String>> over;
        public final Map<String, Long> refundCost;
        /** 2026-11-02：天賦樹退一點要幾金幣（0 = 免費）、你有多少金幣（退點確認用）。 */
        public final long refundEach;
        public final long gold;
        public final String message;
        public final String search;
        public final boolean reset;
        /**
         * 2026-11-09 照站長「模組右上新增打開網頁天賦規劃器與匯入代碼」：伺服器有這兩個功能（imp）、規劃器網址（web；空字串 = 網頁沒開）。
         * 舊伺服器沒有 imp：不畫這兩個按鈕。
         */
        public final boolean importable;
        public final String plannerUrl;

        /** 背包裡的一顆珠寶：k 在哪（伺服器的代號）、名稱、說明、不能放的原因（空字串 = 可以放）。 */
        public record Jewel(String key, String name, List<String> lines, String problem) {
        }

        private State(final JsonObject root, final Data data) {
            kind = string(root, "kind", "");
            staticHash = UiWire.parseHex(string(root, "static", ""));
            open = root.has("open") && root.get("open").getAsBoolean();
            start = data.indexOf(string(root, "start", ""));
            classId = string(root, "class", "");
            owned = bits(root, "owned", data);
            avail = bits(root, "avail", data);
            poor = bits(root, "poor", data);
            blocked = bits(root, "locked", data);
            hidden = bits(root, "hidden", data);
            fixed = bits(root, "fixed", data);
            seeds = bits(root, "seeds", data);
            jewels = bits(root, "jewels", data);
            anchors = bits(root, "anchors", data);
            if (start >= 0) {
                owned.set(start);
                seeds.set(start);
            }
            points = root.has("points") ? root.get("points").getAsInt() : 0;
            title = string(root, "title", "");
            info = strings(root.get("info"));
            refund = string(root, "refund", "");
            use = new HashMap<>();
            if (root.has("use") && root.get("use").isJsonObject()) {
                for (final Map.Entry<String, JsonElement> entry : root.getAsJsonObject("use").entrySet()) {
                    use.put(entry.getKey(), entry.getValue().getAsString());
                }
            }
            if (root.has("sets") && root.get("sets").isJsonObject()) {
                final JsonObject sets = root.getAsJsonObject("sets");
                setCount = sets.has("n") ? sets.get("n").getAsInt() : 0;
                activeSet = sets.has("a") ? sets.get("a").getAsInt() : 0;
                card = sets.has("card") && sets.get("card").getAsBoolean();
            } else {
                setCount = 0;
                activeSet = 0;
                card = false;
            }
            weaponSet = root.has("wsa") ? root.get("wsa").getAsInt() : 0;
            weaponCap = root.has("wscap") ? root.get("wscap").getAsInt() : 0;
            weaponUsed1 = root.has("wsu1") ? root.get("wsu1").getAsInt() : 0;
            weaponUsed2 = root.has("wsu2") ? root.get("wsu2").getAsInt() : 0;
            weapon1 = bits(root, "ws1", data);
            weapon2 = bits(root, "ws2", data);
            if (root.has("bag") && root.get("bag").isJsonArray()) {
                final List<Jewel> list = new ArrayList<>();
                for (final JsonElement element : root.getAsJsonArray("bag")) {
                    if (element.isJsonObject()) {
                        final JsonObject o = element.getAsJsonObject();
                        list.add(new Jewel(string(o, "k", ""), string(o, "n", ""), strings(o.get("d")), string(o, "p", "")));
                    }
                }
                bag = List.copyOf(list);
            } else {
                bag = null;
            }
            jewelRadius = new HashMap<>();
            if (root.has("jrad") && root.get("jrad").isJsonObject()) {
                for (final Map.Entry<String, JsonElement> entry : root.getAsJsonObject("jrad").entrySet()) {
                    jewelRadius.put(entry.getKey(), entry.getValue().getAsDouble());
                }
            }
            attrs = new HashMap<>();
            if (root.has("attrs") && root.get("attrs").isJsonObject()) {
                for (final Map.Entry<String, JsonElement> entry : root.getAsJsonObject("attrs").entrySet()) {
                    attrs.put(entry.getKey(), entry.getValue().getAsString());
                }
            }
            over = new HashMap<>();
            if (root.has("over") && root.get("over").isJsonObject()) {
                for (final Map.Entry<String, JsonElement> entry : root.getAsJsonObject("over").entrySet()) {
                    final JsonObject object = entry.getValue().getAsJsonObject();
                    final List<String> shown = new ArrayList<>();
                    shown.add(string(object, "n", ""));
                    shown.addAll(strings(object.get("d")));
                    over.put(entry.getKey(), shown);
                }
            }
            refundCost = new HashMap<>();
            if (root.has("rc") && root.get("rc").isJsonObject()) {
                for (final Map.Entry<String, JsonElement> entry : root.getAsJsonObject("rc").entrySet()) {
                    refundCost.put(entry.getKey(), entry.getValue().getAsLong());
                }
            }
            refundEach = root.has("rcost") ? root.get("rcost").getAsLong() : 0;
            gold = root.has("gold") ? root.get("gold").getAsLong() : 0;
            message = string(root, "msg", "");
            search = root.has("search") ? root.get("search").getAsString() : null;
            reset = root.has("reset") && root.get("reset").getAsBoolean();
            importable = root.has("imp") && root.get("imp").getAsBoolean();
            plannerUrl = string(root, "web", "");
        }
    }

    public final Data data;
    public final State state;

    /** 2026-11-09：第 I 組武器組天賦的顏色（亮紅；遊戲內、網頁規劃器同色）。 */
    public static final int WEAPON_SET_1 = 0xFFFF3B3B;
    /** 2026-11-09：第 II 組武器組天賦的顏色（亮青）。 */
    public static final int WEAPON_SET_2 = 0xFF2EE6FF;

    /** 2026-11-09：這個節點是第幾組的武器組天賦（兩組都配的算正在用那一組；0 = 不是）。 */
    public int weaponSetOf(final int i) {
        final boolean one = state.weapon1.get(i);
        final boolean two = state.weapon2.get(i);
        if (one && two) {
            return state.weaponSet == 2 ? 2 : 1;
        }
        return one ? 1 : two ? 2 : 0;
    }

    /** 2026-11-09：武器組的名字（第 I 組、第 II 組）。 */
    public static String weaponSetName(final int set) {
        return set == 2 ? "第 II 組" : "第 I 組";
    }

    /** 2026-11-09：武器組的顏色標籤（說明文字用）。 */
    public static String weaponSetTag(final int set) {
        return set == 2 ? "<#2ee6ff>" : "<#ff3b3b>";
    }

    public TreeModel(final Data data, final State state) {
        this.data = data;
        this.state = state;
    }

    // ---- 讀 ----

    public static Data parseData(final String kind, final long hash, final String json) {
        return new Data(kind, hash, JsonParser.parseString(json).getAsJsonObject());
    }

    public static Data parseData(final String kind, final long hash, final JsonObject root) {
        return new Data(kind, hash, root);
    }

    public static State parseState(final JsonObject root, final Data data) {
        return new State(root, data);
    }

    /** 狀態裡要哪一份靜態資料的雜湊（沒寫 = 這種畫面的資料直接帶在狀態裡）。 */
    public static long staticHashOf(final JsonObject state) {
        return UiWire.parseHex(string(state, "static", ""));
    }

    private static String string(final JsonObject object, final String key, final String fallback) {
        final JsonElement element = object.get(key);
        return element == null || !element.isJsonPrimitive() ? fallback : element.getAsString();
    }

    static List<String> strings(final JsonElement element) {
        final List<String> out = new ArrayList<>();
        if (element != null && element.isJsonArray()) {
            for (final JsonElement line : element.getAsJsonArray()) {
                out.add(line.getAsString());
            }
        }
        return out;
    }

    private static BitSet bits(final JsonObject root, final String key, final Data data) {
        final BitSet set = new BitSet(data.size());
        if (root.has(key) && root.get(key).isJsonArray()) {
            for (final JsonElement element : root.getAsJsonArray(key)) {
                final int i = data.indexOf(element.getAsString());
                if (i >= 0) {
                    set.set(i);
                }
            }
        }
        return set;
    }

    static int parseColor(final String text) {
        try {
            return text != null && text.startsWith("#") && text.length() == 7 ? Integer.parseInt(text.substring(1), 16) : 0;
        } catch (final NumberFormatException e) {
            return 0;
        }
    }

    // ---- 畫面要的 ----

    public boolean visible(final int i) {
        return !state.hidden.get(i);
    }

    public NodeState stateOf(final int i, final BitSet path) {
        if (state.owned.get(i)) {
            return NodeState.OWNED;
        }
        if (path != null && path.get(i)) {
            return NodeState.PATH;
        }
        if (state.avail.get(i)) {
            return NodeState.AVAILABLE;
        }
        if (state.poor.get(i)) {
            return NodeState.POOR;
        }
        if (state.blocked.get(i)) {
            return NodeState.BLOCKED;
        }
        return NodeState.LOCKED;
    }

    /**
     * 2026-11-02 照站長：虛實交織的跳點——現在能配（伺服器的 avail），但旁邊沒有已配置的或出發點。
     */
    public boolean jump(final int i) {
        if (i < 0 || i >= data.size() || !state.avail.get(i)) {
            return false;
        }
        for (final int next : data.neighbours[i]) {
            if (state.owned.get(next) || state.seeds.get(next)) {
                return false;
            }
        }
        return true;
    }

    /** 畫面上看到的名稱（珠寶、征服、職業變體換掉的）。 */
    public String name(final int i) {
        final List<String> over = state.over.get(data.ids[i]);
        if (over != null && !over.getFirst().isEmpty()) {
            return over.getFirst();
        }
        final List<String> variant = variant(i);
        return variant != null && !variant.getFirst().isEmpty() ? variant.getFirst() : data.names[i];
    }

    /** 畫面上看到的說明（沒有被換掉的照靜態資料）。replaced = true 時說明已經有顏色（插件寫好的）。 */
    public List<String> lines(final int i) {
        final List<String> over = state.over.get(data.ids[i]);
        if (over != null) {
            return over.subList(1, over.size());
        }
        final List<String> variant = variant(i);
        return variant != null ? variant.subList(1, variant.size()) : data.lines.get(i);
    }

    private List<String> variant(final int i) {
        final Map<String, List<String>> byClass = data.variants.get(data.ids[i]);
        return byClass == null || state.classId.isEmpty() ? null : byClass.get(state.classId);
    }

    /**
     * 路徑預覽：要配哪些節點（依序，含目標）。目標已配、看不到、被擋住、是起點、走不到時回傳空的；現在就能配回傳只有目標。
     * single = true（昇華天賦）時只有現在就能配的才有路徑。
     */
    public List<Integer> path(final int target, final boolean single) {
        if (target < 0 || target >= data.size() || state.owned.get(target) || !visible(target) || state.blocked.get(target)
                || data.isStart(target)) {
            return List.of();
        }
        if (state.avail.get(target) || state.poor.get(target)) {
            return List.of(target);
        }
        if (single) {
            return List.of();
        }
        final int n = data.size();
        final int[] distance = new int[n];
        final int[] parent = new int[n];
        Arrays.fill(distance, Integer.MAX_VALUE);
        Arrays.fill(parent, -1);
        final PriorityQueue<int[]> queue = new PriorityQueue<>(Comparator.<int[]>comparingInt(e -> e[0]).thenComparingInt(e -> e[1]));
        for (int i = state.owned.nextSetBit(0); i >= 0; i = state.owned.nextSetBit(i + 1)) {
            distance[i] = 0;
            queue.add(new int[] {0, i});
        }
        for (int i = state.seeds.nextSetBit(0); i >= 0; i = state.seeds.nextSetBit(i + 1)) {
            if (distance[i] != 0) {
                distance[i] = 0;
                queue.add(new int[] {0, i});
            }
        }
        // 2026-11-02 照站長：現在就能配的（含虛實交織的跳點）距離是自己的點數、沒有 parent，路徑可以從跳點開始
        for (int i = state.avail.nextSetBit(0); i >= 0; i = state.avail.nextSetBit(i + 1)) {
            if (!visible(i) || state.blocked.get(i)) {
                continue;
            }
            final int d = Math.max(1, data.cost[i]);
            if (d < distance[i]) {
                distance[i] = d;
                queue.add(new int[] {d, i});
            }
        }
        while (!queue.isEmpty()) {
            final int[] entry = queue.poll();
            final int current = entry[1];
            if (entry[0] > distance[current]) {
                continue;
            }
            if (current == target) {
                break;
            }
            for (final int next : data.neighbours[current]) {
                if (state.owned.get(next) || data.isStart(next) || state.blocked.get(next) || !visible(next)) {
                    continue;
                }
                final int d = distance[current] + Math.max(1, data.cost[next]);
                if (d < distance[next]) {
                    distance[next] = d;
                    parent[next] = current;
                    queue.add(new int[] {d, next});
                }
            }
        }
        if (distance[target] == Integer.MAX_VALUE) {
            return List.of();
        }
        final List<Integer> path = new ArrayList<>();
        for (int step = target; step >= 0 && !state.owned.get(step) && !state.seeds.get(step); step = parent[step]) {
            path.add(step);
        }
        java.util.Collections.reverse(path);
        return path;
    }

    /** 路徑總共要幾點。 */
    public int cost(final List<Integer> path) {
        int total = 0;
        for (final int i : path) {
            total += Math.max(0, data.cost[i]);
        }
        return total;
    }

    /** 搜尋：名稱或說明含 query（不分大小寫，標籤拿掉）的節點；基石、核心排前面，同一種照離起點近的。 */
    public List<Integer> search(final String query) {
        final String needle = query == null ? "" : query.trim().toLowerCase(Locale.ROOT);
        final List<Integer> out = new ArrayList<>();
        if (needle.isEmpty()) {
            return out;
        }
        for (int i = 0; i < data.size(); i++) {
            if (!visible(i)) {
                continue;
            }
            if (Rich.plain(name(i)).toLowerCase(Locale.ROOT).contains(needle)) {
                out.add(i);
                continue;
            }
            for (final String line : lines(i)) {
                if (Rich.plain(line).toLowerCase(Locale.ROOT).contains(needle)) {
                    out.add(i);
                    break;
                }
            }
        }
        final double sx = state.start >= 0 ? data.x[state.start] : 0;
        final double sy = state.start >= 0 ? data.y[state.start] : 0;
        out.sort(Comparator.<Integer>comparingInt(i -> switch (data.type[i]) {
            case 'k' -> 0;
            case 'n' -> 1;
            default -> 2;
        }).thenComparingDouble(i -> Math.hypot(data.x[i] - sx, data.y[i] - sy)).thenComparing(i -> data.ids[i]));
        return out;
    }

    /** 最靠近 (wx, wy) 的看得到的節點（在 radius 之內；沒有回傳 -1）。 */
    public int nearest(final double wx, final double wy, final double radius) {
        int best = -1;
        double bestDistance = radius * radius;
        for (int i = 0; i < data.size(); i++) {
            if (!visible(i)) {
                continue;
            }
            final double dx = data.x[i] - wx;
            final double dy = data.y[i] - wy;
            final double d = dx * dx + dy * dy;
            if (d <= bestDistance) {
                bestDistance = d;
                best = i;
            }
        }
        return best;
    }
}
