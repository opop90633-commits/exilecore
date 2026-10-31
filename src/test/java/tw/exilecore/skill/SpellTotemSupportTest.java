package tw.exilecore.skill;

import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import tw.exilecore.character.SkillLoadout;

import java.io.IOException;
import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 2026-10-22：英靈圖騰、星咒圖騰、暴擊引星把技能寶石放在自己的第 1 孔，第 2～5 孔的輔助都套在那顆技能上：
 * 輔助照放進去的技能驗（要圖騰的也可以）；宿主本身只吃要圖騰的、加速度與持續時間的。
 */
class SpellTotemSupportTest {

    static SkillConfig config;

    @BeforeAll
    static void load() throws IOException {
        config = new SkillConfig();
        config.load(Path.of("src/main/resources/data"));
    }

    @Test
    void hostsAreRecognised() {
        assertTrue(SupportDefinition.hostsSkill(config.skill("star_spell_totem")));
        assertTrue(SupportDefinition.hostsSkill(config.skill("critical_guide_star")));
        assertTrue(SupportDefinition.hostsSkill(config.skill("hero_spirit_totem")));
        assertFalse(SupportDefinition.hostsSkill(config.skill("blazing_star_shell")));
    }

    @Test
    void supportsOnAHostFollowTheInnerSkill() {
        final SkillDefinition totem = config.skill("star_spell_totem");
        final SkillDefinition fireball = config.skill("blazing_star_shell");
        for (final String id : new String[] {"long_stay", "quick_chant", "swift_post_iii", "brim_over_ii", "post_wrath",
                "firm_post", "savage_flare", "added_fire"}) {
            assertNull(config.support(id).hostRefusal(totem, fireball), id);
        }
        // 2026-10-30 照 PoE2：法術圖騰加 UsedByProxy、暴擊引星加 Triggered，蓄詠（Considered Casting）兩個都擋；這次之前掛得上
        assertNotNull(config.support("charged_chant").hostRefusal(totem, fireball));
        assertNotNull(config.support("charged_chant").hostRefusal(config.skill("critical_guide_star"), fireball));
        assertNull(config.support("blast_peak").hostRefusal(config.skill("critical_guide_star"), fireball));
        // 還沒放技能：只知道不能放哪些
        assertNotNull(config.support("savage_flare").hostRefusal(totem, null));
        // 攻擊的輔助掛不到放了法術的宿主上
        assertNotNull(config.support("quick_strike").hostRefusal(totem, fireball));
        // 2026-10-24：寫了「圖騰除外」的輔助不能掛在圖騰宿主上（裂虛回響、連擊：英靈每次只打一下）
        assertNotNull(config.support("void_crack_echo").hostRefusal(config.skill("hero_spirit_totem"), config.skill("soaring_smash") != null
                ? config.skill("soaring_smash") : fireball));
        assertNotNull(config.support("twin_chant").hostRefusal(totem, fireball));
        // 宿主本身只吃這幾種
        assertTrue(config.support("swift_post_iii").worksOnHost()); // 2026-10-26：疾樁 I 併入疾樁 III
        assertTrue(config.support("quick_chant").worksOnHost());
        assertTrue(config.support("enduring_terrain").worksOnHost());
        assertFalse(config.support("savage_flare").worksOnHost());
    }

    @Test
    void innerSkillRulesAndEncoding() {
        assertNull(SkillService.linkedProblem(config.skill("spine_crush")), "錘子攻擊可以放進英靈圖騰");
        // 2026-10-27 照 PoE2 Ancestral Warrior Totem：千鈞砸是引導（Channel），原作不能給英靈用（這次之前測的就是千鈞砸、可以放）
        assertNotNull(SkillService.linkedProblem(config.skill("thousand_weight_smash")));
        assertNotNull(SkillService.linkedProblem(config.skill("blazing_star_shell")));
        final SkillLoadout.Entry inner = new SkillLoadout.Entry("blazing_star_shell", 18, 4, List.of());
        final SkillLoadout.Entry host = new SkillLoadout.Entry("star_spell_totem", 20, 5, List.of("", "added_fire", "", "savage_flare~", ""))
                .withInner(inner);
        assertEquals("5:@blazing_star_shell/18/4,added_fire,,savage_flare~,", host.encodeSupports());
        final SkillLoadout.Entry back = SkillLoadout.Entry.decode("star_spell_totem", 20, host.encodeSupports());
        assertEquals("blazing_star_shell", back.inner().skillId());
        assertEquals(18, back.inner().level());
        assertEquals(4, back.inner().sockets());
        assertEquals(List.of("added_fire", "savage_flare"), back.supportIds());
        assertTrue(back.resonant(3));
        assertEquals(1, back.firstSupportSocket());
        assertEquals(2, back.freeSocket(), "第 1 孔放著技能不算空孔");
        final SkillLoadout.Entry spell = SkillService.innerEntry(back);
        assertEquals("blazing_star_shell", spell.skillId());
        assertEquals(List.of("added_fire", "savage_flare"), spell.supportIds(), "宿主孔裡的輔助就是裡面技能的輔助");
        assertNull(SkillLoadout.Entry.decode("blazing_star_shell", 18, "3:added_fire,,").inner(), "一般技能沒有");
        assertEquals("3:added_fire,,", SkillLoadout.Entry.decode("blazing_star_shell", 18, "3:added_fire,,").encodeSupports());
    }
}
