package tw.exilecore.map;

import org.bukkit.Material;
import org.bukkit.Sound;
import org.bukkit.entity.Player;
import org.bukkit.event.inventory.ClickType;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.inventory.ItemStack;
import tw.exilecore.ExileCore;
import tw.exilecore.gui.Menu;
import tw.exilecore.item.ItemData;
import tw.exilecore.item.ItemRenderer;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

/**
 * 星圖上左鍵一格（2026-10-21）：列出背包與倉庫地圖頁裡這種地圖，讓玩家自己挑一張。
 * 左鍵：放上地圖裝置（可以先補品質、詞綴再開）；Shift＋左鍵：直接開。格子已經在星圖那邊指定好了（MapService.chooseCell）。
 */
public final class AtlasMapPickMenu extends Menu {

    private static final int LIST_SLOTS = 45;
    private static final int SLOT_BACK = 45;
    private static final int SLOT_INFO = 49;
    private static final int SLOT_CLOSE = 53;

    /** 一張可以選的地圖：在背包哪一格（倉庫的是 -1）、倉庫裡的那個物品（背包的是 null）。 */
    private record Candidate(int inventorySlot, ItemStack stashItem, ItemStack shown, ItemData data, int rank) {
    }

    private final MapService service;
    private final MapConfig.MapDef map;
    private final AtlasGrid.Cell cell;
    private final List<Candidate> candidates = new ArrayList<>();
    /** 第幾頁（2026-10-26：超過 45 張可以翻頁，以前只列最高的 45 張）。 */
    private int page;

    public AtlasMapPickMenu(final ExileCore plugin, final Player player, final MapService service, final MapConfig.MapDef map,
                            final AtlasGrid.Cell cell) {
        super(plugin, player, 6, "<#9fd3ff>選一張 " + ItemRenderer.escape(map.name()));
        this.service = service;
        this.map = map;
        this.cell = cell;
    }

    /** 背包、倉庫裡有沒有這種地圖（沒有就不用開這個選單）。 */
    static boolean anyFor(final ExileCore plugin, final Player player, final MapService service, final MapConfig.MapDef map) {
        final AtlasMapPickMenu probe = new AtlasMapPickMenu(plugin, player, service, map, null);
        probe.collect();
        return !probe.candidates.isEmpty();
    }

    private void collect() {
        candidates.clear();
        for (int i = 0; i < 36; i++) {
            final ItemStack stack = player.getInventory().getItem(i);
            final ItemData data = mapOf(stack);
            if (data != null) {
                candidates.add(new Candidate(i, null, stack, data, rank(data)));
            }
        }
        final tw.exilecore.stash.Stash stash = plugin.stash() == null ? null : plugin.stash().stash(player).orElse(null);
        if (stash != null) {
            for (final ItemStack stack : stash.maps()) {
                final ItemData data = mapOf(stack);
                if (data != null) {
                    candidates.add(new Candidate(-1, stack, stack, data, rank(data)));
                }
            }
        }
        candidates.sort(Comparator.comparingInt(Candidate::rank).reversed());
    }

    @Override
    protected void draw() {
        collect();
        final int pages = pages();
        page = Math.max(0, Math.min(page, pages - 1));
        for (int i = 0; i < LIST_SLOTS; i++) {
            final int index = page * LIST_SLOTS + i;
            if (index >= candidates.size()) {
                set(i, null);
                continue;
            }
            final Candidate candidate = candidates.get(index);
            final ItemStack shown = candidate.shown().clone();
            appendLore(shown, List.of("", candidate.inventorySlot() >= 0 ? "<gray>在背包裡" : "<gray>在倉庫的地圖頁",
                    "<yellow>左鍵：放上地圖裝置<gray>（可以先補品質、詞綴再開）", "<yellow>Shift＋左鍵：直接開"));
            set(i, shown);
        }
        set(SLOT_BACK, uiIcon("nav_back", Material.FILLED_MAP, "<#f5b942>← 星圖", "<gray>回到星圖（這一格已經指定好了）"));
        set(SLOT_INFO, icon(Material.COMPASS, "<white>" + ItemRenderer.escape(map.name()) + " <dark_gray>（"
                        + (cell == null ? "?" : AtlasMenu.where(cell.q(), cell.r())) + "）",
                "<gray>背包與倉庫裡的這種地圖：<white>" + candidates.size() + "<gray> 張",
                pages > 1 ? "<dark_gray>階級高的排前面（第 " + (page + 1) + "／" + pages + " 頁）" : "<dark_gray>階級高的排前面"));
        set(Menu.SLOT_PREV, pages > 1 ? pageButton(false, page, pages) : null);
        set(Menu.SLOT_NEXT, pages > 1 ? pageButton(true, page, pages) : null);
        set(SLOT_CLOSE, closeButton());
    }

