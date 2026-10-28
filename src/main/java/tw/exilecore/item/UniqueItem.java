package tw.exilecore.item;

import tw.exilecore.core.config.YamlNode;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * 傳奇物品（data/uniques.yml）：固定的名字、基底與詞綴（數值有範圍），加上一段世界觀文字。
 * <pre>
 * sky_heart:
 *   name: 天穹之心
 *   base: jade_amulet
 *   drop_level: 66
 *   sources: [final]               # 誰會掉：final（終局 Boss）、map_boss（任何地圖 Boss）、地圖代號（那張圖的 Boss）
 *   weight: 100                    # 同一個來源裡的權重（2026-10-28：沒寫的照 rarity，見 UniqueRarity）
 *   rarity: uncommon               # 2026-10-28 照 PoE2 的稀有度：全域掉落、賭博、僥倖石的權重（UniqueRarity）
 *   flavour: ["它曾經照亮整片天空。"]
 *   mods:
 *     - { text: "+{value} 最大生命", value: [60, 80], stats: [{ stat: life.max, type: FLAT, value: "{value}" }] }
 * </pre>
 * 每條詞綴登記成一條只在這件傳奇上出現的詞綴（代號 unique.傳奇.序號），所以屬性、描述、天啟石重擲數值都跟一般詞綴共用同一套。
 * <p>
 * 改版（詞綴增減、調順序、改範圍）：把 {@code version} 加一，順序有變的話寫 {@code legacy}：
 * <pre>
 *   version: 2
 *   legacy: { 1: [1, 0, 2, 3, 0, 4] }   # 第 1 版的物品升上來：新的第 n 條對應舊的第幾條（0 = 新的，重擲）
 * </pre>
 * 已經掉出來的舊傳奇照這個補正（{@link UniqueUpgrade}）：對得上、數值還在範圍裡的留著，其他的重擲。
 *
 * @param id       代號
 * @param name     名字
 * @param base     基底代號
 * @param affixIds 詞綴代號（依順序）
 * @param flavour  世界觀文字
 * @param dropLevel 物品等級至少多少才會掉
 * @param weight   權重
 * @param sources  掉落來源
 * @param version  定義的版本（預設 1）
 * @param legacy   舊版 → 新的每一條對應舊的第幾條（1 起算，0 = 新的）；沒寫的版本照序號對
 * @param chase    追逐級（極稀有）：物品上標出來、掉出來全服廣播；只從 chase 池掉，機率是 maps.yml 的 drops.chase_chance
 *                 （所有地圖 Boss 固定，不吃加成）；weight 只決定四件裡掉哪一件
 */
