package tw.exilecore.worldgen;

import java.util.SplittableRandom;

import static tw.exilecore.worldgen.CampaignLayout.ROAD_HALF;
import static tw.exilecore.worldgen.CampaignLayout.SEA_LEVEL;
import static tw.exilecore.worldgen.CampaignLayout.SEED;
import static tw.exilecore.worldgen.CampaignLayout.Theme;

/**
 * 劇情世界的地形：每一欄的高度與方塊（依主題）、路、沼澤的木棧道、地面的草，
 * 以及用格點散布的樹、石頭、廢墟殘牆等自然物件。純邏輯，寫進 {@link BlockSink}。
 */
public final class CampaignTerrain {

    /** 散布物件的格點大小與最大半徑（一個區塊要看附近哪些格點）。 */
    static final int CELL = 9;
    static final int MAX_RADIUS = 6;

    /**
     * 樹上垂下來的蜘蛛網至少在地面幾格以上（跳到最高時頭頂大約在腳下那格往上 4 格多，碰到會整個停住）。
     * 地面看這一格與周圍八格最高的（{@link #highestAround}）；碰不碰得到的完整規則在 {@link WebReach}。
     */
    public static final int WEB_CLEARANCE = 5;

    private CampaignTerrain() {
    }

    // ---- 一欄的資料 ----

    /** 這一欄最後的地表高度（含沼澤路旁挖低的水道）。 */
    public static int top(final int x, final int z) {
        int top = (int) Math.floor(CampaignLayout.surfaceHeight(x + 0.5, z + 0.5) + 0.5);
        if (swampChannel(x, z)) {
            top = Math.min(top, SEA_LEVEL - 1);
        }
        return top;
    }

    /** 這一欄與周圍八欄裡最高的地表（站在隔壁格子的邊上，身體會伸進這一格）。 */
    static int highestAround(final int x, final int z) {
        int highest = Integer.MIN_VALUE;
        for (int dx = -1; dx <= 1; dx++) {
            for (int dz = -1; dz <= 1; dz++) {
                highest = Math.max(highest, top(x + dx, z + dz));
            }
        }
        return highest;
    }

    /** 沼澤裡路的兩側挖成水道（路本身是木棧道）；祭壇與 Boss 場地附近不挖。 */
    static boolean swampChannel(final int x, final int z) {
        if (CampaignLayout.blend(x + 0.5).pick(x, z) != Theme.SWAMP) {
            return false;
        }
        return CampaignLayout.roadDistance(x + 0.5, z + 0.5) < 6.5 && CampaignLayout.flatness(x + 0.5, z + 0.5) < 0.3;
    }

    /** 這一格是不是路面（含邊緣的抖動）。 */
    public static boolean isRoad(final int x, final int z) {
        if (x < -CampaignLayout.CAMP_HALF || x > CampaignLayout.WORLD_END_X - 60) {
            return false;
        }
        final double jitter = Noise.random01(SEED + 41, x, z) < 0.25 ? 1 : 0;
        return CampaignLayout.roadDistance(x + 0.5, z + 0.5) <= ROAD_HALF + 0.5 + jitter;
    }

    /** 沼澤的路是木棧道（腳下是水）。 */
    public static boolean isBoardwalk(final int x, final int z) {
        return isRoad(x, z) && swampChannel(x, z);
    }

    /** 路面最上面一格的高度（木棧道也是這個高度）。 */
    public static int roadTop(final int x) {
        return CampaignLayout.roadTop(x + 0.5);
    }

    /** 站得住的高度（路上用路面，其他用地表，水裡回傳水面上）。 */
    public static int standY(final int x, final int z) {
        if (isRoad(x, z)) {
            return roadTop(x) + 1;
        }
        return Math.max(top(x, z), SEA_LEVEL) + 1;
    }

    // ---- 生成 ----

    /** 生成一個區塊的地形與自然物件。 */
    public static void generate(final int chunkX, final int chunkZ, final BlockSink sink) {
        final int minX = chunkX << 4;
        final int minZ = chunkZ << 4;
        for (int dx = 0; dx < 16; dx++) {
            for (int dz = 0; dz < 16; dz++) {
                column(minX + dx, minZ + dz, sink);
            }
        }
        features(minX, minZ, sink);
    }

