package tw.exilecore.skill;

import org.bukkit.Location;
import org.bukkit.Particle;
import org.bukkit.Sound;
import org.bukkit.SoundCategory;
import org.bukkit.attribute.Attribute;
import org.bukkit.attribute.AttributeInstance;
import org.bukkit.entity.LivingEntity;
import org.bukkit.entity.Player;
import org.bukkit.util.Vector;
import tw.exilecore.ExileCore;
import tw.exilecore.character.PlayerState;
import tw.exilecore.combat.HitEffects;
import tw.exilecore.item.ItemRenderer;
import tw.exilecore.stats.Modifier;
import tw.exilecore.stats.StatKeys;

import java.util.List;
import java.util.Locale;

/**
 * 2026-10-26 照 PoE2 的引導（Channel）技能（這次之前：引導只有「按住蓄力、放開出手」，魔力在放開時扣一次；
 * 盾衝、蠻衝、引霆突是一按就衝完的位移加冷卻，凋蝕術是一按一次的印記）：
 * <ul>
 *   <li>逐秒扣魔力（charge.drain）：PoE2 的「每分鐘魔力消耗」。開始引導時先扣一秒，之後按住每秒再扣一次；魔力不夠就結束（放出或停下）。</li>
 *   <li>衝鋒（mode: rush）：按住一直朝準心的方向跑（速度吃移動速度）。盾衝撞上敵人就停下，撞擊一圈（衝越遠越痛）、撞上的那隻另外挨一下並被擊退，
 *       衝的時候盾舉著（擋下所有可格擋的擊中）；蠻衝一路每一次攻擊時間踏地一下（加一下餘震），最短 3 秒，2.5 秒之後每秒花怒氣撐著，
 *       用的時候不會獲得怒氣。</li>
 *   <li>持續作用（mode: pulse）：凋蝕術按住期間，作用區域從準心處慢慢往準心移動（1.8 公尺／秒），每一次施法間隔給範圍內的敵人一層凋零；
 *       引導中移動速度更少 70%。</li>
 *   <li>連續出手（mode: stream，2026-10-26：熾息）：按住期間每一次攻擊／施法間隔放一次技能本身。冷卻在開始引導時就開始算；
 *       熾息：最短 3 秒、前 4 秒不花怒氣、之後每秒花怒氣撐著，有能星就用掉一顆強化。
 *       （2026-10-26 站長把焚燼併入焚流，只有焚燼用的燃料一起拿掉；這次之前焚燼不花魔力，燒別的技能花魔力存下來的燃料）</li>
 *   <li>回充（mode: recharge，2026-10-27 照 PoE2 的冥想 Meditate）：按住期間能量護盾照回充速度回充（不用等延遲；技能的 es.recharge 更多），
 *       可以溢出到最大的 1.5 倍（溢出的部分是守護）；冷卻在開始時就算，溢出滿了結束。</li>
 *   <li>受到傷害中斷（interrupt_on_hit，2026-10-27 照 PoE2 Meditate：Channelling ends when you take damage）：任何模式都可以加。</li>
 * </ul>
 * 按住的偵測沿用蓄力（{@link Signatures}）：滑鼠鍵按住時客戶端一直送施放（左鍵每 tick、右鍵每 4 tick），停了就是放開；
 * 數字鍵沒辦法按住，按一下開始、再按一下結束。
 */
final class Channelling {

