package tw.exilecore.skill;

import org.bukkit.Location;
import org.bukkit.World;
import org.bukkit.entity.LivingEntity;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.entity.EntityDeathEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.util.Vector;
import tw.exilecore.ExileCore;
import tw.exilecore.character.ConditionTracker;
import tw.exilecore.character.PlayerState;
import tw.exilecore.combat.Debuffs;
import tw.exilecore.combat.HitEffects;
import tw.exilecore.combat.KeystoneEffects;
import tw.exilecore.monster.MonsterData;
import tw.exilecore.monster.MonsterRarity;
import tw.exilecore.stats.AnointKeys;
import tw.exilecore.stats.DamageType;
import tw.exilecore.stats.HitEvent;
import tw.exilecore.stats.HitResult;
import tw.exilecore.stats.ModType;
import tw.exilecore.stats.Modifier;
import tw.exilecore.stats.Recently;
import tw.exilecore.stats.SkillProfile;
import tw.exilecore.stats.StatKeys;
import tw.exilecore.stats.StatSheet;
import tw.exilecore.stats.Tags;
import tw.exilecore.stats.TreeConditions;
import tw.exilecore.stats.TreeLastKeys;
import tw.exilecore.stats.TreeMechanicKeys;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.random.RandomGenerator;

/**
 * 2026-10-27 照 PoE2 0.5：天賦樹最後一批「尚未生效」的機制（屬性鍵在 {@link TreeLastKeys}；站長：「天賦點好像還有蠻多沒有生效的東西」）。
 * <ul>
 *   <li>條件：有秘術增強、召喚物全是夥伴、強化攻擊中、20 秒內用過榮耀技能、8 秒內投射物／近戰擊中、護盾 2 秒內開始回充、站在地面上、
 *       身上幾個詛咒、立著幾種祭品（{@link #registerConditions}）；</li>
 *   <li>事件：被暈眩（易爆、致盲對方）、禁錮敵人（恫嚇）、重暈（雙手武器、變身恫嚇）、完全破甲（癱瘓）、上流血（加劇）、用掉榮耀、
 *       立祭品、召喚物復活、變回人形（秘術增強）；</li>
 *   <li>擊中：危害的減益與次數、被元素擊中、擊退機率；防禦方的護甲防禦（遠處）、免疫曝露、凋零效果；</li>
 *   <li>每 {@value #PERIOD} tick：存在範圍的進出（暴擊、凋零）、稀有怪給榮耀、隊友回怒氣、變身時夥伴疾速、舉盾衰弱、熔焰（永久火焰易傷）。</li>
 * </ul>
 */
public final class TreeLeftovers implements Listener {

    /** 增益名稱（快捷欄字型要收進去）。 */
    public static final List<String> HUD_TEXTS = List.of("榮耀激昂");
    /** 每幾 tick 掃一次。 */
    public static final long PERIOD = 10;
    /** 「最近」：4 秒。 */
    static final long RECENT = StatKeys.RECENTLY_TICKS;
    /** 致盲 4 秒（跟擊中的致盲一樣）。 */
    static final long BLIND_TICKS = 80;
    /** 癱瘓 4 秒（跟擊中的癱瘓一樣）。 */
    static final long MAIM_TICKS = 80;
    /** 存在範圍的進出記多久（之後清掉）。 */
    static final long PRESENCE_MEMORY = 200;
    /** 舉盾衰弱、熔焰掃描的上限（格）。 */
    static final double SCAN_CAP = 30;
    /** 熔焰的減益留多久（「永久」：10 分鐘沒再點燃就清掉）。 */
    static final long MELTING_TICKS = 20L * 60 * 10;
    /** 被元素擊中的事件。 */
    private static final Map<DamageType, String> HIT_BY = Map.of(DamageType.FIRE, TreeLastKeys.EVENT_HIT_FIRE,
            DamageType.COLD, TreeLastKeys.EVENT_HIT_COLD, DamageType.LIGHTNING, TreeLastKeys.EVENT_HIT_LIGHTNING);

    /** 一隻敵人跟一個玩家的存在範圍：現在在不在裡面、最後一次進來、出去的 tick。 */
    static final class Presence {
        boolean inside;
        long entered = Long.MIN_VALUE / 4;
        long exited = Long.MIN_VALUE / 4;
    }

    private final ExileCore plugin;
    private final SkillService service;
    /** 玩家 → 敵人 → 存在範圍的進出。 */
    private final Map<UUID, Map<UUID, Presence>> presence = new HashMap<>();
    /** 熔焰：敵人 → 玩家 → 被他點燃了幾秒。 */
    private final Map<UUID, Map<UUID, Double>> melting = new HashMap<>();
    /** 稀有怪給榮耀：玩家 → 下一次的 tick。 */
    private final Map<UUID, Long> gloryNext = new HashMap<>();
    /** 變身時夥伴疾速：召喚物 → 上次給的 tick。 */
    private final Map<UUID, Long> onslaughtGiven = new HashMap<>();

    TreeLeftovers(final ExileCore plugin, final SkillService service) {
        this.plugin = plugin;
        this.service = service;
    }

    private long now() {
        return plugin.getServer().getCurrentTick();
    }

    private Debuffs debuffs() {
        return plugin.combat() == null ? null : plugin.combat().debuffs();
    }

