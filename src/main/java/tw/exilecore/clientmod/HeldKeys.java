package tw.exilecore.clientmod;

import java.util.ArrayList;
import java.util.List;

/**
 * 2026-11-02：一個玩家用模組按住的技能鍵（純邏輯，測試用）。
 * <p>
 * 照原版滑鼠鍵按住時客戶端送的頻率重複「按一下」：左鍵那兩格（左鍵、潛行＋左鍵）每 tick、右鍵那兩格每 4 tick，
 * 所以按住連發、蓄力、持續引導跟按住滑鼠一模一樣（放開後照原本的逾時判斷放出）。數字鍵格只在按下時算一次
 * （原版按住數字鍵也只送一次；蓄力的數字鍵技能照舊「再按一次放出」）。
 * 放開的封包掉了也不會永遠按著：最多按住 {@link #MAX_HOLD_TICKS}。
 */
final class HeldKeys {

    /** 按住最久幾 tick（2 分鐘；封包掉了的保險）。 */
    static final long MAX_HOLD_TICKS = 20L * 120;
    /** 右鍵按住時原版每幾 tick 送一次「使用」。 */
    static final int RIGHT_REPEAT = 4;

    private final int slots;
    private final int mouseKeys;
    /** 每一格從哪個 tick 開始按住（-1 = 沒按）。 */
    private final long[] since;

    HeldKeys(final int slots, final int mouseKeys) {
        this.slots = slots;
        this.mouseKeys = mouseKeys;
        this.since = new long[slots];
        java.util.Arrays.fill(since, -1);
    }

    /** 按下：回傳 true = 這一下要放（剛按下；已經按著的重複按下不算）。 */
    boolean press(final int slot, final long now) {
        if (slot < 0 || slot >= slots || since[slot] >= 0) {
            return false;
        }
        since[slot] = now;
        return true;
    }

    void release(final int slot) {
        if (slot >= 0 && slot < slots) {
            since[slot] = -1;
        }
    }

    boolean held(final int slot) {
        return slot >= 0 && slot < slots && since[slot] >= 0;
    }

    /** 這個 tick 要重複「按一下」的格子（按下那個 tick 已經放過，不算）。 */
    List<Integer> repeats(final long now) {
        final List<Integer> out = new ArrayList<>();
        for (int slot = 0; slot < Math.min(slots, mouseKeys); slot++) {
            final long start = since[slot];
            if (start < 0 || now <= start) {
                continue;
            }
            if (now - start > MAX_HOLD_TICKS) {
                since[slot] = -1;
                continue;
            }
            final boolean right = slot % 2 == 1; // 0 左、1 右、2 潛行＋左、3 潛行＋右
            if (!right || (now - start) % RIGHT_REPEAT == 0) {
                out.add(slot);
            }
        }
        return out;
    }

    void clear() {
        java.util.Arrays.fill(since, -1);
    }
}
