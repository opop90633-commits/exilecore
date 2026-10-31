package tw.exilecore.skill;

import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import tw.exilecore.stats.Tags;

import java.io.IOException;
import java.nio.file.Path;
import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** 2026-10-26：德魯伊的技能照 PoE2 0.5 對標籤、補上漏掉的技能（弦月爪＝Lunar Assault、翼刃斬＝Rend）。 */
class DruidRefTest {

    static SkillConfig config;

    @BeforeAll
    static void load() throws IOException {
        config = new SkillConfig();
        config.load(Path.of("src/main/resources/data"));
    }

    private static double levelValue(final String skill, final String stat, final int level) {
        for (final ScaledMod mod : config.levelModifiers(skill)) {
            if (mod.stat().equals(stat)) {
                return mod.value().at(level);
            }
        }
        throw new AssertionError(skill + " 沒有 " + stat);
    }

    @Test
    void lunarAssaultFollowsRef() {
        final SkillDefinition skill = config.skill("crescent_claw");
        assertTrue(skill.isAttack());
        assertTrue(skill.tags().containsAll(Set.of(Tags.SHAPESHIFT, Tags.WEREWOLF, Tags.MELEE, Tags.STRIKE, Tags.AREA, Tags.COLD)), skill.tags().toString());
        assertEquals(Set.of(Tags.TALISMAN), skill.weapons());
        assertEquals(1, skill.tier());
        assertEquals(1.1, skill.attackSpeed(), 1e-9);
        assertEquals(1.25, skill.effectiveness().at(20), 1e-9);
        assertEquals(3.31, skill.effectiveness().at(40), 1e-9);
        assertEquals(46, skill.mana().at(20), 1e-9);
        assertEquals(506, levelValue("crescent_claw", "freeze.buildup", 20), 1e-9);
        assertEquals(725, levelValue("crescent_claw", "chill.effect", 40), 1e-9);
    }

    @Test
    void wyvernRendIsAFreeWyvernAttack() {
        final SkillDefinition skill = config.skill("wing_blade_slash");
        assertTrue(skill.isAttack(), "飛龍技能維持攻擊");
        assertTrue(skill.tags().containsAll(Set.of(Tags.SHAPESHIFT, Tags.WYVERN, Tags.MELEE, Tags.STRIKE, Tags.AREA, Tags.PHYSICAL)), skill.tags().toString());
        assertEquals(0, skill.mana().at(20), 1e-9, "跟撼山爪、疾爪一樣不花魔力");
        assertEquals(0.9, skill.attackSpeed(), 1e-9);
        assertEquals(3.43, skill.effectiveness().at(20), 1e-9);
    }

    @Test
    void tagsFollowRef() {
        for (final String id : List.of("pounce", "gnawing_bite")) {
            assertTrue(config.skill(id).tags().contains(Tags.SLAM), id + " 照 PoE2 是猛擊");
        }
        for (final String id : List.of("cold_night_howl", "armor_rend_bear_roar", "collapse_quake_totem")) {
            assertTrue(config.skill(id).tags().contains("nova"), id);
        }
        assertTrue(config.skill("volcano").tags().contains(Tags.PROJECTILE));
        for (final String id : List.of("wood_armor", "thorn_body", "beast_blood_boil", "guard_moment", "unquenched_fury")) {
            final SkillDefinition skill = config.skill(id);
            assertTrue(skill.tags().contains(Tags.PERSISTENT), id + " 是常駐增益");
            assertEquals("常駐", skill.typeName(), id);
            assertFalse(skill.tags().contains(Tags.SPELL), id);
        }
    }
}
