package tw.exilecore.web;

import java.awt.Polygon;
import java.awt.image.BufferedImage;
import java.util.List;

/**
 * 2026-11-07 照站長「怪物死掉冒出來的粒子換成比較明顯的、用材質包畫的」：怪物詞綴的效果圖（像素圖，程式畫的）。
 * 這次之前這些效果（熔岩爆、噬魂、蝕生、喚靈、晶爆的晶體、死後爆炸、焦土／凍土／雷土）只有粒子，看不出範圍。
 * <ul>
 *   <li>地上的（{@link #MARKS}，32×32，用 {@code exilecore:item/mark/plane} 平貼在地上，跟招式預警同一個模型、同一個著色）：
 *       圖的外框就是範圍的邊（實體放大成 2 × 半徑），白色的圖著色成傷害類型的顏色，有顏色的圖用白色著色（不變色）；</li>
 *   <li>立著的（{@link #SPRITES}，16×16，{@code minecraft:item/generated}，物品模型 {@code exilecore:fx/圖名}）：
 *       靈魂、晶體，展示實體轉向看的人（billboard）。</li>
 * </ul>
 * 遊戲裡怎麼放見 skill/GroundDecals 的短暫的圖。純函式、不依賴 Bukkit（測試用）。
 */
public final class MonsterFx {

    /** 熔岩爆：岩漿裂紋＋亮橘外框（有顏色，不著色）。 */
    public static final String MAGMA = "fx_magma_burst";
    /** 爆炸（死後爆炸、晶體、爆岩、爆花、屍爆、閃電幻影）：白色，著色成傷害類型。 */
    public static final String BURST = "fx_burst";
    /** 怪物留下的點燃／冰緩／感電地面：白色，著色成地面的顏色；外框內側一圈往內的尖齒（跟玩家的虛線地面分得開）。 */
    public static final String GROUND = "fx_mob_ground";
    /** 靈魂落地（蝕生的爬起來、喚靈的屍體爬起來）：有顏色。 */
    public static final String SOUL_RING = "fx_soul_ring";
    /** 立著的：靈魂。 */
    public static final String SOUL = "soul";
    /** 立著的：晶爆的晶體。 */
    public static final String CRYSTAL = "crystal";

    public static final List<String> MARKS = List.of(MAGMA, BURST, GROUND, SOUL_RING);
    public static final List<String> SPRITES = List.of(SOUL, CRYSTAL);

    /** 地上的圖幾像素寬。 */
    public static final int MARK_PX = 32;
    /** 立著的圖幾像素寬。 */
    public static final int SPRITE_PX = 16;

    private MonsterFx() {
    }

    static BufferedImage image(final String name) {
        return switch (name) {
            case MAGMA -> magma();
            case BURST -> burst();
            case GROUND -> ground();
            case SOUL_RING -> soulRing();
            case SOUL -> soul();
            case CRYSTAL -> crystal();
            default -> throw new IllegalArgumentException(name);
        };
    }

    private static int argb(final int alpha, final int rgb) {
        return Math.max(0, Math.min(255, alpha)) << 24 | rgb & 0xFFFFFF;
    }

    /** 像素中心到圖中心的距離（以「半徑 = 1」為單位：1 = 圖的邊）。 */
    private static double radial(final int x, final int y, final int n) {
        final double c = n / 2.0;
        return Math.hypot(x + 0.5 - c, y + 0.5 - c) / c;
    }

    private static double angle(final int x, final int y, final int n) {
        final double c = n / 2.0;
        return Math.atan2(y + 0.5 - c, x + 0.5 - c);
    }

    /** 固定的雜訊（同一個像素每次都一樣，資源包的雜湊才不會變）。 */
    private static int noise(final int x, final int y, final int seed) {
        int h = x * 374761393 + y * 668265263 + seed * 1274126177;
        h = (h ^ h >>> 13) * 1274126177;
        return (h ^ h >>> 16) & 0xFF;
    }

    // ---- 地上的 ----

