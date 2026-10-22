package tw.exilecore.world;

import org.bukkit.Chunk;
import org.bukkit.Location;
import org.bukkit.NamespacedKey;
import org.bukkit.World;
import org.bukkit.entity.Entity;
import org.bukkit.entity.EntitySnapshot;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.world.EntitiesLoadEvent;
import org.bukkit.persistence.PersistentDataType;
import tw.exilecore.ExileCore;
import tw.exilecore.worldgen.CampaignLayout;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CompletableFuture;

/**
 * 主城分流（2026-10-22）：營地（流放者營地）複製成好幾條線（{@code exilecore:town_2}、{@code town_3}…），
 * 每個世界的營地座標一模一樣（NPC、傳送門、落點都用同樣的座標，只是換一個世界）。
 * 人多時擠在同一個營地，伺服器每刻要對「每個實體 × 附近每個玩家」追蹤一次，分成幾條線就少好幾倍。
 * <ul>
 *   <li>第一次建立分流世界時，把劇情世界營地那一帶的區塊檔（region、entities、poi）直接複製過去：
 *       手動蓋的方塊、擺的裝飾（盔甲座、物品展示框）都跟著過去；之後各條線各自存檔。</li>
 *   <li>複製來的實體 UUID 跟劇情世界的一樣：第一次啟動時把營地範圍裡的實體換成新的（UUID 換掉，其他一樣），
 *       範圍外的（戰鬥區域的怪物）拿掉；之後區塊載入時範圍外的實體也一律拿掉（分流世界只有營地）。</li>
 *   <li>玩家最後待的那條線記在玩家身上（PDC）：從地圖、競技場回來、/town、重生都回同一條線；
 *       那條線比最少的那條多太多人時換到最少的。</li>
 *   <li>{@code data/world.yml} 的 {@code campaign.channels}：幾條線（1 = 不分流）。改完要重開伺服器。</li>
 *   <li>想重新從劇情世界複製（主城改過）：/ec world channels rebuild，下次啟動時刪掉分流世界重做。</li>
 * </ul>
 * 第 1 線就是劇情世界本身（戰鬥區域也在那裡）。
 */
public final class TownChannels implements Listener {

    /** 最多幾條線。 */
    public static final int MAX_CHANNELS = 9;
    /** 分流世界資料夾裡的記號檔（有 = 第一次的整理（換 UUID、清範圍外的實體）做過了）。 */
    private static final String MARKER = "exilecore-town-channel.txt";
    /** 插件資料夾裡的記號檔（有 = 下次啟動時把分流世界刪掉，從劇情世界重新複製）。 */
    static final String REBUILD_FLAG = "town-channels-rebuild.txt";
    /** 營地範圍外再多複製、整理幾個區塊（要蓋過視距：站在營地邊緣看得到的都要有）。 */
    private static final int MARGIN_CHUNKS = 13;

    private final ExileCore plugin;
    private final NamespacedKey channelKey;
    /** 所有營地世界：第 0 個是劇情世界（第 1 線）。 */
    private final List<World> towns = new ArrayList<>();

    TownChannels(final ExileCore plugin) {
        this.plugin = plugin;
        this.channelKey = new NamespacedKey(plugin, "town_channel");
    }

    static NamespacedKey key(final int channel) {
        return new NamespacedKey("exilecore", "town_" + channel);
    }

    /**
     * 分流世界的資料夾：跟劇情世界的資料夾同一層、同樣的命名（Paper 照「命名空間_代號」取名：exilecore_campaign → exilecore_town_2）。
     * 載入之後會核對（{@link #add}）；對不上只是沒複製到手動蓋的東西（世界照樣能用）。
     */
    private static Path folder(final World campaign, final int channel) {
        final String base = campaign.getWorldPath().getFileName().toString();
        final String name = base.endsWith("campaign") ? base.substring(0, base.length() - "campaign".length()) + "town_" + channel
                : "exilecore_town_" + channel;
        return campaign.getWorldPath().resolveSibling(name);
    }

    // ---- 啟動 ----

