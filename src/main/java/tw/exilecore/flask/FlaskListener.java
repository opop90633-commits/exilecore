package tw.exilecore.flask;

import tw.exilecore.stats.StatKeys;
import org.bukkit.Sound;
import org.bukkit.SoundCategory;
import org.bukkit.entity.LivingEntity;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.entity.EntityDeathEvent;
import org.bukkit.event.entity.PlayerDeathEvent;
import org.bukkit.event.inventory.ClickType;
import org.bukkit.event.inventory.InventoryAction;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.event.inventory.InventoryCloseEvent;
import org.bukkit.event.inventory.InventoryDragEvent;
import org.bukkit.event.inventory.InventoryType;
import org.bukkit.event.player.PlayerAttemptPickupItemEvent;
import org.bukkit.event.player.PlayerDropItemEvent;
import org.bukkit.event.player.PlayerItemHeldEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.event.player.PlayerRespawnEvent;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.PlayerInventory;
import tw.exilecore.ExileCore;
import tw.exilecore.character.PlayerState;
import tw.exilecore.item.ItemRenderer;
import tw.exilecore.monster.MonsterData;

/**
 * 藥劑的按鍵與充能：數字鍵選到藥劑欄就喝（取消換手上的東西）、殺怪充能、重生補滿。
 * <p>
 * 藥劑欄（照 PoE2，一格生命、一格魔力）：只能放對應的藥劑；空著的時候是空瓶提示，拿不走（拿著對的藥劑點它＝放進去）；
 * 從地上撿到藥劑時那格空著就直接放進去。
 */
final class FlaskListener implements Listener {

    private final ExileCore plugin;
    private final FlaskService flasks;

    FlaskListener(final ExileCore plugin, final FlaskService flasks) {
        this.plugin = plugin;
        this.flasks = flasks;
    }

    /** 數字鍵（或滾輪）選到藥劑欄：喝那一瓶，手上的武器不換。那一格不是藥劑就照原版。 */
    @EventHandler(priority = EventPriority.LOW, ignoreCancelled = true)
    public void onHeld(final PlayerItemHeldEvent event) {
        final Player player = event.getPlayer();
        if (!flasks.config().isFlaskSlot(event.getNewSlot()) || !flasks.eligible(player)) {
            return;
        }
        if (flasks.drink(player, event.getNewSlot())) {
            event.setCancelled(true);
        }
    }

    /**
     * 殺怪：藥劑欄的藥劑充能。跟掉落一樣，傷害有到 5% 的人都算（組隊時不會只有最後一擊的人有充能）。
     * 怪物資料在 HIGH 會被清掉，這裡要先讀。
     */
    @EventHandler(priority = EventPriority.NORMAL, ignoreCancelled = true)
    public void onKill(final EntityDeathEvent event) {
        final LivingEntity entity = event.getEntity();
        if (entity instanceof Player) {
            return;
        }
        final MonsterData data = plugin.monsters().data(entity).orElse(null);
        if (data == null || plugin.monsters().isSummoned(entity)) {
            return; // 召喚出來的小怪不給充能
        }
        final int charges = flasks.config().chargesPerKill(data.rarity());
        for (final Player player : plugin.monsterModule().credit().sharers(entity, entity.getKiller())) {
            if (flasks.eligible(player)) {
                // 2026-10：打死牠的那個技能掛了獵賞這類輔助：充能更多
                final String skill = plugin.skills() == null ? null : plugin.skills().lastSkill(entity, player);
                final double life = skill == null ? 0 : plugin.skills().supportFlat(player, skill, StatKeys.FLASK_KILL_LIFE);
                final double mana = skill == null ? 0 : plugin.skills().supportFlat(player, skill, StatKeys.FLASK_KILL_MANA);
                final double charm = skill == null ? 0 : plugin.skills().supportFlat(player, skill, StatKeys.CHARM_KILL);
                if (life <= 0 && mana <= 0) {
                    flasks.addCharges(player, charges);
                } else {
                    flasks.addCharges(player, (int) Math.round(charges * (1 + life / 100.0)), FlaskKind.LIFE);
                    flasks.addCharges(player, (int) Math.round(charges * (1 + mana / 100.0)), FlaskKind.MANA);
                }
                if (flasks.charms() != null) {
                    flasks.charms().onKill(player, data.rarity(), (int) Math.round(charges * (1 + charm / 100.0)));
                }
            }
        }
    }

    @EventHandler
    public void onDeath(final PlayerDeathEvent event) {
        flasks.forget(event.getPlayer());
    }

