package tw.exilecore.map;

import net.kyori.adventure.bossbar.BossBar;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.title.Title;
import org.bukkit.Color;
import org.bukkit.GameMode;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.NamespacedKey;
import org.bukkit.Particle;
import org.bukkit.Sound;
import org.bukkit.block.Block;
import org.bukkit.block.data.type.Candle;
import org.bukkit.entity.Display;
import org.bukkit.entity.Entity;
import org.bukkit.entity.LivingEntity;
import org.bukkit.entity.Mob;
import org.bukkit.entity.Player;
import org.bukkit.entity.TextDisplay;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.block.Action;
import org.bukkit.event.entity.EntityDeathEvent;
import org.bukkit.event.player.PlayerInteractEvent;
import org.bukkit.inventory.EquipmentSlot;
import org.bukkit.persistence.PersistentDataType;
import tw.exilecore.ExileCore;
import tw.exilecore.monster.MonsterData;
import tw.exilecore.monster.MonsterRarity;
import tw.exilecore.skill.GroundDecals;
import tw.exilecore.zone.ZoneConfig;

import java.time.Duration;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.random.RandomGenerator;

/**
 * 祭壇（PoE2 的 Ritual）：地圖裡幾座祭壇，各在不同房間。右鍵一座開始儀式——地上一個血色的圓，怪一波一波在圓裡出現，
 * 圓裡的人每殺一隻拿貢品（後面的祭壇給更多）。全部做完後右鍵任何一座祭壇，用貢品換獎勵（{@link RitualMenu}；每個人各自一組，可以重擲）。
 * 貢品只在這張圖有效。設定在 {@code map_events.yml} 的 {@code ritual}。
 */
final class RitualEvents implements Listener {

    private static final Particle.DustOptions BLOOD = new Particle.DustOptions(Color.fromRGB(0xC0303A), 1.2f);
    private static final Particle.DustOptions BLOOD_DARK = new Particle.DustOptions(Color.fromRGB(0x6A1018), 1.6f);
    /** 碰得到祭壇：離中心幾格內右鍵（蠟燭或底座）。 */
    private static final double SHARE_MARGIN = 4;

    /** 一座祭壇。 */
    final class Altar {
        final int index;
        final Location center;
        boolean started;
        boolean done;
        int wave;
        long startTick;
        long waveStartTick;
        final List<LivingEntity> monsters = new ArrayList<>();
        TextDisplay label;

        Altar(final int index, final Location center) {
            this.index = index;
            this.center = center;
        }
    }

    /** 一份獎勵與價格；買走之後 bundle 為 null。 */
    static final class Offer {
        MapRewards.Bundle bundle;
        final int price;

        Offer(final MapRewards.Bundle bundle, final int price) {
            this.bundle = bundle;
            this.price = price;
        }
    }

    /** 一張圖的祭壇。 */
    final class Ritual {
        final MapInstance instance;
        final List<Altar> altars = new ArrayList<>();
        final int waves;
        Altar active;
        final Map<UUID, Integer> tribute = new HashMap<>();
        final Map<UUID, Integer> earned = new HashMap<>();
        final Map<UUID, Integer> rerolls = new HashMap<>();
        final Map<UUID, List<Offer>> offers = new HashMap<>();
        BossBar bar;
        final Set<UUID> watching = new HashSet<>();

        Ritual(final MapInstance instance, final int waves) {
            this.instance = instance;
            this.waves = waves;
        }

        int done() {
            int count = 0;
            for (final Altar altar : altars) {
                if (altar.done) {
                    count++;
                }
            }
            return count;
        }

        boolean allDone() {
            return done() >= altars.size();
        }

        int tributeOf(final Player player) {
            return tribute.getOrDefault(player.getUniqueId(), 0);
        }
    }

    private final ExileCore plugin;
    private final MapService service;
    private final MapEventConfig config;
    private final Map<Integer, Ritual> byInstance = new HashMap<>();
    /** 祭壇的方塊（底座與蠟燭）→ 祭壇。 */
    private final Map<Long, Altar> blocks = new HashMap<>();
    private final Map<Altar, Ritual> owners = new HashMap<>();
    /** 儀式的怪：實例編號。 */
    private final NamespacedKey ritualKey;

    RitualEvents(final ExileCore plugin, final MapService service, final MapEventConfig config) {
        this.plugin = plugin;
        this.service = service;
        this.config = config;
        this.ritualKey = new NamespacedKey(plugin, "ritual_instance");
    }

