package tw.exilecore.map;

import org.bukkit.GameMode;
import org.bukkit.Location;
import org.bukkit.attribute.Attribute;
import org.bukkit.attribute.AttributeInstance;
import org.bukkit.entity.Ageable;
import org.bukkit.entity.Entity;
import org.bukkit.entity.EntityType;
import org.bukkit.entity.LivingEntity;
import org.bukkit.entity.Player;
import org.bukkit.event.entity.CreatureSpawnEvent;
import tw.exilecore.ExileCore;
import tw.exilecore.monster.MonsterData;
import tw.exilecore.monster.MonsterRarity;
import tw.exilecore.zone.ZoneConfig;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.function.Consumer;
import java.util.random.RandomGenerator;

/**
 * 地圖的怪物群：版面上的每個怪物群位置，玩家走近時一次刷出來（PoE 的地圖是固定的怪，不會一直重刷）。
 * 等級是地圖的等級，魔法／稀有怪物群的比例與群的大小吃地圖詞綴；每隻都帶實例標記，屬性表會加上地圖詞綴。
 */
final class MapSpawner {

    /** 怪物最少的追蹤範圍。 */
    private static final double MIN_FOLLOW_RANGE = 48;

    private final ExileCore plugin;
    private final MapService service;
    private final Map<String, Optional<EntityType>> typeCache = new HashMap<>();

    MapSpawner(final ExileCore plugin, final MapService service) {
        this.plugin = plugin;
        this.service = service;
    }

    /** 每 10 tick：開著、有人的實例，玩家附近還沒刷的怪物群刷出來。 */
    void tick() {
        final double radius = service.config().settings().packRadius();
        for (final MapInstance instance : service.instances()) {
            if (instance.state() != MapInstance.State.OPEN || instance.inside().isEmpty() || instance.bossOnly()) {
                continue; // 極境沒有路上的怪：只有 Boss（每個人只能進一次，不要死在路上）
            }
            final List<Player> players = new ArrayList<>();
            for (final UUID uuid : instance.inside()) {
                final Player player = plugin.getServer().getPlayer(uuid);
                if (player != null && !player.isDead() && player.getGameMode() != GameMode.SPECTATOR && instance.contains(player.getLocation())) {
                    players.add(player);
                }
            }
            final List<MapLayout.Spot> spots = instance.layout().packSpots();
            for (int i = 0; i < spots.size(); i++) {
                if (instance.packSpawned(i)) {
                    continue;
                }
                final double x = instance.originX() + spots.get(i).x() + 0.5;
                final double z = instance.originZ() + spots.get(i).z() + 0.5;
                for (final Player player : players) {
                    if (Math.hypot(player.getLocation().getX() - x, player.getLocation().getZ() - z) <= radius) {
                        spawnPack(instance, i);
                        break;
                    }
                }
            }
        }
    }

