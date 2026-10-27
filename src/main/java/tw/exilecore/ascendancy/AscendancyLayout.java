package tw.exilecore.ascendancy;

import org.bukkit.Material;
import org.bukkit.inventory.ItemStack;
import tw.exilecore.gui.Menu;
import tw.exilecore.skill.SkillDefinition;
import tw.exilecore.stats.KeywordText;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Function;
import java.util.function.Predicate;

/**
 * 昇華天賦樹的畫法（預覽與配點共用）。
 * <p>
 * 2026-10-26 照 PoE2 的昇華樹改版：一個天賦可以分岔成好幾個，所以不再是「每條分支一排」，而是一棵往右長的樹——
 * 第 0、2、4、6、8 欄是離起點 1～5 步的天賦，中間的欄放連線（━ 同一排、┗ 從上一排分岔下來、┃ 經過）；
 * 分岔出去的天賦往下多一排，樹比 4 排高的時候用下面的 ▲ ▼ 上下捲動。
 * 這次之前每條分支一排、最左邊是分支的標籤（最多 4 條、每條 4 個）。
 */
final class AscendancyLayout {

    /** 樹從第幾排開始畫（第 0 排是分頁或標題）。 */
    static final int FIRST_ROW = 1;
    /** 一次看得到幾排。 */
    static final int VISIBLE_ROWS = 4;
    /** ▲ ▼ 一次捲幾排（留一排重疊，看得出接在哪裡）。 */
    static final int STEP = 3;
    /** 捲動的按鈕（下面一排）。 */
    static final int SLOT_UP = 48;
    static final int SLOT_DOWN = 50;
    /** 說明行最後面寫著這個的是還沒做出來的機制（灰色）。 */
    static final String PENDING = "（尚未生效）";
    /** 2026-10-27：關鍵字、技能說明一行最多幾個字（太長折行）。 */
    static final int WRAP = 30;

    private AscendancyLayout() {
    }

    /**
     * 畫好的一頁。
     *
     * @param items 格子 → 圖示（天賦與連線）
     * @param nodes 格子 → 天賦（點擊用）
     * @param rows  整棵樹有幾排
     */
    record Page(Map<Integer, ItemStack> items, Map<Integer, AscendancyConfig.Node> nodes, int rows) {
    }

    /** 天賦在第幾欄（depth 0 = 第 0 欄）。 */
    static int column(final int depth) {
        return depth * 2;
    }

    /** 捲動的位置夾在範圍內。 */
    static int clampOffset(final int offset, final int rows) {
        return Math.max(0, Math.min(offset, Math.max(0, rows - VISIBLE_ROWS)));
    }

    /**
     * 畫從第 offset 排開始的 4 排。
     *
     * @param icon 天賦的圖示
     * @param lit  這個天賦配了沒（連線發光）
     */
    static Page page(final AscendancyConfig.Ascendancy ascendancy, final int offset, final Function<AscendancyConfig.Node, ItemStack> icon,
                     final Predicate<AscendancyConfig.Node> lit) {
        final List<AscendancyConfig.Cell> cells = ascendancy.layout();
        final Map<String, AscendancyConfig.Cell> byId = new HashMap<>();
        int rows = 0;
        for (final AscendancyConfig.Cell cell : cells) {
            byId.put(cell.node().id(), cell);
            rows = Math.max(rows, cell.row() + 1);
        }
        final Map<Integer, ItemStack> items = new LinkedHashMap<>();
        final Map<Integer, AscendancyConfig.Node> nodes = new LinkedHashMap<>();
        for (final AscendancyConfig.Cell cell : cells) {
            final int slot = slot(cell.row(), column(cell.depth()), offset);
            if (slot >= 0) {
                items.put(slot, icon.apply(cell.node()));
                nodes.put(slot, cell.node());
            }
            // 往右的連線：第一個後續在同一排（━），其他的往下（┗），中間經過的排畫 ┃
            final List<AscendancyConfig.Cell> children = new ArrayList<>();
            for (final AscendancyConfig.Node child : ascendancy.children(cell.node().id())) {
                final AscendancyConfig.Cell childCell = byId.get(child.id());
                if (childCell != null && childCell.depth() == cell.depth() + 1) {
                    children.add(childCell);
                }
            }
            if (children.isEmpty()) {
                continue;
            }
            final int column = column(cell.depth()) + 1;
            final int last = children.getLast().row();
            for (int row = cell.row(); row <= last; row++) {
                final int linkSlot = slot(row, column, offset);
                if (linkSlot < 0) {
                    continue;
                }
                AscendancyConfig.Cell here = null;
                boolean later = false;
                for (final AscendancyConfig.Cell child : children) {
                    if (child.row() == row) {
                        here = child;
                    }
                    if (child.row() >= row && lit.test(child.node())) {
                        later = true;
                    }
                }
                if (here != null) {
                    items.put(linkSlot, link(ascendancy, cell.node(), here.node(), row == cell.row(), row < last, lit.test(here.node())));
                } else {
                    items.put(linkSlot, pass(cell.node(), later));
                }
            }
        }
        return new Page(items, nodes, rows);
    }

