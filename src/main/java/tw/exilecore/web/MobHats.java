package tw.exilecore.web;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;

import java.awt.image.BufferedImage;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 2026-10-31 照站長：怪物的資源包新外觀——地圖怪頭上戴的 3D 頭飾，照地圖場景換造型（藤蔓冠、冰晶冠、熔岩角…），
 * 稀有、傳奇怪和 Boss 戴加大的版本（{@code _elite}）。
 * <p>
 * 只有人形的怪（殭屍、骷髏、掠奪者…）會畫頭上的東西；伺服器只把頭飾送給有資源包的人（怪物真正的裝備是空的），
 * 沒有資源包的人看到原版。
 * <p>
 * 座標是物品模型的座標（16 = 一格的 0.625 倍）：怪物的頭剛好是 1.6～14.4 的方塊，正面朝北（z 小的那邊），頭頂 y = 14.4。
 * 每種造型一張 32×32 的色票貼圖（4×4 格，每格 8×8 像素），每個方塊挑一格顏色。
 */
public final class MobHats {

    /** 物品模型：exilecore:hat/造型（加大的版本是 造型_elite）。 */
    public static final String MODEL = "exilecore:hat/";
    public static final String ELITE = "_elite";

    /** 地圖場景 → 頭飾造型。 */
    public static final Map<String, String> THEME_STYLES = Map.ofEntries(
            Map.entry("forest", "vine"), Map.entry("jungle", "vine"), Map.entry("swamp", "vine"),
            Map.entry("snow", "frost"), Map.entry("glacier", "frost"),
            Map.entry("volcano", "ember"), Map.entry("inferno", "ember"),
            Map.entry("beach", "coral"), Map.entry("coral", "coral"),
            Map.entry("cherry", "blossom"),
            Map.entry("fungal", "mushroom"),
            Map.entry("sculk", "sculk"), Map.entry("cave", "sculk"),
            Map.entry("crypt", "bone"), Map.entry("ruins", "bone"), Map.entry("desert", "bone"),
            Map.entry("star", "star"), Map.entry("void", "star"));

    /** 造型 → 中文名（給說明、測試用）。 */
    public static final Map<String, String> NAMES = new LinkedHashMap<>();

    static {
        NAMES.put("vine", "藤蔓冠");
        NAMES.put("frost", "冰晶冠");
        NAMES.put("ember", "熔岩角");
        NAMES.put("coral", "珊瑚冠");
        NAMES.put("blossom", "櫻花環");
        NAMES.put("mushroom", "蕈傘");
        NAMES.put("sculk", "幽匿鹿角");
        NAMES.put("bone", "骨刺冠");
        NAMES.put("star", "星環");
    }

    private MobHats() {
    }

    /** 這個場景的怪戴哪個模型（沒有對應的造型回傳 null）。 */
    public static String model(final String theme, final boolean elite) {
        final String style = theme == null ? null : THEME_STYLES.get(theme);
        return style == null ? null : MODEL + style + (elite ? ELITE : "");
    }

    public static List<String> styles() {
        return List.copyOf(NAMES.keySet());
    }

    // ---------------------------------------------------------------- 色票

    static int[] palette(final String style) {
        return switch (style) {
            case "vine" -> new int[] {0x2F5A1E, 0x4E8A2C, 0x7DB646, 0x3B6B22, 0xD8323C, 0xF2C94C};
            case "frost" -> new int[] {0xEAF8FF, 0xBDEBFA, 0x7FD3F0, 0x3E8EDB, 0xC9D6E3};
            case "ember" -> new int[] {0x2A2228, 0x6E1A14, 0x4A2E26, 0xF2701E, 0xFFC23A, 0x5A5050};
            case "coral" -> new int[] {0xF06AA8, 0xF59A3D, 0xF5D547, 0x9B5BD6, 0xD9C79A, 0xF8F4EC, 0x3CB7B0};
            case "blossom" -> new int[] {0xF7A8C8, 0xFCD3E3, 0xE0679A, 0xF8D35E, 0x5E9E3E, 0x6B4A3A};
            case "mushroom" -> new int[] {0xC8302C, 0x8E1F1C, 0xF4EFE6, 0xE6DCC8, 0x8A5A3B, 0x58E0C8};
            case "sculk" -> new int[] {0x0E2A33, 0x123F4A, 0x2FE0E6, 0xCFC8B8, 0x0A0F14};
            case "bone" -> new int[] {0xE8E0CC, 0xC9BC9C, 0x5A5040, 0xC8282C};
            case "star" -> new int[] {0xF2C14E, 0xFFE7A0, 0x7A4FD6, 0x2A1B5C, 0xFFFFFF};
            default -> throw new IllegalArgumentException("沒有這個頭飾造型：" + style);
        };
    }

