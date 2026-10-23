package tw.exilecore.life;

import net.kyori.adventure.title.Title;
import org.bukkit.GameMode;
import org.bukkit.GameRules;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.NamespacedKey;
import org.bukkit.Particle;
import org.bukkit.Sound;
import org.bukkit.World;
import org.bukkit.WorldCreator;
import org.bukkit.block.Block;
import org.bukkit.entity.Display;
import org.bukkit.entity.Entity;
import org.bukkit.entity.ItemDisplay;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;
import org.bukkit.persistence.PersistentDataType;
import org.bukkit.util.Transformation;
import org.bukkit.util.Vector;
import org.joml.AxisAngle4f;
import org.joml.Vector3f;
import tw.exilecore.ExileCore;
import tw.exilecore.currency.CurrencyType;
import tw.exilecore.item.ItemRenderer;

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
 * 生活玩法「星骸之海」（2026-10）：海域世界、每人一塊筏、漂流物與星索鉤、筏的建造、攔截網、精力、生活等級、材料與製作；
 * 設施、研究、料理、種田、釣魚、渦齒鯊；航行與小島、海象、瓶中信收集（{@link LifeIslands}）；深淵巨獸淵鯨（{@link LifeWhale}）。
 * 材料、生活經驗、精力、鉤子都記在帳號進度（life.*，帳號共用；材料不是物品，所以不能交易）。
 */
public final class LifeService {

    public static final NamespacedKey WORLD_KEY = new NamespacedKey("exilecore", "sea");

    static final String XP = "life.xp";
    static final String HOOK = "life.hook";
    static final String PLOT = "life.plot";
    static final String STAMINA = "life.sta";
    static final String CURRENCY = "life.cur";
    static final String MATERIAL = "life.mat.";
    static final String RESEARCH = "life.res";
    static final String FOOD = "life.food";
    static final String CROPS = "life.crops";
    static final String LETTERS = "life.letters";
    static final String WHALE = "life.whale";
    // 2026-10-23
    static final String DEX = "life.dex";
    static final String ZONE = "life.zone";
    static final String BED = "life.bed";
    static final String ORDERS = "life.orders";
    static final String LIKES = "life.likes";
    static final String KING = "life.king";
    static final String CHAMP = "life.champ";
    static final String HOOK_ITEM = "hook";

    /** 一個人的生活資料（帳號共用；記憶體裡改，定時寫進帳號進度）。 */
    static final class Profile {
        final Map<String, Integer> materials = new LinkedHashMap<>();
        long xp;
        int hook;
        int plot = -1;
        String staminaDay = "";
        int staminaUsed;
        String currencyDay = "";
        int currencyCount;
        int staminaRestored;
        final Set<String> research = new java.util.LinkedHashSet<>();
        String food;
        long foodUntil;
        /** 種下去的作物：「x,y,z」→「作物@種下的時間」。 */
        final Map<String, String> crops = new LinkedHashMap<>();
        /** 讀過的瓶中信（位元）。 */
        long letters;
        /** 打倒淵鯨幾次。 */
        int whaleKills;
        // 2026-10-23
        /** 魚圖鑑：魚 → {釣過幾條, 最長（0.1 公分）}。 */
        final Map<String, long[]> dex = new LinkedHashMap<>();
        /** 海域（海圖桌選）。 */
        String zone = LifeConfig.DEFAULT_ZONE;
        String bedDay = "";
        String ordersDay = "";
        int ordersDone;
        String likesDay = "";
        int likesCount;
        final Set<Integer> likedPlots = new HashSet<>();
        int kingKills;
        /** 釣魚大賽贏的那一週（最近結算的那一週才有稱號）。 */
        String champWeek = "";
        boolean dirty;
    }

    /** 一個漂流物（畫面上是 ItemDisplay）。 */
    private static final class Drift {
        final LifeConfig.Drift type;
        final ItemDisplay display;
        double x;
        double z;
        final double baseY;
        int age;
        boolean pulling;
        int pullAge;
        Location pullFrom;

        Drift(final LifeConfig.Drift type, final ItemDisplay display, final double x, final double baseY, final double z) {
            this.type = type;
            this.display = display;
            this.x = x;
            this.baseY = baseY;
            this.z = z;
        }
    }

    /** 一個人在海上的漂流物。 */
    private static final class Session {
        final List<Drift> drifts = new ArrayList<>();
        int nextSpawn;
        /** 釣魚：浮標的位置、幾 tick 咬鉤、咬鉤到幾 tick（沒在釣 null）。 */
        Location bobber;
        int biteAt;
        int biteUntil;
        int fishTick;
        /** 作物的樣子（「x,y,z」→ 顯示）。 */
        final Map<String, ItemDisplay> cropViews = new HashMap<>();
        /** 渦齒鯊。 */
        org.bukkit.entity.LivingEntity shark;
        Location sharkTarget;
        int sharkBite;
        int sharkAge;
        int nextShark = -1;
    }

    private final ExileCore plugin;
    private final LifeConfig config;
    private final NamespacedKey itemKey;
    private final NamespacedKey driftKey;
    private final RandomGenerator random;
    private World world;
    private final Map<UUID, Profile> profiles = new HashMap<>();
    private final Map<UUID, Session> sessions = new HashMap<>();
    /** 攔截網裡攔到的東西：「x,y,z」→ 漂流物代號。 */
    private final Map<String, List<String>> nets = new HashMap<>();
    /**
     * 2026-10-18：盆被誰種了（全域、存 life_pots.txt）：「x,y,z」→ 帳號 UUID；{@link #POT_CLEARED} = 盆拆掉了、誰的作物都不算。
     * 以前只看在線玩家的資料，種的人下線後別人可以在同一盆再種一份。
     */
    private final Map<String, String> pots = new HashMap<>();
    private static final String POT_CLEARED = "-";
    private final Map<Integer, UUID> plotOwners = new HashMap<>();
    private int nextPlot;
    private final Set<UUID> diedAtSea = new HashSet<>();
    private int tickCount;
    /** 航行、小島、海象。 */
    private final LifeIslands islands;
    private final LifeWhale whale;
    /** 2026-10-23：圖鑑、大賽、委託、魚籠、按讚、鯊王。 */
    private final LifeExtras extras;

    LifeService(final ExileCore plugin, final LifeConfig config) {
        this.plugin = plugin;
        this.config = config;
        this.itemKey = new NamespacedKey(plugin, "life_item");
        this.driftKey = new NamespacedKey(plugin, "life_drift");
        this.random = plugin.items().random();
        this.islands = new LifeIslands(plugin, this);
        this.whale = new LifeWhale(plugin, this);
        this.extras = new LifeExtras(plugin, this);
        loadAccess();
    }

    // ---- 開放名單（2026-10：先只開給管理員，/sea allow 加人、/sea open 全部開放；記在 life_access.txt）----

    private boolean openToAll;
    private final Map<UUID, String> allowed = new LinkedHashMap<>();

    private Path accessFile() {
        return plugin.dataFolder().resolve("life_access.txt");
    }

    private void loadAccess() {
        openToAll = false;
        allowed.clear();
        try {
            if (!Files.exists(accessFile())) {
                return;
            }
            for (final String raw : Files.readAllLines(accessFile())) {
                final String line = raw.trim();
                if (line.isEmpty() || line.startsWith("#")) {
                    continue;
                }
                if (line.equalsIgnoreCase("open")) {
                    openToAll = true;
                    continue;
                }
                final int comma = line.indexOf(',');
                try {
                    allowed.put(UUID.fromString(comma < 0 ? line : line.substring(0, comma).trim()), comma < 0 ? "?" : line.substring(comma + 1).trim());
                } catch (final IllegalArgumentException ignored) {
                    // 壞掉的一行跳過
                }
            }
        } catch (final java.io.IOException e) {
            plugin.getSLF4JLogger().warn("{}：讀不到 life_access.txt：{}", config.name(), e.toString());
        }
    }

    private void saveAccess() {
        final StringBuilder out = new StringBuilder("# 星骸之海的開放名單：第一行 open = 全部開放；其他每行「UUID,名字」是先開放的玩家（管理員一定能進）\n");
        if (openToAll) {
            out.append("open\n");
        }
        for (final Map.Entry<UUID, String> entry : allowed.entrySet()) {
            out.append(entry.getKey()).append(',').append(entry.getValue()).append('\n');
        }
        try {
            Files.writeString(accessFile(), out.toString());
        } catch (final java.io.IOException e) {
            plugin.getSLF4JLogger().warn("{}：寫不進 life_access.txt：{}", config.name(), e.toString());
        }
    }

    /** 能不能進星骸之海：管理員、全部開放、或在名單上。 */
    public boolean canEnter(final Player player) {
        return openToAll || player.hasPermission(ExileCore.PERMISSION_ADMIN) || allowed.containsKey(player.getUniqueId());
    }

    public boolean openToAll() {
        return openToAll;
    }

    Map<UUID, String> allowed() {
        return java.util.Collections.unmodifiableMap(allowed);
    }

    void setOpenToAll(final boolean open) {
        openToAll = open;
        saveAccess();
    }

    /** 加進名單；已經在名單上回傳 false。 */
    boolean allow(final UUID id, final String name) {
        final boolean added = !allowed.containsKey(id);
        allowed.put(id, name);
        saveAccess();
        return added;
    }

    /** 從名單拿掉；本來不在回傳 false。 */
    boolean deny(final UUID id) {
        final boolean removed = allowed.remove(id) != null;
        saveAccess();
        return removed;
    }

    LifeWhale whale() {
        return whale;
    }

    NamespacedKey itemKey() {
        return itemKey;
    }

    /** 這是不是淵鯨（MapService：每秒傷害上限比照終局、掉落不吃物品數量與稀有度）。 */
    /** 2026-10-23 晚：這是不是渦齒鯊王（MapService：每秒傷害上限照比照的極境）。 */
    public boolean isSharkKing(final org.bukkit.entity.Entity entity) {
        return world != null && world.equals(entity.getWorld()) && extras.isKing(entity);
    }

    /** 鯊王比照的極境（maps.yml）；沒有 null。 */
    public tw.exilecore.map.MapConfig.Uber sharkKingUber() {
        return extras.likeUber();
    }

    public boolean isWhale(final org.bukkit.entity.Entity entity) {
        return world != null && world.equals(entity.getWorld()) && whale.isWhale(entity);
    }

    LifeIslands islands() {
        return islands;
    }

    LifeExtras extras() {
        return extras;
    }

    int tickCount() {
        return tickCount;
    }

    /** 2026-10-23：晚上（海域世界的時間）。 */
    boolean night() {
        if (world == null) {
            return false;
        }
        final long time = world.getTime();
        return time >= 13_000 && time <= 23_000;
    }

    /** 2026-10-23：鯊王在的筏也是戰鬥區（跟渦齒鯊一樣）。 */
    void markCombat(final int plot, final boolean on) {
        if (on) {
            sharkPlots.add(plot);
        } else {
            sharkPlots.remove(plot);
        }
    }

    NamespacedKey driftKey() {
        return driftKey;
    }

    public LifeConfig config() {
        return config;
    }

    // ---- 世界 ----

    void loadWorld() {
        world = plugin.getServer().getWorld(WORLD_KEY);
        if (world == null) {
            try {
                world = WorldCreator.ofKey(WORLD_KEY).generator(new OceanGenerator(config.seaLevel())).generateStructures(false).createWorld();
            } catch (final RuntimeException e) {
                plugin.getSLF4JLogger().error("{}：海域世界載入失敗", config.name(), e);
                return;
            }
        }
        if (world == null) {
            return;
        }
        world.setGameRule(GameRules.SPAWN_MOBS, false);
        world.setGameRule(GameRules.SPAWN_MONSTERS, false);
        world.setGameRule(GameRules.SPAWN_PATROLS, false);
        world.setGameRule(GameRules.SPAWN_PHANTOMS, false);
        world.setGameRule(GameRules.SPAWN_WANDERING_TRADERS, false);
        world.setGameRule(GameRules.MOB_GRIEFING, false);
        world.setGameRule(GameRules.RANDOM_TICK_SPEED, 0);
        world.setGameRule(GameRules.ADVANCE_WEATHER, false);
        world.setGameRule(GameRules.BLOCK_DROPS, false);
        world.setGameRule(GameRules.KEEP_INVENTORY, true);
        world.setGameRule(GameRules.PVP, false);
        world.setGameRule(GameRules.FALL_DAMAGE, false);
        world.setGameRule(GameRules.WATER_SOURCE_CONVERSION, false);
        world.setGameRule(GameRules.ADVANCE_TIME, true); // 海上有日夜
        world.setStorm(false);
        world.setClearWeatherDuration(Integer.MAX_VALUE / 2);
        loadPlots();
        loadNets();
        loadPots(); // 2026-10-18
        loadMembers();
        loadPlotLevels();
        extras.loadTraps(); // 2026-10-23
        extras.loadTournament();
        extras.loadLikes();
    }

    public World world() {
        return world;
    }

    /** 筏的建造規則管的地方（海域世界裡、小島與淵鯨的場地以外；渦齒鯊在咬的筏也算）。 */
    boolean raftArea(final Location location) {
        return world != null && location != null && world.equals(location.getWorld()) && !islands.onIsland(location) && !whale.inArena(location);
    }

    /** 小島、淵鯨的場地存在的時候抓住那幾個區塊（不卸載）；hold false 放掉。 */
    static void holdChunks(final ExileCore plugin, final World world, final int cx, final int cz, final int radius, final boolean hold) {
        for (int x = (cx - radius - 2) >> 4; x <= (cx + radius + 2) >> 4; x++) {
            for (int z = (cz - radius - 2) >> 4; z <= (cz + radius + 2) >> 4; z++) {
                if (hold) {
                    world.addPluginChunkTicket(x, z, plugin);
                } else {
                    world.removePluginChunkTicket(x, z, plugin);
                }
            }
        }
    }

    /** 是不是生活區（海上：跟營地一樣不能放技能、快捷列照原版用）；渦齒鯊在咬的筏、小島、淵鯨的場地是戰鬥區。 */
    public boolean lifeMode(final Location location) {
        if (world == null || location == null || !world.equals(location.getWorld())) {
            return false;
        }
        return !sharkPlots.contains(plotAt(location.getX(), location.getZ())) && !islands.onIsland(location) && !whale.inArena(location);
    }

    /** 正在被渦齒鯊攻擊的筏。 */
    private final Set<Integer> sharkPlots = new HashSet<>();
    /** 淨水器下一次能用的時間（「x,y,z」→ 毫秒）。 */
    private final Map<String, Long> purifierReady = new HashMap<>();

    // ---- 筏的位置 ----

    private Path plotFile() {
        return plugin.dataFolder().resolve("life_plots.txt");
    }

    private void loadPlots() {
        plotOwners.clear();
        nextPlot = 0;
        try {
            if (Files.exists(plotFile())) {
                for (final String line : Files.readAllLines(plotFile())) {
                    final String[] parts = line.trim().split(",");
                    if (parts.length == 2) {
                        final int index = Integer.parseInt(parts[0]);
                        plotOwners.put(index, UUID.fromString(parts[1]));
                        nextPlot = Math.max(nextPlot, index + 1);
                    }
                }
            }
        } catch (final java.io.IOException | RuntimeException e) {
            plugin.getSLF4JLogger().warn("{}：讀不到 life_plots.txt：{}", config.name(), e.toString());
        }
    }

    /** 第 index 塊筏的中心（x、z）。 */
    int[] plotCenter(final int index) {
        return new int[] {(index % config.plotsPerRow()) * config.plotSpacing(), (index / config.plotsPerRow()) * config.plotSpacing()};
    }

    /** 這個位置在第幾塊筏的範圍（不在任何一塊 -1）。 */
    int plotAt(final double x, final double z) {
        final int spacing = config.plotSpacing();
        final int ix = Math.floorDiv((int) Math.floor(x) + spacing / 2, spacing);
        final int iz = Math.floorDiv((int) Math.floor(z) + spacing / 2, spacing);
        if (ix < 0 || ix >= config.plotsPerRow() || iz < 0) {
            return -1;
        }
        return iz * config.plotsPerRow() + ix;
    }

    /** 這個人的筏（第一次去的時候分一塊）；帳號還沒載入 -1。 */
    private int ensurePlot(final Player player, final Profile profile) {
        if (profile.plot >= 0) {
            plotOwners.putIfAbsent(profile.plot, player.getUniqueId());
            nextPlot = Math.max(nextPlot, profile.plot + 1); // life_plots.txt 不見了也不會分到別人的
            return profile.plot;
        }
        final int index = nextPlot++;
        plotOwners.put(index, player.getUniqueId());
        try {
            Files.writeString(plotFile(), index + "," + player.getUniqueId() + System.lineSeparator(),
                    java.nio.file.StandardOpenOption.CREATE, java.nio.file.StandardOpenOption.APPEND);
        } catch (final java.io.IOException e) {
            plugin.getSLF4JLogger().warn("{}：寫不進 life_plots.txt：{}", config.name(), e.toString());
        }
        profile.plot = index;
        profile.dirty = true;
        flush(player);
        return index;
    }

    // ---- 一起玩（2026-10-16）：拜訪、共用木筏、隊伍同行 ----

    /** 現在在別人筏上的人 → 那塊筏（拜訪、共用、跟隊長）；回自己的筏、離開海域就拿掉。 */
    private final Map<UUID, Integer> guests = new HashMap<>();
    /** 共用木筏的成員：筏 → 成員（UUID → 名字；life_members.txt）。成員可以在那塊筏上蓋、拆、收網、種田、掌舵。 */
    private final Map<Integer, Map<UUID, String>> members = new HashMap<>();
    /** 筏主的生活等級（筏主不在線時，成員蓋筏、訪客的漂流物範圍照這個；life_plot_levels.txt）。 */
    private final Map<Integer, Integer> plotLevels = new HashMap<>();
    /** 拜訪邀請：被邀請的人 → 邀請。 */
    private final Map<UUID, Invite> invites = new HashMap<>();
    static final long INVITE_MILLIS = 120_000;

    record Invite(int plot, UUID from, String fromName, long until) {
    }

