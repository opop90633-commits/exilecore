package tw.exilecore.skill;

import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import tw.exilecore.core.Units;
import tw.exilecore.stats.ModType;
import tw.exilecore.stats.Modifier;
import tw.exilecore.stats.SupportKeys;

import java.io.IOException;
import java.nio.file.Path;
import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** 2026-10-29 照站長加回 PoE2 Momentum（衝勢）：出手前一次動作時間內移動 2 公尺（3 格），更多 40% 傷害。 */
class MomentumSupportTest {

    static SkillConfig config;

    @BeforeAll
    static void load() throws IOException {
        config = new SkillConfig();
        config.load(Path.of("src/main/resources/data"));
    }

    @Test
    void definedLikePoe2() {
        final SupportDefinition momentum = config.support("momentum");
        assertNotNull(momentum, "衝勢要能切出來");
        assertEquals("衝勢", momentum.name());
        assertEquals(2, momentum.tier());
        assertEquals(1.2, momentum.manaMultiplier(), 1e-9);
        assertTrue(momentum.modifiers().stream().anyMatch(m -> m.stat().equals(SupportKeys.MOMENTUM) && m.value() == 40));
        assertEquals(3.0, SupportMechanics.MOMENTUM_TRAVEL, 1e-9, "2 公尺 = 3 格");
    }

    @Test
    void movementCountsOnlyDuringTheUse() {
        assertEquals(2.5, SupportMechanics.momentumMoved(new double[] {10, -1, 0}, 12.5), 1e-9, "還在用：開始到現在");
        assertEquals(4, SupportMechanics.momentumMoved(new double[] {10, 4, 0}, 30), 1e-9, "用完了：停在用完那時候走的距離");
        assertEquals(0, SupportMechanics.momentumMoved(new double[] {10, -1, 0}, 9), 1e-9);
    }

    @Test
    void hitAndRunLikePoe2() {
        final SupportDefinition hitRun = config.support("hit_and_run");
        assertNotNull(hitRun);
        assertEquals(1.0, hitRun.manaMultiplier(), 1e-9, "原作沒有消耗加成");
        assertTrue(hitRun.modifiers().stream().anyMatch(m -> m.stat().equals(SupportKeys.HIT_AND_RUN) && m.value() == 10), "移動 10 公尺");
        assertTrue(hitRun.modifiers().stream().anyMatch(m -> m.stat().equals("attack.speed") && m.type() == ModType.MORE && (Math.abs(m.value() - 40) < 1e-9 || Math.abs(m.value() - 0.4) < 1e-9)));
        final String text = ModifierText.describe(hitRun.modifiers().stream().filter(m -> m.stat().equals(SupportKeys.HIT_AND_RUN)).findFirst().orElseThrow());
        assertTrue(text.contains("15 格"), text);
        for (final SkillDefinition skill : config.all()) {
            if (SupportGuards.conditional(skill)) {
                assertNotNull(hitRun.refusal(skill), skill.id() + "：本來就有使用條件（要榮耀、連擊）的不行");
            }
        }
    }

    @Test
    void tooltip() {
        final String text = ModifierText.describe(new Modifier(SupportKeys.MOMENTUM, ModType.FLAT, 40, Set.of(), "衝勢"));
        assertTrue(text.contains("3 格") && text.contains("40%") && text.contains("單次使用"), text);
    }
}
