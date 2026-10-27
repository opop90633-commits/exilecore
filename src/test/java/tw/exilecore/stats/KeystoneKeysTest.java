package tw.exilecore.stats;

import org.junit.jupiter.api.Test;
import tw.exilecore.combat.Debuffs;
import tw.exilecore.passive.PassiveNode;
import tw.exilecore.passive.PassiveTree;

import java.io.IOException;
import java.nio.file.Path;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.random.RandomGenerator;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** 2026-10-26：天賦樹一次性的特殊效果（基石、核心天賦；KeystoneKeys）。 */
class KeystoneKeysTest {

    @Test
    void luckAndGlancingBlows() {
        assertEquals(8, KeystoneKeys.luck(3, 8, true, false));
        assertEquals(3, KeystoneKeys.luck(3, 8, false, true));
        assertEquals(3, KeystoneKeys.luck(3, 8, true, true)); // 幸運又不幸互相抵銷
        // 側身之擊：閃避不幸（命中 50% → 75%）、偏斜幸運（20% → 36%）
        assertEquals(0.75, KeystoneKeys.unluckyEvadeHitChance(0.5), 1e-9);
        assertEquals(0.36, KeystoneKeys.luckyChance(0.2), 1e-9);
    }

    @Test
    void heartstopperAndEquilibrium() {
        assertEquals(0.5, KeystoneKeys.heartstopper(0));
        assertEquals(0.5, KeystoneKeys.heartstopper(KeystoneKeys.HEARTSTOPPER_WINDOW));
        assertEquals(1.5, KeystoneKeys.heartstopper(KeystoneKeys.HEARTSTOPPER_WINDOW + 1));
        assertEquals(2, KeystoneKeys.equilibrium(0)); // 火→電
        assertEquals(1, KeystoneKeys.equilibrium(2)); // 電→冰
        assertEquals(0, KeystoneKeys.equilibrium(1)); // 冰→火
    }

    @Test
    void derivedConversions() {
        final StatSheet sheet = new StatSheet();
        sheet.setBase(StatKeys.EVASION, 1000);
        sheet.setBase(StatKeys.STR, 50);
        sheet.setBase(StatKeys.ACCURACY, 100);
        sheet.setBase(StatKeys.ES_MAX, 100);
        sheet.add(Modifier.flat(KeystoneKeys.EVASION_AS_ARMOUR, 8, "測試"));
        sheet.add(Modifier.flat(KeystoneKeys.ACCURACY_FROM_STR, 100, "測試"));
        sheet.add(Modifier.increased(KeystoneKeys.HELMET_ES, 0.7, "測試"));
        sheet.add(Modifier.flat(KeystoneKeys.BOOTS_AILMENT_THRESHOLD, 1, "測試"));
        sheet.add(Modifier.flat(KeystoneKeys.ARMOUR_STUN_THRESHOLD, 30, "測試"));
        sheet.add(Modifier.flat(KeystoneKeys.FLAG_GIANTS_BLOOD, 1, "測試"));
        sheet.add(Modifier.increased(KeystoneKeys.LIFE_COST_SPELL_DAMAGE, 0.4, "測試"));
        KeystoneKeys.applyDerived(sheet, List.of(new KeystoneKeys.Piece("HEAD", 0, 0, 40), new KeystoneKeys.Piece("FEET", 60, 90, 0),
                new KeystoneKeys.Piece("CHEST", 200, 0, 0)), 100, true);
        assertEquals(80, sheet.get(StatKeys.ARMOUR), 1e-9); // 閃避 8%
        assertEquals(150, sheet.get(StatKeys.ACCURACY), 1e-9); // + 力量
        assertEquals(128, sheet.get(StatKeys.ES_MAX), 1e-9); // 頭盔 40 × 70%
        assertEquals(60, sheet.flat(StatKeys.AILMENT_THRESHOLD, Set.of()), 1e-9); // 鞋子護甲、閃避較低的
        assertEquals(78, sheet.flat(StatKeys.STUN_THRESHOLD, Set.of()), 1e-9); // 防具護甲 260 × 30%
        assertEquals(-50, sheet.flat(StatKeys.LIFE_MAX, Set.of()), 1e-9); // 巨人之血：力量生命減半
        assertEquals(0.4, sheet.increased(StatKeys.DAMAGE, Set.of(Tags.SPELL)), 1e-9);
        assertEquals(0, sheet.increased(StatKeys.DAMAGE, Set.of(Tags.ATTACK)), 1e-9);
    }

