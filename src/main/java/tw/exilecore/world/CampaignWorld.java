package tw.exilecore.world;

import org.bukkit.Chunk;
import org.bukkit.GameMode;
import org.bukkit.GameRule;
import org.bukkit.GameRules;
import org.bukkit.Location;
import org.bukkit.NamespacedKey;
import org.bukkit.World;
import org.bukkit.WorldCreator;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Entity;
import org.bukkit.entity.Player;
import org.bukkit.generator.ChunkGenerator;
import org.bukkit.scheduler.BukkitTask;
import tw.exilecore.ExileCore;
import tw.exilecore.npc.NpcType;
import tw.exilecore.worldgen.BufferSink;
import tw.exilecore.worldgen.CampaignLayout;
import tw.exilecore.worldgen.CampaignStructures;
import tw.exilecore.zone.Zone;
import tw.exilecore.zone.ZoneService;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;

/**
 * 劇情世界（{@code exilecore:campaign}，海邊營地＋五個區域）、地圖世界（{@code exilecore:maps}，虛空）與 PvP 競技場（{@code exilecore:arena}，虛空）：
 * 啟動時載入（第一次會產生），設好遊戲規則（不自然生怪、不破壞、不下雨、固定時間）；
 * 第一次產生劇情世界時自動擺好區域版面與營地 NPC（{@link #setup}）。
 * <p>
 * 另外每 2 秒看一次：在劇情世界裡跑到所有區域外面（懸崖上、海上）的玩家傳回最後的檢查點；
 * 登入時還在舊世界（例如原本的虛空大廳）的玩家送回營地。
 */
public final class CampaignWorld {

    public static final NamespacedKey CAMPAIGN_KEY = new NamespacedKey("exilecore", "campaign");
    public static final NamespacedKey MAPS_KEY = new NamespacedKey("exilecore", "maps");
    /** PvP 競技場的世界（虛空，只有這個世界開 PvP；場地由 pvp 模組蓋）。 */
    public static final NamespacedKey ARENA_KEY = new NamespacedKey("exilecore", "arena");
    /** 不定時活動（開服活動）的世界（2026-10，虛空；圓形場地由活動模組自動蓋，不會影響劇情世界的生怪區）。 */
    public static final NamespacedKey EVENT_KEY = new NamespacedKey("exilecore", "event");
    /** 劇情世界資料夾裡的記號檔（有這個檔 = 已經擺過版面與 NPC）。 */
    private static final String MARKER = "exilecore-campaign.txt";
    /** 營地的版本記號：舊世界升級時補蓋後來才加的攤位與 NPC（商人、市集掌櫃），補完寫這個檔。 */
    private static final String CAMP_MARKER = "exilecore-camp-v3.txt";
    /** 在區域外待幾次檢查（每次 2 秒）就傳回去。 */
    private static final int OUTSIDE_LIMIT = 2;

    private final ExileCore plugin;
    private final WorldLore lore;
    private World campaign;
    private World maps;
    private World arena;
    private World event;
    private boolean createdCampaign;
    /** 2026-10-22 主城分流：營地的每一條線（第 1 線是劇情世界）。 */
    private final TownChannels channels;
    private BukkitTask guardTask;
    private final Map<UUID, Integer> outside = new HashMap<>();

    public CampaignWorld(final ExileCore plugin, final WorldLore lore) {
        this.plugin = plugin;
        this.lore = lore;
        this.channels = new TownChannels(plugin);
    }

    /** 主城分流（沒有劇情世界時只有空的清單）。 */
    public TownChannels channels() {
        return channels;
    }

    // ---- 啟動 ----