    MapEventConfig.Ritual settings() {
        return config.ritual();
    }

    // ---- 放置 ----

    /** 地圖蓋好：照機率放幾座祭壇（從 rooms 裡拿房間，拿走的不會再給別的事件）。 */
    void place(final MapInstance instance, final List<MapLayout.Room> rooms) {
        final MapEventConfig.Ritual ritual = config.ritual();
        final RandomGenerator random = service.random();
        final double monthly = plugin.events() == null ? -1 : plugin.events().ritualChance();
        // 每月活動可以改這個月的機率；星圖天賦再增加 x%
        final double chance = Math.min(1.0, (monthly >= 0 ? monthly : ritual.chance()) * (1 + instance.atlas().increased("atlas.ritual.chance")));
        if (instance.special() != null || chance <= 0 || random.nextDouble() >= chance || rooms.isEmpty()) {
            return;
        }
        final int waves = ritual.minWaves() + (ritual.maxWaves() > ritual.minWaves() ? random.nextInt(ritual.maxWaves() - ritual.minWaves() + 1) : 0);
        final Ritual state = new Ritual(instance, waves);
        for (int i = 0; i < ritual.altars() && !rooms.isEmpty(); i++) {
            final MapLayout.Room room = rooms.remove(random.nextInt(rooms.size()));
            final MapLayout.Spot spot = MapEvents.spotIn(instance.layout(), room);
            if (spot == null) {
                i--;
                continue;
            }
            final Location center = new Location(instance.world(), instance.originX() + spot.x() + 0.5, MapLayout.FLOOR_Y + 1,
                    instance.originZ() + spot.z() + 0.5);
            final Altar altar = new Altar(state.altars.size(), center);
            build(altar, true);
            state.altars.add(altar);
            owners.put(altar, state);
        }
        if (state.altars.isEmpty()) {
            return;
        }
        byInstance.put(instance.id(), state);
    }

    /** 底座（哭泣黑曜石）＋上面三根紅蠟燭（點著＝還沒做）。 */
    private void build(final Altar altar, final boolean lit) {
        final Block base = altar.center.getBlock();
        base.setType(Material.CRYING_OBSIDIAN);
        final Block top = base.getRelative(0, 1, 0);
        top.setType(Material.RED_CANDLE);
        if (top.getBlockData() instanceof Candle candle) {
            candle.setCandles(3);
            candle.setLit(lit);
            top.setBlockData(candle);
        }
        blocks.put(MapEvents.key(base), altar);
        blocks.put(MapEvents.key(top), altar);
    }

    private void extinguish(final Altar altar) {
        final Block top = altar.center.getBlock().getRelative(0, 1, 0);
        if (top.getBlockData() instanceof Candle candle) {
            candle.setLit(false);
            top.setBlockData(candle);
        }
    }

    // ---- 每 10 tick ----

