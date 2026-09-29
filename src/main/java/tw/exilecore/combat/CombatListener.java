package tw.exilecore.combat;

import org.bukkit.entity.Entity;
import org.bukkit.entity.LivingEntity;
import org.bukkit.entity.Player;
import org.bukkit.entity.Projectile;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.entity.EntityDamageByEntityEvent;
import org.bukkit.event.entity.EntityDamageEvent;
import org.bukkit.projectiles.ProjectileSource;
import tw.exilecore.ExileCore;
import tw.exilecore.character.PlayerState;
import tw.exilecore.monster.MonsterData;
import tw.exilecore.stats.DamageCalculator;
import tw.exilecore.stats.DamagePacket;
import tw.exilecore.stats.DamageType;
import tw.exilecore.stats.Formulas;
import tw.exilecore.stats.HitEvent;
import tw.exilecore.stats.HitResult;
import tw.exilecore.stats.SkillProfile;
import tw.exilecore.stats.StatSheet;
import tw.exilecore.stats.Tags;

import java.util.HashMap;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

/**
 * 接管原版傷害（設計文件「傷害計算流程」與「與原版規則的切割」）：
 * 原版的傷害數字一律丟掉，命中只是觸發器；
 * 玩家打怪物 → 算完把數字寫回事件（怪物的原版護甲已歸零）；
 * 怪物打玩家 → 扣我們自己的生命／護盾，原版傷害設為 0，生命歸零才讓原版處理死亡。
 */
public final class CombatListener implements Listener {

    /** 生命歸零時交給原版殺死玩家用的傷害。 */
    private static final double LETHAL = 1_000_000;

    private final ExileCore plugin;
    private final EnvironmentDamage environment;
    /** 週期性環境傷害（火、岩漿）的限速：玩家 → 上次套用的 tick。 */
    private final Map<UUID, Long> environmentCooldown = new HashMap<>();

    public CombatListener(final ExileCore plugin, final EnvironmentDamage environment) {
        this.plugin = plugin;
        this.environment = environment;
    }

    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onDamageByEntity(final EntityDamageByEntityEvent event) {
        if (!(event.getEntity() instanceof LivingEntity defender)) {
            return;
        }
        final LivingEntity attacker = resolveAttacker(event.getDamager());
        if (attacker == null) {
            // 例如 TNT、掉落的鐵砧：走環境傷害
            return;
        }
        final boolean attackerIsPlayer = attacker instanceof Player;
        final boolean defenderIsPlayer = defender instanceof Player;
        if (!attackerIsPlayer && !defenderIsPlayer) {
            return; // 怪物互打交給原版
        }

        // 攻擊方的屬性表與技能
        final StatSheet attackerSheet;
        final SkillProfile skill;
        if (attackerIsPlayer) {
            final Player player = (Player) attacker;
            final PlayerState state = plugin.players().get(player);
            attackerSheet = plugin.players().sheet(player);
            if (event.getCause() == EntityDamageEvent.DamageCause.ENTITY_SWEEP_ATTACK) {
                event.setCancelled(true); // 橫掃在階段 3 由範圍技能決定
                return;
            }
            if (event.getCause() == EntityDamageEvent.DamageCause.ENTITY_ATTACK && !consumeAttack(player, state, attackerSheet)) {
                event.setCancelled(true); // 攻速限制
                return;
            }
            skill = SkillProfile.defaultAttack(state.weaponTags());
        } else {
            final Optional<MonsterData> data = plugin.monsters().data(attacker);
            if (data.isEmpty()) {
                return; // 沒等級化的生物（例如其他插件的）交給原版
            }
            attackerSheet = plugin.monsters().sheet(attacker, data.get());
            skill = SkillProfile.monsterAttack();
        }

        // 防禦方
        final StatSheet defenderSheet;
        final double defenderEs;
        PlayerState defenderState = null;
        if (defenderIsPlayer) {
            defenderState = plugin.players().get((Player) defender);
            defenderSheet = plugin.players().sheet((Player) defender);
            defenderEs = defenderState.energyShield();
        } else {
            final Optional<MonsterData> data = plugin.monsters().data(defender);
            if (data.isEmpty()) {
                return; // 打到沒等級化的生物：原版處理
            }
            defenderSheet = plugin.monsters().sheet(defender, data.get());
            defenderEs = 0;
        }

        final HitResult result = DamageCalculator.calculate(attackerSheet, skill, defenderSheet, defenderEs, plugin.items().random());
        final HitEvent hitEvent = new HitEvent(attacker, defender, skill, result);
        plugin.getServer().getPluginManager().callEvent(hitEvent);
        if (hitEvent.isCancelled() || !result.hit() || result.blocked()) {
            event.setCancelled(true);
            feedback(attacker, defender, result);
            return;
        }

        if (defenderIsPlayer) {
            applyToPlayer((Player) defender, defenderState, result.esDamage(), result.lifeDamage(), event);
        } else {
            event.setDamage(Math.max(0.01, result.total()));
        }
        feedback(attacker, defender, result);
    }

