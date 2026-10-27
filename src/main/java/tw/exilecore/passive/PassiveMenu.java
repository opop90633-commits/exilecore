package tw.exilecore.passive;

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
import tw.exilecore.character.CharacterRecord;
import tw.exilecore.character.PlayerState;
import tw.exilecore.gui.Menu;
import tw.exilecore.item.ItemRenderer;
import tw.exilecore.web.UiPack;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;

/**
 * 天賦樹視窗：上面 5 列是 9×5 的視野，最下面一列是捲動與功能按鈕。
 * <ul>
 *   <li>左鍵：配點。點還沒連到的節點會先用藍色畫出最短路徑、列出整條路加起來的能力，再點一次目標就整條配上（PoE2 做法）。</li>
 *   <li>右鍵：退點，花金幣（未滿 20 級免費；有免費退點次數先用）。</li>
 *   <li>點空白的地方：把視野移過去。</li>
 *   <li>縮小地圖：整棵樹分區看，點一區就跳過去。</li>
 *   <li>有套用介面資源包的玩家看到星盤：星空背景、節點之間有連線，已配置的是金色（{@link UiPack}）。</li>
 *   <li>搜尋（2026-10）：有結果時搜尋鈕左鍵跳到下一個、右鍵上一個、Shift＋點重新搜尋、Q 清掉；方向鍵上寫著那個方向還有幾個結果。</li>
 *   <li>珠寶孔（PoE2 的珠寶插槽）：配了之後左鍵選一顆背包裡的珠寶放進去（{@link JewelPickMenu}），再左鍵拿回來；
 *       右鍵退點會連珠寶一起拿回（{@link JewelSockets}）。</li>
 * </ul>
 */
public final class PassiveMenu extends Menu {

    private static final int VIEW_WIDTH = 9;
    private static final int VIEW_HEIGHT = 5;
    private static final int SCROLL_STEP = 3;

    /** 方向鍵照「← ↑ ↓ →」排（跟鍵盤的方向鍵一樣左右在兩邊）；套用資源包的人看到四個方向不同的箭頭圖示。 */
    private static final int SLOT_LEFT = 45;
    private static final int SLOT_UP = 46;
    private static final int SLOT_DOWN = 47;
    private static final int SLOT_RIGHT = 48;
    private static final int SLOT_INFO = 49;
    private static final int SLOT_HOME = 50;
    private static final int SLOT_OVERVIEW = 51;
    private static final int SLOT_SEARCH = 52;
    private static final int SLOT_CLOSE = 53;

    private static final NamespacedKey TREE_CELL = NamespacedKey.fromString(UiPack.TREE_CELL);
    // 星盤的顏色
    private static final Color GOLD = Color.fromRGB(0xF5C04A);
    private static final Color EDGE_GOLD = Color.fromRGB(0xE6A82E);
    private static final Color BRIGHT = Color.fromRGB(0xE8E8F2);
    private static final Color EDGE_LIT = Color.fromRGB(0x9C9CB8);
    private static final Color DIM = Color.fromRGB(0x5E5E74);
    private static final Color EDGE_DIM = Color.fromRGB(0x33354A);
    private static final Color BLUE = Color.fromRGB(0x6FA8FF);
    private static final Color EDGE_BLUE = Color.fromRGB(0x5A8EE8);
    private static final Color CYAN = Color.fromRGB(0x55FFFF);
    private static final Color WHITE = Color.fromRGB(0xFFFFFF);
    /** 珠寶孔的顏色（紫，跟昇華、星籤同一系）。 */
    private static final String SOCKET_COLOR = "#c77dff";

    private final PassiveTree tree;
    private final PassiveState passive;
    private Set<String> highlights;
    /** 搜尋結果照順序（核心、基石在前，同一種照離起點的遠近），搜尋鈕一個一個跳。 */
    private List<PassiveNode> results = List.of();
    /** 目前跳到第幾個搜尋結果（−1 = 還沒跳）。 */
    private int resultIndex = -1;
    private int viewX;
    private int viewY;
    private boolean overview;
    private boolean changed;
    /** 路徑預覽：目標與要經過的節點（含目標）。 */
    private PassiveNode previewTarget;
    private List<PassiveNode> previewPath = List.of();
    private final Set<String> previewIds = new HashSet<>();
    /** 2026-10-26：永恆珠寶征服的節點（畫面顯示征服後的名稱與效果；每次畫細節時重算）。 */
    private java.util.Map<String, PassiveNode> conquered = java.util.Map.of();

    public PassiveMenu(final ExileCore plugin, final Player player, final PassiveTree tree,
                       final Set<String> highlights, final PassiveNode focus) {
        super(plugin, player, 6, "<dark_gray>天賦樹");
        this.tree = tree;
        this.passive = new PassiveState(plugin, player, tree);
        this.highlights = Set.copyOf(highlights);
        this.results = orderResults(this.highlights);
        if (focus != null) {
            centerOn(focus.x(), focus.y());
            resultIndex = results.indexOf(focus);
        } else {
            home();
        }
    }

    private List<PassiveNode> orderResults(final Set<String> ids) {
        final List<PassiveNode> list = new ArrayList<>();
        for (final String id : ids) {
            final PassiveNode node = tree.node(id);
            if (node != null) {
                list.add(node);
            }
        }
        final PassiveNode start = passive.start();
        final int sx = start == null ? 0 : start.x();
        final int sy = start == null ? 0 : start.y();
        list.sort(java.util.Comparator.<PassiveNode>comparingInt(node -> switch (node.type()) {
                    case KEYSTONE -> 0;
                    case NOTABLE -> 1;
                    default -> 2;
                })
                .thenComparingLong(node -> (long) (node.x() - sx) * (node.x() - sx) + (long) (node.y() - sy) * (node.y() - sy))
                .thenComparing(PassiveNode::id));
        return List.copyOf(list);
    }

