package tw.exilecore.stats;

import java.util.EnumMap;
import java.util.Map;
import java.util.Set;

/**
 * 有效生命池（EHP）與最多吃得下的一下（Maximum Hit Taken）。
 * <p>
 * 2026-11-02 照站長（PoB2 CalcDefence 的 buildDefenceEstimations、numberOfHitsToDie、reducePoolsByDamage、MaximumHitTaken）；
 * 這次之前網頁的「有效生命」是五種傷害各自「最多吃得下一下」的平均（免疫、無限大的不算），跟 PoB2 的 EHP 不是同一個東西。
 * <p>
 * 減傷與扣資源全部用遊戲同一套純函式（玩家實際受到的傷害也是這些算的）：
 * <ul>
 *   <li>減免：{@link DamageCalculator#mitigate}（護甲照這一下的大小、抗性與上限、穿透、混沌免疫、承受的傷害視為另一種、受到的傷害增減）</li>
 *   <li>擊中前：{@link DamageCalculator#chanceToBeHit}（閃避）、{@link DamageCalculator#blockChance}／{@link DamageCalculator#blockedTaken}（格擋）、
 *       {@link DamageCalculator#deflectChanceAgainst}／{@link DamageCalculator#deflectPrevented}（偏斜）、{@link DamageCalculator#critChanceTaken}（被暴擊）</li>
 *   <li>扣資源：{@link DamageCalculator#shieldSplit}（能量護盾、跳過護盾、混沌對護盾兩倍）→ {@link IncomingDamage#guardAbsorb}（守護）→
 *       {@link IncomingDamage#split}（心靈昇華：扣生命的部分有 x% 改扣魔力）→ 生命扣到 0 就死（跟 CombatListener.applyToPlayer 同一個順序）</li>
 * </ul>
 * PoB2 的做法：
 * <ol>
 *   <li>敵人的一下 = 各類型的原始傷害 × 敵人的暴擊期望（1 + 暴擊率 × 暴擊加成）；</li>
 *   <li>每一下受到的 = 減免後 × 格擋、偏斜的期望（PoB2 把這些當成「每一下少受多少」，DamageIn × blockEffect × deflectMulti）；</li>
 *   <li>從滿資源一直打到死，最後一下只算用到的那一部分（PoB2 扣掉 overkill）＝ 會被打到的下數；</li>
 *   <li>除以被打中的機率（閃避）＝ 總共要幾下（TotalNumberOfHits）；</li>
 *   <li>EHP ＝ 總下數 × 敵人一下的原始傷害（暴擊前，totalEnemyDamageIn）。</li>
 * </ol>
 * 純函式，不依賴 Bukkit。
 */
public final class EffectiveHitPool {

    private EffectiveHitPool() {
    }

    /** 算到這麼多下（或敵人總傷害超過 {@link #MAX_DAMAGE}）還沒死：當成無限大（PoB2 ehpCalcMaxDamage 1 億）。 */
    public static final double MAX_DAMAGE = 1e8;
    /** 怪物暴擊的基礎暴擊加成（%）：PoE2 monsterConstants base_critical_hit_damage_bonus 30（MonsterSheets 的 crit.multi 130）。 */
    public static final double MONSTER_CRIT_BONUS = 30;
    /** 怪物的基礎暴擊率（%）：monsters.yml defence.crit_chance、PoB2 enemyCritChance 的預設。 */
    public static final double MONSTER_CRIT_CHANCE = 5;
    /** 敵人的一下有多少是攻擊（格擋只擋攻擊）：PoB2 的「Average」是近戰、投射物、法術、法術投射物四種平均＝一半攻擊。 */
    public static final double AVERAGE_ATTACK_SHARE = 0.5;

    /**
     * 敵人的一下。
     *
     * @param damage      各類型的原始傷害（暴擊前、減免前）
     * @param penetration 元素穿透（百分比點數；PoB2 巔峰 Boss 3、超級巔峰 8）
     * @param accuracy    命中（閃避、偏斜用）
     * @param critChance  暴擊率（0～1，防禦方的修飾器之前）
     * @param critBonus   暴擊加成（百分比點數，怪物 30）
     * @param attackShare 這一下有幾成是攻擊（0～1；格擋只擋攻擊）
     */
    public record Enemy(DamagePacket damage, double penetration, double accuracy, double critChance, double critBonus, double attackShare) {
        public Enemy {
            damage = new DamagePacket(damage);
        }

        /** 原始傷害的總和（暴擊前）。 */
        public double total() {
            return damage.total();
        }
    }

