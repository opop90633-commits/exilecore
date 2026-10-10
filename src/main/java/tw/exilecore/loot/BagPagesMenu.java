package tw.exilecore.loot;

import org.bukkit.Material;
import org.bukkit.entity.Player;
import org.bukkit.event.inventory.ClickType;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.inventory.ItemStack;
import tw.exilecore.ExileCore;
import tw.exilecore.character.Bag;
import tw.exilecore.gui.Menu;

import java.util.List;

/**
 * 隨身背包的全部頁一覽（2026-10，背包可以買很多頁）：每一頁一格，寫裝了幾格，點一下跳過去；
 * 一次 36 頁，更多就 ◀ ▶ 換批。
 */
final class BagPagesMenu extends Menu {

    private static final int PER_SCREEN = CONTENT_END - CONTENT_START;

    private final LootService service;
    private final Bag bag;
    private final int current;
    private int screen;

    BagPagesMenu(final ExileCore plugin, final Player player, final LootService service, final Bag bag, final int current) {
        super(plugin, player, 6, "<dark_gray>隨身背包：全部頁");
        this.service = service;
        this.bag = bag;
        this.current = current;
        this.screen = current / PER_SCREEN;
    }

    private int screens() {
        return Math.max(1, (bag.viewPages() + PER_SCREEN - 1) / PER_SCREEN);
    }

    @Override
    protected void draw() {
        frame();
        int used = 0;
        for (int p = 0; p < bag.viewPages(); p++) {
            used += bag.itemCount(p);
        }
        set(4, icon(Material.BUNDLE, "<#f5b942>隨身背包 " + bag.viewPages() + " 頁",
                "<gray>一共 " + used + " / " + bag.viewPages() * Bag.PAGE_SIZE + " 格有東西", "<gray>點一頁直接打開"));
        for (int i = 0; i < PER_SCREEN; i++) {
            final int p = screen * PER_SCREEN + i;
            if (p >= bag.viewPages()) {
                break;
            }
            final int count = bag.itemCount(p);
            final Material material = count == 0 ? Material.GRAY_STAINED_GLASS_PANE : count >= Bag.PAGE_SIZE ? Material.RED_STAINED_GLASS_PANE
                    : Material.LIME_STAINED_GLASS_PANE;
            ItemStack stack = icon(material, (p == current ? "<#f5b942>▶ " : "<white>") + "第 " + (p + 1) + " 頁",
                    "<gray>" + count + " / " + Bag.PAGE_SIZE + " 格有東西" + (count >= Bag.PAGE_SIZE ? "（滿了）" : ""),
                    "", "<yellow>左鍵：打開這一頁");
            stack = withCount(stack, Math.max(1, Math.min(64, p + 1)));
            set(CONTENT_START + i, p == current ? highlight(stack) : stack);
        }
        if (screens() > 1) {
            set(SLOT_PREV, pageButton(false, screen, screens()));
            set(SLOT_NEXT, pageButton(true, screen, screens()));
        }
        set(SLOT_BACK, backButton("隨身背包"));
        set(SLOT_HELP, helpButton(List.of("綠色：還有空位　紅色：滿了　灰色：空的", "格子上的數字是第幾頁（超過 64 頁的寫 64）",
                "背包裡換頁：◀ ▶ 一頁、Shift 跳 " + JUMP_PAGES + " 頁、右鍵到第一頁／最後一頁")));
        set(SLOT_CLOSE_BAR, closeButton());
    }

    @Override
    protected void onClick(final int slot, final ClickType click, final InventoryClickEvent event) {
        if (slot == SLOT_CLOSE_BAR) {
            goBack();
            return;
        }
        if (slot == SLOT_BACK) {
            new BagMenu(plugin, player, service, bag, current, BagMenu.Filter.NONE).open();
            return;
        }
        if ((slot == SLOT_PREV || slot == SLOT_NEXT) && screens() > 1) {
            screen = Math.floorMod(screen + (slot == SLOT_NEXT ? 1 : -1), screens());
            redraw();
            return;
        }
        if (slot >= CONTENT_START && slot < CONTENT_END) {
            final int p = screen * PER_SCREEN + slot - CONTENT_START;
            if (p < bag.viewPages()) {
                new BagMenu(plugin, player, service, bag, p, BagMenu.Filter.NONE).open();
            }
        }
    }
}
