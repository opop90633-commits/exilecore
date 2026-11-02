package tw.exilecore.web;

/**
 * 2026-11-02 照站長（PoE 風格的生命球、魔力球）：球怎麼畫。純函式、不依賴 Bukkit（測試用）。
 * <p>
 * 資源包（{@link OrbFont}）用它把每一段液面預先畫成圖；星骸輔助模組的 {@code tw.exilecore.client.core.OrbArt} 是同一份
 * （改一邊要一起改，裝不裝模組看到的球才一樣）。
 * <p>
 * 一顆球是 {@value #BOX}×{@value #BOX} GUI 像素的格子（高度不超過聊天欄最下面那行：畫面底往上 40 格），
 * 畫成 size×size 的圖（size = {@value #BOX} × 解析度）。由裡到外：液體（半徑 {@value #LIQUID}）、金屬框。
 * 2026-11-02 照站長改成 PoE 1 的樣子：能量護盾是疊在紅色生命上面一層半透明、有光澤的藍白色液面（{@link #shield}）；
 * 混沌免疫（最大生命 1）整顆是能量護盾的液體（{@link Liquid#SHIELD}）；心靈昇華（傷害先扣魔力）在魔力球的框上畫一段紫紅色（{@link #mom}）。
 * 這次之前能量護盾是框外面一圈外環（{@link #ring}，留著沒用）。
 * 每一層分開畫（液體＋玻璃底、能量護盾罩層、保留的灰色、框＋玻璃反光、心靈昇華標記），資源包疊字、模組疊圖。顏色都是 ARGB。
 */
public final class OrbArt {

    /** 一顆球的格子（GUI 像素）。 */
    public static final int BOX = 38;
    static final double C = BOX / 2.0;
    /** 液體半徑。 */
    public static final double LIQUID = 12.6;
    static final double FRAME_OUT = 15.6;
    /** 能量護盾外環（框外面一圈）。 */
    public static final double RING_IN = 16.2;
    public static final double RING_OUT = 18.6;

    /** 液體的顏色：亮、基本、暗、空的玻璃。 */
    public enum Liquid {
        LIFE(0xff6a50, 0xc81e1e, 0x420606, 0x1c0b0c),
        MANA(0x78a8ff, 0x2350d8, 0x07164e, 0x0b0d1e),
        /** 混沌免疫：整顆球是能量護盾。 */
        SHIELD(0xf2fbff, 0x7cc4f4, 0x1c3a78, 0x0b1020);

        final int light;
        final int base;
        final int dark;
        final int empty;

        Liquid(final int light, final int base, final int dark, final int empty) {
            this.light = light;
            this.base = base;
            this.dark = dark;
            this.empty = empty;
        }
    }

    private OrbArt() {
    }

    // ---- 純函式（測試用）----

    /**
     * 比例換成第幾段（0～levels）：0 = 空、levels = 滿；有一點就至少 1 段，沒滿就最多 levels − 1 段（看得出不是滿的）。
     */
    public static int segment(final double fraction, final int levels) {
        if (!(fraction > 0)) {
            return 0;
        }
        if (fraction >= 1) {
            return levels;
        }
        return (int) Math.max(1, Math.min(levels - 1, Math.round(fraction * levels)));
    }

    /** 液面（格子裡的 y，GUI 像素，往下是正）：level 0 = 球底、1 = 球頂；加上波紋（phase 是時間）。 */
    public static double surface(final double level, final double x, final double phase) {
        final double l = Math.max(0, Math.min(1, level));
        final double amp = 0.75 * Math.min(1, Math.min(l, 1 - l) * 8);
        final double wave = amp * (0.7 * Math.sin(x * 0.45 + phase) + 0.3 * Math.sin(x * 1.05 - phase * 1.7 + 1.3));
        return C + LIQUID - 2 * LIQUID * l + wave;
    }

    /**
     * 外環這一點（相對球心）有沒有被能量護盾填到：從正下方往兩邊同時往上長，fraction = 1 時接在正上方。
     */
    public static boolean ringFilled(final double dx, final double dy, final double fraction) {
        if (!(fraction > 0)) {
            return false;
        }
        final double fromBottom = Math.abs(Math.atan2(dx, dy)); // 正下方 0、正上方 π
        return fromBottom <= Math.min(1, fraction) * Math.PI + 1e-9;
    }

    /** 往目標靠近（畫面上平滑地動）：每秒靠近 rate 倍的差距（指數趨近），dt 秒。 */
    public static double approach(final double current, final double target, final double dt, final double rate) {
        if (!(dt > 0)) {
            return current;
        }
        final double k = 1 - Math.exp(-rate * Math.min(dt, 1));
        final double next = current + (target - current) * k;
        return Math.abs(next - target) < 1e-4 ? target : next;
    }

