package tw.exilecore.skill;

import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import tw.exilecore.stats.DamageType;
import tw.exilecore.stats.Modifier;
import tw.exilecore.stats.ModType;
import tw.exilecore.stats.SkillProfile;
import tw.exilecore.stats.StatKeys;

import java.io.IOException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** 2026-10-28：攻擊技能的第二段（StageAttacks）——照 PoE2 0.5 RePoE 的逐級表，1／20／40 級的值與純計算。 */
class StageAttacksTest {

    static SkillConfig config;

    @BeforeAll
    static void load() throws IOException {
        config = new SkillConfig();
        config.load(Path.of("src/main/resources/data"));
    }

    /** 技能在這一級的 profile（含 level_modifiers，跟 SkillBuild.of 一樣疊進 local）。 */
    private static SkillProfile profile(final String skill, final int level) {
        final SkillDefinition definition = config.skill(skill);
        assertNotNull(definition, skill);
        final List<Modifier> local = new ArrayList<>(definition.modifiers());
        for (final ScaledMod mod : config.levelModifiers(skill)) {
            local.add(mod.toModifier(level, 1.0, definition.name()));
        }
        return SkillProfiles.build(config, definition, level, Set.of(), local);
    }

    private static double stageEff(final String skill, final String stage, final int level) {
        return StageAttacks.stage(profile(skill, level), stage).effectiveness();
    }

    @Test
    void stageTablesMatchPoe2() {
        // 1／20／40 級（RePoE damage_multiplier ÷ 100）
        assertEquals(1.10, stageEff("bear_slam", "enraged", 1), 1e-9);
        assertEquals(3.98, stageEff("bear_slam", "enraged", 20), 1e-9);
        assertEquals(14.47, stageEff("bear_slam", "enraged", 40), 1e-9);
        assertEquals(0.35, stageEff("wolf_shred", "third", 1), 1e-9);
        assertEquals(2.90, stageEff("wolf_shred", "third", 40), 1e-9);
        assertEquals(1.87, stageEff("wolf_shred", "final", 20), 1e-9);
        assertEquals(4.05, stageEff("wyvern_rend", "empowered", 20), 1e-9);
        assertEquals(3.12, stageEff("oil_barrage", "empowered", 20), 1e-9);
        assertEquals(10.14, stageEff("gathering_storm", "perfect", 20), 1e-9);
        assertEquals(36.84, stageEff("gathering_storm", "perfect", 40), 1e-9);
        assertEquals(3.12, stageEff("primal_strikes", "final", 20), 1e-9);
        assertEquals(2.54, stageEff("shield_rush", "collision", 20), 1e-9);
        assertEquals(1.45, stageEff("explosive_concoction", "second", 20), 1e-9);
        assertEquals(0.70, stageEff("magnetic_salvo", "explosion", 1), 1e-9);
        assertEquals(12.67, stageEff("magnetic_salvo", "explosion", 40), 1e-9);
        assertEquals(27.15, stageEff("gas_grenade", "explosion", 40), 1e-9);
        // 沒有這一段：原封不動
        final SkillProfile plain = profile("bear_slam", 20);
        assertSame(plain, StageAttacks.stage(plain, "final"));
        // 隱藏技能自己的表
        assertEquals(4.71, config.skill("proc_cross_shockwave").effectiveness().at(20), 1e-9);
        assertEquals(6.70, config.skill("proc_glacial_lance_burst").effectiveness().at(20), 1e-9);
        assertEquals(4.35, config.skill("proc_primal_spirit").effectiveness().at(20), 1e-9);
        assertEquals(1.19, config.skill("proc_domain_pustule").effectiveness().at(20), 1e-9);
        assertEquals(2.45, config.skill("proc_glacial_bolt_wall").effectiveness().at(20), 1e-9);
        assertEquals(14.29, config.skill("proc_frag_shatter").effectiveness().at(20), 1e-9);
        assertEquals(63.34, config.skill("proc_frag_shatter").effectiveness().at(40), 1e-9);
    }

    @Test
    void limitsPerLevel() {
        assertEquals(2, StageAttacks.limit(profile("earthquake", 1), "jagged", 0));
        assertEquals(4, StageAttacks.limit(profile("earthquake", 20), "jagged", 0));
        assertEquals(8, StageAttacks.limit(profile("earthquake", 40), "jagged", 0));
        // 2026-10-28：驚雷之鐘的口數改由 skills.yml 的 stages.impact.count 給（SkillDeployables 讀；見 SkillStagesTest），不再用 limit.deploy
        assertEquals(1, StageAttacks.limit(profile("bramble_arrow", 1), "ground", 9));
        assertEquals(3, StageAttacks.limit(profile("bramble_arrow", 20), "ground", 9));
        assertEquals(30, StageAttacks.limit(profile("rain_of_arrows", 1), "arrows", 0));
        assertEquals(39, StageAttacks.limit(profile("rain_of_arrows", 20), "arrows", 0));
        assertEquals(49, StageAttacks.limit(profile("rain_of_arrows", 40), "arrows", 0));
        assertEquals(7, StageAttacks.limit(profile("bear_slam", 20), "nothing", 7)); // 沒寫照 fallback
        assertEquals(20, StageAttacks.limit(profile("primal_strikes", 20), "final_reach", 0));
    }

