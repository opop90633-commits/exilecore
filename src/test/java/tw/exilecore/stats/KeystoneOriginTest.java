package tw.exilecore.stats;

import org.junit.jupiter.api.Test;
import tw.exilecore.character.Gear;
import tw.exilecore.combat.Impales;
import tw.exilecore.item.ItemBase;
import tw.exilecore.item.ItemSlot;
import tw.exilecore.passive.PassiveNode;
import tw.exilecore.passive.PassiveTree;

import java.io.IOException;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.Set;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** 2026-10-27 照站長：之前做成平衡等效的基石、核心天賦換回 PoE2 原作機制（KeystoneOriginKeys、Impales、Gear）。 */
class KeystoneOriginTest {

    private static ItemBase base(final String id, final ItemSlot slot, final String... tags) {
        return new ItemBase(id, id, "STONE", null, slot, Set.of(tags), 1, new ItemBase.Requirements(1, 0, 0, 0), Map.of(),
                1.2, 5, 0, 0, 0, 0, 0, null, 10, false, null, 1, 1, null);
    }

    private static StatSheet flag(final String key) {
        return new StatSheet().add(Modifier.flat(key, 1, "測試"));
    }

    @Test
    void giantsBloodLetsTwoHandedAxesMacesSwordsTakeAShield() {
        final ItemBase greatMace = base("mace2", ItemSlot.MAIN_HAND, "weapon", Tags.TWO_HAND, Tags.MACE, Tags.MELEE);
        final ItemBase quarterstaff = base("qstaff", ItemSlot.MAIN_HAND, "weapon", Tags.TWO_HAND, Tags.QUARTERSTAFF, Tags.MELEE);
        final ItemBase shield = base("shield", ItemSlot.OFF_HAND, "shield", "str_armour", "str_only");
        // 沒有巨人之血：雙手武器不能配盾
        assertNotNull(Gear.pairProblem(greatMace, shield));
        // 巨人之血只放寬雙手斧、錘、劍（長棍不行）
        assertTrue(KeystoneKeys.giantWeapon(greatMace.tags()));
        assertFalse(KeystoneKeys.giantWeapon(quarterstaff.tags()));
        // 2026-10-27 照原作做出雙持：兩手各拿一把雙手斧、錘、劍（這次之前副手空著給更多 25% 傷害當平衡等效）
        final ItemBase greatSword = base("sword2", ItemSlot.MAIN_HAND, "weapon", Tags.TWO_HAND, Tags.SWORD, Tags.MELEE);
        final StatSheet giant = flag(KeystoneKeys.FLAG_GIANTS_BLOOD);
        assertTrue(Gear.giantOffHand(greatMace, greatSword, giant));
        assertFalse(Gear.giantOffHand(greatMace, greatSword, new StatSheet()), "沒點巨人之血");
        assertFalse(Gear.giantOffHand(greatMace, quarterstaff, giant), "長棍不能單手持用");
        assertFalse(Gear.giantOffHand(quarterstaff, greatMace, giant), "主手是長棍");
    }

    @Test
    void lordOfTheWildsPutsASceptreInTheOffHand() {
        final ItemBase talisman = base("talis", ItemSlot.MAIN_HAND, "weapon", Tags.TWO_HAND, Tags.TALISMAN, Tags.MELEE);
        final ItemBase greatMace = base("mace2", ItemSlot.MAIN_HAND, "weapon", Tags.TWO_HAND, Tags.MACE, Tags.MELEE);
        final ItemBase sceptre = base("csceptre", ItemSlot.MAIN_HAND, "weapon", "one_hand", "sceptre", "caster");
        final StatSheet lord = flag(KeystoneKeys.FLAG_LORD_OF_WILDS);
        assertTrue(Gear.sceptreOffHand(talisman, sceptre, lord));
        assertFalse(Gear.sceptreOffHand(talisman, sceptre, new StatSheet()), "沒點荒野領主");
        assertFalse(Gear.sceptreOffHand(greatMace, sceptre, lord), "主手要是獸魂符");
    }

    @Test
    void impalesStoreThirtyPercentAndAttacksExtractTheStrongest() {
        assertEquals(30, KeystoneOriginKeys.impaleAmount(100, 0), 1e-9);
        assertEquals(36, KeystoneOriginKeys.impaleAmount(100, 0.2), 1e-9); // 刀刀見骨：增加 20% 穿刺效果
        final Impales impales = new Impales();
        final UUID enemy = UUID.randomUUID();
        final UUID me = UUID.randomUUID();
        final UUID other = UUID.randomUUID();
        impales.add(enemy, me, 30);
        impales.add(enemy, me, 50);
        impales.add(enemy, other, 90);
        assertEquals(50, impales.strongest(enemy, me), 1e-9, "只抽自己的");
        assertEquals(50, impales.extract(enemy, me), 1e-9);
        assertEquals(30, impales.extract(enemy, me), 1e-9);
        assertEquals(0, impales.extract(enemy, me), 1e-9);
        for (int i = 0; i < KeystoneOriginKeys.IMPALE_MAX + 5; i++) {
            impales.add(enemy, me, 100 + i);
        }
        assertEquals(KeystoneOriginKeys.IMPALE_MAX, impales.count(enemy), "最多 60 個，丟掉最弱的");
        assertEquals(0, impales.strongest(enemy, other), 1e-9, "別人的那個最弱，被擠掉了");
    }

