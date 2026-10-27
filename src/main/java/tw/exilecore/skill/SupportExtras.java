package tw.exilecore.skill;

import org.bukkit.Location;
import org.bukkit.Particle;
import org.bukkit.entity.LivingEntity;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.entity.EntityDamageEvent;
import org.bukkit.event.entity.EntityDeathEvent;
import org.bukkit.util.Vector;
import tw.exilecore.ExileCore;
import tw.exilecore.ailment.AilmentType;
import tw.exilecore.character.PlayerState;
import tw.exilecore.combat.Debuffs;
import tw.exilecore.monster.MonsterData;
import tw.exilecore.monster.MonsterRarity;
import tw.exilecore.stats.DamagePacket;
import tw.exilecore.stats.DamageType;
import tw.exilecore.stats.HitEvent;
import tw.exilecore.stats.HitResult;
import tw.exilecore.stats.ModType;
import tw.exilecore.stats.Modifier;
import tw.exilecore.stats.SkillProfile;
import tw.exilecore.stats.StatKeys;
import tw.exilecore.stats.StatSheet;
import tw.exilecore.stats.SupportExtraKeys;
import tw.exilecore.stats.Tags;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Iterator;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/**
 * 2026-10-27 照站長「PoE2 0.5 還沒有的一般輔助寶石（不含血脈）全部補上」：新輔助的機制（屬性鍵見 {@link SupportExtraKeys}，
 * 防呆見 {@link SupportGuards}）。由 {@link SupportMechanics} 在同樣的時機呼叫（擊中前、擊中後、放技能前後、每 5 tick、凍結／重暈事件），
 * 擊中、死亡、召喚物受傷另外自己聽事件。
 * <ul>
 *   <li>放技能時：先祖強化（Ancestral Aid／Call、Fist of War、Crescendo）、靜止（Dauntless）、堅忍（Stoicism）、
 *   充能射擊、指向、隨機穿透分裂連鎖、跳彈、碎片、光影齊射、新彈匣、用掉藥劑充能（Ambrosia、Concoct）、戰星（Potential、Rally、Tumult）、
 *   召喚物契約、褻瀆、易爆（Catharsis、Volatile Power）、怒氣（Relentless Rage）、放技能的那段時間的增益（Unbreakable、Unabating…）</li>
 *   <li>擊中前：對致盲（Blindside）、用掉凍結（Biting Frost）、用掉完全破甲（Exploit Weakness）、追擊與撤擊、必然暴擊、幸運（Defy）、
 *   完美時機、好目標（Punch Through）、狂亂（Derange）</li>
 *   <li>擊中後：眩暈、曝露、穿刺、加劇流血、易爆、電擊蓄勢的敵人、重壓擊、消耗癱瘓、招架、感電傳導、荊棘（Barbs、Quill Burst）、
 *   印記吸取、敵人再生減少（Acrimony）、野火</li>
 *   <li>打死時：詛咒綻放、感電吸取、撕裂（Tear）、血之殘骸（Rip）、斬首（Behead）</li>
 *   <li>每 5 tick：移動、引導的增益（Steadfast、Crackling Barrier、Deadly Resolve）、聖化地面、詛咒地面、野火擴散、光影齊射、夥伴、戰旗</li>
 * </ul>
 */
public final class SupportExtras implements Listener {

    private final ExileCore plugin;
    private final SkillService service;

    SupportExtras(final ExileCore plugin, final SkillService service) {
        this.plugin = plugin;
        this.service = service;
    }

    private long now() {
        return plugin.getServer().getCurrentTick();
    }

    private static String key(final Player player, final String skillId) {
        return player.getUniqueId() + "/" + skillId;
    }

    private StatSheet effective(final Player player, final SkillBuild build) {
        return build.profile().effective(plugin.players().sheet(player));
    }

    private static double flat(final StatSheet sheet, final String stat, final Set<String> tags) {
        return sheet.flat(stat, tags);
    }

    private boolean roll(final double percent) {
        return percent > 0 && (percent >= 100 || plugin.random().nextDouble() * 100 < percent);
    }

    private Debuffs debuffs() {
        return plugin.combat() == null ? null : plugin.combat().debuffs();
    }

    // =====================================================================
    // 狀態
    // =====================================================================

    /** Ancestral Aid：最近格擋了幾次（玩家 → 次數）。 */
    private final Map<UUID, Integer> blocks = new HashMap<>();
    /** Dauntless：最後一次移動在哪個 tick。 */
    private final Map<UUID, Long> lastMoved = new HashMap<>();
    /** Stoicism：從哪個 tick 開始算（翻滾、突進之後重來）。 */
    private final Map<UUID, Long> stoicSince = new HashMap<>();
    private final Map<UUID, Location> lastAt = new HashMap<>();
    /** Inexorable Critical：玩家/技能 → 從哪個 tick 開始累積。 */
    private final Map<String, Long> critSince = new HashMap<>();
    /** Charged Shots：玩家/技能 → 用了幾次。 */
    private final Map<String, Integer> uses = new HashMap<>();
    /** Ancestral Call、Fist of War：玩家/技能 → 下一次先祖強化的 tick。 */
    private final Map<String, Long> boostReady = new HashMap<>();
    /** Alignment：玩家/技能 → {指示的方向（0 北、1 東、2 南、3 西）, 從哪個 tick 開始指示（對準之後 1 秒才換新的方向）}。 */
    private final Map<String, long[]> alignment = new HashMap<>();
    /** 2026-10-27 照 PoE2 最後一擊：玩家 → 上一個用的技能、哪個 tick、連段到第幾下（Crescendo、Rending Apex）。 */
    private final Map<UUID, Combo> combos = new HashMap<>();

    private record Combo(String skill, long tick, int count) {
    }
    /** Spectral Volley：玩家/技能 → 存起來的光影投射物。 */
    private final Map<String, Spectral> spectral = new HashMap<>();
    /** 放技能（used）時算好、出手（beforeUse）時疊上的修飾器（藥劑充能、戰星、召喚物契約、褻瀆）。 */
    private final Map<UUID, Pending> pending = new HashMap<>();

    /** 付消耗時算好的修飾器是哪個技能的（召喚、戰吼這種不走 beforeUse 的技能留下的，下一個技能不能拿去用）。 */
    private record Pending(String skillId, List<Modifier> modifiers) {
    }
    /** Relentless Rage：花掉的怒氣累計。 */
    private final Map<UUID, Double> rageSpent = new HashMap<>();
    /** Fresh Clip：玩家/技能 → 這個技能裝填的弩矢（{tick, 幾顆}）。 */
    private final Map<String, ArrayDeque<long[]>> bolts = new HashMap<>();
    /** Perfection：每層到哪個 tick。 */
    private final Map<UUID, List<Long>> perfection = new HashMap<>();
    /** Incision：最近用掉的切割（玩家 → (tick, 層數)）。 */
    private final Map<UUID, ArrayDeque<long[]>> incisions = new HashMap<>();
    /** 擊中前看到的切割層數（怪物 → 層數），下一 tick 看有沒有被流血用掉。 */
    private final Map<UUID, Integer> incisionSeen = new HashMap<>();
    /** Mark of Siphoning：怪物 → 誰的印記、偷取 x%、到哪個 tick。 */
    private final Map<UUID, Siphon> siphons = new HashMap<>();
    /** 荊棘反彈之後：玩家 → (tick, Barbs 剩幾下, Quill Burst 剩幾下)。 */
    private final Map<UUID, long[]> thorns = new HashMap<>();
    /** 擊中前看到的條件（這一下要用掉凍結、用掉完全破甲、打中滿血的），擊中後處理；怪物 → 旗標。 */
    private final Map<UUID, Integer> preHitFlags = new HashMap<>();
    private static final int CONSUME_FREEZE_FLAG = 1;
    private static final int CONSUME_BROKEN_FLAG = 2;
    private static final int FULL_LIFE_FLAG = 4;
    /** Wildfire：點燃中的怪物 → (主人, 半徑)。 */
    private final Map<UUID, Wildfire> wildfires = new HashMap<>();
    /** Wildfire：怪物在點燃的怪物旁邊待了多久（怪物 → tick）。 */
    private final Map<UUID, Long> nearFire = new HashMap<>();
    /** Deadly Resolve：玩家 → 每層到哪個 tick；下一層什麼時候給、什麼時候能再斬。 */
    private final Map<UUID, List<Long>> resolve = new HashMap<>();
    private final Map<UUID, long[]> resolveTimers = new HashMap<>();
    /** 聖化地面、詛咒地面。 */
    private final List<Ground> grounds = new ArrayList<>();
    /** Energy Barrier：玩家 → {放被輔助的技能到哪個 tick, 被暈眩時多久不被打斷（tick）}；被暈眩之後：玩家 → 到哪個 tick 回充不被打斷。 */
    private final Map<UUID, long[]> barrierUsing = new HashMap<>();
    private final Map<UUID, Long> barrierUntil = new HashMap<>();
    /** Stormfire：被掛了雷焚的技能感電的敵人 → {誰, 幾 %}（牠還在感電時，點燃每一跳另外受到 x% 的閃電傷害）。 */
    private final Map<UUID, Stormfire> stormfires = new HashMap<>();
    /** Refraction：插下的戰旗（主人、地方、半徑、到哪個 tick、每 1000 護甲幾 %）。 */
    private final List<Banner> banners = new ArrayList<>();
    /** Stomping Ground：正在用的突進技能（玩家 → 技能、到哪個 tick、這一步走了多遠）。 */
    private final Map<UUID, Stomp> stomps = new HashMap<>();

    private record Stormfire(UUID owner, double percent) {
    }

    private record Banner(UUID owner, Location at, double radius, long untilTick, double perThousand) {
    }

    private static final class Stomp {
        final String skillId;
        final Location from;
        long untilTick;
        double walked;
        /** 跳躍：在空中不算走路，起跳、落地各一步。 */
        boolean leap;

        Stomp(final String skillId, final Location from, final long untilTick) {
            this.skillId = skillId;
            this.from = from;
            this.untilTick = untilTick;
        }
    }
    /** Last Gasp：召喚物 → 到哪個 tick 才死。 */
    private final Map<UUID, Long> lastGasp = new HashMap<>();
    /** Crazed Minions：主人/技能 → 召喚物最後一次死掉的 tick（之後叫出來的算復活）。 */
    private final Map<String, Long> minionDied = new HashMap<>();
    /** Delayed Gratification：玩家/技能 → 達成條件之後多一次的期限。 */
    private final Map<String, Long> gratification = new HashMap<>();
    /** 最後一下打這隻怪的技能有沒有掛撕裂、血之殘骸…（打死時用）：怪物 → 那一下的資料。 */
    private final Map<UUID, LastHit> lastHits = new HashMap<>();

    private record Siphon(UUID owner, double percent, long untilTick) {
    }

    private record Wildfire(UUID owner, double radius, long untilTick) {
    }

    private record LastHit(UUID owner, SkillProfile skill, StatSheet effective, boolean bleeding, boolean shocked, long tick) {
    }

    /** 地面：聖化（kind 0）或詛咒（kind 1：curse、spec 是那個詛咒）。 */
    private static final class Ground {
        final UUID owner;
        final int kind;
        final Location at;
        final double radius;
        final long untilTick;
        final SkillBuild curse;
        final SkillParams.Mark spec;

        Ground(final UUID owner, final int kind, final Location at, final double radius, final long untilTick, final SkillBuild curse,
               final SkillParams.Mark spec) {
            this.owner = owner;
            this.kind = kind;
            this.at = at;
            this.radius = radius;
            this.untilTick = untilTick;
            this.curse = curse;
            this.spec = spec;
        }
    }

    private static final class Spectral {
        int count;
        long lastCreated;
        SkillBuild build;
        UUID target;
    }

    // =====================================================================
    // 能不能用（SupportMechanics.usageProblem）
    // =====================================================================

    /** 不能用的原因；可以回傳 null。 */
    String usageProblem(final Player player, final SkillBuild build, final StatSheet effective) {
        final Set<String> tags = build.profile().tags();
        final String name = build.definition().name();
        final long now = now();
        final int needBlocks = (int) Math.round(flat(effective, SupportExtraKeys.ANCESTRAL_AID, tags));
        if (needBlocks > 0 && blocks.getOrDefault(player.getUniqueId(), 0) < needBlocks) {
            return String.format(Locale.ROOT, "%s 要先格擋 %d 次（現在 %d）", name, needBlocks, blocks.getOrDefault(player.getUniqueId(), 0));
        }
        if (flat(effective, SupportExtraKeys.DAUNTLESS, tags) > 0) {
            final long still = now - lastMoved.getOrDefault(player.getUniqueId(), now - SupportExtraKeys.DAUNTLESS_TICKS);
            if (still < SupportExtraKeys.DAUNTLESS_TICKS) {
                return String.format(Locale.ROOT, "%s 要先靜止 %.1f 秒", name, SupportExtraKeys.DAUNTLESS_TICKS / 20.0);
            }
        }
        final int volatility = (int) Math.round(flat(effective, SupportExtraKeys.CATHARSIS, tags));
        if (volatility > 0 && service.treeMechanics() != null && service.treeMechanics().volatility(player) < volatility) {
            return String.format(Locale.ROOT, "%s 要有 %d 層易爆（現在 %d）", name, volatility, service.treeMechanics().volatility(player));
        }
        return null;
    }

    /** Delayed Gratification：達成條件後 10 秒內多一次，這次不用條件。 */
    boolean gratified(final Player player, final SkillBuild build) {
        final Long until = gratification.get(key(player, build.definition().id()));
        return until != null && until >= now();
    }

    /** Delayed Gratification：條件更難（榮耀需求 × 這個倍率）。 */
    static double conditionScale(final StatSheet effective, final Set<String> tags) {
        return 1 + Math.max(0, effective.flat(SupportExtraKeys.GRATIFICATION, tags)) / 100.0;
    }

    /** 用掉了條件（榮耀）：開一次 10 秒內的免費使用；這次是免費的那次就收掉。 */
    void gratificationUsed(final Player player, final SkillBuild build, final boolean free) {
        final String key = key(player, build.definition().id());
        if (free) {
            gratification.remove(key);
        } else if (effective(player, build).flat(SupportExtraKeys.GRATIFICATION, build.profile().tags()) > 0) {
            gratification.put(key, now() + SupportExtraKeys.GRATIFICATION_TICKS);
        }
    }

    /** Defy、Desperation：被包圍時魔力不夠也能放。 */
    boolean freeMana(final Player player, final SkillBuild build) {
        if (!StatKeys.flag(effective(player, build), SupportExtraKeys.FLAG_FREE_SURROUNDED)) {
            return false;
        }
        final PlayerState state = plugin.players().get(player);
        return plugin.players().conditions().test(player, state, tw.exilecore.stats.TreeConditions.SURROUNDED, now());
    }

    /** Second Wind：冷卻次數加倍。 */
    int cooldownUsesFactor(final Player player, final String skillId) {
        return service.supportFlat(player, skillId, SupportExtraKeys.SECOND_WIND) > 0 ? 2 : 1;
    }

    // =====================================================================
    // 真的放了（付完消耗；SupportMechanics.used）
    // =====================================================================

