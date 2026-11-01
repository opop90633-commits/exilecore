package tw.exilecore.skill;

import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import tw.exilecore.stats.DamagePacket;
import tw.exilecore.stats.DamageType;
import tw.exilecore.stats.Modifier;
import tw.exilecore.stats.SkillProfile;

import java.io.IOException;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** 2026-11-01 照 PoE2 0.5：滾雷珠、積雷雲、野旋風、抽荊、星火連珠、冥偶。 */
class SpellParitySkHTest {

    static SkillConfig config;

    @BeforeAll
    static void load() throws IOException {
        config = new SkillConfig();
        config.load(Path.of("src/main/resources/data"));
    }

    private static double flat(final List<Modifier> mods, final String stat) {
        return mods.stream().filter(m -> m.stat().equals(stat)).mapToDouble(Modifier::value).sum();
    }

    // ---- 滾雷珠 ----

    @Test
    void ballLightningZapsEvery200ms() {
        assertFalse(BallLightning.zapDue(0));
        assertFalse(BallLightning.zapDue(3));
        assertTrue(BallLightning.zapDue(4));
        assertTrue(BallLightning.zapDue(8));
    }

    @Test
    void fireInfusedBallSlowsDown25PercentPerSecond() {
        double speed = 0.2;
        for (int i = 0; i < 20; i++) {
            speed = BallLightning.nextSpeed(speed, 0.2, true);
        }
        assertEquals(0.15, speed, 1e-9, "一秒後 75%");
        assertEquals(0.2, BallLightning.nextSpeed(0.2, 0.2, false), 1e-12, "沒用掉火灌注不變慢");
        assertEquals(0.02, BallLightning.nextSpeed(0.02, 0.2, true), 1e-12, "最慢 10%");
    }

    @Test
    void ballLightningData() {
        final SkillDefinition ball = config.skill(BallLightning.ID);
        assertNull(ball.params().splash(), "雷珠本身不打人，不再有打中時的濺射");
        final List<Modifier> infused = config.resource(BallLightning.ID).infusedFor("fire");
        assertEquals(1, flat(infused, BallLightning.FIRE_INFUSED), 1e-9);
        assertEquals(350, flat(infused, GroundSkills.TRAIL_IGNITED), 1e-9);
        assertEquals(0, flat(infused, "damage.extra.fire"), 1e-9, "平衡等效拿掉了");
        assertEquals(Set.of("spell", "projectile", "lightning", "area", "chain", "duration"), ball.tags(), "標籤不動");
        final SkillDefinition blast = config.skill(BallLightning.EXPLOSION);
        assertTrue(blast.hidden());
        assertTrue(blast.damageByLevel());
        assertEquals(19, blast.damage()[0]);
        assertEquals(570, blast.damage()[38], "20 級");
        assertEquals(856, blast.damage()[39]);
        assertEquals(11468, blast.damage()[79], "40 級");
        assertEquals(7, flat(blast.modifiers(), "spell.base_crit"), 1e-9);
    }

    @Test
    void ownTableKeepsSupportsButNotTheParentsOwnNumbers() {
        final SkillProfile own = new SkillProfile("proc", Set.of("spell", "fire", "area"), 1.0, new DamagePacket().set(DamageType.FIRE, 570),
                new DamagePacket().set(DamageType.FIRE, 856), Map.of(), List.of(Modifier.flat("spell.base_crit", 7, "雷珠火爆")));
        final SkillProfile parent = new SkillProfile("ball", Set.of("spell", "lightning", "totem"), 1.0, new DamagePacket().set(DamageType.LIGHTNING, 6),
                new DamagePacket().set(DamageType.LIGHTNING, 105), Map.of(), List.of(
                        Modifier.flat("spell.base_crit", 9, "滾雷珠"),
                        Modifier.more("damage", 0.3, "某輔助"),
                        Modifier.flat(BallLightning.FIRE_INFUSED, 1, "灌注")));
        final SkillProfile out = OwnTableHits.compose(own, parent, "滾雷珠", Set.of("spell", "lightning"), Set.of(BallLightning.FIRE_INFUSED));
        assertEquals(570, out.spellDamage().get(DamageType.FIRE), 1e-9);
        assertEquals(0, out.spellDamage().get(DamageType.LIGHTNING), 1e-9);
        assertEquals(7, flat(out.local(), "spell.base_crit"), 1e-9, "暴擊照自己的，不加上原技能的 9");
        assertEquals(0.3, out.local().stream().filter(m -> m.stat().equals("damage")).mapToDouble(Modifier::value).sum(), 1e-9, "輔助照帶");
        assertEquals(0, flat(out.local(), BallLightning.FIRE_INFUSED), 1e-9, "灌注記號不帶");
        assertTrue(out.tags().contains("totem"), "圖騰放的照算圖騰");
        assertFalse(out.tags().contains("lightning"), "傷害種類標籤照自己的");
    }

