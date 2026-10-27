package tw.exilecore.character;

import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

/**
 * 2026-10-27 效能：天賦樹／「最近…」的條件一變就整張屬性表重算；在門檻上來回跳（移動中／站著、低生命、被包圍…）時
 * 一秒重算好幾次。現在每個人因為「條件變了」而重算之後，至少隔 {@link #HOLD_TICKS} tick 才再看一次條件
 * （每 10 tick 檢查一次，所以條件生效最多晚 0.5 秒）。裝備、增益等其他原因的重算不受影響（照常馬上重算，條件也一起更新）。
 * 純邏輯，不依賴 Bukkit。
 */
final class ConditionHold {

    /** 兩次「條件變了」的重算之間至少隔幾 tick（1 秒）。 */
    static final long HOLD_TICKS = 20;

    private final Map<UUID, Long> last = new HashMap<>();

    /** 現在可以檢查條件（變了就重算）嗎。 */
    boolean ready(final UUID player, final long now) {
        final Long at = last.get(player);
        return at == null || now - at >= HOLD_TICKS;
    }

    /** 這個 tick 因為條件變了重算了。 */
    void flipped(final UUID player, final long now) {
        last.put(player, now);
    }

    void forget(final UUID player) {
        last.remove(player);
    }
}
