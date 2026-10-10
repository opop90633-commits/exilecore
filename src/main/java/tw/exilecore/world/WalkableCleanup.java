package tw.exilecore.world;

import org.bukkit.Chunk;
import org.bukkit.ChunkSnapshot;
import org.bukkit.Material;
import org.bukkit.NamespacedKey;
import org.bukkit.Tag;
import org.bukkit.World;
import org.bukkit.block.data.Ageable;
import org.bukkit.block.data.BlockData;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.world.ChunkLoadEvent;
import org.bukkit.persistence.PersistentDataContainer;
import org.bukkit.persistence.PersistentDataType;
import org.bukkit.plugin.Plugin;
import tw.exilecore.worldgen.CampaignTerrain;
import tw.exilecore.worldgen.WebReach;

import java.util.ArrayList;
import java.util.List;

/**
 * 劇情世界的「拉腳」方塊（2026-10）：以前地上會長蜘蛛網（焚城廢墟的地上、幽語森林的 Boss 場地與斷柱上、
 * 黑橡樹垂下來比較低的）與甜莓叢（霜頂山脈），踩進去速度剩四分之一、或減速又扣血，跑起來像被拉住。
 * 現在的地形已經不放；區塊載入時再清一次：所有甜莓叢、碰得到的蜘蛛網（{@link WebReach}：自己或周圍八格底下 4 格內
 * 有站得上去的方塊，走路會撞到、跳起來頭會碰到；樹葉不算）換成空氣。
 * <p>
 * 先用區塊的方塊種類表（{@link Chunk#contains}）看有沒有這些方塊：沒有就什麼都不做（也不寫記號，不讓區塊多存一次）；
 * 有就只掃地表附近（周圍最高的方塊往上 4 格、這一格最高的方塊往下 {@link #SCAN_DEPTH} 格），清完記在區塊上（{@link #VERSION}），
 * 之後載入不再掃。剛產生的區塊也一樣掃：只產生到一半就存起來的舊區塊，補完時也算「剛產生」，裡面是舊的地形。
 */
final class WalkableCleanup implements Listener {

    /**
     * 清理的版本：之後要再清別的東西，就在 {@link #apply} 加一步「done &lt; 新版本」、這裡加一；
     * 清過舊版本的區塊只做新的那幾步。
     */
    static final int VERSION = 1;
    /** 從最高的方塊往下掃幾格：地上的裝飾、最高的樹（雲杉頂端在地面上 13 格）底下的地面都在這裡面。 */
    static final int SCAN_DEPTH = 20;

    private final Plugin plugin;
    private final World campaign;
    private final NamespacedKey key;
    /** 載入時清掉、還沒寫進主控台的格數（一分鐘最多記一次，不洗版）。 */
    private int unreported;
    private long lastReport;
    private final BlockData cobweb;
    private final List<BlockData> berries = new ArrayList<>();
    private final BlockData air;

    WalkableCleanup(final Plugin plugin, final World campaign) {
        this.plugin = plugin;
        this.campaign = campaign;
        this.key = new NamespacedKey(plugin, "walkable_cleanup");
        this.cobweb = Material.COBWEB.createBlockData();
        this.air = Material.AIR.createBlockData();
        for (int age = 0; age <= 3; age++) {
            final BlockData bush = Material.SWEET_BERRY_BUSH.createBlockData();
            if (bush instanceof Ageable ageable) {
                ageable.setAge(age);
            }
            berries.add(bush);
        }
    }

    /** 啟動時：已經載入的區塊先做一次；回傳清掉幾格。 */
    int applyLoaded() {
        int changed = 0;
        for (final Chunk chunk : campaign.getLoadedChunks()) {
            changed += apply(chunk);
        }
        return changed;
    }

    @EventHandler
    public void onLoad(final ChunkLoadEvent event) {
        if (!event.getWorld().equals(campaign)) {
            return;
        }
        unreported += apply(event.getChunk());
        final long now = System.currentTimeMillis();
        if (unreported > 0 && now - lastReport >= 60_000) {
            plugin.getSLF4JLogger().info("劇情世界：剛載入的區塊清掉 {} 格地上的蜘蛛網與甜莓叢", unreported);
            unreported = 0;
            lastReport = now;
        }
    }

