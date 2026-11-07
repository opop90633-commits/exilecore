package tw.exilecore.item.unique;

import tw.exilecore.character.PlayerState;
import tw.exilecore.stats.StatKeys;
import tw.exilecore.stats.StatSheet;
import tw.exilecore.stats.Tags;

import java.util.Set;

/**
 * 2026-10-26：傳奇改技能消耗的效果（{@link tw.exilecore.skill.SkillService} 放技能扣魔力時呼叫）。
 * <ul>
 *   <li>{@link UniqueStats#COST_MANA_TO_LIFE}：魔力消耗的 x% 改扣生命（恍惚之矛）。</li>
 *   <li>{@link UniqueStats#MANA_COST_AS_PHYSICAL}：付了多少魔力，就受到那麼多的 x% 物理傷害（寂心；先扣能量護盾，最少留 1 生命）。</li>
 *   <li>{@link UniqueStats#LEECH_LIFE_ON_CAST}：施放法術時偷取 x% 最大生命（念珠魔杖）。</li>
 *   <li>{@link UniqueStats#NO_WARCRY}：不能使用戰吼（血偶巨鎚）。</li>
 *   <li>{@link UniqueStats#LIFE_LOSE_ON_SKILL}：使用技能時失去 x 生命（荊冠細環，2026-10-27）。</li>
 *   <li>{@link UniqueStats#SACRIFICE_ES_ON_SPELL}、{@link UniqueStats#SACRIFICE_ES_ON_ATTACK}：犧牲生命換能量護盾（縫魔，2026-10-28）。</li>
 * </ul>
 */
public final class UniqueCosts {

    private UniqueCosts() {
    }

    /**
     * 2026-11-01 照 PoE2 Rathpith Globe（獻祭之心）：非引導法術額外消耗 x% 最大生命（跟其他生命消耗加在一起，付不起就放不出來）。
     *
     * @param skillTags 技能的情境標籤（{@link Tags#NON_CHANNELLING} 由 SkillProfiles 加）
     */
    public static double extraLifeCost(final StatSheet sheet, final Set<String> skillTags, final double maxLife) {
        if (sheet == null || maxLife <= 0 || !skillTags.contains(Tags.SPELL) || !skillTags.contains(Tags.NON_CHANNELLING)) {
            return 0;
        }
        final double percent = Math.max(0, sheet.flat(UniqueStats.SPELL_EXTRA_LIFE_COST, Set.of()));
        return maxLife * percent / 100.0;
    }

    /**
     * 2026-11-07 照站長「瓦爾傳奇照原作」：技能額外消耗等於魔力消耗 x% 的生命（PoE2「Skills Gain 100% of Mana Cost as Extra Life Cost」，
     * {@link VaalKeys#SKILL_EXTRA_LIFE_COST_OF_MANA}）：魔力照付；生命那份照 {@link tw.exilecore.stats.CostKeys#manaToLife}
     * 換成生命消耗效率，再吃「增加／更多技能的生命消耗」。這次之前沒有。
     *
     * @param effective 技能的屬性表（含輔助）
     * @param manaPaid  這次實際付的魔力（已經扣掉改成生命的部分）
     */
    public static double extraLifeOfMana(final StatSheet effective, final Set<String> skillTags, final double manaPaid) {
        if (effective == null || manaPaid <= 0) {
            return 0;
        }
        final double percent = Math.max(0, effective.flat(VaalKeys.SKILL_EXTRA_LIFE_COST_OF_MANA, skillTags));
        if (percent <= 0) {
            return 0;
        }
        return manaPaid * percent / 100.0 * tw.exilecore.stats.CostKeys.manaToLife(effective, skillTags)
                * Math.max(0, 1 + effective.increased(tw.exilecore.stats.TreeKeys.LIFE_COST, skillTags))
                * Math.max(0, effective.more(tw.exilecore.stats.TreeKeys.LIFE_COST, skillTags));
    }

