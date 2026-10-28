package tw.exilecore.skill;

import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import tw.exilecore.stats.DamageType;
import tw.exilecore.stats.Modifier;
import tw.exilecore.stats.StatSheet;

import java.io.IOException;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** 2026-10-28 照 PoE2 0.5 Spark（游雷；原作繁中「電球」）：貼地游走、2 秒持續時間、同一次施放 0.66 秒打同一目標一次、冰灌注版整組數值。 */
class SparkPoe2Test {

    static SkillConfig config;

    @BeforeAll
    static void load() throws IOException {
        config = new SkillConfig();
        config.load(Path.of("src/main/resources/data"));
    }

    private static SkillBuild spark(final int level) {
        return SkillBuild.of(config, config.skill("spark"), level, 100, false, List.of(), Set.of(), new StatSheet());
    }

    @Test
    void baseStatsMatchPoe2() {
        final SkillDefinition spark = config.skill("spark");
        assertEquals(0.7, spark.castTime(), 1e-9);
        assertEquals(0.5, spark.params().speed(), 1e-9, "每秒 10 公尺");
        assertEquals(4, spark.params().projectiles().intAt(1));
        assertEquals(9, spark.params().projectiles().intAt(20));
        assertEquals(13, spark.params().projectiles().intAt(40));
        final SkillParams.Wander wander = spark.params().wander();
        assertNotNull(wander, "貼地游走");
        assertEquals(2, wander.duration(), 1e-9, "投射物持續 2 秒");
        assertEquals(2, wander.width(), 1e-9, "spark_pathing_width_bounds 20");
        assertEquals(0.66, wander.hitCooldown(), 1e-9);
        assertEquals(14, wander.hitCooldownTicks(), "0.66 秒無條件進位成 14 tick（不比原作快）");
        assertEquals(14, spark(20).intervalTicks(new StatSheet()), 1e-9, "施法 0.7 秒");
    }

    @Test
    void coldInfusedSetReplacesDamageAndFiresRing() {
        final ResourceSpec resource = config.resource("spark");
        assertEquals(List.of("cold"), resource.infusion());
        final ResourceSpec.InfusedSet set = resource.infusedSet();
        assertNotNull(set);
        assertTrue(set.nova());
        final SkillBuild plain = spark(20);
        final SkillBuild infused = SkillService.applyInfusedSet(SkillService.applyInfused(plain, resource.infusedFor("cold")), set);
        assertEquals(16, infused.projectiles(), "20 級 16 顆");
        assertEquals(360, infused.spread(), 1e-9, "往四周一圈");
        assertEquals(207, infused.profile().spellDamage().get(DamageType.COLD), 1e-9);
        assertEquals(310, infused.profile().spellDamageMax().get(DamageType.COLD), 1e-9);
        assertEquals(13, infused.profile().spellDamage().get(DamageType.LIGHTNING), 1e-9, "冰灌注版的閃電比一般版（11–216）高");
        assertEquals(245, infused.profile().spellDamageMax().get(DamageType.LIGHTNING), 1e-9);
        assertEquals(11, plain.profile().spellDamage().get(DamageType.LIGHTNING), 1e-9);
        assertEquals(0, plain.profile().spellDamage().get(DamageType.COLD), 1e-9);
        // 施法時間 +0.5 秒（加在基礎施法時間上，吃施法速度）：0.7 → 1.2 秒
        assertEquals(24, infused.intervalTicks(new StatSheet()), 1e-9);
        final StatSheet faster = new StatSheet();
        final SkillBuild fast = SkillService.applyInfusedSet(SkillService.applyInfused(
                plain.withProfile(plain.profile().withLocal(List.of(Modifier.increased(tw.exilecore.stats.StatKeys.CAST_SPEED, 1.0, "測試")))),
                resource.infusedFor("cold")), set);
        assertEquals(12, fast.intervalTicks(faster), 1e-9, "施法速度加倍：1.2 秒 → 0.6 秒");
        // 暴擊率 9% + 2% = 11%
        assertEquals(2, crit(infused.profile().local()) - crit(plain.profile().local()), 1e-9);
        assertEquals(9, crit(config.skill("spark").modifiers()), 1e-9);
        // 等級 1、40
        assertEquals(12, SkillService.applyInfusedSet(spark(1), set).projectiles());
        assertEquals(20, set.projectiles().intAt(40));
        assertEquals(2379, SkillProfiles.tableAt(set.damage().get(DamageType.COLD), 40)[0], 1e-9);
        assertEquals(3568, SkillProfiles.tableAt(set.damage().get(DamageType.COLD), 40)[1], 1e-9);
        assertEquals(149, SkillProfiles.tableAt(set.damage().get(DamageType.LIGHTNING), 40)[0], 1e-9);
        assertEquals(2825, SkillProfiles.tableAt(set.damage().get(DamageType.LIGHTNING), 40)[1], 1e-9);
    }

