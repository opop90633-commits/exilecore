package tw.exilecore.admin;

import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.event.ClickEvent;
import net.kyori.adventure.text.event.HoverEvent;
import net.kyori.adventure.text.format.NamedTextColor;
import org.bukkit.Material;
import org.bukkit.Sound;
import org.bukkit.entity.Player;
import org.bukkit.event.inventory.ClickType;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.inventory.ItemStack;
import tw.exilecore.ExileCore;
import tw.exilecore.character.Bag;
import tw.exilecore.character.CharacterRecord;
import tw.exilecore.character.PlayerState;
import tw.exilecore.gui.Menu;
import tw.exilecore.item.ItemData;
import tw.exilecore.item.ItemRenderer;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * 管理員查看玩家（/ec inv 玩家，2026-10）：背包、裝備（含其他裝備組）、隨身背包、倉庫（物品頁）。
 * 每件物品的說明最後寫物品 UUID；點一下在聊天欄送出 UUID（點聊天欄那行就複製）。點最上面的玩家頭像複製玩家 UUID。
 * <p>
 * 隨身背包、倉庫跟倉庫一樣下面有「全部頁籤」一覽與通貨頁（通貨袋）。
 * 回收物品（2026-10，回收 BUG 物品用）：背包、隨身背包、倉庫裡的物品與通貨 Shift＋左鍵拿到自己的背包（通貨一次一疊）、Shift＋右鍵直接刪掉，
 * 都要在 5 秒內按第二次確認。拿之前先關掉對方的介面、確認那格還是同一個東西；拿完存檔、記進流水帳與管理紀錄。
 * 裝備欄只能看（要回收先請對方脫下來，或用管理網頁的「收回物品」）。
 */
public final class InspectMenu extends Menu {

    private enum View {
        INVENTORY("背包", Material.CHEST),
        GEAR("裝備", Material.IRON_CHESTPLATE),
        BAG("隨身背包", Material.BUNDLE),
        STASH("倉庫", Material.ENDER_CHEST);

        private final String title;
        private final Material icon;

        View(final String title, final Material icon) {
            this.title = title;
            this.icon = icon;
        }
    }

    private static final int SLOT_HEAD = 0;
    private static final int[] VIEW_SLOTS = {3, 4, 5, 6};
    /** 一個畫面放幾格（第 2–5 排）：隨身背包、倉庫一頁 45 格，攤平之後一個畫面看 36 格。 */
    private static final int SHOWN = 36;

    /** 畫面上這一格是哪裡的東西（拿的時候用）。 */
    private record Source(View view, int page, int index, ItemStack snapshot) {
    }

    private final Map<Integer, Source> sources = new HashMap<>();
    /** 隨身背包、倉庫的畫面：物品頁、全部頁籤一覽、通貨頁（2026-10）。 */
    private enum Mode { ITEMS, TABS, CURRENCY }
    private static final int SLOT_TABS = 45;
    private static final int SLOT_CURRENCY = 46;
    private Mode mode = Mode.ITEMS;
    /** 一覽裡這一格是第幾頁。 */
    private final Map<Integer, Integer> tabSlots = new HashMap<>();
    /** 通貨頁這一格是哪種通貨。 */
    private final Map<Integer, String> currencySlots = new HashMap<>();
    private int confirmSlot = -1;
    private boolean confirmDelete;
    private long confirmUntil;
    private static final int SLOT_PREV = 47;
    private static final int SLOT_NEXT = 51;
    private static final int SLOT_REFRESH = 49;

    private final UUID targetId;
    private final Map<Integer, String> itemIds = new HashMap<>();
    private View view = View.INVENTORY;
    private int page;

    public InspectMenu(final ExileCore plugin, final Player admin, final Player target) {
        super(plugin, admin, 6, "<dark_gray>查看：" + ItemRenderer.escape(target.getName()));
        this.targetId = target.getUniqueId();
    }

    private Player target() {
        return plugin.getServer().getPlayer(targetId);
    }

