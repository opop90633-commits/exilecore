package tw.exilecore.monster;

import tw.exilecore.core.config.LevelCurve;
import tw.exilecore.core.config.YamlNode;
import tw.exilecore.item.AffixMod;
import tw.exilecore.skill.BossSkillUse;
import tw.exilecore.stats.DamageType;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.EnumMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.TreeMap;

/**
 * data/monsters.yml：怪物的等級規則、生命／傷害表、稀有度倍率、原版生物對照表、怪物詞綴。
 * 純資料，不依賴 Bukkit。
 */
public final class MonsterConfig {

    /** 一種稀有度的參數。 */
    public record RaritySettings(int weight, double lifeMultiplier, double damageMultiplier,
                                 int minMods, int maxMods, double quantityBonus, double rarityBonus,
                                 double experienceMultiplier) {
    }

    /**
     * 一種原版生物的參數。
     *
     * @param skills      這種生物的招式（data/boss_skills.yml 的代號；空的 = 沒有）
     * @param skillChance 一般怪有幾成會帶這組招式（0–1；魔法怪兩倍、稀有怪一定帶）
     * @param hand        帶招式時手上拿的東西（原版物品代號，null = 不拿）
     */
    public record TypeSettings(String name, double lifeMultiplier, double damageMultiplier, List<BossSkillUse> skills, double skillChance,
                               String hand) {
        public TypeSettings {
            skills = skills == null ? List.of() : List.copyOf(skills);
        }

        public TypeSettings(final String name, final double lifeMultiplier, final double damageMultiplier) {
            this(name, lifeMultiplier, damageMultiplier, List.of(), 0, null);
        }
    }

    /**
     * 怪物詞綴：名稱、修飾器、附加元素（物理傷害的比例）、原版移動速度加成；光環、召喚；招式（{@code skills}）與手上拿的東西。
     */
    public record MonsterMod(String id, String name, List<AffixMod> mods, Map<DamageType, Double> addedFromPhysical,
                             double vanillaSpeedBonus, int minLevel, Aura aura, Summon summon, List<BossSkillUse> skills, String hand) {

        public MonsterMod {
            skills = skills == null ? List.of() : List.copyOf(skills);
        }

        /** 舊的寫法（沒有光環、召喚，任何等級都會出現）。 */
        public MonsterMod(final String id, final String name, final List<AffixMod> mods, final Map<DamageType, Double> addedFromPhysical,
                          final double vanillaSpeedBonus) {
            this(id, name, mods, addedFromPhysical, vanillaSpeedBonus, 1, null, null, List.of(), null);
        }

        /** 沒有招式的寫法。 */
        public MonsterMod(final String id, final String name, final List<AffixMod> mods, final Map<DamageType, Double> addedFromPhysical,
                          final double vanillaSpeedBonus, final int minLevel, final Aura aura, final Summon summon) {
            this(id, name, mods, addedFromPhysical, vanillaSpeedBonus, minLevel, aura, summon, List.of(), null);
        }

        /** 這條詞綴會不會給招式。 */
        public boolean hasSkills() {
            return !skills.isEmpty();
        }
    }

    /**
     * 光環：半徑內的怪物（含自己）都得到這些修飾器。
     *
     * @param radius 半徑（格）
     * @param stats  給範圍內怪物的修飾器
     * @param color  光環粒子的顏色（十六進位 RGB）
     */
    public record Aura(double radius, List<AffixMod> stats, int color) {
        public Aura {
            stats = List.copyOf(stats);
        }
    }

    /**
     * 召喚：有目標時每隔 {@code intervalSeconds} 秒叫出 {@code count} 隻同種的普通怪（最多同時 {@code max} 隻）。
     * 召喚出來的怪物不掉東西、不給經驗。
     */
    public record Summon(int intervalSeconds, int count, int max) {
    }

