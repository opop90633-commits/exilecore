package tw.exilecore.skill;

import org.bukkit.Location;
import org.bukkit.Particle;
import org.bukkit.entity.LivingEntity;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.entity.EntityDeathEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import tw.exilecore.ExileCore;
import tw.exilecore.ailment.AilmentType;
import tw.exilecore.character.PlayerState;
import tw.exilecore.character.SkillLoadout;
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
import tw.exilecore.stats.SupportKeys;
import tw.exilecore.stats.Tags;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.Iterator;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/**
 * 2026-10-26 照 PoE2 0.5 重做的輔助寶石與戰吼需要的機制（屬性鍵見 {@link SupportKeys}）：
 * <ul>
 *   <li>擊中前的條件加成（炙獵、遠穿、釘地 II）、只減擊中的傷害（燎原）、條件破甲（霜裂甲、烙印深）、異常的條件加成（寒焚、淬毒 III、急蝕、速蝕）</li>
 *   <li>擊中之後：定身累積（釘地）、凋零機率（枯觸）、元素崩解（元崩）、中毒破甲（鏽甲）、點燃用掉凍結（寒焚）、斷命 II、火山噴發</li>
 *   <li>放技能時：電荷（引雷）、最近用過的元素（元湧）、獻祭生命（捐命）、榮耀（熔魂嘯、澤嘯）、移動距離（震嘯）</li>
 *   <li>戰吼的強化被用掉時：各戰吼的效果、餘震、盾波、冰緩地面、不竭嘯、決嘯、嘯準</li>
 *   <li>戰吼輔助：碎甲吼、契血嘯、碎晶嘯；凍結時：冰嚙、霜仇嘯</li>
 *   <li>每 5 tick：移動距離、火山步、燃僕、腐化之血、冰緩地面</li>
 * </ul>
 */
public final class SupportMechanics implements Listener {

    private final ExileCore plugin;
    private final SkillService service;
    /** 2026-10-27 照 PoE2 0.5：血脈輔助的機制（supports_lineage.yml）。 */
    private final LineageMechanics lineage;

    SupportMechanics(final ExileCore plugin, final SkillService service) {
        this.plugin = plugin;
        this.service = service;
        this.lineage = new LineageMechanics(plugin, service);
        this.extras = new SupportExtras(plugin, service);
    }

    /** 2026-10-27：血脈輔助的機制（CombatListener、MinionService 這些從這裡拿）。 */
    public LineageMechanics lineage() {
        return lineage;
    }

    /** 2026-10-27 照 PoE2 0.5 補上的一般輔助寶石的機制（SupportExtras）。 */
    private final SupportExtras extras;

    public SupportExtras extras() {
        return extras;
    }

    private long now() {
        return plugin.getServer().getCurrentTick();
    }

    // =====================================================================
    // 移動距離（震嘯、引雷、火山步）
    // =====================================================================

    /** 玩家累積走了幾格（每 5 tick 取一次位置，只算水平距離；換世界、傳送一大段不算）。 */
    private final Map<UUID, Double> travelled = new HashMap<>();
    private final Map<UUID, Location> lastAt = new HashMap<>();
    /** 震嘯：上次用這個技能時走到幾格（玩家/技能）。 */
    private final Map<String, Double> echoMark = new HashMap<>();
    /** 引雷：上次放這個技能時走到幾格（玩家/技能）。 */
    private final Map<String, Double> voltMark = new HashMap<>();

    double travelled(final Player player) {
        return travelled.getOrDefault(player.getUniqueId(), 0.0);
    }

    private void sampleTravel(final Player player) {
        final Location at = player.getLocation();
        final Location before = lastAt.put(player.getUniqueId(), at);
        if (before == null || !before.getWorld().equals(at.getWorld())) {
            return;
        }
        final double dx = at.getX() - before.getX();
        final double dz = at.getZ() - before.getZ();
        final double step = Math.sqrt(dx * dx + dz * dz);
        if (step > 0.01 && step < 8) { // 一次跳太遠（傳送）不算
            travelled.merge(player.getUniqueId(), step, Double::sum);
            stepped(player, step);
        }
    }

    private static String key(final Player player, final String skillId) {
        return player.getUniqueId() + "/" + skillId;
    }

    // =====================================================================
    // 榮耀（熔魂嘯：點燃敵人照強度；澤嘯：花在其他技能上的生命 5%）
    // =====================================================================

    private final Map<String, Double> glory = new HashMap<>();

    /** 這個技能要幾點榮耀才能用（0 = 不用）。2026-10-26：加上 skills.yml 的 resource.glory（戰旗、熾陽之矛、天罰巨鎚）。 */
    int gloryRequired(final SkillBuild build, final StatSheet sheet) {
        final int own = gloryCap(build.definition());
        final int support = sheet == null ? 0 : (int) Math.round(build.profile().effective(sheet).flat(SupportKeys.GLORY_COST, build.profile().tags()));
        // 2026-10-27 照 PoE2 Delayed Gratification：條件更難（需求 × 2.5）；旗熾（Daresso's Passion）：要的榮耀更少 50%
        final double harder = sheet == null ? 1 : SupportExtras.conditionScale(build.profile().effective(sheet), build.profile().tags());
        final double less = sheet == null ? 1 : Math.max(0, build.profile().effective(sheet).more(tw.exilecore.stats.LineageKeys.GLORY_REQUIRED, build.profile().tags()));
        return (int) Math.ceil(Math.max(own, support) * harder * less - 1e-9);
    }

    /**
     * 2026-10-27 照 PoE2 Delayed Gratification：要連擊才能用的技能（驚雷之鐘）要幾點連擊——條件更難（× 2.5）；達成條件用了之後 10 秒內多一次不用連擊。
     */
    int comboRequired(final Player player, final SkillBuild build, final int base) {
        if (base <= 0) {
            return 0;
        }
        if (extras.gratified(player, build)) {
            return 0;
        }
        final StatSheet sheet = plugin.players().sheet(player);
        return (int) Math.ceil(base * SupportExtras.conditionScale(build.profile().effective(sheet), build.profile().tags()) - 1e-9);
    }

    /** 2026-10-26：技能自己要的榮耀（戰吼的 warcry.glory、resource.glory 取大的）。 */
    private int gloryCap(final SkillDefinition definition) {
        final WarcrySpec warcry = definition.warcry();
        final ResourceSpec resource = service.config().resource(definition.id());
        return Math.max(warcry == null ? 0 : warcry.extras().glory(), resource == null ? 0 : resource.glory());
    }

    /** 2026-10-26：技能的榮耀從哪裡來（resource.glory_from；戰吼沒寫是 ignite、其他沒寫是 attack_hit）。 */
    private String glorySource(final SkillDefinition definition) {
        final ResourceSpec resource = service.config().resource(definition.id());
        if (resource != null && resource.gloryFrom() != null) {
            return resource.gloryFrom();
        }
        return definition.warcry() != null && definition.warcry().extras().glory() > 0 ? "ignite" : "attack_hit";
    }

    /**
     * 2026-10-26 照 PoE2：面板上靠這種方式累積榮耀的技能加 amount 點（戰旗：攻擊打中照怪物強度；熾陽之矛：冰凍、感電、點燃各 1 點；
     * 天罰巨鎚：重暈照怪物強度；熔魂嘯：點燃照怪物強度）。
     */
    void gainGlory(final Player player, final String source, final double amount) {
        if (amount <= 0) {
            return;
        }
        final PlayerState state = plugin.players().get(player);
        if (!state.isLoaded() || !state.hasCharacter()) {
            return;
        }
        for (int i = 0; i < SkillLoadout.MAX_SKILLS; i++) {
            final SkillLoadout.Entry entry = state.skills().skill(i);
            final SkillDefinition definition = entry == null ? null : service.config().skill(entry.skillId());
            if (definition == null) {
                continue;
            }
            final int cap = gloryCap(definition);
            if (cap > 0 && source.equals(glorySource(definition))) {
                addGlory(player, definition.id(), amount, cap);
            }
            // 2026-10-27 照 PoE2：放在裂甲熊咆孔裡的戰吼（熔魂嘯）也累積自己的榮耀
            final SkillDefinition socketed = SocketedWarcry.hosts(definition) && entry.inner() != null
                    ? service.config().skill(entry.inner().skillId()) : null;
            if (socketed != null && gloryCap(socketed) > 0 && source.equals(glorySource(socketed))) {
                addGlory(player, socketed.id(), amount, gloryCap(socketed));
            }
        }
    }

    double glory(final Player player, final String skillId) {
        return glory.getOrDefault(key(player, skillId), 0.0);
    }

    /** 點燃敵人時（SkillTriggers 呼叫）：面板上要靠點燃累積榮耀的技能（熔魂嘯）照牠的強度加。 */
    void onIgnite(final Player player, final LivingEntity target) {
        gainGlory(player, "ignite", service.warcries().power(target)); // 2026-10-26：改走 gainGlory（來源可以設定）
    }

    private void addGlory(final Player player, final String skillId, final double amount, final int cap) {
        // 2026-10-26 照 PoE2 天賦：榮耀獲得增加
        final double gain = Math.max(0, 1 + plugin.players().sheet(player).increased(tw.exilecore.stats.TreeNewMechanicKeys.GLORY_GAIN, Set.of()));
        glory.merge(key(player, skillId), Math.min(cap, amount * gain), (a, b) -> Math.min(cap, a + b));
    }