    /** 重生：藥劑補滿（跟生命、魔力一起）。 */
    @EventHandler
    public void onRespawn(final PlayerRespawnEvent event) {
        final Player player = event.getPlayer();
        plugin.getServer().getScheduler().runTask(plugin, () -> {
            if (player.isOnline()) {
                flasks.refill(player);
            }
        });
    }

    @EventHandler
    public void onQuit(final PlayerQuitEvent event) {
        flasks.quit(event.getPlayer());
    }

    // ---- 藥劑欄 ----

    /**
     * 點到藥劑欄（或數字鍵、副手鍵跟藥劑欄對調）：藥劑欄只能放對應的藥劑，空瓶提示拿不走；
     * 拿著對的藥劑點空瓶＝放進去，從藥劑欄拿走藥劑＝那格馬上換成空瓶。其他地方跑出來的空瓶（不應該有）擋掉。
     * 在選單自己的處理（LOWEST）之後：選單已經擋掉的點擊不用管。
     */
    @EventHandler(priority = EventPriority.LOW, ignoreCancelled = true)
    public void onSlotClick(final InventoryClickEvent event) {
        if (!(event.getWhoClicked() instanceof Player player) || !flasks.eligible(player)) {
            return;
        }
        final PlayerInventory inventory = player.getInventory();
        final Inventory clicked = event.getClickedInventory();
        final int slot = event.getSlot();
        // 自己的背包（看別人背包的外掛畫面裡，那個背包是別人的：不算）
        final boolean own = clicked instanceof PlayerInventory mine && mine.getHolder() == player;
        final FlaskKind slotKind = own && slot < 9 ? flasks.config().kindAt(slot) : null;
        final ItemStack current = event.getCurrentItem();
        final ClickType click = event.getClick();

        if ((click == ClickType.NUMBER_KEY || click == ClickType.SWAP_OFFHAND) && event.getAction() == InventoryAction.NOTHING
                && (slotKind != null || click == ClickType.NUMBER_KEY && flasks.config().kindAt(event.getHotbarButton()) != null)) {
            // 原版也不會換的（合成結果格、盔甲格放不下…）：不要自己搬（搬進合成結果格的東西會不見）
            event.setCancelled(true);
            return;
        }
        if (click == ClickType.NUMBER_KEY) {
            final int button = event.getHotbarButton();
            final FlaskKind buttonKind = button >= 0 ? flasks.config().kindAt(button) : null;
            if (slotKind != null && buttonKind != null) {
                if (own && slot != button) {
                    deny(event, player, "生命藥劑欄和魔力藥劑欄不能對調");
                } else {
                    event.setCancelled(true);
                }
                return;
            }
            if (buttonKind != null) {
                // 指著別的格子按藥劑欄的數字鍵：那一格的東西放進藥劑欄、藥劑欄的東西放到那一格
                swapIntoFlaskSlot(event, player, buttonKind, button, current, clicked, slot);
                return;
            }
            if (slotKind != null) {
                // 指著藥劑欄按別的數字鍵：快捷欄那一格的東西放進藥劑欄
                swapIntoFlaskSlot(event, player, slotKind, slot, inventory.getItem(button), inventory, button);
                return;
            }
            if (FlaskService.isPlaceholder(current) || FlaskService.isPlaceholder(inventory.getItem(button))) {
                event.setCancelled(true);
                flasks.ensureSoon(player);
            }
            return;
        }
        if (click == ClickType.SWAP_OFFHAND) {
            if (slotKind != null) {
                final ItemStack offhand = inventory.getItemInOffHand();
                swapIntoFlaskSlot(event, player, slotKind, slot, offhand, inventory, 40);
            } else if (FlaskService.isPlaceholder(current)) {
                event.setCancelled(true);
                flasks.ensureSoon(player);
            }
            return;
        }
        if (slotKind == null) {
            if (FlaskService.isPlaceholder(current) || FlaskService.isPlaceholder(event.getCursor())) {
                event.setCancelled(true); // 跑到別的地方的空瓶（例如創造模式搬進倉庫的）：點一下就清掉
                if (FlaskService.isPlaceholder(current) && clicked != null && !own) {
                    clicked.setItem(slot, null);
                }
                flasks.ensureSoon(player);
                return;
            }
            // 自己的背包畫面裡 Shift＋點藥劑：那一種的藥劑欄空著就放進去
            if (click.isShiftClick() && own && event.getView().getTopInventory().getType() == InventoryType.CRAFTING
                    && flasks.kindOf(current) != null && flasks.canEquip(player, current)) {
                event.setCancelled(true);
                final FlaskKind kind = flasks.kindOf(current);
                inventory.setItem(flasks.config().slotFor(kind), current.asOne());
                clicked.setItem(slot, null);
                sound(player, true);
            }
            return;
        }
        onFlaskSlotClick(event, player, slotKind, slot, current);
    }

