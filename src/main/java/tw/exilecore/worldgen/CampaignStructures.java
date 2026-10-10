package tw.exilecore.worldgen;

import java.util.ArrayList;
import java.util.List;
import java.util.SplittableRandom;
import java.util.function.Consumer;

import static tw.exilecore.worldgen.CampaignLayout.CAMP_HALF;
import static tw.exilecore.worldgen.CampaignLayout.CAMP_Y;
import static tw.exilecore.worldgen.CampaignLayout.SEA_LEVEL;
import static tw.exilecore.worldgen.CampaignLayout.SEED;
import static tw.exilecore.worldgen.CampaignLayout.Theme;

/**
 * 劇情世界的建築：海邊的流放者營地（木柵欄、門樓、瞭望塔、營火廣場、帳篷、每個 NPC 一個攤位、
 * 地圖裝置平台與六道傳送門）、碼頭與押送船、每個檢查點的祭壇、每個區域的 Boss 場地、區域交界的石拱門、山頂的星骸。
 * <p>
 * 每個建築是一個有範圍的「零件」，生成區塊時只蓋跟那個區塊相交的零件（{@link BlockSink} 會切掉區塊外的方塊）。
 * NPC 與傳送門的位置在 {@link #npcSpot} 與 {@link #gate}，擺版面時用同一組座標。
 */
public final class CampaignStructures {

    /** 站在營地地面上的高度。 */
    static final int GROUND = CAMP_Y + 1;

    /** 地圖裝置平台的中心與傳送門。 */
    public static final int DEVICE_X = 0;
    public static final int DEVICE_Z = -18;
    /** 地圖裝置平台的高度（最上面一格；站在上面是 68）。 */
    public static final int DEVICE_TOP = CAMP_Y + 1;
    public static final int GATE_COUNT = 6;
    /** 傳送門那一排的 z（門洞），後面一格是牆。 */
    public static final int GATE_Z = -28;
    private static final int[] GATE_X = {-15, -9, -3, 3, 9, 15};

    /** 營地 NPC 的種類（跟 npc 模組的 NpcType 名稱一樣）。 */
    public enum Npc { IDENTIFIER, STASH, SALVAGE, WAYPOINT, MAP_DEVICE, VENDOR, MARKET, HEIST }

    /** 後來才加的 NPC（商人、市集掌櫃、盜賊首領）：已經產生的世界裡要補蓋攤位（見 {@link #stallFor}）。 */
    public static final java.util.Set<Npc> ADDED_LATER = java.util.EnumSet.of(Npc.VENDOR, Npc.MARKET, Npc.HEIST);

    /** 一個零件：水平範圍＋怎麼蓋。 */
    record Part(int minX, int minZ, int maxX, int maxZ, Consumer<BlockSink> builder) {
    }

    private static final List<Part> PARTS = buildParts();

    private CampaignStructures() {
    }

    /** 蓋跟這個區塊相交的零件。 */
    public static void generate(final int chunkX, final int chunkZ, final BlockSink sink) {
        final int minX = chunkX << 4;
        final int minZ = chunkZ << 4;
        for (final Part part : PARTS) {
            if (part.maxX() >= minX && part.minX() <= minX + 15 && part.maxZ() >= minZ && part.minZ() <= minZ + 15) {
                part.builder().accept(sink);
            }
        }
    }

    /** 零件數（測試用）。 */
    static int partCount() {
        return PARTS.size();
    }

    // ---- 給擺版面用的座標 ----

    /** NPC 站的位置（方塊中心、站的高度、面向）。 */
    public static CampaignLayout.Spot npcSpot(final Npc npc) {
        return switch (npc) {
            case IDENTIFIER -> new CampaignLayout.Spot(-15.5, GROUND, -7.5, -90f);
            case STASH -> new CampaignLayout.Spot(16.5, GROUND, -7.5, 90f);
            case SALVAGE -> new CampaignLayout.Spot(16.5, GROUND, 10.5, 90f);
            case WAYPOINT -> new CampaignLayout.Spot(22.5, GROUND, -6.5, 90f);
            case MAP_DEVICE -> new CampaignLayout.Spot(DEVICE_X + 0.5, DEVICE_TOP + 1, DEVICE_Z + 0.5, 0f);
            case VENDOR -> new CampaignLayout.Spot(-15.5, GROUND, -17.5, -90f);
            // 2026-10：盜賊首領搬到原本市集掌櫃的攤位；市集掌櫃（只剩擺設）站到原本盜賊首領的攤位
            case MARKET -> new CampaignLayout.Spot(-15.5, GROUND, 8.5, -90f);
            case HEIST -> new CampaignLayout.Spot(16.5, GROUND, -17.5, 90f);
        };
    }

    /**
     * 一個 NPC 的攤位（只有站在攤位裡的 NPC 有；傳送師與地圖裝置沒有）。給已經產生的世界補蓋用：
     * 把攤位的方塊寫進 sink（世界座標）。
     *
     * @return 有沒有攤位
     */
    public static boolean stallFor(final Npc npc, final BlockSink sink) {
        final Part part = switch (npc) {
            case VENDOR -> stall(-16, -18, 1, B.GREEN_WOOL, Npc.VENDOR);
            case MARKET -> stall(16, -18, -1, B.YELLOW_WOOL, Npc.MARKET);
            case HEIST -> stall(-16, 8, 1, B.GRAY_WOOL, Npc.HEIST);
            default -> null;
        };
        if (part == null) {
            return false;
        }
        part.builder().accept(sink);
        return true;
    }

    /** 第 i 道傳送門：門洞中心（站的高度）。進門的判定範圍是門洞 3 格寬、4 格高、1 格深。 */
    public static CampaignLayout.Spot gate(final int index) {
        return new CampaignLayout.Spot(GATE_X[index] + 0.5, GROUND, GATE_Z + 0.5, 180f);
    }

    /** 從地圖回來的落點：地圖裝置平台前面，面向傳送門。 */
    public static CampaignLayout.Spot deviceReturn() {
        return new CampaignLayout.Spot(DEVICE_X + 0.5, GROUND, DEVICE_Z + 7.5, 180f);
    }

    // ---- 零件清單 ----

