package tw.exilecore.skill;

import org.bukkit.Particle;
import org.bukkit.entity.Entity;
import org.bukkit.entity.LivingEntity;
import org.bukkit.entity.Mob;
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
 * 嘲諷（戰吼、召喚物的吼叫、殘影）：被嘲諷的怪物在持續時間內只打嘲諷牠的人（或召喚物）。
 * 以前只是 setTarget 一次，原版 AI（被別人打、附近有更近的玩家、召喚物）、MythicMobs 的仇恨表馬上又把目標換掉，
 * 看起來就像沒效果。現在記著誰嘲諷了牠、到什麼時候：換目標的事件一律改回嘲諷的人，每半秒也再確認一次。
 * <p>
 * 2026-10-17：所有嘲諷共用這裡——
 * <ul>
 *   <li>身上已經有嘲諷的怪不會被別人（或別隻召喚物）搶走；</li>
 *   <li>嘲諷結束後 {@link #COOLDOWN_TICKS} 內不能再被嘲諷（嘲諷之間的冷卻，不會一直被輪流嘲諷鎖住）。</li>
 * </ul>
 */
final class Taunts implements Listener {

    /** 嘲諷的人離太遠（傳送走了）就放掉。 */
    private static final double MAX_DISTANCE = 32;
    /** 嘲諷結束後多久才能再被嘲諷（tick）。 */
    static final long COOLDOWN_TICKS = 3 * 20;

    private record Taunt(UUID source, long until) {
    }

    private final ExileCore plugin;
    private final Map<UUID, Taunt> taunted = new HashMap<>();
    /** 怪 → 到哪個 tick 之前不能再被嘲諷。 */
    private final Map<UUID, Long> cooldown = new HashMap<>();

    Taunts(final ExileCore plugin) {
        this.plugin = plugin;
    }

    /**
     * 嘲諷：這隻怪 ticks 內只打 source（玩家或召喚物）。
     *
     * @return 有沒有嘲諷到（身上已經有嘲諷、還在冷卻中就沒有）
     */
    boolean taunt(final Mob mob, final LivingEntity source, final long ticks) {
        if (!canTaunt(mob)) {
            return false;
        }
        final long until = now() + Math.max(20, ticks);
        taunted.put(mob.getUniqueId(), new Taunt(source.getUniqueId(), until));
        cooldown.put(mob.getUniqueId(), until + COOLDOWN_TICKS);
        mob.setTarget(source);
        tw.exilecore.skill.Fx.spawn(mob.getWorld(), Particle.ANGRY_VILLAGER, mob.getLocation().add(0, mob.getHeight() + 0.3, 0), 1, 0.1, 0.1, 0.1, 0);
        return true;
    }

    /** 這隻怪現在能不能被嘲諷：身上沒有嘲諷、也不在冷卻中。 */
    boolean canTaunt(final Entity mob) {
        if (taunter(mob) != null) {
            return false;
        }
        final Long until = cooldown.get(mob.getUniqueId());
        return until == null || now() >= until;
    }

    /** 還在嘲諷中就回傳嘲諷的人或召喚物（不然 null，順便清掉）。 */
    private LivingEntity taunter(final Entity entity) {
        final Taunt taunt = taunted.get(entity.getUniqueId());
        if (taunt == null) {
            return null;
        }
        final LivingEntity source = alive(taunt.source());
        if (now() > taunt.until() || source == null || !source.getWorld().equals(entity.getWorld())
                || source.getLocation().distanceSquared(entity.getLocation()) > MAX_DISTANCE * MAX_DISTANCE) {
            taunted.remove(entity.getUniqueId());
            return null;
        }
        return source;
    }

    private LivingEntity alive(final UUID uuid) {
        final Entity entity = plugin.getServer().getEntity(uuid);
        return entity instanceof LivingEntity living && living.isValid() && !living.isDead() ? living : null;
    }

    private long now() {
        return plugin.getServer().getCurrentTick();
    }

    @EventHandler(priority = EventPriority.HIGHEST)
    public void onTarget(final EntityTargetEvent event) {
        if (taunted.isEmpty()) {
            return;
        }
        final LivingEntity source = taunter(event.getEntity());
        if (source == null || source.equals(event.getTarget())) {
            return;
        }
        event.setCancelled(false);
        event.setTarget(source);
    }

    @EventHandler
    public void onDeath(final EntityDeathEvent event) {
        taunted.remove(event.getEntity().getUniqueId());
        cooldown.remove(event.getEntity().getUniqueId());
    }

    /** 每半秒：被嘲諷的怪目標不是嘲諷的人就改回來（有些 AI 換目標不發事件）；過期的冷卻清掉。 */
    void tick() {
        final long now = now();
        if (!cooldown.isEmpty()) {
            cooldown.values().removeIf(until -> now >= until);
        }
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
            final LivingEntity source = alive(entry.getValue().source());
            if (now > entry.getValue().until() || source == null || !source.getWorld().equals(mob.getWorld())
                    || source.getLocation().distanceSquared(mob.getLocation()) > MAX_DISTANCE * MAX_DISTANCE) {
                iterator.remove();
                continue;
            }
            if (!source.equals(mob.getTarget())) {
                mob.setTarget(source);
            }
        }
    }

    void clear() {
        taunted.clear();
        cooldown.clear();
    }
}
