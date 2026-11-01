package tw.exilecore.skill;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.file.Path;
import java.util.List;
import java.util.Set;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import tw.exilecore.stats.DamageType;
import tw.exilecore.stats.StatKeys;
import tw.exilecore.stats.StatSheet;
import tw.exilecore.stats.Tags;

/**
 * 2026-11-01 照 PoE2 0.5：盾技能（Shield Charge、Resonating Shield、Shield Wall）、Supercharged Slam、Sunder、Earthshatter、
 * Molten Blast、Shockwave Totem、Tempest Bell、Ancestral Warrior Totem 補上的機制。
 */
class ShieldSlamParityTest {

    static SkillConfig config;

    @BeforeAll
    static void load() throws IOException {
        config = new SkillConfig();
        config.load(Path.of("src/main/resources/data"));
    }

    private static SkillBuild build(final String id, final int level) {
        return SkillBuild.of(config, config.skill(id), level, 90, false, List.of(), Set.of(), new StatSheet());
    }

    @Test
    void shieldSkillsNeedAShieldNotAMace() {
        for (final String id : List.of("iron_wall_ram", "shield_drum", "earth_wall_strike")) {
            final SkillDefinition skill = config.skill(id);
            assertEquals(Set.of(SkillGear.SHIELD), skill.weapons(), id);
            assertTrue(skill.usableWith(Set.of(Tags.SWORD, Tags.MELEE)), id + "：不限主手");
            assertTrue(skill.usableWith(Set.of()), id + "：徒手也行");
            assertNotNull(SkillGear.problem(skill, null, false, Set.of()), id + "：副手沒拿東西不行");
            assertNotNull(SkillGear.problem(skill, Set.of("shield", "int_armour", "focus"), false, Set.of()), id + "：法器不行");
            assertNull(SkillGear.problem(skill, Set.of("shield", "str_armour"), false, Set.of()), id);
            assertNull(SkillGear.problem(skill, Set.of("shield", "dex_armour", "dex_only"), false, Set.of()), id + "：圓盾也是盾（PoB2）");
            assertEquals("副手盾", skill.weaponText(), id);
        }
    }

    @Test
    void ownAttackTimesIgnoreWeaponSpeed() {
        final StatSheet plain = new StatSheet();
        plain.add(tw.exilecore.stats.Modifier.flat(StatKeys.WEAPON_APS, 1.6, "武器"));
        assertEquals(12, build("iron_wall_ram", 1).intervalTicks(plain), 1e-9, "盾牌衝鋒 0.6 秒");
        assertEquals(16, build("shield_drum", 1).intervalTicks(plain), 1e-9, "共鳴之盾 0.8 秒");
        assertEquals(25, build("earth_wall_strike", 1).intervalTicks(plain), 1e-9, "盾牆 1.25 秒");
        final StatSheet fast = new StatSheet();
        fast.add(tw.exilecore.stats.Modifier.more(StatKeys.ATTACK_SPEED, 1.0, "加速"));
        assertEquals(12.5, build("earth_wall_strike", 1).intervalTicks(fast), 1e-9, "吃攻擊速度的加成");
        // 千鈞砸：攻擊時間不能被修改，只留伺服器的基礎速度
        final double expected = 0.85 * 20 / SkillConfig.playerSpeedScale();
        assertEquals(expected, build("thousand_weight_smash", 1).intervalTicks(fast), 1e-9);
        assertEquals(17, SkillProfiles.fixedAttackInterval(0.85, 1.0), 1e-9);
    }

