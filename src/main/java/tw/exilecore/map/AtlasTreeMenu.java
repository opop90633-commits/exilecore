package tw.exilecore.map;

import org.bukkit.Color;
import org.bukkit.Material;
import org.bukkit.NamespacedKey;
import org.bukkit.Sound;
import org.bukkit.entity.Player;
import org.bukkit.event.inventory.ClickType;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.components.CustomModelDataComponent;
import tw.exilecore.ExileCore;
import tw.exilecore.gui.Menu;
import tw.exilecore.item.ItemRenderer;
import tw.exilecore.web.UiPack;

import java.util.ArrayList;
import java.util.List;

/**
 * 星圖天賦（地圖天賦樹，2026-10-21 改成一棵樹）：上面 5 排是 9×5 的視窗，一格一個節點（方格座標，上下左右相鄰＝相連），
 * 中心的星核是起點；最下面一排是回星圖、上下左右捲動、回中心、天賦點（含說明）、全部退回、關閉。
 * <p>
 * 左鍵點（點不到的會自動把路上的一起點，點數夠的話）、右鍵退（免費，連不回中心的一起退）。
 * 節點的顏色（沒有資源包也看得出來）：綠＝已點、黃＝現在可以點、橘＝可以點但天賦點不夠、灰＝還連不到；
 * 大天賦、核心用自己的圖示，核心的名字是紫色。
 */
public final class AtlasTreeMenu extends Menu {

    private static final int VIEW_WIDTH = 9;
    private static final int VIEW_HEIGHT = 5;
    private static final int SLOT_ATLAS = 45;
    private static final int SLOT_UP = 46;
    private static final int SLOT_DOWN = 47;
    private static final int SLOT_LEFT = 48;
    private static final int SLOT_RIGHT = 49;
    private static final int SLOT_HOME = 50;
    private static final int SLOT_POINTS = 51;
    private static final int SLOT_RESET = 52;
    private static final int SLOT_CLOSE = 53;
    /** 一次捲幾格（Shift 一格）。 */
    private static final int SCROLL = 3;
    /** 全部退回要在幾毫秒內點第二下。 */
    private static final long RESET_CONFIRM_MILLIS = 5000;

    private enum State { TAKEN, AVAILABLE, POOR, LOCKED }

    // 2026-10-21：有套用介面資源包的玩家看到星盤（跟天賦樹同一套：星空背景、上下左右的連線、節點、發光；UiPack.TREE_CELL）
    private static final NamespacedKey TREE_CELL = NamespacedKey.fromString(UiPack.TREE_CELL);
    private static final Color GOLD = Color.fromRGB(0xF5C04A);
    private static final Color EDGE_GOLD = Color.fromRGB(0xE6A82E);
    private static final Color BRIGHT = Color.fromRGB(0xE8E8F2);
    private static final Color EDGE_LIT = Color.fromRGB(0x9C9CB8);
    private static final Color DIM = Color.fromRGB(0x5E5E74);
    private static final Color EDGE_DIM = Color.fromRGB(0x33354A);
    private static final Color ORANGE = Color.fromRGB(0xFFAA55);
    private static final Color CYAN = Color.fromRGB(0x55FFFF);
    private static final Color WHITE = Color.fromRGB(0xFFFFFF);
    /** 往上、右、下、左（跟 UiPack.Cell 的旗標同順序；畫面的 y 往下變大）。 */
    private static final int[][] SIDES = {{0, -1}, {1, 0}, {0, 1}, {-1, 0}};

    private final MapService service;
    private int viewX;
    private int viewY;
    /** 第一次點「全部退回」的時間（0 = 沒有在等確認）。 */
    private long resetArmedAt;

    public AtlasTreeMenu(final ExileCore plugin, final Player player, final MapService service) {
        super(plugin, player, 6, "<#9fd3ff>星圖天賦");
        this.service = service;
        home();
    }

    private void home() {
        centerOn(AtlasTree.ROOT_X, AtlasTree.ROOT_Y);
    }

