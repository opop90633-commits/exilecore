package tw.exilecore.item;

import tw.exilecore.core.config.YamlNode;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Stream;

/**
 * 物品資料檔的載入與查詢：基底、詞綴、稀有名字表、縮放設定。
 * 只讀資料夾裡的 YAML，不依賴 Bukkit，單元測試可以直接指向 src/main/resources/data。
 * <p>
 * 載入失敗會丟出例外並指出檔名與欄位，讓錯的設定檔不會上線。
 */
public final class ItemRepository {

    /** items.yml 的縮放設定。 */
    public record Scaling(double damagePerLevel, double defencePerLevel, double requirementPerLevel,
                          double qualityWeaponPercent, double qualityArmourPercent, int statLevelCap) {

        public Scaling(final double damagePerLevel, final double defencePerLevel, final double requirementPerLevel,
                       final double qualityWeaponPercent, final double qualityArmourPercent) {
            this(damagePerLevel, defencePerLevel, requirementPerLevel, qualityWeaponPercent, qualityArmourPercent, 0);
        }

        /** 算基底數值、需求用的物品等級（2026-10-18）：超過 stat_level_cap 的照上限算；0 = 不限。 */
        public int cap(final int itemLevel) {
            return statLevelCap > 0 ? Math.min(itemLevel, statLevelCap) : itemLevel;
        }
    }

    private final Map<String, ItemBase> bases = new LinkedHashMap<>();
    /** 隱匿傳奇：傳奇代號 → 帶的那條隱匿詞綴是前綴還是後綴（uniques.yml 的 veiled）。 */
    private final Map<String, List<AffixType>> uniqueVeiled = new java.util.HashMap<>();
    /** 傳奇自己的符文孔數、符文效果增加（小數）：uniques.yml 的 rune_sockets、rune_effect（2026-10）。 */
    private final Map<String, Integer> uniqueRuneSockets = new java.util.HashMap<>();
    /** 傳奇的基底數值照哪一個基底（uniques.yml 的 stat_base，2026-10：基底合併後傳奇保留原本那一階的數值）。 */
    private final Map<String, String> uniqueStatBases = new java.util.HashMap<>();
    private final Map<String, Double> uniqueRuneEffect = new java.util.HashMap<>();
    private final Map<String, Affix> affixes = new LinkedHashMap<>();
    /** 詞綴代號 → 資料檔指定的標籤（chips:）；沒指定的照修飾器判斷。 */
    private final Map<String, java.util.Set<AffixChips.Chip>> affixChips = new java.util.HashMap<>();
    private final Map<String, UniqueItem> uniques = new LinkedHashMap<>();
    /** 退役的傳奇（uniques.yml 的 retired: true，2026-10 PoE2 改版）：定義留著讓還沒換掉的舊物品照常生效，其他地方都看不到。 */
    private final java.util.Set<String> retiredUniques = new java.util.HashSet<>();
    /** 拿掉的基底（bases 的 retired: true）：定義留著讓舊物品照常生效；不掉落、不出現在圖鑑、商人、新手武器。 */
    private final java.util.Set<String> retiredBases = new java.util.HashSet<>();
    /** 載入時發現、但不擋啟動的問題（例如傳奇的基底被改名了）；物品模組啟動時印到主控台。 */
    private final List<String> warnings = new ArrayList<>();
    private List<String> rareNamePrefixes = List.of();
    private Map<String, List<String>> rareNameSuffixes = Map.of();
    private Scaling scaling = new Scaling(0.035, 0.035, 0.02, 1.0, 1.0, 92); // items.yml 沒寫 stat_level_cap 也照 92
    /** 傳奇定義的指紋（名字、詞綴、範圍）：uniques.yml 改了就變；物品上記著上次檢查時的指紋（{@link UniqueUpgrade}）。 */
    private int uniqueRevision;

    /** 從資料夾載入全部資料檔（bases/*.yml、affixes/*.yml、uniques.yml、names.yml、items.yml）。 */
    /** 符文（2026-10）。 */
    private final Map<String, Runes.Rune> runes = new java.util.LinkedHashMap<>();

    public Runes.Rune rune(final String id) {
        return id == null ? null : runes.get(id);
    }

    public java.util.Collection<Runes.Rune> runes() {
        return java.util.Collections.unmodifiableCollection(runes.values());
    }

