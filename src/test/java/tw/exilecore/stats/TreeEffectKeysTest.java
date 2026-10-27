package tw.exilecore.stats;

import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import tw.exilecore.character.Recoup;
import tw.exilecore.combat.HitEffects;
import tw.exilecore.combat.TargetConditions;
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
 * 2026-10-26：PoE2 0.5 天賦樹 stat_map 裡 new_key／needs_tag 那一批（戰吼速度、圖騰放置速度、移動中動作的懲罰、各種「x% 機率」…）
 * 真的有屬性鍵、樹上用得到、換算照 PoE2。
 */
class TreeEffectKeysTest {

    static PassiveTree tree;

    @BeforeAll
    static void load() throws IOException {
        tree = PassiveTree.load(Path.of("src/main/resources/data/passives/tree.json"));
    }

    private static boolean treeHas(final String stat) {
        for (final PassiveNode node : tree.nodes()) {
            for (final Modifier modifier : node.modifiers()) {
                if (modifier.stat().equals(stat) || modifier.stat().startsWith(stat + "@")) {
                    return true;
                }
            }
        }
        return false;
    }

    @Test
    void treeUsesTheNewKeys() {
        for (final String stat : List.of(TreeEffectKeys.ACTION_PENALTY, SupportKeys.WARCRY_SPEED, SupportKeys.TOTEM_PLACEMENT_SPEED,
                TreeEffectKeys.SHIELD_DEFENCES, TreeEffectKeys.RECOUP_SPEED, TreeEffectKeys.QUIVER_EFFECT, TreeEffectKeys.FOCUS_ES,
                TreeEffectKeys.BLIND_EFFECT, TreeEffectKeys.RAGE_LOSS_DELAY, TreeEffectKeys.MINION_REVIVE_SPEED, TreeEffectKeys.IGNITE_SPEED,
                TreeEffectKeys.ES_RECOUP_ELEMENTAL, TreeEffectKeys.RAGE_DECAY, TreeEffectKeys.CHARM_EFFECT, TreeEffectKeys.ARMOUR_BREAK_DURATION,
                TreeEffectKeys.WITHERED_EFFECT, TreeEffectKeys.THORNS_IGNORE_ARMOUR, TreeEffectKeys.CHAIN_CHANCE, TreeEffectKeys.LIFE_RECOVERY_RATE,
                TreeEffectKeys.ITEM_ATTR_REQUIREMENT, TreeEffectKeys.ARCANE_SURGE_ON_CRIT, TreeEffectKeys.AILMENT_SPEED, TreeEffectKeys.BUFF_TIME,
                TreeEffectKeys.DEFLECT_PREVENT, TreeEffectKeys.CORPSE_SAVE, TreeEffectKeys.STUN_THRESHOLD_PER_DEX, TreeEffectKeys.LIFE_CONVERT_ES,
                TreeEffectKeys.ACCURACY_PER_INT, TreeEffectKeys.RAGE_ON_IGNITE, TreeEffectKeys.ACCURACY_RANGE_PENALTY, TreeEffectKeys.DAZE_EFFECT,
                TreeEffectKeys.FORK_EXTRA_CHANCE, TreeEffectKeys.BODY_ARMOUR, TreeEffectKeys.CURSE_ENEMY_REGEN, TreeEffectKeys.ES_BYPASS,
                TreeEffectKeys.PROJECTILE_CHANCE_TWO, TreeEffectKeys.SLAM_AFTERSHOCK_CHANCE,
                SupportKeys.CURSE_DELAY, SupportKeys.PIN_DURATION, SupportKeys.CULL_THRESHOLD, SupportKeys.WARCRY_POWER)) {
            assertTrue(treeHas(stat), "樹上要用到 " + stat);
        }
    }

    @Test
    void ancestralBoostAndElementalAilmentConditions() {
        boolean ancestral = false;
        boolean elemental = false;
        for (final PassiveNode node : tree.nodes()) {
            for (final Modifier modifier : node.modifiers()) {
                ancestral |= modifier.tags().contains(TreeEffectKeys.ANCESTRAL_BOOST);
                elemental |= modifier.tags().contains(TreeConditions.VS_ELEMENTAL_AILMENT);
            }
        }
        assertTrue(ancestral, "先祖強化攻擊的傷害");
        assertTrue(elemental, "對元素異常的敵人");
        final TargetConditions.Target ignited = new TargetConditions.Target(1, false, false, false, false, false, false, false, 1, false, false,
                false, false, false, false, true, false, false);
        final TargetConditions.Target bleeding = new TargetConditions.Target(1, false, false, false, false, false, false, false, 1, false, false,
                false, false, false, false, false, true, false);
        assertTrue(TargetConditions.tags(ignited).contains(TreeConditions.VS_ELEMENTAL_AILMENT));
        assertFalse(TargetConditions.tags(bleeding).contains(TreeConditions.VS_ELEMENTAL_AILMENT), "流血不是元素異常");
        assertTrue(TargetConditions.tags(bleeding).contains(TreeConditions.VS_AILMENT));
    }