    private void centerOn(final int x, final int y) {
        viewX = x - VIEW_WIDTH / 2;
        viewY = y - VIEW_HEIGHT / 2;
        clampView();
    }

    private void clampView() {
        final AtlasTree tree = service.tree();
        viewX = Math.max(tree.minX() - 1, Math.min(tree.maxX() + 2 - VIEW_WIDTH, viewX));
        viewY = Math.max(tree.minY() - 1, Math.min(tree.maxY() + 2 - VIEW_HEIGHT, viewY));
    }

    @Override
    protected void draw() {
        final AtlasTree tree = service.tree();
        final AtlasTree.Earned earned = service.atlasEarned(player);
        final List<String> allocated = service.atlasAllocated(player);
        final int left = Math.max(0, earned.total() - tree.spent(allocated));
        final boolean pack = plugin.web() != null && plugin.web().hasPack(player);
        for (int row = 0; row < VIEW_HEIGHT; row++) {
            for (int column = 0; column < VIEW_WIDTH; column++) {
                final int x = viewX + column;
                final int y = viewY + row;
                final int slot = row * 9 + column;
                if (x == AtlasTree.ROOT_X && y == AtlasTree.ROOT_Y) {
                    set(slot, pack ? cell(tree, x, y, null, allocated, left, "<#e8e8f2>星核", List.of("<gray>起點：從這裡往外點", "<dark_gray>(0, 0)"))
                            : glowing(icon(Material.NETHER_STAR, "<#e8e8f2>星核", "<gray>起點：從這裡往外點", "<dark_gray>(0, 0)")));
                    continue;
                }
                final AtlasTree.Node node = tree.at(x, y);
                if (pack) {
                    set(slot, node == null ? cell(tree, x, y, null, allocated, left, null, List.of()) : node(tree, node, allocated, left, true));
                } else {
                    set(slot, node == null ? empty(x, y) : node(tree, node, allocated, left, false));
                }
            }
        }
        set(SLOT_ATLAS, uiIcon("nav_back", Material.FILLED_MAP, "<#f5b942>← 星圖", "<gray>左鍵：回到星圖（地圖的解鎖進度）",
                "<#bcd4ff>右鍵：星圖天賦組（月卡）<gray>：第 " + (AtlasSetMenu.activeSet(plugin.players().get(player)) + 1) + " 組使用中"));
        set(SLOT_UP, arrow("arrow_up", "↑ 往上"));
        set(SLOT_DOWN, arrow("arrow_down", "↓ 往下"));
        set(SLOT_LEFT, arrow("arrow_left", "← 往左"));
        set(SLOT_RIGHT, arrow("arrow_right", "→ 往右"));
        set(SLOT_HOME, icon(Material.NETHER_STAR, "<white>回到中心", "<gray>視窗現在在 (" + viewX + ", " + viewY + ")"));
        set(SLOT_POINTS, points(tree, earned, allocated, left));
        set(SLOT_RESET, reset(allocated));
        set(SLOT_CLOSE, closeButton());
    }

    /** 捲動的箭頭（2026-10-21：有資源包的人看到跟天賦樹一樣的箭頭圖示）。 */
    private ItemStack arrow(final String icon, final String name) {
        return uiIcon(icon, Material.ARROW, "<white>" + name, "<gray>捲 " + SCROLL + " 格（Shift 一格）");
    }

    /** 空格：深色玻璃，背景照座標有一點點變化（看得出在捲動）。 */
    private static ItemStack empty(final int x, final int y) {
        final int shade = Math.floorMod(x * 7 + y * 13 + x * y, 4);
        final Material material = shade == 0 ? Material.GRAY_STAINED_GLASS_PANE : Material.BLACK_STAINED_GLASS_PANE;
        return icon(material, " ");
    }

