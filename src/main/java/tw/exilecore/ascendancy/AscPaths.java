package tw.exilecore.ascendancy;

import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.NamespacedKey;
import org.bukkit.Particle;
import org.bukkit.Sound;
import org.bukkit.entity.Display;
import org.bukkit.entity.Entity;
import org.bukkit.entity.ItemDisplay;
import org.bukkit.entity.LivingEntity;
import org.bukkit.entity.Mob;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.inventory.ItemStack;
import org.bukkit.persistence.PersistentDataType;
import org.bukkit.util.Vector;
import tw.exilecore.ExileCore;
import tw.exilecore.ailment.AilmentType;
import tw.exilecore.character.PlayerState;
import tw.exilecore.combat.Debuffs;
import tw.exilecore.monster.MonsterData;
import tw.exilecore.skill.Mechanic;
import tw.exilecore.skill.SkillBuild;
import tw.exilecore.skill.SkillDefinition;
import tw.exilecore.stats.DamagePacket;
import tw.exilecore.stats.DamageType;
import tw.exilecore.stats.HitEvent;
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
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/**
 * 2026-10-27 照 PoE2 0.5：補上的三個昇華的機制（屬性鍵在 {@link AscKeys}；技能在 skills.yml 的 BEGIN/END ascendancy3）。
 * <ul>
 *   <li>靈狩者（Spirit Walker）：靈現之靈與靈現奔竄、熊靈（荒野守護：躍擊、重擊偷取、怒吼恫嚇；荒野擁抱）、原始梟羽與原始賞金
 *       （強化下一個投射物技能、翱翔之地）、夥伴在身邊的傷害、凱莎的平衡、自然秩序的林靈附身。</li>
 *   <li>鳴鐘武師（Martial Artist）：空無之鐘（空無專注）、空無共鳴、空無型態的幻影、山巒之教、武術精通的護盾回充、武術宗師的連擊。
 *       符文子午線、石拳之道在 {@link AscBuild#equipment}。</li>
 *   <li>淵井骸主（Abyssal Lich）：割裂血肉、幽影之井（骷髏換成幽影之魂）、深淵幻影、不情願的獻祭（祭品，Warcries 呼叫）。</li>
 * </ul>
 * 狀態只在記憶體（下線、換角色就清掉）。一格當一格。由 {@link AscendancyEffects} 在固定的地方呼叫。
 */
final class AscPaths implements Listener {

    private final AscendancyEffects effects;
    private final ExileCore plugin;
    private final NamespacedKey minionSkillKey;

    // ---- 原作的數值 ----
    /** 每移動幾格一個靈現之靈。 */
    static final double WISP_METRES = 10;
    /** 靈鹿躍起幾次、每次幾格、靈鹿之間的角度、追敵的錐角（一半）、追敵距離。 */
    static final int STAG_LEAPS = 4;
    static final double STAG_LEAP = 3;
    static final double STAG_SPREAD = 15;
    static final double STAG_HOME_HALF_ANGLE = 60;
    static final double STAG_HOME_RANGE = 5;
    /** 靈現奔竄的冷卻（0.3 秒）。 */
    static final long STAMPEDE_COOLDOWN = 6;
    /** 梟羽每幾 tick 一根（4 秒）、強化等幾 tick（8 秒）、翱翔之地的長、寬。 */
    static final long FEATHER_TICKS = 80;
    static final long EMPOWER_TICKS = 160;
    static final double TRAIL_LENGTH = 12;
    static final double TRAIL_HALF_WIDTH = 1.2;
    /** 熊靈：躍擊（8 秒、3 格）、重擊（5 秒、2.5 格、偷取 50%）、怒吼（12 秒、6 格、恫嚇 8 秒）、荒野擁抱（存在範圍 6 格、每秒 2%、先承受 8%）。 */
    static final long BEAR_LEAP_TICKS = 160;
    static final double BEAR_LEAP_RADIUS = 3;
    static final long BEAR_SLAM_TICKS = 100;
    static final double BEAR_SLAM_RADIUS = 2.5;
    static final double BEAR_SLAM_LEECH = 0.5;
    static final long BEAR_ROAR_TICKS = 240;
    static final double BEAR_ROAR_RADIUS = 6;
    static final long INTIMIDATE_TICKS = 160;
    static final double EMBRACE_RADIUS = 6;
    static final double EMBRACE_REGEN = 2;
    static final double EMBRACE_SHARE = 0.08;
    /** 空無之鐘：最多 3 口、21 秒、出現在 3 格內；打中鐘的範圍。空無共鳴：最多存 3 次、每 0.5 秒回一次。 */
    static final int BELL_MAX = 3;
    static final long BELL_TICKS = 420;
    static final double BELL_SPAWN = 3;
    static final double BELL_HIT_RADIUS = 2.5;
    static final int RESONANCE_USES = 3;
    static final long RESONANCE_TICKS = 10;
    /** 空無型態：幻影的傷害更少 15%、花 80% 的消耗、用掉銳星多 2 個、出現在準心 6 格內。 */
    static final double FORM_LESS = 0.15;
    static final double FORM_COST = 0.8;
    static final int FORM_EXTRA = 2;
    static final double FORM_RADIUS = 6;
    /** 山巒之教：8 秒沒有獲得就全部失去；攻擊更多 15%、不超過最大生命 30% 的擊中更少 40%、暈眩門檻更多 50%。 */
    static final long MOUNTAIN_TICKS = 160;
    static final double MOUNTAIN_ATTACK = 0.15;
    static final double MOUNTAIN_SMALL_HIT = 0.30;
    static final double MOUNTAIN_LESS = 0.40;
    static final double MOUNTAIN_STUN = 0.50;
    /** 幽影之魂：最後一次「本來要召喚」之後留多久（精魂召喚每次同步都會再叫一次，就會刷新）。 */
    static final long UMBRAL_TICKS = 60;
    /** 自然秩序：夥伴增加 30% 移動速度。 */
    static final double ORDER_SPEED = 0.30;

    /** 一個玩家的狀態。 */
    static final class Own {
        Location last;
        double moved;
        int wisps;
        long stampedeReady;
        int leap;
        long leapTick;
        int feathers;
        long featherNext;
        double empower;
        long empowerUntil;
        boolean onTrail;
        boolean trailFull;
        long bearLeapReady;
        long bearSlamReady;
        long bearRoarReady;
        long bearSlamUntil;
        UUID bear;
        UUID embraced;
        boolean companionNear;
        int possession = -1;
        long possessionNext;
        final List<Bell> bells = new ArrayList<>();
        long bellNext;
        /** 2026-10-28：空無專注的 +x 上限、鐘的持續時間（每秒重算一次，面板上的組合）。 */
        int bellBonus;
        long bellTicks = BELL_TICKS;
        long bellCheckedAt = Long.MIN_VALUE;
        int resonance = RESONANCE_USES;
        long resonanceNext;
        SkillBuild lastMelee;
        int mountain;
        long mountainGained;
        final Map<String, Umbral> umbral = new LinkedHashMap<>();
    }

    private record Bell(Location at, ItemDisplay display, long until) {
    }

    /** 幽影之魂：哪一種骷髏、幾個（同一個 tick 本來要叫幾隻）、到哪個 tick。 */
    static final class Umbral {
        int count;
        long tick;
        long until;
    }

    /** 翱翔之地：誰的、起點、方向、到哪個 tick。 */
    private record Trail(UUID owner, Location from, Vector direction, long until) {
    }

    private final Map<UUID, Own> owns = new HashMap<>();
    private final List<Trail> trails = new ArrayList<>();
    /** 敵人上一次被看到時有沒有被釘身（山巒之道：剛被釘身才算禁錮）。 */
    private final Map<UUID, Boolean> pinned = new HashMap<>();

    AscPaths(final AscendancyEffects effects) {
        this.effects = effects;
        this.plugin = effects.plugin;
        this.minionSkillKey = new NamespacedKey(plugin, "minion_skill");
    }

