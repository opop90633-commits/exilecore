package tw.exilecore.core;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Supplier;

/**
 * 效能計時（給 /ec perf 看）：每個區段（定時工作、戰鬥事件…）每秒花了多少時間、呼叫幾次、最慢一次多久。
 * 保留最近 60 秒，每秒換一格（{@link #roll()}）。只在主執行緒用。純邏輯，不依賴 Bukkit。
 */
public final class Perf {

    /** 保留幾秒。 */
    public static final int WINDOW = 60;

    /** 一個區段：每秒一格的總時間、次數、最慢一次。 */
    private static final class Section {
        final long[] nanos = new long[WINDOW];
        final int[] calls = new int[WINDOW];
        final long[] max = new long[WINDOW];
    }

    /**
     * 一個區段最近 seconds 秒的統計。
     *
     * @param name          區段名稱
     * @param msPerTick     平均每個 tick 花幾毫秒（一秒 20 tick；伺服器每個 tick 只有 50 毫秒）
     * @param callsPerSecond 每秒呼叫幾次
     * @param maxMs         最慢的一次（毫秒）
     */
    public record Stat(String name, double msPerTick, double callsPerSecond, double maxMs) {
    }

    private final Map<String, Section> sections = new LinkedHashMap<>();
    private int current;
    /** 已經走過幾秒（還沒滿 60 秒時平均只算有資料的秒數）。 */
    private int filled = 1;

    /** 計時跑一段工作。 */
    public void time(final String name, final Runnable task) {
        final long start = System.nanoTime();
        try {
            task.run();
        } finally {
            record(name, System.nanoTime() - start);
        }
    }

    public <T> T time(final String name, final Supplier<T> task) {
        final long start = System.nanoTime();
        try {
            return task.get();
        } finally {
            record(name, System.nanoTime() - start);
        }
    }

    /** 記一筆（奈秒）。 */
    public void record(final String name, final long nanos) {
        final Section section = sections.computeIfAbsent(name, key -> new Section());
        section.nanos[current] += nanos;
        section.calls[current]++;
        if (nanos > section.max[current]) {
            section.max[current] = nanos;
        }
        if (nanos >= spikeNanos && spikeListener != null) {
            spike(name, section, nanos);
        }
    }

    // ---- 2026-10-27：單次太慢（瞬間掉 TPS）的警告：每個區段 30 秒內最多寫一次 log，期間另外幾次一起報 ----

    /** 單次超過幾毫秒算「卡頓」。 */
    public static final double SPIKE_MS = 20;
    /** 同一個區段多久最多警告一次（奈秒）。 */
    static final long SPIKE_QUIET_NANOS = 30_000_000_000L;

    /** 一次卡頓警告：區段、這次幾毫秒、上次警告之後另外又超過幾次（沒寫進 log 的）。 */
    public record Spike(String name, double ms, int suppressed) {
    }

    private long spikeNanos = Math.round(SPIKE_MS * 1_000_000);
    private java.util.function.Consumer<Spike> spikeListener;
    private java.util.function.LongSupplier clock = System::nanoTime;
    private final Map<String, long[]> spikeState = new java.util.HashMap<>();

    /** 卡頓時通知誰（ExileCore 寫 log）；thresholdMs：單次超過幾毫秒。 */
    public void onSpike(final double thresholdMs, final java.util.function.Consumer<Spike> listener) {
        this.spikeNanos = Math.max(1, Math.round(thresholdMs * 1_000_000));
        this.spikeListener = listener;
    }

    /** 測試用：換時鐘。 */
    void clock(final java.util.function.LongSupplier clock) {
        this.clock = clock;
    }

    private void spike(final String name, final Section section, final long nanos) {
        final long[] state = spikeState.computeIfAbsent(name, key -> new long[] {Long.MIN_VALUE, 0}); // {上次警告的時間, 期間略過幾次}
        final long now = clock.getAsLong();
        if (state[0] != Long.MIN_VALUE && now - state[0] < SPIKE_QUIET_NANOS) {
            state[1]++;
            return;
        }
        final int suppressed = (int) state[1];
        state[0] = now;
        state[1] = 0;
        try {
            spikeListener.accept(new Spike(name, nanos / 1e6, suppressed));
        } catch (final RuntimeException ignored) {
            // 寫 log 出錯不能影響計時
        }
    }

    /** 每秒呼叫一次：換到下一格（清掉 60 秒前的資料）。 */
    public void roll() {
        current = (current + 1) % WINDOW;
        for (final Section section : sections.values()) {
            section.nanos[current] = 0;
            section.calls[current] = 0;
            section.max[current] = 0;
        }
        filled = Math.min(WINDOW, filled + 1);
    }

    /**
     * 最近 seconds 秒（不含正在累積的這一秒）的統計，依每 tick 花的時間由多到少排。
     */
    public List<Stat> stats(final int seconds) {
        final int span = Math.max(1, Math.min(Math.min(seconds, WINDOW - 1), filled - 1));
        final List<Stat> list = new ArrayList<>();
        for (final Map.Entry<String, Section> entry : sections.entrySet()) {
            long nanos = 0;
            long calls = 0;
            long max = 0;
            for (int back = 1; back <= span; back++) {
                final int index = Math.floorMod(current - back, WINDOW);
                nanos += entry.getValue().nanos[index];
                calls += entry.getValue().calls[index];
                max = Math.max(max, entry.getValue().max[index]);
            }
            if (calls == 0) {
                continue;
            }
            list.add(new Stat(entry.getKey(), nanos / 1e6 / (span * 20.0), calls / (double) span, max / 1e6));
        }
        list.sort(Comparator.comparingDouble(Stat::msPerTick).reversed());
        return list;
    }
}
