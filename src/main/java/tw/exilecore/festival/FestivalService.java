package tw.exilecore.festival;

import net.kyori.adventure.bossbar.BossBar;
import net.kyori.adventure.title.Title;
import org.bukkit.Color;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.NamespacedKey;
import org.bukkit.Particle;
import org.bukkit.Sound;
import org.bukkit.World;
import org.bukkit.attribute.Attribute;
import org.bukkit.attribute.AttributeInstance;
import org.bukkit.entity.Ageable;
import org.bukkit.entity.Display;
import org.bukkit.entity.Entity;
import org.bukkit.entity.EntityType;
import org.bukkit.entity.Interaction;
import org.bukkit.entity.ItemDisplay;
import org.bukkit.entity.LivingEntity;
import org.bukkit.entity.Mob;
import org.bukkit.entity.Player;
import org.bukkit.entity.TextDisplay;
import org.bukkit.event.entity.CreatureSpawnEvent;
import org.bukkit.inventory.EntityEquipment;
import org.bukkit.inventory.ItemStack;
import org.bukkit.persistence.PersistentDataType;
import org.bukkit.util.Transformation;
import org.joml.AxisAngle4f;
import org.joml.Vector3f;
import tw.exilecore.ExileCore;
import tw.exilecore.bounty.BountyConfig;
import tw.exilecore.bounty.BountyService;
import tw.exilecore.currency.CurrencyType;
import tw.exilecore.item.ItemRenderer;
import tw.exilecore.loot.LootBonus;
import tw.exilecore.monster.MonsterData;
import tw.exilecore.monster.MonsterRarity;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.random.RandomGenerator;

/**
 * 節慶活動（2026-10 萬聖節「萬燈夜」）：幽燈墓園、點燈、南瓜王、不給糖就搗蛋、挑戰與兌換。設定在 data/festival.yml。
 */
public final class FestivalService {

    // 帳號進度（帳號共用）
    static final String LAMP = "fes.lamp";               // 「20261016:位元」今天點了哪幾盞
    static final String LAMP_DAYS = "fes.lamp_days";     // 點滿全部的天數
    static final String KING = "fes.king";               // 打倒南瓜王的次數
    static final String PUMPKIN = "fes.pumpkin";         // 敲過的搗蛋南瓜
    static final String DONE = "fes.c.";                 // 挑戰發過了：fes.c.king、fes.c.pumpkin、fes.c.lamp、fes.c.title
    private static final String COSMETIC = "sp.cos.";    // 贊助模組的時裝擁有（SponsorService.COSMETIC）

    private final ExileCore plugin;
    private final FestivalConfig config;
    private final NamespacedKey key;
    private final RandomGenerator random;

    // ---- 場地 ----
    private boolean arenaChecked;
    private boolean arenaReady;
    private boolean building;

    // ---- 全服點燈 ----
    private int globalCount;
    private long bonusUntil;
    private boolean stateLoaded;
    private boolean stateDirty;

    // ---- 點燈中 ----
    private record Guard(int lantern, int seconds) {
    }

    private final Map<UUID, Guard> guards = new HashMap<>();
    /** 點燈竄出來的幽魂 → 幾點散掉。 */
    private final Map<UUID, Long> ghosts = new HashMap<>();
    /** 現在在墓園裡（夜晚、霧）的人。 */
    private final Set<UUID> inside = new HashSet<>();
    private int tickCount;

    // ---- 南瓜王 ----
    private final class King {
        final long startMillis;
        final long endMillis;
        final Location center;
        final Set<UUID> participants = new HashSet<>();
        final Set<UUID> mobs = new HashSet<>();
        final Set<UUID> viewers = new HashSet<>();
        final BossBar bar;
        LivingEntity boss;
        long lastSeenMillis;
        /** 2026-10-25：南瓜王出場後無敵到這個時間（大家先集合）。 */
        long shieldUntil;
        int players = 1;

        King(final Location center, final long now) {
            this.center = center;
            this.startMillis = now;
            this.endMillis = now + config.boss().durationMinutes() * 60_000L;
            this.lastSeenMillis = now;
            this.bar = BossBar.bossBar(plugin.text().parse("<" + config.color() + ">" + ItemRenderer.escape(config.boss().name())), 1f,
                    BossBar.Color.YELLOW, BossBar.Overlay.NOTCHED_10);
        }
    }

    private King king;
    private long nextKingMillis = -1;
    private boolean kingWarned;

    // ---- 搗蛋南瓜 ----
    private final class Pumpkin {
        final UUID id = UUID.randomUUID();
        final List<UUID> parts = new ArrayList<>();
        final long expire;
        final int level;

        Pumpkin(final long expire, final int level) {
            this.expire = expire;
            this.level = level;
        }
    }

    private final class Trick {
        final UUID owner;
        final Set<UUID> mobs = new HashSet<>();
        final long expire;

        Trick(final UUID owner, final long expire) {
            this.owner = owner;
            this.expire = expire;
        }
    }

    private final Map<UUID, Pumpkin> pumpkins = new HashMap<>(); // 互動實體 → 南瓜
    private final Map<UUID, Trick> tricks = new HashMap<>();

    FestivalService(final ExileCore plugin, final FestivalConfig config) {
        this.plugin = plugin;
        this.config = config;
        this.key = new NamespacedKey(plugin, "festival");
        this.random = plugin.items().random();
    }

    public FestivalConfig config() {
        return config;
    }

    public boolean running() {
        return config.running(System.currentTimeMillis());
    }

    public boolean shopOpen() {
        return config.shopOpen(System.currentTimeMillis());
    }

    /** 選單上要不要出現（活動開始到兌換商店關掉）。 */
    public boolean visible() {
        return shopOpen();
    }

    // ---- 稱號 ----

    void registerTitles() {
        final BountyService bounties = plugin.bounties();
        if (bounties == null) {
            return;
        }
        final Map<String, BountyConfig.Title> titles = new LinkedHashMap<>();
        for (final FestivalConfig.Title title : config.titles().values()) {
            titles.put(title.id(), new BountyConfig.Title(title.id(), title.name(), title.color(), 0, title.stats()));
        }
        bounties.registerTitles(titles);
        bounties.setTitleSource("festival", new BountyService.TitleSource() {
            @Override
            public Set<String> owned(final Map<String, String> progress) {
                return Set.of();
            }

            @Override
            public List<String> howTo(final Player player, final String id) {
                if (!titles.containsKey(id)) {
                    return null;
                }
                return List.of(id.equals(config.challenges().title())
                        ? "<gray>" + config.name() + "：完成全部的活動挑戰（選單 → " + config.name() + "）"
                        : "<gray>" + config.name() + "：選單 → " + config.name() + " 用糖果兌換");
            }

            @Override
            public String sourceName(final String id) {
                return titles.containsKey(id) ? config.name() + "限定" : null;
            }

            @Override
            public boolean listed(final Player player, final String id) {
                return !titles.containsKey(id) || shopOpen() || plugin.bounties().ownedTitles(player).contains(id);
            }
        });
    }

    // ---- 每秒 ----

    void tick() {
        final long now = System.currentTimeMillis();
        tickCount++;
        loadState();
        ensureArena();
        final boolean running = config.running(now);
        tickAmbience(running);
        tickPumpkins(now);
        tickGhosts(now);
        if (king != null) {
            if (!running) {
                stopKing("活動結束了，" + config.boss().name() + "回到燈裡");
            } else {
                tickKing(now);
            }
        }
        if (running) {
            tickLanterns(now);
            scheduleKing(now);
        }
        if (stateDirty && tickCount % 30 == 0) {
            saveState();
        }
    }

    // ---- 場地 ----

    private World eventWorld() {
        return plugin.world() == null || plugin.world().worlds() == null ? null : plugin.world().worlds().event();
    }

    private Path arenaFile() {
        return plugin.dataFolder().resolve("festival_arena.txt");
    }

    private String arenaStamp() {
        final FestivalConfig.Arena arena = config.arena();
        return arena.x() + "," + arena.z() + "," + arena.radius() + "," + config.lanterns().count() + "," + config.lanterns().ring();
    }

