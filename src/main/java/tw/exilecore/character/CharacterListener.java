package tw.exilecore.character;

import com.destroystokyo.paper.event.player.PlayerArmorChangeEvent;
import net.kyori.adventure.text.serializer.plain.PlainTextComponentSerializer;
import org.bukkit.GameMode;
import org.bukkit.entity.Entity;
import org.bukkit.entity.Item;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.entity.EntityPickupItemEvent;
import org.bukkit.event.entity.EntityRegainHealthEvent;
import org.bukkit.event.entity.EntityResurrectEvent;
import org.bukkit.event.entity.FoodLevelChangeEvent;
import org.bukkit.event.entity.PlayerDeathEvent;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.event.inventory.InventoryDragEvent;
import org.bukkit.event.inventory.InventoryPickupItemEvent;
import org.bukkit.event.inventory.InventoryType;
import org.bukkit.event.player.PlayerAttemptPickupItemEvent;
import org.bukkit.event.player.PlayerDropItemEvent;
import org.bukkit.event.player.PlayerExpChangeEvent;
import org.bukkit.event.player.PlayerItemHeldEvent;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.event.player.PlayerRespawnEvent;
import org.bukkit.event.player.PlayerSwapHandItemsEvent;
import org.bukkit.inventory.CraftingInventory;
import org.bukkit.inventory.ItemStack;
import tw.exilecore.ExileCore;
import tw.exilecore.item.ItemRenderer;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.UUID;

/**
 * 玩家狀態相關的事件：登入載入角色、登出存檔、死亡懲罰、換裝時標記重算、把原版的飢餓／回血／經驗全部關掉。
 */
public final class CharacterListener implements Listener {

    private final ExileCore plugin;
    private final PlayerStateService states;
    private final CharacterService characters;

    public CharacterListener(final ExileCore plugin, final PlayerStateService states, final CharacterService characters) {
        this.plugin = plugin;
        this.states = states;
        this.characters = characters;
    }

    @EventHandler
    public void onJoin(final PlayerJoinEvent event) {
        final Player player = event.getPlayer();
        player.setFoodLevel(20);
        player.setSaturation(20f);
        player.setMaximumNoDamageTicks(0);
        final PlayerState state = states.get(player);
        states.rebuild(player, state);
        state.restoreAll();
        // 等一個 tick 讓客戶端準備好再更新顯示，資料庫載入完會再套一次
        plugin.getServer().getScheduler().runTask(plugin, () -> states.display(player, state));
        states.updateListName(player); // TAB 列表：選好角色前先寫「選角中」
        characters.onJoin(player);
    }

    @EventHandler
    public void onQuit(final PlayerQuitEvent event) {
        settleLooseItems(event.getPlayer());
        characters.onQuit(event.getPlayer());
    }

    @EventHandler
    public void onRespawn(final PlayerRespawnEvent event) {
        final Player player = event.getPlayer();
        plugin.getServer().getScheduler().runTask(plugin, () -> {
            // 重生後是新的實體，原版預設值要再設一次
            player.setFoodLevel(20);
            player.setSaturation(20f);
            player.setMaximumNoDamageTicks(0);
            final PlayerState state = states.get(player);
            states.rebuild(player, state);
            state.restoreAll();
            states.display(player, state);
        });
    }

