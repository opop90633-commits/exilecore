package tw.exilecore.map;

import net.kyori.adventure.text.Component;
import net.kyori.adventure.title.Title;
import org.bukkit.Color;
import org.bukkit.GameMode;
import org.bukkit.Location;
import org.bukkit.NamespacedKey;
import org.bukkit.Particle;
import org.bukkit.Sound;
import org.bukkit.World;
import org.bukkit.entity.Display;
import org.bukkit.entity.LivingEntity;
import org.bukkit.entity.Player;
import org.bukkit.entity.TextDisplay;
import org.bukkit.inventory.ItemStack;
import org.bukkit.persistence.PersistentDataType;
import tw.exilecore.ExileCore;
import tw.exilecore.character.CharacterRecord;
import tw.exilecore.character.PlayerState;
import tw.exilecore.currency.CurrencyType;
import tw.exilecore.item.ItemBase;
import tw.exilecore.item.ItemData;
import tw.exilecore.item.ItemRenderer;
import tw.exilecore.monster.DropTable;
import tw.exilecore.monster.MonsterData;
import tw.exilecore.monster.MonsterListener;
import tw.exilecore.stats.Modifier;
import tw.exilecore.stats.StatSheet;
import tw.exilecore.world.CampaignWorld;
import tw.exilecore.worldgen.BufferSink;
import tw.exilecore.worldgen.CampaignLayout;
import tw.exilecore.worldgen.CampaignStructures;
import tw.exilecore.zone.Zone;
import tw.exilecore.zone.ZoneConfig;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.OptionalInt;
import java.util.Set;
import java.util.TreeSet;
import java.util.UUID;
import java.util.random.RandomGenerator;

/**
 * 地圖的核心：開地圖（在地圖世界的空格子蓋出版面）、傳送門進出、實例的生命週期、
 * 給其他模組的掛勾（怪物屬性、等級、掉落加成、死亡懲罰、/town 回來、人多時怪物變硬）。
 * <p>
 * 傳送門是每個人（或每個隊伍）自己的：營地的六道門對每個人顯示「自己或隊友開的最新一張地圖」，
 * 還能進幾次就亮幾道（粒子只有自己看得到，門上的字也只有自己隊伍看得到），互不佔位。
 * 同一個隊伍（或沒隊伍的一個人）同時只有一張「目前的地圖」：開新的，舊的那張就進不去了（裡面的人可以打完再出來）。
 * <p>
 * 地圖世界的格子：第 i 格的原點是 (1024 + (i % 16) × 512, 1024 + (i / 16) × 512)，版面在原點往東南 192 格內。
 * 蓋過還沒清的格子記在 plugins/ExileCore/map-cells.txt，下次啟動先清掉（伺服器當掉也不會留下舊地圖）。
 */
public final class MapService {

    /**
     * 特別的實例（每週回響、昇華試煉）：不用地圖物品、不看星圖；Boss 倒下時不點亮星圖、不掉地圖與傳奇，改通知 onComplete。
     *
     * @param kind       種類（給紀錄看）
     * @param color      營地傳送門的粒子顏色（RGB）
     * @param onComplete Boss 倒下時：這個實例、當時在裡面的玩家
     * @param loot       每一份掉落（怪物、Boss，掉落表擲完之後）再交給開它的模組改（劫盜：裝備加隱匿詞綴）；null = 不改
     */
    /** 極境、劫盜終局的版面：起點一間，下一間就是 Boss 房（2026-10）。 */
    public static final int UBER_ROOMS = 2;

    public record Special(String kind, int color, java.util.function.BiConsumer<MapInstance, List<Player>> onComplete,
                          java.util.function.BiConsumer<MapInstance, List<DropTable.Drop>> loot) {

        public Special(final String kind, final int color, final java.util.function.BiConsumer<MapInstance, List<Player>> onComplete) {
            this(kind, color, onComplete, null);
        }
    }

    static final int CELL_SIZE = 512;
    static final int CELL_OFFSET = 1024;
    static final int CELL_COLUMNS = 16;
    /** 清格子時的高度範圍（地板 64，牆、柱子、樹葉最高不會超過地板上 16 格）。 */
    static final int CLEAR_MIN_Y = MapLayout.FLOOR_Y - 4;
    static final int CLEAR_MAX_Y = MapLayout.FLOOR_Y + 18;
    /** 從地圖回來之後幾 tick 內不會再被傳送門吸進去。 */
    private static final long GATE_COOLDOWN_TICKS = 60;
    /** 離開隊伍後還能留在那張地圖裡多久。 */
    private static final long EVICT_TICKS = 30 * 20;

    private final ExileCore plugin;
    private final MapConfig config;
    private final MapWorks works;
    private final MapSpawner spawner;
    private final MapBosses bosses;
    /** 地圖事件（神龕、寶箱、裂隙、流寇）。 */
    private final MapEventConfig eventConfig = new MapEventConfig();
    private final MapEvents events;
    /** 星圖天賦（atlas_tree.yml）。 */
    private final AtlasTree tree = new AtlasTree();
    private final NamespacedKey instanceKey;
    private final NamespacedKey bossKey;
    private final Path cellsFile;
    private final Map<Integer, MapInstance> instances = new LinkedHashMap<>();
    /** 格子 → 實例（正在清的格子是 -1）。 */
    private final Map<Integer, Integer> cells = new HashMap<>();
    /** 上次沒清乾淨、要先清的格子。 */
    private final Set<Integer> dirty = new TreeSet<>();
    /** 門上的字：沒有地圖的人看到的說明（預設看得到）、每張地圖一個（只有那個隊伍看得到）。 */
    /** 門上的字的底邊離地面多高（吊燈的石頭頂在 6，留一點空）。 */
    static final double LABEL_HEIGHT = 6.3;
    private TextDisplay generalLabel;
    private final Map<Integer, TextDisplay> groupLabels = new HashMap<>();
    private final Map<UUID, Long> gateCooldown = new HashMap<>();
    /** 在地圖裡死掉的玩家（重生時送回營地）。 */
    private final Set<UUID> diedInMap = new LinkedHashSet<>();
    /** 不再是這張地圖的隊伍（被請出、自己離開）還在裡面的人：哪個 tick 送回營地。 */
    private final Map<UUID, Long> evictAt = new HashMap<>();
    /** 子系統上次記錯誤的 tick。 */
    private final Map<String, Long> errorLogged = new HashMap<>();
    /** 玩家身上記的「最後進入的實例」（只能回到同一個實例，格子重用時不會跑進別人的地圖）。 */
    private final NamespacedKey tokenKey;
    private int nextId = 1;
    private long ticks;
    private final BossDamageLog bossLog;
    /** 極境、金庫之主死掉後的觀戰（2026-10）。 */
    private final MapSpectators spectators;

    MapService(final ExileCore plugin, final MapConfig config) {
        this.plugin = plugin;
        this.config = config;
        this.works = new MapWorks(plugin, plugin.getSLF4JLogger());
        this.instanceKey = new NamespacedKey(plugin, "map_instance");
        this.bossKey = new NamespacedKey(plugin, "map_boss");
        this.cellsFile = plugin.dataFolder().resolve("map-cells.txt");
        this.tokenKey = new NamespacedKey(plugin, "map_token");
        this.spawner = new MapSpawner(plugin, this);
        this.bosses = new MapBosses(plugin, this);
        this.events = new MapEvents(plugin, this, eventConfig);
        this.bossLog = new BossDamageLog(plugin);
        this.spectators = new MapSpectators(plugin, this);
    }

    /** 終局 Boss 開打一分鐘的傷害紀錄（給模組註冊監聽）。 */
    BossDamageLog bossLog() {
        return bossLog;
    }

    /** 地圖事件（給模組註冊監聽）。 */
    MapEvents events() {
        return events;
    }

    /** 重讀 map_events.yml（之後開的地圖照新的）。 */
    void reloadEvents() {
        try {
            eventConfig.load(plugin.dataFolder().resolve("data"));
        } catch (final Exception e) {
            plugin.getSLF4JLogger().error("map_events.yml 讀取失敗，地圖事件照原本的設定", e); // 2026-10-18：load 改成成功才換上，舊的設定留著
        }
    }

    /** 重讀 atlas_tree.yml（之後開的地圖照新的）；設定的問題記在主控台。 */
    void reloadTree() {
        try {
            tree.load(plugin.dataFolder().resolve("data"));
        } catch (final Exception e) {
            plugin.getSLF4JLogger().error("atlas_tree.yml 讀取失敗，星圖天賦照上一次讀到的", e);
            return;
        }
        for (final String problem : tree.problems()) {
            plugin.getSLF4JLogger().warn(problem);
        }
    }

    /** 星圖天賦的設定。 */
    public AtlasTree tree() {
        return tree;
    }

    public MapConfig config() {
        return config;
    }

    /** 地圖事件與機制的設定（注入要查蒸餾情緒的階級）。 */
    public MapEventConfig eventConfig() {
        return eventConfig;
    }

    NamespacedKey instanceKey() {
        return instanceKey;
    }

    NamespacedKey bossKey() {
        return bossKey;
    }

    MapBosses bosses() {
        return bosses;
    }

    RandomGenerator random() {
        return plugin.items().random();
    }

    /** 地圖世界；沒有載入回傳 null。 */
    public World world() {
        final CampaignWorld worlds = plugin.world() == null ? null : plugin.world().worlds();
        return worlds == null ? null : worlds.maps();
    }

    // ---- 啟動與關閉 ----

    /** 讀上次沒清的格子，排進清理。 */
    void start() {
        reloadEvents();
        reloadTree();
        dirty.clear();
        try {
            if (Files.exists(cellsFile)) {
                for (final String line : Files.readAllLines(cellsFile, StandardCharsets.UTF_8)) {
                    final String text = line.trim();
                    if (!text.isEmpty() && !text.startsWith("#")) {
                        dirty.add(Integer.parseInt(text));
                    }
                }
            }
        } catch (final IOException | NumberFormatException e) {
            plugin.getSLF4JLogger().warn("讀取 map-cells.txt 失敗，舊的地圖格子可能要手動清", e);
        }
        final World world = world();
        if (world == null) {
            return;
        }
        for (final int cell : List.copyOf(dirty)) {
            cells.put(cell, -1);
            final int[] origin = origin(cell);
            works.clear(world, origin[0] - 16, CLEAR_MIN_Y, origin[1] - 16, origin[0] + MapLayout.SIZE + 16, CLEAR_MAX_Y,
                    origin[1] + MapLayout.SIZE + 16, () -> {
                        cells.remove(cell);
                        dirty.remove(cell);
                        saveCells();
                    });
        }
        if (!dirty.isEmpty()) {
            plugin.getSLF4JLogger().info("地圖：清理上次沒清的 {} 個格子", dirty.size());
        }
    }

    /** 模組停用：玩家送回營地、Boss 血條收掉、實體移除（格子留到下次啟動清）。 */
    void shutdown() {
        spectators.endAll(); // 2026-10-17：觀戰的人先改回原本的模式（以前重開後會卡在旁觀模式）
        for (final MapInstance instance : List.copyOf(instances.values())) {
            // 2026-10-17：重開時還沒打完的終局、極境、巔峰、金庫之主（花碎片、鑰匙開的）：退回開門用掉的東西
            // 2026-10-18：門用掉過（打輸過一門）就不退；也不退「進去過但已經沒人在裡面」的（以前打輸後遇到重開就全額退，等於免費重打）
            if ((instance.usesAttempts() || instance.singleEntry()) && !instance.bossKilled() && instance.state() != MapInstance.State.CLOSING
                    && instance.portalsUntouched() && (instance.visitors().isEmpty() || !instance.inside().isEmpty())) {
                instance.refundPaid();
            }
            for (final UUID uuid : Set.copyOf(instance.inside())) {
                final Player player = plugin.getServer().getPlayer(uuid);
                if (player != null) {
                    sendToCamp(player);
                }
            }
            bosses.end(instance);
            spawner.removeAll(instance);
        }
        events.clearAll();
        removeLabels();
        instances.clear();
        saveCells();
    }

    private void saveCells() {
        final List<String> lines = new ArrayList<>();
        lines.add("# 蓋過、還沒清掉的地圖格子（下次啟動會先清掉）");
        final Set<Integer> used = new TreeSet<>(dirty);
        for (final MapInstance instance : instances.values()) {
            used.add(instance.cell());
        }
        for (final int cell : used) {
            lines.add(String.valueOf(cell));
        }
        try {
            Files.write(cellsFile, lines, StandardCharsets.UTF_8);
        } catch (final IOException e) {
            plugin.getSLF4JLogger().warn("寫入 map-cells.txt 失敗", e);
        }
    }

    static int[] origin(final int cell) {
        return new int[] {CELL_OFFSET + (cell % CELL_COLUMNS) * CELL_SIZE, CELL_OFFSET + (cell / CELL_COLUMNS) * CELL_SIZE};
    }

    // ---- 查詢 ----

    public Collection<MapInstance> instances() {
        return instances.values();
    }

    public MapInstance instance(final int id) {
        return instances.get(id);
    }

    /** 這個位置在哪個開著的實例裡。 */
    public MapInstance instanceAt(final Location location) {
        if (location == null || location.getWorld() == null || !location.getWorld().equals(world())) {
            return null;
        }
        for (final MapInstance instance : instances.values()) {
            if (instance.contains(location)) {
                return instance;
            }
        }
        return null;
    }

    /** 這個玩家現在在極境裡（離開就不能再回來）。 */
    public boolean inUber(final Player player) {
        final MapInstance instance = instanceAt(player.getLocation());
        return instance != null && instance.isUber() && instance.state() == MapInstance.State.OPEN;
    }

    public boolean inMap(final Player player) {
        return instanceAt(player.getLocation()) != null;
    }

    /**
     * 換階段鎖血（2026-10）：這一下（擊中或持續傷害）之後原版血量最低到哪裡。
     * 地圖 Boss 還沒進下一個階段時是下一個門檻；其他怪物回傳 0（不鎖）。
     */
    public double phaseFloor(final LivingEntity entity) {
        if (!entity.getPersistentDataContainer().has(bossKey())) {
            return 0;
        }
        return bosses.phaseFloor(instanceOf(entity), entity);
    }

    /** 巔峰的每秒傷害上限（2026-10）：每個人的額度。 */
    private final DpsCap dpsCap = new DpsCap();

    /**
     * 打終局、極境、巔峰 Boss 的傷害照 maps.yml 的每秒上限限制（2026-10）：每秒傷害超過上限的部分照 dps_over 遞減
     * （每秒傷害取打訓練假人的紀錄與實際打出來的較高者，見 {@link DpsCap}）。
     * 不是巔峰 Boss、沒有上限的照原本的回傳。
     *
     * @param source 打的玩家（召喚物、持續傷害算主人）
     */
    public double capBossDamage(final LivingEntity defender, final UUID source, final double amount) {
        final String worldRole = plugin.worldEvents() == null ? null : plugin.worldEvents().role(defender);
        if (amount > 0 && source != null && ("boss".equals(worldRole) || "commander".equals(worldRole)
                || plugin.festival() != null && plugin.festival().isKing(defender)
                || plugin.life() != null && plugin.life().isWhale(defender))) {
            // 2026-10：世界 Boss（開天者、萬燈夜的南瓜王）、入侵怪潮的潮汐統帥、星骸之海的淵鯨比照極境：每秒傷害上限與分段遞減照 maps.yml 的 final_dps（15 萬）
            final Player player = plugin.getServer().getPlayer(source);
            final double record = player == null || plugin.training() == null ? 0 : plugin.training().bossDps(player);
            return dpsCap.apply("world:" + source, amount, config.finalDpsCap(), config.finalDpsOver(),
                    plugin.getServer().getCurrentTick(), record);
        }
        if (amount <= 0 || source == null || !defender.getPersistentDataContainer().has(bossKey())) {
            return amount;
        }
        final MapInstance instance = instanceOf(defender);
        // 2026-10：終局、極境、巔峰、金庫之主都是 15 萬（maps.yml 的 final_dps、各極境與巔峰的 dps_cap、dps_over；沒寫就不限）
        if (instance == null || !instance.usesAttempts()) {
            return amount;
        }
        final double cap = instance.isPinnacle() ? instance.pinnacle().dpsCap()
                : instance.isUber() ? instance.uber().dpsCap() : config.finalDpsCap();
        if (cap <= 0) {
            return amount;
        }
        final List<MapConfig.DpsTier> over = instance.isPinnacle() ? instance.pinnacle().dpsOver()
                : instance.isUber() ? instance.uber().dpsOver() : config.finalDpsOver();
        // 打假人的紀錄換成打 Boss 的每秒傷害（dummies.yml 的 boss_factor：打無防禦的白怪假人比打首領高很多）
        final Player player = plugin.getServer().getPlayer(source);
        final double record = player == null || plugin.training() == null ? 0 : plugin.training().bossDps(player);
        return dpsCap.apply(DpsCap.key(instance.id(), source), amount, cap, over, plugin.getServer().getCurrentTick(), record);
    }

