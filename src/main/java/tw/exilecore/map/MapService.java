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
import java.util.Iterator;
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
    /** 星圖的生成規則（atlas.yml，2026-10-21）。 */
    private final AtlasConfig atlasConfig = new AtlasConfig();
    /** 每個角色的星圖進度（照存的字串快取；存的字串變了就重讀）。 */
    private final Map<UUID, CachedAtlas> atlasCache = new HashMap<>();

    private record CachedAtlas(long seed, String cells, AtlasProgress progress) {
    }
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
    /** 門上的說明字：每條線的營地世界一個（2026-10-22 主城分流；世界名稱 → 字）。 */
    private final Map<String, TextDisplay> generalLabels = new HashMap<>();
    /** 每張開著的地圖的字：每條線一個（"實例編號@世界名稱" → 字）。 */
    private final Map<String, TextDisplay> groupLabels = new HashMap<>();
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
            atlasConfig.load(plugin.dataFolder().resolve("data")); // 2026-10-21：星圖的生成規則
            atlasCache.clear();
        } catch (final Exception e) {
            plugin.getSLF4JLogger().error("atlas.yml 讀取失敗，星圖的生成規則照上一次讀到的", e);
        }
        for (final String problem : atlasConfig.problems()) {
            plugin.getSLF4JLogger().warn(problem);
        }
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

    /** 星圖的生成規則。 */
    public AtlasConfig atlasConfig() {
        return atlasConfig;
    }

    // ---- 星圖（程序生成的六角格，2026-10-21）----

    /** 一般地圖在星圖上的規則（atlas.yml 的 maps；沒寫 min_distance 的照以前的圈數換算 2×圈 − 1）。 */
    List<AtlasGrid.Species> species() {
        final List<AtlasGrid.Species> list = new ArrayList<>();
        for (final MapConfig.MapDef map : config.maps()) {
            final AtlasConfig.MapRule rule = atlasConfig.rule(map.id());
            final int minDistance = rule.minDistance() >= 0 ? rule.minDistance() : Math.max(1, map.ring() * 2 - 1);
            list.add(new AtlasGrid.Species(map.id(), minDistance, rule.weight()));
        }
        return list;
    }

    /** 有城塞的終局（一般終局，不含巔峰、事件巔峰），照 requires 的先後排。 */
    List<String> citadelFinals() {
        final List<String> list = new ArrayList<>();
        for (final MapConfig.MapDef map : config.finals()) {
            if (config.pinnacle(map.id()) == null && !config.isEventPinnacle(map.id())) {
                list.add(map.id());
            }
        }
        return list;
    }

    /** 這個角色的星圖（照他的種子生成）。 */
    public AtlasGrid grid(final Player player) {
        return atlasProgress(player).grid();
    }

    /**
     * 這個角色的星圖進度：沒有種子的（舊角色、新角色）先給種子，舊的星圖進度（完成過哪些地圖）轉成格子。
     * 照存的字串快取，存的變了才重讀。
     */
    public AtlasProgress atlasProgress(final Player player) {
        final PlayerState state = plugin.players().get(player);
        final Map<String, String> progress = state.progress();
        long seed = Atlas.parseLong(progress.get(AtlasProgress.SEED_KEY));
        final boolean fresh = seed == 0;
        if (fresh) {
            seed = random().nextLong() | 1L;
        }
        final StringBuilder raw = new StringBuilder();
        for (int i = 1; i < 1000; i++) {
            final String chunk = progress.get(AtlasProgress.cellsKey(i));
            if (chunk == null) {
                break;
            }
            raw.append(chunk).append('|');
        }
        final CachedAtlas cached = atlasCache.get(player.getUniqueId());
        if (!fresh && cached != null && cached.seed() == seed && cached.cells().contentEquals(raw)) {
            return cached.progress();
        }
        final AtlasGrid grid = new AtlasGrid(atlasConfig, seed, species(), citadelFinals());
        final AtlasProgress atlas = new AtlasProgress(grid, AtlasProgress.readCells(progress));
        if (fresh && state.hasCharacter()) {
            // 轉檔：以前完成過的地圖 → 最近的那種格子（連同路上的）；以前解鎖過終局的，所有城塞當作攻破
            final Set<String> legacy = Atlas.completed(config, progress);
            final List<String> species = new ArrayList<>();
            for (final MapConfig.MapDef map : config.maps()) {
                if (legacy.contains(map.id())) {
                    species.add(map.id());
                }
            }
            for (final long key : AtlasProgress.migrate(grid, species)) {
                atlas.complete(AtlasGrid.qOf(key), AtlasGrid.rOf(key));
            }
            if (Atlas.legacyFinalUnlocked(config, legacy)) {
                for (final String finalId : citadelFinals()) {
                    plugin.characters().setProgress(player, AtlasProgress.CITADEL_PREFIX + finalId, "legacy");
                }
            }
            plugin.characters().setProgress(player, AtlasProgress.SEED_KEY, String.valueOf(seed));
            saveAtlasProgress(player, atlas);
            if (!species.isEmpty()) {
                player.sendMessage(plugin.text().prefixed("<#9fd3ff>星圖改版了：以前完成的 " + species.size() + " 種地圖已經轉成新星圖上的 "
                        + atlas.completed().size() + " 格（/atlas 看看）"));
            }
        }
        atlasCache.put(player.getUniqueId(), new CachedAtlas(seed, raw.toString(), atlas));
        return atlas;
    }

    /**
     * 這張圖對應星圖的哪一格（開圖時）：記在實例上、套這一格的加成。
     * Boss 格：Boss 生命 ×boss_life、Boss 掉落多幾成；腐化：怪物等級 +monster_level（算在怪物強度上）、怪物群、物品數量、稀有度；
     * 隱藏格：保證一個隨機的地圖事件（MapEvents.place 看 {@link MapInstance#atlasCell}）。
     */
    private void applyCell(final Player player, final MapInstance instance, final AtlasGrid.Cell cell) {
        instance.setAtlasCell(cell);
        final AtlasProgress atlas = atlasProgress(player);
        // 2026-10-21 漏洞修正：同一個人同一格還有一張沒打完的圖（傳送門關了、隊友還在裡面）時，這張當作打過的——
        //   不然開兩張同一格的圖，第一次完成的加成（Boss 格、腐化的蝕變石、淨化的定星石）會拿兩次
        boolean twin = false;
        for (final MapInstance other : instances.values()) {
            if (other != instance && player.getUniqueId().equals(other.owner()) && !other.bossKilled() && other.atlasCell() != null
                    && other.atlasCell().q() == cell.q() && other.atlasCell().r() == cell.r()) {
                twin = true;
                break;
            }
        }
        // 2026-10-21 漏洞修正：格子要打倒 Boss（開圖的人在旁邊）才算完成；以前不打 Boss、或站遠讓隊友打，
        //   這格就永遠沒完成，每張同種地圖都能再吃一次第一次的加成。現在每格的加成最多給 CELL_TRIES 次（開圖就算一次）
        final boolean completedCell = atlas.isCompleted(cell.q(), cell.r());
        final String cellTry = "celltry." + cell.q() + ":" + cell.r();
        final int cellTries = (int) Atlas.parseLong(plugin.players().get(player).progress().get(cellTry));
        final boolean exhausted = !completedCell && cellTries >= CELL_TRIES;
        final boolean done = twin || completedCell || exhausted;
        if (!done) {
            plugin.characters().setProgress(player, cellTry, String.valueOf(cellTries + 1));
        }
        final List<String> notes = new ArrayList<>();
        // 核心天賦的效果（不是格子的加成）：完成過的格子也照樣吃
        if (instance.atlas().get("atlas.chosen_event") > 0) { // 核心「單一信仰」：選的那種事件每張圖額外保證幾個，其他事件不出現（2026-10-21：祭壇、幻霧、保險箱也不出現；隨機抽到別種是空房，不換成選的那種）
            final MapEventConfig.Kind faith = faithKind(player);
            instance.setAtlas(instance.atlas().plus(AtlasBonus.kindKey(faith, "extra"), instance.atlas().count("atlas.chosen_event"))
                    .plus(AtlasBonus.faithKey(faith), 1));
            notes.add("單一信仰：" + MapEventConfig.sourceName(MapEventConfig.uniqueSource(faith)));
        }
        final boolean allCorrupt = instance.atlas().get("atlas.all_corrupt") > 0; // 核心「腐化之心」
        if (done && allCorrupt) {
            applyCorruption(instance);
            notes.add("腐化之心：當作腐化區");
        }
        if (done) {
            // 2026-10-21 漏洞修正：完成過的格子再開就是一張普通的圖（Boss 格、隱藏格、腐化、石板的加成只給第一次），
            // 不然大家只會挑一格最好的（腐化＋Boss＋好幾片石板）一直刷
            player.sendMessage(plugin.text().parse("<gray>  星圖：<white>(" + cell.q() + ", " + cell.r() + ")<gray> "
                    + (exhausted ? "已經開了 " + cellTries + " 次都沒打完：格子的加成最多給 " + CELL_TRIES + " 次（打倒 Boss 照樣完成這格）"
                            : "已完成過：格子的加成只給第一次（往外走找新的格子）")
                    + (notes.isEmpty() ? "" : "　" + String.join("、", notes))));
            return;
        }
        if (cell.kind() == AtlasGrid.Kind.BOSS) {
            instance.twist().bossPower += Math.max(0, atlasConfig.bossLife() - 1);
            instance.twist().bossLoot += 0.5;
            instance.setAtlasBossCell(true);
            notes.add("Boss 格：Boss 更強、掉得更多");
        }
        if (cell.kind() == AtlasGrid.Kind.HIDDEN) {
            final MapEventConfig.Kind[] kinds = {MapEventConfig.Kind.SHRINE, MapEventConfig.Kind.STRONGBOX, MapEventConfig.Kind.BREACH,
                    MapEventConfig.Kind.EXILE, MapEventConfig.Kind.ABYSS};
            final MapEventConfig.Kind kind = kinds[random().nextInt(kinds.length)];
            instance.setAtlas(instance.atlas().plus(AtlasBonus.kindKey(kind, "extra"), 1));
            notes.add("隱藏格：保證一個地圖事件");
        }
        // 星圖天賦 atlas.corrupt.chance：沒腐化的格子開圖時也有機會算腐化（基本機率 × 增加的）；淨化區不會被腐化（2026-10-21）
        final boolean extraCorrupt = !cell.corrupted() && !cell.purified() && cell.distance() >= atlasConfig.corruption().minDistance()
                && random().nextDouble() < atlasConfig.corruption().chance() * instance.atlas().increased("atlas.corrupt.chance");
        if (cell.corrupted() || extraCorrupt || allCorrupt && !cell.purified()) {
            applyCorruption(instance);
            instance.setFirstCorrupted(true); // 2026-10-21：第一次完成這格：Boss 有機率掉蝕變石
            notes.add("腐化區：怪更強、掉得更多、Boss 可能掉蝕變石");
        }
        if (cell.purified()) {
            instance.setFirstPurified(true); // 2026-10-21：第一次完成這格：Boss 有機率掉定星石
            notes.add("淨化區：Boss 可能掉定星石");
        }
        // 先驅石板：罩到這格的每座塔（半徑照 atlas.yml 的 tower.radius + 星圖天賦）
        final List<AtlasTablets.Kind> covering = tabletsCovering(player, atlas, cell.q(), cell.r());
        if (!covering.isEmpty()) {
            final double effect = 1 + instance.atlas().increased("atlas.tablet.effect");
            final AtlasTablets.Applied applied = AtlasTablets.apply(instance.atlas(), covering, effect);
            instance.setAtlas(applied.bonus());
            if (applied.bossTablets() > 0 && cell.kind() == AtlasGrid.Kind.NORMAL) {
                instance.twist().bossPower += Math.max(0, atlasConfig.bossLife() - 1);
                instance.twist().bossLoot += 0.5;
                instance.setAtlasBossCell(true);
            }
            instance.twist().quantity += applied.bountyQuantity() / 100.0;
            instance.twist().bossLoot += applied.bountyRarity() / 100.0;
            final List<String> names = new ArrayList<>();
            for (final AtlasTablets.Kind kind : covering) {
                names.add(kind.displayName());
            }
            notes.add("石板 ×" + covering.size() + "（" + String.join("、", names) + "）");
            consumeTablets(player, atlas, cell.q(), cell.r()); // 2026-10-21：每開一張用掉 1 次
        }
        player.sendMessage(plugin.text().parse("<gray>  星圖：<white>(" + cell.q() + ", " + cell.r() + ")<gray> 距離 " + cell.distance()
                + (done ? "・已完成過（再打不給點數）" : "・完成後 +天賦點、解鎖周圍")
                + (notes.isEmpty() ? "" : "　" + String.join("、", notes))));
    }

    /** 一座塔最多開幾次（2026-10-21 漏洞修正：塔不用地圖物品，不能無限免費開）。 */
    static final int TOWER_TRIES = 3;

    /** 一格的加成（Boss 格、腐化、隱藏格、石板）最多給幾次（2026-10-21 漏洞修正：開圖就算一次，打倒 Boss 完成這格）。 */
    static final int CELL_TRIES = 2;

    /** 角色進度：核心「單一信仰」選的事件。 */
    public static final String FAITH_KEY = "atlasfaith";

    /** 核心「單一信仰」選的事件（沒選是神龕）。 */
    public MapEventConfig.Kind faithKind(final Player player) {
        final String stored = plugin.players().get(player).progress().get(FAITH_KEY);
        if (stored != null) {
            for (final MapEventConfig.Kind kind : MapEventConfig.Kind.values()) {
                if (kind.name().equalsIgnoreCase(stored.trim()) && kind != MapEventConfig.Kind.SAFE) {
                    return kind;
                }
            }
        }
        return MapEventConfig.Kind.SHRINE;
    }

    /** 核心「單一信仰」：換成下一種事件（神龕 → 寶箱 → 裂隙 → 流寇 → 深淵 → 神龕）。 */
    public MapEventConfig.Kind cycleFaith(final Player player) {
        final MapEventConfig.Kind[] kinds = {MapEventConfig.Kind.SHRINE, MapEventConfig.Kind.STRONGBOX, MapEventConfig.Kind.BREACH,
                MapEventConfig.Kind.EXILE, MapEventConfig.Kind.ABYSS};
        final MapEventConfig.Kind current = faithKind(player);
        MapEventConfig.Kind next = kinds[0];
        for (int i = 0; i < kinds.length; i++) {
            if (kinds[i] == current) {
                next = kinds[(i + 1) % kinds.length];
            }
        }
        plugin.characters().setProgress(player, FAITH_KEY, next.name());
        return next;
    }

    /** 腐化區的加成（atlas.yml 的 corruption；星圖天賦 atlas.corrupt.bonus 加強；核心「腐化之心」的代價怪物更痛）。 */
    private void applyCorruption(final MapInstance instance) {
        final AtlasConfig.Corruption corruption = atlasConfig.corruption();
        final double more = 1 + instance.atlas().increased("atlas.corrupt.bonus");
        instance.twist().monsterPower += 0.08 * corruption.monsterLevel();
        instance.twist().packSize += corruption.pack() / 100.0 * more;
        instance.twist().quantity += corruption.quantity() / 100.0 * more;
        instance.twist().bossLoot += corruption.rarity() / 100.0 * more;
        if (instance.atlas().get("atlas.monster_damage_more") > 0) {
            instance.twist().monsterPower += instance.atlas().increased("atlas.monster_damage_more");
        }
    }

    /** 這座塔插的石板（角色進度 tablet.q:r）；沒插回傳 null。 */
    public AtlasTablets.Kind tabletAt(final Player player, final int q, final int r) {
        return AtlasTablets.Kind.ofCurrency(plugin.players().get(player).progress().get(AtlasTablets.key(q, r)));
    }

    /**
     * 2026-10-23：這座塔周圍還有幾格吃得到石板加成（地圖格、沒完成、格子的加成次數還沒用完）。
     * 0 = 插石板也沒用，塔與城塞的清單就不列它。
     */
    public int tabletTargets(final Player player, final AtlasProgress atlas, final AtlasGrid.Cell tower) {
        final int radius = atlasConfig.tower().radius() + atlasBonus(player).count("atlas.tower.radius");
        final java.util.Map<String, String> progress = plugin.players().get(player).progress();
        int count = 0;
        for (final long[] at : AtlasGrid.within(tower.q(), tower.r(), radius)) {
            final int q = (int) at[0];
            final int r = (int) at[1];
            if (atlas.grid().isEmpty(q, r) || !atlas.grid().cell(q, r).isMap() || atlas.isCompleted(q, r)) {
                continue;
            }
            if (Atlas.parseLong(progress.get("celltry." + q + ":" + r)) >= CELL_TRIES) {
                continue; // 開了好幾次都沒打完：格子的加成（含石板）已經不給了
            }
            count++;
        }
        return count;
    }

    /** 2026-10-23：玩家從塔與城塞的清單移除的塔（角色進度 towerhide.q:r）。 */
    public boolean towerHidden(final Player player, final int q, final int r) {
        return plugin.players().get(player).progress().get(towerHideKey(q, r)) != null;
    }

    public void setTowerHidden(final Player player, final int q, final int r, final boolean hidden) {
        if (hidden) {
            plugin.characters().setProgress(player, towerHideKey(q, r), "1");
        } else {
            plugin.characters().removeProgress(player, towerHideKey(q, r));
        }
    }

    private static String towerHideKey(final int q, final int r) {
        return "towerhide." + q + ":" + r;
    }

    /** 這座塔的石板還能用幾次（沒插回傳 0）。 */
    public int tabletUses(final Player player, final int q, final int r) {
        if (tabletAt(player, q, r) == null) {
            return 0;
        }
        return AtlasTablets.parseUses(plugin.players().get(player).progress().get(AtlasTablets.usesKey(q, r)));
    }

    /** 罩到這一格的每片石板用掉 1 次；用完的拿掉（塔空出來可以再插）。 */
    private void consumeTablets(final Player player, final AtlasProgress atlas, final int q, final int r) {
        final int radius = atlasConfig.tower().radius() + atlasBonus(player).count("atlas.tower.radius");
        for (final AtlasGrid.Cell tower : atlas.completedTowers()) {
            if (AtlasGrid.distance(tower.q(), tower.r(), q, r) > radius) {
                continue;
            }
            final AtlasTablets.Kind kind = tabletAt(player, tower.q(), tower.r());
            if (kind == null) {
                continue;
            }
            final int left = tabletUses(player, tower.q(), tower.r()) - 1;
            if (left <= 0) {
                plugin.characters().removeProgress(player, AtlasTablets.key(tower.q(), tower.r()));
                plugin.characters().removeProgress(player, AtlasTablets.usesKey(tower.q(), tower.r()));
                player.sendMessage(plugin.text().prefixed("<#d8c8a0>塔 (" + tower.q() + ", " + tower.r() + ") 的" + kind.displayName()
                        + "用完了<gray>（這張還有效；在地圖裝置的「塔與城塞」可以再插一片）"));
            } else {
                plugin.characters().setProgress(player, AtlasTablets.usesKey(tower.q(), tower.r()), Integer.toString(left));
            }
        }
    }

    /** 罩到這一格的石板（每座完成的塔，半徑 = tower.radius + 星圖天賦 atlas.tower.radius）。 */
    List<AtlasTablets.Kind> tabletsCovering(final Player player, final AtlasProgress atlas, final int q, final int r) {
        final List<AtlasTablets.Kind> list = new ArrayList<>();
        final int radius = atlasConfig.tower().radius() + atlasBonus(player).count("atlas.tower.radius");
        for (final AtlasGrid.Cell tower : atlas.completedTowers()) {
            if (AtlasGrid.distance(tower.q(), tower.r(), q, r) > radius) {
                continue;
            }
            final AtlasTablets.Kind kind = tabletAt(player, tower.q(), tower.r());
            if (kind != null) {
                list.add(kind);
            }
        }
        return list;
    }

    /**
     * 把一片石板插進完成的塔：扣一片通貨（背包或通貨袋）、記在角色進度。回傳問題（null = 插好了）。
     */
    public String insertTablet(final Player player, final AtlasGrid.Cell tower, final AtlasTablets.Kind kind) {
        final AtlasProgress atlas = atlasProgress(player);
        if (tower == null || tower.kind() != AtlasGrid.Kind.TOWER) {
            return "這不是塔";
        }
        if (!atlas.isCompleted(tower.q(), tower.r())) {
            return "先完成這座塔";
        }
        if (tabletAt(player, tower.q(), tower.r()) != null) {
            return "這座塔已經插了石板（用完才能再插）";
        }
        final CurrencyType type = plugin.currencies() == null ? null : plugin.currencies().repository().get(kind.currencyId());
        if (type == null) {
            return "currencies.yml 沒有 " + kind.currencyId();
        }
        if (countCurrency(player, type) < 1) {
            return "你沒有" + kind.displayName();
        }
        takeCurrency(player, type, 1);
        plugin.characters().setProgress(player, AtlasTablets.key(tower.q(), tower.r()), kind.currencyId());
        plugin.characters().setProgress(player, AtlasTablets.usesKey(tower.q(), tower.r()), Integer.toString(AtlasTablets.USES));
        player.sendMessage(plugin.text().prefixed("<#d8c8a0>插上" + kind.displayName() + "：" + kind.description()
                + "<gray>（塔 (" + tower.q() + ", " + tower.r() + ") 周圍 " + (atlasConfig.tower().radius() + atlasBonus(player).count("atlas.tower.radius")) + " 格；罩到的地圖可以開 "
                + AtlasTablets.USES + " 張）"));
        return null;
    }

    /** 擲一片隨機的先驅石板給這個人（塔完成時）。 */
    private void giveTablet(final Player player, final AtlasTablets.Kind kind) {
        if (plugin.currencies() == null) {
            return;
        }
        final CurrencyType type = plugin.currencies().repository().get(kind.currencyId());
        if (type == null) {
            plugin.getSLF4JLogger().warn("currencies.yml 沒有 {}，塔沒有掉石板", kind.currencyId());
            return;
        }
        final ItemStack stack = plugin.currencies().create(type, 1);
        plugin.economy().ledger().produced(type.id(), "tower", 1);
        plugin.economy().wallet().dropFor(player, plugin.economy().wallet().give(player, stack));
        player.sendMessage(plugin.text().prefixed("<#d8c8a0>✦ 掉落：<white>" + ItemRenderer.escape(type.name()) + "<gray>（插進完成的塔：地圖裝置的「塔與城塞」）"));
    }

    // ---- 塔（2026-10-21）----

    /** 塔的「地圖」：旁邊那種地圖的怪、城區版面、一隻塔守（比地圖 Boss 弱）；爬到頂打倒塔守就完成。 */
    MapConfig.MapDef towerMap(final AtlasGrid grid, final AtlasGrid.Cell tower) {
        MapConfig.MapDef nearby = null;
        for (final AtlasGrid.Cell neighbour : grid.neighbours(tower.q(), tower.r())) {
            if (neighbour.isMap() && neighbour.mapId() != null) {
                nearby = config.find(neighbour.mapId());
                if (nearby != null) {
                    break;
                }
            }
        }
        if (nearby == null) {
            nearby = config.maps().iterator().next();
        }
        final MapConfig.Boss base = nearby.boss();
        final MapConfig.Boss keeper = new MapConfig.Boss("塔守・" + base.name(), base.type(), base.mythic(), false, base.life() * 0.6,
                base.damage() * 0.8, base.scale(), base.mods(), "「這座塔，不歡迎任何人。」", "塔頂的石板座亮了起來。", List.of(), base.skills());
        return new MapConfig.MapDef("tower", "塔（" + nearby.name() + "）", null, 0, config.tiers().level(config.tiers().clamp(tower.tier())), "ruins",
                -1, -1, List.of(), false, nearby.monsters(), keeper, "先驅留下的瞭望塔。爬到頂，插上石板，附近的地圖就聽你的。", false,
                MapLayout.Style.CITY);
    }

    /** 開一座塔（不用地圖物品；要解鎖、還沒完成）。回傳問題（null = 開了）。 */
    public String openTower(final Player player, final AtlasGrid.Cell tower) {
        if (!plugin.players().get(player).hasCharacter()) {
            return "先建立角色";
        }
        final AtlasProgress atlas = atlasProgress(player);
        if (tower == null || tower.kind() != AtlasGrid.Kind.TOWER || !atlas.isUnlocked(tower.q(), tower.r())) {
            return "這座塔還沒解鎖";
        }
        if (atlas.isCompleted(tower.q(), tower.r())) {
            return "這座塔已經完成了（塔只能打一次）";
        }
        // 2026-10-21 漏洞修正：塔不用地圖物品，開圖的人不進去、讓隊友只刷怪不打塔守，就能一直免費開。每座塔最多開 TOWER_TRIES 次
        final String tries = "towertry." + tower.q() + ":" + tower.r();
        final int used = (int) Atlas.parseLong(plugin.players().get(player).progress().get(tries));
        if (used >= TOWER_TRIES) {
            return "這座塔已經開了 " + used + " 次都沒打完（每座最多 " + TOWER_TRIES + " 次）：往外找別的塔";
        }
        final MapConfig.MapDef map = towerMap(atlas.grid(), tower);
        final MapStats stats = MapStats.forTier(config.tiers(), tower.tier(), 0);
        final Special special = new Special("tower", 0xd8c8a0, (instance, players) -> {
            // 2026-10-21 漏洞修正：只有開塔的人完成這座塔（在不在裡面都算）；隊友不會順便完成自己星圖上的塔
            final Player owner = plugin.getServer().getPlayer(instance.owner());
            final AtlasGrid.Cell cell = instance.atlasCell();
            if (owner != null && cell != null && plugin.players().get(owner).hasCharacter() && completeCell(owner, cell)) {
                final AtlasTablets.Kind[] kinds = AtlasTablets.Kind.values();
                giveTablet(owner, kinds[random().nextInt(kinds.length)]);
            }
        });
        // 2026-10-21 漏洞修正：重開的塔只有起點跟塔守那一間（以前前兩次可以只清怪不打塔守，等於免費多刷兩張圖）
        final MapInstance opened = openSpecialInstance(player, map, stats, used > 0 ? 2 : 4, special);
        if (opened == null) {
            return lastSpecialProblem == null ? "塔開不起來" : lastSpecialProblem;
        }
        plugin.characters().setProgress(player, tries, String.valueOf(used + 1));
        opened.setAtlasCell(tower);
        if (used > 0) {
            player.sendMessage(plugin.text().parse("<gray>  重開的塔：只剩塔守那一間（第 " + (used + 1) + "／" + TOWER_TRIES + " 次）"));
        }
        player.sendMessage(plugin.text().parse("<gray>  星圖：塔 <white>(" + tower.q() + ", " + tower.r() + ")<gray>，完成後解鎖周圍 "
                + atlasConfig.unlock().onTower() + " 格、天賦點 +" + atlasConfig.tower().points() + "、掉 1 片先驅石板"));
        return null;
    }

    // ---- 城塞（2026-10-21）----

    /**
     * 從城塞開那個終局（第一次不用天穹碎片）：要解鎖、還沒攻破、requires 的終局打贏過。階級最高到完成過的最高階（至少 1）。
     * 回傳問題（null = 開了）。
     */
    public String activateCitadel(final Player player, final AtlasGrid.Cell citadel, final int tier) {
        if (!plugin.players().get(player).hasCharacter()) {
            return "先建立角色";
        }
        final AtlasProgress atlas = atlasProgress(player);
        if (citadel == null || citadel.kind() != AtlasGrid.Kind.CITADEL || !atlas.isUnlocked(citadel.q(), citadel.r())) {
            return "這座城塞還沒解鎖";
        }
        if (atlas.isCompleted(citadel.q(), citadel.r())) {
            return "這座城塞已經攻破了：那個終局在地圖裝置用天穹碎片開";
        }
        final MapConfig.MapDef map = config.find(citadel.citadelFinal());
        if (map == null || !map.isFinal()) {
            return "maps.yml 沒有終局 " + citadel.citadelFinal();
        }
        final Set<String> completed = completed(player);
        if (!Atlas.finalRequirementsMet(config, map, completed)) {
            return "要先打贏 " + Atlas.missingRequirements(config, map, completed) + " 才能開" + map.name();
        }
        final int best = Math.max(1, bestTier(player));
        final int chosen = Math.max(1, Math.min(best, tier <= 0 ? Math.min(best, citadel.tier()) : tier));
        String problem = capacityProblem();
        if (problem == null) {
            problem = endgameProblem(player);
        }
        if (problem != null) {
            return problem;
        }
        // 2026-10-21 漏洞修正：第一次從城塞開不用碎片；之後（第一次沒打完）照一般終局扣碎片，不然可以讓隊友一直免費打終局
        // 2026-10-21 漏洞修正：免費那一次照終局算（以前照城塞那一格算：已經攻破過的終局，在別座城塞又能免費打一次）
        final String tryKey = "citadelfree." + map.id();
        final boolean free = !plugin.players().get(player).progress().containsKey(tryKey)
                && !plugin.players().get(player).progress().containsKey("citadeltry." + citadel.q() + ":" + citadel.r());
        final CurrencyType fragment = plugin.currencies() == null ? null : plugin.currencies().repository().get(config.drops().fragment());
        if (!free) {
            if (fragment == null || countCurrency(player, fragment) < config.drops().fragmentCost()) {
                return "這座城塞的免費那一次用掉了：要 " + config.drops().fragmentCost() + " 片" + (fragment == null ? "天穹碎片" : fragment.name());
            }
            takeCurrency(player, fragment, config.drops().fragmentCost());
        }
        final UUID ownerId = player.getUniqueId();
        try {
            final MapInstance opened = open(player, map, MapStats.forTier(config.tiers(), chosen, config.finalLevelBonus(map.id())));
            if (free) {
                plugin.characters().setProgress(player, tryKey, "1");
            } else if (fragment != null) {
                opened.paidRefund(() -> refundLater(ownerId, fragment, config.drops().fragmentCost()));
            }
            opened.setAtlasCell(citadel);
        } catch (final RuntimeException e) {
            plugin.getSLF4JLogger().error("從城塞開終局 {} 失敗", map.id(), e);
            if (!free && fragment != null) {
                refundLater(ownerId, fragment, config.drops().fragmentCost());
            }
            return "這座城塞開不起來（設定有問題，已經記在主控台）";
        }
        player.sendMessage(plugin.text().parse("<gray>  星圖：城塞 <white>(" + citadel.q() + ", " + citadel.r() + ")<gray>，攻破後 " + map.name()
                + " 可以在地圖裝置用天穹碎片打開（T" + chosen + "）"));
        return null;
    }

    /** 把星圖進度寫回角色（完成的格子分段存；多出來的舊段落刪掉）。 */
    private void saveAtlasProgress(final Player player, final AtlasProgress atlas) {
        final List<String> chunks = AtlasProgress.chunks(atlas.completed());
        for (int i = 0; i < chunks.size(); i++) {
            plugin.characters().setProgress(player, AtlasProgress.cellsKey(i + 1), chunks.get(i));
        }
        final Map<String, String> progress = plugin.players().get(player).progress();
        for (int i = chunks.size() + 1; i < 1000; i++) {
            if (!progress.containsKey(AtlasProgress.cellsKey(i))) {
                break;
            }
            plugin.characters().removeProgress(player, AtlasProgress.cellsKey(i));
        }
        atlasCache.remove(player.getUniqueId());
    }

    /** 玩家在星圖上選的「下次開圖用這一格」（q:r）；沒有或已經不能用回傳 null。 */
    public AtlasGrid.Cell chosenCell(final Player player, final String mapId) {
        final long[] at = AtlasProgress.decodeOne(plugin.players().get(player).progress().get(AtlasProgress.CHOSEN_KEY));
        if (at == null) {
            return null;
        }
        final AtlasProgress atlas = atlasProgress(player);
        final AtlasGrid.Cell cell = atlas.grid().cell((int) at[0], (int) at[1]);
        return cell.isMap() && mapId.equals(cell.mapId()) && atlas.isUnlocked(cell.q(), cell.r()) ? cell : null;
    }

    /** 記住下次開圖用哪一格（null = 清掉、自動選）。 */
    public void chooseCell(final Player player, final AtlasGrid.Cell cell) {
        if (cell == null) {
            plugin.characters().removeProgress(player, AtlasProgress.CHOSEN_KEY);
        } else {
            plugin.characters().setProgress(player, AtlasProgress.CHOSEN_KEY, AtlasProgress.encodeOne(cell.q(), cell.r()));
        }
    }

    /** 開這種地圖會用哪一格：玩家選的，不然最近的未完成格；沒解鎖這種地圖回傳 null。 */
    public AtlasGrid.Cell cellFor(final Player player, final String mapId) {
        final AtlasGrid.Cell chosen = chosenCell(player, mapId);
        return chosen != null ? chosen : atlasProgress(player).defaultCell(mapId);
    }

    /**
     * 完成一格（打倒這格的 Boss）：記進度、解鎖周圍、天賦點與掉落上限的訊息。本來就完成過的只回傳 false。
     */
    boolean completeCell(final Player player, final AtlasGrid.Cell cell) {
        final AtlasProgress atlas = atlasProgress(player);
        final Set<String> speciesBefore = atlas.unlockedSpecies();
        final int capBefore = Atlas.dropTierCap(config, atlas.capUnits());
        final int pointsBefore = atlas.cellPoints();
        final int earnedBefore = atlasEarned(player).total(); // 2026-10-22：訊息照上限後的點數（滿 120 不再顯示 +1）
        if (!atlas.complete(cell.q(), cell.r())) {
            return false;
        }
        saveAtlasProgress(player, atlas);
        final AtlasProgress after = atlasProgress(player);
        final List<String> unlocked = new ArrayList<>();
        for (final String id : after.unlockedSpecies()) {
            if (!speciesBefore.contains(id)) {
                final MapConfig.MapDef map = config.find(id);
                unlocked.add(map == null ? id : map.name());
            }
        }
        final int rawPoints = after.cellPoints() - pointsBefore;
        final AtlasTree.Earned earnedAfter = atlasEarned(player);
        final int points = earnedAfter.total() - earnedBefore;
        player.sendMessage(plugin.text().prefixed("<#9fd3ff>星圖：完成 (" + cell.q() + ", " + cell.r() + ")"
                + (cell.kind() == AtlasGrid.Kind.TOWER ? " 塔" : cell.kind() == AtlasGrid.Kind.CITADEL ? " 城塞" : "")
                + "<gray>，共 " + after.completed().size() + " 格、最遠 " + after.farthest() + " 格"
                + (points > 0 ? "<gray>，星圖天賦點 +" + points
                : rawPoints > 0 && earnedAfter.capped() ? "<gray>，星圖天賦點已滿（" + earnedAfter.cap() + "）" : "")));
        if (!unlocked.isEmpty()) {
            player.sendMessage(plugin.text().parse("<gray>  解鎖了新的地圖：<white>" + ItemRenderer.escape(String.join("、", unlocked))));
        }
        final int capAfter = Atlas.dropTierCap(config, after.capUnits());
        if (capAfter > capBefore) {
            player.sendMessage(plugin.text().parse("<gray>  掉落的地圖最高 <white>T" + capAfter + "<gray>（星圖每完成 "
                    + atlasConfig.dropCap().perCells() + " 格，高一階）"));
        }
        if (cell.kind() == AtlasGrid.Kind.CITADEL && cell.citadelFinal() != null) {
            plugin.characters().setProgress(player, AtlasProgress.CITADEL_PREFIX + cell.citadelFinal(), String.valueOf(System.currentTimeMillis() / 1000));
            final MapConfig.MapDef finalMap = config.find(cell.citadelFinal());
            player.sendMessage(plugin.text().parse("<#f5b942>  攻破城塞：" + ItemRenderer.escape(finalMap == null ? cell.citadelFinal() : finalMap.name())
                    + "<gray> 之後可以在地圖裝置用天穹碎片打開"));
        }
        if (plugin.quests() != null) {
            plugin.quests().refresh(player);
        }
        return true;
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
     * 一場終局、極境、巔峰、金庫之主的紀錄（2026-10-21，/ec bossdps）：沒有玩家名字。
     *
     * @param kind      終局／極境／巔峰／金庫之主
     * @param seconds   開打到倒下幾秒（含換階段、護盾）
     * @param perPlayer 每個有打的人：[原本的每秒傷害, 遞減後的每秒傷害]（照整場的秒數平均）
     */
    public record BossFight(String kind, String boss, int tier, double seconds, List<double[]> perPlayer, long at) {
    }

    /** 重開伺服器之後最近的幾場（最多 {@value #FIGHT_LOG} 場）。 */
    private final java.util.ArrayDeque<BossFight> fights = new java.util.ArrayDeque<>();
    static final int FIGHT_LOG = 500;

    /** Boss 倒下時記一場（MapBosses.killed）：主控台也寫一行，重開伺服器之後還能從紀錄檔查。 */
    void recordFight(final MapInstance instance) {
        if (!(instance.usesAttempts() || instance.isUber() || instance.isPinnacle()) || instance.fightStartTick() < 0) {
            return;
        }
        final double seconds = Math.max(1, (plugin.getServer().getCurrentTick() - instance.fightStartTick()) / 20.0);
        final List<double[]> perPlayer = new ArrayList<>();
        for (final double[] total : dpsCap.totals(instance.id()).values()) {
            if (total[0] > 0) {
                perPlayer.add(new double[] {total[0] / seconds, total[1] / seconds});
            }
        }
        if (perPlayer.isEmpty()) {
            return;
        }
        final String kind = instance.isPinnacle() ? "巔峰" : instance.isUber() ? "極境" : instance.isHeistFinale() ? "金庫之主" : "終局";
        final BossFight fight = new BossFight(kind, instance.bossDef().name(), instance.stats().tier(), seconds, List.copyOf(perPlayer),
                System.currentTimeMillis());
        fights.addLast(fight);
        while (fights.size() > FIGHT_LOG) {
            fights.removeFirst();
        }
        final StringBuilder each = new StringBuilder();
        for (final double[] one : perPlayer) {
            each.append(each.isEmpty() ? "" : "、").append(BossDpsCommand.wan(one[0]))
                    .append(one[1] < one[0] - 1 ? "（遞減後 " + BossDpsCommand.wan(one[1]) + "）" : "");
        }
        plugin.getSLF4JLogger().info("Boss 戰紀錄：{} T{} {} {} 人 {} 秒；每人打 Boss 每秒 {}", kind, fight.tier(), fight.boss(), perPlayer.size(),
                Math.round(seconds), each);
    }

    /** 重開伺服器之後最近的 Boss 戰（舊的在前）。 */
    public List<BossFight> recentFights() {
        return List.copyOf(fights);
    }

    /**
     * 打終局、極境、巔峰 Boss 的傷害照 maps.yml 的每秒上限限制（2026-10）：每秒傷害超過上限的部分照 dps_over 遞減
     * （每秒傷害取打訓練假人的紀錄與實際打出來的較高者，見 {@link DpsCap}）。
     * 不是巔峰 Boss、沒有上限的照原本的回傳。
     *
     * @param source 打的玩家（召喚物、持續傷害算主人）
     */
    public double capBossDamage(final LivingEntity defender, final UUID source, final double amount) {
        final String worldRole = plugin.worldEvents() == null ? null : plugin.worldEvents().role(defender);
        if ("boss".equals(worldRole) || "commander".equals(worldRole) || plugin.festival() != null && plugin.festival().isKing(defender)) {
            return amount; // 2026-10-21：開天者、南瓜王、潮汐統帥不遞減（血量改照打 Boss 每秒 300／600／150 萬、3 分半）
        }
        if (amount > 0 && source != null && plugin.life() != null && plugin.life().isWhale(defender)) {
            // 星骸之海的淵鯨：照 maps.yml 的 whale_dps（2026-10-21：打 Boss 每秒 600 萬打 3 分半，1200 萬起遞減）
            final Player player = plugin.getServer().getPlayer(source);
            final double record = player == null || plugin.training() == null ? 0 : plugin.training().bossDps(player);
            return dpsCap.apply("world:" + source, amount, config.whaleDpsCap(), config.whaleDpsOver(),
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
        // 2026-10-21：金庫之主照 heist_dps
        final double cap = instance.isPinnacle() ? instance.pinnacle().dpsCap()
                : instance.isUber() ? instance.uber().dpsCap() : instance.isHeistFinale() ? config.heistDpsCap() : config.finalDpsCap();
        if (cap <= 0) {
            return amount;
        }
        final List<MapConfig.DpsTier> over = instance.isPinnacle() ? instance.pinnacle().dpsOver()
                : instance.isUber() ? instance.uber().dpsOver() : instance.isHeistFinale() ? config.heistDpsOver() : config.finalDpsOver();
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

    /**
     * 角色完成過的地圖（含新星圖的記號：解鎖的種類、前線、城塞、掉落上限；見 {@link Atlas#view}）。
     * 沒有角色的人是空的。
     */
    public Set<String> completed(final Player player) {
        final PlayerState state = plugin.players().get(player);
        if (!state.hasCharacter()) {
            return Atlas.completed(config, state.progress());
        }
        return Atlas.view(config, state.progress(), atlasProgress(player));
    }

    /** 角色完成過的最高階（終局能開到這一階）；沒完成過是 0。 */
    public int bestTier(final Player player) {
        final Map<String, String> progress = plugin.players().get(player).progress();
        return Atlas.bestTier(config, progress, Atlas.completed(config, progress));
    }

    /** 終局開在某一階時的怪物等級。 */
    /** 建議每秒傷害照「單人幾秒打完」算（2026-10-21：3 分半；換階段、護盾的時間另外算）。 */
    public static final double RECOMMENDED_SECONDS = 210;

    /** 這隻 Boss 在這個等級的生命（一個人打）：怪物等級的生命 × 傳奇怪物的倍率 × Boss 的倍率 × 階級倍率。 */
    private double bossLife(final MapConfig.MapDef map, final int level, final double multiplier) {
        if (plugin.monsters() == null || map.boss() == null) {
            return 0;
        }
        final tw.exilecore.monster.MonsterConfig monsters = plugin.monsters().config();
        return monsters.lifeAt(level) * monsters.rarity(tw.exilecore.monster.MonsterRarity.UNIQUE).lifeMultiplier() * map.boss().life() * multiplier;
    }

    /** 終局開在這一階：建議打 Boss 的每秒傷害（單人約 3 分半打完；2026-10-21）。 */
    public double finalRecommendedDps(final MapConfig.MapDef map, final int tier) {
        return bossLife(map, finalLevel(tier), config.finalBossLifeMultiplier(tier)) / RECOMMENDED_SECONDS;
    }

    /** 極境：建議打 Boss 的每秒傷害（單人約 3 分半打完）。 */
    public double uberRecommendedDps(final MapConfig.MapDef map) {
        final MapConfig.Uber uber = config.uber(map.id());
        return uber == null ? 0 : bossLife(map, config.uberStats(map.id(), uber).monsterLevel(),
                uber.life() * config.finalLifeMultiplier(config.tiers().max())) / RECOMMENDED_SECONDS;
    }

    /** 巔峰（含事件巔峰）：建議打 Boss 的每秒傷害（單人約 3 分半打完）。 */
    public double pinnacleRecommendedDps(final MapConfig.MapDef map) {
        final MapConfig.Pinnacle pinnacle = config.pinnacle(map.id());
        return pinnacle == null ? 0 : bossLife(map, config.pinnacleStats(map.id(), pinnacle).monsterLevel(),
                config.finalLifeMultiplier(config.tiers().max())) / RECOMMENDED_SECONDS;
    }

    /**
     * 劫盜終局（金庫之主）：建議打 Boss 的每秒傷害（單人約 3 分半打完；2026-10-23）。
     * 生命照 MapBosses.tierLife：開的人打過的最高階照 final.tier_life 再乘（跟極境一樣）。
     */
    public double heistFinaleRecommendedDps(final MapConfig.MapDef map, final int level, final int tier) {
        return bossLife(map, level, config.finalLifeMultiplier(Math.max(1, tier))) / RECOMMENDED_SECONDS;
    }

    /** 「建議打 Boss 每秒 50 萬」的那一行（MiniMessage）。 */
    public static String recommendedText(final double dps) {
        return recommendedText(dps, 0);
    }

    /** 同上，再寫每秒傷害從多少開始遞減（2026-10-21 晚；cap ≤ 0 = 不遞減，不寫）。 */
    public static String recommendedText(final double dps, final double cap) {
        return dps <= 0 ? "" : "<gray>建議打 Boss 每秒 <gold>" + BossDpsCommand.wan(dps) + "</gold><dark_gray>（單人約 3 分半打完；組隊血量照人數加"
                + (cap > 0 ? "；每秒超過 " + BossDpsCommand.wan(cap) + " 的部分會遞減" : "") + "）";
    }

    /** 終局、極境、巔峰的列表底下那一行：建議每秒是什麼意思、從多少開始遞減（2026-10-21 晚）。 */
    public static String recommendedNote(final double cap) {
        return "<dark_gray>建議每秒＝單人約 3 分半打完" + (cap > 0 ? "；每秒超過 " + BossDpsCommand.wan(cap) + " 的部分會遞減" : "");
    }

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
        final double top = config.finalBossLifeMultiplier(max);
        if (top > 1.0) {
            int from = 1;
            while (from < max && config.finalBossLifeMultiplier(from + 1) <= 1.0) {
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
        if (!boss && instance.atlas().get("atlas.no_monster_currency") > 0) {
            drops.removeIf(drop -> drop.currency() != null); // 2026-10-21 核心「魔王的宴席」的代價：一般怪不掉通貨
        }
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
                * (1 + instance.atlas().mapDrop()) * Math.max(0, 1 - instance.atlas().increased("atlas.map_drop_less")); // 2026-10-21 核心「孤注一擲」的代價
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
        return instance == null ? 0 : instance.atlas().experience() - instance.atlas().increased("atlas.experience_less"); // 2026-10-21 核心「無人之地」的代價
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
        final Set<String> completed = completed(player);
        // 2026-10-21：新星圖：沒解鎖的種類也能開（別人給的地圖），只是不算任何格子完成、吃不到格子的加成
        final boolean grid = Atlas.isGrid(completed);
        if (!grid && !Atlas.unlocked(config, map, completed)) {
            return map.name() + " 還沒在星圖上解鎖：先完成跟它相連的地圖";
        }
        final AtlasGrid.Cell cell = grid && !map.isFinal() ? cellFor(player, map.id()) : null;
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
            if (cell != null) {
                applyCell(player, opened, cell);
            } else if (grid && !map.isFinal()) {
                player.sendMessage(plugin.text().parse("<gray>  這種地圖還沒在你的星圖上解鎖：打完不算星圖的格子"));
            }
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
        if (Atlas.isGrid(completed) && !Atlas.unlocked(config, map, completed) && Atlas.finalRequirementsMet(config, map, completed)) {
            return "先在星圖上找到" + map.name() + "的城塞並攻破（/atlas）"; // 2026-10-21
        }
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

    /** 主城分流（2026-10-22）；世界模組沒啟用時 null。 */
    private tw.exilecore.world.TownChannels channels() {
        final CampaignWorld worlds = plugin.world() == null ? null : plugin.world().worlds();
        return worlds == null ? null : worlds.channels();
    }

    /** 所有營地世界（每條線一個；沒有分流就只有劇情世界）。 */
    private List<World> towns() {
        final tw.exilecore.world.TownChannels channels = channels();
        if (channels != null && !channels.towns().isEmpty()) {
            return channels.towns();
        }
        final World campaign = campaign();
        return campaign == null ? List.of() : List.of(campaign);
    }

    /** 這個世界是營地（劇情世界或分流世界）。 */
    private boolean isTown(final World world) {
        final tw.exilecore.world.TownChannels channels = channels();
        return channels != null ? channels.isTown(world) : world != null && world.equals(campaign());
    }

    /** 營地的地圖裝置前面（回來的落點；第 1 線）；沒有劇情世界就用營地的落點。 */
    public Location deviceReturn() {
        final World campaign = campaign();
        if (campaign != null) {
            final CampaignLayout.Spot spot = CampaignStructures.deviceReturn();
            return new Location(campaign, spot.x(), spot.y(), spot.z(), spot.yaw(), 0f);
        }
        return plugin.zones() == null ? null : plugin.zones().campLocation();
    }

    /** 同上，換到這個玩家該回的那條線（2026-10-22 主城分流：從地圖出來回到進去前那條線）。 */
    public Location deviceReturn(final Player player) {
        final Location at = deviceReturn();
        final tw.exilecore.world.TownChannels channels = channels();
        if (at == null || channels == null || !channels.enabled()) {
            return at;
        }
        return tw.exilecore.world.TownChannels.mapTo(at, channels.pick(player));
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
                    tw.exilecore.gui.ActionBar.send(player, plugin.text().parse("<red>這一門你已經出來了<gray>：等裡面的人全部倒下（或出來），再走下一門"));
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
                    tw.exilecore.gui.ActionBar.send(player, plugin.text().parse("<red>次數只剩隊友正在打的這一波了<gray>：同一波死了或出來再進去要多扣 1 次"));
                    return;
                }
                instance.usePortal(); // 同一波裡死了、回營地再進去：照舊扣 1 次
            }
            instance.markAttemptEntry(player.getUniqueId());
        } else if (instance.singleEntry() && instance.uberEntered(player.getUniqueId())) {
            // 極境、劫盜終局每個人只能進一次：死了或回營地就出局
            gateCooldown.put(player.getUniqueId(), (long) plugin.getServer().getCurrentTick());
            tw.exilecore.gui.ActionBar.send(player, plugin.text().parse("<red>" + singleEntryName(instance) + "每個人只能進去一次（死了或離開就不能再進）"));
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
        final Location target = instance.at(instance.layout().entrance(), instance.layout().entranceYaw());
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
        final Location target = deviceReturn(player);
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
            if (campaign != null && isTown(location.getWorld())) { // 2026-10-22 主城分流：每條線的營地都有門
                final int gate = gateAt(location);
                if (gate >= 0) {
                    if (!plugin.players().get(player).hasCharacter()) {
                        continue;
                    }
                    // 每個人走進的是自己（或隊友）的地圖；還能進幾次就亮幾道門
                    final MapInstance instance = target(player);
                    if (instance == null) {
                        tw.exilecore.gui.ActionBar.send(player, plugin.text().parse("<gray>你" + (plugin.party() != null && plugin.party().inParty(player) ? "和隊友" : "")
                                + "沒有開著的地圖：先在地圖裝置開一張"));
                        continue;
                    }
                    if (instance.state() != MapInstance.State.OPEN) {
                        tw.exilecore.gui.ActionBar.send(player, plugin.text().parse("<gray>" + ItemRenderer.escape(instance.label()) + " 還在成形……"));
                        continue;
                    }
                    if (gate >= Math.min(CampaignStructures.GATE_COUNT, instance.portalsLeft())) {
                        tw.exilecore.gui.ActionBar.send(player, plugin.text().parse("<gray>這道門沒有亮：" + ItemRenderer.escape(instance.label())
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
        removeGroupLabels(instance.id());
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
        for (final World town : towns()) {
            updateLabels(town);
        }
    }

    /** 一條線的營地：門上的字（2026-10-22 主城分流：每個營地世界各一套）。 */
    private void updateLabels(final World town) {
        final Location at = labelSpot(town);
        if (!town.isChunkLoaded(at.getBlockX() >> 4, at.getBlockZ() >> 4)) {
            return;
        }
        TextDisplay general = generalLabels.get(town.getName());
        if (general == null || !general.isValid()) {
            general = spawnLabel(at, true);
            general.text(plugin.text().parse("<#9fd3ff>地圖傳送門<newline><gray>在地圖裝置開一張地圖，門就會亮起來"
                    + "<newline><dark_gray>每個人（隊伍）看到的是自己的地圖"));
            generalLabels.put(town.getName(), general);
        }
        keepAt(general, at);
        // 每張開著的地圖一個字（只有那個隊伍看得到）
        for (final MapInstance instance : instances.values()) {
            final String key = groupKey(instance.id(), town);
            if (instance.state() == MapInstance.State.CLOSING || instance.portalsLeft() <= 0) {
                removeLabel(groupLabels.remove(key));
                continue;
            }
            TextDisplay label = groupLabels.get(key);
            if (label == null || !label.isValid()) {
                label = spawnLabel(at, false);
                groupLabels.put(key, label);
            }
            keepAt(label, at);
            label.text(label(instance));
        }
        // 誰看得到哪一個
        for (final Player player : town.getPlayers()) {
            if (player.getLocation().distanceSquared(at) > 64 * 64) {
                continue;
            }
            final MapInstance target = target(player);
            setVisible(player, general, target == null);
            for (final MapInstance instance : instances.values()) {
                final TextDisplay label = groupLabels.get(groupKey(instance.id(), town));
                if (label != null) {
                    setVisible(player, label, target != null && target.id() == instance.id());
                }
            }
        }
    }

    private static String groupKey(final int instanceId, final World town) {
        return instanceId + "@" + town.getName();
    }

    /** 這張地圖在每條線的字都收掉。 */
    private void removeGroupLabels(final int instanceId) {
        final String prefix = instanceId + "@";
        for (final Iterator<Map.Entry<String, TextDisplay>> it = groupLabels.entrySet().iterator(); it.hasNext(); ) {
            final Map.Entry<String, TextDisplay> entry = it.next();
            if (entry.getKey().startsWith(prefix)) {
                removeLabel(entry.getValue());
                it.remove();
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
        for (final TextDisplay label : generalLabels.values()) {
            removeLabel(label);
        }
        generalLabels.clear();
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
        for (final World town : towns()) { // 2026-10-22 主城分流：每條線的營地
            gateParticles(town);
        }
    }

    private void gateParticles(final World campaign) {
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
        return deviceReturn(player);
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
        complete(instance, boss, null);
    }

    /**
     * 同上；damagers：對 Boss 打到傷害門檻的人（2026-10-21：隊友要在裡面才完成自己星圖上的格子、攻破自己的城塞；
     * null 或空的 = 不檢查，例如 Boss 生不出來、沒有傷害紀錄）。
     */
    void complete(final MapInstance instance, final Location boss, final Set<UUID> damagers) {
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
            completeFor(player, instance, damagers == null || damagers.isEmpty() || damagers.contains(uuid));
            announceAtlasPoints(player, pointsBefore); // 星圖天賦點：新的地圖、終局、極境、最高階里程碑
        }
    }

    /** 一個在裡面的人完成這張地圖：委託、每月挑戰；星圖點亮、首殺獎勵、最高完成階級（極境另外算）。 */
    private void completeFor(final Player player, final MapInstance instance, final boolean contributed) {
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
        // 2026-10-21：從城塞開的終局：攻破城塞（開圖的人那一格；隊友是自己星圖上已經解鎖、最近的那個終局的城塞）
        if (map.isFinal() && instance.atlasCell() != null && instance.atlasCell().kind() == AtlasGrid.Kind.CITADEL && state.hasCharacter()) {
            AtlasGrid.Cell mine = player.getUniqueId().equals(instance.owner()) ? instance.atlasCell() : null;
            if (mine == null && contributed) { // 2026-10-21 漏洞修正：隊友要對 Boss 打到傷害才攻破自己的城塞
                for (final AtlasGrid.Cell open : atlasProgress(player).openCitadels()) {
                    if (map.id().equals(open.citadelFinal())) {
                        mine = open;
                        break;
                    }
                }
            }
            if (mine != null && !atlasProgress(player).isCompleted(mine.q(), mine.r())) {
                completeCell(player, mine);
            }
        }
        // 2026-10-21：新星圖：開圖的人完成他選的那一格；隊友完成自己星圖上最近的同種類已解鎖格（沒解鎖就不算）
        if (!map.isFinal() && instance.atlasCell() != null && state.hasCharacter()) {
            // 2026-10-21 漏洞修正：隊友要對 Boss 打到傷害才完成自己的格子（以前分身站在旁邊就能一直推星圖）
            final AtlasGrid.Cell mine = player.getUniqueId().equals(instance.owner()) ? instance.atlasCell()
                    : contributed ? atlasProgress(player).defaultCell(map.id()) : null;
            if (mine != null && !atlasProgress(player).isCompleted(mine.q(), mine.r())) {
                completeCell(player, mine);
            }
        }
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
        if (!Atlas.isGrid(before)) { // 2026-10-21：新星圖的解鎖在 completeCell 裡算
            for (final MapConfig.MapDef other : config.all()) {
                if (config.isEventPinnacle(other.id())) {
                    continue; // 2026-10-18：事件巔峰不在星圖上，不算「解鎖了」
                }
                if (!Atlas.unlocked(config, other, before) && Atlas.unlocked(config, other, after)) {
                    unlocked.add(other.name());
                }
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
        if (!Atlas.isGrid(before) && capAfter > capBefore) { // 2026-10-21：新星圖的掉落上限訊息在 completeCell 裡
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
            final Location safe = deviceReturn(owner); // 2026-10-22 主城分流：掉在他那條線的營地
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
        // 2026-10-21：一般地圖的點數照新星圖完成的格子（atlas.yml 的 points）；終局、極境、階級照 atlas_tree.yml
        final int cellPoints = plugin.players().get(player).hasCharacter() ? atlasProgress(player).cellPoints() : 0;
        return tree.earned(cellPoints, finals, uberDefeated(player).size(), Atlas.bestTier(config, progress, completed));
    }

    /** 這個角色有效的星圖天賦（照點的順序；點數不夠的從最後點的開始不算）。 */
    public List<String> atlasAllocated(final Player player) {
        final Map<String, String> progress = plugin.players().get(player).progress();
        final String stored = progress.get(AtlasTree.PROGRESS_KEY);
        if (stored != null && !stored.isBlank() && !String.valueOf(tree.version()).equals(progress.get(AtlasTree.VERSION_KEY))) {
            // 2026-10-21：樹的版本變了（節點代號或位置大改）：全部退點（跟被動天賦樹一樣），點數照新規則重算
            plugin.characters().removeProgress(player, AtlasTree.PROGRESS_KEY);
            plugin.characters().setProgress(player, AtlasTree.VERSION_KEY, String.valueOf(tree.version()));
            player.sendMessage(plugin.text().prefixed("<yellow>星圖天賦樹改版了，你的星圖天賦全部退回（免費），請重新配點：/atlas tree"));
            return List.of();
        }
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

    /** 退掉一個星圖天賦（連不回中心的一起退，免費）；回傳退掉的（空的 = 沒點這個）。 */
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

    /**
     * 2026-10-21：匯入網頁規劃器的配點（代號用逗號隔開）：照「從起點往外」排好、點數不夠的從外面開始不算，整組換掉現在的（免費）。
     * 回傳給玩家看的結果。
     */
    public String importAtlas(final Player player, final String code) {
        if (!plugin.players().get(player).hasCharacter()) {
            return "先建立角色";
        }
        final List<String> planned = tree.decodeCode(code); // 2026-10-21：短代碼（舊的「代號,代號」也讀得懂）
        if (planned.isEmpty()) {
            return "代碼是空的";
        }
        final List<String> ordered = tree.orderFromRoot(planned);
        final List<String> effective = tree.effective(ordered, atlasEarned(player).total());
        saveAtlas(player, effective);
        final int unknown = planned.size() - ordered.size();
        return "星圖天賦換成規劃的 " + effective.size() + " 個（" + tree.spent(effective) + " 點）"
                + (effective.size() < ordered.size() ? "；" + (ordered.size() - effective.size()) + " 個點數不夠，升級後再匯入一次" : "")
                + (unknown > 0 ? "；" + unknown + " 個代號不認得或連不到中心" : "");
    }

    /** 2026-10-21：網頁規劃器的網址（帶著現在的配點）；網頁沒開回傳 null。 */
    public String atlasPlannerUrl(final Player player) {
        if (plugin.web() == null || !plugin.web().available(player)) {
            return null;
        }
        final List<String> allocated = atlasAllocated(player);
        return plugin.web().publicUrl() + "/atlas" + (allocated.isEmpty() ? "" : "#" + tree.encodeCode(allocated)); // 2026-10-21：短代碼
    }

    /** 2026-10-21：整組換掉（星圖天賦組切換）：照這份配點存（已經是有效的順序）。 */
    public void replaceAtlas(final Player player, final List<String> allocated) {
        saveAtlas(player, allocated);
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
            plugin.characters().setProgress(player, AtlasTree.VERSION_KEY, String.valueOf(tree.version()));
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
        player.teleport(instance.at(instance.layout().entrance(), instance.layout().entranceYaw()));
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
            if (!map.isFinal() && plugin.players().get(player).hasCharacter()) {
                // 2026-10-21：新星圖：把最近的那種格子（連同路上的）標成完成
                final AtlasProgress atlas = atlasProgress(player);
                for (final long key : AtlasProgress.migrate(atlas.grid(), List.of(map.id()))) {
                    atlas.complete(AtlasGrid.qOf(key), AtlasGrid.rOf(key));
                }
                saveAtlasProgress(player, atlas);
            }
        } else {
            plugin.characters().removeProgress(player, Atlas.key(map.id()));
        }
        if (plugin.quests() != null) {
            plugin.quests().refresh(player);
        }
    }

    /** 2026-10-21：清掉新星圖的進度（完成的格子、選的格子、攻破的城塞；種子留著）。 */
    public void resetAtlasProgress(final Player player) {
        final Map<String, String> progress = plugin.players().get(player).progress();
        for (int i = 1; i < 1000; i++) {
            if (!progress.containsKey(AtlasProgress.cellsKey(i))) {
                break;
            }
            plugin.characters().removeProgress(player, AtlasProgress.cellsKey(i));
        }
        plugin.characters().removeProgress(player, AtlasProgress.CHOSEN_KEY);
        for (final String finalId : citadelFinals()) {
            plugin.characters().removeProgress(player, AtlasProgress.CITADEL_PREFIX + finalId);
        }
        atlasCache.remove(player.getUniqueId());
    }

    /** 2026-10-21：換一個種子（星圖重新生成；完成的格子清掉）。 */
    public long reseedAtlas(final Player player, final long seed) {
        resetAtlasProgress(player);
        final long value = seed == 0 ? random().nextLong() | 1L : seed;
        plugin.characters().setProgress(player, AtlasProgress.SEED_KEY, String.valueOf(value));
        atlasCache.remove(player.getUniqueId());
        return value;
    }

    /** 2026-10-21：把起點周圍 radius 格全部標成完成（測試用）。 */
    public int revealAtlas(final Player player, final int radius) {
        final AtlasProgress atlas = atlasProgress(player);
        int added = 0;
        for (final long[] at : AtlasGrid.within(0, 0, radius)) {
            final int q = (int) at[0];
            final int r = (int) at[1];
            if ((q != 0 || r != 0) && !atlas.grid().isEmpty(q, r) && atlas.complete(q, r)) {
                added++;
            }
        }
        saveAtlasProgress(player, atlas);
        return added;
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