public record UniqueItem(String id, String name, String base, List<String> affixIds, List<String> flavour,
                         int dropLevel, int weight, Set<String> sources, int version, Map<Integer, List<Integer>> legacy,
                         boolean chase) {

    /** 傳奇詞綴的代號前綴。 */
    public static final String AFFIX_PREFIX = "unique.";
    /** 傳奇詞綴的出現標籤（沒有基底帶這個標籤，所以不會被隨機抽到）。 */
    public static final String SPAWN_TAG = "unique_only";

    public UniqueItem {
        affixIds = List.copyOf(affixIds);
        flavour = List.copyOf(flavour);
        sources = Set.copyOf(sources);
        legacy = legacy == null ? Map.of() : Map.copyOf(legacy);
    }

    /** 沒有版本與舊版對應的傳奇（測試用）。 */
    public UniqueItem(final String id, final String name, final String base, final List<String> affixIds, final List<String> flavour,
                      final int dropLevel, final int weight, final Set<String> sources) {
        this(id, name, base, affixIds, flavour, dropLevel, weight, sources, 1, Map.of(), false);
    }

    /** 沒有追逐級標記的傳奇（舊的呼叫法）。 */
    public UniqueItem(final String id, final String name, final String base, final List<String> affixIds, final List<String> flavour,
                      final int dropLevel, final int weight, final Set<String> sources, final int version, final Map<Integer, List<Integer>> legacy) {
        this(id, name, base, affixIds, flavour, dropLevel, weight, sources, version, legacy, false);
    }

    /**
     * 第 fromVersion 版的物品升到這一版時，新的第 index 條（1 起算）對應舊的第幾條；0 = 新的詞綴（沒有舊的可以留）。
     * 沒寫 legacy 的版本照同樣的序號對。
     */
    public int legacyIndex(final int fromVersion, final int index) {
        final List<Integer> mapping = legacy.get(fromVersion);
        if (mapping == null) {
            return index;
        }
        return index >= 1 && index <= mapping.size() ? Math.max(0, mapping.get(index - 1)) : 0;
    }

    /** 解析一件傳奇，詞綴加進 affixes（代號 unique.傳奇.序號）。 */
    static UniqueItem parse(final String id, final YamlNode node, final Map<String, Affix> affixes) {
        final String name = node.requireString("name");
        final String base = node.requireString("base");
        final List<String> affixIds = new ArrayList<>();
        int index = 0;
        for (final YamlNode modNode : node.list("mods")) {
            index++;
            final String affixId = AFFIX_PREFIX + id + "." + index;
            final Map<String, int[]> vars = new LinkedHashMap<>();
            for (final String key : modNode.keys()) {
                if (!key.equals("text") && !key.equals("stats")) {
                    vars.put(key, modNode.range(key));
                }
            }
            final List<AffixMod> mods = new ArrayList<>();
            for (final YamlNode stat : modNode.list("stats")) {
                mods.add(AffixMod.parse(stat));
            }
            if (mods.isEmpty()) {
                throw new IllegalArgumentException(modNode.path() + "：傳奇詞綴至少要有一條 stats");
            }
            final List<AffixTier> tiers = List.of(new AffixTier(1, 1, 1, vars.isEmpty() ? Map.of("value", new int[] {0, 0}) : vars));
            affixes.put(affixId, new Affix(affixId, AffixType.SUFFIX, affixId, Set.of(SPAWN_TAG), Set.of(), Set.of(), List.of(),
                    modNode.requireString("text"), tiers, mods));
            affixIds.add(affixId);
        }
        if (affixIds.isEmpty()) {
            throw new IllegalArgumentException(node.path() + "：傳奇至少要有一條 mods");
        }
        final Set<String> sources = new HashSet<>();
        final boolean retired = node.bool("retired", false);
        for (final String source : node.strings("sources")) {
            if (!retired) { // 退役的傳奇（2026-10 PoE2 改版）不會從任何地方掉
                sources.add(source.trim().toLowerCase(Locale.ROOT));
            }
        }
        final int version = node.integer("version", 1);
        if (version < 1) {
            throw new IllegalArgumentException(node.path() + ".version：要從 1 開始");
        }
        final Map<Integer, List<Integer>> legacy = new LinkedHashMap<>();
        if (node.has("legacy")) {
            final YamlNode legacyNode = node.node("legacy");
            for (final String key : legacyNode.keys()) {
                final int from;
                try {
                    from = Integer.parseInt(key.trim());
                } catch (final NumberFormatException e) {
                    throw new IllegalArgumentException(node.path() + ".legacy：鍵要是舊的版本號，卻是「" + key + "」");
                }
                if (from < 1 || from >= version) {
                    throw new IllegalArgumentException(node.path() + ".legacy." + key + "：舊版本要在 1 到 " + (version - 1) + " 之間");
                }
                final List<Integer> mapping = new ArrayList<>();
                for (final String text : legacyNode.strings(key)) {
                    try {
                        mapping.add(Integer.parseInt(text.trim()));
                    } catch (final NumberFormatException e) {
                        throw new IllegalArgumentException(node.path() + ".legacy." + key + "：要寫舊的序號（0 = 新的），卻是「" + text + "」");
                    }
                }
                if (mapping.size() != affixIds.size()) {
                    throw new IllegalArgumentException(node.path() + ".legacy." + key + "：要有 " + affixIds.size() + " 個（每條新的詞綴一個）");
                }
                legacy.put(from, mapping);
            }
        }
        // 2026-10-28 照 PoE2：沒寫 weight 的照 rarity（UniqueRarity 的全域掉落權重）；這次之前一律 100
        final UniqueRarity rarity = UniqueRarity.parse(node.string("rarity", null));
        final int weight = node.integer("weight", rarity == null ? 100 : rarity.dropWeight());
        return new UniqueItem(id, name, base, affixIds, node.strings("flavour"), Math.max(1, node.integer("drop_level", 1)),
                Math.max(0, weight), sources, version, legacy, !retired && node.bool("chase", false));
    }

    /** 這個來源（final、map_boss、地圖代號）會不會掉這件。 */
    public boolean droppedBy(final String source) {
        return sources.contains(source.toLowerCase(Locale.ROOT));
    }
}