    void used(final Player player, final SkillBuild build) {
        final StatSheet sheet = plugin.players().sheet(player);
        final StatSheet effective = build.profile().effective(sheet);
        final Set<String> tags = build.profile().tags();
        final PlayerState state = plugin.players().get(player);
        final long now = now();
        final List<Modifier> extra = new ArrayList<>();
        // 放技能的那段時間的增益（Unbreakable、Unabating、Untouchable、Unbending、Unyielding）
        final double interval = Math.max(4, build.intervalTicks(sheet));
        final List<Modifier> using = new ArrayList<>();
        for (final Modifier modifier : build.profile().local()) {
            if (modifier.stat().startsWith(SupportExtraKeys.USING_PREFIX)) {
                using.add(new Modifier(modifier.stat().substring(SupportExtraKeys.USING_PREFIX.length()), modifier.type(), modifier.value(),
                        modifier.tags(), modifier.source()));
            } else if (modifier.stat().startsWith(SupportExtraKeys.USING_QUARTER_PREFIX)) {
                final double quarters = interval / 5.0; // 每 0.25 秒的攻擊／施法時間一份
                using.add(new Modifier(modifier.stat().substring(SupportExtraKeys.USING_QUARTER_PREFIX.length()), modifier.type(),
                        modifier.value() * quarters, modifier.tags(), modifier.source()));
            }
        }
        if (!using.isEmpty()) {
            service.buff(player, "support.using", "出手中", (int) Math.round(interval) + 4, using);
        }
        // 2026-10-27 照 PoE2 Energy Barrier：放的那段時間被暈眩 → 能量護盾立刻回充（playerStunned）；這次之前放的那段時間回充不等待
        final double barrier = flat(effective, SupportExtraKeys.ENERGY_BARRIER, tags);
        if (barrier > 0) {
            barrierUsing.put(player.getUniqueId(), new long[] {now + Math.round(interval) + 4, Math.round(barrier * 20)});
        }
        // 殘骸類（Harmonic、Magnetic Remnants、Remnant Potency）：2026-10-27 改成跟著每一個殘骸走（ChargeEffects.dropInfusionRemnant 讀技能的屬性表）
        // Stoicism：突進技能重來
        if (tags.contains(Tags.TRAVEL)) {
            stoicSince.put(player.getUniqueId(), now);
        }
        // Catharsis：引爆隨機層數的易爆
        if (flat(effective, SupportExtraKeys.CATHARSIS, tags) > 0 && service.treeMechanics() != null) {
            final int have = service.treeMechanics().volatility(player);
            if (have > 0) {
                service.treeMechanics().explodeVolatility(player, 1 + plugin.random().nextInt(have));
            }
        }
        // Derange：扣等同智慧的能量護盾
        final double derange = flat(effective, SupportExtraKeys.DERANGE, tags);
        if (derange > 0) {
            final double intelligence = Math.max(0, sheet.get(StatKeys.INT));
            state.setEnergyShield(Math.max(0, state.energyShield() - intelligence));
        }
        // Ambrosia：用掉魔力藥劑充能，每點獲得 x% 傷害為額外閃電
        final double ambrosia = flat(effective, SupportExtraKeys.AMBROSIA, tags);
        if (ambrosia > 0) {
            final int used = consumeFlask(player, tw.exilecore.flask.FlaskKind.MANA, ambrosia / 100.0);
            if (used > 0) {
                extra.add(Modifier.flat(StatKeys.damageExtra(DamageType.LIGHTNING), used * flat(effective, SupportExtraKeys.AMBROSIA_GAIN, tags), "瓊漿"));
            }
        }
        // Concoct：用掉生命藥劑充能，每點流血更多 x%
        final double concoct = flat(effective, SupportExtraKeys.CONCOCT, tags);
        if (concoct > 0) {
            final int used = consumeFlask(player, tw.exilecore.flask.FlaskKind.LIFE, concoct / 100.0);
            if (used > 0) {
                extra.add(Modifier.more(StatKeys.BLEED_MAGNITUDE, used * flat(effective, SupportExtraKeys.CONCOCT_BLEED, tags) / 100.0, "調劑"));
            }
        }
        final tw.exilecore.charge.ChargeService charges = plugin.combat() == null ? null : plugin.combat().charges();
        // Potential：消耗一顆銳星，暴擊率更多 x%
        final double potential = flat(effective, SupportExtraKeys.POTENTIAL, tags);
        if (potential > 0 && charges != null && charges.consume(player, "power")) {
            extra.add(Modifier.more(StatKeys.CRIT_CHANCE, potential / 100.0, "潛力"));
            plugin.players().conditions().onChargeSpent(player, "power", now);
        }
        // Tumult：消耗一顆怒星，破甲等同物理傷害 x%
        final double tumult = flat(effective, SupportExtraKeys.TUMULT, tags);
        if (tumult > 0 && charges != null && charges.consume(player, "frenzy")) {
            extra.add(Modifier.flat(StatKeys.ARMOUR_BREAK, tumult, "騷動"));
            plugin.players().conditions().onChargeSpent(player, "frenzy", now);
        }
        // Rally：消耗全部磐星，每顆回復最大生命 x%
        final double rally = flat(effective, SupportExtraKeys.RALLY, tags);
        if (rally > 0 && charges != null) {
            int spent = 0;
            while (charges.count(player, "endurance") > 0 && charges.consume(player, "endurance")) {
                spent++;
                plugin.players().conditions().onChargeSpent(player, "endurance", now);
            }
            if (spent > 0 && !state.ruined(now)) {
                state.setLife(Math.min(state.maxLife(), state.life() + state.maxLife() * rally * spent / 100.0));
            }
        }
        // Relentless Rage：花掉夠多怒氣之後，這一次給怒氣
        final double relentless = flat(effective, SupportExtraKeys.RELENTLESS, tags);
        if (relentless > 0 && rageSpent.getOrDefault(player.getUniqueId(), 0.0) >= relentless) {
            rageSpent.remove(player.getUniqueId());
            service.resources().gainRage(player, flat(effective, SupportExtraKeys.RELENTLESS_GAIN, tags));
        }
        // Minion Pact：附近一隻召喚物失去你最大生命 x% 的生命，這一下更多 y%
        final double pact = flat(effective, SupportExtraKeys.MINION_PACT_LIFE, tags);
        if (pact > 0 && service.minions() != null && pactMinion(player, state.maxLife() * pact / 100.0)) {
            extra.add(Modifier.more(StatKeys.DAMAGE, flat(effective, SupportExtraKeys.MINION_PACT_MORE, tags) / 100.0, "召喚物契約"));
        }
        // Profanity：用掉 5 格內最多 x 具屍體，每具混沌更多 y%
        final double profanity = flat(effective, SupportExtraKeys.PROFANITY, tags);
        if (profanity > 0 && plugin.combat() != null && plugin.combat().corpses() != null) {
            final int consumed = consumeCorpses(player, (int) Math.round(profanity));
            if (consumed > 0) {
                extra.add(new Modifier(StatKeys.DAMAGE, ModType.MORE, consumed * flat(effective, SupportExtraKeys.PROFANITY_MORE, tags) / 100.0,
                        Set.of(Tags.CHAOS), "褻瀆"));
            }
        }
        // Gorge：用屍體的技能（每用掉一具）給藥劑充能
        final double gorge = flat(effective, SupportExtraKeys.GORGE, tags);
        if (gorge > 0 && plugin.flasks() != null && (build.definition().mechanic() == Mechanic.CORPSE || tags.contains(Tags.CORPSE))) {
            plugin.flasks().addCharges(player, (int) Math.round(gorge), tw.exilecore.flask.FlaskKind.LIFE);
            plugin.flasks().addCharges(player, (int) Math.round(gorge), tw.exilecore.flask.FlaskKind.MANA);
        }
        // Second Wind：進入冷卻時，每秒冷卻回復最大生命 x%
        final double secondWind = flat(effective, SupportExtraKeys.SECOND_WIND, tags);
        if (secondWind > 0 && build.cooldownTicks() > 0 && !state.ruined(now)) {
            state.setLife(Math.min(state.maxLife(), state.life() + state.maxLife() * secondWind * build.cooldownTicks() / 20.0 / 100.0));
        }
        if (!extra.isEmpty()) {
            pending.put(player.getUniqueId(), new Pending(build.definition().id(), extra));
        } else {
            pending.remove(player.getUniqueId());
        }
        plugin.players().display(player, state);
    }

    /** 用掉這種藥劑最大充能的 fraction（不夠就有多少用多少），回傳用掉幾點。 */
    private int consumeFlask(final Player player, final tw.exilecore.flask.FlaskKind kind, final double fraction) {
        final tw.exilecore.flask.FlaskService flasks = plugin.flasks();
        if (flasks == null) {
            return 0;
        }
        final int slot = flasks.slotFor(player, kind);
        if (slot < 0) {
            return 0;
        }
        final org.bukkit.inventory.ItemStack stack = player.getInventory().getItem(slot);
        final tw.exilecore.flask.FlaskService.FlaskData data = flasks.read(stack).orElse(null);
        final tw.exilecore.flask.FlaskBase base = data == null ? null : flasks.config().base(data.base());
        if (base == null) {
            return 0;
        }
        final int want = (int) Math.ceil(flasks.maxCharges(stack, base) * fraction);
        final int used = Math.min(want, data.charges());
        return used > 0 && flasks.spendCharges(player, kind, used) ? used : 0;
    }

    /** Minion Pact：最近的一隻自己的召喚物扣 amount 生命（扣到死也算），有扣到回傳 true。 */
    private boolean pactMinion(final Player player, final double amount) {
        MinionService.Minion best = null;
        double distance = SupportExtraKeys.MINION_PACT_RANGE * SupportExtraKeys.MINION_PACT_RANGE;
        for (final MinionService.Minion minion : service.minions().all()) {
            if (!minion.owner.equals(player.getUniqueId()) || !minion.mob.isValid() || !minion.mob.getWorld().equals(player.getWorld())) {
                continue;
            }
            final double d = minion.mob.getLocation().distanceSquared(player.getLocation());
            if (d <= distance) {
                distance = d;
                best = minion;
            }
        }
        if (best == null) {
            return false;
        }
        final double loss = amount * best.healthScale;
        best.mob.setHealth(Math.max(0, best.mob.getHealth() - loss));
        best.mob.getWorld().spawnParticle(Particle.DAMAGE_INDICATOR, best.mob.getLocation().add(0, 1, 0), 6, 0.3, 0.3, 0.3, 0);
        return true;
    }

    /** Profanity：用掉 5 格內最多 max 具屍體，回傳用掉幾具。 */
    private int consumeCorpses(final Player player, final int max) {
        final tw.exilecore.combat.Corpses corpses = plugin.combat().corpses();
        final Location at = player.getLocation();
        int consumed = 0;
        for (int i = 0; i < max; i++) {
            final tw.exilecore.combat.Corpses.Corpse corpse = corpses.nearest(at.getWorld().getUID(), at.getX(), at.getZ(),
                    SupportExtraKeys.PROFANITY_RANGE, now());
            if (corpse == null) {
                break;
            }
            corpses.remove(corpse);
            at.getWorld().spawnParticle(Particle.SOUL, new Location(at.getWorld(), corpse.x(), corpse.y() + 0.5, corpse.z()), 6, 0.3, 0.3, 0.3, 0.02);
            consumed++;
        }
        return consumed;
    }

    // =====================================================================
    // 出手之前（強化之後；SupportMechanics.beforeUse）
    // =====================================================================

    SkillBuild beforeUse(final Player player, final SkillBuild original) {
        SkillBuild build = original;
        final StatSheet sheet = plugin.players().sheet(player);
        final StatSheet effective = build.profile().effective(sheet);
        final Set<String> tags = build.profile().tags();
        final String key = key(player, build.definition().id());
        final long now = now();
        final List<Modifier> local = new ArrayList<>();
        final Pending paid = pending.remove(player.getUniqueId());
        if (paid != null && paid.skillId().equals(build.definition().id())) {
            local.addAll(paid.modifiers());
        }
        // 先祖強化
        boolean boost = false;
        int boosts = 1;
        if (flat(effective, SupportExtraKeys.ANCESTRAL_AID, tags) > 0 && blocks.getOrDefault(player.getUniqueId(), 0) > 0) {
            blocks.remove(player.getUniqueId());
            boost = true;
        }
        final double call = flat(effective, SupportExtraKeys.ANCESTRAL_CALL, tags);
        if (call > 0 && boostReady.getOrDefault(key, 0L) <= now) {
            boostReady.put(key, now + Math.round(call * 20));
            boost = true;
        }
        final double fist = flat(effective, SupportExtraKeys.FIST_OF_WAR, tags);
        if (fist > 0 && boostReady.getOrDefault(key + "#fist", 0L) <= now) {
            boostReady.put(key + "#fist", now + Math.round(fist * 20));
            boost = true;
            boosts = 2; // 效果加倍
        }
        // 2026-10-27 照 PoE2 最後一擊（PerformsFinalStrike）：快速連用時第 3／4 下是最後一擊（Crescendo 先祖強化、Rending Apex 破甲）；
        // 這次之前我們的打擊沒有連段，Crescendo 做成每 3 下一次、Rending Apex 平均成每一下破甲 25%
        final int finalAt = SupportExtraKeys.FINAL_STRIKE_SKILLS.getOrDefault(build.definition().id(), 0);
        final Combo previous = combos.get(player.getUniqueId());
        final int step = comboStep(previous == null ? null : previous.skill(), previous == null ? 0 : previous.tick(),
                previous == null ? 0 : previous.count(), build.definition().id(), now, finalAt);
        combos.put(player.getUniqueId(), new Combo(build.definition().id(), now, finalAt > 0 && step >= finalAt ? 0 : step));
        if (finalAt > 0 && step >= finalAt) {
            local.add(Modifier.flat(SupportExtraKeys.FINAL_STRIKE_MARK, 1, "最後一擊"));
            if (flat(effective, SupportExtraKeys.CRESCENDO, tags) > 0) {
                boost = true;
            }
            if (flat(effective, SupportExtraKeys.CRESCENDO, tags) > 0 || flat(effective, SupportExtraKeys.RENDING_APEX, tags) > 0) {
                player.getWorld().spawnParticle(Particle.SWEEP_ATTACK, player.getEyeLocation().add(SkillTargets.facing(player).multiply(1.5)), 1, 0, 0, 0, 0);
            }
        }
        // Dauntless：每靜止 0.25 秒擊中更多 x%
        final double dauntless = flat(effective, SupportExtraKeys.DAUNTLESS, tags);
        if (dauntless > 0) {
            final long still = now - lastMoved.getOrDefault(player.getUniqueId(), now);
            final double more = Math.min(SupportExtraKeys.DAUNTLESS_CAP, dauntless * Math.floor(still / 5.0));
            if (more > 0) {
                local.add(Modifier.more(tw.exilecore.stats.SupportKeys.DAMAGE_HIT, more / 100.0, "無畏"));
            }
        }
        // Stoicism：每秒更多 x%，最多 20%
        final double stoic = flat(effective, SupportExtraKeys.STOICISM, tags);
        if (stoic > 0) {
            final long since = stoicSince.computeIfAbsent(player.getUniqueId(), uuid -> now);
            final double more = Math.min(SupportExtraKeys.STOICISM_CAP, stoic * Math.floor((now - since) / 20.0));
            if (more > 0) {
                local.add(Modifier.more(StatKeys.DAMAGE, more / 100.0, "堅忍"));
            }
        }
        // Charged Shots：每 3 發一發充能射擊
        final double charged = flat(effective, SupportExtraKeys.CHARGED_SHOTS, tags);
        if (charged > 0) {
            final int count = uses.merge(key + "#charged", 1, Integer::sum);
            if (count >= SupportExtraKeys.CHARGED_SHOTS_EVERY) {
                uses.remove(key + "#charged");
                local.add(Modifier.flat(StatKeys.damageExtra(DamageType.LIGHTNING), charged, "充能射擊"));
                player.getWorld().spawnParticle(Particle.ELECTRIC_SPARK, player.getEyeLocation(), 10, 0.3, 0.3, 0.3, 0.05);
            }
        }
        // Fresh Clip：這個技能過去 8 秒每裝填一顆弩矢更多 x%（2026-10-27 照 PoE2；這次之前照所有弩技能「射出幾發」算）
        final double fresh = flat(effective, SupportExtraKeys.FRESH_CLIP, tags);
        if (fresh > 0) {
            final int count = reloadedRecently(key, now);
            if (count > 0) {
                local.add(Modifier.more(StatKeys.DAMAGE, fresh * count / 100.0, "新彈匣"));
            }
        }
        // Stomping Ground：開始用突進技能（每走一步一陣震波；瞬間到位的在 afterCast 照路線補）
        if (flat(effective, SupportExtraKeys.STOMPING, tags) > 0 && tags.contains(Tags.TRAVEL)) {
            final boolean leap = build.definition().mechanic() == Mechanic.LEAP;
            final Stomp stomp = new Stomp(build.definition().id(), player.getLocation(),
                    now + (leap ? 100 : Math.max(10, Math.round(build.intervalTicks(sheet)))));
            stomp.leap = leap;
            stomps.put(player.getUniqueId(), stomp);
        }
        // Delayed Reaction／Rearm：2026-10-27 照 PoE2 全部作用在危險物件上（寒凝彈的冰晶 SkillZones、蒺藜步的鐵蒺 OriginalSkills、
        // 矛陣的矛 Spearfield）；這次之前矛陣是持續打的範圍，在這裡做成持續時間更少 70%、每一下更多 283%／持續時間更多 x%
        // Volatile Power：被強化時使用給易爆
        final double volatile_ = flat(effective, SupportExtraKeys.VOLATILITY_ON_EMPOWERED, tags);
        if (volatile_ > 0 && tags.contains(tw.exilecore.stats.TreeMechanicKeys.EMPOWERED) && service.treeMechanics() != null) {
            service.treeMechanics().gainVolatility(player, (int) Math.round(volatile_));
        }
        if (!local.isEmpty()) {
            build = build.withProfile(build.profile().withLocal(local));
        }
        if (boost) {
            for (int i = 0; i < boosts; i++) {
                build = TreeMechanics.ancestralBoost(build, sheet);
            }
            plugin.players().get(player).markRecent(tw.exilecore.stats.TreeLastKeys.EVENT_EMPOWERED, now);
            player.getWorld().spawnParticle(Particle.SOUL_FIRE_FLAME, player.getLocation().add(0, 1, 0), 12, 0.4, 0.5, 0.4, 0.02);
        }
        // 投射物：Alignment、Gambleshot、Ricochet、Wildshards、Spectral Volley
        if (build.definition().mechanic() == Mechanic.PROJECTILE) {
            build = projectiles(player, build, effective, tags, key);
        }
        return build;
    }