    /**
     * 熔岩爆：最外面兩圈像素是亮黃＋橘的外框（＝傷害範圍的邊），裡面是暗紅的岩漿底、8 道往外的亮橘裂紋、中間一團亮黃。
     */
    static BufferedImage magma() {
        final int n = MARK_PX;
        final BufferedImage image = new BufferedImage(n, n, BufferedImage.TYPE_INT_ARGB);
        for (int y = 0; y < n; y++) {
            for (int x = 0; x < n; x++) {
                final double d = radial(x, y, n);
                if (d > 1.0) {
                    continue;
                }
                final double r = d * n / 2.0; // 像素
                if (r > n / 2.0 - 1.1) {
                    image.setRGB(x, y, argb(255, 0xFFD23A)); // 外框：亮黃
                    continue;
                }
                if (r > n / 2.0 - 2.2) {
                    image.setRGB(x, y, argb(245, 0xFF7A1A)); // 外框內側：橘
                    continue;
                }
                if (r < 2.6) {
                    image.setRGB(x, y, argb(230, 0xFFE58A)); // 中間：亮黃
                    continue;
                }
                if (r < 4.2) {
                    image.setRGB(x, y, argb(210, 0xFF9A2A));
                    continue;
                }
                // 裂紋：8 道，角度隨距離歪一點（像閃電一樣折）
                final double a = angle(x, y, n) + Math.sin(r * 0.9) * 0.12;
                final double sector = Math.PI * 2 / 8;
                final double off = Math.abs(((a % sector) + sector) % sector - sector / 2); // 離裂紋中線的角度
                if (off * r < 0.75) {
                    image.setRGB(x, y, argb(235, r < 9 ? 0xFFB040 : 0xFF7A1A));
                    continue;
                }
                // 岩漿底：越靠中間越亮、越不透明；雜訊點出一點顆粒
                final int grain = noise(x, y, 7);
                final int rgb = grain < 40 ? 0xC8341A : grain < 140 ? 0x8A1E0E : 0x5C1208;
                image.setRGB(x, y, argb((int) Math.round(150 - 70 * d), rgb));
            }
        }
        return image;
    }

    /** 爆炸（白色，著色）：實心外框、內側一圈細線、中間一顆 8 角的爆炸星、很淡的底。 */
    static BufferedImage burst() {
        final int n = MARK_PX;
        final BufferedImage image = new BufferedImage(n, n, BufferedImage.TYPE_INT_ARGB);
        for (int y = 0; y < n; y++) {
            for (int x = 0; x < n; x++) {
                final double d = radial(x, y, n);
                if (d > 1.0) {
                    continue;
                }
                final double r = d * n / 2.0;
                final double a = angle(x, y, n);
                if (r > n / 2.0 - 1.4) {
                    image.setRGB(x, y, argb(250, 0xFFFFFF)); // 外框 = 範圍的邊
                    continue;
                }
                if (r > n / 2.0 - 3.6 && r < n / 2.0 - 2.4) {
                    image.setRGB(x, y, argb(140, 0xFFFFFF)); // 內側一圈細線
                    continue;
                }
                // 中間一顆 8 角的爆炸星（尖端到 11 像素、凹處 4 像素）
                final double sector = Math.PI * 2 / 8;
                final double t = Math.abs(((a + Math.PI / 8) % sector + sector) % sector / sector - 0.5) * 2; // 尖端 0、凹處 1
                final double star = 11 - 7 * t;
                if (r < star) {
                    image.setRGB(x, y, argb(r < 2.5 ? 235 : r > star - 1.1 ? 210 : 120, 0xFFFFFF));
                    continue;
                }
                image.setRGB(x, y, argb((int) Math.round(30 + 40 * d), 0xFFFFFF));
            }
        }
        return image;
    }