    /**
     * 這隻 Boss 不普攻（2026-10）：終局、極境、巔峰、金庫之主只用招式（照 PoE1 的賽勒斯——祂從不揮手，
     * 全部靠光束、殞星、風暴）；原版的近戰、射箭整場都擋掉。護盾結晶、小怪不算。
     */
    public boolean bossSkillsOnly(final LivingEntity entity) {
        // 2026-10-17：世界 Boss（不定時活動）與南瓜王（萬燈夜）也不普攻，只用招式（出招節奏另外調快）
        if (plugin.worldEvents() != null && "boss".equals(plugin.worldEvents().role(entity))) {
            return true;
        }
        if (plugin.festival() != null && plugin.festival().isKing(entity)) {
            return true;
        }
        if (!entity.getPersistentDataContainer().has(bossKey())) {
            return false;
        }
        final MapInstance instance = instanceOf(entity);
        return instance != null && instance.usesAttempts();
    }

    /** 正在打地圖 Boss（2026-10，掛機偵測用）：人在自己的地圖裡、Boss 還活著、離 Boss 48 格內。 */
    public boolean inBossFight(final Player player) {
        final MapInstance instance = instanceAt(player.getLocation());
        if (instance == null || instance.state() != MapInstance.State.OPEN || instance.bossKilled() || !belongs(player, instance)) {
            return false;
        }
        final LivingEntity boss = instance.boss();
        return boss != null && boss.isValid() && !boss.isDead() && boss.getWorld().equals(player.getWorld())
                && boss.getLocation().distanceSquared(player.getLocation()) <= 48 * 48;
    }

    /** 鎖在門檻上的 Boss：下一個 tick 檢查換階段。 */
    public void phaseLocked(final LivingEntity entity) {
        final MapInstance instance = instanceOf(entity);
        plugin.getServer().getScheduler().runTask(plugin, () -> bosses.checkNow(instance, entity));
    }

    /** 這隻怪物屬於哪個實例（沒有標記回傳 null）。 */
    public MapInstance instanceOf(final LivingEntity entity) {
        final Integer id = entity.getPersistentDataContainer().get(instanceKey, PersistentDataType.INTEGER);
        return id == null ? null : instances.get(id);
    }

    /** 角色完成過的地圖。 */
    public Set<String> completed(final Player player) {
        return Atlas.completed(config, plugin.players().get(player).progress());
    }

    /** 角色完成過的最高階（終局能開到這一階）；沒完成過是 0。 */
    public int bestTier(final Player player) {
        final Map<String, String> progress = plugin.players().get(player).progress();
        return Atlas.bestTier(config, progress, Atlas.completed(config, progress));
    }

    /** 終局開在某一階時的怪物等級。 */
    public int finalLevel(final int tier) {
        return config.tiers().level(tier) + config.finalLevelBonus();
    }

    /**
     * 終局的等級說明（星圖、地圖裝置）：整個範圍（T1–T16 的怪物等級），再一行這個角色現在最高能開到幾階。
     * 只寫「現在能開 T1–T1・64 級」會讓人以為終局就是 64 級。
     */
    public List<String> finalTierLines(final Player player) {
        final int max = config.tiers().max();
        final int best = bestTier(player);
        final List<String> lines = new ArrayList<>();
        lines.add("<gray>階級 T1–T" + max + "・怪物等級 <white>" + finalLevel(1) + "–" + finalLevel(max)
                + "<dark_gray>（地圖的怪物等級 +" + config.finalLevelBonus() + "）");
        final double top = config.finalLifeMultiplier(max);
        if (top > 1.0) {
            int from = 1;
            while (from < max && config.finalLifeMultiplier(from + 1) <= 1.0) {
                from++;
            }
            lines.add("<gray>T" + (from + 1) + " 起 Boss 生命越來越多：T" + max + " <white>×" + MapDeviceMenu.number(top)
                    + "<dark_gray>（剛進終局的人先打低階）");
        }
        if (best <= 0) {
            lines.add("<gray>你還沒完成過地圖<dark_gray>：完成過幾階的地圖，終局就能開到幾階");
        } else {
            lines.add("<gray>你現在最高能開 <white>T" + best + "<gray>（怪物 " + finalLevel(best) + " 級）"
                    + (best < max ? "<dark_gray>：完成更高階的地圖就往上開放" : ""));
        }
        return lines;
    }

    /** 物品是不是地圖（基底 slot MAP）。 */
    public boolean isMap(final ItemData data) {
        final ItemBase base = data == null ? null : plugin.items().repository().base(data.base());
        return base != null && base.isMap();
    }

    // ---- 給其他模組的掛勾 ----

    /** 怪物屬性表：地圖詞綴（全部怪物與 Boss）＋Boss 的階段。 */
    void augment(final LivingEntity entity, final StatSheet sheet) {
        final MapInstance instance = instanceOf(entity);
        if (instance == null) {
            return;
        }
        sheet.addAll(instance.stats().monsterModifiers());
        if (entity.getPersistentDataContainer().has(bossKey)) {
            sheet.addAll(instance.stats().bossModifiers());
            sheet.addAll(bosses.phaseModifiers(instance));
        }
    }

    /** 地圖裡的怪物等級（召喚出來的、原版法術生出來的小怪也算）。 */
    OptionalInt levelAt(final Location location) {
        final MapInstance instance = instanceAt(location);
        return instance == null ? OptionalInt.empty() : OptionalInt.of(instance.level());
    }

    /** 在地圖裡的玩家受到的地圖詞綴。 */
    List<Modifier> playerModifiers(final Player player) {
        final MapInstance instance = instanceAt(player.getLocation());
        return instance == null ? List.of() : instance.stats().playerModifiers();
    }

    /**
     * 終局以上的 Boss（終局、極境、巔峰、金庫之主；2026-10-16）：掉落不吃物品數量、稀有度（自己的、全服、主題、地圖的都不算）。
     */
    public boolean flatBossLoot(final LivingEntity entity) {
        if (plugin.life() != null && plugin.life().isWhale(entity)) {
            return true; // 星骸之海的淵鯨（比照巔峰）
        }
        if (!entity.getPersistentDataContainer().has(bossKey)) {
            return false;
        }
        final MapInstance instance = instanceOf(entity);
        return instance != null && instance.usesAttempts();
    }

    /** 掉落加成（小數）：{物品數量, 物品稀有度}。 */
    public double[] dropBonus(final LivingEntity entity) {
        final MapInstance instance = instanceOf(entity);
        if (instance == null || instance.special() != null && !entity.getPersistentDataContainer().has(bossKey)) {
            return new double[] {0, 0}; // 特別的實例（回響、試煉）：只有 Boss 有加成，路上的怪照一般掉
        }
        // 2026-10：這個月的主題改成全域的（MonsterListener 在地圖內外都加），這裡不再加，免得地圖裡算兩次
        // 星圖天賦：地圖 Boss 另外的物品數量與稀有度（地圖本身的已經在 stats 裡）
        final boolean boss = entity.getPersistentDataContainer().has(bossKey);
        final double bossQuantity = boss ? instance.atlas().increased("atlas.boss_quantity") : 0;
        final double bossRarity = boss ? instance.atlas().increased("atlas.boss_rarity") : 0;
        // 地圖變化：血路、流浪精英加路上的怪；王道、Boss 變體加 Boss
        final double twistQuantity = boss ? instance.twist().bossLoot : instance.twist().quantity;
        final double twistRarity = boss ? instance.twist().bossLoot : 0;
        return new double[] {instance.stats().quantity() / 100.0 + events.quantityBonus(entity) + bossQuantity + twistQuantity,
                instance.stats().rarity() / 100.0 + bossRarity + twistRarity};
    }

    /**
     * /town 之後回來：只能回到還開著的、自己最後進的那個實例（格子重用時不會跑進別人的地圖），
     * 而且還是自己或隊友開的（被請出隊伍就回不去了）。
     */
    public boolean canReturnTo(final Player player, final Location location) {
        return returnProblem(player, location) == null;
    }

    /** 同上，回傳不能回去的原因（null = 可以）：地圖關了；極境離開了就不能再進。 */
    public String returnProblem(final Player player, final Location location) {
        if (location == null || location.getWorld() == null || !location.getWorld().equals(world())) {
            return null;
        }
        final MapInstance instance = instanceAt(location);
        if (!belongs(player, instance) || !canEnter(player, instance)) {
            return "那張地圖已經關閉了";
        }
        if (instance.usesAttempts()) {
            return "這一門你已經出來了：等裡面的人全部倒下（或出來），再走下一門";
        }
        if (instance.singleEntry()) {
            return singleEntryName(instance) + "每個人只能進去一次，離開了就不能再回去";
        }
        return null;
    }

    /**
     * 一個人的那份掉落（掉落表擲完之後、交給撿取之前）：地圖怪照他的星圖掉地圖；地圖 Boss 的地圖、傳奇、天穹碎片。
     *
     * @param player 拿這份的人；null = 沒有人有份
     */
    public void rollLoot(final LivingEntity entity, final MonsterData data, final Player player, final double quantityBonus,
                         final List<DropTable.Drop> drops) {
        final MapInstance instance = instanceOf(entity);
        if (instance == null) {
            return;
        }
        if (instance.special() != null) {
            // 特別的實例（回響、試煉、劫盜）：只有掉落表（加上物品數量、稀有度），不掉地圖、傳奇、天穹碎片；劫盜另外改裝備
            if (instance.special().loot() != null) {
                try {
                    instance.special().loot().accept(instance, drops);
                } catch (final RuntimeException e) {
                    plugin.getSLF4JLogger().error("{}（{}）的掉落處理出錯", instance.label(), instance.special().kind(), e);
                }
            }
            return;
        }
        // 劫盜合約：只有星圖（一般的地圖）裡的怪物掉（heist.yml 的 contract_chance；吃物品數量）
        final boolean boss = entity.getPersistentDataContainer().has(bossKey);
        if (plugin.heists() != null) {
            final double[] contract = plugin.heists().config().settings().contractChance();
            final double base = boss ? contract[3] : switch (data.rarity()) {
                case NORMAL -> contract[0];
                case MAGIC -> contract[1];
                case RARE, UNIQUE -> contract[2];
            };
            final tw.exilecore.currency.CurrencyType type = plugin.currencies() == null ? null
                    : plugin.currencies().repository().get(tw.exilecore.heist.HeistService.CONTRACT);
            if (type != null && random().nextDouble() < Math.min(1, base * (1 + Math.max(0, quantityBonus)))) {
                drops.add(DropTable.Drop.currency(type));
            }
        }
        // 2026-10-17：只有這張圖現在的 Boss 給 Boss 掉落（區塊重新載入帶回來的舊 Boss 不算，以前打死會再擲一整份）
        if (boss && instance.boss() != null && instance.boss().getUniqueId().equals(entity.getUniqueId())) {
            bosses.loot(instance, player, drops);
            return;
        }
        // 追逐級傳奇：一般怪物也有極低的固定機率（不吃任何加成；怪物等級不到 75 時池子是空的）
        if (random().nextDouble() < config.chaseMobChance()) {
            final ItemData chase = MapDrops.unique(plugin.items().generator(), "chase", data.level(), random());
            if (chase != null) {
                drops.add(DropTable.Drop.equipment(chase));
            }
        }
        // 共鳴石（2026-10-18）：一般怪物（等級 75 以上）另外擲一次 drops.resonance_mob_chance（約 1500 萬隻一顆；跟追逐級一樣不吃任何加成）
        if (plugin.currencies() != null && data.level() >= MapConfig.RESONANCE_MIN_LEVEL && random().nextDouble() < config.resonanceMobChance()) {
            final tw.exilecore.currency.CurrencyType resonance = plugin.currencies().resonanceType();
            if (resonance != null) {
                drops.add(DropTable.Drop.currency(resonance));
            }
        }
        // 星圖天賦：地圖怪掉地圖的機率增加、高一階的機率增加
        final double chance = config.drops().mapChance().getOrDefault(data.rarity(), 0.0) * (1 + Math.max(0, quantityBonus))
                * (1 + instance.atlas().mapDrop());
        if (random().nextDouble() < chance) {
            final ItemData map = MapDrops.roll(config, plugin.items().generator(), Math.max(1, instance.stats().tier()),
                    player == null ? null : completed(player), random(), instance.atlas().tierUp());
            if (map != null) {
                drops.add(DropTable.Drop.equipment(map));
            }
        }
    }

    /** 地圖怪死亡（每個人的掉落都擲完之後，一次）：殺怪數；地圖 Boss 的完成。 */
    public void onMonsterDeath(final LivingEntity entity) {
        final MapInstance instance = instanceOf(entity);
        if (instance == null) {
            return;
        }
        instance.addKill();
        if (entity.getPersistentDataContainer().has(bossKey)) {
            bosses.killed(instance, entity);
        }
    }

    /** 地圖怪多給的經驗（星圖天賦，小數；不是地圖怪是 0）。 */
    public double experienceBonus(final LivingEntity entity) {
        final MapInstance instance = instanceOf(entity);
        return instance == null ? 0 : instance.atlas().experience();
    }

    /** 終局以上的 Boss（2026-10-15）：Boss 生命掉到 75% 以下才進來的人沒有掉落；其他怪物、其他地圖都有。 */
    public boolean bossLootEligible(final LivingEntity entity, final Player player) {
        final MapInstance instance = instanceOf(entity);
        return instance == null || instance.boss() != entity || instance.lootEligible(player.getUniqueId());
    }

    /** 這隻怪物跟這個玩家在同一張地圖裡（掉落、經驗「在附近」的判斷：同一張地圖都算）。 */
    public boolean sameInstance(final LivingEntity entity, final Player player) {
        final MapInstance instance = instanceOf(entity);
        return instance != null && instance.contains(player.getLocation());
    }

    /** 地圖怪刷出來時，這張地圖裡有幾個玩家（人多時怪物變硬）；不是地圖怪回傳空的。 */
    public OptionalInt playersIn(final LivingEntity entity) {
        final MapInstance instance = instanceOf(entity);
        if (instance == null) {
            return OptionalInt.empty();
        }
        int count = 0;
        for (final Player player : instance.world().getPlayers()) {
            if (instance.contains(player.getLocation()) && !player.isDead() && player.getGameMode() != GameMode.SPECTATOR
                    && player.getGameMode() != GameMode.CREATIVE) {
                count++;
            }
        }
        return OptionalInt.of(Math.max(1, count));
    }

    /** 劇情 Boss 每天掉地圖的次數記在角色進度的這個鍵（值是「2026-10-02|3」：哪一天、第幾次）。 */
    static final String PROGRESS_BOSS_MAPS = "bossmaps.";

    /**
     * 劇情區域 Boss 被打死：最後一個區域（霜頂山脈）的 Boss 掉 T1 地圖。首殺照 campaign_first_kill_maps；
     * 之後每個角色每天只有前 campaign_boss_daily_maps 次會掉（Boss 90 秒就重生，不然可以一直站著刷地圖）。
     */
    public void onZoneBossKilled(final Zone zone, final Player player, final boolean firstKill, final Location location) {
        if (!zone.id().equals(config.drops().campaignBossZone())) {
            return;
        }
        if (!firstKill && !takeDailyBossMaps(player, zone)) {
            return;
        }
        final int count = firstKill ? config.drops().campaignFirstKillMaps() : config.drops().campaignBossMaps();
        final Set<String> completed = completed(player);
        for (int i = 0; i < count; i++) {
            final ItemData map = MapDrops.roll(config, plugin.items().generator(), 1, completed, random());
            if (map != null) {
                MonsterListener.dropOwned(plugin, location, plugin.items().toStack(map), player, true);
            }
        }
        if (count > 0 && firstKill) {
            player.sendMessage(plugin.text().prefixed("<#f5b942>星骸的記憶甦醒了<gray>：撿起地圖，帶回營地的地圖裝置（NPC）打開傳送門"));
        }
    }

