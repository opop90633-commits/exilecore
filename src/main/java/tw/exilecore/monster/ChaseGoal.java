package tw.exilecore.monster;

import com.destroystokyo.paper.entity.ai.Goal;
import com.destroystokyo.paper.entity.ai.GoalKey;
import com.destroystokyo.paper.entity.ai.GoalType;
import org.bukkit.GameMode;
import org.bukkit.NamespacedKey;
import org.bukkit.entity.EntityType;
import org.bukkit.entity.LivingEntity;
import org.bukkit.entity.Mob;
import org.bukkit.entity.Player;
import org.bukkit.plugin.Plugin;

import java.util.EnumSet;
import java.util.Set;

/**
 * 白天不打人的怪物（原版的溺屍白天在陸地上不追、蜘蛛在亮處不主動攻擊、終界使者不被看就不理人）也要照 PoE 的規矩：
 * 看到玩家就追上去近戰。劇情世界與地圖世界的時間是固定的白天，沒有這個它們會一直發呆。
 * 只加在我們的怪物身上（等級化時與區塊重新載入時），優先權最高，有目標時才啟用。
 */
final class ChaseGoal implements Goal<Mob> {

    /** 需要這個目標的原版生物。 */
    static final Set<EntityType> TYPES = EnumSet.of(EntityType.DROWNED, EntityType.SPIDER, EntityType.CAVE_SPIDER, EntityType.ENDERMAN);
    private static final double SIGHT = 24;
    private static final double GIVE_UP = 48;

    private final GoalKey<Mob> key;
    private final Plugin plugin;
    private final Mob mob;
    /** 2026-10-23 效能：兩次尋路至少隔幾 tick（目標一直在跑也不會每 4 tick 重算整條路）。 */
    private static final int MIN_GAP = 6;
    private int cooldown;
    private int repath;
    private int sincePath;
    /** 2026-10-22 效能：上次尋路時目標的位置（目標沒走遠就不重算整條路，溺屍的水陸尋路特別貴）。 */
    private org.bukkit.Location pathTarget;
    /** 2026-10-22 效能：沒有目標時多久找一次人（原版每 tick 都問一次要不要啟用）。 */
    private int scan;

    ChaseGoal(final Plugin plugin, final Mob mob) {
        this.key = GoalKey.of(Mob.class, new NamespacedKey(plugin, "chase"));
        this.plugin = plugin;
        this.mob = mob;
    }

    static GoalKey<Mob> key(final Plugin plugin) {
        return GoalKey.of(Mob.class, new NamespacedKey(plugin, "chase"));
    }

    @Override
    public boolean shouldActivate() {
        LivingEntity target = mob.getTarget();
        if (!valid(target, GIVE_UP)) {
            if (--scan > 0) {
                return false;
            }
            scan = 10; // 半秒找一次（地圖世界幾十個人，每隻怪每 tick 掃一遍太多）
            target = null;
            double best = SIGHT * SIGHT;
            for (final Player player : mob.getLocation().getNearbyPlayers(SIGHT)) {
                if (!valid(player, SIGHT)) {
                    continue;
                }
                final double distance = player.getLocation().distanceSquared(mob.getLocation());
                if (distance < best && mob.hasLineOfSight(player)) {
                    best = distance;
                    target = player;
                }
            }
            if (target == null) {
                return false;
            }
            mob.setTarget(target);
        }
        return true;
    }

    @Override
    public boolean shouldStayActive() {
        return valid(mob.getTarget(), GIVE_UP);
    }

    private boolean valid(final LivingEntity target, final double range) {
        if (target == null || !target.isValid() || target.isDead() || !target.getWorld().equals(mob.getWorld())) {
            return false;
        }
        if (target instanceof Player player && (player.getGameMode() == GameMode.CREATIVE || player.getGameMode() == GameMode.SPECTATOR
                || (plugin instanceof tw.exilecore.ExileCore core && core.players().grace().isSafe(player)))) {
            return false; // 入場保護中的人不追
        }
        return target.getLocation().distanceSquared(mob.getLocation()) <= range * range;
    }

    @Override
    public void start() {
        repath = 0;
        sincePath = MIN_GAP;
        pathTarget = null;
    }

    @Override
    public void stop() {
        mob.getPathfinder().stopPathfinding();
    }

    @Override
    public void tick() {
        final LivingEntity target = mob.getTarget();
        if (target == null) {
            return;
        }
        mob.lookAt(target);
        sincePath++;
        final double reach = mob.getWidth() * 2 + target.getWidth();
        final double distance = target.getLocation().distanceSquared(mob.getLocation());
        final boolean inReach = distance <= reach * reach + 1;
        // 2026-10-22 效能：目標離上次尋路的位置超過 1.5 格、或滿一秒才重算（以前固定每半秒整條重算）
        // 2026-10-23 效能（spark：100 人時尋路佔主執行緒 7%）：目標在跑時 1.5 格大概 4 tick 就超過，等於每 4 tick 重算一次；
        // 現在兩次尋路至少隔 6 tick（目標 16 格以外隔 12 tick），已經貼著目標的不重算（直接打），秒數加一點亂數讓各隻怪錯開
        final org.bukkit.Location targetAt = target.getLocation();
        final int gap = distance > 256 ? MIN_GAP * 2 : MIN_GAP;
        final boolean moved = pathTarget == null || !pathTarget.getWorld().equals(targetAt.getWorld())
                || pathTarget.distanceSquared(targetAt) > 2.25;
        if (!(inReach && mob.hasLineOfSight(target)) && (--repath <= 0 || (moved && sincePath >= gap))) {
            repath = 20 + mob.getEntityId() % 7;
            sincePath = 0;
            pathTarget = targetAt;
            mob.getPathfinder().moveTo(target, 1.2);
        }
        if (cooldown > 0) {
            cooldown--;
            return;
        }
        if (inReach && mob.hasLineOfSight(target)) {
            mob.swingMainHand();
            mob.attack(target);
            cooldown = 20;
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