    private static void column(final int x, final int z, final BlockSink sink) {
        final int top = top(x, z);
        final Theme theme = CampaignLayout.blend(x + 0.5).pick(x, z);
        final double cliff = CampaignLayout.cliffWeight(x + 0.5, z + 0.5);
        final double coast = CampaignLayout.coastDistance(x + 0.5, z + 0.5);
        final boolean underwater = top < SEA_LEVEL;
        final boolean road = isRoad(x, z);
        final boolean boardwalk = road && underwater;
        final double roll = Noise.random01(SEED + 43, x, z);
        final double patch = Noise.fbm(SEED + 47, x / 14.0, z / 14.0, 2);

        sink.set(x, CampaignLayout.FLOOR_Y, z, B.BEDROCK);
        // 岩層：懸崖整片是岩石（露出來的崖面才好看），一般地面上面三格是土
        final boolean rocky = cliff > 0.15 && !road;
        final int rock = rockBlock(theme, x, z, roll);
        final int soilDepth = rocky ? 1 : 3;
        final int stoneTop = top - soilDepth;
        if (stoneTop > CampaignLayout.FLOOR_Y) {
            sink.fill(x, CampaignLayout.FLOOR_Y + 1, z, x, stoneTop, z, B.STONE);
        }
        if (rocky) {
            // 崖面的上半部用主題的岩石
            final int from = Math.max(CampaignLayout.FLOOR_Y + 1, top - 14);
            for (int y = from; y < top; y++) {
                sink.set(x, y, z, rockLayer(theme, x, y, z, rock));
            }
        } else {
            final int filler = fillerBlock(theme, underwater, coast);
            for (int y = Math.max(CampaignLayout.FLOOR_Y + 1, top - soilDepth + 1); y < top; y++) {
                sink.set(x, y, z, filler);
            }
        }

        // 最上面一格
        final int surface;
        if (road && !boardwalk) {
            surface = roadBlock(theme, x, z, roll);
        } else if (underwater) {
            surface = seabedBlock(theme, coast, roll);
        } else if (rocky && cliff > 0.6 && theme != Theme.SWAMP) {
            surface = cliffTopBlock(theme, top, roll, patch);
        } else {
            surface = topBlock(theme, coast, top, roll, patch);
        }
        sink.set(x, top, z, surface);

        // 水
        if (underwater) {
            sink.fill(x, top + 1, z, x, SEA_LEVEL, z, B.WATER);
        }
        // 沼澤的木棧道：水面上一格高的木板，每 3 格一根柱子
        if (boardwalk) {
            final int deck = roadTop(x);
            sink.set(x, deck, z, Noise.random01(SEED + 53, x, z) < 0.12 ? B.SPRUCE_SLAB_TOP : B.SPRUCE_PLANKS);
            final boolean edge = CampaignLayout.roadDistance(x + 0.5, z + 0.5) > ROAD_HALF - 0.5;
            if (edge && Math.floorMod(x, 3) == 0) {
                for (int y = top + 1; y < deck; y++) {
                    sink.set(x, y, z, B.SPRUCE_LOG);
                }
            }
            return;
        }
        if (road) {
            return;
        }
        groundCover(theme, x, top, z, surface, underwater, coast, sink);
    }

    private static int rockBlock(final Theme theme, final int x, final int z, final double roll) {
        return switch (theme) {
            case CAMP, COAST -> roll < 0.6 ? B.STONE : roll < 0.85 ? B.ANDESITE : B.TUFF;
            case FOREST -> roll < 0.55 ? B.STONE : roll < 0.8 ? B.MOSSY_COBBLE : B.ANDESITE;
            case RUINS -> roll < 0.45 ? B.STONE_BRICKS : roll < 0.7 ? B.CRACKED_BRICKS : roll < 0.85 ? B.MOSSY_BRICKS : B.ANDESITE;
            case SWAMP -> roll < 0.5 ? B.PACKED_MUD : roll < 0.8 ? B.DIRT : B.MUD_BRICKS;
            case MOUNTAIN -> roll < 0.5 ? B.STONE : roll < 0.75 ? B.ANDESITE : roll < 0.9 ? B.CALCITE : B.PACKED_ICE;
        };
    }

    /** 崖面：一層一層的條紋（用高度換岩石種類），廢墟是城牆的磚。 */
    private static int rockLayer(final Theme theme, final int x, final int y, final int z, final int base) {
        final double band = Noise.value(SEED + 59, x / 11.0, y / 2.5 + z / 11.0);
        if (theme == Theme.RUINS) {
            final double r = Noise.random01(SEED + 61, x * 31L + y, z);
            return r < 0.5 ? B.STONE_BRICKS : r < 0.75 ? B.CRACKED_BRICKS : r < 0.9 ? B.MOSSY_BRICKS : B.ANDESITE;
        }
        if (theme == Theme.MOUNTAIN) {
            return band > 0.35 ? B.CALCITE : band < -0.4 ? B.ANDESITE : B.STONE;
        }
        if (theme == Theme.SWAMP) {
            return band > 0.2 ? B.PACKED_MUD : B.DIRT;
        }
        return band > 0.4 ? B.ANDESITE : band < -0.45 ? B.TUFF : base == B.MOSSY_COBBLE && band > 0 ? B.MOSSY_COBBLE : B.STONE;
    }

    private static int fillerBlock(final Theme theme, final boolean underwater, final double coast) {
        if (coast < 8) {
            return B.SAND;
        }
        return switch (theme) {
            case SWAMP -> underwater ? B.CLAY : B.MUD;
            case MOUNTAIN -> B.STONE;
            default -> B.DIRT;
        };
    }

    private static int roadBlock(final Theme theme, final int x, final int z, final double roll) {
        final boolean edge = CampaignLayout.roadDistance(x + 0.5, z + 0.5) > ROAD_HALF - 0.5;
        return switch (theme) {
            case CAMP, COAST -> edge ? (roll < 0.5 ? B.GRAVEL : B.COARSE_DIRT) : (roll < 0.85 ? B.DIRT_PATH : B.GRAVEL);
            case FOREST -> edge ? (roll < 0.5 ? B.COARSE_DIRT : B.ROOTED_DIRT) : (roll < 0.8 ? B.DIRT_PATH : B.COARSE_DIRT);
            case RUINS -> roll < 0.45 ? B.STONE_BRICKS : roll < 0.7 ? B.CRACKED_BRICKS : roll < 0.82 ? B.ANDESITE
                    : roll < 0.92 ? B.MOSSY_BRICKS : B.GRAVEL;
            case SWAMP -> roll < 0.6 ? B.PACKED_MUD : roll < 0.85 ? B.MUD_BRICKS : B.COARSE_DIRT;
            case MOUNTAIN -> roll < 0.5 ? B.GRAVEL : roll < 0.8 ? B.COBBLE : B.ANDESITE;
        };
    }