    /** 2026-10-26（待價而沽）：面板上的某個技能直接加榮耀（照它自己的上限）。 */
    void addGloryDirect(final Player player, final String skillId, final double amount) {
        final SkillDefinition definition = service.config().skill(skillId);
        final int cap = definition == null ? 0 : gloryCap(definition);
        if (cap > 0 && amount > 0) {
            addGlory(player, skillId, amount, cap);
        }
    }

    /** 技能花了生命（血魔法、血祭術、捐命；SkillService 呼叫）：掛著澤嘯的其他技能拿到 5% 的榮耀。 */
    void lifeSpent(final Player player, final String skillId, final double amount) {
        if (amount <= 0) {
            return;
        }
        final PlayerState state = plugin.players().get(player);
        for (int i = 0; i < SkillLoadout.MAX_SKILLS; i++) {
            final SkillLoadout.Entry entry = state.skills().skill(i);
            if (entry == null || entry.skillId().equals(skillId)) {
                continue;
            }
            final double cost = service.supportFlat(player, entry.skillId(), SupportKeys.GLORY_COST);
            if (cost > 0) {
                addGlory(player, entry.skillId(), amount * 0.05, (int) Math.round(cost));
            }
        }
    }

    // =====================================================================
    // 放技能之前、之後
    // =====================================================================

    /** 不能用的原因（要榮耀、震嘯要先移動 10 格）；可以用回傳 null。 */
    String usageProblem(final Player player, final SkillBuild build) {
        final String lineageProblem = lineage.usageProblem(player, build); // 2026-10-27 照 PoE2：連膛（彈匣空了要翻滾裝填）
        if (lineageProblem != null) {
            return lineageProblem;
        }
        final StatSheet sheet = plugin.players().sheet(player);
        final int required = extras.gratified(player, build) ? 0 : gloryRequired(build, sheet); // 2026-10-27：Delayed Gratification 多一次不用條件
        if (required > 0 && glory(player, build.definition().id()) + 1e-6 < required) {
            return String.format(Locale.ROOT, "%s 要 %d 點榮耀（現在 %d）", build.definition().name(), required,
                    (int) Math.floor(glory(player, build.definition().id())));
        }
        final StatSheet effective = build.profile().effective(sheet);
        if (effective.flat(SupportKeys.WARCRY_ECHOES, build.profile().tags()) > 0) {
            final Double mark = echoMark.get(key(player, build.definition().id()));
            final double moved = mark == null ? Double.MAX_VALUE : travelled(player) - mark;
            if (moved < ECHO_TRAVEL) {
                return String.format(Locale.ROOT, "%s 要先移動 %.0f 格（還差 %.1f）", build.definition().name(), ECHO_TRAVEL, ECHO_TRAVEL - moved);
            }
        }
        return extras.usageProblem(player, build, effective); // 2026-10-27：先祖援助、無畏、淨化…
    }

    /** 震嘯：用過之後要再移動幾格才能用。 */
    static final double ECHO_TRAVEL = 10;

    /** 真的放了（扣完魔力之後）：用掉榮耀、記下移動距離。 */
    void used(final Player player, final SkillBuild build) {
        final StatSheet sheet = plugin.players().sheet(player);
        final boolean free = extras.gratified(player, build); // 2026-10-27 照 PoE2 Delayed Gratification：這次不用條件
        final int required = free ? 0 : gloryRequired(build, sheet);
        final ResourceSpec resource = service.config().resource(build.definition().id());
        if (gloryCap(build.definition()) > 0 || free || resource != null && resource.combo() > 0) { // 2026-10-27：連擊也是使用條件
            extras.gratificationUsed(player, build, free);
        }
        extras.used(player, build); // 2026-10-27：放技能的那段時間的增益、用掉藥劑充能與戰星…
        // 2026-10-26 照 PoE2 天賦：x% 機率不消耗榮耀；戰旗用掉榮耀後回復需要的 x%（我們的戰旗是立即的增益：放下時就回）
        final boolean consumed = required > 0 && !(plugin.random().nextDouble() * 100 < sheet.flat(tw.exilecore.stats.TreeNewMechanicKeys.GLORY_KEEP, Set.of()));
        if (required > 0 && service.treeLeftovers() != null) {
            service.treeLeftovers().gloryUsed(player, consumed ? required : 0); // 2026-10-27 照 PoE2 天賦樹：用了需要榮耀的技能、用掉榮耀
        }
        if (consumed) {
            glory.remove(key(player, build.definition().id()));
            final double refund = build.profile().tags().contains(tw.exilecore.stats.TreeMechanicKeys.BANNER)
                    ? sheet.flat(tw.exilecore.stats.TreeNewMechanicKeys.BANNER_GLORY_REFUND, Set.of()) : 0;
            if (refund > 0) {
                addGlory(player, build.definition().id(), required * Math.min(100, refund) / 100.0, gloryCap(build.definition()));
            }
        }
        if (build.profile().effective(sheet).flat(SupportKeys.WARCRY_ECHOES, build.profile().tags()) > 0) {
            echoMark.put(key(player, build.definition().id()), travelled(player));
        }
        lineage.used(player, build); // 2026-10-27 照 PoE2：血脈輔助（焦血、狂伐、聖杯、縛魔…）
    }

    /** 捐命（PoE2 Sacrificial Offering）：放的時候獻祭 x% 最大生命（扣不死）。 */
    void sacrifice(final Player player, final SkillBuild build, final PlayerState state) {
        final double percent = build.profile().effective(plugin.players().sheet(player)).flat(SupportKeys.SACRIFICE_LIFE, build.profile().tags());
        if (percent <= 0) {
            return;
        }
        final double amount = Math.min(state.life() - 1, state.maxLife() * percent / 100.0);
        if (amount > 0) {
            state.setLife(state.life() - amount);
            plugin.players().display(player, state);
            lifeSpent(player, build.definition().id(), amount);
        }
    }

    /** 元湧：每個玩家最近用過的元素技能（火、冰、電）在哪個 tick。 */
    private final Map<UUID, long[]> elementsUsed = new HashMap<>();
    private static final DamageType[] ELEMENTS = {DamageType.FIRE, DamageType.COLD, DamageType.LIGHTNING};
    /** 「最近」= 4 秒。 */
    static final long RECENT_TICKS = 80;

    /** 放下去之前（強化之後）：引雷的電荷、元湧的元素數。 */
    SkillBuild beforeUse(final Player player, final SkillBuild build) {
        final long now = now();
        final StatSheet effective = build.profile().effective(plugin.players().sheet(player));
        final Set<String> tags = build.profile().tags();
        final List<Modifier> extra = new ArrayList<>();
        // 引雷（PoE2 Volt）：每移動 1 格 2 點電荷，最多 30；放的時候全部用掉，每點獲得 1% 傷害為額外閃電傷害
        final double voltMax = effective.flat(SupportKeys.VOLT, tags);
        if (voltMax > 0 && build.isAttack()) {
            final String key = key(player, build.definition().id());
            final double moved = travelled(player) - voltMark.getOrDefault(key, travelled(player));
            voltMark.put(key, travelled(player));
            final double charge = Math.min(voltMax, Math.floor(moved * 2));
            if (charge > 0) {
                extra.add(Modifier.flat(StatKeys.damageExtra(DamageType.LIGHTNING), charge, "引雷電荷"));
            }
        }
        // 元湧（PoE2 Rising Tempest）：最近用過的技能裡，每有一種跟這個技能不同的元素，元素傷害更多 x%
        final long[] used = elementsUsed.computeIfAbsent(player.getUniqueId(), uuid -> new long[] {-1000, -1000, -1000});
        final double perType = effective.flat(SupportKeys.ELEMENTAL_PER_RECENT_TYPE, tags);
        if (perType > 0) {
            int kinds = 0;
            for (int i = 0; i < ELEMENTS.length; i++) {
                if (now - used[i] <= RECENT_TICKS && !tags.contains(ELEMENTS[i].tag())) {
                    kinds++;
                }
            }
            if (kinds > 0) {
                extra.add(new Modifier(StatKeys.DAMAGE, ModType.MORE, perType * kinds / 100.0, Set.of(Tags.ELEMENTAL), "元湧"));
            }
        }
        for (int i = 0; i < ELEMENTS.length; i++) {
            if (tags.contains(ELEMENTS[i].tag())) {
                used[i] = now;
            }
        }
        return extras.beforeUse(player, extra.isEmpty() ? build : build.withProfile(build.profile().withLocal(extra))); // 2026-10-27：先祖強化、投射物輔助…
    }

    // =====================================================================
    // 戰吼的強化被用掉
    // =====================================================================

    /** 這一下用掉的強化。 */
    record Empowering(SkillBuild build, List<SkillRuntime.Empowered> used, Origin origin) {
    }

