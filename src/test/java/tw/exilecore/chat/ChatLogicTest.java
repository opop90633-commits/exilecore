package tw.exilecore.chat;

import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** 用角色名稱找人（私訊、組隊、交易）與聊天裡的物品連結。 */
class ChatLogicTest {

    private static final List<NameMatch.Candidate<String>> ONLINE = List.of(
            new NameMatch.Candidate<>("p1", "暗影刺客", "k_yyds"),
            new NameMatch.Candidate<>("p2", "Alice", "Steve"),
            new NameMatch.Candidate<>("p3", null, "Newbie"),
            new NameMatch.Candidate<>("p4", "小明", "ming01"),
            new NameMatch.Candidate<>("p5", "小明", "ming02"));

    @Test
    void findsByCharacterNameThenAccountName() {
        assertEquals("p1", NameMatch.resolve(ONLINE, "暗影刺客").found());
        assertEquals("p2", NameMatch.resolve(ONLINE, "alice").found(), "不分大小寫");
        assertEquals("p2", NameMatch.resolve(ONLINE, " Alice ").found());
        assertEquals("p1", NameMatch.resolve(ONLINE, "K_YYDS").found(), "也可以打玩家 ID");
        assertEquals("p3", NameMatch.resolve(ONLINE, "Newbie").found(), "還沒選角色：玩家 ID");
        assertTrue(NameMatch.resolve(ONLINE, "暗影").isEmpty(), "不猜開頭（私訊才不會送錯人）");
        assertTrue(NameMatch.resolve(ONLINE, "").isEmpty());
        assertTrue(NameMatch.resolve(ONLINE, null).isEmpty());
    }

    @Test
    void fullWidthLettersMatchTheirHalfWidthForm() {
        assertEquals("p2", NameMatch.resolve(ONLINE, "Ａｌｉｃｅ").found(), "全形英文字＝半形（不能拿來冒充別人）");
        assertEquals(List.of("Alice"), NameMatch.suggest(ONLINE, "ＡＬ"));
    }

    @Test
    void sameNameAsksForTheAccountName() {
        final NameMatch.Result<String> result = NameMatch.resolve(ONLINE, "小明");
        assertNull(result.found());
        assertEquals(2, result.ambiguous().size());
        assertFalse(result.isEmpty());
        assertEquals("p5", NameMatch.resolve(ONLINE, "ming02").found());
    }

    @Test
    void characterNameWinsOverSomeoneElsesAccountName() {
        final List<NameMatch.Candidate<String>> online = List.of(
                new NameMatch.Candidate<>("a", "Bob", "x1"),
                new NameMatch.Candidate<>("b", "Carol", "Bob"));
        assertEquals("a", NameMatch.resolve(online, "bob").found(), "大家看到的是角色名稱");
    }

    @Test
    void suggestionsShowWhatOthersSee() {
        assertEquals(List.of("Alice", "Newbie", "小明", "暗影刺客"), NameMatch.suggest(ONLINE, ""));
        assertEquals(List.of("Alice"), NameMatch.suggest(ONLINE, "al"));
        assertEquals(List.of("k_yyds"), NameMatch.suggest(ONLINE, "k_"), "打玩家 ID 也補得出來");
        assertEquals(List.of("小明"), NameMatch.suggest(ONLINE, "小"), "同名只列一次");
    }

    @Test
    void splitsTargetAndMessage() {
        assertArrayEquals(new String[]{"小明", "你好 嗎"}, NameMatch.splitTarget("小明 你好 嗎"));
        assertArrayEquals(new String[]{"小明", "你好"}, NameMatch.splitTarget("  小明\t 你好  "));
        assertArrayEquals(new String[]{"小明", ""}, NameMatch.splitTarget("小明"));
        assertArrayEquals(new String[]{"", ""}, NameMatch.splitTarget(null));
    }

    @Test
    void itemLinkTokens() {
        final List<ItemLinks.Part> parts = ItemLinks.parse("看 [i] 跟 [I3]、[物品]、[Item9]");
        assertEquals(List.of(
                new ItemLinks.Text("看 "),
                new ItemLinks.Link("[i]", ItemLinks.MAIN_HAND),
                new ItemLinks.Text(" 跟 "),
                new ItemLinks.Link("[I3]", 2),
                new ItemLinks.Text("、"),
                new ItemLinks.Link("[物品]", ItemLinks.MAIN_HAND),
                new ItemLinks.Text("、[Item9]")), parts, "最多三個，多的照原樣");
        assertEquals(Set.of(ItemLinks.MAIN_HAND, 2), ItemLinks.slots(parts));
        assertTrue(ItemLinks.hasLinks(parts));
    }

    @Test
    void textWithoutLinksStaysOnePart() {
        assertEquals(List.of(new ItemLinks.Text("[i0] [ii] [i10] i")), ItemLinks.parse("[i0] [ii] [i10] i"));
        assertFalse(ItemLinks.hasLinks(ItemLinks.parse("hello")));
        assertEquals(List.of(new ItemLinks.Text("")), ItemLinks.parse(""));
        assertEquals(List.of(new ItemLinks.Link("[i]", ItemLinks.MAIN_HAND)), ItemLinks.parse("[i]"));
    }
}
