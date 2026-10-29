package tw.exilecore.training;

import org.bukkit.attribute.Attribute;
import org.bukkit.attribute.AttributeInstance;
import org.bukkit.entity.Entity;
import org.bukkit.entity.LivingEntity;
import org.bukkit.entity.Mannequin;
import org.bukkit.entity.Player;
import org.bukkit.entity.Projectile;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.entity.EntityDamageByEntityEvent;
import org.bukkit.event.entity.EntityDamageEvent;
import org.bukkit.event.entity.EntityDeathEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import tw.exilecore.stats.HitEvent;
import tw.exilecore.stats.HitResult;

import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

/** 訓練假人：只有玩家打得到、不會死、記錄每一下。 */
final class TrainingListener implements Listener {

    private final tw.exilecore.ExileCore plugin;
    private final TrainingService service;

    TrainingListener(final tw.exilecore.ExileCore plugin, final TrainingService service) {
        this.plugin = plugin;
        this.service = service;
    }

    /** 最前面：不是玩家（或玩家的召喚物）打的傷害取消；玩家打的先換等級、補滿生命（戰鬥模組在 HIGH 算傷害）。 */
    @EventHandler(priority = EventPriority.LOWEST, ignoreCancelled = true)
    public void onDamage(final EntityDamageEvent event) {
        if (!(event.getEntity() instanceof Mannequin dummy) || !service.isDummy(dummy)) {
            return;
        }
        final Player player = event instanceof EntityDamageByEntityEvent byEntity ? attacker(byEntity.getDamager()) : null;
        if (player == null || !service.canTarget(dummy, player)) {
            event.setCancelled(true); // 2026-10：別人那組的假人（每人一組）也打不到
            return;
        }
        service.beforeHit(player, dummy);
    }

    /** 最後：會打死的那一下只扣到剩 1（下一下之前又補滿）。 */
    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void onDamageClamp(final EntityDamageEvent event) {
        if (event.getEntity() instanceof Mannequin dummy && service.isDummy(dummy)) {
            final double safe = Math.max(0, dummy.getHealth() - 1);
            if (event.getDamage() > safe) {
                event.setDamage(safe);
            }
        }
    }

    /** 打中還沒套用的那一下：假人 → （誰打的、結果）。等傷害事件確定沒被取消（MONITOR）才算。 */
    private final Map<UUID, Pending> pending = new HashMap<>();

    private record Pending(Player player, HitResult result, String source) {
    }

    /** 每一下的結果：沒打中、被格擋（或被別的插件取消）馬上算；打中的等傷害事件確定了再算。召喚物打的算主人的。 */
    @EventHandler(priority = EventPriority.MONITOR)
    public void onHit(final HitEvent event) {
        final Player player = event.credit();
        if (player == null || !service.isDummy(event.defender()) || !service.canTarget(event.defender(), player)) {
            return;
        }
        final HitResult result = event.result();
        if (event.isCancelled() || !result.hit() || result.blocked()) {
            service.onHit(player, event.defender(), result, event.isCancelled(), null);
        } else {
            pending.put(event.defender().getUniqueId(), new Pending(player, result, source(event)));
        }
    }

    /** 傷害事件塵埃落定：沒被取消才算打中（戰鬥模組的傷害數字也是這時候才跳）。 */
    @EventHandler(priority = EventPriority.MONITOR)
    public void onDamageDone(final EntityDamageEvent event) {
        final Pending hit = pending.remove(event.getEntity().getUniqueId());
        if (hit != null && !event.isCancelled() && event.getEntity() instanceof LivingEntity dummy && hit.player().isOnline()) {
            service.onHit(hit.player(), dummy, hit.result(), false, hit.source());
        }
    }

    /** 2026-10-29：這一下是哪個技能打的（總結裡分來源）：技能的名字；召喚物打的寫「召喚物」；普攻、不知道回傳 null。 */
    private String source(final HitEvent event) {
        if (!(event.attacker() instanceof Player)) {
            final String id = plugin.combat() == null ? null : plugin.combat().skillDamage().currentSkill(event.defender());
            return id == null ? "召喚物" : "召喚物：" + skillName(id);
        }
        final String id = plugin.combat() == null ? null : plugin.combat().skillDamage().currentSkill(event.defender());
        return id == null ? null : skillName(id);
    }

    private String skillName(final String profileId) {
        final int dot = profileId.indexOf('.');
        final String id = dot < 0 ? profileId : profileId.substring(0, dot);
        final tw.exilecore.skill.SkillDefinition definition = plugin.skills() == null ? null : plugin.skills().config().skill(id);
        return definition == null ? id : definition.name() + (dot < 0 ? "" : "（追加）");
    }

    /** 萬一還是死了（例如 /kill）：取消，生命補滿（怪物模組的掉落、經驗都不會跑）。 */
    @EventHandler(priority = EventPriority.LOWEST)
    public void onDeath(final EntityDeathEvent event) {
        if (!service.isDummy(event.getEntity())) {
            return;
        }
        event.setCancelled(true);
        final AttributeInstance max = event.getEntity().getAttribute(Attribute.MAX_HEALTH);
        event.setReviveHealth(max == null ? 1 : max.getValue());
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onQuit(final PlayerQuitEvent event) {
        service.forget(event.getPlayer());
    }

    /** 這一下算誰打的：玩家、玩家射的、玩家的召喚物（算主人的）；其他（怪物、環境）是 null。 */
    private Player attacker(final Entity damager) {
        if (damager instanceof Player player) {
            return player;
        }
        if (damager instanceof Projectile projectile && projectile.getShooter() instanceof Player player) {
            return player;
        }
        if (damager instanceof LivingEntity living && plugin.combat() != null && plugin.combat().minions() != null) {
            final tw.exilecore.combat.MinionHooks.Attack attack = plugin.combat().minions().attack(living);
            if (attack != null) {
                return attack.owner();
            }
        }
        return null;
    }
}