    /** 凋蝕術的作用區域往準心移動的速度（公尺／秒；PoE2 wither_channeled_move_targeting_area_X_units_per_second = 18）。 */
    static final double WITHER_FOLLOW = 1.8;
    /** 凋蝕術引導中移動速度更少幾 %（PoE2 movement_speed_+%_final_while_performing_action = -70）。 */
    static final double WITHER_SLOW = 70;
    /** 衝鋒時撞到敵人的偵測：前方幾格、多寬。 */
    static final double CRASH_AHEAD = 1.2;
    static final double CRASH_WIDTH = 1.0;
    /** 撞上的那隻被擊退（PoE2 盾衝 9 公尺；換成原版的擊退速度）。 */
    static final double CRASH_KNOCKBACK = 1.6;
    /** 卡在牆上幾 tick 沒前進就停下。 */
    static final int STUCK_TICKS = 6;
    /** 蠻衝的踏地餘震晚幾 tick（PoE2 aftershock_delay_ms = 300）。 */
    static final long AFTERSHOCK_DELAY = 6;
    /** 引導中的增益（盾舉著、凋蝕術減速）多久刷新一次、撐幾 tick。 */
    static final int BUFF_REFRESH = 10;
    static final int BUFF_TICKS = 15;

    private final ExileCore plugin;
    private final SkillService service;

    Channelling(final ExileCore plugin, final SkillService service) {
        this.plugin = plugin;
        this.service = service;
    }

    /**
     * 剛開始引導（SkillService 在 {@link Signatures#press} 開始新的蓄力之後呼叫）：記下技能、先扣一次消耗、引霆突往後翻。
     * 扣不起回傳不能放的原因（引導取消），可以回傳 null。
     */
    String start(final Player player, final SkillRuntime runtime, final PlayerState state, final SkillBuild build, final ChargeSpec spec,
                 final long now) {
        final SkillRuntime.Charging charging = runtime.charging;
        if (charging == null) {
            return null;
        }
        charging.build = build;
        charging.lastAt = player.getLocation();
        charging.nextBeat = now;
        final ChargeSpec.Channel channel = spec.channel();
        if (spec.drain()) {
            charging.drainCost = build.manaCost();
            final String problem = service.payChannel(player, state, build, charging.drainCost);
            if (problem != null) {
                return problem;
            }
        } else if (spec.mode() != ChargeSpec.Mode.RELEASE) {
            final String problem = service.payChannel(player, state, build, build.manaCost()); // 蠻衝：開始時扣一次（PoE2 每次使用的魔力）
            if (problem != null) {
                return problem;
            }
        }
        if (spec.mode() == ChargeSpec.Mode.PULSE) {
            charging.focus = service.targets().aimPoint(Origin.of(player), build.range() > 0 ? build.range() : 22);
        }
        if (spec.mode() == ChargeSpec.Mode.STREAM || spec.mode() == ChargeSpec.Mode.RECHARGE) {
            // 2026-10-26 照 PoE2（熾息：冷卻在用的時候就開始算，引導結束不再重算）；2026-10-27 冥想也是
            if (build.cooldownTicks() > 0) {
                runtime.startCooldown(build.definition().id(), now, build.cooldownTicks());
                service.showCooldown(player, build.definition().id(), build.cooldownTicks());
            }
            if (channel.empowerCharge() != null && plugin.combat() != null && plugin.combat().charges() != null
                    && plugin.combat().charges().spend(player, channel.empowerCharge(), build.profile().effective(state.sheet()), build.profile().tags()) > 0) {
                charging.empowered = channel.empowerStat() == null ? List.of()
                        : List.of(Modifier.flat(channel.empowerStat(), channel.empowerValue(), "強化"));
                state.notice("<#c7a6ff>" + ItemRenderer.escape(build.definition().name()) + "：用掉一顆戰星強化", now + 30);
            }
        }
        if (channel.backflip() > 0) {
            final Vector back = SkillTargets.facing(player).multiply(-channel.backflip() * 0.25).setY(0.35);
            player.setVelocity(back); // 引霆突：往後翻
            player.getWorld().spawnParticle(Particle.ELECTRIC_SPARK, player.getLocation().add(0, 1, 0), 16, 0.4, 0.6, 0.4, 0.05);
        }
        return null;
    }