    public void enable() {
        BlockPalette.init(plugin.getSLF4JLogger());
        if (lore.campaignEnabled()) {
            campaign = load(CAMPAIGN_KEY, new CampaignGenerator(), lore.campaignTime());
            if (campaign != null) {
                final CampaignLayout.Spot spawn = CampaignLayout.campSpawn();
                campaign.setSpawnLocation(new Location(campaign, spawn.x(), spawn.y(), spawn.z(), spawn.yaw(), 0f));
                // 世界資料夾裡沒有記號檔 = 這個世界是新產生的（第一次啟動，或刪掉資料夾重新產生）
                final Path marker = campaign.getWorldPath().resolve(MARKER);
                createdCampaign = !Files.exists(marker);
                if (createdCampaign) {
                    try {
                        Files.writeString(marker, "ExileCore 劇情世界（刪掉這個檔，下次啟動會重新擺版面與營地 NPC）\n");
                    } catch (final IOException e) {
                        plugin.getSLF4JLogger().warn("寫入 {} 失敗", marker, e);
                    }
                }
                plugin.getSLF4JLogger().info("劇情世界：{}{}", campaign.getName(), createdCampaign ? "（新產生的世界）" : "");
                loadChannels(spawn);
            }
        }
        maps = load(MAPS_KEY, new VoidGenerator(), lore.mapTime());
        if (maps != null) {
            maps.setGameRule(GameRules.FALL_DAMAGE, false);
        }
        arena = load(ARENA_KEY, new VoidGenerator(), lore.mapTime());
        if (arena != null) {
            // 競技場：唯一開 PvP 的世界（誰能打誰由 pvp 模組決定）；從掩體上掉下來不痛
            arena.setGameRule(GameRules.PVP, true);
            arena.setGameRule(GameRules.FALL_DAMAGE, false);
        }
        event = load(EVENT_KEY, new VoidGenerator(), lore.mapTime());
        if (event != null) {
            event.setGameRule(GameRules.FALL_DAMAGE, false);
        }
        // 自動擺版面要等其他模組（區域、NPC）都啟用之後
        plugin.getServer().getScheduler().runTask(plugin, this::autoSetup);
        // 視距、模擬距離：等其他模組的世界（海域）也載入了再一起套
        plugin.getServer().getScheduler().runTask(plugin, this::applyDistances);
        guardTask = plugin.repeat("world.guard", this::guard, 40, 40);
    }

    public void reload() {
        applyDistances();
        for (final World world : new World[] {campaign, maps, arena, event}) {
            if (world != null) {
                applyTime(world, world == campaign ? lore.campaignTime() : lore.mapTime());
            }
        }
        for (final World town : channels.towns()) {
            if (town != campaign) {
                applyTime(town, lore.campaignTime());
            }
        }
    }

    /**
     * 2026-10-22 效能：所有世界套 data/world.yml 的視距與模擬距離（spark：區塊系統佔主執行緒兩成多，
     * 掃可生怪區塊、區塊票、區塊載入都跟「人數 × 每人載入的區塊」成正比）。視距不能比模擬距離小。
     */
    public void applyDistances() {
        final int simulation = lore.simulationDistance();
        final int view = lore.viewDistance() == 0 ? 0 : Math.max(lore.viewDistance(), simulation);
        for (final World world : plugin.getServer().getWorlds()) {
            try {
                if (simulation > 0 && world.getSimulationDistance() != simulation) {
                    world.setSimulationDistance(simulation);
                }
                if (view > 0 && world.getViewDistance() != view) {
                    world.setViewDistance(view);
                }
            } catch (final RuntimeException e) {
                plugin.getSLF4JLogger().warn("設定 {} 的視距／模擬距離失敗", world.getName(), e);
            }
        }
    }

    /**
     * 2026-10-22 主城分流：劇情世界之外再載入幾條線的營地世界（data/world.yml 的 campaign.channels）。
     * 新的從劇情世界複製營地那一帶的區塊檔（{@link TownChannels#prepare}），用同一個產生器（沒複製到的區塊長得一樣）。
     */
    private void loadChannels(final CampaignLayout.Spot spawn) {
        final List<Integer> wanted = channels.prepare(campaign, lore.campaignChannels(), lore.campaignRefreshOnStart());
        plugin.getServer().getPluginManager().registerEvents(channels, plugin);
        for (final int channel : wanted) {
            final World town = load(TownChannels.key(channel), new CampaignGenerator(), lore.campaignTime());
            if (town == null) {
                continue;
            }
            town.setSpawnLocation(new Location(town, spawn.x(), spawn.y(), spawn.z(), spawn.yaw(), 0f));
            channels.add(town, channel);
            plugin.getSLF4JLogger().info("主城分流：第 {} 線 {}", channel, town.getName());
        }
    }

    public void disable() {
        if (guardTask != null) {
            guardTask.cancel();
            guardTask = null;
        }
        outside.clear();
    }

    /** 劇情世界；沒啟用或載入失敗為 null。 */
    public World campaign() {
        return campaign;
    }

    /** 地圖世界；載入失敗為 null。 */
    public World maps() {
        return maps;
    }