    private ItemStack node(final AtlasTree tree, final AtlasTree.Node node, final List<String> allocated, final int left, final boolean pack) {
        final State state = state(tree, node, allocated, left);
        final AtlasTree.Trunk trunk = tree.trunk(node.trunk());
        final String trunkColor = trunk == null ? "#f5b942" : trunk.color();
        final List<String> lore = new ArrayList<>();
        if (trunk != null && !"core".equals(trunk.id())) {
            lore.add("<" + trunkColor + ">" + ItemRenderer.escape(trunk.name()));
        }
        for (final AtlasTree.Mod mod : node.mods()) {
            lore.add("<white>" + ItemRenderer.escape(AtlasTree.describe(mod.stat(), mod.value())));
        }
        for (final AtlasTree.Mod mod : node.drawbacks()) {
            lore.add("<red>" + ItemRenderer.escape(AtlasTree.describe(mod.stat(), mod.value())));
        }
        lore.add("<dark_gray>" + (node.keystone() ? "核心・" : node.notable() ? "大天賦・" : "") + node.cost() + " 點　(" + node.x() + ", " + node.y() + ")");
        lore.add("");
        switch (state) {
            case TAKEN -> {
                final int deeper = tree.refund(allocated, node.id()).size() - 1;
                lore.add("<green>✔ 已點");
                if (hasStat(node, "atlas.chosen_event")) {
                    lore.add("<yellow>選的事件：" + MapEventConfig.sourceName(MapEventConfig.uniqueSource(service.faithKind(player))) + "<gray>（左鍵換下一種）");
                }
                lore.add("<gray>右鍵退回（免費" + (deeper > 0 ? "，連同連不回中心的 " + deeper + " 個" : "") + "）");
            }
            case AVAILABLE -> lore.add("<yellow>左鍵點<gray>（要 " + node.cost() + " 點，你還有 " + left + " 點）");
            case POOR -> {
                lore.add("<red>天賦點不夠：要 " + node.cost() + " 點，你還有 " + left + " 點");
                lore.add("<dark_gray>完成新的地圖、終局、極境，或打到更高階拿天賦點");
            }
            case LOCKED -> {
                final List<AtlasTree.Node> path = tree.pathTo(allocated, node);
                if (path.isEmpty()) {
                    lore.add("<gray>連不到中心");
                } else {
                    int cost = 0;
                    for (final AtlasTree.Node step : path) {
                        cost += step.cost();
                    }
                    lore.add("<gray>要先點到旁邊：左鍵把路上的 " + path.size() + " 個一起點（共 " + cost + " 點，你還有 " + left + " 點）");
                }
            }
        }
        final String color = switch (state) {
            case TAKEN -> "<green>";
            case AVAILABLE -> "<yellow>";
            case POOR -> "<#ffaa55>";
            case LOCKED -> "<gray>";
        };
        final String name = color + (state == State.TAKEN ? "✔ " : "") + ItemRenderer.escape(node.name())
                + (node.keystone() ? " <#c48bff>✦" : node.notable() ? " <" + trunkColor + ">★" : "");
        if (pack) {
            return cell(tree, node.x(), node.y(), node, allocated, left, name, lore);
        }
        final Material material;
        if (node.notable() || node.keystone()) {
            material = material(node.icon().isBlank() ? (trunk == null ? "" : trunk.icon()) : node.icon(), Material.NETHER_STAR);
        } else {
            material = switch (state) {
                case TAKEN -> Material.LIME_STAINED_GLASS_PANE;
                case AVAILABLE -> Material.YELLOW_STAINED_GLASS_PANE;
                case POOR -> Material.ORANGE_STAINED_GLASS_PANE;
                case LOCKED -> Material.LIGHT_GRAY_STAINED_GLASS_PANE;
            };
        }
        final ItemStack icon = icon(material, name, lore);
        return state == State.TAKEN ? glowing(icon) : icon;
    }

    /** 這個座標是不是點了（星核一律算點了）。 */
    private static boolean takenAt(final AtlasTree tree, final int x, final int y, final List<String> allocated) {
        if (x == AtlasTree.ROOT_X && y == AtlasTree.ROOT_Y) {
            return true;
        }
        final AtlasTree.Node node = tree.at(x, y);
        return node != null && allocated.contains(node.id());
    }

    private static boolean existsAt(final AtlasTree tree, final int x, final int y) {
        return x == AtlasTree.ROOT_X && y == AtlasTree.ROOT_Y || tree.at(x, y) != null;
    }

