package tw.exilecore.slots;

import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import tw.exilecore.core.config.YamlNode;
import tw.exilecore.currency.CurrencyRepository;
import tw.exilecore.economy.EconomyConfig;
import tw.exilecore.sponsor.SponsorConfig;
import tw.exilecore.stats.ModType;
import tw.exilecore.stats.Modifier;
import tw.exilecore.stats.StatKeys;
import tw.exilecore.worldgen.BufferSink;
import tw.exilecore.worldgen.Campaign;
import tw.exilecore.worldgen.CampaignLayout;
import tw.exilecore.worldgen.CampaignStructures;
import tw.exilecore.worldgen.Palette;

import java.io.IOException;
import java.io.StringReader;
import java.nio.file.Path;
import java.util.ArrayDeque;
import java.util.Deque;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.SplittableRandom;
import java.util.random.RandomGenerator;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** 拉霸機（2026-11-02）：資料檔讀得進來、期望回報率小於 1、抽結果照權重、通貨數量、每個押注額一個稱號（保底已拿掉）、沒中的一排、機台站得住。 */
class SlotsConfigTest {

    static final Path DATA = Path.of("src/main/resources/data");
    static SlotsConfig config;
    static CurrencyRepository currencies;
    static EconomyConfig economy;
    static SponsorConfig sponsor;

    @BeforeAll
    static void load() throws IOException {
        config = new SlotsConfig();
        config.load(DATA);
        currencies = new CurrencyRepository();
        currencies.load(DATA);
        economy = new EconomyConfig();
        economy.load(DATA);
        sponsor = new SponsorConfig();
        sponsor.load(DATA);
    }

    /** 照順序吐出指定的 nextInt 結果（測權重的邊界）。 */
    private static final class FixedRandom implements RandomGenerator {
        private final Deque<Integer> values = new ArrayDeque<>();

        FixedRandom(final int... values) {
            for (final int value : values) {
                this.values.add(value);
            }
        }

        @Override
        public int nextInt(final int bound) {
            final int value = values.isEmpty() ? 0 : values.poll();
            assertTrue(value >= 0 && value < bound, "測試給的值 " + value + " 超出 " + bound);
            return value;
        }

        @Override
        public long nextLong() {
            return 0;
        }
    }

    @Test
    void shippedFileIsConsistent() {
        assertTrue(config.enabled());
        assertEquals(List.of(1000L, 10000L, 100000L, 500000L, 1000000L), config.bets()); // 2026-11-02 照站長拉高
        assertEquals(10000, config.totalWeight(), "總權重 10,000（註解的機率表照這個算）");
        final List<String> problems = config.validate(id -> currencies.get(id) != null, economy::value, id -> sponsor.cosmetics().containsKey(id));
        assertEquals(List.of(), problems);
        // 2026-11-02 照站長：只有金幣上的浮動，不出通貨
        for (final SlotsConfig.Outcome outcome : config.outcomes()) {
            assertTrue(outcome.kind() != SlotsConfig.Kind.CURRENCY && outcome.bonus() == null, outcome.id() + " 不能出通貨");
        }
        for (final String id : config.cosmetics()) {
            assertEquals(SlotsConfig.LIMITED_TAG, sponsor.cosmetics().get(id).limited(), id + "：sponsor.yml 寫 limited: 拉霸機");
            assertEquals(0, sponsor.cosmetics().get(id).fashionPrice(), id + "：限定的不能用時裝幣換");
        }
    }

    @Test
    void expectedReturnIsBelowOne() {
        final double ev = config.expectedReturn();
        assertTrue(ev < 1, "期望回報率要小於 1（回收金幣），實際 " + ev);
        assertTrue(ev > 0.8, "也不要低到沒人想玩，實際 " + ev);
        assertEquals(0.855, ev, 0.0005, "slots.yml 開頭註解寫的期望回報率（改了權重要一起改）");
    }

