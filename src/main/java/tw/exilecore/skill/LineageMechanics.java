package tw.exilecore.skill;

import org.bukkit.Location;
import org.bukkit.Particle;
import org.bukkit.Sound;
import org.bukkit.entity.Entity;
import org.bukkit.entity.LivingEntity;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.entity.EntityDeathEvent;
import org.bukkit.util.Vector;
import tw.exilecore.ExileCore;
import tw.exilecore.ailment.AilmentType;
import tw.exilecore.character.PlayerState;
import tw.exilecore.character.SkillLoadout;
import tw.exilecore.combat.Debuffs;
import tw.exilecore.monster.MonsterData;
import tw.exilecore.monster.MonsterRarity;
import tw.exilecore.stats.DamagePacket;
import tw.exilecore.stats.DamageType;
import tw.exilecore.stats.HitEvent;
import tw.exilecore.stats.LineageKeys;
import tw.exilecore.stats.ModType;
import tw.exilecore.stats.Modifier;
import tw.exilecore.stats.SkillProfile;
import tw.exilecore.stats.StatKeys;
import tw.exilecore.stats.StatSheet;
import tw.exilecore.stats.Tags;

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
 * 2026-10-27 照 PoE2 0.5：血脈輔助寶石（data/supports_lineage.yml）執行時的機制。屬性鍵見 {@link LineageKeys}，
 * 能不能掛與建技能時的加成在 {@link LineageRules}。入口（都很短，方便合併）：
 * <ul>
 *   <li>{@link SupportMechanics}：放技能（usageProblem、used、afterEmpowered）、擊中前（preHit）、輔助事件（event）、每 5 tick（tick）</li>
 *   <li>{@link SkillService}：放之前（prepare，算攻擊間隔之前）、冷卻次數、主要寶石觸發（triggered）、弩的彈匣</li>
 *   <li>{@link SkillExecutor}：詛咒（curseGround、cursePlaced）、牆、裂縫、跳躍落地；{@link SkillDeployables}：圖騰；{@link MinionService}：召喚物</li>
 *   <li>戰鬥、異常、暴擊、偷取、翻滾、殘骸、完美時機：CombatListener、AilmentService、DamageCalculator、DodgeRoll、ChargeEffects、Signatures</li>
 * </ul>
 */
public final class LineageMechanics implements Listener {

    private final ExileCore plugin;
    private final SkillService service;

    LineageMechanics(final ExileCore plugin, final SkillService service) {
        this.plugin = plugin;
        this.service = service;
    }

    private long now() {
        return plugin.getServer().getCurrentTick();
    }

    private static String key(final Player player, final String skillId) {
        return player.getUniqueId() + "/" + skillId;
    }

    static boolean hasLineage(final List<SupportDefinition> supports) {
        for (final SupportDefinition support : supports) {
            if (support != null && support.lineage()) {
                return true;
            }
        }
        return false;
    }

    /** 技能面板上這個技能掛的輔助（宿主孔裡的技能用宿主那一格的；找不到回傳空的）。 */
    private List<SupportDefinition> supportsOn(final Player player, final String skillId) {
        if (player == null || skillId == null) {
            return List.of();
        }
        final PlayerState state = plugin.players().get(player);
        if (!state.isLoaded() || !state.hasCharacter()) {
            return List.of();
        }
        final String id = SupportProcs.baseId(skillId);
        int position = state.skills().find(id);
        if (position < 0) {
            position = state.skills().findInner(id);
        }
        return position < 0 ? List.of() : service.supportsOf(state.skills().skill(position));
    }

    /** 面板上掛了這個屬性的技能（代號 → 數值加總）。 */
    private Map<String, Double> panel(final Player player, final String stat) {
        final PlayerState state = plugin.players().get(player);
        if (!state.isLoaded() || !state.hasCharacter()) {
            return Map.of();
        }
        Map<String, Double> found = null;
        for (int i = 0; i < SkillLoadout.MAX_SKILLS; i++) {
            final SkillLoadout.Entry entry = state.skills().skill(i);
            if (entry == null) {
                continue;
            }
            final double value = LineageRules.total(service.supportsOf(entry), stat);
            if (value != 0) {
                if (found == null) {
                    found = new HashMap<>();
                }
                found.put(entry.skillId(), value);
            }
        }
        return found == null ? Map.of() : found;
    }

    // =====================================================================
    // 放技能
    // =====================================================================

    /** 弩的彈匣（連膛：不會自己裝填）：沒子彈就不能放。可以放回傳 null。 */
    String usageProblem(final Player player, final SkillBuild build) {
        if (LineageRules.total(build.supports(), LineageKeys.NO_RELOAD) > 0 && SkillService.ammoSkill(build.definition())) {
            final Integer left = service.runtime(player).ammo.get(build.definition().id());
            if (left != null && left <= 0) {
                return build.definition().name() + " 的彈匣空了：翻滾來裝填";
            }
        }
        return null;
    }

    /**
     * 放之前（扣完消耗、算攻擊間隔之前；SkillService 呼叫）：血滲的額外物理、狂伐的攻擊速度、傲寒的守護消耗、積勢的範圍、雪封的封印。
     */
    SkillBuild prepare(final Player player, final SkillBuild build) {
        final List<SupportDefinition> supports = build.supports();
        if (!hasLineage(supports)) {
            return build;
        }
        final String id = build.definition().id();
        final List<Modifier> mods = new ArrayList<>();
        // 血滲（Atalui's Bloodletting）：每 20 點生命消耗獲得 2% 傷害為額外物理傷害，最多 40%
        final double perLife = LineageRules.total(supports, LineageKeys.PHYS_PER_LIFE_COST);
        if (perLife > 0) {
            final double percent = bloodlettingPercent(build.manaCost(), perLife);
            if (percent > 0) {
                mods.add(Modifier.flat(StatKeys.damageExtra(DamageType.PHYSICAL), percent, "血滲"));
            }
        }
        // 狂伐（Medved's Felling）：每 5 點怒氣增加 5% 攻擊速度
        final double perRage = LineageRules.total(supports, LineageKeys.SPEED_PER_5_RAGE);
        if (perRage > 0) {
            final int rage = service.resources().rage(player);
            if (rage >= 5) {
                mods.add(Modifier.increased(StatKeys.ATTACK_SPEED, Math.floor(rage / 5.0) * perRage / 100.0, "狂伐"));
            }
        }
        // 傲寒（Olroth's Hubris）：另外消耗 20% 最大魔力，附加這筆消耗 15% 的物理與冰冷傷害
        final double hubris = LineageRules.total(supports, LineageKeys.HUBRIS);
        if (hubris > 0) {
            final PlayerState state = plugin.players().get(player);
            final double cost = state.maxMana() * hubris / 100.0;
            if (cost > 0 && state.mana() + 1e-9 >= cost) {
                state.setMana(state.mana() - cost);
                plugin.players().display(player, state);
                mods.addAll(hubrisAdded(cost));
            }
        }
        SkillBuild result = mods.isEmpty() ? build : build.withProfile(build.profile().withLocal(mods));
        // 積勢（Vruun's Inevitability）：用掉連擊，每點增加 6% 範圍
        final double perCombo = LineageRules.total(supports, LineageKeys.COMBO_AREA);
        if (perCombo > 0) {
            final int combo = takeCombo(player, id);
            if (combo > 0) {
                result = result.withAreaAndProjectiles(result.areaScale() * Math.sqrt(1 + perCombo * combo / 100.0), result.projectiles());
                notice(player, String.format(Locale.ROOT, "<#c7a6ff>%s 用掉 %d 點積勢連擊", build.definition().name(), combo));
            }
        }
        // 雪封（Tul's Avalanche）：解開格擋蓄的封印，放完之後每個回響一次
        if (LineageRules.total(supports, LineageKeys.BLOCK_SEALS) > 0) {
            final Integer seals = blockSeals.remove(key(player, id));
            if (seals != null && seals > 0) {
                pendingEchoes.put(player.getUniqueId(), seals);
                notice(player, String.format(Locale.ROOT, "<#c7a6ff>%s 解開 %d 個雪封", build.definition().name(), seals));
            }
        }
        return result;
    }

    /**
     * 2026-10-27：面板、技能說明、網頁的估計用（不扣魔力、不看當下的魔力夠不夠）：血滲的額外物理、傲寒（照滿魔力付得起）的附加物理與冰冷，
     * 跟 {@link #prepare} 放的時候加的一樣。這次之前估計完全沒算這兩個（戰鬥有）。純函式（測試用）。
     *
     * @param maxMana 最大魔力（傲寒的消耗照這個算）
     */
    static List<Modifier> estimateModifiers(final SkillBuild build, final double maxMana) {
        final List<SupportDefinition> supports = build.supports();
        if (!hasLineage(supports)) {
            return List.of();
        }
        final List<Modifier> mods = new ArrayList<>();
        final double perLife = LineageRules.total(supports, LineageKeys.PHYS_PER_LIFE_COST);
        if (perLife > 0) {
            final double percent = bloodlettingPercent(build.manaCost(), perLife);
            if (percent > 0) {
                mods.add(Modifier.flat(StatKeys.damageExtra(DamageType.PHYSICAL), percent, "血滲"));
            }
        }
        final double hubris = LineageRules.total(supports, LineageKeys.HUBRIS);
        if (hubris > 0 && maxMana > 0) {
            mods.addAll(hubrisAdded(maxMana * hubris / 100.0));
        }
        return mods;
    }

    /** 同上，疊進技能（沒有就原樣）。 */
    static SkillBuild estimated(final SkillBuild build, final double maxMana) {
        final List<Modifier> mods = estimateModifiers(build, maxMana);
        return mods.isEmpty() ? build : build.withProfile(build.profile().withLocal(mods));
    }

    /** 血滲：生命消耗換成的額外物理傷害 %（每 20 點 per%，最多 40%）。 */
    static double bloodlettingPercent(final double lifeCost, final double per) {
        return Math.min(40, Math.floor(Math.max(0, lifeCost) / 20.0) * per);
    }

    /** 傲寒：附加的物理、冰冷傷害（這筆消耗的 15%）。 */
    static List<Modifier> hubrisAdded(final double cost) {
        final double added = cost * 0.15;
        final Set<String> physical = Set.copyOf(DamageType.PHYSICAL.tags());
        final Set<String> cold = Set.copyOf(DamageType.COLD.tags());
        return List.of(new Modifier(StatKeys.DAMAGE_ADDED_MIN, ModType.FLAT, added, physical, "傲寒"),
                new Modifier(StatKeys.DAMAGE_ADDED_MAX, ModType.FLAT, added, physical, "傲寒"),
                new Modifier(StatKeys.DAMAGE_ADDED_MIN, ModType.FLAT, added, cold, "傲寒"),
                new Modifier(StatKeys.DAMAGE_ADDED_MAX, ModType.FLAT, added, cold, "傲寒"));
    }