    /**
     * 有資源包：星盤的一格（2026-10-21）。node 是 null 而且不是星核＝空格（只有背景、不顯示說明）。
     * 連線：兩邊都點了＝金色，一邊點了＝亮，都沒點＝暗；節點：點了金色＋發光、可以點亮白、點數不夠橘、連不到暗。
     */
    private ItemStack cell(final AtlasTree tree, final int x, final int y, final AtlasTree.Node node, final List<String> allocated,
                           final int left, final String name, final List<String> lore) {
        final boolean root = x == AtlasTree.ROOT_X && y == AtlasTree.ROOT_Y;
        final ItemStack stack = name == null ? new ItemStack(Material.PAPER) : icon(Material.PAPER, name, lore);
        stack.editMeta(meta -> {
            meta.setItemModel(TREE_CELL);
            if (name == null) {
                meta.setHideTooltip(true);
            }
            final CustomModelDataComponent data = meta.getCustomModelDataComponent();
            final int background = Math.floorMod(x * 7 + y * 13 + x * y, UiPack.BACKGROUNDS);
            final List<String> strings = new ArrayList<>(List.of(Integer.toString(background), "", "", ""));
            final List<Boolean> flags = new ArrayList<>(List.of(false, false, false, false));
            final List<Color> colors = new ArrayList<>(List.of(EDGE_DIM, EDGE_DIM, EDGE_DIM, EDGE_DIM, WHITE, GOLD, CYAN));
            if (root || node != null) {
                final boolean here = takenAt(tree, x, y, allocated);
                for (int i = 0; i < SIDES.length; i++) {
                    final int nx = x + SIDES[i][0];
                    final int ny = y + SIDES[i][1];
                    if (!existsAt(tree, nx, ny)) {
                        continue;
                    }
                    final boolean there = takenAt(tree, nx, ny, allocated);
                    flags.set(i, true);
                    colors.set(i, here && there ? EDGE_GOLD : here || there ? EDGE_LIT : EDGE_DIM);
                }
                if (root) {
                    strings.set(UiPack.Cell.STRING_NODE, "start");
                    colors.set(UiPack.Cell.COLOR_NODE, GOLD);
                    strings.set(UiPack.Cell.STRING_GLOW, "glow");
                    colors.set(UiPack.Cell.COLOR_GLOW, GOLD);
                } else {
                    final State state = state(tree, node, allocated, left);
                    strings.set(UiPack.Cell.STRING_NODE, node.keystone() ? "keystone" : node.notable() ? "notable" : "small");
                    colors.set(UiPack.Cell.COLOR_NODE, switch (state) {
                        case TAKEN -> GOLD;
                        case AVAILABLE -> BRIGHT;
                        case POOR -> ORANGE;
                        case LOCKED -> DIM;
                    });
                    if (state == State.TAKEN) {
                        strings.set(UiPack.Cell.STRING_GLOW, "glow");
                        colors.set(UiPack.Cell.COLOR_GLOW, GOLD);
                    }
                }
            }
            data.setStrings(strings);
            data.setFlags(flags);
            data.setColors(colors);
            meta.setCustomModelDataComponent(data);
        });
        return stack;
    }

    private static boolean hasStat(final AtlasTree.Node node, final String stat) {
        for (final AtlasTree.Mod mod : node.mods()) {
            if (mod.stat().equals(stat)) {
                return true;
            }
        }
        return false;
    }

    private static State state(final AtlasTree tree, final AtlasTree.Node node, final List<String> allocated, final int left) {
        if (allocated.contains(node.id())) {
            return State.TAKEN;
        }
        if (!tree.connected(node, allocated)) {
            return State.LOCKED;
        }
        return node.cost() <= left ? State.AVAILABLE : State.POOR;
    }

