package tw.exilecore.item.unique;

import io.papermc.paper.event.entity.EntityKnockbackEvent;
import io.papermc.paper.event.entity.EntityPushedByEntityAttackEvent;
import org.bukkit.Location;
import org.bukkit.Particle;
import org.bukkit.entity.Entity;
import org.bukkit.entity.LivingEntity;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.entity.EntityDeathEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.util.Vector;
import tw.exilecore.ExileCore;
import tw.exilecore.ailment.AilmentService;
import tw.exilecore.ailment.AilmentState;
import tw.exilecore.ailment.AilmentType;
import tw.exilecore.character.PlayerState;
import tw.exilecore.combat.Corpses;
import tw.exilecore.combat.Debuffs;
import tw.exilecore.stats.DamageType;
import tw.exilecore.stats.HitEvent;
import tw.exilecore.stats.HitResult;
import tw.exilecore.stats.Modifier;
import tw.exilecore.stats.SkillProfile;
import tw.exilecore.stats.StatKeys;
import tw.exilecore.stats.StatSheet;
import tw.exilecore.stats.Tags;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/**
 * 2026-10-26：照 PoE2 0.5 的傳奇特殊效果（要看事件、每半秒檢查的那些）。戰鬥模組啟用時建立（{@link tw.exilecore.combat.CombatModule}）。
 * <ul>
 *   <li>擊中：壓碎（{@link UniqueStats#CRUSH_ON_HIT}，物理傷害減免 -15%，4 秒）、深淵消耗（6 秒生命回復速度 -50%）、加劇流血。</li>
 *   <li>擊退的方向反過來（堞衛脛甲）。</li>
 *   <li>擊殺：替弩技能裝填一發（歃盟弩）、完全破甲／冰緩的敵人碎裂不留屍體（撬桿、長冬之戒）。</li>
 *   <li>每半秒：週期神龕（隕砧）、點燃擴散（火葬長杖）、燃燒地面（行灰之履）、每移動 1.2 格觸發潰骸（踏骸之履）、
 *       你施加的詛咒、凋零沒有持續時間限制（司祭法衣）。</li>
 *   <li>藥劑：生命藥劑也回復召喚物、召喚物在藥劑效果中不會死（骸骨束帶）。</li>
 *   <li>2026-10-27 第二批：暴擊觸發落雷（雷頌墜）、殺死感電的敵人觸發游雷（縛地長杖）、擊殺時護盾立刻回充（喪鈴法器）、
 *       格擋回復生命（降伏塔盾）與格擋斷命（厄門塔盾）、每秒失去生命（骸節鐵手）、每 3 秒吃一具屍體回血（噬屍頭環）。</li>
 * </ul>
 * 打中之前要疊的條件加成（{@link #attackerConditional}、{@link #defenderConditional}、{@link #pierceProfile}）是純函式，戰鬥與投射物呼叫。
 */
public final class UniqueMechanics implements Listener {

    /** 每幾 tick 檢查一次。 */
    public static final long PERIOD = 10;
    /** 深淵消耗的減益代號（怪物的生命回復看這個）。 */
    public static final String WASTING_ID = "unique.abyssal_wasting";
    public static final long WASTING_TICKS = 120;
    /** 壓碎的減益代號、時間、物理傷害減免降低多少（PoE2 Crushed：15%）。 */
    public static final String CRUSH_ID = "unique.crushed";
    public static final long CRUSH_TICKS = 80;
    public static final double CRUSH_REDUCTION = 15;
    /** 燃燒地面：每一塊留多久、多大、最多幾塊、隔多遠放一塊。 */
    static final long GROUND_TICKS = 160;
    static final double GROUND_RADIUS = 1.2;
    static final int GROUND_MAX = 24;
    static final double GROUND_STEP = 1.0;
    /** 無限的減益到哪個 tick。 */
    static final long FOREVER = Long.MAX_VALUE / 4;

    private final ExileCore plugin;
    private final Debuffs debuffs;
    private final Corpses corpses;