    /**
     * 連段（最後一擊）：上一下是 lastSkill（lastTick、到第 lastCount 下），這一下用 skill：快速連用（{@link SupportExtraKeys#FINAL_STRIKE_WINDOW} 內）
     * 同一個技能就往下一下，不然從第 1 下重來；回傳這一下是第幾下（finalAt ≤ 0 的技能沒有連段，回傳 0）。
     */
    static int comboStep(final String lastSkill, final long lastTick, final int lastCount, final String skill, final long now, final int finalAt) {
        if (finalAt <= 0) {
            return 0;
        }
        final boolean chained = skill.equals(lastSkill) && now - lastTick <= SupportExtraKeys.FINAL_STRIKE_WINDOW;
        return chained ? lastCount + 1 : 1;
    }

    /** 2026-10-27 照 PoE2 Fresh Clip：skillId 在 tick 裝填了 count 顆弩矢（SkillService 裝填完成、立即裝填、急裝、翻滾裝填時呼叫）。 */
    public void boltsReloaded(final Player player, final String skillId, final int count, final long tick) {
        if (player == null || skillId == null || count <= 0) {
            return;
        }
        final long now = now();
        final ArrayDeque<long[]> log = bolts.computeIfAbsent(key(player, skillId), k -> new ArrayDeque<>());
        log.addLast(new long[] {Math.min(now, tick), count});
        while (!log.isEmpty() && now - log.peekFirst()[0] > SupportExtraKeys.FRESH_CLIP_TICKS) {
            log.pollFirst();
        }
    }

    /** 這個玩家/技能過去 8 秒裝填了幾顆弩矢。 */
    private int reloadedRecently(final String key, final long now) {
        final ArrayDeque<long[]> log = bolts.get(key);
        if (log == null) {
            return 0;
        }
        int count = 0;
        for (final long[] each : log) {
            if (now - each[0] <= SupportExtraKeys.FRESH_CLIP_TICKS) {
                count += (int) each[1];
            }
        }
        return count;
    }

    private SkillBuild projectiles(final Player player, final SkillBuild original, final StatSheet effective, final Set<String> tags, final String key) {
        SkillBuild build = original;
        int pierce = build.pierce();
        int chains = build.chains();
        int forks = build.forks();
        int count = build.projectiles();
        double spread = build.spread();
        final List<Modifier> local = new ArrayList<>();
        final double align = flat(effective, SupportExtraKeys.ALIGNMENT, tags);
        if (align > 0) {
            // 2026-10-27 照 PoE2：原作鏡頭固定，畫面的上下左右就是世界上固定的四個方向（這裡用東南西北）；對準之後過 1 秒才指示新的方向
            //（called_shot_aiming_delay_ms 1000；這次之前沒有延遲）
            final long now = now();
            final long[] state = alignment.computeIfAbsent(key, k -> new long[] {plugin.random().nextInt(4), now});
            final int indicated = (int) state[0];
            if (now < state[1]) {
                tw.exilecore.gui.ActionBar.send(player, plugin.text().parse("<gray>下一個方向 " + direction(indicated) + "（瞄準中…）"));
            } else if (alignmentMatches(SkillTargets.facing(player), indicated)) {
                count += (int) Math.round(align);
                int next = plugin.random().nextInt(3);
                next = next >= indicated ? next + 1 : next;
                alignment.put(key, new long[] {next, now + SupportExtraKeys.ALIGNMENT_DELAY_TICKS});
                tw.exilecore.gui.ActionBar.send(player, plugin.text().parse("<#ffd36b>對準了！1 秒後指示下一個方向：" + direction(next)));
            } else {
                tw.exilecore.gui.ActionBar.send(player, plugin.text().parse("<gray>指示方向：" + direction(indicated)));
            }
        }
        if (StatKeys.flag(effective, SupportExtraKeys.FLAG_GAMBLESHOT)) {
            switch (plugin.random().nextInt(3)) {
                case 0 -> forks++;
                case 1 -> chains++;
                default -> pierce++;
            }
        }
        if (StatKeys.flag(effective, SupportExtraKeys.FLAG_RICOCHET)) {
            final int base = build.definition().params().projectiles().intAt(build.level());
            final int extra = Math.max(0, count - Math.max(1, base));
            count = Math.max(1, Math.min(count, base));
            if (extra > 0) {
                local.add(Modifier.flat(StatKeys.CHAIN_TERRAIN, 100.0 * extra, "跳彈"));
            }
        }
        final double shards = flat(effective, SupportExtraKeys.WILDSHARDS, tags);
        if (shards > 0 && roll(shards)) {
            count += SupportExtraKeys.WILDSHARDS_COUNT;
            spread = 360;
        }
        count = Math.max(1, Math.min(SkillBuild.MAX_PROJECTILES, count));
        if (count != build.projectiles() || pierce != build.pierce() || chains != build.chains() || forks != build.forks() || spread != build.spread()) {
            if (spread < 1 && count > 1) {
                spread = Math.min(60, 12.0 * (count - 1));
            }
            build = new SkillBuild(build.definition(), build.level(), build.gemLevel(), build.isDefault(), build.supports(), build.profile(),
                    build.reach(), build.angle(), build.targets(), build.knockback(), count, spread, build.speed(), build.range(), build.size(),
                    Math.min(20, pierce), chains, build.explode(), build.wobble(), build.arrow(), build.radius(), build.length(), build.chainRange(),
                    build.manaCost(), build.areaScale(), build.bonusLevel(), build.cooldownTicks(), build.durationScale(), Math.min(3, forks),
                    build.returning(), build.echo(), build.deploy(), build.totems(), build.minions(), build.reserve(), build.minionReserve(),
                    build.auraEffect(), build.generosity(), build.exerts(), build.repeats());
        }
        if (!local.isEmpty()) {
            build = build.withProfile(build.profile().withLocal(local));
        }
        // Spectral Volley：一般的這一下更少 60%；存一發光影投射物（之後 3 秒沒有再存就全部射出去，更多 20%）
        if (StatKeys.flag(effective, SupportExtraKeys.FLAG_SPECTRAL)) {
            final Spectral stored = spectral.computeIfAbsent(key, k -> new Spectral());
            stored.count = Math.min(SupportExtraKeys.SPECTRAL_MAX, stored.count + 1);
            stored.lastCreated = now();
            stored.build = build.withProfile(build.profile().scaled(1 + flat(effective, SupportExtraKeys.SPECTRAL_MORE, tags) / 100.0, "光影投射物"));
            final LivingEntity aimed = service.targets().aimed(Origin.of(player), Math.max(8, build.range()), 1.2);
            stored.target = aimed == null ? null : aimed.getUniqueId();
            build = build.withProfile(build.profile().scaled(0.4, "光影齊射"));
        }
        return build;
    }

    /** Alignment：面向 facing 有沒有對準指示的方向（0 北、1 東、2 南、3 西；左右 {@link SupportExtraKeys#ALIGNMENT_ANGLE} 度內）。 */
    static boolean alignmentMatches(final Vector facing, final int indicated) {
        final double yaw = Math.toDegrees(Math.atan2(-facing.getX(), facing.getZ())); // 0 = 南（+z），照原版的 yaw
        final double wanted = new double[] {180, -90, 0, 90}[indicated]; // 北、東、南、西
        double diff = Math.abs(yaw - wanted) % 360;
        diff = diff > 180 ? 360 - diff : diff;
        return diff <= SupportExtraKeys.ALIGNMENT_ANGLE;
    }

    private static String direction(final int index) {
        return switch (index) {
            case 0 -> "北";
            case 1 -> "東";
            case 2 -> "南";
            default -> "西";
        };
    }

    // =====================================================================
    // 放完之後（SupportMechanics.afterEmpowered）
    // =====================================================================

    void afterCast(final Player player, final SkillBuild build, final LivingEntity preferred) {
        final StatSheet effective = effective(player, build);
        final Set<String> tags = build.profile().tags();
        final long now = now();
        // Spell Cascade：往目標區域兩側各落一次（範圍更少）
        final double cascades = flat(effective, SupportExtraKeys.CASCADE, tags);
        if (cascades > 0 && SupportGuards.cascadable(build.definition())) {
            final double areaLess = flat(effective, SupportExtraKeys.CASCADE_AREA, tags) / 100.0;
            final SkillBuild smaller = build.withAreaAndProjectiles(build.areaScale() * Math.sqrt(Math.max(0.1, 1 - areaLess)), build.projectiles());
            final double angle = plugin.random().nextDouble() * Math.PI;
            final double offset = Math.max(1.5, Math.max(build.radius(), 1.5) * 2);
            final Origin self = Origin.of(player);
            final Location center = build.definition().mechanic() == Mechanic.NOVA ? player.getLocation() : null;
            for (int i = 1; i <= (int) Math.round(cascades); i++) {
                final Vector axis = new Vector(Math.cos(angle), 0, Math.sin(angle)).multiply(offset * i);
                for (final int side : new int[] {1, -1}) {
                    final Vector shift = axis.clone().multiply(side);
                    final Origin moved = new Origin(player, self.feet().clone().add(shift), self.eye().clone().add(shift), self.direction(), false);
                    plugin.getServer().getScheduler().runTaskLater(plugin, () -> {
                        if (player.isOnline() && player.getWorld().equals(moved.feet().getWorld())) {
                            service.executor().executeFrom(moved, smaller, null);
                        }
                    }, 3L * i);
                }
            }
            if (center != null) {
                center.getWorld().spawnParticle(Particle.ENCHANT, center.add(0, 1, 0), 20, 1, 0.3, 1, 0.2);
            }
        }
        // Perfection、Window of Opportunity：完美時機
        if (SupportDefinition.PERFECT_TIMING.contains(build.definition().id())) {
            final boolean perfect = build.profile().id().endsWith(SupportProcs.PERFECT_SUFFIX);
            final List<Long> stacks = perfection.computeIfAbsent(player.getUniqueId(), uuid -> new ArrayList<>());
            if (perfect) {
                final int gain = (int) Math.round(flat(effective, SupportExtraKeys.PERFECTION, tags));
                for (int i = 0; i < gain; i++) {
                    if (stacks.size() >= SupportExtraKeys.PERFECTION_MAX) {
                        stacks.sort(Long::compare);
                        stacks.removeFirst();
                    }
                    stacks.add(now + SupportExtraKeys.PERFECTION_TICKS);
                }
                final double deflect = flat(effective, SupportExtraKeys.PERFECT_DEFLECT, tags);
                if (deflect > 0) {
                    service.buff(player, "support.window", "良機", 160, List.of(Modifier.flat(StatKeys.DEFLECTION_FROM_EVASION, deflect, "良機")));
                }
            } else {
                stacks.clear(); // 沒抓到完美時機：完美全部失去
            }
            refreshPerfection(player, stacks, now);
        }
        // Cursed Ground：2026-10-27 改在詛咒落下時接手（cursedGround，SkillExecutor.applyCurseAt 呼叫）
        // Stomping Ground：2026-10-27 照 PoE2 每走一步一陣震波——瞬間到位的突進照路線每 1.5 格補一陣；跳躍起跳一陣（落地在 onUse）；
        // 按住衝的在 sample 照走的距離；這次之前起點、終點各一陣
        final Stomp stomp = stomps.get(player.getUniqueId());
        if (stomp != null && stomp.skillId.equals(build.definition().id()) && flat(effective, SupportExtraKeys.STOMPING, tags) > 0) {
            final Location to = player.getLocation();
            lastAt.put(player.getUniqueId(), to); // 瞬間到位的那一段不要在 sample 再算一次
            if (build.definition().mechanic() == Mechanic.LEAP) {
                stompAt(player, stomp, stomp.from);
            } else if (to.getWorld() == stomp.from.getWorld() && to.distance(stomp.from) >= SupportExtraKeys.STOMPING_STEP) {
                final Vector path = to.toVector().subtract(stomp.from.toVector());
                final int steps = stompSteps(path.length());
                for (int i = 1; i <= steps; i++) {
                    stompAt(player, stomp, stomp.from.clone().add(path.clone().multiply(i * SupportExtraKeys.STOMPING_STEP / path.length())));
                }
                stomp.walked = 0;
            }
        }
        // Refraction：2026-10-27 照 PoE2：插下的戰旗的範圍（插旗的地方、旗子的半徑、持續時間）內的敵人；這次之前用你身邊（臨在 1.5 倍）
        final double refraction = flat(effective, SupportExtraKeys.REFRACTION, tags);
        if (refraction > 0 && build.definition().warcry() != null) {
            final WarcrySpec spec = build.definition().warcry();
            banners.removeIf(banner -> banner.owner().equals(player.getUniqueId()) && banner.untilTick() <= now);
            banners.add(new Banner(player.getUniqueId(), player.getLocation(), Warcries.baseRadius(spec, effective, tags) * build.areaScale(),
                    now + build.durationTicks(spec.duration()), refraction));
        }
    }

    /** Stomping Ground：走了 distance 格是幾步。 */
    static int stompSteps(final double distance) {
        return (int) Math.floor(Math.max(0, distance) / SupportExtraKeys.STOMPING_STEP + 1e-9);
    }

    /** 這一步震出一陣震波（2 格、武器攻擊傷害的 50%；skills.yml 的 proc_stomping_ground）。 */
    private void stompAt(final Player player, final Stomp stomp, final Location at) {
        castFree(player, "proc_stomping_ground", stomp.skillId, groundAt(at), null, 1.0);
    }