    private Path membersFile() {
        return plugin.dataFolder().resolve("life_members.txt");
    }

    private Path plotLevelsFile() {
        return plugin.dataFolder().resolve("life_plot_levels.txt");
    }

    private void loadMembers() {
        members.clear();
        try {
            if (Files.exists(membersFile())) {
                for (final String raw : Files.readAllLines(membersFile())) {
                    final String line = raw.trim();
                    if (line.isEmpty() || line.startsWith("#")) {
                        continue;
                    }
                    final String[] parts = line.split(",", 3);
                    try {
                        members.computeIfAbsent(Integer.parseInt(parts[0].trim()), k -> new LinkedHashMap<>())
                                .put(UUID.fromString(parts[1].trim()), parts.length > 2 ? parts[2].trim() : "?");
                    } catch (final RuntimeException ignored) {
                        // 壞掉的一行跳過
                    }
                }
            }
        } catch (final java.io.IOException e) {
            plugin.getSLF4JLogger().warn("{}：讀不到 life_members.txt：{}", config.name(), e.toString());
        }
    }

    private void saveMembers() {
        final StringBuilder out = new StringBuilder("# 星骸之海的共用木筏：每行「筏的編號,成員 UUID,名字」（/sea member 改）\n");
        for (final Map.Entry<Integer, Map<UUID, String>> plot : members.entrySet()) {
            for (final Map.Entry<UUID, String> member : plot.getValue().entrySet()) {
                out.append(plot.getKey()).append(',').append(member.getKey()).append(',').append(member.getValue()).append('\n');
            }
        }
        try {
            Files.writeString(membersFile(), out.toString());
        } catch (final java.io.IOException e) {
            plugin.getSLF4JLogger().warn("{}：寫不進 life_members.txt：{}", config.name(), e.toString());
        }
    }

    private void loadPlotLevels() {
        plotLevels.clear();
        try {
            if (Files.exists(plotLevelsFile())) {
                for (final String raw : Files.readAllLines(plotLevelsFile())) {
                    final String[] parts = raw.trim().split(",");
                    if (parts.length == 2) {
                        try {
                            plotLevels.put(Integer.parseInt(parts[0].trim()), Integer.parseInt(parts[1].trim()));
                        } catch (final NumberFormatException ignored) {
                            // 壞掉的一行跳過
                        }
                    }
                }
            }
        } catch (final java.io.IOException e) {
            plugin.getSLF4JLogger().warn("{}：讀不到 life_plot_levels.txt：{}", config.name(), e.toString());
        }
    }

    /** 記下筏主現在的生活等級（變了才寫檔）。 */
    private void rememberLevel(final Profile profile) {
        if (profile.plot < 0) {
            return;
        }
        final int level = config.levelOf(profile.xp);
        final Integer old = plotLevels.put(profile.plot, level);
        if (old != null && old == level) {
            return;
        }
        final StringBuilder out = new StringBuilder();
        for (final Map.Entry<Integer, Integer> entry : plotLevels.entrySet()) {
            out.append(entry.getKey()).append(',').append(entry.getValue()).append('\n');
        }
        try {
            Files.writeString(plotLevelsFile(), out.toString());
        } catch (final java.io.IOException e) {
            plugin.getSLF4JLogger().warn("{}：寫不進 life_plot_levels.txt：{}", config.name(), e.toString());
        }
    }

    /** 這個人現在待的筏（在別人的筏上就是那塊；沒有 -1）。 */
    int activePlot(final Player player) {
        final Integer guest = guests.get(player.getUniqueId());
        if (guest != null) {
            return guest;
        }
        final Profile profile = profiles.get(player.getUniqueId());
        return profile == null ? -1 : profile.plot;
    }

    /** 能不能在這塊筏上蓋、拆、收網、種田、掌舵：筏主或共用成員。 */
    boolean canBuild(final Player player, final Profile profile, final int plot) {
        if (plot < 0 || profile == null) {
            return false;
        }
        return plot == profile.plot || members.getOrDefault(plot, Map.of()).containsKey(player.getUniqueId());
    }

    /** 2026-10-23 晚：這塊筏最大幾×幾：筏主的生活等級，加上筏主累積贊助最高階送的格數（筏主在線才算）。 */
    int raftSize(final int plot) {
        final UUID owner = plotOwners.get(plot);
        final Player online = owner == null ? null : plugin.getServer().getPlayer(owner);
        final int bonus = online == null || plugin.sponsors() == null ? 0 : plugin.sponsors().raftSizeBonus(online);
        return Math.min(config.plotSpacing() / 2, config.raftSize(plotLevel(plot)) + bonus);
    }

    /** 筏主的生活等級（決定筏多大）：筏主在線照現在的，不在線照記下來的。 */
    int plotLevel(final int plot) {
        final UUID owner = plotOwners.get(plot);
        final Profile profile = owner == null ? null : profiles.get(owner);
        if (profile != null) {
            return config.levelOf(profile.xp);
        }
        return plotLevels.getOrDefault(plot, 1);
    }

    /** 筏主的名字。 */
    String ownerName(final int plot) {
        final UUID owner = plotOwners.get(plot);
        if (owner == null) {
            return "?";
        }
        final Player online = plugin.getServer().getPlayer(owner);
        if (online != null) {
            return online.getName();
        }
        final String name = plugin.getServer().getOfflinePlayer(owner).getName();
        return name == null ? "?" : name;
    }

    /** 筏主（2026-10-16，木筏管理選單用）；沒人 null。 */
    UUID ownerOf(final int plot) {
        return plotOwners.get(plot);
    }

    /** 2026-10-23：這塊筏的海域（筏主選的；筏主不在線就是一開始的海域）。 */
    String zoneOf(final int plot) {
        final UUID owner = plotOwners.get(plot);
        final Profile profile = owner == null ? null : profiles.get(owner);
        return profile == null ? LifeConfig.DEFAULT_ZONE : profile.zone;
    }

    /** 2026-10-23：選海域（海圖桌）；回傳問題，成功 null。 */
    String chooseZone(final Player player, final LifeConfig.Zone zone) {
        final Profile profile = profile(player);
        if (profile == null) {
            return "帳號資料載入中，請稍等";
        }
        if (config.levelOf(profile.xp) < zone.level()) {
            return "要生活等級 " + zone.level();
        }
        if (zone.id().equals(profile.zone)) {
            return "已經在" + zone.name() + "了";
        }
        profile.zone = zone.id();
        profile.dirty = true;
        flush(player);
        player.sendMessage(plugin.text().prefixed("<" + config.color() + ">換到" + ItemRenderer.escape(zone.name()) + "<gray>：" + ItemRenderer.escape(zone.description())));
        player.playSound(player.getLocation(), Sound.ENTITY_BOAT_PADDLE_WATER, 1f, 1f);
        return null;
    }

    /** 這個人的筏（分過的；沒分過 -1）。 */
    private int plotOf(final UUID owner) {
        final Profile profile = profiles.get(owner);
        if (profile != null && profile.plot >= 0) {
            return profile.plot;
        }
        for (final Map.Entry<Integer, UUID> entry : plotOwners.entrySet()) {
            if (entry.getValue().equals(owner)) {
                return entry.getKey();
            }
        }
        return -1;
    }

    /** 站在這塊筏上（附近）的人：一起出航、一起打淵鯨。 */
    List<Player> crewOn(final int plot) {
        final List<Player> crew = new ArrayList<>();
        if (world == null || plot < 0) {
            return crew;
        }
        for (final Player player : world.getPlayers()) {
            if (player.isDead() || player.getGameMode() == GameMode.SPECTATOR || activePlot(player) != plot) {
                continue;
            }
            if (plotAt(player.getLocation().getX(), player.getLocation().getZ()) == plot) {
                crew.add(player);
            }
        }
        return crew;
    }

    /** 共用成員（名字）。 */
    Map<UUID, String> membersOf(final int plot) {
        return java.util.Collections.unmodifiableMap(members.getOrDefault(plot, Map.of()));
    }

    /** 這個人是哪幾塊筏的共用成員。 */
    List<Integer> memberOf(final UUID player) {
        final List<Integer> plots = new ArrayList<>();
        for (final Map.Entry<Integer, Map<UUID, String>> entry : members.entrySet()) {
            if (entry.getValue().containsKey(player)) {
                plots.add(entry.getKey());
            }
        }
        return plots;
    }

    /** 加共用成員（筏主）；回傳問題，成功 null。 */
    String addMember(final Player owner, final UUID id, final String name) {
        final Profile profile = profile(owner);
        if (profile == null) {
            return "帳號資料載入中，請稍等";
        }
        if (profile.plot < 0) {
            return "你還沒有木筏（先 /sea 去一次）";
        }
        if (id.equals(owner.getUniqueId())) {
            return "不用加自己";
        }
        final Map<UUID, String> list = members.computeIfAbsent(profile.plot, k -> new LinkedHashMap<>());
        if (list.containsKey(id)) {
            return name + " 已經是共用成員了";
        }
        if (list.size() >= 8) {
            return "共用成員最多 8 個";
        }
        list.put(id, name);
        saveMembers();
        return null;
    }

    /** 拿掉共用成員（筏主）；回傳問題，成功 null。 */
    String removeMember(final Player owner, final UUID id, final String name) {
        final Profile profile = profile(owner);
        if (profile == null || profile.plot < 0) {
            return "你還沒有木筏";
        }
        final Map<UUID, String> list = members.get(profile.plot);
        if (list == null || list.remove(id) == null) {
            return name + " 不是你的共用成員";
        }
        if (list.isEmpty()) {
            members.remove(profile.plot);
        }
        saveMembers();
        return null;
    }

    /** 邀請別人來自己待的這塊筏（筏主、共用成員才能邀）；回傳問題，成功 null。 */
    String invite(final Player from, final Player target) {
        final Profile profile = profile(from);
        final int plot = activePlot(from);
        if (profile == null) {
            return "帳號資料載入中，請稍等";
        }
        if (world == null || !world.equals(from.getWorld()) || plot < 0) {
            return "要在木筏上才能邀請";
        }
        if (!canBuild(from, profile, plot)) {
            return "只有筏主、共用成員能邀請別人來";
        }
        if (target.equals(from)) {
            return "不用邀請自己";
        }
        if (!canEnter(target)) {
            return target.getName() + " 還不能進" + config.name();
        }
        invites.put(target.getUniqueId(), new Invite(plot, from.getUniqueId(), from.getName(), System.currentTimeMillis() + INVITE_MILLIS));
        target.sendMessage(plugin.text().prefixed("<" + config.color() + ">" + ItemRenderer.escape(from.getName()) + " 邀請你去 "
                + ItemRenderer.escape(ownerName(plot)) + " 的木筏 <click:run_command:'/sea accept'><hover:show_text:'<gray>點一下前往'><green>[前往]</hover></click>"
                + "<gray>（/sea accept，" + INVITE_MILLIS / 60_000 + " 分鐘內有效）"));
        return null;
    }

    /** 接受邀請；回傳問題，成功 null。 */
    String accept(final Player player) {
        final Invite invite = invites.get(player.getUniqueId());
        if (invite == null || invite.until() < System.currentTimeMillis()) {
            invites.remove(player.getUniqueId());
            return "沒有人邀請你（或邀請過期了）";
        }
        // 2026-10：去不了（正在打淵鯨、資料載入中…）邀請留著，等一下可以再接受
        final String problem = travelTo(player, invite.plot(), invite.fromName() + " 的邀請");
        if (problem == null) {
            invites.remove(player.getUniqueId());
        }
        return problem;
    }

    /**
     * 去某人的木筏：自己的、你是共用成員的、同一隊的人在的那塊都可以直接去；其他要對方先邀請（/sea invite）。
     * 回傳問題，成功 null。
     */
    String visit(final Player player, final UUID target, final String name) {
        if (target.equals(player.getUniqueId())) {
            return travel(player, false);
        }
        final Player online = plugin.getServer().getPlayer(target);
        final boolean party = plugin.party() != null && plugin.party().sameGroup(player.getUniqueId(), target);
        // 對方在海上：去他現在待的那塊（他可能也在別人的筏上）；不在就去他自己的筏
        final int plot = online != null && world != null && world.equals(online.getWorld()) && activePlot(online) >= 0 ? activePlot(online) : plotOf(target);
        if (plot < 0) {
            return name + " 還沒有木筏";
        }
        final Profile profile = profile(player);
        final Invite invite = invites.get(player.getUniqueId());
        final boolean invited = invite != null && invite.plot() == plot && invite.until() >= System.currentTimeMillis();
        if (!party && !invited && !canBuild(player, profile, plot)) {
            return "要 " + name + " 先邀請你（/sea invite），或是同一隊、共用成員才能直接去";
        }
        if (invited) {
            invites.remove(player.getUniqueId());
        }
        return travelTo(player, plot, party ? "隊伍" : null);
    }

    /** 把別人請離自己待的這塊筏（筏主、共用成員）；回傳問題，成功 null。 */
    String kick(final Player from, final Player target) {
        final Profile profile = profile(from);
        final int plot = activePlot(from);
        if (!canBuild(from, profile, plot)) {
            return "只有筏主、共用成員能請人離開";
        }
        if (activePlot(target) != plot || target.equals(from)) {
            return target.getName() + " 不在這塊筏上";
        }
        final Profile other = profile(target);
        if (other != null && other.plot == plot) {
            return "這是 " + target.getName() + " 自己的筏";
        }
        final String problem = travel(target, false);
        if (problem != null) {
            guests.remove(target.getUniqueId());
            target.performCommand("town");
        }
        target.sendMessage(plugin.text().prefixed("<gray>" + ItemRenderer.escape(from.getName()) + " 請你離開了這塊筏"));
        return null;
    }

    /** 隊長現在待的筏（隊長在海上、不是自己）；沒有 -1。 */
    int leaderPlot(final Player player) {
        if (plugin.party() == null || world == null) {
            return -1;
        }
        final tw.exilecore.party.PartyRoster.Party party = plugin.party().party(player.getUniqueId());
        if (party == null || party.leader().equals(player.getUniqueId())) {
            return -1;
        }
        final Player leader = plugin.getServer().getPlayer(party.leader());
        if (leader == null || !world.equals(leader.getWorld())) {
            return -1;
        }
        return activePlot(leader);
    }

    /** 筏的範圍（相對中心）：[最小, 最大]。 */
    static int[] extent(final int size) {
        return new int[] {-(size / 2), size - size / 2 - 1};
    }

    Material floorBlock() {
        final LifeConfig.Item floor = floorItem();
        return floor == null ? Material.BAMBOO_MOSAIC : material(floor.block(), Material.BAMBOO_MOSAIC);
    }

    private LifeConfig.Item floorItem() {
        for (final LifeConfig.Item item : config.items().values()) {
            if (item.place() == LifeConfig.Place.FLOOR) {
                return item;
            }
        }
        return null;
    }

    static Material material(final String name, final Material fallback) {
        final Material material = Material.matchMaterial(name);
        return material == null ? fallback : material;
    }

    /** 起始筏：4×4 的地板（已經有地板就不動）。 */
    private void ensureStarter(final int[] center) {
        world.getChunkAt(center[0] >> 4, center[1] >> 4).load(true);
        final int y = config.seaLevel();
        if (world.getBlockAt(center[0], y, center[1]).getType() == floorBlock()) {
            return;
        }
        final int[] range = extent(4);
        for (int dx = range[0]; dx <= range[1]; dx++) {
            for (int dz = range[0]; dz <= range[1]; dz++) {
                world.getBlockAt(center[0] + dx, y, center[1] + dz).setType(floorBlock(), false);
            }
        }
    }

    /** 自己的筏上可以站的地方。 */
    Location raftSpawn(final int index) {
        final int[] center = plotCenter(index);
        final Location at = new Location(world, center[0] + 0.5, config.seaLevel() + 1, center[1] + 0.5);
        at.setYaw(-90); // 面向東邊（漂流物從那裡來）
        return at;
    }

    /** 前往木筏（選單、/sea）：在隊伍裡、隊長在海上就去隊長待的那塊，不然回自己的；回傳問題，成功 null。 */
    public String travel(final Player player) {
        return travel(player, true);
    }

    /** followLeader false：一定回自己的筏（/sea home、選單右鍵）。 */
    String travel(final Player player, final boolean followLeader) {
        if (followLeader) {
            final int leader = leaderPlot(player);
            final Profile profile = profiles.get(player.getUniqueId());
            if (leader >= 0 && (profile == null || leader != profile.plot)) {
                return travelTo(player, leader, "隊伍");
            }
        }
        if (world == null) {
            return config.name() + "的世界沒有載入（看主控台）";
        }
        if (!canEnter(player)) {
            return config.name() + "還沒有開放";
        }
        if (!plugin.players().get(player).hasCharacter()) {
            return "先選一個角色";
        }
        final Profile profile = profile(player);
        if (profile == null) {
            return "帳號資料載入中，請稍等";
        }
        final int index = ensurePlot(player, profile);
        rememberLevel(profile);
        ensureStarter(plotCenter(index));
        if (guests.containsKey(player.getUniqueId())) {
            leaveRaft(player);
        }
        if (plugin.zones() == null || !plugin.zones().teleport(player, raftSpawn(index), config.name())) {
            return "現在不能傳送";
        }
        guests.remove(player.getUniqueId());
        if (!hasHook(player)) {
            giveItem(player, hookItem(player));
        }
        player.showTitle(Title.title(plugin.text().parse("<" + config.color() + ">" + ItemRenderer.escape(config.name())),
                plugin.text().parse("<gray>拿著星索鉤，對準漂過來的東西按右鍵"),
                Title.Times.times(Duration.ofMillis(300), Duration.ofMillis(2200), Duration.ofMillis(600))));
        player.sendMessage(plugin.text().prefixed("<" + config.color() + ">" + ItemRenderer.escape(config.name())
                + "<gray>：漂流物從東邊漂來；材料在選單 <white>/sea</white> 做成筏的方塊；回營地：<white>/town"));
        return null;
    }

    /** 換筏之前：漂流物、島、航行、淵鯨收掉（到新的筏重新算）。 */
    private void leaveRaft(final Player player) {
        islands.leave(player);
        whale.leave(player);
        final Session session = sessions.remove(player.getUniqueId());
        if (session != null) {
            clearSession(session);
        }
    }

