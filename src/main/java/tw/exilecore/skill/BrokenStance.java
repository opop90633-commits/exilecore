package tw.exilecore.skill;

import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

/**
 * 2026-11-01 照 PoE2 0.5 Vaulting Impact（躍棍崩擊；PoB VaultingImpactPlayer 的 Vs Dazed 段）：破勢（Broken Stance）。
 * <ul>
 *   <li>躍棍崩擊的震波打中「眩暈中」的敵人時給牠破勢：強度是這一下傷害的 {@link #SHARE}（exploit_debuff_attack_damage_to_convert_to_magnitude_% 10），
 *       持續 {@link #TICKS} tick（60 秒）。</li>
 *   <li>之後玩家對牠的每一下擊中額外造成「強度」那麼多的物理傷害（不吃加成，跟穿刺、裂甲一樣加在減免前），
 *       打了 {@link #HIT_LIMIT} 下（broken_stance_hit_limit 10；品質每 1% +0.1 次，{@link #EXTRA_HITS}）就移除。</li>
 *   <li>再次給破勢：強度取大的，次數、時間重新算。</li>
 *   <li>躍棍崩擊本身不會造成眩暈（cannot_daze，skills.yml 的 flag.cannot_daze）。</li>
 * </ul>
 * 原作這次之前沒有。存在 StageLeftovers（擊中後給、擊中前加傷害）；這裡只有狀態與純邏輯。
 */
final class BrokenStance {

    static final String ID = TargetDash.VAULTING_IMPACT;
    /** 強度 = 這一下傷害的 10%。 */
    static final double SHARE = 0.10;
    /** 60 秒。 */
    static final long TICKS = 1200;
    /** 最多 10 下擊中。 */
    static final int HIT_LIMIT = 10;
    /** 品質：多撐幾下擊中（FLAT，gem_quality.yml 每 1% 0.1；小數捨去）。 */
    static final String EXTRA_HITS = "broken_stance.hits";

    record Entry(double amount, int hitsLeft, long until) {
    }

    private final Map<UUID, Entry> stances = new HashMap<>();

    /** 這次可以撐幾下（品質加的次數往下捨去）。 */
    static int hitLimit(final double extraHits) {
        return HIT_LIMIT + (int) Math.floor(Math.max(0, extraHits) + 1e-9);
    }

    /** 這一下傷害給的強度。 */
    static double magnitude(final double damage) {
        return damage > 0 ? damage * SHARE : 0;
    }

    /** 給破勢（同一隻再給：強度取大的，次數、時間重算）。 */
    void apply(final UUID target, final double damage, final double extraHits, final long now) {
        final double amount = magnitude(damage);
        if (target == null || amount <= 0) {
            return;
        }
        if (stances.size() > 512) {
            stances.values().removeIf(entry -> entry.until() <= now);
        }
        final Entry old = stances.get(target);
        final double kept = old != null && old.until() > now ? Math.max(old.amount(), amount) : amount;
        stances.put(target, new Entry(kept, hitLimit(extraHits), now + TICKS));
    }

    /** 這一下擊中：回傳要額外加的物理傷害（沒有破勢回傳 0），並扣一次。 */
    double consume(final UUID target, final long now) {
        final Entry entry = target == null ? null : stances.get(target);
        if (entry == null) {
            return 0;
        }
        if (entry.until() <= now || entry.hitsLeft() <= 0) {
            stances.remove(target);
            return 0;
        }
        if (entry.hitsLeft() <= 1) {
            stances.remove(target);
        } else {
            stances.put(target, new Entry(entry.amount(), entry.hitsLeft() - 1, entry.until()));
        }
        return entry.amount();
    }

    /** 測試、顯示用：目前的破勢（沒有回傳 null）。 */
    Entry get(final UUID target) {
        return stances.get(target);
    }

    void forget(final UUID target) {
        stances.remove(target);
    }
}