    private void ensureArena() {
        if (arenaChecked || !config.shopOpen(System.currentTimeMillis())) {
            return;
        }
        final World world = eventWorld();
        if (world == null) {
            return; // 活動世界還沒載入：下次再檢查
        }
        arenaChecked = true;
        try {
            if (Files.exists(arenaFile()) && Files.readString(arenaFile()).trim().equals(arenaStamp())) {
                arenaReady = true;
                return;
            }
        } catch (final java.io.IOException e) {
            plugin.getSLF4JLogger().warn("{}：讀不到 festival_arena.txt：{}", config.name(), e.toString());
        }
        plugin.getSLF4JLogger().info("{}：在活動世界蓋幽燈墓園（{}）", config.name(), arenaStamp());
        buildArena(plugin.getServer().getConsoleSender());
    }

    /** 蓋（重蓋）幽燈墓園；回傳問題，成功 null。 */
    public String buildArena(final org.bukkit.command.CommandSender sender) {
        final World world = eventWorld();
        if (world == null) {
            return "活動世界沒有載入（看主控台）";
        }
        if (building) {
            return "正在蓋了";
        }
        if (king != null) {
            return "南瓜王還在場上，先 /ec festival stop";
        }
        building = true;
        arenaReady = false;
        FestivalArenaBuilder.center(world, config).getChunk().load();
        new FestivalArenaBuilder(plugin, sender, world, config, () -> {
            building = false;
            arenaReady = true;
            try {
                Files.writeString(arenaFile(), arenaStamp());
            } catch (final java.io.IOException e) {
                sender.sendMessage(plugin.text().prefixed("<red>墓園蓋好了，但記不進 festival_arena.txt：" + e.getMessage()));
            }
        }).start();
        return null;
    }

    public boolean arenaReady() {
        return arenaReady;
    }

    private Location center() {
        final World world = eventWorld();
        return world == null ? null : FestivalArenaBuilder.center(world, config);
    }

    /** 在幽燈墓園裡（多算牆外幾格）。 */
    public boolean inArena(final Location location) {
        final World world = eventWorld();
        if (world == null || location == null || !world.equals(location.getWorld())) {
            return false;
        }
        final double dx = location.getX() - (config.arena().x() + 0.5);
        final double dz = location.getZ() - (config.arena().z() + 0.5);
        final double r = config.arena().radius() + 6;
        return dx * dx + dz * dz <= r * r;
    }

    private List<Player> arenaPlayers() {
        final List<Player> list = new ArrayList<>();
        final World world = eventWorld();
        if (world == null) {
            return list;
        }
        for (final Player player : world.getPlayers()) {
            if (!player.isDead() && player.getGameMode() != org.bukkit.GameMode.SPECTATOR && plugin.players().get(player).hasCharacter()
                    && inArena(player.getLocation())) {
                list.add(player);
            }
        }
        return list;
    }

    /** 傳送到幽燈墓園（選單用）；回傳問題，成功 null。 */
    public String travel(final Player player) {
        if (!running()) {
            return config.name() + "沒有在進行";
        }
        if (!arenaReady) {
            return building ? "幽燈墓園還在蓋，等一下再來" : "幽燈墓園還沒蓋好（請通知管理員：/ec festival build）";
        }
        final Location center = center();
        final double angle = Math.PI / 2 + (random.nextDouble() - 0.5) * 0.6; // 南邊的入口附近
        final double distance = config.arena().radius() - 5;
        final Location to = center.clone().add(Math.cos(angle) * distance, 0, Math.sin(angle) * distance);
        to.setDirection(center.toVector().subtract(to.toVector()).setY(0));
        if (plugin.zones() == null || !plugin.zones().teleport(player, to, config.name())) {
            return "現在不能傳送";
        }
        player.sendMessage(plugin.text().prefixed("<" + config.color() + ">幽燈墓園<gray>：站在南瓜燈旁邊守 " + config.lanterns().guardSeconds()
                + " 秒點燈；回營地：<white>/town"));
        return null;
    }

    // ---- 夜晚與霧 ----

    private static final Particle.DustOptions MIST = new Particle.DustOptions(Color.fromRGB(150, 150, 172), 3.2f);
    private static final Particle.DustOptions MIST_DARK = new Particle.DustOptions(Color.fromRGB(70, 64, 92), 2.6f);

    private void tickAmbience(final boolean running) {
        final Set<UUID> now = new HashSet<>();
        if (running && arenaReady) {
            for (final Player player : arenaPlayers()) {
                now.add(player.getUniqueId());
                if (inside.add(player.getUniqueId())) {
                    player.setPlayerTime(18_000, false);
                    player.showTitle(Title.title(plugin.text().parse("<" + config.color() + ">幽燈墓園"),
                            plugin.text().parse("<gray>" + ItemRenderer.escape(config.name())),
                            Title.Times.times(Duration.ofMillis(400), Duration.ofMillis(1800), Duration.ofMillis(600))));
                }
                final Location at = player.getLocation();
                player.spawnParticle(Particle.DUST, at.clone().add(0, 0.3, 0), 26, 9, 0.25, 9, 0, MIST);
                player.spawnParticle(Particle.DUST, at.clone().add(0, 1.2, 0), 10, 12, 0.6, 12, 0, MIST_DARK);
                player.spawnParticle(Particle.WHITE_ASH, at.clone().add(0, 3, 0), 20, 10, 3, 10, 0);
                if (random.nextInt(4) == 0) {
                    player.spawnParticle(Particle.SOUL, at.clone().add(0, 0.5, 0), 3, 8, 0.3, 8, 0.01);
                }
            }
        }
        for (final Iterator<UUID> it = inside.iterator(); it.hasNext(); ) {
            final UUID id = it.next();
            if (!now.contains(id)) {
                it.remove();
                final Player player = plugin.getServer().getPlayer(id);
                if (player != null) {
                    player.resetPlayerTime();
                }
            }
        }
    }

    /** /ec reload 之後：場地、下一隻南瓜王的時間重新算（場地的座標、燈的數量可能改了）。 */
    void reloaded() {
        arenaChecked = false;
        arenaReady = false;
        if (king == null) {
            nextKingMillis = -1;
        }
    }

    void forget(final Player player) {
        inside.remove(player.getUniqueId());
        guards.remove(player.getUniqueId());
        if (king != null) {
            king.viewers.remove(player.getUniqueId()); // 重新登入要再顯示血條
        }
    }

    // ---- 點燈 ----

    private List<int[]> lanternSpots() {
        return FestivalArenaBuilder.lanternSpots(config);
    }

    private int allMask() {
        return (int) ((1L << config.lanterns().count()) - 1); // 2026-10-18：用 long 算（32 盞時 1 << 32 == 1，全滿永遠不成立）
    }

    /** 今天點了哪幾盞（位元）。 */
    public int litToday(final Player player) {
        final String value = progress(player, LAMP);
        final String today = config.day(System.currentTimeMillis());
        if (value == null || !value.startsWith(today + ":")) {
            return 0;
        }
        try {
            return Integer.parseInt(value.substring(today.length() + 1));
        } catch (final NumberFormatException e) {
            return 0;
        }
    }

    private void tickLanterns(final long now) {
        if (!arenaReady || plugin.bounties() == null) {
            return;
        }
        final List<int[]> spots = lanternSpots();
        final World world = eventWorld();
        final Set<UUID> seen = new HashSet<>();
        final boolean resend = tickCount % 5 == 0;
        for (final Player player : arenaPlayers()) {
            if (!plugin.bounties().accountLoaded(player)) {
                continue;
            }
            final int mask = litToday(player);
            if (resend) {
                for (int i = 0; i < spots.size(); i++) {
                    sendLantern(player, world, spots.get(i), (mask & 1 << i) != 0);
                }
            }
            final Location at = player.getLocation();
            int near = -1;
            for (int i = 0; i < spots.size(); i++) {
                final int[] spot = spots.get(i);
                final double dx = at.getX() - (spot[0] + 0.5);
                final double dz = at.getZ() - (spot[2] + 0.5);
                if (dx * dx + dz * dz <= 3.0 * 3.0 && Math.abs(at.getY() - spot[1]) < 3) {
                    near = i;
                    break;
                }
            }
            if (near < 0 || (mask & 1 << near) != 0) {
                if (near >= 0 && tickCount % 3 == 0) {
                    tw.exilecore.gui.ActionBar.send(player, plugin.text().parse("<" + config.color() + ">🎃 這盞今天已經點過了 <gray>（" + Integer.bitCount(mask) + "／"
                            + spots.size() + "）"));
                }
                continue;
            }
            seen.add(player.getUniqueId());
            final Guard before = guards.get(player.getUniqueId());
            final int seconds = before != null && before.lantern() == near ? before.seconds() + 1 : 1;
            final int need = config.lanterns().guardSeconds();
            final int[] spot = spots.get(near);
            final Location lamp = new Location(world, spot[0] + 0.5, spot[1] + 0.5, spot[2] + 0.5);
            if (seconds >= need) {
                guards.remove(player.getUniqueId());
                light(player, near, mask, lamp, now);
                continue;
            }
            guards.put(player.getUniqueId(), new Guard(near, seconds));
            final StringBuilder bar = new StringBuilder();
            for (int i = 0; i < need; i++) {
                bar.append(i < seconds ? "▮" : "▯");
            }
            tw.exilecore.gui.ActionBar.send(player, plugin.text().parse("<" + config.color() + ">點燈中 <white>" + bar + " <gray>" + seconds + "／" + need + " 秒"
                    + "<dark_gray>（離開就要重來）"));
            player.spawnParticle(Particle.SMALL_FLAME, lamp, 4 + seconds, 0.3, 0.3, 0.3, 0.01);
            player.playSound(lamp, Sound.BLOCK_CANDLE_AMBIENT, 0.6f, 0.8f + seconds * 0.06f);
        }
        guards.keySet().removeIf(id -> !seen.contains(id));
    }