    @Test
    void comboStages() {
        // 疾爪：第 3 擊 Fast Strikes、第 4 擊終結；驚雷刺：第 3 擊終結
        assertNull(StageAttacks.comboStage("wolf_shred", 1, 4));
        assertNull(StageAttacks.comboStage("wolf_shred", 2, 4));
        assertEquals("third", StageAttacks.comboStage("wolf_shred", 3, 4));
        assertEquals("final", StageAttacks.comboStage("wolf_shred", 4, 4));
        assertNull(StageAttacks.comboStage("primal_strikes", 2, 3));
        assertEquals("final", StageAttacks.comboStage("primal_strikes", 3, 3));
        assertNull(StageAttacks.comboStage("bear_slam", 3, 0));
    }

    @Test
    void stageMarkerAndExtraModifiers() {
        final SkillProfile base = profile("wolf_shred", 20);
        final SkillProfile third = StageAttacks.stage(base, "third", List.of(Modifier.more(StatKeys.ATTACK_SPEED, 0.35, "第三擊")));
        assertEquals(1.09, third.effectiveness(), 1e-9);
        assertTrue(StageAttacks.is(third, "third"));
        assertFalse(StageAttacks.is(base, "third"));
        assertTrue(third.local().stream().anyMatch(m -> m.stat().equals(StatKeys.ATTACK_SPEED) && m.value() == 0.35));
    }

    @Test
    void rainArrowCount() {
        assertEquals(39, StageAttacks.rainArrows(39, 0, false));
        assertEquals(41, StageAttacks.rainArrows(39, 2, false));
        assertEquals(97, StageAttacks.rainArrows(39, 0, true)); // 更多 150%：39 × 2.5 = 97.5 → 97
        assertEquals(0, StageAttacks.rainDelay(0));
        assertEquals(2, StageAttacks.rainDelay(1)); // 0.08 秒 ≈ 1.6 tick
        assertEquals(16, StageAttacks.rainDelay(10));
    }

    @Test
    void jaggedGroundLimit() {
        final List<double[]> active = new ArrayList<>();
        assertTrue(StageAttacks.jaggedAllowed(active, 0, 0, 2));
        active.add(new double[] {0, 0, 1.8});
        assertFalse(StageAttacks.jaggedAllowed(active, 1, 0, 2), "疊在現有的上面不產生");
        assertTrue(StageAttacks.jaggedAllowed(active, 5, 0, 2));
        active.add(new double[] {5, 0, 1.8});
        assertFalse(StageAttacks.jaggedAllowed(active, 10, 0, 2), "滿了不產生");
        assertTrue(StageAttacks.jaggedAllowed(active, 10, 0, 0), "沒有上限的技能照舊");
    }

    @Test
    void pustuleStoredPoison() {
        // 20 級：每 969 點更多 1%（最多 300%）、每 9691 點半徑 +0.1 公尺（最多 +1.5）
        assertEquals(0, StageAttacks.pustuleMore(500, 969), 1e-9);
        assertEquals(10, StageAttacks.pustuleMore(9690, 969), 1e-9);
        assertEquals(300, StageAttacks.pustuleMore(1e9, 969), 1e-9);
        assertEquals(0.3, StageAttacks.pustuleRadius(30000, 9691), 1e-9);
        assertEquals(1.5, StageAttacks.pustuleRadius(1e9, 9691), 1e-9);
        assertEquals(0, StageAttacks.pustuleMore(1000, 0), 1e-9);
    }

    @Test
    void shieldDamage() {
        // 盾技能不看主手武器：副手盾的逐級基礎傷害（鐵壁衝撞 20 級 85–128）
        final SkillDefinition rush = config.skill("shield_rush");
        assertTrue(rush.weaponless());
        final double[] base = SkillProfiles.spellDamage(config, rush, 20);
        assertEquals(85, base[0], 1e-9);
        assertEquals(128, base[1], 1e-9);
        assertTrue(config.skill("resonating_shield").weaponless());
        assertTrue(config.skill("shield_wall").weaponless());
        assertTrue(config.skill("proc_magma_spray").weaponless());
        // 每 15 點護甲 3～5；熔岩噴射護甲、閃避各 5～7 火焰
        final List<Modifier> added = StageAttacks.shieldAdded(StageAttacks.SHIELD_SKILLS.get("shield_rush"), 300, 999);
        assertEquals(60, added.get(0).value(), 1e-9);
        assertEquals(100, added.get(1).value(), 1e-9);
        final List<Modifier> magma = StageAttacks.shieldAdded(StageAttacks.SHIELD_SKILLS.get("proc_magma_spray"), 150, 150);
        assertEquals(100, magma.get(0).value(), 1e-9);
        assertEquals(140, magma.get(1).value(), 1e-9);
        assertTrue(magma.get(0).tags().containsAll(DamageType.FIRE.tags()));
        assertTrue(StageAttacks.shieldAdded(StageAttacks.SHIELD_SKILLS.get("shield_rush"), 0, 0).isEmpty());
    }

