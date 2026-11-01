package tw.exilecore.skill;

import org.junit.jupiter.api.Test;
import tw.exilecore.ailment.AilmentState;
import tw.exilecore.stats.DamagePacket;
import tw.exilecore.stats.DamageType;
import tw.exilecore.stats.Modifier;
import tw.exilecore.stats.SkillProfile;
import tw.exilecore.stats.StatKeys;
import tw.exilecore.stats.Tags;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** 2026-11-01 照 PoE2 0.5：熾星彈、焰幕、流焰天幕、炎核爆、焚流補上的機制（{@link FireSpells}）。 */
class FireSpellsTest {

    @Test
    void fireballRingIsTwelveEvenBolts() {
        final List<double[]> ring = FireSpells.ring(FireSpells.RING_BOLTS, 0);
        assertEquals(12, ring.size());
        double sx = 0;
        double sz = 0;
        for (final double[] d : ring) {
            assertEquals(1.0, Math.hypot(d[0], d[1]), 1e-9);
            sx += d[0];
            sz += d[1];
        }
        assertEquals(0, sx, 1e-9); // 平均分佈：加起來是 0
        assertEquals(0, sz, 1e-9);
        assertEquals(6.0, FireSpells.RING_RANGE_METRES, 1e-9); // 原作 120 − 60
    }

    @Test
    void ringProfileDropsMarkerAndAddsIgniteMore() {
        final SkillProfile base = new SkillProfile("blazing_star_shell", Set.of(Tags.SPELL, Tags.FIRE), 1.0, new DamagePacket().set(DamageType.FIRE, 10),
                new DamagePacket().set(DamageType.FIRE, 15), Map.of(), List.of(Modifier.flat(FireSpells.FIREBALL_RING, 1, "灌注")));
        final SkillProfile ring = FireSpells.ringProfile(base);
        assertTrue(ring.local().stream().noneMatch(m -> m.stat().equals(FireSpells.FIREBALL_RING)));
        assertTrue(ring.local().stream().anyMatch(m -> m.stat().equals(StatKeys.IGNITE_CHANCE) && Math.abs(m.value() - 1.0) < 1e-9));
        assertEquals(10, ring.spellDamage().get(DamageType.FIRE), 1e-9);
    }

    @Test
    void flameWallAddedDamageByLevel() {
        final double[] l20 = FireSpells.wallAdded(20, false);
        assertEquals(27, l20[0], 1e-9);
        assertEquals(41, l20[1], 1e-9);
        assertEquals(0, l20[3], 1e-9);
        final double[] infused = FireSpells.wallAdded(20, true);
        assertEquals(3, infused[2], 1e-9);
        assertEquals(65, infused[3], 1e-9);
        final double[] l40 = FireSpells.wallAdded(40, true);
        assertEquals(102, l40[0], 1e-9);
        assertEquals(153, l40[1], 1e-9);
        assertEquals(13, l40[2], 1e-9);
        assertEquals(242, l40[3], 1e-9);
        assertEquals(4, FireSpells.wallMods(infused).size());
        assertEquals(2, FireSpells.wallMods(l20).size());
    }

    @Test
    void projectileCrossesWall() {
        final List<double[]> wall = List.of(new double[] {0, 64, 0}, new double[] {1, 64, 0}, new double[] {2, 64, 0});
        // 從牆前面飛到牆後面（高度 1.5 格）
        assertTrue(FireSpells.crosses(new double[] {1, 65.5, -2}, new double[] {1, 65.5, 2}, wall, 1.0));
        // 從旁邊飛過去、離牆太遠
        assertFalse(FireSpells.crosses(new double[] {6, 65.5, -2}, new double[] {6, 65.5, 2}, wall, 1.0));
        // 從牆頂上很高的地方飛過去
        assertFalse(FireSpells.crosses(new double[] {1, 70, -2}, new double[] {1, 70, 2}, wall, 1.0));
    }

    @Test
    void firestormTimingAndRadius() {
        assertEquals(4, FireSpells.boltTicks(false)); // 0.2 秒
        assertEquals(2, FireSpells.boltTicks(true)); // 火灌注頻率增加 100%
        assertEquals(3.0, FireSpells.stormRadius(false), 1e-9);
        assertEquals(4.5, FireSpells.stormRadius(true), 1e-9);
        final Map<UUID, Long> last = new HashMap<>();
        final UUID enemy = UUID.randomUUID();
        assertTrue(FireSpells.gate(last, enemy, 100));
        assertFalse(FireSpells.gate(last, enemy, 102)); // 0.15 秒內不能再打
        assertTrue(FireSpells.gate(last, enemy, 103));
    }

