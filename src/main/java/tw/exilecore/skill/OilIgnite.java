package tw.exilecore.skill;

import tw.exilecore.stats.DamageCalculator;
import tw.exilecore.stats.DamageType;
import tw.exilecore.stats.SkillProfile;
import tw.exilecore.stats.StatKeys;
import tw.exilecore.stats.StatSheet;
import tw.exilecore.stats.Tags;

import java.util.Map;
import java.util.Set;

/**
 * 2026-11-01 照 PoE2 Oil Grenade 的 Oil Ground（PoB2：skill_main_hand_total_attack_fire_damage_permyriad_to_add_as_unscalable_damage_to_oil_ground_ignite
 * 15000、active_skill_base_physical_damage_%_to_convert_to_fire 100；說明「Once Ignited, Oil Ground adds 150% of this Attack's Fire Damage as
 * unscalable Damage to the Ignite」）：技能寫了 {@link #KEY}（FLAT，百分比）的火油，點燃之後不再打擊中，改成讓裡面的敵人點燃——
 * 點燃的基礎是「這次攻擊（火油那一段：物理 100% 轉火焰）的火焰傷害」× 150%。這份傷害是不可調整的（unscalable）：
 * 已經吃過攻擊本身的加成，點燃幅度的增加／更多不再乘上去；持續時間、異常速度照常。
 * 沒寫的技能照舊（SkillZones 的地面效果一波一下擊中）。
 */
public final class OilIgnite {

    /** FLAT：點燃的火油把「這次攻擊的火焰傷害」的幾 % 當成不可調整的點燃基礎（火油榴彈 150）。 */
    public static final String KEY = "oil.ignite_from_attack";

    private static final Set<String> FIRE = Set.of(Tags.FIRE);

    private OilIgnite() {
    }

    /** 這一灘火油寫的百分比（沒寫 0）。 */
    static double percent(final SkillProfile profile) {
        return profile == null ? 0 : SkillZones.localFlat(profile, KEY);
    }

    /** 火油那一段：物理全部轉火焰（其他轉換拿掉）。純函式。 */
    static SkillProfile allFire(final SkillProfile profile) {
        return new SkillProfile(profile.id(), profile.tags(), profile.effectiveness(), profile.spellDamage(), profile.spellDamageMax(),
                Map.of(DamageType.FIRE, 1.0), profile.local());
    }

    /**
     * 要交給 {@code AilmentService.igniteFrom} 的基礎火焰：fire × percent%，再除掉 igniteFrom 會乘上去的點燃幅度（1 ＋ 增加）× 更多，
     * 讓這份傷害不吃點燃幅度（不可調整）。幅度被扣到 0 以下回傳 0。純函式。
     */
    static double unscalableBase(final double fire, final double percent, final double increasedMagnitude, final double moreMagnitude) {
        final double scale = Math.max(0, 1 + increasedMagnitude) * Math.max(0, moreMagnitude);
        if (!(fire > 0) || !(percent > 0) || !(scale > 1e-9)) {
            return 0;
        }
        return fire * percent / 100.0 / scale;
    }

    /** 這一灘點燃的火油一波要給的點燃基礎（{@link #unscalableBase}）；這個技能沒寫 {@link #KEY} 回傳 0。 */
    static double base(final SkillProfile profile, final StatSheet ownerSheet) {
        final double percent = percent(profile);
        if (percent <= 0 || ownerSheet == null) {
            return 0;
        }
        final double fire = DamageCalculator.expectedOutgoing(ownerSheet, allFire(profile)).get(DamageType.FIRE);
        final StatSheet effective = profile.effective(ownerSheet);
        return unscalableBase(fire, percent, effective.increased(StatKeys.IGNITE_MAGNITUDE, FIRE), effective.more(StatKeys.IGNITE_MAGNITUDE, FIRE));
    }
}