    /**
     * 引導中每 tick（{@link Signatures} 的蓄力迴圈呼叫）：逐秒扣魔力。扣不起回傳 false（放開型的照樣放出，衝鋒、持續作用就停下）。
     */
    boolean drain(final Player player, final SkillRuntime.Charging charging, final ChargeSpec spec, final long held) {
        if (!spec.drain() || held <= 0 || !ChargeSpec.drainDue(held) || charging.build == null) {
            return true;
        }
        final PlayerState state = plugin.players().get(player);
        final String problem = service.payChannel(player, state, charging.build, charging.drainCost);
        if (problem != null) {
            state.notice("<gray>" + ItemRenderer.escape(charging.build.definition().name()) + "：" + problem + "，引導結束", plugin.getServer().getCurrentTick() + 30);
            plugin.players().display(player, state);
            return false;
        }
        return true;
    }

    /**
     * 衝鋒、持續作用的一 tick。回傳 true = 引導結束（runtime.charging 由呼叫的人清掉）。
     *
     * @param released 這一 tick 偵測到放開了（滑鼠鍵沒再送、數字鍵又按一次）
     */
    boolean tick(final Player player, final SkillRuntime.Charging charging, final ChargeSpec spec, final boolean released, final long now) {
        final SkillBuild build = charging.build;
        if (build == null || !service.eligible(player) || service.inTown(player)
                || plugin.ailments() != null && plugin.ailments().isIncapacitated(player)) {
            return true;
        }
        final long held = now - charging.start;
        final ChargeSpec.Channel channel = spec.channel();
        if (released) {
            charging.letGo = true;
        }
        if (channel.interruptOnHit() && plugin.players().get(player).lastDamageTick() >= charging.start) {
            // 2026-10-27 照 PoE2（冥想）：受到傷害就中斷
            final PlayerState state = plugin.players().get(player);
            state.notice("<gray>" + ItemRenderer.escape(build.definition().name()) + "：受到傷害，引導中斷", now + 30);
            plugin.players().display(player, state);
            return true;
        }
        if (charging.letGo && held >= channel.minTicks()) {
            if (spec.mode() == ChargeSpec.Mode.HOLD) {
                shieldBash(player, charging, build, now); // 2026-10-26 照 PoE2：剛擋下一擊就放開 = 盾擊
            }
            return true; // 放開了（過了最短引導時間）
        }
        if (!drain(player, charging, spec, held)) {
            return true;
        }
        final int rage = charging.empowered != null ? channel.rageWhenEmpowered(channel.rageDue(held)) : channel.rageDue(held);
        if (rage > 0) {
            if (service.resources().rage(player) < rage) {
                final PlayerState state = plugin.players().get(player);
                state.notice("<gray>" + ItemRenderer.escape(build.definition().name()) + "：怒氣不夠，引導結束", now + 30);
                plugin.players().display(player, state);
                return true;
            }
            service.resources().spendRage(player, rage);
        }
        return switch (spec.mode()) {
            case RUSH -> rush(player, charging, channel, build, now);
            case HOLD -> hold(player, charging, now);
            case STREAM -> stream(player, charging, channel, now);
            case RECHARGE -> recharge(player, charging, channel, build, now);
            default -> pulse(player, charging, build, now);
        };
    }

    // ---- 回充（冥想） ----

    /** 溢出的上限：最大能量護盾的幾倍（PoE2 Overflow：最多到最大的 1.5 倍）。 */
    static final double OVERFLOW_MAX = 0.5;
    /** 溢出的守護撐幾 tick（每 tick 刷新，放開後還留著）。 */
    static final long OVERFLOW_TICKS = 160;