    /** 第 row 排第 column 欄在畫面上的格子（不在這一頁 = -1）。 */
    private static int slot(final int row, final int column, final int offset) {
        final int visible = row - offset;
        if (visible < 0 || visible >= VISIBLE_ROWS) {
            return -1;
        }
        return (FIRST_ROW + visible) * 9 + column;
    }

    /** 兩個天賦之間的連線：配了＝發光的銅鏈，還沒配＝鐵鏈。 */
    private static ItemStack link(final AscendancyConfig.Ascendancy ascendancy, final AscendancyConfig.Node from, final AscendancyConfig.Node to,
                                  final boolean sameRow, final boolean more, final boolean lit) {
        final String shape = sameRow ? (more ? "┳" : "━") : (more ? "┣" : "┗");
        final String what = to.option() ? "選項" : "天賦";
        final ItemStack stack = Menu.icon(lit ? Material.COPPER_CHAIN : Material.IRON_CHAIN,
                (lit ? "<green>" : "<dark_gray>") + shape + " " + (lit ? "已連通" : "往右接下一個" + what),
                "<gray>" + sources(ascendancy, from, to) + "→「" + AscendancyService.escape(to.name()) + "」");
        return lit ? Menu.glowing(stack) : stack;
    }

    /**
     * 連線提示的起點：下一個天賦接在好幾個天賦後面（例：儀祭者的展露之指接在三個負面天賦後面）時全部列出來並註明「任一」，
     * 免得看起來只能從其中一條過去。
     */
    static String sources(final AscendancyConfig.Ascendancy ascendancy, final AscendancyConfig.Node from, final AscendancyConfig.Node to) {
        final List<String> names = new ArrayList<>();
        for (final String id : to.requires()) {
            final AscendancyConfig.Node parent = ascendancy.node(id);
            if (parent != null && !parent.option()) {
                names.add(AscendancyService.escape(parent.name()));
            }
        }
        if (names.size() <= 1) {
            return "「" + AscendancyService.escape(from.name()) + "」";
        }
        return "「" + String.join("／", names) + "」（任一）";
    }

    /** 經過的連線（分岔往下接到更下面的那一排）。 */
    private static ItemStack pass(final AscendancyConfig.Node from, final boolean lit) {
        final ItemStack stack = Menu.icon(lit ? Material.COPPER_CHAIN : Material.IRON_CHAIN, (lit ? "<green>" : "<dark_gray>") + "┃",
                "<gray>從「" + AscendancyService.escape(from.name()) + "」往下分岔");
        return lit ? Menu.glowing(stack) : stack;
    }

    /** ▲ ▼ 捲動按鈕（不用捲 = null）。 */
    static ItemStack scroll(final boolean up, final int offset, final int rows) {
        if (rows <= VISIBLE_ROWS) {
            return null;
        }
        final boolean can = up ? offset > 0 : offset + VISIBLE_ROWS < rows;
        if (!can) {
            return null;
        }
        return Menu.icon(Material.SPECTRAL_ARROW, "<white>" + (up ? "▲ 往上看" : "▼ 往下看"),
                "<gray>這棵樹有 " + rows + " 排，現在看第 " + (offset + 1) + "～" + Math.min(rows, offset + VISIBLE_ROWS) + " 排",
                "", "<yellow>左鍵：" + (up ? "往上" : "往下") + "捲");
    }

    /**
     * 天賦的說明（效果、怎麼接）；配點、退點的提示由選單自己加。
     *
     * @param bright 效果寫成白色（可以配、已配）還是灰色
     */
    static List<String> describe(final AscendancyConfig.Ascendancy ascendancy, final AscendancyConfig.Node node, final boolean bright) {
        return describe(ascendancy, node, bright, null);
    }

