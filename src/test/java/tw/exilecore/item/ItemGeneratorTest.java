package tw.exilecore.item;

import com.google.gson.Gson;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import tw.exilecore.currency.CurrencyOperation;
import tw.exilecore.currency.CurrencyRepository;
import tw.exilecore.currency.CurrencyType;
import tw.exilecore.stats.Modifier;
import tw.exilecore.stats.StatKeys;

import java.io.IOException;
import java.nio.file.Path;
import java.util.HashSet;
import java.util.List;
import java.util.Random;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** 用 jar 裡的真實資料檔跑生成與通貨操作，檢查 PoE 規則的不變量。 */
class ItemGeneratorTest {

    static final Path DATA = Path.of("src/main/resources/data");
    static ItemRepository repository;
    static ItemGenerator generator;
    static CurrencyRepository currencies;

    @BeforeAll
    static void load() throws IOException {
        repository = new ItemRepository();
        repository.load(DATA);
        generator = new ItemGenerator(repository);
        currencies = new CurrencyRepository();
        currencies.load(DATA);
    }

    @Test
    void dataFilesLoad() {
        assertEquals(40, repository.baseCount());
        assertTrue(repository.affixCount() >= 30, "詞綴數 " + repository.affixCount());
        assertEquals(13, currencies.size());
        assertNotNull(repository.base("short_sword"));
        assertNotNull(repository.affix("life_flat"));
    }

    @Test
    void generatedItemsRespectRarityLimitsGroupsAndItemLevel() {
        final Random random = new Random(7);
        final List<ItemBase> bases = List.copyOf(repository.bases());
        int rareCount = 0;
        for (int i = 0; i < 3000; i++) {
            final ItemBase base = bases.get(random.nextInt(bases.size()));
            final Rarity rarity = Rarity.values()[random.nextInt(3)];
            final int ilvl = 1 + random.nextInt(70);
            final ItemData data = generator.generate(base, rarity, ilvl, random);
            checkInvariants(data, base);
            if (rarity == Rarity.RARE) {
                rareCount++;
                assertTrue(data.affixes().size() >= 4 || generator.eligible(data, AffixType.PREFIX).isEmpty() || generator.eligible(data, AffixType.SUFFIX).isEmpty(),
                        "稀有物品至少 4 條詞綴：" + base.id() + " " + data.affixes().size());
                assertNotNull(data.name());
                assertFalse(data.identified());
            }
            if (rarity == Rarity.MAGIC) {
                assertTrue(data.affixes().size() >= 1 && data.affixes().size() <= 2);
                assertFalse(data.identified());
            }
            if (rarity == Rarity.NORMAL) {
                assertTrue(data.affixes().isEmpty());
                assertTrue(data.identified());
            }
        }
        assertTrue(rareCount > 800);
    }

    private void checkInvariants(final ItemData data, final ItemBase base) {
        final Set<String> groups = new HashSet<>();
        int prefixes = 0;
        int suffixes = 0;
        for (final RolledAffix rolled : data.affixes()) {
            final Affix affix = repository.affix(rolled.id());
            assertNotNull(affix, "未知詞綴 " + rolled.id());
            assertTrue(affix.canSpawnOn(base), affix.id() + " 不該出現在 " + base.id());
            assertTrue(groups.add(affix.group()), "群組重複 " + affix.group());
            final AffixTier tier = affix.tier(rolled.tier());
            assertTrue(tier.ilvl() <= data.ilvl(), affix.id() + " T" + tier.tier() + " 需要 ilvl " + tier.ilvl() + " 但物品是 " + data.ilvl());
            for (final var entry : tier.vars().entrySet()) {
                final int value = rolled.vars().get(entry.getKey());
                assertTrue(value >= Math.min(entry.getValue()[0], entry.getValue()[1]) && value <= Math.max(entry.getValue()[0], entry.getValue()[1]));
            }
            if (affix.type() == AffixType.PREFIX) {
                prefixes++;
            } else {
                suffixes++;
            }
        }
        assertTrue(prefixes <= data.rarity().maxPrefixes(), "前綴超過上限");
        assertTrue(suffixes <= data.rarity().maxSuffixes(), "後綴超過上限");
        if (base.implicit() != null) {
            assertEquals(base.implicit().vars().keySet(), data.implicitVars().keySet());
        }
    }

    @Test
    void higherTiersOnlyAppearAtHighItemLevelAndAreRarer() {
        final Random random = new Random(11);
        final ItemBase base = repository.base("plate_body");
        int t1 = 0;
        int t6 = 0;
        for (int i = 0; i < 2000; i++) {
            final ItemData data = generator.generate(base, Rarity.RARE, 80, random);
            for (final RolledAffix rolled : data.affixes()) {
                if (rolled.id().equals("life_flat")) {
                    if (rolled.tier() == 1) {
                        t1++;
                    } else if (rolled.tier() == 6) {
                        t6++;
                    }
                }
            }
        }
        assertTrue(t1 > 0 && t6 > t1 * 2, "T1=" + t1 + " T6=" + t6);

        final ItemData lowLevel = generator.generate(base, Rarity.RARE, 5, random);
        for (final RolledAffix rolled : lowLevel.affixes()) {
            assertTrue(rolled.tier() >= repository.affix(rolled.id()).tiers().size() - 1 || repository.affix(rolled.id()).tier(rolled.tier()).ilvl() <= 5);
        }
    }