    @Override
    protected void draw() {
        frame();
        itemIds.clear();
        sources.clear();
        tabSlots.clear();
        currencySlots.clear();
        final Player target = target();
        if (target == null) {
            set(22, icon(Material.BARRIER, "<red>玩家已經離線"));
            set(SLOT_CLOSE_BAR, closeButton());
            return;
        }
        final PlayerState state = plugin.players().get(target);
        final CharacterRecord character = state.isLoaded() ? state.character() : null;
        final ItemStack head = icon(Material.PLAYER_HEAD, "<#f5b942>" + ItemRenderer.escape(target.getName()),
                "<gray>玩家 UUID：<white>" + target.getUniqueId(),
                character == null ? "<dark_gray>還沒選角色" : "<gray>角色：<white>" + ItemRenderer.escape(character.name())
                        + " <gray>Lv." + state.level() + "（角色編號 " + character.id() + "）",
                "", "<yellow>左鍵：在聊天欄送出玩家 UUID（點那行複製）");
        head.editMeta(org.bukkit.inventory.meta.SkullMeta.class, meta -> meta.setOwningPlayer(target));
        set(SLOT_HEAD, head);
        for (int i = 0; i < View.values().length; i++) {
            final View each = View.values()[i];
            set(VIEW_SLOTS[i], tab(each.icon, each.title, each == view, List.of("<gray>看" + each.title)));
        }
        set(SLOT_REFRESH, icon(Material.CLOCK, "<white>重新整理", "<gray>看最新的狀態", "", "<yellow>左鍵：重新整理"));
        set(SLOT_CLOSE_BAR, closeButton());
        switch (view) {
            case INVENTORY -> drawInventory(target);
            case GEAR -> drawGear(state);
            case BAG, STASH -> drawStorageView(target, state);
        }
    }

    /** 背包：第 1 排快捷列，下面三排是背包上面三排（跟遊戲裡的位置一樣）。 */
    private void drawInventory(final Player target) {
        final ItemStack[] contents = target.getInventory().getStorageContents();
        for (int i = 0; i < 9 && i < contents.length; i++) {
            show(9 + i, contents[i], new Source(View.INVENTORY, 0, i, contents[i])); // 快捷列放在最上面一排
        }
        for (int i = 9; i < 36 && i < contents.length; i++) {
            show(18 + (i - 9), contents[i], new Source(View.INVENTORY, 0, i, contents[i]));
        }
    }

    /** 裝備：一次看一組（換頁看其他裝備組），十個裝備欄照順序排在第 2、3 排。 */
    private void drawGear(final PlayerState state) {
        final int active = state.activeGearSet();
        page = Math.max(0, Math.min(page, PlayerState.GEAR_SETS - 1));
        final ItemStack[] items = page == active ? state.jewellery() : state.gearSet(page);
        set(13, icon(Material.PAPER, page == active ? "<green>第 " + (page + 1) + " 組（使用中）"
                : "<gray>第 " + (page + 1) + " 組（放著，不生效）", "<dark_gray>下面左右換頁看其他裝備組"));
        for (int i = 0; i < PlayerState.JEWELLERY_SLOTS.length; i++) {
            final int at = i < 9 ? 18 + i : 31; // 第 10 個（鞋子）放在下一排中間
            final ItemStack stack = items[i];
            if (stack == null || stack.isEmpty()) {
                set(at, uiIcon(tw.exilecore.character.menu.CharacterPanelMenu.slotShape(PlayerState.JEWELLERY_SLOTS[i]),
                        Material.LIGHT_GRAY_STAINED_GLASS_PANE, "<dark_gray>" + PlayerState.JEWELLERY_SLOTS[i].displayName() + "：空"));
            } else {
                show(at, stack, null);
            }
        }
        set(SLOT_PREV, pageButton(false, page, PlayerState.GEAR_SETS));
        set(SLOT_NEXT, pageButton(true, page, PlayerState.GEAR_SETS));
    }

    private static List<ItemStack[]> bagPages(final PlayerState state) {
        final List<ItemStack[]> list = new ArrayList<>();
        final int pages = Math.max(state.bag().pages(), state.bag().storedPages());
        for (int p = 0; p < pages; p++) {
            list.add(state.bag().page(p));
        }
        return list;
    }

    private static List<ItemStack[]> stashPages(final tw.exilecore.stash.Stash stash) {
        final List<ItemStack[]> list = new ArrayList<>();
        for (int p = 0; p < stash.pageCount(); p++) {
            list.add(stash.page(p));
        }
        return list;
    }