    /**
     * 同上；skills 查得到賦予的技能時，附上技能的說明與「Shift＋點擊看技能詳細」。
     * 2026-10-27 照站長：補充說明（note）這次之前寫了兩次（效果下面一次、選項下面又一次斜體），改成只寫一次；
     * 只寫了關鍵字的補一行關鍵字說明（KeywordText）。
     */
    static List<String> describe(final AscendancyConfig.Ascendancy ascendancy, final AscendancyConfig.Node node, final boolean bright,
                                 final Function<String, SkillDefinition> skills) {
        final List<String> lore = new ArrayList<>();
        final List<SkillDefinition> granted = granted(node, skills);
        final List<SkillDefinition> described = new ArrayList<>();
        for (final String text : node.display()) {
            final boolean pending = text.endsWith(PENDING);
            for (final String line : text.split("\n")) {
                lore.add((pending ? "<dark_gray>" : bright ? "<white>" : "<gray>") + AscendancyService.escape(line));
            }
            // 2026-10-27 照站長：「提供技能：X」下面直接接技能的說明
            for (final SkillDefinition skill : granted) {
                if (text.equals("提供技能：" + skill.name()) && !described.contains(skill)) {
                    described.add(skill);
                    skillText(lore, skill);
                }
            }
        }
        for (final SkillDefinition skill : granted) {
            if (!described.contains(skill)) { // 說明行沒寫技能名稱的（名稱不一樣）補一行
                lore.add("<#e7c56b>提供技能：" + AscendancyService.escape(skill.name()));
                skillText(lore, skill);
            }
        }
        if (node.shownNote() != null) {
            lore.add("<dark_gray>" + AscendancyService.escape(node.shownNote())); // 2026-10-26：做法跟原作不同的地方
        }
        for (final String explanation : node.explanations()) {
            for (final String line : KeywordText.wrap(explanation, WRAP)) {
                lore.add("<#9fb4c7>" + AscendancyService.escape(line));
            }
        }
        if (!granted.isEmpty()) {
            lore.add("<yellow>Shift＋點擊：看技能詳細（傷害、施法時間、冷卻、消耗…）");
        }
        if (node.choice()) {
            final List<String> names = new ArrayList<>();
            for (final AscendancyConfig.Node option : ascendancy.options(node.id())) {
                names.add(AscendancyService.escape(option.name()));
            }
            lore.add("<#f5b942>配了之後從右邊的選項選一個（不再花點數）：");
            lore.add("<#f5b942>　" + String.join("／", names));
        }
        lore.add("");
        lore.add("<gray>" + (node.option() ? "選項（不花點數）" : node.granted() ? "自帶（不花點數，選了這個昇華就配好）" : node.cost() == 0 ? "不花點數" : node.cost() + " 點") // 2026-10-27：聖潔一統免費；占血術自帶
                + "<dark_gray>　" + position(ascendancy, node));
        return lore;
    }

    private static void skillText(final List<String> lore, final SkillDefinition skill) {
        if (skill.description() != null && !skill.description().isBlank()) {
            for (final String line : KeywordText.wrap(tw.exilecore.charge.ChargeText.expand(skill.description()), WRAP)) {
                lore.add("<gray>　" + AscendancyService.escape(line));
            }
        }
    }

    /** 2026-10-27：天賦賦予、查得到的技能（skills 是 null 回傳空的）。 */
    static List<SkillDefinition> granted(final AscendancyConfig.Node node, final Function<String, SkillDefinition> skills) {
        final List<SkillDefinition> out = new ArrayList<>();
        if (skills == null) {
            return out;
        }
        for (final String id : node.grantedSkills()) {
            final SkillDefinition skill = skills.apply(id);
            if (skill != null) {
                out.add(skill);
            }
        }
        return out;
    }

    /** 天賦接在哪裡（「直接接在起點」「接在『鐵壁』後面」「『植入寶石』的選項」）。 */
    static String position(final AscendancyConfig.Ascendancy ascendancy, final AscendancyConfig.Node node) {
        if (node.option()) {
            final AscendancyConfig.Node choice = ascendancy.node(node.optionOf());
            return "「" + AscendancyService.escape(choice == null ? node.optionOf() : choice.name()) + "」的選項：只能選一個";
        }
        if (!node.unlock().isEmpty()) {
            // 2026-10-27 照 PoE2（聖潔一統）：這幾個都配了才能配
            final List<String> names = new ArrayList<>();
            for (final String id : node.unlock()) {
                final AscendancyConfig.Node need = ascendancy.node(id);
                names.add("「" + AscendancyService.escape(need == null ? id : need.name()) + "」");
            }
            return "要先配" + String.join("、", names);
        }
        if (node.root()) {
            return "直接接在起點";
        }
        final List<String> names = new ArrayList<>();
        for (final String id : node.requires()) {
            final AscendancyConfig.Node before = ascendancy.node(id);
            names.add("「" + AscendancyService.escape(before == null ? id : before.name()) + "」");
        }
        return "接在" + String.join("或", names) + "後面";
    }
}
