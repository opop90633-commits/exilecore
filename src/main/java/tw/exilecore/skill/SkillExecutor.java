package tw.exilecore.skill;

import org.bukkit.FluidCollisionMode;
import org.bukkit.Location;
import org.bukkit.Tag;
import org.bukkit.World;
import org.bukkit.block.Block;
import org.bukkit.entity.LivingEntity;
import org.bukkit.entity.Player;
import org.bukkit.util.RayTraceResult;
import org.bukkit.util.Vector;
import tw.exilecore.ExileCore;
import tw.exilecore.combat.Debuffs;
import tw.exilecore.combat.SkillDamage;
import tw.exilecore.stats.SkillProfile;
import tw.exilecore.stats.StatKeys;
import tw.exilecore.stats.StatSheet;
import tw.exilecore.stats.Tags;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;

/**
 * 各種運作方式的實作：找目標、打下去、畫外觀。投射物交給 {@link ProjectileEngine}，
 * 留在地上、延遲發生的交給 {@link SkillZones}。技能從一個 {@link Origin} 放出來（玩家自己，或之後的圖騰、陷阱）。
 */
final class SkillExecutor {

    private final ExileCore plugin;
    private final SkillTargets targets;
    private final SkillVisuals visuals;
    private final ProjectileEngine projectiles;
    private final SkillZones zones;
    private final SkillDamage damage;

    SkillExecutor(final ExileCore plugin, final SkillTargets targets, final SkillVisuals visuals, final ProjectileEngine projectiles,
                  final SkillZones zones, final SkillDamage damage) {
        this.plugin = plugin;
        this.targets = targets;
        this.visuals = visuals;
        this.projectiles = projectiles;
        this.zones = zones;
        this.damage = damage;
    }

    /** 花魔力之前的檢查；可以放回傳 null，不行回傳原因。 */
    String precheck(final Player player, final SkillBuild build, final LivingEntity preferred) {
        if (Snap.is(build)) {
            return snap().problem(player, build, preferred); // 2026-10-27 照 PoE2 Snap：要對準異常中的敵人或霜鑽
        }
        switch (build.definition().mechanic()) {
            case LEAP -> {
                if (landing(player, build.range()) == null) {
                    return "前方沒有可以落腳的地方";
                }
            }
            case DASH -> {
                if (hasFlag(build.definition(), StatKeys.FLAG_LIGHTNING_WARP)) { // 2026-10-26：雷遁要瞄準敵人（2026-10-28 照 PoE2：或自己的滾雷珠）
                    return warpAim(player, build).pick() == LightningWarp.Pick.NONE ? "準心沒有指著敵人或自己的滾雷珠" : null;
                }
                if (!build.params().dash().whirls() && dashDestination(player, build.range()) == null) { // 2026-10-28 照 PoE2：迴旋棍步前面是牆照樣原地轉
                    return "前方沒有空間";
                }
            }
            case MARK -> {
                if (!build.params().mark().curse() && markTarget(player, build, preferred) == null) {
                    return "準心沒有指著敵人";
                }
            }
            case CORPSE -> {
                if (corpseNear(Origin.of(player), build) == null) {
                    return "附近沒有屍體";
                }
            }
            case FLICKER -> {
                return flickerCheck(player, build, preferred);
            }
            default -> {
            }
        }
        return null;
    }

    /** 放出技能。跳躍會回傳滯空的 tick 數（其他回傳 0），期間不能做別的事。 */
    int execute(final Player player, final SkillRuntime runtime, final SkillBuild build, final LivingEntity preferred,
                final long now) {
        final Origin origin = Origin.of(player);
        switch (build.definition().mechanic()) {
            case STRIKE -> {
                if (KillingPalm.is(build)) {
                    palm(player, runtime, build, preferred); // 2026-10-28 照 PoE2 斷魂掌：衝過去打、撲殺就連衝；這次之前原地打一下
                } else {
                    strike(origin, build, preferred);
                }
            }
            case PROJECTILE -> projectiles.launch(origin, build);
            case NOVA -> nova(origin, build);
            case CONE -> {
                cone(origin, build);
                extraFissure(origin, build); // 2026-10-27 照 PoE2 分裂地面
                if (Unearth.is(build)) {
                    unearth().raise(origin, build); // 2026-10-27 照 PoE2 Unearth：範圍內的屍體變骨構體
                }
            }
            case CHAIN -> chain(origin, build, preferred);
            case LEAP -> {
                return leap(player, runtime, build, now);
            }
            case DASH -> {
                if (hasFlag(build.definition(), StatKeys.FLAG_LIGHTNING_WARP)) {
                    // 2026-10-28 照 PoE2：主要寶石觸發的雷遁對觸發的目標（暴擊打到的那隻…），不看準心；這次之前雷遁不能插進主要寶石
                    warp(player, build, triggered(build) ? triggeredAim(player, build, preferred) : warpAim(player, build));
                } else {
                    dash(player, build);
                }
            }
            case IMPACT -> impactOrSnap(origin, build, preferred);
            case ZONE -> {
                zone(origin, build);
                extraFissure(origin, build); // 2026-10-27 照 PoE2 分裂地面
            }
            case MARK -> mark(player, build, preferred);
            case CORPSE -> detonate(origin, build);
            case FLICKER -> flicker(player, build, preferred);
            default -> throw new IllegalStateException(build.definition().mechanic().name());
        }
        return 0;
    }

    /** 從別的地方放（圖騰、陷阱、雙詠）：不含跳躍、位移、瞬影、印記這些只有玩家本人能做的。 */
    void executeFrom(final Origin origin, final SkillBuild build, final LivingEntity preferred) {
        switch (build.definition().mechanic()) {
            // 斷魂掌（2026-10-28 照 PoE2）：原作沒有 Totemable／Triggerable，不會從圖騰、觸發放出來；萬一從別的地方放就只在原地打（不衝刺、不連衝）。
            // 複擊的那一下是玩家自己打，走 palmAgain（會衝刺、連衝）
            case STRIKE -> strike(origin, build, preferred);
            case PROJECTILE -> projectiles.launch(origin, build);
            case NOVA -> nova(origin, build);
            case CONE -> {
                cone(origin, build);
                extraFissure(origin, build); // 2026-10-27 照 PoE2 分裂地面
            }
            case CHAIN -> chain(origin, build, preferred);
            case IMPACT -> impactOrSnap(origin, build, preferred);
            case ZONE -> {
                zone(origin, build);
                extraFissure(origin, build); // 2026-10-27 照 PoE2 分裂地面
            }
            // 2026-10-28 照 PoE2 Spell Totem：骸爆從圖騰放，引爆圖騰目標附近的屍體（沒有就圖騰身邊的）；這次之前骸爆不能交給圖騰
            case CORPSE -> detonate(origin, build, build.profile().tags().contains(tw.exilecore.stats.Tags.TOTEM) ? preferred : null);
            case MARK -> { // 2026-10：輔助觸發的減益（殘肢、目眩、曝露）：範圍詛咒落在目標身上，沒有目標就在原點
                // 2026-10-28 照 PoE2 Spell Totem：圖騰放的詛咒、縛根一樣落在圖騰的目標身上，算你的詛咒（佔你的詛咒上限），
                //   縛根的品質（多一道裂隙）照圖騰面向目標的方向；這次之前詛咒不能交給圖騰
                final SkillParams.Mark spec = build.params().mark();
                if (spec.curse() && origin.owner() != null) {
                    curseFrom(origin, origin.owner(), build, preferred != null ? preferred.getLocation() : origin.feet(), spec);
                }
            }
            default -> {
            }
        }
    }

    // ---- 近戰打擊 ----

    /** 近戰擴散打到目標周圍幾格（再乘範圍效果）。 */
    static final double MELEE_SPLASH_RADIUS = 2.5;
    /** 打擊技能的額外目標最多幾個。 */
    static final int MAX_STRIKE_TARGETS = 6;

    /** 打擊技能多打幾個（只有 strike 標籤、原本只打一個的；照屬性表）。 */
    static int extraStrikeTargets(final StatSheet sheet, final SkillBuild build) {
        if (build.targets() != 1 || !build.profile().tags().contains(tw.exilecore.stats.Tags.STRIKE)) {
            return 0;
        }
        final double extra = build.profile().effective(sheet).flat(StatKeys.STRIKE_TARGETS, build.profile().tags());
        return Math.max(0, Math.min(MAX_STRIKE_TARGETS, (int) Math.round(extra)));
    }

    /** 近戰擴散：周圍的敵人受到這一下幾 %（只有近戰的打擊）。 */
    static double meleeSplash(final StatSheet sheet, final SkillBuild build) {
        if (!build.profile().tags().contains(tw.exilecore.stats.Tags.MELEE)) {
            return 0;
        }
        return Math.max(0, Math.min(200, build.profile().effective(sheet).flat(StatKeys.MELEE_SPLASH, build.profile().tags())));
    }

    private void strike(final Origin origin, final SkillBuild build, final LivingEntity preferred) {
        // 2026-10-28 照 PoE2：連段的那一下多的打擊距離（寒冰震波第三擊 +0.7 公尺…；SkillStages）
        final double reach = build.reach() + SkillStages.mark(build.profile(), SkillStages.REACH);
        final Location from = origin.feet();
        final List<LivingEntity> hits = new ArrayList<>();
        final StatSheet ownerSheet = origin.owner() == null ? null : plugin.players().sheet(origin.owner());
        final int extraTargets = ownerSheet == null ? 0 : extraStrikeTargets(ownerSheet, build);
        if (build.targets() == 1) {
            LivingEntity target = null;
            if (preferred != null && targets.valid(preferred, origin.owner())
                    && preferred.getLocation().distanceSquared(from) <= Math.pow(reach + 1.5, 2)) {
                target = preferred;
            }
            if (target == null) {
                target = targets.aimed(origin, reach, 0.6);
            }
            if (target == null) {
                final List<LivingEntity> cone = targets.cone(origin, reach, build.angle());
                target = cone.isEmpty() ? null : cone.get(0);
            }
            if (target != null) {
                hits.add(target);
                if (extraTargets > 0) {
                    // 額外目標：先找扇形裡的，不夠再找第一個目標附近的
                    for (final LivingEntity other : targets.cone(origin, reach + 1, Math.max(build.angle(), 120))) {
                        if (hits.size() > extraTargets) {
                            break;
                        }
                        if (!hits.contains(other)) {
                            hits.add(other);
                        }
                    }
                    for (final LivingEntity other : targets.around(target.getLocation(), 3.0, origin.owner())) {
                        if (hits.size() > extraTargets) {
                            break;
                        }
                        if (!hits.contains(other)) {
                            hits.add(other);
                        }
                    }
                }
            }
        } else {
            hits.addAll(targets.cone(origin, reach, build.angle()));
            // 2026-10 手感：準心指到的敵人一定打得到（跟單一目標的刺擊一樣有輔助瞄準）；以前多目標的只看扇形，準心指著但偏出扇形的打不到
            final LivingEntity aimed = preferred != null && targets.valid(preferred, origin.owner())
                    && preferred.getLocation().distanceSquared(from) <= Math.pow(reach + 1.5, 2) ? preferred : targets.aimed(origin, reach, 0.6);
            if (aimed != null) {
                hits.remove(aimed);
                hits.add(0, aimed);
            }
            if (build.targets() > 0 && hits.size() > build.targets()) {
                hits.subList(build.targets(), hits.size()).clear();
            }
        }
        final SkillParams.Execute execute = build.params().execute();
        // 2026-10-28 照 PoE2：一下打好幾次（風暴連擊第三下快速連打三次；SkillStages 的 hits）
        final int repeatsHere = SkillStages.hits(build.profile());
        for (int round = 0; round < repeatsHere; round++) {
            for (final LivingEntity target : hits) {
                if (round > 0 && (!target.isValid() || target.isDead())) {
                    continue;
                }
                SkillProfile profile = build.profile();
                boolean executed = false;
                if (execute != null && lifeFraction(target) < execute.below()) {
                    profile = profile.scaled(1 + execute.more() / 100.0, "處決");
                    executed = true;
                }
                damage.hit(origin.owner(), target, profile, knockback(from, target, build.knockback()));
                if (executed) {
                    visuals.execute(SkillTargets.center(target));
                }
            }
        }
        // 近戰擴散：每個打中的目標周圍的其他敵人也挨一下（打到的不重複）
        double splash = ownerSheet == null || hits.isEmpty() ? 0 : meleeSplash(ownerSheet, build);
        if (ownerSheet != null && !hits.isEmpty() && splash < tw.exilecore.stats.KeystoneOriginKeys.SPLASH_ON_PROC
                && build.profile().tags().contains(tw.exilecore.stats.Tags.MELEE)) {
            // 2026-10-27 照 PoE2 劈裂大地：「錘的打擊有 x% 機率造成擴散傷害」，擲中就照擴散輔助的 60% 擴散；這次之前平衡等效成一律 6% 擴散
            final double chance = build.profile().effective(ownerSheet).flat(tw.exilecore.stats.KeystoneOriginKeys.SPLASH_CHANCE, build.profile().tags());
            if (chance > 0 && plugin.random().nextDouble() * 100 < chance) {
                splash = tw.exilecore.stats.KeystoneOriginKeys.SPLASH_ON_PROC;
            }
        }
        if (splash > 0) {
            final SkillProfile splashProfile = build.profile().scaled(splash / 100.0, "近戰擴散");
            final double radius = MELEE_SPLASH_RADIUS * build.areaScale();
            final java.util.Set<LivingEntity> splashed = new java.util.HashSet<>(hits);
            for (final LivingEntity target : List.copyOf(hits)) {
                for (final LivingEntity other : targets.around(target.getLocation(), radius, origin.owner())) {
                    if (splashed.add(other)) {
                        damage.hit(origin.owner(), other, splashProfile, null);
                    }
                }
                tw.exilecore.skill.Fx.spawn(target.getWorld(), org.bukkit.Particle.SWEEP_ATTACK, SkillTargets.center(target), 2, radius * 0.3, 0.2, radius * 0.3, 0);
            }
        }
        // 2026-10-25：揮到的範圍裡的鐘、冰晶直接被打到（不用旁邊有敵人）
        targets.strike(origin.owner(), build.profile(), SkillTargets.coneShape(origin, reach, Math.max(build.angle(), 60)), new Object());
        visuals.strike(build.definition().visual(), origin, hits, reach, build.angle());
    }

    /** 目標還剩幾成生命（原版血量是等比例縮放的，直接看）。 */
    static double lifeFraction(final LivingEntity target) {
        final org.bukkit.attribute.AttributeInstance max = target.getAttribute(org.bukkit.attribute.Attribute.MAX_HEALTH);
        final double top = max == null ? 20 : max.getValue();
        return top <= 0 ? 1 : target.getHealth() / top;
    }

    private static Vector knockback(final Location from, final LivingEntity target, final double strength) {
        if (strength <= 0) {
            return null;
        }
        final Vector direction = target.getLocation().toVector().subtract(from.toVector()).setY(0);
        if (direction.lengthSquared() < 1e-6) {
            direction.setX(1);
        }
        return direction.normalize().multiply(strength).setY(0.3);
    }

    // ---- 範圍 ----

    private void nova(final Origin origin, final SkillBuild build) {
        final double radius = build.radius();
        Location center = origin.feet();
        // 冰新星（2026-10 PoE2）：自己的霜鑽還在飛就從那裡爆開
        if (origin.self() && hasFlag(build.definition(), tw.exilecore.stats.StatKeys.FLAG_NOVA_FROM_FROSTBOLT)) {
            final Location bolt = projectiles.latest(origin.owner().getUniqueId(), "frostbolt", center, 30);
            if (bolt != null) {
                center = bolt;
            }
        }
        for (final LivingEntity target : targets.around(center, radius, origin.owner())) {
            if (origin.canSee(target)) {
                damage.hit(origin.owner(), target, build.profile(), knockback(center, target, build.params().knockback() > 0 ? build.knockback() : 0)); // 2026-10-28：nova 寫了 knockback 才擊退（其他新星照舊）
            }
        }
        targets.strike(origin.owner(), build.profile(), SkillTargets.sphere(center, radius), new Object()); // 2026-10-25
        visuals.nova(build.definition().visual(), center, radius);
        final SkillParams.Zone ground = build.params().ground();
        if (ground != null) {
            zones.addCircle(origin.owner(), center, build.scaledRadius(ground.radius()), build.profile().scaled(ground.damage() / 100.0, "地面效果"),
                    build.definition().visual(), build.durationTicks(ground.duration()), ground.interval(), ground.pull());
        }
        final SkillParams.Aftershock aftershock = build.params().aftershock();
        // 2026-10-28 照 PoE2 Earthquake：鋸齒地面有逐級上限（2 → 4 → 8）、不能疊在現有的上面，產生不了就沒有餘震
        if (aftershock != null && (plugin.skills() == null
                || plugin.skills().stageAttacks().allowAftershock(origin.owner(), build, center, Math.round(aftershock.delay() * 20)))) {
            // 2026-10-26：餘震的半徑、傷害照等級（PoE2 地動逐級）；天賦「你親自使用的猛擊的餘震範圍增加」
            final double shockArea = origin.self() && build.definition().tags().contains(tw.exilecore.stats.Tags.SLAM)
                    ? TreeMechanics.aftershockRadius(build.profile().effective(plugin.players().sheet(origin.owner())), build.profile().tags()) : 1.0;
            zones.addImpact(origin.owner(), center, radius * aftershock.radius(build.level()) * shockArea,
                    build.profile().scaled(1 + aftershock.more(build.level()) / 100.0, "餘震"),
                    build.definition().visual(), (int) Math.round(aftershock.delay() * 20));
        }
        if (origin.self()) {
            uniqueAftershocks(origin.owner(), build, center, radius);
        }
    }

    /** 2026-10-26（鑄父之錘，PoE2 Hrimnor's Hymn）：你自己使用的猛擊技能多造成幾次餘震（同樣的範圍與傷害，間隔 0.5 秒）。 */
    private void uniqueAftershocks(final Player owner, final SkillBuild build, final Location center, final double radius) {
        uniqueAftershocks(owner, build, build.profile(), center, radius);
    }

    /** 2026-10-28：同上，餘震照 profile（奔雷踏終點的猛擊有自己的表）。 */
    private void uniqueAftershocks(final Player owner, final SkillBuild build, final SkillProfile profile, final Location center, final double radius) {
        final int extra = extraAftershocks(owner, build);
        if (extra <= 0) {
            return;
        }
        final double shockRadius = radius * TreeMechanics.aftershockRadius(build.profile().effective(plugin.players().sheet(owner)), build.profile().tags());
        for (int i = 1; i <= Math.min(3, extra); i++) {
            zones.addImpact(owner, center, shockRadius, profile.scaled(1.0, "餘震"), build.definition().visual(), 10 * i);
        }
    }

