package tw.exilecore.event;

import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import tw.exilecore.bounty.Echoes;
import tw.exilecore.monster.MonsterConfig;
import tw.exilecore.monster.MonsterRarity;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.YearMonth;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** 每月活動：events.yml 讀得起來、主題照月份輪替、挑戰進度的存檔。 */
class EventTest {

    static final Path DATA = Path.of("src/main/resources/data");
    static EventConfig config;

    @BeforeAll
    static void load() throws IOException {
        config = new EventConfig();
        config.load(DATA);
    }

    @Test
    void shippedFileHasTwelveThemesWithValidModsAndTitles() throws IOException {
        assertTrue(config.enabled());
        assertEquals(12, config.themes().size(), "一年十二個月各一個主題");
        final MonsterConfig monsters = new MonsterConfig();
        monsters.load(DATA);
        final List<String> mods = new ArrayList<>();
        for (final MonsterConfig.MonsterMod mod : monsters.mods()) {
            mods.add(mod.id());
        }
        assertEquals(List.of(), config.validate(mods, List.of("shrine", "strongbox", "breach", "exile", "abyss"))); // 2026-10-18
        final java.util.Set<String> titleNames = new java.util.HashSet<>();
        for (final EventConfig.Theme theme : config.themes()) {
            assertNotNull(theme.title(), theme.id() + " 要有限定稱號");
            assertTrue(titleNames.add(theme.title().name()), theme.id() + " 的稱號名字重複");
            assertEquals("event." + theme.id(), theme.titleId());
            assertTrue(theme.challenges().size() >= 4 && theme.challenges().size() <= 7, theme.id() + "：4–7 個挑戰（選單放得下）");
            assertNotNull(theme.rareMod(), theme.id() + " 要有主題詞綴");
            assertTrue(theme.bonusMerits() > 0);
            for (final EventConfig.Challenge challenge : theme.challenges()) {
                assertTrue(challenge.rendered().contains(String.valueOf(challenge.count())), challenge.id());
                assertTrue(challenge.merits() > 0);
            }
            assertNotNull(theme.challenge("maps"));
            assertNull(theme.challenge("nope"));
        }
        assertTrue(Files.readString(DATA.resolve("index.txt")).contains("data/events.yml"), "index.txt 要列 events.yml");
    }

    @Test
    void themeRotatesByMonthAndForceOverrides() throws IOException {
        assertEquals(config.themes().get(0), config.themeFor(YearMonth.of(2026, 1)));
        assertEquals(config.themes().get(9), config.themeFor(YearMonth.of(2026, 10)));
        assertEquals(config.themes().get(11), config.themeFor(YearMonth.of(2027, 12)));
        assertEquals("2026-10", EventConfig.monthKey(YearMonth.of(2026, 10)));
        assertEquals("2027-01", EventConfig.monthKey(YearMonth.of(2027, 1)));
        assertEquals("", config.force());
        // 稱號選單的「下次輪到」：從下個月起找
        assertEquals(YearMonth.of(2027, 3), config.nextMonthFor(config.themes().get(2), YearMonth.of(2026, 11)), "3 月的主題下次是明年 3 月");
        assertEquals(YearMonth.of(2026, 11), config.nextMonthFor(config.themes().get(10), YearMonth.of(2026, 11)), "含起點那個月");
        assertNull(config.nextMonthFor(null, YearMonth.of(2026, 11)));
    }

