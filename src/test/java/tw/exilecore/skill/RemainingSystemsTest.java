package tw.exilecore.skill;

import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import tw.exilecore.combat.HitEffects;
import tw.exilecore.stats.LineageKeys;
import tw.exilecore.stats.Modifier;
import tw.exilecore.stats.ModType;
import tw.exilecore.stats.StatKeys;
import tw.exilecore.stats.StatSheet;
import tw.exilecore.stats.SupportExtraKeys;
import tw.exilecore.stats.TreeEffectKeys;
import tw.exilecore.stats.TreeMechanicKeys;

import java.io.IOException;
import java.nio.file.Path;
import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** 2026-11-01：眩暈統一的套用、吸電（Enervation）、無情（Ruthless）的消耗、骰禍（Ixchel's Torment）每次觸發擲一次。 */
class RemainingSystemsTest {

    static SkillConfig config;

    @BeforeAll
    static void load() throws IOException {
        config = new SkillConfig();
        config.load(Path.of("src/main/resources/data"));
    }

    private static SupportDefinition support(final String id) {
        final SupportDefinition support = config.support(id);
        assertNotNull(support, id);
        return support;
    }

    private static boolean has(final SupportDefinition support, final String stat) {
        return support.modifiers().stream().anyMatch(m -> m.stat().equals(stat));
    }

    @Test
    void dazeUsesTreeDurationAndEffect() {
        final StatSheet plain = new StatSheet();
        assertEquals(160, HitEffects.dazeTicks(160, plain, Set.of()), "沒有天賦：8 秒");
        final StatSheet tree = new StatSheet().overlay(List.of(Modifier.increased(TreeMechanicKeys.DAZE_DURATION, 0.5, "天賦"),
                Modifier.increased(TreeEffectKeys.DAZE_EFFECT, 0.2, "天賦")));
        assertEquals(240, HitEffects.dazeTicks(160, tree, Set.of()), "增加 50% 眩暈持續時間：12 秒");
        assertEquals(0.5, HitEffects.dazeStunBuildup(0), 1e-9, "眩暈：受到的暈眩累積更多 50%");
        assertEquals(0.6, HitEffects.dazeStunBuildup(tree.increased(TreeEffectKeys.DAZE_EFFECT, Set.of())), 1e-9);
        assertEquals(0, HitEffects.dazeStunBuildup(-2), 1e-9);
    }

    @Test
    void enervationGivesFlatManaRegenWithoutProc() {
        final SupportDefinition volt = support("volt_drain");
        assertEquals(0.3, LineageRules.total(volt, SupportExtraKeys.MANA_REGEN_ON_SHOCK), 1e-9, "每秒再生 0.3 魔力");
        assertFalse(has(volt, "proc.shock"), "不再觸發有 1 秒冷卻的隱藏技能");
        assertNull(config.skill("proc_enervation"));
        assertEquals(1.0, volt.manaMultiplier(), 1e-9, "Enervation：魔耗 100%");
        assertEquals(80, SupportExtraKeys.MANA_REGEN_ON_SHOCK_TICKS, "4 秒");
        final List<Modifier> buff = SupportProcs.enervationModifiers(0.3);
        assertEquals(1, buff.size());
        assertEquals(StatKeys.MANA_REGEN, buff.getFirst().stat());
        assertEquals(ModType.FLAT, buff.getFirst().type());
        assertEquals(0.3, buff.getFirst().value(), 1e-9);
    }

    @Test
    void ruthlessCostsFullMana() {
        assertEquals(1.0, support("merciless_heart").manaMultiplier(), 1e-9, "PoB2 Ruthless 沒有 manaMultiplier：100%");
    }

    @Test
    void ixchelRollsDamageOncePerTrigger() {
        final SupportDefinition dice = support("dice_bane");
        assertEquals(40, LineageRules.total(dice, LineageKeys.RANDOM_DAMAGE), 1e-9);
        assertFalse(has(dice, StatKeys.DAMAGE_RANDOM), "不再每一下各自擲（DamageCalculator 的 damage.random）");
        assertTrue(LineageRules.total(dice, LineageKeys.RANDOM_AREA) > 0);
        assertEquals(0.6, LineageMechanics.randomDamage(40, 0), 1e-9, "更少 40%");
        assertEquals(1.6, LineageMechanics.randomDamage(40, 1), 1e-9, "更多 60%");
        assertEquals(1.0, LineageMechanics.randomDamage(40, 0.4), 1e-9);
        assertEquals(1.0, LineageMechanics.randomDamage(0, 0.9), 1e-9);
        assertEquals("觸發出來的技能每次觸發的傷害在更少 40% 到更多 60% 之間隨機（同一次觸發的每一下都一樣）",
                ModifierText.describe(new Modifier(LineageKeys.RANDOM_DAMAGE, ModType.FLAT, 40, Set.of(), "")));
    }
}
