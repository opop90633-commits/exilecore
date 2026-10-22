package tw.exilecore.combat;

import tw.exilecore.stats.DamagePacket;
import tw.exilecore.stats.DamageType;
import tw.exilecore.stats.Modifier;
import tw.exilecore.stats.StatKeys;
import tw.exilecore.stats.Tags;

import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 三相輔助（2026-10）：火焰、冰冷、閃電三種「共鳴」（各 0–50）。
 * 被輔助的技能擊中時，這一下打最多的那種元素讓「另外兩種」各 +25 共鳴；某種共鳴 2 秒沒拿到就每秒掉 5。
 * 出手時照最低的共鳴：每 5 點 更多 4% 元素傷害（滿 50 = 更多 40%）；三種都至少 25 時，元素抗性穿透 15%。
 */
public final class Trinity {

    public static final double MAX = 50;
    public static final double GAIN = 25;
    public static final double DECAY_PER_SECOND = 5;
    public static final long GRACE_TICKS = 40;
    public static final double MORE_PER_STEP = 4;
    public static final double PENETRATION = 15;
    public static final double PENETRATION_AT = 25;
    private static final DamageType[] ELEMENTS = {DamageType.FIRE, DamageType.COLD, DamageType.LIGHTNING};

    private static final class State {
        final double[] value = new double[3];
        final long[] gained = new long[3];
        final long[] updated = new long[3];
    }

    private final Map<UUID, State> states = new ConcurrentHashMap<>();

    /** 現在的共鳴（照時間扣掉衰減）：火焰、冰冷、閃電。 */
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
        final long decayFrom = Math.max(state.updated[i], state.gained[i] + GRACE_TICKS);
        final double decay = tick > decayFrom ? (tick - decayFrom) / 20.0 * DECAY_PER_SECOND : 0;
        return Math.max(0, state.value[i] - decay);
    }

    /** 出手前疊上去的加成（沒有共鳴就是空的）。 */
    public List<Modifier> bonus(final UUID player, final long tick) {
        final double[] res = resonance(player, tick);
        final double lowest = Math.min(res[0], Math.min(res[1], res[2]));
        final double more = Math.floor(lowest / 5) * MORE_PER_STEP;
        final List<Modifier> list = new java.util.ArrayList<>();
        if (more > 0) {
            list.add(Modifier.more(StatKeys.DAMAGE, more / 100.0, "三相").withTags(Set.of(Tags.ELEMENTAL)));
        }
        if (lowest >= PENETRATION_AT) {
            list.add(Modifier.flat(StatKeys.PENETRATION, PENETRATION, "三相").withTags(Set.of(Tags.ELEMENTAL)));
        }
        return list;
    }

    /** 擊中之後：打最多的那種元素讓另外兩種共鳴增加。 */
    public void hit(final UUID player, final DamagePacket dealt, final long tick) {
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
        final State state = states.computeIfAbsent(player, key -> new State());
        for (int i = 0; i < 3; i++) {
            final double now = current(state, i, tick);
            state.value[i] = i == best ? now : Math.min(MAX, now + GAIN);
            state.updated[i] = tick;
            if (i != best) {
                state.gained[i] = tick;
            }
        }
    }

    /**
     * 2026-10-22：畫面上方戰鬥資源血條的一段（火、冰、電三種共鳴＋現在吃到的加成）；三種都是 0 就是空字串。
     */
    public String hudText(final UUID player, final long tick) {
        final double[] res = resonance(player, tick);
        if (res[0] <= 0 && res[1] <= 0 && res[2] <= 0) {
            return "";
        }
        final double lowest = Math.min(res[0], Math.min(res[1], res[2]));
        final int more = (int) (Math.floor(lowest / 5) * MORE_PER_STEP);
        final StringBuilder text = new StringBuilder("<gray>共鳴 ")
                .append("<#ff7043>火 ").append(level(res[0]))
                .append(" <#7fd3ff>冰 ").append(level(res[1]))
                .append(" <#ffe14d>電 ").append(level(res[2]));
        if (more > 0) {
            text.append(" <gray>元素傷害 <white>+").append(more).append('%');
        }
        if (lowest >= PENETRATION_AT) {
            text.append(" <#c58fff>穿透 ").append((int) PENETRATION).append('%');
        }
        return text.toString();
    }

    /** 共鳴的數字（滿了是 MAX）。 */
    private static String level(final double value) {
        final int shown = (int) Math.floor(value);
        return shown >= MAX ? "<b>" + shown + "</b>" : String.valueOf(shown);
    }

    public void forget(final UUID player) {
        states.remove(player);
    }
}
