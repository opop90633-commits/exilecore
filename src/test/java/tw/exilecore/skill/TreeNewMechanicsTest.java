package tw.exilecore.skill;

import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import tw.exilecore.passive.PassiveNode;
import tw.exilecore.passive.PassiveTree;
import tw.exilecore.stats.DamageType;
import tw.exilecore.stats.ModType;
import tw.exilecore.stats.Modifier;
import tw.exilecore.stats.StatKeys;
import tw.exilecore.stats.StatSheet;
import tw.exilecore.stats.Tags;
import tw.exilecore.stats.TreeNewMechanicKeys;

import java.io.IOException;
import java.nio.file.Path;
import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 2026-10-26：天賦樹「新機制」類天賦（統治者、易爆、切割、傀儡大師、秘術增強、先祖強化…）照 PoE2 0.5 的數值，
 * 而且樹上真的用到這些屬性鍵（{@link TreeMechanics}）。
 */
class TreeNewMechanicsTest {

    static final Path DATA = Path.of("src/main/resources/data");
    static PassiveTree tree;
    static SkillConfig config;

    @BeforeAll
    static void load() throws IOException {
        tree = PassiveTree.load(DATA.resolve("passives/tree.json"));
        config = new SkillConfig();
        config.load(DATA);
    }

    private static boolean treeHas(final String stat) {
        for (final PassiveNode node : tree.nodes()) {
            for (final Modifier modifier : node.modifiers()) {
                if (modifier.stat().equals(stat)) {
                    return true;
                }
            }
        }
        return false;
    }

    @Test
    void surpassingChanceGivesOneEachHundred() {
        assertEquals(0, TreeMechanics.surpassing(0, 0.0));
        assertEquals(1, TreeMechanics.surpassing(15, 0.10));
        assertEquals(0, TreeMechanics.surpassing(15, 0.20));
        assertEquals(3, TreeMechanics.surpassing(215, 0.10), "PoE2：215% 必定 2 次，再擲 15%");
        assertEquals(2, TreeMechanics.surpassing(215, 0.99));
    }

    @Test
    void archonDurationRecoveryAndEffect() {
        final StatSheet sheet = new StatSheet();
        assertEquals(200, TreeMechanics.archonTicks(sheet), "PoE2：統治者增益預設 10 秒");
        assertEquals(400, TreeMechanics.archonRecoveryTicks(sheet), "PoE2：恢復期 20 秒");
        sheet.add(Modifier.increased(TreeNewMechanicKeys.ARCHON_DURATION, 0.30, "恆久統治者"));
        sheet.add(Modifier.increased(TreeNewMechanicKeys.ARCHON_RECOVERY, 0.25, "復行統治者"));
        assertEquals(260, TreeMechanics.archonTicks(sheet));
        assertEquals(320, TreeMechanics.archonRecoveryTicks(sheet), "恢復期結束速度加快 25%：20 ÷ 1.25 = 16 秒");
        sheet.add(Modifier.flat(TreeNewMechanicKeys.FLAG_ARCHON_NO_RECOVERY, 1, "測試"));
        assertEquals(0, TreeMechanics.archonRecoveryTicks(sheet));

        final StatSheet effect = new StatSheet();
        effect.add(Modifier.increased(TreeNewMechanicKeys.ARCHON_EFFECT, 0.20, "統治者效果"));
        effect.add(Modifier.flat(TreeNewMechanicKeys.ARCHON_GRANT_RESIST, 20, "振奮統治者"));
        final StatSheet player = new StatSheet().addAll(TreeMechanics.archonModifiers(TreeMechanics.Archon.ELEMENTAL, effect));
        assertEquals(1 + 0.25 * 1.2, player.more(StatKeys.DAMAGE, Set.of(Tags.SPELL, Tags.ELEMENTAL, Tags.FIRE)), 1e-9,
                "元素統治者：法術元素傷害更多 25%（乘統治者效果）");
        assertEquals(0, player.more(StatKeys.DAMAGE, Set.of(Tags.SPELL, Tags.PHYSICAL)), 1e-9, "法術不能造成非元素傷害");
        assertEquals(1.0, player.more(StatKeys.DAMAGE, Set.of(Tags.ATTACK, Tags.PHYSICAL)), 1e-9, "攻擊不受影響");
        assertEquals(24, player.flat(StatKeys.RESIST_FIRE, Set.of()), 1e-9, "另外給的抗性也乘效果");
        final StatSheet undeath = new StatSheet().addAll(TreeMechanics.archonModifiers(TreeMechanics.Archon.UNDEATH, new StatSheet()));
        assertEquals(1.25, undeath.more(StatKeys.DAMAGE, Set.of(Tags.MINION)), 1e-9);
        assertEquals(3.0, undeath.more(StatKeys.COOLDOWN_RECOVERY, Set.of("command")), 1e-9, "指令技能冷卻回復速度更多 200%");
    }