    /**
     * 你自己使用的猛擊多幾次餘震：鑄父之錘、天賦樹的機率、技能自己的「x% 機率多一次餘震」（2026-10-28 照 PoE2 奔雷踏、炎脈裂地的品質，
     * 同一個鍵 slam.aftershock_chance 寫在技能的品質裡）。
     */
    private int extraAftershocks(final Player owner, final SkillBuild build) {
        if (owner == null || !build.definition().tags().contains(tw.exilecore.stats.Tags.SLAM)) {
            return 0;
        }
        final tw.exilecore.stats.StatSheet sheet = build.profile().effective(plugin.players().sheet(owner));
        // 2026-10-26 照 PoE2 天賦：「猛擊 x% 機率多造成一次餘震」（錘、變身限定的帶標籤；兩套鍵都算）、「餘震範圍增加」
        return (int) Math.round(plugin.players().sheet(owner).flat(tw.exilecore.item.unique.UniqueStats.SLAM_AFTERSHOCK, java.util.Set.of()))
                + TreeMechanics.extraAftershocks(sheet, build.profile().tags(), plugin.random())
                + tw.exilecore.stats.TreeEffectKeys.fromChance(sheet.flat(tw.exilecore.stats.TreeEffectKeys.SLAM_AFTERSHOCK_CHANCE,
                        build.profile().tags()), plugin.random());
    }

    /** 2026-10-28 照 PoE2 炎脈裂地的品質：裂縫的餘震——整道裂縫 0.5 秒後再打一次（每隻只算一下）。 */
    private void lineAftershocks(final Player owner, final SkillBuild build, final SkillProfile profile, final List<Location> points, final double radius) {
        final int extra = Math.min(3, extraAftershocks(owner, build));
        for (int i = 1; i <= extra; i++) {
            plugin.getServer().getScheduler().runTaskLater(plugin, () -> {
                if (!owner.isOnline()) {
                    return;
                }
                final java.util.Set<LivingEntity> hit = new java.util.LinkedHashSet<>();
                for (final Location point : points) {
                    if (point.getWorld() != null && point.getWorld().equals(owner.getWorld())) {
                        hit.addAll(targets.around(point, radius, owner));
                        visuals.nova(build.definition().visual(), point, radius);
                    }
                }
                final SkillProfile shock = profile.scaled(1.0, "餘震");
                for (final LivingEntity target : hit) {
                    damage.hit(owner, target, shock, null);
                }
            }, 10L * i);
        }
    }

    /** 嵐棍橫掃品質的近身加成：1.5 公尺內全部、4 公尺以外沒有。 */
    static final double CLOSE_FULL = 1.5;
    static final double CLOSE_NONE = 4.0;

    /** 2026-10-28：距離 distance 公尺時近身更多幾（小數）；percent 是 1.5 公尺內的更多 %。純函式（測試用）。 */
    static double closeMore(final double percent, final double distance) {
        if (percent <= 0 || distance >= CLOSE_NONE) {
            return 0;
        }
        final double t = distance <= CLOSE_FULL ? 1 : (CLOSE_NONE - distance) / (CLOSE_NONE - CLOSE_FULL);
        return percent / 100.0 * t;
    }

    /** 2026-10-28：技能自己（skills.yml 的 modifiers）有沒有寫這個屬性。 */
    static boolean hasModifier(final SkillDefinition definition, final String stat) {
        for (final tw.exilecore.stats.Modifier modifier : definition.modifiers()) {
            if (modifier.stat().equals(stat)) {
                return true;
            }
        }
        return false;
    }

    static boolean hasFlag(final SkillDefinition definition, final String flag) {
        for (final tw.exilecore.stats.Modifier modifier : definition.modifiers()) {
            if (modifier.stat().equals(flag) && modifier.value() > 0) {
                return true;
            }
        }
        return false;
    }

    private void cone(final Origin origin, final SkillBuild build) {
        final double length = build.length();
        final List<LivingEntity> coneHits = targets.cone(origin, length, build.angle());
        // 2026-10-28 照 PoE2 Lightning Conduit：範圍內有感電中的敵人時多劈 1 → 2 道雷，隨機分給打到的敵人（這次之前沒做）
        final int extraBolts = origin.owner() == null || coneHits.isEmpty() ? 0 : (int) Math.round(build.profile()
                .effective(plugin.players().sheet(origin.owner())).flat(tw.exilecore.stats.SpellParityKeys.SHOCKED_BOLTS, build.profile().tags()));
        final boolean shockedInside = extraBolts > 0 && plugin.ailments() != null
                && coneHits.stream().anyMatch(target -> plugin.ailments().has(target, tw.exilecore.ailment.AilmentType.SHOCK));
        // 2026-10-28 照 PoE2 Wind Serpent's Fury（base_knockback_distance）：技能自己寫了擊退的扇形攻擊會擊退（品質再更多 x%）；這次之前扇形沒有擊退
        final boolean pushes = build.knockback() > 0 && hasModifier(build.definition(), StatKeys.SKILL_KNOCKBACK);
        // 2026-10-28 照 PoE2 Wind Blast 的品質：1.5 公尺內更多 x% 傷害，到 4 公尺遞減到沒有
        final double close = build.qualityFlat(tw.exilecore.stats.GemQualityKeys.CLOSE_MORE);
        for (final LivingEntity target : coneHits) {
            final double near = close > 0 ? closeMore(close, Math.max(0, target.getLocation().distance(origin.feet()) - target.getWidth() / 2)) : 0;
            damage.hit(origin.owner(), target, near > 0 ? build.profile().scaled(1 + near, "近身") : build.profile(),
                    pushes ? knockback(origin.feet(), target, build.knockback()) : null);
        }
        if (shockedInside) {
            for (final LivingEntity target : ZonePulses.distribute(coneHits, extraBolts, plugin.random())) {
                if (target.isValid() && !target.isDead()) {
                    damage.hit(origin.owner(), target, build.profile(), null);
                    visuals.impact(build.definition().visual(), target.getLocation());
                }
            }
        }
        targets.strike(origin.owner(), build.profile(), SkillTargets.coneShape(origin, length, build.angle()), new Object()); // 2026-10-25
        visuals.cone(build.definition().visual(), origin, length, build.angle());
        coneStages(origin, build, length);
        final SkillParams.Aftershock aftershock = build.params().aftershock();
        if (aftershock != null) {
            final org.bukkit.util.Vector facing = origin.facing().clone().setY(0);
            if (facing.lengthSquared() > 1.0E-6) {
                facing.normalize();
            }
            final Location center = origin.feet().clone().add(facing.multiply(length * 0.75));
            zones.addImpact(origin.owner(), center, length * aftershock.radius(build.level()),
                    build.profile().scaled(1 + aftershock.more(build.level()) / 100.0, "第二擊"),
                    build.definition().visual(), (int) Math.round(aftershock.delay() * 20));
        }
    }

    /** 2026-10-28 照 PoE2 Glacial Cascade：最後一根冰稜用掉凍結時更多幾倍（一般怪更多 250%、傳奇怪更多 425%）。 */
    static final double FINAL_SPIKE_FREEZE_MORE = 2.5;
    static final double FINAL_SPIKE_FREEZE_MORE_UNIQUE = 4.25;
    /** 2026-10-28 照 PoE2 Wind Serpent's Fury：每條風蛇隔幾 tick 跟在矛後面。 */
    static final int SERPENT_GAP_TICKS = 5;

    /**
     * 2026-10-28 照 PoE2：扇形技能的分段（SkillStages）。
     * final（冰脊裂的最後一根冰稜）：在扇形最遠的地方再打一小圈（自己的傷害表、轉換、半徑），打到凍結的敵人用掉凍結、更多 250%（傳奇 425%）；
     * serpent（蛇嵐突的風蛇）：你親自用的時候用掉身上所有的怒星（charge_cost 先付的那一顆也算一條），每顆一條風蛇隔一下沿同一道再打一次。
     */
    private void coneStages(final Origin origin, final SkillBuild build, final double length) {
        final org.bukkit.util.Vector facing = origin.facing().clone().setY(0);
        if (facing.lengthSquared() > 1.0E-6) {
            facing.normalize();
        }
        final SkillStages.Stage spike = SkillStages.get(build.definition().id(), "final");
        if (spike != null) {
            final Location tip = origin.feet().clone().add(facing.clone().multiply(length));
            final double radius = SkillStages.radius(build, "final", 0.9 * build.areaScale());
            final SkillProfile profile = SkillStages.profile(build, build.profile(), "final");
            for (final LivingEntity target : targets.around(tip, radius, origin.owner())) {
                SkillProfile hit = profile;
                if (plugin.ailments() != null && plugin.ailments().isFrozen(target)) {
                    final boolean unique = plugin.monsters() != null && plugin.monsters().data(target)
                            .map(data -> data.rarity() == tw.exilecore.monster.MonsterRarity.UNIQUE).orElse(false);
                    plugin.ailments().consume(target, tw.exilecore.ailment.AilmentType.FREEZE, origin.owner());
                    hit = profile.scaled(1 + (unique ? FINAL_SPIKE_FREEZE_MORE_UNIQUE : FINAL_SPIKE_FREEZE_MORE), "用掉凍結");
                }
                damage.hit(origin.owner(), target, hit, null);
            }
            targets.strike(origin.owner(), profile, SkillTargets.sphere(tip, radius), new Object());
            visuals.nova(build.definition().visual(), tip, radius);
        }
        final SkillStages.Stage serpent = SkillStages.get(build.definition().id(), "serpent");
        if (serpent != null && origin.self() && plugin.combat() != null && plugin.combat().charges() != null) {
            final Player player = origin.owner();
            final StatSheet effective = build.profile().effective(plugin.players().sheet(player));
            int serpents = plugin.skills() != null && plugin.skills().config().chargeCost(build.definition().id()) != null ? 1 : 0;
            for (int guard = 0; guard < 20; guard++) {
                final int spent = plugin.combat().charges().spend(player, "frenzy", effective, build.profile().tags());
                if (spent <= 0) {
                    break;
                }
                serpents += spent;
            }
            final SkillProfile profile = SkillStages.profile(build, build.profile(), "serpent");
            final Location from = origin.feet().clone();
            final org.bukkit.util.Vector direction = origin.direction().clone();
            final double angle = build.angle();
            for (int i = 1; i <= Math.min(10, serpents); i++) {
                plugin.getServer().getScheduler().runTaskLater(plugin, () -> {
                    if (!player.isOnline() || !player.getWorld().equals(from.getWorld())) {
                        return;
                    }
                    final Origin again = Origin.at(player, from, direction);
                    for (final LivingEntity target : targets.cone(again, length, angle)) {
                        damage.hit(player, target, profile, null);
                    }
                    visuals.cone(build.definition().visual(), again, length, angle);
                }, (long) SERPENT_GAP_TICKS * i);
            }
        }
        if (SkillStages.get(build.definition().id(), THUNDER) != null && origin.self() && plugin.combat() != null
                && plugin.combat().charges() != null) {
            thunderBolts(origin, build, facing, length);
        }
    }

    /** 2026-10-28 照 PoE2 Falling Thunder：投射物那一段（skills.yml 的 stages.thunder）、射出去的隱藏技能。 */
    static final String THUNDER = "thunder";
    static final String THUNDER_BOLT = "falling_thunder_bolt";
    /** 每用掉一顆銳星投射物更多 50% 傷害（品質再加，gem_quality.yml 的 falling_thunder）；有用掉銳星時再更多 100%。 */
    static final double THUNDER_MORE_PER_CHARGE = 50;
    static final double THUNDER_MORE_CHARGED = 100;
    /** 品質：每用掉一顆銳星再更多 x%（FLAT，加在 50% 上）。 */
    public static final String THUNDER_QUALITY = "falling_thunder.per_charge_more";
    /** 每顆銳星幾發（stages.thunder 沒寫 count 時）。 */
    static final int THUNDER_PER_CHARGE = 3;
    /** PoE2 active_skill_projectile_speed_+%_variation_final 50：每一發的速度各自 ±50%（往前散開）。 */
    static final double THUNDER_SPEED_VARIATION = 50;

    /** 驚雷劈這一下投射物的傷害倍率（純函式）：(1 + 每顆 x% × 顆數) ×（有用掉 → 2），沒用掉是 0。 */
    static double thunderMore(final int charges, final double perCharge) {
        if (charges <= 0) {
            return 0;
        }
        return (1 + perCharge / 100.0 * charges) * (1 + THUNDER_MORE_CHARGED / 100.0);
    }

    /** 驚雷劈幾發（純函式）：每顆 perCharge 發，再加輔助、裝備的投射物數量（extra，可以是負的）；沒有銳星 0 發。 */
    static int thunderCount(final int charges, final int perCharge, final int extra) {
        if (charges <= 0) {
            return 0;
        }
        return Math.max(1, Math.min(SkillBuild.MAX_PROJECTILES, perCharge * charges + extra));
    }

    /**
     * 2026-10-28 照 PoE2 Falling Thunder：你親自用的時候用掉身上所有的銳星（恆星、品質「機率不用掉」照樣算一顆、倍星算兩顆，
     * 最多照身上原本的顆數用），從落點往前射出閃電投射物：每顆 3 發、物理全部轉閃電、穿透所有目標、每顆更多 50%、有用掉再更多 100%；
     * 沒有銳星不射。投射物的數量、速度、分裂、連鎖照掛著的輔助算（輔助掛在投射物上也要能掛），傷害用這一下（含輔助、品質）。
     */
    private void thunderBolts(final Origin origin, final SkillBuild build, final org.bukkit.util.Vector facing, final double length) {
        final Player player = origin.owner();
        final StatSheet sheet = plugin.players().sheet(player);
        final StatSheet effective = build.profile().effective(sheet);
        final int held = plugin.combat().charges().count(player, "power");
        int consumed = 0;
        int benefit = 0;
        while (consumed < Math.min(20, held)) {
            final int got = plugin.combat().charges().spend(player, "power", effective, build.profile().tags());
            if (got <= 0) {
                break;
            }
            consumed++;
            benefit += got;
        }
        final tw.exilecore.character.PlayerState state = plugin.players().get(player);
        final SkillBuild bolt = plugin.skills() == null ? null
                : thunderBuild(plugin.skills().config(), build, benefit, state.level(), state.weaponTags(), sheet);
        if (bolt == null) {
            return; // PoE2：沒有銳星就沒有投射物
        }
        final int count = bolt.projectiles();
        // 落點：長棍劈到地上的地方（扇形一半的距離），從那裡往前（水平）射出去
        final Location impact = origin.feet().clone().add(facing.clone().multiply(Math.min(length, Math.max(1.0, length * 0.5))));
        projectiles.launch(Origin.at(player, impact.subtract(0, 0.4, 0), facing.clone()), bolt);
        state.notice(String.format(java.util.Locale.ROOT, "<#ffe066>⚡ %s：用掉 %d 顆銳星，射出 %d 發", bolt.definition().name(), consumed, count),
                plugin.getServer().getCurrentTick() + 30);
    }

    /**
     * 驚雷劈這一下（build）用掉 benefit 顆銳星時射出去的投射物（純資料，測試用）：隱藏技能 falling_thunder_bolt 帶上這一下的修飾器
     * （數量、速度、分裂、連鎖照輔助），傷害換成這一下的（含輔助、品質）再照投射物那一段換表、轉換、加上銳星的更多；
     * 標籤換成投射物（不是近戰、猛擊、範圍）。沒有銳星回傳 null。
     */
    static SkillBuild thunderBuild(final SkillConfig config, final SkillBuild build, final int benefit, final int characterLevel,
                                   final Set<String> weaponTags, final StatSheet sheet) {
        final SkillDefinition sub = config.skill(THUNDER_BOLT);
        final SkillStages.Stage stage = SkillStages.get(build.definition().id(), THUNDER);
        if (benefit <= 0 || sub == null || stage == null) {
            return null;
        }
        // 輔助、品質、技能自己的修飾器（這一下 profile 的 local）照樣算進投射物的數量、速度、分裂、連鎖（隱藏的攻擊不能直接掛輔助）
        final SkillBuild base = SkillBuild.of(config, sub, build.level(), characterLevel, false, List.of(), weaponTags, sheet, Set.of(),
                build.profile().local());
        final int perCharge = stage.count().intAt(build.level()) > 0 ? stage.count().intAt(build.level()) : THUNDER_PER_CHARGE;
        final int count = thunderCount(benefit, perCharge, base.projectiles() - sub.params().projectiles().intAt(build.level()));
        final double perChargeMore = THUNDER_MORE_PER_CHARGE + build.profile().effective(sheet).flat(THUNDER_QUALITY, build.profile().tags());
        final SkillProfile profile = SkillStages.profile(build, build.profile(), THUNDER)
                .derive(THUNDER_BOLT, Set.of(Tags.PROJECTILE), Set.of(Tags.MELEE, Tags.SLAM, Tags.AREA))
                .scaled(thunderMore(benefit, perChargeMore), "用掉銳星")
                .withLocal(List.of(tw.exilecore.stats.Modifier.flat(ProjectileEngine.SPEED_VARIATION, THUNDER_SPEED_VARIATION, sub.name())));
        return base.withProfile(profile).withAreaAndProjectiles(base.areaScale(), count);
    }

    // ---- 連鎖 ----

    /**
     * 2026-10-28 照 PoE2 Arc（RePoE 類型有 Projectile、base_is_projectile，沒有 projectiles_can_shotgun）：連鎖的閃電是投射物，
     * 多的投射物各放一道，照投射物的扇形（SkillBuild.spread，環射是一圈）排開，每道各自找自己方向上的敵人、各自連鎖；
     * 同一次施放的幾道不能打中同一個敵人（不能霰射：一開始的目標各挑不同的，連鎖也跳過別道打過的）。
     * 這次之前不管幾個投射物都只放一道（星辰萬象、分光掛上去「只有一發」）。
     */
    private void chain(final Origin origin, final SkillBuild build, final LivingEntity preferred) {
        final int beams = chainBeams(build.definition(), build.projectiles());
        if (beams <= 1) {
            chainBeam(origin, build, preferred, true, new HashSet<>());
            return;
        }
        final boolean circle = build.spread() >= 360 - 1e-9;
        final Vector look = circle ? origin.facing() : origin.direction();
        final List<double[]> directions = SkillMath.fan(look.getX(), look.getY(), look.getZ(), beams, build.spread());
        final int primary = primaryBeam(beams, circle);
        final Set<UUID> hit = new HashSet<>(); // 幾道共用：同一個敵人只會被這次施放的其中一道打到
        chainBeam(beamOrigin(origin, directions.get(primary)), build, preferred, true, hit);
        for (int i = 0; i < beams; i++) {
            if (i != primary) {
                chainBeam(beamOrigin(origin, directions.get(i)), build, null, false, hit);
            }
        }
    }