    Own own(final Player player) {
        return owns.computeIfAbsent(player.getUniqueId(), id -> new Own());
    }

    private long now() {
        return effects.now();
    }

    private boolean on(final Player player, final String skillId) {
        return plugin.skills() != null && plugin.skills().auraOn(player, skillId);
    }

    private double level(final Player player, final String skillId, final String stat) {
        return plugin.skills() == null ? 0 : plugin.skills().levelValue(skillId, stat, plugin.skills().grantedLevel(player));
    }

    void forget(final UUID player) {
        final Own own = owns.remove(player);
        if (own != null) {
            for (final Bell bell : own.bells) {
                if (bell.display() != null && bell.display().isValid()) {
                    bell.display().remove();
                }
            }
        }
        trails.removeIf(trail -> trail.owner().equals(player));
    }

    /** 自己放的、會打人的技能（光環、召喚、戰吼、放下去的、隱藏的不算）。 */
    private static boolean damaging(final SkillBuild build) {
        final SkillDefinition definition = build.definition();
        return definition.mechanic() != Mechanic.AURA && definition.mechanic() != Mechanic.SUMMON && definition.mechanic() != Mechanic.WARCRY
                && build.deploy() == null && !definition.hidden();
    }

    // =====================================================================
    // 放技能（AscendancyEffects.cast）
    // =====================================================================

    /** 玩家自己放了一個技能：回傳這一下要用的 build（原始賞金的強化、山巒之教），順便觸發靈現奔竄、敲鐘、空無型態、深淵幻影。 */
    SkillBuild used(final Player player, final PlayerState state, final SkillBuild original, final LivingEntity target) {
        SkillBuild build = original;
        final SkillDefinition definition = build.definition();
        final Set<String> tags = build.profile().tags();
        final StatSheet sheet = state.sheet();
        final Own own = own(player);
        final long now = now();
        final boolean damaging = damaging(build);
        final boolean attack = damaging && tags.contains(Tags.ATTACK);
        final List<Modifier> local = new ArrayList<>();
        if (attack && tags.contains(Tags.MELEE) && !definition.tags().contains("channelling") && !"hollow_form".equals(definition.id())) {
            own.lastMelee = original; // 空無型態的幻影用最近使用的近戰攻擊
        }
        // 靈現奔竄：攻擊時用掉全部的靈現之靈
        if (attack && own.wisps > 0 && now >= own.stampedeReady && on(player, "vivid_stampede")) {
            final int count = own.wisps;
            own.wisps = 0;
            own.stampedeReady = now + STAMPEDE_COOLDOWN;
            stampede(player, sheet, count, target);
        }
        // 原始賞金：強化接下來的第一個投射物技能（多 2 個投射物、投射物速度增加；每多用 1 根梟羽更多 100%）
        if (damaging && tags.contains(Tags.PROJECTILE) && own.empowerUntil > now) {
            final double effect = Math.max(1, own.empower);
            own.empowerUntil = 0;
            final double speed = level(player, "primal_bounty", "asc.bounty_speed") / 100.0 * effect;
            build = faster(build.withAreaAndProjectiles(build.areaScale(), build.projectiles() + (int) Math.round(2 * effect)), 1 + speed);
            local.add(Modifier.increased(StatKeys.SKILL_PROJECTILE_SPEED, speed, "原始賞金"));
            final double trail = AscendancyEffects.flat(sheet, AscKeys.SACRED_UNITY);
            if (trail > 0) {
                final Vector direction = player.getLocation().getDirection().setY(0);
                if (direction.lengthSquared() > 1e-6) {
                    trails.add(new Trail(player.getUniqueId(), player.getLocation().clone(), direction.normalize(), now + Math.round(trail * 20)));
                }
            }
            tw.exilecore.skill.Fx.spawn(player.getWorld(), Particle.CLOUD, player.getLocation().add(0, 1.2, 0), 12, 0.4, 0.4, 0.4, 0.02);
        }
        // 山巒之教：攻擊更多 15%，用掉 1 層
        if (attack && own.mountain > 0) {
            local.add(Modifier.more(StatKeys.DAMAGE, MOUNTAIN_ATTACK, "山巒之教"));
            own.mountain--;
            if (own.mountain == 0) {
                plugin.players().markDirty(player);
            }
        }
        // 空無之鐘：你的攻擊打到鐘就敲碎
        if (attack && !own.bells.isEmpty()) {
            strikeBells(player, own, tags.contains(Tags.MELEE) ? 3.5 : 15);
        }
        if ("hollow_form".equals(definition.id())) {
            hollowForm(player, state, own, target);
        }
        // 深淵幻影：造成傷害的法術用掉 1 顆銳星，幻影也施放同一個法術（傷害更少）
        if (damaging && tags.contains(Tags.SPELL) && build.cooldownTicks() <= 0 && !definition.tags().contains("channelling")
                && !tags.contains("triggered") && definition.source() == null && on(player, "abyssal_apparition")
                && plugin.combat() != null && plugin.combat().charges() != null
                && plugin.combat().charges().spend(player, "power", null, tags) > 0) {
            apparition(player, build, target);
        }
        return local.isEmpty() ? build : build.withProfile(build.profile().withLocal(local));
    }

    /** 同一次施放的投射物速度乘 scale（原始賞金）。 */
    static SkillBuild faster(final SkillBuild b, final double scale) {
        return new SkillBuild(b.definition(), b.level(), b.gemLevel(), b.isDefault(), b.supports(), b.profile(),
                b.reach(), b.angle(), b.targets(), b.knockback(), b.projectiles(), b.spread(), b.speed() * scale, b.range(), b.size(), b.pierce(),
                b.chains(), b.explode(), b.wobble(), b.arrow(), b.radius(), b.length(), b.chainRange(), b.manaCost(), b.areaScale(), b.bonusLevel(),
                b.cooldownTicks(), b.durationScale(), b.forks(), b.returning(), b.echo(), b.deploy(), b.totems(), b.minions(), b.reserve(),
                b.minionReserve(), b.auraEffect(), b.generosity(), b.exerts(), b.repeats());
    }

    // ---- 靈現奔竄 ----

    private void stampede(final Player player, final StatSheet sheet, final int count, final LivingEntity target) {
        final Vector facing = player.getLocation().getDirection().setY(0);
        if (facing.lengthSquared() < 1e-6) {
            facing.setX(1);
        }
        facing.normalize();
        final boolean homing = AscendancyEffects.has(sheet, AscKeys.SACRED_UNITY);
        final boolean guidance = AscendancyEffects.has(sheet, AscKeys.STAG_GUIDANCE);
        player.getWorld().playSound(player.getLocation(), Sound.ENTITY_HORSE_JUMP, 0.8f, 1.4f);
        for (int i = 0; i < count; i++) {
            final double angle = Math.toRadians((i - (count - 1) / 2.0) * STAG_SPREAD);
            final Vector dir = facing.clone().rotateAroundY(angle);
            leap(player, player.getLocation().clone(), dir, 1, homing);
        }
        if (guidance) {
            effects.later(STAG_LEAPS * 6L + 2, () -> {
                final Own own = own(player);
                final int max = (int) AscendancyEffects.flat(effects.sheet(player), AscKeys.VIVID_WISPS);
                own.wisps = Math.min(Math.max(1, max), own.wisps + 1); // 莫里根的指引：奔竄結束時獲得 1 個靈現之靈
            });
        }
    }

