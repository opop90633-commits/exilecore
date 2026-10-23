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
        // 2026-10-23 照 PoE2：戒指沒有符文孔，「特殊」那 25% 沒有變化（不再改成加腐化詞綴）
        int ringEnchanted = 0;
        for (int i = 0; i < 400; i++) {
            final ItemData ring = rare("jring_16", random);
            CurrencyOperation.CORRUPT.apply(ring, generator, random);
            ringEnchanted += ring.affixes().stream().anyMatch(rolled -> generator.isCorruptionEnchant(rolled.id())) ? 1 : 0;
            assertEquals(0, ring.corruptSockets());
            assertTrue(ring.quality() <= 20);
        }
        assertTrue(ringEnchanted > 60 && ringEnchanted < 140, "戒指大約四分之一加腐化詞綴：" + ringEnchanted);
        // 法杖：特殊那 25% 是品質增加，最多 23%
        int qualityUp = 0;
        for (int i = 0; i < 400; i++) {
            final ItemData wand = rare("wand_01", random);
            wand.setQuality(20);
            CurrencyOperation.CORRUPT.apply(wand, generator, random);
            assertEquals(0, wand.corruptSockets(), "法杖不開孔");
            assertTrue(wand.quality() <= ItemData.CORRUPTED_QUALITY_MAX);
            qualityUp += wand.quality() > 20 ? 1 : 0;
        }
        assertTrue(qualityUp > 60 && qualityUp < 140, "法杖大約四分之一品質超過 20：" + qualityUp);
        // 稀有珠寶：沒變化、換 1～3 條、加腐化詞綴、多或少一條（可以超過上限）
        int over = 0;
        int fewer = 0;
        int jewelEnchanted = 0;
        for (int i = 0; i < 400; i++) {
            final ItemData jewel = generator.generate(items.base("ruby_jewel"), Rarity.RARE, 70, random);
            final int before = jewel.affixes().size();
            CurrencyOperation.CORRUPT.apply(jewel, generator, random);
            final long enchants = jewel.affixes().stream().filter(rolled -> generator.isCorruptionEnchant(rolled.id())).count();
            jewelEnchanted += enchants > 0 ? 1 : 0;
            final int after = jewel.affixes().size() - (int) enchants;
            over += after > before ? 1 : 0;
            fewer += after < before ? 1 : 0;
        }
        assertTrue(over > 20 && fewer > 20, "珠寶多一條 " + over + "、少一條 " + fewer);
        assertTrue(jewelEnchanted > 60 && jewelEnchanted < 140, "珠寶大約四分之一加腐化詞綴：" + jewelEnchanted);
        // 每一種在用的裝備、珠寶都有腐化詞綴可以抽
        for (final tw.exilecore.item.ItemBase base : items.bases()) {
            if (base.dropWeight() <= 0 || base.isMap() || base.isFlaskLike()) {
                continue;
            }
            assertTrue(items.affixes().stream().anyMatch(affix -> generator.isCorruptionEnchant(affix.id()) && ItemGenerator.corruptionFits(affix, base)),
                    base.id() + " 沒有腐化詞綴可以抽");
        }
    }

    /** 2026-10-23：傳奇珠寶照 PoE2：一半沒變化、重骰再乘 0.78～1.22、加腐化詞綴；傳奇本身的效果不會少。 */
    @Test
    void uniqueJewelsNeverLoseTheirMods() {
        final SplittableRandom random = new SplittableRandom(23);
        final var unique = items.unique("lockbox_coin");
        assertNotNull(unique);
        int unchanged = 0;
        int enchanted = 0;
        int outside = 0;
        for (int i = 0; i < 400; i++) {
            final ItemData jewel = generator.generateUnique(unique, 85, random);
            final java.util.List<String> before = jewel.affixes().stream().map(RolledAffix::id).toList();
            final java.util.List<java.util.Map<String, Integer>> values = jewel.affixes().stream().map(rolled -> java.util.Map.copyOf(rolled.vars())).toList();
            CurrencyOperation.CORRUPT.apply(jewel, generator, random);
            final java.util.List<String> own = jewel.affixes().stream().map(RolledAffix::id)
                    .filter(id -> !generator.isCorruptionEnchant(id)).toList();
            assertEquals(before, own, "傳奇珠寶的效果一條都不會少、也不會多一般詞綴");
            final boolean hasEnchant = jewel.affixes().size() > own.size();
            enchanted += hasEnchant ? 1 : 0;
            final java.util.List<java.util.Map<String, Integer>> now = jewel.affixes().stream()
                    .filter(rolled -> !generator.isCorruptionEnchant(rolled.id())).map(rolled -> java.util.Map.copyOf(rolled.vars())).toList();
            unchanged += !hasEnchant && now.equals(values) ? 1 : 0;
            // 「+x 最大生命」原本 25～35，乘 0.78～1.22 可以超出
            final int life = jewel.affixes().stream().filter(rolled -> rolled.id().endsWith(".3")).findFirst().orElseThrow().vars().values().iterator().next();
            assertTrue(life >= 19 && life <= 43, "最多 0.78～1.22 倍：" + life);
            outside += life < 25 || life > 35 ? 1 : 0;
        }
        assertTrue(unchanged > 150, "大約一半沒變化：" + unchanged);
        assertTrue(enchanted > 60 && enchanted < 140, "大約四分之一加腐化詞綴：" + enchanted);
        assertTrue(outside > 5, "重骰時可以超出原本範圍：" + outside);
    }

    /** 2026-10-23：稀有裝備「換詞綴」是 1～3 條換成新的，破裂的那條不動。 */
    @Test
    void replaceKeepsFracturedAndCount() {
        final SplittableRandom random = new SplittableRandom(31);
        for (int i = 0; i < 100; i++) {
            final ItemData data = rare("plate_body", random);
            CurrencyOperation.FRACTURE.apply(data, generator, random);
            final String fractured = data.fractured();
            final int size = data.affixes().size();
            final int replaced = generator.replaceRandomAffixes(data, random);
            assertTrue(replaced >= 0 && replaced <= 3);
            assertEquals(size, data.affixes().size(), "換掉幾條就補幾條");
            assertTrue(data.affixes().stream().anyMatch(rolled -> rolled.id().equals(fractured)));
        }
    }

    /** 2026-10-23：地圖照 PoE2：階級 ±1（T1～T16）、詞綴全部變前綴或後綴、稀有多 1～2 條；藥劑、護符加品質（最多 23%）。 */
    @Test
    void mapsFlasksAndCharmsCanBeCorrupted() {
        final SplittableRandom random = new SplittableRandom(41);
        final tw.exilecore.item.ItemBase mapBase = items.bases().stream().filter(tw.exilecore.item.ItemBase::isMap)
                .filter(base -> base.dropWeight() > 0).findFirst().orElseThrow();
        int tierChanged = 0;
        int allOneType = 0;
        int grew = 0;
        for (int i = 0; i < 400; i++) {
            final ItemData map = generator.generate(mapBase, Rarity.RARE, 80, random);
            map.setMapTier(8);
            final int before = map.affixes().size();
            assertTrue(CurrencyOperation.CORRUPT.canApply(map, generator), "地圖可以腐化");
            CurrencyOperation.CORRUPT.apply(map, generator, random);
            assertTrue(map.corrupted());
            final int tier = tw.exilecore.item.ItemStats.mapTier(mapBase, map);
            assertTrue(tier == 7 || tier == 8 || tier == 9, "階級只會 ±1：" + tier);
            tierChanged += tier != 8 ? 1 : 0;
            grew += map.affixes().size() > before ? 1 : 0;
            final long prefixes = map.affixes().stream().filter(rolled -> items.affix(rolled.id()).type() == tw.exilecore.item.AffixType.PREFIX).count();
            allOneType += map.affixes().size() >= 2 && (prefixes == 0 || prefixes == map.affixes().size()) ? 1 : 0;
            assertTrue(prefixes <= Math.max(5, before) && map.affixes().size() - prefixes <= Math.max(5, before));
        }
        assertTrue(tierChanged > 60 && tierChanged < 140, "大約四分之一改階級：" + tierChanged);
        assertTrue(grew > 40, "稀有地圖多詞綴：" + grew);
        assertTrue(allOneType > 60, "全部變前綴或後綴：" + allOneType);
        // 階級不會超出 T1～T16
        final ItemData top = generator.generate(mapBase, Rarity.RARE, 80, random);
        for (int i = 0; i < 50; i++) {
            final ItemData copy = generator.generate(mapBase, Rarity.MAGIC, 80, random);
            copy.setMapTier(16);
            CurrencyOperation.CORRUPT.apply(copy, generator, random);
            assertTrue(tw.exilecore.item.ItemStats.mapTier(mapBase, copy) <= CurrencyOperation.MAP_TIER_MAX);
        }
        assertNotNull(top);
        // 藥劑、護符
        for (final tw.exilecore.item.ItemBase base : items.bases()) {
            if (!base.isFlaskLike()) {
                continue;
            }
            int quality = 0;
            for (int i = 0; i < 200; i++) {
                final ItemData flask = generator.generate(base, Rarity.MAGIC, 80, random);
                final CurrencyType vaal = currencies.get("corrupt_orb");
                assertTrue(vaal.canApply(flask, generator), base.id() + " 可以腐化");
                CurrencyOperation.CORRUPT.apply(flask, generator, random);
                assertTrue(flask.quality() <= ItemData.CORRUPTED_QUALITY_MAX);
                quality += flask.quality() > 0 ? 1 : 0;
            }
            assertTrue(quality > 60 && quality < 140, base.id() + " 大約一半加品質：" + quality);
            final ItemData good = generator.generate(base, Rarity.MAGIC, 80, random);
            good.setCorruptedQuality(20);
            final tw.exilecore.flask.FlaskMods mods = tw.exilecore.flask.FlaskMods.of(items, good);
            if (base.isCharm()) {
                assertTrue(mods.duration() > tw.exilecore.flask.FlaskMods.of(items, withoutQuality(good)).duration(), "護符品質加持續時間");
            } else {
                assertTrue(mods.recoveryAmount(100, false) > tw.exilecore.flask.FlaskMods.of(items, withoutQuality(good)).recoveryAmount(100, false),
                        "藥劑品質加回復量");
            }
        }
    }

    private static ItemData withoutQuality(final ItemData data) {
        final ItemData copy = data.copy();
        copy.setQuality(0);
        return copy;
    }
}