    private int pages() {
        return Math.max(1, (candidates.size() + LIST_SLOTS - 1) / LIST_SLOTS);
    }

    @Override
    protected void onClick(final int slot, final ClickType click, final InventoryClickEvent event) {
        if (slot == SLOT_BACK) {
            new AtlasMenu(plugin, player, service).open();
            return;
        }
        if (slot == SLOT_CLOSE) {
            goBack();
            return;
        }
        if ((slot == Menu.SLOT_PREV || slot == Menu.SLOT_NEXT) && pages() > 1) {
            page = Math.floorMod(page + (slot == Menu.SLOT_NEXT ? 1 : -1), pages());
            player.playSound(player.getLocation(), Sound.ITEM_BOOK_PAGE_TURN, 0.6f, 1.0f);
            redraw();
            return;
        }
        final int picked = page * LIST_SLOTS + slot;
        if (slot < 0 || slot >= LIST_SLOTS || picked >= candidates.size() || !acceptAction(click) || click.isRightClick()) {
            return;
        }
        final Candidate candidate = candidates.get(picked);
        int inventorySlot = candidate.inventorySlot();
        ItemData data = candidate.data();
        if (inventorySlot >= 0) {
            final ItemData now = mapOf(player.getInventory().getItem(inventorySlot));
            if (now == null || !now.uuid().equals(data.uuid())) {
                tw.exilecore.gui.ActionBar.send(player, plugin.text().parse("<red>那張地圖已經不在背包的那一格了"));
                redraw();
                return;
            }
        } else {
            // 倉庫的：拿一張到背包（照地圖裝置從倉庫拿的做法）
            final tw.exilecore.stash.Stash stash = plugin.stash() == null ? null : plugin.stash().stash(player).orElse(null);
            int index = -1;
            if (stash != null) {
                for (int i = 0; i < stash.maps().size(); i++) {
                    if (stash.maps().get(i) == candidate.stashItem()) {
                        index = i;
                        break;
                    }
                }
            }
            if (index < 0) {
                tw.exilecore.gui.ActionBar.send(player, plugin.text().parse("<red>那張地圖已經不在倉庫了"));
                redraw();
                return;
            }
            final int free = player.getInventory().firstEmpty();
            if (free < 0 || free >= 36) {
                tw.exilecore.gui.ActionBar.send(player, plugin.text().parse("<red>背包沒有空格：先空出一格才能從倉庫拿地圖"));
                return;
            }
            final ItemStack taken = stash.takeMap(index);
            if (taken == null) {
                redraw();
                return;
            }
            player.getInventory().setItem(free, taken);
            plugin.players().markDirty(player);
            inventorySlot = free;
            data = plugin.items().read(taken).orElse(data);
        }
        player.playSound(player.getLocation(), Sound.UI_BUTTON_CLICK, 0.4f, 1.5f);
        if (click.isShiftClick()) {
            final String problem = service.activate(player, inventorySlot, data);
            if (problem != null) {
                player.sendMessage(plugin.text().prefixed("<red>" + ItemRenderer.escape(problem)));
                redraw();
                return;
            }
            player.closeInventory();
            return;
        }
        final MapDeviceMenu device = new MapDeviceMenu(plugin, player, service);
        device.preselect(inventorySlot, data);
        device.open();
    }

    /** 這個物品是不是這種地圖；是的話回傳它的資料。 */
    private ItemData mapOf(final ItemStack stack) {
        if (stack == null || stack.isEmpty()) {
            return null;
        }
        final ItemData data = plugin.items().read(stack).orElse(null);
        if (data == null || !service.isMap(data)) {
            return null;
        }
        final MapConfig.MapDef def = service.config().byBase(data.base());
        return def != null && def.id().equals(map.id()) ? data : null;
    }

    /** 排序：階級高的先，同階稀有度高的先。 */
    private int rank(final ItemData data) {
        final tw.exilecore.item.ItemBase base = plugin.items().repository().base(data.base());
        final int tier = base == null ? 0 : tw.exilecore.item.ItemStats.mapTier(base, data);
        return tier * 10 + data.rarity().ordinal();
    }
}