    /** 攻擊放下去之前：用掉符合這一類攻擊的強化，把各戰吼的效果疊上去。 */
    Empowering empower(final Player player, final SkillRuntime runtime, final SkillBuild build, final long now) {
        if (!build.isAttack() || runtime.empowered.isEmpty()) {
            return new Empowering(build, List.of(), null);
        }
        final List<SkillRuntime.Empowered> used = new ArrayList<>();
        final List<Modifier> mods = new ArrayList<>();
        int extraTargets = 0;
        for (final Iterator<Map.Entry<String, SkillRuntime.Empowered>> it = runtime.empowered.entrySet().iterator(); it.hasNext(); ) {
            final Map.Entry<String, SkillRuntime.Empowered> entry = it.next();
            final SkillRuntime.Empowered each = entry.getValue();
            if (!each.active(now)) {
                it.remove();
                continue;
            }
            if (!each.spec().accepts(build.definition())) {
                continue;
            }
            used.add(each);
            final WarcrySpec.Empower spec = each.spec();
            final int level = each.level();
            final String source = each.name() + "強化";
            if (spec.gainFire().at(level) > 0) {
                mods.add(Modifier.flat(StatKeys.damageExtra(DamageType.FIRE), spec.gainFire().at(level), source));
            }
            if (spec.coldMax().at(level) > 0) {
                final int times = Warcries.per5(each.power(), spec.cap());
                mods.add(new Modifier(StatKeys.DAMAGE_ADDED_MIN, ModType.FLAT, spec.coldMin().at(level) * times, Set.of(Tags.COLD), source));
                mods.add(new Modifier(StatKeys.DAMAGE_ADDED_MAX, ModType.FLAT, spec.coldMax().at(level) * times, Set.of(Tags.COLD), source));
            }
            if (spec.armourBreak().at(level) > 0) {
                mods.add(Modifier.flat(StatKeys.ARMOUR_BREAK_FLAT, spec.armourBreak().at(level), source));
            }
            if (spec.more().at(level) > 0) {
                mods.add(Modifier.more(StatKeys.DAMAGE, spec.more().at(level) / 100.0, source));
            }
            if (each.cull()) {
                mods.add(Modifier.flat(StatKeys.FLAG_CULLING, 1, "決嘯"));
            }
            mods.addAll(each.extra()); // 2026-10-27 照 PoE2：戰吼品質的額外火焰、裂甲熊咆插槽的增加傷害
            extraTargets += spec.extraTargets();
            // 不竭嘯（PoE2 Tireless）：x% 機率不算這一次
            if (each.tireless() > 0 && plugin.random().nextDouble() * 100 < each.tireless()) {
                continue;
            }
            final SkillRuntime.Empowered next = each.consume();
            if (next == null) {
                it.remove();
            } else {
                entry.setValue(next);
            }
        }
        if (used.isEmpty()) {
            return new Empowering(build, List.of(), null);
        }
        // 嘯準（PoE2 Unerring Power）：被強化時命中更多 30%（消耗在扣魔力的時候就算了）
        if (StatKeys.flag(build.profile().effective(plugin.players().sheet(player)), SupportKeys.FLAG_UNERRING)) {
            mods.add(Modifier.more(StatKeys.ACCURACY, 0.3, "嘯準"));
        }
        SkillBuild result = build.withProfile(build.profile().withLocal(mods));
        if (extraTargets > 0 && build.definition().mechanic() == Mechanic.STRIKE) {
            result = result.withTargets(result.targets() + extraTargets);
        }
        final Location at = player.getLocation().add(0, 1.2, 0);
        Fx.spawn(at.getWorld(), Particle.CRIT, at, 8, 0.3, 0.3, 0.3, 0.1);
        at.getWorld().playSound(at, org.bukkit.Sound.ENTITY_PLAYER_ATTACK_STRONG, 0.4f, 0.7f);
        return new Empowering(result, List.copyOf(used), Origin.of(player));
    }

    /** 被強化的攻擊放完之後：震地的餘震、堅壁戰吼的盾波、寒夜嗥的冰緩地面。 */
    void afterEmpowered(final Player player, final Empowering empowering, final SkillBuild build, final LivingEntity preferred) {
        lineage.afterCast(player, build, preferred); // 2026-10-27 照 PoE2：血脈輔助（三迴、雪封、幻分、碎星、祭脈）
        extras.afterCast(player, build, preferred); // 2026-10-27：連瀑、完美、詛咒地面、震波
        if (empowering == null || empowering.used().isEmpty()) {
            return;
        }
        final boolean slam = build.definition().tags().contains(Tags.SLAM);
        for (final SkillRuntime.Empowered each : empowering.used()) {
            final WarcrySpec.Empower spec = each.spec();
            if (slam && spec.aftershocks() > 0 && empowering.origin() != null) {
                // 2026-10-27 照 PoE2（震地戰吼的品質）：餘震更多 x% 傷害
                final SkillBuild shock = each.aftershockMore() > 0
                        ? build.withProfile(build.profile().scaled(1 + each.aftershockMore() / 100.0, each.name() + "餘震（品質）")) : build;
                for (int i = 1; i <= spec.aftershocks(); i++) {
                    plugin.getServer().getScheduler().runTaskLater(plugin, () -> {
                        if (player.isOnline() && player.getWorld().equals(empowering.origin().world())) {
                            service.executor().executeFrom(empowering.origin(), shock.withProfile(shock.profile().derive(
                                    shock.profile().id() + ".aftershock", Set.of(), Set.of())), preferred);
                        }
                    }, 10L * i);
                }
            }
            if (slam && spec.chilledGround()) {
                final Location at = player.getLocation().add(SkillTargets.facing(player).multiply(2));
                chilledGround(player, at, 3.0 * build.areaScale(), 80);
            }
            if (spec.trigger() != null) {
                // 2026-10-27 照 PoE2（Shield Wave）：盾每 15 點護甲附加 5–7 物理（再乘盾波的傷害效益）；這次之前沒有
                // 2026-10-27 照 PoE2（堅壁戰吼的品質）：盾波半徑 +x 格
                castHidden(player, spec.trigger(), each.level(), player.getLocation(), 0, null, shieldWaveAdded(offhandShieldArmour(player)),
                        triggerRadiusScale(service.config().skill(spec.trigger()), each.level(), each.triggerRadius()));
            }
        }
    }

    /** 2026-10-27 照 PoE2（Shield Wave：off_hand_minimum/maximum_added_physical_damage_per_15_shield_armour 5／7）。 */
    static final double SHIELD_WAVE_MIN_PER_15 = 5;
    static final double SHIELD_WAVE_MAX_PER_15 = 7;

    /** 盾波照盾的護甲附加的物理傷害（每 15 點護甲 5–7；沒有護甲就沒有）。 */
    static List<Modifier> shieldWaveAdded(final double armour) {
        if (armour <= 0) {
            return List.of();
        }
        final Set<String> physical = Set.copyOf(tw.exilecore.stats.DamageType.PHYSICAL.tags());
        return List.of(new Modifier(StatKeys.DAMAGE_ADDED_MIN, tw.exilecore.stats.ModType.FLAT, armour / 15 * SHIELD_WAVE_MIN_PER_15, physical, "盾波（盾的護甲）"),
                new Modifier(StatKeys.DAMAGE_ADDED_MAX, tw.exilecore.stats.ModType.FLAT, armour / 15 * SHIELD_WAVE_MAX_PER_15, physical, "盾波（盾的護甲）"));
    }

    /** 副手盾的護甲（含詞綴、品質；沒有盾 0）。 */
    private double offhandShieldArmour(final Player player) {
        final org.bukkit.inventory.ItemStack off = plugin.players().get(player).jewellery(tw.exilecore.character.Gear.OFF);
        if (off == null || off.isEmpty() || plugin.items() == null) {
            return 0;
        }
        final tw.exilecore.item.ItemData data = plugin.items().read(off).orElse(null);
        final tw.exilecore.item.ItemBase base = data == null ? null : plugin.items().repository().base(data.base());
        if (base == null || !base.isShield()) {
            return 0;
        }
        return tw.exilecore.item.ItemStats.snapshot(plugin.items().repository(), base, data).armour();
    }

    /** 放一個隱藏技能（等級 level；cooldown > 0 時同一個玩家同一個技能至少隔這麼多 tick）。 */
    private final Map<String, Long> hiddenReady = new HashMap<>();

    void castHidden(final Player player, final String skillId, final int level, final Location at, final long cooldown, final LivingEntity target) {
        castHidden(player, skillId, level, at, cooldown, target, List.of());
    }

    /**
     * 2026-10-27：隱藏技能的半徑多 extra 格時，半徑要乘多少（照它自己這一級的基礎半徑；沒有半徑或沒加回傳 1）。
     */
    static double triggerRadiusScale(final SkillDefinition sub, final int level, final double extra) {
        if (sub == null || extra <= 0) {
            return 1;
        }
        final double base = sub.params().radius().at(Math.max(1, level));
        return base <= 0 ? 1 : (base + extra) / base;
    }

    /** 同上，另外加上只對這一下有效的修飾器（盾波的盾護甲附加傷害）。 */
    void castHidden(final Player player, final String skillId, final int level, final Location at, final long cooldown, final LivingEntity target,
                    final List<Modifier> local) {
        castHidden(player, skillId, level, at, cooldown, target, local, 1);
    }

    /** 2026-10-27：同上，半徑再乘 radiusScale（堅壁戰吼的品質：盾波半徑 +x 格）。 */
    void castHidden(final Player player, final String skillId, final int level, final Location at, final long cooldown, final LivingEntity target,
                    final List<Modifier> local, final double radiusScale) {
        final SkillDefinition sub = service.config().skill(skillId);
        if (sub == null) {
            return;
        }
        final long now = now();
        if (cooldown > 0) {
            final String key = key(player, skillId);
            if (hiddenReady.getOrDefault(key, 0L) > now) {
                return;
            }
            hiddenReady.put(key, now + cooldown);
        }
        final PlayerState state = plugin.players().get(player);
        SkillBuild build = SkillBuild.of(service.config(), sub, Math.max(1, level), state.level(), false, List.of(), state.weaponTags(),
                plugin.players().sheet(player));
        if (!local.isEmpty()) {
            build = build.withProfile(build.profile().withLocal(local));
        }
        if (radiusScale > 1) {
            build = build.withAreaAndProjectiles(build.areaScale() * radiusScale, build.projectiles());
        }
        final org.bukkit.util.Vector direction = target != null
                ? SkillTargets.center(target).toVector().subtract(at.toVector()) : SkillTargets.facing(player);
        service.procCast(Origin.at(player, at.clone().add(0, 0.2, 0), direction.lengthSquared() < 1e-6 ? SkillTargets.facing(player)
                : direction.normalize()), build, target);
    }

