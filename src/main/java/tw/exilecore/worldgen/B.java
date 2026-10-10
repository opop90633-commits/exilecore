package tw.exilecore.worldgen;

/**
 * 世界生成用到的方塊（{@link Palette} 的代號）。全部集中在這裡，Bukkit 端啟動時一次換成 BlockData，
 * 寫錯的狀態字串會在啟動時就被發現（主控台警告並改用石頭），不會等到生成區塊才出事。
 */
public final class B {

    private B() {
    }

    public static final int AIR = Palette.AIR;

    // ---- 地形 ----
    public static final int BEDROCK = Palette.id("bedrock");
    public static final int STONE = Palette.id("stone");
    public static final int DEEPSLATE = Palette.id("deepslate");
    public static final int ANDESITE = Palette.id("andesite");
    public static final int TUFF = Palette.id("tuff");
    public static final int CALCITE = Palette.id("calcite");
    public static final int GRAVEL = Palette.id("gravel");
    public static final int DIRT = Palette.id("dirt");
    public static final int COARSE_DIRT = Palette.id("coarse_dirt");
    public static final int ROOTED_DIRT = Palette.id("rooted_dirt");
    public static final int PODZOL = Palette.id("podzol");
    public static final int GRASS = Palette.id("grass_block");
    public static final int DIRT_PATH = Palette.id("dirt_path");
    public static final int MOSS = Palette.id("moss_block");
    public static final int SAND = Palette.id("sand");
    public static final int SANDSTONE = Palette.id("sandstone");
    public static final int SMOOTH_SANDSTONE = Palette.id("smooth_sandstone");
    public static final int CLAY = Palette.id("clay");
    public static final int MUD = Palette.id("mud");
    public static final int PACKED_MUD = Palette.id("packed_mud");
    public static final int MUD_BRICKS = Palette.id("mud_bricks");
    public static final int MUDDY_ROOTS = Palette.id("muddy_mangrove_roots[axis=y]");
    public static final int SNOW_BLOCK = Palette.id("snow_block");
    public static final int POWDER_SNOW = Palette.id("powder_snow");
    public static final int PACKED_ICE = Palette.id("packed_ice");
    public static final int BLUE_ICE = Palette.id("blue_ice");
    public static final int WATER = Palette.id("water[level=0]");
    public static final int BASALT = Palette.id("basalt[axis=y]");
    public static final int SMOOTH_BASALT = Palette.id("smooth_basalt");
    public static final int BLACKSTONE = Palette.id("blackstone");
    public static final int COAL_BLOCK = Palette.id("coal_block");
    public static final int SOUL_SOIL = Palette.id("soul_soil");

    // ---- 石材與建材 ----
    public static final int COBBLE = Palette.id("cobblestone");
    public static final int MOSSY_COBBLE = Palette.id("mossy_cobblestone");
    public static final int STONE_BRICKS = Palette.id("stone_bricks");
    public static final int CRACKED_BRICKS = Palette.id("cracked_stone_bricks");
    public static final int MOSSY_BRICKS = Palette.id("mossy_stone_bricks");
    public static final int CHISELED_BRICKS = Palette.id("chiseled_stone_bricks");
    public static final int STONE_BRICK_SLAB = Palette.id("stone_brick_slab[type=bottom]");
    public static final int STONE_BRICK_WALL = Palette.id("stone_brick_wall[up=true]");
    public static final int COBBLE_WALL = Palette.id("cobblestone_wall[up=true]");
    public static final int SMOOTH_STONE = Palette.id("smooth_stone");
    public static final int SMOOTH_STONE_SLAB = Palette.id("smooth_stone_slab[type=bottom]");
    public static final int POLISHED_ANDESITE = Palette.id("polished_andesite");
    public static final int DEEPSLATE_BRICKS = Palette.id("deepslate_bricks");
    public static final int CRACKED_DEEPSLATE_BRICKS = Palette.id("cracked_deepslate_bricks");
    public static final int DEEPSLATE_TILES = Palette.id("deepslate_tiles");
    public static final int POLISHED_DEEPSLATE = Palette.id("polished_deepslate");
    public static final int CHISELED_DEEPSLATE = Palette.id("chiseled_deepslate");
    public static final int COBBLED_DEEPSLATE = Palette.id("cobbled_deepslate");
    public static final int BLACKSTONE_BRICKS = Palette.id("polished_blackstone_bricks");
    public static final int CRACKED_BLACKSTONE_BRICKS = Palette.id("cracked_polished_blackstone_bricks");
    public static final int POLISHED_BLACKSTONE = Palette.id("polished_blackstone");
    public static final int GILDED_BLACKSTONE = Palette.id("gilded_blackstone");
    public static final int TUFF_BRICKS = Palette.id("tuff_bricks");
    public static final int POLISHED_TUFF = Palette.id("polished_tuff");
    public static final int PURPUR = Palette.id("purpur_block");
    public static final int PURPUR_PILLAR = Palette.id("purpur_pillar[axis=y]");
    public static final int END_STONE_BRICKS = Palette.id("end_stone_bricks");
    public static final int QUARTZ = Palette.id("quartz_block");
    public static final int QUARTZ_PILLAR = Palette.id("quartz_pillar[axis=y]");
    public static final int SMOOTH_QUARTZ = Palette.id("smooth_quartz");
    public static final int PRISMARINE = Palette.id("prismarine");
    public static final int DARK_PRISMARINE = Palette.id("dark_prismarine");
    public static final int OBSIDIAN = Palette.id("obsidian");
    public static final int CRYING_OBSIDIAN = Palette.id("crying_obsidian");
    public static final int AMETHYST = Palette.id("amethyst_block");
    public static final int BUDDING_AMETHYST = Palette.id("budding_amethyst");
    public static final int AMETHYST_CLUSTER = Palette.id("amethyst_cluster[facing=up]");
    public static final int LODESTONE = Palette.id("lodestone");
    public static final int BONE_BLOCK = Palette.id("bone_block[axis=y]");
    public static final int IRON_BLOCK = Palette.id("iron_block");
    public static final int OXIDIZED_COPPER = Palette.id("oxidized_copper");

