package tw.exilecore.skill;

import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import tw.exilecore.charge.ChargeService;
import tw.exilecore.stats.DamageType;
import tw.exilecore.stats.GrantedSkillKeys;
import tw.exilecore.stats.SkillProfile;
import tw.exilecore.stats.Tags;

import java.io.IOException;
import java.nio.file.Path;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** 2026-10-28 照 PoE2 0.5 重做的武器賦予技能：寒心、屍焰爆、血鐮、銳星虹吸、纏蝕箭（資料與純計算）。 */
class GrantedSkillPoe2Test {

    static SkillConfig config;

    @BeforeAll
    static void load() throws IOException {
        config = new SkillConfig();
        config.load(Path.of("src/main/resources/data"));
    }

    private static double level(final String skill, final String stat, final int level) {
        for (final ScaledMod mod : config.levelModifiers(skill)) {
            if (mod.stat().equals(stat)) {
                return mod.value().at(level);
            }
        }
        throw new AssertionError(skill + " 沒有 " + stat);
    }

    private static boolean has(final String skill, final String stat) {
        return config.skill(skill).modifiers().stream().anyMatch(m -> m.stat().equals(stat))
                || config.levelModifiers(skill).stream().anyMatch(m -> m.stat().equals(stat));
    }

    // ---- 銳星虹吸 ----

    @Test
    void extraChargesAbove100PercentGiveOneSureAndRollTheRest() {
        assertEquals(0, ChargeService.extraCount(0, 0.0));
        assertEquals(0, ChargeService.extraCount(-1, 0.0));
        assertEquals(1, ChargeService.extraCount(0.76, 0.75));
        assertEquals(0, ChargeService.extraCount(0.76, 0.76));
        assertEquals(1, ChargeService.extraCount(1.0, 0.999), "100% 一定多一顆，不會多兩顆");
        assertEquals(2, ChargeService.extraCount(1.56, 0.55), "156%：一定一顆，56% 再一顆");
        assertEquals(1, ChargeService.extraCount(1.56, 0.56));
        assertEquals(2, ChargeService.extraCount(2.0, 0.999));
        // 期望值 = 機率本身
        double sum = 0;
        for (int i = 0; i < 1000; i++) {
            sum += ChargeService.extraCount(1.56, (i + 0.5) / 1000.0);
        }
        assertEquals(1.56, sum / 1000, 1e-9);
    }

    @Test
    void powerSiphonGrantsOnCullNotKill() {
        assertTrue(has("power_siphon", "charge.event.cull.power"));
        assertFalse(has("power_siphon", "charge.event.kill.power"), "照 PoE2 是撲殺時給，不是打死時");
        assertTrue(has("power_siphon", GrantedSkillKeys.FLAG_NO_RARITY_CHARGES));
        assertTrue(has("power_siphon", "flag.culling"));
        assertEquals(0, level("power_siphon", "charge.extra", 1), 1e-9);
        assertEquals(76, level("power_siphon", "charge.extra", 20), 1e-9);
        assertEquals(100, level("power_siphon", "charge.extra", 26), 1e-9);
        assertEquals(156, level("power_siphon", "charge.extra", 40), 1e-9);
    }

    // ---- 血鐮 ----

    @Test
    void reapGainsFlatLifeOnCull() {
        assertEquals(5, level("reap", GrantedSkillKeys.LIFE_ON_CULL_FLAT, 1), 1e-9);
        assertEquals(188, level("reap", GrantedSkillKeys.LIFE_ON_CULL_FLAT, 20), 1e-9);
        assertEquals(829, level("reap", GrantedSkillKeys.LIFE_ON_CULL_FLAT, 40), 1e-9);
        assertFalse(has("reap", tw.exilecore.stats.StatKeys.LIFE_ON_CULL), "不再用最大生命的 % 頂著");
        assertEquals(188, GrantedSkillKeys.cullLife(5000, 0, 188), 1e-9, "固定值，跟最大生命無關");
        assertEquals(188, GrantedSkillKeys.cullLife(100, 0, 188), 1e-9);
        assertEquals(50 + 188, GrantedSkillKeys.cullLife(1000, 5, 188), 1e-9, "跟最大生命 % 的可以疊");
        assertEquals(0, GrantedSkillKeys.cullLife(1000, -5, -1), 1e-9);
    }

    // ---- 纏蝕箭 ----