    // ---- 畫圖 ----

    /**
     * 液體與玻璃底：level 是液面（0～1），trail 是剛剛還在的液面（&gt; level 時中間那段畫淡一點：剛被打掉的）。
     */
    public static int[] liquid(final int size, final Liquid kind, final double level, final double trail, final double phase) {
        final int[] out = new int[size * size];
        final double px = BOX / (double) size;
        final double[] top = new double[size];
        final double[] trailTop = new double[size];
        for (int i = 0; i < size; i++) {
            final double x = (i + 0.5) * px;
            top[i] = level <= 0 ? Double.MAX_VALUE : level >= 1 ? -1 : surface(level, x, phase);
            trailTop[i] = trail > level ? (trail >= 1 ? -1 : surface(trail, x, phase)) : Double.MAX_VALUE;
        }
        for (int j = 0; j < size; j++) {
            final double y = (j + 0.5) * px;
            for (int i = 0; i < size; i++) {
                final double x = (i + 0.5) * px;
                final double dx = x - C;
                final double dy = y - C;
                final double r = Math.hypot(dx, dy);
                final double cover = edge(LIQUID + 0.2 - r, px);
                if (cover <= 0) {
                    continue;
                }
                final double nx = dx / LIQUID;
                final double ny = dy / LIQUID;
                final double nz = Math.sqrt(Math.max(0, 1 - nx * nx - ny * ny));
                // 光從左上照過來
                final double lambert = clamp(-0.45 * nx - 0.55 * ny + 0.7 * nz);
                final double rim = clamp((r - (LIQUID - 2.2)) / 2.2); // 靠邊緣暗一點
                int rgb;
                if (y >= top[i]) {
                    rgb = mix(kind.dark, kind.base, 0.25 + 0.75 * lambert);
                    rgb = mix(rgb, kind.dark, 0.45 * rim);
                    final double depth = y - top[i];
                    if (depth < 1.3 && level < 1) {
                        rgb = mix(rgb, kind.light, 0.65 * (1 - depth / 1.3)); // 液面那一條亮的
                    }
                    // 液體裡慢慢浮動的光點
                    final double swirl = Math.sin(x * 0.7 + phase * 0.6) * Math.sin(y * 0.6 - phase * 0.45);
                    rgb = mix(rgb, kind.light, 0.10 * Math.max(0, swirl));
                } else if (y >= trailTop[i]) {
                    rgb = mix(mix(kind.empty, kind.base, 0.55), 0xffffff, 0.18 + 0.12 * lambert); // 剛被打掉的那段
                } else {
                    rgb = mix(kind.empty, 0x3a3a46, 0.25 * lambert);
                    rgb = mix(rgb, 0x000000, 0.35 * rim);
                }
                out[j * size + i] = argb(cover, rgb);
            }
        }
        return out;
    }

    /**
     * 能量護盾罩層（PoE 1）：從球底到 level 一層半透明的藍白色，液面一條亮線、左邊一條直的光澤；疊在生命的液體上面。
     */
    public static int[] shield(final int size, final double level, final double phase) {
        final int[] out = new int[size * size];
        if (!(level > 0)) {
            return out;
        }
        final double px = BOX / (double) size;
        for (int i = 0; i < size; i++) {
            final double x = (i + 0.5) * px;
            final double top = level >= 1 ? -1 : surface(level, x, phase + 2.1);
            for (int j = 0; j < size; j++) {
                final double y = (j + 0.5) * px;
                final double dx = x - C;
                final double dy = y - C;
                final double r = Math.hypot(dx, dy);
                final double cover = edge(LIQUID + 0.2 - r, px) * edge(y - top, px);
                if (cover <= 0) {
                    continue;
                }
                final double nx = dx / LIQUID;
                final double ny = dy / LIQUID;
                final double nz = Math.sqrt(Math.max(0, 1 - nx * nx - ny * ny));
                double alpha = 0.16 + 0.22 * (1 - nz); // 邊緣厚一點（像一層殼），中間透得出下面的紅色
                int rgb = mix(0x3c8cff, 0xb8e0ff, clamp(-0.4 * nx - 0.5 * ny + 0.6 * nz));
                final double depth = y - top;
                if (depth < 1.2 && level < 1) {
                    rgb = 0xf4fbff;
                    alpha = 0.92 - 0.5 * depth / 1.2; // 液面一條亮線
                }
                final double gloss = 1 - Math.abs((x - (C - LIQUID * 0.5)) / 1.6);
                if (gloss > 0) {
                    rgb = mix(rgb, 0xe8f6ff, 0.6 * gloss);
                    alpha = Math.max(alpha, 0.22 + 0.30 * gloss); // 左邊一條直的光澤
                }
                final double shimmer = Math.sin(x * 0.9 - y * 0.5 + phase * 1.3);
                alpha += 0.05 * Math.max(0, shimmer);
                out[j * size + i] = argb(cover * clamp(alpha), rgb);
            }
        }
        return out;
    }

