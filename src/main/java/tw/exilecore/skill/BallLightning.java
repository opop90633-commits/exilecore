package tw.exilecore.skill;

/**
 * 2026-11-01 照 PoE2 0.5 Ball Lightning（滾雷珠，PoB2 BallLightningPlayer 三組 statSet）。純資料與純函式，不依賴 Bukkit；
 * 雷珠的飛行、放電、爆炸在 {@link ProjectileEngine}。
 * <ul>
 *   <li>雷珠本身不打人（「The Projectile itself does not Hit enemies」），一路穿過敵人；
 *       每 0.2 秒（ball_lightning_base_hit_frequency_ms 200）對 1.8 公尺內（active_skill_base_area_of_effect_radius 18）的每一個敵人放一道雷。
 *       這次之前是會打中敵人的貫穿投射物，打中時再電到附近 3 個（40%）。</li>
 *   <li>用掉火灌注（active_skill_consumes_a_fire_infusion）：越飛越慢（ball_lightning_infused_acceleration −25：每秒速度更少 25%）、
 *       飛的路上每 0.35 秒留一灘點燃地面（{@link GroundSkills#TRAIL_IGNITED}）、消失時在原地爆開 2.6 公尺
 *       （active_skill_base_secondary_area_of_effect_radius 26）的火焰，傷害是自己的逐級表（Fire-Infused statSet：
 *       19–29 → 570–856 → 7645–11468，暴擊 7%，{@value #EXPLOSION}）。這次之前是獲得 50% 傷害為額外火焰、範圍增加 50%。</li>
 * </ul>
 */
public final class BallLightning {

    /** 滾雷珠的技能代號。 */
    public static final String ID = "rolling_thunder_orb";
    /** 每幾 tick 放一次雷（200 毫秒）。 */
    public static final int ZAP_TICKS = 4;
    /** 放雷的半徑（公尺；吃範圍加成）。 */
    public static final double ZAP_RADIUS_M = 1.8;
    /** FLAT 1（灌注的加成）：這一顆用掉了火灌注（減速、消失時爆炸）。 */
    public static final String FIRE_INFUSED = "ball_lightning.fire_infused";
    /** 用掉火灌注時每秒速度更少幾 %。 */
    public static final double INFUSED_SLOW_PER_SECOND = 25;
    /** 存火焰爆炸逐級傷害的隱藏技能。 */
    public static final String EXPLOSION = "proc_ball_lightning_explosion";
    /** 火焰爆炸的半徑（公尺；吃範圍加成）。 */
    public static final double EXPLOSION_RADIUS_M = 2.6;

    private BallLightning() {
    }

    /** 這個技能是不是滾雷珠。 */
    static boolean is(final SkillDefinition definition) {
        return definition != null && ID.equals(definition.id());
    }

    /** 雷珠飛了 age tick：這一 tick 要不要放雷（第 0 tick 不放）。 */
    static boolean zapDue(final int age) {
        return age > 0 && age % ZAP_TICKS == 0;
    }

    /** 用掉火灌注的雷珠每 tick 的速度倍率（每秒更少 {@value #INFUSED_SLOW_PER_SECOND}%：連乘 20 次 = 0.75）。 */
    static double slowPerTick() {
        return Math.pow(1 - INFUSED_SLOW_PER_SECOND / 100.0, 1 / 20.0);
    }

    /** 速度最慢降到原本的幾倍（還是會往前飄，不會整個停住）。 */
    static final double MIN_SPEED_FACTOR = 0.1;

    /** 這一 tick 之後的速度：用掉火灌注的照 {@link #slowPerTick} 慢下來，最慢 start × {@link #MIN_SPEED_FACTOR}。 */
    static double nextSpeed(final double speed, final double start, final boolean infused) {
        if (!infused) {
            return speed;
        }
        return Math.max(start * MIN_SPEED_FACTOR, speed * slowPerTick());
    }
}
