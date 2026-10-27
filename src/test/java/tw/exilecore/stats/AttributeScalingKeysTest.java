package tw.exilecore.stats;

import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import tw.exilecore.passive.PassiveNode;
import tw.exilecore.passive.PassiveTree;
import tw.exilecore.skill.SupportColours;

import java.io.IOException;
import java.nio.file.Path;
import java.util.List;
import java.util.Random;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 2026-10-26：PoE2 0.5 天賦樹「每 x 點屬性」「照裝備數值換算」「插著幾顆某色輔助寶石」「對特定敵人的命中、撲殺」
 * （{@link AttributeScalingKeys}、{@link SupportColours}）。
 */
class AttributeScalingKeysTest {

    static PassiveTree tree;

    @BeforeAll
    static void load() throws IOException {
        tree = PassiveTree.load(Path.of("src/main/resources/data/passives/tree.json"));
    }

    private static boolean treeHas(final String stat, final String tag) {
        for (final PassiveNode node : tree.nodes()) {
            for (final Modifier modifier : node.modifiers()) {
                if ((modifier.stat().equals(stat) || modifier.stat().startsWith(stat + "@")) && (tag == null || modifier.tags().contains(tag))) {
                    return true;
                }
            }
        }
        return false;
    }

    @Test
    void treeUsesTheNewKeys() {
        for (final String stat : List.of(AttributeScalingKeys.AILMENT_THRESHOLD_PER_DEX, AttributeScalingKeys.STUN_THRESHOLD_PER_STR,
                AttributeScalingKeys.LIFE_PER_4_DEX, AttributeScalingKeys.DAMAGE_PER_15_STR, // 每 10 點力量的法術傷害是神諭者專屬節點（樹上不放）
                AttributeScalingKeys.ATTACK_SPEED_PER_25_DEX, AttributeScalingKeys.EVASION_PER_10_INT, AttributeScalingKeys.DAMAGE_PER_5_LOWEST,
                AttributeScalingKeys.SPELL_DAMAGE_PER_100_MANA, AttributeScalingKeys.DAMAGE_PER_BLOCK, AttributeScalingKeys.ATTACK_SPEED_PER_400_ACCURACY,
                AttributeScalingKeys.ES_PER_8_HELMET_ARMOUR, AttributeScalingKeys.ARMOUR_PER_BOOTS_ES, AttributeScalingKeys.ES_PER_BODY_EVASION,
                AttributeScalingKeys.EVASION_PER_GLOVES_ARMOUR, AttributeScalingKeys.EVASION_PER_HELMET_ES, AttributeScalingKeys.ATTACK_DAMAGE_PER_75_SHIELD,
                AttributeScalingKeys.HELMET_ARMOUR_BLEED_IMMUNE, AttributeScalingKeys.HELMET_EVASION_POISON_IMMUNE,
                AttributeScalingKeys.gated(AttributeScalingKeys.RED5, "resist.max.fire"), AttributeScalingKeys.gated(AttributeScalingKeys.RGB5, "resist.max.cold"),
                AttributeScalingKeys.gated(AttributeScalingKeys.BLUE10, StatKeys.MANA_MAX),
                AttributeScalingKeys.gated(AttributeScalingKeys.MAJORITY_BLUE, "flag.immune.chill"))) {
            assertTrue(treeHas(stat, null), "樹上要用到 " + stat);
        }
        assertTrue(treeHas(StatKeys.ACCURACY, TreeConditions.VS_RARE), "對稀有、傳奇的命中");
        assertTrue(treeHas(StatKeys.ACCURACY, TreeConditions.VS_BLEEDING), "對流血的命中");
        assertTrue(treeHas(StatKeys.ACCURACY, TreeConditions.VS_CLOSE), "近距離的命中");
        // 「弓的攻擊有撲殺」是神諭者專屬節點（樹上不放），換算見 cullingFollowsTheHitContext
        assertTrue(treeHas(StatKeys.FLAG_CULLING, TreeConditions.VS_MARKED), "對被標記的撲殺");
        assertTrue(treeHas(StatKeys.FLAG_CULLING + "@" + TreeConditions.COMPANION, TreeConditions.VS_BEAST), "夥伴在場時對野獸撲殺");
        assertTrue(treeHas(SupportKeys.CULL_THRESHOLD, TreeConditions.VS_IMMOBILISED));
        assertTrue(treeHas(SupportKeys.CULL_THRESHOLD, TreeConditions.VS_RARE));
    }