    /** 去別人的筏（拜訪、共用、跟隊長）；回傳問題，成功 null。 */
    String travelTo(final Player player, final int plot, final String reason) {
        if (world == null) {
            return config.name() + "的世界沒有載入（看主控台）";
        }
        if (!canEnter(player)) {
            return config.name() + "還沒有開放";
        }
        if (!plugin.players().get(player).hasCharacter()) {
            return "先選一個角色";
        }
        final Profile profile = profile(player);
        if (profile == null) {
            return "帳號資料載入中，請稍等";
        }
        ensurePlot(player, profile);
        if (plot == profile.plot) {
            return travel(player, false);
        }
        if (!plotOwners.containsKey(plot)) {
            return "那塊筏不在了";
        }
        if (whale.fighting(player)) {
            return "正在跟淵鯨打，先打完";
        }
        leaveRaft(player);
        if (plugin.zones() == null || !plugin.zones().teleport(player, raftSpawn(plot), config.name())) {
            return "現在不能傳送";
        }
        guests.put(player.getUniqueId(), plot);
        if (!hasHook(player)) {
            giveItem(player, hookItem(player));
        }
        final boolean member = canBuild(player, profile, plot);
        player.sendMessage(plugin.text().prefixed("<" + config.color() + ">來到 " + ItemRenderer.escape(ownerName(plot)) + " 的木筏"
                + (reason == null ? "" : "<gray>（" + ItemRenderer.escape(reason) + "）")
                + (member ? "<gray>：你是共用成員，可以蓋、拆、收網、掌舵" : "<gray>：可以撈東西、釣魚，蓋筏要筏主加你為共用成員")
                + "；回自己的筏：<white>/sea home"));
        for (final Player other : crewOn(plot)) {
            if (!other.equals(player)) {
                other.sendMessage(plugin.text().prefixed("<gray>" + ItemRenderer.escape(player.getName()) + " 來到了這塊筏"));
            }
        }
        return null;
    }

    // ---- 帳號資料 ----

    /** 這個人的生活資料；帳號還沒載入 null。 */
    Profile profile(final Player player) {
        final Profile cached = profiles.get(player.getUniqueId());
        if (cached != null) {
            return cached;
        }
        if (plugin.bounties() == null || !plugin.bounties().accountLoaded(player)) {
            return null;
        }
        final Profile profile = new Profile();
        profile.xp = parseLong(value(player, XP));
        profile.hook = (int) parseLong(value(player, HOOK));
        final String plot = value(player, PLOT);
        profile.plot = plot == null ? -1 : (int) parseLong(plot);
        final String[] stamina = split(value(player, STAMINA));
        profile.staminaDay = stamina[0];
        final String[] used = stamina[1].split(":");
        profile.staminaUsed = (int) parseLong(used[0]);
        profile.staminaRestored = used.length > 1 ? (int) parseLong(used[1]) : 0;
        final String research = value(player, RESEARCH);
        if (research != null) {
            for (final String id : research.split(",")) {
                if (!id.isBlank()) {
                    profile.research.add(id.trim());
                }
            }
        }
        final String[] food = split(value(player, FOOD));
        if (!food[0].isEmpty()) {
            profile.food = food[0];
            profile.foodUntil = parseLong(food[1]);
        }
        final String crops = value(player, CROPS);
        if (crops != null) {
            for (final String entry : crops.split(";")) {
                final int eq = entry.indexOf('=');
                if (eq > 0) {
                    profile.crops.put(entry.substring(0, eq), entry.substring(eq + 1));
                }
            }
        }
        final String[] currency = split(value(player, CURRENCY));
        profile.currencyDay = currency[0];
        profile.currencyCount = (int) parseLong(currency[1]);
        profile.letters = parseLong(value(player, LETTERS));
        profile.whaleKills = (int) parseLong(value(player, WHALE));
        // 2026-10-23
        profile.dex.putAll(LifeExtras.decodeDex(value(player, DEX)));
        final String zone = value(player, ZONE);
        profile.zone = zone == null || zone.isBlank() ? LifeConfig.DEFAULT_ZONE : zone.trim();
        final String bed = value(player, BED);
        profile.bedDay = bed == null ? "" : bed.trim();
        final String[] orders = split(value(player, ORDERS));
        profile.ordersDay = orders[0];
        profile.ordersDone = (int) parseLong(orders[1]);
        final String[] likes = split(value(player, LIKES));
        profile.likesDay = likes[0];
        final String[] likeBits = likes[1].split(":");
        profile.likesCount = (int) parseLong(likeBits[0]);
        if (likeBits.length > 1) {
            for (final String plotText : likeBits[1].split(",")) {
                if (!plotText.isBlank()) {
                    profile.likedPlots.add((int) parseLong(plotText));
                }
            }
        }
        profile.kingKills = (int) parseLong(value(player, KING));
        final String champ = value(player, CHAMP);
        profile.champWeek = champ == null ? "" : champ.trim();
        for (final String id : config.materials().keySet()) {
            final long count = parseLong(value(player, MATERIAL + id));
            if (count > 0) {
                profile.materials.put(id, (int) Math.min(Integer.MAX_VALUE, count));
            }
        }
        reconcilePots(player, profile); // 2026-10-18
        profiles.put(player.getUniqueId(), profile);
        rememberLevel(profile);
        extras.claimAward(player); // 2026-10-23：釣魚大賽的獎
        return profile;
    }

    private String value(final Player player, final String key) {
        return plugin.bounties().progressValue(player, key);
    }

    private static long parseLong(final String text) {
        if (text == null || text.isBlank()) {
            return 0;
        }
        try {
            return Long.parseLong(text.trim());
        } catch (final NumberFormatException e) {
            return 0;
        }
    }

    private static String[] split(final String text) {
        if (text == null) {
            return new String[] {"", "0"};
        }
        final int colon = text.indexOf(':');
        return colon < 0 ? new String[] {"", "0"} : new String[] {text.substring(0, colon), text.substring(colon + 1)};
    }

    /** 改過的寫進帳號進度。 */
    void flush(final Player player) {
        final Profile profile = profiles.get(player.getUniqueId());
        if (profile == null || !profile.dirty || plugin.bounties() == null) {
            return;
        }
        if (plugin.bounties().commitAccount(player, values(profile), config.name())) {
            profile.dirty = false;
        }
    }

    /**
     * 2026-10-18：管理員發放／收回材料用——生活資料改在記憶體，回傳把它寫進資料庫的工作，
     * 由呼叫端放進「這筆已經領了」的同一個交易（以前材料自己先寫，當機會重複發／重複收回）。沒有要寫的回傳 null。
     */
    public tw.exilecore.character.CharacterRepository.ExtraWork adminWork(final Player player) {
        final Profile profile = profiles.get(player.getUniqueId());
        if (profile == null || !profile.dirty || plugin.bounties() == null) {
            return null;
        }
        final tw.exilecore.character.CharacterRepository.ExtraWork work = plugin.bounties().accountWork(player, values(profile));
        if (work != null) {
            profile.dirty = false;
        }
        return work;
    }

    /** 要寫回帳號進度的生活資料（2026-10-18：從 flush 抽出來，adminWork 共用）。 */
    private Map<String, String> values(final Profile profile) {
        final Map<String, String> values = new LinkedHashMap<>();
        values.put(XP, String.valueOf(profile.xp));
        values.put(HOOK, String.valueOf(profile.hook));
        if (profile.plot >= 0) {
            values.put(PLOT, String.valueOf(profile.plot));
        }
        values.put(STAMINA, profile.staminaDay + ":" + profile.staminaUsed + ":" + profile.staminaRestored);
        values.put(RESEARCH, profile.research.isEmpty() ? null : String.join(",", profile.research));
        values.put(FOOD, profile.food == null ? null : profile.food + ":" + profile.foodUntil);
        final StringBuilder crops = new StringBuilder();
        for (final Map.Entry<String, String> entry : profile.crops.entrySet()) {
            crops.append(crops.isEmpty() ? "" : ";").append(entry.getKey()).append('=').append(entry.getValue());
        }
        values.put(CROPS, crops.isEmpty() ? null : crops.toString());
        values.put(CURRENCY, profile.currencyDay + ":" + profile.currencyCount);
        values.put(LETTERS, profile.letters == 0 ? null : String.valueOf(profile.letters));
        values.put(WHALE, profile.whaleKills == 0 ? null : String.valueOf(profile.whaleKills));
        // 2026-10-23
        values.put(DEX, LifeExtras.encodeDex(profile.dex));
        values.put(ZONE, LifeConfig.DEFAULT_ZONE.equals(profile.zone) ? null : profile.zone);
        values.put(BED, profile.bedDay.isEmpty() ? null : profile.bedDay);
        values.put(ORDERS, profile.ordersDay.isEmpty() ? null : profile.ordersDay + ":" + profile.ordersDone);
        final StringBuilder liked = new StringBuilder();
        for (final int plot : profile.likedPlots) {
            liked.append(liked.isEmpty() ? "" : ",").append(plot);
        }
        values.put(LIKES, profile.likesDay.isEmpty() ? null : profile.likesDay + ":" + profile.likesCount + ":" + liked);
        values.put(KING, profile.kingKills == 0 ? null : String.valueOf(profile.kingKills));
        values.put(CHAMP, profile.champWeek.isEmpty() ? null : profile.champWeek);
        for (final String id : config.materials().keySet()) {
            final int count = profile.materials.getOrDefault(id, 0);
            values.put(MATERIAL + id, count > 0 ? String.valueOf(count) : null);
        }
        return values;
    }

    void flushAll() {
        for (final Player player : plugin.getServer().getOnlinePlayers()) {
            flush(player);
        }
        saveNets();
        savePots(); // 2026-10-18
        extras.saveTraps(); // 2026-10-23
        extras.settleIfNewWeek();
    }

    // ---- 盆被誰種了（2026-10-18）----

    private Path potFile() {
        return plugin.dataFolder().resolve("life_pots.txt");
    }

    /**
     * 跟攔截網一樣：每 60 秒、關服時寫一次，一行一盆「x,y,z=帳號 UUID@種下的時間（或 -）」。內容沒變就不寫。
     * 2026-10-18：加上種下的時間（舊格式只有 UUID 也讀得懂），同一盆兩個人都種了的時候早種的算。
     */
    private String savedPots = "";
    /** 2026-10-18：life_pots.txt 讀壞了、也備份不了：不要拿記憶體裡不完整的蓋掉它（等管理員處理）。 */
    private boolean potsUnsafe;
    /** 2026-10-18：第一次上線沒有 life_pots.txt：正在從所有帳號的作物建紀錄，建好之前不能種（不然會種到下線的人的盆上）。 */
    private boolean potsLoading;
    /** 2026-10-18：重新載入就換一代，舊的那次查詢回來不要套用。 */
    private int potsGeneration;

    private void savePots() {
        if (potsUnsafe) {
            return; // 2026-10-18：讀壞的檔案留著，不覆蓋
        }
        final StringBuilder out = new StringBuilder();
        for (final Map.Entry<String, String> entry : new java.util.TreeMap<>(pots).entrySet()) {
            out.append(entry.getKey()).append('=').append(entry.getValue()).append(System.lineSeparator());
        }
        final String text = out.toString();
        if (text.equals(savedPots)) {
            return;
        }
        try {
            final Path temp = potFile().resolveSibling("life_pots.txt.tmp");
            Files.writeString(temp, text);
            Files.move(temp, potFile(), java.nio.file.StandardCopyOption.REPLACE_EXISTING, java.nio.file.StandardCopyOption.ATOMIC_MOVE);
            savedPots = text;
        } catch (final java.io.IOException e) {
            plugin.getSLF4JLogger().warn("{}：寫不進 life_pots.txt：{}", config.name(), e.toString());
        }
    }

    private void loadPots() {
        if (!pots.isEmpty()) {
            savePots(); // 重新載入時：記憶體裡的比較新，先寫下去
        }
        pots.clear();
        savedPots = "";
        potsUnsafe = false;
        potsLoading = false;
        potsGeneration++;
        try {
            if (!Files.exists(potFile())) {
                rebuildPots(); // 2026-10-18：第一次上線：從帳號資料建
                return;
            }
            for (final String line : Files.readAllLines(potFile())) {
                final int eq = line.indexOf('=');
                if (eq > 0 && eq < line.length() - 1) {
                    pots.put(line.substring(0, eq).trim(), line.substring(eq + 1).trim());
                }
            }
        } catch (final java.io.IOException | RuntimeException e) {
            plugin.getSLF4JLogger().warn("{}：讀不到 life_pots.txt：{}", config.name(), e.toString());
            // 2026-10-18：讀壞了：先備份成 life_pots.txt.bad 才准之後覆蓋；備份不了就不寫（不蓋掉原本的）
            try {
                Files.copy(potFile(), potFile().resolveSibling("life_pots.txt.bad"), java.nio.file.StandardCopyOption.REPLACE_EXISTING);
                plugin.getSLF4JLogger().warn("{}：已備份成 life_pots.txt.bad，從帳號資料重建盆的紀錄", config.name());
            } catch (final java.io.IOException | RuntimeException backup) {
                potsUnsafe = true;
                plugin.getSLF4JLogger().warn("{}：life_pots.txt 備份不了，這次開服不會覆蓋它：{}", config.name(), backup.toString());
            }
            pots.clear();
            rebuildPots();
        }
    }

    /**
     * 2026-10-18：在資料庫執行緒上讀所有帳號的作物（account_progress 的 life.crops），每盆取最早種的那個人當紀錄。
     * 期間在線的人已經對過的盆、拆掉的盆以記憶體為準；查不到（資料庫沒開、出錯）就只靠「早種的算」的規則。
     */
    private void rebuildPots() {
        if (plugin.database() == null) {
            return;
        }
        final int generation = potsGeneration;
        potsLoading = true;
        plugin.database().async(connection -> {
            final Map<String, String> earliest = new HashMap<>();
            try (java.sql.PreparedStatement statement = connection.prepareStatement(
                    "SELECT account_uuid, progress_value FROM account_progress WHERE progress_key = ?")) {
                statement.setString(1, CROPS);
                try (java.sql.ResultSet result = statement.executeQuery()) {
                    while (result.next()) {
                        final String account = result.getString(1);
                        final String crops = result.getString(2);
                        if (account == null || crops == null) {
                            continue;
                        }
                        for (final String entry : crops.split(";")) {
                            final int eq = entry.indexOf('=');
                            if (eq <= 0) {
                                continue;
                            }
                            final String at = entry.substring(0, eq);
                            final long time = plantedAt(entry.substring(eq + 1));
                            final String before = earliest.get(at);
                            if (before == null || time < potTime(before)) {
                                earliest.put(at, potRecord(account, time));
                            }
                        }
                    }
                }
            }
            return earliest;
        }).whenComplete((earliest, error) -> plugin.getServer().getScheduler().runTask(plugin, () -> {
            if (generation != potsGeneration) {
                return;
            }
            potsLoading = false;
            if (error != null) {
                plugin.getSLF4JLogger().warn("{}：從帳號資料建盆的紀錄失敗（改用早種的算）：{}", config.name(), error.toString());
                return;
            }
            int added = 0;
            for (final Map.Entry<String, String> entry : earliest.entrySet()) {
                if (pots.containsKey(entry.getKey())) {
                    continue; // 在線的人已經對過、或盆拆掉了：記憶體為準
                }
                try {
                    if (profiles.containsKey(UUID.fromString(potOwner(entry.getValue())))) {
                        continue; // 在線、資料已載入的人：他記憶體裡的作物才是最新的
                    }
                } catch (final IllegalArgumentException ignored) {
                    continue;
                }
                pots.put(entry.getKey(), entry.getValue());
                added++;
            }
            plugin.getSLF4JLogger().info("{}：從帳號資料建好盆的紀錄（{} 盆）", config.name(), added);
        }));
    }

    /** 2026-10-18：紀錄「UUID@時間」；時間不明（舊作物沒寫）就只有 UUID。 */
    private static String potRecord(final String owner, final long time) {
        return time < 0 ? owner : owner + "@" + time;
    }

    /** 2026-10-18：紀錄裡的帳號 UUID（舊格式整串就是 UUID；「-」照樣回「-」）。 */
    private static String potOwner(final String record) {
        final int at = record.indexOf('@');
        return at < 0 ? record : record.substring(0, at);
    }

    /** 2026-10-18：紀錄裡的種下時間；舊格式（沒有時間）、壞掉的 -1。 */
    private static long potTime(final String record) {
        final int at = record.indexOf('@');
        if (at < 0) {
            return -1;
        }
        try {
            return Long.parseLong(record.substring(at + 1).trim());
        } catch (final NumberFormatException e) {
            return -1;
        }
    }

    /** 2026-10-18：作物「代號@種下的時間」的時間（沒寫時間的舊作物當 0：跟長大的算法一樣）。 */
    private static long plantedAt(final String crop) {
        final int sep = crop.indexOf('@');
        return sep < 0 ? 0 : parseLong(crop.substring(sep + 1));
    }

