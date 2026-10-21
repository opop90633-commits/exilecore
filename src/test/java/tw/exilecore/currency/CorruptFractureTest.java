package tw.exilecore.currency;

import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import tw.exilecore.item.ItemData;
import tw.exilecore.item.ItemGenerator;
import tw.exilecore.item.ItemRepository;
import tw.exilecore.item.Rarity;
import tw.exilecore.item.RolledAffix;
import tw.exilecore.item.Runes;

import java.io.IOException;
import java.nio.file.Path;
import java.util.SplittableRandom;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** 2026-10-21：蝕變石（PoE2 的瓦爾寶珠）、定星石（PoE2 的破裂石）。 */
class CorruptFractureTest {

    static final Path DATA = Path.of("src/main/resources/data");
    static ItemRepository items;
    static ItemGenerator generator;
    static CurrencyRepository currencies;

    @BeforeAll
    static void load() throws IOException {
        items = new ItemRepository();
        items.load(DATA);
        generator = new ItemGenerator(items);
        currencies = new CurrencyRepository();
        currencies.load(DATA);
    }

    private static ItemData rare(final String base, final SplittableRandom random) {
        ItemData data;
        do {
            data = generator.generate(items.base(base), Rarity.RARE, 80, random);
        } while (data.affixes().size() < CurrencyOperation.MIN_FRACTURE_AFFIXES);
        return data;
    }

    @Test
    void bothStonesExistAndNeverDropNormally() {
        final CurrencyType vaal = currencies.get("corrupt_orb");
        final CurrencyType fracture = currencies.get("fracture_orb");
        assertNotNull(vaal);
        assertNotNull(fracture);
        assertEquals(CurrencyOperation.CORRUPT, vaal.operation());
        assertEquals(CurrencyOperation.FRACTURE, fracture.operation());
        assertEquals(0, vaal.dropWeight(), "只在腐化區掉");
        assertEquals(0, fracture.dropWeight(), "只在淨化區掉");
    }

    @Test
    void fracturedAffixSurvivesEveryRemoval() {
        final SplittableRandom random = new SplittableRandom(7);
        for (int round = 0; round < 50; round++) {
            final ItemData data = rare("plate_body", random);
            assertTrue(CurrencyOperation.FRACTURE.canApply(data, generator));
            CurrencyOperation.FRACTURE.apply(data, generator, random);
            final String fractured = data.fractured();
            assertNotNull(fractured);
            assertFalse(CurrencyOperation.FRACTURE.canApply(data, generator), "一件只能有一條破裂");
            final int before = valueOf(data, fractured);
            for (int i = 0; i < 20; i++) {
                if (CurrencyOperation.REMOVE_AND_ADD.canApply(data, generator)) {
                    CurrencyOperation.REMOVE_AND_ADD.apply(data, generator, random);
                }
                if (CurrencyOperation.REROLL_VALUES.canApply(data, generator)) {
                    CurrencyOperation.REROLL_VALUES.apply(data, generator, random);
                }
                if (CurrencyOperation.REMOVE_AFFIX.canApply(data, generator)) {
                    CurrencyOperation.REMOVE_AFFIX.apply(data, generator, random);
                }
            }
            assertTrue(data.affixes().stream().anyMatch(rolled -> rolled.id().equals(fractured)), "破裂的詞綴一直都在");
            assertEquals(before, valueOf(data, fractured), "破裂的詞綴數值也不變");
            assertFalse(CurrencyOperation.SCOUR.canApply(data, generator), "破裂的物品不能洗白");
            assertFalse(CurrencyOperation.REROLL_RARE.canApply(data, generator));
        }
        // 只剩破裂的那條時，湮滅石、亂流石用不了（不會白白吃掉）
        final ItemData lonely = rare("plate_body", random);
        CurrencyOperation.FRACTURE.apply(lonely, generator, random);
        lonely.affixes().removeIf(rolled -> !lonely.isFractured(rolled));
        assertFalse(CurrencyOperation.REMOVE_AFFIX.canApply(lonely, generator));
        assertFalse(CurrencyOperation.REMOVE_AND_ADD.canApply(lonely, generator));
    }