    /** 跳到下一個（step = 1）或上一個（−1）搜尋結果。 */
    private void jumpResult(final int step) {
        if (results.isEmpty()) {
            return;
        }
        resultIndex = resultIndex < 0 ? (step > 0 ? 0 : results.size() - 1) : Math.floorMod(resultIndex + step, results.size());
        final PassiveNode node = results.get(resultIndex);
        overview = false;
        centerOn(node.x(), node.y());
        redraw();
        player.playSound(player.getLocation(), Sound.UI_BUTTON_CLICK, 0.4f, 1.7f);
        tw.exilecore.gui.ActionBar.send(player, plugin.text().parse("<#55ffff>搜尋結果 " + (resultIndex + 1) + "/" + results.size() + "：<white>"
                + ItemRenderer.escape(node.name().isEmpty() ? node.type().displayName() : node.name())
                + (node.isSocket() && jewelIn(node) != null ? " <dark_gray>（有珠寶）" : "")));
    }

    /** 這一區有幾個搜尋結果。 */
    private int resultsIn(final TreeView.Region region) {
        if (results.isEmpty()) {
            return 0;
        }
        int count = 0;
        for (final PassiveNode node : region.nodes()) {
            if (highlights.contains(node.id())) {
                count++;
            }
        }
        return count;
    }

    private void clearSearch() {
        highlights = Set.of();
        results = List.of();
        resultIndex = -1;
        redraw();
        player.playSound(player.getLocation(), Sound.UI_BUTTON_CLICK, 0.4f, 0.9f);
        tw.exilecore.gui.ActionBar.send(player, plugin.text().parse("<gray>搜尋標記清掉了"));
    }

    /** 視野外、這個方向（dx, dy 其中一個是 ±1）還有幾個搜尋結果。 */
    private int resultsToward(final int dx, final int dy) {
        int count = 0;
        for (final PassiveNode node : results) {
            final boolean inside = node.x() >= viewX && node.x() < viewX + VIEW_WIDTH && node.y() >= viewY && node.y() < viewY + VIEW_HEIGHT;
            if (inside) {
                continue;
            }
            if ((dx < 0 && node.x() < viewX) || (dx > 0 && node.x() >= viewX + VIEW_WIDTH)
                    || (dy < 0 && node.y() < viewY) || (dy > 0 && node.y() >= viewY + VIEW_HEIGHT)) {
                count++;
            }
        }
        return count;
    }

    private String towardLine(final int dx, final int dy) {
        final int count = results.isEmpty() ? 0 : resultsToward(dx, dy);
        return count > 0 ? "<#55ffff>這個方向還有 " + count + " 個搜尋結果" : "";
    }

    /** 2026-10-27 照站長：目前跳到第幾個珠寶孔（−1 = 還沒跳）。 */
    private int socketIndex = -1;

    /** 2026-10-27 照站長：在已配置的珠寶孔之間跳（step = 1 下一個、−1 上一個），順序照離起點的距離。 */
    private void jumpSocket(final int step) {
        final java.util.Set<String> owned = plugin.players().get(player).passives();
        final List<PassiveNode> sockets = new ArrayList<>();
        for (final PassiveNode node : tree.nodes()) {
            if (node.isSocket() && owned.contains(node.id())) {
                sockets.add(node);
            }
        }
        if (sockets.isEmpty()) {
            tw.exilecore.gui.ActionBar.send(player, plugin.text().parse("<gray>還沒有配置任何珠寶孔"));
            return;
        }
        final PassiveNode start = passive.start();
        final int sx = start == null ? 0 : start.x();
        final int sy = start == null ? 0 : start.y();
        sockets.sort(java.util.Comparator.<PassiveNode>comparingLong(node -> (long) (node.x() - sx) * (node.x() - sx)
                + (long) (node.y() - sy) * (node.y() - sy)).thenComparing(PassiveNode::id));
        socketIndex = socketIndex < 0 ? (step > 0 ? 0 : sockets.size() - 1) : Math.floorMod(socketIndex + step, sockets.size());
        final PassiveNode node = sockets.get(Math.min(socketIndex, sockets.size() - 1));
        overview = false;
        centerOn(node.x(), node.y());
        redraw();
        player.playSound(player.getLocation(), Sound.UI_BUTTON_CLICK, 0.4f, 1.7f);
        final ItemStack jewel = jewelIn(node);
        tw.exilecore.gui.ActionBar.send(player, plugin.text().parse("<" + SOCKET_COLOR + ">珠寶孔 " + (socketIndex + 1) + "/" + sockets.size() + "："
                + (jewel == null ? "<gray>空的" : "<white>" + ItemRenderer.escape(net.kyori.adventure.text.serializer.plain.PlainTextComponentSerializer
                .plainText().serialize(jewel.effectiveName())))));
    }

    private void home() {
        final PassiveNode start = passive.start();
        if (start != null) {
            centerOn(start.x(), start.y());
        } else {
            centerOn((tree.minX() + tree.maxX()) / 2, (tree.minY() + tree.maxY()) / 2);
        }
    }

    private void centerOn(final int x, final int y) {
        viewX = x - VIEW_WIDTH / 2;
        viewY = y - VIEW_HEIGHT / 2;
        clampView();
    }

    private void clampView() {
        viewX = Math.max(tree.minX() - 1, Math.min(tree.maxX() + 2 - VIEW_WIDTH, viewX));
        viewY = Math.max(tree.minY() - 1, Math.min(tree.maxY() + 2 - VIEW_HEIGHT, viewY));
    }

    private boolean pack() {
        return plugin.web() != null && plugin.web().hasPack(player);
    }

    // ---- 畫 ----

    @Override
    protected void draw() {
        final PlayerState state = plugin.players().get(player);
        final TreeView view = new TreeView(tree, passive.start(), state.passives(), previewIds, highlights, PassiveState.rules(plugin, state),
                PassiveState.ascendancyId(plugin, state));
        final boolean pack = pack();
        if (overview) {
            drawOverview(view, pack);
        } else {
            drawDetail(view, state, pack);
        }
        drawButtons(state);
    }

