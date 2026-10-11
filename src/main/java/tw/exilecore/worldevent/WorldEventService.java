package tw.exilecore.worldevent;

import net.kyori.adventure.bossbar.BossBar;
import net.kyori.adventure.title.Title;
import org.bukkit.Location;
import org.bukkit.NamespacedKey;
import org.bukkit.Sound;
import org.bukkit.World;
import org.bukkit.attribute.Attribute;
import org.bukkit.attribute.AttributeInstance;
import org.bukkit.entity.Ageable;
import org.bukkit.entity.Entity;
import org.bukkit.entity.EntityType;
import org.bukkit.entity.LivingEntity;
import org.bukkit.entity.Mob;
import org.bukkit.entity.Player;
import org.bukkit.event.entity.CreatureSpawnEvent;
import org.bukkit.inventory.ItemStack;
import org.bukkit.persistence.PersistentDataType;
import tw.exilecore.ExileCore;
import tw.exilecore.bounty.BountyConfig;
import tw.exilecore.bounty.BountyService;
import tw.exilecore.character.PlayerState;
import tw.exilecore.currency.CurrencyType;
import tw.exilecore.item.ItemData;
import tw.exilecore.item.ItemRenderer;
import tw.exilecore.item.UniqueItem;
import tw.exilecore.monster.MonsterRarity;
import tw.exilecore.zone.Zone;
import tw.exilecore.zone.ZoneLayout;

import java.time.Duration;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.random.RandomGenerator;

/**
 * 不定時活動（2026-10，開服活動）：營地附近不定時出現世界 Boss 或入侵怪潮。
 * 事件持續 {@link WorldEventConfig#durationMinutes()} 分鐘；時間到沒完成、或附近一陣子沒人就關閉，怪物收掉。
 * 打完給活動素材，到選單的「不定時活動」兌換。
 */
public final class WorldEventService {

    public enum Kind {
        BOSS("世界 Boss"), INVASION("入侵怪潮");

        private final String displayName;

        Kind(final String displayName) {
            this.displayName = displayName;
        }

        public String displayName() {
            return displayName;
        }
    }

    /** 正在進行的事件。 */
    private final class Active {
        final Kind kind;
        final Location center;
        final long startMillis;
        final long endMillis;
        final Set<UUID> participants = new HashSet<>();
        final Set<UUID> mobs = new HashSet<>();
        final BossBar bar;
        final Set<UUID> viewers = new HashSet<>();
        long lastSeenMillis;
        LivingEntity boss;
        int wave;
        long waveStartMillis;
        boolean commanderOut;
        int level;
        /** 世界 Boss 現在照幾人算生命。 */
        int bossPlayers = 1;
        /** 世界 Boss、統帥現在的最大生命（照目前算的人數；寫伺服器紀錄用，2026-10）。 */
        double bossLife;
        double commanderLife;
        /** 入侵的統帥（出來了才有）與現在照幾人算生命（2026-10）。 */
        LivingEntity commander;
        int commanderPlayers = 1;

        Active(final Kind kind, final Location center, final long now) {
            this.kind = kind;
            this.center = center;
            this.startMillis = now;
            this.endMillis = now + config.durationMinutes() * 60_000L;
            this.lastSeenMillis = now;
            this.bar = BossBar.bossBar(plugin.text().parse("<#ffd060>" + config.name()), 1f,
                    kind == Kind.BOSS ? BossBar.Color.YELLOW : BossBar.Color.BLUE, BossBar.Overlay.PROGRESS);
        }
    }

    private final ExileCore plugin;
    private final WorldEventConfig config;
    private final NamespacedKey key;
    private final RandomGenerator random;
    private Active active;
    /** 下一次事件（毫秒）；-1 = 還沒排。 */
    private long nextMillis = -1;
    private Kind nextKind;
    private boolean warned;

    WorldEventService(final ExileCore plugin, final WorldEventConfig config) {
        this.plugin = plugin;
        this.config = config;
        this.key = new NamespacedKey(plugin, "world_event");
        this.random = plugin.items().random();
    }

    public WorldEventConfig config() {
        return config;
    }

    // ---- 稱號 ----

    /** 兌換商店的稱號登記到稱號系統（戴、顯示、加成都認得）。 */
    void registerTitles() {
        final BountyService bounties = plugin.bounties();
        if (bounties == null) {
            return;
        }
        final Map<String, BountyConfig.Title> titles = new LinkedHashMap<>();
        for (final WorldEventConfig.ShopItem item : config.shop()) {
            if (item.kind() == WorldEventConfig.Kind.TITLE) {
                titles.put(item.titleId(), new BountyConfig.Title(item.titleId(), item.titleName(), item.titleColor(), 0, item.titleStats()));
            }
        }
        bounties.registerTitles(titles);
        bounties.setTitleSource("world_event", new BountyService.TitleSource() {
            @Override
            public Set<String> owned(final Map<String, String> progress) {
                return Set.of();
            }

            @Override
            public List<String> howTo(final Player player, final String id) {
                return titles.containsKey(id) ? List.of("<gray>" + config.name() + "：選單的「不定時活動」用素材兌換") : null;
            }

            @Override
            public String sourceName(final String id) {
                return titles.containsKey(id) ? config.name() + "兌換" : null;
            }
        });
    }

    // ---- 排程 ----

    public boolean running() {
        return active != null;
    }

    public Kind runningKind() {
        return active == null ? null : active.kind;
    }

    /** 整點模式（2026-10）：now 之後（不含現在這一刻）下一個列在 hours 裡的整點。 */
    private long nextOnTheHour(final long now) {
        java.time.ZonedDateTime at = java.time.Instant.ofEpochMilli(now).atZone(config.timezone())
                .truncatedTo(java.time.temporal.ChronoUnit.HOURS).plusHours(1);
        for (int i = 0; i < 48 && !config.hours().contains(at.getHour()); i++) {
            at = at.plusHours(1);
        }
        return at.toInstant().toEpochMilli();
    }

    /** 下一次是什麼（還沒排、正在進行 null）。 */
    public Kind upcoming() {
        return active != null || nextMillis < 0 ? null : nextKind;
    }

