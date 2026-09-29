package tw.exilecore.character;

import com.destroystokyo.paper.event.player.PlayerArmorChangeEvent;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.entity.EntityRegainHealthEvent;
import org.bukkit.event.entity.FoodLevelChangeEvent;
import org.bukkit.event.entity.PlayerDeathEvent;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.event.inventory.InventoryDragEvent;
import org.bukkit.event.player.PlayerDropItemEvent;
import org.bukkit.event.player.PlayerExpChangeEvent;
import org.bukkit.event.player.PlayerItemHeldEvent;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.event.player.PlayerRespawnEvent;
import org.bukkit.event.player.PlayerSwapHandItemsEvent;
import org.bukkit.event.entity.EntityPickupItemEvent;
import tw.exilecore.ExileCore;

/**
 * 玩家狀態相關的事件：登入登出、換裝時標記重算、把原版的飢餓／回血／經驗全部關掉。
 */
public final class CharacterListener implements Listener {

    private final ExileCore plugin;
    private final PlayerStateService states;

    public CharacterListener(final ExileCore plugin, final PlayerStateService states) {
        this.plugin = plugin;
        this.states = states;
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
        // 等一個 tick 讓客戶端準備好再更新顯示
        plugin.getServer().getScheduler().runTask(plugin, () -> states.display(player, state));
    }

    @EventHandler
    public void onQuit(final PlayerQuitEvent event) {
        states.remove(event.getPlayer());
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
        // RPG 伺服器不掉裝備與經驗；死亡懲罰（掉經驗）在階段 2 加
        event.setKeepInventory(true);
        event.setKeepLevel(true);
        event.getDrops().clear();
        event.setDroppedExp(0);
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