    private void drawDetail(final TreeView view, final PlayerState state, final boolean pack) {
        final int points = plugin.players().availablePoints(state);
        final CharacterRecord character = state.character();
        final long gold = character == null ? 0 : state.gold();
        final int freeRefunds = character == null ? 0 : character.refundPoints();
        final long refundCost = passive.refundCost();
        conquered = TimelessJewels.conquered(plugin, state, tree); // 2026-10-26
        for (int row = 0; row < VIEW_HEIGHT; row++) {
            for (int column = 0; column < VIEW_WIDTH; column++) {
                final TreeView.Tile tile = view.tile(viewX + column, viewY + row);
                final ItemStack stack;
                if (tile.node() == null) {
                    // 2026-10：沒資源包時空白的地方放黑色玻璃片（看得出樹的形狀：灰＝還沒連到的小節點、黑＝空的）
                    stack = pack ? cell(tile, null, List.of()) : vanillaEmpty(tile, view);
                } else {
                    final List<String> lore = lore(tile, view, points, gold, refundCost, freeRefunds);
                    final String name = name(tile);
                    stack = pack ? cell(tile, name, lore) : vanilla(tile, name, lore);
                }
                set(row * 9 + column, stack);
            }
        }
    }

    /** 2026-10-26：畫面上看到的節點（被永恆珠寶征服的換成征服後的，其他照職業變體）。 */
    private PassiveNode shown(final PassiveNode raw) {
        final PassiveNode replaced = conquered.get(raw.id());
        return replaced != null ? replaced : raw.forClass(classId());
    }

    private String name(final TreeView.Tile tile) {
        final PassiveNode node = shown(tile.node());
        final String color = switch (tile.state()) {
            case OWNED -> node.isStart() ? "<#77ccff>" : "<green>";
            case PATH -> "<#77aaff>";
            case AVAILABLE -> "<yellow>";
            case LOCKED -> "<dark_gray>";
        };
        final String shown = node.name().isEmpty() ? node.type().displayName() : node.name();
        if (node.isSocket()) {
            final ItemStack jewel = jewelIn(node);
            if (jewel != null) {
                final List<String> described = JewelSockets.describe(plugin, jewel);
                if (!described.isEmpty()) {
                    return (tile.highlight() ? "<#55ffff>★ " : "") + described.getFirst();
                }
            }
            final String jewelColor = tile.state() == TreeView.NodeState.OWNED ? "<" + SOCKET_COLOR + ">" : color;
            return (tile.highlight() ? "<#55ffff>★ " : "") + jewelColor + "◇ " + ItemRenderer.escape(shown) + (jewel == null && tile.state() == TreeView.NodeState.OWNED ? " <dark_gray>（空的）" : "");
        }
        return (tile.highlight() ? "<#55ffff>★ " : "") + color + ItemRenderer.escape(shown);
    }

    /** 玩家的職業代號（節點的職業變體用；沒有角色為 null）。 */
    private String classId() {
        final tw.exilecore.character.CharacterClass type = plugin.players().get(player).characterClass();
        return type == null ? null : type.id();
    }

    /** 這個珠寶孔裡的珠寶（沒有、不是珠寶孔為 null）。 */
    private ItemStack jewelIn(final PassiveNode node) {
        return node.isSocket() ? plugin.players().get(player).jewel(node.id()) : null;
    }

    private List<String> lore(final TreeView.Tile tile, final TreeView view, final int points, final long gold,
                              final long refundCost, final int freeRefunds) {
        final PassiveNode node = shown(tile.node()); // 2026-10-26：職業變體、永恆珠寶征服
        final PassiveNode start = passive.start();
        final String typeColor = switch (node.type()) {
            case NOTABLE -> "<#f5b942>";
            case KEYSTONE -> "<#ff55ff>";
            case SOCKET -> "<" + SOCKET_COLOR + ">";
            default -> "<gray>";
        };
        final List<String> lore = new ArrayList<>();
        lore.add(typeColor + node.type().displayName() + (tile.highlight() ? " <#55ffff>★ 搜尋結果" : ""));
        final ItemStack jewel = jewelIn(node);
        if (jewel != null) {
            // 孔裡有珠寶：寫珠寶的詞綴，不寫孔的說明
            final List<String> described = JewelSockets.describe(plugin, jewel);
            for (int i = 1; i < described.size(); i++) {
                lore.add(described.get(i));
            }
        } else {
            for (final String line : node.description()) {
                lore.add("<white>" + ItemRenderer.escape(line));
            }
            // 2026-10-27 照站長：說明裡的關鍵字（撲殺、秘術增強…）補一次說明
            for (final String explanation : tw.exilecore.stats.KeywordText.explain(node.description())) {
                for (final String line : tw.exilecore.stats.KeywordText.wrap(explanation, 30)) {
                    lore.add("<#9fb4c7>" + ItemRenderer.escape(line));
                }
            }
        }
        if (conquered.containsKey(node.id())) {
            final PassiveNode original = tile.node().forClass(classId());
            lore.add("<#c48bff>被永恆珠寶征服" + (original.name().equals(node.name()) ? "" : "（原本是「" + ItemRenderer.escape(original.name()) + "」）"));
        }
        lore.add("");
        final boolean own = start != null && node.id().equals(start.id());
        if (node.isStart()) {
            lore.add(own ? "<gray>你的職業起點，永遠已配置" : "<dark_gray>別的職業的起點");
        } else if (tile.state() == TreeView.NodeState.OWNED) {
            if (isAttributeChoice(node)) {
                final PlayerState owner = plugin.players().get(player);
                final String chosen = tw.exilecore.character.PlayerStateService.chosenAttribute(owner, owner.characterClass(), node.id());
                lore.add("<#ffd96a>目前：" + attributeName(chosen) + (wisdom(owner)
                        ? " <yellow>（左鍵切換：力量 → 敏捷 → 智慧 → 傷害 → 防禦 → 消耗效率）" : " <yellow>（左鍵切換：力量 → 敏捷 → 智慧）"));
            }
            if (node.isSocket()) {
                lore.add(jewel == null ? "<yellow>左鍵：選一顆背包裡的珠寶放進去" : "<yellow>左鍵：把珠寶拿回背包");
            }
            final boolean refundable = tree.canRefund(plugin.players().get(player).passives(), start, node,
                    PassiveState.rules(plugin, plugin.players().get(player)));
            if (!refundable) {
                lore.add("<green>已配置 <dark_gray>（退掉會切斷路徑）");
            } else if (freeRefunds > 0) {
                lore.add("<yellow>右鍵退點（免費退點次數 " + freeRefunds + "）");
            } else if (refundCost <= 0) {
                lore.add("<yellow>右鍵退點（免費）");
            } else {
                lore.add(String.format(Locale.ROOT, gold >= refundCost ? "<yellow>右鍵退點（%,d 金幣，你有 %,d）" : "<red>右鍵退點要 %,d 金幣，你有 %,d",
                        refundCost, gold));
            }
        } else if (tile.state() == TreeView.NodeState.PATH && node.equals(previewTarget)) {
            lore.add(points >= previewPath.size()
                    ? "<#77aaff>再點一次：整條路配上（共 " + previewPath.size() + " 點，剩 " + points + "）"
                    : "<red>這條路要 " + previewPath.size() + " 點，你只有 " + points + " 點");
            final List<String> total = PassiveSummary.sum(previewPath);
            if (previewPath.size() > 1 && !total.isEmpty()) {
                lore.add("");
                lore.add("<#77aaff>整條路加起來：");
                for (int i = 0; i < Math.min(10, total.size()); i++) {
                    lore.add("<white>　" + ItemRenderer.escape(total.get(i)));
                }
                if (total.size() > 10) {
                    lore.add("<dark_gray>　…還有 " + (total.size() - 10) + " 項");
                }
            }
        } else if (tile.state() == TreeView.NodeState.PATH) {
            lore.add("<#77aaff>路徑上的第 " + (previewPath.indexOf(node) + 1) + " 點");
        } else if (tile.state() == TreeView.NodeState.AVAILABLE) {
            lore.add(points > 0 ? "<yellow>左鍵配置（剩 " + points + " 點）" : "<red>天賦點不夠");
            if (node.isSocket()) {
                lore.add("<dark_gray>配了之後才能放珠寶");
            }
        } else {
            lore.add("<dark_gray>還沒連到：左鍵預覽最短路徑");
        }
        if (previewTarget != null && tile.state() != TreeView.NodeState.OWNED) {
            lore.add("<dark_gray>右鍵：取消路徑預覽");
        }
        return lore;
    }

