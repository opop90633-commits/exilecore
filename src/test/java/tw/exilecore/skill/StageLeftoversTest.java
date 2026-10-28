package tw.exilecore.skill;

import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import tw.exilecore.stats.DamageType;
import tw.exilecore.stats.Modifier;
import tw.exilecore.stats.SkillProfile;
import tw.exilecore.stats.StatKeys;

import java.io.IOException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.EnumSet;
import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** 2026-10-28：分段之後剩下的機制（StageLeftovers）——照 PoE2 0.5 RePoE 的逐級表與公式。 */
class StageLeftoversTest {

    static SkillConfig config;

    @BeforeAll
    static void load() throws IOException {
        config = new SkillConfig();
        config.load(Path.of("src/main/resources/data"));
    }

    private static SkillProfile profile(final String skill, final int level) {
        final SkillDefinition definition = config.skill(skill);
        assertNotNull(definition, skill);
        final List<Modifier> local = new ArrayList<>(definition.modifiers());
        for (final ScaledMod mod : config.levelModifiers(skill)) {
            local.add(mod.toModifier(level, 1.0, definition.name()));
        }
        return SkillProfiles.build(config, definition, level, Set.of(), local);
    }

    @Test
    void levelTablesMatchPoe2() {
        // 鐘的異常門檻／暈眩門檻 18 → 6357 → 148290
        assertEquals(18, StageAttacks.limit(profile("tempest_bell", 1), "bell_ailment", 0));
        assertEquals(6357, StageAttacks.limit(profile("tempest_bell", 20), "bell_poise", 0));
        assertEquals(148290, StageAttacks.limit(profile("tempest_bell", 40), "bell_ailment", 0));
        // 冰晶生命 36 → 12714 → 33734（霜壁、凍壁矢）；土牆 36 → 12714 → 45597
        assertEquals(36, StageAttacks.limit(profile("ice_wall", 1), "crystal_life", 0));
        assertEquals(12714, StageAttacks.limit(profile("glacial_bolt", 20), "crystal_life", 0));
        assertEquals(33734, StageAttacks.limit(profile("ice_wall", 40), "crystal_life", 0));
        assertEquals(45597, StageAttacks.limit(profile("shield_wall", 40), "wall_life", 0));
        // 寒魄掌門檻 32 → 12788 → 13640；血獵 1 → 381 → 2190；寒晶雷曝露等級上限 20 → 100
        assertEquals(32, StageAttacks.limit(profile("frost_palm", 1), "palm_threshold", 0));
        assertEquals(12788, StageAttacks.limit(profile("frost_palm", 20), "palm_threshold", 0));
        assertEquals(13640, StageAttacks.limit(profile("frost_palm", 40), "palm_threshold", 0));
        assertEquals(381, StageAttacks.limit(profile("blood_harvest", 20), "blood_per", 0));
        assertEquals(20, StageAttacks.limit(profile("frost_mine", 1), "exposure_level", 0));
        assertEquals(100, StageAttacks.limit(profile("frost_mine", 20), "exposure_level", 0));
        // 雷暴震波 240% → 869% → 3157%（引霆突一般突進 120% 的兩倍起）
        assertEquals(2.4, config.skill(StageLeftovers.TEMPEST_SHOCKWAVE).effectiveness().at(1), 1e-9);
        assertEquals(8.69, config.skill(StageLeftovers.TEMPEST_SHOCKWAVE).effectiveness().at(20), 1e-9);
        assertEquals(31.57, config.skill(StageLeftovers.TEMPEST_SHOCKWAVE).effectiveness().at(40), 1e-9);
        // 分段：熾陽之矛點燃地面 350% → 1429%、奔雷踏鋸齒地面爆開每級 40%、回旋鍛錘裂地 75% → 198% → 380%、地火柱開場爆發
        assertEquals(14.29, SkillStages.get("sunfire_spear", "ground").effectiveness().at(20), 1e-9);
        assertEquals(0.4, SkillStages.get("stampede", "explosion").effectiveness().at(33), 1e-9);
        assertEquals(1.98, SkillStages.get("forge_hammer", "fissure").effectiveness().at(20), 1e-9);
        assertEquals(5, SkillStages.get("forge_hammer", "fissure").count().intAt(20));
        assertEquals(1.313, SkillStages.get("volcano", "eruption").effectiveness().at(20), 1e-9);
        // 毒苞倒數快 1% 的門檻（瘴域、瘴苞雨同一張表）
        assertEquals(466, levelValue("toxic_domain", StageLeftovers.PUSTULE_FASTER_PER, 20), 1e-9);
        assertEquals(2178, levelValue("toxic_growth", StageLeftovers.PUSTULE_FASTER_PER, 40), 1e-9);
        assertEquals(969, levelValue("toxic_growth", StageLeftovers.PUSTULE_MORE_PER, 20), 1e-9);
    }

