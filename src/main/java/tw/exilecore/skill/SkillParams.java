package tw.exilecore.skill;

import tw.exilecore.core.config.YamlNode;

/**
 * 技能運作方式的參數（skills.yml 裡跟 mechanic 同名的區塊）。沒用到的欄位保持預設值。
 * 距離的單位都是方塊，角度是度數（整個扇形的張角），速度是每 tick 幾格，時間是秒。
 * <p>
 * 2026-10-29 照站長（1 公尺 = 2 格，{@link tw.exilecore.core.Units}）：skills.yml 裡照 PoE2 公尺寫的欄位（範圍半徑、扇形與直線長度、
 * 爆炸半徑、連鎖距離、濺射與漩渦的範圍、位移距離、地面效果…）照原作寫公尺，讀進來乘上 {@link tw.exilecore.core.Units#BLOCKS_PER_METRE}，
 * 檢查範圍照公尺檢查；投射物的速度、射程、大小，近戰打擊距離（reach），準心最遠指多遠（range）本來就照 Minecraft 調，不換算。
 * 這次之前 1 公尺 = 1 格。
 *
 * @param reach       strike、flicker：打得到多遠
 * @param angle       strike、cone、flicker：扇形張角
 * @param targets     strike、flicker：最多打幾個（0 = 範圍內全部）
 * @param knockback   strike、flicker、dash 的順路打擊：擊退力道（0 = 不擊退）
 * @param execute     strike：處決——目標生命低於某個比例時更多傷害（null = 沒有）
 * @param projectiles projectile：一次幾發
 * @param spread      projectile：多發時整個扇形的張角；impact（2026-10-28）：多的那幾發散在準心周圍幾格（0 = 照半徑估）
 * @param speed       projectile：每 tick 飛幾格
 * @param range       projectile：最遠飛幾格；leap、dash：最遠幾格；chain：第一個目標最遠幾格；impact、zone、mark：準心最遠指多遠；
 *                    flicker：準心指的敵人、沒指到時找最近的敵人最遠幾格
 * @param size        projectile（魔法彈）：碰撞半徑
 * @param pierce      projectile：可以穿透幾個敵人
 * @param explode     projectile：擊中時爆炸的半徑（0 = 不爆炸）
 * @param wobble      projectile：每 tick 左右亂竄的最大角度（游雷）
 * @param arrow       projectile：用原版箭矢（弓、弩）
 * @param gravity     projectile（魔法彈）：每 tick 往下掉多少（榴彈的拋物線；0 = 直飛）
 * @param fuse        projectile：幾秒後自己炸開（榴彈；0 = 沒有）。有引信的不會直接打中敵人，碰到方塊會彈開
 * @param blink       projectile：停下來的地方施放者瞬移過去（瞬影箭）
 * @param splash      projectile：打中時再電到附近幾個敵人（null = 沒有）
 * @param burst       projectile：一次施放分成好幾波連續射出（null = 一波）
 * @param vortex      projectile：停下來的地方留下一個會把敵人吸進去的漩渦（null = 沒有）
 * @param scatter     projectile：停下來的地方再散射出幾支小的（裂空箭；null = 沒有）
 * @param ground      projectile 停下來的地方、dash 走過的路、nova 炸完的地方：留下的地面效果（null = 沒有）
 * @param radius      nova、leap：範圍半徑；impact、zone：圓的半徑
 * @param length      cone：扇形長度；zone 的直線：長度
 * @param chains      chain：連鎖幾次
 * @param chainRange  chain：每次連鎖最遠幾格
 * @param aftershock  nova：餘震（過一下再炸一次更大圈；null = 沒有）
 * @param dash        dash 的細節（順路打擊、起點與終點的爆開）
 * @param delay       impact：幾秒後落下
 * @param zone        zone：持續多久、多久一波、每波多少傷害、形狀、吸力
 * @param mark        mark：持續多久、受到的傷害增加多少、只對自己的攻擊
 * @param search      corpse：準心指的點附近多遠內找屍體
 * @param percent     corpse：屍體最大生命的幾 % 當火焰傷害
 * @param wander      projectile（2026-10-28 照 PoE2 Spark）：貼著地面不規則游走、照持續時間消失、同一次施放打同一個目標有間隔（null = 不是這種）
 */
