package tw.exilecore.zone;

import org.bukkit.GameMode;
import org.bukkit.Location;
import org.bukkit.NamespacedKey;
import org.bukkit.Tag;
import org.bukkit.World;
import org.bukkit.attribute.Attribute;
import org.bukkit.attribute.AttributeInstance;
import org.bukkit.block.Block;
import org.bukkit.block.BlockFace;
import org.bukkit.entity.Ageable;
import org.bukkit.entity.Entity;
import org.bukkit.entity.EntityType;
import org.bukkit.entity.LivingEntity;
import org.bukkit.entity.Player;
import org.bukkit.event.entity.CreatureSpawnEvent;
import org.bukkit.persistence.PersistentDataType;
import tw.exilecore.ExileCore;
import tw.exilecore.monster.MonsterRarity;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.Iterator;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.random.RandomGenerator;

/**
 * 區域刷怪：區域裡關掉原版的自然生成，改成在玩家附近（看不太到的距離）一群一群地出現，PoE 的怪物群：
 * 普通群 3–5 隻、魔法群 2–4 隻（共用同一組詞綴）、稀有群 1 隻稀有＋2–3 隻普通小弟。
 * 同一群是同一種怪物，等級照出現位置的區域等級。
 */
final class PackSpawner {

    /** 每次找地點最多試幾次。 */
    private static final int ATTEMPTS = 8;
    /** 離檢查點太近不刷（檢查點是休息的地方）。 */
    private static final double CHECKPOINT_CLEARANCE = 10;
    /** 怪物最少的追蹤範圍（原版骷髏只有 16 格，刷在 16–28 格外會看不到玩家；站遠射箭也要追得到）。 */
    private static final double MIN_FOLLOW_RANGE = 48;

    private final ExileCore plugin;
    private final ZoneService zones;
    private final RandomGenerator random;
    private final NamespacedKey packKey;
    /** 活著的區域怪物。 */
    private final Map<UUID, LivingEntity> alive = new HashMap<>();
    /** 設定檔的怪物代號 → 原版生物種類（不認得的是 empty，只警告一次）。 */
    private final Map<String, Optional<EntityType>> typeCache = new HashMap<>();

    PackSpawner(final ExileCore plugin, final ZoneService zones) {
        this.plugin = plugin;
        this.zones = zones;
        this.random = plugin.items().random();
        this.packKey = new NamespacedKey(plugin, "zone_pack");
    }

    /** 設定檔重載後清掉快取（重新警告不認得的代號）。 */
    void reload() {
        typeCache.clear();
    }

    int alive() {
        prune();
        return alive.size();
    }

    void forget(final UUID uuid) {
        alive.remove(uuid);
    }

    /** 移除所有區域怪物（/ec zone clear、模組停用）。回傳移除幾隻。 */
    int removeAll() {
        int count = 0;
        // 2026-10-18：走訪複本——remove() 同步觸發移除事件會 forget() 改到同一張 HashMap（CME）
        for (final LivingEntity entity : java.util.List.copyOf(alive.values())) {
            if (entity.isValid()) {
                entity.remove();
                count++;
            }
        }
        alive.clear();
        return count;
    }

    private void prune() {
        final Iterator<LivingEntity> iterator = alive.values().iterator();
        while (iterator.hasNext()) {
            if (!iterator.next().isValid()) {
                iterator.remove();
            }
        }
    }

    // ---- 每 interval_ticks 一次 ----

    void tick() {
        prune();
        final ZoneConfig.SpawnerSettings settings = zones.config().spawner();
        if (alive.size() >= settings.worldCap()) {
            return;
        }
        for (final Player player : plugin.getServer().getOnlinePlayers()) {
            if (player.getGameMode() == GameMode.SPECTATOR || player.isDead() || !plugin.players().get(player).hasCharacter()) {
                continue;
            }
            final Zone zone = zones.zoneAt(player.getLocation()).orElse(null);
            if (zone == null || zone.town() || zone.monsters().isEmpty()) {
                continue;
            }
            if (countNear(player.getLocation(), settings.nearbyRadius()) >= settings.maxNearby()) {
                continue;
            }
            spawnPack(player, zone, settings);
            if (alive.size() >= settings.worldCap()) {
                return;
            }
        }
    }

    private int countNear(final Location center, final double radius) {
        final double radiusSquared = radius * radius;
        int count = 0;
        for (final LivingEntity entity : alive.values()) {
            if (entity.getWorld().equals(center.getWorld()) && entity.getLocation().distanceSquared(center) <= radiusSquared) {
                count++;
            }
        }
        return count;
    }

