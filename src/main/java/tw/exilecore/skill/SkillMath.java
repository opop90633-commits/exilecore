package tw.exilecore.skill;

import java.util.ArrayList;
import java.util.List;

/**
 * 技能用到的數學：動作間隔、扇形、投射物散射、跳躍的初速度。純函式，不依賴 Bukkit，都有單元測試。
 */
public final class SkillMath {

    /** 動作之間最少幾 tick（每秒最多 10 次）。 */
    public static final double MIN_INTERVAL_TICKS = 2;

    /** 原版玩家的物理：每 tick 重力、垂直阻力、空中水平阻力、從一般地面起跳那一 tick 的水平阻力（方塊摩擦 0.6 × 0.91）。 */
    public static final double GRAVITY = 0.08;
    public static final double DRAG_Y = 0.98;
    public static final double DRAG_XZ = 0.91;
    public static final double DRAG_GROUND = 0.6 * 0.91;

    /** 原版 block_interaction_range 的上限（按住左鍵連續施放時的方塊互動距離，見 HoldToCast）。 */
    public static final double HOLD_CAST_REACH = 64.0;
    /** 原版生存模式的方塊互動距離（開門、開箱子只在這個距離內算）。 */
    public static final double VANILLA_BLOCK_REACH = 4.5;
    /** 按住左鍵時兩次揮手最多隔幾 tick（客戶端每 tick 揮一次；伺服器偶爾一個 tick 收到兩下、下一個 tick 沒有）。 */
    public static final int HOLD_SWING_GAP = 3;
    /** 右鍵、丟東西帶出來的揮手：同一個或下一個 tick 的「左鍵點空氣」不算（兩個封包偶爾會分到相鄰的兩個 tick）。 */
    public static final int STRAY_SWING_WINDOW = 1;

    private SkillMath() {
    }

    /** 一個點到方塊（x..x+1、y..y+1、z..z+1）最近的距離的平方（點在方塊裡是 0）。 */
    public static double distanceSquaredToBox(final double px, final double py, final double pz, final int x, final int y, final int z) {
        final double dx = px - Math.max(x, Math.min(x + 1, px));
        final double dy = py - Math.max(y, Math.min(y + 1, py));
        final double dz = pz - Math.max(z, Math.min(z + 1, pz));
        return dx * dx + dy * dy + dz * dz;
    }

    /** 眼睛到方塊在原版的距離內（寬 0.5 格，跟原版伺服器的容許值差不多）。 */
    public static boolean withinVanillaReach(final double eyeX, final double eyeY, final double eyeZ, final int x, final int y, final int z) {
        final double reach = VANILLA_BLOCK_REACH + 0.5;
        return distanceSquaredToBox(eyeX, eyeY, eyeZ, x, y, z) <= reach * reach;
    }

    /**
     * 揮手算不算按住左鍵的那一下：客戶端開始「挖」方塊（生存模式的左鍵點方塊）之後、還沒放開，而且上一下沒隔太久。
     * 右鍵用工具（斧頭削樹皮、鋤頭鋤地、鏟子鏟出小路）、開門、丟東西也會讓客戶端揮手，那些都不算。
     *
     * @param held     正在按住（開始挖之後還沒收到停止挖）
     * @param heldTick 開始挖或上一次算進去的揮手是哪個 tick
     */
    public static boolean heldSwing(final boolean held, final long heldTick, final long now) {
        return held && now >= heldTick && now - heldTick <= HOLD_SWING_GAP;
    }

    /** 伺服器從揮手推出來的「左鍵點空氣」算不算左鍵：右鍵、丟東西的同一個或下一個 tick 不算。 */
    public static boolean airClick(final long lastRightTick, final long lastDropTick, final long now) {
        return now - lastRightTick > STRAY_SWING_WINDOW && now - lastDropTick > STRAY_SWING_WINDOW;
    }

    /** 攻擊的間隔（tick）：20 ÷（每秒攻擊次數 × 技能的攻速倍率）。 */
    public static double attackInterval(final double attacksPerSecond, final double skillMultiplier) {
        final double rate = Math.max(0.05, attacksPerSecond * skillMultiplier);
        return Math.max(MIN_INTERVAL_TICKS, 20.0 / rate);
    }