    private static int seabedBlock(final Theme theme, final double coast, final double roll) {
        if (theme == Theme.SWAMP) {
            return roll < 0.7 ? B.MUD : B.CLAY;
        }
        if (coast < 0) {
            return roll < 0.65 ? B.SAND : roll < 0.85 ? B.GRAVEL : B.CLAY;
        }
        return roll < 0.5 ? B.GRAVEL : B.DIRT;
    }

    private static int cliffTopBlock(final Theme theme, final int top, final double roll, final double patch) {
        return switch (theme) {
            case CAMP, COAST -> patch > 0.35 ? B.COARSE_DIRT : B.GRASS;
            case FOREST -> patch > 0.2 ? B.PODZOL : patch < -0.3 ? B.MOSS : B.GRASS;
            case RUINS -> roll < 0.4 ? B.COARSE_DIRT : roll < 0.7 ? B.GRASS : B.GRAVEL;
            case SWAMP -> B.GRASS;
            case MOUNTAIN -> top > 82 || patch > 0 ? B.SNOW_BLOCK : B.STONE;
        };
    }

    private static int topBlock(final Theme theme, final double coast, final int top, final double roll, final double patch) {
        if (coast < 3) {
            return B.SAND;
        }
        if (coast < 9) {
            return roll < (9 - coast) / 6 ? B.SAND : theme == Theme.SWAMP ? B.MUD : B.GRASS;
        }
        return switch (theme) {
            case CAMP -> patch > 0.45 ? B.COARSE_DIRT : B.GRASS;
            case COAST -> patch > 0.4 ? B.COARSE_DIRT : patch < -0.5 ? B.GRAVEL : B.GRASS;
            case FOREST -> patch > 0.25 ? B.PODZOL : patch < -0.35 ? B.MOSS : roll < 0.04 ? B.ROOTED_DIRT : B.GRASS;
            case RUINS -> patch > 0.3 ? B.COARSE_DIRT : patch < -0.35 ? B.GRAVEL : roll < 0.06 ? B.CRACKED_BRICKS
                    : roll < 0.1 ? B.COBBLE : B.GRASS;
            case SWAMP -> patch > 0.3 ? B.MUD : patch < -0.4 ? B.MOSS : roll < 0.08 ? B.MUDDY_ROOTS : B.GRASS;
            case MOUNTAIN -> top > 84 ? B.SNOW_BLOCK : patch > 0.3 ? B.STONE : patch < -0.4 ? B.GRAVEL : B.GRASS;
        };
    }

    /** 地面上的草、蕨、雪、浮萍。 */
    private static void groundCover(final Theme theme, final int x, final int top, final int z, final int surface,
                                    final boolean underwater, final double coast, final BlockSink sink) {
        final double roll = Noise.random01(SEED + 43, x, z);
        if (underwater) {
            if (theme == Theme.SWAMP && top == SEA_LEVEL - 1 && roll < 0.07) {
                sink.set(x, SEA_LEVEL + 1, z, B.LILY_PAD);
            } else if (coast < 0 && roll < 0.05 && top < SEA_LEVEL - 1) {
                sink.set(x, top + 1, z, B.SEAGRASS);
            }
            return;
        }
        if (coast < 4) {
            if (roll < 0.015) {
                sink.set(x, top + 1, z, B.DEAD_BUSH);
            }
            return;
        }
        final double r = Noise.random01(SEED + 67, x, z);
        final int y = top + 1;
        if (theme != Theme.MOUNTAIN && !plantable(surface)) {
            return;
        }
        switch (theme) {
            case CAMP, COAST -> {
                if (r < 0.14) {
                    sink.set(x, y, z, B.SHORT_GRASS);
                } else if (r < 0.165) {
                    tall(sink, x, y, z, B.TALL_GRASS_LOW, B.TALL_GRASS_HIGH);
                } else if (r < 0.17) {
                    sink.set(x, y, z, B.BUSH);
                }
            }
            case FOREST -> {
                if (r < 0.16) {
                    sink.set(x, y, z, B.SHORT_GRASS);
                } else if (r < 0.26) {
                    sink.set(x, y, z, B.FERN);
                } else if (r < 0.285) {
                    tall(sink, x, y, z, B.LARGE_FERN_LOW, B.LARGE_FERN_HIGH);
                } else if (r < 0.3) {
                    // 菇只長在灰化土上（其他地面要夠暗才長得住，一更新就會掉）
                    if (surface == B.PODZOL) {
                        sink.set(x, y, z, r < 0.292 ? B.BROWN_MUSHROOM : B.RED_MUSHROOM);
                    }
                } else if (r < 0.36) {
                    sink.set(x, y, z, B.LEAF_LITTER);
                } else if (r < 0.37) {
                    sink.set(x, y, z, B.MOSS_CARPET);
                }
            }
            case RUINS -> {
                if (r < 0.1) {
                    sink.set(x, y, z, B.SHORT_DRY_GRASS);
                } else if (r < 0.13) {
                    sink.set(x, y, z, B.DEAD_BUSH);
                } else if (r < 0.135) {
                    sink.set(x, y, z, B.GRAY_CARPET); // 灰燼（以前是蜘蛛網：踩進去速度剩四分之一）
                }
            }
            case SWAMP -> {
                if (r < 0.14) {
                    sink.set(x, y, z, B.SHORT_GRASS);
                } else if (r < 0.17) {
                    sink.set(x, y, z, B.FERN);
                } else if (r < 0.18) {
                    sink.set(x, y, z, B.SHORT_GRASS);
                } else if (r < 0.2) {
                    sink.set(x, y, z, B.MOSS_CARPET);
                }
            }
            case MOUNTAIN -> {
                if (top > 76) {
                    if (r < 0.55) {
                        sink.set(x, y, z, r < 0.1 ? B.SNOW_LAYER2 : B.SNOW_LAYER);
                    }
                } else if (r < 0.08) {
                    if (surface == B.GRASS) {
                        sink.set(x, y, z, B.SHORT_GRASS);
                    }
                } else if (r < 0.35) {
                    sink.set(x, y, z, B.SNOW_LAYER);
                } else if (r < 0.355 && surface == B.GRASS) {
                    sink.set(x, y, z, B.FERN); // 以前是甜莓叢：走過去會減速、扣血
                }
            }
        }
    }