    /** 怪物的地面（白色，著色）：實心外框、內側一圈 16 顆往內的尖齒、格紋的淡底。 */
    static BufferedImage ground() {
        final int n = MARK_PX;
        final BufferedImage image = new BufferedImage(n, n, BufferedImage.TYPE_INT_ARGB);
        for (int y = 0; y < n; y++) {
            for (int x = 0; x < n; x++) {
                final double d = radial(x, y, n);
                if (d > 1.0) {
                    continue;
                }
                final double r = d * n / 2.0;
                if (r > n / 2.0 - 1.2) {
                    image.setRGB(x, y, argb(240, 0xFFFFFF));
                    continue;
                }
                final double a = angle(x, y, n);
                final double sector = Math.PI * 2 / 16;
                final double t = ((a % sector) + sector) % sector / sector; // 0..1：一顆齒的位置
                final double tooth = 1 - Math.abs(t - 0.5) * 2; // 中間 1、兩邊 0
                if (r > n / 2.0 - 1.2 - 3.2 * tooth) {
                    image.setRGB(x, y, argb(190, 0xFFFFFF));
                    continue;
                }
                final boolean checker = (x + y & 1) == 0;
                image.setRGB(x, y, argb(checker ? 70 : 40, 0xFFFFFF));
            }
        }
        return image;
    }

    /** 靈魂落地：青色外框、外框上 8 道往外竄的魂火（尖端碰到圖的邊）、很淡的青底。 */
    // 2026-11-08 照站長「魂火和晶體還是改成紅色的好了」：魂火圈、魂火、晶體都換成紅色系；同日稍早魂火藍綠、晶體紫
    static BufferedImage soulRing() {
        final int n = MARK_PX;
        final BufferedImage image = new BufferedImage(n, n, BufferedImage.TYPE_INT_ARGB);
        final double rim = n / 2.0 - 4.5;
        for (int y = 0; y < n; y++) {
            for (int x = 0; x < n; x++) {
                final double d = radial(x, y, n);
                if (d > 1.0) {
                    continue;
                }
                final double r = d * n / 2.0;
                final double a = angle(x, y, n);
                final double sector = Math.PI * 2 / 8;
                final double t = ((a % sector) + sector) % sector / sector;
                final double flame = Math.max(0, 1 - Math.abs(t - 0.5) * 4); // 一道魂火：中間 1，往兩邊 0
                if (Math.abs(r - rim) < 0.9) {
                    image.setRGB(x, y, argb(250, 0xFF8A7A));
                    continue;
                }
                if (r > rim && r < rim + 4.4 * flame) {
                    image.setRGB(x, y, argb(235, r > rim + 4.4 * flame - 1.2 ? 0xFFE0D8 : 0xE83A2A));
                    continue;
                }
                if (Math.abs(r - (rim - 2)) < 0.6) {
                    image.setRGB(x, y, argb(140, 0xB0201A));
                    continue;
                }
                if (r < rim) {
                    image.setRGB(x, y, argb((int) Math.round(25 + 50 * r / rim), 0x8A1610));
                }
            }
        }
        return image;
    }

    // ---- 立著的 ----

