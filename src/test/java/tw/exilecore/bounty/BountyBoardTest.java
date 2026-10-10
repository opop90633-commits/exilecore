package tw.exilecore.bounty;

import org.junit.jupiter.api.Test;
import tw.exilecore.core.config.YamlNode;
import tw.exilecore.monster.MonsterRarity;

import java.io.StringReader;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.random.RandomGenerator;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** 每日委託與每週回響的狀態機：產生、計數、完成、換日、一格一個鍵。 */
class BountyBoardTest {

    static final String DAY = "2026-10-03";
    static final String WEEK = "2026-W40";
    static final UUID ACCOUNT = UUID.fromString("00000000-0000-0000-0000-000000000042");

    static BountyConfig config() {
        final BountyConfig config = new BountyConfig();
        config.load(YamlNode.load("bounties.yml", new StringReader("""
                settings: { level_gap: 10 }
                daily:
                  count: 3
                  templates:
                    slayer: { text: "擊殺 {count} 隻", kill: { count: 3 }, merits: 10, experience: 10, weight: 5 }
                    elites: { text: "魔法 {count} 隻", kill: { count: 2, rarity: magic }, merits: 10, weight: 5 }
                    rares: { text: "稀有 {count} 隻", kill: { count: 1, rarity: rare }, merits: 20, weight: 5 }
                    shards: { text: "撿 {count} 片", collect: { count: 2, item: 碎屑, chance: 0.5 }, merits: 15, weight: 5 }
                    boss: { text: "Boss {count} 個", boss: { count: 1 }, merits: 20, weight: 5 }
                    maps: { text: "地圖 {count} 張", maps: { count: 2 }, merits: 25, weight: 5 }
                weekly: { merits: 100, experience: 50 }
                """)));
        return config;
    }

    static BountyBoard board(final BountyConfig config, final Map<String, String> progress) {
        return new BountyBoard(config, progress, DAY, WEEK);
    }

    static void apply(final Map<String, String> progress, final BountyBoard board) {
        for (final BountyBoard.Write write : board.writes()) {
            if (write.value() == null) {
                progress.remove(write.key());
            } else {
                progress.put(write.key(), write.value());
            }
        }
    }

    /** 固定的「亂數」。 */
    static RandomGenerator fixed(final double value) {
        return new RandomGenerator() {
            @Override
            public long nextLong() {
                return 0;
            }

            @Override
            public double nextDouble() {
                return value;
            }
        };
    }

    /** 每一格都是指定種類的委託（直接寫進度）。 */
    static Map<String, String> with(final String... templates) {
        final Map<String, String> progress = new HashMap<>();
        for (int i = 0; i < templates.length; i++) {
            progress.put(BountyBoard.key(i), new BountyBoard.Daily(i, DAY, templates[i], 0, false).encode());
        }
        return progress;
    }

    @Test
    void generationIsStablePerAccountAndDay() {
        final BountyConfig config = config();
        final Map<String, String> progress = new HashMap<>();
        BountyBoard board = board(config, progress);
        assertTrue(board.needsGeneration());
        board.generate(BountyBoard.seed(ACCOUNT, DAY), false);
        apply(progress, board);
        assertInstanceOf(BountyBoard.Generated.class, board.events().getFirst());
        board = board(config, progress);
        assertFalse(board.needsGeneration());
        final List<BountyBoard.Daily> today = board.today();
        assertEquals(3, today.size());
        final Set<String> kinds = new HashSet<>();
        for (final BountyBoard.Daily daily : today) {
            assertTrue(kinds.add(daily.template()), "不重複");
            assertNotEquals("maps", daily.template(), "不能跑地圖的角色抽不到地圖");
        }
        // 同一個帳號同一天：重新產生是同一批
        final Map<String, String> again = new HashMap<>();
        final BountyBoard second = board(config, again);
        second.generate(BountyBoard.seed(ACCOUNT, DAY), false);
        apply(again, second);
        assertEquals(progress, again);
        // 已經產生過：再叫一次什麼都不做
        board = board(config, progress);
        board.generate(BountyBoard.seed(ACCOUNT, DAY), false);
        assertTrue(board.writes().isEmpty());
        // 不同的帳號、不同天：大部分時候不一樣（看很多天的話）
        int differ = 0;
        for (int d = 1; d <= 20; d++) {
            final String day = "2026-11-" + String.format("%02d", d);
            final Map<String, String> other = new HashMap<>();
            final BountyBoard otherBoard = new BountyBoard(config, other, day, WEEK);
            otherBoard.generate(BountyBoard.seed(ACCOUNT, day), true);
            apply(other, otherBoard);
            final Set<String> otherKinds = new HashSet<>();
            for (final BountyBoard.Daily daily : new BountyBoard(config, other, day, WEEK).today()) {
                otherKinds.add(daily.template());
            }
            if (!otherKinds.equals(kinds)) {
                differ++;
            }
        }
        assertTrue(differ >= 10, "換日會換委託（" + differ + "/20）");
    }

