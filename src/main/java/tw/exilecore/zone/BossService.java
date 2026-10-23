package tw.exilecore.zone;

import net.kyori.adventure.bossbar.BossBar;
import net.kyori.adventure.title.Title;
import org.bukkit.GameMode;
import org.bukkit.Location;
import org.bukkit.NamespacedKey;
import org.bukkit.Sound;
import org.bukkit.attribute.Attribute;
import org.bukkit.attribute.AttributeInstance;
import org.bukkit.entity.Ageable;
import org.bukkit.entity.Entity;
import org.bukkit.entity.EntityType;
import org.bukkit.entity.LivingEntity;
import org.bukkit.entity.Player;
import org.bukkit.entity.Projectile;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.entity.CreatureSpawnEvent;
import org.bukkit.event.entity.EntityDamageByEntityEvent;
import org.bukkit.event.entity.EntityDeathEvent;
import org.bukkit.persistence.PersistentDataType;
import tw.exilecore.ExileCore;
import tw.exilecore.character.CharacterRecord;
import tw.exilecore.character.PlayerState;
import tw.exilecore.item.ItemRenderer;

import java.time.Duration;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

/**
 * 區域 Boss：玩家走進 Boss 場地就出現，頭上有血條（BossBar），附近沒人一段時間就消失重置。
 * 打倒時，每個有打到它的角色如果是第一次打倒這個 Boss，送天賦點（進度 {@code boss.<區域>}）。
 * Boss 不存進區塊（插件重開或區塊卸載就消失），所以不會出現兩隻。
 */
final class BossService implements Listener {

    /** 血條的顯示距離比場地半徑多這麼多格。 */
    private static final double BAR_EXTRA = 16;
    /** Boss 被拉出場地這麼遠、而且身邊沒有人在打它，就傳回中心（有人在打就一路追）。 */
    private static final double LEASH_EXTRA = 12;
    /** Boss 身邊這麼近有玩家就算「有人在打」。 */
    private static final double ENGAGED = 24;
    /** Boss 的追蹤範圍。 */
    private static final double FOLLOW_RANGE = 64;

    /** 一場進行中的 Boss 戰。 */
    private static final class Fight {
        final Zone zone;
        final LivingEntity entity;
        final BossBar bar;
        final Set<UUID> damagers = new HashSet<>();
        final Set<UUID> viewers = new HashSet<>();
        long lastPlayerTick;

        Fight(final Zone zone, final LivingEntity entity, final BossBar bar, final long now) {
            this.zone = zone;
            this.entity = entity;
            this.bar = bar;
            this.lastPlayerTick = now;
        }
    }

    private final ExileCore plugin;
    private final ZoneService zones;
    private final NamespacedKey bossKey;
    private final Map<String, Fight> fights = new HashMap<>();
    private final Map<String, Long> cooldownUntil = new HashMap<>();
    private final Set<String> warned = new HashSet<>();

    BossService(final ExileCore plugin, final ZoneService zones) {
        this.plugin = plugin;
        this.zones = zones;
        this.bossKey = new NamespacedKey(plugin, "zone_boss");
    }

    // ---- 每 20 tick ----

    void tick() {
        final long now = plugin.getServer().getCurrentTick();
        final ZoneConfig.BossSettings settings = zones.config().boss();
        for (final Zone zone : zones.combatZones()) {
            if (zone.boss() == null || zone.layout().arena() == null) {
                continue;
            }
            final Location arena = zones.toLocation(zone.layout().arena());
            if (arena == null) {
                continue;
            }
            final Fight fight = fights.get(zone.id());
            if (fight != null) {
                updateFight(fight, arena, settings, now);
                continue;
            }
            if (now < cooldownUntil.getOrDefault(zone.id(), 0L)) {
                continue;
            }
            if (!challengers(arena, settings.radius()).isEmpty()) {
                spawn(zone, arena);
            }
        }
    }

