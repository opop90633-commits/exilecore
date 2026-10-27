package tw.exilecore.map;

import org.bukkit.Chunk;
import org.bukkit.ChunkSnapshot;
import org.bukkit.Material;
import org.bukkit.World;
import org.bukkit.block.data.BlockData;
import org.bukkit.entity.Entity;
import org.bukkit.entity.Player;
import org.bukkit.plugin.Plugin;
import org.slf4j.Logger;
import tw.exilecore.world.BlockPalette;
import tw.exilecore.worldgen.BufferSink;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.List;
import java.util.concurrent.CompletableFuture;

/**
 * 地圖世界的施工隊：蓋地圖（把 {@link BufferSink} 的方塊分幾個 tick 放進世界）與清格子（把一塊範圍裡的方塊清成空氣），
 * 每個 tick 有方塊數上限，不會卡伺服器。要用到的區塊先非同步載入並加上插件票證，做完才放掉。
 */
final class MapWorks {

    /** 每個 tick 最多放幾個方塊（清格子時算讀取的格數）。 */
    static final int BLOCKS_PER_TICK = 30_000;
    /**
     * 每個 tick 最多花多少時間（2026-10 效能：以前只看方塊數，3 萬格一口氣放下去會卡上百毫秒，玩家感覺到「頓一下」；
     * 現在到時間就停，下一個 tick 接著做，地圖晚一兩秒蓋好，換來不卡）。
     */
    static final long NANOS_PER_TICK = 8_000_000L;
    /** 放幾格看一次時間。 */
    static final int CHECK_EVERY = 256;

    /** 一件工作。 */
    abstract static class Job {
        final World world;
        final int minX;
        final int minZ;
        final int maxX;
        final int maxZ;
        final Runnable done;
        boolean ready;
        boolean loading;
        boolean failed;
        /** 載入區塊失敗後重試了幾次（2026-10-17）。 */
        int retries;
        final List<Chunk> chunks = new ArrayList<>();

        Job(final World world, final int minX, final int minZ, final int maxX, final int maxZ, final Runnable done) {
            this.world = world;
            this.minX = minX;
            this.minZ = minZ;
            this.maxX = maxX;
            this.maxZ = maxZ;
            this.done = done;
        }

        /** 這個 tick 最晚做到什麼時候（System.nanoTime）。 */
        long deadline = Long.MAX_VALUE;

        /** @return 用掉多少額度；做完時回傳負數 */
        abstract int step(int budget);

        /** 動方塊之前先做的事（清格子：移掉範圍裡的實體）。在尋路清單拿開之前呼叫。 */
        void prepare() {
        }

        boolean late() {
            return System.nanoTime() > deadline;
        }
    }

    private final Plugin plugin;
    private final Logger logger;
    private final Deque<Job> jobs = new ArrayDeque<>();

    MapWorks(final Plugin plugin, final Logger logger) {
        this.plugin = plugin;
        this.logger = logger;
    }

    /** 蓋地圖：done 在全部放完之後（主執行緒）呼叫。 */
    void build(final World world, final BufferSink blocks, final int minX, final int minZ, final int maxX, final int maxZ, final Runnable done) {
        blocks.compact();
        buildCompacted(world, blocks, minX, minZ, maxX, maxZ, done);
    }

    /** 蓋地圖：blocks 已經 compact 過（2026-10：在背景執行緒做好，主執行緒只排工作）。 */
    void buildCompacted(final World world, final BufferSink blocks, final int minX, final int minZ, final int maxX, final int maxZ,
                        final Runnable done) {
        jobs.add(new BuildJob(world, blocks, minX, minZ, maxX, maxZ, done));
    }

    /** 清格子：範圍內 y 在 [minY, maxY] 的方塊全部變空氣，玩家以外的實體移除。 */
    void clear(final World world, final int minX, final int minY, final int minZ, final int maxX, final int maxY, final int maxZ, final Runnable done) {
        jobs.add(new ClearJob(world, minX, minY, minZ, maxX, maxY, maxZ, done));
    }

    boolean idle() {
        return jobs.isEmpty();
    }

