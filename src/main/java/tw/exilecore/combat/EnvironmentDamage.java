package tw.exilecore.combat;

import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.event.entity.EntityDamageEvent;
import tw.exilecore.stats.DamageType;

import java.util.EnumMap;
import java.util.Locale;
import java.util.Map;

/**
 * 原版環境傷害 → 我們的傷害類型。config.yml 的 combat.environment 區塊：
 * <pre>
 * combat:
 *   environment:
 *     FALL:      { type: physical, multiplier: 0.5, ignore_mitigation: true }
 *     FIRE_TICK: { type: fire, multiplier: 0.5, periodic: true }
 * </pre>
 * 原版數字乘上「倍率 × 最大生命 ÷ 40」：原版 20 點（滿血）的傷害在倍率 1 時等於一條命。
 */
public final class EnvironmentDamage {

    /** 週期性傷害（火、岩漿、仙人掌）最多每 10 tick 套用一次。 */
    public static final long PERIODIC_INTERVAL_TICKS = 10;

    public record Mapping(DamageType type, double multiplier, boolean periodic, boolean ignoreMitigation) {
    }

    private final Map<EntityDamageEvent.DamageCause, Mapping> mappings = new EnumMap<>(EntityDamageEvent.DamageCause.class);

    public EnvironmentDamage() {
        // 預設值；config.yml 可覆寫
        put(EntityDamageEvent.DamageCause.FALL, DamageType.PHYSICAL, 0.5, false, true);
        put(EntityDamageEvent.DamageCause.FIRE, DamageType.FIRE, 0.4, true, false);
        put(EntityDamageEvent.DamageCause.FIRE_TICK, DamageType.FIRE, 0.25, true, false);
        put(EntityDamageEvent.DamageCause.LAVA, DamageType.FIRE, 1.0, true, false);
        put(EntityDamageEvent.DamageCause.HOT_FLOOR, DamageType.FIRE, 0.3, true, false);
        put(EntityDamageEvent.DamageCause.CAMPFIRE, DamageType.FIRE, 0.3, true, false);
        put(EntityDamageEvent.DamageCause.LIGHTNING, DamageType.LIGHTNING, 1.0, false, false);
        put(EntityDamageEvent.DamageCause.FREEZE, DamageType.COLD, 0.3, true, false);
        put(EntityDamageEvent.DamageCause.DROWNING, DamageType.PHYSICAL, 0.5, true, true);
        put(EntityDamageEvent.DamageCause.SUFFOCATION, DamageType.PHYSICAL, 0.5, true, true);
        put(EntityDamageEvent.DamageCause.CONTACT, DamageType.PHYSICAL, 0.3, true, false);
        put(EntityDamageEvent.DamageCause.CRAMMING, DamageType.PHYSICAL, 0.3, true, true);
        put(EntityDamageEvent.DamageCause.POISON, DamageType.CHAOS, 0.3, true, true);
        put(EntityDamageEvent.DamageCause.WITHER, DamageType.CHAOS, 0.4, true, true);
        put(EntityDamageEvent.DamageCause.MAGIC, DamageType.CHAOS, 0.5, false, false);
        put(EntityDamageEvent.DamageCause.DRAGON_BREATH, DamageType.CHAOS, 0.4, true, false);
        put(EntityDamageEvent.DamageCause.BLOCK_EXPLOSION, DamageType.FIRE, 1.0, false, false);
        put(EntityDamageEvent.DamageCause.ENTITY_EXPLOSION, DamageType.FIRE, 1.0, false, false);
        put(EntityDamageEvent.DamageCause.FALLING_BLOCK, DamageType.PHYSICAL, 0.5, false, false);
        put(EntityDamageEvent.DamageCause.FLY_INTO_WALL, DamageType.PHYSICAL, 0.5, false, true);
        put(EntityDamageEvent.DamageCause.SONIC_BOOM, DamageType.PHYSICAL, 1.5, false, true);
        put(EntityDamageEvent.DamageCause.THORNS, DamageType.PHYSICAL, 0.3, false, false);
        put(EntityDamageEvent.DamageCause.STARVATION, DamageType.CHAOS, 0.0, true, true);
        put(EntityDamageEvent.DamageCause.VOID, DamageType.CHAOS, 100.0, true, true);
        put(EntityDamageEvent.DamageCause.WORLD_BORDER, DamageType.CHAOS, 1.0, true, true);
    }

    private void put(final EntityDamageEvent.DamageCause cause, final DamageType type, final double multiplier,
                     final boolean periodic, final boolean ignoreMitigation) {
        mappings.put(cause, new Mapping(type, multiplier, periodic, ignoreMitigation));
    }

    /** 從 config.yml 的 combat.environment 覆寫。 */
    public void load(final ConfigurationSection section) {
        if (section == null) {
            return;
        }
        for (final String key : section.getKeys(false)) {
            final EntityDamageEvent.DamageCause cause;
            try {
                cause = EntityDamageEvent.DamageCause.valueOf(key.trim().toUpperCase(Locale.ROOT));
            } catch (final IllegalArgumentException e) {
                continue;
            }
            final ConfigurationSection entry = section.getConfigurationSection(key);
            if (entry == null) {
                continue;
            }
            final DamageType type = DamageType.fromTag(entry.getString("type", "physical"));
            final Mapping old = mappings.get(cause);
            mappings.put(cause, new Mapping(type == null ? DamageType.PHYSICAL : type,
                    entry.getDouble("multiplier", old == null ? 1.0 : old.multiplier()),
                    entry.getBoolean("periodic", old != null && old.periodic()),
                    entry.getBoolean("ignore_mitigation", old != null && old.ignoreMitigation())));
        }
    }

    public Mapping mapping(final EntityDamageEvent.DamageCause cause) {
        return mappings.get(cause);
    }
}
