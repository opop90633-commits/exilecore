package tw.exilecore.character;

import org.bukkit.GameMode;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.block.Action;
import org.bukkit.event.inventory.ClickType;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.event.inventory.InventoryCloseEvent;
import org.bukkit.event.inventory.InventoryDragEvent;
import org.bukkit.event.player.PlayerDropItemEvent;
import org.bukkit.event.player.PlayerInteractEvent;
import org.bukkit.event.player.PlayerSwapHandItemsEvent;
import org.bukkit.inventory.EquipmentSlot;
import org.bukkit.inventory.InventoryView;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.PlayerInventory;
import tw.exilecore.ExileCore;
import tw.exilecore.character.menu.CharacterPanelMenu;
import tw.exilecore.item.ItemBase;
import tw.exilecore.item.ItemData;
import tw.exilecore.item.ItemRenderer;

import java.util.Optional;

/**
 * 角色面板裝備欄的顯示用格子（快捷列第 9 格、副手、四個防具欄）：拿不走、換不了、丟不掉，點了打開角色面板。
 * 那幾格被放進真的物品（Shift＋點防具、右鍵穿防具、拖進空的防具欄）由 {@link Gear#sync} 收進裝備欄。
 * 選單（{@link tw.exilecore.gui.Menu}）的點擊在 LOWEST 就取消了，這裡只處理原版的背包、箱子畫面。
 */
final class GearListener implements Listener {

    private final ExileCore plugin;
    private final Gear gear;

    GearListener(final ExileCore plugin, final Gear gear) {
        this.plugin = plugin;
        this.gear = gear;
    }

    private boolean active(final Player player) {
        final PlayerState state = plugin.players().get(player);
        return state.isLoaded() && state.hasCharacter();
    }

    @EventHandler(priority = EventPriority.LOW, ignoreCancelled = true)
    public void onClick(final InventoryClickEvent event) {
        if (!(event.getWhoClicked() instanceof Player player) || !active(player)) {
            return;
        }
        if (player.getGameMode() == GameMode.CREATIVE) {
            syncSoon(player); // 創造模式的游標在客戶端：不擋、不換（拖走的顯示用複本下次同步清掉）
            return;
        }
        final ItemStack current = event.getCurrentItem();
        final ItemStack cursor = event.getCursor();
        final boolean ownSlot = event.getClickedInventory() instanceof PlayerInventory;
        boolean blocked = Gear.isDisplay(current) || Gear.isDisplay(cursor);
        if (event.getClick() == ClickType.NUMBER_KEY) {
            final int button = event.getHotbarButton();
            if (button == Gear.HAND_SLOT || button >= 0 && Gear.isDisplay(player.getInventory().getItem(button))) {
                blocked = true; // 數字鍵 9：會把第 9 格的顯示用複本換出去
            }
        }
        if (event.getClick() == ClickType.SWAP_OFFHAND && (ownSlot || Gear.isDisplay(player.getInventory().getItemInOffHand()))) {
            blocked = true; // 背包裡按 F：會把副手的顯示用複本換出去
        }
        if (blocked) {
            event.setCancelled(true);
            if (Gear.isDisplay(cursor)) {
                player.setItemOnCursor(null);
            }
            final ItemStack held = cursor == null || cursor.isEmpty() || Gear.isDisplay(cursor) ? null : cursor;
            if (ownSlot && Gear.isDisplay(current) && Gear.skillSlot(event.getSlot()) && held == null && plugin.skills() != null
                    && (event.getClick().isLeftClick() || event.getClick().isRightClick())) {
                plugin.getServer().getScheduler().runTask(plugin, () -> {
                    if (player.isOnline()) {
                        new tw.exilecore.skill.SkillMenu(plugin, player, plugin.skills()).open(); // 點技能圖示：打開技能面板
                    }
                });
                return;
            }
            if (ownSlot && Gear.isDisplay(current) && Gear.indexAt(event.getSlot()) >= 0) {
                if (held != null && plugin.currencies() != null && plugin.currencies().isCurrency(held)) {
                    player.sendActionBar(plugin.text().parse("<red>通貨要用在背包裡的裝備<gray>：先在角色面板（Shift＋F → 角色）卸下"));
                } else if (held != null) {
                    equipFromCursor(player, event.getView(), held); // 拿著裝備點顯示用的格子：直接裝上，換下來的放回游標
                } else if (event.getClick().isLeftClick() || event.getClick().isRightClick()) {
                    plugin.getServer().getScheduler().runTask(plugin, () -> {
                        if (player.isOnline()) {
                            new CharacterPanelMenu(plugin, player).open();
                        }
                    });
                    return;
                }
            }
        }
        syncSoon(player);
    }