    /** 這個區塊清過沒有；沒清過就清，回傳清掉幾格。 */
    int apply(final Chunk chunk) {
        final PersistentDataContainer marks = chunk.getPersistentDataContainer();
        final int done = marks.getOrDefault(key, PersistentDataType.INTEGER, 0);
        if (done >= VERSION) {
            return 0;
        }
        if (!contains(chunk)) {
            return 0; // 沒有這些方塊：不寫記號，下次載入再看一次種類表（很便宜）
        }
        int changed = 0;
        if (done < 1) {
            changed += clean(chunk); // 第 1 版：碰得到的蜘蛛網、甜莓叢
        }
        marks.set(key, PersistentDataType.INTEGER, VERSION);
        return changed;
    }

    private boolean contains(final Chunk chunk) {
        if (chunk.contains(cobweb)) {
            return true;
        }
        for (final BlockData bush : berries) {
            if (chunk.contains(bush)) {
                return true;
            }
        }
        return false;
    }

    private int clean(final Chunk chunk) {
        final ChunkSnapshot snapshot = chunk.getChunkSnapshot(true, false, false, false);
        final int minHeight = campaign.getMinHeight();
        final int maxHeight = campaign.getMaxHeight();
        final ChunkBlocks blocks = new ChunkBlocks(snapshot, chunk.getX() << 4, chunk.getZ() << 4, minHeight, maxHeight);
        // 高度圖只算擋路的方塊（樹葉算；蜘蛛網、甜莓叢不算）
        final int[][] highest = new int[16][16];
        for (int x = 0; x < 16; x++) {
            for (int z = 0; z < 16; z++) {
                highest[x][z] = snapshot.getHighestBlockYAt(x, z);
            }
        }
        int changed = 0;
        for (int x = 0; x < 16; x++) {
            for (int z = 0; z < 16; z++) {
                // 碰得到的蜘蛛網：自己或隔壁那一格底下 4 格內有站得上去的方塊，一定在周圍最高的方塊往上 4 格以內
                final int top = Math.min(maxHeight - 1, highestAround(blocks, highest, x, z) + WebReach.REACH);
                final int bottom = Math.max(minHeight + 1, highest[x][z] - SCAN_DEPTH);
                for (int y = top; y >= bottom; y--) {
                    final Material type = snapshot.getBlockType(x, y, z);
                    final boolean remove = type == Material.SWEET_BERRY_BUSH
                            || type == Material.COBWEB && WebReach.reachable(blocks, blocks.baseX() + x, y, blocks.baseZ() + z);
                    if (remove) {
                        chunk.getBlock(x, y, z).setBlockData(air, false);
                        changed++;
                    }
                }
            }
        }
        return changed;
    }

    /** 這一欄與周圍八欄最高的方塊；隔壁區塊的欄（快照裡沒有）看地形的高度。 */
    private static int highestAround(final ChunkBlocks blocks, final int[][] highest, final int x, final int z) {
        int best = Integer.MIN_VALUE;
        for (int dx = -1; dx <= 1; dx++) {
            for (int dz = -1; dz <= 1; dz++) {
                final int nx = x + dx;
                final int nz = z + dz;
                best = Math.max(best, nx >= 0 && nx < 16 && nz >= 0 && nz < 16 ? highest[nx][nz]
                        : CampaignTerrain.top(blocks.baseX() + nx, blocks.baseZ() + nz));
            }
        }
        return best;
    }

    /**
     * 給 {@link WebReach} 查方塊：這個區塊的看快照；隔壁區塊的（快照裡沒有，也不為了這個去載入）只看地形的高度——
     * 那一欄地表以下是實心、以上是空的（大石頭、倒木看不到）。
     */
    private record ChunkBlocks(ChunkSnapshot snapshot, int baseX, int baseZ, int minHeight, int maxHeight)
            implements WebReach.Blocks {

        @Override
        public boolean solid(final int x, final int y, final int z) {
            if (y < minHeight || y >= maxHeight) {
                return false;
            }
            final int lx = x - baseX;
            final int lz = z - baseZ;
            if (lx < 0 || lx > 15 || lz < 0 || lz > 15) {
                return y <= CampaignTerrain.top(x, z);
            }
            return snapshot.getBlockType(lx, y, lz).isSolid();
        }

        @Override
        public boolean leaves(final int x, final int y, final int z) {
            final int lx = x - baseX;
            final int lz = z - baseZ;
            if (y < minHeight || y >= maxHeight || lx < 0 || lx > 15 || lz < 0 || lz > 15) {
                return false;
            }
            return Tag.LEAVES.isTagged(snapshot.getBlockType(lx, y, lz));
        }
    }
}