    /** 色票貼圖：每格一個顏色，加一點固定的明暗斑點（看起來不會是死平的色塊）。 */
    static BufferedImage texture(final String style) {
        final int[] colors = palette(style);
        // 32×32（資源包的貼圖都是 32 的正方形）；模型的 UV 照 16 算，一格顏色是 UV 的 4×4、貼圖的 8×8 像素
        final BufferedImage image = new BufferedImage(32, 32, BufferedImage.TYPE_INT_ARGB);
        for (int cell = 0; cell < 16; cell++) {
            final int rgb = colors[Math.min(cell, colors.length - 1)];
            final int cx = (cell % 4) * 8;
            final int cy = (cell / 4) * 8;
            for (int x = 0; x < 8; x++) {
                for (int y = 0; y < 8; y++) {
                    final int hash = (x * 7 + y * 13 + cell * 5) % 5;
                    final double shade = hash == 0 ? 0.88 : hash == 1 ? 1.08 : 1.0;
                    image.setRGB(cx + x, cy + y, 0xFF000000 | shade(rgb, shade));
                }
            }
        }
        return image;
    }

    private static int shade(final int rgb, final double factor) {
        final int r = (int) Math.min(255, ((rgb >> 16) & 0xFF) * factor);
        final int g = (int) Math.min(255, ((rgb >> 8) & 0xFF) * factor);
        final int b = (int) Math.min(255, (rgb & 0xFF) * factor);
        return (r << 16) | (g << 8) | b;
    }

    // ---------------------------------------------------------------- 模型

    /** 一組方塊（模型的 elements）。 */
    static final class Shape {
        final List<double[]> boxes = new ArrayList<>(); // x0 y0 z0 x1 y1 z1 color axis(0無 1x 2y 3z) angle ox oy oz

        Shape box(final double x0, final double y0, final double z0, final double x1, final double y1, final double z1, final int color) {
            boxes.add(new double[] {x0, y0, z0, x1, y1, z1, color, 0, 0, 0, 0, 0});
            return this;
        }

        /** 繞 axis（'x'、'y'、'z'）轉 angle 度（原版只收 -45、-22.5、0、22.5、45），轉軸在方塊底面中心。 */
        Shape tilt(final double x0, final double y0, final double z0, final double x1, final double y1, final double z1, final int color,
                   final char axis, final double angle) {
            boxes.add(new double[] {x0, y0, z0, x1, y1, z1, color, axis - 'w', angle, (x0 + x1) / 2, y0, (z0 + z1) / 2});
            return this;
        }

        /** 一圈額帶（頭的外面一點點）。 */
        Shape ring(final double y0, final double y1, final int color) {
            box(1, y0, 1, 15, y1, 1.9, color);
            box(1, y0, 14.1, 15, y1, 15, color);
            box(1, y0, 1.9, 1.9, y1, 14.1, color);
            box(14.1, y0, 1.9, 15, y1, 14.1, color);
            return this;
        }

        /** 左右對稱：把目前的方塊照 x = 8 鏡射一份加進去。 */
        Shape mirror() {
            final List<double[]> copies = new ArrayList<>();
            for (final double[] b : boxes) {
                final double[] m = b.clone();
                m[0] = 16 - b[3];
                m[3] = 16 - b[0];
                m[9] = 16 - b[9];
                if (b[7] == 2 || b[7] == 3) { // 繞 y、z 轉的方向鏡射後相反
                    m[8] = -b[8];
                }
                copies.add(m);
            }
            boxes.addAll(copies);
            return this;
        }