    /** 2026-10-28 照 PoE2：連鎖技能放幾道——有投射物標籤（鏈雷）的照投射物數量，其他（血脈牽、裂隙之手…原作不是投射物）一道。純函式。 */
    static int chainBeams(final SkillDefinition definition, final int projectiles) {
        return definition.tags().contains(Tags.PROJECTILE) ? Math.max(1, Math.min(SkillBuild.MAX_PROJECTILES, projectiles)) : 1;
    }

    /** 2026-10-28：幾道裡哪一道對準準心（打指定目標）：扇形取中間那道（偶數取偏左的中間），一圈取第一道（往前）。純函式。 */
    static int primaryBeam(final int beams, final boolean circle) {
        return circle || beams <= 1 ? 0 : (beams - 1) / 2;
    }

    private static Origin beamOrigin(final Origin origin, final double[] direction) {
        return new Origin(origin.owner(), origin.feet(), origin.eye(), new Vector(direction[0], direction[1], direction[2]), origin.self());
    }

    /** 一道連鎖的閃電：primary 這道先打指定的目標、再找準心指的，其他道只找自己方向上（40° 扇形內）還沒被打過的敵人；hit 是這次施放打過的。 */
    private void chainBeam(final Origin origin, final SkillBuild build, final LivingEntity preferred, final boolean primary, final Set<UUID> hit) {
        final double range = build.range();
        final Location eye = origin.eye();
        LivingEntity current = null;
        if (preferred != null && targets.valid(preferred, origin.owner()) && preferred.getLocation().distance(origin.feet()) <= range
                && !hit.contains(preferred.getUniqueId())) {
            current = preferred;
        }
        if (current == null && primary) {
            current = targets.aimed(origin, range, 1.0);
            if (current != null && hit.contains(current.getUniqueId())) {
                current = null;
            }
        }
        if (current == null) {
            for (final LivingEntity candidate : targets.cone(origin, range, 40)) {
                if (!hit.contains(candidate.getUniqueId())) {
                    current = candidate;
                    break;
                }
            }
        }
        final List<Location> points = new ArrayList<>();
        points.add(eye.clone().add(origin.direction().clone().multiply(0.6)).subtract(0, 0.3, 0));
        // 2026-10-28 照 PoE2 Lightning Rod：閃電的連鎖可以跳到自己插在地上的雷樁矢箭上（算一次連鎖、箭充能後再炸一次）
        final boolean rods = origin.owner() != null && SkillZones.chainsToRods(build.profile());
        LightningRods.Rod rod = null;
        if (current == null && rods) {
            rod = zones.aimedRod(origin.owner(), eye, origin.direction(), range, 40);
        }
        if (current == null && rod == null) {
            // 沒有目標：往前劈一道空的閃電
            final RayTraceResult ray = origin.world().rayTraceBlocks(eye, origin.direction(), range, FluidCollisionMode.NEVER, true);
            final Vector end = ray == null ? eye.toVector().add(origin.direction().clone().multiply(range)) : ray.getHitPosition();
            points.add(end.toLocation(origin.world()));
            visuals.chain(points);
            return;
        }
        // 2026-10-26 照 PoE2 天賦樹：「閃電技能 x% 機率多連鎖一次」
        final int chains = build.chains() + (origin.owner() == null || build.chains() <= 0 ? 0 : tw.exilecore.stats.TreeEffectKeys.fromChance(
                build.profile().effective(plugin.players().sheet(origin.owner())).flat(tw.exilecore.stats.TreeEffectKeys.CHAIN_CHANCE, build.profile().tags()),
                plugin.random()));
        final Set<LightningRods.Rod> rodsHit = new HashSet<>();
        for (int i = 0; i <= chains && (current != null || rod != null); i++) {
            final Location from;
            if (current != null) {
                from = SkillTargets.center(current);
                hit.add(current.getUniqueId());
                damage.hit(origin.owner(), current, build.profile(), null);
            } else {
                from = SkillZones.rodTop(rod.at.toLocation(origin.world()));
                rodsHit.add(rod);
                zones.chainToRod(origin.owner(), rod, build.profile());
            }
            points.add(from);
            if (i >= chains) {
                break;
            }
            final LivingEntity next = current != null ? targets.nearest(current, build.chainRange(), hit, origin.owner())
                    : targets.nearest(from, build.chainRange(), hit, origin.owner());
            final LightningRods.Rod nextRod = rods ? zones.nearestRod(origin.owner(), from, build.chainRange(), rodsHit) : null;
            // 近的先：敵人跟箭一樣近時打敵人
            if (nextRod != null && (next == null
                    || SkillZones.rodTop(nextRod.at.toLocation(origin.world())).distanceSquared(from) < SkillTargets.center(next).distanceSquared(from))) {
                current = null;
                rod = nextRod;
            } else {
                current = next;
                rod = null;
            }
        }
        visuals.chain(points);
    }

    // ---- 跳躍 ----

    private int leap(final Player player, final SkillRuntime runtime, final SkillBuild build, final long now) {
        final Location target = landing(player, build.range());
        if (target == null) {
            return 0;
        }
        final Location from = player.getLocation();
        final double dx = target.getX() - from.getX();
        final double dy = target.getY() - from.getY();
        final double dz = target.getZ() - from.getZ();
        final int ticks = SkillMath.leapTicks(Math.sqrt(dx * dx + dz * dz));
        final double[] velocity = SkillMath.leapVelocity(dx, dy, dz, ticks, SkillService.grounded(player));
        player.setFallDistance(0);
        player.setVelocity(new Vector(velocity[0], velocity[1], velocity[2]));
        runtime.leap = new SkillRuntime.Leap(now, ticks, build, target);
        visuals.leapStart(player);
        return ticks;
    }

    /** 跳躍落地：打周圍一圈。 */
    void land(final Player player, final SkillRuntime.Leap leap) {
        final double radius = leap.build().radius();
        final Location at = player.getLocation();
        for (final LivingEntity target : targets.around(at, radius, player)) {
            if (player.hasLineOfSight(target)) {
                damage.hit(player, target, leap.build().profile(), null);
            }
        }
        targets.strike(player, leap.build().profile(), SkillTargets.sphere(at, radius), new Object()); // 2026-10-25
        player.setFallDistance(0);
        visuals.leapLand(at, radius);
        uniqueAftershocks(player, leap.build(), at, radius); // 2026-10-26（鑄父之錘）
        if (plugin.skills() != null) {
            plugin.skills().supportMechanics().lineage().landed(player, leap.build(), at); // 2026-10-27 照 PoE2 燼躍：落地引爆屍體
        }
    }

    /**
     * 跳躍的落點：視線打到方塊就落在它前面，沒打到就往面向的方向跳 range 格；
     * 再從那裡往上 2 格、往下 6 格找站得住的地方。都沒有（例如前面是虛空）回傳 null。
     */
    Location landing(final Player player, final double range) {
        final Location eye = player.getEyeLocation();
        final Vector direction = eye.getDirection();
        final World world = player.getWorld();
        final RayTraceResult ray = world.rayTraceBlocks(eye, direction, range, FluidCollisionMode.NEVER, true);
        final Vector point;
        if (ray != null) {
            point = ray.getHitPosition().subtract(direction.clone().multiply(0.4));
        } else {
            point = player.getLocation().toVector().add(SkillTargets.facing(player).multiply(range));
        }
        final int x = point.getBlockX();
        final int z = point.getBlockZ();
        final int baseY = (int) Math.floor(point.getY());
        for (int dy = 2; dy >= -6; dy--) {
            final int y = baseY + dy;
            if (y - 1 < world.getMinHeight() || y + 1 >= world.getMaxHeight()) {
                continue;
            }
            if (standable(world, x, y, z)) {
                return new Location(world, x + 0.5, y, z + 0.5, player.getLocation().getYaw(), player.getLocation().getPitch());
            }
        }
        return null;
    }

    private static boolean standable(final World world, final int x, final int y, final int z) {
        final Block feet = world.getBlockAt(x, y, z);
        final Block below = feet.getRelative(0, -1, 0);
        final Block head = feet.getRelative(0, 1, 0);
        return below.getType().isSolid() && !Tag.LEAVES.isTagged(below.getType())
                && feet.isPassable() && !feet.isLiquid() && head.isPassable() && !head.isLiquid();
    }

    // ---- 位移 ----

    /** 位移的落點（沿著水平的面向走到被擋住為止）；走不出去回傳 null。 */
    Location dashDestination(final Player player, final double range) {
        return pathDestination(player, SkillTargets.facing(player), range, DashPath.STEP, 1.0);
    }

    /** 2026-10-28：同上，方向自己給（近戰條件位移朝目標走）、每一步走 step 格、至少要走 least 格（位移 1 格；條件位移可以很短，重錘 0.5 格）。 */
    private Location pathDestination(final Player player, final Vector facing, final double range, final double step, final double least) {
        return pathDestination(player, player.getLocation(), facing, range, step, least);
    }

    /** 2026-10-28：同上，從 from 出發（迴旋棍步一路走：每一 tick 從起點算走到第幾格的位置）。 */
    private Location pathDestination(final Player player, final Location from, final Vector facing, final double range, final double step,
                                     final double least) {
        final World world = player.getWorld();
        final DashPath.Blocks blocks = new DashPath.Blocks() {
            @Override
            public boolean passable(final int x, final int y, final int z) {
                if (y < world.getMinHeight() || y >= world.getMaxHeight()) {
                    return false;
                }
                final Block block = world.getBlockAt(x, y, z);
                return block.isPassable() && !block.isLiquid();
            }

            @Override
            public boolean solid(final int x, final int y, final int z) {
                if (y < world.getMinHeight() || y >= world.getMaxHeight()) {
                    return false;
                }
                final Block block = world.getBlockAt(x, y, z);
                return block.getType().isSolid() && !Tag.LEAVES.isTagged(block.getType());
            }
        };
        final double[] end = DashPath.destination(from.getX(), from.getY(), from.getZ(), facing.getX(), facing.getZ(), range, blocks, step);
        if (end == null) {
            return null;
        }
        final Location to = new Location(world, end[0], end[1], end[2], from.getYaw(), from.getPitch());
        return to.distanceSquared(from) < least * least ? null : to;
    }

    // ---- 近戰條件位移（2026-10-28 照 PoE2 melee_conditional_step_distance） ----

    /**
     * 2026-10-28 照 PoE2（{@link MeleeStep}）：出手前往前踏一步（技能打之前呼叫；踏完之後這一下、旋矛擊的旋風都在新的位置）。
     * 準心指著（或鎖定的）敵人在打得到的距離外就朝牠踏，走到打得到為止；沒有目標、範圍裡也沒有敵人就朝準心指的地面踏。
     * 沿路被牆擋住就停在牆前（{@link DashPath}）。被定住由呼叫端擋掉。回傳實際走了幾格（沒動是 0）。
     * 這次之前近戰技能都在原地打。
     */
    double meleeStep(final Player player, final SkillBuild build, final MeleeStep.Spec spec, final LivingEntity preferred) {
        final Location from = player.getLocation();
        final Origin origin = Origin.of(player);
        final double reach = MeleeStep.reach(build);
        LivingEntity target = preferred != null && preferred.getWorld() == player.getWorld() && targets.valid(preferred, player)
                && horizontal(from, preferred.getLocation()) <= MeleeStep.LOCK_RANGE ? preferred : null;
        if (target == null) {
            target = targets.aimed(origin, reach + spec.distance() + 1.5, 0.6);
        }
        final Vector direction;
        final double move;
        if (target != null) {
            direction = target.getLocation().toVector().subtract(from.toVector()).setY(0);
            move = MeleeStep.towardTarget(spec, horizontal(from, target.getLocation()) - target.getWidth() / 2, reach);
        } else {
            if (spec.conditional() && enemyInReach(origin, build, reach)) {
                return 0; // 這一下已經打得到敵人：不動
            }
            direction = SkillTargets.facing(player);
            move = MeleeStep.towardGround(spec, groundCursor(origin, from, spec.distance() + 1.5));
        }
        if (move <= 0 || direction.lengthSquared() < 1e-6) {
            return 0;
        }
        final Location to = pathDestination(player, direction.normalize(), move, DashPath.evenStep(move), MeleeStep.MIN_MOVE);
        if (to == null) {
            return 0;
        }
        player.setFallDistance(0);
        if (!tw.exilecore.core.Teleports.go(player, to)) {
            return 0;
        }
        visuals.dash(build.definition().visual(), from, to);
        return horizontal(from, to);
    }

    /** 這一下現在就打得到的範圍裡有沒有敵人（打擊、扇形看前方的扇形，新星看周圍一圈）。 */
    private boolean enemyInReach(final Origin origin, final SkillBuild build, final double reach) {
        if (build.definition().mechanic() == Mechanic.NOVA) {
            return !targets.around(origin.feet(), reach, origin.owner()).isEmpty();
        }
        return !targets.cone(origin, reach, build.angle() > 0 ? build.angle() : 60).isEmpty();
    }

    /** 準心指的地面離腳下多遠（水平；range 內沒指到方塊回傳無限遠）。 */
    private static double groundCursor(final Origin origin, final Location from, final double range) {
        final RayTraceResult ray = origin.world().rayTraceBlocks(origin.eye(), origin.direction(), range, FluidCollisionMode.NEVER, true);
        if (ray == null) {
            return Double.POSITIVE_INFINITY;
        }
        final Vector hit = ray.getHitPosition();
        return Math.hypot(hit.getX() - from.getX(), hit.getZ() - from.getZ());
    }

    private static double horizontal(final Location a, final Location b) {
        return Math.hypot(a.getX() - b.getX(), a.getZ() - b.getZ());
    }

    private void dash(final Player player, final SkillBuild original) {
        if (original.params().dash().whirls()) {
            whirl(player, original); // 2026-10-28 照 PoE2 Whirling Assault；這次之前瞬移 7 格、順路打一條線並擊退
            return;
        }
        final Location to = dashDestination(player, original.range());
        if (to == null) {
            return;
        }
        final Location from = player.getLocation();
        // 2026-10-28 照 PoE2 Rake（base_additional_damage_from_distance_+%_final）：照衝刺的距離最多更多 100% 傷害（品質再 +0～30%）；
        //   這次之前裂血掠沒有照距離加成
        final double distanceMore = dashDistanceMore(original, from.distance(to));
        final SkillBuild build = distanceMore > 0 ? original.withProfile(original.profile().scaled(1 + distanceMore, "衝刺距離")) : original;
        final SkillParams.Dash params = build.params().dash();
        final String visual = build.definition().visual();
        final Object strikeSource = new Object(); // 2026-10-25：起點、順路、終點同一下（鐘、冰晶只算一次）
        // 起點爆開
        final double startRadius = build.scaledRadius(params.startRadius());
        if (startRadius > 0) {
            targets.strike(player, build.profile(), SkillTargets.sphere(from, startRadius), strikeSource);
            for (final LivingEntity target : targets.around(from, startRadius, player)) {
                damage.hit(player, target, build.profile(), null);
            }
            visuals.nova(visual, from, startRadius);
        }
        // 順路打擊
        if (params.hitWidth() > 0) {
            targets.strike(player, build.profile(), SkillTargets.segment(from, to, params.hitWidth()), strikeSource);
            for (final LivingEntity target : targets.alongLine(from, to, params.hitWidth(), player)) {
                damage.hit(player, target, build.profile(), knockback(from, target, build.knockback()));
            }
        }
        // 沿路的地面效果
        final SkillParams.Zone ground = build.params().ground();
        if (ground != null) {
            final List<Location> points = new ArrayList<>();
            final double distance = from.distance(to);
            final int steps = Math.max(1, (int) Math.round(distance / 1.2));
            for (int i = 0; i <= steps; i++) {
                final Location point = from.clone().add(to.clone().subtract(from).toVector().multiply(i / (double) steps));
                point.setY(Math.floor(point.getY()) + 0.1);
                points.add(point);
            }
            zones.addPoints(player, points, build.scaledRadius(ground.radius()), build.profile().scaled(ground.damage() / 100.0, "地面效果"),
                    visual, build.durationTicks(ground.duration()), ground.interval(), ground.pull());
        }
        visuals.dash(visual, from, to);
        player.setFallDistance(0);
        tw.exilecore.core.Teleports.go(player, to);
        // 終點爆開
        final double endRadius = build.scaledRadius(params.endRadius());
        if (endRadius > 0) {
            // 2026-10-28 照 PoE2（奔雷踏）：終點的猛擊有自己的傷害表（SkillStages 的 slam）；這次之前跟沿路的踏步一樣
            final SkillProfile slam = SkillStages.profile(build, build.profile(), "slam");
            targets.strike(player, slam, SkillTargets.sphere(to, endRadius), strikeSource);
            for (final LivingEntity target : targets.around(to, endRadius, player)) {
                damage.hit(player, target, slam, null);
            }
            visuals.nova(visual, to, endRadius);
            uniqueAftershocks(player, build, slam, to, endRadius); // 2026-10-28 照 PoE2 Stampede 的品質（終點猛擊 x% 機率多一次餘震）
        }
        if (plugin.skills() != null) {
            plugin.skills().leftovers().stampede(player, build, from, to); // 2026-10-28 照 PoE2 Stampede：鋸齒地面、終點炸開
        }
    }

    /** 正在迴旋棍步的玩家 → 這一招的記號（新的一招蓋掉舊的，舊的那招剩下的步、圈就不做了）。 */
    private final java.util.Map<UUID, Object> whirling = new java.util.HashMap<>();