    @Test
    void iceBoltProfileUsesOwnTableAndCrit() {
        final SkillProfile base = new SkillProfile("flowing_flame_canopy", Set.of(Tags.SPELL, Tags.FIRE), 1.0, new DamagePacket().set(DamageType.FIRE, 111),
                new DamagePacket().set(DamageType.FIRE, 167), Map.of(), List.of(Modifier.flat(StatKeys.SPELL_BASE_CRIT, 7, "技能"),
                Modifier.increased(StatKeys.DAMAGE, 1.0, "火灌注")));
        final SkillProfile ice = FireSpells.boltProfile(base, DamageType.COLD, FireSpells.ICE_BOLTS, 20, FireSpells.ICE_CRIT);
        assertEquals(95, ice.spellDamage().get(DamageType.COLD), 1e-9);
        assertEquals(143, ice.spellMax().get(DamageType.COLD), 1e-9);
        assertEquals(0, ice.spellDamage().get(DamageType.FIRE), 1e-9);
        assertEquals(12, ice.local().stream().filter(m -> m.stat().equals(StatKeys.SPELL_BASE_CRIT)).mapToDouble(Modifier::value).sum(), 1e-9);
        assertTrue(ice.local().stream().anyMatch(m -> m.stat().equals(StatKeys.DAMAGE))); // 火灌注的傷害增加也給冰彈
        final SkillProfile bolt = FireSpells.boltProfile(base, DamageType.LIGHTNING, FireSpells.LIGHTNING_BOLTS, 20, FireSpells.LIGHTNING_CRIT);
        assertEquals(16, bolt.spellDamage().get(DamageType.LIGHTNING), 1e-9);
        assertEquals(302, bolt.spellMax().get(DamageType.LIGHTNING), 1e-9);
    }

    @Test
    void flameblastRadiusPerStage() {
        assertEquals(1.0, FireSpells.stageRadiusScale(1, 1), 1e-9);
        assertEquals(10.0, FireSpells.stageRadiusScale(1, 10), 1e-9); // 0.6 公尺 × 10 層 = 6 公尺
        assertEquals(1.0, FireSpells.stageRadiusScale(0, 10), 1e-9);
        final double[] hit = FireSpells.rayDistance(new double[] {0, 0, 0}, new double[] {1, 0, 0}, new double[] {10, 1, 0});
        assertNotNull(hit);
        assertEquals(10, hit[0], 1e-9);
        assertEquals(1, hit[1], 1e-9);
        assertNull(FireSpells.rayDistance(new double[] {0, 0, 0}, new double[] {1, 0, 0}, new double[] {-5, 0, 0}));
    }

    @Test
    void compoundingIgnitesAddUpWithinOneUse() {
        final AilmentState state = new AilmentState();
        final UUID source = UUID.randomUUID();
        state.addCompoundingIgnite(10, 100, source, false, 7);
        state.addCompoundingIgnite(10, 120, source, false, 7);
        assertEquals(20, state.ignite(50).dps(), 1e-9); // 同一次引導：加在一起
        assertEquals(10, state.ignite(110).dps(), 1e-9); // 第一次結束了，剩第二次
        state.addCompoundingIgnite(15, 200, source, false, 8); // 另一次引導：不加在一起，最強的那個在燒
        assertEquals(15, state.ignite(110).dps(), 1e-9);
        assertNull(state.ignite(300));
    }

    @Test
    void describesMarkers() {
        assertNotNull(FireSpells.describe(FireSpells.FIREBALL_RING, 1));
        assertNotNull(FireSpells.describe(FireSpells.FLAG_COMPOUNDING, 1));
        assertNull(FireSpells.describe("damage", 1));
    }

    @Test
    void burningScalesLikeADot() {
        final SkillProfile wall = new SkillProfile("flame_curtain", Set.of(Tags.SPELL, Tags.AREA, Tags.FIRE), 1.0, new DamagePacket().set(DamageType.FIRE, 5),
                new DamagePacket().set(DamageType.FIRE, 8), Map.of(), List.of());
        assertEquals(1.75, FireSpells.burnDps(null, wall, 105, 1.0), 1e-9); // 原作 105 每分鐘
        final tw.exilecore.stats.StatSheet sheet = new tw.exilecore.stats.StatSheet().overlay(List.of(
                new Modifier(StatKeys.DAMAGE, tw.exilecore.stats.ModType.INCREASED, 1.0, Set.of(Tags.FIRE), "火焰傷害"),
                Modifier.more(tw.exilecore.stats.SupportKeys.DAMAGE_SKILL_DOT, 0.4, "非異常持續傷害")));
        assertEquals(1.75 * 2 * 1.4, FireSpells.burnDps(sheet, wall, 105, 1.0), 1e-9);
        assertEquals(0, FireSpells.burnDps(sheet, wall, 0, 1.0), 1e-9);
        assertEquals(0.25 / 0.075, FireSpells.incinerateIgnites(0.25), 1e-9); // 每 0.075 秒點燃一次
    }
}
