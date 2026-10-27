package tw.exilecore.web;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import tw.exilecore.passive.PassiveNode;
import tw.exilecore.passive.PassiveTree;
import tw.exilecore.passive.TreeCode;

import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * 網頁天賦規劃器要的資料（/tree/data.json）：節點（照檔案順序＝代碼的位元順序）、職業起點、點數規則。純邏輯。
 */
public final class PlannerData {

    /** 一個職業。color 是 #rrggbb。 */
    public record ClassInfo(String id, String name, String color) {
    }

    /**
     * 一個昇華天賦（給規劃器的昇華預覽）。lines 是效果的說明；note 是補充（沒有是 null）。
     * 2026-10-26 照 PoE2 會分岔：requires 是相連的、比較靠近起點的天賦（空的 = 接在起點；任一個配了就能配），
     * depth 是離起點幾步（0 = 接在起點）、row 是畫在第幾排；optionOf 是「選一個」天賦的選項（同一組只能配一個、不花點數），
     * choice = 「選一個」的天賦，notable = 核心天賦。
     */
    public record AscendancyNode(String name, int cost, List<String> lines, String note, String id, List<String> requires, int depth,
                                 int row, String optionOf, boolean choice, boolean notable, List<JsonObject> skills) {
        public AscendancyNode {
            lines = List.copyOf(lines);
            requires = requires == null ? List.of() : List.copyOf(requires);
            skills = skills == null ? List.of() : List.copyOf(skills);
        }

        /** 2026-10-27 以前的寫法（沒有賦予技能的預覽）。 */
        public AscendancyNode(final String name, final int cost, final List<String> lines, final String note, final String id,
                              final List<String> requires, final int depth, final int row, final String optionOf, final boolean choice,
                              final boolean notable) {
            this(name, cost, lines, note, id, requires, depth, row, optionOf, choice, notable, List.of());
        }

        public AscendancyNode(final String name, final int cost, final List<String> lines, final String note, final String id) {
            this(name, cost, lines, note, id, List.of(), 0, 0, null, false, false);
        }

        public AscendancyNode(final String name, final int cost, final List<String> lines, final String note) {
            this(name, cost, lines, note, null);
        }
    }

    /**
     * 一個昇華職業：branches 是選單上的每一排（2026-10-26 以前是每條分支；現在分岔出去的那一排從分岔的地方開始，
     * 每個天賦的 depth、requires 說它接在哪裡）。color 是 #rrggbb。
     */
    public record AscendancyInfo(String id, String classId, String name, String color, String summary, List<List<AscendancyNode>> branches) {
        public AscendancyInfo {
            branches = List.copyOf(branches);
        }
    }

    private static final Gson GSON = new GsonBuilder().disableHtmlEscaping().create();

    private PlannerData() {
    }

    /** 職業的主屬性（str／dex／int；一樣高的照力量、敏捷、智慧的順序）：屬性點沒選的時候就是它。不認得的職業算力量。 */
    static String mainAttribute(final String classId) {
        for (final tw.exilecore.character.CharacterClass type : tw.exilecore.character.CharacterClass.values()) {
            if (type.id().equalsIgnoreCase(classId)) {
                final int s = type.strength();
                final int d = type.dexterity();
                final int i = type.intelligence();
                return s >= d && s >= i ? "str" : d >= i ? "dex" : "int";
            }
        }
        return "str";
    }

    /** 2026-10-27：名詞 → 說明（{@link tw.exilecore.stats.Glossary}，照表的順序）。 */
    static JsonObject glossaryJson() {
        final JsonObject glossary = new JsonObject();
        tw.exilecore.stats.Glossary.terms().forEach(glossary::addProperty);
        return glossary;
    }

    public static byte[] json(final PassiveTree tree, final List<ClassInfo> classes, final int levelCap, final int pointsPerLevel,
                              final int bonusPoints) {
        return json(tree, classes, levelCap, pointsPerLevel, bonusPoints, List.of(), 0);
    }

