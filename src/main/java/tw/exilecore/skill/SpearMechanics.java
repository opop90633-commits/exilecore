package tw.exilecore.skill;

import org.bukkit.Location;
import org.bukkit.Particle;
import org.bukkit.Sound;
import org.bukkit.SoundCategory;
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
import tw.exilecore.item.ItemRenderer;
import tw.exilecore.stats.Modifier;
import tw.exilecore.stats.StatKeys;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.HashMap;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/**
 * 2026-10-27 照 PoE2 0.5（poe2db／RePoE）的矛技能專屬機制：
 * <ul>
 *   <li>擲矛（Spear Throw，每把矛都賦予）：身上有怒星就消耗一顆，矛飛到盡頭（打中敵人、撞牆、飛到最遠）時炸開 2 格
 *       （spear_throw_blast，自己的傷害表 232% → 724%）。</li>
 *   <li>炎爆矛（Explosive Spear）：矛飛向準心指的地方（指著敵人就是牠腳下），貫穿路上的敵人，插在那裡，2.5 秒後炸開 2.8 格
 *       （explosive_spear_blast，135% → 489%，全部轉火焰）；你的引爆類技能（detonator 標籤）打在 3 格內會提早引爆。
 *       同時最多 1～5 支（照技能等級），再多時最舊的那支消失（PoE2 的 Limit：「the oldest effect will disappear」，不會炸）。
 *       身上有怒星就消耗一顆，落地馬上炸成十字形（explosive_spear_infused，200% → 724%；照矛飛的方向，每一臂從中心伸出 4 → 5.9 → 7.9 格、
 *       寬 1.5 格），並在中心留下 2 格、8 秒的燃燒地面（站在上面的敵人每秒被施加易燃，照這次爆炸的火焰傷害算，易燃疊到 50% 才點燃；點燃效果增加 100%）。
 *       2026-10-27 照 PoE2 改落點、上限、十字與燃燒地面；這次之前拋物線落地、超過上限最舊的提早炸開、怒星爆炸是等面積的圓＋一定點燃＋點燃效果更多 100%；
 *       （更早）打中敵人或牆就炸、用矛那一下的傷害（50%）。</li>
 *   <li>血嗅印（Bloodhound's Mark）：被標記的敵人身上你造成的失血（流血扣的血）300% 算成重暈累積；牠被重暈時，
 *       或死掉時失血累積到暈眩門檻 5% 以上，印記用掉、觸發血爆（bloodhound_blast，2.6 格，不吃武器、不會造成流血；
 *       照失血累積佔暈眩門檻的比例最多更多 100% 傷害與 100% 範圍）。牠在流血的時候印記不會結束。
 *       這次之前是一般的印記（護甲 -30～-50%、受到你的傷害增加 10%）。</li>
 * </ul>
 */
public final class SpearMechanics implements Listener {

    /** skills.yml 的 signature。 */
    static final Set<String> SIGNATURES = Set.of("spear_throw", "explosive_spear");

    static final String SPEAR_THROW_BLAST = "spear_throw_blast";
    static final String EXPLOSIVE_BLAST = "explosive_spear_blast";
    static final String EXPLOSIVE_INFUSED = "explosive_spear_infused";
    static final String BLOODHOUND = "blood_scent_seal";
    static final String BLOODHOUND_BLAST = "bloodhound_blast";

    /** 這一發有沒有消耗怒星（值 = 效果倍率，倍星 2）：放在技能的本地修飾器上，投射物停下時看。 */
    static final String INFUSED = "spear.frenzy_infused";

