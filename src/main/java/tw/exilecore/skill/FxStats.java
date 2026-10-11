package tw.exilecore.skill;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * 技能特效送了多少封包（2026-10，人多時大家的 PING 一起飆高）：照「誰的技能」分開算，
 * 每 {@link #WINDOW_TICKS} tick 結算一次，{@code /ec skill fx} 看上一段時間誰送最多。
 * 另外決定人多的時候，別人的技能特效要打薄幾倍（{@link #stride}）。純邏輯，不依賴 Bukkit。
 */
final class FxStats {

    /** 一段統計多長（tick）：10 秒。 */
    static final int WINDOW_TICKS = 200;
    /** 不是玩家的技能（怪物、Boss、環境）記在這裡。 */
    static final UUID OTHERS = new UUID(0L, 0L);

    /** 一個人的技能在一段時間內送出的封包。 */
    record Entry(UUID source, long packets, double perSecond) {
    }

    private Map<UUID, long[]> current = new HashMap<>();
    private Map<UUID, long[]> last = Map.of();
    private long windowStart = Long.MIN_VALUE;
    private long lastLength;

    /** 記一筆：source 的技能這次送了 packets 個封包（source null = 不是玩家的）。 */
    void add(final long now, final UUID source, final int packets) {
        if (packets <= 0) {
            return;
        }
        rotate(now);
        current.computeIfAbsent(source == null ? OTHERS : source, key -> new long[1])[0] += packets;
    }

    /** 上一段時間送最多的前 limit 名（還沒結算過一段就是空的）。 */
    List<Entry> top(final long now, final int limit) {
        rotate(now);
        final double seconds = Math.max(1, lastLength) / 20.0;
        final List<Entry> entries = new ArrayList<>(last.size());
        for (final Map.Entry<UUID, long[]> entry : last.entrySet()) {
            entries.add(new Entry(entry.getKey(), entry.getValue()[0], entry.getValue()[0] / seconds));
        }
        entries.sort((a, b) -> Long.compare(b.packets(), a.packets()));
        return entries.size() > limit ? List.copyOf(entries.subList(0, limit)) : List.copyOf(entries);
    }

    /** 上一段的總封包數。 */
    long total(final long now) {
        rotate(now);
        long sum = 0;
        for (final long[] value : last.values()) {
            sum += value[0];
        }
        return sum;
    }

    /** 上一段時間多長（秒；還沒結算過是 0）。 */
    double lastSeconds() {
        return lastLength / 20.0;
    }

    private void rotate(final long now) {
        if (windowStart == Long.MIN_VALUE) {
            windowStart = now;
            return;
        }
        if (now - windowStart >= WINDOW_TICKS) {
            // 中間整段沒人放技能：上一段就是空的
            last = now - windowStart >= 2L * WINDOW_TICKS && current.isEmpty() ? Map.of() : current;
            lastLength = Math.min(now - windowStart, 2L * WINDOW_TICKS);
            current = new HashMap<>();
            windowStart = now;
        }
    }

    /**
     * 附近有 viewers 個別人在看：別人每幾點才送一點（自己的技能照常全部送）。
     * 看的人不超過 full 個時每點都送；超過時打薄，讓每個技能送出的封包總量大約維持在 full 個人份。
     */
    static int stride(final int viewers, final int full) {
        if (full <= 0 || viewers <= full) {
            return 1;
        }
        return (viewers + full - 1) / full;
    }
}