        Shape add(final Shape other) {
            boxes.addAll(other.boxes);
            return this;
        }
    }

    /** 一個造型的方塊（elite = 加大的版本）。 */
    static Shape shape(final String style, final boolean elite) {
        final Shape s = new Shape();
        switch (style) {
            case "vine" -> {
                s.ring(12.4, 14.6, 3);
                final Shape side = new Shape()
                        .box(0.4, 14, 0.4, 3.4, 16.6, 3.4, 1)          // 角落的葉叢
                        .box(0.4, 14, 12.6, 3.4, 16.2, 15.6, 2)
                        .box(0.4, 14, 6, 2.4, 16.4, 10, 2)            // 側邊
                        .box(0.4, 6, 4, 1.0, 12.6, 4.6, 3)            // 垂下來的藤
                        .box(0.4, 8.5, 10, 1.0, 12.6, 10.6, 3)
                        .box(0.1, 8, 3.6, 1.2, 9, 5, 2)               // 藤上的小葉
                        .box(0.1, 10, 9.6, 1.2, 11, 11, 1);
                s.add(side.mirror());
                s.box(6.5, 14.2, 0.6, 9.5, 16, 2.4, 2)                // 前面
                        .box(5.5, 14, 13.4, 10.5, 17, 15.6, 1)        // 後面
                        .box(4, 7, 15, 4.6, 12.6, 15.6, 3)
                        .box(8, 4.5, 15, 8.6, 12.6, 15.6, 3)
                        .box(11.5, 8, 15, 12.1, 12.6, 15.6, 3);
                if (elite) {
                    s.box(6.2, 16, 0.8, 9.8, 17, 4.2, 4)              // 前面一朵大紅花
                            .box(7.3, 16.6, 1.8, 8.7, 17.6, 3.2, 5)
                            .tilt(7, 14.4, 7, 9, 20, 9, 1, 'y', 45)   // 頭頂的葉尖
                            .tilt(7.5, 20, 7.5, 8.5, 22.5, 8.5, 2, 'y', 45);
                }
            }
            case "frost" -> {
                s.ring(12.4, 14.4, 4);
                s.tilt(6.8, 14.2, 6.8, 9.2, elite ? 25.5 : 21.5, 9.2, 1, 'y', 45)   // 正中間的冰晶
                        .tilt(7.4, elite ? 25.5 : 21.5, 7.4, 8.6, elite ? 27.5 : 23, 8.6, 0, 'y', 45);
                final Shape side = new Shape()
                        .tilt(3.2, 14, 2.2, 4.8, 18.5, 3.8, 2, 'z', 22.5)          // 前
                        .tilt(3.1, 14, 12.1, 4.9, 19.5, 13.9, 0, 'x', 22.5)        // 後
                        .tilt(1.3, 13.6, 7.3, 2.7, 17.2, 8.7, 3, 'z', 22.5)        // 側
                        .tilt(5.6, 14.2, 4.6, 6.6, 17, 5.6, 0, 'y', 45);           // 小碎晶
                s.add(side.mirror());
                if (elite) {
                    s.add(new Shape()
                            .tilt(1.0, 12.8, 3.6, 2.4, 19, 5.0, 1, 'z', 45)
                            .tilt(2.6, 14, 9.6, 3.8, 21, 10.8, 2, 'z', 22.5).mirror());
                }
            }
            case "ember" -> {
                s.ring(12.4, 14.6, 0);
                s.box(4.5, 14.6, 0.8, 5.7, 15.6, 2.0, 3)
                        .box(10.3, 14.6, 0.8, 11.5, 15.6, 2.0, 3)
                        .box(7.3, 14.6, 0.8, 8.7, 16.2, 2.0, 4);
                final Shape horn = new Shape()
                        .box(0.0, 12.4, 5.8, 3.4, 15.8, 9.6, 2)                    // 角根
                        .tilt(-0.6, 15.4, 6.2, 2.4, 19.4, 9.2, 2, 'z', 22.5)
                        .tilt(-2.0, 18.4, 6.6, 0.6, 22.0, 8.8, 1, 'z', 45)
                        .tilt(-4.0, 20.2, 6.9, -2.0, 23.0, 8.5, elite ? 4 : 3, 'z', 45);
                s.add(horn.mirror());
                if (elite) {
                    s.tilt(7.0, 14.4, 6.6, 9.0, 19.5, 8.6, 2, 'x', -22.5)       // 中間第三支角
                            .tilt(7.4, 19.0, 5.6, 8.6, 22.0, 6.8, 3, 'x', -22.5)
                            .add(new Shape().tilt(3.0, 14.4, 1.0, 4.4, 17.6, 2.4, 3, 'z', 22.5).mirror());
                }
            }
            case "coral" -> {
                s.ring(12.4, 14.2, 4);
                branch(s, 4, 3, 5, 0, false);
                branch(s, 11.5, 3, 4, 1, true);
                branch(s, 3, 11, 6, 3, true);
                branch(s, 12.5, 12, 5, 0, false);
                branch(s, 8, 8, 4, 2, false);
                s.box(7, 14, 12.8, 9.4, 16.4, 15.2, 6)                    // 管狀珊瑚
                        .box(7.6, 16.4, 13.4, 8.8, 17.4, 14.6, 6)
                        .box(1.2, 13.4, 5.6, 2.6, 16, 7, 6);
                if (elite) {
                    branch(s, 8, 4, 8, 3, true);
                    s.box(5.6, 18.6, 7.4, 10.4, 19.2, 12.2, 0)            // 貝殼和珍珠
                            .box(6.8, 19.2, 8.6, 9.2, 21.4, 11.0, 5);
                }
            }
            case "blossom" -> {
                s.ring(13.0, 14.4, 5);
                final double[][] spots = {{2, 1.5}, {6, 1.0}, {10, 1.0}, {14, 1.5}, {15, 6}, {15, 10.5}, {13.5, 15},
                        {8, 15.1}, {2.5, 15}, {1, 10.5}, {1, 6}};
                for (int i = 0; i < spots.length; i++) {
                    flower(s, spots[i][0], 14.2, spots[i][1], 1.0, i % 3 == 1 ? 2 : 0);
                }
                s.box(3.8, 14.2, 0.6, 4.8, 14.8, 2.0, 4).box(11.2, 14.2, 0.6, 12.2, 14.8, 2.0, 4)
                        .box(0.6, 14.2, 8, 2.0, 14.8, 9, 4).box(14, 14.2, 8, 15.4, 14.8, 9, 4)
                        .box(5, 14.2, 14, 6, 14.8, 15.4, 4).box(10.6, 14.2, 14, 11.6, 14.8, 15.4, 4);
                if (elite) {
                    flower(s, 13.5, 14.8, 4.5, 1.8, 2);                      // 耳邊一朵大的
                    s.tilt(7.5, 14.4, 7.5, 8.5, 18.5, 8.5, 5, 'z', 22.5)       // 頭頂的花枝
                            .box(5.4, 17.6, 7.2, 7.0, 18.4, 8.8, 4);
                    flower(s, 6.6, 18.6, 8.0, 1.0, 1);
                }
            }
            case "mushroom" -> {
                s.box(-0.5, 13.4, -0.5, 16.5, 15.6, 16.5, 1)                  // 傘緣
                        .box(0.5, 15.6, 0.5, 15.5, 17.6, 15.5, 0)
                        .box(3, 17.6, 3, 13, 18.8, 13, 0)
                        .box(5, 18.8, 5, 7, 19.1, 7, 2).box(9.5, 18.8, 8, 11.5, 19.1, 10, 2).box(6, 18.8, 10.5, 8, 19.1, 12.5, 2)
                        .box(-0.8, 14, 5, -0.5, 15.2, 7, 2).box(16.5, 14, 9, 16.8, 15.2, 11, 2)
                        .box(6, 14, -0.8, 8, 15.2, -0.5, 2).box(10, 16, 0.2, 12, 17, 0.5, 2).box(3, 16, 15.5, 5, 17, 15.8, 2)
                        .box(1.2, 16.2, 9, 1.4, 17.2, 11, 2).box(14.6, 16.2, 3.5, 14.8, 17.2, 5.5, 2);
                if (elite) {
                    s.box(11, 18.8, 4, 12, 20.5, 5, 3).box(10, 20.5, 3, 13, 21.5, 6, 5)
                            .box(4, 18.8, 10, 4.8, 20, 10.8, 3).box(3.2, 20, 9.2, 5.6, 20.8, 11.6, 4)
                            .box(11, 18.8, 11, 11.6, 19.6, 11.6, 3).box(10.5, 19.6, 10.5, 12.1, 20.2, 12.1, 5);
                }
            }
            case "sculk" -> {
                s.ring(12.6, 14.6, 1);
                s.box(4, 13, 0.7, 5, 14, 1.0, 2).box(11, 13.4, 0.7, 12, 14.4, 1.0, 2).box(7.5, 12.8, 0.7, 8.5, 13.6, 1.0, 2);
                final Shape antler = new Shape()
                        .tilt(1.0, 14, 7.0, 2.4, 20, 8.4, 0, 'z', 22.5)              // 主幹
                        .tilt(-0.4, 17, 5.0, 0.8, 20.4, 6.2, 0, 'x', -22.5)          // 往前的叉
                        .box(-1.8, 19.4, 7.0, -0.6, 22.2, 8.2, 1)                    // 往上的叉
                        .box(-1.8, 22.2, 7.0, -0.6, 23.2, 8.2, 2)
                        .box(-0.6, 20.0, 3.6, 0.4, 21.0, 4.6, 2)
                        .tilt(-0.2, 18, 9.2, 1.0, 21, 10.4, 1, 'x', 22.5);           // 往後的叉
                if (elite) {
                    antler.box(-3.4, 21.4, 7.1, -1.8, 22.4, 8.1, 1).box(-4.2, 22.4, 7.1, -3.2, 23.6, 8.1, 2);
                }
                s.add(antler.mirror());
                if (elite) {
                    s.box(6.5, 14.4, 6.5, 9.5, 16, 9.5, 1).box(7.2, 16, 7.2, 8.8, 16.8, 8.8, 2);  // 幽匿觸媒的眼
                }
            }
            case "bone" -> {
                s.ring(12.4, 14.6, 1);
                spike(s, 8, 1.5, 5.5, 0);
                spike(s, 4, 1.5, 3.5, 0);
                spike(s, 12, 1.5, 3.5, 0);
                spike(s, 1.5, 6, 3, 1);
                spike(s, 14.5, 6, 3, 1);
                spike(s, 1.5, 11, 3, 0);
                spike(s, 14.5, 11, 3, 0);
                spike(s, 5, 14.5, 4, 1);
                spike(s, 11, 14.5, 4, 1);
                spike(s, 8, 14.5, 3, 0);
                if (elite) {
                    s.add(new Shape()
                            .box(0.0, 12.0, 6.0, 2.6, 14.6, 9.0, 1)
                            .tilt(-0.6, 14.2, 6.4, 1.8, 18.0, 8.6, 0, 'z', 22.5)
                            .tilt(-1.6, 17.4, 6.8, 0.4, 20.6, 8.2, 0, 'z', 45).mirror());
                    s.box(7.3, 13, 0.5, 8.7, 14.2, 0.9, 3);
                }
            }
            case "star" -> {
                final double y0 = elite ? 19.0 : 18.4;
                final double t = 0.8;
                final double a = elite ? 1.5 : 3.0;
                final double b = 16 - a;
                s.box(a, y0, a, b, y0 + t, a + t, 0).box(a, y0, b - t, b, y0 + t, b, 0)
                        .box(a, y0, a + t, a + t, y0 + t, b - t, 0).box(b - t, y0, a + t, b, y0 + t, b - t, 0);
                s.box(7.3, y0 - 0.6, a - 0.3, 8.7, y0 + 1.4, a + 1.1, 1)                 // 正面的星
                        .box(6.6, y0, a - 0.3, 9.4, y0 + 0.8, a + 1.1, 1)
                        .box(7.6, y0 + 0.1, a - 0.5, 8.4, y0 + 0.7, a - 0.3, 2);
                s.box(1, 16, 8, 2, 17, 9, 2).box(14, 16.5, 7, 15, 17.5, 8, 2).box(8, 17, 14, 9, 18, 15, 4);
                if (elite) {
                    s.box(7.6, y0 + 0.1, -1.2, 8.4, y0 + 0.7, a, 1).box(7.6, y0 + 0.1, b, 8.4, y0 + 0.7, 17.2, 1)
                            .box(-1.2, y0 + 0.1, 7.6, a, y0 + 0.7, 8.4, 1).box(b, y0 + 0.1, 7.6, 17.2, y0 + 0.7, 8.4, 1)
                            .tilt(a - 0.4, y0 + 0.1, a - 0.4, a + 0.8, y0 + 0.7, a + 0.8, 1, 'y', 45)
                            .tilt(b - 0.8, y0 + 0.1, a - 0.4, b + 0.4, y0 + 0.7, a + 0.8, 1, 'y', 45)
                            .tilt(a - 0.4, y0 + 0.1, b - 0.8, a + 0.8, y0 + 0.7, b + 0.4, 1, 'y', 45)
                            .tilt(b - 0.8, y0 + 0.1, b - 0.8, b + 0.4, y0 + 0.7, b + 0.4, 1, 'y', 45)
                            .box(7, 21.2, 7, 9, 23.2, 9, 2).box(7.5, 23.2, 7.5, 8.5, 24, 8.5, 4);    // 頭頂飄著的晶核
                }
            }
            default -> throw new IllegalArgumentException("沒有這個頭飾造型：" + style);
        }
        return s;
    }