    private static List<Part> buildParts() {
        final List<Part> parts = new ArrayList<>();
        // 營地
        palisade(parts);
        parts.add(new Part(-9, -9, 9, 9, CampaignStructures::plaza));
        parts.add(new Part(DEVICE_X - 6, DEVICE_Z - 6, DEVICE_X + 6, DEVICE_Z + 6, CampaignStructures::deviceDais));
        parts.add(new Part(-19, GATE_Z - 1, 19, GATE_Z + 4, CampaignStructures::gates));
        parts.add(stall(-16, -8, 1, B.PURPLE_WOOL, Npc.IDENTIFIER));
        parts.add(stall(16, -8, -1, B.BLUE_WOOL, Npc.STASH));
        parts.add(stall(16, 10, -1, B.RED_WOOL, Npc.SALVAGE));
        parts.add(stall(-16, -18, 1, B.GREEN_WOOL, Npc.VENDOR));
        parts.add(stall(16, -18, -1, B.YELLOW_WOOL, Npc.MARKET));
        parts.add(stall(-16, 8, 1, B.GRAY_WOOL, Npc.HEIST));
        parts.add(new Part(22, -10, 28, -4, CampaignStructures::waypointObelisk));
        final int[][] tents = {{-22, 16, 0}, {-12, 24, 1}, {-2, 27, 2}, {-26, 26, 3}, {6, 22, 0}, {-24, -20, 1}, {-14, 14, 2}};
        for (final int[] tent : tents) {
            parts.add(new Part(tent[0] - 3, tent[1] - 3, tent[0] + 3, tent[1] + 3, sink -> tent(sink, tent[0], tent[1], tent[2])));
        }
        parts.add(new Part(-32, -32, 32, 32, CampaignStructures::campProps));
        // 海灘、碼頭、押送船
        parts.add(new Part(-104, -7, -34, 7, CampaignStructures::dock));
        parts.add(new Part(-136, -8, -100, 8, CampaignStructures::ship));
        // 各區域
        for (int zone = 0; zone < CampaignLayout.ZONE_COUNT; zone++) {
            final int index = zone;
            final Theme theme = CampaignLayout.zoneTheme(zone);
            for (int which = 0; which < 3; which++) {
                final CampaignLayout.Spot spot = CampaignLayout.checkpoint(zone, which);
                final int x = (int) Math.floor(spot.x());
                final int z = (int) Math.floor(spot.z());
                parts.add(new Part(x - 5, z - 5, x + 5, z + 5, sink -> shrine(sink, x, z, theme)));
            }
            final CampaignLayout.Spot arena = CampaignLayout.arena(zone);
            final int ax = (int) Math.floor(arena.x());
            final int az = (int) Math.floor(arena.z());
            final int radius = CampaignLayout.ARENA_RADIUS;
            parts.add(new Part(ax - radius - 1, az - radius - 1, ax + radius + 1, az + radius + 1, sink -> arena(sink, ax, az, theme, index)));
            final int gateX = CampaignLayout.zoneStart(zone);
            final int gateZ = (int) Math.round(CampaignLayout.roadZ(gateX));
            parts.add(new Part(gateX - 1, gateZ - 6, gateX + 1, gateZ + 6, sink -> zoneGate(sink, gateX, gateZ, theme)));
            // 路燈：每 36 格一盞，左右交替
            for (int x = CampaignLayout.zoneStart(zone) + 20; x < CampaignLayout.zoneStart(zone) + CampaignLayout.ZONE_LENGTH; x += 36) {
                final int lampX = x;
                final int side = ((x / 36) & 1) == 0 ? 1 : -1;
                final int lampZ = (int) Math.round(CampaignLayout.roadZ(lampX)) + side * (CampaignLayout.ROAD_HALF + 2);
                if (CampaignLayout.flatness(lampX + 0.5, lampZ + 0.5) > 0) {
                    continue;
                }
                parts.add(new Part(lampX - 1, lampZ - 1, lampX + 1, lampZ + 1, sink -> lamp(sink, lampX, lampZ, theme)));
            }
        }
        final int starZ = (int) Math.round(CampaignLayout.roadZ(CampaignLayout.STAR_X));
        parts.add(new Part(CampaignLayout.STAR_X - 14, starZ - 14, CampaignLayout.STAR_X + 14, starZ + 14,
                sink -> star(sink, CampaignLayout.STAR_X, starZ)));
        return List.copyOf(parts);
    }

    // ---- 營地 ----

    /** 木柵欄：四邊各拆成幾段，東西兩邊中間留門。 */
    private static void palisade(final List<Part> parts) {
        for (int from = -CAMP_HALF; from <= CAMP_HALF; from += 17) {
            final int start = from;
            final int end = Math.min(CAMP_HALF, from + 16);
            parts.add(new Part(start, -CAMP_HALF, end, -CAMP_HALF, sink -> fenceRun(sink, start, end, true, -CAMP_HALF)));
            parts.add(new Part(start, CAMP_HALF, end, CAMP_HALF, sink -> fenceRun(sink, start, end, true, CAMP_HALF)));
            parts.add(new Part(-CAMP_HALF, start, -CAMP_HALF, end, sink -> fenceRun(sink, start, end, false, -CAMP_HALF)));
            parts.add(new Part(CAMP_HALF, start, CAMP_HALF, end, sink -> fenceRun(sink, start, end, false, CAMP_HALF)));
        }
        for (final int side : new int[] {-1, 1}) {
            final int x = side * CAMP_HALF;
            parts.add(new Part(x - 1, -5, x + 1, 5, sink -> campGate(sink, x)));
            for (final int zSide : new int[] {-1, 1}) {
                final int cx = side * (CAMP_HALF - 1);
                final int cz = zSide * (CAMP_HALF - 1);
                parts.add(new Part(cx - 2, cz - 2, cx + 2, cz + 2, sink -> watchtower(sink, cx, cz)));
            }
        }
    }