    private boolean roll(final double percent) {
        return percent >= 100 || percent > 0 && plugin.random().nextDouble() * 100 < percent;
    }

    private boolean monster(final LivingEntity entity) {
        return !(entity instanceof Player) && plugin.monsters() != null && plugin.monsters().data(entity).isPresent()
                && (plugin.combat() == null || plugin.combat().minions() == null || plugin.combat().minions().owner(entity) == null);
    }

    private void heal(final Player player, final double percent) {
        if (percent <= 0) {
            return;
        }
        final PlayerState state = plugin.players().get(player);
        if (state.ruined(now())) {
            return;
        }
        state.setLife(Math.min(state.maxLife(), state.life() + state.maxLife() * percent / 100.0));
        plugin.players().display(player, state);
    }

    // =====================================================================
    // 條件（ConditionTracker）
    // =====================================================================

    /** 自己的狀態條件接上（SkillModule 啟用時）。 */
    void registerConditions(final ConditionTracker conditions) {
        // 「一直有秘術增強」的也是這個增益（TreeMechanics 一直補上）；重算屬性表時不能讀正在重建的表
        conditions.register(TreeConditions.ARCANE_SURGE, (player, state) -> service.buffActive(player, "tree.arcane_surge"));
        conditions.register(TreeConditions.ALL_COMPANIONS, (player, state) -> service.minions() != null
                && service.minions().allCompanions(player.getUniqueId()));
        conditions.register(TreeConditions.EMPOWERED_ATTACK, (player, state) -> within(state, TreeLastKeys.EVENT_EMPOWERED, TreeLastKeys.EMPOWERED_WINDOW));
        conditions.register(TreeConditions.GLORY_20S, (player, state) -> within(state, TreeLastKeys.EVENT_GLORY_USED, TreeLastKeys.TWENTY_SECONDS));
        conditions.register(TreeConditions.PROJECTILE_HIT_8S, (player, state) -> within(state, Recently.PROJECTILE_HIT, TreeLastKeys.EIGHT_SECONDS));
        conditions.register(TreeConditions.MELEE_HIT_8S, (player, state) -> within(state, Recently.MELEE_HIT, TreeLastKeys.EIGHT_SECONDS));
        conditions.register(TreeConditions.ES_RECHARGE_2S, (player, state) -> within(state, TreeLastKeys.EVENT_ES_RECHARGE, TreeLastKeys.TWO_SECONDS));
        conditions.register(TreeConditions.ON_SHOCKED_GROUND, (player, state) -> within(state, TreeLastKeys.EVENT_GROUND_SHOCK, TreeLastKeys.GROUND_WINDOW));
        conditions.register(TreeConditions.ON_CHILLED_GROUND, (player, state) -> within(state, TreeLastKeys.EVENT_GROUND_CHILL, TreeLastKeys.GROUND_WINDOW));
        conditions.register(TreeConditions.ON_IGNITED_GROUND, (player, state) -> within(state, TreeLastKeys.EVENT_GROUND_FIRE, TreeLastKeys.GROUND_WINDOW));
        for (int n = 1; n <= 5; n++) {
            final int at = n;
            conditions.register(TreeConditions.SELF_CURSES + n, (player, state) -> curseCount(player.getUniqueId()) >= at);
        }
        for (int n = 1; n <= 3; n++) {
            final int at = n;
            conditions.register(TreeConditions.OFFERINGS + n, (player, state) -> service.warcries() != null
                    && service.warcries().activeOfferings(player.getUniqueId()) >= at);
        }
    }

    private boolean within(final PlayerState state, final String event, final long window) {
        return now() - state.recentTick(event) <= window;
    }

    /** 身上幾個詛咒（不同的詛咒技能；換界石的詛咒圈也算）。 */
    int curseCount(final UUID player) {
        final Debuffs debuffs = debuffs();
        return debuffs == null ? 0 : curseCount(debuffs.active(player, now()));
    }

    /** 純計算：減益裡有幾種詛咒（代號含 _curse；同一個詛咒的幾條（縛根）算一個）。 */
    static int curseCount(final List<Debuffs.Debuff> active) {
        final Set<String> curses = new HashSet<>();
        for (final Debuffs.Debuff debuff : active) {
            if (debuff.id().startsWith("map_curse.")) {
                curses.add(debuff.id()); // 換界石的詛咒圈：map_curse.種類
            } else if (debuff.id().contains("_curse")) {
                final int dot = debuff.id().indexOf('.', debuff.id().indexOf("_curse"));
                curses.add(dot < 0 ? debuff.id() : debuff.id().substring(0, dot)); // 技能代號_curse(.標籤)
            }
        }
        return curses.size();
    }

    /** 這一下的敵人條件標籤：最近進入、離開這個玩家的存在範圍（TargetConditions 呼叫）。 */
    public Set<String> presenceTags(final UUID player, final UUID enemy, final long now) {
        final Map<UUID, Presence> mine = presence.get(player);
        final Presence mark = mine == null ? null : mine.get(enemy);
        if (mark == null) {
            return Set.of();
        }
        return presenceTags(mark, now);
    }

    static Set<String> presenceTags(final Presence mark, final long now) {
        final Set<String> tags = new HashSet<>(2);
        if (now - mark.entered <= RECENT) {
            tags.add(TreeConditions.VS_ENTERED_PRESENCE);
        }
        if (now - mark.exited <= RECENT) {
            tags.add(TreeConditions.VS_EXITED_PRESENCE);
        }
        return tags;
    }

