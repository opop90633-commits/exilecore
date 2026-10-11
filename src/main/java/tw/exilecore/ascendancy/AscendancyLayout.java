package tw.exilecore.ascendancy;

import org.bukkit.Material;
import org.bukkit.inventory.ItemStack;
import tw.exilecore.gui.Menu;

import java.util.ArrayList;
import java.util.List;

/**
 * 昇華天賦樹的畫法（2026-10，預覽與配點共用）：每條分支一列，最左邊是這條分支的標籤（整條在做什麼、每個天賦的效果），
 * 天賦之間用鏈子連起來（配了的是發光的銅鏈）。以前連線用灰色玻璃，跟背景分不出來，天賦看起來像散在畫面上。
 */
final class AscendancyLayout {

    /** 分支裡第幾個天賦放在第幾欄（中間隔一格放連線）。第 0 欄是分支的標籤，第 1 欄是標籤到第一個天賦的連線。 */
    static final int[] NODE_COLUMNS = {2, 4, 6, 8};
    /** 分支從第幾排開始（第 0 排是分頁或標題）。 */
    static final int FIRST_ROW = 1;

    private AscendancyLayout() {
    }

    /** 兩個天賦之間的連線：配了＝發光的銅鏈，還沒配＝鐵鏈。 */
    static ItemStack link(final boolean lit) {
        final ItemStack stack = Menu.icon(lit ? Material.COPPER_CHAIN : Material.IRON_CHAIN, lit ? "<green>━ 已連通 ━" : "<dark_gray>━ 往右接下一個 ━");
        return lit ? Menu.glowing(stack) : stack;
    }

    /**
     * 一條分支的標籤（那一排的最左邊）：「分支 n」＋這條分支每個天賦依序的名稱、點數、效果，一眼看完整條在做什麼。
     *
     * @param allocated 已經配了幾個（預覽別人的是 0）
     */
    static ItemStack branchLabel(final AscendancyConfig.Ascendancy ascendancy, final int index, final List<AscendancyConfig.Node> branch,
                                 final int allocated) {
        final List<String> lore = new ArrayList<>();
        lore.add("<gray>從左到右一個接一個配：要先配前一個，才能配下一個");
        lore.add("");
        int cost = 0;
        for (int i = 0; i < branch.size() && i < NODE_COLUMNS.length; i++) {
            final AscendancyConfig.Node node = branch.get(i);
            cost += node.cost();
            lore.add((i < allocated ? "<green>✔ " : "<white>" + (i + 1) + ". ") + "<" + ascendancy.color() + ">"
                    + AscendancyService.escape(node.name()) + " <dark_gray>（" + node.cost() + " 點）");
            for (final String line : StatLines.describeAll(node.stats())) {
                lore.add("<gray>　　" + AscendancyService.escape(line));
            }
        }
        lore.add("");
        lore.add("<dark_gray>整條 " + cost + " 點" + (allocated > 0 ? "，已配 " + allocated + " 個" : ""));
        final String first = branch.isEmpty() ? "" : "：" + AscendancyService.escape(branch.getFirst().name()) + " →";
        final ItemStack stack = Menu.icon(Material.PAPER, "<#f5b942>【分支 " + (index + 1) + "】" + "<" + ascendancy.color() + ">" + first, lore);
        return allocated > 0 ? Menu.glowing(stack) : stack;
    }

    /** 天賦在分支裡的位置說明（「第 2 個，接在『鐵壁』後面」）。 */
    static String position(final List<AscendancyConfig.Node> branch, final int i) {
        return i == 0 ? "分支的第 1 個（從這裡開始配）"
                : "第 " + (i + 1) + " 個（要先配「" + AscendancyService.escape(branch.get(i - 1).name()) + "」）";
    }
}
