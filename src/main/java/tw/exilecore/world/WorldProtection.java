package tw.exilecore.world;

import org.bukkit.GameMode;
import org.bukkit.World;
import org.bukkit.block.Block;
import org.bukkit.entity.ArmorStand;
import org.bukkit.entity.Entity;
import org.bukkit.entity.Hanging;
import org.bukkit.entity.ItemFrame;
import org.bukkit.entity.Projectile;
import org.bukkit.entity.Player;
import org.bukkit.event.Event;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.block.Action;
import org.bukkit.event.block.BlockBreakEvent;
import org.bukkit.event.block.BlockBurnEvent;
import org.bukkit.event.block.BlockExplodeEvent;
import org.bukkit.event.block.BlockFadeEvent;
import org.bukkit.event.block.BlockFertilizeEvent;
import org.bukkit.event.block.BlockFormEvent;
import org.bukkit.event.block.BlockFromToEvent;
import org.bukkit.event.block.BlockGrowEvent;
import org.bukkit.event.block.BlockIgniteEvent;
import org.bukkit.event.block.BlockPlaceEvent;
import org.bukkit.event.block.BlockSpreadEvent;
import org.bukkit.event.block.LeavesDecayEvent;
import org.bukkit.event.entity.EntityChangeBlockEvent;
import org.bukkit.event.entity.EntityDamageByEntityEvent;
import org.bukkit.event.entity.EntityExplodeEvent;
import org.bukkit.event.hanging.HangingBreakByEntityEvent;
import org.bukkit.event.hanging.HangingPlaceEvent;
import org.bukkit.event.player.PlayerArmorStandManipulateEvent;
import org.bukkit.event.player.PlayerBucketEmptyEvent;
import org.bukkit.event.player.PlayerBucketFillEvent;
import org.bukkit.event.player.PlayerInteractEntityEvent;
import org.bukkit.event.player.PlayerInteractEvent;
import org.bukkit.event.world.StructureGrowEvent;
import tw.exilecore.ExileCore;

/**
 * 劇情世界、地圖世界與競技場受保護：玩家不能挖、放、用桶子、打開箱子與其他會動到方塊的東西（右鍵施放技能不受影響）；
 * 自然變化（樹葉凋零、融雪、火勢、水流、爆炸、生物改變方塊）也全部擋掉，世界永遠是生成時的樣子。
 * 創造模式的管理員不受限制（要改建築就切創造）。
 */
public final class WorldProtection implements Listener {

    private final ExileCore plugin;
    private final CampaignWorld worlds;

    public WorldProtection(final ExileCore plugin, final CampaignWorld worlds) {
        this.plugin = plugin;
        this.worlds = worlds;
    }

    private boolean protectedWorld(final World world) {
        return world != null && (world.equals(worlds.campaign()) || world.equals(worlds.maps()) || world.equals(worlds.arena())
                || world.equals(worlds.event()));
    }

    private boolean builder(final Player player) {
        return player.getGameMode() == GameMode.CREATIVE && player.hasPermission(ExileCore.PERMISSION_ADMIN);
    }

    private boolean deny(final Player player, final World world) {
        return protectedWorld(world) && !builder(player);
    }

    @EventHandler(priority = EventPriority.LOW, ignoreCancelled = true)
    public void onBreak(final BlockBreakEvent event) {
        if (deny(event.getPlayer(), event.getBlock().getWorld())) {
            event.setCancelled(true);
        }
    }

    @EventHandler(priority = EventPriority.LOW, ignoreCancelled = true)
    public void onPlace(final BlockPlaceEvent event) {
        if (deny(event.getPlayer(), event.getBlock().getWorld())) {
            event.setCancelled(true);
        }
    }

    @EventHandler(priority = EventPriority.LOW, ignoreCancelled = true)
    public void onBucketEmpty(final PlayerBucketEmptyEvent event) {
        if (deny(event.getPlayer(), event.getBlock().getWorld())) {
            event.setCancelled(true);
        }
    }

    @EventHandler(priority = EventPriority.LOW, ignoreCancelled = true)
    public void onBucketFill(final PlayerBucketFillEvent event) {
        if (deny(event.getPlayer(), event.getBlock().getWorld())) {
            event.setCancelled(true);
        }
    }

    /**
     * 右鍵方塊（箱子、門、拉桿…）與踩壓（壓力板、耕地）一律擋掉；拿著東西右鍵方塊也不能用（斧頭削樹皮、鏟子挖路、骨粉）。
     * 右鍵空氣不受影響，技能監聽器也不看這個事件的取消狀態，所以施放技能照常。
     * 用 LOWEST，比技能監聽器（LOW）早：它看到方塊互動被擋掉，就會把這一下當成施放技能（點到木桶也能放技能）。
     */
    @EventHandler(priority = EventPriority.LOWEST)
    public void onInteract(final PlayerInteractEvent event) {
        final Block block = event.getClickedBlock();
        if (block == null || !deny(event.getPlayer(), block.getWorld())) {
            return;
        }
        if (event.getAction() == Action.PHYSICAL) {
            event.setCancelled(true);
            return;
        }
        if (event.getAction() == Action.RIGHT_CLICK_BLOCK) {
            event.setUseInteractedBlock(Event.Result.DENY);
            event.setUseItemInHand(Event.Result.DENY);
        }
    }

