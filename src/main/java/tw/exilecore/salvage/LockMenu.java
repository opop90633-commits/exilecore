package tw.exilecore.salvage;

import org.bukkit.Material;
import org.bukkit.Sound;
import org.bukkit.entity.Player;
import org.bukkit.event.inventory.ClickType;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.inventory.ItemStack;
import tw.exilecore.ExileCore;
import tw.exilecore.character.Gear;
import tw.exilecore.character.PlayerState;
import tw.exilecore.gui.Menu;
import tw.exilecore.item.ItemData;
import tw.exilecore.item.Rarity;

import java.util.Optional;

/**
 * 鎖定裝備（/lock）：上面 4 列是你的背包（前 3 列＝背包、第 4 列＝快捷列），第 5 列是身上穿的與副手，
 * 點一件就鎖定或解除鎖定。鎖定的裝備（2026-10-31 起技能、輔助寶石也能鎖）拆解台、市集、交易、垃圾桶都不收，避免把要的裝備不小心拆掉；
 * 鎖定記在物品上（放進倉庫、給別人都還鎖著）。
 */
public final class LockMenu extends Menu {

    private static final int MIRROR_SLOTS = 36;
    /** 第 5 列：頭盔、胸甲、護腿、鞋子、副手。 */
    private static final int WORN_START = 36;
    private static final int[] WORN_INVENTORY = {39, 38, 37, 36, 40};
    private static final int SLOT_LOCK_UNIQUES = 46;
    private static final int SLOT_LOCK_RARES = 47;
    private static final int SLOT_UNLOCK_ALL = 48;
    private static final int SLOT_LOCK_GEMS = 49; // 2026-10-31
    private static final int SLOT_CLOSE = 53;

    public LockMenu(final ExileCore plugin, final Player player) {
        super(plugin, player, 6, "<dark_gray>鎖定裝備");
    }

    /** 選單的格子 → 背包格（0–26 對背包 9–35，27–35 對快捷列 0–8，36–40 對身上穿的與副手）。 */
    static int inventorySlot(final int menuSlot) {
        if (menuSlot >= WORN_START) {
            return WORN_INVENTORY[menuSlot - WORN_START];
        }
        return menuSlot < 27 ? menuSlot + 9 : menuSlot - 27;
    }

    /**
     * 2026-10-18：這一格真的物品。身上穿的、快捷列第 9 格放的是 Gear 的顯示用複本（沒有物品資料，以前怎麼點都鎖不到），
     * 要讀角色面板裝備欄裡的那件。
     */
    private ItemStack realStack(final int inventorySlot) {
        if (Gear.indexAt(inventorySlot) >= 0) {
            return Gear.realAt(plugin.players().get(player), inventorySlot);
        }
        return player.getInventory().getItem(inventorySlot);
    }

    /** 2026-10-18：改好鎖定的物品寫回去（裝備欄的換成新的那件、標記要存檔，背包格是原地改的不用動）。 */
    private void storeBack(final int inventorySlot, final ItemStack stack) {
        final int index = Gear.indexAt(inventorySlot);
        if (index < 0) {
            return;
        }
        final PlayerState state = plugin.players().get(player);
        state.setJewellery(index, stack);
        plugin.players().markDirty(player);
    }

    /** 2026-10-18：要改的那一件（裝備欄的先複製一份，改完再整件寫回）。 */
    private ItemStack editable(final int inventorySlot) {
        final ItemStack stack = realStack(inventorySlot);
        return stack != null && Gear.indexAt(inventorySlot) >= 0 ? stack.clone() : stack;
    }

