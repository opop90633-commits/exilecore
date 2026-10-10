package tw.exilecore.item;

import tw.exilecore.core.config.YamlNode;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.random.RandomGenerator;

/**
 * 詞綴的一個階級：
 * <pre>- { tier: 1, ilvl: 60, weight: 200, value: [80, 99] }</pre>
 * 或多變數：
 * <pre>- { tier: 1, ilvl: 60, weight: 200, min: [12, 16], max: [24, 30] }</pre>
 * 除了 tier／ilvl／weight／name 以外的鍵都當成變數範圍。
 *
 * @param tier   階級代號，越小越強（原本最強的是 1，後來加的更強階級是 0、-1…；玩家看到的 T 幾是排名，見 {@link Affix#rank}）
 * @param ilvl   需要的最低物品等級
 * @param weight 權重
 * @param vars   變數名 → [最小, 最大]
 */
public record AffixTier(int tier, int ilvl, int weight, Map<String, int[]> vars) {

    public AffixTier {
        // 保留設定檔的順序（min 在 max 前面）：擲骰、指紋都照這個順序，每次啟動都一樣（Map.copyOf 的順序每次 JVM 不同）
        vars = java.util.Collections.unmodifiableMap(new LinkedHashMap<>(vars));
    }

    public static AffixTier parse(final YamlNode node) {
        final int tier = node.integer("tier", 1);
        final int ilvl = node.integer("ilvl", 1);
        final int weight = node.integer("weight", 1000);
        final Map<String, int[]> vars = new LinkedHashMap<>();
        for (final String key : node.keys()) {
            if (key.equals("tier") || key.equals("ilvl") || key.equals("weight") || key.equals("name")) {
                continue;
            }
            vars.put(key, node.range(key));
        }
        if (vars.isEmpty()) {
            throw new IllegalArgumentException(node.path() + "：階級至少要有一個變數範圍，例如 value: [10, 20]");
        }
        return new AffixTier(tier, ilvl, weight, vars);
    }

    /** 在每個變數的範圍裡各擲一個整數。 */
    public Map<String, Integer> roll(final RandomGenerator random) {
        final Map<String, Integer> rolled = new LinkedHashMap<>();
        for (final Map.Entry<String, int[]> entry : vars.entrySet()) {
            final int lo = Math.min(entry.getValue()[0], entry.getValue()[1]);
            final int hi = Math.max(entry.getValue()[0], entry.getValue()[1]);
            rolled.put(entry.getKey(), lo == hi ? lo : lo + random.nextInt(hi - lo + 1));
        }
        return rolled;
    }
}
