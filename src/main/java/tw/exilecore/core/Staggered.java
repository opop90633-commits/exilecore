package tw.exilecore.core;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;

/**
 * 把「每隔一段時間把所有在線的人做一遍」攤開來做（2026-10-20）：以前自動存檔、網頁角色快照每 5 分鐘一次全部做完，
 * 100 人時同一個 tick 卡 1.6 秒。現在一輪分成 {@code slots} 次呼叫，每次只做一小批，一輪下來每個人剛好一次；
 * 這一輪中途上線的人排到這一輪後面，下線的由呼叫的人自己略過。
 */
public final class Staggered {
    private final int slots;
    private final ArrayDeque<UUID> queue = new ArrayDeque<>();
    private final Set<UUID> round = new HashSet<>();
    private int calls;
    private boolean started;

    /** @param slots 一輪分成幾次呼叫（例如每 10 tick 叫一次、一輪 5 分鐘 = 600） */
    public Staggered(final int slots) {
        this.slots = Math.max(1, slots);
    }

    /**
     * 這一次要做的人。一輪的次數到了才開新的一輪（{@link #newRound()} 會是 true）；
     * 每次做「還沒做的人 ÷ 這一輪剩下的次數」個（無條件進位），最後一次一定做完。
     */
    public List<UUID> next(final Collection<UUID> online) {
        started = false;
        if (calls >= slots) {
            calls = 0;
            round.clear();
            queue.clear();
        }
        if (calls == 0) {
            started = true;
        }
        for (final UUID id : online) {
            if (round.add(id)) {
                queue.add(id);
            }
        }
        final int left = slots - calls;
        calls++;
        final int count = (int) Math.ceil(queue.size() / (double) left);
        final List<UUID> out = new ArrayList<>(count);
        for (int i = 0; i < count && !queue.isEmpty(); i++) {
            out.add(queue.poll());
        }
        return out;
    }

    /** 上一次 {@link #next} 是不是新一輪的第一次。 */
    public boolean newRound() {
        return started;
    }
}
