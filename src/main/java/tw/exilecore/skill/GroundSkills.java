package tw.exilecore.skill;

import java.util.ArrayList;
import java.util.List;

/**
 * 2026-11-01 照 PoE2 0.5（Persistent Ground 能掛、我們這邊原本沒留地面的技能）：技能自己留下的地面效果。純資料與純函式，不依賴 Bukkit；
 * 放地面的地方在 {@link StageLeftovers}（點燃地面、荊棘地面）、{@link SkillExecutor}（冰緩地面）、{@link ProjectileEngine}（沿路的點燃地面）。
 * <ul>
 *   <li>滾雷珠（Ball Lightning，Fire-Infused／Ignited Ground）：用掉火灌注時，雷珠飛的路上每 0.35 秒留一灘點燃地面
 *       （0.9 公尺、4 秒，「像造成 22–33 → 744–1116 → 12986–19478 火焰傷害一樣點燃」，{@value #BALL_LIGHTNING_GROUND}）。</li>
 *   <li>焚流（Incinerate，Ignited Ground）：用掉火灌注時，火焰噴到的地方留下點燃地面
 *       （1.4 公尺、8 秒，「像造成 24–36 → 829–1243 → 14470–21705 火焰傷害一樣點燃」，{@value #INCINERATE_GROUND}）。</li>
 *   <li>霜星爆（Ice Nova，Cold-Infused）：用掉冰灌注時，爆炸的地方留下跟爆炸一樣大（4.8 公尺）的冰緩地面 8 秒。</li>
 *   <li>刺棘身（Briarpatch）：儲存你造成的物理攻擊傷害，施放法術時用掉，長出荊棘地面（{@link #store} 等）。</li>
 * </ul>
 * 地面的持續時間都照原技能的 {@link SkillBuild#groundDurationTicks}（久地：Persistent Ground 拉長的就是這些）。
 */
public final class GroundSkills {

    /** FLAT（灌注的加成）：投射物飛的路上每隔幾毫秒留一灘點燃地面（滾雷珠用掉火灌注：350）。 */
    public static final String TRAIL_IGNITED = "ground.ignited_trail";
    /** FLAT 1（灌注的加成）：扇形噴到的地方留下點燃地面（焚流用掉火灌注）。 */
    public static final String CONE_IGNITED = "ground.ignited_cone";
    /** FLAT（灌注的加成）：新星爆炸的地方留下跟爆炸一樣大的冰緩地面，持續幾秒（霜星爆用掉冰灌注：8）。 */
    public static final String NOVA_CHILLED = "ground.chilled_nova";

    /** 存滾雷珠點燃地面數值的隱藏技能（半徑、秒數、逐級的「像造成 X–Y 火焰傷害一樣點燃」）。 */
    public static final String BALL_LIGHTNING_GROUND = "proc_ball_lightning_ground";
    /** 存焚流點燃地面數值的隱藏技能。 */
    public static final String INCINERATE_GROUND = "proc_incinerate_ground";

    /**
     * 霜星爆冰緩地面的冰緩幅度（%）。原作（PoB2、poe2db）沒寫冰緩地面冰緩幾 %，照我們其他冰緩地面（霜牙刺、昇華的冰緩地面）20%。
     */
    public static final double NOVA_CHILL = 20;

    /** 同一個技能留下的點燃地面，新的一灘中心離還在的一灘不到半徑的這個比例：只刷新那一灘（引導一直噴不會疊出一大堆）。 */
    public static final double MERGE_FRACTION = 0.5;
    /** 同一個玩家、同一個技能的點燃地面最多同時幾灘（再多最舊的先消失；效能用，原作沒有上限）。 */
    public static final int MAX_PATCHES = 40;
    /** 同一個玩家的點燃地面，同一隻敵人每幾 tick 最多施加一次易燃（站在好幾灘重疊的地方也一樣；原作地面效果是敵人身上的狀態，不疊）。 */
    public static final int IGNITE_GAP_TICKS = 10;

    // ---- 刺棘身（PoE2 Briarpatch）----