    /** 劇情世界載入之後：要重做的先刪掉資料夾；還沒有的從劇情世界複製營地那一帶的區塊檔。回傳要載入的分流編號。 */
    List<Integer> prepare(final World campaign, final int channels) {
        towns.clear();
        towns.add(campaign);
        final List<Integer> wanted = new ArrayList<>();
        if (channels <= 1) {
            return wanted;
        }
        final Path flag = plugin.dataFolder().resolve(REBUILD_FLAG);
        final boolean rebuild = Files.exists(flag);
        for (int channel = 2; channel <= Math.min(MAX_CHANNELS, channels); channel++) {
            final Path folder = folder(campaign, channel);
            try {
                if (rebuild && Files.exists(folder) && plugin.getServer().getWorld(key(channel)) == null) {
                    deleteTree(folder);
                    plugin.getSLF4JLogger().info("主城分流：第 {} 線的世界刪掉了，從劇情世界重新複製", channel);
                }
                if (!Files.exists(folder.resolve("level.dat"))) {
                    final int copied = copyRegions(campaign.getWorldPath(), folder);
                    plugin.getSLF4JLogger().info("主城分流：第 {} 線是新的，從劇情世界複製了 {} 個區塊檔", channel, copied);
                }
                wanted.add(channel);
            } catch (final IOException e) {
                plugin.getSLF4JLogger().error("主城分流：第 {} 線的世界準備失敗（這條線不開）", channel, e);
            }
        }
        if (rebuild) {
            try {
                Files.deleteIfExists(flag);
            } catch (final IOException e) {
                plugin.getSLF4JLogger().warn("刪 {} 失敗", flag, e);
            }
        }
        return wanted;
    }

    /** 一個分流世界載入好了：登記、第一次的話整理實體。 */
    void add(final World world, final int channel) {
        if (!towns.contains(world)) {
            towns.add(world);
        }
        if (channelOf(world) != channel) {
            plugin.getSLF4JLogger().warn("主城分流：{} 本來是第 {} 線，前面有一條線沒載入，現在算第 {} 線", world.getName(), channel, channelOf(world));
        }
        if (!Files.exists(world.getWorldPath().resolve(MARKER))) {
            final Path expected = folder(towns.getFirst(), channel);
            if (!world.getWorldPath().toAbsolutePath().normalize().equals(expected.toAbsolutePath().normalize())) {
                plugin.getSLF4JLogger().warn("主城分流：{} 的資料夾是 {}，不是預期的 {}：營地的區塊檔沒有複製到（手動蓋的東西不會出現在這條線）",
                        world.getName(), world.getWorldPath(), expected);
            }
            firstTimeSetup(world);
        }
    }

    /** 營地那一帶（含視距的邊）的區塊範圍（區塊座標）。 */
    private static int[] chunkBounds() {
        return new int[] {
                (CampaignLayout.CAMP_BOX_MIN_X >> 4) - MARGIN_CHUNKS, (CampaignLayout.CAMP_BOX_MIN_Z >> 4) - MARGIN_CHUNKS,
                (CampaignLayout.CAMP_BOX_MAX_X >> 4) + MARGIN_CHUNKS, (CampaignLayout.CAMP_BOX_MAX_Z >> 4) + MARGIN_CHUNKS};
    }

    /** 把劇情世界營地那一帶的區塊檔複製到新世界的資料夾（region、entities、poi 各一份）；回傳複製了幾個檔。 */
    private static int copyRegions(final Path source, final Path target) throws IOException {
        final int[] bounds = chunkBounds();
        final int minRegionX = Math.floorDiv(bounds[0], 32);
        final int minRegionZ = Math.floorDiv(bounds[1], 32);
        final int maxRegionX = Math.floorDiv(bounds[2], 32);
        final int maxRegionZ = Math.floorDiv(bounds[3], 32);
        int copied = 0;
        for (final String kind : new String[] {"region", "entities", "poi"}) {
            final Path from = source.resolve(kind);
            if (!Files.isDirectory(from)) {
                continue;
            }
            final Path to = target.resolve(kind);
            Files.createDirectories(to);
            for (int rx = minRegionX; rx <= maxRegionX; rx++) {
                for (int rz = minRegionZ; rz <= maxRegionZ; rz++) {
                    final String name = "r." + rx + "." + rz + ".mca";
                    final Path file = from.resolve(name);
                    if (Files.isRegularFile(file)) {
                        Files.copy(file, to.resolve(name), StandardCopyOption.REPLACE_EXISTING);
                        copied++;
                    }
                }
            }
        }
        return copied;
    }

    private static void deleteTree(final Path root) throws IOException {
        if (!Files.exists(root)) {
            return;
        }
        try (var walk = Files.walk(root)) {
            final List<Path> paths = walk.sorted(java.util.Comparator.reverseOrder()).toList();
            for (final Path path : paths) {
                Files.deleteIfExists(path);
            }
        }
    }

