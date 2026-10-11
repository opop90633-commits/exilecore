package tw.exilecore.ailment;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.UUID;

/**
 * 一隻怪物（或一個玩家）身上的異常狀態。時間都用伺服器 tick。純資料，不依賴 Bukkit。
 * <ul>
 *   <li>點燃：可以同時有好幾個，但只有傷害最高的那個在燒（PoE2）；它結束後下一個接著燒。流血一樣（只有最強的在流）。</li>
 *   <li>中毒：全部一起算（可以疊加），最多 {@link #MAX_POISONS} 個，太多時丟掉最弱的。</li>
 *   <li>冰緩、感電：只留最強的；比較弱的新效果不會蓋掉還沒結束的強效果。</li>
 *   <li>凍結：冰冷傷害累積到 100% 就凍結；凍結中不累積，傳奇怪凍結結束後還有一段時間不能累積。</li>
 *   <li>暈眩、電擊（PoE2）：跟凍結一樣是累積條（{@link Meter}）：滿了就暈眩／電擊一段時間，之後一段時間免疫。</li>
 * </ul>
 */
public final class AilmentState {

    /**
     * 一條累積條（凍結、暈眩、電擊共用）：打中累積，一段時間沒再被打到就消退；滿了觸發一段時間，結束後一段時間免疫。
     * 時間都用伺服器 tick。
     */
    public static final class Meter {
        private double buildup;
        private long lastBuildupTick;
        private long decayedTo;
        private long activeUntil;
        private long immuneUntil;

        /**
         * 加累積（先把該消退的消退掉）。觸發中或免疫期間不累積。
         *
         * @param amount     這一擊的累積（1 = 100%）
         * @param delayTicks 最後一次被打到後幾 tick 開始消退
         * @param perTick    每 tick 消退多少
         * @return 累積滿了（呼叫端決定要觸發還是被避免掉）
         */
        public boolean add(final double amount, final long now, final long delayTicks, final double perTick) {
            decay(now, delayTicks, perTick);
            if (amount <= 0 || active(now) || now < immuneUntil) {
                return false;
            }
            buildup += amount;
            lastBuildupTick = now;
            decayedTo = now;
            return buildup >= 1.0;
        }

        /** 消退：最後一次累積後 delayTicks 之後，每 tick 少 perTick。 */
        public void decay(final long now, final long delayTicks, final double perTick) {
            if (buildup <= 0) {
                decayedTo = now;
                return;
            }
            final long from = Math.max(lastBuildupTick + delayTicks, decayedTo);
            if (now > from) {
                buildup = Math.max(0, buildup - (now - from) * perTick);
            }
            decayedTo = Math.max(decayedTo, now);
        }

        /** 觸發 ticks 個 tick；結束後 immunityTicks 個 tick 不能再累積。累積歸零。 */
        public void trigger(final long now, final long ticks, final long immunityTicks) {
            activeUntil = Math.max(activeUntil, now + ticks);
            immuneUntil = Math.max(immuneUntil, activeUntil + immunityTicks);
            buildup = 0;
        }

        /** 累積滿了但被避免掉：歸零。 */
        public void reset() {
            buildup = 0;
        }

        public boolean active(final long now) {
            return now < activeUntil;
        }

        public long activeUntil() {
            return activeUntil;
        }

        /** 目前的累積（1 = 100%）。 */
        public double buildup() {
            return buildup;
        }

        /** 沒有任何要追蹤的東西。 */
        public boolean idle(final long now) {
            return buildup <= 0 && !active(now) && now >= immuneUntil;
        }

        void clear() {
            buildup = 0;
            activeUntil = 0;
            immuneUntil = 0;
            lastBuildupTick = 0;
            decayedTo = 0;
        }
    }

    /** 最多記幾個點燃（太多時丟掉最弱的）。 */
    static final int MAX_IGNITES = 8;
    /** 最多記幾個流血。 */
    static final int MAX_BLEEDS = 8;
    /** 中毒疊加上限（設定檔可以改小）。 */
    public static final int MAX_POISONS = 20;

    /**
     * 一個持續傷害（點燃、流血、中毒）。
     *
     * @param dps     每秒傷害
     * @param endTick 結束的 tick
     * @param source  造成的玩家（死時算他殺的；怪物造成的是 null）
     */
    public record Ignite(double dps, long endTick, UUID source, boolean aggravated) {

        public Ignite(final double dps, final long endTick, final UUID source) {
            this(dps, endTick, source, false);
        }
    }

    /**
     * 流血這一跳要乘幾倍：目標在移動就乘 movingMultiplier（PoE2）。加劇的流血（猩紅突襲）造成的時候已經照
     * 「當作在移動」算進每秒傷害了，這裡不能再乘一次（2026-10 以前對移動中的怪物會乘兩次）。
     */
    public static double bleedFactor(final Ignite bleed, final boolean moving, final double movingMultiplier) {
        return moving && !bleed.aggravated() ? movingMultiplier : 1.0;
    }