    /** 一株分叉的珊瑚。 */
    private static void branch(final Shape s, final double x, final double z, final double h, final int c, final boolean flip) {
        final double d = flip ? -1 : 1;
        s.box(x - 0.6, 14, z - 0.6, x + 0.6, 14 + h, z + 0.6, c);
        final double mid = 14 + h * 0.45;
        s.box(Math.min(x + 0.6 * d, x + 2.0 * d), mid, z - 0.5, Math.max(x + 0.6 * d, x + 2.0 * d), mid + 1, z + 0.5, c);
        s.box(Math.min(x + 1.2 * d, x + 2.2 * d), mid + 1, z - 0.5, Math.max(x + 1.2 * d, x + 2.2 * d), 14 + h * 0.85, z + 0.5, c);
        final double low = 14 + h * 0.25;
        s.box(x - 0.5, low, Math.min(z - 0.6 * d, z - 1.8 * d), x + 0.5, low + 1, Math.max(z - 0.6 * d, z - 1.8 * d), c);
        s.box(x - 0.5, low + 1, Math.min(z - 1.0 * d, z - 1.8 * d), x + 0.5, 14 + h * 0.6, Math.max(z - 1.0 * d, z - 1.8 * d), c);
    }

    /** 一朵花：花瓣十字＋花心。 */
    private static void flower(final Shape s, final double x, final double y, final double z, final double size, final int c) {
        s.box(x - 1.2 * size, y, z - 1.2 * size, x + 1.2 * size, y + 0.8 * size, z + 1.2 * size, c);
        s.box(x - 1.8 * size, y + 0.1, z - 0.5 * size, x + 1.8 * size, y + 0.7 * size, z + 0.5 * size, 1);
        s.box(x - 0.5 * size, y + 0.1, z - 1.8 * size, x + 0.5 * size, y + 0.7 * size, z + 1.8 * size, 1);
        s.box(x - 0.5 * size, y + 0.6 * size, z - 0.5 * size, x + 0.5 * size, y + 1.2 * size, z + 0.5 * size, 3);
    }