    /** 炎爆矛插多久才炸（PoE2 base_skill_detonation_time 2500 毫秒）。 */
    static final long DETONATION_TICKS = 50;
    /** 引爆類技能打在幾格內會引爆插著的炎爆矛。 */
    static final double DETONATE_RANGE = 3;
    /** 炎爆矛：準心指的點離你至少幾格（PoE2 remote_spear_mine_minimum_target_distance 10 = 1 格）。 */
    static final double MIN_TARGET = 1.0;
    /** 炎爆矛（怒星）十字的寬度（PoE2 active_skill_base_secondary_area_of_effect_radius 15 = 1.5 格，整條的寬）。 */
    static final double CROSS_WIDTH = tw.exilecore.core.Units.metres(1.5); // 2026-10-29 照站長：1 公尺 = 1.5 格（Units），照 PoE2 公尺的常數也換算
    /** 炎爆矛（怒星）燃燒地面的半徑（PoE2 active_skill_base_tertiary_area_of_effect_radius 20 = 2 格）。 */
    static final double GROUND_RADIUS = tw.exilecore.core.Units.metres(2.0); // 2026-10-29 照站長：1 公尺 = 1.5 格（Units），照 PoE2 公尺的常數也換算
    /** 炎爆矛（怒星）燃燒地面持續幾秒（PoE2 base_skill_effect_duration 8000，吃持續時間的加成）。 */
    static final double GROUND_SECONDS = 8.0;
    /** 燃燒地面多久檢查一次、同一隻敵人多久再點一次（tick）。 */
    static final int GROUND_INTERVAL = 10;
    static final int GROUND_REIGNITE = 20;

    /** 炎爆矛同時最多插幾支（1–40 級，PoE2 base_number_of_remote_spear_mines_allowed）。 */
    static final int[] EXPLOSIVE_LIMIT = {1, 1, 1, 1, 1, 1, 1, 2, 2, 2, 2, 2, 2, 2, 2, 3, 3, 3, 3, 3, 3, 3, 3, 4, 4, 4, 4, 4, 4, 4, 4,
            5, 5, 5, 5, 5, 5, 5, 5, 5};

    /** 血嗅印：失血的幾倍算成重暈累積（PoE2 hunters_mark_%_of_phys_damage_over_time_as_build_up 300）。 */
    static final double BLOOD_TO_STUN = 3.0;
    /** 血嗅印：死掉時失血累積要到暈眩門檻的幾成才觸發血爆（PoE2 5%）。 */
    static final double DEATH_THRESHOLD = 0.05;
    /** 血嗅印：還在流血時印記至少再撐幾 tick（不會在流血中結束）。 */
    static final long BLEED_HOLD = 10;

    private final ExileCore plugin;
    private final SkillService service;

    private record Lodged(Location at, SkillBuild build, long until) {
    }

    /** 炎爆矛（怒星）留下的燃燒地面：fire = 這次爆炸的火焰傷害（平均、減免前），sheet = 疊好技能修飾器的屬性表（點燃效果增加 100%）。 */
    private record Ground(Location at, double radius, long until, double fire, tw.exilecore.stats.StatSheet sheet, Map<UUID, Long> next) {
    }

    private static final class Hound {
        final UUID owner;
        final LivingEntity target;
        final int level;
        double fromBleed;
        boolean wasStunned;
        /** 2026-10-28 照 PoE2 Bloodhound's Mark 的品質：失血的幾倍算成重暈累積（3 ＋ 品質的 x%）。 */
        double toStun = BLOOD_TO_STUN;

        Hound(final UUID owner, final LivingEntity target, final int level, final boolean stunned) {
            this.owner = owner;
            this.target = target;
            this.level = level;
            this.wasStunned = stunned;
        }
    }

    private final Map<UUID, Deque<Lodged>> lodged = new HashMap<>();
    private final Map<UUID, List<Ground>> grounds = new HashMap<>();
    /** 被血嗅印標記的敵人 → 誰標的、失血累積了多少。 */
    private final Map<UUID, Hound> hounds = new HashMap<>();

    SpearMechanics(final ExileCore plugin, final SkillService service) {
        this.plugin = plugin;
        this.service = service;
    }

    // ---- 放之前：消耗怒星 ----

    /** 擲矛、炎爆矛有怒星就消耗一顆（照 PoE2：有就一定消耗），在這一下的本地修飾器上記下來。 */
    SkillBuild beforeCast(final Player player, final SkillDefinition definition, final SkillBuild build, final PlayerState state, final long now) {
        final String signature = service.config().signature(definition.id());
        if (signature == null || !SIGNATURES.contains(signature) || plugin.combat() == null || plugin.combat().charges() == null) {
            return build;
        }
        final int benefit = plugin.combat().charges().spend(player, "frenzy", build.profile().effective(state.sheet()), build.profile().tags());
        if (benefit <= 0) {
            return build;
        }
        state.notice("<#ffe14a>" + ItemRenderer.escape(definition.name()) + " 消耗怒星：" + ("spear_throw".equals(signature) ? "飛到盡頭炸開" : "落地馬上炸開")
                + (benefit > 1 ? "（倍星：炸兩次）" : ""), now + 30);
        plugin.players().display(player, state);
        return build.withProfile(build.profile().withLocal(List.of(Modifier.flat(INFUSED, benefit, "怒星"))));
    }