    /**
     * 2026-10-18：載入一個人的生活資料時對一下全域紀錄：
     * 沒有紀錄（舊資料、當機前沒寫到）→ 記成他的（不丟東西）；盆拆掉了 → 他這盆的作物不算了（告訴他）；
     * 紀錄指向別人 → 比種下的時間，早種的算（紀錄沒有時間也當他的比較早）：他比較早就把紀錄改回他、
     * 晚種的人在線就把那份拿掉（下線的話他上線時對紀錄自己會拿掉）；別人比較早就拿掉他這份；
     * 紀錄指向他但他沒種 → 清掉紀錄（不卡住盆）。
     */
    private void reconcilePots(final Player player, final Profile profile) {
        final String me = player.getUniqueId().toString();
        int broken = 0;
        int lost = 0;
        for (final Iterator<Map.Entry<String, String>> it = profile.crops.entrySet().iterator(); it.hasNext(); ) {
            final Map.Entry<String, String> crop = it.next();
            final String at = crop.getKey();
            final long mine = plantedAt(crop.getValue());
            final String record = pots.get(at);
            if (record == null) {
                pots.put(at, potRecord(me, mine));
                continue;
            }
            if (record.equals(POT_CLEARED)) {
                it.remove();
                profile.dirty = true;
                broken++;
                continue;
            }
            final String owner = potOwner(record);
            if (owner.equals(me)) {
                pots.put(at, potRecord(me, mine)); // 補上時間
                continue;
            }
            long theirs = potTime(record);
            if (theirs < 0) {
                // 舊格式沒有時間：那個人資料在記憶體的話用他作物的時間
                try {
                    final Profile other = profiles.get(UUID.fromString(owner));
                    final String planted = other == null ? null : other.crops.get(at);
                    theirs = planted == null ? -1 : plantedAt(planted);
                } catch (final IllegalArgumentException ignored) {
                    // 壞掉的值：當作沒有時間
                }
            }
            if (theirs < 0 || mine < theirs) {
                pots.put(at, potRecord(me, mine));
                dropLaterCrop(owner, at);
            } else {
                it.remove();
                profile.dirty = true;
                lost++;
            }
        }
        pots.entrySet().removeIf(entry -> potOwner(entry.getValue()).equals(me) && !profile.crops.containsKey(entry.getKey()));
        if (broken > 0) {
            player.sendMessage(plugin.text().prefixed("<gray>你種的作物有 " + broken + " 盆不見了：盆在你下線時被拆掉了"));
        }
        if (lost > 0) {
            player.sendMessage(plugin.text().prefixed("<gray>你種的作物有 " + lost + " 盆不算了：那盆別人比你早種"));
        }
    }

    /** 2026-10-18：同一盆早種的人上線了：晚種的人在線就拿掉他那份並告訴他（下線的話等他上線對紀錄時拿掉）。 */
    private void dropLaterCrop(final String owner, final String at) {
        final UUID id;
        try {
            id = UUID.fromString(owner);
        } catch (final IllegalArgumentException ignored) {
            return;
        }
        final Profile theirs = profiles.get(id);
        if (theirs == null || theirs.crops.remove(at) == null) {
            return;
        }
        theirs.dirty = true;
        final Player other = plugin.getServer().getPlayer(id);
        if (other != null) {
            other.sendMessage(plugin.text().prefixed("<gray>你在 " + at + " 種的作物不算了：那盆別人比你早種（他剛上線）"));
        }
    }

    /** 2026-10-18：收成、作物不見了：紀錄還是他的才清。 */
    private void releasePot(final Player player, final String at) {
        final String record = pots.get(at);
        if (record != null && potOwner(record).equals(player.getUniqueId().toString())) {
            pots.remove(at);
        }
    }

    /** 2026-10-18：拆掉的是盆：記成拆掉了（下線的人上線時作物不算了）。 */
    private void clearPot(final Block block) {
        final LifeConfig.Item pot = config.itemByBlock(block.getType().name());
        if (pot != null && pot.station() != null && pot.station().startsWith("planter")) {
            pots.put(key(block.getX(), block.getY(), block.getZ()), POT_CLEARED);
        }
    }

    // ---- 攔截網裡的東西（2026-10-17：以前只在記憶體，重開伺服器就沒了）----

    private Path netFile() {
        return plugin.dataFolder().resolve("life_nets.txt");
    }

    /** 每 60 秒、關服時寫一次：一行一張網「x,y,z=漂流物代號;代號…」。內容沒變就不寫。 */
    private String savedNets = "";

    private void saveNets() {
        final StringBuilder out = new StringBuilder();
        for (final Map.Entry<String, List<String>> entry : new java.util.TreeMap<>(nets).entrySet()) {
            if (!entry.getValue().isEmpty()) {
                out.append(entry.getKey()).append('=').append(String.join(";", entry.getValue())).append(System.lineSeparator());
            }
        }
        final String text = out.toString();
        if (text.equals(savedNets)) {
            return;
        }
        try {
            final Path temp = netFile().resolveSibling("life_nets.txt.tmp");
            Files.writeString(temp, text);
            Files.move(temp, netFile(), java.nio.file.StandardCopyOption.REPLACE_EXISTING, java.nio.file.StandardCopyOption.ATOMIC_MOVE);
            savedNets = text;
        } catch (final java.io.IOException e) {
            plugin.getSLF4JLogger().warn("{}：寫不進 life_nets.txt：{}", config.name(), e.toString());
        }
    }

    private void loadNets() {
        if (!nets.isEmpty()) {
            saveNets(); // 重新載入（reload）時：記憶體裡的比較新，先寫下去
        }
        nets.clear();
        savedNets = "";
        try {
            if (!Files.exists(netFile())) {
                return;
            }
            for (final String line : Files.readAllLines(netFile())) {
                final int eq = line.indexOf('=');
                if (eq <= 0) {
                    continue;
                }
                final List<String> held = new ArrayList<>();
                for (final String id : line.substring(eq + 1).trim().split(";")) {
                    if (!id.isBlank() && held.size() < config.netCapacity()) {
                        held.add(id.trim());
                    }
                }
                if (!held.isEmpty()) {
                    nets.put(line.substring(0, eq).trim(), held);
                }
            }
        } catch (final java.io.IOException | RuntimeException e) {
            plugin.getSLF4JLogger().warn("{}：讀不到 life_nets.txt：{}", config.name(), e.toString());
        }
    }

    void forget(final Player player) {
        guests.remove(player.getUniqueId());
        invites.remove(player.getUniqueId());
        islands.leave(player);
        whale.leave(player);
        diedAtSea.remove(player.getUniqueId());
        flush(player);
        profiles.remove(player.getUniqueId());
        final Session session = sessions.remove(player.getUniqueId());
        if (session != null) {
            clearSession(session);
        }
    }

    // ---- 等級、精力、材料 ----

    public int level(final Player player) {
        final Profile profile = profile(player);
        return profile == null ? 1 : config.levelOf(profile.xp);
    }

    public long xp(final Player player) {
        final Profile profile = profile(player);
        return profile == null ? 0 : profile.xp;
    }

    public int hookTier(final Player player) {
        final Profile profile = profile(player);
        return profile == null ? 0 : Math.min(config.hooks().size() - 1, profile.hook);
    }

    public int material(final Player player, final String id) {
        final Profile profile = profile(player);
        return profile == null ? 0 : profile.materials.getOrDefault(id, 0);
    }

    /** 今天還剩幾點精力。 */
    public int stamina(final Player player) {
        final Profile profile = profile(player);
        if (profile == null) {
            return 0;
        }
        final int max = config.staminaMax(config.levelOf(profile.xp));
        final String today = config.day(System.currentTimeMillis());
        return today.equals(profile.staminaDay) ? Math.max(0, max - profile.staminaUsed) : max;
    }

    /**
     * 管理員給精力（/sea stamina；2026-10-16）：不受每天回復上限限制，最多回到滿；amount 0 以下 = 回滿。
     * 回傳實際回了幾點；這個人沒有生活資料（不在線上、沒有角色）回傳 -1。
     */
    public int grantStamina(final Player player, final int amount) {
        final Profile profile = profile(player);
        if (profile == null) {
            return -1;
        }
        final String today = config.day(System.currentTimeMillis());
        if (!today.equals(profile.staminaDay)) {
            profile.staminaDay = today;
            profile.staminaUsed = 0;
            profile.staminaRestored = 0;
        }
        final int restored = amount <= 0 ? profile.staminaUsed : Math.min(amount, profile.staminaUsed);
        profile.staminaUsed -= restored;
        profile.dirty = true;
        return restored;
    }

    /** 回精力（喝淡水、吃料理）：一天最多回 daily_restore 點，最多回到滿。回傳實際回了幾點。 */
    int restoreStamina(final Profile profile, final int amount) {
        final String today = config.day(System.currentTimeMillis());
        if (!today.equals(profile.staminaDay)) {
            profile.staminaDay = today;
            profile.staminaUsed = 0;
            profile.staminaRestored = 0;
        }
        final int room = Math.min(profile.staminaUsed, config.dailyRestore() - profile.staminaRestored);
        final int restored = Math.max(0, Math.min(amount, room));
        profile.staminaUsed -= restored;
        profile.staminaRestored += restored;
        profile.dirty = true;
        return restored;
    }

    boolean spendStamina(final Player player, final Profile profile, final int amount) {
        if (amount <= 0) {
            return true;
        }
        final String today = config.day(System.currentTimeMillis());
        if (!today.equals(profile.staminaDay)) {
            profile.staminaDay = today;
            profile.staminaUsed = 0;
            profile.staminaRestored = 0;
        }
        final int max = config.staminaMax(config.levelOf(profile.xp));
        if (profile.staminaUsed + amount > max) {
            bar(player, ("<red>今天的精力用完了<gray>（半夜回滿）"));
            return false;
        }
        profile.staminaUsed += amount;
        profile.dirty = true;
        return true;
    }

    // ---- 管理員發放、收回（2026-10-18，管理網頁）----

    /** 管理網頁的選單：{代號, 名稱}。代號：mat:材料、item:筏的方塊與設施、food:料理、horn（深淵號角）。 */
    public List<String[]> adminChoices() {
        final List<String[]> out = new ArrayList<>();
        for (final LifeConfig.Material material : config.materials().values()) {
            out.add(new String[] {"mat:" + material.id(), "材料・" + material.name()});
        }
        for (final LifeConfig.Item item : config.items().values()) {
            out.add(new String[] {"item:" + item.id(), "方塊・" + item.name()});
        }
        for (final LifeConfig.Food food : config.foods().values()) {
            out.add(new String[] {"food:" + food.id(), "料理・" + food.name()});
        }
        if (config.whale() != null) {
            out.add(new String[] {"horn", "道具・" + config.whale().hornName()});
        }
        if (config.sharkKing() != null) {
            out.add(new String[] {"bait", "道具・" + config.sharkKing().baitName()}); // 2026-10-23
        }
        return out;
    }

    /** 這個代號（見 {@link #adminChoices}）的名稱；不認得回傳 null。 */
    public String adminName(final String ref) {
        if (ref == null) {
            return null;
        }
        if (ref.startsWith("mat:")) {
            final LifeConfig.Material material = config.materials().get(ref.substring(4));
            return material == null ? null : material.name();
        }
        if (ref.startsWith("item:")) {
            final LifeConfig.Item item = config.items().get(ref.substring(5));
            return item == null ? null : item.name();
        }
        if (ref.startsWith("food:")) {
            final LifeConfig.Food food = config.foods().get(ref.substring(5));
            return food == null ? null : food.name();
        }
        if ("bait".equals(ref) && config.sharkKing() != null) {
            return config.sharkKing().baitName();
        }
        return "horn".equals(ref) && config.whale() != null ? config.whale().hornName() : null;
    }

    /**
     * 管理員發（2026-10-18）：材料加進帳號（馬上寫回帳號進度）；方塊、料理、號角做成物品，先放背包、隨身背包，放不下掉在腳下（只有他撿得到）。
     * 回傳發了幾個；帳號還沒載入 -1；不認得的代號 -2。
     */
    public long adminGive(final Player player, final String ref, final long amount) {
        final String name = adminName(ref);
        if (name == null) {
            return -2;
        }
        final Profile profile = profile(player);
        if (profile == null) {
            return -1;
        }
        final int count = (int) Math.max(0, Math.min(Integer.MAX_VALUE, amount));
        if (ref.startsWith("mat:")) {
            addMaterial(profile, ref.substring(4), count); // 2026-10-18：不在這裡 flush，呼叫端用 adminWork 放進領取的交易
            return count;
        }
        int left = count;
        while (left > 0) {
            final ItemStack one = ref.startsWith("item:") ? raftItem(config.items().get(ref.substring(5)), 1)
                    : ref.startsWith("food:") ? foodItem(config.foods().get(ref.substring(5)), 1)
                    : "bait".equals(ref) ? extras.baitItem(1)
                    : whale.hornItem(1);
            final int stack = Math.min(left, Math.max(1, one.getMaxStackSize()));
            one.setAmount(stack);
            giveItem(player, one);
            left -= stack;
        }
        return count;
    }

    /** 管理員收回材料（2026-10-18）：有多少拿多少。回傳拿了幾個；帳號還沒載入 -1；不是材料 -2（方塊、料理、號角是物品，用收回物品）。 */
    public long adminTake(final Player player, final String ref, final long amount) {
        if (ref == null || !ref.startsWith("mat:") || adminName(ref) == null) {
            return -2;
        }
        final Profile profile = profile(player);
        if (profile == null) {
            return -1;
        }
        final String id = ref.substring(4);
        final int have = profile.materials.getOrDefault(id, 0);
        final int taken = (int) Math.max(0, Math.min(have, amount));
        if (taken > 0) {
            if (have - taken <= 0) {
                profile.materials.remove(id);
            } else {
                profile.materials.put(id, have - taken);
            }
            profile.dirty = true; // 2026-10-18：不在這裡 flush，呼叫端用 adminWork 放進領取的交易
        }
        return taken;
    }

    void addMaterial(final Profile profile, final String id, final int amount) {
        if (amount <= 0) {
            return;
        }
        profile.materials.merge(id, amount, (a, b) -> (int) Math.min(Integer.MAX_VALUE, (long) a + b));
        profile.dirty = true;
    }

    void addXp(final Player player, final Profile profile, final int amount) {
        if (amount <= 0) {
            return;
        }
        final int before = config.levelOf(profile.xp);
        profile.xp += amount;
        profile.dirty = true;
        final int after = config.levelOf(profile.xp);
        if (after > before) {
            rememberLevel(profile);
            final int beforeSize = config.raftSize(before);
            final int afterSize = config.raftSize(after);
            player.showTitle(Title.title(plugin.text().parse("<" + config.color() + ">生活等級 " + after),
                    plugin.text().parse(afterSize > beforeSize ? "<white>木筏可以蓋到 " + afterSize + "×" + afterSize : "<gray>" + config.name()),
                    Title.Times.times(Duration.ofMillis(200), Duration.ofMillis(1800), Duration.ofMillis(500))));
            player.playSound(player.getLocation(), Sound.UI_TOAST_CHALLENGE_COMPLETE, 0.7f, 1.3f);
            player.sendMessage(plugin.text().prefixed("<" + config.color() + ">生活等級 " + after + "！<gray>精力上限 "
                    + config.staminaMax(after) + (afterSize > beforeSize ? "，木筏可以蓋到 " + afterSize + "×" + afterSize : "")));
            if (before < LifeModule.WANDERER_LEVEL && after >= LifeModule.WANDERER_LEVEL
                    || before < LifeModule.MASTER_LEVEL && after >= LifeModule.MASTER_LEVEL) { // 2026-10-23：80 級「星海船長」
                // 稱號「星海旅人」：從帳號進度算，馬上寫回去、重算屬性
                flush(player);
                plugin.players().markDirty(player);
            }
        }
    }

    // ---- 物品 ----

    ItemStack hookItem() {
        return hookItem(null);
    }

    /** 2026-10-23 晚：星索鉤的名字、說明照這個人現在的階級（以前一直是「星索鉤」，升級了看不出來）。 */
    ItemStack hookItem(final Player owner) {
        final ItemStack stack = new ItemStack(Material.FISHING_ROD);
        decorateHook(stack, owner);
        return stack;
    }

    private void decorateHook(final ItemStack stack, final Player owner) {
        final LifeConfig.Hook hook = owner == null ? config.hook(0) : config.hook(hookTier(owner));
        stack.editMeta(meta -> {
            meta.displayName(ItemRenderer.line("<" + config.color() + ">星索鉤<gray>・" + ItemRenderer.escape(hook.name())));
            final List<net.kyori.adventure.text.Component> lore = new ArrayList<>();
            lore.add(ItemRenderer.line("<gray>在" + ItemRenderer.escape(config.name()) + "：對準漂流物按右鍵撈起來"));
            lore.add(ItemRenderer.line("<gray>撈得到 <white>" + trim(hook.range()) + "<gray> 格內的東西"
                    + (hook.doubleChance() > 0 ? String.format(Locale.ROOT, "、<white>%.0f%%<gray> 多撈一份", hook.doubleChance() * 100) : "")));
            lore.add(ItemRenderer.line("<gray>鉤子的階級記在帳號裡（選單 /sea 升級）"));
            lore.add(ItemRenderer.line("<dark_gray>生活工具"));
            meta.lore(lore);
            meta.setUnbreakable(true);
            meta.getPersistentDataContainer().set(itemKey, PersistentDataType.STRING, HOOK_ITEM);
        });
    }

    /** 升級之後：背包、游標上的星索鉤改成新的名字與說明。 */
    private void refreshHooks(final Player player) {
        if (HOOK_ITEM.equals(itemId(player.getItemOnCursor()))) {
            decorateHook(player.getItemOnCursor(), player);
        }
        for (final ItemStack stack : player.getInventory().getContents()) {
            if (HOOK_ITEM.equals(itemId(stack))) {
                decorateHook(stack, player);
            }
        }
    }

    ItemStack raftItem(final LifeConfig.Item item, final int amount) {
        final ItemStack stack = new ItemStack(material(item.block(), Material.BAMBOO_PLANKS), Math.max(1, amount));
        stack.editMeta(meta -> {
            meta.displayName(ItemRenderer.line("<" + config.color() + ">" + ItemRenderer.escape(item.name())));
            final List<net.kyori.adventure.text.Component> lore = new ArrayList<>();
            if (!item.description().isBlank()) {
                lore.add(ItemRenderer.line("<gray>" + ItemRenderer.escape(item.description())));
            }
            lore.add(ItemRenderer.line("<dark_gray>只能蓋在" + ItemRenderer.escape(config.name()) + "自己的筏上"));
            meta.lore(lore);
            meta.getPersistentDataContainer().set(itemKey, PersistentDataType.STRING, item.id());
        });
        return stack;
    }

    /** 這個物品是生活的哪一樣（不是 null）。 */
    String itemId(final ItemStack stack) {
        if (stack == null || stack.isEmpty() || !stack.hasItemMeta()) {
            return null;
        }
        return stack.getItemMeta().getPersistentDataContainer().get(itemKey, PersistentDataType.STRING);
    }

