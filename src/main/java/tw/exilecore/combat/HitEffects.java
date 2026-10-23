package tw.exilecore.combat;

import org.bukkit.NamespacedKey;
import org.bukkit.Particle;
import org.bukkit.attribute.Attribute;
import org.bukkit.attribute.AttributeInstance;
import org.bukkit.attribute.AttributeModifier;
import org.bukkit.entity.Entity;
import org.bukkit.entity.LivingEntity;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import tw.exilecore.ExileCore;
import tw.exilecore.monster.MonsterData;
import tw.exilecore.monster.MonsterRarity;
import tw.exilecore.stats.DamageType;
import tw.exilecore.stats.HitEvent;
import tw.exilecore.stats.HitResult;
import tw.exilecore.stats.Modifier;
import tw.exilecore.stats.SkillProfile;
import tw.exilecore.stats.StatKeys;
import tw.exilecore.stats.StatSheet;
import tw.exilecore.stats.Tags;

import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/**
 * 2026-10 PoE2 系統補齊：擊中怪物時擲機率套上的減益，以及減益讓怪物變慢。
 * <ul>
 *   <li>曝露（exposure.chance）：這一下打出的火焰／冰冷／閃電，那幾種抗性 −20%（曝露效果可以加），4 秒。</li>
 *   <li>定身（pin.chance，只有投射物攻擊）：不能移動、照樣能攻擊；普通與魔法 2 秒、稀有 1.5 秒、傳奇與 Boss 1 秒，之後 4 秒不會再被定身。</li>
 *   <li>眩暈（daze.chance）：4 秒內造成的傷害更少 15%、暈眩門檻減少 30%。</li>
 *   <li>致盲（blind.chance，2026-10-21 PoE2 腐化）：4 秒內命中與閃避更少 20%。</li>
 *   <li>癱瘓（maim.chance，2026-10-21 PoE2 腐化）：4 秒內移動速度變慢 30%（吃減速幅度 slow.magnitude）。</li>
 * </ul>
 * 減益的修飾器由 {@link CombatListener} 疊進屬性表；移動變慢（詛咒、定身）每 2 tick 在這裡套到原版的移動速度。
 */
public final class HitEffects implements Listener {

    public static final double EXPOSURE = 20;
    static final long EXPOSURE_TICKS = 80;
    static final long DAZE_TICKS = 80;
    static final long BLIND_TICKS = 80;
    static final long MAIM_TICKS = 80;
    /** 癱瘓的減速（PoE2：30%）；致盲的命中、閃避更少（PoE2：20%）。 */
    public static final double MAIM_SLOW = 0.3;
    public static final double BLIND_LESS = 0.2;
    static final long PIN_IMMUNE_TICKS = 80;

    private final ExileCore plugin;
    private final Debuffs debuffs;
    private final NamespacedKey slowKey;
    private final Map<UUID, Double> applied = new HashMap<>();
    private final Map<UUID, Long> pinImmune = new HashMap<>();
    /** 玩家最後打中的技能（擊殺後的增益看打死敵人的那一下的輔助寶石）。 */
    private final Map<UUID, SkillProfile> lastSkill = new HashMap<>();

    HitEffects(final ExileCore plugin, final Debuffs debuffs) {
        this.plugin = plugin;
        this.debuffs = debuffs;
        this.slowKey = new NamespacedKey(plugin, "debuff_slow");
    }