    private final Map<UUID, Long> nextShrine = new HashMap<>();
    /** 點燃擴散：附近的敵人從哪個 tick 開始待在燒著的敵人旁邊。 */
    private final Map<UUID, Long> spreadSince = new HashMap<>();
    /** 燃燒地面：玩家 → 地上的那幾塊。 */
    private final Map<UUID, List<Patch>> patches = new HashMap<>();
    private final Map<UUID, Location> lastPatch = new HashMap<>();
    /** 燃燒地面：怪物下次可以再被點的 tick（一秒一次）。 */
    private final Map<UUID, Long> groundCooldown = new HashMap<>();
    /** 潰骸觸發：上一次的位置、累積走了幾格。 */
    private final Map<UUID, Location> lastStep = new HashMap<>();
    private final Map<UUID, Double> travelled = new HashMap<>();
    /** 召喚物在生命藥劑效果中不會死：主人 → 到哪個 tick。 */
    private final Map<UUID, Long> undyingUntil = new HashMap<>();
    /** 2026-10-27：傳奇觸發的技能（玩家:技能 → 下次可以觸發的 tick）。 */
    private final Map<String, Long> triggerReady = new HashMap<>();
    /** 2026-10-27（噬屍頭環）：下次消耗屍體的 tick。 */
    private final Map<UUID, Long> nextCorpse = new HashMap<>();

    private record Patch(Location at, long until) {
    }

    public UniqueMechanics(final ExileCore plugin, final Debuffs debuffs, final Corpses corpses) {
        this.plugin = plugin;
        this.debuffs = debuffs;
        this.corpses = corpses;
        if (debuffs != null) {
            // 2026-10-28 照 PoE2 Blackflame（黯炎）：你施加的凋零同時增加受到的火焰傷害（Debuffs.typedTaken 問這裡）
            debuffs.setWitherFireSources(this::witherAlsoFire);
        }
    }

    /** 2026-10-28：這個玩家施加的凋零是不是也增加受到的火焰傷害（黯炎；不在線上的不算）。 */
    private boolean witherAlsoFire(final UUID source) {
        if (org.bukkit.Bukkit.getServer() == null) {
            return false;
        }
        final Player player = plugin.getServer().getPlayer(source);
        return player != null && plugin.players().find(source).isPresent()
                && StatKeys.flag(plugin.players().sheet(player), UniqueStats.WITHERED_FIRE_TAKEN);
    }

    private long now() {
        return plugin.getServer().getCurrentTick();
    }

    // ---- 打中之前疊上去的條件加成（純函式） ----

    /**
     * 攻擊方是玩家時這一下另外疊的：敵人冰緩的話霜環（冰冷傷害的一部分變成額外火焰）、寒息箭袋（受到的傷害增加冰緩效果）；
     * 敵人重暈的話屠王巨鎚（一定暴擊）、夢魘長棍（增加傷害）。
     */
    public static List<Modifier> attackerConditional(final AilmentService ailments, final LivingEntity defender, final StatSheet sheet,
                                                     final Set<String> hitTags) {
        if (ailments == null || defender == null) {
            return List.of();
        }
        List<Modifier> list = null;
        final double chill = ailments.chillMagnitude(defender);
        if (chill > 0) {
            final double perChill = sheet.flat(UniqueStats.COLD_AS_FIRE_PER_CHILL, Set.of());
            if (perChill > 0) {
                list = new ArrayList<>(3);
                // 「獲得冰冷傷害的 x% 為額外火焰傷害」：damage.extra.fire 只寫 cold 標籤，只算冰冷那一部分
                list.add(Modifier.flat(StatKeys.damageExtra(DamageType.FIRE), perChill * chill * 100, "冰緩的敵人").withTags(Set.of(Tags.COLD)));
            }
            if (StatKeys.flag(sheet, UniqueStats.CHILL_TAKEN_FROM_MAGNITUDE)) {
                list = list == null ? new ArrayList<>(3) : list;
                list.add(Modifier.more(StatKeys.DAMAGE, chill, "冰緩的敵人"));
            }
        }
        if (ailments.heavyStunned(defender)) {
            if (StatKeys.flag(sheet, UniqueStats.CRIT_VS_HEAVY_STUNNED)) {
                list = list == null ? new ArrayList<>(3) : list;
                list.add(Modifier.more(StatKeys.CRIT_CHANCE, 1000, "對重暈的敵人一定暴擊"));
            }
            final double increased = sheet.increased(UniqueStats.DAMAGE_VS_HEAVY_STUNNED, hitTags);
            if (increased != 0) {
                list = list == null ? new ArrayList<>(3) : list;
                list.add(Modifier.increased(StatKeys.DAMAGE, increased, "對重暈的敵人"));
            }
        }
        return list == null ? List.of() : list;
    }

