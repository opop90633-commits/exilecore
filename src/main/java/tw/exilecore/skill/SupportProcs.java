package tw.exilecore.skill;

import org.bukkit.Location;
import org.bukkit.entity.LivingEntity;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.entity.EntityDeathEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.util.Vector;
import tw.exilecore.ExileCore;
import tw.exilecore.ailment.AilmentType;
import tw.exilecore.character.PlayerState;
import tw.exilecore.character.SkillLoadout;
import tw.exilecore.stats.HitEvent;
import tw.exilecore.stats.Modifier;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * 輔助寶石觸發的專屬技能（2026-10，照 PoE2 的「Supported skills trigger X」）。
 * <p>
 * supports.yml 的修飾器寫 {@code { stat: proc.事件, type: FLAT, value: 機率%, tags: [隱藏技能代號] }}：被輔助的技能（技能面板上的）
 * 符合事件時，有這個機率在目標的位置放那個隱藏技能（用被輔助技能的寶石等級，不帶輔助，不花魔力）。
 * 事件：
 * <ul>
 *   <li>hit_ignited／hit_frozen／hit_shocked／hit_bleeding／hit_poisoned／hit_elemental：擊中身上有那種異常的敵人，用掉那個異常（hit_elemental：點燃、凍結、感電任一）</li>
 *   <li>heavy_stun：擊中暈眩中的敵人</li>
 *   <li>crit：暴擊時；vs_ignited／vs_frozen／vs_shocked／vs_chilled／vs_bleeding／vs_poisoned／vs_stunned：擊中身上有那種異常的敵人（不用掉）</li>
 *   <li>kill_ignited／kill_frozen／kill_shocked／kill_bleeding／kill_poisoned／kill_stunned：打死身上有那種異常的敵人</li>
 *   <li>use：放這個技能時（猛擊在面前、戰吼在腳下、詛咒在準心指的地方）</li>
 *   <li>hit：擊中時</li>
 *   <li>armour_broken：讓敵人完全破甲的那一下；stun_applied：讓敵人重暈的那一下；minion_death：被輔助的召喚技能叫出來的召喚物死掉（2026-10）</li>
 * </ul>
 * 同一個玩家的同一個隱藏技能至少間隔 {@value #COOLDOWN} tick；觸發出來的隱藏技能打中不會再觸發。
 */
public final class SupportProcs implements Listener {

    static final String PREFIX = "proc.";
    /** 銀月庇的增益代號（技能代號）與它落下的月光（隱藏技能）。 */
    static final String LUNAR_BLESSING = "lunar_blessing";
    static final String LUNAR_MOONBEAM = "lunar_moonbeam";
    /** 完美時機放開的那一下（技能描述的代號後面接這個；baseId 會把它去掉）。 */
    static final String PERFECT_SUFFIX = ".perfect";
    static final long COOLDOWN = 20; // 2026-10 平衡：0.5 → 1 秒
    /** 打死的記錄留多久（tick）。 */
    private static final long KILL_WINDOW = 20;

    private final ExileCore plugin;
    private final SkillService service;
    private final Map<UUID, Map<String, Long>> ready = new HashMap<>();
    /** 怪物 → 最後一下是誰用哪個技能打的、當時身上有哪些異常。 */
    private final Map<UUID, LastHit> lastHits = new HashMap<>();

    /** meta：2026-10-27 這一下是主要寶石觸發的法術（或它觸發出來的）打的——打死時觸發的技能也標成觸發的。 */
    private record LastHit(UUID player, String skillId, java.util.Set<AilmentType> ailments, long tick, boolean meta) {
    }

    SupportProcs(final ExileCore plugin, final SkillService service) {
        this.plugin = plugin;
        this.service = service;
    }

    private long now() {
        return plugin.getServer().getCurrentTick();
    }

    /** 2026-10-22：技能在面板上的位置；放在英靈圖騰、星咒圖騰、引星孔裡的技能用宿主那一格（宿主孔裡的輔助就是它的）。 */
    private static int positionOf(final PlayerState state, final String skillId) {
        final int position = state.skills().find(skillId);
        return position >= 0 ? position : state.skills().findInner(skillId);
    }

    /** 這個技能（面板上的）掛的輔助裡，事件符合的 proc 修飾器。 */
    private List<Modifier> procs(final Player player, final String skillId, final String event) {
        final PlayerState state = plugin.players().get(player);
        final int position = positionOf(state, skillId);
        if (position < 0) {
            return List.of();
        }
        final SkillLoadout.Entry entry = state.skills().skill(position);
        final List<Modifier> list = new java.util.ArrayList<>();
        for (final SupportDefinition support : service.supportsOf(entry)) {
            for (final Modifier modifier : support.modifiers()) {
                if (modifier.stat().equals(PREFIX + event) && !modifier.tags().isEmpty()) {
                    list.add(modifier);
                }
            }
        }
        // 2026-10：技能自己寫的 proc（碎脊擊的震波、血獵的血爆、穿雲箭與蓄勁一擊的完美時機…，照 PoE2）
        final SkillDefinition own = service.config().skill(skillId);
        if (own != null) {
            for (final Modifier modifier : own.modifiers()) {
                if (modifier.stat().equals(PREFIX + event) && !modifier.tags().isEmpty()) {
                    list.add(modifier);
                }
            }
        }
        return list;
    }

    private boolean hasFlag(final Player player, final String skillId, final String stat) {
        final PlayerState state = plugin.players().get(player);
        final int position = positionOf(state, skillId);
        if (position < 0) {
            return false;
        }
        for (final SupportDefinition support : service.supportsOf(state.skills().skill(position))) {
            for (final Modifier modifier : support.modifiers()) {
                if (modifier.stat().equals(stat) && modifier.value() > 0) {
                    return true;
                }
            }
        }
        return false;
    }

    /** 其他模組回報的事件（SkillService.procEvent）。 */
    void event(final Player player, final String skillId, final String event, final Location at, final LivingEntity target) {
        final String id = baseId(skillId);
        final Location where = at != null ? at : target != null ? target.getLocation() : player.getLocation();
        fireAll(player, id, event, where, target, null);
    }

    /** 最後一下打這隻怪的是不是這個玩家；是的話回傳用的技能。 */
    String lastSkill(final LivingEntity entity, final Player player) {
        final LastHit hit = lastHits.get(entity.getUniqueId());
        return hit == null || !hit.player().equals(player.getUniqueId()) || now() - hit.tick() > KILL_WINDOW ? null : hit.skillId();
    }

    /** 這個技能掛的輔助裡某個屬性的 FLAT 加總。 */
    double supportFlat(final Player player, final String skillId, final String stat) {
        final PlayerState state = plugin.players().get(player);
        final int position = positionOf(state, baseId(skillId));
        if (position < 0) {
            return 0;
        }
        double sum = 0;
        for (final SupportDefinition support : service.supportsOf(state.skills().skill(position))) {
            for (final Modifier modifier : support.modifiers()) {
                if (modifier.stat().equals(stat) && modifier.type() == tw.exilecore.stats.ModType.FLAT) {
                    sum += modifier.value();
                }
            }
        }
        return sum;
    }

    static String baseId(final String profileId) {
        final int dot = profileId.indexOf('.');
        return dot < 0 ? profileId : profileId.substring(0, dot);
    }

    /** 這一下是主要寶石觸發的法術打的（2026-10）：它的輔助觸發出來的技能也標成觸發的，打出暴擊不會再幫暴擊引星充能。 */
    private boolean metaHit;

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onHit(final HitEvent event) {
        // 2026-10-17：存起來再還原（輔助觸發的技能同步打中又進來一次時，以前 finally 直接清成 false，外層後面的觸發就不算主要寶石的）
        final boolean previous = metaHit;
        metaHit = previous || event.skill() != null && event.skill().id().contains(MetaGems.TRIGGERED_SUFFIX);
        try {
            onHitInner(event);
        } finally {
            metaHit = previous;
        }
    }

    private void onHitInner(final HitEvent event) {
        if (!(event.attacker() instanceof Player player) || event.skill() == null || !event.result().hit()) {
            return;
        }
        final String skillId = baseId(event.skill().id());
        final LivingEntity target = event.defender();
        final java.util.Set<AilmentType> ailments = java.util.EnumSet.noneOf(AilmentType.class);
        if (plugin.ailments() != null) {
            for (final AilmentType type : AilmentType.values()) {
                if (plugin.ailments().has(target, type)) {
                    ailments.add(type);
                }
            }
        }
        lastHits.put(target.getUniqueId(), new LastHit(player.getUniqueId(), skillId, ailments, now(), metaHit));
        if (lastHits.size() > 4096) {
            lastHits.values().removeIf(hit -> now() - hit.tick() > KILL_WINDOW);
        }
        fireAll(player, skillId, "hit", target.getLocation(), target, null);
        // 銀月庇（2026-10，PoE2 的月光賜福）：增益期間狼人形態的攻擊打中時落下月光（每秒最多一次）
        if (event.skill().tags().contains(tw.exilecore.stats.Tags.WEREWOLF) && event.skill().tags().contains(tw.exilecore.stats.Tags.ATTACK)
                && service.buffs().has(player.getUniqueId(), LUNAR_BLESSING)) {
            if (castAt(player, LUNAR_MOONBEAM, LUNAR_BLESSING, target.getLocation())) {
                moonbeamChain(player, target); // 2026-10-27 照 PoE2：另外落在 6 格內 4～8 個敵人身上
            }
        }
        if (event.skill().id().endsWith(PERFECT_SUFFIX)) {
            // 2026-10-28 照 PoE2 Snipe：完美時機的箭直接打中凍結中的敵人時用掉凍結，改炸寒冰爆破（perfect_hit_frozen），不另外炸一般的爆炸；
            // 這次之前一律炸一般的（perfect_hit）
            if (ailments.contains(AilmentType.FREEZE) && fireAll(player, skillId, "perfect_hit_frozen", target.getLocation(), target, null)) {
                plugin.ailments().consume(target, AilmentType.FREEZE, player);
            } else {
                fireAll(player, skillId, "perfect_hit", target.getLocation(), target, null); // 2026-10：完美時機放開的那一下打中（穿雲箭的爆炸）
            }
        }
        if (event.result().crit()) {
            fireAll(player, skillId, "crit", target.getLocation(), target, null);
        }
        for (final AilmentType type : ailments) {
            fireAll(player, skillId, "vs_" + word(type), target.getLocation(), target, null); // 不用掉異常
        }
        for (final AilmentType type : List.of(AilmentType.IGNITE, AilmentType.FREEZE, AilmentType.SHOCK, AilmentType.BLEED, AilmentType.POISON)) {
            if (ailments.contains(type) && fireAll(player, skillId, "hit_" + word(type), target.getLocation(), target, null)) {
                plugin.ailments().consume(target, type, player); // 用掉那個異常（2026-10-27 無限循環：感電可能重新施加）
            }
        }
        for (final AilmentType type : List.of(AilmentType.IGNITE, AilmentType.FREEZE, AilmentType.SHOCK)) {
            if (ailments.contains(type) && fireAll(player, skillId, "hit_elemental", target.getLocation(), target, null)) {
                plugin.ailments().consume(target, type, player); // 2026-10-27 無限循環
                break;
            }
        }
        if (ailments.contains(AilmentType.STUN)) {
            fireAll(player, skillId, "heavy_stun", target.getLocation(), target, null);
            if (hasFlag(player, skillId, tw.exilecore.stats.StatKeys.FLAG_RELOAD_ON_STUN)) {
                service.reloadNow(player, skillId); // 震裝（2026-10）
            }
        }
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onDeath(final EntityDeathEvent event) {
        final LastHit hit = lastHits.remove(event.getEntity().getUniqueId());
        if (hit == null || now() - hit.tick() > KILL_WINDOW) {
            return;
        }
        final Player player = plugin.getServer().getPlayer(hit.player());
        if (player == null || !player.getWorld().equals(event.getEntity().getWorld())) {
            return;
        }
        // 2026-10-27：主要寶石觸發的法術打死的，觸發出來的技能也帶 .meta（不再幫主要寶石充能；這次之前打死時這個標記掉了，會自己餵自己）
        final boolean previous = metaHit;
        metaHit = previous || hit.meta();
        try {
            fireAll(player, hit.skillId(), "kill", event.getEntity().getLocation(), null, event.getEntity()); // 2026-10：打死（技能寫的 proc.kill）
            service.chargeEffects().onKill(player, hit.skillId(), event.getEntity()); // 2026-10-24：剔殘（技能自己的擊殺給戰星）
            for (final AilmentType type : hit.ailments()) {
                fireAll(player, hit.skillId(), "kill_" + word(type), event.getEntity().getLocation(), null, event.getEntity());
            }
        } finally {
            metaHit = previous;
        }
    }

    /** 放了一個技能（SkillService 呼叫）：猛擊在面前、戰吼在腳下、詛咒在準心指的地方。 */
    void onUse(final Player player, final SkillDefinition definition, final Location at) {
        onUse(player, definition, at, false);
    }

    /** 2026-10-27：triggered＝主要寶石觸發的法術放的（「放下時」觸發出來的技能也帶 .meta，不再幫主要寶石充能；這次之前沒標）。 */
    void onUse(final Player player, final SkillDefinition definition, final Location at, final boolean triggered) {
        final boolean previous = metaHit;
        metaHit = previous || triggered;
        try {
            fireAll(player, definition.id(), "use", at, null, null);
        } finally {
            metaHit = previous;
        }
        service.supportMechanics().extras().onUse(player, definition, at); // 2026-10-27 照 PoE2 Holy Descent（跳躍落地）
    }

    /**
     * 2026-10-26 照 PoE2：把隱藏技能的基礎傷害換成「這隻敵人最大生命的 percent%」（照技能原本的傷害種類比例分；之後照樣吃增加、更多）。
     * 找不到敵人的生命就照原本的。
     */
    SkillBuild withLifeDamage(final SkillBuild build, final LivingEntity source, final double percent) {
        if (source == null || plugin.monsters() == null) {
            return build;
        }
        final tw.exilecore.monster.MonsterData data = plugin.monsters().data(source).orElse(null);
        if (data == null) {
            return build;
        }
        final double life = plugin.monsters().sheet(source, data).get(tw.exilecore.stats.StatKeys.LIFE_MAX);
        final tw.exilecore.stats.SkillProfile base = build.profile();
        if (life <= 0 || base.spellDamage() == null || base.spellDamage().total() <= 0) {
            return build;
        }
        final double amount = life * percent / 100.0;
        final tw.exilecore.stats.DamagePacket shares = base.spellDamage();
        final tw.exilecore.stats.DamagePacket packet = new tw.exilecore.stats.DamagePacket();
        for (final tw.exilecore.stats.DamageType type : tw.exilecore.stats.DamageType.values()) {
            if (shares.get(type) > 0) {
                packet.set(type, amount * shares.get(type) / shares.total());
            }
        }
        // 法術的效用只乘附加傷害（基礎傷害不乘），照原本的
        return build.withProfile(new tw.exilecore.stats.SkillProfile(base.id(), base.tags(), base.effectiveness(), packet, new tw.exilecore.stats.DamagePacket(packet),
                base.conversion(), base.local()));
    }

    /** 放一個隱藏技能（等級照技能面板上 levelFrom 那個技能的寶石等級；沒放就 1 級）。 */
    boolean castAt(final Player player, final String subId, final String levelFrom, final Location at) {
        final SkillDefinition sub = service.config().skill(subId);
        if (sub == null) {
            return false;
        }
        final Map<String, Long> mine = ready.computeIfAbsent(player.getUniqueId(), uuid -> new HashMap<>());
        final long now = now();
        if (mine.getOrDefault(subId, 0L) > now) {
            return false;
        }
        mine.put(subId, now + (sub.cooldown() > 0 ? Math.max(1, Math.round(sub.cooldown() * 20)) : COOLDOWN)); // 2026-10-26：照觸發技能自己的冷卻
        castNow(player, sub, levelFrom, at);
        return true;
    }

    /** 2026-10-28：不看觸發冷卻直接放一次（回槍躍品質多的那一道震波：同一次觸發的第二道）。 */
    void castAtNow(final Player player, final String subId, final String levelFrom, final Location at) {
        final SkillDefinition sub = service.config().skill(subId);
        if (sub != null) {
            castNow(player, sub, levelFrom, at);
        }
    }

    /** 月光另外落下的範圍（格；PoE2 Moonbeam secondary radius 6 格）與每道之間隔幾 tick（200 毫秒）。 */
    static final double MOONBEAM_CHAIN_RANGE = 6;
    static final long MOONBEAM_CHAIN_DELAY = 4;

    /** 2026-10-27 照 PoE2（RePoE wolf_lunar_blessing_beam_attack_extra_beams_to_trigger）：月光另外落在幾個敵人身上（1–15 級 4、16–20 級 5、21–25 級 6、26–30 級 7、31 級起 8）。 */
    static int moonbeamExtraTargets(final int level) {
        return level <= 15 ? 4 : level <= 20 ? 5 : level <= 25 ? 6 : level <= 30 ? 7 : 8;
    }

    /** 2026-10-27 照 PoE2：月光打中那個敵人之後，再一道一道落在 6 格內最近的另外幾個敵人身上（不另外算冷卻）；這次之前只落一道。 */
    private void moonbeamChain(final Player player, final LivingEntity first) {
        final SkillDefinition sub = service.config().skill(LUNAR_MOONBEAM);
        if (sub == null) {
            return;
        }
        final PlayerState state = plugin.players().get(player);
        final int level = gemLevelOf(player, state, LUNAR_BLESSING);
        final Location center = first.getLocation();
        final List<LivingEntity> others = new java.util.ArrayList<>(service.targets().around(center, MOONBEAM_CHAIN_RANGE, player));
        others.removeIf(each -> each.getUniqueId().equals(first.getUniqueId()));
        others.sort(java.util.Comparator.comparingDouble(each -> each.getLocation().distanceSquared(center)));
        final int count = Math.min(others.size(), moonbeamExtraTargets(level));
        for (int i = 0; i < count; i++) {
            final LivingEntity next = others.get(i);
            plugin.getServer().getScheduler().runTaskLater(plugin, () -> {
                if (player.isOnline() && next.isValid() && !next.isDead() && next.getWorld().equals(player.getWorld())) {
                    castNow(player, sub, LUNAR_BLESSING, next.getLocation());
                }
            }, MOONBEAM_CHAIN_DELAY * (i + 1));
        }
    }

    /**
     * 2026-10-27 照站長：面板上這個技能的寶石能用的等級（屬性不夠時自動降到夠用的等級；沒放在面板上算 1）。
     * 這次之前觸發、分身直接用寶石本身的等級，屬性不夠的寶石觸發出來的還是高等級。
     */
    private int gemLevelOf(final Player player, final PlayerState state, final String skillId) {
        final int position = state.skills().find(skillId);
        return position < 0 ? 1 : GemAttributes.usableLevel(service.config(), state.skills().skill(position), plugin.players().sheet(player));
    }

    private void castNow(final Player player, final SkillDefinition sub, final String levelFrom, final Location at) {
        final PlayerState state = plugin.players().get(player);
        final int level = gemLevelOf(player, state, levelFrom);
        final SkillBuild build = StageAttacks.withShield(plugin, state, SkillBuild.of(service.config(), sub, level, state.level(), false, List.of(),
                state.weaponTags(), plugin.players().sheet(player))); // 2026-10-28 照 PoE2：熔岩噴射照盾的護甲、閃避附加火焰
        service.procCast(Origin.at(player, at.clone().add(0, 0.2, 0), SkillTargets.facing(player)), build, null);
    }

    /** 殘影（2026-10）：用 levelFrom 那個技能的等級叫出 subId 的分身（冷卻照 proc 的）。 */
    void decoy(final Player player, final String subId, final String levelFrom, final Location at) {
        final SkillDefinition sub = service.config().skill(subId);
        if (sub == null || sub.summon() == null) {
            return;
        }
        final Map<String, Long> mine = ready.computeIfAbsent(player.getUniqueId(), uuid -> new HashMap<>());
        final long now = now();
        if (mine.getOrDefault(subId, 0L) > now) {
            return;
        }
        mine.put(subId, now + Math.max(COOLDOWN, Math.round(sub.cooldown() * 20)));
        final PlayerState state = plugin.players().get(player);
        final int level = gemLevelOf(player, state, levelFrom);
        final SkillBuild build = SkillBuild.of(service.config(), sub, level, state.level(), false, List.of(), state.weaponTags(),
                plugin.players().sheet(player));
        service.minions().decoy(player, build, at);
    }

    @EventHandler
    public void onQuit(final PlayerQuitEvent event) {
        ready.remove(event.getPlayer().getUniqueId());
    }

    private static String word(final AilmentType type) {
        return switch (type) {
            case IGNITE -> "ignited";
            case FREEZE -> "frozen";
            case SHOCK -> "shocked";
            case BLEED -> "bleeding";
            case POISON -> "poisoned";
            case STUN -> "stunned";
            case CHILL -> "chilled";
            case ELECTROCUTE -> "electrocuted";
        };
    }

    /** 2026-10-28 照 PoE2：觸發的爆炸半徑 +x 格（FLAT；穿雲箭的品質，寫在原技能上）。 */
    static final String EXPLOSION_RADIUS_FLAT = "skill.explosion_radius";

    /**
     * 2026-10-28 照 PoE2：技能自己的隱藏技能（parent_damage: own，例如穿雲箭的爆炸）是同一顆寶石的一部分：
     * 傷害照原技能那一下（含輔助）乘 share，再加上隱藏技能自己的修飾器（全部轉冰冷、對傳奇更多…）；
     * 範圍照原技能（含輔助、品質）加隱藏技能自己的算，品質的「爆炸半徑 +x 格」加在基礎半徑上。
     * 這次之前只換傷害：隱藏技能自己的修飾器、原技能的範圍與品質都沒算到。
     */
    static SkillBuild ownSubBuild(final SkillBuild sub, final SkillBuild parent, final double share, final tw.exilecore.stats.StatSheet sheet) {
        final SkillDefinition definition = sub.definition();
        tw.exilecore.stats.SkillProfile profile = parent.profile().scaled(share, definition.name())
                .derive(definition.id(), definition.tags(), java.util.Set.of()).withLocal(sub.profile().local());
        if (!sub.profile().conversion().isEmpty()) { // 隱藏技能自己寫的 convert（震波全轉閃電…）取代原技能的
            profile = new tw.exilecore.stats.SkillProfile(profile.id(), profile.tags(), profile.effectiveness(), profile.spellDamage(),
                    profile.spellDamageMax(), sub.profile().conversion(), profile.local());
        }
        SkillBuild result = sub.withProfile(profile);
        if (!definition.tags().contains(tw.exilecore.stats.Tags.AREA)) {
            return result;
        }
        final tw.exilecore.stats.StatSheet effective = profile.effective(sheet);
        double scale = Math.sqrt(Math.max(0.05, effective.apply(tw.exilecore.stats.StatKeys.SKILL_AREA, 1.0, profile.tags())));
        final double baseRadius = definition.params().radius().at(sub.level());
        final double flat = effective.flat(EXPLOSION_RADIUS_FLAT, profile.tags());
        if (baseRadius > 0 && flat > 0) {
            scale *= (baseRadius + flat) / baseRadius;
        }
        return result.withAreaAndProjectiles(scale, result.projectiles());
    }

    /** 事件符合的 proc 各擲一次；有放出去回傳 true。 */
    private boolean fireAll(final Player player, final String skillId, final String event, final Location at, final LivingEntity target,
                            final LivingEntity exclude) {
        final List<Modifier> list = procs(player, skillId, event);
        if (list.isEmpty()) {
            return false;
        }
        final PlayerState state = plugin.players().get(player);
        final int position = positionOf(state, skillId);
        final SkillLoadout.Entry entry = state.skills().skill(position);
        final Map<String, Long> mine = ready.computeIfAbsent(player.getUniqueId(), uuid -> new HashMap<>());
        boolean fired = false;
        for (final Modifier modifier : list) {
            final String subId = modifier.tags().iterator().next();
            final long now = now();
            if (mine.getOrDefault(subId, 0L) > now || plugin.random().nextDouble() * 100 >= modifier.value()) {
                continue;
            }
            final SkillDefinition sub = service.config().skill(subId);
            if (sub == null) {
                continue;
            }
            // 2026-10-26（照 PoE2）：觸發的技能寫了自己的冷卻（磁流 0.2 秒、熔岩迸 0.4 秒、元素崩裂 1 秒…）就照它的；沒寫照舊 1 秒
            mine.put(subId, now + (sub.cooldown() > 0 ? Math.max(1, Math.round(sub.cooldown() * 20)) : COOLDOWN));
            plugin.players().conditions().onTriggered(player, now); // 2026-10-26：天賦樹「最近觸發過技能」
            final int level = GemAttributes.usableLevel(service.config(), entry, plugin.players().sheet(player)); // 2026-10-27：屬性不足降級後的等級
            SkillBuild build = SkillBuild.of(service.config(), sub, level, state.level(), false, List.of(),
                    state.weaponTags(), plugin.players().sheet(player));
            // 2026-10（照 PoE2 的餘震）：寫了 parent_damage 的照觸發它的那一下出傷害（含輔助），不然慢又重的猛擊（千鈞砸）掛了等於沒掛
            final SkillDefinition parent = service.config().skill(skillId);
            final double share = service.config().parentShare(subId, parent, level); // 2026-10-26：parent_damage: own 照自己的表換算
            if (share > 0 && parent != null) {
                final SkillBuild parentBuild = service.build(state, plugin.players().sheet(player), parent, entry);
                if (service.config().parentDamage(subId) == SkillConfig.PARENT_OWN_TABLE) {
                    build = ownSubBuild(build, parentBuild, share, plugin.players().sheet(player));
                } else {
                    build = build.withProfile(parentBuild.profile().scaled(share, sub.name()).derive(sub.id(), sub.tags(), java.util.Set.of()));
                }
            }
            if (metaHit) {
                build = build.withProfile(build.profile().derive(build.profile().id() + MetaGems.TRIGGERED_SUFFIX, java.util.Set.of(), java.util.Set.of()));
            }
            // 2026-10-26 照 PoE2：傷害照那隻敵人的最大生命（焚亡爆：點燃中死掉的 10%；寒殞片：凍結中死掉的 10%；碎骨爆：被定身死掉的 25%）
            final double lifePercent = service.config().lifeDamage(subId);
            if (lifePercent > 0) {
                build = withLifeDamage(build, exclude != null ? exclude : target, lifePercent);
            }
            LivingEntity aim = target;
            if (aim == null) {
                for (final LivingEntity candidate : service.targets().around(at, 8, player)) {
                    if (candidate != exclude) {
                        aim = candidate;
                        break;
                    }
                }
            }
            final Vector direction = aim != null ? SkillTargets.center(aim).toVector().subtract(at.toVector()) : SkillTargets.facing(player);
            final Location from = at.clone().add(0, 0.2, 0);
            service.procCast(Origin.at(player, from, direction.lengthSquared() < 1e-6 ? SkillTargets.facing(player) : direction.normalize()),
                    build, aim);
            fired = true;
        }
        return fired;
    }
}