    @Test
    void volatilityExplodesForHundredPerStack() {
        final StatSheet sheet = new StatSheet();
        assertEquals(80, TreeMechanics.volatilityDelay(sheet), "PoE2：4 秒後爆炸");
        assertEquals(200, TreeMechanics.volatilityMax(sheet));
        sheet.add(Modifier.flat(TreeNewMechanicKeys.VOLATILITY_MAX, 30, "受控混沌"));
        assertEquals(30, TreeMechanics.volatilityMax(sheet));
        assertEquals(500, TreeMechanics.volatilityDamage(5, new StatSheet()), 1e-9, "每層 100 物理");
        final StatSheet armoured = new StatSheet();
        armoured.add(Modifier.flat(StatKeys.ARMOUR, 5000, "護甲"));
        assertTrue(TreeMechanics.volatilityDamage(5, armoured) < 500, "物理傷害吃護甲");
        final StatSheet crit = new StatSheet();
        crit.add(Modifier.flat(TreeNewMechanicKeys.VOLATILITY_POWER_CRIT, 1, "精確易爆"));
        final StatSheet power = new StatSheet().addAll(TreeMechanics.volatilePower(12, crit));
        assertEquals(12, power.flat(StatKeys.damageExtra(DamageType.CHAOS), Set.of()), 1e-9, "易爆之力：每層 1% 傷害額外獲得為混沌");
        assertEquals(0.12, power.increased(StatKeys.CRIT_CHANCE, Set.of()), 1e-9);
    }

    @Test
    void puppetMasterAndArcaneSurge() {
        final StatSheet sheet = new StatSheet();
        assertEquals(5, TreeMechanics.puppetMax(sheet));
        sheet.add(Modifier.flat(TreeNewMechanicKeys.PUPPET_MAX, 1, "傀儡大師"));
        sheet.add(Modifier.increased(TreeNewMechanicKeys.PUPPET_EFFECT, 0.5, "傀儡大師效果"));
        assertEquals(6, TreeMechanics.puppetMax(sheet));
        final StatSheet buffed = new StatSheet().addAll(TreeMechanics.puppetModifiers(2, sheet));
        assertEquals(0.30, buffed.increased(StatKeys.ATTACK_SPEED, Set.of("command")), 1e-9, "2 層 × 10% × 1.5");
        assertEquals(0.09, buffed.increased(StatKeys.ATTACK_SPEED, Set.of(Tags.MINION)), 1e-9, "召喚物 3% 技能速度");
        assertEquals(0, buffed.increased(StatKeys.ATTACK_SPEED, Set.of(Tags.ATTACK)), 1e-9);

        final StatSheet surge = new StatSheet().addAll(TreeMechanics.arcaneSurge(new StatSheet()));
        assertEquals(0.15, surge.increased(StatKeys.CAST_SPEED, Set.of()), 1e-9, "PoE2：秘術增強增加 15% 施法速度");
        assertEquals(1.20, surge.more(StatKeys.MANA_REGEN, Set.of()), 1e-9, "魔力回復更多 20%");
        final StatSheet life = new StatSheet();
        life.add(Modifier.flat(TreeNewMechanicKeys.FLAG_ARCANE_SURGE_LIFE, 1, "測試"));
        life.add(Modifier.increased(TreeNewMechanicKeys.ARCANE_SURGE_EFFECT, 0.5, "測試"));
        final StatSheet lifeSurge = new StatSheet().addAll(TreeMechanics.arcaneSurge(life));
        assertEquals(1.30, lifeSurge.more(StatKeys.LIFE_REGEN, Set.of()), 1e-9);
        assertEquals(1.0, lifeSurge.more(StatKeys.MANA_REGEN, Set.of()), 1e-9);
    }