    /**
     * 沒有資源包的空白格（2026-10）：黑色玻璃片、沒有說明；旁邊有路徑預覽的節點時用藍色，讓路線稍微看得出來。
     */
    private ItemStack vanillaEmpty(final TreeView.Tile tile, final TreeView view) {
        // 2026-10-26：連線經過的空格用顏色畫出來（配了＝綠、路徑預覽＝藍、能配的那一段＝橘、其他＝棕）
        final TreeView.EdgeState wire = TreeView.brightest(tile);
        final Material material = wire == null ? Material.BLACK_STAINED_GLASS_PANE : switch (wire) {
            case OWNED -> Material.GREEN_STAINED_GLASS_PANE;
            case PATH -> Material.BLUE_STAINED_GLASS_PANE;
            case LIT -> Material.ORANGE_STAINED_GLASS_PANE;
            case DIM -> Material.BROWN_STAINED_GLASS_PANE;
        };
        final ItemStack stack = new ItemStack(material);
        stack.editMeta(meta -> meta.setHideTooltip(true));
        return stack;
    }

    /** 沒有資源包：用原版的玻璃片與節點的圖示。 */
    private ItemStack vanilla(final TreeView.Tile tile, final String name, final List<String> lore) {
        final PassiveNode node = tile.node();
        final Material material;
        final ItemStack jewel = jewelIn(node);
        if (jewel != null) {
            material = jewel.getType(); // 孔裡有珠寶：畫那顆珠寶
        } else if (node.type() == PassiveNode.Type.SMALL) {
            if (tile.state() == TreeView.NodeState.OWNED && isAttributeChoice(node)) {
                // 2026-10：屬性點照選的屬性上色（力量紅、敏捷綠、智慧藍）
                material = switch (chosen(node)) {
                    case "dex" -> Material.LIME_STAINED_GLASS_PANE;
                    case "int" -> Material.BLUE_STAINED_GLASS_PANE;
                    default -> Material.RED_STAINED_GLASS_PANE;
                };
            } else if (tile.state() == TreeView.NodeState.OWNED) {
                material = Material.LIME_STAINED_GLASS_PANE;
            } else if (tile.highlight()) {
                material = Material.CYAN_STAINED_GLASS_PANE;
            } else if (tile.state() == TreeView.NodeState.PATH) {
                material = Material.LIGHT_BLUE_STAINED_GLASS_PANE;
            } else if (tile.state() == TreeView.NodeState.AVAILABLE) {
                material = Material.YELLOW_STAINED_GLASS_PANE;
            } else {
                material = Material.GRAY_STAINED_GLASS_PANE;
            }
        } else {
            material = material(node.icon(), Material.ENCHANTED_BOOK);
        }
        final ItemStack stack = icon(material, name, lore);
        if (tile.state() == TreeView.NodeState.OWNED || tile.state() == TreeView.NodeState.PATH || tile.highlight()) {
            glowing(stack);
        }
        return stack;
    }