    private static Location groundAt(final Location at) {
        final Location spot = at.clone();
        spot.setY(Math.floor(spot.getY()));
        return spot;
    }

    private void refreshPerfection(final Player player, final List<Long> stacks, final long now) {
        stacks.removeIf(until -> until <= now);
        if (stacks.isEmpty()) {
            service.buffs().remove(player, "support.perfection");
            return;
        }
        long first = Long.MAX_VALUE;
        for (final long until : stacks) {
            first = Math.min(first, until);
        }
        service.buff(player, "support.perfection", "完美 ×" + stacks.size(), (int) Math.max(1, first - now),
                List.of(Modifier.more(StatKeys.DAMAGE, SupportExtraKeys.PERFECTION_MORE * stacks.size() / 100.0, "完美")));
    }

    /**
     * 2026-10-27 照 PoE2 Cursed Ground：掛了咒地的詛咒落下時不直接詛咒，改成留下一塊詛咒地面（詛咒的範圍、持續時間；最多 x 塊，
     * 多的拿掉最舊的），站在裡面的敵人一直被詛咒（tickGrounds）。有接手回傳 true（SkillExecutor.applyCurseAt 就不照常詛咒）。
     */
    boolean cursedGround(final Player player, final SkillBuild build, final Location center, final SkillParams.Mark spec) {
        final StatSheet effective = effective(player, build);
        final int max = (int) Math.round(flat(effective, SupportExtraKeys.CURSED_GROUND, build.profile().tags()));
        if (max <= 0 || build.definition().hidden()) {
            return false;
        }
        final long now = now();
        final List<Ground> mine = new ArrayList<>();
        for (final Ground ground : grounds) {
            if (ground.owner.equals(player.getUniqueId()) && ground.kind == 1) {
                mine.add(ground);
            }
        }
        while (mine.size() >= max) {
            grounds.remove(mine.removeFirst());
        }
        final double radius = spec.radius(build.level()) * build.areaScale();
        final long ticks = Math.max(20, build.durationTicks(spec.duration(build.level())));
        grounds.add(new Ground(player.getUniqueId(), 1, center.clone(), radius, now + ticks, build, spec));
        service.executor().applyCurseAt(player, build, center, spec, now + 15);
        return true;
    }

    /** Holy Descent：跳躍落地（SupportProcs.onUse 的跳躍在落地時才呼叫）。 */
    void onUse(final Player player, final SkillDefinition definition, final Location at) {
        if (definition.mechanic() != Mechanic.LEAP) {
            return;
        }
        // Stomping Ground：跳躍落地是第二步
        final Stomp stomp = stomps.remove(player.getUniqueId());
        if (stomp != null && stomp.skillId.equals(definition.id())) {
            stompAt(player, stomp, at);
        }
        final double seconds = service.supportFlat(player, definition.id(), SupportExtraKeys.HOLY_DESCENT);
        if (seconds > 0) {
            grounds.add(new Ground(player.getUniqueId(), 0, at.clone(), SupportExtraKeys.CONSECRATED_RADIUS, now() + Math.round(seconds * 20), null, null));
            at.getWorld().spawnParticle(Particle.END_ROD, at.clone().add(0, 0.3, 0), 20, 1, 0.1, 1, 0.01);
        }
    }

    /** Astral Projection：新星技能改在準心指的地方放；有接手回傳 true（SkillService 就不另外放）。 */
    boolean astral(final Player player, final SkillBuild build, final LivingEntity preferred) {
        if (build.definition().mechanic() != Mechanic.NOVA || !StatKeys.flag(effective(player, build), SupportExtraKeys.FLAG_ASTRAL)) {
            return false;
        }
        final Origin origin = Origin.of(player);
        final double range = 12;
        final LivingEntity aimed = preferred != null ? preferred : service.targets().aimed(origin, range, 1.2);
        final Location at = aimed != null ? aimed.getLocation() : service.targets().aimPoint(origin, range);
        final Vector direction = SkillTargets.facing(player);
        service.executor().executeFrom(Origin.at(player, at, direction), build, aimed);
        at.getWorld().spawnParticle(Particle.PORTAL, at.clone().add(0, 1, 0), 20, 0.5, 0.5, 0.5, 0.2);
        return true;
    }

    /** 技能面板上這個技能能用的等級（沒有回傳 1）。 */
    private int levelOf(final Player player, final String skillId) {
        final PlayerState state = plugin.players().get(player);
        final int position = state.skills().find(skillId);
        return position < 0 ? 1 : GemAttributes.usableLevel(service.config(), state.skills().skill(position), plugin.players().sheet(player));
    }

    /** 放一個隱藏技能（不花魔力；scale = 傷害倍率）。 */
    private void castFree(final Player player, final String subId, final String levelFrom, final Location at, final LivingEntity target,
                          final double scale) {
        final SkillDefinition sub = service.config().skill(subId);
        if (sub == null) {
            return;
        }
        final PlayerState state = plugin.players().get(player);
        final int position = state.skills().find(levelFrom);
        final int level = position < 0 ? 1 : GemAttributes.usableLevel(service.config(), state.skills().skill(position), plugin.players().sheet(player));
        SkillBuild build = SkillBuild.of(service.config(), sub, level, state.level(), false, List.of(), state.weaponTags(), plugin.players().sheet(player));
        if (scale != 1.0) {
            build = build.withProfile(build.profile().scaled(scale, sub.name()));
        }
        final Vector direction = target != null ? SkillTargets.center(target).toVector().subtract(at.toVector()) : SkillTargets.facing(player);
        service.procCast(Origin.at(player, at.clone().add(0, 0.2, 0), direction.lengthSquared() < 1e-6 ? SkillTargets.facing(player) : direction.normalize()),
                build, target);
    }

    // =====================================================================
    // 擊中前（SupportMechanics.preHit）
    // =====================================================================

    List<Modifier> preHit(final Player attacker, final LivingEntity defender, final StatSheet skillSheet, final Set<String> tags, final SkillProfile skill) {
        final List<Modifier> list = new ArrayList<>();
        final long now = now();
        final UUID target = defender.getUniqueId();
        final boolean ailments = plugin.ailments() != null;
        // Blindside：對致盲的敵人暴擊率、暴擊加成更多
        final double blind = skillSheet.flat(SupportExtraKeys.CRIT_VS_BLINDED, tags);
        if (blind > 0 && ailments && plugin.ailments().blinded(defender)) {
            list.add(Modifier.more(StatKeys.CRIT_CHANCE, blind / 100.0, "攻其不備"));
            list.add(Modifier.more(StatKeys.CRIT_BONUS, blind / 100.0, "攻其不備"));
        }
        int flags = 0;
        // Biting Frost：用掉凍結，這一下更多 x%（傳奇再更多 y%）
        final double frost = skillSheet.flat(SupportExtraKeys.CONSUME_FREEZE, tags);
        if (frost > 0 && ailments && plugin.ailments().isFrozen(defender)) {
            list.add(Modifier.more(StatKeys.DAMAGE, frost / 100.0, "噬寒"));
            final MonsterData data = plugin.monsters() == null ? null : plugin.monsters().data(defender).orElse(null);
            final double unique = skillSheet.flat(SupportExtraKeys.CONSUME_FREEZE_UNIQUE, tags);
            if (unique > 0 && data != null && (data.boss() || data.rarity() == MonsterRarity.UNIQUE)) {
                list.add(Modifier.more(StatKeys.DAMAGE, unique / 100.0, "噬寒"));
            }
            flags |= CONSUME_FREEZE_FLAG;
        }
        // Exploit Weakness：用掉完全破甲，這一下更多 x%
        final double broken = skillSheet.flat(SupportExtraKeys.CONSUME_BROKEN, tags);
        if (broken > 0 && ailments && Double.isInfinite(plugin.ailments().armourBroken(defender))) {
            list.add(Modifier.more(StatKeys.DAMAGE, broken / 100.0, "乘虛"));
            flags |= CONSUME_BROKEN_FLAG;
        }
        // Opening Move：打中滿血的敵人（擊中後讓牠眩暈）
        if (skillSheet.flat(SupportExtraKeys.DAZE_FULL_LIFE, tags) > 0 && SupportMechanics.fullLife(defender)) {
            flags |= FULL_LIFE_FLAG;
        }
        if (flags != 0) {
            preHitFlags.merge(target, flags, (a, b) -> a | b);
        }
        // Pursuit／Retreat：最近 2 秒投射物攻擊（近戰）擊中過
        final PlayerState state = plugin.players().get(attacker);
        final double pursuit = skillSheet.flat(SupportExtraKeys.PURSUIT, tags);
        if (pursuit > 0 && tags.contains(Tags.MELEE) && now - state.recentTick(tw.exilecore.stats.Recently.PROJECTILE_HIT) <= SupportExtraKeys.PURSUIT_TICKS) {
            list.add(new Modifier(StatKeys.DAMAGE, ModType.MORE, pursuit / 100.0, Set.of(Tags.MELEE), "追擊"));
        }
        final double retreat = skillSheet.flat(SupportExtraKeys.RETREAT, tags);
        if (retreat > 0 && tags.contains(Tags.PROJECTILE) && now - state.recentTick(tw.exilecore.stats.Recently.MELEE_HIT) <= SupportExtraKeys.PURSUIT_TICKS) {
            list.add(new Modifier(StatKeys.DAMAGE, ModType.MORE, retreat / 100.0, Set.of(Tags.PROJECTILE), "撤擊"));
        }
        // Inexorable Critical：每秒增加暴擊率、暴擊加成更少，暴擊時歸零
        final double inexorable = skillSheet.flat(SupportExtraKeys.INEXORABLE, tags);
        if (inexorable > 0 && skill != null) {
            final String key = key(attacker, SupportProcs.baseId(skill.id()));
            final long since = critSince.computeIfAbsent(key, k -> now);
            final double seconds = Math.floor((now - since) / 20.0);
            list.add(Modifier.increased(StatKeys.CRIT_CHANCE, Math.min(SupportExtraKeys.INEXORABLE_CAP, inexorable * seconds) / 100.0, "必然暴擊"));
            final double less = Math.min(SupportExtraKeys.INEXORABLE_BONUS_CAP, skillSheet.flat(SupportExtraKeys.INEXORABLE_BONUS, tags) * seconds);
            if (less > 0) {
                list.add(Modifier.more(StatKeys.CRIT_BONUS, -less / 100.0, "必然暴擊"));
            }
        }
        // Defy：被包圍時攻擊的擊中傷害幸運
        if (StatKeys.flag(skillSheet, SupportExtraKeys.FLAG_LUCKY_SURROUNDED) && tags.contains(Tags.ATTACK)
                && plugin.players().conditions().test(attacker, state, tw.exilecore.stats.TreeConditions.SURROUNDED, now)) {
            list.add(Modifier.flat(tw.exilecore.stats.KeystoneKeys.LUCKY, 100, "抗命"));
        }
        // Window of Opportunity：完美時機放開的那一下
        final double perfect = skillSheet.flat(SupportExtraKeys.PERFECT_MORE, tags);
        if (perfect > 0 && skill != null && skill.id().contains(SupportProcs.PERFECT_SUFFIX)) {
            list.add(Modifier.more(StatKeys.DAMAGE, perfect / 100.0, "良機"));
        }
        // Derange：非異常的持續傷害（地面效果）每 100 智慧更多 x%
        final double derange = skillSheet.flat(SupportExtraKeys.DERANGE, tags);
        if (derange > 0 && skill != null && skill.id().contains(".zone")) {
            final double intelligence = Math.max(0, plugin.players().sheet(attacker).get(StatKeys.INT));
            list.add(Modifier.more(StatKeys.DAMAGE, derange * Math.floor(intelligence / SupportExtraKeys.DERANGE_PER) / 100.0, "狂亂"));
        }
        // Direstrike：被輔助的常駐增益開著時，你在低血時攻擊傷害增加 x%
        if (tags.contains(Tags.ATTACK) && plugin.players().conditions().test(attacker, state, tw.exilecore.stats.TreeConditions.LOW_LIFE, now)) {
            final double dire = direstrike(attacker, state);
            if (dire > 0) {
                list.add(new Modifier(StatKeys.DAMAGE, ModType.INCREASED, dire / 100.0, Set.of(Tags.ATTACK), "絕擊"));
            }
        }
        // Retaliate：攻擊傷害照你的重暈條最多更多 x%
        final double poise = skillSheet.flat(SupportExtraKeys.POISE_DAMAGE, tags);
        if (poise > 0 && tags.contains(Tags.ATTACK) && ailments) {
            final double fraction = plugin.ailments().stunBuildup(attacker);
            if (fraction > 0) {
                list.add(new Modifier(StatKeys.DAMAGE, ModType.MORE, poise * fraction / 100.0, Set.of(Tags.ATTACK), "還擊"));
            }
        }
        // Punch Through：被擊退過的敵人（好目標）：投射物攻擊擊中增加傷害，用掉
        if (tags.contains(Tags.PROJECTILE) && tags.contains(Tags.ATTACK) && debuffs() != null) {
            for (final Debuffs.Debuff debuff : debuffs().active(target, now)) {
                if (debuff.id().equals(EASY_TARGET) && debuff.source().equals(attacker.getUniqueId())) {
                    list.add(Modifier.increased(StatKeys.DAMAGE, debuff.taken(), "好目標"));
                    debuffs().remove(target, EASY_TARGET, attacker.getUniqueId(), now);
                    break;
                }
            }
        }
        return list;
    }

    static final String EASY_TARGET = "support.easy_target";

    /** Direstrike：開著的常駐增益掛的絕擊（取最大的）。 */
    private double direstrike(final Player player, final PlayerState state) {
        double best = 0;
        for (int i = 0; i < tw.exilecore.character.SkillLoadout.MAX_SKILLS; i++) {
            final tw.exilecore.character.SkillLoadout.Entry entry = state.skills().skill(i);
            if (entry == null || !service.auraActive(player, entry.skillId())) {
                continue;
            }
            for (final SupportDefinition support : service.supportsOf(entry)) {
                for (final Modifier modifier : support.modifiers()) {
                    if (modifier.stat().equals(SupportExtraKeys.DIRESTRIKE)) {
                        best = Math.max(best, modifier.value());
                    }
                }
            }
        }
        return best;
    }

    /** 額外破甲（SupportMechanics.extraBreak）：Outmaneuver（對被招架的）。 */
    double extraBreak(final Player player, final StatSheet skillSheet, final Set<String> context, final LivingEntity defender) {
        final double parried = skillSheet.flat(SupportExtraKeys.OUTMANEUVER, context);
        if (parried > 0 && debuffs() != null && debuffs().has(defender.getUniqueId(), PARRIED, now())) {
            return parried;
        }
        return 0;
    }

    /** 招架造成的減益代號（ChargeEffects 的招架）。 */
    static final String PARRIED = "parried";

    /** Thrill of the Kill：撲殺時 8 秒內攻擊獲得 x% 傷害為額外閃電（SupportMechanics.onCull）。 */
    void onCull(final Player player, final StatSheet skillSheet, final Set<String> tags) {
        final double thrill = skillSheet.flat(SupportExtraKeys.THRILL, tags);
        if (thrill <= 0) {
            return;
        }
        final List<Modifier> mods = new ArrayList<>();
        mods.add(new Modifier(StatKeys.damageExtra(DamageType.LIGHTNING), ModType.FLAT, thrill, Set.of(Tags.ATTACK), "殺意"));
        final double shock = skillSheet.flat(SupportExtraKeys.THRILL_SHOCK, tags);
        if (shock > 0) {
            mods.add(Modifier.increased(StatKeys.SHOCK_CHANCE, shock / 100.0, "殺意"));
        }
        service.buff(player, "support.thrill", "殺意", (int) SupportExtraKeys.THRILL_TICKS, mods);
    }

