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
        assertTrue(SupportDefinition.hostsSkill(config.skill("totem_casting")));
        assertTrue(SupportDefinition.hostsSkill(config.skill("meta_crit")));
        assertTrue(SupportDefinition.hostsSkill(config.skill("ancestral_totem")));
        assertFalse(SupportDefinition.hostsSkill(config.skill("fireball")));
    }

    @Test
    void supportsOnAHostFollowTheInnerSkill() {
        final SkillDefinition totem = config.skill("totem_casting");
        final SkillDefinition fireball = config.skill("fireball");
        for (final String id : new String[] {"prolonged_duration", "faster_casting", "urgent_totems_iii", "overabundance_ii", "totem_fury",
                "sturdy_totem", "critical_strikes", "added_fire", "considered_casting"}) {
            assertNull(config.support(id).hostRefusal(totem, fireball), id);
        }
        // 還沒放技能：只知道不能放哪些
        assertNotNull(config.support("critical_strikes").hostRefusal(totem, null));
        // 攻擊的輔助掛不到放了法術的宿主上
        assertNotNull(config.support("faster_attacks").hostRefusal(totem, fireball));
        // 2026-10-24：寫了「圖騰除外」的輔助不能掛在圖騰宿主上（裂虛回響、連擊：英靈每次只打一下）
        assertNotNull(config.support("rift_echo").hostRefusal(config.skill("ancestral_totem"), config.skill("leap_slam") != null
                ? config.skill("leap_slam") : fireball));
        assertNotNull(config.support("spell_echo").hostRefusal(totem, fireball));
        // 宿主本身只吃這幾種
        assertTrue(config.support("urgent_totems_iii").worksOnHost()); // 2026-10-26：疾樁 I 併入疾樁 III
        assertTrue(config.support("faster_casting").worksOnHost());
        assertTrue(config.support("persistent_ground").worksOnHost());
        assertFalse(config.support("critical_strikes").worksOnHost());
    }

    @Test
    void innerSkillRulesAndEncoding() {
        assertNull(SkillService.linkedProblem(config.skill("boneshatter")), "錘子攻擊可以放進英靈圖騰");
        // 2026-10-27 照 PoE2 Ancestral Warrior Totem：千鈞砸是引導（Channel），原作不能給英靈用（這次之前測的就是千鈞砸、可以放）
        assertNotNull(SkillService.linkedProblem(config.skill("supercharged_slam")));
        assertNotNull(SkillService.linkedProblem(config.skill("fireball")));
        final SkillLoadout.Entry inner = new SkillLoadout.Entry("fireball", 18, 4, List.of());
        final SkillLoadout.Entry host = new SkillLoadout.Entry("totem_casting", 20, 5, List.of("", "added_fire", "", "critical_strikes~", ""))
                .withInner(inner);
        assertEquals("5:@fireball/18/4,added_fire,,critical_strikes~,", host.encodeSupports());
        final SkillLoadout.Entry back = SkillLoadout.Entry.decode("totem_casting", 20, host.encodeSupports());
        assertEquals("fireball", back.inner().skillId());
        assertEquals(18, back.inner().level());
        assertEquals(4, back.inner().sockets());
        assertEquals(List.of("added_fire", "critical_strikes"), back.supportIds());
        assertTrue(back.resonant(3));
        assertEquals(1, back.firstSupportSocket());
        assertEquals(2, back.freeSocket(), "第 1 孔放著技能不算空孔");
        final SkillLoadout.Entry spell = SkillService.innerEntry(back);
        assertEquals("fireball", spell.skillId());
        assertEquals(List.of("added_fire", "critical_strikes"), spell.supportIds(), "宿主孔裡的輔助就是裡面技能的輔助");
        assertNull(SkillLoadout.Entry.decode("fireball", 18, "3:added_fire,,").inner(), "一般技能沒有");
        assertEquals("3:added_fire,,", SkillLoadout.Entry.decode("fireball", 18, "3:added_fire,,").encodeSupports());
    }
}
