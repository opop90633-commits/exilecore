package tw.exilecore.skill;

import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

/**
 * 2026-11-01 照 PoE2 的「可存次數」（storedUses）：有冷卻的觸發技能可以存好幾次，用一次少一次，
 * 照順序一次充一次（跟 {@link SkillRuntime#startCooldown} 的主動技能同一套算法）。
 * 第一個用到的是 Impending Doom（咒爆）觸發的咒殞爆：冷卻 0.15 秒、存 3～7 次（skills.yml 的 cooldown_uses 逐級表）。
 * 這次之前觸發技能（{@link SupportProcs}）只有單一冷卻，同時結束的詛咒是照冷卻一隻一隻排開炸。
 */
final class StoredUses {

    /** 玩家 → 技能代號 → 每一次好的 tick。 */
    private final Map<UUID, Map<String, long[]>> slots = new HashMap<>();

    /** 有一次能用就用掉、開始充那一次，回傳 true；全部都在充就回傳 false（觸發失敗，不排隊）。 */
    boolean tryUse(final UUID player, final String key, final int uses, final long now, final long ticks) {
        final Map<String, long[]> mine = slots.computeIfAbsent(player, id -> new HashMap<>());
        final long[] resized = resize(mine.get(key), Math.max(1, uses));
        mine.put(key, resized);
        return use(resized, now, ticks);
    }

    void forget(final UUID player) {
        slots.remove(player);
    }

    /**
     * 純函式（測試用）：用掉最早好的那一次；照順序一次充一次——這一次在「其他次裡最晚好的」之後（或現在）再過 ticks 才好。
     * 沒有好的（每一次都要等）回傳 false，不改動。
     */
    static boolean use(final long[] slots, final long now, final long ticks) {
        if (slots.length == 0) {
            return false;
        }
        int use = 0;
        for (int i = 1; i < slots.length; i++) {
            if (slots[i] < slots[use]) {
                use = i;
            }
        }
        if (slots[use] > now) {
            return false;
        }
        long latest = now;
        for (int i = 0; i < slots.length; i++) {
            if (i != use) {
                latest = Math.max(latest, slots[i]);
            }
        }
        slots[use] = latest + Math.max(0, ticks);
        return true;
    }

    /** 純函式（測試用）：現在能用的次數。 */
    static int available(final long[] slots, final long now) {
        int count = 0;
        for (final long ready : slots) {
            if (ready <= now) {
                count++;
            }
        }
        return count;
    }

    /** 等級變了、次數跟著變：多的次數一開始就是好的，少了就丟掉最晚好的那幾次。純函式（測試用）。 */
    static long[] resize(final long[] current, final int uses) {
        if (current == null) {
            return new long[uses];
        }
        if (current.length == uses) {
            return current;
        }
        final long[] sorted = current.clone();
        java.util.Arrays.sort(sorted);
        final long[] result = new long[uses];
        System.arraycopy(sorted, 0, result, 0, Math.min(uses, sorted.length)); // 多出來的是 0（好的）
        return result;
    }
}