    /** 最近用過的冷卻技能、指令技能（玩家 → 技能 → tick）：星律、令福。 */
    private final Map<UUID, Map<String, Long>> cooldownUsed = new HashMap<>();
    private final Map<UUID, Map<String, Long>> commandUsed = new HashMap<>();
    /** 滿溢聖杯到哪個 tick。 */
    private final Map<UUID, Long> chalice = new HashMap<>();
    /** 縛魔：召喚物的不潔之力到哪個 tick（召喚物的實體）。 */
    private final Map<UUID, Long> unholyMinions = new HashMap<>();

    /** 真的放了（扣完消耗；SupportMechanics.used 呼叫）。 */
    void used(final Player player, final SkillBuild build) {
        final long now = now();
        final String id = build.definition().id();
        if (build.cooldownTicks() > 0) {
            cooldownUsed.computeIfAbsent(player.getUniqueId(), uuid -> new HashMap<>()).put(id, now);
        }
        final List<String> commanded = service.config().command(id);
        if (commanded != null) {
            commandUsed.computeIfAbsent(player.getUniqueId(), uuid -> new HashMap<>()).put(id, now);
        }
        final List<SupportDefinition> supports = build.supports();
        if (!hasLineage(supports)) {
            return;
        }
        final PlayerState state = plugin.players().get(player);
        boolean changed = false;
        // 焦血（Atziri's Impatience）：失去 25% 最大生命、魔力、能量護盾
        final double lose = LineageRules.total(supports, LineageKeys.LOSE_ON_USE);
        if (lose > 0) {
            state.setLife(Math.max(1, state.life() - state.maxLife() * lose / 100.0));
            state.setMana(Math.max(0, state.mana() - state.maxMana() * lose / 100.0));
            state.setEnergyShield(Math.max(0, state.energyShield() - state.maxEnergyShield() * lose / 100.0));
            changed = true;
        }
        // 狂伐：每 5 點怒氣失去 1% 最大生命
        final double perRage = LineageRules.total(supports, LineageKeys.LIFE_PER_5_RAGE);
        if (perRage > 0) {
            final double loss = Math.floor(service.resources().rage(player) / 5.0) * perRage / 100.0 * state.maxLife();
            if (loss > 0) {
                state.setLife(Math.max(1, state.life() - loss));
                changed = true;
            }
        }
        // 聖杯（Uhtred's Rite）：滿溢聖杯（每 1 秒冷卻 1 秒）；魔力藥劑生效時魔力消耗效率增加 20%（退回那一部分）
        // 2026-10-27 照 PoE2 的效率公式（CostKeys）：跟其他效率加總再除——付的是 原本 ÷ (1 + E)，應該是 原本 ÷ (1 + E + 0.2)，退差額；
        // 這次之前固定退 1 − 1/1.2（沒算其他效率）
        final double cup = LineageRules.total(supports, LineageKeys.CHALICE);
        if (cup > 0 && build.cooldownTicks() > 0) {
            final int ticks = (int) Math.round(build.cooldownTicks() * cup);
            chalice.merge(player.getUniqueId(), now + ticks, Math::max);
            service.buff(player, "lineage_chalice", "滿溢聖杯", ticks, List.of(
                    new Modifier(StatKeys.FLASK_LIFE_RECOVERY, ModType.INCREASED, 0.3, Set.of(), "滿溢聖杯"),
                    new Modifier(StatKeys.FLASK_MANA_RECOVERY, ModType.INCREASED, 0.3, Set.of(), "滿溢聖杯")));
        }
        final double efficiency = LineageRules.total(supports, LineageKeys.MANA_FLASK_EFFICIENCY);
        if (efficiency > 0 && build.manaCost() > 0 && plugin.flasks() != null && plugin.flasks().recovering(player, tw.exilecore.flask.FlaskKind.MANA)) {
            final double others = tw.exilecore.stats.CostKeys.manaEfficiency(build.profile().effective(state.sheet()), build.profile().tags());
            final double paidShare = tw.exilecore.stats.CostKeys.divisor(others) / tw.exilecore.stats.CostKeys.divisor(others + efficiency / 100.0);
            state.setMana(Math.min(state.maxMana(), state.mana() + build.manaCost() * (1 - paidShare)));
            changed = true;
        }
        // 縛魔（Kurgal's Leash）：指揮時你與聽命的召喚物獲得不潔之力（30% 額外混沌）
        final double unholy = LineageRules.total(supports, LineageKeys.UNHOLY_ON_COMMAND);
        if (unholy > 0 && commanded != null) {
            final int ticks = (int) Math.round(unholy * 20);
            service.buff(player, "lineage_unholy", "不潔之力", ticks, unholyMight());
            for (final MinionService.Minion minion : service.minions().all()) {
                if (minion.owner.equals(player.getUniqueId()) && (commanded.contains("any") || commanded.contains(minion.skillId()))) {
                    unholyMinions.put(minion.mob.getUniqueId(), now + ticks);
                }
            }
        }
        if (changed) {
            plugin.players().display(player, state);
        }
    }

    /** 不潔之力：獲得 30% 傷害為額外混沌傷害。 */
    static List<Modifier> unholyMight() {
        return List.of(Modifier.flat(StatKeys.damageExtra(DamageType.CHAOS), 30, "不潔之力"));
    }

    /** 碎星的層數、幻分的幻身（玩家/技能）。 */
    private final Map<String, Integer> wildshards = new HashMap<>();
    private final Map<String, List<Mimic>> mimics = new HashMap<>();
    /** 雪封這一次要回響幾次（prepare 放進來，afterCast 拿走）。 */
    private final Map<UUID, Integer> pendingEchoes = new HashMap<>();

    private record Mimic(Location feet, Location eye, Vector direction, long until) {
    }

    /** 放完之後（SupportMechanics.afterEmpowered 呼叫）：碎星、三迴、雪封、幻分、祭脈。 */
    void afterCast(final Player player, final SkillBuild build, final LivingEntity preferred) {
        if (build == null || build.definition().isAttack() && !hasLineage(build.supports())) {
            return;
        }
        final Origin origin = Origin.of(player);
        if (LineageRules.cascadable(build.definition())) {
            offeringCascade(player, build, origin, preferred);
        }
        final List<SupportDefinition> supports = build.supports();
        final Integer sealed = pendingEchoes.remove(player.getUniqueId());
        if (!hasLineage(supports)) {
            return;
        }
        final String key = key(player, build.definition().id());
        // 碎星（Sione's Temper）：每層 20% 機率多往四周射 8 發
        final double shard = LineageRules.total(supports, LineageKeys.WILDSHARD);
        if (shard > 0) {
            final int stacks = wildshards.merge(key, 1, Integer::sum);
            if (plugin.random().nextDouble() * 100 < shard * stacks) {
                wildshards.remove(key);
                final SkillBuild nova = ring(player, build);
                if (nova != null) {
                    service.procCast(new Origin(player, origin.feet(), origin.eye(), origin.direction(), false), nova, null);
                }
            }
        }
        // 三迴（Zarokh's Refrain）、雪封（Tul's Avalanche）：回響（同一個地方再放，每次隔 0.3 秒）
        int echoes = (int) Math.round(LineageRules.total(supports, LineageKeys.ECHOES)) + (sealed == null ? 0 : sealed);
        if (echoes > 0) {
            final double echoArea = LineageRules.total(supports, LineageKeys.ECHO_AREA) / 100.0;
            final SkillBuild echo = echoArea > 0 ? build.withAreaAndProjectiles(build.areaScale() * Math.sqrt(1 + echoArea), build.projectiles()) : build;
            echoes = Math.min(8, echoes);
            for (int i = 1; i <= echoes; i++) {
                later(player, origin, echo, preferred, 6L * i);
            }
        }
        // 幻分（Trickster's Shard）：留下幻身，到上限時全部一起放（傷害更少 50%）
        final int limit = (int) Math.round(LineageRules.total(supports, LineageKeys.MIMIC));
        if (limit > 0 && build.definition().castTime() > 0) {
            final long now = now();
            final List<Mimic> list = mimics.computeIfAbsent(key, k -> new ArrayList<>());
            list.removeIf(mimic -> mimic.until() < now || !mimic.feet().getWorld().equals(player.getWorld()));
            final double angle = plugin.random().nextDouble() * Math.PI * 2;
            final Location feet = origin.feet().clone().add(Math.cos(angle) * 1.5, 0, Math.sin(angle) * 1.5);
            list.add(new Mimic(feet, feet.clone().add(0, 1.5, 0), origin.direction().clone(), now + MIMIC_TICKS));
            Fx.spawn(feet.getWorld(), Particle.END_ROD, feet.clone().add(0, 1, 0), 12, 0.2, 0.6, 0.2, 0.01);
            if (list.size() >= limit) {
                final SkillBuild half = build.withProfile(build.profile().scaled(0.5, "幻身"));
                int i = 0;
                for (final Mimic mimic : List.copyOf(list)) {
                    later(player, new Origin(player, mimic.feet(), mimic.eye(), mimic.direction(), false), half, preferred, 2L * i++);
                    Fx.spawn(mimic.feet().getWorld(), Particle.END_ROD, mimic.feet().clone().add(0, 1, 0), 8, 0.2, 0.4, 0.2, 0.05);
                }
                list.clear();
                notice(player, "<#c7a6ff>" + build.definition().name() + "：幻身一起施放");
            }
        }
    }

    /** 幻身撐幾 tick（PoE2 5 秒）。 */
    static final long MIMIC_TICKS = 100;

    private void later(final Player player, final Origin origin, final SkillBuild build, final LivingEntity target, final long delay) {
        plugin.getServer().getScheduler().runTaskLater(plugin, () -> {
            if (player.isOnline() && !player.isDead() && player.getWorld().equals(origin.world())) {
                service.procCast(origin, build, target != null && target.isValid() ? target : null);
            }
        }, Math.max(1, delay));
    }

    /** 碎星的環射：同一個法術往四周一圈射 8 發（傷害照原本這一下）。 */
    private SkillBuild ring(final Player player, final SkillBuild build) {
        if (build.definition().mechanic() != Mechanic.PROJECTILE) {
            return null;
        }
        final PlayerState state = plugin.players().get(player);
        final int base = build.definition().params().projectiles().intAt(build.level());
        final SupportDefinition carrier = new SupportDefinition("lineage.ring", "碎星", "", "PAPER", 1, Set.of(), Set.of(), 1.0,
                List.of(Modifier.flat(StatKeys.SKILL_PROJECTILES, 8 - base, "碎星"), Modifier.flat(StatKeys.FLAG_PROJECTILE_NOVA, 1, "碎星")), List.of());
        final SkillBuild nova = SkillBuild.of(service.config(), build.definition(), build.level(), state.level(), false, List.of(carrier),
                state.weaponTags(), plugin.players().sheet(player));
        return nova.withProfile(build.profile());
    }