    /** 挨打的是玩家時：你被冰緩時擊中你的暴擊率減少（暗潮指環）。回傳攻擊方要用的屬性表。 */
    public static StatSheet defenderConditional(final StatSheet attacker, final StatSheet defenderSheet, final AilmentService ailments,
                                                final LivingEntity defender) {
        final double increased = defenderSheet.increased(UniqueStats.CRIT_CHANCE_TAKEN_CHILLED, Set.of());
        if (increased == 0 || ailments == null || !ailments.has(defender, AilmentType.CHILL)) {
            return attacker;
        }
        return attacker.overlay(List.of(Modifier.more(StatKeys.CRIT_CHANCE, Math.max(-1, increased), "被冰緩時")));
    }

    /** 投射物這一下的技能描述：每穿透過一次，擊中傷害、暴擊率增加（穿雲箭袋）。 */
    public static SkillProfile pierceProfile(final SkillProfile profile, final int pierced, final StatSheet owner) {
        if (pierced <= 0 || owner == null) {
            return profile;
        }
        final double damage = owner.flat(UniqueStats.PIERCE_DAMAGE_PER, profile.tags());
        final double crit = owner.flat(UniqueStats.PIERCE_CRIT_PER, profile.tags());
        if (damage <= 0 && crit <= 0) {
            return profile;
        }
        final List<Modifier> extra = new ArrayList<>(2);
        if (damage > 0) {
            extra.add(Modifier.increased(StatKeys.DAMAGE, damage * pierced / 100.0, "穿透過 " + pierced + " 次"));
        }
        if (crit > 0) {
            extra.add(Modifier.increased(StatKeys.CRIT_CHANCE, crit * pierced / 100.0, "穿透過 " + pierced + " 次"));
        }
        return profile.withLocal(extra);
    }

    /** 這一發穿透了 pierced 次，要不要折返（穿雲箭袋：攻擊的投射物穿透至少 x 次）。 */
    public static boolean returnsAfter(final StatSheet owner, final Set<String> tags, final int pierced) {
        final double after = owner == null ? 0 : owner.flat(UniqueStats.PIERCE_RETURN_AFTER, tags);
        return after > 0 && pierced == (int) Math.round(after);
    }

    /** 分裂出來的投射物要不要穿透所有目標（裂穹）。 */
    public static boolean pierceAfterFork(final StatSheet owner, final Set<String> tags) {
        return owner != null && owner.flat(UniqueStats.PIERCE_AFTER_FORK, tags) > 0;
    }

    // ---- 事件 ----

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onHit(final HitEvent event) {
        final HitResult result = event.result();
        if (!(event.attacker() instanceof Player player) || event.defender() instanceof Player || !result.hit() || result.blocked()
                || event.skill() == null || plugin.monsters() == null || plugin.monsters().data(event.defender()).isEmpty()) {
            return;
        }
        final StatSheet sheet = event.skill().effective(plugin.players().sheet(player));
        final Set<String> tags = event.skill().tags();
        final UUID target = event.defender().getUniqueId();
        final long now = now();
        if (event.skill().isAttack() && sheet.flat(UniqueStats.CRUSH_ON_HIT, tags) > 0) {
            debuffs.apply(target, new Debuffs.Debuff(CRUSH_ID, player.getUniqueId(), now + CRUSH_TICKS, 0, false, null,
                    List.of(Modifier.flat(UniqueStats.CRUSHED, CRUSH_REDUCTION, "壓碎")), 0));
        }
        if (sheet.flat(UniqueStats.ABYSSAL_WASTING_ON_HIT, tags) > 0) {
            debuffs.apply(target, new Debuffs.Debuff(WASTING_ID, player.getUniqueId(), now + WASTING_TICKS, 0, false, null, List.of(), 0));
        }
        final double aggravate = sheet.flat(UniqueStats.BLEED_AGGRAVATE_CHANCE, tags);
        if (aggravate > 0 && plugin.ailments() != null && plugin.items().random().nextDouble() * 100 < aggravate) {
            plugin.ailments().aggravateBleeds(event.defender(), player.getUniqueId());
        }
        // 2026-10-27（雷頌墜）：暴擊時觸發落雷
        if (result.crit() && StatKeys.flag(sheet, UniqueStats.TRIGGER_CRIT_LIGHTNING_BOLT)) {
            trigger(player, "lightning_bolt", event.defender().getLocation(), now);
        }
    }

