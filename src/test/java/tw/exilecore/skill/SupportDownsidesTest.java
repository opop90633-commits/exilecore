package tw.exilecore.skill;

import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import tw.exilecore.stats.ModType;
import tw.exilecore.stats.Modifier;
import tw.exilecore.stats.StatKeys;
import tw.exilecore.stats.SupportExtraKeys;
import tw.exilecore.stats.SupportKeys;

import java.io.IOException;
import java.nio.file.Path;
import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** 2026-11-01 照 PoE2 補上輔助寶石的代價（Pierce I、Overcharge、Overextend、Extraction、See Red、Flame Pillar、Salvo、Tectonic Slams、Haemocrystals、Bone Shrapnel）。 */
class SupportDownsidesTest {

    static SkillConfig config;

    @BeforeAll
    static void load() throws IOException {
        config = new SkillConfig();
        config.load(Path.of("src/main/resources/data"));
    }

    private static double flat(final String support, final String stat) {
        double sum = 0;
        for (final Modifier modifier : config.support(support).modifiers()) {
            if (modifier.stat().equals(stat) && modifier.type() == ModType.FLAT) {
                sum += modifier.value();
            }
        }
        return sum;
    }

    @Test
    void downsidesAreInTheData() {
        assertEquals(20, flat("pierce", SupportExtraKeys.PIERCED_LESS), "Pierce I：穿透之後更少 20%");
        assertTrue(config.support("blazing_volt").flags(SupportExtraKeys.FLAG_SHOCK_REFLECT), "Overcharge：感電反射");
        assertEquals(5, flat("all_in_gamble", SupportExtraKeys.DAZE_SELF_ON_CRIT), "Overextend：暴擊時自己眩暈 5 秒");
        assertEquals(10, flat("blood_erosion", SupportKeys.SACRIFICE_LIFE), "Extraction：獻祭 10% 生命");
        assertEquals(15, flat("blood_linger", SupportExtraKeys.BLEEDER_HASTE), "See Red：流血的敵人加速 15%");
        assertTrue(config.support("blaze_column").flags(SupportExtraKeys.FLAG_CANNOT_IGNITE), "Flame Pillar：不能點燃");
        assertTrue(config.support("sealed_arrows").flags(SupportExtraKeys.FLAG_RANDOM_DIRECTION), "Salvo：隨機方向");
        assertTrue(config.support("blood_crystallize").flags(SupportExtraKeys.FLAG_CANNOT_BLEED), "Haemocrystals：不能流血");
        assertTrue(config.support("shard_crack").flags(SupportExtraKeys.FLAG_CANNOT_PIN), "Bone Shrapnel：不能定身");
        assertEquals(100, flat("shard_crack", SupportProcs.PREFIX + "kill_pinned"), "Bone Shrapnel：打死被定身的敵人時");
        assertEquals(0, flat("shard_crack", SupportProcs.PREFIX + "kill_stunned"), "不再是打死暈眩中的");
        assertTrue(config.support("twin_quake").modifiers().stream().anyMatch(modifier -> modifier.stat().equals(StatKeys.ATTACK_SPEED)
                && modifier.type() == ModType.MORE && modifier.value() < 0), "Tectonic Slams：攻擊速度更慢 15%");
        assertFalse(config.support("blood_erosion").description().contains("魔力消耗更高"));
        assertFalse(config.support("all_in_gamble").description().contains("魔力消耗更高"));
    }

    @Test
    void downsidesHaveText() {
        assertEquals("穿透過敵人之後投射物更少 20% 傷害",
                ModifierText.describe(new Modifier(SupportExtraKeys.PIERCED_LESS, ModType.FLAT, 20, Set.of(), "")));
        assertTrue(ModifierText.describe(new Modifier(SupportProcs.PREFIX + "kill_pinned", ModType.FLAT, 100,
                Set.of("proc_bone_burst"), "")).startsWith("打死被定身的敵人時"));
    }

    @Test
    void randomFanStaysInFrontWithinTheHalfAngle() {
        final double[] rolls = {0.0, 0.5, 0.999, 0.25};
        final int[] next = {0};
        final List<double[]> directions = SkillMath.randomFan(0, 0, 1, 4, 60, () -> rolls[next[0]++]);
        assertEquals(4, directions.size());
        for (final double[] d : directions) {
            assertEquals(1, Math.sqrt(d[0] * d[0] + d[1] * d[1] + d[2] * d[2]), 1e-9, "單位向量");
            assertTrue(d[2] >= Math.cos(Math.toRadians(60)) - 1e-9, "在前方左右 60° 內");
        }
        assertEquals(1, directions.get(1)[2], 1e-9, "擲到 0.5 = 正前方");
        assertEquals(Math.cos(Math.toRadians(60)), directions.get(0)[2], 1e-9, "擲到 0 = 最邊邊");
    }

    @Test
    void salvoFiresInAllDirections() {
        // 2026-11-01 照站長：原作 Salvo 是以角色為中心 360 度全方位隨機
        assertEquals(180, tw.exilecore.stats.SupportExtraKeys.RANDOM_DIRECTION_HALF_ANGLE, 1e-9);
        final List<double[]> back = SkillMath.randomFan(0, 0, 1, 1, 180, () -> 0.0);
        assertEquals(-1, back.get(0)[2], 1e-9, "擲到 0 = 正後方");
    }
}
