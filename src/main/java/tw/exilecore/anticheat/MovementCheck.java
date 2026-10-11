package tw.exilecore.anticheat;

/**
 * 一個玩家的移動檢查（2026-10；純邏輯，不依賴 Bukkit，方便測試）：每刻餵進「這一刻水平走了多遠、垂直走了多遠、有沒有踩在東西上」，
 * 算出有沒有超速、有沒有在空中停住或往上飄。
 * <ul>
 *   <li>超速：最近 {@value #WINDOW} 刻水平移動的總和，不能超過「這個玩家這一秒最多能走幾格」（照移動速度屬性算，
 *       衝刺跳躍、冰面、技能或擊退給的速度都算進去，再放寬 tolerance）。超過的部分累積成違規點數，一下子衝太快才會到 1。</li>
 *   <li>飛行：沒踩在東西上的時候，連續 {@link Config#hoverTicks} 刻沒有往下掉（懸空）、或離開地面後總共升了超過
 *       {@link Config#maxRise} 格（原版跳最高約 1.25 格）就算飛。</li>
 * </ul>
 * 違規點數每刻慢慢消退；到 1 就該出手（拉回去），由服務那邊決定怎麼處理。
 */
public final class MovementCheck {

    /** 看最近幾刻（1 秒）。 */
    public static final int WINDOW = 20;

    /** 檢查的參數（anticheat.yml）。 */
    public record Config(double tolerance, int hoverTicks, double maxRise, double decayPerTick) {
        public static final Config DEFAULT = new Config(1.25, 14, 1.9, 0.02);
    }

    /** 這一刻的結果。 */
    public enum Verdict { OK, SPEED, FLY }

    private final Config config;
    private final double[] recent = new double[WINDOW];
    private int cursor;
    private double windowSum;
    private double violation;
    private int airTicks;
    private int hoverTicks;
    private double rise;
    private double lastHorizontal;
    private double lastAllowed;

    public MovementCheck(final Config config) {
        this.config = config;
    }

    /**
     * 餵一刻。
     *
     * @param horizontal   這一刻水平走了幾格
     * @param vertical     這一刻垂直走了幾格（往上是正）
     * @param supported    有踩在東西上（地面、液體、梯子、鷹架…）
     * @param allowedPerSecond 這個玩家這一秒最多能走幾格（還沒乘 tolerance）
     * @param flightCheck  要不要檢查飛行（可以飛、滑翔、騎東西、有漂浮效果時關掉）
     */
    public Verdict tick(final double horizontal, final double vertical, final boolean supported, final double allowedPerSecond,
                        final boolean flightCheck) {
        lastHorizontal = horizontal;
        lastAllowed = allowedPerSecond * config.tolerance();
        windowSum -= recent[cursor];
        recent[cursor] = horizontal;
        windowSum += horizontal;
        cursor = (cursor + 1) % WINDOW;
        Verdict verdict = Verdict.OK;
        if (windowSum > lastAllowed && lastAllowed > 0) {
            // 超出的比例累積：快 20% 一秒累積 0.2；快兩倍一秒就到 1
            violation += (windowSum / lastAllowed - 1) / WINDOW * 4;
        } else {
            violation = Math.max(0, violation - config.decayPerTick());
        }
        if (supported || !flightCheck) {
            airTicks = 0;
            hoverTicks = 0;
            rise = 0;
        } else {
            airTicks++;
            if (vertical > 0) {
                rise += vertical;
            }
            if (vertical >= -0.03 && airTicks > 4) {
                hoverTicks++;
            } else {
                hoverTicks = 0;
            }
            if (hoverTicks >= config.hoverTicks() || rise > config.maxRise()) {
                violation += 0.5;
                hoverTicks = 0;
                rise = 0;
                verdict = Verdict.FLY;
            }
        }
        if (violation >= 1) {
            violation = 0;
            return verdict == Verdict.FLY ? Verdict.FLY : Verdict.SPEED;
        }
        return Verdict.OK;
    }

    /** 傳送、重生、換世界之後：忘掉之前的（不然會把傳送當成超速）。 */
    public void reset() {
        java.util.Arrays.fill(recent, 0);
        cursor = 0;
        windowSum = 0;
        violation = 0;
        airTicks = 0;
        hoverTicks = 0;
        rise = 0;
    }

    public double violation() {
        return violation;
    }

    /** 最近一秒走了幾格。 */
    public double windowSum() {
        return windowSum;
    }

    public double lastHorizontal() {
        return lastHorizontal;
    }

    /** 最近一次算出來的上限（已經乘過 tolerance）。 */
    public double lastAllowed() {
        return lastAllowed;
    }

    public int airTicks() {
        return airTicks;
    }
}
