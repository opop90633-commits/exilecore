package tw.exilecore.skill;

import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import tw.exilecore.core.config.YamlNode;
import tw.exilecore.stats.DamageType;
import tw.exilecore.stats.Modifier;
import tw.exilecore.stats.SkillProfile;
import tw.exilecore.stats.StatKeys;
import tw.exilecore.stats.StatSheet;

import java.io.IOException;
import java.io.StringReader;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** 2026-10-28 照 PoE2：技能的分段傷害（skills.yml 的 stages、SkillStages）。 */
class SkillStagesTest {

    static SkillConfig config;

    @BeforeAll
    static void load() throws IOException {
        config = new SkillConfig();
        config.load(Path.of("src/main/resources/data"));
    }

    private static SkillBuild build(final String id, final int level) {
        return SkillBuild.of(config, config.skill(id), level, 90, false, List.of(), Set.of(), new StatSheet());
    }

    private static double more(final SkillProfile profile, final String source) {
        return profile.local().stream().filter(m -> m.stat().equals(StatKeys.DAMAGE) && source.equals(m.source()))
                .mapToDouble(Modifier::value).sum();
    }

    /** 每一段的逐級表照 RePoE（1／20／40 級）。 */
    @Test
    void stageTablesFollowPoe2() {
        final Object[][] cases = {
                {"tempest_flurry", "final", 1.35, 4.21, 11.17},
                {"tempest_flurry", "third", 0.9, 0.9, 0.9},
                {"ice_strike", "final", 1.3, 4.05, 10.76},
                {"tempest_bell", "impact", 1.0, 4.08, 18.1},
                {"whirlwind_lance", "whirlwind", 0.6, 2.17, 7.89},
                {"plasma_blast", "explosion", 3.0, 12.25, 54.29},
                {"siege_salvo", "explosion", 1.5, 5.43, 19.73},
                {"storm_javelin", "infused", 0.25, 0.91, 3.29},
                {"rapid_thrust", "last", 1.0, 3.12, 8.28},
                {"rapid_thrust", "explosion", 1.75, 5.46, 14.48},
                {"stampede", "slam", 1.2, 3.74, 9.93},
                {"forge_hammer", "recall", 0.8, 3.27, 14.48},
                {"reeling_palm", "projectile", 0.5, 2.04, 9.05},
                {"piercing_rounds", "vs_armour_broken", 2.2, 5.8, 11.14},
                {"serpent_fury", "serpent", 0.55, 1.99, 7.24},
                {"prism_rend", "cold", 3.5, 14.29, 63.34},
                {"prism_rend", "lightning", 1.4, 5.72, 25.34},
                {"sunfire_spear", "pulse", 0.27, 1.1, 4.89},
                {"glacial_cascade", "final", 1.2, 3.74, 9.93},
                {"fangs_of_frost", "burst", 1.1, 4.49, 19.91},
        };
        for (final Object[] c : cases) {
            final SkillStages.Stage stage = SkillStages.get((String) c[0], (String) c[1]);
            assertNotNull(stage, c[0] + "." + c[1]);
            assertEquals((double) c[2], stage.effectiveness().at(1), 1e-9, c[0] + "." + c[1] + " 1 級");
            assertEquals((double) c[3], stage.effectiveness().at(20), 1e-9, c[0] + "." + c[1] + " 20 級");
            assertEquals((double) c[4], stage.effectiveness().at(40), 1e-9, c[0] + "." + c[1] + " 40 級");
        }
        assertEquals(4, SkillStages.get("tempest_bell", "impact").count().intAt(24), "24 級 4 口鐘");
        assertEquals(1, SkillStages.get("tempest_bell", "impact").count().intAt(1));
        assertEquals(20, SkillStages.get("sunfire_spear", "pulse").count().intAt(20));
        assertNull(SkillStages.get("fireball", "final"), "沒寫的技能沒有分段");
        assertTrue(SkillStages.of("fireball").isEmpty());
    }

    /** 這一段的傷害 = 原本這一下 × (這一段 ÷ 主段，同一級)，轉換、修飾器換成這一段的。 */
    @Test
    void profileRescalesToTheStageTable() {
        final SkillBuild plasma = build("plasma_blast", 20);
        final SkillProfile blast = SkillStages.profile(plasma, plasma.profile(), "explosion");
        assertEquals(12.25 / 33.89 - 1, more(blast, "爆炸"), 1e-9, "爆炸 1225% ÷ 彈 3389%");
        assertEquals(Map.of(DamageType.LIGHTNING, 1.0), blast.conversion(), "爆炸物理全部轉閃電");
        assertEquals(plasma.profile().conversion(), SkillStages.profile(plasma, plasma.profile(), "nothing").conversion(), "沒有這一段就原樣");
        final SkillBuild fangs = build("fangs_of_frost", 20);
        final SkillProfile burst = SkillStages.profile(fangs, fangs.profile(), "burst");
        final double freeze = burst.local().stream().filter(m -> m.stat().equals(StatKeys.FREEZE_BUILDUP) && "冰霜爆裂".equals(m.source()))
                .mapToDouble(Modifier::value).sum();
        // 20 級：刺擊本身更多 157%、爆裂再更多 61.1% → (1 + 1.57) × 1.611 ≈ 1 + 3.14（PoE2 爆裂 314%）
        assertEquals(4.14, (1 + 1.57) * (1 + freeze), 0.01);
        // 共用同一個比例：主段 1 級的效益跟 40 級不同，倍數也跟著不同
        final SkillBuild low = build("plasma_blast", 1);
        assertEquals(3.0 / 8.3, SkillStages.share(low.definition(), SkillStages.get("plasma_blast", "explosion"), 1), 1e-9);
    }