public record SkillParams(double reach, double angle, int targets, double knockback, Execute execute,
                          Scaled projectiles, double spread, double speed, double range, double size, int pierce,
                          Scaled explode, double wobble, boolean arrow, double gravity, double fuse, boolean blink,
                          Splash splash, Burst burst, Vortex vortex, Scatter scatter, Zone ground,
                          Scaled radius, Scaled length, Scaled chains, double chainRange, Aftershock aftershock,
                          Dash dash, double delay, Zone zone, Mark mark, double search, Scaled percent, Wander wander) {

    /**
     * 2026-10-28 照 PoE2 Spark（RePoE SparkPlayer）：投射物貼著地面不規則游走（spark_pathing_width_bounds：離自己那條射出去的線左右最多
     * width 格），飛 duration 秒（base_skill_effect_duration，吃技能持續時間，不看射程；投射物速度變快就飛得遠），
     * 同一次施放的投射物打同一個目標至少隔 hitCooldown 秒（spark_min_time_between_target_clearing_ms：同一隻可以被同一次的好幾顆打到，
     * 但 0.66 秒內只會被打一次）。這次之前游雷在眼睛的高度亂飛（wobble 一直累積轉向，會轉回頭）、飛 range 格、同一次施放一個敵人只打一次。
     */
    public record Wander(double duration, double width, double hitCooldown) {
        /** 同一次施放打同一個目標的間隔（tick，無條件進位：0.66 秒 → 14 tick，才不會比原作更快打第二下）。 */
        public int hitCooldownTicks() {
            return hitCooldown <= 0 ? 0 : (int) Math.ceil(hitCooldown * 20 - 1e-9);
        }
    }

    /** 處決：目標生命低於 below（0.35 = 35%）時更多 more%（150 = 更多 150%）傷害。 */
    public record Execute(double below, double more) {
    }

    /**
     * 打中時濺射：對目標附近 radius 內最多 targets 個敵人，各造成這一下 damage%（50 = 一半）的傷害。
     * 2026-10-26：targets、damage 可以寫逐級表（照 PoE2 雷鳴箭的雷束：2→4→5 個、雷束是 100% 基礎攻擊傷害）；
     * 沒帶等級的 {@link #targets()}、{@link #damage()} 是 20 級的值（說明、估算用）。
     */
    public record Splash(Scaled targetsByLevel, double radius, Scaled damageByLevel) {
        public Splash(final int targets, final double radius, final double damage) {
            this(Scaled.of(targets), radius, Scaled.of(damage));
        }

        public int targets() {
            return targets(tw.exilecore.character.SkillLoadout.MAX_LEVEL);
        }

        public int targets(final int level) {
            return Math.max(1, targetsByLevel.intAt(level));
        }

        public double damage() {
            return damage(tw.exilecore.character.SkillLoadout.MAX_LEVEL);
        }

        public double damage(final int level) {
            return Math.max(1, Math.min(100, damageByLevel.at(level)));
        }
    }

    /** 連續射出：一共 count 波，每波間隔 interval tick（每波都重新照準心的方向）。 */
    public record Burst(int count, int interval) {
    }

    /**
     * 散射：停下來的地方往四周（整個扇形 spread 度）再射出 count 支，每支造成這一下 damage% 的傷害，飛 range 格。
     * 2026-10-26 照 PoE2（凝霜箭的冰片）：counts 可以隨等級（[8, 11]）；onHit = 只在打中敵人時往前噴出（不是停下來的時候）；
     * effectiveness > 0 = 小的用自己的傷害效益（不照這一發的 damage%）；convert = 小的自己的轉換（空的 = 跟這一發一樣）。
     */
    public record Scatter(Scaled counts, double spread, double damage, double range, boolean onHit, double effectiveness,
                          java.util.Map<tw.exilecore.stats.DamageType, Double> convert) {
        public Scatter {
            convert = convert == null || convert.isEmpty() ? java.util.Map.of() : java.util.Map.copyOf(convert);
        }

        /** 這個技能等級射出幾支（1～12）。 */
        public int count(final int level) {
            return Math.max(1, Math.min(12, counts.intAt(level)));
        }
    }

    /** 漩渦：留 duration 秒、每 interval 秒把 radius 內的敵人往中心拉 pull 格並造成這一下 damage% 的傷害。 */
    public record Vortex(double duration, double radius, double interval, double pull, double damage) {
    }

    /**
     * 地面效果：留 duration 秒，每 interval 秒對裡面的敵人造成技能 damage%（30 = 三成）的傷害。
     * zone 運作方式的形狀：circle（準心指的地方一圈）或 line（面前橫向的一道，length 長）；pull 是每波往中心拉幾格；
     * max 同一個玩家這個技能的地面效果同時最多幾個（0 = 不另外限制；超過的話最舊的那個消失）。
     */
    public record Zone(double duration, double interval, Scaled radius, double damage, String shape, Scaled length, double pull, int max,
                       Bolts bolts, Shower shower) {
        public Zone(final double duration, final double interval, final Scaled radius, final double damage, final String shape, final Scaled length,
                    final double pull, final int max) {
            this(duration, interval, radius, damage, shape, length, pull, max, null, null);
        }

        public boolean line() {
            return "line".equals(shape);
        }
    }

    /**
     * 2026-10-28 照 PoE2 Orb of Storms：每一波對 range 格內隨機一個敵人放一道雷，連鎖 chains 次（逐級，3 → 5 → 9），每次跳 chainRange 格內的。
     * 這次之前雷雲每一波打範圍內全部的敵人、沒有連鎖。
     */
    public record Bolts(Scaled chains, double range, double chainRange) {
    }

    /**
     * 2026-10-28 照 PoE2 Volcano：每一波噴出 count 顆火山彈（逐級，3 → 5 → 6），各落在範圍內隨機一點，炸到落點 radius 格內的敵人。
     * 這次之前地火柱每一波打範圍內全部的敵人。
     */
    public record Shower(Scaled count, double radius) {
    }

    /**
     * 餘震：delay 秒後在同一個地方再炸一次，半徑 × radius，更多 more% 傷害。
     * 2026-10-26：radius、more 可以寫逐級表（照 PoE2 地動：餘震半徑、傷害效益每一級不同）；
     * 沒帶等級的 {@link #radius()}、{@link #more()} 是 20 級的值（說明、估算用）。
     */
    public record Aftershock(double delay, Scaled radiusByLevel, Scaled moreByLevel) {
        public Aftershock(final double delay, final double radius, final double more) {
            this(delay, Scaled.of(radius), Scaled.of(more));
        }

        public double radius() {
            return radiusByLevel.last();
        }

        public double radius(final int level) {
            return radiusByLevel.at(level);
        }

        public double more() {
            return moreByLevel.last();
        }

        public double more(final int level) {
            return moreByLevel.at(level);
        }
    }

    /**
     * 位移的細節：hitWidth > 0 時順路打到離路線這麼近的敵人；startRadius、endRadius > 0 時在起點、終點炸一圈；
     * ground 不是 null 時沿路留下地面效果（寫在 dash 區塊的 ground）。
     */
    public record Dash(double hitWidth, Scaled startRadius, Scaled endRadius, Scaled rangeByLevel, int spins, double spinRadius) {
        public Dash(final double hitWidth, final Scaled startRadius, final Scaled endRadius) {
            this(hitWidth, startRadius, endRadius, null, 0, 0);
        }

        public Dash(final double hitWidth, final Scaled startRadius, final Scaled endRadius, final Scaled rangeByLevel) {
            this(hitWidth, startRadius, endRadius, rangeByLevel, 0, 0);
        }

        /**
         * 2026-10-28 照 PoE2 Whirling Assault（{@link WhirlingAssault}）：spins > 0 = 不是瞬移，而是在整個動作時間裡一路往前走、
         * 沿路轉 spins 圈，每一圈在當下的位置打一圈半徑 spinRadius 格的範圍（各是一個打擊範圍）；這次之前位移只有瞬移＋順路一條線。
         */
        public boolean whirls() {
            return spins > 0 && spinRadius > 0;
        }

        /** 2026-10-28 照 PoE2（掠閃 4 → 4.9 → 5.5 米）：這個等級最遠幾格；沒寫逐級的回傳 fallback（{@link SkillParams#range()}）。 */
        public double range(final int level, final double fallback) {
            return rangeByLevel == null ? fallback : rangeByLevel.at(level);
        }
    }

    /**
     * 印記：duration 秒內目標受到的傷害增加 taken%；selfOnly = 只有施放者打它才增加。
     * 2026-10 詛咒：radius > 0 = 準心指的地方半徑 radius 格內的敵人全部套上（不用指著敵人）；
     * tags 不是空的 = 只有帶那些標籤的擊中才增加（fire：火焰技能…；好幾個就各一條）。
     */
    public record Mark(Scaled durationByLevel, double taken, boolean selfOnly, Scaled radiusByLevel, java.util.List<String> tags,
                       java.util.List<ScaledMod> effects, Scaled slow) {
        public Mark(final double duration, final double taken, final boolean selfOnly, final double radius, final java.util.List<String> tags,
                    final java.util.List<ScaledMod> effects, final Scaled slow) {
            this(Scaled.of(duration), taken, selfOnly, Scaled.of(radius), tags, effects, slow);
        }

        public Mark(final double duration, final double taken, final boolean selfOnly, final double radius, final java.util.List<String> tags) {
            this(duration, taken, selfOnly, radius, tags, java.util.List.of(), Scaled.parse("slow", null, 0));
        }

        /** 等級 1 的持續秒數（2026-10-26：詛咒照 PoE2 逐級，施放時用 {@link #duration(int)}）。 */
        public double duration() {
            return durationByLevel.first();
        }

        public double duration(final int level) {
            return durationByLevel.at(level);
        }

        /** 等級 1 的範圍半徑（0 = 單體印記）。 */
        public double radius() {
            return radiusByLevel.first();
        }

        public double radius(final int level) {
            return radiusByLevel.at(level);
        }

        public Mark(final double duration, final double taken, final boolean selfOnly) {
            this(duration, taken, selfOnly, 0, java.util.List.of());
        }

        public boolean curse() {
            return radiusByLevel.first() > 0 || radiusByLevel.last() > 0;
        }
    }

    static SkillParams parse(final Mechanic mechanic, final YamlNode skill) {
        final YamlNode node = skill.node(mechanic.key());
        final String where = node.path();
        final double f = blocksPerMetre(skill); // 2026-10-29：每公尺幾格
        double reach = 3.5;
        double angle = 70;
        int targets = 1;
        double knockback = 0;
        Execute execute = null;
        Scaled projectiles = Scaled.of(1);
        double spread = 0;
        double speed = 1.5;
        double range = 30;
        double size = 0.3;
        int pierce = 0;
        Scaled explode = Scaled.of(0);
        double wobble = 0;
        boolean arrow = false;
        double gravity = 0;
        double fuse = 0;
        boolean blink = false;
        Splash splash = null;
        Burst burst = null;
        Vortex vortex = null;
        Scatter scatter = null;
        Zone ground = null;
        Scaled radius = m(f, Scaled.of(3)); // 2026-10-29：預設值也是公尺
        Scaled length = m(f, Scaled.of(6));
        Scaled chains = Scaled.of(2);
        double chainRange = m(f, 7);
        Aftershock aftershock = null;
        Dash dash = null;
        double delay = 0.8;
        Zone zone = null;
        Mark mark = null;
        double search = 6;
        Scaled percent = Scaled.of(10);
        Wander wander = null;
        switch (mechanic) {
            case STRIKE -> {
                reach = check(where, "reach", node.decimal("reach", reach), 1, 8);
                angle = check(where, "angle", node.decimal("angle", angle), 1, 360);
                targets = (int) check(where, "targets", node.integer("targets", targets), 0, 50);
                knockback = check(where, "knockback", node.decimal("knockback", knockback), 0, 3);
                if (node.has("execute")) {
                    final YamlNode sub = node.node("execute");
                    execute = new Execute(check(sub.path(), "below", sub.decimal("below", 0.35), 0.05, 0.9),
                            check(sub.path(), "more", sub.decimal("more", 100), 1, 500));
                }
            }
            case PROJECTILE -> {
                projectiles = scaled(where, node, "count", 1, 1, 20);
                spread = check(where, "spread", node.decimal("spread", projectiles.last() > 1 ? 30 : 0), 0, 360);
                speed = check(where, "speed", node.decimal("speed", speed), 0.2, 4);
                range = check(where, "range", node.decimal("range", range), 2, 64);
                size = check(where, "size", node.decimal("size", size), 0, 2);
                pierce = (int) check(where, "pierce", node.integer("pierce", pierce), 0, 20);
                explode = m(f, scaled(where, node, "explode", 0, 0, 6));
                wobble = check(where, "wobble", node.decimal("wobble", wobble), 0, 90);
                arrow = node.bool("arrow", false);
                gravity = check(where, "gravity", node.decimal("gravity", 0), 0, 0.3);
                fuse = check(where, "fuse", node.decimal("fuse", 0), 0, 10);
                blink = node.bool("blink", false);
                if (node.has("wander")) {
                    final YamlNode sub = node.node("wander");
                    wander = new Wander(check(sub.path(), "duration", sub.decimal("duration", 2), 0.2, 10),
                            m(f, check(sub.path(), "width", sub.decimal("width", 2), 0, 10)),
                            check(sub.path(), "hit_cooldown", sub.decimal("hit_cooldown", 0), 0, 5));
                }
                if (arrow && (gravity > 0 || fuse > 0)) {
                    throw new IllegalArgumentException(where + "：原版箭矢（arrow: true）不能寫 gravity 或 fuse（榴彈用魔法彈）");
                }
                if (node.has("splash")) {
                    final YamlNode sub = node.node("splash");
                    splash = new Splash(scaled(sub.path(), sub, "targets", 3, 1, 10),
                            m(f, check(sub.path(), "radius", sub.decimal("radius", 5), 1, 12)),
                            // 2026-10-28 照 PoE2：上限 100 → 200（雷鳴箭 1～3 級的雷束是基礎攻擊傷害 100%，比箭本身 80%～97% 還高）
                            scaled(sub.path(), sub, "damage", 50, 1, 200));
                }
                if (node.has("burst")) {
                    final YamlNode sub = node.node("burst");
                    burst = new Burst((int) check(sub.path(), "count", sub.integer("count", 3), 2, 10),
                            (int) check(sub.path(), "interval", sub.integer("interval", 2), 1, 10));
                }
                if (node.has("vortex")) {
                    final YamlNode sub = node.node("vortex");
                    vortex = new Vortex(check(sub.path(), "duration", sub.decimal("duration", 2), 0.5, 10),
                            m(f, check(sub.path(), "radius", sub.decimal("radius", 3), 1, 8)),
                            check(sub.path(), "interval", sub.decimal("interval", 0.5), 0.1, 2),
                            m(f, check(sub.path(), "pull", sub.decimal("pull", 0.2), 0, 1.5)),
                            check(sub.path(), "damage", sub.decimal("damage", 40), 1, 100));
                }
                if (node.has("scatter")) {
                    final YamlNode sub = node.node("scatter");
                    // 2026-10-26：count 可以隨等級、on_hit／effectiveness／convert（凝霜箭的冰片，照 PoE2）
                    final java.util.Map<tw.exilecore.stats.DamageType, Double> convert = new java.util.EnumMap<>(tw.exilecore.stats.DamageType.class);
                    if (sub.has("convert")) {
                        final YamlNode weights = sub.node("convert");
                        double total = 0;
                        for (final String key : weights.keys()) {
                            final tw.exilecore.stats.DamageType type = tw.exilecore.stats.DamageType.fromTag(key);
                            if (type == null || type == tw.exilecore.stats.DamageType.PHYSICAL) {
                                throw new IllegalArgumentException(sub.path() + ".convert：只能把物理轉成其他類型，卻是「" + key + "」");
                            }
                            final double value = check(sub.path() + ".convert", key, weights.decimal(key, 0), 0, 1);
                            total += value;
                            if (value > 0) {
                                convert.put(type, value);
                            }
                        }
                        check(sub.path(), "convert（總和）", total, 0, 1);
                    }
                    scatter = new Scatter(scaled(sub.path(), sub, "count", 3, 1, 12),
                            check(sub.path(), "spread", sub.decimal("spread", 360), 10, 360),
                            check(sub.path(), "damage", sub.decimal("damage", 60), 1, 100),
                            check(sub.path(), "range", sub.decimal("range", 8), 1, 30),
                            sub.bool("on_hit", false),
                            check(sub.path(), "effectiveness", sub.decimal("effectiveness", 0), 0, 10), convert);
                }
                if (node.has("ground")) {
                    ground = zone(node.node("ground"), false, f);
                }
            }
            case NOVA -> {
                radius = m(f, scaled(where, node, "radius", 3.5, 0.5, 10));
                knockback = check(where, "knockback", node.decimal("knockback", 0), 0, 3); // 2026-10-28 照 PoE2（旋矛擊揮那一下擊退）；這次之前新星不能擊退
                if (node.has("ground")) {
                    ground = zone(node.node("ground"), false, f);
                }
                if (node.has("aftershock")) {
                    final YamlNode sub = node.node("aftershock");
                    // 2026-10-26：delay 上限 3 → 4（PoE2 地動 4 秒後餘震）；radius、more 可以逐級
                    aftershock = new Aftershock(check(sub.path(), "delay", sub.decimal("delay", 0.8), 0.2, 4),
                            m(f, scaled(sub.path(), sub, "radius", 1.5, 0.5, 3)), // 新星的餘震半徑是公尺（扇形的是長度的倍數，不換算）
                            scaled(sub.path(), sub, "more", 50, -90, 1000));
                }
            }
            case CONE -> {
                length = m(f, scaled(where, node, "length", 6, 1, 16));
                angle = check(where, "angle", node.decimal("angle", angle), 1, 180);
                if (node.has("aftershock")) {
                    // 2026-10：扇形也能有餘震——前方（扇形長度的 75% 處）過一下再砸一圈（崩土擊的第二下）；radius 是扇形長度的倍數
                    final YamlNode sub = node.node("aftershock");
                    aftershock = new Aftershock(check(sub.path(), "delay", sub.decimal("delay", 0.4), 0.1, 3),
                            scaled(sub.path(), sub, "radius", 0.4, 0.1, 3),
                            scaled(sub.path(), sub, "more", 50, -90, 300));
                }
            }
            case LEAP -> {
                range = check(where, "range", node.decimal("range", 9), 2, 16);
                radius = m(f, scaled(where, node, "radius", 2.5, 0.5, 8));
            }
            case CHAIN -> {
                range = check(where, "range", node.decimal("range", 12), 2, 32);
                chains = scaled(where, node, "chains", 2, 0, 10);
                chainRange = m(f, check(where, "chain_range", node.decimal("chain_range", chainRange), 1, 16));
            }
            case DASH -> {
                // 2026-10-28 照 PoE2（掠閃 4 → 4.9 → 5.5 米）：range 可以寫逐級表；這次之前只能寫一個數字
                final Scaled dashRange = m(f, scaled(where, node, "range", 8, 2, 20)); // 照 PoE2 的位移距離（公尺）
                range = dashRange.last();
                knockback = check(where, "knockback", node.decimal("knockback", 0), 0, 3);
                dash = new Dash(m(f, check(where, "hit_width", node.decimal("hit_width", 0), 0, 4)),
                        m(f, scaled(where, node, "start_radius", 0, 0, 8)), m(f, scaled(where, node, "end_radius", 0, 0, 8)),
                        dashRange.first() == dashRange.last() && !dashRange.isTable() ? null : dashRange,
                        (int) check(where, "spins", node.integer("spins", 0), 0, 12), // 2026-10-28 照 PoE2 Whirling Assault：一路轉幾圈
                        m(f, check(where, "spin_radius", node.decimal("spin_radius", 0), 0, 8)));
                if (node.has("ground")) {
                    ground = zone(node.node("ground"), false, f);
                }
            }
            case IMPACT -> {
                range = check(where, "range", node.decimal("range", 20), 2, 40);
                delay = check(where, "delay", node.decimal("delay", 0.8), 0.1, 4);
                radius = m(f, scaled(where, node, "radius", 3, 0.5, 10));
                // 2026-10-28 照 PoE2（衝霄弩台一次 3 發、落釘弩台一輪 4 擊）：一次落下幾發（投射物數量的加成加在這上面）；
                // spread：多的那幾發散在準心周圍幾格（0 = 照半徑估）。這次之前一律 1 發
                projectiles = scaled(where, node, "count", 1, 1, 20);
                spread = m(f, check(where, "spread", node.decimal("spread", 0), 0, 10));
                // 2026-10-28 照 PoE2 Contagion（瘟疫「使一名敵人」）：targets 有寫就是單體——準心指的（或準心 radius 內最近的）那隻敵人，
                // 不是一整圈；沒寫（0）照舊打半徑內全部。這次之前 impact 一律打一整圈
                targets = node.has("targets") ? (int) check(where, "targets", node.integer("targets", 1), 1, 10) : 0;
            }
            case ZONE -> {
                range = check(where, "range", node.decimal("range", 0), 0, 40);
                zone = zone(node, true, f);
                radius = zone.radius();
                length = zone.length();
            }
            case MARK -> {
                range = check(where, "range", node.decimal("range", 16), 2, 40);
                final java.util.List<String> markTags = new java.util.ArrayList<>();
                for (final String tag : node.strings("tags")) {
                    final String t = tag.trim().toLowerCase(java.util.Locale.ROOT);
                    if (!java.util.Set.of("physical", "fire", "cold", "lightning", "chaos", "attack", "spell", "projectile", "melee", "area", "minion").contains(t)) {
                        throw new IllegalArgumentException(where + ".tags：不認得「" + tag + "」（physical、fire、cold、lightning、chaos、attack、spell…）");
                    }
                    markTags.add(t);
                }
                // 2026-10-26：詛咒的範圍、持續時間可以寫逐級表（PoE2：1.5→3.1→3.7 格、6→7.4→8.6 秒）
                final Scaled markRadius = m(f, scaled(where, node, "radius", 0, 0, 10));
                final Scaled markDuration = scaled(where, node, "duration", 6, 0.5, 30);
                // 2026-10：effects 疊在敵人身上的修飾器（削弱：敵人造成的傷害更少…；value 可以隨等級）、slow 敵人移動慢幾 %
                final java.util.List<ScaledMod> markEffects = ScaledMod.parseList(node, "effects");
                final Scaled markSlow = scaled(where, node, "slow", 0, 0, 90);
                mark = new Mark(markDuration,
                        check(where, "taken", node.decimal("taken", markEffects.isEmpty() && markSlow.last() <= 0 ? 20 : 0), 0, 100),
                        node.bool("self_only", markRadius.first() <= 0), markRadius, java.util.List.copyOf(markTags), markEffects, markSlow);
            }
            case CORPSE -> {
                range = check(where, "range", node.decimal("range", 16), 2, 40);
                search = check(where, "search", node.decimal("search", 6), 1, 16);
                radius = m(f, scaled(where, node, "radius", 3, 0.5, 10));
                percent = scaled(where, node, "percent", 10, 0, 100);
            }
            case FLICKER -> {
                range = check(where, "range", node.decimal("range", 12), 3, 24);
                reach = check(where, "reach", node.decimal("reach", reach), 1, 8);
                angle = check(where, "angle", node.decimal("angle", angle), 1, 360);
                targets = (int) check(where, "targets", node.integer("targets", targets), 0, 50);
                knockback = check(where, "knockback", node.decimal("knockback", knockback), 0, 3);
            }
            case DESECRATE -> {
                range = check(where, "range", node.decimal("range", 16), 2, 40);
                search = check(where, "spread", node.decimal("spread", 2.5), 0, 8);
                percent = scaled(where, node, "life", 80, 1, 500);
                targets = (int) check(where, "count", node.integer("count", 3), 1, 10);
            }
            case GUARD -> {
                // 舉盾（2026-10）：持續幾秒、自己得到的增益（effects）、移動慢幾 %（slow）；借 Mark 的欄位放
                mark = new Mark(check(where, "duration", node.decimal("duration", 2), 0.5, 10), 0, true, 0, java.util.List.of(),
                        ScaledMod.parseList(node, "effects"), scaled(where, node, "slow", 0, 0, 90));
            }
            case SUMMON, AURA, WARCRY, RALLY, META -> {
                // 參數在各自的區塊（SummonSpec、AuraSpec、WarcrySpec），這裡沒有東西要讀
            }
            default -> throw new IllegalStateException(mechanic.name());
        }
        return new SkillParams(reach, angle, targets, knockback, execute, projectiles, spread, speed, range, size, pierce,
                explode, wobble, arrow, gravity, fuse, blink, splash, burst, vortex, scatter, ground,
                radius, length, chains, chainRange, aftershock, dash, delay, zone, mark, search, percent, wander);
    }

    /** 地面效果的區塊（zone 運作方式自己、投射物停下來留下的、位移沿路留下的）。 */
    private static Zone zone(final YamlNode node, final boolean shaped, final double f) {
        final String where = node.path();
        final String shape = shaped ? node.string("shape", "circle").trim() : "circle";
        if (!shape.equals("circle") && !shape.equals("line")) {
            throw new IllegalArgumentException(where + ".shape：要寫 circle 或 line");
        }
        return new Zone(check(where, "duration", node.decimal("duration", 3), 0.2, 30),
                check(where, "interval", node.decimal("interval", 0.5), 0.1, 5),
                m(f, scaled(where, node, "radius", shape.equals("line") ? 1.0 : 2.5, 0.3, 10)),
                check(where, "damage", node.decimal("damage", 30), 1, 500), // 2026-10-26：上限 200 → 500（只上毒的毒霧照 PoE2 像打中一樣，是投射物的 2～4 倍）
                shape, m(f, scaled(where, node, "length", 6, 1, 16)),
                m(f, check(where, "pull", node.decimal("pull", 0), 0, 1.5)),
                (int) check(where, "max", node.decimal("max", 0), 0, 10),
                node.has("bolts") ? new Bolts(scaled(where + ".bolts", node.node("bolts"), "chains", 0, 0, 20),
                        m(f, check(where + ".bolts", "range", node.node("bolts").decimal("range", 5.6), 1, 16)),
                        m(f, check(where + ".bolts", "chain_range", node.node("bolts").decimal("chain_range", 3.5), 1, 16))) : null,
                node.has("shower") ? new Shower(scaled(where + ".shower", node.node("shower"), "count", 3, 1, 20),
                        m(f, check(where + ".shower", "radius", node.node("shower").decimal("radius", 0.6), 0.2, 4))) : null);
    }

    /** 2026-10-29：照 PoE2 公尺寫的距離換成格（{@link tw.exilecore.core.Units}；先照公尺檢查範圍再換）。 */
    private static double m(final double factor, final double metres) {
        return metres * factor;
    }

    private static Scaled m(final double factor, final Scaled metres) {
        return metres.times(factor);
    }

    /**
     * 2026-10-29 照站長（只換算 PoE2DB 原作有公尺的地方）：這個技能的距離每公尺幾格。skills.yml 寫 {@code metres: false} 的
     * （伺服器自己設計的近似值，例如輔助觸發的熔坑、詛咒小標記）照舊 1 比 1，不換算。
     */
    static double blocksPerMetre(final YamlNode skill) {
        return skill.bool("metres", true) ? tw.exilecore.core.Units.BLOCKS_PER_METRE : 1.0;
    }

    private static Scaled scaled(final String where, final YamlNode node, final String key, final double def,
                                 final double min, final double max) {
        final Scaled value = Scaled.parse(where + "." + key, node.raw(key), def);
        check(where, key, value.first(), min, max);
        check(where, key, value.last(), min, max);
        return value;
    }

    private static double check(final String where, final String key, final double value, final double min, final double max) {
        if (value < min || value > max) {
            throw new IllegalArgumentException(where + "." + key + "：要在 " + trim(min) + " 到 " + trim(max) + " 之間，卻是 " + trim(value));
        }
        return value;
    }

    private static String trim(final double value) {
        return value == Math.rint(value) ? String.valueOf((long) value) : String.valueOf(value);
    }
}