    private int minLevel = 1;
    private int maxLevel = 60;
    private int ringSize = 64;
    private int levelPerRing = 2;
    private final TreeMap<Integer, Double> lifeTable = new TreeMap<>();
    private final TreeMap<Integer, double[]> damageTable = new TreeMap<>();
    private final TreeMap<Integer, Double> experienceTable = new TreeMap<>();
    /** 怪物經驗表再乘的倍率（monsters.yml 的 experience_multiplier；沒寫 = 1）。 */
    private double experienceMultiplier = 1.0;
    private double accuracyPerLevel = 8;
    private double accuracyBase = 10;
    private double evasionPerLevel = 3;
    private double evasionBase = 20;
    private double armourPerLevel = 8;
    private double critChance = 5;
    private final Map<MonsterRarity, RaritySettings> rarities = new EnumMap<>(MonsterRarity.class);
    private final Map<String, TypeSettings> types = new LinkedHashMap<>();
    private TypeSettings defaultType = new TypeSettings(null, 1.0, 1.0);
    private final Map<String, MonsterMod> mods = new LinkedHashMap<>();

    public MonsterConfig() {
        rarities.put(MonsterRarity.NORMAL, new RaritySettings(85, 1.0, 1.0, 0, 0, 0, 0, 1.0));
        rarities.put(MonsterRarity.MAGIC, new RaritySettings(12, 1.8, 1.2, 1, 1, 0.5, 0.3, 3.0));
        rarities.put(MonsterRarity.RARE, new RaritySettings(3, 3.5, 1.5, 2, 4, 1.5, 1.0, 12.0));
        rarities.put(MonsterRarity.UNIQUE, new RaritySettings(0, 8.0, 2.0, 0, 0, 3.0, 2.0, 40.0));
        lifeTable.put(1, 20.0);
        damageTable.put(1, new double[] {2, 4});
        experienceTable.put(1, 20.0);
    }

