package tw.exilecore.skill;

import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import tw.exilecore.stats.Modifier;
import tw.exilecore.stats.ModType;

import java.io.IOException;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 2026-11-01 照 PoE2（PoB2 minion.lua 與 CalcTools 的 statInterpolation 3）：珂芮絲三招的傷害照巨靈等級（技能等級 × 2）的逐級表，
 * 跟羅贊、娜瑟菈的招式同一套公式；藥劑的基礎暴擊率、幻影神射的武器需求、娜瑟菈的寧靜 40 級表。
 */
class KelariDamageParityTest {

    static SkillConfig config;

    @BeforeAll
    static void load() throws IOException {
        config = new SkillConfig();
        config.load(Path.of("src/main/resources/data"));
    }

    /** PoB2：SkillDamageBaseEffectiveness × baseEffectiveness × (1 + inc × (L−1)) × (1 + dmgInc)^(L−1)，L = 巨靈等級 = 技能等級 × 2。 */
    private static double[] pob(final double base, final double inc, final double dmgInc, final int skillLevel) {
        final int actor = skillLevel * 2;
        final double eff = 3.885209 * base * (1 + inc * (actor - 1)) * Math.pow(1 + dmgInc, actor - 1);
        return new double[] {Math.floor(eff * 0.8 + 0.5), Math.floor(eff * 1.2 + 0.5)};
    }

    private static double[] ours(final String id, final int level) {
        return SkillProfiles.spellDamage(config, config.skill(id), level);
    }

    @Test
    void kelariCommandsFollowTheDjinnFormula() {
        for (final int level : new int[] {1, 20, 40}) {
            assertArrayEquals(pob(5.01, 0.25, 0.0415, level), ours("sand_spirit_trick", level), 1e-9, "詭計 " + level);
            assertArrayEquals(pob(5.01, 0.055, 0.0415, level), ours("asc_kelari_clone", level), 1e-9, "分身 " + level);
            assertArrayEquals(pob(1.79, 0.25, 0.038, level), ours("sand_spirit_cruelty", level), 1e-9, "殘酷 " + level);
            assertArrayEquals(pob(1.79, 0.25, 0.038, level), ours("asc_kelari_blade", level), 1e-9, "刀刃 " + level);
            assertArrayEquals(pob(4.9, 0.25, 0.0415, level), ours("sand_spirit_verdict", level), 1e-9, "裁決 " + level);
            // 同一套：羅贊的清算（已經照這套寫的）
            assertArrayEquals(pob(2.72, 0.25, 0.0415, level), ours("blade_spirit_settle", level), 1e-9, "清算 " + level);
            assertArrayEquals(pob(9.36, 0.25, 0.0415, level), ours("asc_ruzhan_wave", level), 1e-9, "熔岩浪 " + level);
        }
        assertArrayEquals(new double[] {817, 1226}, ours("sand_spirit_trick", 20), 1e-9);
        assertArrayEquals(new double[] {256, 384}, ours("sand_spirit_cruelty", 20), 1e-9);
        assertArrayEquals(new double[] {800, 1199}, ours("sand_spirit_verdict", 20), 1e-9);
    }

    @Test
    void kelariCommandNumbers() {
        final SkillDefinition trick = config.skill("sand_spirit_trick");
        final SkillDefinition cruelty = config.skill("sand_spirit_cruelty");
        final SkillDefinition verdict = config.skill("sand_spirit_verdict");
        assertEquals(0.8, trick.castTime(), 1e-9);
        assertEquals(0.8, cruelty.castTime(), 1e-9);
        assertEquals(0.5, verdict.castTime(), 1e-9);
        assertEquals(5, trick.cooldown(), 1e-9);
        assertEquals(5, cruelty.cooldown(), 1e-9);
        assertEquals(12, verdict.cooldown(), 1e-9);
        for (final SkillDefinition definition : new SkillDefinition[] {trick, cruelty, verdict}) {
            assertEquals(15, flat(definition, "spell.base_crit"), 1e-9, definition.id());
        }
    }

    @Test
    void concoctionsHaveTheirOwnCritAndEffects() {
        assertEquals(11, flat(config.skill("fracture_tonic"), "spell.base_crit"), 1e-9);
        assertEquals(1, flat(config.skill("fracture_tonic"), "asc.all_exposure"), 1e-9);
        assertEquals(1, flat(config.skill("fracture_tonic"), "asc.freeze_primed"), 1e-9);
        assertEquals(8, flat(config.skill("thunder_blast_tonic"), "spell.base_crit"), 1e-9);
        assertTrue(config.skill("thunder_blast_tonic").modifiers().stream()
                .anyMatch(m -> m.stat().equals("shock.effect") && m.type() == ModType.MORE), "感電效果是更多 50%");
    }

    @Test
    void mirageNeedsBowCrossbowOrSpearAndCalmingGoesTo40() {
        final SkillDefinition mirage = config.skill("mirage_deadeye");
        assertTrue(mirage.usableWith(java.util.Set.of("bow")));
        assertTrue(mirage.usableWith(java.util.Set.of("crossbow")));
        assertTrue(mirage.usableWith(java.util.Set.of("spear")));
        assertTrue(!mirage.usableWith(java.util.Set.of("sword")) && !mirage.usableWith(java.util.Set.of()), "別的武器、徒手都不行");
        double regen1 = 0;
        double regen20 = 0;
        double regen40 = 0;
        for (final ScaledMod mod : config.levelModifiers("final_mirage_spirit")) {
            if (mod.stat().equals("asc.calming_regen")) {
                regen1 = mod.value().at(1);
                regen20 = mod.value().at(20);
                regen40 = mod.value().at(40);
            }
        }
        assertEquals(10, regen1, 1e-9);
        assertEquals(67, regen20, 1e-9);
        assertEquals(127, regen40, 1e-9);
    }

    private static double flat(final SkillDefinition definition, final String stat) {
        double total = 0;
        for (final Modifier modifier : definition.modifiers()) {
            if (modifier.stat().equals(stat) && modifier.type() == ModType.FLAT) {
                total += modifier.value();
            }
        }
        return total;
    }
}