    /**
     * 2026-10-27 照 PoE2 的冥想（Meditate）：按住期間能量護盾回充（不用等延遲），回充速度更多（技能 buff 的 es.recharge MORE，24～100%）；
     * 護盾滿了之後繼續回充、溢出（最多最大的 1.5 倍，溢出的部分是守護），溢出滿了結束。回傳 true = 引導結束。
     */
    private boolean recharge(final Player player, final SkillRuntime.Charging charging, final ChargeSpec.Channel channel, final SkillBuild build,
                             final long now) {
        if (channel.slow() > 0 && (now - charging.start) % BUFF_REFRESH == 0) {
            service.buff(player, "channel_slow", "引導中", BUFF_TICKS, List.of(Modifier.more(StatKeys.MOVEMENT_SPEED, -actionPenalty(player, channel.slow()), "引導中")));
        }
        final PlayerState state = plugin.players().get(player);
        final double max = state.maxEnergyShield();
        if (max <= 0) {
            state.notice("<gray>" + ItemRenderer.escape(build.definition().name()) + "：沒有能量護盾", now + 30);
            plugin.players().display(player, state);
            return true;
        }
        double more = 0;
        for (final Modifier mod : build.warcryOwnModifiers()) {
            if (StatKeys.ES_RECHARGE.equals(mod.stat()) && mod.type() == tw.exilecore.stats.ModType.MORE) {
                more += mod.value();
            }
        }
        final tw.exilecore.stats.StatSheet sheet = state.sheet();
        final double perTick = tw.exilecore.stats.Formulas.esRechargePerSecond(max, tw.exilecore.character.PlayerStateService.esRechargeSpeed(sheet)) / 20.0;
        final boolean natural = now - state.lastDamageTick() >= tw.exilecore.character.PlayerStateService.esRechargeDelay(sheet);
        final double[] result = meditateStep(state.energyShield(), max, charging.overflow, perTick, more, natural);
        state.setEnergyShield(result[0]);
        charging.overflow = result[1];
        if (charging.overflow > 0) {
            state.addGuard(charging.overflow, now + OVERFLOW_TICKS);
        }
        if (now % 5 == 0) {
            player.getWorld().spawnParticle(Particle.ENCHANT, player.getLocation().add(0, 1.2, 0), 6, 0.4, 0.5, 0.4, 0.4);
        }
        plugin.players().display(player, state);
        if (charging.overflow >= max * OVERFLOW_MAX - 1e-6) {
            state.notice("<#8fd3ff>" + ItemRenderer.escape(build.definition().name()) + "：能量護盾溢出滿了", now + 30);
            return true;
        }
        return false;
    }

    /**
     * 冥想的一 tick（純函式，測試用）：回傳 {新的能量護盾, 新的溢出}。natural = 一般的回充已經在跑（延遲過了，PlayerStateService 會加基礎的那份），
     * 這裡只加「更多」的那份；還沒開始回充就整份都由這裡加。護盾滿了之後整份（基礎 ×（1 + 更多））都算溢出，最多最大的 {@link #OVERFLOW_MAX}。
     */
    static double[] meditateStep(final double es, final double max, final double overflow, final double basePerTick, final double more,
                                 final boolean natural) {
        final double full = basePerTick * (1 + more);
        if (es < max) {
            final double add = natural ? basePerTick * more : full;
            return new double[] {Math.min(max, es + add), overflow};
        }
        return new double[] {max, Math.min(max * OVERFLOW_MAX, overflow + full)};
    }

    // ---- 連續出手（熾息） ----

    /** 2026-10-26：按住期間每一次攻擊／施法間隔放一次技能本身（不另外扣消耗、不重算冷卻）。回傳 true = 引導結束。 */
    private boolean stream(final Player player, final SkillRuntime.Charging charging, final ChargeSpec.Channel channel, final long now) {
        if (channel.slow() > 0 && (now - charging.start) % BUFF_REFRESH == 0) {
            service.buff(player, "channel_slow", "引導中", BUFF_TICKS, List.of(Modifier.more(StatKeys.MOVEMENT_SPEED,
                    -actionPenalty(player, channel.slow(), skillTags(charging.skillId)), "引導中"))); // 2026-10-27：帶技能標籤（火焰、冰冷技能）
        }
        if (now < charging.nextBeat) {
            return false;
        }
        charging.nextBeat = now + Math.max(2, Math.round(charging.interval));
        final SkillRuntime runtime = service.runtime(player);
        plugin.players().get(player).markRecent(tw.exilecore.stats.TreeConditions.CHANNEL_EVENT, now); // 天賦樹「引導中」
        final LivingEntity target = charging.target == null ? null
                : plugin.getServer().getEntity(charging.target) instanceof LivingEntity living && living.isValid() ? living : null;
        runtime.releasing = new SkillRuntime.Release(charging.skillId, now - charging.start, false, charging.speed);
        try {
            service.perform(player, runtime, charging.key, target, now);
        } finally {
            runtime.releasing = null;
        }
        return runtime.charging != charging; // 中途被取消（換了技能）就結束
    }

