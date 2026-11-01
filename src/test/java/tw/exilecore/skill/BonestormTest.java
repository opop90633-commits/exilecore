package tw.exilecore.skill;

import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import tw.exilecore.currency.GemQuality;
import tw.exilecore.stats.KeystoneOriginKeys;
import tw.exilecore.stats.Modifier;
import tw.exilecore.stats.ModType;
import tw.exilecore.stats.StatSheet;

import java.io.IOException;
import java.nio.file.Path;
import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 2026-11-01 照 PoE2 Bonestorm（碎骨飛矢；Path of Building BonestormPlayer）：累積型引導（每 0.125 秒凝聚投射物數量支、最多 20 支、
 * 最短 0.3 秒）、骨刺打中炸開 0.5 格（爆炸自己的表）、一定穿刺且穿刺效果更多 200%、放出時用掉全部銳星（+0.7 格、每顆更多 40% 範圍）、
 * 逐 0.2 秒扣魔力。
 */
class BonestormTest {

    static final Path DATA = Path.of("src/main/resources/data");
    static SkillConfig config;

    @BeforeAll
    static void load() throws IOException {
        config = new SkillConfig();
        config.load(DATA);
        GemQuality.load(DATA);
    }

    @Test
    void channelAccumulatesUpToTwentySpikes() {
        final ChargeSpec spec = config.charge(Bonestorm.ID);
        assertNotNull(spec);
        assertTrue(spec.accumulate());
        assertEquals(20, spec.stages(), "base_number_of_allowed_bone_storm_projectiles 20（這次之前 19 層 + 基礎 2 支）");
        assertEquals(0.125, spec.stageTime(), 1e-9);
        assertEquals(6, spec.channel().minTicks(), "base_minimum_channel_time_ms 300");
        assertTrue(spec.drain());
        // 施法速度 1 倍：一段 2.5 tick，第 k 段結束時手上 k 支
        assertEquals(2.5, spec.stageTicks(1.0), 1e-9);
        assertEquals(1, spec.heldCount(0, 1.0, 1));
        assertEquals(2, spec.heldCount(6, 1.0, 1), "放開最快也撐到 0.3 秒：2 支");
        assertEquals(16, spec.heldCount(40, 1.0, 1));
        assertEquals(19, spec.heldCount(49, 1.0, 1));
        assertEquals(20, spec.heldCount(50, 1.0, 1));
        assertEquals(20, spec.heldCount(500, 1.0, 1), "最多 20 支");
        assertEquals(50, spec.fullTicks(1.0, 1), 1e-9, "20 段 × 0.125 秒 = 2.5 秒");
        // 多射的投射物加在每一段，上限不變（Path of Building：channelTimeMultiplier = ceil(段數 ÷ 投射物數量)）
        assertEquals(4, spec.heldCount(5, 1.0, 2));
        assertEquals(20, spec.heldCount(25, 1.0, 2));
        assertEquals(25, spec.fullTicks(1.0, 2), 1e-9);
        assertEquals(17.5, spec.fullTicks(1.0, 3), 1e-9, "ceil(20 ÷ 3) = 7 段");
        assertEquals(20, spec.heldCount(18, 1.0, 3));
        // 施法速度快一倍：一段 1.25 tick，但一段最短 2 tick（ChargeSpec.stageTicks 的下限）
        assertEquals(2.0, spec.stageTicks(2.0), 1e-9);
        assertEquals(20, spec.heldCount(40, 2.0, 1));
        assertEquals(6, spec.heldCount(13, 1.2, 1), "施法速度 1.2 倍：一段 2.083 tick");
    }

    @Test
    void releaseFiresTheHeldSpikes() {
        final ChargeSpec spec = config.charge(Bonestorm.ID);
        final SkillBuild build = SkillBuild.of(config, config.skill(Bonestorm.ID), 20, 90, false, List.of(), Set.of(), new StatSheet());
        assertEquals(1, build.projectiles(), "base_number_of_projectiles 1（這次之前 2）");
        assertEquals(0.5, build.explode(), 1e-9, "爆炸 0.5 公尺 = 0.5 格");
        assertEquals(16, Signatures.released(build, spec, 16, false, new StatSheet(), 2.0).projectiles());
        assertEquals(20, Signatures.released(build, spec, 20, false, new StatSheet(), 2.5).projectiles());
    }

