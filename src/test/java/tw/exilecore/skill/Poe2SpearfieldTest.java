package tw.exilecore.skill;

import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import tw.exilecore.stats.StatKeys;
import tw.exilecore.stats.StatSheet;
import tw.exilecore.stats.SupportExtraKeys;
import tw.exilecore.stats.TreeMechanicKeys;

import java.io.IOException;
import java.nio.file.Path;
import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 2026-10-27 照站長「遲爆、再布 II 先做成原作」：矛陣改成照 PoE2 0.5 Spearfield（插在地上、敵人碰到才炸的矛），
 * 遲爆（Delayed Reaction）、再布 II（Rearm II）跟冰晶、鐵蒺一樣照原作作用在矛上。
 */
class Poe2SpearfieldTest {

    static SkillConfig config;

    @BeforeAll
    static void load() throws IOException {
        config = new SkillConfig();
        config.load(Path.of("src/main/resources/data"));
    }

    @Test
    void spearfieldIsAConeOfSpearsLikePoe2() {
        final SkillDefinition skill = config.skill("spear_thicket");
        assertNotNull(skill);
        assertEquals(Mechanic.CONE, skill.mechanic(), "照 PoE2：面前扇形冒出矛（這次之前準心處持續打的範圍）");
        assertEquals(Spearfield.SIGNATURE, config.signature("spear_thicket"));
        assertTrue(OriginalSkills.SIGNATURES.contains(Spearfield.SIGNATURE), "整個交給 OriginalSkills（Spearfield）");
        assertTrue(skill.tags().contains(TreeMechanicKeys.HAZARD), "矛是危險物件");
        final StatSheet sheet = new StatSheet();
        // 扇形長度照 RePoE（1 級 4 公尺、20 級 6.9 公尺、40 級 9.9 公尺）
        assertEquals(4.0, SkillBuild.of(config, skill, 1, 1, false, List.of(), Set.of("spear"), sheet).length(), 1e-9);
        assertEquals(6.9, SkillBuild.of(config, skill, 20, 90, false, List.of(), Set.of("spear"), sheet).length(), 1e-9);
        assertEquals(9.9, skill.params().length().at(40), 1e-9);
        // 傷害：炸開的攻擊傷害 50%（1 級）～204%（20 級）
        assertEquals(0.5, skill.effectiveness().at(1), 1e-9);
        assertEquals(2.04, skill.effectiveness().at(20), 1e-9);
        final StatSheet effective = SkillBuild.of(config, skill, 1, 1, false, List.of(), Set.of("spear"), sheet).profile().effective(sheet);
        assertEquals(100, effective.flat(StatKeys.MAIM_CHANCE, skill.tags()), 1e-9, "擊中一定癱瘓");
    }

    @Test
    void spearCountDurationAndEarlyPenalty() {
        // 每 0.2 公尺扇形長度一支，最多同時 70 支
        assertEquals(20, Spearfield.count(4.0));
        assertEquals(34, Spearfield.count(6.9));
        assertEquals(49, Spearfield.count(9.9));
        assertEquals(70, Spearfield.count(30));
        // 插 10 秒；遲爆更少 70%
        assertEquals(200, Spearfield.duration(1, false));
        assertEquals(60, Spearfield.duration(1, true));
        assertEquals(300, Spearfield.duration(1.5, false));
        // 插下 0.5 秒內被敵人碰炸：更少 80%；遲爆時間到自己炸的不算
        assertEquals(0.2, Spearfield.damageScale(100, 105, true), 1e-9);
        assertEquals(1.0, Spearfield.damageScale(100, 110, true), 1e-9);
        assertEquals(1.0, Spearfield.damageScale(100, 105, false), 1e-9);
        assertEquals(0.6, Spearfield.BLAST, 1e-9);
    }

    @Test
    void delayedReactionAndRearmWorkOnSpears() {
        final SkillDefinition skill = config.skill("spear_thicket");
        final SupportDefinition delayed = config.support("delayed_reaction");
        final SupportDefinition rearm = config.support("rearm_ii");
        assertNull(delayed.refusal(skill), "遲爆可以輔助矛陣");
        assertNull(rearm.refusal(skill), "再布 II 可以輔助矛陣");
        final StatSheet sheet = new StatSheet();
        final SkillBuild build = SkillBuild.of(config, skill, 20, 90, false, List.of(delayed, rearm), Set.of("spear"), sheet);
        final StatSheet effective = build.profile().effective(sheet);
        assertEquals(15, effective.flat(SupportExtraKeys.DELAYED_REACTION, skill.tags()), 1e-9);
        assertEquals(30, effective.flat(SupportExtraKeys.REARM, skill.tags()), 1e-9);
        // 不再是「持續時間更少 70%、每一下更多 283%」的平衡等效：輔助不改技能本身的持續時間，矛自己照原作處理
        assertEquals(1.0, build.durationScale(), 1e-9);
        assertTrue(delayed.description().contains("矛陣的矛") && rearm.description().contains("矛陣的矛"));
    }
}
