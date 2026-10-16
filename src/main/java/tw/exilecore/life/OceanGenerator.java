package tw.exilecore.life;

import org.bukkit.Material;
import org.bukkit.block.Biome;
import org.bukkit.generator.BiomeProvider;
import org.bukkit.generator.ChunkGenerator;
import org.bukkit.generator.WorldInfo;

import java.util.List;
import java.util.Random;

/**
 * 星骸之海的世界（2026-10）：一整片海。最底下基岩、沙底、上面是水，水面在 seaLevel（那一層也是水）。
 * 生物群系一律暖洋（水比較藍）；不長結構、洞穴、裝飾。
 */
final class OceanGenerator extends ChunkGenerator {

    private final int seaLevel;

    OceanGenerator(final int seaLevel) {
        this.seaLevel = seaLevel;
    }

    @Override
    public void generateNoise(final WorldInfo info, final Random random, final int chunkX, final int chunkZ, final ChunkData data) {
        final int min = info.getMinHeight();
        final int sandTop = Math.max(min + 2, seaLevel - 20);
        data.setRegion(0, min, 0, 16, min + 1, 16, Material.BEDROCK);
        data.setRegion(0, min + 1, 0, 16, sandTop - 3, 16, Material.SANDSTONE);
        data.setRegion(0, sandTop - 3, 0, 16, sandTop, 16, Material.SAND);
        data.setRegion(0, sandTop, 0, 16, seaLevel + 1, 16, Material.WATER);
    }

    @Override
    public BiomeProvider getDefaultBiomeProvider(final WorldInfo info) {
        return new BiomeProvider() {
            @Override
            public Biome getBiome(final WorldInfo worldInfo, final int x, final int y, final int z) {
                return Biome.WARM_OCEAN;
            }

            @Override
            public List<Biome> getBiomes(final WorldInfo worldInfo) {
                return List.of(Biome.WARM_OCEAN);
            }
        };
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
