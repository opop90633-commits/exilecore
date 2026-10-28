package tw.exilecore.ailment;

import net.kyori.adventure.text.Component;
import net.kyori.adventure.util.TriState;
import org.bukkit.GameMode;
import org.bukkit.Location;
import org.bukkit.NamespacedKey;
import org.bukkit.Particle;
import org.bukkit.Sound;
import org.bukkit.attribute.Attribute;
import org.bukkit.attribute.AttributeInstance;
import org.bukkit.attribute.AttributeModifier;
import org.bukkit.entity.Entity;
import org.bukkit.entity.LivingEntity;
import org.bukkit.entity.Mob;
import org.bukkit.entity.Player;
import org.bukkit.persistence.PersistentDataType;
import tw.exilecore.ExileCore;
import tw.exilecore.character.PlayerState;
import tw.exilecore.monster.MonsterData;
import tw.exilecore.monster.MonsterRarity;
import tw.exilecore.stats.DamageType;
import tw.exilecore.stats.HitResult;
import tw.exilecore.stats.SkillProfile;
import tw.exilecore.stats.StatKeys;
import tw.exilecore.stats.StatSheet;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.random.RandomGenerator;

/**
 * 異常狀態（階段 3 批次 3；流血、中毒照 PoE2 補上）：打中時算點燃、冰緩、凍結、感電、流血、中毒，
 * 之後每半秒扣持續傷害的血、更新減速與凍結、畫粒子與標記。怪物與玩家都會中（怪物帶火／冰／電／撕裂／蝕毒詞綴時打玩家也會）。
 * <ul>
 *   <li>點燃：怪物直接扣原版血量（燒死時算點燃的玩家殺的，掉落與經驗照給）；玩家先扣護盾再扣生命。身上會冒火（只是外觀）。</li>
 *   <li>冰緩：移動變慢；玩家的攻擊與施法也變慢（技能模組看 {@link #actionSpeed}）。</li>
 *   <li>凍結：不能動、不能攻擊、不能施放（怪物的 AI 暫停），身上結霜發抖。</li>
 *   <li>感電：受到的傷害增加（戰鬥模組看 {@link #damageTakenMultiplier}，持續傷害也吃）。</li>
 *   <li>流血：物理持續傷害，只有最強的在流，目標移動時兩倍（動作列的 ⚠）。身上滴血。</li>
 *   <li>中毒：混沌持續傷害，可以疊加（名字後面寫 毒×3）。</li>
 * </ul>
 * 減速用暫時的屬性修飾器（不會存進存檔）；暫停 AI 時在怪物身上做記號，萬一區塊卸載或當機，下次載入時自動恢復。
 */
public final class AilmentService {

    /** 每幾 tick 更新一次（點燃扣血、效果、粒子）。 */
    public static final long TICK_INTERVAL = 10;
    private static final Particle.DustOptions BLOOD = new Particle.DustOptions(org.bukkit.Color.fromRGB(0xB01818), 1.1f);
    private static final Particle.DustOptions VENOM = new Particle.DustOptions(org.bukkit.Color.fromRGB(0x6FC22C), 1.0f);
    private static final Particle.DustOptions CORRUPTED = new Particle.DustOptions(org.bukkit.Color.fromRGB(0x5A0A2A), 1.2f);
    /** 2026-10-23：怪物給玩家的癱瘓、阻礙、致盲持續幾秒（PoE2：4 秒）。 */
    static final double HEX_SECONDS = 4;
    /** 2026-10-23：腐化之血每層每秒 = 那一下傷害的幾成；持續幾秒（每次打中重新計時）。 */
    static final double CORRUPTED_BLOOD_PER_STACK = 0.05;
    static final double CORRUPTED_BLOOD_SECONDS = 4;

    private static boolean rollPercent(final double percent, final RandomGenerator random) {
        return percent > 0 && (percent >= 100 || random.nextDouble() * 100 < percent);
    }

    private final ExileCore plugin;
    private final AilmentConfig config;
    private final Map<UUID, Tracked> tracked = new HashMap<>();
    private final NamespacedKey slowKey;
    private final NamespacedKey jumpKey;
    private final NamespacedKey unawareKey;
    private final NamespacedKey touchedKey;

    /** 一個身上有異常的實體，以及我們對它做過的改變（結束時要還原）。 */
    private static final class Tracked {
        final AilmentState state = new AilmentState();
        final boolean player;
        /** 2026-10-24：累積的失血（流血打掉的傷害；血宣的爆炸照它的 15% 算）。 */
        double bloodLoss;
        double appliedSlow;
        boolean jumpBlocked;
        boolean madeUnaware;
        boolean unawarePending;
        boolean visualFire;
        boolean frozenVisual;
        String marks = "";

        Tracked(final boolean player) {
            this.player = player;
        }
    }

    public AilmentService(final ExileCore plugin, final AilmentConfig config) {
        this.plugin = plugin;
        this.config = config;
        this.slowKey = new NamespacedKey(plugin, "ailment_slow");
        this.jumpKey = new NamespacedKey(plugin, "ailment_jump");
        this.unawareKey = new NamespacedKey(plugin, "ailment_unaware");
        this.touchedKey = new NamespacedKey(plugin, "ailment_touched");
    }

    public AilmentConfig config() {
        return config;
    }


    private long now() {
        return plugin.getServer().getCurrentTick();
    }

    private static long ticks(final double seconds) {
        return Math.max(1, Math.round(seconds * 20));
    }

    /**
     * 讓 target 中毒，視同 source 造成 baseChaos 點基礎混沌傷害（2026-10-15，蛛巢壁壘的「格擋時使敵人中毒」）：
     * 吃 source 的中毒傷害、中毒持續時間加成；混沌免疫、避免中毒的不會中。回傳有沒有中。
     */
    public boolean poisonFrom(final Player source, final LivingEntity target, final double baseChaos, final StatSheet sourceSheet) {
        if (baseChaos <= 0 || target == null || !target.isValid() || target.isDead() || target instanceof Player) {
            return false;
        }
        final java.util.Optional<tw.exilecore.monster.MonsterData> data = plugin.monsters() == null ? java.util.Optional.empty()
                : plugin.monsters().data(target);
        final StatSheet defenderSheet = data.isPresent() ? plugin.monsters().sheet(target, data.get()) : null;
        final RandomGenerator random = plugin.items().random();
        if (defenderSheet != null && (StatKeys.flag(defenderSheet, StatKeys.FLAG_CHAOS_IMMUNE)
                || avoided(defenderSheet, StatKeys.AVOID_POISON, random))) {
            return false;
        }
        final Set<String> context = Set.of(tw.exilecore.stats.Tags.CHAOS);
        final double faster = tw.exilecore.stats.TreeEffectKeys.ailmentSpeed(sourceSheet, null, context); // 2026-10-26：傷害型異常快 x%
        final double dps = config.poisonDps(baseChaos, sourceSheet.increased(StatKeys.POISON_MAGNITUDE, context),
                sourceSheet.more(StatKeys.POISON_MAGNITUDE, context)) * faster;
        final double seconds = AilmentConfig.duration(config.poisonDuration(),
                sourceSheet.increased(StatKeys.POISON_DURATION, context) + sourceSheet.increased(StatKeys.AILMENT_DURATION, context), 0) / faster;
        final long now = now();
        track(target).state.addPoison(dps * resistFactor(defenderSheet, DamageType.CHAOS), now + ticks(seconds), source.getUniqueId(),
                poisonStacks(sourceSheet, context));
        feedback(target, AilmentType.POISON);
        return true;
    }

    // ---- 2026-10-26：傳奇的特殊效果用（tw.exilecore.item.unique） ----

    /**
     * 讓 target 點燃，視同 source 造成 baseFire 點火焰傷害（行灰之履的燃燒地面）：吃 source 的點燃傷害、持續時間加成；
     * 避免點燃的不會中。回傳有沒有中。
     */
    public boolean igniteFrom(final Player source, final LivingEntity target, final double baseFire, final StatSheet sourceSheet) {
        return igniteFrom(source, target, baseFire, sourceSheet, 0);
    }

    /**
     * 2026-10-27 照 PoE2 的點燃地面（Ignited Ground）：站在上面每次對敵人施加易燃（照 baseFire 當成這一下的火焰傷害算，
     * 跟擊中一樣吃點燃機率的增加），易燃會疊（8 秒）；身上的易燃總和到 50% 才點燃，不擲機率。點燃了回傳 true。
     */
    public boolean groundIgnite(final Player source, final LivingEntity target, final double baseFire, final StatSheet sourceSheet) {
        if (baseFire <= 0 || target == null || !target.isValid() || target.isDead() || target instanceof Player) {
            return false;
        }
        final java.util.Optional<tw.exilecore.monster.MonsterData> data = plugin.monsters() == null ? java.util.Optional.empty()
                : plugin.monsters().data(target);
        final StatSheet defenderSheet = data.isPresent() ? plugin.monsters().sheet(target, data.get()) : new StatSheet();
        final double threshold = AilmentConfig.threshold(baseThreshold(target, data.orElse(null), defenderSheet)
                        + Math.max(0, defenderSheet.flat(StatKeys.AILMENT_THRESHOLD, Set.of())),
                defenderSheet.increased(StatKeys.AILMENT_THRESHOLD, Set.of()));
        final Set<String> context = Set.of(tw.exilecore.stats.Tags.FIRE);
        final double flammable = config.flammability(baseFire, threshold, sourceSheet.flat(StatKeys.IGNITE_CHANCE, context),
                sourceSheet.increased(StatKeys.IGNITE_CHANCE, context)) * sourceSheet.more(StatKeys.IGNITE_CHANCE, context);
        final long now = now();
        final Tracked entry = track(target);
        final double total = entry.state.addFlammability(flammable, now + ticks(config.flammabilityDuration(false)), now);
        return total >= GROUND_FLAMMABILITY && igniteFrom(source, target, baseFire, sourceSheet);
    }

    /** 2026-10-27 照 PoE2：地面效果的易燃總和到 50% 才點燃（poe2db Ignited Ground）。 */
    public static final double GROUND_FLAMMABILITY = 50;

    /** 同上，基礎持續時間換成 baseSeconds 秒（0 = 照點燃的預設；2026-10-26 燼使照 PoE2 是 3 秒）。 */
    public boolean igniteFrom(final Player source, final LivingEntity target, final double baseFire, final StatSheet sourceSheet,
                              final double baseSeconds) {
        if (baseFire <= 0 || target == null || !target.isValid() || target.isDead() || target instanceof Player) {
            return false;
        }
        final java.util.Optional<tw.exilecore.monster.MonsterData> data = plugin.monsters() == null ? java.util.Optional.empty()
                : plugin.monsters().data(target);
        final StatSheet defenderSheet = data.isPresent() ? plugin.monsters().sheet(target, data.get()) : null;
        if (defenderSheet != null && avoided(defenderSheet, StatKeys.AVOID_IGNITE, plugin.items().random())) {
            return false;
        }
        final Set<String> context = Set.of(tw.exilecore.stats.Tags.FIRE);
        final double faster = tw.exilecore.stats.TreeEffectKeys.ailmentSpeed(sourceSheet, tw.exilecore.stats.TreeEffectKeys.IGNITE_SPEED, context); // 2026-10-26
        final double dps = config.igniteDps(baseFire, sourceSheet.increased(StatKeys.IGNITE_MAGNITUDE, context),
                sourceSheet.more(StatKeys.IGNITE_MAGNITUDE, context)) * faster;
        final double seconds = AilmentConfig.duration(baseSeconds > 0 ? baseSeconds : config.igniteDuration(),
                sourceSheet.increased(StatKeys.IGNITE_DURATION, context) + sourceSheet.increased(StatKeys.AILMENT_DURATION, context), 0)
                * sourceSheet.more(StatKeys.IGNITE_DURATION, context) / faster; // 2026-10-27：更多的點燃持續時間（熔岩噴射、燃燒射擊）以前沒算到
        final Tracked entry = track(target);
        final boolean chaos = chaosIgnite(sourceSheet); // 2026-10-28 照 PoE2 Blackflame（黯炎）：你施加的點燃造成混沌傷害
        entry.state.addIgnite(dps, now() + ticks(seconds), source.getUniqueId(), chaos);
        igniteFeedback(target, chaos);
        update(target, entry, now());
        return true;
    }

    /** 這個目標身上最強的點燃（沒有回傳 null）。 */
    public AilmentState.Ignite ignite(final LivingEntity entity) {
        final Tracked entry = tracked.get(entity.getUniqueId());
        return entry == null ? null : entry.state.ignite(now());
    }

    /** 把一個點燃（每秒傷害、到哪個 tick、誰點的）原樣加到 target 身上（點燃擴散）。 */
    public void copyIgnite(final LivingEntity target, final AilmentState.Ignite ignite) {
        if (ignite == null || ignite.endTick() <= now() || target instanceof Player || !target.isValid() || target.isDead()) {
            return;
        }
        final Tracked entry = track(target);
        entry.state.addIgnite(ignite.dps(), ignite.endTick(), ignite.source(), ignite.chaos()); // 2026-10-28：擴散過去的混沌點燃還是混沌
        feedback(target, AilmentType.IGNITE);
        update(target, entry, now());
    }

    /** 冰緩效果（0.3 = 30%；沒有冰緩是 0）。 */
    public double chillMagnitude(final LivingEntity entity) {
        final Tracked entry = tracked.get(entity.getUniqueId());
        return entry == null ? 0 : entry.state.chill(now());
    }

    /** 加劇 source 在 target 身上造成的流血；回傳加劇了幾條。 */
    public int aggravateBleeds(final LivingEntity target, final UUID source) {
        final Tracked entry = tracked.get(target.getUniqueId());
        return entry == null ? 0 : entry.state.aggravateBleeds(source, now(), config.bleedMovingMultiplier());
    }

    // ---- 給其他模組查詢 ----

    /** 受到的傷害倍率（感電：1.2 = 增加 20%）。 */
    public double damageTakenMultiplier(final LivingEntity entity) {
        final Tracked entry = tracked.get(entity.getUniqueId());
        return entry == null ? 1.0 : 1.0 + entry.state.shock(now());
    }

    /** 身上有沒有這種異常（昇華的招牌天賦用：對點燃、冰緩、凍結、中毒中的敵人）。 */
    public boolean has(final LivingEntity entity, final AilmentType type) {
        final Tracked entry = tracked.get(entity.getUniqueId());
        if (entry == null) {
            return false;
        }
        final long now = now();
        return switch (type) {
            case IGNITE -> entry.state.ignite(now) != null;
            case CHILL -> entry.state.chill(now) > 0 || entry.state.frozen(now);
            case FREEZE -> entry.state.frozen(now);
            case SHOCK -> entry.state.shock(now) > 0;
            case BLEED -> entry.state.bleed(now) != null;
            case POISON -> !entry.state.poisons(now).isEmpty();
            case STUN, ELECTROCUTE -> entry.state.incapacitated(now);
        };
    }

    /** 2026-10-26（護符：受到緩速時）：身上有沒有緩速（冰緩、癱瘓、阻礙、減速地面）。 */
    public boolean slowed(final LivingEntity entity) {
        final Tracked entry = tracked.get(entity.getUniqueId());
        if (entry == null) {
            return false;
        }
        final long now = now();
        return entry.state.chill(now) > 0 || entry.state.hexSlow(now) > 0;
    }

    /** 2026-10-26（PoE2 銀護符：速度不受緩速影響）：解掉身上的冰緩、癱瘓、阻礙、減速地面，移動速度馬上還原。 */
    public void clearSlows(final LivingEntity entity) {
        final Tracked entry = tracked.get(entity.getUniqueId());
        if (entry == null) {
            return;
        }
        entry.state.clearSlows();
        if (entry.state.isEmpty(plugin.getServer().getCurrentTick())) {
            tracked.remove(entity.getUniqueId());
            restore(entity, entry);
        } else {
            update(entity, entry, now());
        }
    }

    /** 2026-10-17：是不是重暈中（暈眩條滿了的那種；輕暈、凍結、電擊不算）。 */
    public boolean heavyStunned(final LivingEntity entity) {
        final Tracked entry = tracked.get(entity.getUniqueId());
        return entry != null && entry.state.stun().active(now());
    }

    /** 身上疊了幾層中毒。 */
    public int poisonStacks(final LivingEntity entity) {
        final Tracked entry = tracked.get(entity.getUniqueId());
        return entry == null ? 0 : entry.state.poisons(now()).size();
    }

    /** 2026-10-26：致盲中（天賦樹「對致盲的敵人」）。 */
    public boolean blinded(final LivingEntity entity) {
        final Tracked entry = tracked.get(entity.getUniqueId());
        return entry != null && entry.state.blindUntil() > now();
    }

    /** 2026-10-26：電擊中（天賦樹「對電擊的敵人」；凍結、暈眩不算）。 */
    public boolean electrocuted(final LivingEntity entity) {
        final Tracked entry = tracked.get(entity.getUniqueId());
        return entry != null && entry.state.electrocute().active(now());
    }

    /** 2026-10-26：阻礙中（天賦樹「對阻礙的敵人」）。 */
    public boolean hindered(final LivingEntity entity) {
        final Tracked entry = tracked.get(entity.getUniqueId());
        return entry != null && entry.state.hinderUntil() > now();
    }

    public boolean isFrozen(final LivingEntity entity) {
        final Tracked entry = tracked.get(entity.getUniqueId());
        return entry != null && entry.state.frozen(now());
    }

    /** 不能動也不能攻擊、施放：凍結、暈眩或電擊中。 */
    public boolean isIncapacitated(final LivingEntity entity) {
        final Tracked entry = tracked.get(entity.getUniqueId());
        return entry != null && entry.state.incapacitated(now());
    }

    /**
     * 別的地方（Boss 招式放招時）暫停了這隻怪物的 AI，現在要放手，但牠正被凍結、暈眩：交給這裡，控制結束時恢復
     * （這裡看到 AI 已經停了不會記成自己停的，不接手的話凍結中就會被恢復、或結束後沒人恢復）。
     * 回傳有沒有接手；沒有（沒被控住、AI 本來就在跑）呼叫端自己恢復。
     */
    public boolean adoptPause(final Mob mob) {
        final Tracked entry = tracked.get(mob.getUniqueId());
        if (entry == null || entry.player || mob.isAware() || !entry.state.incapacitated(now())) {
            return false;
        }
        if (!entry.madeUnaware) {
            entry.madeUnaware = true;
            mob.getPersistentDataContainer().set(unawareKey, PersistentDataType.BYTE, (byte) 1);
        }
        return true;
    }

    /** 動作速度倍率：冰緩 30% → 0.7；凍結、暈眩、電擊 → 0；沒事 → 1。 */
    public double actionSpeed(final LivingEntity entity) {
        final Tracked entry = tracked.get(entity.getUniqueId());
        if (entry == null) {
            return 1.0;
        }
        final long now = now();
        return entry.state.incapacitated(now) ? 0 : Math.max(0.05, 1.0 - entry.state.chill(now));
    }

    // ---- 打中 ----

    /**
     * 2026-10-28 照 PoE2 Voltaic Mark（擊中被標記的敵人時電擊累積增加 25% → 45%）：電擊累積的倍率 =
     * (1 + 攻擊者的增加 + 目標身上印記的增加) × 攻擊者的更多。這次之前只看攻擊者（印記是降閃電抗性）。
     */
    public static double electrocuteBuildupScale(final double attackerIncreased, final double attackerMore, final double markedIncreased) {
        return Math.max(0, 1 + attackerIncreased + markedIncreased) * Math.max(0, attackerMore);
    }

    /**
     * 一擊確定打中之後（傷害事件沒被取消）算異常。
     *
     * @param attackerSheet   攻擊方的屬性表（還沒疊技能的輔助寶石）
     * @param takenMultiplier 這一擊算傷害時乘的「受到的傷害」倍率（感電）；異常用乘之前的傷害算
     * @param defenderMonster 防禦方是怪物時的資料（玩家為 null）
     */
    public void onHit(final LivingEntity attacker, final LivingEntity defender, final SkillProfile skill,
                      final StatSheet attackerSheet, final StatSheet defenderSheet, final HitResult result,
                      final double takenMultiplier, final MonsterData defenderMonster) {
        onHit(attacker, defender, skill, attackerSheet, defenderSheet, result, takenMultiplier, defenderMonster, 1.0);
    }