    /** 傳奇觸發的技能：同一種每 {@link UniqueStats#TRIGGER_COOLDOWN_TICKS} tick 最多一次（觸發出來的技能再暴擊不會無限連鎖）。 */
    private void trigger(final Player player, final String skillId, final Location at, final long now) {
        if (plugin.skills() == null) {
            return;
        }
        final String key = player.getUniqueId() + ":" + skillId;
        if (triggerReady.getOrDefault(key, 0L) > now) {
            return;
        }
        triggerReady.put(key, now + UniqueStats.TRIGGER_COOLDOWN_TICKS);
        plugin.skills().triggerSkill(player, skillId, at);
    }

    /** 2026-10-27：你格擋了一下（降伏塔盾：回復生命；厄門塔盾：生命低於斷命門檻的攻擊者直接倒下）。 */
    @EventHandler(priority = EventPriority.MONITOR)
    public void onBlocked(final HitEvent event) {
        if (!(event.defender() instanceof Player player) || !event.result().blocked()) {
            return;
        }
        final StatSheet sheet = plugin.players().sheet(player);
        final double recover = sheet.flat(UniqueStats.BLOCK_RECOVER_LIFE, Set.of());
        final PlayerState state = plugin.players().find(player.getUniqueId()).orElse(null);
        if (recover > 0 && state != null && state.hasCharacter() && !state.isDead() && !state.ruined(now()) && state.life() < state.maxLife()) {
            state.setLife(Math.min(state.maxLife(), state.life() + state.maxLife() * recover / 100.0));
            plugin.players().display(player, state);
        }
        final LivingEntity attacker = event.attacker();
        if (StatKeys.flag(sheet, UniqueStats.BLOCK_CULL) && attacker != null && !(attacker instanceof Player) && attacker.isValid()
                && !attacker.isDead() && plugin.monsters() != null) {
            final var data = plugin.monsters().data(attacker);
            final org.bukkit.attribute.AttributeInstance max = attacker.getAttribute(org.bukkit.attribute.Attribute.MAX_HEALTH);
            if (data.isPresent() && max != null && max.getValue() > 0 && (plugin.maps() == null || plugin.maps().phaseFloor(attacker) <= 0)
                    && attacker.getHealth() <= max.getValue() * tw.exilecore.combat.CombatListener.cullThreshold(data.get())) {
                attacker.setKiller(player);
                attacker.setHealth(0);
            }
        }
    }

    /** 堞衛脛甲：你的擊退把敵人拉向你。 */
    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void onKnockback(final EntityKnockbackEvent event) {
        if (event.getEntity() instanceof Player || !(event instanceof EntityPushedByEntityAttackEvent pushed)) {
            return;
        }
        final Entity pusher = pushed.getPushedBy();
        if (!(pusher instanceof Player player) || !StatKeys.flag(plugin.players().sheet(player), UniqueStats.KNOCKBACK_REVERSED)) {
            return;
        }
        final Vector knockback = event.getKnockback();
        event.setKnockback(new Vector(-knockback.getX(), knockback.getY(), -knockback.getZ()));
    }