    /**
     * 2026-10-28 照 PoE2 0.5 Whirling Assault（{@link WhirlingAssault}）：整個動作時間（攻擊時間 + 1 秒）裡一路往前走 range 格
     * （{@link DashPath}：被牆擋住就停在牆前；一開始被釘住就原地轉），沿路轉 spins 圈，每一圈在當下的位置打一圈（各是一個打擊範圍、不擊退）。
     * 中途被凍結、重暈、電擊就整招停下；被釘住只是不再往前走。這次之前（dash）瞬移 7 格、順路打一條寬 1.4 格的線並擊退 0.3。
     */
    private void whirl(final Player player, final SkillBuild build) {
        final SkillParams.Dash params = build.params().dash();
        final Location from = player.getLocation();
        final boolean pinned = plugin.skills() != null && plugin.skills().supportMechanics().immobilised(player);
        final Vector facing = SkillTargets.facing(player);
        final Location found = pinned ? null : pathDestination(player, facing, build.range(), DashPath.evenStep(build.range()), 0);
        final double distance = found == null ? 0 : horizontal(from, found);
        final int total = Math.max(WhirlingAssault.MIN_TICKS, (int) Math.round(build.intervalTicks(plugin.players().sheet(player))));
        final double radius = params.spinRadius() * build.areaScale();
        final String visual = build.definition().visual();
        final Object token = new Object();
        whirling.put(player.getUniqueId(), token);
        final int spins = params.spins();
        final int[] nextSpin = {0};
        final Location[] last = {from.clone()};
        final boolean[] moving = {distance > 1e-3};
        final int[] tick = {0};
        player.setFallDistance(0);
        final org.bukkit.scheduler.BukkitTask[] task = new org.bukkit.scheduler.BukkitTask[1];
        task[0] = plugin.getServer().getScheduler().runTaskTimer(plugin, () -> {
            tick[0]++;
            if (whirling.get(player.getUniqueId()) != token || !player.isOnline() || player.isDead()
                    || player.getWorld() != from.getWorld()
                    || plugin.ailments() != null && plugin.ailments().isIncapacitated(player)) {
                whirling.remove(player.getUniqueId(), token);
                task[0].cancel();
                return;
            }
            if (moving[0]) {
                final Location now = player.getLocation();
                if (now.distanceSquared(last[0]) > 2.25
                        || plugin.skills() != null && plugin.skills().supportMechanics().immobilised(player)) {
                    moving[0] = false; // 被別的東西挪走了、或被釘住：不再往前走，照樣轉
                } else {
                    final double travel = distance * WhirlingAssault.progress(tick[0], total);
                    final Location point = travel < 1e-3 ? null
                            : pathDestination(player, from, facing, travel, DashPath.evenStep(travel), 0);
                    final Location step = point == null ? last[0].clone() : point; // 照 DashPath 走（高低、牆都跟落點算法一樣）
                    step.setYaw(now.getYaw());
                    step.setPitch(now.getPitch());
                    player.setFallDistance(0);
                    if (tw.exilecore.core.Teleports.go(player, step)) {
                        last[0] = step;
                    } else {
                        moving[0] = false;
                    }
                }
            }
            while (nextSpin[0] < spins && tick[0] >= WhirlingAssault.spinTick(nextSpin[0], spins, total)) {
                nextSpin[0]++;
                final Location at = player.getLocation();
                targets.strike(player, build.profile(), SkillTargets.sphere(at, radius), new Object()); // 每一圈各是一個打擊範圍
                for (final LivingEntity target : targets.around(at, radius, player)) {
                    damage.hit(player, target, build.profile(), null);
                }
                visuals.nova(visual, at, radius);
            }
            if (tick[0] >= total) {
                whirling.remove(player.getUniqueId(), token);
                task[0].cancel();
            }
        }, 1L, 1L);
    }

    /** 2026-10-28：衝刺了 distance 格，照距離更多幾（小數）：技能自己寫了 charge.distance_more 才有（裂血掠），品質的另外加。 */
    static double dashDistanceMore(final SkillBuild build, final double distance) {
        double base = 0;
        for (final tw.exilecore.stats.Modifier modifier : build.definition().modifiers()) {
            if (modifier.stat().equals(tw.exilecore.stats.GemQualityKeys.DISTANCE_MORE)) {
                base += modifier.value();
            }
        }
        if (base <= 0 || build.range() <= 0) {
            return 0;
        }
        return (base + build.qualityFlat(tw.exilecore.stats.GemQualityKeys.DISTANCE_MORE)) / 100.0 * Math.max(0, Math.min(1, distance / build.range()));
    }

    /**
     * 2026-10-26 照 PoE2 的引導衝鋒（盾衝撞上敵人、蠻衝一路踏地）：在 at 炸一圈（照 profile 的傷害），回傳打到的敵人。
     */
    List<LivingEntity> burst(final Player player, final SkillProfile profile, final Location at, final double radius, final double knockback,
                             final String visual) {
        targets.strike(player, profile, SkillTargets.sphere(at, radius), new Object());
        final List<LivingEntity> hit = targets.around(at, radius, player);
        for (final LivingEntity target : hit) {
            damage.hit(player, target, profile, knockback(at, target, knockback));
        }
        visuals.nova(visual, at, radius);
        return hit;
    }

    /** 2026-10-26：只打這一隻（盾衝撞上的那一隻另外挨的一下）。 */
    void hitOne(final Player player, final SkillProfile profile, final LivingEntity target, final Location from, final double knockback) {
        damage.hit(player, target, profile, knockback(from, target, knockback));
    }

    /** 蝕咒裂：找詛咒的範圍（PoE2 2.8 格）、一次最多幾個爆炸。 */
    static final double HEXBLAST_SEARCH = 2.8;
    static final int HEXBLAST_MAX = 10;

    /**
     * 2026-10-26 照 PoE2 Hexblast：準心處 2.8 格內每一隻被你詛咒的敵人，拿掉牠的詛咒、在牠身上炸開一圈混沌（技能的半徑、傷害），
     * 一次最多 10 個；沒有詛咒就沒有傷害。回傳炸了幾個。（原作只引爆過了一半時間的詛咒：我們沒記詛咒的開始時間，任何詛咒都算）
     */
    int hexblast(final Player player, final SkillBuild build, final LivingEntity preferred) {
        if (plugin.combat() == null) {
            return 0;
        }
        final Origin origin = Origin.of(player);
        final LivingEntity aimed = preferred != null && targets.valid(preferred, player) ? preferred : targets.aimed(origin, build.range(), 1.2);
        final Location center = aimed != null ? aimed.getLocation() : targets.aimPoint(origin, build.range());
        final long now = plugin.getServer().getCurrentTick();
        final double radius = build.radius();
        int blasts = 0;
        for (final LivingEntity cursed : targets.around(center, HEXBLAST_SEARCH * Math.sqrt(Math.max(0.1, build.areaScale())), player)) {
            if (blasts >= HEXBLAST_MAX) {
                break;
            }
            boolean mine = false;
            long remaining = 0;
            for (final Debuffs.Debuff debuff : plugin.combat().debuffs().active(cursed.getUniqueId(), now)) {
                if (debuff.id().contains("_curse") && player.getUniqueId().equals(debuff.source())) {
                    mine = true;
                    remaining = Math.max(remaining, debuff.untilTick() - now);
                }
            }
            if (!mine) {
                continue;
            }
            plugin.combat().debuffs().removeCurses(cursed.getUniqueId());
            blasts++;
            final Location at = cursed.getLocation();
            // 2026-10-28 照 PoE2 Hexblast 的品質：被移除的詛咒每剩 1 秒更多 (0—10)% 傷害
            final SkillProfile blast = hexblastProfile(build, remaining);
            targets.strike(player, blast, SkillTargets.sphere(at, radius), new Object());
            for (final LivingEntity victim : targets.around(at, radius, player)) {
                damage.hit(player, victim, blast, null);
            }
            visuals.nova(build.definition().visual(), at, radius);
        }
        return blasts;
    }

    /** 2026-10-28：蝕咒裂這一下的傷害：詛咒還剩 remainingTicks（每剩整 1 秒更多品質的 x%）。 */
    static SkillProfile hexblastProfile(final SkillBuild build, final long remainingTicks) {
        final double per = build.qualityFlat(tw.exilecore.stats.GemQualityKeys.MORE_PER_CURSE_SECOND);
        final long seconds = Math.max(0, remainingTicks) / 20;
        return per > 0 && seconds > 0 ? build.profile().scaled(1 + per / 100.0 * seconds, "詛咒剩餘時間") : build.profile();
    }

    /** 稜光裂：脈衝的範圍（PoE2 5 格）、最多用掉幾隻的異常、冰爆與雷爆是火爆的幾倍（PoE2 350% ÷ 125%、140% ÷ 125%）。 */
    static final double SUNDER_PULSE = 5;
    static final int SUNDER_MAX = 20;
    static final double SUNDER_COLD = 2.8;
    static final double SUNDER_LIGHTNING = 1.12;

    /**
     * 2026-10-26 照 PoE2 Elemental Sundering：身邊 5 格的脈衝（不打人）用掉最多 20 隻敵人身上的冰凍、感電、點燃，
     * 每用掉一個在那隻身上炸開那種元素（技能的半徑；火的照技能的表、冰 2.8 倍、雷 1.12 倍，全部轉成那種元素、不會造成異常）。
     * 沒有異常就沒有傷害。回傳炸了幾個。（這次之前是準心處隨機一種元素的爆炸）
     */
    int sunder(final Player player, final SkillBuild build) {
        if (plugin.ailments() == null) {
            return 0;
        }
        final Location center = player.getLocation();
        final double radius = build.radius();
        int blasts = 0;
        int consumed = 0;
        visuals.nova(build.definition().visual(), center, SUNDER_PULSE * Math.sqrt(Math.max(0.1, build.areaScale())));
        for (final LivingEntity enemy : targets.around(center, SUNDER_PULSE * Math.sqrt(Math.max(0.1, build.areaScale())), player)) {
            if (consumed >= SUNDER_MAX) {
                break;
            }
            boolean any = false;
            for (final tw.exilecore.ailment.AilmentType type : new tw.exilecore.ailment.AilmentType[] {
                    tw.exilecore.ailment.AilmentType.IGNITE, tw.exilecore.ailment.AilmentType.FREEZE, tw.exilecore.ailment.AilmentType.SHOCK}) {
                if (!plugin.ailments().has(enemy, type)) {
                    continue;
                }
                plugin.ailments().consume(enemy, type, player); // 2026-10-27 無限循環：感電可能重新施加
                any = true;
                blasts++;
                final tw.exilecore.stats.DamageType element = switch (type) {
                    case FREEZE -> tw.exilecore.stats.DamageType.COLD;
                    case SHOCK -> tw.exilecore.stats.DamageType.LIGHTNING;
                    default -> tw.exilecore.stats.DamageType.FIRE;
                };
                // 2026-10-28 照 PoE2：冰爆、雷爆照自己的逐級表（SkillStages 的 cold、lightning；1 級 350%、140%，40 級 6334%、2534%）；
                // 這次之前固定是火爆的 2.8 倍、1.12 倍（20 級的比例）
                final SkillStages.Stage own = SkillStages.get(build.definition().id(),
                        type == tw.exilecore.ailment.AilmentType.FREEZE ? "cold" : type == tw.exilecore.ailment.AilmentType.SHOCK ? "lightning" : "fire");
                final double scale = own != null ? SkillStages.share(build.definition(), own, build.level())
                        : type == tw.exilecore.ailment.AilmentType.FREEZE ? SUNDER_COLD
                        : type == tw.exilecore.ailment.AilmentType.SHOCK ? SUNDER_LIGHTNING : 1.0;
                final SkillProfile base = build.profile();
                final SkillProfile blast = new SkillProfile(base.id() + "." + element.name().toLowerCase(java.util.Locale.ROOT), base.tags(),
                        base.effectiveness(), base.spellDamage(), base.spellDamageMax(), java.util.Map.of(element, 1.0), base.local())
                        .scaled(scale, build.definition().name())
                        .withLocal(List.of(tw.exilecore.stats.Modifier.more(StatKeys.IGNITE_CHANCE, -1.0, "稜光裂"),
                                tw.exilecore.stats.Modifier.more(StatKeys.FREEZE_BUILDUP, -1.0, "稜光裂"),
                                tw.exilecore.stats.Modifier.more(StatKeys.SHOCK_CHANCE, -1.0, "稜光裂")));
                final Location at = enemy.getLocation();
                for (final LivingEntity victim : targets.around(at, radius, player)) {
                    damage.hit(player, victim, blast, null);
                }
                visuals.nova(build.definition().visual(), at, radius);
            }
            if (any) {
                consumed++;
            }
        }
        return blasts;
    }

    // ---- 瞬影 ----

    /** 瞬影擊要打誰、瞬移到哪裡（已經轉好面向目標）。 */
    record FlickerPlan(LivingEntity target, Location destination) {
    }

    /** 最多看幾個看得到的候選目標（找不到落點才往下看；太多會一直打射線）。 */
    private static final int FLICKER_CANDIDATES = 8;
    /** 找不到目標或落點時，幾 tick 內直接回同一個原因（按住按鍵時不要每 tick 重算一次）。 */
    private static final int FLICKER_MISS_TICKS = 4;
    /** 站上去會出事的方塊：傳送門、火、蜘蛛網、細雪、甜莓叢、凋零玫瑰（液體另外擋）。 */
    private static final Set<org.bukkit.Material> FLICKER_HAZARDS = java.util.EnumSet.of(org.bukkit.Material.NETHER_PORTAL,
            org.bukkit.Material.END_PORTAL, org.bukkit.Material.END_GATEWAY, org.bukkit.Material.FIRE, org.bukkit.Material.SOUL_FIRE,
            org.bukkit.Material.COBWEB, org.bukkit.Material.POWDER_SNOW, org.bukkit.Material.SWEET_BERRY_BUSH,
            org.bukkit.Material.WITHER_ROSE);
    /** 腳下不能是這些（站著會燙）。 */
    private static final Set<org.bukkit.Material> FLICKER_BAD_GROUND = java.util.EnumSet.of(org.bukkit.Material.MAGMA_BLOCK,
            org.bukkit.Material.CAMPFIRE, org.bukkit.Material.SOUL_CAMPFIRE);

    /** 同一個 tick 裡施放前檢查算好的落點（真正施放時直接用，不用再算一次）。 */
    private UUID planFor;
    private long planTick = Long.MIN_VALUE;
    private FlickerPlan plan;
    /** 剛剛找不到的人：到哪個 tick、原因。 */
    private final java.util.Map<UUID, Miss> misses = new java.util.HashMap<>();

    private record Miss(long until, String reason) {
    }

    /** 施放前檢查：找得到目標與落點回傳 null（並記下來給這個 tick 的施放用），不行回傳原因。 */
    private String flickerCheck(final Player player, final SkillBuild build, final LivingEntity preferred) {
        final long now = plugin.getServer().getCurrentTick();
        final Miss miss = misses.get(player.getUniqueId());
        if (miss != null && now < miss.until()) {
            return miss.reason();
        }
        final List<LivingEntity> candidates = flickerCandidates(player, build, preferred);
        final FlickerPlan found = flickerPlan(player, candidates);
        if (found == null) {
            final String reason = candidates.isEmpty()
                    ? String.format(java.util.Locale.ROOT, "%.0f 格內沒有看得到的敵人", build.range())
                    : "敵人旁邊沒有落腳的地方";
            if (misses.size() > 256) {
                misses.values().removeIf(old -> old.until() <= now);
            }
            misses.put(player.getUniqueId(), new Miss(now + FLICKER_MISS_TICKS, reason));
            return reason;
        }
        misses.remove(player.getUniqueId());
        planFor = player.getUniqueId();
        planTick = now;
        plan = found;
        return null;
    }

    /**
     * 瞬影擊的候選目標，照順序：排隊時點到的敵人、準心指的敵人、range 內由近到遠的敵人；
     * 都要看得到（不穿牆），最多 {@link #FLICKER_CANDIDATES} 個（看不到的不算數）。
     */
    List<LivingEntity> flickerCandidates(final Player player, final SkillBuild build, final LivingEntity preferred) {
        return dashCandidates(player, build.range(), preferred);
    }

    /** 同上，range 自己給（2026-10-28：斷魂掌的衝刺距離）。 */
    private List<LivingEntity> dashCandidates(final Player player, final double range, final LivingEntity preferred) {
        final Location feet = player.getLocation();
        final List<LivingEntity> list = new ArrayList<>();
        if (preferred != null && targets.valid(preferred, player) && preferred.getWorld().equals(feet.getWorld())
                && preferred.getLocation().distanceSquared(feet) <= (range + 1.5) * (range + 1.5) && player.hasLineOfSight(preferred)) {
            list.add(preferred);
        }
        final LivingEntity aimed = targets.aimed(Origin.of(player), range, 0.8);
        if (aimed != null && !list.contains(aimed) && player.hasLineOfSight(aimed)) {
            list.add(aimed);
        }
        for (final LivingEntity near : targets.around(feet, range, player)) {
            if (list.size() >= FLICKER_CANDIDATES) {
                break;
            }
            if (!list.contains(near) && player.hasLineOfSight(near)) {
                list.add(near);
            }
        }
        return list;
    }

    /** 第一個找得到落點的候選目標；都沒有回傳 null。 */
    private FlickerPlan flickerPlan(final Player player, final List<LivingEntity> candidates) {
        for (final LivingEntity target : candidates) {
            final Location spot = flickerSpot(player, target);
            if (spot != null) {
                return new FlickerPlan(target, spot);
            }
        }
        return null;
    }

    /**
     * 目標旁邊的落點（{@link SkillMath#flickerSpots}：面向玩家那一側優先）：腳下有地可以站（踩在那一格的頂面上，
     * 半磚、地毯也行；岩漿塊、營火不行），玩家的碰撞箱放得下（方塊、船、世界邊界都不能擋）、
     * 碰到的格子沒有液體、傳送門、火、蜘蛛網這些，而且從那裡看得到目標（不會隔著牆）。
     * 高度先試目標腳下那層，再上一格、下一格、下兩格。面向轉向目標。
     */
    private Location flickerSpot(final Player player, final LivingEntity target) {
        final World world = target.getWorld();
        final Location at = target.getLocation();
        final org.bukkit.util.BoundingBox box = target.getBoundingBox();
        final double halfWidth = Math.max(box.getWidthX(), box.getWidthZ()) / 2;
        final Location from = player.getLocation();
        final Vector aim = SkillTargets.center(target).toVector();
        final int baseY = (int) Math.floor(at.getY() + 0.01);
        for (final double[] spot : SkillMath.flickerSpots(from.getX(), from.getZ(), at.getX(), at.getZ(), halfWidth)) {
            for (final int dy : new int[] {0, 1, -1, -2}) {
                final int y = baseY + dy;
                if (y - 1 < world.getMinHeight() || y + 2 >= world.getMaxHeight()) {
                    continue;
                }
                final double feet = standHeight(world, spot[0], y, spot[1]);
                if (Double.isNaN(feet)) {
                    continue;
                }
                final Vector eye = new Vector(spot[0], feet + 1.62, spot[1]);
                final Vector look = aim.clone().subtract(eye);
                final double distance = look.length();
                if (distance > 1e-6 && world.rayTraceBlocks(eye.toLocation(world), look.clone().multiply(1 / distance), distance,
                        FluidCollisionMode.NEVER, true) != null) {
                    continue; // 隔著牆（例如目標在一格寬的走道裡，旁邊那格在牆的另一邊）
                }
                final Location to = new Location(world, spot[0], feet, spot[1]);
                if (distance > 1e-6) {
                    to.setDirection(look);
                } else {
                    to.setYaw(from.getYaw());
                    to.setPitch(from.getPitch());
                }
                return to;
            }
        }
        return null;
    }

