package tw.exilecore.world;

import org.bukkit.HeightMap;
import org.bukkit.Location;
import org.bukkit.World;
import org.bukkit.block.Biome;
import org.bukkit.generator.BiomeProvider;
import org.bukkit.generator.ChunkGenerator;
import org.bukkit.generator.WorldInfo;
import tw.exilecore.worldgen.BlockSink;
import tw.exilecore.worldgen.Campaign;
import tw.exilecore.worldgen.CampaignLayout;
import tw.exilecore.worldgen.CampaignTerrain;

import java.util.List;
import java.util.Random;

/**
 * 劇情世界的區塊生成器：全部交給 {@link Campaign}（純邏輯），這裡只把方塊寫進 ChunkData、
 * 提供生態域與出生點。原版的地形、洞穴、礦物、結構、生物全部關掉。
 */
public final class CampaignGenerator extends ChunkGenerator {

    private final Biomes biomes = new Biomes();

    @Override
    public void generateNoise(final WorldInfo worldInfo, final Random random, final int chunkX, final int chunkZ,
                              final ChunkData chunkData) {
        Campaign.generateChunk(chunkX, chunkZ, new ChunkDataSink(chunkX, chunkZ, chunkData));
    }

    @Override
    public BiomeProvider getDefaultBiomeProvider(final WorldInfo worldInfo) {
        return biomes;
    }

    @Override
    public int getBaseHeight(final WorldInfo worldInfo, final Random random, final int x, final int z, final HeightMap heightMap) {
        return Math.max(CampaignTerrain.top(x, z), CampaignLayout.SEA_LEVEL) + 1;
    }

    @Override
    public Location getFixedSpawnLocation(final World world, final Random random) {
        final CampaignLayout.Spot spawn = CampaignLayout.campSpawn();
        return new Location(world, spawn.x(), spawn.y(), spawn.z(), spawn.yaw(), 0f);
    }

    @Override
    public boolean shouldGenerateNoise() {
        return false;
    }

    @Override
    public boolean shouldGenerateSurface() {
        return false;
    }

    @Override
    public boolean shouldGenerateCaves() {
        return false;
    }

    @Override
    public boolean shouldGenerateDecorations() {
        return false;
    }

    @Override
    public boolean shouldGenerateMobs() {
        return false;
    }

    @Override
    public boolean shouldGenerateStructures() {
        return false;
    }

    /** 把世界座標的方塊寫進這個區塊（區塊外、高度外的略過）。 */
    static final class ChunkDataSink implements BlockSink {

        private final int minX;
        private final int minZ;
        private final ChunkData data;
        private final int minY;
        private final int maxY;

        ChunkDataSink(final int chunkX, final int chunkZ, final ChunkData data) {
            this.minX = chunkX << 4;
            this.minZ = chunkZ << 4;
            this.data = data;
            this.minY = data.getMinHeight();
            this.maxY = data.getMaxHeight();
        }

        @Override
        public void set(final int x, final int y, final int z, final int block) {
            final int dx = x - minX;
            final int dz = z - minZ;
            if (dx < 0 || dx > 15 || dz < 0 || dz > 15 || y < minY || y >= maxY) {
                return;
            }
            data.setBlock(dx, y, dz, BlockPalette.get(block));
        }

        @Override
        public void fill(final int x1, final int y1, final int z1, final int x2, final int y2, final int z2, final int block) {
            final int fromX = Math.max(Math.min(x1, x2), minX) - minX;
            final int toX = Math.min(Math.max(x1, x2), minX + 15) - minX;
            final int fromZ = Math.max(Math.min(z1, z2), minZ) - minZ;
            final int toZ = Math.min(Math.max(z1, z2), minZ + 15) - minZ;
            final int fromY = Math.max(Math.min(y1, y2), minY);
            final int toY = Math.min(Math.max(y1, y2), maxY - 1);
            if (fromX > toX || fromZ > toZ || fromY > toY) {
                return;
            }
            data.setRegion(fromX, fromY, fromZ, toX + 1, toY + 1, toZ + 1, BlockPalette.get(block));
        }

        @Override
        public boolean intersects(final int areaMinX, final int areaMinZ, final int areaMaxX, final int areaMaxZ) {
            return areaMaxX >= minX && areaMinX <= minX + 15 && areaMaxZ >= minZ && areaMinZ <= minZ + 15;
        }
    }

    /** 生態域：照 {@link Campaign#biome} 的代號。 */
    static final class Biomes extends BiomeProvider {

        @Override
        public Biome getBiome(final WorldInfo worldInfo, final int x, final int y, final int z) {
            return biome(Campaign.biome(x, z));
        }

        @Override
        public List<Biome> getBiomes(final WorldInfo worldInfo) {
            return List.of(Biome.DEEP_OCEAN, Biome.OCEAN, Biome.BEACH, Biome.PLAINS, Biome.DARK_FOREST, Biome.SAVANNA,
                    Biome.MANGROVE_SWAMP, Biome.SNOWY_SLOPES);
        }

        static Biome biome(final String key) {
            return switch (key) {
                case "deep_ocean" -> Biome.DEEP_OCEAN;
                case "ocean" -> Biome.OCEAN;
                case "beach" -> Biome.BEACH;
                case "dark_forest" -> Biome.DARK_FOREST;
                case "savanna" -> Biome.SAVANNA;
                case "mangrove_swamp" -> Biome.MANGROVE_SWAMP;
                case "snowy_slopes" -> Biome.SNOWY_SLOPES;
                default -> Biome.PLAINS;
            };
        }
    }
}
