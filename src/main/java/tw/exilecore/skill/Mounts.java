package tw.exilecore.skill;

import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.NamespacedKey;
import org.bukkit.Particle;
import org.bukkit.Sound;
import org.bukkit.attribute.Attribute;
import org.bukkit.attribute.AttributeInstance;
import org.bukkit.entity.AbstractHorse;
import org.bukkit.entity.Entity;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.entity.EntityDamageEvent;
import org.bukkit.event.entity.EntityDismountEvent;
import org.bukkit.event.entity.PlayerDeathEvent;
import org.bukkit.event.inventory.InventoryOpenEvent;
import org.bukkit.event.player.PlayerChangedWorldEvent;
import org.bukkit.event.player.PlayerInteractEntityEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.inventory.ItemStack;
import org.bukkit.persistence.PersistentDataType;
import tw.exilecore.ExileCore;
import tw.exilecore.stats.Modifier;
import tw.exilecore.stats.StatKeys;
import tw.exilecore.stats.Tags;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/**
 * 坐騎（2026-10）：skills.yml 裡寫了 {@code mount:} 的技能叫出一匹真的坐騎讓你騎上去（再放一次＝下馬）。
 * <ul>
 *   <li>騎著的時候移動照坐騎的速度（吃你的移動速度加成），攻擊更多 {@value #MOUNTED_MORE}% 傷害。</li>
 *   <li>放法術（非攻擊、非坐騎技能）、按潛行鍵、死掉、下線、換世界、被傳送都會下馬；下馬坐騎就消失。</li>
 *   <li>坐騎打不死、不會被打、不能開背包、別人不能騎；不存檔（當機也不會留在世界裡）。</li>
 *   <li>{@code requires_mount: true} 的技能（鐵蹄踐踏這類）要騎著才能放，放的時候坐騎往前衝一下。</li>
 * </ul>
 */
final class Mounts implements Listener {

    /** 騎著的時候攻擊更多幾 % 傷害。 */
    static final double MOUNTED_MORE = 15;

    /** 一種坐騎：原版生物、速度倍率。 */
    record Spec(String kind, double speed) {
        static final Set<String> KINDS = Set.of("HORSE", "SKELETON_HORSE", "ZOMBIE_HORSE", "CAMEL");
    }

    private final ExileCore plugin;
    private final SkillService service;
    private final NamespacedKey key;
    /** 玩家 → 坐騎。 */
    private final Map<UUID, AbstractHorse> mounts = new HashMap<>();

    Mounts(final ExileCore plugin, final SkillService service) {
        this.plugin = plugin;
        this.service = service;
        this.key = new NamespacedKey(plugin, "mount");
    }

    boolean isMount(final Entity entity) {
        return entity != null && entity.getPersistentDataContainer().has(key, PersistentDataType.STRING);
    }

    /** 正騎在自己的坐騎上。 */
    boolean mounted(final Player player) {
        final AbstractHorse horse = mounts.get(player.getUniqueId());
        return horse != null && horse.isValid() && horse.getPassengers().contains(player);
    }

    /** 放坐騎技能：沒騎就叫出來騎上去，騎著就下馬。回傳要顯示的提示。 */
    String toggle(final Player player, final SkillDefinition definition, final Spec spec) {
        if (mounted(player)) {
            dismount(player);
            return "<gray>下馬了";
        }
        dismount(player);
        final Location at = player.getLocation();
        final Class<? extends AbstractHorse> type = switch (spec.kind()) {
            case "SKELETON_HORSE" -> org.bukkit.entity.SkeletonHorse.class;
            case "ZOMBIE_HORSE" -> org.bukkit.entity.ZombieHorse.class;
            case "CAMEL" -> org.bukkit.entity.Camel.class;
            default -> org.bukkit.entity.Horse.class;
        };
        final AbstractHorse horse;
        try {
            horse = at.getWorld().spawn(at, type, false, created -> {
                created.setPersistent(false);
                created.setRemoveWhenFarAway(false);
                created.setInvulnerable(true);
                created.setAdult();
                created.setTamed(true);
                created.setOwner(player);
                created.setAgeLock(true);
                created.setBreed(false);
                created.setCollidable(false);
                created.getPersistentDataContainer().set(key, PersistentDataType.STRING, player.getUniqueId().toString());
                created.getInventory().setSaddle(new ItemStack(Material.SADDLE));
                if (created instanceof org.bukkit.entity.Horse h) {
                    h.setColor(org.bukkit.entity.Horse.Color.BLACK);
                    h.setStyle(org.bukkit.entity.Horse.Style.WHITE);
                    h.getInventory().setArmor(new ItemStack(Material.IRON_HORSE_ARMOR));
                }
                final AttributeInstance jump = created.getAttribute(Attribute.JUMP_STRENGTH);
                if (jump != null) {
                    jump.setBaseValue(0.75);
                }
                final AttributeInstance step = created.getAttribute(Attribute.STEP_HEIGHT);
                if (step != null) {
                    step.setBaseValue(1.5);
                }
            });
        } catch (final RuntimeException e) {
            plugin.getSLF4JLogger().warn("叫不出坐騎（{}）", e.toString());
            return "<red>這裡叫不出坐騎";
        }
        if (!horse.isValid()) {
            return "<red>這裡叫不出坐騎";
        }
        syncSpeed(player, horse, spec);
        mounts.put(player.getUniqueId(), horse);
        horse.addPassenger(player);
        at.getWorld().playSound(at, Sound.ENTITY_HORSE_ARMOR, 0.8f, 1f);
        at.getWorld().playSound(at, Sound.ENTITY_HORSE_AMBIENT, 0.6f, 1.1f);
        at.getWorld().spawnParticle(Particle.CLOUD, at.clone().add(0, 0.6, 0), 16, 0.6, 0.3, 0.6, 0.02);
        horse.getPersistentDataContainer().set(new NamespacedKey(plugin, "mount_speed"), PersistentDataType.DOUBLE, spec.speed());
        return "<#e0b070>" + tw.exilecore.item.ItemRenderer.escape(definition.name()) + "<gray>：騎上去了（再放一次或按潛行鍵下馬）";
    }