    /** 一根骨刺：粗的下半截＋細的上半截。 */
    private static void spike(final Shape s, final double x, final double z, final double h, final int c) {
        s.box(x - 0.9, 14.2, z - 0.9, x + 0.9, 14.2 + h * 0.6, z + 0.9, c);
        s.box(x - 0.5, 14.2 + h * 0.6, z - 0.5, x + 0.5, 14.2 + h, z + 0.5, 0);
    }

    /** 方塊轉成原版模型的 JSON。 */
    static JsonObject modelJson(final String style, final boolean elite) {
        final Shape shape = shape(style, elite);
        final JsonObject root = new JsonObject();
        root.addProperty("ambientocclusion", false);
        final JsonObject textures = new JsonObject();
        textures.addProperty("p", "exilecore:item/hat/" + style);
        textures.addProperty("particle", "exilecore:item/hat/" + style);
        root.add("textures", textures);
        final JsonArray elements = new JsonArray();
        for (final double[] b : shape.boxes) {
            final JsonObject element = new JsonObject();
            element.add("from", numbers(b[0], b[1], b[2]));
            element.add("to", numbers(b[3], b[4], b[5]));
            if (b[7] != 0) {
                final JsonObject rotation = new JsonObject();
                rotation.addProperty("angle", b[8]);
                rotation.addProperty("axis", String.valueOf((char) ('w' + (int) b[7])));
                rotation.add("origin", numbers(b[9], b[10], b[11]));
                element.add("rotation", rotation);
            }
            final int cell = (int) b[6];
            final double u = (cell % 4) * 4;
            final double v = (cell / 4) * 4;
            final JsonObject faces = new JsonObject();
            for (final String face : new String[] {"north", "south", "east", "west", "up", "down"}) {
                final JsonObject f = new JsonObject();
                f.add("uv", numbers(u + 0.5, v + 0.5, u + 3.5, v + 3.5));
                f.addProperty("texture", "#p");
                faces.add(face, f);
            }
            element.add("faces", faces);
            elements.add(element);
        }
        root.add("elements", elements);
        // 戴在頭上：照原版的頭飾畫法（模型中心 = 頭的中心），不另外移動；其他畫法（物品欄、手上）縮小一點
        final JsonObject display = new JsonObject();
        display.add("head", transform(0, 0, 0, 1));
        display.add("gui", transform(30, 225, 0, 0.5));
        display.add("ground", transform(0, 0, 0, 0.35));
        display.add("fixed", transform(0, 180, 0, 0.6));
        root.add("display", display);
        return root;
    }

