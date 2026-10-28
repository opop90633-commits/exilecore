package tw.exilecore.character;

import org.junit.jupiter.api.Test;
import tw.exilecore.ascendancy.AscBuild;
import tw.exilecore.ascendancy.Ascension;
import tw.exilecore.item.AccountBound;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** 2026-10-28 職業轉職卷：能換哪些、不能換的原因、換的時候清掉哪些進度、綁定帳號擋哪些地方。 */
class ClassChangeTest {

    @Test
    void choicesAreEveryOtherClass() {
        for (final CharacterClass current : CharacterClass.values()) {
            final List<CharacterClass> choices = ClassChange.choices(current);
            assertEquals(CharacterClass.values().length - 1, choices.size());
            assertFalse(choices.contains(current), current + " 不能換成自己");
        }
    }

    @Test
    void eligibility() {
        assertNotNull(ClassChange.problem(null, CharacterClass.MONK), "沒有角色");
        assertNotNull(ClassChange.problem(CharacterClass.MONK, null), "沒有這個職業");
        assertNotNull(ClassChange.problem(CharacterClass.MONK, CharacterClass.MONK), "同一個職業");
        assertNull(ClassChange.problem(CharacterClass.MONK, CharacterClass.WITCH));
    }

    @Test
    void progressKeysMatchTheRealOnes() {
        // 寫死的字串跟昇華模組的常數要一樣（不然換職業清不到）
        assertEquals(Ascension.CHOSEN, ClassChange.ASC_CHOSEN);
        assertEquals(Ascension.NODES, ClassChange.ASC_NODES);
        assertEquals(AscBuild.MERIDIANS, ClassChange.ASC_MERIDIANS);
        assertEquals(PlayerState.TREE_SET_KEY, ClassChange.TREE_SET_ACTIVE);
    }

    @Test
    void classSwitchClearsClassSpecificStateOnly() {
        final Map<String, String> progress = new HashMap<>();
        progress.put(Ascension.CHOSEN, "titan");
        progress.put(Ascension.NODES, "a,b,c");
        progress.put(AscBuild.MERIDIANS, "rune_fire||rune_cold||");
        progress.put(Ascension.WEEK, "2026-W43|2");
        progress.put("trial.sekhema", "1700000000");
        progress.put("attr.n123", "str");
        progress.put("treeset.1.0", "code");
        progress.put("treeset.1.1", "more");
        progress.put(PlayerState.TREE_SET_KEY, "1");
        progress.put(ClassSplit.PROGRESS, "witch");
        progress.put("quest.act1", "done");
        final List<String> removed = ClassChange.progressToRemove(progress);
        assertEquals(List.of(Ascension.CHOSEN, Ascension.NODES, AscBuild.MERIDIANS, "attr.n123", "treeset.1.0", "treeset.1.1"), removed);
        // 試煉留著 = 昇華點拿回來；任務、現在用哪一組天賦不動
        assertFalse(removed.contains("trial.sekhema"));
        assertFalse(removed.contains(Ascension.WEEK));
        assertFalse(removed.contains(PlayerState.TREE_SET_KEY));
        assertFalse(removed.contains("quest.act1"));
        assertTrue(ClassChange.progressToRemove(null).isEmpty());
    }

    @Test
    void meridianRunesAreReturned() {
        final Map<String, String> progress = new HashMap<>();
        assertTrue(ClassChange.meridianRunes(progress).isEmpty());
        progress.put(AscBuild.MERIDIANS, "rune_fire||rune_cold||rune_fire");
        assertEquals(List.of("rune_fire", "rune_cold", "rune_fire"), ClassChange.meridianRunes(progress));
    }

    @Test
    void confirmTextStatesTheRules() {
        final String text = String.join("\n", ClassChange.rules(CharacterClass.RANGER));
        assertTrue(text.contains("天賦點全部退回"));
        assertTrue(text.contains("昇華點全部還你"));
        assertTrue(text.contains("珠寶"));
        assertTrue(text.contains("等級、經驗、裝備、技能、通貨"));
        assertTrue(text.contains("弓箭手"));
    }

    @Test
    void scrollIsAccountBound() {
        assertTrue(String.join("\n", ClassScroll.lore()).contains("綁定帳號"));
        for (final AccountBound.Channel channel : AccountBound.Channel.values()) {
            assertNotNull(AccountBound.problem(true, channel), channel + " 要擋");
            assertNull(AccountBound.problem(false, channel), channel + " 一般物品不擋");
        }
    }
}