    /** 改了設定（/ec reload）：沒在進行的話照新的時間重排。 */
    void reschedule() {
        if (active == null) {
            nextMillis = -1;
        }
    }

    /** 下一次的時間（「20:00」，照設定的時區；還沒排、正在進行 null）。 */
    public String nextClock() {
        if (active != null || nextMillis < 0) {
            return null;
        }
        return java.time.Instant.ofEpochMilli(nextMillis).atZone(config.timezone())
                .format(java.time.format.DateTimeFormatter.ofPattern("HH:mm", Locale.ROOT));
    }

    /** 下一次大概幾分鐘後（還沒排、正在進行 -1）。 */
    public long minutesUntilNext() {
        if (active != null || nextMillis < 0) {
            return -1;
        }
        return Math.max(0, (nextMillis - System.currentTimeMillis()) / 60_000);
    }

    /** 正在進行的事件還剩幾秒。 */
    public long secondsLeft() {
        return active == null ? 0 : Math.max(0, (active.endMillis - System.currentTimeMillis()) / 1000);
    }

    public Location location() {
        return active == null ? null : active.center.clone();
    }

    /** 每秒。 */
    void tick() {
        ensureArena();
        final long now = System.currentTimeMillis();
        if (active != null) {
            tickActive(now);
            return;
        }
        if (!config.enabled()) {
            return;
        }
        if (nextMillis < 0) {
            schedule(now, true);
            return;
        }
        if (!warned && config.warnMinutes() > 0 && now >= nextMillis - config.warnMinutes() * 60_000L) {
            warned = true;
            if (eligiblePlayers() >= config.minPlayers()) {
                broadcast("<#ffd060>✦ " + config.name() + "<gray>：" + config.warnMinutes() + " 分鐘後，活動場地會出現<white>"
                        + nextKind.displayName() + "<gray>！");
            }
        }
        if (now >= nextMillis) {
            if (eligiblePlayers() >= config.minPlayers()) {
                start(nextKind, null);
            }
            schedule(now, false);
        }
    }

    private void schedule(final long now, final boolean first) {
        if (config.onTheHour()) {
            nextMillis = nextOnTheHour(now);
        } else {
            final int[] range = config.interval();
            final int minutes = first ? config.firstDelay() : range[0] + random.nextInt(Math.max(1, range[1] - range[0] + 1));
            nextMillis = now + minutes * 60_000L;
        }
        final int total = config.bossWeight() + config.invasionWeight();
        if (config.onTheHour() && config.alternate()) {
            // 2026-10：輪流，2 小時一整輪——雙數點世界 Boss、單數點入侵怪潮
            final int hour = java.time.Instant.ofEpochMilli(nextMillis).atZone(config.timezone()).getHour();
            nextKind = hour % 2 == 0 ? Kind.BOSS : Kind.INVASION;
        } else {
            nextKind = total <= 0 || random.nextInt(Math.max(1, total)) < config.invasionWeight() ? Kind.INVASION : Kind.BOSS;
        }
        warned = false;
    }

    private int eligiblePlayers() {
        int count = 0;
        for (final Player player : plugin.getServer().getOnlinePlayers()) {
            if (plugin.players().get(player).hasCharacter()) {
                count++;
            }
        }
        return count;
    }

    // ---- 開始與結束 ----

    /** 開始一個事件（管理員指令也用；at null = 自己找地點）。回傳問題，成功 null。 */
    public String start(final Kind kind, final Location at) {
        if (active != null) {
            return "已經有事件在進行（/ec worldevent stop 先結束）";
        }
        final Location center = at != null ? at : pickLocation();
        if (center == null) {
            return "找不到事件的地點：營地還沒設定，請用 /ec worldevent here 加一個地點";
        }
        active = new Active(kind, center, System.currentTimeMillis());
        center.getChunk().load();
        if (kind == Kind.BOSS && !spawnBoss(active)) {
            active = null;
            return "世界 Boss 生不出來（看主控台）";
        }
        if (kind == Kind.INVASION) {
            active.level = invasionLevel(active);
            active.wave = 0;
            active.waveStartMillis = 0;
        }
        final String where = String.format(Locale.ROOT, "（%d, %d, %d）", center.getBlockX(), center.getBlockY(), center.getBlockZ());
        broadcast("<#ffd060>✦ " + config.name() + "<white>：" + (kind == Kind.BOSS ? "<#ffb347>" + ItemRenderer.escape(config.boss().name())
                : "<#3fc8ff>" + ItemRenderer.escape(config.invasion().name())) + "<white> 出現在" + (hasArena() ? "活動場地" : "營地附近" + where) + "！"
                + "<gray> " + config.durationMinutes() + " 分鐘內完成（選單的「不定時活動」可以傳送過去）");
        for (final Player player : plugin.getServer().getOnlinePlayers()) {
            player.playSound(player.getLocation(), kind == Kind.BOSS ? Sound.ENTITY_WITHER_SPAWN : Sound.EVENT_RAID_HORN, 0.6f, 1f);
        }
        return null;
    }

    /** 管理員結束（或關服）：怪物收掉，不給獎勵。 */
    public void stop(final String reason) {
        if (active == null) {
            return;
        }
        final Active ending = active;
        active = null;
        cleanup(ending);
        if (reason != null) {
            broadcast("<#ffd060>✦ " + config.name() + "<gray>：" + reason);
        }
    }

    private void cleanup(final Active ending) {
        for (final UUID id : ending.mobs) {
            final Entity entity = plugin.getServer().getEntity(id);
            if (entity != null) {
                if (plugin.skills() != null) {
                    plugin.skills().bossSkills().detach(id);
                }
                entity.remove();
            }
        }
        ending.mobs.clear();
        for (final UUID viewer : ending.viewers) {
            final Player player = plugin.getServer().getPlayer(viewer);
            if (player != null) {
                player.hideBossBar(ending.bar);
            }
        }
        ending.viewers.clear();
    }

    /** 管理員用 /ec worldevent here 加的地點（存在 plugins/ExileCore/world_event_spots.txt，一行一個「世界,x,y,z」）。 */
    private java.nio.file.Path spotFile() {
        return plugin.dataFolder().resolve("world_event_spots.txt");
    }

