package tw.exilecore.client.core;

import java.util.HashMap;
import java.util.Map;

/**
 * 模組畫持續範圍的樣子（顏色、閃爍、要撒幾顆粒子、一圈分幾段）。純 Java（測試用）。
 *
 * <ul>
 *   <li>顏色：DUST／顏色粒子照伺服器給的顏色；其他粒子照種類給一個代表色（火焰橘、靈魂火青、煙灰……）。</li>
 *   <li>重要的（Boss／怪物招式的預警、地圖機制的危險區）：顏色往紅色偏一半，外框一秒閃一次（變粗變亮），裡面也跟著閃。</li>
 *   <li>撒的粒子比伺服器少（地上已經畫了圈）：每 {@link #AMBIENT_INTERVAL} tick 一輪；照玩家自己的「粒子」設定：
 *       全部 ×1、減少 ×0.5、最少 不撒（地上的圈照畫）。</li>
 * </ul>
 */
public final class FxLook {

    /** 持續範圍撒粒子的間隔（tick；跟伺服器重畫一樣）。 */
    public static final int AMBIENT_INTERVAL = 10;
    /** 重要的往紅色偏多少。 */
    static final double IMPORTANT_TINT = 0.5;
    static final int IMPORTANT_RED = 0xFF3030;
    /** 重要的閃一次幾 tick。 */
    public static final double PULSE_TICKS = 20;
    /** 2026-11-02 照站長：能踩的機制圈（站位圈、安全區）外框：照原本的顏色（跟沒裝模組看到的一樣），閃的時候往白色亮，不染紅。 */
    public static int safeEdgeColor(final FxCodec.Style style, final double t) {
        final double p = pulse(t);
        return argb(mix(baseColor(style), 0xFFFFFF, 0.3 * p), 0.75 + 0.25 * p);
    }

    /** 能踩的機制圈裡面：照原本的顏色，半透明。 */
    public static int safeFillColor(final FxCodec.Style style, final double t) {
        return argb(baseColor(style), 0.18 + 0.1 * pulse(t));
    }

    /** 外框寬度（格）。 */
    public static final float EDGE_WIDTH = 0.09f;
    public static final float IMPORTANT_EDGE_WIDTH = 0.13f;
    public static final float IMPORTANT_EDGE_PULSE = 0.07f;
    /** 玩家的粒子設定（原版 ParticleStatus 的順序）。 */
    public static final int PARTICLES_ALL = 0;
    public static final int PARTICLES_DECREASED = 1;
    public static final int PARTICLES_MINIMAL = 2;

    private static final Map<String, Integer> COLORS = new HashMap<>();

    static {
        put(0xFF8A2A, "flame", "small_flame", "lava", "dripping_lava", "falling_lava", "landing_lava", "copper_fire_flame");
        put(0x4FD8E6, "soul_fire_flame", "soul", "sculk_soul");
        put(0x8A8A8A, "smoke", "large_smoke", "campfire_cosy_smoke", "campfire_signal_smoke", "ash", "mycelium");
        put(0xE6E6E6, "cloud", "poof", "white_smoke", "white_ash", "snowflake", "item_snowball", "sweep_attack");
        put(0xB45AE6, "witch", "portal", "reverse_portal", "dragon_breath", "squid_ink", "trial_omen", "ominous_spawning");
        put(0xF2F2C8, "enchant", "end_rod", "firework", "flash", "glow", "wax_on");
        put(0x6EDC5A, "happy_villager", "composter", "spore_blossom_air", "falling_spore_blossom", "item_slime", "sneeze");
        put(0x8CD2FF, "electric_spark", "wax_off", "scrape", "nautilus", "dolphin");
        put(0xC8C8FF, "crit", "enchanted_hit");
        put(0xFF4060, "heart", "damage_indicator", "angry_villager", "crimson_spore");
        put(0x1E8C8C, "sculk_charge", "sculk_charge_pop", "warped_spore", "glow_squid_ink");
        put(0x3F76E4, "falling_water", "dripping_water", "splash", "bubble", "bubble_pop", "rain", "underwater", "fishing",
                "dripping_dripstone_water", "falling_dripstone_water", "current_down", "bubble_column_up");
        put(0xF0D040, "totem_of_undying", "falling_honey", "dripping_honey", "landing_honey", "falling_nectar", "wax_on");
        put(0x5A3A2A, "dust_plume", "egg_crack");
    }

    private static void put(final int rgb, final String... ids) {
        for (final String id : ids) {
            COLORS.put(id, rgb);
        }
    }

    private FxLook() {
    }

    /** 這個樣式的代表色（rgb）。 */
    public static int baseColor(final FxCodec.Style style) {
        if (style == null) {
            return 0xE0E0E0;
        }
        switch (style.kind()) {
            case FxCodec.DATA_DUST, FxCodec.DATA_DUST_TRANSITION, FxCodec.DATA_SPELL, FxCodec.DATA_TRAIL -> {
                return style.a() & 0xFFFFFF;
            }
            case FxCodec.DATA_COLOR -> {
                return style.a() & 0xFFFFFF;
            }
            default -> {
                // 照粒子種類
            }
        }
        String id = style.particle() == null ? "" : style.particle();
        if (id.startsWith("minecraft:")) {
            id = id.substring("minecraft:".length());
        }
        return COLORS.getOrDefault(id, 0xE0E0E0);
    }