    /** 一段柵欄：尖頭的原木，門的位置留空。 */
    private static void fenceRun(final BlockSink sink, final int from, final int to, final boolean alongX, final int fixed) {
        for (int i = from; i <= to; i++) {
            final int x = alongX ? i : fixed;
            final int z = alongX ? fixed : i;
            if (!alongX && Math.abs(z) <= 3) {
                continue; // 東門、西門
            }
            final int height = 4 + (Math.floorMod(i * 7 + fixed, 3) == 0 ? 1 : 0);
            sink.set(x, CAMP_Y, z, B.COBBLE);
            for (int y = GROUND; y < GROUND + height; y++) {
                sink.set(x, y, z, B.SPRUCE_LOG);
            }
            sink.set(x, GROUND + height, z, B.SPRUCE_FENCE);
        }
    }

    /** 門樓：兩根高柱＋橫樑＋掛燈，地上鋪石。 */
    private static void campGate(final BlockSink sink, final int x) {
        for (final int z : new int[] {-4, 4}) {
            for (int y = GROUND; y <= GROUND + 6; y++) {
                sink.set(x, y, z, B.STRIPPED_SPRUCE_LOG);
            }
            sink.set(x, GROUND + 7, z, B.SPRUCE_FENCE);
        }
        for (int z = -4; z <= 4; z++) {
            sink.set(x, GROUND + 6, z, B.SPRUCE_LOG_Z);
            sink.set(x, CAMP_Y, z, Math.abs(z) <= 2 ? B.COBBLE : B.GRAVEL);
        }
        sink.set(x, GROUND + 5, -2, B.LANTERN_HANGING);
        sink.set(x, GROUND + 5, 2, B.LANTERN_HANGING);
        // 門上的布旗
        sink.set(x, GROUND + 7, 0, B.RED_WOOL);
        sink.set(x, GROUND + 8, 0, B.SPRUCE_FENCE);
    }

    /** 角落的瞭望塔：3×3 的柱腳、平台、欄杆、燈。 */
    private static void watchtower(final BlockSink sink, final int cx, final int cz) {
        final int deck = GROUND + 8;
        for (final int dx : new int[] {-1, 1}) {
            for (final int dz : new int[] {-1, 1}) {
                for (int y = GROUND; y < deck; y++) {
                    sink.set(cx + dx, y, cz + dz, B.SPRUCE_LOG);
                }
            }
        }
        for (int dx = -2; dx <= 2; dx++) {
            for (int dz = -2; dz <= 2; dz++) {
                sink.set(cx + dx, deck, cz + dz, B.SPRUCE_PLANKS);
                if (Math.abs(dx) == 2 || Math.abs(dz) == 2) {
                    sink.set(cx + dx, deck + 1, cz + dz, B.SPRUCE_FENCE);
                }
            }
        }
        for (final int dx : new int[] {-2, 2}) {
            for (final int dz : new int[] {-2, 2}) {
                sink.set(cx + dx, deck + 2, cz + dz, B.SPRUCE_FENCE);
                sink.set(cx + dx, deck + 3, cz + dz, B.SPRUCE_SLAB);
            }
        }
        sink.set(cx, deck + 1, cz, B.LANTERN);
        // 梯子不做：塔是裝飾，玩家上不去
    }

    /** 營火廣場：石與碎石鋪地，中間營火，東西兩側原木長椅，四角路燈。 */
    private static void plaza(final BlockSink sink) {
        for (int x = -8; x <= 8; x++) {
            for (int z = -8; z <= 8; z++) {
                final double d = Math.sqrt(x * x + z * z);
                if (d > 8.4) {
                    continue;
                }
                final double r = Noise.random01(SEED + 101, x, z);
                sink.set(x, CAMP_Y, z, d < 1.6 ? B.COBBLE : r < 0.35 ? B.COBBLE : r < 0.6 ? B.GRAVEL : r < 0.85 ? B.COARSE_DIRT : B.PACKED_MUD);
                for (int y = GROUND; y < GROUND + 3; y++) {
                    sink.set(x, y, z, B.AIR);
                }
            }
        }
        sink.set(0, GROUND, 0, B.CAMPFIRE);
        for (int i = -1; i <= 1; i++) {
            sink.set(-3, GROUND, i, B.SPRUCE_LOG_Z);
            sink.set(3, GROUND, i, B.SPRUCE_LOG_Z);
        }
        for (final int dx : new int[] {-6, 6}) {
            for (final int dz : new int[] {-6, 6}) {
                lampPost(sink, dx, GROUND, dz, B.LANTERN);
            }
        }
    }

    /** 地圖裝置的平台：高一格的圓台（邊緣是半磚），四角有星骸燈柱。NPC 站在正中間。 */
    private static void deviceDais(final BlockSink sink) {
        for (int dx = -6; dx <= 6; dx++) {
            for (int dz = -6; dz <= 6; dz++) {
                final double d = Math.sqrt(dx * dx + dz * dz);
                final int x = DEVICE_X + dx;
                final int z = DEVICE_Z + dz;
                if (d <= 4.6) {
                    sink.set(x, CAMP_Y, z, B.STONE_BRICKS);
                    sink.set(x, DEVICE_TOP, z, d <= 1.5 ? B.CHISELED_BRICKS : d > 3.6 ? B.STONE_BRICKS : B.POLISHED_ANDESITE);
                    for (int y = DEVICE_TOP + 1; y < DEVICE_TOP + 4; y++) {
                        sink.set(x, y, z, B.AIR);
                    }
                } else if (d <= 5.6) {
                    sink.set(x, CAMP_Y, z, B.STONE_BRICKS);
                    sink.set(x, DEVICE_TOP, z, B.STONE_BRICK_SLAB);
                }
            }
        }
        for (final int dx : new int[] {-3, 3}) {
            for (final int dz : new int[] {-3, 3}) {
                sink.set(DEVICE_X + dx, DEVICE_TOP + 1, DEVICE_Z + dz, B.STONE_BRICK_WALL);
                sink.set(DEVICE_X + dx, DEVICE_TOP + 2, DEVICE_Z + dz, B.AMETHYST);
                sink.set(DEVICE_X + dx, DEVICE_TOP + 3, DEVICE_Z + dz, B.AMETHYST_CLUSTER);
            }
        }
    }

