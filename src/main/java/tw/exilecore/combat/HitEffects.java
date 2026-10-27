package tw.exilecore.combat;

import org.bukkit.NamespacedKey;
import org.bukkit.Particle;
import org.bukkit.attribute.Attribute;
import org.bukkit.attribute.AttributeInstance;
import org.bukkit.attribute.AttributeModifier;
import org.bukkit.entity.Entity;
import org.bukkit.entity.LivingEntity;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import tw.exilecore.ExileCore;
import tw.exilecore.monster.MonsterData;
import tw.exilecore.monster.MonsterRarity;
import tw.exilecore.stats.DamageType;
import tw.exilecore.stats.HitEvent;
import tw.exilecore.stats.HitResult;
import tw.exilecore.stats.Modifier;
import tw.exilecore.stats.SkillProfile;
import tw.exilecore.stats.StatKeys;
import tw.exilecore.stats.StatSheet;
import tw.exilecore.stats.Tags;

import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/**
 * 2026-10 PoE2 系統補齊：擊中怪物時擲機率套上的減益，以及減益讓怪物變慢。
 * <ul>
 *   <li>曝露（exposure.chance）：這一下打出的火焰／冰冷／閃電，那幾種抗性 −20%（曝露效果可以加；2026-10-26 照 PoE2 魔法、稀有、傳奇身上更少），4 秒。</li>
 *   <li>定身／釘地（pin.chance，只有投射物攻擊）：2026-10-26 照 PoE2 Pin 改成累積條（物理 × 4.2 ÷ 暈眩門檻，見 ailments.yml 的 pin），
 *   滿了 3 秒不能移動、照樣能攻擊。這次之前是擲機率：普通與魔法 2 秒、稀有 1.5 秒、傳奇 1 秒，之後 4 秒免疫。</li>
 *   <li>眩暈（daze.chance）：8 秒內受到的暈眩累積更多 50%（2026-10-26 照 PoE2；這次之前 4 秒、造成的傷害更少 15%、暈眩門檻減少 30%）。</li>
 *   <li>致盲（blind.chance，2026-10-21 PoE2 腐化）：4 秒內命中與閃避更少 20%。</li>
 *   <li>癱瘓（maim.chance，2026-10-21 PoE2 腐化）：4 秒內移動速度變慢 30%（吃減速幅度 slow.magnitude），2026-10-26 照 PoE2 閃避也減少 15%。</li>
 * </ul>
 * 減益的修飾器由 {@link CombatListener} 疊進屬性表；移動變慢（詛咒、定身）每 2 tick 在這裡套到原版的移動速度。
 */
public final class HitEffects implements Listener {

    public static final double EXPOSURE = 20;
    static final long EXPOSURE_TICKS = 80;
    /** 2026-10-26 照 PoE2：眩暈 8 秒（這次之前 4 秒）。 */
    static final long DAZE_TICKS = 160;
    /** 2026-10-26 照 PoE2：眩暈的敵人受到的暈眩累積更多 50%。 */
    public static final double DAZE_STUN_BUILDUP = 0.5;
    /** 2026-10-26 照 PoE2：癱瘓減少 15% 閃避。 */
    public static final double MAIM_EVASION = 0.15;
    static final long BLIND_TICKS = 80;
    static final long MAIM_TICKS = 80;
    /** 癱瘓的減速（PoE2：30%）；致盲的命中、閃避更少（PoE2：20%）。 */
    public static final double MAIM_SLOW = 0.3;
    public static final double BLIND_LESS = 0.2;

    private final ExileCore plugin;
    private final Debuffs debuffs;
    private final NamespacedKey slowKey;
    private final Map<UUID, Double> applied = new HashMap<>();
    /** 玩家最後打中的技能（擊殺後的增益看打死敵人的那一下的輔助寶石）。 */
    private final Map<UUID, SkillProfile> lastSkill = new HashMap<>();

