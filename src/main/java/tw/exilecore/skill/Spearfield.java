package tw.exilecore.skill;

import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.Particle;
import org.bukkit.Sound;
import org.bukkit.SoundCategory;
import org.bukkit.entity.Display;
import org.bukkit.entity.ItemDisplay;
import org.bukkit.entity.LivingEntity;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;
import org.bukkit.util.Transformation;
import org.bukkit.util.Vector;
import org.joml.Quaternionf;
import org.joml.Vector3f;
import tw.exilecore.ExileCore;
import tw.exilecore.combat.SkillDamage;
import tw.exilecore.stats.SkillProfile;
import tw.exilecore.stats.SupportExtraKeys;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * 2026-10-27 照 PoE2 0.5 Spearfield（矛陣；RePoE poe2 SpearfieldPlayer、poe2db）：往地上一刺，面前扇形（長度照技能等級 4～9.9 格，
 * skills.yml 的 cone.length，吃範圍）冒出一片插在地上的矛——每 0.2 格長度一支、同時最多 70 支、插 10 秒（吃持續時間）。
 * 敵人碰到就炸開（0.6 格，吃範圍），造成這個技能的傷害並癱瘓；放下 0.5 秒內被敵人碰炸的傷害更少 80%。
 * 矛是危險物件（hazard），遲爆（Delayed Reaction）、再布（Rearm）照原作作用在矛上（跟寒凝彈的冰晶、蒺藜步的鐵蒺同一套）：
 * 遲爆＝矛插的時間更少 70%、敵人碰到不會炸，只在插下的那一刻（那時就有敵人，算被敵人碰炸，吃「0.5 秒內更少 80%」）或時間到時炸，更多 x% 傷害；
 * 再布＝炸開後 x% 機率過 1 秒在原地重新插一支（重新計時）。
 * 這次之前矛陣是準心處一塊持續打的範圍（每 0.5 秒打 45%、5 秒）。
 */
final class Spearfield {

    static final String SIGNATURE = "spearfield";
    /** 每 0.2 格扇形長度一支矛（spear_wall_cm_per_spear 20）。 */
    static final double METRES_PER_SPEAR = tw.exilecore.core.Units.metres(0.2); // 2026-10-29 照站長：1 公尺 = 1.5 格（Units），照 PoE2 公尺的常數也換算（扇形長度已是格，支數維持原作）
    /** 同時最多 70 支（spear_wall_maximum_active_spears）。 */
    static final int MAX_SPEARS = 70;
    /** 矛插 10 秒（base_skill_effect_duration 10000）。 */
    static final long DURATION_TICKS = 200;
    /** 炸開的半徑 0.6 格（active_skill_base_secondary_area_of_effect_radius 6）。 */
    static final double BLAST = tw.exilecore.core.Units.metres(0.6); // 2026-10-29 照站長：1 公尺 = 1.5 格（Units），照 PoE2 公尺的常數也換算
    /** 敵人「碰到」矛：碰撞箱離矛 0.3 格內。 */
    static final double TRIGGER = 0.3;
    /** 放下 0.5 秒內被敵人碰炸：傷害更少 80%（hazard_damage_+%_final_for_first_500_ms_when_not_killed_by_self −80）。 */
    static final long EARLY_TICKS = 10;
    static final double EARLY_LESS = 0.8;
    /** 冒出來的矛離自己至少多遠（不要插在自己腳下）。 */
    static final double MIN_DISTANCE = 0.8;

    /** 一支矛：delayed＝遲爆（只在放下的那一刻有敵人或時間到時炸）；rearm＝再布的機率 %；armedAt＝重新布置後幾時能碰。 */
    private record Spear(Location at, long placed, long until, long armedAt, long duration, SkillProfile profile, double blast,
                         ItemDisplay display, boolean delayed, double rearm) {
    }

    private final ExileCore plugin;
    private final SkillService service;
    private final Map<UUID, ArrayDeque<Spear>> spears = new HashMap<>();

    Spearfield(final ExileCore plugin, final SkillService service) {
        this.plugin = plugin;
        this.service = service;
    }

    /** 扇形長度 length 格冒出幾支矛（每 0.2 格一支，最多 70）。 */
    static int count(final double length) {
        return Math.max(1, Math.min(MAX_SPEARS, (int) Math.floor(length / METRES_PER_SPEAR + 1e-6)));
    }