    /** 隨身背包、倉庫：下面一排是「全部頁籤」「通貨頁」（跟倉庫一樣），中間照模式畫。 */
    private void drawStorageView(final Player target, final PlayerState state) {
        final List<ItemStack[]> pages = view == View.BAG ? bagPages(state)
                : plugin.stash() == null ? List.of() : plugin.stash().stash(target).map(InspectMenu::stashPages).orElse(List.of());
        set(SLOT_TABS, tab(view == View.BAG ? Material.BUNDLE : Material.CHEST, "全部頁籤", mode == Mode.TABS,
                List.of("<gray>每一頁一格，點一下跳過去（" + pages.size() + " 頁）")));
        set(SLOT_CURRENCY, tab(Material.SUNFLOWER, view == View.BAG ? "通貨袋" : "通貨頁", mode == Mode.CURRENCY,
                List.of("<gray>看" + (view == View.BAG ? "隨身背包的通貨袋" : "倉庫的通貨頁"))));
        switch (mode) {
            case TABS -> drawTabs(target, pages);
            case CURRENCY -> drawCurrencies(currencies(target, state));
            default -> drawStorage(pages, view);
        }
    }

    private Map<String, Integer> currencies(final Player target, final PlayerState state) {
        if (view == View.BAG) {
            return state.bag().pouch().all();
        }
        return plugin.stash() == null ? Map.of() : plugin.stash().stash(target).map(tw.exilecore.stash.Stash::currencies).orElse(Map.of());
    }

    private void drawTabs(final Player target, final List<ItemStack[]> pages) {
        final tw.exilecore.stash.Stash stash = view == View.STASH && plugin.stash() != null ? plugin.stash().stash(target).orElse(null) : null;
        for (int p = 0; p < pages.size() && p < SHOWN; p++) {
            int count = 0;
            for (final ItemStack stack : pages.get(p)) {
                count += stack != null && !stack.isEmpty() ? 1 : 0;
            }
            final boolean locked = stash != null && stash.locked(p);
            final String name = stash != null ? stash.tabLabel(p) : "第 " + (p + 1) + " 頁";
            final List<String> lore = new ArrayList<>();
            lore.add("<gray>" + count + " / " + pages.get(p).length + " 格");
            if (locked) {
                lore.add("<red>收回的頁（只能拿出來）");
            }
            lore.add("");
            lore.add("<yellow>左鍵：看這一頁");
            set(9 + p, icon(locked ? Material.IRON_BARS : count > 0 ? Material.CHEST : Material.BARREL, (locked ? "<red>" : "<white>")
                    + ItemRenderer.escape(name), lore));
            tabSlots.put(9 + p, p);
        }
        if (pages.isEmpty()) {
            set(22, icon(Material.BARRIER, "<gray>" + view.title + "是空的（或還沒載入）"));
        }
    }

    private void drawCurrencies(final Map<String, Integer> amounts) {
        final List<Map.Entry<String, Integer>> list = new ArrayList<>(amounts.entrySet());
        list.removeIf(e -> e.getValue() == null || e.getValue() <= 0);
        list.sort(Map.Entry.comparingByKey());
        final int screens = Math.max(1, (list.size() + SHOWN - 1) / SHOWN);
        page = Math.max(0, Math.min(page, screens - 1));
        if (list.isEmpty()) {
            set(22, icon(Material.BARRIER, "<gray>沒有通貨"));
            return;
        }
        final boolean confirming = System.currentTimeMillis() < confirmUntil;
        for (int k = 0; k < SHOWN && page * SHOWN + k < list.size(); k++) {
            final Map.Entry<String, Integer> entry = list.get(page * SHOWN + k);
            final tw.exilecore.currency.CurrencyType type = plugin.currencies().repository().get(entry.getKey());
            final int slot = 9 + k;
            ItemStack stack;
            if (type == null) {
                stack = icon(Material.PAPER, "<gray>" + ItemRenderer.escape(entry.getKey()), "<dark_gray>（資料檔裡沒有這種通貨）");
            } else {
                stack = plugin.currencies().create(type, Math.max(1, Math.min(entry.getValue(), type.stack())));
            }
            final boolean pending = confirming && slot == confirmSlot;
            appendLore(stack, "", String.format(java.util.Locale.ROOT, "<gray>存了 <white>%,d</white> 個", entry.getValue()),
                    pending ? (confirmDelete ? "<red>▶ 再按一次 Shift＋右鍵：確定全部刪掉" : "<#ffd27f>▶ 再按一次 Shift＋左鍵：確定拿一疊")
                            : "<#ffd27f>Shift＋左鍵：拿一疊到自己的背包<gray>　<red>Shift＋右鍵：全部刪掉");
            set(slot, pending ? glowing(stack) : stack);
            currencySlots.put(slot, entry.getKey());
        }
        if (screens > 1) {
            set(SLOT_PREV, pageButton(false, page, screens));
            set(SLOT_NEXT, pageButton(true, page, screens));
        }
    }