    @Test
    void supportColoursFollowPoe2() {
        assertEquals(SupportColours.RED, SupportColours.colour("heft"));
        assertEquals(SupportColours.GREEN, SupportColours.colour("pierce"));
        assertEquals(SupportColours.BLUE, SupportColours.colour("faster_casting"));
        assertEquals(0, SupportColours.colour("vruuns_aftermath"), "白色的不算任何顏色");
        final SupportColours.Counts counts = SupportColours.count(List.of("heft", "brutality", "pierce", "vruuns_aftermath"));
        assertEquals(new SupportColours.Counts(2, 1, 0, 4), counts);
    }

    @Test
    void supportGatesNeedEnoughGems() {
        final StatSheet sheet = new StatSheet();
        sheet.add(Modifier.flat(AttributeScalingKeys.gated(AttributeScalingKeys.RED5, "resist.max.fire"), 2, "樹"));
        sheet.add(Modifier.increased(AttributeScalingKeys.gated(AttributeScalingKeys.BLUE10, StatKeys.MANA_MAX), 0.05, "樹"));
        sheet.add(Modifier.flat(AttributeScalingKeys.gated(AttributeScalingKeys.MAJORITY_RED, "flag.immune.ignite"), 1, "樹"));
        sheet.add(Modifier.flat(AttributeScalingKeys.gated(AttributeScalingKeys.RGB5, "resist.max.cold"), 1, "樹"));
        sheet.setBase(StatKeys.MANA_MAX, 100);
        final StatSheet few = new StatSheet().addAll(sheet.allModifiers()).setBase(StatKeys.MANA_MAX, 100);
        AttributeScalingKeys.applyDerived(few, AttributeScalingKeys.Gear.NONE, new AttributeScalingKeys.Supports(4, 3, 9, 16));
        assertEquals(0, few.get("resist.max.fire"), 1e-9, "4 顆紅色不夠");
        assertEquals(100, few.get(StatKeys.MANA_MAX), 1e-9, "9 顆藍色不夠");
        assertFalse(StatKeys.flag(few, "flag.immune.ignite"));
        AttributeScalingKeys.applyDerived(sheet, AttributeScalingKeys.Gear.NONE, new AttributeScalingKeys.Supports(11, 5, 10, 26));
        assertEquals(2, sheet.get("resist.max.fire"), 1e-9);
        assertEquals(105, sheet.get(StatKeys.MANA_MAX), 1e-9);
        assertFalse(StatKeys.flag(sheet, "flag.immune.ignite"), "26 顆裡 11 顆紅色不過半");
        assertEquals(1, sheet.get("resist.max.cold"), 1e-9, "紅綠藍各 5 顆以上");
        assertFalse(AttributeScalingKeys.gate(AttributeScalingKeys.MAJORITY_RED, new AttributeScalingKeys.Supports(13, 0, 0, 26)), "剛好一半不算過半");
        assertTrue(AttributeScalingKeys.gate(AttributeScalingKeys.MAJORITY_RED, new AttributeScalingKeys.Supports(14, 0, 0, 26)));
    }

