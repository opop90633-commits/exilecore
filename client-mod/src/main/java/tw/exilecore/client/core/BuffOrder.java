package tw.exilecore.client.core;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

/**
 * 2026-11-02 照站長（1.4）：增益列的排序——重要的（護符、神龕）、技能格的增益排前面，同一級的快結束的排前面；
 * 光環排最後（沒有倒數）。增益列放不下寫「+N」時藏起來的是排在後面的（比較不重要、還很久的）。純 Java（測試用）。
 */
public final class BuffOrder {

    private BuffOrder() {
    }

    /** 重要程度：0 = 護符、神龕；1 = 技能格的增益；2 = 其他；3 = 光環（slot = aura）。 */
    public static int tier(final HudData.Buff buff, final int auraSlot) {
        if (buff.slot() == auraSlot) {
            return 3;
        }
        if (buff.important()) {
            return 0;
        }
        return buff.slot() >= 0 ? 1 : 2;
    }

    /** 還沒結束的，照 {@link #tier} 再照剩下的時間排（同名同時間照原本的順序）。 */
    public static List<HudData.Buff> sorted(final List<HudData.Buff> buffs, final long elapsed, final int auraSlot) {
        final List<HudData.Buff> live = new ArrayList<>();
        for (final HudData.Buff buff : buffs) {
            if (buff.left(elapsed) > 0) {
                live.add(buff);
            }
        }
        live.sort(Comparator.<HudData.Buff>comparingInt(buff -> tier(buff, auraSlot)).thenComparingLong(buff -> buff.left(elapsed)));
        return live;
    }
}