    /** 靈鹿的第 n 次躍起：落地的地方放震波，第 4 次留下感電地面。 */
    private void leap(final Player player, final Location from, final Vector dir, final int n, final boolean homing) {
        effects.later(6, () -> {
            if (!player.isOnline() || from.getWorld() == null || from.getWorld() != player.getWorld()) {
                return;
            }
            Vector step = dir.clone().multiply(STAG_LEAP);
            Vector next = dir;
            if (homing) {
                // 聖潔一統：靈鹿會躍向前方 120° 內的敵人
                LivingEntity best = null;
                double bestDistance = Double.MAX_VALUE;
                for (final LivingEntity enemy : effects.enemiesNear(player, from, STAG_HOME_RANGE)) {
                    final Vector to = enemy.getLocation().toVector().subtract(from.toVector()).setY(0);
                    final double distance = to.length();
                    if (distance < 0.5 || distance > bestDistance || Math.toDegrees(to.angle(dir)) > STAG_HOME_HALF_ANGLE) {
                        continue;
                    }
                    best = enemy;
                    bestDistance = distance;
                }
                if (best != null) {
                    step = best.getLocation().toVector().subtract(from.toVector()).setY(0);
                    next = step.clone().normalize();
                }
            }
            final Location land = from.clone().add(step);
            land.setY(from.getY());
            final Location mid = from.clone().add(step.clone().multiply(0.5)).add(0, 1.2, 0);
            tw.exilecore.skill.Fx.spawn(from.getWorld(), Particle.ELECTRIC_SPARK, mid, 10, 0.3, 0.3, 0.3, 0.05);
            tw.exilecore.skill.Fx.spawn(from.getWorld(), Particle.END_ROD, land.clone().add(0, 0.3, 0), 6, 0.4, 0.1, 0.4, 0.02);
            if (plugin.skills() != null) {
                final Own own = own(player);
                own.leap = n;
                own.leapTick = now();
                plugin.skills().triggerSkill(player, "asc_vivid_stag", land);
                if (n >= STAG_LEAPS) {
                    plugin.skills().triggerSkill(player, "asc_stag_ground", land); // 跳完全程：感電地面
                }
            }
            if (n < STAG_LEAPS) {
                leap(player, land, next, n + 1, homing);
            }
        });
    }

    // ---- 空無之鐘 ----

    private void strikeBells(final Player player, final Own own, final double reach) {
        final Location eye = player.getLocation().add(0, 1.0, 0);
        final Vector facing = player.getLocation().getDirection().setY(0);
        if (facing.lengthSquared() < 1e-6) {
            return;
        }
        facing.normalize();
        final Iterator<Bell> iterator = own.bells.iterator();
        final List<Bell> broken = new ArrayList<>();
        while (iterator.hasNext()) {
            final Bell bell = iterator.next();
            if (bell.at().getWorld() != eye.getWorld()) {
                continue;
            }
            final Vector to = bell.at().toVector().subtract(eye.toVector()).setY(0);
            final double along = to.dot(facing);
            final double side = to.clone().subtract(facing.clone().multiply(along)).length();
            if (along >= -0.5 && along <= reach && side <= (reach <= 4 ? 2.0 : 1.5)) {
                iterator.remove();
                broken.add(bell);
            }
        }
        for (final Bell bell : broken) {
            ring(player, bell);
        }
    }

    /** 敲碎一口鐘：震波（粉碎打擊、不累積暈眩）；敲鐘一定是暴擊，空無共鳴跟著響。 */
    private void ring(final Player player, final Bell bell) {
        if (bell.display() != null && bell.display().isValid()) {
            bell.display().remove();
        }
        final Location at = bell.at().clone();
        at.getWorld().playSound(at, Sound.BLOCK_BELL_USE, 1.0f, 1.3f);
        tw.exilecore.skill.Fx.spawn(at.getWorld(), Particle.SONIC_BOOM, at.clone().add(0, 0.5, 0), 1, 0, 0, 0, 0);
        if (plugin.skills() != null) {
            effects.later(1, () -> plugin.skills().triggerSkill(player, "asc_hollow_bell", at.clone().add(0, -1.0, 0)));
        }
        resonate(player);
    }

    /** 空無共鳴：其他技能暴擊時背上的鐘響（最多存 3 次）。 */
    private void resonate(final Player player) {
        final Own own = own(player);
        if (own.resonance <= 0 || !on(player, "hollow_resonance") || plugin.skills() == null) {
            return;
        }
        own.resonance--;
        if (own.resonanceNext <= now()) {
            own.resonanceNext = now() + cooldownTicks(player, resonanceTicks(player));
        }
        player.getWorld().playSound(player.getLocation(), Sound.BLOCK_BELL_RESONATE, 0.6f, 1.6f);
        effects.later(1, () -> plugin.skills().triggerSkill(player, "asc_hollow_ring", player.getLocation()));
    }

    /** 空無共鳴回一次要幾 tick（技能的冷卻 0.5 秒）。 */
    private long resonanceTicks(final Player player) {
        final double seconds = level(player, "hollow_resonance", "asc.resonance_cooldown");
        return seconds > 0 ? Math.round(seconds * 20) : RESONANCE_TICKS;
    }

    /** 冷卻回復速度套用在 ticks 上。 */
    private long cooldownTicks(final Player player, final long ticks) {
        final StatSheet sheet = effects.sheet(player);
        final double recovery = sheet == null ? 0 : sheet.increased(StatKeys.COOLDOWN_RECOVERY, Set.of());
        return Math.max(1, Math.round(ticks / Math.max(0.1, 1 + recovery)));
    }

    // ---- 空無型態 ----

    private void hollowForm(final Player player, final PlayerState state, final Own own, final LivingEntity target) {
        final SkillBuild melee = own.lastMelee;
        if (melee == null || plugin.skills() == null) {
            state.notice("<gray>空無型態：先用一次近戰攻擊（幻影會用你最近使用的近戰攻擊）", now() + 40);
            return;
        }
        int images = 1;
        if (plugin.combat() != null && plugin.combat().charges() != null
                && plugin.combat().charges().spend(player, "power", null, melee.profile().tags()) > 0) {
            images += FORM_EXTRA;
        }
        final Location aim = target != null && target.getWorld() == player.getWorld()
                && target.getLocation().distanceSquared(player.getLocation()) <= FORM_RADIUS * FORM_RADIUS * 4
                ? target.getLocation() : player.getLocation().add(player.getLocation().getDirection().setY(0).normalize().multiply(3));
        final SkillBuild weaker = melee.withProfile(melee.profile().withLocal(List.of(Modifier.more(StatKeys.DAMAGE, -FORM_LESS, "空無型態"))));
        for (int i = 0; i < images; i++) {
            final double cost = melee.manaCost() * FORM_COST;
            if (cost > 0 && state.mana() < cost) {
                break;
            }
            if (cost > 0) {
                state.setMana(state.mana() - cost);
            }
            final double angle = plugin.random().nextDouble() * Math.PI * 2;
            final double radius = 0.8 + plugin.random().nextDouble() * 1.4;
            final Location spot = aim.clone().add(Math.cos(angle) * radius, 0, Math.sin(angle) * radius);
            if (spot.distanceSquared(player.getLocation()) > FORM_RADIUS * FORM_RADIUS) {
                final Vector back = spot.toVector().subtract(player.getLocation().toVector()).setY(0);
                spot.setX(player.getLocation().getX() + back.getX() * FORM_RADIUS / Math.max(1e-6, back.length()));
                spot.setZ(player.getLocation().getZ() + back.getZ() * FORM_RADIUS / Math.max(1e-6, back.length()));
            }
            effects.later(1 + 4L * i, () -> {
                if (!player.isOnline() || spot.getWorld() != player.getWorld()) {
                    return;
                }
                LivingEntity nearest = null;
                double best = Double.MAX_VALUE;
                for (final LivingEntity enemy : effects.enemiesNear(player, spot, FORM_RADIUS)) {
                    final double d = enemy.getLocation().distanceSquared(spot);
                    if (d < best) {
                        best = d;
                        nearest = enemy;
                    }
                }
                final Vector facing = nearest != null ? nearest.getLocation().toVector().subtract(spot.toVector()).setY(0)
                        : player.getLocation().getDirection().setY(0);
                if (facing.lengthSquared() < 1e-6) {
                    facing.setX(1);
                }
                final Location from = spot.clone();
                from.setDirection(facing);
                tw.exilecore.skill.Fx.spawn(from.getWorld(), Particle.REVERSE_PORTAL, from.clone().add(0, 1, 0), 30, 0.25, 0.7, 0.25, 0.02);
                tw.exilecore.skill.Fx.spawn(from.getWorld(), Particle.SOUL, from.clone().add(0, 1.6, 0), 4, 0.15, 0.1, 0.15, 0.01);
                plugin.skills().recast(player, weaker, from, facing.normalize(), nearest);
            });
        }
        plugin.players().display(player, state);
    }