    @Test
    void perAttributeAndManaAndBlock() {
        final StatSheet sheet = new StatSheet().setBase(StatKeys.STR, 100).setBase(StatKeys.DEX, 52).setBase(StatKeys.INT, 31)
                .setBase(StatKeys.MANA_MAX, 450).setBase(StatKeys.BLOCK_CHANCE, 80);
        sheet.add(Modifier.flat(AttributeScalingKeys.AILMENT_THRESHOLD_PER_DEX, 4, "樹"));
        sheet.add(Modifier.flat(AttributeScalingKeys.STUN_THRESHOLD_PER_STR, 3, "樹"));
        sheet.add(Modifier.flat(AttributeScalingKeys.LIFE_PER_4_DEX, 1, "樹"));
        sheet.add(Modifier.increased(AttributeScalingKeys.SPELL_DAMAGE_PER_10_STR, 0.02, "樹"));
        sheet.add(Modifier.increased(AttributeScalingKeys.DAMAGE_PER_15_STR, 0.01, "樹"));
        sheet.add(Modifier.increased(AttributeScalingKeys.ATTACK_SPEED_PER_25_DEX, 0.01, "樹"));
        sheet.add(Modifier.increased(AttributeScalingKeys.EVASION_PER_10_INT, 0.03, "樹"));
        sheet.add(Modifier.increased(AttributeScalingKeys.DAMAGE_PER_5_LOWEST, 0.02, "樹"));
        sheet.add(Modifier.increased(AttributeScalingKeys.SPELL_DAMAGE_PER_100_MANA, 0.03, "樹"));
        sheet.add(Modifier.increased(AttributeScalingKeys.DAMAGE_PER_BLOCK, 0.01, "樹"));
        AttributeScalingKeys.applyDerived(sheet, AttributeScalingKeys.Gear.NONE, AttributeScalingKeys.Supports.NONE);
        assertEquals(208, sheet.get(StatKeys.AILMENT_THRESHOLD), 1e-9, "52 敏捷 × 4");
        assertEquals(300, sheet.get(StatKeys.STUN_THRESHOLD), 1e-9);
        assertEquals(13, sheet.get(StatKeys.LIFE_MAX), 1e-9, "52 / 4");
        assertEquals(0.03 * 3, sheet.increased(StatKeys.EVASION, Set.of()), 1e-9, "31 智慧 → 3 份");
        assertEquals(0.01 * 2, sheet.increased(StatKeys.ATTACK_SPEED, Set.of()), 1e-9, "52 敏捷 → 2 份");
        // 傷害：力量 6 份 1%、最低屬性（31 智慧）6 份 2%、格擋照上限 50%
        assertEquals(0.06 + 0.12 + 0.50, sheet.increased(StatKeys.DAMAGE, Set.of()), 1e-9);
        // 法術再加：力量 10 份 2%、450 魔力 4 份 3%
        assertEquals(0.06 + 0.12 + 0.50 + 0.20 + 0.12, sheet.increased(StatKeys.DAMAGE, Set.of(Tags.SPELL)), 1e-9);
    }

    @Test
    void equipmentValuesConvert() {
        final List<KeystoneKeys.Piece> pieces = List.of(new KeystoneKeys.Piece("HEAD", 81, 20, 30), new KeystoneKeys.Piece("LEGS", 40, 0, 0),
                new KeystoneKeys.Piece("FEET", 0, 0, 25), new KeystoneKeys.Piece("CHEST", 300, 125, 0), new KeystoneKeys.Piece("OFF_HAND", 100, 80, 0));
        final StatSheet sheet = new StatSheet();
        sheet.add(Modifier.flat(AttributeScalingKeys.ES_PER_8_HELMET_ARMOUR, 1, "樹"));
        sheet.add(Modifier.flat(AttributeScalingKeys.EVASION_PER_HELMET_ES, 1, "樹"));
        sheet.add(Modifier.flat(AttributeScalingKeys.EVASION_PER_GLOVES_ARMOUR, 1, "樹"));
        sheet.add(Modifier.flat(AttributeScalingKeys.ARMOUR_PER_BOOTS_ES, 2, "樹"));
        sheet.add(Modifier.flat(AttributeScalingKeys.ES_PER_BODY_EVASION, 12, "樹"));
        sheet.add(Modifier.increased(AttributeScalingKeys.ATTACK_DAMAGE_PER_75_SHIELD, 0.04, "樹"));
        sheet.add(Modifier.flat(AttributeScalingKeys.HELMET_ARMOUR_BLEED_IMMUNE, 1, "樹"));
        sheet.add(Modifier.flat(AttributeScalingKeys.HELMET_EVASION_POISON_IMMUNE, 1, "樹"));
        AttributeScalingKeys.applyDerived(sheet, new AttributeScalingKeys.Gear(pieces, 100, 50, 90), AttributeScalingKeys.Supports.NONE);
        assertEquals(10 + 10, sheet.get(StatKeys.ES_MAX), 1e-9, "頭盔 81 護甲 → 10、胸甲 125 閃避 → 10（胸甲本地效果改由 TreeNewMechanicKeys 算）");
        assertEquals(30 + 40, sheet.get(StatKeys.EVASION), 1e-9, "頭盔 30 護盾、護腿 40 護甲");
        assertEquals(50, sheet.get(StatKeys.ARMOUR), 1e-9, "鞋子 25 護盾 × 2（力量需求換護甲改由 TreeNewMechanicKeys 算）");
        assertEquals(0.08, sheet.increased(StatKeys.DAMAGE, Set.of(Tags.ATTACK)), 1e-9, "盾 180 → 2 份");
        assertEquals(0, sheet.increased(StatKeys.DAMAGE, Set.of(Tags.SPELL)), 1e-9, "只有攻擊");
        assertTrue(StatKeys.flag(sheet, "flag.immune.bleed"), "頭盔護甲 81 > 閃避 20");
        assertFalse(StatKeys.flag(sheet, "flag.immune.poison"));
    }

