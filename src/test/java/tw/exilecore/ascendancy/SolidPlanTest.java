package tw.exilecore.ascendancy;

import org.junit.jupiter.api.Test;
import tw.exilecore.skill.SkillConfig;
import tw.exilecore.stats.ModType;
import tw.exilecore.stats.Modifier;
import tw.exilecore.stats.StatKeys;

import java.io.IOException;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 2026-11-08 照站長「照原作改」：星紋弩衛的萬全之策（PoE2 Tactician A Solid Plan：Persistent Buffs have 50% less Reservation）
 * 只給同時是常駐、增益的技能（光環、焚魂、蟲噬群…），精魂召喚的骷髏、附魂、狼吃不到；更少 50% 另外乘。
 */
class SolidPlanTest {

    @Test
    void onlyPersistentBuffsGetHalfReservation() throws IOException {
        final AscendancyConfig config = new AscendancyConfig();
        config.load(Path.of("src/main/resources/data"));
        final Modifier plan = config.ascendancy("legionnaire").node("solid_plan").stats().getFirst();
        assertEquals(StatKeys.AURA_RESERVATION, plan.stat());
        assertEquals(ModType.MORE, plan.type());
        assertEquals(-0.5, plan.value(), 1e-9);
        final SkillConfig skills = new SkillConfig();
        skills.load(Path.of("src/main/resources/data"));
        for (final String minion : new String[] {"summon_bone_heavy", "summon_bone_frostmage", "soul_attach_art", "grey_wolf_escort", "wild_beast_taming"}) {
            assertFalse(plan.appliesTo(skills.skill(minion).tags()), minion + " 是精魂召喚，不是增益");
        }
        for (final String buff : new String[] {"evil_thought", "summon_burning_souls", "insect_devour_horde"}) {
            assertTrue(plan.appliesTo(skills.skill(buff).tags()), buff + " 是常駐增益");
        }
    }
}
