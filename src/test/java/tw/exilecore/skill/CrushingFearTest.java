package tw.exilecore.skill;

import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import tw.exilecore.stats.StatKeys;
import tw.exilecore.stats.StatSheet;

import java.io.IOException;
import java.nio.file.Path;
import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertTrue;

/** 2026-11-14 照站長「死懼碎擊的震波原版是有撲殺的，打擊和震波兩個都有撲殺效果」。 */
class CrushingFearTest {

    static SkillConfig config;

    @BeforeAll
    static void load() throws IOException {
        config = new SkillConfig();
        config.load(Path.of("src/main/resources/data"));
    }

    @Test
    void shockwaveCullsLikeThePalm() {
        final SkillBuild build = SkillBuild.of(config, config.skill(SkillExecutor.CRUSHING_FEAR), 20, 90, false, List.of(),
                Set.of("quarterstaff"), new StatSheet());
        assertTrue(StatKeys.flag(build.profile().effective(new StatSheet()), StatKeys.FLAG_CULLING), "打擊有撲殺");
        final var shockwave = SkillStages.profile(build, build.profile(), "shockwave");
        assertTrue(StatKeys.flag(shockwave.effective(new StatSheet()), StatKeys.FLAG_CULLING), "震波也有撲殺");
    }
}