    private static int valueOf(final ItemData data, final String affixId) {
        for (final RolledAffix rolled : data.affixes()) {
            if (rolled.id().equals(affixId)) {
                return rolled.vars().values().stream().mapToInt(Integer::intValue).sum();
            }
        }
        return Integer.MIN_VALUE;
    }

    @Test
    void corruptionHasTheFourPoe2Outcomes() {
        final SplittableRandom random = new SplittableRandom(11);
        int enchanted = 0;
        int socketed = 0;
        for (int i = 0; i < 400; i++) {
            final ItemData data = rare("plate_body", random);
            final int slotsBefore = Runes.slots(items, items.base("plate_body"), data);
            assertTrue(CurrencyOperation.CORRUPT.canApply(data, generator));
            CurrencyOperation.CORRUPT.apply(data, generator, random);
            assertTrue(data.corrupted());
            assertFalse(CurrencyOperation.CORRUPT.canApply(data, generator), "腐化過不能再腐化");
            assertFalse(CurrencyOperation.REMOVE_AND_ADD.canApply(data, generator) && !data.corrupted());
            assertTrue(CurrencyOperation.RUNE.allowedOnCorrupted(), "腐化過還能嵌符文");
            assertFalse(CurrencyOperation.REMOVE_AND_ADD.allowedOnCorrupted());
            final long enchants = data.affixes().stream().filter(rolled -> generator.isCorruptionEnchant(rolled.id())).count();
            assertTrue(enchants <= 1, "一件只會有一條腐化詞綴");
            enchanted += enchants > 0 ? 1 : 0;
            if (Runes.slots(items, items.base("plate_body"), data) > slotsBefore) {
                assertEquals(slotsBefore + 1, Runes.slots(items, items.base("plate_body"), data), "多一個孔，可以超過上限");
                socketed++;
            }
        }
        assertTrue(enchanted > 60 && enchanted < 140, "大約四分之一加腐化詞綴：" + enchanted);
        assertTrue(socketed > 60 && socketed < 140, "大約四分之一多一個孔：" + socketed);
        // 戒指沒有符文孔：多一個孔的那 25% 改成加腐化詞綴
        int ringEnchanted = 0;
        for (int i = 0; i < 200; i++) {
            final ItemData ring = rare("jring_16", random);
            CurrencyOperation.CORRUPT.apply(ring, generator, random);
            ringEnchanted += ring.affixes().stream().anyMatch(rolled -> generator.isCorruptionEnchant(rolled.id())) ? 1 : 0;
            assertEquals(0, ring.corruptSockets());
        }
        assertTrue(ringEnchanted > 70 && ringEnchanted < 130, "戒指大約一半加腐化詞綴：" + ringEnchanted);
        // 珠寶照 PoE2：多一條（可以超過上限）或少一條，不加腐化詞綴
        int over = 0;
        int fewer = 0;
        for (int i = 0; i < 200; i++) {
            final ItemData jewel = generator.generate(items.base("ruby_jewel"), Rarity.RARE, 70, random);
            final int before = jewel.affixes().size();
            CurrencyOperation.CORRUPT.apply(jewel, generator, random);
            assertTrue(jewel.affixes().stream().noneMatch(rolled -> generator.isCorruptionEnchant(rolled.id())), "珠寶沒有腐化詞綴");
            over += jewel.affixes().size() > before ? 1 : 0;
            fewer += jewel.affixes().size() < before ? 1 : 0;
        }
        assertTrue(over > 25 && fewer > 25, "珠寶多一條 " + over + "、少一條 " + fewer);
        // 每一種在用的裝備都有腐化詞綴可以抽（珠寶除外）
        for (final tw.exilecore.item.ItemBase base : items.bases()) {
            if (base.dropWeight() <= 0 || base.isMap() || base.isJewel() || base.isFlaskLike()) {
                continue;
            }
            assertTrue(items.affixes().stream().anyMatch(affix -> generator.isCorruptionEnchant(affix.id()) && ItemGenerator.corruptionFits(affix, base)),
                    base.id() + " 沒有腐化詞綴可以抽");
        }
    }
}