    /**
     * 心靈昇華（受到的傷害有 share 先扣魔力）：魔力球的框上，從正下方往兩邊長的一段紫紅色（share = 1 時一整圈）。
     */
    public static int[] mom(final int size, final double share) {
        final int[] out = new int[size * size];
        if (!(share > 0)) {
            return out;
        }
        final double px = BOX / (double) size;
        final double inner = LIQUID + 0.4;
        final double outer = FRAME_OUT - 0.4;
        for (int j = 0; j < size; j++) {
            final double y = (j + 0.5) * px;
            for (int i = 0; i < size; i++) {
                final double x = (i + 0.5) * px;
                final double dx = x - C;
                final double dy = y - C;
                final double r = Math.hypot(dx, dy);
                final double cover = edge(r - inner, px) * edge(outer - r, px);
                if (cover <= 0 || !ringFilled(dx, dy, share)) {
                    continue;
                }
                final double t = (r - inner) / (outer - inner);
                out[j * size + i] = argb(cover * 0.92, mix(0x8a1478, 0xff78e6, Math.sin(Math.PI * clamp(t))));
            }
        }
        return out;
    }

    /** 保留的生命（PoE2：鎖住、回不來的那段）：從球頂往下 fraction 的灰色，斜線花紋。 */
    public static int[] reserved(final int size, final double fraction) {
        final int[] out = new int[size * size];
        if (!(fraction > 0)) {
            return out;
        }
        final double px = BOX / (double) size;
        final double bottom = C - LIQUID + 2 * LIQUID * Math.min(1, fraction);
        for (int j = 0; j < size; j++) {
            final double y = (j + 0.5) * px;
            if (y > bottom + px) {
                break;
            }
            for (int i = 0; i < size; i++) {
                final double x = (i + 0.5) * px;
                final double r = Math.hypot(x - C, y - C);
                final double cover = edge(LIQUID + 0.2 - r, px) * edge(bottom - y, px);
                if (cover <= 0) {
                    continue;
                }
                final boolean stripe = ((int) Math.floor((x + y) / 2.5)) % 2 == 0;
                int rgb = stripe ? 0x5a5254 : 0x4a4345;
                if (bottom - y < 0.9) {
                    rgb = 0x8a8082; // 下緣一條亮線
                }
                out[j * size + i] = argb(cover * 0.94, rgb);
            }
        }
        return out;
    }

    /** 金屬框（左上亮右下暗、四顆鉚釘）＋玻璃反光（左上一塊白）。 */
    public static int[] frame(final int size) {
        final int[] out = new int[size * size];
        final double px = BOX / (double) size;
        final double mid = (LIQUID + FRAME_OUT) / 2;
        for (int j = 0; j < size; j++) {
            final double y = (j + 0.5) * px;
            for (int i = 0; i < size; i++) {
                final double x = (i + 0.5) * px;
                final double dx = x - C;
                final double dy = y - C;
                final double r = Math.hypot(dx, dy);
                // 反光（在液體上面）
                final double gx = (x - (C - 4.2)) / 5.6;
                final double gy = (y - (C - 5.6)) / 3.2;
                final double glare = 1 - (gx * gx + gy * gy);
                int color = 0;
                if (glare > 0 && r < LIQUID) {
                    color = argb(0.32 * glare * glare, 0xffffff);
                }
                final double cover = edge(r - (LIQUID - 0.3), px) * edge(FRAME_OUT - r, px);
                if (cover > 0) {
                    final double angle = Math.atan2(dy, dx);
                    final double light = 0.5 + 0.5 * Math.cos(angle + 2.3); // 左上亮
                    final double t = (r - LIQUID) / (FRAME_OUT - LIQUID);
                    final double bevel = Math.sin(Math.PI * clamp(t));
                    int rgb = mix(0x24190e, 0xe0c084, clamp(0.12 + 0.8 * light * bevel));
                    if (t < 0.18 || t > 0.86) {
                        rgb = mix(rgb, 0x0a0604, 0.7); // 內外各一條暗線
                    }
                    // 四顆鉚釘（斜 45 度）
                    for (int k = 0; k < 4; k++) {
                        final double a = Math.PI / 4 + k * Math.PI / 2;
                        final double rx = C + Math.cos(a) * mid;
                        final double ry = C + Math.sin(a) * mid;
                        final double d = Math.hypot(x - rx, y - ry);
                        if (d < 1.05) {
                            rgb = mix(0xfff0c0, 0x6a5030, clamp((d + (x - rx + y - ry) * 0.4) / 1.4));
                        }
                    }
                    color = over(color, argb(cover, rgb));
                }
                out[j * size + i] = color;
            }
        }
        return out;
    }

