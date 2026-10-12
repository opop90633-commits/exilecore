package tw.exilecore.item;

import com.destroystokyo.paper.event.inventory.PrepareResultEvent;
import org.bukkit.NamespacedKey;
import org.bukkit.GameMode;
import org.bukkit.Material;
import org.bukkit.block.Container;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.block.CrafterCraftEvent;
import org.bukkit.event.entity.EntityDeathEvent;
import org.bukkit.event.entity.EntityPickupItemEvent;
import org.bukkit.event.inventory.BrewEvent;
import org.bukkit.event.inventory.FurnaceSmeltEvent;
import org.bukkit.event.inventory.PrepareItemCraftEvent;
import org.bukkit.inventory.ItemStack;
import org.bukkit.plugin.Plugin;

/**
 * 我們的物品（裝備、通貨、寶石、藥劑、金幣）不能拿去做原版的合成、鐵砧、砂輪、鍛造台、釀造與熔爐：
 * 原版會把它們當成普通的石英、藥水、鐵劍，做出來的東西沒有我們的資料（例如兩瓶藥劑在合成格裡「修理」成一瓶普通藥水）。
 * <p>
 * 反過來，原版的物品（雞肉、羽毛、雞蛋、骨頭……）跟遊戲無關（2026-10）：動物、沒等級化的生物死掉不掉原版的東西，
 * 玩家（生存、冒險模式）也撿不起來地上的原版物品——只撿得到我們的物品。創造模式照原版（方便管理員蓋東西）。
 */
final class CustomItemGuard implements Listener {

    private final String namespace;

    /**
     * 會從玩家身上清掉的原版物品（2026-10）：動物與怪物的原版掉落。只清這些（而且不是我們的物品），
     * 箭矢、方塊、工具這些不碰，管理員發的原版東西不會被誤刪。
     */
    static final java.util.Set<Material> JUNK = java.util.EnumSet.of(
            Material.CHICKEN, Material.COOKED_CHICKEN, Material.FEATHER, Material.EGG, Material.BROWN_EGG, Material.BLUE_EGG,
            Material.BEEF, Material.COOKED_BEEF, Material.PORKCHOP, Material.COOKED_PORKCHOP, Material.MUTTON, Material.COOKED_MUTTON,
            Material.RABBIT, Material.COOKED_RABBIT, Material.RABBIT_HIDE, Material.RABBIT_FOOT, Material.LEATHER,
            Material.COD, Material.SALMON, Material.TROPICAL_FISH, Material.PUFFERFISH, Material.INK_SAC, Material.GLOW_INK_SAC,
            Material.ROTTEN_FLESH, Material.BONE, Material.BONE_MEAL, Material.STRING, Material.SPIDER_EYE, Material.GUNPOWDER,
            Material.SLIME_BALL, Material.MAGMA_CREAM, Material.PHANTOM_MEMBRANE, Material.TURTLE_SCUTE, Material.ARMADILLO_SCUTE,
            Material.WHITE_WOOL, Material.SADDLE);

    private final Plugin plugin;

    CustomItemGuard(final Plugin plugin) {
        this.plugin = plugin;
        this.namespace = new NamespacedKey(plugin, "guard").getNamespace();
    }

    /** 這一格是要清掉的原版雜物（不是我們的物品、是動物或怪物的原版掉落）。 */
    boolean isJunk(final ItemStack stack) {
        return stack != null && !stack.isEmpty() && JUNK.contains(stack.getType()) && !isCustom(stack);
    }

    /**
     * 清掉一個玩家背包（含副手、游標上拿著的）裡的原版雜物（2026-10：撿取擋掉之前撿到的生雞肉、羽毛）。
     * 生存、冒險模式才清；回傳清掉幾個。
     */
    int sweep(final Player player) {
        final GameMode mode = player.getGameMode();
        if (mode == GameMode.CREATIVE || mode == GameMode.SPECTATOR) {
            return 0;
        }
        int removed = 0;
        final org.bukkit.inventory.PlayerInventory inventory = player.getInventory();
        for (int slot = 0; slot < inventory.getSize(); slot++) {
            final ItemStack stack = inventory.getItem(slot);
            if (isJunk(stack)) {
                removed += stack.getAmount();
                inventory.setItem(slot, null);
            }
        }
        final ItemStack cursor = player.getItemOnCursor();
        if (isJunk(cursor)) {
            removed += cursor.getAmount();
            player.setItemOnCursor(null);
        }
        if (removed > 0) {
            player.sendMessage(net.kyori.adventure.text.minimessage.MiniMessage.miniMessage().deserialize(
                    "<gray>清掉了背包裡 <white>" + removed + "</white> 個跟遊戲無關的原版物品（生雞肉、羽毛…）"));
        }
        return removed;
    }