    /** 今天還能不能從劇情 Boss 拿地圖；能的話算一次（存進角色進度）。今天拿完了提示一次。 */
    private boolean takeDailyBossMaps(final Player player, final Zone zone) {
        final int cap = config.drops().campaignBossDailyMaps();
        if (cap <= 0) {
            return false;
        }
        final String key = PROGRESS_BOSS_MAPS + zone.id();
        final String today = plugin.economy().ledger().today();
        final int used = MapDrops.dailyCount(plugin.players().get(player).progress().get(key), today);
        if (used >= cap) {
            if (used == cap) {
                plugin.characters().setProgress(player, key, today + "|" + (cap + 1)); // 只提示一次
                player.sendMessage(plugin.text().prefixed("<gray>今天從" + ItemRenderer.escape(zone.boss() == null ? "這個 Boss" : zone.boss().name())
                        + "拿地圖的次數用完了（每天 " + cap + " 次）。<white>地圖裡的怪和地圖 Boss</white> 會一直掉地圖"));
            }
            return false;
        }
        plugin.characters().setProgress(player, key, today + "|" + (used + 1));
        return true;
    }

    // ---- 開地圖 ----

    /** 開地圖的結果（null = 成功）。 */
    public String activate(final Player player, final int slot, final ItemData expected) {
        if (!plugin.players().get(player).hasCharacter()) {
            return "先建立角色";
        }
        final ItemStack stack = player.getInventory().getItem(slot);
        final Optional<ItemData> data = stack == null ? Optional.empty() : plugin.items().read(stack);
        if (data.isEmpty() || expected == null || !data.get().uuid().equals(expected.uuid())) {
            return "那張地圖已經不在背包的那一格了，重新選一次";
        }
        final MapConfig.MapDef map = config.byBase(data.get().base());
        if (map == null) {
            return "這不是能開的地圖（maps.yml 沒有這個基底）";
        }
        if (!Atlas.unlocked(config, map, completed(player))) {
            return map.name() + " 還沒在星圖上解鎖：先完成跟它相連的地圖";
        }
        String problem = capacityProblem();
        if (problem == null) {
            problem = endgameProblem(player);
        }
        if (problem != null) {
            return problem;
        }
        // 先拿走地圖（背包那一格減一）
        final ItemStack one = stack.asOne();
        if (stack.getAmount() > 1) {
            stack.setAmount(stack.getAmount() - 1);
        } else {
            player.getInventory().setItem(slot, null);
        }
        final MapStats stats = MapStats.of(plugin.items().repository(), data.get(), config.tiers());
        try {
            final MapInstance opened = open(player, map, stats);
            final UUID ownerId = player.getUniqueId();
            opened.paidRefund(() -> giveBackLater(ownerId, one, "地圖"));
        } catch (final RuntimeException e) {
            // 2026-10：生成地圖出錯（設定有問題）：地圖還給他，不會不見
            plugin.getSLF4JLogger().error("開地圖 {} 失敗，地圖還給 {}", map.id(), player.getName(), e);
            plugin.economy().wallet().dropFor(player, plugin.economy().wallet().give(player, one));
            return "這張地圖開不起來（設定有問題，已經記在主控台）；地圖還給你了";
        }
        return null;
    }

    /** 主要終局開不了的原因（{@link #finalProblem(Player, MapConfig.MapDef)}）。 */
    public String finalProblem(final Player player) {
        return finalProblem(player, config.finalMap());
    }

    /**
     * 某個終局開不了的原因（不看階級；null = 可以開）：沒設定、沒角色、最外圈還沒全部完成、要先打贏的終局還沒打、碎片不夠。
     */
    public String finalProblem(final Player player, final MapConfig.MapDef map) {
        if (map == null) {
            return "maps.yml 沒有設定終局";
        }
        if (!plugin.players().get(player).hasCharacter()) {
            return "先建立角色";
        }
        final Set<String> completed = completed(player);
        if (!Atlas.finalUnlocked(config, completed)) {
            return "先完成星圖最外圈的地圖（" + ringNames(config.maxRing()) + "）";
        }
        if (!Atlas.finalRequirementsMet(config, map, completed)) {
            return "要先打贏 " + Atlas.missingRequirements(config, map, completed) + " 才能開" + map.name();
        }
        if (config.isEventPinnacle(map.id())) {
            return map.name() + "不用天穹碎片開：用入場券在地圖裝置的「事件巔峰」按鈕開"; // 2026-10-18
        }
        if (config.pinnacle(map.id()) != null) {
            return map.name() + "不用天穹碎片開：集齊碎片後用地圖裝置的「巔峰」按鈕";
        }
        final CurrencyType fragment = plugin.currencies().repository().get(config.drops().fragment());
        if (fragment == null) {
            return "currencies.yml 沒有 " + config.drops().fragment();
        }
        final int cost = config.drops().fragmentCost();
        if (countCurrency(player, fragment) < cost) {
            return "需要 " + cost + " 片" + fragment.name() + "（" + fragmentSources() + " 的 Boss 掉落）";
        }
        return null;
    }

    /** 某一圈的地圖名稱（「觀星台、星落凍原」）。 */
    String ringNames(final int ring) {
        final List<String> names = new ArrayList<>();
        for (final MapConfig.MapDef map : config.ofRing(ring)) {
            names.add(map.name());
        }
        return String.join("、", names);
    }

    /** 掉天穹碎片的地圖名稱。 */
    String fragmentSources() {
        final List<String> names = new ArrayList<>();
        for (final String id : config.drops().fragmentMaps()) {
            final MapConfig.MapDef map = config.find(id);
            names.add(map == null ? id : map.name());
        }
        return names.isEmpty() ? "地圖" : String.join("、", names);
    }

    /** 開主要的終局（{@link #activateFinal(Player, MapConfig.MapDef, int)}）。 */
    public String activateFinal(final Player player, final int tier) {
        return activateFinal(player, config.finalMap(), tier);
    }

    /**
     * 開終局：要兩片天穹碎片（背包或隨身背包的通貨袋）。階級最高到完成過的最高階（終局 Boss 跟著變強、掉落跟著變好）。
     * 哪一個終局都一樣（maps.yml 的 final 與 finals）。
     */
    public String activateFinal(final Player player, final MapConfig.MapDef map, final int tier) {
        final String blocked = finalProblem(player, map);
        if (blocked != null) {
            return blocked;
        }
        final int best = Math.max(1, bestTier(player));
        if (tier < 1 || tier > best) {
            return "終局最高只能開到 T" + best + "（你完成過的最高階）";
        }
        String problem = capacityProblem();
        if (problem == null) {
            problem = endgameProblem(player);
        }
        if (problem != null) {
            return problem;
        }
        final CurrencyType fragment = plugin.currencies().repository().get(config.drops().fragment());
        takeCurrency(player, fragment, config.drops().fragmentCost());
        final UUID ownerId = player.getUniqueId();
        final Runnable refund = () -> refundLater(ownerId, fragment, config.drops().fragmentCost());
        try {
            open(player, map, MapStats.forTier(config.tiers(), tier, config.finalLevelBonus(map.id()))).paidRefund(refund);
        } catch (final RuntimeException e) {
            plugin.getSLF4JLogger().error("開終局 {} 失敗，碎片還給 {}", map.id(), player.getName(), e);
            refund.run();
            return "這張終局開不起來（設定有問題，已經記在主控台）；碎片還給你了";
        }
        return null;
    }

    // ---- 極境（Uber） ----

    /** 下一個 open 要不要做成極境（只在 {@link #activateUber} 裡設一下）。 */
    private java.util.Optional<MapConfig.Uber> pendingUber = java.util.Optional.empty();

    /**
     * 某個終局的極境開不了的原因（null = 可以開）：這個終局沒有極境、還沒打贏過一般版本、key 不夠、地圖世界滿了。
     */
    public String uberProblem(final Player player, final MapConfig.MapDef map) {
        if (map == null || !map.isFinal()) {
            return "這不是終局";
        }
        final MapConfig.Uber uber = config.uber(map.id());
        if (uber == null) {
            return map.name() + " 沒有極境版本";
        }
        if (!plugin.players().get(player).hasCharacter()) {
            return "先建立角色";
        }
        if (!completed(player).contains(map.id())) {
            return "要先打贏一般的" + map.boss().name() + "（" + map.name() + "）";
        }
        final CurrencyType key = plugin.currencies().repository().get(uber.key());
        if (key == null) {
            return "currencies.yml 沒有 " + uber.key();
        }
        if (countCurrency(player, key) < uber.keyCost()) {
            return "需要 " + uber.keyCost() + " 個" + key.name() + "（一般的終局 Boss 開到 T" + uber.keyMinTier() + " 以上才會掉）";
        }
        return null;
    }

    /**
     * 開極境：用掉 key，開在終局的最高階＋極境的等級加成；Boss 換成極境的版本，怪物的招式冷卻縮短、照設定加屬性。
     * 每個人只能走進去一次（死了或回營地就不能再進）。
     */
    public String activateUber(final Player player, final MapConfig.MapDef map) {
        final String problem = uberProblem(player, map);
        if (problem != null) {
            return problem;
        }
        String capacity = capacityProblem();
        if (capacity == null) {
            capacity = endgameProblem(player);
        }
        if (capacity != null) {
            return capacity;
        }
        final MapConfig.Uber uber = config.uber(map.id());
        final CurrencyType uberKey = plugin.currencies().repository().get(uber.key());
        takeCurrency(player, uberKey, uber.keyCost());
        final UUID ownerId = player.getUniqueId();
        final Runnable refund = () -> refundLater(ownerId, uberKey, uber.keyCost());
        pendingUber = java.util.Optional.of(uber);
        try {
            open(player, map, uberStats(map, uber), UBER_ROOMS, null).paidRefund(refund); // 一進去一間，下一間就是 Boss 房
        } catch (final RuntimeException e) {
            plugin.getSLF4JLogger().error("開極境 {} 失敗，鑰匙還給 {}", map.id(), player.getName(), e);
            refund.run();
            return "極境開不起來（設定有問題，已經記在主控台）；鑰匙還給你了";
        } finally {
            pendingUber = java.util.Optional.empty();
        }
        return null;
    }

    // ---- 巔峰（2026-10：集齊碎片才能開一次的最終 Boss） ----

    /** 下一個 open 要不要做成巔峰（只在 {@link #activatePinnacle} 裡設一下）。 */
    private java.util.Optional<MapConfig.Pinnacle> pendingPinnacle = java.util.Optional.empty();

    /** 這個人少哪些碎片（通貨代號）：集齊了是空的。 */
    public List<CurrencyType> missingFragments(final Player player, final MapConfig.Pinnacle pinnacle) {
        final List<CurrencyType> missing = new ArrayList<>();
        for (final String currency : pinnacle.costs()) { // 2026-10-18：事件巔峰是那一張入場券
            final CurrencyType type = plugin.currencies().repository().get(currency);
            if (type != null && countCurrency(player, type) < 1) {
                missing.add(type);
            }
        }
        return missing;
    }

    /** 某個巔峰開不了的原因（null = 可以開）：不是巔峰、沒角色、要先打贏的終局還沒打、碎片沒集齊、地圖世界滿了。 */
    public String pinnacleProblem(final Player player, final MapConfig.MapDef map) {
        final MapConfig.Pinnacle pinnacle = map == null ? null : config.pinnacle(map.id());
        if (pinnacle == null) {
            return "這不是巔峰";
        }
        if (!plugin.players().get(player).hasCharacter()) {
            return "先建立角色";
        }
        final Set<String> completed = completed(player);
        if (pinnacle.ticket() != null && !Atlas.finalUnlocked(config, completed)) { // 2026-10-18：事件巔峰要先解鎖終局（擁有者：不能只買一張券就開）
            return "先完成星圖最外圈的地圖（" + ringNames(config.maxRing()) + "）才能開事件巔峰";
        }
        if (!Atlas.finalRequirementsMet(config, map, completed)) {
            return "要先打贏 " + Atlas.missingRequirements(config, map, completed) + " 才能開" + map.name();
        }
        for (final String currency : pinnacle.costs()) { // 2026-10-18：事件巔峰是那一張入場券
            if (plugin.currencies().repository().get(currency) == null) {
                return "currencies.yml 沒有 " + currency;
            }
        }
        final List<CurrencyType> missing = missingFragments(player, pinnacle);
        if (!missing.isEmpty()) {
            final List<String> names = new ArrayList<>();
            for (final CurrencyType type : missing) {
                names.add(type.name());
            }
            if (pinnacle.ticket() != null) {
                // 2026-10-18：事件巔峰：要一張入場券
                return "需要 1 張" + String.join("、", names) + "（" + fragmentSourceText(pinnacle) + "）";
            }
            return "碎片還沒集齊：缺 " + String.join("、", names) + "（" + fragmentSourceText(pinnacle) + "）";
        }
        return null;
    }

    /**
     * 2026-10-18：事件巔峰的入場券由哪一種地圖事件掉（map_events.yml 的 ticket）：回傳事件的來源代號（shrine、ritual…），
     * 沒有事件掉這張券回傳 null。
     */
    public String ticketEventSource(final String currency) {
        return eventConfig.ticketSource(currency);
    }

    /** 2026-10-18：「完成神龕事件時有份的人各 2% 掉落」。 */
    String ticketSourceText(final String currency) {
        final String source = ticketEventSource(currency);
        if (source == null) {
            return "地圖事件完成時有份的人有機率掉";
        }
        final MapEventConfig.Ticket ticket = eventConfig.ticket(source);
        return "完成" + MapEventConfig.sourceName(source) + "事件時有份的人各 " + MapDeviceMenu.number(ticket.chance() * 100) + "% 掉落";
    }

    /** 「天穹熔爐、永凍王座、冥河渡口、餘燼堡壘的 Boss 各有 10% 機率掉」這種說明。 */
    String fragmentSourceText(final MapConfig.Pinnacle pinnacle) {
        if (pinnacle.ticket() != null) {
            return ticketSourceText(pinnacle.ticket()); // 2026-10-18：事件巔峰
        }
        final List<String> names = new ArrayList<>();
        int minTier = Integer.MAX_VALUE;
        double chance = 0;
        for (final MapConfig.FragmentSource fragment : pinnacle.fragments()) {
            final MapConfig.MapDef from = config.find(fragment.from());
            names.add(from == null ? fragment.from() : from.name());
            minTier = Math.min(minTier, fragment.minTier());
            chance = Math.max(chance, fragment.chance());
        }
        return String.join("、", names) + (minTier > 1 ? "開到 T" + minTier + " 以上" : "") + "的 Boss 各有 "
                + Math.round(chance * 100) + "% 機率掉";
    }

    /**
     * 開巔峰：用掉每一種碎片各一個，開在終局的最高階＋等級加成；只有 Boss 房，每個人只能走進去一次。
     */
    public String activatePinnacle(final Player player, final MapConfig.MapDef map) {
        final String problem = pinnacleProblem(player, map);
        if (problem != null) {
            return problem;
        }
        String capacity = capacityProblem();
        if (capacity == null) {
            capacity = endgameProblem(player);
        }
        if (capacity != null) {
            return capacity;
        }
        final MapConfig.Pinnacle pinnacle = config.pinnacle(map.id());
        for (final String currency : pinnacle.costs()) { // 2026-10-18：事件巔峰吃 1 張入場券（流水帳 MAP_DEVICE，跟碎片一樣）
            takeCurrency(player, plugin.currencies().repository().get(currency), 1);
        }
        final UUID ownerId = player.getUniqueId();
        final Runnable refund = () -> {
            for (final String currency : pinnacle.costs()) {
                refundLater(ownerId, plugin.currencies().repository().get(currency), 1);
            }
        };
        pendingPinnacle = java.util.Optional.of(pinnacle);
        try {
            open(player, map, config.pinnacleStats(map.id(), pinnacle), UBER_ROOMS, null).paidRefund(refund); // 一進去一間，下一間就是 Boss 房
        } catch (final RuntimeException e) {
            plugin.getSLF4JLogger().error("開巔峰 {} 失敗，碎片還給 {}", map.id(), player.getName(), e);
            refund.run();
            return "巔峰開不起來（設定有問題，已經記在主控台）；" + (pinnacle.ticket() != null ? "入場券" : "碎片") + "還給你了";
        } finally {
            pendingPinnacle = java.util.Optional.empty();
        }
        return null;
    }

