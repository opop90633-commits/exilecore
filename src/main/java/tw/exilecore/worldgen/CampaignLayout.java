package tw.exilecore.worldgen;

/**
 * 劇情世界（1–60 級）的藍圖：營地在海邊，一條路往東穿過五個區域。全部是純函式，
 * 區塊生成與「擺版面」（檢查點、Boss 場地、NPC 的位置）用同一套座標，兩邊永遠對得上。
 * <pre>
 *            北（−z）：海
 *   ~~~~~ 船 碼頭 [營地] ==潮濕海岸== | 幽語森林 | 焚城廢墟 | 腐沼 | 霜頂山脈 ▲ 天穹之心
 *            南（+z）：懸崖
 *   x:   −130  −56   0   40        360       680       1000     1320        1640
 * </pre>
 * 每個區域 320 格長；路（{@link #roadZ}）在區域裡左右彎，兩側是可以走的丘陵，再外面是懸崖（海岸的北邊是海）。
 * 高度只用 {@link #FLOOR_Y} 以上，下面是虛空（整個世界受保護，沒人會挖到底）。
 */
public final class CampaignLayout {

    /** 地形用的固定種子：跟世界種子無關，每台伺服器生出來都一樣。 */
    public static final long SEED = 0x5EED_57A2_C0A5_7L;

    public static final int SEA_LEVEL = 62;
    /** 基岩的高度（下面是虛空）。 */
    public static final int FLOOR_Y = 40;

    // ---- 營地 ----
    /** 營地地面的最上面一格（站在 67）。 */
    public static final int CAMP_Y = 66;
    /** 木柵欄在 |x| 或 |z| = 34。 */
    public static final int CAMP_HALF = 34;
    /** 營地平台（完全平坦）的範圍 |x|、|z| ≤ 40，外面 12 格漸變到四周的地形。 */
    public static final int CAMP_FLAT = 40;
    /** 營地區域（安全區）的範圍，含海灘、碼頭與船。 */
    public static final int CAMP_BOX_MIN_X = -150;
    public static final int CAMP_BOX_MAX_X = 39;
    public static final int CAMP_BOX_MIN_Z = -72;
    public static final int CAMP_BOX_MAX_Z = 71;

    // ---- 戰鬥區域 ----
    public static final int ZONE_START_X = 40;
    public static final int ZONE_LENGTH = 320;
    public static final int ZONE_COUNT = 5;
    /** 最後一個區域（山頂）多延伸幾格，山頂的星骸也算在區域裡。 */
    public static final int LAST_ZONE_EXTRA = 80;
    public static final int ZONE_MIN_Z = -128;
    public static final int ZONE_MAX_Z = 127;
    public static final int ENTRY_OFFSET = 8;
    public static final int MIDWAY_OFFSET = 150;
    public static final int BOSS_FRONT_OFFSET = 255;
    public static final int ARENA_OFFSET = 290;
    /** Boss 場地的地板半徑（區域 Boss 的範圍是 20）。 */
    public static final int ARENA_RADIUS = 22;
    /** 檢查點的祭壇半徑（地形在這裡完全平坦）。 */
    public static final int SHRINE_RADIUS = 3;
    /** 路面半寬（路寬 5）。 */
    public static final int ROAD_HALF = 2;
    /** 世界的東端：山頂之後全部是懸崖。 */
    public static final int WORLD_END_X = ZONE_START_X + ZONE_COUNT * ZONE_LENGTH + LAST_ZONE_EXTRA;
    /** 山頂的星骸（天穹之心最大的一塊）。 */
    public static final int STAR_X = ZONE_START_X + ZONE_COUNT * ZONE_LENGTH + 30;

    /** 各區域的地形主題（依 zones.yml 的順序：海岸、森林、廢墟、沼澤、山脈）。 */
    public enum Theme { CAMP, COAST, FOREST, RUINS, SWAMP, MOUNTAIN }

    private static final Theme[] ZONE_THEMES = {Theme.COAST, Theme.FOREST, Theme.RUINS, Theme.SWAMP, Theme.MOUNTAIN};

    /**
     * 路面高度的控制點（x → 最上面一格的 y），中間用餘弦內插。Boss 場地前後 ±32 格是平的
     * （場地的地板整片同一個高度，路如果還在爬坡，場地邊緣會多出一格跳不上去的台階）。
     */
    private static final double[][] ROAD_HEIGHT = {
            {-400, 66}, {40, 66}, {200, 65}, {360, 67}, {520, 70}, {680, 68}, {840, 67},
            {1000, 64}, {1060, 63}, {1322, 63}, {1362, 66}, {1450, 78}, {1556, 90}, {1580, 96}, {2400, 96},
    };