    // ---- 投射物停下 ----

    /**
     * 2026-10-27 照 PoE2（炎爆矛的落點在準心）：炎爆矛要飛到哪裡——準心指著敵人就是牠腳下，不然是準心指的地面（最遠 projectile.range、最近 1 格）；
     * 其他技能回傳 null（照一般投射物飛）。這次之前炎爆矛是拋物線，落在哪裡看角度。
     */
    Location landingPoint(final Origin origin, final SkillBuild build) {
        final String signature = build == null ? null : service.config().signature(build.definition().id());
        if (origin == null || !"explosive_spear".equals(signature) && !"lance".equals(signature)) { // 2026-10-31 照 PoE2：霆落矛也飛到準心插著
            return null;
        }
        final LivingEntity aimed = service.targets().aimed(origin, build.range(), 1.2);
        final Location at = aimed != null ? aimed.getLocation() : service.targets().aimPoint(origin, build.range());
        return clampTarget(origin.feet(), at, MIN_TARGET, build.range());
    }

    /** 目標點離 from（水平距離）至少 min、最多 max：太近往外推、太遠往回拉（高度不變）。 */
    static Location clampTarget(final Location from, final Location at, final double min, final double max) {
        final double dx = at.getX() - from.getX();
        final double dz = at.getZ() - from.getZ();
        final double distance = Math.sqrt(dx * dx + dz * dz);
        if (distance >= min && distance <= max) {
            return at;
        }
        if (distance < 1e-6) {
            final Vector facing = from.getDirection().setY(0);
            final Vector unit = facing.lengthSquared() < 1e-9 ? new Vector(1, 0, 0) : facing.normalize();
            return new Location(at.getWorld(), from.getX() + unit.getX() * min, at.getY(), from.getZ() + unit.getZ() * min);
        }
        final double scale = (distance < min ? min : max) / distance;
        return new Location(at.getWorld(), from.getX() + dx * scale, at.getY(), from.getZ() + dz * scale);
    }

    /** 投射物停下（ProjectileEngine）：擲矛的怒星炸開、炎爆矛插地（或怒星馬上炸成十字）。direction：矛飛的方向（十字照它擺）。 */
    void landed(final Player owner, final SkillBuild build, final tw.exilecore.stats.SkillProfile profile, final Location at, final Vector direction) {
        final String signature = service.config().signature(build.definition().id());
        if (signature == null || !SIGNATURES.contains(signature) || at == null) {
            return;
        }
        final int infused = (int) Math.round(local(profile, INFUSED));
        if ("spear_throw".equals(signature)) {
            for (int i = 0; i < infused; i++) {
                blast(owner, SPEAR_THROW_BLAST, build, at);
            }
            return;
        }
        if (infused > 0) {
            for (int i = 0; i < infused; i++) {
                crossBlast(owner, build, at, direction);
            }
            return;
        }
        final Deque<Lodged> mine = lodged.computeIfAbsent(owner.getUniqueId(), uuid -> new ArrayDeque<>());
        final int limit = EXPLOSIVE_LIMIT[Math.max(0, Math.min(EXPLOSIVE_LIMIT.length - 1, build.level() - 1))]
                + SkillLimits.bonus(plugin.players().sheet(owner), build); // 2026-10-28 照 PoE2：「上限 1 支插著的矛」吃 +x 上限（過剩）
        while (mine.size() >= limit) {
            // 2026-10-27 照 PoE2（Limit：超過上限時最舊的消失）：最舊的那支消失、不炸；這次之前最舊的提早炸開
            final Lodged gone = mine.pollFirst();
            if (gone != null) {
                Fx.as(owner.getUniqueId(), () -> gone.at().getWorld().spawnParticle(Particle.SMOKE, gone.at().clone().add(0, 0.3, 0), 6, 0.1, 0.2, 0.1, 0.01));
            }
        }
        mine.addLast(new Lodged(at.clone(), build, plugin.getServer().getCurrentTick() + DETONATION_TICKS));
        at.getWorld().playSound(at, Sound.ITEM_TRIDENT_HIT_GROUND, SoundCategory.PLAYERS, 0.7f, 0.9f);
    }