    /** 印記放上去了（SupportMechanics.markApplied）：Mark of Siphoning。 */
    void markApplied(final Player player, final StatSheet effective, final Set<String> tags, final LivingEntity target, final long untilTick) {
        siphons.values().removeIf(each -> each.owner().equals(player.getUniqueId()));
        final double percent = effective.flat(SupportExtraKeys.MARK_SIPHON, tags);
        if (percent > 0) {
            siphons.put(target.getUniqueId(), new Siphon(player.getUniqueId(), percent, untilTick));
        }
    }

    // =====================================================================
    // 異常的條件加成（SupportMechanics.ailmentSheet）
    // =====================================================================

    void ailmentExtra(final Player player, final StatSheet skillSheet, final SkillProfile skill, final LivingEntity defender,
                      final List<Modifier> extra) {
        final Set<String> tags = skill.tags();
        final boolean ailments = plugin.ailments() != null;
        // Malady：流血、中毒的基礎機率互換
        if (StatKeys.flag(skillSheet, SupportExtraKeys.FLAG_MALADY)) {
            final double bleed = skillSheet.flat(StatKeys.BLEED_CHANCE, tags);
            final double poison = skillSheet.flat(StatKeys.POISON_CHANCE, tags);
            if (bleed != poison) {
                extra.add(Modifier.flat(StatKeys.BLEED_CHANCE, poison - bleed, "病變"));
                extra.add(Modifier.flat(StatKeys.POISON_CHANCE, bleed - poison, "病變"));
            }
        }
        // Stormfire：2026-10-27 照 PoE2 改成被這個技能感電的敵人，點燃每一跳另外受到 x% 的閃電傷害（stormfire，AilmentService 讀）；
        // 這次之前平衡等效成點燃更多 x%
        // Commiserate：你身上有同一種異常時，施加的那種更多 x%（並移除你身上的）
        final double share = skillSheet.flat(SupportExtraKeys.COMMISERATE, tags);
        if (share > 0 && ailments) {
            final double more = share / 100.0;
            final boolean[] cured = new boolean[1];
            for (final Object[] pair : new Object[][] {{AilmentType.IGNITE, StatKeys.IGNITE_MAGNITUDE}, {AilmentType.BLEED, StatKeys.BLEED_MAGNITUDE},
                    {AilmentType.POISON, StatKeys.POISON_MAGNITUDE}, {AilmentType.CHILL, StatKeys.CHILL_EFFECT}, {AilmentType.SHOCK, StatKeys.SHOCK_EFFECT}}) {
                final AilmentType type = (AilmentType) pair[0];
                if (plugin.ailments().has(player, type)) {
                    extra.add(Modifier.more((String) pair[1], more, "同病相憐"));
                    cured[0] = true;
                    plugin.getServer().getScheduler().runTask(plugin, () -> {
                        if (plugin.ailments() != null && plugin.ailments().has(defender, type)) {
                            plugin.ailments().cure(player, type);
                        }
                    });
                }
            }
        }
        // Drain Ailments：施加持續傷害時用掉敵人一種異常，持續傷害更多 x%
        final double drain = skillSheet.flat(SupportExtraKeys.DRAIN_AILMENTS, tags);
        final double debuff = skillSheet.flat(StatKeys.DEBUFF_CHAOS, tags);
        final double debuffBase = skillSheet.flat(tw.exilecore.stats.SkillMechanicKeys.DEBUFF_CHAOS_BASE, tags);
        double dotMore = 1;
        if (drain > 0 && ailments && (debuff > 0 || debuffBase > 0)) {
            final List<AilmentType> present = new ArrayList<>();
            for (final AilmentType type : new AilmentType[] {AilmentType.IGNITE, AilmentType.CHILL, AilmentType.FREEZE, AilmentType.SHOCK,
                    AilmentType.BLEED, AilmentType.POISON}) {
                if (plugin.ailments().has(defender, type)) {
                    present.add(type);
                }
            }
            if (!present.isEmpty()) {
                final AilmentType pick = present.get(plugin.random().nextInt(present.size()));
                plugin.getServer().getScheduler().runTask(plugin, () -> {
                    if (plugin.ailments() != null && defender.isValid()) {
                        plugin.ailments().cure(defender, pick);
                    }
                });
                dotMore *= 1 + drain / 100.0;
            }
        }
        // Derange：每 100 智慧非異常持續傷害更多 x%
        final double derange = skillSheet.flat(SupportExtraKeys.DERANGE, tags);
        if (derange > 0 && (debuff > 0 || debuffBase > 0)) {
            final double intelligence = Math.max(0, plugin.players().sheet(player).get(StatKeys.INT));
            dotMore *= 1 + derange * Math.floor(intelligence / SupportExtraKeys.DERANGE_PER) / 100.0;
        }
        if (dotMore != 1) {
            if (debuff > 0) {
                extra.add(Modifier.flat(StatKeys.DEBUFF_CHAOS, debuff * (dotMore - 1), "持續傷害"));
            }
            if (debuffBase > 0) {
                extra.add(Modifier.flat(tw.exilecore.stats.SkillMechanicKeys.DEBUFF_CHAOS_BASE, debuffBase * (dotMore - 1), "持續傷害"));
            }
        }
        // Incision：最近每用掉一層切割流血更多 x%（最多 30%）
        final double incision = skillSheet.flat(SupportExtraKeys.INCISION_BLEED, tags);
        if (incision > 0) {
            final ArrayDeque<long[]> log = incisions.get(player.getUniqueId());
            int consumed = 0;
            if (log != null) {
                final long now = now();
                log.removeIf(each -> now - each[0] > StatKeys.RECENTLY_TICKS);
                for (final long[] each : log) {
                    consumed += (int) each[1];
                }
            }
            final double more = Math.min(SupportExtraKeys.INCISION_BLEED_CAP, incision * consumed);
            if (more > 0) {
                extra.add(Modifier.more(StatKeys.BLEED_MAGNITUDE, more / 100.0, "切口"));
            }
        }
        // Spar、Retaliate：暈眩像多造成「你重暈條的百分比」的傷害
        final double poiseStun = skillSheet.flat(SupportExtraKeys.POISE_STUN, tags);
        if (poiseStun > 0 && ailments) {
            final double fraction = plugin.ailments().stunBuildup(player);
            if (fraction > 0) {
                extra.add(Modifier.more(StatKeys.STUN_BUILDUP, poiseStun * fraction / 100.0, "切磋"));
            }
        }
        // Syzygy：對點燃中而且完全破甲的敵人有重壓擊（蓄勢待暈直接重暈）
        extra.addAll(syzygy(skillSheet, defender));
        // Rime：2026-10-27 照 PoE2：站在你掛了霧凇的技能留下的冰緩地面上的敵人，被你任何技能擊中時凍結累積像多 x% 傷害；
        // 這次之前照「冰緩中的敵人」、而且只算掛了霧凇的那個技能自己的擊中
        final double rime = service.zones() == null ? 0 : service.zones().groundFlat(player.getUniqueId(), defender.getLocation(), SupportExtraKeys.RIME);
        if (rime > 0) {
            extra.add(Modifier.more(StatKeys.FREEZE_BUILDUP, rime / 100.0, "霧凇"));
        }
    }

    // =====================================================================
    // 事件（SupportMechanics.event：重暈、凍結…）
    // =====================================================================

    void event(final Player player, final String skillId, final String event, final LivingEntity target) {
        final String id = SupportProcs.baseId(skillId);
        // Vanguard（2026-10-27 照 PoE2）：輕暈敵人時每 10 力量 x 守護；重暈時另外 y（這次之前怪物的輕暈沒有事件，x＋y 合併在重暈時給）
        if (event != null && VANGUARD_EVENTS.contains(event)) {
            final double vanguard = service.supportFlat(player, id, SupportExtraKeys.VANGUARD)
                    + ("stun_applied".equals(event) ? service.supportFlat(player, id, SupportExtraKeys.VANGUARD_HEAVY) : 0);
            if (vanguard > 0) {
                final double strength = Math.max(0, plugin.players().sheet(player).get(StatKeys.STR));
                plugin.players().get(player).addGuard(vanguard * Math.floor(strength / 10), now() + SupportExtraKeys.VANGUARD_TICKS);
            }
        }
        if (!"stun_applied".equals(event)) {
            return;
        }
        // Practical Magic：重暈敵人時 8 秒內法術傷害增加 x%
        final double magic = service.supportFlat(player, id, SupportExtraKeys.PRACTICAL_MAGIC);
        if (magic > 0) {
            service.buff(player, "support.practical_magic", "實用魔法", (int) SupportExtraKeys.PRACTICAL_MAGIC_TICKS,
                    List.of(new Modifier(StatKeys.DAMAGE, ModType.INCREASED, magic / 100.0, Set.of(Tags.SPELL), "實用魔法")));
        }
        // Syzygy：重暈點燃中而且完全破甲的敵人：永久恫嚇
        if (target != null && service.supportFlat(player, id, SupportExtraKeys.FLAG_SYZYGY) > 0 && plugin.ailments() != null && debuffs() != null
                && plugin.ailments().has(target, AilmentType.IGNITE) && Double.isInfinite(plugin.ailments().armourBroken(target))) {
            debuffs().apply(target.getUniqueId(), new Debuffs.Debuff("intimidate", player.getUniqueId(), now() + 20L * 60 * 30,
                    tw.exilecore.stats.TreeLastKeys.INTIMIDATE_TAKEN, false));
        }
    }

    /** 輕暈（AilmentService 給的 light_stun_applied）、重暈（stun_applied）。 */
    static final Set<String> VANGUARD_EVENTS = Set.of("light_stun_applied", "stun_applied");

    /** Syzygy：對點燃中而且完全破甲的敵人有重壓擊（擊中蓄勢待暈的敵人直接重暈）。CombatListener 的條件修飾器之後不能改情境，這裡在擊中前加。 */
    List<Modifier> syzygy(final StatSheet skillSheet, final LivingEntity defender) {
        if (StatKeys.flag(skillSheet, SupportExtraKeys.FLAG_SYZYGY) && plugin.ailments() != null && plugin.ailments().has(defender, AilmentType.IGNITE)
                && Double.isInfinite(plugin.ailments().armourBroken(defender))) {
            return List.of(Modifier.flat(StatKeys.FLAG_CRUSH_PRIMED, 1, "合軌"));
        }
        return List.of();
    }