    /** 巔峰的 Boss 生不出來（設定有問題）：把開門用掉的碎片還給開的人（不在線就記在主控台）。 */
    void refundPinnacleFragments(final MapInstance instance) {
        instance.paidRefund(null); // 退過了：重開伺服器時不要再退一次
        final MapConfig.Pinnacle pinnacle = instance.pinnacle();
        final Player owner = pinnacle == null ? null : plugin.getServer().getPlayer(instance.owner());
        if (owner == null) {
            plugin.getSLF4JLogger().warn("巔峰 {} 的 Boss 生不出來，開的人 {} 不在線上，碎片沒有退還", instance.map().id(), instance.ownerName());
            return;
        }
        for (final String currency : pinnacle.costs()) { // 2026-10-18：事件巔峰還那一張入場券
            final CurrencyType type = plugin.currencies().repository().get(currency);
            if (type != null) {
                refundCurrency(owner, type, plugin.currencies().create(type, 1));
            }
        }
        owner.sendMessage(plugin.text().prefixed("<gray>還給你開" + ItemRenderer.escape(instance.map().name()) + "用掉的"
                + (pinnacle.ticket() != null ? "入場券" : "碎片")));
    }

    /** 極境的地圖數值（{@link MapConfig#uberStats}）。 */
    MapStats uberStats(final MapConfig.MapDef map, final MapConfig.Uber uber) {
        return config.uberStats(map.id(), uber);
    }

    /**
     * 2026-10：你（隊伍）還有一張花了碎片、鑰匙開的終局、極境、巔峰、劫盜終局沒打完（還有門、Boss 還沒倒）：
     * 不能開新的——開新的會把那張的傳送門關掉，碎片、鑰匙就白花了（隊友亂開也一樣）。
     */
    private String endgameProblem(final Player player) {
        for (final MapInstance old : instances.values()) {
            if (old.state() != MapInstance.State.CLOSING && old.portalsLeft() > 0 && !old.bossKilled()
                    && (old.usesAttempts() || old.singleEntry()) && canEnter(player, old)) {
                return "你" + (plugin.party() != null && plugin.party().groupMembers(player.getUniqueId()).size() > 1 ? "的隊伍" : "")
                        + "的「" + old.label() + "」還沒打完（還剩 " + old.portalsLeft() + " 門）：開新的會把它關掉、碎片或鑰匙就白花了；"
                        + "先打完或等它關閉";
            }
        }
        return null;
    }

    private String capacityProblem() {
        if (world() == null) {
            return "地圖世界沒有載入（看主控台）";
        }
        if (instances.size() >= config.settings().instanceCap()) {
            return "同時開著的地圖已經到上限（" + config.settings().instanceCap() + "），等別人的地圖關掉";
        }
        if (freeCell() < 0) {
            return "地圖世界的格子正在清理，等一下再開";
        }
        return null;
    }

    /**
     * 開一個特別的實例（每週回響、昇華試煉）：不用地圖物品、不看星圖。成功回傳 null。
     *
     * @param rooms 主路大約幾個房間（5–12）
     */
    public String openSpecial(final Player owner, final MapConfig.MapDef map, final MapStats stats, final int rooms, final Special special) {
        String problem = capacityProblem();
        if (problem == null) {
            problem = endgameProblem(owner); // 2026-10-17：開回響、試煉也會關掉隊伍沒打完的終局門（碎片白花）
        }
        if (problem != null) {
            return problem;
        }
        open(owner, map, stats, rooms, special);
        return null;
    }

    /**
     * 同 {@link #openSpecial}，但回傳開出來的實例（劫盜要記著它；開不了回傳 null，原因用 {@link #specialProblem()} 問）。
     */
    public MapInstance openSpecialInstance(final Player owner, final MapConfig.MapDef map, final MapStats stats, final int rooms,
                                           final Special special) {
        String problem = capacityProblem();
        if (problem == null) {
            problem = endgameProblem(owner); // 2026-10-17：劫盜也一樣，不能把隊伍沒打完的終局門關掉
        }
        lastSpecialProblem = problem;
        if (problem != null) {
            return null;
        }
        return open(owner, map, stats, rooms, special);
    }

    /** 上一次 openSpecialInstance 開不了的原因（主執行緒用）。 */
    private String lastSpecialProblem;

    /** 現在開不了特別的實例的原因（實例太多、隊伍還有沒打完的終局）；可以開回傳 null。 */
    public String specialProblem() {
        return lastSpecialProblem != null ? lastSpecialProblem : capacityProblem();
    }

    /**
     * 實例蓋好（OPEN）之後把這些人直接送進去（劫盜的下一區：打完一區直接進下一區，不用回營地）。
     * 蓋太久（1 分鐘）、實例被關掉就放棄。
     */
    public void sendInWhenOpen(final MapInstance instance, final java.util.Collection<UUID> players) {
        final List<UUID> list = List.copyOf(players);
        final long deadline = plugin.getServer().getCurrentTick() + 60 * 20;
        final org.bukkit.scheduler.BukkitTask[] task = new org.bukkit.scheduler.BukkitTask[1];
        task[0] = plugin.getServer().getScheduler().runTaskTimer(plugin, () -> {
            if (!instances.containsKey(instance.id()) || plugin.getServer().getCurrentTick() > deadline) {
                task[0].cancel();
                return;
            }
            if (instance.state() != MapInstance.State.OPEN) {
                return;
            }
            task[0].cancel();
            for (final UUID uuid : list) {
                final Player player = plugin.getServer().getPlayer(uuid);
                if (player != null && !player.isDead() && canEnter(player, instance)) {
                    enter(player, instance);
                }
            }
        }, 10, 10);
    }

    /**
     * 別的模組在實例裡加一隻這張地圖的怪（劫盜封鎖時的增援）：算是這個實例的怪（掉落、擊殺），關閉時一起移除。
     *
     * @return 刷出來的怪；刷不出來回傳 null
     */
    public LivingEntity spawnExtra(final MapInstance instance, final Location at, final tw.exilecore.monster.MonsterRarity rarity) {
        final List<ZoneConfig.SpawnEntry> monsters = instance.map().monsters();
        if (monsters.isEmpty() || instance.state() != MapInstance.State.OPEN) {
            return null;
        }
        int total = 0;
        for (final ZoneConfig.SpawnEntry entry : monsters) {
            total += entry.weight();
        }
        int pick = random().nextInt(Math.max(1, total));
        ZoneConfig.SpawnEntry chosen = monsters.getLast();
        for (final ZoneConfig.SpawnEntry entry : monsters) {
            pick -= entry.weight();
            if (pick < 0) {
                chosen = entry;
                break;
            }
        }
        final LivingEntity entity = spawner.spawn(instance, chosen, at, rarity, null);
        if (entity != null) {
            instance.extras().add(entity);
        }
        return entity;
    }

    /** 版面大小照星圖的圈數（不照階級：同一張地圖每一階的大小差不多）。 */
    private MapInstance open(final Player owner, final MapConfig.MapDef map, final MapStats stats) {
        return open(owner, map, stats, map.isFinal() ? 6 : 6 + Math.min(5, map.ring()), null);
    }

    private MapInstance open(final Player owner, final MapConfig.MapDef map, final MapStats stats, final int rooms, final Special special) {
        // 同一個隊伍（或沒隊伍的一個人）只有一張目前的地圖：舊的那張收掉傳送門（裡面的人可以打完再出來）
        for (final MapInstance old : instances.values()) {
            if (old.state() != MapInstance.State.CLOSING && old.portalsLeft() > 0 && canEnter(owner, old)) {
                old.closePortals();
                for (final Player member : groupOnline(old)) {
                    member.sendMessage(plugin.text().prefixed("<gray>" + ItemRenderer.escape(owner.getName()) + " 開了新的地圖，"
                            + ItemRenderer.escape(old.label()) + " 的傳送門關閉了<dark_gray>（還在裡面的人可以打完再出來）"));
                }
            }
        }
        final World world = world();
        final int cell = freeCell();
        final int[] origin = origin(cell);
        final MapLayout layout = MapLayout.generate(random().nextLong(), rooms, map.layout());
        // 星圖天賦：開圖的人點的（特別的實例、極境不吃）；地圖本身的部分加進數值，其他的存在實例上
        final AtlasBonus atlas = special != null || pendingUber.isPresent() || pendingPinnacle.isPresent() ? AtlasBonus.NONE : atlasBonus(owner);
        // 2026-10：終局、極境、巔峰用「門」算（一道門 = 一次挑戰，預設 2 道；巔峰照 maps.yml 的 attempts）
        final boolean heistFinale = special != null && "heist_finale".equals(special.kind()); // 金庫之主也是 2 門
        final int portals = map.isFinal() && special == null || heistFinale
                ? pendingPinnacle.map(MapConfig.Pinnacle::attempts).orElse(BOSS_ATTEMPTS) : config.settings().portals();
        final MapInstance instance = new MapInstance(nextId++, map, stats.plus(atlas), layout, world, cell, origin[0], origin[1],
                owner.getUniqueId(), owner.getName(), portals, plugin.getServer().getCurrentTick());
        instance.setSpecial(special);
        instance.setAtlas(atlas);
        pendingUber.ifPresent(uber -> instance.setUber(uber, uber.merge(map.boss())));
        pendingPinnacle.ifPresent(instance::setPinnacle);
        instances.put(instance.id(), instance);
        cells.put(cell, instance.id());
        dirty.add(cell);
        saveCells();
        owner.sendMessage(plugin.text().prefixed("<gray>地圖裝置亮了起來：<white>" + ItemRenderer.escape(instance.label())
                + "<gray>（怪物等級 " + instance.level() + "）正在成形……"));
        if (!atlas.isEmpty()) {
            owner.sendMessage(plugin.text().parse("<gray>  星圖天賦：<white>" + ItemRenderer.escape(atlasSummary(atlas, 3))));
        }
        // 2026-10：算方塊（幾十萬格）、排序在背景執行緒做（以前在主執行緒，每開一張圖卡一下）；只碰 Palette（有鎖），不碰 Bukkit
        final Runnable whenBuilt = () -> {
            if (!instances.containsKey(instance.id())) {
                return; // 蓋的時候被關掉了
            }
            instance.setState(MapInstance.State.OPEN);
            guarded("事件", () -> events.place(instance)); // 神龕、寶箱、裂隙、流寇
            if (instance.portalsLeft() <= 0) {
                return; // 蓋的時候又開了新的地圖：這張已經進不去了（沒人之後會自己關掉）
            }
            if (campaign() == null) {
                // 沒有劇情世界的傳送門：直接送開圖的人進去
                final Player player = plugin.getServer().getPlayer(instance.owner());
                if (player != null) {
                    enter(player, instance);
                }
                return;
            }
            for (final Player member : groupOnline(instance)) {
                member.sendMessage(plugin.text().prefixed("<green>傳送門開好了：<white>" + ItemRenderer.escape(instance.label())
                        + "<green>（" + ItemRenderer.escape(instance.ownerName()) + " 開的，" + (instance.usesAttempts()
                        ? "共 " + instance.portalsLeft() + " 門，一門一次挑戰" : instance.singleEntry()
                        ? "每個人只能進入 1 次" : "可以進入 " + instance.portalsLeft() + " 次，同一波進去的隊友只算 1 次")
                        + "）<gray>——地圖裝置後面的門亮了，只有你" + (plugin.party() != null && plugin.party().inParty(member) ? "和隊友" : "")
                        + "看得到"));
                member.playSound(member.getLocation(), Sound.BLOCK_END_PORTAL_SPAWN, 0.6f, 1.2f);
            }
        };
        final var theme = map.theme();
        java.util.concurrent.CompletableFuture.supplyAsync(() -> {
            final BufferSink sink = new BufferSink();
            MapBuilder.build(layout, theme, origin[0], origin[1], sink);
            sink.compact();
            return sink;
        }).whenComplete((sink, error) -> {
            if (!plugin.isEnabled()) {
                return;
            }
            plugin.getServer().getScheduler().runTask(plugin, () -> {
                if (!instances.containsKey(instance.id())) {
                    return;
                }
                if (error != null || sink == null) {
                    plugin.getSLF4JLogger().error("蓋地圖 {} 失敗", instance.label(), error);
                    final boolean refunded = instance.refundPaid(); // 2026-10-17：背景蓋圖失敗也要退（以前只有同步出錯才退）
                    close(instance, "地圖蓋不出來（設定有問題，已經記在主控台）" + (refunded ? "；開門用掉的東西退還了" : ""));
                    return;
                }
                works.buildCompacted(world, sink, origin[0] + layout.minX(), origin[1] + layout.minZ(), origin[0] + layout.maxX(),
                        origin[1] + layout.maxZ(), whenBuilt);
            });
        });
        return instance;
    }

    /** 終局、極境預設幾道門（2026-10；巔峰照 maps.yml 的 attempts）。 */
    static final int BOSS_ATTEMPTS = 2;

    /** 用門算的實例：還剩幾道門、這一門進行中的說明（訊息用）。 */
    static String attemptText(final MapInstance instance) {
        return instance.attemptActive()
                ? "第 " + instance.attemptsUsed() + " 門挑戰中・之後還剩 " + Math.max(0, instance.portalsLeft() - 1) + " 門"
                : "還剩 " + instance.portalsLeft() + " 門（一門一次挑戰）";
    }

    /** 照波次扣的實例（一般地圖）：還能進入幾次、這一波進行中的說明（訊息用）。 */
    static String waveText(final MapInstance instance) {
        return instance.attemptActive()
                ? "這一波進行中・之後還能進入 " + Math.max(0, instance.portalsLeft() - 1) + " 次"
                : "還能進入 " + instance.portalsLeft() + " 次（同一波進去的隊友只算 1 次）";
    }

    /** 只能進一次的實例叫什麼（訊息用）。 */
    private static String singleEntryName(final MapInstance instance) {
        return instance.isUber() ? "極境" : instance.isPinnacle() ? "巔峰" : "金庫之主";
    }

    /** 這個玩家能不能走進這張地圖的傳送門：自己開的，或隊友開的。 */
    public boolean canEnter(final Player player, final MapInstance instance) {
        final UUID uuid = player.getUniqueId();
        if (instance.owner().equals(uuid)) {
            return true;
        }
        return plugin.party() != null && plugin.party().sameGroup(uuid, instance.owner());
    }

    /** 這個玩家的「目前的地圖」：自己或隊友開的、傳送門還開著的最新一張；沒有回傳 null。 */
    public MapInstance target(final Player player) {
        MapInstance best = null;
        for (final MapInstance instance : instances.values()) {
            if (instance.state() == MapInstance.State.CLOSING || instance.portalsLeft() <= 0 || !canEnter(player, instance)) {
                continue;
            }
            if (best == null || instance.id() > best.id()) {
                best = instance;
            }
        }
        return best;
    }

    /** 能走進這張地圖的在線玩家（開圖的人與他現在的隊友）。 */
    private List<Player> groupOnline(final MapInstance instance) {
        final List<Player> result = new ArrayList<>();
        for (final Player player : plugin.getServer().getOnlinePlayers()) {
            if (canEnter(player, instance)) {
                result.add(player);
            }
        }
        return result;
    }

    private int freeCell() {
        for (int cell = 0; cell < config.settings().instanceCap() + 16; cell++) {
            if (!cells.containsKey(cell) && !dirty.contains(cell)) {
                return cell;
            }
        }
        return -1;
    }

