package tw.exilecore.skill;

import org.bukkit.util.Vector;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import tw.exilecore.stats.DamageType;
import tw.exilecore.stats.Modifier;
import tw.exilecore.stats.SkillProfile;
import tw.exilecore.stats.StatKeys;
import tw.exilecore.stats.StatSheet;
import tw.exilecore.stats.Tags;
import tw.exilecore.stats.TreeConditions;

import java.io.IOException;
import java.nio.file.Path;
import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** 2026-11-01 照 PoE2 0.5：弩技能補上的擊退、弩台放置距離、充滿才射、子榴彈、引爆榴彈、碰到敵人就炸、毒氣擴大。 */
class CrossbowParityTest {

    static SkillConfig config;

    @BeforeAll
    static void load() throws IOException {
        config = new SkillConfig();
        config.load(Path.of("src/main/resources/data"));
    }

    private static final Set<String> CROSSBOW = Set.of(Tags.CROSSBOW, Tags.RANGED, Tags.TWO_HAND);

    private static SkillBuild build(final String skill, final int level) {
        return SkillBuild.of(config, config.skill(skill), level, 90, false, List.of(), CROSSBOW, new StatSheet());
    }

    @Test
    void knockbackDistancesFollowBaseKnockbackDistance() {
        assertEquals(new HitKnockback.Spec(4, true), HitKnockback.get("blasting_quarrel"));
        assertEquals(new HitKnockback.Spec(1, false), HitKnockback.get("frozen_earth_repeater"));
        assertEquals(new HitKnockback.Spec(0.3, false), HitKnockback.get("successive_shots"));
        assertEquals(new HitKnockback.Spec(0.3, false), HitKnockback.get("armor_break_quarrel"));
        assertEquals(new HitKnockback.Spec(4, false), HitKnockback.get("frozen_wall_shaft"));
        assertEquals(new HitKnockback.Spec(4, false), HitKnockback.get("searing_slurry_cannon"));
        assertNull(HitKnockback.get("blast_grenade"), "沒寫的技能照舊不擊退");
    }

    @Test
    void knockbackSpeedPushesTheRequestedDistance() {
        for (final double blocks : new double[] {0.3, 1, 4, 8}) {
            final double speed = HitKnockback.speedFor(blocks);
            assertEquals(blocks, speed * HitKnockback.blocksPerSpeed(HitKnockback.lift(blocks)), 1e-9);
            assertTrue(speed > 0 && speed < 2, "速度合理：" + speed);
        }
        assertTrue(HitKnockback.speedFor(4) > HitKnockback.speedFor(1));
        assertEquals(0, HitKnockback.speedFor(0), 1e-12);
        final Vector push = HitKnockback.velocity(new Vector(0, -0.5, 2), 4);
        assertNotNull(push);
        assertEquals(0, push.getX(), 1e-12);
        assertTrue(push.getZ() > 0, "照飛的方向（水平）推");
        assertEquals(HitKnockback.LIFT, push.getY(), 1e-12);
        assertEquals(HitKnockback.SMALL_LIFT, HitKnockback.velocity(new Vector(1, 0, 0), 0.3).getY(), 1e-12);
        assertNull(HitKnockback.velocity(new Vector(0, 1, 0), 4), "沒有水平方向就不推");
    }

    @Test
    void knockbackDistanceUsesSkillKnockbackModifiers() {
        final StatSheet sheet = new StatSheet();
        assertEquals(4, HitKnockback.distance(4, sheet, Set.of()), 1e-9);
        sheet.add(Modifier.more(StatKeys.SKILL_KNOCKBACK, 0.5, "測試"));
        assertEquals(6, HitKnockback.distance(4, sheet, Set.of()), 1e-9);
        assertEquals(4, HitKnockback.distance(4, null, Set.of()), 1e-9);
    }

    @Test
    void ballistaePlaceUpToEightMetresAway() {
        assertEquals(8, TotemPlacement.range("skyward_crossbow_turret"), 1e-9);
        assertEquals(8, TotemPlacement.range("nailfall_crossbow_turret"), 1e-9);
        assertEquals(0, TotemPlacement.range("star_spell_totem"), 1e-9, "沒寫的圖騰照舊放面前");
        assertEquals(18, config.skill("nailfall_crossbow_turret").deploy().range(), 1e-9, "找敵人的距離不變");
    }