    /** 草、蕨、菇長得住的方塊（其他的方塊一更新就會掉）。 */
    private static boolean plantable(final int surface) {
        return surface == B.GRASS || surface == B.PODZOL || surface == B.MOSS || surface == B.COARSE_DIRT
                || surface == B.DIRT || surface == B.ROOTED_DIRT || surface == B.MUD || surface == B.MUDDY_ROOTS;
    }

    private static void tall(final BlockSink sink, final int x, final int y, final int z, final int lower, final int upper) {
        sink.set(x, y, z, lower);
        sink.set(x, y + 1, z, upper);
    }

    // ---- 散布的物件 ----

    private static void features(final int minX, final int minZ, final BlockSink sink) {
        final int fromCellX = Math.floorDiv(minX - MAX_RADIUS, CELL);
        final int toCellX = Math.floorDiv(minX + 15 + MAX_RADIUS, CELL);
        final int fromCellZ = Math.floorDiv(minZ - MAX_RADIUS, CELL);
        final int toCellZ = Math.floorDiv(minZ + 15 + MAX_RADIUS, CELL);
        for (int cx = fromCellX; cx <= toCellX; cx++) {
            for (int cz = fromCellZ; cz <= toCellZ; cz++) {
                cellFeature(cx, cz, sink);
                // 懸崖上的樹林密一點：同一格再放第二棵
                cellFeature(cx + 100_003, cz - 100_019, cx, cz, sink);
            }
        }
    }

    private static void cellFeature(final int cx, final int cz, final BlockSink sink) {
        cellFeature(cx, cz, cx, cz, sink);
    }

    /**
     * 一個格點最多放一個物件；{@code seedX／seedZ} 只影響亂數（第二輪用不同的亂數），位置在 {@code cx／cz} 這一格裡。
     */
    private static void cellFeature(final int seedX, final int seedZ, final int cx, final int cz, final BlockSink sink) {
        final boolean second = seedX != cx;
        final SplittableRandom random = new SplittableRandom(Noise.hash(SEED + (second ? 71 : 73), seedX, seedZ));
        final int x = cx * CELL + 1 + random.nextInt(CELL - 2);
        final int z = cz * CELL + 1 + random.nextInt(CELL - 2);
        if (!sink.intersects(x - MAX_RADIUS, z - MAX_RADIUS, x + MAX_RADIUS, z + MAX_RADIUS)) {
            return;
        }
        if (x < CampaignLayout.CAMP_BOX_MIN_X - 40 || x > CampaignLayout.WORLD_END_X + 40) {
            return;
        }
        final double cliff = CampaignLayout.cliffWeight(x + 0.5, z + 0.5);
        if (second && cliff < 0.7) {
            return;
        }
        final double roadDistance = CampaignLayout.roadDistance(x + 0.5, z + 0.5);
        if (roadDistance < ROAD_HALF + 4 && x > -CampaignLayout.CAMP_HALF) {
            return;
        }
        if (CampaignLayout.campFlatness(x + 0.5, z + 0.5) > 0 || CampaignLayout.flatness(x + 0.5, z + 0.5) > 0.02) {
            return;
        }
        if (x < CampaignLayout.CAMP_BOX_MAX_X + 8 && x > CampaignLayout.CAMP_BOX_MIN_X
                && z > CampaignLayout.CAMP_BOX_MIN_Z && z < CampaignLayout.CAMP_BOX_MAX_Z) {
            return; // 營地、海灘、碼頭另外佈置
        }
        final Theme theme = CampaignLayout.blend(x + 0.5).pick(x, z);
        final int top = top(x, z);
        final boolean water = top < SEA_LEVEL;
        final double chance = density(theme, cliff);
        if (random.nextDouble() >= chance) {
            return;
        }
        if (water && theme != Theme.SWAMP) {
            return;
        }
        final int y = top + 1;
        switch (theme) {
            case CAMP, COAST -> coastFeature(sink, random, x, y, z, cliff);
            case FOREST -> forestFeature(sink, random, x, y, z, cliff);
            case RUINS -> ruinsFeature(sink, random, x, y, z, cliff);
            case SWAMP -> swampFeature(sink, random, x, y, z, water);
            case MOUNTAIN -> mountainFeature(sink, random, x, y, z, cliff);
        }
    }