    // =====================================================================
    // 事件（別的系統呼叫）
    // =====================================================================

    /** 被暈眩（輕暈或重暈；AilmentService 呼叫）：x% 機率獲得易爆；讓暈你的敵人致盲。 */
    public void stunnedBy(final Player player, final LivingEntity attacker) {
        final long now = now();
        plugin.players().get(player).markRecent(TreeLastKeys.EVENT_STUNNED, now);
        final StatSheet sheet = plugin.players().sheet(player);
        if (roll(sheet.flat(TreeLastKeys.VOLATILITY_ON_STUNNED, Set.of())) && service.treeMechanics() != null) {
            service.treeMechanics().gainVolatility(player, 1);
        }
        final Debuffs debuffs = debuffs();
        if (attacker != null && attacker != player && debuffs != null && StatKeys.flag(sheet, TreeLastKeys.FLAG_BLIND_STUNNER)) {
            debuffs.apply(attacker.getUniqueId(), new Debuffs.Debuff("blind", player.getUniqueId(), now + BLIND_TICKS, 0, false, null,
                    HitEffects.blindModifiers(0), 0));
        }
    }

    /** 禁錮了敵人（釘住、重暈、凍結、電擊）：恫嚇 x 秒（受到的傷害增加 10%）。 */
    public void immobilised(final Player player, final LivingEntity defender) {
        intimidate(player, defender, plugin.players().sheet(player).flat(TreeLastKeys.INTIMIDATE_ON_IMMOBILISE, Set.of()));
    }

    private void intimidate(final Player player, final LivingEntity defender, final double seconds) {
        final Debuffs debuffs = debuffs();
        if (seconds <= 0 || debuffs == null || defender instanceof Player) {
            return;
        }
        // 同一個代號：恫嚇不疊加（再恫嚇只刷新時間）
        debuffs.apply(defender.getUniqueId(), new Debuffs.Debuff("intimidate", player.getUniqueId(), now() + Math.round(seconds * 20),
                TreeLastKeys.INTIMIDATE_TAKEN, false));
    }

    /** 重暈了敵人（AilmentService 呼叫）：雙手近戰武器重暈的事件、變身中重暈恫嚇、禁錮恫嚇。 */
    public void heavyStunned(final Player player, final LivingEntity defender, final SkillProfile skill) {
        final PlayerState state = plugin.players().get(player);
        if (skill != null && skill.tags().contains(Tags.MELEE) && state.weaponTags().contains(Tags.TWO_HAND)) {
            state.markRecent(TreeLastKeys.EVENT_STUNNED_TWO_HAND, now());
        }
        if (service.shapeshifted(player)) {
            intimidate(player, defender, plugin.players().sheet(player).flat(TreeLastKeys.INTIMIDATE_SHAPESHIFT_STUN, Set.of()));
        }
        immobilised(player, defender);
    }

    /** 完全破甲（CombatListener 呼叫）：有天賦的話癱瘓牠。 */
    public void onFullBreak(final Player player, final LivingEntity defender) {
        final Debuffs debuffs = debuffs();
        if (debuffs == null || !StatKeys.flag(plugin.players().sheet(player), TreeLastKeys.FLAG_MAIM_ON_FULL_BREAK)) {
            return;
        }
        debuffs.apply(defender.getUniqueId(), new Debuffs.Debuff("maim", player.getUniqueId(), now() + MAIM_TICKS, 0, false, null,
                List.of(Modifier.increased(StatKeys.EVASION, -HitEffects.MAIM_EVASION, "癱瘓")), HitEffects.MAIM_SLOW));
    }

    /** 上了流血（AilmentService 呼叫）：對被釘住的加劇；對站在碎地上的 x% 機率加劇。 */
    public void bleedInflicted(final Player player, final LivingEntity defender, final StatSheet attack, final Set<String> context) {
        final Debuffs debuffs = debuffs();
        if (plugin.ailments() == null || debuffs == null) {
            return;
        }
        final boolean pinned = attack.flat(TreeLastKeys.FLAG_AGGRAVATE_PINNED, context) > 0 && debuffs.has(defender.getUniqueId(), "pin", now());
        final double jagged = attack.flat(TreeLastKeys.AGGRAVATE_JAGGED, context);
        if (pinned || jagged > 0 && service.chargeEffects() != null && service.chargeEffects().onJaggedGround(defender) && roll(jagged)) {
            plugin.ailments().aggravateBleeds(defender, player.getUniqueId());
        }
    }

    /** 用了需要榮耀的技能（SupportMechanics 呼叫）：記下來；用掉 consumed 點時回復生命、6 秒增加攻擊傷害。 */
    public void gloryUsed(final Player player, final double consumed) {
        plugin.players().get(player).markRecent(TreeLastKeys.EVENT_GLORY_USED, now());
        if (consumed <= 0) {
            return;
        }
        final StatSheet sheet = plugin.players().sheet(player);
        heal(player, sheet.flat(TreeLastKeys.LIFE_PER_GLORY, Set.of()) * consumed);
        final double attack = TreeLastKeys.gloryAttack(sheet.flat(TreeLastKeys.ATTACK_PER_GLORY, Set.of()), consumed);
        if (attack > 0) {
            service.buff(player, "tree.glory_attack", "榮耀激昂", TreeLastKeys.ATTACK_PER_GLORY_TICKS,
                    List.of(new Modifier(StatKeys.DAMAGE, ModType.INCREASED, attack, Set.of(Tags.ATTACK), "榮耀激昂")));
        }
    }

