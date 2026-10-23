package tw.exilecore.map;

import net.kyori.adventure.bossbar.BossBar;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.title.Title;
import org.bukkit.GameMode;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.NamespacedKey;
import org.bukkit.Particle;
import org.bukkit.Sound;
import org.bukkit.block.Block;
import org.bukkit.entity.Display;
import org.bukkit.entity.Entity;
import org.bukkit.entity.LivingEntity;
import org.bukkit.entity.Mob;
import org.bukkit.entity.Player;
import org.bukkit.entity.TextDisplay;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.entity.EntityDeathEvent;
import org.bukkit.persistence.PersistentDataType;
import tw.exilecore.ExileCore;
import tw.exilecore.item.ItemRenderer;
import tw.exilecore.monster.DropTable;
import tw.exilecore.monster.MonsterData;
import tw.exilecore.monster.MonsterRarity;
import tw.exilecore.zone.ZoneConfig;

import java.time.Duration;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Iterator;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.random.RandomGenerator;

/**
 * 幻霧（PoE2 的 Delirium）：地圖裡一面鏡子，走過去，霧就跟著這張圖裡的每個人一段時間——越久怪越硬（之後刷出來的怪與幻霧怪的生命、傷害
 * 一路往上），每隔幾秒在每個人附近刷一小群幻霧怪。殺怪累積獎勵進度（每個人各自算），時間到照進度掉獎勵（蒸餾情緒主要從這裡來）。
 * 難度由 {@link MapInstance#delirium()} 帶給 {@link MapSpawner}。設定在 {@code map_events.yml} 的 {@code delirium}。
 */
final class DeliriumEvents implements Listener {

    /** 走到鏡子幾格內就開始。 */
    private static final double TOUCH = 1.8;

    /** 一張圖的幻霧。 */
    final class Fog {
        final MapInstance instance;
        final Location mirror;
        TextDisplay label;
        boolean started;
        boolean done;
        long startTick;
        long nextSpawnTick;
        final List<LivingEntity> monsters = new ArrayList<>();
        /** 每個人的獎勵進度。 */
        final Map<UUID, Integer> progress = new HashMap<>();
        /** 這次的獎勵種類（每個人一樣）。 */
        final List<MapEventConfig.Reward> tracks = new ArrayList<>();
        BossBar bar;
        final Set<UUID> watching = new HashSet<>();

        Fog(final MapInstance instance, final Location mirror) {
            this.instance = instance;
            this.mirror = mirror;
        }
    }

    private final ExileCore plugin;
    private final MapService service;
    private final MapEventConfig config;
    private final Map<Integer, Fog> byInstance = new HashMap<>();
    /** 幻霧怪（額外刷的）。 */
    private final NamespacedKey deliriumKey;

    DeliriumEvents(final ExileCore plugin, final MapService service, final MapEventConfig config) {
        this.plugin = plugin;
        this.service = service;
        this.config = config;
        this.deliriumKey = new NamespacedKey(plugin, "delirium_monster");
    }

    // ---- 放置 ----

    /** 地圖蓋好：照機率放一面鏡子（從 rooms 裡拿一個房間）。 */
    void place(final MapInstance instance, final List<MapLayout.Room> rooms) {
        final MapEventConfig.Delirium settings = config.delirium();
        final RandomGenerator random = service.random();
        final double monthly = plugin.events() == null ? -1 : plugin.events().deliriumChance();
        // 每月活動可以改這個月的機率；星圖天賦再增加 x%
        final double chance = Math.min(1.0, (monthly >= 0 ? monthly : settings.chance()) * (1 + instance.atlas().increased("atlas.delirium.chance")));
        // 2026-10-21 修正：星圖核心「單一信仰」：其他事件不出現（祭壇、幻霧也算）
        if (instance.atlas().get("atlas.other_events_off") > 0) {
            return;
        }
        if (instance.special() != null || chance <= 0 || random.nextDouble() >= chance || rooms.isEmpty()) {
            return;
        }
        // 2026-10-21：鏡子放在地圖開頭（起點房間北側）；放不下才照舊找一個房間
        final MapLayout.Spot early = MapEvents.startSpot(instance.layout(), 3, -4);
        for (int attempt = 0; attempt < 3 && (early != null || !rooms.isEmpty()); attempt++) {
            final MapLayout.Spot spot = early != null ? early : MapEvents.spotIn(instance.layout(), rooms.remove(random.nextInt(rooms.size())));
            if (spot == null) {
                continue;
            }
            final Location mirror = new Location(instance.world(), instance.originX() + spot.x() + 0.5, MapLayout.FLOOR_Y + 1,
                    instance.originZ() + spot.z() + 0.5);
            final Block base = mirror.getBlock();
            base.setType(Material.TINTED_GLASS);
            base.getRelative(0, 1, 0).setType(Material.TINTED_GLASS);
            byInstance.put(instance.id(), new Fog(instance, mirror));
            return;
        }
    }