    @Test
    void superchargedSlamNeedsATwoHandedMaceAndNoDualWield() {
        final SkillDefinition slam = config.skill("thousand_weight_smash");
        assertNotNull(SkillGear.problem(slam, null, false, Set.of(Tags.MACE, Tags.ONE_HAND)), "單手錘不行");
        assertNull(SkillGear.problem(slam, null, false, Set.of(Tags.MACE, Tags.TWO_HAND)));
        assertNotNull(SkillGear.problem(slam, null, true, Set.of(Tags.MACE, Tags.TWO_HAND)), "雙持不行");
        assertTrue(config.charge("thousand_weight_smash").lockedStart());
        assertEquals(1.4, SlamCascade.more(1), 1e-9);
        assertEquals(2.2, SlamCascade.more(3), 1e-9);
        assertEquals(1.2, SlamCascade.aftershockRadius(0), 1e-9);
        assertEquals(2.0, SlamCascade.aftershockRadius(2), 1e-9);
        assertEquals(4.2, SlamCascade.aftershockRadius(20), 1e-9, "最多加 3 公尺");
        assertEquals(5, SlamCascade.aftershockDelay(0));
        assertEquals(15, SlamCascade.aftershockDelay(2));
    }

    @Test
    void sunderAreasAreRadiiAndOverlap() {
        assertEquals(0.9, SkillExecutor.sunderRadius(0), 1e-9);
        assertEquals(1.7, SkillExecutor.sunderRadius(4), 1e-9);
        assertEquals(0.9, SkillExecutor.sunderCenter(0), 1e-9);
        assertEquals(0.9 + 0.9 * (0.9 + 1.1), SkillExecutor.sunderCenter(1), 1e-9);
        assertEquals(0.9 + 0.9 * (2.0 + 2.4 + 2.8 + 3.2), SkillExecutor.sunderCenter(4), 1e-9);
        final SkillBuild sunder = build("sunder", 20);
        assertSame(sunder, SkillGear.dualWield(sunder, false));
        final double more = SkillGear.dualWield(sunder, true).profile().effective(new StatSheet()).more(StatKeys.DAMAGE, Set.of());
        assertEquals(0.7, more, 1e-9, "雙持更少 30%");
    }

    @Test
    void moltenBlastThrowsOneAndScattersShrapnel() {
        final SkillBuild blast = build("lava_splash", 20);
        assertEquals(1, blast.projectiles());
        assertEquals(2, SkillGear.dualWield(blast, true).projectiles(), "雙持 2 顆");
        final SkillParams.Scatter scatter = blast.params().scatter();
        assertNotNull(scatter);
        assertEquals(8, scatter.count(20));
        final tw.exilecore.stats.SkillProfile shard = ProjectileEngine.shrapnelProfile(blast.profile(), scatter);
        assertEquals(blast.profile().effectiveness(), shard.effectiveness(), 1e-9, "碎片傷害效益同一張表");
        assertEquals(1.0, shard.conversion().get(DamageType.FIRE), 1e-9, "碎片全部轉火焰");
        assertEquals(0.6, blast.profile().conversion().get(DamageType.FIRE), 1e-9);
    }

    @Test
    void shieldChargeLocksAndStunsWhenFullyCharged() {
        final ChargeSpec.Channel channel = config.charge("iron_wall_ram").channel();
        assertFalse(Channelling.fullyCharged(channel, 7.9));
        assertTrue(Channelling.fullyCharged(channel, 8.0));
        assertEquals(0, TargetLock.angleTo(1, 0, 5, 0), 1e-9);
        assertEquals(90, TargetLock.angleTo(1, 0, 0, 3), 1e-9);
        assertArrayEquals(new double[] {0, 1}, TargetLock.steer(1, 0, 0, 4, true), 1e-9, "鎖定的敵人在旁邊：轉過去");
        assertArrayEquals(new double[] {1, 0}, TargetLock.steer(1, 0, 0, 4, false), 1e-9, "沒鎖到：照準心");
        assertArrayEquals(new double[] {1, 0}, TargetLock.steer(1, 0, 0, 0.1, true), 1e-9, "已經貼上：照準心");
        assertFalse(config.skill("iron_wall_ram").modifiers().stream().anyMatch(m -> m.stat().equals(StatKeys.STUN_BUILDUP)),
                "整招不再更多 50% 暈眩（只有撞上的那一下）");
    }