    @Test
    void currencyOperationsFollowTheRules() {
        final Random random = new Random(21);
        final ItemBase base = repository.base("short_sword");
        final ItemData data = generator.generate(base, Rarity.NORMAL, 40, random);

        assertTrue(CurrencyOperation.UPGRADE_TO_MAGIC.canApply(data, generator));
        assertFalse(CurrencyOperation.REROLL_RARE.canApply(data, generator));
        CurrencyOperation.UPGRADE_TO_MAGIC.apply(data, generator, random);
        assertEquals(Rarity.MAGIC, data.rarity());
        assertTrue(data.identified());
        assertTrue(data.affixes().size() >= 1 && data.affixes().size() <= 2);

        // 補到 2 條後不能再加
        while (data.affixes().size() < 2) {
            assertTrue(CurrencyOperation.ADD_AFFIX_MAGIC.canApply(data, generator));
            CurrencyOperation.ADD_AFFIX_MAGIC.apply(data, generator, random);
        }
        assertFalse(CurrencyOperation.ADD_AFFIX_MAGIC.canApply(data, generator));

        CurrencyOperation.MAGIC_TO_RARE.apply(data, generator, random);
        assertEquals(Rarity.RARE, data.rarity());
        assertEquals(3, data.affixes().size());
        assertNotNull(data.name());

        int guard = 0;
        while (CurrencyOperation.ADD_AFFIX_RARE.canApply(data, generator) && guard++ < 10) {
            CurrencyOperation.ADD_AFFIX_RARE.apply(data, generator, random);
        }
        assertTrue(data.affixes().size() <= 6);
        checkInvariants(data, base);

        final List<RolledAffix> before = data.affixes().stream().map(RolledAffix::copy).toList();
        CurrencyOperation.REROLL_VALUES.apply(data, generator, random);
        assertEquals(before.size(), data.affixes().size());
        for (int i = 0; i < before.size(); i++) {
            assertEquals(before.get(i).id(), data.affixes().get(i).id());
            assertEquals(before.get(i).tier(), data.affixes().get(i).tier());
        }

        CurrencyOperation.SCOUR.apply(data, generator, random);
        assertEquals(Rarity.NORMAL, data.rarity());
        assertTrue(data.affixes().isEmpty());

        assertTrue(CurrencyOperation.QUALITY_WEAPON.canApply(data, generator));
        CurrencyOperation.QUALITY_WEAPON.apply(data, generator, random);
        assertEquals(5, data.quality());
        assertFalse(CurrencyOperation.QUALITY_ARMOUR.canApply(data, generator));
    }

    @Test
    void currencyTypesRestrictRarity() {
        final Random random = new Random(5);
        final ItemData normal = generator.generate(repository.base("hand_axe"), Rarity.NORMAL, 10, random);
        final CurrencyType turmoil = currencies.get("turmoil_orb");
        final CurrencyType glimmer = currencies.get("glimmer_orb");
        assertFalse(turmoil.canApply(normal, generator));
        assertTrue(glimmer.canApply(normal, generator));
    }

    @Test
    void modifiersAndNamesComeFromTheData() {
        final Random random = new Random(9);
        final ItemBase base = repository.base("short_sword");
        final ItemData data = generator.generate(base, Rarity.NORMAL, 1, random);
        final List<Modifier> mods = ItemStats.modifiers(repository, data);
        assertTrue(mods.stream().anyMatch(m -> m.stat().equals(StatKeys.WEAPON_MIN) && m.value() == 6));
        assertTrue(mods.stream().anyMatch(m -> m.stat().equals(StatKeys.WEAPON_APS) && m.value() == 1.55));
        assertEquals("短劍", ItemStats.displayName(repository, data));

        final ItemData high = generator.generate(base, Rarity.NORMAL, 60, random);
        final ItemStats.Snapshot snapshot = ItemStats.snapshot(repository, base, high);
        assertTrue(snapshot.damage().get(tw.exilecore.stats.DamageType.PHYSICAL)[1] > 40, "等級 60 的武器要明顯更強");
        assertEquals(54, snapshot.requirements().level());

        final ItemData magic = generator.generate(base, Rarity.MAGIC, 30, random);
        magic.setIdentified(true);
        final String name = ItemStats.displayName(repository, magic);
        assertTrue(name.contains("短劍") && name.length() > 2, name);
        assertFalse(ItemStats.affixLines(repository, magic).isEmpty());
        // 未鑑定時詞綴不生效
        magic.setIdentified(false);
        assertTrue(ItemStats.modifiers(repository, magic).stream().noneMatch(m -> m.source().contains("T")));
    }

    @Test
    void jsonRoundTrip() {
        final Random random = new Random(13);
        final ItemData data = generator.generate(repository.base("kite_shield"), Rarity.RARE, 45, random);
        data.setQuality(7);
        final Gson gson = new Gson();
        final ItemData back = gson.fromJson(gson.toJson(data), ItemData.class);
        assertEquals(data.uuid(), back.uuid());
        assertEquals(data.base(), back.base());
        assertEquals(data.rarity(), back.rarity());
        assertEquals(data.ilvl(), back.ilvl());
        assertEquals(7, back.quality());
        assertEquals(data.affixes().size(), back.affixes().size());
        assertEquals(data.affixes().get(0).vars(), back.affixes().get(0).vars());
        assertEquals(data.name(), back.name());
    }
}