    /**
     * 讀物品資料。2026-10：先在一份新的上面讀一次，全部讀得過才換掉現在的
     * （以前先清空再讀，/ec reload 的時候檔案打錯字，伺服器上的基底、詞綴、傳奇就全部不見，直到下次讀成功）。
     */
    public void load(final Path dataFolder) throws IOException {
        if (!bases.isEmpty()) {
            new ItemRepository().loadNow(dataFolder); // 讀不過就丟例外，現在的資料不動
        }
        loadNow(dataFolder);
    }

    private void loadNow(final Path dataFolder) throws IOException {
        bases.clear();
        uniqueVeiled.clear();
        uniqueRuneSockets.clear();
        uniqueStatBases.clear();
        uniqueRuneEffect.clear();
        affixes.clear();
        affixChips.clear();
        uniques.clear();
        retiredUniques.clear();
        retiredBases.clear();
        warnings.clear();
        runes.clear();
        runes.putAll(Runes.load(dataFolder)); // 2026-10 符文

        for (final Path file : yamlFiles(dataFolder.resolve("bases"))) {
            final YamlNode root = YamlNode.load(file);
            for (final String id : root.keys()) {
                if (bases.containsKey(id)) {
                    throw new IllegalArgumentException(file.getFileName() + "：基底代號重複 " + id);
                }
                bases.put(id, ItemBase.parse(id, root.node(id)));
                if (root.node(id).bool("retired", false)) {
                    retiredBases.add(id); // 拿掉的基底（2026-10：劍、斧、匕首、爪）：舊物品照常能用，其他地方都看不到
                }
            }
        }
        loadFlaskBases(dataFolder.resolve("flasks.yml"));
        for (final Path file : yamlFiles(dataFolder.resolve("affixes"))) {
            final YamlNode root = YamlNode.load(file);
            for (final String id : root.keys()) {
                if (affixes.containsKey(id)) {
                    throw new IllegalArgumentException(file.getFileName() + "：詞綴代號重複 " + id);
                }
                affixes.put(id, Affix.parse(id, root.node(id)));
                // 2026-10：chips 直接指定這條詞綴顯示哪些標籤（照 PoE2 的標籤；例如箭袋的「弓技能傷害」只有「傷害」，
                // 但修飾器要寫 attack、bow 才吃得對）。沒寫的照修飾器的屬性與標籤判斷。
                // 寫 chips: [] 是「這條沒有標籤」（PoE2 的暈眩、破甲、印記那些就沒有）
                final List<String> chipIds = root.node(id).strings("chips");
                if (root.node(id).has("chips")) {
                    final java.util.Set<AffixChips.Chip> chips = java.util.EnumSet.noneOf(AffixChips.Chip.class);
                    for (final String chipId : chipIds) {
                        final AffixChips.Chip chip = AffixChips.byId(chipId);
                        if (chip == null) {
                            throw new IllegalArgumentException(file.getFileName() + "：" + id + ".chips 不認得的標籤 " + chipId);
                        }
                        chips.add(chip);
                    }
                    affixChips.put(id, java.util.Collections.unmodifiableSet(chips));
                }
            }
        }

        final Path uniquesFile = dataFolder.resolve("uniques.yml");
        if (Files.exists(uniquesFile)) {
            final YamlNode root = YamlNode.load(uniquesFile);
            for (final String id : root.keys()) {
                final UniqueItem unique = UniqueItem.parse(id, root.node(id), affixes);
                if (!bases.containsKey(unique.base())) {
                    // 基底被改名或刪掉：這件傳奇先不用，不要讓整個物品模組起不來
                    warnings.add("uniques.yml：" + id + " 的基底 " + unique.base() + " 不存在，這件傳奇先停用");
                    for (final String affixId : unique.affixIds()) {
                        affixes.remove(affixId);
                    }
                    continue;
                }
                uniques.put(id, unique);
                // 2026-10：傳奇自己的符文孔數（rune_sockets，蓋過基底的）與符文效果增加（rune_effect，%）
                if (root.node(id).has("rune_sockets")) {
                    uniqueRuneSockets.put(id, Math.max(0, Math.min(6, root.node(id).integer("rune_sockets", 0))));
                }
                final String statBase = root.node(id).string("stat_base", null);
                if (statBase != null) {
                    if (bases.containsKey(statBase.trim())) {
                        uniqueStatBases.put(id, statBase.trim());
                    } else {
                        warnings.add("uniques.yml：" + id + " 的 stat_base " + statBase + " 不存在，改照基底本身的數值");
                    }
                }
                if (root.node(id).has("rune_effect")) {
                    uniqueRuneEffect.put(id, root.node(id).decimal("rune_effect", 0) / 100.0);
                }
                if (root.node(id).bool("retired", false)) {
                    retiredUniques.add(id);
                }
                final Object veiledRaw = root.node(id).raw("veiled");
                if (veiledRaw != null) {
                    // 隱匿傳奇（劫盜，2026-10）：掉出來就帶隱匿前綴／後綴（可以寫一個或一串），找盜賊首領揭露
                    final List<Object> entries = veiledRaw instanceof List<?> list ? new java.util.ArrayList<>(list) : List.of(veiledRaw);
                    final List<AffixType> types = new java.util.ArrayList<>();
                    for (final Object entry : entries) {
                        final String type = String.valueOf(entry).trim().toLowerCase(java.util.Locale.ROOT);
                        if (!type.equals("prefix") && !type.equals("suffix")) {
                            throw new IllegalArgumentException("uniques.yml：" + id + ".veiled 要寫 prefix、suffix（或 [prefix, suffix]）");
                        }
                        types.add(type.equals("prefix") ? AffixType.PREFIX : AffixType.SUFFIX);
                    }
                    uniqueVeiled.put(id, List.copyOf(types));
                }
            }
        }

        uniqueRevision = computeUniqueRevision();

        final Path namesFile = dataFolder.resolve("names.yml");
        if (Files.exists(namesFile)) {
            final YamlNode names = YamlNode.load(namesFile);
            rareNamePrefixes = names.strings("prefixes");
            final Map<String, List<String>> suffixes = new LinkedHashMap<>();
            final YamlNode suffixNode = names.node("suffixes");
            for (final String key : suffixNode.keys()) {
                suffixes.put(key, suffixNode.strings(key));
            }
            rareNameSuffixes = suffixes;
        }

        final Path itemsFile = dataFolder.resolve("items.yml");
        if (Files.exists(itemsFile)) {
            final YamlNode config = YamlNode.load(itemsFile).node("scaling");
            scaling = new Scaling(
                    config.decimal("damage_per_level", scaling.damagePerLevel()),
                    config.decimal("defence_per_level", scaling.defencePerLevel()),
                    config.decimal("requirement_per_level", scaling.requirementPerLevel()),
                    config.decimal("quality_weapon_percent", scaling.qualityWeaponPercent()),
                    config.decimal("quality_armour_percent", scaling.qualityArmourPercent()),
                    config.integer("stat_level_cap", scaling.statLevelCap()));
        }

        validate();
    }