    /** 有資源包：星盤的一格（背景、連線、節點、發光、搜尋標記）。name 為 null 時不顯示說明。 */
    private ItemStack cell(final TreeView.Tile tile, final String name, final List<String> lore) {
        final ItemStack stack = name == null ? new ItemStack(Material.PAPER) : icon(Material.PAPER, name, lore);
        final PassiveNode node = tile.node();
        stack.editMeta(meta -> {
            meta.setItemModel(TREE_CELL);
            if (name == null) {
                meta.setHideTooltip(true);
            }
            final CustomModelDataComponent data = meta.getCustomModelDataComponent();
            final List<String> strings = new ArrayList<>(List.of(Integer.toString(tile.background()), "", "", ""));
            final List<Boolean> flags = new ArrayList<>(List.of(false, false, false, false));
            final List<Color> colors = new ArrayList<>(List.of(EDGE_DIM, EDGE_DIM, EDGE_DIM, EDGE_DIM, WHITE, GOLD, CYAN));
            for (int i = 0; i < 4; i++) {
                final TreeView.EdgeState edge = tile.edges()[i];
                if (edge != null) {
                    flags.set(i, true);
                    colors.set(i, switch (edge) {
                        case OWNED -> EDGE_GOLD;
                        case PATH -> EDGE_BLUE;
                        case LIT -> EDGE_LIT;
                        case DIM -> EDGE_DIM;
                    });
                }
            }
            if (node != null) {
                final ItemStack jewel = jewelIn(node);
                final String jewelHex = JewelSockets.color(plugin, jewel);
                final Color jewelColor = jewelHex == null ? null : Color.fromRGB(Integer.parseInt(jewelHex.substring(1), 16));
                strings.set(UiPack.Cell.STRING_NODE, switch (node.type()) {
                    case SMALL -> "small";
                    case NOTABLE -> "notable";
                    case KEYSTONE -> "keystone";
                    case START -> "start";
                    case SOCKET -> jewel == null ? "socket" : "jewel";
                });
                final Color attrColor = tile.state() == TreeView.NodeState.OWNED && isAttributeChoice(node) ? attributeColor(chosen(node)) : null;
                colors.set(UiPack.Cell.COLOR_NODE, jewelColor != null ? jewelColor : attrColor != null ? attrColor : switch (tile.state()) {
                    case OWNED -> GOLD;
                    case PATH -> BLUE;
                    case AVAILABLE -> BRIGHT;
                    case LOCKED -> DIM;
                });
                if (tile.state() == TreeView.NodeState.OWNED) {
                    strings.set(UiPack.Cell.STRING_GLOW, "glow");
                    colors.set(UiPack.Cell.COLOR_GLOW, jewelColor != null ? jewelColor : attrColor != null ? attrColor : GOLD);
                } else if (tile.state() == TreeView.NodeState.PATH) {
                    strings.set(UiPack.Cell.STRING_GLOW, "glow");
                    colors.set(UiPack.Cell.COLOR_GLOW, BLUE);
                }
                if (tile.highlight()) {
                    strings.set(UiPack.Cell.STRING_RING, "ring");
                }
            }
            data.setStrings(strings);
            data.setFlags(flags);
            data.setColors(colors);
            meta.setCustomModelDataComponent(data);
        });
        return stack;
    }

    // ---- 縮小地圖 ----

    private void drawOverview(final TreeView view, final boolean pack) {
        for (int slot = 0; slot < VIEW_WIDTH * VIEW_HEIGHT; slot++) {
            set(slot, pack ? cell(new TreeView.Tile(0, 0, null, null, new TreeView.EdgeState[4], false, slot % UiPack.BACKGROUNDS),
                    null, List.of()) : null);
        }
        final int centerX = viewX + VIEW_WIDTH / 2;
        final int centerY = viewY + VIEW_HEIGHT / 2;
        for (final TreeView.Region region : view.regions(VIEW_WIDTH, VIEW_HEIGHT)) {
            if (region.nodes().isEmpty()) {
                continue;
            }
            final boolean here = region.contains(centerX, centerY);
            final int found = resultsIn(region);
            final List<String> lore = new ArrayList<>();
            if (found > 0) {
                lore.add("<#55ffff>★ 這一區有 " + found + " 個搜尋結果");
            }
            lore.add("<gray>已配置 <white>" + region.owned() + "</white> / " + region.nodes().size()
                    + (region.available() > 0 ? "　<yellow>能配 " + region.available() : ""));
            final List<String> named = new ArrayList<>();
            for (final PassiveNode node : region.nodes()) {
                if (node.type() == PassiveNode.Type.NOTABLE || node.type() == PassiveNode.Type.KEYSTONE || node.isStart() || node.isSocket()) {
                    final boolean owned = view.owned(node);
                    final String color = node.type() == PassiveNode.Type.KEYSTONE ? "<#ff55ff>" : node.isSocket() ? "<" + SOCKET_COLOR + ">" : "";
                    named.add((owned ? "<green>✔ " : "<gray>・") + color
                            + ItemRenderer.escape(node.name().isEmpty() ? node.type().displayName() : node.name())
                            + (node.isSocket() && jewelIn(node) != null ? " <dark_gray>（有珠寶）" : ""));
                }
            }
            if (!named.isEmpty()) {
                lore.add("");
                lore.addAll(named.subList(0, Math.min(8, named.size())));
                if (named.size() > 8) {
                    lore.add("<dark_gray>…還有 " + (named.size() - 8) + " 個");
                }
            }
            lore.add("");
            lore.add(here ? "<#77ccff>你現在看的地方" : "<yellow>左鍵：到這一區");
            if (found > 0) {
                lore.add("<#55ffff>（會跳到這一區的第一個搜尋結果）");
            }
            final PassiveNode landmark = region.landmark();
            final String name = (found > 0 ? "<#55ffff>★ " : "") + "<white>" + ItemRenderer.escape(landmark.name().isEmpty() ? landmark.type().displayName() : landmark.name())
                    + " <dark_gray>附近";
            final ItemStack stack;
            if (pack) {
                final TreeView.NodeState state = region.owned() > 0 ? TreeView.NodeState.OWNED
                        : region.available() > 0 ? TreeView.NodeState.AVAILABLE : TreeView.NodeState.LOCKED;
                // 有搜尋時：青色的圈標出有結果的區（沒搜尋時照舊標你現在看的地方）
                final boolean ring = results.isEmpty() ? here : found > 0;
                stack = cell(new TreeView.Tile(region.centerX(), region.centerY(), landmark, state, new TreeView.EdgeState[4], ring,
                        (region.column() + region.row()) % UiPack.BACKGROUNDS), name, lore);
            } else {
                final Material material = found > 0 ? Material.CYAN_STAINED_GLASS_PANE
                        : landmark.type() == PassiveNode.Type.SMALL
                        ? region.owned() > 0 ? Material.LIME_STAINED_GLASS_PANE
                        : region.available() > 0 ? Material.YELLOW_STAINED_GLASS_PANE : Material.GRAY_STAINED_GLASS_PANE
                        : material(landmark.icon(), Material.ENCHANTED_BOOK);
                stack = icon(material, name, lore);
                if (here || region.owned() > 0 || found > 0) {
                    glowing(stack);
                }
            }
            // 有搜尋時疊的數字是這一區的結果數，沒搜尋時是已配置的點數
            stack.setAmount(Math.max(1, Math.min(64, found > 0 ? found : results.isEmpty() ? region.owned() : 1)));
            set(region.row() * 9 + region.column(), stack);
        }
    }

