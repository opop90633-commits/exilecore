package tw.exilecore.skill;

import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

/**
 * 同一招打同一個人的間隔（純邏輯，不依賴 Bukkit）：齊射的好幾發、追過來的光球、疊在一起的落點與地面效果，
 * 在 {@link BossSkill#hitGapTicks} 之內只算第一下。每隻怪物（招式引擎的一個腦袋）一個，不同怪物之間不互相影響。
 */
final class HitGate {

    /** 招式｜玩家 → 到哪個 tick 之前不能再打。 */
    private final Map<String, Long> until = new HashMap<>();

    /**
     * 這一招現在可以打這個人嗎；可以的話記下這次（之後 gap tick 內不能再打）。
     *
     * @param gap 至少隔幾 tick（0 以下 = 不限，也不記）
     */
    boolean allow(final String skill, final UUID victim, final long gap, final long now) {
        if (gap <= 0) {
            return true;
        }
        final String key = skill + "|" + victim;
        final Long blocked = until.get(key);
        if (blocked != null && now < blocked) {
            return false;
        }
        until.put(key, now + gap);
        return true;
    }

    /** 丟掉已經到期的紀錄（每秒一次，不然打過的人會一直留著）。 */
    void prune(final long now) {
        until.values().removeIf(tick -> tick <= now);
    }

    void clear() {
        until.clear();
    }

    int size() {
        return until.size();
    }
}