    /** 連段的那一下：第三下連打三次、終結打擊；攻速、距離、次數的記號。 */
    @Test
    void comboStagesApplyToTheRightStep() {
        assertEquals("third", SkillStages.comboKey("tempest_flurry", 3, 4));
        assertEquals("final", SkillStages.comboKey("tempest_flurry", 4, 4));
        assertNull(SkillStages.comboKey("tempest_flurry", 2, 4));
        assertEquals("final", SkillStages.comboKey("ice_strike", 3, 3));
        assertNull(SkillStages.comboKey("ice_strike", 2, 3));
        assertNull(SkillStages.comboKey("primal_strikes", 3, 3), "沒寫分段的連段技能照舊");
        final SkillBuild flurry = build("tempest_flurry", 20);
        final SkillBuild third = SkillStages.apply(flurry, "third");
        assertEquals(3, SkillStages.hits(third.profile()));
        assertEquals(1 / 0.65, SkillStages.timeScale(third.profile()), 1e-9, "攻速更少 35%");
        assertEquals(0.9 / 1.4 - 1, more(third.profile(), "第三下連打"), 1e-9);
        final SkillBuild finisher = SkillStages.apply(flurry, "final");
        assertEquals(1, SkillStages.hits(finisher.profile()));
        assertEquals(1 / 0.55, SkillStages.timeScale(finisher.profile()), 1e-9, "攻速更少 45%");
        assertEquals(0.2, SkillStages.mark(finisher.profile(), SkillStages.REACH), 1e-9);
        assertEquals(1, SkillStages.mark(finisher.profile(), SkillStages.IS + "final"), 1e-9);
        assertEquals(Map.of(DamageType.LIGHTNING, 1.0), finisher.profile().conversion());
        assertEquals(1.0, SkillStages.timeScale(flurry.profile()), 1e-9, "一般的那一下不變");
        assertEquals(1, SkillStages.hits(flurry.profile()));
        assertEquals(0.7, SkillStages.mark(SkillStages.apply(build("ice_strike", 1), "final").profile(), SkillStages.REACH), 1e-9);
    }

    /** 2026-10-28 照 PoE2：三連刺一次刺六下（最後一刺另外一段）；蛇嵐突要 1 顆怒星；說明列出每一段這一級的效益。 */
    @Test
    void dataAndTooltip() {
        assertEquals(5, build("rapid_thrust", 1).repeats(), "六刺");
        assertNotNull(config.chargeCost("serpent_fury"));
        assertEquals("frenzy", config.chargeCost("serpent_fury").charge());
        assertNotNull(config.skill(StageEffects.PALM_BOLT));
        assertTrue(config.skill(StageEffects.PALM_BOLT).hidden());
        final List<String> lines = SkillService.stageLines(build("tempest_flurry", 20));
        assertTrue(lines.stream().anyMatch(l -> l.contains("終結打擊：傷害效益 <white>421%")), lines.toString());
        assertTrue(lines.stream().anyMatch(l -> l.contains("第三下連打：傷害效益 <white>90%") && l.contains("3</white> 下")), lines.toString());
        assertTrue(SkillService.stageLines(build("fireball", 20)).isEmpty());
    }

    /** 寫錯的分段讀不進來。 */
    @Test
    void parseRejectsBadStages() {
        assertThrows(IllegalArgumentException.class, () -> SkillStages.parse(YamlNode.load("t", new StringReader("""
                stages:
                  final: { effectiveness: 0 }
                """))));
        assertThrows(IllegalArgumentException.class, () -> SkillStages.parse(YamlNode.load("t", new StringReader("""
                stages:
                  final: { effectiveness: 1, hits: 0 }
                """))));
        final Map<String, SkillStages.Stage> ok = SkillStages.parse(YamlNode.load("t", new StringReader("""
                stages:
                  blast: { name: 爆, effectiveness: [1, 2], radius: 1.5, convert: { fire: 1.0 } }
                """)));
        assertEquals(1.5, ok.get("blast").radius(1), 1e-9);
        assertEquals("爆", ok.get("blast").name());
        assertEquals(Map.of(DamageType.FIRE, 1.0), ok.get("blast").conversion());
        assertTrue(SkillStages.parse(YamlNode.load("t", new StringReader("name: 測試\n"))).isEmpty());
    }
}
