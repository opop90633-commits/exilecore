package tw.exilecore.monster;

import tw.exilecore.core.config.YamlNode;
import tw.exilecore.item.AffixMod;
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
                                 int minMods, int maxMods, double quantityBonus, double rarityBonus) {
    }

    /** 一種原版生物的參數。 */
    public record TypeSettings(String name, double lifeMultiplier, double damageMultiplier) {
    }

    /**
     * 怪物詞綴：名稱、修飾器、附加元素（物理傷害的比例）、原版移動速度加成。
     */
    public record MonsterMod(String id, String name, List<AffixMod> mods, Map<DamageType, Double> addedFromPhysical,
                             double vanillaSpeedBonus) {
    }

    private int minLevel = 1;
    private int maxLevel = 60;
    private int ringSize = 64;
    private int levelPerRing = 2;
    private final TreeMap<Integer, Double> lifeTable = new TreeMap<>();
    private final TreeMap<Integer, double[]> damageTable = new TreeMap<>();
    private double accuracyPerLevel = 8;
    private double accuracyBase = 10;
    private double evasionPerLevel = 6;
    private double evasionBase = 40;
    private double armourPerLevel = 8;
    private double critChance = 5;
    private final Map<MonsterRarity, RaritySettings> rarities = new EnumMap<>(MonsterRarity.class);
    private final Map<String, TypeSettings> types = new LinkedHashMap<>();
    private TypeSettings defaultType = new TypeSettings(null, 1.0, 1.0);
    private final Map<String, MonsterMod> mods = new LinkedHashMap<>();

    public MonsterConfig() {
        rarities.put(MonsterRarity.NORMAL, new RaritySettings(85, 1.0, 1.0, 0, 0, 0, 0));
        rarities.put(MonsterRarity.MAGIC, new RaritySettings(12, 1.8, 1.2, 1, 1, 0.5, 0.3));
        rarities.put(MonsterRarity.RARE, new RaritySettings(3, 3.5, 1.5, 2, 4, 1.5, 1.0));
        rarities.put(MonsterRarity.UNIQUE, new RaritySettings(0, 8.0, 2.0, 0, 0, 3.0, 2.0));
        lifeTable.put(1, 20.0);
        damageTable.put(1, new double[] {2, 4});
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
            rarities.put(rarity, new RaritySettings(node.integer("weight", 0),
                    node.decimal("life", 1.0), node.decimal("damage", 1.0),
                    modRange[0], modRange[1], node.decimal("quantity", 0), node.decimal("rarity", 0)));
        }

        types.clear();
        final YamlNode typesNode = root.node("types");
        for (final String key : typesNode.keys()) {
            final YamlNode node = typesNode.node(key);
            final TypeSettings settings = new TypeSettings(node.string("name", null), node.decimal("life", 1.0), node.decimal("damage", 1.0));
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
            mods.put(key, new MonsterMod(key, node.requireString("name"), list, added, node.decimal("vanilla_speed", 0)));
        }
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

    /** 等級表的線性內插。 */
    public double lifeAt(final int level) {
        return interpolate(lifeTable, level);
    }

    public double[] damageAt(final int level) {
        final Map.Entry<Integer, double[]> floor = damageTable.floorEntry(level);
        final Map.Entry<Integer, double[]> ceiling = damageTable.ceilingEntry(level);
        if (floor == null) {
            return ceiling == null ? new double[] {1, 2} : ceiling.getValue();
        }
        if (ceiling == null || ceiling.getKey().equals(floor.getKey())) {
            return floor.getValue();
        }
        final double t = (level - floor.getKey()) / (double) (ceiling.getKey() - floor.getKey());
        return new double[] {
                floor.getValue()[0] + (ceiling.getValue()[0] - floor.getValue()[0]) * t,
                floor.getValue()[1] + (ceiling.getValue()[1] - floor.getValue()[1]) * t};
    }

    private static double interpolate(final TreeMap<Integer, Double> table, final int level) {
        final Map.Entry<Integer, Double> floor = table.floorEntry(level);
        final Map.Entry<Integer, Double> ceiling = table.ceilingEntry(level);
        if (floor == null) {
            return ceiling == null ? 1 : ceiling.getValue();
        }
        if (ceiling == null || ceiling.getKey().equals(floor.getKey())) {
            return floor.getValue();
        }
        final double t = (level - floor.getKey()) / (double) (ceiling.getKey() - floor.getKey());
        return floor.getValue() + (ceiling.getValue() - floor.getValue()) * t;
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