    /** 立了祭品（Warcries 呼叫）：回復生命。 */
    public void offeringCreated(final Player player) {
        heal(player, plugin.players().sheet(player).flat(TreeLastKeys.LIFE_ON_OFFERING, Set.of()));
    }

    /** 召喚物復活了（AuraService 呼叫）：回復生命；2026-10-27 塗油限定 狂颮之主：給剛復活的那隻元素統治者 x 秒。 */
    public void minionRevived(final Player player, final String skillId) {
        final StatSheet sheet = plugin.players().sheet(player);
        heal(player, sheet.flat(TreeLastKeys.LIFE_ON_MINION_REVIVE, Set.of()));
        final double seconds = sheet.flat(AnointKeys.MINION_REVIVE_ARCHON, Set.of());
        if (seconds > 0 && service.minions() != null) {
            final UUID minion = service.minions().newest(player.getUniqueId(), skillId);
            if (minion != null) {
                service.minions().buffMinion(minion, minionElementalArchon(), (int) Math.round(seconds * 20));
            }
        }
    }

    /**
     * 2026-10-27 照 PoE2：給召喚物的元素統治者（法術元素傷害更多 25%、不能造成非元素傷害、凍結累積／點燃與感電機率更多 100%）；
     * 跟玩家的一樣（TreeMechanics.archonModifiers），不吃主人的「你身上的統治者效果」與統治者另外給的抗性、速度。
     */
    static List<Modifier> minionElementalArchon() {
        final String source = TreeMechanics.Archon.ELEMENTAL.displayName();
        return List.of(new Modifier(StatKeys.DAMAGE, ModType.MORE, 0.25, Set.of(Tags.SPELL, Tags.ELEMENTAL), source),
                new Modifier(StatKeys.DAMAGE, ModType.MORE, -1.0, Set.of(Tags.SPELL, Tags.PHYSICAL), source),
                new Modifier(StatKeys.DAMAGE, ModType.MORE, -1.0, Set.of(Tags.SPELL, Tags.CHAOS), source),
                new Modifier(StatKeys.FREEZE_BUILDUP, ModType.MORE, 1.0, Set.of(Tags.SPELL), source),
                new Modifier(StatKeys.IGNITE_CHANCE, ModType.MORE, 1.0, Set.of(Tags.SPELL), source),
                new Modifier(StatKeys.SHOCK_CHANCE, ModType.MORE, 1.0, Set.of(Tags.SPELL), source));
    }

    /** 變回人形（ConditionTracker 呼叫）：變身至少 x 秒的話獲得秘術增強。 */
    public void reverted(final Player player, final StatSheet sheet, final double seconds) {
        final double need = sheet.flat(TreeLastKeys.REVERT_ARCANE_SURGE, Set.of());
        if (need > 0 && seconds + 1e-9 >= need && service.treeMechanics() != null) {
            service.treeMechanics().grantArcaneSurge(player, (int) TreeMechanics.ARCANE_SURGE_TICKS);
        }
    }

    // =====================================================================
    // 靜態的小工具（別的系統直接呼叫）
    // =====================================================================

    /** 投射物穿透 3 格內敵人的機率（ProjectileEngine 打中時呼叫；穿過去不算穿透次數）。 */
    static boolean pierceClose(final StatSheet effective, final Set<String> tags, final Player owner, final LivingEntity target,
                               final RandomGenerator random) {
        final double chance = effective.flat(TreeLastKeys.PIERCE_CLOSE, tags);
        if (chance <= 0 || !owner.getWorld().equals(target.getWorld())
                || owner.getLocation().distanceSquared(target.getLocation()) > TreeLastKeys.PIERCE_CLOSE_RANGE * TreeLastKeys.PIERCE_CLOSE_RANGE) {
            return false;
        }
        return chance >= 100 || random.nextDouble() * 100 < chance;
    }

    /** 這一灘地面算哪一種（感電、冰緩、點燃地面）；都不是回傳 null。dormant：毒霧、還沒點燃的火油（不算點燃地面）。 */
    static String groundEvent(final SkillProfile profile, final boolean dormant) {
        if (profile == null) {
            return null;
        }
        if (GroundEffects.sum(profile, GroundEffects.SHOCK) > 0) {
            return TreeLastKeys.EVENT_GROUND_SHOCK;
        }
        if (GroundEffects.sum(profile, GroundEffects.CHILL) > 0) {
            return TreeLastKeys.EVENT_GROUND_CHILL;
        }
        return !dormant && !GroundEffects.is(profile) && profile.tags().contains(Tags.FIRE) ? TreeLastKeys.EVENT_GROUND_FIRE : null;
    }