    /** 不定時活動的世界；載入失敗為 null。 */
    public World event() {
        return event;
    }

    /** PvP 競技場的世界；載入失敗為 null。 */
    public World arena() {
        return arena;
    }

    private World load(final NamespacedKey key, final ChunkGenerator generator, final long time) {
        World world = plugin.getServer().getWorld(key);
        if (world == null) {
            try {
                world = WorldCreator.ofKey(key).generator(generator).generateStructures(false).createWorld();
            } catch (final RuntimeException e) {
                plugin.getSLF4JLogger().error("載入世界 {} 失敗", key, e);
                return null;
            }
        }
        if (world == null) {
            plugin.getSLF4JLogger().error("載入世界 {} 失敗（createWorld 回傳 null）", key);
            return null;
        }
        rule(world, GameRules.SPAWN_MOBS, false);
        rule(world, GameRules.SPAWN_MONSTERS, false);
        rule(world, GameRules.SPAWN_PATROLS, false);
        rule(world, GameRules.SPAWN_PHANTOMS, false);
        rule(world, GameRules.SPAWN_WANDERING_TRADERS, false);
        rule(world, GameRules.SPAWN_WARDENS, false);
        rule(world, GameRules.MOB_GRIEFING, false);
        rule(world, GameRules.FIRE_SPREAD_RADIUS_AROUND_PLAYER, 0);
        rule(world, GameRules.RANDOM_TICK_SPEED, 0);
        rule(world, GameRules.ADVANCE_WEATHER, false);
        rule(world, GameRules.BLOCK_DROPS, false);
        rule(world, GameRules.KEEP_INVENTORY, true);
        rule(world, GameRules.PVP, false);
        rule(world, GameRules.RAIDS, false);
        rule(world, GameRules.RESPAWN_RADIUS, 0);
        rule(world, GameRules.WATER_SOURCE_CONVERSION, false);
        rule(world, GameRules.LOCATOR_BAR, false); // 2026-10 效能：原版的定位條（每 tick 更新每個玩家的路標，spark 1.5%）用不到
        world.setStorm(false);
        world.setThundering(false);
        world.setClearWeatherDuration(Integer.MAX_VALUE / 2);
        applyTime(world, time);
        return world;
    }

    private static <T> void rule(final World world, final GameRule<T> rule, final T value) {
        world.setGameRule(rule, value);
    }

    private static void applyTime(final World world, final long time) {
        if (time < 0) {
            world.setGameRule(GameRules.ADVANCE_TIME, true);
        } else {
            world.setGameRule(GameRules.ADVANCE_TIME, false);
            world.setTime(time);
        }
    }

    // ---- 擺版面 ----

    /**
     * 新產生的劇情世界：自動擺好版面與營地 NPC（舊的版面先備份）。
     * 已經存在的世界不自動動版面（你可能用 /ec zone 改過）；版面不在劇情世界時只在主控台提醒。
     */
    private void autoSetup() {
        if (campaign == null || plugin.zones() == null) {
            return;
        }
        final String campId = plugin.zones().zones().stream().filter(Zone::town).map(Zone::id).findFirst().orElse(null);
        if (createdCampaign && lore.campaignAutoSetup()) {
            plugin.getSLF4JLogger().info("劇情世界是新產生的：自動擺好區域版面與營地 NPC（舊的版面先備份）");
            setup(plugin.getServer().getConsoleSender());
        } else if (!CampaignSetup.applied(plugin.zones().layout(), campaign.getName(), campId)) {
            plugin.getSLF4JLogger().warn("區域版面不在劇情世界 {}：要用劇情世界的話在遊戲內輸入 /ec world setup", campaign.getName());
        } else if (!campUpToDate(campaign)) {
            plugin.getSLF4JLogger().info("營地升級：補蓋商人、市集掌櫃、盜賊首領的攤位、NPC 移到現在的位置（盜賊首領在黃色攤位）");
            placeNpcs(plugin.getServer().getConsoleSender(), campaign);
        }
        // 2026-10-22 主城分流：每條線的 NPC 也要對（複製來的照劇情世界當時的版本；新產生的要放）
        for (final World town : channels.towns()) {
            if (town != campaign && !campUpToDate(town)) {
                placeNpcs(plugin.getServer().getConsoleSender(), town);
            }
        }
    }