    @Test
    void glancingBlowsMakesEvasionUnlucky() {
        final StatSheet attacker = new StatSheet().setBase(StatKeys.ACCURACY, 500);
        attacker.add(Modifier.flat(StatKeys.WEAPON_MIN, 10, "測試").withTags(Set.of(Tags.PHYSICAL)));
        attacker.add(Modifier.flat(StatKeys.WEAPON_MAX, 10, "測試").withTags(Set.of(Tags.PHYSICAL)));
        final StatSheet plain = new StatSheet().setBase(StatKeys.EVASION, 2000);
        final StatSheet glancing = new StatSheet().setBase(StatKeys.EVASION, 2000);
        glancing.add(Modifier.flat(KeystoneKeys.FLAG_GLANCING, 1, "測試"));
        final SkillProfile skill = SkillProfile.defaultAttack(Set.of());
        final RandomGenerator a = RandomGenerator.of("L64X128MixRandom");
        int plainHits = 0;
        int glancingHits = 0;
        for (int i = 0; i < 4000; i++) {
            plainHits += DamageCalculator.calculate(attacker, skill, plain, 0, a).hit() ? 1 : 0;
            glancingHits += DamageCalculator.calculate(attacker, skill, glancing, 0, a).hit() ? 1 : 0;
        }
        assertTrue(glancingHits > plainHits, plainHits + " / " + glancingHits);
    }

    @Test
    void whispersOfDoomAllowsTwoCurses() {
        final Debuffs debuffs = new Debuffs();
        final UUID target = UUID.randomUUID();
        final UUID me = UUID.randomUUID();
        debuffs.applyCurse(target, new Debuffs.Debuff("a", me, 100, 0.1, false), "a", 2);
        debuffs.applyCurse(target, new Debuffs.Debuff("b", me, 200, 0.1, false), "b", 2);
        assertTrue(debuffs.has(target, "a", 0) && debuffs.has(target, "b", 0));
        debuffs.applyCurse(target, new Debuffs.Debuff("c", me, 300, 0.1, false), "c", 2);
        assertFalse(debuffs.has(target, "a", 0)); // 最早結束的拿掉
        assertTrue(debuffs.has(target, "b", 0) && debuffs.has(target, "c", 0));
        debuffs.applyCurse(target, new Debuffs.Debuff("d", me, 400, 0.1, false), "d");
        assertTrue(debuffs.has(target, "d", 0));
        assertFalse(debuffs.has(target, "b", 0) || debuffs.has(target, "c", 0)); // 沒有基石只能一個
    }

    @Test
    void keystonesAreActiveInTree() throws IOException {
        final PassiveTree tree = PassiveTree.load(Path.of("src/main/resources/data/passives/tree.json"));
        for (final String name : List.of("側身之擊", "幻景綠洲", "巨人之血", "能量連結", "心靈永止", "原初飢餓", "靈能魔符", "可信之情",
                "元素之相", "滅世之語", "荒野湧浪咒", "創傷信仰", "荒野領主", "空掌之術", "祭祀終章")) {
            final PassiveNode node = tree.nodes().stream().filter(n -> name.equals(n.name()) && n.type() == PassiveNode.Type.KEYSTONE)
                    .findFirst().orElseThrow(() -> new AssertionError(name));
            assertFalse(node.modifiers().isEmpty(), name);
            assertTrue(node.description().stream().noneMatch(line -> line.contains("尚未生效")), name + " " + node.description());
        }
    }
}
