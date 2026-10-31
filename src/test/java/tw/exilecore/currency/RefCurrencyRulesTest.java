package tw.exilecore.currency;

import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import tw.exilecore.item.Affix;
import tw.exilecore.item.AffixTier;
import tw.exilecore.item.AffixType;
import tw.exilecore.item.Catalyst;
import tw.exilecore.item.ItemBase;
import tw.exilecore.item.ItemData;
import tw.exilecore.item.ItemGenerator;
import tw.exilecore.item.ItemRepository;
import tw.exilecore.item.Rarity;
import tw.exilecore.item.RolledAffix;
import tw.exilecore.item.Runes;

import java.io.IOException;
import java.nio.file.Path;
import java.util.List;
import java.util.SplittableRandom;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** 2026-10-26：通貨製作照 PoE2 0.5（高階／完美通貨、星魄分階、催化劑 13 種、星籤分開、蝕刻器、工匠石、預見鎖、寶石品質）。 */
class RefCurrencyRulesTest {

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

    private static ItemBase base(final java.util.function.Predicate<ItemBase> filter) {
        return items.bases().stream().filter(b -> !items.retiredBase(b.id())).filter(filter).findFirst().orElseThrow();
    }

    private static int level(final ItemData data, final RolledAffix rolled) {
        final Affix affix = items.affix(rolled.id());
        for (final AffixTier tier : affix.tiers()) {
            if (tier.tier() == rolled.tier()) {
                return tier.ilvl();
            }
        }
        return 0;
    }

    @Test
    void greaterAndPerfectOrbsOnlyRollHighLevelMods() {
        final CurrencyType perfect = currencies.get("perfect_glimmer_orb");
        assertEquals(70, perfect.minModLevel());
        assertEquals(44, currencies.get("greater_glimmer_orb").minModLevel());
        assertEquals(35, currencies.get("greater_zenith_orb").minModLevel());
        assertEquals(50, currencies.get("perfect_turmoil_orb").minModLevel());
        final ItemBase ring = base(b -> b.slot() == tw.exilecore.item.ItemSlot.RING);
        final SplittableRandom random = new SplittableRandom(3);
        for (int i = 0; i < 40; i++) {
            final ItemData data = generator.generate(ring, Rarity.NORMAL, 82, random);
            assertTrue(perfect.canApply(data, generator));
            ItemGenerator.withMinModLevel(perfect.minModLevel(), () -> perfect.operation().apply(data, generator, random));
            assertEquals(Rarity.MAGIC, data.rarity());
            for (final RolledAffix rolled : data.affixes()) {
                assertTrue(level(data, rolled) >= 70, "完美微光石只給需求等級 70 以上的：" + rolled.id());
            }
        }
        // 物品等級不到：不能用
        final ItemData low = generator.generate(ring, Rarity.NORMAL, 50, random);
        assertFalse(perfect.canApply(low, generator));
        assertNotNull(Crafting.problem(perfect, low, generator));
        assertEquals(0, ItemGenerator.minModLevel(), "做完要還原");
    }

    @Test
    void alchemyWorksOnMagicAndMirrorOnUniques() {
        final CurrencyType alchemy = currencies.get("spark_orb");
        final ItemBase ring = base(b -> b.slot() == tw.exilecore.item.ItemSlot.RING);
        final ItemData magic = generator.generate(ring, Rarity.MAGIC, 60, new SplittableRandom(1));
        assertTrue(alchemy.canApply(magic, generator));
        alchemy.operation().apply(magic, generator, new SplittableRandom(2));
        assertEquals(Rarity.RARE, magic.rarity());
        assertEquals(4, magic.affixes().size());
        assertTrue(currencies.get("star_mirror").rarities().contains(Rarity.UNIQUE));
    }