    // ---- 每 10 tick ----

    void tick(final long now) {
        if (byInstance.isEmpty()) {
            return;
        }
        final Iterator<Map.Entry<Integer, Fog>> iterator = byInstance.entrySet().iterator();
        while (iterator.hasNext()) {
            final Map.Entry<Integer, Fog> entry = iterator.next();
            final Fog fog = entry.getValue();
            final MapInstance instance = service.instance(entry.getKey());
            if (instance == null || instance.state() == MapInstance.State.CLOSING) {
                dispose(fog);
                iterator.remove();
                continue;
            }
            if (instance.state() != MapInstance.State.OPEN || fog.done) {
                continue;
            }
            try {
                tickFog(fog, now);
            } catch (final RuntimeException e) {
                plugin.getSLF4JLogger().error("地圖 #{} 的幻霧出錯", instance.id(), e);
                end(fog, false);
            }
        }
    }

    private List<Player> players(final MapInstance instance) {
        final List<Player> list = new ArrayList<>();
        for (final UUID uuid : instance.inside()) {
            final Player player = plugin.getServer().getPlayer(uuid);
            if (player != null && !player.isDead() && player.getGameMode() != GameMode.SPECTATOR && instance.contains(player.getLocation())) {
                list.add(player);
            }
        }
        return list;
    }

    private void tickFog(final Fog fog, final long now) {
        final MapEventConfig.Delirium settings = config.delirium();
        final Location mirror = fog.mirror;
        // 2026-10-18：只有開始前（鏡子的標籤、粒子、碰觸）要鏡子的區塊；開始後霧跟著人走，往遠處打也要照常計時、刷怪、結算
        if (!fog.started && !mirror.getWorld().isChunkLoaded(mirror.getBlockX() >> 4, mirror.getBlockZ() >> 4)) {
            return;
        }
        final List<Player> players = players(fog.instance);
        if (!fog.started) {
            if (fog.label == null || !fog.label.isValid()) {
                fog.label = label(fog);
            }
            mirror.getWorld().spawnParticle(Particle.WHITE_ASH, mirror.getX(), mirror.getY() + 1, mirror.getZ(), 6, 0.6, 0.8, 0.6, 0.01);
            for (final Player player : players) {
                if (Math.hypot(player.getLocation().getX() - mirror.getX(), player.getLocation().getZ() - mirror.getZ()) <= TOUCH) {
                    start(fog, player, now);
                    break;
                }
            }
            return;
        }
        final double elapsed = (now - fog.startTick) / 20.0;
        if (elapsed >= settings.duration()) {
            end(fog, true);
            return;
        }
        final double difficulty = settings.difficultyAt(elapsed);
        fog.instance.setDelirium(difficulty);
        fog.monsters.removeIf(monster -> !monster.isValid() || monster.isDead());
        // 霧跟著每個人
        for (final Player player : players) {
            final Location at = player.getLocation();
            at.getWorld().spawnParticle(Particle.ASH, at.getX(), at.getY() + 1, at.getZ(), 40, 4, 1.5, 4, 0.02);
            at.getWorld().spawnParticle(Particle.WHITE_ASH, at.getX(), at.getY() + 1.2, at.getZ(), 10, 3, 1, 3, 0.01);
        }
        // 幻霧怪
        if (now >= fog.nextSpawnTick) {
            fog.nextSpawnTick = now + Math.round(settings.spawnSeconds() * 20);
            for (final Player player : players) {
                spawnAround(fog, player, difficulty);
            }
        }
        // 血條：剩幾秒、難度、進度
        if (fog.bar == null) {
            fog.bar = BossBar.bossBar(Component.empty(), 1f, BossBar.Color.WHITE, BossBar.Overlay.PROGRESS);
        }
        fog.bar.progress((float) Math.max(0, Math.min(1, 1 - elapsed / settings.duration())));
        final Set<UUID> seen = new HashSet<>();
        for (final Player player : players) {
            seen.add(player.getUniqueId());
            if (fog.watching.add(player.getUniqueId())) {
                player.showBossBar(fog.bar);
            }
        }
        for (final UUID uuid : Set.copyOf(fog.watching)) {
            if (!seen.contains(uuid)) {
                final Player player = plugin.getServer().getPlayer(uuid);
                if (player != null) {
                    player.hideBossBar(fog.bar);
                }
                fog.watching.remove(uuid);
            }
        }
        // 進度用第一個人的（同一張圖通常是一隊；每個人自己的數字在動作列）
        final StringBuilder text = new StringBuilder("<white>幻霧 <gray>剩 <white>" + Math.round(settings.duration() - elapsed) + "</white> 秒　怪物 <#ff5c6a>+"
                + Math.round(difficulty * 100) + "%");
        if (!players.isEmpty()) {
            final int points = fog.progress.getOrDefault(players.getFirst().getUniqueId(), 0);
            text.append("　<gray>獎勵進度 <white>").append(points).append("</white>（").append(settings.sharesFor(points)).append(" 份");
            if (players.size() > 1) {
                text.append("，各算各的");
            }
            text.append("）");
        }
        fog.bar.name(plugin.text().parse(text.toString()));
    }