    /** 每一格放物件的機率：走廊裡稀、懸崖上密。 */
    private static double density(final Theme theme, final double cliff) {
        final double inside = switch (theme) {
            case CAMP, COAST -> 0.16;
            case FOREST -> 0.4;
            case RUINS -> 0.42;
            case SWAMP -> 0.32;
            case MOUNTAIN -> 0.22;
        };
        final double outside = switch (theme) {
            case CAMP, COAST -> 0.35;
            case FOREST -> 0.95;
            case RUINS -> 0.45;
            case SWAMP -> 0.7;
            case MOUNTAIN -> 0.55;
        };
        return Noise.lerp(inside, outside, cliff);
    }

    private static void coastFeature(final BlockSink sink, final SplittableRandom random, final int x, final int y, final int z,
                                     final double cliff) {
        final double coast = CampaignLayout.coastDistance(x + 0.5, z + 0.5);
        final double r = random.nextDouble();
        if (coast < 10) {
            if (r < 0.45) {
                driftwood(sink, random, x, y, z);
            } else if (r < 0.7) {
                boulder(sink, random, x, y, z, B.STONE, B.ANDESITE, 1);
            } else if (r < 0.85) {
                wreck(sink, random, x, y, z);
            } else {
                barrels(sink, random, x, y, z);
            }
            return;
        }
        if (r < 0.45) {
            oakTree(sink, random, x, y, z, B.OAK_LOG, B.OAK_LEAVES);
        } else if (r < 0.62) {
            birchTree(sink, random, x, y, z);
        } else if (r < 0.82) {
            boulder(sink, random, x, y, z, B.STONE, B.ANDESITE, 1 + random.nextInt(2));
        } else {
            bush(sink, random, x, y, z, B.OAK_LEAVES);
        }
    }

    private static void forestFeature(final BlockSink sink, final SplittableRandom random, final int x, final int y, final int z,
                                      final double cliff) {
        final double r = random.nextDouble();
        if (r < 0.42) {
            darkOakTree(sink, random, x, y, z);
        } else if (r < 0.66) {
            spruceTree(sink, random, x, y, z, false);
        } else if (r < 0.78) {
            oakTree(sink, random, x, y, z, B.OAK_LOG, B.AZALEA_LEAVES);
        } else if (r < 0.88) {
            boulder(sink, random, x, y, z, B.MOSSY_COBBLE, B.STONE, 1 + random.nextInt(2));
        } else if (r < 0.95) {
            bush(sink, random, x, y, z, B.DARK_OAK_LEAVES);
        } else {
            fallenLog(sink, random, x, y, z, B.DARK_OAK_LOG_X, B.DARK_OAK_LOG_Z);
        }
    }

    private static void ruinsFeature(final BlockSink sink, final SplittableRandom random, final int x, final int y, final int z,
                                     final double cliff) {
        final double r = random.nextDouble();
        if (r < 0.2) {
            ruinedHouse(sink, random, x, z);
        } else if (r < 0.38) {
            ruinWall(sink, random, x, y, z);
        } else if (r < 0.52) {
            ruinPillar(sink, random, x, y, z);
        } else if (r < 0.7) {
            rubble(sink, random, x, y, z);
        } else if (r < 0.85) {
            deadTree(sink, random, x, y, z, B.DARK_OAK_LOG, true);
        } else {
            boulder(sink, random, x, y, z, B.BLACKSTONE, B.BASALT, 1);
        }
    }

    private static void swampFeature(final BlockSink sink, final SplittableRandom random, final int x, final int y, final int z,
                                     final boolean water) {
        final double r = random.nextDouble();
        if (water) {
            if (r < 0.6) {
                mangroveTree(sink, random, x, Math.max(y, SEA_LEVEL), z);
            }
            return;
        }
        if (r < 0.45) {
            mangroveTree(sink, random, x, y, z);
        } else if (r < 0.62) {
            deadTree(sink, random, x, y, z, B.MANGROVE_LOG, false);
        } else if (r < 0.75) {
            boulder(sink, random, x, y, z, B.PACKED_MUD, B.MUD, 1);
        } else if (r < 0.88) {
            bones(sink, random, x, y, z);
        } else {
            bush(sink, random, x, y, z, B.MANGROVE_LEAVES);
        }
    }

    private static void mountainFeature(final BlockSink sink, final SplittableRandom random, final int x, final int y, final int z,
                                        final double cliff) {
        final double r = random.nextDouble();
        if (r < 0.5) {
            spruceTree(sink, random, x, y, z, true);
        } else if (r < 0.72) {
            boulder(sink, random, x, y, z, B.STONE, B.CALCITE, 1 + random.nextInt(2));
        } else if (r < 0.88) {
            iceSpike(sink, random, x, y, z);
        } else {
            deadTree(sink, random, x, y, z, B.SPRUCE_LOG, false);
        }
    }

    // ---- 物件 ----

    static void oakTree(final BlockSink sink, final SplittableRandom random, final int x, final int y, final int z,
                        final int log, final int leaves) {
        final int height = 4 + random.nextInt(3);
        blob(sink, random, x, y + height - 1, z, 2.6, 2.0, leaves, 0.12);
        column(sink, x, y, z, height, log);
    }