    @Test
    void essencesAreTieredAndOnlyMagicToRare() {
        final CurrencyType lesser = currencies.get("lesser_essence_flame");
        final CurrencyType normal = currencies.get("essence_flame");
        final CurrencyType greater = currencies.get("greater_essence_flame");
        final ItemBase ring = base(b -> b.slot() == tw.exilecore.item.ItemSlot.RING);
        final ItemData plain = generator.generate(ring, Rarity.NORMAL, 82, new SplittableRandom(1));
        assertFalse(normal.canApply(plain, generator), "PoE2 的精髓不能用在普通物品上");
        final int[] expected = {11, 21, 31}; // poe2db：+11–15、+21–25、+31–35% 火焰抗性（照我們的階級最低值）
        final CurrencyType[] tiers = {lesser, normal, greater};
        for (int i = 0; i < tiers.length; i++) {
            final ItemData data = generator.generate(ring, Rarity.NORMAL, 10, new SplittableRandom(5)); // 物品等級低也照固定階級
            data.setRarity(Rarity.MAGIC);
            data.affixes().clear();
            assertNull(Crafting.problem(tiers[i], data, generator), tiers[i].id());
            Crafting.applyEssence(tiers[i], data, generator, new SplittableRandom(9));
            assertEquals(Rarity.RARE, data.rarity());
            final RolledAffix fire = data.affixes().getLast();
            assertEquals("resist_fire", fire.id());
            assertTrue(fire.vars().get("value") >= expected[i] && fire.vars().get("value") <= expected[i] + 4, tiers[i].id() + " " + fire.vars());
        }
        // 完美：稀有物品移除一條、加專屬的那條
        final CurrencyType perfect = currencies.get("perfect_essence_body");
        final ItemBase body = base(b -> b.tags().contains("body") && b.tags().contains("armour"));
        final ItemData rare = generator.generate(body, Rarity.RARE, 80, new SplittableRandom(7));
        final int before = rare.affixes().size();
        assertNull(Crafting.problem(perfect, rare, generator));
        Crafting.applyEssence(perfect, rare, generator, new SplittableRandom(8));
        assertTrue(rare.affixes().stream().anyMatch(r -> r.id().equals("ess_life_pct")));
        assertEquals(before, rare.affixes().size());
        // 星魄專屬的詞綴不會自然擲出
        for (int i = 0; i < 50; i++) {
            assertFalse(generator.generate(body, Rarity.RARE, 85, new SplittableRandom(i)).affixes().stream()
                    .anyMatch(r -> r.id().startsWith("ess_")));
        }
    }

    @Test
    void catalystsAreTheRefThirteenAndSkipBelts() {
        int fresh = 0;
        for (final CurrencyType type : currencies.all()) {
            if (type.operation() == CurrencyOperation.CATALYST && type.pickWeight() > 0) {
                fresh++;
                assertFalse(type.catalyst().legacy(), type.id());
            }
        }
        assertEquals(13, fresh);
        final ItemBase belt = base(b -> b.slot() == tw.exilecore.item.ItemSlot.BELT);
        final ItemData data = generator.generate(belt, Rarity.RARE, 60, new SplittableRandom(1));
        assertNotNull(Crafting.problem(currencies.get("catalyst_fire"), data, generator), "腰帶不能打催化劑");
        assertTrue(Catalyst.FIRE.matches(new tw.exilecore.stats.Modifier("resist.fire", tw.exilecore.stats.ModType.FLAT, 10, java.util.Set.of(), "t")));
        assertFalse(Catalyst.COLD.matches(new tw.exilecore.stats.Modifier("resist.fire", tw.exilecore.stats.ModType.FLAT, 10, java.util.Set.of(), "t")));
    }

    @Test
    void omensAreSplitPerCurrency() {
        final CurrencyType whittling = currencies.get("omen_whittling");
        assertTrue(Crafting.triggers(whittling, currencies.get("turmoil_orb")));
        assertFalse(Crafting.triggers(whittling, currencies.get("annul_orb")), "PoE2：去劣只有混沌石觸發");
        final CurrencyType exalt = currencies.get("omen_sinistral");
        assertTrue(Crafting.triggers(exalt, currencies.get("zenith_orb")));
        assertFalse(Crafting.triggers(exalt, currencies.get("bounty_orb")));
        assertFalse(Crafting.triggers(exalt, currencies.get("lustre_orb")));
        assertTrue(Crafting.triggers(currencies.get("omen_sinistral_coronation"), currencies.get("bounty_orb")));
        assertTrue(Crafting.triggers(currencies.get("omen_sinistral_coronation"), currencies.get("greater_bounty_orb")));
        assertFalse(Crafting.triggers(currencies.get("omen_sinistral_guard"), currencies.get("turmoil_orb")));
        assertTrue(Crafting.triggers(currencies.get("omen_dextral_erasure"), currencies.get("turmoil_orb")));
        // 完美星魄才觸發 Crystallisation
        final CurrencyType crystal = currencies.get("omen_sinistral_crystallisation");
        assertTrue(Crafting.triggers(crystal, currencies.get("perfect_essence_body")));
        assertFalse(Crafting.triggers(crystal, currencies.get("essence_body")));
        int omens = 0;
        for (final CurrencyType type : currencies.all()) {
            if (type.operation() == CurrencyOperation.OMEN) {
                omens++;
            }
        }
        assertTrue(omens >= 26, "星籤：" + omens);
    }