    /** 引導中不會獲得怒氣（PoE2 蠻衝：Cannot gain Rage while using this Skill）。 */
    boolean blocksRage(final SkillRuntime runtime) {
        if (runtime == null || runtime.charging == null) {
            return false;
        }
        final ChargeSpec spec = service.config().charge(runtime.charging.skillId);
        return spec != null && spec.channel().ragePerSecond() > 0;
    }

    // ---- 衝鋒 ----

    private boolean rush(final Player player, final SkillRuntime.Charging charging, final ChargeSpec.Channel channel, final SkillBuild build,
                         final long now) {
        final Location at = player.getLocation();
        final Vector facing = SkillTargets.facing(player);
        if (charging.lastAt != null && charging.lastAt.getWorld() == at.getWorld()) {
            final double moved = Math.hypot(at.getX() - charging.lastAt.getX(), at.getZ() - charging.lastAt.getZ());
            charging.travelled += moved;
            charging.stuck = now - charging.start > 2 && moved < 0.05 ? charging.stuck + 1 : 0;
        }
        charging.lastAt = at;
        if (charging.stuck >= STUCK_TICKS) {
            return true; // 撞牆了
        }
        final double perTick = channel.speed() / 20.0 * moveScale(player);
        final Vector velocity = facing.clone().multiply(perTick);
        velocity.setY(Math.min(0, player.getVelocity().getY()));
        player.setVelocity(velocity);
        player.setFallDistance(0);
        final SkillParams.Dash dash = build.params().dash();
        final double radius = dash == null ? 2.2 : build.scaledRadius(dash.endRadius());
        if (channel.collision() > 0) {
            // 盾衝：盾舉著（擋下所有可格擋的擊中），撞上敵人就停下
            if ((now - charging.start) % BUFF_REFRESH == 0) {
                service.buff(player, "channel_block", "舉盾衝鋒", BUFF_TICKS, List.of(
                        Modifier.flat(StatKeys.BLOCK_CHANCE, 100, "舉盾衝鋒"), Modifier.flat(StatKeys.BLOCK_MAX, 50, "舉盾衝鋒")));
            }
            final Location ahead = at.clone().add(facing.clone().multiply(CRASH_AHEAD));
            final List<LivingEntity> collided = service.targets().alongLine(at, ahead, CRASH_WIDTH, player);
            if (!collided.isEmpty()) {
                crash(player, build, channel, collided, at, radius);
                return true;
            }
            if (now % 2 == 0) {
                player.getWorld().spawnParticle(Particle.CLOUD, at.clone().add(0, 0.2, 0), 3, 0.3, 0.1, 0.3, 0.02);
            }
            return false;
        }
        // 蠻衝：每一次攻擊時間踏地一下，再一下餘震
        if (now >= charging.nextBeat) {
            charging.nextBeat = now + Math.max(4, Math.round(charging.interval));
            final Location slam = at.clone();
            service.executor().burst(player, build.profile(), slam, radius, build.knockback(), build.definition().visual());
            player.getWorld().playSound(slam, Sound.ENTITY_RAVAGER_STEP, SoundCategory.PLAYERS, 0.9f, 0.8f);
            plugin.getServer().getScheduler().runTaskLater(plugin, () -> {
                if (player.isOnline() && player.getWorld() == slam.getWorld()) {
                    service.executor().burst(player, build.profile(), slam, radius, 0, build.definition().visual());
                }
            }, AFTERSHOCK_DELAY);
        }
        return false;
    }