    /**
     * 身上有沒有星索鉤。2026-10-17：連游標、隨身背包、倉庫一起看（以前只看背包，
     * 把鉤子收進隨身背包或倉庫之後每次上筏、按選單都再給一把，可以一直拿）。
     */
    private boolean hasHook(final Player player) {
        if (HOOK_ITEM.equals(itemId(player.getItemOnCursor()))) {
            return true;
        }
        for (final ItemStack stack : player.getInventory().getContents()) {
            if (HOOK_ITEM.equals(itemId(stack))) {
                return true;
            }
        }
        final tw.exilecore.character.PlayerState state = plugin.players().get(player);
        if (state != null && state.bag() != null && state.bag().usable()) {
            for (int page = 0; page < state.bag().pages(); page++) {
                for (final ItemStack stack : state.bag().page(page)) {
                    if (HOOK_ITEM.equals(itemId(stack))) {
                        return true;
                    }
                }
            }
        }
        final tw.exilecore.stash.Stash stash = plugin.stash() == null ? null : plugin.stash().stash(player).orElse(null);
        if (stash != null) {
            for (int page = 0; page < stash.pageCount(); page++) {
                for (final ItemStack stack : stash.page(page)) {
                    if (HOOK_ITEM.equals(itemId(stack))) {
                        return true;
                    }
                }
            }
        }
        return false;
    }

    /** 給星索鉤（選單；身上沒有才給）。 */
    String giveHook(final Player player) {
        if (hasHook(player)) {
            return "你已經有星索鉤了（背包、隨身背包或倉庫裡）";
        }
        giveItem(player, hookItem(player));
        return null;
    }

    void giveItem(final Player player, final ItemStack stack) {
        // 2026-10-17：先放背包、隨身背包，還是放不下才掉在腳下而且只有自己撿得到（以前掉成誰都能撿的，淵鯨的傳奇會被別人撿走）
        plugin.economy().wallet().dropFor(player, plugin.economy().wallet().give(player, stack));
    }

    // ---- 製作 ----

    /** 做 times 次；回傳問題，成功 null。 */
    String craft(final Player player, final LifeConfig.Recipe recipe, final int times) {
        final Profile profile = profile(player);
        if (profile == null) {
            return "帳號資料載入中，請稍等";
        }
        if (config.levelOf(profile.xp) < recipe.level()) {
            return "要生活等級 " + recipe.level();
        }
        if (recipe.research() != null && !profile.research.contains(recipe.research())) {
            final LifeConfig.Research entry = config.research().get(recipe.research());
            return "要先在研究台研究「" + (entry == null ? recipe.research() : entry.name()) + "」";
        }
        if (recipe.hookUpgrade()) {
            if (profile.hook >= recipe.hook()) {
                return "鉤子已經是" + config.hook(profile.hook).name() + "了";
            }
            if (profile.hook < recipe.hook() - 1) {
                return "要先升級到" + config.hook(recipe.hook() - 1).name();
            }
        }
        final int count = recipe.hookUpgrade() ? 1 : Math.max(1, times);
        for (final Map.Entry<String, Integer> cost : recipe.cost().entrySet()) {
            final int need = cost.getValue() * count;
            if (profile.materials.getOrDefault(cost.getKey(), 0) < need) {
                return "材料不夠：" + materialName(cost.getKey()) + " 要 " + need + "，你有 " + profile.materials.getOrDefault(cost.getKey(), 0);
            }
        }
        if (recipe.currency() != null) {
            // 2026-10-23：做通貨佔每天海上通貨的額度（跟瓶中信、寶箱共用）：先看額度夠不夠
            final String today = config.day(System.currentTimeMillis());
            final int used = today.equals(profile.currencyDay) ? profile.currencyCount : 0;
            if (used + recipe.amount() * count > config.bottleDailyCurrency()) {
                return "今天從海上拿通貨的額度不夠（每天 " + config.bottleDailyCurrency() + " 顆，已用 " + used + "）";
            }
        }
        if (!spendStamina(player, profile, recipe.stamina() * count)) {
            return "精力不夠（要 " + recipe.stamina() * count + "，剩 " + stamina(player) + "）";
        }
        if (recipe.currency() != null) {
            for (int i = 0; i < recipe.amount() * count; i++) {
                takeCurrencyQuota(profile);
            }
        }
        for (final Map.Entry<String, Integer> cost : recipe.cost().entrySet()) {
            profile.materials.merge(cost.getKey(), -cost.getValue() * count, Integer::sum);
            if (profile.materials.getOrDefault(cost.getKey(), 0) <= 0) {
                profile.materials.remove(cost.getKey());
            }
        }
        profile.dirty = true;
        if (recipe.hookUpgrade()) {
            profile.hook = recipe.hook();
            refreshHooks(player); // 2026-10-23 晚：手上的鉤子跟著換名字
            player.sendMessage(plugin.text().prefixed("<" + config.color() + ">星索鉤升級成" + ItemRenderer.escape(config.hook(profile.hook).name())
                    + "<gray>：撈得到 " + trim(config.hook(profile.hook).range()) + " 格內的東西"));
        } else if (recipe.food() != null) {
            giveItem(player, foodItem(config.foods().get(recipe.food()), recipe.amount() * count));
        } else if (recipe.currency() != null) {
            giveCurrency(player, recipe.currency(), recipe.amount() * count); // 2026-10-23（額度在上面扣過了）
        } else if (recipe.tool() != null) {
            giveItem(player, extras.baitItem(recipe.amount() * count)); // 2026-10-23：現在只有鯊王血餌
        } else {
            final LifeConfig.Item item = config.items().get(recipe.item());
            giveItem(player, raftItem(item, recipe.amount() * count));
        }
        addXp(player, profile, recipe.xp() * count);
        flush(player);
        return null;
    }

    /** 2026-10：生活的提示走 HUD 的提示欄（state.notice），不會被每 tick 重畫的動作列蓋掉。 */
    static final long NOTICE_TICKS = 60;
    /** 撈到、釣到、收網、收成：留久一點。 */
    static final long CATCH_TICKS = 100;

    void bar(final Player player, final String miniMessage) {
        bar(player, miniMessage, NOTICE_TICKS);
    }

    void bar(final Player player, final String miniMessage, final long ticks) {
        final tw.exilecore.character.PlayerState state = plugin.players().get(player);
        if (state == null || !state.hasCharacter()) {
            tw.exilecore.gui.ActionBar.send(player, plugin.text().parse(miniMessage));
            return;
        }
        state.notice(miniMessage, plugin.getServer().getCurrentTick() + ticks);
        plugin.players().display(player, state);
    }

    /** 2026-10：撈到、釣到、收網、收成也進撿東西的角落提示（快捷欄右邊，跟打怪掉落一樣）。 */
    void feedMaterial(final Player player, final String id, final int amount) {
        if (amount <= 0) {
            return;
        }
        plugin.players().lootFeed().add(player.getUniqueId(), materialName(id), amount, null, "#7fd4ff", plugin.getServer().getCurrentTick());
    }

    String materialName(final String id) {
        final LifeConfig.Material material = config.materials().get(id);
        return material == null ? id : material.name();
    }

    static String trim(final double value) {
        return value == Math.rint(value) ? String.valueOf((long) value) : String.format(Locale.ROOT, "%.1f", value);
    }

    // ---- 每 2 tick：漂流物 ----

    void tick() {
        if (world == null || !config.enabled()) {
            return;
        }
        tickCount++;
        islands.advance();
        final Set<UUID> active = new HashSet<>();
        for (final Player player : world.getPlayers()) {
            if (player.isDead()) {
                // 倒下（例如在島上被打倒）：還算在場，島、航行留著，等重生
                if (sessions.containsKey(player.getUniqueId())) {
                    active.add(player.getUniqueId());
                }
                continue;
            }
            if (player.getGameMode() == GameMode.SPECTATOR || !plugin.players().get(player).hasCharacter()) {
                continue;
            }
            final Profile profile = profile(player);
            final int plot = profile == null ? -1 : activePlot(player);
            if (plot < 0) {
                continue;
            }
            final int[] center = plotCenter(plot);
            final Location at = player.getLocation();
            if (at.getX() - center[0] < -48 || at.getX() - center[0] > LifeIslands.OFFSET + LifeIslands.RADIUS + 16
                    || Math.abs(at.getZ() - center[1]) > 48) {
                continue;
            }
            active.add(player.getUniqueId());
            final Session session = sessions.computeIfAbsent(player.getUniqueId(), id -> new Session());
            tickSession(player, profile, session, center, plot);
        }
        for (final Iterator<Map.Entry<UUID, Session>> it = sessions.entrySet().iterator(); it.hasNext(); ) {
            final Map.Entry<UUID, Session> entry = it.next();
            if (!active.contains(entry.getKey())) {
                final Player gone = plugin.getServer().getPlayer(entry.getKey());
                if (gone != null) {
                    islands.leave(gone); // 離開筏：島清掉、航行取消
                }
                if (gone == null || !world.equals(gone.getWorld())) {
                    guests.remove(entry.getKey()); // 離開海域：下次 /sea 照常回自己的（或隊長的）筏
                }
                clearSession(entry.getValue());
                it.remove();
            }
        }
        extras.tickKings(); // 2026-10-23
        if (tickCount % 5 == 0) {
            whale.tick(); // 淵鯨（每 10 tick）
        }
        if (tickCount % 20 == 0) {
            guard();
            expireFood();
        }
        if (tickCount % 600 == 0) {
            flushAll(); // 每 60 秒寫一次
        }
    }

    private void tickSession(final Player player, final Profile profile, final Session session, final int[] center, final int plot) {
        final int size = raftSize(plot);
        final double lane = Math.max(14, size / 2.0 + 10);
        // 生成
        if (--session.nextSpawn <= 0 && session.drifts.size() < config.maxDrifts()) {
            final double[] seconds = config.spawnSeconds();
            // 2026-10-23：海象、筏燈（晚上）、船帆、海域都影響漂流物（海域照筏主選的）
            final double factor = islands.driftFactor(player) * extras.raftDrift(plot, night(), tickCount);
            session.nextSpawn = (int) Math.round((seconds[0] + random.nextDouble() * (seconds[1] - seconds[0])) * 10 / factor); // 每 2 tick 一次 → ×10
            final LifeConfig.Drift type = config.rollDrift(random.nextDouble(),
                    LifeConfig.combine(islands.driftBoost(player), config.zone(zoneOf(plot)).boost())); // 星潮：星骸碎片大量漂來；海域
            final double x = center[0] + lane + 16;
            final double z = center[1] + (random.nextDouble() * 2 - 1) * lane;
            final double y = config.seaLevel() + 0.75;
            final Location spawn = new Location(world, x, y, z);
            final ItemDisplay display = world.spawn(spawn, ItemDisplay.class, created -> {
                created.setItemStack(new ItemStack(material(type.display(), Material.OAK_LOG)));
                final float scale = type.crate() ? 0.75f : 0.7f;
                created.setTransformation(new Transformation(new Vector3f(), new AxisAngle4f((float) (random.nextDouble() * Math.PI * 2), 0, 1, 0),
                        new Vector3f(scale, scale, scale), new AxisAngle4f()));
                created.setTeleportDuration(2);
                created.setPersistent(false);
                created.setBillboard(Display.Billboard.FIXED);
                if (type.bottle() || type.crate() || "star_shard".equals(type.id())) {
                    created.setGlowing(true);
                }
                created.getPersistentDataContainer().set(driftKey, PersistentDataType.STRING, player.getUniqueId().toString());
                created.setVisibleByDefault(false); // 每個人的漂流物只有自己看得到（同一塊筏上好幾個人時不會亂成一片）
            });
            player.showEntity(plugin, display);
            session.drifts.add(new Drift(type, display, x, y, z));
        }
        // 移動
        final LifeConfig.Hook hook = config.hook(profile.hook);
        for (final Iterator<Drift> it = session.drifts.iterator(); it.hasNext(); ) {
            final Drift drift = it.next();
            if (!drift.display.isValid()) {
                it.remove();
                continue;
            }
            drift.age++;
            if (drift.pulling) {
                drift.pullAge++;
                final double t = Math.min(1, drift.pullAge / (double) Math.max(1, hook.pullTicks() / 2.0));
                final Location to = player.getLocation().add(0, 1.0, 0);
                final Location now = drift.pullFrom.clone().add(to.toVector().subtract(drift.pullFrom.toVector()).multiply(t));
                drift.display.teleport(now);
                world.spawnParticle(Particle.SPLASH, now, 3, 0.1, 0.1, 0.1, 0);
                if (t >= 1) {
                    drift.display.remove();
                    it.remove();
                    collect(player, profile, drift.type, false);
                }
                continue;
            }
            drift.x -= config.driftSpeed() * 2;
            final double bob = Math.sin(drift.age * 0.25) * 0.06;
            drift.display.teleport(new Location(world, drift.x, drift.baseY + bob, drift.z));
            // 2026-10-17：只有筏主自己的漂流物會進網（以前訪客、隊友各自的漂流物也進，產量跟筏上人數成正比）
            if (profile.plot == plot && netCatch(player, profile, drift, plot)) {
                drift.display.remove();
                it.remove();
                continue;
            }
            if (drift.x < center[0] - lane - 16) {
                drift.display.remove();
                it.remove();
            }
        }
        tickFishing(player, profile, session);
        if (tickCount % 10 == 0) {
            tickCrops(player, profile, session);
        }
        if (plot == profile.plot) {
            tickShark(player, profile, session, center); // 渦齒鯊只咬自己的筏（去別人的筏不會帶鯊魚過去）
        }
        islands.tick(player, profile, center);
    }

    // ---- 星索鉤 ----

    /** 右鍵拿著星索鉤（LifeModule）：撈起準心對到的漂流物。回傳要不要取消事件（在海上一律取消，不甩原版的釣竿）。 */
    boolean useHook(final Player player) {
        if (!lifeMode(player.getLocation())) {
            return false;
        }
        final Profile profile = profile(player);
        final Session session = sessions.get(player.getUniqueId());
        if (profile == null || session == null) {
            bar(player, ("<gray>回到自己的筏上才撈得到東西"));
            return true;
        }
        final LifeConfig.Hook hook = config.hook(profile.hook);
        final Location eye = player.getEyeLocation();
        final Vector direction = eye.getDirection().normalize();
        Drift best = null;
        double bestOff = Double.MAX_VALUE;
        for (final Drift drift : session.drifts) {
            if (drift.pulling) {
                continue;
            }
            final Vector to = drift.display.getLocation().toVector().subtract(eye.toVector());
            final double distance = to.length();
            if (distance > hook.range() || distance < 0.5) {
                continue;
            }
            final double along = to.dot(direction);
            if (along <= 0) {
                continue;
            }
            final double off = to.clone().subtract(direction.clone().multiply(along)).length();
            if (off <= 1.1 + distance * 0.06 && off < bestOff) {
                bestOff = off;
                best = drift;
            }
        }
        if (session.bobber != null) {
            reelIn(player, profile, session);
            return true;
        }
        if (best == null) {
            if (!castLine(player, session, hook)) {
                bar(player, ("<gray>對準 " + trim(hook.range()) + " 格內的漂流物（或水面：釣魚）再按右鍵"));
            }
            return true;
        }
        if (!spendStamina(player, profile, config.driftStamina())) { // 2026-10-16：撈東西預設不花精力（life.yml 的 drift.stamina）
            return true;
        }
        best.pulling = true;
        best.pullFrom = best.display.getLocation();
        player.playSound(player.getLocation(), Sound.ENTITY_FISHING_BOBBER_RETRIEVE, 0.8f, 1.1f);
        return true;
    }

    /** 撈到（或從攔截網收到）一樣漂流物。 */
    private void collect(final Player player, final Profile profile, final LifeConfig.Drift type, final boolean fromNet) {
        final StringBuilder got = new StringBuilder();
        if (type.bottle()) {
            readLetter(player, profile);
            if (!config.bottleCurrencies().isEmpty() && random.nextDouble() < config.bottleCurrencyChance()) {
                final String today = config.day(System.currentTimeMillis());
                if (!today.equals(profile.currencyDay)) {
                    profile.currencyDay = today;
                    profile.currencyCount = 0;
                }
                if (profile.currencyCount < config.bottleDailyCurrency()) {
                    profile.currencyCount++;
                    profile.dirty = true;
                    final String id = config.bottleCurrencies().get(random.nextInt(config.bottleCurrencies().size()));
                    giveCurrency(player, id, 1);
                    got.append("瓶子裡還有 ").append(currencyName(id));
                }
            }
            if (got.isEmpty()) {
                got.append("一封瓶中信");
            }
            extras.rollTreasureMap(player, profile, false); // 2026-10-23
        } else if (type.crate()) {
            final int rolls = type.rolls()[0] + random.nextInt(Math.max(1, type.rolls()[1] - type.rolls()[0] + 1));
            final Map<String, Integer> sum = new LinkedHashMap<>();
            for (int i = 0; i < rolls; i++) {
                LifeConfig.Drift pick = config.rollDrift(random.nextDouble());
                for (int tries = 0; tries < 10 && pick.gives().isEmpty(); tries++) {
                    pick = config.rollDrift(random.nextDouble());
                }
                for (final Map.Entry<String, int[]> give : pick.gives().entrySet()) {
                    final int amount = give.getValue()[0] + random.nextInt(Math.max(1, give.getValue()[1] - give.getValue()[0] + 1));
                    sum.merge(give.getKey(), amount, Integer::sum);
                }
            }
            for (final Map.Entry<String, Integer> entry : sum.entrySet()) {
                addMaterial(profile, entry.getKey(), entry.getValue());
                if (!fromNet) {
                    feedMaterial(player, entry.getKey(), entry.getValue());
                }
                got.append(got.isEmpty() ? "漂流箱：" : "、").append(materialName(entry.getKey())).append(" +").append(entry.getValue());
            }
            player.playSound(player.getLocation(), Sound.BLOCK_BARREL_OPEN, 0.8f, 1.1f);
            extras.rollTreasureMap(player, profile, true); // 2026-10-23
        } else {
            final boolean doubled = !fromNet && random.nextDouble() < config.hook(profile.hook).doubleChance();
            for (final Map.Entry<String, int[]> give : type.gives().entrySet()) {
                int amount = give.getValue()[0] + random.nextInt(Math.max(1, give.getValue()[1] - give.getValue()[0] + 1));
                if (doubled) {
                    amount *= 2;
                }
                addMaterial(profile, give.getKey(), amount);
                if (!fromNet) {
                    feedMaterial(player, give.getKey(), amount);
                }
                got.append(got.isEmpty() ? "" : "、").append(materialName(give.getKey())).append(" +").append(amount);
            }
            if (doubled) {
                got.append("（多撈一份）");
            }
        }
        addXp(player, profile, fromNet ? Math.max(1, type.xp() / 2) : type.xp());
        if (!fromNet && config.crewXp() > 0) {
            // 2026-10-23：同一塊筏上的其他人分到一部分生活經驗（一起玩）
            final int share = (int) Math.round(type.xp() * config.crewXp());
            if (share > 0) {
                for (final Player other : crewOn(activePlot(player))) {
                    final Profile theirs = other.equals(player) ? null : profiles.get(other.getUniqueId());
                    if (theirs != null) {
                        addXp(other, theirs, share);
                    }
                }
            }
        }
        if (!fromNet) {
            bar(player, "<" + config.color() + ">" + ItemRenderer.escape(got.toString())
                    + " <dark_gray>｜ <gray>精力 " + stamina(player), CATCH_TICKS);
            player.playSound(player.getLocation(), Sound.ENTITY_ITEM_PICKUP, 0.6f, 1.2f);
        }
    }

