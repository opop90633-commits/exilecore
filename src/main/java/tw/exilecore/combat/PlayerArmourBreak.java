package tw.exilecore.combat;

import tw.exilecore.stats.StatSheet;

import java.util.Map;
import java.util.Set;
import java.util.UUID;

/**
 * 怪物破玩家的護甲（2026-10-27 照 PoE2 Armour Break 對玩家）。
 * <p>
 * 原作：帶「破甲」的怪物（稀有／魔法怪詞綴 of Shattering：破甲量等同造成物理傷害的 1000%；換界石詞綴「怪物造成的物理傷害有 15–45% 作為破甲」）
 * 擊中你時扣你的護甲；扣到 0 就是完全破甲——玩家 4 秒（怪物 12 秒）沒有護甲。玩家被完全破甲時不會像怪物那樣多受 20% 物理傷害（原作只對非玩家）。
 * 天賦「減少 x% 承受的破甲」（堅不可破、伊澤洛之騎士、維納利斯之障）：破甲量 ×（1 ＋ 增加）。
 * 這次之前怪物的破甲只對怪物有效（玩家打怪），換界石的破甲詞綴對玩家沒有作用，「減少承受的破甲」平衡等效成增加護甲。
 * <p>
 * 部分破甲在最後一次被破之後 {@link #DURATION_TICKS} 恢復（我們照玩家的完全破甲時間；原作沒寫部分破甲對玩家多久，取一樣的 4 秒）。
 */
public final class PlayerArmourBreak {

    /** 屬性鍵：承受的破甲（INCREASED；天賦「減少 25% 承受的破甲」＝ -0.25）。 */
    public static final String TAKEN = "armour.break_taken";
    /** 玩家的破甲（部分、完全）持續多久：4 秒（PoE2：玩家的完全破甲 4 秒）。 */
    public static final long DURATION_TICKS = 80;

    private static final Map<UUID, State> STATES = new java.util.concurrent.ConcurrentHashMap<>();

    private PlayerArmourBreak() {
    }

    /** 一個玩家的破甲：被破掉多少、到什麼時候恢復、完全破甲到什麼時候。 */
    static final class State {
        double broken;
        long until;
        long fullyUntil;

        /** 扣掉 amount；剛好扣光（完全破甲）回傳 true。完全破甲期間不再累積。 */
        boolean add(final double amount, final double armour, final long now) {
            if (amount <= 0 || armour <= 0 || now < fullyUntil) {
                return false;
            }
            if (now >= until) {
                broken = 0;
            }
            broken = Math.min(armour, broken + amount);
            until = now + DURATION_TICKS;
            if (broken >= armour - 1e-6) {
                fullyUntil = now + DURATION_TICKS;
                broken = 0;
                until = 0;
                return true;
            }
            return false;
        }

        /** 目前被破掉的護甲；完全破甲回傳正無限大。 */
        double broken(final long now) {
            if (now < fullyUntil) {
                return Double.POSITIVE_INFINITY;
            }
            return now < until ? broken : 0;
        }
    }

    /**
     * 這一下破多少護甲：物理傷害 × 破甲百分比 ×（1 ＋ 增加承受的破甲）。
     *
     * @param physical     這一下減免前的物理傷害
     * @param percent      怪物的破甲（造成物理傷害的 %）
     * @param takenIncreased 玩家的「增加承受的破甲」（減少 25% ＝ -0.25）
     */
    public static double amount(final double physical, final double percent, final double takenIncreased) {
        if (physical <= 0 || percent <= 0) {
            return 0;
        }
        return physical * percent / 100.0 * Math.max(0, 1 + takenIncreased);
    }

    /** 玩家的「增加承受的破甲」。 */
    public static double takenIncreased(final StatSheet defender) {
        return defender.increased(TAKEN, Set.of());
    }

    /** 怪物擊中玩家：破甲。回傳這一下是不是讓玩家完全破甲。 */
    public static boolean onHit(final UUID player, final double amount, final double armour, final long now) {
        if (amount <= 0 || armour <= 0) {
            return false;
        }
        if (STATES.size() > 256) {
            STATES.values().removeIf(state -> state.broken(now) == 0);
        }
        return STATES.computeIfAbsent(player, key -> new State()).add(amount, armour, now);
    }

    /** 玩家目前被破掉的護甲；完全破甲回傳正無限大；沒被破回傳 0。 */
    public static double broken(final UUID player, final long now) {
        final State state = STATES.get(player);
        return state == null ? 0 : state.broken(now);
    }

    /** 被破之後還剩的護甲比例要扣多少（給「更多 -x% 護甲」用）：0–1。 */
    public static double lessFraction(final double broken, final double armour) {
        if (broken <= 0 || armour <= 0) {
            return 0;
        }
        return Double.isInfinite(broken) ? 1.0 : Math.min(1.0, broken / armour);
    }

    /** 死掉、換角色：清掉。 */
    public static void clear(final UUID player) {
        STATES.remove(player);
    }
}