    /**
     * 2026-10-27 照 PoE2（塗油限定 沙漠之蔑）：站在你的冰緩地面上的敵人受到的火焰傷害增加、站在點燃地面上的受到的冰冷傷害增加
     * （SkillZones 每一波打之前呼叫；減益撐到下一波之後一點，離開地面就沒了）。event 是 {@link #groundEvent} 的結果。
     */
    static void scornGround(final ExileCore plugin, final Player owner, final StatSheet ownerSheet, final LivingEntity target, final String event,
                            final long intervalTicks, final long now) {
        if (ownerSheet == null || event == null || target instanceof Player || plugin.combat() == null || plugin.combat().debuffs() == null) {
            return;
        }
        final double taken;
        final DamageType type;
        if (TreeLastKeys.EVENT_GROUND_CHILL.equals(event)) {
            taken = ownerSheet.flat(AnointKeys.CHILLED_GROUND_FIRE_TAKEN, Set.of());
            type = DamageType.FIRE;
        } else if (TreeLastKeys.EVENT_GROUND_FIRE.equals(event)) {
            taken = ownerSheet.flat(AnointKeys.IGNITED_GROUND_COLD_TAKEN, Set.of());
            type = DamageType.COLD;
        } else {
            return;
        }
        if (taken <= 0) {
            return;
        }
        plugin.combat().debuffs().apply(target.getUniqueId(), new Debuffs.Debuff("tree.scorn." + type.tag() + "." + owner.getUniqueId(),
                owner.getUniqueId(), now + intervalTicks + 5, taken / 100.0, false, type.tag()));
    }

    /** 地面的一波（SkillZones 呼叫）：站在裡面的玩家記一下。 */
    static void groundPulse(final ExileCore plugin, final List<Location> points, final double radius, final String event, final long now) {
        if (event == null || points.isEmpty() || points.getFirst().getWorld() == null) {
            return;
        }
        final World world = points.getFirst().getWorld();
        for (final Player player : world.getPlayers()) {
            final Location at = player.getLocation();
            for (final Location point : points) {
                final double dx = at.getX() - point.getX();
                final double dz = at.getZ() - point.getZ();
                if (dx * dx + dz * dz <= radius * radius && Math.abs(at.getY() - point.getY()) <= 2) {
                    plugin.players().find(player.getUniqueId()).ifPresent(state -> state.markRecent(event, now));
                    break;
                }
            }
        }
    }

    /**
     * 防禦方是玩家的那一下（CombatListener 呼叫）：6 格外打來的用更多護甲防禦（PoE2 Impenetrable Shell）、
     * 免疫曝露（曝露的抗性減益抵掉）、身上的凋零效果減少。
     * untaggedProjectile：這一下是投射物攻擊，但技能描述沒有 projectile 標籤（怪物的箭用的是普攻的描述）：「對投射物攻擊用 x% 護甲防禦」補在這裡。
     */
    public static StatSheet defenderSheet(final LivingEntity defender, final LivingEntity attacker, final StatSheet sheet,
                                          final List<Modifier> defenderDebuffs, final Debuffs debuffs, final long now,
                                          final boolean untaggedProjectile) {
        final List<Modifier> extra = new ArrayList<>(2);
        if (untaggedProjectile) {
            final double projectile = sheet.flat(TreeLastKeys.ARMOUR_DEFEND, Set.of(Tags.PROJECTILE, Tags.ATTACK)) - sheet.flat(TreeLastKeys.ARMOUR_DEFEND, Set.of());
            if (projectile > 0) {
                extra.add(Modifier.flat(TreeLastKeys.ARMOUR_DEFEND, projectile, "投射物攻擊"));
            }
        }
        if (attacker != null && attacker.getWorld().equals(defender.getWorld())
                && attacker.getLocation().distanceSquared(defender.getLocation()) > tw.exilecore.stats.TreeConditions.FAR_RANGE * TreeConditions.FAR_RANGE) {
            final double far = sheet.flat(TreeLastKeys.ARMOUR_DEFEND, Set.of(TreeLastKeys.FROM_FAR)) - sheet.flat(TreeLastKeys.ARMOUR_DEFEND, Set.of());
            if (far > 0) {
                extra.add(Modifier.flat(TreeLastKeys.ARMOUR_DEFEND, far, "遠處的擊中"));
            }
        }
        if (StatKeys.flag(sheet, tw.exilecore.stats.TreeNewMechanicKeys.FLAG_EXPOSURE_IMMUNE)) {
            for (final Modifier mod : defenderDebuffs) {
                if ("曝露".equals(mod.source()) && mod.type() == ModType.FLAT) {
                    extra.add(Modifier.flat(mod.stat(), -mod.value(), "免疫曝露"));
                }
            }
        }
        final double witheredScale = Math.max(0, 1 + sheet.increased(tw.exilecore.stats.TreeNewMechanicKeys.WITHERED_SELF_EFFECT, Set.of()));
        if (witheredScale < 1 && debuffs != null) {
            double withered = 0;
            for (final Debuffs.Debuff debuff : debuffs.active(defender.getUniqueId(), now)) {
                if (debuff.id().startsWith("withered.")) {
                    withered += debuff.taken();
                }
            }
            if (withered > 0) {
                extra.add(Modifier.flat(StatKeys.DEBUFF_TAKEN + DamageType.CHAOS.tag(), -withered * (1 - witheredScale) * 100, "凋零效果減少"));
            }
        }
        return extra.isEmpty() ? sheet : sheet.overlay(extra);
    }