    private void updateFight(final Fight fight, final Location arena, final ZoneConfig.BossSettings settings, final long now) {
        final LivingEntity boss = fight.entity;
        if (!boss.isValid()) {
            // 真正的死亡已經在死亡事件裡結束戰鬥；還在這裡的是被移除或區塊卸載（isDead 對被移除的實體也是 true）
            boss.remove();
            end(fight);
            return;
        }
        // 血條：給場地附近的人看
        final AttributeInstance max = boss.getAttribute(Attribute.MAX_HEALTH);
        final double maxHealth = max == null ? boss.getHealth() : max.getValue();
        fight.bar.progress((float) Math.max(0, Math.min(1, boss.getHealth() / Math.max(1, maxHealth))));
        // 「附近」以 Boss 現在的位置算：追著人跑出場地也不算沒人
        final List<Player> near = challengers(boss.getLocation(), settings.radius() + BAR_EXTRA);
        final Set<UUID> nearIds = new HashSet<>();
        // 2026-10：血條常駐——附近的人都看得到（剛復活的保護時間、死掉觀戰也一樣），不只是正在打的人
        final List<Player> watchers = new ArrayList<>(near);
        for (final Player player : boss.getWorld().getNearbyPlayers(boss.getLocation(), settings.radius() + BAR_EXTRA + 16)) {
            if (!watchers.contains(player) && plugin.players().get(player).hasCharacter()) {
                watchers.add(player);
            }
        }
        for (final Player player : watchers) {
            nearIds.add(player.getUniqueId());
            if (fight.viewers.add(player.getUniqueId())) {
                player.showBossBar(fight.bar);
            }
        }
        for (final UUID viewer : new ArrayList<>(fight.viewers)) {
            if (!nearIds.contains(viewer)) {
                fight.viewers.remove(viewer);
                final Player player = plugin.getServer().getPlayer(viewer);
                if (player != null) {
                    player.hideBossBar(fight.bar);
                }
            }
        }
        if (!near.isEmpty()) {
            fight.lastPlayerTick = now;
        } else if (now - fight.lastPlayerTick > settings.resetSeconds() * 20L) {
            boss.remove(); // 沒人了：重置（下次重新出現、滿血）
            end(fight);
            return;
        }
        // 2026-10：追出場地太遠（場地半徑 + LEASH_EXTRA）就一定拉回原位、放掉目標——不管身邊有沒有人在打，
        // 免得被風箏到下一個區域（例如 12 級的 Boss 被拉到 24 級 Boss 那邊）
        if (!boss.getWorld().equals(arena.getWorld())
                || boss.getLocation().distanceSquared(arena) > Math.pow(settings.radius() + LEASH_EXTRA, 2)) {
            final Location from = boss.getLocation();
            boss.teleport(arena);
            if (boss instanceof org.bukkit.entity.Mob mob) {
                mob.setTarget(null);
            }
            tw.exilecore.skill.Fx.spawn(from.getWorld(), org.bukkit.Particle.REVERSE_PORTAL, from.add(0, 1, 0), 30, 0.6, 1.0, 0.6, 0.05);
            for (final Player player : near) {
                tw.exilecore.gui.ActionBar.send(player, plugin.text().parse("<gray>" + tw.exilecore.item.ItemRenderer.escape(fight.zone.boss().name())
                        + " 不再追了，退回自己的場地"));
            }
        }
    }

    /** 場地附近的角色（有角色、活著、不是旁觀模式）。 */
    private List<Player> challengers(final Location arena, final double radius) {
        final List<Player> list = new ArrayList<>();
        final double radiusSquared = radius * radius;
        for (final Player player : arena.getWorld().getNearbyPlayers(arena, radius)) {
            if (player.getLocation().distanceSquared(arena) <= radiusSquared && player.getGameMode() != GameMode.SPECTATOR
                    && !player.isDead() && plugin.players().get(player).hasCharacter() && !plugin.players().get(player).isSafe()) {
                list.add(player);
            }
        }
        return list;
    }

    // ---- 生成與結束 ----

