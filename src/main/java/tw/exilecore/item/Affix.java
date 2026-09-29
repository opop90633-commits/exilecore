package tw.exilecore.item;

import tw.exilecore.core.config.YamlNode;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * 一條詞綴的定義（設定檔）：
 * <pre>
 * life_flat:
 *   type: prefix
 *   group: life
 *   spawn_tags: [armour, jewellery, shield]
 *   names: [神軀的, 不朽的, 雄壯的, 壯碩的, 強健的, 健康的]   # 依階級 1 → N
 *   text: "+{value} 最大生命"
 *   tiers:
 *     - { tier: 1, ilvl: 60, value: [80, 99], weight: 200 }
 *   mods:
 *     - { stat: life.max, type: FLAT, value: "{value}" }
 * </pre>
 *
 * @param id        代號
 * @param type      前綴／後綴
 * @param group     同群組的詞綴一件物品只會出現一個
 * @param spawnTags 只出現在帶這些標籤（任一）的基底上
 * @param names     各階級的名稱，索引 = 階級 − 1；不夠長時用最後一個
 * @param text      顯示文字，變數用大括號
 * @param tiers     階級，依 tier 遞增排序
 * @param mods      修飾器範本
 */
public record Affix(String id, AffixType type, String group, Set<String> spawnTags,
                    List<String> names, String text, List<AffixTier> tiers, List<AffixMod> mods) {

    public Affix {
        spawnTags = Set.copyOf(spawnTags);
        names = List.copyOf(names);
        tiers = List.copyOf(tiers);
        mods = List.copyOf(mods);
    }

    public static Affix parse(final String id, final YamlNode node) {
        final AffixType type = AffixType.parse(node.requireString("type"));
        final String group = node.string("group", id);
        final Set<String> spawnTags = new HashSet<>(node.strings("spawn_tags"));
        if (spawnTags.isEmpty()) {
            throw new IllegalArgumentException(node.path() + "：spawn_tags 不能是空的");
        }
        final List<String> names = node.strings("names");
        final String text = node.requireString("text");
        final List<AffixTier> tiers = new ArrayList<>();
        for (final YamlNode tierNode : node.list("tiers")) {
            tiers.add(AffixTier.parse(tierNode));
        }
        if (tiers.isEmpty()) {
            throw new IllegalArgumentException(node.path() + "：至少要有一個階級");
        }
        tiers.sort(Comparator.comparingInt(AffixTier::tier));
        final List<AffixMod> mods = new ArrayList<>();
        for (final YamlNode modNode : node.list("mods")) {
            mods.add(AffixMod.parse(modNode));
        }
        if (mods.isEmpty()) {
            throw new IllegalArgumentException(node.path() + "：至少要有一條 mods");
        }
        return new Affix(id, type, group, spawnTags, names, text, tiers, mods);
    }

    /** 這條詞綴能不能出現在這個基底上。 */
    public boolean canSpawnOn(final ItemBase base) {
        for (final String tag : base.tags()) {
            if (spawnTags.contains(tag)) {
                return true;
            }
        }
        return false;
    }

    /** 物品等級允許的階級（ilvl ≤ 物品等級）。 */
    public List<AffixTier> tiersFor(final int itemLevel) {
        final List<AffixTier> list = new ArrayList<>();
        for (final AffixTier tier : tiers) {
            if (tier.ilvl() <= itemLevel) {
                list.add(tier);
            }
        }
        return list;
    }

    public AffixTier tier(final int number) {
        for (final AffixTier tier : tiers) {
            if (tier.tier() == number) {
                return tier;
            }
        }
        throw new IllegalArgumentException("詞綴 " + id + " 沒有階級 " + number);
    }

    /** 這個階級的名稱（前綴形容詞或後綴稱號）。 */
    public String nameFor(final int tierNumber) {
        if (names.isEmpty()) {
            return "";
        }
        final int index = Math.min(names.size(), Math.max(1, tierNumber)) - 1;
        return names.get(index);
    }

    /** 把顯示文字裡的變數換成擲出的值。 */
    public String render(final Map<String, Integer> vars) {
        return substitute(text, vars);
    }

    public static String substitute(final String template, final Map<String, Integer> vars) {
        String result = template;
        for (final Map.Entry<String, Integer> entry : vars.entrySet()) {
            result = result.replace("{" + entry.getKey() + "}", String.valueOf(entry.getValue()));
        }
        return result;
    }
}