    /** 盾衝撞上敵人：一圈撞擊（衝越遠更多傷害），撞上的另外挨一下（Collision）並被擊退。 */
    private void crash(final Player player, final SkillBuild build, final ChargeSpec.Channel channel, final List<LivingEntity> collided,
                       final Location at, final double radius) {
        final double more = channel.distanceFactor(charging(player) == null ? 0 : charging(player).travelled);
        final tw.exilecore.stats.SkillProfile area = more > 1 ? build.profile().scaled(more, "衝鋒距離") : build.profile();
        service.executor().burst(player, area, at, radius, build.knockback(), build.definition().visual());
        final tw.exilecore.stats.SkillProfile collision = area.scaled(channel.collision(), "撞擊");
        for (final LivingEntity target : collided) {
            if (target.isValid()) {
                service.executor().hitOne(player, collision, target, at, CRASH_KNOCKBACK);
            }
        }
        player.setVelocity(new Vector(0, 0, 0));
        player.getWorld().playSound(at, Sound.ITEM_SHIELD_BLOCK, SoundCategory.PLAYERS, 1f, 0.7f);
        final PlayerState state = plugin.players().get(player);
        state.notice(String.format(Locale.ROOT, "<#c9d6e3>%s 撞上！<gray>（衝了 %.1f 公尺，更多 %.0f%% 傷害）",
                ItemRenderer.escape(build.definition().name()), charging(player) == null ? 0 : charging(player).travelled, (more - 1) * 100),
                plugin.getServer().getCurrentTick() + 30);
        plugin.players().display(player, state);
    }

    private SkillRuntime.Charging charging(final Player player) {
        return service.runtime(player).charging;
    }

    /** 2026-10-26 照 PoE2 天賦樹：邊動作邊走的減速（percent 是基礎幾 %）乘上「減少使用技能時的移動速度懲罰」，回傳小數。 */
    private double actionPenalty(final Player player, final double percent) {
        return actionPenalty(player, percent, java.util.Set.of());
    }

    /** 2026-10-27 照 PoE2 天賦樹：「使用火焰／冰冷技能時的移動速度懲罰減少」看技能的標籤。 */
    private double actionPenalty(final Player player, final double percent, final java.util.Set<String> skillTags) {
        return tw.exilecore.stats.TreeEffectKeys.actionPenalty(plugin.players().sheet(player), percent / 100.0, skillTags);
    }

    /** 技能的標籤（找不到是空的）。 */
    private java.util.Set<String> skillTags(final String skillId) {
        final SkillDefinition definition = service.config().skill(skillId);
        return definition == null ? java.util.Set.of() : definition.tags();
    }

    /** 移動速度倍率（原版的移動速度屬性 ÷ 基礎 0.1；含疾速、衝刺之類）。 */
    private static double moveScale(final Player player) {
        final AttributeInstance speed = player.getAttribute(Attribute.MOVEMENT_SPEED);
        if (speed == null) {
            return 1.0;
        }
        return Math.max(0.3, Math.min(3.0, speed.getValue() / 0.1));
    }

    // ---- 舉盾（架盾） ----

    /** 架盾移動速度更少幾 %（PoE2 Raise Shield：-50%）。 */
    static final double GUARD_SLOW = 50;

    /** 2026-10-26 照 PoE2 的架盾：按住期間盾舉著，擋下所有可格擋的擊中，移動更少 50%。 */
    private boolean hold(final Player player, final SkillRuntime.Charging charging, final long now) {
        if ((now - charging.start) % BUFF_REFRESH == 0) {
            service.buff(player, "channel_block", "架盾", BUFF_TICKS, List.of(
                    Modifier.flat(StatKeys.BLOCK_CHANCE, 100, "架盾"), Modifier.flat(StatKeys.BLOCK_MAX, 50, "架盾"),
                    Modifier.more(StatKeys.MOVEMENT_SPEED, -actionPenalty(player, GUARD_SLOW), "架盾")));
        }
        return false;
    }

