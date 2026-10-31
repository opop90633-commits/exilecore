package tw.exilecore.skill;

import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import tw.exilecore.stats.StatSheet;

import java.io.IOException;
import java.nio.file.Path;
import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** 2026-10-29 照站長（PoE2 的 Set Minion Count）：每種精魂召喚自己設定帶幾隻，少帶就少保留精魂。 */
class MinionCountTest {

    static SkillConfig config;

    @BeforeAll
    static void load() throws IOException {
        config = new SkillConfig();
        config.load(Path.of("src/main/resources/data"));
    }

    @Test
    void wantedCountCapsMinionsAndReservation() {
        final SkillBuild archers = SkillBuild.of(config, config.skill("summon_bone_bowmen"), 20, 100, false, List.of(), Set.of(), new StatSheet());
        assertTrue(archers.minions() > 2 && archers.minionReserve() > 0);
        final AuraService.Fit all = AuraService.fit(archers, 10000);
        assertNotNull(all);
        assertEquals(archers.minions(), all.minions(), "沒設定：付得起就帶滿");
        final AuraService.Fit two = AuraService.fit(archers, 10000, 2);
        assertNotNull(two);
        assertEquals(2, two.minions());
        assertEquals(archers.reserve() + Math.max(0, 2 - archers.definition().aura().freeMinions()) * archers.minionReserve(), two.reserve(), 1e-9,
                "只保留 2 隻的份");
        assertEquals(1, AuraService.fit(archers, 10000, 0).minions(), "至少 1 隻");
        assertEquals(archers.minions(), AuraService.fit(archers, 10000, 999).minions(), "超過上限照上限");
        assertEquals(archers.minions(), AuraService.fit(archers, 10000, AuraService.AUTO_COUNT).minions(), "自動：付得起就帶滿");
    }

    @Test
    void unsetCountFollowsRefDefaults() {
        // 2026-10-30 照 PoE2：沒設定照原作預設（skill_desired_initial_amount：骸骨戰士 2，其他 1），不再把精魂吃光
        final SkillBuild spectre = SkillBuild.of(config, config.skill("soul_attach_art"), 20, 100, false, List.of(), Set.of(), new StatSheet());
        final SkillBuild warriors = SkillBuild.of(config, config.skill("summon_skeletons"), 20, 100, false, List.of(), Set.of(), new StatSheet());
        assertEquals(1, AuraService.fit(spectre, 10000, AuraService.DEFAULT_COUNT).minions());
        assertEquals(spectre.minionReserve(), AuraService.fit(spectre, 10000, AuraService.DEFAULT_COUNT).reserve(), 1e-9, "只保留一隻的份");
        assertEquals(2, AuraService.fit(warriors, 10000, AuraService.DEFAULT_COUNT).minions());
        assertEquals(0, AuraService.fit(warriors, 10000, AuraService.DEFAULT_COUNT).reserve(), 1e-9, "前兩隻不用精魂");
    }
}