    private final List<Ignite> ignites = new ArrayList<>();
    private final List<Ignite> bleeds = new ArrayList<>();
    private final List<Ignite> poisons = new ArrayList<>();
    private double chill;
    private long chillEnd;
    private double shock;
    private long shockEnd;
    private final Meter freeze = new Meter();
    private final Meter stun = new Meter();
    private final Meter electrocute = new Meter();

    // ---- 點燃 ----

    public void addIgnite(final double dps, final long endTick, final UUID source) {
        if (dps <= 0) {
            return;
        }
        ignites.add(new Ignite(dps, endTick, source));
        if (ignites.size() > MAX_IGNITES) {
            ignites.remove(ignites.stream().min(Comparator.comparingDouble(Ignite::dps)).orElseThrow());
        }
    }

    /** 現在在燒的點燃（最強的那個；沒有回傳 null）。順便丟掉結束的。 */
    public Ignite ignite(final long now) {
        ignites.removeIf(ignite -> ignite.endTick() <= now);
        Ignite strongest = null;
        for (final Ignite ignite : ignites) {
            if (strongest == null || ignite.dps() > strongest.dps()) {
                strongest = ignite;
            }
        }
        return strongest;
    }

    // ---- 流血 ----

    public void addBleed(final double dps, final long endTick, final UUID source) {
        addBleed(dps, endTick, source, false);
    }

    /** @param aggravated 加劇的流血：每秒傷害已經含「當作在移動」的倍率 */
    public void addBleed(final double dps, final long endTick, final UUID source, final boolean aggravated) {
        if (dps <= 0) {
            return;
        }
        bleeds.add(new Ignite(dps, endTick, source, aggravated));
        if (bleeds.size() > MAX_BLEEDS) {
            bleeds.remove(bleeds.stream().min(Comparator.comparingDouble(Ignite::dps)).orElseThrow());
        }
    }

    /** 現在在流的流血（最強的那個；沒有回傳 null）。順便丟掉結束的。 */
    public Ignite bleed(final long now) {
        bleeds.removeIf(bleed -> bleed.endTick() <= now);
        Ignite strongest = null;
        for (final Ignite bleed : bleeds) {
            if (strongest == null || bleed.dps() > strongest.dps()) {
                strongest = bleed;
            }
        }
        return strongest;
    }

    // ---- 中毒 ----

    /** 加一個中毒；超過上限時丟掉最弱的。 */
    public void addPoison(final double dps, final long endTick, final UUID source, final int maxStacks) {
        if (dps <= 0) {
            return;
        }
        poisons.add(new Ignite(dps, endTick, source));
        final int cap = Math.max(1, Math.min(MAX_POISONS, maxStacks));
        while (poisons.size() > cap) {
            poisons.remove(poisons.stream().min(Comparator.comparingDouble(Ignite::dps)).orElseThrow());
        }
    }

    /** 還在的中毒（丟掉結束的）。 */
    public List<Ignite> poisons(final long now) {
        poisons.removeIf(poison -> poison.endTick() <= now);
        return List.copyOf(poisons);
    }

    /** 所有中毒加起來每秒多少傷害（沒有是 0）。 */
    public double poisonDps(final long now) {
        double total = 0;
        for (final Ignite poison : poisons(now)) {
            total += poison.dps();
        }
        return total;
    }

    /** 最晚結束的中毒還剩幾 tick（沒有是 0）。 */
    public long poisonRemaining(final long now) {
        long end = 0;
        for (final Ignite poison : poisons(now)) {
            end = Math.max(end, poison.endTick());
        }
        return Math.max(0, end - now);
    }

    /** 傷害最高的那個中毒的來源（毒死時算他殺的；沒有玩家來源回傳 null）。 */
    public UUID poisonSource(final long now) {
        Ignite strongest = null;
        for (final Ignite poison : poisons(now)) {
            if (poison.source() != null && (strongest == null || poison.dps() > strongest.dps())) {
                strongest = poison;
            }
        }
        return strongest == null ? null : strongest.source();
    }

    // ---- 冰緩 ----

    /** 冰緩：比現在的強（或一樣）才換上去。回傳是否換了。 */
    public boolean applyChill(final double magnitude, final long endTick, final long now) {
        if (magnitude <= 0) {
            return false;
        }
        final double current = chill(now);
        if (magnitude < current) {
            return false;
        }
        chill = magnitude;
        chillEnd = magnitude == current ? Math.max(chillEnd, endTick) : endTick;
        return true;
    }

    /** 現在的冰緩效果（小數；沒有是 0）。 */
    public double chill(final long now) {
        return now < chillEnd ? chill : 0;
    }

    public long chillEnd() {
        return chillEnd;
    }

    // ---- 感電 ----

    public boolean applyShock(final double effect, final long endTick, final long now) {
        if (effect <= 0) {
            return false;
        }
        final double current = shock(now);
        if (effect < current) {
            return false;
        }
        shock = effect;
        shockEnd = effect == current ? Math.max(shockEnd, endTick) : endTick;
        return true;
    }

    /** 現在的感電效果（小數，受到的傷害增加多少；沒有是 0）。 */
    public double shock(final long now) {
        return now < shockEnd ? shock : 0;
    }