    /** 矛炸開時的傷害倍率：放下 0.5 秒內被敵人碰炸更少 80%；遲爆時間到自己炸的不算。 */
    static double damageScale(final long placed, final long now, final boolean byEnemy) {
        return byEnemy && now - placed < EARLY_TICKS ? 1 - EARLY_LESS : 1;
    }

    /** 矛插多久（tick）：10 秒 × 持續時間倍率；遲爆更少 70%。 */
    static long duration(final double durationScale, final boolean delayed) {
        return Math.max(1, Math.round(DURATION_TICKS * durationScale * (delayed ? SupportExtraKeys.DELAYED_REACTION_DURATION : 1)));
    }

    /** 放出：面前扇形冒出一片矛。 */
    void place(final Player player, final SkillBuild build, final long now) {
        final String id = build.definition().id();
        final double delayed = service.supportFlat(player, id, SupportExtraKeys.DELAYED_REACTION);
        // 2026-10-28 照 PoE2 Spearfield 的品質：危害 (0—10)% 機率重新布置（跟 Rearm 輔助的機率相加）
        final double rearm = service.supportFlat(player, id, SupportExtraKeys.REARM) + build.qualityFlat(tw.exilecore.stats.GemQualityKeys.HAZARD_REARM);
        final SkillProfile profile = delayed > 0 ? build.profile().scaled(1 + delayed / 100.0, "遲爆") : build.profile();
        final long duration = duration(build.durationScale(), delayed > 0);
        final double length = Math.max(MIN_DISTANCE + 0.5, build.length());
        final double half = Math.toRadians(Math.max(10, build.angle()) / 2);
        final double blast = BLAST * Math.max(0.1, build.areaScale());
        final Vector facing = SkillTargets.facing(player);
        final Location origin = player.getLocation();
        final ArrayDeque<Spear> mine = spears.computeIfAbsent(player.getUniqueId(), k -> new ArrayDeque<>());
        final int count = count(length);
        final List<Spear> instant = new ArrayList<>();
        for (int i = 0; i < count; i++) {
            final double angle = -half + 2 * half * (i + plugin.random().nextDouble()) / count; // 平均散在扇形裡
            final double distance = MIN_DISTANCE + (length - MIN_DISTANCE) * Math.sqrt(plugin.random().nextDouble());
            final Vector dir = facing.clone().rotateAroundY(angle).multiply(distance);
            final Location at = ground(origin.clone().add(dir));
            if (at == null) {
                continue; // 牆裡、懸空太深的地方不插
            }
            while (mine.size() >= MAX_SPEARS) {
                removeDisplay(mine.removeFirst().display());
            }
            final Spear spear = new Spear(at, now, now + duration, now, duration, profile, blast, display(at), delayed > 0, rearm);
            // 遲爆：敵人碰到不會觸發，除非放下的那一刻就有敵人
            if (delayed > 0 && !service.targets().around(at, TRIGGER, player).isEmpty()) {
                instant.add(spear);
                continue;
            }
            mine.addLast(spear);
        }
        ServerSwing.mainHand(player);
        player.getWorld().playSound(origin, Sound.BLOCK_POINTED_DRIPSTONE_LAND, SoundCategory.PLAYERS, 1f, 0.8f);
        Fx.spawn(player.getWorld(), Particle.CRIT, origin.clone().add(facing.clone().multiply(length / 2)).add(0, 0.3, 0), 20,
                length / 4, 0.1, length / 4, 0.05);
        for (final Spear spear : instant) {
            explode(player, mine, spear, now, true);
        }
    }