    /** 被詛咒的玩家還能不能回充能量護盾（PlayerStateService 呼叫）：詛咒他的人有「被你詛咒的敵人不能回充」就不能。 */
    public static boolean esRechargeBlocked(final ExileCore plugin, final Player player, final long now) {
        if (plugin.combat() == null || plugin.combat().debuffs() == null) {
            return false;
        }
        for (final Debuffs.Debuff debuff : plugin.combat().debuffs().active(player.getUniqueId(), now)) {
            if (!debuff.id().contains("_curse") || debuff.source() == null || debuff.source().equals(player.getUniqueId())) {
                continue;
            }
            final Player curser = plugin.getServer().getPlayer(debuff.source());
            if (curser != null && StatKeys.flag(plugin.players().sheet(curser), TreeLastKeys.FLAG_CURSE_NO_ES_RECHARGE)) {
                return true;
            }
        }
        return false;
    }

    // =====================================================================
    // 擊中
    // =====================================================================

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onHit(final HitEvent event) {
        final HitResult result = event.result();
        if (!result.hit() || result.blocked() || event.skill() == null) {
            return;
        }
        final long now = now();
        final LivingEntity defender = event.defender();
        // 被元素擊中（PoE2 Adaptive Skin：最近被哪幾種元素擊中過，那幾種的抗性上限 +1%）
        if (defender instanceof Player hurt && event.attacker() != hurt) {
            final PlayerState state = plugin.players().find(hurt.getUniqueId()).orElse(null);
            if (state != null) {
                for (final Map.Entry<DamageType, String> entry : HIT_BY.entrySet()) {
                    if (result.dealt().get(entry.getKey()) > 0) {
                        state.markRecent(entry.getValue(), now);
                    }
                }
            }
        }
        final Player player = event.credit();
        if (player == null || event.attacker() != player || defender instanceof Player || !monster(defender)) {
            return;
        }
        final SkillProfile skill = event.skill();
        final StatSheet owner = plugin.players().sheet(player);
        final StatSheet effective = skill.effective(owner);
        final Debuffs debuffs = debuffs();
        // 危害：最近被你的危害打到的敵人護甲、閃避減少；記「最近觸發過幾次危害」
        if (skill.tags().contains(TreeMechanicKeys.HAZARD)) {
            plugin.players().conditions().note(player, TreeConditions.HAZARDS, skill.id() + "@" + now, now);
            final double armour = owner.increased(TreeLastKeys.HAZARD_ENEMY_ARMOUR, Set.of());
            final double evasion = owner.increased(TreeLastKeys.HAZARD_ENEMY_EVASION, Set.of());
            if (debuffs != null && (armour != 0 || evasion != 0)) {
                final List<Modifier> mods = new ArrayList<>(2);
                if (armour != 0) {
                    mods.add(Modifier.increased(StatKeys.ARMOUR, armour, "危害"));
                }
                if (evasion != 0) {
                    mods.add(Modifier.increased(StatKeys.EVASION, evasion, "危害"));
                }
                debuffs.apply(defender.getUniqueId(), new Debuffs.Debuff("tree.hazard." + player.getUniqueId(), player.getUniqueId(), now + RECENT,
                        0, false, null, mods, 0));
            }
        }
        // 擊退機率（對近距離、流血的敵人：條件在標籤）
        if (roll(effective.flat(TreeLastKeys.KNOCKBACK_CHANCE, skill.tags()))) {
            final Vector push = defender.getLocation().toVector().subtract(player.getLocation().toVector()).setY(0);
            if (push.lengthSquared() > 1e-6) {
                defender.setVelocity(defender.getVelocity().add(push.normalize().multiply(0.6).setY(0.25)));
            }
        }
    }

    // =====================================================================
    // 每 PERIOD tick
    // =====================================================================

    void tick() {
        final long now = now();
        for (final Player player : plugin.getServer().getOnlinePlayers()) {
            final PlayerState state = plugin.players().find(player.getUniqueId()).orElse(null);
            if (state == null || !state.hasCharacter() || state.isDead()) {
                continue;
            }
            final StatSheet sheet = plugin.players().sheet(player);
            final double radius = tw.exilecore.combat.Presence.radius(sheet);
            trackPresence(player, sheet, radius, now);
            gloryFromRares(player, state, sheet, radius, now);
            allyRage(player, state, sheet, radius);
            companionOnslaught(player, sheet, radius, now);
            shieldDebilitate(player, sheet, now);
            shieldBlind(player, sheet, now);
            meltingFlames(player, sheet, now);
        }
        onslaughtGiven.values().removeIf(tick -> now - tick > 200);
        // 2026-10-27：沒死就消失（區塊卸載、被清掉）的敵人沒有死亡事件；這次之前只在死亡時清，會一直累積
        if (now % 200 < PERIOD && !melting.isEmpty()) {
            melting.keySet().removeIf(id -> {
                final org.bukkit.entity.Entity entity = plugin.getServer().getEntity(id);
                return entity == null || !entity.isValid();
            });
        }
    }

    /** 這個玩家需不需要記存在範圍的進出（屬性表裡有「對最近進入／離開…的敵人」或「進入時凋零」）。 */
    private static boolean wantsPresence(final StatSheet sheet) {
        if (sheet.flat(TreeLastKeys.WITHER_ON_ENTER, Set.of()) > 0) {
            return true;
        }
        for (final String stat : new String[] {StatKeys.CRIT_CHANCE, StatKeys.CRIT_BONUS, StatKeys.DAMAGE}) {
            for (final Modifier mod : sheet.modifiersOf(stat)) {
                if (mod.tags().contains(TreeConditions.VS_ENTERED_PRESENCE) || mod.tags().contains(TreeConditions.VS_EXITED_PRESENCE)) {
                    return true;
                }
            }
        }
        return false;
    }