    void tick(final long now) {
        if (byInstance.isEmpty()) {
            return;
        }
        final Iterator<Map.Entry<Integer, Ritual>> iterator = byInstance.entrySet().iterator();
        while (iterator.hasNext()) {
            final Map.Entry<Integer, Ritual> entry = iterator.next();
            final Ritual ritual = entry.getValue();
            final MapInstance instance = service.instance(entry.getKey());
            if (instance == null || instance.state() == MapInstance.State.CLOSING) {
                dispose(ritual);
                iterator.remove();
                continue;
            }
            if (instance.state() != MapInstance.State.OPEN) {
                continue;
            }
            try {
                tickRitual(ritual, now);
            } catch (final RuntimeException e) {
                plugin.getSLF4JLogger().error("地圖 #{} 的祭壇出錯", instance.id(), e);
                if (ritual.active != null) {
                    finish(ritual, ritual.active, true);
                }
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

    private void tickRitual(final Ritual ritual, final long now) {
        final MapEventConfig.Ritual settings = config.ritual();
        for (final Altar altar : ritual.altars) {
            final Location center = altar.center;
            if (!center.getWorld().isChunkLoaded(center.getBlockX() >> 4, center.getBlockZ() >> 4)) {
                continue;
            }
            if (altar.label == null || !altar.label.isValid()) {
                altar.label = label(ritual, altar);
            }
            if (!altar.started) {
                center.getWorld().spawnParticle(Particle.DUST, center.getX(), center.getY() + 1.6, center.getZ(), 3, 0.25, 0.3, 0.25, 0, BLOOD);
            }
        }
        final Altar active = ritual.active;
        if (active == null) {
            return;
        }
        final double radius = settings.radius();
        ring(active.center, radius, (int) (radius * 5), now);
        active.monsters.removeIf(monster -> !monster.isValid() || monster.isDead());
        final boolean waveOver = active.monsters.isEmpty() || now - active.waveStartTick >= Math.round(settings.waveSeconds() * 20);
        if (now - active.startTick >= Math.round(settings.timeout() * 20)) {
            finish(ritual, active, true);
            return;
        }
        if (waveOver) {
            if (active.wave < ritual.waves) {
                spawnWave(ritual, active, now);
            } else if (active.monsters.isEmpty()) {
                finish(ritual, active, false);
                return;
            }
        }
        // 血條：第幾座、第幾波、剩幾隻
        if (ritual.bar == null) {
            ritual.bar = BossBar.bossBar(Component.empty(), 1f, BossBar.Color.RED, BossBar.Overlay.NOTCHED_6);
        }
        ritual.bar.name(plugin.text().parse("<#ff5c6a>祭壇 " + (active.index + 1) + "/" + ritual.altars.size() + "　<white>第 " + active.wave + "/" + ritual.waves
                + " 波　<gray>剩 <white>" + active.monsters.size() + "</white> 隻　<dark_gray>圓裡殺怪拿貢品"));
        ritual.bar.progress((float) Math.max(0, Math.min(1, 1 - (double) (active.wave - 1) / ritual.waves)));
        final List<Player> players = players(ritual.instance);
        final Set<UUID> seen = new HashSet<>();
        for (final Player player : players) {
            seen.add(player.getUniqueId());
            if (ritual.watching.add(player.getUniqueId())) {
                player.showBossBar(ritual.bar);
            }
        }
        for (final UUID uuid : Set.copyOf(ritual.watching)) {
            if (!seen.contains(uuid)) {
                final Player player = plugin.getServer().getPlayer(uuid);
                if (player != null) {
                    player.hideBossBar(ritual.bar);
                }
                ritual.watching.remove(uuid);
            }
        }
    }

    /** 地上一圈血色的點（慢慢轉；套了資源包的人看平面的圈，粒子只送給沒套的人）。 */
    private void ring(final Location center, final double radius, final int points, final long now) {
        final GroundDecals decals = plugin.skills() == null ? null : plugin.skills().decals();
        final List<Player> viewers;
        if (decals != null) {
            decals.ring(decalId(center), center, radius, 0xC0303A);
            viewers = decals.viewers(center);
            if (viewers.isEmpty()) {
                return;
            }
        } else {
            viewers = center.getWorld().getPlayers();
        }
        final double spin = (now % 200) / 200.0 * Math.PI * 2;
        for (int i = 0; i < points; i++) {
            final double angle = Math.PI * 2 * i / points + spin;
            GroundDecals.particle(viewers, Particle.DUST, new Location(center.getWorld(), center.getX() + Math.cos(angle) * radius, center.getY() + 0.3 + (i % 2) * 0.5,
                    center.getZ() + Math.sin(angle) * radius), 1, 0, 0, 0, 0, i % 3 == 0 ? BLOOD_DARK : BLOOD);
        }
    }

    private static String decalId(final Location center) {
        return "ritual:" + center.getWorld().getName() + ":" + center.getBlockX() + ":" + center.getBlockY() + ":" + center.getBlockZ();
    }

    // ---- 開始與波 ----

    /**
     * 右鍵祭壇：開始儀式；全部做完後是換獎勵。
     * 跟寶箱一樣不能 ignoreCancelled（世界保護把地圖世界的方塊互動標成拒絕），接走之後叫技能模組這一下不施放。
     */
    @EventHandler(priority = EventPriority.LOWEST)
    public void onInteract(final PlayerInteractEvent event) {
        if (event.getAction() != Action.RIGHT_CLICK_BLOCK || event.getClickedBlock() == null || event.getHand() != EquipmentSlot.HAND) {
            return;
        }
        final Altar altar = blocks.get(MapEvents.key(event.getClickedBlock()));
        if (altar == null || !MapEvents.withinReach(event.getPlayer(), event.getClickedBlock())) {
            return; // 太遠的當成一般的右鍵（施放技能）
        }
        event.setCancelled(true);
        event.setUseItemInHand(org.bukkit.event.Event.Result.DENY);
        final Ritual ritual = owners.get(altar);
        final Player player = event.getPlayer();
        if (plugin.skills() != null) {
            plugin.skills().consumeRightClick(player);
        }
        if (ritual == null || ritual.instance.state() != MapInstance.State.OPEN) {
            return;
        }
        if (ritual.allDone()) {
            new RitualMenu(plugin, this, ritual, player).open();
            return;
        }
        if (altar.done) {
            player.sendActionBar(plugin.text().parse("<gray>這座祭壇做完了，去找還點著蠟燭的那座（" + ritual.done() + "/" + ritual.altars.size() + "）"));
            return;
        }
        if (ritual.active != null) {
            player.sendActionBar(plugin.text().parse("<red>先完成進行中的儀式（祭壇 " + (ritual.active.index + 1) + "）"));
            return;
        }
        altar.started = true;
        altar.startTick = plugin.getServer().getCurrentTick();
        altar.wave = 0;
        ritual.active = altar;
        extinguish(altar);
        final Location center = altar.center;
        center.getWorld().playSound(center, Sound.BLOCK_RESPAWN_ANCHOR_SET_SPAWN, 1.0f, 0.5f);
        center.getWorld().playSound(center, Sound.ENTITY_WITHER_AMBIENT, 0.6f, 0.5f);
        center.getWorld().spawnParticle(Particle.DUST, center.getX(), center.getY() + 1, center.getZ(), 60, 2.5, 0.8, 2.5, 0, BLOOD);
        for (final Player near : players(ritual.instance)) {
            if (near.getLocation().distanceSquared(center) <= 40 * 40) {
                near.showTitle(Title.title(plugin.text().parse("<#ff5c6a>儀式開始"),
                        plugin.text().parse("<gray>待在圓裡殺怪拿貢品（祭壇 " + (altar.index + 1) + "/" + ritual.altars.size() + "）"),
                        Title.Times.times(Duration.ofMillis(200), Duration.ofMillis(1800), Duration.ofMillis(500))));
            }
        }
        spawnWave(ritual, altar, altar.startTick);
    }

    /** 下一波：在圓裡刷怪（照這張圖的怪物與魔法／稀有群的比例）。 */
    private void spawnWave(final Ritual ritual, final Altar altar, final long now) {
        final MapEventConfig.Ritual settings = config.ritual();
        final MapInstance instance = ritual.instance;
        final RandomGenerator random = service.random();
        altar.wave++;
        altar.waveStartTick = now;
        final ZoneConfig.SpawnEntry entry = service.spawner().chooseMonster(instance.map().monsters(), random);
        if (entry == null) {
            return;
        }
        final int count = settings.minWave() + (settings.maxWave() > settings.minWave() ? random.nextInt(settings.maxWave() - settings.minWave() + 1) : 0);
        final MapStats stats = instance.stats();
        final MapConfig.Settings packs = service.config().settings();
        final double normal = packs.normal().weight();
        final double magic = packs.magic().weight() * (1 + stats.magicPacks() / 100.0);
        final double rare = packs.rare().weight() * (1 + stats.rarePacks() / 100.0);
        final double roll = random.nextDouble() * (normal + magic + rare);
        final List<String> magicMods = plugin.monsters().rollMods(MonsterRarity.MAGIC, instance.level());
        final List<Player> players = players(instance);
        for (int i = 0; i < count; i++) {
            final MonsterRarity rarity = roll >= normal + magic && i == 0 ? MonsterRarity.RARE
                    : roll >= normal && roll < normal + magic ? MonsterRarity.MAGIC : MonsterRarity.NORMAL;
            final Location at = inCircle(instance, altar.center, settings.radius(), random);
            final LivingEntity monster = service.spawner().spawn(instance, entry, at, rarity,
                    rarity == MonsterRarity.MAGIC ? magicMods : rarity == MonsterRarity.RARE ? null : List.of());
            if (monster == null) {
                continue;
            }
            monster.getPersistentDataContainer().set(ritualKey, PersistentDataType.INTEGER, instance.id());
            altar.monsters.add(monster);
            monster.getWorld().spawnParticle(Particle.DUST, monster.getLocation().add(0, 1, 0), 20, 0.4, 0.6, 0.4, 0, BLOOD);
            if (monster instanceof Mob mob && !players.isEmpty()) {
                Player nearest = null;
                double best = Double.MAX_VALUE;
                for (final Player player : players) {
                    final double distance = player.getLocation().distanceSquared(at);
                    if (distance < best) {
                        best = distance;
                        nearest = player;
                    }
                }
                mob.setTarget(nearest);
            }
        }
        altar.center.getWorld().playSound(altar.center, Sound.ENTITY_ZOMBIE_VILLAGER_CONVERTED, 1.0f, 0.5f);
    }

    /** 圓裡在地板上的位置（離中心 2 格到半徑減 1 格）。 */
    private static Location inCircle(final MapInstance instance, final Location center, final double radius, final RandomGenerator random) {
        for (int attempt = 0; attempt < 16; attempt++) {
            final double angle = random.nextDouble() * Math.PI * 2;
            final double distance = 2 + random.nextDouble() * Math.max(1, radius - 3);
            final double x = center.getX() + Math.cos(angle) * distance;
            final double z = center.getZ() + Math.sin(angle) * distance;
            if (instance.layout().isFloor((int) Math.floor(x) - instance.originX(), (int) Math.floor(z) - instance.originZ())) {
                return new Location(instance.world(), Math.floor(x) + 0.5, MapLayout.FLOOR_Y + 1, Math.floor(z) + 0.5, random.nextFloat() * 360f, 0f);
            }
        }
        return center.clone().add(2, 0, 0);
    }

    /** 一座祭壇完成（timedOut：時間到，剩下的怪消失）。 */
    private void finish(final Ritual ritual, final Altar altar, final boolean timedOut) {
        altar.done = true;
        if (plugin.skills() != null) {
            plugin.skills().decals().remove(decalId(altar.center));
        }
        if (ritual.active == altar) {
            ritual.active = null;
        }
        for (final LivingEntity monster : altar.monsters) {
            if (monster.isValid()) {
                monster.remove();
            }
        }
        altar.monsters.clear();
        final Location center = altar.center;
        center.getWorld().playSound(center, Sound.BLOCK_BEACON_DEACTIVATE, 1.0f, 0.7f);
        center.getWorld().spawnParticle(Particle.SOUL, center.getX(), center.getY() + 1, center.getZ(), 30, 0.8, 0.8, 0.8, 0.03);
        final boolean all = ritual.allDone();
        for (final Player player : players(ritual.instance)) {
            final int tribute = ritual.tributeOf(player);
            if (all && plugin.events() != null) {
                plugin.events().onMapEvent(player, ritual.instance.level()); // 每月挑戰：做完全部祭壇算一個地圖事件
            }
            if (all) {
                player.showTitle(Title.title(plugin.text().parse("<#ff5c6a>儀式全部完成"),
                        plugin.text().parse("<gray>右鍵任何一座祭壇換獎勵（你有 <white>" + tribute + "</white> 貢品）"),
                        Title.Times.times(Duration.ofMillis(200), Duration.ofMillis(2500), Duration.ofMillis(600))));
                player.playSound(player.getLocation(), Sound.UI_TOAST_CHALLENGE_COMPLETE, 0.8f, 1.1f);
            } else {
                player.sendMessage(plugin.text().prefixed("<#ff5c6a>祭壇 " + ritual.done() + "/" + ritual.altars.size() + " 完成"
                        + (timedOut ? "<gray>（時間到）" : "") + "<gray>，你有 <white>" + tribute + "</white> 貢品；下一座的貢品更多"));
            }
        }
        // 2026-10-18：每做完一座都收血條（以前只有最後一座才收，中間那幾座的血條一直掛著；下一座開始會再顯示）
        if (ritual.bar != null) {
            for (final UUID uuid : ritual.watching) {
                final Player player = plugin.getServer().getPlayer(uuid);
                if (player != null) {
                    player.hideBossBar(ritual.bar);
                }
            }
            ritual.watching.clear();
        }
        for (final Altar other : ritual.altars) {
            if (other.label != null && other.label.isValid()) {
                other.label.text(labelText(ritual, other));
            }
        }
        plugin.getSLF4JLogger().info("地圖 #{}：祭壇 {}/{} 完成", ritual.instance.id(), ritual.done(), ritual.altars.size());
    }

    // ---- 貢品 ----

    /** 儀式的怪死了：圓裡（加一點邊）的每個人拿貢品。 */
    @EventHandler(priority = EventPriority.MONITOR)
    public void onDeath(final EntityDeathEvent event) {
        final LivingEntity entity = event.getEntity();
        final Integer instanceId = entity.getPersistentDataContainer().get(ritualKey, PersistentDataType.INTEGER);
        if (instanceId == null) {
            return;
        }
        final Ritual ritual = byInstance.get(instanceId);
        if (ritual == null || ritual.active == null) {
            return;
        }
        final Altar altar = ritual.active;
        final MonsterRarity rarity = plugin.monsters().data(entity).map(MonsterData::rarity).orElse(MonsterRarity.NORMAL);
        final int base = config.ritual().tributeFor(rarity, altar.index);
        if (base <= 0) {
            return;
        }
        // 星圖天賦：貢品 +x%
        final int amount = (int) Math.round(base * (1 + ritual.instance.atlas().increased("atlas.ritual.tribute")));
        final double reach = config.ritual().radius() + SHARE_MARGIN;
        for (final Player player : players(ritual.instance)) {
            if (Math.hypot(player.getLocation().getX() - altar.center.getX(), player.getLocation().getZ() - altar.center.getZ()) > reach) {
                continue;
            }
            final int total = ritual.tribute.merge(player.getUniqueId(), amount, Integer::sum);
            ritual.earned.merge(player.getUniqueId(), amount, Integer::sum);
            player.sendActionBar(plugin.text().parse("<#ff5c6a>貢品 +" + amount + " <gray>（共 " + total + "）"));
        }
    }

    // ---- 獎勵 ----

    /** 這個人的那組獎勵（第一次看時做出來）。 */
    List<Offer> offers(final Ritual ritual, final Player player) {
        return ritual.offers.computeIfAbsent(player.getUniqueId(), uuid -> generate(ritual, player));
    }

    private List<Offer> generate(final Ritual ritual, final Player player) {
        final MapEventConfig.Ritual settings = config.ritual();
        final RandomGenerator random = service.random();
        final List<Offer> list = new ArrayList<>();
        for (int i = 0; i < settings.rewards(); i++) {
            final MapEventConfig.Reward kind = MapRewards.pick(settings.rewardWeights(), random);
            if (kind == null) {
                break;
            }
            final MapRewards.Bundle bundle = MapRewards.roll(plugin, service, kind, ritual.instance, player, config.delirium(), MapEventConfig.RITUAL_SOURCE, random);
            if (bundle != null) {
                list.add(new Offer(bundle, MapRewards.price(settings, bundle.kind(), random)));
            }
        }
        return list;
    }

    /** 重擲這個人的那組要幾貢品（總共拿到的 × cost × growth^次數）。 */
    int rerollPrice(final Ritual ritual, final Player player) {
        final MapEventConfig.Ritual settings = config.ritual();
        final int earned = ritual.earned.getOrDefault(player.getUniqueId(), 0);
        final int times = ritual.rerolls.getOrDefault(player.getUniqueId(), 0);
        return Math.max(1, (int) Math.round(earned * settings.rerollCost() * Math.pow(settings.rerollGrowth(), times)));
    }

    /** 買第 index 件；null = 成功，不然是原因。 */
    String buy(final Ritual ritual, final Player player, final int index) {
        final List<Offer> list = offers(ritual, player);
        if (index < 0 || index >= list.size() || list.get(index).bundle == null) {
            return "這一格已經買走了";
        }
        final Offer offer = list.get(index);
        final int tribute = ritual.tributeOf(player);
        if (tribute < offer.price) {
            return "貢品不夠：要 " + offer.price + "，你有 " + tribute;
        }
        ritual.tribute.put(player.getUniqueId(), tribute - offer.price);
        final boolean dropped = MapRewards.give(plugin, player, offer.bundle.items());
        plugin.getSLF4JLogger().info("[ritual] {} 在地圖 #{} 用 {} 貢品換了 {}", player.getName(), ritual.instance.id(), offer.price, offer.bundle.kind());
        offer.bundle = null;
        if (dropped) {
            player.sendMessage(plugin.text().prefixed("<yellow>背包滿了，換到的東西掉在腳下"));
        }
        return null;
    }

    /** 重擲整組；null = 成功。 */
    String reroll(final Ritual ritual, final Player player) {
        final int price = rerollPrice(ritual, player);
        final int tribute = ritual.tributeOf(player);
        if (tribute < price) {
            return "貢品不夠：重擲要 " + price + "，你有 " + tribute;
        }
        ritual.tribute.put(player.getUniqueId(), tribute - price);
        ritual.rerolls.merge(player.getUniqueId(), 1, Integer::sum);
        ritual.offers.put(player.getUniqueId(), generate(ritual, player));
        return null;
    }

    // ---- 標籤與清理 ----

    private TextDisplay label(final Ritual ritual, final Altar altar) {
        final Location at = altar.center.clone().add(0, 2.2, 0);
        final TextDisplay display = at.getWorld().spawn(at, TextDisplay.class, created -> {
            created.setBillboard(Display.Billboard.CENTER);
            created.setPersistent(false);
            created.setSeeThrough(false);
            created.setShadowed(true);
        });
        display.text(labelText(ritual, altar));
        return display;
    }

    private Component labelText(final Ritual ritual, final Altar altar) {
        if (ritual.allDone()) {
            return plugin.text().parse("<#ff5c6a>祭壇<newline><yellow>右鍵：用貢品換獎勵");
        }
        if (altar.done) {
            return plugin.text().parse("<dark_gray>祭壇 " + (altar.index + 1) + "/" + ritual.altars.size() + "（已完成）");
        }
        return plugin.text().parse("<#ff5c6a>祭壇 " + (altar.index + 1) + "/" + ritual.altars.size()
                + "<newline><gray>右鍵開始儀式：圓裡會一波一波出現怪，殺了拿貢品<newline><dark_gray>全部做完後用貢品換獎勵（星籤、傳奇…）");
    }

    private void dispose(final Ritual ritual) {
        for (final Altar altar : ritual.altars) {
            if (altar.label != null && altar.label.isValid()) {
                altar.label.remove();
            }
            altar.label = null;
            for (final LivingEntity monster : altar.monsters) {
                if (monster.isValid()) {
                    monster.remove();
                }
            }
            altar.monsters.clear();
            blocks.values().removeIf(other -> other == altar);
            owners.remove(altar);
        }
        if (ritual.bar != null) {
            for (final UUID uuid : ritual.watching) {
                final Player player = plugin.getServer().getPlayer(uuid);
                if (player != null) {
                    player.hideBossBar(ritual.bar);
                }
            }
            ritual.watching.clear();
        }
    }

    void clear(final MapInstance instance) {
        final Ritual ritual = byInstance.remove(instance.id());
        if (ritual != null) {
            dispose(ritual);
        }
    }

    void clearAll() {
        for (final Ritual ritual : byInstance.values()) {
            dispose(ritual);
        }
        byInstance.clear();
        blocks.clear();
        owners.clear();
    }

    /** /ec map 的狀態。 */
    String summary(final MapInstance instance) {
        final Ritual ritual = byInstance.get(instance.id());
        if (ritual == null) {
            return null;
        }
        return "祭壇 " + ritual.done() + "/" + ritual.altars.size() + (ritual.active != null ? "（進行中）" : ritual.allDone() ? "（換獎勵）" : "");
    }

    boolean isRitualMonster(final Entity entity) {
        return entity.getPersistentDataContainer().has(ritualKey);
    }

    /** 這個人在這張圖的貢品（側欄、指令用）；沒有祭壇回傳 -1。 */
    int tribute(final MapInstance instance, final Player player) {
        final Ritual ritual = byInstance.get(instance.id());
        return ritual == null ? -1 : ritual.tributeOf(player);
    }

    /** 給 RitualMenu：這個人看獎勵時祭壇還在、全部做完了嗎。 */
    boolean canShop(final Ritual ritual, final Player player) {
        return byInstance.get(ritual.instance.id()) == ritual && ritual.allDone() && ritual.instance.state() == MapInstance.State.OPEN
                && ritual.instance.contains(player.getLocation());
    }

    String formatRarity(final MapEventConfig.Reward kind) {
        return switch (kind) {
            case OMEN, EMOTION -> "<#c48bff>";
            case UNIQUE -> "<#af6025>";
            case RARE, JEWEL -> "<#ffff77>";
            case MAP -> "<#7fd4ff>";
            default -> "<white>";
        };
    }

}