    /** 點到藥劑欄本身（左鍵、右鍵、Shift、Q…）。 */
    private void onFlaskSlotClick(final InventoryClickEvent event, final Player player, final FlaskKind kind, final int slot,
                                  final ItemStack current) {
        final PlayerInventory inventory = player.getInventory();
        final ItemStack cursor = event.getCursor();
        final ClickType click = event.getClick();
        final boolean cursorEmpty = cursor == null || cursor.isEmpty();
        final boolean holdsPlaceholder = FlaskService.isPlaceholder(current);
        final boolean cursorFits = !cursorEmpty && flasks.kindOf(cursor) == kind && cursor.getAmount() == 1;
        if (holdsPlaceholder) {
            event.setCancelled(true); // 空瓶拿不走、Shift／Q 都不行
            if (cursorFits && (click == ClickType.LEFT || click == ClickType.RIGHT)) {
                final ItemStack flask = cursor.asOne();
                event.getView().setCursor(null);
                inventory.setItem(slot, flask); // 放進去：空瓶不見
                sound(player, true);
            } else if (!cursorEmpty) {
                deny(event, player, wrongItem(kind, cursor));
            }
            return;
        }
        if (click == ClickType.LEFT || click == ClickType.RIGHT) {
            if (cursorEmpty) {
                if (current != null && !current.isEmpty()) {
                    // 拿起藥劑欄的東西：那格馬上換成空瓶（不會空著一瞬間被別的東西塞進去）
                    final ItemStack taken = current.clone();
                    event.setCancelled(true);
                    inventory.setItem(slot, flasks.placeholder(kind));
                    event.getView().setCursor(taken);
                }
                return;
            }
            if (!cursorFits) {
                deny(event, player, wrongItem(kind, cursor));
            }
            return; // 對的藥劑：照原版對調
        }
        if (click.isShiftClick() && current != null && !current.isEmpty()
                && event.getView().getTopInventory().getType() == InventoryType.CRAFTING) {
            // 自己的背包畫面裡 Shift＋點藥劑欄：搬到主背包，那格馬上換成空瓶
            for (int target = 9; target < 36; target++) {
                final ItemStack there = inventory.getItem(target);
                if (there == null || there.isEmpty()) {
                    final ItemStack moving = current.clone();
                    event.setCancelled(true);
                    inventory.setItem(slot, flasks.placeholder(kind));
                    inventory.setItem(target, moving);
                    return;
                }
            }
            event.setCancelled(true); // 主背包滿了
            return;
        }
        if (click.isShiftClick() || click == ClickType.DROP || click == ClickType.CONTROL_DROP) {
            flasks.ensureSoon(player); // 拿走了（放進倉庫、隨身背包、丟掉）：下一個 tick 放空瓶
            return;
        }
        if (click == ClickType.DOUBLE_CLICK) {
            return;
        }
        event.setCancelled(true); // 其他（中鍵、視窗外…）不讓動藥劑欄
    }

    /**
     * 數字鍵或副手鍵：incoming（從 sourceInventory 的 sourceSlot 來）要換進藥劑欄 flaskSlot。
     * 只有對的藥劑可以換進來；藥劑欄原本是空瓶的話自己搬（空瓶不能被換出去），原本是藥劑的話照原版對調。
     */
    private void swapIntoFlaskSlot(final InventoryClickEvent event, final Player player, final FlaskKind kind, final int flaskSlot,
                                   final ItemStack incoming, final Inventory sourceInventory, final int sourceSlot) {
        final PlayerInventory inventory = player.getInventory();
        final ItemStack inSlot = inventory.getItem(flaskSlot);
        final boolean incomingEmpty = incoming == null || incoming.isEmpty();
        final boolean incomingFits = !incomingEmpty && flasks.kindOf(incoming) == kind && incoming.getAmount() == 1;
        if (!incomingEmpty && !incomingFits) {
            deny(event, player, wrongItem(kind, incoming));
            return;
        }
        if (FlaskService.isPlaceholder(inSlot)) {
            event.setCancelled(true);
            if (incomingFits) {
                final ItemStack flask = incoming.asOne();
                if (sourceSlot == 40 && sourceInventory == inventory) {
                    inventory.setItemInOffHand(null);
                } else {
                    sourceInventory.setItem(sourceSlot, null);
                }
                inventory.setItem(flaskSlot, flask);
                sound(player, true);
            }
            return;
        }
        if (incomingEmpty && inSlot != null && !inSlot.isEmpty()) {
            // 藥劑換到別的格子，藥劑欄空了：自己搬，藥劑欄馬上換成空瓶
            final ItemStack moving = inSlot.clone();
            event.setCancelled(true);
            inventory.setItem(flaskSlot, flasks.placeholder(kind));
            if (sourceSlot == 40 && sourceInventory == inventory) {
                inventory.setItemInOffHand(moving);
            } else {
                sourceInventory.setItem(sourceSlot, moving);
            }
        }
        // 兩邊都是對的藥劑（或都是空的）：照原版
    }

