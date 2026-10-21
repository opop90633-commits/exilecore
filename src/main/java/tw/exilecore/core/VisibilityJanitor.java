package tw.exilecore.core;

import com.destroystokyo.paper.event.entity.EntityRemoveFromWorldEvent;
import org.bukkit.entity.Entity;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.plugin.Plugin;

import java.lang.ref.WeakReference;
import java.lang.reflect.Field;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/**
 * 2026-10-20 效能（spark：CraftPlayer.canSee 的 HashMap 查詢佔整個主執行緒 6～9%）：
 * Paper 每個玩家身上有一張「這個實體對我跟預設不一樣」的表（showEntity／hideEntity 寫進去），實體消失時不會清掉，
 * 只有玩家下線才清。地面標記、圖騰、陷阱、召喚物、名牌、背飾這些會一直生一直消的東西，每個都留一筆，
 * 表越長越大，伺服器每刻追蹤實體時對每個玩家×每個實體都要查這張表，越查越慢。
 * <p>
 * 這裡在實體真的消失（不是區塊卸載）時，把線上每個玩家對它的紀錄清掉：預設看得到的先改成預設看不到，
 * 再 hideEntity（看不到的實體 hideEntity = 把「這個人看得到」那筆拿掉），不會再送一次生成封包。
 * 別的插件寫的紀錄不動（hideEntity 只拿掉我們這個插件的）。
 * <p>
 * 2026-10-21：spark 上 canSee 還是佔 10%，事件漏掉的紀錄另外用 {@link #sweep()} 定時掃：每次一個玩家，
 * 拿掉「實體已經不在、而且只有我們這個插件寫的」紀錄（線上玩家、別的插件寫的不動），每 5 分鐘在主控台報一次表的大小。
 */
public final class VisibilityJanitor implements Listener {

    private final Plugin plugin;

    public VisibilityJanitor(final Plugin plugin) {
        this.plugin = plugin;
    }

    /** CraftPlayer 的 invertedVisibilityEntities（找不到就是 null，掃描整個不做）。 */
    private Field field;
    private boolean looked;
    private int cursor;
    private long roundEntries;
    private int roundMax;
    private String roundMaxName = "";
    private long roundRemoved;
    private int roundPlayers;
    private long lastReport;

    /** 每 10 tick 叫一次：掃一個玩家的可見度表。 */
    public void sweep() {
        final List<? extends Player> online = new ArrayList<>(plugin.getServer().getOnlinePlayers());
        if (online.isEmpty() || !plugin.isEnabled()) {
            return;
        }
        if (cursor >= online.size()) {
            report();
            cursor = 0;
        }
        final Player player = online.get(cursor++);
        final Map<UUID, Set<WeakReference<Plugin>>> map = table(player);
        if (map == null) {
            return;
        }
        final int size = map.size();
        int removed = 0;
        final Iterator<Map.Entry<UUID, Set<WeakReference<Plugin>>>> it = map.entrySet().iterator();
        while (it.hasNext()) {
            final Map.Entry<UUID, Set<WeakReference<Plugin>>> entry = it.next();
            final UUID id = entry.getKey();
            if (plugin.getServer().getPlayer(id) != null || plugin.getServer().getEntity(id) != null) {
                continue; // 還在：照留
            }
            if (onlyOurs(entry.getValue())) {
                it.remove();
                removed++;
            }
        }
        roundPlayers++;
        roundEntries += size;
        roundRemoved += removed;
        if (size > roundMax) {
            roundMax = size;
            roundMaxName = player.getName();
        }
    }

    /** 這筆紀錄只有我們（或已經被回收的插件）寫的。 */
    private boolean onlyOurs(final Set<WeakReference<Plugin>> refs) {
        if (refs == null) {
            return true;
        }
        for (final WeakReference<Plugin> ref : refs) {
            final Plugin owner = ref == null ? null : ref.get();
            if (owner != null && owner != plugin) {
                return false;
            }
        }
        return true;
    }

    private void report() {
        final long now = System.currentTimeMillis();
        if (roundPlayers > 0 && now - lastReport >= 5 * 60_000L) {
            lastReport = now;
            plugin.getSLF4JLogger().info("[可見度表] {} 人：共 {} 筆（平均 {}，最大 {} 筆：{}），這一輪清掉 {} 筆失效的",
                    roundPlayers, roundEntries, roundEntries / roundPlayers, roundMax, roundMaxName, roundRemoved);
        }
        roundEntries = 0;
        roundMax = 0;
        roundMaxName = "";
        roundRemoved = 0;
        roundPlayers = 0;
    }

    @SuppressWarnings("unchecked")
    private Map<UUID, Set<WeakReference<Plugin>>> table(final Player player) {
        if (!looked) {
            looked = true;
            for (Class<?> type = player.getClass(); type != null && field == null; type = type.getSuperclass()) {
                try {
                    field = type.getDeclaredField("invertedVisibilityEntities");
                    field.setAccessible(true);
                } catch (final NoSuchFieldException ignored) {
                    // 再往上找
                } catch (final RuntimeException e) {
                    plugin.getSLF4JLogger().warn("可見度表：拿不到 invertedVisibilityEntities（{}），不掃", e.toString());
                    return null;
                }
            }
            if (field == null) {
                plugin.getSLF4JLogger().warn("可見度表：這個 Paper 版本沒有 invertedVisibilityEntities，不掃");
            }
        }
        if (field == null) {
            return null;
        }
        try {
            return (Map<UUID, Set<WeakReference<Plugin>>>) field.get(player);
        } catch (final ReflectiveOperationException | RuntimeException e) {
            plugin.getSLF4JLogger().debug("可見度表讀取失敗：{}", e.toString());
            return null;
        }
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onRemove(final EntityRemoveFromWorldEvent event) {
        final Entity entity = event.getEntity();
        if (entity instanceof Player || !plugin.isEnabled()) {
            return;
        }
        if (entity.isPersistent() && !entity.isDead()) {
            return; // 區塊卸載：之後還會載回來，看得到／看不到的設定要留著
        }
        final boolean byDefault = entity.isVisibleByDefault();
        List<Player> touched = null;
        for (final Player player : plugin.getServer().getOnlinePlayers()) {
            if (player.canSee(entity) != byDefault) {
                if (touched == null) {
                    touched = new ArrayList<>();
                }
                touched.add(player);
            }
        }
        if (touched == null) {
            return;
        }
        try {
            if (byDefault) {
                entity.setVisibleByDefault(false); // 先翻過來：原本被藏起來的人變成「看得到」那筆，下面 hideEntity 一起拿掉
            }
            for (final Player player : touched) {
                player.hideEntity(plugin, entity);
            }
        } catch (final RuntimeException e) {
            plugin.getSLF4JLogger().debug("清實體可見度紀錄失敗：{}", e.toString());
        }
    }
}