    // =====================================================================
    // 擊中之後
    // =====================================================================

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onHit(final HitEvent event) {
        final long now = now();
        final LivingEntity defender = event.defender();
        // 玩家挨打：格擋（Ancestral Aid）、被擊中（Deadly Resolve）
        if (defender instanceof Player player) {
            if (barrierUntil.getOrDefault(player.getUniqueId(), 0L) >= now) {
                keepRecharging(player, now); // Energy Barrier：被暈眩後那幾秒受傷不打斷回充
            }
            if (event.result().blocked()) {
                blocks.merge(player.getUniqueId(), 1, Integer::sum);
            } else if (event.result().hit() && !(event.attacker() instanceof Player)) {
                deadlyResolve(player, event.attacker(), now);
            }
            return;
        }
        if (!event.result().hit() || event.result().blocked() || event.skill() == null) {
            return;
        }
        // 召喚物打的：Minion Splash、Crazed Minions
        if (!(event.attacker() instanceof Player player)) {
            minionHit(event);
            return;
        }
        final SkillProfile skill = event.skill();
        // 荊棘反彈：記下來（Barbs、Quill Burst）
        if (tw.exilecore.combat.Thorns.ID.equals(skill.id())) {
            thorns.put(player.getUniqueId(), new long[] {now, 1, 1});
            return;
        }
        if (plugin.monsters() == null) {
            return;
        }
        final MonsterData monster = plugin.monsters().data(defender).orElse(null);
        if (monster == null) {
            return;
        }
        final StatSheet effective = skill.effective(plugin.players().sheet(player));
        final Set<String> tags = skill.tags();
        final UUID target = defender.getUniqueId();
        final HitResult result = event.result();
        final boolean ailments = plugin.ailments() != null;
        final Debuffs debuffs = debuffs();
        lastHits.put(target, new LastHit(player.getUniqueId(), skill, effective, ailments && plugin.ailments().has(defender, AilmentType.BLEED),
                ailments && plugin.ailments().has(defender, AilmentType.SHOCK), now));
        if (lastHits.size() > 2048) {
            lastHits.values().removeIf(each -> now - each.tick() > 40);
        }
        // 擊中前記下的：用掉凍結（留下冰緩）、用掉完全破甲、打中滿血的
        final int flags = preHitFlags.getOrDefault(target, 0);
        preHitFlags.remove(target);
        if ((flags & CONSUME_FREEZE_FLAG) != 0 && ailments) {
            plugin.ailments().cure(defender, AilmentType.FREEZE);
            plugin.ailments().groundStatusOnMonster(defender, 0.3, 0, 80);
        }
        if ((flags & CONSUME_BROKEN_FLAG) != 0 && ailments) {
            plugin.ailments().consumeFullBreak(defender);
        }
        if ((flags & FULL_LIFE_FLAG) != 0 && debuffs != null) {
            daze(player, defender, now);
        }
        // Concussive Spells：物理傷害暴擊時眩暈
        if (result.crit() && effective.flat(SupportExtraKeys.DAZE_PHYSICAL_CRIT, tags) > 0 && result.dealt().get(DamageType.PHYSICAL) > 0 && debuffs != null) {
            daze(player, defender, now);
        }
        // 曝露：冰冷暴擊時（Cold Exposure）；點燃、感電的下一 tick 看（Fire、Lightning Exposure）
        final double coldExposure = effective.flat(SupportExtraKeys.EXPOSURE_ON_COLD_CRIT, tags);
        if (coldExposure > 0 && result.crit() && result.dealt().get(DamageType.COLD) > 0) {
            expose(player, defender, monster, effective, tags, DamageType.COLD, coldExposure);
        }
        final double fireExposure = effective.flat(SupportExtraKeys.EXPOSURE_ON_IGNITE, tags);
        final double lightningExposure = effective.flat(SupportExtraKeys.EXPOSURE_ON_SHOCK, tags);
        final double conduct = effective.flat(SupportExtraKeys.SHOCK_NEARBY, tags);
        final double wildfire = effective.flat(SupportExtraKeys.WILDFIRE, tags);
        final double stormfire = effective.flat(SupportExtraKeys.STORMFIRE, tags);
        if ((fireExposure > 0 || lightningExposure > 0 || conduct > 0 || wildfire > 0 || stormfire > 0) && ailments) {
            final boolean wasIgnited = plugin.ailments().has(defender, AilmentType.IGNITE);
            final boolean wasShocked = plugin.ailments().has(defender, AilmentType.SHOCK);
            plugin.getServer().getScheduler().runTask(plugin, () -> {
                if (!defender.isValid() || defender.isDead() || plugin.ailments() == null) {
                    return;
                }
                final boolean ignited = plugin.ailments().has(defender, AilmentType.IGNITE);
                final boolean shocked = plugin.ailments().has(defender, AilmentType.SHOCK);
                if (fireExposure > 0 && ignited && (!wasIgnited || result.dealt().get(DamageType.FIRE) > 0)) {
                    expose(player, defender, monster, effective, tags, DamageType.FIRE, fireExposure);
                }
                if (lightningExposure > 0 && shocked && (!wasShocked || result.dealt().get(DamageType.LIGHTNING) > 0)) {
                    expose(player, defender, monster, effective, tags, DamageType.LIGHTNING, lightningExposure);
                }
                final boolean shocking = shocked && (!wasShocked || result.dealt().get(DamageType.LIGHTNING) > 0);
                if (conduct > 0 && shocking && roll(conduct)) {
                    conductShock(player, defender);
                }
                if (stormfire > 0 && shocking) {
                    stormfires.put(defender.getUniqueId(), new Stormfire(player.getUniqueId(), stormfire)); // 雷焚：牠還在感電時，點燃另外受到閃電傷害
                }
                if (wildfire > 0 && ignited) {
                    wildfires.put(defender.getUniqueId(), new Wildfire(player.getUniqueId(), wildfire, now() + 80));
                }
            });
        }
        // Rending Apex（2026-10-27 照 PoE2）：連段的最後一擊破甲，等同造成的攻擊傷害 x%（所有傷害類型）；這次之前平均成每一下物理傷害的 25%
        final double rending = effective.flat(SupportExtraKeys.RENDING_APEX, tags);
        if (rending > 0 && ailments && effective.flat(SupportExtraKeys.FINAL_STRIKE_MARK, tags) > 0 && result.dealt().total() > 0) {
            final double armour = plugin.monsters().sheet(defender, monster).get(StatKeys.ARMOUR);
            final double percent = effective.apply(StatKeys.ARMOUR_BREAK, rending, tags); // 「增加 x% 破甲」照乘
            if (armour > 0 && plugin.ailments().breakArmour(defender, monster, result.dealt().total(), percent, armour)) {
                service.procEvent(player, skill.id(), "armour_broken", null, defender);
            }
        }
        // Impale：擊中時穿刺（存下減免前物理傷害的 30%）
        final double impale = effective.flat(SupportExtraKeys.IMPALE_ON_HIT, tags);
        if (impale > 0 && result.outgoing().get(DamageType.PHYSICAL) > 0 && roll(impale)) {
            tw.exilecore.combat.Impales.shared().add(target, player.getUniqueId(), tw.exilecore.stats.KeystoneOriginKeys.impaleAmount(
                    result.outgoing().get(DamageType.PHYSICAL), effective.increased(tw.exilecore.stats.KeystoneOriginKeys.IMPALE_MAGNITUDE, tags)));
            defender.getWorld().spawnParticle(Particle.CRIT, defender.getLocation().add(0, defender.getHeight() * 0.6, 0), 6, 0.2, 0.3, 0.2, 0.05);
        }
        // Incision：記下這一下之前身上的切割，下一 tick 被流血用掉就算「最近用掉」
        if (effective.flat(SupportExtraKeys.INCISION_BLEED, tags) > 0 && debuffs != null && ailments && !plugin.ailments().has(defender, AilmentType.BLEED)) {
            final int stacks = tw.exilecore.combat.HitEffects.stacks(debuffs, target, "incision.", now);
            if (stacks > 0) {
                plugin.getServer().getScheduler().runTask(plugin, () -> {
                    if (plugin.ailments() != null && plugin.ailments().has(defender, AilmentType.BLEED)
                            && tw.exilecore.combat.HitEffects.stacks(debuffs, target, "incision.", now()) < stacks) {
                        incisions.computeIfAbsent(player.getUniqueId(), uuid -> new ArrayDeque<>()).addLast(new long[] {now(), stacks});
                    }
                });
            }
        }
        // Rupture：擊中重暈中的敵人時加劇流血；Rusted Spikes：打到被定身的
        if (ailments && (effective.flat(SupportExtraKeys.AGGRAVATE_HEAVY_STUNNED, tags) > 0 && plugin.ailments().heavyStunned(defender)
                || effective.flat(tw.exilecore.stats.TreeLastKeys.FLAG_AGGRAVATE_PINNED, tags) > 0 && debuffs != null && debuffs.has(target, "pin", now))) {
            plugin.getServer().getScheduler().runTask(plugin, () -> {
                if (plugin.ailments() != null && defender.isValid()) {
                    plugin.ailments().aggravateBleeds(defender, player.getUniqueId());
                }
            });
        }
        // Volatility：暴擊時 x% 機率一層易爆
        if (result.crit() && roll(effective.flat(SupportExtraKeys.VOLATILITY_ON_CRIT, tags)) && service.treeMechanics() != null) {
            service.treeMechanics().gainVolatility(player, 1);
        }
        // Inexorable Critical：暴擊時歸零
        if (result.crit() && effective.flat(SupportExtraKeys.INEXORABLE, tags) > 0) {
            critSince.put(key(player, SupportProcs.baseId(skill.id())), now);
        }
        // Neural Overload：擊中蓄勢待電的敵人直接電擊
        if (StatKeys.flag(effective, SupportExtraKeys.FLAG_OVERLOAD) && ailments) {
            plugin.ailments().electrocuteIfPrimed(defender);
        }
        // Undermine：用掉癱瘓，破掉牠 x% 的護甲
        final double undermine = effective.flat(SupportExtraKeys.UNDERMINE, tags);
        if (undermine > 0 && debuffs != null && ailments && debuffs.has(target, "maim", now)) {
            final double armour = plugin.monsters().sheet(defender, monster).get(StatKeys.ARMOUR);
            removeAny(debuffs, target, "maim", now);
            if (armour > 0 && plugin.ailments().breakArmour(defender, monster, armour * undermine / 100.0,
                    100.0 / Math.max(1, plugin.ailments().config().armourBreakMultiplier(monster.rarity())), armour)) {
                service.procEvent(player, skill.id(), "armour_broken", null, defender);
            }
        }
        // Frenzied Riposte：用掉被招架，給一顆怒星
        final double riposte = effective.flat(SupportExtraKeys.RIPOSTE, tags);
        if (riposte > 0 && debuffs != null && debuffs.has(target, PARRIED, now) && plugin.combat().charges() != null) {
            removeAny(debuffs, target, PARRIED, now);
            plugin.combat().charges().grant(player, "frenzy", (int) Math.round(riposte));
        }
        // Punch Through：擊退時讓敵人成為好目標（每格擊退距離 x%）
        final double punch = effective.flat(SupportExtraKeys.PUNCH_THROUGH, tags);
        if (punch > 0 && debuffs != null) {
            final double knockback = Math.max(0, effective.apply(StatKeys.SKILL_KNOCKBACK, effective.flat(StatKeys.SKILL_KNOCKBACK, tags), tags));
            if (knockback > 0) {
                debuffs.apply(target, new Debuffs.Debuff(EASY_TARGET, player.getUniqueId(), now + SupportExtraKeys.EASY_TARGET_TICKS,
                        punch * knockback / 100.0, true));
            }
        }
        // Font of Blood／Mana：圖騰的擊中，站在圖騰 4 格內的你與隊友偷取圖騰物理傷害的 x%
        final double fontBlood = effective.flat(SupportExtraKeys.FONT_BLOOD, tags);
        final double fontMana = effective.flat(SupportExtraKeys.FONT_MANA, tags);
        if ((fontBlood > 0 || fontMana > 0) && tags.contains(Tags.TOTEM_HIT) && result.dealt().get(DamageType.PHYSICAL) > 0) {
            final double physical = result.dealt().get(DamageType.PHYSICAL);
            for (final Player ally : fontAllies(player)) {
                final PlayerState state = plugin.players().get(ally);
                if (fontBlood > 0 && !state.ruined(now)) {
                    state.addHitLeech(physical * fontBlood / 100.0);
                }
                if (fontMana > 0) {
                    state.addHitManaLeech(physical * fontMana / 100.0);
                }
            }
        }
        // Mark of Siphoning：被印記的敵人受到你的物理攻擊，偷取為生命與魔力
        final Siphon siphon = siphons.get(target);
        if (siphon != null && siphon.owner().equals(player.getUniqueId()) && siphon.untilTick() > now && skill.isAttack()) {
            final double physical = result.dealt().get(DamageType.PHYSICAL);
            if (physical > 0) {
                final PlayerState state = plugin.players().get(player);
                if (!state.ruined(now)) {
                    state.addHitLeech(physical * siphon.percent() / 100.0);
                }
                state.addHitManaLeech(physical * siphon.percent() / 100.0);
            }
        }
        // Acrimony：受到持續傷害（地面效果、混沌減益）的敵人生命再生減少
        final double acrimony = effective.flat(SupportExtraKeys.ACRIMONY, tags);
        if (acrimony > 0 && debuffs != null) {
            debuffs.apply(target, new Debuffs.Debuff("support.acrimony", player.getUniqueId(), now + SupportExtraKeys.ACRIMONY_TICKS, 0, false, null,
                    List.of(Modifier.increased(tw.exilecore.stats.TreeEffectKeys.REGEN_RATE, -acrimony / 100.0, "怨蝕")), 0));
        }
        // Barbs、Quill Burst：荊棘反彈之後的擊中
        final long[] retaliated = thorns.get(player.getUniqueId());
        if (retaliated != null && now - retaliated[0] <= SupportExtraKeys.THORNS_WINDOW) {
            final double barbs = effective.flat(SupportExtraKeys.BARBS, tags);
            if (barbs > 0 && retaliated[1] > 0) {
                retaliated[1]--;
                final SkillProfile thornsProfile = tw.exilecore.combat.Thorns.profile(plugin.players().sheet(player));
                if (thornsProfile != null) {
                    final SkillProfile twice = thornsProfile.derive("support.barbs", Set.of(), Set.of()).scaled(2.0, "倒鉤");
                    plugin.combat().skillDamage().hit(player, defender, twice, null);
                }
            }
            final double quill = effective.flat(SupportExtraKeys.QUILL_BURST, tags);
            if (quill > 0) {
                if (retaliated[2] == 1) {
                    retaliated[2] = 1 + (long) quill; // 第一次：設好剩幾下
                }
                if (retaliated[2] > 1) {
                    retaliated[2]--;
                    service.procs().castAt(player, "proc_quill_burst", SupportProcs.baseId(skill.id()), defender.getLocation());
                }
            }
        }
    }

    /** 站在你的圖騰 4 格內的你與隊友（Font of Blood／Mana）。 */
    private List<Player> fontAllies(final Player owner) {
        final List<SkillService.TotemView> totems = service.totems(owner.getUniqueId());
        final List<Player> candidates = new ArrayList<>();
        candidates.add(owner);
        if (plugin.party() != null) {
            for (final UUID member : plugin.party().groupMembers(owner.getUniqueId())) {
                final Player ally = member.equals(owner.getUniqueId()) ? null : plugin.getServer().getPlayer(member);
                if (ally != null) {
                    candidates.add(ally);
                }
            }
        }
        final List<Player> inside = new ArrayList<>();
        for (final Player candidate : candidates) {
            for (final SkillService.TotemView totem : totems) {
                if (candidate.getWorld().equals(totem.feet().getWorld())
                        && candidate.getLocation().distanceSquared(totem.feet()) <= SupportExtraKeys.FONT_RADIUS * SupportExtraKeys.FONT_RADIUS) {
                    inside.add(candidate);
                    break;
                }
            }
        }
        return inside;
    }

    /** 拿掉這個代號的減益（不管是誰給的）。 */
    private static void removeAny(final Debuffs debuffs, final UUID target, final String id, final long now) {
        for (final Debuffs.Debuff debuff : debuffs.active(target, now)) {
            if (debuff.id().equals(id) && debuff.source() != null) {
                debuffs.remove(target, id, debuff.source(), now);
            }
        }
    }

    /** 眩暈（PoE2 Daze：8 秒內受到的暈眩累積更多 50%；跟 HitEffects 一樣）。 */
    private void daze(final Player player, final LivingEntity defender, final long now) {
        debuffs().apply(defender.getUniqueId(), new Debuffs.Debuff("daze", player.getUniqueId(), now + 160, 0, false, null,
                List.of(Modifier.more(tw.exilecore.ailment.AilmentKeys.STUN_BUILDUP_TAKEN, tw.exilecore.combat.HitEffects.DAZE_STUN_BUILDUP, "眩暈")), 0));
        defender.getWorld().spawnParticle(Particle.WAX_OFF, defender.getLocation().add(0, defender.getHeight(), 0), 6, 0.3, 0.2, 0.3, 0);
    }

    /** 曝露（那種元素抗性 −20%，吃曝露效果，魔法、稀有、傳奇身上更少）x 秒。 */
    private void expose(final Player player, final LivingEntity defender, final MonsterData monster, final StatSheet effective, final Set<String> tags,
                        final DamageType type, final double seconds) {
        final Debuffs debuffs = debuffs();
        if (debuffs == null) {
            return;
        }
        final StatSheet defenderSheet = plugin.monsters().sheet(defender, monster);
        final double amount = (tw.exilecore.combat.HitEffects.EXPOSURE + effective.flat(tw.exilecore.stats.TreeMechanicKeys.EXPOSURE_EXTRA, tags))
                * Math.max(0, 1 + effective.increased(StatKeys.EXPOSURE_EFFECT, tags))
                * Math.max(0, defenderSheet.apply(tw.exilecore.ailment.AilmentKeys.EXPOSURE_EFFECT_ON_SELF, 1.0, Set.of()));
        debuffs.apply(defender.getUniqueId(), new Debuffs.Debuff("exposure." + type.tag(), player.getUniqueId(), now() + Math.round(seconds * 20), 0, false, null,
                List.of(Modifier.flat(type.mitigationStat(), -amount, "曝露")), 0));
        defender.getWorld().spawnParticle(Particle.ENCHANTED_HIT, defender.getLocation().add(0, defender.getHeight() * 0.7, 0), 8, 0.3, 0.3, 0.3, 0.05);
    }

    /**
     * Shock Conduction（2026-10-27 照 PoE2）：讓 2.5 格內的敵人也感電（跟這隻一樣的感電幅度）；這次之前只傳給其中一隻、固定 20%。
     */
    private void conductShock(final Player player, final LivingEntity source) {
        final double magnitude = Math.max(0.01, plugin.ailments().shockMagnitude(source));
        for (final LivingEntity near : service.targets().around(source.getLocation(), SupportExtraKeys.SHOCK_NEARBY_RADIUS, player)) {
            if (near != source) {
                plugin.ailments().groundStatusOnMonster(near, 0, magnitude, 80);
                near.getWorld().spawnParticle(Particle.ELECTRIC_SPARK, near.getLocation().add(0, 1, 0), 8, 0.3, 0.4, 0.3, 0.05);
            }
        }
    }

    /** Stormfire：這隻敵人現在受到的點燃另外有幾 % 當作閃電傷害（還在感電、被掛了雷焚的技能感電過；AilmentService 每一跳問）。 */
    public double stormfire(final LivingEntity target) {
        final Stormfire mark = stormfires.get(target.getUniqueId());
        return mark == null || plugin.ailments() == null || !plugin.ailments().has(target, AilmentType.SHOCK) ? 0 : mark.percent();
    }

    /**
     * 2026-10-27 照 PoE2 Drenched：積雷雲（Thunderstorm）範圍內的敵人濕透，離開後還留 2 秒：感電機率更多 50%、受到的凍結累積更多 25%
     * （Shock Conduction 擊中濕透的一定感電）。SkillZones 的地面效果每一波呼叫。
     */
    static void drench(final ExileCore plugin, final Player owner, final LivingEntity target, final String skillId, final int intervalTicks, final long now) {
        if (owner == null || skillId == null || target instanceof Player || plugin.combat() == null || !SupportExtraKeys.DRENCHING_SKILLS.contains(skillId)) {
            return;
        }
        plugin.combat().debuffs().apply(target.getUniqueId(), new Debuffs.Debuff(SupportExtraKeys.DRENCHED_ID, owner.getUniqueId(),
                now + intervalTicks + SupportExtraKeys.DRENCHED_TICKS, 0, false, null, DRENCHED_MODS, 0));
    }

    private static final List<Modifier> DRENCHED_MODS = List.of(
            Modifier.more(SupportExtraKeys.SHOCK_CHANCE_TAKEN, SupportExtraKeys.DRENCHED_SHOCK / 100.0, "濕透"),
            Modifier.more(SupportExtraKeys.FREEZE_BUILDUP_TAKEN, SupportExtraKeys.DRENCHED_FREEZE / 100.0, "濕透"),
            Modifier.flat(SupportExtraKeys.FLAG_DRENCHED, 1, "濕透"));