    /**
     * 把區域版面擺到劇情世界（舊版面先備份）、在營地放 NPC（同種類已經有就移到正確位置）。
     * 放 NPC 要先載入營地的區塊，所以是非同步的；完成後通知 sender。
     */
    public void setup(final CommandSender sender) {
        if (campaign == null) {
            sender.sendMessage(plugin.text().prefixed("<red>劇情世界沒有啟用（data/world.yml 的 campaign.enabled）或載入失敗"));
            return;
        }
        final ZoneService zones = plugin.zones();
        if (zones == null) {
            sender.sendMessage(plugin.text().prefixed("<red>區域模組沒有啟用，不能擺版面"));
            return;
        }
        final Path layoutFile = plugin.dataFolder().resolve("zone-layout.yml");
        final String stamp = java.time.LocalDateTime.now().format(java.time.format.DateTimeFormatter.ofPattern("yyyyMMdd-HHmmss"));
        final Path backup = plugin.dataFolder().resolve("zone-layout.backup-" + stamp + ".yml");
        try {
            if (Files.exists(layoutFile)) {
                Files.copy(layoutFile, backup, StandardCopyOption.REPLACE_EXISTING);
            }
        } catch (final IOException e) {
            sender.sendMessage(plugin.text().prefixed("<red>備份 zone-layout.yml 失敗：" + e.getMessage()));
            return;
        }
        final String campId = zones.zones().stream().filter(Zone::town).map(Zone::id).findFirst().orElse(null);
        final List<String> combat = zones.combatZones().stream().map(Zone::id).toList();
        final int placed = CampaignSetup.apply(zones.layout(), campaign.getName(), campId, combat);
        zones.rebuild();
        try {
            zones.saveLayout();
        } catch (final IOException e) {
            sender.sendMessage(plugin.text().prefixed("<red>存 zone-layout.yml 失敗：" + e.getMessage()));
            return;
        }
        sender.sendMessage(plugin.text().prefixed("<green>區域版面已擺到劇情世界：營地" + (campId == null ? "（zones.yml 沒有城鎮）" : "")
                + "＋" + placed + " 個區域" + (combat.size() > placed ? "（zones.yml 多出來的 " + (combat.size() - placed) + " 個區域沒擺）" : "")
                + "<gray>；舊版面備份成 " + backup.getFileName()));
        for (final World town : channels.towns()) {
            placeNpcs(sender, town);
        }
    }

    /** 2026-10-22 主城分流：把每條分流線的 NPC 對齊第 1 線（位置、有沒有）；/ec world channels sync。 */
    public int syncChannels(final CommandSender sender) {
        int count = 0;
        for (final World town : channels.towns()) {
            if (town != campaign) {
                placeNpcs(sender, town);
                count++;
            }
        }
        return count;
    }

    /** 營地的 NPC：先載入營地的區塊，等實體也載入後，同種類已經有就移到正確位置，沒有就放一個新的。 */
    private void placeNpcs(final CommandSender sender, final World town) {
        if (plugin.npcs() == null) {
            return;
        }
        final List<CompletableFuture<Chunk>> futures = new ArrayList<>();
        for (int cx = -3; cx <= 2; cx++) {
            for (int cz = -3; cz <= 2; cz++) {
                futures.add(town.getChunkAtAsync(cx, cz));
                if (campaign != null && !town.equals(campaign)) {
                    futures.add(campaign.getChunkAtAsync(cx, cz)); // 分流線要照第 1 線擺，第 1 線的營地也先載入
                }
            }
        }
        CompletableFuture.allOf(futures.toArray(new CompletableFuture<?>[0])).whenComplete((ignored, error) ->
                plugin.getServer().getScheduler().runTask(plugin, () -> {
                    if (error != null) {
                        sender.sendMessage(plugin.text().prefixed("<red>載入營地區塊失敗：" + error.getMessage()));
                        return;
                    }
                    final List<Chunk> chunks = new ArrayList<>();
                    for (final CompletableFuture<Chunk> future : futures) {
                        final Chunk chunk = future.join();
                        chunk.addPluginChunkTicket(plugin);
                        chunks.add(chunk);
                    }
                    // 後來才加的攤位（舊世界生成時還沒有）：直接蓋進世界；新世界生成時已經蓋了，再蓋一次一模一樣
                    final int blocks = buildAddedStalls(town);
                    // 實體是跟區塊分開載入的：等兩秒再找舊的 NPC，免得重複放
                    plugin.getServer().getScheduler().runTaskLater(plugin, () -> {
                        final int moved = spawnNpcs(town);
                        for (final Chunk chunk : chunks) {
                            chunk.removePluginChunkTicket(plugin);
                        }
                        try {
                            Files.writeString(town.getWorldPath().resolve(CAMP_MARKER),
                                    "ExileCore 營地版本 " + CAMP_VERSION + "（商人、市集掌櫃、盜賊首領；盜賊首領在黃色攤位）。刪掉這個檔，下次啟動會再補蓋一次攤位與 NPC\n");
                        } catch (final IOException e) {
                            plugin.getSLF4JLogger().warn("寫入營地版本記號失敗", e);
                        }
                        sender.sendMessage(plugin.text().prefixed("<green>營地 NPC 已就位（新放 " + moved + " 個；攤位 " + blocks + " 格方塊"
                                + (channels.enabled() ? "；第 " + channels.channelOf(town) + " 線" : "") + "）"));
                    }, 40);
                }));
    }

