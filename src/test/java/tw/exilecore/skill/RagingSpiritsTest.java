package tw.exilecore.skill;

import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import tw.exilecore.stats.StatSheet;
import tw.exilecore.stats.Tags;

import java.io.IOException;
import java.nio.file.Path;
import java.util.List;
import java.util.Set;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** 2026-11-01 照 PoE2 Raging Spirits：火焰法術叫幾隻焚魂（範圍照半徑、投射物打中、一次最多 2 隻）。 */
class RagingSpiritsTest {

    static SkillConfig config;

    @BeforeAll
    static void load() throws IOException {
        config = new SkillConfig();
        config.load(Path.of("src/main/resources/data"));
    }

    private static SkillBuild build(final String id) {
        return SkillBuild.of(config, config.skill(id), 20, 100, false, List.of(), Set.of(Tags.WAND), new StatSheet());
    }

    @Test
    void onePerOneAndHalfMetresUpToTwo() {
        assertEquals(0, RagingSpirits.perRadius(0));
        assertEquals(0, RagingSpirits.perRadius(1.4));
        assertEquals(1, RagingSpirits.perRadius(1.5));
        assertEquals(1, RagingSpirits.perRadius(2.9));
        assertEquals(2, RagingSpirits.perRadius(3.0));
        assertEquals(2, RagingSpirits.perRadius(12), "一次最多 2 隻");
    }

    @Test
    void spellRules() {
        final SkillDefinition fireball = config.skill("blazing_star_shell");
        assertTrue(RagingSpirits.fireSpell(fireball));
        assertTrue(RagingSpirits.onHit(fireball), "投射物法術：打中才叫");
        assertEquals(0, RagingSpirits.onCast(fireball, RagingSpirits.areaRadius(build("blazing_star_shell"))));

        final SkillBuild big = build("flame_core_detonation");
        // 2026-11-01 照 PoE2 Flameblast：半徑是放開時的 0.6 公尺 × 層數（Signatures.released 改好的 build 才交給 fireSpellUsed）
        assertEquals(0, RagingSpirits.onCast(big.definition(), RagingSpirits.areaRadius(big)), "沒有層數：0.6 格不到 1.5 格不叫");
        assertEquals(2, RagingSpirits.onCast(big.definition(), RagingSpirits.areaRadius(big) * 10), "滿 10 層 6 格：兩隻");

        final SkillBuild small = build("herald_ash_burst");
        assertEquals(0, RagingSpirits.onCast(small.definition(), RagingSpirits.areaRadius(small)), "半徑 1.2 格：不到 1.5 格不叫");

        final SkillBuild wall = build("flame_curtain");
        assertEquals(wall.length() / 2, RagingSpirits.areaRadius(wall), 1e-9, "火牆用牆長的一半當半徑");

        assertFalse(RagingSpirits.fireSpell(config.skill("summon_burning_souls")), "召喚焚魂本身不是會叫焚魂的法術");
    }

    @Test
    void projectileBudgetTwoPerCast() {
        final RagingSpirits.Budget budget = new RagingSpirits.Budget();
        final UUID owner = UUID.randomUUID();
        assertFalse(budget.take(owner, "blazing_star_shell", 0), "沒放過不叫");
        budget.arm(owner, "blazing_star_shell", 10);
        assertTrue(budget.take(owner, "blazing_star_shell", 12));
        assertTrue(budget.take(owner, "blazing_star_shell", 13));
        assertFalse(budget.take(owner, "blazing_star_shell", 14), "一次施放最多 2 隻");
        assertFalse(budget.take(owner, "starfire_bead_chain", 14), "別的技能的投射物不算這一次");
        budget.arm(owner, "blazing_star_shell", 20);
        assertFalse(budget.take(owner, "blazing_star_shell", 20 + RagingSpirits.PROJECTILE_WINDOW_TICKS + 1), "太久以前放的不算");
    }
}