    @EventHandler(ignoreCancelled = true)
    public void onFertilize(final BlockFertilizeEvent event) {
        if (protectedWorld(event.getBlock().getWorld()) && !(event.getPlayer() != null && builder(event.getPlayer()))) {
            event.setCancelled(true);
        }
    }

    @EventHandler(priority = EventPriority.LOW, ignoreCancelled = true)
    public void onHangingBreak(final HangingBreakByEntityEvent event) {
        final Entity remover = event.getRemover();
        if (protectedWorld(event.getEntity().getWorld()) && !(remover instanceof Player player && builder(player))) {
            event.setCancelled(true);
        }
    }

    @EventHandler(priority = EventPriority.LOW, ignoreCancelled = true)
    public void onHangingPlace(final HangingPlaceEvent event) {
        final Player player = event.getPlayer();
        if (protectedWorld(event.getEntity().getWorld()) && (player == null || !builder(player))) {
            event.setCancelled(true);
        }
    }

    /** 物品展示框、盔甲座：不能拿走、轉、打壞（NPC 是人偶，本來就打不壞）。 */
    @EventHandler(priority = EventPriority.LOW, ignoreCancelled = true)
    public void onInteractEntity(final PlayerInteractEntityEvent event) {
        final Entity entity = event.getRightClicked();
        if ((entity instanceof ItemFrame || entity instanceof ArmorStand) && deny(event.getPlayer(), entity.getWorld())) {
            event.setCancelled(true);
        }
    }

    @EventHandler(priority = EventPriority.LOW, ignoreCancelled = true)
    public void onArmorStand(final PlayerArmorStandManipulateEvent event) {
        if (deny(event.getPlayer(), event.getRightClicked().getWorld())) {
            event.setCancelled(true);
        }
    }

    @EventHandler(priority = EventPriority.LOW, ignoreCancelled = true)
    public void onDecorationDamage(final EntityDamageByEntityEvent event) {
        final Entity victim = event.getEntity();
        if (!(victim instanceof ItemFrame || victim instanceof ArmorStand || victim instanceof Hanging) || !protectedWorld(victim.getWorld())) {
            return;
        }
        Entity damager = event.getDamager();
        if (damager instanceof Projectile projectile && projectile.getShooter() instanceof Entity shooter) {
            damager = shooter;
        }
        if (!(damager instanceof Player player && builder(player))) {
            event.setCancelled(true);
        }
    }

    // ---- 自然變化 ----

    @EventHandler(ignoreCancelled = true)
    public void onEntityChangeBlock(final EntityChangeBlockEvent event) {
        if (protectedWorld(event.getBlock().getWorld()) && !(event.getEntity() instanceof Player player && builder(player))) {
            event.setCancelled(true);
        }
    }

    @EventHandler(ignoreCancelled = true)
    public void onEntityExplode(final EntityExplodeEvent event) {
        if (protectedWorld(event.getEntity().getWorld())) {
            event.blockList().clear();
        }
    }

    @EventHandler(ignoreCancelled = true)
    public void onBlockExplode(final BlockExplodeEvent event) {
        if (protectedWorld(event.getBlock().getWorld())) {
            event.blockList().clear();
        }
    }

    @EventHandler(ignoreCancelled = true)
    public void onBurn(final BlockBurnEvent event) {
        if (protectedWorld(event.getBlock().getWorld())) {
            event.setCancelled(true);
        }
    }

    @EventHandler(ignoreCancelled = true)
    public void onIgnite(final BlockIgniteEvent event) {
        if (protectedWorld(event.getBlock().getWorld()) && !(event.getPlayer() != null && builder(event.getPlayer()))) {
            event.setCancelled(true);
        }
    }

    @EventHandler(ignoreCancelled = true)
    public void onDecay(final LeavesDecayEvent event) {
        if (protectedWorld(event.getBlock().getWorld())) {
            event.setCancelled(true);
        }
    }

    @EventHandler(ignoreCancelled = true)
    public void onFade(final BlockFadeEvent event) {
        if (protectedWorld(event.getBlock().getWorld())) {
            event.setCancelled(true);
        }
    }

    @EventHandler(ignoreCancelled = true)
    public void onForm(final BlockFormEvent event) {
        if (protectedWorld(event.getBlock().getWorld())) {
            event.setCancelled(true);
        }
    }

    @EventHandler(ignoreCancelled = true)
    public void onSpread(final BlockSpreadEvent event) {
        if (protectedWorld(event.getBlock().getWorld())) {
            event.setCancelled(true);
        }
    }

    @EventHandler(ignoreCancelled = true)
    public void onGrow(final BlockGrowEvent event) {
        if (protectedWorld(event.getBlock().getWorld())) {
            event.setCancelled(true);
        }
    }

    @EventHandler(ignoreCancelled = true)
    public void onStructureGrow(final StructureGrowEvent event) {
        if (protectedWorld(event.getWorld())) {
            event.setCancelled(true);
        }
    }

    @EventHandler(ignoreCancelled = true)
    public void onFlow(final BlockFromToEvent event) {
        if (protectedWorld(event.getBlock().getWorld())) {
            event.setCancelled(true);
        }
    }
}