    // =====================================================================
    // 戰吼輔助：碎甲吼、契血嘯、碎晶嘯
    // =====================================================================

    /** 碎甲吼：已經用掉的完全破甲（怪物 → 破甲結束之前不會再炸一次）。 */
    private final Map<UUID, Long> consumedBreak = new HashMap<>();
    /** 契血嘯：最近用過幾次（玩家 → tick 清單）。 */
    private final Map<UUID, List<Long>> pactUses = new HashMap<>();

    /** 戰吼吼完、算完強度之後（Warcries 呼叫）：戰吼輔助的效果；回傳多強化幾次（碎晶嘯）。 */
    int onWarcry(final Player player, final SkillBuild build, final List<LivingEntity> enemies, final double radius, final StringBuilder notice) {
        final StatSheet effective = build.profile().effective(plugin.players().sheet(player));
        final Set<String> tags = build.profile().tags();
        final long now = now();
        int bonus = 0;
        // 碎甲吼（PoE2 Battershout）：用掉範圍內敵人的完全破甲，在牠身上炸一下（破掉的護甲 40% 物理傷害，2 格，傳奇更多 50%）
        if (StatKeys.flag(effective, SupportKeys.FLAG_BATTERSHOUT) && plugin.ailments() != null && plugin.monsters() != null) {
            int exploded = 0;
            for (final LivingEntity target : enemies) {
                if (!target.isValid() || !Double.isInfinite(plugin.ailments().armourBroken(target)) || consumedBreak.containsKey(target.getUniqueId())) {
                    continue;
                }
                final MonsterData data = plugin.monsters().data(target).orElse(null);
                if (data == null) {
                    continue;
                }
                final double armour = plugin.monsters().sheet(target, data).get(StatKeys.ARMOUR);
                if (armour <= 0) {
                    continue;
                }
                consumedBreak.put(target.getUniqueId(), now);
                battershout(player, target, armour * 0.4);
                exploded++;
            }
            if (exploded > 0) {
                notice.append(String.format(Locale.ROOT, "<gray>：%d 隻敵人的破甲炸開", exploded));
            }
        }
        // 契血嘯（PoE2 Paquate's Pact）：範圍內每隻敵人觸發扭曲契約（腐化之血）；代價是最近每用一次多 10% 最大生命（最多 30%）
        if (StatKeys.flag(effective, SupportKeys.FLAG_PAQUATE)) {
            final List<Long> uses = pactUses.computeIfAbsent(player.getUniqueId(), uuid -> new ArrayList<>());
            uses.removeIf(tick -> now - tick > RECENT_TICKS);
            final PlayerState state = plugin.players().get(player);
            final double percent = Math.min(30, 10.0 * uses.size());
            uses.add(now);
            if (percent > 0) {
                final double cost = Math.min(state.life() - 1, state.maxLife() * percent / 100.0);
                if (cost > 0) {
                    state.setLife(state.life() - cost);
                    plugin.players().display(player, state);
                    lifeSpent(player, build.definition().id(), cost);
                    notice.append(String.format(Locale.ROOT, "<red>（付出 %.0f%% 最大生命）", percent));
                }
            }
            int pacts = 0;
            for (final LivingEntity target : enemies) {
                if (target.isValid()) {
                    twistedPact(player, build.level(), target);
                    pacts++;
                }
            }
            if (pacts > 0) {
                notice.append(String.format(Locale.ROOT, "<gray>：%d 隻敵人被扭曲契約纏上", pacts));
            }
        }
        // 碎晶嘯（PoE2 Vruun's Aftermath）：範圍內你的冰晶全部炸掉；炸掉至少 5 個多強化 3 次攻擊
        final double crystalBonus = effective.flat(SupportKeys.WARCRY_CRYSTAL_EMPOWERS, tags);
        if (crystalBonus > 0) {
            final int broken = service.crystals().explodeAround(player, player.getLocation(), radius);
            if (broken > 0) {
                notice.append(String.format(Locale.ROOT, "<gray>：炸掉 %d 顆冰晶", broken));
            }
            if (broken >= 5) {
                bonus += (int) Math.round(crystalBonus);
            }
        }
        return bonus;
    }

    private void battershout(final Player player, final LivingEntity center, final double damage) {
        final DamagePacket packet = new DamagePacket();
        packet.add(DamageType.PHYSICAL, damage);
        final SkillProfile profile = new SkillProfile("battershout_explosion", Set.of(Tags.WARCRY, Tags.AREA, Tags.PHYSICAL), 1.0, packet, packet,
                Map.of(), List.of(Modifier.flat(StatKeys.SPELL_BASE_CRIT, 5, "碎甲吼"), Modifier.increased(StatKeys.ARMOUR_BREAK, -1.0, "碎甲吼")));
        final MonsterData data = plugin.monsters().data(center).orElse(null);
        final Location at = center.getLocation();
        for (final LivingEntity target : service.targets().around(at, 2.0, player)) {
            final MonsterData each = plugin.monsters().data(target).orElse(null);
            final boolean unique = each != null && (each.boss() || each.rarity() == MonsterRarity.UNIQUE);
            plugin.combat().skillDamage().hit(player, target, unique ? profile.scaled(1.5, "對傳奇") : profile, null);
        }
        at.getWorld().spawnParticle(Particle.EXPLOSION, at.clone().add(0, 1, 0), 2, 0.3, 0.3, 0.3, 0);
        at.getWorld().spawnParticle(Particle.BLOCK, at.clone().add(0, 1, 0), 20, 0.6, 0.6, 0.6, 0, org.bukkit.Material.IRON_BLOCK.createBlockData());
        at.getWorld().playSound(at, org.bukkit.Sound.ENTITY_GENERIC_EXPLODE, 0.7f, 1.3f);
        if (data == null) {
            consumedBreak.remove(center.getUniqueId());
        }
    }

    /** 腐化之血（契血嘯的扭曲契約）：怪物 → 每秒傷害、層數、到哪個 tick、誰的。 */
    private static final class Corrupted {
        final UUID owner;
        double dps;
        int stacks;
        long untilTick;

        Corrupted(final UUID owner) {
            this.owner = owner;
        }
    }

    private final Map<UUID, Corrupted> corrupted = new HashMap<>();
    /** 腐化之血最多疊幾層、每層持續幾 tick（PoE2：10 層、5 秒）。 */
    static final int CORRUPTED_MAX = 10;
    static final long CORRUPTED_TICKS = 100;

    private void twistedPact(final Player player, final int level, final LivingEntity target) {
        final SkillDefinition pact = service.config().skill("proc_twisted_pact");
        if (pact == null) {
            return;
        }
        final PlayerState state = plugin.players().get(player);
        final StatSheet sheet = plugin.players().sheet(player);
        final SkillBuild build = SkillBuild.of(service.config(), pact, level, state.level(), false, List.of(), state.weaponTags(), sheet);
        final double perStack = tw.exilecore.stats.DamageCalculator.expectedOutgoing(sheet, build.profile()).total();
        if (perStack <= 0) {
            return;
        }
        final Corrupted entry = corrupted.computeIfAbsent(target.getUniqueId(), uuid -> new Corrupted(player.getUniqueId()));
        entry.dps = Math.max(entry.dps, perStack);
        entry.stacks = Math.min(CORRUPTED_MAX, entry.stacks + 1);
        entry.untilTick = now() + CORRUPTED_TICKS;
        target.getWorld().spawnParticle(Particle.DUST, target.getLocation().add(0, target.getHeight() * 0.6, 0), 10, 0.3, 0.4, 0.3, 0,
                new Particle.DustOptions(org.bukkit.Color.fromRGB(0x7A0F1F), 1.4f));
    }

    // =====================================================================
    // 凍結時（冰嚙、霜仇嘯）
    // =====================================================================

    /** 被輔助的技能的事件（SkillService.procEvent）。 */
    void event(final Player player, final String skillId, final String event, final LivingEntity target) {
        lineage.event(player, skillId, event, target); // 2026-10-27 照 PoE2：血脈輔助（凝破、熔甲、淵賜）
        extras.event(player, skillId, event, target); // 2026-10-27：重暈時（實用魔法、先鋒、合軌）
        if (!"freeze".equals(event)) {
            return;
        }
        final String id = SupportProcs.baseId(skillId);
        // 冰嚙（PoE2 Ice Bite）：凍結時 x 秒內你獲得 y% 傷害為額外冰冷傷害
        final double bite = service.supportFlat(player, id, SupportKeys.ICE_BITE);
        if (bite > 0) {
            final double seconds = Math.max(1, service.supportFlat(player, id, SupportKeys.ICE_BITE_SECONDS));
            service.buff(player, "ice_bite", "冰嚙", (int) Math.round(seconds * 20),
                    List.of(Modifier.flat(StatKeys.damageExtra(DamageType.COLD), bite, "冰嚙")));
        }
        // 霜仇嘯（PoE2 Bhatair's Vengeance）：凍結時每 2 點怒氣，你和身邊的隊友 15 秒內獲得 1% 傷害為額外冰冷傷害
        final double fang = service.supportFlat(player, id, SupportKeys.SHATTER_FANG);
        if (fang > 0) {
            final int rage = service.resources().rage(player);
            final double percent = Math.floor(rage / 2.0);
            if (percent > 0) {
                final List<Modifier> mods = List.of(Modifier.flat(StatKeys.damageExtra(DamageType.COLD), percent, "霜仇"));
                final int ticks = (int) Math.round(fang * 20);
                service.buff(player, "shatter_fang", "霜仇", ticks, mods);
                if (plugin.party() != null) {
                    for (final UUID member : plugin.party().groupMembers(player.getUniqueId())) {
                        final Player ally = member.equals(player.getUniqueId()) ? null : plugin.getServer().getPlayer(member);
                        if (ally != null && ally.getWorld().equals(player.getWorld()) && ally.getLocation().distanceSquared(player.getLocation()) <= PRESENCE * PRESENCE) {
                            service.buff(ally, "shatter_fang", "霜仇", ticks, mods);
                        }
                    }
                }
            }
        }
    }

