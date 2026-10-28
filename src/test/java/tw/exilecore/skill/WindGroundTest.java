package tw.exilecore.skill;

import org.junit.jupiter.api.Test;
import tw.exilecore.item.unique.UniqueStats;
import tw.exilecore.stats.DamageType;
import tw.exilecore.stats.Modifier;
import tw.exilecore.stats.StatKeys;
import tw.exilecore.stats.StatSheet;
import tw.exilecore.stats.SupportKeys;

import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;

/** 2026-10-28 照 PoE2：貝雷克的戒指、元素之章的疾風技能元素地面；疾矢 III 的投射物速度套用在傷害。 */
class WindGroundTest {

    private static StatSheet sheet(final String... flags) {
        final StatSheet sheet = new StatSheet();
        for (final String flag : flags) {
            sheet.add(Modifier.flat(flag, 1, "測試"));
        }
        return sheet;
    }

    @Test
    void forcedGroundCountsWhenNotStandingOnOne() {
        assertSame(WindGround.NONE, WindGround.of(sheet()));
        final WindGround grip = WindGround.of(sheet(UniqueStats.WIND_GROUND_LIGHTNING));
        assertEquals(DamageType.LIGHTNING, grip.primary());
        assertEquals(List.of(DamageType.LIGHTNING), grip.elements(null));
        assertEquals(List.of(DamageType.FIRE), grip.elements(DamageType.FIRE), "踩到別的地面照踩到的（沒有「好幾種」）");
        assertNull(WindGround.NONE.primary());
        assertEquals(List.of(), WindGround.NONE.elements(null));
    }

    @Test
    void tamingIsBoostedByAllThree() {
        final WindGround taming = WindGround.of(sheet(UniqueStats.WIND_GROUND_FIRE, UniqueStats.WIND_GROUND_COLD,
                UniqueStats.WIND_GROUND_LIGHTNING, UniqueStats.WIND_GROUND_MULTIPLE));
        assertEquals(List.of(DamageType.FIRE, DamageType.COLD, DamageType.LIGHTNING), taming.elements(null));
        assertEquals(List.of(DamageType.COLD, DamageType.FIRE, DamageType.LIGHTNING), taming.elements(DamageType.COLD));
        final List<Modifier> gains = taming.gains(null, 50, "元素旋風");
        assertEquals(3, gains.size());
        assertEquals(StatKeys.damageExtra(DamageType.FIRE), gains.getFirst().stat());
    }

    @Test
    void projectileAccelerationThreeAppliesSpeedToDamage() {
        final StatSheet plain = new StatSheet();
        plain.add(Modifier.increased(StatKeys.SKILL_PROJECTILE_SPEED, 0.3, "測試"));
        assertEquals(0, SkillBuild.projectileSpeedAsDamage(plain, Set.of()), 1e-9, "沒掛疾矢 III 不算");
        final StatSheet supported = new StatSheet();
        supported.add(Modifier.increased(StatKeys.SKILL_PROJECTILE_SPEED, 0.3, "測試"));
        supported.add(Modifier.increased(StatKeys.SKILL_PROJECTILE_SPEED, -0.1, "測試"));
        supported.add(Modifier.flat(SupportKeys.FLAG_PROJECTILE_SPEED_TO_DAMAGE, 1, "疾矢 III"));
        assertEquals(0.2, SkillBuild.projectileSpeedAsDamage(supported, Set.of()), 1e-9, "增加與減少都算");
    }
}