    private static List<Path> yamlFiles(final Path folder) throws IOException {
        if (!Files.isDirectory(folder)) {
            return List.of();
        }
        try (Stream<Path> stream = Files.list(folder)) {
            return stream.filter(path -> path.getFileName().toString().endsWith(".yml")).sorted().toList();
        }
    }

    /**
     * 停用的詞綴：spawn_tags 寫 [retired]，不會再擲到任何物品上，定義留著只是讓已經擲到的舊物品照常生效、顯示。
     */
    public static final String RETIRED_TAG = "retired";

    /** 蝕變石的腐化詞綴（2026-10-21，affixes/corrupted.yml）：不會自然擲出，只有腐化時加上。 */
    public static final String CORRUPTION_TAG = "corruption";

    /**
     * 交叉檢查：每條詞綴至少能出現在一個基底上，否則多半是標籤打錯；
     * 本地詞綴（stat 以 local. 開頭，見 {@link LocalMods}）只能用認得的屬性、只能是 INCREASED、只能出在武器上（防具上不會生效）。
     */
    private void validate() {
        for (final Affix affix : affixes.values()) {
            final boolean local = checkLocal("詞綴 " + affix.id(), affix.mods());
            if (affix.spawnTags().contains(UniqueItem.SPAWN_TAG) || affix.spawnTags().contains(RETIRED_TAG)) {
                continue; // 傳奇詞綴只在那件傳奇上；停用的詞綴不會再擲出來（只留給舊物品）
            }
            if (affix.spawnTags().contains(CORRUPTION_TAG)) {
                // 2026-10-21：腐化詞綴：spawn_tags（corruption 以外）、require_tags 要對得到至少一個基底（蝕變石照這些挑）
                if (bases.values().stream().noneMatch(base -> ItemGenerator.corruptionFits(affix, base))) {
                    throw new IllegalArgumentException("腐化詞綴 " + affix.id() + " 的 spawn_tags " + affix.spawnTags() + " 對不到任何基底");
                }
                continue;
            }
            boolean anyBase = false;
            for (final ItemBase base : bases.values()) {
                if (affix.canSpawnOn(base)) {
                    anyBase = true;
                    if (local && !base.isWeapon()) {
                        throw new IllegalArgumentException("詞綴 " + affix.id() + " 是武器本身的詞綴（local.），卻會出在非武器的基底 "
                                + base.id() + " 上（加 exclude_tags 或改 spawn_tags）");
                    }
                }
            }
            if (!anyBase && FLASK_TAGS.containsAll(affix.spawnTags()) && flaskLikeBases().isEmpty()) {
                continue; // 藥劑、護符的詞綴，但這個資料夾沒有 flasks.yml（測試、只載入裝備）：沒有基底可對，略過
            }
            if (!anyBase) {
                throw new IllegalArgumentException("詞綴 " + affix.id() + " 的 spawn_tags " + affix.spawnTags() + " 對不到任何基底");
            }
        }
        for (final ItemBase base : bases.values()) {
            if (base.implicit() != null && checkLocal("基底 " + base.id() + " 的固定詞綴", base.implicit().mods()) && !base.isWeapon()) {
                throw new IllegalArgumentException("基底 " + base.id() + " 不是武器，固定詞綴卻用了武器本身的屬性（local.）");
            }
        }
    }

