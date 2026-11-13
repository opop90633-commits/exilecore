package tw.exilecore.summer;

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
import org.bukkit.block.Block;
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
import tw.exilecore.monster.DropTable;
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
 * 夏日活動「星潮祭」（2026-11-03 照站長）：星潮海灘、沙灘挖寶、西瓜大亂鬥、深潮海妖、漂流瓶、挑戰與兌換。設定在 data/summer_festival.yml。
 * 骨架照萬燈夜的 FestivalService；兩個活動各自獨立（各自的場地、狀態檔、素材、商店），可以同時進行。
 */
public final class SummerService {

    // 帳號進度（帳號共用）
    static final String DIG = "sf.dig";                 // 「20261104:位元」今天挖到了哪幾個藏寶點
    static final String DIG_DAYS = "sf.dig_days";       // 挖滿全部的天數
    static final String KRAKEN = "sf.kraken";           // 打倒海妖的次數
    static final String MELEE = "sf.melee";             // 參加過幾局大亂鬥（至少打破一顆）
    static final String BOTTLE = "sf.bottle";           // 打開過的漂流瓶
    static final String MAP = "sf.map";                 // 手上有一張藏寶圖（下一張地圖的 Boss 多掉東西）
    static final String DONE = "sf.c.";                 // 挑戰發過了：sf.c.kraken、sf.c.dig、sf.c.melee、sf.c.bottle、sf.c.title
    private static final String COSMETIC = "sp.cos.";   // 贊助模組的時裝擁有（SponsorService.COSMETIC）

    private final ExileCore plugin;
    private final SummerConfig config;
    private final NamespacedKey key;
    private final RandomGenerator random;

    // ---- 場地 ----
    private boolean arenaChecked;
    private boolean arenaReady;
    private boolean building;

    // ---- 全服打破的西瓜（滿潮）----
    private int globalCount;
    private long bonusUntil;
    private boolean stateLoaded;
    private boolean stateDirty;

    /** 現在在海灘上（傍晚）的人。 */
    private final Set<UUID> inside = new HashSet<>();
    private int tickCount;

    // ---- 挖寶：海盜寶箱的守衛 ----
    private final class Guard {
        final UUID owner;
        final Set<UUID> mobs = new HashSet<>();
        final long expire;

        Guard(final UUID owner, final long expire) {
            this.owner = owner;
            this.expire = expire;
        }
    }

    private final Map<UUID, Guard> guards = new HashMap<>();

    // ---- 西瓜大亂鬥 ----
    private final class Melon {
        final List<UUID> parts = new ArrayList<>();
        final boolean golden;
        final Location at;

        Melon(final boolean golden, final Location at) {
            this.golden = golden;
            this.at = at;
        }
    }

    private final class Round {
        final long startMillis;
        final long endMillis;
        final Map<UUID, Integer> points = new HashMap<>();
        final Map<UUID, Melon> melons = new HashMap<>(); // 互動實體 → 西瓜
        final BossBar bar;
        int secondsSinceSpawn = 999;
        int spawned;
        int broken;

        Round(final long now) {
            this.startMillis = now;
            this.endMillis = now + config.melee().seconds() * 1000L;
            this.bar = BossBar.bossBar(plugin.text().parse("<#7cff6b>🍉 西瓜大亂鬥"), 1f, BossBar.Color.GREEN, BossBar.Overlay.PROGRESS);
        }
    }

    private Round round;
    private long nextRoundMillis = -1;
    private boolean roundWarned;
    private final Set<UUID> roundViewers = new HashSet<>();

    // ---- 深潮海妖 ----
    private final class Kraken {
        final long startMillis;
        final long endMillis;
        final Location center;
        final Set<UUID> participants = new HashSet<>();
        final Set<UUID> mobs = new HashSet<>();
        final Set<UUID> viewers = new HashSet<>();
        final BossBar bar;
        LivingEntity boss;
        long lastSeenMillis;
        /** 出場後無敵到這個時間（大家先集合）。 */
        long shieldUntil;
        int players = 1;
        /** 2026-11-03：現在第幾階段（生命掉到 boss.phases 的比例以下就加 1；BossSkills 照 Host.phase() 解鎖寫 phase: 的招）。 */
        int phase;

        Kraken(final Location center, final long now) {
            this.center = center;
            this.startMillis = now;
            this.endMillis = now + config.boss().durationMinutes() * 60_000L;
            this.lastSeenMillis = now;
            this.bar = BossBar.bossBar(plugin.text().parse("<" + config.color() + ">" + ItemRenderer.escape(config.boss().name())), 1f,
                    BossBar.Color.BLUE, BossBar.Overlay.NOTCHED_10);
        }
    }

    private Kraken kraken;
    private long nextKrakenMillis = -1;
    private boolean krakenWarned;

    // ---- 漂流瓶 ----
    private final class Bottle {
        final List<UUID> parts = new ArrayList<>();
        final long expire;

        Bottle(final long expire) {
            this.expire = expire;
        }
    }

    private final Map<UUID, Bottle> bottles = new HashMap<>(); // 互動實體 → 漂流瓶

    SummerService(final ExileCore plugin, final SummerConfig config) {
        this.plugin = plugin;
        this.config = config;
        this.key = new NamespacedKey(plugin, "summer");
        this.random = plugin.items().random();
    }

    public SummerConfig config() {
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
        for (final SummerConfig.Title title : config.titles().values()) {
            titles.put(title.id(), new BountyConfig.Title(title.id(), title.name(), title.color(), 0, title.stats()));
        }
        bounties.registerTitles(titles);
        bounties.setTitleSource("summer", new BountyService.TitleSource() {
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
                        : "<gray>" + config.name() + "：選單 → " + config.name() + " 用貝殼兌換");
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
        tickBottles(now);
        tickGuards(now);
        if (tickCount % 10 == 0) {
            collectShovels(); // 2026-11-03 晚 照站長：挖寶鏟拿掉了（改成拿武器右鍵沙子），發出去的收回
        }
        if (kraken != null) {
            if (!running) {
                stopKraken("活動結束了，" + config.boss().name() + "沉回海裡");
            } else {
                tickKraken(now);
            }
        }
        if (round != null) {
            if (!running) {
                endRound(false);
            } else {
                tickRound(now);
            }
        }
        if (running) {
            scheduleKraken(now);
            scheduleRound(now);
        }
        if (stateDirty && tickCount % 30 == 0) {
            saveState();
        }
    }

    /** 每 2 tick：踩到西瓜就算打破（沒裝客戶端模組的人也不用點）。 */
    void tickFast() {
        final Round event = round;
        if (event == null || event.melons.isEmpty()) {
            return;
        }
        final List<Player> players = arenaPlayers();
        if (players.isEmpty()) {
            return;
        }
        for (final Map.Entry<UUID, Melon> entry : new ArrayList<>(event.melons.entrySet())) {
            final Melon melon = entry.getValue();
            for (final Player player : players) {
                final Location at = player.getLocation();
                final double dx = at.getX() - melon.at.getX();
                final double dz = at.getZ() - melon.at.getZ();
                if (dx * dx + dz * dz <= 0.8 * 0.8 && at.getY() > melon.at.getY() - 1.2 && at.getY() < melon.at.getY() + 1.2) {
                    breakMelon(player, entry.getKey());
                    break;
                }
            }
        }
    }

    // ---- 場地 ----

    private World eventWorld() {
        return plugin.world() == null || plugin.world().worlds() == null ? null : plugin.world().worlds().event();
    }

    private Path arenaFile() {
        return plugin.dataFolder().resolve("summer_arena.txt");
    }

    private String arenaStamp() {
        final SummerConfig.Arena arena = config.arena();
        return arena.x() + "," + arena.z() + "," + arena.radius() + ",v1";
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
            plugin.getSLF4JLogger().warn("{}：讀不到 summer_arena.txt：{}", config.name(), e.toString());
        }
        plugin.getSLF4JLogger().info("{}：在活動世界蓋星潮海灘（{}）", config.name(), arenaStamp());
        buildArena(plugin.getServer().getConsoleSender());
    }

    /** 蓋（重蓋）星潮海灘；回傳問題，成功 null。 */
    public String buildArena(final org.bukkit.command.CommandSender sender) {
        final World world = eventWorld();
        if (world == null) {
            return "活動世界沒有載入（看主控台）";
        }
        if (building) {
            return "正在蓋了";
        }
        if (kraken != null) {
            return "海妖還在場上，先 /ec summer boss stop";
        }
        if (round != null) {
            endRound(false);
        }
        building = true;
        arenaReady = false;
        SummerArenaBuilder.center(world, config).getChunk().load();
        new SummerArenaBuilder(plugin, sender, world, config, () -> {
            building = false;
            arenaReady = true;
            try {
                Files.writeString(arenaFile(), arenaStamp());
            } catch (final java.io.IOException e) {
                sender.sendMessage(plugin.text().prefixed("<red>海灘蓋好了，但記不進 summer_arena.txt：" + e.getMessage()));
            }
        }).start();
        return null;
    }

    public boolean arenaReady() {
        return arenaReady;
    }

    private Location center() {
        final World world = eventWorld();
        return world == null ? null : SummerArenaBuilder.center(world, config);
    }

    /**
     * 2026-11-06 照站長「深潮海妖把招式放在入口，玩家進入會死」：南邊棧道的入口（{@link #travel} 傳送進來的地方）是安全區，
     * 半徑 {@link #SHELTER_RADIUS} 格：海妖的招打不到、不會被盯上、隨機落點不挑；站在裡面打海妖不算傷害（MapService.capBossDamage），
     * 不能躲在裡面輸出。這次之前入口跟海灘其他地方一樣，剛進來就踩在還沒散的招式上。
     */
    static final double SHELTER_RADIUS = 9;

    /** 南邊入口安全區的中心（傳送點的中心：離中央 radius − 5 格、正南）。 */
    private double[] shelterCenter() {
        return new double[] {config.arena().x() + 0.5, config.arena().z() + 0.5 + config.arena().radius() - 5};
    }

    /** 這個位置在入口的安全區裡（海妖在場時才有意義）。 */
    public boolean inShelter(final Location location) {
        final World world = eventWorld();
        if (world == null || location == null || !world.equals(location.getWorld())) {
            return false;
        }
        final double[] c = shelterCenter();
        final double dx = location.getX() - c[0];
        final double dz = location.getZ() - c[1];
        return dx * dx + dz * dz <= SHELTER_RADIUS * SHELTER_RADIUS;
    }