    /** 你的引爆類技能（detonator 標籤，炎爆矛自己除外）打在插著的炎爆矛附近：提早引爆。 */
    int detonateNear(final Player player, final SkillDefinition definition, final Location at) {
        if (at == null || definition == null || !definition.tags().contains(tw.exilecore.stats.TreeMechanicKeys.DETONATOR)
                || "explosive_spear".equals(service.config().signature(definition.id()))) {
            return 0;
        }
        final Deque<Lodged> mine = lodged.get(player.getUniqueId());
        if (mine == null || mine.isEmpty()) {
            return 0;
        }
        final List<Lodged> boom = new ArrayList<>();
        for (final Iterator<Lodged> it = mine.iterator(); it.hasNext(); ) {
            final Lodged spear = it.next();
            if (spear.at().getWorld() == at.getWorld() && spear.at().distance(at) <= DETONATE_RANGE) {
                it.remove();
                boom.add(spear);
            }
        }
        boom.forEach(spear -> explode(player, spear));
        return boom.size(); // 2026-10-28：引爆了幾支（雷躍的品質）
    }

    /** 每 tick（SkillService，每個玩家）：插著的炎爆矛時間到炸開；血嗅印的目標被重暈、還在流血。 */
    void tick(final Player player, final long now) {
        final Deque<Lodged> mine = lodged.get(player.getUniqueId());
        if (mine != null) {
            final List<Lodged> boom = new ArrayList<>();
            for (final Iterator<Lodged> it = mine.iterator(); it.hasNext(); ) {
                final Lodged spear = it.next();
                if (spear.at().getWorld() != player.getWorld()) {
                    it.remove();
                } else if (now >= spear.until()) {
                    it.remove();
                    boom.add(spear);
                } else if (now % 10 == 0) {
                    Fx.as(player.getUniqueId(), () -> spear.at().getWorld().spawnParticle(Particle.FLAME, spear.at().clone().add(0, 0.3, 0),
                            3, 0.1, 0.2, 0.1, 0.01));
                }
            }
            boom.forEach(spear -> explode(player, spear));
            if (mine.isEmpty()) {
                lodged.remove(player.getUniqueId());
            }
        }
        tickGrounds(player, now);
        if (!hounds.isEmpty() && now % 2 == 0) {
            tickHounds(player, now);
        }
    }

    private void explode(final Player player, final Lodged spear) {
        if (spear != null && service.eligible(player)) {
            blast(player, EXPLOSIVE_BLAST, spear.build(), spear.at());
        }
    }

    /** 在 at 放一次隱藏的爆炸（傷害照原技能那一下（含輔助、武器）× 自己的傷害表比例；自己的轉換、修飾器另外加上）。 */
    private void blast(final Player player, final String subId, final SkillBuild parent, final Location at) {
        final SkillBuild sub = subBuild(player, subId, parent);
        if (sub != null) {
            service.procCast(Origin.at(player, at.clone().add(0, 0.1, 0), SkillTargets.facing(player)), sub, null);
        }
    }