    @Test
    void extractedImpaleIsAddedBeforeMitigationWithoutScaling() {
        final StatSheet attacker = new StatSheet()
                .setBase(StatKeys.ACCURACY, 1_000_000)
                .add(new Modifier(StatKeys.WEAPON_MIN, ModType.FLAT, 10, Set.of(Tags.PHYSICAL), "劍"))
                .add(new Modifier(StatKeys.WEAPON_MAX, ModType.FLAT, 10, Set.of(Tags.PHYSICAL), "劍"))
                .add(Modifier.flat(StatKeys.WEAPON_APS, 1.5, "劍"))
                .add(Modifier.flat(StatKeys.WEAPON_CRIT, 0, "劍"))
                .add(new Modifier(StatKeys.DAMAGE, ModType.INCREASED, 1.0, Set.of(Tags.PHYSICAL), "天賦")); // 10 → 20
        final StatSheet defender = new StatSheet().setBase(StatKeys.RESIST_MAX, 75);
        final SkillProfile attack = SkillProfile.defaultAttack(Set.of(Tags.SWORD));
        final HitResult plain = DamageCalculator.calculate(attacker, attack, defender, 0, new Random(1));
        final HitResult impaled = DamageCalculator.calculate(attacker.overlay(List.of(Modifier.flat(KeystoneOriginKeys.IMPALE_EXTRACT, 15, "穿刺"))),
                attack, defender, 0, new Random(1));
        assertEquals(20, plain.outgoing().get(DamageType.PHYSICAL), 1e-9);
        assertEquals(35, impaled.outgoing().get(DamageType.PHYSICAL), 1e-9, "穿刺的 15 不再吃增加 100%");
    }

    @Test
    void sniperScalesWithDistanceAndCursesStrengthenHalfway() {
        assertEquals(0, KeystoneOriginKeys.arrowCrit(0.4, 0), 1e-9);
        assertEquals(0.2, KeystoneOriginKeys.arrowCrit(0.4, 3.5), 1e-9);
        assertEquals(0.4, KeystoneOriginKeys.arrowCrit(0.4, 7), 1e-9);
        assertEquals(0.4, KeystoneOriginKeys.arrowCrit(0.4, 20), 1e-9);
        assertEquals(1100, KeystoneOriginKeys.curseHalfway(1000, 1200));
    }

    @Test
    void armourMovementPenaltiesFollowRef() {
        assertEquals(0.05, KeystoneOriginKeys.armourMovePenalty("CHEST", Set.of("armour", "body", "str_armour", "str_only")), 1e-9);
        assertEquals(0.04, KeystoneOriginKeys.armourMovePenalty("CHEST", Set.of("armour", "body", "str_armour", "dex_armour")), 1e-9);
        assertEquals(0.03, KeystoneOriginKeys.armourMovePenalty("CHEST", Set.of("armour", "body", "int_armour", "int_only")), 1e-9);
        assertEquals(0.03, KeystoneOriginKeys.armourMovePenalty("OFF_HAND", Set.of("shield", "str_armour", "str_only")), 1e-9);
        assertEquals(0.015, KeystoneOriginKeys.armourMovePenalty("OFF_HAND", Set.of("shield", "str_armour", "int_armour")), 1e-9);
        assertEquals(0, KeystoneOriginKeys.armourMovePenalty("OFF_HAND", Set.of("shield", "dex_armour", "dex_only")), 1e-9, "圓盾");
        assertEquals(0, KeystoneOriginKeys.armourMovePenalty("OFF_HAND", Set.of("shield", "int_armour", "focus")), 1e-9, "法器");
        assertEquals(0, KeystoneOriginKeys.armourMovePenalty("HEAD", Set.of("armour", "str_armour")), 1e-9);
    }

    @Test
    void treeUsesTheOriginalMechanics() throws IOException {
        final PassiveTree tree = PassiveTree.load(Path.of("src/main/resources/data/passives/tree.json"));
        final Map<String, String> expected = Map.of(
                "無限循環", KeystoneOriginKeys.SHOCK_REAPPLY,
                "分裂地面", KeystoneOriginKeys.EXTRA_FISSURE_CHANCE,
                "暴烈衝擊", KeystoneOriginKeys.BURNING_EXPLODE_CHANCE,
                "末日厄運", KeystoneOriginKeys.CURSE_LATE_MAGNITUDE,
                "狙擊手", KeystoneOriginKeys.ARROW_DISTANCE_CRIT,
                "氣勢", KeystoneOriginKeys.IGNORE_ARMOUR_MOVE_PENALTY,
                "命運終結", KeystoneOriginKeys.CURSE_NO_ES_RECHARGE,
                "刀刀見骨", KeystoneOriginKeys.IMPALE_MAGNITUDE,
                "穿刺機率", KeystoneOriginKeys.IMPALE_SPELL_CHANCE,
                "劈裂大地", KeystoneOriginKeys.SPLASH_CHANCE);
        for (final Map.Entry<String, String> entry : expected.entrySet()) {
            final PassiveNode node = tree.nodes().stream().filter(n -> entry.getKey().equals(n.name())).findFirst()
                    .orElseThrow(() -> new AssertionError(entry.getKey()));
            assertTrue(node.modifiers().stream().anyMatch(mod -> mod.stat().equals(entry.getValue())), entry.getKey() + " " + node.modifiers());
        }
        // 荒野領主不再直接給精魂（副手真的裝權杖）；巨人之血 2026-10-27 起真的雙持，不再有平衡等效的更多傷害
        final PassiveNode lord = tree.nodes().stream().filter(n -> "荒野領主".equals(n.name())).findFirst().orElseThrow();
        assertNull(lord.modifiers().stream().filter(mod -> mod.stat().equals(StatKeys.SPIRIT_MAX) && mod.type() == ModType.FLAT)
                .findFirst().orElse(null));
    }
}
