package tw.exilecore.skill;

import org.bukkit.Particle;
import org.bukkit.entity.Entity;
import org.bukkit.entity.LivingEntity;
import org.bukkit.entity.Mob;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.entity.EntityDeathEvent;
import org.bukkit.event.entity.EntityTargetEvent;
import tw.exilecore.ExileCore;

import java.util.HashMap;
import java.util.Iterator;
import java.util.Map;
import java.util.UUID;

/**
 * 嘲諷（戰吼）：被嘲諷的怪物在持續時間內只打嘲諷牠的人。
 * 以前只是 setTarget 一次，原版 AI（被別人打、附近有更近的玩家、召喚物）、MythicMobs 的仇恨表馬上又把目標換掉，
 * 看起來就像沒效果。現在記著誰嘲諷了牠、到什麼時候：換目標的事件一律改回嘲諷的人，每半秒也再確認一次。
 */
final class Taunts implements Listener {

    /** 嘲諷的人離太遠（傳送走了）就放掉。 */
    private static final double MAX_DISTANCE = 32;

    private record Taunt(UUID player, long until) {
    }

    private final ExileCore plugin;
    private final Map<UUID, Taunt> taunted = new HashMap<>();

    Taunts(final ExileCore plugin) {
        this.plugin = plugin;
    }

    /** 嘲諷：這隻怪 ticks 內只打 player。 */
    void taunt(final Mob mob, final Player player, final long ticks) {
        taunted.put(mob.getUniqueId(), new Taunt(player.getUniqueId(), plugin.getServer().getCurrentTick() + Math.max(20, ticks)));
        mob.setTarget(player);
        tw.exilecore.skill.Fx.spawn(mob.getWorld(), Particle.ANGRY_VILLAGER, mob.getLocation().add(0, mob.getHeight() + 0.3, 0), 1, 0.1, 0.1, 0.1, 0);
    }

    /** 還在嘲諷中就回傳嘲諷的人（不然 null，順便清掉）。 */
    private Player taunter(final Entity entity) {
        final Taunt taunt = taunted.get(entity.getUniqueId());
        if (taunt == null) {
            return null;
        }
        final Player player = plugin.getServer().getPlayer(taunt.player());
        if (plugin.getServer().getCurrentTick() > taunt.until() || player == null || player.isDead()
                || !player.getWorld().equals(entity.getWorld())
                || player.getLocation().distanceSquared(entity.getLocation()) > MAX_DISTANCE * MAX_DISTANCE) {
            taunted.remove(entity.getUniqueId());
            return null;
        }
        return player;
    }

    @EventHandler(priority = EventPriority.HIGHEST)
    public void onTarget(final EntityTargetEvent event) {
        if (taunted.isEmpty()) {
            return;
        }
        final Player player = taunter(event.getEntity());
        if (player == null || player.equals(event.getTarget())) {
            return;
        }
        event.setCancelled(false);
        event.setTarget(player);
    }

    @EventHandler
    public void onDeath(final EntityDeathEvent event) {
        taunted.remove(event.getEntity().getUniqueId());
    }

    /** 每半秒：被嘲諷的怪目標不是嘲諷的人就改回來（有些 AI 換目標不發事件）。 */
    void tick() {
        if (taunted.isEmpty()) {
            return;
        }
        final Iterator<Map.Entry<UUID, Taunt>> iterator = taunted.entrySet().iterator();
        while (iterator.hasNext()) {
            final Map.Entry<UUID, Taunt> entry = iterator.next();
            final Entity entity = plugin.getServer().getEntity(entry.getKey());
            if (!(entity instanceof Mob mob) || !mob.isValid()) {
                iterator.remove();
                continue;
            }
            final Player player = plugin.getServer().getPlayer(entry.getValue().player());
            if (plugin.getServer().getCurrentTick() > entry.getValue().until() || player == null || player.isDead()
                    || !player.getWorld().equals(mob.getWorld())
                    || player.getLocation().distanceSquared(mob.getLocation()) > MAX_DISTANCE * MAX_DISTANCE) {
                iterator.remove();
                continue;
            }
            final LivingEntity current = mob.getTarget();
            if (!player.equals(current)) {
                mob.setTarget(player);
            }
        }
    }

    void clear() {
        taunted.clear();
    }
}