    /**
     * 2026-10-27 照 PoE2 Explosive Spear 的 Frenzy-Charged 爆炸：照矛飛的方向（水平）擺一個十字，每一臂從中心伸出
     * explosive_spear_infused 的 nova.radius（4 → 5.9 → 7.9 格）、整條寬 1.5 格，兩者都吃範圍加成；
     * 中心留下 2 格、8 秒的燃燒地面。這次之前是面積一樣的圓＋一定點燃＋點燃效果更多 100%。
     */
    private void crossBlast(final Player player, final SkillBuild parent, final Location at, final Vector direction) {
        final SkillBuild sub = subBuild(player, EXPLOSIVE_INFUSED, parent);
        final tw.exilecore.combat.SkillDamage damage = plugin.combat() == null ? null : plugin.combat().skillDamage();
        if (sub == null || at.getWorld() == null) {
            return;
        }
        final Vector forward = direction == null ? SkillTargets.facing(player) : direction.clone().setY(0);
        final Vector unit = forward.lengthSquared() < 1e-9 ? new Vector(1, 0, 0) : forward.normalize();
        final double length = sub.scaledRadius(sub.definition().params().radius());
        final double halfWidth = CROSS_WIDTH / 2 * sub.areaScale();
        if (damage != null) {
            for (final LivingEntity victim : service.targets().around(at, length + 1, player)) {
                final Location where = victim.getLocation();
                final double slack = victim.getBoundingBox().getWidthX() / 2;
                if (inCross(where.getX() - at.getX(), where.getZ() - at.getZ(), unit.getX(), unit.getZ(), length + slack, halfWidth + slack)) {
                    damage.hit(player, victim, sub.profile(), null);
                }
            }
        }
        final Vector side = new Vector(-unit.getZ(), 0, unit.getX());
        Fx.as(player.getUniqueId(), () -> {
            for (double d = -length; d <= length; d += 0.5) {
                at.getWorld().spawnParticle(Particle.FLAME, at.clone().add(unit.clone().multiply(d)).add(0, 0.3, 0), 2, 0.1, 0.1, 0.1, 0.02);
                at.getWorld().spawnParticle(Particle.FLAME, at.clone().add(side.clone().multiply(d)).add(0, 0.3, 0), 2, 0.1, 0.1, 0.1, 0.02);
            }
            Fx.spawn(at.getWorld(), Particle.EXPLOSION, at.clone().add(0, 0.5, 0), 1); // 2026-10-31：走 Fx（貼地的爆炸）
        });
        at.getWorld().playSound(at, Sound.ENTITY_GENERIC_EXPLODE, SoundCategory.PLAYERS, 0.8f, 1.1f);
        // 燃燒地面：點燃視同受到這次爆炸的火焰傷害（平均、減免前）
        final tw.exilecore.stats.StatSheet sheet = plugin.players().sheet(player);
        final double fire = tw.exilecore.stats.DamageCalculator.expectedOutgoing(sheet, sub.profile()).get(tw.exilecore.stats.DamageType.FIRE);
        if (fire > 0) {
            grounds.computeIfAbsent(player.getUniqueId(), uuid -> new ArrayList<>()).add(new Ground(at.clone(), GROUND_RADIUS * sub.areaScale(),
                    plugin.getServer().getCurrentTick() + sub.durationTicks(GROUND_SECONDS), fire, sub.profile().effective(sheet), new HashMap<>()));
            // 2026-10-28 照 PoE2：點燃地面是元素地面（旋風矛、旋矛擊的旋風碰到變成火焰的）；這次之前沒登記
            ElementalGround.mark(at, GROUND_RADIUS * sub.areaScale(), tw.exilecore.stats.DamageType.FIRE,
                    plugin.getServer().getCurrentTick() + sub.durationTicks(GROUND_SECONDS));
        }
    }

    /** (dx, dz) 在不在十字裡：十字的一條沿著 (fx, fz)（單位向量），另一條垂直；每一臂從中心伸出 length，半寬 halfWidth。 */
    static boolean inCross(final double dx, final double dz, final double fx, final double fz, final double length, final double halfWidth) {
        final double along = dx * fx + dz * fz;
        final double side = -dx * fz + dz * fx;
        return Math.abs(along) <= length && Math.abs(side) <= halfWidth
                || Math.abs(side) <= length && Math.abs(along) <= halfWidth;
    }