    /** 游標上的裝備裝上（換下來的放回游標）。 */
    private void equipFromCursor(final Player player, final InventoryView view, final ItemStack stack) {
        final PlayerState state = plugin.players().get(player);
        final Optional<ItemData> data = plugin.items().read(stack);
        final ItemBase base = data.map(value -> plugin.items().repository().base(value.base())).orElse(null);
        if (base == null || !base.isWearable() || Gear.index(base.slot()) < 0) {
            player.sendActionBar(plugin.text().parse("<red>這不是能裝備的東西"));
            return;
        }
        int index = Gear.index(base.slot());
        if (base.slot() == tw.exilecore.item.ItemSlot.RING && state.jewellery(0) != null && state.jewellery(1) == null) {
            index = 1;
        }
        if (index == Gear.OFF) {
            final ItemStack main = state.jewellery(Gear.MAIN);
            final ItemBase mainBase = main == null ? null
                    : plugin.items().read(main).map(value -> plugin.items().repository().base(value.base())).orElse(null);
            final String problem = Gear.pairProblem(mainBase, base);
            if (problem != null) {
                player.sendActionBar(plugin.text().parse("<red>" + ItemRenderer.escape(problem)));
                return;
            }
        }
        final ItemStack previous = state.jewellery(index);
        state.setJewellery(index, stack.clone());
        view.setCursor(previous); // 同一個事件裡換掉游標，不會有一瞬間兩邊都有
        plugin.getServer().getScheduler().runTask(plugin, () -> {
            if (!player.isOnline()) {
                return;
            }
            gear.sync(player);
            plugin.players().sheet(player);
            player.sendActionBar(plugin.text().parse("<green>裝上 " + ItemRenderer.escape(base.name()) + "<gray>（裝備在角色面板）"));
        });
    }

    @EventHandler(priority = EventPriority.LOW, ignoreCancelled = true)
    public void onDrag(final InventoryDragEvent event) {
        if (!(event.getWhoClicked() instanceof Player player) || !active(player)) {
            return;
        }
        if (Gear.isDisplay(event.getOldCursor())) {
            event.setCancelled(true);
            player.setItemOnCursor(null);
            syncSoon(player);
            return;
        }
        final InventoryView view = event.getView();
        for (final int raw : event.getRawSlots()) {
            if (Gear.isDisplay(view.getItem(raw))) {
                event.setCancelled(true);
                break;
            }
        }
        syncSoon(player);
    }

    /** 換手（F）：副手放的是顯示用複本，一律不換（Shift＋F 開選單在 LootListener 先處理掉了）。 */
    @EventHandler(priority = EventPriority.HIGHEST)
    public void onSwapHands(final PlayerSwapHandItemsEvent event) {
        if (active(event.getPlayer())) {
            event.setCancelled(true);
        }
    }

    @EventHandler(priority = EventPriority.LOWEST)
    public void onDrop(final PlayerDropItemEvent event) {
        if (Gear.isDisplay(event.getItemDrop().getItemStack())) {
            event.setCancelled(true); // 顯示用的複本丟不掉（取消後伺服器放回原位，下一次同步也會補回來）
            syncSoon(event.getPlayer());
        }
    }

    /** 拿著防具右鍵：原版會跟身上那件（顯示用的複本）對換；改成裝進角色面板，換下來的放回手上那一格。 */
    @EventHandler(priority = EventPriority.LOW)
    public void onInteract(final PlayerInteractEvent event) {
        if (event.getAction() != Action.RIGHT_CLICK_AIR && event.getAction() != Action.RIGHT_CLICK_BLOCK) {
            return;
        }
        final Player player = event.getPlayer();
        if (player.getGameMode() == GameMode.SPECTATOR || !active(player)) {
            return;
        }
        if (event.getAction() == Action.RIGHT_CLICK_BLOCK && event.getClickedBlock() != null
                && event.getClickedBlock().getType().isInteractable() && !player.isSneaking()) {
            return; // 拿著防具開箱子、開門：照原版
        }
        final PlayerInventory inventory = player.getInventory();
        final int slot = event.getHand() == EquipmentSlot.OFF_HAND ? 40 : inventory.getHeldItemSlot();
        final ItemStack stack = inventory.getItem(slot);
        if (stack == null || stack.isEmpty() || Gear.isDisplay(stack)) {
            return;
        }
        final ItemBase base = plugin.items().read(stack).map(value -> plugin.items().repository().base(value.base())).orElse(null);
        if (base == null || !base.slot().isVanilla() || base.slot() == tw.exilecore.item.ItemSlot.MAIN_HAND
                || base.slot() == tw.exilecore.item.ItemSlot.OFF_HAND) {
            return; // 只管防具（武器、盾右鍵是施放技能）
        }
        event.setCancelled(true);
        final String problem = gear.equip(player, slot);
        if (problem != null) {
            player.sendActionBar(plugin.text().parse("<red>" + ItemRenderer.escape(problem)));
        } else {
            player.sendActionBar(plugin.text().parse("<green>裝上 " + ItemRenderer.escape(base.name()) + "<gray>（裝備在角色面板）"));
        }
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onClose(final InventoryCloseEvent event) {
        if (event.getPlayer() instanceof Player player) {
            syncSoon(player);
        }
    }

    private void syncSoon(final Player player) {
        plugin.getServer().getScheduler().runTask(plugin, () -> {
            if (player.isOnline() && gear.sync(player)) {
                plugin.players().sheet(player);
            }
        });
    }
}