    @Test
    void coilingBoltsHasOneTablePerDamageType() {
        final SkillDefinition bolts = config.skill("coiling_bolts");
        assertEquals(Set.of(DamageType.PHYSICAL, DamageType.CHAOS), bolts.typedDamage().keySet());
        final int[][] expected = {{1, 9, 14, 8, 15}, {20, 279, 418, 244, 453}, {40, 3735, 5603, 3269, 6070}};
        for (final int[] row : expected) {
            final SkillProfile profile = SkillProfiles.build(config, bolts, row[0], Set.of());
            assertEquals(row[1], profile.spellDamage().get(DamageType.PHYSICAL), 1e-9, "物理最小 " + row[0]);
            assertEquals(row[2], profile.spellDamageMax().get(DamageType.PHYSICAL), 1e-9, "物理最大 " + row[0]);
            assertEquals(row[3], profile.spellDamage().get(DamageType.CHAOS), 1e-9, "混沌最小 " + row[0]);
            assertEquals(row[4], profile.spellDamageMax().get(DamageType.CHAOS), 1e-9, "混沌最大 " + row[0]);
            final double[] sum = SkillProfiles.spellDamage(config, bolts, row[0]);
            assertArrayEquals(new double[] {row[1] + row[3], row[2] + row[4]}, sum, 1e-9, "說明用的總和");
        }
        assertEquals(1.0, bolts.damageTypes().values().stream().mapToDouble(Double::doubleValue).sum(), 1e-9);
        assertTrue(Math.abs(bolts.damageTypes().get(DamageType.PHYSICAL) - 0.5) < 0.05);
    }

    @Test
    void typedTablesSumAndWeigh() {
        final Map<DamageType, int[]> typed = Map.of(DamageType.PHYSICAL, new int[] {1, 3, 2, 4}, DamageType.FIRE, new int[] {1, 1, 3, 3});
        assertArrayEquals(new int[] {2, 4, 5, 7}, SkillDefinition.summedTable(typed));
        final Map<DamageType, Double> weights = SkillDefinition.typedWeights(typed);
        assertEquals(10 / 18.0, weights.get(DamageType.PHYSICAL), 1e-9);
        assertEquals(8 / 18.0, weights.get(DamageType.FIRE), 1e-9);
        // 表外的等級照最後兩級的比例往外推（跟一般逐級表一樣）
        final double[] beyond = SkillProfiles.tableAt(new int[] {10, 20, 20, 40}, 3);
        assertArrayEquals(new double[] {40, 80}, beyond, 1e-9);
        assertArrayEquals(new double[] {10, 20}, SkillProfiles.tableAt(new int[] {10, 20, 20, 40}, 1), 1e-9);
    }

    @Test
    void otherSpellsStillSplitOneTable() {
        final SkillDefinition firebolt = config.skill("firebolt");
        assertTrue(firebolt.typedDamage().isEmpty());
        final SkillProfile profile = SkillProfiles.build(config, firebolt, 1, Set.of());
        assertEquals(7, profile.spellDamage().get(DamageType.FIRE), 1e-9);
        assertEquals(10, profile.spellDamageMax().get(DamageType.FIRE), 1e-9);
    }

    // ---- 寒心 ----

    @Test
    void heartOfIceIsAPersistentPresenceChill() {
        final SkillDefinition heart = config.skill("heart_of_ice");
        assertEquals(Mechanic.AURA, heart.mechanic());
        assertEquals(0, heart.aura().reserve(), 1e-9, "原作沒有精魂保留");
        assertFalse(heart.aura().allies());
        assertEquals(0, heart.cooldown(), 1e-9, "不再是冷卻 4 秒的寒氣爆發");
        assertNull(heart.damage(), "不打人");
        assertFalse(heart.dealsDamage());
        assertTrue(has("heart_of_ice", GrantedSkillKeys.FLAG_PRESENCE_CHILL));
        assertEquals(26, level("heart_of_ice", GrantedSkillKeys.AS_THOUGH_MIN, 1), 1e-9);
        assertEquals(39, level("heart_of_ice", GrantedSkillKeys.AS_THOUGH_MAX, 1), 1e-9);
        assertEquals(888, level("heart_of_ice", GrantedSkillKeys.AS_THOUGH_MIN, 20), 1e-9);
        assertEquals(1332, level("heart_of_ice", GrantedSkillKeys.AS_THOUGH_MAX, 20), 1e-9);
        assertEquals(15509, level("heart_of_ice", GrantedSkillKeys.AS_THOUGH_MIN, 40), 1e-9);
        assertEquals(23263, level("heart_of_ice", GrantedSkillKeys.AS_THOUGH_MAX, 40), 1e-9);
        assertTrue(heart.tags().contains(Tags.PERSISTENT));
    }