    private void trackPresence(final Player player, final StatSheet sheet, final double radius, final long now) {
        if (!wantsPresence(sheet)) {
            presence.remove(player.getUniqueId());
            return;
        }
        final Map<UUID, Presence> mine = presence.computeIfAbsent(player.getUniqueId(), id -> new HashMap<>());
        final double witherSeconds = sheet.flat(TreeLastKeys.WITHER_ON_ENTER, Set.of());
        final Debuffs debuffs = debuffs();
        final Location at = player.getLocation();
        final double scan = Math.min(SCAN_CAP + radius, radius * 1.5 + 4);
        final Set<UUID> seen = new HashSet<>();
        for (final LivingEntity entity : player.getWorld().getNearbyLivingEntities(at, scan)) {
            if (!entity.isValid() || entity.isDead() || !monster(entity)) {
                continue;
            }
            seen.add(entity.getUniqueId());
            final boolean inside = entity.getLocation().distanceSquared(at) <= radius * radius;
            final Presence mark = mine.computeIfAbsent(entity.getUniqueId(), id -> new Presence());
            if (inside && !mark.inside) {
                mark.entered = now;
                // PoE2 Wither Away：沒有凋零的敵人進入你的存在範圍時凋零 x 秒
                if (witherSeconds > 0 && debuffs != null && HitEffects.stacks(debuffs, entity.getUniqueId(), "withered.", now) == 0) {
                    HitEffects.addWithered(debuffs, entity.getUniqueId(), player.getUniqueId(), now, 1, Math.round(witherSeconds * 20));
                }
            } else if (!inside && mark.inside) {
                mark.exited = now;
            }
            mark.inside = inside;
        }
        for (final Iterator<Map.Entry<UUID, Presence>> it = mine.entrySet().iterator(); it.hasNext(); ) {
            final Map.Entry<UUID, Presence> entry = it.next();
            final Presence mark = entry.getValue();
            if (!seen.contains(entry.getKey()) && mark.inside) {
                mark.inside = false; // 跑遠了（或死了）
                mark.exited = now;
            }
            if (!mark.inside && now - Math.max(mark.entered, mark.exited) > PRESENCE_MEMORY) {
                it.remove();
            }
        }
    }

    /** PoE2 Glorious Anticipation：存在範圍內每有一隻稀有或傳奇怪物，技能每 x 秒獲得 1 點榮耀。 */
    private void gloryFromRares(final Player player, final PlayerState state, final StatSheet sheet, final double radius, final long now) {
        final double seconds = sheet.flat(TreeLastKeys.GLORY_PER_RARE, Set.of());
        if (seconds <= 0) {
            gloryNext.remove(player.getUniqueId());
            return;
        }
        final long next = gloryNext.computeIfAbsent(player.getUniqueId(), id -> now + Math.round(seconds * 20));
        if (now < next) {
            return;
        }
        gloryNext.put(player.getUniqueId(), now + Math.round(seconds * 20));
        int rares = 0;
        for (final LivingEntity entity : player.getWorld().getNearbyLivingEntities(player.getLocation(), radius)) {
            final MonsterData data = monster(entity) ? plugin.monsters().data(entity).orElse(null) : null;
            if (data != null && (data.boss() || data.rarity() == MonsterRarity.RARE || data.rarity() == MonsterRarity.UNIQUE)) {
                rares++;
            }
        }
        if (rares <= 0) {
            return;
        }
        for (int i = 0; i < tw.exilecore.character.SkillLoadout.MAX_SKILLS; i++) {
            final tw.exilecore.character.SkillLoadout.Entry entry = state.skills().skill(i);
            if (entry != null) {
                service.addGlory(player, entry.skillId(), rares); // 不用榮耀的技能不會加
            }
        }
    }

    /** PoE2 Warlord Berserker：最近獲得過怒氣時，存在範圍內的隊友每秒回復 x 點怒氣。 */
    private void allyRage(final Player player, final PlayerState state, final StatSheet sheet, final double radius) {
        final double perSecond = sheet.flat(TreeLastKeys.ALLY_RAGE_REGEN, Set.of());
        if (perSecond <= 0 || service.resources() == null || plugin.party() == null || !state.recently(TreeConditions.RAGE_GAINED, now())) {
            return;
        }
        for (final Player ally : player.getWorld().getPlayers()) {
            if (ally != player && !ally.isDead() && ally.getLocation().distanceSquared(player.getLocation()) <= radius * radius
                    && plugin.party().sameGroup(player.getUniqueId(), ally.getUniqueId())) {
                service.resources().gainRage(ally, perSecond * PERIOD / 20.0);
            }
        }
    }

    /** PoE2 Rallying Form：變身時存在範圍內的夥伴有疾速。 */
    private void companionOnslaught(final Player player, final StatSheet sheet, final double radius, final long now) {
        if (!StatKeys.flag(sheet, TreeLastKeys.FLAG_COMPANION_ONSLAUGHT_SHAPESHIFTED) || service.minions() == null || !service.shapeshifted(player)) {
            return;
        }
        for (final UUID companion : service.minions().companionsNear(player, radius)) {
            final Long last = onslaughtGiven.get(companion);
            if (last == null || now - last >= 20) {
                service.minions().buffMinion(companion, TreeMechanics.COMPANION_ONSLAUGHT, 40);
                onslaughtGiven.put(companion, now);
            }
        }
    }

