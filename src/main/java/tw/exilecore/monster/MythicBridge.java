package tw.exilecore.monster;

import org.bukkit.Location;
import org.bukkit.entity.Entity;
import org.bukkit.entity.LivingEntity;
import org.bukkit.entity.Player;
import org.bukkit.event.Event;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.inventory.ItemStack;
import org.bukkit.plugin.PluginManager;
import tw.exilecore.ExileCore;

import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.regex.Pattern;

/**
 * MythicMobs 的橋接。全部用反射呼叫，所以編譯時不需要 MythicMobs，沒裝也不會載入失敗；
 * 對照的是 MythicMobs 5.13 的 API（建立時就把所有方法找好，找不到就改用 {@link NoMythic}）。
 * <p>
 * MythicMobs 怪物的外觀、技能、AI 照 MythicMobs 的設定；等級、生命、傷害、掉落照我們的：
 * <ul>
 *   <li>我們自己生成的（區域刷怪、區域 Boss）：生成後由呼叫端 decorate。</li>
 *   <li>MythicMobs 自己生成的（它的刷怪點、/mm mobs spawn、技能召喚）：生成前（PreSpawn 事件）把等級改成所在位置的等級，
 *       生成後下一個 tick 等級化；召喚出來的小怪一律普通稀有度。</li>
 *   <li>死亡時 MythicMobs 自己的掉落清空，只走我們的掉落表。</li>
 * </ul>
 */
final class MythicBridge implements MythicHook, Listener {

    private static final Pattern COLOR_CODES = Pattern.compile("(?i)[&§]([0-9a-fk-orx]|#[0-9a-f]{6})");
    private static final Pattern TAGS = Pattern.compile("<[^<>]+>");

    private final ExileCore plugin;
    private final MonsterService monsters;

    private final Object mythic;
    private final Method getMobManager;
    private final Method getMythicMob;
    private final Method getMobNames;
    private final Method isMythicMob;
    private final Method getActiveMob;
    private final Method isMythicMobSpawning;
    private final Method adaptLocation;
    private final Method spawnMob;
    private final Method internalName;
    private final Method activeEntity;
    private final Method activeType;
    private final Method activeDisplayName;
    private final Method bukkitEntity;

    private final Class<? extends Event> preSpawnEvent;
    private final Method preSpawnLocation;
    private final Method preSpawnSetLevel;
    private final Class<? extends Event> spawnEvent;
    private final Method spawnEventEntity;
    private final Method spawnEventReason;
    private final Object summonReason;
    private final Class<? extends Event> deathEvent;
    private final Method deathEventEntity;
    private final Method deathEventSetDrops;

    /** 我們自己呼叫生成的期間為 true：這時的生成事件不做自動等級化。 */
    private boolean spawning;
    private boolean warned;