    /** 在場地生成這個區域的 Boss（已經有一隻就先移除）。回傳是否成功。 */
    boolean spawn(final Zone zone, final Location arena) {
        final Fight existing = fights.get(zone.id());
        if (existing != null) {
            existing.entity.remove();
            end(existing);
        }
        final ZoneConfig.BossDefinition definition = zone.boss();
        final int level = zone.levelAt(arena.getX(), arena.getZ());
        LivingEntity entity = null;
        if (definition.mythicId() != null && plugin.mythic().exists(definition.mythicId())) {
            entity = plugin.mythic().spawn(definition.mythicId(), arena, level);
        } else if (definition.mythicId() != null && plugin.mythic().available() && warned.add(zone.id() + ":mythic")) {
            plugin.getSLF4JLogger().warn("MythicMobs 裡沒有 {}（區域 {} 的 Boss），改用原版 {}", definition.mythicId(), zone.id(), definition.vanillaType());
        }
        if (entity == null) {
            entity = spawnVanilla(zone, definition, arena);
        }
        if (entity == null || !entity.isValid()) {
            cooldownUntil.put(zone.id(), plugin.getServer().getCurrentTick() + 20L * 30);
            return false;
        }
        if (definition.vanillaType() != null && !entity.getType().name().equalsIgnoreCase(definition.vanillaType())
                && warned.add(zone.id() + ":type")) {
            // 生命倍率看的是實際生出來的生物種類；/ec balance 算 Boss 時間用的是 zones.yml 的 type
            plugin.getSLF4JLogger().warn("區域 {} 的 Boss 生出來是 {}，zones.yml 寫的 type 是 {}：生命照 {} 的倍率算，/ec balance 的 Boss 時間會不準",
                    zone.id(), entity.getType().name(), definition.vanillaType(), entity.getType().name());
        }
        entity.getPersistentDataContainer().set(bossKey, PersistentDataType.STRING, zone.id());
        entity.setPersistent(false);
        try {
            plugin.monsters().decorateBoss(entity, level, definition.name(), definition.mods(),
                    definition.lifeMultiplier(), definition.damageMultiplier(), definition.scale());
        } catch (final RuntimeException e) {
            entity.remove(); // 沒等級化的 Boss 不能留著
            cooldownUntil.put(zone.id(), plugin.getServer().getCurrentTick() + 20L * 30);
            plugin.getSLF4JLogger().error("區域 {} 的 Boss 等級化失敗", zone.id(), e);
            return false;
        }
        final AttributeInstance follow = entity.getAttribute(Attribute.FOLLOW_RANGE);
        if (follow != null && follow.getBaseValue() < FOLLOW_RANGE) {
            follow.setBaseValue(FOLLOW_RANGE);
        }
        final BossBar bar = BossBar.bossBar(plugin.text().parse("<#af6025>" + ItemRenderer.escape(definition.name())
                + " <gray>Lv." + level), 1f, BossBar.Color.RED, BossBar.Overlay.NOTCHED_12);
        final Fight fight = new Fight(zone, entity, bar, plugin.getServer().getCurrentTick());
        fights.put(zone.id(), fight);
        attachSkills(zone, definition, entity, level);

        for (final Player player : challengers(arena, zones.config().boss().radius() + BAR_EXTRA)) {
            player.showTitle(Title.title(plugin.text().parse("<#af6025>" + ItemRenderer.escape(definition.name())),
                    plugin.text().parse("<gray>" + ItemRenderer.escape(zone.name()) + "的區域 Boss"),
                    Title.Times.times(Duration.ofMillis(300), Duration.ofMillis(2200), Duration.ofMillis(600))));
            player.playSound(player.getLocation(), Sound.ENTITY_WITHER_SPAWN, 0.5f, 1.2f);
            if (definition.intro() != null && !definition.intro().isBlank() && !plugin.players().hidesMonsterTalk(player)) {
                player.sendMessage(plugin.text().parse(Zone.loreLine(definition.name(), definition.intro())));
            }
            fight.viewers.add(player.getUniqueId());
            player.showBossBar(bar);
        }
        return true;
    }