    // ---- 木材 ----
    public static final int SPRUCE_LOG = Palette.id("spruce_log[axis=y]");
    public static final int SPRUCE_LOG_X = Palette.id("spruce_log[axis=x]");
    public static final int SPRUCE_LOG_Z = Palette.id("spruce_log[axis=z]");
    public static final int STRIPPED_SPRUCE_LOG = Palette.id("stripped_spruce_log[axis=y]");
    public static final int SPRUCE_PLANKS = Palette.id("spruce_planks");
    public static final int SPRUCE_SLAB = Palette.id("spruce_slab[type=bottom]");
    public static final int SPRUCE_SLAB_TOP = Palette.id("spruce_slab[type=top]");
    public static final int SPRUCE_FENCE = Palette.id("spruce_fence");
    public static final int SPRUCE_FENCE_X = Palette.id("spruce_fence[east=true,west=true]");
    public static final int SPRUCE_FENCE_Z = Palette.id("spruce_fence[north=true,south=true]");
    public static final int OAK_LOG = Palette.id("oak_log[axis=y]");
    public static final int OAK_PLANKS = Palette.id("oak_planks");
    public static final int OAK_FENCE = Palette.id("oak_fence");
    public static final int BIRCH_LOG = Palette.id("birch_log[axis=y]");
    public static final int DARK_OAK_LOG = Palette.id("dark_oak_log[axis=y]");
    public static final int DARK_OAK_LOG_X = Palette.id("dark_oak_log[axis=x]");
    public static final int DARK_OAK_LOG_Z = Palette.id("dark_oak_log[axis=z]");
    public static final int DARK_OAK_PLANKS = Palette.id("dark_oak_planks");
    public static final int DARK_OAK_SLAB = Palette.id("dark_oak_slab[type=bottom]");
    public static final int DARK_OAK_FENCE = Palette.id("dark_oak_fence");
    public static final int MANGROVE_LOG = Palette.id("mangrove_log[axis=y]");
    public static final int MANGROVE_ROOTS = Palette.id("mangrove_roots");
    public static final int PALE_OAK_LOG = Palette.id("pale_oak_log[axis=y]");

    // ---- 樹葉（持久，不會因為離樹幹太遠而掉光） ----
    public static final int OAK_LEAVES = Palette.id("oak_leaves[persistent=true]");
    public static final int BIRCH_LEAVES = Palette.id("birch_leaves[persistent=true]");
    public static final int SPRUCE_LEAVES = Palette.id("spruce_leaves[persistent=true]");
    public static final int DARK_OAK_LEAVES = Palette.id("dark_oak_leaves[persistent=true]");
    public static final int MANGROVE_LEAVES = Palette.id("mangrove_leaves[persistent=true]");
    public static final int AZALEA_LEAVES = Palette.id("azalea_leaves[persistent=true]");
    public static final int PALE_OAK_LEAVES = Palette.id("pale_oak_leaves[persistent=true]");