    MythicBridge(final ExileCore plugin, final MonsterService monsters) throws ReflectiveOperationException {
        this.plugin = plugin;
        this.monsters = monsters;
        final ClassLoader loader = MythicBridge.class.getClassLoader();

        final Class<?> mythicBukkit = Class.forName("io.lumine.mythic.bukkit.MythicBukkit", true, loader);
        this.mythic = mythicBukkit.getMethod("inst").invoke(null);
        this.getMobManager = mythicBukkit.getMethod("getMobManager");

        final Class<?> mobExecutor = Class.forName("io.lumine.mythic.core.mobs.MobExecutor", true, loader);
        mobExecutor.cast(getMobManager.invoke(mythic)); // 版本不對會在這裡丟 ClassCastException
        this.getMythicMob = mobExecutor.getMethod("getMythicMob", String.class);
        this.getMobNames = mobExecutor.getMethod("getMobNames");
        this.isMythicMob = mobExecutor.getMethod("isMythicMob", Entity.class);
        this.getActiveMob = mobExecutor.getMethod("getActiveMob", UUID.class);
        this.isMythicMobSpawning = mobExecutor.getMethod("isMythicMobSpawning");

        final Class<?> adapter = Class.forName("io.lumine.mythic.bukkit.BukkitAdapter", true, loader);
        this.adaptLocation = adapter.getMethod("adapt", Location.class);

        final Class<?> mythicMob = Class.forName("io.lumine.mythic.api.mobs.MythicMob", true, loader);
        final Class<?> abstractLocation = Class.forName("io.lumine.mythic.api.adapters.AbstractLocation", true, loader);
        this.spawnMob = mythicMob.getMethod("spawn", abstractLocation, double.class);
        this.internalName = mythicMob.getMethod("getInternalName");

        final Class<?> activeMob = Class.forName("io.lumine.mythic.core.mobs.ActiveMob", true, loader);
        this.activeEntity = activeMob.getMethod("getEntity");
        this.activeType = activeMob.getMethod("getType");
        this.activeDisplayName = activeMob.getMethod("getDisplayName");

        final Class<?> abstractEntity = Class.forName("io.lumine.mythic.api.adapters.AbstractEntity", true, loader);
        this.bukkitEntity = abstractEntity.getMethod("getBukkitEntity");

        this.preSpawnEvent = Class.forName("io.lumine.mythic.bukkit.events.MythicMobPreSpawnEvent", true, loader).asSubclass(Event.class);
        this.preSpawnLocation = preSpawnEvent.getMethod("getLocation");
        this.preSpawnSetLevel = preSpawnEvent.getMethod("setMobLevel", double.class);
        this.spawnEvent = Class.forName("io.lumine.mythic.bukkit.events.MythicMobSpawnEvent", true, loader).asSubclass(Event.class);
        this.spawnEventEntity = spawnEvent.getMethod("getEntity");
        this.spawnEventReason = spawnEvent.getMethod("getSpawnReason");
        this.summonReason = Class.forName("io.lumine.mythic.api.mobs.entities.SpawnReason", true, loader).getField("SUMMON").get(null);

        this.deathEvent = Class.forName("io.lumine.mythic.bukkit.events.MythicMobDeathEvent", true, loader).asSubclass(Event.class);
        this.deathEventEntity = deathEvent.getMethod("getEntity");
        this.deathEventSetDrops = deathEvent.getMethod("setDrops", List.class);
    }

    /** 註冊 MythicMobs 的事件（用 registerEvent，因為編譯時沒有那些事件類別）。 */
    void register() {
        final PluginManager manager = plugin.getServer().getPluginManager();
        manager.registerEvent(preSpawnEvent, this, EventPriority.NORMAL, (listener, event) -> {
            if (preSpawnEvent.isInstance(event)) {
                onMythicPreSpawn(event);
            }
        }, plugin, true);
        manager.registerEvent(spawnEvent, this, EventPriority.NORMAL, (listener, event) -> {
            if (spawnEvent.isInstance(event)) {
                onMythicSpawn(event);
            }
        }, plugin, true);
        manager.registerEvent(deathEvent, this, EventPriority.NORMAL, (listener, event) -> {
            if (deathEvent.isInstance(event)) {
                onMythicDeath(event);
            }
        }, plugin, false);
    }

    private Object manager() throws ReflectiveOperationException {
        return getMobManager.invoke(mythic);
    }

    @Override
    public boolean available() {
        return true;
    }

    @Override
    public boolean exists(final String mobId) {
        if (mobId == null || mobId.isBlank()) {
            return false;
        }
        try {
            return ((Optional<?>) getMythicMob.invoke(manager(), mobId)).isPresent();
        } catch (final ReflectiveOperationException | RuntimeException e) {
            warn(e);
            return false;
        }
    }

    @Override
    public LivingEntity spawn(final String mobId, final Location location, final int level) {
        try {
            final Optional<?> mob = (Optional<?>) getMythicMob.invoke(manager(), mobId);
            if (mob.isEmpty()) {
                return null;
            }
            final Object where = adaptLocation.invoke(null, location);
            final Object active;
            spawning = true;
            try {
                active = spawnMob.invoke(mob.get(), where, (double) level);
            } finally {
                spawning = false;
            }
            if (active == null) {
                return null;
            }
            final Object entity = bukkitEntity.invoke(activeEntity.invoke(active));
            if (entity instanceof LivingEntity living) {
                return living;
            }
            if (entity instanceof Entity other) {
                other.remove(); // 不是生物（例如 MythicMobs 的盔甲座怪）：我們管不了，移除
            }
            return null;
        } catch (final ReflectiveOperationException | RuntimeException e) {
            warn(e);
            return null;
        }
    }