    // ---- 星火連珠 ----

    @Test
    void emberFusilladeBeams() {
        assertEquals(4, EmberBeams.chains(0));
        assertEquals(5, EmberBeams.chains(1));
        assertTrue(EmberBeams.fusillade(false, true, true), "這次用掉電灌注");
        assertTrue(EmberBeams.fusillade(true, false, false), "整輪都帶");
        assertFalse(EmberBeams.fusillade(true, true, false), "新的一輪重算");
        final List<Modifier> infused = config.resource("starfire_bead_chain").infusedFor("lightning");
        assertEquals(1, flat(infused, EmberBeams.MARK), 1e-9);
        assertEquals(0, flat(infused, "damage.extra.lightning"), 1e-9, "平衡等效拿掉了");
        final SkillDefinition beam = config.skill(EmberBeams.BEAM);
        assertTrue(beam.hidden());
        assertEquals(1, beam.damage()[0]);
        assertEquals(10, beam.damage()[1]);
        assertEquals(16, beam.damage()[38], "20 級");
        assertEquals(309, beam.damage()[39]);
        assertEquals(4140, beam.damage()[79], "40 級");
    }

    // ---- 積雷雲 ----

    @Test
    void thunderstormStrikesEvery350PlusMinus150ms() {
        assertEquals(4, StormStrikes.nextGapTicks(0), "0.2 秒");
        assertEquals(10, StormStrikes.nextGapTicks(1), "0.5 秒");
        assertEquals(7, StormStrikes.nextGapTicks(0.5), "0.35 秒");
        for (double u = 0; u <= 1; u += 0.25) {
            final double[] p = StormStrikes.point(2.3, u, 0.3);
            assertTrue(Math.hypot(p[0], p[1]) <= 2.3 + 1e-9);
        }
        assertTrue(StormStrikes.canHit(null, 100));
        assertFalse(StormStrikes.canHit(95L, 100), "0.5 秒內只挨一道");
        assertTrue(StormStrikes.canHit(90L, 100));
        final SkillDefinition storm = config.skill(StormStrikes.ID);
        assertEquals(100, storm.params().zone().damage(), 1e-9, "每一道雷全額傷害");
        assertEquals(12, storm.params().zone().duration(), 1e-9);
    }

    // ---- 野旋風 ----

    @Test
    void tornadoSlowsMoreAtTheEdge() {
        assertEquals(0, PrimalTornado.edgeSlow(0, 3), 1e-9);
        assertEquals(0.15, PrimalTornado.edgeSlow(1.5, 3), 1e-9);
        assertEquals(0.30, PrimalTornado.edgeSlow(3, 3), 1e-9);
        assertEquals(0.30, PrimalTornado.edgeSlow(5, 3), 1e-9);
        final SkillProfile profile = new SkillProfile("wild_whirlwind", Set.of("spell", "physical", "area"), 1.0,
                new DamagePacket().set(DamageType.PHYSICAL, 146), new DamagePacket().set(DamageType.PHYSICAL, 146), Map.of(), List.of());
        assertEquals(146, PrimalTornado.baseDps(profile), 1e-9);
        assertEquals(292, PrimalTornado.scaledDps(null, profile, DamageType.FIRE, PrimalTornado.baseDps(profile) * PrimalTornado.ELEMENT_MULTIPLIER, 1), 1e-9,
                "元素是物理的兩倍");
        assertEquals(100, config.skill(PrimalTornado.ID).params().zone().damage(), 1e-9, "持續傷害不再打五成");
    }