    /** 六道傳送門：黑石磚拱門，門洞後面是哭泣黑曜石的牆；門前鋪石磚走道。 */
    private static void gates(final BlockSink sink) {
        for (int x = -19; x <= 19; x++) {
            for (int z = GATE_Z + 1; z <= GATE_Z + 4; z++) {
                sink.set(x, CAMP_Y, z, Noise.random01(SEED + 103, x, z) < 0.2 ? B.CRACKED_BRICKS : B.STONE_BRICKS);
                sink.set(x, GROUND, z, B.AIR); // 石磚上不留草
                sink.set(x, GROUND + 1, z, B.AIR);
            }
        }
        for (final int gx : GATE_X) {
            // 牆：門洞後面與兩側
            for (int x = gx - 2; x <= gx + 2; x++) {
                sink.set(x, CAMP_Y, GATE_Z, Math.abs(x - gx) <= 1 ? B.CRYING_OBSIDIAN : B.POLISHED_BLACKSTONE);
                sink.set(x, CAMP_Y, GATE_Z - 1, B.POLISHED_BLACKSTONE);
                for (int y = GROUND; y <= GROUND + 4; y++) {
                    sink.set(x, y, GATE_Z - 1, Math.abs(x - gx) <= 1 && y < GROUND + 4 ? B.OBSIDIAN : B.BLACKSTONE_BRICKS);
                }
            }
            for (final int px : new int[] {gx - 2, gx + 2}) {
                for (int y = GROUND; y <= GROUND + 3; y++) {
                    sink.set(px, y, GATE_Z, y == GROUND + 3 ? B.CHISELED_DEEPSLATE : B.BLACKSTONE_BRICKS);
                }
            }
            for (int x = gx - 2; x <= gx + 2; x++) {
                sink.set(x, GROUND + 4, GATE_Z, x == gx ? B.GILDED_BLACKSTONE : B.POLISHED_BLACKSTONE);
            }
            for (int x = gx - 1; x <= gx + 1; x++) {
                for (int y = GROUND; y < GROUND + 4; y++) {
                    sink.set(x, y, GATE_Z, B.AIR);
                }
            }
            sink.set(gx, GROUND + 5, GATE_Z, B.AMETHYST_CLUSTER);
            // 門柱前面掛燈：上面伸出一塊石頭吊著（不然一更新就掉下來）
            for (final int lx : new int[] {gx - 2, gx + 2}) {
                sink.set(lx, GROUND + 5, GATE_Z + 1, B.POLISHED_BLACKSTONE);
                sink.set(lx, GROUND + 4, GATE_Z + 1, B.SOUL_LANTERN_HANGING);
            }
        }
    }

    /**
     * NPC 的攤位：5×5 木板地、四根柱子、彩色布篷（跟白色交錯）、面向廣場那一邊是櫃台，後面放道具。
     *
     * @param front 櫃台在哪一邊：1 = 東邊（+x），−1 = 西邊
     */
    private static Part stall(final int cx, final int cz, final int front, final int color, final Npc npc) {
        return new Part(cx - 3, cz - 3, cx + 3, cz + 3, sink -> {
            for (int dx = -2; dx <= 2; dx++) {
                for (int dz = -2; dz <= 2; dz++) {
                    sink.set(cx + dx, CAMP_Y, cz + dz, B.SPRUCE_PLANKS);
                    for (int y = GROUND; y <= GROUND + 3; y++) {
                        sink.set(cx + dx, y, cz + dz, B.AIR);
                    }
                    // 布篷：斜一點（櫃台那邊低）
                    final int roof = GROUND + 3 + (dx * front < 0 ? 1 : 0);
                    sink.set(cx + dx, roof, cz + dz, ((dz + 2) & 1) == 0 ? color : B.WHITE_WOOL);
                }
            }
            for (final int dx : new int[] {-2, 2}) {
                for (final int dz : new int[] {-2, 2}) {
                    final int top = GROUND + 2 + (dx * front < 0 ? 1 : 0);
                    for (int y = GROUND; y <= top; y++) {
                        sink.set(cx + dx, y, cz + dz, B.SPRUCE_FENCE);
                    }
                }
            }
            // 櫃台：前面一排（中間留一格讓 NPC 看得到外面）
            final int counterX = cx + front * 2;
            for (final int dz : new int[] {-1, 1}) {
                sink.set(counterX, GROUND, cz + dz, B.SPRUCE_SLAB_TOP);
            }
            sink.set(cx - front, GROUND + 3, cz, B.LANTERN_HANGING); // 掛在布篷（後半高一格）底下
            // 後面的道具
            final int backX = cx - front * 2;
            switch (npc) {
                case IDENTIFIER -> {
                    sink.set(backX, GROUND, cz - 1, B.BOOKSHELF);
                    sink.set(backX, GROUND, cz + 1, B.BOOKSHELF);
                    sink.set(backX, GROUND + 1, cz - 1, B.BOOKSHELF);
                    sink.set(backX, GROUND, cz, B.LECTERN);
                    sink.set(counterX, GROUND + 1, cz - 1, B.CANDLE);
                }
                case STASH -> {
                    sink.set(backX, GROUND, cz - 1, B.BARREL);
                    sink.set(backX, GROUND, cz + 1, B.BARREL);
                    sink.set(backX, GROUND + 1, cz - 1, B.BARREL);
                    sink.set(backX, GROUND, cz, B.HAY);
                    sink.set(counterX, GROUND + 1, cz + 1, B.DECORATED_POT);
                }
                case SALVAGE -> {
                    sink.set(backX, GROUND, cz - 1, B.ANVIL);
                    sink.set(backX, GROUND, cz, B.SMOKER);
                    sink.set(backX, GROUND, cz + 1, B.GRINDSTONE);
                    sink.set(counterX, GROUND + 1, cz - 1, B.CRAFTING_TABLE);
                }
                case VENDOR -> {
                    sink.set(backX, GROUND, cz - 1, B.SMITHING_TABLE);
                    sink.set(backX, GROUND, cz, B.BARREL);
                    sink.set(backX, GROUND + 1, cz, B.BARREL);
                    sink.set(backX, GROUND, cz + 1, B.LOOM);
                    sink.set(counterX, GROUND + 1, cz + 1, B.DECORATED_POT);
                }
                case HEIST -> {
                    sink.set(backX, GROUND, cz - 1, B.BARREL);
                    sink.set(backX, GROUND, cz + 1, B.BARREL);
                    sink.set(backX, GROUND + 1, cz + 1, B.BARREL);
                    sink.set(backX, GROUND, cz, B.CARTOGRAPHY_TABLE);
                    sink.set(counterX, GROUND + 1, cz + 1, B.CANDLE);
                }
                case MARKET -> {
                    sink.set(backX, GROUND, cz - 1, B.CARTOGRAPHY_TABLE);
                    sink.set(backX, GROUND, cz, B.LECTERN);
                    sink.set(backX, GROUND, cz + 1, B.BOOKSHELF);
                    sink.set(counterX, GROUND + 1, cz - 1, B.BELL);
                }
                default -> {
                }
            }
        });
    }