    /**
     * 營地的版本：NPC 的位置改了就加一（已經產生的世界下次啟動時把 NPC 移過去）。
     * 4（2026-10）：盜賊首領搬到黃色攤位（原本市集掌櫃的）、市集掌櫃搬到灰色攤位。
     */
    private static final int CAMP_VERSION = 4;

    private boolean campUpToDate(final World town) {
        try {
            final Path marker = town.getWorldPath().resolve(CAMP_MARKER);
            return Files.exists(marker) && Files.readString(marker).contains("營地版本 " + CAMP_VERSION);
        } catch (final IOException e) {
            return true; // 讀不到就不動
        }
    }

    /** 把後來才加的 NPC 攤位蓋進劇情世界（區塊要已經載入）；回傳放了幾格方塊。 */
    private int buildAddedStalls(final World town) {
        final BufferSink sink = new BufferSink();
        for (final CampaignStructures.Npc npc : CampaignStructures.ADDED_LATER) {
            CampaignStructures.stallFor(npc, sink);
        }
        sink.compact();
        for (int i = 0; i < sink.size(); i++) {
            town.getBlockAt(sink.x(i), sink.y(i), sink.z(i)).setBlockData(BlockPalette.get(sink.block(i)), false);
        }
        return sink.size();
    }

    /** @return 新放了幾個 */
    private int spawnNpcs(final World town) {
        final Map<NpcType, Entity> existing = new HashMap<>();
        final Location center = new Location(town, 0, CampaignLayout.CAMP_Y, 0);
        for (final Entity entity : plugin.npcs().loaded()) {
            if (!entity.getWorld().equals(town) || entity.getLocation().distanceSquared(center) > 60 * 60) {
                continue;
            }
            final NpcType type = plugin.npcs().type(entity).orElse(null);
            if (type == null || type == NpcType.STORY) {
                continue; // 劇情人物由任務模組擺
            }
            if (existing.containsKey(type)) {
                entity.remove(); // 重複的
            } else {
                existing.put(type, entity);
            }
        }
        // 2026-10-22 主城分流：分流世界的 NPC 跟第 1 線（劇情世界）一樣——第 1 線手動搬過的照搬、第 1 線刪掉的這裡也不放
        final Map<NpcType, Location> lineOne = new HashMap<>();
        boolean followLineOne = false;
        if (campaign != null && !town.equals(campaign)) {
            followLineOne = true;
            for (int cx = -3; cx <= 2 && followLineOne; cx++) {
                for (int cz = -3; cz <= 2; cz++) {
                    if (!campaign.isChunkLoaded(cx, cz) || !campaign.getChunkAt(cx, cz).isEntitiesLoaded()) {
                        followLineOne = false; // 第 1 線的營地沒載入完就不知道它有哪些，照預設擺
                        break;
                    }
                }
            }
            final Location campaignCenter = new Location(campaign, 0, CampaignLayout.CAMP_Y, 0);
            for (final Entity entity : plugin.npcs().loaded()) {
                if (!entity.getWorld().equals(campaign) || entity.getLocation().distanceSquared(campaignCenter) > 60 * 60) {
                    continue;
                }
                final NpcType type = plugin.npcs().type(entity).orElse(null);
                if (type != null && type != NpcType.STORY) {
                    lineOne.putIfAbsent(type, TownChannels.mapTo(entity.getLocation(), town));
                }
            }
        }
        int spawned = 0;
        for (final CampaignStructures.Npc npc : CampaignStructures.Npc.values()) {
            final NpcType type = NpcType.valueOf(npc.name());
            final CampaignLayout.Spot spot = CampaignStructures.npcSpot(npc);
            final Location fromLineOne = lineOne.get(type);
            final Entity current = existing.get(type);
            if (followLineOne && fromLineOne == null) {
                if (current != null) {
                    current.remove(); // 第 1 線沒有這種 NPC（刪掉了）：這條線也拿掉
                }
                continue;
            }
            final Location location = fromLineOne != null ? fromLineOne
                    : new Location(town, spot.x(), spot.y(), spot.z(), spot.yaw(), 0f);
            if (current != null) {
                current.teleport(location);
                plugin.npcs().dress(current, type); // 名字跟上（2026-10：倉庫、拆解、傳送、市集只是擺設，沒有名字）
            } else {
                plugin.npcs().spawn(location, type);
                spawned++;
            }
        }
        if (plugin.quests() != null) {
            plugin.quests().placeStoryNpcs(); // 營地的劇情人物（區域裡的在區塊載入時補上）
        }
        return spawned;
    }

