package tw.exilecore.item;

import com.destroystokyo.paper.event.inventory.PrepareResultEvent;
import org.bukkit.NamespacedKey;
import org.bukkit.block.Container;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.block.CrafterCraftEvent;
import org.bukkit.event.inventory.BrewEvent;
import org.bukkit.event.inventory.FurnaceSmeltEvent;
import org.bukkit.event.inventory.PrepareItemCraftEvent;
import org.bukkit.inventory.ItemStack;
import org.bukkit.plugin.Plugin;

/**
 * 我們的物品（裝備、通貨、寶石、藥劑、金幣）不能拿去做原版的合成、鐵砧、砂輪、鍛造台、釀造與熔爐：
 * 原版會把它們當成普通的石英、藥水、鐵劍，做出來的東西沒有我們的資料（例如兩瓶藥劑在合成格裡「修理」成一瓶普通藥水）。
 */
final class CustomItemGuard implements Listener {

    private final String namespace;

    CustomItemGuard(final Plugin plugin) {
        this.namespace = new NamespacedKey(plugin, "guard").getNamespace();
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