    /** 傳送師旁邊的星骸方尖碑。 */
    private static void waypointObelisk(final BlockSink sink) {
        final int x = 25;
        final int z = -7;
        for (int dx = -1; dx <= 1; dx++) {
            for (int dz = -1; dz <= 1; dz++) {
                sink.set(x + dx, CAMP_Y, z + dz, B.STONE_BRICKS);
                sink.set(x + dx, GROUND, z + dz, dx == 0 && dz == 0 ? B.LODESTONE : B.STONE_BRICK_SLAB);
            }
        }
        for (int y = GROUND + 1; y <= GROUND + 4; y++) {
            sink.set(x, y, z, y == GROUND + 4 ? B.CHISELED_BRICKS : B.STONE_BRICKS);
        }
        sink.set(x, GROUND + 5, z, B.AMETHYST);
        sink.set(x, GROUND + 6, z, B.AMETHYST_CLUSTER);
    }

    /** 帳篷：人字形的布（樣式 0–3 換顏色），後面封起來，裡面鋪地毯、放乾草床。 */
    private static void tent(final BlockSink sink, final int cx, final int cz, final int style) {
        final int cloth = switch (style) {
            case 0 -> B.WHITE_WOOL;
            case 1 -> B.LIGHT_GRAY_WOOL;
            case 2 -> B.BROWN_WOOL;
            default -> B.GRAY_WOOL;
        };
        for (int dx = -2; dx <= 2; dx++) {
            for (int dz = -2; dz <= 2; dz++) {
                sink.set(cx + dx, GROUND + (2 - Math.abs(dz)), cz + dz, cloth);
                for (int y = GROUND; y < GROUND + (2 - Math.abs(dz)); y++) {
                    sink.set(cx + dx, y, cz + dz, dx == -2 ? cloth : B.AIR);
                }
                if (Math.abs(dz) <= 1 && dx > -2) {
                    sink.set(cx + dx, GROUND, cz + dz, dx == -1 && dz == 0 ? B.HAY : B.BROWN_CARPET);
                }
            }
        }
        sink.set(cx + 3, GROUND, cz, B.SPRUCE_FENCE);
        sink.set(cx + 3, GROUND + 1, cz, B.SPRUCE_FENCE);
        sink.set(cx + 3, GROUND + 2, cz, B.SPRUCE_FENCE);
        sink.set(cx - 3, GROUND, cz, B.SPRUCE_FENCE);
        sink.set(cx - 3, GROUND + 1, cz, B.SPRUCE_FENCE);
        sink.set(cx - 3, GROUND + 2, cz, B.SPRUCE_FENCE);
    }

    /** 營地裡零散的東西：貨物、柴堆、路燈、晾衣架。 */
    private static void campProps(final BlockSink sink) {
        // 西門內的貨物堆（剛從船上卸下來）
        sink.set(-29, GROUND, 6, B.BARREL);
        sink.set(-29, GROUND, 7, B.BARREL);
        sink.set(-29, GROUND + 1, 6, B.BARREL);
        sink.set(-28, GROUND, 7, B.HAY);
        sink.set(-29, GROUND, -6, B.HAY);
        sink.set(-28, GROUND, -6, B.HAY);
        sink.set(-29, GROUND + 1, -6, B.HAY);
        // 柴堆
        for (int i = 0; i < 3; i++) {
            sink.set(-6 + i, GROUND, 13, B.SPRUCE_LOG_X);
            sink.set(-6 + i, GROUND + 1, 13, i == 1 ? B.SPRUCE_LOG_X : B.AIR);
        }
        // 營地中央大路兩旁的路燈
        for (final int x : new int[] {-24, -12, 12, 24}) {
            lampPost(sink, x, GROUND, -4, B.LANTERN);
            lampPost(sink, x, GROUND, 4, B.LANTERN);
        }
        // 傳送門走道兩頭的燈
        lampPost(sink, -20, GROUND, GATE_Z + 3, B.SOUL_LANTERN);
        lampPost(sink, 20, GROUND, GATE_Z + 3, B.SOUL_LANTERN);
        // 攤位之間的補給箱
        sink.set(20, GROUND, 14, B.BARREL);
        sink.set(21, GROUND, 14, B.HAY);
        sink.set(-20, GROUND, -12, B.CAULDRON);
    }

    private static void lampPost(final BlockSink sink, final int x, final int y, final int z, final int lantern) {
        sink.set(x, y, z, B.SPRUCE_FENCE);
        sink.set(x, y + 1, z, B.SPRUCE_FENCE);
        sink.set(x, y + 2, z, lantern);
    }

    // ---- 碼頭與押送船 ----