    // ---- 深淵幻影 ----

    private void apparition(final Player player, final SkillBuild build, final LivingEntity target) {
        final double less = Math.min(100, Math.max(0, level(player, "abyssal_apparition", "asc.apparition_less")));
        final SkillBuild weaker = build.withProfile(build.profile().withLocal(List.of(Modifier.more(StatKeys.DAMAGE, -less / 100.0, "深淵幻影"))));
        final Vector facing = player.getLocation().getDirection();
        final Vector side = new Vector(-facing.getZ(), 0, facing.getX());
        if (side.lengthSquared() > 1e-6) {
            side.normalize().multiply(plugin.random().nextBoolean() ? 1.5 : -1.5);
        }
        final Location at = player.getLocation().add(side).add(facing.clone().setY(0).multiply(-0.5));
        tw.exilecore.skill.Fx.spawn(at.getWorld(), Particle.SCULK_SOUL, at.clone().add(0, 1, 0), 16, 0.3, 0.7, 0.3, 0.02);
        effects.later(6, () -> {
            if (!player.isOnline() || at.getWorld() != player.getWorld()) {
                return;
            }
            final Vector dir = target != null && target.isValid() && target.getWorld() == at.getWorld()
                    ? target.getLocation().add(0, 1, 0).toVector().subtract(at.clone().add(0, 1.5, 0).toVector()) : player.getLocation().getDirection();
            if (dir.lengthSquared() < 1e-6) {
                dir.setX(1);
            }
            at.setDirection(dir);
            tw.exilecore.skill.Fx.spawn(at.getWorld(), Particle.SCULK_SOUL, at.clone().add(0, 1, 0), 10, 0.2, 0.5, 0.2, 0.02);
            plugin.skills().recast(player, weaker, at, dir.normalize(), target);
        });
    }

    // =====================================================================
    // 擊中（AscendancyEffects.hitByPlayer：玩家打中怪物）
    // =====================================================================

    void hit(final Player player, final HitEvent event, final MonsterData data) {
        final LivingEntity target = event.defender();
        final Own own = own(player);
        final SkillProfile skill = event.skill();
        final String id = skill == null ? "" : skill.id();
        // 空無之鐘：打中鐘附近的敵人也算打到鐘
        if (!own.bells.isEmpty() && skill != null && skill.isAttack() && !id.startsWith("asc_hollow")) {
            final List<Bell> broken = new ArrayList<>();
            own.bells.removeIf(bell -> {
                if (bell.at().getWorld() == target.getWorld() && bell.at().distanceSquared(target.getLocation()) <= BELL_HIT_RADIUS * BELL_HIT_RADIUS) {
                    broken.add(bell);
                    return true;
                }
                return false;
            });
            for (final Bell bell : broken) {
                ring(player, bell);
            }
        }
        // 空無共鳴：其他技能暴擊時鐘響
        if (event.result().crit() && !id.startsWith("asc_hollow")) {
            resonate(player);
        }
        // 山巒之道：剛被釘身的敵人算被禁錮（凍結、電擊在 ailment）
        if (AscendancyEffects.has(effects.sheet(player), AscKeys.MOUNTAIN) && plugin.ailments() != null) {
            final boolean now = plugin.ailments().pinned(target);
            final Boolean before = pinned.put(target.getUniqueId(), now);
            if (now && (before == null || !before)) {
                immobilised(player, target);
            }
        }
    }

    /** 造成異常時（AscendancyEffects.ailment）：凍結、電擊算禁錮。 */
    void ailment(final Player player, final LivingEntity target, final AilmentType type) {
        if (type == AilmentType.FREEZE || type == AilmentType.ELECTROCUTE) {
            immobilised(player, target);
        }
    }

    /** 山巒之道：禁錮敵人時，照牠的強度每點 100% 超越機率獲得 1 層山巒之教。 */
    private void immobilised(final Player player, final LivingEntity target) {
        final double max = AscendancyEffects.flat(effects.sheet(player), AscKeys.MOUNTAIN);
        if (max <= 0) {
            return;
        }
        // 2026-10-28 照 PoE2（磐石之拳的無與倫比）：每點強度再多 x% 超越機率
        final double chance = effects.power(target) * (100 + Math.max(0, effects.sheet(player).flat(
                tw.exilecore.item.unique.UniqueStats.MOUNTAIN_EXTRA_CHANCE, java.util.Set.of())));
        if (chance <= 0) {
            return;
        }
        final int gained = (int) (chance / 100) + (plugin.random().nextDouble() * 100 < chance % 100 ? 1 : 0);
        final Own own = own(player);
        final boolean had = own.mountain > 0;
        own.mountain = (int) Math.min(max, own.mountain + gained);
        own.mountainGained = now();
        if (!had && own.mountain > 0) {
            plugin.players().markDirty(player);
            player.getWorld().playSound(player.getLocation(), Sound.BLOCK_DEEPSLATE_PLACE, 0.8f, 0.8f);
        }
        plugin.players().get(player).notice("<#c9b48a>山巒之教 " + own.mountain + "／" + (int) max, now() + 30);
    }

    /** 玩家打出去的條件加成（AscendancyEffects.conditional）：莫里根的指引（靈鹿每躍起一次更多傷害、感電幅度）。 */
    List<Modifier> conditional(final Player attacker, final StatSheet sheet, final SkillProfile skill) {
        if (skill == null || !"asc_vivid_stag".equals(skill.id())) {
            return List.of();
        }
        final Own own = owns.get(attacker.getUniqueId());
        final double guidance = AscendancyEffects.flat(sheet, AscKeys.STAG_GUIDANCE);
        if (own == null || guidance <= 0 || own.leap <= 1 || now() - own.leapTick > 2) {
            return List.of();
        }
        final double more = guidance / 100.0 * (own.leap - 1);
        return List.of(Modifier.more(StatKeys.DAMAGE, more, "莫里根的指引"), Modifier.more(StatKeys.SHOCK_EFFECT, more, "莫里根的指引"));
    }

    // =====================================================================
    // 熊靈打中的（重擊偷取）
    // =====================================================================

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onHit(final HitEvent event) {
        if (!event.result().hit() || event.attacker() == null || event.attacker() instanceof Player || plugin.skills() == null
                || plugin.skills().minions() == null) {
            return;
        }
        final LivingEntity attacker = event.attacker();
        final Player owner = plugin.skills().minions().owner(attacker);
        if (owner == null) {
            return;
        }
        final Own own = owns.get(owner.getUniqueId());
        if (own != null && attacker.getUniqueId().equals(own.bear) && own.bearSlamUntil >= now()) {
            plugin.skills().minions().heal(attacker, event.result().total() * BEAR_SLAM_LEECH);
        }
    }

    // =====================================================================
    // 翻滾（原始賞金）
    // =====================================================================

