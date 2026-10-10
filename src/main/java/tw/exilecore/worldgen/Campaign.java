package tw.exilecore.worldgen;

/**
 * 劇情世界生成的入口：一個區塊 = 地形與自然物件（{@link CampaignTerrain}）＋建築（{@link CampaignStructures}），
 * 另外提供每一格的生態域（草色、水色、天空）。
 */
public final class Campaign {

    private Campaign() {
    }

    /** 生成一個區塊（sink 只接受這個區塊裡的方塊）。 */
    public static void generateChunk(final int chunkX, final int chunkZ, final BlockSink sink) {
        CampaignTerrain.generate(chunkX, chunkZ, sink);
        CampaignStructures.generate(chunkX, chunkZ, sink);
    }

    /**
     * 這一格的生態域（原版的代號，例如 {@code dark_forest}）：海、海灘，
     * 其他照主題——營地與海岸是平原、森林是黑森林、廢墟是莽原（乾黃的草）、沼澤是紅樹林沼澤、山脈是雪坡。
     */
    public static String biome(final int x, final int z) {
        final double coast = CampaignLayout.coastDistance(x + 0.5, z + 0.5);
        if (coast < -6) {
            return coast < -30 ? "deep_ocean" : "ocean";
        }
        if (coast < 6) {
            return "beach";
        }
        return switch (CampaignLayout.blend(x + 0.5).pick(x, z)) {
            case CAMP, COAST -> "plains";
            case FOREST -> "dark_forest";
            case RUINS -> "savanna";
            case SWAMP -> "mangrove_swamp";
            case MOUNTAIN -> "snowy_slopes";
        };
    }

    /** 會用到的生態域（BiomeProvider 要先列出來）。 */
    public static final String[] BIOMES = {"deep_ocean", "ocean", "beach", "plains", "dark_forest", "savanna", "mangrove_swamp", "snowy_slopes"};
}