    @Test
    void attackSpeedPerAccuracyIsCapped() {
        final StatSheet sheet = new StatSheet().setBase(StatKeys.ACCURACY, 1000);
        sheet.add(Modifier.increased(AttributeScalingKeys.ATTACK_SPEED_PER_400_ACCURACY, 0.01, "樹"));
        AttributeScalingKeys.applyLate(sheet);
        assertEquals(0.02, sheet.increased(StatKeys.ATTACK_SPEED, Set.of()), 1e-9);
        final StatSheet big = new StatSheet().setBase(StatKeys.ACCURACY, 20000);
        big.add(Modifier.increased(AttributeScalingKeys.ATTACK_SPEED_PER_400_ACCURACY, 0.01, "樹"));
        AttributeScalingKeys.applyLate(big);
        assertEquals(0.20, big.increased(StatKeys.ATTACK_SPEED, Set.of()), 1e-9, "最多 20%");
    }

    @Test
    void cullingFollowsTheHitContext() {
        final StatSheet sheet = new StatSheet();
        sheet.add(new Modifier(StatKeys.FLAG_CULLING, ModType.FLAT, 1, Set.of(Tags.ATTACK, Tags.BOW), "樹"));
        sheet.add(new Modifier(StatKeys.FLAG_CULLING, ModType.FLAT, 1, Set.of(TreeConditions.VS_MARKED), "樹"));
        sheet.add(new Modifier(SupportKeys.CULL_THRESHOLD, ModType.INCREASED, 0.4, Set.of(TreeConditions.VS_RARE), "樹"));
        assertFalse(StatKeys.flag(sheet, StatKeys.FLAG_CULLING), "不是每一下都撲殺");
        assertTrue(AttributeScalingKeys.culls(sheet, Set.of(Tags.ATTACK, Tags.BOW, Tags.PROJECTILE)));
        assertFalse(AttributeScalingKeys.culls(sheet, Set.of(Tags.SPELL)));
        assertTrue(AttributeScalingKeys.culls(sheet, Set.of(Tags.SPELL, TreeConditions.VS_MARKED)));
        assertEquals(1.4, AttributeScalingKeys.cullScale(sheet, Set.of(TreeConditions.VS_RARE)), 1e-9);
        assertEquals(1.0, AttributeScalingKeys.cullScale(sheet, Set.of()), 1e-9);
    }

    @Test
    void accuracyAgainstSpecificEnemiesCountsOnTheHit() {
        final StatSheet attacker = new StatSheet().setBase(StatKeys.ACCURACY, 1)
                .add(new Modifier(StatKeys.WEAPON_MIN, ModType.FLAT, 10, Set.of(Tags.PHYSICAL), "劍"))
                .add(new Modifier(StatKeys.WEAPON_MAX, ModType.FLAT, 10, Set.of(Tags.PHYSICAL), "劍"))
                .add(Modifier.flat(StatKeys.WEAPON_APS, 1.5, "劍"))
                .add(new Modifier(StatKeys.ACCURACY, ModType.FLAT, 1_000_000, Set.of(TreeConditions.VS_RARE), "樹"));
        final StatSheet defender = new StatSheet().setBase(StatKeys.EVASION, 100_000).setBase(StatKeys.RESIST_MAX, 75);
        final SkillProfile plain = SkillProfile.defaultAttack(Set.of());
        final SkillProfile vsRare = plain.derive(plain.id(), Set.of(TreeConditions.VS_RARE), Set.of());
        final Random random = new Random(5);
        int plainHits = 0;
        int rareHits = 0;
        for (int i = 0; i < 400; i++) {
            plainHits += DamageCalculator.calculate(attacker, plain, defender, 0, random).hit() ? 1 : 0;
            rareHits += DamageCalculator.calculate(attacker, vsRare, defender, 0, random).hit() ? 1 : 0;
        }
        assertTrue(plainHits < 60, "沒有條件時幾乎打不中：" + plainHits);
        assertEquals(400, rareHits, "對稀有怪的命中算進去");
    }
}