    /**
     * 滿資源。
     *
     * @param life  生命（沒被保留的；生命扣到 0 就死）
     * @param es    能量護盾
     * @param mana  魔力（心靈昇華用）
     * @param guard 守護（護符效果期間才有；網頁的角色沒有）
     */
    public record Pools(double life, double es, double mana, double guard) {
    }

    /**
     * 結果。
     *
     * @param ehp           有效生命池（能吃下的總原始傷害；無限大＝打不死）
     * @param hits          平均要被打幾下才死（含閃避）
     * @param damagingHits  真的打中的下數（不含閃避；格擋、偏斜已經折算進每一下）
     * @param hitChance     被打中的機率（閃避）
     * @param damageChance  打中的那一下平均受到幾成（格擋、偏斜）
     * @param critEffect    敵人的暴擊期望倍率
     * @param rawPerHit     敵人一下的原始傷害（暴擊前）
     * @param takenPerHit   一下（暴擊期望後）減免後的傷害，格擋、偏斜之前
     * @param maxHit        每一種傷害最多吃得下的一下（原始傷害，暴擊前；NaN＝免疫、無限大＝打不死）
     */
    public record Result(double ehp, double hits, double damagingHits, double hitChance, double damageChance, double critEffect,
                         double rawPerHit, DamagePacket takenPerHit, Map<DamageType, Double> maxHit) {
    }

    // ---- 敵人 ----

    /** 敵人的屬性表（只用到暴擊加成與元素穿透；拿去給遊戲的 mitigate、critMultiplier）。 */
    public static StatSheet enemySheet(final Enemy enemy) {
        final StatSheet sheet = new StatSheet().setBase(StatKeys.CRIT_MULTI, 100 + Math.max(0, enemy.critBonus()));
        if (enemy.penetration() > 0) {
            sheet.add(new Modifier(StatKeys.PENETRATION, ModType.FLAT, enemy.penetration(), Set.of(Tags.ELEMENTAL), "敵人"));
        }
        return sheet;
    }

    /** 敵人的暴擊期望倍率：1 + 被暴擊率（含防禦方的修飾器）×（暴擊倍率 − 1，含「擊中你的暴擊傷害加成減少」）。 */
    public static double critEffect(final StatSheet defender, final Enemy enemy) {
        final double chance = DamageCalculator.critChanceTaken(Math.max(0, Math.min(1, enemy.critChance())), defender);
        if (chance <= 0) {
            return 1;
        }
        return 1 + chance * (DamageCalculator.critMultiplier(enemySheet(enemy), Set.of(), defender) - 1);
    }

    // ---- 減免 ----

    /**
     * 一下原始傷害減免後受到多少（遊戲的 {@link DamageCalculator#mitigate}，護甲照這一下的大小）。
     * 擲骰的部分取期望：「x% 機率用 200% 護甲防禦」照機率平均兩種結果；「物理有 x% 改成隨機一種元素」平均分給三種元素。
     */
    public static DamagePacket taken(final StatSheet defender, final DamagePacket raw, final StatSheet enemySheet) {
        DamagePacket incoming = raw;
        final double physAsElement = DamageCalculator.physTakenAsElement(defender);
        if (physAsElement > 0 && raw.get(DamageType.PHYSICAL) > 0) {
            incoming = new DamagePacket(raw);
            final double moved = raw.get(DamageType.PHYSICAL) * physAsElement;
            incoming.set(DamageType.PHYSICAL, raw.get(DamageType.PHYSICAL) - moved);
            for (final DamageType element : new DamageType[] {DamageType.FIRE, DamageType.COLD, DamageType.LIGHTNING}) {
                incoming.add(element, moved / 3);
            }
        }
        final double doubled = Math.max(0, Math.min(1, DamageCalculator.doubleArmourChance(defender)));
        final DamagePacket normal = DamageCalculator.mitigate(incoming, mitigator(defender, false), enemySheet, Set.of());
        if (doubled <= 0) {
            return normal;
        }
        final DamagePacket twice = DamageCalculator.mitigate(incoming, mitigator(defender, true), enemySheet, Set.of());
        final DamagePacket out = new DamagePacket();
        for (final DamageType type : DamageType.values()) {
            out.set(type, normal.get(type) * (1 - doubled) + twice.get(type) * doubled);
        }
        return out;
    }

    private static StatSheet mitigator(final StatSheet defender, final boolean doubled) {
        final java.util.List<Modifier> extra = DamageCalculator.armourDefence(defender, doubled, Set.of());
        return extra.isEmpty() ? defender : defender.overlay(extra);
    }

    // ---- 扣資源 ----