    /** 刺棘身的技能代號。 */
    public static final String BRIARPATCH = "thorn_body";
    /** FLAT（刺棘身的精魂效果）：儲存你造成的物理攻擊傷害的幾 %（briarpatch_stores_permyriad_of_physical_damage_dealt 100 = 1%）。 */
    public static final String BRIAR_STORE = "briarpatch.store";
    /** FLAT（刺棘身的精魂效果，逐級）：最多儲存多少（briarpatch_maximum_stored_physical_damage 9 → 256 → 3424）。 */
    public static final String BRIAR_CAP = "briarpatch.cap";
    /** 荊棘地面的半徑（公尺；active_skill_base_area_of_effect_radius 30）。 */
    public static final double BRIAR_RADIUS_M = 3.0;
    /** 荊棘地面的持續時間（秒；base_skill_effect_duration 6000）。 */
    public static final double BRIAR_SECONDS = 6.0;
    /** 每幾 tick 最多長一片荊棘地面（briarpatch_ground_creation_delay_ms 8000）。 */
    public static final long BRIAR_GAP_TICKS = 160;
    /** 荊棘地面同時最多幾片（base_briarpatch_ground_limit 1）。 */
    public static final int BRIAR_LIMIT = 1;
    /** 在荊棘地面上移動的敵人每幾 tick 最多被打一次（poe2db Thorny Ground：no more than twice per second）。 */
    public static final long BRIAR_HIT_GAP_TICKS = 10;
    /** 荊棘地面的基礎暴擊率（%；Briarpatch critChance 10）。 */
    public static final double BRIAR_CRIT = 10;
    /** 兩次檢查之間（2 tick）水平移動超過幾格算「在上面移動」。 */
    public static final double BRIAR_MOVED = 0.04;

    private GroundSkills() {
    }

    /** 毫秒換成 tick（至少 1）。 */
    static int intervalTicks(final double millis) {
        return (int) Math.max(1, Math.round(millis / 50.0));
    }

    /** 投射物飛了 age tick：這一 tick 要不要留一灘（每 interval 毫秒一灘；第 0 tick 不留）。 */
    static boolean trailDue(final int age, final double millis) {
        return millis > 0 && age > 0 && age % intervalTicks(millis) == 0;
    }

    /**
     * 扇形噴到的地方沿著中線排幾灘（離自己幾格）：第一灘的邊貼著自己（radius），之後每隔一個直徑一灘，中心不超過 length。
     * radius 或 length 不合理回傳空的。
     */
    static List<Double> conePoints(final double length, final double radius) {
        final List<Double> out = new ArrayList<>();
        if (radius <= 0 || length <= 0) {
            return out;
        }
        for (double d = Math.min(radius, length); d <= length + 1e-9 && out.size() < 16; d += 2 * radius) {
            out.add(d);
        }
        return out;
    }

    /** 新的一灘中心離舊的那一灘 distance 格：要不要合成一灘（只刷新時間）。 */
    static boolean merges(final double distance, final double radius) {
        return distance <= radius * MERGE_FRACTION;
    }

    /** 刺棘身：原本存了 stored，這一下造成 physical 點物理攻擊傷害、存 percent%，最多 cap。 */
    static double store(final double stored, final double physical, final double percent, final double cap) {
        if (cap <= 0) {
            return 0;
        }
        final double next = Math.max(0, stored) + Math.max(0, physical) * Math.max(0, percent) / 100.0;
        return Math.min(cap, next);
    }

    /** 刺棘身：上一片在 last tick 長出來（沒有是負的），現在 now 能不能再長一片。 */
    static boolean canGrow(final long last, final long now) {
        return last < 0 || now - last >= BRIAR_GAP_TICKS;
    }

    /** 荊棘地面：這隻敵人兩次檢查之間水平移動了 (dx, dz) 格，算不算在上面移動。 */
    static boolean moved(final double dx, final double dz) {
        return dx * dx + dz * dz > BRIAR_MOVED * BRIAR_MOVED;
    }

    /** 玩家看到的說明（ModifierText 不認得的交給 StageLeftovers.describe，再交給這裡）；不是這裡的回傳 null。 */
    static String describe(final String stat, final double value) {
        final String number = value == Math.rint(value) ? String.valueOf((long) value) : String.format(java.util.Locale.ROOT, "%.2f", value);
        return switch (stat) {
            case TRAIL_IGNITED -> "飛的路上每 " + String.format(java.util.Locale.ROOT, "%.2f", value / 1000.0)
                    + " 秒留下一灘點燃地面（0.9 格、4 秒，照等級像造成 22–33 → 744–1116 火焰傷害一樣點燃）";
            case CONE_IGNITED -> "火焰噴到的地方留下點燃地面（1.4 格、8 秒，照等級像造成 24–36 → 829–1243 火焰傷害一樣點燃）";
            case NOVA_CHILLED -> "爆炸的地方留下跟爆炸一樣大的冰緩地面 " + number + " 秒（冰緩 " + (long) NOVA_CHILL + "%）";
            case BRIAR_STORE -> "儲存你造成的物理攻擊傷害的 " + number + "%；施放法術時用掉，在你腳下長出荊棘地面";
            case BRIAR_CAP -> "最多儲存 " + number + " 點傷害";
            default -> null;
        };
    }
}