    public void load(final Path dataFolder) throws IOException {
        final Path file = dataFolder.resolve("monsters.yml");
        if (!Files.exists(file)) {
            return;
        }
        final YamlNode root = YamlNode.load(file);

        final YamlNode level = root.node("level");
        minLevel = level.integer("min", minLevel);
        maxLevel = level.integer("max", maxLevel);
        ringSize = Math.max(1, level.integer("ring_size", ringSize));
        levelPerRing = level.integer("per_ring", levelPerRing);

        if (root.has("life")) {
            lifeTable.clear();
            final YamlNode lifeNode = root.node("life");
            for (final String key : lifeNode.keys()) {
                lifeTable.put(Integer.parseInt(key.trim()), lifeNode.decimal(key, 0));
            }
        }
        if (root.has("damage")) {
            damageTable.clear();
            final YamlNode damageNode = root.node("damage");
            for (final String key : damageNode.keys()) {
                final int[] range = damageNode.range(key);
                damageTable.put(Integer.parseInt(key.trim()), new double[] {range[0], range[1]});
            }
        }
        if (root.has("experience")) {
            experienceTable.clear();
            final YamlNode xpNode = root.node("experience");
            for (final String key : xpNode.keys()) {
                experienceTable.put(Integer.parseInt(key.trim()), xpNode.decimal(key, 0));
            }
        }
        // 沒寫 = 1：舊的 monsters.yml（自己改過、新版在 .new）照原本的表給經驗
        final double multiplier = root.decimal("experience_multiplier", 1.0);
        if (multiplier < 0) {
            throw new IllegalArgumentException("monsters.yml：experience_multiplier 不能是負數");
        }
        experienceMultiplier = multiplier;
        final YamlNode defence = root.node("defence");
        accuracyBase = defence.decimal("accuracy_base", accuracyBase);
        accuracyPerLevel = defence.decimal("accuracy_per_level", accuracyPerLevel);
        evasionBase = defence.decimal("evasion_base", evasionBase);
        evasionPerLevel = defence.decimal("evasion_per_level", evasionPerLevel);
        armourPerLevel = defence.decimal("armour_per_level", armourPerLevel);
        critChance = defence.decimal("crit_chance", critChance);

        final YamlNode rarityNode = root.node("rarity");
        for (final String key : rarityNode.keys()) {
            final MonsterRarity rarity = MonsterRarity.parse(key, null);
            if (rarity == null) {
                throw new IllegalArgumentException(rarityNode.path() + "：不認得的稀有度 " + key);
            }
            final YamlNode node = rarityNode.node(key);
            final int[] modRange = node.has("mods") ? node.range("mods") : new int[] {0, 0};
            final RaritySettings previous = rarities.get(rarity);
            rarities.put(rarity, new RaritySettings(node.integer("weight", 0),
                    node.decimal("life", 1.0), node.decimal("damage", 1.0),
                    modRange[0], modRange[1], node.decimal("quantity", 0), node.decimal("rarity", 0),
                    node.decimal("experience", previous == null ? 1.0 : previous.experienceMultiplier())));
        }

        types.clear();
        final YamlNode typesNode = root.node("types");
        for (final String key : typesNode.keys()) {
            final YamlNode node = typesNode.node(key);
            final double chance = node.decimal("skill_chance", 0.25);
            if (chance < 0 || chance > 1) {
                throw new IllegalArgumentException(node.path() + ".skill_chance：要在 0 到 1 之間");
            }
            final TypeSettings settings = new TypeSettings(node.string("name", null), node.decimal("life", 1.0), node.decimal("damage", 1.0),
                    BossSkillUse.parseList(node, "skills"), chance, hand(node));
            if (key.equalsIgnoreCase("default")) {
                defaultType = settings;
            } else {
                types.put(key.trim().toUpperCase(Locale.ROOT), settings);
            }
        }

        mods.clear();
        final YamlNode modsNode = root.node("mods");
        for (final String key : modsNode.keys()) {
            final YamlNode node = modsNode.node(key);
            final List<AffixMod> list = new ArrayList<>();
            for (final YamlNode modNode : node.list("stats")) {
                list.add(AffixMod.parse(modNode));
            }
            final Map<DamageType, Double> added = new EnumMap<>(DamageType.class);
            final YamlNode addedNode = node.node("added");
            for (final String typeKey : addedNode.keys()) {
                final DamageType type = DamageType.fromTag(typeKey);
                if (type == null) {
                    throw new IllegalArgumentException(addedNode.path() + "：不認得的傷害類型 " + typeKey);
                }
                added.put(type, addedNode.decimal(typeKey, 0));
            }
            Aura aura = null;
            if (node.has("aura")) {
                final YamlNode auraNode = node.node("aura");
                final List<AffixMod> auraStats = new ArrayList<>();
                for (final YamlNode modNode : auraNode.list("stats")) {
                    auraStats.add(AffixMod.parse(modNode));
                }
                int color = 0xff5533;
                try {
                    color = Integer.parseInt(auraNode.string("color", "ff5533").replace("#", "").trim(), 16);
                } catch (final NumberFormatException ignored) {
                    // 用預設顏色
                }
                aura = new Aura(Math.max(2, auraNode.decimal("radius", 10)), auraStats, color);
            }
            Summon summon = null;
            if (node.has("summon")) {
                final YamlNode summonNode = node.node("summon");
                summon = new Summon(Math.max(2, summonNode.integer("interval", 8)), Math.max(1, summonNode.integer("count", 2)),
                        Math.max(1, summonNode.integer("max", 4)));
            }
            mods.put(key, new MonsterMod(key, node.requireString("name"), list, added, node.decimal("vanilla_speed", 0),
                    Math.max(1, node.integer("min_level", 1)), aura, summon, BossSkillUse.parseList(node, "skills"), hand(node)));
        }
    }

    /** 手上拿的東西：原版物品代號（大寫）；沒寫是 null。存不存在要到遊戲裡才知道（這裡不依賴 Bukkit）。 */
    private static String hand(final YamlNode node) {
        final String hand = node.string("hand", null);
        return hand == null || hand.isBlank() ? null : hand.trim().toUpperCase(Locale.ROOT);
    }

    /** 所有帶招式的詞綴（給測試與 /ec 檢查）。 */
    public List<MonsterMod> skillMods() {
        final List<MonsterMod> list = new ArrayList<>();
        for (final MonsterMod mod : mods.values()) {
            if (mod.hasSkills()) {
                list.add(mod);
            }
        }
        return list;
    }

