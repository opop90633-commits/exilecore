package tw.exilecore.core;

import org.slf4j.Logger;

import java.util.HashMap;
import java.util.Map;
import java.util.function.LongSupplier;

/**
 * 2026-10-27 效能：每 tick 跑的東西出錯時，同一個錯（同一段訊息、同一種例外、同一行程式）一分鐘只寫一次完整的堆疊，
 * 期間略過幾次下次一起報。這次之前每 tick 出錯就每 tick 寫一份完整堆疊（一秒 20 份），寫 log 本身就會把 TPS 拖垮。
 * 純邏輯（Logger 由呼叫的人給）。
 */
public final class LogThrottle {

    /** 同一個錯多久最多寫一次（奈秒）。 */
    static final long QUIET_NANOS = 60_000_000_000L;
    /** 記住幾種不同的錯（超過就全部忘掉重來，不會無限長大）。 */
    static final int MAX_KEYS = 512;

    private final Map<String, long[]> seen = new HashMap<>();
    private final LongSupplier clock;

    public LogThrottle() {
        this(System::nanoTime);
    }

    LogThrottle(final LongSupplier clock) {
        this.clock = clock;
    }

    /** 共用的一份（主執行緒用）。 */
    private static final LogThrottle SHARED = new LogThrottle();

    /**
     * 這個錯現在該不該寫：該寫回傳上次寫之後略過了幾次（0 以上），不該寫回傳 -1。
     */
    public synchronized int allow(final String message, final Throwable error) {
        final String key = key(message, error);
        final long now = clock.getAsLong();
        long[] state = seen.get(key);
        if (state == null) {
            if (seen.size() >= MAX_KEYS) {
                seen.clear();
            }
            state = new long[] {now, 0};
            seen.put(key, state);
            return 0;
        }
        if (now - state[0] < QUIET_NANOS) {
            state[1]++;
            return -1;
        }
        final int suppressed = (int) Math.min(Integer.MAX_VALUE, state[1]);
        state[0] = now;
        state[1] = 0;
        return suppressed;
    }

    static String key(final String message, final Throwable error) {
        final StringBuilder key = new StringBuilder(message == null ? "" : message);
        if (error != null) {
            key.append('|').append(error.getClass().getName());
            final StackTraceElement[] trace = error.getStackTrace();
            if (trace.length > 0) {
                key.append('@').append(trace[0].getClassName()).append(':').append(trace[0].getLineNumber());
            }
        }
        return key.toString();
    }

    /** 照共用的節流寫一筆錯誤（訊息不帶參數；要帶名字的先自己組好）。 */
    public static void error(final Logger logger, final String message, final Throwable error) {
        final int suppressed = SHARED.allow(message, error);
        if (suppressed < 0) {
            return;
        }
        if (suppressed > 0) {
            logger.error("{}（同一個錯一分鐘內另外又發生 {} 次，沒有重複寫）", message, suppressed, error);
        } else {
            logger.error(message, error);
        }
    }
}