    /** 天賦點：還有幾點、總共幾點、怎麼來、目前全部的效果。 */
    private ItemStack points(final AtlasTree tree, final AtlasTree.Earned earned, final List<String> allocated, final int left) {
        final AtlasTree.Points rules = tree.points();
        final MapConfig config = service.config();
        final List<String> lore = new ArrayList<>();
        lore.add("<gray>還沒用 <white>" + left + "<gray>・總共 " + earned.total() + "<dark_gray>（全部點滿要 " + tree.totalCost() + "）");
        if (rules.max() > 0) { // 2026-10-21：總點數上限
            lore.add(earned.capped()
                    ? "<#f5b942>已經到上限 " + rules.max() + " 點<gray>（拿到的共 " + earned.raw() + "；點不滿整棵樹，要挑方向）"
                    : "<dark_gray>總點數上限 " + rules.max() + " 點（點不滿整棵樹，要挑方向）");
        }
        lore.add("");
        if (rules.perMap() > 0) {
            final AtlasProgress atlas = service.atlasProgress(player);
            final AtlasConfig.Points cells = atlas.grid().config().points();
            lore.add("<gray>星圖的格子 <white>+" + earned.maps() + "<gray>（完成 " + atlas.completedMapCells() + " 格、塔 " + atlas.completedTowerCount()
                    + "、城塞 " + atlas.completedCitadelCount() + "）");
            lore.add("<dark_gray>  每格 " + cells.perCell() + " 點，" + cells.softCap() + " 格之後每 " + cells.afterCapEvery() + " 格 1 點；塔 "
                    + cells.perTower() + "、城塞 " + cells.perCitadel());
        }
        if (rules.perFinal() > 0) {
            lore.add("<gray>打贏一個終局 <white>+" + rules.perFinal() + "<gray>（你打贏了 <white>" + earned.finals() / rules.perFinal()
                    + "<gray> / " + (config.finals().size() - config.eventPinnacles().size()) + "）");
        }
        if (rules.perUber() > 0) {
            lore.add("<gray>打贏一個極境 <white>+" + rules.perUber() + "<gray>（你打贏了 <white>" + earned.ubers() / rules.perUber() + "<gray>）");
        }
        if (!rules.tierMilestones().isEmpty()) {
            final List<String> tiers = new ArrayList<>();
            for (final int tier : rules.tierMilestones()) {
                tiers.add("T" + tier);
            }
            final int best = service.bestTier(player);
            final int next = tree.nextMilestone(best);
            lore.add("<gray>最高完成階級到 " + String.join("、", tiers) + " 各 <white>+1");
            lore.add("<gray>  你現在 <white>" + (best > 0 ? "T" + best : "還沒完成過") + "<gray>"
                    + (next > 0 ? "，下一點在 T" + next : "，都拿到了"));
        }
        lore.add("");
        lore.add("<gray>左鍵點、右鍵退（<white>退點免費<gray>，連不回中心的一起退）");
        lore.add("<gray>效果算在<white>你開的地圖<gray>上，隊友進你的地圖也吃");
        lore.add("<dark_gray>改了之後開的地圖才算（開著的不變）；終局（只吃極境鑰匙那條）、極境、巔峰、回響、試煉、塔、金庫不吃");
        lore.add("<#77ccff>右鍵：網頁規劃器<gray>（整棵樹、帶著現在的配點；/atlas import 匯入）");
        final AtlasBonus bonus = tree.bonus(allocated);
        if (!bonus.isEmpty()) {
            lore.add("");
            lore.add("<#9fd3ff>你開的地圖：");
            for (final String stat : AtlasTree.STATS.keySet()) {
                if (bonus.get(stat) > 0) {
                    lore.add("<green>• " + ItemRenderer.escape(AtlasTree.describe(stat, bonus.get(stat))));
                }
            }
            for (final String stat : AtlasTree.DRAWBACKS.keySet()) {
                if (bonus.get(stat) > 0) {
                    lore.add("<red>• " + ItemRenderer.escape(AtlasTree.describe(stat, bonus.get(stat))));
                }
            }
        }
        final ItemStack icon = icon(Material.BOOK, "<#9fd3ff>星圖天賦點 <white>" + left + "<gray> / " + earned.total(), lore);
        return left > 0 ? glowing(icon) : icon;
    }