    private void start(final Fog fog, final Player who, final long now) {
        final MapEventConfig.Delirium settings = config.delirium();
        final RandomGenerator random = service.random();
        fog.started = true;
        fog.startTick = now;
        fog.nextSpawnTick = now + 40;
        for (int i = 0; i < settings.rewards(); i++) {
            MapEventConfig.Reward kind = MapRewards.pick(settings.rewardWeights(), random);
            // 傳奇一團霧最多一條：又抽到傳奇就重抽（權重只剩傳奇的話這條就不要了）
            for (int retry = 0; kind == MapEventConfig.Reward.UNIQUE && fog.tracks.contains(kind) && retry < 20; retry++) {
                kind = MapRewards.pick(settings.rewardWeights(), random);
            }
            if (kind != null && !(kind == MapEventConfig.Reward.UNIQUE && fog.tracks.contains(kind))) {
                fog.tracks.add(kind);
            }
        }
        final Block base = fog.mirror.getBlock();
        base.setType(Material.GLASS_PANE);
        base.getRelative(0, 1, 0).setType(Material.AIR);
        if (fog.label != null && fog.label.isValid()) {
            fog.label.remove();
            fog.label = null;
        }
        fog.mirror.getWorld().playSound(fog.mirror, Sound.BLOCK_GLASS_BREAK, 1.0f, 0.5f);
        fog.mirror.getWorld().playSound(fog.mirror, Sound.AMBIENT_SOUL_SAND_VALLEY_MOOD, 1.0f, 0.6f);
        fog.mirror.getWorld().spawnParticle(Particle.WHITE_ASH, fog.mirror.getX(), fog.mirror.getY() + 1, fog.mirror.getZ(), 200, 3, 1.5, 3, 0.05);
        final String tracks = String.join("、", fog.tracks.stream().map(MapEventConfig.Reward::displayName).toList());
        for (final Player player : players(fog.instance)) {
            player.showTitle(Title.title(plugin.text().parse("<white>幻霧"),
                    plugin.text().parse("<gray>霧會跟著你 " + Math.round(settings.duration()) + " 秒：越久怪越硬、殺越多獎勵越多（" + ItemRenderer.escape(tracks) + "）"),
                    Title.Times.times(Duration.ofMillis(300), Duration.ofMillis(3000), Duration.ofMillis(800))));
            player.playSound(player.getLocation(), Sound.ENTITY_ELDER_GUARDIAN_CURSE, 0.5f, 1.6f);
        }
        plugin.getSLF4JLogger().info("地圖 #{}：{} 走進幻霧之鏡（獎勵：{}）", fog.instance.id(), who.getName(), tracks);
    }