    /** 檢查本地屬性（local.）寫得對不對；有本地屬性回傳 true。 */
    private static boolean checkLocal(final String where, final List<AffixMod> mods) {
        boolean any = false;
        for (final AffixMod mod : mods) {
            if (!mod.isLocal() || ItemStats.UNVEILED_MAGNITUDE.equals(mod.stat()) || LocalMods.REQUIREMENTS.equals(mod.stat())) {
                continue; // 「增加 x% 已揭露詞綴的幅度」「減少屬性需求」：不是武器本身的數值（防具也能出），ItemStats 自己處理
            }
            any = true;
            if (!LocalMods.KNOWN.contains(mod.stat())) {
                throw new IllegalArgumentException(where + "：不認得的武器本身屬性 " + mod.stat() + "（只有 "
                        + String.join("、", new java.util.TreeSet<>(LocalMods.KNOWN)) + "）");
            }
            final tw.exilecore.stats.ModType expected = LocalMods.CRIT_BASE.equals(mod.stat()) ? tw.exilecore.stats.ModType.FLAT : tw.exilecore.stats.ModType.INCREASED;
            if (mod.type() != expected) {
                throw new IllegalArgumentException(where + "：武器本身的屬性 " + mod.stat() + " 只能用 " + expected + "，卻是 " + mod.type());
            }
        }
        return any;
    }

    /**
     * 2026-10：藥劑與護符也是物品引擎的基底（擲 PoE2 的藥劑詞綴、用魔法類的通貨），但定義寫在 flasks.yml，
     * 這裡合成：bases 的每一種藥劑 → slot FLASK、tags [flask, life_flask／mana_flask]；charms.bases 的每一種護符 → slot CHARM、tags [charm]。
     * 代號跟 flasks.yml 一樣（藥劑物品上的資料兩邊共用同一個代號）。
     */
    /** 藥劑、護符基底的標籤（affixes/flasks.yml 的 spawn_tags 用這些）。 */
    static final Set<String> FLASK_TAGS = Set.of("flask", "life_flask", "mana_flask", "charm");