    /** 隨身背包、倉庫：所有頁攤平，一個畫面看 36 格（一頁 45 格的後面 9 格在下一個畫面）。 */
    private void drawStorage(final List<ItemStack[]> pages, final View which) {
        final int size = pages.isEmpty() ? 0 : pages.getFirst().length;
        final int total = pages.size() * size;
        final int screens = Math.max(1, (total + SHOWN - 1) / SHOWN);
        page = Math.max(0, Math.min(page, screens - 1));
        if (total == 0) {
            set(22, icon(Material.BARRIER, "<gray>" + which.title + "是空的（或還沒載入）"));
            return;
        }
        final int from = page * SHOWN;
        for (int k = 0; k < SHOWN && from + k < total; k++) {
            final int p = (from + k) / size;
            final int index = (from + k) % size;
            final ItemStack stack = pages.get(p)[index];
            show(9 + k, stack, new Source(which, p, index, stack));
        }
        final int lastFlat = Math.min(total, from + SHOWN) - 1;
        set(SLOT_REFRESH - 1, icon(Material.PAPER, "<gray>第 " + (from / size + 1) + " 頁第 " + (from % size + 1) + " 格 ～ 第 "
                + (lastFlat / size + 1) + " 頁第 " + (lastFlat % size + 1) + " 格", "<dark_gray>（共 " + pages.size() + " 頁，一頁 " + size + " 格）"));
        if (screens > 1) {
            set(SLOT_PREV, pageButton(false, page, screens));
            set(SLOT_NEXT, pageButton(true, page, screens));
        }
    }

    /** 放一件物品的複本（說明最後加上物品 UUID；source 不是 null 的可以拿走）。 */
    private void show(final int slot, final ItemStack stack, final Source source) {
        if (stack == null || stack.isEmpty()) {
            return;
        }
        final boolean confirming = slot == confirmSlot && System.currentTimeMillis() < confirmUntil;
        final ItemStack copy = stack.clone();
        final String id = plugin.items().read(stack).map(ItemData::uuid).orElse(null);
        copy.editMeta(meta -> {
            final List<Component> lore = meta.lore() == null ? new ArrayList<>() : new ArrayList<>(meta.lore());
            lore.add(ItemRenderer.line(""));
            lore.add(ItemRenderer.line(id == null ? "<dark_gray>（沒有物品 UUID：不是裝備）" : "<gray>物品 UUID：<white>" + id));
            if (id != null) {
                lore.add(ItemRenderer.line("<yellow>左鍵：在聊天欄送出 UUID（點那行複製）"));
            }
            if (source != null) {
                if (confirming) {
                    lore.add(ItemRenderer.line(confirmDelete ? "<red>▶ 再按一次 Shift＋右鍵：確定刪掉" : "<#ffd27f>▶ 再按一次 Shift＋左鍵：確定拿走"));
                } else {
                    lore.add(ItemRenderer.line("<#ffd27f>Shift＋左鍵：拿到自己的背包<gray>　<red>Shift＋右鍵：刪掉"));
                }
            }
            meta.lore(lore);
        });
        if (id != null) {
            itemIds.put(slot, id);
        }
        if (source != null) {
            sources.put(slot, source);
        }
        set(slot, confirming ? glowing(copy) : copy);
    }