    private LivingEntity spawnVanilla(final Zone zone, final ZoneConfig.BossDefinition definition, final Location arena) {
        final EntityType type;
        try {
            type = EntityType.valueOf(definition.vanillaType().toUpperCase(Locale.ROOT));
        } catch (final IllegalArgumentException e) {
            if (warned.add(zone.id() + ":type")) {
                plugin.getSLF4JLogger().warn("zones.yml：區域 {} 的 Boss 類型 {} 不認得", zone.id(), definition.vanillaType());
            }
            return null;
        }
        if (!type.isSpawnable() || type.getEntityClass() == null || !LivingEntity.class.isAssignableFrom(type.getEntityClass())) {
            if (warned.add(zone.id() + ":type")) {
                plugin.getSLF4JLogger().warn("zones.yml：區域 {} 的 Boss 類型 {} 不能生成", zone.id(), definition.vanillaType());
            }
            return null;
        }
        final Entity entity = arena.getWorld().spawnEntity(arena, type, CreatureSpawnEvent.SpawnReason.CUSTOM, created -> {
            if (created instanceof Ageable ageable) {
                ageable.setAdult();
            }
        });
        if (entity instanceof LivingEntity living) {
            living.clearActivePotionEffects(); // 困難模式的蜘蛛會隨機帶隱形
            return living;
        }
        entity.remove();
        return null;
    }

    /** 掛上招式（data/boss_skills.yml：zones.yml 寫的 skills，沒寫就照生物種類的預設）。小怪生成在場地裡、算這個區域的怪。 */
    private void attachSkills(final Zone zone, final ZoneConfig.BossDefinition definition, final LivingEntity boss, final int level) {
        if (plugin.skills() == null) {
            return;
        }
        plugin.skills().bossSkills().attach(boss, definition.skills(), new tw.exilecore.skill.BossSkills.Host() {
            @Override
            public String name() {
                return definition.name();
            }

            @Override
            public int phase() {
                return 0;
            }

            @Override
            public LivingEntity summon(final String type, final Location at) {
                final EntityType entityType;
                try {
                    entityType = EntityType.valueOf(type.toUpperCase(Locale.ROOT));
                } catch (final IllegalArgumentException e) {
                    return null;
                }
                if (!entityType.isSpawnable() || entityType.getEntityClass() == null || !LivingEntity.class.isAssignableFrom(entityType.getEntityClass())) {
                    return null;
                }
                final Entity spawned = at.getWorld().spawnEntity(at, entityType, CreatureSpawnEvent.SpawnReason.CUSTOM, created -> {
                    if (created instanceof Ageable ageable) {
                        ageable.setAdult();
                    }
                    if (created instanceof LivingEntity living) {
                        plugin.monsters().markSummoned(living);
                    }
                });
                if (!(spawned instanceof LivingEntity minion) || !spawned.isValid()) {
                    spawned.remove();
                    return null;
                }
                minion.clearActivePotionEffects();
                minion.setPersistent(false);
                plugin.monsters().decorate(minion, level, tw.exilecore.monster.MonsterRarity.NORMAL, List.of());
                return minion;
            }
        });
    }

    /** 收掉一場戰鬥（血條、紀錄）；不會移除實體。 */
    private void end(final Fight fight) {
        if (plugin.skills() != null) {
            plugin.skills().bossSkills().detach(fight.entity.getUniqueId());
        }
        for (final UUID viewer : fight.viewers) {
            final Player player = plugin.getServer().getPlayer(viewer);
            if (player != null) {
                player.hideBossBar(fight.bar);
            }
        }
        fight.viewers.clear();
        fights.remove(fight.zone.id(), fight);
    }

    /** 管理指令：移除 Boss 並清掉冷卻。 */
    boolean reset(final Zone zone) {
        cooldownUntil.remove(zone.id());
        final Fight fight = fights.get(zone.id());
        if (fight == null) {
            return false;
        }
        fight.entity.remove();
        end(fight);
        return true;
    }

    Optional<LivingEntity> boss(final Zone zone) {
        final Fight fight = fights.get(zone.id());
        return fight == null ? Optional.empty() : Optional.of(fight.entity);
    }

    /** 模組停用：移除所有 Boss 與血條。 */
    void shutdown() {
        for (final Fight fight : new ArrayList<>(fights.values())) {
            fight.entity.remove();
            end(fight);
        }
        fights.clear();
    }