    /** 刷第 index 群。 */
    void spawnPack(final MapInstance instance, final int index) {
        final MapTwists twists = service.events().twists();
        if (twists.packHeld(instance, index)) {
            return; // 隱藏房還沒打開：先不刷（打開之後走近再刷）
        }
        instance.setPackSpawned(index, true);
        final MapTwists.Theme theme = twists.packTheme(instance, index);
        if (theme == MapTwists.Theme.VAULT) {
            return; // 寶庫：沒有怪群
        }
        final MapConfig.Settings settings = service.config().settings();
        final RandomGenerator random = service.random();
        final ZoneConfig.SpawnEntry mixed = twists.chooseEntry(instance, random);
        final ZoneConfig.SpawnEntry entry = mixed != null ? mixed : chooseMonster(instance.map().monsters(), random);
        if (entry == null) {
            return;
        }
        final MapStats stats = instance.stats();
        double normal = settings.normal().weight();
        double magic = settings.magic().weight() * (1 + stats.magicPacks() / 100.0);
        double rare = settings.rare().weight() * (1 + stats.rarePacks() / 100.0);
        if (theme == MapTwists.Theme.DARK) {
            normal *= 0.3;
            magic *= 1.5;
            rare *= 3;
        }
        final double roll = random.nextDouble() * (normal + magic + rare);
        final double sizeFactor = 1 + (stats.packSize() + instance.twist().packSize) / 100.0;
        final MapLayout.Spot spot = instance.layout().packSpots().get(index);
        final List<LivingEntity> pack = instance.pack(index);
        if (theme == MapTwists.Theme.ELITE) {
            // 精英巢穴：兩隻（30% 三隻）稀有怪，沒有小怪
            final int count = 2 + (random.nextDouble() < 0.3 ? 1 : 0);
            for (int i = 0; i < count; i++) {
                final List<String> rareMods = plugin.events() == null ? null : plugin.events().rareMods(instance.level(), random);
                add(pack, spawn(instance, entry, around(instance, spot, i == 0, random), MonsterRarity.RARE, rareMods));
            }
            return;
        }
        if (theme == MapTwists.Theme.SWARM) {
            // 怪物潮：一大群普通怪
            final int size = size(settings.normal(), sizeFactor * twists.swarmSize(), random);
            for (int i = 0; i < size; i++) {
                add(pack, spawn(instance, entry, around(instance, spot, i == 0, random), MonsterRarity.NORMAL, List.of()));
            }
            return;
        }
        if (roll < normal) {
            final int size = size(settings.normal(), sizeFactor, random);
            for (int i = 0; i < size; i++) {
                add(pack, spawn(instance, entry, around(instance, spot, i == 0, random), MonsterRarity.NORMAL, List.of()));
            }
        } else if (roll < normal + magic) {
            final int size = size(settings.magic(), sizeFactor, random);
            final List<String> mods = plugin.monsters().rollMods(MonsterRarity.MAGIC, instance.level());
            for (int i = 0; i < size; i++) {
                add(pack, spawn(instance, entry, around(instance, spot, i == 0, random), MonsterRarity.MAGIC, mods));
            }
        } else {
            // 每月活動的主題：稀有怪一定帶那一條詞綴（沒有活動照一般抽）
            final List<String> rareMods = plugin.events() == null ? null : plugin.events().rareMods(instance.level(), random);
            add(pack, spawn(instance, entry, around(instance, spot, true, random), MonsterRarity.RARE, rareMods));
            final int minions = size(settings.rare(), sizeFactor, random);
            for (int i = 0; i < minions; i++) {
                add(pack, spawn(instance, entry, around(instance, spot, false, random), MonsterRarity.NORMAL, List.of()));
            }
        }
    }

    private static void add(final List<LivingEntity> pack, final LivingEntity entity) {
        if (entity != null) {
            pack.add(entity);
        }
    }

    private static int size(final MapConfig.PackSettings pack, final double factor, final RandomGenerator random) {
        final int base = pack.maxSize() <= pack.minSize() ? pack.minSize() : pack.minSize() + random.nextInt(pack.maxSize() - pack.minSize() + 1);
        final double scaled = base * factor;
        final int whole = (int) Math.floor(scaled);
        return whole + (random.nextDouble() < scaled - whole ? 1 : 0);
    }

    /** 群的中心附近 3 格內找地板。 */
    private static Location around(final MapInstance instance, final MapLayout.Spot spot, final boolean center, final RandomGenerator random) {
        int x = spot.x();
        int z = spot.z();
        if (!center) {
            for (int attempt = 0; attempt < 6; attempt++) {
                final int nx = spot.x() + random.nextInt(7) - 3;
                final int nz = spot.z() + random.nextInt(7) - 3;
                if (instance.layout().isFloor(nx, nz)) {
                    x = nx;
                    z = nz;
                    break;
                }
            }
        }
        return new Location(instance.world(), instance.originX() + x + 0.5, MapLayout.FLOOR_Y + 1, instance.originZ() + z + 0.5,
                random.nextFloat() * 360f, 0f);
    }