    /**
     * 第一次載入：營地那一帶的區塊先載入，範圍裡的實體換成新的（UUID 換掉：複製來的跟劇情世界的一樣，
     * 打假人的紀錄、召喚物的主人這些照 UUID 認會混在一起），範圍外的拿掉；做完寫記號檔。
     */
    private void firstTimeSetup(final World world) {
        final int[] bounds = chunkBounds();
        final List<CompletableFuture<Chunk>> futures = new ArrayList<>();
        for (int cx = bounds[0]; cx <= bounds[2]; cx++) {
            for (int cz = bounds[1]; cz <= bounds[3]; cz++) {
                futures.add(world.getChunkAtAsync(cx, cz));
            }
        }
        CompletableFuture.allOf(futures.toArray(new CompletableFuture<?>[0])).whenComplete((ignored, error) ->
                plugin.getServer().getScheduler().runTask(plugin, () -> {
                    if (error != null) {
                        plugin.getSLF4JLogger().error("主城分流：{} 的區塊載入失敗（下次啟動再整理）", world.getName(), error);
                        return;
                    }
                    final List<Chunk> chunks = new ArrayList<>();
                    for (final CompletableFuture<Chunk> future : futures) {
                        final Chunk chunk = future.join();
                        chunk.addPluginChunkTicket(plugin);
                        chunks.add(chunk);
                    }
                    // 實體跟區塊分開載入：等兩秒再整理
                    plugin.getServer().getScheduler().runTaskLater(plugin, () -> {
                        int replaced = 0;
                        int removed = 0;
                        for (final Chunk chunk : chunks) {
                            if (!chunk.isEntitiesLoaded()) {
                                continue;
                            }
                            for (final Entity entity : chunk.getEntities()) {
                                if (entity instanceof Player || !entity.isValid()) {
                                    continue;
                                }
                                if (!inCampBox(entity.getLocation())) {
                                    entity.remove();
                                    removed++;
                                    continue;
                                }
                                if (reissue(entity)) {
                                    replaced++;
                                }
                            }
                        }
                        for (final Chunk chunk : chunks) {
                            chunk.removePluginChunkTicket(plugin);
                        }
                        try {
                            Files.writeString(world.getWorldPath().resolve(MARKER),
                                    "ExileCore 主城分流：從劇情世界複製來的營地（刪掉這個檔，下次啟動會再整理一次實體）\n");
                        } catch (final IOException e) {
                            plugin.getSLF4JLogger().warn("寫入 {} 的記號檔失敗", world.getName(), e);
                        }
                        plugin.getSLF4JLogger().info("主城分流：{} 整理好了（實體換新 {} 個、營地外的拿掉 {} 個）", world.getName(), replaced, removed);
                    }, 40);
                }));
    }

    /** 把一個實體換成一模一樣的新實體（新的 UUID）；換不了回傳 false（原本的留著）。 */
    private boolean reissue(final Entity entity) {
        if (!entity.getPassengers().isEmpty() || entity.isInsideVehicle()) {
            return false; // 疊在一起的不動（很少見）
        }
        try {
            final EntitySnapshot snapshot = entity.createSnapshot();
            if (snapshot == null) {
                return false;
            }
            final Location at = entity.getLocation();
            entity.remove();
            snapshot.createEntity(at);
            return true;
        } catch (final RuntimeException e) {
            plugin.getSLF4JLogger().debug("主城分流：{} 換不了新實體：{}", entity.getType(), e.toString());
            return false;
        }
    }

    /** 分流世界只有營地：載入的區塊裡在營地範圍外的實體一律拿掉（複製來的戰鬥區域的怪、掉落物）。 */
    @EventHandler(priority = EventPriority.MONITOR)
    public void onEntitiesLoad(final EntitiesLoadEvent event) {
        if (!isChannel(event.getWorld())) {
            return;
        }
        List<Entity> strays = null;
        for (final Entity entity : event.getEntities()) {
            if (!(entity instanceof Player) && !inCampBox(entity.getLocation())) {
                if (strays == null) {
                    strays = new ArrayList<>();
                }
                strays.add(entity);
            }
        }
        if (strays == null) {
            return;
        }
        final List<Entity> remove = strays;
        plugin.getServer().getScheduler().runTask(plugin, () -> {
            for (final Entity entity : remove) {
                if (entity.isValid()) {
                    entity.remove();
                }
            }
        });
    }