    @Test
    void expectedReturnFormula() {
        final SlotsConfig small = parse("""
                lose_weight: 50
                outcomes:
                  a: { kind: gold, multiplier: 1, weight: 40 }
                  b: { kind: gold, multiplier: 3, weight: 10 }
                """);
        assertEquals((40 * 1 + 10 * 3) / 100.0, small.expectedReturn(), 1e-9);
        assertEquals(0.4, small.chance(small.outcomes().getFirst()), 1e-9);
    }

    @Test
    void rejectsReturnOfOneOrMore() {
        assertThrows(IllegalArgumentException.class, () -> parse("""
                lose_weight: 0
                outcomes:
                  a: { kind: gold, multiplier: 1, weight: 1 }
                  b: { kind: gold, multiplier: 2, weight: 1 }
                """), "期望回報率 1.5：不能用");
        assertThrows(IllegalArgumentException.class, () -> parse("""
                settings: { reels: 4 }
                lose_weight: 90
                outcomes:
                  a: { kind: gold, multiplier: 2, weight: 5 }
                  b: { kind: gold, multiplier: 2, weight: 5 }
                """), "滾輪只能 3 或 5 個");
        assertThrows(IllegalArgumentException.class, () -> parse("""
                lose_weight: 90
                outcomes:
                  a: { kind: currency, multiplier: 2, weight: 5, pool: nope }
                  b: { kind: gold, multiplier: 2, weight: 5 }
                """), "通貨獎池要存在");
    }

    @Test
    void rollFollowsWeights() {
        final List<SlotsConfig.Outcome> outcomes = config.outcomes();
        final SlotsConfig.Outcome first = outcomes.getFirst();
        assertEquals(Optional.of(first), config.roll(new FixedRandom(0)));
        assertEquals(Optional.of(first), config.roll(new FixedRandom(first.weight() - 1)));
        assertEquals(Optional.of(outcomes.get(1)), config.roll(new FixedRandom(first.weight())));
        int before = 0;
        for (final SlotsConfig.Outcome outcome : outcomes) {
            before += outcome.weight();
        }
        assertEquals(Optional.of(outcomes.getLast()), config.roll(new FixedRandom(before - 1)), "最後一種獎");
        assertEquals(Optional.empty(), config.roll(new FixedRandom(before)), "後面都是沒中");
        assertEquals(Optional.empty(), config.roll(new FixedRandom(config.totalWeight() - 1)));
    }

    @Test
    void simulatedReturnMatchesFormula() {
        final RandomGenerator random = new SplittableRandom(20261102L);
        final int rounds = 2_000_000;
        double paid = 0;
        int jackpots = 0;
        for (int i = 0; i < rounds; i++) {
            final Optional<SlotsConfig.Outcome> outcome = config.roll(random);
            if (outcome.isPresent()) {
                paid += outcome.get().value();
                if (outcome.get().kind() == SlotsConfig.Kind.JACKPOT) {
                    jackpots++;
                }
            }
        }
        assertEquals(config.expectedReturn(), paid / rounds, 0.02, "模擬 200 萬把的回報率跟公式差不多");
        assertTrue(jackpots > 200 && jackpots < 600, "頭獎約 1/5000（200 萬把約 400 次），實際 " + jackpots);
    }

    @Test
    void currencyPrizeUsesReferenceValue() {
        final Map<String, Long> values = Map.of("zenith_orb", 1500L, "turmoil_orb", 4500L, "bounty_orb", 500L, "spark_orb", 500L,
                "chance_orb", 3000L, "annul_orb", 15000L);
        // 押 100、5 倍 = 500 金幣：只換得到豐饒石、星火石（各一顆）
        for (int i = 0; i < 200; i++) {
            final SlotsConfig.CurrencyPrize prize = config.currencyPrize("common", 5, 100, id -> values.getOrDefault(id, 0L), new SplittableRandom(i))
                    .orElseThrow();
            assertTrue(Set.of("bounty_orb", "spark_orb").contains(prize.id()), prize.id());
            assertEquals(1, prize.amount());
        }
        // 押 10,000、5 倍 = 50,000 金幣：抽到至臻石（第一個）是 33 顆（無條件捨去）
        final SlotsConfig.CurrencyPrize zenith = config.currencyPrize("common", 5, 10000, id -> values.getOrDefault(id, 0L), new FixedRandom(0))
                .orElseThrow();
        assertEquals(new SlotsConfig.CurrencyPrize("zenith_orb", 33), zenith);
        assertTrue(zenith.amount() * 1500L <= 50000, "價值不會超過倍數 × 押注");
        // 押注太小，一顆都換不到：空的（改給金幣）
        assertEquals(Optional.empty(), config.currencyPrize("rare", 25, 100, id -> 150000L, new SplittableRandom(1)));
        assertEquals(Optional.empty(), config.currencyPrize("common", 5, 100, id -> 0L, new SplittableRandom(1)), "沒有參考價值的不抽");
        assertEquals(2000, SlotsConfig.goldPrize(2, 1000));
        assertEquals(1_000_000, SlotsConfig.goldPrize(100, 10000));
    }

