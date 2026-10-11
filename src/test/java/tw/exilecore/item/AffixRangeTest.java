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
    void spellAddedDamageIsRetiredButOldItemsKeepIt() {
        // 2026-10 PoE2 改版：法術附加（PoE2 沒有）全部停用：不再擲到任何基底上，定義留著讓舊物品照常生效、顯示
        for (final String element : java.util.List.of("fire", "cold", "lightning", "chaos")) {
            final Affix oneHand = repository.affix("spell_added_" + element);
            final Affix twoHand = repository.affix("spell_added_" + element + "_2h");
            assertTrue(oneHand.spawnTags().contains(ItemRepository.RETIRED_TAG), oneHand.id());
            assertTrue(twoHand.spawnTags().contains(ItemRepository.RETIRED_TAG), twoHand.id());
            for (final ItemBase base : repository.bases()) {
                assertFalse(oneHand.canSpawnOn(base), base.id() + " 不再出 " + oneHand.id());
                assertFalse(twoHand.canSpawnOn(base), base.id() + " 不再出 " + twoHand.id());
            }
            assertEquals(oneHand.group(), twoHand.group(), "同一組：一件裝備只會有一條法術附加");
            // 舊的數值照舊：雙手（長杖 8 成）比單手（一半）多 6 成
            assertEquals(oneHand.tiers().getFirst().vars().get("max")[1] * 1.6, twoHand.tiers().getFirst().vars().get("max")[1], 2.0);
        }
        // 攻擊附加照出在戒指；施法武器改出 PoE2 的「增加法術傷害」（法杖、長杖各自的數值）
        assertTrue(repository.affix("added_fire").canSpawnOn(repository.base("jring_16")), "戒指照出攻擊附加");
        assertTrue(repository.affix("spell_inc_weapon_wand").canSpawnOn(repository.base("wand_14")), "法杖");
        assertTrue(repository.affix("spell_inc_weapon_staff").canSpawnOn(repository.base("staff_17")), "長杖");
        assertFalse(repository.affix("spell_inc_weapon_wand").canSpawnOn(repository.base("staff_17")), "長杖用雙手的那一條");
        assertEquals(repository.affix("spell_inc_weapon_wand").group(), repository.affix("spell_inc_weapon_staff").group());
        assertTrue(repository.affix("spell_added_fire").text().startsWith("法術附加") && repository.affix("added_fire").text().startsWith("攻擊附加"),
                "看得出來是攻擊還是法術");
    }

    @Test
    void casterWeaponsDoNotRollAttackOnlyAffixes() {
        // 2026-10 PoE2 改版：舊基底退役（法杖 → wand_xx、長杖 → staff_xx…）；武器的物理、附加、攻速、命中是本地的另一批詞綴
        for (final String caster : java.util.List.of("wand_14", "staff_17")) {
            final ItemBase base = repository.base(caster);
            for (final String attackOnly : java.util.List.of("added_phys", "added_fire", "added_cold", "added_lightning", "added_chaos",
                    "added_phys_local", "added_fire_local", "added_fire_local_crossbow", "phys_inc", "local_phys_accuracy",
                    "attack_speed", "local_attack_speed", "local_attack_speed_bow", "accuracy", "accuracy_weapon")) {
                assertFalse(repository.affix(attackOnly).canSpawnOn(base), caster + " 不該出 " + attackOnly);
            }
            final String kind = base.hasTag("wand") ? "wand" : "staff";
            assertTrue(repository.affix("spell_inc_weapon_" + kind).canSpawnOn(base), caster + "：法術的照出");
            assertTrue(repository.affix("cast_speed_" + kind).canSpawnOn(base));
            assertTrue(repository.affix("spell_crit_chance_" + kind).canSpawnOn(base), "法術暴擊");
        }
        // PoE2 的權杖是召喚與精魂的武器：不出攻擊的詞綴
        final ItemBase sceptre = repository.base("sceptre_16");
        assertFalse(repository.affix("added_fire_local").canSpawnOn(sceptre), "權杖不出攻擊附加");
        assertTrue(repository.affix("minion_inc").canSpawnOn(sceptre), "權杖出召喚物的詞綴");
        final ItemBase mace = repository.base("mace1_26");
        assertTrue(repository.affix("added_fire_local").canSpawnOn(mace));
        assertTrue(repository.affix("local_attack_speed").canSpawnOn(mace));
        assertTrue(repository.affix("added_fire").canSpawnOn(repository.base("jring_16")), "戒指照出攻擊附加");
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