    /** PoE2 Frightening Shield：舉盾時正前方 x 格內的敵人衰弱。 */
    private void shieldDebilitate(final Player player, final StatSheet sheet, final long now) {
        final double range = Math.min(SCAN_CAP, sheet.flat(TreeLastKeys.SHIELD_DEBILITATE, Set.of()));
        final Debuffs debuffs = debuffs();
        if (range <= 0 || debuffs == null || !(service.buffActivePrefix(player, "guard") || service.buffActive(player, "channel_block"))) {
            return;
        }
        final Location at = player.getLocation();
        final Vector facing = at.getDirection().setY(0);
        if (facing.lengthSquared() < 1e-6) {
            return;
        }
        facing.normalize();
        for (final LivingEntity entity : player.getWorld().getNearbyLivingEntities(at, range)) {
            if (!monster(entity) || entity.getLocation().distanceSquared(at) > range * range) {
                continue;
            }
            final Vector to = entity.getLocation().toVector().subtract(at.toVector()).setY(0);
            if (to.lengthSquared() > 1e-6 && to.normalize().dot(facing) >= 0.5) {
                debuffs.apply(entity.getUniqueId(), new Debuffs.Debuff(KeystoneEffects.DEBILITATE_ID, player.getUniqueId(), now + PERIOD * 2, 0,
                        false, null, List.of(Modifier.increased(StatKeys.DAMAGE, -KeystoneEffects.DEBILITATE_LESS, "衰弱")),
                        KeystoneEffects.DEBILITATE_SLOW));
            }
        }
    }

    /** 2026-10-27 照 PoE2（塗油限定 光明壁壘）：舉盾時正前方 x 格內的敵人致盲（原作每 0.25 秒；致盲 4 秒，這裡每 10 tick 續上）。 */
    private void shieldBlind(final Player player, final StatSheet sheet, final long now) {
        final double range = Math.min(SCAN_CAP, sheet.flat(AnointKeys.SHIELD_BLIND, Set.of()));
        final Debuffs debuffs = debuffs();
        if (range <= 0 || debuffs == null || !(service.buffActivePrefix(player, "guard") || service.buffActive(player, "channel_block"))) {
            return;
        }
        for (final LivingEntity entity : inFront(player, range)) {
            debuffs.apply(entity.getUniqueId(), new Debuffs.Debuff("blind", player.getUniqueId(), now + BLIND_TICKS, 0, false, null,
                    HitEffects.blindModifiers(0), 0));
        }
    }

    /** 正前方（左右各 60 度）range 格內的敵人。 */
    private List<LivingEntity> inFront(final Player player, final double range) {
        final List<LivingEntity> list = new ArrayList<>();
        final Location at = player.getLocation();
        final Vector facing = at.getDirection().setY(0);
        if (facing.lengthSquared() < 1e-6) {
            return list;
        }
        facing.normalize();
        for (final LivingEntity entity : player.getWorld().getNearbyLivingEntities(at, range)) {
            if (!monster(entity) || entity.getLocation().distanceSquared(at) > range * range) {
                continue;
            }
            final Vector to = entity.getLocation().toVector().subtract(at.toVector()).setY(0);
            if (to.lengthSquared() > 1e-6 && to.normalize().dot(facing) >= 0.5) {
                list.add(entity);
            }
        }
        return list;
    }

    /** PoE2 Melting Flames：被你點燃的敵人，每被你點燃 1 秒，永久受到的火焰傷害增加 x%（最多 10 倍）。 */
    private void meltingFlames(final Player player, final StatSheet sheet, final long now) {
        final double per = sheet.flat(TreeLastKeys.MELTING_FLAMES, Set.of());
        final Debuffs debuffs = debuffs();
        if (per <= 0 || debuffs == null || plugin.ailments() == null) {
            return;
        }
        for (final LivingEntity entity : player.getWorld().getNearbyLivingEntities(player.getLocation(), SCAN_CAP)) {
            if (!monster(entity)) {
                continue;
            }
            final tw.exilecore.ailment.AilmentState.Ignite ignite = plugin.ailments().ignite(entity);
            if (ignite == null || !player.getUniqueId().equals(ignite.source())) {
                continue;
            }
            final Map<UUID, Double> byPlayer = melting.computeIfAbsent(entity.getUniqueId(), id -> new HashMap<>());
            final double seconds = byPlayer.merge(player.getUniqueId(), PERIOD / 20.0, Double::sum);
            final double taken = TreeLastKeys.meltingTaken(per, seconds);
            if (taken > 0) {
                debuffs.apply(entity.getUniqueId(), new Debuffs.Debuff("tree.melting." + player.getUniqueId(), player.getUniqueId(),
                        now + MELTING_TICKS, taken, false, DamageType.FIRE.tag()));
            }
        }
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onDeath(final EntityDeathEvent event) {
        final UUID dead = event.getEntity().getUniqueId();
        melting.remove(dead);
        for (final Map<UUID, Presence> mine : presence.values()) {
            mine.remove(dead);
        }
    }

    @EventHandler
    public void onQuit(final PlayerQuitEvent event) {
        final UUID id = event.getPlayer().getUniqueId();
        presence.remove(id);
        gloryNext.remove(id);
    }
}