    @Override
    public boolean spawningNow() {
        if (spawning) {
            return true;
        }
        try {
            return (Boolean) isMythicMobSpawning.invoke(manager());
        } catch (final ReflectiveOperationException | RuntimeException e) {
            return false;
        }
    }

    @Override
    public boolean isMythic(final Entity entity) {
        try {
            return (Boolean) isMythicMob.invoke(manager(), entity);
        } catch (final ReflectiveOperationException | RuntimeException e) {
            return false;
        }
    }

    @Override
    public String displayName(final Entity entity) {
        try {
            final Optional<?> active = (Optional<?>) getActiveMob.invoke(manager(), entity.getUniqueId());
            if (active.isEmpty()) {
                return null;
            }
            String name = (String) activeDisplayName.invoke(active.get());
            if (name == null || name.isBlank()) {
                name = (String) internalName.invoke(activeType.invoke(active.get()));
            }
            return strip(name);
        } catch (final ReflectiveOperationException | RuntimeException e) {
            return null;
        }
    }

    @Override
    public Collection<String> mobIds() {
        try {
            @SuppressWarnings("unchecked")
            final Collection<String> names = (Collection<String>) getMobNames.invoke(manager());
            return List.copyOf(names);
        } catch (final ReflectiveOperationException | RuntimeException e) {
            warn(e);
            return List.of();
        }
    }

    // ---- MythicMobs 事件 ----

    /** 生成前：MythicMobs 的等級改成所在位置的等級（我們自己生成的已經帶了等級）。 */
    private void onMythicPreSpawn(final Event event) {
        if (spawning) {
            return;
        }
        try {
            final Object location = preSpawnLocation.invoke(event);
            if (location instanceof Location where && where.getWorld() != null) {
                preSpawnSetLevel.invoke(event, (double) monsters.levelFor(where));
            }
        } catch (final ReflectiveOperationException | RuntimeException e) {
            warn(e);
        }
    }

    private void onMythicSpawn(final Event event) {
        if (spawning) {
            return; // 我們自己生成的，呼叫端會 decorate
        }
        try {
            final Object entity = spawnEventEntity.invoke(event);
            if (!(entity instanceof LivingEntity living) || entity instanceof Player) {
                return;
            }
            final boolean summoned = spawnEventReason.invoke(event) == summonReason;
            if (summoned) {
                monsters.markSummoned(living); // 2026-10-18：MM 召喚出來的小怪不掉東西、不給經驗（以前留著召喚者可以一直刷）
            }
            // 下一個 tick 等 MythicMobs 把名字、裝備、屬性都套完再蓋上我們的
            plugin.getServer().getScheduler().runTask(plugin, () -> {
                if (!living.isValid()) {
                    return;
                }
                final MonsterRarity rarity = summoned ? MonsterRarity.NORMAL : monsters.config().rollRarity(plugin.items().random());
                monsters.decorate(living, monsters.levelFor(living.getLocation()), rarity, null, displayName(living));
            });
        } catch (final ReflectiveOperationException | RuntimeException e) {
            warn(e);
        }
    }

    private void onMythicDeath(final Event event) {
        try {
            final Object entity = deathEventEntity.invoke(event);
            if (entity instanceof LivingEntity living && monsters.isMonster(living)) {
                // 我們的怪物只走我們的掉落表
                deathEventSetDrops.invoke(event, new ArrayList<ItemStack>());
            }
        } catch (final ReflectiveOperationException | RuntimeException e) {
            warn(e);
        }
    }

    private void warn(final Exception e) {
        if (warned) {
            return;
        }
        warned = true;
        final Throwable cause = e instanceof InvocationTargetException invocation && invocation.getCause() != null ? invocation.getCause() : e;
        plugin.getSLF4JLogger().warn("呼叫 MythicMobs 失敗（之後同類錯誤不再顯示）：{}", cause.toString());
    }

    static String strip(final String text) {
        if (text == null) {
            return null;
        }
        return TAGS.matcher(COLOR_CODES.matcher(text).replaceAll("")).replaceAll("").trim();
    }
}