    @Test
    void mapsOnlyForCharactersThatCanRunMaps() {
        final BountyConfig config = config();
        boolean sawMaps = false;
        for (int d = 1; d <= 28; d++) {
            final String day = "2026-02-" + String.format("%02d", d);
            final Map<String, String> progress = new HashMap<>();
            final BountyBoard board = new BountyBoard(config, progress, day, WEEK);
            board.generate(BountyBoard.seed(ACCOUNT, day), true);
            apply(progress, board);
            for (final BountyBoard.Daily daily : new BountyBoard(config, progress, day, WEEK).today()) {
                sawMaps |= daily.template().equals("maps");
            }
        }
        assertTrue(sawMaps, "能跑地圖的角色抽得到地圖");
    }

    @Test
    void newDayMeansNewDailies() {
        final BountyConfig config = config();
        final Map<String, String> progress = with("slayer", "elites", "rares");
        assertEquals(3, board(config, progress).today().size());
        final BountyBoard tomorrow = new BountyBoard(config, progress, "2026-10-04", WEEK);
        assertTrue(tomorrow.today().isEmpty(), "昨天的不算");
        assertTrue(tomorrow.needsGeneration());
        tomorrow.generate(BountyBoard.seed(ACCOUNT, "2026-10-04"), false);
        apply(progress, tomorrow);
        for (final BountyBoard.Daily daily : new BountyBoard(config, progress, "2026-10-04", WEEK).today()) {
            assertEquals("2026-10-04", daily.day());
            assertEquals(0, daily.count());
        }
    }

    @Test
    void killsCountWithRarityAndLevelGap() {
        final BountyConfig config = config();
        final Map<String, String> progress = with("slayer", "elites", "rares");
        BountyBoard board = board(config, progress);
        board.kill(30, 50, MonsterRarity.NORMAL, false, fixed(0));
        assertTrue(board.writes().isEmpty(), "怪物比角色低 20 級不算");
        board.kill(40, 50, MonsterRarity.NORMAL, false, fixed(0));
        apply(progress, board);
        assertEquals(1, board(config, progress).slot(0).count(), "低 10 級還算");
        assertEquals(0, board(config, progress).slot(1).count(), "普通怪不算魔法以上");
        board = board(config, progress);
        board.kill(50, 50, MonsterRarity.MAGIC, false, fixed(0));
        apply(progress, board);
        assertEquals(2, board(config, progress).slot(0).count());
        assertEquals(1, board(config, progress).slot(1).count());
        assertEquals(0, board(config, progress).slot(2).count(), "魔法怪不算稀有");
        assertInstanceOf(BountyBoard.Counted.class, board.events().getFirst());
        // 稀有怪：三格都算，殺怪的那格滿了、稀有的那格也滿了
        board = board(config, progress);
        board.kill(55, 50, MonsterRarity.RARE, false, fixed(0));
        apply(progress, board);
        final List<BountyBoard.Event> events = board.events();
        assertEquals(3, events.size());
        int completed = 0;
        for (final BountyBoard.Event event : events) {
            if (event instanceof BountyBoard.Completed done) {
                completed++;
                assertTrue(done.daily().done());
            }
        }
        assertEquals(3, completed, "擊殺 3、魔法 2、稀有 1 都完成了");
        assertEquals(3, board(config, progress).doneToday());
        // 完成了就不再算
        board = board(config, progress);
        board.kill(55, 50, MonsterRarity.RARE, false, fixed(0));
        assertTrue(board.writes().isEmpty());
    }

    @Test
    void collectRollsAndBossesCount() {
        final BountyConfig config = config();
        final Map<String, String> progress = with("shards", "boss", "maps");
        BountyBoard board = board(config, progress);
        board.kill(50, 50, MonsterRarity.NORMAL, false, fixed(0.6));
        assertTrue(board.writes().isEmpty(), "普通怪 50%：擲到 0.6 沒撿到");
        board.kill(50, 50, MonsterRarity.MAGIC, false, fixed(0.6));
        apply(progress, board);
        assertEquals(1, board(config, progress).slot(0).count(), "魔法怪 2 倍＝100%");
        board = board(config, progress);
        board.kill(50, 50, MonsterRarity.UNIQUE, true, fixed(0.99));
        apply(progress, board);
        assertTrue(board(config, progress).slot(0).done(), "Boss 必掉");
        assertTrue(board(config, progress).slot(1).done(), "打倒 Boss");
        assertEquals(0, board(config, progress).slot(2).count(), "殺怪不算完成地圖");
        // 完成地圖：等級太低的不算
        board = board(config, progress);
        board.mapCompleted(30, 60);
        assertTrue(board.writes().isEmpty());
        board.mapCompleted(61, 60);
        board.mapCompleted(62, 60);
        apply(progress, board);
        assertTrue(board(config, progress).slot(2).done());
        assertEquals(3, board(config, progress).doneToday());
    }