    // ---- 植物與地面裝飾 ----
    public static final int SHORT_GRASS = Palette.id("short_grass");
    public static final int TALL_GRASS_LOW = Palette.id("tall_grass[half=lower]");
    public static final int TALL_GRASS_HIGH = Palette.id("tall_grass[half=upper]");
    public static final int FERN = Palette.id("fern");
    public static final int LARGE_FERN_LOW = Palette.id("large_fern[half=lower]");
    public static final int LARGE_FERN_HIGH = Palette.id("large_fern[half=upper]");
    public static final int DEAD_BUSH = Palette.id("dead_bush");
    public static final int SHORT_DRY_GRASS = Palette.id("short_dry_grass");
    public static final int BUSH = Palette.id("bush");
    public static final int BROWN_MUSHROOM = Palette.id("brown_mushroom");
    public static final int RED_MUSHROOM = Palette.id("red_mushroom");
    public static final int MOSS_CARPET = Palette.id("moss_carpet");
    public static final int LILY_PAD = Palette.id("lily_pad");
    public static final int SEAGRASS = Palette.id("seagrass");
    public static final int COBWEB = Palette.id("cobweb");
    public static final int SNOW_LAYER = Palette.id("snow[layers=1]");
    public static final int SNOW_LAYER2 = Palette.id("snow[layers=2]");
    public static final int LEAF_LITTER = Palette.id("leaf_litter[segment_amount=3,facing=north]");
    public static final int GLOW_LICHEN = Palette.id("glow_lichen[down=true]");
    public static final int PALE_MOSS_CARPET = Palette.id("pale_moss_carpet");

    // ---- 光源與道具 ----
    public static final int LANTERN = Palette.id("lantern[hanging=false]");
    public static final int LANTERN_HANGING = Palette.id("lantern[hanging=true]");
    public static final int SOUL_LANTERN = Palette.id("soul_lantern[hanging=false]");
    public static final int SOUL_LANTERN_HANGING = Palette.id("soul_lantern[hanging=true]");
    public static final int CAMPFIRE = Palette.id("campfire[lit=true,signal_fire=false]");
    public static final int CAMPFIRE_OUT = Palette.id("campfire[lit=false,signal_fire=false]");
    public static final int SOUL_CAMPFIRE = Palette.id("soul_campfire[lit=true,signal_fire=false]");
    public static final int TORCH = Palette.id("torch");
    public static final int SEA_LANTERN = Palette.id("sea_lantern");
    public static final int GLOWSTONE = Palette.id("glowstone");
    public static final int SHROOMLIGHT = Palette.id("shroomlight");
    /** 路標：地上指方向的發光箭頭（劇情的路、地圖的主路）。主題都沒用到蛙光體，一看就知道是路標。 */
    public static final int GUIDE = Palette.id("ochre_froglight");
    public static final int END_ROD = Palette.id("end_rod[facing=up]");
    public static final int CHAIN = Palette.id("iron_chain[axis=y]");
    public static final int IRON_BARS = Palette.id("iron_bars");
    public static final int IRON_BARS_X = Palette.id("iron_bars[east=true,west=true]");
    public static final int IRON_BARS_Z = Palette.id("iron_bars[north=true,south=true]");
    public static final int BARREL = Palette.id("barrel[facing=up,open=false]");
    public static final int HAY = Palette.id("hay_block[axis=y]");
    public static final int CRAFTING_TABLE = Palette.id("crafting_table");
    public static final int ANVIL = Palette.id("anvil[facing=north]");
    public static final int GRINDSTONE = Palette.id("grindstone[face=floor,facing=north]");
    public static final int LECTERN = Palette.id("lectern[facing=south,has_book=false,powered=false]");
    public static final int BOOKSHELF = Palette.id("bookshelf");
    public static final int CAULDRON = Palette.id("cauldron");
    public static final int SMOKER = Palette.id("smoker[facing=south,lit=false]");
    public static final int SKULL = Palette.id("skeleton_skull[rotation=0]");
    public static final int DECORATED_POT = Palette.id("decorated_pot[facing=north,cracked=false,waterlogged=false]");
    public static final int SMITHING_TABLE = Palette.id("smithing_table");
    public static final int CARTOGRAPHY_TABLE = Palette.id("cartography_table");
    public static final int LOOM = Palette.id("loom[facing=north]");
    public static final int BELL = Palette.id("bell[attachment=floor,facing=north,powered=false]");
    public static final int CANDLE = Palette.id("white_candle[candles=3,lit=true]");

    // ---- 布料 ----
    public static final int WHITE_WOOL = Palette.id("white_wool");
    public static final int LIGHT_GRAY_WOOL = Palette.id("light_gray_wool");
    public static final int GRAY_WOOL = Palette.id("gray_wool");
    public static final int BROWN_WOOL = Palette.id("brown_wool");
    public static final int RED_WOOL = Palette.id("red_wool");
    public static final int BLUE_WOOL = Palette.id("blue_wool");
    public static final int GREEN_WOOL = Palette.id("green_wool");
    public static final int YELLOW_WOOL = Palette.id("yellow_wool");
    public static final int PURPLE_WOOL = Palette.id("purple_wool");
    public static final int CYAN_WOOL = Palette.id("cyan_wool");
    public static final int BROWN_CARPET = Palette.id("brown_carpet");
    public static final int RED_CARPET = Palette.id("red_carpet");
    public static final int GRAY_CARPET = Palette.id("gray_carpet");
}
