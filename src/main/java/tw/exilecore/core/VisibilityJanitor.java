package tw.exilecore.core;

import com.destroystokyo.paper.event.entity.EntityRemoveFromWorldEvent;
import org.bukkit.entity.Entity;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.plugin.Plugin;

import java.util.ArrayList;
import java.util.List;

/**
 * 2026-10-20 效能（spark：CraftPlayer.canSee 的 HashMap 查詢佔整個主執行緒 6～9%）：
 * Paper 每個玩家身上有一張「這個實體對我跟預設不一樣」的表（showEntity／hideEntity 寫進去），實體消失時不會清掉，
 * 只有玩家下線才清。地面標記、圖騰、陷阱、召喚物、名牌、背飾這些會一直生一直消的東西，每個都留一筆，
 * 表越長越大，伺服器每刻追蹤實體時對每個玩家×每個實體都要查這張表，越查越慢。
 * <p>
 * 這裡在實體真的消失（不是區塊卸載）時，把線上每個玩家對它的紀錄清掉：預設看得到的先改成預設看不到，
 * 再 hideEntity（看不到的實體 hideEntity = 把「這個人看得到」那筆拿掉），不會再送一次生成封包。
 * 別的插件寫的紀錄不動（hideEntity 只拿掉我們這個插件的）。
 */
public final class VisibilityJanitor implements Listener {

    private final Plugin plugin;

    public VisibilityJanitor(final Plugin plugin) {
        this.plugin = plugin;
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