    @Override
    protected void onClick(final int slot, final ClickType click, final InventoryClickEvent event) {
        if (slot == SLOT_CLOSE_BAR) {
            goBack();
            return;
        }
        if (!acceptAction(click)) {
            return;
        }
        for (int i = 0; i < VIEW_SLOTS.length; i++) {
            if (VIEW_SLOTS[i] == slot) {
                view = View.values()[i];
                page = 0;
                mode = Mode.ITEMS;
                confirmSlot = -1;
                redraw();
                return;
            }
        }
        if ((view == View.BAG || view == View.STASH) && (slot == SLOT_TABS || slot == SLOT_CURRENCY)) {
            final Mode next = slot == SLOT_TABS ? Mode.TABS : Mode.CURRENCY;
            mode = mode == next ? Mode.ITEMS : next;
            page = 0;
            confirmSlot = -1;
            redraw();
            return;
        }
        final Integer tabPage = tabSlots.get(slot);
        if (tabPage != null) {
            final Player t = target();
            final List<ItemStack[]> pages = t == null ? List.of() : view == View.BAG ? bagPages(plugin.players().get(t))
                    : plugin.stash() == null ? List.of() : plugin.stash().stash(t).map(InspectMenu::stashPages).orElse(List.of());
            final int size = pages.isEmpty() ? 45 : pages.getFirst().length;
            mode = Mode.ITEMS;
            page = tabPage * size / SHOWN;
            redraw();
            return;
        }
        final String currencyId = currencySlots.get(slot);
        if (currencyId != null && (click == ClickType.SHIFT_LEFT || click == ClickType.SHIFT_RIGHT)) {
            final boolean delete = click == ClickType.SHIFT_RIGHT;
            final long now = System.currentTimeMillis();
            if (confirmSlot != slot || confirmDelete != delete || now > confirmUntil) {
                confirmSlot = slot;
                confirmDelete = delete;
                confirmUntil = now + 5000;
                player.playSound(player.getLocation(), Sound.UI_BUTTON_CLICK, 0.6f, 0.8f);
                redraw();
                return;
            }
            confirmSlot = -1;
            takeCurrency(currencyId, delete);
            redraw();
            return;
        }
        if (slot == SLOT_REFRESH) {
            redraw();
            return;
        }
        if (view != View.INVENTORY && mode != Mode.TABS && (slot == SLOT_PREV || slot == SLOT_NEXT)) {
            final int pages = view == View.GEAR ? PlayerState.GEAR_SETS : mode == Mode.CURRENCY ? currencyScreens() : storageScreens();
            page = Math.floorMod(page + (slot == SLOT_NEXT ? 1 : -1), Math.max(1, pages));
            confirmSlot = -1;
            redraw();
            return;
        }
        final Source source = sources.get(slot);
        if (source != null && (click == ClickType.SHIFT_LEFT || click == ClickType.SHIFT_RIGHT)) {
            final boolean delete = click == ClickType.SHIFT_RIGHT;
            final long now = System.currentTimeMillis();
            if (confirmSlot != slot || confirmDelete != delete || now > confirmUntil) {
                confirmSlot = slot;
                confirmDelete = delete;
                confirmUntil = now + 5000;
                player.playSound(player.getLocation(), Sound.UI_BUTTON_CLICK, 0.6f, 0.8f);
                redraw();
                return;
            }
            confirmSlot = -1;
            take(source, delete);
            redraw();
            return;
        }
        if (slot == SLOT_HEAD) {
            copyLine("玩家 UUID", targetId.toString());
            return;
        }
        final String id = itemIds.get(slot);
        if (id != null) {
            copyLine("物品 UUID", id);
        }
    }

    private int currencyScreens() {
        final Player target = target();
        if (target == null) {
            return 1;
        }
        final long kinds = currencies(target, plugin.players().get(target)).values().stream().filter(v -> v != null && v > 0).count();
        return (int) Math.max(1, (kinds + SHOWN - 1) / SHOWN);
    }