    /** 擊殺：替弩技能裝填、碎裂（不留屍體）。在異常清掉之前看（LOW）。 */
    @EventHandler(priority = EventPriority.LOW)
    public void onDeath(final EntityDeathEvent event) {
        final LivingEntity victim = event.getEntity();
        final Player killer = victim.getKiller();
        if (killer == null || victim instanceof Player) {
            return;
        }
        final StatSheet sheet = plugin.players().sheet(killer);
        // 2026-10-27（縛地長杖）：殺死感電的敵人時觸發游雷（在異常清掉之前看）
        if (StatKeys.flag(sheet, UniqueStats.TRIGGER_KILL_SHOCKED_SPARK) && plugin.ailments() != null
                && plugin.ailments().has(victim, AilmentType.SHOCK)) {
            trigger(killer, "spark", victim.getLocation(), now());
        }
        // 2026-10-27（喪鈴法器）：擊殺時 x% 機率讓能量護盾立刻開始回充
        final double recharge = sheet.flat(UniqueStats.ES_RECHARGE_ON_KILL, Set.of());
        if (recharge > 0 && plugin.items().random().nextDouble() * 100 < recharge) {
            plugin.players().find(killer.getUniqueId()).ifPresent(state ->
                    state.setLastDamageTick(Math.min(state.lastDamageTick(), now() - tw.exilecore.character.PlayerStateService.esRechargeDelay(sheet))));
        }
        final double load = sheet.flat(UniqueStats.CROSSBOW_LOAD_ON_KILL, Set.of());
        if (load > 0 && plugin.skills() != null && plugin.items().random().nextDouble() * 100 < load) {
            plugin.skills().loadBolt(killer);
        }
        final AilmentService ailments = plugin.ailments();
        final boolean shatter = ailments != null
                && (StatKeys.flag(sheet, UniqueStats.SHATTER_BROKEN) && ailments.armourBroken(victim) == Double.POSITIVE_INFINITY
                || StatKeys.flag(sheet, UniqueStats.SHATTER_CHILLED) && ailments.has(victim, AilmentType.CHILL));
        if (shatter && corpses != null) {
            final Location at = victim.getLocation();
            at.getWorld().spawnParticle(Particle.BLOCK, at.clone().add(0, 0.8, 0), 20, 0.3, 0.4, 0.3, 0.1,
                    org.bukkit.Material.ICE.createBlockData());
            // 屍體在死亡處理裡才放：等兩 tick 再拿掉
            plugin.getServer().getScheduler().runTaskLater(plugin, () -> {
                final Corpses.Corpse corpse = corpses.nearest(at.getWorld().getUID(), at.getX(), at.getZ(), 1.0, now());
                if (corpse != null) {
                    corpses.remove(corpse);
                }
            }, 2);
        }
    }

    @EventHandler
    public void onQuit(final PlayerQuitEvent event) {
        final UUID uuid = event.getPlayer().getUniqueId();
        nextShrine.remove(uuid);
        patches.remove(uuid);
        lastPatch.remove(uuid);
        lastStep.remove(uuid);
        travelled.remove(uuid);
        undyingUntil.remove(uuid);
        nextCorpse.remove(uuid);
        triggerReady.keySet().removeIf(key -> key.startsWith(uuid + ":"));
    }

    // ---- 藥劑、召喚物 ----

    /** 喝了生命藥劑（FlaskService 呼叫）：骸骨束帶的召喚物也回復、藥劑效果中不會死。 */
    public void lifeFlaskUsed(final Player player, final StatSheet sheet, final double recovery, final int ticks) {
        if (StatKeys.flag(sheet, UniqueStats.LIFE_FLASK_MINIONS) && plugin.skills() != null && plugin.skills().minions() != null) {
            plugin.skills().minions().healOwned(player, recovery);
        }
        if (StatKeys.flag(sheet, UniqueStats.MINION_UNDYING_FLASK)) {
            undyingUntil.merge(player.getUniqueId(), now() + ticks, Math::max);
        }
    }

    /** 召喚物挨打（原版血量）：主人的生命藥劑效果中，這一下最多打到剩一點血。 */
    public double minionDamage(final LivingEntity minion, final Player owner, final double vanillaDamage) {
        if (owner == null || undyingUntil.getOrDefault(owner.getUniqueId(), 0L) <= now()) {
            return vanillaDamage;
        }
        return Math.max(0, Math.min(vanillaDamage, minion.getHealth() - 0.5));
    }

    // ---- 每半秒 ----