    List<WorldEventConfig.Spot> addedSpots() {
        final List<WorldEventConfig.Spot> list = new ArrayList<>();
        try {
            if (java.nio.file.Files.exists(spotFile())) {
                for (final String line : java.nio.file.Files.readAllLines(spotFile())) {
                    final String[] parts = line.trim().split(",");
                    if (parts.length == 4) {
                        list.add(new WorldEventConfig.Spot(parts[0], Double.parseDouble(parts[1]), Double.parseDouble(parts[2]), Double.parseDouble(parts[3])));
                    }
                }
            }
        } catch (final java.io.IOException | NumberFormatException e) {
            plugin.getSLF4JLogger().warn("不定時活動：讀不到 world_event_spots.txt：{}", e.toString());
        }
        return list;
    }

    public String addSpot(final Location at) {
        try {
            java.nio.file.Files.writeString(spotFile(), String.format(Locale.ROOT, "%s,%.1f,%.1f,%.1f%n", at.getWorld().getName(),
                    at.getX(), at.getY(), at.getZ()), java.nio.file.StandardOpenOption.CREATE, java.nio.file.StandardOpenOption.APPEND);
            return null;
        } catch (final java.io.IOException e) {
            return "寫不進 world_event_spots.txt：" + e.getMessage();
        }
    }

    public String clearSpots() {
        try {
            java.nio.file.Files.deleteIfExists(spotFile());
            return null;
        } catch (final java.io.IOException e) {
            return e.getMessage();
        }
    }

    // ---- 圓形場地（2026-10，/ec worldevent arena）----

    private java.nio.file.Path arenaFile() {
        return plugin.dataFolder().resolve("world_event_arena.txt");
    }

    /** 蓋好的場地（世界、中心、半徑）；沒有 null。 */
    private Location arenaCenter;
    private int arenaRadius;
    private boolean arenaLoaded;

    private void loadArena() {
        if (arenaLoaded) {
            return;
        }
        arenaLoaded = true;
        try {
            if (java.nio.file.Files.exists(arenaFile())) {
                final String[] parts = java.nio.file.Files.readString(arenaFile()).trim().split(",");
                final World world = parts.length == 5 ? plugin.getServer().getWorld(parts[0]) : null;
                if (world != null) {
                    arenaCenter = new Location(world, Integer.parseInt(parts[1]) + 0.5, Integer.parseInt(parts[2]), Integer.parseInt(parts[3]) + 0.5);
                    arenaRadius = Integer.parseInt(parts[4]);
                }
            }
        } catch (final java.io.IOException | NumberFormatException e) {
            plugin.getSLF4JLogger().warn("不定時活動：讀不到 world_event_arena.txt：{}", e.toString());
        }
    }

    /** 現在能不能傳過去（事件進行中，或有場地可以提早入場）。 */
    public boolean canTravel() {
        return active != null || hasArena();
    }

    /** 有沒有蓋好的場地。 */
    public boolean hasArena() {
        loadArena();
        return arenaCenter != null;
    }

    /** 活動世界（虛空；沒有就 null）。 */
    private World eventWorld() {
        return plugin.world() == null || plugin.world().worlds() == null ? null : plugin.world().worlds().event();
    }

    /** 活動世界裡場地的中心（地板在 y = 99）。 */
    private static final int EVENT_FLOOR_Y = 100;
    static final int DEFAULT_ARENA_RADIUS = 45;
    private boolean arenaChecked;

    /**
     * 啟動後第一次：活動世界在、但場地還沒蓋在活動世界裡（沒蓋過，或之前蓋在劇情世界）→ 自動在活動世界的 (0, 100, 0) 蓋一個。
     */
    private void ensureArena() {
        if (arenaChecked) {
            return;
        }
        arenaChecked = true;
        final World world = eventWorld();
        if (world == null || hasArena() && world.equals(arenaCenter.getWorld())) {
            return;
        }
        plugin.getSLF4JLogger().info("不定時活動：在活動世界 {} 自動蓋圓形場地（半徑 {}）", world.getName(), DEFAULT_ARENA_RADIUS);
        buildArena(plugin.getServer().getConsoleSender(), null, DEFAULT_ARENA_RADIUS);
    }

    /**
     * 蓋一個半徑 radius 的圓形場地；蓋好之後事件都在裡面發生。有活動世界就一律蓋在活動世界的 (0, 100, 0)（不會影響劇情世界的生怪區），
     * 沒有才蓋在 at（管理員腳下）。
     */
    public String buildArena(final org.bukkit.command.CommandSender sender, final Location at, final int radius) {
        if (active != null) {
            return "事件進行中不能蓋場地";
        }
        final World world = eventWorld();
        if (world == null && at == null) {
            return "活動世界沒有載入（看主控台）";
        }
        final Location center = world != null ? new Location(world, 0, EVENT_FLOOR_Y, 0) : at.getBlock().getLocation();
        new ArenaBuilder(plugin, sender, center, radius, world != null, () -> {
            arenaCenter = center.clone().add(0.5, 0, 0.5);
            arenaRadius = radius;
            arenaLoaded = true;
            try {
                java.nio.file.Files.writeString(arenaFile(), String.format(Locale.ROOT, "%s,%d,%d,%d,%d", center.getWorld().getName(),
                        center.getBlockX(), center.getBlockY(), center.getBlockZ(), radius));
            } catch (final java.io.IOException e) {
                sender.sendMessage(plugin.text().prefixed("<red>場地蓋好了，但記不進 world_event_arena.txt：" + e.getMessage()));
            }
        }).start();
        return null;
    }

    /** 事件範圍：有場地就是場地再多 8 格。 */
    private double eventRadius() {
        return hasArena() ? Math.max(config.radius(), arenaRadius + 8) : config.radius();
    }