    private void loadFlaskBases(final Path file) throws IOException {
        if (!Files.exists(file)) {
            return;
        }
        final YamlNode root = YamlNode.load(file);
        final YamlNode flasks = root.node("bases");
        for (final String id : flasks.keys()) {
            final YamlNode node = flasks.node(id);
            final String kind = node.string("kind", "life").trim().toLowerCase(java.util.Locale.ROOT);
            if (bases.containsKey(id)) {
                throw new IllegalArgumentException("flasks.yml：藥劑代號跟裝備基底重複 " + id);
            }
            bases.put(id, ItemBase.flaskLike(id, node.requireString("name"), "POTION", ItemSlot.FLASK,
                    Set.of("flask", kind + "_flask"), node.integer("level", 1)));
        }
        final YamlNode charms = root.node("charms").node("bases");
        for (final String id : charms.keys()) {
            final YamlNode node = charms.node(id);
            if (bases.containsKey(id)) {
                throw new IllegalArgumentException("flasks.yml：護符代號跟裝備基底重複 " + id);
            }
            bases.put(id, ItemBase.flaskLike(id, node.requireString("name"), node.string("icon", "AMETHYST_SHARD").trim().toUpperCase(java.util.Locale.ROOT),
                    ItemSlot.CHARM, Set.of("charm"), node.integer("level", 1)));
        }
    }

    public ItemBase base(final String id) {
        return bases.get(id);
    }

    public ItemBase requireBase(final String id) {
        final ItemBase base = bases.get(id);
        if (base == null) {
            throw new IllegalArgumentException("找不到基底 " + id);
        }
        return base;
    }

    /** 這件傳奇帶的隱匿詞綴（前綴、後綴，可能好幾條）；不是隱匿傳奇回傳空的。 */
    public List<AffixType> uniqueVeiled(final String uniqueId) {
        return uniqueId == null ? List.of() : uniqueVeiled.getOrDefault(uniqueId, List.of());
    }

    /** 現役的裝備基底（拿掉的不算；藥劑、護符的基底由藥劑模組管，不在這裡，用 {@link #base(String)} 還是找得到）。 */
    public Collection<ItemBase> bases() {
        final List<ItemBase> list = new ArrayList<>();
        for (final ItemBase base : bases.values()) {
            if (!retiredBases.contains(base.id()) && !base.isFlaskLike()) {
                list.add(base);
            }
        }
        return Collections.unmodifiableList(list);
    }

    /** 藥劑與護符的基底（flasks.yml 合成的）。 */
    public List<ItemBase> flaskLikeBases() {
        final List<ItemBase> list = new ArrayList<>();
        for (final ItemBase base : bases.values()) {
            if (base.isFlaskLike()) {
                list.add(base);
            }
        }
        return Collections.unmodifiableList(list);
    }

    /** 拿掉的基底嗎。 */
    public boolean retiredBase(final String id) {
        return id != null && retiredBases.contains(id);
    }

    public Affix affix(final String id) {
        return affixes.get(id);
    }

    public Collection<Affix> affixes() {
        return Collections.unmodifiableCollection(affixes.values());
    }

    /** 這條詞綴顯示的標籤：資料檔有寫 chips 的照寫的，不然照修飾器判斷。 */
    public java.util.Set<AffixChips.Chip> chipsOf(final Affix affix) {
        final java.util.Set<AffixChips.Chip> fixed = affixChips.get(affix.id());
        return fixed != null ? fixed : AffixChips.of(affix.mods());
    }

    /**
     * 掉落等級 ≤ 給定等級的裝備基底（地圖另外掉，不在這裡；含珠寶）。
     * 遺時珠寶（半徑珠寶）不在這裡（2026-10）：只在劫盜的金庫箱與大金庫掉，見 {@link #pickRadiusJewel}。
     */
    public List<ItemBase> basesDroppableAt(final int level) {
        final List<ItemBase> list = new ArrayList<>();
        for (final ItemBase base : bases.values()) {
            if (base.dropLevel() <= level && !base.isMap() && !base.isFlaskLike() && !base.isRadiusJewel() && base.dropWeight() > 0
                    && !retiredBases.contains(base.id())) {
                list.add(base);
            }
        }
        return list;
    }

    /** 掉落等級 ≤ 給定等級的遺時珠寶（半徑珠寶）基底：只有劫盜會掉。 */
    public List<ItemBase> radiusJewelsAt(final int level) {
        final List<ItemBase> list = new ArrayList<>();
        for (final ItemBase base : bases.values()) {
            if (base.isRadiusJewel() && base.dropLevel() <= level && base.dropWeight() > 0 && !retiredBases.contains(base.id())) {
                list.add(base);
            }
        }
        return list;
    }