    /** 在玩家附近 6–10 格刷一小群幻霧怪（難度由 MapSpawner 照實例的幻霧值加上去）。 */
    private void spawnAround(final Fog fog, final Player player, final double difficulty) {
        final MapEventConfig.Delirium settings = config.delirium();
        final MapInstance instance = fog.instance;
        final RandomGenerator random = service.random();
        final ZoneConfig.SpawnEntry entry = service.spawner().chooseMonster(instance.map().monsters(), random);
        if (entry == null) {
            return;
        }
        final int count = settings.minSpawn() + (settings.maxSpawn() > settings.minSpawn() ? random.nextInt(settings.maxSpawn() - settings.minSpawn() + 1) : 0);
        final boolean magic = random.nextDouble() < 0.2 + difficulty * 0.3;
        final List<String> mods = magic ? plugin.monsters().rollMods(MonsterRarity.MAGIC, instance.level()) : List.of();
        for (int i = 0; i < count; i++) {
            Location at = null;
            for (int attempt = 0; attempt < 10; attempt++) {
                final double angle = random.nextDouble() * Math.PI * 2;
                final double distance = 6 + random.nextDouble() * 4;
                final double x = player.getLocation().getX() + Math.cos(angle) * distance;
                final double z = player.getLocation().getZ() + Math.sin(angle) * distance;
                if (instance.layout().isFloor((int) Math.floor(x) - instance.originX(), (int) Math.floor(z) - instance.originZ())) {
                    at = new Location(instance.world(), Math.floor(x) + 0.5, MapLayout.FLOOR_Y + 1, Math.floor(z) + 0.5, random.nextFloat() * 360f, 0f);
                    break;
                }
            }
            if (at == null) {
                return;
            }
            final LivingEntity monster = service.spawner().spawn(instance, entry, at, magic ? MonsterRarity.MAGIC : MonsterRarity.NORMAL, mods);
            if (monster == null) {
                continue;
            }
            monster.getPersistentDataContainer().set(deliriumKey, PersistentDataType.BYTE, (byte) 1);
            plugin.monsters().data(monster).ifPresent(data -> plugin.monsters().decorateData(monster,
                    new MonsterData(data.level(), data.rarity(), data.mods(), "幻霧・" + data.name(), data.lifeMultiplier(), data.damageMultiplier(), false)));
            fog.monsters.add(monster);
            monster.getWorld().spawnParticle(Particle.WHITE_ASH, monster.getLocation().add(0, 1, 0), 25, 0.4, 0.6, 0.4, 0.03);
            if (monster instanceof Mob mob) {
                mob.setTarget(player);
            }
        }
    }

    /** 幻霧結束：照進度掉獎勵（timeUp = 時間到；false 是出錯或關圖，不掉）。 */
    private void end(final Fog fog, final boolean timeUp) {
        final MapEventConfig.Delirium settings = config.delirium();
        fog.done = true;
        fog.instance.setDelirium(0);
        if (fog.bar != null) {
            for (final UUID uuid : fog.watching) {
                final Player player = plugin.getServer().getPlayer(uuid);
                if (player != null) {
                    player.hideBossBar(fog.bar);
                }
            }
            fog.watching.clear();
        }
        if (!timeUp) {
            return;
        }
        final RandomGenerator random = service.random();
        for (final Player player : players(fog.instance)) {
            if (timeUp && plugin.events() != null) {
                plugin.events().onMapEvent(player, fog.instance.level()); // 每月挑戰：幻霧結束算一個地圖事件
            }
            final int points = fog.progress.getOrDefault(player.getUniqueId(), 0);
            final int shares = settings.sharesFor(points);
            if (shares <= 0) {
                player.sendMessage(plugin.text().prefixed("<gray>幻霧散了：進度 " + points + "，還不到第一份（" + settings.thresholds().getFirst() + "）"));
                continue;
            }
            final List<ItemStackHolder> got = new ArrayList<>();
            final List<DropTable.Drop> none = List.of();
            for (final MapEventConfig.Reward kind : fog.tracks) {
                // 傳奇那一條固定一件（份數只加其他種類的數量）
                final int times = kind == MapEventConfig.Reward.UNIQUE ? 1 : shares;
                for (int i = 0; i < times; i++) {
                    final MapRewards.Bundle bundle = MapRewards.roll(plugin, service, kind, fog.instance, player, settings, MapEventConfig.DELIRIUM_SOURCE, random);
                    if (bundle != null) {
                        got.add(new ItemStackHolder(bundle));
                    }
                }
            }
            List<org.bukkit.inventory.ItemStack> items = new ArrayList<>();
            for (final ItemStackHolder holder : got) {
                items.addAll(holder.bundle.items());
            }
            if (plugin.loot() != null) {
                items = plugin.loot().filterRewards(player, items, fog.instance.level()); // 2026-10：也照掉落過濾
            }
            final boolean dropped = MapRewards.give(plugin, player, items);
            player.sendMessage(plugin.text().prefixed(String.format(Locale.ROOT, "<white>幻霧散了：進度 %d，每條獎勵 %d 份%s，拿到 %d 件%s",
                    points, shares, fog.tracks.contains(MapEventConfig.Reward.UNIQUE) && shares > 1 ? "<gray>（傳奇固定一件）<white>" : "",
                    items.size(), dropped ? "<yellow>（背包滿了，有的掉在腳下）" : "")));
            player.playSound(player.getLocation(), Sound.ENTITY_PLAYER_LEVELUP, 0.7f, 1.2f);
            MapRewards.deliver(plugin, player, player.getLocation(), new MonsterData(fog.instance.level(), MonsterRarity.RARE, List.of(), "幻霧"), none, 0);
            if (service.events() != null) {
                service.events().rollTicket(MapEventConfig.DELIRIUM_SOURCE, player, player.getLocation()); // 2026-10-18：入場券（拿到獎勵的人）
            }
        }
        plugin.getSLF4JLogger().info("地圖 #{}：幻霧結束", fog.instance.id());
    }