    private CampaignLayout() {
    }

    // ---- 區域 ----

    public static int zoneStart(final int index) {
        return ZONE_START_X + index * ZONE_LENGTH;
    }

    /** 區域的東端（含）。 */
    public static int zoneEnd(final int index) {
        return zoneStart(index) + ZONE_LENGTH - 1 + (index == ZONE_COUNT - 1 ? LAST_ZONE_EXTRA : 0);
    }

    /** x 在第幾個區域；營地那邊回傳 −1，超過最後一個回傳最後一個。 */
    public static int zoneIndex(final double x) {
        if (x < ZONE_START_X) {
            return -1;
        }
        return Math.min(ZONE_COUNT - 1, (int) Math.floor((x - ZONE_START_X) / ZONE_LENGTH));
    }

    public static Theme zoneTheme(final int index) {
        return index < 0 ? Theme.CAMP : ZONE_THEMES[Math.min(index, ZONE_THEMES.length - 1)];
    }

    /** 這個 x 的主題（區域交界處不混合；混合看 {@link #blend}）。 */
    public static Theme themeAt(final double x) {
        return zoneTheme(zoneIndex(x));
    }

    /**
     * 交界處的混合：回傳 {前一個主題, 後一個主題, t}，t 從 0（完全是前一個）到 1。
     * 交界兩側各 24 格漸變。
     */
    public static Blend blend(final double x) {
        for (int i = 0; i <= ZONE_COUNT - 1; i++) {
            final double boundary = zoneStart(i);
            if (Math.abs(x - boundary) < 24) {
                return new Blend(zoneTheme(i - 1), zoneTheme(i), Noise.smoothstep(boundary - 24, boundary + 24, x));
            }
        }
        final Theme theme = themeAt(x);
        return new Blend(theme, theme, 0);
    }

    /** 兩個主題的混合。 */
    public record Blend(Theme from, Theme to, double t) {
        /** 用座標雜湊抖動決定這一格屬於哪一邊（讓交界的方塊自然交錯）。 */
        public Theme pick(final int x, final int z) {
            if (t <= 0) {
                return from;
            }
            if (t >= 1) {
                return to;
            }
            return Noise.random01(SEED + 77, x, z) < t ? to : from;
        }
    }

    // ---- 路 ----

    /** 路中心的 z：出營地東門是直的，之後左右彎（約 ±40 格）。 */
    public static double roadZ(final double x) {
        final double wave = 26 * Math.sin(x / 97.0 + 0.7) + 14 * Math.sin(x / 41.0 + 2.1);
        final double ramp = Noise.smoothstep(CAMP_HALF, 110, x);
        return wave * ramp;
    }

    /** 路面（最上面一格）的高度，平滑版本。 */
    public static double roadHeight(final double x) {
        for (int i = 0; i + 1 < ROAD_HEIGHT.length; i++) {
            final double x0 = ROAD_HEIGHT[i][0];
            final double x1 = ROAD_HEIGHT[i + 1][0];
            if (x <= x1) {
                final double t = x <= x0 ? 0 : (x - x0) / (x1 - x0);
                final double s = (1 - Math.cos(Math.PI * t)) / 2;
                return ROAD_HEIGHT[i][1] + (ROAD_HEIGHT[i + 1][1] - ROAD_HEIGHT[i][1]) * s;
            }
        }
        return ROAD_HEIGHT[ROAD_HEIGHT.length - 1][1];
    }

    /** 路面最上面一格的整數高度。 */
    public static int roadTop(final double x) {
        return (int) Math.round(roadHeight(x));
    }

    /** 離路中心的距離（用垂直距離近似，路的彎度很緩）。 */
    public static double roadDistance(final double x, final double z) {
        return Math.abs(z - roadZ(x));
    }

    /** 可以走的走廊半寬（離路中心這麼遠之後開始是懸崖）；山頂之後收掉。 */
    public static double corridorHalf(final double x) {
        double width = 46 + 12 * Noise.value1(SEED + 5, x / 80.0);
        // 營地附近的懸崖要在營地平台（含漸變的一圈）外面，不然會變成爬得上去的緩坡
        width = Math.max(width, Noise.lerp(64, 0, Noise.smoothstep(60, 110, x)));
        return width * (1 - Noise.smoothstep(STAR_X + 10, WORLD_END_X, x));
    }

    // ---- 重要的點 ----

