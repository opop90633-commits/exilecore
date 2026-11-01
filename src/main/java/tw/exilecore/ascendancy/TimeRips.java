package tw.exilecore.ascendancy;

import org.bukkit.Location;
import org.bukkit.Particle;
import org.bukkit.Sound;
import org.bukkit.entity.LivingEntity;
import org.bukkit.entity.Mob;
import org.bukkit.entity.Player;
import tw.exilecore.ExileCore;
import tw.exilecore.ailment.AilmentConfig;
import tw.exilecore.ailment.AilmentService;
import tw.exilecore.monster.MonsterData;
import tw.exilecore.stats.HitEvent;

import java.util.HashMap;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * 2026-11-01 照 PoE2 Moment of Vulnerability（乘隙之機；PoB2 MomentOfVulnerabilityPlayer：time_rip_damage_share_%、
 * rip_from_time_immunity_duration_ms 10000）：
 * 「Hitting enemies that are Primed for Stun, Electrocution, or Freeze causes a visage of them to appear, lasting as long as the original
 * target would have been affected by the form of Immobilisation used to create the visage. A portion of the damage dealt to visages is also
 * dealt to the original.」
 * <ul>
 *   <li>打中蓄勢待凍／待電／待暈（照這個順序挑一種）的怪：在牠站的地方拉出一個時間殘影（同種怪、同等級稀有度、沒有詞綴、
 *       不會動也不會打人、不掉東西），存在凍結／電擊／重暈的基礎時間那麼久。</li>
 *   <li>同一隻怪 10 秒內只會被拉出一次殘影；殘影自己不會再被拉出殘影。</li>
 *   <li>玩家打殘影造成的傷害有 share%（技能等級 60→128%，品質每 1% +1%）當作固定傷害（不吃加成、不暴擊）也打在本體上。</li>
 * </ul>
 * 跟原作不同：原作的召喚物打殘影也會分擔；這裡只算玩家自己的擊中（AscendancyEffects 的擊中入口只有玩家）。
 * 這次之前說明寫了，但沒有殘影（品質寫「沒有效果」）。
 */
final class TimeRips {

    /** 同一隻怪多久之內不會再被拉出殘影（tick；rip_from_time_immunity_duration_ms 10000）。 */
    static final long IMMUNITY_TICKS = 200;

    /** 殘影是哪一種禁錮拉出來的。 */
    enum Form { FREEZE, ELECTROCUTE, STUN }

    private final AscendancyEffects effects;
    private final ExileCore plugin;
    /** 殘影 → 本體。 */
    private final Map<UUID, Visage> visages = new HashMap<>();
    /** 本體 → 什麼時候之後才能再被拉出殘影。 */
    private final Map<UUID, Long> immune = new HashMap<>();

    private record Visage(LivingEntity entity, LivingEntity original, long until, double share) {
    }

    TimeRips(final AscendancyEffects effects) {
        this.effects = effects;
        this.plugin = effects.plugin;
    }

    /** 這隻是不是時間殘影。 */
    boolean isVisage(final LivingEntity entity) {
        return entity != null && visages.containsKey(entity.getUniqueId());
    }

    /**
     * 玩家打中怪物之後（AscGranted.hit）。打的是殘影：分擔傷害給本體；share ≥ 0（乘隙之機打中的）而且目標蓄勢待禁錮：拉出殘影。
     *
     * @param share 乘隙之機的分擔比例（%）；不是乘隙之機的擊中給 −1
     */
    void hit(final Player player, final HitEvent event, final MonsterData data, final double share) {
        final LivingEntity target = event.defender();
        final long now = effects.now();
        final Visage visage = visages.get(target.getUniqueId());
        if (visage != null) {
            final LivingEntity original = visage.original();
            final double amount = sharedDamage(event.result().total(), visage.share);
            if (amount > 0 && original.isValid() && !original.isDead() && plugin.ailments() != null) {
                plugin.ailments().dealFlatPhysical(original, amount, player.getUniqueId());
                original.getWorld().spawnParticle(Particle.REVERSE_PORTAL, original.getLocation().add(0, 1, 0), 8, 0.3, 0.5, 0.3, 0.02);
            }
            return;
        }
        // 原版的 Boss 實體（終界龍、凋零怪）不拉殘影：生成一隻會帶出 Boss 血條與原版行為
        if (share < 0 || data == null || plugin.ailments() == null || target instanceof Player || !(target instanceof Mob)
                || target instanceof org.bukkit.entity.Boss) {
            return;
        }
        cleanup(now);
        final Long until = immune.get(target.getUniqueId());
        if (until != null && until > now) {
            return;
        }
        final AilmentService ailments = plugin.ailments();
        final Form form = form(ailments.isPrimedForFreeze(target), ailments.isPrimedForElectrocute(target), ailments.isPrimed(target));
        if (form == null) {
            return;
        }
        final long ticks = Math.max(1, Math.round(seconds(ailments.config(), form) * 20));
        immune.put(target.getUniqueId(), now + IMMUNITY_TICKS);
        spawn(target, data, form, ticks, share, now);
    }

