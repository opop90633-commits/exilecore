package tw.exilecore.skill;

import java.util.HashMap;
import java.util.Map;

/**
 * 粒子的預算（2026-10，打 Boss 時畫面偶爾卡一下）：同一個區域（128×128 格）每個 tick 最多送幾個「畫形狀用的」粒子封包，
 * 超過的就打薄。純邏輯，不依賴 Bukkit。
 * <p>
 * 警告圈、擴散環、光束這些是一顆一顆送的（每一點一個封包，附近每個玩家各一份）；幾招疊在一起時一個 tick 可以送出好幾百個，
 * 客戶端處理不完就會卡一下。預算用完時每個形狀仍然給 {@code floor} 點，形狀看得出來（地板變色才是主要的警告）。
 */
final class ParticleBudget {

    /** 一個區域多大（2 的次方，格）：同一場 Boss 戰的東西都在同一區。 */
    static final int AREA_SHIFT = 7;

    private final int perTick;
    private final Map<Long, int[]> used = new HashMap<>();
    private long tick = Long.MIN_VALUE;

    ParticleBudget(final int perTick) {
        this.perTick = perTick;
    }

    /** 區域的代號（世界、x、z 各佔一段）。 */
    static long area(final int worldHash, final int blockX, final int blockZ) {
        return ((long) worldHash << 32) ^ ((long) (blockX >> AREA_SHIFT) << 16) ^ ((blockZ >> AREA_SHIFT) & 0xFFFFL);
    }

    /**
     * 想畫 wanted 點：這一區這個 tick 還剩多少就給多少，至少 floor 點（wanted 比 floor 少就照 wanted）。
     *
     * @return 這次可以畫幾點
     */
    int allow(final long now, final long area, final int wanted, final int floor) {
        if (now != tick) {
            used.clear();
            tick = now;
        }
        if (wanted <= 0) {
            return 0;
        }
        final int[] spent = used.computeIfAbsent(area, key -> new int[1]);
        final int granted = Math.max(Math.min(floor, wanted), Math.min(wanted, perTick - spent[0]));
        spent[0] += granted;
        return granted;
    }

    /** 這一區這個 tick 已經用了多少（測試、效能檢視用）。 */
    int spent(final long now, final long area) {
        if (now != tick) {
            return 0;
        }
        final int[] spent = used.get(area);
        return spent == null ? 0 : spent[0];
    }
}