    /**
     * 同上；stunScale：暈眩（重暈累積、輕暈機率）用的傷害倍率。
     * 2026-10-18：Boss 的每秒傷害遞減之後，暈眩照遞減後的傷害算（以前照遞減前的幾百萬算，Boss 一直被暈、被打斷）
     */
    public void onHit(final LivingEntity attacker, final LivingEntity defender, final SkillProfile skill,
                      final StatSheet attackerSheet, final StatSheet defenderSheet, final HitResult result,
                      final double takenMultiplier, final MonsterData defenderMonster, final double stunScale) {
        onHit(attacker, defender, skill, attackerSheet, defenderSheet, result, takenMultiplier, defenderMonster, stunScale, java.util.Map.of());
    }

    /**
     * 同上；typeTaken：這一擊每種傷害另外乘的「受到的這種傷害增加」（2026-10-21：凋息、縛根）。異常一樣用乘之前的傷害算
     * （中毒、流血、點燃每一跳另外照減益乘，不然會乘兩次）。
     */
    public void onHit(final LivingEntity attacker, final LivingEntity defender, final SkillProfile skill,
                      final StatSheet attackerSheet, final StatSheet defenderSheet, final HitResult result,
                      final double takenMultiplier, final MonsterData defenderMonster, final double stunScale,
                      final java.util.Map<DamageType, Double> typeTaken) {
        if (!result.hit() || result.blocked() || !defender.isValid() || defender.isDead()) {
            return;
        }
        if (defender instanceof Player player && !canBeAilmented(player)) {
            return;
        }
        final StatSheet attack = skill.effective(attackerSheet);
        // 純化：被輔助的技能不能造成元素異常（點燃、冰緩、凍結、感電）；中毒、流血、暈眩、電擊照常
        final boolean noElemental = StatKeys.flag(attack, StatKeys.FLAG_NO_AILMENTS);
        // 元素異常門檻（點燃、冰緩、感電）；2026-10-26 照 PoE2：怪物用等級表、玩家用生命的 50%
        final double baseThreshold = baseThreshold(defender, defenderMonster, defenderSheet);
        // 2026-10-26 照 PoE2 天賦樹：「+x 異常門檻」（等同最大護盾 x% 的那種）加在基礎門檻上
        final double threshold = AilmentConfig.threshold(baseThreshold + Math.max(0, defenderSheet.flat(StatKeys.AILMENT_THRESHOLD, Set.of())),
                defenderSheet.increased(StatKeys.AILMENT_THRESHOLD, Set.of()));
        if (threshold <= 0) {
            return;
        }
        // 2026-10-26 照 PoE2：暈眩門檻（poise；重暈、凍結、電擊）。凍結、電擊再乘「增加元素異常門檻」
        final double poise = basePoise(defender, defenderMonster, defenderSheet);
        final double freezePoise = AilmentConfig.threshold(poise, defenderSheet.increased(StatKeys.AILMENT_THRESHOLD, Set.of()));
        // 2026-10-26 照 PoE2：異常用目標減免前的傷害（這次之前用抗性、護甲減免後的，再除掉感電之類的倍率）
        final double fire = result.outgoing().get(DamageType.FIRE);
        final double cold = result.outgoing().get(DamageType.COLD);
        final double lightning = result.outgoing().get(DamageType.LIGHTNING);
        final double physical = result.outgoing().get(DamageType.PHYSICAL);
        final double chaos = StatKeys.flag(defenderSheet, StatKeys.FLAG_CHAOS_IMMUNE) ? 0 : result.outgoing().get(DamageType.CHAOS);
        if (fire <= 0 && cold <= 0 && lightning <= 0 && physical <= 0 && chaos <= 0) {
            return;
        }
        final boolean playerTarget = defender instanceof Player;
        final double selfDuration = defenderSheet.increased(StatKeys.SELF_AILMENT_DURATION, Set.of());
        final RandomGenerator random = plugin.items().random();
        final long now = now();
        Tracked entry = tracked.get(defender.getUniqueId());
        boolean changed = false;

        // PoE2 昇華：所有傷害都算進點燃／冰緩／感電（燃燒使者、冬之塑造者、風暴塑造者）
        final double allDamage = fire + cold + lightning + physical + chaos;
        // 2026-10-26（昇華・紛亂）：混沌傷害輪流也算進凍結（0）、點燃（1）或感電（2）
        final int unravel = tw.exilecore.ascendancy.AscendancyEffects.unravel(attacker);
        // 2026-10-27 照 PoE2 血脈輔助：燼蝕（混沌算進點燃）、霆蝕（混沌算進感電）、寂寒（混沌算進凍結）、傲寒（物理算進凍結）、霆爆（不能感電）
        final boolean chaosIgnites = StatKeys.flag(attack, tw.exilecore.stats.LineageKeys.CHAOS_IGNITES);
        final boolean chaosShocks = StatKeys.flag(attack, tw.exilecore.stats.LineageKeys.CHAOS_SHOCKS);
        final double igniteBase = noElemental ? 0 : StatKeys.flag(attack, StatKeys.FLAG_ALL_IGNITE) ? allDamage
                : fire + (unravel == 1 || chaosIgnites ? chaos : 0);
        // 2026-10-26（赤紋之戒）：對流血中的敵人／你流血時被打，擊中的所有傷害都算進冰緩
        final boolean bleedingChill = entry != null && (StatKeys.flag(attack, tw.exilecore.item.unique.UniqueStats.BLEEDING_TARGET_CHILL)
                || StatKeys.flag(defenderSheet, tw.exilecore.item.unique.UniqueStats.BLEEDING_SELF_CHILL)) && entry.state.bleed(now) != null;
        final double chillBase = noElemental ? 0 : StatKeys.flag(attack, StatKeys.FLAG_ALL_CHILL) || bleedingChill ? allDamage : cold;
        final double shockBase = noElemental || StatKeys.flag(attack, tw.exilecore.stats.LineageKeys.LIGHTNING_ELECTROCUTES) ? 0
                : StatKeys.flag(attack, StatKeys.FLAG_ALL_SHOCK) ? allDamage : lightning + (unravel == 2 || chaosShocks ? chaos : 0);
        // 2026-10-27 照 PoE2 Chaotic Freeze：對受到持續傷害（混沌減益、中毒）的敵人，混沌傷害也累積凍結；這次之前沒有
        final double chaoticFreeze = chaos > 0 && unravel != 0 && StatKeys.flag(attack, tw.exilecore.stats.SupportExtraKeys.FLAG_CHAOTIC_FREEZE)
                && entry != null && (debuffDps(defender) > 0 || !entry.state.poisons(now).isEmpty()) ? chaos : 0;
        if (igniteBase > 0) {
            final Set<String> context = skill.contextFor(DamageType.FIRE);
            // 2026-10-26 照 PoE2：這一擊先疊一層易燃（怪物身上 8 秒、玩家 4 秒），點燃機率 = 身上所有易燃加起來
            // （這次之前每一擊各自擲「門檻比例 × 25 ＋ 點燃機率」）
            final double flammable = config.flammability(igniteBase, threshold, attack.flat(StatKeys.IGNITE_CHANCE, context),
                    attack.increased(StatKeys.IGNITE_CHANCE, context))
                    * attack.more(StatKeys.IGNITE_CHANCE, context); // 2026-10：輔助的「更多／更少機率」
            entry = entry == null ? track(defender) : entry;
            final double chance = Math.min(1.0, entry.state.addFlammability(flammable, now + ticks(config.flammabilityDuration(playerTarget)), now) / 100.0);
            if (chance > 0 && random.nextDouble() < chance && !avoided(defenderSheet, StatKeys.AVOID_IGNITE, random)
                    && !oneDamagingAilment(defender, defenderSheet)) { // 2026-10-26 照 PoE2 天賦樹：身上已經有傷害型異常就不會再中
                // 2026-10-26 照 PoE2 天賦樹：點燃（傷害型異常）造成傷害快 x%：每秒傷害乘、時間除（總傷害不變）
                final double igniteFaster = tw.exilecore.stats.TreeEffectKeys.ailmentSpeed(attack, tw.exilecore.stats.TreeEffectKeys.IGNITE_SPEED, context);
                // 2026-10-28 照 PoE2 Blackflame（黯炎）：玩家的點燃造成混沌傷害而非火焰傷害——吃混沌抗性、混沌免疫的不受傷；這次之前一律火焰
                final boolean chaosIgnite = attacker instanceof Player && chaosIgnite(attack);
                final DamageType igniteType = chaosIgnite ? DamageType.CHAOS : DamageType.FIRE;
                final double dps = config.igniteDps(igniteBase, attack.increased(StatKeys.IGNITE_MAGNITUDE, context),
                        attack.more(StatKeys.IGNITE_MAGNITUDE, context)) * igniteResist(defenderSheet, igniteType) // 2026-10-26：減免前的傷害，再吃火焰抗性
                        * tw.exilecore.stats.TreeKeys.selfEffect(defenderSheet, tw.exilecore.stats.TreeKeys.IGNITE_EFFECT_ON_SELF) // 2026-10-26：身上的點燃效果
                        * igniteFaster;
                final double seconds = AilmentConfig.duration(config.igniteDuration(),
                        attack.increased(StatKeys.IGNITE_DURATION, context) + attack.increased(StatKeys.AILMENT_DURATION, context),
                        selfDuration + defenderSheet.increased(StatKeys.SELF_AILMENT_DURATION + ".ignite", Set.of()))
                        * attack.more(StatKeys.IGNITE_DURATION, context) / igniteFaster; // 2026-10-27：更多的點燃持續時間以前沒算到
                entry = entry == null ? track(defender) : entry;
                entry.state.addIgnite(dps, now + ticks(seconds), attacker instanceof Player ? attacker.getUniqueId() : null, chaosIgnite);
                if (attacker instanceof Player igniter && plugin.skills() != null && !skill.tags().contains(tw.exilecore.stats.Tags.TOTEM_HIT)) {
                    // 2026-10-26 照 PoE2 天賦樹：擊中點燃敵人時獲得怒氣
                    final double rage = attack.flat(tw.exilecore.stats.TreeEffectKeys.RAGE_ON_IGNITE, Set.of());
                    if (rage > 0) {
                        plugin.skills().resources().gainRage(igniter, rage);
                    }
                }
                igniteFeedback(defender, chaosIgnite);
                if (attacker instanceof Player self && self != defender && StatKeys.flag(attack, tw.exilecore.stats.KeystoneKeys.IGNITE_REFLECT)) {
                    // 2026-10-26 照 PoE2 天賦樹：你造成的點燃會反射到自己身上（照沒吃目標抗性的點燃傷害，再吃你自己的火焰抗性）
                    igniteSelf(self, config.igniteDps(igniteBase, attack.increased(StatKeys.IGNITE_MAGNITUDE, context),
                            attack.more(StatKeys.IGNITE_MAGNITUDE, context)), seconds, chaosIgnite);
                }
                if (attacker instanceof Player triggerer && plugin.skills() != null) {
                    // 主要寶石：元素異常時施放（2026-10）；2026-10-26 照 PoE2：點燃的能量照這個點燃總傷害占異常門檻的 %
                    if (!skill.tags().contains(tw.exilecore.stats.Tags.TOTEM_HIT)) plugin.skills().triggers().ailment(triggerer, defender, AilmentType.IGNITE, skill.id(),
                            threshold > 0 ? dps * seconds / threshold * 100 : tw.exilecore.skill.MetaGems.CRIT_PERCENT_FALLBACK);
                }
                chargeEvent(attacker, "ignite", attack, context, defender); // 戰星（2026-10）
                changed = true;
            }
        }
        if (chillBase > 0 || chaoticFreeze > 0) {
            final Set<String> context = skill.contextFor(DamageType.COLD);
            // 2026-10：「更多」的冰緩效果也算；2026-10-21：攻擊方的減速幅度（slow.magnitude）、玩家身上的減速幅度（slow.taken）也乘進去
            final double slowScale = Math.max(0, 1 + attack.increased(StatKeys.SLOW_MAGNITUDE, context));
            // 2026-10-23：怪物（不羈的）也吃；2026-10-26 照 PoE2：稀有度的「緩速效果更少」也算，在 30% 下限判斷之後才乘（不然稀有、傳奇很難冰緩）
            final double slowTaken = Math.max(0, defenderSheet.apply(StatKeys.SLOW_TAKEN, 1.0, Set.of()));
            final double magnitude = config.chillMagnitude(chillBase, threshold, attack.apply(StatKeys.CHILL_EFFECT, 1.0, context) * slowScale
                    * tw.exilecore.stats.TreeKeys.selfEffect(defenderSheet, tw.exilecore.stats.TreeKeys.CHILL_EFFECT_ON_SELF) - 1) * slowTaken; // 2026-10-26：身上的冰緩效果
            if (magnitude > 0 && !StatKeys.flag(defenderSheet, StatKeys.FLAG_UNSLOWABLE) && !avoided(defenderSheet, StatKeys.AVOID_CHILL, random)) {
                final double seconds = AilmentConfig.duration(config.chillDuration(playerTarget),
                        attack.increased(StatKeys.CHILL_DURATION, context) + attack.increased(StatKeys.AILMENT_DURATION, context),
                        selfDuration + defenderSheet.increased(StatKeys.SELF_AILMENT_DURATION + ".chill", Set.of()));
                entry = entry == null ? track(defender) : entry;
                // 2026-10-26（昇華・狂風暴雪）：最多緩速 35%、可以同時兩個
                changed |= entry.state.applyChill(tw.exilecore.ascendancy.AscendancyEffects.stackAilment(attacker, defender, AilmentType.CHILL,
                        magnitude, now + ticks(seconds)), now + ticks(seconds), now);
                if (attacker instanceof Player chiller && !(defender instanceof Player)) {
                    plugin.players().conditions().onAilment(chiller, defender, AilmentType.CHILL, now); // 2026-10-26：天賦樹「最近冰緩過敵人」
                }
            }
            // 2026-10-26 照 PoE2：凍結累積 = 冰冷 × 2.1 ÷ 暈眩門檻（觸發後門檻暫時提高）；這次之前 200% × 冰冷 ÷ 異常門檻，傳奇怪 50%
            // 天賦樹的凍結門檻（增加凍結門檻、等同最大護盾 x% 的凍結門檻）也算
            entry = entry == null ? track(defender) : entry;
            final AilmentConfig.MeterSettings freezeSettings = config.freeze();
            final double buildup = noElemental ? 0 : freezeSettings.buildupFor(cold
                            + (unravel == 0 || StatKeys.flag(attack, tw.exilecore.stats.LineageKeys.CHAOS_FREEZES) ? chaos : 0)
                            + (StatKeys.flag(attack, tw.exilecore.stats.LineageKeys.PHYSICAL_FREEZES) ? physical : 0) + chaoticFreeze,
                    tw.exilecore.stats.TreeKeys.freezeThreshold(freezePoise, defenderSheet) * entry.state.freezeMeter().thresholdMultiplier(now),
                    attack.apply(StatKeys.FREEZE_BUILDUP, 1.0, context)
                            * defenderSheet.more(tw.exilecore.stats.SupportExtraKeys.FREEZE_BUILDUP_TAKEN, Set.of()), playerTarget); // 2026-10-27 照 PoE2：濕透
            if (buildup > 0) {
                if (entry.state.addBuildup(buildup, now, freezeSettings.decayDelayTicks(playerTarget), freezeSettings.decayPerTick(playerTarget))) {
                    if (StatKeys.flag(defenderSheet, StatKeys.FLAG_FREEZE_IMMUNE) || avoided(defenderSheet, StatKeys.AVOID_FREEZE, random)) {
                        entry.state.resetBuildup();
                    } else {
                        final double seconds = AilmentConfig.duration(config.freezeDuration(playerTarget),
                                attack.increased(StatKeys.FREEZE_DURATION, context) + attack.increased(StatKeys.AILMENT_DURATION, context),
                                selfDuration + defenderSheet.increased(StatKeys.SELF_AILMENT_DURATION + ".freeze", Set.of()));
                        entry.state.freeze(now, ticks(seconds), 0);
                        entry.state.freezeMeter().raiseThreshold(freezeSettings.after(playerTarget), freezeSettings.afterTicks(playerTarget), now);
                        refreshAt(defender, entry, entry.state.frozenUntil() - now); // 解凍準時（不用等下一次半秒更新）
                        feedback(defender, AilmentType.FREEZE);
                        if (plugin.skills() != null && !playerTarget) {
                            plugin.skills().markAilment(defender, AilmentType.FREEZE); // 2026-10-28 照 PoE2：霜縛印被凍結時觸發
                        }
                        if (attacker instanceof Player freezer && !playerTarget && plugin.skills() != null && plugin.skills().treeLeftovers() != null) {
                            plugin.skills().treeLeftovers().immobilised(freezer, defender); // 2026-10-27 照 PoE2 天賦樹：禁錮（凍結）時恫嚇
                        }
                        if (attacker instanceof Player triggerer && plugin.skills() != null) {
                            if (!skill.tags().contains(tw.exilecore.stats.Tags.TOTEM_HIT)) plugin.skills().triggers().ailment(triggerer, defender, AilmentType.FREEZE, skill.id()); // 主要寶石（2026-10）
                            plugin.skills().procEvent(triggerer, skill.id(), "freeze", null, defender); // 輔助：凍結時
                        }
                        chargeEvent(attacker, "freeze", attack, context, defender); // 戰星（2026-10）
                    }
                }
                changed = true;
            }
        }
        if (shockBase > 0) {
            final Set<String> context = skill.contextFor(DamageType.LIGHTNING);
            // 2026-10-27 照 PoE2 Drenched：濕透的敵人感電機率更多 50%；Shock Conduction 擊中濕透的一定感電
            final double chance = StatKeys.flag(attack, tw.exilecore.stats.SupportExtraKeys.FLAG_SHOCK_DRENCHED)
                    && StatKeys.flag(defenderSheet, tw.exilecore.stats.SupportExtraKeys.FLAG_DRENCHED) ? 1.0
                    : config.shockChance(shockBase, threshold, attack.flat(StatKeys.SHOCK_CHANCE, context),
                    attack.increased(StatKeys.SHOCK_CHANCE, context))
                    * attack.more(StatKeys.SHOCK_CHANCE, context) // 2026-10：輔助的「更多／更少機率」
                    * defenderSheet.more(tw.exilecore.stats.SupportExtraKeys.SHOCK_CHANCE_TAKEN, Set.of());
            if (chance > 0 && random.nextDouble() < chance && !avoided(defenderSheet, StatKeys.AVOID_SHOCK, random)) {
                final double effect = config.shockEffect(attack.increased(StatKeys.SHOCK_EFFECT, context))
                        * tw.exilecore.stats.TreeKeys.selfEffect(defenderSheet, tw.exilecore.stats.TreeKeys.SHOCK_EFFECT_ON_SELF); // 2026-10-26：身上的感電效果
                final double seconds = AilmentConfig.duration(config.shockDuration(playerTarget),
                        attack.increased(StatKeys.SHOCK_DURATION, context) + attack.increased(StatKeys.AILMENT_DURATION, context),
                        selfDuration + defenderSheet.increased(StatKeys.SELF_AILMENT_DURATION + ".shock", Set.of()));
                entry = entry == null ? track(defender) : entry;
                // 2026-10-26（昇華・二重打擊）：感電幅度更少 25%、可以同時兩個
                if (entry.state.applyShock(tw.exilecore.ascendancy.AscendancyEffects.stackAilment(attacker, defender, AilmentType.SHOCK, effect,
                        now + ticks(seconds)), now + ticks(seconds), now)) {
                    feedback(defender, AilmentType.SHOCK);
                    if (attacker instanceof Player triggerer && plugin.skills() != null) {
                        if (!skill.tags().contains(tw.exilecore.stats.Tags.TOTEM_HIT)) plugin.skills().triggers().ailment(triggerer, defender, AilmentType.SHOCK, skill.id()); // 主要寶石（2026-10）
                    }
                    chargeEvent(attacker, "shock", attack, context, defender); // 戰星（2026-10）
                    changed = true;
                }
            }
        }
        // 流血（PoE2）：擊中的物理傷害（攻擊、法術都可以）；機率看加成（流血機率 +x%）
        // 2026-10-26（昇華・鮮血荊棘）：元素傷害也計入流血幅度
        final double bleedBase = physical + (tw.exilecore.ascendancy.AscendancyEffects.elementalBleed(attacker) ? fire + cold + lightning : 0);
        if (bleedBase > 0 && canBleed(skill)) {
            final Set<String> context = skill.contextFor(DamageType.PHYSICAL);
            // 2026-10-26 照 PoE2：切割（Incision）每層讓牠被流血的機率 +10%（天賦的切割效果另外乘）
            final double incision = plugin.skills() == null || plugin.skills().treeMechanics() == null ? 0
                    : plugin.skills().treeMechanics().incisionBleedChance(defender);
            final double chance = config.bleedChance(bleedBase, threshold, attack.flat(StatKeys.BLEED_CHANCE, context),
                    attack.increased(StatKeys.BLEED_CHANCE, context))
                    * attack.more(StatKeys.BLEED_CHANCE, context) + incision; // 2026-10：輔助的「更多／更少機率」
            if (chance > 0 && random.nextDouble() < chance && !avoided(defenderSheet, StatKeys.AVOID_BLEED, random)
                    && !StatKeys.flag(defenderSheet, StatKeys.FLAG_CHAOS_IMMUNE) // 混沌免疫（PoE2）也免疫流血
                    && !oneDamagingAilment(defender, defenderSheet)
                    && !(result.deflected() && StatKeys.flag(defenderSheet, tw.exilecore.stats.KeystoneKeys.DEFLECT_NO_BLEED))) { // 2026-10-26：被偏斜的擊中不會讓你流血
                // 猩紅突襲（PoE2 基石）：流血加劇（不管目標有沒有在動都照移動算）、基礎持續 1 秒、更多 50%
                final boolean crimson = StatKeys.flag(attack, StatKeys.FLAG_CRIMSON_ASSAULT);
                // 2026-10-26（叛徒之矛）：流血傷害快 x%：每秒傷害乘上去、時間除掉（總傷害不變）
                final double faster = Math.max(0.1, 1 + attack.increased(tw.exilecore.item.unique.UniqueStats.BLEED_FASTER, context)
                        + attack.increased(tw.exilecore.stats.TreeEffectKeys.AILMENT_SPEED, context)); // 2026-10-26 照 PoE2 天賦樹：傷害型異常快 x%
                final double dps = config.bleedDps(bleedBase, attack.increased(StatKeys.BLEED_MAGNITUDE, context),
                        attack.more(StatKeys.BLEED_MAGNITUDE, context)) * (crimson ? 1.5 * config.bleedMovingMultiplier() : 1.0) * faster;
                final double seconds = AilmentConfig.duration(crimson ? 1.0 : config.bleedDuration(),
                        attack.increased(StatKeys.BLEED_DURATION, context) + attack.increased(StatKeys.AILMENT_DURATION, context),
                        selfDuration + defenderSheet.increased(StatKeys.SELF_AILMENT_DURATION + ".bleed", Set.of())) / faster;
                entry = entry == null ? track(defender) : entry;
                entry.state.addBleed(dps, now + ticks(seconds), attacker instanceof Player ? attacker.getUniqueId() : null, crimson);
                if (attacker instanceof Player bleeder && !playerTarget && plugin.skills() != null && plugin.skills().treeLeftovers() != null) {
                    plugin.skills().treeLeftovers().bleedInflicted(bleeder, defender, attack, context); // 2026-10-27 照 PoE2 天賦樹：對釘住的、站在碎地上的加劇
                }
                if (incision > 0) {
                    plugin.skills().treeMechanics().clearIncision(defender); // 被流血了：切割全部拿掉
                }
                feedback(defender, AilmentType.BLEED);
                changed = true;
            }
        }
        // 中毒（PoE2）：物理與混沌傷害都算，可以疊加
        if (physical + chaos > 0) {
            final Set<String> context = skill.contextFor(DamageType.CHAOS);
            final double onCrit = result.crit() ? attack.flat(tw.exilecore.item.unique.UniqueStats.POISON_ON_CRIT, context) / 100.0 : 0; // 2026-10-26（夢鏡面具）
            final double chance = Math.max(onCrit, config.poisonChance(physical + chaos, threshold, attack.flat(StatKeys.POISON_CHANCE, context),
                    attack.increased(StatKeys.POISON_CHANCE, context))
                    * attack.more(StatKeys.POISON_CHANCE, context)); // 2026-10：輔助的「更多／更少機率」
            if (chance > 0 && random.nextDouble() < chance && !avoided(defenderSheet, StatKeys.AVOID_POISON, random)
                    && !StatKeys.flag(defenderSheet, StatKeys.FLAG_CHAOS_IMMUNE) // 2026-10：混沌免疫也免疫中毒
                    && !oneDamagingAilment(defender, defenderSheet)) {
                final double poisonFaster = tw.exilecore.stats.TreeEffectKeys.ailmentSpeed(attack, null, context); // 2026-10-26 照 PoE2 天賦樹：傷害型異常快 x%
                final double dps = config.poisonDps(physical + chaos, attack.increased(StatKeys.POISON_MAGNITUDE, context),
                        attack.more(StatKeys.POISON_MAGNITUDE, context)) * resistFactor(defenderSheet, DamageType.CHAOS) // 2026-10-26：減免前的傷害，再吃混沌抗性
                        * poisonFaster;
                final double seconds = AilmentConfig.duration(config.poisonDuration(),
                        attack.increased(StatKeys.POISON_DURATION, context) + attack.increased(StatKeys.AILMENT_DURATION, context),
                        selfDuration + defenderSheet.increased(StatKeys.SELF_AILMENT_DURATION + ".poison", Set.of()))
                        * attack.more(StatKeys.POISON_DURATION, context) / poisonFaster; // 2026-10：「更少中毒持續時間」（蝕骨劇毒）也算
                entry = entry == null ? track(defender) : entry;
                // 2026-10-26 照 PoE2：層數上限是「同一個來源」的，怪物毒玩家也一樣（每隻怪各自最強的 1 層，一群怪的毒會疊）；
                // 這次之前怪物的毒來源都是 null，全部怪物共用 1 層。怪物打怪物照舊（來源 null）
                final UUID poisonSource = attacker instanceof Player || (attacker != null && defender instanceof Player)
                        ? attacker.getUniqueId() : null;
                entry.state.addPoison(dps, now + ticks(seconds), poisonSource,
                        poisonStacks(attack, context)); // 2026-10-26 照 PoE2：預設 1 層生效，加層數另外算；壓倒性毒性加倍
                if (attacker instanceof Player poisoner && !playerTarget) {
                    plugin.players().conditions().note(poisoner, tw.exilecore.stats.TreeConditions.POISONS, "poison", now); // 2026-10-27：最近讓敵人中毒幾次
                    if (plugin.skills() != null) {
                        plugin.skills().stageAttacks().poisoned(poisoner, defender, dps * seconds); // 2026-10-28 照 PoE2 瘴域：毒苞存下這次中毒的預期傷害
                    }
                }
                feedback(defender, AilmentType.POISON);
                changed = true;
            }
        }
        // 2026-10-23：怪物打中玩家的癱瘓、阻礙、致盲、腐化之血（PoE2；怪物詞綴給機率，珠寶的腐化詞綴可以免疫）
        if (playerTarget && !(attacker instanceof Player)) {
            final Set<String> hexTags = skill.tags() == null ? Set.of() : skill.tags();
            final long hexEnd = now + ticks(HEX_SECONDS);
            final boolean unslowable = StatKeys.flag(defenderSheet, StatKeys.FLAG_UNSLOWABLE);
            if (!unslowable && !StatKeys.flag(defenderSheet, StatKeys.FLAG_MAIM_IMMUNE) && rollPercent(attack.flat(StatKeys.MAIM_CHANCE, hexTags), random)
                    // 2026-10-27 照 PoE2 天賦樹：被偏斜的擊中不會讓你癱瘓（這次之前旗標沒有人讀）
                    && !(result.deflected() && StatKeys.flag(defenderSheet, tw.exilecore.stats.TreeNewMechanicKeys.FLAG_DEFLECT_NO_MAIM))) {
                entry = entry == null ? track(defender) : entry;
                entry.state.maim(hexEnd);
                if (plugin.combat() != null) {
                    // 2026-10-26 照 PoE2：癱瘓也減少 15% 閃避（這次之前只有變慢）
                    plugin.combat().debuffs().apply(defender.getUniqueId(), new tw.exilecore.combat.Debuffs.Debuff("monster_maim",
                            attacker.getUniqueId(), hexEnd, 0, false, null,
                            List.of(tw.exilecore.stats.Modifier.increased(StatKeys.EVASION, -tw.exilecore.combat.HitEffects.MAIM_EVASION, "癱瘓")), 0));
                }
                defender.getWorld().spawnParticle(Particle.DAMAGE_INDICATOR, defender.getLocation().add(0, 0.3, 0), 4, 0.3, 0.1, 0.3, 0);
                changed = true;
            }
            if (!unslowable && !StatKeys.flag(defenderSheet, StatKeys.FLAG_HINDER_IMMUNE) && rollPercent(attack.flat(StatKeys.HINDER_CHANCE, hexTags), random)) {
                entry = entry == null ? track(defender) : entry;
                entry.state.hinder(hexEnd);
                defender.getWorld().spawnParticle(Particle.SQUID_INK, defender.getLocation().add(0, 0.2, 0), 5, 0.3, 0.05, 0.3, 0.01);
                changed = true;
            }
            if (!StatKeys.flag(defenderSheet, StatKeys.FLAG_BLIND_IMMUNE) && rollPercent(attack.flat(StatKeys.BLIND_CHANCE, hexTags), random)) {
                entry = entry == null ? track(defender) : entry;
                entry.state.blind(hexEnd);
                if (plugin.combat() != null) {
                    // 命中與閃避更少 20%：戰鬥模組照減益疊進屬性表（玩家打怪、怪打玩家都算）
                    plugin.combat().debuffs().apply(defender.getUniqueId(), new tw.exilecore.combat.Debuffs.Debuff("monster_blind",
                            attacker.getUniqueId(), hexEnd, 0, false, null,
                            List.of(tw.exilecore.stats.Modifier.more(StatKeys.ACCURACY, -tw.exilecore.combat.HitEffects.BLIND_LESS, "致盲"),
                                    tw.exilecore.stats.Modifier.more(StatKeys.EVASION, -tw.exilecore.combat.HitEffects.BLIND_LESS, "致盲")), 0));
                }
                defender.getWorld().spawnParticle(Particle.SMOKE, defender.getLocation().add(0, defender.getHeight() * 0.8, 0), 8, 0.25, 0.2, 0.25, 0.02);
                changed = true;
            }
            if (!StatKeys.flag(defenderSheet, StatKeys.FLAG_CORRUPTED_BLOOD_IMMUNE) && allDamage > 0
                    && rollPercent(attack.flat(StatKeys.CORRUPTED_BLOOD_CHANCE, hexTags), random)) {
                entry = entry == null ? track(defender) : entry;
                entry.state.addCorruptedBlood(allDamage * CORRUPTED_BLOOD_PER_STACK, now + ticks(CORRUPTED_BLOOD_SECONDS), now);
                defender.getWorld().spawnParticle(Particle.DUST, defender.getLocation().add(0, defender.getHeight() * 0.5, 0), 6, 0.25, 0.3, 0.25, 0, CORRUPTED);
                changed = true;
            }
        }
        // 混沌減益（2026-10，PoE2 的汲魂彈、疫潮）：每秒傷害 = 這一下傷害的 x%；同一個技能的不疊加
        final double debuffPercent = attack.flat(StatKeys.DEBUFF_CHAOS, skill.contextFor(DamageType.CHAOS));
        // 2026-10-26 照 PoE2（汲魂彈）：持續傷害有自己的每秒基礎傷害，吃法術、混沌的加成（不照這一下的傷害換算）
        final double debuffBase = chaosDebuffBase(attack, skill);
        if ((debuffPercent > 0 || debuffBase > 0) && !StatKeys.flag(defenderSheet, StatKeys.FLAG_CHAOS_IMMUNE)) {
            final double seconds = debuffSeconds(attack, skill); // 2026-10-26 照 PoE2：吃技能持續時間的加成；這次之前固定
            final double dps = allDamage * debuffPercent / 100.0 + debuffBase * resistFactor(defenderSheet, DamageType.CHAOS);
            entry = entry == null ? track(defender) : entry;
            entry.state.addDebuff(debuffKey(skill.id()), new AilmentState.Debuff(dps, now + ticks(seconds),
                    attacker instanceof Player ? attacker.getUniqueId() : null, StatKeys.flag(attack, StatKeys.FLAG_DEBUFF_SPREAD), 0, dps, ticks(seconds)));
            changed = true;
        }
        // 暈眩（PoE2 的重暈）：任何傷害都累積，物理算 100%、其他算幾成；滿了就暈一段時間。
        // 2026-10：玩家打出的物理、近戰各多 50%（相乘）；玩家不會被重暈（PoE2），只會輕暈
        final boolean playerAttacker = attacker instanceof Player;
        final boolean melee = skill.tags() != null && skill.tags().contains(tw.exilecore.stats.Tags.MELEE);
        final double physicalStun = physical * (playerAttacker ? 1.5 : 2.0);
        final double meleeStun = melee ? (playerAttacker ? 1.5 : 1.33) : 1.0;
        final double stunDamage = (physicalStun + (fire + cold + lightning + chaos) * config.stunElementalMultiplier()) * meleeStun
                * Math.max(0, Math.min(1.0, stunScale)); // 2026-10-18：Boss 照遞減後的傷害算暈眩
        if (stunDamage > 0) {
            final Set<String> context = skill.contextFor(DamageType.PHYSICAL);
            // 暈眩門檻 = 暈眩門檻（poise）＋「+x 暈眩門檻」，再乘（1 ＋ 增加暈眩門檻）：元素異常門檻不影響暈眩
            // 2026-10-26 照 PoE2：怪物的基礎改用 poise 等級表 × 稀有度（這次之前用最大生命 × 稀有度倍率）
            final double flatStun = Math.max(0, defenderSheet.flat(StatKeys.STUN_THRESHOLD, Set.of()));
            final double stunIncreased = defenderSheet.increased(StatKeys.STUN_THRESHOLD, Set.of());
            // 2026-10-27 照 PoE2：「更多暈眩門檻」（Steadfast、Unbreakable、山巒之教…）也算；重暈另外有只給重暈的更多；這次之前更多的不吃
            final double stunMore = Math.max(0, defenderSheet.more(StatKeys.STUN_THRESHOLD, Set.of()));
            final double stunThreshold = AilmentConfig.threshold(poise + flatStun, stunIncreased) * stunMore
                    * Math.max(0, defenderSheet.more(tw.exilecore.stats.SupportExtraKeys.HEAVY_STUN_THRESHOLD, Set.of()));
            final double heavyThreshold = stunThreshold * (entry == null ? 1.0 : entry.state.stun().thresholdMultiplier(now));
            // 2026-10-26 照 PoE2：眩暈的敵人受到的暈眩累積更多 50%（stun.buildup_taken）
            // 2026-10-26 照 PoE2 Rhoa Mount：騎在坐騎上被打會累積重暈（被重暈就摔下坐騎）
            final boolean mountedTarget = playerTarget && plugin.skills() != null && plugin.skills().mounted((Player) defender);
            final double rawBuildup = playerTarget && !config.playerHeavyStun() && !mountedTarget ? 0
                    : config.stun().buildupFor(stunDamage, heavyThreshold, attack.apply(StatKeys.STUN_BUILDUP, 1.0, context)
                    * Math.max(0, defenderSheet.apply(AilmentKeys.STUN_BUILDUP_TAKEN, 1.0, Set.of())), playerTarget);
            // 2026-10-27 照 PoE2 Brink：被輔助的技能的暈眩累積到不了 100%（停在差一點，給重壓擊、天罰巨鎚這類收尾）；這次之前沒有
            final boolean brink = StatKeys.flag(attack, tw.exilecore.stats.SupportExtraKeys.FLAG_STUN_CANNOT_REACH);
            final double buildup = brink && entry != null
                    ? Math.min(rawBuildup, Math.max(0, 0.999 - entry.state.stun().buildup())) : brink ? Math.min(rawBuildup, 0.999) : rawBuildup;
            // 2026-10-24（鼓翼風）：這一下之前就蓄勢待暈了（重暈時另外給戰星事件 stun_primed）
            final boolean wasPrimed = defenderMonster != null && entry != null && primed(entry, defenderMonster, now);
            // 蓄勢待暈的怪被「重擊」技能打中：直接重暈（碎骨這類，PoE2）
            final boolean crush = !brink && defenderMonster != null && (entry != null && StatKeys.flag(attack, StatKeys.FLAG_CRUSH_PRIMED)
                    && primed(entry, defenderMonster, now)
                    // 2026-10（傳奇）：重暈滿血的敵人
                    || StatKeys.flag(attack, StatKeys.FLAG_STUN_FULL_LIFE) && fullLifeHits.remove(defender.getUniqueId()));
            // 輕暈：機率 ≈ 這一擊 ÷ 目標最大生命（2026-10-26 照 PoE2；這次之前 ÷ 暈眩門檻），低於 15% 算 0；打斷一下
            // 2026-10-27 照 PoE2 Character.ot：玩家 4 秒內每被暈眩一次，輕暈門檻更多 50%（這次之前沒有）
            final double lightThreshold = AilmentConfig.threshold(lightStunBase(defender, defenderSheet) + flatStun, stunIncreased)
                    * (playerTarget && entry != null ? recentStunThreshold(entry.state.stunsRecently(now)) : 1.0)
                    * stunMore * Math.max(0, defenderSheet.more(tw.exilecore.stats.SupportExtraKeys.LIGHT_STUN_THRESHOLD, Set.of())); // 2026-10-27 照 PoE2 Unbreakable
            final double lightChance = lightThreshold <= 0 ? 0 : Math.min(1.0, stunDamage / lightThreshold);
            if (lightChance * 100 >= config.lightStunMin() && !StatKeys.flag(defenderSheet, StatKeys.FLAG_UNWAVERING) && random.nextDouble() < lightChance
                    && !StatKeys.flag(defenderSheet, tw.exilecore.stats.TreeLastKeys.FLAG_NO_LIGHT_STUN) // 2026-10-27 照 PoE2 天賦樹：不會被輕暈（有條件）
                    && !(result.deflected() && StatKeys.flag(defenderSheet, tw.exilecore.item.unique.UniqueStats.NO_LIGHT_STUN_DEFLECTED)) // 2026-10-27（冷蔑步靴）
                    && !avoided(defenderSheet, StatKeys.AVOID_STUN, random)) {
                entry = entry == null ? track(defender) : entry;
                if (!entry.state.stun().active(now)) {
                    final long light = ticks(config.lightStunDuration(playerTarget) / stunRecovery(playerTarget, defenderSheet));
                    entry.state.lightStun(now + light);
                    if (playerTarget) {
                        entry.state.noteStunned(now); // 2026-10-27：4 秒內被暈眩的次數
                    }
                    refreshAt(defender, entry, light);
                    changed = true;
                    stunEvents(attacker, defender, skill, "light_stun_applied"); // 2026-10-27 照 PoE2：先鋒（輕暈給守護）、能障（被暈眩）
                    if (defender instanceof Player stunned && plugin.skills() != null && plugin.skills().treeLeftovers() != null) {
                        plugin.skills().treeLeftovers().stunnedBy(stunned, attacker); // 2026-10-27 照 PoE2 天賦樹：被暈眩時易爆、致盲對方
                    }
                }
            }
            if (buildup > 0 || crush) {
                entry = entry == null ? track(defender) : entry;
                if (entry.state.stun().add(crush ? Math.max(buildup, 1.0) : buildup, now, config.stun().decayDelayTicks(playerTarget),
                        config.stun().decayPerTick(playerTarget) * (playerTarget // 2026-10-26 照 PoE2：「你的重度暈眩累積消退加快 x%」
                                ? Math.max(0, 1 + defenderSheet.increased(tw.exilecore.passive.ConquestEffects.STUN_DECAY, Set.of())) : 1))) {
                    if (avoided(defenderSheet, StatKeys.AVOID_STUN, random)) {
                        entry.state.stun().reset();
                    } else {
                        final double seconds = AilmentConfig.duration(config.stun().duration(playerTarget),
                                attack.increased(StatKeys.STUN_DURATION, context) + attack.increased(StatKeys.AILMENT_DURATION, context), selfDuration)
                                / stunRecovery(playerTarget, defenderSheet); // 2026-10-26（傳奇）：暈眩回復
                        entry.state.stun().trigger(now, ticks(seconds), 0);
                        entry.state.stun().raiseThreshold(config.stun().after(playerTarget), config.stun().afterTicks(playerTarget), now); // 2026-10-26 照 PoE2
                        if (playerTarget) {
                            entry.state.noteStunned(now); // 2026-10-27：4 秒內被暈眩的次數
                            stunEvents(attacker, defender, skill, null); // 2026-10-27 照 PoE2 Energy Barrier：被重暈也算被暈眩
                        }
                        refreshAt(defender, entry, entry.state.stun().activeUntil() - now);
                        feedback(defender, AilmentType.STUN);
                        if (defender instanceof Player stunned) {
                            plugin.players().get(stunned).markRecent(tw.exilecore.stats.Recently.STUN, now); // 「最近被重暈過」
                            plugin.players().conditions().onStunned(stunned, now); // 2026-10-26：天賦樹（最近被重暈幾次、被重暈時回復生命）
                            if (plugin.skills() != null && plugin.skills().treeLeftovers() != null) {
                                plugin.skills().treeLeftovers().stunnedBy(stunned, attacker); // 2026-10-27 照 PoE2 天賦樹：被暈眩時易爆、致盲對方
                            }
                            if (plugin.skills() != null) {
                                plugin.skills().dismountOnStun(stunned); // 2026-10-26 照 PoE2：騎著被重暈就摔下坐騎
                            }
                        }
                        if (attacker instanceof Player stunner && plugin.skills() != null) {
                            plugin.skills().procEvent(stunner, skill.id(), "stun_applied", null, defender); // 輔助：重暈時（2026-10）
                        }
                        if (attacker instanceof Player stunner && !(defender instanceof Player)) {
                            plugin.players().get(stunner).markRecent(tw.exilecore.stats.TreeConditions.STUNNED_ENEMY, now); // 2026-10-26：「最近重暈過敵人」
                            if (plugin.skills() != null && plugin.skills().treeLeftovers() != null) {
                                plugin.skills().treeLeftovers().heavyStunned(stunner, defender, skill); // 2026-10-27 照 PoE2 天賦樹：雙手近戰重暈、變身重暈恫嚇、禁錮恫嚇
                            }
                            if (plugin.skills() != null && plugin.skills().treeMechanics() != null && !skill.tags().contains(tw.exilecore.stats.Tags.TOTEM_HIT)) {
                                plugin.skills().treeMechanics().onHeavyStun(stunner, defender); // 2026-10-26 照 PoE2 天賦：重暈稀有怪回血、磐星、先祖強化；破甲
                            }
                        }
                        chargeEvent(attacker, "stun_applied", attack, context, defender); // 戰星：重暈時（2026-10）
                        if (wasPrimed) {
                            chargeEvent(attacker, "stun_primed", attack, context, defender); // 2026-10-24：重暈蓄勢待暈的敵人（鼓翼風）
                        }
                    }
                }
                changed = true;
            }
        }
        // 電擊（PoE2）：要有「電擊機率」的來源，閃電傷害才會累積；滿了就麻一段時間
        if (lightning > 0) {
            final Set<String> context = skill.contextFor(DamageType.LIGHTNING);
            // 2026-10-27 照 PoE2 霆爆（Eonyr's Thunder）：閃電傷害本身就累積電擊（等於 100% 的來源）
            final double chance = StatKeys.flag(attack, tw.exilecore.stats.LineageKeys.LIGHTNING_ELECTROCUTES) ? 1.0
                    : Math.max(0, attack.flat(StatKeys.ELECTROCUTE_CHANCE, context))
                    * Math.max(0, 1 + attack.increased(StatKeys.ELECTROCUTE_CHANCE, context))
                    * Math.max(0, attack.more(StatKeys.ELECTROCUTE_CHANCE, context)) / 100.0; // 2026-10-27：更多（風暴箭的 −100%）以前沒算到
            // 2026-10-26 照 PoE2：電擊是累積條，有來源就每一擊都累積，不先擲機率；不到 100% 的「電擊機率」改成累積量乘那個比例
            //（期望值一樣）。這次之前先擲機率，擲中才累積
            if (chance > 0) {
                // 2026-10-26 照 PoE2：閃電 × 1.7 ÷ 暈眩門檻（這次之前 150% × 閃電 ÷ 異常門檻，傳奇怪 50%）
                entry = entry == null ? track(defender) : entry;
                final double buildup = config.electrocute().buildupFor(lightning, freezePoise * entry.state.electrocute().thresholdMultiplier(now),
                        electrocuteBuildupScale(attack.increased(StatKeys.ELECTROCUTE_BUILDUP, context), attack.more(StatKeys.ELECTROCUTE_BUILDUP, context),
                                defenderSheet.increased(tw.exilecore.stats.SpellParityKeys.ELECTROCUTE_BUILDUP_TAKEN, Set.of()))
                                * Math.min(1.0, chance), playerTarget);
                if (buildup > 0) {
                    if (entry.state.electrocute().add(buildup, now, config.electrocute().decayDelayTicks(playerTarget),
                            config.electrocute().decayPerTick(playerTarget))) {
                        if (avoided(defenderSheet, StatKeys.AVOID_ELECTROCUTE, random)) {
                            entry.state.electrocute().reset();
                        } else {
                            final double seconds = AilmentConfig.duration(config.electrocute().duration(playerTarget),
                                    attack.increased(StatKeys.ELECTROCUTE_DURATION, context) + attack.increased(StatKeys.AILMENT_DURATION, context), selfDuration);
                            entry.state.electrocute().trigger(now, ticks(seconds), 0);
                            entry.state.electrocute().raiseThreshold(config.electrocute().after(playerTarget), config.electrocute().afterTicks(playerTarget), now);
                            refreshAt(defender, entry, entry.state.electrocute().activeUntil() - now);
                            feedback(defender, AilmentType.ELECTROCUTE);
                            if (plugin.skills() != null && !playerTarget) {
                                plugin.skills().markAilment(defender, AilmentType.ELECTROCUTE); // 2026-10-28 照 PoE2：引雷印被電擊時觸發
                            }
                            if (attacker instanceof Player zapper && !playerTarget && plugin.skills() != null && plugin.skills().treeLeftovers() != null) {
                                plugin.skills().treeLeftovers().immobilised(zapper, defender); // 2026-10-27 照 PoE2 天賦樹：禁錮（電擊）時恫嚇
                            }
                            chargeEvent(attacker, "electrocute", attack, context, defender); // 2026-10-24：戰意（PoE2：冰凍、電擊、釘住）
                            if (attacker instanceof Player electrocuter && plugin.skills() != null) {
                                plugin.skills().procEvent(electrocuter, skill.id(), "electrocute", null, defender); // 2026-10-27：輔助：電擊時（凝破）
                            }
                        }
                    }
                    changed = true;
                }
            }
        }
        if (changed && entry != null) {
            update(defender, entry, now); // 馬上套用（不用等下一次更新）
        }
    }