    static void birchTree(final BlockSink sink, final SplittableRandom random, final int x, final int y, final int z) {
        final int height = 5 + random.nextInt(3);
        blob(sink, random, x, y + height - 1, z, 2.1, 2.2, B.BIRCH_LEAVES, 0.1);
        column(sink, x, y, z, height, B.BIRCH_LOG);
    }

    static void spruceTree(final BlockSink sink, final SplittableRandom random, final int x, final int y, final int z,
                           final boolean snowy) {
        final int height = 7 + random.nextInt(5);
        final int leafStart = 2 + random.nextInt(2);
        for (int dy = leafStart; dy <= height; dy++) {
            final double t = (double) (dy - leafStart) / Math.max(1, height - leafStart);
            final double radius = (1 - t) * 3.0 + ((dy & 1) == 0 ? 0.6 : 0);
            disc(sink, x, y + dy, z, radius, B.SPRUCE_LEAVES);
            if (snowy && (dy & 1) == 0) {
                // 每一層葉子上的雪
                discTop(sink, random, x, y + dy + 1, z, radius - 0.5, B.SNOW_LAYER, 0.5);
            }
        }
        sink.set(x, y + height + 1, z, B.SPRUCE_LEAVES);
        column(sink, x, y, z, height, B.SPRUCE_LOG);
    }

    static void darkOakTree(final BlockSink sink, final SplittableRandom random, final int x, final int y, final int z) {
        final int height = 5 + random.nextInt(3);
        blob(sink, random, x, y + height, z, 4.2, 2.2, B.DARK_OAK_LEAVES, 0.08);
        for (int dx = 0; dx <= 1; dx++) {
            for (int dz = 0; dz <= 1; dz++) {
                column(sink, x + dx, y, z + dz, height + 1, B.DARK_OAK_LOG);
            }
        }
        // 垂下來的蜘蛛網（林影織母的地盤）：要在那一格與周圍八格的地面五格以上，跳起來頭也碰不到（碰到會整個停住）。
        // 大石頭、倒木這些地形上的東西這裡看不到：區塊載入時照 WebReach 再檢查一次（WalkableCleanup）
        if (random.nextDouble() < 0.35) {
            final int webZ = z + (random.nextBoolean() ? 1 : 0);
            final int webY = y + height - 2;
            if (webY >= highestAround(x + 2, webZ) + WEB_CLEARANCE) {
                sink.set(x + 2, webY, webZ, B.COBWEB);
            }
        }
    }

    static void mangroveTree(final BlockSink sink, final SplittableRandom random, final int x, final int y, final int z) {
        final int rootHeight = 2 + random.nextInt(2);
        // 根：往四周斜下去
        for (final int[] dir : new int[][] {{1, 0}, {-1, 0}, {0, 1}, {0, -1}}) {
            for (int step = 1; step <= 2; step++) {
                final int rx = x + dir[0] * step;
                final int rz = z + dir[1] * step;
                for (int ry = y - 1; ry < y + rootHeight - step + 1; ry++) {
                    sink.set(rx, ry, rz, B.MANGROVE_ROOTS);
                }
            }
        }
        final int height = rootHeight + 3 + random.nextInt(3);
        blob(sink, random, x, y + height, z, 3.2, 2.0, B.MANGROVE_LEAVES, 0.12);
        for (int dy = rootHeight; dy <= height; dy++) {
            sink.set(x, y + dy, z, B.MANGROVE_LOG);
        }
        for (int dy = 0; dy < rootHeight; dy++) {
            sink.set(x, y + dy, z, B.MANGROVE_ROOTS);
        }
    }

    static void deadTree(final BlockSink sink, final SplittableRandom random, final int x, final int y, final int z,
                         final int log, final boolean charred) {
        final int height = 3 + random.nextInt(4);
        column(sink, x, y, z, height, charred && random.nextBoolean() ? B.BASALT : log);
        final int branch = 1 + random.nextInt(2);
        for (int i = 0; i < branch; i++) {
            final int dir = random.nextInt(4);
            final int bx = dir == 0 ? 1 : dir == 1 ? -1 : 0;
            final int bz = dir == 2 ? 1 : dir == 3 ? -1 : 0;
            final int by = y + height - 1 - i;
            sink.set(x + bx, by, z + bz, log);
            sink.set(x + bx * 2, by + 1, z + bz * 2, log);
        }
        if (charred) {
            sink.set(x, y - 1, z, B.COAL_BLOCK);
        }
    }

    static void fallenLog(final BlockSink sink, final SplittableRandom random, final int x, final int y, final int z,
                          final int logX, final int logZ) {
        final boolean alongX = random.nextBoolean();
        final int length = 3 + random.nextInt(3);
        for (int i = 0; i < length; i++) {
            sink.set(x + (alongX ? i : 0), y, z + (alongX ? 0 : i), alongX ? logX : logZ);
            if (random.nextDouble() < 0.3) {
                sink.set(x + (alongX ? i : 0), y + 1, z + (alongX ? 0 : i), B.MOSS_CARPET);
            }
        }
    }

    static void driftwood(final BlockSink sink, final SplittableRandom random, final int x, final int y, final int z) {
        fallenLog(sink, random, x, y, z, B.SPRUCE_LOG_X, B.SPRUCE_LOG_Z);
    }

