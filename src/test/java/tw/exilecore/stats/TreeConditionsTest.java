package tw.exilecore.stats;

import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import tw.exilecore.character.PlayerStateService;
import tw.exilecore.combat.TargetConditions;
import tw.exilecore.passive.PassiveNode;
import tw.exilecore.passive.PassiveTree;

import java.io.IOException;
import java.io.StringReader;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.function.Predicate;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** 2026-10-26：天賦樹的條件（PoE2 0.5 的 while stationary／while Surrounded／vs Blinded…）與新的屬性鍵。 */
class TreeConditionsTest {

    static PassiveTree tree;

    @BeforeAll
    static void load() throws IOException {
        tree = PassiveTree.load(Path.of("src/main/resources/data/passives/tree.json"));
    }

    @Test
    void selfConditionsBecomeStatSuffixAndEnemyConditionsBecomeTags() {
        final Modifier base = Modifier.increased(StatKeys.ARMOUR, 0.3, "測試");
        assertEquals("armour@stationary", TreeConditions.apply(base, "stationary").stat());
        assertEquals(new Recently.Condition("stationary", false), Recently.condition("armour@stationary"));
        assertEquals(new Recently.Condition("fulllife", true), Recently.condition("es.delay@nofulllife"));
        assertEquals(new Recently.Condition(TreeConditions.STUNNED_ENEMY, false), Recently.condition("skill.area@stunnedenemy"));
        assertNull(Recently.condition("armour@nostationaryx"), "不認得的條件");
        final Modifier vs = TreeConditions.apply(Modifier.increased(StatKeys.DAMAGE, 0.15, "測試"), "vs_blinded");
        assertEquals(StatKeys.DAMAGE, vs.stat());
        assertTrue(vs.tags().contains(TreeConditions.VS_BLINDED));
        assertTrue(TreeConditions.hasEnemyCondition(vs));
        assertFalse(TreeConditions.hasEnemyCondition(base));
        assertTrue(TreeConditions.known("nohit") && TreeConditions.known("vs_far") && TreeConditions.known("nolowmana"));
        assertFalse(TreeConditions.known("vs_tuesday"));
        assertTrue(TreeConditions.SELF.containsAll(TreeConditions.RESERVED));
    }

    @Test
    void treeJsonConditionIsReadIntoModifiers() {
        final String json = """
                {"version": 4, "starts": {"marauder": "s"}, "nodes": [
                 {"id": "s", "type": "start", "x": 0, "y": 0, "start": "marauder", "links": ["a"], "stats": []},
                 {"id": "a", "type": "small", "name": "測試", "x": 1, "y": 0, "links": [], "stats": [
                  {"stat": "armour", "type": "INCREASED", "value": 0.3, "tags": [], "condition": "stationary"},
                  {"stat": "damage", "type": "INCREASED", "value": 0.15, "tags": ["projectile"], "condition": "vs_far"},
                  {"stat": "evasion", "type": "INCREASED", "value": 0.1, "tags": []}]}]}
                """;
        final PassiveNode node = PassiveTree.load(new StringReader(json)).node("a");
        assertEquals("armour@stationary", node.modifiers().get(0).stat());
        assertEquals(Set.of("projectile", TreeConditions.VS_FAR), node.modifiers().get(1).tags());
        assertEquals("evasion", node.modifiers().get(2).stat());
    }

    /** 屬性表：照條件成不成立決定算不算（跟 PlayerStateService 重算時同一套）。 */
    private static StatSheet sheet(final List<Modifier> mods, final Predicate<Recently.Condition> holds) {
        final StatSheet sheet = new StatSheet();
        for (final Modifier mod : mods) {
            final Modifier resolved = PlayerStateService.resolved(mod, holds);
            if (resolved != null) {
                sheet.add(resolved);
            }
        }
        return sheet;
    }

    private static Modifier find(final String condition, final String stat) {
        for (final PassiveNode node : tree.nodes()) {
            for (final Modifier mod : node.modifiers()) {
                if (mod.stat().equals(stat + "@" + condition)) {
                    return mod;
                }
            }
        }
        return null;
    }