    private void sendLantern(final Player player, final World world, final int[] spot, final boolean lit) {
        final String facing = FestivalArenaBuilder.facing(config.arena().x() - spot[0], config.arena().z() - spot[2]);
        player.sendBlockChange(new Location(world, spot[0], spot[1], spot[2]),
                plugin.getServer().createBlockData((lit ? "minecraft:jack_o_lantern" : "minecraft:carved_pumpkin") + "[facing=" + facing + "]"));
    }

    private void light(final Player player, final int lantern, final int mask, final Location lamp, final long now) {
        final int after = mask | 1 << lantern;
        final Map<String, String> values = new LinkedHashMap<>();
        values.put(LAMP, config.day(now) + ":" + after);
        final boolean full = after == allMask();
        int days = 0;
        if (full) {
            days = number(player, LAMP_DAYS) + 1;
            values.put(LAMP_DAYS, String.valueOf(days));
        }
        if (!plugin.bounties().commitAccount(player, values, config.name() + "點燈")) {
            return;
        }
        final World world = lamp.getWorld();
        sendLantern(player, world, lanternSpots().get(lantern), true);
        world.spawnParticle(Particle.FLAME, lamp, 30, 0.3, 0.5, 0.3, 0.04);
        world.spawnParticle(Particle.SOUL_FIRE_FLAME, lamp, 16, 0.4, 0.6, 0.4, 0.03);
        player.playSound(lamp, Sound.ITEM_FIRECHARGE_USE, 0.8f, 1.2f);
        player.playSound(lamp, Sound.ENTITY_VEX_CHARGE, 0.8f, 0.7f);
        tw.exilecore.gui.ActionBar.send(player, plugin.text().parse("<" + config.color() + ">🎃 點亮了！<gray>今天 " + Integer.bitCount(after) + "／"
                + config.lanterns().count() + " 盞——燈裡竄出了幽魂！"));
        spawnGhosts(lamp, Math.max(1, Math.min(90, plugin.players().get(player).level())), player);
        // 全服的燈
        globalCount++;
        stateDirty = true;
        if (globalCount >= config.lanterns().globalTarget()) {
            globalCount -= config.lanterns().globalTarget();
            bonusUntil = Math.max(now, bonusUntil) + config.lanterns().globalMinutes() * 60_000L;
            saveState();
            broadcast("<" + config.color() + ">🎃 " + ItemRenderer.escape(config.name()) + "<white>：全服點亮了 "
                    + config.lanterns().globalTarget() + " 盞燈！<gray>接下來 " + config.lanterns().globalMinutes()
                    + " 分鐘，全服物品數量 <white>+" + config.lanterns().globalQuantity() + "%");
            for (final Player online : plugin.getServer().getOnlinePlayers()) {
                online.playSound(online.getLocation(), Sound.UI_TOAST_CHALLENGE_COMPLETE, 0.6f, 1.2f);
            }
        }
        if (full) {
            giveCurrency(player, FestivalConfig.WICK, config.lanterns().dailyWick());
            player.sendMessage(plugin.text().prefixed("<" + config.color() + ">今天的 " + config.lanterns().count() + " 盞燈全部點亮了！"
                    + "<gray>燈芯 +" + config.lanterns().dailyWick() + "（點滿 " + days + "／" + config.challenges().lanternDays() + " 天）"));
            player.playSound(player.getLocation(), Sound.UI_TOAST_CHALLENGE_COMPLETE, 0.8f, 1f);
            checkChallenges(player);
        }
    }

    private void spawnGhosts(final Location at, final int level, final Player target) {
        final int[] range = config.lanterns().ghosts();
        final int count = range[0] + random.nextInt(Math.max(1, range[1] - range[0] + 1));
        final long expire = System.currentTimeMillis() + config.lanterns().ghostSeconds() * 1000L;
        for (int i = 0; i < count; i++) {
            final double angle = random.nextDouble() * Math.PI * 2;
            final Location spot = at.clone().add(Math.cos(angle) * 2, 0.5, Math.sin(angle) * 2);
            final LivingEntity ghost = spawnMob(config.lanterns().ghostType(), spot);
            if (ghost == null) {
                continue;
            }
            try {
                plugin.monsters().decorate(ghost, level, i == 0 && count >= 4 ? MonsterRarity.MAGIC : MonsterRarity.NORMAL, List.of());
            } catch (final RuntimeException e) {
                ghost.remove();
                continue;
            }
            ghost.customName(plugin.text().parse("<#b8a7ff>燈裡的幽魂"));
            tag(ghost, "ghost");
            ghosts.put(ghost.getUniqueId(), expire);
            if (ghost instanceof Mob mob && target != null) {
                mob.setTarget(target);
            }
        }
    }

    private void tickGhosts(final long now) {
        for (final Iterator<Map.Entry<UUID, Long>> it = ghosts.entrySet().iterator(); it.hasNext(); ) {
            final Map.Entry<UUID, Long> entry = it.next();
            final Entity entity = plugin.getServer().getEntity(entry.getKey());
            if (entity == null || !entity.isValid() || entity.isDead()) {
                it.remove();
            } else if (now >= entry.getValue()) {
                entity.getWorld().spawnParticle(Particle.SOUL, entity.getLocation(), 8, 0.3, 0.4, 0.3, 0.02);
                entity.remove();
                it.remove();
            }
        }
    }

    // ---- 全服點燈（存檔：plugins/ExileCore/festival_state.txt「點了幾盞,加成到幾點」）----

    private Path stateFile() {
        return plugin.dataFolder().resolve("festival_state.txt");
    }

    private void loadState() {
        if (stateLoaded) {
            return;
        }
        stateLoaded = true;
        try {
            if (Files.exists(stateFile())) {
                final String[] parts = Files.readString(stateFile()).trim().split(",");
                globalCount = Integer.parseInt(parts[0]);
                bonusUntil = parts.length > 1 ? Long.parseLong(parts[1]) : 0;
            }
        } catch (final java.io.IOException | NumberFormatException e) {
            plugin.getSLF4JLogger().warn("{}：讀不到 festival_state.txt：{}", config.name(), e.toString());
        }
    }

    void saveState() {
        if (!stateLoaded) {
            return;
        }
        stateDirty = false;
        try {
            Files.writeString(stateFile(), globalCount + "," + bonusUntil);
        } catch (final java.io.IOException e) {
            plugin.getSLF4JLogger().warn("{}：寫不進 festival_state.txt：{}", config.name(), e.toString());
        }
    }

    public int globalCount() {
        loadState();
        return globalCount;
    }

    /** 全服加成還剩幾分鐘（沒有 0）。 */
    public long bonusMinutesLeft() {
        loadState();
        final long left = bonusUntil - System.currentTimeMillis();
        return left <= 0 || !running() ? 0 : (left + 59_999) / 60_000;
    }

    /** 全服的物品數量加成（小數；MonthlyEventService.quantityBonus 加上去，LootBonus、地圖、劫盜都吃得到）。 */
    public double quantityBonus() {
        return bonusMinutesLeft() > 0 ? config.lanterns().globalQuantity() / 100.0 : 0;
    }

