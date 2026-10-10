package tw.exilecore.item;

import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** 詞綴的數值後面接著這一階的範圍（PoE 按住 Alt 看到的樣子）。 */
class AffixRangeTest {

    static final Path DATA = Path.of("src/main/resources/data");
    static ItemRepository repository;

    @BeforeAll
    static void load() throws IOException {
        repository = new ItemRepository();
        repository.load(DATA);
    }

    private static String range(final int lo, final int hi) {
        return "<" + Affix.RANGE_COLOR + ">(" + lo + "–" + hi + ")</" + Affix.RANGE_COLOR + ">";
    }

    @Test
    void valueThenRange() {
        assertEquals("+15" + range(10, 19) + " 最大生命",
                Affix.substituteWithRanges("+{value} 最大生命", Map.of("value", 15), Map.of("value", new int[]{10, 19})));
        assertEquals("附加 5" + range(3, 6) + " 到 12" + range(10, 14) + " 物理傷害",
                Affix.substituteWithRanges("附加 {min} 到 {max} 物理傷害", Map.of("min", 5, "max", 12),
                        Map.of("min", new int[]{3, 6}, "max", new int[]{10, 14})));
    }

    @Test
    void fixedValuesAndOddCases() {
        assertEquals("暴擊傷害 +30%", Affix.substituteWithRanges("暴擊傷害 +{value}%", Map.of("value", 30), Map.of("value", new int[]{30, 30})),
                "上下限一樣：不接範圍");
        assertEquals("玩家 -23" + range(-25, -21) + "% 抗性",
                Affix.substituteWithRanges("玩家 {value}% 抗性", Map.of("value", -23), Map.of("value", new int[]{-21, -25})), "反過來寫也照大小排");
        assertEquals("+7 力量", Affix.substituteWithRanges("+{value} 力量", Map.of("value", 7), Map.of()), "階級被刪掉了：只有數值");
        assertEquals("{x} 跟 \\<b>", Affix.substituteWithRanges("{x} 跟 <b>", Map.of(), Map.of()), "沒有的變數照原樣、標籤字元跳脫");
    }

    @Test
    void spellAddedDamageRollsOnCasterWeaponsAndRings() {
        final Affix oneHand = repository.affix("spell_added_fire");
        final Affix twoHand = repository.affix("spell_added_fire_2h");
        assertTrue(oneHand.canSpawnOn(repository.base("wand")), "法杖");
        assertTrue(oneHand.canSpawnOn(repository.base("sceptre")), "權杖");
        assertFalse(oneHand.canSpawnOn(repository.base("staff")), "長杖用雙手的那一條");
        assertTrue(twoHand.canSpawnOn(repository.base("staff")));
        assertFalse(oneHand.canSpawnOn(repository.base("short_sword")), "近戰武器沒有");
        assertFalse(twoHand.canSpawnOn(repository.base("bow")));
        // 戒指：跟攻擊附加一樣兩種都出（數值跟單手的一樣）；項鍊、腰帶沒有
        for (final String element : java.util.List.of("fire", "cold", "lightning")) {
            final Affix spell = repository.affix("spell_added_" + element);
            assertTrue(spell.canSpawnOn(repository.base("iron_ring")), "戒指出法術附加" + element);
            assertTrue(repository.affix("added_" + element).canSpawnOn(repository.base("iron_ring")), "戒指照出攻擊附加" + element);
            for (final ItemBase base : repository.bases()) {
                if (base.tags().contains("amulet") || base.tags().contains("belt")) {
                    assertFalse(spell.canSpawnOn(base), base.id() + " 不出法術附加");
                }
            }
        }
        assertEquals(oneHand.group(), twoHand.group(), "同一組：一件裝備只會有一條法術附加火焰");
        assertTrue(repository.affix("spell_inc").canSpawnOn(repository.base("sceptre")), "權杖也出「增加法術傷害」");
        // 單手是攻擊附加的一半、雙手 8 成（最高階）
        final AffixTier attackTop = repository.affix("added_fire").tiers().getFirst();
        final AffixTier spellTop = oneHand.tiers().getFirst();
        final AffixTier staffTop = twoHand.tiers().getFirst();
        assertEquals(attackTop.vars().get("max")[1] * 0.5, spellTop.vars().get("max")[1], 1.0);
        assertEquals(attackTop.vars().get("max")[1] * 0.8, staffTop.vars().get("max")[1], 1.0);
        assertTrue(oneHand.text().startsWith("法術附加") && repository.affix("added_fire").text().startsWith("攻擊附加"),
                "看得出來是攻擊還是法術");
    }

    @Test
    void casterWeaponsDoNotRollAttackOnlyAffixes() {
        for (final String caster : java.util.List.of("wand", "staff")) {
            final ItemBase base = repository.base(caster);
            for (final String attackOnly : java.util.List.of("added_phys", "added_fire", "added_cold", "added_lightning", "added_chaos",
                    "phys_inc", "attack_speed", "accuracy")) {
                assertFalse(repository.affix(attackOnly).canSpawnOn(base), caster + " 不該出 " + attackOnly);
            }
            assertTrue(repository.affix("spell_inc").canSpawnOn(base), caster + "：法術的照出");
            assertTrue(repository.affix("cast_speed").canSpawnOn(base));
            assertTrue(repository.affix("crit_chance").canSpawnOn(base), "暴擊法術也吃");
        }
        final ItemBase sceptre = repository.base("sceptre");
        assertTrue(repository.affix("added_fire").canSpawnOn(sceptre), "權杖是單手錘：攻擊附加照出");
        assertTrue(repository.affix("spell_added_fire").canSpawnOn(sceptre), "也出法術附加");
        final ItemBase sword = repository.base("short_sword");
        assertTrue(repository.affix("added_fire").canSpawnOn(sword));
        assertTrue(repository.affix("attack_speed").canSpawnOn(sword));
        assertTrue(repository.affix("added_fire").canSpawnOn(repository.base("iron_ring")), "戒指照出攻擊附加");
    }

    @Test
    void everyShippedAffixRendersWithRanges() {
        int checked = 0;
        for (final Affix affix : repository.affixes()) {
            for (final AffixTier tier : affix.tiers()) {
                final Map<String, Integer> middle = new LinkedHashMap<>();
                for (final Map.Entry<String, int[]> entry : tier.vars().entrySet()) {
                    middle.put(entry.getKey(), (entry.getValue()[0] + entry.getValue()[1]) / 2);
                }
                final String ranged = Affix.substituteWithRanges(affix.text(), middle, affix.ranges(tier.tier()));
                assertFalse(ranged.contains("{"), affix.id() + " T" + tier.tier() + " 有沒換掉的變數：" + ranged);
                // 範圍可能是小數（{value/10} 的詞綴：「0.5(0.5–0.6)%」）
                final String plain = ranged.replaceAll("<[^>]+>", "").replaceAll("\\(-?\\d+(?:\\.\\d+)?–-?\\d+(?:\\.\\d+)?\\)", "");
                assertEquals(affix.render(middle).replace("<", "\\<"), plain, affix.id() + "：拿掉範圍就是原本的文字");
                checked++;
            }
        }
        assertTrue(checked > 300, "詞綴的階級數：" + checked);
    }
}
