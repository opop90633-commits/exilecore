package tw.exilecore.sponsor;

import org.bukkit.Color;
import org.bukkit.GameMode;
import org.bukkit.Location;
import org.bukkit.Particle;
import org.bukkit.World;
import org.bukkit.entity.Player;
import org.bukkit.potion.PotionEffectType;
import tw.exilecore.ExileCore;

import java.util.HashMap;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;

/**
 * 外觀特效的粒子（每 2 tick 畫一次；戴著特效的人才畫）。旁觀、隱形、死掉的時候不畫。
 * 粒子只有好看：不影響戰鬥、也不擋視線（都在腳邊、頭上或背後）。
 */
final class CosmeticRenderer {

    /** 翅膀的點：離中線多遠、多高（每邊一樣）。 */
    private static final double[][] WING = {
            {0.30, 1.50}, {0.50, 1.66}, {0.70, 1.80}, {0.90, 1.92}, {1.05, 1.98},
            {0.45, 1.36}, {0.68, 1.46}, {0.90, 1.56},
            {0.55, 1.18}, {0.78, 1.24},
    };

    private final ExileCore plugin;
    private final SponsorService service;
    private final Map<UUID, Location> last = new HashMap<>();
    private final Map<String, Particle> particles = new HashMap<>();
    private long tick;

    CosmeticRenderer(final ExileCore plugin, final SponsorService service) {
        this.plugin = plugin;
        this.service = service;
    }

    /** 粒子名稱 → 粒子（不認得、要特別資料的換成 END_ROD）。 */
    Particle particle(final String name) {
        return particles.computeIfAbsent(name, key -> {
            try {
                final Particle particle = Particle.valueOf(key.toUpperCase(Locale.ROOT));
                final Class<?> data = particle.getDataType();
                if (data == Void.class || data == Particle.DustOptions.class) {
                    return particle;
                }
            } catch (final IllegalArgumentException ignored) {
                // 下面換成 END_ROD
            }
            plugin.getSLF4JLogger().warn("sponsor.yml：粒子「{}」不能用（不認得或要特別的資料），換成 END_ROD", key);
            return Particle.END_ROD;
        });
    }

    void tick() {
        tick++;
        for (final Player player : plugin.getServer().getOnlinePlayers()) {
            if (player.isDead() || player.getGameMode() == GameMode.SPECTATOR || player.isInvisible()
                    || player.hasPotionEffect(PotionEffectType.INVISIBILITY) || !service.loaded(player)) {
                continue;
            }
            final SponsorConfig.Cosmetic cosmetic = service.worn(player);
            if (cosmetic == null) {
                last.remove(player.getUniqueId());
                continue;
            }
            draw(player, cosmetic);
        }
        if (tick % 200 == 0) {
            last.keySet().removeIf(uuid -> plugin.getServer().getPlayer(uuid) == null);
        }
    }

    private void draw(final Player player, final SponsorConfig.Cosmetic cosmetic) {
        final Location at = player.getLocation();
        final World world = at.getWorld();
        final Particle particle = particle(cosmetic.particle());
        final Object data = particle.getDataType() == Particle.DustOptions.class ? dust(cosmetic) : null;
        final double t = tick;
        switch (cosmetic.style()) {
            case RING -> {
                for (int i = 0; i < 3; i++) {
                    final double angle = t * 0.18 + i * Math.PI * 2 / 3;
                    spawn(world, particle, data, at.getX() + Math.cos(angle) * 0.75, at.getY() + 0.1, at.getZ() + Math.sin(angle) * 0.75);
                }
            }
            case HALO -> {
                if (tick % 2 == 0) {
                    for (int i = 0; i < 6; i++) {
                        final double angle = t * 0.05 + i * Math.PI / 3;
                        spawn(world, particle, data, at.getX() + Math.cos(angle) * 0.35, at.getY() + 2.25, at.getZ() + Math.sin(angle) * 0.35);
                    }
                }
            }
            case SPIRAL -> {
                for (int strand = 0; strand < 2; strand++) {
                    final double angle = t * 0.3 + strand * Math.PI;
                    final double height = (t % 40) / 40.0 * 2.1;
                    spawn(world, particle, data, at.getX() + Math.cos(angle) * 0.6, at.getY() + height, at.getZ() + Math.sin(angle) * 0.6);
                }
            }
            case WINGS -> {
                if (tick % 3 != 0) {
                    return;
                }
                final double yaw = Math.toRadians(at.getYaw());
                final double forwardX = -Math.sin(yaw);
                final double forwardZ = Math.cos(yaw);
                final double rightX = -forwardZ;
                final double rightZ = forwardX;
                final double flap = 0.85 + 0.15 * Math.sin(t * 0.15);
                for (final double[] point : WING) {
                    final double out = point[0] * flap;
                    final double back = 0.28 + point[0] * 0.18;
                    for (final int side : new int[] {-1, 1}) {
                        spawn(world, particle, data, at.getX() - forwardX * back + rightX * out * side, at.getY() + point[1],
                                at.getZ() - forwardZ * back + rightZ * out * side);
                    }
                }
            }
            case TRAIL -> {
                final Location previous = last.put(player.getUniqueId(), at.clone());
                if (previous != null && previous.getWorld() == world && previous.distanceSquared(at) > 0.004 && player.isOnGround()) {
                    world.spawnParticle(particle, at.getX(), at.getY() + 0.05, at.getZ(), 2, 0.15, 0.02, 0.15, 0, data);
                }
            }
            case ORBIT -> {
                if (tick % 2 == 0) {
                    for (int i = 0; i < 2; i++) {
                        final double angle = t * 0.12 + i * Math.PI;
                        spawn(world, particle, data, at.getX() + Math.cos(angle) * 0.85, at.getY() + 1.1 + Math.sin(t * 0.1 + i) * 0.2,
                                at.getZ() + Math.sin(angle) * 0.85);
                    }
                }
            }
            case CROWN -> {
                if (tick % 3 == 0) {
                    for (int i = 0; i < 5; i++) {
                        final double angle = i * Math.PI * 2 / 5 + t * 0.02;
                        spawn(world, particle, data, at.getX() + Math.cos(angle) * 0.28, at.getY() + 2.15 + (i % 2) * 0.12,
                                at.getZ() + Math.sin(angle) * 0.28);
                    }
                }
            }
            case AURA -> {
                if (tick % 2 == 0) {
                    world.spawnParticle(particle, at.getX(), at.getY() + 1.0, at.getZ(), 1, 0.45, 0.6, 0.45, 0, data);
                }
            }
            default -> {
            }
        }
    }

    private static void spawn(final World world, final Particle particle, final Object data, final double x, final double y, final double z) {
        world.spawnParticle(particle, x, y, z, 1, 0, 0, 0, 0, data);
    }

    private Particle.DustOptions dust(final SponsorConfig.Cosmetic cosmetic) {
        if (cosmetic.rainbow()) {
            final java.awt.Color hsb = java.awt.Color.getHSBColor((tick % 120) / 120f, 0.6f, 1f);
            return new Particle.DustOptions(Color.fromRGB(hsb.getRed(), hsb.getGreen(), hsb.getBlue()), 0.9f);
        }
        final String hex = cosmetic.color() == null ? "#ffffff" : cosmetic.color();
        return new Particle.DustOptions(Color.fromRGB(Integer.parseInt(hex.substring(1), 16)), 0.9f);
    }
}
