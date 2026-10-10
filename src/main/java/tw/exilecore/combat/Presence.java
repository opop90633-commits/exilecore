package tw.exilecore.combat;

import org.bukkit.Particle;
import org.bukkit.World;
import org.bukkit.entity.LivingEntity;
import org.bukkit.entity.Player;
import tw.exilecore.ExileCore;
import tw.exilecore.character.PlayerState;
import tw.exilecore.stats.DamagePacket;
import tw.exilecore.stats.DamageType;
import tw.exilecore.stats.Modifier;
import tw.exilecore.stats.SkillProfile;
import tw.exilecore.stats.StatKeys;
import tw.exilecore.stats.StatSheet;
import tw.exilecore.stats.Tags;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;

/**
 * 臨在（2026-10，照 PoE2 的 Presence）：你身邊 {@value #BASE_RADIUS} 格內（吃 presence.area）的敵人受到你身上「範圍內的敵人…」的效果：
 * <ul>
 *   <li>presence.resist.火焰／冰冷／閃電／混沌（FLAT）：抗性降低 x%</li>
 *   <li>presence.taken（FLAT）：受到的傷害增加 x%</li>
 *   <li>presence.weaken（FLAT）：造成的傷害更少 x%</li>
 *   <li>presence.slow（FLAT）：移動速度變慢 x%</li>
 *   <li>presence.burn（FLAT）：每秒受到你最大生命 x% 的火焰傷害</li>
 * </ul>
 * 每 {@value #PERIOD} tick 檢查一次，效果是一條很短的減益（離開範圍一下子就沒了）。
 */
public final class Presence {

    public static final long PERIOD = 10;
    /** 臨在的基礎半徑（格）。 */
    public static final double BASE_RADIUS = 6;
    /** 減益留多久（比檢查間隔長一點，走出範圍很快就消失）。 */
    private static final long LINGER = PERIOD + 6;
    public static final String BURN_ID = "presence.burn";

    private final ExileCore plugin;
    private final Debuffs debuffs;
    private final SkillDamage damage;
    private long ticks;

    public Presence(final ExileCore plugin, final Debuffs debuffs, final SkillDamage damage) {
        this.plugin = plugin;
        this.debuffs = debuffs;
        this.damage = damage;
    }

    public void tick() {
        ticks += PERIOD;
        final boolean burnTick = ticks % 20 == 0;
        final long now = plugin.getServer().getCurrentTick();
        for (final Player player : plugin.getServer().getOnlinePlayers()) {
            final PlayerState state = plugin.players().find(player.getUniqueId()).orElse(null);
            if (state == null || !state.hasCharacter() || state.isDead() || player.isDead()) {
                continue;
            }
            final StatSheet sheet = plugin.players().sheet(player);
            final List<Modifier> mods = new ArrayList<>();
            for (final DamageType type : new DamageType[] {DamageType.FIRE, DamageType.COLD, DamageType.LIGHTNING, DamageType.CHAOS}) {
                final double resist = sheet.flat(StatKeys.PRESENCE_RESIST + type.tag(), Set.of());
                if (resist != 0) {
                    mods.add(Modifier.flat(type.mitigationStat(), -resist, "臨在"));
                }
            }
            final double weaken = sheet.flat(StatKeys.PRESENCE_WEAKEN, Set.of());
            if (weaken > 0) {
                mods.add(Modifier.more(StatKeys.DAMAGE, -Math.min(90, weaken) / 100.0, "臨在"));
            }
            final double taken = Math.max(0, sheet.flat(StatKeys.PRESENCE_TAKEN, Set.of())) / 100.0;
            final double slow = Math.max(0, Math.min(90, sheet.flat(StatKeys.PRESENCE_SLOW, Set.of()))) / 100.0;
            final double burn = sheet.flat(StatKeys.PRESENCE_BURN, Set.of());
            if (mods.isEmpty() && taken <= 0 && slow <= 0 && burn <= 0) {
                continue;
            }
            final double radius = BASE_RADIUS * Math.sqrt(Math.max(0.1, 1 + sheet.increased(StatKeys.PRESENCE_AREA, Set.of())));
            final double radiusSquared = radius * radius;
            final World world = player.getWorld();
            final String id = "presence." + player.getUniqueId();
            final List<LivingEntity> burning = new ArrayList<>();
            for (final LivingEntity target : world.getNearbyLivingEntities(player.getLocation(), radius)) {
                if (target instanceof Player || target.isDead() || !target.isValid() || plugin.monsters().data(target).isEmpty()
                        || target.getLocation().distanceSquared(player.getLocation()) > radiusSquared) {
                    continue;
                }
                if (!mods.isEmpty() || taken > 0 || slow > 0) {
                    debuffs.apply(target.getUniqueId(), new Debuffs.Debuff(id, player.getUniqueId(), now + LINGER, taken, false, null, mods, slow));
                }
                if (burn > 0) {
                    burning.add(target);
                }
            }
            if (burnTick && !burning.isEmpty()) {
                final double amount = state.maxLife() * Math.min(100, burn) / 100.0;
                final SkillProfile profile = new SkillProfile(BURN_ID, Set.of(Tags.AREA, Tags.FIRE), 0, new DamagePacket().set(DamageType.FIRE, amount));
                for (final LivingEntity target : burning) {
                    if (target.isValid() && !target.isDead()) {
                        damage.hit(player, target, profile, null);
                        tw.exilecore.skill.Fx.spawn(world, Particle.FLAME, target.getLocation().add(0, 1, 0), 4, 0.3, 0.4, 0.3, 0.01);
                    }
                }
            }
        }
    }
}