    /** 法術的間隔（tick）：基礎施法時間 ÷ 施法速度倍率。 */
    public static double castInterval(final double castTimeSeconds, final double castSpeedMultiplier) {
        return Math.max(MIN_INTERVAL_TICKS, castTimeSeconds * 20.0 / Math.max(0.05, castSpeedMultiplier));
    }

    /**
     * 下一次可以動作的時間。連續動作（這一下剛好在可以動的那個 tick）時接著上一次的時間算，
     * 小數不會因為四捨五入到整數 tick 而累積誤差；停過一陣子就從現在開始算。
     */
    public static double nextReady(final double readyAt, final long now, final double interval) {
        final double base = now - readyAt < 1.0 ? Math.min(readyAt, now) : now;
        return base + interval;
    }

    /**
     * 多發投射物的方向：以 (dx, dy, dz) 為中心，在水平方向平均排開成 spread 度的扇形，仰角不變。
     * count = 1 時就是原方向。回傳的方向都是單位向量。
     */
    public static List<double[]> fan(final double dx, final double dy, final double dz, final int count, final double spreadDegrees) {
        final double length = Math.sqrt(dx * dx + dy * dy + dz * dz);
        final double ux = length == 0 ? 0 : dx / length;
        final double uy = length == 0 ? 0 : dy / length;
        final double uz = length == 0 ? 1 : dz / length;
        final List<double[]> list = new ArrayList<>();
        if (count <= 1) {
            list.add(new double[] {ux, uy, uz});
            return list;
        }
        final boolean fullCircle = spreadDegrees >= 360 - 1e-9;
        for (int i = 0; i < count; i++) {
            // 整圈（散射）：平均分一圈，第一支往前；不然在扇形裡平均排開（兩端各一支）
            final double degrees = fullCircle ? 360.0 * i / count : -spreadDegrees / 2 + spreadDegrees * i / (count - 1);
            final double angle = Math.toRadians(degrees);
            final double cos = Math.cos(angle);
            final double sin = Math.sin(angle);
            list.add(new double[] {ux * cos - uz * sin, uy, ux * sin + uz * cos});
        }
        return list;
    }

    /** 回歸的投射物這一 tick 會不會經過施放者身邊：距離不到一步多一點（至少 1 格）就算到了，不會一直在旁邊打轉。 */
    public static boolean returnArrived(final double distance, final double speed) {
        return distance <= Math.max(1.0, speed * 0.75);
    }

    /** 水平面上，點 (px, pz) 到線段 (ax, az)–(bx, bz) 的距離。 */
    public static double pointSegmentDistance(final double px, final double pz, final double ax, final double az,
                                              final double bx, final double bz) {
        final double dx = bx - ax;
        final double dz = bz - az;
        final double lengthSquared = dx * dx + dz * dz;
        double t = lengthSquared < 1e-12 ? 0 : ((px - ax) * dx + (pz - az) * dz) / lengthSquared;
        t = Math.max(0, Math.min(1, t));
        final double cx = ax + dx * t;
        final double cz = az + dz * t;
        return Math.sqrt((px - cx) * (px - cx) + (pz - cz) * (pz - cz));
    }

    /**
     * 目標是不是在水平扇形裡：面向 (fx, fz)，目標相對位置 (tx, tz)，扇形張角 angle 度。
     * 貼得很近（0.75 格內）的一律算在裡面。
     */
    public static boolean inCone(final double fx, final double fz, final double tx, final double tz, final double angleDegrees) {
        final double targetLength = Math.sqrt(tx * tx + tz * tz);
        if (targetLength < 0.75 || angleDegrees >= 360) {
            return true;
        }
        final double facingLength = Math.sqrt(fx * fx + fz * fz);
        if (facingLength == 0) {
            return true;
        }
        final double cos = (fx * tx + fz * tz) / (facingLength * targetLength);
        return cos >= Math.cos(Math.toRadians(angleDegrees / 2));
    }

    /** 跳躍要幾 tick：越遠越久，10 到 15 tick。 */
    public static int leapTicks(final double horizontalDistance) {
        return (int) Math.max(10, Math.min(15, Math.round(8 + horizontalDistance * 0.6)));
    }