    /** 一個點：方塊座標的中心、站的高度、面向。 */
    public record Spot(double x, double y, double z, float yaw) {
    }

    /** 區域裡某個偏移（入口、中途、Boss 前、Boss 場地）在路上的點，站在路面上，面向東。 */
    public static Spot roadSpot(final int zoneIndex, final int offset, final float yaw) {
        final int x = zoneStart(zoneIndex) + offset;
        final int z = (int) Math.round(roadZ(x));
        return new Spot(x + 0.5, roadTop(x) + 1, z + 0.5, yaw);
    }

    /** 第 i 個區域的檢查點（入口、中途、Boss 前）。 */
    public static Spot checkpoint(final int zoneIndex, final int which) {
        final int offset = which == 0 ? ENTRY_OFFSET : which == 1 ? MIDWAY_OFFSET : BOSS_FRONT_OFFSET;
        return roadSpot(zoneIndex, offset, -90f);
    }

    /** 第 i 個區域的 Boss 場地中心（面向西，迎著玩家來的方向）。 */
    public static Spot arena(final int zoneIndex) {
        return roadSpot(zoneIndex, ARENA_OFFSET, 90f);
    }

    /** 營地的落點（重生、/town）：營火南邊，面向北邊的地圖裝置。 */
    public static Spot campSpawn() {
        return new Spot(0.5, CAMP_Y + 1, 7.5, 180f);
    }

    // ---- 地形 ----

    /**
     * 海岸線的距離：正的是陸地、負的是海（大約的格數）。營地西邊與北邊是海，
     * 潮濕海岸的北邊沿著路是海灘，到森林之前海岸線往北轉走。
     */
    public static double coastDistance(final double x, final double z) {
        // 營地西邊的海岸線（x ≈ −58）
        final double westLine = -58 + 4 * Noise.value1(SEED + 11, z / 30.0);
        final double west = x - westLine;
        // 北邊的海岸線：營地一帶在 z ≈ −64，進海岸區以後跟著路（路北邊 44 格），300 以後往北彎走
        final double northLine;
        if (x < ZONE_START_X) {
            northLine = -64 + 3 * Noise.value1(SEED + 13, x / 25.0);
        } else {
            final double follow = Math.min(-64 + (x - ZONE_START_X) * 0.4, roadZ(x) - 44);
            final double bend = x > 300 ? (x - 300) * (x - 300) / 30.0 : 0;
            northLine = follow - bend + 5 * Noise.value1(SEED + 17, x / 35.0);
        }
        final double north = z - northLine;
        return Math.min(west, north);
    }

    /** 這一欄的地表（最上面一格實心方塊）高度；水面另外看 {@link #SEA_LEVEL}。 */
    public static int surfaceY(final int x, final int z) {
        return (int) Math.floor(surfaceHeight(x, z));
    }

    /** 地表高度（小數）。 */
    public static double surfaceHeight(final double x, final double z) {
        final double land = landHeight(x, z);
        final double coast = coastDistance(x, z);
        if (coast >= 16) {
            return land;
        }
        if (coast < 0) {
            // 海底：越遠越深，最深到海平面下 15
            final double depth = Math.min(14, 1.5 + (-coast) * 0.35) + Noise.value(SEED + 19, x / 9.0, z / 9.0);
            return SEA_LEVEL - 1 - depth;
        }
        // 海灘：從海平面慢慢升到陸地的高度
        final double beach = SEA_LEVEL + coast * 0.25;
        if (cliffWeight(x, z) > 0.5) {
            // 崖頂碰到海：垂直的海崖，底下留 5 格沙灘（任何漸變都會變成爬得上去的斜坡）
            return coast < 5 ? beach : Math.max(beach, land);
        }
        return Noise.lerp(beach, Math.max(beach, land), Noise.smoothstep(4, 16, coast));
    }