    /** 挑哪一種禁錮拉出殘影（凍結 → 電擊 → 暈眩）；都不是回傳 null。 */
    static Form form(final boolean freeze, final boolean electrocute, final boolean stun) {
        return freeze ? Form.FREEZE : electrocute ? Form.ELECTROCUTE : stun ? Form.STUN : null;
    }

    /** 打殘影 dealt 點傷害，本體分到多少（share%；負的、0 都是 0）。 */
    static double sharedDamage(final double dealt, final double share) {
        return dealt <= 0 || share <= 0 ? 0 : dealt * share / 100.0;
    }

    /** 那種禁錮的基礎時間（秒，打怪物）。 */
    private static double seconds(final AilmentConfig config, final Form form) {
        return switch (form) {
            case FREEZE -> config.freeze().duration(false);
            case ELECTROCUTE -> config.electrocute().duration(false);
            case STUN -> config.stun().duration(false);
        };
    }

    private void spawn(final LivingEntity original, final MonsterData data, final Form form, final long ticks, final double share, final long now) {
        final Class<? extends org.bukkit.entity.Entity> type = original.getType().getEntityClass();
        if (type == null || !LivingEntity.class.isAssignableFrom(type) || plugin.monsters() == null) {
            return;
        }
        final Location at = original.getLocation().clone();
        final LivingEntity entity;
        try {
            // world.spawn 的生成原因是 CUSTOM：MonsterListener 不會替它抽詞綴，這裡自己裝飾
            entity = (LivingEntity) at.getWorld().spawn(at, type, spawned -> {
                spawned.setPersistent(false);
                spawned.setSilent(true);
                spawned.setGlowing(true);
                if (spawned instanceof Mob mob) {
                    mob.setAI(false);
                }
                if (spawned instanceof LivingEntity living) {
                    living.setRemoveWhenFarAway(true);
                    living.setCollidable(false);
                }
            });
        } catch (final RuntimeException e) {
            return; // 這種怪不能這樣生成（少數特殊實體）：就不拉殘影
        }
        plugin.monsters().decorateExact(entity, new MonsterData(data.level(), data.rarity(), List.of(), "時間殘影",
                data.lifeMultiplier(), data.damageMultiplier(), false), Math.max(1, plugin.monsters().playersAround(original)));
        plugin.monsters().markSummoned(entity); // 不掉東西、不給經驗
        final org.bukkit.attribute.AttributeInstance max = entity.getAttribute(org.bukkit.attribute.Attribute.MAX_HEALTH);
        if (max != null) {
            entity.setHealth(max.getValue());
        }
        visages.put(entity.getUniqueId(), new Visage(entity, original, now + ticks, share));
        at.getWorld().spawnParticle(form == Form.FREEZE ? Particle.SNOWFLAKE : form == Form.ELECTROCUTE ? Particle.ELECTRIC_SPARK : Particle.CRIT,
                at.clone().add(0, 1, 0), 24, 0.4, 0.7, 0.4, 0.05);
        at.getWorld().playSound(at, Sound.BLOCK_AMETHYST_BLOCK_RESONATE, 0.8f, 0.6f);
        effects.later(ticks, () -> expire(entity.getUniqueId()));
    }

    private void expire(final UUID id) {
        final Visage visage = visages.remove(id);
        if (visage != null && visage.entity().isValid()) {
            visage.entity().getWorld().spawnParticle(Particle.REVERSE_PORTAL, visage.entity().getLocation().add(0, 1, 0), 20, 0.3, 0.6, 0.3, 0.05);
            if (plugin.monsters() != null) {
                plugin.monsters().forget(id);
            }
            visage.entity().remove();
        }
    }

    private void cleanup(final long now) {
        immune.values().removeIf(until -> until <= now);
        for (final Iterator<Map.Entry<UUID, Visage>> it = visages.entrySet().iterator(); it.hasNext(); ) {
            final Visage visage = it.next().getValue();
            if (!visage.entity().isValid() || visage.entity().isDead()) {
                it.remove();
            }
        }
    }
}
