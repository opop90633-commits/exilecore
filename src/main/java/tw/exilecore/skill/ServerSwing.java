package tw.exilecore.skill;

import org.bukkit.Bukkit;
import org.bukkit.entity.Player;
import org.bukkit.inventory.EquipmentSlot;

import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

/**
 * 伺服器叫玩家揮手（右鍵、數字鍵施放、排隊放出來的左鍵、複擊、表情動作…）。
 * <p>
 * 2026-10-28 照站長「放技能時一直自動幫我按左鍵」：{@code player.swingMainHand()} 會把揮手動畫也送給玩家自己，
 * 原版客戶端收到自己的揮手動畫時走 {@code LocalPlayer.swing}，又把一個揮手封包送回伺服器（回音）。
 * Paper 收到揮手封包會沿視線找：沒碰到東西、或碰到實體交互距離外的實體（戰鬥中準星幾乎都對著怪物），就推出一個「左鍵點空氣」
 * （冒險模式碰到方塊推出「左鍵點方塊」），所以每次右鍵、數字鍵施放，延遲一個來回之後就多放一次左鍵的技能；
 * 左鍵的技能排隊放出來時伺服器又揮手，回音又變成左鍵，一直循環。以前只擋右鍵、丟東西之後 3 tick 內的揮手，延遲超過 150 毫秒就擋不到。
 * 這次之前伺服器揮手不記帳；現在每次伺服器揮手都記一筆「等回音」，回音回來時（{@link SkillInputListener}）吃掉、不算左鍵。
 * 一定要經過這裡揮手，不要直接呼叫 {@code swingMainHand()}。
 */
public final class ServerSwing {

    /** 回音最多等幾筆（表情動作連續揮手也不會一直累積）。 */
    static final int MAX_PENDING = 8;
    /** 回音最少、最多等幾 tick。 */
    static final int MIN_WINDOW = 8;
    static final int MAX_WINDOW = 40;
    /** 延遲（來回）之外多等幾 tick（網路抖動、伺服器一個 tick 處理不完）。 */
    static final int WINDOW_MARGIN = 6;

    private static final Map<UUID, Ledger> LEDGERS = new HashMap<>();

    private ServerSwing() {
    }

    public static void mainHand(final Player player) {
        swing(player, EquipmentSlot.HAND);
    }

    public static void offHand(final Player player) {
        swing(player, EquipmentSlot.OFF_HAND);
    }

    public static void swing(final Player player, final EquipmentSlot hand) {
        LEDGERS.computeIfAbsent(player.getUniqueId(), id -> new Ledger()).record(Bukkit.getCurrentTick(), window(player.getPing()));
        player.swingHand(hand);
    }

    /** 還有伺服器揮手的回音沒回來：從揮手推出來的左鍵先當成回音（同一個封包的揮手事件接著會吃掉一筆）。 */
    static boolean echoPending(final Player player, final long now) {
        final Ledger ledger = LEDGERS.get(player.getUniqueId());
        return ledger != null && ledger.pending(now);
    }

    /** 收到一個不是按住左鍵的揮手：有在等回音就吃掉一筆，回傳它是不是回音。 */
    static boolean consumeEcho(final Player player, final long now) {
        final Ledger ledger = LEDGERS.get(player.getUniqueId());
        return ledger != null && ledger.consume(now);
    }

    static void forget(final Player player) {
        LEDGERS.remove(player.getUniqueId());
    }

    /** 回音最多等幾 tick：延遲（毫秒，來回）換成 tick，再加一點。 */
    static int window(final int pingMs) {
        final int rtt = (int) Math.ceil(Math.max(0, pingMs) / 50.0);
        return Math.max(MIN_WINDOW, Math.min(MAX_WINDOW, rtt + WINDOW_MARGIN));
    }

    /** 等回音的帳（純邏輯，單元測試用）：幾筆還沒回來、最早那筆在哪個 tick 揮的、最晚等到哪個 tick。 */
    static final class Ledger {
        private int count;
        private long since;
        private long until = Long.MIN_VALUE;

        void record(final long now, final int window) {
            if (now > until) {
                count = 0;
            }
            if (count == 0) {
                since = now;
            }
            count = Math.min(MAX_PENDING, count + 1);
            until = Math.max(until, now + window);
        }

        /** 回音至少要一個來回：跟伺服器揮手同一個 tick 收到的揮手不會是回音。 */
        boolean pending(final long now) {
            return count > 0 && now > since && now <= until;
        }

        boolean consume(final long now) {
            if (!pending(now)) {
                if (now > until) {
                    count = 0;
                }
                return false;
            }
            count--;
            return true;
        }
    }
}