    /** 地點：有場地就在場地中央；不然寫好的點（world_events.yml 的 locations＋指令加的）隨機挑；都沒有就在營地外圍找。 */
    private Location pickLocation() {
        if (hasArena()) {
            return arenaCenter.clone();
        }
        final List<WorldEventConfig.Spot> spots = new ArrayList<>(config.spots());
        spots.addAll(addedSpots());
        if (!spots.isEmpty()) {
            final WorldEventConfig.Spot spot = spots.get(random.nextInt(spots.size()));
            final World world = plugin.getServer().getWorld(spot.world());
            if (world != null) {
                return new Location(world, spot.x(), spot.y(), spot.z());
            }
        }
        if (plugin.zones() == null) {
            return null;
        }
        final Optional<Zone> camp = plugin.zones().camp();
        if (camp.isEmpty() || camp.get().layout() == null || camp.get().layout().box() == null) {
            return null;
        }
        final ZoneLayout.Box box = camp.get().layout().box();
        final World world = plugin.getServer().getWorld(box.world());
        if (world == null) {
            return null;
        }
        final double cx = (box.minX() + box.maxX()) / 2.0;
        final double cz = (box.minZ() + box.maxZ()) / 2.0;
        final double half = Math.max(box.maxX() - box.minX(), box.maxZ() - box.minZ()) / 2.0;
        for (int attempt = 0; attempt < 12; attempt++) {
            final double angle = random.nextDouble() * Math.PI * 2;
            final double distance = half + config.campDistance();
            final int x = (int) Math.round(cx + Math.cos(angle) * distance);
            final int z = (int) Math.round(cz + Math.sin(angle) * distance);
            final Location ground = world.getHighestBlockAt(x, z).getLocation().add(0.5, 1, 0.5);
            if (!ground.getBlock().getRelative(0, -1, 0).isLiquid() && !plugin.zones().inTown(ground)) {
                return ground;
            }
        }
        return null;
    }

    // ---- 每秒 ----

    private void tickActive(final long now) {
        final Active event = active;
        // 參加的人：範圍內、有角色、活著
        final List<Player> near = nearPlayers(event, eventRadius());
        for (final Player player : near) {
            event.participants.add(player.getUniqueId());
        }
        if (!near.isEmpty()) {
            event.lastSeenMillis = now;
        }
        if (now >= event.endMillis) {
            final LivingEntity left = event.kind == Kind.BOSS ? event.boss : event.commander;
            final AttributeInstance leftMax = left == null || !left.isValid() ? null : left.getAttribute(Attribute.MAX_HEALTH);
            plugin.getSLF4JLogger().info("[Boss 紀錄] 不定時活動：{} 時間到沒有完成：{}", event.kind.displayName(), leftMax == null || leftMax.getValue() <= 0
                    ? "Boss 不在場" : String.format(Locale.ROOT, "還剩 %.1f%%（算 %d 人、生命 %s）", left.getHealth() / leftMax.getValue() * 100,
                    event.kind == Kind.BOSS ? event.bossPlayers : event.commanderPlayers,
                    number(event.kind == Kind.BOSS ? event.bossLife : event.commanderLife)));
            stop("時間到，" + event.kind.displayName() + "沒有完成，事件結束了");
            return;
        }
        if (now - event.lastSeenMillis > config.emptyCloseSeconds() * 1000L) {
            stop("附近沒有人，" + event.kind.displayName() + "離開了");
            return;
        }
        event.mobs.removeIf(id -> {
            final Entity entity = plugin.getServer().getEntity(id);
            return entity == null || !entity.isValid() || entity.isDead();
        });
        if (event.kind == Kind.BOSS) {
            tickBoss(event, near);
        } else {
            tickInvasion(event, near, now);
        }
        updateBar(event, now);
    }

    private List<Player> nearPlayers(final Active event, final double radius) {
        final List<Player> list = new ArrayList<>();
        for (final Player player : event.center.getWorld().getPlayers()) {
            if (player.isDead() || player.getGameMode() == org.bukkit.GameMode.SPECTATOR || !plugin.players().get(player).hasCharacter()) {
                continue;
            }
            if (player.getLocation().distanceSquared(event.center) <= radius * radius) {
                list.add(player);
            }
        }
        return list;
    }

    private void updateBar(final Active event, final long now) {
        final long left = Math.max(0, (event.endMillis - now) / 1000);
        final String time = String.format(Locale.ROOT, "%d:%02d", left / 60, left % 60);
        if (event.kind == Kind.BOSS) {
            if (event.boss != null && event.boss.isValid()) {
                final AttributeInstance max = event.boss.getAttribute(Attribute.MAX_HEALTH);
                event.bar.progress((float) Math.max(0, Math.min(1, event.boss.getHealth() / Math.max(1, max == null ? 1 : max.getValue()))));
            }
            event.bar.name(plugin.text().parse("<#ffb347>" + ItemRenderer.escape(config.boss().name()) + " <gray>Lv." + config.boss().level()
                    + " <dark_gray>｜ <white>" + time));
        } else {
            final WorldEventConfig.Invasion invasion = config.invasion();
            final int shownWave = Math.max(1, Math.min(invasion.waves(), event.wave));
            event.bar.progress((float) Math.max(0, Math.min(1, left / (config.durationMinutes() * 60.0))));
            event.bar.name(plugin.text().parse("<#3fc8ff>" + ItemRenderer.escape(invasion.name())
                    + (event.commanderOut ? " <gray>｜ <#ff7a3d>" + ItemRenderer.escape(invasion.commander().name())
                    : " <gray>｜ 第 <white>" + shownWave + "</white>／" + invasion.waves() + " 波")
                    + " <gray>剩 <white>" + event.mobs.size() + "</white> 隻 <dark_gray>｜ <white>" + time));
        }
        final Set<UUID> show = new HashSet<>();
        for (final Player player : nearPlayers(event, eventRadius() + 24)) {
            show.add(player.getUniqueId());
            if (event.viewers.add(player.getUniqueId())) {
                player.showBossBar(event.bar);
            }
        }
        for (final UUID viewer : new ArrayList<>(event.viewers)) {
            if (!show.contains(viewer)) {
                event.viewers.remove(viewer);
                final Player player = plugin.getServer().getPlayer(viewer);
                if (player != null) {
                    player.hideBossBar(event.bar);
                }
            }
        }
    }