    /** 資源的現況：生命、護盾、魔力、守護。 */
    private static final int LIFE = 0;
    private static final int ES = 1;
    private static final int MANA = 2;
    private static final int GUARD = 3;

    /**
     * 受到一下（已經減免）之後的資源（純函式；跟 CombatListener.applyToPlayer 同一個順序）：
     * 護盾（{@link DamageCalculator#shieldSplit}）→ 守護（{@link IncomingDamage#guardAbsorb}）→ 心靈昇華（{@link IncomingDamage#split}）→ 生命。
     * 回傳 {生命, 護盾, 魔力, 守護}；生命 ≤ 0 就是死了。
     */
    public static double[] afterHit(final StatSheet defender, final DamagePacket dealt, final double[] pools) {
        final double[] split = DamageCalculator.shieldSplit(dealt, defender, pools[ES]);
        double esTaken = split[0];
        double lifeTaken = split[1];
        double guard = pools[GUARD];
        if (guard > 0 && esTaken + lifeTaken > 0) {
            final double[] guarded = IncomingDamage.guardAbsorb(esTaken, lifeTaken, guard);
            esTaken -= guarded[0];
            lifeTaken -= guarded[1];
            guard -= guarded[0] + guarded[1];
        }
        final IncomingDamage incoming = IncomingDamage.split(esTaken, lifeTaken, Math.max(0, pools[MANA]),
                defender.flat(StatKeys.DAMAGE_FROM_MANA, Set.of()), 0);
        return new double[] {pools[LIFE] - incoming.life(), pools[ES] - incoming.es(), pools[MANA] - incoming.mana(), guard};
    }

    private static double[] scaled(final DamagePacket dealt, final double factor, final StatSheet defender, final double[] pools) {
        final DamagePacket part = new DamagePacket(dealt);
        part.scale(factor);
        return afterHit(defender, part, pools);
    }

    /**
     * 從滿資源開始，每一下受到 dealt，平均要幾下才死（PoB2 numberOfHitsToDie）：最後一下只算剛好打死的那一部分（扣掉 overkill）。
     * 一樣的幾下（資源都還夠、不會變的）一次跳過，不用一下一下算。打不死回傳無限大。
     */
    public static double hitsToDie(final StatSheet defender, final DamagePacket dealt, final Pools pools) {
        if (dealt.total() <= 0) {
            return Double.POSITIVE_INFINITY;
        }
        double[] state = {pools.life(), pools.es(), pools.mana(), pools.guard()};
        if (state[LIFE] <= 0) {
            return 0;
        }
        double hits = 0;
        for (int step = 0; step < 10_000; step++) {
            final double[] next = afterHit(defender, dealt, state);
            if (next[LIFE] <= 0) {
                // 最後一下：二分找剛好打死的比例（扣資源對這一下的大小是分段線性的）
                double low = 0;
                double high = 1;
                for (int i = 0; i < 50; i++) {
                    final double mid = (low + high) / 2;
                    if (scaled(dealt, mid, defender, state)[LIFE] > 0) {
                        low = mid;
                    } else {
                        high = mid;
                    }
                }
                return hits + high;
            }
            // 每一種資源這一下扣多少；在扣到不夠之前每一下都一樣
            long same = Long.MAX_VALUE;
            for (int r = 0; r < state.length; r++) {
                final double used = state[r] - next[r];
                if (used > 1e-12) {
                    final double n = r == LIFE ? Math.ceil(state[r] / used) - 1 : Math.floor(state[r] / used);
                    same = Math.min(same, (long) Math.max(1, n));
                }
            }
            if (same == Long.MAX_VALUE) {
                return Double.POSITIVE_INFINITY; // 什麼都沒扣
            }
            // 跳過的下數裡最後一下要先檢查（浮點誤差）：從保守的開始
            final long jump = Math.max(1, same - 1);
            for (int r = 0; r < state.length; r++) {
                state[r] -= (state[r] - next[r]) * jump;
            }
            hits += jump;
            if (hits > MAX_DAMAGE) {
                return Double.POSITIVE_INFINITY;
            }
        }
        return Double.POSITIVE_INFINITY;
    }

    // ---- 最多吃得下的一下 ----