    public void tick() {
        final long now = now();
        for (final Player player : plugin.getServer().getOnlinePlayers()) {
            final PlayerState state = plugin.players().find(player.getUniqueId()).orElse(null);
            if (state == null || !state.hasCharacter() || state.isDead() || player.isDead()) {
                continue;
            }
            final StatSheet sheet = plugin.players().sheet(player);
            shrine(player, sheet, now);
            igniteSpread(player, sheet, now);
            ground(player, state, sheet, now);
            decompose(player, sheet);
            forever(player, sheet, now);
            witherWhileIgnited(player, sheet, now);
            lifeLoss(player, state, sheet, now);
            devour(player, state, sheet, now);
        }
        triggerReady.values().removeIf(until -> until < now);
        groundCooldown.values().removeIf(until -> until < now);
        spreadSince.values().removeIf(since -> now - since > 200);
        undyingUntil.values().removeIf(until -> until < now);
    }

    /** 隕砧：每 x 秒一個隨機的神龕增益，20 秒。 */
    private void shrine(final Player player, final StatSheet sheet, final long now) {
        final double interval = sheet.flat(UniqueStats.SHRINE_PERIODIC, Set.of());
        if (interval <= 0 || plugin.skills() == null || plugin.maps() == null) {
            nextShrine.remove(player.getUniqueId());
            return;
        }
        final Long next = nextShrine.get(player.getUniqueId());
        if (next == null) {
            nextShrine.put(player.getUniqueId(), now + Math.round(interval * 20));
            return;
        }
        if (now < next) {
            return;
        }
        nextShrine.put(player.getUniqueId(), now + Math.round(interval * 20));
        final List<tw.exilecore.map.MapEventConfig.Shrine> shrines = plugin.maps().eventConfig().shrines();
        if (shrines.isEmpty()) {
            return;
        }
        final tw.exilecore.map.MapEventConfig.Shrine shrine = shrines.get(plugin.items().random().nextInt(shrines.size()));
        plugin.skills().buff(player, "unique.shrine." + shrine.id(), shrine.name(), (int) (UniqueStats.SHRINE_PERIODIC_SECONDS * 20), shrine.mods());
    }

    /** 火葬長杖、野焰之戒：你點燃的敵人旁邊（x/10 格內）待滿 1 秒的敵人也被點燃。 */
    private void igniteSpread(final Player player, final StatSheet sheet, final long now) {
        final double radius = sheet.flat(UniqueStats.IGNITE_SPREAD, Set.of()) / 10.0;
        final AilmentService ailments = plugin.ailments();
        if (radius <= 0 || ailments == null || plugin.monsters() == null) {
            return;
        }
        for (final LivingEntity burning : player.getWorld().getNearbyLivingEntities(player.getLocation(), 24)) {
            if (burning instanceof Player || plugin.monsters().data(burning).isEmpty()) {
                continue;
            }
            final AilmentState.Ignite ignite = ailments.ignite(burning);
            if (ignite == null || !player.getUniqueId().equals(ignite.source())) {
                continue;
            }
            for (final LivingEntity near : burning.getWorld().getNearbyLivingEntities(burning.getLocation(), radius)) {
                if (near == burning || near instanceof Player || plugin.monsters().data(near).isEmpty() || ailments.ignite(near) != null) {
                    continue;
                }
                final long since = spreadSince.computeIfAbsent(near.getUniqueId(), uuid -> now);
                if (now - since >= 20) {
                    ailments.copyIgnite(near, ignite);
                    spreadSince.remove(near.getUniqueId());
                }
            }
        }
    }