    /**
     * 玩家站在 (x, ?, z)、腳下是第 y − 1 格時腳底的高度（那一格的頂面：整格是 y、半磚 y − 0.5、地毯 y − 15/16）；
     * 站不了回傳 NaN：沒有地（空氣、草、花、液體、樹葉）、燙腳的地、碰撞箱塞不下、碰到液體或危險的方塊。
     */
    private static double standHeight(final World world, final double x, final int y, final double z) {
        final Block below = world.getBlockAt((int) Math.floor(x), y - 1, (int) Math.floor(z));
        if (below.isPassable() || below.isLiquid() || Tag.LEAVES.isTagged(below.getType()) || FLICKER_BAD_GROUND.contains(below.getType())) {
            return Double.NaN;
        }
        final double feet = below.getBoundingBox().getMaxY();
        if (feet < y - 1 + 1e-3 || feet > y + 1e-3) {
            return Double.NaN; // 比一格高的（柵欄、牆）或怪形狀：不站
        }
        final org.bukkit.util.BoundingBox body = new org.bukkit.util.BoundingBox(x - 0.3, feet + 1e-3, z - 0.3, x + 0.3, feet + 1.8, z + 0.3);
        if (world.hasCollisionsIn(body)) {
            return Double.NaN;
        }
        for (int bx = (int) Math.floor(body.getMinX()); bx <= (int) Math.floor(body.getMaxX()); bx++) {
            for (int bz = (int) Math.floor(body.getMinZ()); bz <= (int) Math.floor(body.getMaxZ()); bz++) {
                for (int by = (int) Math.floor(body.getMinY()); by <= (int) Math.floor(body.getMaxY()); by++) {
                    final Block block = world.getBlockAt(bx, by, bz);
                    if (block.isLiquid() || FLICKER_HAZARDS.contains(block.getType())) {
                        return Double.NaN;
                    }
                }
            }
        }
        return feet;
    }

    /**
     * 複擊的瞬影擊再閃一次（2026-10）：原本的目標還在、看得到就再打它，不然打準心指的或最近的。
     * 找不到敵人或落點回傳 false（剩下的不閃了）。
     */
    boolean flickerAgain(final Player player, final SkillBuild build, final LivingEntity preferred) {
        final FlickerPlan found = flickerPlan(player, flickerCandidates(player, build, preferred));
        if (found == null) {
            return false;
        }
        planFor = player.getUniqueId();
        planTick = plugin.getServer().getCurrentTick();
        plan = found;
        flicker(player, build, preferred);
        return true;
    }

    /**
     * 瞬影擊：瞬移到目標旁邊（已經貼著就不動）、轉向它，打一下（跟近戰打擊一樣，那個目標優先）。
     * 傳送被別的插件擋掉就不打（不然會從原地打空）。
     */
    private void flicker(final Player player, final SkillBuild build, final LivingEntity preferred) {
        final long now = plugin.getServer().getCurrentTick();
        FlickerPlan chosen = player.getUniqueId().equals(planFor) && planTick == now ? plan : null;
        planFor = null;
        plan = null;
        if (chosen == null || !targets.valid(chosen.target(), player)) {
            chosen = flickerPlan(player, flickerCandidates(player, build, preferred));
        }
        if (chosen == null) {
            return;
        }
        final Location from = player.getLocation();
        final Location to = chosen.destination();
        if (to.distanceSquared(from) >= SkillMath.FLICKER_MIN_MOVE * SkillMath.FLICKER_MIN_MOVE) {
            player.setFallDistance(0);
            player.setVelocity(new Vector());
            if (!tw.exilecore.core.Teleports.go(player, to)) {
                return;
            }
            visuals.dash(build.definition().visual(), from, to);
        }
        strike(Origin.of(player), build, chosen.target());
        // 2026-10-24（照 PoE2）：每顆銳星多瞬移打 2 下（優先打還沒打過的），出手速度更多 285%；連打期間不能拿銳星。
        // 銳星是要打下一下時才用（附近沒有敵人就不用掉，照 PoE2「沒有額外攻擊就不消耗」）
        final var charges = plugin.combat() == null ? null : plugin.combat().charges();
        if (charges == null || charges.count(player, "power") <= 0) {
            return;
        }
        final long step = Math.max(2, Math.round(build.intervalTicks(plugin.players().sheet(player)) / (1 + FLICKER_MORE_SPEED)));
        final java.util.Set<UUID> hit = new java.util.HashSet<>();
        hit.add(chosen.target().getUniqueId());
        charges.blockPower(player, now + step * FLICKER_PER_CHARGE + 5);
        flickerChain(player, build, hit, 0, step, 0);
    }

    /** 每顆銳星多瞬移打幾下。 */
    static final int FLICKER_PER_CHARGE = 2;
    /** 連打的出手速度更多（2.85 = 285%）。 */
    static final double FLICKER_MORE_SPEED = 2.85;
    /** 保險：一次瞬影擊最多連打幾下（銳星上限再高也不會無限連）。 */
    static final int FLICKER_MAX_CHAIN = 40;

    /**
     * 瞬影擊的連打：{@code paid} 是用掉的銳星還能打幾下；用完了、身上還有銳星就再用一顆（先確認有敵人可以打）。
     * 每一下之間隔 step tick；連打期間一直延長「不能拿銳星」。
     */
    private void flickerChain(final Player player, final SkillBuild build, final java.util.Set<UUID> hit, final int paid, final long step, final int done) {
        plugin.getServer().getScheduler().runTaskLater(plugin, () -> {
            final var charges = plugin.combat() == null ? null : plugin.combat().charges();
            if (charges == null || !player.isOnline() || player.isDead() || done >= FLICKER_MAX_CHAIN) {
                return;
            }
            final List<LivingEntity> candidates = new ArrayList<>(flickerCandidates(player, build, null));
            candidates.sort((a, b) -> Boolean.compare(hit.contains(a.getUniqueId()), hit.contains(b.getUniqueId()))); // 沒打過的優先
            final FlickerPlan next = flickerPlan(player, candidates);
            if (next == null) {
                return; // 附近沒有敵人：連打結束（銳星沒用掉的留著）
            }
            int left = paid;
            if (left <= 0) {
                // 戰星輔助：恆星不扣、倍星這顆打 4 下；抑星不消耗（就不連打）
                final int benefit = charges.spend(player, "power", build.profile().effective(plugin.players().sheet(player)), build.profile().tags());
                if (benefit <= 0) {
                    return; // 銳星用完了
                }
                left = FLICKER_PER_CHARGE * benefit;
            }
            final Location from = player.getLocation();
            final Location to = next.destination();
            if (to.distanceSquared(from) >= SkillMath.FLICKER_MIN_MOVE * SkillMath.FLICKER_MIN_MOVE) {
                player.setFallDistance(0);
                player.setVelocity(new Vector());
                if (!tw.exilecore.core.Teleports.go(player, to)) {
                    return;
                }
                visuals.dash(build.definition().visual(), from, to);
            }
            hit.add(next.target().getUniqueId());
            strike(Origin.of(player), build, next.target());
            charges.blockPower(player, plugin.getServer().getCurrentTick() + step + 5);
            flickerChain(player, build, hit, left - 1, step, done + 1);
        }, step);
    }

    // ---- 斷魂掌 ----

    /** 斷魂掌連衝中的玩家 → 下一掌排在哪個 tick（連衝結束就拿掉）。 */
    private final java.util.Map<UUID, Long> palmPending = new java.util.HashMap<>();

    /**
     * 2026-10-28 照 PoE2 Killing Palm（見 {@link KillingPalm}）：衝向 6.5 格內的敵人（打得到就不動）打一掌；
     * 那一掌撲殺了目標，就往附近隨機一隻可以撲殺的敵人再衝過去打（一直撲殺得到就一直連）。這次之前原地打一下。
     */
    private void palm(final Player player, final SkillRuntime runtime, final SkillBuild build, final LivingEntity preferred) {
        final double reach = build.reach();
        LivingEntity target = null;
        Location spot = null;
        for (final LivingEntity candidate : dashCandidates(player, KillingPalm.DASH_RANGE, preferred)) {
            if (candidate.getLocation().distanceSquared(player.getLocation()) <= reach * reach) {
                target = candidate; // 已經打得到：不用衝
                break;
            }
            spot = flickerSpot(player, candidate);
            if (spot != null) {
                target = candidate;
                break;
            }
        }
        if (target == null) {
            strike(Origin.of(player), build, preferred); // 附近沒有敵人：原地打（跟原作一樣可以空揮）
            return;
        }
        if (!palmDash(player, build, spot)) {
            return;
        }
        strike(Origin.of(player), build, target);
        if (KillingPalm.culled(player.getUniqueId(), target.getUniqueId(), plugin.getServer().getCurrentTick())) {
            final Set<UUID> hit = new java.util.HashSet<>();
            hit.add(target.getUniqueId());
            palmChain(player, runtime, build, hit);
        }
    }

    /** 複擊（裂虛回響）的斷魂掌：算玩家自己再打一下，照樣衝過去、撲殺就連衝（這次之前複擊的那一下原地打）。 */
    void palmAgain(final Player player, final SkillRuntime runtime, final SkillBuild build, final LivingEntity preferred) {
        palm(player, runtime, build, preferred);
    }

    /** 這個玩家的斷魂掌還在連衝（下一掌還沒打）：複擊的那一下等連衝打完再打。 */
    boolean palmChaining(final Player player, final long now) {
        final Long next = palmPending.get(player.getUniqueId());
        return next != null && next >= now;
    }

    /** 衝到落點（null = 不用動）；傳送被擋掉回傳 false。 */
    private boolean palmDash(final Player player, final SkillBuild build, final Location to) {
        if (to == null) {
            return true;
        }
        final Location from = player.getLocation();
        if (to.distanceSquared(from) < SkillMath.FLICKER_MIN_MOVE * SkillMath.FLICKER_MIN_MOVE) {
            return true;
        }
        player.setFallDistance(0);
        player.setVelocity(new Vector());
        if (!tw.exilecore.core.Teleports.go(player, to)) {
            return false;
        }
        visuals.dash(build.definition().visual(), from, to);
        return true;
    }

    /**
     * 斷魂掌的連擊：隔一次攻擊時間（{@link KillingPalm#chainStepTicks}，吃攻擊速度），隨機挑一隻 6.5 格內看得到、可以撲殺、
     * 還沒打過的敵人衝過去打；那一掌又撲殺就再排下一掌。2026-10-28 照 PoE2：沒有上限（這次之前隔 0.2 秒、最多 40 掌）——
     * 每一掌都要撲殺（打死）一隻 {@code hit} 裡沒有的敵人才會再連，同一隻不會打兩次，連的次數最多就是附近的敵人數。
     * 連衝期間把 {@code runtime.readyAt} 往後推（每一掌都是一次攻擊，打完才能做別的事），跟原作一樣不能邊連衝邊放別的技能。
     */
    private void palmChain(final Player player, final SkillRuntime runtime, final SkillBuild build, final Set<UUID> hit) {
        final long step = KillingPalm.chainStepTicks(build.intervalTicks(plugin.players().sheet(player)));
        final long at = plugin.getServer().getCurrentTick() + step;
        palmPending.put(player.getUniqueId(), at);
        if (runtime != null) {
            runtime.readyAt = Math.max(runtime.readyAt, at + 1); // 下一掌打之前不能做別的
        }
        plugin.getServer().getScheduler().runTaskLater(plugin, () -> {
            palmPending.remove(player.getUniqueId(), at);
            if (!player.isOnline() || player.isDead()
                    || plugin.ailments() != null && plugin.ailments().isIncapacitated(player)) {
                return;
            }
            final List<LivingEntity> candidates = new ArrayList<>();
            for (final LivingEntity near : targets.around(player.getLocation(), KillingPalm.DASH_RANGE, player)) {
                if (!hit.contains(near.getUniqueId()) && zones.cullable(near) && player.hasLineOfSight(near)) {
                    candidates.add(near);
                }
            }
            KillingPalm.shuffle(candidates, java.util.concurrent.ThreadLocalRandom.current());
            for (final LivingEntity next : candidates) {
                final boolean inReach = next.getLocation().distanceSquared(player.getLocation()) <= build.reach() * build.reach();
                final Location spot = inReach ? null : flickerSpot(player, next);
                if (!inReach && spot == null) {
                    continue;
                }
                if (!palmDash(player, build, spot)) {
                    return;
                }
                hit.add(next.getUniqueId());
                final long now = plugin.getServer().getCurrentTick();
                if (runtime != null) {
                    runtime.readyAt = Math.max(runtime.readyAt, now + step); // 這一掌也是一次攻擊：打完才能做別的
                }
                ServerSwing.mainHand(player);
                strike(Origin.of(player), build, next);
                if (KillingPalm.culled(player.getUniqueId(), next.getUniqueId(), now)) {
                    palmChain(player, runtime, build, hit);
                }
                return;
            }
        }, step);
    }

    // ---- 延遲落下、地面效果、印記 ----

    /**
     * 2026-10-26（PoE2 的 Lightning Warp）：瞄準的敵人腳下留 8 秒感電地面；生命已經低到可以撲殺 → 瞬移進去炸開（撲殺它、炸周圍 3 格）；
     * 還沒低到 → 掛 4 秒標記，期間掉到門檻就自動瞬移過去。
     * 2026-10-28 照 PoE2 0.5：也能瞄準自己的滾雷珠（瞬移過去、雷珠在爆炸裡消失、照樣給閃電灌注、不留感電地面）；
     * 可以撲殺的敵人、滾雷珠比還不能撲殺的敵人優先（見 {@link LightningWarp}）。這次之前只看準心第一個碰到的敵人。
     */
    private void warp(final Player player, final SkillBuild build, final WarpAim aim) {
        if (aim.pick() == LightningWarp.Pick.NONE) {
            return;
        }
        if (LightningWarp.shockedGround(aim.pick()) && plugin.skills() != null && plugin.skills().config().skill(WARP_GROUND) != null) {
            plugin.skills().procs().castAt(player, WARP_GROUND, build.definition().id(), aim.enemy().getLocation());
        }
        switch (aim.pick()) {
            case CULL -> zones.warpInto(player, aim.enemy(), build);
            case BALL -> {
                final Origin origin = Origin.of(player);
                projectiles.aimedShot(player.getUniqueId(), LightningWarp.BALL, origin.eye(), origin.direction(),
                        targets.aimReach(origin, build.range()), LightningWarp.BALL_TOLERANCE, true); // 雷珠在爆炸裡消失
                player.setFallDistance(0);
                player.setVelocity(new Vector());
                zones.warpTo(player, aim.landing(), build);
            }
            case MARK -> {
                zones.markWarp(player, aim.enemy(), build, WARP_MARK_TICKS);
                visuals.cast(build.definition().visual(), aim.enemy().getLocation().add(0, 1, 0));
            }
            default -> {
            }
        }
    }

    /** 雷遁這一下要對誰：種類、敵人（撲殺／標記）、滾雷珠那裡的落點。 */
    private record WarpAim(LightningWarp.Pick pick, LivingEntity enemy, Location landing) {
    }

    /** 2026-10-28：這一下是不是主要寶石觸發的（技能描述的代號帶 {@link MetaGems#TRIGGERED_SUFFIX}）。 */
    static boolean triggered(final SkillBuild build) {
        return build.profile().id().contains(MetaGems.TRIGGERED_SUFFIX);
    }

    /**
     * 2026-10-28 照 PoE2：被觸發的雷遁（暴擊引星、祈喚…）要對誰。原作被觸發的技能對觸發它的目標：
     * 觸發的目標（暴擊打到的那隻）在範圍內 → 撲殺得到就瞬移進去炸開，不然掛標記；
     * 沒有目標（翻滾、召喚物死掉、祈喚按下去）→ 範圍內看得到、撲殺得到的最近一隻；都沒有就對最近一隻看得到的掛標記；
     * 範圍內沒有敵人就照準心（滾雷珠）。
     */
    private WarpAim triggeredAim(final Player player, final SkillBuild build, final LivingEntity preferred) {
        final double range = build.range();
        final Location feet = player.getLocation();
        final boolean hasPreferred = preferred != null && preferred.isValid() && !preferred.isDead()
                && preferred.getWorld().equals(feet.getWorld()) && preferred.getLocation().distanceSquared(feet) <= range * range;
        LivingEntity cull = null;
        LivingEntity any = null;
        if (!hasPreferred) {
            double cullBest = Double.MAX_VALUE;
            double anyBest = Double.MAX_VALUE;
            for (final LivingEntity near : targets.around(feet, range, player)) {
                if (!player.hasLineOfSight(near)) {
                    continue;
                }
                final double d = near.getLocation().distanceSquared(feet);
                if (d < anyBest) {
                    anyBest = d;
                    any = near;
                }
                if (d < cullBest && zones.cullable(near)) {
                    cullBest = d;
                    cull = near;
                }
            }
        }
        final LightningWarp.Pick pick = LightningWarp.chooseTriggered(hasPreferred, hasPreferred && zones.cullable(preferred), cull != null, any != null);
        return switch (pick) {
            case CULL -> new WarpAim(pick, hasPreferred ? preferred : cull, null);
            case MARK -> new WarpAim(pick, hasPreferred ? preferred : any, null);
            default -> warpAim(player, build);
        };
    }

    private WarpAim warpAim(final Player player, final SkillBuild build) {
        final Origin origin = Origin.of(player);
        final double range = build.range();
        final LivingEntity any = targets.aimed(origin, range, 1.5);
        final SkillTargets.Aimed cull = any == null ? null : targets.aimedWhere(origin, range, 1.5, zones::cullable);
        final double reach = targets.aimReach(origin, range);
        final Location ball = reach <= 0.05 ? null : projectiles.aimedShot(player.getUniqueId(), LightningWarp.BALL, origin.eye(),
                origin.direction(), reach, LightningWarp.BALL_TOLERANCE, false);
        final Location landing = ball == null ? null : ballLanding(player, ball);
        final Double ballAlong = landing == null ? null : ball.toVector().subtract(origin.eye().toVector()).dot(origin.direction());
        final LightningWarp.Pick pick = LightningWarp.choose(cull == null ? null : cull.along(), ballAlong, any != null);
        return switch (pick) {
            case CULL -> new WarpAim(pick, cull.entity(), null);
            case BALL -> new WarpAim(pick, null, landing);
            case MARK -> new WarpAim(pick, any, null);
            case NONE -> new WarpAim(pick, null, null);
        };
    }

    /** 瞬移到滾雷珠那裡站的位置：雷珠正下方幾格內能站的地方；懸空就站在雷珠的高度（會掉下去）；卡在方塊裡回傳 null。 */
    private Location ballLanding(final Player player, final Location ball) {
        final World world = ball.getWorld();
        final int x = ball.getBlockX();
        final int z = ball.getBlockZ();
        final int baseY = (int) Math.floor(ball.getY());
        for (int dy = 0; dy >= -3; dy--) {
            final int y = baseY + dy;
            if (y - 1 >= world.getMinHeight() && y + 1 < world.getMaxHeight() && standable(world, x, y, z)) {
                return new Location(world, ball.getX(), y, ball.getZ(), player.getLocation().getYaw(), player.getLocation().getPitch());
            }
        }
        final Location feet = ball.clone().subtract(0, 0.9, 0);
        final Block at = feet.getBlock();
        final Block head = at.getRelative(0, 1, 0);
        if (at.isPassable() && !at.isLiquid() && head.isPassable() && !head.isLiquid()) {
            feet.setYaw(player.getLocation().getYaw());
            feet.setPitch(player.getLocation().getPitch());
            return feet;
        }
        return null;
    }