    /** 在玩家附近刷一群；回傳刷了幾隻。 */
    int spawnPack(final Player player, final Zone zone, final ZoneConfig.SpawnerSettings settings) {
        final Location center = findSpot(player.getLocation(), zone, settings);
        if (center == null) {
            return 0;
        }
        final ZoneConfig.SpawnEntry entry = chooseMonster(zone);
        if (entry == null) {
            return 0;
        }
        final int level = zone.levelAt(center.getX(), center.getZ());
        final int total = settings.normal().weight() + settings.magic().weight() + settings.rare().weight();
        final int roll = total <= 0 ? 0 : random.nextInt(total);
        int spawned = 0;
        if (roll < settings.normal().weight()) {
            final int size = between(settings.normal().minSize(), settings.normal().maxSize());
            for (int i = 0; i < size; i++) {
                spawned += spawnOne(zone, entry, i == 0 ? center : around(center), level, MonsterRarity.NORMAL, List.of());
            }
        } else if (roll < settings.normal().weight() + settings.magic().weight()) {
            final int size = between(settings.magic().minSize(), settings.magic().maxSize());
            final List<String> mods = plugin.monsters().rollMods(MonsterRarity.MAGIC, level);
            for (int i = 0; i < size; i++) {
                spawned += spawnOne(zone, entry, i == 0 ? center : around(center), level, MonsterRarity.MAGIC, mods);
            }
        } else {
            spawned += spawnOne(zone, entry, center, level, MonsterRarity.RARE, null);
            final int minions = between(settings.rare().minSize(), settings.rare().maxSize());
            for (int i = 0; i < minions; i++) {
                spawned += spawnOne(zone, entry, around(center), level, MonsterRarity.NORMAL, List.of());
            }
        }
        return spawned;
    }

    private int between(final int min, final int max) {
        return max <= min ? min : min + random.nextInt(max - min + 1);
    }

    private int spawnOne(final Zone zone, final ZoneConfig.SpawnEntry entry, final Location location, final int level,
                         final MonsterRarity rarity, final List<String> mods) {
        final LivingEntity entity;
        String baseName = null;
        if (entry.isMythic()) {
            entity = plugin.mythic().spawn(entry.mythicId(), location, level);
            if (entity != null) {
                baseName = plugin.mythic().displayName(entity);
            }
        } else {
            final Optional<EntityType> type = entityType(entry.id());
            if (type.isEmpty()) {
                return 0;
            }
            final Entity spawned = location.getWorld().spawnEntity(location, type.get(), CreatureSpawnEvent.SpawnReason.CUSTOM, created -> {
                if (created instanceof Ageable ageable) {
                    ageable.setAdult(); // 不要小殭屍
                }
            });
            if (!(spawned instanceof LivingEntity living)) {
                spawned.remove();
                return 0;
            }
            living.clearActivePotionEffects(); // 困難模式的蜘蛛會隨機帶隱形等效果
            entity = living;
        }
        if (entity == null || !entity.isValid()) {
            return 0; // 被其他插件取消了
        }
        plugin.monsters().decorate(entity, level, rarity, mods, baseName);
        entity.getPersistentDataContainer().set(packKey, PersistentDataType.STRING, zone.id());
        entity.setPersistent(false); // 區塊卸載就消失，不會在沒人的地方越積越多；玩家回來時重新刷
        final AttributeInstance follow = entity.getAttribute(Attribute.FOLLOW_RANGE);
        if (follow != null && follow.getBaseValue() < MIN_FOLLOW_RANGE) {
            follow.setBaseValue(MIN_FOLLOW_RANGE);
        }
        alive.put(entity.getUniqueId(), entity);
        return 1;
    }

    private Optional<EntityType> entityType(final String id) {
        return typeCache.computeIfAbsent(id, key -> {
            try {
                final EntityType type = EntityType.valueOf(key.toUpperCase(Locale.ROOT));
                if (type.isAlive() && type.isSpawnable() && type.getEntityClass() != null
                        && LivingEntity.class.isAssignableFrom(type.getEntityClass())) {
                    return Optional.of(type);
                }
            } catch (final IllegalArgumentException ignored) {
                // 下面警告
            }
            plugin.getSLF4JLogger().warn("zones.yml：不認得的怪物 {}（要寫原版生物類型，例如 ZOMBIE，或 mythic:代號）", key);
            return Optional.empty();
        });
    }

    /** 依權重抽這一群的怪物；MythicMobs 怪物不存在時跳過。 */
    private ZoneConfig.SpawnEntry chooseMonster(final Zone zone) {
        final List<ZoneConfig.SpawnEntry> usable = new ArrayList<>();
        int total = 0;
        for (final ZoneConfig.SpawnEntry entry : zone.monsters()) {
            final boolean ok = entry.isMythic() ? plugin.mythic().exists(entry.mythicId()) : entityType(entry.id()).isPresent();
            if (ok) {
                usable.add(entry);
                total += entry.weight();
            }
        }
        if (total <= 0) {
            return null;
        }
        int pick = random.nextInt(total);
        for (final ZoneConfig.SpawnEntry entry : usable) {
            pick -= entry.weight();
            if (pick < 0) {
                return entry;
            }
        }
        return usable.get(usable.size() - 1);
    }