    private World campaign() {
        final CampaignWorld worlds = plugin.world() == null ? null : plugin.world().worlds();
        return worlds == null ? null : worlds.campaign();
    }

    /** 營地的地圖裝置前面（回來的落點）；沒有劇情世界就用營地的落點。 */
    public Location deviceReturn() {
        final World campaign = campaign();
        if (campaign != null) {
            final CampaignLayout.Spot spot = CampaignStructures.deviceReturn();
            return new Location(campaign, spot.x(), spot.y(), spot.z(), spot.yaw(), 0f);
        }
        return plugin.zones() == null ? null : plugin.zones().campLocation();
    }

    // ---- 進出 ----

    /** 走進傳送門（用掉一次）。 */
    void enter(final Player player, final MapInstance instance) {
        if (instance.state() != MapInstance.State.OPEN || instance.portalsLeft() <= 0) {
            return;
        }
        if (instance.usesAttempts()) {
            // 2026-10：門（一門一次挑戰）。這一門進行中：還沒進來過的隊友可以跟進，死了或出去的要等全部倒下再走下一門
            if (instance.attemptActive()) {
                if (instance.enteredThisAttempt(player.getUniqueId())) {
                    gateCooldown.put(player.getUniqueId(), (long) plugin.getServer().getCurrentTick());
                    player.sendActionBar(plugin.text().parse("<red>這一門你已經出來了<gray>：等裡面的人全部倒下（或出來），再走下一門"));
                    return;
                }
            } else if (instance.bossKilled()) {
                return; // 打倒了：門都關了
            } else if (instance.startAttempt()) {
                bosses.resetForAttempt(instance); // 第二門以後：Boss 回滿、從第一階段重來
                for (final Player member : groupOnline(instance)) {
                    member.sendMessage(plugin.text().prefixed("<#f5b942>" + ItemRenderer.escape(instance.label()) + "：第 "
                            + instance.attemptsUsed() + " 門開始<gray>（Boss 回滿、從頭打起；這是最後 " + instance.portalsLeft() + " 門）"));
                }
            }
            instance.markAttemptEntry(player.getUniqueId());
        } else if (instance.usesWaves()) {
            // 2026-10：一般地圖照波次扣：同一波第一次進去的隊友不扣；這一波的人全部倒下（或出來）才扣 1 次
            if (!instance.attemptActive()) {
                instance.startAttempt();
            } else if (instance.enteredThisAttempt(player.getUniqueId())) {
                if (instance.portalsLeft() < 2) {
                    gateCooldown.put(player.getUniqueId(), (long) plugin.getServer().getCurrentTick());
                    player.sendActionBar(plugin.text().parse("<red>次數只剩隊友正在打的這一波了<gray>：同一波死了或出來再進去要多扣 1 次"));
                    return;
                }
                instance.usePortal(); // 同一波裡死了、回營地再進去：照舊扣 1 次
            }
            instance.markAttemptEntry(player.getUniqueId());
        } else if (instance.singleEntry() && instance.uberEntered(player.getUniqueId())) {
            // 極境、劫盜終局每個人只能進一次：死了或回營地就出局
            gateCooldown.put(player.getUniqueId(), (long) plugin.getServer().getCurrentTick());
            player.sendActionBar(plugin.text().parse("<red>" + singleEntryName(instance) + "每個人只能進去一次（死了或離開就不能再進）"));
            return;
        }
        if (instance.singleEntry()) {
            instance.markUberEntry(player.getUniqueId());
        }
        if (!instance.usesAttempts() && !instance.usesWaves()) {
            instance.usePortal(); // 用門、波次算的在結束的時候才扣
        }
        instance.visitors().add(player.getUniqueId());
        remember(player, instance);
        final Location target = instance.at(instance.layout().entrance(), -90f);
        player.teleport(target);
        gateCooldown.put(player.getUniqueId(), (long) plugin.getServer().getCurrentTick());
        plugin.players().markDirty(player); // 地圖詞綴（玩家的抗性、回復）
        plugin.players().grace().grant(player); // 入場保護：移動前不會受傷
        player.showTitle(Title.title(plugin.text().parse("<#9fd3ff>" + ItemRenderer.escape(instance.label())),
                plugin.text().parse("<gray>怪物等級 " + instance.level() + (instance.usesAttempts() ? "・<#f5b942>第 " + instance.attemptsUsed() + " 門"
                        : instance.singleEntry() ? "・<red>只能進來這一次"
                        : "・" + waveText(instance)) + "<dark_gray>・移動前不會受傷"),
                Title.Times.times(Duration.ofMillis(300), Duration.ofMillis(2200), Duration.ofMillis(600))));
        if (!instance.map().lore().isBlank()) {
            player.sendMessage(plugin.text().parse(Zone.loreLine(null, instance.map().lore())));
        }
        player.playSound(target, Sound.BLOCK_PORTAL_TRAVEL, 0.35f, 1.4f);
    }

    /** 記住這個玩家進的是哪個實例（/town 回來、重新登入時對照）。 */
    private void remember(final Player player, final MapInstance instance) {
        player.getPersistentDataContainer().set(tokenKey, PersistentDataType.STRING, instance.token().toString());
    }

    /** 這個玩家可以待在（回到）這個實例嗎：要是他最後進的那一個。 */
    boolean belongs(final Player player, final MapInstance instance) {
        return instance != null && instance.state() == MapInstance.State.OPEN
                && instance.token().toString().equals(player.getPersistentDataContainer().get(tokenKey, PersistentDataType.STRING));
    }

    /** 送回營地（地圖裝置前面）。 */
    public void sendToCamp(final Player player) {
        final Location target = deviceReturn();
        if (target != null) {
            player.teleport(target);
        }
        gateCooldown.put(player.getUniqueId(), (long) plugin.getServer().getCurrentTick());
        plugin.players().markDirty(player);
    }

    // ---- 每 tick ----

    void tick() {
        ticks++;
        guarded("施工", works::tick);
        guarded("Boss 紀錄", bossLog::tick);
        if (ticks % 20 == 0) {
            guarded("實例", this::lifecycle);
            guarded("觀戰", spectators::tick);
            guarded("傳送門文字", this::updateLabels);
        }
        if (ticks % 4 == 0) {
            guarded("傳送門", this::checkPortals);
        }
        if (ticks % 10 == 0) {
            guarded("刷怪", spawner::tick);
            guarded("Boss", bosses::tick);
            guarded("事件", events::tick);
        }
        if (ticks % 10 == 5) {
            guarded("傳送門粒子", this::gateParticles);
        }
        if (ticks % 40 == 20) {
            guarded("跑出去的怪", this::recallEscaped);
        }
        if (ticks % 20 == 10) {
            guarded("卡住的怪", this::unstick);
        }
        if (ticks % 10 == 3) {
            guarded("卡在牆裡的怪", this::unembed);
        }
    }

    /** 卡住偵測（2026-10）：每隻追著玩家的地圖怪上次檢查時的位置與連續卡住的次數。 */
    private final Map<UUID, double[]> stuck = new HashMap<>();

    /**
     * 卡在柱子、道具後面的怪（2026-10）：追著玩家、離玩家 3 格以上，1 秒內幾乎沒動，而且看不到玩家（或是近戰怪）——
     * 第一次重新找路；連續第二次往側面挪一步（找得到地板的那一邊）再找路。Boss、護盾結晶、放招中、凍結中的不管。
     */
    private void unstick() {
        final World maps = world();
        if (maps == null || instances.isEmpty()) {
            stuck.clear();
            return;
        }
        final Set<UUID> seen = new HashSet<>();
        for (final org.bukkit.entity.Mob mob : maps.getEntitiesByClass(org.bukkit.entity.Mob.class)) {
            if (!mob.isValid() || mob.isDead() || !(mob.getTarget() instanceof Player target) || !target.getWorld().equals(maps)) {
                continue;
            }
            if (mob.getPersistentDataContainer().has(bossKey()) || !mob.hasAI()) {
                continue;
            }
            final MapInstance instance = instanceOf(mob);
            if (instance == null || instance.shieldCrystals().contains(mob.getUniqueId())) {
                continue;
            }
            if (plugin.skills() != null && plugin.skills().bossSkills().casting(mob.getUniqueId())) {
                continue;
            }
            if (plugin.ailments() != null && plugin.ailments().isIncapacitated(mob)) {
                continue;
            }
            final Location at = mob.getLocation();
            final double distance = at.distance(target.getLocation());
            if (distance <= 3 || distance > 40) {
                continue;
            }
            seen.add(mob.getUniqueId());
            final double[] last = stuck.get(mob.getUniqueId());
            if (last == null) {
                stuck.put(mob.getUniqueId(), new double[] {at.getX(), at.getZ(), 0});
                continue;
            }
            final double moved = Math.hypot(at.getX() - last[0], at.getZ() - last[1]);
            last[0] = at.getX();
            last[1] = at.getZ();
            final boolean blocked = !mob.hasLineOfSight(target) || !(mob instanceof com.destroystokyo.paper.entity.RangedEntity);
            if (moved >= 0.4 || !blocked) {
                last[2] = 0;
                continue;
            }
            last[2]++;
            if (last[2] >= 2) {
                sidestep(instance, mob, target);
                last[2] = 0;
            }
            mob.getPathfinder().moveTo(target, 1.2);
        }
        stuck.keySet().retainAll(seen);
    }

    /** 卡在牆裡（身體跟方塊重疊）連續幾次檢查才挪出來（每 0.5 秒檢查一次；被推撞牆的那一下會自己彈開，不算）。 */
    private static final int EMBED_CHECKS = 2;
    /** 卡在牆裡的怪：連續幾次檢查都卡著。 */
    private final Map<UUID, Integer> embedded = new HashMap<>();

    /**
     * 卡在牆裡的地圖怪（2026-10-15）：碰撞箱跟方塊重疊（刷在柱子、道具上，被推、被傳送進牆裡，體型大的貼著牆刷出來…），
     * 不管有沒有在追人、是不是 Boss，連續 {@link #EMBED_CHECKS} 次都卡著就挪到附近最近的空地（{@link #freeSpot}）。
     */
    private void unembed() {
        final World maps = world();
        if (maps == null || instances.isEmpty()) {
            embedded.clear();
            return;
        }
        final Set<UUID> seen = new HashSet<>();
        for (final LivingEntity entity : maps.getLivingEntities()) {
            if (entity instanceof Player || !entity.isValid() || entity.isDead() || entity.isInsideVehicle()) {
                continue;
            }
            final MapInstance instance = instanceOf(entity);
            if (instance == null || !isEmbedded(entity)) {
                continue;
            }
            seen.add(entity.getUniqueId());
            final int count = embedded.merge(entity.getUniqueId(), 1, Integer::sum);
            if (count >= EMBED_CHECKS) {
                freeFromWalls(instance, entity);
                embedded.remove(entity.getUniqueId());
            }
        }
        embedded.keySet().retainAll(seen);
    }

    /**
     * 這隻的碰撞箱（稍微縮一點，貼著牆、站在地上的不算）有沒有跟方塊重疊。只看會動的怪（盔甲座、標籤不算）；
     * 惱鬼本來就穿牆，不算。
     */
    static boolean isEmbedded(final LivingEntity entity) {
        if (!(entity instanceof org.bukkit.entity.Mob) || entity instanceof org.bukkit.entity.Vex) {
            return false;
        }
        return entity.getWorld().hasCollisionsIn(entity.getBoundingBox().expand(-0.06));
    }

    /**
     * 卡在牆裡的話挪到附近最近的空地（地圖的地板上、整個碰撞箱放得下、腳下是實心的）；沒卡住、找不到空地都不動。
     *
     * @return 有沒有挪
     */
    boolean freeFromWalls(final MapInstance instance, final LivingEntity entity) {
        if (!isEmbedded(entity)) {
            return false;
        }
        final Location spot = freeSpot(instance, entity);
        if (spot == null) {
            return false;
        }
        entity.teleport(spot);
        return true;
    }

    /** 由近到遠（最遠 6 格）找一個放得下這隻的位置；找不到 null。 */
    private static Location freeSpot(final MapInstance instance, final LivingEntity entity) {
        final Location at = entity.getLocation();
        final org.bukkit.util.BoundingBox box = entity.getBoundingBox();
        final double halfX = box.getWidthX() / 2;
        final double halfZ = box.getWidthZ() / 2;
        final double height = box.getHeight();
        final int cx = at.getBlockX();
        final int cz = at.getBlockZ();
        Location best = null;
        double bestDistance = Double.MAX_VALUE;
        for (int radius = 0; radius <= 6 && best == null; radius++) {
            for (int dx = -radius; dx <= radius; dx++) {
                for (int dz = -radius; dz <= radius; dz++) {
                    if (Math.max(Math.abs(dx), Math.abs(dz)) != radius) {
                        continue; // 只看這一圈
                    }
                    final int bx = cx + dx;
                    final int bz = cz + dz;
                    if (!instance.layout().isFloor(bx - instance.originX(), bz - instance.originZ())) {
                        continue;
                    }
                    for (int up = 1; up <= 2; up++) { // 地板上一格；有台階、道具墊高的再上一格
                        final double y = MapLayout.FLOOR_Y + up;
                        final double x = bx + 0.5;
                        final double z = bz + 0.5;
                        final org.bukkit.util.BoundingBox there = new org.bukkit.util.BoundingBox(x - halfX, y, z - halfZ, x + halfX, y + height, z + halfZ)
                                .expand(-0.06);
                        if (entity.getWorld().hasCollisionsIn(there)) {
                            continue;
                        }
                        if (entity.getWorld().getBlockAt(bx, (int) y - 1, bz).isPassable()) {
                            continue; // 腳下要是實心的
                        }
                        final double distance = (x - at.getX()) * (x - at.getX()) + (z - at.getZ()) * (z - at.getZ());
                        if (distance < bestDistance) {
                            bestDistance = distance;
                            best = new Location(entity.getWorld(), x, y, z, at.getYaw(), at.getPitch());
                        }
                        break;
                    }
                }
            }
        }
        return best;
    }

    /** 往側面（左右，找不到再往後）挪 1.5 格：要是地圖裡的地板、身體兩格是空的。 */
    private void sidestep(final MapInstance instance, final org.bukkit.entity.Mob mob, final Player target) {
        final Location at = mob.getLocation();
        final org.bukkit.util.Vector toward = target.getLocation().toVector().subtract(at.toVector()).setY(0);
        if (toward.lengthSquared() < 1e-6) {
            return;
        }
        toward.normalize();
        final org.bukkit.util.Vector side = new org.bukkit.util.Vector(-toward.getZ(), 0, toward.getX());
        final boolean leftFirst = random().nextBoolean();
        final org.bukkit.util.Vector[] tries = {
                side.clone().multiply(leftFirst ? 1.5 : -1.5), side.clone().multiply(leftFirst ? -1.5 : 1.5),
                side.clone().multiply(leftFirst ? 1.2 : -1.2).add(toward.clone().multiply(-1.0)),
                side.clone().multiply(leftFirst ? -1.2 : 1.2).add(toward.clone().multiply(-1.0)),
        };
        for (final org.bukkit.util.Vector step : tries) {
            final Location spot = at.clone().add(step);
            spot.setY(MapLayout.FLOOR_Y + 1);
            if (!instance.layout().isFloor(spot.getBlockX() - instance.originX(), spot.getBlockZ() - instance.originZ())) {
                continue;
            }
            if (!spot.getBlock().isPassable() || !spot.clone().add(0, 1, 0).getBlock().isPassable()
                    || spot.clone().add(0, -1, 0).getBlock().isPassable()) {
                continue;
            }
            final org.bukkit.util.BoundingBox box = mob.getBoundingBox();
            if (mob.getWorld().hasCollisionsIn(box.clone().shift(spot.getX() - at.getX(), spot.getY() - at.getY(), spot.getZ() - at.getZ()).expand(-0.06))) {
                continue; // 體型大的：整個碰撞箱要放得下，不然挪過去就卡進牆裡
            }
            spot.setYaw(at.getYaw());
            spot.setPitch(at.getPitch());
            mob.teleport(spot);
            return;
        }
    }