    /** 環境傷害（掉落、火、岩漿、溺水…）只處理玩家。 */
    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onEnvironmentDamage(final EntityDamageEvent event) {
        if (event instanceof EntityDamageByEntityEvent) {
            return;
        }
        if (!(event.getEntity() instanceof Player player)) {
            return;
        }
        final EnvironmentDamage.Mapping mapping = environment.mapping(event.getCause());
        if (mapping == null) {
            return; // 未設定的原因照原版（例如 /kill）
        }
        final PlayerState state = plugin.players().get(player);
        if (mapping.periodic()) {
            final long now = plugin.getServer().getCurrentTick();
            final Long last = environmentCooldown.get(player.getUniqueId());
            if (last != null && now - last < EnvironmentDamage.PERIODIC_INTERVAL_TICKS) {
                event.setCancelled(true);
                return;
            }
            environmentCooldown.put(player.getUniqueId(), now);
        }
        final double amount = event.getDamage() * mapping.multiplier() * state.maxLife() / 40.0;
        final DamagePacket outgoing = new DamagePacket().set(mapping.type(), amount);
        final DamagePacket dealt = mapping.ignoreMitigation() ? outgoing : DamageCalculator.mitigate(outgoing, plugin.players().sheet(player));
        double es = state.energyShield();
        double esDamage = 0;
        double lifeDamage = dealt.total();
        if (mapping.type() != DamageType.CHAOS && es > 0) {
            esDamage = Math.min(es, lifeDamage);
            lifeDamage -= esDamage;
        }
        applyToPlayer(player, state, esDamage, lifeDamage, event);
    }

    /** 扣玩家的護盾與生命；原版傷害改成 0，致死時改成致死值讓原版處理死亡。 */
    private void applyToPlayer(final Player player, final PlayerState state, final double esDamage,
                               final double lifeDamage, final EntityDamageEvent event) {
        state.setEnergyShield(state.energyShield() - esDamage);
        state.setLife(state.life() - lifeDamage);
        state.setLastDamageTick(plugin.getServer().getCurrentTick());
        if (state.isDead()) {
            event.setDamage(LETHAL);
            return;
        }
        event.setDamage(0);
        plugin.players().display(player, state);
    }

    /** 攻速限制：這次攻擊是否允許，允許就排下一次可攻擊的 tick。 */
    private boolean consumeAttack(final Player player, final PlayerState state, final StatSheet sheet) {
        final long now = plugin.getServer().getCurrentTick();
        if (now < state.nextAttackTick()) {
            return false;
        }
        final double aps = DamageCalculator.attacksPerSecond(sheet, Set.of(Tags.ATTACK, Tags.MELEE));
        state.setNextAttackTick(now + Formulas.attackIntervalTicks(aps));
        return true;
    }

    private static LivingEntity resolveAttacker(final Entity damager) {
        if (damager instanceof LivingEntity living) {
            return living;
        }
        if (damager instanceof Projectile projectile) {
            final ProjectileSource source = projectile.getShooter();
            if (source instanceof LivingEntity living) {
                return living;
            }
        }
        return null;
    }

    /** 除錯訊息（/ec debug 開啟時）。 */
    private void feedback(final LivingEntity attacker, final LivingEntity defender, final HitResult result) {
        if (attacker instanceof Player player) {
            final PlayerState state = plugin.players().get(player);
            if (state.debug()) {
                player.sendMessage(plugin.text().prefixed(String.format(Locale.ROOT, "<gray>你 → %s：%s",
                        name(defender), result.describe())));
            }
        }
        if (defender instanceof Player player) {
            final PlayerState state = plugin.players().get(player);
            if (state.debug()) {
                player.sendMessage(plugin.text().prefixed(String.format(Locale.ROOT, "<gray>%s → 你：%s",
                        name(attacker), result.describe())));
            }
        }
    }

    private String name(final LivingEntity entity) {
        if (entity instanceof Player player) {
            return player.getName();
        }
        return plugin.monsters().data(entity).map(MonsterData::name).orElse(entity.getType().name());
    }
}
