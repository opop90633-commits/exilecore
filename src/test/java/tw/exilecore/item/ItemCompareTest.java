package tw.exilecore.item;

import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** 裝備比較：摘要與差異那幾行。 */
class ItemCompareTest {

    static ItemRepository items;

    @BeforeAll
    static void load() throws IOException {
        items = new ItemRepository();
        items.load(Path.of("src/main/resources/data"));
    }

    @Test
    void summaryReadsBaseAndAffixes() {
        final ItemData plain = new ItemData("plate_body", Rarity.NORMAL, 1);
        final ItemCompare.Summary summary = ItemCompare.summarize(items, plain);
        assertEquals(40, summary.armour(), 1e-9, "板甲身體基底 40 護甲");
        assertEquals(0, summary.dps(), 1e-9);

        final ItemData rare = new ItemData("plate_body", Rarity.RARE, 60);
        rare.setIdentified(true);
        rare.affixes().add(new RolledAffix("life_flat", 3, Map.of("value", 60)));
        rare.affixes().add(new RolledAffix("resist_fire", 2, Map.of("value", 40)));
        rare.affixes().add(new RolledAffix("armour_inc", 2, Map.of("value", 70)));
        final ItemCompare.Summary summaryRare = ItemCompare.summarize(items, rare);
        assertEquals(60, summaryRare.life(), 1e-9);
        assertEquals(40, summaryRare.resistFire(), 1e-9);
        // 基底 40 × (1 + 0.035 × 59) ≈ 123（四捨五入）× 1.7
        assertEquals(Math.round(40 * (1 + 0.035 * 59)) * 1.7, summaryRare.armour(), 1e-6);
    }

    @Test
    void weaponSummaryUsesDps() {
        final ItemData sword = new ItemGenerator(items).generate(items.base("short_sword"), Rarity.NORMAL, 1, new java.util.SplittableRandom(1));
        final ItemCompare.Summary summary = ItemCompare.summarize(items, sword);
        assertTrue(summary.aps() > 0);
        assertTrue(summary.dps() > 0);
        assertEquals(summary.dps(), summary.physicalDps(), 1e-9, "白劍只有物理");
    }

    @Test
    void linesShowOnlyDifferences() {
        final ItemData worn = new ItemData("plate_body", Rarity.NORMAL, 1);
        final ItemData candidate = new ItemData("plate_body", Rarity.RARE, 1);
        candidate.setIdentified(true);
        candidate.affixes().add(new RolledAffix("life_flat", 3, Map.of("value", 60)));
        candidate.affixes().add(new RolledAffix("resist_cold", 2, Map.of("value", 30)));
        final List<String> lines = ItemCompare.lines(items, candidate, worn, "舊板甲");
        assertEquals(2, lines.size());
        assertTrue(lines.get(0).contains("比身上的"), lines.get(0));
        assertTrue(lines.get(0).contains("舊板甲"));
        assertTrue(lines.get(1).contains("<green>生命 +60"), lines.get(1));
        assertTrue(lines.get(1).contains("<green>冰抗 +30%"), lines.get(1));
        assertTrue(!lines.get(1).contains("護甲"), "護甲一樣就不寫");

        final List<String> same = ItemCompare.lines(items, worn, worn, "舊板甲");
        assertEquals(1, same.size());
        assertTrue(same.get(0).contains("差不多"));

        final List<String> worse = ItemCompare.lines(items, worn, candidate, "好板甲");
        assertTrue(worse.get(1).contains("<red>生命 −60"), worse.get(1));
    }

    @Test
    void attackAndSpellCritAreComparedSeparately() {
        // 同一個種子：固定詞綴擲出一樣的，差別只在後綴
        final ItemData attack = new ItemGenerator(items).generate(items.base("jade_amulet"), Rarity.NORMAL, 84, new java.util.SplittableRandom(7));
        attack.affixes().add(new RolledAffix("amulet_attack_crit_chance", -1, Map.of("value", 50)));
        attack.affixes().add(new RolledAffix("crit_multi", 1, Map.of("value", 30)));
        final ItemData spell = new ItemGenerator(items).generate(items.base("jade_amulet"), Rarity.NORMAL, 84, new java.util.SplittableRandom(7));
        spell.affixes().add(new RolledAffix("amulet_spell_crit_chance", -1, Map.of("value", 50)));
        spell.affixes().add(new RolledAffix("amulet_spell_crit_multi", -1, Map.of("value", 44)));
        final ItemCompare.Summary summary = ItemCompare.summarize(items, attack);
        assertEquals(0.5, summary.attackCritIncreased(), 1e-9);
        assertEquals(0, summary.spellCritIncreased(), 1e-9);
        assertEquals(0, summary.critIncreased(), 1e-9);
        assertEquals(30, summary.critMulti(), 1e-9);
        // 數字一樣的暴擊率，一個攻擊一個法術：不能寫成「差不多」
        final String text = String.join(" ", ItemCompare.lines(items, spell, attack, "攻擊項鍊"));
        assertTrue(text.contains("<green>法術暴擊率 +50%"), text);
        assertTrue(text.contains("<red>攻擊暴擊率 −50%"), text);
        assertTrue(text.contains("<green>法術暴擊傷害 +44%"), text);
        assertTrue(text.contains("<red>暴擊傷害 −30%"), text);
    }
}
