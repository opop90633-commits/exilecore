package tw.exilecore.skill;

import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import tw.exilecore.stats.Modifier;
import tw.exilecore.stats.StatKeys;

import java.io.IOException;
import java.nio.file.Path;
import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** 2026-11-01 照 PoE2 0.5：鎖敵衝刺、擊退距離、破勢、長方形範圍（TargetDash、KnockbackMath、BrokenStance、StormWave）。 */
class TargetDashKnockbackTest {

    static SkillConfig config;

    @BeforeAll
    static void load() throws IOException {
        config = new SkillConfig();
        config.load(Path.of("src/main/resources/data"));
        tw.exilecore.currency.GemQuality.load(Path.of("src/main/resources/data"));
    }

    @Test
    void dashRangesFollowPoe2() {
        assertEquals(tw.exilecore.core.Units.metres(7.5), TargetDash.range("cripple_purge"), 1e-9);
        assertEquals(tw.exilecore.core.Units.metres(6.5), TargetDash.range("thunder_draw_staff"), 1e-9);
        assertEquals(tw.exilecore.core.Units.metres(5.0), TargetDash.range("blood_hunt"), 1e-9);
        assertEquals(0, TargetDash.range("soul_sever_palm"), 1e-9, "斷魂掌自己有一套");
        assertEquals(0, TargetDash.range(null), 1e-9);
        assertTrue(TargetDash.locksLeap("leaping_staff_crash"));
        assertFalse(TargetDash.locksLeap("thunder_bound"));
        for (final String id : List.of("cripple_purge", "thunder_draw_staff", "blood_hunt")) {
            assertEquals(Mechanic.STRIKE, config.skill(id).mechanic(), id);
            assertEquals(1, config.skill(id).params().targets(), id + "：單一目標");
        }
    }

    @Test
    void lockPicksClosestToCrosshair() {
        // 看著 +x；三個候選：正前方遠一點、偏 20°、偏 60°（太偏）、超出距離
        final List<double[]> candidates = List.of(
                new double[] {6, 0, 0, 6},
                new double[] {3 * Math.cos(Math.toRadians(20)), 0, 3 * Math.sin(Math.toRadians(20)), 3},
                new double[] {2 * Math.cos(Math.toRadians(60)), 0, 2 * Math.sin(Math.toRadians(60)), 2},
                new double[] {9, 0, 0, 9});
        assertEquals(0, TargetDash.best(1, 0, 0, candidates, 7.5, TargetDash.LOCK_ANGLE), "夾角最小的");
        assertEquals(0, TargetDash.best(1, 0, 0, candidates.subList(1, 3), 7.5, TargetDash.LOCK_ANGLE), "60° 太偏，鎖 20° 的");
        assertEquals(-1, TargetDash.best(1, 0, 0, List.of(candidates.get(2), candidates.get(3)), 7.5, TargetDash.LOCK_ANGLE), "沒有可以鎖的");
        assertEquals(1, TargetDash.best(1, 0, 0, List.of(new double[] {5, 0, 0, 5}, new double[] {2, 0, 0, 2}), 7.5, 35), "一樣正就近的");
        assertTrue(TargetDash.needsDash(5, 4));
        assertFalse(TargetDash.needsDash(3, 4));
        assertEquals(90, TargetDash.angle(1, 0, 0, 0, 0, 1), 1e-9);
    }