    /**
     * 跑到地圖外面、屏障天花板上面的怪（終界使者傳送、被推出去、飛出去的）每 2 秒抓回來：
     * 腳下是地板就直接放回地板上，不然放到實例裡最近的玩家旁邊；實例裡沒人就移除（Boss 不移除）。
     */
    private void recallEscaped() {
        final World maps = world();
        if (maps == null || instances.isEmpty()) {
            return;
        }
        for (final LivingEntity entity : maps.getLivingEntities()) {
            if (entity instanceof Player || !entity.isValid() || entity.isDead()) {
                continue;
            }
            final MapInstance instance = instanceOf(entity);
            if (instance == null) {
                continue;
            }
            final Location at = entity.getLocation();
            if (instance.insideWalls(at)) {
                continue;
            }
            final Location floor = new Location(maps, at.getX(), MapLayout.FLOOR_Y + 1, at.getZ(), at.getYaw(), at.getPitch());
            if (instance.insideWalls(floor)) {
                entity.teleport(floor);
                freeFromWalls(instance, entity); // 放回去的地方是柱子、道具的話再挪到旁邊的空地
                continue;
            }
            Player nearest = null;
            double best = Double.MAX_VALUE;
            for (final UUID uuid : instance.inside()) {
                final Player player = plugin.getServer().getPlayer(uuid);
                if (player != null && player.getWorld().equals(maps) && !player.isDead()) {
                    final double distance = player.getLocation().distanceSquared(at);
                    if (distance < best) {
                        best = distance;
                        nearest = player;
                    }
                }
            }
            if (nearest != null) {
                entity.teleport(nearest.getLocation());
            } else if (entity != instance.boss()) {
                entity.remove();
            }
        }
    }

    /** 一個子系統出錯不會拖垮其他的（也不會每 tick 洗版：同一個子系統每分鐘最多記一次）。 */
    private void guarded(final String name, final Runnable task) {
        try {
            task.run();
        } catch (final RuntimeException e) {
            final long now = plugin.getServer().getCurrentTick();
            final Long last = errorLogged.get(name);
            if (last == null || now - last > 1200) {
                errorLogged.put(name, now);
                plugin.getSLF4JLogger().error("地圖的{}處理出錯", name, e);
            }
        }
    }

    /** 玩家走進營地的傳送門，或地圖裡的回程門。 */
    private void checkPortals() {
        final World campaign = campaign();
        final World maps = world();
        final long now = plugin.getServer().getCurrentTick();
        for (final Player player : plugin.getServer().getOnlinePlayers()) {
            if (player.isDead() || player.getGameMode() == GameMode.SPECTATOR) {
                continue;
            }
            final Long cooldown = gateCooldown.get(player.getUniqueId());
            if (cooldown != null && now - cooldown < GATE_COOLDOWN_TICKS) {
                continue;
            }
            final Location location = player.getLocation();
            if (campaign != null && location.getWorld().equals(campaign)) {
                final int gate = gateAt(location);
                if (gate >= 0) {
                    if (!plugin.players().get(player).hasCharacter()) {
                        continue;
                    }
                    // 每個人走進的是自己（或隊友）的地圖；還能進幾次就亮幾道門
                    final MapInstance instance = target(player);
                    if (instance == null) {
                        player.sendActionBar(plugin.text().parse("<gray>你" + (plugin.party() != null && plugin.party().inParty(player) ? "和隊友" : "")
                                + "沒有開著的地圖：先在地圖裝置開一張"));
                        continue;
                    }
                    if (instance.state() != MapInstance.State.OPEN) {
                        player.sendActionBar(plugin.text().parse("<gray>" + ItemRenderer.escape(instance.label()) + " 還在成形……"));
                        continue;
                    }
                    if (gate >= Math.min(CampaignStructures.GATE_COUNT, instance.portalsLeft())) {
                        player.sendActionBar(plugin.text().parse("<gray>這道門沒有亮：" + ItemRenderer.escape(instance.label())
                                + (instance.usesAttempts() ? " " + attemptText(instance) : instance.singleEntry() ? " 每個人只能進入 1 次"
                                : " " + waveText(instance)) + "，走進亮著的門"));
                        continue;
                    }
                    enter(player, instance);
                }
            } else if (maps != null && location.getWorld().equals(maps)) {
                final MapInstance instance = instanceAt(location);
                if (instance != null && inExitPortal(instance, location)) {
                    // 2026-10：極境、金庫之主走進出口門就直接離開（不再要求蹲下確認）
                    sendToCamp(player);
                    player.sendMessage(plugin.text().prefixed(instance.usesAttempts()
                            ? "<gray>離開了 " + ItemRenderer.escape(instance.label()) + "<dark_gray>（這一門不能再回去；裡面的人全部倒下或出來之後可以走下一門）"
                            : instance.singleEntry()
                            ? "<gray>離開了 " + ItemRenderer.escape(instance.label()) + "<dark_gray>（" + singleEntryName(instance) + "每個人只能進一次，不能再回去了）"
                            : "<gray>回到營地<dark_gray>（" + ItemRenderer.escape(instance.label())
                            + "：這一波的隊友全部出來或倒下才扣 1 次；同一波再進去會多扣 1 次）"));
                }
            }
        }
    }

    /** 營地第幾道傳送門的門洞（不在任何門洞回傳 −1）。 */
    private static int gateAt(final Location location) {
        final int bx = location.getBlockX();
        final int by = location.getBlockY();
        final int bz = location.getBlockZ();
        if (bz != CampaignStructures.GATE_Z || by < CampaignLayout.CAMP_Y + 1 || by > CampaignLayout.CAMP_Y + 4) {
            return -1;
        }
        for (int i = 0; i < CampaignStructures.GATE_COUNT; i++) {
            final int gx = (int) Math.floor(CampaignStructures.gate(i).x());
            if (bx >= gx - 1 && bx <= gx + 1) {
                return i;
            }
        }
        return -1;
    }

    private static boolean inExitPortal(final MapInstance instance, final Location location) {
        final MapLayout.Spot exit = instance.layout().exitPortal();
        final int x = location.getBlockX() - instance.originX();
        final int z = location.getBlockZ() - instance.originZ();
        final int y = location.getBlockY();
        return x == exit.x() && Math.abs(z - exit.z()) <= 1 && y >= MapLayout.FLOOR_Y + 1 && y <= MapLayout.FLOOR_Y + 4;
    }

    /** 這一門還有人在打嗎：這一門進去過、還在裡面、活著（倒地的、觀戰的不算）。 */
    private boolean challengerAlive(final MapInstance instance) {
        for (final UUID uuid : instance.inside()) {
            final Player player = plugin.getServer().getPlayer(uuid);
            if (player != null && !player.isDead() && instance.enteredThisAttempt(uuid)
                    && player.getGameMode() != GameMode.SPECTATOR && player.getGameMode() != GameMode.CREATIVE) {
                return true;
            }
        }
        return false;
    }

    /** 每秒：誰在哪個實例裡、要不要關閉。 */
    private void lifecycle() {
        final World maps = world();
        final long now = plugin.getServer().getCurrentTick();
        final Map<Integer, Set<UUID>> insideNow = new HashMap<>();
        if (maps != null) {
            for (final Player player : maps.getPlayers()) {
                final MapInstance instance = instanceAt(player.getLocation());
                final boolean staff = player.getGameMode() == GameMode.CREATIVE || player.getGameMode() == GameMode.SPECTATOR;
                if (instance != null && (staff || belongs(player, instance) || instance.state() == MapInstance.State.BUILDING)) {
                    if (!staff && !player.isDead() && instance.state() == MapInstance.State.OPEN && !canEnter(player, instance)) {
                        // 已經不是這張地圖的隊伍（被請出、自己離開隊伍）：給 30 秒離開，時間到送回營地
                        final Long deadline = evictAt.get(player.getUniqueId());
                        if (deadline == null) {
                            evictAt.put(player.getUniqueId(), now + EVICT_TICKS);
                            player.sendMessage(plugin.text().prefixed("<yellow>你已經不在開這張地圖的隊伍裡，" + EVICT_TICKS / 20
                                    + " 秒後會回到營地"));
                        } else if (now >= deadline) {
                            evictAt.remove(player.getUniqueId());
                            sendToCamp(player);
                            player.sendMessage(plugin.text().prefixed("<gray>回到營地（已經不在那張地圖的隊伍裡）"));
                            continue;
                        }
                    } else {
                        evictAt.remove(player.getUniqueId());
                    }
                    insideNow.computeIfAbsent(instance.id(), key -> new LinkedHashSet<>()).add(player.getUniqueId());
                } else if (!staff && !player.isDead()) {
                    // 在地圖世界、卻不在自己的實例裡（格子已經清掉、或重用給別人了）：送回營地
                    sendToCamp(player);
                }
            }
        }
        final MapConfig.Settings settings = config.settings();
        for (final MapInstance instance : List.copyOf(instances.values())) {
            final Set<UUID> before = Set.copyOf(instance.inside());
            instance.inside().clear();
            instance.inside().addAll(insideNow.getOrDefault(instance.id(), Set.of()));
            for (final UUID uuid : before) {
                if (!instance.inside().contains(uuid)) {
                    final Player left = plugin.getServer().getPlayer(uuid);
                    if (left != null) {
                        plugin.players().markDirty(left); // 離開地圖：地圖詞綴拿掉
                        bosses.hideFor(instance, left);
                    }
                }
            }
            for (final UUID uuid : instance.inside()) {
                if (!before.contains(uuid)) {
                    final Player joined = plugin.getServer().getPlayer(uuid);
                    if (joined != null) {
                        plugin.players().markDirty(joined); // 進地圖（含 /town 回來）：地圖詞綴加上
                    }
                }
            }
            if (instance.attemptActive() && !challengerAlive(instance)) {
                // 2026-10：這一門的人全部倒下（或出來）：這一門結束，扣一道門；打倒了就全部關掉
                // （一般地圖的「波次」也走這裡：這一波的人都出來了，扣 1 次，不用公告）
                instance.endAttempt();
                if (instance.usesWaves()) {
                    // 一般地圖：只扣次數
                } else if (instance.bossKilled()) {
                    instance.closePortals();
                } else {
                    for (final Player member : groupOnline(instance)) {
                        member.sendMessage(plugin.text().prefixed(instance.portalsLeft() > 0
                                ? "<yellow>" + ItemRenderer.escape(instance.label()) + "：這一門的挑戰失敗了<gray>——還剩 <white>"
                                + instance.portalsLeft() + "<gray> 門，走進亮著的門再挑戰一次（Boss 會回滿）"
                                : "<red>" + ItemRenderer.escape(instance.label()) + "：門都用完了，挑戰失敗"));
                    }
                }
            }
            if (instance.state() == MapInstance.State.BUILDING) {
                continue;
            }
            if (!instance.inside().isEmpty()) {
                instance.setLastOccupiedTick(now);
                instance.setEmptySinceTick(-1);
                continue;
            }
            if (instance.emptySinceTick() < 0) {
                instance.setEmptySinceTick(now);
            }
            final long empty = now - instance.emptySinceTick();
            final boolean exhausted = instance.portalsLeft() <= 0 && empty >= settings.emptyCloseSeconds() * 20L;
            final boolean idle = now - instance.lastOccupiedTick() >= settings.idleMinutes() * 1200L;
            final boolean finished = instance.bossKilled() && empty >= 5 * 1200L;
            if (exhausted || idle || finished) {
                close(instance, exhausted ? "次數用完" : finished ? "已完成" : "太久沒人");
            }
        }
    }

    /** 關閉一個實例：裡面的人送回營地、怪物移除、傳送門熄掉、格子排進清理。 */
    public void close(final MapInstance instance, final String reason) {
        if (!instances.containsKey(instance.id()) || instance.state() == MapInstance.State.CLOSING) {
            return;
        }
        instance.setState(MapInstance.State.CLOSING);
        dpsCap.forget(instance.id());
        for (final UUID uuid : Set.copyOf(instance.inside())) {
            final Player player = plugin.getServer().getPlayer(uuid);
            if (player != null) {
                sendToCamp(player);
                player.sendMessage(plugin.text().prefixed("<gray>" + ItemRenderer.escape(instance.label()) + " 關閉了（" + reason + "）"));
            }
        }
        bosses.end(instance);
        spawner.removeAll(instance);
        events.clear(instance);
        removeLabel(groupLabels.remove(instance.id()));
        instances.remove(instance.id());
        cells.put(instance.cell(), -1);
        final MapLayout layout = instance.layout();
        works.clear(instance.world(), instance.originX() + layout.minX() - 2, CLEAR_MIN_Y, instance.originZ() + layout.minZ() - 2,
                instance.originX() + layout.maxX() + 2, CLEAR_MAX_Y, instance.originZ() + layout.maxZ() + 2, () -> {
                    cells.remove(instance.cell());
                    dirty.remove(instance.cell());
                    saveCells();
                });
        plugin.getSLF4JLogger().info("地圖 #{} {}（{}）關閉：{}，擊殺 {}", instance.id(), instance.label(), instance.ownerName(), reason, instance.kills());
    }

    // ---- 傳送門的樣子 ----

    /**
     * 傳送門那一排上方正中間（門上的字放這裡）。字比兩根門柱之間的空隙寬，門柱前面吊燈的石頭（地面＋5 那一格，
     * 頂在＋6、前面在 GATE_Z＋2）會擋住兩邊：所以字的底邊高過石頭，前後放在石頭前面（站在走道上往上看，
     * 視線不會先碰到石頭；字會轉向看的人，斜著看時邊邊往後轉一點也還在石頭上面）。
     */
    static Location labelSpot(final World campaign) {
        return new Location(campaign, CampaignStructures.DEVICE_X + 0.5, CampaignLayout.CAMP_Y + 1 + LABEL_HEIGHT,
                CampaignStructures.GATE_Z + 2.5);
    }

    /**
     * 門上的字（每秒）：沒有地圖的人看到說明；有地圖的人只看到自己（隊伍）那張的狀態（別人的看不到）。
     * 用實體的「只給某些人看」：說明預設看得到、對有地圖的人藏起來；每張地圖的字預設看不到、只給那個隊伍看。
     */
    private void updateLabels() {
        final World campaign = campaign();
        if (campaign == null) {
            return;
        }
        final Location at = labelSpot(campaign);
        if (!campaign.isChunkLoaded(at.getBlockX() >> 4, at.getBlockZ() >> 4)) {
            return;
        }
        if (generalLabel == null || !generalLabel.isValid()) {
            generalLabel = spawnLabel(at, true);
            generalLabel.text(plugin.text().parse("<#9fd3ff>地圖傳送門<newline><gray>在地圖裝置開一張地圖，門就會亮起來"
                    + "<newline><dark_gray>每個人（隊伍）看到的是自己的地圖"));
        }
        keepAt(generalLabel, at);
        // 每張開著的地圖一個字（只有那個隊伍看得到）
        for (final MapInstance instance : instances.values()) {
            if (instance.state() == MapInstance.State.CLOSING || instance.portalsLeft() <= 0) {
                removeLabel(groupLabels.remove(instance.id()));
                continue;
            }
            TextDisplay label = groupLabels.get(instance.id());
            if (label == null || !label.isValid()) {
                label = spawnLabel(at, false);
                groupLabels.put(instance.id(), label);
            }
            keepAt(label, at);
            label.text(label(instance));
        }
        // 誰看得到哪一個
        for (final Player player : campaign.getPlayers()) {
            if (player.getLocation().distanceSquared(at) > 64 * 64) {
                continue;
            }
            final MapInstance target = target(player);
            setVisible(player, generalLabel, target == null);
            for (final Map.Entry<Integer, TextDisplay> entry : groupLabels.entrySet()) {
                setVisible(player, entry.getValue(), target != null && target.id() == entry.getKey());
            }
        }
    }

    /** 字不在該在的位置（更新外掛前生出來的，在舊的位置）就搬過去。 */
    private static void keepAt(final TextDisplay label, final Location at) {
        if (label.getLocation().distanceSquared(at) > 0.01) {
            label.teleport(at);
        }
    }