    /** 祭脈（Kulemak's Dominion）：範圍法術也在掛了祭脈的祭柱那裡放一次（傷害更少 50%）。 */
    private void offeringCascade(final Player player, final SkillBuild build, final Origin origin, final LivingEntity preferred) {
        final Map<String, Double> offerings = panel(player, LineageKeys.OFFERING_CASCADE);
        if (offerings.isEmpty()) {
            return;
        }
        final Map<String, Location> spots = service.warcries().offeringSpots(player.getUniqueId());
        for (final Map.Entry<String, Double> entry : offerings.entrySet()) {
            final Location spot = spots.get(entry.getKey());
            if (spot == null || !spot.getWorld().equals(player.getWorld())) {
                continue;
            }
            final SkillBuild cascade = build.withProfile(build.profile().scaled(Math.max(0, 1 + entry.getValue() / 100.0), "祭脈"));
            // 往下看：落在準心的法術就落在祭柱上，以自己為中心的就以祭柱為中心
            final Location feet = spot.clone();
            service.procCast(new Origin(player, feet, feet.clone().add(0, 1.2, 0), new Vector(0, -1, 0), false), cascade, null);
            Fx.spawn(spot.getWorld(), Particle.SOUL, spot.clone().add(0, 1, 0), 10, 0.3, 0.6, 0.3, 0.02);
        }
    }

    /** 主要寶石觸發出來的技能（SkillService.metaCast）：骰禍的隨機範圍。 */
    SkillBuild triggered(final SkillBuild build) {
        if (LineageRules.total(build.supports(), LineageKeys.RANDOM_AREA) <= 0) {
            return build;
        }
        final double more = -0.4 + plugin.random().nextDouble() * 0.9; // 更少 40% 到更多 50%
        return build.withAreaAndProjectiles(build.areaScale() * Math.sqrt(1 + more), build.projectiles());
    }

    /** 冷卻可以多存幾次（裂潮、星律；SkillRuntime 的 cooldownUses）。 */
    int cooldownUses(final Player player, final String skillId) {
        return player == null ? 0 : (int) Math.round(Math.max(0, LineageRules.total(supportsOn(player, skillId), LineageKeys.COOLDOWN_USES)));
    }

    // =====================================================================
    // 擊中前（SupportMechanics.preHit）
    // =====================================================================

    /** 自然交換的冷卻（玩家 → 好了的 tick）、律勁的層數、舉盾時格擋的 tick。 */
    private final Map<UUID, Long> natureReady = new HashMap<>();
    private final Map<UUID, Integer> rhythm = new HashMap<>();
    private final Map<UUID, Long> shieldBlocked = new HashMap<>();

    /** 擊中前的條件加成（玩家打的）。 */
    List<Modifier> preHit(final Player attacker, final LivingEntity defender, final StatSheet skillSheet, final Set<String> tags) {
        final List<Modifier> list = new ArrayList<>();
        final long now = now();
        // 毒慾（Arakaali's Lust）：每層中毒更多 8%（最多 40%）
        final double perPoison = skillSheet.flat(LineageKeys.PER_POISON, tags);
        if (perPoison > 0 && plugin.ailments() != null) {
            final int stacks = plugin.ailments().poisonStacks(defender);
            if (stacks > 0) {
                list.add(Modifier.more(StatKeys.DAMAGE, Math.min(40, perPoison * stacks) / 100.0, "毒慾"));
            }
        }
        // 掠獸（Einhar's Beastrite）：只能傷害低血的敵人
        if (StatKeys.flag(skillSheet, LineageKeys.LOW_LIFE_ONLY) && !lowLife(defender)) {
            list.add(Modifier.more(StatKeys.DAMAGE, -1.0, "掠獸（不是低血）"));
        }
        // 霜換（Mórrigan's Insight）：用掉凍結，更多 50%，觸發自然交換
        final double consume = skillSheet.flat(LineageKeys.CONSUME_FREEZE, tags);
        if (consume > 0 && plugin.ailments() != null && plugin.ailments().isFrozen(defender)
                && plugin.ailments().consume(defender, AilmentType.FREEZE, attacker)) {
            list.add(Modifier.more(StatKeys.DAMAGE, consume / 100.0, "霜換"));
            natureExchange(attacker, now);
        }
        // 怒準（Prototype Seventeen）：每 5 點怒氣增加 20% 暴擊率
        final double critPerRage = skillSheet.flat(LineageKeys.CRIT_PER_5_RAGE, tags);
        if (critPerRage > 0) {
            final int rage = service.resources().rage(attacker);
            if (rage >= 5) {
                list.add(Modifier.increased(StatKeys.CRIT_CHANCE, Math.floor(rage / 5.0) * critPerRage / 100.0, "怒準"));
            }
        }
        // 星律（Uhtred's Constellation）：最近 8 秒每用過一種冷卻技能更多 8%
        final double perCooldown = skillSheet.flat(LineageKeys.PER_COOLDOWN_SKILL, tags);
        if (perCooldown > 0) {
            final int kinds = recentKinds(cooldownUsed.get(attacker.getUniqueId()), now, 160);
            if (kinds > 0) {
                list.add(Modifier.more(StatKeys.DAMAGE, perCooldown * kinds / 100.0, "星律"));
            }
        }
        // 令福（Varashta's Blessing）：最近 15 秒每用過一種指令技能更多 10%
        final double perCommand = skillSheet.flat(LineageKeys.PER_COMMAND, tags);
        if (perCommand > 0) {
            final int kinds = recentKinds(commandUsed.get(attacker.getUniqueId()), now, 300);
            if (kinds > 0) {
                list.add(Modifier.more(StatKeys.DAMAGE, perCommand * kinds / 100.0, "令福"));
            }
        }
        // 聖杯：生命藥劑生效時更多 20%
        final double lifeFlask = skillSheet.flat(LineageKeys.LIFE_FLASK_MORE, tags);
        if (lifeFlask > 0 && plugin.flasks() != null && plugin.flasks().recovering(attacker, tw.exilecore.flask.FlaskKind.LIFE)) {
            list.add(Modifier.more(StatKeys.DAMAGE, lifeFlask / 100.0, "聖杯"));
        }
        // 孤圍（Vorana's Siege）：6 格內沒有其他敵人
        final double isolated = skillSheet.flat(LineageKeys.VS_ISOLATED, tags);
        if (isolated > 0 && isolated(attacker, defender)) {
            list.add(Modifier.more(StatKeys.DAMAGE, isolated / 100.0, "孤立"));
        }
        // 律勁（Tasalio's Rhythm）：每層更多 10%
        final double perRhythm = skillSheet.flat(LineageKeys.RHYTHM, tags);
        final int stacks = rhythm.getOrDefault(attacker.getUniqueId(), 0);
        if (perRhythm > 0 && stacks > 0) {
            list.add(Modifier.more(StatKeys.DAMAGE, perRhythm * stacks / 100.0, "律勁"));
        }
        // 岳盾（Styrn's Mountain）：舉盾時格擋過，4 秒內其他近戰技能更多 20%
        final Long blocked = shieldBlocked.get(attacker.getUniqueId());
        if (blocked != null && now - blocked <= 80 && tags.contains(Tags.MELEE) && !tags.contains("channelling")) {
            final double more = LineageRules.total(supportsOnAny(attacker, LineageRules.RAISE_SHIELD), LineageKeys.SHIELD_BLOCK_MORE);
            if (more > 0) {
                list.add(Modifier.more(StatKeys.DAMAGE, more / 100.0, "岳盾"));
            }
        }
        return list;
    }

    /** 面板上第一個在 ids 裡的技能掛的輔助。 */
    private List<SupportDefinition> supportsOnAny(final Player player, final Set<String> ids) {
        for (final String id : ids) {
            final List<SupportDefinition> found = supportsOn(player, id);
            if (!found.isEmpty()) {
                return found;
            }
        }
        return List.of();
    }

    /** 低血：35% 以下（原版血量只是比例）。 */
    static boolean lowLife(final LivingEntity entity) {
        final org.bukkit.attribute.AttributeInstance max = entity.getAttribute(org.bukkit.attribute.Attribute.MAX_HEALTH);
        return max != null && max.getValue() > 0 && entity.getHealth() <= max.getValue() * 0.35 + 1e-9;
    }

    /** 孤立：6 格內沒有其他敵人。 */
    private boolean isolated(final Player attacker, final LivingEntity defender) {
        for (final LivingEntity other : service.targets().around(defender.getLocation(), 6, attacker)) {
            if (!other.getUniqueId().equals(defender.getUniqueId())) {
                return false;
            }
        }
        return true;
    }

    /** 最近 window tick 內用過幾種（同一種只算一次）。 */
    static int recentKinds(final Map<String, Long> used, final long now, final long window) {
        if (used == null) {
            return 0;
        }
        int kinds = 0;
        for (final long tick : used.values()) {
            if (now - tick <= window) {
                kinds++;
            }
        }
        return kinds;
    }

    /** 霜換的自然交換（冷卻 8 秒）：8 秒內獲得守護與冰冷荊棘（照角色能用的技能等級）。 */
    private void natureExchange(final Player player, final long now) {
        if (natureReady.getOrDefault(player.getUniqueId(), 0L) > now) {
            return;
        }
        natureReady.put(player.getUniqueId(), now + 160);
        final PlayerState state = plugin.players().get(player);
        final int level = Math.max(1, Math.min(40, service.config().maxLevelFor(state.level())));
        state.addGuard(natureGuard(level), now + 160);
        final double[] thorns = natureThorns(level);
        service.buff(player, "lineage_natures_exchange", "自然交換", 160, List.of(
                Modifier.flat(tw.exilecore.item.unique.UniqueStats.THORNS_COLD_MIN, thorns[0], "自然交換"),
                Modifier.flat(tw.exilecore.item.unique.UniqueStats.THORNS_COLD_MAX, thorns[1], "自然交換")));
        plugin.players().display(player, state);
        Fx.spawn(player.getWorld(), Particle.SNOWFLAKE, player.getLocation().add(0, 1, 0), 20, 0.4, 0.6, 0.4, 0.02);
    }

    /** 自然交換的守護（poe2db：1 級 16、20 級 531、40 級 9275）。 */
    static double natureGuard(final int level) {
        return NATURE_GUARD[Math.max(1, Math.min(NATURE_GUARD.length, level)) - 1];
    }

    /** 自然交換的冰冷荊棘（poe2db：1 級 3–5、20 級 106–159）。 */
    static double[] natureThorns(final int level) {
        final int i = Math.max(1, Math.min(NATURE_THORNS.length, level)) - 1;
        return NATURE_THORNS[i].clone();
    }

    private static final double[] NATURE_GUARD = {16, 21, 29, 38, 48, 59, 72, 86, 103, 122, 143, 167, 195, 226, 262, 302, 349, 402, 462, 531,
            610, 701, 805, 925, 1062, 1221, 1403, 1614, 1857, 2139, 2466, 2845, 3285, 3797, 4393, 5089, 5902, 6853, 7968, 9275};
    private static final double[][] NATURE_THORNS = {{3, 5}, {4, 6}, {6, 9}, {8, 11}, {10, 14}, {12, 18}, {14, 22}, {17, 26}, {21, 31},
            {24, 37}, {29, 43}, {33, 50}, {39, 58}, {45, 68}, {52, 79}, {60, 91}, {70, 105}, {80, 121}, {92, 139}, {106, 159}, {122, 183},
            {140, 210}, {161, 242}, {185, 277}, {212, 319}, {244, 366}, {281, 421}, {323, 484}, {371, 557}, {428, 642}, {493, 740},
            {569, 853}, {657, 985}, {759, 1139}, {879, 1318}, {1018, 1527}, {1180, 1771}, {1371, 2056}, {1594, 2390}, {1855, 2783}};

