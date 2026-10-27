package tw.exilecore.core;

import java.util.ArrayDeque;
import java.util.function.Consumer;

/**
 * 2026-10-27 效能：每個 tick 最多做幾件的排隊（擊殺爆炸的連鎖）。這個 tick 做不完的留到下一個 tick，順序不變，
 * 每一件最後都會做到（結果一樣，只是晚幾個 tick）。做的途中新排進來的不在這一次做（跟以前「排到下一個 tick」一樣，不會遞迴）。
 * 純邏輯，不依賴 Bukkit；只在主執行緒用。
 *
 * @param <T> 一件工作
 */
public final class TickCapQueue<T> {
    private final int cap;
    private final ArrayDeque<T> queue = new ArrayDeque<>();

    public TickCapQueue(final int cap) {
        this.cap = Math.max(1, cap);
    }

    /** 排一件；回傳排之前是不是空的（空的話呼叫的人要排下一個 tick 的 {@link #drain}）。 */
    public boolean add(final T item) {
        final boolean wasEmpty = queue.isEmpty();
        queue.add(item);
        return wasEmpty;
    }

    /**
     * 做這個 tick 的份：最多 cap 件，而且只做呼叫這時已經排著的。
     *
     * @return 還有沒有剩（有的話呼叫的人要再排下一個 tick）
     */
    public boolean drain(final Consumer<T> work) {
        final int count = Math.min(cap, queue.size());
        for (int i = 0; i < count; i++) {
            final T item = queue.poll();
            try {
                work.accept(item);
            } catch (final RuntimeException e) {
                lastError = e; // 一件出錯不影響其他的（呼叫的人用 takeError 記錄）
            }
        }
        return !queue.isEmpty();
    }

    private RuntimeException lastError;

    /** 上次 drain 吞掉的錯誤（沒有 null），拿了就清掉。 */
    public RuntimeException takeError() {
        final RuntimeException error = lastError;
        lastError = null;
        return error;
    }

    public int size() {
        return queue.size();
    }
}