    /**
     * 同上，再帶昇華職業（2026-10：規劃器可以預覽每個職業的昇華天賦；只看，不進代碼）。
     *
     * @param ascendancyPoints 昇華點最多幾點
     */
    public static byte[] json(final PassiveTree tree, final List<ClassInfo> classes, final int levelCap, final int pointsPerLevel,
                              final int bonusPoints, final List<AscendancyInfo> ascendancies, final int ascendancyPoints) {
        final JsonObject root = new JsonObject();
        root.addProperty("code", TreeCode.PREFIX);
        root.addProperty("version", tree.version());
        root.addProperty("levelCap", levelCap);
        root.addProperty("pointsPerLevel", pointsPerLevel);
        root.addProperty("bonusPoints", bonusPoints);
        final JsonArray classArray = new JsonArray();
        final Map<String, String> starts = tree.starts();
        for (final ClassInfo info : classes) {
            final String start = starts.get(info.id().toLowerCase(Locale.ROOT));
            if (start == null) {
                continue;
            }
            final JsonObject object = new JsonObject();
            object.addProperty("id", info.id());
            object.addProperty("name", info.name());
            object.addProperty("color", info.color());
            object.addProperty("start", start);
            object.addProperty("attr", mainAttribute(info.id())); // 2026-10：屬性點沒選的時候算哪個屬性（跟遊戲一樣：職業的主屬性）
            classArray.add(object);
        }
        root.add("classes", classArray);
        final JsonArray nodes = new JsonArray();
        final Map<String, Integer> index = new java.util.HashMap<>();
        for (final PassiveNode node : tree.ordered()) {
            index.put(node.id(), index.size());
        }
        for (final PassiveNode node : tree.ordered()) {
            final JsonObject object = new JsonObject();
            object.addProperty("id", node.id());
            object.addProperty("x", node.x());
            object.addProperty("y", node.y());
            // 2026-10-26：PoE2 原作座標（珠寶半徑 jewelRadii 照這個算距離）
            final double[] position = tree.position(node);
            object.addProperty("px", Math.round(position[0]));
            object.addProperty("py", Math.round(position[1]));
            object.addProperty("type", node.type().name().toLowerCase(Locale.ROOT));
            object.addProperty("name", node.name().isEmpty() ? node.type().displayName() : node.name());
            final JsonArray lines = new JsonArray();
            for (final String line : node.description()) {
                lines.add(line);
            }
            object.add("lines", lines);
            // 2026-10-27 照站長：說明裡的關鍵字（撲殺、秘術增強…）的說明，每個一次（跟遊戲裡天賦樹的說明一樣）
            final List<String> explained = tw.exilecore.stats.KeywordText.explain(node.description());
            if (!explained.isEmpty()) {
                final JsonArray explain = new JsonArray();
                explained.forEach(explain::add);
                object.add("explain", explain);
            }
            if (node.attributeChoice() > 0) {
                object.addProperty("attr", node.attributeChoice()); // 2026-10：屬性點（+x 任一屬性），規劃器裡可以選力量／敏捷／智慧
            }
            // 2026-10-26（PoE2 的樹）：明確的連線（每條只寫在一端）與職業變體
            final JsonArray links = new JsonArray();
            for (final PassiveNode other : tree.neighbours(node)) {
                if (index.get(other.id()) > index.get(node.id())) {
                    links.add(other.id());
                }
            }
            object.add("links", links);
            // 2026-10-26 照 PoE2：解鎖條件（stat：要有那個昇華規則，例如觀星「未見之道」asc.unseen_path；ascendancy：只有選了這個昇華才顯示；nodes：要先配其中一個）
            final PassiveTree.Unlock unlock = tree.unlock(node);
            if (unlock != null) {
                final JsonObject unlockObject = new JsonObject();
                if (unlock.stat() != null) {
                    unlockObject.addProperty("stat", unlock.stat());
                    if (unlock.ascendancy() != null) {
                        unlockObject.addProperty("ascendancy", unlock.ascendancy()); // 2026-10-26 照站長：只有選了這個昇華才顯示
                    }
                } else {
                    final JsonArray unlockNodes = new JsonArray();
                    unlock.nodes().forEach(unlockNodes::add);
                    unlockObject.add("nodes", unlockNodes);
                }
                object.add("unlock", unlockObject);
            }
            if (!node.variants().isEmpty()) {
                final JsonObject variants = new JsonObject();
                for (final Map.Entry<String, PassiveNode.Variant> entry : node.variants().entrySet()) {
                    final JsonObject variant = new JsonObject();
                    variant.addProperty("name", entry.getValue().name());
                    final JsonArray variantLines = new JsonArray();
                    entry.getValue().description().forEach(variantLines::add);
                    variant.add("lines", variantLines);
                    variants.add(entry.getKey(), variant);
                }
                object.add("variants", variants);
            }
            nodes.add(object);
        }
        root.add("nodes", nodes);
        // 2026-10-27 照 PoE2 0.5：塗油限定的核心（天賦樹上沒有、不能配點，只能注入在項鍊上）：給規劃器的注入選單，不畫在樹上、不進代碼
        final JsonArray anoint = new JsonArray();
        for (final PassiveNode node : tree.anointOnly()) {
            final JsonObject object = new JsonObject();
            object.addProperty("id", node.id());
            object.addProperty("name", node.name());
            final JsonArray lines = new JsonArray();
            node.description().forEach(lines::add);
            object.add("lines", lines);
            final List<String> explained = tw.exilecore.stats.KeywordText.explain(node.description());
            if (!explained.isEmpty()) {
                final JsonArray explain = new JsonArray();
                explained.forEach(explain::add);
                object.add("explain", explain);
            }
            final tw.exilecore.passive.AnointRecipes.Recipe recipe = tw.exilecore.passive.AnointRecipes.get().recipe(node.id());
            if (recipe != null) {
                final JsonArray emotions = new JsonArray();
                for (final String emotion : recipe.emotions()) {
                    emotions.add(tw.exilecore.passive.AnointRecipes.get().emotionNames().getOrDefault(emotion, emotion));
                }
                object.add("recipe", emotions);
            }
            anoint.add(object);
        }
        root.add("anointOnly", anoint);
        // 2026-10：珠寶孔周圍的範圍。2026-10-26 照 PoE2：原作單位（遺時珠寶小 1000、中 1150、大 1300，永恆珠寶 1500），
        // 算法：節點的 px/py（原作座標）與孔的距離 ≤ 半徑；這次之前是格子：2／3／4 格，距離 ≤ 半徑 + 0.5
        final JsonArray radii = new JsonArray();
        radii.add(tw.exilecore.passive.JewelSockets.BASE_RADIUS);
        radii.add(tw.exilecore.passive.JewelSockets.MEDIUM_RADIUS);
        radii.add(tw.exilecore.passive.JewelSockets.LARGE_RADIUS);
        radii.add(tw.exilecore.passive.TimelessJewels.RADIUS);
        root.add("jewelRadii", radii);
        // 2026-10-26：永恆珠寶（英雄悲劇、不朽恨意）：哪件傳奇是哪個族、征服者與他們的基石。
        // 被征服的節點換成什麼由伺服器算（POST /tree/timeless；計算器 /tree/calc 的 jewels[].conquest 也有），網頁不自己重寫雜湊
        final JsonObject timeless = new JsonObject();
        timeless.addProperty("radius", tw.exilecore.passive.TimelessJewels.RADIUS);
        final JsonArray legions = new JsonArray();
        for (final tw.exilecore.passive.TimelessJewels.Legion legion : tw.exilecore.passive.TimelessJewels.legions().values().stream()
                .sorted(java.util.Comparator.comparingInt(tw.exilecore.passive.TimelessJewels.Legion::code)).toList()) {
            final JsonObject l = new JsonObject();
            l.addProperty("code", legion.code());
            l.addProperty("id", legion.id());
            l.addProperty("name", legion.name());
            l.addProperty("unique", legion.unique());
            final JsonArray conquerors = new JsonArray();
            for (int i = 1; i <= legion.conquerors().size(); i++) {
                final JsonObject c = new JsonObject();
                c.addProperty("name", legion.conqueror(i).name());
                c.addProperty("keystone", legion.keystone(i).name());
                conquerors.add(c);
            }
            l.add("conquerors", conquerors);
            legions.add(l);
        }
        timeless.add("legions", legions);
        root.add("timeless", timeless);
        root.addProperty("ascendancyPoints", ascendancyPoints);
        final JsonArray ascendancyArray = new JsonArray();
        for (final AscendancyInfo info : ascendancies) {
            final JsonObject object = new JsonObject();
            object.addProperty("id", info.id());
            object.addProperty("class", info.classId());
            object.addProperty("name", info.name());
            object.addProperty("color", info.color());
            object.addProperty("summary", info.summary() == null ? "" : info.summary());
            final JsonArray branches = new JsonArray();
            for (final List<AscendancyNode> branch : info.branches()) {
                final JsonArray branchArray = new JsonArray();
                for (final AscendancyNode node : branch) {
                    final JsonObject nodeObject = new JsonObject();
                    if (node.id() != null) {
                        nodeObject.addProperty("id", node.id()); // 2026-10：網頁計算器配昇華天賦用
                    }
                    nodeObject.addProperty("name", node.name());
                    nodeObject.addProperty("cost", node.cost());
                    final JsonArray lines = new JsonArray();
                    for (final String line : node.lines()) {
                        lines.add(line);
                    }
                    nodeObject.add("lines", lines);
                    final List<String> shown = new java.util.ArrayList<>(node.lines());
                    // 2026-10-27：補充說明跟說明行一樣的不再給（只顯示一次）
                    if (node.note() != null && !node.note().isBlank() && !node.lines().contains(node.note())) {
                        nodeObject.addProperty("note", node.note());
                        shown.add(node.note());
                    }
                    // 2026-10-27 照站長：只寫了關鍵字的（撲殺、毀滅打擊…）補關鍵字說明，每個一次
                    final List<String> explained = tw.exilecore.stats.KeywordText.explain(shown);
                    if (!explained.isEmpty()) {
                        final JsonArray explain = new JsonArray();
                        explained.forEach(explain::add);
                        nodeObject.add("explain", explain);
                    }
                    // 2026-10-26：分岔（照 PoE2）
                    final JsonArray requires = new JsonArray();
                    for (final String required : node.requires()) {
                        requires.add(required);
                    }
                    nodeObject.add("requires", requires);
                    nodeObject.addProperty("depth", node.depth());
                    nodeObject.addProperty("row", node.row());
                    if (node.optionOf() != null) {
                        nodeObject.addProperty("optionOf", node.optionOf());
                    }
                    if (node.choice()) {
                        nodeObject.addProperty("choice", true);
                    }
                    if (node.notable()) {
                        nodeObject.addProperty("notable", true);
                    }
                    // 2026-10-27 照站長：賦予的技能直接帶技能預覽（跟寶石圖鑑同一份資料：標籤、說明、施法時間、冷卻、各等級數值）
                    if (!node.skills().isEmpty()) {
                        final JsonArray skills = new JsonArray();
                        node.skills().forEach(skills::add);
                        nodeObject.add("skills", skills);
                    }
                    branchArray.add(nodeObject);
                }
                branches.add(branchArray);
            }
            object.add("branches", branches);
            ascendancyArray.add(object);
        }
        root.add("ascendancies", ascendancyArray);
        // 2026-10-27 照站長：名詞表（網頁在提示框的說明裡把名詞畫虛線底線，滑上去看解釋）
        root.add("glossary", glossaryJson());
        return GSON.toJson(root).getBytes(StandardCharsets.UTF_8);
    }
}
