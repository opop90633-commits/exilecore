package tw.exilecore.ladder;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;

/** 排行榜的排序規則。 */
class LadderTest {

    private static Ladder.Entry entry(final String name, final int level, final long experience, final int deaths, final int maps, final int best) {
        return new Ladder.Entry(name.hashCode(), name, "acct_" + name, "marauder", level, experience, deaths, 3600, maps, best, 0);
    }

    @Test
    void levelBoardSortsByLevelThenExperience() {
        final List<Ladder.Entry> entries = List.of(entry("a", 50, 10, 0, 0, 0), entry("b", 60, 5, 3, 2, 3), entry("c", 60, 9, 0, 1, 1));
        final List<Ladder.Entry> sorted = Ladder.sorted(entries, Ladder.Board.LEVEL);
        assertEquals(List.of("c", "b", "a"), sorted.stream().map(Ladder.Entry::name).toList());
    }

    @Test
    void mapsBoardNeedsAMapAndPrefersMoreMapsThenTier() {
        final List<Ladder.Entry> entries = List.of(entry("none", 90, 0, 0, 0, 0), entry("few", 70, 0, 0, 3, 16), entry("many", 61, 0, 0, 8, 2),
                entry("same", 80, 0, 0, 8, 5));
        final List<Ladder.Entry> sorted = Ladder.sorted(entries, Ladder.Board.MAPS);
        assertEquals(List.of("same", "many", "few"), sorted.stream().map(Ladder.Entry::name).toList(), "沒打過地圖的不上榜；張數同的看最高階");
    }

    @Test
    void deathlessBoardNeedsLevelAndSortsByDeaths() {
        final List<Ladder.Entry> entries = List.of(entry("lowbie", 10, 0, 0, 0, 0), entry("died", 80, 0, 2, 0, 0), entry("clean", 45, 0, 0, 0, 0),
                entry("cleaner", 70, 0, 0, 0, 0));
        final List<Ladder.Entry> sorted = Ladder.sorted(entries, Ladder.Board.DEATHLESS);
        assertEquals(List.of("cleaner", "clean", "died"), sorted.stream().map(Ladder.Entry::name).toList(), "30 級以下不上；死亡少的在前，同死亡看等級");
    }

    @Test
    void parsingAndPlaytime() {
        assertEquals(Ladder.Board.MAPS, Ladder.Board.parse("maps"));
        assertEquals(Ladder.Board.DEATHLESS, Ladder.Board.parse("不死"));
        assertEquals(Ladder.Board.LEVEL, Ladder.Board.parse("whatever"));
        assertEquals("3 小時 20 分", Ladder.playtime(3 * 3600 + 20 * 60));
        assertEquals("5 分", Ladder.playtime(5 * 60 + 30));
        assertEquals("重甲鬥士", entry("x", 1, 0, 0, 0, 0).className());
    }
}