    void dodge(final Player player) {
        final Own own = owns.get(player.getUniqueId());
        if (own == null || own.feathers <= 0 || !on(player, "primal_bounty")) {
            return;
        }
        final StatSheet sheet = effects.sheet(player);
        final int spend = (int) Math.max(1, Math.min(own.feathers, AscendancyEffects.flat(sheet, AscKeys.OWL_SPEND)));
        own.feathers -= spend;
        own.empower = spend; // 每多用 1 根，強化效果更多 100%
        own.empowerUntil = now() + EMPOWER_TICKS;
        player.getWorld().playSound(player.getLocation(), Sound.ENTITY_PARROT_FLY, 0.9f, 0.8f);
        tw.exilecore.skill.Fx.spawn(player.getWorld(), Particle.WHITE_ASH, player.getLocation().add(0, 1, 0), 20, 0.5, 0.5, 0.5, 0.01);
        plugin.players().get(player).notice("<#e8e1c8>原始賞金：下一個投射物技能被強化（用掉 " + spend + " 根梟羽）", now() + 40);
    }

    // =====================================================================
    // 受到擊中（AscendancyEffects.absorb）
    // =====================================================================

    /** 回傳 {護盾, 生命}：荒野擁抱（熊靈先承受 8%）、山巒之教（小擊中更少 40%、被擊中失去 1 層）。 */
    double[] absorb(final Player player, final PlayerState state, final double esIn, final double lifeIn) {
        double es = esIn;
        double life = lifeIn;
        final Own own = owns.get(player.getUniqueId());
        if (own == null) {
            return new double[] {es, life};
        }
        if (own.embraced != null && es + life > 0 && plugin.skills() != null && plugin.skills().minions() != null) {
            final Entity bear = plugin.getServer().getEntity(own.embraced);
            if (bear instanceof LivingEntity living && living.isValid() && !living.isDead() && living.getWorld() == player.getWorld()) {
                final double share = (es + life) * EMBRACE_SHARE;
                final double scale = plugin.skills().minions().healthScale(living);
                living.setHealth(Math.max(0, living.getHealth() - share * scale));
                final double keep = 1 - EMBRACE_SHARE;
                es *= keep;
                life *= keep;
            }
        }
        if (own.mountain > 0 && es + life > 0) {
            if (es + life <= state.maxLife() * MOUNTAIN_SMALL_HIT) {
                es *= 1 - MOUNTAIN_LESS;
                life *= 1 - MOUNTAIN_LESS;
            }
            own.mountain--; // 被擊中時失去 1 層
            if (own.mountain == 0) {
                plugin.players().markDirty(player);
            }
        }
        return new double[] {es, life};
    }

    // =====================================================================
    // 擊殺（割裂血肉）
    // =====================================================================

    /** 割裂血肉：被你（或存在範圍內友軍）擊殺的被詛咒敵人 33% 機率爆炸，造成牠最大生命四分之一的物理傷害。 */
    void death(final LivingEntity dead, final Player killer) {
        pinned.remove(dead.getUniqueId());
        if (plugin.combat() == null || !plugin.combat().debuffs().cursed(dead.getUniqueId(), now())) {
            return;
        }
        Player lich = null;
        for (final Player player : dead.getWorld().getPlayers()) {
            final StatSheet sheet = effects.sheet(player);
            if (!AscendancyEffects.has(sheet, AscKeys.RUPTURE_PHYSICAL)) {
                continue;
            }
            if (player.equals(killer) || killer != null && killer.getWorld() == player.getWorld()
                    && killer.getLocation().distanceSquared(player.getLocation()) <= Math.pow(AscendancyEffects.presence(sheet), 2)) {
                lich = player;
                break;
            }
        }
        if (lich == null || plugin.random().nextDouble() * 100 >= AscendancyEffects.flat(effects.sheet(lich), AscKeys.RUPTURE_PHYSICAL)) {
            return;
        }
        final org.bukkit.attribute.AttributeInstance max = dead.getAttribute(org.bukkit.attribute.Attribute.MAX_HEALTH);
        final double amount = max == null ? 0 : max.getValue() / 4;
        if (amount <= 0 || plugin.combat().skillDamage() == null) {
            return;
        }
        final SkillProfile profile = new SkillProfile("asc_rupture_flesh", Set.of(Tags.AREA, Tags.PHYSICAL), 0,
                new DamagePacket().set(DamageType.PHYSICAL, amount));
        final Location at = dead.getLocation();
        dead.getWorld().spawnParticle(Particle.BLOCK, at.clone().add(0, 1, 0), 30, 0.6, 0.6, 0.6, 0.05, Material.REDSTONE_BLOCK.createBlockData());
        dead.getWorld().playSound(at, Sound.ENTITY_SLIME_SQUISH, 0.8f, 0.6f);
        for (final LivingEntity target : effects.enemiesNear(lich, at, 2.5)) {
            plugin.combat().skillDamage().hit(lich, target, profile, null);
        }
    }

    // =====================================================================
    // 幽影之井（MinionService.summon 呼叫）
    // =====================================================================

    /** 骷髏的種類 → 每一個幽影之魂給的增益。 */
    static final Map<String, List<Modifier>> UMBRAL = Map.of(
            "summon_skeletons", List.of(new Modifier(StatKeys.DAMAGE, ModType.INCREASED, 0.15, Set.of(Tags.ATTACK), "幽影之魂")),
            "skeletal_archers", List.of(Modifier.increased(StatKeys.SKILL_PROJECTILE_SPEED, 0.15, "幽影之魂")),
            "skeletal_cleric", List.of(Modifier.increased(StatKeys.ES_RECHARGE, 0.30, "幽影之魂")),
            "skeletal_pyromancer", List.of(Modifier.increased(StatKeys.SKILL_AREA, 0.15, "幽影之魂")),
            "skeletal_stormcaller", List.of(new Modifier(StatKeys.DAMAGE, ModType.INCREASED, 0.25, Set.of(Tags.SPELL), "幽影之魂")),
            "skeletal_frostcaller", List.of(Modifier.increased(StatKeys.ES_MAX, 0.35, "幽影之魂")),
            "skeletal_brute", List.of(Modifier.increased(StatKeys.STUN_BUILDUP, 0.60, "幽影之魂")),
            "skeletal_reaver", List.of(Modifier.increased(StatKeys.ATTACK_SPEED, 0.06, "幽影之魂"), Modifier.increased(StatKeys.CAST_SPEED, 0.06, "幽影之魂")));

    /** 本來要召喚一隻骷髏：有幽影之井就改成幽影之魂（回傳 true = 不要叫出來）。 */
    boolean umbral(final Player player, final String skillId) {
        if (!UMBRAL.containsKey(skillId) || !AscendancyEffects.has(effects.sheet(player), AscKeys.UMBRAL_WELL)) {
            return false;
        }
        final Own own = own(player);
        final Umbral soul = own.umbral.computeIfAbsent(skillId, id -> new Umbral());
        final long now = now();
        final int before = soul.until > now ? soul.count : 0;
        if (soul.tick != now) {
            soul.tick = now;
            soul.count = 0;
        }
        soul.count++;
        soul.until = now + UMBRAL_TICKS;
        if (soul.count > before) {
            plugin.players().markDirty(player);
            tw.exilecore.skill.Fx.spawn(player.getWorld(), Particle.SCULK_SOUL, player.getLocation().add(0, 1, 0), 6, 0.4, 0.6, 0.4, 0.02);
        }
        return true;
    }

    // =====================================================================
    // 不情願的獻祭（Warcries 呼叫）
    // =====================================================================

    /** 撲殺範圍內、radius 格內最近的敵人（沒有 null）。 */
    LivingEntity offeringVictim(final Player player, final double radius) {
        LivingEntity best = null;
        double bestDistance = Double.MAX_VALUE;
        for (final LivingEntity enemy : effects.enemiesNear(player, player.getLocation(), radius)) {
            final MonsterData data = plugin.monsters() == null ? null : plugin.monsters().data(enemy).orElse(null);
            final org.bukkit.attribute.AttributeInstance max = enemy.getAttribute(org.bukkit.attribute.Attribute.MAX_HEALTH);
            if (data == null || max == null || max.getValue() <= 0
                    || enemy.getHealth() > max.getValue() * tw.exilecore.combat.CombatListener.cullThreshold(data)) {
                continue;
            }
            final double distance = enemy.getLocation().distanceSquared(player.getLocation());
            if (distance < bestDistance) {
                best = enemy;
                bestDistance = distance;
            }
        }
        return best;
    }

