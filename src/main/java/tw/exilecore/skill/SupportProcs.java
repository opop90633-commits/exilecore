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
import tw.exilecore.stats.StatSheet;

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
 *   <li>kill_ignited／kill_frozen／kill_shocked／kill_bleeding／kill_poisoned／kill_stunned：打死身上有那種異常的敵人；kill_pinned：打死被定身的敵人（2026-11-01）</li>
 *   <li>use：放這個技能時（猛擊在面前、戰吼在腳下、詛咒在準心指的地方）</li>
 *   <li>hit：擊中時</li>
 *   <li>mark_activated：被輔助的印記被觸發、用掉時，在目標腳下（2026-10-31 照 PoE2 Charged Mark）</li>
 *   <li>armour_broken：讓敵人完全破甲的那一下；stun_applied：讓敵人重暈的那一下；minion_death：被輔助的召喚技能叫出來的召喚物死掉（2026-10）</li>
 *   <li>2026-11-01：shock：讓敵人感電的那一下；curse_applied：詛咒到每一個敵人（在牠身上，不吃冷卻）；projectile_end：每一發投射物飛到終點（不吃冷卻）</li>
 * </ul>
 * 同一個玩家的同一個隱藏技能照它自己的冷卻（skills.yml 的 cooldown；沒寫的至少間隔 {@value #COOLDOWN} tick，明寫 cooldown: 0 的沒有冷卻，2026-11-01 照 PoE2）；
 * 觸發出來的隱藏技能打中不會再觸發。
 */
public final class SupportProcs implements Listener {

    static final String PREFIX = "proc.";
    /** 銀月庇的增益代號（技能代號）與它落下的月光（隱藏技能）。 */
    static final String LUNAR_BLESSING = "silver_moon_shelter";
    static final String LUNAR_MOONBEAM = "lunar_moonbeam";
    /** 完美時機放開的那一下（技能描述的代號後面接這個；baseId 會把它去掉）。 */
    static final String PERFECT_SUFFIX = ".perfect";
    /**
     * 觸發技能沒寫 cooldown 時的預設冷卻（tick）。2026-10 平衡：0.5 → 1 秒。
     * 2026-11-01 照 PoE2：觸發技能只有資料寫的冷卻——PoB2 沒有冷卻的寫 cooldown: 0（{@link SkillConfig#noTriggerCooldown}，沒有冷卻）；
     * 還在吃這個預設的是 PoB2 裡沒有那個觸發技能資料的（霜渦、霜漫、眩嘯的觸發，見 skills.yml 的註解）。
     */
    static final long COOLDOWN = 20;

    /**
     * 2026-11-01 照 PoE2：觸發技能這一次要等幾 tick（純函式，測試用）。none（skills.yml 明寫 cooldown: 0）→ 0（沒有冷卻）；
     * 寫了冷卻的照它（至少 1 tick）；都沒寫照預設 {@link #COOLDOWN}。這次之前 0 跟沒寫一樣吃 1 秒。
     */
    static long triggerCooldownTicks(final double seconds, final boolean none) {
        if (none) {
            return 0;
        }
        return seconds > 0 ? Math.max(1, Math.round(seconds * 20)) : COOLDOWN;
    }

    /** 這個觸發技能的冷卻（tick；0 = 沒有）。 */
    private long triggerCooldownTicks(final SkillDefinition sub) {
        return triggerCooldownTicks(sub.cooldown(), service.config().noTriggerCooldown(sub.id()));
    }
    /** 打死的記錄留多久（tick）。 */
    private static final long KILL_WINDOW = 20;

    private final ExileCore plugin;
    private final SkillService service;
    private final Map<UUID, Map<String, Long>> ready = new HashMap<>();
    /** 怪物 → 最後一下是誰用哪個技能打的、當時身上有哪些異常。 */
    private final Map<UUID, LastHit> lastHits = new HashMap<>();

    /** meta：2026-10-27 這一下是主要寶石觸發的法術（或它觸發出來的）打的——打死時觸發的技能也標成觸發的。 */
    /** pinned：2026-11-01 照 PoE2 Bone Shrapnel：打中時牠被定身了沒（kill_pinned）。 */
    private record LastHit(UUID player, String skillId, java.util.Set<AilmentType> ailments, long tick, boolean meta, boolean pinned) {
    }

    SupportProcs(final ExileCore plugin, final SkillService service) {
        this.plugin = plugin;
        this.service = service;
        this.rifts = new ShockingRifts(plugin, service.targets()); // 2026-11-01 照 PoE2 Electromagnetism
        this.basis = new ProcBasis(plugin, service); // 2026-11-01 照 PoE2：噴炎柱、魔焰爆、播火爆、荊棘叢生、腐血嘯、刺爆針
    }

    /** 2026-11-01 照 PoE2：幾個觸發技能自己的傷害基礎與做法（{@link ProcBasis}）。 */
    private final ProcBasis basis;

    /** 2026-11-01 照 PoE2 Quill Burst：刺爆針（SupportExtras 在荊棘反彈之後的擊中呼叫）。 */
    boolean quillBurst(final Player player, final String levelFrom, final LivingEntity struck) {
        return basis.quillBurst(player, levelFrom, struck);
    }

    private long now() {
        return plugin.getServer().getCurrentTick();
    }

    /**
     * 2026-10-22：技能在面板上的位置；放在英靈圖騰、星咒圖騰、引星孔裡的技能用宿主那一格（宿主孔裡的輔助就是它的）。
     * 2026-10-28 照 PoE2（同一個技能可以放好幾顆）：主要寶石觸發的那一下（代號帶 .meta，或正在處理觸發的那一下打中）先找宿主孔裡的那顆，
     * 自己按的先找面板上的，輔助才不會用到另一顆的；這次之前一個技能只有一顆，一律先找面板上的。
     */
    private int positionOf(final PlayerState state, final String skillId) {
        return state.skills().locate(baseId(skillId), metaHit || skillId.contains(MetaGems.TRIGGERED_SUFFIX));
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

    private boolean markListening;

    /** 2026-10-31 照 PoE2 Charged Mark：印記被用掉時觸發（Debuffs 準備好之後登記一次）。 */
    private void listenMarks() {
        if (markListening || plugin.combat() == null || plugin.combat().debuffs() == null) {
            return;
        }
        plugin.combat().debuffs().onMarkConsumed(this::markConsumed);
        markListening = true;
    }

    private void markConsumed(final UUID target, final String markId, final UUID source, final long now) {
        if (source == null || markId == null) {
            return;
        }
        final Player player = plugin.getServer().getPlayer(source);
        final org.bukkit.entity.Entity entity = plugin.getServer().getEntity(target);
        if (player == null || !player.isOnline() || !(entity instanceof LivingEntity living) || !living.getWorld().equals(player.getWorld())) {
            return;
        }
        fireAll(player, baseId(markId), "mark_activated", living.getLocation(), living, null);
        service.supportMechanics().gloryOnMark(player); // 2026-11-07 照站長「瓦爾傳奇照原作」：奎波拉特的論述（印記啟動時隨機一個技能獲得榮耀）
    }

    /**
     * 2026-11-01 照 PoE2 Enervation（support_regenerate_mana_per_minute_on_shock：被輔助的技能讓敵人感電時，4 秒內每秒再生 x 魔力）：
     * 每次感電都套（沒有觸發冷卻，原作沒寫）；同一個增益重新套只刷新時間、不疊加（原作沒寫可以疊）。
     * 這次之前觸發隱藏技能 proc_enervation（觸發冷卻 1 秒）。
     */
    private void enervation(final Player player, final String id) {
        final double perSecond = supportFlat(player, id, tw.exilecore.stats.SupportExtraKeys.MANA_REGEN_ON_SHOCK);
        if (perSecond > 0) {
            service.buff(player, ENERVATION_BUFF, "吸電", (int) tw.exilecore.stats.SupportExtraKeys.MANA_REGEN_ON_SHOCK_TICKS,
                    enervationModifiers(perSecond));
        }
    }

    static final String ENERVATION_BUFF = "support.enervation";

    /** 吸電的增益：每秒再生 x 魔力（固定值）。純函式（測試用）。 */
    static List<Modifier> enervationModifiers(final double perSecond) {
        return List.of(Modifier.flat(tw.exilecore.stats.StatKeys.MANA_REGEN, perSecond, "吸電"));
    }

    /** 其他模組回報的事件（SkillService.procEvent）。 */
    void event(final Player player, final String skillId, final String event, final Location at, final LivingEntity target) {
        final String id = baseId(skillId);
        final Location where = at != null ? at : target != null ? target.getLocation() : player.getLocation();
        // 2026-10-28：主要寶石觸發的（代號帶 .meta）照宿主孔裡那顆的輔助（同一個技能面板上也可能有一顆）
        final boolean previous = metaHit;
        metaHit = previous || skillId.contains(MetaGems.TRIGGERED_SUFFIX);
        try {
            final boolean fired = fireAll(player, id, event, where, target, null);
            if ("stun_applied".equals(event)) {
                SupportRemakes.devastate(plugin, service, player, id, target); // 2026-11-01 照 PoE2 Devastate（崩勢）：重暈時完全破甲
            }
            if (SHOCK.equals(event) && target != null) {
                if (fired) {
                    // 2026-11-01 照 PoE2 Static Shocks：跟著那隻敵人走；每一波最多電 6 個（牠先），每一團各打各的
                    service.zones().attachStaticShock(player.getUniqueId(), STATIC_SHOCKS, target, tw.exilecore.stats.SupportSystemKeys.STATIC_SHOCK_MAX_TARGETS);
                }
                shockCluster(player, id, where, target);
                enervation(player, id);
            }
            // 2026-11-01 照 PoE2 Auto Reload：重暈敵人時自動裝填；這次之前是擊中暈眩中的敵人時（onHitInner）
            if ("stun_applied".equals(event) && hasFlag(player, id, tw.exilecore.stats.StatKeys.FLAG_RELOAD_ON_STUN)) {
                service.reloadNow(player, id);
            }
        } finally {
            metaHit = previous;
        }
    }

    // =====================================================================
    // 2026-11-01 照 PoE2 把幾顆近似的輔助改成跟原作一樣
    // =====================================================================

    /** 被輔助的技能讓敵人感電時（AilmentService；靜電 Static Shocks、磁暴 Electromagnetism）。 */
    static final String SHOCK = "shock";
    /**
     * 磁暴：讓敵人感電、而且 {@value #SHOCK_CLUSTER_RADIUS} 格內（含牠）至少 {@value #SHOCK_CLUSTER_COUNT} 隻不在雷隙裡的感電中敵人時，
     * 用掉 {@value #SHOCK_CLUSTER_RADIUS} 格內所有敵人的感電觸發雷隙（{@link ShockingRifts}）。
     */
    static final String SHOCK_CLUSTER = "shock_cluster";
    static final double SHOCK_CLUSTER_RADIUS = tw.exilecore.stats.SupportSystemKeys.RIFT_TRIGGER_RADIUS;
    static final int SHOCK_CLUSTER_COUNT = tw.exilecore.stats.SupportSystemKeys.RIFT_TRIGGER_COUNT;
    static final String STATIC_SHOCKS = "proc_static_orb";
    /** 2026-11-01 照 PoE2 Electromagnetism：雷隙的一般脈衝與最後一波（各自的傷害表）。 */
    static final String SHOCK_RIFT = "proc_shock_rift";
    static final String SHOCK_RIFT_FINAL = "proc_shock_rift_final";
    /** 2026-11-01 照 PoE2 Crater：落地時裂出的裂隙。 */
    static final String CRATER = "proc_crater";
    /** 震風（Wind Wave）：放被輔助的技能的那段時間你被暈眩（SupportExtras.playerStunned）。 */
    static final String SELF_STUNNED = "self_stunned";
    /** 咒爆（Impending Doom）：被輔助的詛咒時間到結束時，在每一隻還帶著它的敵人身上。 */
    static final String CURSE_EXPIRED = "curse_expired";
    // 2026-11-01 照 PoE2 DoomBlastPlayer：咒殞爆冷卻 0.15 秒、可以存 3～7 次（skills.yml 的 cooldown_uses 逐級表，StoredUses）；
    //   沒有存著的次數就不觸發。這次之前沒有可存次數，同一刻結束的好幾隻照冷卻一隻一隻排開炸（DOOM_SPACING 3 tick）
    /** 瘟潰（Bursting Plague）：帶著瘟疫的敵人死掉時（{@link PlagueBursts}）。 */
    static final String PLAGUE_DEATH = "plague_death";
    static final String PLAGUE_BURST = "proc_bursting_plague";

    private final PlagueBursts plague = new PlagueBursts();
    /** 2026-11-01 照 PoE2 storedUses：有可存次數的觸發技能（cooldown_uses）用這個，不用 {@link #ready} 的單一冷卻。 */
    private final StoredUses storedUses = new StoredUses();

    /** 2026-11-01 照 PoE2 Electromagnetism：正在開的雷隙用掉了幾個感電（fireAll 開雷隙時讀）。 */
    private int riftShocks;
    private final ShockingRifts rifts;

    /**
     * 磁暴（2026-11-01 照 PoE2 Electromagnetism，PoB2 try_consuming_shock_to_trigger_shocking_rift_in_radius_on_shocking_enemy 40／
     * consume_shock_of_X_enemies_to_trigger_shocking_rift 3）：讓敵人感電時，牠 4 格內（含牠）有至少 3 隻不在雷隙裡的感電中敵人，
     * 就用掉 4 格內所有敵人的感電，在牠身上開一道雷隙（每用掉一個感電脈衝速度增加 25%）。
     * 這次之前 40% 機率（把 40 公尺的半徑看成機率）、只用掉牠與最近另外 2 隻的感電。
     */
    private void shockCluster(final Player player, final String id, final Location where, final LivingEntity target) {
        if (plugin.ailments() == null || procs(player, id, SHOCK_CLUSTER).isEmpty() || !plugin.ailments().has(target, AilmentType.SHOCK)) {
            return;
        }
        final List<LivingEntity> shocked = new java.util.ArrayList<>(service.targets().around(target.getLocation(), SHOCK_CLUSTER_RADIUS, player));
        if (shocked.stream().noneMatch(each -> each.getUniqueId().equals(target.getUniqueId()))) {
            shocked.add(target);
        }
        shocked.removeIf(each -> !each.isValid() || each.isDead() || !plugin.ailments().has(each, AilmentType.SHOCK));
        int outside = 0;
        for (final LivingEntity each : shocked) {
            if (!rifts.inRift(each.getLocation())) {
                outside++;
            }
        }
        if (!tw.exilecore.stats.SupportSystemKeys.riftTriggers(outside)) {
            return;
        }
        final int previous = riftShocks;
        riftShocks = shocked.size();
        final boolean fired;
        try {
            fired = fireAll(player, id, SHOCK_CLUSTER, where, target, null);
        } finally {
            riftShocks = previous;
        }
        if (!fired) {
            return;
        }
        for (final LivingEntity each : shocked) {
            plugin.ailments().consume(each, AilmentType.SHOCK, player);
        }
    }

    /**
     * 咒爆：詛咒放上去了（SkillExecutor.applyCurseAt，每一隻敵人一次）。時間到前 1 tick 看牠身上還是不是同一個詛咒
     * （沒被重放、換掉、淨化、死掉），是的話在牠身上炸咒殞爆。持續時間無限的詛咒不會結束，不炸。
     */
    void curseApplied(final Player player, final String skillId, final LivingEntity target, final List<tw.exilecore.combat.Debuffs.Debuff> placed,
                      final long applied, final long until, final Object source) {
        if (player == null || target == null || placed.isEmpty() || until >= Long.MAX_VALUE / 8 || until - applied <= 1
                || procs(player, baseId(skillId), CURSE_EXPIRED).isEmpty()) {
            return;
        }
        final String id = baseId(skillId);
        final boolean meta = metaHit;
        final List<tw.exilecore.combat.Debuffs.Debuff> copy = List.copyOf(placed);
        // 2026-11-01 照站長：身上的詛咒被新的同一個詛咒蓋掉（再放一次、另一塊詛咒地面）也算這個詛咒結束，當下就炸；
        //   同一塊詛咒地面、同一個咒環自己刷新不算（source 一樣）。這次之前蓋掉的不炸（舊的那個到期檢查時已經不是同一個詛咒）
        if (overwritten(curseSources, player.getUniqueId() + ":" + id + ":" + target.getUniqueId(), source, applied, until)) {
            final boolean previous = metaHit;
            metaHit = previous || meta;
            try {
                fireAll(player, id, CURSE_EXPIRED, target.getLocation(), target, null);
            } finally {
                metaHit = previous;
            }
        }
        plugin.getServer().getScheduler().runTaskLater(plugin, () -> curseEnding(player, id, target, copy, meta), Math.max(1, until - applied - 1));
    }

    /** 每個（施放者、詛咒、敵人）最後一次是哪個來源放的、到哪個 tick（咒爆看「被蓋掉」）。 */
    private final Map<String, CurseSource> curseSources = new HashMap<>();

    record CurseSource(Object source, long until) {
    }

    /**
     * 這次放上去的詛咒是不是蓋掉了身上還沒結束、別的來源放的同一個詛咒（是的話要當下炸咒殞爆）。順便記下這次的來源。純函式（測試用）。
     */
    static boolean overwritten(final Map<String, CurseSource> sources, final String key, final Object source, final long now, final long until) {
        if (sources.size() > 4096) {
            sources.values().removeIf(each -> each.until() < now);
        }
        final CurseSource last = sources.put(key, new CurseSource(source, until));
        return last != null && last.until() > now && !java.util.Objects.equals(last.source(), source);
    }

    private void curseEnding(final Player player, final String id, final LivingEntity target, final List<tw.exilecore.combat.Debuffs.Debuff> placed,
                             final boolean meta) {
        if (!player.isOnline() || !target.isValid() || target.isDead() || !target.getWorld().equals(player.getWorld())
                || plugin.combat() == null || plugin.combat().debuffs() == null) {
            return;
        }
        final long now = now();
        if (!sameCurseStill(plugin.combat().debuffs().active(target.getUniqueId(), now), placed)) {
            return;
        }
        // 2026-11-01 照 PoE2：當下就炸，次數由咒殞爆的可存次數管（fireAll → StoredUses）；這次之前照冷卻排隊
        final boolean previous = metaHit;
        metaHit = previous || meta;
        try {
            fireAll(player, id, CURSE_EXPIRED, target.getLocation(), target, null);
        } finally {
            metaHit = previous;
        }
    }

    /** 身上還有放上去的那個詛咒（同一個代號、施放者、結束時間；效果被末日厄運放大過也算）。純函式（測試用）。 */
    static boolean sameCurseStill(final List<tw.exilecore.combat.Debuffs.Debuff> current, final List<tw.exilecore.combat.Debuffs.Debuff> placed) {
        for (final tw.exilecore.combat.Debuffs.Debuff original : placed) {
            for (final tw.exilecore.combat.Debuffs.Debuff now : current) {
                if (now.id().equals(original.id()) && java.util.Objects.equals(now.source(), original.source()) && now.untilTick() == original.untilTick()) {
                    return true;
                }
            }
        }
        return false;
    }

    /** 瘟潰：被輔助的技能讓敵人中毒了（AilmentService）。 */
    void poisonApplied(final Player player, final String skillId, final LivingEntity target, final double dps, final long untilTick) {
        final String id = baseId(skillId);
        if (procs(player, id, PLAGUE_DEATH).isEmpty()) {
            return;
        }
        plague.poisoned(target.getUniqueId(), player.getUniqueId(), id, dps, untilTick, now());
    }

    /** 瘟潰：中毒跳了一次（AilmentService，扣血之前）。 */
    void poisonTicked(final LivingEntity target, final UUID source, final double dps, final double seconds) {
        if (source != null) {
            plague.ticked(target.getUniqueId(), source, dps, seconds, now());
        }
    }

    /** 瘟潰：帶著瘟疫的敵人死掉了，每個存了瘟疫的玩家各炸一次（照那個技能的寶石等級）。 */
    private void plagueDeath(final LivingEntity dead) {
        final Map<UUID, PlagueBursts.Plague> plagued = plague.died(dead.getUniqueId());
        if (plagued == null) {
            return;
        }
        final double life = monsterLife(dead);
        if (life <= 0) {
            return;
        }
        final double cap = PlagueBursts.cap(life);
        for (final Map.Entry<UUID, PlagueBursts.Plague> entry : plagued.entrySet()) {
            final Player owner = plugin.getServer().getPlayer(entry.getKey());
            final double amount = Math.min(entry.getValue().amount, cap);
            if (owner == null || !owner.isOnline() || !owner.getWorld().equals(dead.getWorld()) || amount <= 0) {
                continue;
            }
            final double percent = amount / life * 100.0;
            final double radius = PlagueBursts.radiusScale(amount, cap);
            castAt(owner, PLAGUE_BURST, entry.getValue().skillId, dead.getLocation(), build -> {
                final SkillBuild damaged = withLifeDamage(build, dead, percent);
                return damaged.withAreaAndProjectiles(damaged.areaScale() * radius, damaged.projectiles());
            });
        }
    }

    /** 怪物的最大生命（不是怪物回傳 0）。 */
    private double monsterLife(final LivingEntity entity) {
        if (entity == null || plugin.monsters() == null) {
            return 0;
        }
        final tw.exilecore.monster.MonsterData data = plugin.monsters().data(entity).orElse(null);
        return data == null ? 0 : plugin.monsters().sheet(entity, data).get(tw.exilecore.stats.StatKeys.LIFE_MAX);
    }

    /** 最後一下打這隻怪的是不是這個玩家；是的話回傳用的技能。 */
    String lastSkill(final LivingEntity entity, final Player player) {
        final LastHit hit = lastHits.get(entity.getUniqueId());
        return hit == null || !hit.player().equals(player.getUniqueId()) || now() - hit.tick() > KILL_WINDOW ? null : hit.skillId();
    }

    /** 這個技能掛的輔助裡某個屬性的 FLAT 加總。 */
    double supportFlat(final Player player, final String skillId, final String stat) {
        final PlayerState state = plugin.players().get(player);
        final int position = positionOf(state, skillId); // 2026-10-28：代號帶 .meta 的找宿主孔裡那顆（positionOf 自己去掉後綴）
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
        final boolean pinned = plugin.ailments() != null && plugin.ailments().pinned(target)
                || plugin.combat() != null && plugin.combat().debuffs() != null && plugin.combat().debuffs().has(target.getUniqueId(), "pin", now());
        lastHits.put(target.getUniqueId(), new LastHit(player.getUniqueId(), skillId, ailments, now(), metaHit, pinned));
        if (lastHits.size() > 4096) {
            lastHits.values().removeIf(hit -> now() - hit.tick() > KILL_WINDOW);
        }
        fireAll(player, skillId, "hit", target.getLocation(), target, null);
        // 銀月庇（2026-10，PoE2 的月光賜福）：增益期間狼人形態的攻擊打中時落下月光（每秒最多一次）
        // 2026-11-01 照 PoE2 Lunar Blessing（Werewolf Melee Attacks call down Moonbeams on Hit）：只有狼人的近戰攻擊；這次之前狼人的攻擊都算
        if (event.skill().tags().contains(tw.exilecore.stats.Tags.WEREWOLF) && event.skill().tags().contains(tw.exilecore.stats.Tags.ATTACK)
                && event.skill().tags().contains(tw.exilecore.stats.Tags.MELEE)
                && service.buffs().has(player.getUniqueId(), LUNAR_BLESSING)) {
            moonbeam(player, target);
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
            // 2026-11-01 照 PoE2 Auto Reload：震裝改成重暈敵人時裝填（event 的 stun_applied）；這次之前擊中暈眩中的敵人時
        }
        // 2026-11-01 照 PoE2 Dazzle（穩擊）：擊中時用掉眩暈（被眩暈的敵人不能閃避：flag.always_hit 帶 vs_dazed）
        if (hasFlag(player, skillId, tw.exilecore.stats.SupportParityKeys.FLAG_CONSUME_DAZE) && plugin.combat() != null
                && plugin.combat().debuffs() != null) {
            plugin.combat().debuffs().removeAll(target.getUniqueId(), "daze");
        }
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onDeath(final EntityDeathEvent event) {
        plagueDeath(event.getEntity()); // 2026-11-01 照 PoE2 Bursting Plague：不管是誰打死的，帶著瘟疫死掉就炸
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
            if (hit.pinned()) {
                fireAll(player, hit.skillId(), "kill_pinned", event.getEntity().getLocation(), null, event.getEntity()); // 2026-11-01 照 PoE2 Bone Shrapnel
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
        listenMarks();
        basis.used(player, definition.id()); // 2026-11-01 照 PoE2 Flame Pillar：每次使用最多 10 道火柱，重新算
        final boolean previous = metaHit;
        metaHit = previous || triggered;
        try {
            fireAll(player, definition.id(), "use", at, null, null);
            // 2026-11-01 照 PoE2 Crater：跳躍技能「落地時」在落點觸發——跳躍機制的這裡就是落地（SkillService.updateLeap），
            // 其他跳躍技能（往後翻、往前撲）等玩家真的落地；這次之前熔坑擊用 proc.use，不是跳躍機制的在放的地方觸發
            if (SupportGuards.jumping(definition)) {
                if (definition.mechanic() == Mechanic.LEAP && !triggered) {
                    landed(player, definition.id(), at, triggered);
                } else {
                    watchLanding(player, definition.id(), triggered);
                }
            }
        } finally {
            metaHit = previous;
        }
        service.supportMechanics().extras().onUse(player, definition, at); // 2026-10-27 照 PoE2 Holy Descent（跳躍落地）
    }

    /** 2026-11-01 照 PoE2 Crater：跳躍技能落地了（at 是落點）。 */
    private void landed(final Player player, final String skillId, final Location at, final boolean triggered) {
        final boolean previous = metaHit;
        metaHit = previous || triggered;
        try {
            fireAll(player, skillId, tw.exilecore.stats.SupportSystemKeys.LAND, at, null, null);
        } finally {
            metaHit = previous;
        }
    }

    /** 2026-11-01 照 PoE2 Crater：不是跳躍機制的跳躍技能，每 tick 看玩家落地了沒（{@link tw.exilecore.stats.SupportSystemKeys#landed}），落地時在腳下觸發。 */
    private void watchLanding(final Player player, final String skillId, final boolean triggered) {
        if (procs(player, skillId, tw.exilecore.stats.SupportSystemKeys.LAND).isEmpty()) {
            return;
        }
        final long start = now();
        final boolean[] airborne = {false};
        final org.bukkit.scheduler.BukkitTask[] task = new org.bukkit.scheduler.BukkitTask[1];
        task[0] = plugin.getServer().getScheduler().runTaskTimer(plugin, () -> {
            if (!player.isOnline() || player.isDead()) {
                task[0].cancel();
                return;
            }
            final boolean grounded = SkillService.grounded(player);
            airborne[0] |= !grounded;
            if (tw.exilecore.stats.SupportSystemKeys.landed(now() - start, grounded, airborne[0])) {
                task[0].cancel();
                landed(player, skillId, player.getLocation(), triggered);
            }
        }, 1L, 1L);
    }

    /**
     * 2026-11-01 照 PoE2 Crater（TriggeredCraterPlayer）：在落點往外裂出 count 道裂隙（長 1.5 格 × 範圍、6 秒、最多 9 道；
     * 出現時打一次：武器攻擊、傷害效益 35%、80% 物理轉火焰），平均分 360 度、第一道朝面向。每一道各打各的。
     */
    private void craterFissures(final Player player, final SkillBuild build, final Location at, final int count) {
        final SkillParams.Zone spec = build.params().zone();
        if (spec == null) {
            return;
        }
        final Vector facing = SkillTargets.facing(player);
        final double radius = build.radius();
        final tw.exilecore.stats.SkillProfile profile = build.profile().scaled(spec.damage() / 100.0, "裂隙");
        final int ticks = build.durationTicks(spec.duration());
        for (final double[] direction : tw.exilecore.stats.SupportSystemKeys.fissureDirections(facing.getX(), facing.getZ(), count)) {
            final List<Location> points = new java.util.ArrayList<>();
            for (final double along : tw.exilecore.stats.SupportSystemKeys.fissureSteps(build.length(), Math.max(0.4, radius))) {
                points.add(at.clone().add(direction[0] * along, 0.1, direction[1] * along));
            }
            // 出現時打一次（間隔 = 持續時間，同炎脈裂地）
            service.zones().addFissure(player, points, radius, profile, build.definition().visual(), ticks, Math.max(spec.interval(), ticks / 20.0 + 1),
                    build.definition().id(), spec.max());
        }
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
        final long ticks = triggerCooldownTicks(sub); // 2026-10-26：照觸發技能自己的冷卻；2026-11-01：cooldown: 0 的沒有冷卻
        if (ticks > 0) {
            mine.put(subId, now + ticks);
        }
        castNow(player, sub, levelFrom, at);
        return true;
    }

    /** 2026-10-30：同 castAt，放之前再調整這一下（招架橫掃用圓盾當武器）。 */
    boolean castAt(final Player player, final String subId, final String levelFrom, final Location at,
                   final java.util.function.UnaryOperator<SkillBuild> adjust) {
        final SkillDefinition sub = service.config().skill(subId);
        if (sub == null) {
            return false;
        }
        final Map<String, Long> mine = ready.computeIfAbsent(player.getUniqueId(), uuid -> new HashMap<>());
        final long now = now();
        if (mine.getOrDefault(subId, 0L) > now) {
            return false;
        }
        final long ticks = triggerCooldownTicks(sub); // 2026-11-01：cooldown: 0 的沒有冷卻
        if (ticks > 0) {
            mine.put(subId, now + ticks);
        }
        final PlayerState state = plugin.players().get(player);
        final SkillBuild build = withParentGround(player, state, levelFrom, adjust.apply(procBuild(sub,
                gemLevelOf(player, state, levelFrom), state, List.of(), plugin.players().sheet(player))));
        service.procCast(Origin.at(player, at.clone().add(0, 0.2, 0), SkillTargets.facing(player)), build, null);
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
    static final double MOONBEAM_CHAIN_RANGE = tw.exilecore.core.Units.metres(6); // 2026-10-29 照站長：1 公尺 = 1.5 格（Units），照 PoE2 公尺的常數也換算
    static final long MOONBEAM_CHAIN_DELAY = 4;

    /** 2026-10-27 照 PoE2（RePoE wolf_lunar_blessing_beam_attack_extra_beams_to_trigger）：月光另外落在幾個敵人身上（1–15 級 4、16–20 級 5、21–25 級 6、26–30 級 7、31 級起 8）。 */
    static int moonbeamExtraTargets(final int level) {
        return level <= 15 ? 4 : level <= 20 ? 5 : level <= 25 ? 6 : level <= 30 ? 7 : 8;
    }

    /** 2026-11-01 照 PoE2 Moonbeam（wolf_lunar_blessing_beam_attack_delay_ms 950）：月光打中後 0.95 秒才落下（tick）。 */
    static final long MOONBEAM_DELAY = 19;

    /**
     * 2026-11-01 照 PoE2 Moonbeam：打中時（月光冷卻 1 秒）先在那個敵人當下的位置亮起光柱，0.95 秒後落下；
     * 同時挑 6 格內最近的另外 4～8 個敵人，之後每 0.2 秒在牠們（挑的時候）的位置各落一道（不另外算冷卻）。
     * 這次之前打中當下就落下，另外幾道也是馬上一道一道落。
     */
    private void moonbeam(final Player player, final LivingEntity first) {
        final SkillDefinition sub = service.config().skill(LUNAR_MOONBEAM);
        if (sub == null) {
            return;
        }
        final Map<String, Long> mine = ready.computeIfAbsent(player.getUniqueId(), uuid -> new HashMap<>());
        final long now = now();
        if (mine.getOrDefault(LUNAR_MOONBEAM, 0L) > now) {
            return;
        }
        final long cooldown = triggerCooldownTicks(sub);
        if (cooldown > 0) {
            mine.put(LUNAR_MOONBEAM, now + cooldown);
        }
        final PlayerState state = plugin.players().get(player);
        final int level = gemLevelOf(player, state, LUNAR_BLESSING);
        final Location center = first.getLocation();
        final List<Location> spots = new java.util.ArrayList<>();
        spots.add(center.clone());
        final List<LivingEntity> others = new java.util.ArrayList<>(service.targets().around(center, MOONBEAM_CHAIN_RANGE, player));
        others.removeIf(each -> each.getUniqueId().equals(first.getUniqueId()));
        others.sort(java.util.Comparator.comparingDouble(each -> each.getLocation().distanceSquared(center)));
        final int count = Math.min(others.size(), moonbeamExtraTargets(level));
        for (int i = 0; i < count; i++) {
            spots.add(others.get(i).getLocation().clone());
        }
        for (int i = 0; i < spots.size(); i++) {
            final Location at = spots.get(i);
            final long delay = moonbeamLandsAfter(i);
            plugin.getServer().getScheduler().runTaskLater(plugin, () -> telegraph(at), Math.max(0, delay - MOONBEAM_DELAY));
            plugin.getServer().getScheduler().runTaskLater(plugin, () -> {
                if (player.isOnline() && at.getWorld() != null && at.getWorld().equals(player.getWorld())) {
                    castNow(player, sub, LUNAR_BLESSING, at);
                }
            }, delay);
        }
    }

    /** 2026-11-01 照 PoE2：第 index 道月光（0 = 打中的那個）打中後幾 tick 落下：0.95 秒，之後每道多 0.2 秒。 */
    static long moonbeamLandsAfter(final int index) {
        return MOONBEAM_DELAY + MOONBEAM_CHAIN_DELAY * Math.max(0, index);
    }

    /** 月光落下前的光柱（0.95 秒的預兆）。 */
    private static void telegraph(final Location at) {
        if (at.getWorld() == null) {
            return;
        }
        for (int y = 0; y < 6; y++) {
            Fx.spawn(at.getWorld(), org.bukkit.Particle.END_ROD, at.clone().add(0, 0.3 + y * 0.6, 0), 2, 0.05, 0.2, 0.05, 0);
        }
    }

    /**
     * 2026-10-27 照站長：面板上這個技能的寶石能用的等級（屬性不夠時自動降到夠用的等級；沒放在面板上算 1）。
     * 這次之前觸發、分身直接用寶石本身的等級，屬性不夠的寶石觸發出來的還是高等級。
     */
    private int gemLevelOf(final Player player, final PlayerState state, final String skillId) {
        final int position = state.skills().find(skillId);
        return position < 0 ? 1 : effectiveLevel(player, state, state.skills().skill(position));
    }

    /**
     * 2026-11-03 照站長＋PoE2（輔助送的技能用被輔助技能的等級）：被輔助技能實際施放的等級——寶石等級（屬性不足會降）＋裝備的「+技能等級」＋輔助加的等級。
     * 這次之前只看寶石本身的等級（最多 21），32 級的詛咒（寶石 20＋裝備 12）觸發的咒殞爆照 20 級算。
     */
    private int effectiveLevel(final Player player, final PlayerState state, final SkillLoadout.Entry entry) {
        if (entry == null) {
            return 1;
        }
        final StatSheet sheet = plugin.players().sheet(player);
        final SkillDefinition parent = service.config().skill(entry.skillId());
        if (parent == null) {
            return GemAttributes.usableLevel(service.config(), entry, sheet);
        }
        try {
            final SkillBuild built = service.build(state, sheet, parent, entry);
            return built == null ? GemAttributes.usableLevel(service.config(), entry, sheet) : built.level();
        } catch (final RuntimeException e) {
            return GemAttributes.usableLevel(service.config(), entry, sheet);
        }
    }

    /** 2026-11-03：照指定的等級做觸發技能的 build（不再被角色等級的上限壓、不再另外加「+技能等級」：等級已經是被輔助技能的實際等級）。 */
    private SkillBuild procBuild(final SkillDefinition sub, final int level, final PlayerState state, final List<SupportDefinition> inherited,
                                 final StatSheet sheet) {
        final Integer before = SkillBuild.PREVIEW_LEVEL.get();
        SkillBuild.PREVIEW_LEVEL.set(level);
        try {
            return SkillBuild.of(service.config(), sub, level, state.level(), false, inherited, state.weaponTags(), sheet);
        } finally {
            if (before == null) {
                SkillBuild.PREVIEW_LEVEL.remove();
            } else {
                SkillBuild.PREVIEW_LEVEL.set(before);
            }
        }
    }

    private void castNow(final Player player, final SkillDefinition sub, final String levelFrom, final Location at) {
        final PlayerState state = plugin.players().get(player);
        final int level = gemLevelOf(player, state, levelFrom);
        final SkillBuild build = withParentGround(player, state, levelFrom,
                StageAttacks.withShield(plugin, state, procBuild(sub, level, state, List.of(), plugin.players().sheet(player)))); // 2026-10-28 照 PoE2：熔岩噴射照盾的護甲、閃避附加火焰
        service.procCast(Origin.at(player, at.clone().add(0, 0.2, 0), SkillTargets.facing(player)), build, null);
    }

    /**
     * 2026-11-01 照 PoE2 Persistent Ground（久地）：技能自己留下的地面效果交給隱藏技能放的（引霆突、霆落矛品質的感電地面、霜牙刺的冰緩地面、
     * 雷遁的感電地面…），照面板上 parentId 那個技能的「地面效果持續時間增加」拉長。
     * 同日稍後照 PoE2（技能的輔助也作用在它自己觸發的部分）：原技能輔助的所有持續時間修飾器都帶過去（{@link SkillBuild#withParentDuration}）。
     * 那個技能沒掛持續時間的輔助（或不在面板上）原樣回傳。這次之前隱藏技能的地面效果吃不到原技能的輔助。
     */
    SkillBuild withParentGround(final Player player, final PlayerState state, final String parentId, final SkillBuild sub) {
        if (sub == null || parentId == null) {
            return sub;
        }
        final int position = state.skills().find(parentId);
        final SkillDefinition parent = position < 0 ? null : service.config().skill(parentId);
        if (parent == null) {
            return sub;
        }
        final SkillLoadout.Entry entry = state.skills().skill(position);
        // 2026-11-01 照 PoE2（技能自己的輔助也作用在它自己觸發的部分）：原技能生效的輔助的持續時間修飾器（技能效果持續時間增加、更多／更少，
        //   地面效果持續時間增加）全部帶給隱藏技能，跟它自己吃到的同一池重算（SkillBuild.withParentDuration）；
        //   這次之前只帶「地面效果持續時間增加」的比例（withGroundRatio），延時、短時這類輔助吃不到
        if (SkillBuild.parentDurationModifiers(service.supportsOf(entry), parent).isEmpty()) {
            return sub; // 沒掛持續時間的輔助：不用算原技能
        }
        final tw.exilecore.stats.StatSheet sheet = plugin.players().sheet(player);
        final List<Modifier> mods = SkillBuild.parentDurationModifiers(service.build(state, sheet, parent, entry).supports(), parent); // 只算真的生效的輔助
        return sub.withParentDuration(mods, sheet);
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
        final SkillBuild build = procBuild(sub, level, state, List.of(), plugin.players().sheet(player));
        service.minions().decoy(player, build, at);
    }

    @EventHandler
    public void onQuit(final PlayerQuitEvent event) {
        ready.remove(event.getPlayer().getUniqueId());
        rifts.forget(event.getPlayer().getUniqueId()); // 2026-11-01 照 PoE2 Electromagnetism
        plague.forgetOwner(event.getPlayer().getUniqueId());
        storedUses.forget(event.getPlayer().getUniqueId());
        basis.forget(event.getPlayer().getUniqueId());
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
            scale *= (baseRadius + tw.exilecore.core.Units.metres(flat)) / baseRadius; // 2026-10-29 照站長：1 公尺 = 1.5 格（Units），品質加的半徑（照 PoE2 公尺）也換算
        }
        return result.withAreaAndProjectiles(scale, result.projectiles());
    }

    /** 事件符合的 proc 各擲一次；有放出去回傳 true。 */
    /** 2026-11-01 照 PoE2：這個技能格上能輔助送出來的技能（sub）的輔助（{@link #grantedSupports(List, SkillDefinition, SkillDefinition)}）。 */
    private List<SupportDefinition> grantedSupports(final SkillLoadout.Entry entry, final SkillDefinition sub) {
        if (entry == null || sub == null) {
            return List.of();
        }
        return grantedSupports(service.supportsOf(entry), service.config().skill(entry.skillId()), sub);
    }

    /**
     * 純邏輯：掛在原技能上的輔助裡，送出來的技能吃得到的那些——輔助要插得進去（本體或別的輔助送的技能能輔助）、
     * 自己不送技能、原作能用在觸發技能上、而且能輔助送的這個技能（{@link SupportDefinition#supportsGranted}）。
     */
    static List<SupportDefinition> grantedSupports(final List<SupportDefinition> socketed, final SkillDefinition parent, final SkillDefinition sub) {
        if (socketed == null || socketed.isEmpty() || sub == null) {
            return List.of();
        }
        final List<String> ids = new java.util.ArrayList<>();
        for (final SupportDefinition support : socketed) {
            if (support != null) {
                ids.add(support.id());
            }
        }
        final List<SupportDefinition> out = new java.util.ArrayList<>();
        for (final SupportDefinition support : socketed) {
            if (support == null || !support.supportsGranted(sub)) {
                continue;
            }
            if (parent != null && !SupportDefinition.hostsSkill(parent) && support.socketRefusal(parent, ids) != null) {
                continue;
            }
            out.add(support);
        }
        return out;
    }

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
            // 2026-11-01 照 PoE2：「對每個被詛咒的目標」（朽咒）、「每一發飛到終點時」（撒蒺）這種每個各觸發一次，不吃觸發冷卻
            final boolean uncapped = tw.exilecore.stats.SupportRemakeKeys.UNCAPPED_EVENTS.contains(event);
            // 2026-11-01 照 PoE2 storedUses：觸發的技能寫了可存次數（cooldown_uses，例如咒殞爆）就照次數，不看單一冷卻
            final int level = effectiveLevel(player, state, entry); // 2026-11-03：被輔助技能的實際等級（含裝備＋等級）；2026-10-27：屬性不足降級後的等級
            final int uses = uncapped ? 1 : service.config().cooldownUses(subId, level);
            if (!uncapped && uses <= 1 && mine.getOrDefault(subId, 0L) > now) {
                continue;
            }
            // 2026-11-01 照 PoE2 Reverberate（沉響）：有餘震機率的（餘震、連震），攻擊時間每 0.25 秒餘震機率增加 x%
            final double chanceMore = tw.exilecore.stats.SupportRemakeKeys.AFTERSHOCK_PROCS.contains(subId)
                    ? aftershockChanceMore(player, skillId, state, entry) : 1.0;
            final double roll = plugin.random().nextDouble() * 100;
            // 2026-11-01 照 PoE2 Tremors（連震）：30% 多 1 次、15% 多 2 次、10% 多 3 次餘震（擲一次）；這次之前 45% 機率一次較大的
            final int times = tw.exilecore.stats.SupportRemakeKeys.TREMOR_PROC.equals(subId) ? SupportRemakes.tremors(roll, chanceMore)
                    : roll < modifier.value() * chanceMore ? 1 : 0;
            if (times <= 0) {
                continue;
            }
            final SkillDefinition sub = service.config().skill(subId);
            if (sub == null) {
                continue;
            }
            // 2026-11-01 照 PoE2 Static Shocks：一隻敵人身上最多附著一團靜電（已經有的不再觸發）
            if (STATIC_SHOCKS.equals(subId) && target != null && service.zones().attached(STATIC_SHOCKS, target.getUniqueId())) {
                continue;
            }
            // 2026-11-01 照 PoE2 Coursing Current（竄電）：從感電的敵人射向附近濕透的敵人，只會碰到濕透的；附近沒有濕透的就不觸發
            LivingEntity drenchedTarget = null;
            java.util.Set<UUID> notDrenched = null;
            if (tw.exilecore.stats.SupportRemakeKeys.COURSING_PROC.equals(subId)) {
                if (target == null) {
                    continue;
                }
                final double reach = Math.max(1, sub.params().range()); // 第一下（感電的敵人 → 濕透的敵人）最遠幾格
                drenchedTarget = SupportRemakes.nearestDrenched(plugin, service.targets(), player, target, reach, now);
                if (drenchedTarget == null) {
                    continue;
                }
                notDrenched = SupportRemakes.notDrenched(plugin, service.targets(), player, target, reach + 48, now); // 連鎖 6 次 × 6 格以內都算
            }
            // 2026-10-26（照 PoE2）：觸發的技能寫了自己的冷卻（磁流 0.2 秒、熔岩迸 0.4 秒、元素崩裂 1 秒…）就照它的；沒寫照舊 1 秒
            // 2026-11-01 照 PoE2：明寫 cooldown: 0 的（PoB2 沒有冷卻：雷隙、裂隙、餘震…）沒有冷卻；這次之前一律至少 1 秒
            final long ticks = uncapped ? 0 : triggerCooldownTicks(sub);
            if (ticks > 0) {
                if (uses > 1) {
                    if (!storedUses.tryUse(player.getUniqueId(), subId, uses, now, ticks)) {
                        continue; // 存的次數都用完了：這次不觸發（照 PoE2，不排隊）
                    }
                } else {
                    mine.put(subId, now + ticks);
                }
            }
            plugin.players().conditions().onTriggered(player, now); // 2026-10-26：天賦樹「最近觸發過技能」
            // 2026-11-01 照 PoE2（PoB2 SkillGrantedBySupport）：送出來的技能也吃同一顆寶石上能輔助它的其他輔助（咒殞爆吃附蝕、擴散…）；
            //   這次之前送的技能一顆輔助都不吃
            final List<SupportDefinition> inherited = grantedSupports(entry, sub);
            SkillBuild build = procBuild(sub, level, state, inherited, plugin.players().sheet(player));
            final List<Modifier> subOwnLocal = build.profile().local(); // 2026-11-01 撒蒺：鐵蒺自己的修飾器（致殘），換成原技能的傷害後再加回去
            // 2026-10（照 PoE2 的餘震）：寫了 parent_damage 的照觸發它的那一下出傷害（含輔助），不然慢又重的猛擊（千鈞砸）掛了等於沒掛
            final SkillDefinition parent = service.config().skill(skillId);
            final double share = service.config().parentShare(subId, parent, level); // 2026-10-26：parent_damage: own 照自己的表換算
            SkillBuild parentBuilt = null; // 2026-11-01：雙震的兩側餘震照原本那一下的範圍（TectonicSlams）
            if (share > 0 && parent != null) {
                final SkillBuild parentBuild = service.build(state, plugin.players().sheet(player), parent, entry);
                parentBuilt = parentBuild;
                if (service.config().parentDamage(subId) == SkillConfig.PARENT_OWN_TABLE) {
                    build = ownSubBuild(build, parentBuild, share, plugin.players().sheet(player));
                } else {
                    build = build.withProfile(parentBuild.profile().scaled(share, sub.name()).derive(sub.id(), sub.tags(), java.util.Set.of()));
                }
            }
            if (MaceSlams.BONESHATTER_WAVE.equals(subId) && parentBuilt != null) {
                // 2026-11-01 照 PoE2 Boneshatter：震波由傳奇敵人（含 Boss）的重暈造成時更多 50%；雙持時照碎脊擊更少 30%（原技能那一下是重新算的，沒有雙持）
                final tw.exilecore.monster.MonsterData data = target == null || plugin.monsters() == null ? null : plugin.monsters().data(target).orElse(null);
                final boolean unique = data != null && (data.boss() || data.rarity() == tw.exilecore.monster.MonsterRarity.UNIQUE);
                final double factor = MaceSlams.boneshatterWaveFactor(unique,
                        state.offHandAttack() != null ? SkillService.dualWieldDamagePercent(parentBuilt.profile()) : 0);
                build = build.withProfile(build.profile().scaled(factor, unique ? "傳奇的重暈" : "雙持"));
            }
            if (metaHit) {
                build = build.withProfile(build.profile().derive(build.profile().id() + MetaGems.TRIGGERED_SUFFIX, java.util.Set.of(), java.util.Set.of()));
            }
            // 2026-10-26 照 PoE2：傷害照那隻敵人的最大生命（焚亡爆：點燃中死掉的 10%；寒殞片：凍結中死掉的 10%；碎骨爆：被定身死掉的 25%）
            final double lifePercent = service.config().lifeDamage(subId);
            if (lifePercent > 0) {
                build = withLifeDamage(build, exclude != null ? exclude : target, lifePercent);
            }
            // 2026-11-01 照 PoE2：噴炎柱（剩下點燃的 25%）、魔焰爆（目前魔力的 25%）換傷害基礎；播火爆（扇形、傳點燃）、
            //   荊棘叢生（危險區域）、腐血嘯（腐化之血）自己放（ProcBasis）
            if (ProcBasis.handles(subId)) {
                final SkillBuild adjusted = basis.adjust(player, subId, skillId, build, target);
                if (adjusted == null) {
                    continue; // 沒有點燃、沒有魔力：不放
                }
                build = adjusted;
                // 腐血嘯打的是戰吼本身的範圍：照原技能（含輔助、品質）算
                final SkillBuild host = parentBuilt != null || !ProcBasis.CORRUPTING_CRY.equals(subId) || parent == null ? parentBuilt
                        : service.build(state, plugin.players().sheet(player), parent, entry);
                if (basis.custom(player, subId, skillId, build, subOwnLocal, host, at, target)) {
                    fired = true;
                    continue;
                }
            }
            if (CRATER.equals(subId)) {
                // 2026-11-01 照 PoE2 Crater：落點往外裂出 3 道裂隙（support_fissure_on_landing_create_X_fissures_on_impact）；這次之前燒出一個熔坑
                final int count = (int) Math.round(supportFlat(player, skillId, tw.exilecore.stats.SupportSystemKeys.CRATER_FISSURES));
                craterFissures(player, build, at, count > 0 ? count : tw.exilecore.stats.SupportSystemKeys.CRATER_DEFAULT_FISSURES);
                fired = true;
                continue;
            }
            if (SHOCK_RIFT.equals(subId) && target != null) {
                // 2026-11-01 照 PoE2 Electromagnetism：開一道會脈衝、吸人、阻礙的雷隙（ShockingRifts）；這次之前一次炸開的新星
                final SkillDefinition lastDefinition = service.config().skill(SHOCK_RIFT_FINAL);
                SkillBuild last = lastDefinition == null ? build : procBuild(lastDefinition, level, state, grantedSupports(entry, lastDefinition),
                        plugin.players().sheet(player));
                if (metaHit && lastDefinition != null) {
                    last = last.withProfile(last.profile().derive(last.profile().id() + MetaGems.TRIGGERED_SUFFIX, java.util.Set.of(), java.util.Set.of()));
                }
                rifts.open(player, build, last, target.getLocation(), Math.max(0, riftShocks));
                fired = true;
                continue;
            }
            // 2026-11-01 照 PoE2 Tectonic Slams（雙震）：被輔助的技能的所有餘震更少 45% 傷害、範圍更少 30%（TectonicSlams）
            final boolean aftershockProc = TectonicSlams.AFTERSHOCKS.contains(subId);
            final double aftershockLess = aftershockProc ? supportFlat(player, skillId, TectonicSlams.DAMAGE_LESS) : 0;
            final double aftershockAreaLess = aftershockProc ? supportFlat(player, skillId, TectonicSlams.AREA_LESS) : 0;
            if (TectonicSlams.PROC.equals(subId)) {
                fired |= sideAftershocks(player, build, parentBuilt, at, aftershockLess, aftershockAreaLess);
                continue;
            }
            if (aftershockProc) {
                build = TectonicSlams.lessened(build, aftershockLess, aftershockAreaLess);
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
            if (drenchedTarget != null) {
                service.executor().chainFrom(Origin.towards(player, target.getLocation(), drenchedTarget), build, drenchedTarget, notDrenched);
                fired = true;
                continue;
            }
            if (tw.exilecore.stats.SupportRemakeKeys.CALTROPS_PROC.equals(subId)) {
                // 2026-11-01 照 PoE2 Caltrops（TriggeredCaltropsPlayer）：在投射物終點 3 格內撒一顆鐵蒺（碰到才炸、8 秒、最多 20 顆，OriginalSkills）；
                //   這次之前在終點放一片 4 秒的鐵蒺地（zone）
                service.originals().spearCaltrop(player, build, subOwnLocal, at, skillId);
                fired = true;
                continue;
            }
            if (tw.exilecore.stats.SupportRemakeKeys.DECAY_PROC.equals(subId) && target != null) {
                // 2026-11-01 照 PoE2 Decaying Hex（朽咒）：直接在被詛咒的那個敵人身上掛凋朽（混沌持續傷害的減益）；這次之前在落點留一片地面
                if (plugin.combat() != null && plugin.combat().skillDamage() != null) {
                    plugin.combat().skillDamage().hit(player, target, build.profile(), null);
                    fired = true;
                }
                continue;
            }
            final Vector direction = aim != null ? SkillTargets.center(aim).toVector().subtract(at.toVector()) : SkillTargets.facing(player);
            final Location from = at.clone().add(0, 0.2, 0);
            final Origin origin = Origin.at(player, from, direction.lengthSquared() < 1e-6 ? SkillTargets.facing(player) : direction.normalize());
            service.procCast(origin, build, aim);
            // 2026-11-01 照 PoE2 Tremors：多次餘震一次一次來（間隔 0.5 秒）
            final SkillBuild again = build;
            final LivingEntity againAim = aim;
            for (int i = 1; i < times; i++) {
                plugin.getServer().getScheduler().runTaskLater(plugin, () -> {
                    if (player.isOnline() && player.getWorld().equals(from.getWorld())) {
                        service.procCast(origin, again, againAim != null && againAim.isValid() && !againAim.isDead() ? againAim : null);
                    }
                }, tw.exilecore.stats.SupportRemakeKeys.TREMOR_GAP_TICKS * i);
            }
            fired = true;
        }
        return fired;
    }

    /**
     * 2026-11-01 照 PoE2 Tectonic Slams（雙震）：猛擊的同時，在原本那一下的範圍前方左右兩側各震一次餘震
     * （範圍照原本那一下 {@link TectonicSlams#initialArea}，再更少 30%；傷害＝那一下的重複再更少 45%）。
     * 這次之前是落在猛擊面前 2.5 格的一圈 2～2.5 格的側震。回傳有沒有放出去。
     */
    private boolean sideAftershocks(final Player player, final SkillBuild sub, final SkillBuild parent, final Location at,
                                    final double damageLess, final double areaLess) {
        final TectonicSlams.Area area = TectonicSlams.initialArea(parent);
        final Vector facing = SkillTargets.facing(player);
        final Location centre = area.atAim() ? at.clone() : player.getLocation().clone().add(facing.clone().multiply(area.forward()));
        final SkillBuild side = TectonicSlams.sideBuild(sub, area.radius(), damageLess, areaLess);
        final double distance = area.radius() > 0 ? area.radius() : sub.radius();
        boolean fired = false;
        for (final double[] offset : TectonicSlams.sideCentres(facing.getX(), facing.getZ(), distance)) {
            final Location from = centre.clone().add(offset[0], 0.2, offset[1]);
            service.procCast(Origin.at(player, from, facing), side, null);
            fired = true;
        }
        return fired;
    }

    /**
     * 2026-11-01 照 PoE2 Reverberate（沉響）：這個技能掛的「攻擊時間每 0.25 秒餘震機率增加 x%」換成機率的倍率（照被輔助那一下的攻擊時間）。
     * 沒掛回傳 1。
     */
    private double aftershockChanceMore(final Player player, final String skillId, final PlayerState state, final SkillLoadout.Entry entry) {
        final double per = supportFlat(player, skillId, tw.exilecore.stats.SupportRemakeKeys.AFTERSHOCK_PER_QUARTER);
        final SkillDefinition parent = per <= 0 ? null : service.config().skill(skillId);
        if (parent == null || entry == null) {
            return 1.0;
        }
        final tw.exilecore.stats.StatSheet sheet = plugin.players().sheet(player);
        return SupportRemakes.reverberate(per, service.build(state, sheet, parent, entry).intervalTicks(sheet));
    }
}