    static boolean inCampBox(final Location location) {
        final double x = location.getX();
        final double z = location.getZ();
        return x >= CampaignLayout.CAMP_BOX_MIN_X && x < CampaignLayout.CAMP_BOX_MAX_X + 1
                && z >= CampaignLayout.CAMP_BOX_MIN_Z && z < CampaignLayout.CAMP_BOX_MAX_Z + 1;
    }

    // ---- 查詢 ----

    /** 所有營地世界（第 0 個是劇情世界）。 */
    public List<World> towns() {
        return List.copyOf(towns);
    }

    /** 有分流（兩條線以上）。 */
    public boolean enabled() {
        return towns.size() > 1;
    }

    /** 這個世界是營地的一條線（含劇情世界）。 */
    public boolean isTown(final World world) {
        return world != null && towns.contains(world);
    }

    /** 這個世界是分流世界（不含劇情世界）。 */
    public boolean isChannel(final World world) {
        return world != null && towns.size() > 1 && towns.indexOf(world) > 0;
    }

    /** 第幾線（1 起算）；不是營地世界回傳 0。 */
    public int channelOf(final World world) {
        final int index = world == null ? -1 : towns.indexOf(world);
        return index < 0 ? 0 : index + 1;
    }

    /** 第 n 線的世界；沒有這條線回傳 null。 */
    public World world(final int channel) {
        return channel >= 1 && channel <= towns.size() ? towns.get(channel - 1) : null;
    }

    /** 同一個座標換到另一個營地世界。 */
    public static Location mapTo(final Location location, final World world) {
        if (location == null || world == null) {
            return location;
        }
        return new Location(world, location.getX(), location.getY(), location.getZ(), location.getYaw(), location.getPitch());
    }

    /** 這條線現在幾個人（整個世界的人；劇情世界是營地裡的人）。 */
    public int population(final World world) {
        if (!isChannel(world)) {
            int count = 0;
            for (final Player player : world.getPlayers()) {
                if (inCampBox(player.getLocation())) {
                    count++;
                }
            }
            return count;
        }
        return world.getPlayerCount();
    }

    /** 人最少的那條線。 */
    public World leastCrowded() {
        World best = towns.getFirst();
        int bestCount = Integer.MAX_VALUE;
        for (final World world : towns) {
            final int count = population(world);
            if (count < bestCount) {
                best = world;
                bestCount = count;
            }
        }
        return best;
    }

    /**
     * 這個玩家該去哪條線：現在就在營地世界 → 同一條；記得上次的那條 → 那條（2026-10-22：人多也不換走）；沒記過 → 人最少的。
     * 回傳的一定是營地世界（沒有分流就是劇情世界）。
     */
    public World pick(final Player player) {
        if (towns.isEmpty()) {
            return null;
        }
        if (!enabled()) {
            return towns.getFirst();
        }
        if (isTown(player.getWorld())) {
            return player.getWorld();
        }
        final Integer remembered = player.getPersistentDataContainer().get(channelKey, PersistentDataType.INTEGER);
        if (player.hasPermission(ExileCore.PERMISSION_ADMIN)) {
            // 管理員預設第 1 線（劇情世界，可以改建的那條）；用 /channel 換過就照他換的，不管擠不擠
            final World wanted = remembered == null ? null : world(remembered);
            return wanted != null ? wanted : towns.getFirst();
        }
        final World wanted = remembered == null ? null : world(remembered);
        return wanted != null ? wanted : leastCrowded(); // 2026-10-22：玩家自己選的線，人再多也不拉走
    }

    /** 記住的線（1 起算）；沒記過回傳 0。 */
    public int remembered(final Player player) {
        final Integer value = player.getPersistentDataContainer().get(channelKey, PersistentDataType.INTEGER);
        return value == null ? 0 : value;
    }

    /** 記住這個玩家在哪條線（每 10 tick 由區域模組呼叫；/channel 換線也記）。 */
    public void remember(final Player player, final World world) {
        final int channel = channelOf(world);
        if (channel <= 0) {
            return;
        }
        final Integer current = player.getPersistentDataContainer().get(channelKey, PersistentDataType.INTEGER);
        if (current == null || current != channel) {
            player.getPersistentDataContainer().set(channelKey, PersistentDataType.INTEGER, channel);
        }
    }

    /** 標記下次啟動時重做分流世界。 */
    void markRebuild() throws IOException {
        Files.writeString(plugin.dataFolder().resolve(REBUILD_FLAG), "下次啟動時刪掉分流世界（exilecore_town_2…），從劇情世界重新複製營地\n");
    }
}