    // ---- 伺服器紀錄（2026-10）：世界 Boss、統帥的生命與打了多久，跟地圖終局一樣用「[Boss 紀錄]」開頭 ----

    /** 這隻現在的最大生命（照目前的生命倍率）；讀不到回傳 0。 */
    private double lifeOf(final LivingEntity mob) {
        return plugin.monsters().data(mob).map(data -> plugin.monsters().sheet(mob, data).get(tw.exilecore.stats.StatKeys.LIFE_MAX)).orElse(0.0);
    }

    private static String number(final double value) {
        return String.format(Locale.ROOT, "%,.0f", value);
    }

    /** 事件開始到現在幾秒（一位小數）。 */
    private static String seconds(final Active event) {
        return String.format(Locale.ROOT, "%.1f", (System.currentTimeMillis() - event.startMillis) / 1000.0);
    }

    // ---- 世界 Boss ----

    private boolean spawnBoss(final Active event) {
        final WorldEventConfig.Boss def = config.boss();
        final LivingEntity boss = spawnMob(def.type(), event.center);
        if (boss == null) {
            plugin.getSLF4JLogger().warn("不定時活動：世界 Boss 的類型 {} 生不出來", def.type());
            return false;
        }
        try {
            plugin.monsters().fixPlayers(boss, 1); // 人數由下面的 tickBoss 照在場的高等級玩家算
            plugin.monsters().decorateBoss(boss, def.level(), def.name(), def.mods(), def.life(), def.damage(), def.scale());
        } catch (final RuntimeException e) {
            boss.remove();
            plugin.getSLF4JLogger().error("不定時活動：世界 Boss 等級化失敗", e);
            return false;
        }
        tag(boss, "boss");
        event.boss = boss;
        event.bossLife = lifeOf(boss);
        plugin.getSLF4JLogger().info("[Boss 紀錄] 不定時活動：{} Lv.{} 出現：生命 {}（算 1 人）", def.name(), def.level(), number(event.bossLife));
        event.mobs.add(boss.getUniqueId());
        final AttributeInstance follow = boss.getAttribute(Attribute.FOLLOW_RANGE);
        if (follow != null && follow.getBaseValue() < 48) {
            follow.setBaseValue(48);
        }
        attachSkills(boss, def.name(), def.skills(), def.level());
        for (final Player player : nearPlayers(event, eventRadius() + 24)) {
            player.showTitle(Title.title(plugin.text().parse("<#ffb347>" + ItemRenderer.escape(def.name())),
                    plugin.text().parse("<gray>世界 Boss"), Title.Times.times(Duration.ofMillis(300), Duration.ofMillis(2200), Duration.ofMillis(600))));
            if (!def.intro().isBlank()) {
                player.sendMessage(plugin.text().parse("<#ffb347>" + ItemRenderer.escape(def.name()) + "<white>：" + ItemRenderer.escape(def.intro())));
            }
        }
        return true;
    }

    /** 世界 Boss：生命固定（2026-10：不因為人數變多）；沒在打人就找一個附近的。 */
    private void tickBoss(final Active event, final List<Player> near) {
        final LivingEntity boss = event.boss;
        if (boss == null || !boss.isValid()) {
            return;
        }
        if (boss instanceof Mob mob && !near.isEmpty() && !(mob.getTarget() instanceof Player)) {
            mob.setTarget(near.get(random.nextInt(near.size())));
        }
        // 2026-10：生命照在場（浮動）的高等級玩家人數：等級 count_level 以上、活著、不是旁觀的才算，最多 max_counted_players 人；
        // 有人進來就變多、離開就變少，血量比例不變
        final WorldEventConfig.Boss def = config.boss();
        final int players = strongPlayers(near, def.countLevel(), def.maxCounted());
        if (players != event.bossPlayers) {
            final int before = event.bossPlayers;
            event.bossPlayers = players;
            if (plugin.monsters().setLifeMultiplier(boss, def.life() * (1 + def.lifePerPlayer() * (players - 1)))) {
                event.bossLife = lifeOf(boss);
                plugin.getSLF4JLogger().info("[Boss 紀錄] 不定時活動：{} 的生命照 {} 人重算：{}（開始後 {} 秒）", def.name(), players,
                        number(event.bossLife), seconds(event));
                for (final Player player : near) {
                    player.sendMessage(plugin.text().prefixed("<#ffd27f>" + ItemRenderer.escape(def.name()) + " 的生命照 " + players + " 位 "
                            + def.countLevel() + " 等以上的玩家重算" + (players > before ? "（變多）" : "（變少）") + "<gray>，血量比例不變"));
                }
            }
        }
    }

    /** 在場（活著、不是旁觀或創造）、等級 countLevel 以上的人數，最多 maxCounted（0 = 沒有上限），至少 1。 */
    private int strongPlayers(final List<Player> near, final int countLevel, final int maxCounted) {
        int strong = 0;
        for (final Player player : near) {
            if (!player.isDead() && player.getGameMode() != org.bukkit.GameMode.SPECTATOR
                    && player.getGameMode() != org.bukkit.GameMode.CREATIVE && plugin.players().get(player).level() >= countLevel) {
                strong++;
            }
        }
        return Math.max(1, maxCounted > 0 ? Math.min(maxCounted, strong) : strong);
    }

    /** 2026-10：統帥的生命照在場的高等級玩家人數重算（跟世界 Boss 一樣；血量比例不變）。 */
    private void tickCommander(final Active event, final List<Player> near) {
        final LivingEntity commander = event.commander;
        if (commander == null || !commander.isValid()) {
            return;
        }
        final WorldEventConfig.Commander def = config.invasion().commander();
        if (def.lifePerPlayer() <= 0) {
            return;
        }
        final int players = strongPlayers(near, def.countLevel(), def.maxCounted());
        if (players != event.commanderPlayers) {
            final int before = event.commanderPlayers;
            event.commanderPlayers = players;
            if (plugin.monsters().setLifeMultiplier(commander, def.life() * (1 + def.lifePerPlayer() * (players - 1)))) {
                event.commanderLife = lifeOf(commander);
                plugin.getSLF4JLogger().info("[Boss 紀錄] 不定時活動：{} 的生命照 {} 人重算：{}（開始後 {} 秒）", def.name(), players,
                        number(event.commanderLife), seconds(event));
                for (final Player player : near) {
                    player.sendMessage(plugin.text().prefixed("<#ff7a3d>" + ItemRenderer.escape(def.name()) + " 的生命照 " + players + " 位 "
                            + def.countLevel() + " 等以上的玩家重算" + (players > before ? "（變多）" : "（變少）") + "<gray>，血量比例不變"));
                }
            }
        }
    }