    /** 2026-10-28：這隻怪的生命是不是已經低到撲殺門檻（圖騰挑雷遁的目標用）。 */
    boolean cullable(final LivingEntity target) {
        return zones.cullable(target);
    }

    /**
     * 2026-10-28 照 PoE2 Spell Totem：圖騰放的雷遁（目標要撲殺得到，見 {@link LightningWarp#chooseTotem}）：
     * 目標腳下留 8 秒感電地面、炸開（撲殺牠、炸周圍 3 格，算你的傷害）；回傳圖騰要瞬移到的地方（目標原本站的位置），
     * 圖騰自己由 SkillDeployables 搬過去。閃電灌注原作給圖騰、不給你（論壇「Lightning Warp doesn't create lightning infusions
     * when used by Spell Totems」），我們的圖騰沒有灌注，所以不給。
     */
    Location totemWarp(final Player owner, final SkillBuild build, final LivingEntity target) {
        final Location at = target.getLocation();
        if (plugin.skills() != null && plugin.skills().config().skill(WARP_GROUND) != null) {
            plugin.skills().procs().castAt(owner, WARP_GROUND, build.definition().id(), at);
        }
        zones.warpBlast(owner, at, build);
        visuals.cast(build.definition().visual(), at.clone().add(0, 1, 0));
        return at;
    }

    /** 雷遁留下的感電地面（隱藏技能）。 */
    static final String WARP_GROUND = "proc_warp_shocked_ground";
    /** 雷遁標記多久（4 秒）。 */
    static final int WARP_MARK_TICKS = 80;

    /** 2026-10-26：一次射出存著的彈（Hailstorm Rounds）：上次放的 tick（玩家:技能）。 */
    private final java.util.Map<String, Long> rainFired = new java.util.HashMap<>();

    private Snap snap;
    private Unearth unearth;

    /** 2026-10-27 照 PoE2 Unearth（掘骸）：骨刺之後把屍體變骨構體（見 {@link Unearth}）。 */
    private VolatileCores volatileCores;

    /** 2026-10-28 照 PoE2：屍焰爆的核心。 */
    private VolatileCores volatileCores() {
        if (volatileCores == null) {
            volatileCores = new VolatileCores(plugin, targets, damage, visuals);
        }
        return volatileCores;
    }

    private DecomposeClouds decomposeClouds;

    /** 2026-10-28 照 PoE2：腐屍爆的毒雲。 */
    DecomposeClouds decomposeClouds() {
        if (decomposeClouds == null) {
            decomposeClouds = new DecomposeClouds(plugin, targets, damage, visuals);
        }
        return decomposeClouds;
    }

    /** 每 tick（SkillService）：有毒雲才跑（沒放過腐屍爆的不建立）。 */
    void tickDecompose(final Player player, final long now) {
        if (decomposeClouds != null) {
            decomposeClouds.tick(player, now);
        }
    }

    /** 玩家離線：清掉他的毒雲。 */
    void forgetDecompose(final UUID player) {
        if (decomposeClouds != null) {
            decomposeClouds.clear(player);
        }
    }

    private Unearth unearth() {
        if (unearth == null) {
            unearth = new Unearth(plugin);
        }
        return unearth;
    }

    /** 2026-10-27 照 PoE2 Snap（噬異爆）：用掉敵人的異常炸開（見 {@link Snap}）。 */
    private Snap snap() {
        if (snap == null) {
            snap = new Snap(plugin, targets, projectiles, damage);
        }
        return snap;
    }

    private void impactOrSnap(final Origin origin, final SkillBuild build, final LivingEntity preferred) {
        if (Snap.is(build)) {
            snap().cast(origin, build, preferred);
            visuals.cast(build.definition().visual(), origin.eye());
        } else {
            impact(origin, build);
        }
    }

    /** 2026-10-28：瘟疫品質另外感染的敵人找第一隻身邊多遠內的。 */
    static final double EXTRA_TARGET_RANGE = 8;

    private void impact(final Origin origin, final SkillBuild build) {
        final LivingEntity aimed = targets.aimed(origin, build.range(), 1.0);
        final Location center = aimed != null ? aimed.getLocation() : targets.aimPoint(origin, build.range());
        final int delay = (int) Math.max(2, Math.round(build.params().delay() * 20 / fallSpeed(origin, build)));
        final String id = build.definition().id();
        if (build.params().targets() > 0) {
            singleImpact(origin, build, aimed, center, delay); // 2026-10-28 照 PoE2 Contagion：單體（使一名敵人），不是一整圈
            visuals.cast(build.definition().visual(), origin.eye());
            return;
        }
        // 2026-10-28 照 PoE2：萬箭齊落自己排箭（圖騰、觸發的也落全部的箭、同一隻 0.12 秒一次、同時一場）；寒晶雷引爆前脈衝曝露（StageLeftovers）
        if (plugin.skills() != null && plugin.skills().leftovers().impact(origin, build, center, delay)) {
            visuals.cast(build.definition().visual(), origin.eye());
            return;
        }
        final SkillConfig config = plugin.skills() == null ? null : plugin.skills().config();
        final SkillConfig.RainSpec rain = config == null ? null : config.rain(id);
        final SkillConfig.LodgeSpec lodge = config == null || origin.owner() == null ? null : config.lodge(id);
        final SkillConfig.RodSpec rod = config == null || origin.owner() == null ? null : config.rod(id);
        if (rod != null) {
            // 2026-10-28 照 PoE2 Lightning Rod（雷樁矢）：每一支箭落下炸開之後插在地上（多的投射物各落一支，平均散在準心周圍）
            final int count = Math.max(1, build.projectiles());
            final double spread = Math.max(1.5, build.radius() * 1.2);
            final double phase = plugin.random().nextDouble() * Math.PI * 2;
            for (int i = 0; i < count; i++) {
                final Location at = center.clone();
                if (i > 0) {
                    final double angle = phase + Math.PI * 2 * (i - 1) / (count - 1);
                    at.add(Math.cos(angle) * spread, 0, Math.sin(angle) * spread);
                }
                zones.addRodImpact(origin.owner(), at, build.radius(), build.profile(), build.definition().visual(), delay, id);
            }
        } else if (rain != null && origin.owner() != null) {
            // 2026-10-26（PoE2 Hailstorm Rounds）：一次射出整個彈匣，散在準心 area 格內陸續落下
            final String key = origin.owner().getUniqueId() + ":" + id;
            final long now = plugin.getServer().getCurrentTick();
            final int cap = Math.max(1, rain.store().intAt(build.level()));
            final Long last = rainFired.put(key, now);
            final int stored = last == null ? cap : (int) Math.min(cap, (now - last) / Math.max(1, Math.round(rain.interval() * 20)));
            final int count = Math.max(1, stored) * Math.max(1, build.projectiles()); // 原作每一發還多射一支（projectiles）
            for (int i = 0; i < count; i++) {
                final double angle = plugin.random().nextDouble() * Math.PI * 2;
                final double distance = Math.sqrt(plugin.random().nextDouble()) * rain.area();
                final Location at = center.clone().add(Math.cos(angle) * distance, 0, Math.sin(angle) * distance);
                final int extra = (int) Math.round(rain.seconds() * 20 * i / Math.max(1, count));
                zones.addImpact(origin.owner(), at, rain.radius() * build.areaScale(), build.profile(), build.definition().visual(), delay + extra);
            }
        } else if (lodge != null) {
            // 2026-10-26（PoE2 伏霆彈、寒凝彈）：一次落下好幾顆，落地後留在地上
            for (int i = 0; i < lodge.count(); i++) {
                final Location at = center.clone();
                if (lodge.count() > 1 && lodge.scatter() > 0) {
                    final double angle = Math.PI * 2 * i / lodge.count() + plugin.random().nextDouble();
                    final double distance = lodge.scatter() * (0.4 + 0.6 * plugin.random().nextDouble());
                    at.add(Math.cos(angle) * distance, 0, Math.sin(angle) * distance);
                }
                zones.addImpact(origin.owner(), at, build.radius(), build.profile(), build.definition().visual(), delay, id, build.level());
            }
        } else {
            // 2026-10-27 照 PoE2（召霆矢這類射到地上再落下的投射物技能）：多的投射物各射一支、各落一道，平均散在準心周圍；
            // 這次之前只落一道（多一發投射物的輔助只有懲罰沒有效果）
            // 2026-10-28 照 PoE2：一次落下好幾發的（impact 的 count：衝霄弩台 3 發、落釘弩台一輪 4 擊）照 count＋投射物加成；
            // spread 有寫就照寫的散開（吃範圍加成），沒寫照半徑估
            final int count = build.definition().tags().contains(tw.exilecore.stats.Tags.PROJECTILE) ? Math.max(1, build.projectiles()) : 1;
            final double configured = build.definition().params().spread();
            final double spread = configured > 0 ? configured * build.areaScale() : Math.max(1.5, build.radius() * 1.2);
            final double phase = plugin.random().nextDouble() * Math.PI * 2;
            // 2026-10-28 照 PoE2 Siege Ballista（衝霄弩台）：每發弩彈落地打一小圈，過一下再炸開一圈（cascade）
            final SkillConfig.CascadeSpec cascade = config == null ? null : config.cascade(id);
            final SkillProfile blast = cascade == null ? null : cascadeProfile(build.profile(), cascade, build.level());
            final double blastRadius = cascade == null ? 0 : cascade.radius() * build.areaScale();
            final int blastDelay = cascade == null ? 0 : (int) Math.max(1, Math.round(cascade.after() * 20));
            final List<Location> points = new ArrayList<>();
            for (int i = 0; i < count; i++) {
                final Location at = center.clone();
                if (i > 0) {
                    final double angle = phase + Math.PI * 2 * (i - 1) / (count - 1);
                    at.add(Math.cos(angle) * spread, 0, Math.sin(angle) * spread);
                }
                points.add(at);
            }
            if (SkillStages.get(id, "bolt") != null) {
                // 2026-10-28 照 PoE2 Siege Cascade（崩城落彈）：準心處（impact 的半徑內）每一隻敵人旁邊各落一發弩彈（stages.bolt 的小半徑），
                // 沒有敵人就落在準心；這次之前是準心處一整圈 4 格
                final List<Location> seeded = new ArrayList<>();
                for (final Location at : points) {
                    for (final LivingEntity enemy : targets.around(at, build.radius(), origin.owner())) {
                        seeded.add(enemy.getLocation());
                    }
                }
                if (!seeded.isEmpty()) {
                    points.clear();
                    points.addAll(seeded);
                }
            }
            final double boltRadius = SkillStages.radius(build, "bolt", build.radius());
            final SkillStages.Stage explosion = SkillStages.get(id, "explosion");
            final SkillProfile burst = explosion == null ? null : SkillStages.profile(build, build.profile(), "explosion");
            final double burstRadius = SkillStages.radius(build, "explosion", boltRadius);
            final int burstDelay = explosion == null ? 0 : (int) Math.max(1, Math.round(explosion.after() * 20));
            for (final Location at : points) {
                zones.addImpact(origin.owner(), at, boltRadius, build.profile(), build.definition().visual(), delay);
                if (blast != null) {
                    zones.addImpact(origin.owner(), at, blastRadius, blast, build.definition().visual(), delay + blastDelay);
                }
                if (burst != null) {
                    // 2026-10-28 照 PoE2：落地之後再炸開（自己的傷害表、半徑；崩城落彈的弩彈）
                    zones.addImpact(origin.owner(), at, burstRadius, burst, build.definition().visual(), delay + burstDelay);
                }
            }
            pulses(origin, build, center, delay);
        }
        visuals.cast(build.definition().visual(), origin.eye());
    }

    /**
     * 2026-10-28 照 PoE2 Contagion（瘟疫「使一名敵人受到混沌持續傷害的減益效果」）：impact 寫了 targets 的是單體——準心指的那隻敵人
     * （沒指到就準心 radius 內最近的），再加品質的「施放時另外感染 (0—2) 個敵人」（第一隻身邊 {@link #EXTRA_TARGET_RANGE} 格內最近的幾隻），
     * delay 之後各打一下（不打旁邊的）。沒有敵人就什麼都不做。這次之前在準心落一整圈 1.7 格，品質多的幾隻也是各落一整圈。
     */
    private void singleImpact(final Origin origin, final SkillBuild build, final LivingEntity aimed, final Location center, final int delay) {
        final Player owner = origin.owner();
        if (owner == null) {
            return;
        }
        final int extra = OriginalSkills.qualityInt(build.qualityFlat(tw.exilecore.stats.GemQualityKeys.EXTRA_TARGETS));
        final List<LivingEntity> picked = singleTargets(aimed, targets.around(center, build.radius(), owner),
                primary -> targets.around(primary.getLocation(), EXTRA_TARGET_RANGE, owner), build.params().targets() + extra);
        if (picked.isEmpty()) {
            return;
        }
        final String visual = build.definition().visual();
        plugin.getServer().getScheduler().runTaskLater(plugin, () -> {
            if (!owner.isOnline()) {
                return;
            }
            for (final LivingEntity target : picked) {
                if (target.isValid() && !target.isDead() && target.getWorld().equals(owner.getWorld())) {
                    damage.hit(owner, target, build.profile(), null);
                    visuals.mark(visual, target);
                }
            }
        }, Math.max(1, delay));
    }

    /**
     * 單體 impact 打誰（純函式，測試用）：第一隻是準心指的（沒指到就 nearCursor 的第一隻，已經照距離排好），
     * 再從第一隻身邊（around，照距離排好）補到 count 隻，不重複。
     */
    static <T> List<T> singleTargets(final T aimed, final List<T> nearCursor, final java.util.function.Function<T, List<T>> around, final int count) {
        final T primary = aimed != null ? aimed : nearCursor.isEmpty() ? null : nearCursor.getFirst();
        if (primary == null || count <= 0) {
            return List.of();
        }
        final List<T> out = new ArrayList<>();
        out.add(primary);
        if (count > 1) {
            for (final T other : around.apply(primary)) {
                if (out.size() >= count) {
                    break;
                }
                if (!out.contains(other)) {
                    out.add(other);
                }
            }
        }
        return out;
    }

    /**
     * 2026-10-28 照 PoE2 Spear of Solaris（熾陽之矛）：落地之後天光在原地脈衝（stages.pulse：自己的傷害表、半徑，
     * 每 interval 秒一次、共 count 次）。這次之前沒有脈衝。
     */
    private void pulses(final Origin origin, final SkillBuild build, final Location center, final int delay) {
        final SkillStages.Stage pulse = SkillStages.get(build.definition().id(), "pulse");
        if (pulse == null || origin.owner() == null) {
            return;
        }
        final double radius = SkillStages.radius(build, "pulse", build.radius());
        final SkillProfile profile = SkillStages.profile(build, build.profile(), "pulse")
                .derive(build.definition().id() + ".pulse", java.util.Set.of(tw.exilecore.stats.Tags.AREA), java.util.Set.of(tw.exilecore.stats.Tags.PROJECTILE));
        final double interval = pulse.interval() > 0 ? pulse.interval() : 0.2;
        final int times = Math.max(1, pulse.count().intAt(build.level()));
        final Player owner = origin.owner();
        final Location at = center.clone();
        plugin.getServer().getScheduler().runTaskLater(plugin, () -> {
            if (owner.isOnline() && owner.getWorld().equals(at.getWorld())) {
                zones.addCircle(owner, at, radius, profile, build.definition().visual(), (int) Math.round(interval * times * 20), interval, 0);
            }
        }, Math.max(1, delay));
        if (plugin.skills() != null) {
            plugin.skills().leftovers().solarisGround(owner, build, at, delay); // 2026-10-28 照 PoE2 Spear of Solaris：會擴大的點燃地面
        }
    }

    /**
     * 2026-10-28 照 PoE2 Siege Ballista：弩彈落地之後的爆炸——同一個技能（輔助、品質、加成照吃），效益換成爆炸的、物理轉換換成爆炸的。
     */
    static SkillProfile cascadeProfile(final SkillProfile bolt, final SkillConfig.CascadeSpec spec, final int level) {
        return new SkillProfile(bolt.id(), bolt.tags(), spec.effectiveness().at(level), bolt.spellDamage(), bolt.spellDamageMax(),
                spec.conversion().isEmpty() ? bolt.conversion() : spec.conversion(), bolt.local());
    }

    /**
     * 2026-10-24：從天上掉下來的彈（弩台、帶投射物標籤的延遲落下）：投射物速度越快掉得越快（落下的等待時間 ÷ 倍率，0.25～4 倍）。
     * 其他延遲落下的（隕石這種不是投射物的）照原本的時間。
     */
    static boolean fallsAsProjectile(final SkillDefinition definition) {
        return definition.tags().contains(tw.exilecore.stats.Tags.PROJECTILE) || definition.weapons().contains("crossbow");
    }

    private double fallSpeed(final Origin origin, final SkillBuild build) {
        if (origin.owner() == null || !fallsAsProjectile(build.definition())) {
            return 1.0;
        }
        final StatSheet effective = build.profile().effective(plugin.players().sheet(origin.owner()));
        return Math.max(0.25, Math.min(4.0, effective.apply(StatKeys.SKILL_PROJECTILE_SPEED, 1.0, build.profile().tags())));
    }

    /** 2026-10-26 照 PoE2 天賦樹：「裂地／法球技能 +1 上限」（skill.limit，帶 fissure／orb 標籤）：同時存在的上限多幾個。 */
    private int zoneMax(final Origin origin, final SkillBuild build, final int max) {
        if (max <= 0 || origin.owner() == null) {
            return max;
        }
        // 2026-10-28：改用 SkillLimits（小數捨去，跟圖騰、召喚物這些一樣）；這次之前四捨五入
        return max + SkillLimits.bonus(plugin.players().sheet(origin.owner()), build);
    }