    /** 2026-11-02 照站長：稱號每個押注額一個（+1%～+5%）；這次之前只有一個「金運亨通」+5%，還有保底。 */
    @Test
    void titlesOnePerBet() {
        final List<SlotsConfig.Title> titles = config.titles();
        assertEquals(5, titles.size());
        final List<Long> bets = List.of(1000L, 10000L, 100000L, 500000L, 1000000L);
        final List<String> ids = List.of("slots_t1", "slots_t2", "slots_t3", "slots_t4", "slots_fortune");
        final List<String> names = List.of("小賭怡情", "手氣正旺", "一擲千金", "豪賭之王", "賭神");
        for (int i = 0; i < 5; i++) {
            final SlotsConfig.Title title = titles.get(i);
            assertEquals(bets.get(i), title.bet());
            assertEquals(ids.get(i), title.id());
            assertEquals(names.get(i), title.name());
            assertEquals(title, config.titleFor(bets.get(i)), "押 " + bets.get(i) + " 中頭獎給第 " + (i + 1) + " 階");
            assertEquals(title, config.titleById(ids.get(i)));
            assertEquals(List.of(new Modifier(StatKeys.GOLD_FIND, ModType.INCREASED, (i + 1) / 100.0, Set.of(), "稱號「" + names.get(i) + "」")),
                    title.stats(), "怪物掉落金幣 +" + (i + 1) + "%");
            assertEquals(i + 1, title.goldFindPercent(), 1e-9);
        }
        assertEquals(null, config.titleFor(100), "不是押注額的沒有稱號");
        assertEquals(null, config.titleFor(2000));
        assertEquals(List.of(), SlotsConfig.titleProblems(config.bets(), titles, true), "每個押注額剛好一個、代號不重複");
    }

    @Test
    void jackpotGrantsOnlyThatTier() {
        for (final SlotsConfig.Title title : config.titles()) {
            for (final long bet : config.bets()) {
                assertEquals(bet == title.bet(), title.jackpotGrants(bet),
                        "押 " + bet + " 中頭獎" + (bet == title.bet() ? "給" : "不給") + "「" + title.name() + "」");
            }
        }
        // 押 100 萬中頭獎只給 100 萬那一階，不會順便給比較低的
        final List<SlotsConfig.Title> granted = config.titles().stream().filter(t -> t.jackpotGrants(1_000_000)).toList();
        assertEquals(List.of(config.titleFor(1_000_000)), granted);
    }

    @Test
    void allTitlesStackToFifteenPercent() {
        final List<Modifier> all = new java.util.ArrayList<>();
        for (final SlotsConfig.Title title : config.titles()) {
            all.addAll(title.stats()); // 跟 BountyService.titleModifiers 一樣：擁有的稱號加成全部加上去
        }
        final tw.exilecore.stats.StatSheet sheet = new tw.exilecore.stats.StatSheet().addAll(all);
        assertEquals(0.15, sheet.increased(StatKeys.GOLD_FIND, Set.of()), 1e-9, "五個都有：怪物掉落金幣 +15%");
        final List<Modifier> merged = tw.exilecore.ascendancy.StatLines.merge(all, "稱號加成");
        assertEquals(1, merged.size());
        assertEquals(0.15, merged.getFirst().value(), 1e-9);
        // 只有第 1、3 階：+4%
        final tw.exilecore.stats.StatSheet some = new tw.exilecore.stats.StatSheet()
                .addAll(config.titles().get(0).stats()).addAll(config.titles().get(2).stats());
        assertEquals(0.04, some.increased(StatKeys.GOLD_FIND, Set.of()), 1e-9);
    }