    @Test
    void forceAndErrorsAreChecked(@TempDir final Path dir) throws IOException {
        Files.writeString(dir.resolve(EventConfig.FILE), """
                settings: { force: star_month }
                themes:
                  ember_month: { name: 餘燼, rare_mod: fiery, challenges: [{ id: kill, kind: kill, text: "殺 {count}", count: 10, merits: 5 }] }
                  star_month: { name: 星痕, rare_mod: resistant, event_boost: { shrine: 10 },
                                challenges: [{ id: boss, kind: boss, text: "Boss {count}", count: 3, merits: 5 }], title: { name: 見證者 } }
                """);
        final EventConfig fresh = new EventConfig();
        fresh.load(dir);
        assertEquals("star_month", fresh.themeFor(YearMonth.of(2026, 1)).id(), "force 指定的主題蓋過月份");
        assertNull(fresh.nextMonthFor(fresh.theme("ember_month"), YearMonth.of(2026, 1)), "force 指定了別的主題就永遠輪不到");
        assertEquals(YearMonth.of(2026, 1), fresh.nextMonthFor(fresh.theme("star_month"), YearMonth.of(2026, 1)));
        assertEquals(10, fresh.themeFor(YearMonth.of(2026, 1)).eventBoost().get("shrine"));
        assertNull(fresh.theme("ember_month").title());
        assertEquals(-1, fresh.theme("ember_month").ritualChance(), 1e-9, "沒寫 = 照 map_events.yml");
        assertEquals(List.of("events.yml 的 ember_month.rare_mod：monsters.yml 沒有「fiery」這個詞綴"),
                fresh.validate(List.of("resistant"), List.of("shrine")));

        Files.writeString(dir.resolve(EventConfig.FILE), "settings: { force: nope }\nthemes:\n  a: { name: A }\n");
        assertThrows(IllegalArgumentException.class, () -> new EventConfig().load(dir), "force 指到不存在的主題");
        Files.writeString(dir.resolve(EventConfig.FILE), "themes:\n  a: { name: A, challenges: [{ id: x, kind: dance, count: 1 }] }\n");
        assertThrows(IllegalArgumentException.class, () -> new EventConfig().load(dir), "不認得的 kind");
        Files.writeString(dir.resolve(EventConfig.FILE), "themes:\n  a: { name: A, challenges: [{ id: x, kind: kill, count: 1 }, { id: x, kind: boss, count: 1 }] }\n");
        assertThrows(IllegalArgumentException.class, () -> new EventConfig().load(dir), "挑戰代號重複");
        Files.writeString(dir.resolve(EventConfig.FILE), "settings: { enabled: false }\nthemes:\n  a: { name: A }\n");
        final EventConfig off = new EventConfig();
        off.load(dir);
        assertFalse(off.enabled());
        final EventConfig none = new EventConfig();
        none.load(dir.resolve("nowhere"));
        assertFalse(none.enabled(), "沒有檔案 = 沒有活動");
        assertNull(none.themeFor(YearMonth.of(2026, 1)));
    }

    @Test
    void progressCountsCompletesAndSurvivesEncoding() {
        final EventConfig.Theme theme = config.themes().get(9);
        final EventProgress progress = new EventProgress("2026-10");
        final EventConfig.Challenge boss = theme.challenge("boss");
        assertFalse(progress.add(boss, 10));
        assertEquals(10, progress.count("boss"));
        assertTrue(progress.add(boss, boss.count()), "剛好做完");
        assertEquals(boss.count(), progress.count("boss"), "不會超過目標");
        assertTrue(progress.done("boss"));
        assertFalse(progress.add(boss, 1), "做完的不再加");
        assertFalse(progress.allDone(theme.challenges()));
        assertEquals(1, progress.doneCount());
        final String text = progress.encode();
        assertTrue(text.startsWith("2026-10|boss=" + boss.count() + "|done=boss|t=0"), text);
        final EventProgress back = EventProgress.decode(text, "2026-10");
        assertEquals(boss.count(), back.count("boss"));
        assertTrue(back.done("boss"));
        assertFalse(back.titleGiven());
        // 換月：從頭
        final EventProgress next = EventProgress.decode(text, "2026-11");
        assertEquals(0, next.count("boss"));
        assertFalse(next.done("boss"));
        assertEquals("2026-11", next.month());
        // 全部做完、稱號發過
        for (final EventConfig.Challenge challenge : theme.challenges()) {
            progress.add(challenge, challenge.count());
        }
        assertTrue(progress.allDone(theme.challenges()));
        progress.markTitleGiven();
        assertTrue(EventProgress.decode(progress.encode(), "2026-10").titleGiven());
        assertTrue(progress.encode().length() < 250, "要放得進帳號進度的欄位");
        // 讀不懂的
        assertEquals(0, EventProgress.decode("亂寫", "2026-10").count("boss"));
        assertEquals(0, EventProgress.decode(null, "2026-10").doneCount());
    }

    @Test
    void minMonsterLevelIsWhatTheMenusTellThePlayer() {
        // 比較用的角色等級最高算到 84（最高階地圖的怪物），再減 level_gap（預設 10），最低 1
        assertEquals(1, Echoes.minMonsterLevel(1, 84, 10));
        assertEquals(1, Echoes.minMonsterLevel(10, 84, 10), "不會變成 0 以下");
        assertEquals(10, Echoes.minMonsterLevel(20, 84, 10));
        assertEquals(50, Echoes.minMonsterLevel(60, 84, 10));
        assertEquals(63, Echoes.minMonsterLevel(73, 84, 10));
        assertEquals(74, Echoes.minMonsterLevel(84, 84, 10));
        assertEquals(74, Echoes.minMonsterLevel(100, 84, 10), "100 級的角色打 T16（84 級）的怪還是算");
        assertEquals(84, Echoes.minMonsterLevel(100, 84, 0), "level_gap 0：只有跟你一樣高（或更高）的才算");
        assertEquals(84, Echoes.minMonsterLevel(100, 84, -5), "負的當 0");
        // 玩家看到的字：選單、任務追蹤、提醒都用同一個說法
        assertEquals("Lv.74 以上", EventText.atLeast(74));
    }