    private void zone(final Origin origin, final SkillBuild build) {
        final SkillParams.Zone params = build.params().zone();
        final String visual = build.definition().visual();
        final SkillProfile profile = build.profile().scaled(params.damage() / 100.0, "地面效果");
        final double radius = build.radius();
        if (params.line() && origin.owner() != null && StatKeys.flag(build.profile().effective(plugin.players().sheet(origin.owner())),
                tw.exilecore.stats.SupportExtraKeys.FLAG_FORTRESS)) {
            // 2026-10-27 照 PoE2 Fortress（環壁）：牆改成在準心指的地方圍成一圈（牆的長度加倍，圓周 = 兩倍長度）
            final LivingEntity aimed = targets.aimed(origin, Math.max(8, build.range()), 1.0);
            final Location center = aimed != null ? aimed.getLocation() : targets.aimPoint(origin, Math.max(8, build.range()));
            final double ring = Math.max(1.5, build.length() * 2 / (2 * Math.PI));
            final int steps = Math.max(6, (int) Math.round(2 * Math.PI * ring / Math.max(0.8, radius)));
            final List<Location> points = new ArrayList<>();
            for (int i = 0; i < steps; i++) {
                final double a = 2 * Math.PI * i / steps;
                points.add(ground(center.clone().add(Math.cos(a) * ring, 0, Math.sin(a) * ring)));
            }
            zones.addPoints(origin.owner(), points, radius, profile, visual, build.durationTicks(params.duration()), params.interval(), params.pull(),
                    build.definition().id(), zoneMax(origin, build, params.max()));
            if (plugin.skills() != null) {
                plugin.skills().leftovers().wallPlaced(origin, build, points); // 2026-10-28 照 PoE2 Shield Wall：每一段的生命
            }
        } else if (params.line()) {
            // 面前橫向的一道（焰幕）：中心在面前 2 格，左右各半條長度
            final Vector facing = origin.facing();
            // 2026-10-27 照 PoE2 裂垣（Ahn's Citadel）：牆改成沿著一道往前的裂縫（從腳下往準心延伸），長度增加 65%
            final double wallLength = origin.owner() == null ? 0 : build.profile().effective(plugin.players().sheet(origin.owner()))
                    .flat(tw.exilecore.stats.LineageKeys.WALL_FISSURE, build.profile().tags());
            final double length = wallLength > 0 ? build.length() * Math.max(0.1, 1 + build.profile().effective(plugin.players().sheet(origin.owner()))
                    .increased(tw.exilecore.stats.LineageKeys.WALL_LENGTH, build.profile().tags())) : build.length();
            final Vector side = wallLength > 0 ? facing.clone().setY(0).normalize() : new Vector(-facing.getZ(), 0, facing.getX());
            final Location center = origin.feet().clone().add(facing.clone().multiply(wallLength > 0 ? 1.0 + length / 2 : 2.0));
            final List<Location> points = new ArrayList<>();
            final int steps = Math.max(1, (int) Math.round(length / Math.max(0.8, radius)));
            for (int i = 0; i <= steps; i++) {
                final double offset = -length / 2 + length * i / steps;
                final Location point = center.clone().add(side.clone().multiply(offset));
                points.add(ground(point));
            }
            zones.addPoints(origin.owner(), points, radius, profile, visual, build.durationTicks(params.duration()), params.interval(), params.pull(),
                    build.definition().id(), zoneMax(origin, build, params.max()));
            if (plugin.skills() != null) {
                plugin.skills().leftovers().wallPlaced(origin, build, points); // 2026-10-28 照 PoE2 Shield Wall：每一段的生命
            }
            if (origin.self() && origin.owner() != null) {
                lineAftershocks(origin.owner(), build, profile, points, radius); // 2026-10-28 照 PoE2 Volcanic Fissure 的品質：x% 機率多一次餘震
            }
        } else {
            final Location center;
            if (build.range() <= 0) {
                center = origin.feet().clone();
            } else {
                final LivingEntity aimed = targets.aimed(origin, build.range(), 1.0);
                center = aimed != null ? aimed.getLocation() : targets.aimPoint(origin, build.range());
            }
            zones.addCircle(origin.owner(), center, radius, profile, visual, build.durationTicks(params.duration()), params.interval(), params.pull(),
                    build.definition().id(), zoneMax(origin, build, params.max()));
            if (origin.owner() != null && (params.bolts() != null || params.shower() != null)) {
                // 2026-10-28 照 PoE2：雷雲每一波一道會連鎖的雷（連鎖 3 → 5 → 9 次，再加連鎖的輔助）、地火柱每一波噴 3 → 5 → 6 顆火山彈（再加投射物數量）
                final tw.exilecore.stats.StatSheet effective = build.profile().effective(plugin.players().sheet(origin.owner()));
                final int chains = params.bolts() == null ? -1 : params.bolts().chains().intAt(build.level())
                        + Math.max(0, (int) Math.round(effective.flat(StatKeys.SKILL_CHAINS, build.profile().tags())));
                final int shards = params.shower() == null ? 0 : Math.max(1, params.shower().count().intAt(build.level())
                        + (int) Math.round(effective.flat(StatKeys.SKILL_PROJECTILES, build.profile().tags())));
                zones.pulses(origin.owner(), build.definition().id(), chains,
                        params.bolts() == null ? 0 : params.bolts().range() * build.areaScale(),
                        params.bolts() == null ? 0 : params.bolts().chainRange(),
                        Math.min(20, shards), params.shower() == null ? 0 : params.shower().radius() * build.areaScale());
            }
            DruidSupports.advanceStorm(plugin, zones, origin, build, center); // 2026-10-26 照 PoE2：行嵐、掠嵐讓風暴從腳下走過去
            if (plugin.skills() != null) {
                plugin.skills().leftovers().volcano(origin, build, center); // 2026-10-28 照 PoE2 Volcano：引導的開場爆發
            }
        }
        visuals.cast(visual, origin.eye());
    }

    /**
     * 2026-10-27 照 PoE2 分裂地面：裂地技能（fissure 標籤：炎脈裂地、骨裂地脈）有 x% 機率多一道裂縫，往左或右偏
     * {@link tw.exilecore.stats.KeystoneOriginKeys#EXTRA_FISSURE_ANGLE} 度；這次之前我們的裂地一次只出一道，平衡等效成裂地技能更多 10% 傷害。
     */
    private void extraFissure(final Origin origin, final SkillBuild build) {
        if (origin.owner() == null || !build.profile().tags().contains(tw.exilecore.stats.KeystoneKeys.FISSURE)) {
            return;
        }
        final tw.exilecore.stats.StatSheet effective = build.profile().effective(plugin.players().sheet(origin.owner()));
        final java.util.Set<String> tags = build.profile().tags();
        // 2026-10-27 照 PoE2 Upheaval（多裂開 x 道，左右交替往外偏）、Branching Fissures（分岔 x 道，分岔的傷害、範圍更少）
        final int additional = (int) Math.round(effective.flat(tw.exilecore.stats.SupportExtraKeys.FISSURE_EXTRA, tags));
        for (int i = 1; i <= additional; i++) {
            turnedFissure(origin, build, Math.toRadians(tw.exilecore.stats.KeystoneOriginKeys.EXTRA_FISSURE_ANGLE) * ((i + 1) / 2) * (i % 2 == 0 ? -1 : 1));
        }
        // 2026-10-27 照 PoE2 狂裂（Kaom's Madness）：多裂出 3 道，往兩側輪流散開（每道再偏 15 度）
        final int kaom = (int) Math.round(effective.flat(tw.exilecore.stats.LineageKeys.EXTRA_FISSURES, tags));
        for (int i = 1; i <= Math.min(6, kaom); i++) {
            turnedFissure(origin, build, Math.toRadians(15.0 * ((i + 1) / 2)) * (i % 2 == 1 ? 1 : -1));
        }
        final int branches = (int) Math.round(effective.flat(tw.exilecore.stats.SupportExtraKeys.FISSURE_BRANCHES, tags));
        if (branches > 0) {
            final double less = effective.flat(tw.exilecore.stats.SupportExtraKeys.FISSURE_BRANCH_DAMAGE, tags) / 100.0;
            final double smaller = effective.flat(tw.exilecore.stats.SupportExtraKeys.FISSURE_BRANCH_AREA, tags) / 100.0;
            final SkillBuild branch = build.withProfile(build.profile().scaled(Math.max(0, 1 - less), "分岔裂地"))
                    .withAreaAndProjectiles(build.areaScale() * Math.sqrt(Math.max(0.1, 1 - smaller)), build.projectiles());
            // 2026-10-27 照 PoE2：分岔從主裂縫上岔出去（第 i 道在主裂縫的 i ÷ (x＋1) 處，左右交替偏 40 度）；這次之前從起點往兩旁各裂一道。
            // 扇形的裂縫往前長（沿著面向）；地面的一道（炎脈裂地）是面前橫的一條（沿著左右）
            final Vector along = origin.direction().clone().setY(0);
            final boolean cone = build.definition().mechanic() == Mechanic.CONE;
            final Vector axis = cone ? along : new Vector(-along.getZ(), 0, along.getX());
            for (int i = 1; i <= branches; i++) {
                final double at = cone ? build.length() * branchPoint(i, branches) : build.length() * (branchPoint(i, branches) - 0.5);
                final Vector shift = axis.lengthSquared() < 1e-6 ? new Vector() : axis.clone().normalize().multiply(at);
                final Origin from = new Origin(origin.owner(), origin.feet().clone().add(shift), origin.eye().clone().add(shift), origin.direction(),
                        origin.self());
                turnedFissure(from, branch, Math.toRadians(40) * (i % 2 == 0 ? -1 : 1));
            }
        }
        final double chance = effective.flat(tw.exilecore.stats.KeystoneOriginKeys.EXTRA_FISSURE_CHANCE, tags);
        if (chance <= 0 || plugin.random().nextDouble() * 100 >= chance) {
            return;
        }
        turnedFissure(origin, build, Math.toRadians(tw.exilecore.stats.KeystoneOriginKeys.EXTRA_FISSURE_ANGLE) * (plugin.random().nextBoolean() ? 1 : -1));
    }

    /** Branching Fissures：第 i 道（共 total 道）分岔從主裂縫的哪裡（長度的比例）岔出去。 */
    static double branchPoint(final int i, final int total) {
        return i / (double) (Math.max(1, total) + 1);
    }

    /** 往旁邊偏 angle（弧度）再裂一道。 */
    private void turnedFissure(final Origin origin, final SkillBuild build, final double angle) {
        final Vector dir = origin.direction().clone();
        final double x = dir.getX() * Math.cos(angle) - dir.getZ() * Math.sin(angle);
        final double z = dir.getX() * Math.sin(angle) + dir.getZ() * Math.cos(angle);
        final Origin turned = new Origin(origin.owner(), origin.feet(), origin.eye(), new Vector(x, dir.getY(), z), origin.self());
        if (build.definition().mechanic() == Mechanic.CONE) {
            cone(turned, build);
        } else {
            zone(turned, build);
        }
    }

    /** 這個點往下找地面（最多 4 格），讓牆站在地上而不是浮著。 */
    private static Location ground(final Location point) {
        final World world = point.getWorld();
        final int x = point.getBlockX();
        final int z = point.getBlockZ();
        for (int y = point.getBlockY() + 1; y >= point.getBlockY() - 4; y--) {
            if (y - 1 >= world.getMinHeight() && world.getBlockAt(x, y, z).isPassable() && !world.getBlockAt(x, y - 1, z).isPassable()) {
                return new Location(world, point.getX(), y, point.getZ());
            }
        }
        return point;
    }

    // ---- 骸爆 ----

    /**
     * 2026-10-28 照 PoE2 Spell Totem：圖騰放的骸爆找哪具屍體——圖騰的目標（敵人）附近最近的（骸爆的搜尋範圍內）；
     * 沒有就圖騰身邊、骸爆距離內最近的。都沒有回傳 null（圖騰換放別的法術）。
     */
    tw.exilecore.combat.Corpses.Corpse corpseNear(final Origin origin, final SkillBuild build, final LivingEntity target) {
        final tw.exilecore.combat.Corpses corpses = plugin.combat().corpses();
        final long now = plugin.getServer().getCurrentTick();
        tw.exilecore.combat.Corpses.Corpse corpse = null;
        if (target != null && target.getWorld().equals(origin.world())) {
            corpse = corpses.nearest(origin.world().getUID(), target.getLocation().getX(), target.getLocation().getZ(), build.params().search(), now);
        }
        if (corpse == null) {
            corpse = corpses.nearest(origin.world().getUID(), origin.feet().getX(), origin.feet().getZ(), build.range(), now);
        }
        return corpse;
    }

    /** 準心指的點附近最近的屍體；沒有回傳 null。 */
    tw.exilecore.combat.Corpses.Corpse corpseNear(final Origin origin, final SkillBuild build) {
        final tw.exilecore.combat.Corpses corpses = plugin.combat().corpses();
        final long now = plugin.getServer().getCurrentTick();
        final LivingEntity aimed = targets.aimed(origin, build.range(), 1.0);
        final Location point = aimed != null ? aimed.getLocation() : targets.aimPoint(origin, build.range());
        tw.exilecore.combat.Corpses.Corpse corpse = corpses.nearest(origin.world().getUID(), point.getX(), point.getZ(), build.params().search(), now);
        if (corpse == null) {
            // 準心附近沒有：找自己身邊的
            corpse = corpses.nearest(origin.world().getUID(), origin.feet().getX(), origin.feet().getZ(), build.params().search(), now);
        }
        return corpse;
    }

    private void detonate(final Origin origin, final SkillBuild build) {
        detonate(origin, build, null);
    }

    private void detonate(final Origin origin, final SkillBuild build, final LivingEntity preferred) {
        final tw.exilecore.combat.Corpses.Corpse corpse = preferred == null ? corpseNear(origin, build) : corpseNear(origin, build, preferred);
        if (corpse == null) {
            return;
        }
        final tw.exilecore.combat.Corpses.Contagion carried = plugin.combat().corpses().contagion(corpse); // 2026-10-28 照 PoE2 Contagion：引爆帶著瘟疫的屍體，爆炸擊中時傳出瘟疫
        if (origin.owner() == null || !tw.exilecore.stats.TreeEffectKeys.corpseSaved(build.profile().effective(plugin.players().sheet(origin.owner())), plugin.random())) { // 2026-10-26 照 PoE2 天賦樹：x% 機率不毀掉屍體；2026-10-27 也算輔助（惜屍，PoE2 Corpse Conservation）
            plugin.combat().corpses().remove(corpse);
        }
        if (origin.owner() != null) {
            plugin.players().get(origin.owner()).markRecent(tw.exilecore.stats.Recently.CORPSE, plugin.getServer().getCurrentTick()); // 「最近消耗過屍體」
        }
        if (origin.owner() != null && VolatileCores.is(build.profile().effective(plugin.players().sheet(origin.owner())))) {
            // 2026-10-28 照 PoE2 Volatile Dead（屍焰爆）：屍體變成一顆會追敵人、點燃身邊敵人、5 秒後才爆炸的核心；這次之前直接引爆
            for (int i = 0; i < VolatileCores.cores(1); i++) {
                volatileCores().spawn(origin.owner(), build, new Location(origin.world(), corpse.x(), corpse.y(), corpse.z()), carried);
            }
            return;
        }
        if (DecomposeClouds.is(build)) {
            // 2026-10-28 照 PoE2 Decompose：屍體變成一團易燃的毒雲（中毒、被點燃或引爆類技能打到才炸開）；這次之前直接引爆屍體造成火焰傷害
            final Location at = new Location(origin.world(), corpse.x(), corpse.y(), corpse.z());
            if (origin.self() && origin.owner() != null) {
                lastBlast.put(origin.owner().getUniqueId(), new Blast(at.clone(), corpse.maxLife(), plugin.getServer().getCurrentTick()));
            }
            decomposeClouds().spawn(origin.owner(), build, at, corpse.maxLife(), carried);
            return;
        }
        final Location center = new Location(origin.world(), corpse.x(), corpse.y() + 0.3, corpse.z());
        if (origin.self() && origin.owner() != null) { // 2026-10-28 照 PoE2：連瀑照這一次的爆炸往兩側各炸一次（SkillExecutor.cascade）
            lastBlast.put(origin.owner().getUniqueId(), new Blast(center.clone(), corpse.maxLife(), plugin.getServer().getCurrentTick()));
        }
        blastAt(origin.owner(), build, center, corpse.maxLife(), carried);
    }

    /** 屍體在 center 爆炸（骸爆；連瀑的那幾次也走這裡，不再用掉屍體）。 */
    private void blastAt(final Player owner, final SkillBuild build, final Location center, final double corpseLife) {
        blastAt(owner, build, center, corpseLife, null);
    }

    /** 同上；carried：2026-10-28 照 PoE2 Contagion，這具屍體帶著的瘟疫（爆炸擊中的敵人都掛上；null = 沒有）。 */
    private void blastAt(final Player owner, final SkillBuild build, final Location center, final double corpseLife,
                         final tw.exilecore.combat.Corpses.Contagion carried) {
        final double radius = build.radius();
        // 技能自己的傷害 ＋ 屍體最大生命的 x% 當物理傷害（照法術附加一樣，走增加／更多）
        // 2026-10-28 照 PoE2 Detonate Dead（poe2db：屍體生命的部分是物理，品質把物理轉成火焰）；這次之前當火焰
        final double extra = corpseLife * build.params().percent().at(build.level()) / 100.0;
        final SkillProfile base = build.profile();
        final tw.exilecore.stats.DamagePacket min = base.spellDamage() == null ? new tw.exilecore.stats.DamagePacket() : new tw.exilecore.stats.DamagePacket(base.spellDamage());
        final tw.exilecore.stats.DamagePacket max = base.spellMax() == null ? new tw.exilecore.stats.DamagePacket() : new tw.exilecore.stats.DamagePacket(base.spellMax());
        min.add(tw.exilecore.stats.DamageType.PHYSICAL, extra);
        max.add(tw.exilecore.stats.DamageType.PHYSICAL, extra);
        final SkillProfile profile = new SkillProfile(base.id(), base.tags(), base.effectiveness(), min, max, base.conversion(), base.local());
        // 2026-10-28 照 PoE2 Detonate Dead（poe2db：爆炸延遲 400 毫秒）：先冒煙預警，8 tick 後才炸；這次之前當下就炸
        Fx.spawn(center.getWorld(), org.bukkit.Particle.LARGE_SMOKE, center, 10, radius * 0.3, 0.2, radius * 0.3, 0.01);
        plugin.getServer().getScheduler().runTaskLater(plugin, () -> {
            if (owner != null && !owner.isOnline()) {
                return;
            }
            for (final LivingEntity target : targets.around(center, radius, owner)) {
                if (carried != null && plugin.ailments() != null) {
                    plugin.ailments().applyContagion(target, carried); // 先掛上：這一下打死的話照樣再傳出去
                }
                damage.hit(owner, target, profile, null);
            }
            targets.strike(owner, profile, SkillTargets.sphere(center, radius), new Object()); // 2026-10-25
            visuals.detonate(build.definition().visual(), center, radius);
        }, BLAST_DELAY_TICKS);
    }