    /** 2026-10-26（冰行者之靴）：玩家的暈眩回復速度（暈眩時間除以這個數）；怪物一律 1。 */
    /**
     * 2026-10-27 照 PoE2（poe2db Stun：玩家一般不會被重暈，但舉盾時「格擋太多傷害可能讓你重度暈眩」、招架也會累積；
     * 閃避率同樣是避開這份累積的機率）：玩家舉盾（含招架）格擋下來的擊中累積重暈，
     * 累積 = 減免前的暈眩傷害（怪物的物理 ×2、近戰 ×1.33）× 0.65（HeavyStunDamageScalePlayer）÷ 暈眩門檻（最大生命），
     * 滿了重暈 3 秒（base_heavy_stun_duration_ms 3000），之後 10 秒內門檻更多 100%（stun.player_after）。
     * 這次之前玩家完全不會被重暈。一般被打中照樣不累積（stun.player_heavy: false）。
     */
    public void blockedHit(final Player defender, final LivingEntity attacker, final SkillProfile skill, final StatSheet attack,
                           final StatSheet defenderSheet) {
        if (defender == null || attack == null || defenderSheet == null || !defender.isValid()) {
            return;
        }
        final RandomGenerator random = plugin.items().random();
        // 閃避率同樣避開格擋的暈眩累積（怪物的命中公式）
        final double evade = 1 - tw.exilecore.stats.Formulas.monsterHitChance(attack.get(StatKeys.ACCURACY, skill.tags()), defenderSheet.get(StatKeys.EVASION));
        if (evade > 0 && random.nextDouble() < evade) {
            return;
        }
        final tw.exilecore.stats.DamagePacket raw = tw.exilecore.stats.DamageCalculator.expectedOutgoing(attack, skill);
        final boolean playerAttacker = attacker instanceof Player;
        final boolean melee = skill.tags() != null && skill.tags().contains(tw.exilecore.stats.Tags.MELEE);
        final double others = raw.get(DamageType.FIRE) + raw.get(DamageType.COLD) + raw.get(DamageType.LIGHTNING) + raw.get(DamageType.CHAOS);
        final double stunDamage = (raw.get(DamageType.PHYSICAL) * (playerAttacker ? 1.5 : 2.0) + others * config.stunElementalMultiplier())
                * (melee ? (playerAttacker ? 1.5 : 1.33) : 1.0);
        if (stunDamage <= 0 || avoided(defenderSheet, StatKeys.AVOID_STUN, random) || StatKeys.flag(defenderSheet, StatKeys.FLAG_UNWAVERING)) {
            return;
        }
        final long now = now();
        final Tracked entry = track(defender);
        final double threshold = AilmentConfig.threshold(lightStunBase(defender, defenderSheet)
                + Math.max(0, defenderSheet.flat(StatKeys.STUN_THRESHOLD, Set.of())), defenderSheet.increased(StatKeys.STUN_THRESHOLD, Set.of()))
                * entry.state.stun().thresholdMultiplier(now)
                * Math.max(0, defenderSheet.more(StatKeys.STUN_THRESHOLD, Set.of())) // 2026-10-27 照 PoE2：更多暈眩門檻、只給重暈的更多也算
                * Math.max(0, defenderSheet.more(tw.exilecore.stats.SupportExtraKeys.HEAVY_STUN_THRESHOLD, Set.of()));
        final double buildup = config.stun().buildupFor(stunDamage, threshold,
                Math.max(0, defenderSheet.apply(AilmentKeys.STUN_BUILDUP_TAKEN, 1.0, Set.of())), true);
        if (buildup <= 0) {
            return;
        }
        if (entry.state.stun().add(buildup, now, config.stun().decayDelayTicks(true), config.stun().decayPerTick(true)
                * Math.max(0, 1 + defenderSheet.increased(tw.exilecore.passive.ConquestEffects.STUN_DECAY, Set.of())))) {
            final double seconds = config.stun().duration(true) / stunRecovery(true, defenderSheet);
            entry.state.stun().trigger(now, ticks(seconds), 0);
            entry.state.stun().raiseThreshold(config.stun().after(true), config.stun().afterTicks(true), now);
            entry.state.noteStunned(now);
            refreshAt(defender, entry, entry.state.stun().activeUntil() - now);
            feedback(defender, AilmentType.STUN);
            plugin.players().get(defender).markRecent(tw.exilecore.stats.Recently.STUN, now);
            plugin.players().conditions().onStunned(defender, now);
            if (plugin.skills() != null) {
                plugin.skills().dismountOnStun(defender);
            }
        }
        refreshAt(defender, entry, 1);
    }

