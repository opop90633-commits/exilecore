package tw.exilecore.item;

/**
 * 2026-10-29 照站長：合併掉的舊基底（retired ＋ merged_into）合併到的是固定等級的基底（沒有 base_level、不照物品等級縮放，
 * 例如照 PoE2 的施法長杖、法杖、權杖）時，玩家手上的舊物品直接換成那個基底：需求照新基底（PoE2 原值），物品等級、詞綴、品質不變，
 * 固有詞綴的數值清掉（讀物品時 {@link AffixUpgrade} 照新基底補）。
 * 這次之前舊的長杖、法杖、權杖一直留在舊基底，最高那一階（暗鋼長杖、雷紋法杖、秘銀權杖）需求被改成 1 等，物品等級 92 的武器變成 1 等就能拿。
 * 合併到照物品等級縮放的基底（防具、其他武器）照舊不動（數值本來就照它原本那一階）。純邏輯，不依賴 Bukkit。
 */
public final class BaseUpgrade {

    private BaseUpgrade() {
    }

    /** 要換成哪個基底；不用換回傳 null。 */
    static String target(final ItemRepository repository, final ItemData data) {
        if (data == null || data.base() == null) {
            return null;
        }
        final ItemBase base = repository.base(data.base());
        if (base == null || base.mergedInto() == null) {
            return null;
        }
        final String canonical = repository.canonicalBase(data.base());
        final ItemBase target = repository.base(canonical);
        if (target == null || canonical.equals(data.base()) || target.baseLevel() > 0) {
            return null;
        }
        return canonical;
    }

    /** 換過去（直接改 data）；回傳有沒有改。 */
    public static boolean apply(final ItemRepository repository, final ItemData data) {
        final String target = target(repository, data);
        if (target == null) {
            return false;
        }
        data.setBase(target);
        data.setImplicitVars(new java.util.LinkedHashMap<>());
        return true;
    }
}
