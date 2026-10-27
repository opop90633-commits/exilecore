package tw.exilecore.skill;

import tw.exilecore.core.config.YamlNode;

/**
 * 蓄力放開（2026-10，照 PoE2 的 Snipe、Perfect Strike、Flameblast）：按住按鍵蓄力，放開才出手。skills.yml 的 charge 區塊。
 * <ul>
 *   <li>完美時機（perfect_at &gt; 0）：蓄到「這一招攻擊／施法時間的 perfect_at%」那一刻會叮一聲，前後 {@link #WINDOW_BEFORE}～{@link #WINDOW_AFTER}
 *       tick 內放開是完美時機：更多 perfect_more% 傷害，並觸發這個技能寫的 proc.perfect（放開的時候）／proc.perfect_hit（打中的時候）</li>
 *   <li>疊層（stages &gt; 0）：每 stage_time 秒（吃施法／攻擊速度）加一層，最多 stages 層；每多一層更多 stage_more% 傷害、增加 stage_area% 範圍</li>
 * </ul>
 * 滑鼠鍵按住蓄力、放開出手；數字鍵按一下開始、再按一下放出。蓄太久（完美時機之後 {@link #OVERHOLD} tick、疊滿之後同樣）自動放出。
 *
 * @param perfectAt   完美時機在攻擊／施法時間的幾 %（0 = 沒有完美時機）
 * @param perfectMore 完美時機更多幾 % 傷害
 * @param stages      最多幾層（0 = 不疊層）
 * @param stageTime   每層幾秒（基礎；攻擊／施法速度越快越短）
 * @param stageMore   每多一層更多幾 % 傷害
 * @param stageArea   每多一層增加幾 % 範圍
 * @param stageProjectiles 每多一層多射幾支投射物（2026-10-26：照 PoE2 的 Bonestorm，引導越久骨刺越多，放開一起射出）
 * @param channel   2026-10-26 照 PoE2 的引導（Channel）：按住期間的魔力消耗、衝鋒、持續作用（{@link Channel}）
 */