    @Test
    void asThoughProfileCarriesOnlyThatDamageType() {
        final SkillProfile base = new SkillProfile("heart_of_ice", Set.of("aura", Tags.PERSISTENT), 1.0, null);
        final SkillProfile cold = AsThough.profile(base, DamageType.COLD, 26, 39);
        assertTrue(cold.tags().contains(Tags.COLD));
        assertTrue(cold.tags().contains(Tags.SPELL));
        assertEquals(26, cold.spellDamage().get(DamageType.COLD), 1e-9);
        assertEquals(39, cold.spellDamageMax().get(DamageType.COLD), 1e-9);
        assertEquals(0, cold.spellDamage().get(DamageType.FIRE), 1e-9);
        final tw.exilecore.stats.StatSheet sheet = new tw.exilecore.stats.StatSheet();
        assertEquals(32.5, AsThough.amount(sheet, cold, DamageType.COLD), 1e-9, "沒有加成就是平均");
    }

    // ---- 屍焰爆 ----

    @Test
    void volatileDeadMakesCoresWithTheirOwnIgniteTable() {
        final SkillDefinition dead = config.skill("volatile_dead");
        assertTrue(has("volatile_dead", GrantedSkillKeys.FLAG_VOLATILE_CORE));
        assertFalse(has("volatile_dead", tw.exilecore.stats.StatKeys.IGNITE_CHANCE), "核心直接點燃，不擲機率");
        // 爆炸（小的那張表）
        assertArrayEquals(new double[] {3, 4}, SkillProfiles.spellDamage(config, dead, 1), 1e-9);
        assertArrayEquals(new double[] {20, 29}, SkillProfiles.spellDamage(config, dead, 20), 1e-9);
        assertArrayEquals(new double[] {52, 78}, SkillProfiles.spellDamage(config, dead, 40), 1e-9);
        // 點燃（像造成）
        assertEquals(4, level("volatile_dead", GrantedSkillKeys.AS_THOUGH_MIN, 1), 1e-9);
        assertEquals(6, level("volatile_dead", GrantedSkillKeys.AS_THOUGH_MAX, 1), 1e-9);
        assertEquals(146, level("volatile_dead", GrantedSkillKeys.AS_THOUGH_MIN, 20), 1e-9);
        assertEquals(218, level("volatile_dead", GrantedSkillKeys.AS_THOUGH_MAX, 20), 1e-9);
        assertEquals(2541, level("volatile_dead", GrantedSkillKeys.AS_THOUGH_MIN, 40), 1e-9);
        assertEquals(3812, level("volatile_dead", GrantedSkillKeys.AS_THOUGH_MAX, 40), 1e-9);
        assertEquals(2.2, dead.params().radius().at(1), 1e-9, "爆炸 2.2 格");
    }

    @Test
    void volatileCoreHelpers() {
        assertEquals(1, VolatileCores.cores(1), "一具屍體一顆核心");
        assertEquals(0, VolatileCores.cores(0));
        assertEquals(100, VolatileCores.duration(1.0), "5 秒");
        assertEquals(150, VolatileCores.duration(1.5), "吃持續時間");
        assertTrue(VolatileCores.igniteDue(0), "一出現就點");
        assertFalse(VolatileCores.igniteDue(5));
        assertTrue(VolatileCores.igniteDue(10));
        assertEquals(1.5, VolatileCores.IGNITE_RADIUS, 1e-9);
        // 往敵人移動，最多一步、不會穿過去
        final double[] step = VolatileCores.seek(new double[] {0, 0, 0}, new double[] {10, 0, 0}, 0.2, 0.4);
        assertArrayEquals(new double[] {0.2, 0, 0}, step, 1e-9);
        final double[] close = VolatileCores.seek(new double[] {0, 0, 0}, new double[] {0.5, 0, 0}, 0.2, 0.4);
        assertArrayEquals(new double[] {0.1, 0, 0}, close, 1e-9);
        final double[] stay = VolatileCores.seek(new double[] {0, 0, 0}, new double[] {0.3, 0, 0}, 0.2, 0.4);
        assertArrayEquals(new double[] {0, 0, 0}, stay, 1e-9);
        final double[] diagonal = VolatileCores.seek(new double[] {0, 0, 0}, new double[] {3, 0, 4}, 1, 0);
        assertArrayEquals(new double[] {0.6, 0, 0.8}, diagonal, 1e-9);
    }
}
