package tw.exilecore.skill;

import org.bukkit.Location;
import org.bukkit.Particle;
import org.bukkit.Sound;
import org.bukkit.SoundCategory;
import org.bukkit.entity.LivingEntity;
import org.bukkit.entity.Player;
import org.bukkit.util.Vector;
import tw.exilecore.ExileCore;
import tw.exilecore.character.PlayerState;
import tw.exilecore.item.ItemRenderer;
import tw.exilecore.stats.StatSheet;

import java.util.List;
import java.util.Set;

/**
 * 2026-10-28 照 PoE2 Hollow Form：空無型態引導中的每一下（{@link Channelling} 的連續出手照 {@link SkillRuntime.Charging#interval} 叫
 * {@link SkillService#perform}，SkillService 交給這裡）：照孔的順序挑一個能用的近戰攻擊，花它 80% 的消耗顯現一個幻影
 * （有銳星就用掉 1 顆、多 2 個），幻影在準心附近（離你 {@link HollowForm#RADIUS} 格內）出現、用那個攻擊打離它最近的敵人一次就消失。
 * 規則在 {@link HollowForm}。這次之前（2026-10-27）在 AscPaths：按一次放、用你最近使用的近戰攻擊。
 */
final class HollowImages {

    /** 準心最遠找多遠（再拉回離你 {@link HollowForm#RADIUS} 格內）。 */
    static final double AIM_RANGE = 22;
    /** 幻影出現在準心旁邊多遠（格）。 */
    static final double SCATTER_MIN = 0.6;
    static final double SCATTER_MAX = 1.8;
    /** 目標離幻影太遠時，幻影改出現在目標旁邊這麼遠（格；近戰打得到）。 */
    static final double BESIDE = 1.2;

    private final ExileCore plugin;
    private final SkillService service;

    HollowImages(final ExileCore plugin, final SkillService service) {
        this.plugin = plugin;
        this.service = service;
    }

    /** 孔裡的攻擊自己的冷卻（幻影共用，跟你自己按的那一顆分開）。 */
    static String cooldownKey(final SkillBuild attack) {
        return attack.definition().id() + "#hollow";
    }

    /** 這一招的動作速度（冰緩）。 */
    private double actionSpeed(final Player player) {
        return plugin.ailments() == null ? 1.0 : Math.max(0.05, plugin.ailments().actionSpeed(player));
    }

    /** 幻影出現的間隔（tick）：孔裡那個攻擊的攻擊間隔 ÷ 0.7（冰緩再拉長）。 */
    double step(final Player player, final SkillBuild attack, final StatSheet sheet) {
        return HollowForm.stepTicks(attack.intervalTicks(sheet) / actionSpeed(player));
    }

    /** 剛開始引導：第一個幻影在一次間隔的 30% 之後。 */
    void started(final Player player, final SkillRuntime runtime, final SkillBuild first, final StatSheet sheet, final long now) {
        final SkillRuntime.Charging charging = runtime.charging;
        if (charging == null) {
            return;
        }
        final double step = step(player, first, sheet);
        charging.interval = step;
        charging.nextBeat = now + HollowForm.startTicks(step);
        runtime.hollowNext = 0;
    }

    /** 引導中的一下：顯現幻影。孔裡沒有能用的、付不起消耗就結束引導。 */
    void beat(final Player player, final SkillRuntime runtime, final PlayerState state, final StatSheet sheet, final SkillBuild host, final long now) {
        final SkillRuntime.Charging charging = runtime.charging;
        if (charging == null) {
            return;
        }
        final List<SkillBuild> socketed = service.hollowSocketed(player);
        if (socketed.isEmpty()) {
            stop(player, runtime, state, "孔裡沒有能用的" + HollowForm.wants(), now);
            return;
        }
        SkillBuild attack = null;
        for (int i = 0; i < socketed.size(); i++) {
            final int index = Math.floorMod(runtime.hollowNext + i, socketed.size());
            if (runtime.cooldownLeft(cooldownKey(socketed.get(index)), now) <= 0) {
                attack = socketed.get(index);
                runtime.hollowNext = index + 1; // 照孔的順序輪流
                break;
            }
        }
        if (attack == null) {
            charging.nextBeat = now + 2; // 孔裡的攻擊都在冷卻：等一下再看
            return;
        }
        attack = attack.rollElement(sheet, plugin.items().random());
        final double step = step(player, attack, sheet);
        charging.interval = step;
        charging.nextBeat = now + Math.max(2, Math.round(step));
        final int cost = HollowForm.imageCost(attack.manaCost());
        final String unpaid = service.payChannel(player, state, attack, cost);
        if (unpaid != null) {
            stop(player, runtime, state, unpaid, now);
            return;
        }
        if (attack.cooldownTicks() > 0) {
            runtime.startCooldown(cooldownKey(attack), now, attack.cooldownTicks());
        }
        // 用掉銳星多 2 個幻影（空無型態自己的恆星、倍星照算；孔裡的攻擊不能消耗戰星）
        final int counted = plugin.combat() == null || plugin.combat().charges() == null ? 0
                : plugin.combat().charges().spend(player, "power", host.profile().effective(sheet), host.profile().tags());
        final int images = HollowForm.images(counted);
        state.markRecent(tw.exilecore.stats.KeystoneKeys.RECENT_ATTACK, now);
        plugin.players().grace().end(player, true);
        final SkillBuild image = HollowForm.imageBuild(attack);
        final Location aim = clamp(player, service.targets().aimPoint(Origin.of(player), AIM_RANGE));
        appear(player, image, aim);
        int made = 1;
        for (int i = 1; i < images; i++) {
            if (service.payChannel(player, state, attack, cost) != null) {
                break; // 多的幻影一個一個付，付不起就少幾個
            }
            made++;
            plugin.getServer().getScheduler().runTaskLater(plugin, () -> appear(player, image, aim), HollowForm.EXTRA_STAGGER * i);
        }
        if (counted > 0) {
            state.notice("<#c7a6ff>" + ItemRenderer.escape(host.definition().name()) + "：用掉一顆銳星，顯現 " + made + " 個幻影", now + 30);
        }
        plugin.players().display(player, state);
    }

