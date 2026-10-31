package tw.exilecore.skill;

import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import tw.exilecore.ailment.AilmentType;
import tw.exilecore.stats.DamageType;
import tw.exilecore.stats.Modifier;
import tw.exilecore.stats.SkillProfile;
import tw.exilecore.stats.StatKeys;
import tw.exilecore.stats.Tags;

import java.io.IOException;
import java.nio.file.Path;
import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** 2026-10-27 照 PoE2 Snap（噬異爆）：用掉異常、各元素自己的傷害表與暴擊率、品質。 */
class SnapTest {

    static SkillConfig config;

    @BeforeAll
    static void load() throws IOException {
        config = new SkillConfig();
        config.load(Path.of("src/main/resources/data"));
        tw.exilecore.currency.GemQuality.load(Path.of("src/main/resources/data"));
    }

    private static double level(final String stat, final int level) {
        for (final ScaledMod mod : config.levelModifiers(Snap.ID)) {
            if (mod.stat().equals(stat)) {
                return mod.value().at(level);
            }
        }
        throw new AssertionError(stat);
    }

    private static double[] cold(final int level) {
        return SkillProfiles.spellDamage(config, config.skill(Snap.ID), level);
    }

    private static double crit(final SkillProfile profile) {
        return profile.local().stream().filter(m -> m.stat().equals(StatKeys.SPELL_BASE_CRIT)).mapToDouble(Modifier::value).sum();
    }

    @Test
    void damageTablesMatchRefdb() {
        assertEquals(14, cold(1)[0], 1e-9);
        assertEquals(21, cold(1)[1], 1e-9);
        assertEquals(473, cold(20)[0], 1e-9);
        assertEquals(709, cold(20)[1], 1e-9);
        assertEquals(1, level(Snap.LIGHTNING_MIN, 1), 1e-9);
        assertEquals(15, level(Snap.LIGHTNING_MAX, 1), 1e-9);
        assertEquals(27, level(Snap.LIGHTNING_MIN, 20), 1e-9);
        assertEquals(512, level(Snap.LIGHTNING_MAX, 20), 1e-9);
        assertEquals(6, level(Snap.FIRE_MIN, 1), 1e-9);
        assertEquals(8, level(Snap.FIRE_MAX, 1), 1e-9);
        assertEquals(192, level(Snap.FIRE_MIN, 20), 1e-9);
        assertEquals(288, level(Snap.FIRE_MAX, 20), 1e-9);
        assertEquals(3358, level(Snap.FIRE_MIN, 40), 1e-9);
        assertEquals(8944, level(Snap.LIGHTNING_MAX, 40), 1e-9);
        assertEquals(Set.of(DamageType.COLD), config.skill(Snap.ID).damageTypes().keySet());
        assertEquals(4, config.skill(Snap.ID).cooldown(), 1e-9);
        assertNull(config.resource(Snap.ID).remnant(), "殘骸由 Snap.java 照元素留下");
    }

    @Test
    void priorityIsFreezeShockIgniteWhichIsAlsoStrongest() {
        assertEquals(AilmentType.FREEZE, Snap.pick(type -> true));
        assertEquals(AilmentType.SHOCK, Snap.pick(type -> type != AilmentType.FREEZE));
        assertEquals(AilmentType.IGNITE, Snap.pick(type -> type == AilmentType.IGNITE || type == AilmentType.CHILL));
        assertNull(Snap.pick(type -> type == AilmentType.CHILL || type == AilmentType.POISON), "冰緩、中毒不算");
        for (int lv = 1; lv <= 40; lv++) {
            final double c = cold(lv)[0] + cold(lv)[1];
            final double l = level(Snap.LIGHTNING_MIN, lv) + level(Snap.LIGHTNING_MAX, lv);
            final double f = level(Snap.FIRE_MIN, lv) + level(Snap.FIRE_MAX, lv);
            assertTrue(c > l && l > f, "等級 " + lv);
        }
    }

    @Test
    void elementProfileSwapsDamageCritAndTags() {
        final SkillProfile base = SkillProfiles.build(config, config.skill(Snap.ID), 20, Set.of(),
                List.of(Modifier.flat(StatKeys.SPELL_BASE_CRIT, 13, "test")));
        final SkillProfile fire = Snap.elementProfile(base, DamageType.FIRE, 192, 288, false);
        assertEquals(192, fire.spellDamage().get(DamageType.FIRE), 1e-9);
        assertEquals(288, fire.spellMax().get(DamageType.FIRE), 1e-9);
        assertEquals(0, fire.spellDamage().get(DamageType.COLD), 1e-9);
        assertTrue(fire.tags().contains(Tags.FIRE));
        assertFalse(fire.tags().contains(Tags.COLD));
        assertFalse(fire.tags().contains(Tags.LIGHTNING));
        assertEquals(8, crit(fire), 1e-9);
        final SkillProfile lightning = Snap.elementProfile(base, DamageType.LIGHTNING, 27, 512, true);
        assertEquals(11, crit(lightning), 1e-9);
        assertTrue(lightning.local().stream().anyMatch(m -> m.stat().equals(StatKeys.DAMAGE) && Math.abs(m.value() - 0.5) < 1e-9), "傳奇敵人更多 50%");
        assertEquals(13, crit(Snap.elementProfile(base, DamageType.COLD, 473, 709, false)), 1e-9);
        assertEquals(0, Snap.remnantIndex(DamageType.FIRE));
        assertEquals(1, Snap.remnantIndex(DamageType.COLD));
        assertEquals(2, Snap.remnantIndex(DamageType.LIGHTNING));
    }

    @Test
    void qualityGivesExtraRemnantChanceAndCooldownRecovery() {
        final List<Modifier> mods = tw.exilecore.currency.GemQuality.modifiers(Snap.ID, 20, "品質");
        assertEquals(10, mods.stream().filter(m -> m.stat().equals(Snap.EXTRA_REMNANT)).mapToDouble(Modifier::value).sum(), 1e-9);
        // 2026-10-27 照 PoE2：這條是額外的品質效果（進階奇術才有，gem_quality.yml 的 advanced），一般品質沒有了
        assertEquals(0, mods.stream().filter(m -> m.stat().equals(StatKeys.COOLDOWN_RECOVERY)).mapToDouble(Modifier::value).sum(), 1e-9);
        final List<Modifier> advanced = tw.exilecore.currency.GemQuality.advancedModifiers(Snap.ID, 20, "高階奇術");
        assertEquals(1.0, advanced.stream().filter(m -> m.stat().equals(StatKeys.COOLDOWN_RECOVERY)).mapToDouble(Modifier::value).sum(), 1e-9);
    }
}
