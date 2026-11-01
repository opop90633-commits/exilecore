package tw.exilecore.skill;

import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import tw.exilecore.stats.Tags;
import tw.exilecore.stats.TreeMechanicKeys;

import java.io.IOException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 2026-11-01 照 PoE2：撒蒺（Caltrops → TriggeredCaltropsPlayer）的真鐵蒺、咒爆（Impending Doom → DoomBlastPlayer）的可存次數（storedUses）。
 */
class CaltropsDoomStoredUsesTest {

    static SkillConfig config;

    @BeforeAll
    static void load() throws IOException {
        config = new SkillConfig();
        config.load(Path.of("src/main/resources/data"));
    }

    // ---- 撒蒺 ----

    @Test
    void caltropCapRemovesOldest() {
        final List<Integer> list = new ArrayList<>();
        for (int i = 0; i < OriginalSkills.SPEAR_CALTROP_MAX; i++) {
            assertTrue(OriginalSkills.pushCapped(list, i, OriginalSkills.SPEAR_CALTROP_MAX).isEmpty());
        }
        assertEquals(20, list.size());
        assertEquals(List.of(0), OriginalSkills.pushCapped(list, 20, OriginalSkills.SPEAR_CALTROP_MAX), "滿 20 顆再撒：拿掉最舊的");
        assertEquals(20, list.size());
        assertEquals(1, list.getFirst());
        assertEquals(20, list.getLast());
    }

    @Test
    void caltropLandsWithinThreeMetres() {
        assertEquals(3.0, OriginalSkills.SPEAR_CALTROP_SCATTER, 1e-9);
        assertEquals(0.5, OriginalSkills.SPEAR_CALTROP_RADIUS, 1e-9);
        assertEquals(160, OriginalSkills.SPEAR_CALTROP_TICKS, "8 秒");
        for (final double u : new double[]{0, 0.3, 1}) {
            for (final double v : new double[]{0, 0.25, 0.7}) {
                final double[] offset = OriginalSkills.scatterOffset(u, v, 3);
                assertTrue(Math.hypot(offset[0], offset[1]) <= 3 + 1e-9);
            }
        }
        assertEquals(3, Math.hypot(OriginalSkills.scatterOffset(1, 0.4, 3)[0], OriginalSkills.scatterOffset(1, 0.4, 3)[1]), 1e-9);
    }

    @Test
    void caltropIsParentScaledAttackHazard() {
        final SkillDefinition caltrop = config.skill(tw.exilecore.stats.SupportRemakeKeys.CALTROPS_PROC);
        assertTrue(caltrop.isAttack());
        assertTrue(caltrop.tags().containsAll(List.of(Tags.ATTACK, Tags.AREA, Tags.PROJECTILE, TreeMechanicKeys.HAZARD)));
        assertFalse(caltrop.tags().contains(Tags.SPELL));
        assertEquals(0.75, config.parentDamage(caltrop.id()), 1e-9, "原技能攻擊傷害的 75%");
        assertTrue(caltrop.modifiers().stream().anyMatch(m -> m.stat().equals("maim.chance") && m.value() >= 100), "致殘");
    }

    // ---- 咒爆 ----

    @Test
    void doomBlastStoredUsesTable() {
        final String id = "proc_impending_doom";
        assertEquals(3, config.cooldownUses(id, 1));
        assertEquals(3, config.cooldownUses(id, 9));
        assertEquals(4, config.cooldownUses(id, 10));
        assertEquals(4, config.cooldownUses(id, 20));
        assertEquals(5, config.cooldownUses(id, 21));
        assertEquals(5, config.cooldownUses(id, 32));
        assertEquals(6, config.cooldownUses(id, 33));
        assertEquals(6, config.cooldownUses(id, 39));
        assertEquals(7, config.cooldownUses(id, 40));
        assertEquals(0.15, config.skill(id).cooldown(), 1e-9);
        assertEquals(1, config.cooldownUses("proc_aftershock", 20), "沒寫的照舊 1 次");
    }

    @Test
    void storedUsesSpendAndRechargeOneAtATime() {
        final long[] slots = new long[3];
        assertTrue(StoredUses.use(slots, 100, 3));
        assertTrue(StoredUses.use(slots, 100, 3));
        assertTrue(StoredUses.use(slots, 100, 3));
        assertFalse(StoredUses.use(slots, 100, 3), "3 次用完：這次不觸發");
        assertEquals(0, StoredUses.available(slots, 100));
        assertEquals(1, StoredUses.available(slots, 103), "一次充一次：0.15 秒後回 1 次");
        assertEquals(2, StoredUses.available(slots, 106));
        assertEquals(3, StoredUses.available(slots, 109));
        assertTrue(StoredUses.use(slots, 103, 3));
        assertFalse(StoredUses.use(slots, 103, 3));
    }

    @Test
    void storedUsesResizeKeepsEarliest() {
        assertArrayEquals(new long[]{0, 0, 0}, StoredUses.resize(null, 3));
        assertArrayEquals(new long[]{5, 9, 12, 0}, StoredUses.resize(new long[]{9, 5, 12}, 4), "多的次數一開始就是好的");
        assertArrayEquals(new long[]{5, 9}, StoredUses.resize(new long[]{9, 12, 5}, 2), "少了丟掉最晚好的");
    }
}
