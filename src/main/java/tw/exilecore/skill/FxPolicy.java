package tw.exilecore.skill;

import java.util.ArrayDeque;
import java.util.Deque;
import java.util.UUID;

/**
 * 粒子特效的規則（2026-11-02 照站長「伺服器粒子太多」）：純邏輯，不依賴 Bukkit，{@link Fx} 照這裡決定送給誰、送多少。
 * <ul>
 *   <li>看得到的距離：水平 {@code range} 格內、上下 {@code range} 格內（{@link #inView}）。</li>
 *   <li>每個人的「特效等級」高／中／低（{@link Quality}）× 這是誰的特效（{@link Relation}）× 重要與否 × 種類（{@link Kind}）
 *       → 送不送、密度幾成（{@link #density}）。重要的（Boss／怪物招式的預警、地圖機制的危險區）一律全部送。</li>
 *   <li>地上一圈的點數照周長（{@link #ringPoints}）、範圍裡面稀疏撒（{@link #areaPoints}）。</li>
 *   <li>持續的範圍（地面效果、光環、旗幟……）最多每 {@link #REDRAW_TICKS} tick 重畫一次（{@link Throttle}），
 *       同一個人、同一種、幾乎疊在一起的只畫一個（{@link Merge}）。</li>
 * </ul>
 */
public final class FxPolicy {

    /** 持續範圍的外框最多多久重畫一次（tick；0.5 秒）。 */
    public static final int REDRAW_TICKS = 10;
    /** 地上一圈：每隔幾格一點。 */
    static final double RING_SPACING = 0.85;
    /** 地上一圈最少、最多幾點（全密度）。 */
    static final int RING_MIN = 6;
    static final int RING_MAX = 48;
    /** 打薄之後的外框至少幾點（低畫質看別人的範圍：稀疏的外框）。 */
    static final int RING_SPARSE_MIN = 4;
    /** 範圍裡面：每幾平方格一點、最多幾點。 */
    static final double AREA_PER_POINT = 4.0;
    static final int AREA_MAX = 24;

    /** 中：別人的特效幾成。 */
    static final double MID_OTHERS = 0.4;
    /** 低：自己的特效幾成。 */
    static final double LOW_SELF = 0.6;
    /** 低：別人持續範圍的外框幾成（稀疏的外框；裡面、一次性的打擊不送）。 */
    static final double LOW_OTHERS_OUTLINE = 0.25;

    private FxPolicy() {
    }

    /** 玩家設定「特效等級」。 */
    public enum Quality {
        HIGH("high", "高"), MID("mid", "中"), LOW("low", "低");

        private final String key;
        private final String label;

        Quality(final String key, final String label) {
            this.key = key;
            this.label = label;
        }

        /** 存在角色進度裡的值。 */
        public String key() {
            return key;
        }

        /** 選單上的字（高／中／低）。 */
        public String label() {
            return label;
        }

        /** 按一下換下一個：高 → 中 → 低 → 高。 */
        public Quality next() {
            return values()[(ordinal() + 1) % values().length];
        }

        /** 存的值 → 等級（沒設定過、看不懂的算高）。 */
        public static Quality parse(final String value) {
            if (value != null) {
                for (final Quality quality : values()) {
                    if (quality.key.equals(value)) {
                        return quality;
                    }
                }
            }
            return HIGH;
        }
    }

    /** 這個特效跟看的人的關係。 */
    public enum Relation {
        /** 看的人自己的技能。 */
        SELF,
        /** 別的玩家的技能。 */
        OTHER,
        /** 不是玩家的：怪物、Boss、環境、地圖（不知道是誰的也算這個）。 */
        WORLD;

        static Relation of(final UUID owner, final UUID viewer) {
            if (owner == null) {
                return WORLD;
            }
            return owner.equals(viewer) ? SELF : OTHER;
        }
    }

    /** 特效的種類。 */
    public enum Kind {
        /** 一次性的（打擊、爆炸、軌跡……）。 */
        ONE_SHOT,
        /** 持續範圍的外框。 */
        OUTLINE,
        /** 持續範圍裡面撒的點。 */
        FILL
    }

    /**
     * 這個人看這個特效的密度（0 = 不送，1 = 全部）。
     *
     * @param hidesOthers 這個人關掉了「別人的技能特效」（重要的照常）
     */
    public static double density(final Quality quality, final Relation relation, final boolean important, final Kind kind,
                                 final boolean hidesOthers) {
        if (important) {
            return 1.0;
        }
        switch (relation) {
            case WORLD:
                return 1.0;
            case SELF:
                return quality == Quality.LOW ? LOW_SELF : 1.0;
            default:
                if (hidesOthers) {
                    return 0;
                }
                return switch (quality) {
                    case HIGH -> 1.0;
                    case MID -> MID_OTHERS;
                    case LOW -> kind == Kind.OUTLINE ? LOW_OTHERS_OUTLINE : 0;
                };
        }
    }