    /** 每 30 秒掃一次在線的人（剛上線、換角色載入背包之後也會被掃到）。 */
    void start() {
        plugin.getServer().getScheduler().runTaskTimer(plugin, () -> {
            int total = 0;
            for (final Player player : plugin.getServer().getOnlinePlayers()) {
                total += sweep(player);
            }
            if (total > 0) {
                plugin.getSLF4JLogger().info("清掉玩家身上 {} 個原版雜物（生雞肉、羽毛…）", total);
            }
        }, 20L * 30, 20L * 30);
    }

    /** PDC 裡有這個插件的鍵就是我們的物品。 */
    boolean isCustom(final ItemStack stack) {
        if (stack == null || stack.isEmpty()) {
            return false;
        }
        for (final NamespacedKey key : stack.getPersistentDataContainer().getKeys()) {
            if (key.getNamespace().equals(namespace)) {
                return true;
            }
        }
        return false;
    }

    private boolean any(final ItemStack[] stacks) {
        for (final ItemStack stack : stacks) {
            if (isCustom(stack)) {
                return true;
            }
        }
        return false;
    }

    /**
     * 生物死掉：原版的掉落（肉、羽毛、骨頭）與經驗球拿掉，只留我們的物品。
     * 有等級的怪物 MonsterListener 已經清過；這裡補動物、村民這些沒等級化的生物（被技能的範圍打死也會掉原版的東西）。
     * 玩家自己死掉不管（裝備與背包另外處理）。
     */
    @EventHandler(priority = EventPriority.HIGHEST)
    public void onEntityDeath(final EntityDeathEvent event) {
        if (event.getEntity() instanceof Player) {
            return;
        }
        event.getDrops().removeIf(stack -> !isCustom(stack));
        event.setDroppedExp(0);
    }

    /** 玩家（生存、冒險）撿不起來原版的物品：雞下的蛋、別人丟的雜物都留在地上。 */
    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onPickup(final EntityPickupItemEvent event) {
        if (!(event.getEntity() instanceof Player player)) {
            return;
        }
        final GameMode mode = player.getGameMode();
        if (mode == GameMode.CREATIVE || mode == GameMode.SPECTATOR) {
            return;
        }
        if (!isCustom(event.getItem().getItemStack())) {
            event.setCancelled(true);
        }
    }

    /** 合成格（背包的 2×2、工作台的 3×3，含修理配方）。 */
    @EventHandler(priority = EventPriority.HIGH)
    public void onCraft(final PrepareItemCraftEvent event) {
        if (any(event.getInventory().getMatrix())) {
            event.getInventory().setResult(null);
        }
    }

    /** 鐵砧、砂輪、鍛造台（都是這個事件的子類別）。 */
    @EventHandler(priority = EventPriority.HIGH)
    public void onPrepareResult(final PrepareResultEvent event) {
        if (any(event.getInventory().getContents())) {
            event.setResult(null);
        }
    }

    /** 自動合成器。 */
    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onCrafter(final CrafterCraftEvent event) {
        if (event.getBlock().getState(false) instanceof Container container && any(container.getInventory().getContents())) {
            event.setCancelled(true);
        }
    }

    /** 釀造台：材料或瓶子裡有我們的物品（藥劑是藥水做的外殼）就不釀。 */
    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onBrew(final BrewEvent event) {
        if (any(event.getContents().getContents())) {
            event.setCancelled(true);
        }
    }

    /** 熔爐：鐵劍、金頭盔這種裝備不能被燒成粒。 */
    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onSmelt(final FurnaceSmeltEvent event) {
        if (isCustom(event.getSource())) {
            event.setCancelled(true);
        }
    }
}