    private static double levelValue(final String skill, final String stat, final int level) {
        for (final ScaledMod mod : config.levelModifiers(skill)) {
            if (mod.stat().equals(stat)) {
                return mod.value().at(level);
            }
        }
        return 0;
    }

    @Test
    void yamlTimingsMatchPoe2() {
        // 崩城落彈 0.5 秒落地、0.6 秒後爆炸；冰脊裂主波 4.8 公尺；雷雲每 4 秒；寒魄掌不再打中就炸
        assertEquals(0.5, config.skill("siege_salvo").params().delay(), 1e-9);
        assertEquals(0.6, SkillStages.get("siege_salvo", "explosion").after(), 1e-9);
        assertEquals(tw.exilecore.core.Units.metres(4.8), config.skill("glacial_cascade").params().length().at(20), 1e-9); // 2026-10-29 照站長：1 公尺 = 1.5 格（Units），期望值照原作公尺換算
        assertEquals(4.0, config.skill("storm_orb").params().zone().interval(), 1e-9);
        for (final Modifier modifier : config.skill("frost_palm").modifiers()) {
            assertFalse(modifier.stat().startsWith("proc."), "寒魄掌改成照門檻碎，不再 proc.hit");
        }
        for (final Modifier modifier : config.skill("blood_harvest").modifiers()) {
            assertFalse(modifier.stat().startsWith("proc."), "血獵的血爆改成 StageLeftovers 照失血放");
        }
    }

    @Test
    void bellAilmentsAndKnockback() {
        assertEquals(1.0, StageLeftovers.bellArea(0), 1e-9);
        assertEquals(1.3, StageLeftovers.bellArea(0.1), 1e-9); // 每 0.1 公尺更多 30%
        assertEquals(2.2, StageLeftovers.bellArea(0.4), 1e-9);
        final List<Modifier> gains = StageLeftovers.bellGains(EnumSet.of(DamageType.FIRE, DamageType.COLD));
        assertEquals(2, gains.size());
        assertEquals(StatKeys.damageGain(DamageType.FIRE), gains.get(0).stat());
        assertEquals(30, gains.get(0).value(), 1e-9);
        assertTrue(StageLeftovers.bellGains(EnumSet.noneOf(DamageType.class)).isEmpty());
    }

    @Test
    void bloodHuntArea() {
        assertEquals(0, StageLeftovers.bloodArea(0, 381), 1e-9);
        assertEquals(0, StageLeftovers.bloodArea(380, 381), 1e-9);
        assertEquals(10, StageLeftovers.bloodArea(381, 381), 1e-9);
        assertEquals(30, StageLeftovers.bloodArea(1200, 381), 1e-9);
        assertEquals(500, StageLeftovers.bloodArea(1_000_000, 381), 1e-9); // 最多 500%
    }

    @Test
    void pustuleExpiresFaster() {
        assertEquals(1.0, StageLeftovers.pustuleRate(0, 466), 1e-9);
        // 存的毒 × 60 每 466 點快 1%：存 466 點 → 快 60% → 1.6 倍速
        assertEquals(1.6, StageLeftovers.pustuleRate(466, 466), 1e-9);
        assertEquals(1.0, StageLeftovers.pustuleRate(500, 0), 1e-9);
    }

