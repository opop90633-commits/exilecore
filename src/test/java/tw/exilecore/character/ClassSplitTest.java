package tw.exilecore.character;

import org.junit.jupiter.api.Test;
import tw.exilecore.passive.TreeVersion;

import java.util.HashMap;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** 混沌術士分成元素法師與混沌術士：原本的混沌術士選一次；天賦樹改版全部退點。 */
class ClassSplitTest {

    @Test
    void onlyOldWitchesHaveToChoose() {
        final Map<String, String> progress = new HashMap<>();
        assertTrue(ClassSplit.pending(CharacterClass.WITCH, progress), "原本的混沌術士：還沒選");
        for (final CharacterClass other : CharacterClass.values()) {
            if (other != CharacterClass.WITCH) {
                assertFalse(ClassSplit.pending(other, progress), other + " 不用選");
            }
        }
        progress.put(ClassSplit.PROGRESS, "witch");
        assertFalse(ClassSplit.pending(CharacterClass.WITCH, progress), "選過了（新建的混沌術士一開始就寫好）");
        assertFalse(ClassSplit.pending(CharacterClass.WITCH, null));
    }

    @Test
    void onlySorceressOrWitchCanBeChosen() {
        assertTrue(ClassSplit.allowed(CharacterClass.SORCERESS));
        assertTrue(ClassSplit.allowed(CharacterClass.WITCH));
        assertFalse(ClassSplit.allowed(CharacterClass.MARAUDER));
        assertFalse(ClassSplit.allowed(CharacterClass.MONK));
    }

    @Test
    void ascendancyStaysOnlyWithItsClass() {
        // 星火術士、虛衣祭司給了元素法師：選元素法師留著、選混沌術士退掉
        assertFalse(ClassSplit.dropsAscendancy("sorceress", CharacterClass.SORCERESS));
        assertTrue(ClassSplit.dropsAscendancy("sorceress", CharacterClass.WITCH));
        assertFalse(ClassSplit.dropsAscendancy("witch", CharacterClass.WITCH));
        assertFalse(ClassSplit.dropsAscendancy(null, CharacterClass.WITCH), "還沒選昇華職業");
    }

    @Test
    void oldTreesAreRefunded() {
        assertFalse(TreeVersion.outdated(1, null), "沒記過版本的角色是第 1 版配的");
        assertTrue(TreeVersion.outdated(2, null), "第 2 版（七個職業）：舊角色全部退點");
        assertTrue(TreeVersion.outdated(2, "1"));
        assertFalse(TreeVersion.outdated(2, "2"));
        assertFalse(TreeVersion.outdated(2, " 2 "));
        assertTrue(TreeVersion.outdated(3, "2"));
        assertTrue(TreeVersion.outdated(2, ""), "空的當作沒記過");
    }
}