    /** 撲殺祭品的目標：回傳牠的強度（祭品每點強度效果增加 1%）。 */
    double cull(final Player player, final LivingEntity victim) {
        final double power = effects.power(victim);
        victim.getWorld().spawnParticle(Particle.SCULK_SOUL, victim.getLocation().add(0, 1, 0), 20, 0.4, 0.6, 0.4, 0.03);
        victim.setKiller(player);
        victim.setHealth(0);
        return power;
    }

    // =====================================================================
    // 每 10 tick
    // =====================================================================

    void tick(final long now) {
        for (final Player player : plugin.getServer().getOnlinePlayers()) {
            final PlayerState state = plugin.players().find(player.getUniqueId()).orElse(null);
            if (state == null || !state.hasCharacter() || state.isDead() || player.isDead()) {
                continue;
            }
            final StatSheet sheet = state.sheet();
            final Own own = owns.get(player.getUniqueId());
            final boolean any = own != null || AscendancyEffects.has(sheet, AscKeys.VIVID_WISPS) || AscendancyEffects.has(sheet, AscKeys.OWL_FEATHERS)
                    || AscendancyEffects.has(sheet, AscKeys.COMPANION_NEAR_DAMAGE) || AscendancyEffects.has(sheet, AscKeys.NATURAL_ORDER)
                    || on(player, "wild_protector") || on(player, "hollow_focus");
            if (!any) {
                continue;
            }
            try {
                tickPlayer(player, state, sheet, own(player), now);
            } catch (final RuntimeException e) {
                tw.exilecore.core.LogThrottle.error(plugin.getSLF4JLogger(), "昇華效果（靈狩、鳴鐘、淵井）出錯", e);
            }
        }
        trails.removeIf(trail -> trail.until() <= now);
        if (AscendancyEffects.every(now, 20)) {
            for (final Trail trail : trails) {
                drawTrail(trail);
            }
            pinned.keySet().removeIf(id -> !(plugin.getServer().getEntity(id) instanceof LivingEntity living) || !living.isValid());
        }
    }

    private void tickPlayer(final Player player, final PlayerState state, final StatSheet sheet, final Own own, final long now) {
        boolean dirty = false;
        // 靈現之靈：每移動 10 格 1 個
        final double wispMax = AscendancyEffects.flat(sheet, AscKeys.VIVID_WISPS);
        final Location here = player.getLocation();
        if (wispMax > 0 && on(player, "vivid_stampede")) {
            if (own.last != null && own.last.getWorld() == here.getWorld()) {
                final double dx = here.getX() - own.last.getX();
                final double dz = here.getZ() - own.last.getZ();
                final double step = Math.sqrt(dx * dx + dz * dz);
                if (step < 12) { // 傳送不算
                    own.moved += step;
                }
            }
            final double metres = Math.max(1, level(player, "vivid_stampede", "asc.wisp_metres") > 0 ? level(player, "vivid_stampede", "asc.wisp_metres") : WISP_METRES);
            while (own.moved >= metres && own.wisps < wispMax) {
                own.moved -= metres;
                own.wisps++;
                state.notice("<#7ec8e3>靈現之靈 " + own.wisps + "／" + (int) wispMax, now + 30);
            }
            if (own.wisps >= wispMax) {
                own.moved = 0;
            }
        } else {
            own.wisps = 0;
            own.moved = 0;
        }
        own.last = here.clone();
        // 原始梟羽：每 4 秒 1 根（姆哈查之贈：快 50%）
        final double featherMax = AscendancyEffects.flat(sheet, AscKeys.OWL_FEATHERS);
        if (featherMax > 0 && on(player, "primal_bounty")) {
            if (own.feathers >= featherMax) {
                own.featherNext = 0;
            } else if (own.featherNext <= 0) {
                own.featherNext = now + featherTicks(sheet);
            } else if (now >= own.featherNext) {
                own.feathers++;
                own.featherNext = own.feathers >= featherMax ? 0 : now + featherTicks(sheet);
                state.notice("<#e8e1c8>原始梟羽 " + own.feathers + "／" + (int) featherMax, now + 30);
            }
        } else {
            own.feathers = 0;
            own.featherNext = 0;
        }
        // 翱翔之地：站在你（或隊友）的翱翔之地上
        final boolean onTrail = onTrail(player);
        final boolean full = state.life() >= state.maxLife() - 1e-6;
        if (onTrail != own.onTrail || onTrail && full != own.trailFull) {
            own.onTrail = onTrail;
            own.trailFull = full;
            dirty = true;
        }
        // 熊靈
        tickBear(player, sheet, own, now);
        // 夥伴在存在範圍內
        if (AscendancyEffects.has(sheet, AscKeys.COMPANION_NEAR_DAMAGE) && plugin.skills() != null && plugin.skills().minions() != null) {
            final boolean near = plugin.skills().minions().companionNear(player, AscendancyEffects.presence(sheet));
            if (near != own.companionNear) {
                own.companionNear = near;
                dirty = true;
            }
        } else if (own.companionNear) {
            own.companionNear = false;
            dirty = true;
        }
        // 自然秩序：每 20 秒換一種林靈附身
        final double order = AscendancyEffects.flat(sheet, AscKeys.NATURAL_ORDER);
        if (order > 0) {
            if (now >= own.possessionNext) {
                own.possession = plugin.random().nextInt(3);
                own.possessionNext = now + Math.round(order * 20);
                dirty = true;
                state.notice("<#9fd36b>林靈附身你的夥伴：" + POSSESSION[own.possession], now + 40);
            }
        } else if (own.possession >= 0) {
            own.possession = -1;
            dirty = true;
        }
        // 空無之鐘
        tickBells(player, sheet, own, now);
        // 空無共鳴：每 0.5 秒回一次
        if (own.resonance < RESONANCE_USES && own.resonanceNext > 0 && now >= own.resonanceNext) {
            own.resonance++;
            own.resonanceNext = own.resonance < RESONANCE_USES ? now + cooldownTicks(player, resonanceTicks(player)) : 0;
        }
        // 山巒之教：8 秒沒有獲得就全部失去
        if (own.mountain > 0 && (now - own.mountainGained > MOUNTAIN_TICKS || !AscendancyEffects.has(sheet, AscKeys.MOUNTAIN))) {
            own.mountain = 0;
            dirty = true;
        }
        // 幽影之魂：本來要召喚的停了就散掉
        if (own.umbral.values().removeIf(soul -> soul.until <= now)) {
            dirty = true;
        }
        if (dirty) {
            plugin.players().markDirty(player);
        }
    }

    private static long featherTicks(final StatSheet sheet) {
        return Math.max(1, Math.round(FEATHER_TICKS / (1 + AscendancyEffects.flat(sheet, AscKeys.OWL_RATE) / 100.0)));
    }

    static final String[] POSSESSION = {"靈現（25% 傷害為額外閃電）", "原始（增加 30% 攻擊與施法速度）", "荒野（每秒再生 3% 最大生命、增加 30% 最大生命）"};

    private boolean onTrail(final Player player) {
        if (trails.isEmpty()) {
            return false;
        }
        final Set<UUID> friends = new HashSet<>();
        friends.add(player.getUniqueId());
        if (plugin.party() != null) {
            friends.addAll(plugin.party().groupMembers(player.getUniqueId()));
        }
        final Location at = player.getLocation();
        for (final Trail trail : trails) {
            if (!friends.contains(trail.owner()) || trail.from().getWorld() != at.getWorld()) {
                continue;
            }
            final Vector to = at.toVector().subtract(trail.from().toVector()).setY(0);
            final double along = to.dot(trail.direction());
            final double side = to.clone().subtract(trail.direction().clone().multiply(along)).length();
            if (along >= 0 && along <= TRAIL_LENGTH && side <= TRAIL_HALF_WIDTH && Math.abs(at.getY() - trail.from().getY()) < 3) {
                return true;
            }
        }
        return false;
    }

