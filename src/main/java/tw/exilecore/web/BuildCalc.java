package tw.exilecore.web;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import tw.exilecore.ExileCore;
import tw.exilecore.character.CharacterClass;
import tw.exilecore.character.PlayerState;
import tw.exilecore.character.PlayerStateService;
import tw.exilecore.character.SkillLoadout;
import tw.exilecore.character.StatsReport;
import tw.exilecore.character.VirtualCharacter;
import tw.exilecore.item.ItemData;
import tw.exilecore.passive.PassiveTree;
import tw.exilecore.passive.TreeCode;
import tw.exilecore.skill.SkillConfig;
import tw.exilecore.skill.SkillDefinition;
import tw.exilecore.skill.SkillService;
import tw.exilecore.skill.SupportDefinition;
import tw.exilecore.stats.ModType;
import tw.exilecore.stats.Modifier;
import tw.exilecore.stats.StatSheet;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * 網頁計算器（2026-10）：網頁送來一個角色（天賦、昇華、裝備、技能），用遊戲裡同一套算法算屬性與每個技能的每秒傷害，
 * 也可以順便算「這個技能再掛一顆輔助寶石會多幾 %」。
 * <p>
 * 送來的格式（JSON）：
 * <pre>
 * {
 *   "tree": "EC1.2.witch.xxxx",            天賦規劃器的代碼（職業、天賦、屬性點的選擇）
 *   "level": 70,
 *   "asc": "lich", "ascNodes": ["…"],      昇華職業與昇華天賦（可以不寫）
 *   "progress": { … },                     角色頁快照的進度（任務、Boss 首殺、試煉；只收認得的鍵）
 *   "gear": [物品資料 | null × 10],          照 PlayerState.JEWELLERY_SLOTS 的順序
 *   "jewels": { "節點": 物品資料 },
 *   "skills": [{ "id", "level", "sockets", "supports": [...] }],   技能面板（最多 27 個）
 *   "binds": [技能的位置 | -1 × 10],         按鍵格（左、右、蹲左、蹲右、數字鍵 3–8）
 *   "extra": [{ "stat", "type", "value", "tags", "source" }],      快照記的稱號、公會、贊助、分身獎勵
 *   "supportsFor": 2,                       要比較輔助寶石的技能位置（可以不寫）
 *   "instilFor": -1                         要比較項鍊注入哪個核心（蒸餾情緒）：看哪個技能位置的傷害，-1 = 傷害最高的那個（可以不寫）
 * }
 * </pre>
 * 讀進來的部分（{@link #parse}）是純函式，可以單元測試；算的部分要插件的模組。
 */
public final class BuildCalc {

    private static final Gson GSON = new GsonBuilder().disableHtmlEscaping().create();
    /** 進度只收這些開頭的鍵（算加成用得到的）。 */
    static final List<String> PROGRESS_PREFIXES = List.of("q.", "boss.", "trial.", "asc", "attr.");
    static final int MAX_EXTRA = 400;
    static final int MAX_PROGRESS = 400;
    static final int MAX_JEWELS = 40;

    /** 讀進來的角色。 */
    public record Build(CharacterClass characterClass, int level, Set<String> passives, Map<String, String> progress,
                        List<ItemData> gear, Map<String, ItemData> jewels, SkillLoadout skills, List<Modifier> extra,
                        Integer supportsFor, Integer instilFor) {
    }

    /** 讀不懂送來的東西。 */
    public static final class BadRequest extends Exception {
        public BadRequest(final String message) {
            super(message);
        }
    }

    private final ExileCore plugin;
    /** 2026-10-18：對方的 IP（跟贊助商店同一套：代理後面看代理給的標頭；以前用 remote()，接代理後全服共用一個桶）。 */
    private final java.util.function.Function<WebServer.Request, String> clientIp;

    BuildCalc(final ExileCore plugin, final java.util.function.Function<WebServer.Request, String> clientIp) {
        this.plugin = plugin;
        this.clientIp = clientIp;
    }

    // ---- 讀 ----

    /** 讀網頁送來的 JSON（純函式：天賦樹與技能資料要給）。 */
    public static Build parse(final String body, final PassiveTree tree, final SkillConfig skills) throws BadRequest {
        final JsonObject json;
        try {
            final JsonElement element = JsonParser.parseString(body == null ? "" : body);
            if (!element.isJsonObject()) {
                throw new BadRequest("要送一個 JSON 物件");
            }
            json = element.getAsJsonObject();
        } catch (final RuntimeException e) {
            throw new BadRequest("看不懂送來的 JSON");
        }
        if (tree == null) {
            throw new BadRequest("天賦樹還沒載入");
        }
        // 天賦（含職業與屬性點的選擇）
        final TreeCode.Decoded decoded;
        try {
            decoded = TreeCode.decode(tree, string(json, "tree"));
        } catch (final TreeCode.InvalidCode e) {
            throw new BadRequest(e.getMessage());
        }
        final CharacterClass characterClass = CharacterClass.parse(decoded.classId(), null);
        if (characterClass == null) {
            throw new BadRequest("不認得的職業：" + decoded.classId());
        }
        final int level = json.has("level") && json.get("level").isJsonPrimitive() ? clamp(json.get("level").getAsInt(), 1, VirtualCharacter.MAX_LEVEL) : 1;
        // 進度：快照的（只收認得的鍵），再蓋上這次的屬性點與昇華
        final Map<String, String> progress = new LinkedHashMap<>();
        if (json.has("progress") && json.get("progress").isJsonObject()) {
            for (final Map.Entry<String, JsonElement> entry : json.getAsJsonObject("progress").entrySet()) {
                if (progress.size() >= MAX_PROGRESS) {
                    break;
                }
                if (allowedProgress(entry.getKey()) && entry.getValue().isJsonPrimitive()) {
                    progress.put(entry.getKey(), entry.getValue().getAsString());
                }
            }
        }
        progress.keySet().removeIf(key -> key.startsWith("attr."));
        decoded.attributes().forEach((node, attr) -> progress.put("attr." + node, attr));
        if (json.has("asc")) {
            progress.remove(tw.exilecore.ascendancy.Ascension.CHOSEN);
            progress.remove(tw.exilecore.ascendancy.Ascension.NODES);
            final String asc = json.get("asc").isJsonNull() ? null : json.get("asc").getAsString().trim();
            if (asc != null && !asc.isEmpty()) {
                progress.put(tw.exilecore.ascendancy.Ascension.CHOSEN, asc);
                final List<String> nodes = new ArrayList<>();
                for (final String id : strings(json, "ascNodes", 64)) {
                    if (id.matches("[A-Za-z0-9_.-]{1,64}")) {
                        nodes.add(id);
                    }
                }
                if (!nodes.isEmpty()) {
                    progress.put(tw.exilecore.ascendancy.Ascension.NODES, String.join(",", nodes));
                }
            }
        }
        // 裝備與珠寶
        final List<ItemData> gear = new ArrayList<>();
        final JsonArray gearArray = json.has("gear") && json.get("gear").isJsonArray() ? json.getAsJsonArray("gear") : new JsonArray();
        for (int i = 0; i < PlayerState.JEWELLERY_SLOTS.length; i++) {
            gear.add(i < gearArray.size() ? item(gearArray.get(i)) : null);
        }
        final Map<String, ItemData> jewels = new LinkedHashMap<>();
        if (json.has("jewels") && json.get("jewels").isJsonObject()) {
            for (final Map.Entry<String, JsonElement> entry : json.getAsJsonObject("jewels").entrySet()) {
                if (jewels.size() >= MAX_JEWELS) {
                    break;
                }
                final ItemData data = item(entry.getValue());
                if (data != null && tree.node(entry.getKey()) != null) {
                    jewels.put(entry.getKey(), data);
                }
            }
        }
        // 技能面板
        final SkillLoadout loadout = new SkillLoadout();
        final List<Integer> positions = new ArrayList<>();
        final JsonArray skillArray = json.has("skills") && json.get("skills").isJsonArray() ? json.getAsJsonArray("skills") : new JsonArray();
        for (int i = 0; i < skillArray.size() && i < SkillLoadout.MAX_SKILLS; i++) {
            positions.add(-1);
            if (!skillArray.get(i).isJsonObject()) {
                continue;
            }
            final JsonObject s = skillArray.get(i).getAsJsonObject();
            final String id = string(s, "id");
            final SkillDefinition definition = skills == null || id == null ? null : skills.skill(id);
            if (definition == null || definition.hidden()) {
                continue;
            }
            final List<String> supports = new ArrayList<>();
            for (final String supportId : strings(s, "supports", SkillLoadout.MAX_SOCKETS)) {
                // 2026-10-18：共鳴的輔助代號後面有 ~（照存檔的寫法），查定義時剝掉、放進 Entry 時保留讓它記成共鳴
                final String clean = supportId.endsWith(SkillLoadout.Entry.RESONANT_SUFFIX)
                        ? supportId.substring(0, supportId.length() - SkillLoadout.Entry.RESONANT_SUFFIX.length()) : supportId;
                supports.add(skills.support(clean) == null ? "" : supportId);
            }
            final int sockets = s.has("sockets") ? s.get("sockets").getAsInt() : Math.max(SkillLoadout.BASE_SOCKETS, supports.size());
            final int level1 = s.has("level") ? s.get("level").getAsInt() : 1;
            final int position = loadout.add(new SkillLoadout.Entry(definition.id(), level1, sockets, supports));
            positions.set(i, position);
        }
        if (json.has("binds") && json.get("binds").isJsonArray()) {
            final JsonArray binds = json.getAsJsonArray("binds");
            for (int slot = 0; slot < SkillLoadout.BIND_SLOTS && slot < binds.size(); slot++) {
                final int index = binds.get(slot).isJsonPrimitive() ? binds.get(slot).getAsInt() : -1;
                if (index >= 0 && index < positions.size() && positions.get(index) >= 0) {
                    loadout.bind(slot, positions.get(index));
                }
            }
        }
        // 快照記的加成
        final List<Modifier> extra = new ArrayList<>();
        if (json.has("extra") && json.get("extra").isJsonArray()) {
            for (final JsonElement element : json.getAsJsonArray("extra")) {
                if (extra.size() >= MAX_EXTRA) {
                    break;
                }
                final Modifier modifier = modifier(element);
                if (modifier != null) {
                    extra.add(modifier);
                }
            }
        }
        Integer supportsFor = null;
        if (json.has("supportsFor") && json.get("supportsFor").isJsonPrimitive()) {
            final int index = json.get("supportsFor").getAsInt();
            if (index >= 0 && index < positions.size() && positions.get(index) >= 0) {
                supportsFor = positions.get(index);
            }
        }
        Integer instilFor = null;
        if (json.has("instilFor") && json.get("instilFor").isJsonPrimitive()) {
            final int index = json.get("instilFor").getAsInt();
            instilFor = index >= 0 && index < positions.size() && positions.get(index) >= 0 ? positions.get(index) : -1;
        }
        return new Build(characterClass, level, new LinkedHashSet<>(decoded.nodes()), progress, gear, jewels, loadout, extra, supportsFor,
                instilFor);
    }

    static boolean allowedProgress(final String key) {
        if (key == null || key.length() > 96) {
            return false;
        }
        for (final String prefix : PROGRESS_PREFIXES) {
            if (key.startsWith(prefix)) {
                return true;
            }
        }
        return false;
    }

    private static ItemData item(final JsonElement element) {
        if (element == null || !element.isJsonObject()) {
            return null;
        }
        try {
            final ItemData data = GSON.fromJson(element, ItemData.class);
            return data == null || data.base() == null || data.rarity() == null ? null : data;
        } catch (final RuntimeException e) {
            return null; // 壞掉的物品當成沒穿
        }
    }

    /** 快照記的一條修飾器；看不懂回傳 null。 */
    static Modifier modifier(final JsonElement element) {
        if (element == null || !element.isJsonObject()) {
            return null;
        }
        final JsonObject o = element.getAsJsonObject();
        try {
            final String stat = string(o, "stat");
            final ModType type = ModType.valueOf(string(o, "type"));
            final double value = o.get("value").getAsDouble();
            if (stat == null || stat.isEmpty() || stat.length() > 96 || !Double.isFinite(value)) {
                return null;
            }
            final Set<String> tags = new LinkedHashSet<>(strings(o, "tags", 16));
            final String source = string(o, "source");
            return new Modifier(stat, type, value, tags, source == null ? "" : source);
        } catch (final RuntimeException e) {
            return null;
        }
    }

    /** 一條修飾器寫成快照的 JSON（{@link #modifier} 讀得回來）。 */
    public static JsonObject modifierJson(final Modifier modifier) {
        final JsonObject o = new JsonObject();
        o.addProperty("stat", modifier.stat());
        o.addProperty("type", modifier.type().name());
        o.addProperty("value", modifier.value());
        final JsonArray tags = new JsonArray();
        modifier.tags().forEach(tags::add);
        o.add("tags", tags);
        o.addProperty("source", modifier.source());
        return o;
    }

    private static String string(final JsonObject o, final String key) {
        return o.has(key) && o.get(key).isJsonPrimitive() ? o.get(key).getAsString() : null;
    }

    private static List<String> strings(final JsonObject o, final String key, final int limit) {
        final List<String> list = new ArrayList<>();
        if (o.has(key) && o.get(key).isJsonArray()) {
            for (final JsonElement e : o.getAsJsonArray(key)) {
                if (list.size() >= limit) {
                    break;
                }
                if (e.isJsonPrimitive()) {
                    list.add(e.getAsString());
                } else {
                    list.add("");
                }
            }
        }
        return list;
    }

    private static int clamp(final int value, final int min, final int max) {
        return Math.max(min, Math.min(max, value));
    }

    // ---- 算 ----

    /** 一次計算的結果。 */
    private record Result(PlayerState state, StatSheet sheet, SkillService.VirtualAuras auras, List<String> inactive) {
    }

    /**
     * 網頁上配的物品（不是遊戲裡拿來的）缺的東西補上，跟真的物品一樣算（2026-10）：
     * 一律當成已鑑定；固定詞綴沒寫數值的用那個物品等級的最大值；傳奇的版本寫 0（網頁上配的）用現在的版本。純函式。
     */
    static ItemData normalize(final tw.exilecore.item.ItemRepository repository, final ItemData source) {
        if (source == null || repository == null) {
            return source;
        }
        final tw.exilecore.item.ItemBase base = repository.base(source.base());
        if (base == null) {
            return null; // 不認得的基底當成沒穿
        }
        final ItemData data = source.copy();
        data.setIdentified(true);
        if (data.unique() != null && data.uniqueVersion() <= 0) {
            final tw.exilecore.item.UniqueItem unique = repository.unique(data.unique());
            if (unique != null) {
                data.setUniqueVersion(unique.version());
            }
        }
        if (data.implicitVars().isEmpty() && base.implicit() != null) {
            final tw.exilecore.item.Implicit implicit = tw.exilecore.item.ItemStats.implicit(repository, base, data);
            if (implicit != null && implicit.vars() != null) {
                final Map<String, Integer> max = new LinkedHashMap<>();
                implicit.vars().forEach((name, range) -> max.put(name, range[range.length - 1]));
                data.setImplicitVars(max);
            }
        }
        return data;
    }

    private Result evaluate(final Build build, final SkillLoadout skills) {
        final int level = Math.min(build.level(), plugin.players().config().levelCap());
        final PlayerState state = VirtualCharacter.state(build.characterClass(), level, build.passives(), build.progress(), skills);
        final PlayerStateService players = plugin.players();
        final tw.exilecore.item.ItemRepository repository = plugin.items() == null ? null : plugin.items().repository();
        final List<ItemData> gear = new ArrayList<>();
        for (final ItemData data : build.gear()) {
            gear.add(normalize(repository, data));
        }
        final Map<String, ItemData> jewels = new LinkedHashMap<>();
        build.jewels().forEach((node, data) -> {
            final ItemData normalized = normalize(repository, data);
            if (normalized != null) {
                jewels.put(node, normalized);
            }
        });
        final PlayerStateService.VirtualInputs inputs = new PlayerStateService.VirtualInputs(gear, jewels, build.extra());
        // 先算沒有光環的表，照它的精魂決定開哪些光環，再把光環的效果加進去重算（跟遊戲裡一樣：光環是屬性表的來源之一）
        final PlayerStateService.VirtualSheet base = players.virtualSheet(state, inputs);
        final SkillService.VirtualAuras auras = plugin.skills() == null ? null : plugin.skills().virtualAuras(state, base.sheet());
        final PlayerStateService.VirtualSheet full = auras == null || auras.self().isEmpty() ? base
                : players.virtualSheet(state, inputs.plus(auras.self()));
        return new Result(state, full.sheet(), auras, full.inactive());
    }

    /** 算一個角色，回傳網頁要的 JSON。 */
    public byte[] compute(final Build build) {
        final Result result = evaluate(build, build.skills());
        final JsonObject root = new JsonObject();
        final JsonObject numbers = new JsonObject();
        StatsReport.numbers(plugin, result.sheet(), build.level()).forEach(numbers::addProperty);
        root.add("numbers", numbers);
        final JsonArray inactive = new JsonArray();
        result.inactive().forEach(inactive::add);
        root.add("inactive", inactive);
        final SkillService skills = plugin.skills();
        final JsonArray rows = new JsonArray();
        if (skills != null) {
            for (final SkillService.DpsRow row : skills.skillDps(result.state(), result.sheet(), auraView(result))) {
                rows.add(rowJson(row));
            }
            final JsonObject auras = new JsonObject();
            final JsonArray active = new JsonArray();
            final JsonArray skipped = new JsonArray();
            if (result.auras() != null) {
                for (final String id : result.auras().active()) {
                    final SkillDefinition definition = skills.config().skill(id);
                    active.add(definition == null ? id : definition.name());
                }
                result.auras().skipped().forEach(skipped::add);
                auras.addProperty("reserved", result.auras().reserved());
            }
            auras.add("active", active);
            auras.add("skipped", skipped);
            root.add("auras", auras);
            if (build.supportsFor() != null) {
                root.add("supports", supports(build, result));
            }
            if (build.instilFor() != null) {
                root.add("instils", instils(build, result));
            }
        }
        root.add("skills", rows);
        root.add("jewels", jewelEffects(build));
        return GSON.toJson(root).getBytes(StandardCharsets.UTF_8);
    }

    /**
     * 珠寶孔裡的珠寶實際給了什麼（2026-10-16：網頁天賦樹上直接看得到）：每個有配、有放珠寶的孔位一筆——
     * 半徑（遺時星晶才有）、範圍內已配的節點、這顆珠寶加在角色身上的所有效果（遺時星晶的「範圍內小點各給 x」已經乘上小點數）。
     */
    private JsonArray jewelEffects(final Build build) {
        final JsonArray out = new JsonArray();
        final PassiveTree tree = plugin.passives() == null ? null : plugin.passives().tree();
        final tw.exilecore.item.ItemRepository repository = plugin.items() == null ? null : plugin.items().repository();
        if (tree == null || repository == null) {
            return out;
        }
        for (final Map.Entry<String, ItemData> entry : build.jewels().entrySet()) {
            final tw.exilecore.passive.PassiveNode socket = tree.node(entry.getKey());
            final ItemData data = normalize(repository, entry.getValue());
            if (socket == null || !socket.isSocket() || data == null || !build.passives().contains(socket.id())) {
                continue;
            }
            final tw.exilecore.item.ItemBase base = repository.base(data.base());
            final List<Modifier> mods;
            try {
                mods = tw.exilecore.passive.JewelSockets.modifiers(repository, Map.of(socket.id(), data), build.passives(), tree);
            } catch (final RuntimeException e) {
                continue;
            }
            final JsonObject jewel = new JsonObject();
            jewel.addProperty("node", socket.id());
            final JsonArray inRange = new JsonArray();
            if (base != null && base.isRadiusJewel()) {
                final double radius = tw.exilecore.passive.JewelSockets.radiusOf(tw.exilecore.item.ItemStats.modifiers(repository, data));
                jewel.addProperty("radius", radius);
                for (final tw.exilecore.passive.PassiveNode near : tw.exilecore.passive.JewelSockets.nodesInRadius(tree, socket, radius)) {
                    if (build.passives().contains(near.id())) {
                        inRange.add(near.id());
                    }
                }
            }
            jewel.add("inRange", inRange);
            final JsonArray lines = new JsonArray();
            tw.exilecore.skill.ModifierText.describeAll(mods).forEach(lines::add);
            jewel.add("lines", lines);
            out.add(jewel);
        }
        return out;
    }

    private static SkillService.AuraView auraView(final Result result) {
        if (result.auras() != null) {
            return result.auras();
        }
        return new SkillService.AuraView() {
            @Override
            public boolean active(final String skillId) {
                return false;
            }

            @Override
            public int minionCap(final String skillId) {
                return -1;
            }

            @Override
            public List<Modifier> minionEffects() {
                return List.of();
            }
        };
    }

    private static JsonObject rowJson(final SkillService.DpsRow row) {
        final JsonObject o = new JsonObject();
        o.addProperty("slot", row.slot());
        o.addProperty("key", row.slot() < 0 ? "" : SkillLoadout.keyName(row.slot()));
        o.addProperty("position", row.position());
        o.addProperty("id", row.skillId());
        o.addProperty("name", row.name());
        o.addProperty("dps", row.dps());
        o.addProperty("full", row.full());
        o.addProperty("minions", row.minions());
        o.addProperty("hit", row.hit());
        o.addProperty("rate", row.rate());
        o.addProperty("crit", row.crit());
        return o;
    }

    /**
     * 輔助寶石比較（PoE 的「這顆輔助多幾 %」）：每一顆能掛上去、還沒掛的輔助，假設掛在這個技能上（沒有空孔就假設多開一孔）
     * 算一次每秒傷害；掛著的每一顆，假設拿掉算一次。屬性表不變（輔助只改這個技能的數值），所以很快。
     */
    private JsonObject supports(final Build build, final Result result) {
        final SkillService skills = plugin.skills();
        final SkillConfig config = skills.config();
        final int position = build.supportsFor();
        final SkillLoadout.Entry entry = result.state().skills().skill(position);
        final SkillDefinition definition = config.skill(entry.skillId());
        final SkillService.AuraView view = auraView(result);
        final JsonObject out = new JsonObject();
        out.addProperty("position", position);
        out.addProperty("name", definition.name());
        final SkillService.DpsRow base = skills.skillDpsAt(result.state(), result.sheet(), view, position);
        out.addProperty("dps", base == null ? 0 : base.dps());
        out.addProperty("full", base == null ? 0 : base.full());
        out.addProperty("freeSocket", entry.freeSocket() >= 0);
        final JsonArray add = new JsonArray();
        for (final SupportDefinition support : config.supports()) {
            if (entry.hasSupport(support.id())) {
                continue;
            }
            final JsonObject o = new JsonObject();
            o.addProperty("id", support.id());
            o.addProperty("name", support.name());
            o.addProperty("tier", support.tier());
            final String refusal = support.refusal(definition);
            if (refusal != null) {
                continue; // 掛不上去的不列
            }
            String problem = null;
            if (config.oncePerCharacter(support.id())) {
                final int used = result.state().skills().supportUsedBy(support.id());
                if (used >= 0 && used != position) {
                    problem = "已經掛在別的技能上（這種一個角色只能一顆）";
                }
            }
            SkillLoadout.Entry trial = entry.freeSocket() >= 0 ? entry : entry.withSockets(entry.sockets() + 1);
            if (trial.freeSocket() < 0) {
                problem = problem == null ? "輔助孔滿了（假設換掉一顆）" : problem;
                trial = null;
            } else {
                trial = trial.withSupport(trial.freeSocket(), support.id());
            }
            final SkillService.DpsRow row = trial == null ? replaceBest(result, view, position, entry, support.id())
                    : withEntry(result, view, position, trial);
            put(o, row, base);
            if (problem != null) {
                o.addProperty("problem", problem);
            }
            add.add(o);
        }
        out.add("add", add);
        final JsonArray remove = new JsonArray();
        for (int i = 0; i < entry.sockets(); i++) {
            final String id = entry.support(i);
            if (id.isEmpty()) {
                continue;
            }
            final SupportDefinition support = config.support(id);
            final JsonObject o = new JsonObject();
            o.addProperty("id", id);
            o.addProperty("name", support == null ? id : support.name());
            put(o, withEntry(result, view, position, entry.withSupport(i, "")), base);
            remove.add(o);
        }
        out.add("remove", remove);
        return out;
    }

    /** 裝備欄裡項鍊是第幾格。 */
    static int amuletIndex() {
        for (int i = 0; i < PlayerState.JEWELLERY_SLOTS.length; i++) {
            if (PlayerState.JEWELLERY_SLOTS[i] == tw.exilecore.item.ItemSlot.AMULET) {
                return i;
            }
        }
        return -1;
    }

    /**
     * 項鍊注入比較（2026-10，蒸餾情緒）：天賦樹上每一個還沒配的核心，假設注入在項鍊上（換掉原本注入的）重算一次，
     * 看某個技能的每秒傷害多多少（跟「沒注入」比）。注入會改屬性（力量、敏捷…），所以每一個都整張屬性表重算。
     */
    private JsonObject instils(final Build build, final Result result) {
        final JsonObject out = new JsonObject();
        final int amulet = amuletIndex();
        final ItemData current = amulet < 0 ? null : build.gear().get(amulet);
        if (current == null) {
            out.addProperty("error", "先配一條項鍊");
            return out;
        }
        final SkillService skills = plugin.skills();
        final PassiveTree tree = plugin.passives() == null ? null : plugin.passives().tree();
        if (tree == null) {
            out.addProperty("error", "天賦樹還沒載入");
            return out;
        }
        // 看哪個技能：指定的，不然現在傷害最高的那個
        int position = build.instilFor() != null && build.instilFor() >= 0 ? build.instilFor() : -1;
        if (position < 0) {
            double best = -1;
            for (final SkillService.DpsRow row : skills.skillDps(result.state(), result.sheet(), auraView(result))) {
                if (row.full() > best) {
                    best = row.full();
                    position = row.position();
                }
            }
        }
        if (position < 0) {
            out.addProperty("error", "沒有能算傷害的技能（加技能、綁按鍵）");
            return out;
        }
        final SkillLoadout.Entry entry = result.state().skills().skill(position);
        final SkillDefinition definition = entry == null ? null : skills.config().skill(entry.skillId());
        out.addProperty("position", position);
        out.addProperty("name", definition == null ? "?" : definition.name());
        out.addProperty("current", current.instilled());
        final ItemData plain = current.copy();
        plain.setInstilled(null);
        final SkillService.DpsRow base = dpsWith(build, amulet, plain, position);
        out.addProperty("dps", base == null ? 0 : base.dps());
        out.addProperty("full", base == null ? 0 : base.full());
        final List<JsonObject> list = new ArrayList<>();
        for (final tw.exilecore.passive.PassiveNode node : tree.nodes()) {
            if (node.type() != tw.exilecore.passive.PassiveNode.Type.NOTABLE || build.passives().contains(node.id())) {
                continue; // 天賦樹上已經配了：注入沒有效果
            }
            final ItemData trial = current.copy();
            trial.setInstilled(node.id());
            final SkillService.DpsRow row = dpsWith(build, amulet, trial, position);
            final JsonObject o = new JsonObject();
            o.addProperty("id", node.id());
            o.addProperty("name", node.name());
            put(o, row, base);
            list.add(o);
        }
        list.sort((a, b) -> Double.compare(b.get("full").getAsDouble(), a.get("full").getAsDouble()));
        final JsonArray array = new JsonArray();
        list.forEach(array::add);
        out.add("list", array);
        return out;
    }

    /** 項鍊換成 amulet 時第 position 個技能的每秒傷害（其他都不變）。 */
    private SkillService.DpsRow dpsWith(final Build build, final int index, final ItemData amulet, final int position) {
        final List<ItemData> gear = new ArrayList<>(build.gear());
        gear.set(index, amulet);
        final Build trial = new Build(build.characterClass(), build.level(), build.passives(), build.progress(), gear, build.jewels(),
                build.skills(), build.extra(), null, null);
        final Result result = evaluate(trial, trial.skills());
        return plugin.skills().skillDpsAt(result.state(), result.sheet(), auraView(result), position);
    }

    /** 孔滿了：換掉掛著的哪一顆最好（傷害最高的那個結果）。 */
    private SkillService.DpsRow replaceBest(final Result result, final SkillService.AuraView view, final int position,
                                            final SkillLoadout.Entry entry, final String supportId) {
        SkillService.DpsRow best = null;
        for (int i = 0; i < entry.sockets(); i++) {
            final SkillService.DpsRow row = withEntry(result, view, position, entry.withSupport(i, supportId));
            if (row != null && (best == null || row.full() > best.full())) {
                best = row;
            }
        }
        return best;
    }

    /** 技能面板第 position 格換成 trial 時這個技能的每秒傷害（其他都不變）。 */
    private SkillService.DpsRow withEntry(final Result result, final SkillService.AuraView view, final int position, final SkillLoadout.Entry trial) {
        final SkillLoadout original = result.state().skills();
        final SkillLoadout copy = original.copy();
        copy.set(position, trial);
        result.state().setSkills(copy);
        try {
            return plugin.skills().skillDpsAt(result.state(), result.sheet(), view, position);
        } finally {
            result.state().setSkills(original);
        }
    }

    private static void put(final JsonObject o, final SkillService.DpsRow row, final SkillService.DpsRow base) {
        final double dps = row == null ? 0 : row.dps();
        final double full = row == null ? 0 : row.full();
        o.addProperty("dps", dps);
        o.addProperty("full", full);
        final double baseFull = base == null ? 0 : base.full();
        o.addProperty("pct", baseFull <= 0 ? 0 : (full / baseFull - 1) * 100);
    }

    /** 網頁計算器的技能與輔助清單（/tree/skills.json）。 */
    static byte[] skillsJson(final SkillConfig config) {
        final JsonObject root = new JsonObject();
        final JsonArray skills = new JsonArray();
        for (final SkillDefinition definition : config.all()) {
            if (definition.hidden()) {
                continue;
            }
            final JsonObject o = new JsonObject();
            o.addProperty("id", definition.id());
            o.addProperty("name", definition.name());
            o.addProperty("type", definition.typeName());
            o.addProperty("tier", definition.tier());
            o.addProperty("weapon", definition.weaponText());
            o.addProperty("description", definition.description() == null ? "" : definition.description());
            o.addProperty("aura", definition.aura() != null);
            o.addProperty("summon", definition.summon() != null);
            o.addProperty("damage", definition.dealsDamage() || definition.summon() != null);
            final JsonArray tags = new JsonArray();
            definition.tags().stream().sorted().forEach(tags::add);
            o.add("tags", tags);
            skills.add(o);
        }
        root.add("skills", skills);
        final JsonArray supports = new JsonArray();
        for (final SupportDefinition support : config.supports()) {
            final JsonObject o = new JsonObject();
            o.addProperty("id", support.id());
            o.addProperty("name", support.name());
            o.addProperty("tier", support.tier());
            o.addProperty("description", support.descriptionText());
            o.addProperty("requires", support.requiresText());
            supports.add(o);
        }
        root.add("supports", supports);
        root.addProperty("maxLevel", SkillLoadout.GEM_CAP);
        root.addProperty("maxSockets", SkillLoadout.MAX_SOCKETS);
        root.addProperty("maxSkills", SkillLoadout.MAX_SKILLS);
        root.addProperty("oncePerCharacter", config.supportOncePerCharacter());
        final JsonArray keys = new JsonArray();
        for (int slot = 0; slot < SkillLoadout.BIND_SLOTS; slot++) {
            keys.add(SkillLoadout.keyName(slot));
        }
        root.add("keys", keys);
        return GSON.toJson(root).getBytes(StandardCharsets.UTF_8);
    }

    /**
     * 每個 IP 的次數限制（權杖桶）：平常每秒 {@code perSecond} 次，一下子最多 {@code burst} 次。純邏輯，時間由呼叫端給。
     */
    static final class Limiter {
        private final double perSecond;
        private final double burst;
        private final Map<String, double[]> buckets = new java.util.concurrent.ConcurrentHashMap<>();

        Limiter(final double perSecond, final double burst) {
            this.perSecond = perSecond;
            this.burst = burst;
        }

        /** 這個 IP 現在能不能再算一次（能的話扣掉一次）。 */
        boolean allow(final String ip, final long nowMillis) {
            if (buckets.size() > 10_000) {
                buckets.clear(); // 太多 IP：全部重來（不會一直長大）
            }
            final double[] bucket = buckets.computeIfAbsent(ip == null ? "?" : ip, key -> new double[] {burst, nowMillis});
            synchronized (bucket) {
                final double elapsed = Math.max(0, nowMillis - bucket[1]) / 1000.0;
                bucket[0] = Math.min(burst, bucket[0] + elapsed * perSecond);
                bucket[1] = nowMillis;
                if (bucket[0] < 1) {
                    return false;
                }
                bucket[0] -= 1;
                return true;
            }
        }
    }

    private final Limiter limiter = new Limiter(4, 12);
    /** 同時最多幾個計算（網頁伺服器的執行緒上算，不碰主執行緒）。 */
    private final java.util.concurrent.Semaphore running = new java.util.concurrent.Semaphore(3);

    /** POST /tree/calc 的處理。 */
    WebServer.Response handle(final WebServer.Request request) {
        if (!limiter.allow(clientIp.apply(request), System.currentTimeMillis())) {
            return WebServer.Response.text(429, "算太快了，等一下再試");
        }
        final Build build;
        try {
            build = parse(request.text(), plugin.passives() == null ? null : plugin.passives().tree(),
                    plugin.skills() == null ? null : plugin.skills().config());
        } catch (final BadRequest e) {
            return WebServer.Response.text(400, e.getMessage());
        } catch (final RuntimeException e) {
            // 2026-10-18：型別不對的 JSON（例如該是數字卻是物件）當成讀不懂，不要變 500、每次印整段 stack trace
            return WebServer.Response.text(400, "讀不懂送來的資料");
        }
        boolean acquired = false;
        try {
            acquired = running.tryAcquire(2, java.util.concurrent.TimeUnit.SECONDS);
            if (!acquired) {
                return WebServer.Response.text(503, "計算的人太多，等一下再試");
            }
            return new WebServer.Response(200, "application/json; charset=utf-8", compute(build), Map.of("Cache-Control", "no-store"));
        } catch (final InterruptedException e) {
            Thread.currentThread().interrupt();
            return WebServer.Response.text(503, "伺服器忙碌中");
        } catch (final RuntimeException e) {
            plugin.getSLF4JLogger().warn("網頁計算器算不出來：{}", e.toString());
            return WebServer.Response.text(500, "算不出來（" + e.getClass().getSimpleName() + "）");
        } finally {
            if (acquired) {
                running.release();
            }
        }
    }
}