    /** 所有有招式的生物種類（代號 → 設定）。 */
    public Map<String, TypeSettings> skillTypes() {
        final Map<String, TypeSettings> map = new LinkedHashMap<>();
        for (final Map.Entry<String, TypeSettings> entry : types.entrySet()) {
            if (!entry.getValue().skills().isEmpty()) {
                map.put(entry.getKey(), entry.getValue());
            }
        }
        return map;
    }

    // ---- 查詢 ----

    /** 距離出生點的等級：1 + 每 ring_size 格加 per_ring 級，夾在 min 與 max 之間。 */
    public int levelForDistance(final double distance) {
        final int level = minLevel + (int) (distance / ringSize) * levelPerRing;
        return Math.max(minLevel, Math.min(maxLevel, level));
    }

    public int minLevel() {
        return minLevel;
    }

    public int maxLevel() {
        return maxLevel;
    }

    /** 生命表的錨點（等級 → 生命），唯讀。 */
    public java.util.NavigableMap<Integer, Double> lifeAnchors() {
        return java.util.Collections.unmodifiableNavigableMap(lifeTable);
    }

    /** 傷害表的錨點（等級 → [最小, 最大]），唯讀（陣列是複本）。 */
    public java.util.NavigableMap<Integer, double[]> damageAnchors() {
        final java.util.NavigableMap<Integer, double[]> copy = new TreeMap<>();
        for (final Map.Entry<Integer, double[]> entry : damageTable.entrySet()) {
            copy.put(entry.getKey(), entry.getValue().clone());
        }
        return copy;
    }

    /** 等級 → 普通怪的生命（錨點之間等比內插，見 {@link LevelCurve}）。 */
    public double lifeAt(final int level) {
        return LevelCurve.at(lifeTable, level, 1, LevelCurve.Mode.GEOMETRIC);
    }

    /**
     * 擊殺一隻這個等級的普通怪的基礎經驗：經驗表（線性內插）× experience_multiplier。
     * 實際給的再乘稀有度倍率與等級差懲罰（character.yml）。
     */
    public double experienceAt(final int level) {
        return LevelCurve.at(experienceTable, level, 1, LevelCurve.Mode.LINEAR) * experienceMultiplier;
    }

    /** monsters.yml 的 experience_multiplier。 */
    public double experienceMultiplier() {
        return experienceMultiplier;
    }

    /** 等級 → 普通怪每次攻擊的物理傷害範圍（等比內插）。回傳複本。 */
    public double[] damageAt(final int level) {
        return LevelCurve.range(damageTable, level, new double[] {1, 2}, LevelCurve.Mode.GEOMETRIC);
    }

    public double accuracyAt(final int level) {
        return accuracyBase + accuracyPerLevel * level;
    }

    public double evasionAt(final int level) {
        return evasionBase + evasionPerLevel * level;
    }

    public double armourAt(final int level) {
        return armourPerLevel * level;
    }

    public double critChance() {
        return critChance;
    }

    public RaritySettings rarity(final MonsterRarity rarity) {
        return rarities.get(rarity);
    }

    public TypeSettings type(final String entityType) {
        return types.getOrDefault(entityType.toUpperCase(Locale.ROOT), defaultType);
    }

    public MonsterMod mod(final String id) {
        return mods.get(id);
    }

    public List<MonsterMod> mods() {
        return List.copyOf(mods.values());
    }

    /** 依權重抽稀有度。 */
    public MonsterRarity rollRarity(final java.util.random.RandomGenerator random) {
        int total = 0;
        for (final RaritySettings settings : rarities.values()) {
            total += settings.weight();
        }
        if (total <= 0) {
            return MonsterRarity.NORMAL;
        }
        int pick = random.nextInt(total);
        for (final Map.Entry<MonsterRarity, RaritySettings> entry : rarities.entrySet()) {
            pick -= entry.getValue().weight();
            if (pick < 0) {
                return entry.getKey();
            }
        }
        return MonsterRarity.NORMAL;
    }
}
