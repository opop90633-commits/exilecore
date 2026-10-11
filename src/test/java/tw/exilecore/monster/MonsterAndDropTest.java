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
        assertEquals(17.0, config.lifeAt(1), 1e-9);
        assertEquals(17 * Math.pow(31 / 17.0, 4 / 9.0), config.lifeAt(5), 1e-9);   // 等級 1 的 17 → 等級 10 的 31，等比內插
        assertEquals(1540.0, config.lifeAt(60), 1e-9);
        assertEquals(3671.0, config.lifeAt(90), 1e-9);
        assertEquals(4105.0, config.lifeAt(120), 1e-9, "超過最後一個錨點（100 級）取端點");
        // 等比內插：錨點之間每一級漲一樣多 %（不會剛過錨點漲很多、快到下一個錨點只漲一點）
        final double first = config.lifeAt(21) / config.lifeAt(20);
        for (int level = 21; level < 30; level++) {
            assertEquals(first, config.lifeAt(level + 1) / config.lifeAt(level), 1e-9, "等級 " + level);
        }
        final double[] damage = config.damageAt(15);
        assertEquals(8 * Math.sqrt(18 / 8.0), damage[0], 1e-9);
        assertEquals(15 * Math.sqrt(30 / 15.0), damage[1], 1e-9);
    }

    @Test
    void monsterSheetsScaleWithRarityAndMods() {
        final StatSheet normal = MonsterSheets.build(config, new MonsterData(20, MonsterRarity.NORMAL, List.of(), "殭屍"), "ZOMBIE");
        final StatSheet rare = MonsterSheets.build(config, new MonsterData(20, MonsterRarity.RARE, List.of("sturdy", "fiery"), "焰牙殭屍"), "ZOMBIE");
        assertEquals(107, normal.get(StatKeys.LIFE_MAX), 1e-9);
        assertEquals(107 * 3.5 * 1.5, rare.get(StatKeys.LIFE_MAX), 1e-9);
        assertEquals(50, rare.get(StatKeys.RESIST_FIRE), 1e-9);
        final Set<String> fireContext = Set.of(Tags.ATTACK, Tags.MELEE, Tags.FIRE, Tags.ELEMENTAL);
        assertTrue(rare.flat(StatKeys.DAMAGE_ADDED_MIN, fireContext) > 0);
        assertEquals(0, normal.flat(StatKeys.DAMAGE_ADDED_MIN, fireContext), 1e-9);
        assertEquals(DamageType.FIRE, DamageType.fromTag("fire"));
    }

    @Test
    void experienceAndVanillaHealthScale() {
        // PoE 的怪物經驗表 × 2（monsters.yml 的 experience_multiplier）
        assertEquals(2.0, config.experienceMultiplier(), 1e-9);
        assertEquals(40.0, config.experienceAt(1), 1e-9);
        assertEquals(16_530.0, config.experienceAt(60), 1e-9);
        assertEquals(26_500.0, config.experienceAt(84), 1e-9);
        assertEquals(27_278.0, config.experienceAt(91), 1e-9);
        assertEquals(27_278.0, config.experienceAt(100), 1e-9, "91 級以後不再增加");
        for (int level = 2; level <= 100; level++) {
            assertTrue(config.experienceAt(level) >= config.experienceAt(level - 1), "不會越高等給越少：" + level);
        }
        assertEquals(1.0, config.rarity(MonsterRarity.NORMAL).experienceMultiplier(), 1e-9);
        assertEquals(12.0, config.rarity(MonsterRarity.RARE).experienceMultiplier(), 1e-9);
        // 40 級稀有怪 552 × 3.5 = 1932 > 原版上限 1024，按比例縮放
        final StatSheet rare = MonsterSheets.build(config, new MonsterData(40, MonsterRarity.RARE, List.of(), "x"), "ZOMBIE");
        final double life = rare.get(StatKeys.LIFE_MAX);
        assertTrue(life > MonsterSheets.VANILLA_HEALTH_CAP, "生命 " + life);
        assertEquals(MonsterSheets.VANILLA_HEALTH_CAP / life, MonsterSheets.healthScale(life), 1e-9);
        assertEquals(1.0, MonsterSheets.healthScale(200), 1e-9);
    }

    @Test
    void dropCountsFollowTheTable() {
        final Random random = new Random(3);
        int total = 0;
        int currency = 0;
        int equipment = 0;
        int gems = 0;
        int flasks = 0;
        for (int i = 0; i < 4000; i++) {
            final List<DropTable.Drop> rolled = drops.roll(30, MonsterRarity.MAGIC, 0, 0, generator, currencies, random);
            total += rolled.size();
            for (final DropTable.Drop drop : rolled) {
                switch (drop.kind()) {
                    case CURRENCY -> {
                        currency++;
                        assertTrue(drop.currency().dropLevel() <= 30, "高階的鑿星石不能從 30 級的怪掉：" + drop.currency().id());
                    }
                    case EQUIPMENT -> {
                        equipment++;
                        assertEquals(30, drop.item().ilvl());
                    }
                    case SKILL_GEM, SUPPORT_GEM -> {
                        gems++;
                        assertEquals(30, drop.level(), "寶石的等級由怪物等級決定");
                    }
                    case FLASK -> {
                        flasks++;
                        assertEquals(30, drop.level());
                    }
                }
            }
        }
        final double average = total / 4000.0;
        // 魔法怪 0.968 件（2026-10：寶石、藥劑、低階通貨掉少一點，件數變成以前的 0.7 倍；第二次至臻石等三種再少一點，再 × 0.926）
        assertTrue(average > 0.92 && average < 1.02, "平均件數 " + average);
        // 通貨與裝備的分類權重 126：120
        assertEquals(126.0 / 120.0, currency / (double) equipment, 0.15, "通貨與裝備差不多：" + currency + "／" + equipment);
        assertTrue(equipment > gems && gems > flasks && flasks > 0, "裝備 > 寶石 > 藥劑 > 0：" + equipment + "／" + gems + "／" + flasks);
    }

    @Test
    void fewerGemsAndJunkButTheSameGearAndCurrency() {
        // 2026-10：未切割技能寶石 1/3、輔助寶石與藥劑一半、微光石等四種低階通貨一半；裝備與其他通貨的掉率不變。
        // 2026-10 第二次：至臻石 0.4 倍、豐饒石 300/570、小鑿星石 60/160（人少沒有交易，幾乎沒人用）；其他還是不變。
        // 2026-10 第三、四次：至臻石 400 → 250 → 80、小鑿星石 60 → 24（照 100 人伺服器調高價通貨）；其他還是不變。
        // 以前：魔法怪 1.5 件，分類 60／30／6／4／3（合計 103），通貨權重合計 10,956（70 級都掉得到）
        final Random random = new Random(21);
        final int monsters = 40_000;
        int equipment = 0;
        int skill = 0;
        int support = 0;
        int flask = 0;
        int zenith = 0;
        int glimmer = 0;
        int bounty = 0;
        int lesserSocket = 0;
        int spark = 0;
        for (int i = 0; i < monsters; i++) {
            for (final DropTable.Drop drop : drops.roll(70, MonsterRarity.MAGIC, 0, 0, generator, currencies, random)) {
                switch (drop.kind()) {
                    case EQUIPMENT -> equipment++;
                    case SKILL_GEM -> skill++;
                    case SUPPORT_GEM -> support++;
                    case FLASK -> flask++;
                    case CURRENCY -> {
                        switch (drop.currency().id()) {
                            case "zenith_orb" -> zenith++;
                            case "glimmer_orb" -> glimmer++;
                            case "bounty_orb" -> bounty++;
                            case "lesser_socket_orb" -> lesserSocket++;
                            case "spark_orb" -> spark++;
                            default -> {
                            }
                        }
                    }
                    default -> {
                    }
                }
            }
        }
        final double per = monsters;
        assertEquals(1.5 * 30 / 103, equipment / per, 0.015, "裝備的掉率不變");
        final double currencyBefore = 1.5 * 60 / 103 / 10956;
        assertEquals(currencyBefore * 1000 * 0.08, zenith / per, 0.004, "至臻石是以前的 0.08 倍（1000 → 80）");
        assertEquals(currencyBefore * 570 * 300 / 570, bounty / per, 0.004, "豐饒石 570 → 300");
        assertEquals(currencyBefore * 160 * 24 / 160, lesserSocket / per, 0.002, "小鑿星石 160 → 24");
        assertEquals(currencyBefore * 260, spark / per, 0.003, "星火石的掉率不變");
        assertEquals(1.5 * 6 / 103 / 3, skill / per, 0.006, "未切割技能寶石是以前的 1/3");
        assertEquals(1.5 * 4 / 103 * 1.5 / 4, support / per, 0.005, "未切割輔助寶石是以前的 3/8（4 → 1.5）");
        assertEquals(1.5 * 3 / 103 / 2, flask / per, 0.005, "藥劑是以前的一半");
        assertEquals(1.5 * 60 / 103 * 3000 / 10956 / 2, glimmer / per, 0.012, "微光石是以前的一半");
    }

    @Test
    void goldDropsScaleWithLevelAndRarity() {
        final Random random = new Random(4);
        assertEquals(2, drops.goldRange(1)[0]);
        assertEquals(200, drops.goldRange(60)[1]);
        assertTrue(drops.goldRange(30)[0] > drops.goldRange(10)[0]);
        long normalTotal = 0;
        long rareTotal = 0;
        int normalDrops = 0;
        for (int i = 0; i < 2000; i++) {
            final long normal = drops.rollGold(20, MonsterRarity.NORMAL, 0, random);
            if (normal > 0) {
                normalDrops++;
            }
            normalTotal += normal;
            rareTotal += drops.rollGold(20, MonsterRarity.RARE, 0, random);
        }
        assertTrue(normalDrops > 1000 && normalDrops < 1400, "普通怪約 60% 掉金幣：" + normalDrops);
        assertTrue(rareTotal > normalTotal * 8, "稀有怪金幣要多很多");
        // 掉落的魔法／稀有裝備未鑑定（PoE2：帶回城找鑑定師免費鑑定），普通裝備沒有詞綴所以已鑑定
        int unidentified = 0;
        for (int i = 0; i < 200; i++) {
            for (final DropTable.Drop drop : drops.roll(30, MonsterRarity.RARE, 0, 2.0, generator, currencies, random)) {
                if (!drop.isEquipment()) {
                    continue;
                }
                if (drop.item().rarity() == tw.exilecore.item.Rarity.NORMAL) {
                    assertTrue(drop.item().identified());
                } else {
                    assertTrue(!drop.item().identified(), "魔法與稀有掉落要未鑑定");
                    unidentified++;
                }
            }
        }
        assertTrue(unidentified > 50, "應該掉了不少魔法／稀有裝備：" + unidentified);
    }

    @Test
    void serverBonusFromDropsYml(@org.junit.jupiter.api.io.TempDir final Path temp) throws IOException {
        // 出貨的 drops.yml：沒有加成
        assertEquals(0, drops.serverQuantity());
        assertEquals(0, drops.serverRarity());
        assertEquals(1, drops.serverGold());
        // 活動：數量 +50%、稀有度 +20%、金幣 2 倍（管理網頁改的值也是走這裡）
        java.nio.file.Files.writeString(temp.resolve("drops.yml"), """
                server_bonus: { quantity: 50, rarity: 20, gold: 2 }
                gold:
                  chance: { normal: 1.0, magic: 1.0, rare: 1.0, unique: 1.0 }
                  amount:
                    1: [10, 10]
                    100: [10, 10]
                """);
        final DropTable table = new DropTable();
        table.load(temp);
        assertEquals(0.5, table.serverQuantity(), 1e-9);
        assertEquals(0.2, table.serverRarity(), 1e-9);
        assertEquals(2.0, table.serverGold(), 1e-9);
        assertEquals(20, table.rollGold(50, MonsterRarity.NORMAL, 0, new Random(1)), "掉出來的金幣乘金幣倍數");
        assertEquals(10.0, table.averageGold(50), 1e-9, "價格照金幣表（商人、賭博），不跟著活動的倍數變");
        // 寫錯的範圍夾回去、拿掉這段就回到沒有加成
        java.nio.file.Files.writeString(temp.resolve("drops.yml"), "server_bonus: { quantity: 9999, rarity: -5, gold: 0 }\n");
        table.load(temp);
        assertEquals(DropTable.MAX_SERVER_BONUS / 100.0, table.serverQuantity(), 1e-9);
        assertEquals(0, table.serverRarity(), 1e-9);
        assertEquals(DropTable.MIN_SERVER_GOLD, table.serverGold(), 1e-9);
        java.nio.file.Files.writeString(temp.resolve("drops.yml"), "unidentified: true\n");
        table.load(temp);
        assertEquals(0, table.serverQuantity());
        assertEquals(1, table.serverGold());
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