    /**
     * 這一種傷害從滿資源最多吃得下多大的一下（原始傷害，暴擊前；PoB2 MaximumHitTaken）：二分搜尋，因為護甲對大的一下比較沒效。
     * 免疫回傳 NaN，大到 {@link #MAX_DAMAGE} 還打不死回傳無限大。
     */
    public static double maxHit(final StatSheet defender, final DamageType type, final Pools pools, final StatSheet enemySheet) {
        final StatSheet enemy = enemySheet == null ? new StatSheet() : enemySheet;
        final double[] full = {pools.life(), pools.es(), pools.mana(), pools.guard()};
        if (full[LIFE] <= 0) {
            return 0;
        }
        if (taken(defender, single(type, MAX_DAMAGE), enemy).total() <= 0) {
            return Double.NaN;
        }
        double low = 0;
        double high = Math.max(1, full[LIFE] + full[ES] + full[MANA] + full[GUARD]);
        while (alive(defender, type, high, full, enemy)) {
            low = high;
            high *= 2;
            if (high > MAX_DAMAGE) {
                return Double.POSITIVE_INFINITY;
            }
        }
        for (int i = 0; i < 60 && high - low > 1e-6 * high; i++) {
            final double mid = (low + high) / 2;
            if (alive(defender, type, mid, full, enemy)) {
                low = mid;
            } else {
                high = mid;
            }
        }
        return low;
    }

    private static boolean alive(final StatSheet defender, final DamageType type, final double raw, final double[] full, final StatSheet enemy) {
        return afterHit(defender, taken(defender, single(type, raw), enemy), full)[LIFE] > 0;
    }

    private static DamagePacket single(final DamageType type, final double amount) {
        return new DamagePacket().set(type, amount);
    }

    // ---- 全部 ----

    /** 被打中的機率（閃避；遊戲裡攻擊與法術都閃得掉）。 */
    public static double hitChance(final StatSheet defender, final Enemy enemy) {
        return Math.max(0, Math.min(1, DamageCalculator.chanceToBeHit(defender, enemy.accuracy())));
    }

    /** 打中的那一下平均受到幾成：格擋（只擋攻擊的那一份；龜殼護符擋下的還受一部分）× 偏斜（少受 40% 起）。 */
    public static double damageChance(final StatSheet defender, final Enemy enemy) {
        final double block = DamageCalculator.blockChance(defender) * Math.max(0, Math.min(1, enemy.attackShare()));
        final double blockEffect = 1 - Math.max(0, Math.min(1, block)) * (1 - DamageCalculator.blockedTaken(defender));
        final double deflect = Math.max(0, Math.min(1, DamageCalculator.deflectChanceAgainst(defender, enemy.accuracy())));
        final double deflectEffect = 1 - deflect * DamageCalculator.deflectPrevented(defender, false);
        return Math.max(0, blockEffect * deflectEffect);
    }

    /** 算 EHP、要被打幾下、每一種傷害最多吃得下的一下（PoB2 buildDefenceEstimations 的 EHP 那一段）。 */
    public static Result compute(final StatSheet defender, final Pools pools, final Enemy enemy) {
        return compute(defender, pools, enemy, true);
    }

    /** 同上；withMaxHit = false 不算每一種的最多吃得下一下（熱圖、滑到節點只比 EHP，省時間；maxHit 是空的）。 */
    public static Result compute(final StatSheet defender, final Pools pools, final Enemy enemy, final boolean withMaxHit) {
        final StatSheet enemySheet = enemySheet(enemy);
        final double crit = critEffect(defender, enemy);
        final DamagePacket raw = new DamagePacket(enemy.damage());
        raw.scale(crit);
        final DamagePacket taken = taken(defender, raw, enemySheet);
        final double hitChance = hitChance(defender, enemy);
        final double damageChance = damageChance(defender, enemy);
        final DamagePacket perHit = new DamagePacket(taken);
        perHit.scale(damageChance);
        final double damaging = hitsToDie(defender, perHit, pools);
        final double hits = hitChance <= 0 ? Double.POSITIVE_INFINITY : damaging / hitChance;
        double ehp = hits * enemy.total();
        if (Double.isFinite(ehp) && ehp >= MAX_DAMAGE) {
            ehp = Double.POSITIVE_INFINITY;
        }
        final Map<DamageType, Double> maxHit = new EnumMap<>(DamageType.class);
        for (final DamageType type : withMaxHit ? DamageType.values() : new DamageType[0]) {
            maxHit.put(type, maxHit(defender, type, pools, enemySheet));
        }
        return new Result(ehp, Double.isInfinite(ehp) ? Double.POSITIVE_INFINITY : hits, damaging, hitChance, damageChance, crit,
                enemy.total(), taken, maxHit);
    }

    // ---- PoB2 的預設敵人 ----