    private ItemStack reset(final List<String> allocated) {
        if (allocated.isEmpty()) {
            return icon(Material.GRINDSTONE, "<gray>全部退回", "<dark_gray>還沒點任何天賦");
        }
        if (armed()) {
            return glowing(icon(Material.GRINDSTONE, "<red>再點一次：全部退回", "<gray>退回 " + allocated.size() + " 個天賦（免費）",
                    "<dark_gray>5 秒內沒點就取消"));
        }
        return icon(Material.GRINDSTONE, "<white>全部退回", "<gray>退回 " + allocated.size() + " 個天賦（免費）", "<dark_gray>要點兩下確認");
    }

    private boolean armed() {
        return resetArmedAt > 0 && System.currentTimeMillis() - resetArmedAt <= RESET_CONFIRM_MILLIS;
    }

    @Override
    protected void onClick(final int slot, final ClickType click, final InventoryClickEvent event) {
        switch (slot) {
            case SLOT_ATLAS -> {
                if (click.isRightClick()) {
                    new AtlasSetMenu(plugin, player, service).open(); // 2026-10-21：星圖天賦組（月卡）
                } else {
                    new AtlasMenu(plugin, player, service).open();
                }
            }
            case SLOT_CLOSE -> goBack();
            case SLOT_RESET -> clickReset(click);
            case SLOT_UP -> scroll(0, -1, click);
            case SLOT_DOWN -> scroll(0, 1, click);
            case SLOT_LEFT -> scroll(-1, 0, click);
            case SLOT_RIGHT -> scroll(1, 0, click);
            case SLOT_HOME -> {
                home();
                redraw();
            }
            case SLOT_POINTS -> {
                if (click.isRightClick()) { // 2026-10-21：網頁規劃器
                    final String url = service.atlasPlannerUrl(player);
                    if (url == null) {
                        tw.exilecore.gui.ActionBar.send(player, plugin.text().parse("<red>網頁沒有開"));
                    } else {
                        player.sendMessage(plugin.text().prefixed("<#77ccff><click:open_url:'" + url + "'><u>打開星圖天賦的網頁規劃器</u></click><gray>（帶著你現在的配點；規劃好按「複製代碼」，回來輸入 /atlas import <代碼>）"));
                    }
                }
            }
            default -> {
                if (slot >= VIEW_WIDTH * VIEW_HEIGHT) {
                    return;
                }
                final int x = viewX + slot % 9;
                final int y = viewY + slot / 9;
                final AtlasTree.Node node = service.tree().at(x, y);
                if (node != null) {
                    clickNode(node, click);
                }
            }
        }
    }

    private void scroll(final int dx, final int dy, final ClickType click) {
        final int step = click.isShiftClick() ? 1 : SCROLL;
        viewX += dx * step;
        viewY += dy * step;
        clampView();
        redraw();
    }