    /** 西門到海邊的小路，再接木造碼頭（海平面上一格），盡頭是 T 形平台。 */
    private static void dock(final BlockSink sink) {
        final int deck = SEA_LEVEL + 1;
        for (int x = -35; x >= -102; x--) {
            final boolean platform = x <= -98;
            final int half = platform ? 5 : 2;
            for (int z = -half; z <= half; z++) {
                final int top = CampaignTerrain.top(x, z);
                if (top >= deck) {
                    // 還在陸地上：鋪小路
                    if (Math.abs(z) <= 1) {
                        sink.set(x, top, z, Noise.random01(SEED + 107, x, z) < 0.3 ? B.GRAVEL : B.DIRT_PATH);
                        sink.set(x, top + 1, z, B.AIR);
                        sink.set(x, top + 2, z, B.AIR);
                    }
                    continue;
                }
                sink.set(x, deck, z, Math.abs(z) == half ? B.SPRUCE_SLAB_TOP : B.SPRUCE_PLANKS);
                for (int y = deck + 1; y <= deck + 3; y++) {
                    sink.set(x, y, z, B.AIR);
                }
                // 柱子：每 4 格，碼頭兩側
                if (Math.floorMod(x, 4) == 0 && Math.abs(z) == half) {
                    for (int y = top + 1; y < deck; y++) {
                        sink.set(x, y, z, B.SPRUCE_LOG);
                    }
                    sink.set(x, deck + 1, z, B.SPRUCE_FENCE);
                    if (Math.floorMod(x, 12) == 0) {
                        sink.set(x, deck + 2, z, B.LANTERN);
                    }
                }
            }
        }
        // 平台上的貨物與繫船柱
        sink.set(-99, deck + 1, 4, B.BARREL);
        sink.set(-100, deck + 1, 4, B.BARREL);
        sink.set(-99, deck + 1, -4, B.HAY);
        // 跳板到船上：碼頭（站 64）→ 半磚（站 64.5）→ 甲板（站 65）
        for (int z = -1; z <= 1; z++) {
            sink.set(-103, deck, z, B.SPRUCE_PLANKS);
            sink.set(-104, deck + 1, z, B.SPRUCE_SLAB);
            sink.set(-104, deck, z, B.SPRUCE_LOG_X);
        }
    }

    /** 押送船：船身漸窄的船殼、甲板、兩根桅杆與帆、甲板上的囚籠、船尾的艙房。 */
    private static void ship(final BlockSink sink) {
        final int bow = -134;
        final int stern = -105;
        final int keel = SEA_LEVEL - 5;
        final int deck = SEA_LEVEL + 2;
        final double length = stern - bow;
        for (int x = bow; x <= stern; x++) {
            final double t = (x - bow) / length;              // 0 = 船頭（西），1 = 船尾
            final double width = 5.2 * Math.sqrt(Math.max(0, Math.sin(Math.PI * Math.min(1, t * 0.85 + 0.12))));
            final int half = (int) Math.round(width);
            for (int z = -half; z <= half; z++) {
                final boolean side = Math.abs(z) == half;
                final int bottom = keel + (int) Math.round(Math.abs(z) * 0.6);
                final boolean end = x == bow || x == stern;
                for (int y = bottom; y <= deck; y++) {
                    if (y == bottom || side || (end && y < deck)) {
                        sink.set(x, y, z, y >= deck - 1 ? B.DARK_OAK_LOG_X : B.DARK_OAK_PLANKS);
                    } else if (y == deck) {
                        sink.set(x, y, z, B.SPRUCE_PLANKS);
                    } else {
                        sink.set(x, y, z, B.AIR);
                    }
                }
                if (side) {
                    sink.set(x, deck + 1, z, B.DARK_OAK_FENCE);
                } else {
                    for (int y = deck + 1; y <= deck + 4; y++) {
                        sink.set(x, y, z, B.AIR);
                    }
                }
            }
            if (x == bow || x == stern) {
                for (int z = -half; z <= half; z++) {
                    // 船尾中間留一個缺口接跳板
                    sink.set(x, deck + 1, z, x == stern && Math.abs(z) <= 1 ? B.AIR : B.DARK_OAK_FENCE);
                }
            }
        }
        // 船頭斜桅
        for (int i = 1; i <= 4; i++) {
            sink.set(bow - i, deck + i / 2, 0, B.SPRUCE_LOG_X);
        }
        // 兩根桅杆與帆
        for (final int mast : new int[] {-124, -112}) {
            final int sail = mast == -124 ? 4 : 3;
            for (int y = deck + 1; y <= deck + 15; y++) {
                sink.set(mast, y, 0, B.SPRUCE_LOG);
            }
            for (int z = -sail; z <= sail; z++) {
                sink.set(mast, deck + 14, z, B.SPRUCE_LOG_Z);
                for (int y = deck + 6; y <= deck + 13; y++) {
                    sink.set(mast + 1, y, z, Math.abs(z) == sail && y < deck + 10 ? B.AIR : B.WHITE_WOOL);
                }
            }
            sink.set(mast, deck + 16, 0, B.RED_WOOL);
        }
        // 囚籠（流放者就是從這裡被押上岸的）
        for (int x = -120; x <= -116; x++) {
            for (int z = -2; z <= 2; z++) {
                final boolean edgeX = x == -120 || x == -116;
                final boolean edgeZ = Math.abs(z) == 2;
                if (edgeX || edgeZ) {
                    for (int y = deck + 1; y <= deck + 2; y++) {
                        sink.set(x, y, z, edgeX && !edgeZ ? B.IRON_BARS_Z : edgeZ && !edgeX ? B.IRON_BARS_X : B.IRON_BARS);
                    }
                }
                sink.set(x, deck + 3, z, B.DARK_OAK_SLAB);
            }
        }
        sink.set(-118, deck + 1, 0, B.HAY);
        // 船頭的艙房（船尾空著，從碼頭的跳板上船）
        for (int x = -133; x <= -129; x++) {
            for (int z = -2; z <= 2; z++) {
                final boolean wall = x == -133 || x == -129 || Math.abs(z) == 2;
                for (int y = deck + 1; y <= deck + 3; y++) {
                    sink.set(x, y, z, wall ? B.DARK_OAK_PLANKS : B.AIR);
                }
                sink.set(x, deck + 4, z, B.SPRUCE_SLAB);
            }
        }
        sink.set(-129, deck + 1, 0, B.AIR);
        sink.set(-129, deck + 2, 0, B.AIR);
        sink.set(-131, deck + 3, 0, B.LANTERN_HANGING);
        sink.set(-109, deck + 1, 2, B.LANTERN);
        sink.set(-127, deck + 1, 3, B.LANTERN);
        sink.set(-122, deck + 1, -3, B.BARREL);
        sink.set(-121, deck + 1, -3, B.BARREL);
        sink.set(-110, deck + 1, -2, B.HAY);
    }

    // ---- 區域 ----

