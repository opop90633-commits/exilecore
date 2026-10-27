package tw.exilecore.core;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;

/**
 * 2026-10-27 效能：把「每隔一段時間把一整批東西（地圖裡的怪…）全部檢查一遍」攤到好幾次呼叫做完。
 * 一輪開始時（{@link #due()}）呼叫的人把這一輪要做的東西放進來（{@link #refill}），之後每次 {@link #next()}
 * 拿「剩下的 ÷ 這一輪剩下的次數」個（無條件進位），一輪的最後一次一定拿完。每個東西一輪剛好做一次，間隔跟以前一樣，
 * 只是不會全部擠在同一個 tick。純邏輯，不依賴 Bukkit；只在主執行緒用。
 *
 * @param <T> 要做的東西
 */
public final class Spread<T> {
    private final int slots;
    private final ArrayDeque<T> queue = new ArrayDeque<>();
    private int left;

    /** @param slots 一輪分成幾次呼叫 */
    public Spread(final int slots) {
        this.slots = Math.max(1, slots);
    }

    /** 這一輪做完了（或還沒開始）：該放下一輪的東西進來了。 */
    public boolean due() {
        return left <= 0;
    }

    /** 開新的一輪（上一輪沒做完的丟掉）。 */
    public void refill(final Collection<? extends T> items) {
        queue.clear();
        queue.addAll(items);
        left = slots;
    }

    /** 這一次要做的。 */
    public List<T> next() {
        if (left <= 0) {
            return List.of();
        }
        final int count = (int) Math.ceil(queue.size() / (double) left);
        left--;
        final List<T> out = new ArrayList<>(count);
        for (int i = 0; i < count && !queue.isEmpty(); i++) {
            out.add(queue.poll());
        }
        return out;
    }

    /** 這一輪還沒做的有幾個。 */
    public int remaining() {
        return queue.size();
    }
}