    @Test
    void aKillCountsForTheRightChallengeKinds() {
        final List<String> none = List.of();
        assertEquals(List.of(EventConfig.Kind.KILL), EventConfig.Kind.forKill(MonsterRarity.NORMAL, false, none, "deadly"));
        assertEquals(List.of(EventConfig.Kind.KILL), EventConfig.Kind.forKill(MonsterRarity.MAGIC, false, List.of("deadly"), "deadly"),
                "魔法怪帶了主題詞綴也不算主題擊殺（只有稀有以上）");
        assertEquals(List.of(EventConfig.Kind.KILL, EventConfig.Kind.RARE), EventConfig.Kind.forKill(MonsterRarity.RARE, false, none, "deadly"));
        assertEquals(List.of(EventConfig.Kind.KILL, EventConfig.Kind.RARE, EventConfig.Kind.THEMED),
                EventConfig.Kind.forKill(MonsterRarity.RARE, false, List.of("fast", "deadly"), "deadly"), "稀有怪帶著這個月的詞綴：三種都算");
        assertEquals(List.of(EventConfig.Kind.KILL, EventConfig.Kind.RARE, EventConfig.Kind.BOSS),
                EventConfig.Kind.forKill(MonsterRarity.UNIQUE, true, none, "deadly"), "Boss 是傳奇稀有度：擊殺、稀有、Boss");
        assertEquals(List.of(EventConfig.Kind.KILL, EventConfig.Kind.RARE), EventConfig.Kind.forKill(MonsterRarity.RARE, false, List.of("deadly"), null),
                "這個主題沒有詞綴：沒有主題擊殺");
    }

    @Test
    void advanceFinishesSeveralChallengesWithOneKill() {
        final EventConfig.Theme theme = config.themes().get(9);
        assertEquals("deadly_month", theme.id());
        final EventProgress progress = new EventProgress("2026-10");
        final List<EventConfig.Challenge> finished = new ArrayList<>();
        assertFalse(progress.advance(theme.challenges(), EventConfig.Kind.KILL, 0, finished), "加 0 不算變動");
        assertFalse(progress.advance(theme.challenges(), EventConfig.Kind.KILL, -3, finished));
        assertTrue(progress.advance(theme.challenges(), EventConfig.Kind.KILL, 1, finished));
        assertEquals(1, progress.count("kill"));
        assertEquals(0, progress.count("rare"), "只加到這一種的挑戰");
        assertTrue(finished.isEmpty());

        // 擊殺做到差 2 隻、稀有做到差 1 隻；殺一隻稀有怪：擊殺還差 1、稀有剛好做完——每個挑戰各自剛好做完的那一下才算
        final int kills = theme.challenge("kill").count();
        progress.advance(theme.challenges(), EventConfig.Kind.KILL, kills - 3, finished);
        progress.advance(theme.challenges(), EventConfig.Kind.RARE, theme.challenge("rare").count() - 1, finished);
        assertTrue(finished.isEmpty());
        assertEquals(kills - 2, progress.count("kill"));
        for (final EventConfig.Kind kind : EventConfig.Kind.forKill(MonsterRarity.RARE, false, List.of(), null)) {
            progress.advance(theme.challenges(), kind, 1, finished);
        }
        assertEquals(kills - 1, progress.count("kill"));
        assertEquals(List.of("rare"), finished.stream().map(EventConfig.Challenge::id).toList());
        finished.clear();
        assertTrue(progress.advance(theme.challenges(), EventConfig.Kind.KILL, 1, finished), "再殺一隻普通怪：擊殺剛好做完");
        assertEquals(List.of("kill"), finished.stream().map(EventConfig.Challenge::id).toList());
        finished.clear();
        assertFalse(progress.advance(theme.challenges(), EventConfig.Kind.KILL, 1, finished), "做完的不再動");
        assertFalse(progress.advance(theme.challenges(), EventConfig.Kind.RARE, 1, finished));
        assertTrue(finished.isEmpty());
        assertEquals(2, progress.doneCount());
        // 沒有這一種挑戰的主題：什麼都不變
        final EventProgress other = new EventProgress("2026-10");
        assertFalse(other.advance(List.of(theme.challenge("kill")), EventConfig.Kind.BOSS, 1, finished));
        // 存起來再讀回來數字還在
        final EventProgress back = EventProgress.decode(progress.encode(), "2026-10");
        assertEquals(theme.challenge("kill").count(), back.count("kill"));
        assertTrue(back.done("kill") && back.done("rare"));
    }

