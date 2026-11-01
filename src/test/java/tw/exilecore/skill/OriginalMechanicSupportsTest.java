package tw.exilecore.skill;

import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import tw.exilecore.stats.LineageKeys;
import tw.exilecore.stats.Modifier;
import tw.exilecore.stats.StatKeys;
import tw.exilecore.stats.SupportExtraKeys;
import tw.exilecore.stats.SupportKeys;

import java.io.IOException;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 2026-11-01 照 PoE2：機制跟原作不一樣的幾顆輔助改成原作的做法（破眩、腥獵、雙蝕、契血嘯、碎晶嘯、厚樁、霆爆、骰禍、令福、血契）。
 */
class OriginalMechanicSupportsTest {

    static SkillConfig config;

    @BeforeAll
    static void load() throws IOException {
        config = new SkillConfig();
        config.load(LineageSupportTest.DATA);
    }

    private static SupportDefinition support(final String id) {
        final SupportDefinition support = config.support(id);
        assertNotNull(support, id);
        return support;
    }

    private static boolean has(final SupportDefinition support, final String stat) {
        return support.modifiers().stream().map(Modifier::stat).anyMatch(stat::equals);
    }

    @Test
    void breakPostureDazesOnlyTheBrokenEnemy() {
        final SupportDefinition support = support("daze_breaker");
        assertEquals(8, LineageRules.total(support, SupportExtraKeys.DAZE_ON_ARMOUR_BROKEN), 1e-9, "Break Posture：眩暈 8 秒");
        assertFalse(has(support, "proc.armour_broken"), "不再觸發有範圍的破眩震");
    }

    @Test
    void bloodlustIsPhysicalOnly() {
        final SupportDefinition support = support("gore_hunt");
        assertEquals(30, LineageRules.total(support, SupportExtraKeys.PHYSICAL_VS_BLEEDING), 1e-9);
        assertFalse(has(support, StatKeys.DAMAGE_VS_AILMENT + "bleeding"), "damage.vs_bleeding 會讓所有傷害類型都更多");
        assertTrue(has(support, StatKeys.BLEED_CHANCE), "還是不能造成流血");
    }

    @Test
    void admixtureBleedIsOnlyVersusPoisoned() {
        final SupportDefinition support = support("twin_corrode");
        assertEquals(20, LineageRules.total(support, SupportKeys.POISON_VS_BLEEDING), 1e-9);
        assertEquals(20, LineageRules.total(support, SupportKeys.BLEED_VS_POISONED), 1e-9);
        assertFalse(has(support, StatKeys.BLEED_MAGNITUDE), "流血不再一律更多");
    }

    @Test
    void paquatesPactAppliesFiveStacks() {
        assertEquals(5, SupportMechanics.PACT_STACKS);
        assertEquals(10, SupportMechanics.CORRUPTED_MAX);
    }

    @Test
    void vruunsAftermathCountsCrystalsAndFragmentsTogether() {
        assertTrue(SupportMechanics.crystalShatterEmpowers(5, 0));
        assertTrue(SupportMechanics.crystalShatterEmpowers(2, 3), "冰晶＋冰裂合計 5 個");
        assertTrue(SupportMechanics.crystalShatterEmpowers(0, 5));
        assertFalse(SupportMechanics.crystalShatterEmpowers(2, 2));
    }

    @Test
    void greatwoodOnlyOneTotemAndScalesWithNearbyTotems() {
        final SupportDefinition support = support("thick_post");
        assertEquals(1, LineageRules.total(support, SupportExtraKeys.TOTEM_ONLY_ONE), 1e-9, "只能有一根圖騰");
        assertEquals(6, LineageRules.total(support, SupportExtraKeys.GREATWOOD_DAMAGE), 1e-9);
        assertEquals(6, LineageRules.total(support, SupportExtraKeys.GREATWOOD_AREA), 1e-9);
        assertEquals(83, LineageRules.total(support, SupportExtraKeys.GREATWOOD_REGEN), 1e-9, "每根 0.83%");
        assertEquals(30, LineageRules.total(support, StatKeys.TOTEM_LIFE), 1e-9);
        assertEquals(6, SupportExtraKeys.GREATWOOD_CAP);
        // 6 根 × 6% 增加範圍，原本沒有增加：半徑 √1.36
        assertEquals(Math.sqrt(1.36), LineageMechanics.greatwoodRadiusScale(0, 36), 1e-9);
        // 跟原本的增加相加：原本增加 50% → (1.5 + 0.36) / 1.5
        assertEquals(Math.sqrt(1.86 / 1.5), LineageMechanics.greatwoodRadiusScale(0.5, 36), 1e-9);
        assertEquals(1, LineageMechanics.greatwoodRadiusScale(0.5, 0), 1e-9);
    }

    @Test
    void ixchelRandomisesProjectileSpeed() {
        assertEquals(0.6, LineageMechanics.randomProjectileSpeed(0), 1e-9, "更少 40%");
        assertEquals(1.5, LineageMechanics.randomProjectileSpeed(1), 1e-9, "更多 50%");
        assertEquals(1.05, LineageMechanics.randomProjectileSpeed(0.5), 1e-9);
        assertTrue(LineageRules.total(support("dice_bane"), LineageKeys.RANDOM_AREA) > 0);
    }

    @Test
    void varashtaWindowIsEightSeconds() {
        assertEquals(160, LineageMechanics.COMMAND_WINDOW_TICKS);
        assertEquals(1, LineageMechanics.recentKinds(java.util.Map.of("a", 100L, "b", 30L), 200, LineageMechanics.COMMAND_WINDOW_TICKS),
                "8.5 秒前用的不算");
    }
}