    private void drawTrail(final Trail trail) {
        if (trail.from().getWorld() == null) {
            return;
        }
        for (double d = 0.5; d <= TRAIL_LENGTH; d += 1.5) {
            final Location at = trail.from().clone().add(trail.direction().clone().multiply(d)).add(0, 0.15, 0);
            tw.exilecore.skill.Fx.spawn(at.getWorld(), Particle.WHITE_ASH, at, 3, 0.5, 0.05, 0.5, 0);
        }
    }

    // ---- 熊靈 ----

    /** 找 owner 那個技能叫出來的召喚物（附近 40 格內）。 */
    private LivingEntity minionOf(final Player owner, final String skillId) {
        if (plugin.skills() == null || plugin.skills().minions() == null) {
            return null;
        }
        for (final Entity entity : owner.getWorld().getNearbyEntities(owner.getLocation(), 40, 20, 40)) {
            if (entity instanceof LivingEntity living && living.isValid() && !living.isDead()
                    && skillId.equals(living.getPersistentDataContainer().get(minionSkillKey, PersistentDataType.STRING))
                    && owner.equals(plugin.skills().minions().owner(living))) {
                return living;
            }
        }
        return null;
    }

    private void tickBear(final Player player, final StatSheet sheet, final Own own, final long now) {
        final LivingEntity bear = on(player, "wild_protector") ? minionOf(player, "wild_protector") : null;
        own.bear = bear == null ? null : bear.getUniqueId();
        // 荒野擁抱（聖潔一統）：熊靈存在範圍內的你與隊友
        final boolean sacred = AscendancyEffects.has(sheet, AscKeys.SACRED_UNITY);
        final List<Player> friends = new ArrayList<>();
        friends.add(player);
        if (plugin.party() != null) {
            for (final UUID id : plugin.party().groupMembers(player.getUniqueId())) {
                final Player other = plugin.getServer().getPlayer(id);
                if (other != null && !other.equals(player)) {
                    friends.add(other);
                }
            }
        }
        for (final Player friend : friends) {
            final Own o = own(friend);
            final UUID embrace = sacred && bear != null && friend.getWorld() == bear.getWorld()
                    && friend.getLocation().distanceSquared(bear.getLocation()) <= EMBRACE_RADIUS * EMBRACE_RADIUS ? bear.getUniqueId() : null;
            if (embrace != null && !embrace.equals(o.embraced) || embrace == null && o.embraced != null
                    && (ownerOf(o.embraced) == null || player.getUniqueId().equals(ownerOf(o.embraced)))) {
                o.embraced = embrace;
                plugin.players().markDirty(friend);
            }
        }
        if (bear == null) {
            return;
        }
        final LivingEntity target = bear instanceof Mob mob ? mob.getTarget() : null;
        final boolean valid = target != null && target.isValid() && !target.isDead() && target.getWorld() == bear.getWorld()
                && effects.enemyOf(player, target);
        final double distance = valid ? target.getLocation().distance(bear.getLocation()) : Double.MAX_VALUE;
        // 躍擊：跳向較遠的敵人，落地 3 格、癱瘓、擊退
        if (valid && now >= own.bearLeapReady && distance >= 2 && distance <= 9) {
            own.bearLeapReady = now + BEAR_LEAP_TICKS;
            final Vector toward = target.getLocation().toVector().subtract(bear.getLocation().toVector());
            bear.setVelocity(toward.clone().multiply(0.18).setY(0.45));
            effects.later(10, () -> bearImpact(player, bear, BEAR_LEAP_RADIUS, true));
            return;
        }
        // 重擊：身邊 2.5 格，偷取 50%
        if (valid && now >= own.bearSlamReady && distance <= BEAR_SLAM_RADIUS + 0.5) {
            own.bearSlamReady = now + BEAR_SLAM_TICKS;
            own.bearSlamUntil = now + 2;
            bearImpact(player, bear, BEAR_SLAM_RADIUS, false);
            return;
        }
        // 怒吼：恫嚇 6 格內的敵人 8 秒、引牠們來打熊靈
        if (now >= own.bearRoarReady && plugin.combat() != null) {
            final List<LivingEntity> near = effects.enemiesNear(player, bear.getLocation(), BEAR_ROAR_RADIUS);
            if (!near.isEmpty()) {
                own.bearRoarReady = now + BEAR_ROAR_TICKS;
                bear.getWorld().playSound(bear.getLocation(), Sound.ENTITY_POLAR_BEAR_WARNING, 1.0f, 0.9f);
                tw.exilecore.skill.Fx.spawn(bear.getWorld(), Particle.SONIC_BOOM, bear.getLocation().add(0, 1, 0), 1, 0, 0, 0, 0);
                for (final LivingEntity enemy : near) {
                    plugin.combat().debuffs().apply(enemy.getUniqueId(), new Debuffs.Debuff("intimidate", player.getUniqueId(), now + INTIMIDATE_TICKS,
                            tw.exilecore.stats.TreeLastKeys.INTIMIDATE_TAKEN, false));
                    if (enemy instanceof Mob mob) {
                        mob.setTarget(bear);
                    }
                }
            }
        }
    }

    private UUID ownerOf(final UUID bear) {
        final Entity entity = bear == null ? null : plugin.getServer().getEntity(bear);
        if (!(entity instanceof LivingEntity living) || plugin.skills() == null || plugin.skills().minions() == null) {
            return null;
        }
        final Player owner = plugin.skills().minions().owner(living);
        return owner == null ? null : owner.getUniqueId();
    }

    /** 熊靈打 radius 格內的敵人（用熊靈自己的攻擊）；躍擊另外癱瘓。 */
    private void bearImpact(final Player owner, final LivingEntity bear, final double radius, final boolean maim) {
        if (!bear.isValid() || bear.isDead() || plugin.combat() == null) {
            return;
        }
        final Location at = bear.getLocation();
        bear.getWorld().playSound(at, Sound.ENTITY_RAVAGER_STEP, 1.0f, 0.8f);
        tw.exilecore.skill.Fx.spawn(bear.getWorld(), Particle.BLOCK, at.clone().add(0, 0.2, 0), 24, radius * 0.4, 0.1, radius * 0.4, 0,
                Material.DIRT.createBlockData());
        final long now = now();
        for (final LivingEntity enemy : effects.enemiesNear(owner, at, radius)) {
            enemy.setNoDamageTicks(0);
            enemy.damage(1.0, bear); // 數字由 CombatListener 換成熊靈這一下的傷害
            final Vector push = enemy.getLocation().toVector().subtract(at.toVector()).setY(0);
            if (push.lengthSquared() > 1e-6) {
                enemy.setVelocity(enemy.getVelocity().add(push.normalize().multiply(0.4).setY(0.15)));
            }
            if (maim) {
                plugin.combat().debuffs().apply(enemy.getUniqueId(), new Debuffs.Debuff("maim", owner.getUniqueId(), now + 80, 0, false, null,
                        List.of(Modifier.increased(StatKeys.EVASION, -tw.exilecore.combat.HitEffects.MAIM_EVASION, "癱瘓")),
                        tw.exilecore.combat.HitEffects.MAIM_SLOW));
            }
        }
    }

    // ---- 空無之鐘 ----