    /** 拿走一疊（或全部刪掉）某種通貨：通貨袋或倉庫的通貨頁。 */
    private void takeCurrency(final String id, final boolean delete) {
        final Player target = target();
        if (target == null) {
            player.sendMessage(plugin.text().prefixed("<red>玩家已經離線"));
            return;
        }
        final PlayerState state = plugin.players().get(target);
        if (!state.isLoaded() || !state.hasCharacter()) {
            player.sendMessage(plugin.text().prefixed("<red>對方的角色還沒載入"));
            return;
        }
        if (!target.equals(player)) {
            target.closeInventory();
        }
        final tw.exilecore.currency.CurrencyType type = plugin.currencies().repository().get(id);
        final int stackSize = type == null ? Integer.MAX_VALUE : type.stack();
        final int taken;
        if (view == View.BAG) {
            final int have = state.bag().pouch().amount(id);
            taken = state.bag().pouch().withdraw(id, delete ? have : Math.min(have, stackSize));
        } else {
            final tw.exilecore.stash.Stash stash = plugin.stash() == null ? null : plugin.stash().stash(target).orElse(null);
            if (stash == null) {
                player.sendMessage(plugin.text().prefixed("<red>對方的倉庫還沒載入"));
                return;
            }
            final int have = stash.currency(id);
            taken = delete ? have : Math.min(have, stackSize);
            stash.setCurrency(id, have - taken);
        }
        if (taken <= 0) {
            player.sendMessage(plugin.text().prefixed("<red>那種通貨已經沒有了（對方剛動過），重新整理再試"));
            return;
        }
        state.markDirty();
        plugin.characters().save(target);
        final String name = type == null ? id : type.name();
        if (!delete && type != null) {
            final tw.exilecore.economy.Wallet wallet = plugin.economy().wallet();
            final ItemStack rest = wallet.give(player, plugin.currencies().create(type, taken));
            if (rest != null && !rest.isEmpty()) {
                wallet.dropFor(player, rest);
            }
        }
        final String where = view == View.BAG ? "通貨袋" : "倉庫通貨頁";
        player.sendMessage(plugin.text().prefixed((delete ? "<red>刪掉了 " : "<green>拿走了 ") + target.getName() + " " + where + " 的 <white>"
                + ItemRenderer.escape(name) + " ×" + taken));
        player.playSound(player.getLocation(), delete ? Sound.ENTITY_ITEM_BREAK : Sound.ENTITY_ITEM_PICKUP, 0.6f, 1f);
        plugin.getSLF4JLogger().info("[管理] {} {} {} {} 的 {} ×{}", player.getName(), delete ? "刪掉" : "拿走", target.getName(), where, name, taken);
        if (delete && plugin.economy() != null) {
            // 2026-10-18：記在 ADMIN_TAKE，不跟管理員發放同一列互相抵掉
            plugin.economy().ledger().consumed(id, tw.exilecore.economy.EconomyLedger.ADMIN_TAKE, taken); // 刪掉的算消耗（拿走的只是換人放）
        }
        final tw.exilecore.economy.TradeLogRepository.Entry entry = new tw.exilecore.economy.TradeLogRepository.Entry(UUID.randomUUID().toString(),
                tw.exilecore.economy.TradeLogRepository.ADMIN, null, (delete ? "刪除" : "收回") + name, Math.max(1, Math.min(Integer.MAX_VALUE, taken)), null,
                player.getName(), target.getUniqueId(), target.getName());
        final String detail = target.getName() + " " + where + "：" + name + " ×" + taken;
        plugin.database().async(connection -> {
            plugin.economy().tradeLog().insert(connection, List.of(entry));
            if (plugin.admin() != null && plugin.admin().repository() != null) {
                plugin.admin().repository().log(connection, player.getName(), delete ? "刪除通貨" : "收回通貨", detail);
            }
            return null;
        }).whenComplete((ignored, error) -> {
            if (error != null) {
                plugin.getSLF4JLogger().warn("管理員回收通貨的紀錄寫不進去：{}", error.toString());
            }
        });
    }

    private int storageScreens() {
        final Player target = target();
        if (target == null) {
            return 1;
        }
        final List<ItemStack[]> pages = view == View.BAG ? bagPages(plugin.players().get(target))
                : plugin.stash() == null ? List.of() : plugin.stash().stash(target).map(InspectMenu::stashPages).orElse(List.of());
        final int total = pages.isEmpty() ? 0 : pages.size() * pages.getFirst().length;
        return Math.max(1, (total + SHOWN - 1) / SHOWN);
    }

