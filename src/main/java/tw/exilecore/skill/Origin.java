package tw.exilecore.skill;

import org.bukkit.FluidCollisionMode;
import org.bukkit.Location;
import org.bukkit.World;
import org.bukkit.entity.LivingEntity;
import org.bukkit.entity.Player;
import org.bukkit.util.Vector;

/**
 * 技能從哪裡放出來：玩家自己，或之後的圖騰、陷阱（從它們的位置、朝某個方向）。
 * 傷害永遠算 {@link #owner} 的（命中、暴擊、掉落歸屬都是玩家的）。
 *
 * @param owner     施放者（傷害、魔力、掉落都算他的）
 * @param feet      腳底的位置（範圍、扇形從這裡量）
 * @param eye       眼睛的位置（射線、投射物從這裡出發）
 * @param direction 面向（含上下）
 * @param self      是玩家本人（視線用玩家自己的判斷、落地用玩家的位置）
 */
public record Origin(Player owner, Location feet, Location eye, Vector direction, boolean self) {

    /** 玩家本人。 */
    public static Origin of(final Player player) {
        final Location eye = player.getEyeLocation();
        return new Origin(player, player.getLocation(), eye, eye.getDirection(), true);
    }

    /** 從某個位置朝某個方向（圖騰、陷阱）：眼睛在腳底上 1.2 格。 */
    public static Origin at(final Player owner, final Location feet, final Vector direction) {
        final Vector dir = direction.lengthSquared() < 1e-9 ? new Vector(0, 0, 1) : direction.clone().normalize();
        final Location base = feet.clone();
        base.setDirection(dir);
        return new Origin(owner, base, base.clone().add(0, 1.2, 0), dir, false);
    }

    /** 從某個位置朝某個目標。 */
    public static Origin towards(final Player owner, final Location feet, final LivingEntity target) {
        final Vector to = SkillTargets.center(target).toVector().subtract(feet.clone().add(0, 1.2, 0).toVector());
        return at(owner, feet, to);
    }

    public World world() {
        return feet.getWorld();
    }

    /** 水平的面向（抬頭或低頭到底時用身體的朝向）。 */
    public Vector facing() {
        final Vector flat = direction.clone().setY(0);
        if (flat.lengthSquared() < 1e-6) {
            if (self) {
                return SkillTargets.facing(owner);
            }
            final double yaw = Math.toRadians(feet.getYaw());
            return new Vector(-Math.sin(yaw), 0, Math.cos(yaw));
        }
        return flat.normalize();
    }

    /** 看不看得到這個目標：玩家用自己的視線判斷，圖騰、陷阱從眼睛的位置射一條線。 */
    public boolean canSee(final LivingEntity target) {
        if (self) {
            return owner.hasLineOfSight(target);
        }
        final Vector to = SkillTargets.center(target).toVector().subtract(eye.toVector());
        final double distance = to.length();
        if (distance < 1e-6) {
            return true;
        }
        return world().rayTraceBlocks(eye, to.multiply(1 / distance), distance, FluidCollisionMode.NEVER, true) == null;
    }
}
