package tw.exilecore.skill;

import org.bukkit.Location;

import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

/**
 * 一個玩家的施放狀態（不存檔）：下一次可以動作的時間、排隊中的按鍵、跳躍中。
 */
final class SkillRuntime {

    /** 排隊的按鍵最多等幾 tick，太久就丟掉（避免放開按鍵很久之後突然放出來）。 */
    static final int QUEUE_TIMEOUT = 10;

    /** 下一次可以動作的 tick（有小數，見 {@link SkillMath#nextReady}）。 */
    double readyAt = Double.NEGATIVE_INFINITY;
    /** 排隊的按鍵（-1 = 沒有）與想打的目標。 */
    int queuedKey = -1;
    UUID queuedTarget;
    long queuedAt;
    /** 右鍵點了實體（NPC、怪物）之後，跟著來的「右鍵空氣」不要再施放一次。 */
    long suppressRightUntil = -1;
    /** 這個 tick 已經用右鍵施放過（主手、副手、點實體會各來一個事件）。 */
    long rightTriggerTick = -1;
    /** 這個 tick 已經算過一次左鍵（點方塊、點空氣、揮手、打到實體會各來一個事件；按住時每 tick 一次）。 */
    long leftTriggerTick = -1;
    /** 左鍵直接打到實體的 tick。 */
    long lastEntityAttackTick = -1;
    /** 按了右鍵、丟了東西的 tick：同一個或下一個 tick 從揮手推出來的「左鍵點空氣」不算（{@link SkillMath#airClick}）。 */
    long lastRightTick = -1;
    long lastDropTick = -1;
    /** 正在按住左鍵（客戶端開始「挖」某個方塊、還沒放開）：這時的揮手才算左鍵（{@link SkillMath#heldSwing}）。 */
    boolean leftHeld;
    /** 開始按住或上一次算進去的揮手是哪個 tick。 */
    long leftHeldTick = -1;
    /** 提示訊息與失敗音效的節流。 */
    long lastFailTick = -1_000;
    /** 持續引導（2026-10）：正在蓄的技能、第幾層、上一次放的 tick。 */
    String channelSkill;
    int channelStage;
    /** 2026-10：弩技能彈匣裡還有幾發（沒記 = 滿的）、裝填到什麼時候。 */
    final java.util.Map<String, Integer> ammo = new java.util.HashMap<>();
    final java.util.Map<String, Long> reloadDone = new java.util.HashMap<>();
    /** 2026-10：封印從什麼時候開始蓄（上次解開的 tick）。 */
    final java.util.Map<String, Long> sealSince = new java.util.HashMap<>();
    long channelTick = -1_000;
    /** 跳躍中。 */
    Leap leap;
    /** 上次落地的 tick（落地後一小段時間也不算摔落傷害）。 */
    long landedAt = -1_000;
    /** 冷卻中的技能：技能代號 → 哪個 tick 才能再放。 */
    final Map<String, Long> cooldowns = new HashMap<>();
    /** 雙詠：等著重複的那一次（null = 沒有）。 */
    Echo echo;
    /** 複擊：還要自動再打的近戰攻擊（null = 沒有）。 */
    Repeat repeat;
    /** 戰吼的強化：接下來幾次攻擊更痛（null = 沒有）。 */
    Exert exert;
    /** 連珠（2026-10）：下一次弓／矛的投射物攻擊會重複（null = 沒有）。 */
    Barrage barrage;

    /** 雙詠要重複的一次施放。 */
    record Echo(long atTick, SkillBuild build, UUID target) {
    }

    /**
     * 複擊還要再打的幾下：下一下在哪個 tick（有小數，跟 {@link #readyAt} 一樣）、每一下隔幾 tick（攻擊間隔）、
     * 還剩幾下、用哪個技能（含輔助寶石、戰吼強化）、原本的目標。
     */
    record Repeat(double atTick, double gapTicks, int left, SkillBuild build, UUID target) {
        /** 打完這一下之後還要打的（沒了回傳 null）。 */
        Repeat next() {
            return left <= 1 ? null : new Repeat(atTick + gapTicks, gapTicks, left - 1, build, target);
        }

        /** 全部打完、可以做別的事的 tick：最後一下之後再隔一次攻擊間隔。 */
        double doneAt() {
            return atTick + gapTicks * left;
        }
    }

    /**
     * 連珠備好的那一次：哪個技能放的、重複幾次、每次重複的傷害倍率、隔幾 tick、到哪個 tick 失效；
     * 用掉之後技能的冷卻是幾秒＋被強化那一招攻擊時間的幾 %（{@link BarrageSpec#usedCooldownTicks}）、冷卻的倍率。
     */
    record Barrage(String skillId, String name, BarrageSpec spec, int repeats, long untilTick, double baseCooldown,
                   double cooldownPercent, double cooldownScale) {
        boolean active(final long now) {
            return repeats > 0 && now < untilTick;
        }
    }

    /** 戰吼之後的強化：還剩幾次、更多幾 %、到哪個 tick 失效。 */
    record Exert(int left, double more, long untilTick) {
        Exert consume() {
            return left <= 1 ? null : new Exert(left - 1, more, untilTick);
        }

        boolean active(final long now) {
            return left > 0 && now < untilTick;
        }
    }

    /** 這個技能還要等幾 tick（0 = 可以放）。 */
    long cooldownLeft(final String skillId, final long now) {
        final Long until = cooldowns.get(skillId);
        return until == null ? 0 : Math.max(0, until - now);
    }

    void startCooldown(final String skillId, final long now, final int ticks) {
        if (ticks > 0) {
            cooldowns.put(skillId, now + ticks);
        }
    }

    /** 一次跳躍重擊：起跳時間、預計的滯空時間、落地要打的技能（含輔助寶石）。 */
    record Leap(long startTick, int ticks, SkillBuild build, Location target) {
    }

    void queue(final int key, final UUID target, final long now) {
        queuedKey = key;
        queuedTarget = target;
        queuedAt = now;
    }

    void clearQueue() {
        queuedKey = -1;
        queuedTarget = null;
    }

    /** 換角色：清掉所有狀態。 */
    void reset() {
        readyAt = Double.NEGATIVE_INFINITY;
        leftHeld = false;
        clearQueue();
        leap = null;
        cooldowns.clear();
        echo = null;
        repeat = null;
        exert = null;
        barrage = null;
    }
}