    // ---- 入侵怪潮 ----

    private int invasionLevel(final Active event) {
        final WorldEventConfig.Invasion invasion = config.invasion();
        final List<Player> near = nearPlayers(event, eventRadius() + 40);
        final List<Player> pool = near.isEmpty() ? new ArrayList<>(plugin.getServer().getOnlinePlayers()) : near;
        int total = 0;
        int count = 0;
        for (final Player player : pool) {
            final PlayerState state = plugin.players().get(player);
            if (state.hasCharacter()) {
                total += state.level();
                count++;
            }
        }
        final int level = count == 0 ? invasion.levelMin() : Math.round(total / (float) count);
        return Math.max(invasion.levelMin(), Math.min(invasion.levelMax(), level));
    }

    private void tickInvasion(final Active event, final List<Player> near, final long now) {
        final WorldEventConfig.Invasion invasion = config.invasion();
        if (event.commanderOut) {
            // 統帥倒了就完成（在死亡事件裡處理）；小怪繼續追人
            retarget(event, near);
            tickCommander(event, near);
            return;
        }
        if (near.isEmpty() && event.wave == 0) {
            return; // 等第一個人到了才開始
        }
        final boolean cleared = event.mobs.size() < 3;
        final boolean overdue = event.wave > 0 && now - event.waveStartMillis >= invasion.waveSeconds() * 1000L;
        if (event.wave == 0 || cleared || overdue) {
            if (event.wave >= invasion.waves()) {
                if (cleared) {
                    spawnCommander(event);
                }
            } else if (event.mobs.size() < 80) {
                event.wave++;
                event.waveStartMillis = now;
                spawnWave(event, Math.max(1, near.size()));
                for (final Player player : near) {
                    player.sendActionBar(plugin.text().parse("<#3fc8ff>第 " + event.wave + "／" + invasion.waves() + " 波怪潮來了！"));
                    player.playSound(player.getLocation(), Sound.EVENT_RAID_HORN, 0.5f, 1.3f);
                }
            }
        }
        retarget(event, near);
    }

    private void retarget(final Active event, final List<Player> near) {
        if (near.isEmpty()) {
            return;
        }
        for (final UUID id : event.mobs) {
            if (plugin.getServer().getEntity(id) instanceof Mob mob && !(mob.getTarget() instanceof Player)) {
                Player closest = null;
                double best = Double.MAX_VALUE;
                for (final Player player : near) {
                    final double distance = player.getLocation().distanceSquared(mob.getLocation());
                    if (distance < best) {
                        best = distance;
                        closest = player;
                    }
                }
                mob.setTarget(closest);
            }
        }
    }

    private void spawnWave(final Active event, final int players) {
        final WorldEventConfig.Invasion invasion = config.invasion();
        final int count = Math.min(invasion.maxCount(), invasion.baseCount() + invasion.perPlayer() * players);
        for (int i = 0; i < count; i++) {
            final String type = invasion.types().get(random.nextInt(invasion.types().size()));
            final double angle = random.nextDouble() * Math.PI * 2;
            final double distance = hasArena() ? arenaRadius * (0.55 + random.nextDouble() * 0.35) : 12 + random.nextDouble() * 8;
            final Location at = event.center.clone().add(Math.cos(angle) * distance, 0, Math.sin(angle) * distance);
            // 有場地：就在場地的地板上（上面清空了）；沒有：找最高的地面
            final Location ground = hasArena() ? at.getBlock().getLocation().add(0.5, 0, 0.5)
                    : at.getWorld().getHighestBlockAt(at).getLocation().add(0.5, 1, 0.5);
            final LivingEntity mob = spawnMob(type, ground);
            if (mob == null) {
                continue;
            }
            final MonsterRarity rarity = i < invasion.rarePerWave() ? MonsterRarity.RARE
                    : i < invasion.rarePerWave() + invasion.magicPerWave() ? MonsterRarity.MAGIC : MonsterRarity.NORMAL;
            try {
                plugin.monsters().decorate(mob, event.level, rarity, List.of());
            } catch (final RuntimeException e) {
                mob.remove();
                continue;
            }
            mob.setRemoveWhenFarAway(false);
            tag(mob, rarity == MonsterRarity.RARE ? "rare" : "mob");
            event.mobs.add(mob.getUniqueId());
        }
    }

    private void spawnCommander(final Active event) {
        final WorldEventConfig.Commander def = config.invasion().commander();
        final LivingEntity commander = spawnMob(def.type(), event.center);
        event.commanderOut = true;
        if (commander == null) {
            complete(event, null);
            return;
        }
        try {
            plugin.monsters().decorateBoss(commander, event.level, def.name(), List.of(), def.life(), def.damage(), 1.2);
        } catch (final RuntimeException e) {
            commander.remove();
            complete(event, null);
            return;
        }
        tag(commander, "commander");
        event.mobs.add(commander.getUniqueId());
        event.commander = commander;
        event.commanderPlayers = 1;
        event.commanderLife = lifeOf(commander);
        tickCommander(event, nearPlayers(event, eventRadius() + 24)); // 一出來就照現在的人數
        plugin.getSLF4JLogger().info("[Boss 紀錄] 不定時活動：{} Lv.{} 出現：生命 {}（算 {} 人，開始後 {} 秒）", def.name(), event.level,
                number(event.commanderLife), event.commanderPlayers, seconds(event));
        attachSkills(commander, def.name(), def.skills(), event.level);
        for (final Player player : nearPlayers(event, eventRadius() + 24)) {
            player.showTitle(Title.title(plugin.text().parse("<#ff7a3d>" + ItemRenderer.escape(def.name())),
                    plugin.text().parse("<gray>最後一波：打倒牠就完成了"), Title.Times.times(Duration.ofMillis(300), Duration.ofMillis(2000), Duration.ofMillis(500))));
        }
    }

