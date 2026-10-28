package tw.exilecore.skill;

import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import tw.exilecore.stats.StatKeys;
import tw.exilecore.stats.StatSheet;

import java.io.IOException;
import java.nio.file.Path;
import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** 2026-10-26：弩的原作機制（逐級修飾器、留在地上的彈、一次射出存著的彈）。 */
class CrossbowPoe2Test {

    static SkillConfig config;

    @BeforeAll
    static void load() throws IOException {
        config = new SkillConfig();
        config.load(Path.of("src/main/resources/data"));
    }

    private static StatSheet effective(final String id, final int level) {
        final StatSheet sheet = new StatSheet();
        final SkillBuild build = SkillBuild.of(config, config.skill(id), level, 100, false, List.of(), Set.of("crossbow"), sheet);
        return build.profile().effective(sheet);
    }

    @Test
    void levelModifiersFollowTheGemLevel() {
        assertEquals(2, effective("permafrost_bolts", 1).flat(StatKeys.SKILL_PIERCE, Set.of()), 1e-9, "1 級穿透 2");
        assertEquals(4, effective("permafrost_bolts", 20).flat(StatKeys.SKILL_PIERCE, Set.of()), 1e-9, "20 級穿透 4");
        assertEquals(1, effective("armour_piercing_bolt", 1).flat(StatKeys.ARMOUR_BREAK_FLAT, Set.of()), 1e-9);
        assertEquals(845, effective("armour_piercing_bolt", 20).flat(StatKeys.ARMOUR_BREAK_FLAT, Set.of()), 1e-9);
        assertEquals(1 + 5.04, effective("permafrost_bolts", 20).apply(StatKeys.FREEZE_BUILDUP, 1.0, Set.of("cold")), 1e-9,
                "20 級 504% 更多凍結累積");
        assertTrue(config.levelModifiers("fireball").isEmpty());
    }

    @Test
    void stormblastAndIceShardsLodge() {
        final SkillConfig.LodgeSpec storm = config.lodge("stormblast_bolts");
        assertNotNull(storm);
        assertFalse(storm.proximity(), "伏霆彈要被引爆");
        assertEquals(3, storm.count());
        assertEquals(30, storm.max());
        assertNotNull(config.skill(storm.skill()));
        final SkillConfig.LodgeSpec shards = config.lodge("frost_bolt_round");
        assertTrue(shards.proximity(), "冰晶是地雷");
        assertEquals(1.5, shards.arm(), 1e-9);
        assertEquals(16, shards.max());
        assertEquals(400, shards.rampMore(), 1e-9);
        assertNull(config.lodge("fireball"));
    }

    @Test
    void hailstormFiresItsStore() {
        final SkillConfig.RainSpec rain = config.rain("hail_rounds");
        assertNotNull(rain);
        assertEquals(32, rain.store().intAt(1));
        assertEquals(50, rain.store().intAt(20));
        assertEquals(tw.exilecore.core.Units.metres(4), rain.area(), 1e-9); // 2026-10-29 照站長：1 公尺 = 1.5 格（Units），期望值照原作公尺換算
        assertEquals(1, config.clip("hail_rounds", 20), "放一次就要重裝");
    }

    @Test
    void procSkillsExist() {
        for (final String id : List.of("proc_stormblast_burst", "proc_ice_shard_burst", "proc_shockburst_pulse", "proc_galvanic_beam",
                "proc_permafrost_shatter", "proc_incendiary_shatter")) {
            assertNotNull(config.skill(id), id);
            assertTrue(config.skill(id).hidden(), id);
        }
        // 2026-10-28 照 PoE2：碎裂錐形有自己的表（基礎攻擊傷害 100%，不隨等級），parent_damage: own；這次之前 1.0（跟那一發一樣）
        assertEquals(SkillConfig.PARENT_OWN_TABLE, config.parentDamage("proc_permafrost_shatter"), 1e-9);
        final SkillDefinition bolt = config.skill("permafrost_bolts");
        assertEquals(1.0 / bolt.effectiveness().at(1), config.parentShare("proc_permafrost_shatter", bolt, 1), 1e-9);
    }
}