    static void wreck(final BlockSink sink, final SplittableRandom random, final int x, final int y, final int z) {
        // 擱淺的小船殘骸：船底木板＋幾根肋骨
        final boolean alongX = random.nextBoolean();
        for (int i = -2; i <= 2; i++) {
            final int px = x + (alongX ? i : 0);
            final int pz = z + (alongX ? 0 : i);
            sink.set(px, y - 1, pz, B.DARK_OAK_PLANKS);
            if ((i & 1) == 0) {
                sink.set(px + (alongX ? 0 : 1), y, pz + (alongX ? 1 : 0), B.DARK_OAK_FENCE);
                sink.set(px - (alongX ? 0 : 1), y, pz - (alongX ? 1 : 0), B.DARK_OAK_FENCE);
                if (random.nextBoolean()) {
                    sink.set(px + (alongX ? 0 : 1), y + 1, pz + (alongX ? 1 : 0), B.DARK_OAK_FENCE);
                }
            }
        }
    }

    static void barrels(final BlockSink sink, final SplittableRandom random, final int x, final int y, final int z) {
        sink.set(x, y, z, B.BARREL);
        if (random.nextBoolean()) {
            sink.set(x + 1, y, z, B.BARREL);
        }
        if (random.nextBoolean()) {
            sink.set(x, y + 1, z, B.BARREL);
        }
    }

    static void boulder(final BlockSink sink, final SplittableRandom random, final int x, final int y, final int z,
                        final int main, final int accent, final int radius) {
        final int top = radius == 1 ? 1 : 2;
        for (int dx = -radius; dx <= radius; dx++) {
            for (int dz = -radius; dz <= radius; dz++) {
                for (int dy = -1; dy <= top; dy++) {
                    final double d = Math.sqrt(dx * dx + dz * dz + dy * dy * 1.6);
                    if (d <= radius + 0.3 + random.nextDouble() * 0.5) {
                        sink.set(x + dx, y + dy, z + dz, random.nextDouble() < 0.3 ? accent : main);
                    }
                }
            }
        }
    }

    static void bush(final BlockSink sink, final SplittableRandom random, final int x, final int y, final int z, final int leaves) {
        blob(sink, random, x, y, z, 1.4, 1.0, leaves, 0.2);
    }

    static void ruinWall(final BlockSink sink, final SplittableRandom random, final int x, final int y, final int z) {
        final boolean alongX = random.nextBoolean();
        final int length = 4 + random.nextInt(6);
        final int start = -length / 2;
        for (int i = start; i < start + length; i++) {
            final int height = 1 + random.nextInt(4) + (Math.abs(i) < 2 ? 1 : 0);
            final int px = x + (alongX ? i : 0);
            final int pz = z + (alongX ? 0 : i);
            final int base = top(px, pz) + 1;
            for (int dy = -1; dy < height; dy++) {
                if (dy > 0 && random.nextDouble() < 0.12) {
                    continue; // 缺角
                }
                final double r = random.nextDouble();
                sink.set(px, base + dy, pz, r < 0.5 ? B.STONE_BRICKS : r < 0.8 ? B.CRACKED_BRICKS : B.MOSSY_BRICKS);
            }
            if (random.nextDouble() < 0.15) {
                sink.set(px + (alongX ? 0 : 1), y, pz + (alongX ? 1 : 0), B.STONE_BRICK_SLAB);
            }
        }
    }

    /** 燒毀的房子：四面殘牆（高低不一、有門洞）、焦黑的倒梁、屋裡的碎石地。牆腳跟著地形。 */
    static void ruinedHouse(final BlockSink sink, final SplittableRandom random, final int x, final int z) {
        final int halfX = 2 + random.nextInt(2);
        final int halfZ = 2 + random.nextInt(2);
        final int door = random.nextInt(4);
        for (int dx = -halfX; dx <= halfX; dx++) {
            for (int dz = -halfZ; dz <= halfZ; dz++) {
                final int px = x + dx;
                final int pz = z + dz;
                final int base = top(px, pz) + 1;
                final boolean wallX = Math.abs(dx) == halfX;
                final boolean wallZ = Math.abs(dz) == halfZ;
                if (!wallX && !wallZ) {
                    // 屋內：碎石地、偶爾一塊燒黑的地板
                    final double r = random.nextDouble();
                    sink.set(px, base - 1, pz, r < 0.35 ? B.COBBLE : r < 0.6 ? B.GRAVEL : r < 0.75 ? B.COAL_BLOCK : B.COARSE_DIRT);
                    continue;
                }
                final boolean doorway = switch (door) {
                    case 0 -> dx == halfX && dz == 0;
                    case 1 -> dx == -halfX && dz == 0;
                    case 2 -> dz == halfZ && dx == 0;
                    default -> dz == -halfZ && dx == 0;
                };
                final boolean corner = wallX && wallZ;
                final int height = doorway ? 0 : corner ? 4 + random.nextInt(2) : 1 + random.nextInt(4);
                sink.set(px, base - 1, pz, B.STONE_BRICKS);
                for (int dy = 0; dy < height; dy++) {
                    final double r = random.nextDouble();
                    sink.set(px, base + dy, pz, r < 0.4 ? B.STONE_BRICKS : r < 0.7 ? B.CRACKED_BRICKS
                            : r < 0.85 ? B.MOSSY_BRICKS : B.BLACKSTONE_BRICKS);
                }
                if (doorway) {
                    sink.set(px, base, pz, B.AIR);
                    sink.set(px, base + 1, pz, B.AIR);
                }
            }
        }
        // 倒下來的焦黑屋梁
        if (random.nextDouble() < 0.7) {
            final boolean alongX = random.nextBoolean();
            final int length = (alongX ? halfX : halfZ) * 2 - 1;
            final int offset = random.nextInt(3) - 1;
            for (int i = 0; i < length; i++) {
                final int px = x + (alongX ? i - length / 2 : offset);
                final int pz = z + (alongX ? offset : i - length / 2);
                sink.set(px, top(px, pz) + 1 + (i == 0 ? 1 : 0), pz, alongX ? B.DARK_OAK_LOG_X : B.DARK_OAK_LOG_Z);
            }
        }
        if (random.nextDouble() < 0.4) {
            sink.set(x, top(x, z) + 1, z, B.CAMPFIRE_OUT);
        }
    }