    int pending() {
        return jobs.size();
    }

    /** 每個 tick：處理排在最前面的工作。 */
    void tick() {
        final Job first = jobs.peek();
        if (first == null) {
            return;
        }
        if (first.ready) {
            first.prepare();
        }
        NavigationPause.run(first.world, logger, this::work);
    }

    /** 2026-10-27：伺服器已經很忙（平均每 tick 超過這麼多毫秒）時，施工的時間額度改成 {@link #BUSY_NANOS_PER_TICK}。 */
    static final double BUSY_MSPT = 40;
    static final long BUSY_NANOS_PER_TICK = 3_500_000L;

    /** 2026-10-27：這個 tick 施工可以花多少時間（奈秒）：平常 8 毫秒，伺服器忙（平均超過 40 毫秒）時 3.5 毫秒，地圖晚一點蓋好但不會把 TPS 拖下去。 */
    static long budgetNanos(final double averageTickMillis) {
        return averageTickMillis > BUSY_MSPT ? BUSY_NANOS_PER_TICK : NANOS_PER_TICK;
    }

    private void work() {
        double mspt = 0;
        try {
            mspt = plugin.getServer().getAverageTickTime();
        } catch (final RuntimeException | LinkageError ignored) {
            // 不是 Paper：照平常的額度
        }
        final long deadline = System.nanoTime() + budgetNanos(mspt);
        int budget = BLOCKS_PER_TICK;
        while (budget > 0 && !jobs.isEmpty() && System.nanoTime() < deadline) {
            final Job job = jobs.peek();
            if (!job.ready) {
                if (!job.loading) {
                    load(job);
                }
                return; // 等區塊載入
            }
            job.deadline = deadline;
            if (job.ready) {
                job.prepare();
            }
            final int used = job.step(budget);
            if (used < 0) {
                jobs.poll();
                for (final Chunk chunk : job.chunks) {
                    chunk.removePluginChunkTicket(plugin);
                }
                try {
                    if (job.failed && job instanceof ClearJob) {
                        // 2026-10-18：有區塊沒載入、沒清乾淨：不要當成清好釋放格子（留在 dirty，下次啟動再清）
                        logger.warn("地圖世界清格子時有區塊沒載入，這一格先不釋放，下次啟動再清");
                    } else if (job.done != null) {
                        job.done.run();
                    }
                } catch (final RuntimeException e) {
                    logger.error("地圖施工完成後的處理失敗", e);
                }
                budget -= 1000;
            } else {
                budget -= Math.max(1, used);
            }
        }
    }

    /** 載入區塊失敗最多重試幾次。 */
    private static final int MAX_LOAD_RETRIES = 3;

    private void load(final Job job) {
        job.loading = true;
        final List<CompletableFuture<Chunk>> futures = new ArrayList<>();
        for (int cx = job.minX >> 4; cx <= job.maxX >> 4; cx++) {
            for (int cz = job.minZ >> 4; cz <= job.maxZ >> 4; cz++) {
                futures.add(job.world.getChunkAtAsync(cx, cz));
            }
        }
        CompletableFuture.allOf(futures.toArray(new CompletableFuture<?>[0])).whenComplete((ignored, error) ->
                plugin.getServer().getScheduler().runTask(plugin, () -> {
                    if (error != null) {
                        // 2026-10-17：載入失敗重試（最多 3 次，每次隔 1 秒）；以前標了失敗就照樣施工，沒載入的區塊會在主執行緒同步載入（卡頓）
                        if (job.retries < MAX_LOAD_RETRIES) {
                            job.retries++;
                            logger.warn("地圖世界載入區塊失敗，{} 秒後重試（第 {} 次）：{}", 1, job.retries, error.toString());
                            plugin.getServer().getScheduler().runTaskLater(plugin, () -> job.loading = false, 20);
                            return;
                        }
                        logger.error("地圖世界載入區塊失敗（重試 {} 次都不行，照樣施工）", MAX_LOAD_RETRIES, error);
                        job.failed = true;
                    }
                    job.chunks.clear();
                    for (final CompletableFuture<Chunk> future : futures) {
                        if (!future.isCompletedExceptionally()) {
                            final Chunk chunk = future.join();
                            chunk.addPluginChunkTicket(plugin);
                            job.chunks.add(chunk);
                        }
                    }
                    job.ready = true;
                }));
    }