    /** 2026-10-27 照 PoE2 Character.ot（light_stun_threshold_+%_final_per_number_of_times_stunned_in_past_duration 50）：玩家輕暈門檻的倍率。 */
    public static final double LIGHT_STUN_THRESHOLD_PER_RECENT_STUN = 0.5;

    static double recentStunThreshold(final int stunsRecently) {
        return 1 + LIGHT_STUN_THRESHOLD_PER_RECENT_STUN * Math.max(0, stunsRecently);
    }

    static double stunRecovery(final boolean playerTarget, final StatSheet defenderSheet) {
        return playerTarget ? Math.max(0.1, 1 + defenderSheet.increased(tw.exilecore.item.unique.UniqueStats.STUN_RECOVERY, Set.of())) : 1.0;
    }

    /** 減益的代號：技能代號去掉後綴（「.triggered」之類），同一個技能的不疊加。 */
    static String debuffKey(final String skillId) {
        final int dot = skillId == null ? -1 : skillId.indexOf('.');
        return skillId == null ? "?" : dot < 0 ? skillId : skillId.substring(0, dot);
    }

    /** 傳染的半徑（格）。 */
    static final double SPREAD_RADIUS = 4.0;
    /** 每傳一手更多幾倍、最多幾手（PoE2：每手更多 100%，最多更多 300%）。 */
    static final int SPREAD_MAX = 3;

    /**
     * 疫潮（2026-10，PoE2）：掛著會傳染的減益的敵人死掉時，傳給附近的敵人（怪物），每傳一手更多 100% 傷害（最多更多 300%）、
     * 時間重新算。被傳到的敵人身上已經有更強的就不換。
     */
    public void spreadDebuffs(final LivingEntity dead) {
        final Tracked entry = tracked.get(dead.getUniqueId());
        if (entry == null || dead instanceof Player) {
            return;
        }
        final long now = now();
        for (final java.util.Map.Entry<String, AilmentState.Debuff> one : entry.state.debuffs(now).entrySet()) {
            final AilmentState.Debuff debuff = one.getValue();
            if (!debuff.spread()) {
                continue;
            }
            final int spreads = Math.min(SPREAD_MAX, debuff.spreads() + 1);
            // 2026-10-26 照 PoE2：傳出去的照掛上時的完整持續時間（含技能持續時間的加成）重新算；這次之前 min(5 秒, 剩下 + 2 秒)
            final long length = debuff.length() > 0 ? debuff.length() : Math.min(ticks(5), Math.max(ticks(1), debuff.endTick() - now) + ticks(2));
            final AilmentState.Debuff passed = new AilmentState.Debuff(debuff.base() * (1 + spreads), now + length,
                    debuff.source(), true, spreads, debuff.base(), length);
            for (final org.bukkit.entity.Entity near : dead.getNearbyEntities(SPREAD_RADIUS, SPREAD_RADIUS, SPREAD_RADIUS)) {
                if (!(near instanceof LivingEntity living) || near instanceof Player || !living.isValid() || living.isDead()
                        || plugin.monsters() == null || plugin.monsters().data(living).isEmpty()) {
                    continue;
                }
                Tracked other = tracked.get(living.getUniqueId());
                other = other == null ? track(living) : other;
                other.state.addDebuff(one.getKey(), passed);
            }
            dead.getWorld().spawnParticle(org.bukkit.Particle.SQUID_INK, dead.getLocation().add(0, 1, 0), 30, SPREAD_RADIUS / 2, 0.6,
                    SPREAD_RADIUS / 2, 0.02);
        }
    }

    /**
     * 2026-10-24：固定的物理傷害（不吃任何加成；血宣爆炸裡「失血的 15%」那部分）：走持續傷害那一套扣血、記傷害、算擊殺，
     * 但不算進目標自己的失血。
     */
    public void dealFlatPhysical(final LivingEntity target, final double amount, final UUID source) {
        if (amount <= 0 || target instanceof Player || !target.isValid() || target.isDead()) {
            return;
        }
        Tracked entry = tracked.get(target.getUniqueId());
        entry = entry == null ? track(target) : entry;
        final long now = now();
        final double before = entry.bloodLoss;
        damageOverTime(target, entry, amount * 20.0 / TICK_INTERVAL / (1 + entry.state.shock(now)), source, AilmentType.BLEED, now);
        entry.bloodLoss = before;
    }

    /**
     * 2026-10-28 照 PoE2（poe2db Tornado Shot）：技能自己的物理持續傷害（龍捲風裡的敵人；dps 已含攻擊方的加成），
     * 這一次結算 seconds 秒份，走持續傷害那一套（不暴擊、不算擊中、吃感電；不算進目標的失血），並阻礙 hinderTicks。
     * 這次之前龍捲風是每 0.5 秒擊中一次。
     */
    public void skillPhysicalDot(final LivingEntity target, final double dps, final double seconds, final UUID source, final long hinderTicks) {
        if (target == null || !target.isValid() || target.isDead()) {
            return;
        }
        if (target instanceof Player player) {
            if (hinderTicks > 0) {
                hinder(player, hinderTicks);
            }
        } else if (hinderTicks > 0 && plugin.monsters() != null && plugin.monsters().data(target).isPresent()) {
            final StatSheet sheet = plugin.monsters().sheet(target, plugin.monsters().data(target).get());
            if (!StatKeys.flag(sheet, StatKeys.FLAG_UNSLOWABLE) && !StatKeys.flag(sheet, StatKeys.FLAG_HINDER_IMMUNE)) {
                final Tracked hindered = track(target);
                hindered.state.hinder(now() + hinderTicks);
                update(target, hindered, now());
            }
        }
        if (dps <= 0 || seconds <= 0 || !target.isValid() || target.isDead()) {
            return;
        }
        Tracked entry = tracked.get(target.getUniqueId());
        entry = entry == null ? track(target) : entry;
        final double before = entry.bloodLoss;
        skillDot = true;
        try {
            damageOverTime(target, entry, dps * seconds * 20.0 / TICK_INTERVAL, source, AilmentType.BLEED, DamageType.PHYSICAL, now());
        } finally {
            skillDot = false;
        }
        entry.bloodLoss = before;
    }

    /** 2026-10-28：正在結算技能自己的物理持續傷害（不是流血：不算失血、不累積血獵印記的重暈）。 */
    private boolean skillDot;

    /**
     * 2026-10-26 照 PoE2：技能自己的混沌持續傷害（每秒，{@link tw.exilecore.stats.SkillMechanicKeys#DEBUFF_CHAOS_BASE}），
     * 吃這個技能（含法術）的混沌傷害增加、更多；沒有回傳 0。
     */
    static double chaosDebuffBase(final StatSheet attack, final tw.exilecore.stats.SkillProfile skill) {
        final java.util.Set<String> context = skill.contextFor(DamageType.CHAOS);
        final double base = attack.flat(tw.exilecore.stats.SkillMechanicKeys.DEBUFF_CHAOS_BASE, context);
        return base <= 0 ? 0 : Math.max(0, attack.apply(StatKeys.DAMAGE, base, context));
    }

    /**
     * 2026-10-26 照 PoE2：汲魂彈（4 秒）、疫潮（5 秒）的混沌減益有 Duration 標籤，持續時間乘技能持續時間的增加／更多
     * （{@link StatKeys#SKILL_DURATION}，照技能的標籤：持久、急蝕、天賦的「技能效果持續時間」）。這次之前固定 debuff.duration 秒。
     */
    public static double debuffSeconds(final StatSheet attack, final tw.exilecore.stats.SkillProfile skill) {
        final double base = attack.flat(StatKeys.DEBUFF_DURATION, Set.of());
        return (base > 0 ? base : 4) * Math.max(0.1, attack.apply(StatKeys.SKILL_DURATION, 1.0, skill.tags()));
    }

    /** 2026-10-28 照 PoE2 Blood Hunt：用掉這隻怪累積的失血（回傳用掉多少，之後從 0 再累積）。 */
    public double consumeBloodLoss(final LivingEntity entity) {
        final Tracked entry = tracked.get(entity.getUniqueId());
        if (entry == null) {
            return 0;
        }
        final double consumed = entry.bloodLoss;
        entry.bloodLoss = 0;
        return consumed;
    }

    /** 2026-10-24：這隻怪累積被流血打掉多少（血宣：爆炸多打失血的 15%）。 */
    public double bloodLoss(final LivingEntity entity) {
        final Tracked entry = tracked.get(entity.getUniqueId());
        return entry == null ? 0 : entry.bloodLoss;
    }

    /**
     * 2026-10-24（疫使照 PoE2）：把這隻怪身上「最痛的那個毒」傳給半徑內的敵人（照原本的每秒傷害、剩餘時間，來源記給 source），
     * 每個被傳到的有 hinderChance% 機率緩行。回傳傳給了幾隻。
     */
    public int spreadStrongestPoison(final LivingEntity dead, final double radius, final UUID source, final double hinderChance, final double hinderSeconds) {
        final Tracked entry = tracked.get(dead.getUniqueId());
        if (entry == null || dead instanceof Player) {
            return 0;
        }
        final long now = now();
        AilmentState.Ignite strongest = null;
        for (final AilmentState.Ignite poison : entry.state.poisons(now)) {
            if (strongest == null || poison.dps() > strongest.dps()) {
                strongest = poison;
            }
        }
        if (strongest == null) {
            return 0;
        }
        final long left = Math.max(ticks(1), strongest.endTick() - now);
        int spread = 0;
        for (final org.bukkit.entity.Entity near : dead.getNearbyEntities(radius, radius, radius)) {
            if (!(near instanceof LivingEntity living) || near instanceof Player || living == dead || !living.isValid() || living.isDead()
                    || plugin.monsters() == null || plugin.monsters().data(living).isEmpty()
                    || living.getLocation().distanceSquared(dead.getLocation()) > radius * radius) {
                continue;
            }
            Tracked other = tracked.get(living.getUniqueId());
            other = other == null ? track(living) : other;
            other.state.addPoison(strongest.dps(), now + left, source == null ? strongest.source() : source,
                    strongest.limit()); // 2026-10-27：傳過去的帶著原本那層毒的層數上限；這次之前用 20 層，把來源整個的上限拉高、1 層上限的毒全部生效
            if (hinderChance > 0 && plugin.random().nextDouble() * 100 < hinderChance) {
                other.state.hinder(now + ticks(hinderSeconds));
            }
            spread++;
        }
        return spread;
    }