    @Test
    void stationaryManaRegenOnlyCountsWhileStationary() {
        final Modifier mod = find(TreeConditions.STATIONARY, StatKeys.MANA_REGEN);
        assertNotNull(mod, "PoE2 樹上有「靜止時，增加 16% 魔力再生速度」");
        final List<Modifier> mods = new ArrayList<>(List.of(mod, Modifier.increased(StatKeys.MANA_REGEN, 0.1, "其他")));
        assertEquals(0.1 + mod.value(), sheet(mods, c -> true).increased(StatKeys.MANA_REGEN, Set.of()), 1e-9, "靜止");
        assertEquals(0.1, sheet(mods, c -> false).increased(StatKeys.MANA_REGEN, Set.of()), 1e-9, "移動中");
    }

    @Test
    void negatedConditionsFlipTheResult() {
        final Modifier noHit = find("nohit", StatKeys.EVASION);
        assertNotNull(noHit, "「若你最近未被擊中，減少 30% 閃避值」");
        assertTrue(noHit.value() < 0);
        // holds 收到的條件帶 negated；PlayerStateService.evaluate 回傳 negated != 發生過
        final Predicate<Recently.Condition> hitRecently = c -> c.negated() != c.event().equals(Recently.HIT);
        assertEquals(0, sheet(List.of(noHit), hitRecently).increased(StatKeys.EVASION, Set.of()), 1e-9, "最近被打過：沒有減少");
        assertEquals(noHit.value(), sheet(List.of(noHit), c -> c.negated()).increased(StatKeys.EVASION, Set.of()), 1e-9, "沒被打過：減少");
        final Modifier surrounded = find(TreeConditions.SURROUNDED, StatKeys.DAMAGE);
        assertNotNull(surrounded, "「被包圍時，增加 25% 攻擊傷害」");
        assertEquals(0.25, sheet(List.of(surrounded), c -> c.event().equals(TreeConditions.SURROUNDED))
                .increased(StatKeys.DAMAGE, Set.of(Tags.ATTACK)), 1e-9);
        assertEquals(0, sheet(List.of(surrounded), c -> false).increased(StatKeys.DAMAGE, Set.of(Tags.ATTACK)), 1e-9);
    }

    @Test
    void reservedConditionsStayInactiveInTheTree() {
        for (final PassiveNode node : tree.nodes()) {
            for (final Modifier mod : node.modifiers()) {
                final Recently.Condition condition = Recently.condition(mod.stat());
                if (condition != null) {
                    assertFalse(TreeConditions.RESERVED.contains(condition.event()), "變身、夥伴還沒接上，不該寫進樹：" + node.id());
                }
                assertTrue(!Recently.hasCondition(mod.stat()) || condition != null, "樹上的條件引擎都要認得：" + mod.stat());
            }
        }
    }

    private static TargetConditions.Target target(final double distance, final boolean blinded, final boolean frozen) {
        return new TargetConditions.Target(distance, blinded, false, false, false, false, false, false, 0.5, false, false, frozen,
                false, false, false, false, false, false);
    }

    @Test
    void enemyConditionsOnlyApplyWhenTheTargetMatches() {
        final Set<String> far = TargetConditions.tags(target(10, false, false));
        assertTrue(far.contains(TreeConditions.VS_FAR));
        assertFalse(far.contains(TreeConditions.VS_CLOSE));
        final Set<String> nearBlindFrozen = TargetConditions.tags(target(1.5, true, true));
        assertTrue(nearBlindFrozen.containsAll(Set.of(TreeConditions.VS_NEAR, TreeConditions.VS_CLOSE, TreeConditions.VS_BLINDED,
                TreeConditions.VS_IMMOBILISED, TreeConditions.VS_FROZEN, TreeConditions.VS_AILMENT)));
        assertFalse(nearBlindFrozen.contains(TreeConditions.VS_FAR));

        final StatSheet attacker = new StatSheet();
        attacker.add(TreeConditions.apply(new Modifier(StatKeys.DAMAGE, ModType.INCREASED, 0.15, Set.of(Tags.PROJECTILE), "樹"), "vs_far"));
        attacker.add(TreeConditions.apply(Modifier.increased(StatKeys.DAMAGE, 0.2, "樹"), "vs_blinded"));
        final SkillProfile arrow = new SkillProfile("arrow", Set.of(Tags.ATTACK, Tags.PROJECTILE), 1.0, null);
        assertEquals(0, attacker.increased(StatKeys.DAMAGE, arrow.contextFor(DamageType.PHYSICAL)), 1e-9, "沒有目標的狀態：都不算");
        assertEquals(0.15, attacker.increased(StatKeys.DAMAGE, TargetConditions.tag(arrow, far).contextFor(DamageType.PHYSICAL)), 1e-9);
        assertEquals(0.2, attacker.increased(StatKeys.DAMAGE, TargetConditions.tag(arrow, nearBlindFrozen).contextFor(DamageType.PHYSICAL)), 1e-9);
        final SkillProfile melee = new SkillProfile("slash", Set.of(Tags.ATTACK, Tags.MELEE), 1.0, null);
        assertEquals(0, attacker.increased(StatKeys.DAMAGE, TargetConditions.tag(melee, far).contextFor(DamageType.PHYSICAL)), 1e-9,
                "投射物的才吃遠處加成");
        assertTrue(TargetConditions.tag(arrow, Set.of()) == arrow);
    }