    /** 每 tick：時間到的拿掉（遲爆的時間到才炸），敵人碰到的炸開。 */
    void tick(final Player player, final long now) {
        final ArrayDeque<Spear> mine = spears.get(player.getUniqueId());
        if (mine == null || mine.isEmpty()) {
            return;
        }
        final List<Spear> boom = new ArrayList<>();
        final List<Spear> expired = new ArrayList<>();
        final boolean check = now % 2 == 0;
        for (final Iterator<Spear> it = mine.iterator(); it.hasNext(); ) {
            final Spear spear = it.next();
            final boolean sameWorld = spear.at().getWorld() != null && spear.at().getWorld().equals(player.getWorld());
            if (!sameWorld || now >= spear.until()) {
                it.remove();
                removeDisplay(spear.display());
                if (sameWorld && spear.delayed()) {
                    expired.add(spear); // 遲爆：時間到才炸
                }
                continue;
            }
            if (spear.delayed() || now < spear.armedAt() || !check) {
                continue;
            }
            if (!service.targets().around(spear.at(), TRIGGER, player).isEmpty()) {
                it.remove();
                boom.add(spear);
            }
        }
        for (final Spear spear : expired) {
            explode(player, mine, spear, now, false);
        }
        for (final Spear spear : boom) {
            explode(player, mine, spear, now, true);
        }
    }

    /** 炸開（0.6 格）；再布：x% 機率過 1 秒在原地重新布置（重新計時）。 */
    private void explode(final Player player, final ArrayDeque<Spear> mine, final Spear spear, final long now, final boolean byEnemy) {
        removeDisplay(spear.display());
        final SkillDamage damage = plugin.combat() == null ? null : plugin.combat().skillDamage();
        if (damage != null && spear.at().getWorld() != null) {
            final double scale = damageScale(spear.placed(), now, byEnemy);
            final SkillProfile profile = scale < 1 ? spear.profile().scaled(scale, "矛陣（剛插下就被碰炸）") : spear.profile();
            for (final LivingEntity victim : service.targets().around(spear.at(), spear.blast(), player)) {
                damage.hit(player, victim, profile, null);
            }
            Fx.spawn(spear.at().getWorld(), Particle.CRIT, spear.at().clone().add(0, 0.4, 0), 8, 0.25, 0.3, 0.25, 0.15);
            spear.at().getWorld().playSound(spear.at(), Sound.BLOCK_POINTED_DRIPSTONE_BREAK, SoundCategory.PLAYERS, 0.6f, 1.2f);
        }
        if (spear.rearm() > 0 && plugin.random().nextDouble() * 100 < spear.rearm() && mine.size() < MAX_SPEARS) {
            final long armed = now + SupportExtraKeys.REARM_TICKS;
            mine.addLast(new Spear(spear.at(), now, armed + spear.duration(), armed, spear.duration(), spear.profile(), spear.blast(),
                    display(spear.at()), spear.delayed(), spear.rearm()));
        }
    }

    /** 這個玩家現在插著幾支（測試、技能面板）。 */
    int active(final UUID player) {
        final ArrayDeque<Spear> mine = spears.get(player);
        return mine == null ? 0 : mine.size();
    }

    void forget(final UUID player) {
        final ArrayDeque<Spear> mine = spears.remove(player);
        if (mine != null) {
            for (final Spear spear : mine) {
                removeDisplay(spear.display());
            }
        }
    }

    /** 落在地面上：腳下是實心就往上找一格，懸空就往下找（最多 3 格）；找不到回傳 null。 */
    private static Location ground(final Location at) {
        if (at.getWorld() == null) {
            return null;
        }
        final Location probe = at.clone();
        probe.setY(Math.floor(at.getY()));
        if (!probe.getBlock().isPassable()) {
            probe.add(0, 1, 0);
            if (!probe.getBlock().isPassable()) {
                return null;
            }
        }
        for (int i = 0; i < 3 && probe.clone().add(0, -1, 0).getBlock().isPassable(); i++) {
            probe.add(0, -1, 0);
        }
        return probe.clone().add(0, 0.05, 0);
    }

    private static ItemDisplay display(final Location at) {
        if (at.getWorld() == null) {
            return null;
        }
        return at.getWorld().spawn(at, ItemDisplay.class, d -> {
            d.setItemStack(new ItemStack(Material.POINTED_DRIPSTONE));
            d.setPersistent(false);
            d.setBillboard(Display.Billboard.VERTICAL);
            d.setTransformation(new Transformation(new Vector3f(0, 0.35f, 0), new Quaternionf(), new Vector3f(0.7f, 0.7f, 0.7f), new Quaternionf()));
        });
    }

    private static void removeDisplay(final ItemDisplay display) {
        if (display != null && display.isValid()) {
            display.remove();
        }
    }
}