    /** 異常用的攻擊方屬性表（CombatListener 呼叫）：怒毒（每點怒氣讓中毒快 2%）。 */
    public StatSheet ailmentSheet(final LivingEntity attacker, final StatSheet sheet, final StatSheet skillSheet, final SkillProfile skill) {
        if (!(attacker instanceof Player player) || skill == null) {
            return sheet;
        }
        final double perRage = skillSheet.flat(LineageKeys.POISON_FASTER_PER_RAGE, skill.tags());
        if (perRage <= 0) {
            return sheet;
        }
        final int rage = service.resources().rage(player);
        return rage <= 0 ? sheet : sheet.overlay(List.of(new Modifier(tw.exilecore.stats.TreeEffectKeys.AILMENT_SPEED, ModType.INCREASED,
                rage * perRage / 100.0, Set.copyOf(DamageType.CHAOS.tags()), "怒毒")));
    }

    // =====================================================================
    // 擊中之後
    // =====================================================================

    /** 積勢：玩家/技能 → {連擊, 最後累積的 tick}。 */
    private final Map<String, long[]> combos = new HashMap<>();
    static final int COMBO_MAX = 10;
    static final long COMBO_DECAY = 120;

    private int takeCombo(final Player player, final String skillId) {
        final long[] combo = combos.remove(key(player, skillId));
        return combo == null || now() - combo[1] > COMBO_DECAY ? 0 : (int) combo[0];
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onHit(final HitEvent event) {
        if (!(event.attacker() instanceof Player player) || event.skill() == null || !event.result().hit() || event.result().blocked()
                || event.defender() instanceof Player || plugin.monsters() == null) {
            return;
        }
        final SkillProfile skill = event.skill();
        final Set<String> tags = skill.tags();
        final String skillId = SupportProcs.baseId(skill.id());
        final long now = now();
        // 積勢：用其他近戰攻擊打中時，掛了積勢的近戰攻擊累積連擊
        if (tags.contains(Tags.MELEE) && tags.contains(Tags.ATTACK) && !tags.contains(Tags.TOTEM_HIT)) {
            for (final Map.Entry<String, Double> entry : panel(player, LineageKeys.COMBO_AREA).entrySet()) {
                if (entry.getKey().equals(skillId)) {
                    continue;
                }
                final long[] combo = combos.computeIfAbsent(key(player, entry.getKey()), k -> new long[2]);
                if (now - combo[1] > COMBO_DECAY) {
                    combo[0] = 0;
                }
                combo[0] = Math.min(COMBO_MAX, combo[0] + 1);
                combo[1] = now;
            }
        }
        final LivingEntity target = event.defender();
        final MonsterData monster = plugin.monsters().data(target).orElse(null);
        if (monster == null) {
            return;
        }
        final StatSheet effective = skill.effective(plugin.players().sheet(player));
        // 碾盾（Styrn's Ferocity）：壓碎 8 秒；打壓碎中的敵人破甲（物理傷害的 10%）
        final double crushSeconds = effective.flat(LineageKeys.CRUSH_ON_HIT, tags);
        final Debuffs debuffs = plugin.combat() == null ? null : plugin.combat().debuffs();
        if (debuffs != null) {
            final double crushedBreak = effective.flat(LineageKeys.CRUSHED_BREAK, tags);
            if (crushedBreak > 0 && debuffs.has(target.getUniqueId(), CRUSH_ID, now)) {
                breakArmour(player, skillId, target, monster, event.result().dealt().get(DamageType.PHYSICAL) * crushedBreak / 100.0);
            }
            if (crushSeconds > 0) {
                debuffs.apply(target.getUniqueId(), new Debuffs.Debuff(CRUSH_ID, player.getUniqueId(), now + Math.round(crushSeconds * 20), 0, false, null,
                        List.of(Modifier.flat(tw.exilecore.item.unique.UniqueStats.CRUSHED, CRUSHED_PERCENT, "壓碎")), 0));
            }
        }
        // 蝕甲（Uul-Netol's Embrace）：混沌傷害的 20% 也破甲
        final double chaosBreak = effective.flat(LineageKeys.CHAOS_BREAKS, tags);
        if (chaosBreak > 0) {
            breakArmour(player, skillId, target, monster, event.result().dealt().get(DamageType.CHAOS) * chaosBreak / 100.0);
        }
        // 狂伐：近戰擊中獲得最大怒氣 10% 的怒氣
        final double rageHit = effective.flat(LineageKeys.RAGE_ON_MELEE_HIT, tags);
        if (rageHit > 0 && tags.contains(Tags.MELEE)) {
            service.resources().gainRage(player, service.resources().rageMax(plugin.players().sheet(player)) * rageHit / 100.0);
        }
        // 怒準：暴擊時失去全部怒氣
        if (event.result().crit() && effective.flat(LineageKeys.CRIT_PER_5_RAGE, tags) > 0) {
            service.resources().spendRage(player, Warcries.RAGE_FEED_CAP);
        }
    }

    static final String CRUSH_ID = "lineage_crush";
    /** 壓碎：物理傷害減免少 15%（PoE2 Crushed）。 */
    static final double CRUSHED_PERCENT = 15;

    /** 破甲（照這一下的量；讓牠完全破甲時照一般的「完全破甲時」事件走）。 */
    private void breakArmour(final Player player, final String skillId, final LivingEntity target, final MonsterData monster, final double amount) {
        if (amount <= 0 || plugin.ailments() == null) {
            return;
        }
        final double armour = plugin.monsters().sheet(target, monster).get(StatKeys.ARMOUR);
        if (plugin.ailments().breakArmour(target, monster, amount, 100, armour)) {
            service.procEvent(player, skillId, "armour_broken", null, target);
        }
    }

    /** 輔助的事件（SupportMechanics.event）：凍結、電擊時（凝破）、完全破甲時（熔甲、凝破）、召喚物死掉（淵賜）。 */
    void event(final Player player, final String skillId, final String event, final LivingEntity target) {
        final List<SupportDefinition> supports = supportsOn(player, skillId);
        if (!hasLineage(supports)) {
            return;
        }
        final long now = now();
        switch (event) {
            case "freeze", "electrocute" -> {
                // 凝破（Breachlord's Rift）：凍結、電擊時破掉牠 50% 的護甲
                final double percent = LineageRules.total(supports, LineageKeys.BREAK_ON_FREEZE);
                final MonsterData monster = target == null || plugin.monsters() == null ? null : plugin.monsters().data(target).orElse(null);
                if (percent > 0 && monster != null && plugin.ailments() != null) {
                    final double armour = plugin.monsters().sheet(target, monster).get(StatKeys.ARMOUR);
                    if (plugin.ailments().breakArmour(target, monster, armour * percent / 100.0, 100, armour)) {
                        service.procEvent(player, skillId, "armour_broken", null, target);
                    }
                }
            }
            case "armour_broken" -> {
                if (target == null || plugin.combat() == null) {
                    return;
                }
                final Debuffs debuffs = plugin.combat().debuffs();
                // 熔甲（Uruk's Smelting）：永久受到的物理傷害增加 5%（最多 20%）
                final double smelt = LineageRules.total(supports, LineageKeys.PHYS_TAKEN_ON_BREAK);
                if (smelt > 0) {
                    for (int i = 1; i * smelt <= SMELT_CAP + 1e-9; i++) {
                        if (!debuffs.has(target.getUniqueId(), SMELT_ID + i, now)) {
                            debuffs.apply(target.getUniqueId(), new Debuffs.Debuff(SMELT_ID + i, player.getUniqueId(), Long.MAX_VALUE / 4,
                                    smelt / 100.0, false, DamageType.PHYSICAL.tag()));
                            break;
                        }
                    }
                }
                // 凝破：完全破甲也讓冰冷、閃電擊中受到的傷害增加（跟物理一樣多），破甲結束為止
                if (LineageRules.total(supports, LineageKeys.BREAK_ON_FREEZE) > 0 && plugin.ailments() != null) {
                    final double taken = plugin.ailments().fullyBrokenTaken();
                    for (final DamageType type : new DamageType[] {DamageType.COLD, DamageType.LIGHTNING}) {
                        debuffs.apply(target.getUniqueId(), new Debuffs.Debuff(RIFT_ID + type.tag(), player.getUniqueId(), now + RIFT_TICKS,
                                taken, false, type.tag()));
                    }
                }
            }
            case "minion_death" -> {
                // 淵賜（Amanamu's Tithe）：50% 機率獲得一個怪物詞綴 20 秒（最多 3 個）
                final double chance = LineageRules.total(supports, LineageKeys.ABYSSAL_ON_MINION_DEATH);
                if (chance > 0 && plugin.random().nextDouble() * 100 < chance) {
                    abyssalGift(player, now);
                }
            }
            default -> { }
        }
    }

    static final String SMELT_ID = "lineage_smelt_";
    static final double SMELT_CAP = 20;
    static final String RIFT_ID = "lineage_rift_";
    /** 凝破的冰冷、閃電加成撐多久（跟完全破甲一樣 4 秒）。 */
    static final long RIFT_TICKS = 80;

    /** 淵賜：玩家 → 每個詞綴到哪個 tick。 */
    private final Map<UUID, List<Long>> gifts = new HashMap<>();

    private void abyssalGift(final Player player, final long now) {
        final List<Long> active = gifts.computeIfAbsent(player.getUniqueId(), uuid -> new ArrayList<>());
        active.removeIf(until -> until <= now);
        if (active.size() >= 3 || plugin.monsters() == null) {
            return;
        }
        final List<tw.exilecore.monster.MonsterConfig.MonsterMod> pool = new ArrayList<>();
        for (final tw.exilecore.monster.MonsterConfig.MonsterMod mod : plugin.monsters().config().mods()) {
            if (!modifiersOf(mod, "").isEmpty()) {
                pool.add(mod);
            }
        }
        if (pool.isEmpty()) {
            return;
        }
        final tw.exilecore.monster.MonsterConfig.MonsterMod mod = pool.get(plugin.random().nextInt(pool.size()));
        final int slot = active.size();
        active.add(now + 400);
        service.buff(player, "lineage_abyss." + slot, mod.name() + "（淵賜）", 400, modifiersOf(mod, "淵賜：" + mod.name()));
        notice(player, "<#9b6bff>淵賜：獲得「" + mod.name() + "」20 秒");
    }

    /** 怪物詞綴變成玩家的增益（跟斬獲腰環一樣的換法）。 */
    private static List<Modifier> modifiersOf(final tw.exilecore.monster.MonsterConfig.MonsterMod mod, final String source) {
        final List<Modifier> modifiers = new ArrayList<>();
        for (final tw.exilecore.item.AffixMod affixMod : mod.mods()) {
            modifiers.add(affixMod.toModifier(Map.of(), source));
        }
        for (final Map.Entry<DamageType, Double> entry : mod.addedFromPhysical().entrySet()) {
            modifiers.add(Modifier.flat(StatKeys.damageGain(entry.getKey()), entry.getValue() * 100, source));
        }
        if (mod.vanillaSpeedBonus() > 0) {
            modifiers.add(Modifier.increased(StatKeys.MOVEMENT_SPEED, mod.vanillaSpeedBonus(), source));
        }
        if (mod.aura() != null) {
            for (final tw.exilecore.item.AffixMod affixMod : mod.aura().stats()) {
                modifiers.add(affixMod.toModifier(Map.of(), source));
            }
        }
        return modifiers;
    }

    /** 異常（SkillTriggers.ailment）：燼裁（點燃時獲得元素執政）。 */
    void ailment(final Player player, final AilmentType type, final String skillId) {
        if (type != AilmentType.IGNITE || skillId == null || service.treeMechanics() == null) {
            return;
        }
        if (LineageRules.total(supportsOn(player, skillId), LineageKeys.ARCHON_ON_IGNITE) > 0) {
            service.treeMechanics().gainArchon(player, TreeMechanics.Archon.ELEMENTAL);
        }
    }

    // =====================================================================
    // 擊殺
    // =====================================================================

    @EventHandler(priority = EventPriority.LOW)
    public void onDeath(final EntityDeathEvent event) {
        final LivingEntity dead = event.getEntity();
        if (dead instanceof Player) {
            return;
        }
        if (!undying.isEmpty()) {
            feedSouls(dead);
        }
        final Player killer = dead.getKiller();
        if (killer == null || plugin.monsters() == null) {
            return;
        }
        final String skillId = service.lastSkill(dead, killer);
        final List<SupportDefinition> supports = supportsOn(killer, skillId);
        if (!hasLineage(supports)) {
            return;
        }
        final MonsterData monster = plugin.monsters().data(dead).orElse(null);
        if (monster == null) {
            return;
        }
        // 彈勳（Arjun's Medal）：25% 機率替所有弩技能裝一發
        final double load = LineageRules.total(supports, LineageKeys.LOAD_ON_KILL);
        if (load > 0 && plugin.random().nextDouble() * 100 < load) {
            service.loadBolt(killer);
        }
        // 掠獸（Einhar's Beastrite）：打死稀有怪物時獲得牠兩個詞綴
        final double steal = LineageRules.total(supports, LineageKeys.STEAL_TWO);
        if (steal > 0 && monster.rarity() == MonsterRarity.RARE && !monster.mods().isEmpty()) {
            stealTwo(killer, monster, steal);
        }
        // 霆爆（Eonyr's Thunder）：打死電擊中的敵人時炸開
        final double voltaic = LineageRules.total(supports, LineageKeys.VOLTAIC);
        if (voltaic > 0 && plugin.ailments() != null && plugin.ailments().electrocuted(dead)) {
            final int level = levelOf(killer, skillId);
            final double life = plugin.monsters().sheet(dead, monster).get(StatKeys.LIFE_MAX);
            explode(killer, dead.getLocation().add(0, 0.5, 0), voltaicRadius(level), life * voltaicPercent(voltaic, level) / 100.0,
                    DamageType.LIGHTNING, "voltaic_fulmination", Particle.ELECTRIC_SPARK);
        }
    }

    /** 霆爆：照技能等級 5% → 19%（每級約 0.74%）、1.5 → 2.5 格。 */
    static double voltaicPercent(final double base, final int level) {
        return Math.min(base + 14, base + (Math.max(1, level) - 1) * 14 / 19.0);
    }

    static double voltaicRadius(final int level) {
        return Math.min(2.5, 1.5 + (Math.max(1, level) - 1) / 19.0);
    }

    /** 這個技能在面板上的等級（找不到用角色能用的最高等級）。 */
    private int levelOf(final Player player, final String skillId) {
        final PlayerState state = plugin.players().get(player);
        final int position = state.skills().find(SupportProcs.baseId(skillId));
        final SkillLoadout.Entry entry = position < 0 ? null : state.skills().skill(position);
        return entry == null ? service.config().maxLevelFor(state.level()) : Math.min(entry.level(), service.config().maxLevelFor(state.level()));
    }

    private void stealTwo(final Player killer, final MonsterData monster, final double seconds) {
        final List<String> mods = new ArrayList<>(monster.mods());
        java.util.Collections.shuffle(mods, new java.util.Random(plugin.random().nextLong()));
        int taken = 0;
        for (final String modId : mods) {
            final tw.exilecore.monster.MonsterConfig.MonsterMod mod = plugin.monsters().config().mod(modId);
            if (mod == null) {
                continue;
            }
            final List<Modifier> modifiers = modifiersOf(mod, "掠獸：" + mod.name());
            if (modifiers.isEmpty()) {
                continue;
            }
            service.buff(killer, "lineage_steal." + mod.id(), mod.name() + "（掠獸）", (int) Math.round(seconds * 20), modifiers);
            if (++taken >= 2) {
                break;
            }
        }
        if (taken > 0) {
            notice(killer, "<#f5b942>掠獸：獲得 " + taken + " 個詞綴 " + (int) seconds + " 秒");
        }
    }

    /** 一個隱藏的爆炸（基礎傷害照 amount，吃你的加成）：霆爆、樁爆、燼躍。 */
    private void explode(final Player player, final Location at, final double radius, final double amount, final DamageType type,
                         final String id, final Particle particle) {
        if (amount <= 0 || at.getWorld() == null || plugin.combat() == null) {
            return;
        }
        final DamagePacket packet = new DamagePacket();
        packet.add(type, amount);
        final Set<String> tags = new HashSet<>(type.tags());
        tags.add(Tags.AREA);
        final SkillProfile profile = new SkillProfile(id, tags, 1.0, packet, packet, Map.of(),
                List.of(Modifier.flat(StatKeys.SPELL_BASE_CRIT, 5, "爆炸")));
        for (final LivingEntity target : service.targets().around(at, radius, player)) {
            plugin.combat().skillDamage().hit(player, target, profile, null);
        }
        Fx.spawn(at.getWorld(), particle, at, 30, radius * 0.4, 0.4, radius * 0.4, 0.05);
        Fx.spawn(at.getWorld(), Particle.EXPLOSION, at, 1, 0, 0, 0, 0);
        at.getWorld().playSound(at, Sound.ENTITY_GENERIC_EXPLODE, 0.6f, 1.4f);
    }

    // =====================================================================
    // 跳躍、圖騰、殘骸、翻滾、格擋、完美時機
    // =====================================================================

    /** 跳躍落地（SkillExecutor.land）：燼躍引爆落點的屍體。 */
    void landed(final Player player, final SkillBuild build, final Location at) {
        final double percent = LineageRules.total(build.supports(), LineageKeys.LANDING_DETONATE);
        if (percent <= 0 || plugin.combat() == null) {
            return;
        }
        final long now = now();
        final tw.exilecore.combat.Corpses corpses = plugin.combat().corpses();
        int blown = 0;
        for (final tw.exilecore.combat.Corpses.Corpse corpse : corpses.near(at.getWorld().getUID(), at.getX(), at.getZ(), LANDING_RADIUS, now)) {
            if (blown >= 8) {
                break;
            }
            corpses.remove(corpse);
            explode(player, new Location(at.getWorld(), corpse.x(), corpse.y() + 0.3, corpse.z()), 1.5, corpse.maxLife() * percent / 100.0,
                    DamageType.FIRE, "fiery_reclamation", Particle.FLAME);
            blown++;
        }
    }

    static final double LANDING_RADIUS = 3.5;

    /** 圖騰放技能之前（SkillDeployables）：林樁（附近每根圖騰更多傷害、增加範圍）。 */
    SkillBuild totemCast(final Player owner, final Location feet, final SkillBuild cast) {
        final double per = LineageRules.total(cast.supports(), LineageKeys.TOTEM_GROVE);
        if (per <= 0) {
            return cast;
        }
        final int near = groveCount(owner, feet);
        if (near <= 0) {
            return cast;
        }
        return cast.withProfile(cast.profile().scaled(1 + per * near / 100.0, "林樁"))
                .withAreaAndProjectiles(cast.areaScale() * Math.sqrt(1 + per / 2.0 * near / 100.0), cast.projectiles());
    }

    /** 林樁：圖騰每秒回復最大生命的幾 %（附近每根 1%）。 */
    double totemRegen(final Player owner, final SkillBuild build, final Location feet) {
        final double per = LineageRules.total(build.supports(), LineageKeys.TOTEM_GROVE);
        return per <= 0 ? 0 : groveCount(owner, feet) * per / 10.0;
    }

    /** 這根圖騰 4 格內你的其他圖騰（最多 5 根）。 */
    private int groveCount(final Player owner, final Location feet) {
        int count = 0;
        for (final SkillService.TotemView totem : service.totems(owner.getUniqueId())) {
            if (totem.feet().getWorld().equals(feet.getWorld()) && totem.feet().distanceSquared(feet) <= 16 && totem.feet().distanceSquared(feet) > 0.01) {
                count++;
            }
        }
        return Math.min(5, count);
    }

    /** 圖騰被打倒（SkillDeployables）：樁爆。 */
    void totemDied(final Player owner, final SkillBuild build, final Location feet, final double maxLife) {
        final double percent = LineageRules.total(build.supports(), LineageKeys.TOTEM_EXPLODE);
        if (owner != null && percent > 0) {
            explode(owner, feet.clone().add(0, 0.6, 0), 3, maxLife * percent / 100.0, DamageType.PHYSICAL, "tawhoas_vengeance", Particle.CRIT);
        }
    }

    /** 拾息：玩家 → 每層到哪個 tick。 */
    private final Map<UUID, List<Long>> khatal = new HashMap<>();

    /** 撿到殘骸（ChargeEffects）：拾息。 */
    void remnantCollected(final Player owner) {
        final double per = panel(owner, LineageKeys.REMNANT_CDR).values().stream().mapToDouble(Double::doubleValue).max().orElse(0);
        if (per <= 0) {
            return;
        }
        final long now = now();
        final List<Long> stacks = khatal.computeIfAbsent(owner.getUniqueId(), uuid -> new ArrayList<>());
        stacks.removeIf(until -> until <= now);
        if (stacks.size() >= 8) {
            stacks.remove(0);
        }
        stacks.add(now + 200);
        service.buff(owner, "lineage_khatal", "拾息 ×" + stacks.size(), 200,
                List.of(Modifier.increased(StatKeys.COOLDOWN_RECOVERY, per * stacks.size() / 100.0, "拾息")));
    }

    /** 翻滾（DodgeRoll）：連膛替被輔助的弩技能裝子彈。 */
    public void dodged(final Player player) {
        final Map<String, Double> loads = panel(player, LineageKeys.DODGE_LOAD);
        if (loads.isEmpty()) {
            return;
        }
        final PlayerState state = plugin.players().get(player);
        final StatSheet sheet = plugin.players().sheet(player);
        final SkillRuntime runtime = service.runtime(player);
        for (final Map.Entry<String, Double> entry : loads.entrySet()) {
            final SkillDefinition definition = service.config().skill(entry.getKey());
            final int position = state.skills().find(entry.getKey());
            if (definition == null || position < 0 || !SkillService.ammoSkill(definition)) {
                continue;
            }
            final SkillBuild build = service.build(state, sheet, definition, state.skills().skill(position));
            final int clip = SkillService.clipSize(service.config(), build, sheet);
            final int left = runtime.ammo.getOrDefault(definition.id(), clip);
            runtime.ammo.put(definition.id(), Math.min(clip, Math.max(0, left) + (int) Math.round(entry.getValue())));
            runtime.reloadDone.remove(definition.id());
            service.supportMechanics().extras().boltsReloaded(player, definition.id(), Math.min(clip, Math.max(0, left) + (int) Math.round(entry.getValue()))
                    - Math.max(0, left), now()); // 2026-10-27 照 PoE2 Fresh Clip
        }
        notice(player, "<#c9d6e3>連膛：翻滾裝填");
    }

    /** 雪封：玩家 → 格擋次數。玩家/技能 → 封印。 */
    private final Map<UUID, Integer> blocks = new HashMap<>();
    private final Map<String, Integer> blockSeals = new HashMap<>();

    /** 格擋了一下（CombatListener）：雪封蓄封印、岳盾記下舉盾時格擋過。 */
    public void blocked(final Player player) {
        if (service.buffActivePrefix(player, "guard") && !supportsOnAny(player, LineageRules.RAISE_SHIELD).isEmpty()) {
            shieldBlocked.put(player.getUniqueId(), now());
        }
        final Map<String, Double> seals = panel(player, LineageKeys.BLOCK_SEALS);
        if (seals.isEmpty()) {
            return;
        }
        final int count = blocks.merge(player.getUniqueId(), 1, Integer::sum);
        if (count % 2 != 0) {
            return;
        }
        for (final Map.Entry<String, Double> entry : seals.entrySet()) {
            blockSeals.merge(key(player, entry.getKey()), 1, (a, b) -> Math.min((int) Math.round(entry.getValue()), a + b));
        }
    }

    /** 律勁：完美時機的區間長幾倍（基礎 +100%，每層更短 30%）。 */
    double perfectWindow(final Player player, final String skillId) {
        if (LineageRules.total(supportsOn(player, skillId), LineageKeys.RHYTHM) <= 0) {
            return 1;
        }
        return 2 * Math.pow(0.7, rhythm.getOrDefault(player.getUniqueId(), 0));
    }

    /** 完美時機的結果（Signatures）：律勁成功疊一層（最多 5），任何技能失敗就全部失去。 */
    void perfectResult(final Player player, final String skillId, final boolean perfect) {
        if (!perfect) {
            if (rhythm.remove(player.getUniqueId()) != null) {
                notice(player, "<gray>律勁全部失去");
            }
            return;
        }
        if (LineageRules.total(supportsOn(player, skillId), LineageKeys.RHYTHM) > 0) {
            final int stacks = rhythm.merge(player.getUniqueId(), 1, (a, b) -> Math.min(5, a + b));
            notice(player, "<#ffd27f>律勁 ×" + stacks);
        }
    }

    // =====================================================================
    // 召喚物
    // =====================================================================

    /** 殘戰：召喚物（實體）→ 到哪個 tick 死、吃了幾個靈魂。 */
    private static final class Undying {
        final UUID owner;
        final long until;
        int souls;
        long lastSoul;

        Undying(final UUID owner, final long until) {
            this.owner = owner;
            this.until = until;
        }
    }

    private final Map<UUID, Undying> undying = new HashMap<>();

    private MinionService.Minion minionOf(final LivingEntity entity) {
        if (service.minions() == null) {
            return null;
        }
        for (final MinionService.Minion minion : service.minions().all()) {
            if (minion.mob.getUniqueId().equals(entity.getUniqueId())) {
                return minion;
            }
        }
        return null;
    }

    /**
     * 召喚物被擊中（CombatListener）：要扣的原版血量。憨魄不會受傷（0）；殘戰致命的那一下留一口氣，再戰 20 秒。
     */
    public double minionDamage(final LivingEntity entity, final double vanillaDamage) {
        if (undying.containsKey(entity.getUniqueId())) {
            return 0;
        }
        final MinionService.Minion minion = minionOf(entity);
        if (minion == null) {
            return vanillaDamage;
        }
        final List<SupportDefinition> supports = minion.build.supports();
        if (!hasLineage(supports)) {
            return vanillaDamage;
        }
        if (LineageRules.total(supports, LineageKeys.MINION_UNDAMAGEABLE) > 0) {
            return 0;
        }
        final double seconds = LineageRules.total(supports, LineageKeys.UNDYING);
        if (seconds > 0 && vanillaDamage >= entity.getHealth() - 1e-6) {
            undying.put(entity.getUniqueId(), new Undying(minion.owner, now() + Math.round(seconds * 20)));
            Fx.spawn(entity.getWorld(), Particle.SOUL, entity.getLocation().add(0, 1, 0), 20, 0.3, 0.6, 0.3, 0.03);
            return Math.max(0, entity.getHealth() - 0.5);
        }
        return vanillaDamage;
    }

    /** 召喚物重算屬性表（MinionService.rebuild）：巨伴（閃避、偏斜、體型）、縛魔的不潔之力、殘戰的速度。 */
    List<Modifier> minionModifiers(final MinionService.Minion minion, final StatSheet ownerSheet) {
        final List<Modifier> list = new ArrayList<>();
        final long now = now();
        final double gigantic = LineageRules.total(minion.build.supports(), LineageKeys.GIGANTIC);
        if (gigantic > 0) {
            // 2026-10-27：閃避、偏斜改在 MinionEstimates.ownerDerived（跟召喚物說明、每秒傷害共用），這裡只管體型
            final org.bukkit.attribute.AttributeInstance scale = minion.mob.getAttribute(org.bukkit.attribute.Attribute.SCALE);
            if (scale != null && Math.abs(scale.getBaseValue() - GIGANTIC_SCALE) > 1e-6) {
                scale.setBaseValue(GIGANTIC_SCALE);
            }
        }
        final Long unholy = unholyMinions.get(minion.mob.getUniqueId());
        if (unholy != null && unholy > now) {
            list.addAll(unholyMight());
        }
        final Undying dying = undying.get(minion.mob.getUniqueId());
        if (dying != null) {
            final double speed = (LineageRules.total(minion.build.supports(), LineageKeys.UNDYING) > 0 ? UNDYING_SPEED : 0) + dying.souls;
            list.add(Modifier.increased(StatKeys.ATTACK_SPEED, speed / 100.0, "殘戰"));
            list.add(Modifier.increased(StatKeys.CAST_SPEED, speed / 100.0, "殘戰"));
        }
        return list;
    }

    static final double GIGANTIC_SCALE = 1.2;
    /** 殘戰：攻擊與施法速度增加 40%。 */
    static final double UNDYING_SPEED = 40;

    /** 殘戰的噬魂：附近（4 格）每死一隻敵人，攻擊與施法速度再快 1%（最多 50 層）。 */
    private void feedSouls(final LivingEntity dead) {
        final long now = now();
        for (final Map.Entry<UUID, Undying> entry : undying.entrySet()) {
            final Entity minion = plugin.getServer().getEntity(entry.getKey());
            if (minion != null && minion.getWorld().equals(dead.getWorld()) && minion.getLocation().distanceSquared(dead.getLocation()) <= 16) {
                entry.getValue().souls = Math.min(50, entry.getValue().souls + 1);
                entry.getValue().lastSoul = now;
            }
        }
    }

    /** 你受到的擊中先由夥伴承受的 %（伴償；CombatListener）。 */
    public double companionShare(final Player player) {
        return panel(player, LineageKeys.COMPANION_SHIELD).values().stream().mapToDouble(Double::doubleValue).max().orElse(0);
    }

    /** 伴償：夥伴替你承受了 amount，你補償其中 200% 為生命（4 秒內回完）。 */
    public void companionAbsorbed(final Player player, final double amount) {
        if (amount > 0) {
            final double[] pending = recoup.computeIfAbsent(player.getUniqueId(), uuid -> new double[2]);
            pending[0] += amount * 2;
            pending[1] = RECOUP_TICKS;
        }
    }

    private final Map<UUID, double[]> recoup = new HashMap<>();
    static final double RECOUP_TICKS = 80;

    /** 熾心（Seraph's Heart）：被敵人擊中前（CombatListener）：x% 機率把你的抗性當成 90%。 */
    public StatSheet seraph(final Player player, final StatSheet defender) {
        final double chance = activeAura(player, LineageKeys.SERAPH);
        if (chance <= 0 || plugin.random().nextDouble() * 100 >= chance) {
            return defender;
        }
        final List<Modifier> mods = new ArrayList<>();
        mods.add(Modifier.flat(StatKeys.RESIST_MAX, Math.max(0, SERAPH_RESIST - defender.get(StatKeys.RESIST_MAX)), "熾心"));
        for (final DamageType type : new DamageType[] {DamageType.FIRE, DamageType.COLD, DamageType.LIGHTNING, DamageType.CHAOS}) {
            mods.add(Modifier.flat(type.mitigationStat(), SERAPH_RESIST - defender.get(type.mitigationStat()), "熾心"));
        }
        return defender.overlay(mods);
    }

    static final double SERAPH_RESIST = 90;

    /** 開著的光環掛的這個屬性（取最大）；沒有回傳 0。 */
    private double activeAura(final Player player, final String stat) {
        double best = 0;
        for (final Map.Entry<String, Double> entry : panel(player, stat).entrySet()) {
            if (service.auraOn(player, entry.getKey())) {
                best = Math.max(best, entry.getValue());
            }
        }
        return best;
    }

    // =====================================================================
    // 詛咒（SkillExecutor）
    // =====================================================================

    /** 詛咒地面（蟾咒、咒光）。 */
    private static final class CurseGround {
        final UUID owner;
        final SkillBuild build;
        final SkillParams.Mark spec;
        final Location center;
        final double radius;
        final long placed;
        final long until;
        final int maxToads;
        final double annihilateAfter;
        final List<long[]> toads = new ArrayList<>();
        long nextToad;
        boolean annihilated;

        CurseGround(final UUID owner, final SkillBuild build, final SkillParams.Mark spec, final Location center, final double radius,
                    final long placed, final long until, final int maxToads, final double annihilateAfter) {
            this.owner = owner;
            this.build = build;
            this.spec = spec;
            this.center = center;
            this.radius = radius;
            this.placed = placed;
            this.until = until;
            this.maxToads = maxToads;
            this.annihilateAfter = annihilateAfter;
            this.nextToad = placed;
        }
    }

    private final List<CurseGround> grounds = new ArrayList<>();
    /** 每個人同時最多幾塊詛咒地面（PoE2 maximum_curse_zones_allowed 2）。 */
    static final int MAX_GROUNDS = 2;

    /** 放詛咒（SkillExecutor.applyCurseAt）：掛了蟾咒、咒光的改成詛咒地面；有改回傳 true。 */
    boolean curseGround(final Player player, final SkillBuild build, final Location center, final SkillParams.Mark spec) {
        final int toads = (int) Math.round(LineageRules.total(build.supports(), LineageKeys.TOAD_GROUND));
        final double annihilate = LineageRules.total(build.supports(), LineageKeys.ANNIHILATION_GROUND);
        if (toads <= 0 && annihilate <= 0) {
            return false;
        }
        final long now = now();
        final double radius = spec.radius(build.level()) * build.areaScale();
        final long ticks = Math.max(20, build.durationTicks(spec.duration(build.level())));
        final long until = now + Math.max(ticks, annihilate > 0 ? Math.round((annihilate + 2.5) * 20) : 0);
        final List<CurseGround> mine = new ArrayList<>();
        for (final CurseGround ground : grounds) {
            if (ground.owner.equals(player.getUniqueId())) {
                mine.add(ground);
            }
        }
        while (mine.size() >= MAX_GROUNDS) {
            grounds.remove(mine.remove(0)); // 最舊的那塊拿掉
        }
        grounds.add(new CurseGround(player.getUniqueId(), build, spec, center.clone(), radius, now, until, toads, annihilate));
        center.getWorld().playSound(center, Sound.ENTITY_WITCH_CELEBRATE, 0.6f, 0.7f);
        return true;
    }

    /** 這一次施放有沒有魅咒（不受詛咒上限限制）。 */
    static boolean ignoresCurseLimit(final SkillBuild build) {
        return LineageRules.total(build.supports(), LineageKeys.CURSE_REFLECT) > 0;
    }

    /** 魅咒反射到自己身上的施放（玩家 → 施放的 tick）：一次施放只反射一次。 */
    private final Map<UUID, Long> reflected = new HashMap<>();
    /** 寂咒：敵人 → 不會再被安撫到哪個 tick。 */
    private final Map<UUID, Long> pacifyImmune = new HashMap<>();

    /** 詛咒放到一隻敵人身上之後（SkillExecutor）：魅咒反射、寂咒的安撫。 */
    void cursePlaced(final Player player, final SkillBuild build, final LivingEntity target, final List<Debuffs.Debuff> placed,
                     final long applied, final long until) {
        final List<SupportDefinition> supports = build.supports();
        if (!hasLineage(supports) || plugin.combat() == null) {
            return;
        }
        final Debuffs debuffs = plugin.combat().debuffs();
        if (LineageRules.total(supports, LineageKeys.CURSE_REFLECT) > 0 && !Long.valueOf(applied).equals(reflected.get(player.getUniqueId()))) {
            reflected.put(player.getUniqueId(), applied);
            for (final Debuffs.Debuff debuff : placed) {
                debuffs.apply(player.getUniqueId(), new Debuffs.Debuff(debuff.id() + ".self", player.getUniqueId(), debuff.untilTick(), debuff.taken(),
                        false, debuff.tag(), debuff.mods(), debuff.slow()));
            }
            notice(player, "<#c04070>魅咒：詛咒也落在你身上");
        }
        final double pacify = LineageRules.total(supports, LineageKeys.PACIFY);
        if (pacify > 0 && until - applied >= 40 && !placed.isEmpty()) { // 詛咒地面每 0.5 秒重套的短詛咒不算
            final long start = until - Math.round((until - applied) * pacify / 100.0);
            final UUID id = target.getUniqueId();
            final String curseId = placed.get(0).id();
            plugin.getServer().getScheduler().runTaskLater(plugin, () -> {
                final long now = now();
                if (!target.isValid() || pacifyImmune.getOrDefault(id, 0L) > now || !debuffs.has(id, curseId, now)) {
                    return;
                }
                debuffs.apply(id, new Debuffs.Debuff(PACIFY_ID, player.getUniqueId(), until, 0, false, null,
                        List.of(Modifier.more(StatKeys.DAMAGE, -1.0, "安撫")), 0));
                pacifyImmune.put(id, until + PACIFY_IMMUNE_TICKS);
                Fx.spawn(target.getWorld(), Particle.HEART, target.getLocation().add(0, target.getHeight() + 0.3, 0), 3, 0.2, 0.1, 0.2, 0);
            }, Math.max(1, start - applied));
        }
    }

    static final String PACIFY_ID = "lineage_pacify";
    static final long PACIFY_IMMUNE_TICKS = 160;

    // =====================================================================
    // 每 5 tick（SupportMechanics.tick）
    // =====================================================================

    /** 守頌：玩家 → 下一次給守護的 tick。威宣：玩家 → 上一次在身邊的敵人。 */
    private final Map<UUID, Long> reachNext = new HashMap<>();
    private final Map<UUID, Set<UUID>> presence = new HashMap<>();

    void tick(final long now) {
        if (!grounds.isEmpty()) {
            tickGrounds(now);
        }
        if (!recoup.isEmpty()) {
            tickRecoup();
        }
        if (now % 10 == 0) {
            for (final Player player : plugin.getServer().getOnlinePlayers()) {
                tickPlayer(player, now);
            }
        }
        if (now % 20 == 0) {
            tickUndying(now);
            tickCompanions();
            unholyMinions.values().removeIf(until -> until < now);
            pacifyImmune.values().removeIf(until -> until < now);
            combos.values().removeIf(combo -> now - combo[1] > COMBO_DECAY);
            mimics.values().forEach(list -> list.removeIf(mimic -> mimic.until() < now));
            mimics.values().removeIf(List::isEmpty);
            chalice.values().removeIf(until -> until < now);
        }
    }

    private void tickPlayer(final Player player, final long now) {
        final PlayerState state = plugin.players().get(player);
        if (!state.isLoaded() || !state.hasCharacter() || player.isDead()) {
            return;
        }
        // 守頌（Arbiter's Reach）：每 6 秒讓身邊的隊友獲得你最大生命 10% 的守護（4 秒）
        final double guard = activeAura(player, LineageKeys.ALLY_GUARD);
        if (guard > 0 && now >= reachNext.getOrDefault(player.getUniqueId(), 0L)) {
            reachNext.put(player.getUniqueId(), now + 120);
            if (plugin.party() != null) {
                for (final UUID member : plugin.party().groupMembers(player.getUniqueId())) {
                    final Player ally = plugin.getServer().getPlayer(member);
                    if (ally == null || ally.equals(player) || !ally.getWorld().equals(player.getWorld())
                            || ally.getLocation().distanceSquared(player.getLocation()) > SupportMechanics.PRESENCE * SupportMechanics.PRESENCE) {
                        continue;
                    }
                    final PlayerState other = plugin.players().get(ally);
                    other.addGuard(state.maxLife() * guard / 100.0, now + 80);
                    plugin.players().display(ally, other);
                    Fx.spawn(ally.getWorld(), Particle.END_ROD, ally.getLocation().add(0, 1, 0), 8, 0.3, 0.5, 0.3, 0.01);
                }
            }
        }
        // 威宣（Her Declaration）：走進你身邊的敵人被恫嚇 4 秒
        final double intimidate = activeAura(player, LineageKeys.INTIMIDATE_PRESENCE);
        if (intimidate > 0 && plugin.combat() != null) {
            final Set<UUID> inside = new HashSet<>();
            final Set<UUID> before = presence.getOrDefault(player.getUniqueId(), Set.of());
            for (final LivingEntity enemy : service.targets().around(player.getLocation(), SupportMechanics.PRESENCE, player)) {
                inside.add(enemy.getUniqueId());
                if (!before.contains(enemy.getUniqueId())) {
                    plugin.combat().debuffs().apply(enemy.getUniqueId(), new Debuffs.Debuff("intimidate", player.getUniqueId(),
                            now + Math.round(intimidate * 20), tw.exilecore.stats.TreeLastKeys.INTIMIDATE_TAKEN, false));
                }
            }
            presence.put(player.getUniqueId(), inside);
        } else {
            presence.remove(player.getUniqueId());
        }
        // 聖杯：滿溢聖杯期間藥劑每秒多 2 次充能
        final Long cup = chalice.get(player.getUniqueId());
        if (cup != null && cup >= now && now % 20 == 0 && plugin.flasks() != null) {
            plugin.flasks().addCharges(player, 2, tw.exilecore.flask.FlaskKind.LIFE);
            plugin.flasks().addCharges(player, 2, tw.exilecore.flask.FlaskKind.MANA);
        }
        // 岳盾：舉盾時暈眩門檻更多 50%
        if (service.buffActivePrefix(player, "guard")) {
            final double stun = LineageRules.total(supportsOnAny(player, LineageRules.RAISE_SHIELD), LineageKeys.SHIELD_STUN_THRESHOLD);
            if (stun > 0) {
                // 暈眩門檻只讀「增加」：更多 50% 換成等量的增加（照你現在的增加算）
                final double increased = plugin.players().sheet(player).increased(StatKeys.STUN_THRESHOLD, Set.of());
                service.buff(player, "lineage_mountain", "岳盾", 12, List.of(new Modifier(StatKeys.STUN_THRESHOLD, ModType.INCREASED,
                        Math.max(0, 1 + increased) * stun / 100.0, Set.of(), "岳盾")));
            }
        }
        // 幻分：幻身的樣子
        final String prefix = player.getUniqueId() + "/";
        for (final Map.Entry<String, List<Mimic>> entry : mimics.entrySet()) {
            if (entry.getKey().startsWith(prefix)) {
                for (final Mimic mimic : entry.getValue()) {
                    Fx.spawn(mimic.feet().getWorld(), Particle.WITCH, mimic.feet().clone().add(0, 1, 0), 3, 0.15, 0.5, 0.15, 0);
                }
            }
        }
    }

    /** 焰伴（Catha's Brilliance）：夥伴致盲、點燃身邊 2 格的敵人（每秒一次）。 */
    private void tickCompanions() {
        if (service.minions() == null || plugin.ailments() == null || plugin.combat() == null) {
            return;
        }
        final long now = now();
        for (final MinionService.Minion minion : service.minions().all()) {
            final double percent = LineageRules.total(minion.build.supports(), LineageKeys.COMPANION_BLAZE);
            if (percent <= 0 || !minion.mob.isValid() || minion.effective == null) {
                continue;
            }
            final Player owner = plugin.getServer().getPlayer(minion.owner);
            if (owner == null) {
                continue;
            }
            final double base = MinionSheets.life(minion.effective) * percent / 100.0;
            final StatSheet sheet = plugin.players().sheet(owner);
            for (final LivingEntity enemy : service.targets().around(minion.mob.getLocation(), 2, owner)) {
                plugin.combat().debuffs().apply(enemy.getUniqueId(), new Debuffs.Debuff("lineage_blind", owner.getUniqueId(), now + 30, 0, false, null,
                        List.of(Modifier.more(StatKeys.ACCURACY, -tw.exilecore.combat.HitEffects.BLIND_LESS, "致盲"),
                                Modifier.more(StatKeys.EVASION, -tw.exilecore.combat.HitEffects.BLIND_LESS, "致盲")), 0));
                plugin.ailments().igniteFrom(owner, enemy, base, sheet);
            }
            Fx.spawn(minion.mob.getWorld(), Particle.FLAME, minion.mob.getLocation().add(0, 0.5, 0), 6, 1, 0.3, 1, 0.01);
        }
    }

    /** 殘戰：時間到就倒下。 */
    private void tickUndying(final long now) {
        for (final Iterator<Map.Entry<UUID, Undying>> it = undying.entrySet().iterator(); it.hasNext(); ) {
            final Map.Entry<UUID, Undying> entry = it.next();
            final Entity entity = plugin.getServer().getEntity(entry.getKey());
            if (!(entity instanceof LivingEntity living) || !living.isValid() || living.isDead()) {
                it.remove();
                continue;
            }
            final Undying dying = entry.getValue();
            if (dying.souls > 0 && now - dying.lastSoul > 80) {
                dying.souls = Math.max(0, dying.souls - 2); // 照 PoE2 Soul Eater：4 秒沒吃到靈魂就每 0.5 秒少一個（這裡每秒看一次，少兩個）
            }
            if (now >= dying.until) {
                it.remove();
                living.setHealth(0);
                continue;
            }
            Fx.spawn(living.getWorld(), Particle.SOUL_FIRE_FLAME, living.getLocation().add(0, 1, 0), 3, 0.2, 0.4, 0.2, 0.01);
        }
    }

    /** 伴償的補償（4 秒內回完）。 */
    private void tickRecoup() {
        for (final Iterator<Map.Entry<UUID, double[]>> it = recoup.entrySet().iterator(); it.hasNext(); ) {
            final Map.Entry<UUID, double[]> entry = it.next();
            final Player player = plugin.getServer().getPlayer(entry.getKey());
            final double[] pending = entry.getValue();
            if (player == null || pending[1] <= 0 || pending[0] <= 0) {
                it.remove();
                continue;
            }
            final double step = pending[0] * Math.min(1, 5 / pending[1]);
            pending[0] -= step;
            pending[1] -= 5;
            final PlayerState state = plugin.players().get(player);
            if (!state.ruined(now())) {
                state.setLife(Math.min(state.maxLife(), state.life() + step));
                plugin.players().display(player, state);
            }
        }
    }

    /** 詛咒地面：裡面的敵人被詛咒、巫蟾、湮滅光柱。 */
    private void tickGrounds(final long now) {
        for (final Iterator<CurseGround> it = grounds.iterator(); it.hasNext(); ) {
            final CurseGround ground = it.next();
            final Player owner = plugin.getServer().getPlayer(ground.owner);
            if (owner == null || !owner.isOnline() || now >= ground.until || !owner.getWorld().equals(ground.center.getWorld())) {
                it.remove();
                continue;
            }
            if (now % 10 == 0) {
                service.executor().applyCurseAt(owner, ground.build, ground.center, ground.spec, now + 15); // 站在裡面才被詛咒
                for (int i = 0; i < 16; i++) {
                    final double a = Math.PI * 2 * i / 16 + now * 0.03;
                    Fx.spawn(ground.center.getWorld(), Particle.WITCH, ground.center.clone().add(Math.cos(a) * ground.radius, 0.15,
                            Math.sin(a) * ground.radius), 1, 0, 0, 0, 0);
                }
            }
            if (ground.maxToads > 0) {
                tickToads(owner, ground, now);
            }
            if (ground.annihilateAfter > 0 && !ground.annihilated && now - ground.placed >= Math.round(ground.annihilateAfter * 20)) {
                ground.annihilated = true;
                annihilate(owner, ground);
            }
        }
    }

    /** 巫蟾：每 0.25 秒生一隻（最多 maxToads），生出來 0.5 秒後撲向地面附近最近的敵人炸開。 */
    private void tickToads(final Player owner, final CurseGround ground, final long now) {
        if (ground.toads.size() < ground.maxToads && now >= ground.nextToad) {
            final double angle = plugin.random().nextDouble() * Math.PI * 2;
            final double r = plugin.random().nextDouble() * ground.radius;
            ground.toads.add(new long[] {now, Double.doubleToLongBits(Math.cos(angle) * r), Double.doubleToLongBits(Math.sin(angle) * r)});
            ground.nextToad = now + 5;
        }
        for (final Iterator<long[]> it = ground.toads.iterator(); it.hasNext(); ) {
            final long[] toad = it.next();
            final Location at = ground.center.clone().add(Double.longBitsToDouble(toad[1]), 0.2, Double.longBitsToDouble(toad[2]));
            Fx.spawn(at.getWorld(), Particle.ITEM_SLIME, at, 2, 0.1, 0.1, 0.1, 0);
            if (now - toad[0] < 10) {
                continue;
            }
            LivingEntity nearest = null;
            double best = Math.pow(ground.radius + TOAD_REACH, 2);
            for (final LivingEntity enemy : service.targets().around(ground.center, ground.radius + TOAD_REACH, owner)) {
                final double distance = enemy.getLocation().distanceSquared(at);
                if (distance < best) {
                    best = distance;
                    nearest = enemy;
                }
            }
            if (nearest == null) {
                continue;
            }
            it.remove();
            final double[] damage = TOAD_DAMAGE[Math.max(1, Math.min(TOAD_DAMAGE.length, ground.build.level())) - 1];
            final DamagePacket min = new DamagePacket();
            min.add(DamageType.CHAOS, damage[0]);
            final DamagePacket max = new DamagePacket();
            max.add(DamageType.CHAOS, damage[1]);
            final SkillProfile profile = new SkillProfile("dark_consequences", Set.of(Tags.SPELL, Tags.AREA, Tags.CHAOS), 1.0, min, max, Map.of(),
                    List.of(Modifier.flat(StatKeys.SPELL_BASE_CRIT, 5, "巫蟾")));
            final Location blast = nearest.getLocation().add(0, 0.5, 0);
            for (final LivingEntity enemy : service.targets().around(blast, TOAD_RADIUS, owner)) {
                plugin.combat().skillDamage().hit(owner, enemy, profile, null);
            }
            Fx.spawn(blast.getWorld(), Particle.ITEM_SLIME, blast, 25, 0.6, 0.4, 0.6, 0.1);
            Fx.spawn(blast.getWorld(), Particle.WITCH, blast, 15, 0.6, 0.4, 0.6, 0.05);
            blast.getWorld().playSound(blast, Sound.ENTITY_SLIME_SQUISH, 0.8f, 0.6f);
        }
    }

    static final double TOAD_REACH = 3;
    static final double TOAD_RADIUS = 1.6;
    /** 巫蟾炸開（Dark Consequences）的混沌傷害（poe2db 1–40 級）。 */
    static final double[][] TOAD_DAMAGE = {{5, 10}, {7, 14}, {10, 19}, {13, 24}, {17, 31}, {21, 38}, {25, 47}, {30, 56}, {36, 67}, {43, 79},
            {50, 93}, {59, 109}, {68, 127}, {79, 147}, {92, 170}, {106, 197}, {122, 227}, {141, 261}, {162, 300}, {186, 345}, {214, 397},
            {245, 456}, {282, 523}, {324, 601}, {372, 691}, {427, 793}, {491, 912}, {565, 1049}, {650, 1207}, {749, 1391}, {863, 1603},
            {996, 1849}, {1150, 2135}, {1329, 2468}, {1538, 2856}, {1781, 3308}, {2066, 3836}, {2399, 4455}, {2789, 5179}, {3246, 6029}};

    /** 湮滅光柱：2 秒內打 6 下（1.7 格），物理傷害換成詛咒的元素（沒有元素就是物理）。 */
    private void annihilate(final Player owner, final CurseGround ground) {
        final double[] damage = ANNIHILATION_DAMAGE[Math.max(1, Math.min(ANNIHILATION_DAMAGE.length, ground.build.level())) - 1];
        final List<DamageType> types = new ArrayList<>();
        for (final DamageType type : new DamageType[] {DamageType.FIRE, DamageType.COLD, DamageType.LIGHTNING, DamageType.CHAOS}) {
            if (ground.build.definition().tags().contains(type.tag())) {
                types.add(type);
            }
        }
        if (types.isEmpty()) {
            types.add(DamageType.PHYSICAL);
        }
        final DamagePacket min = new DamagePacket();
        final DamagePacket max = new DamagePacket();
        final Set<String> tags = new HashSet<>(Set.of(Tags.SPELL, Tags.AREA));
        for (final DamageType type : types) {
            min.add(type, damage[0] / types.size());
            max.add(type, damage[1] / types.size());
            tags.addAll(type.tags());
        }
        final SkillProfile profile = new SkillProfile("annihilation", tags, 1.0, min, max, Map.of(), List.of(Modifier.flat(StatKeys.SPELL_BASE_CRIT, 5, "湮滅")));
        final Location at = ground.center.clone();
        for (int i = 0; i < 6; i++) {
            plugin.getServer().getScheduler().runTaskLater(plugin, () -> {
                if (!owner.isOnline() || !owner.getWorld().equals(at.getWorld())) {
                    return;
                }
                for (final LivingEntity enemy : service.targets().around(at, ANNIHILATION_RADIUS, owner)) {
                    plugin.combat().skillDamage().hit(owner, enemy, profile, null);
                }
                Fx.spawn(at.getWorld(), Particle.END_ROD, at.clone().add(0, 2, 0), 30, 0.3, 2, 0.3, 0.02);
                at.getWorld().playSound(at, Sound.BLOCK_BEACON_POWER_SELECT, 0.6f, 1.6f);
            }, 1 + 7L * i);
        }
    }

    static final double ANNIHILATION_RADIUS = 1.7;
    /** 湮滅（Annihilation）的物理傷害（poe2db 1–40 級，每一下）。 */
    static final double[][] ANNIHILATION_DAMAGE = {{25, 37}, {34, 50}, {46, 69}, {60, 90}, {76, 114}, {94, 141}, {115, 172}, {138, 207},
            {165, 247}, {195, 292}, {229, 343}, {268, 401}, {312, 468}, {362, 543}, {419, 628}, {484, 726}, {558, 837}, {643, 964},
            {739, 1109}, {850, 1275}, {977, 1465}, {1122, 1683}, {1288, 1933}, {1480, 2220}, {1700, 2550}, {1953, 2930}, {2245, 3368},
            {2582, 3873}, {2972, 4458}, {3423, 5134}, {3945, 5918}, {4551, 6827}, {5256, 7884}, {6075, 9112}, {7029, 10544},
            {8143, 12214}, {9444, 14166}, {10966, 16448}, {12749, 19123}, {14841, 22261}};

    private void notice(final Player player, final String message) {
        final PlayerState state = plugin.players().get(player);
        state.notice(message, now() + 40);
        plugin.players().display(player, state);
    }

    /** 下線、換角色：這個人的狀態都清掉。 */
    void forget(final UUID id) {
        final String prefix = id + "/";
        cooldownUsed.remove(id);
        commandUsed.remove(id);
        chalice.remove(id);
        natureReady.remove(id);
        rhythm.remove(id);
        shieldBlocked.remove(id);
        pendingEchoes.remove(id);
        gifts.remove(id);
        khatal.remove(id);
        blocks.remove(id);
        recoup.remove(id);
        reachNext.remove(id);
        presence.remove(id);
        reflected.remove(id);
        wildshards.keySet().removeIf(k -> k.startsWith(prefix));
        mimics.keySet().removeIf(k -> k.startsWith(prefix));
        combos.keySet().removeIf(k -> k.startsWith(prefix));
        blockSeals.keySet().removeIf(k -> k.startsWith(prefix));
        grounds.removeIf(ground -> ground.owner.equals(id));
    }
}