    // ---- 攔截網 ----

    static String key(final int x, final int y, final int z) {
        return x + "," + y + "," + z;
    }

    private Material netBlock() {
        for (final LifeConfig.Item item : config.items().values()) {
            if (item.place() == LifeConfig.Place.EDGE) {
                return material(item.block(), Material.COBWEB);
            }
        }
        return null;
    }

    private boolean netCatch(final Player player, final Profile profile, final Drift drift, final int plot) {
        final Material net = netBlock();
        if (net == null || islands.storm(player)) {
            return false;
        }
        final double r = config.netRadius();
        final int y = config.seaLevel();
        for (int bx = (int) Math.floor(drift.x - r); bx <= (int) Math.floor(drift.x + r); bx++) {
            for (int bz = (int) Math.floor(drift.z - r); bz <= (int) Math.floor(drift.z + r); bz++) {
                final double dx = bx + 0.5 - drift.x;
                final double dz = bz + 0.5 - drift.z;
                if (dx * dx + dz * dz > r * r) {
                    continue;
                }
                final Block block = world.getBlockAt(bx, y, bz);
                if (block.getType() != net || plotAt(bx, bz) != plot) {
                    continue;
                }
                final List<String> held = nets.computeIfAbsent(key(bx, y, bz), k -> new ArrayList<>());
                if (held.size() >= config.netCapacity()) {
                    continue;
                }
                held.add(drift.type.id());
                world.spawnParticle(Particle.BUBBLE_POP, drift.display.getLocation(), 6, 0.2, 0.1, 0.2, 0.01);
                if (held.size() == config.netCapacity()) {
                    bar(player, ("<yellow>有一張攔截網滿了（右鍵收）"));
                }
                return true;
            }
        }
        return false;
    }

    /** 右鍵攔截網：收網裡的東西。回傳是不是攔截網（是就取消事件）。 */
    boolean collectNet(final Player player, final Block block) {
        if (!lifeMode(block.getLocation()) || block.getType() != netBlock()) {
            return false;
        }
        final Profile profile = profile(player);
        if (profile == null || !canBuild(player, profile, plotAt(block.getX(), block.getZ()))) {
            bar(player, ("<gray>只有筏主、共用成員能收這張網"));
            return true;
        }
        final List<String> held = nets.remove(key(block.getX(), block.getY(), block.getZ()));
        if (held == null || held.isEmpty()) {
            bar(player, ("<gray>攔截網是空的"));
            return true;
        }
        final Map<String, Integer> before = new LinkedHashMap<>(profile.materials);
        for (final String id : held) {
            for (final LifeConfig.Drift type : config.drifts()) {
                if (type.id().equals(id)) {
                    collect(player, profile, type, true);
                }
            }
        }
        final StringBuilder got = new StringBuilder();
        for (final Map.Entry<String, Integer> entry : profile.materials.entrySet()) {
            final int delta = entry.getValue() - before.getOrDefault(entry.getKey(), 0);
            if (delta > 0) {
                got.append(got.isEmpty() ? "" : "、").append(materialName(entry.getKey())).append(" +").append(delta);
                feedMaterial(player, entry.getKey(), delta);
            }
        }
        bar(player, "<" + config.color() + ">收網：" + (got.isEmpty() ? held.size() + " 樣" : ItemRenderer.escape(got.toString())), CATCH_TICKS);
        player.playSound(player.getLocation(), Sound.ITEM_BUNDLE_REMOVE_ONE, 0.8f, 1f);
        return true;
    }

    // ---- 蓋、拆 ----

    boolean builder(final Player player) {
        return player.getGameMode() == GameMode.CREATIVE && player.hasPermission(ExileCore.PERMISSION_ADMIN);
    }

    /** 放方塊（LifeModule）；回傳問題（要取消），可以放 null。 */
    String canPlace(final Player player, final Block block, final ItemStack hand) {
        if (builder(player)) {
            return null;
        }
        final LifeConfig.Item item = config.items().get(itemId(hand));
        if (item == null || HOOK_ITEM.equals(itemId(hand))) {
            return "海上只能蓋筏的方塊（選單 /sea 製作）";
        }
        final Profile profile = profile(player);
        if (profile == null) {
            return "帳號資料載入中，請稍等";
        }
        final int plot = plotAt(block.getX(), block.getZ());
        if (!canBuild(player, profile, plot)) {
            return "只能蓋在自己的筏（或共用的筏）上";
        }
        final int[] center = plotCenter(plot);
        final int level = plotLevel(plot);
        final int size = raftSize(plot);
        final int[] range = extent(size);
        final int dx = block.getX() - center[0];
        final int dz = block.getZ() - center[1];
        if (dx < range[0] || dx > range[1] || dz < range[0] || dz > range[1]) {
            final LifeConfig.SizeStep next = config.nextSize(level);
            return "木筏現在最大 " + size + "×" + size + (next == null ? "" : "（生活等級 " + next.level() + " 可以到 " + next.size() + "×" + next.size() + "）");
        }
        final int y = block.getY();
        final int sea = config.seaLevel();
        if (item.place() == LifeConfig.Place.FLOOR || item.place() == LifeConfig.Place.EDGE) {
            if (y != sea) {
                return ItemRenderer.escape(item.name()) + "要蓋在水面那一層";
            }
            final Material floor = floorBlock();
            final boolean touching = world.getBlockAt(block.getX() + 1, y, block.getZ()).getType() == floor
                    || world.getBlockAt(block.getX() - 1, y, block.getZ()).getType() == floor
                    || world.getBlockAt(block.getX(), y, block.getZ() + 1).getType() == floor
                    || world.getBlockAt(block.getX(), y, block.getZ() - 1).getType() == floor;
            if (!touching) {
                return ItemRenderer.escape(item.name()) + "要接在原本的地板旁邊";
            }
            return null;
        }
        if (y <= sea || y > sea + config.buildHeight()) {
            return "要蓋在地板上面（最多 " + config.buildHeight() + " 層）";
        }
        if (world.getBlockAt(block.getX(), sea, block.getZ()).getType() != floorBlock()) {
            return "下面要有筏地板";
        }
        if ("fish_trap".equals(item.station())) {
            final String full = extras.canPlaceTrap(plot, tickCount, block); // 2026-10-23
            if (full != null) {
                return full;
            }
        }
        return null;
    }

    /** 2026-10-23：方塊放好了（LifeModule，事件沒被取消）：魚籠開始計時、筏上的設施重掃。 */
    void placed(final Block block, final ItemStack hand) {
        final LifeConfig.Item item = config.items().get(itemId(hand));
        if (item == null) {
            return;
        }
        if ("fish_trap".equals(item.station())) {
            extras.trapPlaced(block);
        } else if (item.station() != null || "raft_lamp".equals(item.id())) {
            extras.forgetScan(plotAt(block.getX(), block.getZ()));
        }
    }

    /** 拆方塊（LifeModule）；回傳問題（要取消），可以拆 null（拆下來的東西還給他）。 */
    String breakBlock(final Player player, final Block block) {
        if (builder(player)) {
            // 2026-10-17：管理員拆的也清掉攔截網的內容（以前同一格再放網會繼承舊的）
            nets.remove(key(block.getX(), block.getY(), block.getZ()));
            clearPot(block); // 2026-10-18：管理員拆的盆也記成拆掉了
            return null;
        }
        // 2026-10：貼在牆上的船帆（WHITE_WALL_BANNER）、疊成一整格的半磚也拆得下來（以前拆不了、或少一塊）
        final String typeName = block.getType().name();
        LifeConfig.Item item = config.itemByBlock(typeName);
        if (item == null && typeName.contains("_WALL_")) {
            item = config.itemByBlock(typeName.replace("_WALL_", "_"));
        }
        if (item == null) {
            return "這個拆不了";
        }
        final int count = block.getBlockData() instanceof org.bukkit.block.data.type.Slab slab
                && slab.getType() == org.bukkit.block.data.type.Slab.Type.DOUBLE ? 2 : 1;
        final Profile profile = profile(player);
        final int plot = plotAt(block.getX(), block.getZ());
        if (!canBuild(player, profile, plot)) {
            return "只能拆自己的筏（或共用的筏）";
        }
        final int[] center = plotCenter(plot);
        if (block.getX() == center[0] && block.getZ() == center[1] && block.getY() == config.seaLevel()) {
            return "筏心不能拆";
        }
        // 2026-10：上面有任何東西（旗子、船帆這種穿得過的也算）都要先拆：以前地板拆了，上面的東西彈掉、不見
        if (item.place() == LifeConfig.Place.FLOOR && !block.getRelative(0, 1, 0).getType().isAir()) {
            return "上面還有東西，先拆上面的";
        }
        giveItem(player, raftItem(item, count));
        clearPot(block); // 2026-10-18：下線的人種的也不算了（全域紀錄）
        if ("fish_trap".equals(item.station())) {
            extras.trapRemoved(block); // 2026-10-23（裡面的魚不給）
        } else {
            extras.forgetScan(plot);
        }
        // 盆裡的作物（誰種的都清：共用成員種的也是）
        final String cropKey = key(block.getX(), block.getY(), block.getZ());
        boolean cropGone = false;
        for (final Profile each : profiles.values()) {
            if (each.crops.remove(cropKey) != null) {
                each.dirty = true;
                cropGone = true;
            }
        }
        if (cropGone) {
            player.sendMessage(plugin.text().prefixed("<gray>盆裡的作物也一起丟掉了"));
        }
        if (item.place() == LifeConfig.Place.EDGE) {
            final List<String> held = nets.remove(key(block.getX(), block.getY(), block.getZ()));
            if (held != null) {
                for (final String id : held) {
                    for (final LifeConfig.Drift type : config.drifts()) {
                        if (type.id().equals(id)) {
                            collect(player, profile, type, true);
                        }
                    }
                }
            }
        }
        if (block.getY() == config.seaLevel()) {
            final Location at = block.getLocation();
            plugin.getServer().getScheduler().runTask(plugin, () -> at.getBlock().setType(Material.WATER, false)); // 拆掉的地方補回海水
        }
        return null;
    }

    // ---- 釣魚、作物、設施、研究、料理、渦齒鯊 ----

    private void clearSession(final Session session) {
        for (final Drift drift : session.drifts) {
            drift.display.remove();
        }
        session.drifts.clear();
        for (final ItemDisplay view : session.cropViews.values()) {
            view.remove();
        }
        session.cropViews.clear();
        if (session.shark != null) {
            removeShark(session);
        }
        session.bobber = null;
    }

    // -- 釣魚 --

    private boolean castLine(final Player player, final Session session, final LifeConfig.Hook hook) {
        if (config.fishing().fish().isEmpty()) {
            return false;
        }
        final org.bukkit.util.RayTraceResult hit = world.rayTraceBlocks(player.getEyeLocation(), player.getEyeLocation().getDirection(),
                hook.range(), org.bukkit.FluidCollisionMode.ALWAYS, true);
        if (hit == null || hit.getHitBlock() == null || hit.getHitBlock().getType() != Material.WATER) {
            return false;
        }
        session.bobber = hit.getHitPosition().toLocation(world).add(0, 0.15, 0);
        final double[] wait = config.fishing().waitSeconds();
        session.fishTick = 0;
        session.biteAt = (int) Math.round((wait[0] + random.nextDouble() * Math.max(0, wait[1] - wait[0])) * 10);
        session.biteUntil = session.biteAt + Math.max(2, config.fishing().biteTicks() / 2);
        player.playSound(session.bobber, Sound.ENTITY_FISHING_BOBBER_THROW, 0.7f, 1f);
        bar(player, ("<gray>拋線了……等魚咬鉤（咬鉤時再按右鍵）"));
        return true;
    }

    private void tickFishing(final Player player, final Profile profile, final Session session) {
        if (session.bobber == null) {
            return;
        }
        session.fishTick++;
        if (player.getLocation().distanceSquared(session.bobber) > 20 * 20 || !HOOK_ITEM.equals(itemId(player.getInventory().getItemInMainHand()))) {
            session.bobber = null;
            return;
        }
        if (session.fishTick < session.biteAt) {
            if (session.fishTick % 5 == 0) {
                player.spawnParticle(Particle.FISHING, session.bobber, 2, 0.05, 0, 0.05, 0);
            }
            return;
        }
        if (session.fishTick == session.biteAt) {
            player.playSound(session.bobber, Sound.ENTITY_FISHING_BOBBER_SPLASH, 1f, 1f);
            player.spawnParticle(Particle.SPLASH, session.bobber, 20, 0.2, 0.1, 0.2, 0);
            bar(player, ("<#7fd4ff>🐟 咬鉤了！<white>快按右鍵"));
            return;
        }
        if (session.fishTick > session.biteUntil) {
            session.bobber = null;
            bar(player, ("<gray>魚跑掉了……"));
        }
    }

    private void reelIn(final Player player, final Profile profile, final Session session) {
        final boolean biting = session.fishTick >= session.biteAt && session.fishTick <= session.biteUntil;
        session.bobber = null;
        if (!biting) {
            bar(player, ("<gray>收線了（太早了）"));
            return;
        }
        final boolean night = night();
        // 2026-10-23：海象、海域、筏燈（夜光潮等於多兩盞燈）
        final int plot = activePlot(player);
        final String weatherId = islands.weatherId(player);
        final int lamps = extras.scan(plot, tickCount).lamps() + ("glow_tide".equals(weatherId) ? 2 : 0);
        final String fish = config.rollFish(config.levelOf(profile.xp), night, weatherId, zoneOf(plot), lamps, random.nextDouble());
        if (fish == null) {
            bar(player, "<gray>什麼都沒釣到（這個等級還沒有魚可以釣）"); // 2026-10-18：先抽再扣精力，沒魚不扣
            return;
        }
        if (!spendStamina(player, profile, config.fishing().stamina())) {
            return;
        }
        addMaterial(profile, fish, 1);
        feedMaterial(player, fish, 1);
        addXp(player, profile, config.fishing().xp());
        player.playSound(player.getLocation(), Sound.ENTITY_FISHING_BOBBER_RETRIEVE, 0.8f, 1.3f);
        final LifeConfig.Fish def = config.fish(fish);
        final double size = def == null ? 0 : LifeConfig.fishSize(def, random.nextDouble());
        bar(player, "<#7fd4ff>釣到了 " + ItemRenderer.escape(materialName(fish)) + (size > 0 ? String.format(Locale.ROOT, "（%.1f 公分）", size) : "")
                + "！<dark_gray> ｜ <gray>精力 " + stamina(player), CATCH_TICKS);
        extras.recordDex(player, profile, fish, (int) Math.round(size * 10), 1, true); // 2026-10-23：圖鑑、釣魚大賽
    }

    // -- 設施 --

    /** 右鍵筏上的設施（LifeModule）；回傳是不是設施（是就取消事件）。 */
    boolean useStation(final Player player, final Block block) {
        if (!lifeMode(block.getLocation()) && !sharkPlots.contains(plotAt(block.getX(), block.getZ()))) {
            return false;
        }
        final LifeConfig.Item item = config.itemByBlock(block.getType().name());
        if (item == null || item.station() == null || "lamp".equals(item.station()) || "sail".equals(item.station())) {
            return false; // 2026-10-23：筏燈、船帆只是有加成的方塊，右鍵不做事
        }
        final Profile profile = profile(player);
        final int plot = plotAt(block.getX(), block.getZ());
        // 自己待的這塊筏上的設施都能用（拜訪也可以淨水、做菜、研究）；種田、掌舵要筏主或共用成員
        if (profile == null || plot != activePlot(player)) {
            bar(player, ("<gray>這不是你待的筏"));
            return true;
        }
        if (("helm".equals(item.station()) || item.station().startsWith("planter")) && !canBuild(player, profile, plot)) {
            bar(player, ("<gray>只有筏主、共用成員能" + ("helm".equals(item.station()) ? "掌舵" : "種田")));
            return true;
        }
        switch (item.station()) {
            case "research" -> new LifeResearchMenu(plugin, player, this).open();
            case "purifier" -> purify(player, profile, block);
            case "grill", "kitchen" -> new LifeCookMenu(plugin, player, this, item.station()).open();
            case "planter_sea", "planter_soil" -> planter(player, profile, block, item.station().equals("planter_sea") ? "sea" : "soil");
            case "helm" -> new LifeVoyageMenu(plugin, player, this).open();
            case "bed" -> extras.useBed(player, profile);          // 2026-10-23
            case "fish_trap" -> extras.useTrap(player, profile, block);
            default -> bar(player, ("<gray>" + ItemRenderer.escape(item.description())));
        }
        return true;
    }