    /** 照掉落權重抽一個遺時珠寶的基底；這個等級還掉不出來回傳 null。 */
    public ItemBase pickRadiusJewel(final int level, final java.util.random.RandomGenerator random) {
        final List<ItemBase> pool = radiusJewelsAt(level);
        double total = 0;
        for (final ItemBase base : pool) {
            total += base.dropWeight();
        }
        if (total <= 0) {
            return null;
        }
        double pick = random.nextDouble() * total;
        for (final ItemBase base : pool) {
            pick -= base.dropWeight();
            if (pick < 0) {
                return base;
            }
        }
        return pool.getLast();
    }

    /** 商人賣的、能賭的基底：能穿戴的裝備（珠寶只掉落，照 PoE2 商人不賣）。 */
    public List<ItemBase> basesSoldAt(final int level) {
        final List<ItemBase> list = new ArrayList<>();
        for (final ItemBase base : bases.values()) {
            if (base.dropLevel() <= level && base.isWearable() && !retiredBases.contains(base.id())) {
                list.add(base);
            }
        }
        return list;
    }

    /** 依掉落權重（drop_weight，珠寶比較少見）從掉得出來的基底抽一個；沒有回傳 null。 */
    public ItemBase pickDroppable(final int level, final java.util.random.RandomGenerator random) {
        return pickDroppable(level, random, LootBias.NONE);
    }

    /** 同上，再照職業調整（{@link LootBias}：只在同一個部位裡重新分配）。 */
    public ItemBase pickDroppable(final int level, final java.util.random.RandomGenerator random, final LootBias bias) {
        return pickByFamily(basesDroppableAt(level), random, bias);
    }

    /** 商人、地圖獎勵這類「一件裝備」：從買得到的基底（不含珠寶、地圖）照分類抽，再照職業調整。 */
    public ItemBase pickSold(final int level, final java.util.random.RandomGenerator random, final LootBias bias) {
        return pickByFamily(basesSoldAt(level), random, bias);
    }

    /**
     * 2026-10：分階的基底（family）先照權重抽一類、再從那一類平均抽一個。
     * 有職業調整時先照原本的權重抽部位（頭、身、主手、副手……），再在那個部位裡照調過的權重抽一類，所以各部位的比例不變。
     */
    public static ItemBase pickByFamily(final List<ItemBase> bases, final java.util.random.RandomGenerator random, final LootBias bias) {
        final java.util.Map<String, List<ItemBase>> families = new java.util.LinkedHashMap<>();
        for (final ItemBase base : bases) {
            families.computeIfAbsent(base.family(), key -> new ArrayList<>()).add(base);
        }
        final java.util.Map<ItemSlot, List<List<ItemBase>>> slots = new java.util.LinkedHashMap<>();
        final java.util.Map<ItemSlot, Double> slotWeights = new java.util.LinkedHashMap<>();
        double total = 0;
        for (final List<ItemBase> members : families.values()) {
            final double weight = Math.max(0, members.getFirst().dropWeight());
            if (weight <= 0) {
                continue;
            }
            final ItemSlot slot = members.getFirst().slot();
            slots.computeIfAbsent(slot, key -> new ArrayList<>()).add(members);
            slotWeights.merge(slot, weight, Double::sum);
            total += weight;
        }
        if (total <= 0) {
            return null;
        }
        double slotPick = random.nextDouble() * total;
        ItemSlot chosen = null;
        for (final var entry : slotWeights.entrySet()) {
            chosen = entry.getKey();
            slotPick -= entry.getValue();
            if (slotPick < 0) {
                break;
            }
        }
        final List<List<ItemBase>> group = slots.get(chosen);
        final LootBias effective = bias == null ? LootBias.NONE : bias;
        double groupTotal = 0;
        final double[] weights = new double[group.size()];
        for (int i = 0; i < group.size(); i++) {
            final ItemBase sample = group.get(i).getFirst();
            weights[i] = sample.dropWeight() * (effective.none() ? 1 : effective.factor(sample));
            groupTotal += weights[i];
        }
        List<ItemBase> members = group.getLast();
        if (groupTotal > 0) {
            double pick = random.nextDouble() * groupTotal;
            for (int i = 0; i < group.size(); i++) {
                pick -= weights[i];
                if (pick < 0) {
                    members = group.get(i);
                    break;
                }
            }
        }
        return members.get(random.nextInt(members.size()));
    }

    /** 載入時的非致命問題。 */
    public List<String> warnings() {
        return List.copyOf(warnings);
    }

