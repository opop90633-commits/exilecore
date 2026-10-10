package tw.exilecore.world;

import org.bukkit.Chunk;
import org.bukkit.World;
import org.bukkit.block.Block;
import org.bukkit.block.data.BlockData;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.world.ChunkLoadEvent;
import tw.exilecore.worldgen.B;
import tw.exilecore.worldgen.RoadGuides;

import java.util.List;

/**
 * 劇情世界的路標（{@link RoadGuides}）：區塊載入時，把那個區塊裡的路面換成發光的箭頭。
 * 新世界、舊世界（路標是後來才加的）都一樣：已經是箭頭的不動，所以每次載入再做一次也沒關係。
 */
final class RoadGuideListener implements Listener {

    private final World campaign;

    RoadGuideListener(final World campaign) {
        this.campaign = campaign;
    }

    /** 啟動時：已經載入的區塊先做一次。 */
    void applyLoaded() {
        for (final Chunk chunk : campaign.getLoadedChunks()) {
            apply(chunk);
        }
    }

    @EventHandler
    public void onLoad(final ChunkLoadEvent event) {
        if (event.getWorld().equals(campaign)) {
            apply(event.getChunk());
        }
    }

    /** @return 換了幾格 */
    int apply(final Chunk chunk) {
        final List<RoadGuides.Cell> cells = RoadGuides.byChunk().get(RoadGuides.chunkKey(chunk.getX(), chunk.getZ()));
        if (cells == null) {
            return 0;
        }
        final BlockData guide = BlockPalette.get(B.GUIDE);
        int changed = 0;
        for (final RoadGuides.Cell cell : cells) {
            final Block block = campaign.getBlockAt(cell.x(), cell.y(), cell.z());
            if (block.getType() == guide.getMaterial() || block.getType().isAir()) {
                continue; // 已經是路標；或那裡是空的（地形跟算的不一樣）就不放
            }
            block.setBlockData(guide, false);
            changed++;
        }
        return changed;
    }
}