    /** 0–1 的閃爍（重要的用；t 是 tick，可以有小數）。 */
    public static double pulse(final double t) {
        return 0.5 + 0.5 * Math.sin(t * Math.PI * 2 / PULSE_TICKS);
    }

    /** 重要的往紅色偏。 */
    public static int tint(final int rgb, final boolean important) {
        return important ? mix(rgb, IMPORTANT_RED, IMPORTANT_TINT) : rgb;
    }

    static int mix(final int a, final int b, final double t) {
        final int r = (int) Math.round((a >> 16 & 0xff) * (1 - t) + (b >> 16 & 0xff) * t);
        final int g = (int) Math.round((a >> 8 & 0xff) * (1 - t) + (b >> 8 & 0xff) * t);
        final int bl = (int) Math.round((a & 0xff) * (1 - t) + (b & 0xff) * t);
        return r << 16 | g << 8 | bl;
    }

    static int argb(final int rgb, final double alpha) {
        final int a = (int) Math.round(Math.max(0, Math.min(1, alpha)) * 255);
        return a << 24 | rgb & 0xFFFFFF;
    }

    /** 外框的顏色（argb）。 */
    public static int edgeColor(final FxCodec.Style style, final boolean important, final double t) {
        final int rgb = tint(baseColor(style), important);
        if (!important) {
            return argb(rgb, 0.85);
        }
        final double p = pulse(t);
        return argb(mix(rgb, IMPORTANT_RED, 0.3 * p), 0.6 + 0.4 * p);
    }

    /** 外框寬度（格）。 */
    public static float edgeWidth(final boolean important, final double t) {
        return important ? IMPORTANT_EDGE_WIDTH + (float) (IMPORTANT_EDGE_PULSE * pulse(t)) : EDGE_WIDTH;
    }

    /** 裡面（AREA、LINE）的顏色（argb，半透明）。 */
    public static int fillColor(final FxCodec.Style style, final boolean important, final double t) {
        final int rgb = tint(baseColor(style), important);
        return argb(rgb, important ? 0.16 + 0.12 * pulse(t) : 0.14);
    }

    /** 一圈分幾段（每段約 0.4 格，16–128 段）。 */
    public static int ringSegments(final double radius) {
        return (int) Math.max(16, Math.min(128, Math.round(2 * Math.PI * Math.max(0, radius) / 0.4)));
    }

    /** 圓盤分幾圈（每圈約 1 格，1–8 圈）。 */
    public static int discRings(final double radius) {
        return (int) Math.max(1, Math.min(8, Math.ceil(Math.max(0, radius))));
    }

    /** 線分幾段（每段約 0.5 格，1–128 段）。 */
    public static int lineSegments(final double length) {
        return (int) Math.max(1, Math.min(128, Math.ceil(Math.max(0, length) / 0.5)));
    }

    /** 這一輪（每 {@link #AMBIENT_INTERVAL} tick）在這個範圍撒幾顆粒子。 */
    public static int ambientCount(final FxCodec.Effect effect, final int particleSetting) {
        if (effect == null || particleSetting >= PARTICLES_MINIMAL) {
            return 0;
        }
        final double density = Math.max(0, Math.min(1, effect.density()));
        if (density <= 0) {
            return 0;
        }
        final double r = Math.max(0, effect.radius());
        final double base = switch (effect.shape()) {
            case FxCodec.SHAPE_RING -> Math.max(2, Math.min(12, effect.count() * 0.25));
            case FxCodec.SHAPE_AREA -> Math.max(2, Math.min(16, Math.PI * r * r / 8.0)) + Math.max(1, Math.min(8, effect.count() * 0.15));
            case FxCodec.SHAPE_LINE -> Math.max(2, Math.min(16, length(effect) / 2.0));
            case FxCodec.SHAPE_BEAM -> Math.max(3, Math.min(32, length(effect) / 0.75));
            default -> Math.max(1, Math.min(16, effect.count()));
        };
        final double scaled = base * density * (particleSetting == PARTICLES_DECREASED ? 0.5 : 1.0);
        return Math.max(1, (int) Math.round(scaled));
    }

    public static double length(final FxCodec.Effect effect) {
        final double dx = effect.ex() - effect.x();
        final double dy = effect.ey() - effect.y();
        final double dz = effect.ez() - effect.z();
        return Math.sqrt(dx * dx + dy * dy + dz * dz);
    }

    /** 這個範圍要不要畫地上的圖形（RING、AREA、LINE；BEAM、點、一團只撒粒子）。 */
    public static boolean drawsGround(final int shape) {
        return shape == FxCodec.SHAPE_RING || shape == FxCodec.SHAPE_AREA || shape == FxCodec.SHAPE_LINE;
    }
}
