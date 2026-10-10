package tw.exilecore.item;

import net.kyori.adventure.text.Component;
import org.bukkit.NamespacedKey;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.entity.EntityPickupItemEvent;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.event.inventory.InventoryType;
import org.bukkit.event.player.PlayerDropItemEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.PlayerInventory;
import org.bukkit.persistence.PersistentDataType;
import tw.exilecore.ExileCore;
import tw.exilecore.character.PlayerState;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

/**
 * 裝備比較的顯示：背包裡每件裝備的說明最後面加上「比身上的 ×：護甲 +30、生命 −12」（{@link ItemCompare}），
 * 身上的裝備換了、背包動了就重算（屬性表重算時排一次，下一個 tick 做）。那幾行只是說明，資料不變；
 * 物品離開背包（丟掉、放進倉庫、上架、交易）時先畫回乾淨的樣子，別人才不會看到你的比較。
 */
public final class ItemComparer implements Listener {

    /** 說明最後面有比較那幾行的標記（存那幾行的雜湊：沒變就不重畫）。 */
    public static final NamespacedKey KEY = new NamespacedKey("exilecore", "compare");
    private static final int MAIN_SLOTS = 36;

    private final ExileCore plugin;
    private final ItemService items;
    private final Set<UUID> pending = new HashSet<>();

    public ItemComparer(final ExileCore plugin, final ItemService items) {
        this.plugin = plugin;
        this.items = items;
    }

    /** 下一個 tick 重算這個玩家背包裡的比較（同一個 tick 排幾次只做一次）。 */
    public void schedule(final Player player) {
        if (!pending.add(player.getUniqueId())) {
            return;
        }
        plugin.getServer().getScheduler().runTask(plugin, () -> {
            pending.remove(player.getUniqueId());
            if (player.isOnline()) {
                try {
                    apply(player);
                } catch (final RuntimeException e) {
                    plugin.getSLF4JLogger().error("{} 的裝備比較出錯", player.getName(), e);
                }
            }
        });
    }

    /** 重算背包（含快捷欄）每一件裝備的比較。 */
    public void apply(final Player player) {
        final PlayerState state = plugin.players().get(player);
        final PlayerInventory inventory = player.getInventory();
        for (int slot = 0; slot < MAIN_SLOTS; slot++) {
            final ItemStack stack = inventory.getItem(slot);
            if (stack == null || stack.isEmpty()) {
                continue;
            }
            final Optional<ItemData> read = items.read(stack);
            if (read.isEmpty()) {
                continue;
            }
            final ItemData data = read.get();
            final ItemBase base = items.repository().base(data.base());
            List<String> lines = List.of();
            if (base != null && base.isWearable() && data.identified() && !(base.isWeapon() && slot == inventory.getHeldItemSlot())) {
                final Equipped equipped = equipped(player, state, base);
                if (equipped != null && !equipped.data.uuid().equals(data.uuid())) {
                    lines = ItemCompare.lines(items.repository(), data, equipped.data, equipped.name);
                }
            }
            final String hash = lines.isEmpty() ? null : Integer.toHexString(lines.hashCode());
            final String current = stack.getPersistentDataContainer().get(KEY, PersistentDataType.STRING);
            if (hash == null ? current == null : hash.equals(current)) {
                continue; // 跟上次一樣
            }
            items.rerender(stack, data);
            if (!lines.isEmpty()) {
                final List<String> copy = lines;
                stack.editMeta(meta -> {
                    final List<Component> lore = meta.lore() == null ? new ArrayList<>() : new ArrayList<>(meta.lore());
                    lore.add(ItemRenderer.line(""));
                    for (final String line : copy) {
                        lore.add(ItemRenderer.line(line));
                    }
                    meta.lore(lore);
                    meta.getPersistentDataContainer().set(KEY, PersistentDataType.STRING, hash);
                });
            }
            inventory.setItem(slot, stack);
        }
    }

    /** 身上同一個欄位的那件。 */
    private record Equipped(ItemData data, String name) {
    }

    private Equipped equipped(final Player player, final PlayerState state, final ItemBase base) {
        final int index = tw.exilecore.character.Gear.index(base.slot());
        final ItemStack stack = index < 0 ? null
                : base.slot() == tw.exilecore.item.ItemSlot.RING && state.jewellery(0) == null ? state.jewellery(1) : state.jewellery(index);
        if (stack == null || stack.isEmpty()) {
            return null;
        }
        final Optional<ItemData> data = items.read(stack);
        if (data.isEmpty() || !data.get().identified()) {
            return null;
        }
        final ItemBase worn = items.repository().base(data.get().base());
        if (worn == null || !worn.isWearable()) {
            return null;
        }
        if (base.isWeapon() != worn.isWeapon()) {
            return null; // 主手拿的是別的東西（盾、法杖 vs 近戰）就不比
        }
        return new Equipped(data.get(), ItemStats.displayName(items.repository(), data.get()));
    }

    /** 這件有比較那幾行就畫回乾淨的樣子。 */
    public void strip(final ItemStack stack) {
        if (stack == null || stack.isEmpty() || !stack.getPersistentDataContainer().has(KEY)) {
            return;
        }
        items.read(stack).ifPresent(data -> items.rerender(stack, data));
    }

    /** 物品要離開背包（放進容器、選單）：先畫乾淨。任何開著別的容器時的點擊都算，多做無妨。 */
    @EventHandler(priority = EventPriority.LOWEST)
    public void onClick(final InventoryClickEvent event) {
        if (event.getView().getTopInventory().getType() == InventoryType.CRAFTING) {
            return; // 只開著自己的背包：留著比較
        }
        strip(event.getCurrentItem());
        strip(event.getCursor());
        if (event.getWhoClicked() instanceof Player player) {
            schedule(player);
        }
    }

    @EventHandler(priority = EventPriority.LOWEST)
    public void onDrop(final PlayerDropItemEvent event) {
        strip(event.getItemDrop().getItemStack());
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onPickup(final EntityPickupItemEvent event) {
        if (event.getEntity() instanceof Player player) {
            schedule(player);
        }
    }

    @EventHandler
    public void onQuit(final PlayerQuitEvent event) {
        pending.remove(event.getPlayer().getUniqueId());
    }
}