    /** 2026-11-06：打海妖的人站在入口安全區裡（那一下不算）。 */
    public boolean shelteredAttacker(final UUID source) {
        final Player player = source == null ? null : plugin.getServer().getPlayer(source);
        return player != null && kraken != null && inShelter(player.getLocation());
    }

    /** 在星潮海灘裡（多算牆外幾格）。 */
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

    /** 傳送到星潮海灘（選單用）；回傳問題，成功 null。 */
    public String travel(final Player player) {
        if (!running()) {
            return config.name() + "沒有在進行";
        }
        if (!arenaReady) {
            return building ? "星潮海灘還在蓋，等一下再來" : "星潮海灘還沒蓋好（請通知管理員：/ec summer build）";
        }
        final Location center = center();
        final double angle = Math.PI / 2 + (random.nextDouble() - 0.5) * 0.5; // 南邊的入口附近（棧道）
        final double distance = config.arena().radius() - 5;
        final Location to = center.clone().add(Math.cos(angle) * distance, 0, Math.sin(angle) * distance);
        to.setDirection(center.toVector().subtract(to.toVector()).setY(0));
        if (plugin.zones() == null || !plugin.zones().teleport(player, to, config.name())) {
            return "現在不能傳送";
        }
        player.sendMessage(plugin.text().prefixed("<" + config.color() + ">星潮海灘<gray>：拿著武器<white>右鍵沙子</white>挖寶（動作條會說熱不熱；海妖、大亂鬥進行中右鍵照常放技能）；回營地：<white>/town"));
        if (kraken != null) { // 2026-11-06
            player.sendMessage(plugin.text().prefixed("<#9fe8c8>入口的綠圈是安全區<gray>：海妖的招打不到你，但站在裡面打海妖也不算傷害"));
        }
        return null;
    }

    // ---- 2026-11-03 照站長：星潮挖寶鏟（到海灘發一把；綁定帳號；活動結束收回） ----

    private NamespacedKey shovelKey() {
        return new NamespacedKey(plugin, "summer_shovel");
    }

    /** 星潮挖寶鏟。 */
    ItemStack shovelItem() {
        final ItemStack stack = new ItemStack(Material.GOLDEN_SHOVEL);
        stack.editMeta(meta -> {
            meta.displayName(ItemRenderer.line("<" + config.color() + ">星潮挖寶鏟"));
            meta.lore(List.of(
                    ItemRenderer.line("<gray>帶在身上，站在星潮海灘的沙子上蹲下 1 秒就挖寶；動作條會說熱（4 格內）、溫（8 格內）還是冷"),
                    ItemRenderer.line("<gray>每天每個人 " + config.dig().spotsPerDay() + " 個藏寶點"),
                    ItemRenderer.line("<dark_gray>" + ItemRenderer.escape(config.name()) + "活動道具：活動結束後收回"),
                    ItemRenderer.line("<dark_gray>綁定帳號（可以丟進垃圾桶）：弄丟了在選單「" + ItemRenderer.escape(config.name()) + " → 沙灘挖寶」再拿")));
            meta.setUnbreakable(true);
            meta.addItemFlags(org.bukkit.inventory.ItemFlag.HIDE_ATTRIBUTES, org.bukkit.inventory.ItemFlag.HIDE_UNBREAKABLE);
            tw.exilecore.item.AccountBound.mark(meta);
            meta.getPersistentDataContainer().set(shovelKey(), PersistentDataType.BYTE, (byte) 1);
        });
        return stack;
    }

    /** 2026-11-03：在星潮海灘上，這一格是挖寶鏟就讓玩家換過去拿（戰鬥區域本來鎖在第 9 格的武器）。 */
    public boolean canHoldShovel(final Player player, final ItemStack stack) {
        return isShovel(stack) && running() && inArena(player.getLocation());
    }

    public boolean isShovel(final ItemStack stack) {
        return stack != null && !stack.isEmpty() && stack.hasItemMeta()
                && stack.getItemMeta().getPersistentDataContainer().has(shovelKey(), PersistentDataType.BYTE);
    }

    /** 身上（游標、背包、隨身背包、倉庫）有沒有挖寶鏟。 */
    private boolean hasShovel(final Player player) {
        if (isShovel(player.getItemOnCursor())) {
            return true;
        }
        for (final ItemStack stack : player.getInventory().getContents()) {
            if (isShovel(stack)) {
                return true;
            }
        }
        final tw.exilecore.character.PlayerState state = plugin.players().get(player);
        if (state != null && state.bag() != null && state.bag().usable()) {
            for (int page = 0; page < state.bag().pages(); page++) {
                for (final ItemStack stack : state.bag().page(page)) {
                    if (isShovel(stack)) {
                        return true;
                    }
                }
            }
        }
        final tw.exilecore.stash.Stash stash = plugin.stash() == null ? null : plugin.stash().stash(player).orElse(null);
        if (stash != null) {
            for (int page = 0; page < stash.pageCount(); page++) {
                for (final ItemStack stack : stash.page(page)) {
                    if (isShovel(stack)) {
                        return true;
                    }
                }
            }
        }
        return false;
    }

    /** 給挖寶鏟（身上沒有才給）；回傳問題，給了 null。tell = 已經有的時候要不要說。 */
    public String giveShovel(final Player player, final boolean tell) {
        if (!running()) {
            return config.name() + "沒有在進行";
        }
        if (hasShovel(player)) {
            return tell ? "你已經有星潮挖寶鏟了（背包、隨身背包或倉庫裡）" : null;
        }
        // 2026-11-03：先放進快捷欄的空格（第 1～8 格），放不下才照一般的順序放（背包 → 隨身背包 → 腳下只有自己撿得到）
        int hotbar = -1;
        for (int i = 0; i < 8; i++) {
            final ItemStack there = player.getInventory().getItem(i);
            if ((there == null || there.isEmpty()) && !(plugin.flasks() != null && plugin.flasks().isFlaskSlot(player, i))) { // 藥劑那幾格不放
                hotbar = i;
                break;
            }
        }
        if (hotbar >= 0) {
            player.getInventory().setItem(hotbar, shovelItem());
            player.sendMessage(plugin.text().prefixed("<" + config.color() + ">拿到星潮挖寶鏟<gray>（快捷欄第 " + (hotbar + 1) + " 格）：帶在身上，站在海灘的沙子上蹲下 1 秒就挖"));
        } else {
            plugin.economy().wallet().dropFor(player, plugin.economy().wallet().give(player, shovelItem()));
            player.sendMessage(plugin.text().prefixed("<" + config.color() + ">拿到星潮挖寶鏟<gray>（在背包裡）：帶在身上，站在海灘的沙子上蹲下 1 秒就挖"));
        }
        return null;
    }

    /** 活動結束：收回在線玩家背包、游標、隨身背包裡的挖寶鏟。 */
    private void collectShovels() {
        for (final Player player : plugin.getServer().getOnlinePlayers()) {
            if (isShovel(player.getItemOnCursor())) {
                player.setItemOnCursor(null);
            }
            final ItemStack[] contents = player.getInventory().getContents();
            for (int i = 0; i < contents.length; i++) {
                if (isShovel(contents[i])) {
                    player.getInventory().setItem(i, null);
                }
            }
            final tw.exilecore.character.PlayerState state = plugin.players().get(player);
            if (state != null && state.bag() != null && state.bag().usable()) {
                for (int page = 0; page < state.bag().pages(); page++) {
                    final ItemStack[] items = state.bag().page(page);
                    for (int i = 0; i < items.length; i++) {
                        if (isShovel(items[i])) {
                            items[i] = null;
                            state.markDirty();
                        }
                    }
                }
            }
        }
    }

    // ---- 傍晚與海風 ----