    private void purify(final Player player, final Profile profile, final Block block) {
        final String at = key(block.getX(), block.getY(), block.getZ());
        final long now = System.currentTimeMillis();
        final long ready = purifierReady.getOrDefault(at, 0L);
        if (now < ready) {
            bar(player, ("<gray>還在煮（" + ((ready - now + 999) / 1000) + " 秒）"));
            return;
        }
        final LifeConfig.Purifier def = config.purifier();
        if (!spendStamina(player, profile, def.stamina())) {
            return;
        }
        purifierReady.put(at, now + def.cooldownSeconds() * 1000L);
        addMaterial(profile, config.freshWater(), def.water());
        addXp(player, profile, def.xp());
        world.spawnParticle(Particle.CLOUD, block.getLocation().add(0.5, 1, 0.5), 8, 0.2, 0.2, 0.2, 0.01);
        player.playSound(block.getLocation(), Sound.BLOCK_BREWING_STAND_BREW, 0.8f, 1.2f);
        bar(player, ("<#7fd4ff>" + ItemRenderer.escape(materialName(config.freshWater())) + " +" + def.water()
                + " <dark_gray>｜ <gray>" + def.cooldownSeconds() + " 秒後再煮一次"));
    }

    // -- 作物 --

    private void planter(final Player player, final Profile profile, final Block block, final String type) {
        final String at = key(block.getX(), block.getY(), block.getZ());
        final String planted = profile.crops.get(at);
        if (planted == null) {
            new LifePlantMenu(plugin, player, this, block, type).open();
            return;
        }
        final int sep = planted.indexOf('@');
        final LifeConfig.Crop crop = config.crops().get(sep < 0 ? planted : planted.substring(0, sep));
        final long since = sep < 0 ? 0 : parseLong(planted.substring(sep + 1));
        if (crop == null) {
            profile.crops.remove(at);
            releasePot(player, at); // 2026-10-18
            profile.dirty = true;
            return;
        }
        final long left = since + crop.minutes() * 60_000L - System.currentTimeMillis();
        if (left > 0) {
            bar(player, ("<gray>" + ItemRenderer.escape(crop.name()) + "還要 " + ((left + 59_999) / 60_000) + " 分鐘"));
            return;
        }
        profile.crops.remove(at);
        releasePot(player, at); // 2026-10-18
        final StringBuilder got = new StringBuilder();
        for (final Map.Entry<String, int[]> give : crop.harvest().entrySet()) {
            final int amount = give.getValue()[0] + random.nextInt(Math.max(1, give.getValue()[1] - give.getValue()[0] + 1));
            addMaterial(profile, give.getKey(), amount);
            feedMaterial(player, give.getKey(), amount);
            got.append(got.isEmpty() ? "" : "、").append(materialName(give.getKey())).append(" +").append(amount);
        }
        final int seeds = crop.seedsBack()[0] + random.nextInt(Math.max(1, crop.seedsBack()[1] - crop.seedsBack()[0] + 1));
        addMaterial(profile, crop.seed(), seeds);
        feedMaterial(player, crop.seed(), seeds);
        if (seeds > 0) {
            got.append("、").append(materialName(crop.seed())).append(" +").append(seeds);
        }
        addXp(player, profile, crop.xp());
        profile.dirty = true;
        final Session session = sessions.get(player.getUniqueId());
        if (session != null) {
            final ItemDisplay view = session.cropViews.remove(at);
            if (view != null) {
                view.remove();
            }
        }
        player.playSound(block.getLocation(), Sound.BLOCK_CROP_BREAK, 0.8f, 1.1f);
        bar(player, "<#9be37f>收成：" + ItemRenderer.escape(got.toString()), CATCH_TICKS);
    }

    /** 種下去（種田選單）；回傳問題，成功 null。 */
    String plant(final Player player, final Block block, final LifeConfig.Crop crop) {
        final Profile profile = profile(player);
        if (profile == null || !canBuild(player, profile, plotAt(block.getX(), block.getZ()))) {
            return "只有筏主、共用成員能種";
        }
        final LifeConfig.Item planter = config.itemByBlock(block.getType().name());
        if (planter == null || planter.station() == null || !planter.station().startsWith("planter")) {
            return "這裡已經沒有海水盆、菜圃了";
        }
        if (!planter.station().equals("planter_" + crop.planter())) {
            return crop.name() + "要種在" + ("sea".equals(crop.planter()) ? "海水盆" : "菜圃");
        }
        final String at = key(block.getX(), block.getY(), block.getZ());
        if (profile.crops.containsKey(at)) {
            return "已經種了東西";
        }
        // 2026-10：同一個盆別人（共用成員）種了也不能再種（以前每個人各種一份，一盆收好幾份）
        for (final Profile each : profiles.values()) {
            if (each != profile && each.crops.containsKey(at)) {
                return "已經有人種了東西";
            }
        }
        // 2026-10-18：種的人下線了也算（全域紀錄）；紀錄是在線的人、但他其實沒種（當機前沒寫到）就不算
        if (potsLoading) {
            return "盆的資料還在載入，請稍等"; // 2026-10-18：第一次上線、從帳號資料建紀錄中
        }
        final String record = pots.get(at);
        final String owner = record == null ? null : potOwner(record); // 2026-10-18：紀錄是「UUID@時間」
        if (owner != null && !owner.equals(POT_CLEARED) && !owner.equals(player.getUniqueId().toString())) {
            UUID ownerId = null;
            try {
                ownerId = UUID.fromString(owner);
            } catch (final IllegalArgumentException ignored) {
                // 檔案壞掉的值：當作沒人種
            }
            final Player other = ownerId == null ? null : plugin.getServer().getPlayer(ownerId);
            final Profile theirs = other == null ? null : profiles.get(other.getUniqueId());
            if (ownerId != null && (theirs == null || theirs.crops.containsKey(at))) {
                return "已經有人種了東西";
            }
        }
        if (config.levelOf(profile.xp) < crop.level()) {
            return "要生活等級 " + crop.level();
        }
        if (profile.materials.getOrDefault(crop.seed(), 0) < 1) {
            return "沒有" + materialName(crop.seed());
        }
        if (crop.water() > 0 && profile.materials.getOrDefault(config.freshWater(), 0) < crop.water()) {
            return "要 " + crop.water() + " 份" + materialName(config.freshWater()) + "（淨水器煮）";
        }
        if (crop.sprinkler() && !nearStation(block, "sprinkler", 4)) {
            return "旁邊 4 格內要有灑水器";
        }
        if (!spendStamina(player, profile, 2)) {
            return "精力不夠";
        }
        profile.materials.merge(crop.seed(), -1, Integer::sum);
        if (crop.water() > 0) {
            profile.materials.merge(config.freshWater(), -crop.water(), Integer::sum);
        }
        profile.materials.values().removeIf(count -> count <= 0);
        final long now = System.currentTimeMillis();
        profile.crops.put(at, crop.id() + "@" + now);
        pots.put(at, potRecord(player.getUniqueId().toString(), now)); // 2026-10-18：連種下的時間一起記（同一盆早種的算）
        profile.dirty = true;
        addXp(player, profile, 1);
        player.playSound(block.getLocation(), Sound.ITEM_CROP_PLANT, 0.8f, 1f);
        return null;
    }

    private boolean nearStation(final Block block, final String station, final int radius) {
        final LifeConfig.Item item = config.station(station);
        if (item == null) {
            return false;
        }
        final Material type = material(item.block(), Material.LIGHTNING_ROD);
        for (int dx = -radius; dx <= radius; dx++) {
            for (int dy = -2; dy <= 2; dy++) {
                for (int dz = -radius; dz <= radius; dz++) {
                    if (block.getRelative(dx, dy, dz).getType() == type) {
                        return true;
                    }
                }
            }
        }
        return false;
    }

    /** 作物的樣子：長大前小小的、熟了換成果實並發光。 */
    private void tickCrops(final Player player, final Profile profile, final Session session) {
        final long now = System.currentTimeMillis();
        final int here = plotAt(player.getLocation().getX(), player.getLocation().getZ());
        for (final java.util.Iterator<Map.Entry<String, String>> it = profile.crops.entrySet().iterator(); it.hasNext(); ) {
            final Map.Entry<String, String> entry = it.next();
            final String[] pos = entry.getKey().split(",");
            // 2026-10：只看現在這塊筏上的（別塊筏的不去載入遠方的區塊）
            if (pos.length == 3 && plotAt(Integer.parseInt(pos[0]), Integer.parseInt(pos[2])) != here) {
                continue;
            }
            final int sep = entry.getValue().indexOf('@');
            final LifeConfig.Crop crop = config.crops().get(sep < 0 ? entry.getValue() : entry.getValue().substring(0, sep));
            if (crop == null || pos.length != 3) {
                continue;
            }
            final long since = sep < 0 ? 0 : parseLong(entry.getValue().substring(sep + 1));
            final double grown = Math.max(0, Math.min(1, (now - since) / (crop.minutes() * 60_000.0)));
            final boolean ripe = grown >= 1;
            final Block block = world.getBlockAt(Integer.parseInt(pos[0]), Integer.parseInt(pos[1]), Integer.parseInt(pos[2]));
            // 2026-10：盆已經被拆掉（別人拆的）：作物跟著清掉，不會飄在水上
            final LifeConfig.Item pot = config.itemByBlock(block.getType().name());
            if (pot == null || pot.station() == null || !pot.station().startsWith("planter")) {
                it.remove();
                releasePot(player, entry.getKey()); // 2026-10-18
                profile.dirty = true;
                continue;
            }
            final double height = block.getType() == Material.CAULDRON ? 0.85 : 1.25;
            final float scale = (float) (0.35 + 0.45 * grown);
            ItemDisplay view = session.cropViews.get(entry.getKey());
            if (view == null || !view.isValid()) {
                view = world.spawn(block.getLocation().add(0.5, height, 0.5), ItemDisplay.class, created -> {
                    created.setPersistent(false);
                    created.setBillboard(Display.Billboard.VERTICAL);
                });
                session.cropViews.put(entry.getKey(), view);
            }
            view.setItemStack(new ItemStack(material(ripe ? crop.ripe() : crop.sprout(), Material.WHEAT_SEEDS)));
            view.setTransformation(new Transformation(new Vector3f(), new AxisAngle4f(), new Vector3f(scale, scale, scale), new AxisAngle4f()));
            view.setGlowing(ripe);
            if (ripe && tickCount % 40 == 0) {
                world.spawnParticle(Particle.HAPPY_VILLAGER, block.getLocation().add(0.5, height + 0.3, 0.5), 3, 0.2, 0.2, 0.2, 0);
            }
        }
        for (final Iterator<Map.Entry<String, ItemDisplay>> it = session.cropViews.entrySet().iterator(); it.hasNext(); ) {
            final Map.Entry<String, ItemDisplay> entry = it.next();
            if (!profile.crops.containsKey(entry.getKey())) {
                entry.getValue().remove();
                it.remove();
            }
        }
    }

    // -- 研究 --

    boolean researched(final Player player, final String id) {
        final Profile profile = profile(player);
        return profile != null && profile.research.contains(id);
    }

    /** 研究（研究台選單）；回傳問題，成功 null。 */
    String research(final Player player, final LifeConfig.Research entry) {
        final Profile profile = profile(player);
        if (profile == null) {
            return "帳號資料載入中，請稍等";
        }
        if (profile.research.contains(entry.id())) {
            return "已經研究過了";
        }
        if (config.levelOf(profile.xp) < entry.level()) {
            return "要生活等級 " + entry.level();
        }
        for (final Map.Entry<String, Integer> need : entry.needs().entrySet()) {
            if (profile.materials.getOrDefault(need.getKey(), 0) < need.getValue()) {
                return "材料不夠：" + materialName(need.getKey()) + " 要 " + need.getValue();
            }
        }
        for (final Map.Entry<String, Integer> need : entry.needs().entrySet()) {
            profile.materials.merge(need.getKey(), -need.getValue(), Integer::sum);
        }
        profile.materials.values().removeIf(count -> count <= 0);
        profile.research.add(entry.id());
        profile.dirty = true;
        addXp(player, profile, 20);
        flush(player);
        final List<String> unlocked = new ArrayList<>();
        for (final LifeConfig.Recipe recipe : config.recipes()) {
            if (entry.id().equals(recipe.research())) {
                unlocked.add(recipeName(recipe));
            }
        }
        player.sendMessage(plugin.text().prefixed("<" + config.color() + ">研究完成：" + ItemRenderer.escape(entry.name())
                + (unlocked.isEmpty() ? "" : "<gray>　解鎖：" + ItemRenderer.escape(String.join("、", unlocked)))));
        player.playSound(player.getLocation(), Sound.BLOCK_ENCHANTMENT_TABLE_USE, 0.8f, 1.2f);
        return null;
    }

    String recipeName(final LifeConfig.Recipe recipe) {
        if (recipe.food() != null) {
            final LifeConfig.Food food = config.foods().get(recipe.food());
            return food == null ? recipe.food() : food.name();
        }
        if (recipe.currency() != null) {
            return currencyName(recipe.currency()); // 2026-10-23
        }
        if (recipe.tool() != null) {
            return config.sharkKing() == null ? recipe.tool() : config.sharkKing().baitName();
        }
        if (recipe.hookUpgrade()) {
            return config.hook(recipe.hook()).name();
        }
        final LifeConfig.Item item = config.items().get(recipe.item());
        return item == null ? recipe.id() : item.name();
    }

    // -- 料理 --

    ItemStack foodItem(final LifeConfig.Food food, final int amount) {
        final ItemStack stack = new ItemStack(material(food.icon(), Material.COOKED_COD), Math.max(1, amount));
        stack.editMeta(meta -> {
            meta.displayName(ItemRenderer.line("<#ffb46b>" + ItemRenderer.escape(food.name())));
            final List<net.kyori.adventure.text.Component> lore = new ArrayList<>();
            lore.add(ItemRenderer.line("<gray>右鍵吃：" + food.minutes() + " 分鐘"));
            for (final String line : tw.exilecore.ascendancy.StatLines.describeAll(food.stats())) {
                lore.add(ItemRenderer.line("<#8fd3ff>・" + ItemRenderer.escape(line)));
            }
            if (food.stamina() > 0) {
                lore.add(ItemRenderer.line("<gray>・在海上吃：回 " + food.stamina() + " 點精力"));
                // 2026-10：寫出限制（以前玩家以為沒作用）
                lore.add(ItemRenderer.line("<dark_gray>　只回用掉的精力；跟喝淡水合計一天最多回 " + config.dailyRestore() + " 點"));
            }
            lore.add(ItemRenderer.line("<dark_gray>同時只有一種料理的效果（吃新的蓋掉舊的）"));
            if (!food.description().isBlank()) {
                lore.add(ItemRenderer.line("<dark_gray>" + ItemRenderer.escape(food.description())));
            }
            meta.lore(lore);
            meta.getPersistentDataContainer().set(itemKey, PersistentDataType.STRING, "food:" + food.id());
        });
        return stack;
    }

    /** 這個物品是哪一種料理（不是 null）。 */
    LifeConfig.Food foodOf(final ItemStack stack) {
        final String id = itemId(stack);
        return id != null && id.startsWith("food:") ? config.foods().get(id.substring(5)) : null;
    }

    /** 做料理（烤爐、料理台選單）；回傳問題，成功 null。 */
    String cook(final Player player, final LifeConfig.Recipe recipe, final int times) {
        final Profile profile = profile(player);
        if (profile == null) {
            return "帳號資料載入中，請稍等";
        }
        if (recipe.research() != null && !profile.research.contains(recipe.research())) {
            return "要先在研究台研究「" + config.research().get(recipe.research()).name() + "」";
        }
        return craft(player, recipe, times);
    }

    /** 吃料理（右鍵，哪裡都可以）；回傳有沒有吃（有就取消原版的動作）。 */
    boolean eat(final Player player, final ItemStack stack) {
        final LifeConfig.Food food = foodOf(stack);
        if (food == null) {
            return false;
        }
        final Profile profile = profile(player);
        if (profile == null || !plugin.players().get(player).hasCharacter()) {
            return true;
        }
        stack.setAmount(stack.getAmount() - 1);
        profile.food = food.id();
        profile.foodUntil = System.currentTimeMillis() + food.minutes() * 60_000L;
        profile.dirty = true;
        int restored = 0;
        String noStamina = "";
        if (food.stamina() > 0) {
            if (!lifeModeOrShark(player.getLocation())) {
                noStamina = "<dark_gray>（不在海上，精力沒回）";
            } else {
                restored = restoreStamina(profile, food.stamina());
                if (restored <= 0) {
                    noStamina = "<dark_gray>（精力是滿的，或今天用料理、淡水回的已經到 " + config.dailyRestore() + " 點上限）";
                }
            }
        }
        plugin.players().markDirty(player);
        player.playSound(player.getLocation(), Sound.ENTITY_GENERIC_EAT, 0.8f, 1f);
        player.sendMessage(plugin.text().prefixed("<#ffb46b>吃了" + ItemRenderer.escape(food.name()) + "<gray>：" + food.minutes() + " 分鐘的加成"
                + (restored > 0 ? "，精力 +" + restored : "") + noStamina));
        flush(player);
        return true;
    }

    /** 在海域世界（不管有沒有鯊魚）。 */
    boolean lifeModeOrSea(final Location location) {
        return lifeModeOrShark(location);
    }

    private boolean lifeModeOrShark(final Location location) {
        return world != null && world.equals(location.getWorld());
    }

    /** 帳號、角色載入好了：讀生活資料；有料理的效果就重算屬性表。 */
    void onReady(final Player player) {
        final Profile profile = profile(player);
        if (profile != null && profile.food != null && profile.foodUntil > System.currentTimeMillis()) {
            plugin.players().markDirty(player);
        }
    }

    /** 料理的加成（屬性表的增益來源）。 */
    List<tw.exilecore.stats.Modifier> foodModifiers(final Player player) {
        final Profile profile = profiles.get(player.getUniqueId());
        if (profile == null || profile.food == null || profile.foodUntil <= System.currentTimeMillis()) {
            return List.of();
        }
        final LifeConfig.Food food = config.foods().get(profile.food);
        if (food == null) {
            return List.of();
        }
        final List<tw.exilecore.stats.Modifier> list = new ArrayList<>();
        for (final tw.exilecore.stats.Modifier mod : food.stats()) {
            list.add(new tw.exilecore.stats.Modifier(mod.stat(), mod.type(), mod.value(), mod.tags(), "料理「" + food.name() + "」"));
        }
        return list;
    }

