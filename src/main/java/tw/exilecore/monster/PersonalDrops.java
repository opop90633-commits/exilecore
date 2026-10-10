package tw.exilecore.monster;

import com.destroystokyo.paper.event.entity.EntityAddToWorldEvent;
import com.destroystokyo.paper.event.entity.EntityRemoveFromWorldEvent;
import org.bukkit.NamespacedKey;
import org.bukkit.entity.Entity;
import org.bukkit.entity.Item;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.entity.ItemMergeEvent;
import org.bukkit.event.player.PlayerChangedWorldEvent;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.inventory.ItemStack;
import org.bukkit.persistence.PersistentDataType;
import tw.exilecore.ExileCore;

import java.util.UUID;

/**
 * 個人掉落（掉在地上的那份）：一段時間內只有主人看得到、撿得到，之後誰都能撿。
 * 期限存在物品實體上（區塊卸載、重新登入、伺服器重開都照算）：區塊載入時到期就放開、還沒到期就只給主人看；
 * 主人上線時重新讓他看得到自己的東西。
 * <p>
 * 永遠的（{@link #prepareForever}）：玩家自己丟出來的東西、背包放不下掉出來的、金幣——一直只有主人看得到、撿得到，
 * 隊友也看不到（東西和金幣不能經過地上換手）。
 */
public final class PersonalDrops implements Listener {

    /** 只有主人看得到、撿得到的時間。 */
    public static final long OWNER_MILLIS = 60_000;
    /** 期限寫這個值＝永遠只屬於主人，不會放開。 */
    private static final long FOREVER = Long.MAX_VALUE;

    private final ExileCore plugin;
    private final NamespacedKey untilKey;

    public PersonalDrops(final ExileCore plugin) {
        this.plugin = plugin;
        this.untilKey = new NamespacedKey(plugin, "personal_until");
    }

    /** 在物品生成之前設定（{@code dropItemNaturally} 的回呼裡呼叫）：主人、別人看不到、期限。 */
    public void prepare(final Item item, final Player owner) {
        item.setOwner(owner.getUniqueId());
        item.setVisibleByDefault(false);
        item.getPersistentDataContainer().set(untilKey, PersistentDataType.LONG, System.currentTimeMillis() + OWNER_MILLIS);
    }

    /** 同上，但永遠只屬於主人（不會放開）。主人可以不在線（例如登出時掉出來的）。 */
    public void prepareForever(final Item item, final UUID owner) {
        item.setOwner(owner);
        item.setVisibleByDefault(false);
        item.getPersistentDataContainer().set(untilKey, PersistentDataType.LONG, FOREVER);
    }

    /**
     * 背包放不下的東西掉在主人腳下：永遠只有他看得到、撿得到，也不會自己消失。
     * 市集、商人、拆解、寶石、藥劑、新手裝備、管理員給東西，放不下時都走這裡。
     */
    public Item dropForever(final Player owner, final ItemStack stack) {
        final Item item = owner.getWorld().dropItemNaturally(owner.getLocation(), stack, created -> {
            prepareForever(created, owner.getUniqueId());
            created.setUnlimitedLifetime(true);
        });
        spawned(item, owner);
        return item;
    }

    /**
     * 永遠只屬於主人的東西不跟會放開的（60 秒的個人掉落）疊在一起：
     * 原版只看主人一樣就合併，合進 60 秒的那堆之後會跟著放開，變成誰都看得到、撿得到。
     */
    @EventHandler(ignoreCancelled = true)
    public void onMerge(final ItemMergeEvent event) {
        if (forever(event.getEntity()) != forever(event.getTarget())) {
            event.setCancelled(true);
        }
    }

    private boolean forever(final Item item) {
        final Long until = item.getPersistentDataContainer().get(untilKey, PersistentDataType.LONG);
        return until != null && until == FOREVER;
    }

    /** 物品生成之後：給主人看，有期限的時間到放開。 */
    public void spawned(final Item item, final Player owner) {
        owner.showEntity(plugin, item);
        final Long until = item.getPersistentDataContainer().get(untilKey, PersistentDataType.LONG);
        if (until != null && until != FOREVER) {
            scheduleRelease(item.getUniqueId(), until - System.currentTimeMillis());
        }
    }

    private void scheduleRelease(final UUID id, final long millis) {
        final long ticks = Math.max(1, millis / 50 + 1);
        plugin.getServer().getScheduler().runTaskLater(plugin, () -> {
            final Entity entity = plugin.getServer().getEntity(id);
            if (entity instanceof Item item && item.isValid()) {
                check(item);
            }
            // 不在載入的區塊裡：下次載入時（onAdd）再處理
        }, ticks);
    }

    /** 到期就放開，還沒就再排一次。 */
    private void check(final Item item) {
        final Long until = item.getPersistentDataContainer().get(untilKey, PersistentDataType.LONG);
        if (until == null || until == FOREVER) {
            return;
        }
        final long left = until - System.currentTimeMillis();
        if (left <= 0) {
            release(item);
        } else {
            scheduleRelease(item.getUniqueId(), left);
        }
    }

    private void release(final Item item) {
        item.getPersistentDataContainer().remove(untilKey);
        item.setOwner(null);
        item.setVisibleByDefault(true);
    }

    /** 區塊載入（包含伺服器重開後）：到期就放開，還沒到期就只給主人看。 */
    @EventHandler
    public void onAdd(final EntityAddToWorldEvent event) {
        if (!(event.getEntity() instanceof Item item)) {
            return;
        }
        final Long until = item.getPersistentDataContainer().get(untilKey, PersistentDataType.LONG);
        if (until == null) {
            return;
        }
        final long left = until - System.currentTimeMillis();
        if (left <= 0 || item.getOwner() == null) {
            release(item);
            return;
        }
        item.setVisibleByDefault(false);
        final Player owner = plugin.getServer().getPlayer(item.getOwner());
        if (owner != null) {
            owner.showEntity(plugin, item);
        }
        if (until != FOREVER) {
            scheduleRelease(item.getUniqueId(), left);
        }
    }

    /** 物品被撿走、消失、區塊卸載：把主人那邊「看得到這個實體」的紀錄清掉（不然會一直累積到下線）。 */
    @EventHandler
    public void onRemove(final EntityRemoveFromWorldEvent event) {
        if (!(event.getEntity() instanceof Item item) || !item.getPersistentDataContainer().has(untilKey)) {
            return;
        }
        final UUID ownerId = item.getOwner();
        final Player owner = ownerId == null ? null : plugin.getServer().getPlayer(ownerId);
        if (owner != null) {
            owner.hideEntity(plugin, item);
        }
    }

    /** 主人上線：重新看得到自己還沒到期的東西（「給誰看」只在一次連線內有效）。 */
    @EventHandler(priority = EventPriority.MONITOR)
    public void onJoin(final PlayerJoinEvent event) {
        showOwn(event.getPlayer());
    }

    /** 換世界（例如從地圖回營地、重新登入後才走進地圖）：那個世界裡自己的東西也要看得到。 */
    @EventHandler(priority = EventPriority.MONITOR)
    public void onWorldChange(final PlayerChangedWorldEvent event) {
        showOwn(event.getPlayer());
    }

    private void showOwn(final Player player) {
        for (final Item item : player.getWorld().getEntitiesByClass(Item.class)) {
            if (player.getUniqueId().equals(item.getOwner()) && item.getPersistentDataContainer().has(untilKey)) {
                player.showEntity(plugin, item);
            }
        }
    }
}