    // ---- 怪物 ----

    private LivingEntity spawnMob(final String typeName, final Location at) {
        final EntityType type;
        try {
            type = EntityType.valueOf(typeName);
        } catch (final IllegalArgumentException e) {
            return null;
        }
        if (!type.isSpawnable() || type.getEntityClass() == null || !LivingEntity.class.isAssignableFrom(type.getEntityClass())) {
            return null;
        }
        final Entity entity = at.getWorld().spawnEntity(at, type, CreatureSpawnEvent.SpawnReason.CUSTOM, created -> {
            if (created instanceof Ageable ageable) {
                ageable.setAdult();
            }
        });
        if (entity instanceof LivingEntity living && living.isValid()) {
            living.clearActivePotionEffects();
            living.setPersistent(false);
            return living;
        }
        entity.remove();
        return null;
    }

    private void tag(final LivingEntity entity, final String role) {
        entity.getPersistentDataContainer().set(key, PersistentDataType.STRING, role);
    }

    /** 這隻是不定時活動的怪嗎（回傳角色：boss、commander、rare、mob；不是 null）。 */
    public String role(final Entity entity) {
        return entity.getPersistentDataContainer().get(key, PersistentDataType.STRING);
    }

    private void attachSkills(final LivingEntity boss, final String name, final List<tw.exilecore.skill.BossSkillUse> skills, final int level) {
        if (plugin.skills() == null || skills.isEmpty()) {
            return;
        }
        plugin.skills().bossSkills().attach(boss, skills, new tw.exilecore.skill.BossSkills.Host() {
            @Override
            public String name() {
                return name;
            }

            @Override
            public int phase() {
                return 0;
            }

            @Override
            public String tempoKind() {
                return "world"; // 2026-10：出招節奏照 boss_skills.yml 的 settings.tempo.world
            }

            @Override
            public LivingEntity summon(final String type, final Location at) {
                final LivingEntity minion = spawnMob(type.toUpperCase(Locale.ROOT), at);
                if (minion == null) {
                    return null;
                }
                plugin.monsters().markSummoned(minion);
                plugin.monsters().decorate(minion, level, MonsterRarity.NORMAL, List.of());
                if (active != null) {
                    active.mobs.add(minion.getUniqueId());
                }
                return minion;
            }
        });
    }

    // ---- 死亡、獎勵 ----

    /** 不定時活動的怪死了（WorldEventModule 的死亡事件）。 */
    void onDeath(final LivingEntity entity, final Player killer) {
        final String role = role(entity);
        if (role == null || active == null) {
            return;
        }
        final Active event = active;
        event.mobs.remove(entity.getUniqueId());
        if (plugin.skills() != null) {
            plugin.skills().bossSkills().detach(entity.getUniqueId());
        }
        switch (role) {
            case "boss" -> complete(event, entity.getLocation());
            case "commander" -> {
                for (final Player player : rewardees(event)) {
                    giveCurrency(player, config.invasion().currency(), config.invasion().commanderAmount());
                }
                complete(event, entity.getLocation());
            }
            case "rare" -> {
                if (killer != null) {
                    giveCurrency(killer, config.invasion().currency(), config.invasion().rareAmount());
                }
            }
            default -> {
                if (killer != null && random.nextDouble() < config.invasion().killChance()) {
                    giveCurrency(killer, config.invasion().currency(), 1);
                }
            }
        }
    }

    /** 拿獎勵的人：參加過、現在在範圍內（多 20 格）、在線、有角色。 */
    private List<Player> rewardees(final Active event) {
        final List<Player> list = new ArrayList<>();
        for (final UUID id : event.participants) {
            final Player player = plugin.getServer().getPlayer(id);
            if (player != null && player.getWorld().equals(event.center.getWorld()) && plugin.players().get(player).hasCharacter()
                    && player.getLocation().distanceSquared(event.center) <= Math.pow(eventRadius() + 20, 2)) {
                list.add(player);
            }
        }
        return list;
    }

    private void complete(final Active event, final Location at) {
        if (active != event) {
            return;
        }
        final List<Player> players = rewardees(event);
        // 2026-10：結果寫進伺服器紀錄（調血量用）：打了幾秒、照幾人算、生命多少、幾個人有份
        if (event.kind == Kind.BOSS) {
            plugin.getSLF4JLogger().info("[Boss 紀錄] 不定時活動：{} 倒下：{} 秒、算 {} 人、生命 {}、{} 人拿到獎勵", config.boss().name(),
                    seconds(event), event.bossPlayers, number(event.bossLife), players.size());
        } else {
            plugin.getSLF4JLogger().info("[Boss 紀錄] 不定時活動：{} 完成：{} 秒、{} 算 {} 人、生命 {}、{} 人拿到獎勵", config.invasion().name(),
                    seconds(event), config.invasion().commander().name(), event.commanderPlayers, number(event.commanderLife), players.size());
        }
        if (event.kind == Kind.BOSS) {
            final WorldEventConfig.Boss def = config.boss();
            for (final Player player : players) {
                final int[] range = def.amount();
                giveCurrency(player, def.currency(), range[0] + random.nextInt(Math.max(1, range[1] - range[0] + 1)));
                for (final WorldEventConfig.Extra extra : def.extra()) {
                    if (random.nextDouble() < extra.chance()) {
                        giveCurrency(player, extra.currency(), extra.amount());
                    }
                }
                if (!def.uniques().isEmpty() && random.nextDouble() < def.uniqueChance()) {
                    final String id = def.uniques().get(random.nextInt(def.uniques().size()));
                    final ItemData data = giveUnique(player, id, def.level());
                    if (data != null) {
                        broadcast("<#ffd060>✦ " + ItemRenderer.escape(player.getName()) + " 從" + ItemRenderer.escape(def.name())
                                + "身上拿到了專屬傳奇 <#af6025>" + ItemRenderer.escape(data.name()) + "<#ffd060>！");
                    }
                }
                player.playSound(player.getLocation(), Sound.UI_TOAST_CHALLENGE_COMPLETE, 0.8f, 1f);
            }
            if (!def.defeat().isBlank()) {
                broadcast("<#ffb347>" + ItemRenderer.escape(def.defeat()));
            }
        } else {
            for (final Player player : players) {
                giveCurrency(player, config.invasion().currency(), config.invasion().completeAmount());
                player.playSound(player.getLocation(), Sound.UI_TOAST_CHALLENGE_COMPLETE, 0.8f, 1f);
            }
        }
        active = null;
        cleanup(event);
        broadcast("<#ffd060>✦ " + config.name() + "<white>：" + event.kind.displayName() + "完成了！<gray>" + players.size()
                + " 位冒險者拿到了活動素材（選單的「不定時活動」兌換）");
    }

