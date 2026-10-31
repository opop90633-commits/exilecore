package tw.exilecore.passive;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import tw.exilecore.item.ItemBase;
import tw.exilecore.item.ItemData;
import tw.exilecore.item.ItemRepository;
import tw.exilecore.stats.ModType;
import tw.exilecore.stats.Modifier;

import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * 永恆珠寶（2026-10-26，照 PoE2 0.5 的 Timeless Jewel）：英雄悲劇（卡爾葛）、不朽恨意（深淵族）。
 * <p>
 * 放進配了的珠寶孔：孔周圍 {@value #RADIUS}（PoE2 單位，「非常大」）範圍內的天賦被征服——
 * <ul>
 *   <li>核心換成那個族的核心（哪一個照種子與節點決定）；</li>
 *   <li>基石換成征服者的基石（英雄悲劇：沃拉娜／梅德偉／奧爾羅斯；不朽恨意：阿姆納姆、骷髏馬克、柯戈、特克羅德、烏拉曼）；</li>
 *   <li>深淵族：小天賦換成「+5 貢禮」、屬性點多給「+3 貢禮」，深淵族的核心照已配置的貢禮給（「每 N 點貢禮」）。
 *       卡爾葛不動小天賦（PoE2 資料沒有卡爾葛的小點附加）。</li>
 * </ul>
 * 資料在 data/passives/timeless.json（tools/gen_timeless.py 從 PoB-PoE2 的 LegionPassives.lua 產生；跟著插件 jar，不是可調的設定檔）。
 * <p>
 * <b>種子對應</b>：PoE2 版 PoB 沒有「種子 → 換成哪個核心」的官方查表（PoE1 有 LUT 檔），所以這裡用確定性的雜湊
 * （種子、族、節點代號 → 核心清單的第幾個）自己做：同一顆珠寶放在同一個孔永遠一樣，換種子或換孔會變，但跟原作的結果不同。
 * <p>
 * 珠寶的詞綴：{@link #SEED}（種子 100–8000）、{@link #CONQUEROR}（征服者，1 起算）、{@link #LEGION}（1 卡爾葛、2 深淵族）；
 * 這三個不進屬性表，只在這裡讀。PoE2 限 1 顆（Historic），兩種永恆珠寶同時只算一顆。
 */
public final class TimelessJewels {

    private TimelessJewels() {
    }

    public static final String SEED = "timeless.seed";
    public static final String CONQUEROR = "timeless.conqueror";
    public static final String LEGION = "timeless.legion";
    /** 「僅限：同時只能配一顆永恆珠寶」（PoE2 的 Historic）：只是說明，限制在 {@link JewelSockets#socket}。 */
    public static final String HISTORIC = "timeless.historic";
    /** 永恆珠寶詞綴的屬性（不進屬性表）。 */
    public static final List<String> STATS = List.of(SEED, CONQUEROR, LEGION, HISTORIC);
    /** 永恆珠寶基底的標籤（bases/jewels.yml 的 timeless_jewel）。 */
    public static final String BASE_TAG = "timeless";
    /** 範圍（PoE2 單位；遺時珠寶是 1000／1150／1300）。 */
    public static final double RADIUS = 1500;
    public static final int SEED_MIN = 100;
    public static final int SEED_MAX = 8000;
    /** 卡爾葛的基石：沒有某屬性的固有加成，改成每 2 點給別的防禦（PlayerStateService 算屬性值加成的地方讀）。 */
    public static final String FLAG_ALT_STR = "flag.timeless_alt_str";
    public static final String FLAG_ALT_DEX = "flag.timeless_alt_dex";
    public static final String FLAG_ALT_INT = "flag.timeless_alt_int";
    private static final String RESOURCE = "/data/passives/timeless.json";

    /** 一行效果：per &gt; 0 = 每 per 點貢禮給一次；tributeIncrease = 「增加 x% 貢禮」。 */
    public record Line(String text, List<Modifier> stats, int per, double tributeIncrease) {
        public Line {
            stats = List.copyOf(stats);
        }
    }

    /** 換上去的節點（核心、基石、貢禮小點）；tribute = 這個節點給幾點貢禮。 */
    public record Template(String id, String name, String english, List<Line> lines, int tribute) {
        public Template {
            lines = List.copyOf(lines);
        }
    }

    public record Conqueror(String id, String name, String keystone) {
    }

    /**
     * 一個族。
     *
     * @param code      珠寶上 {@link #LEGION} 的值
     * @param small     小天賦換成的（null = 不換）
     * @param attribute 屬性點多給的（null = 不加）
     */
    public record Legion(String id, int code, String name, String unique, List<Conqueror> conquerors, List<Template> keystones,
                         List<Template> notables, Template small, Template attribute) {

        /** 第 index 個征服者（1 起算；超出範圍用第一個）。 */
        public Conqueror conqueror(final int index) {
            return conquerors.get(index >= 1 && index <= conquerors.size() ? index - 1 : 0);
        }

        public Template keystone(final int conquerorIndex) {
            final String id = conqueror(conquerorIndex).keystone();
            for (final Template template : keystones) {
                if (template.id().equals(id)) {
                    return template;
                }
            }
            return keystones.getFirst();
        }
    }

    /** 放在孔位 socket 的一顆永恆珠寶。 */
    public record Conquest(String socket, Legion legion, int seed, int conqueror) {
        public Conqueror conquerorInfo() {
            return legion.conqueror(conqueror);
        }
    }

    private static volatile Map<Integer, Legion> legions;

    /** 所有的族（珠寶上的代號 → 族）。 */
    public static Map<Integer, Legion> legions() {
        Map<Integer, Legion> loaded = legions;
        if (loaded == null) {
            synchronized (TimelessJewels.class) {
                loaded = legions;
                if (loaded == null) {
                    loaded = load();
                    legions = loaded;
                }
            }
        }
        return loaded;
    }

    private static Map<Integer, Legion> load() {
        try (InputStream in = TimelessJewels.class.getResourceAsStream(RESOURCE)) {
            if (in == null) {
                throw new IllegalStateException("找不到 " + RESOURCE);
            }
            final JsonObject root = JsonParser.parseReader(new InputStreamReader(in, StandardCharsets.UTF_8)).getAsJsonObject();
            final JsonObject legionsObject = root.getAsJsonObject("legions");
            final Map<Integer, Legion> out = new LinkedHashMap<>();
            int code = 0;
            for (final String id : List.of("kalguur", "abyss")) {
                code++;
                final JsonObject object = legionsObject.getAsJsonObject(id);
                final List<Conqueror> conquerors = new ArrayList<>();
                for (final JsonElement element : object.getAsJsonArray("conquerors")) {
                    final JsonObject c = element.getAsJsonObject();
                    conquerors.add(new Conqueror(c.get("id").getAsString(), c.get("name").getAsString(), c.get("keystone").getAsString()));
                }
                out.put(code, new Legion(id, code, object.get("name").getAsString(), object.get("unique").getAsString(), conquerors,
                        templates(object.getAsJsonArray("keystones")), templates(object.getAsJsonArray("notables")),
                        template(object.get("small")), template(object.get("attribute"))));
            }
            return Map.copyOf(out);
        } catch (final java.io.IOException e) {
            throw new IllegalStateException(RESOURCE + "：" + e.getMessage(), e);
        }
    }

    private static List<Template> templates(final JsonArray array) {
        final List<Template> list = new ArrayList<>();
        for (final JsonElement element : array) {
            list.add(template(element));
        }
        return list;
    }

    private static Template template(final JsonElement element) {
        if (element == null || element.isJsonNull()) {
            return null;
        }
        final JsonObject object = element.getAsJsonObject();
        final List<Line> lines = new ArrayList<>();
        final String name = object.get("name").getAsString();
        for (final JsonElement lineElement : object.getAsJsonArray("lines")) {
            final JsonObject line = lineElement.getAsJsonObject();
            final List<Modifier> stats = new ArrayList<>();
            for (final JsonElement statElement : line.getAsJsonArray("stats")) {
                final JsonObject stat = statElement.getAsJsonObject();
                final Set<String> tags = new HashSet<>();
                for (final JsonElement tag : stat.getAsJsonArray("tags")) {
                    tags.add(tag.getAsString());
                }
                stats.add(new Modifier(stat.get("stat").getAsString(), ModType.valueOf(stat.get("type").getAsString().toUpperCase(Locale.ROOT)),
                        stat.get("value").getAsDouble(), tags, "天賦 " + name));
            }
            lines.add(new Line(line.get("text").getAsString(), stats, line.get("per").getAsInt(),
                    line.has("tribute_increase") ? line.get("tribute_increase").getAsDouble() : 0));
        }
        return new Template(object.get("id").getAsString(), name, object.get("en").getAsString(), lines,
                object.has("tribute") ? object.get("tribute").getAsInt() : 0);
    }

    /** 這個基底是不是永恆珠寶。 */
    public static boolean isTimeless(final ItemBase base) {
        return base != null && base.isJewel() && base.tags().contains(BASE_TAG);
    }

    /** 這顆珠寶的征服（不是永恆珠寶、資料不完整回傳 null）。 */
    public static Conquest read(final ItemRepository repository, final String socket, final ItemData data) {
        if (repository == null || data == null || !isTimeless(repository.base(data.base()))) {
            return null;
        }
        int seed = 0;
        int conqueror = 1;
        int legion = 0;
        for (final Modifier mod : tw.exilecore.item.ItemStats.modifiers(repository, data)) {
            switch (mod.stat()) {
                case SEED -> seed = (int) Math.round(mod.value());
                case CONQUEROR -> conqueror = (int) Math.round(mod.value());
                case LEGION -> legion = (int) Math.round(mod.value());
                default -> {
                }
            }
        }
        final Legion found = legions().get(legion);
        return found == null ? null : new Conquest(socket, found, seed, conqueror);
    }

    /** 已配置的孔位裡的永恆珠寶（照孔位順序第一顆；PoE2 限 1 顆）；沒有回傳 null。 */
    public static Conquest active(final ItemRepository repository, final Map<String, ItemData> jewels, final Set<String> passives,
                                  final PassiveTree tree) {
        if (repository == null || jewels == null || jewels.isEmpty() || tree == null || passives == null) {
            return null;
        }
        for (final Map.Entry<String, ItemData> entry : jewels.entrySet()) {
            final PassiveNode socket = tree.node(entry.getKey());
            if (socket == null || !socket.isSocket() || !passives.contains(socket.id())) {
                continue;
            }
            final Conquest conquest = read(repository, socket.id(), entry.getValue());
            if (conquest != null) {
                return conquest;
            }
        }
        return null;
    }

    /** 範圍內會被征服的節點（不含珠寶孔、起點）。 */
    public static List<PassiveNode> inRadius(final PassiveTree tree, final PassiveNode socket) {
        final List<PassiveNode> list = new ArrayList<>();
        for (final PassiveNode node : JewelSockets.nodesInRadius(tree, socket, RADIUS)) {
            if (!node.isSocket() && !node.isStart()) {
                list.add(node);
            }
        }
        return list;
    }

    /**
     * 被征服的節點：節點代號 → 換上去的節點（位置、代號、種類不變）。conquest 為 null 回傳空的。
     *
     * @param allocated 已配置的天賦（深淵族照這些算貢禮，核心的「每 N 點貢禮」換成現在的數值）
     */
    public static Map<String, PassiveNode> conquered(final PassiveTree tree, final Conquest conquest, final Set<String> allocated) {
        if (tree == null || conquest == null) {
            return Map.of();
        }
        final PassiveNode socket = tree.node(conquest.socket());
        if (socket == null) {
            return Map.of();
        }
        final Legion legion = conquest.legion();
        final Map<String, Template> chosen = new LinkedHashMap<>();
        final Map<String, Boolean> attribute = new LinkedHashMap<>();
        for (final PassiveNode node : inRadius(tree, socket)) {
            switch (node.type()) {
                case NOTABLE -> chosen.put(node.id(), notable(legion, conquest.seed(), node.id()));
                case KEYSTONE -> chosen.put(node.id(), legion.keystone(conquest.conqueror()));
                case SMALL -> {
                    if (node.attributeChoice() > 0 && legion.attribute() != null) {
                        chosen.put(node.id(), legion.attribute());
                        attribute.put(node.id(), true);
                    } else if (node.attributeChoice() <= 0 && legion.small() != null) {
                        chosen.put(node.id(), legion.small());
                    }
                }
                default -> {
                }
            }
        }
        final int tribute = tribute(chosen, allocated);
        final Map<String, PassiveNode> out = new LinkedHashMap<>();
        for (final Map.Entry<String, Template> entry : chosen.entrySet()) {
            final PassiveNode original = tree.node(entry.getKey());
            out.put(entry.getKey(), build(original, entry.getValue(), attribute.containsKey(entry.getKey()), tribute, legion));
        }
        return out;
    }

    /** 已配置的被征服節點給的貢禮（含「增加 x% 貢禮」，無條件捨去）。 */
    static int tribute(final Map<String, Template> chosen, final Set<String> allocated) {
        if (allocated == null) {
            return 0;
        }
        double base = 0;
        double increase = 0;
        for (final Map.Entry<String, Template> entry : chosen.entrySet()) {
            if (!allocated.contains(entry.getKey())) {
                continue;
            }
            base += entry.getValue().tribute();
            for (final Line line : entry.getValue().lines()) {
                increase += line.tributeIncrease();
            }
        }
        return (int) Math.floor(base * (1 + increase / 100.0) + 1e-9);
    }

    /** 換上去的節點：效果照貢禮算好；屬性點保留原本的效果再加上附加的。 */
    private static PassiveNode build(final PassiveNode original, final Template template, final boolean addition, final int tribute,
                                     final Legion legion) {
        final List<Modifier> mods = new ArrayList<>();
        final List<String> description = new ArrayList<>();
        if (addition) {
            mods.addAll(original.modifiers());
            description.addAll(original.description());
        }
        boolean perTribute = false;
        for (final Line line : template.lines()) {
            description.add(line.text());
            if (line.per() > 0) {
                perTribute = true;
                final int times = tribute / line.per();
                for (final Modifier stat : line.stats()) {
                    if (times > 0) {
                        mods.add(new Modifier(stat.stat(), stat.type(), stat.value() * times, stat.tags(), stat.source()));
                    }
                }
            } else {
                mods.addAll(line.stats());
            }
        }
        if (perTribute) {
            description.add("（目前已配置的貢禮：" + tribute + "）");
        }
        final String name = addition ? original.name() : template.name();
        return new PassiveNode(original.id(), original.type(), name, original.x(), original.y(), original.icon(), mods, description,
                null, Map.of());
    }

    /** 這個核心換成哪一個（種子、族、節點代號的確定性雜湊；見類別說明）。 */
    static Template notable(final Legion legion, final int seed, final String nodeId) {
        final List<Template> notables = legion.notables();
        return notables.get(Math.floorMod(mix(seed, legion.code(), nodeId), notables.size()));
    }

    static long mix(final int seed, final int legion, final String nodeId) {
        long node;
        try {
            node = Long.parseLong(nodeId.startsWith("p") ? nodeId.substring(1) : nodeId);
        } catch (final NumberFormatException e) {
            node = nodeId.hashCode();
        }
        long z = seed * 0x9E3779B97F4A7C15L + node * 0xC2B2AE3D27D4EB4FL + legion * 0x165667B19E3779F9L;
        // SplitMix64 的收尾
        z = (z ^ (z >>> 30)) * 0xBF58476D1CE4E5B9L;
        z = (z ^ (z >>> 27)) * 0x94D049BB133111EBL;
        return (z ^ (z >>> 31)) & Long.MAX_VALUE;
    }

    /** 天賦樹給的修飾器：跟 {@link PassiveTree#modifiers(Set, String)} 一樣，被征服的節點換成征服後的。 */
    public static List<Modifier> treeModifiers(final PassiveTree tree, final Set<String> allocated, final String classId,
                                               final Map<String, PassiveNode> conquered) {
        if (conquered == null || conquered.isEmpty()) {
            return tree.modifiers(allocated, classId);
        }
        final List<Modifier> list = new ArrayList<>();
        for (final String id : allocated) {
            final PassiveNode replaced = conquered.get(id);
            if (replaced != null) {
                list.addAll(replaced.modifiers());
                continue;
            }
            final PassiveNode node = tree.node(id);
            if (node != null) {
                list.addAll(node.forClass(classId).modifiers());
            }
        }
        return list;
    }

    /** 同上，直接從角色身上的珠寶算（物品資料；虛擬角色也用這個）。 */
    public static List<Modifier> treeModifiers(final ItemRepository repository, final Map<String, ItemData> jewels, final PassiveTree tree,
                                               final Set<String> allocated, final String classId) {
        return treeModifiers(tree, allocated, classId, conquered(tree, active(repository, jewels, allocated, tree), allocated));
    }

    /** 玩家現在被征服的節點（天賦樹畫面、說明用）。 */
    public static Map<String, PassiveNode> conquered(final tw.exilecore.ExileCore plugin, final tw.exilecore.character.PlayerState state,
                                                     final PassiveTree tree) {
        if (plugin.items() == null || tree == null || state.jewels().isEmpty()) {
            return Map.of();
        }
        final Map<String, ItemData> data = JewelSockets.jewelData(plugin, state);
        return conquered(tree, active(plugin.items().repository(), data, state.passives(), tree), state.passives());
    }

    /** 珠寶說明用：這顆永恆珠寶的效果（給玩家看的幾行）。 */
    public static List<String> explain(final Conquest conquest) {
        final List<String> lines = new ArrayList<>();
        if (conquest == null) {
            return lines;
        }
        final Legion legion = conquest.legion();
        final Template keystone = legion.keystone(conquest.conqueror());
        lines.add("珠寶孔周圍 " + (int) RADIUS + "（非常大）範圍內的天賦受" + legion.name() + "征服：");
        lines.add("・核心換成" + legion.name() + "的核心（種子 " + conquest.seed() + " 決定哪一個）");
        lines.add("・基石換成" + conquest.conquerorInfo().name() + "的「" + keystone.name() + "」");
        if (legion.small() != null) {
            lines.add("・小天賦換成「" + legion.small().lines().getFirst().text() + "」，屬性點多給「"
                    + (legion.attribute() == null ? "" : legion.attribute().lines().getFirst().text()) + "」");
        }
        lines.add("同時只能配一顆恆時珠寶");
        return lines;
    }
}