    /** 「身邊」（PoE2 的 Presence）多遠。 */
    static final double PRESENCE = 4;

    // =====================================================================
    // 熔魂嘯：火山步、火山噴發
    // =====================================================================

    /** 熔魂嘯期間：步數技能、噴發技能、等級、到哪個 tick、這次走了多遠。 */
    private static final class Kaom {
        final String steps;
        final String eruption;
        final int level;
        final long untilTick;
        double walked;

        Kaom(final String steps, final String eruption, final int level, final long untilTick) {
            this.steps = steps;
            this.eruption = eruption;
            this.level = level;
            this.untilTick = untilTick;
        }
    }

    private final Map<UUID, Kaom> kaom = new HashMap<>();
    /** 火山步每走幾格一次；火山噴發的冷卻（tick）。 */
    static final double STEP_METRES = 1.2;
    static final long ERUPTION_COOLDOWN = 8;

    /** 熔魂嘯吼完（Warcries 呼叫）。 */
    void startKaom(final Player player, final WarcrySpec spec, final int level, final long untilTick) {
        if (spec.extras().steps() != null || spec.extras().eruption() != null) {
            kaom.put(player.getUniqueId(), new Kaom(spec.extras().steps(), spec.extras().eruption(), level, untilTick));
        }
    }

    private void stepped(final Player player, final double step) {
        final Kaom active = kaom.get(player.getUniqueId());
        if (active == null || active.steps == null) {
            return;
        }
        if (now() > active.untilTick) {
            kaom.remove(player.getUniqueId());
            return;
        }
        active.walked += step;
        if (active.walked >= STEP_METRES) {
            active.walked = 0;
            castHidden(player, active.steps, active.level, player.getLocation(), 0, null);
        }
    }

    // =====================================================================
    // 擊中前（CombatListener 呼叫）
    // =====================================================================

    /**
     * 擊中前的條件加成（玩家打的）：炙獵（對點燃中的敵人額外火焰）、遠穿（照距離）、釘地 II（對被定住的敵人暴擊率）。
     */
    public List<Modifier> preHit(final Player attacker, final LivingEntity defender, final StatSheet skillSheet, final Set<String> tags) {
        return preHit(attacker, defender, skillSheet, tags, null);
    }

    /** 2026-10-27：多一個這一下的技能描述（完美時機、地面效果、必然暴擊要看是哪一下）。 */
    public List<Modifier> preHit(final Player attacker, final LivingEntity defender, final StatSheet skillSheet, final Set<String> tags,
                                 final SkillProfile skill) {
        final List<Modifier> list = new ArrayList<>(extras.preHit(attacker, defender, skillSheet, tags, skill));
        list.addAll(lineage.preHit(attacker, defender, skillSheet, tags)); // 2026-10-27 照 PoE2：血脈輔助
        final double extraFire = skillSheet.flat(SupportKeys.EXTRA_FIRE_VS_IGNITED, tags);
        if (extraFire > 0 && plugin.ailments() != null && plugin.ailments().has(defender, AilmentType.IGNITE)) {
            list.add(Modifier.flat(StatKeys.damageExtra(DamageType.FIRE), extraFire, "炙獵"));
        }
        final double far = skillSheet.flat(SupportKeys.DAMAGE_FAR, tags);
        if (far > 0 && attacker.getWorld().equals(defender.getWorld())) {
            final double distance = attacker.getLocation().distance(defender.getLocation());
            final double scale = Math.max(0, Math.min(1, (distance - 3.5) / 3.5));
            if (scale > 0) {
                list.add(new Modifier(StatKeys.DAMAGE, ModType.MORE, far * scale / 100.0, Set.of(Tags.ATTACK), "遠穿"));
            }
        }
        final double vsImmobile = skillSheet.increased(SupportKeys.CRIT_VS_IMMOBILISED, tags);
        if (vsImmobile != 0 && immobilised(defender)) {
            list.add(Modifier.increased(StatKeys.CRIT_CHANCE, vsImmobile, "對被定住的敵人"));
        }
        return list;
    }

    /** 被定住（PoE2 的 Immobilised：凍結、重暈、定身、電擊）。 */
    private boolean immobilised(final LivingEntity target) {
        if (plugin.ailments() != null && (plugin.ailments().isFrozen(target) || plugin.ailments().heavyStunned(target)
                || plugin.ailments().has(target, AilmentType.ELECTROCUTE))) {
            return true;
        }
        return plugin.combat() != null && plugin.combat().debuffs().has(target.getUniqueId(), "pin", now());
    }

    /** 只減擊中的傷害（燎原、深切）：這一下的傷害倍率（1 = 沒有）。 */
    public static double hitOnlyFactor(final StatSheet skillSheet, final Set<String> tags) {
        return Math.max(0, skillSheet.more(SupportKeys.DAMAGE_HIT, tags));
    }

    /**
     * 2026-10-27：面板、技能說明、網頁估計用：同 {@link #hitOnlyFactor(StatSheet, Set)}，標籤照 CombatListener 的 hitTags（攻擊一律含物理）。
     */
    public static double hitOnlyFactor(final StatSheet sheet, final tw.exilecore.stats.SkillProfile profile) {
        final Set<String> tags = new java.util.HashSet<>(profile.tags());
        if (tags.contains(tw.exilecore.stats.Tags.ATTACK)) {
            tags.add(tw.exilecore.stats.Tags.PHYSICAL);
        }
        return hitOnlyFactor(profile.effective(sheet), tags);
    }

    /** 擊中的結果乘上倍率（異常照原本的算，CombatListener 另外留一份）。 */
    public static HitResult scaled(final HitResult result, final double factor) {
        if (factor == 1.0 || !result.hit() || result.blocked()) {
            return result;
        }
        return new HitResult(result.hit(), result.blocked(), result.crit(), new DamagePacket(result.outgoing()).scale(factor),
                new DamagePacket(result.dealt()).scale(factor), result.esDamage() * factor, result.lifeDamage() * factor, result.deflected());
    }

    /** 斷命 I／II：只對稀有、傳奇（含 Boss）。 */
    public static boolean cullsRare(final StatSheet skillSheet, final MonsterData monster) {
        return monster != null && (monster.boss() || monster.rarity() == MonsterRarity.RARE || monster.rarity() == MonsterRarity.UNIQUE)
                && StatKeys.flag(skillSheet, SupportKeys.FLAG_CULLING_RARE);
    }

    /** 斷命門檻的倍率（斷命 II 的增益）。 */
    public static double cullScale(final StatSheet skillSheet) {
        return Math.max(0, 1 + skillSheet.increased(SupportKeys.CULL_THRESHOLD, Set.of()));
    }

    /** 斷命打死敵人（CombatListener 呼叫）：斷命 II 給 20 秒「斷命門檻增加 20%」。 */
    public void onCull(final Player player, final StatSheet skillSheet, final Set<String> tags) {
        extras.onCull(player, skillSheet, tags); // 2026-10-27 照 PoE2 Thrill of the Kill
        final double bonus = skillSheet.flat(SupportKeys.CULL_THRESHOLD_ON_CULL, tags);
        if (bonus > 0) {
            service.buff(player, "cull_threshold", "斷命", 400, List.of(Modifier.increased(SupportKeys.CULL_THRESHOLD, bonus / 100.0, "斷命")));
        }
    }

    /** 額外的破甲百分比：霜裂甲（打被你凍結的敵人）、烙印深（打被標記的敵人）。碎甲吼的戰吼不能破甲：回傳負無限大。 */
    public double extraBreak(final Player player, final StatSheet skillSheet, final Set<String> context, final LivingEntity defender) {
        if (StatKeys.flag(skillSheet, SupportKeys.FLAG_BATTERSHOUT)) {
            return Double.NEGATIVE_INFINITY;
        }
        double extra = 0;
        final double frozen = skillSheet.flat(SupportKeys.BREAK_VS_FROZEN, context);
        if (frozen > 0 && plugin.ailments() != null && plugin.ailments().isFrozen(defender)) {
            extra += frozen;
        }
        final Marked mark = marked.get(defender.getUniqueId());
        if (mark != null && mark.source().equals(player.getUniqueId()) && mark.untilTick() > now()
                && plugin.combat() != null && plugin.combat().debuffs().has(defender.getUniqueId(), mark.skillId(), now())) {
            extra += mark.breakPercent();
        }
        return extra + extras.extraBreak(player, skillSheet, context, defender); // 2026-10-27 照 PoE2 Outmaneuver（對被招架的）
    }