    static void ruinPillar(final BlockSink sink, final SplittableRandom random, final int x, final int y, final int z) {
        final int height = 2 + random.nextInt(5);
        sink.set(x, y - 1, z, B.STONE_BRICKS);
        for (int dy = 0; dy < height; dy++) {
            sink.set(x, y + dy, z, dy == height - 1 ? B.CHISELED_BRICKS : random.nextDouble() < 0.4 ? B.CRACKED_BRICKS : B.STONE_BRICKS);
        }
        // 倒在旁邊的一截
        if (random.nextDouble() < 0.5) {
            final int dir = random.nextInt(4);
            final int bx = dir == 0 ? 1 : dir == 1 ? -1 : 0;
            final int bz = dir == 2 ? 1 : dir == 3 ? -1 : 0;
            for (int i = 2; i <= 3; i++) {
                sink.set(x + bx * i, y, z + bz * i, B.CRACKED_BRICKS);
            }
        }
    }

    static void rubble(final BlockSink sink, final SplittableRandom random, final int x, final int y, final int z) {
        for (int i = 0; i < 6; i++) {
            final int dx = random.nextInt(3) - 1;
            final int dz = random.nextInt(3) - 1;
            final double r = random.nextDouble();
            sink.set(x + dx, y, z + dz, r < 0.3 ? B.COBBLE : r < 0.55 ? B.GRAVEL : r < 0.8 ? B.STONE_BRICK_SLAB : B.COAL_BLOCK);
        }
        if (random.nextDouble() < 0.3) {
            sink.set(x, y + 1, z, B.CAMPFIRE_OUT);
        }
    }

    static void bones(final BlockSink sink, final SplittableRandom random, final int x, final int y, final int z) {
        sink.set(x, y, z, B.BONE_BLOCK);
        if (random.nextBoolean()) {
            sink.set(x + 1, y, z, B.BONE_BLOCK);
            sink.set(x + 1, y + 1, z, B.SKULL);
        }
    }

    static void iceSpike(final BlockSink sink, final SplittableRandom random, final int x, final int y, final int z) {
        final int height = 3 + random.nextInt(6);
        for (int dy = -1; dy < height; dy++) {
            sink.set(x, y + dy, z, B.PACKED_ICE);
            if (dy < height / 2) {
                for (final int[] dir : new int[][] {{1, 0}, {-1, 0}, {0, 1}, {0, -1}}) {
                    if (random.nextDouble() < 0.6) {
                        sink.set(x + dir[0], y + dy, z + dir[1], B.PACKED_ICE);
                    }
                }
            }
        }
    }

    // ---- 小工具 ----

    static void column(final BlockSink sink, final int x, final int y, final int z, final int height, final int block) {
        for (int dy = 0; dy < height; dy++) {
            sink.set(x, y + dy, z, block);
        }
    }

    /** 橢球形的樹冠，邊緣隨機缺一點。 */
    static void blob(final BlockSink sink, final SplittableRandom random, final int x, final int y, final int z,
                     final double radius, final double halfHeight, final int block, final double holes) {
        final int r = (int) Math.ceil(radius);
        final int h = (int) Math.ceil(halfHeight);
        for (int dx = -r; dx <= r; dx++) {
            for (int dz = -r; dz <= r; dz++) {
                for (int dy = -h; dy <= h; dy++) {
                    final double d = (dx * dx + dz * dz) / (radius * radius) + (dy * dy) / (halfHeight * halfHeight);
                    if (d <= 1.0 && !(d > 0.6 && random.nextDouble() < holes)) {
                        sink.set(x + dx, y + dy, z + dz, block);
                    }
                }
            }
        }
    }

    static void disc(final BlockSink sink, final int x, final int y, final int z, final double radius, final int block) {
        final int r = (int) Math.ceil(radius);
        for (int dx = -r; dx <= r; dx++) {
            for (int dz = -r; dz <= r; dz++) {
                if (dx * dx + dz * dz <= radius * radius + 0.5) {
                    sink.set(x + dx, y, z + dz, block);
                }
            }
        }
    }

    private static void discTop(final BlockSink sink, final SplittableRandom random, final int x, final int y, final int z,
                                final double radius, final int block, final double chance) {
        final int r = (int) Math.ceil(radius);
        for (int dx = -r; dx <= r; dx++) {
            for (int dz = -r; dz <= r; dz++) {
                if ((dx != 0 || dz != 0) && dx * dx + dz * dz <= radius * radius && random.nextDouble() < chance) {
                    sink.set(x + dx, y, z + dz, block);
                }
            }
        }
    }
}