    /** 召喚物打中：Minion Splash（打擊擴散）。 */
    private void minionHit(final HitEvent event) {
        if (service.minions() == null || !(event.attacker() instanceof org.bukkit.entity.Mob mob) || event.credit() == null) {
            return;
        }
        if (SPLASH_ID.equals(event.skill().id())) {
            return;
        }
        MinionService.Minion minion = null;
        for (final MinionService.Minion each : service.minions().all()) {
            if (each.mob.getUniqueId().equals(mob.getUniqueId())) {
                minion = each;
                break;
            }
        }
        if (minion == null) {
            return;
        }
        boolean splash = false;
        for (final SupportDefinition support : minion.build.supports()) {
            splash |= support.flags(SupportExtraKeys.FLAG_MINION_SPLASH);
        }
        final Player owner = event.credit();
        final DamagePacket outgoing = event.result().outgoing();
        // Crazed Minions：2026-10-27 改成召喚物自己的屬性表更多傷害（crazed，MinionService.rebuild）；這次之前每一下另外打 30%
        if (splash && event.skill().tags().contains(Tags.MELEE)) {
            final DamagePacket share = new DamagePacket(outgoing).scale(SupportExtraKeys.MINION_SPLASH_SHARE);
            final SkillProfile profile = new SkillProfile(SPLASH_ID, Set.of(Tags.MINION, Tags.AREA), 0, share, share, Map.of(),
                    List.of(Modifier.flat(StatKeys.FLAG_NO_CRIT, 1, "僕濺")));
            for (final LivingEntity near : service.targets().around(event.defender().getLocation(), SupportExtraKeys.MINION_SPLASH_RADIUS, owner)) {
                if (near != event.defender()) {
                    plugin.combat().skillDamage().hit(owner, near, profile, null);
                }
            }
        }
    }

    static final String SPLASH_ID = "support.minion_splash";

    /**
     * Crazed Minions（2026-10-27 照 PoE2：召喚物的屬性表更多 x% 傷害）：這隻召喚物最近（4 秒內）復活過——同一個技能有一隻死掉之後才叫出來的；
     * 回傳要加在召喚物身上的修飾器與到哪個 tick（MinionService.rebuild 呼叫）。沒有回傳 null。
     */
    Map.Entry<List<Modifier>, Long> crazed(final MinionService.Minion minion) {
        double crazed = 0;
        for (final SupportDefinition support : minion.build.supports()) {
            for (final Modifier modifier : support.modifiers()) {
                if (modifier.stat().equals(SupportExtraKeys.CRAZED)) {
                    crazed = Math.max(crazed, modifier.value());
                }
            }
        }
        final Long died = minionDied.get(minion.owner + "/" + minion.build.definition().id());
        final long until = minion.bornTick + SupportExtraKeys.CRAZED_TICKS;
        if (crazed <= 0 || died == null || minion.bornTick < died || now() > until) {
            return null;
        }
        return Map.entry(List.of(Modifier.more(StatKeys.DAMAGE, crazed / 100.0, "狂僕")), until);
    }

    /** Deadly Resolve：被擊中時用掉一層，朝對方斬一刀（照護甲更多傷害）。 */
    private void deadlyResolve(final Player player, final LivingEntity attacker, final long now) {
        final List<Long> stacks = resolve.get(player.getUniqueId());
        if (stacks == null || attacker == null) {
            return;
        }
        stacks.removeIf(until -> until <= now);
        final long[] timers = resolveTimers.computeIfAbsent(player.getUniqueId(), uuid -> new long[2]);
        if (stacks.isEmpty() || timers[1] > now) {
            return;
        }
        stacks.removeFirst();
        timers[1] = now + SupportExtraKeys.DEADLY_RESOLVE_GAP;
        // 2026-10-27 照 PoE2 TriggeredDeadlyResolvePlayer：每 x 點護甲（照等級 5～108）更多 1%，最多更多 200%；這次之前每 200 點 1%、最多 100%
        final double armour = Math.max(0, plugin.players().sheet(player).get(StatKeys.ARMOUR));
        final String host = channellingSkill(player);
        final int level = levelOf(player, host == null ? "" : host);
        castFree(player, "proc_deadly_resolve", host == null ? "" : host, player.getLocation(), attacker, SupportExtraKeys.deadlyResolveScale(armour, level));
    }

    // =====================================================================
    // 打死
    // =====================================================================

    @EventHandler(priority = EventPriority.MONITOR)
    public void onDeath(final EntityDeathEvent event) {
        final LivingEntity dead = event.getEntity();
        final UUID id = dead.getUniqueId();
        if (dead instanceof Player) {
            return;
        }
        preHitFlags.remove(id);
        siphons.remove(id);
        wildfires.remove(id);
        nearFire.remove(id);
        lastGasp.remove(id);
        stormfires.remove(id);
        final LastHit hit = lastHits.remove(id);
        if (hit == null || now() - hit.tick() > 40) {
            return;
        }
        final Player player = plugin.getServer().getPlayer(hit.owner());
        if (player == null || !player.getWorld().equals(dead.getWorld())) {
            return;
        }
        final StatSheet effective = hit.effective();
        final Set<String> tags = hit.skill().tags();
        final Location at = dead.getLocation();
        // Hex Bloom：被你詛咒的敵人死掉時，詛咒傳給附近的敵人
        final double bloom = effective.flat(SupportExtraKeys.HEX_BLOOM, tags);
        final double bloomAny = bloomRadius(player);
        if (debuffs() != null && Math.max(bloom, bloomAny) > 0) {
            final double radius = Math.max(bloom, bloomAny);
            final List<Debuffs.Debuff> curses = new ArrayList<>();
            for (final Debuffs.Debuff debuff : debuffs().active(id, now())) {
                if (debuff.source() != null && debuff.source().equals(player.getUniqueId()) && debuffs().isMark(debuff.id())) {
                    curses.add(debuff);
                }
            }
            if (!curses.isEmpty()) {
                for (final LivingEntity near : service.targets().around(at, radius, player)) {
                    for (final Debuffs.Debuff curse : curses) {
                        debuffs().applyCurse(near.getUniqueId(), curse, curse.id());
                    }
                }
                at.getWorld().spawnParticle(Particle.WITCH, at.clone().add(0, 1, 0), 16, radius / 2, 0.4, radius / 2, 0.05);
            }
        }
        // Shock Siphon：打死感電中的敵人回復能量護盾
        final double siphon = effective.flat(SupportExtraKeys.SHOCK_SIPHON, tags);
        if (siphon > 0 && hit.shocked()) {
            final PlayerState state = plugin.players().get(player);
            state.setEnergyShield(Math.min(state.maxEnergyShield(), state.energyShield() + state.maxEnergyShield() * siphon / 100.0));
        }
        // Tear：打死流血中的敵人時擊殺效果再發生一次（生命、魔力、護盾、戰星、藥劑充能）
        final double tear = effective.flat(SupportExtraKeys.TEAR, tags);
        if (tear > 0 && hit.bleeding() && roll(tear)) {
            onKillAgain(player, effective, tags);
        }
        // Rip：打死流血中的敵人留下血之殘骸
        final double rip = effective.flat(SupportExtraKeys.RIP, tags);
        if (rip > 0 && hit.bleeding() && service.chargeEffects() != null) {
            final double angle = plugin.random().nextDouble() * Math.PI * 2;
            final Location spot = at.clone().add(Math.cos(angle) * 1.5 * plugin.random().nextDouble(), 0.4, Math.sin(angle) * 1.5 * plugin.random().nextDouble());
            service.chargeEffects().dropCustomRemnant(player, spot, org.bukkit.Material.REDSTONE, times -> {
                for (int i = 0; i < times; i++) {
                    stackRip(player, rip);
                }
            });
        }
        // Behead：打擊打死稀有怪，偷走牠兩個詞綴 x 秒
        final double behead = effective.flat(SupportExtraKeys.BEHEAD, tags);
        if (behead > 0 && plugin.combat() != null && plugin.combat().killEffects() != null && plugin.monsters() != null) {
            final MonsterData data = plugin.monsters().data(dead).orElse(null);
            if (data != null && data.rarity() == MonsterRarity.RARE) {
                plugin.combat().killEffects().stealMods(player, dead, behead, 2);
            }
        }
    }

    /** 召喚物死掉（Crazed Minions 的「復活」）：MinionService 拿掉牠之前先記下來。 */
    @EventHandler(priority = EventPriority.LOWEST)
    public void onMinionDeath(final EntityDeathEvent event) {
        if (service.minions() == null || event.getEntity() instanceof Player) {
            return;
        }
        final UUID id = event.getEntity().getUniqueId();
        for (final MinionService.Minion minion : service.minions().all()) {
            if (minion.mob.getUniqueId().equals(id)) {
                minionDied.put(minion.owner + "/" + minion.build.definition().id(), now());
                return;
            }
        }
    }

    /** 其他技能（不一定掛詛咒綻放）打死的：身上的詛咒是掛了詛咒綻放的詛咒放的，也照樣傳。回傳最大的半徑。 */
    private double bloomRadius(final Player player) {
        final PlayerState state = plugin.players().get(player);
        double best = 0;
        for (int i = 0; i < tw.exilecore.character.SkillLoadout.MAX_SKILLS; i++) {
            final tw.exilecore.character.SkillLoadout.Entry entry = state.skills().skill(i);
            if (entry != null) {
                for (final SupportDefinition support : service.supportsOf(entry)) {
                    for (final Modifier modifier : support.modifiers()) {
                        if (modifier.stat().equals(SupportExtraKeys.HEX_BLOOM)) {
                            best = Math.max(best, modifier.value());
                        }
                    }
                }
            }
        }
        return best;
    }

    /** Rip：每撿一個血之殘骸，生命再生速度增加 x%（5 秒，最多 100%）。 */
    private void stackRip(final Player player, final double each) {
        final double current = ripStacks.merge(player.getUniqueId(), each, (a, b) -> Math.min(100, a + b));
        service.buff(player, "support.rip", "血之殘骸", (int) SupportExtraKeys.RIP_TICKS,
                List.of(Modifier.increased(tw.exilecore.stats.TreeEffectKeys.REGEN_RATE, current / 100.0, "血之殘骸")));
        plugin.getServer().getScheduler().runTaskLater(plugin, () -> ripStacks.computeIfPresent(player.getUniqueId(),
                (uuid, value) -> value - each <= 1e-6 ? null : value - each), SupportExtraKeys.RIP_TICKS);
    }

    private final Map<UUID, Double> ripStacks = new HashMap<>();

    /** Tear：擊殺效果再一次（擊殺回復生命、魔力、護盾，擊殺給藥劑充能、戰星）。 */
    private void onKillAgain(final Player player, final StatSheet effective, final Set<String> tags) {
        final PlayerState state = plugin.players().get(player);
        final long now = now();
        if (!state.ruined(now)) {
            state.setLife(Math.min(state.maxLife(), state.life() + Math.max(0, effective.flat(StatKeys.LIFE_ON_KILL, tags))
                    + state.maxLife() * Math.max(0, effective.flat(StatKeys.LIFE_PER_KILL, tags)) / 100.0));
        }
        state.setMana(Math.min(state.maxMana(), state.mana() + Math.max(0, effective.flat(StatKeys.MANA_ON_KILL, tags))
                + state.maxMana() * Math.max(0, effective.flat(StatKeys.MANA_PER_KILL, tags)) / 100.0));
        state.setEnergyShield(Math.min(state.maxEnergyShield(), state.energyShield() + Math.max(0, effective.flat(StatKeys.ES_ON_KILL, tags))));
        if (plugin.flasks() != null) {
            plugin.flasks().addCharges(player, 1);
        }
        if (plugin.combat() != null && plugin.combat().charges() != null) {
            plugin.combat().charges().onKill(player);
        }
        plugin.players().display(player, state);
    }

    /** Last Gasp：常駐召喚物生命歸零時不死，4 秒後才倒下。 */
    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void onMinionDamaged(final EntityDamageEvent event) {
        if (!(event.getEntity() instanceof org.bukkit.entity.Mob mob) || service.minions() == null) {
            return;
        }
        final Long until = lastGasp.get(mob.getUniqueId());
        if (until != null) {
            if (until > now()) {
                event.setDamage(0); // 已經在最後一口氣：照樣挨打但不會掉血
            }
            return;
        }
        if (event.getFinalDamage() < mob.getHealth()) {
            return;
        }
        for (final MinionService.Minion minion : service.minions().all()) {
            if (!minion.mob.getUniqueId().equals(mob.getUniqueId())) {
                continue;
            }
            double seconds = 0;
            for (final SupportDefinition support : minion.build.supports()) {
                for (final Modifier modifier : support.modifiers()) {
                    if (modifier.stat().equals(SupportExtraKeys.LAST_GASP)) {
                        seconds = Math.max(seconds, modifier.value());
                    }
                }
            }
            if (seconds > 0) {
                event.setDamage(0);
                mob.setHealth(Math.max(0.5, Math.min(mob.getHealth(), 1)));
                final long ends = now() + Math.round(seconds * 20);
                lastGasp.put(mob.getUniqueId(), ends);
                mob.getWorld().spawnParticle(Particle.SOUL, mob.getLocation().add(0, 1, 0), 10, 0.3, 0.5, 0.3, 0.02);
                plugin.getServer().getScheduler().runTaskLater(plugin, () -> {
                    lastGasp.remove(mob.getUniqueId());
                    if (mob.isValid() && !mob.isDead()) {
                        mob.setHealth(0);
                    }
                }, Math.round(seconds * 20));
            }
            return;
        }
    }

    // =====================================================================
    // 每 5 tick（SupportMechanics.tick）
    // =====================================================================

    void tick(final long now) {
        for (final Player player : plugin.getServer().getOnlinePlayers()) {
            sample(player, now);
            channelling(player, now);
        }
        tickBarrier(now);
        if (now % 10 == 0) {
            tickGrounds(now);
            tickSpectral(now);
            tickRefraction(now);
        }
        if (now % 20 == 0) {
            tickWildfire(now);
            tickMinionBuffs();
            critSince.entrySet().removeIf(entry -> now - entry.getValue() > 20L * 60 * 10);
            siphons.values().removeIf(each -> each.untilTick() < now);
            gratification.values().removeIf(until -> until < now);
            thorns.values().removeIf(each -> now - each[0] > SupportExtraKeys.THORNS_WINDOW);
            minionDied.values().removeIf(tick -> now - tick > 20L * 60);
            barrierUsing.values().removeIf(each -> each[0] < now);
            if (plugin.ailments() != null) {
                stormfires.keySet().removeIf(target -> !(plugin.getServer().getEntity(target) instanceof LivingEntity living)
                        || !living.isValid() || !plugin.ailments().has(living, AilmentType.SHOCK));
            }
            for (final Iterator<Map.Entry<String, ArrayDeque<long[]>>> it = bolts.entrySet().iterator(); it.hasNext(); ) {
                final ArrayDeque<long[]> log = it.next().getValue();
                log.removeIf(each -> now - each[0] > SupportExtraKeys.FRESH_CLIP_TICKS);
                if (log.isEmpty()) {
                    it.remove();
                }
            }
        }
    }