    @Test
    void knockbackDistanceConversion() {
        assertEquals(KnockbackMath.BLOCKS_PER_VELOCITY, KnockbackMath.distance(1.0), 0.05, "速度 1 大約推 6.92 格");
        assertEquals(15, KnockbackMath.distance(KnockbackMath.velocityFor(15)), 0.2, "蛇嵐突 15 公尺");
        assertEquals(0, KnockbackMath.velocityFor(0), 1e-9);
        final double serpent = config.skill("snake_squall_thrust").modifiers().stream()
                .filter(m -> m.stat().equals(StatKeys.SKILL_KNOCKBACK)).mapToDouble(Modifier::value).sum();
        assertEquals(KnockbackMath.velocityFor(15), serpent, 0.01);
        assertEquals(5, KnockbackMath.distance(serpent * KnockbackMath.SERPENT_SHARE), 0.2, "風蛇 5 公尺");
        // 嵐棍橫掃：貼身 3 倍、邊緣 0.1 倍、中間線性
        assertEquals(3.0, KnockbackMath.windBlast(0, 6), 1e-9);
        assertEquals(0.1, KnockbackMath.windBlast(6, 6), 1e-9);
        assertEquals(1.55, KnockbackMath.windBlast(3, 6), 1e-9);
        assertEquals(0.1, KnockbackMath.windBlast(20, 6), 1e-9);
        assertTrue(SkillExecutor.hasModifier(config.skill("mist_staff_sweep"), StatKeys.SKILL_KNOCKBACK), "嵐棍橫掃要寫擊退才會推");
        assertEquals(KnockbackMath.DEFAULT_VELOCITY, config.skill("thunder_draw_staff").params().knockback(), 1e-9, "引雷棍擊退");
        assertEquals(0, config.skill("triple_stab").params().knockback(), 1e-9, "三連刺前五刺不擊退");
        final SkillStages.Stage last = SkillStages.get("triple_stab", SkillStages.LAST);
        assertNotNull(last);
        assertTrue(last.modifiers().stream().anyMatch(m -> m.toModifier(1, 1.0, "t").stat().equals(KnockbackMath.STAGE)), "最後一刺擊退");
    }

    @Test
    void brokenStanceStoresTenPercentForTenHits() {
        final BrokenStance stances = new BrokenStance();
        final UUID target = UUID.randomUUID();
        assertEquals(0, stances.consume(target, 0), 1e-9);
        stances.apply(target, 1000, 0, 0);
        assertEquals(100, stances.get(target).amount(), 1e-9, "強度 = 10%");
        for (int i = 0; i < 10; i++) {
            assertEquals(100, stances.consume(target, 10 + i), 1e-9, "第 " + (i + 1) + " 下");
        }
        assertEquals(0, stances.consume(target, 30), 1e-9, "10 下之後移除");
        assertNull(stances.get(target));
        // 品質 20% → +2 下；再給一次強度取大的
        stances.apply(target, 500, 2.0, 0);
        assertEquals(12, stances.get(target).hitsLeft());
        stances.apply(target, 200, 1.9, 5);
        assertEquals(50, stances.get(target).amount(), 1e-9, "取大的");
        assertEquals(11, stances.get(target).hitsLeft(), "小數捨去");
        assertEquals(0, stances.consume(target, 5 + BrokenStance.TICKS), 1e-9, "60 秒後沒了");
        assertEquals(10, BrokenStance.hitLimit(0));
        assertEquals(12, BrokenStance.hitLimit(2.0));
        assertFalse(SkillExecutor.hasFlag(config.skill("leaping_staff_crash"), tw.exilecore.stats.SupportParityKeys.FLAG_CANNOT_DAZE), "2026-11-01 照站長：技能本身的異常限制拿掉，可以眩暈");
        assertTrue(SkillService.qualityText(config.skill("leaping_staff_crash"), 20).contains("2 下"),
                SkillService.qualityText(config.skill("leaping_staff_crash"), 20));
    }

    @Test
    void stripShapes() {
        assertEquals(tw.exilecore.core.Units.metres(1.0), StormWave.halfWidth("thunder_surge", 0, 1), 1e-9);
        assertEquals(tw.exilecore.core.Units.metres(2.0), StormWave.halfWidth("thunder_surge", 1.0, 1), 1e-9, "品質 20% 半寬 +1 公尺");
        assertEquals(tw.exilecore.core.Units.metres(1.7) * 1.5, StormWave.halfWidth("snake_squall_thrust", 0, 1.5), 1e-9);
        assertEquals(0, StormWave.halfWidth("mist_staff_sweep", 0, 1), 1e-9);
        assertTrue(StormWave.inside(1, 0, 7.9, 0.9, 8, 1));
        assertFalse(StormWave.inside(1, 0, 8.2, 0, 8, 1), "超過長度");
        assertFalse(StormWave.inside(1, 0, 4, 1.2, 8, 1), "超過半寬");
        assertFalse(StormWave.inside(1, 0, -2, 0, 8, 1), "身後");
        assertTrue(StormWave.inside(0, 1, 0.5, 6, 8, 1), "往 +z");
        final SkillDefinition wave = config.skill("thunder_surge");
        assertEquals(Mechanic.CONE, wave.mechanic());
        assertEquals(tw.exilecore.core.Units.metres(8), wave.params().length().at(1), 1e-9);
        assertTrue(SkillService.qualityText(wave, 20).contains("半寬"), SkillService.qualityText(wave, 20));
    }
}