    /** 傳奇定義的指紋（uniques.yml 的名字、詞綴、範圍改了就變）。 */
    public int uniqueRevision() {
        return uniqueRevision;
    }

    private int computeUniqueRevision() {
        int hash = 1;
        for (final UniqueItem unique : uniques.values()) {
            hash = 31 * hash + unique.id().hashCode();
            hash = 31 * hash + unique.name().hashCode();
            hash = 31 * hash + unique.version();
            for (final String affixId : unique.affixIds()) {
                hash = 31 * hash + affixId.hashCode();
                final Affix affix = affixes.get(affixId);
                if (affix == null) {
                    continue;
                }
                final Map<String, int[]> vars = affix.tiers().getFirst().vars();
                for (final String key : new java.util.TreeSet<>(vars.keySet())) { // 排好序：每次啟動算出來都一樣
                    hash = 31 * hash + key.hashCode();
                    hash = 31 * hash + java.util.Arrays.hashCode(vars.get(key));
                }
            }
        }
        return hash;
    }

    /** 這件傳奇自己寫的符文孔數；沒寫回傳 -1（照基底）。 */
    /** 傳奇的基底數值照哪一個基底（沒寫 stat_base 回傳 null，照它的基底）。 */
    public ItemBase uniqueStatBase(final String id) {
        final String baseId = id == null ? null : uniqueStatBases.get(id);
        return baseId == null ? null : bases.get(baseId);
    }

    /** 合併掉的基底（retired ＋ merged_into）現在算哪一個；其他照原本的代號。 */
    public String canonicalBase(final String id) {
        final ItemBase base = id == null ? null : bases.get(id);
        return base != null && base.mergedInto() != null && bases.containsKey(base.mergedInto()) ? base.mergedInto() : id;
    }

    public int uniqueRuneSockets(final String id) {
        return id == null ? -1 : uniqueRuneSockets.getOrDefault(id, -1);
    }

    /** 這件傳奇嵌的符文效果增加（小數；0 = 沒有）。 */
    public double uniqueRuneEffect(final String id) {
        return id == null ? 0 : uniqueRuneEffect.getOrDefault(id, 0.0);
    }

    public UniqueItem unique(final String id) {
        return id == null ? null : uniques.get(id);
    }

    /** 現役的傳奇（退役的不算：掉落、圖鑑、兌換券、僥倖石都只看這些）。 */
    public Collection<UniqueItem> uniques() {
        if (retiredUniques.isEmpty()) {
            return Collections.unmodifiableCollection(uniques.values());
        }
        final List<UniqueItem> list = new ArrayList<>();
        for (final UniqueItem unique : uniques.values()) {
            if (!retiredUniques.contains(unique.id())) {
                list.add(unique);
            }
        }
        return Collections.unmodifiableList(list);
    }

    /** 退役的傳奇（2026-10 PoE2 改版）：玩家手上的會換成傳奇兌換券。 */
    public boolean retiredUnique(final String id) {
        return id != null && retiredUniques.contains(id);
    }

    /** 這個來源（final、map_boss、地圖代號）會掉的傳奇，物品等級要夠。 */
    public List<UniqueItem> uniquesFor(final String source, final int itemLevel) {
        final List<UniqueItem> list = new ArrayList<>();
        for (final UniqueItem unique : uniques.values()) {
            if (unique.droppedBy(source) && unique.dropLevel() <= itemLevel && unique.weight() > 0) {
                list.add(unique);
            }
        }
        return list;
    }

    public List<String> rareNamePrefixes() {
        return rareNamePrefixes;
    }

    /** 稀有名字的後綴，依基底分類（weapon／armour／jewellery）；沒有就退回 default。 */
    public List<String> rareNameSuffixes(final ItemBase base) {
        final String category = base.isMap() ? "map" : base.isJewel() ? "jewel" : base.isWeapon() ? "weapon"
                : base.isJewellery() ? "jewellery" : "armour";
        final List<String> list = rareNameSuffixes.get(category);
        if (list != null && !list.isEmpty()) {
            return list;
        }
        return rareNameSuffixes.getOrDefault("default", List.of("之物"));
    }

    public Scaling scaling() {
        return scaling;
    }

    public int baseCount() {
        return bases.size();
    }

    public int affixCount() {
        return affixes.size();
    }
}
