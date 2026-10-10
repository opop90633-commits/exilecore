package tw.exilecore.world;

import org.bukkit.Location;
import org.bukkit.World;
import org.bukkit.block.Biome;
import org.bukkit.generator.BiomeProvider;
import org.bukkit.generator.ChunkGenerator;
import org.bukkit.generator.WorldInfo;

import java.util.List;
import java.util.Random;

/**
 * 地圖世界（異界）的生成器：整個世界都是虛空（生態域 the_void：不下雨、不自然生怪），
 * 地圖實例由地圖模組在格子裡蓋出來。
 */
public final class VoidGenerator extends ChunkGenerator {

    private final BiomeProvider biomes = new BiomeProvider() {
        @Override
        public Biome getBiome(final WorldInfo worldInfo, final int x, final int y, final int z) {
            return Biome.THE_VOID;
        }

        @Override
        public List<Biome> getBiomes(final WorldInfo worldInfo) {
            return List.of(Biome.THE_VOID);
        }
    };

    @Override
    public BiomeProvider getDefaultBiomeProvider(final WorldInfo worldInfo) {
        return biomes;
    }

    @Override
    public Location getFixedSpawnLocation(final World world, final Random random) {
        return new Location(world, 0.5, 100, 0.5);
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
}