    /**
     * 異常用的攻擊方屬性表（還沒疊輔助寶石的那張再疊上條件加成）：寒焚（對凍結的點燃更多）、淬毒 III（對流血的中毒更多）、
     * 急蝕（技能與傷害型異常的持續時間更少、非異常持續傷害更多）、速蝕（滿血的敵人：非異常持續傷害更多）。
     */
    public StatSheet ailmentSheet(final StatSheet attackerSheet, final StatSheet skillSheet, final SkillProfile skill, final LivingEntity defender,
                                  final MonsterData monster) {
        return ailmentSheet(null, attackerSheet, skillSheet, skill, defender, monster);
    }

    /** 2026-10-27：多一個攻擊方（玩家才看：同病相憐看你身上的異常、切口看你最近用掉的切割）。 */
    public StatSheet ailmentSheet(final LivingEntity attacker, final StatSheet attackerSheet, final StatSheet skillSheet, final SkillProfile skill,
                                  final LivingEntity defender, final MonsterData monster) {
        final Set<String> tags = skill.tags();
        final List<Modifier> extra = new ArrayList<>();
        if (attacker instanceof Player player) {
            extras.ailmentExtra(player, skillSheet, skill, defender, extra); // 2026-10-27：病變、雷焚、同病相憐、汲蝕、狂亂、切口、霧凇、合軌
        }
        if (plugin.ailments() != null) {
            final double vsFrozen = skillSheet.more(SupportKeys.IGNITE_VS_FROZEN, tags);
            if (vsFrozen != 1 && plugin.ailments().isFrozen(defender)) {
                extra.add(Modifier.more(StatKeys.IGNITE_MAGNITUDE, vsFrozen - 1, "寒焚"));
                final double unique = skillSheet.more(SupportKeys.IGNITE_VS_FROZEN_UNIQUE, tags);
                if (unique != 1 && monster != null && (monster.boss() || monster.rarity() == MonsterRarity.UNIQUE)) {
                    extra.add(Modifier.more(StatKeys.IGNITE_MAGNITUDE, unique - 1, "寒焚"));
                }
            }
            final double vsBleeding = skillSheet.more(SupportKeys.POISON_VS_BLEEDING, tags);
            if (vsBleeding != 1 && plugin.ailments().has(defender, AilmentType.BLEED)) {
                extra.add(Modifier.more(StatKeys.POISON_MAGNITUDE, vsBleeding - 1, "淬毒"));
            }
        }
        // 急蝕：傷害型異常（點燃、流血、中毒）的持續時間更少 x%（異常模組只讀「增加」，換算成等量的增加）
        final double shorter = skillSheet.more(SupportKeys.SKILL_AND_AILMENT_DURATION, tags);
        if (shorter != 1) {
            for (final String stat : new String[] {StatKeys.IGNITE_DURATION, StatKeys.BLEED_DURATION}) {
                final double increased = skillSheet.increased(stat, tags) + skillSheet.increased(StatKeys.AILMENT_DURATION, tags);
                extra.add(Modifier.increased(stat, (1 + increased) * (shorter - 1), "急蝕"));
            }
            extra.add(Modifier.more(StatKeys.POISON_DURATION, shorter - 1, "急蝕"));
        }
        // 非異常的持續傷害（混沌減益）：急蝕更多 x%、速蝕對滿血的敵人更多 x%
        double dotMore = skillSheet.more(SupportKeys.DAMAGE_SKILL_DOT, tags);
        final double fullLife = skillSheet.more(SupportKeys.DOT_VS_FULL_LIFE, tags);
        if (fullLife != 1 && fullLife(defender)) {
            dotMore *= fullLife;
        }
        final double debuff = skillSheet.flat(StatKeys.DEBUFF_CHAOS, tags);
        if (dotMore != 1 && debuff > 0) {
            extra.add(Modifier.flat(StatKeys.DEBUFF_CHAOS, debuff * (dotMore - 1), "持續傷害"));
        }
        final double debuffBase = skillSheet.flat(tw.exilecore.stats.SkillMechanicKeys.DEBUFF_CHAOS_BASE, tags); // 2026-10-26（汲魂彈）
        if (dotMore != 1 && debuffBase > 0) {
            extra.add(Modifier.flat(tw.exilecore.stats.SkillMechanicKeys.DEBUFF_CHAOS_BASE, debuffBase * (dotMore - 1), "持續傷害"));
        }
        if (shorter != 1 && (debuff > 0 || debuffBase > 0)) {
            final double seconds = skillSheet.flat(StatKeys.DEBUFF_DURATION, Set.of()) > 0 ? skillSheet.flat(StatKeys.DEBUFF_DURATION, Set.of()) : 4;
            extra.add(Modifier.flat(StatKeys.DEBUFF_DURATION, seconds * shorter - skillSheet.flat(StatKeys.DEBUFF_DURATION, Set.of()), "急蝕"));
        }
        return extra.isEmpty() ? attackerSheet : attackerSheet.overlay(extra);
    }

    /** 目標現在是滿血（原版血量 ≥ 上限的 99.5%）。 */
    static boolean fullLife(final LivingEntity entity) {
        final org.bukkit.attribute.AttributeInstance max = entity.getAttribute(org.bukkit.attribute.Attribute.MAX_HEALTH);
        return max != null && max.getValue() > 0 && entity.getHealth() >= max.getValue() * 0.995;
    }

    // =====================================================================
    // 印記（烙印深、久印）
    // =====================================================================

    /** 被標記的怪物：誰的、哪個印記、破甲幾 %、到哪個 tick。 */
    private record Marked(UUID source, String skillId, double breakPercent, long untilTick) {
    }

    private final Map<UUID, Marked> marked = new HashMap<>();
    /** 久印：還沒觸發過一次的印記（怪物/玩家/印記）。 */
    private final Set<String> eternal = new java.util.HashSet<>();

    /** 印記放上去了（SkillExecutor 呼叫）。 */
    void markApplied(final Player player, final SkillBuild build, final LivingEntity target, final long untilTick) {
        final StatSheet effective = build.profile().effective(plugin.players().sheet(player));
        final double breakPercent = effective.flat(SupportKeys.MARK_BREAK, build.profile().tags());
        marked.values().removeIf(each -> each.source().equals(player.getUniqueId()));
        if (breakPercent > 0) {
            marked.put(target.getUniqueId(), new Marked(player.getUniqueId(), build.definition().id(), breakPercent, untilTick));
        }
        eternal.removeIf(each -> each.contains("/" + player.getUniqueId() + "/"));
        if (StatKeys.flag(effective, SupportKeys.FLAG_MARK_ETERNAL)) {
            eternal.add(target.getUniqueId() + "/" + player.getUniqueId() + "/" + build.definition().id());
        }
        if (marked.size() > 1024) {
            marked.values().removeIf(each -> each.untilTick() < now());
        }
        extras.markApplied(player, effective, build.profile().tags(), target, untilTick); // 2026-10-27 照 PoE2 Mark of Siphoning
    }

    /** 印記觸發的時候（SkillTriggers 呼叫）：久印的第一次不用掉，回傳 true = 這次保留。 */
    boolean keepMark(final LivingEntity target, final Player player, final String skillId) {
        return eternal.remove(target.getUniqueId() + "/" + player.getUniqueId() + "/" + skillId);
    }

    // =====================================================================
    // 擊中之後
    // =====================================================================

