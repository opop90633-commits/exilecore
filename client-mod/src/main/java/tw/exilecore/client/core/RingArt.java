package tw.exilecore.client.core;

/**
 * 2026-11-02 照站長（1.4）：生命球外面一圈金色的守護環（疊在球框上，不多佔位置）。從正上方順時針，長度照守護量 ÷ 最大生命（最多一整圈）。
 * 回傳 ARGB（邊緣反鋸齒）。純 Java（測試用）。
 */
public final class RingArt {

    public static final int GOLD = 0xFFE8C050;
    public static final int GOLD_LIGHT = 0xFFFFF0B0;

    private RingArt() {
    }

    /** 守護量換成環的長度（0～1）：守護 ÷ 最大生命，有守護就至少畫一小段。 */
    public static double fraction(final long guard, final long maxLife) {
        if (guard <= 0) {
            return 0;
        }
        return Math.max(0.06, Math.min(1, guard / (double) Math.max(1, maxLife)));
    }

    /**
     * @param size     圖的邊長（像素）
     * @param fraction 環的長度（0～1）
     */
    public static int[] ring(final int size, final double fraction) {
        final int[] argb = new int[size * size];
        if (fraction <= 0 || size <= 0) {
            return argb;
        }
        final double c = size / 2.0;
        final double outer = c - 0.2;
        final double thickness = Math.max(1.6, size * 0.075);
        final double inner = outer - thickness;
        final double sweep = Math.min(1, fraction) * Math.PI * 2;
        for (int y = 0; y < size; y++) {
            for (int x = 0; x < size; x++) {
                final double dx = x + 0.5 - c;
                final double dy = y + 0.5 - c;
                final double r = Math.sqrt(dx * dx + dy * dy);
                final double edge = Math.min(r - inner, outer - r);
                if (edge <= -0.5) {
                    continue;
                }
                double angle = Math.atan2(dx, -dy); // 正上方 0，順時針變大
                if (angle < 0) {
                    angle += Math.PI * 2;
                }
                final double along = sweep >= Math.PI * 2 ? 1 : Math.max(0, Math.min(1, (sweep - angle) * r + 0.5));
                final double alpha = Math.max(0, Math.min(1, edge + 0.5)) * along;
                if (alpha <= 0) {
                    continue;
                }
                final double t = (r - inner) / thickness; // 外緣亮一點
                final int base = t > 0.55 ? GOLD_LIGHT : GOLD;
                argb[y * size + x] = ((int) Math.round(alpha * 255) << 24) | (base & 0xFFFFFF);
            }
        }
        return argb;
    }
}