    /**
     * 跳躍的初速度：照原版玩家的物理（先移動、再扣重力與阻力），ticks 之後剛好到 (dx, dy, dz)。
     * 從地面起跳時第一 tick 的水平阻力是地面摩擦（{@link #DRAG_GROUND}），之後是空中阻力。
     */
    public static double[] leapVelocity(final double dx, final double dy, final double dz, final int ticks, final boolean fromGround) {
        final double horizontal = horizontalFactor(ticks, fromGround ? DRAG_GROUND : DRAG_XZ);
        final double vertical = (1 - Math.pow(DRAG_Y, ticks)) / (1 - DRAG_Y);
        final double vy = (dy + GRAVITY * DRAG_Y / (1 - DRAG_Y) * (ticks - vertical)) / vertical;
        return new double[] {dx / horizontal, vy, dz / horizontal};
    }

    /** 初速 1 在 ticks 之內水平走多遠：第一 tick 走 1，之後乘 firstDrag，再每 tick 乘空中阻力。 */
    private static double horizontalFactor(final int ticks, final double firstDrag) {
        if (ticks <= 0) {
            return 1;
        }
        return 1 + firstDrag * (1 - Math.pow(DRAG_XZ, ticks - 1)) / (1 - DRAG_XZ);
    }

    /** 照原版物理模擬 ticks 之後的位移（測試用）。 */
    public static double[] simulateLeap(final double[] velocity, final int ticks, final boolean fromGround) {
        double vx = velocity[0];
        double vy = velocity[1];
        double vz = velocity[2];
        double x = 0;
        double y = 0;
        double z = 0;
        for (int t = 0; t < ticks; t++) {
            x += vx;
            y += vy;
            z += vz;
            final double drag = t == 0 && fromGround ? DRAG_GROUND : DRAG_XZ;
            vx *= drag;
            vz *= drag;
            vy = (vy - GRAVITY) * DRAG_Y;
        }
        return new double[] {x, y, z};
    }

    // ---- 瞬影擊 ----

    /** 瞬影擊的落點離目標碰撞箱邊緣多遠（玩家半寬 0.3 再留 0.45 的縫，不會卡進目標裡）。 */
    public static final double FLICKER_GAP = 0.75;
    /** 落點跟玩家現在的位置差不到這麼多就不瞬移（已經貼著目標）。 */
    public static final double FLICKER_MIN_MOVE = 0.6;

    /**
     * 瞬影擊的落點候選（水平座標 {x, z}）：從目標中心往「目標→玩家」的方向走到玩家的碰撞箱剛好在目標的碰撞箱外面
     * （兩個都是正方形：斜的方向要走比較遠，邊緣才隔得開 {@link #FLICKER_GAP} − 0.3），
     * 先試面向玩家的那一側，再往左右轉 45°、90°、135°，最後是背面（被牆、坑擋住時換一邊站）。
     * 玩家就站在目標中心上（方向算不出來）時，從 +x 開始轉。
     *
     * @param halfWidth 目標碰撞箱的半寬
     */
    public static List<double[]> flickerSpots(final double px, final double pz, final double tx, final double tz, final double halfWidth) {
        double dx = px - tx;
        double dz = pz - tz;
        final double length = Math.sqrt(dx * dx + dz * dz);
        if (length < 1e-6) {
            dx = 1;
            dz = 0;
        } else {
            dx /= length;
            dz /= length;
        }
        final double edge = Math.max(0, halfWidth) + FLICKER_GAP;
        final List<double[]> spots = new ArrayList<>(8);
        for (final double degrees : new double[] {0, 45, -45, 90, -90, 135, -135, 180}) {
            final double radians = Math.toRadians(degrees);
            final double cos = Math.cos(radians);
            final double sin = Math.sin(radians);
            final double rx = dx * cos - dz * sin;
            final double rz = dx * sin + dz * cos;
            final double distance = edge / Math.max(Math.abs(rx), Math.abs(rz)); // 正方形的邊：x 或 z 有一邊離開 edge
            spots.add(new double[] {tx + rx * distance, tz + rz * distance});
        }
        return spots;
    }

    /** 冷卻中的技能能不能消耗一顆戰星直接放（cooldown_bypass）：還在冷卻、技能允許、身上有那種戰星。 */
    public static boolean bypassCooldown(final long cooldownLeft, final String bypassCharge, final int held) {
        return cooldownLeft > 0 && bypassCharge != null && held > 0;
    }
}
