package tw.exilecore.item;

import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import tw.exilecore.monster.DropTable;
import tw.exilecore.monster.MonsterRarity;
import tw.exilecore.currency.CurrencyRepository;
import tw.exilecore.stats.Modifier;
import tw.exilecore.stats.StatKeys;

import java.io.IOException;
import java.nio.file.Path;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.SplittableRandom;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** 珠寶（PoE2 的珠寶）：基底、顏色決定的詞綴池、詞綴上限、掉落。用 jar 裡的資料檔。 */
class JewelTest {

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
    void fourJewelBasesWithColours() {
        final ItemBase ruby = repository.base("ruby_jewel");
        final ItemBase emerald = repository.base("emerald_jewel");
        final ItemBase sapphire = repository.base("sapphire_jewel");
        final ItemBase diamond = repository.base("diamond_jewel");
        for (final ItemBase base : List.of(ruby, emerald, sapphire, diamond)) {
            assertNotNull(base);
            assertTrue(base.isJewel());
            assertFalse(base.isWearable());
            assertFalse(base.isJewellery(), "珠寶不是飾品（催化劑打不了）");
            assertTrue(base.hasTag("jewel"), "slot: JEWEL 自動加 jewel 標籤");
            assertNotNull(base.jewelColor());
            assertTrue(base.dropWeight() < ItemBase.DEFAULT_DROP_WEIGHT, "珠寶比一般裝備少見");
        }
        assertNotNull(repository.base("short_sword"));
        assertEquals(ItemBase.DEFAULT_DROP_WEIGHT, repository.base("short_sword").dropWeight());
        assertNull(repository.base("short_sword").jewelColor());
        assertEquals("#e8f6ff", diamond.jewelColor(), "白星晶三色都有：白");
        assertTrue(Set.of(ruby.jewelColor(), emerald.jewelColor(), sapphire.jewelColor()).size() == 3, "三種顏色不一樣");
    }

    @Test
    void colourDecidesTheAffixPool() {
        final Set<String> rubyOnly = new HashSet<>();
        final Set<String> emeraldOnly = new HashSet<>();
        for (final Affix affix : repository.affixes()) {
            if (!affix.id().startsWith("jewel_")) {
                continue;
            }
            assertTrue(affix.canSpawnOn(repository.base("diamond_jewel")), "白星晶什麼珠寶詞綴都能出：" + affix.id());
            for (final ItemBase base : repository.bases()) {
                if (!base.isJewel()) {
                    assertFalse(affix.canSpawnOn(base), affix.id() + " 只出現在珠寶上，不該出現在 " + base.id());
                }
            }
            if (affix.spawnTags().equals(Set.of("ruby"))) {
                rubyOnly.add(affix.id());
            }
            if (affix.spawnTags().equals(Set.of("emerald"))) {
                emeraldOnly.add(affix.id());
            }
        }
        assertTrue(rubyOnly.contains("jewel_life_inc") && emeraldOnly.contains("jewel_evasion_inc"));
        // 一般裝備的詞綴不會出現在珠寶上
        for (final Affix affix : repository.affixes()) {
            if (!affix.id().startsWith("jewel_") && !affix.spawnTags().contains(UniqueItem.SPAWN_TAG)) {
                assertFalse(affix.canSpawnOn(repository.base("diamond_jewel")), affix.id() + " 不該出現在珠寶上");
            }
        }
        final SplittableRandom random = new SplittableRandom(3);
        for (int i = 0; i < 300; i++) {
            final ItemData ruby = generator.generate(repository.base("ruby_jewel"), Rarity.RARE, 70, random);
            for (final RolledAffix rolled : ruby.affixes()) {
                assertFalse(emeraldOnly.contains(rolled.id()), "赤星晶不會出翠星晶的詞綴：" + rolled.id());
            }
            assertTrue(ruby.affixes().size() >= 3 && ruby.affixes().size() <= 4, "稀有珠寶 3–4 條：" + ruby.affixes().size());
            assertTrue(generator.countOf(ruby, AffixType.PREFIX) <= ItemBase.JEWEL_AFFIXES_PER_TYPE);
            assertTrue(generator.countOf(ruby, AffixType.SUFFIX) <= ItemBase.JEWEL_AFFIXES_PER_TYPE);
            assertTrue(repository.rareNameSuffixes(repository.base("ruby_jewel")).stream().anyMatch(ruby.name()::endsWith),
                    "珠寶有自己的稀有名字後綴：" + ruby.name());
            assertTrue(repository.rareNameSuffixes(repository.base("ruby_jewel")).contains("之核"), "names.yml 的 jewel 後綴");
        }
    }