    /** 敵人身上的混沌減益每秒多少傷害（說明、測試用）。 */
    public double debuffDps(final LivingEntity entity) {
        final Tracked entry = tracked.get(entity.getUniqueId());
        return entry == null ? 0 : entry.state.debuffDps(now());
    }

    /** 創造、旁觀模式與還沒選角色的玩家不會中異常。 */
    private boolean canBeAilmented(final Player player) {
        if (player.getGameMode() == GameMode.CREATIVE || player.getGameMode() == GameMode.SPECTATOR) {
            return false;
        }
        final PlayerState state = plugin.players().get(player);
        return state.hasCharacter() && !state.isDead();
    }

    /**
     * 門檻的基礎：怪物 = 最大生命 × 稀有度倍率；玩家 = 最大生命 ＋ 最大護盾。
     * 元素異常再乘「增加元素異常門檻」，暈眩再乘「增加暈眩門檻」（兩個互不影響）。
     */
    /**
     * 面板估計（2026-10）：把點燃、流血、中毒的期望每秒傷害與感電的增傷加進技能的每秒傷害。
     * 對象當成同等級的一般怪物（異常門檻 = 一般怪物的生命 × 門檻倍率）、站著不動；一秒打 hitsPerSecond 下，每下的傷害 = expected × critFactor。
     * 點燃、流血只算最強的一個：覆蓋率 1 − e^(−機率 × 每秒次數 × 持續秒數)；中毒照平均疊幾層算（最多疊加上限）。
     *
     * @param hitDps 擊中的每秒傷害（不含異常）
     * @return 擊中＋持續傷害，再乘上感電的期望增傷
     */
    /**
     * 面板估計主要寶石（元素異常時施放）的能量用（2026-10-17）：打同等級一般怪物，一下平均造成幾次 {點燃, 感電, 冰凍}。
     * 點燃、感電照機率；冰凍照一下的累積量（滿 1 凍一次）。不能造成元素異常的回傳全 0。
     */
    public double[] ailmentsPerHit(final StatSheet attackerSheet, final tw.exilecore.stats.SkillProfile skill,
                                   final tw.exilecore.stats.DamagePacket expected, final double critFactor, final int level) {
        final double[] out = new double[3];
        if (expected == null || expected.isEmpty() || plugin.monsters() == null) {
            return out;
        }
        // 2026-10-26 照 PoE2：同等級普通怪的異常門檻、暈眩門檻（等級表）
        final double threshold = plugin.monsters().config().ailmentThresholdAt(Math.max(1, level)) * config.monsterThreshold(MonsterRarity.NORMAL);
        final double poise = plugin.monsters().config().poiseAt(Math.max(1, level)) * plugin.monsters().config().rarityPoise(MonsterRarity.NORMAL);
        final StatSheet attack = skill.effective(attackerSheet);
        if (threshold <= 0 || StatKeys.flag(attack, StatKeys.FLAG_NO_AILMENTS)) {
            return out;
        }
        final double fire = expected.get(DamageType.FIRE) * critFactor;
        final double cold = expected.get(DamageType.COLD) * critFactor;
        final double lightning = expected.get(DamageType.LIGHTNING) * critFactor;
        final double all = expected.total() * critFactor;
        final double igniteBase = StatKeys.flag(attack, StatKeys.FLAG_ALL_IGNITE) ? all : fire;
        if (igniteBase > 0) {
            final Set<String> context = skill.contextFor(DamageType.FIRE);
            out[0] = Math.min(1, config.igniteChance(igniteBase, threshold, attack.flat(StatKeys.IGNITE_CHANCE, context),
                    attack.increased(StatKeys.IGNITE_CHANCE, context)) * attack.more(StatKeys.IGNITE_CHANCE, context));
        }
        final double shockBase = StatKeys.flag(attack, StatKeys.FLAG_ALL_SHOCK) ? all : lightning;
        if (shockBase > 0) {
            final Set<String> context = skill.contextFor(DamageType.LIGHTNING);
            out[1] = Math.min(1, config.shockChance(shockBase, threshold, attack.flat(StatKeys.SHOCK_CHANCE, context),
                    attack.increased(StatKeys.SHOCK_CHANCE, context)) * attack.more(StatKeys.SHOCK_CHANCE, context));
        }
        if (cold > 0) {
            final Set<String> context = skill.contextFor(DamageType.COLD);
            out[2] = Math.min(1, config.freezeBuildup(cold, poise, attack.apply(StatKeys.FREEZE_BUILDUP, 1.0, context), false));
        }
        return out;
    }

    public double estimateDps(final StatSheet attackerSheet, final tw.exilecore.stats.SkillProfile skill, final tw.exilecore.stats.DamagePacket expected,
                              final double critFactor, final double hitsPerSecond, final int level, final double hitDps) {
        if (hitsPerSecond <= 0 || expected == null || expected.isEmpty() || plugin.monsters() == null) {
            return hitDps;
        }
        final double threshold = plugin.monsters().config().ailmentThresholdAt(Math.max(1, level)) * config.monsterThreshold(MonsterRarity.NORMAL);
        if (threshold <= 0) {
            return hitDps;
        }
        final StatSheet attack = skill.effective(attackerSheet);
        final boolean noElemental = StatKeys.flag(attack, StatKeys.FLAG_NO_AILMENTS);
        final double fire = expected.get(DamageType.FIRE) * critFactor;
        final double cold = expected.get(DamageType.COLD) * critFactor;
        final double lightning = expected.get(DamageType.LIGHTNING) * critFactor;
        final double physical = expected.get(DamageType.PHYSICAL) * critFactor;
        final double chaos = expected.get(DamageType.CHAOS) * critFactor;
        final double all = fire + cold + lightning + physical + chaos;
        double dot = 0;
        // 點燃
        final double igniteBase = noElemental ? 0 : StatKeys.flag(attack, StatKeys.FLAG_ALL_IGNITE) ? all : fire;
        if (igniteBase > 0) {
            final Set<String> context = skill.contextFor(DamageType.FIRE);
            // 2026-10-26 照 PoE2：易燃會疊（8 秒內每一下都加上去），一直打的平均機率 ≈ 每下的易燃 × 8 秒內的次數
            final double perHit = config.igniteChance(igniteBase, threshold, attack.flat(StatKeys.IGNITE_CHANCE, context),
                    attack.increased(StatKeys.IGNITE_CHANCE, context)) * attack.more(StatKeys.IGNITE_CHANCE, context);
            final double chance = Math.min(1.0, perHit * Math.max(1.0, hitsPerSecond * config.flammabilityDuration(false) / 2.0));
            final double seconds = AilmentConfig.duration(config.igniteDuration(),
                    attack.increased(StatKeys.IGNITE_DURATION, context) + attack.increased(StatKeys.AILMENT_DURATION, context), 0)
                    * attack.more(StatKeys.IGNITE_DURATION, context); // 2026-10-27：估計也乘更多的持續時間
            dot += config.igniteDps(igniteBase, attack.increased(StatKeys.IGNITE_MAGNITUDE, context), attack.more(StatKeys.IGNITE_MAGNITUDE, context))
                    * uptime(chance, hitsPerSecond, seconds);
        }
        // 流血（攻擊、法術都可以，2026-10-27 照 PoE2）
        if (physical > 0 && canBleed(skill)) {
            final Set<String> context = skill.contextFor(DamageType.PHYSICAL);
            final double chance = config.bleedChance(physical, threshold, attack.flat(StatKeys.BLEED_CHANCE, context),
                    attack.increased(StatKeys.BLEED_CHANCE, context)) * attack.more(StatKeys.BLEED_CHANCE, context);
            final boolean crimson = StatKeys.flag(attack, StatKeys.FLAG_CRIMSON_ASSAULT);
            final double seconds = AilmentConfig.duration(crimson ? 1.0 : config.bleedDuration(),
                    attack.increased(StatKeys.BLEED_DURATION, context) + attack.increased(StatKeys.AILMENT_DURATION, context), 0);
            dot += config.bleedDps(physical, attack.increased(StatKeys.BLEED_MAGNITUDE, context), attack.more(StatKeys.BLEED_MAGNITUDE, context))
                    * (crimson ? 1.5 * config.bleedMovingMultiplier() : 1.0) * uptime(chance, hitsPerSecond, seconds);
        }
        // 中毒（會疊）
        if (physical + chaos > 0) {
            final Set<String> context = skill.contextFor(DamageType.CHAOS);
            final double chance = config.poisonChance(physical + chaos, threshold, attack.flat(StatKeys.POISON_CHANCE, context),
                    attack.increased(StatKeys.POISON_CHANCE, context)) * attack.more(StatKeys.POISON_CHANCE, context);
            final double seconds = AilmentConfig.duration(config.poisonDuration(),
                    attack.increased(StatKeys.POISON_DURATION, context) + attack.increased(StatKeys.AILMENT_DURATION, context), 0)
                    * attack.more(StatKeys.POISON_DURATION, context);
            final int maxStacks = poisonStacks(attack, context);
            final double stacks = Math.min(maxStacks, Math.max(0, Math.min(1, chance)) * hitsPerSecond * seconds);
            dot += config.poisonDps(physical + chaos, attack.increased(StatKeys.POISON_MAGNITUDE, context), attack.more(StatKeys.POISON_MAGNITUDE, context))
                    * stacks;
        }
        // 混沌減益（2026-10，汲魂彈、疫潮）：每一下都會掛上（同一個技能不疊加），只要一直打就一直在
        final double debuffPercent = attack.flat(StatKeys.DEBUFF_CHAOS, skill.contextFor(DamageType.CHAOS));
        final double debuffBase = chaosDebuffBase(attack, skill); // 2026-10-26 照 PoE2（汲魂彈自己的每秒基礎傷害）
        if (debuffPercent > 0 || debuffBase > 0) {
            final double seconds = debuffSeconds(attack, skill); // 2026-10-26：吃技能持續時間的加成
            dot += (all * debuffPercent / 100.0 + debuffBase) * uptime(1.0, hitsPerSecond, seconds);
        }
        // 感電：受到的傷害增加（擊中與持續傷害都吃）
        double shocked = 1.0;
        final double shockBase = noElemental ? 0 : StatKeys.flag(attack, StatKeys.FLAG_ALL_SHOCK) ? all : lightning;
        if (shockBase > 0) {
            final Set<String> context = skill.contextFor(DamageType.LIGHTNING);
            final double chance = config.shockChance(shockBase, threshold, attack.flat(StatKeys.SHOCK_CHANCE, context),
                    attack.increased(StatKeys.SHOCK_CHANCE, context)) * attack.more(StatKeys.SHOCK_CHANCE, context);
            final double seconds = AilmentConfig.duration(config.shockDuration(false),
                    attack.increased(StatKeys.SHOCK_DURATION, context) + attack.increased(StatKeys.AILMENT_DURATION, context), 0);
            shocked += config.shockEffect(attack.increased(StatKeys.SHOCK_EFFECT, context)) * uptime(chance, hitsPerSecond, seconds);
        }
        return (hitDps + dot) * shocked;
    }

    /** 只算最強一個的異常（點燃、流血、感電）在身上的時間比例。 */
    private static double uptime(final double chance, final double hitsPerSecond, final double seconds) {
        final double p = Math.max(0, Math.min(1, chance));
        if (p <= 0 || hitsPerSecond <= 0 || seconds <= 0) {
            return 0;
        }
        return 1 - Math.exp(-p * hitsPerSecond * seconds);
    }

    /**
     * 元素異常門檻的基礎（2026-10-26 照 PoE2 0.5）：怪物 = 等級表（不看稀有度、生命）；玩家 = 最大生命 × 50%（不含能量護盾）。
     * 這次之前：怪物 = 最大生命 × 稀有度倍率（傳奇 0.5）；玩家 = 生命 ＋ 護盾。
     */
    private double baseThreshold(final LivingEntity defender, final MonsterData monster, final StatSheet sheet) {
        if (defender instanceof Player player) {
            return plugin.players().get(player).maxLife() * config.playerThresholdLife();
        }
        if (monster == null) {
            return 0;
        }
        return plugin.monsters().config().ailmentThresholdAt(Math.max(1, monster.level())) * config.monsterThreshold(monster.rarity());
    }

    /**
     * 2026-10-26 照 PoE2 0.5：暈眩門檻（poise；重暈、凍結、電擊、釘地）的基礎：怪物 = poise 等級表 × 稀有度（1.26／2.25／6）；
     * 玩家 = 異常門檻（生命的 50%）。
     */
    private double basePoise(final LivingEntity defender, final MonsterData monster, final StatSheet sheet) {
        if (defender instanceof Player player) {
            // 2026-10-26 照 PoE2 天賦樹：「暈眩門檻有 x% 改照能量護盾算」
            final double share = sheet == null ? 0 : Math.max(0, Math.min(100, sheet.flat(tw.exilecore.stats.KeystoneKeys.STUN_ES_SHARE, Set.of()))) / 100.0;
            if (share > 0) {
                final PlayerState state = plugin.players().get(player);
                return (state.maxLife() * (1 - share) + state.maxEnergyShield() * share) * config.playerThresholdLife();
            }
            return baseThreshold(defender, monster, sheet);
        }
        if (monster == null) {
            return 0;
        }
        return plugin.monsters().config().poiseAt(Math.max(1, monster.level())) * plugin.monsters().config().rarityPoise(monster.rarity())
                * (monster.boss() ? config.bossPoise(pinnacleBoss(defender)) : 1); // 2026-10-26 照 PoE2：Boss 的暈眩門檻（地圖 ×3.13、巔峰 ×9.38）
    }

    /** 2026-10-26：這隻 Boss 是不是在巔峰、極境（只有 Boss 房的實例）。 */
    private boolean pinnacleBoss(final LivingEntity defender) {
        final tw.exilecore.map.MapInstance instance = plugin.maps() == null ? null : plugin.maps().instanceOf(defender);
        return instance != null && instance.bossOnly();
    }

    /** 2026-10-26 照 PoE2：輕暈看目標的最大生命（玩家不含能量護盾）。 */
    private double lightStunBase(final LivingEntity defender, final StatSheet sheet) {
        if (defender instanceof Player player) {
            return plugin.players().get(player).maxLife();
        }
        return sheet.get(StatKeys.LIFE_MAX);
    }

    /**
     * 2026-10-26：點燃、中毒每秒的傷害照減免前的那一擊算，再吃目標這種傷害的抗性（照抗性上限）；物理（流血）不吃護甲。
     */
    static double resistFactor(final StatSheet sheet, final DamageType type) {
        if (sheet == null || type == DamageType.PHYSICAL) {
            return 1.0;
        }
        final double cap = tw.exilecore.stats.DamageCalculator.resistCap(sheet, type);
        final double resist = Math.min(sheet.get(type.mitigationStat()), cap);
        return Math.max(0, 1 - tw.exilecore.stats.Formulas.resistReduction(resist, cap));
    }

    /** 2026-10-28 照 PoE2 Blackflame（黯炎）：攻擊方的點燃造成混沌傷害而非火焰傷害。 */
    public static boolean chaosIgnite(final StatSheet attack) {
        return attack != null && StatKeys.flag(attack, tw.exilecore.item.unique.UniqueStats.IGNITE_AS_CHAOS);
    }

    /** 2026-10-28：點燃每秒傷害吃目標哪一種抗性（混沌點燃吃混沌抗性；混沌免疫的目標不受混沌點燃的傷害）。 */
    static double igniteResist(final StatSheet sheet, final DamageType type) {
        if (type == DamageType.CHAOS && sheet != null && StatKeys.flag(sheet, StatKeys.FLAG_CHAOS_IMMUNE)) {
            return 0;
        }
        return resistFactor(sheet, type);
    }

    /**
     * 2026-10-26 照 PoE2：同一個人的中毒同時幾層生效：預設 1（ailments.yml 的 poison.max_stacks）＋「中毒層數 +x」（poison.stacks），
     * 開拓者（flag.double_poison）加倍。
     */
    int poisonStacks(final StatSheet attack, final Set<String> context) {
        final int base = config.poisonMaxStacks() + (int) Math.round(Math.max(0, attack.flat(AilmentKeys.POISON_STACKS, context)));
        return Math.max(1, Math.min(AilmentState.MAX_POISONS, base * (StatKeys.flag(attack, StatKeys.FLAG_DOUBLE_POISON) ? 2 : 1)));
    }

    /**
     * 2026-10-26 照 PoE2 Pin：投射物攻擊的物理傷害累積釘地（× 4.2 ÷ 暈眩門檻，觸發後門檻暫時提高 500%）。回傳這一下有沒有釘住
     * （呼叫端照 {@link AilmentConfig#pin()} 的秒數讓牠不能移動）。
     */
    public boolean pinBuildup(final LivingEntity defender, final MonsterData data, final StatSheet sheet, final double physical, final double multiplier) {
        if (physical <= 0 || data == null || defender instanceof Player) {
            return false;
        }
        final long now = now();
        final Tracked entry = track(defender);
        final AilmentConfig.MeterSettings settings = config.pin();
        final double threshold = basePoise(defender, data, sheet) * entry.state.pin().thresholdMultiplier(now);
        final double buildup = settings.buildupFor(physical, threshold, multiplier, false);
        if (!entry.state.pin().add(buildup, now, settings.decayDelayTicks(false), settings.decayPerTick(false))) {
            return false;
        }
        entry.state.pin().trigger(now, ticks(settings.duration(false)), 0);
        entry.state.pin().raiseThreshold(settings.after(false), settings.afterTicks(false), now);
        // 2026-10-26 照 PoE2 Pinned：被釘住的時候輕暈一下（這次之前沒有）
        if (!StatKeys.flag(sheet, StatKeys.FLAG_UNWAVERING)) {
            entry.state.lightStun(now + ticks(config.lightStunDuration(false) / stunRecovery(false, sheet)));
            update(defender, entry, now);
        }
        return true;
    }

    /** 2026-10-26（昇華・位置正好）：被釘身的敵人無法進行動作：釘住期間一直輕暈。 */
    public void holdPinned(final LivingEntity target) {
        final Tracked entry = tracked.get(target.getUniqueId());
        if (entry != null) {
            entry.state.lightStun(now() + ticks(config.pin().duration(false)));
        }
    }

    /** 2026-10-26（昇華・時間凍結）：讓牠停住 ticks 個 tick（不能動也不能攻擊）。 */
    public void holdStill(final LivingEntity target, final long ticks) {
        if (target instanceof Player || ticks <= 0) {
            return;
        }
        track(target).state.lightStun(now() + ticks);
    }

    /** 2026-10-26：這隻現在有沒有被釘身。 */
    public boolean pinned(final LivingEntity target) {
        final Tracked entry = tracked.get(target.getUniqueId());
        return entry != null && entry.state.pin().active(now());
    }

    /** 2026-10-26：異常（冰緩、癱瘓、阻礙、碎地）現在讓牠慢了多少（戰鬥模組的詛咒減速照這個算 25% 速度的下限）。 */
    public double appliedSlow(final LivingEntity entity) {
        final Tracked entry = tracked.get(entity.getUniqueId());
        return entry == null ? 0 : entry.appliedSlow;
    }

    /** 2026-10-26 照 PoE2：緩速最多讓怪物降到原本速度的 25%（凍結、暈眩這種定住的不算）。 */
    public static final double MONSTER_MIN_SPEED = 0.25;

    /** 2026-10-26 照 PoE2 天賦樹：「身上已經有一種傷害型異常時，不會再中傷害型異常」（點燃、流血、中毒）。 */
    private boolean oneDamagingAilment(final LivingEntity defender, final StatSheet defenderSheet) {
        return defender instanceof Player && defenderSheet != null && StatKeys.flag(defenderSheet, tw.exilecore.stats.KeystoneKeys.ONE_DAMAGING_AILMENT)
                && (has(defender, AilmentType.IGNITE) || has(defender, AilmentType.BLEED) || has(defender, AilmentType.POISON));
    }

