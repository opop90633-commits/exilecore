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
        final List<Chunk> chunks = new ArrayList<>();

        Job(final World world, final int minX, final int minZ, final int maxX, final int maxZ, final Runnable done) {
            this.world = world;
            this.minX = minX;
            this.minZ = minZ;
            this.maxX = maxX;
            this.maxZ = maxZ;
            this.done = done;
        }

        /** @return 用掉多少額度；做完時回傳負數 */
        abstract int step(int budget);
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
        int budget = BLOCKS_PER_TICK;
        while (budget > 0 && !jobs.isEmpty()) {
            final Job job = jobs.peek();
            if (!job.ready) {
                if (!job.loading) {
                    load(job);
                }
                return; // 等區塊載入
            }
            final int used = job.step(budget);
            if (used < 0) {
                jobs.poll();
                for (final Chunk chunk : job.chunks) {
                    chunk.removePluginChunkTicket(plugin);
                }
                try {
                    if (job.done != null) {
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
                        logger.error("地圖世界載入區塊失敗", error);
                        job.failed = true;
                    }
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
            for (int i = next; i < end; i++) {
                final BlockData data = BlockPalette.get(blocks.block(i));
                world.getBlockAt(blocks.x(i), blocks.y(i), blocks.z(i)).setBlockData(data, false);
            }
            final int used = end - next;
            next = end;
            return next >= blocks.size() ? -1 : used;
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
        int step(final int budget) {
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
            int used = 0;
            while (chunkIndex < chunks.size() && used < budget) {
                used += clearChunk(chunks.get(chunkIndex));
                chunkIndex++;
            }
            return chunkIndex >= chunks.size() ? -1 : used;
        }

        private boolean inside(final int x, final int z) {
            return x >= minX && x <= maxX && z >= minZ && z <= maxZ;
        }

        private int clearChunk(final Chunk chunk) {
            // 整段高度都看（不能只看高度圖：燈籠、蜘蛛網、花草這種不擋路的方塊不算在高度圖裡）
            final ChunkSnapshot snapshot = chunk.getChunkSnapshot(false, false, false);
            final int baseX = chunk.getX() << 4;
            final int baseZ = chunk.getZ() << 4;
            final int bottom = Math.max(minY, world.getMinHeight());
            final int top = Math.min(maxY, world.getMaxHeight() - 1);
            int read = 16;
            for (int dx = 0; dx < 16; dx++) {
                for (int dz = 0; dz < 16; dz++) {
                    if (!inside(baseX + dx, baseZ + dz)) {
                        continue;
                    }
                    for (int y = bottom; y <= top; y++) {
                        read++;
                        if (!snapshot.getBlockType(dx, y, dz).isAir()) {
                            chunk.getBlock(dx, y, dz).setType(Material.AIR, false);
                        }
                    }
                }
            }
            return read;
        }
    }
}