    /** 蓋：依序放方塊（已經照區塊排好）。 */
    private static final class BuildJob extends Job {
        private final BufferSink blocks;
        private int next;

        BuildJob(final World world, final BufferSink blocks, final int minX, final int minZ, final int maxX, final int maxZ, final Runnable done) {
            super(world, minX, minZ, maxX, maxZ, done);
            this.blocks = blocks;
        }

        @Override
        int step(final int budget) {
            final int end = Math.min(blocks.size(), next + budget);
            int i = next;
            while (i < end) {
                final BlockData data = BlockPalette.get(blocks.block(i));
                world.getBlockAt(blocks.x(i), blocks.y(i), blocks.z(i)).setBlockData(data, false);
                i++;
                if ((i - next) % CHECK_EVERY == 0 && late()) {
                    break; // 這個 tick 的時間用完了：下一個 tick 接著放
                }
            }
            final int used = i - next;
            next = i;
            return next >= blocks.size() ? -1 : Math.max(1, used);
        }
    }

    /** 清：一個區塊一個區塊，只看有方塊的區段。 */
    private static final class ClearJob extends Job {
        private final int minY;
        private final int maxY;
        private int chunkIndex;
        private boolean entitiesRemoved;

        ClearJob(final World world, final int minX, final int minY, final int minZ, final int maxX, final int maxY, final int maxZ,
                 final Runnable done) {
            super(world, minX, minZ, maxX, maxZ, done);
            this.minY = minY;
            this.maxY = maxY;
        }

        @Override
        void prepare() {
            if (!entitiesRemoved) {
                entitiesRemoved = true;
                for (final Chunk chunk : chunks) {
                    for (final Entity entity : chunk.getEntities()) {
                        if (!(entity instanceof Player) && inside(entity.getLocation().getBlockX(), entity.getLocation().getBlockZ())) {
                            entity.remove();
                        }
                    }
                }
            }
        }

        /** 2026-10-27：正在清的區塊做到第幾柱（0～255）與它的快照：一個區塊可以分幾個 tick 清完（這次之前一個區塊一定一次清完，會超過時間額度）。 */
        private int column;
        private ChunkSnapshot snapshot;

        @Override
        int step(final int budget) {
            prepare();
            int used = 0;
            while (chunkIndex < chunks.size() && used < budget && (used == 0 || !late())) {
                final Chunk chunk = chunks.get(chunkIndex);
                if (snapshot == null) {
                    // 整段高度都看（不能只看高度圖：燈籠、蜘蛛網、花草這種不擋路的方塊不算在高度圖裡）
                    snapshot = chunk.getChunkSnapshot(false, false, false);
                    column = 0;
                    used += 16;
                }
                used += clearColumn(chunk, column);
                column++;
                if (column >= 256) {
                    snapshot = null;
                    chunkIndex++;
                }
            }
            return chunkIndex >= chunks.size() ? -1 : Math.max(1, used);
        }

        private boolean inside(final int x, final int z) {
            return x >= minX && x <= maxX && z >= minZ && z <= maxZ;
        }

        /** 清一柱（區塊裡的第 index 柱：dx = index / 16、dz = index % 16），回傳讀了幾格。 */
        private int clearColumn(final Chunk chunk, final int index) {
            final int dx = index >> 4;
            final int dz = index & 15;
            if (!inside((chunk.getX() << 4) + dx, (chunk.getZ() << 4) + dz)) {
                return 0;
            }
            final int bottom = Math.max(minY, world.getMinHeight());
            final int top = Math.min(maxY, world.getMaxHeight() - 1);
            int read = 0;
            for (int y = bottom; y <= top; y++) {
                read++;
                if (!snapshot.getBlockType(dx, y, dz).isAir()) {
                    chunk.getBlock(dx, y, dz).setType(Material.AIR, false);
                }
            }
            return read;
        }
    }
}