    private TextDisplay spawnLabel(final Location at, final boolean visibleByDefault) {
        return at.getWorld().spawn(at, TextDisplay.class, display -> {
            display.setBillboard(Display.Billboard.CENTER);
            display.setPersistent(false);
            display.setSeeThrough(false);
            display.setShadowed(true);
            display.setVisibleByDefault(visibleByDefault);
        });
    }

    private void setVisible(final Player player, final TextDisplay label, final boolean visible) {
        if (label == null || !label.isValid() || player.canSee(label) == visible) {
            return;
        }
        if (visible) {
            player.showEntity(plugin, label);
        } else {
            player.hideEntity(plugin, label);
        }
    }

    private Component label(final MapInstance instance) {
        final String state = instance.state() == MapInstance.State.BUILDING ? "<yellow>成形中……"
                : instance.usesAttempts() ? "<#f5b942>" + attemptText(instance)
                : instance.singleEntry() ? "<red>每個人只能進入 1 次<gray>（死了或離開就不能再進）"
                : "<green>" + waveText(instance) + "<gray>（亮著的門）";
        return plugin.text().parse("<#9fd3ff>" + ItemRenderer.escape(instance.label()) + "<newline>" + state
                + "<newline><dark_gray>" + ItemRenderer.escape(instance.ownerName()) + " 開的"
                + (instance.bossKilled() ? "・<#f5b942>已完成" : ""));
    }

    private void removeLabels() {
        removeLabel(generalLabel);
        generalLabel = null;
        for (final TextDisplay label : groupLabels.values()) {
            removeLabel(label);
        }
        groupLabels.clear();
    }

    /** 移除一個字，順便把每個玩家「看得到／看不到它」的紀錄清掉（不然會一直累積到下線）。 */
    private void removeLabel(final TextDisplay label) {
        if (label == null) {
            return;
        }
        if (!plugin.isEnabled()) {
            // 關伺服器時插件已經是停用狀態，不能再呼叫 show／hideEntity（會丟例外、後面的清理都沒做）：直接移除就好
            if (label.isValid()) {
                label.remove();
            }
            return;
        }
        for (final Player player : plugin.getServer().getOnlinePlayers()) {
            if (label.isVisibleByDefault()) {
                if (!player.canSee(label)) {
                    player.showEntity(plugin, label);
                }
            } else if (player.canSee(label)) {
                player.hideEntity(plugin, label);
            }
        }
        if (label.isValid()) {
            label.remove();
        }
    }

    /**
     * 門洞裡的粒子：每個人只看得到自己（隊伍）那張地圖的門，還能進幾次就亮幾道（從最左邊數）；成形中的是淡淡的煙。
     */
    private void gateParticles() {
        final World campaign = campaign();
        if (campaign == null) {
            return;
        }
        final Location labelAt = labelSpot(campaign);
        for (final Player player : campaign.getPlayers()) {
            if (player.getLocation().distanceSquared(labelAt) > 40 * 40) {
                continue;
            }
            final MapInstance instance = target(player);
            if (instance == null) {
                continue;
            }
            final boolean building = instance.state() != MapInstance.State.OPEN;
            final int lit = building ? CampaignStructures.GATE_COUNT : Math.min(CampaignStructures.GATE_COUNT, instance.portalsLeft());
            final Particle.DustOptions dust = new Particle.DustOptions(instance.special() != null ? Color.fromRGB(instance.special().color())
                    : instance.isUber() ? Color.fromRGB(0xff3b3b)
                    : instance.isPinnacle() ? Color.fromRGB(0xc77dff)
                    : instance.map().isFinal() ? Color.fromRGB(0xf5b942) : Color.fromRGB(0x9fd3ff), 1.1f);
            for (int i = 0; i < lit; i++) {
                final CampaignLayout.Spot spot = CampaignStructures.gate(i);
                final Location center = new Location(campaign, spot.x(), spot.y() + 1.6, spot.z());
                if (player.getLocation().distanceSquared(center) > 40 * 40) {
                    continue; // 太遠看不到，不送
                }
                if (building) {
                    player.spawnParticle(Particle.WHITE_ASH, center, 6, 0.8, 1.2, 0.2, 0);
                } else {
                    player.spawnParticle(Particle.PORTAL, center, 14, 0.8, 1.2, 0.2, 0.2);
                    player.spawnParticle(Particle.DUST, center, 6, 0.8, 1.2, 0.1, 0, dust);
                }
            }
        }
    }

    // ---- 死亡與登入 ----

    void markDeath(final Player player) {
        if (inMap(player)) {
            diedInMap.add(player.getUniqueId());
            spectators.died(player); // 只能進一次的實例：重生後在旁邊觀戰
        }
    }

    public MapSpectators spectators() {
        return spectators;
    }

    /** 重生點：在地圖裡死的回營地（地圖裝置前面）；其他的照原本。 */
    Location respawnFor(final Player player) {
        if (!diedInMap.remove(player.getUniqueId())) {
            return null;
        }
        final Location watch = spectators.respawn(player);
        if (watch != null) {
            return watch;
        }
        gateCooldown.put(player.getUniqueId(), (long) plugin.getServer().getCurrentTick());
        return deviceReturn();
    }

    void forget(final Player player) {
        gateCooldown.remove(player.getUniqueId());
        diedInMap.remove(player.getUniqueId());
        evictAt.remove(player.getUniqueId());
        spectators.forget(player);
    }

    // ---- 首殺與完成 ----

    /** Boss 倒下時離 Boss 幾格內的人才算數（掉落、完成地圖、擊殺稱號；2026-10）。 */
    public static final double BOSS_REWARD_RADIUS = 32;

    /**
     * Boss 倒下時這個人算不算（2026-10）：在線、活著、不是觀戰（死掉之後在旁邊看的）、在 Boss 的 {@link #BOSS_REWARD_RADIUS} 格內。
     */
    public boolean atBoss(final Player player, final Location boss) {
        if (player == null || !player.isOnline() || player.isDead() || player.getGameMode() == org.bukkit.GameMode.SPECTATOR
                || spectators.watching(player)) {
            return false;
        }
        return player.getWorld().equals(boss.getWorld())
                && player.getLocation().distanceSquared(boss) <= BOSS_REWARD_RADIUS * BOSS_REWARD_RADIUS;
    }

    /** Boss 生不出來直接算完成：在裡面的人都算。 */
    void complete(final MapInstance instance) {
        complete(instance, null);
    }

    /**
     * 地圖完成（Boss 死亡）：Boss 旁邊 {@link #BOSS_REWARD_RADIUS} 格內、活著、不是觀戰的人星圖點亮，第一次完成給獎勵
     * （回響、試煉、劫盜終局的獎勵也一樣只給這些人）。boss 是 null（Boss 生不出來）時在裡面的人都算。
     */
    void complete(final MapInstance instance, final Location boss) {
        final MapConfig.MapDef map = instance.map();
        final Set<UUID> players = new LinkedHashSet<>(instance.inside());
        for (final UUID uuid : instance.visitors()) {
            final Player player = plugin.getServer().getPlayer(uuid);
            if (player != null && instance.contains(player.getLocation())) {
                players.add(uuid);
            }
        }
        if (boss != null) {
            players.removeIf(uuid -> !atBoss(plugin.getServer().getPlayer(uuid), boss));
        }
        if ((instance.singleEntry() || instance.isUber() || instance.isPinnacle()) && plugin.discord() != null && !players.isEmpty()) {
            // 2026-10：極境 Boss、金庫之主打倒了：同步到 Discord
            final List<String> names = new ArrayList<>();
            for (final UUID uuid : players) {
                final Player player = plugin.getServer().getPlayer(uuid);
                if (player != null) {
                    names.add(plugin.chat() == null ? player.getName() : plugin.chat().display(player));
                }
            }
            if (!names.isEmpty()) {
                plugin.discord().achievement(tw.exilecore.item.ItemRenderer.escape(String.join("、", names)) + " 打倒了 "
                        + tw.exilecore.item.ItemRenderer.escape(instance.bossDef() == null ? instance.label() : instance.bossDef().name())
                        + "（" + tw.exilecore.item.ItemRenderer.escape(instance.label()) + "）！");
            }
        }
        if (instance.special() != null) {
            // 特別的實例（回響、試煉）：不點亮星圖，交給開它的模組
            final List<Player> online = new ArrayList<>();
            for (final UUID uuid : players) {
                final Player player = plugin.getServer().getPlayer(uuid);
                if (player != null && plugin.players().get(player).hasCharacter()) {
                    online.add(player);
                }
            }
            try {
                instance.special().onComplete().accept(instance, online);
            } catch (final RuntimeException e) {
                plugin.getSLF4JLogger().error("{}（{}）完成時的處理出錯", instance.label(), instance.special().kind(), e);
            }
            return;
        }
        for (final UUID uuid : players) {
            final Player player = plugin.getServer().getPlayer(uuid);
            if (player == null || !plugin.players().get(player).hasCharacter()) {
                continue;
            }
            final int pointsBefore = atlasEarned(player).total();
            completeFor(player, instance);
            announceAtlasPoints(player, pointsBefore); // 星圖天賦點：新的地圖、終局、極境、最高階里程碑
        }
    }

    /** 一個在裡面的人完成這張地圖：委託、每月挑戰；星圖點亮、首殺獎勵、最高完成階級（極境另外算）。 */
    private void completeFor(final Player player, final MapInstance instance) {
        final MapConfig.MapDef map = instance.map();
        final PlayerState state = plugin.players().get(player);
        if (plugin.bounties() != null) {
            plugin.bounties().onMapComplete(player, instance.level()); // 每日委託：完成地圖
        }
        if (plugin.events() != null) {
            plugin.events().onMapComplete(player, instance.level()); // 每月挑戰：完成地圖
        }
        if (instance.isUber()) {
            completeUber(player, instance);
            return;
        }
        if (config.isEventPinnacle(map.id())) {
            completeEventPinnacle(player, instance); // 2026-10-18：事件巔峰不在星圖上：只記打贏過、大字標題
            return;
        }
        final Set<String> before = completed(player);
        final boolean newBest = recordBest(player, before, map, instance.stats().tier());
        final boolean first = !before.contains(map.id());
        if (!first) {
            player.sendMessage(plugin.text().prefixed("<gray>完成 " + ItemRenderer.escape(instance.label())
                    + (newBest ? "<gray>・<#f5b942>最高完成階級 T" + instance.stats().tier()
                    + "<gray>（終局可以開到這一階）" : "")));
            return;
        }
        plugin.characters().setProgress(player, Atlas.key(map.id()), String.valueOf(System.currentTimeMillis() / 1000));
        final int points = map.isFinal() ? config.finalPoints(map.id()) : map.boss().named()
                ? config.firstKill().namedPoints() : config.firstKill().mapPoints();
        final CharacterRecord character = state.character();
        if (points > 0 && character != null) {
            character.setBonusPoints(character.bonusPoints() + points);
            state.markDirty();
        }
        plugin.characters().save(player);
        final Set<String> after = new LinkedHashSet<>(before);
        after.add(map.id());
        final List<String> unlocked = new ArrayList<>();
        for (final MapConfig.MapDef other : config.all()) {
            if (config.isEventPinnacle(other.id())) {
                continue; // 2026-10-18：事件巔峰不在星圖上，不算「解鎖了」
            }
            if (!Atlas.unlocked(config, other, before) && Atlas.unlocked(config, other, after)) {
                unlocked.add(other.name());
            }
        }
        player.showTitle(Title.title(plugin.text().parse("<#f5b942>星圖點亮：" + ItemRenderer.escape(instance.label())),
                plugin.text().parse(points > 0 ? "<white>首殺・天賦點 +" + points : "<gray>" + (unlocked.isEmpty() ? "" : "解鎖 " + String.join("、", unlocked))),
                Title.Times.times(Duration.ofMillis(300), Duration.ofMillis(3200), Duration.ofMillis(800))));
        player.playSound(player.getLocation(), Sound.UI_TOAST_CHALLENGE_COMPLETE, 0.8f, 1.0f);
        player.sendMessage(plugin.text().prefixed("<#f5b942>星圖點亮：" + ItemRenderer.escape(instance.label())
                + (points > 0 ? "<gray>，天賦點 +" + points + "（可用 <white>" + plugin.players().availablePoints(state) + "<gray>）" : "")));
        if (!unlocked.isEmpty()) {
            player.sendMessage(plugin.text().parse("<gray>  解鎖了：<white>" + ItemRenderer.escape(String.join("、", unlocked))
                    + "<gray>（/atlas 看星圖）"));
        }
        final int capBefore = Atlas.dropTierCap(config, Atlas.completedMaps(config, before));
        final int capAfter = Atlas.dropTierCap(config, Atlas.completedMaps(config, after));
        if (capAfter > capBefore) {
            player.sendMessage(plugin.text().parse("<gray>  掉落的地圖最高 <white>T" + capAfter + "<gray>（星圖每完成一張不同的地圖，高一階）"));
        }
        if (newBest && !map.isFinal()) {
            player.sendMessage(plugin.text().parse("<gray>  最高完成階級 <#f5b942>T" + instance.stats().tier() + "<gray>（終局可以開到這一階）"));
        }
        if (plugin.quests() != null) {
            plugin.quests().refresh(player); // 任務：完成地圖
        }
    }

    /**
     * 2026-10-18：打贏事件巔峰：不點亮星圖、不動最高完成階級、不算星圖天賦的終局點數（它不在星圖上，requires 空、points 0）；
     * 第一次打贏記在進度裡（{@link Atlas#key}，之後的稱號、/map 清單看這個）、大字標題。
     */
    private void completeEventPinnacle(final Player player, final MapInstance instance) {
        final String key = Atlas.key(instance.map().id());
        final boolean first = !plugin.players().get(player).progress().containsKey(key);
        final String name = ItemRenderer.escape(instance.bossDef() == null ? instance.map().boss().name() : instance.bossDef().name());
        if (first) {
            plugin.characters().setProgress(player, key, String.valueOf(System.currentTimeMillis() / 1000));
            plugin.characters().save(player);
        }
        player.showTitle(Title.title(plugin.text().parse("<#c77dff>" + (first ? "巔峰首殺" : "巔峰擊破")),
                plugin.text().parse("<#af6025>" + name),
                Title.Times.times(Duration.ofMillis(300), Duration.ofMillis(3600), Duration.ofMillis(800))));
        player.playSound(player.getLocation(), Sound.UI_TOAST_CHALLENGE_COMPLETE, 0.9f, 0.9f);
        player.sendMessage(plugin.text().prefixed("<#c77dff>" + (first ? "第一次" : "") + "打倒了事件巔峰的 <#af6025>" + name
                + "<gray>（" + ItemRenderer.escape(instance.map().name()) + "）"));
        if (plugin.quests() != null) {
            plugin.quests().refresh(player);
        }
    }

    /** 極境的 Boss 生不出來（設定有問題）：把開門用掉的鑰匙還給開的人（不在線就記在主控台）。 */
    void refundUberKey(final MapInstance instance) {
        instance.paidRefund(null); // 退過了：重開伺服器時不要再退一次
        final MapConfig.Uber uber = instance.uber();
        final CurrencyType key = uber == null ? null : plugin.currencies().repository().get(uber.key());
        final Player owner = plugin.getServer().getPlayer(instance.owner());
        if (key == null || owner == null) {
            plugin.getSLF4JLogger().warn("{} 的 Boss 生不出來，鑰匙沒辦法還給 {}（不在線或通貨不存在）", instance.label(), instance.ownerName());
            return;
        }
        refundCurrency(owner, key, plugin.currencies().create(key, uber.keyCost()));
        owner.sendMessage(plugin.text().prefixed("<gray>還給你 " + uber.keyCost() + " 個" + ItemRenderer.escape(key.name())));
    }

    /**
     * 退還通貨（2026-10）：先進通貨袋、背包、隨身背包；以前放不下的掉在腳下——人在地圖裡的話，地圖關掉時會被清掉。
     * 還是放不下的：記到營地回來的地方（{@link #deviceReturn}）掉給他，不在地圖世界裡。
     */
    /** 記下這個實例開門花掉的通貨（劫盜終局用）：蓋圖失敗、重開伺服器時沒打完就退。 */
    public void refundIfUnfinished(final MapInstance instance, final Player owner, final CurrencyType type, final int amount) {
        final UUID ownerId = owner.getUniqueId();
        instance.paidRefund(() -> refundLater(ownerId, type, amount));
    }

