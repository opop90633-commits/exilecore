package tw.exilecore.item;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * 詞綴補正（2026-10）：詞綴（或基底的固定詞綴）後來多了變數——例如地圖詞綴「怪物暴擊率」加了暴擊傷害 {multi}、
 * 揭露詞綴「戰吼效果」加了戰吼傷害 {b}——舊物品身上沒有那個數值，一算屬性、一畫說明就丟「缺少變數」：
 * 倉庫被當成資料損毀、屬性表算不出來（技能放不出去、選單打不開）。
 * <p>
 * 讀物品的時候（{@link ItemService#read}、{@link ItemService#refresh}）把缺的變數補上：用那一階範圍的最低值
 * （那一階已經被刪掉的用最弱的一階）。只補缺的，已經擲出來的數值不動；{@code refresh} 會把補好的寫回物品。
 * 純邏輯，不依賴 Bukkit。
 */
public final class AffixUpgrade {

    private AffixUpgrade() {
    }

    /**
     * 把這件物品每條詞綴、固定詞綴缺的變數補上（直接改 data）。
     *
     * @return 有沒有改
     */
    public static boolean apply(final ItemRepository repository, final ItemData data) {
        boolean changed = false;
        for (final RolledAffix rolled : data.affixes()) {
            final Affix affix = rolled == null || rolled.id() == null ? null : repository.affix(rolled.id());
            if (affix == null) {
                continue;
            }
            final Map<String, Integer> filled = fill(rolled.vars(), ranges(affix, rolled.tier()));
            if (filled != null) {
                rolled.setVars(filled);
                changed = true;
            }
        }
        final ItemBase base = repository.base(data.base());
        if (base != null && base.implicit() != null && !base.implicit().vars().isEmpty()) {
            final Implicit implicit = ItemStats.implicit(repository, base, data);
            final Map<String, Integer> filled = implicit == null ? null : fill(data.implicitVars(), implicit.vars());
            if (filled != null) {
                data.setImplicitVars(filled);
                changed = true;
            }
        }
        return changed;
    }

    /** 這一階每個變數的範圍；這一階已經不在了就用最弱的一階（階級代號最大的）。 */
    static Map<String, int[]> ranges(final Affix affix, final int tierId) {
        final Map<String, int[]> exact = affix.ranges(tierId);
        if (!exact.isEmpty()) {
            return exact;
        }
        AffixTier weakest = null;
        for (final AffixTier tier : affix.tiers()) {
            if (weakest == null || tier.tier() > weakest.tier()) {
                weakest = tier;
            }
        }
        return weakest == null ? Map.of() : weakest.vars();
    }

    /**
     * 補上缺的變數：ranges 裡有、vars 裡沒有的，補範圍的最低值。
     *
     * @return 補好的新表（原本的順序在前、補的接在後面）；沒有缺的回傳 null
     */
    static Map<String, Integer> fill(final Map<String, Integer> vars, final Map<String, int[]> ranges) {
        Map<String, Integer> filled = null;
        for (final Map.Entry<String, int[]> entry : ranges.entrySet()) {
            if (vars != null && vars.get(entry.getKey()) != null) {
                continue;
            }
            if (filled == null) {
                filled = vars == null ? new LinkedHashMap<>() : new LinkedHashMap<>(vars);
            }
            final int[] range = entry.getValue();
            filled.put(entry.getKey(), range == null || range.length == 0 ? 0 : range.length == 1 ? range[0] : Math.min(range[0], range[1]));
        }
        return filled;
    }
}
