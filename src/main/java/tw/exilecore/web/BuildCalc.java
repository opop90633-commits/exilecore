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
import tw.exilecore.stats.DamageType;
import tw.exilecore.stats.EffectiveHitPool;
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
 *   "gear": [物品資料 | null × 11],          照 PlayerState.GEAR_SLOTS 的順序（2026-10-26：第 11 個是展露之指的第三個戒指；舊的 10 個也收）
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
                        Integer supportsFor, Integer instilFor, EnemySettings enemy) {
        public Build {
            enemy = enemy == null ? EnemySettings.DEFAULT : enemy;
        }

        /** 2026-11-02：沒指定敵人（EHP 用 PoB2 的預設敵人）。 */
        public Build(final CharacterClass characterClass, final int level, final Set<String> passives, final Map<String, String> progress,
                     final List<ItemData> gear, final Map<String, ItemData> jewels, final SkillLoadout skills, final List<Modifier> extra,
                     final Integer supportsFor, final Integer instilFor) {
            this(characterClass, level, passives, progress, gear, jewels, skills, extra, supportsFor, instilFor, EnemySettings.DEFAULT);
        }
    }

    /**
     * 2026-11-02 照站長（PoB2 ConfigOptions 的 Enemy Stats）：EHP 對什麼樣的敵人算。
     *
     * @param level 敵人等級（0 ＝ 自動：一般怪、Boss 照角色等級，巔峰以上 T16）
     * @param tier  一般怪／Boss／巔峰 Boss／超級巔峰 Boss（PoB2 的預設是巔峰 Boss）
     * @param mix   物理、火焰、冰冷、閃電、混沌的比例（null ＝ PoB2 的預設分配）
     */
    public record EnemySettings(int level, EffectiveHitPool.Tier tier, double[] mix) {
        public static final EnemySettings DEFAULT = new EnemySettings(0, EffectiveHitPool.Tier.PINNACLE, null);

        public EnemySettings {
            tier = tier == null ? EffectiveHitPool.Tier.PINNACLE : tier;
            mix = mix == null ? null : mix.clone();
        }
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
        for (int i = 0; i < PlayerState.GEAR_SLOTS.length; i++) {
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
                // 2026-10-26：舊的低階 I／II 輔助（supports.yml 的 merged）換成同系列最高階；同一個技能重複的不算
                final String into = skills.supportMergedInto(clean);
                if (into != null) {
                    supports.add(supports.contains(into) || supports.contains(into + SkillLoadout.Entry.RESONANT_SUFFIX) ? ""
                            : into + supportId.substring(clean.length()));
                    continue;
                }
                if (clean.startsWith(SkillLoadout.Entry.INNER_PREFIX)) {
                    // 2026-10-26：宿主孔裡的技能寶石（@代號/等級/孔數；每一孔都可以）照存檔格式留著；這次之前被當成不認得的輔助丟掉
                    final String spellId = clean.substring(SkillLoadout.Entry.INNER_PREFIX.length()).split("/", -1)[0].trim();
                    supports.add(skills.skill(spellId) == null ? "" : clean);
                    continue;
                }
                // 2026-10-27 照站長：規劃器不強迫專屬輔助只能一顆（可能共鳴過）：專屬掉落的輔助一律當成共鳴（血脈輔助不能共鳴，照常一個技能一顆）
                final SupportDefinition known = skills.support(clean);
                supports.add(known == null ? "" : plannerResonant(known) ? clean + SkillLoadout.Entry.RESONANT_SUFFIX : supportId);
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
                instilFor, parseEnemy(json));
    }

    /**
     * 2026-11-02 照站長：讀 "enemy": { "level": 0（自動）～200, "tier": 0 一般怪／1 Boss／2 巔峰 Boss／3 超級巔峰 Boss,
     * "mix": [物理, 火焰, 冰冷, 閃電, 混沌]（比例，不用加起來 100）}。沒有、讀不懂的照 PoB2 的預設（巔峰 Boss、自動等級、預設分配）。純函式。
     */
    static EnemySettings parseEnemy(final JsonObject json) {
        if (json == null || !json.has("enemy") || !json.get("enemy").isJsonObject()) {
            return EnemySettings.DEFAULT;
        }
        final JsonObject o = json.getAsJsonObject("enemy");
        int level = 0;
        EffectiveHitPool.Tier tier = EffectiveHitPool.Tier.PINNACLE;
        double[] mix = null;
        try {
            if (o.has("level") && o.get("level").isJsonPrimitive()) {
                level = clamp(o.get("level").getAsInt(), 0, VirtualCharacter.MAX_LEVEL);
            }
            if (o.has("tier") && o.get("tier").isJsonPrimitive()) {
                final EffectiveHitPool.Tier picked = EffectiveHitPool.Tier.of(o.get("tier").getAsInt());
                tier = picked == null ? tier : picked;
            }
            if (o.has("mix") && o.get("mix").isJsonArray() && o.getAsJsonArray("mix").size() == DamageType.values().length) {
                final JsonArray array = o.getAsJsonArray("mix");
                final double[] values = new double[array.size()];
                double sum = 0;
                for (int i = 0; i < values.length; i++) {
                    final double v = array.get(i).isJsonPrimitive() ? array.get(i).getAsDouble() : 0;
                    values[i] = Double.isFinite(v) ? Math.max(0, Math.min(1000, v)) : 0;
                    sum += values[i];
                }
                mix = sum > 0 ? values : null;
            }
        } catch (final RuntimeException e) {
            return EnemySettings.DEFAULT; // 型別不對：照預設
        }
        return new EnemySettings(level, tier, mix);
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
        final JsonObject ehp = ehp(build, result.sheet()); // 2026-11-02 照站長（PoB2 的 EHP）
        if (ehp != null) {
            root.add("ehp", ehp);
        }
        final JsonArray inactive = new JsonArray();
        result.inactive().forEach(inactive::add);
        root.add("inactive", inactive);
        final SkillService skills = plugin.skills();
        // 2026-10-26 照 PoE2：屬性需求不足、不能用的技能寶石（「名稱：力量不足（需要 x，你有 y）」）
        final JsonArray gemProblems = new JsonArray();
        if (skills != null) {
            tw.exilecore.skill.GemAttributes.unmet(skills.config(), result.state().skills(), result.sheet()).forEach((id, why) -> {
                final SkillDefinition definition = skills.config().skill(id);
                gemProblems.add((definition == null ? id : definition.name()) + "：" + why);
            });
        }
        root.add("gemProblems", gemProblems);
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
            final boolean timeless = tw.exilecore.passive.TimelessJewels.isTimeless(base); // 2026-10-26：永恆珠寶（範圍 1500）
            if (base != null && (base.isRadiusJewel() || timeless)) {
                final double radius = timeless ? tw.exilecore.passive.TimelessJewels.RADIUS
                        : tw.exilecore.passive.JewelSockets.radiusOf(tw.exilecore.item.ItemStats.modifiers(repository, data));
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
            if (timeless) {
                final tw.exilecore.passive.TimelessJewels.Conquest conquest = tw.exilecore.passive.TimelessJewels.read(repository, socket.id(), data);
                tw.exilecore.passive.TimelessJewels.explain(conquest).forEach(lines::add);
                if (conquest != null) {
                    jewel.add("conquest", conquestJson(tree, conquest, build.passives())); // 2026-10-26：網頁天賦樹換成征服後的節點
                }
            }
            jewel.add("lines", lines);
            out.add(jewel);
        }
        return out;
    }

    // ---- 永恆珠寶（2026-10-26）----

    /**
     * 永恆珠寶征服的節點（網頁天賦樹即時換名稱與說明用；種子的雜湊在伺服器算，跟遊戲裡一模一樣，網頁不用自己重寫）。
     * <pre>
     * { "socket", "legion", "legionName", "seed", "conqueror", "conquerorName", "keystone", "radius",
     *   "nodes": { "節點": { "name", "type", "lines": [...], "add": true（屬性點：lines 是多給的，原本的效果保留） } } }
     * </pre>
     * 純函式。allocated：已配置的天賦（深淵族的貢禮照這些算）。
     */
    static JsonObject conquestJson(final PassiveTree tree, final tw.exilecore.passive.TimelessJewels.Conquest conquest, final Set<String> allocated) {
        final JsonObject out = new JsonObject();
        out.addProperty("socket", conquest.socket());
        out.addProperty("legion", conquest.legion().code());
        out.addProperty("legionName", conquest.legion().name());
        out.addProperty("seed", conquest.seed());
        out.addProperty("conqueror", conquest.conqueror());
        out.addProperty("conquerorName", conquest.conquerorInfo().name());
        out.addProperty("keystone", conquest.legion().keystone(conquest.conqueror()).name());
        out.addProperty("radius", tw.exilecore.passive.TimelessJewels.RADIUS);
        final JsonObject nodes = new JsonObject();
        for (final Map.Entry<String, tw.exilecore.passive.PassiveNode> entry
                : tw.exilecore.passive.TimelessJewels.conquered(tree, conquest, allocated).entrySet()) {
            final tw.exilecore.passive.PassiveNode original = tree.node(entry.getKey());
            final tw.exilecore.passive.PassiveNode replaced = entry.getValue();
            final boolean addition = original != null && original.attributeChoice() > 0;
            final List<String> description = replaced.description();
            final List<String> shown = addition ? description.subList(Math.min(description.size(), original.description().size()), description.size())
                    : description;
            final JsonObject node = new JsonObject();
            node.addProperty("name", replaced.name());
            node.addProperty("type", replaced.type().name().toLowerCase(java.util.Locale.ROOT));
            final JsonArray lines = new JsonArray();
            shown.forEach(lines::add);
            node.add("lines", lines);
            if (addition) {
                node.addProperty("add", true);
            }
            nodes.add(entry.getKey(), node);
        }
        out.add("nodes", nodes);
        return out;
    }

    /**
     * 讀 POST /tree/timeless 送來的 JSON（純函式）：{ "socket": 孔位, "legion": 1|2, "seed": 100–8000, "conqueror": 1 起算, "nodes": [已配置的] }。
     * 種子、征服者超出範圍的夾到範圍內（跟擲出來的珠寶一樣）。
     */
    static tw.exilecore.passive.TimelessJewels.Conquest parseConquest(final JsonObject json, final PassiveTree tree) throws BadRequest {
        if (tree == null) {
            throw new BadRequest("天賦樹還沒載入");
        }
        final String socketId = json.has("socket") ? json.get("socket").getAsString() : "";
        final tw.exilecore.passive.PassiveNode socket = tree.node(socketId);
        if (socket == null || !socket.isSocket()) {
            throw new BadRequest("不是珠寶孔：" + socketId);
        }
        final tw.exilecore.passive.TimelessJewels.Legion legion = tw.exilecore.passive.TimelessJewels.legions()
                .get(json.has("legion") ? json.get("legion").getAsInt() : 0);
        if (legion == null) {
            throw new BadRequest("沒有這個族");
        }
        final int seed = Math.max(tw.exilecore.passive.TimelessJewels.SEED_MIN, Math.min(tw.exilecore.passive.TimelessJewels.SEED_MAX,
                json.has("seed") ? json.get("seed").getAsInt() : tw.exilecore.passive.TimelessJewels.SEED_MIN));
        final int conqueror = Math.max(1, Math.min(legion.conquerors().size(), json.has("conqueror") ? json.get("conqueror").getAsInt() : 1));
        return new tw.exilecore.passive.TimelessJewels.Conquest(socket.id(), legion, seed, conqueror);
    }

    /** 讀 POST /tree/timeless 的已配置天賦（只收樹上有的，最多 {@value #MAX_TIMELESS_NODES} 個）。 */
    static Set<String> parseAllocated(final JsonObject json, final PassiveTree tree) {
        final Set<String> allocated = new LinkedHashSet<>();
        if (json.has("nodes") && json.get("nodes").isJsonArray()) {
            for (final JsonElement element : json.getAsJsonArray("nodes")) {
                if (allocated.size() >= MAX_TIMELESS_NODES) {
                    break;
                }
                final String id = element.getAsString();
                if (tree.node(id) != null) {
                    allocated.add(id);
                }
            }
        }
        return allocated;
    }

    static final int MAX_TIMELESS_NODES = 2000;
    private final Limiter timelessLimiter = new Limiter(8, 30);

    /** POST /tree/timeless 的處理（2026-10-26）：給孔位、族、種子、征服者 → 每個被征服的節點換成什麼。 */
    WebServer.Response handleTimeless(final WebServer.Request request) {
        if (!timelessLimiter.allow(clientIp.apply(request), System.currentTimeMillis())) {
            return WebServer.Response.text(429, "太快了，等一下再試");
        }
        final PassiveTree tree = plugin.passives() == null ? null : plugin.passives().tree();
        try {
            final JsonElement element = JsonParser.parseString(request.text() == null ? "" : request.text());
            if (!element.isJsonObject()) {
                return WebServer.Response.text(400, "要送一個 JSON 物件");
            }
            final JsonObject json = element.getAsJsonObject();
            final tw.exilecore.passive.TimelessJewels.Conquest conquest = parseConquest(json, tree);
            return new WebServer.Response(200, "application/json; charset=utf-8",
                    GSON.toJson(conquestJson(tree, conquest, parseAllocated(json, tree))).getBytes(StandardCharsets.UTF_8),
                    Map.of("Cache-Control", "no-store"));
        } catch (final BadRequest e) {
            return WebServer.Response.text(400, e.getMessage());
        } catch (final RuntimeException e) {
            return WebServer.Response.text(400, "讀不懂送來的資料");
        }
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
    /** 2026-10-27：規劃器裡專屬掉落的輔助（不含血脈）一律當成共鳴過，不佔一個技能的專屬名額。 */
    static boolean plannerResonant(final SupportDefinition support) {
        return !support.cuttable() && !support.lineage();
    }

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
            int lineageAt = -1;
            if (false && support.lineage()) { // 2026-10-28 照 PoE2：不同的血脈輔助可以一起掛；這次之前一個技能只能一顆
                for (int i = 0; i < entry.sockets(); i++) {
                    final SupportDefinition other = config.support(entry.support(i));
                    if (other != null && other.lineage()) {
                        problem = "一個技能只能掛一顆血脈輔助（假設換掉" + other.name() + "）";
                        lineageAt = i;
                    }
                }
            }
            if (config.oncePerCharacter(support.id())) {
                final int used = result.state().skills().supportUsedBy(support.id());
                if (used >= 0 && used != position) {
                    problem = "已經掛在別的技能上（這種一個角色只能一顆）";
                }
            }
            SkillLoadout.Entry trial = entry.freeSocket() >= 0 ? entry : entry.withSockets(entry.sockets() + 1);
            if (lineageAt >= 0) {
                trial = entry.withSupport(lineageAt, support.id()); // 換掉已經掛著的那顆血脈輔助
            } else if (trial.freeSocket() < 0) {
                problem = problem == null ? "輔助孔滿了（假設換掉一顆）" : problem;
                trial = null;
            } else {
                trial = trial.withSupport(trial.freeSocket(), support.id(), plannerResonant(support));
            }
            final SkillService.DpsRow row = trial == null ? replaceBest(result, view, position, entry, support.id())
                    : withEntry(result, view, position, trial);
            put(o, row, base);
            if (problem != null) {
                o.addProperty("problem", problem);
            }
            if (lineageAt >= 0) {
                o.addProperty("replace", lineageAt); // 2026-10-27：規劃器點下去就換掉那一孔的血脈輔助
            }
            add.add(o);
        }
        out.add("add", add);
        final JsonArray remove = new JsonArray();
        for (int i = 0; i < entry.sockets(); i++) {
            final String id = entry.support(i);
            if (id.isEmpty() || entry.spellSocket(i)) { // 2026-10-26：放著技能寶石的孔不是輔助
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
        // 2026-10-27 照 PoE2 0.5：塗油限定的核心（天賦樹上沒有）也比（anointOnly = true）
        for (final PassiveTree.Anointable anointable : tree.anointable()) {
            final tw.exilecore.passive.PassiveNode node = anointable.node();
            if (build.passives().contains(node.id())) {
                continue; // 天賦樹上已經配了：注入沒有效果
            }
            if (tw.exilecore.currency.InstilRecipes.size() > 0 && tw.exilecore.currency.InstilRecipes.recipe(node.id()) == null) {
                continue; // 2026-10-27 照 PoE2：沒有情緒配方的核心不能注入
            }
            final ItemData trial = current.copy();
            trial.setInstilled(node.id());
            final SkillService.DpsRow row = dpsWith(build, amulet, trial, position);
            final JsonObject o = new JsonObject();
            o.addProperty("id", node.id());
            o.addProperty("name", node.name());
            if (anointable.anointOnly()) {
                o.addProperty("anointOnly", true);
            }
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
                build.skills(), build.extra(), null, null, build.enemy());
        final Result result = evaluate(trial, trial.skills());
        return plugin.skills().skillDpsAt(result.state(), result.sheet(), auraView(result), position);
    }

    /** 孔滿了：換掉掛著的哪一顆最好（傷害最高的那個結果）。 */
    private SkillService.DpsRow replaceBest(final Result result, final SkillService.AuraView view, final int position,
                                            final SkillLoadout.Entry entry, final String supportId) {
        SkillService.DpsRow best = null;
        for (int i = 0; i < entry.sockets(); i++) {
            if (entry.spellSocket(i) || i == SkillLoadout.Entry.INNER_SOCKET && entry.inner() != null) {
                continue; // 2026-10-26：放著技能寶石的孔不拿來換輔助
            }
            final SupportDefinition replacing = plugin.skills().config().support(supportId);
            final SkillService.DpsRow row = withEntry(result, view, position, entry.withSupport(i, supportId, replacing != null && plannerResonant(replacing)));
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
            o.addProperty("aura", definition.aura() != null && !definition.reservesSpiritOnly()); // 2026-10-28：戰旗、掠閃、喚駒只保留精魂，不是光環
            o.addProperty("summon", definition.summon() != null);
            o.addProperty("damage", definition.dealsDamage() || definition.summon() != null);
            final JsonArray tags = new JsonArray();
            definition.displayTags().stream().sorted().forEach(tags::add); // 2026-10-30：照原作寶石標籤顯示（只拿來搜尋）
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
        root.addProperty("maxLevel", SkillLoadout.CORRUPT_CAP); // 2026-10-30：腐化可以到 Lv.22
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
            return allow(ip, nowMillis, 1);
        }

        /** 2026-11-02：一次扣 cost 次（批次算差異：每一組配點算一次）；不夠就整筆不扣。 */
        boolean allow(final String ip, final long nowMillis, final double cost) {
            if (buckets.size() > 10_000) {
                buckets.clear(); // 太多 IP：全部重來（不會一直長大）
            }
            final double[] bucket = buckets.computeIfAbsent(ip == null ? "?" : ip, key -> new double[] {burst, nowMillis});
            synchronized (bucket) {
                final double elapsed = Math.max(0, nowMillis - bucket[1]) / 1000.0;
                bucket[0] = Math.min(burst, bucket[0] + elapsed * perSecond);
                bucket[1] = nowMillis;
                if (bucket[0] < Math.min(cost, burst)) {
                    return false;
                }
                bucket[0] -= Math.min(cost, burst);
                return true;
            }
        }

        /** 2026-11-02：要等幾秒才夠扣 cost 次（給 429 的 Retry-After；至少 1 秒）。 */
        int retryAfter(final String ip, final long nowMillis, final double cost) {
            final double[] bucket = buckets.get(ip == null ? "?" : ip);
            if (bucket == null) {
                return 1;
            }
            synchronized (bucket) {
                final double have = Math.min(burst, bucket[0] + Math.max(0, nowMillis - bucket[1]) / 1000.0 * perSecond);
                return (int) Math.max(1, Math.ceil((Math.min(cost, burst) - have) / perSecond));
            }
        }
    }

    // ---- 批次算差異（2026-11-02 照站長：網頁滑到節點看「配了會變多少」、節點強度熱圖）----
    // 這次之前網頁要比較只能整份送 /tree/calc 兩次（每次都含輔助比較之類的、回傳整份結果）。
    // 這裡送一份角色（跟 /tree/calc 同格式）＋好幾組「多配／少配哪些節點」，每組只回傳要比較的幾個數字與每個技能的每秒傷害。

    /** 一次最多幾組配點。 */
    static final int MAX_VARIANTS = 48;
    /** 一組最多多配／少配幾個節點。 */
    static final int MAX_VARIANT_NODES = 160;
    /** 一次請求最多算多久（毫秒）：超過就先回傳算好的（done），網頁把剩下的再送一次。 */
    static final long BATCH_BUDGET_MS = 2500;
    /** 回傳的數字（滑到節點時的差異只列這些）。 */
    static final List<String> LIGHT_KEYS = List.of("life", "es", "mana", "spirit", "armour", "evasion",
            "resist.fire", "resist.cold", "resist.lightning", "resist.chaos", "movement");

    /** 一組配點：照送來的角色多配 add、少配 remove。 */
    public record Variant(Set<String> add, Set<String> remove) {
    }

    /** 讀 variants（純函式）：不認得的節點、起點略過；超過上限的丟掉。 */
    static List<Variant> parseVariants(final JsonObject json, final PassiveTree tree) throws BadRequest {
        final List<Variant> list = new ArrayList<>();
        if (!json.has("variants")) {
            return list;
        }
        if (!json.get("variants").isJsonArray()) {
            throw new BadRequest("variants 要是陣列");
        }
        final JsonArray array = json.getAsJsonArray("variants");
        if (array.size() > MAX_VARIANTS) {
            throw new BadRequest("一次最多 " + MAX_VARIANTS + " 組");
        }
        for (final JsonElement element : array) {
            if (!element.isJsonObject()) {
                throw new BadRequest("每一組要是物件");
            }
            final JsonObject o = element.getAsJsonObject();
            list.add(new Variant(nodeSet(o, "add", tree), nodeSet(o, "remove", tree)));
        }
        return list;
    }

    private static Set<String> nodeSet(final JsonObject o, final String key, final PassiveTree tree) {
        final Set<String> out = new LinkedHashSet<>();
        for (final String id : strings(o, key, MAX_VARIANT_NODES)) {
            final tw.exilecore.passive.PassiveNode node = tree.node(id);
            if (node != null && !node.isStart()) {
                out.add(node.id());
            }
        }
        return out;
    }

    /** 照一組配點改角色（純函式）：退掉的屬性點選擇、退掉的孔位裡的珠寶一起拿掉；多配的屬性點照職業主屬性（跟網頁、遊戲一樣）。 */
    static Build withVariant(final Build base, final Variant variant) {
        final Set<String> passives = new LinkedHashSet<>(base.passives());
        passives.removeAll(variant.remove());
        passives.addAll(variant.add());
        final Map<String, String> progress = new LinkedHashMap<>(base.progress());
        final Map<String, ItemData> jewels = new LinkedHashMap<>(base.jewels());
        for (final String id : variant.remove()) {
            if (!variant.add().contains(id)) {
                progress.remove("attr." + id);
                jewels.remove(id);
            }
        }
        return new Build(base.characterClass(), base.level(), passives, progress, base.gear(), jewels, base.skills(), base.extra(), null, null,
                base.enemy());
    }

    /**
     * 要比較的數字（純函式）：LIGHT_KEYS 的，加上 ehp（有效生命池，{@link #ehpJson} 的 value；打不死、沒算的不放）。
     * 2026-11-02 照站長（PoB2 CalcDefence 的 TotalEHP）；這次之前 ehp 是五種傷害「最多吃得下一下」的平均（免疫、無限大的不算）。
     */
    static JsonObject lightNumbers(final Map<String, Double> numbers, final Double ehp) {
        final JsonObject o = new JsonObject();
        for (final String key : LIGHT_KEYS) {
            final Double v = numbers.get(key);
            if (v != null && Double.isFinite(v)) {
                o.addProperty(key, Math.round(v * 100) / 100.0);
            }
        }
        if (ehp != null && Double.isFinite(ehp) && ehp >= 0) {
            o.addProperty("ehp", Math.round(ehp));
        }
        return o;
    }

    // ---- EHP（2026-11-02 照站長，PoB2 CalcDefence 的 Effective Hit Pool、Maximum Hit Taken、Number of Hits）----

    /** JSON 的數字：無限大寫 -2、免疫（NaN）寫 -1（跟 numbers 的 maxhit.* 一樣）。 */
    static double jsonNumber(final double v, final int digits) {
        if (Double.isNaN(v)) {
            return -1;
        }
        if (Double.isInfinite(v)) {
            return -2;
        }
        final double scale = Math.pow(10, digits);
        return Math.round(v * scale) / scale;
    }

    /**
     * 照設定做出敵人（純函式）：等級（{@link EffectiveHitPool#enemyLevel}）、一下的平均傷害（monsters.yml 的傷害表，跟 PoB2 的 monsterDamageTable 同一份）、
     * 命中（monsters.yml 的命中表）。
     */
    static EffectiveHitPool.Enemy enemy(final EnemySettings settings, final int characterLevel, final tw.exilecore.monster.MonsterConfig monsters,
                                        final int t16Level) {
        final int level = enemyLevelOf(settings, characterLevel, t16Level);
        final double[] range = monsters.damageAt(level);
        return EffectiveHitPool.pob2Enemy(settings.tier(), (range[0] + range[1]) / 2, monsters.accuracyAt(level), settings.mix());
    }

    static int enemyLevelOf(final EnemySettings settings, final int characterLevel, final int t16Level) {
        return EffectiveHitPool.enemyLevel(settings.tier(), settings.level(), characterLevel, t16Level, ENEMY_MAX_LEVEL);
    }

    /** 敵人等級最多幾級（monsters.yml 的表寫到 100 級）。 */
    static final int ENEMY_MAX_LEVEL = 100;

    /**
     * 回傳的 ehp（純函式）：
     * <pre>
     * { "value": EHP, "hits": 平均幾下死, "damagingHits": 打中幾下死, "hitChance": 被打中的機率, "damageChance": 打中的平均受到幾成,
     *   "critEffect": 敵人的暴擊期望, "maxhit": { "physical"… }, "taken": { 一下減免後每種受到多少 },
     *   "enemy": { "level", "tier", "tierName", "penetration", "accuracy", "total": 一下的原始傷害, "damage": { "physical"… } } }
     * </pre>
     * 打不死寫 -2、免疫寫 -1。
     */
    static JsonObject ehpJson(final StatSheet sheet, final EffectiveHitPool.Pools pools, final EffectiveHitPool.Enemy enemy,
                              final EnemySettings settings, final int enemyLevel) {
        final EffectiveHitPool.Result r = EffectiveHitPool.compute(sheet, pools, enemy);
        final JsonObject o = new JsonObject();
        o.addProperty("value", jsonNumber(r.ehp(), 0));
        o.addProperty("hits", jsonNumber(r.hits(), 2));
        o.addProperty("damagingHits", jsonNumber(r.damagingHits(), 2));
        o.addProperty("hitChance", jsonNumber(r.hitChance(), 4));
        o.addProperty("damageChance", jsonNumber(r.damageChance(), 4));
        o.addProperty("critEffect", jsonNumber(r.critEffect(), 4));
        final JsonObject maxHit = new JsonObject();
        final JsonObject taken = new JsonObject();
        final JsonObject damage = new JsonObject();
        for (final DamageType type : DamageType.values()) {
            final String key = type.name().toLowerCase(java.util.Locale.ROOT);
            maxHit.addProperty(key, jsonNumber(r.maxHit().get(type), 0));
            taken.addProperty(key, jsonNumber(r.takenPerHit().get(type), 1));
            damage.addProperty(key, jsonNumber(enemy.damage().get(type), 1));
        }
        o.add("maxhit", maxHit);
        o.add("taken", taken);
        final JsonObject e = new JsonObject();
        e.addProperty("level", enemyLevel);
        e.addProperty("tier", settings.tier().ordinal());
        e.addProperty("tierName", settings.tier().displayName());
        e.addProperty("penetration", settings.tier().penetration());
        e.addProperty("accuracy", Math.round(enemy.accuracy()));
        e.addProperty("total", jsonNumber(enemy.total(), 1));
        e.add("damage", damage);
        o.add("enemy", e);
        return o;
    }

    /** 只要 EHP 的數字（批次用：不算每一種的最多吃得下一下）；伺服器沒有怪物設定時 null。 */
    private Double ehpValue(final Build build, final StatSheet sheet) {
        if (plugin.monsters() == null) {
            return null;
        }
        final int t16 = tw.exilecore.map.MapStats.tiers().level(tw.exilecore.map.MapStats.tiers().max());
        return EffectiveHitPool.compute(sheet, StatsReport.pools(sheet), enemy(build.enemy(), build.level(), plugin.monsters().config(), t16), false).ehp();
    }

    /** 這個角色的 ehp（伺服器沒有怪物設定時 null）。 */
    private JsonObject ehp(final Build build, final StatSheet sheet) {
        if (plugin.monsters() == null) {
            return null;
        }
        final int t16 = tw.exilecore.map.MapStats.tiers().level(tw.exilecore.map.MapStats.tiers().max());
        final EffectiveHitPool.Enemy enemy = enemy(build.enemy(), build.level(), plugin.monsters().config(), t16);
        return ehpJson(sheet, StatsReport.pools(sheet), enemy, build.enemy(), enemyLevelOf(build.enemy(), build.level(), t16));
    }

    /** 算一組：數字（n）與每個技能的每秒傷害（s：位置 p、代號、名稱、每秒傷害 d）。 */
    private JsonObject light(final Build build, final boolean withSkills) {
        final Result result = evaluate(build, build.skills());
        final JsonObject o = new JsonObject();
        // plugin 給 null：不算護甲擋幾 %、閃避率（要怪物等級的估計，比較慢；差異只看護甲、閃避值）
        o.add("n", lightNumbers(StatsReport.numbers(null, result.sheet(), build.level()), ehpValue(build, result.sheet())));
        final JsonArray rows = new JsonArray();
        final SkillService skills = plugin.skills();
        if (withSkills && skills != null) {
            for (final SkillService.DpsRow row : skills.skillDps(result.state(), result.sheet(), auraView(result))) {
                final JsonObject r = new JsonObject();
                r.addProperty("p", row.position());
                r.addProperty("id", row.skillId());
                r.addProperty("name", row.name());
                r.addProperty("d", row.dps());
                rows.add(r);
            }
        }
        o.add("s", rows);
        return o;
    }

    /** 每個 IP：平常每秒算 40 組，一下子最多 160 組（熱圖一次約幾百組，幾秒內算完；滑到節點一次 1–2 組）。 */
    private final Limiter batchLimiter = new Limiter(40, 160);
    /** 批次的同時最多幾個（跟 /tree/calc 的分開，熱圖不會把一般計算擠掉）。 */
    private final java.util.concurrent.Semaphore batchRunning = new java.util.concurrent.Semaphore(2);

    /**
     * POST /tree/calc-batch 的處理：角色（同 /tree/calc）＋ "base": true（也算原本的）＋ "dps": false（不算技能的每秒傷害；"skills" 是技能面板）
     * ＋ "variants": [{ "add": [節點…], "remove": [節點…] } × 最多 48]。
     * 回傳 { base?, results: [{ n, s }…], done: 算完幾組, ms }；超過時間就只算前面 done 組。
     */
    WebServer.Response handleBatch(final WebServer.Request request) {
        final PassiveTree tree = plugin.passives() == null ? null : plugin.passives().tree();
        final Build build;
        final List<Variant> variants;
        final boolean withBase;
        final boolean withSkills;
        try {
            build = parse(request.text(), tree, plugin.skills() == null ? null : plugin.skills().config());
            final JsonObject json = JsonParser.parseString(request.text()).getAsJsonObject();
            variants = parseVariants(json, tree);
            withBase = json.has("base") && json.get("base").isJsonPrimitive() && json.get("base").getAsBoolean();
            withSkills = !json.has("dps") || !json.get("dps").isJsonPrimitive() || json.get("dps").getAsBoolean();
        } catch (final BadRequest e) {
            return WebServer.Response.text(400, e.getMessage());
        } catch (final RuntimeException e) {
            return WebServer.Response.text(400, "讀不懂送來的資料");
        }
        final int cost = variants.size() + (withBase ? 1 : 0);
        if (cost == 0) {
            return WebServer.Response.text(400, "沒有要算的");
        }
        final String ip = clientIp.apply(request);
        final long now = System.currentTimeMillis();
        if (!batchLimiter.allow(ip, now, cost)) {
            return new WebServer.Response(429, "text/plain; charset=utf-8", "算太快了，等一下再試".getBytes(StandardCharsets.UTF_8),
                    Map.of("Retry-After", String.valueOf(batchLimiter.retryAfter(ip, now, cost)), "Cache-Control", "no-store"));
        }
        boolean acquired = false;
        try {
            acquired = batchRunning.tryAcquire(3, java.util.concurrent.TimeUnit.SECONDS);
            if (!acquired) {
                return new WebServer.Response(503, "text/plain; charset=utf-8", "計算的人太多，等一下再試".getBytes(StandardCharsets.UTF_8),
                        Map.of("Retry-After", "2", "Cache-Control", "no-store"));
            }
            final long started = System.nanoTime();
            final JsonObject root = new JsonObject();
            if (withBase) {
                root.add("base", light(build, withSkills));
            }
            final JsonArray results = new JsonArray();
            for (final Variant variant : variants) {
                if (results.size() > 0 && (System.nanoTime() - started) / 1_000_000 > BATCH_BUDGET_MS) {
                    break; // 算太久：先回傳這些
                }
                results.add(light(withVariant(build, variant), withSkills));
            }
            root.add("results", results);
            root.addProperty("done", results.size());
            root.addProperty("ms", Math.round((System.nanoTime() - started) / 100_000.0) / 10.0);
            return new WebServer.Response(200, "application/json; charset=utf-8", GSON.toJson(root).getBytes(StandardCharsets.UTF_8),
                    Map.of("Cache-Control", "no-store"));
        } catch (final InterruptedException e) {
            Thread.currentThread().interrupt();
            return WebServer.Response.text(503, "伺服器忙碌中");
        } catch (final RuntimeException e) {
            plugin.getSLF4JLogger().warn("網頁計算器（批次）算不出來：{}", e.toString());
            return WebServer.Response.text(500, "算不出來（" + e.getClass().getSimpleName() + "）");
        } finally {
            if (acquired) {
                batchRunning.release();
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
