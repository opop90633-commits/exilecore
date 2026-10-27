package tw.exilecore.combat;

import tw.exilecore.stats.DamagePacket;
import tw.exilecore.stats.DamageType;
import tw.exilecore.stats.Modifier;
import tw.exilecore.stats.StatKeys;
import tw.exilecore.stats.StatSheet;
import tw.exilecore.stats.Tags;

import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 鼎元訣（2026-10-26 照 PoE2 0.5 的 Trinity；這次之前照以前的三相輔助：另外兩種 +25、最多 50、照最低的共鳴、15% 穿透）：
 * 火焰、冰冷、閃電三種「親和」（各 0–{@value #MAX}）。
 * <ul>
 *   <li>造成元素傷害的擊中：打最多的那種元素 +{@link #GAIN}（逐級 5→13→17，寫在技能的 {@link #GAIN_STAT}），另外兩種各 −{@value #LOSS}；</li>
 *   <li>某種親和 {@value #DELAY_TICKS} tick（8 秒）沒拿到就每秒掉 {@value #DECAY_PER_SECOND}；</li>
 *   <li>出手時照三種加起來：每 {@value #STEP} 點更多 x% 元素傷害（逐級 1→6→10，{@link #MORE_STAT}）。沒有穿透（0.5 拿掉了）。</li>
 * </ul>
 */
public final class Trinity {

    public static final double MAX = 100;
    /** 沒寫逐級數值時的預設（1 級）。 */
    public static final double GAIN = 5;
    public static final double LOSS = 3;
    public static final double DECAY_PER_SECOND = 10;
    public static final long DELAY_TICKS = 160;
    public static final double STEP = 30;
    public static final double MORE_PER_STEP = 1;
    /** 光環給的逐級數值（FLAT）：每一擊拿幾點親和、每 30 點親和更多幾 % 元素傷害。 */
    public static final String GAIN_STAT = "trinity.gain";
    public static final String MORE_STAT = "trinity.more";
    private static final DamageType[] ELEMENTS = {DamageType.FIRE, DamageType.COLD, DamageType.LIGHTNING};

    private static final class State {
        final double[] value = new double[3];
        final long[] gained = new long[3];
        final long[] updated = new long[3];
        double perStep = MORE_PER_STEP;
    }

    private final Map<UUID, State> states = new ConcurrentHashMap<>();

    /** 現在的親和（照時間扣掉衰減）：火焰、冰冷、閃電。 */
    public double[] resonance(final UUID player, final long tick) {
        final State state = states.get(player);
        final double[] result = new double[3];
        if (state == null) {
            return result;
        }
        for (int i = 0; i < 3; i++) {
            result[i] = current(state, i, tick);
        }
        return result;
    }

    private static double current(final State state, final int i, final long tick) {
        final long decayFrom = Math.max(state.updated[i], state.gained[i] + DELAY_TICKS);
        final double decay = tick > decayFrom ? (tick - decayFrom) / 20.0 * DECAY_PER_SECOND : 0;
        return Math.max(0, state.value[i] - decay);
    }

    private static double perStep(final StatSheet sheet) {
        final double value = sheet == null ? 0 : sheet.flat(MORE_STAT, Set.of());
        return value > 0 ? value : MORE_PER_STEP;
    }

    /** 出手前疊上去的加成（沒有親和就是空的）。 */
    public List<Modifier> bonus(final UUID player, final long tick) {
        return bonus(player, tick, null);
    }

    public List<Modifier> bonus(final UUID player, final long tick, final StatSheet sheet) {
        final double[] res = resonance(player, tick);
        final double step = sheet == null ? stored(player) : perStep(sheet);
        final double more = Math.floor((res[0] + res[1] + res[2]) / STEP) * step;
        if (more <= 0) {
            return List.of();
        }
        return List.of(Modifier.more(StatKeys.DAMAGE, more / 100.0, "鼎元訣").withTags(Set.of(Tags.ELEMENTAL)));
    }

    private double stored(final UUID player) {
        final State state = states.get(player);
        return state == null ? MORE_PER_STEP : state.perStep;
    }

    /** 擊中之後：打最多的那種元素 + 親和、另外兩種 − 親和。 */
    public void hit(final UUID player, final DamagePacket dealt, final long tick) {
        hit(player, dealt, tick, null);
    }

    public void hit(final UUID player, final DamagePacket dealt, final long tick, final StatSheet sheet) {
        int best = -1;
        double bestAmount = 0;
        for (int i = 0; i < 3; i++) {
            final double amount = dealt.get(ELEMENTS[i]);
            if (amount > bestAmount) {
                bestAmount = amount;
                best = i;
            }
        }
        if (best < 0) {
            return;
        }
        final double gain = sheet == null || sheet.flat(GAIN_STAT, Set.of()) <= 0 ? GAIN : sheet.flat(GAIN_STAT, Set.of());
        final State state = states.computeIfAbsent(player, key -> new State());
        if (sheet != null) {
            state.perStep = perStep(sheet);
        }
        for (int i = 0; i < 3; i++) {
            final double now = current(state, i, tick);
            state.value[i] = i == best ? Math.min(MAX, now + gain) : Math.max(0, now - LOSS);
            state.updated[i] = tick;
            if (i == best) {
                state.gained[i] = tick;
            }
        }
    }

    /**
     * 2026-10-22：畫面上方戰鬥資源血條的一段（火、冰、電三種親和＋現在吃到的加成）；三種都是 0 就是空字串。
     */
    public String hudText(final UUID player, final long tick) {
        final double[] res = resonance(player, tick);
        if (res[0] <= 0 && res[1] <= 0 && res[2] <= 0) {
            return "";
        }
        final int more = (int) (Math.floor((res[0] + res[1] + res[2]) / STEP) * stored(player));
        final StringBuilder text = new StringBuilder("<gray>親和 ")
                .append("<#ff7043>火 ").append(level(res[0]))
                .append(" <#7fd3ff>冰 ").append(level(res[1]))
                .append(" <#ffe14d>電 ").append(level(res[2]));
        if (more > 0) {
            text.append(" <gray>元素傷害 <white>+").append(more).append('%');
        }
        return text.toString();
    }

    /** 親和的數字（滿了是 MAX）。 */
    private static String level(final double value) {
        final int shown = (int) Math.floor(value);
        return shown >= MAX ? "<b>" + shown + "</b>" : String.valueOf(shown);
    }

    public void forget(final UUID player) {
        states.remove(player);
    }
}
