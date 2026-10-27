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
import tw.exilecore.stats.StatKeys;
import tw.exilecore.stats.Tags;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/**
 * 坐騎（2026-10）：skills.yml 裡寫了 {@code mount:} 的技能叫出一匹真的坐騎讓你騎上去（再放一次＝下馬）。
 * 2026-10-26 照 PoE2 Rhoa Mount（0.5 的一般技能寶石，敏捷、弓與長矛用；不是亞馬遜專屬）：
 * <ul>
 *   <li>拿著弓或長矛才能騎；騎著換成別的武器就下馬。</li>
 *   <li>騎著只能放弓、投擲長矛（非近戰的長矛投射物攻擊）與印記技能，放別的技能會下馬（這次之前只有法術會下馬）。</li>
 *   <li>騎著移動照你的移動速度（PoE2 0.4 拿掉了坐騎的移動速度加成）；放技能不會被減速（坐騎的速度只看你的移動速度加成）。</li>
 *   <li>騎著被打會累積重暈（AilmentService），被重暈就摔下坐騎。</li>
 *   <li>拿掉了原作沒有的「騎著攻擊更多 15% 傷害」（這次之前有）。</li>
 *   <li>按潛行鍵、死掉、下線、換世界、被傳送都會下馬；下馬坐騎就消失（原作下馬後陸行鳥會陪你打、會被打、死了 12 秒後再叫出來：沒做，
 *       所以也不收原作的 100 精魂保留，平衡等效）。</li>
 *   <li>坐騎打不死、不會被打、不能開背包、別人不能騎；不存檔（當機也不會留在世界裡）。</li>
 * </ul>
 */
final class Mounts implements Listener {

    /** 2026-10-26 照 PoE2 Rhoa Mount：騎著能放的技能——弓、投擲長矛（非近戰的長矛投射物）、印記；坐騎技能自己另外處理。 */
    static boolean usableWhileMounted(final SkillDefinition definition) {
        if (definition.mechanic() == Mechanic.MARK) {
            return true;
        }
        if (!definition.isAttack()) {
            return false;
        }
        if (definition.weapons().contains(Tags.BOW) || definition.tags().contains(Tags.BOW)) {
            return true;
        }
        return (definition.weapons().contains(Tags.SPEAR) || definition.tags().contains(Tags.SPEAR))
                && definition.tags().contains(Tags.PROJECTILE) && !definition.tags().contains(Tags.MELEE);
    }

    /** 拿著弓或長矛才能騎（PoE2 Rhoa Mount）。 */
    static boolean mountWeapon(final Set<String> weaponTags) {
        return weaponTags.contains(Tags.BOW) || weaponTags.contains(Tags.SPEAR);
    }

    /**
     * 2026-10-27：坐騎的跳躍力 0.45（約 1.3 格，跟玩家自己跳差不多）、自動踏上 1 格；這次之前 0.75（約 3 格）、1.5 格，
     * 騎著能跳過地圖、競技場的牆（原作的陸行鳥不能跳躍越牆，只是移動）。
     */
    static final double JUMP_STRENGTH = 0.45;
    static final double STEP_HEIGHT = 1.0;

    /** 2026-10-27：不能騎的地方（PvP 競技場、訓練場）。 */
    private boolean blockedHere(final Location at) {
        return (plugin.pvp() != null && plugin.pvp().inArena(at)) || (plugin.training() != null && plugin.training().inArena(at));
    }

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
        // 2026-10-27 照 PoE2 Rhoa Mount：拿著弓或長矛才能騎（這次之前叫得出來、下一秒才被 tick 拉下馬）；PvP 競技場裡不能騎
        if (!mountWeapon(plugin.players().get(player).weaponTags())) {
            return "<red>要拿著弓或長矛才能騎";
        }
        if (blockedHere(player.getLocation())) {
            return "<red>這裡不能騎坐騎";
        }
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
                    jump.setBaseValue(JUMP_STRENGTH);
                }
                final AttributeInstance step = created.getAttribute(Attribute.STEP_HEIGHT);
                if (step != null) {
                    step.setBaseValue(STEP_HEIGHT);
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

    /** 坐騎的速度：原版馬的基本速度 × 倍率 × 你的移動速度（2026-10-26：倍率 0.52 ≈ 跑步的速度，照 PoE2 坐騎不再加移動速度）。 */
    private void syncSpeed(final Player player, final AbstractHorse horse, final Spec spec) {
        final AttributeInstance speed = horse.getAttribute(Attribute.MOVEMENT_SPEED);
        if (speed != null) {
            final double mine = 1 + plugin.players().sheet(player).increased(StatKeys.MOVEMENT_SPEED, Set.of());
            speed.setBaseValue(0.25 * spec.speed() * Math.max(0.5, Math.min(2.5, mine)));
        }
    }

    /** 下馬（坐騎收掉）。 */
    void dismount(final Player player) {
        service.buffs().remove(player, "mounted"); // 2026-10-26：騎乘的傷害加成拿掉了；舊的增益還在身上的話一起拿掉
        final AbstractHorse horse = mounts.remove(player.getUniqueId());
        if (horse != null && horse.isValid()) {
            horse.eject();
            horse.getWorld().spawnParticle(Particle.CLOUD, horse.getLocation().add(0, 0.8, 0), 12, 0.5, 0.4, 0.5, 0.02);
            horse.remove();
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
            if (player == null || !player.isOnline() || !horse.isValid() || !horse.getPassengers().contains(player)
                    || !mountWeapon(plugin.players().get(player).weaponTags()) // 2026-10-26 照 PoE2：換掉弓／長矛就下馬
                    || blockedHere(player.getLocation())) { // 2026-10-27：騎進 PvP 競技場、訓練場就下馬
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
            // 2026-10-26 照 PoE2：拿掉「騎著攻擊更多 15% 傷害」（原作沒有）
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
