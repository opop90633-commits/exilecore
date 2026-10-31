package tw.exilecore.skill;

import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import tw.exilecore.stats.Modifier;
import tw.exilecore.stats.ModType;
import tw.exilecore.stats.StatKeys;
import tw.exilecore.stats.StatSheet;

import java.nio.file.Path;
import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;

/** 2026-10-28：凝霜箭吃「+x 投射物」（弓的詞綴寫 bow 標籤）：多射箭，冰片也多（照 PoE2 冰片自己一組投射物數）。 */
class IceShotProjectilesTest {

    static SkillConfig config;

    @BeforeAll
    static void load() throws Exception {
        config = new SkillConfig();
        config.load(Path.of("src/main/resources/data"));
    }

    @Test
    void bowProjectilesApplyToIceShot() {
        final StatSheet sheet = new StatSheet();
        sheet.add(new Modifier(StatKeys.SKILL_PROJECTILES, ModType.FLAT, 2, Set.of("bow"), "弓"));
        final SkillBuild build = SkillBuild.of(config, config.skill("rime_arrow"), 20, 90, false, List.of(), Set.of("bow", "two_hand", "weapon"), sheet);
        assertEquals(3, build.projectiles());
    }
}