    @Test
    void pityRemoved() throws IOException {
        final String text = java.nio.file.Files.readString(DATA.resolve(SlotsConfig.FILE));
        final YamlNode root = YamlNode.load("slots.yml", new StringReader(text));
        assertFalse(root.has("title"), "舊的單一 title 拿掉了");
        for (final YamlNode node : root.list("titles")) {
            assertFalse(node.has("pity_spins") || node.has("pity_min_bet") || node.has("jackpot_min_bet"), "稱號沒有保底、也沒有「以上」的設定");
        }
        assertFalse(java.util.Arrays.stream(SlotsConfig.Title.class.getRecordComponents()).anyMatch(c -> c.getName().toLowerCase(java.util.Locale.ROOT).contains("pity")),
                "稱號沒有保底的欄位");
        assertFalse(java.util.Arrays.stream(SlotsService.class.getDeclaredFields()).anyMatch(f -> f.getName().toLowerCase(java.util.Locale.ROOT).contains("pity")),
                "拉霸機不再累積保底把數（帳號裡的 slots.pity 不刪也不讀）");
    }

    @Test
    void legacyTitleIdKept() {
        // 2026-11-02 之前拿到「金運亨通」（slots_fortune，+5%）的帳號：直接算擁有 100 萬那一階，加成不變
        final SlotsConfig.Title legacy = config.titleById(SlotsConfig.LEGACY_TITLE_ID);
        assertEquals("slots_fortune", SlotsConfig.LEGACY_TITLE_ID);
        assertEquals(1_000_000L, legacy.bet());
        assertEquals("賭神", legacy.name()); // 2026-11-02 改名（代號不變，擁有的人照舊）
        assertEquals("gradient:#ffe066:#ff9f1c", legacy.color(), "顏色沒變");
        assertEquals(5, legacy.goldFindPercent(), 1e-9, "以前 +5%，現在還是 +5%");
        // 改過、還是舊格式的 slots.yml：舊稱號照舊登記成最高押注那一階（擁有的人不會掉加成），validate 提醒換新格式
        final SlotsConfig old = parse("""
                lose_weight: 90
                outcomes:
                  a: { kind: gold, multiplier: 2, weight: 5 }
                  b: { kind: jackpot, multiplier: 2, weight: 5 }
                title:
                  id: slots_fortune
                  name: 金運亨通
                  stats: [ { stat: gold.find, type: INCREASED, value: 5 } ]
                  jackpot_min_bet: 100
                  pity_spins: 3000
                  pity_min_bet: 100
                """);
        assertEquals(1, old.titles().size());
        assertEquals("slots_fortune", old.titleFor(100).id());
        assertTrue(old.validate(id -> true, id -> 1, id -> true).stream().anyMatch(p -> p.contains("舊的 title")));
    }

    @Test
    void rejectsBadTitles() {
        final String base = """
                lose_weight: 90
                outcomes:
                  a: { kind: gold, multiplier: 2, weight: 5 }
                  b: { kind: jackpot, multiplier: 2, weight: 5 }
                """;
        assertThrows(IllegalArgumentException.class, () -> parse(base + """
                titles:
                  - { bet: 100, id: x, name: 甲 }
                  - { bet: 100, id: y, name: 乙 }
                """), "同一個押注額兩個稱號");
        final IllegalArgumentException duplicate = assertThrows(IllegalArgumentException.class, () -> parse("[100, 200]", base + """
                titles:
                  - { bet: 100, id: x, name: 甲 }
                  - { bet: 200, id: x, name: 乙 }
                """), "代號重複");
        assertTrue(duplicate.getMessage().contains("重複"), duplicate.getMessage());
        assertThrows(IllegalArgumentException.class, () -> parse(base + """
                titles:
                  - { bet: 300, id: x, name: 甲 }
                """), "押注額不在 bets 裡");
        final SlotsConfig missing = parse(base + "titles: []\n");
        assertTrue(missing.validate(id -> true, id -> 1, id -> true).stream().anyMatch(p -> p.contains("沒有稱號")), "押注額沒有稱號：validate 會報");
    }

