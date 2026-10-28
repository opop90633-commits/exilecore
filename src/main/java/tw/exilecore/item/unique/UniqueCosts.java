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