    /** 燃燒地面：站在上面的敵人每秒被施加一次易燃，疊到 50% 點燃（照 PoE2 Ignited Ground）。 */
    private void tickGrounds(final Player player, final long now) {
        final List<Ground> mine = grounds.get(player.getUniqueId());
        if (mine == null) {
            return;
        }
        mine.removeIf(ground -> ground.until() <= now || ground.at().getWorld() != player.getWorld());
        if (mine.isEmpty()) {
            grounds.remove(player.getUniqueId());
            return;
        }
        if (now % GROUND_INTERVAL != 0) {
            return;
        }
        for (final Ground ground : mine) {
            Fx.as(player.getUniqueId(), () -> ground.at().getWorld().spawnParticle(Particle.FLAME, ground.at().clone().add(0, 0.1, 0),
                    6, ground.radius() / 2, 0.02, ground.radius() / 2, 0.0));
            if (plugin.ailments() == null) {
                continue;
            }
            for (final LivingEntity target : service.targets().around(ground.at(), ground.radius(), player)) {
                if (target instanceof Player || ground.next().getOrDefault(target.getUniqueId(), 0L) > now) {
                    continue;
                }
                ground.next().put(target.getUniqueId(), now + GROUND_REIGNITE);
                // 2026-10-27 照 PoE2 Ignited Ground：每次施加易燃（照爆炸的火焰傷害算）、疊到 50% 才點燃；這次之前站上去就點燃
                plugin.ailments().groundIgnite(player, target, ground.fire(), ground.sheet());
            }
        }
    }

    private SkillBuild subBuild(final Player player, final String subId, final SkillBuild parent) {
        final SkillDefinition sub = service.config().skill(subId);
        if (sub == null) {
            return null;
        }
        final PlayerState state = plugin.players().get(player);
        final SkillBuild base = SkillBuild.of(service.config(), sub, parent.level(), state.level(), false, List.of(), state.weaponTags(),
                plugin.players().sheet(player));
        final double own = service.config().parentShare(subId, parent.definition(), parent.level());
        final double share = own > 0 ? own : 1.0;
        final List<Modifier> extra = new ArrayList<>();
        for (final Modifier modifier : base.profile().local()) {
            if (!INFUSED.equals(modifier.stat())) {
                extra.add(modifier); // 隱藏技能自己的修飾器（全部轉火焰、不會流血…）
            }
        }
        return base.withProfile(parent.profile().scaled(share, sub.name()).derive(sub.id(), sub.tags(), Set.of()).withLocal(extra))
                .withAreaAndProjectiles(base.areaScale() * Math.max(0.5, parent.areaScale()), base.projectiles());
    }

    private static double local(final tw.exilecore.stats.SkillProfile profile, final String stat) {
        double total = 0;
        for (final Modifier modifier : profile.local()) {
            if (stat.equals(modifier.stat())) {
                total += modifier.value();
            }
        }
        return total;
    }

    // ---- 血嗅印 ----

    /** 放了印記（SkillExecutor.mark）：記下是誰標的；同一個人原本標的那隻不再算（印記同時只有一個）。 */
    void marked(final Player player, final SkillBuild build, final LivingEntity target) {
        if (!BLOODHOUND.equals(build.definition().id())) {
            hounds.values().removeIf(hound -> hound.owner.equals(player.getUniqueId())); // 放了別的印記：血嗅印被換掉
            return;
        }
        hounds.values().removeIf(hound -> hound.owner.equals(player.getUniqueId()));
        final boolean stunned = plugin.ailments() != null && plugin.ailments().heavyStunned(target);
        final Hound hound = new Hound(player.getUniqueId(), target, build.level(), stunned);
        hound.toStun = BLOOD_TO_STUN + build.qualityFlat(tw.exilecore.stats.GemQualityKeys.BLOOD_TO_STUN) / 100.0; // 2026-10-28 照 PoE2：品質再 +(0—50)%
        hounds.put(target.getUniqueId(), hound);
    }

    /** 流血扣血（AilmentService，每一跳）：被標記的敵人身上標記者的失血 300% 算成重暈累積。 */
    public void bloodLoss(final LivingEntity target, final UUID source, final double amount) {
        final Hound hound = hounds.get(target.getUniqueId());
        if (hound == null || !hound.owner.equals(source) || amount <= 0 || plugin.ailments() == null) {
            return;
        }
        hound.fromBleed = Math.min(1, hound.fromBleed + plugin.ailments().addStunFromDamage(target, amount * hound.toStun));
    }

