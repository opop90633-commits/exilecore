package tw.exilecore.skill;

import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import tw.exilecore.currency.GemQuality;
import tw.exilecore.stats.DamageType;
import tw.exilecore.stats.GemQualityKeys;
import tw.exilecore.stats.StatSheet;
import tw.exilecore.stats.Tags;

import java.io.IOException;
import java.nio.file.Path;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.List;
import java.util.Set;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** 2026-10-28 照 PoE2 Decompose（腐屍爆）：毒雲的半徑、上限、中毒、品質（{@link DecomposeClouds}）。 */
class DecomposePoe2Test {

    static SkillConfig config;

    @BeforeAll
    static void load() throws IOException {
        config = new SkillConfig();
        config.load(Path.of("src/main/resources/data"));
        GemQuality.load(Path.of("src/main/resources/data"));
    }

    @Test
    void cloudGrowsTwentyPercentPerSecondUpToEighty() {
        assertEquals(1.6, DecomposeClouds.radius(1.6, 0), 1e-9);
        assertEquals(1.6 * 1.2, DecomposeClouds.radius(1.6, 20), 1e-9);
        assertEquals(1.6 * 1.5, DecomposeClouds.radius(1.6, 50), 1e-9);
        assertEquals(1.6 * 1.8, DecomposeClouds.radius(1.6, 80), 1e-9);
        assertEquals(1.6 * 1.8, DecomposeClouds.radius(1.6, 200), 1e-9); // 最多更多 80%
        assertEquals(1.6, DecomposeClouds.radius(1.6, -5), 1e-9);
    }

    @Test
    void poisonIsTenPercentOfCorpseLife() {
        assertEquals(250, DecomposeClouds.poisonBase(2500), 1e-9);
        assertEquals(0, DecomposeClouds.poisonBase(-1), 1e-9);
        assertTrue(DecomposeClouds.poisonDue(0));
        assertFalse(DecomposeClouds.poisonDue(10));
        assertTrue(DecomposeClouds.poisonDue(40));
    }

    @Test
    void sixCloudsOldestRemoved() {
        assertEquals(6, DecomposeClouds.limit(0));
        assertEquals(7, DecomposeClouds.limit(1));
        final Deque<Integer> mine = new ArrayDeque<>(List.of(1, 2, 3, 4, 5, 6));
        assertEquals(List.of(1), DecomposeClouds.makeRoom(mine, 6));
        mine.addLast(7);
        assertEquals(List.of(2, 3, 4, 5, 6, 7), new ArrayList<>(mine));
        assertEquals(List.of(), DecomposeClouds.makeRoom(new ArrayDeque<>(List.of(1, 2)), 6));
        assertTrue(SkillLimits.SKILLS.contains("decompose"));
        assertTrue(config.skill("decompose").tags().contains(SkillLimits.TAG));
    }

    @Test
    void qualityAddsDuration() {
        assertEquals(4, DecomposeClouds.seconds(0), 1e-9);
        final SkillDefinition decompose = config.skill("decompose");
        final SkillBuild q20 = SkillBuild.of(config, decompose, 20, 90, false, List.of(), Set.of(Tags.SPELL), new StatSheet(), Set.of(),
                SkillService.qualityModifiers(decompose, 20));
        final double extra = SkillZones.localFlat(q20.profile(), GemQualityKeys.DURATION_FLAT);
        assertEquals(1.0, extra, 1e-9);
        assertEquals(100, q20.durationTicks(DecomposeClouds.seconds(extra)));
        assertEquals("品質 +20%：毒雲持續時間 +1 秒", SkillService.qualityText(decompose, 20));
        assertEquals(tw.exilecore.core.Units.metres(1.6), q20.radius(), 1e-9); // 2026-10-29 照站長：1 公尺 = 1.5 格（Units），期望值照原作公尺換算
        assertTrue(q20.definition().tags().contains(Tags.CHAOS));
        assertTrue(DecomposeClouds.is(q20));
    }

    @Test
    void burningGroundCountsEvenUnderOtherGround() {
        final UUID world = UUID.randomUUID();
        final List<ElementalGround.Surface> list = new ArrayList<>();
        ElementalGround.add(list, new ElementalGround.Surface(world, 0, 64, 0, 2, DamageType.FIRE, 100));
        ElementalGround.add(list, new ElementalGround.Surface(world, 0, 64, 0, 2, DamageType.COLD, 100));
        assertTrue(ElementalGround.touchesAny(list, world, 3, 64, 0, 1.5, DamageType.FIRE, 10));
        assertFalse(ElementalGround.touchesAny(list, world, 5, 64, 0, 1.5, DamageType.FIRE, 10));
        assertFalse(ElementalGround.touchesAny(list, world, 0, 64, 0, 1.5, DamageType.FIRE, 100)); // 過期
    }
}