    @Override
    protected void draw() {
        for (int slot = 0; slot < WORN_START + WORN_INVENTORY.length; slot++) {
            final ItemStack stack = realStack(inventorySlot(slot));
            if (!tw.exilecore.item.Locks.lockable(plugin, stack)) {
                set(slot, slot >= WORN_START ? icon(Material.LIGHT_GRAY_STAINED_GLASS_PANE, "<dark_gray>（身上沒穿東西）") : null);
                continue;
            }
            final boolean locked = tw.exilecore.item.Locks.locked(plugin, stack);
            final ItemStack shown = appendLore(stack.clone(), "",
                    locked ? "<#ffd27f>🔒 已鎖定：拆解台、市集、交易、垃圾桶都不收" : "<dark_gray>沒鎖定",
                    locked ? "<yellow>點一下：解除鎖定" : "<yellow>點一下：鎖定");
            set(slot, locked ? glowing(shown) : shown);
        }
        for (int slot = WORN_START + WORN_INVENTORY.length; slot < 54; slot++) {
            set(slot, filler());
        }
        set(SLOT_LOCK_UNIQUES, icon(Material.ORANGE_DYE, "<#af6025>鎖定所有傳奇", "<gray>背包與身上的傳奇全部鎖起來", "", "<yellow>左鍵：鎖定"));
        set(SLOT_LOCK_RARES, icon(Material.YELLOW_DYE, "<#ffff77>鎖定所有稀有", "<gray>背包與身上的稀有裝備全部鎖起來", "", "<yellow>左鍵：鎖定"));
        set(SLOT_LOCK_GEMS, icon(Material.HEART_OF_THE_SEA, "<#7fd4ff>鎖定所有技能、輔助寶石", "<gray>背包裡的技能寶石、輔助寶石全部鎖起來", "", "<yellow>左鍵：鎖定"));
        set(SLOT_UNLOCK_ALL, icon(Material.GRAY_DYE, "<gray>全部解除鎖定", "<gray>裝備與寶石", "<yellow>左鍵：解除"));
        set(SLOT_CLOSE, closeButton());
    }

    @Override
    protected void onClick(final int slot, final ClickType click, final InventoryClickEvent event) {
        if (slot < WORN_START + WORN_INVENTORY.length) {
            toggle(inventorySlot(slot));
        } else if (slot == SLOT_LOCK_UNIQUES) {
            lockAll(Rarity.UNIQUE, true);
        } else if (slot == SLOT_LOCK_RARES) {
            lockAll(Rarity.RARE, true);
        } else if (slot == SLOT_LOCK_GEMS) {
            lockGems();
        } else if (slot == SLOT_UNLOCK_ALL) {
            lockAll(null, false);
        } else if (slot == SLOT_CLOSE) {
            goBack();
            return;
        } else {
            return;
        }
        redraw();
    }

    private void toggle(final int inventorySlot) {
        final ItemStack stack = editable(inventorySlot);
        if (!tw.exilecore.item.Locks.lockable(plugin, stack)) {
            return;
        }
        final boolean locked = !tw.exilecore.item.Locks.locked(plugin, stack);
        tw.exilecore.item.Locks.set(plugin, stack, locked); // 2026-10-31：寶石也能鎖
        storeBack(inventorySlot, stack);
        player.playSound(player.getLocation(), locked ? Sound.BLOCK_CHAIN_PLACE : Sound.BLOCK_CHAIN_BREAK, 0.6f, 1.2f);
    }

    /** 2026-10-31：背包裡的技能、輔助寶石全部鎖起來。 */
    private void lockGems() {
        int changed = 0;
        if (plugin.skills() != null) {
            for (int slot = 0; slot < WORN_START; slot++) {
                final ItemStack stack = player.getInventory().getItem(inventorySlot(slot));
                if (plugin.skills().gems().lockable(stack) && !plugin.skills().gems().locked(stack)
                        && plugin.skills().gems().setLocked(stack, true)) {
                    changed++;
                }
            }
        }
        tw.exilecore.gui.ActionBar.send(player, plugin.text().parse("<#ffd27f>🔒 鎖定了 " + changed + " 格寶石"));
        player.playSound(player.getLocation(), Sound.BLOCK_CHAIN_PLACE, 0.6f, 1.0f);
    }

    /** 全部鎖定（某個稀有度）或全部解除。 */
    private void lockAll(final Rarity rarity, final boolean locked) {
        int changed = 0;
        for (int slot = 0; slot < WORN_START + WORN_INVENTORY.length; slot++) {
            final ItemStack stack = editable(inventorySlot(slot));
            final Optional<ItemData> data = plugin.items().read(stack);
            if (data.isEmpty()) {
                // 2026-10-31：全部解除也解寶石
                if (rarity == null && !locked && plugin.skills() != null && plugin.skills().gems().locked(stack)
                        && plugin.skills().gems().setLocked(stack, false)) {
                    changed++;
                }
                continue;
            }
            if ((rarity != null && data.get().rarity() != rarity) || data.get().locked() == locked) {
                continue;
            }
            data.get().setLocked(locked);
            plugin.items().update(stack, data.get());
            storeBack(inventorySlot(slot), stack);
            changed++;
        }
        tw.exilecore.gui.ActionBar.send(player, plugin.text().parse(locked ? "<#ffd27f>🔒 鎖定了 " + changed + " 件" : "<gray>解除了 " + changed + " 件的鎖定"));
        player.playSound(player.getLocation(), locked ? Sound.BLOCK_CHAIN_PLACE : Sound.BLOCK_CHAIN_BREAK, 0.6f, 1.0f);
    }
}