    /** 刷一隻地圖怪（MythicMobs 或原版）；mods 為 null 時依稀有度隨機抽。 */
    LivingEntity spawn(final MapInstance instance, final ZoneConfig.SpawnEntry entry, final Location location,
                       final MonsterRarity rarity, final List<String> mods) {
        LivingEntity entity = null;
        String baseName = null;
        if (entry.isMythic()) {
            entity = plugin.mythic().spawn(entry.mythicId(), location, instance.level());
            if (entity != null) {
                baseName = plugin.mythic().displayName(entity);
                service.tag(entity, instance, false);
            }
        } else {
            entity = spawnVanilla(entry.id(), location, created -> service.tag(created, instance, false));
        }
        if (entity == null || !entity.isValid()) {
            return null;
        }
        service.freeFromWalls(instance, entity); // 刷在柱子、道具上，或體型大的貼著牆刷出來：馬上挪到旁邊的空地（2026-10-15）
        final MonsterData data = plugin.monsters().decorate(entity, instance.level(), rarity, mods, baseName);
        final double power = instance.twist().monsterPower;
        if ((instance.delirium() > 0 || power > 0) && data != null) {
            // 幻霧中：之後刷出來的怪都更硬（生命與傷害乘 1 + 難度）；詛咒寶匣再乘 1 + 詛咒
            final double factor = (1 + instance.delirium()) * (1 + power);
            plugin.monsters().decorateData(entity, new MonsterData(data.level(), data.rarity(), data.mods(), data.name(),
                    data.lifeMultiplier() * factor, data.damageMultiplier() * factor, data.boss()));
        }
        prepare(entity);
        return entity;
    }

    /** 刷一隻原版生物，建立時先執行 before（加標記）；不認得的代號回傳 null。 */
    LivingEntity spawnVanilla(final String typeName, final Location location, final Consumer<LivingEntity> before) {
        final Optional<EntityType> type = entityType(typeName);
        if (type.isEmpty()) {
            return null;
        }
        final Entity spawned = location.getWorld().spawnEntity(location, type.get(), CreatureSpawnEvent.SpawnReason.CUSTOM, created -> {
            if (created instanceof Ageable ageable) {
                ageable.setAdult();
            }
            if (created instanceof LivingEntity living) {
                before.accept(living);
            }
        });
        if (!(spawned instanceof LivingEntity living)) {
            spawned.remove();
            return null;
        }
        living.clearActivePotionEffects();
        return living;
    }

    /** 地圖怪不會因為玩家走遠就消失（區塊卸載時存起來，回來還在），追蹤範圍至少 48 格（站遠射箭也會被追）。 */
    static void prepare(final LivingEntity entity) {
        entity.setPersistent(true);
        entity.setRemoveWhenFarAway(false);
        final AttributeInstance follow = entity.getAttribute(Attribute.FOLLOW_RANGE);
        if (follow != null && follow.getBaseValue() < MIN_FOLLOW_RANGE) {
            follow.setBaseValue(MIN_FOLLOW_RANGE);
        }
    }

    Optional<EntityType> entityType(final String id) {
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
            plugin.getSLF4JLogger().warn("maps.yml：不認得的怪物 {}（要寫原版生物類型，例如 ZOMBIE，或 mythic:代號）", key);
            return Optional.empty();
        });
    }

    /** 照權重抽這張圖的一種怪（事件也用）。 */
    ZoneConfig.SpawnEntry chooseMonster(final List<ZoneConfig.SpawnEntry> entries, final RandomGenerator random) {
        final List<ZoneConfig.SpawnEntry> usable = new ArrayList<>();
        int total = 0;
        for (final ZoneConfig.SpawnEntry entry : entries) {
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
        return usable.getLast();
    }

    /** 關閉實例：還活著的地圖怪全部移除。 */
    void removeAll(final MapInstance instance) {
        for (int i = 0; i < instance.packCount(); i++) {
            for (final LivingEntity entity : instance.pack(i)) {
                if (entity.isValid()) {
                    entity.remove();
                }
            }
            instance.pack(i).clear();
        }
        for (final LivingEntity entity : instance.extras()) {
            if (entity.isValid()) {
                entity.remove();
            }
        }
        instance.extras().clear();
    }

    /** 設定檔重載後重新警告不認得的代號。 */
    void reload() {
        typeCache.clear();
    }
}