    private void tickHounds(final Player player, final long now) {
        final Debuffs debuffs = plugin.combat() == null ? null : plugin.combat().debuffs();
        final List<Hound> fire = new ArrayList<>();
        for (final Iterator<Hound> it = hounds.values().iterator(); it.hasNext(); ) {
            final Hound hound = it.next();
            if (!hound.owner.equals(player.getUniqueId())) {
                continue;
            }
            if (debuffs == null || !hound.target.isValid()) {
                it.remove();
                continue;
            }
            if (hound.target.isDead()) {
                continue; // 死掉的留給死亡事件
            }
            final Debuffs.Debuff mark = mark(debuffs, hound, now);
            if (mark == null) {
                it.remove();
                continue;
            }
            final boolean stunned = plugin.ailments() != null && plugin.ailments().heavyStunned(hound.target);
            if (stunned && !hound.wasStunned) {
                it.remove();
                fire.add(hound);
                continue;
            }
            hound.wasStunned = stunned;
            // 照 PoE2：牠在流血時印記不會結束
            if (plugin.ailments() != null && plugin.ailments().has(hound.target, AilmentType.BLEED) && mark.untilTick() < now + BLEED_HOLD) {
                debuffs.apply(hound.target.getUniqueId(), new Debuffs.Debuff(mark.id(), mark.source(), now + BLEED_HOLD, mark.taken(),
                        mark.selfOnly(), mark.tag(), mark.mods(), mark.slow()));
            }
        }
        for (final Hound hound : fire) {
            activate(player, hound, hound.target.getLocation(), debuffs);
        }
    }

    private static Debuffs.Debuff mark(final Debuffs debuffs, final Hound hound, final long now) {
        for (final Debuffs.Debuff debuff : debuffs.active(hound.target.getUniqueId(), now)) {
            if (BLOODHOUND.equals(debuff.id()) && hound.owner.equals(debuff.source())) {
                return debuff;
            }
        }
        return null;
    }

    /** 死掉時失血累積到暈眩門檻 5% 以上：觸發血爆。 */
    @EventHandler(priority = EventPriority.MONITOR)
    public void onDeath(final EntityDeathEvent event) {
        final Hound hound = hounds.remove(event.getEntity().getUniqueId());
        if (hound == null || hound.fromBleed < DEATH_THRESHOLD) {
            return;
        }
        final Player owner = plugin.getServer().getPlayer(hound.owner);
        if (owner != null && owner.getWorld().equals(event.getEntity().getWorld())) {
            activate(owner, hound, event.getEntity().getLocation(), plugin.combat() == null ? null : plugin.combat().debuffs());
        }
    }

    /** 印記用掉、觸發血爆：照失血累積的比例最多更多 100% 傷害與 100% 範圍。 */
    private void activate(final Player player, final Hound hound, final Location at, final Debuffs debuffs) {
        if (debuffs != null) {
            debuffs.remove(hound.target.getUniqueId(), BLOODHOUND, hound.owner, plugin.getServer().getCurrentTick());
        }
        final SkillDefinition sub = service.config().skill(BLOODHOUND_BLAST);
        if (sub == null || !service.eligible(player)) {
            return;
        }
        final PlayerState state = plugin.players().get(player);
        final int position = state.skills().find(BLOODHOUND);
        final SkillLoadout.Entry entry = position >= 0 ? state.skills().skill(position) : null;
        final SkillBuild base = SkillBuild.of(service.config(), sub, hound.level, state.level(), false,
                entry == null ? List.of() : service.supportsOf(entry), state.weaponTags(), plugin.players().sheet(player));
        final double share = Math.max(0, Math.min(1, hound.fromBleed));
        final SkillBuild build = base.withProfile(base.profile().withLocal(List.of(Modifier.more(StatKeys.DAMAGE, share, "失血累積"))))
                .withAreaAndProjectiles(base.areaScale() * Math.sqrt(1 + share), base.projectiles());
        service.procCast(Origin.at(player, at.clone().add(0, 0.1, 0), SkillTargets.facing(player)), build, null);
    }

    /** 登出、換角色：清掉插著的炎爆矛與血嗅印。 */
    void forget(final UUID uuid) {
        lodged.remove(uuid);
        grounds.remove(uuid);
        hounds.values().removeIf(hound -> hound.owner.equals(uuid));
    }

    /** 測試用：插著幾支炎爆矛。 */
    int lodgedCount(final UUID uuid) {
        final Deque<Lodged> mine = lodged.get(uuid);
        return mine == null ? 0 : mine.size();
    }
}