    private long now() {
        return plugin.getServer().getCurrentTick();
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onHit(final HitEvent event) {
        final HitResult result = event.result();
        final LivingEntity defender = event.defender();
        if (!result.hit() || result.blocked() || defender instanceof Player || plugin.monsters() == null || event.skill() == null) {
            return;
        }
        final MonsterData monster = plugin.monsters().data(defender).orElse(null);
        if (monster == null) {
            return;
        }
        final StatSheet sheet = attackerSheet(event.attacker());
        if (sheet == null) {
            return;
        }
        final SkillProfile skill = event.skill();
        if (event.attacker() instanceof Player) {
            lastSkill.put(event.attacker().getUniqueId(), skill);
        }
        final StatSheet effective = skill.effective(sheet);
        final Set<String> tags = skill.tags();
        final long now = now();
        final Player viewer = event.credit();
        final UUID source = viewer != null ? viewer.getUniqueId() : event.attacker().getUniqueId();
        final UUID target = defender.getUniqueId();

        final double exposure = effective.flat(StatKeys.EXPOSURE_CHANCE, tags);
        if (exposure > 0 && roll(exposure)) {
            final double amount = EXPOSURE * Math.max(0, 1 + effective.increased(StatKeys.EXPOSURE_EFFECT, tags));
            boolean any = false;
            for (final DamageType type : new DamageType[] {DamageType.FIRE, DamageType.COLD, DamageType.LIGHTNING}) {
                if (result.dealt().get(type) > 0) {
                    debuffs.apply(target, new Debuffs.Debuff("exposure." + type.tag(), source, now + EXPOSURE_TICKS, 0, false, null,
                            List.of(Modifier.flat(type.mitigationStat(), -amount, "曝露")), 0));
                    any = true;
                }
            }
            if (any) {
                feedback(viewer, defender, "曝露", Particle.ENCHANTED_HIT);
            }
        }
        final double pin = effective.flat(StatKeys.PIN_CHANCE, tags);
        if (pin > 0 && skill.isAttack() && tags.contains(Tags.PROJECTILE) && pinImmune.getOrDefault(target, 0L) <= now && roll(pin)) {
            final long ticks = monster.boss() || monster.rarity() == MonsterRarity.UNIQUE ? 20 : monster.rarity() == MonsterRarity.RARE ? 30 : 40;
            debuffs.apply(target, new Debuffs.Debuff("pin", source, now + ticks, 0, false, null, List.of(), 1.0));
            pinImmune.put(target, now + ticks + PIN_IMMUNE_TICKS);
            feedback(viewer, defender, "定身", Particle.CRIT);
        }
        final double daze = effective.flat(StatKeys.DAZE_CHANCE, tags);
        if (daze > 0 && roll(daze)) {
            debuffs.apply(target, new Debuffs.Debuff("daze", source, now + DAZE_TICKS, 0, false, null,
                    List.of(Modifier.more(StatKeys.DAMAGE, -0.15, "眩暈"), Modifier.increased(StatKeys.STUN_THRESHOLD, -0.3, "眩暈")), 0));
            feedback(viewer, defender, "眩暈", Particle.WAX_OFF);
        }
        final double blind = effective.flat(StatKeys.BLIND_CHANCE, tags);
        if (blind > 0 && roll(blind)) {
            debuffs.apply(target, new Debuffs.Debuff("blind", source, now + BLIND_TICKS, 0, false, null,
                    List.of(Modifier.more(StatKeys.ACCURACY, -BLIND_LESS, "致盲"), Modifier.more(StatKeys.EVASION, -BLIND_LESS, "致盲")), 0));
            feedback(viewer, defender, "致盲", Particle.SMOKE);
        }
        final double maim = effective.flat(StatKeys.MAIM_CHANCE, tags);
        if (maim > 0 && roll(maim)) {
            debuffs.apply(target, new Debuffs.Debuff("maim", source, now + MAIM_TICKS, 0, false, null, List.of(),
                    maimSlow(effective.increased(StatKeys.SLOW_MAGNITUDE, tags))));
            feedback(viewer, defender, "癱瘓", Particle.DAMAGE_INDICATOR);
        }
    }

    /** 癱瘓慢多少：30% 乘上減速幅度（最多 90%）。 */
    static double maimSlow(final double slowMagnitudeIncreased) {
        return Math.min(0.9, MAIM_SLOW * Math.max(0, 1 + slowMagnitudeIncreased));
    }

    /** 擊殺後的增益（2026-10）：onkill.speed、onkill.damage（打死敵人的那個技能的輔助寶石，4 秒）。 */
    @EventHandler(priority = EventPriority.MONITOR)
    public void onDeath(final org.bukkit.event.entity.EntityDeathEvent event) {
        final Player killer = event.getEntity().getKiller();
        if (killer == null || event.getEntity() instanceof Player || plugin.skills() == null) {
            return;
        }
        final SkillProfile skill = lastSkill.get(killer.getUniqueId());
        if (skill == null) {
            return;
        }
        final StatSheet effective = skill.effective(plugin.players().sheet(killer));
        final double speed = effective.flat(StatKeys.ONKILL_SPEED, skill.tags());
        final double damage = effective.flat(StatKeys.ONKILL_DAMAGE, skill.tags());
        final List<Modifier> mods = new java.util.ArrayList<>();
        if (speed > 0) {
            mods.add(Modifier.increased(StatKeys.ATTACK_SPEED, speed / 100.0, "擊殺"));
            mods.add(Modifier.increased(StatKeys.CAST_SPEED, speed / 100.0, "擊殺"));
        }
        if (damage > 0) {
            mods.add(Modifier.increased(StatKeys.DAMAGE, damage / 100.0, "擊殺"));
        }
        if (!mods.isEmpty()) {
            plugin.skills().buff(killer, "onkill", "擊殺後", 80, mods);
        }
    }

    @EventHandler
    public void onQuit(final org.bukkit.event.player.PlayerQuitEvent event) {
        lastSkill.remove(event.getPlayer().getUniqueId());
    }

    private boolean roll(final double percent) {
        return percent >= 100 || plugin.items().random().nextDouble() * 100 < percent;
    }

    private StatSheet attackerSheet(final LivingEntity attacker) {
        if (attacker instanceof Player player) {
            return plugin.players().sheet(player);
        }
        final MinionHooks minions = plugin.combat() == null ? null : plugin.combat().minions();
        final MinionHooks.Attack attack = minions == null ? null : minions.attack(attacker);
        return attack == null ? null : attack.sheet();
    }

    private void feedback(final Player viewer, final LivingEntity target, final String word, final Particle particle) {
        if (viewer != null && plugin.combat() != null && plugin.combat().numbers() != null) {
            plugin.combat().numbers().word(viewer, target, word);
        }
        target.getWorld().spawnParticle(particle, target.getLocation().add(0, target.getHeight() * 0.7, 0), 8, 0.3, 0.3, 0.3, 0.05);
    }

    /** 每 2 tick：減益的移動變慢（詛咒、定身）套到原版移動速度；沒了就拿掉。 */
    void tick() {
        final long now = now();
        final Set<UUID> ids = new HashSet<>(debuffs.targets());
        ids.addAll(applied.keySet());
        for (final UUID id : ids) {
            final Entity entity = plugin.getServer().getEntity(id);
            if (!(entity instanceof LivingEntity living) || entity instanceof Player || !living.isValid()) {
                applied.remove(id);
                continue;
            }
            double slow = debuffs.slow(id, now);
            if (slow > 0 && slow < 1 && plugin.monsters() != null) {
                // 2026-10-23：怪物詞綴「不羈的」（身上的減速效果減半）；定身（1）不受影響
                final MonsterData data = plugin.monsters().data(living).orElse(null);
                if (data != null) {
                    slow *= Math.max(0, 1 + plugin.monsters().sheet(living, data).increased(StatKeys.SLOW_TAKEN, Set.of()));
                }
            }
            apply(living, slow);
        }
        if (now % 200 == 0) {
            pinImmune.values().removeIf(until -> until <= now);
        }
    }

    private void apply(final LivingEntity living, final double slow) {
        final Double previous = applied.get(living.getUniqueId());
        if (previous == null ? slow <= 0 : Math.abs(previous - slow) < 1e-3) {
            return;
        }
        for (final Attribute attribute : new Attribute[] {Attribute.MOVEMENT_SPEED, Attribute.FLYING_SPEED}) {
            final AttributeInstance instance = living.getAttribute(attribute);
            if (instance == null) {
                continue;
            }
            instance.removeModifier(slowKey);
            if (slow > 0) {
                instance.addTransientModifier(new AttributeModifier(slowKey, -Math.min(1.0, slow), AttributeModifier.Operation.MULTIPLY_SCALAR_1));
            }
        }
        if (slow > 0) {
            applied.put(living.getUniqueId(), slow);
        } else {
            applied.remove(living.getUniqueId());
        }
    }

    void clear() {
        for (final UUID id : applied.keySet()) {
            if (plugin.getServer().getEntity(id) instanceof LivingEntity living) {
                for (final Attribute attribute : new Attribute[] {Attribute.MOVEMENT_SPEED, Attribute.FLYING_SPEED}) {
                    final AttributeInstance instance = living.getAttribute(attribute);
                    if (instance != null) {
                        instance.removeModifier(slowKey);
                    }
                }
            }
        }
        applied.clear();
    }
}