    /** 這個位置在劇情世界的營地座標系裡（給地圖模組算傳送門位置）。 */
    public Location at(final CampaignLayout.Spot spot) {
        return campaign == null ? null : new Location(campaign, spot.x(), spot.y(), spot.z(), spot.yaw(), 0f);
    }

    // ---- 邊界 ----

    /**
     * 每 2 秒：劇情世界裡跑出所有區域的玩家（爬上懸崖、游到外海）傳回去——在營地那一側（x < 40）回營地，
     * 其他的回最後碰到的檢查點；還在主世界（原本的大廳）的玩家送回營地。其他插件的世界不管。
     */
    private void guard() {
        final ZoneService zones = plugin.zones();
        if (campaign == null || zones == null) {
            return;
        }
        final World lobby = plugin.getServer().getRespawnWorld();
        for (final Player player : plugin.getServer().getOnlinePlayers()) {
            if (player.getGameMode() == GameMode.CREATIVE || player.getGameMode() == GameMode.SPECTATOR || player.isDead()
                    || !plugin.players().get(player).hasCharacter()) {
                outside.remove(player.getUniqueId());
                continue;
            }
            final World world = player.getWorld();
            final boolean inCampaign = world.equals(campaign);
            final boolean inChannel = channels.isChannel(world); // 2026-10-22 主城分流：分流世界只有營地，走出去就帶回這條線的營地
            // 管理員在劇情世界裡可以到處走（看懸崖、海上）；還在大廳的話一樣送去營地
            final boolean roamingAdmin = inCampaign && player.hasPermission(ExileCore.PERMISSION_ADMIN);
            if ((!inCampaign && !inChannel && !world.equals(lobby)) || roamingAdmin || zones.zoneAt(player.getLocation()).isPresent()) {
                outside.remove(player.getUniqueId());
                continue;
            }
            final int count = outside.merge(player.getUniqueId(), 1, Integer::sum);
            if (count < OUTSIDE_LIMIT) {
                continue;
            }
            outside.remove(player.getUniqueId());
            if (inChannel) {
                final Location back = TownChannels.mapTo(zones.campLocation(), world);
                if (back != null) {
                    player.teleportAsync(back);
                    player.sendMessage(plugin.text().prefixed("<gray>那邊是走不過去的懸崖與深海，帶你回去"));
                }
                continue;
            }
            Location back = inCampaign && player.getLocation().getX() >= CampaignLayout.ZONE_START_X ? zones.respawnLocation(player) : null;
            if (back == null || back.getWorld() == null || !back.getWorld().equals(campaign)) {
                back = zones.campLocationFor(player);
            }
            if (back != null && back.getWorld() != null && channels.isTown(back.getWorld())) {
                player.teleportAsync(back);
                player.sendMessage(plugin.text().prefixed(inCampaign ? "<gray>那邊是走不過去的懸崖與深海，帶你回去"
                        : "<gray>押送船靠岸了：送你到流放者營地"));
            }
        }
    }

    public void forget(final Player player) {
        outside.remove(player.getUniqueId());
    }
}
