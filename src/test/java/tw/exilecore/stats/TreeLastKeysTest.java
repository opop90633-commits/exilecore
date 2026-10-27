package tw.exilecore.stats;

import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import tw.exilecore.passive.PassiveNode;
import tw.exilecore.passive.PassiveTree;

import java.io.IOException;
import java.nio.file.Path;
import java.util.List;
import java.util.Random;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 2026-10-27：天賦樹最後一批「尚未生效」（站長：「天賦點好像還有蠻多沒有生效的東西」）照 PoE2 接上了：
 * 樹上沒有「尚未生效」、新條件認得、樹上用到新鍵、換算與傷害計算照原作。
 */
class TreeLastKeysTest {

    static PassiveTree tree;

    @BeforeAll
    static void load() throws IOException {
        tree = PassiveTree.load(Path.of("src/main/resources/data/passives/tree.json"));
    }

    /** 節點與職業變體的全部修飾器。 */
    private static List<Modifier> allModifiers(final PassiveNode node) {
        final List<Modifier> all = new java.util.ArrayList<>(node.modifiers());
        for (final PassiveNode.Variant variant : node.variants().values()) {
            all.addAll(variant.modifiers());
        }
        return all;
    }

    /** 節點與職業變體的全部說明行。 */
    private static List<String> allLines(final PassiveNode node) {
        final List<String> all = new java.util.ArrayList<>(node.description());
        for (final PassiveNode.Variant variant : node.variants().values()) {
            all.addAll(variant.description());
        }
        return all;
    }

    private static boolean treeHas(final String stat) {
        for (final PassiveNode node : tree.nodes()) {
            for (final Modifier modifier : allModifiers(node)) {
                if (modifier.stat().equals(stat) || modifier.stat().startsWith(stat + "@")) {
                    return true;
                }
            }
        }
        return false;
    }

    @Test
    void nothingOnTheTreeIsInactiveAnyMore() {
        int inactive = 0;
        int cosmetic = 0;
        for (final PassiveNode node : tree.nodes()) {
            for (final String line : allLines(node)) {
                inactive += line.contains("尚未生效") ? 1 : 0;
                cosmetic += line.contains("只有外觀") ? 1 : 0;
            }
        }
        // 2026-10-27：這次之前 87 行（含職業變體）；照亮範圍以前標「只有外觀」3 行，
        // 2026-10-27 起（站長：換成有作用的效果）改成遠距離命中值懲罰，沒有只有外觀的行了
        assertEquals(0, inactive);
        assertEquals(0, cosmetic);
    }

    @Test
    void newKeysAreOnTheTree() {
        for (final String stat : List.of(TreeLastKeys.ARMOUR_DEFEND, TreeLastKeys.FLAG_NO_CRIT_TAKEN, TreeLastKeys.FLAG_NO_LIGHT_STUN,
                TreeLastKeys.VOLATILITY_ON_STUNNED, TreeLastKeys.FLAG_BLIND_STUNNER, TreeLastKeys.SURROUNDED_AREA, TreeLastKeys.SHIELD_DEBILITATE,
                TreeLastKeys.FLAG_IGNORE_ELEMENTAL_RESIST, TreeLastKeys.PIERCE_CLOSE, TreeLastKeys.FORK_CHANCE, TreeLastKeys.KNOCKBACK_CHANCE,
                TreeLastKeys.FLAG_MAIM_ON_FULL_BREAK, TreeLastKeys.FLAG_AGGRAVATE_PINNED, TreeLastKeys.AGGRAVATE_JAGGED,
                TreeLastKeys.INTIMIDATE_ON_IMMOBILISE, TreeLastKeys.INTIMIDATE_SHAPESHIFT_STUN, TreeLastKeys.MELTING_FLAMES,
                TreeLastKeys.WITHER_ON_ENTER, TreeLastKeys.FLAG_CURSE_NO_ES_RECHARGE, TreeLastKeys.HAZARD_ENEMY_ARMOUR,
                TreeLastKeys.HAZARD_ENEMY_EVASION, TreeLastKeys.REVERT_ARCANE_SURGE, TreeLastKeys.LIFE_PER_GLORY, TreeLastKeys.ATTACK_PER_GLORY,
                TreeLastKeys.GLORY_PER_RARE, TreeLastKeys.ALLY_RAGE_REGEN, TreeLastKeys.FLAG_COMPANION_ONSLAUGHT_SHAPESHIFTED,
                TreeLastKeys.LIFE_ON_OFFERING, TreeLastKeys.LIFE_ON_MINION_REVIVE, "asc.random_element")) {
            assertTrue(treeHas(stat), stat);
        }
        // 2026-10-27：「獲得 x% 傷害為隨機一種元素」以前是 flag.random_element（整招變成隨機元素），現在樹上不用了
        assertFalse(treeHas(StatKeys.FLAG_RANDOM_ELEMENT));
    }