    @Test
    void explosionHasItsOwnTable() {
        final SkillStages.Stage explosion = SkillStages.get(Bonestorm.ID, ProjectileEngine.EXPLOSION);
        assertNotNull(explosion);
        final SkillDefinition definition = config.skill(Bonestorm.ID);
        // 20 級：骨刺 116–175、爆炸 89–134 → (89 + 134) ÷ (116 + 175)
        assertEquals((89.0 + 134) / (116 + 175), SkillStages.share(definition, explosion, 20), 0.001);
        assertEquals((3.0 + 5) / (4 + 7), SkillStages.share(definition, explosion, 1), 0.001);
        assertEquals((937.0 + 1405) / (1220 + 1831), SkillStages.share(definition, explosion, 40), 0.001);
    }

    @Test
    void impalesWithTwoHundredPercentMoreMagnitude() {
        double chance = 0;
        double more = 1;
        boolean blastStruck = false;
        for (final Modifier modifier : config.skill(Bonestorm.ID).modifiers()) {
            if (modifier.stat().equals(KeystoneOriginKeys.IMPALE_SPELL_CHANCE)) {
                chance += modifier.value();
            }
            if (modifier.stat().equals(KeystoneOriginKeys.IMPALE_MAGNITUDE) && modifier.type() == ModType.MORE) {
                more *= 1 + modifier.value();
            }
            if (modifier.stat().equals(ProjectileEngine.FLAG_BLAST_STRUCK)) {
                blastStruck = modifier.value() > 0;
            }
        }
        assertEquals(100, chance, 1e-9);
        assertEquals(3, more, 1e-9);
        assertTrue(blastStruck, "爆炸也打骨刺打中的那隻");
        // 存下減免前物理傷害的 30% × (1 + 增加) × 更多
        assertEquals(90, KeystoneOriginKeys.impaleAmount(100, 0, 3), 1e-9);
        assertEquals(30, KeystoneOriginKeys.impaleAmount(100, 0), 1e-9);
        assertEquals(135, KeystoneOriginKeys.impaleAmount(100, 0.5, 3), 1e-9);
    }

    @Test
    void powerChargesMakeBiggerExplosions() {
        assertEquals(1.0, Bonestorm.radiusScale(0), 1e-9);
        // (0.5 + 0.7) ÷ 0.5 × √(1 + 0.4 × 顆數)
        assertEquals(2.4 * Math.sqrt(1.4), Bonestorm.radiusScale(1), 1e-9);
        assertEquals(2.4 * Math.sqrt(2.2), Bonestorm.radiusScale(3), 1e-9);
        assertEquals(0.5 * 2.4 * Math.sqrt(2.2), 0.5 * Bonestorm.radiusScale(3), 1e-9, "3 顆：約 1.78 格");
    }

    @Test
    void manaDrainsInSlices() {
        final double[] owed = new double[1];
        assertEquals(0, ChargeSpec.drainSlice(19, 61, 0, owed), "第一秒開始時已經扣過");
        int paid = 0;
        double carry = 0;
        for (long held = 20; held < 40; held++) {
            paid += ChargeSpec.drainSlice(held, 61, carry, owed);
            carry = owed[0];
        }
        assertEquals(61, paid, "一整秒加起來還是每秒消耗");
        assertEquals(12, ChargeSpec.drainSlice(20, 61, 0, owed));
        assertEquals(0.2, owed[0], 1e-9);
        assertEquals(0, ChargeSpec.drainSlice(21, 61, 0.2, owed));
    }

    @Test
    void qualityFollowsRef() {
        final SkillDefinition definition = config.skill(Bonestorm.ID);
        double castSpeed = 0;
        for (final Modifier modifier : SkillService.qualityModifiers(definition, 20)) {
            if (modifier.stat().equals(tw.exilecore.stats.StatKeys.CAST_SPEED) && modifier.type() == ModType.MORE) {
                castSpeed += modifier.value();
            }
        }
        assertEquals(0.10, castSpeed, 1e-9, "active_skill_cast_speed_+%_final 0.5 × 20");
        double keep = 0;
        for (final Modifier modifier : GemQuality.advancedModifiers(Bonestorm.ID, 20, "q")) {
            if (modifier.stat().equals(tw.exilecore.charge.ChargeService.SUP_KEEP)) {
                keep += modifier.value();
            }
        }
        assertEquals(20, keep, 1e-9, "charge_skip_consume_chance_% 1 × 20（高階奇術）");
    }

    @Test
    void channelWordIsChannel() {
        assertEquals("引導", Signatures.chargeWord(config.skill(Bonestorm.ID)));
        assertEquals("引導", Signatures.chargeWord(config.skill("flame_core_detonation")));
    }
}
