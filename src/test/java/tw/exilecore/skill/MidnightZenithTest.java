package tw.exilecore.skill;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** 2026-11-07 照站長「守衛新增暮色瓣落、遺忘看守」：午夜天頂的花瓣、冰晶上限，暮色瓣落的冰晶生命。 */
class MidnightZenithTest {

    @Test
    void petalsAccumulateUntilTen() {
        assertArrayEquals(new int[] {3, 0}, MidnightZenith.gain(0, 3, 0, 10));
        assertArrayEquals(new int[] {9, 0}, MidnightZenith.gain(6, 3, 0, 10));
    }

    @Test
    void tenPetalsCreateFiveCrystals() {
        assertArrayEquals(new int[] {0, 5}, MidnightZenith.gain(7, 3, 0, 10));
        // 傳奇怪（強度 20）一次 20 片：只試一次，剩 10 片等下一次獲得
        assertArrayEquals(new int[] {10, 5}, MidnightZenith.gain(0, 20, 0, 10));
        assertArrayEquals(new int[] {1, 5}, MidnightZenith.gain(10, 1, 5, 10));
    }

    @Test
    void limitBlocksAndCapsCreation() {
        assertArrayEquals(new int[] {15, 0}, MidnightZenith.gain(5, 10, 10, 10), "到上限：不用花瓣");
        assertArrayEquals(new int[] {0, 2}, MidnightZenith.gain(0, 10, 8, 10), "放到上限為止");
        assertArrayEquals(new int[] {12, 0}, MidnightZenith.gain(12, 0, 0, 10), "沒獲得就不試");
    }

    @Test
    void maxCrystalsFromQuality() {
        assertEquals(10, MidnightZenith.maxCrystals(0, 0));
        assertEquals(12, MidnightZenith.maxCrystals(2, 0), "品質 20%：+2");
        assertEquals(11, MidnightZenith.maxCrystals(1.5, 0));
        assertEquals(13, MidnightZenith.maxCrystals(2, 1), "+1 上限");
    }

    @Test
    void ownSkillDoesNotGeneratePetals() {
        assertFalse(MidnightZenith.otherSkill("midnight_zenith"));
        assertFalse(MidnightZenith.otherSkill("proc_crystal_midnight"));
        assertTrue(MidnightZenith.otherSkill("frost_bolt"));
    }

    @Test
    void crystalLifePerFiveColdResistance() {
        // 減少 3%（物品寫 -3）× 75% 抗性 = 15 份 → 減少 45%
        assertEquals(0.55, IceCrystals.coldLifeFactor(-3, 75), 1e-9);
        // 擲到 -3（＝增加 3%）
        assertEquals(1.45, IceCrystals.coldLifeFactor(3, 75), 1e-9);
        assertEquals(1.0, IceCrystals.coldLifeFactor(-3, 4), 1e-9, "不到 5% 不算");
        assertEquals(1.0, IceCrystals.coldLifeFactor(0, 75), 1e-9);
        assertEquals(0, IceCrystals.coldLifeFactor(-3, 200), 1e-9, "最低 0");
    }

    @Test
    void shippedDataFollowsPoe2() throws java.io.IOException {
        final java.nio.file.Path data = java.nio.file.Path.of("src/main/resources/data");
        final SkillConfig config = new SkillConfig();
        config.load(data);
        assertTrue(config.skill(MidnightZenith.SKILL).grantedOnly(), "傳奇賦予，沒有寶石");
        assertTrue(config.skill(MidnightZenith.SKILL).tags().contains(SkillLimits.TAG));
        double petals = 0;
        double life = 0;
        for (final tw.exilecore.skill.ScaledMod mod : config.levelModifiers(MidnightZenith.SKILL)) {
            if (mod.stat().equals("limit." + MidnightZenith.RARE_PETALS)) {
                petals = mod.value().at(13);
            } else if (mod.stat().equals("limit.crystal_life")) {
                life = mod.value().at(13);
            }
        }
        assertEquals(3, petals, 1e-9, "13 級：暴擊稀有、傳奇 3 片");
        assertEquals(903, life, 1e-9, "13 級：冰晶 903 生命");
        assertTrue(config.skill(MidnightZenith.BURST).hidden());
        final tw.exilecore.item.ItemRepository items = new tw.exilecore.item.ItemRepository();
        items.load(data);
        final tw.exilecore.item.UniqueItem unique = items.unique("eventide_petals");
        assertEquals(java.util.Set.of("delve_boss"), unique.sources(), "只有星脈深坑的首領掉，僥倖石、賭博做不出來");
        assertEquals("jamulet_veridical", unique.base());
        assertTrue(items.uniquesFor("chance", 100).stream().noneMatch(u -> u.id().equals("eventide_petals")));
        assertTrue(items.uniquesFor("gamble", 100).stream().noneMatch(u -> u.id().equals("eventide_petals")));
        assertEquals(55, items.base("jamulet_veridical").requirements().level());
    }
}