    @Test
    void newConditionsAreKnown() {
        for (final String condition : List.of(TreeConditions.ARCANE_SURGE, "nolowes", TreeConditions.ALL_COMPANIONS, TreeConditions.EMPOWERED_ATTACK,
                TreeConditions.GLORY_20S, TreeConditions.PROJECTILE_HIT_8S, TreeConditions.MELEE_HIT_8S, TreeConditions.ES_RECHARGE_2S,
                TreeConditions.ON_SHOCKED_GROUND, TreeConditions.ON_CHILLED_GROUND, TreeConditions.ON_IGNITED_GROUND, TreeConditions.RAGE_GAINED,
                "echoes5", "ammotypes3", "grenadetypes2", "poisons10", "hazards5", "selfcurses2", "offerings2",
                TreeLastKeys.EVENT_STUNNED, TreeLastKeys.EVENT_STUNNED_TWO_HAND, TreeLastKeys.EVENT_HIT_FIRE,
                TreeConditions.VS_INCISION, TreeConditions.VS_ENTERED_PRESENCE, TreeConditions.VS_EXITED_PRESENCE)) {
            assertTrue(TreeConditions.known(condition), condition);
        }
        // 每一個樹上的條件都認得（寫錯不會偷偷生效，但也不會生效）
        for (final PassiveNode node : tree.nodes()) {
            for (final Modifier modifier : allModifiers(node)) {
                final Recently.Condition condition = Recently.condition(modifier.stat());
                if (Recently.hasCondition(modifier.stat())) {
                    assertTrue(condition != null, node.id() + " " + modifier.stat());
                }
            }
        }
    }

    @Test
    void meltingFlamesAndGloryCapsFollowTheDescription() {
        // 「每被點燃 1 秒受到的火焰傷害增加 1%，最多 10%」
        assertEquals(0.03, TreeLastKeys.meltingTaken(1, 3.5), 1e-9);
        assertEquals(0.10, TreeLastKeys.meltingTaken(1, 60), 1e-9);
        assertEquals(0, TreeLastKeys.meltingTaken(1, 0.5), 1e-9);
        // 「每用掉 1 點榮耀增加 3% 攻擊傷害，最多 60%」
        assertEquals(0.30, TreeLastKeys.gloryAttack(3, 10), 1e-9);
        assertEquals(0.60, TreeLastKeys.gloryAttack(3, 100), 1e-9);
    }

    private static StatSheet critAttacker() {
        return new StatSheet()
                .setBase(StatKeys.ACCURACY, 1_000_000)
                .setBase(StatKeys.CRIT_MULTI, 200)
                .add(new Modifier(StatKeys.WEAPON_MIN, ModType.FLAT, 10, Set.of(Tags.PHYSICAL), "劍"))
                .add(new Modifier(StatKeys.WEAPON_MAX, ModType.FLAT, 10, Set.of(Tags.PHYSICAL), "劍"))
                .add(Modifier.flat(StatKeys.WEAPON_APS, 1.5, "劍"))
                .add(Modifier.flat(StatKeys.WEAPON_CRIT, 100, "劍"));
    }