    @Test
    void recoupComesBackOverFourSecondsAndSpeedShortensIt() {
        final Recoup recoup = new Recoup();
        recoup.add(Recoup.LIFE, 80, 1.0);
        assertEquals(40, recoup.tick(40)[Recoup.LIFE], 1e-9, "4 秒回完：2 秒回一半");
        assertEquals(40, recoup.tick(100)[Recoup.LIFE], 1e-9);
        assertEquals(0, recoup.pending(Recoup.LIFE), 1e-9);
        recoup.add(Recoup.ES, 80, 2.0); // 回復速度加倍：2 秒回完
        assertEquals(80, recoup.tick(40)[Recoup.ES], 1e-9);
    }

    @Test
    void derivedStatsFromAttributesAndConversion() {
        final StatSheet sheet = new StatSheet();
        sheet.setBase(StatKeys.DEX, 100);
        sheet.setBase(StatKeys.INT, 80);
        sheet.setBase(StatKeys.LIFE_MAX, 1000);
        sheet.add(Modifier.flat(TreeEffectKeys.STUN_THRESHOLD_PER_DEX, 1, "樹"));
        sheet.add(Modifier.flat(TreeEffectKeys.ACCURACY_PER_INT, 1, "樹"));
        sheet.add(Modifier.flat(TreeEffectKeys.LIFE_CONVERT_ES, 4, "樹"));
        TreeEffectKeys.applyDerived(sheet);
        assertEquals(100, sheet.get(StatKeys.STUN_THRESHOLD), 1e-9);
        assertEquals(80, sheet.get(StatKeys.ACCURACY), 1e-9);
        assertEquals(960, sheet.get(StatKeys.LIFE_MAX), 1e-9, "4% 生命轉成護盾");
        assertEquals(40, sheet.get(StatKeys.ES_MAX), 1e-9);
    }

    @Test
    void penaltiesBuffsAndAilmentSpeed() {
        final StatSheet sheet = new StatSheet();
        assertEquals(0.5, TreeEffectKeys.actionPenalty(sheet, 0.5), 1e-9);
        sheet.add(Modifier.increased(TreeEffectKeys.ACTION_PENALTY, -0.06, "樹"));
        assertEquals(0.47, TreeEffectKeys.actionPenalty(sheet, 0.5), 1e-9, "減少 6% 懲罰：50% → 47%");
        sheet.add(Modifier.increased(TreeEffectKeys.BUFF_TIME, -0.10, "樹"));
        assertEquals(89, TreeEffectKeys.buffTicks(sheet, 80), "增益流逝慢 10%：80 tick → 89");
        sheet.add(Modifier.increased(TreeEffectKeys.AILMENT_SPEED, 0.12, "樹"));
        sheet.add(Modifier.increased(TreeEffectKeys.IGNITE_SPEED, 0.18, "樹"));
        assertEquals(1.30, TreeEffectKeys.ailmentSpeed(sheet, TreeEffectKeys.IGNITE_SPEED, Set.of()), 1e-9);
        assertEquals(1.12, TreeEffectKeys.ailmentSpeed(sheet, null, Set.of()), 1e-9);
        sheet.add(Modifier.increased(TreeEffectKeys.ITEM_ATTR_REQUIREMENT, -0.04, "樹"));
        assertEquals(0.96, TreeEffectKeys.requirementScale(sheet), 1e-9);
    }

    @Test
    void chancesAboveHundredAlwaysGiveOne() {
        final Random random = new Random(1);
        assertEquals(0, TreeEffectKeys.fromChance(0, random));
        assertEquals(1, TreeEffectKeys.fromChance(100, random));
        int total = 0;
        for (int i = 0; i < 2000; i++) {
            total += TreeEffectKeys.fromChance(60, random);
        }
        assertTrue(total > 1000 && total < 1400, "60% 機率：" + total);
    }

    @Test
    void blindAndWitheredEffectScale() {
        final List<Modifier> blind = HitEffects.blindModifiers(0, 1.5);
        assertEquals(-0.3, blind.get(0).value(), 1e-9, "致盲效果增加 50%：命中更少 30%");
        assertEquals(-HitEffects.BLIND_LESS, HitEffects.blindModifiers(0).get(0).value(), 1e-9);
        final List<Modifier> quiver = TreeEffectKeys.amplify(List.of(Modifier.flat(StatKeys.LIFE_MAX, 40, "箭袋"),
                Modifier.flat(StatKeys.FLAG_TRAP, 1, "開關")), 0.2, "箭袋效果");
        assertEquals(1, quiver.size(), "開關不放大");
        assertEquals(8, quiver.get(0).value(), 1e-9);
    }
}
