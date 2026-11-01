package tw.exilecore.skill;

import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import tw.exilecore.combat.Debuffs;
import tw.exilecore.stats.DamageType;
import tw.exilecore.stats.Modifier;
import tw.exilecore.stats.SkillProfile;

import java.io.IOException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** 2026-11-01 照 PoE2 0.5：冰系法術補上的機制（ColdSpells、霜壁的冰晶牆）。 */
class ColdSpellsTest {

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
    void novaKnockbackFallsOffWithDistance() {
        // 近 +50%、遠 −90%、最多 2 格
        assertEquals(1.5, ColdSpells.falloffMetres(1, 0, 3.2), 1e-9);
        assertEquals(0.1, ColdSpells.falloffMetres(1, 3.2, 3.2), 1e-9);
        assertEquals(0.8, ColdSpells.falloffMetres(1, 1.6, 3.2), 1e-9);
        assertEquals(2.0, ColdSpells.falloffMetres(5, 0, 3.2), 1e-9);
        assertEquals(0, ColdSpells.falloffMetres(0, 0, 3.2), 1e-9);
        assertEquals(1.0 / ColdSpells.BLOCKS_PER_KNOCKBACK_VELOCITY, ColdSpells.knockbackVelocity(1), 1e-9);
        assertTrue(ColdSpells.falloff(profile(ColdSpells.ICE_NOVA, 20)));
    }

    @Test
    void ownTablesMatchPathOfBuilding() {
        // 冰灌注霜星爆 448–672（20 級）；霜鑽爆炸 230–344；冰梭爆炸 66–98、冰塊 53–80；霜壁閃電 23–444
        final ResourceSpec.InfusedSet nova = config.resource(ColdSpells.ICE_NOVA).infusedSet();
        assertNotNull(nova);
        assertEquals(448, SkillProfiles.tableAt(nova.damage().get(DamageType.COLD), 20)[0], 1e-9);
        assertEquals(9002, SkillProfiles.tableAt(nova.damage().get(DamageType.COLD), 40)[1], 1e-9);
        final SkillProfile bolt = profile(ColdSpells.FROSTBOLT, 20);
        final SkillProfile blast = ColdSpells.ownTable(config, bolt, ColdSpells.FROSTBOLT_BURST, 20);
        assertEquals(230, blast.spellDamage().get(DamageType.COLD), 1e-9);
        assertEquals(344, blast.spellDamageMax().get(DamageType.COLD), 1e-9);
        assertEquals(bolt.local(), blast.local()); // 輔助、技能自己的修飾器照帶
        final SkillProfile darts = profile(ColdSpells.DARTS, 20);
        assertEquals(66, ColdSpells.ownTable(config, darts, ColdSpells.DARTS_BURST, 20).spellDamage().get(DamageType.COLD), 1e-9);
        assertEquals(80, ColdSpells.ownTable(config, darts, ColdSpells.DARTS_CHUNK, 20).spellDamageMax().get(DamageType.COLD), 1e-9);
        final SkillProfile volt = ColdSpells.ownTable(config, profile(ColdSpells.FROST_WALL, 20), ColdSpells.WALL_VOLT, 20);
        assertEquals(444, volt.spellDamageMax().get(DamageType.LIGHTNING), 1e-9);
        assertEquals(0, volt.spellDamageMax().get(DamageType.COLD), 1e-9);
        assertEquals(2.4, ColdSpells.tableRadius(config, ColdSpells.FROSTBOLT_BURST, 20, 0), 1e-9);
        assertEquals(1.8, ColdSpells.tableRadius(config, ColdSpells.DARTS_BURST, 20, 0), 1e-9);
        assertEquals(0.8, ColdSpells.tableRadius(config, ColdSpells.DARTS_CHUNK, 20, 0), 1e-9);
        assertEquals(2.0, ColdSpells.tableRadius(config, ColdSpells.WALL_BURST, 20, 0), 1e-9);
        assertEquals(2.8, ColdSpells.tableRadius(config, ColdSpells.WALL_VOLT, 20, 0), 1e-9);
    }

    @Test
    void eyeOfWinterSpiral() {
        assertEquals(30, ColdSpells.shardsPerSecond(1), 1e-9);
        assertEquals(32, ColdSpells.shardsPerSecond(3), 1e-9); // +2 投射物 = 每秒多 2 片
        assertEquals(0, ColdSpells.spiralAngle(0, 0), 1e-9);
        assertEquals(ColdSpells.SPIRAL_STEP_DEGREES * 3 % 360, ColdSpells.spiralAngle(0, 3), 1e-9);
        assertEquals(900, ColdSpells.FIRST_HIT_MORE, 1e-9);
        assertEquals(0, config.skill(ColdSpells.EYE).params().pierce()); // 冰眼不打人，冰片不貫穿
    }