    @Test
    void tornadoUsesPobTablesAndPull() {
        assertEquals(181 / 60.0, PrimalTornado.physicalDps(1), 1e-9);
        assertEquals(8765 / 60.0, PrimalTornado.physicalDps(20), 1e-9);
        assertEquals(17530 / 60.0, PrimalTornado.elementalDps(20), 1e-9, "元素自己的表（20 級每秒 292.2）");
        assertEquals(373826 / 60.0, PrimalTornado.elementalDps(40), 1e-9);
        assertEquals(527 / 60.0, PrimalTornado.elementalDps(2), 1e-9, "不是物理的兩倍（2 × 264 = 528）");
        assertEquals(0.36, PrimalTornado.pullStep(3, 0.45), 1e-9, "每 50 毫秒拉 12%");
        assertEquals(0, PrimalTornado.pullStep(0.4, 0.45), 1e-9, "中心 0.45 公尺內不拉");
        assertEquals(0.05, PrimalTornado.pullStep(0.5, 0.45), 1e-9, "不拉過頭");
        assertTrue(PrimalTornado.pullFailed(2.0, 2.0), "沒更靠近中心算拉不動");
        assertFalse(PrimalTornado.pullFailed(2.0, 1.8));
        assertEquals(20, PrimalTornado.PULL_FAILED_TICKS, "1 秒");
        assertEquals(GroundSkills.NOVA_CHILL, PrimalTornado.GROUND_STATUS, 1e-9, "照玩家冰緩地面的值");
        final SkillDefinition tornado = config.skill(PrimalTornado.ID);
        assertEquals(tw.exilecore.core.Units.metres(3.0), tornado.params().zone().radius().at(1), 1e-9, "3 公尺");
        assertEquals(1, tornado.params().zone().max(), "上限 1");
        assertEquals(0, tornado.params().zone().pull(), 1e-9, "吸引改在 tornadoPull 每 tick 做");
    }

    @Test
    void thunderstormWetLevelCap() {
        assertFalse(StormStrikes.aboveWetLevel(0, 99), "0 = 不限");
        assertTrue(StormStrikes.aboveWetLevel(20, 21));
        assertFalse(StormStrikes.aboveWetLevel(20, 20));
        final List<ScaledMod> mods = config.levelModifiers(StormStrikes.ID);
        final ScaledMod cap = mods.stream().filter(m -> m.toModifier(1, 1, "x").stat().equals(StormStrikes.WET_MAX_LEVEL)).findFirst().orElseThrow();
        assertEquals(20, cap.toModifier(1, 1, "x").value(), 1e-9);
        assertEquals(52, cap.toModifier(10, 1, "x").value(), 1e-9);
        assertEquals(78, cap.toModifier(17, 1, "x").value(), 1e-9);
        assertEquals(0, cap.toModifier(18, 1, "x").value(), 1e-9);
        assertEquals(40, tw.exilecore.stats.SupportExtraKeys.DRENCHED_TICKS, "離開後還留 2 秒（base_secondary_skill_effect_duration 2000）");
        assertEquals(50, tw.exilecore.stats.SupportExtraKeys.DRENCHED_SHOCK, 1e-9);
        assertEquals(25, tw.exilecore.stats.SupportExtraKeys.DRENCHED_FREEZE, 1e-9);
    }

    // ---- 抽荊 ----

    @Test
    void thrashingVinesGrowEveryHalfCast() {
        assertEquals(5, ThrashingVines.growthTicks(10), "施法 0.5 秒 → 每 0.25 秒一條");
        assertEquals(1, ThrashingVines.growthTicks(1));
        assertEquals(0, ThrashingVines.distanceToSegment(1, 0, 0, 0, 2, 0), 1e-9);
        assertEquals(1, ThrashingVines.distanceToSegment(1, 1, 0, 0, 2, 0), 1e-9);
        assertEquals(1, ThrashingVines.distanceToSegment(3, 0, 0, 0, 2, 0), 1e-9, "超過尾端量到端點");
        final double[] back = ThrashingVines.rootBehind(2.5, 0);
        assertEquals(1.25, back[0], 1e-9, "藤從目標往後退半條，穿過目標");
        final SkillDefinition vines = config.skill(ThrashingVines.ID);
        assertEquals(Mechanic.ZONE, vines.mechanic());
        assertEquals(tw.exilecore.core.Units.metres(4.0), vines.params().zone().radius().at(1), 1e-9, "生長區 4 公尺");
        assertEquals(8, vines.params().zone().duration(), 1e-9);
        assertEquals(1, vines.params().zone().max());
        assertNotNull(Overgrowth.effect(ThrashingVines.ID));
        assertTrue(SkillCascade.cascadable(vines), "原作 Cascadable");
    }

    // ---- 冥偶 ----

    @Test
    void darkEffigyPlacedUpTo12Metres() {
        assertEquals(tw.exilecore.core.Units.metres(12), TotemPlacement.range("nether_puppet"), 1e-9);
        assertEquals(12, config.skill("nether_puppet").deploy().range(), 1e-9, "射 12 公尺內");
    }
}