    /**
     * 2026-11-07 照站長「瓦爾傳奇照原作」（開放問題 2）：這個技能自己是不是「消耗生命」（PoE2 的 Spells which cost Life 是每個技能自己看）：
     * 有魔力消耗、而且血魔法、血祭術、「x% 魔力消耗改成生命消耗」（含已腐化寶石的）、恍惚之矛、獻祭之心（非引導法術）、
     * 「額外消耗魔力消耗 x% 的生命」任一成立。SkillBuild 照這個替技能加 {@link VaalKeys#TAG_COSTS_LIFE} 標籤。
     * 這次之前只有整張屬性表一個「法術要花生命」（PlayerStateService 的 spellsCostLife）。純函式（測試用）。
     */
    public static boolean costsLife(final StatSheet effective, final Set<String> skillTags, final int manaCost) {
        if (effective == null || manaCost <= 0) {
            return false;
        }
        return StatKeys.flag(effective, StatKeys.FLAG_BLOOD_MAGIC) || StatKeys.flag(effective, StatKeys.FLAG_SANGUIMANCY)
                || effective.flat(tw.exilecore.stats.TreeKeys.LIFE_COST_PERCENT, skillTags) > 0
                || effective.flat(UniqueStats.COST_MANA_TO_LIFE, Set.of()) > 0
                || effective.flat(VaalKeys.SKILL_EXTRA_LIFE_COST_OF_MANA, skillTags) > 0
                || skillTags.contains(Tags.SPELL) && skillTags.contains(Tags.NON_CHANNELLING)
                && effective.flat(UniqueStats.SPELL_EXTRA_LIFE_COST, Set.of()) > 0;
    }

    /** 這次的魔力消耗有多少改扣生命。 */
    public static double lifeShare(final StatSheet sheet, final double cost) {
        final double percent = Math.max(0, Math.min(100, sheet.flat(UniqueStats.COST_MANA_TO_LIFE, Set.of())));
        return cost <= 0 ? 0 : cost * percent / 100.0;
    }

    /** 付了魔力之後：寂心受到物理傷害（照物理傷害減免）。 */
    public static void afterManaPaid(final PlayerState state, final StatSheet sheet, final double manaPaid) {
        final double percent = sheet.flat(UniqueStats.MANA_COST_AS_PHYSICAL, Set.of());
        if (percent <= 0 || manaPaid <= 0) {
            return;
        }
        double damage = manaPaid * percent / 100.0 * (1 - Math.max(0, Math.min(90, sheet.flat(StatKeys.PHYS_REDUCTION, Set.of()))) / 100.0);
        final double es = Math.min(state.energyShield(), damage);
        if (es > 0) {
            state.setEnergyShield(state.energyShield() - es);
            damage -= es;
        }
        if (damage > 0) {
            state.setLife(Math.max(1, state.life() - damage));
        }
    }

    /** 放出技能之後：念珠魔杖施放法術時偷取最大生命。 */
    public static void onCast(final PlayerState state, final StatSheet sheet, final Set<String> skillTags) {
        final double percent = sheet.flat(UniqueStats.LEECH_LIFE_ON_CAST, Set.of());
        if (percent > 0 && skillTags.contains(Tags.SPELL) && state.maxLife() > 0) {
            state.addLeech(state.maxLife() * percent / 100.0);
        }
        // 2026-10-28 照 PoE2（縫魔）：施放法術時犧牲 x% 最大生命，獲得等量的能量護盾；
        // 磐石之拳版本：攻擊時犧牲 x% 最大生命，獲得一半（原作是符文保護，我們沒有，換成能量護盾）
        if (skillTags.contains(Tags.SPELL)) {
            sacrifice(state, sheet.flat(UniqueStats.SACRIFICE_ES_ON_SPELL, Set.of()), 1.0);
        }
        if (skillTags.contains(Tags.ATTACK)) {
            sacrifice(state, sheet.flat(UniqueStats.SACRIFICE_ES_ON_ATTACK, Set.of()), 0.5);
        }
        // 2026-10-27（荊冠細環）：使用技能時失去 x 生命（最少留 1 點）
        final double lose = sheet.flat(UniqueStats.LIFE_LOSE_ON_SKILL, Set.of());
        if (lose > 0 && state.life() > 1) {
            state.setLife(Math.max(1, state.life() - lose));
        }
    }

    /** 犧牲 x% 最大生命（最少留 1 點），獲得 ratio 倍的能量護盾（不超過上限）。 */
    static void sacrifice(final PlayerState state, final double percent, final double ratio) {
        if (percent <= 0 || state.maxLife() <= 0 || state.life() <= 1) {
            return;
        }
        final double amount = Math.min(state.life() - 1, state.maxLife() * percent / 100.0);
        state.setLife(state.life() - amount);
        if (state.maxEnergyShield() > 0) {
            state.setEnergyShield(Math.min(state.maxEnergyShield(), state.energyShield() + amount * ratio));
        }
    }

    /** 戰吼放不放得出來（血偶巨鎚）；可以放回傳 null。 */
    public static String warcryProblem(final StatSheet sheet) {
        return StatKeys.flag(sheet, UniqueStats.NO_WARCRY) ? "不能使用戰吼（裝備的傳奇：不能使用戰吼）" : null;
    }
}
