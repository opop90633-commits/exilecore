package tw.exilecore.ops;

import org.bukkit.event.Event;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.entity.EntityDamageByEntityEvent;
import org.bukkit.event.entity.EntityDeathEvent;
import tw.exilecore.core.Perf;

import java.util.IdentityHashMap;
import java.util.Map;

/**
 * 2026-10-27：整個事件所有處理（所有插件、所有優先度）一共花多久，給 /ec perf 看：最早（LOWEST）記下開始的時間、
 * 最後（MONITOR）記結束。死亡事件是 death.handlers（掉落、經驗、擊殺效果、任務…全部加起來），
 * 實體互打是 hit.event（combat.hit 只算我們自己那個處理）。事件裡又觸發同一種事件（爆炸打死別隻）也分得開（每個事件物件各記各的）。
 */
final class EventSpans implements Listener {

    /** 記著的開始時間太多（有事件沒走到 MONITOR）就整個清掉，不會越積越多。 */
    private static final int MAX_OPEN = 4096;

    private final Perf perf;
    private final Map<Event, Long> open = new IdentityHashMap<>();

    EventSpans(final Perf perf) {
        this.perf = perf;
    }

    private void begin(final Event event) {
        if (open.size() >= MAX_OPEN) {
            open.clear();
        }
        open.put(event, System.nanoTime());
    }

    private void end(final Event event, final String name) {
        final Long start = open.remove(event);
        if (start != null) {
            perf.record(name, System.nanoTime() - start);
        }
    }

    @EventHandler(priority = EventPriority.LOWEST)
    public void deathStart(final EntityDeathEvent event) {
        begin(event);
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void deathEnd(final EntityDeathEvent event) {
        end(event, "death.handlers");
    }

    @EventHandler(priority = EventPriority.LOWEST)
    public void hitStart(final EntityDamageByEntityEvent event) {
        begin(event);
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void hitEnd(final EntityDamageByEntityEvent event) {
        end(event, "hit.event");
    }
}
