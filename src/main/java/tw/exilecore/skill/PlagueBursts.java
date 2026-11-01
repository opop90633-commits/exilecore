package tw.exilecore.skill;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * 2026-11-01 照 PoE2 Bursting Plague（瘟潰）：被輔助的技能造成的中毒，每秒把中毒傷害的 {@value #CONVERT_PERCENT}%
 * 存成敵人身上的瘟疫（最多牠最大生命的 {@value #CAP_PERCENT_OF_LIFE}%）；帶著瘟疫死掉時炸出瘟潰爆，物理傷害等於存下的瘟疫，
 * 瘟疫越滿範圍越大（滿的時候範圍更多 100%）。
 * <p>
 * 只算真的在扣血的毒：每一跳照「這個玩家在這隻身上的中毒每秒傷害」與「被輔助的技能施加、還沒結束的毒」取小的。
 * 這裡只管紀錄與計算；施加、每一跳、死掉由 {@link SupportProcs} 接。
 */
final class PlagueBursts {

    static final double CONVERT_PERCENT = 100;
    static final double CAP_PERCENT_OF_LIFE = 20;
    /** 這麼久沒有新的毒、也沒在累積就丟掉紀錄（tick）。 */
    static final long IDLE_TICKS = 20L * 60;

    static final class Plague {
        final String skillId;
        double amount;
        long lastTick;
        /** 被輔助的技能施加的毒：{每秒傷害, 到哪個 tick}。 */
        final List<double[]> poisons = new ArrayList<>();

        Plague(final String skillId) {
            this.skillId = skillId;
        }
    }

    /** 敵人 → 玩家 → 瘟疫。 */
    private final Map<UUID, Map<UUID, Plague>> plagues = new HashMap<>();

    /** 被輔助的技能讓敵人中毒了。 */
    void poisoned(final UUID target, final UUID owner, final String skillId, final double dps, final long untilTick, final long now) {
        if (dps <= 0 || untilTick <= now) {
            return;
        }
        final Plague plague = plagues.computeIfAbsent(target, uuid -> new HashMap<>()).computeIfAbsent(owner, uuid -> new Plague(skillId));
        plague.poisons.add(new double[] {dps, untilTick});
        plague.lastTick = now;
        if (plagues.size() > 2048) {
            prune(now);
        }
    }

    /** 中毒跳了一次：這個玩家在這隻身上這一跳的每秒傷害、跳的間隔（秒）。 */
    void ticked(final UUID target, final UUID source, final double dps, final double seconds, final long now) {
        final Map<UUID, Plague> mine = plagues.get(target);
        final Plague plague = mine == null ? null : mine.get(source);
        if (plague == null) {
            return;
        }
        plague.amount += gain(activeDps(plague.poisons, now), dps, seconds);
        plague.lastTick = now;
    }

    /** 敵人死掉了：拿走牠身上所有玩家的瘟疫（沒有回傳 null）。 */
    Map<UUID, Plague> died(final UUID target) {
        return plagues.remove(target);
    }

    void forgetOwner(final UUID owner) {
        for (final Iterator<Map<UUID, Plague>> it = plagues.values().iterator(); it.hasNext(); ) {
            final Map<UUID, Plague> mine = it.next();
            mine.remove(owner);
            if (mine.isEmpty()) {
                it.remove();
            }
        }
    }

    private void prune(final long now) {
        for (final Iterator<Map<UUID, Plague>> it = plagues.values().iterator(); it.hasNext(); ) {
            final Map<UUID, Plague> mine = it.next();
            mine.values().removeIf(plague -> now - plague.lastTick > IDLE_TICKS && activeDps(plague.poisons, now) <= 0);
            if (mine.isEmpty()) {
                it.remove();
            }
        }
    }

    /** 還沒結束的毒的每秒傷害總和（順便丟掉結束的）。純函式（測試用）。 */
    static double activeDps(final List<double[]> poisons, final long now) {
        poisons.removeIf(each -> each[1] <= now);
        double sum = 0;
        for (final double[] each : poisons) {
            sum += each[0];
        }
        return sum;
    }

    /** 這一跳存進多少瘟疫：被輔助的毒與實際扣血的毒取小的 × 秒數 × 轉換比例。純函式（測試用）。 */
    static double gain(final double supportedDps, final double actualDps, final double seconds) {
        return Math.max(0, Math.min(supportedDps, actualDps)) * Math.max(0, seconds) * CONVERT_PERCENT / 100.0;
    }

    /** 上限：最大生命的 20%。純函式（測試用）。 */
    static double cap(final double maxLife) {
        return Math.max(0, maxLife) * CAP_PERCENT_OF_LIFE / 100.0;
    }

    /** 瘟潰爆的半徑倍率：瘟疫滿的時候範圍更多 100%（半徑 ×√2），照比例。純函式（測試用）。 */
    static double radiusScale(final double amount, final double cap) {
        final double fill = cap <= 0 ? 0 : Math.max(0, Math.min(1, amount / cap));
        return Math.sqrt(1 + fill);
    }
}