    /** 移動：Dauntless 的靜止、Stoicism 的翻滾、Stomping Ground 的步伐。 */
    private void sample(final Player player, final long now) {
        final UUID id = player.getUniqueId();
        final Location at = player.getLocation();
        final Location before = lastAt.put(id, at);
        double step = 0;
        if (before != null && before.getWorld().equals(at.getWorld())) {
            final double dx = at.getX() - before.getX();
            final double dz = at.getZ() - before.getZ();
            step = Math.sqrt(dx * dx + dz * dz);
            if (step >= 8) {
                step = 0; // 傳送不算
            }
        }
        if (step > 0.05) {
            lastMoved.put(id, now);
        }
        if (plugin.combat() != null && plugin.combat().dodge() != null && plugin.combat().dodge().rolling(player)) {
            stoicSince.put(id, now);
        }
        // Stomping Ground：用突進技能的時候（按住衝的一直算），在地上每走一步一陣震波
        final Stomp stomp = stomps.get(id);
        if (stomp != null) {
            final boolean using = now <= stomp.untilTick || stomp.skillId.equals(channellingSkill(player));
            if (!using) {
                stomps.remove(id);
            } else if (!stomp.leap && step > 0 && ((org.bukkit.entity.Entity) player).isOnGround()) {
                stomp.walked += step;
                while (stomp.walked >= SupportExtraKeys.STOMPING_STEP) {
                    stomp.walked -= SupportExtraKeys.STOMPING_STEP;
                    stompAt(player, stomp, at);
                }
            }
        }
    }

    /** 引導中的技能（按著的蓄力、連續放的引導）；沒有回傳 null。 */
    private String channellingSkill(final Player player) {
        final SkillRuntime runtime = service.runtime(player);
        if (runtime.charging != null) {
            return runtime.charging.skillId;
        }
        return now() - runtime.channelTick <= 16 ? runtime.channelSkill : null;
    }

    /** 引導中：Steadfast（門檻）、Crackling Barrier（閃電荊棘）、Deadly Resolve（每秒一層）。 */
    private void channelling(final Player player, final long now) {
        final String skillId = channellingSkill(player);
        if (skillId == null) {
            return;
        }
        final PlayerState state = plugin.players().get(player);
        final int position = state.skills().find(skillId);
        if (position < 0) {
            return;
        }
        final List<Modifier> mods = new ArrayList<>();
        double crackling = 0;
        double resolveMax = 0;
        for (final SupportDefinition support : service.supportsOf(state.skills().skill(position))) {
            for (final Modifier modifier : support.modifiers()) {
                if (modifier.stat().startsWith(SupportExtraKeys.CHANNELLING_PREFIX)) {
                    mods.add(new Modifier(modifier.stat().substring(SupportExtraKeys.CHANNELLING_PREFIX.length()), modifier.type(), modifier.value(),
                            modifier.tags(), support.name()));
                } else if (modifier.stat().equals(SupportExtraKeys.CRACKLING)) {
                    crackling += modifier.value();
                } else if (modifier.stat().equals(SupportExtraKeys.DEADLY_RESOLVE)) {
                    resolveMax = Math.max(resolveMax, modifier.value());
                } else if (modifier.stat().equals(SupportExtraKeys.STOMPING) && modifier.value() > 0) {
                    // Stomping Ground：按住衝的突進（鐵壁衝撞）在放開之前就在走
                    final Stomp stomp = stomps.get(player.getUniqueId());
                    if (stomp == null || !stomp.skillId.equals(skillId)) {
                        stomps.put(player.getUniqueId(), new Stomp(skillId, player.getLocation(), now + 10));
                    } else {
                        stomp.untilTick = Math.max(stomp.untilTick, now + 10);
                    }
                }
            }
        }
        if (crackling > 0) {
            final double amount = state.maxMana() * crackling / 100.0;
            mods.add(Modifier.flat(SupportExtraKeys.THORNS_LIGHTNING_MIN, amount, "電弧屏障"));
            mods.add(Modifier.flat(SupportExtraKeys.THORNS_LIGHTNING_MAX, amount, "電弧屏障"));
        }
        if (!mods.isEmpty()) {
            service.buff(player, "support.channelling", "引導中", 10, mods);
        }
        if (resolveMax > 0) {
            final long[] timers = resolveTimers.computeIfAbsent(player.getUniqueId(), uuid -> new long[2]);
            if (timers[0] <= now) {
                timers[0] = now + 20;
                final List<Long> stacks = resolve.computeIfAbsent(player.getUniqueId(), uuid -> new ArrayList<>());
                stacks.removeIf(until -> until <= now);
                if (stacks.size() < resolveMax) {
                    stacks.add(now + SupportExtraKeys.DEADLY_RESOLVE_TICKS);
                    tw.exilecore.gui.ActionBar.send(player, plugin.text().parse("<#c0392b>必殺決心 " + stacks.size()));
                }
            }
        }
    }

    /** 聖化地面（站在上面的你與隊友回復生命、詛咒效果減少）、詛咒地面（裡面的敵人被詛咒）。 */
    private void tickGrounds(final long now) {
        for (final Iterator<Ground> it = grounds.iterator(); it.hasNext(); ) {
            final Ground ground = it.next();
            final Player owner = plugin.getServer().getPlayer(ground.owner);
            if (owner == null || now > ground.untilTick || !owner.getWorld().equals(ground.at.getWorld())) {
                it.remove();
                continue;
            }
            if (ground.kind == 0) {
                final List<Player> allies = new ArrayList<>();
                allies.add(owner);
                if (plugin.party() != null) {
                    for (final UUID member : plugin.party().groupMembers(owner.getUniqueId())) {
                        final Player ally = member.equals(owner.getUniqueId()) ? null : plugin.getServer().getPlayer(member);
                        if (ally != null) {
                            allies.add(ally);
                        }
                    }
                }
                for (final Player ally : allies) {
                    if (ally.getWorld().equals(ground.at.getWorld()) && ally.getLocation().distanceSquared(ground.at) <= ground.radius * ground.radius) {
                        service.buff(ally, "support.consecrated", "聖化地面", 12, List.of(
                                Modifier.flat(StatKeys.LIFE_REGEN_PERCENT, SupportExtraKeys.CONSECRATED_REGEN, "聖化地面"),
                                Modifier.increased(StatKeys.CURSE_EFFECT_ON_SELF, -0.5, "聖化地面")));
                    }
                }
                ring(ground, Particle.END_ROD);
            } else if (ground.curse != null && ground.spec != null) {
                // 詛咒地面（2026-10-27 照 PoE2 Cursed Ground）：站在裡面的敵人被詛咒，出去 0.75 秒內就沒了（跟血脈的蟾咒地面一樣）；
                // 這次之前每秒在中心重放一次詛咒，出去之後詛咒還留整段持續時間
                service.executor().applyCurseAt(owner, ground.curse, ground.at, ground.spec, now + 15);
                ring(ground, Particle.WITCH);
            }
        }
    }

    private static void ring(final Ground ground, final Particle particle) {
        for (int i = 0; i < 8; i++) {
            final double a = Math.PI * 2 * i / 8;
            Fx.spawn(ground.at.getWorld(), particle, ground.at.clone().add(Math.cos(a) * ground.radius, 0.15, Math.sin(a) * ground.radius), 1, 0, 0, 0, 0);
        }
    }

    /** Spectral Volley：3 秒沒有再存，全部射出去。 */
    private void tickSpectral(final long now) {
        for (final Iterator<Map.Entry<String, Spectral>> it = spectral.entrySet().iterator(); it.hasNext(); ) {
            final Map.Entry<String, Spectral> entry = it.next();
            final Spectral stored = entry.getValue();
            if (stored.count <= 0 || now - stored.lastCreated < SupportExtraKeys.SPECTRAL_TICKS) {
                continue;
            }
            it.remove();
            final Player player = plugin.getServer().getPlayer(UUID.fromString(entry.getKey().substring(0, entry.getKey().indexOf('/'))));
            if (player == null || stored.build == null) {
                continue;
            }
            for (int i = 0; i < stored.count; i++) {
                plugin.getServer().getScheduler().runTaskLater(plugin, () -> {
                    if (!player.isOnline()) {
                        return;
                    }
                    final LivingEntity target = stored.target == null ? null : plugin.getServer().getEntity(stored.target) instanceof LivingEntity living
                            && living.isValid() && !living.isDead() ? living : null;
                    final Location from = player.getEyeLocation().add(0, 0.6, 0);
                    final Origin origin = target != null ? Origin.towards(player, from.clone().subtract(0, 1.2, 0), target) : Origin.of(player);
                    service.executor().executeFrom(origin, stored.build, target);
                    from.getWorld().spawnParticle(Particle.END_ROD, from, 4, 0.2, 0.2, 0.2, 0.01);
                }, 2L * (i + 1));
            }
        }
    }

    /** Refraction：插下的戰旗範圍內的敵人折光消融：元素曝露（每 1000 點主人的護甲 x%，最多 80%）。 */
    private void tickRefraction(final long now) {
        banners.removeIf(banner -> banner.untilTick() < now || plugin.getServer().getPlayer(banner.owner()) == null);
        if (debuffs() == null || banners.isEmpty()) {
            return;
        }
        for (final Banner banner : banners) {
            final Player owner = plugin.getServer().getPlayer(banner.owner());
            if (owner == null || !owner.getWorld().equals(banner.at().getWorld())) {
                continue;
            }
            final double armour = Math.max(0, plugin.players().sheet(owner).get(StatKeys.ARMOUR));
            final double exposure = refractionExposure(banner.perThousand(), armour);
            if (exposure <= 0) {
                continue;
            }
            for (final LivingEntity near : service.targets().around(banner.at(), banner.radius(), owner)) {
                final List<Modifier> mods = new ArrayList<>();
                for (final DamageType type : new DamageType[] {DamageType.FIRE, DamageType.COLD, DamageType.LIGHTNING}) {
                    mods.add(Modifier.flat(type.mitigationStat(), -exposure, "折光"));
                }
                debuffs().apply(near.getUniqueId(), new Debuffs.Debuff("support.refraction", owner.getUniqueId(), now + 15, 0, false, null, mods, 0));
            }
        }
    }

    /** Refraction：每 1000 點護甲 x% 元素曝露，最多 80%。 */
    static double refractionExposure(final double perThousand, final double armour) {
        return Math.min(SupportExtraKeys.REFRACTION_CAP, perThousand * Math.max(0, armour) / 1000.0);
    }

    /** Energy Barrier：被暈眩後那幾秒，能量護盾的「受傷後延遲」一直當作已經過了（受傷也不打斷回充）。 */
    private void tickBarrier(final long now) {
        if (barrierUntil.isEmpty()) {
            return;
        }
        for (final Iterator<Map.Entry<UUID, Long>> it = barrierUntil.entrySet().iterator(); it.hasNext(); ) {
            final Map.Entry<UUID, Long> entry = it.next();
            final Player player = plugin.getServer().getPlayer(entry.getKey());
            if (player == null || entry.getValue() < now) {
                it.remove();
                continue;
            }
            keepRecharging(player, now);
        }
    }

    private void keepRecharging(final Player player, final long now) {
        final PlayerState state = plugin.players().get(player);
        state.setLastDamageTick(Math.min(state.lastDamageTick(),
                now - tw.exilecore.character.PlayerStateService.esRechargeDelay(plugin.players().sheet(player))));
    }

    /**
     * 2026-10-27 照 PoE2 Energy Barrier：玩家被暈眩（輕暈、重暈；AilmentService 呼叫）：正在放掛了能障的技能 → 能量護盾立刻開始回充，
     * x 秒內不會被傷害打斷。這次之前做成「放的那段時間回充不等待」。
     */
    public void playerStunned(final Player player) {
        final long now = now();
        final long[] using = barrierUsing.get(player.getUniqueId());
        if (using == null || using[0] < now) {
            return;
        }
        barrierUntil.put(player.getUniqueId(), now + using[1]);
        keepRecharging(player, now);
    }

    /** Wildfire：在點燃的怪物 1.5 格內待滿 1 秒的敵人也被點燃（同一個點燃）。 */
    private void tickWildfire(final long now) {
        if (wildfires.isEmpty() || plugin.ailments() == null) {
            return;
        }
        final Set<UUID> close = new HashSet<>();
        for (final Iterator<Map.Entry<UUID, Wildfire>> it = wildfires.entrySet().iterator(); it.hasNext(); ) {
            final Map.Entry<UUID, Wildfire> entry = it.next();
            final org.bukkit.entity.Entity entity = plugin.getServer().getEntity(entry.getKey());
            final Wildfire fire = entry.getValue();
            if (!(entity instanceof LivingEntity burning) || !burning.isValid() || burning.isDead() || !plugin.ailments().has(burning, AilmentType.IGNITE)
                    || now > fire.untilTick()) {
                it.remove();
                continue;
            }
            final Player owner = plugin.getServer().getPlayer(fire.owner());
            if (owner == null) {
                it.remove();
                continue;
            }
            final tw.exilecore.ailment.AilmentState.Ignite ignite = plugin.ailments().ignite(burning);
            for (final LivingEntity near : service.targets().around(burning.getLocation(), fire.radius(), owner)) {
                if (near == burning || plugin.ailments().has(near, AilmentType.IGNITE)) {
                    continue;
                }
                close.add(near.getUniqueId());
                final long since = nearFire.computeIfAbsent(near.getUniqueId(), uuid -> now);
                if (now - since >= 20 && ignite != null) {
                    plugin.ailments().copyIgnite(near, ignite);
                    nearFire.remove(near.getUniqueId());
                }
            }
        }
        nearFire.keySet().retainAll(close);
    }

    /** 召喚物相關的增益：Loyalty（夥伴替你挨打）。Commandment 2026-10-27 改在召喚物自己的指令冷卻（MinionService）。 */
    private void tickMinionBuffs() {
        if (service.minions() == null) {
            return;
        }
        final Map<UUID, double[]> perOwner = new HashMap<>();
        for (final MinionService.Minion minion : service.minions().all()) {
            if (!minion.mob.isValid()) {
                continue;
            }
            for (final SupportDefinition support : minion.build.supports()) {
                for (final Modifier modifier : support.modifiers()) {
                    if (modifier.stat().equals(SupportExtraKeys.LOYALTY)) {
                        perOwner.computeIfAbsent(minion.owner, uuid -> new double[2])[0] = Math.max(perOwner.get(minion.owner)[0], modifier.value());
                    }
                }
            }
        }
        for (final Map.Entry<UUID, double[]> entry : perOwner.entrySet()) {
            final Player owner = plugin.getServer().getPlayer(entry.getKey());
            if (owner == null) {
                continue;
            }
            final List<Modifier> mods = new ArrayList<>();
            if (entry.getValue()[0] > 0) {
                mods.add(Modifier.flat(tw.exilecore.stats.TreeNewMechanicKeys.COMPANION_TAKES, entry.getValue()[0], "忠誠"));
            }
            if (!mods.isEmpty()) {
                service.buff(owner, "support.minion", "召喚物輔助", 30, mods);
            }
        }
    }

    /** Relentless Rage：花掉的怒氣（SkillResources.spendRage 呼叫）。 */
    void rageSpent(final Player player, final double amount) {
        if (amount > 0) {
            rageSpent.merge(player.getUniqueId(), amount, Double::sum);
        }
    }

    void forget(final UUID id) {
        blocks.remove(id);
        lastMoved.remove(id);
        stoicSince.remove(id);
        lastAt.remove(id);
        pending.remove(id);
        rageSpent.remove(id);
        perfection.remove(id);
        combos.remove(id);
        barrierUsing.remove(id);
        barrierUntil.remove(id);
        stomps.remove(id);
        banners.removeIf(each -> each.owner().equals(id));
        incisions.remove(id);
        thorns.remove(id);
        resolve.remove(id);
        resolveTimers.remove(id);
        ripStacks.remove(id);
        final String prefix = id + "/";
        critSince.keySet().removeIf(key -> key.startsWith(prefix));
        uses.keySet().removeIf(key -> key.startsWith(prefix));
        boostReady.keySet().removeIf(key -> key.startsWith(prefix));
        alignment.keySet().removeIf(key -> key.startsWith(prefix));
        bolts.keySet().removeIf(key -> key.startsWith(prefix));
        spectral.keySet().removeIf(key -> key.startsWith(prefix));
        gratification.keySet().removeIf(key -> key.startsWith(prefix));
        grounds.removeIf(each -> each.owner.equals(id));
        siphons.values().removeIf(each -> each.owner().equals(id));
    }
}
