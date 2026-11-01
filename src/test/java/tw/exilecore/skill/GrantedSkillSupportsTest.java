package tw.exilecore.skill;

import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 2026-11-01 照 PoE2（PoB2 SkillGrantedBySupport）：輔助送的技能（咒爆的咒殞爆）也吃同一顆寶石上能輔助它的其他輔助；
 * 只能輔助送的技能的輔助也插得進去，效果只給送的技能。
 */
class GrantedSkillSupportsTest {

    static SkillConfig config;

    @BeforeAll
    static void load() throws IOException {
        config = new SkillConfig();
        config.load(Path.of("src/main/resources/data"));
    }

    @Test
    void chaosAttunementGoesOnDespairOnlyThroughImpendingDoom() {
        final SkillDefinition despair = config.skill("soul_wither_curse");
        final SupportDefinition chaos = config.support("decay_infuse");
        assertNotNull(chaos.refusal(despair, List.of()), "凋魂詛咒本身不造成傷害");
        assertNotNull(chaos.socketRefusal(despair, List.of()), "沒有咒爆就插不進去");
        assertNull(chaos.socketRefusal(despair, List.of("curse_blast")), "有咒爆：能輔助咒殞爆就插得進去");
        assertTrue(chaos.onlyGranted(despair, List.of("curse_blast")));
        assertFalse(chaos.supports(despair, List.of("curse_blast")), "本體還是吃不到");
        assertEquals(List.of(config.skill("proc_impending_doom")), chaos.grantedTargets(despair, List.of("curse_blast")));
    }

    @Test
    void doomBlastInheritsTheSupportsThatCanSupportIt() {
        final SkillDefinition despair = config.skill("soul_wither_curse");
        final SkillDefinition doomBlast = config.skill("proc_impending_doom");
        final SupportDefinition doom = config.support("curse_blast");
        final SupportDefinition chaos = config.support("decay_infuse");
        final List<SupportDefinition> inherited = SupportProcs.grantedSupports(List.of(doom, chaos), despair, doomBlast);
        assertTrue(inherited.contains(chaos), "附蝕給咒殞爆");
        assertFalse(inherited.contains(doom), "會送技能的輔助自己不算");
        assertTrue(doom.grantsSkills());
        assertEquals(List.of("proc_impending_doom"), doom.grantedSkillIds());
    }

    @Test
    void parentDamageSubSkillsDoNotInheritAgain() {
        // 照原技能那一下出傷害的（parent_damage：撒蒺的鐵蒺）已經吃過原技能的輔助
        final SkillDefinition caltrop = config.skill("proc_caltrops");
        assertNotNull(caltrop);
        assertFalse(config.support("decay_infuse").supportsGranted(caltrop));
    }

    @Test
    void onlyRealGrantedSkillsCount() {
        for (final String id : SupportDefinition.GRANTED_BY_SUPPORT) {
            assertNotNull(config.skill(id), id);
        }
        // 凍地（Frost Nexus）原作沒有送技能，只是輔助自己的效果：附蝕不會因為它插得進凋魂詛咒
        assertNotNull(config.support("decay_infuse").socketRefusal(config.skill("soul_wither_curse"), List.of("frozen_ground")));
    }
}