    /** 檢查點的祭壇：路上鋪一塊圓形石台，路邊兩盞燈柱。 */
    private static void shrine(final BlockSink sink, final int x, final int z, final Theme theme) {
        final int floor = CampaignTerrain.roadTop(x);
        for (int dx = -CampaignLayout.SHRINE_RADIUS; dx <= CampaignLayout.SHRINE_RADIUS; dx++) {
            for (int dz = -CampaignLayout.SHRINE_RADIUS; dz <= CampaignLayout.SHRINE_RADIUS; dz++) {
                final double d = Math.sqrt(dx * dx + dz * dz);
                if (d > CampaignLayout.SHRINE_RADIUS + 0.4) {
                    continue;
                }
                final int bx = x + dx;
                final int bz = z + dz;
                sink.set(bx, floor, bz, d < 1.2 ? B.CHISELED_BRICKS : paving(theme, bx, bz));
                for (int y = floor - 3; y < floor; y++) {
                    sink.set(bx, y, bz, B.STONE);
                }
                for (int y = floor + 1; y <= floor + 3; y++) {
                    sink.set(bx, y, bz, B.AIR);
                }
            }
        }
        final int lantern = theme == Theme.RUINS ? B.SOUL_LANTERN : B.LANTERN;
        for (final int side : new int[] {-1, 1}) {
            final int px = x + side * 2;
            final int pz = z + side * (CampaignLayout.SHRINE_RADIUS + 1);
            sink.set(px, floor, pz, B.STONE_BRICKS);
            sink.set(px, floor + 1, pz, B.STONE_BRICK_WALL);
            sink.set(px, floor + 2, pz, B.STONE_BRICK_WALL);
            sink.set(px, floor + 3, pz, lantern);
        }
    }

    /** 各主題的鋪地石。 */
    private static int paving(final Theme theme, final int x, final int z) {
        final double r = Noise.random01(SEED + 109, x, z);
        return switch (theme) {
            case CAMP, COAST -> r < 0.5 ? B.SMOOTH_SANDSTONE : r < 0.8 ? B.SANDSTONE : B.COBBLE;
            case FOREST -> r < 0.5 ? B.MOSSY_COBBLE : r < 0.8 ? B.MOSSY_BRICKS : B.COBBLE;
            case RUINS -> r < 0.5 ? B.STONE_BRICKS : r < 0.75 ? B.CRACKED_BRICKS : B.POLISHED_BLACKSTONE;
            case SWAMP -> r < 0.6 ? B.MUD_BRICKS : r < 0.85 ? B.PACKED_MUD : B.MOSSY_COBBLE;
            case MOUNTAIN -> r < 0.5 ? B.POLISHED_ANDESITE : r < 0.8 ? B.STONE_BRICKS : B.CALCITE;
        };
    }

    /** Boss 場地：半徑 22 的圓形地板（同心圓花紋）、一圈斷柱（留出路的方向）、中間的紋章。 */
    private static void arena(final BlockSink sink, final int cx, final int cz, final Theme theme, final int zone) {
        final int floor = CampaignTerrain.roadTop(cx);
        final int radius = CampaignLayout.ARENA_RADIUS;
        for (int dx = -radius; dx <= radius; dx++) {
            for (int dz = -radius; dz <= radius; dz++) {
                final double d = Math.sqrt(dx * dx + dz * dz);
                if (d > radius + 0.4) {
                    continue;
                }
                final int x = cx + dx;
                final int z = cz + dz;
                final int ring = (int) d;
                final int block = ring % 6 == 0 && ring > 0 ? accent(theme) : paving(theme, x, z);
                sink.set(x, floor, z, d < 2.2 ? B.CHISELED_BRICKS : block);
                for (int y = floor - 4; y < floor; y++) {
                    sink.set(x, y, z, B.STONE);
                }
                for (int y = floor + 1; y <= floor + 14; y++) {
                    sink.set(x, y, z, B.AIR);
                }
            }
        }
        // 一圈柱子：路的方向（東西）不擋
        final SplittableRandom random = new SplittableRandom(Noise.hash(SEED + 113, cx, zone));
        for (int i = 0; i < 12; i++) {
            final double angle = Math.PI * 2 * i / 12 + Math.PI / 12;
            if (Math.abs(Math.cos(angle)) > 0.9) {
                continue;
            }
            final int px = cx + (int) Math.round(Math.cos(angle) * (radius - 3));
            final int pz = cz + (int) Math.round(Math.sin(angle) * (radius - 3));
            final int height = 3 + random.nextInt(5);
            for (int y = floor + 1; y <= floor + height; y++) {
                sink.set(px, y, pz, pillar(theme, random));
            }
            if (height >= 5) {
                sink.set(px, floor + height + 1, pz, theme == Theme.RUINS ? B.SOUL_CAMPFIRE : B.CAMPFIRE);
            } else if (theme == Theme.FOREST) {
                sink.set(px, floor + height + 1, pz, B.MOSS_CARPET); // 長滿青苔的斷柱（以前是蜘蛛網；地上、柱頂都不放蜘蛛網了）
            }
        }
        // 主題的點綴
        for (int i = 0; i < 10; i++) {
            final double angle = random.nextDouble() * Math.PI * 2;
            final double distance = 6 + random.nextDouble() * (radius - 10);
            final int x = cx + (int) Math.round(Math.cos(angle) * distance);
            final int z = cz + (int) Math.round(Math.sin(angle) * distance);
            if (Math.abs(z - cz) <= CampaignLayout.ROAD_HALF + 1) {
                continue;
            }
            switch (theme) {
                case CAMP, COAST -> sink.set(x, floor + 1, z, random.nextBoolean() ? B.BARREL : B.DARK_PRISMARINE);
                // 林影織母的地盤：獵物的骨頭與青苔（以前是地上的蜘蛛網：Boss 戰踩進去速度剩四分之一）
                case FOREST -> sink.set(x, floor + 1, z, ((x + z) & 1) == 0 ? B.SKULL : B.MOSS_CARPET);
                case RUINS -> sink.set(x, floor + 1, z, random.nextBoolean() ? B.CRACKED_BRICKS : B.SKULL);
                case SWAMP -> sink.set(x, floor + 1, z, random.nextBoolean() ? B.BONE_BLOCK : B.SKULL);
                case MOUNTAIN -> sink.set(x, floor + 1, z, random.nextBoolean() ? B.PACKED_ICE : B.SNOW_LAYER2);
            }
        }
        // 紋章：中間一顆星骸
        sink.set(cx, floor + 1, cz, B.AIR);
        for (final int[] dir : new int[][] {{2, 2}, {2, -2}, {-2, 2}, {-2, -2}}) {
            sink.set(cx + dir[0], floor, cz + dir[1], B.AMETHYST);
        }
    }