public record ChargeSpec(double perfectAt, double perfectMore, int stages, double stageTime, double stageMore, double stageArea,
                         int stageProjectiles, Channel channel) {

    public ChargeSpec(final double perfectAt, final double perfectMore, final int stages, final double stageTime, final double stageMore,
                      final double stageArea) {
        this(perfectAt, perfectMore, stages, stageTime, stageMore, stageArea, 0, Channel.NONE);
    }

    public ChargeSpec(final double perfectAt, final double perfectMore, final int stages, final double stageTime, final double stageMore,
                      final double stageArea, final int stageProjectiles) {
        this(perfectAt, perfectMore, stages, stageTime, stageMore, stageArea, stageProjectiles, Channel.NONE);
    }

    /**
     * 2026-10-26 照 PoE2 的引導技能怎麼結束（這次之前只有「放開出手」）：
     * RELEASE 按住蓄力、放開出手；RUSH 按住一直往前衝（盾衝、蠻衝），放開或撞到敵人結束；
     * PULSE 按住期間每一次施法間隔作用一次（凋蝕術），放開結束；HOLD 按住期間舉盾（架盾：擋下所有可格擋的擊中；2026-10-27 照站長「放技能減速全部移除」：不再變慢），放開結束；
     * STREAM（2026-10-26，熾息）按住期間每一次攻擊／施法間隔出手一次（技能本身的效果），放開（過了最短引導時間）結束；
     * RECHARGE（2026-10-27 照 PoE2 Meditate）按住期間回充能量護盾（回充速度更多：技能 buff 的 es.recharge MORE），回充可以溢出到最大的 1.5 倍
     * （溢出的部分是守護），放開或溢出滿了結束。
     */
    public enum Mode { RELEASE, RUSH, PULSE, HOLD, STREAM, RECHARGE }

    /**
     * 2026-10-26 照 PoE2 的引導（skills.yml 的 charge 區塊裡的其他欄位）。
     *
     * @param mode         引導方式（mode: release／rush／pulse）
     * @param drain        mana 是「每秒」的魔力消耗（PoE2 的 ManaPerMinute ÷ 60）：開始時先扣一秒、之後按住每秒再扣一次，放開不另外扣；
     *                     魔力不夠就結束引導（drain: true）
     * @param perAttack    每一層的時間是一次攻擊時間（吃攻擊速度；PoE2 的 Detonating Arrow 的 GainsStages）
     * @param stageStat    每一層給的屬性（FLAT，乘層數；爆焰矢：damage.gain.fire 每層 120）
     * @param stageValue   同上的數值
     * @param explodeAtMax 投射物只有疊滿層數才會爆炸（爆焰矢）
     * @param minTime      最短引導幾秒（放開也會撐到這個時間；蠻衝 3 秒、盾衝 1.3 秒）
     * @param speed        衝鋒速度（格／秒，吃移動速度；盾衝 9、蠻衝 5.5）
     * @param distanceMore 衝越遠更多幾 % 傷害（最多；盾衝 100）
     * @param fullDistance 衝多遠（格）拿到全部的 distanceMore
     * @param collision    撞上的敵人另外再挨一下：傷害是範圍那一下的幾倍（盾衝的 Collision 70% ÷ 範圍 30%）
     * @param rageAfter    引導幾秒之後開始花怒氣（蠻衝 2.5 秒）
     * @param ragePerSecond 之後每秒花幾點怒氣（蠻衝 300／分鐘 = 5）；沒有怒氣就結束
     * @param backflip     開始引導時往後翻幾格（引霆突）
     * @param perfectRange 完美時機放開時位移的距離（引霆突 10 格；0 = 不變）
     * @param perfectStat  完美時機放開時另外給的屬性（FLAT；引霆突：damage.convert.lightning 再 50，物理 70% 轉閃電）
     * @param perfectValue 同上的數值
     * @param slow         2026-10-26：引導中移動更少幾 %（STREAM；熾息 40）。2026-10-27 照站長「放技能減速全部移除」：不再套用（只留著讓舊資料讀得進來）。2026-10-26 站長把焚燼併入焚流，只有焚燼用的燃料（fuel）一起拿掉
     * @param empowerCharge 2026-10-26（熾息）：開始引導時有這種戰星就用掉一顆強化這次引導（null = 不會）
     * @param empowerStat  強化時另外給的屬性（FLAT；熾息：damage.gain.lightning 50）
     * @param empowerValue 同上的數值
     * @param empowerRageLess 強化時怒氣花得少幾 %（熾息 50）
     * @param interruptOnHit 2026-10-27 照 PoE2（Meditate：Channelling ends when you take damage）：受到傷害就中斷引導
     * @param attackSpeedCost 2026-10-28 照 PoE2（Snipe：attack_speed_modifiers_apply_to_over_time_cost）：逐秒的魔力消耗再乘攻擊速度的倍率
     *                       （攻擊速度詞綴也會影響此技能的消耗；attack_speed_cost: true）
     */
    public record Channel(Mode mode, boolean drain, boolean perAttack, String stageStat, double stageValue, boolean explodeAtMax,
                          double minTime, double speed, double distanceMore, double fullDistance, double collision,
                          double rageAfter, double ragePerSecond, double backflip, double perfectRange,
                          String perfectStat, double perfectValue, double slow,
                          String empowerCharge, String empowerStat, double empowerValue, double empowerRageLess,
                          boolean interruptOnHit, boolean attackSpeedCost) {

        public static final Channel NONE = new Channel(Mode.RELEASE, false, false, null, 0, false, 0, 0, 0, 0, 0, 0, 0, 0, 0, null, 0,
                0, null, null, 0, 0, false, false);

        /** 強化時這一秒花幾點怒氣（照 empowerRageLess）。 */
        public int rageWhenEmpowered(final int rage) {
            return rage <= 0 ? 0 : (int) Math.max(1, Math.round(rage * (1 - empowerRageLess / 100.0)));
        }

        /** 衝了 distance 格：更多幾倍（1 ＋ 最多 distanceMore%，照距離線性）。純函式（測試用）。 */
        public double distanceFactor(final double distance) {
            if (distanceMore <= 0 || fullDistance <= 0) {
                return 1.0;
            }
            return 1 + distanceMore / 100.0 * Math.max(0, Math.min(1, distance / fullDistance));
        }

        /** 引導了 heldTicks tick：這一 tick 要不要花怒氣、花多少（只在每秒的那一 tick 花）。純函式（測試用）。 */
        public int rageDue(final long heldTicks) {
            if (ragePerSecond <= 0 || heldTicks < Math.round(rageAfter * 20)) {
                return 0;
            }
            return (heldTicks - Math.round(rageAfter * 20)) % 20 == 0 ? (int) Math.round(ragePerSecond) : 0;
        }

        /** 最短引導幾 tick。 */
        public long minTicks() {
            return Math.round(minTime * 20);
        }
    }

    /** 魔力是每秒扣的（引導中）。 */
    public boolean drain() {
        return channel.drain();
    }

    public Mode mode() {
        return channel.mode();
    }

    /**
     * 引導了 heldTicks tick：這一 tick 要不要扣魔力（開始的那一 tick 扣，之後每 20 tick 扣一次）。純函式（測試用）。
     */
    public static boolean drainDue(final long heldTicks) {
        return heldTicks >= 0 && heldTicks % 20 == 0;
    }

    /**
     * 2026-10-28 照 PoE2（Snipe）：引導中每秒實際扣多少魔力。寫了 attack_speed_cost 的技能，每秒消耗再乘攻擊速度的倍率
     * （增加 × 更多，不含武器本身的攻速）；其他照 build 算好的魔力消耗。
     */
    public int drainCost(final SkillBuild build, final tw.exilecore.stats.StatSheet sheet) {
        final int base = build.manaCost();
        if (!channel.attackSpeedCost() || !build.definition().isAttack() || base <= 0 || sheet == null) {
            return base;
        }
        final double multiplier = build.profile().effective(sheet).apply(tw.exilecore.stats.StatKeys.ATTACK_SPEED, 1.0, build.profile().tags());
        return scaledCost(base, multiplier);
    }

    /** 每秒消耗 × 攻擊速度倍率（四捨五入，至少 1）。純函式（測試用）。 */
    static int scaledCost(final int base, final double attackSpeedMultiplier) {
        if (base <= 0) {
            return base;
        }
        return (int) Math.max(1, Math.round(base * Math.max(0.1, attackSpeedMultiplier)));
    }

    /** 完美時機那一刻之前幾 tick 放開也算（網路延遲、按鍵的反應）。 */
    public static final int WINDOW_BEFORE = 2;
    /** 完美時機那一刻之後幾 tick 內放開算（PoE2 基礎 0.3 秒）。 */
    public static final int WINDOW_AFTER = 6;
    /** 過了完美時機（或疊滿）再多按住幾 tick 就自動放出。 */
    public static final int OVERHOLD = 40;

    public boolean perfect() {
        return perfectAt > 0;
    }

    /** 完美時機在第幾 tick（照這一招的攻擊／施法間隔）。 */
    public long perfectTick(final double intervalTicks) {
        return Math.max(6, Math.min(80, Math.round(intervalTicks * perfectAt / 100.0)));
    }

    /** 蓄了 held tick 是不是完美時機。 */
    public boolean isPerfect(final long held, final double intervalTicks) {
        return isPerfect(held, intervalTicks, 1.0);
    }

    /** 同上，完美時機的區間長 windowScale 倍（2026-10-26，虛望之盔：區間長 100～150%）。 */
    public boolean isPerfect(final long held, final double intervalTicks, final double windowScale) {
        if (!perfect()) {
            return false;
        }
        final long at = perfectTick(intervalTicks);
        final double scale = Math.max(1.0, windowScale);
        return held >= at - Math.round(WINDOW_BEFORE * scale) && held <= at + Math.round(WINDOW_AFTER * scale);
    }

    /** 每層幾 tick（照這一招的速度：速度越快越短）。 */
    public double stageTicks(final double speedFactor) {
        return Math.max(2, stageTime * 20 / Math.max(0.1, speedFactor));
    }

    /** 蓄了 held tick 有幾層（至少 1 層）。 */
    public int stagesAt(final long held, final double speedFactor) {
        if (stages <= 0) {
            return 1;
        }
        return (int) Math.max(1, Math.min(stages, 1 + Math.floor(held / stageTicks(speedFactor))));
    }

    /** skills.yml 的 charge 區塊；沒寫回傳 null。 */
    static ChargeSpec parse(final YamlNode skill) {
        if (!skill.has("charge")) {
            return null;
        }
        final YamlNode node = skill.node("charge");
        final double perfectAt = node.decimal("perfect_at", 0);
        final double perfectMore = node.decimal("perfect_more", 0);
        final int stages = node.integer("stages", 0);
        final double stageTime = node.decimal("stage_time", 0.25);
        final double stageMore = node.decimal("stage_more", 0);
        final double stageArea = node.decimal("stage_area", 0);
        final int stageProjectiles = node.integer("stage_projectiles", 0);
        if (stageProjectiles < 0 || stageProjectiles > 5) {
            throw new IllegalArgumentException(node.path() + ".stage_projectiles：要在 0 到 5 之間");
        }
        final Channel channel = parseChannel(node);
        if (channel.mode() != Mode.RELEASE) {
            if (perfectAt > 0 || stages > 0) {
                throw new IllegalArgumentException(node.path() + "：mode 是 rush／pulse／hold／stream 時不能寫 perfect_at、stages");
            }
            if (channel.mode() == Mode.RUSH && (channel.speed() <= 0 || channel.speed() > 20)) {
                throw new IllegalArgumentException(node.path() + ".speed：衝鋒要寫 0 到 20 格／秒");
            }
            return new ChargeSpec(0, 0, 0, stageTime, 0, 0, 0, channel);
        }
        if (perfectAt < 0 || perfectAt > 400 || stages < 0 || stages > 20 || stageTime <= 0 || stageTime > 3 || perfectMore < 0) {
            throw new IllegalArgumentException(node.path() + "：perfect_at 0–400、stages 0–20、stage_time 0–3 秒");
        }
        if (perfectAt <= 0 && stages <= 0) {
            throw new IllegalArgumentException(node.path() + "：要寫 perfect_at（完美時機）或 stages（疊層）");
        }
        return new ChargeSpec(perfectAt, perfectMore, stages, stageTime, stageMore, stageArea, stageProjectiles, channel);
    }

    /** 2026-10-26：charge 區塊裡的引導欄位（都沒寫就是 {@link Channel#NONE}）。 */
    private static Channel parseChannel(final YamlNode node) {
        final String raw = node.string("mode", "release").trim().toLowerCase(java.util.Locale.ROOT);
        final Mode mode = switch (raw) {
            case "release" -> Mode.RELEASE;
            case "rush" -> Mode.RUSH;
            case "pulse" -> Mode.PULSE;
            case "hold" -> Mode.HOLD;
            case "stream" -> Mode.STREAM;
            case "recharge" -> Mode.RECHARGE; // 2026-10-27 照 PoE2 Meditate
            default -> throw new IllegalArgumentException(node.path() + ".mode：要寫 release、rush、pulse、hold、stream 或 recharge，卻是「" + raw + "」");
        };
        final String stageStat = node.has("stage_stat") ? node.string("stage_stat", "").trim() : null;
        final String perfectStat = node.has("perfect_stat") ? node.string("perfect_stat", "").trim() : null;
        final double minTime = node.decimal("min_time", 0);
        final double collision = node.decimal("collision", 0);
        if (minTime < 0 || minTime > 10 || collision < 0 || collision > 10) {
            throw new IllegalArgumentException(node.path() + "：min_time 0–10 秒、collision 0–10 倍");
        }
        // 2026-10-26：STREAM 的移動減速、用戰星強化（熾息）
        final double slow = node.decimal("slow", 0);
        if (slow < 0 || slow > 95) {
            throw new IllegalArgumentException(node.path() + ".slow：要在 0 到 95 之間");
        }
        final String empowerCharge = node.has("empower_charge") ? node.string("empower_charge", "").trim().toLowerCase(java.util.Locale.ROOT) : null;
        if (empowerCharge != null && !empowerCharge.isEmpty() && !empowerCharge.matches("[a-z_]+")) {
            throw new IllegalArgumentException(node.path() + ".empower_charge：要寫戰星的代號（charges.yml，例如 power），卻是「" + empowerCharge + "」");
        }
        final String empowerStat = node.has("empower_stat") ? node.string("empower_stat", "").trim() : null;
        return new Channel(mode, node.bool("drain", false), node.bool("per_attack", false), stageStat == null || stageStat.isEmpty() ? null : stageStat,
                node.decimal("stage_value", 0), node.bool("explode_at_max", false), minTime, node.decimal("speed", 0),
                node.decimal("distance_more", 0), node.decimal("full_distance", 0), collision, node.decimal("rage_after", 0),
                node.decimal("rage_per_second", 0), node.decimal("backflip", 0), node.decimal("perfect_range", 0),
                perfectStat == null || perfectStat.isEmpty() ? null : perfectStat, node.decimal("perfect_value", 0),
                slow,
                empowerCharge == null || empowerCharge.isEmpty() ? null : empowerCharge,
                empowerStat == null || empowerStat.isEmpty() ? null : empowerStat, node.decimal("empower_value", 0),
                node.decimal("empower_rage_less", 0), node.bool("interrupt_on_hit", false),
                node.bool("attack_speed_cost", false)); // 2026-10-28 照 PoE2 Snipe
    }
}