    /** 坐騎的速度：原版馬的基本速度 × 倍率 × 你的移動速度。 */
    private void syncSpeed(final Player player, final AbstractHorse horse, final Spec spec) {
        final AttributeInstance speed = horse.getAttribute(Attribute.MOVEMENT_SPEED);
        if (speed != null) {
            final double mine = 1 + plugin.players().sheet(player).increased(StatKeys.MOVEMENT_SPEED, Set.of());
            speed.setBaseValue(0.25 * spec.speed() * Math.max(0.5, Math.min(2.5, mine)));
        }
    }

    /** 下馬（坐騎收掉）。 */
    void dismount(final Player player) {
        final AbstractHorse horse = mounts.remove(player.getUniqueId());
        if (horse != null && horse.isValid()) {
            horse.eject();
            horse.getWorld().spawnParticle(Particle.CLOUD, horse.getLocation().add(0, 0.8, 0), 12, 0.5, 0.4, 0.5, 0.02);
            horse.remove();
        }
    }

    /** 坐騎技能（requires_mount）放的時候：坐騎往面向的方向衝一下。 */
    void charge(final Player player) {
        final AbstractHorse horse = mounts.get(player.getUniqueId());
        if (horse != null && horse.isValid()) {
            final org.bukkit.util.Vector facing = SkillTargets.facing(player).setY(0);
            if (facing.lengthSquared() > 1.0e-4) {
                horse.setVelocity(facing.normalize().multiply(1.1).setY(0.15));
            }
            horse.getWorld().playSound(horse.getLocation(), Sound.ENTITY_HORSE_GALLOP, 1f, 0.9f);
        }
    }

    /** 每秒：騎著的人拿到騎乘的增益、坐騎速度跟上；沒騎了的收掉。 */
    void tick() {
        if (mounts.isEmpty()) {
            return;
        }
        for (final Map.Entry<UUID, AbstractHorse> entry : List.copyOf(mounts.entrySet())) {
            final Player player = plugin.getServer().getPlayer(entry.getKey());
            final AbstractHorse horse = entry.getValue();
            if (player == null || !player.isOnline() || !horse.isValid() || !horse.getPassengers().contains(player)) {
                if (player != null) {
                    dismount(player);
                } else {
                    mounts.remove(entry.getKey());
                    if (horse.isValid()) {
                        horse.remove();
                    }
                }
                continue;
            }
            final Double speed = horse.getPersistentDataContainer().get(new NamespacedKey(plugin, "mount_speed"), PersistentDataType.DOUBLE);
            syncSpeed(player, horse, new Spec("HORSE", speed == null ? 1.0 : speed));
            service.buffs().apply(player, "mounted", "騎乘", 30,
                    List.of(Modifier.more(StatKeys.DAMAGE, MOUNTED_MORE / 100.0, "騎乘").withTags(Set.of(Tags.ATTACK))));
        }
    }

    void clearAll() {
        for (final AbstractHorse horse : mounts.values()) {
            if (horse.isValid()) {
                horse.eject();
                horse.remove();
            }
        }
        mounts.clear();
    }

    // ---- 事件 ----

    @EventHandler(priority = EventPriority.MONITOR)
    public void onDismount(final EntityDismountEvent event) {
        if (event.getEntity() instanceof Player player && isMount(event.getDismounted())) {
            plugin.getServer().getScheduler().runTask(plugin, () -> {
                if (!mounted(player)) {
                    dismount(player);
                }
            });
        }
    }

    @EventHandler(priority = EventPriority.LOWEST, ignoreCancelled = true)
    public void onDamage(final EntityDamageEvent event) {
        if (isMount(event.getEntity())) {
            event.setCancelled(true);
        }
    }

    @EventHandler(priority = EventPriority.LOWEST, ignoreCancelled = true)
    public void onOpen(final InventoryOpenEvent event) {
        if (event.getInventory().getHolder() instanceof AbstractHorse horse && isMount(horse)) {
            event.setCancelled(true);
        }
    }

    @EventHandler(priority = EventPriority.LOWEST, ignoreCancelled = true)
    public void onInteract(final PlayerInteractEntityEvent event) {
        if (isMount(event.getRightClicked())) {
            event.setCancelled(true); // 別人不能騎、自己也不會打開馬鞍欄
        }
    }

    @EventHandler
    public void onQuit(final PlayerQuitEvent event) {
        dismount(event.getPlayer());
    }

    @EventHandler
    public void onDeath(final PlayerDeathEvent event) {
        dismount(event.getEntity());
    }

    @EventHandler
    public void onWorld(final PlayerChangedWorldEvent event) {
        dismount(event.getPlayer());
    }
}