    private static double crit(final List<Modifier> mods) {
        double crit = 0;
        for (final Modifier mod : mods) {
            if (mod.stat().equals("spell.base_crit")) {
                crit += mod.value();
            }
        }
        return crit;
    }

    @Test
    void extraProjectilesStillAddToTheRing() {
        final ResourceSpec.InfusedSet set = config.resource("spark").infusedSet();
        final SkillBuild plain = spark(20);
        final SkillBuild more = plain.withAreaAndProjectiles(plain.areaScale(), plain.projectiles() + 2); // 例如 +2 投射物
        assertEquals(18, SkillService.applyInfusedSet(more, set).projectiles(), "16 + 2");
        assertEquals(SkillBuild.MAX_PROJECTILES, SkillService.applyInfusedSet(plain.withAreaAndProjectiles(1, 15), set).projectiles(),
                "最多 20 顆");
    }

    @Test
    void sameCastCanHitAgainAfterCooldown() {
        final UUID enemy = UUID.randomUUID();
        final UUID other = UUID.randomUUID();
        final Set<UUID> hit = new HashSet<>(Set.of(enemy, other));
        final Map<UUID, Long> clear = new HashMap<>(Map.of(enemy, 114L, other, 120L));
        assertEquals(0, ProjectileEngine.expireHits(hit, clear, 113));
        assertTrue(hit.contains(enemy), "0.66 秒內同一次施放的其他游雷穿過牠");
        assertEquals(1, ProjectileEngine.expireHits(hit, clear, 114));
        assertFalse(hit.contains(enemy), "間隔到了可以再被同一次施放打");
        assertTrue(hit.contains(other));
        assertEquals(Map.of(other, 120L), clear);
    }

    @Test
    void wanderStaysInsideItsLane() {
        // 隨機轉向夾在 ±60 度
        assertEquals(60, ProjectileEngine.steerWander(50, 25, 60, 0, 1, 1), 1e-9);
        assertEquals(-60, ProjectileEngine.steerWander(-50, -25, 60, 0, 1, 1), 1e-9);
        // 已經偏到邊界（+1 格）還要往外：轉回來
        assertEquals(-30, ProjectileEngine.steerWander(20, 10, 60, 1.0, 1, 1), 1e-9);
        // 往內的不用轉
        assertEquals(-30, ProjectileEngine.steerWander(-20, -10, 60, 1.0, 1, 1), 1e-9);
        // 角度方向相反（lateralSign = -1）也一樣
        assertEquals(30, ProjectileEngine.steerWander(-20, -10, 60, 1.0, 1, -1), 1e-9);
        // 邊界內照常亂竄
        assertEquals(35, ProjectileEngine.steerWander(20, 15, 60, 0.5, 1, 1), 1e-9);
    }

    @Test
    void otherSkillsDoNotWander() {
        assertEquals(null, config.skill("fireball").params().wander());
        assertEquals(null, config.resource("arc").infusedSet());
    }
}
