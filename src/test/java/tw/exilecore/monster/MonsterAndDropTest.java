package tw.exilecore.monster;

import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import tw.exilecore.currency.CurrencyRepository;
import tw.exilecore.item.ItemGenerator;
import tw.exilecore.item.ItemRepository;
import tw.exilecore.stats.DamageType;
import tw.exilecore.stats.Modifier;
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
        // 2026-10-26 照 PoE2（Path of Building 的 Misc.lua，1–100 級逐級）
        assertEquals(15.0, config.lifeAt(1), 1e-9);
        assertEquals(33.0, config.lifeAt(5), 1e-9);
        assertEquals(4834.0, config.lifeAt(60), 1e-9);
        assertEquals(41748.0, config.lifeAt(90), 1e-9);
        assertEquals(56106.0, config.lifeAt(120), 1e-9, "超過最後一個錨點（100 級）取端點");
        assertEquals(1251, config.accuracyAt(68), 1e-9, "命中照 PoE2 的表");
        assertEquals(708, config.evasionAt(68), 1e-9);
        assertEquals(2413, config.armourAt(68), 1e-9);
        // 2026-10-26 照 PoE2 0.5：元素異常門檻、暈眩門檻（poise）的等級表與稀有度倍率
        assertEquals(65527, config.ailmentThresholdAt(80), 1e-9);
        assertEquals(213635, config.poiseAt(80), 1e-9);
        assertEquals(15, config.ailmentThresholdAt(1), 1e-9);
        assertEquals(1.26, config.rarityPoise(MonsterRarity.MAGIC), 1e-9);
        assertEquals(2.25, config.rarityPoise(MonsterRarity.RARE), 1e-9);
        assertEquals(6.0, config.rarityPoise(MonsterRarity.UNIQUE), 1e-9);
        assertEquals(1.0, config.rarityPoise(MonsterRarity.NORMAL), 1e-9);
        assertEquals(-30, config.raritySlowEffect(MonsterRarity.RARE), 1e-9);
        assertEquals(-50, config.rarityExposureEffect(MonsterRarity.UNIQUE), 1e-9);
        final double[] damage = config.damageAt(83); // PoE2 83 級每下平均 364，寫成 ±20%
        assertEquals(291, damage[0], 1e-9);
        assertEquals(437, damage[1], 1e-9);
    }

    /** 2026-10-26（PoE2 怪物效能）：增加的相加、更多的相乘；受到的傷害除以倍率，經驗與物品數量多一半。 */
    @Test
    void effectivenessMultipliesAndDefaultsToOne() {
        final StatSheet plain = MonsterSheets.build(config, new MonsterData(70, MonsterRarity.NORMAL, List.of(), "殭屍"), "ZOMBIE");
        assertEquals(1.0, MonsterSheets.effectiveness(plain), 1e-9, "沒有效能就是 1");
        assertEquals(1.0, MonsterSheets.effectiveness(null), 1e-9);
        final StatSheet sheet = MonsterSheets.build(config, new MonsterData(70, MonsterRarity.NORMAL, List.of(), "殭屍"), "ZOMBIE");
        sheet.add(Modifier.more(StatKeys.EFFECTIVENESS, 0.16, "換界石"));
        sheet.add(Modifier.more(StatKeys.EFFECTIVENESS, 0.13, "換界石"));
        sheet.add(Modifier.increased(StatKeys.EFFECTIVENESS, 0.15, "星圖天賦"));
        assertEquals(1.15 * 1.16 * 1.13, MonsterSheets.effectiveness(sheet), 1e-9);
    }

    @Test
    void monsterSheetsScaleWithRarityAndMods() {
        final StatSheet normal = MonsterSheets.build(config, new MonsterData(20, MonsterRarity.NORMAL, List.of(), "殭屍"), "ZOMBIE");
        final StatSheet rare = MonsterSheets.build(config, new MonsterData(20, MonsterRarity.RARE, List.of("sturdy", "fiery", "fire_resistant"), "焰牙殭屍"), "ZOMBIE");
        assertEquals(249, normal.get(StatKeys.LIFE_MAX), 1e-9, "2026-10-26 照 PoE2：20 級 249");
        assertEquals(249 * 8 * 1.5, rare.get(StatKeys.LIFE_MAX), 1e-9, "PoE2 稀有怪 ×8（+700%）");
        final StatSheet unique = MonsterSheets.build(config, new MonsterData(20, MonsterRarity.UNIQUE, List.of(), "王"), "ZOMBIE");
        assertEquals(249 * 29, unique.get(StatKeys.LIFE_MAX), 1e-9, "PoE2 傳奇 ×29（+2800%）");
        assertEquals(30, unique.get(StatKeys.RESIST_FIRE), 1e-9, "PoE2 的 Boss 元素抗性 30%");
        assertEquals(0, normal.get(StatKeys.RESIST_FIRE), 1e-9);
        assertEquals(50, rare.get(StatKeys.RESIST_FIRE), 1e-9, "2026-10-23：抗性拆成另一條詞綴（耐火的），燃焰的只加火焰傷害");
        final Set<String> fireContext = Set.of(Tags.ATTACK, Tags.MELEE, Tags.FIRE, Tags.ELEMENTAL);
        assertTrue(rare.flat(StatKeys.DAMAGE_ADDED_MIN, fireContext) > 0);
        assertEquals(0, normal.flat(StatKeys.DAMAGE_ADDED_MIN, fireContext), 1e-9);
        assertEquals(DamageType.FIRE, DamageType.fromTag("fire"));
    }

    @Test
    void experienceAndVanillaHealthScale() {
        // 2026-10-27 照 PoE2：PoE 的怪物經驗表 × 1（monsters.yml 的 experience_multiplier；全服 ×2 在 character.yml）；這次之前 ×2（40／16,530／26,500／27,278）
        assertEquals(1.0, config.experienceMultiplier(), 1e-9);
        assertEquals(20.0, config.experienceAt(1), 1e-9);
        assertEquals(8_265.0, config.experienceAt(60), 1e-9);
        assertEquals(13_250.0, config.experienceAt(84), 1e-9);
        assertEquals(13_639.0, config.experienceAt(91), 1e-9);
        assertEquals(13_639.0, config.experienceAt(100), 1e-9, "91 級以後不再增加");
        for (int level = 2; level <= 100; level++) {
            assertTrue(config.experienceAt(level) >= config.experienceAt(level - 1), "不會越高等給越少：" + level);
        }
        assertEquals(1.0, config.rarity(MonsterRarity.NORMAL).experienceMultiplier(), 1e-9);
        assertEquals(5.0, config.rarity(MonsterRarity.RARE).experienceMultiplier(), 1e-9); // 2026-10-27 照 PoE2（+400%）；這次之前 12
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
        // 2026-10 第五次（照市場報告：寶石、天啟石、恩澤石、星盤刻刀、符文變少）：件數 × 1210/1270 → 0.880
        assertTrue(average > 0.83 && average < 0.93, "平均件數 " + average);
        // 通貨與裝備的分類權重 548：600（2026-10 第五次；以前 570：600、更早 126：120）
        // 2026-10-26：通貨的分類權重 504 → 588（照 PoE2 加了高階／完美通貨、蝕刻器、工匠石、預見鎖，其他通貨掉率不變）
        assertEquals(588.0 / 600.0, currency / (double) equipment, 0.1, "通貨與裝備差不多：" + currency + "／" + equipment);
        // 2026-10-22（照市場報告）：技能、輔助寶石的分類權重各 20 → 12，寶石合計 24 比藥劑的 30 少了：照比例驗，不再要求寶石比藥劑多
        assertTrue(equipment > flasks && gems > 0 && flasks > 0, "裝備 > 藥劑 > 0、寶石 > 0：" + equipment + "／" + gems + "／" + flasks);
        assertEquals(24.0 / 30.0, gems / (double) flasks, 0.3, "寶石：藥劑 ≈ 24：30（" + gems + "／" + flasks + "）");
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
        assertEquals(currencyBefore * 300 * 0.35, bounty / per, 0.004, "豐饒石 570 → 300，2026-10-24 再 ×0.35");
        assertEquals(currencyBefore * 24 * 0.35, lesserSocket / per, 0.002, "小鑿星石 160 → 24，2026-10-24 再 ×0.35");
        assertEquals(currencyBefore * 260 * 0.35, spark / per, 0.003, "星火石 2026-10-24 ×0.35");
        // 2026-10 第五次（照市場報告）：未切割技能寶石的分類權重 40 → 20、輔助寶石 30 → 12
        // 2026-10-22 第六次：未切割技能寶石 20 → 12
        assertEquals(1.5 * 6 / 103 / 3 / 2 * 0.6, skill / per, 0.003, "未切割技能寶石是以前的 1/6 × 0.6");
        assertEquals(1.5 * 4 / 103 * 1.5 / 4 * 0.4, support / per, 0.003, "未切割輔助寶石是以前的 3/8 × 2/5");
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