    // ---- 事件 ----

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onDamage(final EntityDamageByEntityEvent event) {
        if (!event.getEntity().getPersistentDataContainer().has(bossKey)) {
            return;
        }
        Entity damager = event.getDamager();
        if (damager instanceof Projectile projectile && projectile.getShooter() instanceof Entity shooter) {
            damager = shooter;
        }
        if (!(damager instanceof Player player)) {
            return;
        }
        for (final Fight fight : fights.values()) {
            if (fight.entity.equals(event.getEntity())) {
                fight.damagers.add(player.getUniqueId());
                return;
            }
        }
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onDeath(final EntityDeathEvent event) {
        Fight fight = null;
        for (final Fight candidate : fights.values()) {
            if (candidate.entity.equals(event.getEntity())) {
                fight = candidate;
                break;
            }
        }
        if (fight == null) {
            return;
        }
        final ZoneConfig.BossSettings settings = zones.config().boss();
        cooldownUntil.put(fight.zone.id(), plugin.getServer().getCurrentTick() + settings.respawnSeconds() * 20L);
        final String bossName = ItemRenderer.escape(fight.zone.boss().name());
        final Player killer = event.getEntity().getKiller();
        if (killer != null) {
            fight.damagers.add(killer.getUniqueId());
        }
        final String defeat = fight.zone.boss().defeat();
        if (defeat != null && !defeat.isBlank()) {
            final Set<UUID> audience = new HashSet<>(fight.viewers);
            audience.addAll(fight.damagers);
            for (final UUID uuid : audience) {
                final Player player = plugin.getServer().getPlayer(uuid);
                if (player != null && !plugin.players().hidesMonsterTalk(player)) {
                    player.sendMessage(plugin.text().parse(Zone.loreLine(fight.zone.boss().name(), defeat)));
                }
            }
        }
        final org.bukkit.Location deathLocation = event.getEntity().getLocation();
        // 跟掉落一樣：傷害有到 5% 的人（party.yml）都算擊敗（首殺天賦點、最後一個區域的地圖）
        for (final Player player : plugin.monsterModule().credit().sharers(event.getEntity(), killer)) {
            final boolean firstKill = reward(player, fight.zone, bossName, settings.firstKillPoints());
            if (plugin.maps() != null) {
                plugin.maps().onZoneBossKilled(fight.zone, player, firstKill, deathLocation);
            }
            if (plugin.quests() != null) {
                plugin.quests().onBoss(player, fight.zone.id());
            }
        }
        end(fight);
    }

    /** @return 是不是第一次擊敗 */
    private boolean reward(final Player player, final Zone zone, final String bossName, final int points) {
        final PlayerState state = plugin.players().get(player);
        final CharacterRecord character = state.character();
        if (character == null) {
            return false;
        }
        final String key = "boss." + zone.id();
        if (state.progress().containsKey(key)) {
            player.sendMessage(plugin.text().prefixed("<gray>擊敗了 <#af6025>" + bossName));
            return false;
        }
        plugin.characters().setProgress(player, key, String.valueOf(System.currentTimeMillis() / 1000));
        if (points > 0) {
            character.setBonusPoints(character.bonusPoints() + points);
            state.markDirty();
        }
        plugin.characters().save(player);
        player.showTitle(Title.title(plugin.text().parse("<#f5b942>首次擊敗 " + bossName + "！"),
                plugin.text().parse(points > 0 ? "<white>天賦點 +" + points + "　<gray>輸入 <white>/tree</white> 配點" : "<gray>" + ItemRenderer.escape(zone.name())),
                Title.Times.times(Duration.ofMillis(300), Duration.ofMillis(3000), Duration.ofMillis(800))));
        player.playSound(player.getLocation(), Sound.UI_TOAST_CHALLENGE_COMPLETE, 0.8f, 1.0f);
        player.sendMessage(plugin.text().prefixed("<#f5b942>首次擊敗 " + bossName
                + (points > 0 ? "<gray>，天賦點 +" + points + "，可用 <white>" + plugin.players().availablePoints(state) : "")));
        return true;
    }
}