    /** 正在吃的料理與剩幾分鐘（選單）；沒有 null。 */
    String foodStatus(final Player player) {
        final Profile profile = profile(player);
        if (profile == null || profile.food == null || profile.foodUntil <= System.currentTimeMillis()) {
            return null;
        }
        final LifeConfig.Food food = config.foods().get(profile.food);
        return food == null ? null : food.name() + "（剩 " + ((profile.foodUntil - System.currentTimeMillis() + 59_999) / 60_000) + " 分鐘）";
    }

    private void expireFood() {
        final long now = System.currentTimeMillis();
        for (final Map.Entry<UUID, Profile> entry : profiles.entrySet()) {
            final Profile profile = entry.getValue();
            if (profile.food != null && profile.foodUntil <= now) {
                final LifeConfig.Food food = config.foods().get(profile.food);
                profile.food = null;
                profile.foodUntil = 0;
                profile.dirty = true;
                final Player player = plugin.getServer().getPlayer(entry.getKey());
                if (player != null) {
                    plugin.players().markDirty(player);
                    player.sendMessage(plugin.text().prefixed("<gray>料理的效果結束了" + (food == null ? "" : "：" + ItemRenderer.escape(food.name()))));
                }
            }
        }
    }

    /** 喝一份淡水回精力（選單）；回傳問題，成功 null。 */
    String drink(final Player player) {
        final Profile profile = profile(player);
        if (profile == null) {
            return "帳號資料載入中，請稍等";
        }
        if (profile.materials.getOrDefault(config.freshWater(), 0) < 1) {
            return "沒有" + materialName(config.freshWater()) + "（淨水器煮）";
        }
        final int restored = restoreStamina(profile, config.drinkStamina());
        if (restored <= 0) {
            return "精力是滿的，或今天已經回到上限（" + config.dailyRestore() + " 點）";
        }
        profile.materials.merge(config.freshWater(), -1, Integer::sum);
        profile.materials.values().removeIf(count -> count <= 0);
        player.playSound(player.getLocation(), Sound.ENTITY_GENERIC_DRINK, 0.8f, 1f);
        bar(player, ("<#7fd4ff>精力 +" + restored + " <dark_gray>｜ <gray>精力 " + stamina(player)));
        return null;
    }

    // -- 渦齒鯊 --

    private void tickShark(final Player player, final Profile profile, final Session session, final int[] center) {
        final LifeConfig.Shark def = config.shark();
        if (def == null || config.levelOf(profile.xp) < def.minLevel()) {
            return;
        }
        // 2026-10-18：航行中或島還開著時渦齒鯊不會來（已經在咬的游走，倒數也暫停）
        if (islands.busy(profile.plot)) {
            if (session.shark != null) {
                player.sendMessage(plugin.text().prefixed("<gray>渦齒鯊游走了"));
                removeShark(session);
            }
            return;
        }
        if (session.shark == null) {
            if (session.nextShark < 0) {
                session.nextShark = randomInterval(def);
            }
            if (--session.nextShark <= 0) {
                spawnShark(player, profile, session, center, def);
                session.nextShark = randomInterval(def);
            }
            return;
        }
        final org.bukkit.entity.LivingEntity shark = session.shark;
        if (!shark.isValid() || shark.isDead()) {
            removeShark(session);
            return;
        }
        session.sharkAge++;
        shark.setRemainingAir(shark.getMaximumAir());
        if (session.sharkAge > def.fleeSeconds() * 10) {
            player.sendMessage(plugin.text().prefixed("<gray>渦齒鯊游走了"));
            removeShark(session);
            return;
        }
        if (session.sharkTarget == null || biteFloor(session.sharkTarget) == null) {
            session.sharkTarget = biteTarget(center, profile);
            session.sharkBite = 0;
            if (session.sharkTarget == null) {
                removeShark(session);
                return;
            }
        }
        final Location at = shark.getLocation();
        final Vector to = session.sharkTarget.toVector().add(new Vector(0.5, -0.6, 0.5)).subtract(at.toVector());
        final double distance = to.length();
        if (distance > 1.3) {
            final Vector step = to.normalize().multiply(Math.min(distance, 0.35));
            final Location next = at.clone().add(step);
            next.setDirection(step);
            shark.teleport(next);
            return;
        }
        if (++session.sharkBite >= def.biteSeconds() * 10) {
            session.sharkBite = 0;
            final Block floor = biteFloor(session.sharkTarget);
            if (floor != null) {
                floor.setType(Material.WATER, false);
                world.playSound(floor.getLocation(), Sound.ENTITY_ZOMBIE_BREAK_WOODEN_DOOR, 1f, 0.8f);
                world.spawnParticle(Particle.BLOCK, floor.getLocation().add(0.5, 0.5, 0.5), 30, 0.4, 0.3, 0.4, floorBlock().createBlockData());
                bar(player, ("<red>渦齒鯊咬掉了一塊筏！"));
            }
            session.sharkTarget = null;
        } else if (session.sharkBite % 5 == 0) {
            world.spawnParticle(Particle.BUBBLE_POP, at, 10, 0.4, 0.3, 0.4, 0.02);
            world.playSound(at, Sound.ENTITY_DOLPHIN_ATTACK, 0.7f, 0.6f);
        }
    }

    private int randomInterval(final LifeConfig.Shark def) {
        final int[] range = def.intervalSeconds();
        return (range[0] + random.nextInt(Math.max(1, range[1] - range[0] + 1))) * 10;
    }

    private Block biteFloor(final Location water) {
        final Material floor = floorBlock();
        final Block block = water.getBlock();
        for (final Block next : List.of(block.getRelative(1, 0, 0), block.getRelative(-1, 0, 0), block.getRelative(0, 0, 1), block.getRelative(0, 0, -1))) {
            if (next.getType() == floor && next.getRelative(0, 1, 0).isPassable()) {
                final int[] center = plotCenter(plotAt(next.getX(), next.getZ()));
                if (next.getX() != center[0] || next.getZ() != center[1]) {
                    return next;
                }
            }
        }
        return null;
    }

    /** 筏邊挑一格水（旁邊是地板、地板上面沒東西、不是筏心）。 */
    private Location biteTarget(final int[] center, final Profile profile) {
        final int size = raftSize(profile.plot) + 2;
        final int[] range = extent(size);
        final int y = config.seaLevel();
        final List<Location> options = new ArrayList<>();
        for (int dx = range[0]; dx <= range[1]; dx++) {
            for (int dz = range[0]; dz <= range[1]; dz++) {
                final Block block = world.getBlockAt(center[0] + dx, y, center[1] + dz);
                if (block.getType() == Material.WATER && biteFloor(block.getLocation()) != null) {
                    options.add(block.getLocation());
                }
            }
        }
        return options.isEmpty() ? null : options.get(random.nextInt(options.size()));
    }

    private void spawnShark(final Player player, final Profile profile, final Session session, final int[] center, final LifeConfig.Shark def) {
        session.sharkTarget = biteTarget(center, profile);
        if (session.sharkTarget == null) {
            return;
        }
        final org.bukkit.entity.EntityType type;
        try {
            type = org.bukkit.entity.EntityType.valueOf(def.type());
        } catch (final IllegalArgumentException e) {
            return;
        }
        final double angle = random.nextDouble() * Math.PI * 2;
        final Location at = new Location(world, center[0] + Math.cos(angle) * 24, config.seaLevel() - 0.6, center[1] + Math.sin(angle) * 24);
        final Entity entity = world.spawnEntity(at, type, org.bukkit.event.entity.CreatureSpawnEvent.SpawnReason.CUSTOM);
        if (!(entity instanceof org.bukkit.entity.LivingEntity shark)) {
            entity.remove();
            return;
        }
        shark.setAI(false);
        shark.setPersistent(false);
        shark.setSilent(false);
        try {
            // 2026-10-17：跟小島的怪一樣，玩家等級減 10、最高 84（掉落的物品等級不比打地圖高）
            final int level = Math.max(1, Math.min(LifeIslands.ISLAND_LEVEL_CAP, plugin.players().get(player).level() - 10));
            plugin.monsters().decorate(shark, level, tw.exilecore.monster.MonsterRarity.MAGIC, List.of());
            plugin.monsters().markSummoned(shark); // 不掉一般的東西、不給經驗（獎勵是鯊魚材料）
            plugin.monsters().setLifeMultiplier(shark, def.life() * config.zone(profile.zone).sharkLife()); // 2026-10-23：海域
        } catch (final RuntimeException e) {
            shark.remove();
            return;
        }
        final org.bukkit.attribute.AttributeInstance scale = shark.getAttribute(org.bukkit.attribute.Attribute.SCALE);
        if (scale != null) {
            scale.setBaseValue(def.scale());
        }
        shark.customName(plugin.text().parse("<#ff6b6b>渦齒鯊"));
        shark.setCustomNameVisible(true);
        shark.getPersistentDataContainer().set(driftKey, PersistentDataType.STRING, "shark:" + player.getUniqueId());
        session.shark = shark;
        session.sharkAge = 0;
        session.sharkBite = 0;
        sharkPlots.add(profile.plot);
        player.showTitle(Title.title(plugin.text().parse("<#ff6b6b>渦齒鯊來了！"), plugin.text().parse("<gray>牠在咬你的筏——現在可以放技能"),
                Title.Times.times(Duration.ofMillis(200), Duration.ofMillis(1800), Duration.ofMillis(400))));
        player.playSound(player.getLocation(), Sound.ENTITY_ELDER_GUARDIAN_CURSE, 0.5f, 1.4f);
    }

    private void removeShark(final Session session) {
        if (session.shark != null) {
            final int plot = plotAt(session.shark.getLocation().getX(), session.shark.getLocation().getZ());
            sharkPlots.remove(plot);
            if (session.shark.isValid()) {
                session.shark.remove();
            }
        }
        session.shark = null;
        session.sharkTarget = null;
    }

    /** 渦齒鯊被打倒（LifeModule 的死亡事件）。 */
    void onDeath(final org.bukkit.entity.LivingEntity entity) {
        final String tag = entity.getPersistentDataContainer().get(driftKey, PersistentDataType.STRING);
        if (tag != null && tag.startsWith("island:")) {
            islands.onDeath(entity);
            return;
        }
        if (tag != null && (tag.startsWith("whale:") || tag.startsWith("whale_add:"))) {
            whale.onDeath(entity);
            return;
        }
        if (tag != null && tag.startsWith("king:")) {
            extras.onKingDeath(entity, tag); // 2026-10-23
            return;
        }
        if (tag == null || !tag.startsWith("shark:")) {
            return;
        }
        final Player owner = plugin.getServer().getPlayer(UUID.fromString(tag.substring(6)));
        for (final Session session : sessions.values()) {
            if (session.shark != null && session.shark.getUniqueId().equals(entity.getUniqueId())) {
                sharkPlots.remove(plotAt(entity.getLocation().getX(), entity.getLocation().getZ()));
                session.shark = null;
                session.sharkTarget = null;
            }
        }
        final LifeConfig.Shark def = config.shark();
        if (owner == null || def == null) {
            return;
        }
        final Profile profile = profile(owner);
        if (profile == null) {
            return;
        }
        final StringBuilder got = new StringBuilder();
        final double zoneDrops = config.zone(profile.zone).sharkDrops(); // 2026-10-23：海域
        for (final Map.Entry<String, int[]> drop : def.drops().entrySet()) {
            final int rolled = drop.getValue()[0] + random.nextInt(Math.max(1, drop.getValue()[1] - drop.getValue()[0] + 1));
            final int amount = (int) Math.round(rolled * zoneDrops);
            if (amount > 0) {
                addMaterial(profile, drop.getKey(), amount);
                got.append(got.isEmpty() ? "" : "、").append(materialName(drop.getKey())).append(" +").append(amount);
            }
        }
        addXp(owner, profile, def.xp());
        owner.sendMessage(plugin.text().prefixed("<#ff6b6b>打倒了渦齒鯊！<gray>" + ItemRenderer.escape(got.toString())));
        owner.playSound(owner.getLocation(), Sound.UI_TOAST_CHALLENGE_COMPLETE, 0.6f, 1.3f);
    }

    // ---- 死亡、離太遠 ----

    void diedAtSea(final Player player) {
        diedAtSea.add(player.getUniqueId());
        whale.ownerDied(player);
    }

    /** 重生點：死在海上的回自己的筏（沒有 null）。 */
    Location respawn(final Player player) {
        if (!diedAtSea.remove(player.getUniqueId())) {
            return null;
        }
        final Location arena = whale.respawn(player);
        if (arena != null) {
            return arena; // 打淵鯨倒下：重生在場地邊上
        }
        final int plot = activePlot(player);
        return plot < 0 ? null : raftSpawn(plot);
    }

    private void guard() {
        for (final Player player : world.getPlayers()) {
            if (builder(player) || player.getGameMode() == GameMode.SPECTATOR || player.isDead()) {
                continue;
            }
            if (!canEnter(player)) {
                // 名單拿掉、關閉開放：還在海上的人送回營地
                player.sendMessage(plugin.text().prefixed("<gray>" + ItemRenderer.escape(config.name()) + "現在沒有開放給你，送你回營地"));
                forget(player);
                player.performCommand("town");
                continue;
            }
            final Profile profile = profile(player);
            final int plot = profile == null ? -1 : activePlot(player);
            if (plot < 0) {
                continue;
            }
            final int[] center = plotCenter(plot);
            final Location at = player.getLocation();
            if (Math.abs(at.getX() - center[0]) > config.roamRadius() || Math.abs(at.getZ() - center[1]) > config.roamRadius()
                    || at.getY() < config.seaLevel() - 30) {
                player.teleportAsync(raftSpawn(plot));
                player.sendMessage(plugin.text().prefixed("<gray>海流把你沖回了筏上"));
            }
        }
    }

    // ---- 瓶中信（收集）----

    /** 讀一封瓶中信：先給沒讀過的；12 封都讀過就是「拾信者」。 */
    void readLetter(final Player player, final Profile profile) {
        final List<String> letters = config.letters();
        if (letters.isEmpty()) {
            return;
        }
        final int count = Math.min(63, letters.size());
        final List<Integer> unread = new ArrayList<>();
        for (int i = 0; i < count; i++) {
            if ((profile.letters & 1L << i) == 0) {
                unread.add(i);
            }
        }
        final int index = unread.isEmpty() ? random.nextInt(count) : unread.get(random.nextInt(unread.size()));
        final boolean fresh = !unread.isEmpty();
        player.sendMessage(plugin.text().prefixed("<#bfe6ff>📜 瓶中信" + (fresh ? "（第 " + (index + 1) + " 封）" : "") + "：<white>"
                + ItemRenderer.escape(letters.get(index))));
        if (!fresh) {
            return;
        }
        profile.letters |= 1L << index;
        profile.dirty = true;
        final int read = Long.bitCount(profile.letters & ((1L << count) - 1));
        bar(player, ("<#bfe6ff>收集瓶中信 " + read + "／" + count));
        if (read == count) {
            flush(player);
            final LifeConfig.Title title = config.titles().get("letter_finder");
            if (title != null) {
                plugin.players().markDirty(player);
                player.sendMessage(plugin.text().prefixed("<#bfe6ff>瓶中信全部收齊了！獲得稱號「" + ItemRenderer.escape(title.name()) + "」（稱號選單可以戴）"));
                player.playSound(player.getLocation(), Sound.UI_TOAST_CHALLENGE_COMPLETE, 0.8f, 1.2f);
            }
        }
    }

    int lettersRead(final Player player) {
        final Profile profile = profile(player);
        return profile == null ? 0 : Long.bitCount(profile.letters & ((1L << Math.min(63, config.letters().size())) - 1));
    }

    /** 每天從海上拿到通貨的額度（瓶中信、小島寶箱共用）；還有就扣一次。 */
    boolean takeCurrencyQuota(final Profile profile) {
        final String today = config.day(System.currentTimeMillis());
        if (!today.equals(profile.currencyDay)) {
            profile.currencyDay = today;
            profile.currencyCount = 0;
        }
        if (profile.currencyCount >= config.bottleDailyCurrency()) {
            return false;
        }
        profile.currencyCount++;
        profile.dirty = true;
        return true;
    }

    // ---- 通貨 ----

    String currencyName(final String id) {
        final CurrencyType type = plugin.currencies() == null ? null : plugin.currencies().repository().get(id);
        return type == null ? id : type.name();
    }

    void giveCurrency(final Player player, final String currencyId, final int amount) {
        if (amount <= 0 || plugin.currencies() == null) {
            return;
        }
        final CurrencyType type = plugin.currencies().repository().get(currencyId);
        if (type == null) {
            return;
        }
        if (plugin.economy() != null) {
            plugin.economy().ledger().produced(type.id(), tw.exilecore.economy.EconomyLedger.EVENT, amount);
        }
        final ItemStack item = plugin.currencies().create(type, amount);
        final ItemStack rest = plugin.loot() != null ? plugin.loot().stowCurrency(player, plugin.players().get(player), type, item)
                : plugin.economy().wallet().give(player, item);
        plugin.economy().wallet().dropFor(player, rest);
    }

    /** 關服：漂流物收掉、存檔。 */
    void shutdown() {
        islands.shutdown();
        whale.shutdown();
        extras.shutdown(); // 2026-10-23
        flushAll();
        for (final Session session : sessions.values()) {
            clearSession(session);
        }
        sessions.clear();
        if (world != null) {
            for (final Entity entity : world.getEntities()) {
                if (entity.getPersistentDataContainer().has(driftKey)) {
                    entity.remove();
                }
            }
        }
    }

    /** 管理員狀態。 */
    public String status() {
        return String.format(Locale.ROOT, "%s：世界 %s；分出去 %d 塊筏；在海上 %d 人", config.name(), world == null ? "沒有載入" : world.getName(),
                plotOwners.size(), world == null ? 0 : world.getPlayers().size());
    }
}
