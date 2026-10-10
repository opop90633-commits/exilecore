package tw.exilecore.skill;

import org.bukkit.entity.LivingEntity;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.entity.EntityDeathEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import tw.exilecore.ExileCore;
import tw.exilecore.character.PlayerState;
import tw.exilecore.character.SkillLoadout;
import tw.exilecore.stats.HitEvent;
import tw.exilecore.stats.StatKeys;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * 觸發（2026-10，照 PoE2 的 Cast on X）：掛了觸發輔助的法術，條件成立時自動施放（不花魔力，每個技能有 {@value #COOLDOWN} tick 的內置冷卻）：
 * 攻擊暴擊（trigger.crit）、被敵人擊中（trigger.hit_taken）、擊殺（trigger.kill）、讓敵人點燃／冰緩／感電（trigger.ailment）。
 * 要綁在上方按鍵格的技能才會被觸發（2026-10；不用按）。
 */
public final class SkillTriggers implements Listener {

    /** 同一個技能被觸發的最短間隔（tick）。 */
    public static final long COOLDOWN = 15;

    private final ExileCore plugin;
    private final SkillService service;
    private final Map<UUID, Map<String, Long>> ready = new HashMap<>();
    private boolean firing;

    SkillTriggers(final ExileCore plugin, final SkillService service) {
        this.plugin = plugin;
        this.service = service;
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onHit(final HitEvent event) {
        if (!event.result().hit() || event.result().blocked()) {
            return;
        }
        if (event.attacker() instanceof Player player && event.result().crit() && event.skill() != null && event.skill().isAttack()) {
            fire(player, StatKeys.TRIGGER_CRIT, event.defender());
        }
        if (event.attacker() instanceof Player player && event.result().crit() && plugin.combat() != null
                && plugin.combat().debuffs().remove(event.defender().getUniqueId(), "hunters_mark", player.getUniqueId(),
                plugin.getServer().getCurrentTick())) {
            // 狙擊印記（2026-10 PoE2）：暴擊用掉印記，給一顆怒星
            if (plugin.combat().charges() != null) {
                plugin.combat().charges().grant(player, "frenzy", 1);
            }
        }
        if (event.attacker() instanceof Player player && event.result().dealt().get(tw.exilecore.stats.DamageType.FIRE) > 0
                && event.skill() != null && !event.skill().id().endsWith(".zone")) {
            service.zones().detonateGas(player, event.defender().getLocation()); // 火焰引爆毒霧（2026-10 PoE2）
        }
        if (event.defender() instanceof Player player && !(event.attacker() instanceof Player)) {
            fire(player, StatKeys.TRIGGER_HIT_TAKEN, event.attacker());
        }
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onDeath(final EntityDeathEvent event) {
        final Player killer = event.getEntity().getKiller();
        if (killer != null && !(event.getEntity() instanceof Player)) {
            fire(killer, StatKeys.TRIGGER_KILL, null);
        }
    }

    @EventHandler
    public void onQuit(final PlayerQuitEvent event) {
        ready.remove(event.getPlayer().getUniqueId());
    }

    /** 翻滾時（DodgeRoll 呼叫，2026-10）。 */
    public void dodge(final Player player) {
        fire(player, StatKeys.TRIGGER_DODGE, null);
        if (StatKeys.flag(plugin.players().sheet(player), StatKeys.FLAG_DODGE_CALTROPS)) {
            service.procs().castAt(player, "proc_caltrops", "trail_of_caltrops", player.getLocation()); // 蒺藜步（2026-10）
        }
        if (StatKeys.flag(plugin.players().sheet(player), StatKeys.FLAG_DODGE_DECOY) && service.minions() != null) {
            service.procs().decoy(player, "proc_phantom_decoy", "phantom_step", player.getLocation()); // 殘影（2026-10）
        }
    }

    /** 自己的召喚物死掉時（MinionService 呼叫，2026-10）：找牠倒下的地方附近的敵人當目標。 */
    public void minionDeath(final Player owner, final org.bukkit.Location at) {
        LivingEntity target = null;
        double best = 100;
        for (final org.bukkit.entity.Entity near : at.getWorld().getNearbyEntities(at, 6, 4, 6)) {
            if (near instanceof LivingEntity living && !(near instanceof Player) && service.minions() != null && !service.minions().isMinion(living)
                    && living.isValid() && !living.isDead() && plugin.monsters() != null && plugin.monsters().data(living).isPresent()) {
                final double d = living.getLocation().distanceSquared(at);
                if (d < best) {
                    best = d;
                    target = living;
                }
            }
        }
        fire(owner, StatKeys.TRIGGER_MINION_DEATH, target);
    }

    /** 讓敵人點燃、冰緩、感電時（異常模組呼叫）。 */
    public void ailment(final Player player, final LivingEntity target) {
        fire(player, StatKeys.TRIGGER_AILMENT, target);
    }

    /** 條件成立：面板上掛了這種觸發輔助的法術各放一次（冷卻好的）。 */
    void fire(final Player player, final String trigger, final LivingEntity target) {
        if (firing || !service.eligible(player) || service.inTown(player)) {
            return; // 觸發出來的法術打中不會再觸發（避免連鎖）
        }
        final PlayerState state = plugin.players().get(player);
        final List<SkillLoadout.Entry> entries = state.skills().entries();
        if (entries.isEmpty()) {
            return;
        }
        final long now = plugin.getServer().getCurrentTick();
        final Map<String, Long> mine = ready.computeIfAbsent(player.getUniqueId(), uuid -> new HashMap<>());
        for (final SkillLoadout.Entry entry : entries) {
            if (entry == null || mine.getOrDefault(entry.skillId(), 0L) > now) {
                continue;
            }
            final SkillDefinition definition = service.config().skill(entry.skillId());
            if (definition == null || definition.hidden() || definition.type() != SkillType.SPELL || !hasTrigger(entry, trigger)
                    || !AuraService.boundToKey(state, entry.skillId())) {
                continue; // 2026-10：要綁在上方按鍵格才會被觸發（跟光環一樣算在 10 格裡）
            }
            mine.put(entry.skillId(), now + COOLDOWN);
            firing = true;
            try {
                service.triggerCast(player, definition, entry, target != null && target.isValid() && !target.isDead() ? target : null);
            } finally {
                firing = false;
            }
        }
    }

    private boolean hasTrigger(final SkillLoadout.Entry entry, final String trigger) {
        for (final SupportDefinition support : service.supportsOf(entry)) {
            for (final tw.exilecore.stats.Modifier modifier : support.modifiers()) {
                if (modifier.stat().equals(trigger) && modifier.value() > 0) {
                    return true;
                }
            }
        }
        return false;
    }
}