    /** 2026-10-26 心靈永止：玩家 → {這一段持續傷害開始的 tick, 上次受到持續傷害的 tick}。 */
    private final Map<UUID, long[]> dotStreak = new HashMap<>();

    /** 心靈永止的倍率（沒有這個基石是 1）：中斷超過 1 秒再受到持續傷害算「開始承受」。 */
    private double heartstopper(final Player player, final StatSheet sheet) {
        if (!StatKeys.flag(sheet, tw.exilecore.stats.KeystoneKeys.FLAG_HEARTSTOPPER)) {
            dotStreak.remove(player.getUniqueId());
            return 1.0;
        }
        final long now = now();
        final long[] streak = dotStreak.computeIfAbsent(player.getUniqueId(), uuid -> new long[] {now, now});
        if (now - streak[1] > tw.exilecore.stats.KeystoneKeys.HEARTSTOPPER_WINDOW) {
            streak[0] = now;
        }
        streak[1] = now;
        return tw.exilecore.stats.KeystoneKeys.heartstopper(now - streak[0]);
    }

    /** 擲避免：最多 {@link StatKeys#AVOID_CAP}%（堆再多也一樣；要免疫用開關）。 */
    private static boolean avoided(final StatSheet defender, final String stat, final RandomGenerator random) {
        if (stat.startsWith("avoid.") && StatKeys.flag(defender, "flag.immune." + stat.substring("avoid.".length()))) {
            return true; // 2026-10-26：免疫（PoE2 護符的 Grants Immunity to X：flag.immune.ignite／shock／bleed／poison／stun…，不受 75% 上限）
        }
        if ((stat.equals(StatKeys.AVOID_IGNITE) || stat.equals(StatKeys.AVOID_CHILL) || stat.equals(StatKeys.AVOID_FREEZE)
                || stat.equals(StatKeys.AVOID_SHOCK)) && StatKeys.flag(defender, StatKeys.FLAG_ELEMENTAL_AILMENT_IMMUNE)) {
            return true; // 免疫元素異常（2026-10，三元守護）
        }
        final double chance = AilmentConfig.avoidChance(defender.get(stat));
        return chance > 0 && random.nextDouble() < chance;
    }

    private Tracked track(final LivingEntity entity) {
        return tracked.computeIfAbsent(entity.getUniqueId(), uuid -> new Tracked(entity instanceof Player));
    }

    /** 中異常的瞬間：音效與一點粒子。 */
    /** 2026-10-28：點燃的回饋；黯炎的混沌點燃用暗紫色的靈魂火焰。 */
    private static void igniteFeedback(final LivingEntity entity, final boolean chaos) {
        if (!chaos) {
            feedback(entity, AilmentType.IGNITE);
            return;
        }
        final Location at = entity.getLocation().add(0, entity.getHeight() * 0.5, 0);
        entity.getWorld().playSound(at, Sound.ITEM_FIRECHARGE_USE, 0.5f, 0.8f);
        entity.getWorld().spawnParticle(Particle.SOUL_FIRE_FLAME, at, 8, 0.3, 0.4, 0.3, 0.02);
    }

    private static void feedback(final LivingEntity entity, final AilmentType type) {
        final Location at = entity.getLocation().add(0, entity.getHeight() * 0.5, 0);
        switch (type) {
            case IGNITE -> {
                entity.getWorld().playSound(at, Sound.ITEM_FIRECHARGE_USE, 0.5f, 1.3f);
                entity.getWorld().spawnParticle(Particle.FLAME, at, 8, 0.3, 0.4, 0.3, 0.02);
            }
            case FREEZE -> {
                entity.getWorld().playSound(at, Sound.BLOCK_GLASS_BREAK, 0.7f, 1.6f);
                entity.getWorld().spawnParticle(Particle.SNOWFLAKE, at, 20, 0.4, 0.6, 0.4, 0.02);
            }
            case SHOCK -> {
                entity.getWorld().playSound(at, Sound.ENTITY_FIREWORK_ROCKET_TWINKLE, 0.6f, 1.6f);
                entity.getWorld().spawnParticle(Particle.ELECTRIC_SPARK, at, 12, 0.35, 0.6, 0.35, 0.08);
            }
            case BLEED -> {
                entity.getWorld().playSound(at, Sound.ENTITY_PLAYER_HURT_SWEET_BERRY_BUSH, 0.6f, 0.7f);
                entity.getWorld().spawnParticle(Particle.DUST, at, 14, 0.3, 0.5, 0.3, 0, BLOOD);
            }
            case POISON -> {
                entity.getWorld().playSound(at, Sound.ENTITY_SPIDER_HURT, 0.5f, 1.5f);
                entity.getWorld().spawnParticle(Particle.DUST, at, 12, 0.3, 0.5, 0.3, 0, VENOM);
            }
            case STUN -> {
                entity.getWorld().playSound(at, Sound.ENTITY_PLAYER_ATTACK_CRIT, 0.8f, 0.6f);
                entity.getWorld().spawnParticle(Particle.CRIT, at.clone().add(0, entity.getHeight() * 0.4, 0), 16, 0.35, 0.2, 0.35, 0.1);
            }
            case ELECTROCUTE -> {
                entity.getWorld().playSound(at, Sound.ENTITY_LIGHTNING_BOLT_IMPACT, 0.5f, 1.8f);
                entity.getWorld().spawnParticle(Particle.ELECTRIC_SPARK, at, 30, 0.4, 0.7, 0.4, 0.15);
            }
            default -> {
            }
        }
    }

    // ---- 每半秒 ----

    public void tick() {
        final long now = now();
        for (final UUID uuid : List.copyOf(tracked.keySet())) {
            final Tracked entry = tracked.get(uuid);
            if (entry == null) {
                continue; // 這一輪裡被清掉了（例如燒死觸發的死亡事件）
            }
            final Entity entity = plugin.getServer().getEntity(uuid);
            try {
                tickOne(uuid, entry, entity, now);
            } catch (final RuntimeException e) {
                // 一個出錯不影響其他人：還原並停止追蹤這一個
                plugin.getSLF4JLogger().error("更新 {} 的異常狀態失敗", uuid, e);
                if (entity instanceof LivingEntity living) {
                    try {
                        restore(living, entry);
                    } catch (final RuntimeException ignored) {
                        // 已經記錄過了
                    }
                }
                tracked.remove(uuid, entry);
            }
        }
    }

    private void tickOne(final UUID uuid, final Tracked entry, final Entity entity, final long now) {
        if (!(entity instanceof LivingEntity living) || !living.isValid() || living.isDead()) {
            if (entity instanceof LivingEntity living) {
                restore(living, entry);
            }
            tracked.remove(uuid, entry);
            return;
        }
        if (living instanceof Player && plugin.zones() != null && plugin.zones().inTown(living.getLocation())) {
            clear(living); // 營地是安全區：身上的異常全部消掉
            return;
        }
        final AilmentState.Ignite ignite = entry.state.ignite(now);
        if (ignite != null) {
            burn(living, entry, ignite, now);
            if (tracked.get(uuid) != entry || !living.isValid() || living.isDead()) {
                return; // 燒死了（死亡事件已經清掉）
            }
        }
        final AilmentState.Ignite bleed = entry.state.bleed(now);
        if (bleed != null) {
            // 流血：目標在移動就更痛（PoE2）
            // 2026-10：加劇的流血已經照移動算了，不再乘第二次
            // 2026-10-27 照 PoE2 Character.ot（no_extra_bleeding_damage_while_moving）：玩家移動時流血不會更痛，只有怪物會；這次之前玩家也 ×2
            final double factor = AilmentState.bleedFactor(bleed, !(living instanceof Player) && moving(living), config.bleedMovingMultiplier());
            damageOverTime(living, entry, bleed.dps() * factor, bleed.source(), AilmentType.BLEED, now);
            if (tracked.get(uuid) != entry || !living.isValid() || living.isDead()) {
                return;
            }
        }
        // 中毒（2026-10-19）：每個人的毒分開算給他自己（以前全部算給毒最強的那個人：組隊時其他人的傷害紀錄、掉落分份都是 0）
        for (final java.util.Map.Entry<UUID, Double> share : entry.state.poisonBySource(now).entrySet()) {
            damageOverTime(living, entry, share.getValue(), share.getKey(), AilmentType.POISON, now);
            if (tracked.get(uuid) != entry || !living.isValid() || living.isDead()) {
                return;
            }
        }
        final double corruptedDps = entry.state.corruptedBloodDps(now); // 2026-10-23：腐化之血（物理持續傷害，算成流血）
        if (corruptedDps > 0) {
            damageOverTime(living, entry, corruptedDps, null, AilmentType.BLEED, now);
            if (tracked.get(uuid) != entry || !living.isValid() || living.isDead()) {
                return;
            }
        }
        final double debuffDps = entry.state.debuffDps(now); // 2026-10：混沌減益（汲魂彈、疫潮），算成混沌的持續傷害
        if (debuffDps > 0) {
            damageOverTime(living, entry, debuffDps, entry.state.debuffSource(now), AilmentType.POISON, now);
            if (tracked.get(uuid) != entry || !living.isValid() || living.isDead()) {
                return;
            }
        }
        entry.state.decay(now, config.freeze().decayDelayTicks(entry.player), config.freeze().decayPerTick(entry.player));
        entry.state.stun().decay(now, config.stun().decayDelayTicks(entry.player), config.stun().decayPerTick(entry.player));
        entry.state.electrocute().decay(now, config.electrocute().decayDelayTicks(entry.player), config.electrocute().decayPerTick(entry.player));
        entry.state.pin().decay(now, config.pin().decayDelayTicks(entry.player), config.pin().decayPerTick(entry.player));
        update(living, entry, now);
        if (entry.state.isEmpty(now)) {
            restore(living, entry);
            tracked.remove(uuid, entry);
        }
    }

    /** delay 個 tick 之後馬上更新一次（凍結結束時準時恢復移動）。 */
    private void refreshAt(final LivingEntity living, final Tracked entry, final long delay) {
        if (delay <= 0) {
            return;
        }
        plugin.getServer().getScheduler().runTaskLater(plugin, () -> {
            if (tracked.get(living.getUniqueId()) != entry || !living.isValid() || living.isDead()) {
                return;
            }
            final long now = now();
            update(living, entry, now);
            if (entry.state.isEmpty(now)) {
                restore(living, entry);
                tracked.remove(living.getUniqueId(), entry);
            }
        }, delay);
    }

    /** 點燃扣血：怪物直接扣原版血量，玩家先扣護盾再扣生命；感電讓點燃也更痛。 */
    private void burn(final LivingEntity living, final Tracked entry, final AilmentState.Ignite ignite, final long now) {
        double dps = ignite.dps();
        // 2026-10-27 照 PoE2 Stormfire：被掛了雷焚的技能感電的敵人，點燃另外受到 x% 的閃電傷害（點燃的減免前傷害 × x%，吃閃電抗性）
        final double storm = living instanceof Player || plugin.skills() == null ? 0 : plugin.skills().supportMechanics().extras().stormfire(living);
        if (storm > 0 && plugin.monsters() != null && plugin.monsters().data(living).isPresent()) {
            final StatSheet sheet = plugin.monsters().sheet(living, plugin.monsters().data(living).get());
            final double fire = resistFactor(sheet, ignite.damageType()); // 2026-10-28：混沌點燃（黯炎）用混沌抗性反推減免前的傷害
            if (fire > 1e-6) {
                dps += dps / fire * storm / 100.0 * resistFactor(sheet, DamageType.LIGHTNING);
            }
        }
        damageOverTime(living, entry, dps, ignite.source(), AilmentType.IGNITE, ignite.damageType(), now);
        if (ignite.chaos() && !(living instanceof Player)) {
            // 2026-10-28：黯炎的點燃是暗紫色的火（靈魂火焰）
            living.getWorld().spawnParticle(Particle.SOUL_FIRE_FLAME, living.getLocation().add(0, living.getHeight() * 0.5, 0), 3, 0.3, 0.4, 0.3, 0.01);
        }
    }

    /** 2026-10-27（Shock Conduction）：這隻目前的感電幅度（0 = 沒有）。 */
    public double shockMagnitude(final LivingEntity entity) {
        final Tracked entry = tracked.get(entity.getUniqueId());
        return entry == null ? 0 : entry.state.shock(now());
    }

    /**
     * 2026-10-27：暈眩的事件——怪物被玩家輕暈（event＝light_stun_applied：輔助的先鋒）；玩家被暈眩（輕暈、重暈：輔助的能障）。
     */
    private void stunEvents(final LivingEntity attacker, final LivingEntity defender, final SkillProfile skill, final String event) {
        if (plugin.skills() == null) {
            return;
        }
        if (defender instanceof Player stunned) {
            plugin.skills().supportMechanics().extras().playerStunned(stunned);
        } else if (event != null && attacker instanceof Player stunner && skill != null) {
            plugin.skills().procEvent(stunner, skill.id(), event, null, defender);
        }
    }

    /** 這個實體正在移動（流血更痛）：水平速度明顯。 */
    private static boolean moving(final LivingEntity living) {
        final org.bukkit.util.Vector velocity = living.getVelocity();
        return Math.hypot(velocity.getX(), velocity.getZ()) > 0.05;
    }

    /**
     * 持續傷害（點燃、流血、中毒）這半秒扣的血：怪物直接扣原版血量（死時算造成的玩家殺的），玩家先扣護盾再扣生命；
     * 感電讓全部持續傷害都更痛。
     */
    private void damageOverTime(final LivingEntity living, final Tracked entry, final double dps, final UUID source,
                                final AilmentType type, final long now) {
        damageOverTime(living, entry, dps, source, type, dotType(type), now);
    }

    /** 2026-10-28：damageType＝這個持續傷害實際是哪一種傷害（黯炎的點燃是混沌；其他照 {@link #dotType}）。 */
    private void damageOverTime(final LivingEntity living, final Tracked entry, final double dps, final UUID source,
                                final AilmentType type, final DamageType damageType, final long now) {
        final double base = dps * TICK_INTERVAL / 20.0 * (1 + entry.state.shock(now));
        if (base <= 0 || living.isInvulnerable()) {
            return;
        }
        if (living instanceof Player player) {
            final double amount = base;
            double taken = amount;
            if (source != null && !source.equals(player.getUniqueId()) && plugin.pvp() != null && plugin.pvp().inArena(player)) {
                // PvP 保底（2026-10）：別的玩家造成的持續傷害也算進「每秒最多打掉總血量的幾 %」
                taken = plugin.pvp().limit(player, amount, false);
                if (taken <= 0) {
                    return;
                }
            }
            if (burnPlayer(player, taken, type, damageType)) {
                credit(source, living, type, damageType, taken); // 競技場裡打玩家的
            }
            return;
        }
        // 2026-10-21：只加某一種傷害的減益（凋息：受到的混沌傷害增加）也加在持續傷害上（中毒是混沌、流血是物理、點燃是火焰）
        final double typed = plugin.combat() == null ? 0
                : plugin.combat().debuffs().typedTaken(living.getUniqueId(), source, plugin.getServer().getCurrentTick(), damageType); // 2026-10-28：混沌點燃吃凋零
        final double amount = typed > 0 ? base * (1 + typed) : base;
        if (plugin.training() != null && plugin.training().absorbDot(living, source, amount)) {
            credit(source, living, type, damageType, amount);
            return; // 營地的訓練假人：記給造成的人，不扣血
        }
        final MonsterData data = plugin.monsters().data(living).orElse(null);
        if (data == null) {
            return;
        }
        // 巔峰 Boss 的每秒傷害上限（2026-10）：持續傷害算造成的那個人的
        double capped = plugin.maps() == null ? amount : plugin.maps().capBossDamage(living, source, amount);
        if (capped <= 0) {
            return;
        }
        if (plugin.monsterModule() != null && plugin.monsterModule().mechanics() != null) {
            // 2026-10-23：怪物詞綴也擋持續傷害（造成的人離近身的太遠打不到；護盾、熔岩護盾先吃；噬魂的減傷）
            final Player from = source == null ? null : plugin.getServer().getPlayer(source);
            capped = plugin.monsterModule().mechanics().incoming(living, data, from, from, capped, Runnable::run, false);
            if (capped <= 0) {
                return;
            }
        }
        credit(source, living, type, damageType, capped);
        if (type == AilmentType.BLEED && !skillDot) { // 2026-10-28：技能自己的物理持續傷害（龍捲風）不算失血
            entry.bloodLoss += capped; // 2026-10-24：血宣
            if (source != null && plugin.skills() != null) {
                plugin.skills().spears().bloodLoss(living, source, capped); // 2026-10-27 照 PoE2 Bloodhound's Mark：失血累積重暈
            }
        }
        double vanilla = plugin.monsters().toVanillaDamage(living, data, capped);
        final double health = living.getHealth();
        if (plugin.maps() != null) {
            // 換階段鎖血（2026-10）：持續傷害也只能打到下一個階段的門檻
            final double floor = plugin.maps().phaseFloor(living);
            if (floor > 0 && health - vanilla < floor) {
                vanilla = Math.max(0, health - floor);
                plugin.maps().phaseLocked(living);
            }
        }
        if (source != null) {
            // 持續傷害也算造成的玩家打的（掉落分份、經驗歸屬）；溢出的不算
            final double scale = plugin.monsters().healthScale(living, data);
            // 2026-10：照上限之後的算（以前記的是上限前的：流血、點燃流派在 Boss 傷害排行上被高估好幾倍）
            plugin.monsters().ledger().record(living.getUniqueId(), source, Math.min(capped, scale > 0 ? health / scale : capped));
        }
        if (health - vanilla <= 1e-4) {
            // 燒死、流血死、毒死：算造成的玩家殺的（掉落、經驗、藥劑充能照給）
            final Player killer = source == null ? null : plugin.getServer().getPlayer(source);
            if (killer != null && killer.isOnline()) {
                living.setKiller(killer);
            }
            living.setHealth(0);
        } else {
            living.setHealth(health - vanilla);
        }
    }

    /** 戰鬥數據：持續傷害也算造成的玩家打出的（側欄另外列「持續」）。 */
    private void credit(final UUID source, final LivingEntity target, final AilmentType type, final DamageType damageType, final double amount) {
        if (source != null && !source.equals(target.getUniqueId()) && plugin.combat() != null && plugin.combat().hud() != null) {
            plugin.combat().hud().dealtDot(source, type, damageType, amount); // 2026-10-28：混沌點燃記成混沌
        }
    }

    /** 持續傷害是哪一種傷害：流血物理、中毒混沌、點燃火焰。 */
    private static DamageType dotType(final AilmentType type) {
        return switch (type) {
            case BLEED -> DamageType.PHYSICAL;
            case POISON -> DamageType.CHAOS;
            default -> DamageType.FIRE;
        };
    }

    /** @return 有沒有扣到（不能中異常、已經死了是 false） */
    private boolean burnPlayer(final Player player, final double amount, final AilmentType type, final DamageType damageType) {
        if (!canBeAilmented(player) || player.isDead()) {
            return false;
        }
        final PlayerState state = plugin.players().get(player);
        final StatSheet sheet = plugin.players().sheet(player);
        if ((type == AilmentType.POISON || damageType == DamageType.CHAOS) && StatKeys.flag(sheet, StatKeys.FLAG_CHAOS_IMMUNE)) { // 2026-10-28：混沌點燃也擋
            return false; // 2026-10：無垢之軀（混沌免疫）也不吃中毒的混沌持續傷害（以前只擋了「上中毒」以外的混沌擊中）
        }
        double rest = amount * heartstopper(player, sheet); // 2026-10-26 心靈永止（PoE2 Heartstopper）
        final double shield = state.energyShield();
        double absorbed = 0;
        // 2026-10-26 照 PoE2：流血、中毒不先扣能量護盾（這次之前全部持續傷害都先扣護盾）
        if (shield > 0 && type != AilmentType.BLEED && type != AilmentType.POISON) {
            absorbed = Math.min(shield, rest);
            state.setEnergyShield(shield - absorbed);
            rest -= absorbed;
        }
        // 2026-10：「受到的傷害先從魔力扣」（以念御身、傳奇）持續傷害也算（以前只算擊中：無垢之軀＋以念御身摸到一次點燃就死）
        final double fromMana = sheet.flat(StatKeys.DAMAGE_FROM_MANA, Set.of());
        if (fromMana > 0 && rest > 0) {
            final tw.exilecore.stats.IncomingDamage split = tw.exilecore.stats.IncomingDamage.split(0, rest, state.mana(), fromMana, 0);
            if (split.mana() > 0) {
                state.setMana(state.mana() - split.mana());
                absorbed += split.mana();
                rest = split.life();
            }
        }
        state.setLife(state.life() - rest);
        if (amount > 0) {
            // 2026-10-17：持續傷害也算在戰鬥中——打斷能量護盾回充、不能直接傳送走或離開競技場
            state.setLastDamageTick(plugin.getServer().getCurrentTick());
        }
        if (plugin.combat() != null) {
            // 死亡回顧：持續傷害也記（同一秒內合併成一筆）
            if (plugin.combat().hud() != null) {
                plugin.combat().hud().takenDot(player, type, damageType, absorbed + rest); // 戰鬥數據：受到的持續傷害
            }
            final String skillName = switch (type) {
                case BLEED -> "持續流血";
                case POISON -> "持續中毒";
                default -> "持續燃燒";
            };
            final java.util.Map<DamageType, Double> dealt = java.util.Map.of(damageType, amount);
            plugin.combat().recap().record(player, new tw.exilecore.combat.DeathRecap.Hit(plugin.getServer().getCurrentTick(), type.displayName(), skillName,
                    dealt, dealt, absorbed, rest, state.life(), state.maxLife(), true));
        }
        if (state.isDead()) {
            player.setHealth(0); // 交給原版處理死亡（死亡懲罰、重生點）
        } else {
            plugin.players().display(player, state);
        }
        return true;
    }

