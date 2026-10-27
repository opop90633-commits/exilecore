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
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
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

    /** 2026-10-27 效能：背包指紋沒變就不重比（見 {@link CompareGate}）。 */
    private final CompareGate gate = new CompareGate();

    /** 下一個 tick 重算這個玩家背包裡的比較（同一個 tick 排幾次只做一次）。 */
    public void schedule(final Player player) {
        gate.force(player.getUniqueId());
        queue(player);
    }

    /**
     * 2026-10-27 效能：下一個 tick 看一下背包有沒有變（指紋），變了才重比。屬性表重算時每 2 秒排一次這個
     * （身上的裝備沒換時；換了用 {@link #schedule}）。
     */
    public void scheduleIfChanged(final Player player) {
        queue(player);
    }

    private void queue(final Player player) {
        if (!pending.add(player.getUniqueId())) {
            return;
        }
        plugin.getServer().getScheduler().runTask(plugin, () -> {
            pending.remove(player.getUniqueId());
            if (player.isOnline()) {
                try {
                    if (gate.changed(player.getUniqueId(), inventorySignature(player.getInventory()))) {
                        plugin.perf().time("items.comparer", () -> apply(player));
                    }
                } catch (final RuntimeException e) {
                    plugin.getSLF4JLogger().error("{} 的裝備比較出錯", player.getName(), e);
                }
            }
        });
    }

    /** 背包（含快捷欄）的指紋：每格的物品種類、數量、物品資料、比較標記，加上手上拿第幾格。只讀 PDC 的字串，不解析。 */
    private int inventorySignature(final PlayerInventory inventory) {
        int hash = inventory.getHeldItemSlot();
        for (int slot = 0; slot < MAIN_SLOTS; slot++) {
            final ItemStack stack = inventory.getItem(slot);
            if (stack == null || stack.isEmpty()) {
                hash = 31 * hash;
                continue;
            }
            hash = 31 * hash + stack.getType().ordinal();
            hash = 31 * hash + stack.getAmount();
            hash = 31 * hash + java.util.Objects.hashCode(items.rawJson(stack));
            hash = 31 * hash + java.util.Objects.hashCode(stack.getPersistentDataContainer().get(KEY, PersistentDataType.STRING));
        }
        return hash;
    }

    /** 重算背包（含快捷欄）每一件裝備的比較。 */
    public void apply(final Player player) {
        final PlayerState state = plugin.players().get(player);
        final PlayerInventory inventory = player.getInventory();
        // 2026-10-27 效能：身上每個欄位的那件這次只解析一次（這次之前背包裡每件同部位的裝備都把身上那件重新解析一次）
        final Map<Integer, Optional<Equipped>> worn = new HashMap<>();
        for (int slot = 0; slot < MAIN_SLOTS; slot++) {
            final ItemStack stack = inventory.getItem(slot);
            if (stack == null || stack.isEmpty()) {
                continue;
            }
            // 2026-10-27 效能：先只看 PDC 的基底代號：不是裝備（藥劑、通貨、方塊…）而且沒有比較標記的直接跳過，不解整份資料
            final ItemBase quick = items.base(stack);
            if ((quick == null || !quick.isWearable()) && !stack.getPersistentDataContainer().has(KEY)) {
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
                final Equipped equipped = worn.computeIfAbsent(equippedKey(state, base),
                        key -> Optional.ofNullable(equipped(player, state, base))).orElse(null);
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
        gate.applied(player.getUniqueId(), inventorySignature(inventory));
    }

    /** 身上同一個欄位的那件。 */
    private record Equipped(ItemData data, String name) {
    }

    /** {@link #equipped} 的結果只看欄位與是不是武器：同一次 apply 裡拿這個當快取的鍵。 */
    private static int equippedKey(final PlayerState state, final ItemBase base) {
        return tw.exilecore.character.Gear.index(base.slot()) * 2 + (base.isWeapon() ? 1 : 0);
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
        gate.forget(event.getPlayer().getUniqueId());
    }
}