    @Test
    void ancestralBoostedSlamHasMoreDamageAndArea() {
        final SkillBuild slam = SkillBuild.of(config, config.skill("boneshatter") != null && config.skill("boneshatter").tags().contains(Tags.SLAM)
                ? config.skill("boneshatter") : firstWith(Tags.SLAM), 10, 50, false, List.of(), Set.of(Tags.MACE), new StatSheet());
        final StatSheet sheet = new StatSheet();
        sheet.add(Modifier.increased(TreeNewMechanicKeys.ANCESTRAL_AREA, 0.30, "先祖強化範圍"));
        final SkillBuild boosted = TreeMechanics.ancestralBoost(slam, sheet);
        assertTrue(boosted.profile().tags().contains(TreeNewMechanicKeys.TAG_ANCESTRAL));
        assertTrue(boosted.profile().tags().contains(tw.exilecore.stats.TreeMechanicKeys.EMPOWERED), "PoE2：先祖強化算強化攻擊");
        assertEquals(slam.areaScale() * Math.sqrt(1.55), boosted.areaScale(), 1e-9, "範圍增加 25% + 30%");
    }

    private static SkillDefinition firstWith(final String tag) {
        return config.all().stream().filter(s -> s.tags().contains(tag) && s.isAttack()).findFirst().orElseThrow();
    }

    @Test
    void arcaneSurgeSupportGrantsTheBuff() {
        final SupportDefinition surge = config.support("arcane_surge");
        assertTrue(surge.modifiers().stream().anyMatch(m -> m.stat().equals(TreeNewMechanicKeys.ARCANE_SURGE_ON_SPEND) && m.value() == 100),
                "PoE2：湧詠 = 累計消耗 100% 最大魔力後獲得秘術增強");
        assertFalse(surge.modifiers().stream().anyMatch(m -> m.stat().equals(StatKeys.CAST_SPEED)));
    }

    @Test
    void treeUsesTheNewMechanics() {
        for (final String stat : List.of(TreeNewMechanicKeys.ARCHON_EFFECT, TreeNewMechanicKeys.ARCHON_DURATION, TreeNewMechanicKeys.ARCHON_RECOVERY,
                TreeNewMechanicKeys.ARCHON_ELEMENTAL_ON_MANA, TreeNewMechanicKeys.ARCHON_UNDEATH_ON_COMMAND, TreeNewMechanicKeys.VOLATILITY_ON_KILL,
                TreeNewMechanicKeys.VOLATILITY_DELAY, TreeNewMechanicKeys.INCISION_CHANCE, TreeNewMechanicKeys.PUPPET_CHANCE,
                TreeNewMechanicKeys.ARCANE_SURGE_EFFECT, TreeNewMechanicKeys.ANCESTRAL_AREA, TreeNewMechanicKeys.SPRINT_MOVEMENT,
                TreeNewMechanicKeys.TOTEM_RECENT_SPEED, TreeNewMechanicKeys.AFTERSHOCK_CHANCE, TreeNewMechanicKeys.CHARM_CHAIN,
                TreeNewMechanicKeys.REMNANT_EFFECT, TreeNewMechanicKeys.HEAVY_STUN_LIFE, TreeNewMechanicKeys.COMPANION_TAKES,
                TreeNewMechanicKeys.CRIT_BASE_EXTRA)) {
            assertTrue(treeHas(stat), "天賦樹上應該有 " + stat);
        }
    }

    @Test
    void heraldCritIsAddedToBaseCrit() {
        final StatSheet sheet = new StatSheet();
        sheet.add(new Modifier(TreeNewMechanicKeys.CRIT_BASE_EXTRA, ModType.FLAT, 8, Set.of(Tags.HERALD), "天賦"));
        assertEquals(8, sheet.flat(TreeNewMechanicKeys.CRIT_BASE_EXTRA, Set.of(Tags.HERALD, Tags.SPELL)), 1e-9);
        assertEquals(0, sheet.flat(TreeNewMechanicKeys.CRIT_BASE_EXTRA, Set.of(Tags.SPELL)), 1e-9, "只有捷光環技能");
    }
}