    private void clickNode(final AtlasTree.Node node, final ClickType click) {
        if (!acceptAction(click)) {
            return;
        }
        resetArmedAt = 0;
        final AtlasTree tree = service.tree();
        final List<String> allocated = service.atlasAllocated(player);
        final boolean taken = allocated.contains(node.id());
        if (click.isRightClick()) {
            if (!taken) {
                tw.exilecore.gui.ActionBar.send(player, plugin.text().parse("<gray>「" + ItemRenderer.escape(node.name()) + "」還沒點（左鍵點）"));
                return;
            }
            final List<String> removed = service.refundAtlas(player, node.id());
            player.playSound(player.getLocation(), Sound.UI_BUTTON_CLICK, 0.4f, 0.8f);
            int points = 0;
            for (final String id : removed) {
                final AtlasTree.Node other = tree.node(id);
                points += other == null ? 0 : other.cost();
            }
            tw.exilecore.gui.ActionBar.send(player, plugin.text().parse("<yellow>退回「" + ItemRenderer.escape(node.name()) + "」"
                    + (removed.size() > 1 ? "（連同連不回中心的 " + (removed.size() - 1) + " 個）" : "") + "<gray>，拿回 " + points + " 點"));
            redraw();
            return;
        }
        if (taken) {
            if (hasStat(node, "atlas.chosen_event")) { // 核心「單一信仰」：左鍵換選的事件
                final MapEventConfig.Kind next = service.cycleFaith(player);
                player.playSound(player.getLocation(), Sound.UI_BUTTON_CLICK, 0.4f, 1.4f);
                tw.exilecore.gui.ActionBar.send(player, plugin.text().parse("<yellow>單一信仰：改選 " + MapEventConfig.sourceName(MapEventConfig.uniqueSource(next))));
                redraw();
                return;
            }
            tw.exilecore.gui.ActionBar.send(player, plugin.text().parse("<gray>「" + ItemRenderer.escape(node.name()) + "」已經點了（右鍵退回）"));
            return;
        }
        // 點不到的：自動把路上的一起點（點數要夠全部）
        final List<AtlasTree.Node> path = tree.connected(node, allocated) ? List.of(node) : tree.pathTo(allocated, node);
        if (path.isEmpty()) {
            player.playSound(player.getLocation(), Sound.ENTITY_VILLAGER_NO, 0.5f, 1.2f);
            tw.exilecore.gui.ActionBar.send(player, plugin.text().parse("<red>連不到中心"));
            return;
        }
        int cost = 0;
        for (final AtlasTree.Node step : path) {
            cost += step.cost();
        }
        final int left = service.atlasUnspent(player);
        if (cost > left) {
            player.playSound(player.getLocation(), Sound.ENTITY_VILLAGER_NO, 0.5f, 1.2f);
            tw.exilecore.gui.ActionBar.send(player, plugin.text().parse("<red>天賦點不夠：" + (path.size() > 1 ? "路上 " + path.size() + " 個共" : "要") + " " + cost
                    + " 點，你還有 " + left + " 點"));
            return;
        }
        for (final AtlasTree.Node step : path) {
            final String problem = service.allocateAtlas(player, step.id());
            if (problem != null) {
                player.playSound(player.getLocation(), Sound.ENTITY_VILLAGER_NO, 0.5f, 1.2f);
                tw.exilecore.gui.ActionBar.send(player, plugin.text().parse("<red>" + ItemRenderer.escape(problem)));
                redraw();
                return;
            }
        }
        player.playSound(player.getLocation(), Sound.ENTITY_EXPERIENCE_ORB_PICKUP, 0.6f, 1.3f);
        final List<String> effects = new ArrayList<>();
        for (final AtlasTree.Mod mod : node.mods()) {
            effects.add(AtlasTree.describe(mod.stat(), mod.value()));
        }
        tw.exilecore.gui.ActionBar.send(player, plugin.text().parse("<green>點了「" + ItemRenderer.escape(node.name()) + "」"
                + (path.size() > 1 ? "<gray>（連同路上的 " + (path.size() - 1) + " 個）" : "") + "<gray>：" + ItemRenderer.escape(String.join("、", effects))));
        redraw();
    }

    private void clickReset(final ClickType click) {
        if (!acceptAction(click)) {
            return;
        }
        if (service.atlasAllocated(player).isEmpty()) {
            tw.exilecore.gui.ActionBar.send(player, plugin.text().parse("<gray>還沒點任何天賦"));
            return;
        }
        if (!armed()) {
            resetArmedAt = System.currentTimeMillis();
            player.playSound(player.getLocation(), Sound.UI_BUTTON_CLICK, 0.4f, 1.0f);
            tw.exilecore.gui.ActionBar.send(player, plugin.text().parse("<yellow>再點一次「全部退回」確認（5 秒內）"));
            redraw();
            return;
        }
        resetArmedAt = 0;
        final int count = service.resetAtlas(player);
        player.playSound(player.getLocation(), Sound.BLOCK_GRINDSTONE_USE, 0.6f, 1.0f);
        tw.exilecore.gui.ActionBar.send(player, plugin.text().parse("<yellow>退回了 " + count + " 個星圖天賦（免費）"));
        redraw();
    }
}