    @Test
    void plasmaBlastMustBeFullyCharged() {
        assertTrue(FullCharge.required("searing_slurry_cannon"));
        assertFalse(FullCharge.required("snipe_shot"));
        assertNotNull(config.charge("searing_slurry_cannon"));
        assertEquals(1, config.skill("searing_slurry_cannon").attackSpeed(), 1e-9);
        assertTrue(FullCharge.cancels(45, 46));
        assertFalse(FullCharge.cancels(46, 46));
        assertEquals(0.5, FullCharge.chargeRatio(23, 46), 1e-9);
        assertEquals(1.0, FullCharge.chargeRatio(80, 46), 1e-9);
        assertTrue(FullCharge.chargeRatio(0, 46) > 0);
        assertTrue(FullCharge.progressText("熾漿砲", 23, 46).contains("50%"));
        assertTrue(FullCharge.progressText("熾漿砲", 46, 46).contains("充能完成"));
        // 總攻擊時間 +2 秒不吃攻擊速度：攻速再快，間隔也至少 40 tick
        final StatSheet fast = new StatSheet();
        fast.add(Modifier.flat(StatKeys.WEAPON_APS, 1.6, "武器"));
        fast.add(Modifier.increased(StatKeys.ATTACK_SPEED, 5.0, "測試"));
        final SkillBuild plasma = SkillBuild.of(config, config.skill("searing_slurry_cannon"), 20, 90, false, List.of(), CROSSBOW, fast);
        assertTrue(plasma.intervalTicks(fast) >= 40, "間隔：" + plasma.intervalTicks(fast));
    }

    @Test
    void clusterGrenadeThrowsEighteenOwnBomblets() {
        final SkillStages.Stage bomblet = SkillStages.get("mother_child_grenade", CrossbowParity.BOMBLET);
        assertNotNull(bomblet);
        assertEquals(18, CrossbowParity.bombletCount(bomblet, 1));
        assertEquals(18, CrossbowParity.bombletCount(bomblet, 20));
        assertEquals(60, CrossbowParity.bombletFuseTicks(bomblet, 2.0), "子榴彈 3 秒才炸");
        assertEquals(1.4, bomblet.radius(20), 1e-9);
        assertEquals(0.4, bomblet.conversion().get(DamageType.FIRE), 1e-9);
        assertEquals(2.86, bomblet.effectiveness().at(20), 1e-9);
        assertEquals(12.67, bomblet.effectiveness().at(40), 1e-9);
        assertEquals(0.2, config.skill("mother_child_grenade").conversion().get(DamageType.FIRE), 1e-9, "主榴彈 20%");
        assertEquals(2, CrossbowParity.bombletRange(4, 0), 1e-9);
        assertEquals(4, CrossbowParity.bombletRange(4, 1), 1e-9);
        // 拋物線射程：2v²·sinθ·cosθ ÷ g = reach
        final double v = CrossbowParity.bombletSpeed(3, 0.06);
        final double angle = Math.atan(CrossbowParity.BOMBLET_LIFT);
        assertEquals(3, 2 * v * v * Math.sin(angle) * Math.cos(angle) / 0.06, 1e-9);
    }

    @Test
    void explosiveShotSplitsConversionAndDetonatesGrenades() {
        assertEquals(0.3, config.skill("blasting_quarrel").conversion().get(DamageType.FIRE), 1e-9);
        final SkillStages.Stage explosion = SkillStages.get("blasting_quarrel", "explosion");
        assertNotNull(explosion);
        assertEquals(0.8, explosion.conversion().get(DamageType.FIRE), 1e-9);
        for (final int level : new int[] {1, 20, 40}) {
            assertEquals(1.0, SkillStages.share(config.skill("blasting_quarrel"), explosion, level), 1e-9, "爆炸跟弩箭同一條表");
        }
        assertTrue(CrossbowParity.detonatesGrenades(build("blasting_quarrel", 20).profile()));
        assertFalse(CrossbowParity.detonatesGrenades(build("blast_grenade", 20).profile()));
        assertTrue(CrossbowParity.burstsOnEnemy(build("kerosene_grenade", 20).profile()));
        assertFalse(CrossbowParity.burstsOnEnemy(build("blast_grenade", 20).profile()));
    }

    @Test
    void gasGrenadeCloudGrowsAndIsCapped() {
        final SkillProfile gas = build("miasma_grenade", 20).profile();
        assertEquals(20, SkillZones.localFlat(gas, tw.exilecore.stats.SupportExtraKeys.ENCROACH), 1e-9);
        assertEquals(80, SkillZones.localFlat(gas, tw.exilecore.stats.SupportExtraKeys.ENCROACH_MAX_KEY), 1e-9);
        assertEquals(6, config.skill("miasma_grenade").params().ground().max());
        assertEquals(1.0, SkillZones.gasBurstScale("miasma_grenade"), 1e-12, "爆炸範圍就是毒氣（擴大後）的範圍");
        assertEquals(1.3, SkillZones.gasBurstScale("venom_etch_arrow"), 1e-12, "其他毒霧照舊");
    }

    @Test
    void siegeCascadeVsImmobilisedAndNeverFreezes() {
        final SkillDefinition siege = config.skill("fortress_crumble_shell");
        assertTrue(siege.modifiers().stream().anyMatch(m -> m.stat().equals(StatKeys.DAMAGE)
                && m.tags().contains(TreeConditions.VS_IMMOBILISED) && Math.abs(m.value() - 1.0) < 1e-9), "對禁錮更多 100%");
        final StatSheet sheet = new StatSheet();
        final double freeze = build("fortress_crumble_shell", 20).profile().effective(sheet).apply(StatKeys.FREEZE_BUILDUP, 1.0, Set.of());
        assertEquals(1, freeze, 1e-9, "2026-11-01 照站長：技能本身的異常限制拿掉，可以凍結");
    }

