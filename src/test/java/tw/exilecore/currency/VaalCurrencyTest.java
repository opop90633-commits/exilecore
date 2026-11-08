package tw.exilecore.currency;

import org.junit.jupiter.api.Test;
import tw.exilecore.item.ItemData;
import tw.exilecore.item.ItemGenerator;
import tw.exilecore.item.ItemRepository;
import tw.exilecore.item.Rarity;
import tw.exilecore.item.RolledAffix;
import tw.exilecore.item.UniqueItem;
import tw.exilecore.item.UniqueUpgrade;

import java.io.IOException;
import java.nio.file.Path;
import java.util.HashSet;
import java.util.Set;
import java.util.SplittableRandom;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** 2026-11-07 照站長「星脈深坑掉 PoE2 Fate of the Vaal 的通貨」：灌注器、雙蝕石、蝕培石、蝕吸器、擾核石、古蝕灌注器。 */
class VaalCurrencyTest {

    static final Path DATA = Path.of("src/main/resources/data");

    private static ItemRepository items() throws IOException {
        final ItemRepository items = new ItemRepository();
        items.load(DATA);
        return items;
    }

    @Test
    void infusersNeedFullQualityAndGoUpToThirty() throws IOException {
        final ItemRepository items = items();
        final ItemGenerator generator = new ItemGenerator(items);
        final SplittableRandom random = new SplittableRandom(3);
        final ItemData armour = generator.generate(items.base("body_str_25"), Rarity.RARE, 80, random);
        armour.setQuality(19);
        assertFalse(CurrencyOperation.VAAL_INFUSE_ARMOUR.canApply(armour, generator), "品質沒滿不能用");
        armour.setQuality(20);
        assertTrue(CurrencyOperation.VAAL_INFUSE_ARMOUR.canApply(armour, generator));
        assertFalse(CurrencyOperation.VAAL_INFUSE_WEAPON.canApply(armour, generator), "鐵匠的不能用在防具");
        int corrupted = 0;
        for (int i = 0; i < 600; i++) {
            final ItemData copy = armour.copy();
            CurrencyOperation.VAAL_INFUSE_ARMOUR.apply(copy, generator, random);
            assertTrue(copy.quality() > 20 && copy.quality() <= 25, "品質 +1～5");
            assertTrue(copy.vaalInfused(), "記下灌過（只是記錄）");
            corrupted += copy.corrupted() ? 1 : 0;
        }
        assertTrue(corrupted > 60 && corrupted < 150, "約 1/6 腐化：" + corrupted);
        armour.setQualityUpTo(30, 30);
        assertFalse(CurrencyOperation.VAAL_INFUSE_ARMOUR.canApply(armour, generator), "30% 就滿了");
    }

    @Test
    void architectAddsASecondCorruptionEnchant() throws IOException {
        final ItemRepository items = items();
        final ItemGenerator generator = new ItemGenerator(items);
        final SplittableRandom random = new SplittableRandom(5);
        final ItemData data = generator.generate(items.base("body_str_25"), Rarity.RARE, 80, random);
        assertFalse(CurrencyOperation.ARCHITECT.canApply(data, generator), "要先腐化");
        data.setCorrupted(true);
        generator.addCorruptionEnchant(data, random);
        assertTrue(CurrencyOperation.ARCHITECT.canApply(data, generator));
        assertTrue(generator.addSecondCorruptionEnchant(data, random));
        data.setDoubleCorrupted(true);
        final Set<String> groups = new HashSet<>();
        int enchants = 0;
        for (final RolledAffix rolled : data.affixes()) {
            if (generator.isCorruptionEnchant(rolled.id())) {
                enchants++;
                groups.add(rolled.id());
            }
        }
        assertEquals(2, enchants, "兩條腐化詞綴");
        assertEquals(2, groups.size(), "不會重複");
        assertFalse(CurrencyOperation.ARCHITECT.canApply(data, generator), "雙重腐化過的不能再用");
    }

    @Test
    void cultivationSwapsVaalModsAndSurvivesUpgrade() throws IOException {
        final ItemRepository items = items();
        final ItemGenerator generator = new ItemGenerator(items);
        final SplittableRandom random = new SplittableRandom(7);
        final UniqueItem greed = items.unique("gilded_greed_plate");
        assertFalse(items.vaalMods(greed.id()).isEmpty(), "貪金胸鎧有瓦爾詞綴");
        final ItemData data = generator.generateUnique(greed, 80, random);
        data.setCorrupted(true);
        generator.cultivate(data, random);
        long vaal = data.affixes().stream().filter(r -> UniqueItem.isVaalAffix(r.id())).count();
        assertTrue(vaal >= 1 && vaal <= 2, "換了 1～2 條：" + vaal);
        assertEquals(vaal, data.cultivatedOut().size());
        assertEquals(greed.affixIds().size(), data.affixes().size(), "條數不變");
        assertFalse(UniqueUpgrade.outdated(items, data), "讀物品時不會被當成舊版補回去");
        // 2026-11-08 照站長：瓦爾詞綴在說明裡標出來（紅色、✦瓦爾）
        assertEquals(vaal, tw.exilecore.item.ItemStats.affixLines(items, data).stream()
                .filter(line -> tw.exilecore.item.ItemStats.AffixLine.VAAL.equals(line.mark())).count());
        // 沒腐化的瓦爾傳奇、或別的傳奇：變成同種類的隨機已腐化傳奇
        final ItemData other = generator.generateUnique(greed, 80, random);
        final String uuid = other.uuid();
        generator.cultivate(other, random);
        assertTrue(other.corrupted());
        assertNotEquals(greed.id(), other.unique());
        assertEquals(uuid, other.uuid(), "還是同一件");
        assertEquals(generator.itemClass(items.base(greed.base())), generator.itemClass(items.base(other.base())), "同種類");
    }