    HitEffects(final ExileCore plugin, final Debuffs debuffs) {
        this.plugin = plugin;
        this.debuffs = debuffs;
        this.slowKey = new NamespacedKey(plugin, "debuff_slow");
    }

    private long now() {
        return plugin.getServer().getCurrentTick();
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onHit(final HitEvent event) {
        final HitResult result = event.result();
        final LivingEntity defender = event.defender();
        if (!result.hit() || result.blocked() || defender instanceof Player || plugin.monsters() == null || event.skill() == null) {
            return;
        }
        final MonsterData monster = plugin.monsters().data(defender).orElse(null);
        if (monster == null) {
            return;
        }
        final StatSheet sheet = attackerSheet(event.attacker());
        if (sheet == null) {
            return;
        }
        final SkillProfile skill = event.skill();
        if (event.attacker() instanceof Player) {
            lastSkill.put(event.attacker().getUniqueId(), skill);
        }
        final StatSheet effective = skill.effective(sheet);
        final Set<String> tags = skill.tags();
        final long now = now();
        final Player viewer = event.credit();
        final UUID source = viewer != null ? viewer.getUniqueId() : event.attacker().getUniqueId();
        final UUID target = defender.getUniqueId();

        final StatSheet defenderSheet = plugin.monsters().sheet(defender, monster);
        final double exposure = effective.flat(StatKeys.EXPOSURE_CHANCE, tags);
        if (exposure > 0 && roll(exposure)) {
            // 2026-10-26 照 PoE2：魔法、稀有、傳奇身上的曝露效果更少 15%／30%／50%；天賦「你造成的曝露再多降 x% 抗性」加在基礎 20% 上
            final double amount = (EXPOSURE + effective.flat(tw.exilecore.stats.TreeMechanicKeys.EXPOSURE_EXTRA, tags))
                    * Math.max(0, 1 + effective.increased(StatKeys.EXPOSURE_EFFECT, tags))
                    * Math.max(0, defenderSheet.apply(tw.exilecore.ailment.AilmentKeys.EXPOSURE_EFFECT_ON_SELF, 1.0, Set.of()));
            boolean any = false;
            for (final DamageType type : new DamageType[] {DamageType.FIRE, DamageType.COLD, DamageType.LIGHTNING}) {
                if (result.dealt().get(type) > 0) {
                    debuffs.apply(target, new Debuffs.Debuff("exposure." + type.tag(), source, now + EXPOSURE_TICKS, 0, false, null,
                            List.of(Modifier.flat(type.mitigationStat(), -amount * tw.exilecore.passive.ConquestEffects.exposureScale(effective, type),
                                    "曝露")), 0));
                    any = true;
                }
            }
            if (any) {
                feedback(viewer, defender, "曝露", Particle.ENCHANTED_HIT);
            }
        }
        final double pin = effective.flat(StatKeys.PIN_CHANCE, tags);
        // 2026-10-26 照 PoE2 Pin：有定身來源的投射物攻擊，減免前的物理傷害累積釘地條，滿了才釘住（這次之前照 pin.chance 擲機率）；天賦樹的「增加釘住累積」乘在累積上
        if (pin > 0 && skill.isAttack() && tags.contains(Tags.PROJECTILE) && plugin.ailments() != null
                && plugin.ailments().pinBuildup(defender, monster, defenderSheet, result.outgoing().get(DamageType.PHYSICAL),
                        Math.max(0, effective.apply(tw.exilecore.stats.TreeKeys.PIN_BUILDUP, 1.0, tags)))) { // 2026-10-26：也算更多（火力抑制更多 40% 禁錮累積）
            final long ticks = scaledTicks(Math.max(1, Math.round(plugin.ailments().config().pin().duration(false) * 20)),
                    effective.increased(tw.exilecore.stats.SupportKeys.PIN_DURATION, tags)); // 2026-10-26 照 PoE2 天賦樹：增加定身持續時間
            debuffs.apply(target, new Debuffs.Debuff("pin", source, now + ticks, 0, false, null, List.of(), 1.0));
            feedback(viewer, defender, "定身", Particle.CRIT);
            if (event.attacker() instanceof Player pinned) {
                plugin.players().conditions().onPinned(pinned, now); // 2026-10-26：天賦樹「最近釘住過敵人」
            }
            if (event.attacker() instanceof Player pinner && !tags.contains(Tags.TOTEM_HIT) && plugin.combat() != null
                    && plugin.combat().charges() != null) {
                plugin.combat().charges().onEvent(pinner, "pin", effective, tags, defender); // 2026-10-24：戰意（釘住時給怒星）
            }
        }
        final double daze = effective.flat(StatKeys.DAZE_CHANCE, tags);
        if (daze > 0 && roll(daze)) {
            debuffs.apply(target, new Debuffs.Debuff("daze", source, now + scaledTicks(DAZE_TICKS, // 2026-10-26：天賦的眩暈持續時間
                    effective.increased(tw.exilecore.stats.TreeMechanicKeys.DAZE_DURATION, tags)), 0, false, null,
                    List.of(Modifier.more(tw.exilecore.ailment.AilmentKeys.STUN_BUILDUP_TAKEN, DAZE_STUN_BUILDUP // 2026-10-26 照 PoE2 天賦樹：眩暈效果
                            * Math.max(0, 1 + effective.increased(tw.exilecore.stats.TreeEffectKeys.DAZE_EFFECT, tags)), "眩暈")), 0));
            feedback(viewer, defender, "眩暈", Particle.WAX_OFF);
        }
        final double blind = effective.flat(StatKeys.BLIND_CHANCE, tags);
        if (blind > 0 && !StatKeys.flag(effective, tw.exilecore.stats.SupportExtraKeys.FLAG_CANNOT_BLIND) && roll(blind)) { // 2026-10-27 照 PoE2 Blindside：不會致盲
            // 2026-10-26 照 PoE2：天賦的致盲持續時間、「被你致盲的敵人暴擊率降低」
            debuffs.apply(target, new Debuffs.Debuff("blind", source, now + scaledTicks(BLIND_TICKS,
                    effective.increased(tw.exilecore.stats.TreeMechanicKeys.BLIND_DURATION, tags)), 0, false, null,
                    blindModifiers(effective.increased(tw.exilecore.stats.TreeMechanicKeys.BLIND_ENEMY_CRIT, tags),
                            Math.max(0, 1 + effective.increased(tw.exilecore.stats.TreeEffectKeys.BLIND_EFFECT, tags))), 0));
            feedback(viewer, defender, "致盲", Particle.SMOKE);
        }
        // 2026-10-26：凋零、暴擊弱點、阻礙（PoE2：混沌弩箭、收割、魔力吸取…）
        final int withered = (int) Math.round(effective.flat(StatKeys.WITHERED_ON_HIT, tags));
        if (withered > 0) {
            addWithered(debuffs, target, source, now, withered, WITHERED_TICKS,
                    Math.max(0, 1 + effective.increased(tw.exilecore.stats.TreeEffectKeys.WITHERED_EFFECT, tags))); // 2026-10-26：凋零效果
        }
        final int weakness = (int) Math.round(effective.flat(StatKeys.CRIT_WEAKNESS_ON_HIT, tags));
        if (weakness > 0) {
            addCritWeakness(debuffs, target, source, now, weakness, CRIT_WEAKNESS_TICKS);
        }
        final double hinder = effective.flat(StatKeys.HINDER_ON_HIT, tags);
        if (hinder > 0 && roll(hinder)) {
            debuffs.apply(target, new Debuffs.Debuff("hinder", source, now + scaledTicks(HINDER_TICKS,
                    effective.increased(tw.exilecore.stats.TreeMechanicKeys.HINDER_DURATION, tags)), 0, false, null, List.of(), HINDER_SLOW));
        }
        final double maim = effective.flat(StatKeys.MAIM_CHANCE, tags);
        if (maim > 0 && !StatKeys.flag(effective, tw.exilecore.stats.SupportExtraKeys.FLAG_CANNOT_MAIM) && roll(maim)) { // 2026-10-27 照 PoE2 Undermine：不會癱瘓
            debuffs.apply(target, new Debuffs.Debuff("maim", source, now + MAIM_TICKS, 0, false, null,
                    List.of(Modifier.increased(StatKeys.EVASION, -MAIM_EVASION, "癱瘓")), maimSlow(effective.increased(StatKeys.SLOW_MAGNITUDE, tags))));
            feedback(viewer, defender, "癱瘓", Particle.DAMAGE_INDICATOR);
        }
    }

    /** 2026-10-26：持續時間乘 (1 + increased)，至少 1 tick。 */
    public static long scaledTicks(final long ticks, final double increased) {
        return Math.max(1, Math.round(ticks * Math.max(0.1, 1 + increased)));
    }

    /** 2026-10-26：致盲的減益：命中、閃避更少 20%；天賦給的「被你致盲的敵人暴擊率降低」（increased 是負的）。 */
    public static List<Modifier> blindModifiers(final double enemyCrit) {
        return blindModifiers(enemyCrit, 1.0);
    }

    /** 2026-10-26 照 PoE2 天賦樹：effect＝致盲效果的倍率（增加 x% 致盲效果：更少 20% 的那 20% 放大，最多 90%）。 */
    public static List<Modifier> blindModifiers(final double enemyCrit, final double effect) {
        final double less = Math.min(0.9, BLIND_LESS * Math.max(0, effect));
        final List<Modifier> mods = new java.util.ArrayList<>(List.of(Modifier.more(StatKeys.ACCURACY, -less, "致盲"),
                Modifier.more(StatKeys.EVASION, -less, "致盲")));
        if (enemyCrit != 0) {
            mods.add(Modifier.increased(StatKeys.CRIT_CHANCE, enemyCrit, "致盲"));
        }
        return mods;
    }

    /** 2026-10-26：凋零（PoE2 Withered）：每層受到的混沌傷害增加 5%，最多 10 層，預設 4 秒。 */
    public static final double WITHERED_TAKEN = 0.05;
    public static final int WITHERED_MAX = 10;
    public static final long WITHERED_TICKS = 80;
    /** 2026-10-26：暴擊弱點（PoE2 Critical Weakness）：每層擊中牠的暴擊率 +0.5%，最多 20 層，預設 4 秒。 */
    public static final double CRIT_WEAKNESS_CHANCE = 0.5;
    public static final int CRIT_WEAKNESS_MAX = 20;
    public static final long CRIT_WEAKNESS_TICKS = 80;
    /** 2026-10-26：阻礙（PoE2 Hinder）：移動速度變慢 30%，4 秒。 */
    public static final double HINDER_SLOW = 0.3;
    public static final long HINDER_TICKS = 80;

    /** 2026-10-26：給目標 stacks 層凋零（每層一條減益；滿了先換掉最快到期的那層）。 */
    public static void addWithered(final Debuffs debuffs, final UUID target, final UUID source, final long now, final int stacks, final long ticks) {
        addWithered(debuffs, target, source, now, stacks, ticks, 1.0);
    }

    /** 2026-10-26 照 PoE2 天賦樹：effect＝凋零效果的倍率（每層受到的混沌傷害增加 5% × effect）。 */
    public static void addWithered(final Debuffs debuffs, final UUID target, final UUID source, final long now, final int stacks, final long ticks,
                                   final double effect) {
        stack(debuffs, target, now, "withered.", WITHERED_MAX, stacks, slot -> new Debuffs.Debuff(slot, source, now + ticks,
                WITHERED_TAKEN * Math.max(0, effect), false, DamageType.CHAOS.tag()));
    }

    /** 2026-10-26：給目標 stacks 層暴擊弱點。 */
    public static void addCritWeakness(final Debuffs debuffs, final UUID target, final UUID source, final long now, final int stacks,
                                       final long ticks) {
        stack(debuffs, target, now, "crit_weakness.", CRIT_WEAKNESS_MAX, stacks, slot -> new Debuffs.Debuff(slot, source, now + ticks, 0,
                false, null, List.of(Modifier.flat(StatKeys.CRIT_CHANCE_TAKEN, CRIT_WEAKNESS_CHANCE, "暴擊弱點")), 0));
    }

    /** 目標身上這種層數減益有幾層。 */
    public static int stacks(final Debuffs debuffs, final UUID target, final String prefix, final long now) {
        int count = 0;
        for (final Debuffs.Debuff debuff : debuffs.active(target, now)) {
            if (debuff.id().startsWith(prefix)) {
                count++;
            }
        }
        return count;
    }

    private static void stack(final Debuffs debuffs, final UUID target, final long now, final String prefix, final int max, final int stacks,
                              final java.util.function.Function<String, Debuffs.Debuff> make) {
        for (int n = 0; n < Math.min(max, stacks); n++) {
            String slot = null;
            long soonest = Long.MAX_VALUE;
            String oldest = prefix + 0;
            final java.util.Map<String, Long> until = new java.util.HashMap<>();
            for (final Debuffs.Debuff debuff : debuffs.active(target, now)) {
                if (debuff.id().startsWith(prefix)) {
                    until.put(debuff.id(), debuff.untilTick());
                }
            }
            for (int i = 0; i < max; i++) {
                final Long end = until.get(prefix + i);
                if (end == null) {
                    slot = prefix + i;
                    break;
                }
                if (end < soonest) {
                    soonest = end;
                    oldest = prefix + i;
                }
            }
            debuffs.apply(target, make.apply(slot != null ? slot : oldest));
        }
    }

    /** 癱瘓慢多少：30% 乘上減速幅度（最多 90%）。 */
    static double maimSlow(final double slowMagnitudeIncreased) {
        return Math.min(0.9, MAIM_SLOW * Math.max(0, 1 + slowMagnitudeIncreased));
    }

    /** 擊殺後的增益（2026-10）：onkill.speed、onkill.damage（打死敵人的那個技能的輔助寶石，4 秒）。 */
    @EventHandler(priority = EventPriority.MONITOR)
    public void onDeath(final org.bukkit.event.entity.EntityDeathEvent event) {
        final Player killer = event.getEntity().getKiller();
        if (killer == null || event.getEntity() instanceof Player || plugin.skills() == null) {
            return;
        }
        final SkillProfile skill = lastSkill.get(killer.getUniqueId());
        if (skill == null) {
            return;
        }
        final StatSheet effective = skill.effective(plugin.players().sheet(killer));
        final double speed = effective.flat(StatKeys.ONKILL_SPEED, skill.tags());
        final double damage = effective.flat(StatKeys.ONKILL_DAMAGE, skill.tags());
        final List<Modifier> mods = new java.util.ArrayList<>();
        if (speed > 0) {
            mods.add(Modifier.increased(StatKeys.ATTACK_SPEED, speed / 100.0, "擊殺"));
            mods.add(Modifier.increased(StatKeys.CAST_SPEED, speed / 100.0, "擊殺"));
        }
        if (damage > 0) {
            mods.add(Modifier.increased(StatKeys.DAMAGE, damage / 100.0, "擊殺"));
        }
        if (!mods.isEmpty()) {
            plugin.skills().buff(killer, "onkill", "擊殺後", 80, mods);
        }
    }

    @EventHandler
    public void onQuit(final org.bukkit.event.player.PlayerQuitEvent event) {
        lastSkill.remove(event.getPlayer().getUniqueId());
    }

    private boolean roll(final double percent) {
        return percent >= 100 || plugin.items().random().nextDouble() * 100 < percent;
    }

    private StatSheet attackerSheet(final LivingEntity attacker) {
        if (attacker instanceof Player player) {
            return plugin.players().sheet(player);
        }
        final MinionHooks minions = plugin.combat() == null ? null : plugin.combat().minions();
        final MinionHooks.Attack attack = minions == null ? null : minions.attack(attacker);
        return attack == null ? null : attack.sheet();
    }

    private void feedback(final Player viewer, final LivingEntity target, final String word, final Particle particle) {
        if (viewer != null && plugin.combat() != null && plugin.combat().numbers() != null) {
            plugin.combat().numbers().word(viewer, target, word);
        }
        target.getWorld().spawnParticle(particle, target.getLocation().add(0, target.getHeight() * 0.7, 0), 8, 0.3, 0.3, 0.3, 0.05);
    }

    /** 每 2 tick：減益的移動變慢（詛咒、定身）套到原版移動速度；沒了就拿掉。 */
    void tick() {
        final long now = now();
        final Set<UUID> ids = new HashSet<>(debuffs.targets());
        ids.addAll(applied.keySet());
        for (final UUID id : ids) {
            final Entity entity = plugin.getServer().getEntity(id);
            if (!(entity instanceof LivingEntity living) || entity instanceof Player || !living.isValid()) {
                applied.remove(id);
                continue;
            }
            double slow = debuffs.slow(id, now);
            if (slow > 0 && slow < 1 && plugin.monsters() != null) {
                // 2026-10-23：怪物詞綴「不羈的」（身上的減速效果減半）；定身（1）不受影響
                // 2026-10-26 照 PoE2：稀有度的「緩速效果更少」也算；跟異常的減速合起來最慢降到原本速度的 25%
                final MonsterData data = plugin.monsters().data(living).orElse(null);
                if (data != null) {
                    slow *= Math.max(0, plugin.monsters().sheet(living, data).apply(StatKeys.SLOW_TAKEN, 1.0, Set.of()));
                    final double ailment = plugin.ailments() == null ? 0 : Math.min(0.99, plugin.ailments().appliedSlow(living));
                    slow = Math.max(0, Math.min(slow, 1 - tw.exilecore.ailment.AilmentService.MONSTER_MIN_SPEED / (1 - ailment)));
                }
            }
            apply(living, slow);
        }
    }

    private void apply(final LivingEntity living, final double slow) {
        final Double previous = applied.get(living.getUniqueId());
        if (previous == null ? slow <= 0 : Math.abs(previous - slow) < 1e-3) {
            return;
        }
        for (final Attribute attribute : new Attribute[] {Attribute.MOVEMENT_SPEED, Attribute.FLYING_SPEED}) {
            final AttributeInstance instance = living.getAttribute(attribute);
            if (instance == null) {
                continue;
            }
            instance.removeModifier(slowKey);
            if (slow > 0) {
                instance.addTransientModifier(new AttributeModifier(slowKey, -Math.min(1.0, slow), AttributeModifier.Operation.MULTIPLY_SCALAR_1));
            }
        }
        if (slow > 0) {
            applied.put(living.getUniqueId(), slow);
        } else {
            applied.remove(living.getUniqueId());
        }
    }

    void clear() {
        for (final UUID id : applied.keySet()) {
            if (plugin.getServer().getEntity(id) instanceof LivingEntity living) {
                for (final Attribute attribute : new Attribute[] {Attribute.MOVEMENT_SPEED, Attribute.FLYING_SPEED}) {
                    final AttributeInstance instance = living.getAttribute(attribute);
                    if (instance != null) {
                        instance.removeModifier(slowKey);
                    }
                }
            }
        }
        applied.clear();
    }
}