    @Test
    void defendWithMoreArmourAgainstCriticalHits() {
        // PoE2 Blade Catcher：「對暴擊以 200% 護甲進行防禦」＝多算 100%
        final StatSheet plain = new StatSheet().setBase(StatKeys.ARMOUR, 200).setBase(StatKeys.RESIST_MAX, 75);
        final StatSheet catcher = new StatSheet().setBase(StatKeys.ARMOUR, 200).setBase(StatKeys.RESIST_MAX, 75)
                .add(new Modifier(TreeLastKeys.ARMOUR_DEFEND, ModType.FLAT, 100, Set.of(TreeLastKeys.CRIT_TAKEN), "捕刃者"));
        final SkillProfile sword = SkillProfile.defaultAttack(Set.of(Tags.SWORD));
        final HitResult before = DamageCalculator.calculate(critAttacker(), sword, plain, 0, new Random(3));
        final HitResult after = DamageCalculator.calculate(critAttacker(), sword, catcher, 0, new Random(3));
        assertTrue(before.crit() && after.crit());
        final double hit = before.outgoing().get(DamageType.PHYSICAL);
        assertEquals(hit * (1 - Formulas.armourReduction(200, hit)), before.dealt().get(DamageType.PHYSICAL), 1e-6);
        assertEquals(hit * (1 - Formulas.armourReduction(400, hit)), after.dealt().get(DamageType.PHYSICAL), 1e-6);
        // 不是暴擊就不算
        final StatSheet noCrit = critAttacker().add(Modifier.flat(StatKeys.FLAG_NO_CRIT, 1, "測試"));
        final HitResult normal = DamageCalculator.calculate(noCrit, sword, catcher, 0, new Random(3));
        final double base = normal.outgoing().get(DamageType.PHYSICAL);
        assertEquals(base * (1 - Formulas.armourReduction(200, base)), normal.dealt().get(DamageType.PHYSICAL), 1e-6);
    }

    @Test
    void cannotBeCritWhileTheFlagHolds() {
        final StatSheet defender = new StatSheet().setBase(StatKeys.RESIST_MAX, 75)
                .add(Modifier.flat(TreeLastKeys.FLAG_NO_CRIT_TAKEN, 1, "招架中"));
        for (int seed = 0; seed < 5; seed++) {
            assertFalse(DamageCalculator.calculate(critAttacker(), SkillProfile.defaultAttack(Set.of()), defender, 0, new Random(seed)).crit());
        }
    }

    @Test
    void hitsIgnoreElementalResistanceOfFrozenEnemies() {
        // PoE2 Heavy Frost：對凍結的敵人無視非負的元素抗性（旗標帶 vs_frozen 標籤，目標狀態補在技能的情境）
        final StatSheet attacker = critAttacker().add(Modifier.flat(StatKeys.FLAG_NO_CRIT, 1, "測試"))
                .add(new Modifier(StatKeys.DAMAGE_ADDED_MIN, ModType.FLAT, 100, Set.of(Tags.ATTACK, Tags.FIRE), "戒指"))
                .add(new Modifier(StatKeys.DAMAGE_ADDED_MAX, ModType.FLAT, 100, Set.of(Tags.ATTACK, Tags.FIRE), "戒指"))
                .add(new Modifier(TreeLastKeys.FLAG_IGNORE_ELEMENTAL_RESIST, ModType.FLAT, 1, Set.of(TreeConditions.VS_FROZEN), "重霜"));
        final StatSheet defender = new StatSheet().setBase(StatKeys.RESIST_FIRE, 50).setBase(StatKeys.RESIST_MAX, 75);
        final SkillProfile plain = SkillProfile.defaultAttack(Set.of());
        final SkillProfile frozen = plain.derive(plain.id(), Set.of(TreeConditions.VS_FROZEN), Set.of());
        assertEquals(50, DamageCalculator.calculate(attacker, plain, defender, 0, new Random(1)).dealt().get(DamageType.FIRE), 1e-6);
        assertEquals(100, DamageCalculator.calculate(attacker, frozen, defender, 0, new Random(1)).dealt().get(DamageType.FIRE), 1e-6);
    }
}