    @Test
    void energyShieldGrantsThresholds() {
        final StatSheet sheet = new StatSheet();
        sheet.setBase(StatKeys.ES_MAX, 1000);
        sheet.add(Modifier.flat(TreeKeys.STUN_THRESHOLD_FROM_ES, 8, "樹"));
        sheet.add(Modifier.flat(TreeKeys.AILMENT_THRESHOLD_FROM_ES, 8, "樹"));
        sheet.add(Modifier.flat(TreeKeys.FREEZE_THRESHOLD_FROM_ES, 50, "樹"));
        TreeKeys.applyEsThresholds(sheet);
        assertEquals(80, sheet.flat(StatKeys.STUN_THRESHOLD, Set.of()), 1e-9);
        assertEquals(80, sheet.flat(StatKeys.AILMENT_THRESHOLD, Set.of()), 1e-9);
        assertEquals(500, sheet.flat(TreeKeys.FREEZE_THRESHOLD, Set.of()), 1e-9);
        sheet.add(Modifier.increased(TreeKeys.FREEZE_THRESHOLD, 0.3, "樹"));
        assertEquals(1000 * 1.3 + 500, TreeKeys.freezeThreshold(1000, sheet), 1e-9);
        sheet.add(Modifier.increased(TreeKeys.IGNITE_EFFECT_ON_SELF, -0.3, "樹"));
        assertEquals(0.7, TreeKeys.selfEffect(sheet, TreeKeys.IGNITE_EFFECT_ON_SELF), 1e-9);
        assertEquals(1, TreeKeys.selfEffect(sheet, TreeKeys.SHOCK_EFFECT_ON_SELF), 1e-9);
    }

    @Test
    void treeUsesTheNewKeys() {
        final Set<String> stats = new java.util.HashSet<>();
        for (final PassiveNode node : tree.nodes()) {
            for (final Modifier mod : node.modifiers()) {
                stats.add(Recently.stat(mod.stat()));
            }
        }
        assertTrue(stats.containsAll(Set.of(TreeKeys.STUN_THRESHOLD_FROM_ES, TreeKeys.AILMENT_THRESHOLD_FROM_ES, TreeKeys.LIFE_COST_PERCENT,
                TreeKeys.PIN_BUILDUP, TreeKeys.ARMOUR_BREAK_EFFECT, TreeKeys.IGNITE_EFFECT_ON_SELF, StatKeys.CURSE_EFFECT_ON_SELF)));
    }

    /** 2026-10-26：變身、夥伴的條件寫進樹了（以前標尚未生效），怒氣照每 5 點一階拆開。 */
    @Test
    void shapeshiftCompanionAndRageTiersAreInTheTree() {
        assertNotNull(find(TreeConditions.SHAPESHIFTED, StatKeys.DAMAGE), "「變身時，增加 10% 傷害」");
        assertNotNull(find(TreeConditions.COMPANION, StatKeys.DAMAGE), "「夥伴在你的臨在範圍內時，增加 10% 傷害」");
        final Modifier rage30 = find("rage30", StatKeys.ARMOUR);
        assertNotNull(rage30, "「每點怒氣增加 1% 護甲」拆成每 5 點一階");
        assertEquals(0.05, rage30.value(), 1e-9);
        final List<Modifier> tiers = new ArrayList<>();
        for (int at = 5; at <= 60; at += 5) {
            tiers.add(find("rage" + at, StatKeys.ARMOUR));
        }
        final int rage = 23;
        assertEquals(0.20, sheet(tiers, c -> c.event().startsWith("rage") && Integer.parseInt(c.event().substring(4)) <= rage)
                .increased(StatKeys.ARMOUR, Set.of()), 1e-9, "23 點怒氣＝4 階");
    }
}