    // ---- 找地點 ----

    private Location findSpot(final Location origin, final Zone zone, final ZoneConfig.SpawnerSettings settings) {
        final World world = origin.getWorld();
        for (int attempt = 0; attempt < ATTEMPTS; attempt++) {
            final double angle = random.nextDouble() * Math.PI * 2;
            final double distance = settings.minDistance() + random.nextDouble() * (settings.maxDistance() - settings.minDistance());
            final int x = (int) Math.floor(origin.getX() + Math.cos(angle) * distance);
            final int z = (int) Math.floor(origin.getZ() + Math.sin(angle) * distance);
            if (!world.isChunkLoaded(x >> 4, z >> 4)) {
                continue;
            }
            final Integer y = ground(world, x, origin.getBlockY(), z, 8);
            if (y == null) {
                continue;
            }
            final Location location = new Location(world, x + 0.5, y, z + 0.5);
            if (!zone.contains(world.getName(), location.getX(), location.getY(), location.getZ()) || nearSafeSpot(zone, location)) {
                continue;
            }
            return location;
        }
        return null;
    }

    /** 群裡其他怪物的位置：中心附近 2 格內找地面，找不到就疊在中心。 */
    private Location around(final Location center) {
        final World world = center.getWorld();
        for (int attempt = 0; attempt < 4; attempt++) {
            final int x = center.getBlockX() + random.nextInt(5) - 2;
            final int z = center.getBlockZ() + random.nextInt(5) - 2;
            final Integer y = ground(world, x, center.getBlockY(), z, 3);
            if (y != null) {
                return new Location(world, x + 0.5, y, z + 0.5);
            }
        }
        return center;
    }

    /** 從 baseY 往上下找站得住的高度：腳下是實心方塊（不是樹葉），腳和頭的位置可以通過且不是液體。 */
    private static Integer ground(final World world, final int x, final int baseY, final int z, final int range) {
        for (int d = 0; d <= range; d++) {
            for (int side = 0; side < (d == 0 ? 1 : 2); side++) {
                final int y = side == 0 ? baseY + d : baseY - d;
                if (y - 1 < world.getMinHeight() || y + 1 >= world.getMaxHeight()) {
                    continue;
                }
                final Block feet = world.getBlockAt(x, y, z);
                final Block below = feet.getRelative(BlockFace.DOWN);
                final Block head = feet.getRelative(BlockFace.UP);
                if (below.isSolid() && !Tag.LEAVES.isTagged(below.getType()) && !built(below.getType())
                        && feet.isPassable() && !feet.isLiquid() && head.isPassable() && !head.isLiquid()) {
                    return y;
                }
            }
        }
        return null;
    }

    /**
     * 2026-10-17：蓋出來的東西上面不刷（屋頂、地板、圍牆上）：木板、樓梯、半磚、原木、羊毛、玻璃、柵欄、牆、地毯、磚類。
     * 天然的地面（草地、泥土、石頭、沙、雪…）照常刷；要讓某塊地不刷怪，地面鋪這些方塊就好。
     */
    static boolean built(final org.bukkit.Material type) {
        if (Tag.PLANKS.isTagged(type) || Tag.STAIRS.isTagged(type) || Tag.SLABS.isTagged(type) || Tag.LOGS.isTagged(type)
                || Tag.WOOL.isTagged(type) || Tag.WOOL_CARPETS.isTagged(type) || Tag.FENCES.isTagged(type) || Tag.WALLS.isTagged(type)
                || Tag.FENCE_GATES.isTagged(type) || Tag.TRAPDOORS.isTagged(type)) {
            return true;
        }
        final String name = type.name();
        return name.contains("GLASS") || name.endsWith("BRICKS") || name.endsWith("TERRACOTTA") || name.endsWith("CONCRETE")
                || name.startsWith("POLISHED_") || name.equals("BOOKSHELF");
    }

    /** 檢查點與 Boss 場地附近不刷。 */
    private boolean nearSafeSpot(final Zone zone, final Location location) {
        for (final ZoneLayout.Checkpoint checkpoint : zone.checkpoints()) {
            if (checkpoint.point().distance2d(location.getX(), location.getZ()) < CHECKPOINT_CLEARANCE) {
                return true;
            }
        }
        final ZoneLayout.Point arena = zone.layout().arena();
        return arena != null && zone.boss() != null
                && arena.distance2d(location.getX(), location.getZ()) < zones.config().boss().radius() + 6;
    }
}
