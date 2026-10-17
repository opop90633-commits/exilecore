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
        final double dps = config.poisonDps(baseChaos, sourceSheet.increased(StatKeys.POISON_MAGNITUDE, context),
                sourceSheet.more(StatKeys.POISON_MAGNITUDE, context));
        final double seconds = AilmentConfig.duration(config.poisonDuration(),
                sourceSheet.increased(StatKeys.POISON_DURATION, context) + sourceSheet.increased(StatKeys.AILMENT_DURATION, context), 0);
        final long now = now();
        track(target).state.addPoison(dps, now + ticks(seconds), source.getUniqueId(),
                config.poisonMaxStacks() * (StatKeys.flag(sourceSheet, StatKeys.FLAG_DOUBLE_POISON) ? 2 : 1));
        feedback(target, AilmentType.POISON);
        return true;
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
        if (!result.hit() || result.blocked() || !defender.isValid() || defender.isDead()) {
            return;
        }
        if (defender instanceof Player player && !canBeAilmented(player)) {
            return;
        }
        final StatSheet attack = skill.effective(attackerSheet);
        // 純化：被輔助的技能不能造成元素異常（點燃、冰緩、凍結、感電）；中毒、流血、暈眩、電擊照常
        final boolean noElemental = StatKeys.flag(attack, StatKeys.FLAG_NO_AILMENTS);
        // 元素異常門檻（點燃、冰緩、凍結、感電、電擊）；暈眩用沒乘「增加元素異常門檻」的基礎值
        final double baseThreshold = baseThreshold(defender, defenderMonster, defenderSheet);
        final double threshold = AilmentConfig.threshold(baseThreshold, defenderSheet.increased(StatKeys.AILMENT_THRESHOLD, Set.of()));
        if (threshold <= 0) {
            return;
        }
        final double taken = takenMultiplier <= 0 ? 1.0 : takenMultiplier;
        final double fire = result.dealt().get(DamageType.FIRE) / taken;
        final double cold = result.dealt().get(DamageType.COLD) / taken;
        final double lightning = result.dealt().get(DamageType.LIGHTNING) / taken;
        final double physical = result.dealt().get(DamageType.PHYSICAL) / taken;
        final double chaos = result.dealt().get(DamageType.CHAOS) / taken;
        if (fire <= 0 && cold <= 0 && lightning <= 0 && physical <= 0 && chaos <= 0) {
            return;
        }
        final boolean playerTarget = defender instanceof Player;
        final boolean unique = defenderMonster != null && defenderMonster.rarity() == MonsterRarity.UNIQUE;
        final double selfDuration = defenderSheet.increased(StatKeys.SELF_AILMENT_DURATION, Set.of());
        final RandomGenerator random = plugin.items().random();
        final long now = now();
        Tracked entry = tracked.get(defender.getUniqueId());
        boolean changed = false;

        // PoE2 昇華：所有傷害都算進點燃／冰緩／感電（燃燒使者、冬之塑造者、風暴塑造者）
        final double allDamage = fire + cold + lightning + physical + chaos;
        final double igniteBase = noElemental ? 0 : StatKeys.flag(attack, StatKeys.FLAG_ALL_IGNITE) ? allDamage : fire;
        final double chillBase = noElemental ? 0 : StatKeys.flag(attack, StatKeys.FLAG_ALL_CHILL) ? allDamage : cold;
        final double shockBase = noElemental ? 0 : StatKeys.flag(attack, StatKeys.FLAG_ALL_SHOCK) ? allDamage : lightning;
        if (igniteBase > 0) {
            final Set<String> context = skill.contextFor(DamageType.FIRE);
            final double chance = config.igniteChance(igniteBase, threshold, attack.flat(StatKeys.IGNITE_CHANCE, context),
                    attack.increased(StatKeys.IGNITE_CHANCE, context))
                    * attack.more(StatKeys.IGNITE_CHANCE, context); // 2026-10：輔助的「更多／更少機率」
            if (chance > 0 && random.nextDouble() < chance && !avoided(defenderSheet, StatKeys.AVOID_IGNITE, random)) {
                final double dps = config.igniteDps(igniteBase, attack.increased(StatKeys.IGNITE_MAGNITUDE, context),
                        attack.more(StatKeys.IGNITE_MAGNITUDE, context));
                final double seconds = AilmentConfig.duration(config.igniteDuration(),
                        attack.increased(StatKeys.IGNITE_DURATION, context) + attack.increased(StatKeys.AILMENT_DURATION, context),
                        selfDuration + defenderSheet.increased(StatKeys.SELF_AILMENT_DURATION + ".ignite", Set.of()));
                entry = entry == null ? track(defender) : entry;
                entry.state.addIgnite(dps, now + ticks(seconds), attacker instanceof Player ? attacker.getUniqueId() : null);
                feedback(defender, AilmentType.IGNITE);
                if (attacker instanceof Player triggerer && plugin.skills() != null) {
                    plugin.skills().triggers().ailment(triggerer, defender, AilmentType.IGNITE, skill.id()); // 主要寶石：元素異常時施放（2026-10）
                }
                chargeEvent(attacker, "ignite", attack, context, defender); // 戰星（2026-10）
                changed = true;
            }
        }
        if (chillBase > 0) {
            final Set<String> context = skill.contextFor(DamageType.COLD);
            final double magnitude = config.chillMagnitude(chillBase, threshold, attack.apply(StatKeys.CHILL_EFFECT, 1.0, context) - 1); // 2026-10：「更多」的冰緩效果也算
            if (magnitude > 0 && !StatKeys.flag(defenderSheet, StatKeys.FLAG_UNSLOWABLE) && !avoided(defenderSheet, StatKeys.AVOID_CHILL, random)) {
                final double seconds = AilmentConfig.duration(config.chillDuration(playerTarget),
                        attack.increased(StatKeys.CHILL_DURATION, context) + attack.increased(StatKeys.AILMENT_DURATION, context),
                        selfDuration + defenderSheet.increased(StatKeys.SELF_AILMENT_DURATION + ".chill", Set.of()));
                entry = entry == null ? track(defender) : entry;
                changed |= entry.state.applyChill(magnitude, now + ticks(seconds), now);
            }
            final double buildup = config.freezeBuildup(cold, threshold, attack.apply(StatKeys.FREEZE_BUILDUP, 1.0, context), unique);
            if (buildup > 0) {
                entry = entry == null ? track(defender) : entry;
                if (entry.state.addBuildup(buildup, now, ticks(config.freezeDecayDelay()), config.freezeDecayPerSecond() / 20.0)) {
                    if (StatKeys.flag(defenderSheet, StatKeys.FLAG_FREEZE_IMMUNE) || avoided(defenderSheet, StatKeys.AVOID_FREEZE, random)) {
                        entry.state.resetBuildup();
                    } else {
                        final double seconds = AilmentConfig.duration(config.freezeDuration(playerTarget),
                                attack.increased(StatKeys.FREEZE_DURATION, context) + attack.increased(StatKeys.AILMENT_DURATION, context),
                                selfDuration + defenderSheet.increased(StatKeys.SELF_AILMENT_DURATION + ".freeze", Set.of()));
                        final long immunity = unique ? ticks(config.freezeUniqueImmunity())
                                : playerTarget ? ticks(config.freezePlayerImmunity()) : 0;
                        entry.state.freeze(now, ticks(seconds), immunity);
                        refreshAt(defender, entry, entry.state.frozenUntil() - now); // 解凍準時（不用等下一次半秒更新）
                        feedback(defender, AilmentType.FREEZE);
                        if (attacker instanceof Player triggerer && plugin.skills() != null) {
                            plugin.skills().triggers().ailment(triggerer, defender, AilmentType.FREEZE, skill.id()); // 主要寶石（2026-10）
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
            final double chance = config.shockChance(shockBase, threshold, attack.flat(StatKeys.SHOCK_CHANCE, context),
                    attack.increased(StatKeys.SHOCK_CHANCE, context))
                    * attack.more(StatKeys.SHOCK_CHANCE, context); // 2026-10：輔助的「更多／更少機率」
            if (chance > 0 && random.nextDouble() < chance && !avoided(defenderSheet, StatKeys.AVOID_SHOCK, random)) {
                final double effect = config.shockEffect(attack.increased(StatKeys.SHOCK_EFFECT, context));
                final double seconds = AilmentConfig.duration(config.shockDuration(playerTarget),
                        attack.increased(StatKeys.SHOCK_DURATION, context) + attack.increased(StatKeys.AILMENT_DURATION, context),
                        selfDuration + defenderSheet.increased(StatKeys.SELF_AILMENT_DURATION + ".shock", Set.of()));
                entry = entry == null ? track(defender) : entry;
                if (entry.state.applyShock(effect, now + ticks(seconds), now)) {
                    feedback(defender, AilmentType.SHOCK);
                    if (attacker instanceof Player triggerer && plugin.skills() != null) {
                        plugin.skills().triggers().ailment(triggerer, defender, AilmentType.SHOCK, skill.id()); // 主要寶石（2026-10）
                    }
                    chargeEvent(attacker, "shock", attack, context, defender); // 戰星（2026-10）
                    changed = true;
                }
            }
        }
        // 流血（PoE2）：只有攻擊的物理傷害；機率看加成（流血機率 +x%）
        if (physical > 0 && skill.isAttack()) {
            final Set<String> context = skill.contextFor(DamageType.PHYSICAL);
            final double chance = config.bleedChance(physical, threshold, attack.flat(StatKeys.BLEED_CHANCE, context),
                    attack.increased(StatKeys.BLEED_CHANCE, context))
                    * attack.more(StatKeys.BLEED_CHANCE, context); // 2026-10：輔助的「更多／更少機率」
            if (chance > 0 && random.nextDouble() < chance && !avoided(defenderSheet, StatKeys.AVOID_BLEED, random)
                    && !StatKeys.flag(defenderSheet, StatKeys.FLAG_CHAOS_IMMUNE)) { // 混沌免疫（PoE2）也免疫流血
                // 猩紅突襲（PoE2 基石）：流血加劇（不管目標有沒有在動都照移動算）、基礎持續 1 秒、更多 50%
                final boolean crimson = StatKeys.flag(attack, StatKeys.FLAG_CRIMSON_ASSAULT);
                final double dps = config.bleedDps(physical, attack.increased(StatKeys.BLEED_MAGNITUDE, context),
                        attack.more(StatKeys.BLEED_MAGNITUDE, context)) * (crimson ? 1.5 * config.bleedMovingMultiplier() : 1.0);
                final double seconds = AilmentConfig.duration(crimson ? 1.0 : config.bleedDuration(),
                        attack.increased(StatKeys.BLEED_DURATION, context) + attack.increased(StatKeys.AILMENT_DURATION, context),
                        selfDuration + defenderSheet.increased(StatKeys.SELF_AILMENT_DURATION + ".bleed", Set.of()));
                entry = entry == null ? track(defender) : entry;
                entry.state.addBleed(dps, now + ticks(seconds), attacker instanceof Player ? attacker.getUniqueId() : null, crimson);
                feedback(defender, AilmentType.BLEED);
                changed = true;
            }
        }
        // 中毒（PoE2）：物理與混沌傷害都算，可以疊加
        if (physical + chaos > 0) {
            final Set<String> context = skill.contextFor(DamageType.CHAOS);
            final double chance = config.poisonChance(physical + chaos, threshold, attack.flat(StatKeys.POISON_CHANCE, context),
                    attack.increased(StatKeys.POISON_CHANCE, context))
                    * attack.more(StatKeys.POISON_CHANCE, context); // 2026-10：輔助的「更多／更少機率」
            if (chance > 0 && random.nextDouble() < chance && !avoided(defenderSheet, StatKeys.AVOID_POISON, random)
                    && !StatKeys.flag(defenderSheet, StatKeys.FLAG_CHAOS_IMMUNE)) { // 2026-10：混沌免疫也免疫中毒
                final double dps = config.poisonDps(physical + chaos, attack.increased(StatKeys.POISON_MAGNITUDE, context),
                        attack.more(StatKeys.POISON_MAGNITUDE, context));
                final double seconds = AilmentConfig.duration(config.poisonDuration(),
                        attack.increased(StatKeys.POISON_DURATION, context) + attack.increased(StatKeys.AILMENT_DURATION, context),
                        selfDuration + defenderSheet.increased(StatKeys.SELF_AILMENT_DURATION + ".poison", Set.of()))
                        * attack.more(StatKeys.POISON_DURATION, context); // 2026-10：「更少中毒持續時間」（蝕骨劇毒）也算
                entry = entry == null ? track(defender) : entry;
                entry.state.addPoison(dps, now + ticks(seconds), attacker instanceof Player ? attacker.getUniqueId() : null,
                        config.poisonMaxStacks() * (StatKeys.flag(attack, StatKeys.FLAG_DOUBLE_POISON) ? 2 : 1)); // 壓倒性毒性：疊加上限加倍
                feedback(defender, AilmentType.POISON);
                changed = true;
            }
        }
        // 混沌減益（2026-10，PoE2 的汲魂彈、疫潮）：每秒傷害 = 這一下傷害的 x%；同一個技能的不疊加
        final double debuffPercent = attack.flat(StatKeys.DEBUFF_CHAOS, skill.contextFor(DamageType.CHAOS));
        if (debuffPercent > 0 && !StatKeys.flag(defenderSheet, StatKeys.FLAG_CHAOS_IMMUNE)) {
            final double seconds = attack.flat(StatKeys.DEBUFF_DURATION, Set.of()) > 0 ? attack.flat(StatKeys.DEBUFF_DURATION, Set.of()) : 4;
            final double dps = allDamage * debuffPercent / 100.0;
            entry = entry == null ? track(defender) : entry;
            entry.state.addDebuff(debuffKey(skill.id()), new AilmentState.Debuff(dps, now + ticks(seconds),
                    attacker instanceof Player ? attacker.getUniqueId() : null, StatKeys.flag(attack, StatKeys.FLAG_DEBUFF_SPREAD), 0, dps));
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
            // 暈眩門檻 = 基礎門檻 ×（1 ＋ 增加暈眩門檻）：元素異常門檻不影響暈眩
            // 2026-10 PoE2：「+x 暈眩門檻」加在基礎門檻上
            final double stunThreshold = AilmentConfig.threshold(baseThreshold + Math.max(0, defenderSheet.flat(StatKeys.STUN_THRESHOLD, Set.of())),
                    defenderSheet.increased(StatKeys.STUN_THRESHOLD, Set.of()));
            final double buildup = playerTarget && !config.playerHeavyStun() ? 0
                    : config.stun().buildupFor(stunDamage, stunThreshold, attack.apply(StatKeys.STUN_BUILDUP, 1.0, context), unique);
            // 蓄勢待暈的怪被「重擊」技能打中：直接重暈（碎骨這類，PoE2）
            final boolean crush = defenderMonster != null && (entry != null && StatKeys.flag(attack, StatKeys.FLAG_CRUSH_PRIMED)
                    && primed(entry, defenderMonster, now)
                    // 2026-10（傳奇）：重暈滿血的敵人
                    || StatKeys.flag(attack, StatKeys.FLAG_STUN_FULL_LIFE) && fullLifeHits.remove(defender.getUniqueId()));
            // 輕暈：機率 ≈ 這一擊 ÷ 暈眩門檻，低於 15% 算 0；打斷一下
            final double lightChance = stunThreshold <= 0 ? 0 : Math.min(1.0, stunDamage / stunThreshold);
            if (lightChance * 100 >= config.lightStunMin() && !StatKeys.flag(defenderSheet, StatKeys.FLAG_UNWAVERING) && random.nextDouble() < lightChance
                    && !avoided(defenderSheet, StatKeys.AVOID_STUN, random)) {
                entry = entry == null ? track(defender) : entry;
                if (!entry.state.stun().active(now)) {
                    final long light = ticks(config.lightStunDuration(playerTarget));
                    entry.state.lightStun(now + light);
                    refreshAt(defender, entry, light);
                    changed = true;
                }
            }
            if (buildup > 0 || crush) {
                entry = entry == null ? track(defender) : entry;
                if (entry.state.stun().add(crush ? Math.max(buildup, 1.0) : buildup, now, config.stun().decayDelayTicks(), config.stun().decayPerTick())) {
                    if (avoided(defenderSheet, StatKeys.AVOID_STUN, random)) {
                        entry.state.stun().reset();
                    } else {
                        final double seconds = AilmentConfig.duration(config.stun().duration(playerTarget),
                                attack.increased(StatKeys.STUN_DURATION, context) + attack.increased(StatKeys.AILMENT_DURATION, context), selfDuration);
                        final long immunity = unique ? ticks(config.stun().uniqueImmunity()) : playerTarget ? ticks(config.stun().playerImmunity()) : 0;
                        entry.state.stun().trigger(now, ticks(seconds), immunity);
                        refreshAt(defender, entry, entry.state.stun().activeUntil() - now);
                        feedback(defender, AilmentType.STUN);
                        if (defender instanceof Player stunned) {
                            plugin.players().get(stunned).markRecent(tw.exilecore.stats.Recently.STUN, now); // 「最近被重暈過」
                        }
                        if (attacker instanceof Player stunner && plugin.skills() != null) {
                            plugin.skills().procEvent(stunner, skill.id(), "stun_applied", null, defender); // 輔助：重暈時（2026-10）
                        }
                        chargeEvent(attacker, "stun_applied", attack, context, defender); // 戰星：重暈時（2026-10）
                    }
                }
                changed = true;
            }
        }
        // 電擊（PoE2）：要有「電擊機率」的來源，閃電傷害才會累積；滿了就麻一段時間
        if (lightning > 0) {
            final Set<String> context = skill.contextFor(DamageType.LIGHTNING);
            final double chance = Math.max(0, attack.flat(StatKeys.ELECTROCUTE_CHANCE, context))
                    * Math.max(0, 1 + attack.increased(StatKeys.ELECTROCUTE_CHANCE, context)) / 100.0;
            if (chance > 0 && random.nextDouble() < chance) {
                final double buildup = config.electrocute().buildupFor(lightning, threshold, attack.apply(StatKeys.ELECTROCUTE_BUILDUP, 1.0, context), unique);
                if (buildup > 0) {
                    entry = entry == null ? track(defender) : entry;
                    if (entry.state.electrocute().add(buildup, now, config.electrocute().decayDelayTicks(), config.electrocute().decayPerTick())) {
                        if (avoided(defenderSheet, StatKeys.AVOID_ELECTROCUTE, random)) {
                            entry.state.electrocute().reset();
                        } else {
                            final double seconds = AilmentConfig.duration(config.electrocute().duration(playerTarget),
                                    attack.increased(StatKeys.ELECTROCUTE_DURATION, context) + attack.increased(StatKeys.AILMENT_DURATION, context), selfDuration);
                            final long immunity = unique ? ticks(config.electrocute().uniqueImmunity())
                                    : playerTarget ? ticks(config.electrocute().playerImmunity()) : 0;
                            entry.state.electrocute().trigger(now, ticks(seconds), immunity);
                            refreshAt(defender, entry, entry.state.electrocute().activeUntil() - now);
                            feedback(defender, AilmentType.ELECTROCUTE);
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
            final long length = Math.max(ticks(1), debuff.endTick() - now) + ticks(2);
            final AilmentState.Debuff passed = new AilmentState.Debuff(debuff.base() * (1 + spreads), now + Math.min(ticks(5), length),
                    debuff.source(), true, spreads, debuff.base());
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
        final double threshold = plugin.monsters().config().lifeAt(Math.max(1, level)) * config.monsterThreshold(MonsterRarity.NORMAL);
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
            out[2] = Math.min(1, config.freezeBuildup(cold, threshold, attack.apply(StatKeys.FREEZE_BUILDUP, 1.0, context), false));
        }
        return out;
    }

    public double estimateDps(final StatSheet attackerSheet, final tw.exilecore.stats.SkillProfile skill, final tw.exilecore.stats.DamagePacket expected,
                              final double critFactor, final double hitsPerSecond, final int level, final double hitDps) {
        if (hitsPerSecond <= 0 || expected == null || expected.isEmpty() || plugin.monsters() == null) {
            return hitDps;
        }
        final double threshold = plugin.monsters().config().lifeAt(Math.max(1, level)) * config.monsterThreshold(MonsterRarity.NORMAL);
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
            final double chance = config.igniteChance(igniteBase, threshold, attack.flat(StatKeys.IGNITE_CHANCE, context),
                    attack.increased(StatKeys.IGNITE_CHANCE, context)) * attack.more(StatKeys.IGNITE_CHANCE, context);
            final double seconds = AilmentConfig.duration(config.igniteDuration(),
                    attack.increased(StatKeys.IGNITE_DURATION, context) + attack.increased(StatKeys.AILMENT_DURATION, context), 0);
            dot += config.igniteDps(igniteBase, attack.increased(StatKeys.IGNITE_MAGNITUDE, context), attack.more(StatKeys.IGNITE_MAGNITUDE, context))
                    * uptime(chance, hitsPerSecond, seconds);
        }
        // 流血（只有攻擊）
        if (physical > 0 && skill.isAttack()) {
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
            final int maxStacks = config.poisonMaxStacks() * (StatKeys.flag(attack, StatKeys.FLAG_DOUBLE_POISON) ? 2 : 1);
            final double stacks = Math.min(maxStacks, Math.max(0, Math.min(1, chance)) * hitsPerSecond * seconds);
            dot += config.poisonDps(physical + chaos, attack.increased(StatKeys.POISON_MAGNITUDE, context), attack.more(StatKeys.POISON_MAGNITUDE, context))
                    * stacks;
        }
        // 混沌減益（2026-10，汲魂彈、疫潮）：每一下都會掛上（同一個技能不疊加），只要一直打就一直在
        final double debuffPercent = attack.flat(StatKeys.DEBUFF_CHAOS, skill.contextFor(DamageType.CHAOS));
        if (debuffPercent > 0) {
            final double seconds = attack.flat(StatKeys.DEBUFF_DURATION, Set.of()) > 0 ? attack.flat(StatKeys.DEBUFF_DURATION, Set.of()) : 4;
            dot += all * debuffPercent / 100.0 * uptime(1.0, hitsPerSecond, seconds);
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

    private double baseThreshold(final LivingEntity defender, final MonsterData monster, final StatSheet sheet) {
        if (defender instanceof Player player) {
            final PlayerState state = plugin.players().get(player);
            return state.maxLife() + state.maxEnergyShield();
        }
        return monster == null ? 0 : sheet.get(StatKeys.LIFE_MAX) * config.monsterThreshold(monster.rarity());
    }

    /** 擲避免：最多 {@link StatKeys#AVOID_CAP}%（堆再多也一樣；要免疫用開關）。 */
    private static boolean avoided(final StatSheet defender, final String stat, final RandomGenerator random) {
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
            final double factor = AilmentState.bleedFactor(bleed, moving(living), config.bleedMovingMultiplier());
            damageOverTime(living, entry, bleed.dps() * factor, bleed.source(), AilmentType.BLEED, now);
            if (tracked.get(uuid) != entry || !living.isValid() || living.isDead()) {
                return;
            }
        }
        final double poisonDps = entry.state.poisonDps(now);
        if (poisonDps > 0) {
            damageOverTime(living, entry, poisonDps, entry.state.poisonSource(now), AilmentType.POISON, now);
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
        entry.state.decay(now, ticks(config.freezeDecayDelay()), config.freezeDecayPerSecond() / 20.0);
        entry.state.stun().decay(now, config.stun().decayDelayTicks(), config.stun().decayPerTick());
        entry.state.electrocute().decay(now, config.electrocute().decayDelayTicks(), config.electrocute().decayPerTick());
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
        damageOverTime(living, entry, ignite.dps(), ignite.source(), AilmentType.IGNITE, now);
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
        final double amount = dps * TICK_INTERVAL / 20.0 * (1 + entry.state.shock(now));
        if (amount <= 0 || living.isInvulnerable()) {
            return;
        }
        if (living instanceof Player player) {
            double taken = amount;
            if (source != null && !source.equals(player.getUniqueId()) && plugin.pvp() != null && plugin.pvp().inArena(player)) {
                // PvP 保底（2026-10）：別的玩家造成的持續傷害也算進「每秒最多打掉總血量的幾 %」
                taken = plugin.pvp().limit(player, amount, false);
                if (taken <= 0) {
                    return;
                }
            }
            if (burnPlayer(player, taken, type)) {
                credit(source, living, type, taken); // 競技場裡打玩家的
            }
            return;
        }
        if (plugin.training() != null && plugin.training().absorbDot(living, source, amount)) {
            credit(source, living, type, amount);
            return; // 營地的訓練假人：記給造成的人，不扣血
        }
        final MonsterData data = plugin.monsters().data(living).orElse(null);
        if (data == null) {
            return;
        }
        // 巔峰 Boss 的每秒傷害上限（2026-10）：持續傷害算造成的那個人的
        final double capped = plugin.maps() == null ? amount : plugin.maps().capBossDamage(living, source, amount);
        if (capped <= 0) {
            return;
        }
        credit(source, living, type, capped);
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
    private void credit(final UUID source, final LivingEntity target, final AilmentType type, final double amount) {
        if (source != null && !source.equals(target.getUniqueId()) && plugin.combat() != null && plugin.combat().hud() != null) {
            plugin.combat().hud().dealtDot(source, type, dotType(type), amount);
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
    private boolean burnPlayer(final Player player, final double amount, final AilmentType type) {
        if (!canBeAilmented(player) || player.isDead()) {
            return false;
        }
        final PlayerState state = plugin.players().get(player);
        final StatSheet sheet = plugin.players().sheet(player);
        if (type == AilmentType.POISON && StatKeys.flag(sheet, StatKeys.FLAG_CHAOS_IMMUNE)) {
            return false; // 2026-10：無垢之軀（混沌免疫）也不吃中毒的混沌持續傷害（以前只擋了「上中毒」以外的混沌擊中）
        }
        double rest = amount;
        final double shield = state.energyShield();
        double absorbed = 0;
        if (shield > 0) {
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
            final DamageType damageType = dotType(type);
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
        setSlow(living, entry, held ? 1.0 : chill);
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

    /** 自己著火（煉獄使者的獰笑焚身）：每秒 dps 火焰傷害，持續 seconds 秒；已經在燒更強的就不換。 */
    public void igniteSelf(final Player player, final double dps, final double seconds) {
        if (dps <= 0) {
            return;
        }
        final Tracked entry = track(player);
        final long now = now();
        final AilmentState.Ignite current = entry.state.ignite(now);
        if (current != null && current.dps() >= dps) {
            return;
        }
        entry.state.addIgnite(dps, now + ticks(seconds), null);
        refreshAt(player, entry, 1);
    }

    // ---- 蓄勢待暈、破甲（2026-10 PoE2）----

    /** 這隻怪的暈眩條到「蓄勢待暈」了沒（普通 40%、魔法 50%、稀有 60%、傳奇 70%）。 */
    private boolean primed(final Tracked entry, final MonsterData data, final long now) {
        entry.state.stun().decay(now, config.stun().decayDelayTicks(), config.stun().decayPerTick());
        return !entry.state.stun().active(now) && entry.state.stun().buildup() >= config.primed(data.rarity());
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
        if (attacker instanceof Player player && plugin.combat() != null && plugin.combat().charges() != null) {
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
        if (physical <= 0 || percent <= 0 || armour <= 0 || data == null) {
            return false;
        }
        final double amount = physical * percent / 100.0 * config.armourBreakMultiplier(data.rarity());
        final Tracked entry = track(defender);
        final long now = now();
        final boolean full = entry.state.breakArmour(amount, armour, now, ticks(config.armourBreakDuration()));
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

    /** 只解除一種異常（護符，2026-10）；凍結、暈眩解除後外觀與移動照常還原。 */
    public void cure(final LivingEntity entity, final AilmentType type) {
        final Tracked entry = tracked.get(entity.getUniqueId());
        if (entry == null) {
            return;
        }
        entry.state.cure(type);
        if (entry.state.isEmpty(plugin.getServer().getCurrentTick())) {
            tracked.remove(entity.getUniqueId());
            restore(entity, entry);
        }
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
                    (bleed.endTick() - now) / 20.0, moving(player) ? "⚠" : ""));
        }
        final int poisons = entry.state.poisons(now).size();
        if (poisons > 0) {
            parts.add(String.format(Locale.ROOT, "<%s>%s×%d %.1fs", AilmentType.POISON.color(), AilmentType.POISON.mark(), poisons,
                    entry.state.poisonRemaining(now) / 20.0));
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