    /** 退通貨給某個人（2026-10-17）：在線就退，不在線記在主控台（方便手動補）。 */
    void refundLater(final UUID ownerId, final CurrencyType type, final int amount) { // 2026-10-18：MapEvents 退保險箱的合約也用這個
        final Player owner = plugin.getServer().getPlayer(ownerId);
        if (type == null || amount <= 0) {
            return;
        }
        if (owner == null) {
            plugin.getSLF4JLogger().warn("要退 {} 個 {} 給 {}，但他不在線上：請手動補", amount, type.id(), ownerId);
            return;
        }
        refundCurrency(owner, type, plugin.currencies().create(type, amount));
        owner.sendMessage(plugin.text().prefixed("<gray>開門用掉的 " + amount + " 個" + ItemRenderer.escape(type.name()) + "退還給你了"));
    }

    /** 退物品（地圖）給某個人：在線就給（放不下掉腳下），不在線記在主控台。 */
    private void giveBackLater(final UUID ownerId, final ItemStack item, final String what) {
        final Player owner = plugin.getServer().getPlayer(ownerId);
        if (owner == null) {
            plugin.getSLF4JLogger().warn("要退{}給 {}，但他不在線上：請手動補（{}）", what, ownerId, item.getType());
            return;
        }
        plugin.economy().wallet().dropFor(owner, plugin.economy().wallet().give(owner, item));
        owner.sendMessage(plugin.text().prefixed("<gray>開門用掉的" + what + "退還給你了"));
    }

    private void refundCurrency(final Player owner, final CurrencyType type, final ItemStack stack) {
        ItemStack rest = plugin.loot() != null ? plugin.loot().stowCurrency(owner, plugin.players().get(owner), type, stack)
                : plugin.economy().wallet().give(owner, stack);
        if (rest != null && !rest.isEmpty()) {
            final Location safe = deviceReturn();
            if (safe != null) {
                safe.getWorld().dropItem(safe, rest, item -> {
                    item.setOwner(owner.getUniqueId());
                    item.setUnlimitedLifetime(true);
                });
                owner.sendMessage(plugin.text().prefixed("<yellow>放不下的退還放在地圖裝置旁邊（只有你撿得到）"));
            } else {
                plugin.personalDrops().dropForever(owner, rest);
            }
        }
    }

    /** 管理指令：清掉打贏過極境的紀錄。 */
    public void resetUber(final Player player) {
        for (final MapConfig.MapDef map : config.finals()) {
            plugin.characters().removeProgress(player, uberKey(map.id()));
        }
    }

    /** 進度裡記「打贏過這個極境」的鍵（之後的稱號、成就看這個）。 */
    public static String uberKey(final String finalId) {
        return "uber." + finalId;
    }

    /** 打贏過哪些極境（終局代號）。 */
    public Set<String> uberDefeated(final Player player) {
        final Map<String, String> progress = plugin.players().get(player).progress();
        final Set<String> defeated = new LinkedHashSet<>();
        for (final MapConfig.MapDef map : config.finals()) {
            if (progress.containsKey(uberKey(map.id()))) {
                defeated.add(map.id());
            }
        }
        return defeated;
    }

    /** 打贏極境：不動星圖與最高完成階級（一般版本早就打贏了），第一次打贏記下來、大字標題。 */
    private void completeUber(final Player player, final MapInstance instance) {
        final String key = uberKey(instance.map().id());
        final boolean first = !plugin.players().get(player).progress().containsKey(key);
        final String name = ItemRenderer.escape(instance.bossDef().name());
        if (first) {
            plugin.characters().setProgress(player, key, String.valueOf(System.currentTimeMillis() / 1000));
            plugin.characters().save(player);
        }
        player.showTitle(Title.title(plugin.text().parse("<#ff5f5f>" + (first ? "極境首殺" : "極境擊破")),
                plugin.text().parse("<#af6025>" + name),
                Title.Times.times(Duration.ofMillis(300), Duration.ofMillis(3600), Duration.ofMillis(800))));
        player.playSound(player.getLocation(), Sound.UI_TOAST_CHALLENGE_COMPLETE, 0.9f, 0.8f);
        player.sendMessage(plugin.text().prefixed("<#ff5f5f>" + (first ? "第一次" : "") + "打倒了極境的 <#af6025>" + name
                + "<gray>（打贏過的極境 " + uberDefeated(player).size() + " / " + countUbers() + "）"));
        if (plugin.quests() != null) {
            plugin.quests().refresh(player);
        }
    }

    /** 有幾個終局有極境版本。 */
    int countUbers() {
        int count = 0;
        for (final MapConfig.MapDef map : config.finals()) {
            if (config.uber(map.id()) != null) {
                count++;
            }
        }
        return count;
    }

    // ---- 星圖天賦 ----

    /** 這個角色拿到的星圖天賦點（完成過的地圖、終局、極境，最高完成階級的里程碑）。 */
    public AtlasTree.Earned atlasEarned(final Player player) {
        final Map<String, String> progress = plugin.players().get(player).progress();
        final Set<String> completed = Atlas.completed(config, progress);
        int finals = 0;
        for (final MapConfig.MapDef map : config.finals()) {
            if (completed.contains(map.id()) && !config.isEventPinnacle(map.id())) { // 2026-10-18：事件巔峰不算星圖的終局點數
                finals++;
            }
        }
        return tree.earned(Atlas.completedMaps(config, completed), finals, uberDefeated(player).size(),
                Atlas.bestTier(config, progress, completed));
    }

    /** 這個角色有效的星圖天賦（照點的順序；點數不夠的從最後點的開始不算）。 */
    public List<String> atlasAllocated(final Player player) {
        final String stored = plugin.players().get(player).progress().get(AtlasTree.PROGRESS_KEY);
        return tree.effective(AtlasTree.parseAllocation(stored), atlasEarned(player).total());
    }

    /** 還沒用的星圖天賦點。 */
    public int atlasUnspent(final Player player) {
        return Math.max(0, atlasEarned(player).total() - tree.spent(atlasAllocated(player)));
    }

    /** 這個角色點的星圖天賦的效果（他開的地圖吃這個）。 */
    public AtlasBonus atlasBonus(final Player player) {
        return plugin.players().get(player).hasCharacter() ? tree.bonus(atlasAllocated(player)) : AtlasBonus.NONE;
    }

    /** 點一個星圖天賦；不能點回傳原因（給玩家看），點了回傳 null。 */
    public String allocateAtlas(final Player player, final String id) {
        if (!plugin.players().get(player).hasCharacter()) {
            return "先建立角色";
        }
        final List<String> allocated = atlasAllocated(player);
        final String problem = tree.allocateProblem(allocated, id, atlasEarned(player).total());
        if (problem != null) {
            return problem;
        }
        final List<String> next = new ArrayList<>(allocated);
        next.add(id);
        if (AtlasTree.joinAllocation(next).length() > AtlasTree.MAX_STORED_LENGTH) {
            plugin.getSLF4JLogger().warn("{} 的星圖天賦存不下（超過 {} 字）：把 atlas_tree.yml 的天賦代號改短", player.getName(), AtlasTree.MAX_STORED_LENGTH);
            return "存不下更多天賦了（請管理員把 atlas_tree.yml 的天賦代號改短）";
        }
        saveAtlas(player, next);
        return null;
    }

    /** 退掉一個星圖天賦（同一條下面點了的一起退，免費）；回傳退掉的（空的 = 沒點這個）。 */
    public List<String> refundAtlas(final Player player, final String id) {
        final List<String> allocated = atlasAllocated(player);
        final List<String> removed = tree.refund(allocated, id);
        if (!removed.isEmpty()) {
            final List<String> next = new ArrayList<>(allocated);
            next.removeAll(removed);
            saveAtlas(player, next);
        }
        return removed;
    }

    /** 全部退回（免費）；回傳退了幾個。 */
    public int resetAtlas(final Player player) {
        final int count = atlasAllocated(player).size();
        plugin.characters().removeProgress(player, AtlasTree.PROGRESS_KEY);
        return count;
    }

    private void saveAtlas(final Player player, final List<String> allocated) {
        if (allocated.isEmpty()) {
            plugin.characters().removeProgress(player, AtlasTree.PROGRESS_KEY);
        } else {
            plugin.characters().setProgress(player, AtlasTree.PROGRESS_KEY, AtlasTree.joinAllocation(allocated));
        }
    }

    /** 星圖天賦效果的短摘要（「地圖的物品數量 +10%、…等 5 項」）；沒有回傳空字串。 */
    static String atlasSummary(final AtlasBonus bonus, final int limit) {
        final List<String> lines = bonus.describe();
        if (lines.size() <= limit) {
            return String.join("、", lines);
        }
        return String.join("、", lines.subList(0, limit)) + " 等 " + lines.size() + " 項";
    }

    /** 這次完成之後星圖天賦點變多了：說一聲。 */
    private void announceAtlasPoints(final Player player, final int before) {
        final int gained = atlasEarned(player).total() - before;
        if (gained <= 0) {
            return;
        }
        player.sendMessage(plugin.text().parse("<#9fd3ff>  星圖天賦點 +" + gained + "<gray>（還沒用的 <white>" + atlasUnspent(player)
                + "<gray>，<white>/atlas tree<gray> 打開星圖天賦）"));
    }

    // ---- 通貨 ----

    /** 背包＋通貨袋＋倉庫的通貨頁裡的某種通貨（2026-10：倉庫也算）。 */
    public int countCurrency(final Player player, final CurrencyType type) {
        int total = plugin.players().get(player).bag().pouch().amount(type.id());
        final tw.exilecore.stash.Stash stash = stashOf(player);
        if (stash != null) {
            total += stash.currency(type.id());
        }
        for (final ItemStack stack : player.getInventory().getStorageContents()) {
            if (stack != null && plugin.currencies().read(stack).map(found -> found.id().equals(type.id())).orElse(false)) {
                total += stack.getAmount();
            }
        }
        return total;
    }

    /** 先拿背包裡的，再拿通貨袋、倉庫的（流水帳記在地圖裝置）。 */
    void takeCurrency(final Player player, final CurrencyType type, final int amount) {
        takeCurrency(player, type, amount, tw.exilecore.economy.EconomyLedger.MAP_DEVICE);
    }

    /** 這個人的倉庫（還沒載入、沒有倉庫模組是 null）。 */
    private tw.exilecore.stash.Stash stashOf(final Player player) {
        return plugin.stash() == null ? null : plugin.stash().stash(player).orElse(null);
    }

    /** 先拿背包裡的，再拿通貨袋的，最後拿倉庫的；source 是流水帳的來源（EconomySources），null 不記（已經在別處記過）。 */
    public void takeCurrency(final Player player, final CurrencyType type, final int amount, final String source) {
        int left = amount;
        final ItemStack[] contents = player.getInventory().getStorageContents();
        for (int i = 0; i < contents.length && left > 0; i++) {
            final ItemStack stack = contents[i];
            if (stack == null || !plugin.currencies().read(stack).map(found -> found.id().equals(type.id())).orElse(false)) {
                continue;
            }
            final int take = Math.min(left, stack.getAmount());
            stack.setAmount(stack.getAmount() - take);
            player.getInventory().setItem(i, stack.getAmount() <= 0 ? null : stack);
            left -= take;
        }
        if (left > 0) {
            left -= plugin.players().get(player).bag().pouch().withdraw(type.id(), left);
        }
        final tw.exilecore.stash.Stash stash = left > 0 ? stashOf(player) : null;
        if (stash != null) {
            final int take = Math.min(left, stash.currency(type.id()));
            stash.setCurrency(type.id(), stash.currency(type.id()) - take); // 倉庫跟角色一起存（下面的 save）
            left -= take;
        }
        if (source != null) { // 2026-10-18：null＝呼叫的地方已經記過（星盤刻刀在 currencies().apply 記了做裝備、地圖）
            plugin.economy().ledger().consumed(type.id(), source, amount);
        }
        plugin.characters().save(player);
    }

    /** 管理指令：直接開一張圖（不用物品、不看星圖）。 */
    public String adminOpen(final Player player, final MapConfig.MapDef map, final MapStats stats) {
        final String problem = capacityProblem();
        if (problem != null) {
            return problem;
        }
        open(player, map, stats);
        return null;
    }

    /** 管理指令：直接開某個終局的極境（不用鑰匙、不用先打贏一般版本）。 */
    /** 管理指令：直接開巔峰（不用碎片、不用先打贏）。 */
    public String adminOpenPinnacle(final Player player, final MapConfig.MapDef map) {
        final MapConfig.Pinnacle pinnacle = map == null ? null : config.pinnacle(map.id());
        if (pinnacle == null) {
            return (map == null ? "這個終局" : map.name()) + " 不是巔峰";
        }
        final String problem = capacityProblem();
        if (problem != null) {
            return problem;
        }
        pendingPinnacle = java.util.Optional.of(pinnacle);
        try {
            open(player, map, config.pinnacleStats(map.id(), pinnacle), UBER_ROOMS, null);
        } finally {
            pendingPinnacle = java.util.Optional.empty();
        }
        return null;
    }

    public String adminOpenUber(final Player player, final MapConfig.MapDef map) {
        final MapConfig.Uber uber = map == null ? null : config.uber(map.id());
        if (uber == null) {
            return (map == null ? "這個終局" : map.name()) + " 沒有極境版本";
        }
        final String problem = capacityProblem();
        if (problem != null) {
            return problem;
        }
        pendingUber = java.util.Optional.of(uber);
        try {
            open(player, map, uberStats(map, uber), UBER_ROOMS, null);
        } finally {
            pendingUber = java.util.Optional.empty();
        }
        return null;
    }

    /** 管理指令：直接進入（不用傳送門、不扣次數）。 */
    public void adminEnter(final Player player, final MapInstance instance) {
        instance.visitors().add(player.getUniqueId());
        remember(player, instance);
        player.teleport(instance.at(instance.layout().entrance(), -90f));
        plugin.players().markDirty(player);
        plugin.players().grace().grant(player);
    }

    /**
     * 記下完成過的最高階（一般地圖才算；終局只能開到已經完成過的階級）。回傳是不是新高。
     *
     * @param before 這次完成前的星圖（算舊版本留下來的最高階用）
     */
    private boolean recordBest(final Player player, final Set<String> before, final MapConfig.MapDef map, final int tier) {
        if (map.isFinal() || tier <= 0) {
            return false;
        }
        final int best = Atlas.bestTier(config, plugin.players().get(player).progress(), before);
        if (tier <= best) {
            return false;
        }
        plugin.characters().setProgress(player, Atlas.BEST_KEY, String.valueOf(tier));
        return true;
    }

    /** 管理指令：完成過的最高階（0 = 清掉）。 */
    public void setBest(final Player player, final int tier) {
        if (tier <= 0) {
            plugin.characters().removeProgress(player, Atlas.BEST_KEY);
        } else {
            plugin.characters().setProgress(player, Atlas.BEST_KEY, String.valueOf(Math.min(config.tiers().max(), tier)));
        }
    }

    /** 管理指令：星圖進度。 */
    public void setCompleted(final Player player, final MapConfig.MapDef map, final boolean completed) {
        if (completed) {
            plugin.characters().setProgress(player, Atlas.key(map.id()), String.valueOf(System.currentTimeMillis() / 1000));
        } else {
            plugin.characters().removeProgress(player, Atlas.key(map.id()));
        }
        if (plugin.quests() != null) {
            plugin.quests().refresh(player);
        }
    }

    /** 給怪物加上實例標記（刷怪、召喚時用）。 */
    void tag(final LivingEntity entity, final MapInstance instance, final boolean boss) {
        entity.getPersistentDataContainer().set(instanceKey, PersistentDataType.INTEGER, instance.id());
        if (boss) {
            entity.getPersistentDataContainer().set(bossKey, PersistentDataType.BYTE, (byte) 1);
        }
    }

    MapSpawner spawner() {
        return spawner;
    }
}