    @Test
    void cultivationOnlyRollsGlobalUniquesByRarity() throws IOException {
        // 2026-11-07 照站長「Boss 或特殊來源限定的排除，其他的照稀有度加權」
        final ItemRepository items = items();
        final ItemGenerator generator = new ItemGenerator(items);
        final SplittableRandom random = new SplittableRandom(21);
        final UniqueItem start = items.unique("wanderer_satchel");
        final java.util.Map<String, Integer> seen = new java.util.HashMap<>();
        for (int i = 0; i < 3000; i++) {
            final ItemData data = generator.generateUnique(start, 84, random);
            generator.cultivate(data, random);
            final UniqueItem made = items.unique(data.unique());
            assertTrue(made.droppedBy("map_boss") || made.droppedBy("chance") || made.droppedBy("gamble") || made.chase(),
                    "只會變成全域掉落的：" + made.id());
            seen.merge(made.id(), 1, Integer::sum);
        }
        // 常見的（weight 100）比很稀有的（weight 8）多很多
        String common = null;
        String rare = null;
        for (final String id : seen.keySet()) {
            final int weight = items.unique(id).weight();
            if (weight >= 100) {
                common = id;
            } else if (weight <= 8 && !items.unique(id).chase()) {
                rare = id;
            }
        }
        if (common != null && rare != null) {
            assertTrue(seen.get(common) > seen.get(rare) * 3, common + "=" + seen.get(common) + " " + rare + "=" + seen.get(rare));
        }
    }

    @Test
    void siphonDrainsOneAndBoostsOthers() throws IOException {
        final ItemRepository items = items();
        final ItemGenerator generator = new ItemGenerator(items);
        final SplittableRandom random = new SplittableRandom(9);
        ItemData ring = null;
        for (int i = 0; i < 50 && (ring == null || ring.affixes().size() < 4); i++) {
            ring = generator.generate(items.base("jring_08"), Rarity.RARE, 82, random);
        }
        assertNotNull(ring);
        assertFalse(CurrencyOperation.SIPHON.canApply(ring, generator), "要先腐化");
        ring.setCorrupted(true);
        assertTrue(CurrencyOperation.SIPHON.canApply(ring, generator));
        CurrencyOperation.SIPHON.apply(ring, generator, random);
        assertEquals(CurrencyOperation.SIPHON_GOAL, ring.siphonGoal());
        final ItemData before = ring.copy();
        generator.siphonComplete(ring, random);
        assertEquals(0, ring.siphonGoal(), "虹吸完拿掉");
        int lowered = 0;
        for (int i = 0; i < ring.affixes().size(); i++) {
            final RolledAffix was = before.affixes().get(i);
            final RolledAffix now = ring.affixes().get(i);
            if (now.tier() != was.tier()) {
                lowered++;
                continue;
            }
            for (final String key : was.vars().keySet()) {
                assertTrue(now.vars().get(key) >= was.vars().get(key), "其他詞綴只會變好");
            }
        }
        assertTrue(lowered <= 1, "最多一條降階");
    }

    @Test
    void destabiliserAndAncientInfuserOutcomes() throws IOException {
        final CurrencyRepository currencies = new CurrencyRepository();
        currencies.load(DATA);
        final CurrencyType core = currencies.get("rune_core_tacati");
        assertTrue(VaalCrafting.isSoulCore(core));
        assertFalse(VaalCrafting.isSoulCore(currencies.get("rune_thesis_jiquani")), "遠古魂核不能再擾亂");
        assertEquals(4, VaalCrafting.runesWithPrefix(currencies, VaalCrafting.THESIS_PREFIX, null).size());
        final SplittableRandom random = new SplittableRandom(13);
        int destroyed = 0;
        int theses = 0;
        for (int i = 0; i < 2000; i++) {
            final VaalCrafting.Outcome outcome = VaalCrafting.destabilise(currencies, core, random);
            destroyed += outcome.items().isEmpty() ? 1 : 0;
            theses += outcome.items().stream().anyMatch(t -> t.affix().startsWith(VaalCrafting.THESIS_PREFIX)) ? 1 : 0;
        }
        assertTrue(destroyed > 450 && destroyed < 750, "約 30% 碎掉：" + destroyed);
        assertTrue(theses > 120 && theses < 300, "約 10% 變遠古魂核：" + theses);
        final CurrencyType tablet = currencies.get("tablet_breach");
        assertTrue(VaalCrafting.isTablet(tablet));
        for (int i = 0; i < 200; i++) {
            for (final CurrencyType made : VaalCrafting.ancientInfuse(currencies, tablet, random).items()) {
                assertTrue(VaalCrafting.isTablet(made));
            }
        }
        assertTrue(VaalCrafting.socketBound("thesis_quipolatl"));
        assertFalse(VaalCrafting.socketBound("core_tacati"));
    }
}