    @Test
    void wallsTotemsAndBells() {
        assertTrue(StageLeftovers.wallEarly(0));
        assertTrue(StageLeftovers.wallEarly(9));
        assertFalse(StageLeftovers.wallEarly(10), "立起 0.5 秒之後照常");
        assertEquals(6, SkillDeployables.placeRange("hero_spirit_totem"), 1e-9);
        assertEquals(8, SkillDeployables.placeRange("collapse_quake_totem"), 1e-9);
        assertEquals(0, SkillDeployables.placeRange("star_spell_totem"), 1e-9);
        assertTrue(config.skill("collapse_quake_totem").usableWith(Set.of(Tags.BOW, Tags.RANGED)), "崩震圖騰弓也能用");
        final SkillDefinition bell = config.skill("startling_thunder_bell");
        assertFalse(bell.tags().contains(Tags.TOTEM));
        assertTrue(bell.tags().contains(Tags.BELL));
        assertEquals(1.0, config.skill("earth_spike_eruption").params().radius().at(1), 1e-9);
    }

    @Test
    void tempestBellHasItsOwnComboRules() {
        final SkillResources.ComboRule rule = SkillResources.COMBO_RULES.get("startling_thunder_bell");
        assertNotNull(rule);
        assertEquals(160, rule.decay(), "8 秒");
        assertTrue(SkillResources.ruleCounts(rule, Set.of(Tags.QUARTERSTAFF, Tags.MELEE)));
        assertFalse(SkillResources.ruleCounts(rule, Set.of(Tags.MACE, Tags.MELEE)), "只算長棍打中的");
        assertFalse(SkillResources.ruleCounts(rule, Set.of()));
        assertEquals(4, SkillResources.ruledNow(new long[] {4, 100, 160}, 260));
        assertEquals(0, SkillResources.ruledNow(new long[] {4, 100, 160}, 261), "8 秒沒累積就歸零");
        assertEquals(0, SkillResources.ruledNow(null, 0));
        assertEquals(4, config.resource("startling_thunder_bell").combo());
        assertTrue(SkillExecutor.hasFlag(config.skill("startling_thunder_bell"), SkillService.UNSTUNNABLE_WHILE_USING));
        assertEquals(10, config.skill("startling_thunder_bell").deploy().rings(), "bell_hit_limit 10");
        assertEquals(0.3, config.skill("startling_thunder_bell").deploy().interval(), 1e-9, "bell_shockwave_cooldown_ms 300");
        assertEquals(2.6, config.skill("startling_thunder_bell").params().radius().at(20), 1e-9);
        assertEquals(1.3, StageLeftovers.bellArea(0.1), 1e-9, "每 0.1 格擊退範圍更多 30%");
        assertFalse(SkillDeployables.bellRecalled(81));
        assertTrue(SkillDeployables.bellRecalled(81.1), "離鐘超過 9 格收回");
    }

    @Test
    void shieldChargeCollisionKnocksBackNineBlocks() {
        assertEquals(9, KnockbackMath.distance(Channelling.CRASH_KNOCKBACK), 0.05);
        assertEquals(2 * KnockbackMath.distance(0.5), KnockbackMath.distance(1.0), 1e-3, "距離跟速度成正比");
        assertTrue(Channelling.CRASH_KNOCKBACK > 1.2 && Channelling.CRASH_KNOCKBACK < 1.4, "約 1.3");
    }

    @Test
    void shockwaveTotemWeaponsMatchTheReferenceList() {
        final SkillDefinition totem = config.skill("collapse_quake_totem");
        for (final String weapon : List.of(Tags.SWORD, Tags.AXE, Tags.MACE, Tags.SPEAR, Tags.QUARTERSTAFF, Tags.CLAW, Tags.DAGGER, "flail",
                "talisman", Tags.BOW, Tags.CROSSBOW)) {
            assertTrue(totem.usableWith(Set.of(weapon)), weapon);
        }
        assertFalse(totem.usableWith(Set.of()), "徒手不行");
        assertEquals(5, SkillExecutor.SUNDER_AREAS, "裂地斬 sunder_wave_max_steps 5");
    }
}