    @Test
    void loseLineNeverMatches() {
        final RandomGenerator random = new SplittableRandom(7);
        for (final int reels : new int[] {3, 5}) {
            for (int i = 0; i < 20000; i++) {
                final int[] line = SlotsConfig.loseLine(reels, 3, random);
                assertEquals(reels, line.length);
                boolean same = true;
                for (final int symbol : line) {
                    assertTrue(symbol >= 0 && symbol < 3);
                    same &= symbol == line[0];
                }
                assertFalse(same, "沒中的一排不會全部一樣");
            }
        }
        // 剛好全部一樣的那一次也會換掉
        final int[] fixed = SlotsConfig.loseLine(3, 4, new FixedRandom(2, 2, 2, 1, 0));
        assertEquals(3, fixed.length);
        assertFalse(fixed[0] == fixed[1] && fixed[1] == fixed[2]);
        final SlotsConfig.Outcome jackpot = config.outcomes().stream().filter(o -> o.kind() == SlotsConfig.Kind.JACKPOT).findFirst().orElseThrow();
        final int index = config.outcomes().indexOf(jackpot);
        assertArrayEquals(new int[] {index, index, index}, config.line(jackpot, random), "中了是全部一樣");
    }

    @Test
    void reelsStopLeftToRight() {
        for (final int reels : new int[] {3, 5}) {
            int previous = 0;
            for (int reel = 0; reel < reels; reel++) {
                final int stop = SlotsConfig.stopTick(reel, reels, 30);
                assertTrue(stop > previous, "第 " + reel + " 個比前一個晚停");
                previous = stop;
            }
            assertEquals(30, previous, "最後一個在 1.5 秒停");
        }
    }

    @Test
    void machineSpotIsStandable() {
        final int x = (int) Math.floor(config.machineX());
        final int y = CampaignLayout.CAMP_Y + 1;
        final int z = (int) Math.floor(config.machineZ());
        final BufferSink sink = new BufferSink((x >> 4) << 4, (z >> 4) << 4, ((x >> 4) << 4) + 15, ((z >> 4) << 4) + 15);
        Campaign.generateChunk(x >> 4, z >> 4, sink);
        final int below = sink.blockAt(x, y - 1, z);
        assertTrue(below > 0 && !Set.of("air", "water").contains(Palette.blockName(below)), "機台腳下要是實心方塊");
        for (final int dy : new int[] {0, 1}) {
            final int block = sink.blockAt(x, y + dy, z);
            assertTrue(block <= 0 || "air".equals(Palette.blockName(block)), "機台的位置被 " + Palette.blockName(Math.max(0, block)) + " 擋住");
        }
        final CampaignLayout.Spot spawn = CampaignLayout.campSpawn();
        assertTrue(Math.hypot(spawn.x() - config.machineX(), spawn.z() - config.machineZ()) > 3, "不要擋在營地落點");
        for (final CampaignStructures.Npc npc : CampaignStructures.Npc.values()) {
            final CampaignLayout.Spot spot = CampaignStructures.npcSpot(npc);
            assertTrue(Math.hypot(spot.x() - config.machineX(), spot.z() - config.machineZ()) > 3, "不要擠在 " + npc + " 旁邊");
        }
    }

    private static SlotsConfig parse(final String yaml) {
        return parse("[100]", yaml);
    }

    private static SlotsConfig parse(final String bets, final String yaml) {
        final SlotsConfig parsed = new SlotsConfig();
        parsed.load(YamlNode.load("slots.yml", new StringReader("bets: " + bets + "\n" + yaml)));
        return parsed;
    }
}