    /** 把目前的狀態套到實體上：減速、凍結、火、粒子、名字上的標記。 */
    private void update(final LivingEntity living, final Tracked entry, final long now) {
        final boolean frozen = entry.state.frozen(now);
        final boolean stunned = entry.state.stun().active(now);
        final boolean electrocuted = entry.state.electrocute().active(now);
        final boolean held = frozen || stunned || electrocuted;
        final double chill = entry.state.chill(now);
        final double shock = entry.state.shock(now);
        final boolean burning = entry.state.ignite(now) != null;
        final boolean bleeding = entry.state.bleed(now) != null;
        final boolean poisoned = !entry.state.poisons(now).isEmpty();
        final double slowed = AilmentState.combineSlow(chill, entry.state.hexSlow(now)); // 2026-10-23：癱瘓、阻礙一起算
        // 2026-10-26 照 PoE2：怪物最慢降到原本速度的 25%
        setSlow(living, entry, held ? 1.0 : entry.player ? slowed : Math.min(1 - MONSTER_MIN_SPEED, slowed));
        if (living instanceof Player) {
            setJumpBlocked(living, entry, held);
        } else if (living instanceof Mob mob) {
            setAware(mob, entry, !held);
        }
        if (frozen) {
            // 原版的「凍僵」外觀：發抖、玩家畫面結霜（原版的凍傷傷害由監聽器擋掉）
            markTouched(living);
            living.setFreezeTicks(living.getMaxFreezeTicks() + 30);
            entry.frozenVisual = true;
        } else if (entry.frozenVisual) {
            living.setFreezeTicks(0);
            entry.frozenVisual = false;
        }
        if (burning != entry.visualFire) {
            if (burning) {
                markTouched(living);
            }
            living.setVisualFire(burning ? TriState.TRUE : TriState.NOT_SET);
            entry.visualFire = burning;
        }
        final Location at = living.getLocation().add(0, living.getHeight() * 0.5, 0);
        if (frozen) {
            living.getWorld().spawnParticle(Particle.SNOWFLAKE, at, 5, 0.3, 0.5, 0.3, 0.0);
        } else if (chill > 0) {
            living.getWorld().spawnParticle(Particle.SNOWFLAKE, at, 2, 0.3, 0.5, 0.3, 0.0);
        }
        if (shock > 0) {
            living.getWorld().spawnParticle(Particle.ELECTRIC_SPARK, at, 3, 0.35, 0.6, 0.35, 0.05);
        }
        if (bleeding) {
            living.getWorld().spawnParticle(Particle.DUST, at.clone().subtract(0, living.getHeight() * 0.3, 0), 4, 0.25, 0.3, 0.25, 0, BLOOD);
        }
        if (poisoned) {
            living.getWorld().spawnParticle(Particle.DUST, at, 3, 0.3, 0.5, 0.3, 0, VENOM);
        }
        if (stunned) {
            living.getWorld().spawnParticle(Particle.CRIT, at.clone().add(0, living.getHeight() * 0.45, 0), 4, 0.3, 0.1, 0.3, 0.02);
        }
        if (electrocuted) {
            living.getWorld().spawnParticle(Particle.ELECTRIC_SPARK, at, 10, 0.35, 0.6, 0.35, 0.1);
        }
        if (!entry.player) {
            updateMarks(living, entry, burning, frozen, chill > 0, shock > 0, bleeding, poisoned ? entry.state.poisons(now).size() : 0,
                    stunned, electrocuted);
        }
        if (entry.state.lightStunned(now) && !stunned) {
            living.getWorld().spawnParticle(Particle.CRIT, at.clone().add(0, living.getHeight() * 0.45, 0), 2, 0.25, 0.1, 0.25, 0.01);
        }
    }

    // ---- 2026-10-23：怪物詞綴的地面、光環直接給玩家的異常（MonsterMechanics） ----

    /** 阻礙玩家 ticks 個 tick（阻礙光環、時間泡泡；不會被減速的、免疫阻礙的不算）。 */
    public void hinder(final Player player, final long ticks) {
        if (!canBeAilmented(player)) {
            return;
        }
        final StatSheet sheet = plugin.players().sheet(player);
        if (StatKeys.flag(sheet, StatKeys.FLAG_UNSLOWABLE) || StatKeys.flag(sheet, StatKeys.FLAG_HINDER_IMMUNE)) {
            return;
        }
        final Tracked entry = track(player);
        entry.state.hinder(now() + ticks);
        update(player, entry, now());
    }

    /**
     * 2026-10-24：玩家技能留下的地面作用在怪物身上（碎地、蔓寒）。slow：固定減速（碎地 0.2）；
     * chillAs：「當作造成這麼多冰冷傷害」來算冰緩（蔓寒：冰晶生命的 100%）。不會被減速的不算。
     */
    public void groundOnMonster(final LivingEntity target, final double slow, final double chillAs, final long ticks) {
        if (target == null || target instanceof Player || !target.isValid() || target.isDead() || plugin.monsters() == null) {
            return;
        }
        final java.util.Optional<tw.exilecore.monster.MonsterData> data = plugin.monsters().data(target);
        if (data.isEmpty()) {
            return;
        }
        final StatSheet sheet = plugin.monsters().sheet(target, data.get());
        if (StatKeys.flag(sheet, StatKeys.FLAG_UNSLOWABLE)) {
            return;
        }
        final long now = now();
        final Tracked entry = track(target);
        if (slow > 0) {
            entry.state.groundSlow(slow, now + ticks);
        }
        if (chillAs > 0) {
            final double threshold = AilmentConfig.threshold(baseThreshold(target, data.get(), sheet), sheet.increased(StatKeys.AILMENT_THRESHOLD, Set.of()));
            final double magnitude = threshold <= 0 ? 0 : config.chillMagnitude(chillAs, threshold, 0);
            if (magnitude > 0) {
                entry.state.applyChill(magnitude, now + ticks, now);
            }
        }
        update(target, entry, now);
    }

    /**
     * 2026-10-26 照 PoE2：玩家技能的冰緩地面、感電地面（Frost Nexus、Storm Lance）直接給怪物冰緩／感電，不擲機率、不造成傷害。
     * chill、shock 是小數（0.1 = 冰緩 10%、受到的傷害增加 10%）；不會被減速的不吃冰緩。
     */
    public void groundStatusOnMonster(final LivingEntity target, final double chill, final double shock, final long ticks) {
        if (target == null || target instanceof Player || !target.isValid() || target.isDead() || plugin.monsters() == null
                || plugin.monsters().data(target).isEmpty()) {
            return;
        }
        final StatSheet sheet = plugin.monsters().sheet(target, plugin.monsters().data(target).get());
        final long now = now();
        final Tracked entry = track(target);
        if (chill > 0 && !StatKeys.flag(sheet, StatKeys.FLAG_UNSLOWABLE)) {
            entry.state.applyChill(chill, now + ticks, now);
        }
        if (shock > 0) {
            entry.state.applyShock(shock, now + ticks, now);
        }
        update(target, entry, now);
    }

    /**
     * 2026-10-28 照 PoE2 寒心（Heart of Ice）：「像造成 baseCold 點冰冷傷害一樣」冰緩 target（不打人、不擲機率）。
     * 冰緩效果照擊中的算法（冰冷 ÷ 異常門檻，吃 sourceSheet 的冰緩效果、緩速幅度，目標身上的冰緩效果、緩速減免），
     * 再乘 effectScale（光環效果）；持續 ticks（光環每次補，離開範圍很快就沒了）。不會被減速、避免冰緩的不吃。回傳有沒有冰緩到。
     */
    public boolean chillFrom(final LivingEntity target, final double baseCold, final StatSheet sourceSheet, final Set<String> context,
                             final double effectScale, final long ticks) {
        if (baseCold <= 0 || target == null || target instanceof Player || !target.isValid() || target.isDead() || plugin.monsters() == null) {
            return false;
        }
        final java.util.Optional<tw.exilecore.monster.MonsterData> data = plugin.monsters().data(target);
        if (data.isEmpty()) {
            return false;
        }
        final StatSheet defenderSheet = plugin.monsters().sheet(target, data.get());
        if (StatKeys.flag(defenderSheet, StatKeys.FLAG_UNSLOWABLE) || avoided(defenderSheet, StatKeys.AVOID_CHILL, plugin.items().random())) {
            return false;
        }
        final double threshold = AilmentConfig.threshold(baseThreshold(target, data.get(), defenderSheet)
                        + Math.max(0, defenderSheet.flat(StatKeys.AILMENT_THRESHOLD, Set.of())),
                defenderSheet.increased(StatKeys.AILMENT_THRESHOLD, Set.of()));
        final double slowScale = Math.max(0, 1 + sourceSheet.increased(StatKeys.SLOW_MAGNITUDE, context));
        final double slowTaken = Math.max(0, defenderSheet.apply(StatKeys.SLOW_TAKEN, 1.0, Set.of()));
        final double magnitude = config.chillMagnitude(baseCold, threshold, sourceSheet.apply(StatKeys.CHILL_EFFECT, 1.0, context) * slowScale
                * Math.max(0, effectScale) * tw.exilecore.stats.TreeKeys.selfEffect(defenderSheet, tw.exilecore.stats.TreeKeys.CHILL_EFFECT_ON_SELF) - 1)
                * slowTaken;
        if (magnitude <= 0) {
            return false;
        }
        final long now = now();
        final Tracked entry = track(target);
        entry.state.applyChill(magnitude, now + Math.max(1, ticks), now);
        update(target, entry, now);
        return true;
    }

    /** 2026-10-23：拿掉某個玩家在怪物身上造成的持續傷害（他死了、下線、離開地圖）；外觀與標記下一次更新時跟上。 */
    public void removeSource(final UUID source) {
        for (final Tracked entry : tracked.values()) {
            if (!entry.player) {
                entry.state.removeSource(source);
            }
        }
    }

    /** 2026-10-23：時間泡泡：玩家身上的減益晚 extra tick 結束（戰鬥模組的減益，例如致盲，也一起延長）。 */
    public void extendDebuffs(final Player player, final long extra) {
        final long now = now();
        final Tracked entry = tracked.get(player.getUniqueId());
        if (entry != null) {
            entry.state.extendDebuffs(extra, now);
        }
        if (plugin.combat() != null) {
            plugin.combat().debuffs().extend(player.getUniqueId(), extra, now);
        }
    }

    /**
     * 站在點燃／冰緩／感電的地面上（怪物的足跡、時間泡泡）：直接上異常，不看機率。
     * value：點燃是每秒傷害、冰緩是減速比例（0.3）、感電是受到的傷害增加（0.2）。免疫元素異常的不算。
     */
    public void groundAilment(final Player player, final AilmentType type, final double value, final double seconds) {
        if (!canBeAilmented(player) || value <= 0 || plugin.zones() != null && plugin.zones().inTown(player.getLocation())) {
            return;
        }
        final StatSheet sheet = plugin.players().sheet(player);
        if (StatKeys.flag(sheet, StatKeys.FLAG_ELEMENTAL_AILMENT_IMMUNE)) {
            return;
        }
        final long now = now();
        final Tracked entry = track(player);
        switch (type) {
            // 2026-10-28 修正：燃燒地面（怪物的拖痕、地圖詞綴）是火焰持續傷害，吃火焰抗性（照抗性上限）；這次之前不吃抗性
            case IGNITE -> entry.state.addIgnite(value * igniteResist(sheet, DamageType.FIRE), now + ticks(seconds), null);
            case CHILL -> {
                if (StatKeys.flag(sheet, StatKeys.FLAG_UNSLOWABLE)) {
                    return;
                }
                entry.state.applyChill(value * Math.max(0, sheet.apply(StatKeys.SLOW_TAKEN, 1.0, Set.of())), now + ticks(seconds), now);
            }
            case SHOCK -> entry.state.applyShock(value, now + ticks(seconds), now);
            default -> {
                return;
            }
        }
        update(player, entry, now);
    }

    /** 自己著火（煉獄使者的獰笑焚身）：每秒 dps 火焰傷害，持續 seconds 秒；已經在燒更強的就不換。 */
    public void igniteSelf(final Player player, final double rawDps, final double rawSeconds) {
        igniteSelf(player, rawDps, rawSeconds, false);
    }

    /** 2026-10-28：chaos＝反射回來的是混沌點燃（黯炎），吃自己的混沌抗性。 */
    public void igniteSelf(final Player player, final double rawDps, final double rawSeconds, final boolean chaos) {
        if (rawDps <= 0) {
            return;
        }
        // 2026-10-24 修正（狂焰焚身）：自己點燃自己是火焰持續傷害，吃火焰抗性（照抗性上限、最低 MIN_RESIST）；
        // 持續時間吃自己身上的「異常／點燃持續時間」（以前固定 4 秒、整段不減）
        final StatSheet sheet = plugin.players().sheet(player);
        final double dps = rawDps * igniteResist(sheet, chaos ? DamageType.CHAOS : DamageType.FIRE); // 2026-10-28：同 resistFactor（照抗性上限）
        final double seconds = AilmentConfig.duration(rawSeconds, 0, sheet.increased(StatKeys.SELF_AILMENT_DURATION, Set.of())
                + sheet.increased(StatKeys.SELF_AILMENT_DURATION + ".ignite", Set.of()));
        if (dps <= 0) {
            return;
        }
        final Tracked entry = track(player);
        final long now = now();
        final AilmentState.Ignite current = entry.state.ignite(now);
        if (current != null && current.dps() >= dps) {
            return;
        }
        entry.state.addIgnite(dps, now + ticks(seconds), null, chaos);
        refreshAt(player, entry, 1);
    }

    // ---- 蓄勢待暈、破甲（2026-10 PoE2）----

    /** 這隻怪的暈眩條到「蓄勢待暈」了沒（普通 40%、魔法 50%、稀有 60%、傳奇 70%）。 */
    private boolean primed(final Tracked entry, final MonsterData data, final long now) {
        entry.state.stun().decay(now, config.stun().decayDelayTicks(), config.stun().decayPerTick());
        return !entry.state.stun().active(now) && entry.state.stun().buildup() >= config.primed(data.rarity());
    }

    /** 2026-10-26 照 PoE2：蓄勢待凍（凍結條到稀有度的門檻，還沒凍住）。 */
    public boolean isPrimedForFreeze(final LivingEntity entity) {
        final Tracked entry = tracked.get(entity.getUniqueId());
        if (entry == null) {
            return false;
        }
        final long now = now();
        entry.state.decay(now, config.freeze().decayDelayTicks(), config.freeze().decayPerTick());
        return !entry.state.frozen(now) && plugin.monsters().data(entity).map(data -> entry.state.buildup() >= config.primed(data.rarity())).orElse(false);
    }

    /**
     * 2026-10-27 照 PoE2（寒夜嗥 Arctic Howl：Freezes enemies that are Primed for Freeze）：蓄勢待凍的怪直接凍結（基礎凍結時間），
     * 回傳有沒有凍住。
     */
    public boolean freezeIfPrimed(final LivingEntity entity) {
        if (entity instanceof org.bukkit.entity.Player || !isPrimedForFreeze(entity)) {
            return false;
        }
        final Tracked entry = tracked.get(entity.getUniqueId());
        if (entry == null) {
            return false;
        }
        final long now = now();
        entry.state.freeze(now, ticks(config.freezeDuration(false)), 0);
        entry.state.freezeMeter().raiseThreshold(config.freeze().after(false), config.freeze().afterTicks(false), now);
        refreshAt(entity, entry, entry.state.frozenUntil() - now);
        feedback(entity, AilmentType.FREEZE);
        return true;
    }

    /** 2026-10-27 照 PoE2 Exploit Weakness：用掉這隻的完全破甲（護甲恢復）；本來就沒有完全破甲回傳 false。 */
    public boolean consumeFullBreak(final LivingEntity entity) {
        final Tracked entry = tracked.get(entity.getUniqueId());
        if (entry == null || !entry.state.fullyBroken(now())) {
            return false;
        }
        entry.state.restoreArmour();
        return true;
    }

    /**
     * 2026-10-27 照 PoE2 Bloodhound's Mark（血嗅印：失血的 300% 算成重暈累積）：對怪物累積 damage 點的重暈（不是擊中，照牠的暈眩門檻），
     * 滿了照一般的重暈（基礎時間）。回傳這次累積了門檻的幾成（0–1）；玩家、不是怪物的回傳 0。
     */
    public double addStunFromDamage(final LivingEntity defender, final double damage) {
        if (damage <= 0 || defender instanceof Player || !defender.isValid() || defender.isDead() || plugin.monsters() == null) {
            return 0;
        }
        final MonsterData monster = plugin.monsters().data(defender).orElse(null);
        if (monster == null) {
            return 0;
        }
        final long now = now();
        Tracked entry = tracked.get(defender.getUniqueId());
        final double threshold = basePoise(defender, monster, null) * (entry == null ? 1.0 : entry.state.stun().thresholdMultiplier(now));
        final double buildup = config.stun().buildupFor(damage, threshold, 1.0);
        if (buildup <= 0) {
            return 0;
        }
        entry = entry == null ? track(defender) : entry;
        if (entry.state.stun().add(buildup, now, config.stun().decayDelayTicks(false), config.stun().decayPerTick(false))) {
            entry.state.stun().trigger(now, ticks(config.stun().duration(false)), 0);
            entry.state.stun().raiseThreshold(config.stun().after(false), config.stun().afterTicks(false), now);
            refreshAt(defender, entry, entry.state.stun().activeUntil() - now);
            feedback(defender, AilmentType.STUN);
        }
        update(defender, entry, now);
        return Math.min(1, buildup);
    }

    /** 2026-10-27 照 PoE2 Spar／Retaliate：這隻（玩家）的重暈條累積了幾成（0–1）。 */
    public double stunBuildup(final LivingEntity entity) {
        final Tracked entry = tracked.get(entity.getUniqueId());
        if (entry == null) {
            return 0;
        }
        final boolean player = entity instanceof Player;
        entry.state.stun().decay(now(), config.stun().decayDelayTicks(player), config.stun().decayPerTick(player));
        return Math.max(0, Math.min(1, entry.state.stun().buildup()));
    }

    /**
     * 2026-10-27 照 PoE2 Neural Overload（Supported Skills Electrocute Primed enemies on Hit）：電擊條到「蓄勢待電」（普通 40%、魔法 50%、
     * 稀有 60%、傳奇 70%）還沒電擊的怪直接電擊（基礎時間），回傳有沒有電到。
     */
    public boolean electrocuteIfPrimed(final LivingEntity entity) {
        if (entity instanceof Player) {
            return false;
        }
        final Tracked entry = tracked.get(entity.getUniqueId());
        final MonsterData data = entry == null || plugin.monsters() == null ? null : plugin.monsters().data(entity).orElse(null);
        if (data == null) {
            return false;
        }
        final long now = now();
        entry.state.electrocute().decay(now, config.electrocute().decayDelayTicks(false), config.electrocute().decayPerTick(false));
        if (entry.state.electrocute().active(now) || entry.state.electrocute().buildup() < config.primed(data.rarity())) {
            return false;
        }
        entry.state.electrocute().trigger(now, ticks(config.electrocute().duration(false)), 0);
        entry.state.electrocute().raiseThreshold(config.electrocute().after(false), config.electrocute().afterTicks(false), now);
        refreshAt(entity, entry, entry.state.electrocute().activeUntil() - now);
        feedback(entity, AilmentType.ELECTROCUTE);
        return true;
    }