    // ---- 下面一列 ----

    private void drawButtons(final PlayerState state) {
        final int points = plugin.players().availablePoints(state);
        final CharacterRecord character = state.character();
        final long gold = character == null ? 0 : state.gold();
        final int freeRefunds = character == null ? 0 : character.refundPoints();
        final long refundCost = passive.refundCost();
        if (overview) {
            for (final int slot : new int[] {SLOT_LEFT, SLOT_RIGHT, SLOT_UP, SLOT_DOWN, SLOT_HOME}) {
                set(slot, filler());
            }
        } else {
            set(SLOT_LEFT, uiIcon("arrow_left", Material.ARROW, "<white>← 往左看", "<gray>點一下：往左捲 " + SCROLL_STEP + " 格",
                    "<gray>Shift＋點：往左捲一整頁", "<dark_gray>點星空裡空白的地方：視野移到那裡", towardLine(-1, 0)));
            set(SLOT_UP, uiIcon("arrow_up", Material.ARROW, "<white>↑ 往上看", "<gray>點一下：往上捲 " + SCROLL_STEP + " 格",
                    "<gray>Shift＋點：往上捲一整頁", "<dark_gray>點星空裡空白的地方：視野移到那裡", towardLine(0, -1)));
            set(SLOT_DOWN, uiIcon("arrow_down", Material.ARROW, "<white>↓ 往下看", "<gray>點一下：往下捲 " + SCROLL_STEP + " 格",
                    "<gray>Shift＋點：往下捲一整頁", "<dark_gray>點星空裡空白的地方：視野移到那裡", towardLine(0, 1)));
            set(SLOT_RIGHT, uiIcon("arrow_right", Material.ARROW, "<white>→ 往右看", "<gray>點一下：往右捲 " + SCROLL_STEP + " 格",
                    "<gray>Shift＋點：往右捲一整頁", "<dark_gray>點星空裡空白的地方：視野移到那裡", towardLine(1, 0)));
            set(SLOT_HOME, icon(Material.COMPASS, "<white>回到起點", "<gray>左鍵：視野移回職業起點",
                    "<gray>右鍵：跳到下一個配了的珠寶孔", "<gray>Shift＋右鍵：跳到上一個")); // 2026-10-27
        }
        final List<String> info = new ArrayList<>();
        info.add("<gray>可用 <white>" + points + "</white>　已配置 <white>" + state.passives().size());
        info.add(String.format(Locale.ROOT, "<gray>金幣 <#f5b942>%,d", gold));
        if (freeRefunds > 0) {
            info.add("<gray>免費退點次數 <white>" + freeRefunds);
        } else if (refundCost <= 0) {
            info.add("<gray>退點免費（未滿 " + plugin.players().config().respecFreeUntilLevel() + " 級）");
        } else {
            info.add(String.format(Locale.ROOT, "<gray>退一點 <#f5b942>%,d 金幣", refundCost));
        }
        info.add(character == null ? "<red>還沒有角色" : "<gray>" + character.characterClass().displayName() + " Lv." + character.level()
                + "　<dark_gray>每升一級 +1 點");
        int sockets = 0;
        for (final String id : state.passives()) {
            final PassiveNode node = tree.node(id);
            if (node != null && node.isSocket()) {
                sockets++;
            }
        }
        if (sockets > 0 || !state.jewels().isEmpty()) {
            info.add("<gray>珠寶孔 <white>" + sockets + "</white>　放了珠寶 <" + SOCKET_COLOR + ">" + state.jewels().size());
        }
        if (previewTarget != null) {
            info.add("<#77aaff>路徑預覽：" + previewPath.size() + " 點 → " + ItemRenderer.escape(previewTarget.name()));
        }
        info.add("");
        info.add("<gray>左鍵配點、右鍵退點；點遠處的節點先畫出路徑");
        info.add("<yellow>左鍵：天賦總覽・網頁規劃器・匯入・說明");
        set(SLOT_INFO, glowing(icon(Material.NETHER_STAR, "<#f5b942>天賦點", info)));
        set(SLOT_OVERVIEW, overview
                ? icon(Material.SPYGLASS, "<white>回到天賦樹", "<gray>回到剛才的位置")
                : icon(Material.FILLED_MAP, "<white>縮小地圖", "<gray>整棵樹分區看，點一區就跳過去"));
        if (results.isEmpty()) {
            set(SLOT_SEARCH, icon(Material.SPYGLASS, "<white>搜尋天賦",
                    "<gray>輸入關鍵字，符合的用<#55ffff>青色</#55ffff>標出",
                    "<dark_gray>例如「珠寶」、「生命」、「暴擊」",
                    "<dark_gray>也可以直接打 /tree find 生命"));
        } else {
            final ItemStack search = glowing(icon(Material.SPYGLASS, "<#55ffff>搜尋結果 "
                            + (resultIndex < 0 ? "共 " + results.size() + " 個" : (resultIndex + 1) + "/" + results.size()),
                    "<yellow>左鍵：跳到下一個",
                    "<yellow>右鍵：跳到上一個",
                    "<gray>Shift＋點：重新搜尋",
                    "<gray>Q：清掉搜尋標記"));
            search.setAmount(Math.max(1, Math.min(64, results.size())));
            set(SLOT_SEARCH, search);
        }
        set(SLOT_CLOSE, closeButton());
    }