    private void stop(final Player player, final SkillRuntime runtime, final PlayerState state, final String why, final long now) {
        runtime.charging = null;
        state.notice("<gray>空無型態：" + ItemRenderer.escape(why) + "，引導結束", now + 30);
        plugin.players().display(player, state);
    }

    /** 拉回離你 {@link HollowForm#RADIUS} 格內（水平）。 */
    private static Location clamp(final Player player, final Location at) {
        final Location feet = player.getLocation();
        if (at.getWorld() != feet.getWorld()) {
            return feet.clone();
        }
        final Vector offset = at.toVector().subtract(feet.toVector()).setY(0);
        final double distance = offset.length();
        if (distance <= HollowForm.RADIUS) {
            return at.clone();
        }
        final Location pulled = feet.clone().add(offset.multiply(HollowForm.RADIUS / distance));
        pulled.setY(at.getY());
        return pulled;
    }

    /** 一個幻影：準心旁邊出現，打離它最近的敵人一次（沒有敵人就朝你面向的方向打）。 */
    private void appear(final Player player, final SkillBuild image, final Location aim) {
        if (!player.isOnline() || aim.getWorld() != player.getWorld() || !service.eligible(player) || service.inTown(player)) {
            return;
        }
        final double angle = plugin.items().random().nextDouble() * Math.PI * 2;
        final double radius = SCATTER_MIN + plugin.items().random().nextDouble() * (SCATTER_MAX - SCATTER_MIN);
        Location spot = clamp(player, aim.clone().add(Math.cos(angle) * radius, 0, Math.sin(angle) * radius));
        final LivingEntity target = service.targets().nearest(spot.clone().add(0, 1, 0), HollowForm.RADIUS, Set.of(), player);
        if (target != null) {
            final Location at = target.getLocation();
            final Vector away = spot.toVector().subtract(at.toVector()).setY(0);
            if (away.length() > BESIDE + 0.3) {
                if (away.lengthSquared() < 1e-6) {
                    away.setX(1);
                }
                final Location beside = at.clone().add(away.normalize().multiply(BESIDE));
                beside.setY(spot.getY());
                spot = clamp(player, beside); // 幻影出現在目標旁邊（近戰打得到）
            }
        }
        final Vector facing = target != null ? SkillTargets.center(target).toVector().subtract(spot.clone().add(0, 1.2, 0).toVector())
                : SkillTargets.facing(player);
        if (facing.lengthSquared() < 1e-6) {
            facing.setX(1);
        }
        final java.util.UUID previousFx = Fx.source(player.getUniqueId());
        try {
            Fx.spawn(spot.getWorld(), Particle.REVERSE_PORTAL, spot.clone().add(0, 1, 0), 30, 0.25, 0.7, 0.25, 0.02);
            Fx.spawn(spot.getWorld(), Particle.SOUL, spot.clone().add(0, 1.6, 0), 4, 0.15, 0.1, 0.15, 0.01);
            spot.getWorld().playSound(spot, Sound.ENTITY_ILLUSIONER_MIRROR_MOVE, SoundCategory.PLAYERS, 0.5f, 1.4f);
            service.recast(player, image, spot, facing.normalize(), target);
        } finally {
            Fx.restore(previousFx);
        }
    }
}