    /** 2026-10-28 照 PoE2 Detonate Dead：屍體爆炸的延遲（400 毫秒）。 */
    static final long BLAST_DELAY_TICKS = 8;

    // ---- 連瀑（2026-10-28 照 PoE2 Cascadable）----

    /** 玩家這一 tick 引爆的屍體（連瀑照它往兩側各炸一次）。 */
    record Blast(Location center, double corpseLife, long tick) {
    }

    private final java.util.Map<UUID, Blast> lastBlast = new java.util.HashMap<>();

    /**
     * 連瀑的基準（施放當下記下來）：self 是施放者的位置與準心；curse 是詛咒落下的中心（詛咒才有）；
     * blast 是剛引爆的屍體（骸爆才有）。
     */
    record CascadeAnchor(Origin self, Location curse, Blast blast) {
    }

    CascadeAnchor cascadeAnchor(final Player player, final SkillBuild build, final LivingEntity preferred) {
        final Origin self = Origin.of(player);
        Location curse = null;
        if (build.definition().mechanic() == Mechanic.MARK && build.params().mark() != null && build.params().mark().curse()) {
            curse = curseCenter(player, build, preferred);
        }
        Blast blast = null;
        if (build.definition().mechanic() == Mechanic.CORPSE) {
            final Blast last = lastBlast.remove(player.getUniqueId());
            if (last != null && last.tick() == plugin.getServer().getCurrentTick() && last.center().getWorld().equals(player.getWorld())) {
                blast = last;
            }
        }
        return new CascadeAnchor(self, curse, blast);
    }

    /**
     * 2026-10-28 照 PoE2 連瀑（Spell Cascade、神幻雪崩）：把這一次往 shift 平移再放一次（build 是已經套上連瀑範圍更少的）。
     * 詛咒：照原本的落點平移再落一圈（一樣過 1.5 秒才生效、一隻敵人同時只有你一個詛咒，同一個詛咒重疊就是刷新）；
     * 骸爆：照原本的爆炸平移再炸一次（不再用掉屍體）；其他：從平移後的位置照同一個準心重放。
     * 這次之前一律從平移後的位置重放：詛咒會落在自己身邊，所以詛咒掛不上連瀑。
     */
    void cascade(final Player player, final SkillBuild build, final CascadeAnchor anchor, final Vector shift) {
        if (anchor.curse() != null) {
            curseAt(player, build, anchor.curse().clone().add(shift), build.params().mark());
            return;
        }
        if (anchor.blast() != null && DecomposeClouds.is(build)) { // 2026-10-28 照 PoE2 Decompose：連瀑往兩側各多一團毒雲（不再用掉屍體）
            decomposeClouds().spawn(player, build, anchor.blast().center().clone().add(shift), anchor.blast().corpseLife(), null);
            return;
        }
        if (anchor.blast() != null) {
            blastAt(player, build, anchor.blast().center().clone().add(shift), anchor.blast().corpseLife());
            return;
        }
        final Origin self = anchor.self();
        executeFrom(new Origin(player, self.feet().clone().add(shift), self.eye().clone().add(shift), self.direction(), false), build, null);
    }

    /** 詛咒落下的中心：指著敵人就是牠腳下，沒有就是準心指的地方。 */
    Location curseCenter(final Player player, final SkillBuild build, final LivingEntity preferred) {
        final Origin origin = Origin.of(player);
        final LivingEntity aimed = preferred != null && targets.valid(preferred, player) ? preferred : targets.aimed(origin, build.range(), 1.2);
        return aimed != null ? aimed.getLocation() : targets.aimPoint(origin, build.range());
    }

    /** 詛咒（2026-10）：準心指的地方（指著敵人就是牠腳下）一圈的敵人全部套上減益。 */
    private void curse(final Player player, final SkillBuild build, final LivingEntity preferred, final SkillParams.Mark spec) {
        curseFrom(Origin.of(player), player, build, curseCenter(player, build, preferred), spec);
    }

    /** 詛咒落在 center（玩家自己放、圖騰放都走這裡）；縛根的品質照 origin 面向的方向多一道裂隙。 */
    private void curseFrom(final Origin origin, final Player player, final SkillBuild build, final Location center, final SkillParams.Mark spec) {
        curseAt(player, build, center, spec);
        // 2026-10-28 照 PoE2 Entangle 的品質：(0—20)% 機率多一道裂隙（再往前一圈；跟天賦樹「多一道裂隙的機率」同一個鍵，只看技能自己的）
        final double extra = SkillZones.localFlat(build.profile(), tw.exilecore.stats.KeystoneOriginKeys.EXTRA_FISSURE_CHANCE);
        if (extra > 0 && plugin.random().nextDouble() * 100 < extra) {
            final double step = spec.radius(build.level()) * build.areaScale() * 1.5;
            final org.bukkit.util.Vector ahead = origin.facing().clone().setY(0);
            if (ahead.lengthSquared() > 1e-6) {
                curseAt(player, build, center.clone().add(ahead.normalize().multiply(step)), spec);
            }
        }
    }

    /** 2026-10-26 照 PoE2：詛咒落地之後 1.5 秒才生效（捷咒：更少 50%）；輔助觸發的減益（隱藏技能）馬上生效。 */
    static final double CURSE_DELAY_SECONDS = 1.5;

    private void curseAt(final Player player, final SkillBuild build, final Location center, final SkillParams.Mark spec) {
        if (build.definition().hidden()) {
            applyCurseAt(player, build, center, spec);
            return;
        }
        final tw.exilecore.stats.StatSheet curseSheet = build.profile().effective(plugin.players().sheet(player));
        // 2026-10-26 照 PoE2 天賦樹：「詛咒生效加快 x%」（stat 寫成減少 x% 延遲）：延遲 ×（1 ＋ 增加）；這次之前只吃輔助的更少
        final double more = Math.max(0, curseSheet.more(tw.exilecore.stats.SupportKeys.CURSE_DELAY, build.profile().tags()))
                * Math.max(0, 1 + curseSheet.increased(tw.exilecore.stats.SupportKeys.CURSE_DELAY, build.profile().tags()));
        final long delay = Math.round(CURSE_DELAY_SECONDS * 20 * more);
        final double radius = spec.radius(build.level()) * build.areaScale(); // 2026-10-26：詛咒的範圍照等級（PoE2 逐級）
        if (delay <= 0) {
            applyCurseAt(player, build, center, spec);
            return;
        }
        final Location at = center.clone();
        // 詛咒圈先畫出來，時間到了才落下
        for (int i = 0; i < 20; i++) {
            final double a = Math.PI * 2 * i / 20;
            Fx.spawn(at.getWorld(), org.bukkit.Particle.WITCH, at.clone().add(Math.cos(a) * radius, 0.2, Math.sin(a) * radius), 1, 0, 0, 0, 0);
        }
        plugin.getServer().getScheduler().runTaskLater(plugin, () -> {
            if (player.isOnline() && player.getWorld().equals(at.getWorld())) {
                applyCurseAt(player, build, at, spec);
            }
        }, delay);
    }

    private void applyCurseAt(final Player player, final SkillBuild build, final Location center, final SkillParams.Mark spec) {
        if (plugin.skills() != null && plugin.skills().supportMechanics().lineage().curseGround(player, build, center, spec)) {
            return; // 2026-10-27 照 PoE2：蟾咒、咒光把詛咒改成詛咒地面（LineageMechanics 定時對站在裡面的敵人套詛咒）
        }
        if (plugin.skills() != null && plugin.skills().supportMechanics().extras().cursedGround(player, build, center, spec)) {
            return; // 2026-10-27 照 PoE2 Cursed Ground（咒地）：詛咒改成詛咒地面，站在裡面才被詛咒
        }
        applyCurseAt(player, build, center, spec, 0);
    }

    /** 2026-10-27：untilTick > 0 時詛咒到那個 tick 為止（詛咒地面：站在裡面才被詛咒）。 */
    void applyCurseAt(final Player player, final SkillBuild build, final Location center, final SkillParams.Mark spec, final long untilTick) {
        final double radius = spec.radius(build.level()) * build.areaScale(); // 2026-10-26：詛咒的範圍照等級（PoE2 逐級）
        final long until = untilTick > 0 ? untilTick
                : plugin.getServer().getCurrentTick() + build.durationTicks(spec.duration(build.level())); // 2026-10-26：持續時間照等級
        final List<String> tags = spec.tags().isEmpty() ? java.util.Collections.singletonList(null) : spec.tags();
        // 2026-10：削弱、遲滯這種詛咒：修飾器疊在敵人身上、移動變慢（詛咒效果照輔助的光環效果不吃，只看技能等級）
        final double scale = build.auraEffect(); // 2026-10：詛咒效果（輔助寶石）
        final List<tw.exilecore.stats.Modifier> effects = new java.util.ArrayList<>();
        for (final ScaledMod effect : spec.effects()) {
            effects.add(effect.toModifier(build.level(), scale, build.definition().name()));
        }
        final List<tw.exilecore.stats.Modifier> mergedCurse = build.withQualityEffects(effects, scale, build.definition().name()); // 2026-10-28 照 PoE2：品質加在詛咒本身的數值上
        effects.clear();
        effects.addAll(mergedCurse);
        final double slow = spec.slow().at(build.level()) / 100.0 * scale;
        // 2026-10-22：真正的詛咒（不是輔助觸發的殘肢、目眩這些隱藏技能）一隻敵人身上同時只能有你的一個，放新的拿掉舊的
        final boolean exclusive = !build.definition().hidden() && !LineageMechanics.ignoresCurseLimit(build); // 2026-10-27 照 PoE2 魅咒：不受詛咒上限限制
        // 2026-10-26 照 PoE2 天賦樹：「被你詛咒的敵人生命再生速度減少 x%」：疊在詛咒上（MonsterMechanics 的再生乘上去）
        final double regen = exclusive ? plugin.players().sheet(player).increased(tw.exilecore.stats.TreeEffectKeys.CURSE_ENEMY_REGEN, java.util.Set.of()) : 0;
        if (regen != 0) {
            effects.add(tw.exilecore.stats.Modifier.increased(tw.exilecore.stats.TreeEffectKeys.REGEN_RATE, regen, build.definition().name()));
        }
        // 2026-10-27 照 PoE2 天賦樹：末日厄運（詛咒過了一半效果增加）、命運終結（被你詛咒的敵人不能回充護盾）
        final tw.exilecore.stats.StatSheet ownerSheet = plugin.players().sheet(player);
        final double late = exclusive ? ownerSheet.increased(tw.exilecore.stats.KeystoneOriginKeys.CURSE_LATE_MAGNITUDE, java.util.Set.of()) : 0;
        final boolean noRecharge = exclusive && StatKeys.flag(ownerSheet, tw.exilecore.stats.KeystoneOriginKeys.CURSE_NO_ES_RECHARGE);
        final long applied = plugin.getServer().getCurrentTick();
        for (final LivingEntity target : targets.around(center, radius, player)) {
            // 2026-10-26（PoE2）：被詛咒的那一方「受到的詛咒效果」（魔法 −15%、稀有 −30%、傳奇 −50%，換界石再更少）
            final double self = curseEffectOnSelf(target);
            final List<tw.exilecore.stats.Modifier> targetEffects = self == 1.0 ? effects : scaled(effects, self);
            final List<Debuffs.Debuff> placed = new ArrayList<>();
            if (noRecharge) {
                plugin.combat().debuffs().apply(target.getUniqueId(), new Debuffs.Debuff(tw.exilecore.stats.KeystoneOriginKeys.NO_ES_RECHARGE_ID,
                        player.getUniqueId(), until, 0, false));
            }
            for (final String tag : tags) {
                final Debuffs.Debuff debuff = new Debuffs.Debuff(build.definition().id() + (tag == null ? "" : "." + tag),
                        player.getUniqueId(), until, spec.taken() / 100.0 * scale * self, spec.selfOnly(), tag, targetEffects, slow * self);
                placed.add(debuff);
                if (exclusive) {
                    plugin.combat().debuffs().applyCurse(target.getUniqueId(), debuff, build.definition().id(), 1 + (int) Math.round(Math.max(0,
                            plugin.players().sheet(player).flat(tw.exilecore.stats.KeystoneKeys.CURSE_LIMIT, java.util.Set.of())))); // 2026-10-26 滅世之語
                } else {
                    plugin.combat().debuffs().apply(target.getUniqueId(), debuff);
                }
                plugin.combat().debuffs().noteCurseLength(target.getUniqueId(), debuff.id(), until - applied); // 2026-10-28 照 PoE2 Contagion：瘟疫傳出去照這個刷新
            }
            if (late > 0) {
                strengthenLate(target.getUniqueId(), placed, applied, until, 1 + late);
            }
            visuals.mark(build.definition().visual(), target);
            if (plugin.skills() != null) {
                plugin.skills().supportMechanics().lineage().cursePlaced(player, build, target, placed, applied, until); // 2026-10-27 照 PoE2：魅咒、寂咒
            }
        }
        visuals.nova(build.definition().visual(), center, radius);
    }

    /**
     * 2026-10-27 照 PoE2 末日厄運：詛咒的持續時間過了一半，效果（受到的傷害、修飾器、緩速）乘 factor。
     * 時間到的時候身上還是同一個詛咒（沒被重放、沒被換掉）才換；這次之前平衡等效成整段詛咒效果增加一半。
     */
    private void strengthenLate(final UUID target, final List<Debuffs.Debuff> placed, final long applied, final long until, final double factor) {
        final long half = tw.exilecore.stats.KeystoneOriginKeys.curseHalfway(applied, until);
        plugin.getServer().getScheduler().runTaskLater(plugin, () -> {
            final long now = plugin.getServer().getCurrentTick();
            final Debuffs debuffs = plugin.combat().debuffs();
            for (final Debuffs.Debuff original : placed) {
                for (final Debuffs.Debuff current : debuffs.active(target, now)) {
                    if (current.equals(original)) {
                        debuffs.apply(target, new Debuffs.Debuff(current.id(), current.source(), current.untilTick(), current.taken() * factor,
                                current.selfOnly(), current.tag(), scaled(current.mods(), factor), current.slow() * factor));
                        break;
                    }
                }
            }
        }, Math.max(1, half - applied));
    }

    /** 2026-10-26：這隻怪身上的詛咒效果倍率（怪物的屬性表；玩家、沒等級化的是 1）。 */
    private double curseEffectOnSelf(final LivingEntity target) {
        if (target instanceof Player cursed) {
            // 2026-10-26 照 PoE2 天賦樹：玩家的「你身上的詛咒效果減少」（PvP 被詛咒時）；這次之前玩家一律 1
            return Math.max(0, plugin.players().sheet(cursed).apply(tw.exilecore.stats.StatKeys.CURSE_EFFECT_ON_SELF, 1.0, java.util.Set.of()));
        }
        if (plugin.monsters() == null) {
            return 1.0;
        }
        final java.util.Optional<tw.exilecore.monster.MonsterData> data = plugin.monsters().data(target);
        if (data.isEmpty()) {
            return 1.0;
        }
        return Math.max(0, plugin.monsters().sheet(target, data.get()).apply(tw.exilecore.stats.StatKeys.CURSE_EFFECT_ON_SELF, 1.0,
                java.util.Set.of()));
    }

    private static List<tw.exilecore.stats.Modifier> scaled(final List<tw.exilecore.stats.Modifier> effects, final double factor) {
        final List<tw.exilecore.stats.Modifier> list = new java.util.ArrayList<>(effects.size());
        for (final tw.exilecore.stats.Modifier m : effects) {
            list.add(new tw.exilecore.stats.Modifier(m.stat(), m.type(), m.value() * factor, m.tags(), m.source()));
        }
        return list;
    }

    LivingEntity markTarget(final Player player, final SkillBuild build, final LivingEntity preferred) {
        final Origin origin = Origin.of(player);
        if (preferred != null && targets.valid(preferred, player) && preferred.getLocation().distance(player.getLocation()) <= build.range()) {
            return preferred;
        }
        // 2026-10-23：一定要準心指著敵人才放得出去（以前沒指到會自己挑前方 30° 內最近的一隻，常常點到不是想要的那隻）
        return targets.aimed(origin, build.range(), 1.2);
    }

    private void mark(final Player player, final SkillBuild build, final LivingEntity preferred) {
        final SkillParams.Mark spec = build.params().mark();
        if (spec.curse()) {
            curse(player, build, preferred, spec);
            return;
        }
        final LivingEntity target = markTarget(player, build, preferred);
        if (target == null) {
            return;
        }
        markOn(player, build, target, false);
    }

    /**
     * 把印記放在 target 身上。bypassLimit：2026-10-28 照 PoE2 0.3.0 起的 Called Shots「bypasses your Mark limit」——
     * 預告射擊放的印記不拿掉你其他的印記（一般放印記照舊同時只能有一個）。
     */
    void markOn(final Player player, final SkillBuild build, final LivingEntity target, final boolean bypassLimit) {
        final SkillParams.Mark params = build.params().mark();
        final long until = plugin.getServer().getCurrentTick() + build.durationTicks(params.duration(build.level()));
        final java.util.List<tw.exilecore.stats.Modifier> mods = new java.util.ArrayList<>();
        for (final ScaledMod effect : params.effects()) {
            mods.add(effect.toModifier(build.level(), build.auraEffect(), build.definition().name()));
        }
        // 2026-10-28 照 PoE2：印記的品質加在印記本身的數值上（霜縛印的凍結累積、引雷印的電擊累積）
        final java.util.List<tw.exilecore.stats.Modifier> merged = build.withQualityEffects(mods, build.auraEffect(), build.definition().name());
        mods.clear();
        mods.addAll(merged);
        // 2026-10-22：同時只能有一個印記，放新的會拿掉舊的（以前引雷印、霜縛印、血嗅印能同時掛在同一隻身上）
        // 2026-10-28 照 PoE2：印記是「上限 1 個被標記的目標」，+x 上限（過剩）讓同一種印記多標記幾個；這次之前一律一個
        final int markLimit = 1 + (build.definition().tags().contains(SkillLimits.TAG) ? SkillLimits.bonus(plugin.players().sheet(player), build) : 0);
        plugin.combat().debuffs().applyMark(target.getUniqueId(), new Debuffs.Debuff(build.definition().id(), player.getUniqueId(), until,
                params.taken() / 100.0 * build.auraEffect(), params.selfOnly(), null, mods, 0), bypassLimit ? 0 : markLimit);
        if (plugin.skills() != null) {
            plugin.skills().supportMechanics().markApplied(player, build, target, until); // 2026-10-26：烙印深、久印
            plugin.skills().spears().marked(player, build, target); // 2026-10-27 照 PoE2 Bloodhound's Mark
            plugin.skills().markActivation().marked(player, build.definition()); // 2026-10-28 照 PoE2：標記新目標時拿掉印記觸發的增益
        }
        visuals.mark(build.definition().visual(), target);
    }
}
