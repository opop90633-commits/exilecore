package tw.exilecore.combat;

import tw.exilecore.stats.DamagePacket;
import tw.exilecore.stats.DamageType;
import tw.exilecore.stats.Modifier;
import tw.exilecore.stats.SkillProfile;
import tw.exilecore.stats.StatKeys;
import tw.exilecore.stats.StatSheet;
import tw.exilecore.stats.Tags;

import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * 荊棘：被近戰攻擊擊中（沒閃掉、沒擋掉）時，對攻擊的怪物反彈一筆物理傷害。純函式，不依賴 Bukkit。
 * <p>
 * 反彈那一下是一個沒有「攻擊」標籤的技能：不用擲命中、不吃武器傷害與「攻擊附加傷害」，
 * 數值就是裝備給的 {@link StatKeys#THORNS_MIN}～{@link StatKeys#THORNS_MAX}，
 * 再吃「增加荊棘傷害」（標籤 {@link Tags#THORNS}）和不分類型的增加；怪物的護甲照常減免；不會暴擊。
 * 遠程攻擊與法術不會觸發荊棘（要貼著打你才會被反彈）。
 */
public final class Thorns {

    /** 反彈那一下的技能代號。 */
    public static final String ID = "thorns";

    private Thorns() {
    }

    /** 2026-10-26 照 PoE2 天賦樹（觀星專屬）：獲得等同最大生命 x% 的物理荊棘傷害（變形時）。 */
    public static final String LIFE_PERCENT = "thorns.life_percent";

    private static double lifeThorns(final StatSheet sheet) {
        final double percent = sheet.flat(LIFE_PERCENT, Set.of());
        return percent <= 0 ? 0 : sheet.get(StatKeys.LIFE_MAX) * percent / 100.0;
    }

    /** 這張屬性表的荊棘傷害範圍 {下限, 上限}；沒有荊棘回傳 null。 */
    public static double[] range(final StatSheet sheet) {
        final double min = sheet.flat(StatKeys.THORNS_MIN, Set.of()) + lifeThorns(sheet);
        final double max = sheet.flat(StatKeys.THORNS_MAX, Set.of()) + lifeThorns(sheet);
        if (max <= 0) {
            return null;
        }
        final double scale = Math.max(0, 1 + sheet.increased(StatKeys.THORNS_DAMAGE, Set.of())); // 2026-10：精魂輔助（刺甲）
        return new double[] {Math.max(0, Math.min(min, max)) * scale, max * scale};
    }

    /** 反彈那一下的技能描述：物理、範圍 {下限, 上限}、效益 0（不吃附加傷害）、不會暴擊（荊棘傷害不暴擊）。 */
    public static SkillProfile profile(final double min, final double max) {
        return new SkillProfile(ID, Set.of(Tags.THORNS), 0,
                new DamagePacket().set(DamageType.PHYSICAL, min), new DamagePacket().set(DamageType.PHYSICAL, max), Map.of(),
                List.of(Modifier.flat(StatKeys.FLAG_NO_CRIT, 1, "荊棘")));
    }

    /**
     * 2026-10-26：反彈那一下（物理＋傳奇的火焰、冰冷荊棘：焚衣、浮冰圓盾），全部吃「增加荊棘傷害」；沒有荊棘回傳 null。
     */
    public static SkillProfile profile(final StatSheet sheet) {
        final double scale = Math.max(0, 1 + sheet.increased(StatKeys.THORNS_DAMAGE, Set.of()));
        final DamagePacket min = new DamagePacket();
        final DamagePacket max = new DamagePacket();
        boolean any = false;
        for (final Object[] part : new Object[][] {{DamageType.PHYSICAL, StatKeys.THORNS_MIN, StatKeys.THORNS_MAX},
                {DamageType.FIRE, tw.exilecore.item.unique.UniqueStats.THORNS_FIRE_MIN, tw.exilecore.item.unique.UniqueStats.THORNS_FIRE_MAX},
                {DamageType.COLD, tw.exilecore.item.unique.UniqueStats.THORNS_COLD_MIN, tw.exilecore.item.unique.UniqueStats.THORNS_COLD_MAX},
                // 2026-10-27 照 PoE2 Crackling Barrier：引導時的閃電荊棘
                {DamageType.LIGHTNING, tw.exilecore.stats.SupportExtraKeys.THORNS_LIGHTNING_MIN, tw.exilecore.stats.SupportExtraKeys.THORNS_LIGHTNING_MAX}}) {
            final double fromLife = part[0] == DamageType.PHYSICAL ? lifeThorns(sheet) : 0;
            final double low = sheet.flat((String) part[1], Set.of()) + fromLife;
            final double high = sheet.flat((String) part[2], Set.of()) + fromLife;
            if (high > 0) {
                min.set((DamageType) part[0], Math.max(0, Math.min(low, high)) * scale);
                max.set((DamageType) part[0], high * scale);
                any = true;
            }
        }
        // 2026-10-26 照 PoE2 天賦樹：「+x% 荊棘暴擊率」——荊棘可以暴擊，基礎暴擊率就是這個數（這次之前荊棘一律不暴擊）
        final double crit = sheet.flat(tw.exilecore.stats.KeystoneKeys.THORNS_CRIT, Set.of());
        return any ? new SkillProfile(ID, Set.of(Tags.THORNS), 0, min, max, Map.of(),
                List.of(crit > 0 ? Modifier.flat(StatKeys.SPELL_BASE_CRIT, crit, "荊棘") : Modifier.flat(StatKeys.FLAG_NO_CRIT, 1, "荊棘"))) : null;
    }

    /**
     * 打到你的這一下會不會觸發荊棘：要是攻擊者本人直接打的（不是箭、火球這種投射物或效果），而且是近戰（技能標籤有 melee）。
     *
     * @param directHit 傷害來源就是攻擊者本人，原因是近戰攻擊（ENTITY_ATTACK、ENTITY_SWEEP_ATTACK）
     */
    public static boolean triggersOn(final boolean directHit, final Set<String> skillTags) {
        return directHit && skillTags.contains(Tags.MELEE);
    }

    /** 2026-10-17：有「荊棘也會被遠程與法術觸發」的話，什麼擊中都觸發。 */
    public static boolean triggersOn(final boolean directHit, final Set<String> skillTags, final boolean anyHit) {
        return anyHit || triggersOn(directHit, skillTags);
    }
}