    @Test
    void jewelAffixCapsAlsoApplyToCurrency() {
        final ItemData jewel = generator.generate(repository.base("sapphire_jewel"), Rarity.NORMAL, 60, new SplittableRandom(4));
        generator.upgradeToRare(jewel, new SplittableRandom(5));
        assertEquals(3, jewel.affixes().size(), "星火石做出來的稀有珠寶 3 條");
        assertTrue(generator.addAffix(jewel, null, new SplittableRandom(6)), "至臻石可以加到第 4 條");
        assertFalse(generator.canAddAffix(jewel, null), "第 5 條加不上（前後綴各 2）");
        assertEquals(4, jewel.affixes().size());
        // 珠寶的修飾器就是詞綴的修飾器（沒有基底數值、沒有固定詞綴）
        final List<Modifier> mods = ItemStats.modifiers(repository, jewel);
        assertFalse(mods.isEmpty());
        for (final Modifier mod : mods) {
            assertFalse(mod.stat().equals(StatKeys.ARMOUR) && mod.type() == tw.exilecore.stats.ModType.FLAT, "珠寶沒有護甲基底值");
        }
        assertEquals(1, ItemStats.requirements(repository, repository.base("sapphire_jewel"), 60).level(), "珠寶沒有需求");
    }

    @Test
    void jewelsDropRarelyAndNeverNormal() {
        final DropTable table = new DropTable();
        try {
            table.load(DATA);
        } catch (final IOException e) {
            throw new AssertionError(e);
        }
        final SplittableRandom random = new SplittableRandom(9);
        int equipment = 0;
        int jewels = 0;
        for (int i = 0; i < 20000; i++) {
            for (final DropTable.Drop drop : table.roll(70, MonsterRarity.RARE, 0, 0, generator, currencies, random)) {
                if (!drop.isEquipment()) {
                    continue;
                }
                equipment++;
                final ItemBase base = repository.base(drop.item().base());
                if (base.isJewel()) {
                    jewels++;
                    assertTrue(drop.item().rarity() != Rarity.NORMAL, "珠寶掉出來至少是魔法的");
                }
            }
        }
        final double share = (double) jewels / equipment;
        assertTrue(share > 0.01 && share < 0.06, "珠寶約佔裝備掉落的 2–3%：" + share);
        // 商人不賣、不能賭
        for (final ItemBase base : repository.basesSoldAt(100)) {
            assertFalse(base.isJewel(), "商人不賣珠寶：" + base.id());
            assertFalse(base.isMap());
        }
        assertTrue(repository.basesDroppableAt(45).stream().anyMatch(ItemBase::isJewel));
        assertFalse(repository.basesDroppableAt(10).stream().anyMatch(ItemBase::isJewel), "20 級以前不掉珠寶");
        final Map<String, Integer> picked = new java.util.HashMap<>();
        for (int i = 0; i < 5000; i++) {
            picked.merge(repository.pickDroppable(80, random).id(), 1, Integer::sum);
        }
        assertTrue(picked.getOrDefault("diamond_jewel", 0) < picked.getOrDefault("ruby_jewel", 0), "白星晶比赤星晶少見");
        assertTrue(picked.getOrDefault("ruby_jewel", 0) < picked.getOrDefault("short_sword", 0), "珠寶比一般裝備少見");
    }
}
