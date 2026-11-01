package tw.exilecore.stats;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.function.ToDoubleFunction;

/**
 * 2026-11-01 照 PoE2（Path of Building 0.5 的 sup_*.lua）把幾顆近似的輔助換成真正的系統時新增的屬性鍵、常數與純計算
 * （不放進 {@link StatKeys}，方便合併）：靜電（Static Shocks）、磁暴（Electromagnetism）、熔坑擊（Crater）、拖滯（Hinder）。
 * 距離照 PoE2 公尺（1 公尺 = 1 格）。
 */
public final class SupportSystemKeys {

    private SupportSystemKeys() {
    }

    // ---- 靜電（TriggeredStaticShocksPlayer）----

    /** grounding_shocks_maximum_targets_per_aoe 6：每一波最多電幾個敵人（附著的那隻先算，再照距離）。 */
    public static final int STATIC_SHOCK_MAX_TARGETS = 6;

    /**
     * 每一波要電的敵人：first（附著的那隻，可以是 null）排第一，其他照 distance 由近到遠，最多 max 個（max ≤ 0 = 不限）。純函式（測試用）。
     */
    public static <T> List<T> pulseTargets(final List<T> inRange, final T first, final ToDoubleFunction<T> distance, final int max) {
        final List<T> sorted = new ArrayList<>(inRange);
        sorted.sort(Comparator.comparingDouble(distance));
        if (first != null && sorted.remove(first)) {
            sorted.addFirst(first);
        }
        return max > 0 && sorted.size() > max ? new ArrayList<>(sorted.subList(0, max)) : sorted;
    }

    // ---- 觸發技能的儲存次數（靜電：冷卻 0.4 秒、可以存 3 次）----

    /** 存了幾次的冷卻：有沒有一次是好的（slots 是每一次好的 tick）。純函式（測試用）。 */
    public static boolean usesReady(final long[] slots, final long now) {
        for (final long ready : slots) {
            if (ready <= now) {
                return true;
            }
        }
        return false;
    }

    /** 用掉最早好的那一次；照順序一次充一個（同 SkillRuntime.startCooldown）。純函式（測試用）。 */
    public static void useOne(final long[] slots, final long now, final long ticks) {
        if (slots.length == 0) {
            return;
        }
        int use = 0;
        for (int i = 1; i < slots.length; i++) {
            if (slots[i] < slots[use]) {
                use = i;
            }
        }
        long latest = now;
        for (int i = 0; i < slots.length; i++) {
            if (i != use) {
                latest = Math.max(latest, slots[i]);
            }
        }
        slots[use] = latest + Math.max(1, ticks);
    }

    // ---- 磁暴（SupportShockingRiftPlayer／TriggeredShockingRiftPlayer）----

    /** try_consuming_shock_to_trigger_shocking_rift_in_radius_on_shocking_enemy 40：看幾公尺內（也是用掉感電的範圍）。 */
    public static final double RIFT_TRIGGER_RADIUS = 4;
    /** consume_shock_of_X_enemies_to_trigger_shocking_rift 3：範圍內至少幾隻感電中（不在雷隙裡）的敵人。 */
    public static final int RIFT_TRIGGER_COUNT = 3;
    /** active_skill_base_secondary_area_of_effect_radius 25：吸引半徑 2.5 公尺（雷隙半徑 2 公尺寫在 skills.yml 的 nova）。 */
    public static final double RIFT_PULL_RADIUS = 2.5;
    /** number_of_shocking_rifts_allowed 3。 */
    public static final int RIFT_LIMIT = 3;
    /** shocking_rift_number_of_pulses 5（最後一下是「最後一波」，用另一張傷害表）。 */
    public static final int RIFT_PULSES = 5;
    /** shocking_rift_pulse_interval_ms 850。 */
    public static final double RIFT_INTERVAL_MS = 850;
    /** shocking_rift_damage_+%_final_per_pulse_performed 20。 */
    public static final double RIFT_MORE_PER_PULSE = 20;
    /** shocking_rift_speed_+%_per_shock_consumed 25。 */
    public static final double RIFT_SPEED_PER_SHOCK = 25;
    /** shocking_rift_hinder_ms 900：每一波打到的敵人阻礙 0.9 秒（阻礙沒寫多少，照一般的移動速度減少 30%）。 */
    public static final long RIFT_HINDER_TICKS = 18;
    /** 吸引的力道（每次往中心的速度，格／tick）與幾 tick 拉一次：原作沒有數字（PoB2、poe2db 都只有吸引半徑），伺服器自己定的。 */
    public static final double RIFT_PULL_STRENGTH = 0.3;
    public static final long RIFT_PULL_EVERY = 4;

