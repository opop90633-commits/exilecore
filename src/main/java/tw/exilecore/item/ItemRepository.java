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
                          double qualityWeaponPercent, double qualityArmourPercent) {
    }

    private final Map<String, ItemBase> bases = new LinkedHashMap<>();
    private final Map<String, Affix> affixes = new LinkedHashMap<>();
    private List<String> rareNamePrefixes = List.of();
    private Map<String, List<String>> rareNameSuffixes = Map.of();
    private Scaling scaling = new Scaling(0.035, 0.035, 0.02, 1.0, 1.0);

    /** 從資料夾載入全部資料檔（bases/*.yml、affixes/*.yml、names.yml、items.yml）。 */
    public void load(final Path dataFolder) throws IOException {
        bases.clear();
        affixes.clear();

        for (final Path file : yamlFiles(dataFolder.resolve("bases"))) {
            final YamlNode root = YamlNode.load(file);
            for (final String id : root.keys()) {
                if (bases.containsKey(id)) {
                    throw new IllegalArgumentException(file.getFileName() + "：基底代號重複 " + id);
                }
                bases.put(id, ItemBase.parse(id, root.node(id)));
            }
        }
        for (final Path file : yamlFiles(dataFolder.resolve("affixes"))) {
            final YamlNode root = YamlNode.load(file);
            for (final String id : root.keys()) {
                if (affixes.containsKey(id)) {
                    throw new IllegalArgumentException(file.getFileName() + "：詞綴代號重複 " + id);
                }
                affixes.put(id, Affix.parse(id, root.node(id)));
            }
        }

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
                    config.decimal("quality_armour_percent", scaling.qualityArmourPercent()));
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

    /** 交叉檢查：每條詞綴至少能出現在一個基底上，否則多半是標籤打錯。 */
    private void validate() {
        for (final Affix affix : affixes.values()) {
            boolean anyBase = false;
            for (final ItemBase base : bases.values()) {
                if (affix.canSpawnOn(base)) {
                    anyBase = true;
                    break;
                }
            }
            if (!anyBase) {
                throw new IllegalArgumentException("詞綴 " + affix.id() + " 的 spawn_tags " + affix.spawnTags() + " 對不到任何基底");
            }
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

    public Collection<ItemBase> bases() {
        return Collections.unmodifiableCollection(bases.values());
    }

    public Affix affix(final String id) {
        return affixes.get(id);
    }

    public Collection<Affix> affixes() {
        return Collections.unmodifiableCollection(affixes.values());
    }

    /** 掉落等級 ≤ 給定等級的基底。 */
    public List<ItemBase> basesDroppableAt(final int level) {
        final List<ItemBase> list = new ArrayList<>();
        for (final ItemBase base : bases.values()) {
            if (base.dropLevel() <= level) {
                list.add(base);
            }
        }
        return list;
    }

    public List<String> rareNamePrefixes() {
        return rareNamePrefixes;
    }

    /** 稀有名字的後綴，依基底分類（weapon／armour／jewellery）；沒有就退回 default。 */
    public List<String> rareNameSuffixes(final ItemBase base) {
        final String category = base.isWeapon() ? "weapon" : base.isJewellery() ? "jewellery" : "armour";
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
