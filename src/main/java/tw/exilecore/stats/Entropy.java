package tw.exilecore.stats;

import java.util.HashMap;
import java.util.Map;
import java.util.UUID;
import java.util.random.RandomGenerator;

/**
 * PoE2 的命中「熵」（2026-10）：每個被打的目標有一個 0～99 的值；第一次被打（或 100 tick 沒被打過）時隨機給一個，
 * 之後每一下把攻擊方的命中率（百分比）加上去，到 100 以上就打中並減 100，不到就閃掉。
 * 所以 30% 閃避就是剛好每 10 下閃 3 下，不會連續閃很多下或連續被打很多下。所有打這個目標的人共用。
 */
public final class Entropy {

    /** 多久沒被打就重新隨機（tick；PoE2 是 100 server tick ≈ 3.3 秒，這裡用 Minecraft 的 tick 換算）。 */
    public static final long RESET_TICKS = 66;

    private static final class State {
        double value;
        long last;
    }

    private final Map<UUID, State> states = new HashMap<>();

    public boolean hits(final UUID target, final double chance, final long tick, final RandomGenerator random) {
        if (chance >= 1.0) {
            return true;
        }
        if (states.size() > 4096) {
            states.values().removeIf(state -> tick - state.last > RESET_TICKS);
        }
        State state = states.get(target);
        if (state == null || tick - state.last > RESET_TICKS) {
            state = new State();
            state.value = random.nextInt(100);
            states.put(target, state);
        }
        state.last = tick;
        state.value += Math.max(0, chance) * 100.0;
        if (state.value >= 100) {
            state.value -= 100;
            return true;
        }
        return false;
    }

    public void forget(final UUID target) {
        states.remove(target);
    }
}