    /** 能量護盾外環（2026-11-02 第一版；改成 {@link #shield} 之後沒用了）：暗的底＋填到的那段藍白色（{@link #ringFilled}）；fraction &lt; 0 = 不畫（沒有能量護盾）。 */
    public static int[] ring(final int size, final double fraction) {
        final int[] out = new int[size * size];
        if (fraction < 0) {
            return out;
        }
        final double px = BOX / (double) size;
        for (int j = 0; j < size; j++) {
            final double y = (j + 0.5) * px;
            for (int i = 0; i < size; i++) {
                final double x = (i + 0.5) * px;
                final double dx = x - C;
                final double dy = y - C;
                final double r = Math.hypot(dx, dy);
                final double cover = edge(r - RING_IN, px) * edge(RING_OUT - r, px);
                if (cover <= 0) {
                    continue;
                }
                final double t = (r - RING_IN) / (RING_OUT - RING_IN);
                if (ringFilled(dx, dy, fraction)) {
                    final double glow = Math.sin(Math.PI * clamp(t));
                    out[j * size + i] = argb(cover, mix(0x5ab8ff, 0xf2fbff, clamp(0.25 + 0.75 * glow)));
                } else {
                    out[j * size + i] = argb(cover * 0.85, t < 0.2 || t > 0.8 ? 0x03060c : 0x0d1d38);
                }
            }
        }
        return out;
    }

    /**
     * 整顆疊好（模組、預覽圖用）：液體、能量護盾罩層（shield ≤ 0 不畫）、保留、框、心靈昇華標記（mom ≤ 0 不畫）。
     */
    public static int[] compose(final int size, final Liquid kind, final double level, final double trail, final double shield,
                                final double reserved, final double mom, final double phase) {
        final int[] out = liquid(size, kind, level, trail, phase);
        overAll(out, shield(size, shield, phase));
        overAll(out, reserved(size, reserved));
        overAll(out, frame(size));
        overAll(out, mom(size, mom));
        return out;
    }

    /** src 疊在 dst 上面（同樣大小）。 */
    public static void overAll(final int[] dst, final int[] src) {
        for (int i = 0; i < dst.length; i++) {
            dst[i] = over(dst[i], src[i]);
        }
    }

    /** 一般的 alpha 疊色（ARGB）。 */
    public static int over(final int dst, final int src) {
        final int sa = src >>> 24;
        if (sa == 0) {
            return dst;
        }
        final int da = dst >>> 24;
        if (sa == 255 || da == 0) {
            return src;
        }
        final double a = sa / 255.0;
        final double b = da / 255.0 * (1 - a);
        final double out = a + b;
        final int r = (int) Math.round((((src >> 16) & 0xff) * a + ((dst >> 16) & 0xff) * b) / out);
        final int g = (int) Math.round((((src >> 8) & 0xff) * a + ((dst >> 8) & 0xff) * b) / out);
        final int bl = (int) Math.round(((src & 0xff) * a + (dst & 0xff) * b) / out);
        return (int) Math.round(out * 255) << 24 | r << 16 | g << 8 | bl;
    }

    /** 邊緣的反鋸齒：distance 是到邊緣的距離（裡面是正），px 是一個像素多寬。 */
    private static double edge(final double distance, final double px) {
        return clamp(distance / px + 0.5);
    }

    private static double clamp(final double value) {
        return Math.max(0, Math.min(1, value));
    }

    private static int argb(final double alpha, final int rgb) {
        final int a = (int) Math.round(clamp(alpha) * 255);
        return a == 0 ? 0 : a << 24 | (rgb & 0xffffff);
    }

    static int mix(final int a, final int b, final double t) {
        final double k = clamp(t);
        final int r = (int) Math.round(((a >> 16) & 0xff) * (1 - k) + ((b >> 16) & 0xff) * k);
        final int g = (int) Math.round(((a >> 8) & 0xff) * (1 - k) + ((b >> 8) & 0xff) * k);
        final int bl = (int) Math.round((a & 0xff) * (1 - k) + (b & 0xff) * k);
        return (r << 16) | (g << 8) | bl;
    }
}