    /** 定身累積（怪物 → 累積、最後一次累積的 tick）；定身之後免疫到哪個 tick。 */
    private final Map<UUID, double[]> pinBuildup = new HashMap<>();
    private final Map<UUID, Long> pinImmune = new HashMap<>();
    /** 定身累積幾 tick 沒加就歸零；定身之後幾 tick 不會再被定身。 */
    static final long PIN_DECAY_TICKS = 80;
    static final long PIN_IMMUNE_TICKS = 80;
    /** 元崩的冷卻（tick，PoE2 1 秒）。 */
    private final Map<UUID, Long> dischargeReady = new HashMap<>();

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onHit(final HitEvent event) {
        if (!(event.attacker() instanceof Player player) || event.skill() == null || !event.result().hit() || event.result().blocked()
                || event.defender() instanceof Player || plugin.monsters() == null) {
            return;
        }
        final LivingEntity target = event.defender();
        final MonsterData monster = plugin.monsters().data(target).orElse(null);
        if (monster == null) {
            return;
        }
        if (event.skill().tags().contains(tw.exilecore.stats.Tags.ATTACK) && !event.skill().tags().contains(tw.exilecore.stats.Tags.TOTEM_HIT)) {
            gainGlory(player, "attack_hit", service.warcries().power(target)); // 2026-10-26 照 PoE2：戰旗的榮耀（攻擊打中照怪物強度 100%）
        }
        final SkillProfile skill = event.skill();
        final StatSheet effective = skill.effective(plugin.players().sheet(player));
        final Set<String> tags = skill.tags();
        final long now = now();
        final double life = Math.max(1, plugin.monsters().sheet(target, monster).get(StatKeys.LIFE_MAX));
        // 釘地（PoE2 Pin）：物理傷害累積定身；暈眩累積的加成改成加在定身累積上
        if (StatKeys.flag(effective, SupportKeys.FLAG_PIN_BUILDUP)) {
            pin(player, target, monster, effective, tags, event.result().dealt().get(DamageType.PHYSICAL) * 1.5 / life, now);
        }
        // 枯觸（PoE2 Withering Touch）：每 100% 異常門檻的混沌傷害 175% 機率凋零（可以超過 100%）
        final double wither = effective.flat(SupportKeys.WITHER_PER_THRESHOLD, tags);
        final double chaos = event.result().dealt().get(DamageType.CHAOS);
        if (wither > 0 && chaos > 0 && plugin.combat() != null) {
            final double chance = chaos / life * wither / 100.0;
            int stacks = (int) Math.floor(chance);
            if (plugin.random().nextDouble() < chance - stacks) {
                stacks++;
            }
            if (stacks > 0) {
                final double seconds = Math.max(1, effective.flat(SupportKeys.WITHER_SECONDS, tags));
                tw.exilecore.combat.HitEffects.addWithered(plugin.combat().debuffs(), target.getUniqueId(), player.getUniqueId(), now, stacks,
                        Math.round(seconds * 20), Math.max(0, 1 + effective.increased(tw.exilecore.stats.TreeEffectKeys.WITHERED_EFFECT, tags))); // 2026-10-26：凋零效果
            }
        }
        if (plugin.ailments() == null) {
            return;
        }
        // 元崩（PoE2 Elemental Discharge）：用掉點燃、凍結、感電，觸發元素崩解（照你的智慧，只有用掉的那幾種元素）
        if (StatKeys.flag(effective, SupportKeys.FLAG_ELEMENTAL_DISCHARGE) && dischargeReady.getOrDefault(player.getUniqueId(), 0L) <= now) {
            final boolean ignited = plugin.ailments().has(target, AilmentType.IGNITE);
            final boolean frozen = plugin.ailments().isFrozen(target);
            final boolean shocked = plugin.ailments().has(target, AilmentType.SHOCK);
            if (ignited || frozen || shocked) {
                dischargeReady.put(player.getUniqueId(), now + 20);
                if (ignited) {
                    plugin.ailments().cure(target, AilmentType.IGNITE);
                }
                if (frozen) {
                    plugin.ailments().cure(target, AilmentType.FREEZE);
                }
                if (shocked) {
                    plugin.ailments().consume(target, AilmentType.SHOCK, player); // 2026-10-27 無限循環：可能重新施加
                }
                discharge(player, target, ignited, frozen, shocked);
            }
        }
        // 鏽甲（PoE2 Corrosion）、寒焚（點燃時用掉凍結）：異常是這一下之後才算，下一 tick 再看
        final double corrosion = effective.flat(SupportKeys.POISON_BREAKS_ARMOUR, tags);
        final boolean frostfire = StatKeys.flag(effective, SupportKeys.FLAG_CONSUME_FREEZE_ON_IGNITE) && plugin.ailments().isFrozen(target);
        if (corrosion > 0 || frostfire) {
            final int before = plugin.ailments().has(target, AilmentType.POISON) ? plugin.ailments().poisonStacks(target) : 0;
            final boolean wasIgnited = plugin.ailments().has(target, AilmentType.IGNITE);
            // 這一下的中毒（照異常模組的算法估：物理＋混沌 × 中毒每秒傷害比例 × 加成 × 持續時間）
            final Set<String> poisonContext = skill.contextFor(DamageType.CHAOS);
            final double poisonBase = event.result().dealt().get(DamageType.PHYSICAL) + event.result().dealt().get(DamageType.CHAOS);
            final double poisonTotal = plugin.ailments().config().poisonDps(poisonBase, effective.increased(StatKeys.POISON_MAGNITUDE, poisonContext),
                    effective.more(StatKeys.POISON_MAGNITUDE, poisonContext)) * plugin.ailments().config().poisonDuration()
                    * Math.max(0, 1 + effective.increased(StatKeys.POISON_DURATION, poisonContext)) * effective.more(StatKeys.POISON_DURATION, poisonContext);
            plugin.getServer().getScheduler().runTask(plugin, () -> {
                if (!target.isValid() || target.isDead() || plugin.ailments() == null) {
                    return;
                }
                if (frostfire && plugin.ailments().has(target, AilmentType.IGNITE) && (!wasIgnited || plugin.ailments().isFrozen(target))) {
                    plugin.ailments().cure(target, AilmentType.FREEZE);
                }
                if (corrosion > 0 && plugin.ailments().has(target, AilmentType.POISON) && (before == 0 || plugin.ailments().poisonStacks(target) > before)) {
                    final double armour = plugin.monsters().sheet(target, monster).get(StatKeys.ARMOUR);
                    plugin.ailments().breakArmour(target, monster, poisonTotal, corrosion, armour);
                }
            });
        }
        // 熔魂嘯：近戰打擊打中時觸發火山噴發（0.4 秒一次）
        final Kaom active = kaom.get(player.getUniqueId());
        if (active != null && active.eruption != null && now <= active.untilTick && tags.contains(Tags.MELEE) && tags.contains(Tags.STRIKE)
                && skill.isAttack() && !skill.id().startsWith("ancestral_")) {
            castHidden(player, active.eruption, active.level, target.getLocation(), ERUPTION_COOLDOWN, target);
        }
    }

    private void pin(final Player player, final LivingEntity target, final MonsterData monster, final StatSheet effective, final Set<String> tags,
                     final double amount, final long now) {
        if (amount <= 0 || pinImmune.getOrDefault(target.getUniqueId(), 0L) > now || plugin.combat() == null) {
            return;
        }
        final double scale = Math.max(0, 1 + effective.increased(StatKeys.STUN_BUILDUP, tags)) * Math.max(0, effective.more(SupportKeys.PIN_BUILDUP, tags));
        final double[] state = pinBuildup.computeIfAbsent(target.getUniqueId(), uuid -> new double[2]);
        if (now - state[1] > PIN_DECAY_TICKS) {
            state[0] = 0;
        }
        state[0] += amount * scale;
        state[1] = now;
        if (state[0] < 1) {
            return;
        }
        pinBuildup.remove(target.getUniqueId());
        final long base = monster.boss() || monster.rarity() == MonsterRarity.UNIQUE ? 20 : monster.rarity() == MonsterRarity.RARE ? 30 : 40;
        final long ticks = Math.max(1, Math.round(base * Math.max(0, effective.more(SupportKeys.PIN_DURATION, tags))
                * Math.max(0.1, 1 + effective.increased(SupportKeys.PIN_DURATION, tags)))); // 2026-10-26 照 PoE2 天賦樹：增加定身持續時間
        plugin.combat().debuffs().apply(target.getUniqueId(), new tw.exilecore.combat.Debuffs.Debuff("pin", player.getUniqueId(), now + ticks,
                0, false, null, List.of(), 1.0));
        pinImmune.put(target.getUniqueId(), now + ticks + PIN_IMMUNE_TICKS);
        target.getWorld().spawnParticle(Particle.CRIT, target.getLocation().add(0, target.getHeight() * 0.7, 0), 8, 0.3, 0.3, 0.3, 0.05);
        if (plugin.combat().numbers() != null) {
            plugin.combat().numbers().word(player, target, "定身");
        }
        if (plugin.combat().charges() != null) {
            plugin.combat().charges().onEvent(player, "pin", effective, tags, target);
        }
    }

    /** 元素崩解：2 格，照智慧：火 40–60%、冰 80–120%、電 1–120%（只有用掉的元素），暴擊率 10%。 */
    private void discharge(final Player player, final LivingEntity center, final boolean fire, final boolean cold, final boolean lightning) {
        final double intelligence = Math.max(0, plugin.players().sheet(player).get(StatKeys.INT));
        final DamagePacket min = new DamagePacket();
        final DamagePacket max = new DamagePacket();
        final Set<String> tags = new java.util.HashSet<>(Set.of(Tags.SPELL, Tags.AREA, Tags.ELEMENTAL));
        if (fire) {
            min.add(DamageType.FIRE, intelligence * 0.4);
            max.add(DamageType.FIRE, intelligence * 0.6);
            tags.add(Tags.FIRE);
        }
        if (cold) {
            min.add(DamageType.COLD, intelligence * 0.8);
            max.add(DamageType.COLD, intelligence * 1.2);
            tags.add(Tags.COLD);
        }
        if (lightning) {
            min.add(DamageType.LIGHTNING, intelligence * 0.01);
            max.add(DamageType.LIGHTNING, intelligence * 1.2);
            tags.add(Tags.LIGHTNING);
        }
        if (max.total() <= 0) {
            return;
        }
        final SkillProfile profile = new SkillProfile("elemental_discharge", tags, 1.0, min, max, Map.of(),
                List.of(Modifier.flat(StatKeys.SPELL_BASE_CRIT, 10, "元素崩解"), Modifier.flat(StatKeys.FLAG_NO_AILMENTS, 1, "元素崩解")));
        final Location at = center.getLocation();
        for (final LivingEntity target : service.targets().around(at, 2.0, player)) {
            plugin.combat().skillDamage().hit(player, target, profile, null);
        }
        at.getWorld().spawnParticle(Particle.FLASH, at.clone().add(0, 1, 0), 1, 0, 0, 0, 0, org.bukkit.Color.WHITE);
        at.getWorld().spawnParticle(Particle.FLAME, at.clone().add(0, 1, 0), fire ? 12 : 0, 0.8, 0.5, 0.8, 0.02);
        at.getWorld().spawnParticle(Particle.SNOWFLAKE, at.clone().add(0, 1, 0), cold ? 12 : 0, 0.8, 0.5, 0.8, 0.02);
        at.getWorld().spawnParticle(Particle.ELECTRIC_SPARK, at.clone().add(0, 1, 0), lightning ? 12 : 0, 0.8, 0.5, 0.8, 0.05);
        at.getWorld().playSound(at, org.bukkit.Sound.ENTITY_GENERIC_EXPLODE, 0.6f, 1.6f);
    }