    @Test
    void rainHitsOncePer012Seconds() {
        assertTrue(StageLeftovers.rainGate(null, 100));
        assertFalse(StageLeftovers.rainGate(100L, 101));
        assertFalse(StageLeftovers.rainGate(100L, 102)); // 0.10 秒
        assertTrue(StageLeftovers.rainGate(100L, 103)); // 0.15 秒
    }

    @Test
    void frostBombExposureCompounds() {
        assertEquals(0, StageLeftovers.bombExposure(0), 1e-9);
        assertEquals(20, StageLeftovers.bombExposure(1), 1e-9);
        assertEquals(22, StageLeftovers.bombExposure(2), 1e-9);
        assertEquals(50, StageLeftovers.bombExposure(16), 1e-9); // 4 秒引爆時（每 0.25 秒一下）剛好疊滿
        assertEquals(50, StageLeftovers.bombExposure(40), 1e-9);
    }

    @Test
    void conduitMorePerFivePercentShock() {
        assertEquals(0, StageLeftovers.conduitMore(0, 10), 1e-9);
        assertEquals(0, StageLeftovers.conduitMore(0.04, 10), 1e-9);
        assertEquals(0.4, StageLeftovers.conduitMore(0.20, 10), 1e-9); // 一般 20% 感電 → 更多 40%
        assertEquals(0.4, StageLeftovers.conduitMore(0.24, 10), 1e-9); // 無條件捨去
        assertEquals(1.0, StageLeftovers.conduitMore(0.50, 10), 1e-9);
    }

    @Test
    void volcanoStages() {
        assertEquals(0, StageLeftovers.volcanoMore(1), 1e-9);
        assertEquals(4.5, StageLeftovers.volcanoMore(4), 1e-9); // 每多一階更多 150%
        assertEquals(5, StageLeftovers.volcanoProjectiles(5, 1));
        assertEquals(17, StageLeftovers.volcanoProjectiles(5, 4)); // 每多一階多 4 顆
    }

    @Test
    void whirlwindStages() {
        assertEquals(1, StageLeftovers.whirlStage(0, false));
        assertEquals(4, StageLeftovers.whirlStage(0, true)); // 怒星：上限再多一階
        assertEquals(2, StageLeftovers.whirlStage(1, false));
        assertEquals(3, StageLeftovers.whirlStage(3, false)); // 最多 3 階
        assertEquals(4, StageLeftovers.whirlStage(4, false)); // 已經 4 階的不會掉
        assertEquals(0, StageLeftovers.whirlMore(1), 1e-9);
        assertEquals(3.0, StageLeftovers.whirlMore(3), 1e-9);
        assertEquals(tw.exilecore.core.Units.metres(2.7), StageLeftovers.whirlRadius(tw.exilecore.core.Units.metres(1.8), 4, 1.0), 1e-9); // 2026-10-29 照站長：1 公尺 = 1.5 格（Units），期望值照原作公尺換算
    }

    @Test
    void miscFormulas() {
        assertEquals(2.0, StageLeftovers.solarisRadius(2.0, 0), 1e-9);
        assertEquals(3.0, StageLeftovers.solarisRadius(2.0, 0.5), 1e-9);
        assertEquals(4.0, StageLeftovers.solarisRadius(2.0, 2), 1e-9);
        assertEquals(5, StageLeftovers.spiral(5, 0).size());
        assertEquals(12714 * 5 / 3.0, StageLeftovers.scaledLife(12714, 5, 3), 1e-9); // 厚晶：要打 5 下 → 生命 5/3 倍
        assertEquals(0, StageLeftovers.scaledLife(0, 5, 3), 1e-9);
        assertEquals(50, StageLeftovers.rivenAmount(1000), 1e-9); // 裂甲 5%
    }

    @Test
    void describesOwnStats() {
        assertNotNull(StageLeftovers.describe(StageLeftovers.MORE_PER_5_SHOCK, 10));
        assertNotNull(StageLeftovers.describe("limit.crystal_life", 36));
        assertNull(StageLeftovers.describe("damage", 1));
        assertTrue(ModifierText.describe(Modifier.flat(StageLeftovers.MORE_PER_5_SHOCK, 10, "x")).contains("感電效果"));
    }
}