    // ---- 點擊 ----

    @Override
    protected void onClick(final int slot, final ClickType click, final InventoryClickEvent event) {
        if (click == ClickType.DOUBLE_CLICK) {
            return; // 雙擊多送的那一下不算（不然配一點會變兩點）
        }
        final boolean page = click.isShiftClick();
        final int step = page ? (slot == SLOT_UP || slot == SLOT_DOWN ? VIEW_HEIGHT : VIEW_WIDTH) : SCROLL_STEP;
        switch (slot) {
            case SLOT_LEFT -> scroll(-step, 0);
            case SLOT_RIGHT -> scroll(step, 0);
            case SLOT_UP -> scroll(0, -step);
            case SLOT_DOWN -> scroll(0, step);
            case SLOT_HOME -> {
                if (click.isRightClick()) {
                    jumpSocket(click.isShiftClick() ? -1 : 1); // 2026-10-27 照站長：跳到配了的珠寶孔
                } else if (!overview) {
                    home();
                    redraw();
                }
            }
            case SLOT_OVERVIEW -> {
                overview = !overview;
                redraw();
                player.playSound(player.getLocation(), Sound.UI_BUTTON_CLICK, 0.4f, overview ? 1.2f : 1.5f);
            }
            case SLOT_INFO -> TreeActions.summary(plugin, player, tree, () -> {
                // 回到剛才看的位置（匯入規劃後配點可能變了：路徑預覽作廢）
                clearPreview();
                open();
            });
            case SLOT_SEARCH -> {
                if (results.isEmpty() || click.isShiftClick()) {
                    plugin.characterMenus().promptTreeSearch(player);
                } else if (click == ClickType.DROP || click == ClickType.CONTROL_DROP) {
                    clearSearch();
                } else {
                    jumpResult(click.isRightClick() ? -1 : 1);
                }
            }
            case SLOT_CLOSE -> goBack();
            default -> {
                if (slot < VIEW_WIDTH * VIEW_HEIGHT) {
                    if (overview) {
                        clickRegion(slot);
                    } else {
                        clickCell(slot, click);
                    }
                }
            }
        }
    }

    private void clickRegion(final int slot) {
        final TreeView view = new TreeView(tree, passive.start(), plugin.players().get(player).passives(), previewIds, highlights,
                PassiveState.rules(plugin, plugin.players().get(player)), PassiveState.ascendancyId(plugin, plugin.players().get(player)));
        for (final TreeView.Region region : view.regions(VIEW_WIDTH, VIEW_HEIGHT)) {
            if (region.row() * 9 + region.column() == slot && !region.nodes().isEmpty()) {
                final PassiveNode landmark = region.landmark();
                centerOn(landmark.x(), landmark.y());
                // 這一區有搜尋結果：跳到這一區的第一個（照結果的順序），搜尋鈕接著從這裡往下跳
                for (int i = 0; i < results.size(); i++) {
                    if (region.nodes().contains(results.get(i))) {
                        resultIndex = i;
                        centerOn(results.get(i).x(), results.get(i).y());
                        tw.exilecore.gui.ActionBar.send(player, plugin.text().parse("<#55ffff>搜尋結果 " + (i + 1) + "/" + results.size() + "：<white>"
                                + ItemRenderer.escape(results.get(i).name().isEmpty() ? results.get(i).type().displayName() : results.get(i).name())));
                        break;
                    }
                }
                overview = false;
                redraw();
                player.playSound(player.getLocation(), Sound.UI_BUTTON_CLICK, 0.4f, 1.5f);
                return;
            }
        }
    }

    private void clickCell(final int slot, final ClickType click) {
        final int x = viewX + slot % 9;
        final int y = viewY + slot / 9;
        final PassiveNode raw = tree.at(x, y);
        // 2026-10-26 照站長：別的昇華看不到觀星專屬節點（當作空格）
        final PassiveNode node = raw == null || tree.hidden(raw, PassiveState.ascendancyId(plugin, plugin.players().get(player))) ? null : raw;
        if (node == null) {
            // 空白的地方：把視野移過去
            final int oldX = viewX;
            final int oldY = viewY;
            centerOn(x, y);
            if (oldX != viewX || oldY != viewY) {
                redraw();
                player.playSound(player.getLocation(), Sound.UI_BUTTON_CLICK, 0.3f, 1.6f);
            }
            return;
        }
        clickNode(node, click);
    }

    private void scroll(final int dx, final int dy) {
        if (overview) {
            return;
        }
        viewX += dx;
        viewY += dy;
        clampView();
        redraw();
        player.playSound(player.getLocation(), Sound.UI_BUTTON_CLICK, 0.4f, 1.5f);
    }