    private void giveCurrency(final Player player, final String currencyId, final int amount) {
        if (amount <= 0 || plugin.currencies() == null) {
            return;
        }
        final CurrencyType type = plugin.currencies().repository().get(currencyId);
        if (type == null) {
            return;
        }
        int left = amount;
        while (left > 0) {
            final int stack = Math.min(left, Math.max(1, type.stack()));
            give(player, plugin.currencies().create(type, stack));
            left -= stack;
        }
        player.sendActionBar(plugin.text().parse("<#ffd060>+" + amount + " " + ItemRenderer.escape(type.name())));
    }

    ItemData giveUnique(final Player player, final String uniqueId, final int itemLevel) {
        final UniqueItem unique = plugin.items().repository().unique(uniqueId);
        if (unique == null) {
            return null;
        }
        final ItemData data = plugin.items().generator().generateUnique(unique, itemLevel, random);
        give(player, plugin.items().toStack(data));
        return data;
    }

    void give(final Player player, final ItemStack stack) {
        final tw.exilecore.economy.Wallet wallet = plugin.economy().wallet();
        wallet.dropFor(player, wallet.give(player, stack));
    }

    private void broadcast(final String text) {
        final net.kyori.adventure.text.Component message = plugin.text().prefixed(text);
        for (final Player player : plugin.getServer().getOnlinePlayers()) {
            player.sendMessage(message);
        }
        plugin.getSLF4JLogger().info("[不定時活動] {}", net.kyori.adventure.text.serializer.plain.PlainTextComponentSerializer.plainText()
                .serialize(plugin.text().parse(text)));
        if (plugin.discord() != null) {
            plugin.discord().worldEvent(text); // 2026-10：世界事件同步到 Discord
        }
    }

    // ---- 兌換 ----

    /** 兌換一樣東西；成功 null。 */
    String buy(final Player player, final WorldEventConfig.ShopItem item) {
        final PlayerState state = plugin.players().get(player);
        if (!state.hasCharacter()) {
            return "先選一個角色";
        }
        final tw.exilecore.economy.Wallet wallet = plugin.economy().wallet();
        for (final Map.Entry<String, Integer> cost : item.cost().entrySet()) {
            if (wallet.available(player, cost.getKey()) < cost.getValue()) {
                return "素材不夠：" + currencyName(cost.getKey()) + " 要 " + cost.getValue() + "，你有 " + wallet.available(player, cost.getKey());
            }
        }
        if (item.kind() == WorldEventConfig.Kind.TITLE) {
            if (plugin.bounties() == null) {
                return "稱號系統沒有啟用";
            }
            if (plugin.bounties().ownedTitles(player).contains(item.titleId())) {
                return "已經有這個稱號了";
            }
        }
        for (final Map.Entry<String, Integer> cost : item.cost().entrySet()) {
            if (!wallet.take(player, cost.getKey(), cost.getValue())) {
                return "素材不夠";
            }
        }
        switch (item.kind()) {
            case TITLE -> {
                final String problem = plugin.bounties().grantTitle(player, item.titleId());
                if (problem != null) {
                    plugin.getSLF4JLogger().warn("不定時活動：{} 換稱號 {} 失敗：{}（素材已扣）", player.getName(), item.titleId(), problem);
                    return problem;
                }
            }
            case CURRENCY -> giveCurrency(player, item.currency(), item.amount());
            case UNIQUE -> giveUnique(player, item.unique(), item.itemLevel());
        }
        plugin.players().markDirty(player);
        plugin.characters().save(player);
        plugin.getSLF4JLogger().info("[不定時活動] {} 兌換 {}", player.getName(), item.id());
        return null;
    }

    String currencyName(final String id) {
        final CurrencyType type = plugin.currencies() == null ? null : plugin.currencies().repository().get(id);
        return type == null ? id : type.name();
    }

    /** 傳送到事件的地方（選單用；有事件才能）。 */
    String travel(final Player player) {
        // 2026-10：有活動場地就可以提早入場（事件還沒開始也能先進去等）
        final Location center = active != null ? active.center : hasArena() ? arenaCenter.clone() : null;
        if (center == null) {
            return "現在沒有事件";
        }
        // 2026-10：不要落在正中間（Boss 就在那裡）：在場地的外圈（靠牆內側）隨機一個方向，面向中央
        final double angle = random.nextDouble() * Math.PI * 2;
        final double distance = hasArena() ? Math.max(8, arenaRadius - 6) : 18;
        final Location to = center.clone().add(Math.cos(angle) * distance, 0, Math.sin(angle) * distance);
        final Location ground = to.getWorld().getHighestBlockAt(to).getLocation().add(0.5, 1, 0.5);
        ground.setDirection(center.toVector().subtract(ground.toVector()).setY(0));
        if (plugin.zones() == null || !plugin.zones().teleport(player, ground, config.name())) {
            return "現在不能傳送";
        }
        player.sendMessage(plugin.text().prefixed(active == null
                ? "<gray>提早入場：事件還沒開始" + (nextClock() == null ? "" : "（下一次 <white>" + nextClock() + "</white>）") + "；回營地：<white>/town"
                : "<gray>打完回營地：<white>/town"));
        return null;
    }

    /** 關服：怪物收掉。 */
    void shutdown() {
        if (active != null) {
            final Active ending = active;
            active = null;
            cleanup(ending);
        }
    }
}