    /** 包一下，免得泛型清單太長。 */
    private record ItemStackHolder(MapRewards.Bundle bundle) {
    }

    /** 霧裡殺的怪：有份的人各加進度。 */
    @EventHandler(priority = EventPriority.MONITOR)
    public void onDeath(final EntityDeathEvent event) {
        final LivingEntity entity = event.getEntity();
        final MapInstance instance = service.instanceOf(entity);
        if (instance == null) {
            return;
        }
        final Fog fog = byInstance.get(instance.id());
        if (fog == null || !fog.started || fog.done) {
            return;
        }
        final MonsterRarity rarity = plugin.monsters().data(entity).map(MonsterData::rarity).orElse(MonsterRarity.NORMAL);
        final int base = config.delirium().progress().getOrDefault(rarity, 0);
        if (base <= 0) {
            return;
        }
        // 星圖天賦：幻霧的獎勵進度 +x%（小數的部分照機率進位：一般怪 1 點 +25% = 四隻多算一點）
        final double scaled = base * (1 + instance.atlas().increased("atlas.delirium.progress"));
        int points = (int) Math.floor(scaled);
        if (service.random().nextDouble() < scaled - points) {
            points++;
        }
        for (final Player player : plugin.monsterModule().credit().sharers(entity, entity.getKiller())) {
            final int total = fog.progress.merge(player.getUniqueId(), points, Integer::sum);
            final int shares = config.delirium().sharesFor(total);
            final List<Integer> thresholds = config.delirium().thresholds();
            final String next = shares < thresholds.size() ? "，下一份 " + thresholds.get(shares) : "，滿了";
            tw.exilecore.gui.ActionBar.send(player, plugin.text().parse("<white>幻霧進度 +" + points + " <gray>（" + total + "，" + shares + " 份" + next + "）"));
        }
    }

    // ---- 標籤與清理 ----

    private TextDisplay label(final Fog fog) {
        final Location at = fog.mirror.clone().add(0, 2.6, 0);
        final TextDisplay display = at.getWorld().spawn(at, TextDisplay.class, created -> {
            created.setBillboard(Display.Billboard.CENTER);
            created.setPersistent(false);
            created.setSeeThrough(false);
            created.setShadowed(true);
        });
        display.text(plugin.text().parse("<white>幻霧之鏡<newline><gray>走過去：霧跟著你 " + Math.round(config.delirium().duration())
                + " 秒，越久怪越硬、殺越多獎勵越多<newline><dark_gray>蒸餾情緒（注入項鍊）主要從這裡來"));
        return display;
    }

    private void dispose(final Fog fog) {
        fog.instance.setDelirium(0);
        if (fog.label != null && fog.label.isValid()) {
            fog.label.remove();
        }
        fog.label = null;
        for (final LivingEntity monster : fog.monsters) {
            if (monster.isValid()) {
                monster.remove();
            }
        }
        fog.monsters.clear();
        if (fog.bar != null) {
            for (final UUID uuid : fog.watching) {
                final Player player = plugin.getServer().getPlayer(uuid);
                if (player != null) {
                    player.hideBossBar(fog.bar);
                }
            }
            fog.watching.clear();
        }
    }

    void clear(final MapInstance instance) {
        final Fog fog = byInstance.remove(instance.id());
        if (fog != null) {
            dispose(fog);
        }
    }

    void clearAll() {
        for (final Fog fog : byInstance.values()) {
            dispose(fog);
        }
        byInstance.clear();
    }

    /** /ec map 的狀態。 */
    String summary(final MapInstance instance) {
        final Fog fog = byInstance.get(instance.id());
        if (fog == null) {
            return null;
        }
        return "幻霧" + (fog.done ? "（結束）" : fog.started ? "（進行中）" : "（鏡子還沒碰）");
    }

    boolean isDeliriumMonster(final Entity entity) {
        return entity.getPersistentDataContainer().has(deliriumKey);
    }
}
