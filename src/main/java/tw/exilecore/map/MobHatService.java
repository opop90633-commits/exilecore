package tw.exilecore.map;

import io.papermc.paper.event.player.PlayerTrackEntityEvent;
import org.bukkit.Material;
import org.bukkit.NamespacedKey;
import org.bukkit.World;
import org.bukkit.entity.Entity;
import org.bukkit.entity.EntityType;
import org.bukkit.entity.LivingEntity;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.inventory.EntityEquipment;
import org.bukkit.inventory.EquipmentSlot;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.persistence.PersistentDataType;
import tw.exilecore.ExileCore;
import tw.exilecore.web.MobHats;

import java.util.EnumSet;
import java.util.HashMap;
import java.util.Map;
import java.util.Set;

/**
 * 2026-10-31 照站長：地圖怪照場景戴頭飾（資源包的 3D 模型，{@link MobHats}）。
 * <p>
 * 怪物真正的頭盔欄是空的（不影響掉落、護甲、陽光），頭飾只用封包送給有資源包的人：開始看到這隻怪的下一 tick 送一次，
 * 之後每 5 秒補送附近的（剛載好資源包、伺服器重送裝備的情況）。沒有資源包的人看到原版。
 * 只有會畫頭上東西的人形怪才戴；原本就戴著頭盔的（例如流寇）不蓋掉。
 */
public final class MobHatService implements Listener {

    /** 會把頭盔欄的東西畫在頭上的怪。 */
    static final Set<EntityType> HEADS = EnumSet.of(EntityType.ZOMBIE, EntityType.HUSK, EntityType.DROWNED, EntityType.ZOMBIE_VILLAGER,
            EntityType.SKELETON, EntityType.STRAY, EntityType.WITHER_SKELETON, EntityType.BOGGED, EntityType.PIGLIN,
            EntityType.PIGLIN_BRUTE, EntityType.ZOMBIFIED_PIGLIN, EntityType.VINDICATOR, EntityType.PILLAGER, EntityType.EVOKER,
            EntityType.ILLUSIONER, EntityType.GIANT);
    /** 補送的間隔（tick）與範圍（格）。 */
    static final int SWEEP_TICKS = 100;
    private static final double SWEEP_RANGE = 48;

    private final ExileCore plugin;
    private final NamespacedKey key;
    private final Map<String, ItemStack> items = new HashMap<>();

    MobHatService(final ExileCore plugin) {
        this.plugin = plugin;
        this.key = new NamespacedKey(plugin, "mob_hat");
    }

    /** 這隻怪戴這個場景的頭飾（elite：稀有、傳奇、Boss 戴加大的）。 */
    void dress(final LivingEntity entity, final String theme, final boolean elite) {
        if (entity == null || !HEADS.contains(entity.getType())) {
            return;
        }
        final String model = MobHats.model(theme, elite);
        if (model == null || hasHelmet(entity)) {
            return;
        }
        entity.getPersistentDataContainer().set(key, PersistentDataType.STRING, model);
    }

    private static boolean hasHelmet(final LivingEntity entity) {
        final EntityEquipment equipment = entity.getEquipment();
        return equipment != null && !equipment.getItem(EquipmentSlot.HEAD).isEmpty();
    }

    private String hat(final Entity entity) {
        return entity instanceof LivingEntity ? entity.getPersistentDataContainer().get(key, PersistentDataType.STRING) : null;
    }

    private boolean hasPack(final Player player) {
        return plugin.web() != null && plugin.web().hasPack(player);
    }

    private ItemStack item(final String model) {
        return items.computeIfAbsent(model, m -> {
            final ItemStack stack = new ItemStack(Material.PAPER);
            final ItemMeta meta = stack.getItemMeta();
            meta.setItemModel(NamespacedKey.fromString(m));
            stack.setItemMeta(meta);
            return stack;
        });
    }

    private void send(final Player player, final LivingEntity entity, final String model) {
        if (player.isOnline() && entity.isValid() && !hasHelmet(entity)) {
            player.sendEquipmentChange(entity, EquipmentSlot.HEAD, item(model));
        }
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onTrack(final PlayerTrackEntityEvent event) {
        final String model = hat(event.getEntity());
        if (model == null || !hasPack(event.getPlayer())) {
            return;
        }
        final Player player = event.getPlayer();
        final LivingEntity entity = (LivingEntity) event.getEntity();
        // 這個事件在生成封包之前：等下一 tick 客戶端有這隻怪了再送
        plugin.getServer().getScheduler().runTask(plugin, () -> send(player, entity, model));
    }

    /** 每 5 秒：地圖世界裡有資源包的人，附近戴頭飾的怪再送一次。 */
    void sweep(final World world) {
        if (world == null) {
            return;
        }
        for (final Player player : world.getPlayers()) {
            if (!hasPack(player)) {
                continue;
            }
            for (final Entity entity : player.getNearbyEntities(SWEEP_RANGE, SWEEP_RANGE / 2, SWEEP_RANGE)) {
                final String model = hat(entity);
                if (model != null) {
                    send(player, (LivingEntity) entity, model);
                }
            }
        }
    }
}