    @Test
    void shieldCollisionPerLevel() {
        // 撞上的那一下：自己的表（1 級 70%／30% = 2.33 倍、20 級 254／94 = 2.70、40 級 921／248 = 3.71），護甲附加 3～5 → 6～8
        for (final int level : new int[] {1, 20}) {
            final SkillProfile area = profile("shield_rush", level).withLocal(
                    StageAttacks.shieldAdded(StageAttacks.SHIELD_SKILLS.get("shield_rush"), 150, 0));
            final SkillProfile hit = StageAttacks.collision(area, 2.7);
            assertEquals(level == 1 ? 0.70 : 2.54, hit.effectiveness(), 1e-9);
            double min = 0;
            double max = 0;
            for (final Modifier modifier : hit.local()) {
                if (StageAttacks.SHIELD_SOURCE.equals(modifier.source())) {
                    if (modifier.stat().equals(StatKeys.DAMAGE_ADDED_MIN)) {
                        min += modifier.value();
                    } else if (modifier.stat().equals(StatKeys.DAMAGE_ADDED_MAX)) {
                        max += modifier.value();
                    }
                }
            }
            assertEquals(60, min, 1e-9);
            assertEquals(80, max, 1e-9);
        }
        assertEquals(2.70, 2.54 / 0.94, 0.01);
    }

    @Test
    void gasBlastUsesOwnTable() {
        final SkillProfile arrow = profile("caustic_arrow", 20);
        final SkillProfile zone = arrow.scaled(2.33, "地面效果").withLocal(List.of(Modifier.flat("flag.ground_poison_only", 1, "x")));
        final SkillProfile blast = StageAttacks.gasBlast(zone, 6, "毒霧引爆");
        assertEquals(2.81, blast.effectiveness(), 1e-9);
        assertEquals(1.0, blast.conversion().get(DamageType.FIRE), 1e-9);
        assertTrue(blast.local().stream().noneMatch(m -> "地面效果".equals(m.source())));
        assertTrue(blast.local().stream().noneMatch(m -> m.stat().equals("flag.ground_poison_only")));
        // 沒有表的照舊乘
        final SkillProfile other = new SkillProfile("x", Set.of(), 1.0, null);
        assertTrue(StageAttacks.gasBlast(other, 6, "毒霧引爆").local().stream()
                .anyMatch(m -> m.stat().equals(StatKeys.DAMAGE) && m.type() == ModType.MORE && Math.abs(m.value() - 5) < 1e-9));
    }

    @Test
    void perfectUsesTableWhenPresent() {
        assertEquals(10.14, StageAttacks.perfect(profile("gathering_storm", 20), 2.33, "完美時機").effectiveness(), 1e-9);
        final SkillProfile plain = profile("bear_slam", 20);
        assertEquals(plain.effectiveness(), StageAttacks.perfect(plain, 2.0, "完美時機").effectiveness(), 1e-9);
    }

    @Test
    void hiddenSkillsAndHooks() {
        for (final String id : List.of(StageAttacks.CROSS_SHOCKWAVE, StageAttacks.LANCE_BURST, StageAttacks.PRIMAL_SPIRIT, StageAttacks.PUSTULE,
                "proc_glacial_bolt_wall", "proc_frag_shatter")) {
            assertNotNull(config.skill(id), id);
            assertTrue(config.skill(id).hidden(), id);
        }
        assertEquals(SkillConfig.PARENT_OWN_TABLE, config.parentDamage(StageAttacks.CROSS_SHOCKWAVE), 1e-9);
        assertEquals(SkillConfig.PARENT_OWN_TABLE, config.parentDamage("proc_frag_shatter"), 1e-9);
        // 交錯爪：用掉印記觸發震波；迸片彈：用掉凍結觸發碎凍
        assertTrue(config.skill("cross_slash").modifiers().stream().anyMatch(m -> m.stat().equals("proc.mark_consumed")
                && m.tags().contains(StageAttacks.CROSS_SHOCKWAVE)));
        assertTrue(config.skill("fragmentation_rounds").modifiers().stream().anyMatch(m -> m.stat().equals("proc.hit_frozen")));
        // 迸片彈的穿透逐級 2 → 4 → 5（加在 projectile.pierce 0 上）
        assertEquals(0, config.skill("fragmentation_rounds").params().pierce());
        // 瘴域：毒花不打傷害；毒苞與回血逐級
        assertTrue(GroundEffects.is(profile("toxic_domain", 20)));
        assertEquals(2500, StageAttacks.local(profile("toxic_domain", 20), StageAttacks.DOMAIN_REGEN), 1e-9);
        assertEquals(969, StageAttacks.local(profile("toxic_domain", 20), StageAttacks.PUSTULE_MORE_PER), 1e-9);
        // 萬箭齊落：每一支 0.7 公尺
        assertEquals(0.7, config.skill("rain_of_arrows").params().radius().at(20), 1e-9);
        assertEquals("proc_glacial_bolt_wall", StageAttacks.CRYSTAL_BURST.get("glacial_bolt"));
    }
}