    private static int accent(final Theme theme) {
        return switch (theme) {
            case CAMP, COAST -> B.PRISMARINE;
            case FOREST -> B.MOSS;
            case RUINS -> B.GILDED_BLACKSTONE;
            case SWAMP -> B.MUDDY_ROOTS;
            case MOUNTAIN -> B.BLUE_ICE;
        };
    }

    private static int pillar(final Theme theme, final SplittableRandom random) {
        final double r = random.nextDouble();
        return switch (theme) {
            case CAMP, COAST -> r < 0.6 ? B.SANDSTONE : B.DARK_PRISMARINE;
            case FOREST -> r < 0.5 ? B.MOSSY_BRICKS : B.MOSSY_COBBLE;
            case RUINS -> r < 0.5 ? B.CRACKED_BRICKS : r < 0.8 ? B.STONE_BRICKS : B.BLACKSTONE_BRICKS;
            case SWAMP -> r < 0.6 ? B.MUD_BRICKS : B.MANGROVE_LOG;
            case MOUNTAIN -> r < 0.5 ? B.PACKED_ICE : B.CALCITE;
        };
    }

    /** 區域交界的石拱門（進入哪個區域就用那個主題）。 */
    private static void zoneGate(final BlockSink sink, final int x, final int z, final Theme theme) {
        final int floor = CampaignTerrain.roadTop(x);
        for (final int side : new int[] {-1, 1}) {
            final int pz = z + side * (CampaignLayout.ROAD_HALF + 3);
            for (int y = floor - 2; y <= floor + 6; y++) {
                sink.set(x, y, pz, pillar(theme, new SplittableRandom(Noise.hash(SEED + 117, x, y * 31L + pz))));
            }
            sink.set(x, floor + 7, pz, theme == Theme.RUINS ? B.SOUL_LANTERN : B.LANTERN);
        }
        for (int dz = -(CampaignLayout.ROAD_HALF + 3); dz <= CampaignLayout.ROAD_HALF + 3; dz++) {
            sink.set(x, floor + 6, z + dz, dz == 0 ? B.CHISELED_BRICKS : B.STONE_BRICKS);
        }
        sink.set(x, floor + 5, z, B.LANTERN_HANGING);
    }

    /** 路燈：各主題的柱子＋燈。 */
    private static void lamp(final BlockSink sink, final int x, final int z, final Theme theme) {
        final int base = CampaignTerrain.top(x, z) + 1;
        // 水裡的柱子從水底長出來，高出水面兩格
        final int postTop = Math.max(base + 1, SEA_LEVEL + 2);
        final int post = switch (theme) {
            case RUINS -> B.STONE_BRICK_WALL;
            case MOUNTAIN -> B.COBBLE_WALL;
            case SWAMP -> B.MANGROVE_LOG;
            default -> B.SPRUCE_FENCE;
        };
        for (int y = base; y <= postTop; y++) {
            sink.set(x, y, z, post);
        }
        sink.set(x, postTop + 1, z, theme == Theme.RUINS ? B.SOUL_LANTERN : theme == Theme.MOUNTAIN ? B.TORCH : B.LANTERN);
    }

    /** 山頂：天穹之心最大的一塊星骸，斜插在隕石坑裡。 */
    private static void star(final BlockSink sink, final int cx, final int cz) {
        final int floor = CampaignTerrain.roadTop(cx);
        final SplittableRandom random = new SplittableRandom(SEED + 127);
        // 隕石坑：一圈黑石、玄武岩
        for (int dx = -13; dx <= 13; dx++) {
            for (int dz = -13; dz <= 13; dz++) {
                final double d = Math.sqrt(dx * dx + dz * dz);
                if (d > 13.4) {
                    continue;
                }
                final int x = cx + dx;
                final int z = cz + dz;
                final double r = Noise.random01(SEED + 131, x, z);
                final int y = floor - (d < 9 ? (int) Math.round((9 - d) / 3) : 0);
                for (int fill = y - 3; fill < y; fill++) {
                    sink.set(x, fill, z, B.STONE);
                }
                sink.set(x, y, z, d > 10 ? (r < 0.5 ? B.BASALT : B.SNOW_BLOCK) : r < 0.4 ? B.BLACKSTONE : r < 0.7 ? B.SMOOTH_BASALT : B.CALCITE);
                for (int above = y + 1; above <= floor + 22; above++) {
                    sink.set(x, above, z, B.AIR);
                }
            }
        }
        // 星骸本體：往東斜的一串晶體
        final int base = floor - 3;
        for (int i = 0; i < 18; i++) {
            final double radius = 4.2 * (1 - i / 20.0);
            final int ox = cx + i / 3;
            final int oy = base + i;
            final int r = (int) Math.ceil(radius);
            for (int dx = -r; dx <= r; dx++) {
                for (int dz = -r; dz <= r; dz++) {
                    if (dx * dx + dz * dz > radius * radius) {
                        continue;
                    }
                    final boolean shell = dx * dx + dz * dz > (radius - 1.2) * (radius - 1.2);
                    sink.set(ox + dx, oy, cz + dz, shell ? (random.nextDouble() < 0.25 ? B.BUDDING_AMETHYST : B.AMETHYST)
                            : random.nextDouble() < 0.3 ? B.SEA_LANTERN : B.CALCITE);
                }
            }
        }
        // 碎片與晶簇
        for (int i = 0; i < 26; i++) {
            final double angle = random.nextDouble() * Math.PI * 2;
            final double distance = 5 + random.nextDouble() * 7;
            final int x = cx + (int) Math.round(Math.cos(angle) * distance);
            final int z = cz + (int) Math.round(Math.sin(angle) * distance);
            final int y = floor + 1 - (distance < 9 ? (int) Math.round((9 - distance) / 3) : 0);
            if (random.nextDouble() < 0.4) {
                sink.set(x, y, z, B.AMETHYST);
                sink.set(x, y + 1, z, B.AMETHYST_CLUSTER);
            } else {
                sink.set(x, y, z, B.AMETHYST_CLUSTER);
            }
        }
    }
}