    private void tickBells(final Player player, final StatSheet sheet, final Own own, final long now) {
        final Iterator<Bell> iterator = own.bells.iterator();
        while (iterator.hasNext()) {
            final Bell bell = iterator.next();
            if (bell.until() <= now || bell.at().getWorld() != player.getWorld()) {
                if (bell.display() != null && bell.display().isValid()) {
                    bell.display().remove();
                }
                iterator.remove();
            } else if (AscendancyEffects.every(now, 20)) {
                tw.exilecore.skill.Fx.spawn(bell.at().getWorld(), Particle.ENCHANT, bell.at(), 4, 0.2, 0.3, 0.2, 0.2);
            }
        }
        if (!on(player, "hollow_focus")) {
            for (final Bell bell : own.bells) {
                if (bell.display() != null && bell.display().isValid()) {
                    bell.display().remove();
                }
            }
            own.bells.clear();
            own.bellNext = 0;
            return;
        }
        final long interval = cooldownTicks(player, Math.round(Math.max(0.5, level(player, "hollow_focus", "asc.bell_interval")) * 20));
        if (own.bellNext <= 0) {
            own.bellNext = now + interval;
        }
        if (now < own.bellNext || plugin.skills() != null && plugin.skills().inTown(player)) {
            return;
        }
        // 2026-10-28 照 PoE2：「上限 3 口鐘」吃 +x 上限、鐘的持續時間吃技能持續時間（過剩）；這次之前固定 3 口、21 秒
        if (now - own.bellCheckedAt >= 20) {
            own.bellCheckedAt = now;
            final SkillBuild focus = plugin.skills() == null ? null : plugin.skills().equippedBuild(player, "hollow_focus");
            own.bellBonus = tw.exilecore.skill.SkillLimits.bonus(effects.sheet(player), focus);
            own.bellTicks = focus == null ? BELL_TICKS : focus.durationTicks(BELL_TICKS / 20.0);
        }
        if (own.bells.size() >= BELL_MAX + own.bellBonus) {
            return;
        }
        own.bellNext = now + interval;
        final double angle = plugin.random().nextDouble() * Math.PI * 2;
        final double r = 1.2 + plugin.random().nextDouble() * (BELL_SPAWN - 1.2);
        final Location at = player.getLocation().add(Math.cos(angle) * r, 1.3, Math.sin(angle) * r);
        final ItemDisplay display = at.getWorld().spawn(at, ItemDisplay.class, d -> {
            d.setItemStack(new ItemStack(Material.BELL));
            d.setPersistent(false);
            d.setGlowing(true);
            d.setBillboard(Display.Billboard.CENTER);
            d.setTransformation(new org.bukkit.util.Transformation(new org.joml.Vector3f(), new org.joml.Quaternionf(),
                    new org.joml.Vector3f(0.6f, 0.6f, 0.6f), new org.joml.Quaternionf()));
            d.setVisibleByDefault(false); // 2026-10-27 照站長：只有自己看得到
        });
        player.showEntity(plugin, display);
        own.bells.add(new Bell(at, display, now + own.bellTicks));
        at.getWorld().playSound(at, Sound.BLOCK_AMETHYST_BLOCK_CHIME, 0.7f, 0.6f);
    }

    // =====================================================================
    // 增益（重算屬性表時）
    // =====================================================================

    /**
     * 2026-10-27：只看屬性表的增益（{@link #buffModifiers} 與網頁的虛擬角色共用）：凱莎的平衡（夥伴獲得附加攻擊傷害 = 主手武器傷害的 60%）、
     * 自然秩序（夥伴增加 30% 移動速度）。純函式（測試用）。
     */
    static List<Modifier> sheetOnly(final StatSheet sheet) {
        final List<Modifier> out = new ArrayList<>();
        if (sheet == null) {
            return out;
        }
        final double weapon = AscendancyEffects.flat(sheet, AscKeys.COMPANION_WEAPON);
        if (weapon > 0) {
            for (final DamageType type : DamageType.values()) {
                final double min = sheet.flat(StatKeys.WEAPON_MIN, type.tags()) * weapon / 100.0;
                final double max = sheet.flat(StatKeys.WEAPON_MAX, type.tags()) * weapon / 100.0;
                if (max > 0) {
                    final Set<String> tags = Set.of(type.tag(), Tags.ATTACK, Tags.MINION, tw.exilecore.stats.TreeMechanicKeys.COMPANION);
                    out.add(new Modifier(StatKeys.DAMAGE_ADDED_MIN, ModType.FLAT, min, tags, "凱莎的平衡"));
                    out.add(new Modifier(StatKeys.DAMAGE_ADDED_MAX, ModType.FLAT, max, tags, "凱莎的平衡"));
                }
            }
        }
        if (AscendancyEffects.has(sheet, AscKeys.NATURAL_ORDER)) {
            out.add(new Modifier(StatKeys.MOVEMENT_SPEED, ModType.INCREASED, ORDER_SPEED,
                    Set.of(Tags.MINION, tw.exilecore.stats.TreeMechanicKeys.COMPANION), "自然秩序"));
        }
        return out;
    }

    List<Modifier> buffModifiers(final Player player, final PlayerState state) {
        final Own own = owns.get(player.getUniqueId());
        final StatSheet sheet = state.sheet();
        final List<Modifier> out = new ArrayList<>();
        // 凱莎的平衡、自然秩序的移動速度（只看屬性表；網頁的虛擬角色也用，2026-10-27）
        out.addAll(sheetOnly(sheet));
        // 自然秩序：林靈附身
        final Set<String> companion = Set.of(Tags.MINION, tw.exilecore.stats.TreeMechanicKeys.COMPANION);
        if (AscendancyEffects.has(sheet, AscKeys.NATURAL_ORDER)) {
            final int possession = own == null ? -1 : own.possession;
            if (possession == 0) {
                out.add(new Modifier(StatKeys.DAMAGE_EXTRA + Tags.LIGHTNING, ModType.FLAT, 25, companion, "林靈附身（靈現）"));
            } else if (possession == 1) {
                out.add(new Modifier(StatKeys.ATTACK_SPEED, ModType.INCREASED, 0.30, companion, "林靈附身（原始）"));
                out.add(new Modifier(StatKeys.CAST_SPEED, ModType.INCREASED, 0.30, companion, "林靈附身（原始）"));
            } else if (possession == 2) {
                out.add(new Modifier(StatKeys.LIFE_REGEN_PERCENT, ModType.FLAT, 3, companion, "林靈附身（荒野）"));
                out.add(new Modifier(StatKeys.LIFE_MAX, ModType.INCREASED, 0.30, companion, "林靈附身（荒野）"));
            }
        }
        if (own == null) {
            return out;
        }
        // 共享盟友傷害：夥伴在存在範圍內時增加 10% 傷害
        if (own.companionNear) {
            final double near = AscendancyEffects.flat(sheet, AscKeys.COMPANION_NEAR_DAMAGE);
            if (near > 0) {
                out.add(Modifier.increased(StatKeys.DAMAGE, near / 100.0, "夥伴在身邊"));
            }
        }
        // 翱翔之地：增加 30% 閃避值，生命全滿時增加 40% 傷害
        if (own.onTrail) {
            out.add(Modifier.increased(StatKeys.EVASION, 0.30, "翱翔之地"));
            if (state.life() >= state.maxLife() - 1e-6) {
                out.add(Modifier.increased(StatKeys.DAMAGE, 0.40, "翱翔之地"));
            }
        }
        // 荒野擁抱：熊靈存在範圍內每秒再生 2% 最大生命
        if (own.embraced != null) {
            out.add(Modifier.flat(StatKeys.LIFE_REGEN_PERCENT, EMBRACE_REGEN, "荒野擁抱"));
        }
        // 山巒之教：暈眩門檻更多 50%
        if (own.mountain > 0) {
            out.add(Modifier.more(StatKeys.STUN_THRESHOLD, MOUNTAIN_STUN, "山巒之教"));
        }
        // 幽影之魂
        final long now = now();
        for (final Map.Entry<String, Umbral> entry : own.umbral.entrySet()) {
            if (entry.getValue().until <= now) {
                continue;
            }
            for (final Modifier mod : UMBRAL.getOrDefault(entry.getKey(), List.of())) {
                out.add(new Modifier(mod.stat(), mod.type(), mod.value() * entry.getValue().count, mod.tags(), mod.source()));
            }
        }
        return out;
    }
}
