package tw.exilecore.ailment;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.UUID;

/**
 * 一隻怪物（或一個玩家）身上的異常狀態。時間都用伺服器 tick。純資料，不依賴 Bukkit。
 * <ul>
 *   <li>點燃：可以同時有好幾個，但只有傷害最高的那個在燒（PoE2）；它結束後下一個接著燒。流血一樣（只有最強的在流）。</li>
 *   <li>中毒：上限<b>每個來源各自算</b>（2026-10-19：組隊時不會互相擠掉）：同一個來源只有最強的 N 層生效（2026-10-26 照 PoE2：預設 1 層，
 *   弱的記著，等強的結束再接著毒；這次之前超過上限直接丟掉）。同一個來源最多記 {@link #MAX_POISONS} 個、整隻最多 {@link #MAX_POISONS_TOTAL} 個。</li>
 *   <li>易燃（2026-10-26 照 PoE2）：每一下火焰傷害疊一層，加起來是點燃的機率。</li>
 *   <li>冰緩、感電：只留最強的；比較弱的新效果不會蓋掉還沒結束的強效果。</li>
 *   <li>凍結：冰冷傷害累積到 100% 就凍結；凍結中不累積，傳奇怪凍結結束後還有一段時間不能累積。</li>
 *   <li>暈眩、電擊、釘地（PoE2）：跟凍結一樣是累積條（{@link Meter}）：滿了就觸發一段時間；2026-10-26 照 PoE2 觸發後門檻暫時提高
 *   （{@link Meter#thresholdMultiplier}），取代以前的免疫時間（免疫參數還在，呼叫端給 0）。</li>
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
        /** 2026-10-26 照 PoE2：觸發後門檻提高多少（小數）、從哪個 tick 開始、幾 tick 直線降回 0。 */
        private double after;
        private long afterFrom;
        private long afterTicks;

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

        /** 2026-10-26 照 PoE2：觸發後門檻暫時提高 bonus（5 = +500%），ticks 個 tick 內直線降回來。 */
        public void raiseThreshold(final double bonus, final long ticks, final long now) {
            if (bonus <= 0 || ticks <= 0) {
                return;
            }
            after = bonus;
            afterFrom = now;
            afterTicks = ticks;
        }

        /** 現在的門檻倍率（1 ＋ 剩下的提高量）。 */
        public double thresholdMultiplier(final long now) {
            if (after <= 0 || afterTicks <= 0 || now >= afterFrom + afterTicks) {
                return 1.0;
            }
            return 1.0 + after * (1.0 - (double) Math.max(0, now - afterFrom) / afterTicks);
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
            return buildup <= 0 && !active(now) && now >= immuneUntil && thresholdMultiplier(now) <= 1.0;
        }

        /** 2026-10-23：時間泡泡：觸發中的晚 extra tick 結束（免疫期跟著往後）。 */
        void extend(final long extra, final long now) {
            if (active(now)) {
                activeUntil += extra;
                immuneUntil += extra;
            }
        }

        void clear() {
            buildup = 0;
            activeUntil = 0;
            immuneUntil = 0;
            lastBuildupTick = 0;
            decayedTo = 0;
            after = 0;
            afterFrom = 0;
            afterTicks = 0;
        }
    }

    /** 最多記幾個點燃（太多時丟掉最弱的）。 */
    static final int MAX_IGNITES = 8;
    /** 最多記幾個流血。 */
    static final int MAX_BLEEDS = 8;
    /** 中毒疊加上限（設定檔可以改小）。 */
    public static final int MAX_POISONS = 20;
    /** 一隻身上全部來源加起來最多記幾個中毒（保險，避免一大隊人無限疊）。 */
    public static final int MAX_POISONS_TOTAL = 64;

    /**
     * 一個持續傷害（點燃、流血、中毒）。
     *
     * @param dps     每秒傷害
     * @param endTick 結束的 tick
     * @param source  造成的玩家（死時算他殺的；怪物造成的是 null）
     * @param chaos   2026-10-28 照 PoE2 Blackflame（黯炎）：這個點燃造成混沌傷害而非火焰傷害（吃混沌抗性、凋零）；這次之前點燃一律是火焰
     */
    public record Ignite(double dps, long endTick, UUID source, boolean aggravated, int limit, boolean chaos) {

        public Ignite(final double dps, final long endTick, final UUID source) {
            this(dps, endTick, source, false);
        }

        public Ignite(final double dps, final long endTick, final UUID source, final boolean aggravated) {
            this(dps, endTick, source, aggravated, 0);
        }

        public Ignite(final double dps, final long endTick, final UUID source, final boolean aggravated, final int limit) {
            this(dps, endTick, source, aggravated, limit, false);
        }

        /** 2026-10-28：這個點燃造成哪一種傷害（黯炎的混沌點燃，其他是火焰）。 */
        public tw.exilecore.stats.DamageType damageType() {
            return chaos ? tw.exilecore.stats.DamageType.CHAOS : tw.exilecore.stats.DamageType.FIRE;
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
    /** 混沌持續傷害的減益（2026-10，PoE2 的汲魂彈、疫潮）：技能代號 → 那個減益（同一種不疊加：留每秒傷害高的、時間取長的）。 */
    private final java.util.Map<String, Debuff> debuffs = new java.util.HashMap<>();
    private double chill;
    private long chillEnd;
    private double shock;
    private long shockEnd;
    private final Meter freeze = new Meter();
    private final Meter stun = new Meter();
    private final Meter electrocute = new Meter();
    private final Meter pin = new Meter();
    /** 2026-10-26 照 PoE2：易燃（每層 {百分比, 結束 tick}）。 */
    private final List<double[]> flammability = new ArrayList<>();

    // ---- 點燃 ----

    public void addIgnite(final double dps, final long endTick, final UUID source) {
        addIgnite(dps, endTick, source, false);
    }

    /** 2026-10-28：chaos＝造成混沌傷害的點燃（黯炎）。 */
    public void addIgnite(final double dps, final long endTick, final UUID source, final boolean chaos) {
        if (dps <= 0) {
            return;
        }
        ignites.add(new Ignite(dps, endTick, source, false, 0, chaos));
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

    /**
     * 2026-10-26（叛徒之矛）：把這個來源造成、還沒加劇的流血加劇（每秒傷害乘上移動時的倍率，之後不再看有沒有在動）。
     * 回傳加劇了幾條。
     */
    public int aggravateBleeds(final UUID source, final long now, final double movingMultiplier) {
        int count = 0;
        for (int i = 0; i < bleeds.size(); i++) {
            final Ignite bleed = bleeds.get(i);
            if (!bleed.aggravated() && bleed.endTick() > now && source != null && source.equals(bleed.source())) {
                bleeds.set(i, new Ignite(bleed.dps() * movingMultiplier, bleed.endTick(), bleed.source(), true));
                count++;
            }
        }
        return count;
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

    // 2026-10-27：生效層數記在每一個中毒自己身上（Ignite.limit）；這次之前整個來源共用「最後一次上毒時的設定」，
    // 傳染（瘟疫之使）或召喚物的毒氣用另一個上限蓋掉玩家自己的，1 層上限也會 20 層全部生效；那張表也不會清（每個來源留一筆）
    private static final UUID NO_SOURCE = new UUID(0, 0);

    /**
     * 加一個中毒。生效層數是<b>同一個來源</b>的（2026-10-19：以前全部來源共用，組隊時強的把弱的毒全部擠掉）：
     * 同一個來源只有最強的 maxStacks 層生效（2026-10-26 照 PoE2，弱的留著等強的結束）；同一個來源最多記 {@link #MAX_POISONS} 個、
     * 全部加起來超過 {@link #MAX_POISONS_TOTAL} 時丟掉最弱的。
     */
    public void addPoison(final double dps, final long endTick, final UUID source, final int maxStacks) {
        if (dps <= 0) {
            return;
        }
        poisonVersion++;
        poisons.add(new Ignite(dps, endTick, source, false, Math.max(1, Math.min(MAX_POISONS, maxStacks))));
        while (poisons.stream().filter(poison -> java.util.Objects.equals(poison.source(), source)).count() > MAX_POISONS) {
            poisons.remove(poisons.stream().filter(poison -> java.util.Objects.equals(poison.source(), source))
                    .min(Comparator.comparingDouble(Ignite::dps)).orElseThrow());
        }
        while (poisons.size() > MAX_POISONS_TOTAL) {
            poisons.remove(poisons.stream().min(Comparator.comparingDouble(Ignite::dps)).orElseThrow());
        }
    }

    /**
     * 中毒照來源分開的每秒傷害（2026-10-19）：每個人的毒算他自己的（傷害紀錄、掉落分份、Boss 上限、傷害顯示）。
     * 照每秒傷害由小到大排，最強的在最後（毒死的那一下比較會落在毒最強的人身上）。沒有玩家來源的 key 是 null。
     */
    public java.util.Map<UUID, Double> poisonBySource(final long now) {
        final java.util.Map<UUID, Double> totals = new java.util.HashMap<>();
        for (final Ignite poison : poisons(now)) {
            totals.merge(poison.source(), poison.dps(), Double::sum);
        }
        final java.util.Map<UUID, Double> sorted = new java.util.LinkedHashMap<>();
        totals.entrySet().stream().sorted(java.util.Map.Entry.comparingByValue())
                .forEach(share -> sorted.put(share.getKey(), share.getValue()));
        return sorted;
    }

    /**
     * 正在生效的中毒（丟掉結束的；每個來源只算最強的那幾層，2026-10-26 照 PoE2）。
     * 2026-10-27：同一個來源由強到弱排，第 i 強（從 0 數）的毒只有在 i 小於它自己的層數上限時生效。
     */
    public List<Ignite> poisons(final long now) {
        // 2026-10-27 效能：同一個 tick、中毒沒變過就直接回傳上次的結果（每秒傷害、異常圖示、條件檢查一個 tick 會讀好幾次；這次之前每次都分組、排序）
        if (now == poisonCacheTick && poisonVersion == poisonCacheVersion && poisonCache != null) {
            return poisonCache;
        }
        if (poisons.removeIf(poison -> poison.endTick() <= now)) {
            poisonVersion++;
        }
        final java.util.Map<UUID, List<Ignite>> bySource = new java.util.LinkedHashMap<>();
        for (final Ignite poison : poisons) {
            bySource.computeIfAbsent(poison.source() == null ? NO_SOURCE : poison.source(), key -> new ArrayList<>()).add(poison);
        }
        final List<Ignite> active = new ArrayList<>();
        for (final List<Ignite> list : bySource.values()) {
            list.sort(Comparator.comparingDouble(Ignite::dps).reversed());
            for (int i = 0; i < list.size(); i++) {
                if (i < Math.max(1, list.get(i).limit())) {
                    active.add(list.get(i));
                }
            }
        }
        poisonCache = List.copyOf(active);
        poisonCacheTick = now;
        poisonCacheVersion = poisonVersion;
        return poisonCache;
    }

    /** 2026-10-27：中毒清單改過幾次（快取 {@link #poisons} 的結果用）。 */
    private int poisonVersion;
    private int poisonCacheVersion = -1;
    private long poisonCacheTick = Long.MIN_VALUE;
    private List<Ignite> poisonCache;

    /** 所有中毒加起來每秒多少傷害（沒有是 0）。 */
    public double poisonDps(final long now) {
        double total = 0;
        for (final Ignite poison : poisons(now)) {
            total += poison.dps();
        }
        return total;
    }

    /** 最晚結束的中毒還剩幾 tick（沒有是 0；還沒輪到的弱毒也算）。 */
    public long poisonRemaining(final long now) {
        poisons(now);
        long end = 0;
        for (final Ignite poison : poisons) {
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

    // ---- 混沌減益（2026-10）----

    /**
     * 一個混沌持續傷害的減益。
     *
     * @param spread  死掉時會不會傳染給附近的敵人（疫潮）
     * @param spreads 已經傳了幾手（每傳一手傷害更多，有上限）
     * @param base    第一手的每秒傷害（傳染時照它算，不會越傳越大到沒上限）
     * @param length  2026-10-26：掛上時的完整持續時間（tick，已經算過技能持續時間的加成；傳染出去照這個重新算，0 = 舊的 5 秒）
     */
    public record Debuff(double dps, long endTick, UUID source, boolean spread, int spreads, double base, long length) {
        public Debuff(final double dps, final long endTick, final UUID source, final boolean spread, final int spreads, final double base) {
            this(dps, endTick, source, spread, spreads, base, 0);
        }
    }

    /** 加一個減益：同一種已經有的話，留每秒傷害高的那個、結束時間取比較晚的。 */
    public void addDebuff(final String key, final Debuff debuff) {
        if (debuff.dps() <= 0) {
            return;
        }
        final Debuff old = debuffs.get(key);
        if (old == null || debuff.dps() >= old.dps()) {
            debuffs.put(key, old == null || debuff.endTick() >= old.endTick() ? debuff
                    : new Debuff(debuff.dps(), old.endTick(), debuff.source(), debuff.spread(), debuff.spreads(), debuff.base(), debuff.length()));
        } else if (debuff.endTick() > old.endTick()) {
            debuffs.put(key, new Debuff(old.dps(), debuff.endTick(), old.source(), old.spread(), old.spreads(), old.base(), Math.max(old.length(), debuff.length())));
        }
    }

    /** 還在的減益（丟掉結束的）。 */
    public java.util.Map<String, Debuff> debuffs(final long now) {
        debuffs.values().removeIf(debuff -> debuff.endTick() <= now);
        return java.util.Map.copyOf(debuffs);
    }

    /** 所有減益加起來每秒多少傷害。 */
    public double debuffDps(final long now) {
        double total = 0;
        for (final Debuff debuff : debuffs(now).values()) {
            total += debuff.dps();
        }
        return total;
    }

    /** 傷害最高的減益的來源。 */
    public UUID debuffSource(final long now) {
        Debuff strongest = null;
        for (final Debuff debuff : debuffs(now).values()) {
            if (debuff.source() != null && (strongest == null || debuff.dps() > strongest.dps())) {
                strongest = debuff;
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

    /** 2026-10-26 照 PoE2：釘地的累積條（不能移動，照樣能攻擊；不算進 {@link #incapacitated}）。 */
    public Meter pin() {
        return pin;
    }

    /** 凍結的累積條。 */
    public Meter freezeMeter() {
        return freeze;
    }

    // ---- 易燃（2026-10-26 照 PoE2）----

    /** 加一層易燃（百分比點數），回傳身上全部易燃加起來（含這一層）。 */
    public double addFlammability(final double percent, final long endTick, final long now) {
        if (percent > 0) {
            flammability.add(new double[] {percent, endTick});
        }
        return flammability(now);
    }

    /** 身上的易燃加起來（百分比點數）。順便丟掉結束的。 */
    public double flammability(final long now) {
        flammability.removeIf(one -> one[1] <= now);
        double total = 0;
        for (final double[] one : flammability) {
            total += one[0];
        }
        return total;
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

    /** 2026-10-27 照 PoE2 Character.ot：最近被暈眩（輕暈或重暈）的時間（tick），給玩家「4 秒內每被暈眩一次輕暈門檻更多 50%」用。 */
    private final java.util.ArrayDeque<Long> recentStuns = new java.util.ArrayDeque<>();
    /** 記幾秒內的暈眩（x_ms_for_number_of_times_stunned_in_past_duration_from_ot 4000）。 */
    public static final long STUN_HISTORY_TICKS = 80;

    /** 記一次被暈眩。 */
    public void noteStunned(final long now) {
        recentStuns.addLast(now);
        while (recentStuns.size() > 16) {
            recentStuns.removeFirst();
        }
    }

    /** 最近 4 秒內被暈眩幾次。 */
    public int stunsRecently(final long now) {
        while (!recentStuns.isEmpty() && recentStuns.peekFirst() <= now - STUN_HISTORY_TICKS) {
            recentStuns.removeFirst();
        }
        return recentStuns.size();
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

    /** 2026-10-27 照 PoE2 Exploit Weakness（用掉完全破甲）：護甲恢復原狀。 */
    public void restoreArmour() {
        fullyBrokenUntil = 0;
        armourBroken = 0;
        armourUntil = 0;
    }

    // ---- 2026-10-23：怪物對玩家的癱瘓、阻礙、致盲、腐化之血（PoE2） ----

    /** 癱瘓、阻礙各慢多少（PoE2：30%）。 */
    public static final double HEX_SLOW = 0.3;
    /** 腐化之血最多幾層。 */
    public static final int CORRUPTED_BLOOD_MAX = 10;

    private long maimUntil;
    private long hinderUntil;
    private long blindUntil;
    private int corruptedStacks;
    private double corruptedDps;
    private long corruptedUntil;

    public void maim(final long until) {
        maimUntil = Math.max(maimUntil, until);
    }

    public void hinder(final long until) {
        hinderUntil = Math.max(hinderUntil, until);
    }

    public void blind(final long until) {
        blindUntil = Math.max(blindUntil, until);
    }

    /** 2026-10-24：玩家技能留下的減速地面（碎地 20%）：取目前與新的較大者，時間取較晚者。 */
    private double groundSlow;
    private long groundSlowUntil;

    public void groundSlow(final double slow, final long until) {
        if (until <= groundSlowUntil && slow <= groundSlow) {
            return;
        }
        groundSlow = Math.max(slow, groundSlowUntil > 0 ? groundSlow : 0);
        groundSlowUntil = Math.max(groundSlowUntil, until);
    }

    public long maimUntil() {
        return maimUntil;
    }

    public long hinderUntil() {
        return hinderUntil;
    }

    public long blindUntil() {
        return blindUntil;
    }

    /** 癱瘓、阻礙加起來慢多少（各 30%，相乘）。 */
    public double hexSlow(final long now) {
        final double maim = now < maimUntil ? HEX_SLOW : 0;
        final double hinder = now < hinderUntil ? HEX_SLOW : 0;
        final double ground = now < groundSlowUntil ? groundSlow : 0;
        return 1 - (1 - maim) * (1 - hinder) * (1 - ground);
    }

    /** 冰緩跟癱瘓、阻礙一起算的減速（相乘，最多 90%）。 */
    public static double combineSlow(final double chill, final double hex) {
        return Math.min(0.9, 1 - (1 - Math.max(0, chill)) * (1 - Math.max(0, hex)));
    }

    /**
     * 腐化之血加一層（PoE2：最多 10 層，每次打中全部重新計時）：每層每秒 dpsPerStack 物理傷害（取目前與新的較高者）。
     * 已經過期的從 0 層開始。
     */
    public void addCorruptedBlood(final double dpsPerStack, final long until, final long now) {
        if (now >= corruptedUntil) {
            corruptedStacks = 0;
            corruptedDps = 0;
        }
        corruptedStacks = Math.min(CORRUPTED_BLOOD_MAX, corruptedStacks + 1);
        corruptedDps = Math.max(corruptedDps, dpsPerStack);
        corruptedUntil = Math.max(corruptedUntil, until);
    }

    public int corruptedBloodStacks(final long now) {
        return now < corruptedUntil ? corruptedStacks : 0;
    }

    public double corruptedBloodDps(final long now) {
        return corruptedBloodStacks(now) * corruptedDps;
    }

    public long corruptedBloodUntil() {
        return corruptedUntil;
    }

    /**
     * 2026-10-23：拿掉某個玩家造成的持續傷害（點燃、流血、中毒、混沌減益）：那個玩家死了、下線、離開地圖。
     * 冰緩、感電、凍結這些沒有記來源（時間也短），照常結束。回傳有沒有拿掉東西。
     */
    public boolean removeSource(final UUID source) {
        if (source == null) {
            return false;
        }
        boolean changed = ignites.removeIf(dot -> source.equals(dot.source()));
        changed |= bleeds.removeIf(dot -> source.equals(dot.source()));
        changed |= poisons.removeIf(dot -> source.equals(dot.source()));
        poisonVersion++;
        changed |= debuffs.values().removeIf(debuff -> source.equals(debuff.source()));
        return changed;
    }

    /**
     * 2026-10-23：時間泡泡（怪物詞綴「時滯的」）：身上的減益晚 extra tick 結束（每 0.5 秒推 5 tick ＝ 消退慢一半）。
     * 點燃、流血、中毒、混沌減益、冰緩、感電、癱瘓、阻礙、致盲、腐化之血，以及凍結、暈眩、電擊（照 PoE2，定住的也延長）。
     */
    public void extendDebuffs(final long extra, final long now) {
        if (extra <= 0) {
            return;
        }
        poisonVersion++;
        for (final List<Ignite> list : List.of(ignites, bleeds, poisons)) {
            list.replaceAll(dot -> dot.endTick() > now ? new Ignite(dot.dps(), dot.endTick() + extra, dot.source(), dot.aggravated(), dot.limit(), dot.chaos()) : dot);
        }
        debuffs.replaceAll((key, debuff) -> debuff.endTick() > now
                ? new Debuff(debuff.dps(), debuff.endTick() + extra, debuff.source(), debuff.spread(), debuff.spreads(), debuff.base(), debuff.length()) : debuff);
        if (chillEnd > now) {
            chillEnd += extra;
        }
        if (shockEnd > now) {
            shockEnd += extra;
        }
        if (maimUntil > now) {
            maimUntil += extra;
        }
        if (hinderUntil > now) {
            hinderUntil += extra;
        }
        if (blindUntil > now) {
            blindUntil += extra;
        }
        if (corruptedUntil > now) {
            corruptedUntil += extra;
        }
        // 2026-10-23：照 PoE2，凍結、暈眩、電擊也延長
        freeze.extend(extra, now);
        stun.extend(extra, now);
        electrocute.extend(extra, now);
        pin.extend(extra, now);
        if (lightStunUntil > now) {
            lightStunUntil += extra;
        }
    }

    // ---- 全部 ----

    /** 還有沒有要追蹤的東西（沒有就可以丟掉這份狀態）。 */
    public boolean isEmpty(final long now) {
        return ignite(now) == null && bleed(now) == null && poisons(now).isEmpty() && debuffs(now).isEmpty() && chill(now) <= 0 && shock(now) <= 0
                && freeze.idle(now) && stun.idle(now) && electrocute.idle(now) && pin.idle(now) && flammability(now) <= 0
                && lightStunUntil <= now && armourBroken(now) <= 0 && !fullyBroken(now) && stunsRecently(now) == 0
                && maimUntil <= now && hinderUntil <= now && groundSlowUntil <= now && blindUntil <= now && corruptedBloodStacks(now) == 0;
    }

    /** 2026-10-26：清掉所有緩速（冰緩、癱瘓、阻礙、減速地面；PoE2 銀護符）。 */
    public void clearSlows() {
        chill = 0;
        chillEnd = 0;
        maimUntil = 0;
        hinderUntil = 0;
        groundSlow = 0;
        groundSlowUntil = 0;
    }

    /** 只清掉一種異常（護符解除，2026-10）。 */
    public void cure(final AilmentType type) {
        switch (type) {
            case IGNITE -> {
                ignites.clear();
                flammability.clear();
            }
            case BLEED -> {
                bleeds.clear();
                corruptedStacks = 0; // 2026-10-23：腐化之血跟流血一起解
                corruptedUntil = 0;
            }
            case POISON -> {
                poisons.clear();
                poisonVersion++;
            }
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
        poisonVersion++;
        flammability.clear();
        pin.clear();
        debuffs.clear();
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
