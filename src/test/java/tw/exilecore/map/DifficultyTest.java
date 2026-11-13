package tw.exilecore.map;

import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** 2026-11-12 照站長「新增暗黑破壞神那樣的難度系統」：10 階的表、解鎖規則。 */
class DifficultyTest {

    private static Difficulty.Table table() throws IOException {
        final MapConfig config = new MapConfig();
        config.load(Path.of("src/main/resources/data"));
        return config.difficulty();
    }

    @Test
    void tenTiersMatchTheChosenTable() throws IOException {
        final Difficulty.Table table = table();
        assertEquals(10, table.max());
        final Difficulty.Tier one = table.tier(1);
        assertEquals(1, one.life(), 1e-9, "難度 1 = 現在的樣子");
        assertEquals(1, one.damage(), 1e-9);
        assertEquals(0, one.quantity() + one.rarity() + one.experience(), 1e-9);
        final Difficulty.Tier ten = table.tier(10);
        assertEquals(28.3, ten.life(), 1e-9);
        assertEquals(4.44, ten.damage(), 1e-9);
        assertEquals(135, ten.quantity(), 1e-9);
        assertEquals(225, ten.rarity(), 1e-9);
        assertEquals(135, ten.experience(), 1e-9);
        for (int level = 2; level <= table.max(); level++) {
            assertTrue(table.tier(level).life() > table.tier(level - 1).life(), "生命一階比一階多：" + level);
            assertTrue(table.tier(level).damage() > table.tier(level - 1).damage());
            assertTrue(table.tier(level).quantity() > table.tier(level - 1).quantity());
        }
        assertEquals(table.tier(10), table.tier(99), "超出範圍照最高階");
    }

    @Test
    void trialNeedsThePreviousTierAndOnlyOnce() {
        assertTrue(Difficulty.canChallenge(1, 2, 10));
        assertFalse(Difficulty.canChallenge(1, 3, 10), "要先解鎖 2");
        assertFalse(Difficulty.canChallenge(2, 2, 10), "通關後就不能再打");
        assertFalse(Difficulty.canChallenge(0, 1, 10), "難度 1 不用關卡");
        assertFalse(Difficulty.canChallenge(10, 11, 10));
    }

    @Test
    void trialBossIsACalamityWithShaperKit() throws IOException {
        final MapConfig config = new MapConfig();
        config.load(Path.of("src/main/resources/data"));
        final MapConfig.EventBoss boss = config.eventBoss(Difficulty.TRIAL_KIND);
        assertTrue(boss != null && boss.recommendedDps() > 0 && boss.fixedLevel(), "進階關卡的 Boss 是災厄級");
        final java.util.List<String> kit = boss.boss().skills().stream().map(tw.exilecore.skill.BossSkillUse::id).toList();
        for (final String id : java.util.List.of("shaper_strike", "shaper_beam", "shaper_orbs", "shaper_teleport_slam", "shaper_vortex",
                "shaper_portal", "shaper_bullet_hell", "shaper_clone")) {
            assertTrue(kit.contains(id), "藍王的招：" + id);
        }
        assertEquals(2, boss.boss().phases().size(), "50%、25% 換階段");
    }

    @Test
    void describesATier() throws IOException {
        final String line = DifficultyMenu.describe(table().tier(5));
        assertTrue(line.contains("×4.42") && line.contains("×1.94") && line.contains("+60%") && line.contains("+100%"), line);
    }
}