    private void tickAmbience(final boolean running) {
        final Set<UUID> now = new HashSet<>();
        if (running && arenaReady) {
            for (final Player player : arenaPlayers()) {
                now.add(player.getUniqueId());
                if (inside.add(player.getUniqueId())) {
                    player.setPlayerTime(12_600, false); // 永遠傍晚（照萬燈夜固定夜晚的做法）
                    player.showTitle(Title.title(plugin.text().parse("<" + config.color() + ">星潮海灘"),
                            plugin.text().parse("<gray>" + ItemRenderer.escape(config.name())),
                            Title.Times.times(Duration.ofMillis(400), Duration.ofMillis(1800), Duration.ofMillis(600))));
                }
                if (tickCount % 3 == 0) {
                    final Location at = player.getLocation();
                    player.spawnParticle(Particle.SPLASH, at.clone().add(0, 0.4, 0), 6, 10, 0.2, 10, 0);
                    if (random.nextInt(6) == 0) {
                        player.playSound(at, Sound.AMBIENT_UNDERWATER_LOOP, 0.15f, 1.6f);
                    }
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

    /** /ec reload 之後：場地、下一次海妖與大亂鬥的時間重新算。 */
    void reloaded() {
        arenaChecked = false;
        arenaReady = false;
        if (kraken == null) {
            nextKrakenMillis = -1;
        }
        if (round == null) {
            nextRoundMillis = -1;
        }
    }

    void forget(final Player player) {
        inside.remove(player.getUniqueId());
        roundViewers.remove(player.getUniqueId());
        if (kraken != null) {
            kraken.viewers.remove(player.getUniqueId()); // 重新登入要再顯示血條
        }
    }

    // ---- 沙灘挖寶 ----

    private int allDigMask() {
        return (int) ((1L << config.dig().spotsPerDay()) - 1);
    }

    /** 今天挖到了哪幾個藏寶點（位元）。 */
    public int dugToday(final Player player) {
        final String value = progress(player, DIG);
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

    /** 這個人今天的藏寶點（種子 = 玩家 UUID＋日期）。 */
    private List<int[]> digSpots(final Player player) {
        final UUID id = player.getUniqueId();
        final long seed = id.getMostSignificantBits() * 31 + id.getLeastSignificantBits() ^ config.day(System.currentTimeMillis()).hashCode() * 1_000_003L;
        return SummerArenaBuilder.digSpots(config, seed);
    }

    /** 這一下是不是在海灘上拿鏟子挖沙（SkillInputListener：不當成放技能）。 */
    public boolean isDigging(final Player player, final ItemStack item, final Block block) {
        // 2026-11-03 晚 照站長：不用鏟子，拿著武器（手上任何東西）右鍵沙子就挖；海妖在場、大亂鬥進行中右鍵照常放技能
        //   （這次之前要拿鏟子，戰鬥區域手上鎖在武器、快捷欄是技能格，拿不出來）
        return block != null && kraken == null && round == null && isBeachSand(block);
    }

    private boolean isBeachSand(final Block block) {
        if (!arenaReady || block.getType() != Material.SAND || block.getY() != SummerArenaBuilder.FLOOR_Y - 1 || !inArena(block.getLocation())) {
            return false;
        }
        return SummerArenaBuilder.diggable(config, block.getX() - config.arena().x(), block.getZ() - config.arena().z());
    }

    /** 拿鏟子右鍵沙子（SummerModule）；回傳有沒有處理（要取消事件）。 */
    boolean dig(final Player player, final Block block) {
        if (!running() || !isBeachSand(block) || plugin.bounties() == null || !plugin.bounties().accountLoaded(player)
                || !plugin.players().get(player).hasCharacter()) {
            return false;
        }
        final int dx = block.getX() - config.arena().x();
        final int dz = block.getZ() - config.arena().z();
        final List<int[]> spots = digSpots(player);
        final int mask = dugToday(player);
        int nearest = -1;
        double best = Double.MAX_VALUE;
        for (int i = 0; i < spots.size(); i++) {
            if ((mask & 1 << i) != 0) {
                continue; // 今天挖過了
            }
            final double d = Math.hypot(spots.get(i)[0] - dx, spots.get(i)[1] - dz);
            if (d < best) {
                best = d;
                nearest = i;
            }
        }
        final Location at = block.getLocation().add(0.5, 1, 0.5);
        player.swingMainHand();
        block.getWorld().playSound(at, Sound.BLOCK_SAND_BREAK, 0.8f, 0.9f + random.nextFloat() * 0.2f);
        tw.exilecore.skill.Fx.spawn(block.getWorld(), Particle.BLOCK, at, 16, 0.3, 0.2, 0.3, 0, Material.SAND.createBlockData());
        if (nearest < 0) {
            tw.exilecore.gui.ActionBar.send(player, plugin.text().parse("<" + config.color() + ">今天的藏寶點都挖完了 <gray>（明天再來）"));
            return true;
        }
        if (best > 1.01) {
            final String hint = best <= 4 ? "<#ff6b4a>很熱！" : best <= 8 ? "<#ffd166>溫溫的" : "<#7fd4ff>冷冰冰";
            tw.exilecore.gui.ActionBar.send(player, plugin.text().parse(hint + " <gray>（" + Integer.bitCount(mask) + "／" + spots.size() + "）"));
            return true;
        }
        found(player, nearest, mask, at);
        return true;
    }

    private void found(final Player player, final int spot, final int mask, final Location at) {
        final int after = mask | 1 << spot;
        final Map<String, String> values = new LinkedHashMap<>();
        values.put(DIG, config.day(System.currentTimeMillis()) + ":" + after);
        final boolean full = after == allDigMask();
        int days = 0;
        if (full) {
            days = number(player, DIG_DAYS) + 1;
            values.put(DIG_DAYS, String.valueOf(days));
        }
        if (!plugin.bounties().commitAccount(player, values, config.name() + "挖寶")) {
            return;
        }
        final SummerConfig.Dig dig = config.dig();
        final World world = at.getWorld();
        world.playSound(at, Sound.ENTITY_PLAYER_LEVELUP, 0.7f, 1.5f);
        tw.exilecore.skill.Fx.spawn(world, Particle.HAPPY_VILLAGER, at, 14, 0.5, 0.5, 0.5, 0);
        // 先給一份貝殼；海盜寶箱的守衛限時清完再給一份（雙倍）
        giveCurrency(player, SummerConfig.SHELL, LootBonus.of(plugin, player).amount(dig.shells(), random));
        if (random.nextDouble() < dig.chestChance()) {
            final Guard guard = new Guard(player.getUniqueId(), System.currentTimeMillis() + dig.guardSeconds() * 1000L);
            final int count = dig.guards()[0] + random.nextInt(Math.max(1, dig.guards()[1] - dig.guards()[0] + 1));
            final UUID guardId = UUID.randomUUID();
            final int level = Math.max(1, Math.min(90, plugin.players().get(player).level()));
            for (int i = 0; i < count; i++) {
                final double angle = Math.PI * 2 * i / count;
                final LivingEntity mob = spawnMob(dig.guardType(), at.clone().add(Math.cos(angle) * 2, 0.2, Math.sin(angle) * 2));
                if (mob == null) {
                    continue;
                }
                try {
                    plugin.monsters().decorate(mob, level, i == 0 && count >= 4 ? MonsterRarity.MAGIC : MonsterRarity.NORMAL, List.of());
                    plugin.monsters().markSummoned(mob); // 不掉一般的東西（獎勵是雙倍貝殼）
                } catch (final RuntimeException e) {
                    mob.remove();
                    continue;
                }
                mob.customName(plugin.text().parse("<#5fb3c9>守寶的海盜"  /* 2026-11-03 照站長：衛道士（這次之前溺屍） */));
                tag(mob, "guard:" + guardId);
                guard.mobs.add(mob.getUniqueId());
                if (mob instanceof Mob m) {
                    m.setTarget(player);
                }
            }
            if (!guard.mobs.isEmpty()) {
                guards.put(guardId, guard);
                world.playSound(at, Sound.ENTITY_VINDICATOR_AMBIENT, 1f, 0.8f);
                tw.exilecore.skill.Fx.spawn(world, Particle.BUBBLE_POP, at, 30, 0.8, 0.6, 0.8, 0.05);
                player.showTitle(Title.title(plugin.text().parse("<#5fb3c9>海盜寶箱！"),
                        plugin.text().parse("<gray>" + dig.guardSeconds() + " 秒內打倒守衛，拿雙倍貝殼（有機會多一顆珍珠）"),
                        Title.Times.times(Duration.ofMillis(200), Duration.ofMillis(1600), Duration.ofMillis(400))));
            }
        }
        tw.exilecore.gui.ActionBar.send(player, plugin.text().parse("<" + config.color() + ">⛏ 挖到了！<gray>今天 " + Integer.bitCount(after) + "／"
                + dig.spotsPerDay() + " 個藏寶點"));
        if (full) {
            giveCurrency(player, SummerConfig.PEARL, dig.dailyPearl());
            player.sendMessage(plugin.text().prefixed("<" + config.color() + ">今天的 " + dig.spotsPerDay() + " 個藏寶點全部挖到了！"
                    + "<gray>珍珠 +" + dig.dailyPearl() + "（挖滿 " + days + "／" + config.challenges().digDays() + " 天）"));
            player.playSound(player.getLocation(), Sound.UI_TOAST_CHALLENGE_COMPLETE, 0.8f, 1f);
            checkChallenges(player);
        }
    }

    private void tickGuards(final long now) {
        for (final Iterator<Map.Entry<UUID, Guard>> it = guards.entrySet().iterator(); it.hasNext(); ) {
            final Guard guard = it.next().getValue();
            guard.mobs.removeIf(id -> {
                final Entity entity = plugin.getServer().getEntity(id);
                return entity == null || !entity.isValid() || entity.isDead();
            });
            if (now >= guard.expire) {
                for (final UUID id : guard.mobs) {
                    final Entity entity = plugin.getServer().getEntity(id);
                    if (entity != null) {
                        tw.exilecore.skill.Fx.spawn(entity.getWorld(), Particle.BUBBLE_POP, entity.getLocation(), 8, 0.3, 0.4, 0.3, 0.02);
                        entity.remove();
                    }
                }
                it.remove();
                final Player owner = plugin.getServer().getPlayer(guard.owner);
                if (owner != null && !guard.mobs.isEmpty()) {
                    owner.sendMessage(plugin.text().prefixed("<gray>守寶的海盜帶著寶箱逃回船上了……這次只有一份貝殼"));
                }
            }
        }
    }

    // ---- 全服打破的西瓜（存檔：plugins/ExileCore/summer_state.txt「打破幾顆,加成到幾點」）----

    private Path stateFile() {
        return plugin.dataFolder().resolve("summer_state.txt");
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
            plugin.getSLF4JLogger().warn("{}：讀不到 summer_state.txt：{}", config.name(), e.toString());
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
            plugin.getSLF4JLogger().warn("{}：寫不進 summer_state.txt：{}", config.name(), e.toString());
        }
    }

    public int globalCount() {
        loadState();
        return globalCount;
    }

    /** 全服加成（滿潮）還剩幾分鐘（沒有 0）。 */
    public long bonusMinutesLeft() {
        loadState();
        final long left = bonusUntil - System.currentTimeMillis();
        return left <= 0 || !running() ? 0 : (left + 59_999) / 60_000;
    }

    /** 全服的物品數量加成（小數；MonthlyEventService.quantityBonus 加上去，跟萬燈夜的加成相加）。 */
    public double quantityBonus() {
        return bonusMinutesLeft() > 0 ? config.melee().globalQuantity() / 100.0 : 0;
    }

    /** 管理員：馬上開全服加成（滿潮）。 */
    public void triggerBonus() {
        loadState();
        bonusUntil = Math.max(System.currentTimeMillis(), bonusUntil) + config.melee().globalMinutes() * 60_000L;
        saveState();
        broadcast("<" + config.color() + ">🌊 " + ItemRenderer.escape(config.name()) + "<white>：滿潮！<gray>接下來 "
                + config.melee().globalMinutes() + " 分鐘，全服物品數量 <white>+" + config.melee().globalQuantity() + "%");
    }

    // ---- 西瓜大亂鬥 ----

    public boolean roundRunning() {
        return round != null;
    }

    public long roundSecondsLeft() {
        return round == null ? 0 : Math.max(0, (round.endMillis - System.currentTimeMillis()) / 1000);
    }

    /** 下一局大亂鬥（「20:00」；正在打、活動沒在進行 null）。 */
    public String nextRoundClock() {
        return clock(round != null ? -1 : nextRoundMillis);
    }

    private String clock(final long millis) {
        if (millis < 0 || !config.running(millis)) {
            return null;
        }
        final java.time.ZonedDateTime at = java.time.Instant.ofEpochMilli(millis).atZone(config.timezone());
        final java.time.ZonedDateTime today = java.time.ZonedDateTime.now(config.timezone());
        return (at.toLocalDate().equals(today.toLocalDate()) ? "" : "明天 ")
                + at.format(java.time.format.DateTimeFormatter.ofPattern("HH:mm", Locale.ROOT));
    }

    /** 2026-11-03 照站長（時間錯開）：hours 那幾點的第 minute 分，now 之後的下一次；這次之前一律整點。 */
    private long nextHourAfter(final long now, final java.util.SortedSet<Integer> hours, final int minute) {
        final java.time.ZonedDateTime current = java.time.Instant.ofEpochMilli(now).atZone(config.timezone());
        java.time.ZonedDateTime at = current.truncatedTo(java.time.temporal.ChronoUnit.HOURS).plusMinutes(minute);
        if (!at.isAfter(current)) {
            at = at.plusHours(1);
        }
        for (int i = 0; i < 48 && !hours.contains(at.getHour()); i++) {
            at = at.plusHours(1);
        }
        return at.toInstant().toEpochMilli();
    }

    private void scheduleRound(final long now) {
        if (nextRoundMillis < 0) {
            nextRoundMillis = nextHourAfter(now, config.melee().hours(), config.meleeMinute());
            roundWarned = false;
            return;
        }
        final int warn = config.melee().warnMinutes();
        if (!roundWarned && warn > 0 && now >= nextRoundMillis - warn * 60_000L && round == null) {
            roundWarned = true;
            broadcast("<#7cff6b>🍉 " + ItemRenderer.escape(config.name()) + "<gray>：" + warn + " 分鐘後星潮海灘開打西瓜大亂鬥！（選單 → "
                    + config.name() + " 傳送）");
        }
        if (now >= nextRoundMillis) {
            if (kraken != null) {
                return; // 海妖在場上：等牠走了再開（排到同一個時間時，海妖先）
            }
            if (round == null && config.running(nextRoundMillis)) {
                final String problem = startRound();
                if (problem != null) {
                    plugin.getSLF4JLogger().warn("{}：大亂鬥沒開成：{}", config.name(), problem);
                }
            }
            nextRoundMillis = nextHourAfter(now, config.melee().hours(), config.meleeMinute());
            roundWarned = false;
        }
    }

    /** 開一局西瓜大亂鬥（排程、管理員指令）；回傳問題，成功 null。 */
    public String startRound() {
        if (round != null) {
            return "大亂鬥已經在進行了";
        }
        if (!arenaReady) {
            return "星潮海灘還沒蓋好";
        }
        if (kraken != null) {
            return "海妖還在場上";
        }
        round = new Round(System.currentTimeMillis());
        broadcast("<#7cff6b>🍉 " + ItemRenderer.escape(config.name()) + "<white>：西瓜大亂鬥開打！<gray>" + config.melee().seconds()
                + " 秒內在星潮海灘踩破、打破最多西瓜（金西瓜 10 分）——前三名有珍珠（選單 → " + config.name() + " 傳送）");
        for (final Player player : arenaPlayers()) {
            player.showTitle(Title.title(plugin.text().parse("<#7cff6b>🍉 西瓜大亂鬥"),
                    plugin.text().parse("<gray>踩破、打破西瓜：1 分；金西瓜 10 分"),
                    Title.Times.times(Duration.ofMillis(300), Duration.ofMillis(2000), Duration.ofMillis(600))));
            player.playSound(player.getLocation(), Sound.ENTITY_PLAYER_LEVELUP, 0.8f, 1.2f);
        }
        return null;
    }

    private void tickRound(final long now) {
        final Round event = round;
        if (now >= event.endMillis) {
            endRound(true);
            return;
        }
        final SummerConfig.Melee melee = config.melee();
        event.secondsSinceSpawn++;
        if (event.secondsSinceSpawn >= melee.spawnEvery()) {
            event.secondsSinceSpawn = 0;
            final World world = eventWorld();
            if (world != null) {
                for (int i = 0; i < melee.spawnCount() && event.melons.size() < 60; i++) {
                    final int[] spot = SummerArenaBuilder.randomLand(config, random);
                    if (spot != null) {
                        spawnMelon(event, world, spot);
                    }
                }
            }
        }
        // 血條：剩多久、自己的分數
        final long left = Math.max(0, (event.endMillis - now) / 1000);
        event.bar.progress((float) Math.max(0, Math.min(1, left / (double) melee.seconds())));
        final List<Player> near = arenaPlayers();
        final Set<UUID> show = new HashSet<>();
        for (final Player player : near) {
            show.add(player.getUniqueId());
            if (roundViewers.add(player.getUniqueId())) {
                player.showBossBar(event.bar);
            }
        }
        event.bar.name(plugin.text().parse("<#7cff6b>🍉 西瓜大亂鬥 <white>" + String.format(Locale.ROOT, "%d:%02d", left / 60, left % 60)
                + " <gray>｜ 打破 " + event.broken + " 顆" + leaderText(event)));
        for (final UUID viewer : new ArrayList<>(roundViewers)) {
            if (!show.contains(viewer)) {
                roundViewers.remove(viewer);
                final Player player = plugin.getServer().getPlayer(viewer);
                if (player != null) {
                    player.hideBossBar(event.bar);
                }
            }
        }
    }

    private String leaderText(final Round event) {
        UUID best = null;
        int bestPoints = 0;
        for (final Map.Entry<UUID, Integer> entry : event.points.entrySet()) {
            if (entry.getValue() > bestPoints) {
                bestPoints = entry.getValue();
                best = entry.getKey();
            }
        }
        if (best == null) {
            return "";
        }
        final Player player = plugin.getServer().getPlayer(best);
        return player == null ? "" : " <dark_gray>｜ <gray>領先 <white>" + ItemRenderer.escape(who(player)) + " " + bestPoints + " 分";
    }

    private void spawnMelon(final Round event, final World world, final int[] spot) {
        final Location at = new Location(world, config.arena().x() + spot[0] + 0.5, SummerArenaBuilder.FLOOR_Y, config.arena().z() + spot[1] + 0.5);
        final boolean golden = random.nextDouble() < config.melee().goldenChance();
        final Melon melon = new Melon(golden, at);
        final Interaction hitbox = world.spawn(at, Interaction.class, created -> {
            created.setInteractionWidth(1.0f);
            created.setInteractionHeight(1.0f);
            created.setResponsive(true);
            created.setPersistent(false);
        });
        final ItemDisplay display = world.spawn(at.clone().add(0, 0.45, 0), ItemDisplay.class, created -> {
            created.setItemStack(new ItemStack(Material.MELON));
            created.setTransformation(new Transformation(new Vector3f(), new AxisAngle4f(), new Vector3f(0.9f, 0.9f, 0.9f), new AxisAngle4f()));
            created.setPersistent(false);
            if (golden) {
                created.setGlowing(true);
                created.setGlowColorOverride(Color.fromRGB(0xffd166));
                created.setBrightness(new Display.Brightness(15, 15));
            }
        });
        for (final Entity part : List.of(hitbox, display)) {
            part.getPersistentDataContainer().set(key, PersistentDataType.STRING, "melon");
            melon.parts.add(part.getUniqueId());
        }
        event.melons.put(hitbox.getUniqueId(), melon);
        event.spawned++;
        world.playSound(at, Sound.BLOCK_WOOD_PLACE, 0.5f, 1.4f);
        if (golden) {
            tw.exilecore.skill.Fx.spawn(world, Particle.END_ROD, at.clone().add(0, 0.6, 0), 10, 0.3, 0.3, 0.3, 0.02);
        }
    }

    /** 這是不是大亂鬥的西瓜（右鍵、左鍵都算打破）。 */
    public boolean isMelon(final Entity entity) {
        return round != null && round.melons.containsKey(entity.getUniqueId());
    }

    /** 打破一顆西瓜（SummerModule、tickFast）。 */
    boolean breakMelon(final Player player, final UUID hitboxId) {
        final Round event = round;
        if (event == null || !plugin.players().get(player).hasCharacter() || player.getGameMode() == org.bukkit.GameMode.SPECTATOR) {
            return false;
        }
        final Melon melon = event.melons.remove(hitboxId);
        if (melon == null) {
            return false;
        }
        for (final UUID id : melon.parts) {
            final Entity entity = plugin.getServer().getEntity(id);
            if (entity != null) {
                entity.remove();
            }
        }
        final int points = melon.golden ? 10 : 1;
        final int total = event.points.merge(player.getUniqueId(), points, Integer::sum);
        event.broken++;
        final World world = melon.at.getWorld();
        tw.exilecore.skill.Fx.spawn(world, Particle.ITEM, melon.at.clone().add(0, 0.5, 0), 18, 0.3, 0.3, 0.3, 0.08, new ItemStack(Material.MELON_SLICE));
        world.playSound(melon.at, Sound.BLOCK_WET_GRASS_BREAK, 1f, melon.golden ? 1.5f : 0.9f);
        if (melon.golden) {
            world.playSound(melon.at, Sound.ENTITY_PLAYER_LEVELUP, 0.8f, 1.8f);
            tw.exilecore.skill.Fx.spawn(world, Particle.TOTEM_OF_UNDYING, melon.at.clone().add(0, 0.6, 0), 20, 0.3, 0.3, 0.3, 0.15);
        }
        tw.exilecore.gui.ActionBar.send(player, plugin.text().parse((melon.golden ? "<#ffd166>🍉 金西瓜！+10" : "<#7cff6b>🍉 +1")
                + " <gray>（你 " + total + " 分）"));
        // 全服的西瓜（滿潮）
        globalCount++;
        stateDirty = true;
        if (globalCount >= config.melee().globalTarget()) {
            globalCount -= config.melee().globalTarget();
            bonusUntil = Math.max(System.currentTimeMillis(), bonusUntil) + config.melee().globalMinutes() * 60_000L;
            saveState();
            broadcast("<" + config.color() + ">🌊 " + ItemRenderer.escape(config.name()) + "<white>：全服打破了 "
                    + config.melee().globalTarget() + " 顆西瓜，滿潮！<gray>接下來 " + config.melee().globalMinutes()
                    + " 分鐘，全服物品數量 <white>+" + config.melee().globalQuantity() + "%");
            for (final Player online : plugin.getServer().getOnlinePlayers()) {
                online.playSound(online.getLocation(), Sound.UI_TOAST_CHALLENGE_COMPLETE, 0.6f, 1.2f);
            }
        }
        return true;
    }

    /** 收掉這一局（時間到發獎；管理員收掉、活動結束不發）。 */
    public void endRound(final boolean reward) {
        final Round event = round;
        if (event == null) {
            return;
        }
        round = null;
        for (final Melon melon : event.melons.values()) {
            for (final UUID id : melon.parts) {
                final Entity entity = plugin.getServer().getEntity(id);
                if (entity != null) {
                    entity.remove();
                }
            }
        }
        event.melons.clear();
        for (final UUID viewer : roundViewers) {
            final Player player = plugin.getServer().getPlayer(viewer);
            if (player != null) {
                player.hideBossBar(event.bar);
            }
        }
        roundViewers.clear();
        if (!reward) {
            broadcast("<#7cff6b>🍉 " + ItemRenderer.escape(config.name()) + "<gray>：這一局西瓜大亂鬥收掉了（不算分）");
            return;
        }
        final SummerConfig.Melee melee = config.melee();
        final List<Map.Entry<UUID, Integer>> ranking = new ArrayList<>(event.points.entrySet());
        ranking.sort((a, b) -> Integer.compare(b.getValue(), a.getValue()));
        final StringBuilder top = new StringBuilder();
        for (int i = 0; i < ranking.size(); i++) {
            final Player player = plugin.getServer().getPlayer(ranking.get(i).getKey());
            if (player == null) {
                continue;
            }
            final int points = ranking.get(i).getValue();
            if (i < melee.topPearls().length && melee.topPearls()[i] > 0) {
                giveCurrency(player, SummerConfig.PEARL, melee.topPearls()[i]);
                top.append(top.isEmpty() ? "" : "、").append(i + 1).append(". ").append(ItemRenderer.escape(who(player))).append(" ").append(points).append(" 分");
            }
            giveCurrency(player, SummerConfig.SHELL, LootBonus.of(plugin, player).amount(points * melee.shellsPerPoint(), random));
            if (plugin.bounties() != null && plugin.bounties().accountLoaded(player)) {
                plugin.bounties().commitAccount(player, Map.of(MELEE, String.valueOf(number(player, MELEE) + 1)), config.name() + "大亂鬥");
                checkChallenges(player);
            }
            player.playSound(player.getLocation(), Sound.UI_TOAST_CHALLENGE_COMPLETE, 0.7f, 1.1f);
        }
        plugin.getSLF4JLogger().info("[{}] 大亂鬥結束：{} 人、生了 {} 顆、打破 {} 顆", config.name(), ranking.size(), event.spawned, event.broken);
        broadcast("<#7cff6b>🍉 " + ItemRenderer.escape(config.name()) + "<white>：西瓜大亂鬥結束！<gray>"
                + (top.isEmpty() ? "這一局沒有人打破西瓜" : "前三名：" + top));
    }

    // ---- 深潮海妖 ----

    public boolean krakenRunning() {
        return kraken != null;
    }

    public long krakenSecondsLeft() {
        return kraken == null ? 0 : Math.max(0, (kraken.endMillis - System.currentTimeMillis()) / 1000);
    }

    /** 下一次海妖（「20:00」；正在打、活動沒在進行 null）。 */
    public String nextKrakenClock() {
        return clock(kraken != null ? -1 : nextKrakenMillis);
    }

    private void scheduleKraken(final long now) {
        if (nextKrakenMillis < 0) {
            nextKrakenMillis = nextHourAfter(now, config.boss().hours(), config.bossMinute());
            krakenWarned = false;
            return;
        }
        final int warn = config.boss().warnMinutes();
        if (!krakenWarned && warn > 0 && now >= nextKrakenMillis - warn * 60_000L && kraken == null) {
            krakenWarned = true;
            broadcast("<" + config.color() + ">🌊 " + ItemRenderer.escape(config.name()) + "<gray>：" + warn + " 分鐘後，<white>"
                    + ItemRenderer.escape(config.boss().name()) + "<gray> 會浮上星潮海灘！（選單 → " + config.name() + " 傳送）");
        }
        if (now >= nextKrakenMillis) {
            if (kraken == null && config.running(nextKrakenMillis)) {
                final String problem = startKraken();
                if (problem != null) {
                    plugin.getSLF4JLogger().warn("{}：海妖沒出來：{}", config.name(), problem);
                }
            }
            nextKrakenMillis = nextHourAfter(now, config.boss().hours(), config.bossMinute());
            krakenWarned = false;
        }
    }

    /** 叫出海妖（排程、管理員指令）；回傳問題，成功 null。 */
    public String startKraken() {
        if (kraken != null) {
            return "海妖已經在場上了";
        }
        if (!arenaReady) {
            return "星潮海灘還沒蓋好";
        }
        if (round != null) {
            endRound(true); // 正在打的大亂鬥先結算（海妖要用中央的沙地）
        }
        final Location center = center();
        center.getChunk().load();
        final SummerConfig.Boss def = config.boss();
        final LivingEntity boss = spawnMob(def.type(), center.clone().add(0, 0.2, 0));
        if (boss == null) {
            return "海妖的類型 " + def.type() + " 生不出來";
        }
        try {
            plugin.monsters().fixPlayers(boss, 1);
            plugin.monsters().decorateBoss(boss, def.level(), def.name(), def.mods(), def.life(), def.damage(), def.scale());
        } catch (final RuntimeException e) {
            boss.remove();
            plugin.getSLF4JLogger().error("{}：海妖等級化失敗", config.name(), e);
            return "海妖等級化失敗（看主控台）";
        }
        final EntityEquipment equipment = boss.getEquipment();
        if (equipment != null) {
            equipment.setHelmet(new ItemStack(Material.TURTLE_HELMET));
            equipment.setItemInMainHand(new ItemStack(Material.TRIDENT));
            equipment.setHelmetDropChance(0);
            equipment.setItemInMainHandDropChance(0);
        }
        final AttributeInstance follow = boss.getAttribute(Attribute.FOLLOW_RANGE);
        if (follow != null && follow.getBaseValue() < 48) {
            follow.setBaseValue(48);
        }
        tag(boss, "kraken");
        boss.setInvulnerable(true); // 出場無敵 10 秒（tickKraken 時間到解除；照南瓜王）
        kraken = new Kraken(center, System.currentTimeMillis());
        kraken.boss = boss;
        kraken.shieldUntil = System.currentTimeMillis() + SPAWN_SHIELD_MILLIS;
        kraken.mobs.add(boss.getUniqueId());
        attachSkills(boss);
        plugin.getSLF4JLogger().info("[Boss 紀錄] {}：{} Lv.{} 出現", config.name(), def.name(), def.level());
        broadcast("<" + config.color() + ">🌊 " + ItemRenderer.escape(config.name()) + "<white>：" + ItemRenderer.escape(def.name())
                + " 浮上了星潮海灘！<gray> " + def.durationMinutes() + " 分鐘內打倒牠（選單 → " + config.name() + " 傳送）");
        for (final Player player : plugin.getServer().getOnlinePlayers()) {
            player.playSound(player.getLocation(), Sound.ENTITY_ELDER_GUARDIAN_CURSE, 0.5f, 1.2f);
        }
        for (final Player player : arenaPlayers()) {
            player.showTitle(Title.title(plugin.text().parse("<" + config.color() + ">" + ItemRenderer.escape(def.name())),
                    plugin.text().parse("<gray>星潮祭的世界 Boss・" + SPAWN_SHIELD_MILLIS / 1000 + " 秒後開打"),
                    Title.Times.times(Duration.ofMillis(300), Duration.ofMillis(2200), Duration.ofMillis(600))));
            if (!def.intro().isBlank() && !plugin.players().hidesMonsterTalk(player)) {
                player.sendMessage(plugin.text().parse("<" + config.color() + ">" + ItemRenderer.escape(def.name()) + "<white>："
                        + ItemRenderer.escape(def.intro())));
            }
        }
        return null;
    }

    /** 收掉海妖（不給獎勵）。 */
    public void stopKraken(final String reason) {
        if (kraken == null) {
            return;
        }
        final Kraken ending = kraken;
        kraken = null;
        cleanupKraken(ending);
        if (reason != null) {
            broadcast("<" + config.color() + ">🌊 " + ItemRenderer.escape(config.name()) + "<gray>：" + ItemRenderer.escape(reason));
        }
    }

    private void cleanupKraken(final Kraken ending) {
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

    /** 海妖出場後無敵幾毫秒（讓大家先集合；照南瓜王）。 */
    static final long SPAWN_SHIELD_MILLIS = 10_000;

    private void tickKraken(final long now) {
        final Kraken event = kraken;
        final List<Player> near = arenaPlayers();
        if (event.boss != null && event.boss.isValid() && event.boss.isInvulnerable() && System.currentTimeMillis() >= event.shieldUntil) {
            event.boss.setInvulnerable(false);
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
            stopKraken("時間到，" + config.boss().name() + "隨著退潮離開了");
            return;
        }
        if (now - event.lastSeenMillis > config.boss().emptyCloseSeconds() * 1000L) {
            stopKraken("海灘上沒有人，" + config.boss().name() + "沉回海裡了");
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
            final SummerConfig.Boss def = config.boss();
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
            final double fraction = Math.max(0, Math.min(1, boss.getHealth() / Math.max(1, max == null ? 1 : max.getValue())));
            event.bar.progress((float) fraction);
            // 2026-11-03 照站長「招式要更多」：生命掉到 boss.phases 的比例以下就換階段（只往上、不回頭），大招（潮影、漲潮、海嘯）才解鎖
            final int phase = def.phaseAt(fraction);
            if (phase > event.phase) {
                event.phase = phase;
                boss.getWorld().playSound(boss.getLocation(), Sound.ENTITY_ELDER_GUARDIAN_CURSE, 1.5f, 0.6f);
                tw.exilecore.skill.Fx.spawn(boss.getWorld(), Particle.SPLASH, boss.getLocation().add(0, 1.5, 0), 120, 2.5, 1.2, 2.5, 0.1);
                for (final Player player : near) {
                    player.sendMessage(plugin.text().prefixed("<" + config.color() + ">" + ItemRenderer.escape(def.name()) + " 進入第 " + (phase + 1)
                            + " 階段<gray>：潮水更兇了，小心大招"));
                }
            }
            if (tickCount % 2 == 0) {
                tw.exilecore.skill.Fx.spawn(boss.getWorld(), Particle.BUBBLE_POP, boss.getLocation().add(0, 2.4, 0), 8, 0.5, 0.3, 0.5, 0.02);
                drawShelter(boss.getWorld()); // 2026-11-06：入口安全區的邊（綠色一圈）
            }
        }
        final long left = Math.max(0, (event.endMillis - now) / 1000);
        event.bar.name(plugin.text().parse("<" + config.color() + ">" + ItemRenderer.escape(config.boss().name()) + " <gray>Lv." + config.boss().level()
                + " <dark_gray>｜ <white>" + String.format(Locale.ROOT, "%d:%02d", left / 60, left % 60)
                + (plugin.ailments() == null ? "" : plugin.ailments().bossBarSuffix(event.boss))));
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

    private static final Particle.DustOptions SHELTER_DUST = new Particle.DustOptions(org.bukkit.Color.fromRGB(0x9FE8C8), 1.4f);

    /** 入口安全區的邊：地上一圈綠點（海妖在場時）。 */
    private void drawShelter(final World world) {
        final double[] c = shelterCenter();
        final double y = center() == null ? 64 : center().getY() + 0.2;
        for (int i = 0; i < 36; i++) {
            final double angle = Math.PI * 2 * i / 36;
            final double x = c[0] + Math.cos(angle) * SHELTER_RADIUS;
            final double z = c[1] + Math.sin(angle) * SHELTER_RADIUS;
            final double top = world.getHighestBlockYAt((int) Math.floor(x), (int) Math.floor(z)) + 1.15;
            tw.exilecore.skill.Fx.spawn(world, Particle.DUST, x, Math.max(y, top), z, 1, 0, 0, 0, 0, SHELTER_DUST);
        }
    }

    private void attachSkills(final LivingEntity boss) {
        final SummerConfig.Boss def = config.boss();
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
                return kraken == null ? 0 : kraken.phase; // 2026-11-03：照生命換階段（tickKraken）；這次之前永遠 0
            }

            @Override
            public String tempoKind() {
                return "festival"; // 跟南瓜王同一個節奏（不普攻，出招比較快；boss_skills.yml 的 tempo.festival）
            }

            @Override
            public boolean sheltered(final Player player) {
                return inShelter(player.getLocation()); // 2026-11-06：南邊棧道入口的安全區
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
                if (kraken != null) {
                    kraken.mobs.add(minion.getUniqueId());
                }
                return minion;
            }
        });
    }

    private void krakenDefeated() {
        final Kraken event = kraken;
        kraken = null;
        final SummerConfig.Boss def = config.boss();
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
            final int[] range = def.pearl();
            giveCurrency(player, SummerConfig.PEARL, bonus.amount(range[0] + random.nextInt(Math.max(1, range[1] - range[0] + 1)), random));
            giveCurrency(player, SummerConfig.SHELL, bonus.amount(def.shells(), random));
            if (random.nextDouble() < bonus.rare(def.cosmeticChance())) {
                final String cosmetic = randomUnowned(player);
                if (cosmetic != null && grantCosmetic(player, cosmetic)) {
                    broadcast("<" + config.color() + ">🌊 " + ItemRenderer.escape(who(player)) + " 從" + ItemRenderer.escape(def.name())
                            + "的珊瑚喉裡撈出了限定時裝 <white>" + ItemRenderer.escape(cosmeticName(cosmetic)) + "<" + config.color() + ">！");
                }
            }
            if (!def.uniques().isEmpty() && random.nextDouble() < bonus.rare(def.uniqueChance())) {
                // 2026-11-05 照站長「夏日活動補上各 Boss 的入場券，並且新增兩件專屬的傳奇」：海妖的專屬傳奇（吃物品稀有度；跟開天者一樣）
                final tw.exilecore.item.ItemData data = giveUnique(player, def.uniques().get(random.nextInt(def.uniques().size())), def.level());
                if (data != null) {
                    broadcast("<" + config.color() + ">🌊 " + ItemRenderer.escape(who(player)) + " 從" + ItemRenderer.escape(def.name())
                            + "身上拿到了專屬傳奇 <#af6025>" + ItemRenderer.escape(data.name()) + "<" + config.color() + ">！");
                }
            }
            if (plugin.bounties() != null && plugin.bounties().accountLoaded(player)) {
                plugin.bounties().commitAccount(player, Map.of(KRAKEN, String.valueOf(number(player, KRAKEN) + 1)), config.name() + "海妖");
                checkChallenges(player);
            }
            player.playSound(player.getLocation(), Sound.UI_TOAST_CHALLENGE_COMPLETE, 0.8f, 1f);
        }
        cleanupKraken(event);
        if (!def.defeat().isBlank()) {
            talk("<" + config.color() + ">" + ItemRenderer.escape(def.defeat()));
        }
        broadcast("<" + config.color() + ">🌊 " + ItemRenderer.escape(config.name()) + "<white>：" + ItemRenderer.escape(def.name()) + " 倒下了！<gray>"
                + players.size() + " 位冒險者拿到了珍珠與貝殼");
    }

    // ---- 貝殼、漂流瓶（MonsterListener 呼叫）----

    /** 地圖裡的怪倒下，有份的每個人各擲一次貝殼。 */
    public void onKill(final Player player, final LivingEntity entity, final MonsterData data) {
        if (!running() || plugin.maps() == null || plugin.maps().instanceOf(entity) == null) {
            return;
        }
        final SummerConfig.Bottle bottle = config.bottle();
        // 終局以上的 Boss 不吃物品數量（跟一般掉落一樣）
        final LootBonus.Bonus bonus = plugin.maps().flatBossLoot(entity)
                ? LootBonus.boosts(plugin, player) // 2026-11-12 照站長「個人的掉寶與稀有藥要影響全域的掉落（包含終局以上 Boss）」：只加個人加倍藥；這次之前 NONE
                : LootBonus.of(plugin, player);
        final int amount;
        if (data.boss()) {
            amount = bonus.amount(bottle.bossShells(), random);
        } else {
            amount = switch (data.rarity()) {
                case RARE, UNIQUE -> bonus.amount(bottle.rareShells(), random);
                case MAGIC -> random.nextDouble() < bonus.more(bottle.magic()) ? 1 : 0;
                default -> random.nextDouble() < bonus.more(bottle.normal()) ? 1 : 0;
            };
        }
        if (amount > 0) {
            giveCurrency(player, SummerConfig.SHELL, amount);
        }
    }

    /** 地圖裡的稀有怪、Boss 倒下（一隻一次）：有機會留下漂流瓶。 */
    public void onMonsterDeath(final LivingEntity entity, final MonsterData data) {
        if (!running() || plugin.maps() == null || plugin.maps().instanceOf(entity) == null) {
            return;
        }
        final double chance = data.boss() ? config.bottle().boss()
                : data.rarity() == MonsterRarity.RARE || data.rarity() == MonsterRarity.UNIQUE ? config.bottle().rare() : 0;
        if (chance <= 0 || random.nextDouble() >= chance) {
            return;
        }
        spawnBottle(entity.getLocation());
    }

    /**
     * 藏寶圖（MonsterListener.rollAndDeliver，每個有份的人各自）：手上有藏寶圖的人，地圖 Boss 倒下多擲 map_extra 件掉落，圖就用掉。
     * 2026-11-03：用掉落表的 rollOne（跟一般掉落一樣的東西），不另外做額外掉落表。
     */
    public void rollKill(final Player owner, final LivingEntity entity, final MonsterData data, final List<DropTable.Drop> rolled,
                         final double rarityBonus) {
        if (owner == null || !running() || !data.boss() || plugin.maps() == null || plugin.bounties() == null
                || !plugin.bounties().accountLoaded(owner) || !hasMap(owner)) {
            return;
        }
        final tw.exilecore.map.MapInstance instance = plugin.maps().instanceOf(entity);
        if (instance == null || instance.boss() != entity) {
            return;
        }
        // 2026-11-03 修正：用掉時把這筆刪掉（null）；這次之前寫成空字串 ""，「有沒有藏寶圖」看的是 != null，
        //   用過一次之後每隻地圖 Boss 都當成有、一直跳「藏寶圖用掉了」，漂流瓶也永遠只開出瓶中信
        if (!plugin.bounties().commitAccount(owner, java.util.Collections.singletonMap(MAP, null), config.name() + "藏寶圖")) {
            return;
        }
        final DropTable drops = plugin.monsterModule().drops();
        int added = 0;
        for (int i = 0; i < config.bottle().mapExtra(); i++) {
            final DropTable.Drop drop = drops.rollOne(data.level(), rarityBonus, plugin.items().generator(), plugin.currencies().repository(), random);
            if (drop != null) {
                rolled.add(drop);
                added++;
            }
        }
        if (config.bottle().mapPearls() > 0) {
            giveCurrency(owner, SummerConfig.PEARL, config.bottle().mapPearls());
        }
        owner.playSound(owner.getLocation(), Sound.ITEM_BOOK_PAGE_TURN, 1f, 0.8f);
        owner.sendMessage(plugin.text().prefixed("<" + config.color() + ">🗺 藏寶圖上的叉就在這裡！<gray>Boss 多掉了 " + added + " 件東西（藏寶圖用掉了）"));
    }

    /** 手上有沒有藏寶圖（選單用）。 */
    public boolean holdsMap(final Player player) {
        return hasMap(player);
    }

    /** 2026-11-03：手上有藏寶圖（值是 "1"；舊版用掉時寫成 "" 的當成沒有）。 */
    private boolean hasMap(final Player player) {
        final String value = progress(player, MAP);
        return value != null && !value.isBlank();
    }

    /** 放一個漂流瓶（管理員測試也用）。 */
    public void spawnBottle(final Location location) {
        final World world = location.getWorld();
        final Location at = location.getBlock().getLocation().add(0.5, 0, 0.5);
        final Bottle bottle = new Bottle(System.currentTimeMillis() + config.bottle().seconds() * 1000L);
        final Interaction hitbox = world.spawn(at, Interaction.class, created -> {
            created.setInteractionWidth(0.9f);
            created.setInteractionHeight(1.1f);
            created.setResponsive(true);
            created.setPersistent(false);
        });
        final ItemDisplay display = world.spawn(at.clone().add(0, 0.5, 0), ItemDisplay.class, created -> {
            created.setItemStack(new ItemStack(Material.GLASS_BOTTLE));
            created.setTransformation(new Transformation(new Vector3f(), new AxisAngle4f((float) Math.toRadians(70), 1, 0, 0),
                    new Vector3f(1.1f, 1.1f, 1.1f), new AxisAngle4f()));
            created.setGlowing(true);
            created.setGlowColorOverride(Color.fromRGB(0x2ec4ff));
            created.setPersistent(false);
        });
        final TextDisplay label = world.spawn(at.clone().add(0, 1.45, 0), TextDisplay.class, created -> {
            created.text(plugin.text().parse("<" + config.color() + ">🍾 漂流瓶\n<gray>右鍵：打開"));
            created.setBillboard(Display.Billboard.CENTER);
            created.setPersistent(false);
        });
        for (final Entity part : List.of(hitbox, display, label)) {
            part.getPersistentDataContainer().set(key, PersistentDataType.STRING, "bottle");
            bottle.parts.add(part.getUniqueId());
        }
        bottles.put(hitbox.getUniqueId(), bottle);
        tw.exilecore.skill.Fx.spawn(world, Particle.BUBBLE_POP, at.clone().add(0, 0.6, 0), 14, 0.4, 0.4, 0.4, 0.02);
        world.playSound(at, Sound.ENTITY_BOAT_PADDLE_WATER, 0.9f, 1.3f);
        for (final Player player : world.getPlayers()) {
            if (player.getLocation().distanceSquared(at) < 40 * 40) {
                tw.exilecore.gui.ActionBar.send(player, plugin.text().parse("<" + config.color() + ">🍾 地上漂來了一個漂流瓶！<gray>右鍵打開"));
            }
        }
    }

    private void removeBottle(final Bottle bottle) {
        for (final UUID id : bottle.parts) {
            final Entity entity = plugin.getServer().getEntity(id);
            if (entity != null) {
                entity.remove();
            }
        }
    }

    /** 這是不是海妖（每秒傷害上限跟開天者一樣；MapService.capBossDamage）。 */
    public boolean isKraken(final Entity entity) {
        return "kraken".equals(role(entity));
    }

    /** 這是不是漂流瓶的感應框（右鍵沿視線找實體時用：拿著武器時客戶端的準星停不到它）。 */
    public boolean isBottle(final Entity entity) {
        return bottles.containsKey(entity.getUniqueId());
    }

    /** 右鍵了一個實體（SummerModule）：漂流瓶、西瓜就處理，回傳要不要取消事件。 */
    boolean interact(final Player player, final Entity entity) {
        if (isMelon(entity)) {
            return breakMelon(player, entity.getUniqueId());
        }
        final Bottle bottle = bottles.remove(entity.getUniqueId());
        if (bottle == null) {
            return false;
        }
        removeBottle(bottle);
        final Location at = entity.getLocation();
        if (plugin.bounties() != null && plugin.bounties().accountLoaded(player)) {
            plugin.bounties().commitAccount(player, Map.of(BOTTLE, String.valueOf(number(player, BOTTLE) + 1)), config.name() + "漂流瓶");
        }
        final SummerConfig.Bottle def = config.bottle();
        at.getWorld().playSound(at, Sound.BLOCK_GLASS_BREAK, 0.7f, 1.3f);
        tw.exilecore.skill.Fx.spawn(at.getWorld(), Particle.BUBBLE_POP, at.clone().add(0, 0.8, 0), 20, 0.5, 0.5, 0.5, 0.03);
        final boolean hasMap = plugin.bounties() != null && plugin.bounties().accountLoaded(player) && hasMap(player);
        if (hasMap || random.nextDouble() < def.letterChance()) {
            // 瓶中信：貝殼＋一句話（手上已經有藏寶圖的，瓶子裡一定是信）
            final int[] range = def.shells();
            final int amount = LootBonus.of(plugin, player).amount(range[0] + random.nextInt(Math.max(1, range[1] - range[0] + 1)), random);
            giveCurrency(player, SummerConfig.SHELL, amount);
            final String letter = def.letters().get(random.nextInt(def.letters().size()));
            player.playSound(player.getLocation(), Sound.ITEM_BOOK_PAGE_TURN, 0.8f, 1.1f);
            if (!plugin.players().hidesSystem(player)) {
                player.sendMessage(plugin.text().prefixed("<" + config.color() + ">🍾 瓶中信<gray>：<white>「" + ItemRenderer.escape(letter)
                        + "」<gray>　貝殼 +" + amount));
            }
        } else if (plugin.bounties() != null && plugin.bounties().accountLoaded(player)
                && plugin.bounties().commitAccount(player, Map.of(MAP, "1"), config.name() + "藏寶圖")) {
            player.playSound(player.getLocation(), Sound.ITEM_BOOK_PUT, 0.8f, 0.9f);
            player.showTitle(Title.title(plugin.text().parse("<#ffd166>🗺 藏寶圖！"),
                    plugin.text().parse("<gray>下一張地圖的 Boss 倒下多掉 " + def.mapExtra() + " 件東西"),
                    Title.Times.times(Duration.ofMillis(200), Duration.ofMillis(1600), Duration.ofMillis(400))));
            player.sendMessage(plugin.text().prefixed("<" + config.color() + ">🍾 瓶子裡是一張藏寶圖！<gray>下一張地圖的 Boss 倒下多掉 " + def.mapExtra()
                    + " 件東西（帳號共用，用掉就沒了）"));
        }
        checkChallenges(player);
        return true;
    }

    private void tickBottles(final long now) {
        for (final Iterator<Bottle> it = bottles.values().iterator(); it.hasNext(); ) {
            final Bottle bottle = it.next();
            boolean gone = now >= bottle.expire;
            if (!gone) {
                final Entity hitbox = plugin.getServer().getEntity(bottle.parts.getFirst());
                gone = hitbox == null || !hitbox.isValid();
            }
            if (gone) {
                removeBottle(bottle);
                it.remove();
            }
        }
    }

    // ---- 死亡（SummerModule）----

    void onDeath(final LivingEntity entity, final Player killer) {
        final String role = role(entity);
        if (role == null) {
            return;
        }
        if (plugin.skills() != null) {
            plugin.skills().bossSkills().detach(entity.getUniqueId());
        }
        if (role.equals("kraken")) {
            if (kraken != null && kraken.boss != null && kraken.boss.getUniqueId().equals(entity.getUniqueId())) {
                krakenDefeated();
            }
            return;
        }
        if (role.startsWith("guard:")) {
            final UUID id;
            try {
                id = UUID.fromString(role.substring("guard:".length()));
            } catch (final IllegalArgumentException e) {
                return;
            }
            final Guard guard = guards.get(id);
            if (guard == null) {
                return;
            }
            guard.mobs.remove(entity.getUniqueId());
            if (guard.mobs.isEmpty()) {
                guards.remove(id);
                final Player owner = plugin.getServer().getPlayer(guard.owner);
                if (owner != null) {
                    final SummerConfig.Dig dig = config.dig();
                    final int amount = LootBonus.of(plugin, owner).amount(dig.shells(), random);
                    giveCurrency(owner, SummerConfig.SHELL, amount);
                    final boolean pearl = random.nextDouble() < dig.pearlChance();
                    if (pearl) {
                        giveCurrency(owner, SummerConfig.PEARL, 1);
                    }
                    owner.playSound(owner.getLocation(), Sound.UI_TOAST_CHALLENGE_COMPLETE, 0.7f, 1.4f);
                    owner.sendMessage(plugin.text().prefixed("<" + config.color() + ">⚓ 守衛都打跑了，寶箱是你的！<gray>雙倍貝殼 +" + amount
                            + (pearl ? "，還有一顆珍珠" : "")));
                }
            }
        }
    }

    // ---- 挑戰 ----

    /** 一個挑戰的狀態（選單用）。 */
    public record Challenge(String name, int progress, int goal, String reward, boolean done) {
    }

    public List<Challenge> challenges(final Player player) {
        final SummerConfig.Challenges c = config.challenges();
        final List<Challenge> list = new ArrayList<>();
        list.add(new Challenge("打倒" + config.boss().name() + " " + c.krakenCount() + " 次", Math.min(c.krakenCount(), number(player, KRAKEN)), c.krakenCount(),
                "珍珠 ×" + c.krakenPearl(), progress(player, DONE + "kraken") != null));
        list.add(new Challenge(c.digDays() + " 天挖滿全部的藏寶點", Math.min(c.digDays(), number(player, DIG_DAYS)), c.digDays(),
                "珍珠 ×" + c.digPearl(), progress(player, DONE + "dig") != null));
        list.add(new Challenge("參加 " + c.meleeCount() + " 局西瓜大亂鬥", Math.min(c.meleeCount(), number(player, MELEE)), c.meleeCount(),
                "貝殼 ×" + c.meleeShells(), progress(player, DONE + "melee") != null));
        list.add(new Challenge("打開 " + c.bottleCount() + " 個漂流瓶", Math.min(c.bottleCount(), number(player, BOTTLE)), c.bottleCount(),
                "貝殼 ×" + c.bottleShells(), progress(player, DONE + "bottle") != null));
        return list;
    }

    private void checkChallenges(final Player player) {
        if (plugin.bounties() == null || !plugin.bounties().accountLoaded(player)) {
            return;
        }
        final SummerConfig.Challenges c = config.challenges();
        claim(player, "kraken", number(player, KRAKEN) >= c.krakenCount(), SummerConfig.PEARL, c.krakenPearl(),
                "打倒" + config.boss().name() + " " + c.krakenCount() + " 次");
        claim(player, "dig", number(player, DIG_DAYS) >= c.digDays(), SummerConfig.PEARL, c.digPearl(),
                c.digDays() + " 天挖滿全部的藏寶點");
        claim(player, "melee", number(player, MELEE) >= c.meleeCount(), SummerConfig.SHELL, c.meleeShells(),
                "參加了 " + c.meleeCount() + " 局西瓜大亂鬥");
        claim(player, "bottle", number(player, BOTTLE) >= c.bottleCount(), SummerConfig.SHELL, c.bottleShells(),
                "打開了 " + c.bottleCount() + " 個漂流瓶");
        if (!c.title().isEmpty() && progress(player, DONE + "title") == null && progress(player, DONE + "kraken") != null
                && progress(player, DONE + "dig") != null && progress(player, DONE + "melee") != null && progress(player, DONE + "bottle") != null) {
            final SummerConfig.Title title = config.titles().get(c.title());
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
            broadcast("<" + config.color() + ">🌊 " + ItemRenderer.escape(who(player)) + " 完成了" + ItemRenderer.escape(config.name())
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
        for (final SummerConfig.ShopItem item : config.shop()) {
            if (item.kind() == SummerConfig.Kind.COSMETIC && !ownsCosmetic(player, item.cosmetic())) {
                pool.add(item.cosmetic());
            }
        }
        return pool.isEmpty() ? null : pool.get(random.nextInt(pool.size()));
    }

    /**
     * 收到時裝之後去哪裡用（2026-11-03 照站長：兌換加了動作「衝浪」；這次之前一律寫「贊助 → 裝備幻化 套上去」，
     * 動作、粒子特效也這樣寫）。
     */
    private String whereToUse(final String id) {
        final var cosmetic = plugin.sponsors() == null ? null : plugin.sponsors().config().cosmetics().get(id);
        if (cosmetic != null && cosmetic.isEmote()) {
            return "贊助 → 動作 綁按鍵";
        }
        if (cosmetic != null && !cosmetic.skin()) {
            return "贊助 → 3D 時裝 戴上";
        }
        return "贊助 → 裝備幻化 套上去";
    }

    private boolean grantCosmetic(final Player player, final String id) {
        if (plugin.bounties() == null || !plugin.bounties().accountLoaded(player)) {
            return false;
        }
        if (!plugin.bounties().commitAccount(player, Map.of(COSMETIC + id, "1"), config.name() + "時裝")) {
            return false;
        }
        player.sendMessage(plugin.text().prefixed("<#ff9ad5>收到時裝</#ff9ad5>：<white>" + ItemRenderer.escape(cosmeticName(id))
                + " <" + config.color() + ">★ " + ItemRenderer.escape(config.name()) + "限定<gray>　" + whereToUse(id)));
        player.playSound(player.getLocation(), Sound.UI_TOAST_CHALLENGE_COMPLETE, 0.8f, 1.2f);
        return true;
    }

    /** 已經有了（時裝、稱號）。 */
    public boolean owned(final Player player, final SummerConfig.ShopItem item) {
        return switch (item.kind()) {
            case COSMETIC -> ownsCosmetic(player, item.cosmetic());
            case TITLE -> plugin.bounties() != null && plugin.bounties().ownedTitles(player).contains(item.title());
            default -> false;
        };
    }

    /** 兌換；成功 null。 */
    String buy(final Player player, final SummerConfig.ShopItem item) {
        return buy(player, item, 1);
    }

    /** 2026-11-08 照站長「星潮祭的 Boss 入場兌換新增右鍵買 10 個」：一次換 times 份（只有通貨類，素材 × times；不夠就一個都不換）。 */
    static final int BULK = 10;

    String buy(final Player player, final SummerConfig.ShopItem item, final int requested) {
        final int times = item.kind() == SummerConfig.Kind.CURRENCY ? Math.max(1, requested) : 1;
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
            if (wallet.available(player, cost.getKey()) < (long) cost.getValue() * times) {
                return "素材不夠：" + currencyName(cost.getKey()) + " 要 " + (long) cost.getValue() * times + "，你有 " + wallet.available(player, cost.getKey());
            }
        }
        // 中途扣不到、或後面給不出去：已經扣的退回去（照萬燈夜 2026-10-17 的做法）
        final Map<String, Integer> taken = new java.util.LinkedHashMap<>();
        for (final Map.Entry<String, Integer> cost : item.cost().entrySet()) {
            if (!wallet.take(player, cost.getKey(), cost.getValue() * times)) {
                refundCosts(player, taken);
                return "素材不夠";
            }
            taken.put(cost.getKey(), cost.getValue() * times);
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
            case CURRENCY -> giveCurrency(player, item.currency(), item.amount() * times);
            case UNIQUE -> { // 2026-11-05 照站長「夏日活動補上各 Boss 的入場券，並且新增兩件專屬的傳奇」
                if (giveUnique(player, item.unique(), item.itemLevel()) == null) {
                    plugin.getSLF4JLogger().warn("{}：{} 換傳奇 {} 失敗（沒有這件；素材退回）", config.name(), player.getName(), item.unique());
                    refundCosts(player, taken);
                    return "沒有這件傳奇（素材退回了，請通知管理員）";
                }
            }
        }
        // 兌換成功才記素材消耗（wallet.take 不記流水帳；用 EVENT_SHOP，記 EVENT 會跟活動產出抵掉）
        for (final Map.Entry<String, Integer> cost : taken.entrySet()) {
            plugin.economy().ledger().of(player).consumed(cost.getKey(), tw.exilecore.economy.EconomyLedger.EVENT_SHOP, cost.getValue());
        }
        plugin.players().markDirty(player);
        plugin.characters().save(player);
        plugin.getSLF4JLogger().info("[{}] {} 兌換 {} ×{}", config.name(), player.getName(), item.id(), times);
        return null;
    }

    /** 2026-11-05 照站長「夏日活動補上各 Boss 的入場券，並且新增兩件專屬的傳奇」：給一件專屬傳奇（背包滿了掉在腳下）；沒有這件回傳 null。 */
    tw.exilecore.item.ItemData giveUnique(final Player player, final String uniqueId, final int itemLevel) {
        final tw.exilecore.item.UniqueItem unique = plugin.items() == null ? null : plugin.items().repository().unique(uniqueId);
        if (unique == null) {
            return null;
        }
        final tw.exilecore.item.ItemData data = plugin.items().generator().generateUnique(unique, itemLevel, random);
        final tw.exilecore.economy.Wallet wallet = plugin.economy().wallet();
        wallet.dropFor(player, wallet.give(player, plugin.items().toStack(data)));
        return data;
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

    /** 兌換失敗：把已經扣的素材退回去（Wallet.deposit：倉庫通貨頁 → 通貨袋 → 背包；放不下的做成物品掉腳下）。 */
    private void refundCosts(final Player player, final Map<String, Integer> taken) {
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

    /** 給素材：先進通貨袋，滿了背包，再滿掉在腳下（貝殼例外：通貨袋滿了，多的自動過濾掉，照萬燈糖）。 */
    void giveCurrency(final Player player, final String currencyId, final int amount) {
        if (amount <= 0 || plugin.currencies() == null) {
            return;
        }
        final CurrencyType type = plugin.currencies().repository().get(currencyId);
        if (type == null) {
            return;
        }
        if (SummerConfig.SHELL.equals(type.id()) && plugin.loot() != null) {
            final int accepted = plugin.loot().depositOrDiscard(player, plugin.players().get(player), type, amount);
            if (accepted >= 0) {
                if (accepted > 0 && plugin.economy() != null) {
                    plugin.economy().ledger().of(player).produced(type.id(), tw.exilecore.economy.EconomyLedger.EVENT, accepted);
                }
                tw.exilecore.gui.ActionBar.send(player, plugin.text().parse(accepted >= amount
                        ? "<" + config.color() + ">+" + amount + " " + ItemRenderer.escape(type.name())
                        : "<gray>" + ItemRenderer.escape(type.name()) + "滿了（上限 " + plugin.players().get(player).bag().pouch().cap()
                        + "），" + (accepted > 0 ? "收下 " + accepted + " 個、" : "") + "多的 " + (amount - accepted) + " 個自動過濾"));
                return;
            }
        }
        if (plugin.economy() != null) {
            plugin.economy().ledger().of(player).produced(type.id(), tw.exilecore.economy.EconomyLedger.EVENT, amount);
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

    /** Boss 的台詞送給全服（開了「隱藏怪物對話」的不送；不進 Discord）。 */
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

    /** 關服：怪物、西瓜、漂流瓶收掉，存檔。 */
    void shutdown() {
        if (kraken != null) {
            final Kraken ending = kraken;
            kraken = null;
            cleanupKraken(ending);
        }
        if (round != null) {
            final Round ending = round;
            round = null;
            for (final Melon melon : ending.melons.values()) {
                for (final UUID id : melon.parts) {
                    final Entity entity = plugin.getServer().getEntity(id);
                    if (entity != null) {
                        entity.remove();
                    }
                }
            }
            for (final UUID viewer : roundViewers) {
                final Player player = plugin.getServer().getPlayer(viewer);
                if (player != null) {
                    player.hideBossBar(ending.bar);
                }
            }
            roundViewers.clear();
        }
        for (final Bottle bottle : bottles.values()) {
            removeBottle(bottle);
        }
        bottles.clear();
        for (final Guard guard : guards.values()) {
            for (final UUID id : guard.mobs) {
                final Entity entity = plugin.getServer().getEntity(id);
                if (entity != null) {
                    entity.remove();
                }
            }
        }
        guards.clear();
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
    /** 2026-11-03 照站長：給玩家看的名字用角色名稱（沒有角色才用帳號名稱）；這次之前大亂鬥領先、前三名、廣播寫帳號名稱。 */
    private String who(final Player player) {
        final var state = plugin.players().get(player);
        return state != null && state.character() != null ? state.character().name() : player.getName();
    }

    public String status() {
        return String.format(Locale.ROOT, "%s：%s；海灘 %s；全服西瓜 %d／%d%s；海妖 %s；大亂鬥 %s；漂流瓶 %d 個",
                config.name(), running() ? "進行中" : shopOpen() ? "只剩兌換" : "沒有進行", arenaReady ? "蓋好了" : building ? "蓋到一半" : "還沒蓋",
                globalCount(), config.melee().globalTarget(), bonusMinutesLeft() > 0 ? "（滿潮還剩 " + bonusMinutesLeft() + " 分）" : "",
                kraken != null ? "在場上（剩 " + krakenSecondsLeft() + " 秒）" : nextKrakenClock() == null ? "—" : "下一次 " + nextKrakenClock(),
                round != null ? "進行中（剩 " + roundSecondsLeft() + " 秒）" : nextRoundClock() == null ? "—" : "下一局 " + nextRoundClock(), bottles.size());
    }
}