    /** 蓄勢待暈（給其他模組，例如技能說明、昇華）。 */
    public boolean isPrimed(final LivingEntity entity) {
        final Tracked entry = tracked.get(entity.getUniqueId());
        return entry != null && plugin.monsters().data(entity).map(data -> primed(entry, data, now())).orElse(false);
    }

    /** 這一下打中前是滿血的目標（傷害寫進去之後才算異常，所以戰鬥那邊先記下來）。 */
    private final java.util.Set<java.util.UUID> fullLifeHits = new java.util.HashSet<>();

    /** 戰鬥結算時呼叫：這一下打中前目標是滿血（原版血量 ≥ 上限的 99.5%）就記下來，給「重暈滿血的敵人」用。 */
    public void noteFullLife(final LivingEntity entity) {
        final org.bukkit.attribute.AttributeInstance max = entity.getAttribute(org.bukkit.attribute.Attribute.MAX_HEALTH);
        if (max != null && max.getValue() > 0 && entity.getHealth() >= max.getValue() * 0.995) {
            fullLifeHits.add(entity.getUniqueId());
        } else {
            fullLifeHits.remove(entity.getUniqueId());
        }
    }

    /** 目前被破掉的護甲；完全破甲回傳 Double.POSITIVE_INFINITY。 */
    /** 異常、重暈給的戰星（2026-10）：只算玩家自己打的（召喚物的不算）；attack 是那一下技能的屬性表。 */
    private void chargeEvent(final LivingEntity attacker, final String event, final StatSheet attack, final Set<String> context,
                             final LivingEntity defender) {
        // 2026-10-21：圖騰、英靈造成的（點燃、凍結、感電、重暈）不給你戰星（照 PoE：圖騰的擊中不算你的）
        if (attacker instanceof Player player && plugin.combat() != null && plugin.combat().charges() != null
                && !context.contains(tw.exilecore.stats.Tags.TOTEM_HIT)) {
            plugin.combat().charges().onEvent(player, event, attack, context, defender);
        }
    }

    public double armourBroken(final LivingEntity entity) {
        final Tracked entry = tracked.get(entity.getUniqueId());
        if (entry == null) {
            return 0;
        }
        final long now = now();
        return entry.state.fullyBroken(now) ? Double.POSITIVE_INFINITY : entry.state.armourBroken(now);
    }

    /** 完全破甲的敵人受到的物理擊中傷害增加（小數，0.2 = 20%）。 */
    public double fullyBrokenTaken() {
        return config.fullyBrokenTaken() / 100.0;
    }

    /**
     * 破甲：玩家的擊中帶「破甲」（擊中前物理傷害的 x%），扣怪物的護甲；對普通怪 ×3、魔法 ×2（PoE2）。
     *
     * @param physical 這一擊減免前的物理傷害
     * @param armour   怪物沒被破之前的護甲
     */
    public boolean breakArmour(final LivingEntity defender, final MonsterData data, final double physical, final double percent, final double armour) {
        return breakArmour(defender, data, physical, percent, armour, 1.0);
    }

    /** 2026-10-26 照 PoE2 天賦樹：durationScale＝破甲持續時間的倍率（增加 x% 破甲持續時間）。 */
    public boolean breakArmour(final LivingEntity defender, final MonsterData data, final double physical, final double percent, final double armour,
                               final double durationScale) {
        if (physical <= 0 || percent <= 0 || armour <= 0 || data == null) {
            return false;
        }
        final double amount = physical * percent / 100.0 * config.armourBreakMultiplier(data.rarity());
        final Tracked entry = track(defender);
        final long now = now();
        final boolean full = entry.state.breakArmour(amount, armour, now,
                Math.max(1, Math.round(ticks(config.armourBreakDuration()) * Math.max(0.1, durationScale))));
        if (full) {
            defender.getWorld().playSound(defender.getLocation(), org.bukkit.Sound.ENTITY_ITEM_BREAK, 0.9f, 0.7f);
            defender.getWorld().spawnParticle(Particle.BLOCK, defender.getLocation().add(0, defender.getHeight() * 0.6, 0), 18, 0.3, 0.4, 0.3, 0,
                    org.bukkit.Material.IRON_BLOCK.createBlockData());
        }
        update(defender, entry, now);
        return full;
    }

    /** 怪物名字後面的標記：暈、麻、燃、寒（凍）、電、血、毒（疊幾層）。 */
    private void updateMarks(final LivingEntity living, final Tracked entry, final boolean burning, final boolean frozen,
                             final boolean chilled, final boolean shocked, final boolean bleeding, final int poisons,
                             final boolean stunned, final boolean electrocuted) {
        final StringBuilder marks = new StringBuilder();
        final long markNow = now();
        if (stunned) {
            marks.append(mark(AilmentType.STUN));
        } else {
            // 暈眩條（2026-10）：還沒暈的時候顯示累積幾成，蓄勢待暈變金色
            entry.state.stun().decay(markNow, config.stun().decayDelayTicks(), config.stun().decayPerTick());
            final double bar = entry.state.stun().buildup();
            if (bar >= 0.05) {
                final boolean ready = plugin.monsters().data(living).map(data -> bar >= config.primed(data.rarity())).orElse(false);
                marks.append(ready ? "<gold>暈" : "<gray>暈").append(Math.min(99, Math.round(bar * 100))).append("%");
            }
        }
        // 2026-10-26 照 PoE2：凍結條也顯示，到「蓄勢待凍」（普通 40%、魔法 50%、稀有 60%、傳奇 70%）變金色
        if (!frozen) {
            final double freezeBar = entry.state.buildup();
            if (freezeBar >= 0.05) {
                final boolean ready = plugin.monsters().data(living).map(data -> freezeBar >= config.primed(data.rarity())).orElse(false);
                marks.append(ready ? "<gold>凍" : "<#9fd8ff>凍").append(Math.min(99, Math.round(freezeBar * 100))).append("%");
            }
        }
        if (entry.state.fullyBroken(markNow)) {
            marks.append("<#c0c0c0>破甲");
        } else if (entry.state.armourBroken(markNow) > 0) {
            marks.append("<#8a8a8a>碎");
        }
        if (electrocuted) {
            marks.append(mark(AilmentType.ELECTROCUTE));
        }
        if (burning) {
            marks.append(mark(AilmentType.IGNITE));
        }
        if (frozen) {
            marks.append(mark(AilmentType.FREEZE));
        } else if (chilled) {
            marks.append(mark(AilmentType.CHILL));
        }
        if (shocked) {
            marks.append(mark(AilmentType.SHOCK));
        }
        if (bleeding) {
            marks.append(mark(AilmentType.BLEED));
        }
        if (poisons > 0) {
            marks.append(mark(AilmentType.POISON)).append(poisons > 1 ? "×" + poisons : "");
        }
        final String text = marks.toString();
        if (text.equals(entry.marks)) {
            return;
        }
        if (!text.isEmpty()) {
            markTouched(living);
        }
        entry.marks = text;
        plugin.monsters().data(living).ifPresent(data -> {
            final Component name = plugin.monsters().displayName(data);
            living.customName(text.isEmpty() ? name : name.append(plugin.text().parse(" " + text)));
        });
    }

    private static String mark(final AilmentType type) {
        return "<" + type.color() + ">" + type.mark();
    }

    /** 移動（與飛行）速度乘上 (1 − slow)；slow = 1 就完全不能動。只在數值變了才重新套。 */
    private void setSlow(final LivingEntity living, final Tracked entry, final double slow) {
        if (Math.abs(slow - entry.appliedSlow) < 1e-3) {
            return;
        }
        for (final Attribute attribute : new Attribute[] {Attribute.MOVEMENT_SPEED, Attribute.FLYING_SPEED}) {
            final AttributeInstance instance = living.getAttribute(attribute);
            if (instance == null) {
                continue;
            }
            instance.removeModifier(slowKey);
            if (slow > 0) {
                instance.addTransientModifier(new AttributeModifier(slowKey, -Math.min(1.0, slow),
                        AttributeModifier.Operation.MULTIPLY_SCALAR_1));
            }
        }
        entry.appliedSlow = slow;
    }

    /** 凍結的玩家不能跳。 */
    private void setJumpBlocked(final LivingEntity living, final Tracked entry, final boolean blocked) {
        if (blocked == entry.jumpBlocked) {
            return;
        }
        final AttributeInstance jump = living.getAttribute(Attribute.JUMP_STRENGTH);
        if (jump != null) {
            jump.removeModifier(jumpKey);
            if (blocked) {
                jump.addTransientModifier(new AttributeModifier(jumpKey, -1.0, AttributeModifier.Operation.MULTIPLY_SCALAR_1));
            }
        }
        entry.jumpBlocked = blocked;
    }

    /**
     * 凍結的怪物暫停 AI；做記號，萬一來不及恢復（區塊卸載、當機），下次載入時監聽器會恢復。
     * 先把速度壓到 0、停下尋路，下一個 tick 才暫停 AI：AI 要先跑一次，才會把「往前走」的輸入歸零，
     * 不然暫停後還會照原本的方向滑一段。
     */
    private void setAware(final Mob mob, final Tracked entry, final boolean aware) {
        if (!aware && !entry.madeUnaware && !entry.unawarePending && mob.isAware()) {
            entry.unawarePending = true;
            mob.getPathfinder().stopPathfinding();
            plugin.getServer().getScheduler().runTask(plugin, () -> {
                entry.unawarePending = false;
                if (tracked.get(mob.getUniqueId()) == entry && mob.isValid() && !mob.isDead()
                        && entry.state.incapacitated(now()) && mob.isAware()) {
                    mob.setAware(false);
                    mob.getPersistentDataContainer().set(unawareKey, PersistentDataType.BYTE, (byte) 1);
                    entry.madeUnaware = true;
                }
            });
        } else if (aware && entry.madeUnaware) {
            mob.setAware(true);
            mob.getPersistentDataContainer().remove(unawareKey);
            entry.madeUnaware = false;
        }
    }

    /** 做記號：身上有我們改過的外觀（火、凍僵、名字標記），萬一來不及還原，下次載入或登入時監聽器會清掉。 */
    private void markTouched(final LivingEntity living) {
        if (!living.getPersistentDataContainer().has(touchedKey)) {
            living.getPersistentDataContainer().set(touchedKey, PersistentDataType.BYTE, (byte) 1);
        }
    }

    /** 還原我們對這個實體做的所有改變。 */
    private void restore(final LivingEntity living, final Tracked entry) {
        setSlow(living, entry, 0);
        setJumpBlocked(living, entry, false);
        if (living instanceof Mob mob) {
            setAware(mob, entry, true);
        }
        if (entry.frozenVisual) {
            living.setFreezeTicks(0);
            entry.frozenVisual = false;
        }
        if (entry.visualFire) {
            living.setVisualFire(TriState.NOT_SET);
            entry.visualFire = false;
        }
        // 卸載時實體已經不是 valid，但名字還是要還原（接著就會存檔）
        if (!entry.player && !entry.marks.isEmpty() && !living.isDead()) {
            entry.marks = "";
            plugin.monsters().data(living).ifPresent(data -> living.customName(plugin.monsters().displayName(data)));
        }
        living.getPersistentDataContainer().remove(touchedKey);
    }

    /**
     * 上次沒來得及還原的（區塊卸載、換維度、當機）：載入、登入、插件啟用時呼叫。
     * 恢復 AI、拿掉火與凍僵的外觀、名字換回沒有標記的。
     */
    void recover(final LivingEntity living) {
        if (tracked.containsKey(living.getUniqueId())) {
            return; // 還在追蹤中（例如換維度後的新實體）：交給下一次更新
        }
        if (living instanceof Mob mob && mob.getPersistentDataContainer().has(unawareKey)) {
            mob.getPersistentDataContainer().remove(unawareKey);
            mob.setAware(true);
        }
        if (living.getPersistentDataContainer().has(touchedKey)) {
            living.getPersistentDataContainer().remove(touchedKey);
            living.setVisualFire(TriState.NOT_SET);
            if (living.getFreezeTicks() > 0) {
                living.setFreezeTicks(0);
            }
            if (!(living instanceof Player)) {
                plugin.monsters().data(living).ifPresent(data -> living.customName(plugin.monsters().displayName(data)));
            }
        }
    }

    /** 插件啟用時：已經載入的實體裡有沒有上次沒還原的。 */
    public void recoverLoaded() {
        for (final org.bukkit.World world : plugin.getServer().getWorlds()) {
            for (final LivingEntity living : world.getLivingEntities()) {
                recover(living);
            }
        }
    }

    // ---- 清除 ----

    /**
     * 2026-10-27 照 PoE2：任何帶物理傷害的擊中都可以造成流血（攻擊、法術都算；PoE1 才是只有攻擊）——
     * 法術要有流血機率才會流血（技能本身寫的、天賦「暴擊時 x% 機率造成流血」、輔助…）。
     * 這次之前只有攻擊會流血（再之前連血脈牽、骨綻、腐血嘯寫了流血機率都不會）。
     */
    static boolean canBleed(final SkillProfile skill) {
        return skill != null;
    }

    /** 只解除一種異常（護符，2026-10）；凍結、暈眩解除後外觀與移動照常還原。 */
    public void cure(final LivingEntity entity, final AilmentType type) {
        final Tracked entry = tracked.get(entity.getUniqueId());
        if (entry == null) {
            return;
        }
        if (plugin.skills() != null && has(entity, type)) {
            plugin.skills().originals().ailmentConsumed(entity, type); // 2026-10-26 照 PoE2：用掉異常（拾晶裝）
        }
        entry.state.cure(type);
        if (entry.state.isEmpty(plugin.getServer().getCurrentTick())) {
            tracked.remove(entity.getUniqueId());
            restore(entity, entry);
        }
    }

    /**
     * 2026-10-27：玩家的技能「用掉」敵人身上的異常（元崩、稜光裂、用掉異常的輔助觸發）。照 PoE2 無限循環：
     * 用掉感電時有 x% 機率重新施加——用掉的效果照常觸發，感電留在身上（同一個感電，時間照舊）。
     *
     * @return 異常真的拿掉了
     */
    public boolean consume(final LivingEntity entity, final AilmentType type, final org.bukkit.entity.Player by) {
        if (type == AilmentType.SHOCK && by != null && !(entity instanceof org.bukkit.entity.Player) && has(entity, type)) {
            final double chance = plugin.players().sheet(by).flat(tw.exilecore.stats.KeystoneOriginKeys.SHOCK_REAPPLY, java.util.Set.of());
            if (chance > 0 && plugin.random().nextDouble() * 100 < chance) {
                if (plugin.skills() != null) {
                    plugin.skills().originals().ailmentConsumed(entity, type); // 用掉的那一下照常算（拾晶裝）
                }
                entity.getWorld().spawnParticle(org.bukkit.Particle.ELECTRIC_SPARK, entity.getLocation().add(0, entity.getHeight() * 0.6, 0),
                        8, 0.3, 0.4, 0.3, 0.05);
                return false;
            }
        }
        cure(entity, type);
        return true;
    }

    /** 清掉一個實體身上的異常並還原（死亡、登出、換角色、卸載）。 */
    public void clear(final LivingEntity entity) {
        fullLifeHits.remove(entity.getUniqueId());
        final Tracked entry = tracked.remove(entity.getUniqueId());
        if (entry != null) {
            entry.state.clear();
            restore(entity, entry);
        }
    }

    /** 這個實體現在是不是被凍結的外觀（原版的凍傷傷害要擋掉）。 */
    boolean hasFrozenVisual(final LivingEntity entity) {
        final Tracked entry = tracked.get(entity.getUniqueId());
        return entry != null && entry.frozenVisual;
    }

    /** 模組停用：全部還原。 */
    public void clearAll() {
        for (final Map.Entry<UUID, Tracked> entry : Map.copyOf(tracked).entrySet()) {
            final Entity entity = plugin.getServer().getEntity(entry.getKey());
            if (entity instanceof LivingEntity living) {
                restore(living, entry.getValue());
            }
        }
        tracked.clear();
    }

    // ---- 顯示 ----

    /** 玩家動作列上的異常（沒有回傳空字串）：燃 2.5s、寒 25%、凍 1.2s、凍結 40%、電 +20%、血、毒、暈 0.8s／暈眩 45%、麻／電擊 60%。 */
    public String statusText(final Player player) {
        final Tracked entry = tracked.get(player.getUniqueId());
        if (entry == null) {
            return "";
        }
        final long now = now();
        final List<String> parts = new ArrayList<>();
        final AilmentState.Ignite ignite = entry.state.ignite(now);
        if (ignite != null) {
            parts.add(String.format(Locale.ROOT, "<%s>%s %.1fs", AilmentType.IGNITE.color(), AilmentType.IGNITE.mark(),
                    (ignite.endTick() - now) / 20.0));
        }
        if (entry.state.frozen(now)) {
            parts.add(String.format(Locale.ROOT, "<%s>%s %.1fs", AilmentType.FREEZE.color(), AilmentType.FREEZE.mark(),
                    (entry.state.frozenUntil() - now) / 20.0));
        } else {
            final double chill = entry.state.chill(now);
            if (chill > 0) {
                parts.add(String.format(Locale.ROOT, "<%s>%s %d%%", AilmentType.CHILL.color(), AilmentType.CHILL.mark(),
                        Math.round(chill * 100)));
            }
            if (entry.state.buildup() > 0.005) {
                parts.add(String.format(Locale.ROOT, "<%s>凍結 %d%%", AilmentType.FREEZE.color(), Math.round(entry.state.buildup() * 100)));
            }
        }
        final double shock = entry.state.shock(now);
        if (shock > 0) {
            parts.add(String.format(Locale.ROOT, "<%s>%s +%d%%", AilmentType.SHOCK.color(), AilmentType.SHOCK.mark(),
                    Math.round(shock * 100)));
        }
        final AilmentState.Ignite bleed = entry.state.bleed(now);
        if (bleed != null) {
            parts.add(String.format(Locale.ROOT, "<%s>%s %.1fs%s", AilmentType.BLEED.color(), AilmentType.BLEED.mark(),
                    (bleed.endTick() - now) / 20.0, "")); // 2026-10-27：玩家移動不再更痛，拿掉移動時的「⚠」
        }
        final int poisons = entry.state.poisons(now).size();
        if (poisons > 0) {
            parts.add(String.format(Locale.ROOT, "<%s>%s×%d %.1fs", AilmentType.POISON.color(), AilmentType.POISON.mark(), poisons,
                    entry.state.poisonRemaining(now) / 20.0));
        }
        // 2026-10-23：癱瘓、阻礙、致盲、腐化之血
        if (entry.state.maimUntil() > now) {
            parts.add(String.format(Locale.ROOT, "<#c08a5a>殘 %.1fs", (entry.state.maimUntil() - now) / 20.0));
        }
        if (entry.state.hinderUntil() > now) {
            parts.add(String.format(Locale.ROOT, "<#8a7ab8>阻 %.1fs", (entry.state.hinderUntil() - now) / 20.0));
        }
        if (entry.state.blindUntil() > now) {
            parts.add(String.format(Locale.ROOT, "<#9a9a9a>盲 %.1fs", (entry.state.blindUntil() - now) / 20.0));
        }
        final int corrupted = entry.state.corruptedBloodStacks(now);
        if (corrupted > 0) {
            parts.add(String.format(Locale.ROOT, "<#a0204a>腐血×%d %.1fs", corrupted, (entry.state.corruptedBloodUntil() - now) / 20.0));
        }
        meterText(parts, entry.state.stun(), AilmentType.STUN, now);
        meterText(parts, entry.state.electrocute(), AilmentType.ELECTROCUTE, now);
        return String.join("  ", parts);
    }

    /** 累積條的動作列文字：觸發中寫剩幾秒，不然寫累積到幾 %。 */
    private static void meterText(final List<String> parts, final AilmentState.Meter meter, final AilmentType type, final long now) {
        if (meter.active(now)) {
            parts.add(String.format(Locale.ROOT, "<%s>%s %.1fs", type.color(), type.mark(), (meter.activeUntil() - now) / 20.0));
        } else if (meter.buildup() > 0.005) {
            parts.add(String.format(Locale.ROOT, "<%s>%s %d%%", type.color(), type.displayName(), Math.round(meter.buildup() * 100)));
        }
    }
}
