package tw.exilecore.skill;

import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import tw.exilecore.ailment.AilmentService;
import tw.exilecore.stats.Modifier;
import tw.exilecore.stats.SkillMechanicKeys;
import tw.exilecore.stats.StatKeys;
import tw.exilecore.stats.StatSheet;

import java.io.IOException;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** 2026-10-28 照 PoE2 Contagion（瘟疫）：單體、傳染半徑 1.7 格 × 範圍、品質多感染幾隻、名稱。 */
class ContagionPoe2Test {

    static SkillConfig config;

    @BeforeAll
    static void load() throws IOException {
        config = new SkillConfig();
        config.load(Path.of("src/main/resources/data"));
    }

    @Test
    void nameAndSingleTarget() {
        final SkillDefinition contagion = config.skill("contagion");
        assertEquals("瘟疫", contagion.name());
        assertEquals(1, contagion.params().targets(), "使一名敵人");
        assertEquals(1.7, contagion.params().radius().at(1), 1e-9);
        assertTrue(contagion.modifiers().stream().anyMatch(m -> m.stat().equals(SkillMechanicKeys.DEBUFF_SPREAD_RADIUS) && m.value() == 1.7));
        // 其他 impact 技能沒寫 targets 照舊打一整圈
        final long single = config.all().stream().filter(s -> s.mechanic() == Mechanic.IMPACT && s.params().targets() > 0).count();
        assertEquals(1, single, "目前只有瘟疫是單體 impact");
    }

    @Test
    void picksAimedThenNearestAroundIt() {
        final Map<String, List<String>> around = Map.of("a", List.of("a", "b", "c", "d"), "x", List.of("x", "y"));
        assertEquals(List.of("a"), SkillExecutor.singleTargets("a", List.of("x"), around::get, 1));
        assertEquals(List.of("a", "b", "c"), SkillExecutor.singleTargets("a", List.of("x"), around::get, 3), "品質 20%：多 2 隻");
        assertEquals(List.of("x", "y"), SkillExecutor.singleTargets(null, List.of("x", "a"), around::get, 3), "沒指到：準心附近最近的");
        assertEquals(List.of(), SkillExecutor.singleTargets(null, List.<String>of(), around::get, 3), "沒有敵人就不放");
    }

    @Test
    void spreadRadiusScalesWithArea() {
        final StatSheet sheet = new StatSheet();
        final SkillBuild plain = SkillBuild.of(config, config.skill("contagion"), 20, 20, false, List.of(), Set.of(), sheet);
        assertEquals(1.7, AilmentService.spreadRadius(plain.profile().effective(sheet), plain.profile()), 1e-9);
        final StatSheet bigger = new StatSheet();
        bigger.add(Modifier.increased(StatKeys.SKILL_AREA, 0.5, "測試"));
        final SkillBuild wide = SkillBuild.of(config, config.skill("contagion"), 20, 20, false, List.of(), Set.of(), bigger);
        assertEquals(1.7 * Math.sqrt(1.5), AilmentService.spreadRadius(wide.profile().effective(bigger), wide.profile()), 1e-9);
    }
}