    /** 管理員：馬上開全服加成。 */
    public void triggerBonus() {
        loadState();
        bonusUntil = Math.max(System.currentTimeMillis(), bonusUntil) + config.lanterns().globalMinutes() * 60_000L;
        saveState();
        broadcast("<" + config.color() + ">🎃 " + ItemRenderer.escape(config.name()) + "<white>：萬燈齊亮！<gray>接下來 "
                + config.lanterns().globalMinutes() + " 分鐘，全服物品數量 <white>+" + config.lanterns().globalQuantity() + "%");
    }

    // ---- 南瓜王 ----

    public boolean kingRunning() {
        return king != null;
    }

    public long kingSecondsLeft() {
        return king == null ? 0 : Math.max(0, (king.endMillis - System.currentTimeMillis()) / 1000);
    }

    /** 下一次南瓜王（「20:00」；正在打、活動沒在進行 null）。 */
    public String nextKingClock() {
        if (king != null || nextKingMillis < 0 || !config.running(nextKingMillis)) {
            return null; // 最後一隻打完了（只剩兌換的那幾天）不顯示
        }
        final java.time.ZonedDateTime at = java.time.Instant.ofEpochMilli(nextKingMillis).atZone(config.timezone());
        final java.time.ZonedDateTime today = java.time.ZonedDateTime.now(config.timezone());
        return (at.toLocalDate().equals(today.toLocalDate()) ? "" : "明天 ")
                + at.format(java.time.format.DateTimeFormatter.ofPattern("HH:mm", Locale.ROOT));
    }

    private long nextKingAfter(final long now) {
        java.time.ZonedDateTime at = java.time.Instant.ofEpochMilli(now).atZone(config.timezone())
                .truncatedTo(java.time.temporal.ChronoUnit.HOURS).plusHours(1);
        for (int i = 0; i < 48 && !config.boss().hours().contains(at.getHour()); i++) {
            at = at.plusHours(1);
        }
        return at.toInstant().toEpochMilli();
    }

    private void scheduleKing(final long now) {
        if (nextKingMillis < 0) {
            nextKingMillis = nextKingAfter(now);
            kingWarned = false;
            return;
        }
        final int warn = config.boss().warnMinutes();
        if (!kingWarned && warn > 0 && now >= nextKingMillis - warn * 60_000L && king == null) {
            kingWarned = true;
            broadcast("<" + config.color() + ">🎃 " + ItemRenderer.escape(config.name()) + "<gray>：" + warn + " 分鐘後，<white>"
                    + ItemRenderer.escape(config.boss().name()) + "<gray> 會出現在幽燈墓園！（選單 → " + config.name() + " 傳送）");
        }
        if (now >= nextKingMillis) {
            if (king == null && config.running(nextKingMillis)) {
                final String problem = startKing();
                if (problem != null) {
                    plugin.getSLF4JLogger().warn("{}：南瓜王沒出來：{}", config.name(), problem);
                }
            }
            nextKingMillis = nextKingAfter(now);
            kingWarned = false;
        }
    }

    /** 叫出南瓜王（排程、管理員指令）；回傳問題，成功 null。 */
    public String startKing() {
        if (king != null) {
            return "南瓜王已經在場上了";
        }
        if (!arenaReady) {
            return "幽燈墓園還沒蓋好";
        }
        final Location center = center();
        center.getChunk().load();
        final FestivalConfig.Boss def = config.boss();
        final LivingEntity boss = spawnMob(def.type(), center.clone().add(0, 0.2, 0));
        if (boss == null) {
            return "南瓜王的類型 " + def.type() + " 生不出來";
        }
        try {
            plugin.monsters().fixPlayers(boss, 1);
            plugin.monsters().decorateBoss(boss, def.level(), def.name(), def.mods(), def.life(), def.damage(), def.scale());
        } catch (final RuntimeException e) {
            boss.remove();
            plugin.getSLF4JLogger().error("{}：南瓜王等級化失敗", config.name(), e);
            return "南瓜王等級化失敗（看主控台）";
        }
        final EntityEquipment equipment = boss.getEquipment();
        if (equipment != null) {
            equipment.setHelmet(new ItemStack(Material.JACK_O_LANTERN));
            equipment.setItemInMainHand(new ItemStack(Material.NETHERITE_HOE));
            equipment.setHelmetDropChance(0);
            equipment.setItemInMainHandDropChance(0);
        }
        final AttributeInstance follow = boss.getAttribute(Attribute.FOLLOW_RANGE);
        if (follow != null && follow.getBaseValue() < 48) {
            follow.setBaseValue(48);
        }
        tag(boss, "king");
        boss.setInvulnerable(true); // 2026-10-25：出場無敵 10 秒（tickKing 時間到解除）
        king = new King(center, System.currentTimeMillis());
        king.boss = boss;
        king.shieldUntil = System.currentTimeMillis() + SPAWN_SHIELD_MILLIS;
        king.mobs.add(boss.getUniqueId());
        attachSkills(boss);
        plugin.getSLF4JLogger().info("[Boss 紀錄] {}：{} Lv.{} 出現", config.name(), def.name(), def.level());
        broadcast("<" + config.color() + ">🎃 " + ItemRenderer.escape(config.name()) + "<white>：" + ItemRenderer.escape(def.name())
                + " 出現在幽燈墓園！<gray> " + def.durationMinutes() + " 分鐘內打倒牠（選單 → " + config.name() + " 傳送）");
        for (final Player player : plugin.getServer().getOnlinePlayers()) {
            player.playSound(player.getLocation(), Sound.ENTITY_WITHER_SPAWN, 0.5f, 1.4f);
        }
        for (final Player player : arenaPlayers()) {
            player.showTitle(Title.title(plugin.text().parse("<" + config.color() + ">" + ItemRenderer.escape(def.name())),
                    plugin.text().parse("<gray>萬燈夜的世界 Boss・" + SPAWN_SHIELD_MILLIS / 1000 + " 秒後開打"), Title.Times.times(Duration.ofMillis(300), Duration.ofMillis(2200), Duration.ofMillis(600))));
            if (!def.intro().isBlank() && !plugin.players().hidesMonsterTalk(player)) {
                player.sendMessage(plugin.text().parse("<" + config.color() + ">" + ItemRenderer.escape(def.name()) + "<white>："
                        + ItemRenderer.escape(def.intro())));
            }
        }
        return null;
    }

    /** 收掉南瓜王（不給獎勵）。 */
    public void stopKing(final String reason) {
        if (king == null) {
            return;
        }
        final King ending = king;
        king = null;
        cleanupKing(ending);
        if (reason != null) {
            broadcast("<" + config.color() + ">🎃 " + ItemRenderer.escape(config.name()) + "<gray>：" + ItemRenderer.escape(reason));
        }
    }