    /** 骸舞（PoE2 Danse Macabre）：祭品多用掉一隻骷髏；有用到回傳增益效果多幾（0.3 = 增加 30%），沒有 0。 */
    double offeringExtraSkeleton(final Player player, final SkillBuild build) {
        final StatSheet effective = build.profile().effective(plugin.players().sheet(player));
        if (!StatKeys.flag(effective, SupportKeys.FLAG_EXTRA_SKELETON) || service.minions() == null) {
            return 0;
        }
        return service.minions().sacrificeSkeleton(player, player.getLocation(), Warcries.SPIKE_SEARCH) ? 0.3 : 0;
    }

    // =====================================================================
    // 冰緩地面（寒夜嗥強化的猛擊）
    // =====================================================================

    private record Ground(UUID owner, Location at, double radius, long untilTick) {
    }

    private final List<Ground> grounds = new ArrayList<>();

    void chilledGround(final Player player, final Location at, final double radius, final long ticks) {
        grounds.add(new Ground(player.getUniqueId(), at.clone(), radius, now() + ticks));
    }

    // =====================================================================
    // 每 5 tick
    // =====================================================================

    void tick(final long now) {
        for (final Player player : plugin.getServer().getOnlinePlayers()) {
            sampleTravel(player);
        }
        lineage.tick(now); // 2026-10-27 照 PoE2：血脈輔助
        extras.tick(now); // 2026-10-27
        if (now % 10 == 0) {
            tickGrounds(now);
            tickCorrupted(now);
        }
        if (now % 20 == 0) {
            tickInfernal();
            consumedBreak.entrySet().removeIf(entry -> {
                final org.bukkit.entity.Entity entity = plugin.getServer().getEntity(entry.getKey());
                return !(entity instanceof LivingEntity living) || !living.isValid() || plugin.ailments() == null
                        || !Double.isInfinite(plugin.ailments().armourBroken(living));
            });
            pinImmune.values().removeIf(until -> until <= now);
            pinBuildup.values().removeIf(state -> now - state[1] > PIN_DECAY_TICKS);
            kaom.values().removeIf(each -> each.untilTick < now);
        }
    }

    private void tickGrounds(final long now) {
        if (grounds.isEmpty() || plugin.ailments() == null) {
            return;
        }
        for (final Iterator<Ground> it = grounds.iterator(); it.hasNext(); ) {
            final Ground ground = it.next();
            final Player owner = plugin.getServer().getPlayer(ground.owner());
            if (now > ground.untilTick() || owner == null || !owner.getWorld().equals(ground.at().getWorld())) {
                it.remove();
                continue;
            }
            for (final LivingEntity target : service.targets().around(ground.at(), ground.radius(), owner)) {
                plugin.ailments().groundOnMonster(target, 0, Math.max(1, plugin.monsters() == null ? 1
                        : plugin.monsters().data(target).map(data -> plugin.monsters().sheet(target, data).get(StatKeys.LIFE_MAX) * 0.05).orElse(1.0)), 15);
            }
            for (int i = 0; i < 10; i++) {
                final double a = Math.PI * 2 * i / 10;
                Fx.spawn(ground.at().getWorld(), Particle.SNOWFLAKE, ground.at().clone().add(Math.cos(a) * ground.radius(), 0.1,
                        Math.sin(a) * ground.radius()), 1, 0, 0, 0, 0);
            }
        }
    }

    private void tickCorrupted(final long now) {
        if (corrupted.isEmpty() || plugin.ailments() == null) {
            return;
        }
        for (final Iterator<Map.Entry<UUID, Corrupted>> it = corrupted.entrySet().iterator(); it.hasNext(); ) {
            final Map.Entry<UUID, Corrupted> entry = it.next();
            final org.bukkit.entity.Entity entity = plugin.getServer().getEntity(entry.getKey());
            final Corrupted state = entry.getValue();
            if (!(entity instanceof LivingEntity living) || !living.isValid() || living.isDead() || now > state.untilTick) {
                it.remove();
                continue;
            }
            // 每半秒扣一次（每秒傷害 × 層數 × 0.5 秒）
            plugin.ailments().dealFlatPhysical(living, state.dps * state.stacks * 0.5, state.owner);
        }
    }

    /** 燃僕（PoE2 Infernal Legion）：召喚物每秒受到最大生命 10% 的火焰傷害，並點燃身邊的敵人（像造成最大生命 10% 的基礎火焰傷害）。 */
    private void tickInfernal() {
        if (service.minions() == null || plugin.combat() == null) {
            return;
        }
        for (final MinionService.Minion minion : service.minions().all()) {
            double percent = 0;
            double radius = 0;
            for (final SupportDefinition support : minion.build.supports()) {
                for (final Modifier modifier : support.modifiers()) {
                    if (modifier.stat().equals(SupportKeys.INFERNAL_LEGION)) {
                        percent = Math.max(percent, modifier.value());
                    } else if (modifier.stat().equals(SupportKeys.INFERNAL_LEGION_RADIUS)) {
                        radius = Math.max(radius, modifier.value());
                    }
                }
            }
            if (percent <= 0 || !minion.mob.isValid() || minion.effective == null) {
                continue;
            }
            final Player owner = plugin.getServer().getPlayer(minion.owner);
            if (owner == null) {
                continue;
            }
            final double maxLife = MinionSheets.life(minion.effective);
            // 自己燒：吃召喚物的火焰抗性（上限照召喚物自己的抗性上限，最多 90%）
            // 2026-10-27：照 PoE2，非混沌的持續傷害先扣能量護盾再扣生命（這次之前直接扣生命、抗性上限寫死 75%）
            final double maxResist = Math.min(90, minion.effective.get(StatKeys.RESIST_MAX)
                    + minion.effective.flat(StatKeys.resistMax(DamageType.FIRE), Set.of()));
            final double resist = Math.min(maxResist > 0 ? maxResist : 75, minion.effective.get(StatKeys.RESIST_FIRE));
            double loss = maxLife * percent / 100.0 * (1 - resist / 100.0);
            if (loss > 0 && minion.es > 0) {
                final double absorbed = Math.min(minion.es, loss);
                minion.es -= absorbed;
                loss -= absorbed;
            }
            if (loss > 0) {
                minion.mob.setHealth(Math.max(0, minion.mob.getHealth() - loss * minion.healthScale));
                if (minion.mob.getHealth() <= 0) {
                    continue;
                }
            }
            minion.mob.getWorld().spawnParticle(Particle.FLAME, minion.mob.getLocation().add(0, minion.mob.getHeight() * 0.5, 0), 4, 0.3, 0.4, 0.3, 0.01);
            if (plugin.ailments() == null) {
                continue;
            }
            // 2026-10-27 照 PoE2：點燃是召喚物施加的——像召喚物造成「最大生命 10%」的基礎火焰傷害，吃召喚物自己的傷害、火焰傷害、點燃加成，
            // 一定點燃（不擲機率）。這次之前當作你本人的一下擊中：吃的是你的加成，還會觸發你的擊中效果（偷取、擊中回復、觸發…）
            final Set<String> fireContext = Set.of(Tags.FIRE);
            final double base = maxLife * percent / 100.0
                    * Math.max(0, 1 + minion.effective.increased(StatKeys.DAMAGE, fireContext)) * minion.effective.more(StatKeys.DAMAGE, fireContext);
            for (final LivingEntity target : service.targets().around(minion.mob.getLocation(), Math.max(1, radius), owner)) {
                plugin.ailments().igniteFrom(owner, target, base, minion.effective);
            }
        }
    }

    // =====================================================================
    // 怪物死掉、玩家離開
    // =====================================================================

    @EventHandler(priority = EventPriority.MONITOR)
    public void onDeath(final EntityDeathEvent event) {
        if (event.getEntity() instanceof Player) {
            return;
        }
        final UUID id = event.getEntity().getUniqueId();
        service.warcries().onDeath(event.getEntity()); // 焚獄戰吼：動搖的敵人燃爆
        corrupted.remove(id);
        marked.remove(id);
        pinBuildup.remove(id);
        pinImmune.remove(id);
        consumedBreak.remove(id);
    }

    @EventHandler
    public void onQuit(final PlayerQuitEvent event) {
        forget(event.getPlayer().getUniqueId());
    }

    /** 2026-10-27：清掉這個玩家的榮耀、輔助的標記、走過的距離…（離線、換角色；這次之前只有離線，而且走過的距離、久印沒清）。 */
    void forget(final UUID id) {
        lineage.forget(id); // 2026-10-27
        extras.forget(id);
        lastAt.remove(id);
        travelled.remove(id);
        eternal.removeIf(each -> each.contains("/" + id + "/"));
        kaom.remove(id);
        elementsUsed.remove(id);
        pactUses.remove(id);
        dischargeReady.remove(id);
        final String prefix = id + "/";
        glory.keySet().removeIf(key -> key.startsWith(prefix));
        echoMark.keySet().removeIf(key -> key.startsWith(prefix));
        voltMark.keySet().removeIf(key -> key.startsWith(prefix));
        hiddenReady.keySet().removeIf(key -> key.startsWith(prefix));
        corrupted.values().removeIf(each -> each.owner.equals(id));
        marked.values().removeIf(each -> each.source().equals(id));
        grounds.removeIf(each -> each.owner().equals(id));
    }
}