    @Test
    void trackerLinesShowProgressAndTheLevelRule() {
        final EventConfig.Theme theme = config.themes().get(9);
        final EventProgress progress = new EventProgress("2026-10");
        progress.advance(theme.challenges(), EventConfig.Kind.KILL, 139, new ArrayList<>());
        final List<String> lines = EventText.trackerLines(theme, progress, 74);
        assertEquals(4, lines.size(), "標題、前兩個挑戰、還有幾個");
        assertTrue(lines.get(0).contains("致命之月") && lines.get(0).contains("0/6") && lines.get(0).contains("Lv.74+"), lines.get(0));
        // 2026-10：每月挑戰的次數 ×4（擊殺 12000、稀有 600）
        assertTrue(lines.get(1).contains("擊殺") && lines.get(1).contains("139/12000"), lines.get(1));
        assertTrue(lines.get(2).contains("擊殺稀有") && lines.get(2).contains("0/600"), lines.get(2));
        assertTrue(lines.get(3).contains("還有 4 個") && lines.get(3).contains("/event"), lines.get(3));
        // 做完的挑戰讓位給後面的
        progress.advance(theme.challenges(), EventConfig.Kind.KILL, theme.challenge("kill").count(), new ArrayList<>());
        final List<String> next = EventText.trackerLines(theme, progress, 74);
        assertTrue(next.get(0).contains("1/6"), next.get(0));
        assertTrue(next.get(1).contains("擊殺稀有") && next.get(2).contains("主題擊殺"), next.toString());
        // 全部做完：不佔側欄
        for (final EventConfig.Challenge challenge : theme.challenges()) {
            progress.advance(theme.challenges(), challenge.kind(), challenge.count(), new ArrayList<>());
        }
        assertTrue(EventText.trackerLines(theme, progress, 74).isEmpty());
        // 只剩兩個以內：沒有「還有幾個」
        final EventProgress almost = new EventProgress("2026-10");
        for (final EventConfig.Challenge challenge : theme.challenges()) {
            if (!challenge.id().equals("boss") && !challenge.id().equals("maps")) {
                almost.advance(theme.challenges(), challenge.kind(), challenge.count(), new ArrayList<>());
            }
        }
        final List<String> two = EventText.trackerLines(theme, almost, 1);
        assertEquals(3, two.size());
        assertTrue(two.get(0).contains("4/6"));
    }

    @Test
    void theLastKillNoteSaysWhyItDidNotCount() {
        final long now = 1_000_000;
        final String counted = EventText.describe(new EventText.Note(EventText.Result.COUNTED, 84, 74,
                List.of(EventConfig.Kind.KILL, EventConfig.Kind.RARE), now - 12_000), now);
        assertTrue(counted.contains("有計入") && counted.contains("Lv.84") && counted.contains("擊殺、擊殺稀有") && counted.contains("12 秒前"), counted);
        final String weak = EventText.describe(new EventText.Note(EventText.Result.TOO_WEAK, 20, 74, List.of(), now - 300_000), now);
        assertTrue(weak.contains("沒計入") && weak.contains("Lv.20") && weak.contains("Lv.74 以上") && weak.contains("5 分鐘前"), weak);
        final String loading = EventText.describe(new EventText.Note(EventText.Result.NOT_LOADED, 84, 74, List.of(), now), now);
        assertTrue(loading.contains("沒計入") && loading.contains("載入") && loading.contains("剛剛"), loading);
        assertEquals("剛剛", EventText.ago(4_999));
        assertEquals("5 秒前", EventText.ago(5_000));
        assertEquals("59 秒前", EventText.ago(59_999));
        assertEquals("1 分鐘前", EventText.ago(60_000));
        assertEquals("2 小時前", EventText.ago(2 * 3_600_000L + 5));
        assertEquals("剛剛", EventText.ago(-5_000), "時鐘倒退不會出現負的");
        final String warning = EventText.tooWeak("這隻怪", 20, 74);
        assertTrue(warning.contains("這隻怪 Lv.20") && warning.contains("Lv.74 以上") && warning.contains("每日委託"), warning);
    }
}