    private void cleanupKing(final King ending) {
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

    /** 2026-10-25：南瓜王出場後無敵幾毫秒（讓大家先集合）。 */
    static final long SPAWN_SHIELD_MILLIS = 10_000;

    private void tickKing(final long now) {
        final King event = king;
        final List<Player> near = arenaPlayers();
        if (event.boss != null && event.boss.isValid() && event.boss.isInvulnerable() && System.currentTimeMillis() >= event.shieldUntil) {
            event.boss.setInvulnerable(false); // 2026-10-25：出場的無敵時間到了
            for (final Player player : near) {
                tw.exilecore.gui.ActionBar.send(player, plugin.text().parse("<" + config.color() + ">" + ItemRenderer.escape(config.boss().name()) + "<white> 可以打了！"));
            }
        }
        for (final Player player : near) {
            event.participants.add(player.getUniqueId());
        }
        if (!near.isEmpty()) {
            event.lastSeenMillis = now;
        }
        if (now >= event.endMillis) {
            stopKing("時間到，" + config.boss().name() + "帶著燈火離開了");
            return;
        }
        if (now - event.lastSeenMillis > config.boss().emptyCloseSeconds() * 1000L) {
            stopKing("墓園裡沒有人，" + config.boss().name() + "回到燈裡了");
            return;
        }
        event.mobs.removeIf(id -> {
            final Entity entity = plugin.getServer().getEntity(id);
            return entity == null || !entity.isValid() || entity.isDead();
        });
        final LivingEntity boss = event.boss;
        if (boss != null && boss.isValid()) {
            if (boss instanceof Mob mob && !near.isEmpty() && !(mob.getTarget() instanceof Player)) {
                mob.setTarget(near.get(random.nextInt(near.size())));
            }
            final FestivalConfig.Boss def = config.boss();
            int strong = 0;
            for (final Player player : near) {
                if (player.getGameMode() != org.bukkit.GameMode.CREATIVE && plugin.players().get(player).level() >= def.countLevel()) {
                    strong++;
                }
            }
            final int players = Math.max(1, def.maxCounted() > 0 ? Math.min(def.maxCounted(), strong) : strong);
            if (players != event.players && plugin.monsters().setLifeMultiplier(boss, def.life() * (1 + def.lifePerPlayer() * (players - 1)))) {
                final boolean more = players > event.players;
                event.players = players;
                for (final Player player : near) {
                    player.sendMessage(plugin.text().prefixed("<" + config.color() + ">" + ItemRenderer.escape(def.name()) + " 的生命照 " + players
                            + " 位 " + def.countLevel() + " 等以上的玩家重算" + (more ? "（變多）" : "（變少）") + "<gray>，血量比例不變"));
                }
            }
            final AttributeInstance max = boss.getAttribute(Attribute.MAX_HEALTH);
            event.bar.progress((float) Math.max(0, Math.min(1, boss.getHealth() / Math.max(1, max == null ? 1 : max.getValue()))));
            if (tickCount % 2 == 0) {
                boss.getWorld().spawnParticle(Particle.SOUL_FIRE_FLAME, boss.getLocation().add(0, 2.4, 0), 6, 0.4, 0.3, 0.4, 0.02);
            }
        }
        final long left = Math.max(0, (event.endMillis - now) / 1000);
        event.bar.name(plugin.text().parse("<" + config.color() + ">" + ItemRenderer.escape(config.boss().name()) + " <gray>Lv." + config.boss().level()
                + " <dark_gray>｜ <white>" + String.format(Locale.ROOT, "%d:%02d", left / 60, left % 60)
                + (plugin.ailments() == null ? "" : plugin.ailments().bossBarSuffix(event.boss)))); // 2026-10-28 照站長：Boss 身上的異常
        final Set<UUID> show = new HashSet<>();
        for (final Player player : near) {
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

    private void attachSkills(final LivingEntity boss) {
        final FestivalConfig.Boss def = config.boss();
        if (plugin.skills() == null || def.skills().isEmpty()) {
            return;
        }
        plugin.skills().bossSkills().attach(boss, def.skills(), new tw.exilecore.skill.BossSkills.Host() {
            @Override
            public String name() {
                return def.name();
            }

            @Override
            public int phase() {
                return 0;
            }

            @Override
            public String tempoKind() {
                return "festival"; // 2026-10-17：南瓜王自己一個節奏（不普攻，出招比較快）
            }

            @Override
            public LivingEntity summon(final String type, final Location at) {
                final LivingEntity minion = spawnMob(type.toUpperCase(Locale.ROOT), at);
                if (minion == null) {
                    return null;
                }
                plugin.monsters().markSummoned(minion);
                plugin.monsters().decorate(minion, def.level(), MonsterRarity.NORMAL, List.of());
                tag(minion, "minion");
                if (king != null) {
                    king.mobs.add(minion.getUniqueId());
                }
                return minion;
            }
        });
    }

    private void kingDefeated() {
        final King event = king;
        king = null;
        final FestivalConfig.Boss def = config.boss();
        final List<Player> players = new ArrayList<>();
        for (final UUID id : event.participants) {
            final Player player = plugin.getServer().getPlayer(id);
            if (player != null && inArena(player.getLocation()) && plugin.players().get(player).hasCharacter()) {
                players.add(player);
            }
        }
        plugin.getSLF4JLogger().info("[Boss 紀錄] {}：{} 倒下：{} 秒、算 {} 人、{} 人拿到獎勵", config.name(), def.name(),
                String.format(Locale.ROOT, "%.1f", (System.currentTimeMillis() - event.startMillis) / 1000.0), event.players, players.size());
        for (final Player player : players) {
            final LootBonus.Bonus bonus = LootBonus.of(plugin, player);
            final int[] range = def.wick();
            giveCurrency(player, FestivalConfig.WICK, bonus.amount(range[0] + random.nextInt(Math.max(1, range[1] - range[0] + 1)), random));
            giveCurrency(player, FestivalConfig.CANDY, bonus.amount(def.candy(), random));
            if (random.nextDouble() < bonus.rare(def.cosmeticChance())) {
                final String cosmetic = randomUnowned(player);
                if (cosmetic != null && grantCosmetic(player, cosmetic)) {
                    broadcast("<" + config.color() + ">🎃 " + ItemRenderer.escape(player.getName()) + " 從" + ItemRenderer.escape(def.name())
                            + "的南瓜殼裡翻出了限定時裝 <white>" + ItemRenderer.escape(cosmeticName(cosmetic)) + "<" + config.color() + ">！");
                }
            }
            if (plugin.bounties() != null && plugin.bounties().accountLoaded(player)) {
                plugin.bounties().commitAccount(player, Map.of(KING, String.valueOf(number(player, KING) + 1)), config.name() + "南瓜王");
                checkChallenges(player);
            }
            player.playSound(player.getLocation(), Sound.UI_TOAST_CHALLENGE_COMPLETE, 0.8f, 1f);
        }
        cleanupKing(event);
        if (!def.defeat().isBlank()) {
            talk("<" + config.color() + ">" + ItemRenderer.escape(def.defeat())); // 2026-10-22：開了「隱藏怪物對話」的不送
        }
        broadcast("<" + config.color() + ">🎃 " + ItemRenderer.escape(config.name()) + "<white>：" + ItemRenderer.escape(def.name()) + " 倒下了！<gray>"
                + players.size() + " 位冒險者拿到了燈芯與糖果");
    }

    // ---- 糖果、搗蛋南瓜（MonsterListener 呼叫）----

    /** 地圖裡的怪倒下，有份的每個人各擲一次糖果。 */
    public void onKill(final Player player, final LivingEntity entity, final MonsterData data) {
        if (!running() || plugin.maps() == null || plugin.maps().instanceOf(entity) == null) {
            return;
        }
        final FestivalConfig.Candy candy = config.candy();
        // 終局以上的 Boss 不吃物品數量（跟一般掉落一樣）
        final LootBonus.Bonus bonus = plugin.maps().flatBossLoot(entity) ? LootBonus.Bonus.NONE : LootBonus.of(plugin, player);
        final int amount;
        if (data.boss()) {
            amount = bonus.amount(candy.boss(), random);
        } else {
            amount = switch (data.rarity()) {
                case RARE, UNIQUE -> bonus.amount(candy.rare(), random);
                case MAGIC -> random.nextDouble() < bonus.more(candy.magic()) ? 1 : 0;
                default -> random.nextDouble() < bonus.more(candy.normal()) ? 1 : 0;
            };
        }
        if (amount > 0) {
            giveCurrency(player, FestivalConfig.CANDY, amount);
        }
    }

    /** 地圖裡的稀有怪、Boss 倒下（一隻一次）：有機會留下搗蛋南瓜。 */
    public void onMonsterDeath(final LivingEntity entity, final MonsterData data) {
        if (!running() || plugin.maps() == null || plugin.maps().instanceOf(entity) == null) {
            return;
        }
        final double chance = data.boss() ? config.candy().pumpkinBoss()
                : data.rarity() == MonsterRarity.RARE || data.rarity() == MonsterRarity.UNIQUE ? config.candy().pumpkinRare() : 0;
        if (chance <= 0 || random.nextDouble() >= chance) {
            return;
        }
        spawnPumpkin(entity.getLocation(), data.level());
    }

    /** 放一個搗蛋南瓜（管理員測試也用）。 */
    public void spawnPumpkin(final Location location, final int level) {
        final World world = location.getWorld();
        final Location at = location.getBlock().getLocation().add(0.5, 0, 0.5);
        final Pumpkin pumpkin = new Pumpkin(System.currentTimeMillis() + config.candy().pumpkinSeconds() * 1000L, level);
        final Interaction hitbox = world.spawn(at, Interaction.class, created -> {
            created.setInteractionWidth(1.1f);
            created.setInteractionHeight(1.2f);
            created.setResponsive(true);
            created.setPersistent(false);
        });
        final ItemDisplay display = world.spawn(at.clone().add(0, 0.55, 0), ItemDisplay.class, created -> {
            created.setItemStack(new ItemStack(Material.JACK_O_LANTERN));
            created.setTransformation(new Transformation(new Vector3f(), new AxisAngle4f(), new Vector3f(1.05f, 1.05f, 1.05f), new AxisAngle4f()));
            created.setGlowing(true);
            created.setGlowColorOverride(Color.fromRGB(0xff8a1e));
            created.setPersistent(false);
        });
        final TextDisplay label = world.spawn(at.clone().add(0, 1.55, 0), TextDisplay.class, created -> {
            created.text(plugin.text().parse("<" + config.color() + ">🎃 搗蛋南瓜\n<gray>右鍵：敲門"));
            created.setBillboard(Display.Billboard.CENTER);
            created.setPersistent(false);
        });
        for (final Entity part : List.of(hitbox, display, label)) {
            part.getPersistentDataContainer().set(key, PersistentDataType.STRING, "pumpkin");
            pumpkin.parts.add(part.getUniqueId());
        }
        pumpkins.put(hitbox.getUniqueId(), pumpkin);
        world.spawnParticle(Particle.SOUL, at.clone().add(0, 0.6, 0), 12, 0.4, 0.4, 0.4, 0.02);
        world.playSound(at, Sound.ENTITY_WITCH_CELEBRATE, 0.8f, 1.1f);
        for (final Player player : world.getPlayers()) {
            if (player.getLocation().distanceSquared(at) < 40 * 40) {
                tw.exilecore.gui.ActionBar.send(player, plugin.text().parse("<" + config.color() + ">🎃 地上冒出了一個搗蛋南瓜！<gray>右鍵敲門"));
            }
        }
    }

    private void removePumpkin(final Pumpkin pumpkin) {
        for (final UUID id : pumpkin.parts) {
            final Entity entity = plugin.getServer().getEntity(id);
            if (entity != null) {
                entity.remove();
            }
        }
    }

    /** 這是不是南瓜王（每秒傷害上限跟開天者一樣，照 maps.yml 的 final_dps；MapService.capBossDamage）。 */
    public boolean isKing(final Entity entity) {
        return "king".equals(role(entity));
    }

    /** 這是不是搗蛋南瓜的感應框（右鍵沿視線找實體時用：拿著武器時客戶端的準星停不到它）。 */
    public boolean isPumpkin(final Entity entity) {
        return pumpkins.containsKey(entity.getUniqueId());
    }

    /** 右鍵了一個實體（FestivalModule）：是搗蛋南瓜就處理，回傳要不要取消事件。 */
    boolean interact(final Player player, final Entity entity) {
        final Pumpkin pumpkin = pumpkins.remove(entity.getUniqueId());
        if (pumpkin == null) {
            return false;
        }
        removePumpkin(pumpkin);
        final Location at = entity.getLocation();
        if (plugin.bounties() != null && plugin.bounties().accountLoaded(player)) {
            plugin.bounties().commitAccount(player, Map.of(PUMPKIN, String.valueOf(number(player, PUMPKIN) + 1)), config.name() + "搗蛋南瓜");
        }
        final FestivalConfig.Candy candy = config.candy();
        if (random.nextDouble() < candy.trickChance()) {
            // 搗蛋：竄出幽魂，限時清完拿雙倍
            final Trick trick = new Trick(player.getUniqueId(), System.currentTimeMillis() + candy.trickSeconds() * 1000L);
            final int count = candy.trickGhosts()[0] + random.nextInt(Math.max(1, candy.trickGhosts()[1] - candy.trickGhosts()[0] + 1));
            final UUID trickId = UUID.randomUUID();
            for (int i = 0; i < count; i++) {
                final double angle = Math.PI * 2 * i / count;
                final LivingEntity ghost = spawnMob(config.lanterns().ghostType(), at.clone().add(Math.cos(angle) * 1.5, 0.8, Math.sin(angle) * 1.5));
                if (ghost == null) {
                    continue;
                }
                try {
                    plugin.monsters().decorate(ghost, pumpkin.level, MonsterRarity.NORMAL, List.of());
                    plugin.monsters().markSummoned(ghost); // 不掉一般的東西（獎勵是雙倍糖果）
                } catch (final RuntimeException e) {
                    ghost.remove();
                    continue;
                }
                ghost.customName(plugin.text().parse("<#b8a7ff>搗蛋的幽魂"));
                tag(ghost, "trick:" + trickId);
                trick.mobs.add(ghost.getUniqueId());
                if (ghost instanceof Mob mob) {
                    mob.setTarget(player);
                }
            }
            if (trick.mobs.isEmpty()) {
                treat(player);
                return true;
            }
            tricks.put(trickId, trick);
            at.getWorld().playSound(at, Sound.ENTITY_WITCH_CELEBRATE, 1f, 0.6f);
            at.getWorld().spawnParticle(Particle.WITCH, at.clone().add(0, 0.8, 0), 40, 0.6, 0.6, 0.6, 0.05);
            player.showTitle(Title.title(plugin.text().parse("<#b8a7ff>不給糖就搗蛋！"),
                    plugin.text().parse("<gray>" + candy.trickSeconds() + " 秒內打倒幽魂，拿雙倍糖果"),
                    Title.Times.times(Duration.ofMillis(200), Duration.ofMillis(1600), Duration.ofMillis(400))));
        } else {
            treat(player);
            at.getWorld().spawnParticle(Particle.HAPPY_VILLAGER, at.clone().add(0, 0.8, 0), 14, 0.5, 0.5, 0.5, 0);
        }
        checkChallenges(player);
        return true;
    }

    private void treat(final Player player) {
        final int[] range = config.candy().treat();
        final int amount = LootBonus.of(plugin, player).amount(range[0] + random.nextInt(Math.max(1, range[1] - range[0] + 1)), random);
        giveCurrency(player, FestivalConfig.CANDY, amount);
        player.playSound(player.getLocation(), Sound.ENTITY_PLAYER_LEVELUP, 0.6f, 1.6f);
        if (!plugin.players().hidesSystem(player)) { // 2026-10-25：過濾系統訊息
            player.sendMessage(plugin.text().prefixed("<" + config.color() + ">🎃 給糖！<gray>糖果 +" + amount));
        }
    }

    private void tickPumpkins(final long now) {
        for (final Iterator<Pumpkin> it = pumpkins.values().iterator(); it.hasNext(); ) {
            final Pumpkin pumpkin = it.next();
            boolean gone = now >= pumpkin.expire;
            if (!gone) {
                final Entity hitbox = plugin.getServer().getEntity(pumpkin.parts.getFirst());
                gone = hitbox == null || !hitbox.isValid();
            }
            if (gone) {
                removePumpkin(pumpkin);
                it.remove();
            }
        }
        for (final Iterator<Map.Entry<UUID, Trick>> it = tricks.entrySet().iterator(); it.hasNext(); ) {
            final Trick trick = it.next().getValue();
            trick.mobs.removeIf(id -> {
                final Entity entity = plugin.getServer().getEntity(id);
                return entity == null || !entity.isValid() || entity.isDead();
            });
            if (now >= trick.expire) {
                for (final UUID id : trick.mobs) {
                    final Entity entity = plugin.getServer().getEntity(id);
                    if (entity != null) {
                        entity.getWorld().spawnParticle(Particle.SOUL, entity.getLocation(), 8, 0.3, 0.4, 0.3, 0.02);
                        entity.remove();
                    }
                }
                it.remove();
                final Player owner = plugin.getServer().getPlayer(trick.owner);
                if (owner != null && !trick.mobs.isEmpty()) {
                    owner.sendMessage(plugin.text().prefixed("<gray>搗蛋的幽魂笑著散掉了……這次沒拿到糖果"));
                }
            }
        }
    }

    // ---- 死亡（FestivalModule）----

    void onDeath(final LivingEntity entity, final Player killer) {
        final String role = role(entity);
        if (role == null) {
            return;
        }
        if (plugin.skills() != null) {
            plugin.skills().bossSkills().detach(entity.getUniqueId());
        }
        if (role.equals("king")) {
            if (king != null && king.boss != null && king.boss.getUniqueId().equals(entity.getUniqueId())) {
                kingDefeated();
            }
            return;
        }
        if (role.equals("ghost")) {
            ghosts.remove(entity.getUniqueId());
            if (killer != null && running() && random.nextDouble() < LootBonus.of(plugin, killer).more(0.3)) {
                giveCurrency(killer, FestivalConfig.CANDY, 1);
            }
            return;
        }
        if (role.startsWith("trick:")) {
            final UUID id;
            try {
                id = UUID.fromString(role.substring("trick:".length()));
            } catch (final IllegalArgumentException e) {
                return;
            }
            final Trick trick = tricks.get(id);
            if (trick == null) {
                return;
            }
            trick.mobs.remove(entity.getUniqueId());
            if (trick.mobs.isEmpty()) {
                tricks.remove(id);
                final Player owner = plugin.getServer().getPlayer(trick.owner);
                if (owner != null) {
                    final int[] range = config.candy().trickReward();
                    final int amount = LootBonus.of(plugin, owner).amount(range[0] + random.nextInt(Math.max(1, range[1] - range[0] + 1)), random);
                    giveCurrency(owner, FestivalConfig.CANDY, amount);
                    owner.playSound(owner.getLocation(), Sound.UI_TOAST_CHALLENGE_COMPLETE, 0.7f, 1.4f);
                    owner.sendMessage(plugin.text().prefixed("<" + config.color() + ">🎃 搗蛋的幽魂都打跑了！<gray>雙倍糖果 +" + amount));
                }
            }
        }
    }

    // ---- 挑戰 ----

    /** 一個挑戰的狀態（選單用）。 */
    public record Challenge(String name, int progress, int goal, String reward, boolean done) {
    }

    public List<Challenge> challenges(final Player player) {
        final FestivalConfig.Challenges c = config.challenges();
        final List<Challenge> list = new ArrayList<>();
        list.add(new Challenge("打倒" + config.boss().name() + " " + c.kingCount() + " 次", Math.min(c.kingCount(), number(player, KING)), c.kingCount(),
                "燈芯 ×" + c.kingWick(), progress(player, DONE + "king") != null));
        list.add(new Challenge("敲 " + c.pumpkinCount() + " 個搗蛋南瓜", Math.min(c.pumpkinCount(), number(player, PUMPKIN)), c.pumpkinCount(),
                "糖果 ×" + c.pumpkinCandy(), progress(player, DONE + "pumpkin") != null));
        list.add(new Challenge(c.lanternDays() + " 天點滿全部的燈", Math.min(c.lanternDays(), number(player, LAMP_DAYS)), c.lanternDays(),
                "燈芯 ×" + c.lanternWick(), progress(player, DONE + "lamp") != null));
        return list;
    }

    private void checkChallenges(final Player player) {
        if (plugin.bounties() == null || !plugin.bounties().accountLoaded(player)) {
            return;
        }
        final FestivalConfig.Challenges c = config.challenges();
        claim(player, "king", number(player, KING) >= c.kingCount(), FestivalConfig.WICK, c.kingWick(),
                "打倒" + config.boss().name() + " " + c.kingCount() + " 次");
        claim(player, "pumpkin", number(player, PUMPKIN) >= c.pumpkinCount(), FestivalConfig.CANDY, c.pumpkinCandy(),
                "敲了 " + c.pumpkinCount() + " 個搗蛋南瓜");
        claim(player, "lamp", number(player, LAMP_DAYS) >= c.lanternDays(), FestivalConfig.WICK, c.lanternWick(),
                c.lanternDays() + " 天點滿全部的燈");
        if (!c.title().isEmpty() && progress(player, DONE + "title") == null && progress(player, DONE + "king") != null
                && progress(player, DONE + "pumpkin") != null && progress(player, DONE + "lamp") != null) {
            final FestivalConfig.Title title = config.titles().get(c.title());
            // 先發稱號（失敗下次再試），成功了才記「完成」
            if (!plugin.bounties().ownedTitles(player).contains(c.title())) {
                final String problem = plugin.bounties().grantTitle(player, c.title());
                if (problem != null) {
                    plugin.getSLF4JLogger().warn("{}：{} 拿不到稱號 {}：{}", config.name(), player.getName(), c.title(), problem);
                    return;
                }
            }
            if (!plugin.bounties().commitAccount(player, Map.of(DONE + "title", "1"), config.name() + "挑戰")) {
                return;
            }
            broadcast("<" + config.color() + ">🎃 " + ItemRenderer.escape(player.getName()) + " 完成了" + ItemRenderer.escape(config.name())
                    + "的全部挑戰，拿到稱號【" + ItemRenderer.escape(title == null ? c.title() : title.name()) + "】！");
        }
    }

    private void claim(final Player player, final String id, final boolean reached, final String currency, final int amount, final String what) {
        if (!reached || progress(player, DONE + id) != null) {
            return;
        }
        if (!plugin.bounties().commitAccount(player, Map.of(DONE + id, "1"), config.name() + "挑戰")) {
            return;
        }
        giveCurrency(player, currency, amount);
        player.sendMessage(plugin.text().prefixed("<" + config.color() + ">✦ " + ItemRenderer.escape(config.name()) + "挑戰完成：<white>"
                + ItemRenderer.escape(what) + "<gray>　" + ItemRenderer.escape(currencyName(currency)) + " +" + amount));
        player.playSound(player.getLocation(), Sound.UI_TOAST_CHALLENGE_COMPLETE, 0.8f, 1.2f);
    }

    // ---- 兌換 ----

    /** 時裝擁有了沒（贊助模組的帳號進度）。 */
    public boolean ownsCosmetic(final Player player, final String id) {
        return progress(player, COSMETIC + id) != null;
    }

    String cosmeticName(final String id) {
        if (plugin.sponsors() != null) {
            final var cosmetic = plugin.sponsors().config().cosmetics().get(id);
            if (cosmetic != null) {
                return cosmetic.name();
            }
        }
        return id;
    }

    private String randomUnowned(final Player player) {
        final List<String> pool = new ArrayList<>();
        for (final FestivalConfig.ShopItem item : config.shop()) {
            if (item.kind() == FestivalConfig.Kind.COSMETIC && !ownsCosmetic(player, item.cosmetic())) {
                pool.add(item.cosmetic());
            }
        }
        return pool.isEmpty() ? null : pool.get(random.nextInt(pool.size()));
    }

    private boolean grantCosmetic(final Player player, final String id) {
        if (plugin.bounties() == null || !plugin.bounties().accountLoaded(player)) {
            return false;
        }
        if (!plugin.bounties().commitAccount(player, Map.of(COSMETIC + id, "1"), config.name() + "時裝")) {
            return false;
        }
        player.sendMessage(plugin.text().prefixed("<#ff9ad5>收到時裝</#ff9ad5>：<white>" + ItemRenderer.escape(cosmeticName(id))
                + " <" + config.color() + ">★ " + ItemRenderer.escape(config.name()) + "限定<gray>　贊助 → 裝備幻化 套上去"));
        player.playSound(player.getLocation(), Sound.UI_TOAST_CHALLENGE_COMPLETE, 0.8f, 1.2f);
        return true;
    }

    /** 已經有了（時裝、稱號）。 */
    public boolean owned(final Player player, final FestivalConfig.ShopItem item) {
        return switch (item.kind()) {
            case COSMETIC -> ownsCosmetic(player, item.cosmetic());
            case TITLE -> plugin.bounties() != null && plugin.bounties().ownedTitles(player).contains(item.title());
            default -> false;
        };
    }

    /** 兌換；成功 null。 */
    String buy(final Player player, final FestivalConfig.ShopItem item) {
        if (!shopOpen()) {
            return config.name() + "的兌換已經結束了";
        }
        if (!plugin.players().get(player).hasCharacter()) {
            return "先選一個角色";
        }
        if (plugin.bounties() == null || !plugin.bounties().accountLoaded(player)) {
            return "帳號資料載入中，請稍等";
        }
        if (owned(player, item)) {
            return "已經有了";
        }
        final tw.exilecore.economy.Wallet wallet = plugin.economy().wallet();
        for (final Map.Entry<String, Integer> cost : item.cost().entrySet()) {
            if (wallet.available(player, cost.getKey()) < cost.getValue()) {
                return "素材不夠：" + currencyName(cost.getKey()) + " 要 " + cost.getValue() + "，你有 " + wallet.available(player, cost.getKey());
            }
        }
        // 2026-10-17：中途扣不到、或後面給不出去：已經扣的退回去（以前只記一條 log，素材就沒了）
        final Map<String, Integer> taken = new java.util.LinkedHashMap<>();
        for (final Map.Entry<String, Integer> cost : item.cost().entrySet()) {
            if (!wallet.take(player, cost.getKey(), cost.getValue())) {
                refundCosts(player, taken);
                return "素材不夠";
            }
            taken.put(cost.getKey(), cost.getValue());
        }
        switch (item.kind()) {
            case COSMETIC -> {
                if (!grantCosmetic(player, item.cosmetic())) {
                    plugin.getSLF4JLogger().warn("{}：{} 換時裝 {} 失敗（素材退回）", config.name(), player.getName(), item.cosmetic());
                    refundCosts(player, taken);
                    return "時裝寫不進帳號（素材退回了，請通知管理員）";
                }
            }
            case TITLE -> {
                final String problem = plugin.bounties().grantTitle(player, item.title());
                if (problem != null) {
                    plugin.getSLF4JLogger().warn("{}：{} 換稱號 {} 失敗：{}（素材退回）", config.name(), player.getName(), item.title(), problem);
                    refundCosts(player, taken);
                    return problem + "（素材退回了）";
                }
            }
            case CURRENCY -> giveCurrency(player, item.currency(), item.amount());
        }
        // 2026-10-18：兌換成功才記素材消耗（wallet.take 不記流水帳；用 EVENT_SHOP，記 EVENT 會跟活動產出抵掉）
        for (final Map.Entry<String, Integer> cost : taken.entrySet()) {
            plugin.economy().ledger().consumed(cost.getKey(), tw.exilecore.economy.EconomyLedger.EVENT_SHOP, cost.getValue());
        }
        plugin.players().markDirty(player);
        plugin.characters().save(player);
        plugin.getSLF4JLogger().info("[{}] {} 兌換 {}", config.name(), player.getName(), item.id());
        return null;
    }

    String currencyName(final String id) {
        final CurrencyType type = plugin.currencies() == null ? null : plugin.currencies().repository().get(id);
        return type == null ? id : type.name();
    }

    // ---- 共用 ----

    private String progress(final Player player, final String key) {
        return plugin.bounties() == null ? null : plugin.bounties().progressValue(player, key);
    }

    private int number(final Player player, final String key) {
        final String value = progress(player, key);
        if (value == null) {
            return 0;
        }
        try {
            return Integer.parseInt(value.trim());
        } catch (final NumberFormatException e) {
            return 0;
        }
    }

    /** 給素材：先進通貨袋，滿了背包，再滿掉在腳下（萬燈糖例外：通貨袋滿了，多的自動過濾掉，2026-10-16）。 */
    /** 兌換失敗：把已經扣的素材退回去。 */
    private void refundCosts(final Player player, final Map<String, Integer> taken) {
        // 2026-10-18：退回走 Wallet.deposit（倉庫通貨頁 → 通貨袋 → 背包），放不下的做成物品掉腳下；
        // 不走 giveCurrency（萬燈糖通貨袋滿了會被自動過濾掉，也不該再算一次產出）
        final tw.exilecore.economy.Wallet wallet = plugin.economy().wallet();
        for (final Map.Entry<String, Integer> cost : taken.entrySet()) {
            int left = wallet.deposit(player, cost.getKey(), cost.getValue());
            final CurrencyType type = plugin.currencies() == null ? null : plugin.currencies().repository().get(cost.getKey());
            while (left > 0 && type != null) {
                final int stack = Math.min(left, Math.max(1, type.stack()));
                wallet.dropFor(player, plugin.currencies().create(type, stack));
                left -= stack;
            }
        }
    }

    void giveCurrency(final Player player, final String currencyId, final int amount) {
        if (amount <= 0 || plugin.currencies() == null) {
            return;
        }
        final CurrencyType type = plugin.currencies().repository().get(currencyId);
        if (type == null) {
            return;
        }
        if (FestivalConfig.CANDY.equals(type.id()) && plugin.loot() != null) {
            final int accepted = plugin.loot().depositOrDiscard(player, plugin.players().get(player), type, amount);
            if (accepted >= 0) {
                if (accepted > 0 && plugin.economy() != null) {
                    plugin.economy().ledger().produced(type.id(), tw.exilecore.economy.EconomyLedger.EVENT, accepted);
                }
                tw.exilecore.gui.ActionBar.send(player, plugin.text().parse(accepted >= amount
                        ? "<" + config.color() + ">+" + amount + " " + ItemRenderer.escape(type.name())
                        : "<gray>" + ItemRenderer.escape(type.name()) + "滿了（上限 " + plugin.players().get(player).bag().pouch().cap()
                        + "），" + (accepted > 0 ? "收下 " + accepted + " 個、" : "") + "多的 " + (amount - accepted) + " 個自動過濾"));
                return;
            }
        }
        if (plugin.economy() != null) {
            plugin.economy().ledger().produced(type.id(), tw.exilecore.economy.EconomyLedger.EVENT, amount);
        }
        int left = amount;
        while (left > 0) {
            final int stack = Math.min(left, Math.max(1, type.stack()));
            final ItemStack item = plugin.currencies().create(type, stack);
            final ItemStack rest = plugin.loot() != null ? plugin.loot().stowCurrency(player, plugin.players().get(player), type, item)
                    : plugin.economy().wallet().give(player, item);
            plugin.economy().wallet().dropFor(player, rest);
            left -= stack;
        }
        tw.exilecore.gui.ActionBar.send(player, plugin.text().parse("<" + config.color() + ">+" + amount + " " + ItemRenderer.escape(type.name())));
    }

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
            living.setRemoveWhenFarAway(false);
            return living;
        }
        entity.remove();
        return null;
    }

    private void tag(final Entity entity, final String role) {
        entity.getPersistentDataContainer().set(key, PersistentDataType.STRING, role);
    }

    String role(final Entity entity) {
        return entity.getPersistentDataContainer().get(key, PersistentDataType.STRING);
    }

    /** 2026-10-22：Boss 的台詞送給全服（開了「隱藏怪物對話」的不送；不進 Discord）。 */
    private void talk(final String text) {
        final net.kyori.adventure.text.Component message = plugin.text().prefixed(text);
        for (final Player player : plugin.getServer().getOnlinePlayers()) {
            if (!plugin.players().hidesMonsterTalk(player)) {
                player.sendMessage(message);
            }
        }
    }

    private void broadcast(final String text) {
        final net.kyori.adventure.text.Component message = plugin.text().prefixed(text);
        for (final Player player : plugin.getServer().getOnlinePlayers()) {
            player.sendMessage(message);
        }
        plugin.getSLF4JLogger().info("[{}] {}", config.name(), net.kyori.adventure.text.serializer.plain.PlainTextComponentSerializer.plainText()
                .serialize(plugin.text().parse(text)));
        if (plugin.discord() != null) {
            plugin.discord().worldEvent(text);
        }
    }

    /** 關服：怪物、南瓜收掉，存檔。 */
    void shutdown() {
        if (king != null) {
            final King ending = king;
            king = null;
            cleanupKing(ending);
        }
        for (final Pumpkin pumpkin : pumpkins.values()) {
            removePumpkin(pumpkin);
        }
        pumpkins.clear();
        for (final Trick trick : tricks.values()) {
            for (final UUID id : trick.mobs) {
                final Entity entity = plugin.getServer().getEntity(id);
                if (entity != null) {
                    entity.remove();
                }
            }
        }
        tricks.clear();
        for (final UUID id : ghosts.keySet()) {
            final Entity entity = plugin.getServer().getEntity(id);
            if (entity != null) {
                entity.remove();
            }
        }
        ghosts.clear();
        for (final UUID id : inside) {
            final Player player = plugin.getServer().getPlayer(id);
            if (player != null) {
                player.resetPlayerTime();
            }
        }
        inside.clear();
        if (stateDirty) {
            saveState();
        }
    }

    /** 管理員狀態。 */
    public String status() {
        return String.format(Locale.ROOT, "%s：%s；墓園 %s；全服點燈 %d／%d%s；南瓜王 %s；搗蛋南瓜 %d 個",
                config.name(), running() ? "進行中" : shopOpen() ? "只剩兌換" : "沒有進行", arenaReady ? "蓋好了" : building ? "蓋到一半" : "還沒蓋",
                globalCount(), config.lanterns().globalTarget(), bonusMinutesLeft() > 0 ? "（全服加成還剩 " + bonusMinutesLeft() + " 分）" : "",
                king != null ? "在場上（剩 " + kingSecondsLeft() + " 秒）" : nextKingClock() == null ? "—" : "下一次 " + nextKingClock(), pumpkins.size());
    }
}