    /** 拿走（或刪掉）一件：先關對方的介面、確認那格還是同一個東西，拿掉、存檔、記紀錄。 */
    private void take(final Source source, final boolean delete) {
        final Player target = target();
        if (target == null) {
            player.sendMessage(plugin.text().prefixed("<red>玩家已經離線"));
            return;
        }
        final PlayerState state = plugin.players().get(target);
        if (!state.isLoaded() || !state.hasCharacter()) {
            player.sendMessage(plugin.text().prefixed("<red>對方的角色還沒載入"));
            return;
        }
        if (!target.equals(player)) {
            target.closeInventory(); // 對方開著的介面先關掉（介面裡的東西寫回去），畫面跟資料才一致
        }
        final ItemStack[] place;
        switch (source.view()) {
            case INVENTORY -> place = null;
            case BAG -> place = state.bag().page(source.page());
            case STASH -> {
                final tw.exilecore.stash.Stash stash = plugin.stash() == null ? null : plugin.stash().stash(target).orElse(null);
                if (stash == null || source.page() >= stash.pageCount()) {
                    player.sendMessage(plugin.text().prefixed("<red>對方的倉庫還沒載入"));
                    return;
                }
                place = stash.page(source.page());
            }
            default -> {
                return;
            }
        }
        final ItemStack current = place == null ? target.getInventory().getItem(source.index()) : place[source.index()];
        if (current == null || current.isEmpty() || !current.equals(source.snapshot())) {
            player.sendMessage(plugin.text().prefixed("<red>那一格的東西已經變了（對方剛動過），重新整理再試"));
            return;
        }
        final ItemStack stack = current.clone();
        if (place == null) {
            target.getInventory().setItem(source.index(), null);
        } else {
            place[source.index()] = null;
        }
        state.markDirty();
        plugin.characters().save(target);
        final String what = tw.exilecore.economy.Wallet.describe(stack);
        final String uuid = plugin.items().read(stack).map(ItemData::uuid).orElse(null);
        final String where = source.view().title + (source.view() == View.INVENTORY ? "" : " 第 " + (source.page() + 1) + " 頁第 " + (source.index() + 1) + " 格");
        if (!delete) {
            final tw.exilecore.economy.Wallet wallet = plugin.economy().wallet();
            final ItemStack rest = wallet.give(player, stack);
            if (rest != null && !rest.isEmpty()) {
                wallet.dropFor(player, rest); // 背包滿了：掉在自己腳下（只有自己看得到）
            }
        }
        player.sendMessage(plugin.text().prefixed((delete ? "<red>刪掉了 " : "<green>拿走了 ") + target.getName() + " " + where + " 的 <white>"
                + ItemRenderer.escape(what) + (stack.getAmount() > 1 ? " ×" + stack.getAmount() : "")));
        player.playSound(player.getLocation(), delete ? Sound.ENTITY_ITEM_BREAK : Sound.ENTITY_ITEM_PICKUP, 0.6f, 1f);
        plugin.getSLF4JLogger().info("[管理] {} {} {} 的 {}（{}）×{}，物品 UUID {}", player.getName(), delete ? "刪掉" : "拿走",
                target.getName(), what, where, stack.getAmount(), uuid);
        // 流水帳與管理紀錄
        final String opId = UUID.randomUUID().toString();
        final tw.exilecore.economy.TradeLogRepository.Entry entry = new tw.exilecore.economy.TradeLogRepository.Entry(opId,
                tw.exilecore.economy.TradeLogRepository.ADMIN, uuid, (delete ? "刪除" : "收回") + what, Math.max(1, stack.getAmount()), null,
                player.getName(), target.getUniqueId(), target.getName());
        final String detail = target.getName() + " " + where + "：" + what + " ×" + stack.getAmount() + (uuid == null ? "" : "（" + uuid + "）");
        plugin.database().async(connection -> {
            plugin.economy().tradeLog().insert(connection, List.of(entry));
            if (plugin.admin() != null && plugin.admin().repository() != null) {
                plugin.admin().repository().log(connection, player.getName(), delete ? "刪除物品" : "收回物品", detail);
            }
            return null;
        }).whenComplete((ignored, error) -> {
            if (error != null) {
                plugin.getSLF4JLogger().warn("管理員回收物品的紀錄寫不進去：{}", error.toString());
            }
        });
    }

    /** 在聊天欄送出一行可以點了複製的文字。 */
    private void copyLine(final String what, final String value) {
        player.sendMessage(plugin.text().prefixed("<gray>" + what + "：").append(Component.text(value, NamedTextColor.WHITE)
                .clickEvent(ClickEvent.copyToClipboard(value))
                .hoverEvent(HoverEvent.showText(Component.text("點一下複製", NamedTextColor.YELLOW))))
                .append(Component.text("  [複製]", NamedTextColor.YELLOW).clickEvent(ClickEvent.copyToClipboard(value))));
        player.playSound(player.getLocation(), Sound.UI_BUTTON_CLICK, 0.5f, 1.4f);
    }
}