    /** 盾擊：擋下之後多久內放開才算（PoE2 raise_shield_base_retaliation_duration 1000 ms）。 */
    static final long BASH_WINDOW = 20;
    /** 盾擊打身前多遠的地方、多大一圈（PoE2 近戰打擊 +1.4 公尺）。 */
    static final double BASH_AHEAD = 1.2;
    static final double BASH_RADIUS = 1.6;
    /** 盾擊：盾牌上每 5 點護甲 3～4 點物理傷害（PoE2）、基礎暴擊率 5%。 */
    static final double BASH_MIN_PER_5 = 3;
    static final double BASH_MAX_PER_5 = 4;
    static final double BASH_CRIT = 5;

    /**
     * 2026-10-26 照 PoE2 的架盾（Raise Shield）：舉盾擋下一擊之後 1 秒內放開、身前有敵人，用盾猛擊（Shield Bash）：
     * 用盾牌當武器（每 5 點護甲 3～4 點物理、基礎暴擊 5%，主手武器的傷害、暴擊不算）、80% 傷害效益（技能的 effectiveness）、
     * 暈眩累積更多 600%、不會被閃避（技能的 modifiers）；拿塔盾（純力量的盾）一定眩暈。吃掛在架盾上的輔助。
     * （原作還有「一定輕暈」：怪物沒有輕暈，沒做）
     */
    private void shieldBash(final Player player, final SkillRuntime.Charging charging, final SkillBuild build, final long now) {
        final PlayerState state = plugin.players().get(player);
        final long blocked = state.recentTick(tw.exilecore.stats.TreeConditions.ACTIVE_BLOCK_EVENT);
        if (blocked < charging.start || now - blocked > BASH_WINDOW || !build.definition().isAttack()) {
            return;
        }
        final Location at = player.getLocation().add(SkillTargets.facing(player).setY(0).normalize().multiply(BASH_AHEAD));
        if (service.targets().around(at, BASH_RADIUS, player).isEmpty()) {
            return;
        }
        final org.bukkit.inventory.ItemStack off = state.jewellery(tw.exilecore.character.Gear.OFF);
        if (off == null || off.isEmpty() || plugin.items() == null) {
            return;
        }
        final tw.exilecore.item.ItemData data = plugin.items().read(off).orElse(null);
        final tw.exilecore.item.ItemBase base = data == null ? null : plugin.items().repository().base(data.base());
        if (base == null || !base.isShield()) {
            return;
        }
        final double armour = tw.exilecore.item.ItemStats.snapshot(plugin.items().repository(), base, data).armour();
        final tw.exilecore.stats.StatSheet sheet = build.profile().effective(state.sheet());
        final List<Modifier> mods = shieldWeapon(sheet, build.profile(), armour, base.hasTag("str_only"));
        service.executor().burst(player, build.profile().withLocal(mods), at, BASH_RADIUS, build.knockback(), "slam");
        player.getWorld().playSound(at, Sound.ITEM_SHIELD_BLOCK, SoundCategory.PLAYERS, 1f, 0.6f);
        state.notice("<#c9d6e3>" + ItemRenderer.escape(build.definition().name()) + "：盾擊！", now + 20);
        plugin.players().display(player, state);
    }

