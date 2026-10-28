package tw.exilecore.skill;

import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import tw.exilecore.stats.StatSheet;

import java.io.IOException;
import java.nio.file.Path;
import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** 2026-10-28 照 PoE2 Arc（RePoE：Projectile、base_is_projectile、沒有 projectiles_can_shotgun）：多的投射物各放一道閃電；這次之前只放一道。 */
class ChainLightningProjectilesTest {

    static SkillConfig config;

    @BeforeAll
    static void load() throws IOException {
        config = new SkillConfig();
        config.load(Path.of("src/main/resources/data"));
    }

    private static SkillBuild arc(final String... supports) {
        final List<SupportDefinition> list = java.util.Arrays.stream(supports).map(config::support).toList();
        return SkillBuild.of(config, config.skill("arc"), 20, 90, false, list, Set.of(), new StatSheet());
    }

    @Test
    void myriadStarsGivesArcThreeBeams() {
        final SkillBuild plain = arc();
        assertEquals(1, SkillExecutor.chainBeams(plain.definition(), plain.projectiles()));
        assertNull(config.support("myriad_stars").refusal(config.skill("arc")), "星辰萬象掛得上鏈雷");
        final SkillBuild myriad = arc("myriad_stars");
        assertEquals(3, myriad.projectiles());
        assertEquals(3, SkillExecutor.chainBeams(myriad.definition(), myriad.projectiles()), "額外 2 個投射物 → 3 道");
        assertEquals(plain.chains() + 1, myriad.chains(), "多連鎖 1 次（每道都是）");
        assertTrue(myriad.spread() > 0 && myriad.spread() < 360, "扇形排開");
    }

    @Test
    void nonProjectileChainSkillsStayOneBeam() {
        // 原作血脈牽、裂隙之手不是投射物：投射物數量不影響
        assertEquals(1, SkillExecutor.chainBeams(config.skill("exsanguinate"), 3));
        assertEquals(1, SkillExecutor.chainBeams(config.skill("rift_hand"), 3));
        assertEquals(3, SkillExecutor.chainBeams(config.skill("arc"), 3));
    }

    @Test
    void novaProjectilesSpreadArcInACircle() {
        final SkillBuild nova = arc("myriad_stars", "nova_projectiles");
        assertEquals(360, nova.spread(), 1e-9, "環射：鏈雷的幾道往四周一圈");
    }

    @Test
    void primaryBeamIsTheMiddleOne() {
        assertEquals(0, SkillExecutor.primaryBeam(1, false));
        assertEquals(1, SkillExecutor.primaryBeam(3, false));
        assertEquals(2, SkillExecutor.primaryBeam(5, false));
        assertEquals(0, SkillExecutor.primaryBeam(2, false));
        assertEquals(0, SkillExecutor.primaryBeam(4, true));
    }
}
