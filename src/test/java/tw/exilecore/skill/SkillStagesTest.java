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
    void stageTablesFollowRef() {
        final Object[][] cases = {
                {"gale_combo", "final", 1.35, 4.21, 11.17},
                {"gale_combo", "third", 0.9, 0.9, 0.9},
                {"frost_shockwave", "final", 1.3, 4.05, 10.76},
                {"startling_thunder_bell", "impact", 1.0, 4.08, 18.1},
                {"hurricane_spear", "whirlwind", 0.6, 2.17, 7.89},
                {"searing_slurry_cannon", "explosion", 3.0, 12.25, 54.29},
                {"fortress_crumble_shell", "explosion", 1.5, 5.43, 19.73},
                {"thunderfall_spear", "infused", 0.25, 0.91, 3.29},
                {"triple_stab", "last", 1.0, 3.12, 8.28},
                {"triple_stab", "explosion", 1.75, 5.46, 14.48},
                {"rushing_thunder_stomp", "slam", 1.2, 3.74, 9.93},
                {"returning_smith_hammer", "recall", 0.8, 3.27, 14.48},
                {"pulse_quake_palm", "projectile", 0.5, 2.04, 9.05},
                {"armor_punch_slug", "vs_armour_broken", 2.2, 5.8, 11.14},
                {"snake_squall_thrust", "serpent", 0.55, 1.99, 7.24},
                {"facet_light_rift", "cold", 3.5, 14.29, 63.34},
                {"facet_light_rift", "lightning", 1.4, 5.72, 25.34},
                {"blazing_sun_spear", "pulse", 0.27, 1.1, 4.89},
                {"ice_ridge_rift", "final", 1.2, 3.74, 9.93},
                {"frost_tooth_stab", "burst", 1.1, 4.49, 19.91},
                {"startle_thunder_chop", "thunder", 1.4, 4.37, 11.59},   // 2026-10-28：投射物那一段沒有自己的表，跟猛擊同一張
        };
        for (final Object[] c : cases) {
            final SkillStages.Stage stage = SkillStages.get((String) c[0], (String) c[1]);
            assertNotNull(stage, c[0] + "." + c[1]);
            assertEquals((double) c[2], stage.effectiveness().at(1), 1e-9, c[0] + "." + c[1] + " 1 級");
            assertEquals((double) c[3], stage.effectiveness().at(20), 1e-9, c[0] + "." + c[1] + " 20 級");
            assertEquals((double) c[4], stage.effectiveness().at(40), 1e-9, c[0] + "." + c[1] + " 40 級");
        }
        assertEquals(4, SkillStages.get("startling_thunder_bell", "impact").count().intAt(24), "24 級 4 口鐘");
        assertEquals(1, SkillStages.get("startling_thunder_bell", "impact").count().intAt(1));
        assertEquals(20, SkillStages.get("blazing_sun_spear", "pulse").count().intAt(20));
        assertNull(SkillStages.get("blazing_star_shell", "final"), "沒寫的技能沒有分段");
        assertTrue(SkillStages.of("blazing_star_shell").isEmpty());
    }

    /** 這一段的傷害 = 原本這一下 × (這一段 ÷ 主段，同一級)，轉換、修飾器換成這一段的。 */
    @Test
    void profileRescalesToTheStageTable() {
        final SkillBuild plasma = build("searing_slurry_cannon", 20);
        final SkillProfile blast = SkillStages.profile(plasma, plasma.profile(), "explosion");
        assertEquals(12.25 / 33.89 - 1, more(blast, "爆炸"), 1e-9, "爆炸 1225% ÷ 彈 3389%");
        assertEquals(Map.of(DamageType.LIGHTNING, 1.0), blast.conversion(), "爆炸物理全部轉閃電");
        assertEquals(plasma.profile().conversion(), SkillStages.profile(plasma, plasma.profile(), "nothing").conversion(), "沒有這一段就原樣");
        final SkillBuild fangs = build("frost_tooth_stab", 20);
        final SkillProfile burst = SkillStages.profile(fangs, fangs.profile(), "burst");
        final double freeze = burst.local().stream().filter(m -> m.stat().equals(StatKeys.FREEZE_BUILDUP) && "冰霜爆裂".equals(m.source()))
                .mapToDouble(Modifier::value).sum();
        // 20 級：刺擊本身更多 157%、爆裂再更多 61.1% → (1 + 1.57) × 1.611 ≈ 1 + 3.14（PoE2 爆裂 314%）
        assertEquals(4.14, (1 + 1.57) * (1 + freeze), 0.01);
        // 共用同一個比例：主段 1 級的效益跟 40 級不同，倍數也跟著不同
        final SkillBuild low = build("searing_slurry_cannon", 1);
        assertEquals(3.0 / 8.3, SkillStages.share(low.definition(), SkillStages.get("searing_slurry_cannon", "explosion"), 1), 1e-9);
    }

    /** 連段的那一下：第三下連打三次、終結打擊；攻速、距離、次數的記號。 */
    @Test
    void comboStagesApplyToTheRightStep() {
        assertEquals("third", SkillStages.comboKey("gale_combo", 3, 4));
        assertEquals("final", SkillStages.comboKey("gale_combo", 4, 4));
        assertNull(SkillStages.comboKey("gale_combo", 2, 4));
        assertEquals("final", SkillStages.comboKey("frost_shockwave", 3, 3));
        assertNull(SkillStages.comboKey("frost_shockwave", 2, 3));
        assertNull(SkillStages.comboKey("thunderclap_stab", 3, 3), "沒寫分段的連段技能照舊");
        final SkillBuild flurry = build("gale_combo", 20);
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
        assertEquals(0.7, SkillStages.mark(SkillStages.apply(build("frost_shockwave", 1), "final").profile(), SkillStages.REACH), 1e-9);
    }

    /** 2026-10-28 照 PoE2：三連刺一次刺六下（最後一刺另外一段）；蛇嵐突要 1 顆怒星；說明列出每一段這一級的效益。 */
    @Test
    void dataAndTooltip() {
        assertEquals(5, build("triple_stab", 1).repeats(), "六刺");
        assertNotNull(config.chargeCost("snake_squall_thrust"));
        assertEquals("frenzy", config.chargeCost("snake_squall_thrust").charge());
        assertNotNull(config.skill(StageEffects.PALM_BOLT));
        assertTrue(config.skill(StageEffects.PALM_BOLT).hidden());
        final List<String> lines = SkillService.stageLines(build("gale_combo", 20));
        assertTrue(lines.stream().anyMatch(l -> l.contains("終結打擊：傷害效益 <white>421%")), lines.toString());
        assertTrue(lines.stream().anyMatch(l -> l.contains("第三下連打：傷害效益 <white>90%") && l.contains("3</white> 下")), lines.toString());
        assertTrue(SkillService.stageLines(build("blazing_star_shell", 20)).isEmpty());
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
        assertEquals(tw.exilecore.core.Units.metres(1.5), ok.get("blast").radius(1), 1e-9); // 2026-10-29 照站長：1 公尺 = 1.5 格（Units），期望值照原作公尺換算
        assertEquals("爆", ok.get("blast").name());
        assertEquals(Map.of(DamageType.FIRE, 1.0), ok.get("blast").conversion());
        assertTrue(SkillStages.parse(YamlNode.load("t", new StringReader("name: 測試\n"))).isEmpty());
    }
}