    /** 靈魂：往上竄的一團魂火（白色的芯、亮青、青、深青的邊、最外面一圈暗藍外框），底部圓、頂端尖往右歪一點。 */
    static BufferedImage soul() {
        final int n = SPRITE_PX;
        final BufferedImage image = new BufferedImage(n, n, BufferedImage.TYPE_INT_ARGB);
        final boolean[][] in = new boolean[n][n];
        final double cx = 8.0;
        final double cy = 10.4;
        final double body = 4.4;
        for (int y = 0; y < n; y++) {
            for (int x = 0; x < n; x++) {
                final double px = x + 0.5;
                final double py = y + 0.5;
                boolean inside = Math.hypot(px - cx, py - cy) <= body;
                if (!inside && py >= 1.0 && py < cy) {
                    // 火舌：從身體往上收成一個尖，中線往右彎
                    final double t = (cy - py) / (cy - 1.0); // 0 底、1 頂
                    final double mid = cx + Math.sin(t * Math.PI) * 1.3 - 0.3;
                    final double half = body * Math.pow(1 - t, 0.75);
                    inside = Math.abs(px - mid) <= half;
                }
                in[y][x] = inside;
            }
        }
        for (int y = 0; y < n; y++) {
            for (int x = 0; x < n; x++) {
                if (!in[y][x]) {
                    // 外框：旁邊有填的格子
                    if (x > 0 && in[y][x - 1] || x < n - 1 && in[y][x + 1] || y > 0 && in[y - 1][x] || y < n - 1 && in[y + 1][x]) {
                        image.setRGB(x, y, argb(255, 0x4A0808));
                    }
                    continue;
                }
                final double dy = y + 0.5 - (cy - 0.6);
                final double core = Math.hypot(x + 0.5 - (cx - 0.3), dy * (dy < 0 ? 0.45 : 0.8)); // 往上（火舌）亮得比較遠
                final int rgb = core < 1.6 ? 0xFFFFFF : core < 2.8 ? 0xFFB8B0 : core < 4.0 ? 0xF04A3A : 0xB81C1C;
                image.setRGB(x, y, argb(255, rgb));
            }
        }
        // 左上的一點反光
        image.setRGB(5, 8, argb(255, 0xFFE0D8));
        return image;
    }

    /** 晶體：三根紫色的晶柱（中間高、兩側斜），左半面亮、右半面暗，最外面一圈深紫外框，中間那根一道反光。 */
    static BufferedImage crystal() {
        final int n = SPRITE_PX;
        final BufferedImage image = new BufferedImage(n, n, BufferedImage.TYPE_INT_ARGB);
        // 每根：多邊形（像素座標）＋中線的 x（左邊亮、右邊暗）
        final Polygon left = new Polygon(new int[] {1, 4, 7, 6, 3}, new int[] {7, 4, 9, 15, 15}, 5);
        final Polygon right = new Polygon(new int[] {9, 12, 15, 13, 10}, new int[] {9, 5, 8, 15, 15}, 5);
        final Polygon middle = new Polygon(new int[] {8, 11, 11, 8, 5, 5}, new int[] {0, 3, 15, 16, 15, 3}, 6);
        final Polygon[] shards = {left, right, middle}; // 中間那根畫在最上面
        final double[] ridges = {4.0, 12.3, 8.0};
        final int[][] tones = {{0xFF8B8B, 0xD83A3A}, {0xFF8B8B, 0xD83A3A}, {0xFFB8B8, 0xF04848}};
        final boolean[][] in = new boolean[n][n];
        for (int s = 0; s < shards.length; s++) {
            for (int y = 0; y < n; y++) {
                for (int x = 0; x < n; x++) {
                    if (shards[s].contains(x + 0.5, y + 0.5)) {
                        in[y][x] = true;
                        image.setRGB(x, y, argb(255, x + 0.5 < ridges[s] ? tones[s][0] : tones[s][1]));
                    }
                }
            }
        }
        for (int y = 0; y < n; y++) {
            for (int x = 0; x < n; x++) {
                if (!in[y][x] && (x > 0 && in[y][x - 1] || x < n - 1 && in[y][x + 1] || y > 0 && in[y - 1][x] || y < n - 1 && in[y + 1][x])) {
                    image.setRGB(x, y, argb(255, 0x480A0A));
                }
            }
        }
        // 中間那根：左邊的直反光、頂端一點白
        for (int y = 3; y <= 11; y++) {
            image.setRGB(6, y, argb(255, 0xFFE6E6));
        }
        image.setRGB(8, 1, argb(255, 0xFFFFFF));
        // 中間那根跟兩側的接縫：暗一點，看得出是三根
        for (int y = 9; y < n; y++) {
            if (in[y][5] && image.getRGB(5, y) >>> 24 > 0 && left.contains(4.5, y + 0.5)) {
                image.setRGB(5, y, argb(255, 0x981C1C));
            }
            if (in[y][10] && right.contains(11.5, y + 0.5)) {
                image.setRGB(10, y, argb(255, 0x981C1C));
            }
        }
        return image;
    }
}
