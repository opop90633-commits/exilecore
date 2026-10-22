package tw.exilecore.skill;

import org.bukkit.entity.LivingEntity;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.entity.EntityDeathEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import tw.exilecore.ExileCore;
import tw.exilecore.stats.HitEvent;
import tw.exilecore.stats.StatKeys;


/**
 * 觸發（2026-10 第二版，照 PoE2 的主要寶石 Cast on X；見 {@link MetaGems}）：條件成立時，開著的那種主要寶石累積能量，
 * 滿了就觸發插在它第 1 孔裡的法術。以前的觸發輔助（暴擊觸發、受擊觸發…）已經拿掉（supports.yml 的 retired）。
 * <ul>
 *   <li>暴擊時（crit）：你用技能暴擊敵人，能量 × 怪物強度（被觸發的法術自己的暴擊不算）。</li>
 *   <li>元素異常（ailment）：冰凍 ×3、感電與點燃 ×1，再乘怪物強度。</li>
 *   <li>翻滾（dodge）、被敵人擊中（damage_taken）、召喚物死掉（minion_death）、近戰擊殺（melee_kill，乘怪物強度）。</li>
 * </ul>
 */
public final class SkillTriggers implements Listener {

    private final ExileCore plugin;
    private final SkillService service;

    SkillTriggers(final ExileCore plugin, final SkillService service) {
        this.plugin = plugin;
        this.service = service;
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onHit(final HitEvent event) {
        if (!event.result().hit() || event.result().blocked()) {
            return;
        }
        // 2026-10-21：圖騰、英靈打中的不給主要寶石能量、不用掉狙擊印記（照 PoE：圖騰的擊中不算你的）；引爆毒霧照樣
        final boolean totem = event.skill() != null && event.skill().tags().contains(tw.exilecore.stats.Tags.TOTEM_HIT);
        if (!totem && event.attacker() instanceof Player player && event.result().crit() && event.skill() != null
                && !event.skill().id().contains(MetaGems.TRIGGERED_SUFFIX)) {
            service.metas().gain(player, MetaSpec.Kind.CRIT, service.metas().power(event.defender()), event.defender());
        }
        if (!totem && event.attacker() instanceof Player player && event.result().crit() && plugin.combat() != null
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
            service.metas().gain(player, MetaSpec.Kind.DAMAGE_TAKEN, 1, event.attacker());
        }
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onDeath(final EntityDeathEvent event) {
        final Player killer = event.getEntity().getKiller();
        if (killer == null || event.getEntity() instanceof Player) {
            return;
        }
        final String skillId = service.lastSkill(event.getEntity(), killer);
        final SkillDefinition skill = skillId == null ? null : service.config().skill(skillId);
        if (skill != null && skill.tags().contains(tw.exilecore.stats.Tags.MELEE)) {
            service.metas().gain(killer, MetaSpec.Kind.MELEE_KILL, service.metas().power(event.getEntity()), null);
        }
    }

    @EventHandler
    public void onQuit(final PlayerQuitEvent event) {
        service.metas().forget(event.getPlayer().getUniqueId());
    }

    /** 翻滾時（DodgeRoll 呼叫，2026-10）。 */
    public void dodge(final Player player) {
        service.metas().gain(player, MetaSpec.Kind.DODGE, 1, null);
        if (plugin.combat() != null && plugin.combat().charges() != null) {
            plugin.combat().charges().onEvent(player, "dodge", null, null, null); // 戰星：翻滾時（2026-10）
        }
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
                    && living.isValid() && !living.isDead() && plugin.monsters() != null && plugin.monsters().data(living).isPresent()
                    && (plugin.training() == null || plugin.training().canTarget(living, owner))) {
                final double d = living.getLocation().distanceSquared(at);
                if (d < best) {
                    best = d;
                    target = living;
                }
            }
        }
        service.metas().gain(owner, MetaSpec.Kind.MINION_DEATH, 1, target);
    }

    /**
     * 讓敵人冰凍、感電、點燃時（異常模組呼叫）：冰凍的能量 ×3（照 PoE2）。
     * 主要寶石觸發的法術造成的異常不算（skillId 帶 {@link MetaGems#TRIGGERED_SUFFIX}），不然會自己一直觸發自己。
     */
    public void ailment(final Player player, final LivingEntity target, final tw.exilecore.ailment.AilmentType type, final String skillId) {
        if (skillId != null && skillId.contains(MetaGems.TRIGGERED_SUFFIX)) {
            return;
        }
        final double kind = type == tw.exilecore.ailment.AilmentType.FREEZE ? 3 : 1;
        service.metas().gain(player, MetaSpec.Kind.AILMENT, kind * service.metas().power(target), target);
    }
}