    /** 不算海的陸地高度：路面高度＋丘陵＋懸崖，營地、檢查點、Boss 場地附近壓平。 */
    public static double landHeight(final double x, final double z) {
        final double road = roadHeight(x);
        final double camp = campFlatness(x, z);
        if (camp >= 1) {
            return CAMP_Y;
        }
        final Blend blend = blend(x);
        final double amplitude = Noise.lerp(hillAmplitude(blend.from()), hillAmplitude(blend.to()), blend.t());
        final double cliffHeight = Noise.lerp(cliffHeight(blend.from()), cliffHeight(blend.to()), blend.t());
        final double bias = Noise.lerp(hillBias(blend.from()), hillBias(blend.to()), blend.t());

        final double distance = roadDistance(x, z);
        final double inner = Noise.smoothstep(5, 18, distance);
        final double hills = (amplitude * Noise.fbm(SEED + 23, x / 45.0, z / 45.0, 3) + bias) * inner;

        // 懸崖：走廊外面是一道垂直的崖壁（邊緣用雜訊弄得不整齊），最矮也比走廊裡最高的丘陵高 5 格以上，爬不上去
        final double cliff = cliffAt(x, z, distance);
        final double plateau = cliffHeight * (1 + 0.3 * Noise.fbm(SEED + 31, x / 30.0, z / 30.0, 2));
        // 檢查點與 Boss 場地：丘陵壓平（懸崖不動，不然會壓出爬得上去的斜坡）
        final double flat = flatness(x, z);
        double height = road + hills * (1 - flat) * (1 - cliff) + plateau * cliff;
        // 營地平台與周圍的漸變
        if (camp > 0) {
            height = Noise.lerp(height, CAMP_Y, camp);
        }
        return height;
    }

    /**
     * 懸崖的權重（0 = 走廊、1 = 崖頂），只有一格寬的過渡，所以是垂直的崖壁。
     * 營地一帶與潮濕海岸的北邊是海：那一側的崖壁往北推到海裡（等於沒有），到 290–330 之間才從海裡升上岸。
     */
    private static double cliffAt(final double x, final double z, final double distance) {
        double half = corridorHalf(x);
        if (z < roadZ(x) && x < 330) {
            half += Noise.lerp(80, 0, Noise.smoothstep(290, 330, x));
        }
        final double edge = distance + 6 * Noise.fbm(SEED + 29, x / 20.0, z / 20.0, 2);
        return Noise.smoothstep(half - 0.5, half + 0.5, edge);
    }

    /** 營地平台的權重：1 = 完全平坦（|x|、|z| ≤ 40），外面 12 格漸變到 0。 */
    public static double campFlatness(final double x, final double z) {
        final double outside = Math.max(Math.abs(x), Math.abs(z)) - CAMP_FLAT;
        if (outside <= 0) {
            return 1;
        }
        return 1 - Noise.smoothstep(0, 12, outside);
    }

    /** 檢查點祭壇與 Boss 場地的壓平權重（1 = 完全平）。 */
    public static double flatness(final double x, final double z) {
        final int index = zoneIndex(x);
        if (index < 0) {
            return 0;
        }
        double best = 0;
        for (int i = Math.max(0, index - 1); i <= Math.min(ZONE_COUNT - 1, index + 1); i++) {
            final int x0 = zoneStart(i);
            for (final int offset : new int[] {ENTRY_OFFSET, MIDWAY_OFFSET, BOSS_FRONT_OFFSET}) {
                best = Math.max(best, disc(x, z, x0 + offset, SHRINE_RADIUS + 3, SHRINE_RADIUS + 9));
            }
            best = Math.max(best, disc(x, z, x0 + ARENA_OFFSET, ARENA_RADIUS + 2, ARENA_RADIUS + 10));
        }
        return best;
    }

    private static double disc(final double x, final double z, final int cx, final double inner, final double outer) {
        if (Math.abs(x - cx) > outer) {
            return 0;
        }
        final double distance = Math.hypot(x - cx, z - roadZ(cx));
        return 1 - Noise.smoothstep(inner, outer, distance);
    }

    /** 走廊裡丘陵的起伏。 */
    public static double hillAmplitude(final Theme theme) {
        return switch (theme) {
            case CAMP, COAST -> 3.5;
            case FOREST -> 6;
            case RUINS -> 3;
            case SWAMP -> 2.2;
            case MOUNTAIN -> 7;
        };
    }

    /** 丘陵的平均偏移（沼澤偏低，多一些水塘）。 */
    public static double hillBias(final Theme theme) {
        return theme == Theme.SWAMP ? -1.5 : theme == Theme.MOUNTAIN ? 2 : 0.5;
    }

    /** 走廊外懸崖的高度。 */
    public static double cliffHeight(final Theme theme) {
        return switch (theme) {
            case CAMP, COAST -> 14;
            case FOREST -> 18;
            case RUINS -> 13;
            case SWAMP -> 9;
            case MOUNTAIN -> 30;
        };
    }

    /** 這一格是不是懸崖（走廊外的高地）：用來換岩石面、決定哪裡長樹。 */
    public static double cliffWeight(final double x, final double z) {
        return cliffAt(x, z, roadDistance(x, z)) * (1 - campFlatness(x, z));
    }
}