    @Test
    void eachSlotIsItsOwnKey() {
        final BountyConfig config = config();
        final Map<String, String> progress = with("slayer", "elites", "rares");
        final BountyBoard board = board(config, progress);
        board.kill(50, 50, MonsterRarity.NORMAL, false, fixed(0));
        assertEquals(List.of(new BountyBoard.Write("bounty.d0", DAY + "|slayer|1")), board.writes(), "只動到那一格");
        assertNull(BountyBoard.parse(0, "壞掉的"));
        assertNull(BountyBoard.parse(0, DAY + "|slayer|x"));
        assertEquals(new BountyBoard.Daily(2, DAY, "rares", 1, true), BountyBoard.parse(2, DAY + "|rares|1|done"));
        assertTrue(BountyBoard.isDone(DAY + "|rares|1|done"));
        assertFalse(BountyBoard.isDone(DAY + "|rares|0"));
    }

    @Test
    void removedTemplateIsRefilled() {
        final BountyConfig config = config();
        final Map<String, String> progress = with("slayer", "gone", "rares");
        BountyBoard board = board(config, progress);
        assertEquals(2, board.today().size(), "設定檔已經沒有的委託不算");
        assertNull(board.slot(1));
        assertTrue(board.needsGeneration());
        board.generate(1234, false);
        apply(progress, board);
        board = board(config, progress);
        assertEquals(3, board.today().size());
        assertEquals("slayer", board.slot(0).template(), "原本的格子不動");
        assertEquals("rares", board.slot(2).template());
        assertNotEquals("slayer", board.slot(1).template(), "補上的不跟其他格重複");
        assertNotEquals("rares", board.slot(1).template());
    }

    @Test
    void weeklyOncePerWeek() {
        final BountyConfig config = config();
        final Map<String, String> progress = new HashMap<>();
        BountyBoard board = board(config, progress);
        assertFalse(board.weeklyDone());
        assertTrue(board.completeWeekly());
        apply(progress, board);
        assertInstanceOf(BountyBoard.WeeklyCompleted.class, board.events().getFirst());
        board = board(config, progress);
        assertTrue(board.weeklyDone());
        assertFalse(board.completeWeekly(), "同一週不能再領");
        assertFalse(new BountyBoard(config, progress, DAY, "2026-W41").weeklyDone(), "下一週重新來");
        // 開回響的次數：每週重新算
        board = board(config, progress);
        assertEquals(0, board.echoAttempts());
        board.useEchoAttempt();
        board.useEchoAttempt();
        apply(progress, board);
        assertEquals(2, board(config, progress).echoAttempts());
        assertEquals(0, new BountyBoard(config, progress, DAY, "2026-W41").echoAttempts());
        board = board(config, progress);
        board.resetWeekly();
        apply(progress, board);
        assertFalse(board(config, progress).weeklyDone());
        assertEquals(0, board(config, progress).echoAttempts(), "管理員重置連次數一起清");
    }

    @Test
    void adminCompleteAndClear() {
        final BountyConfig config = config();
        final Map<String, String> progress = with("slayer", "elites", "rares");
        BountyBoard board = board(config, progress);
        assertTrue(board.forceComplete(1));
        assertFalse(board.forceComplete(1), "完成過了");
        assertFalse(board.forceComplete(5), "沒有這一格");
        apply(progress, board);
        assertInstanceOf(BountyBoard.Completed.class, board.events().getFirst());
        assertEquals(2, board(config, progress).slot(1).count(), "計數直接滿");
        board = board(config, progress);
        board.clearToday();
        apply(progress, board);
        assertTrue(board(config, progress).needsGeneration());
        assertNotNull(progress);
        assertTrue(progress.isEmpty());
    }

    @Test
    void levelGapIsInclusive() {
        final BountyBoard board = board(config(), new HashMap<>());
        assertTrue(board.atLevel(50, 60));
        assertFalse(board.atLevel(49, 60));
        assertTrue(board.atLevel(70, 60));
    }
}