    @Test
    void temporalChainsSlowsOtherEffects() {
        // 25% 慢：每 4 tick 推 1 tick
        double[] step = ColdSpells.temporalStep(0, 25, 4);
        assertEquals(1, step[0], 1e-9);
        assertEquals(0, step[1], 1e-9);
        // 詛咒效果 +20%（30%）：4 tick 推 1.2 → 先推 1，剩 0.2 累積
        step = ColdSpells.temporalStep(0, 30, 4);
        assertEquals(1, step[0], 1e-9);
        assertEquals(0.2, step[1], 1e-9);
        step = ColdSpells.temporalStep(0.9, 30, 4);
        assertEquals(2, step[0], 1e-9);
        // 詛咒等級上限：1 級 20、18 級以後不限
        assertTrue(ColdSpells.aboveCurseLevel(20, 21));
        assertFalse(ColdSpells.aboveCurseLevel(20, 20));
        assertFalse(ColdSpells.aboveCurseLevel(0, 100));
        assertEquals(20, SkillZones.localFlat(profile(ColdSpells.TEMPORAL, 1), ColdSpells.CURSE_MAX_LEVEL), 1e-9);
        assertEquals(0, SkillZones.localFlat(profile(ColdSpells.TEMPORAL, 20), ColdSpells.CURSE_MAX_LEVEL), 1e-9);
        // 攻擊、施法也變慢（40% → 59% → 74%）
        final SkillParams.Mark mark = config.skill(ColdSpells.TEMPORAL).params().mark();
        boolean attack = false;
        boolean cast = false;
        for (final ScaledMod effect : mark.effects()) {
            final Modifier at20 = effect.toModifier(20, 1.0, "t");
            if (at20.stat().equals(tw.exilecore.stats.StatKeys.ATTACK_SPEED)) {
                attack = true;
                assertEquals(-0.59, at20.value(), 1e-9);
            } else if (at20.stat().equals(tw.exilecore.stats.StatKeys.CAST_SPEED)) {
                cast = true;
                assertEquals(-0.74, effect.toModifier(40, 1.0, "t").value(), 1e-9);
            }
        }
        assertTrue(attack && cast);
    }

    @Test
    void extendExceptSkipsTheCurseItself() {
        final Debuffs debuffs = new Debuffs();
        final UUID target = UUID.randomUUID();
        final UUID source = UUID.randomUUID();
        debuffs.apply(target, new Debuffs.Debuff("sluggish_curse", source, 100, 0, false));
        debuffs.apply(target, new Debuffs.Debuff("exposure", source, 100, 0, false));
        debuffs.extendExcept(target, 5, 10, ColdSpells.TEMPORAL);
        for (final Debuffs.Debuff debuff : debuffs.active(target, 10)) {
            assertEquals(debuff.id().equals("exposure") ? 105 : 100, debuff.untilTick());
        }
    }

    @Test
    void frostWallIsAWallOfCrystals() {
        final SkillProfile wall = profile(ColdSpells.FROST_WALL, 20);
        assertEquals(1, SkillZones.localFlat(wall, IceCrystals.WALL), 1e-9);
        assertEquals(0, SkillZones.localFlat(wall, tw.exilecore.stats.StatKeys.CRYSTAL_COUNT), 1e-9); // 不再另外留 4 顆
        final double[] out = IceCrystals.pushOut(0.3, 0, 1.0);
        assertEquals(1.02, out[0], 1e-9);
        assertEquals(0, out[1], 1e-9);
        final double[] center = IceCrystals.pushOut(0, 0, 1.0);
        assertEquals(1.02, Math.hypot(center[0], center[1]), 1e-9);
        assertEquals(60, IceCrystals.MAX_PER_PLAYER);
    }

    @Test
    void orbsFeedEyeOfWinter() {
        assertTrue(ColdSpells.isOrb("thunder_cloud", config.skill("thunder_cloud").tags()));
        assertTrue(ColdSpells.isOrb("chill_crystal_mine", config.skill("chill_crystal_mine").tags()));
        assertTrue(ColdSpells.isOrb("scorching_sun_pearl", config.skill("scorching_sun_pearl").tags()));
        assertTrue(ColdSpells.isOrb("elemental_storm_fire", config.skill("elemental_storm_fire").tags()));
        assertFalse(ColdSpells.isOrb(ColdSpells.ICE_NOVA, config.skill(ColdSpells.ICE_NOVA).tags()));
        assertEquals(DamageType.COLD, ColdSpells.elementOf(config.skill("chill_crystal_mine").tags()));
        assertEquals(DamageType.LIGHTNING, ColdSpells.elementOf(config.skill("thunder_cloud").tags()));
        assertEquals(DamageType.FIRE, ColdSpells.elementOf(config.skill("scorching_sun_pearl").tags()));
        assertTrue(ColdSpells.touches(0, 64, 0, 2.5, 2.9, 65, 0, 0.5));
        assertFalse(ColdSpells.touches(0, 64, 0, 2.5, 3.1, 65, 0, 0.5));
        assertFalse(ColdSpells.touches(0, 64, 0, 2.5, 0, 70, 0, 0.5));
    }

    @Test
    void monstersSwingAtCrystalsAtTheirAttackRate() {
        assertEquals(20, IceCrystals.monsterSwingTicks(1.0));
        assertEquals(33, IceCrystals.monsterSwingTicks(0.6)); // 遲滯 40%
        assertEquals(67, IceCrystals.monsterSwingTicks(0.1)); // 最慢 0.3 倍
        assertEquals(7, IceCrystals.monsterSwingTicks(5));
    }

    @Test
    void chunksLandWithinMortarRange() {
        assertEquals(0.5, ColdSpells.chunkDistance(0), 1e-9);
        assertEquals(1.7, ColdSpells.chunkDistance(1), 1e-9);
        assertNotNull(ColdSpells.describe(ColdSpells.DARTS_LODGE, 1));
        assertNotNull(ColdSpells.describe(ColdSpells.TIME_PASSED, 25));
    }
}