    @Test
    void greaterAnnulmentRemovesTwoAndAlchemyOmenMaxesPrefixes() {
        final ItemBase ring = base(b -> b.slot() == tw.exilecore.item.ItemSlot.RING);
        final ItemData rare = generator.generate(ring, Rarity.RARE, 80, new SplittableRandom(4));
        while (rare.affixes().size() < 4) {
            generator.addAffix(rare, null, new SplittableRandom(rare.affixes().size()));
        }
        final int before = rare.affixes().size();
        Crafting.applyWithOmen(currencies.get("omen_greater_annulment"), currencies.get("annul_orb"), rare, generator, new SplittableRandom(1));
        assertEquals(before - 2, rare.affixes().size());

        final ItemData normal = generator.generate(ring, Rarity.NORMAL, 80, new SplittableRandom(2));
        Crafting.applyWithOmen(currencies.get("omen_sinistral_alchemy"), currencies.get("spark_orb"), normal, generator, new SplittableRandom(3));
        assertEquals(Rarity.RARE, normal.rarity());
        assertEquals(3, generator.countOf(normal, AffixType.PREFIX));
        assertEquals(1, generator.countOf(normal, AffixType.SUFFIX));
    }

    @Test
    void socketsAreFullAndEtcherIsForCasters() {
        // 2026-10-26：工匠石照站長拿掉，新做的裝備照舊符文孔全開（中間那段時間記著 0 孔的也全開）
        final ItemBase body = base(b -> b.tags().contains("body") && b.tags().contains("armour"));
        final ItemData data = generator.generate(body, Rarity.RARE, 60, new SplittableRandom(1));
        assertEquals(Runes.slots(body), Runes.slots(items, body, data), "新做的裝備符文孔全開");
        assertNull(currencies.get("artificer_orb"), "工匠石拿掉了");
        final ItemData opened = data.copy();
        opened.setRuneSockets(0);
        assertEquals(Runes.slots(body), Runes.slots(items, body, opened), "記著 0 孔的也照基底全開");

        final ItemBase wand = base(b -> b.tags().contains("wand"));
        final ItemBase spear = base(b -> b.tags().contains("spear"));
        final ItemData w = generator.generate(wand, Rarity.NORMAL, 20, new SplittableRandom(1));
        final ItemData s = generator.generate(spear, Rarity.NORMAL, 20, new SplittableRandom(1));
        assertEquals("whetstone", currencies.get("arcanist_etcher").id(), "2026-10-27 照站長：星紋蝕刻器刪掉了，舊代號讀進來當成礪鋒石");
        // 2026-10-27 照站長：星紋蝕刻器併進礪鋒石，兩個都能用在所有武器（這次之前分開）
        assertTrue(currencies.get("whetstone").canApply(s, generator));
        assertTrue(currencies.get("whetstone").canApply(w, generator), "礪鋒石法杖類也能用");
        assertTrue(currencies.get("whetstone").canApply(s, generator));
    }

    @Test
    void foresightIsDeterministic() {
        final ItemBase ring = base(b -> b.slot() == tw.exilecore.item.ItemSlot.RING);
        final ItemData data = generator.generate(ring, Rarity.RARE, 80, new SplittableRandom(4));
        final CurrencyType lock = currencies.get("hinekora_lock");
        assertTrue(lock.canApply(data, generator));
        lock.operation().apply(data, generator, new SplittableRandom(5));
        assertNotNull(data.foresight());
        assertFalse(lock.canApply(data, generator));
        final ItemData a = data.copy();
        final ItemData b = data.copy();
        final CurrencyType chaos = currencies.get("turmoil_orb");
        chaos.operation().apply(a, generator, new SplittableRandom(data.foresight()));
        chaos.operation().apply(b, generator, new SplittableRandom(data.foresight()));
        assertEquals(a.affixes().stream().map(RolledAffix::id).toList(), b.affixes().stream().map(RolledAffix::id).toList(),
                "同一個種子：看到的結果跟真的用一樣");
    }

    @Test
    void gemQualityTableIsLoaded() {
        assertTrue(GemQuality.has("chain_thunder"), "Arc 的品質是連鎖");
        final List<tw.exilecore.stats.Modifier> mods = GemQuality.modifiers("chain_thunder", 20, "品質 20%");
        assertEquals("skill.chains", mods.getFirst().stat());
        assertEquals(2.0, mods.getFirst().value(), 1e-9);
        assertNull(GemQuality.modifiers("no_such_skill", 20, "x"));
    }
}