    /** 行灰之履：移動時每走一格在腳下留一塊燃燒地面（8 秒），站在上面的敵人被點燃（視同你最大生命 x% 的火焰傷害；每隻一秒一次）。 */
    private void ground(final Player player, final PlayerState state, final StatSheet sheet, final long now) {
        final double percent = sheet.flat(UniqueStats.GROUND_IGNITE_MOVING, Set.of());
        final UUID uuid = player.getUniqueId();
        final List<Patch> mine = patches.get(uuid);
        if (percent <= 0 && mine == null) {
            return;
        }
        final List<Patch> list = mine == null ? patches.computeIfAbsent(uuid, key -> new ArrayList<>()) : mine;
        list.removeIf(patch -> patch.until() <= now || !patch.at().getWorld().equals(player.getWorld()));
        if (percent > 0 && player.isOnGround()) {
            final Location at = player.getLocation();
            final Location last = lastPatch.get(uuid);
            if (last == null || !last.getWorld().equals(at.getWorld()) || last.distanceSquared(at) >= GROUND_STEP * GROUND_STEP) {
                lastPatch.put(uuid, at.clone());
                if (last != null) { // 剛開始（站著不動）不放
                    list.add(new Patch(at.clone(), now + GROUND_TICKS));
                    while (list.size() > GROUND_MAX) {
                        list.removeFirst();
                    }
                }
            }
        }
        if (list.isEmpty()) {
            patches.remove(uuid);
            return;
        }
        final AilmentService ailments = plugin.ailments();
        final double base = state.maxLife() * Math.max(0, percent) / 100.0;
        for (final Patch patch : list) {
            patch.at().getWorld().spawnParticle(Particle.FLAME, patch.at().clone().add(0, 0.1, 0), 2, 0.4, 0.02, 0.4, 0.0);
            if (ailments == null || base <= 0) {
                continue;
            }
            for (final LivingEntity target : patch.at().getWorld().getNearbyLivingEntities(patch.at(), GROUND_RADIUS)) {
                if (target instanceof Player || plugin.monsters() == null || plugin.monsters().data(target).isEmpty()
                        || groundCooldown.getOrDefault(target.getUniqueId(), 0L) > now) {
                    continue;
                }
                groundCooldown.put(target.getUniqueId(), now + 20);
                ailments.groundIgnite(player, target, base, sheet); // 2026-10-27 照 PoE2 Ignited Ground：疊易燃到 50% 才點燃；這次之前踩到就點燃
            }
        }
    }

    /** 踏骸之履：每移動 x/10 格觸發一次潰骸。 */
    private void decompose(final Player player, final StatSheet sheet) {
        final double step = sheet.flat(UniqueStats.TRIGGER_DECOMPOSE_METRES, Set.of()) / 10.0;
        final UUID uuid = player.getUniqueId();
        if (step <= 0 || plugin.skills() == null) {
            lastStep.remove(uuid);
            travelled.remove(uuid);
            return;
        }
        final Location at = player.getLocation();
        final Location last = lastStep.put(uuid, at.clone());
        if (last == null || !last.getWorld().equals(at.getWorld())) {
            return;
        }
        final double moved = Math.sqrt(Math.pow(at.getX() - last.getX(), 2) + Math.pow(at.getZ() - last.getZ(), 2));
        if (moved > 12) {
            return; // 傳送
        }
        double total = travelled.getOrDefault(uuid, 0.0) + moved;
        int triggers = 0;
        while (total >= step && triggers < 2) {
            total -= step;
            triggers++;
            plugin.skills().triggerSkill(player, "decompose", at);
        }
        travelled.put(uuid, Math.min(total, step));
    }

    /** 2026-10-27（骸節鐵手）：每秒失去 x% 最大生命（每半秒扣一次，最少留 1 點）。 */
    private void lifeLoss(final Player player, final PlayerState state, final StatSheet sheet, final long now) {
        final double percent = sheet.flat(UniqueStats.LIFE_LOSS_PERCENT, Set.of());
        if (percent <= 0 || state.life() <= 1) {
            return;
        }
        state.setLife(Math.max(1, state.life() - state.maxLife() * percent / 100.0 * PERIOD / 20.0));
        plugin.players().display(player, state);
    }

