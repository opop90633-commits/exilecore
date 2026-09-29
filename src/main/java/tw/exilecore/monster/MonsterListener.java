package tw.exilecore.monster;

import org.bukkit.Location;
import org.bukkit.entity.Enemy;
import org.bukkit.entity.Item;
import org.bukkit.entity.LivingEntity;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.entity.CreatureSpawnEvent;
import org.bukkit.event.entity.EntityDeathEvent;
import org.bukkit.inventory.ItemStack;
import tw.exilecore.ExileCore;
import tw.exilecore.item.ItemData;
import tw.exilecore.item.Rarity;
import tw.exilecore.stats.StatKeys;
import tw.exilecore.stats.StatSheet;

import java.util.List;
import java.util.Optional;

/**
 * 原版敵對生物生成時等級化，死亡時走我們的掉落表。
 */
public final class MonsterListener implements Listener {

    /** 掉落後只有擊殺者能撿的秒數。 */
    private static final long OWNER_TICKS = 100;

    private final ExileCore plugin;
    private final MonsterService monsters;
    private final DropTable drops;

    public MonsterListener(final ExileCore plugin, final MonsterService monsters, final DropTable drops) {
        this.plugin = plugin;
        this.monsters = monsters;
        this.drops = drops;
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onSpawn(final CreatureSpawnEvent event) {
        if (!(event.getEntity() instanceof Enemy)) {
            return;
        }
        // CUSTOM 是插件自己生成的（例如 /ec spawn），由呼叫端自己決定等級
        if (event.getSpawnReason() == CreatureSpawnEvent.SpawnReason.CUSTOM) {
            return;
        }
        final LivingEntity entity = event.getEntity();
        if (monsters.isMonster(entity)) {
            return;
        }
        monsters.decorateNatural(entity, monsters.levelFor(entity.getLocation()));
    }

    @EventHandler(priority = EventPriority.HIGH)
    public void onDeath(final EntityDeathEvent event) {
        final LivingEntity entity = event.getEntity();
        final Optional<MonsterData> dataOptional = monsters.data(entity);
        if (dataOptional.isEmpty()) {
            return;
        }
        final MonsterData data = dataOptional.get();
        monsters.forget(entity.getUniqueId());

        // 原版掉落與經驗全部關掉，只走我們的掉落表
        event.getDrops().clear();
        event.setDroppedExp(0);

        final Player killer = entity.getKiller();
        double quantityBonus = monsters.config().rarity(data.rarity()).quantityBonus();
        double rarityBonus = monsters.config().rarity(data.rarity()).rarityBonus();
        if (killer != null) {
            final StatSheet sheet = plugin.players().sheet(killer);
            quantityBonus += sheet.increased(StatKeys.DROP_QUANTITY, java.util.Set.of());
            rarityBonus += sheet.increased(StatKeys.DROP_RARITY, java.util.Set.of());
        }

        final List<DropTable.Drop> rolled = drops.roll(data.level(), data.rarity(), quantityBonus, rarityBonus,
                plugin.items().generator(), plugin.currencies().repository(), plugin.items().random());
        final Location location = entity.getLocation();
        for (final DropTable.Drop drop : rolled) {
            final ItemStack stack;
            final boolean highlight;
            if (drop.isCurrency()) {
                stack = plugin.currencies().create(drop.currency(), 1);
                highlight = true;
            } else {
                final ItemData item = drop.item();
                stack = plugin.items().toStack(item);
                highlight = item.rarity() != Rarity.NORMAL;
            }
            final Item dropped = location.getWorld().dropItemNaturally(location, stack);
            dropped.setPickupDelay(10);
            if (highlight) {
                // 用物品自己的名稱（已帶稀有度顏色）當地上的標籤
                stack.editMeta(meta -> dropped.customName(meta.displayName()));
                dropped.setCustomNameVisible(true);
            }
            if (killer != null) {
                dropped.setOwner(killer.getUniqueId());
                plugin.getServer().getScheduler().runTaskLater(plugin, () -> {
                    if (dropped.isValid()) {
                        dropped.setOwner(null);
                    }
                }, OWNER_TICKS);
            }
        }
    }
}