    /** 持續範圍的 DUST 大小（原本 × 1.35，1.2–2.4；DUST 越大存在越久，重畫變成 0.5 秒一次看起來一樣連續）。 */
    public static float longerSize(final float size) {
        return Math.min(2.4f, Math.max(1.2f, size * 1.35f));
    }

    /** 看得到嗎：水平 range 格內、上下 range 格內。 */
    public static boolean inView(final double dx, final double dy, final double dz, final double range) {
        return dx * dx + dz * dz <= range * range && Math.abs(dy) <= range;
    }

    /** 地上一圈要幾點：全密度照周長每 {@link #RING_SPACING} 格一點（6–48 點），再乘上密度（打薄後至少 4 點；密度 0 不畫）。 */
    public static int ringPoints(final double radius, final double density) {
        if (density <= 0 || radius <= 0) {
            return 0;
        }
        final int full = Math.max(RING_MIN, Math.min(RING_MAX, (int) Math.round(2 * Math.PI * radius / RING_SPACING)));
        if (density >= 1) {
            return full;
        }
        return Math.min(full, Math.max(RING_SPARSE_MIN, (int) Math.round(full * density)));
    }

    /** 範圍裡面稀疏撒幾點：每 {@link #AREA_PER_POINT} 平方格一點（1–24），再乘上密度（密度 0 不撒）。 */
    public static int areaPoints(final double radius, final double density) {
        if (density <= 0 || radius <= 0) {
            return 0;
        }
        final int full = Math.max(1, Math.min(AREA_MAX, (int) Math.round(Math.PI * radius * radius / AREA_PER_POINT)));
        return Math.max(1, (int) Math.round(full * Math.min(1, density)));
    }

    /** 一次送 count 顆的特效打成密度 density 時送幾顆（count 0 是有方向的粒子，不改；至少 1）。 */
    public static int scaledCount(final int count, final double density) {
        if (count <= 1 || density >= 1) {
            return count;
        }
        return Math.max(1, (int) Math.round(count * density));
    }

    /**
     * 一顆一顆送的點（count ≤ 1）打成密度 density：第 sequence 點要不要送（平均每 1/density 點送一點，不隨機）。
     */
    public static boolean keep(final long sequence, final double density) {
        if (density >= 1) {
            return true;
        }
        if (density <= 0) {
            return false;
        }
        return Math.floor((sequence + 1) * density) > Math.floor(sequence * density);
    }

    /** 持續範圍的重畫：同一個範圍最多每 {@link #REDRAW_TICKS} tick 畫一次（上一次畫的時間記在呼叫的人那裡）。 */
    public static final class Throttle {
        private final int interval;

        public Throttle(final int interval) {
            this.interval = Math.max(1, interval);
        }

        /** 上次畫在 lastDrawn（沒畫過 = Long.MIN_VALUE），現在 now：要不要畫。 */
        public boolean due(final long lastDrawn, final long now) {
            return lastDrawn == Long.MIN_VALUE || now - lastDrawn >= interval || now < lastDrawn;
        }

        public int interval() {
            return interval;
        }
    }

    /**
     * 合併疊在一起的範圍（同一個人、同一種、中心在已經畫的那個的半徑一半以內）：這一輪（{@link #REDRAW_TICKS} tick）只畫一個。
     */
    public static final class Merge {
        private record Drawn(UUID owner, Object type, double x, double y, double z, double radius, long tick) {
        }

        private final Deque<Drawn> recent = new ArrayDeque<>();
        private final int window;

        public Merge(final int window) {
            this.window = window;
        }

        /**
         * 想在 (x, y, z) 畫一個半徑 radius 的 type：前面這一輪已經有同一個人、同一種、中心在半徑一半以內的就回傳 false（不畫）；
         * 不然記下來、回傳 true。
         */
        public boolean claim(final UUID owner, final Object type, final double x, final double y, final double z, final double radius,
                             final long now) {
            while (!recent.isEmpty() && (now - recent.peekFirst().tick() >= window || now < recent.peekFirst().tick())) {
                recent.pollFirst();
            }
            for (final Drawn drawn : recent) {
                if (java.util.Objects.equals(drawn.owner(), owner) && drawn.type().equals(type)) {
                    final double limit = 0.5 * Math.max(drawn.radius(), radius);
                    final double dx = drawn.x() - x;
                    final double dz = drawn.z() - z;
                    if (dx * dx + dz * dz <= limit * limit && Math.abs(drawn.y() - y) <= 2) {
                        return false;
                    }
                }
            }
            recent.addLast(new Drawn(owner, type, x, y, z, radius, now));
            return true;
        }

        int size() {
            return recent.size();
        }
    }
}