    @EventHandler(priority = EventPriority.HIGH)
    public void onDeath(final PlayerDeathEvent event) {
        // RPG 伺服器不掉裝備與原版經驗；懲罰是扣我們的經驗
        event.setKeepInventory(true);
        event.setKeepLevel(true);
        event.getDrops().clear();
        event.setDroppedExp(0);
        final Player player = event.getEntity();
        if (plugin.pvp() != null && plugin.pvp().inArena(player)) {
            characters.save(player);
            return; // 競技場：不扣經驗、不算死亡次數（排行榜的「不死」也不受影響）
        }
        if (plugin.worldEvents() != null && plugin.worldEvents().inEventArea(player.getLocation())) {
            // 2026-10-16：死在活動的地方（世界 Boss 開天者、入侵怪潮、萬燈夜的幽燈墓園與南瓜王——整個活動世界）不扣經驗；死亡次數照算
            final tw.exilecore.character.CharacterRecord character = states.get(player).character();
            if (character != null) {
                character.setDeaths(character.deaths() + 1);
            }
            player.sendMessage(plugin.text().prefixed("<gray>死在活動場地：不扣經驗"));
            characters.save(player);
            return;
        }
        final boolean inMap = plugin.maps() != null && plugin.maps().inMap(player);
        final long loss = states.applyDeathPenalty(player, inMap);
        if (loss > 0) {
            player.sendMessage(plugin.text().prefixed(String.format(Locale.ROOT, "<red>死亡懲罰：損失 %,d 經驗<gray>（Lv.%d 死亡扣這一級 %s）",
                    loss, states.get(player).level(),
                    tw.exilecore.character.LevelPace.percent(states.deathPenaltyRatio(player)))));
        }
        characters.save(player);
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onResurrect(final EntityResurrectEvent event) {
        // 不死圖騰救回來：我們的生命也要回一些，否則狀態會卡在「已死亡」
        if (event.getEntity() instanceof Player player) {
            final PlayerState state = states.get(player);
            state.setLife(Math.max(state.life(), state.maxLife() * 0.3));
            state.setLastDamageTick(plugin.getServer().getCurrentTick());
            plugin.getServer().getScheduler().runTask(plugin, () -> states.display(player, state));
        }
    }

    // ---- 換裝就標記重算 ----

    @EventHandler
    public void onArmorChange(final PlayerArmorChangeEvent event) {
        states.markDirty(event.getPlayer());
    }

    @EventHandler
    public void onHeldChange(final PlayerItemHeldEvent event) {
        // 事件發生時手上的物品還沒換，延後一個 tick
        plugin.getServer().getScheduler().runTask(plugin, () -> states.markDirty(event.getPlayer()));
    }

    @EventHandler
    public void onSwapHands(final PlayerSwapHandItemsEvent event) {
        plugin.getServer().getScheduler().runTask(plugin, () -> states.markDirty(event.getPlayer()));
    }

    @EventHandler
    public void onInventoryClick(final InventoryClickEvent event) {
        if (event.getWhoClicked() instanceof Player player) {
            plugin.getServer().getScheduler().runTask(plugin, () -> states.markDirty(player));
        }
    }

    @EventHandler
    public void onInventoryDrag(final InventoryDragEvent event) {
        if (event.getWhoClicked() instanceof Player player) {
            plugin.getServer().getScheduler().runTask(plugin, () -> states.markDirty(player));
        }
    }

    @EventHandler
    public void onDrop(final PlayerDropItemEvent event) {
        plugin.getServer().getScheduler().runTask(plugin, () -> states.markDirty(event.getPlayer()));
    }

    @EventHandler
    public void onPickup(final EntityPickupItemEvent event) {
        if (event.getEntity() instanceof Player player) {
            plugin.getServer().getScheduler().runTask(plugin, () -> states.markDirty(player));
        }
    }

    // ---- 不能把東西丟在地上 ----
    // 預設（config.yml 的 items.allow-drop: false）玩家不能丟東西：免得直接丟給別人、繞過面對面交易的手續費，也不會手滑丟掉；
    // 不要的東西用選單的垃圾桶刪掉。
    // 交易存檔卡住、等重試的玩家一律不能丟、也不能撿：他的存檔要等重試才寫，這段時間丟給別人的東西會先寫進別人的存檔，
    // 萬一這時候當機就變成兩份。

    /** 這個玩家現在能不能把東西丟在地上。 */
    private boolean mayDrop(final Player player) {
        if (characters.tradeLocked(player)) {
            return false;
        }
        return plugin.getConfig().getBoolean("items.allow-drop", false)
                || player.getGameMode() == GameMode.CREATIVE && player.hasPermission(ExileCore.PERMISSION_ADMIN);
    }

    private void denyDrop(final Player player) {
        player.sendActionBar(plugin.text().parse(characters.tradeLocked(player) ? "<red>交易存檔重試中，暫時不能丟東西"
                : "<red>東西不能丟在地上<gray>（不要的東西：選單 Shift＋F 的垃圾桶）"));
    }

    /** 視窗裡的丟東西（游標上的東西點到視窗外面、指著格子按 Q）：直接擋掉，東西留在原地。 */
    @EventHandler(priority = EventPriority.LOW, ignoreCancelled = true)
    public void onDropClick(final InventoryClickEvent event) {
        switch (event.getAction()) {
            case DROP_ALL_CURSOR, DROP_ONE_CURSOR, DROP_ALL_SLOT, DROP_ONE_SLOT -> {
                if (event.getWhoClicked() instanceof Player player && !mayDrop(player)) {
                    event.setCancelled(true);
                    denyDrop(player);
                }
            }
            default -> {
            }
        }
    }

    /**
     * 其他丟東西（Q 鍵、關掉視窗時游標上的東西放不回背包…）：放得回背包就擋掉（伺服器會把東西放回手上或背包）。
     * 放不回去（背包滿了）不能擋——取消的話伺服器塞不回背包，東西會不見——所以讓它先掉下來，下一個 tick 再收進隨身背包；
     * 隨身背包也滿了才留在腳下（不會自己消失）。
     * <p>
     * 不管哪一種，丟出去的東西都永遠只有丟的人看得到、撿得到（隊友也看不到）：東西不能經過地上換手。
     */
    @EventHandler(priority = EventPriority.LOW, ignoreCancelled = true)
    public void onDropGuard(final PlayerDropItemEvent event) {
        final Player player = event.getPlayer();
        final Item drop = event.getItemDrop();
        if (mayDrop(player)) {
            claim(drop, player.getUniqueId()); // 設定打開了、管理員的創造模式：可以丟，但還是只有自己的
            plugin.getServer().getScheduler().runTask(plugin, () -> showToOwner(drop, player));
            return;
        }
        if (fits(player.getInventory().getStorageContents(), drop.getItemStack())) {
            event.setCancelled(true);
            denyDrop(player);
            return;
        }
        claim(drop, player.getUniqueId());
        drop.setUnlimitedLifetime(true);
        plugin.getServer().getScheduler().runTask(plugin, () -> settleOverflow(player, drop));
    }

    /** 掉在地上的這個東西永遠只屬於 owner：別人（包括隊友）看不到、撿不到。在物品生成之前呼叫。 */
    private void claim(final Item drop, final UUID owner) {
        plugin.personalDrops().prepareForever(drop, owner);
    }

    /** 物品生成之後：讓主人自己看得到（別人看不到）。 */
    private void showToOwner(final Item drop, final Player owner) {
        if (drop.isValid() && owner.isOnline()) {
            plugin.personalDrops().spawned(drop, owner);
        }
    }

    /** 背包滿了掉下來的東西：收進隨身背包（掉落自動撿取同一套），收不下的留在腳下。 */
    private void settleOverflow(final Player player, final Item drop) {
        if (!drop.isValid()) {
            return; // 已經被自己撿回去了
        }
        final String name = describe(drop.getItemStack());
        final PlayerState state = states.get(player);
        if (player.isOnline() && plugin.loot() != null && state.isLoaded() && state.hasCharacter()) {
            final ItemStack rest = plugin.loot().stow(player, state, drop.getItemStack());
            if (rest == null) {
                drop.remove();
                states.markDirty(player);
                player.sendMessage(plugin.text().prefixed("<yellow>背包滿了：<white>" + ItemRenderer.escape(name) + "</white> 放進了隨身背包"));
                return;
            }
            if (rest.getAmount() != drop.getItemStack().getAmount()) {
                drop.setItemStack(rest);
                states.markDirty(player);
            }
        }
        if (player.isOnline()) {
            showToOwner(drop, player);
            player.sendMessage(plugin.text().prefixed("<yellow>背包和隨身背包都滿了：<white>" + ItemRenderer.escape(name)
                    + "</white> 掉在你腳下（只有你看得到、撿得到，不會消失）"));
        }
    }

    private static String describe(final ItemStack stack) {
        return PlainTextComponentSerializer.plainText().serialize(stack.effectiveName())
                + (stack.getAmount() > 1 ? " ×" + stack.getAmount() : "");
    }

    /**
     * 登出時游標上拿著的東西、2×2 合成格裡的東西：存檔前先收進背包（放不下放隨身背包）。
     * 不收的話伺服器會在存檔之後才處理它們：游標上的存檔裡一份、又丟在地上一份；合成格裡的根本不在存檔裡。
     * 背包和隨身背包都滿了才丟在腳下（只有自己撿得到、不會消失；這一份不在存檔裡，所以只有一份）。
     */
    /** 關服時（插件先停用、收不到下線事件）也收好游標與合成格的東西（2026-10）。 */
    void settle(final Player player) {
        settleLooseItems(player);
    }

    private void settleLooseItems(final Player player) {
        final PlayerState state = states.get(player);
        if (!state.isLoaded() || !state.hasCharacter()) {
            return;
        }
        final List<ItemStack> loose = new ArrayList<>();
        final ItemStack cursor = player.getItemOnCursor();
        if (!cursor.isEmpty()) {
            loose.add(cursor.clone());
            player.setItemOnCursor(null);
        }
        if (player.getOpenInventory().getTopInventory() instanceof CraftingInventory grid
                && grid.getType() == InventoryType.CRAFTING) {
            final ItemStack[] matrix = grid.getMatrix();
            for (int i = 0; i < matrix.length; i++) {
                if (matrix[i] != null && !matrix[i].isEmpty()) {
                    loose.add(matrix[i].clone());
                    matrix[i] = null;
                }
            }
            grid.setMatrix(matrix);
        }
        for (final ItemStack item : loose) {
            for (final ItemStack rest : player.getInventory().addItem(item).values()) {
                if (!state.bag().add(rest)) {
                    player.getWorld().dropItem(player.getLocation(), rest, dropped -> {
                        claim(dropped, player.getUniqueId()); // 下次上線時看得到（PersonalDrops 上線時重新顯示）
                        dropped.setUnlimitedLifetime(true);
                    });
                }
            }
        }
    }

    /** 背包（快捷列＋主背包）放不放得下這一疊：先疊到一樣的東西上，再放空格（跟伺服器放回背包的做法一樣）。 */
    static boolean fits(final ItemStack[] storage, final ItemStack stack) {
        int need = stack.getAmount();
        for (final ItemStack current : storage) {
            if (current == null || current.isEmpty()) {
                need -= stack.getMaxStackSize();
            } else if (current.isSimilar(stack)) {
                need -= Math.max(0, current.getMaxStackSize() - current.getAmount());
            }
            if (need <= 0) {
                return true;
            }
        }
        return need <= 0;
    }

    // ---- 有主人的東西只有主人撿得到 ----
    // 原版本來就只讓主人撿，但伺服器在檢查主人之前就先發了「撿起來」的事件；不在最前面擋掉的話，
    // 靠這些事件做事的監聽器（例如走過金幣就直接進帳）會讓別人撿走——金幣和東西就經過地上換手了。

    @EventHandler(priority = EventPriority.LOWEST, ignoreCancelled = true)
    public void onForeignPickupAttempt(final PlayerAttemptPickupItemEvent event) {
        if (!mayTake(event.getItem(), event.getPlayer())) {
            event.setCancelled(true);
        }
    }

    @EventHandler(priority = EventPriority.LOWEST, ignoreCancelled = true)
    public void onForeignPickup(final EntityPickupItemEvent event) {
        if (!mayTake(event.getItem(), event.getEntity())) {
            event.setCancelled(true);
        }
    }

    /** 漏斗、漏斗礦車不能吸走有主人的東西（吸進去之後誰都拿得到）。 */
    @EventHandler(priority = EventPriority.LOWEST, ignoreCancelled = true)
    public void onHopperPickup(final InventoryPickupItemEvent event) {
        if (event.getItem().getOwner() != null) {
            event.setCancelled(true);
        }
    }

    private static boolean mayTake(final Item item, final Entity taker) {
        final UUID owner = item.getOwner();
        return owner == null || owner.equals(taker.getUniqueId());
    }

    /**
     * 2026-10：設定開著「星圖直接進倉庫」時，走過地上的星圖也直接收進倉庫的地圖頁（背包滿了也收得到）；
     * 地圖頁滿了、倉庫沒載入就照原版撿進背包。有主人的、交易存檔重試中的不收（上面的監聽器先擋掉）。
     */
    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onMapPickup(final PlayerAttemptPickupItemEvent event) {
        final Player player = event.getPlayer();
        if (plugin.loot() == null || plugin.items() == null || characters.tradeLocked(player)
                || !mayTake(event.getItem(), player) || event.getItem().isDead()) {
            return;
        }
        final ItemStack stack = event.getItem().getItemStack();
        if (stack.getAmount() != 1) {
            return;
        }
        final tw.exilecore.item.ItemData data = plugin.items().read(stack).orElse(null);
        final tw.exilecore.item.ItemBase base = data == null ? null : plugin.items().repository().base(data.base());
        if (base == null || !base.isMap()) {
            return;
        }
        final PlayerState state = plugin.players().get(player);
        if (!state.hasCharacter() || !plugin.loot().stashMap(player, state, stack)) {
            return;
        }
        event.setCancelled(true);
        event.getItem().remove();
        player.playSound(player.getLocation(), org.bukkit.Sound.ENTITY_ITEM_PICKUP, 0.5f, 1.2f);
        state.notice("<#9fd3ff>星圖收進倉庫的地圖頁", plugin.getServer().getCurrentTick() + 50);
    }

    @EventHandler(priority = EventPriority.LOW, ignoreCancelled = true)
    public void onLockedPickup(final EntityPickupItemEvent event) {
        if (event.getEntity() instanceof Player player && characters.tradeLocked(player)) {
            event.setCancelled(true);
        }
    }

    // ---- 關掉原版的資源系統 ----

    @EventHandler
    public void onFoodChange(final FoodLevelChangeEvent event) {
        if (event.getEntity() instanceof Player) {
            event.setCancelled(true);
            event.setFoodLevel(20);
        }
    }

    @EventHandler
    public void onRegainHealth(final EntityRegainHealthEvent event) {
        // 原版回血（飽食、藥水…）全部關掉，生命由我們自己回復
        if (event.getEntity() instanceof Player) {
            event.setCancelled(true);
        }
    }

    @EventHandler
    public void onExpChange(final PlayerExpChangeEvent event) {
        // 經驗條拿來顯示魔力，原版經驗一律歸零
        event.setAmount(0);
    }
}