    /** 用掉 consumed 個感電的雷隙每幾 tick 脈衝一次：850 毫秒 ÷（1 ＋ 25% × consumed），至少 1 tick。純函式（測試用）。 */
    public static long riftIntervalTicks(final int consumed) {
        final double ms = RIFT_INTERVAL_MS / (1 + RIFT_SPEED_PER_SHOCK / 100.0 * Math.max(0, consumed));
        return Math.max(1, Math.round(ms / 50.0));
    }

    /** 已經脈衝過 performed 次之後，這一波的傷害倍率（每一波更多 20%：1 ＋ 0.2 × performed）。純函式（測試用）。 */
    public static double riftPulseMore(final int performed) {
        return 1 + RIFT_MORE_PER_PULSE / 100.0 * Math.max(0, performed);
    }

    /** 第 performed＋1 波是不是最後一波（第 5 波）。純函式（測試用）。 */
    public static boolean riftFinalPulse(final int performed) {
        return performed + 1 >= RIFT_PULSES;
    }

    /** 範圍內（含讓牠感電的那隻）感電中、不在雷隙裡的敵人夠不夠開雷隙。純函式（測試用）。 */
    public static boolean riftTriggers(final int shockedOutsideRifts) {
        return shockedOutsideRifts >= RIFT_TRIGGER_COUNT;
    }

    // ---- 熔坑擊（SupportCraterPlayer／TriggeredCraterPlayer）----

    /** proc 事件：被輔助的跳躍技能落地時（在落點）。{@code SupportProcs}。 */
    public static final String LAND = "land";
    /** FLAT：落地時裂出幾道裂隙（support_fissure_on_landing_create_X_fissures_on_impact 3）。{@code SupportProcs}。 */
    public static final String CRATER_FISSURES = "crater.fissures";
    public static final int CRATER_DEFAULT_FISSURES = 3;

    /**
     * count 道裂隙的方向：從面向（fx, fz）開始平均分 360 度（3 道：0、120、240 度）；原作沒寫裂隙的方向，照「從落點往外裂」平均分。
     * 回傳每一道的單位向量 {x, z}。純函式（測試用）。
     */
    public static double[][] fissureDirections(final double fx, final double fz, final int count) {
        final int n = Math.max(1, count);
        final double length = Math.sqrt(fx * fx + fz * fz);
        final double x0 = length < 1e-6 ? 0 : fx / length;
        final double z0 = length < 1e-6 ? 1 : fz / length;
        final double[][] result = new double[n][2];
        for (int i = 0; i < n; i++) {
            final double angle = 2 * Math.PI * i / n;
            result[i][0] = x0 * Math.cos(angle) - z0 * Math.sin(angle);
            result[i][1] = x0 * Math.sin(angle) + z0 * Math.cos(angle);
        }
        return result;
    }

    /** 一道裂隙沿著方向放幾個圓心：從落點往外 length 格，每段不超過 step 格（含頭尾）。回傳離落點的距離。純函式（測試用）。 */
    public static double[] fissureSteps(final double length, final double step) {
        final int steps = Math.max(1, (int) Math.ceil(Math.max(0, length) / Math.max(0.2, step)));
        final double[] result = new double[steps + 1];
        for (int i = 0; i <= steps; i++) {
            result[i] = Math.max(0, length) * i / steps;
        }
        return result;
    }

    /** 落地等多久：至少等這麼多 tick 才算原地落地（衝刺、沒跳起來的）；最多等這麼多 tick（卡在空中也照當下的位置）。 */
    public static final long LAND_GRACE_TICKS = 4;
    public static final long LAND_TIMEOUT_TICKS = 40;

    /** 不是跳躍機制的跳躍技能（往後翻、往前撲）落地了沒：離地過再踩到地、或一直沒離地等過 grace、或逾時。純函式（測試用）。 */
    public static boolean landed(final long elapsed, final boolean grounded, final boolean wasAirborne) {
        if (elapsed >= LAND_TIMEOUT_TICKS) {
            return true;
        }
        return grounded && (wasAirborne || elapsed >= LAND_GRACE_TICKS);
    }

    // ---- 拖滯（Hinder：Damage over Time inflicted by Supported Skills also Hinders enemies）----

    /** FLAG：被輔助的技能造成的混沌持續傷害（混沌減益每一跳、混沌地面每一波）也阻礙敵人。{@code AilmentService}、{@code SkillZones}。 */
    public static final String HINDER_DOT = "hinder.dot";
    /** 阻礙沒寫時間時的一般值（poe2db：4 秒、移動速度減少 30%）；每一跳都刷新。 */
    public static final long HINDER_DOT_TICKS = 80;
}