    /**
     * 敵人是哪一種（PoB2 ConfigOptions 的 enemyIsBoss）：每一種傷害＝怪物傷害表 × 1.5 × 倍率，混沌再除 chaosDivisor；
     * 元素穿透 penetration；巔峰以上等級至少 T16。
     */
    public enum Tier {
        /** 一般怪（normalEnemyDPSMult 1 / 4.4：五種加起來＝傷害表 × 1.5）。 */
        NORMAL("一般怪", 1 / 4.40, 2.5, 0, false),
        /** Boss（stdBossDPSMult 4 / 4.4：加起來 × 6）。 */
        BOSS("Boss", 4 / 4.40, 2.5, 0, false),
        /** 巔峰 Boss（pinnacleBossDPSMult 8 / 4.4：加起來 × 12、元素穿透 3%）——PoB2 的預設。 */
        PINNACLE("巔峰 Boss", 8 / 4.40, 2.5, 15 / 5.0, true),
        /** 超級巔峰 Boss（uberBossDPSMult 10 / 4.25：加起來 × 15、混沌 ÷ 4、元素穿透 8%）。 */
        UBER("超級巔峰 Boss", 10 / 4.25, 4, 40 / 5.0, true);

        private final String displayName;
        private final double perType;
        private final double chaosDivisor;
        private final double penetration;
        private final boolean endgameLevel;

        Tier(final String displayName, final double perType, final double chaosDivisor, final double penetration, final boolean endgameLevel) {
            this.displayName = displayName;
            this.perType = perType;
            this.chaosDivisor = chaosDivisor;
            this.penetration = penetration;
            this.endgameLevel = endgameLevel;
        }

        public String displayName() {
            return displayName;
        }

        public double penetration() {
            return penetration;
        }

        /** 等級至少到 T16（PoB2 巔峰、超級巔峰最低 82 級；我們的 T16）。 */
        public boolean endgameLevel() {
            return endgameLevel;
        }

        /** 五種加起來是傷害表的幾倍（一般 1.5、Boss 6、巔峰 12、超級巔峰 15）。 */
        public double totalMultiplier() {
            return 1.5 * perType * (4 + 1 / chaosDivisor);
        }

        /** PoB2 的預設分配（物理、火、冰、電各 1，混沌 1 ÷ chaosDivisor）：照 DamageType 的順序。 */
        public double[] defaultMix() {
            return new double[] {1, 1, 1, 1, 1 / chaosDivisor};
        }

        /** 0 一般、1 Boss、2 巔峰、3 超級巔峰；不認得的回傳 null。 */
        public static Tier of(final int index) {
            return index >= 0 && index < values().length ? values()[index] : null;
        }
    }

    /**
     * 敵人的等級（PoB2）：沒指定（≤ 0）時一般怪、Boss 照角色等級，巔峰以上用 T16；巔峰以上最低 T16。夾在 1～maxLevel。
     */
    public static int enemyLevel(final Tier tier, final int requested, final int characterLevel, final int t16Level, final int maxLevel) {
        int level = requested > 0 ? requested : tier.endgameLevel() ? t16Level : characterLevel;
        if (tier.endgameLevel()) {
            level = Math.max(level, t16Level);
        }
        return Math.max(1, Math.min(maxLevel, level));
    }

    /**
     * PoB2 的預設敵人：總傷害＝怪物一下的平均傷害（monsters.yml 的傷害表，跟 PoB2 monsterDamageTable 同一份）× tier 的倍率；
     * mix 是物理、火、冰、電、混沌的比例（null 或全 0 ＝ PoB2 的預設分配）。暴擊 5%、暴擊加成 30%，一半攻擊。
     */
    public static Enemy pob2Enemy(final Tier tier, final double tableDamage, final double accuracy, final double[] mix) {
        double[] weights = mix;
        double sum = 0;
        if (weights != null && weights.length == DamageType.values().length) {
            for (final double w : weights) {
                sum += Math.max(0, Double.isFinite(w) ? w : 0);
            }
        }
        if (sum <= 0) {
            weights = tier.defaultMix();
            sum = 0;
            for (final double w : weights) {
                sum += w;
            }
        }
        final double total = Math.max(0, tableDamage) * tier.totalMultiplier();
        final DamagePacket damage = new DamagePacket();
        final DamageType[] types = DamageType.values();
        for (int i = 0; i < types.length; i++) {
            final double w = Math.max(0, Double.isFinite(weights[i]) ? weights[i] : 0);
            if (w > 0) {
                damage.set(types[i], total * w / sum);
            }
        }
        return new Enemy(damage, tier.penetration(), accuracy, MONSTER_CRIT_CHANCE / 100.0, MONSTER_CRIT_BONUS, AVERAGE_ATTACK_SHARE);
    }
}