    private void clickNode(final PassiveNode node, final ClickType click) {
        final PlayerState state = plugin.players().get(player);
        final PassiveNode start = passive.start();
        PassiveState.Result result;
        if (click.isRightClick() && previewTarget != null && !state.passives().contains(node.id())) {
            // 右鍵還沒配的節點：取消路徑預覽（不是退點）
            clearPreview();
            redraw();
            tw.exilecore.gui.ActionBar.send(player, plugin.text().parse("<gray>取消路徑預覽"));
            return;
        } else if (click.isRightClick()) {
            clearPreview();
            result = passive.refund(node);
        } else if (isAttributeChoice(node) && state.passives().contains(node.id())) {
            // 屬性點（2026-10 PoE2）：配了之後左鍵切換力量／敏捷／智慧，不花錢
            clearPreview();
            final String now = tw.exilecore.character.PlayerStateService.chosenAttribute(state, state.characterClass(), node.id());
            // 2026-10-26 照 PoE2 旅者的智慧：智慧之後再多傷害、防禦、消耗效率
            final boolean wisdom = wisdom(state);
            final String next = switch (now) {
                case "str" -> "dex";
                case "dex" -> "int";
                case "int" -> wisdom ? "dmg" : "str";
                case "dmg" -> "def";
                case "def" -> "cost";
                default -> "str";
            };
            plugin.characters().setProgress(player, "attr." + node.id(), next); // 2026-10：存進資料庫（以前只放在記憶體，重登會不見）
            state.markDirty();
            plugin.players().sheet(player);
            tw.exilecore.gui.ActionBar.send(player, plugin.text().parse(tw.exilecore.character.PlayerStateService.WISDOM_CHOICES.contains(next)
                    ? "<#ffd96a>這個屬性點改成增加 " + attributeAmount(node) + "% " + attributeName(next)
                    : "<#ffd96a>這個屬性點改成 +" + attributeAmount(node) + " " + attributeName(next)));
            result = PassiveState.Result.success();
        } else if (node.isSocket() && state.passives().contains(node.id())) {
            // 配了的珠寶孔：空的選珠寶放進去，有珠寶的拿回來
            clearPreview();
            if (state.jewel(node.id()) == null) {
                new JewelPickMenu(plugin, player, tree, node, highlights).open();
                return;
            }
            result = JewelSockets.unsocket(plugin, player, node);
            if (result.ok()) {
                tw.exilecore.gui.ActionBar.send(player, plugin.text().parse("<yellow>珠寶拿回背包了"));
            }
        } else if (start != null && !node.isStart() && !state.passives().contains(node.id())
                && !tree.canAllocate(state.passives(), start, node, PassiveState.rules(plugin, state))) {
            // 還沒連到：第一次點畫路徑，第二次點同一個目標才配
            if (node.equals(previewTarget)) {
                result = passive.allocatePath(previewPath);
                if (result.ok()) {
                    clearPreview();
                }
            } else {
                final List<PassiveNode> path = tree.pathTo(state.passives(), start, node, PassiveState.rules(plugin, state));
                if (path.isEmpty()) {
                    clearPreview();
                    // 2026-10-26 照 PoE2：有解鎖條件的節點（觀星「未見之道」、要先配某個核心）
                    final PassiveTree.Unlock unlock = tree.unlock(node);
                    result = PassiveState.Result.fail(unlock == null || !tree.locked(state.passives(), node, PassiveState.rules(plugin, state))
                            ? "走不到那裡" : unlock.stat() != null ? "只有觀星配了昇華天賦「未見之道」才能配" : "要先配旁邊說明寫的核心才能配");
                } else {
                    previewTarget = node;
                    previewPath = path;
                    previewIds.clear();
                    for (final PassiveNode step : path) {
                        previewIds.add(step.id());
                    }
                    player.playSound(player.getLocation(), Sound.UI_BUTTON_CLICK, 0.5f, 1.2f);
                    tw.exilecore.gui.ActionBar.send(player, plugin.text().parse("<#77aaff>這條路要 " + path.size() + " 點，再點一次 "
                            + ItemRenderer.escape(node.name()) + " 就整條配上"));
                    redraw();
                    return;
                }
            }
        } else {
            clearPreview();
            result = passive.allocate(node);
        }
        if (result.ok()) {
            changed = true;
            player.playSound(player.getLocation(), Sound.ENTITY_EXPERIENCE_ORB_PICKUP, 0.8f, click.isRightClick() ? 0.7f : 1.3f);
        } else {
            player.playSound(player.getLocation(), Sound.BLOCK_NOTE_BLOCK_BASS, 0.8f, 0.6f);
            tw.exilecore.gui.ActionBar.send(player, plugin.text().parse("<red>" + result.message()));
        }
        redraw();
    }

    /** 這個屬性點現在算哪個屬性。 */
    private String chosen(final PassiveNode node) {
        final PlayerState state = plugin.players().get(player);
        return tw.exilecore.character.PlayerStateService.chosenAttribute(state, state.characterClass(), node.id());
    }

    /** 屬性點的顏色：力量紅、敏捷綠、智慧藍。 */
    private static Color attributeColor(final String key) {
        return switch (key) {
            case "dex" -> Color.fromRGB(0x5BD65B);
            case "int" -> Color.fromRGB(0x5A8CFF);
            default -> Color.fromRGB(0xF0544F);
        };
    }

    /** 天賦樹的屬性點（+x 任一屬性）。 */
    static boolean isAttributeChoice(final PassiveNode node) {
        return node.modifiers().stream().anyMatch(mod -> tw.exilecore.stats.StatKeys.ATTR_CHOICE.equals(mod.stat()));
    }

    private static int attributeAmount(final PassiveNode node) {
        return (int) Math.round(node.modifiers().stream().filter(mod -> tw.exilecore.stats.StatKeys.ATTR_CHOICE.equals(mod.stat()))
                .mapToDouble(tw.exilecore.stats.Modifier::value).sum());
    }

    /** 2026-10-26：有沒有配開路者的旅者的智慧（屬性點可以改給傷害／防禦／消耗效率）。 */
    private static boolean wisdom(final PlayerState state) {
        final String nodes = state.progress().get(tw.exilecore.ascendancy.Ascension.NODES);
        return nodes != null && java.util.Arrays.stream(nodes.split(",")).anyMatch(id -> "travellers_wisdom".equals(id.trim()));
    }

    private static String attributeName(final String key) {
        return switch (key) {
            case "dex" -> "敏捷";
            case "int" -> "智慧";
            case "dmg" -> "傷害";
            case "def" -> "護甲、閃避值與能量護盾";
            case "cost" -> "消耗效率";
            default -> "力量";
        };
    }

    private void clearPreview() {
        previewTarget = null;
        previewPath = List.of();
        previewIds.clear();
    }

    @Override
    protected void onClose() {
        if (changed) {
            plugin.characters().save(player);
        }
    }
}