    /** 拖著東西經過藥劑欄：只有對的藥劑可以放進去。 */
    @EventHandler(priority = EventPriority.LOW, ignoreCancelled = true)
    public void onSlotDrag(final InventoryDragEvent event) {
        if (!(event.getWhoClicked() instanceof Player player) || !flasks.eligible(player)) {
            return;
        }
        if (FlaskService.isPlaceholder(event.getOldCursor())) {
            event.setCancelled(true);
            flasks.ensureSoon(player);
            return;
        }
        for (final int raw : event.getRawSlots()) {
            final Inventory target = event.getView().getInventory(raw);
            final int slot = event.getView().convertSlot(raw);
            final FlaskKind kind = target instanceof PlayerInventory mine && mine.getHolder() == player && slot < 9
                    ? flasks.config().kindAt(slot) : null;
            if (kind != null && flasks.kindOf(event.getOldCursor()) != kind) {
                deny(event, player, wrongItem(kind, event.getOldCursor()));
                return;
            }
        }
    }

    /** 關掉視窗（游標上的東西放回背包之後）：空瓶提示補好。 */
    @EventHandler(priority = EventPriority.MONITOR)
    public void onClose(final InventoryCloseEvent event) {
        if (event.getPlayer() instanceof Player player) {
            flasks.ensureSoon(player);
        }
    }

    /** 從地上撿到藥劑：那一種的藥劑欄空著就直接放進去（照 PoE）。別人的東西撿不到的規則在前面（LOWEST）。 */
    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onPickup(final PlayerAttemptPickupItemEvent event) {
        final Player player = event.getPlayer();
        final ItemStack stack = event.getItem().getItemStack();
        if (plugin.characters() != null && plugin.characters().tradeLocked(player)) {
            return; // 交易存檔重試中不能撿東西：交給原本的規則擋
        }
        if (flasks.kindOf(stack) == null || !flasks.equipIfEmpty(player, stack)) {
            return;
        }
        event.setCancelled(true);
        event.getItem().remove();
        player.playSound(player.getLocation(), Sound.ENTITY_ITEM_PICKUP, SoundCategory.PLAYERS, 0.4f, 1.4f);
    }

    /** 空瓶提示不會掉在地上（不應該發生：點擊已經擋掉了）。 */
    @EventHandler(priority = EventPriority.LOWEST, ignoreCancelled = true)
    public void onDropPlaceholder(final PlayerDropItemEvent event) {
        if (FlaskService.isPlaceholder(event.getItemDrop().getItemStack())) {
            event.setCancelled(true);
            flasks.ensureSoon(event.getPlayer());
        }
    }

    /** 放錯東西的說明：另一種藥劑告訴他放哪格，其他東西告訴他這格只能放什麼。 */
    private String wrongItem(final FlaskKind kind, final ItemStack item) {
        if (FlaskService.isPlaceholder(item)) {
            return "空瓶只是提示，不能搬";
        }
        final FlaskKind itemKind = flasks.kindOf(item);
        if (itemKind != null && itemKind != kind) {
            return itemKind.displayName() + "藥劑要放在快捷欄 " + flasks.slotKey(itemKind);
        }
        return "快捷欄 " + flasks.slotKey(kind) + " 是" + kind.displayName() + "藥劑欄，只能放" + kind.displayName() + "藥劑";
    }

    private void deny(final org.bukkit.event.Cancellable event, final Player player, final String message) {
        event.setCancelled(true);
        final PlayerState state = plugin.players().get(player);
        state.notice("<red>" + ItemRenderer.escape(message), plugin.getServer().getCurrentTick() + 50);
        plugin.players().display(player, state);
        sound(player, false);
    }

    private static void sound(final Player player, final boolean ok) {
        if (ok) {
            player.playSound(player.getLocation(), Sound.ITEM_BOTTLE_FILL, SoundCategory.PLAYERS, 0.5f, 1.3f);
        } else {
            player.playSound(player.getLocation(), Sound.BLOCK_NOTE_BLOCK_BASS, SoundCategory.PLAYERS, 0.6f, 0.7f);
        }
    }
}