    /** 盾擊用盾牌當武器：抵掉主手武器的傷害與暴擊，換成盾牌護甲換算的物理傷害、5% 基礎暴擊（塔盾一定眩暈）。 */
    static List<Modifier> shieldWeapon(final tw.exilecore.stats.StatSheet sheet, final tw.exilecore.stats.SkillProfile profile,
                                       final double armour, final boolean tower) {
        final List<Modifier> mods = new java.util.ArrayList<>();
        for (final tw.exilecore.stats.DamageType type : tw.exilecore.stats.DamageType.values()) {
            final java.util.Set<String> context = profile.contextFor(type);
            final java.util.Set<String> tags = java.util.Set.copyOf(type.tags());
            final double min = sheet.flat(StatKeys.WEAPON_MIN, context);
            final double max = sheet.flat(StatKeys.WEAPON_MAX, context);
            if (min != 0) {
                mods.add(new Modifier(StatKeys.WEAPON_MIN, tw.exilecore.stats.ModType.FLAT, -min, tags, "盾擊"));
            }
            if (max != 0) {
                mods.add(new Modifier(StatKeys.WEAPON_MAX, tw.exilecore.stats.ModType.FLAT, -max, tags, "盾擊"));
            }
        }
        final java.util.Set<String> physical = java.util.Set.copyOf(tw.exilecore.stats.DamageType.PHYSICAL.tags());
        mods.add(new Modifier(StatKeys.WEAPON_MIN, tw.exilecore.stats.ModType.FLAT, Math.max(0, armour) / 5 * BASH_MIN_PER_5, physical, "盾擊"));
        mods.add(new Modifier(StatKeys.WEAPON_MAX, tw.exilecore.stats.ModType.FLAT, Math.max(0, armour) / 5 * BASH_MAX_PER_5, physical, "盾擊"));
        mods.add(Modifier.flat(StatKeys.WEAPON_CRIT, BASH_CRIT - sheet.flat(StatKeys.WEAPON_CRIT, profile.tags()), "盾擊"));
        if (tower) {
            mods.add(Modifier.flat(StatKeys.DAZE_CHANCE, 100, "塔盾"));
        }
        return mods;
    }

    // ---- 持續作用（凋蝕術） ----

    private boolean pulse(final Player player, final SkillRuntime.Charging charging, final SkillBuild build, final long now) {
        if ((now - charging.start) % BUFF_REFRESH == 0) {
            service.buff(player, "channel_slow", "引導中", BUFF_TICKS, List.of(Modifier.more(StatKeys.MOVEMENT_SPEED,
                    -actionPenalty(player, WITHER_SLOW, build.profile().tags()), "引導中"))); // 2026-10-27：帶技能標籤
        }
        final Location aim = service.targets().aimPoint(Origin.of(player), build.range() > 0 ? build.range() : 22);
        if (charging.focus == null || charging.focus.getWorld() != player.getWorld()) {
            charging.focus = aim;
        } else if (aim != null && aim.getWorld() == charging.focus.getWorld()) {
            final Vector toward = aim.toVector().subtract(charging.focus.toVector());
            final double step = WITHER_FOLLOW / 20.0;
            if (toward.lengthSquared() > step * step) {
                toward.normalize().multiply(step);
            }
            charging.focus.add(toward);
        }
        if (now < charging.nextBeat || charging.focus == null) {
            return false;
        }
        charging.nextBeat = now + Math.max(2, Math.round(charging.interval));
        final SkillParams.Mark mark = build.params().mark();
        final double radius = mark == null ? 2.5 : mark.radiusByLevel().at(build.level()) * build.areaScale();
        final long ticks = mark == null ? HitEffects.WITHERED_TICKS : build.durationTicks(mark.duration(build.level()));
        if (plugin.combat() != null) {
            for (final LivingEntity target : service.targets().around(charging.focus, radius, player)) {
                HitEffects.addWithered(plugin.combat().debuffs(), target.getUniqueId(), player.getUniqueId(), now, 1, ticks,
                        Math.max(0, 1 + plugin.players().sheet(player).increased(tw.exilecore.stats.TreeEffectKeys.WITHERED_EFFECT, java.util.Set.of()))); // 2026-10-26：凋零效果
            }
        }
        final Location center = charging.focus.clone().add(0, 0.2, 0);
        player.getWorld().spawnParticle(Particle.SQUID_INK, center, 14, radius * 0.5, 0.15, radius * 0.5, 0.01);
        player.getWorld().spawnParticle(Particle.WITCH, center, 8, radius * 0.5, 0.3, radius * 0.5, 0.01);
        return false;
    }
}