    private static JsonObject transform(final double rx, final double ry, final double rz, final double scale) {
        final JsonObject t = new JsonObject();
        t.add("rotation", numbers(rx, ry, rz));
        t.add("translation", numbers(0, 0, 0));
        t.add("scale", numbers(scale, scale, scale));
        return t;
    }

    private static JsonArray numbers(final double... values) {
        final JsonArray array = new JsonArray();
        for (final double value : values) {
            array.add(Math.round(value * 1000) / 1000.0);
        }
        return array;
    }

    /** 資源包要放的檔案：每種造型一張貼圖、兩個模型（一般、加大）、兩個物品定義。 */
    static Map<String, Object> files() {
        final Map<String, Object> files = new LinkedHashMap<>();
        for (final String style : styles()) {
            files.put("assets/exilecore/textures/item/hat/" + style + ".png", texture(style));
            for (final boolean elite : new boolean[] {false, true}) {
                final String name = style + (elite ? ELITE : "");
                files.put("assets/exilecore/models/item/hat/" + name + ".json", modelJson(style, elite));
                final JsonObject definition = new JsonObject();
                definition.addProperty("type", "minecraft:model");
                definition.addProperty("model", "exilecore:item/hat/" + name);
                final JsonObject root = new JsonObject();
                root.add("model", definition);
                files.put("assets/exilecore/items/hat/" + name + ".json", root);
            }
        }
        return files;
    }
}
