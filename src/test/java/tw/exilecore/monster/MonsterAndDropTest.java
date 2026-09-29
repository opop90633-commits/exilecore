package tw.exilecore.monster;

import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import tw.exilecore.currency.CurrencyRepository;
import tw.exilecore.item.ItemGenerator;
import tw.exilecore.item.ItemRepository;
import tw.exilecore.stats.DamageType;
import tw.exilecore.stats.StatKeys;
import tw.exilecore.stats.StatSheet;
import tw.exilecore.stats.Tags;

import java.io.IOException;
import java.nio.file.Path;
import java.util.List;
import java.util.Random;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class MonsterAndDropTest {

    static final Path DATA = Path.of("src/main/resources/data");
    static MonsterConfig config;
    static DropTable drops;
    static ItemGenerator generator;
    static CurrencyRepository currencies;

    @BeforeAll
    static void load() throws IOException {
        config = new MonsterConfig();
        config.load(DATA);
        drops = new DropTable();
        drops.load(DATA);
        final ItemRepository repository = new ItemRepository();
        repository.load(DATA);
        generator = new ItemGenerator(repository);
        currencies = new CurrencyRepository();
        currencies.load(DATA);
    }

    @Test
    void levelByDistanceAndTablesInterpolate() {
        assertEquals(1, config.levelForDistance(10));
        assertEquals(3, config.levelForDistance(64));
        assertEquals(60, config.levelForDistance(100_000));
        assertEquals(20.0, config.lifeAt(1), 1e-9);
        assertEquals(20 + 50 * 4 / 9.0, config.lifeAt(5), 1e-9);   // 等級 1 的 20 → 等級 10 的 70，線性內插
        assertEquals(3600.0, config.lifeAt(60), 1e-9);
        assertEquals(11000.0, config.lifeAt(90), 1e-9);
    }

    @Test
    void monsterSheetsScaleWithRarityAndMods() {
        final StatSheet normal = MonsterSheets.build(config, new MonsterData(20, MonsterRarity.NORMAL, List.of(), "殭屍"), "ZOMBIE");
        final StatSheet rare = MonsterSheets.build(config, new MonsterData(20, MonsterRarity.RARE, List.of("sturdy", "fiery"), "焰牙殭屍"), "ZOMBIE");
        assertEquals(200, normal.get(StatKeys.LIFE_MAX), 1e-9);
        assertEquals(200 * 3.5 * 1.5, rare.get(StatKeys.LIFE_MAX), 1e-9);
        assertEquals(50, rare.get(StatKeys.RESIST_FIRE), 1e-9);
        final Set<String> fireContext = Set.of(Tags.ATTACK, Tags.MELEE, Tags.FIRE, Tags.ELEMENTAL);
        assertTrue(rare.flat(StatKeys.DAMAGE_ADDED_MIN, fireContext) > 0);
        assertEquals(0, normal.flat(StatKeys.DAMAGE_ADDED_MIN, fireContext), 1e-9);
        assertEquals(DamageType.FIRE, DamageType.fromTag("fire"));
    }

    @Test
    void dropCountsFollowTheTable() {
        final Random random = new Random(3);
        int total = 0;
        int currency = 0;
        int equipment = 0;
        for (int i = 0; i < 4000; i++) {
            final List<DropTable.Drop> rolled = drops.roll(30, MonsterRarity.MAGIC, 0, 0, generator, currencies, random);
            total += rolled.size();
            for (final DropTable.Drop drop : rolled) {
                if (drop.isCurrency()) {
                    currency++;
                } else {
                    equipment++;
                    assertEquals(30, drop.item().ilvl());
                }
            }
        }
        final double average = total / 4000.0;
        assertTrue(average > 1.35 && average < 1.65, "平均件數 " + average);
        assertTrue(currency > equipment, "通貨應該比裝備多");
    }

    @Test
    void rarityBonusMakesRareItemsMoreCommon() {
        final Random random = new Random(8);
        int rareNoBonus = 0;
        int rareBonus = 0;
        for (int i = 0; i < 20000; i++) {
            if (drops.rollRarity(0, random) == tw.exilecore.item.Rarity.RARE) {
                rareNoBonus++;
            }
            if (drops.rollRarity(2.0, random) == tw.exilecore.item.Rarity.RARE) {
                rareBonus++;
            }
        }
        // 稀有 5 → 15，魔法 25 → 75：稀有的占比從 5% 升到約 9.4%
        assertTrue(rareBonus > rareNoBonus * 1.5, rareNoBonus + " vs " + rareBonus);
    }
}