    @Test
    void glacialBoltLeavesTwoWallsAndDoesNotExplode() {
        assertEquals(0, config.skill("frozen_wall_shaft").params().explode().first(), 1e-9);
        final StatSheet sheet = new StatSheet();
        assertEquals(8, IceCrystals.count(build("frozen_wall_shaft", 20), sheet));
    }

    @Test
    void oilGroundIgnitesFromOneAndAHalfTimesTheAttackUnscalable() {
        final SkillProfile oil = build("kerosene_grenade", 20).profile();
        assertEquals(150, OilIgnite.percent(oil), 1e-9);
        assertEquals(0, OilIgnite.percent(build("blast_grenade", 20).profile()), 1e-9, "沒寫的技能照舊");
        assertEquals(100, config.skill("kerosene_grenade").params().ground().damage(), 1e-9, "火油那一段跟主榴彈同一條表");
        assertEquals(1.0, OilIgnite.allFire(oil).conversion().get(DamageType.FIRE), 1e-9, "物理 100% 轉火焰");
        // 1000 火焰 × 150% = 1500；點燃幅度（1 + 50%）× 2 會被 igniteFrom 乘回去，所以先除掉
        assertEquals(500, OilIgnite.unscalableBase(1000, 150, 0.5, 2.0), 1e-9);
        assertEquals(1500, OilIgnite.unscalableBase(1000, 150, 0, 1.0), 1e-9);
        assertEquals(0, OilIgnite.unscalableBase(1000, 150, -1.0, 1.0), 1e-12, "幅度扣光就沒有點燃");
        assertEquals(0, OilIgnite.unscalableBase(0, 150, 0, 1.0), 1e-12);
    }

    @Test
    void permafrostBoltsFollowANormalSpread() {
        final NormalSpread.Spec spec = NormalSpread.get("frozen_earth_repeater");
        assertEquals(new NormalSpread.Spec(14, 40), spec);
        assertNull(NormalSpread.get("successive_shots"));
        assertEquals(20, NormalSpread.angle(spec, 40, () -> 10), 1e-9, "擲幾次都超出：夾在錐形邊上");
        assertEquals(7, NormalSpread.angle(spec, 40, () -> 0.5), 1e-9, "標準差 14 度");
        assertEquals(14, NormalSpread.angle(spec, 80, () -> 0.5), 1e-9, "扇形變寬，標準差照比例變大");
        final java.util.Random random = new java.util.Random(7);
        int inner = 0;
        int outer = 0;
        final int n = 4000;
        for (int i = 0; i < n; i++) {
            final double a = NormalSpread.angle(spec, 40, random::nextGaussian);
            assertTrue(Math.abs(a) <= 20 + 1e-9);
            if (Math.abs(a) <= 4) {
                inner++;
            } else if (Math.abs(a) >= 16) {
                outer++;
            }
        }
        assertTrue(inner > outer * 1.5, "中間 4 度內比邊上 4 度密：" + inner + " vs " + outer);
        final List<double[]> fan = NormalSpread.fan(0, 0, 1, 10, spec, 40, random::nextGaussian);
        assertEquals(10, fan.size());
        for (final double[] d : fan) {
            assertTrue(d[2] >= Math.cos(Math.toRadians(20)) - 1e-9, "都在錐形裡");
        }
        assertEquals(60, SkillStages.mark(build("frozen_earth_repeater", 20).profile(), ProjectileEngine.SPEED_VARIATION), 1e-9);
    }

    @Test
    void glacialBoltGrowsTwoWallsFromTheImpact() {
        final CrystalWalls.Spec spec = CrystalWalls.get("frozen_wall_shaft");
        assertEquals(new CrystalWalls.Spec(2, 6.3, 1.6, 45, true), spec);
        assertEquals(4, CrystalWalls.perWallCap(spec));
        final List<double[]> offsets = CrystalWalls.offsets(spec, 0, 1, 8);
        assertEquals(8, offsets.size());
        int left = 0;
        for (final double[] o : offsets) {
            final double distance = Math.hypot(o[0], o[1]);
            assertTrue(distance <= 6.3 + 1e-9, "每道最長 6.3 格");
            assertTrue(o[1] > 0, "往前張開");
            assertEquals(Math.abs(o[0]), o[1], 1e-9, "跟飛行方向夾 45 度");
            if (o[0] < 0) {
                left++;
            }
        }
        assertEquals(4, left, "左右各 4 顆");
        assertEquals(5.6, Math.hypot(offsets.get(3)[0], offsets.get(3)[1]), 1e-9);
        assertEquals(8, CrystalWalls.offsets(spec, 0, 1, 20).size(), "放不下 6.3 格的截掉");
    }
}
