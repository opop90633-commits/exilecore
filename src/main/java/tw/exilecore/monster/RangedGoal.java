package tw.exilecore.monster;

import com.destroystokyo.paper.entity.RangedEntity;
import com.destroystokyo.paper.entity.ai.Goal;
import com.destroystokyo.paper.entity.ai.GoalKey;
import com.destroystokyo.paper.entity.ai.GoalType;
import org.bukkit.GameMode;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.NamespacedKey;
import org.bukkit.entity.AbstractSkeleton;
import org.bukkit.entity.EntityType;
import org.bukkit.entity.LivingEntity;
import org.bukkit.entity.Mob;
import org.bukkit.entity.Player;
import org.bukkit.inventory.EquipmentSlot;
import org.bukkit.plugin.Plugin;

import java.util.EnumSet;

/**
 * 2026-10-22 效能：骷髏類（骷髏、流浪者、沼骸…拿弓的）的射箭與走位。
 * <p>
 * 原版的弓箭 AI（RangedBowAttackGoal）在看不到人或太遠時<b>每個 tick</b>都重新尋路，目標一動就整條重算
 * （spark：一百多隻骷髏佔主執行緒快 3%）。這個目標優先權最高、佔住移動與視線，所以原版那個不會再跑；
 * 射箭的節奏照原版（非困難：拉弓 1 秒、射完等 2 秒；沼骸等 3.5 秒），只是：
 * <ul>
 *   <li>尋路：目標離上次尋路的位置超過 2 格、或滿半秒才重算；</li>
 *   <li>視線：每 4 tick 看一次（原版每 tick 射線）。</li>
 * </ul>
 * 手上不是弓（換成近戰武器的）不啟用，原版的近戰照常。
 */
final class RangedGoal implements Goal<Mob> {

    /** 原版的射程（15 格）。 */
    private static final double RANGE = 15;
    private static final double GIVE_UP = 48;
    /** 拉滿弓的時間（原版 20 tick = 力道 1.0）。 */
    private static final int DRAW_TICKS = 20;
    private static final int REPATH_TICKS = 10;
    private static final double REPATH_MOVED = 2;
    private static final int SIGHT_TICKS = 4;

    private final GoalKey<Mob> key;
    private final Plugin plugin;
    private final Mob mob;
    /** 射完之後等多久才再拉弓（原版非困難的間隔）。 */
    private final int baseInterval;
    /** 2026-10-23：照怪物的攻擊速度（極速的、疾風光環）縮短過的間隔；每次開始追目標時重算。 */
    private int interval;
    private int cooldown;
    private int drawing = -1;
    private int seeTime;
    private boolean visible;
    private int sightCheck;
    private int repath;
    private Location pathTarget;

    RangedGoal(final Plugin plugin, final Mob mob) {
        this.key = key(plugin);
        this.plugin = plugin;
        this.mob = mob;
        this.baseInterval = mob.getType() == EntityType.BOGGED ? 70 : 40;
        this.interval = baseInterval;
    }

    static GoalKey<Mob> key(final Plugin plugin) {
        return GoalKey.of(Mob.class, new NamespacedKey(plugin, "ranged"));
    }

    /** 這隻要不要掛這個目標：骷髏類（凋零骷髏拿劍，不算）。 */
    static boolean applies(final LivingEntity entity) {
        return entity instanceof AbstractSkeleton && entity instanceof RangedEntity && entity.getType() != EntityType.WITHER_SKELETON;
    }

    private boolean holdingBow() {
        return mob.getEquipment() != null && mob.getEquipment().getItemInMainHand().getType() == Material.BOW;
    }

    @Override
    public boolean shouldActivate() {
        return holdingBow() && valid(mob.getTarget());
    }

    @Override
    public boolean shouldStayActive() {
        return holdingBow() && valid(mob.getTarget());
    }

    private boolean valid(final LivingEntity target) {
        if (target == null || !target.isValid() || target.isDead() || !target.getWorld().equals(mob.getWorld())) {
            return false;
        }
        if (target instanceof Player player && (player.getGameMode() == GameMode.CREATIVE || player.getGameMode() == GameMode.SPECTATOR
                || (plugin instanceof tw.exilecore.ExileCore core && core.players().grace().isSafe(player)))) {
            return false;
        }
        return target.getLocation().distanceSquared(mob.getLocation()) <= GIVE_UP * GIVE_UP;
    }

    @Override
    public void start() {
        interval = baseInterval;
        if (plugin instanceof tw.exilecore.ExileCore core && core.monsters() != null) {
            final MonsterData data = core.monsters().data(mob).orElse(null);
            if (data != null) {
                final double speed = core.monsters().sheet(mob, data).apply(tw.exilecore.stats.StatKeys.ATTACK_SPEED, 1.0,
                        java.util.Set.of(tw.exilecore.stats.Tags.ATTACK));
                interval = (int) Math.max(10, Math.round(baseInterval / Math.max(0.3, speed)));
            }
        }
        cooldown = 0;
        drawing = -1;
        seeTime = 0;
        sightCheck = 0;
        repath = 0;
        pathTarget = null;
    }

    @Override
    public void stop() {
        stopDrawing();
        mob.getPathfinder().stopPathfinding();
        pathTarget = null;
    }

    @Override
    public void tick() {
        final LivingEntity target = mob.getTarget();
        if (target == null) {
            return;
        }
        if (--sightCheck <= 0) {
            sightCheck = SIGHT_TICKS;
            visible = mob.hasLineOfSight(target);
        }
        if (visible) {
            seeTime = Math.max(0, seeTime) + 1;
        } else {
            seeTime = Math.min(0, seeTime) - 1;
        }
        final Location targetAt = target.getLocation();
        final double distance = targetAt.distanceSquared(mob.getLocation());
        if (distance <= RANGE * RANGE && seeTime >= 20) {
            if (pathTarget != null) {
                mob.getPathfinder().stopPathfinding();
                pathTarget = null;
            }
        } else if (--repath <= 0 || pathTarget == null || !pathTarget.getWorld().equals(targetAt.getWorld())
                || pathTarget.distanceSquared(targetAt) > REPATH_MOVED * REPATH_MOVED) {
            repath = REPATH_TICKS;
            pathTarget = targetAt;
            mob.getPathfinder().moveTo(target, 1.0);
        }
        mob.lookAt(target);
        if (drawing >= 0) {
            if (!visible && seeTime < -60) {
                stopDrawing();
                cooldown = interval;
            } else if (visible && ++drawing >= DRAW_TICKS) {
                stopDrawing();
                ((RangedEntity) mob).rangedAttack(target, 1.0f);
                cooldown = interval;
            }
        } else if (--cooldown <= 0 && seeTime >= -60) {
            drawing = 0;
            ((RangedEntity) mob).setChargingAttack(true);
            mob.startUsingItem(EquipmentSlot.HAND); // 拉弓的動作（客戶端看得到）
        }
    }

    private void stopDrawing() {
        if (drawing >= 0) {
            drawing = -1;
            ((RangedEntity) mob).setChargingAttack(false);
            mob.clearActiveItem();
        }
    }

    @Override
    public GoalKey<Mob> getKey() {
        return key;
    }

    @Override
    public EnumSet<GoalType> getTypes() {
        return EnumSet.of(GoalType.MOVE, GoalType.LOOK);
    }
}