    /** 2026-10-27（噬屍頭環）：每 3 秒消耗附近（臨在範圍）一具屍體，回復 x% 最大生命；滿血、禁療中不吃。 */
    private void devour(final Player player, final PlayerState state, final StatSheet sheet, final long now) {
        final double percent = sheet.flat(UniqueStats.CORPSE_CONSUME_HEAL, Set.of());
        final UUID uuid = player.getUniqueId();
        if (percent <= 0 || corpses == null) {
            nextCorpse.remove(uuid);
            return;
        }
        if (nextCorpse.getOrDefault(uuid, 0L) > now || state.life() >= state.maxLife() || state.ruined(now)) {
            return;
        }
        final Location at = player.getLocation();
        final Corpses.Corpse corpse = corpses.nearest(at.getWorld().getUID(), at.getX(), at.getZ(), tw.exilecore.combat.Presence.BASE_RADIUS, now);
        if (corpse == null) {
            return;
        }
        corpses.remove(corpse);
        nextCorpse.put(uuid, now + UniqueStats.CORPSE_CONSUME_TICKS);
        state.setLife(Math.min(state.maxLife(), state.life() + state.maxLife() * percent / 100.0));
        state.markRecent(tw.exilecore.stats.Recently.CORPSE, now);
        at.getWorld().spawnParticle(Particle.DAMAGE_INDICATOR, at.clone().add(0, 1, 0), 6, 0.3, 0.3, 0.3, 0.0);
        plugin.players().display(player, state);
    }

    /** 司祭法衣：你施加的詛咒、凋零沒有持續時間限制。 */
    private void forever(final Player player, final StatSheet sheet, final long now) {
        final boolean curses = StatKeys.flag(sheet, UniqueStats.CURSE_INFINITE);
        final boolean wither = StatKeys.flag(sheet, UniqueStats.WITHER_INFINITE);
        if (!curses && !wither) {
            return;
        }
        final UUID source = player.getUniqueId();
        for (final UUID target : List.copyOf(debuffs.targets())) {
            for (final Debuffs.Debuff debuff : debuffs.active(target, now)) {
                if (!source.equals(debuff.source()) || debuff.untilTick() >= FOREVER) {
                    continue;
                }
                // 2026-10-27：競技場裡對玩家不無限（跟昇華・不斷爭吵一樣，照原本持續時間）
                if (org.bukkit.Bukkit.getServer() != null && org.bukkit.Bukkit.getPlayer(target) != null) {
                    continue;
                }
                if (curses && debuff.id().contains("_curse") || wither && debuff.id().startsWith("withered.")) {
                    debuffs.apply(target, new Debuffs.Debuff(debuff.id(), debuff.source(), FOREVER, debuff.taken(), debuff.selfOnly(),
                            debuff.tag(), debuff.mods(), debuff.slow()));
                }
            }
        }
    }

    /**
     * 2026-10-28 照 PoE2 Blackflame（黯炎）：被點燃的敵人身上你施加的凋零不會失效。每半秒檢查一次：身上有點燃（誰點的都算，
     * 照原作「Ignited enemies」）的敵人，你的凋零往後延半秒（點燃期間暫停計時，點燃結束後照剩下的時間繼續倒數）。
     */
    private void witherWhileIgnited(final Player player, final StatSheet sheet, final long now) {
        final AilmentService ailments = plugin.ailments();
        if (ailments == null || !StatKeys.flag(sheet, UniqueStats.WITHERED_NO_EXPIRE_IGNITED)) {
            return;
        }
        final UUID source = player.getUniqueId();
        for (final UUID target : List.copyOf(debuffs.targets())) {
            if (!debuffs.hasWitheredFrom(target, source, now)) {
                continue;
            }
            if (plugin.getServer().getEntity(target) instanceof LivingEntity living && !(living instanceof Player) // 競技場裡對玩家不延（跟司祭法衣一樣）
                    && ailments.ignite(living) != null && source.equals(ailments.ignite(living).source())) { // 2026-10-28 站長：只算「被你點燃」的敵人
                debuffs.holdWithered(target, source, now, PERIOD);
            }
        }
    }

    /** 測試用：目前某個玩家有幾塊燃燒地面。 */
    int patchCount(final UUID player) {
        final List<Patch> list = patches.get(player);
        return list == null ? 0 : list.size();
    }

    /** 測試用：清掉所有狀態。 */
    void clear() {
        patches.clear();
        nextShrine.clear();
        spreadSince.clear();
        lastPatch.clear();
        groundCooldown.clear();
        lastStep.clear();
        travelled.clear();
        undyingUntil.clear();
        triggerReady.clear();
        nextCorpse.clear();
    }
}
