package tw.exilecore.character;

import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.minimessage.MiniMessage;
import org.junit.jupiter.api.Test;
import tw.exilecore.skill.SkillService;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertTrue;

/** 2026-11-11 照站長「Buff 太多會斷線」：Tab 的增益那段文字巢狀不能越疊越深（客戶端解不開 tab_list 就斷線）。 */
class TabListBuffsTest {

    private static int depth(final Component component) {
        int max = 0;
        for (final Component child : component.children()) {
            max = Math.max(max, depth(child));
        }
        return max + 1;
    }

    @Test
    void manyBuffsStayShallow() {
        final List<SkillService.BuffTimer> buffs = new ArrayList<>();
        for (int i = 0; i < 300; i++) {
            buffs.add(new SkillService.BuffTimer("增益" + i, 100 + i, false));
        }
        final Component parsed = MiniMessage.miniMessage().deserialize(TabList.render(buffs));
        assertTrue(depth(parsed) < 16, "巢狀層數：" + depth(parsed));
        assertTrue(TabList.render(buffs).contains("還有 " + (300 - TabList.BUFFS_SHOWN) + " 個"));
    }
}