    public long shockEnd() {
        return shockEnd;
    }

    // ---- 凍結（舊的呼叫法，轉給累積條）----

    /**
     * 加凍結累積（先把該消退的消退掉）。凍結中或還在凍結免疫期間不累積。
     *
     * @param amount     這一擊的累積（1 = 100%）
     * @param delayTicks 最後一次被打到後幾 tick 開始消退
     * @param perTick    每 tick 消退多少
     * @return 累積滿了（呼叫端決定要凍結還是被避免掉）
     */
    public boolean addBuildup(final double amount, final long now, final long delayTicks, final double perTick) {
        return freeze.add(amount, now, delayTicks, perTick);
    }

    /** 消退：最後一次累積後 delayTicks 之後，每 tick 少 perTick。 */
    public void decay(final long now, final long delayTicks, final double perTick) {
        freeze.decay(now, delayTicks, perTick);
    }

    /** 凍結 ticks 個 tick；結束後 immunityTicks 個 tick 不能再累積。累積歸零。 */
    public void freeze(final long now, final long ticks, final long immunityTicks) {
        freeze.trigger(now, ticks, immunityTicks);
    }

    /** 累積滿了但被避免掉：歸零。 */
    public void resetBuildup() {
        freeze.reset();
    }

    public boolean frozen(final long now) {
        return freeze.active(now);
    }

    public long frozenUntil() {
        return freeze.activeUntil();
    }

    /** 目前的凍結累積（1 = 100%）。 */
    public double buildup() {
        return freeze.buildup();
    }

    // ---- 暈眩、電擊（PoE2）----

    public Meter stun() {
        return stun;
    }

    public Meter electrocute() {
        return electrocute;
    }

    /** 不能動也不能攻擊、施放：凍結、暈眩或電擊中。 */
    public boolean incapacitated(final long now) {
        return freeze.active(now) || stun.active(now) || electrocute.active(now) || lightStunUntil > now;
    }

    // ---- 輕暈、破甲（2026-10 PoE2）----

    private long lightStunUntil;
    private double armourBroken;
    private long armourUntil;
    private long fullyBrokenUntil;

    /** 輕暈：打斷一下（不累積、沒有免疫）。 */
    public void lightStun(final long until) {
        lightStunUntil = Math.max(lightStunUntil, until);
    }

    public boolean lightStunned(final long now) {
        return lightStunUntil > now;
    }

    /**
     * 破甲：扣掉 amount 的護甲（最多扣到 0）；扣到 0 就是「完全破甲」fullyTicks 個 tick（期間不再累積）。
     * 部分破甲在最後一次破甲後 durationTicks 個 tick 恢復。回傳這一下是不是剛好完全破甲。
     */
    public boolean breakArmour(final double amount, final double armour, final long now, final long durationTicks) {
        if (amount <= 0 || armour <= 0 || fullyBroken(now)) {
            return false;
        }
        if (now >= armourUntil) {
            armourBroken = 0;
        }
        armourBroken = Math.min(armour, armourBroken + amount);
        armourUntil = now + durationTicks;
        if (armourBroken >= armour - 1e-6) {
            fullyBrokenUntil = now + durationTicks;
            armourBroken = 0;
            armourUntil = 0;
            return true;
        }
        return false;
    }

    /** 目前被破掉的護甲（完全破甲時看 {@link #fullyBroken}）。 */
    public double armourBroken(final long now) {
        return now < armourUntil ? armourBroken : 0;
    }

    public boolean fullyBroken(final long now) {
        return now < fullyBrokenUntil;
    }

    // ---- 全部 ----

    /** 還有沒有要追蹤的東西（沒有就可以丟掉這份狀態）。 */
    public boolean isEmpty(final long now) {
        return ignite(now) == null && bleed(now) == null && poisons(now).isEmpty() && chill(now) <= 0 && shock(now) <= 0
                && freeze.idle(now) && stun.idle(now) && electrocute.idle(now)
                && lightStunUntil <= now && armourBroken(now) <= 0 && !fullyBroken(now);
    }

    /** 只清掉一種異常（護符解除，2026-10）。 */
    public void cure(final AilmentType type) {
        switch (type) {
            case IGNITE -> ignites.clear();
            case BLEED -> bleeds.clear();
            case POISON -> poisons.clear();
            case CHILL -> {
                chill = 0;
                chillEnd = 0;
            }
            case SHOCK -> {
                shock = 0;
                shockEnd = 0;
            }
            case FREEZE -> freeze.clear();
            case STUN -> {
                stun.clear();
                lightStunUntil = 0;
            }
            case ELECTROCUTE -> electrocute.clear();
        }
    }

    /** 清掉全部（死亡、登出、換角色）。 */
    public void clear() {
        ignites.clear();
        bleeds.clear();
        poisons.clear();
        chill = 0;
        chillEnd = 0;
        shock = 0;
        shockEnd = 0;
        freeze.clear();
        stun.clear();
        electrocute.clear();
        lightStunUntil = 0;
        armourBroken = 0;
        armourUntil = 0;
        fullyBrokenUntil = 0;
    }
}
