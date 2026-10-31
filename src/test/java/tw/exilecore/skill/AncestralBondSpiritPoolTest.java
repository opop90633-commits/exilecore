package tw.exilecore.skill;

import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import tw.exilecore.stats.Modifier;
import tw.exilecore.stats.StatKeys;
import tw.exilecore.stats.StatSheet;
import tw.exilecore.stats.TotemKeys;

import java.io.IOException;
import java.nio.file.Path;
import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 2026-10-28 回報：先祖魂約放 4 根圖騰（300 精魂）、上限 335，面板顯示已用 335／335。
 * 照 PoE2 全部的精魂保留共用一個池：光環、精魂召喚、主要寶石能用的＝最大精魂扣掉圖騰的保留；
 * 這次之前光環那邊只看最大精魂，照隻數算的召喚（付得起幾隻叫幾隻）照整個 335 叫，加起來超過上限。
 */
class AncestralBondSpiritPoolTest {

    static SkillConfig config;

    @BeforeAll
    static void load() throws IOException {
        config = new SkillConfig();
        config.load(Path.of("src/main/resources/data"));
    }

    private static SkillBuild build(final String skill, final int level) {
        return SkillBuild.of(config, config.skill(skill), level, 100, false, List.of(), Set.of(), new StatSheet());
    }

    private static StatSheet spirit(final double max) {
        return new StatSheet().add(Modifier.flat(StatKeys.SPIRIT_MAX, max, "測試"));
    }

    @Test
    void auraCapacityLeavesOutTotemReservation() {
        final StatSheet sheet = spirit(335);
        final double totems = 4 * TotemKeys.ANCESTRAL_BOND_SPIRIT;
        assertEquals(300, totems, 1e-9, "4 根圖騰 300");
        assertEquals(335, AuraService.capacity(sheet), 1e-9, "最大精魂");
        assertEquals(35, AuraService.capacity(sheet, totems), 1e-9, "光環、召喚只剩 35");
        assertEquals(0, AuraService.capacity(sheet, 400), 1e-9, "不會變負的");
        assertEquals(335, AuraService.capacity(sheet, 0), 1e-9, "沒有圖騰");
    }

    @Test
    void perMinionSummonOnlyTakesWhatTotemsLeave() {
        final StatSheet sheet = spirit(335);
        final double totems = 4 * TotemKeys.ANCESTRAL_BOND_SPIRIT;
        final SkillBuild archers = build("summon_bone_bowmen", 20); // 每隻 30
        assertTrue(archers.minionReserve() > 0 && archers.minions() > 1, "照隻數算精魂");
        // 這次之前：照整個 335 叫，加上圖騰超過上限
        final AuraService.Fit before = AuraService.fit(archers, AuraService.capacity(sheet));
        assertNotNull(before);
        assertTrue(totems + before.reserve() > 335, "舊算法：保留超過最大精魂（" + (totems + before.reserve()) + "）");
        // 現在：只用圖騰剩下的 35 → 1 隻 30，總共 330 ≤ 335
        final AuraService.Fit after = AuraService.fit(archers, AuraService.capacity(sheet, totems));
        assertNotNull(after);
        assertEquals(1, after.minions());
        assertEquals(30, after.reserve(), 1e-9);
        assertTrue(totems + after.reserve() <= 335 + 1e-9);
    }

    @Test
    void fixedReservationsDoNotFitIntoTotemSpirit() {
        final StatSheet sheet = spirit(335);
        final double left = AuraService.capacity(sheet, 4 * TotemKeys.ANCESTRAL_BOND_SPIRIT);
        assertNull(AuraService.fit(build("critical_guide_star", 20), left), "暴擊引星 100 放不下（這次之前照 335 開，變成 400／335）");
        assertNotNull(AuraService.fit(build("ember_envoy", 20), left), "燼使 30 放得下");
        assertNull(AuraService.fit(build("ember_envoy", 20), AuraService.capacity(spirit(300), 300)), "剛好 300 用完就開不了");
    }

    @Test
    void statsPanelListsWhatReservesSpirit() {
        final StatSheet sheet = spirit(335)
                .add(Modifier.flat(StatKeys.SPIRIT_RESERVED, 300, SkillDeployables.BOND_SOURCE))
                .add(Modifier.flat(StatKeys.SPIRIT_RESERVED, 30, "光環保留"));
        assertEquals(330, sheet.get(StatKeys.SPIRIT_RESERVED), 1e-9);
        assertEquals("先祖魂約 300、光環保留 30", tw.exilecore.character.StatsReport.spiritBreakdown(sheet));
        assertEquals("", tw.exilecore.character.StatsReport.spiritBreakdown(spirit(335)));
    }

    /** 2026-10-28 照站長：圖騰保留的精魂吃精魂保留效率（例：稜壁「每 100 最大生命，精魂的保留效率增加 1%」）。 */
    @Test
    void bondSpiritFollowsReservationEfficiency() {
        final SkillBuild totem = build("hero_spirit_totem", 20);
        assertEquals(75, SkillDeployables.bondSpirit(new StatSheet(), totem), 1e-9, "沒有效率：每根 75");
        // 效率增加 50%：75 ÷ 1.5 = 50
        final StatSheet efficient = new StatSheet().add(Modifier.increased(StatKeys.AURA_RESERVATION, -0.5, "測試"));
        assertEquals(50, SkillDeployables.bondSpirit(efficient, totem), 1e-9);
        // 稜壁 3000 生命 → 效率增加 30%：75 ÷ 1.3
        final StatSheet shield = new StatSheet().add(Modifier.increased(StatKeys.AURA_RESERVATION, -0.30, "稜壁"));
        assertEquals(75 / 1.3, SkillDeployables.bondSpirit(shield, totem), 1e-9);
        // 保留增加 20%：90
        final StatSheet worse = new StatSheet().add(Modifier.increased(StatKeys.AURA_RESERVATION, 0.2, "測試"));
        assertEquals(90, SkillDeployables.bondSpirit(worse, totem), 1e-9);
    }
}
