package tw.exilecore.skill;

import org.bukkit.Location;
import org.bukkit.entity.LivingEntity;
import org.bukkit.entity.Player;
import tw.exilecore.ExileCore;
import tw.exilecore.stats.DamageType;
import tw.exilecore.stats.SkillProfile;
import tw.exilecore.stats.StatSheet;

/**
 * 2026-11-01 照 PoE2 0.5 Solar Orb（SolarOrbPlayer 的 Ignite statset：active_skill_base_area_of_effect_radius 10，
 * 「Ignites as though dealing 6–8 → 287–430 Fire damage」）：珠子很近（1 公尺，跟 2.5 公尺的爆炸一起吃範圍）的敵人直接被點燃，
 * 點燃照技能 level_modifiers 的 as_though.min／max（{@link AsThough}，吃這個技能的火焰、法術加成，不打人）。
 * 原作沒寫多久點一次，這裡跟屍焰爆的核心一樣每 0.5 秒補一次（點燃本身 4 秒，站著就一直燒）。這次之前用 15% 點燃機率代替。
 */
final class SolarOrbs {

    static final String ID = "scorching_sun_pearl";
    /** 點燃範圍 ÷ 爆炸範圍（1 公尺 ÷ 2.5 公尺）。 */
    static final double IGNITE_FRACTION = 1.0 / 2.5;
    /** 每幾 tick 補一次點燃。 */
    static final long IGNITE_EVERY = 10;

    private SolarOrbs() {
    }

    static boolean is(final String skill) {
        return ID.equals(skill);
    }

    /** 珠子放下後第 age tick 要不要補點燃：放下的那一刻就點，之後每 10 tick。純函式。 */
    static boolean due(final long age) {
        return age >= 0 && age % IGNITE_EVERY == 0;
    }

    /** 點燃半徑（格）：爆炸半徑（已含範圍加成）× 1 ÷ 2.5。純函式。 */
    static double igniteRadius(final double pulseRadius) {
        return Math.max(0, pulseRadius) * IGNITE_FRACTION;
    }

    /** 對珠子身邊的敵人補點燃（SkillZones 每 tick 對烈陽珠的地面效果呼叫）。 */
    static void ignite(final ExileCore plugin, final SkillTargets targets, final Player owner, final Location center, final double pulseRadius,
                       final SkillProfile profile) {
        if (plugin.ailments() == null || profile == null || owner == null) {
            return;
        }
        final StatSheet sheet = plugin.players().sheet(owner);
        final double[] range = AsThough.range(profile.effective(sheet), profile.tags());
        if (range[1] <= 0) {
            return;
        }
        final SkillProfile igniteProfile = AsThough.profile(profile, DamageType.FIRE, range[0], range[1]);
        final double fire = AsThough.amount(sheet, igniteProfile, DamageType.FIRE);
        if (fire <= 0) {
            return;
        }
        final StatSheet igniteSheet = igniteProfile.effective(sheet);
        for (final LivingEntity victim : targets.around(center, igniteRadius(pulseRadius), owner)) {
            plugin.ailments().igniteFrom(owner, victim, fire, igniteSheet);
        }
    }
}
