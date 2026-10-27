package tw.exilecore.passive;

import tw.exilecore.stats.DamageType;
import tw.exilecore.stats.ModType;
import tw.exilecore.stats.Modifier;
import tw.exilecore.stats.StatKeys;
import tw.exilecore.stats.StatSheet;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 永恆珠寶征服後的核心與基石用到的效果（2026-10-26，照 PoE2 0.5；這次之前 data/passives/timeless.json 有 38 行標「尚未生效」）。
 * <p>
 * 屬性鍵寫在這裡（不加到 StatKeys），效果分散在讀它們的地方，每個地方都有註解指回這裡：
 * <ul>
 *   <li>{@link #EXPOSURE_EFFECT}fire／cold／lightning：那一種曝露的效果（{@link #exposureScale}，HitEffects、Presence）；</li>
 *   <li>{@link #UNDEAD_POWER}：算完屬性表才加的（{@link #derive}，PlayerStateService）；</li>
 *   <li>{@link #FLESH}（血肉獻祭）CombatListener；{@link #LOYALTY}（忠誠獻祭）這裡＋MinionSheets 的 undead 條件標籤；
 *       {@link #MIND}（心靈獻祭）PlayerStateService 的魔力回復＋PlayerState 的溢出；{@link #BLOOD}（鮮血獻祭）{@link #lifeSpent}／{@link #bloodRegen}；
 *       {@link #SIGHT}（遠目獻祭）ProjectileEngine；</li>
 *   <li>其他：{@link #COMMAND_LIFE_LOSS}、召喚物冷卻（MinionService）、{@link #STUN_DECAY}（AilmentService）。
 *       秘術增強、盾牌防禦、生命轉護盾、分裂、詛咒速度、怒氣流失、護符、荊棘暴擊、殘影效果用天賦樹（master）那邊的同一個屬性鍵。</li>
 * </ul>
 */
public final class ConquestEffects {

    private ConquestEffects() {
    }

    // ---- 基石（深淵族）----
    /** 血肉獻祭（阿姆納姆）：擊中扣的生命避免 {@link #FLESH_PREVENTED}，再在 4 秒內失去避免掉的 {@link #FLESH_LOST} 倍。 */
    public static final String FLESH = "flag.sacrifice_flesh";
    public static final double FLESH_PREVENTED = 0.2;
    public static final double FLESH_LOST = 1.5;
    /** 忠誠獻祭（骷髏馬克）：不死召喚物每 2 點強度總值，你造成 1% 更多傷害（不死召喚物 25% 更少最大生命寫在資料）。 */
    public static final String LOYALTY = "flag.sacrifice_loyalty";
    /** 心靈獻祭（柯戈）：回復的魔力溢出最大魔力（PoE2 的溢出：最多到 1.5 倍）；50% 更少魔力回復寫在資料。 */
    public static final String MIND = "flag.sacrifice_mind";
    /** 鮮血獻祭（特克羅德）：過去 4 秒內每消耗 16 生命，每秒回復 1 生命；20% 更多技能生命消耗寫在資料。 */
    public static final String BLOOD = "flag.sacrifice_blood";
    public static final double BLOOD_PER = 16;
    public static final long BLOOD_WINDOW_TICKS = 80;
    /** 遠目獻祭（烏拉曼）：每一發投射物隨機多分裂、多連鎖、多從地形連鎖、或無法與地形碰撞。 */
    public static final String SIGHT = "flag.sacrifice_sight";

    // ---- 核心 ----
    /** 後面接 fire／cold／lightning（INCREASED）：那一種曝露的效果，跟「曝露效果」相加。 */
    public static final String EXPOSURE_EFFECT = "exposure.effect.";
    /** FLAT：不死召喚物的強度總值（MinionService 每次重算屬性表時給；每隻 = 牠的生命倍率，至少 1）。 */
    public static final String UNDEAD_POWER = "minion.undead_power";
    /** 召喚物的條件標籤：不死（殭屍、骷髏這類）。 */
    public static final String UNDEAD = "undead";
    /** FLAT 百分比（帶 minion 標籤）：召喚物聽從指令時失去最大生命的 x%。 */
    public static final String COMMAND_LIFE_LOSS = "minion.command_life_loss";
    /** INCREASED：你的重度暈眩累積消退速度。 */
    public static final String STUN_DECAY = "stun.decay_rate";

    /** Minecraft 的不死生物（PoE2 的不死召喚物：骷髏、殭屍類）。 */
    private static final Set<String> UNDEAD_KINDS = Set.of("ZOMBIE", "HUSK", "DROWNED", "ZOMBIE_VILLAGER", "ZOMBIFIED_PIGLIN", "ZOGLIN",
            "SKELETON", "STRAY", "WITHER_SKELETON", "BOGGED", "SKELETON_HORSE", "ZOMBIE_HORSE", "PHANTOM", "WITHER");

    public static boolean undead(final String kind) {
        return kind != null && UNDEAD_KINDS.contains(kind.toUpperCase(java.util.Locale.ROOT));
    }

    /** 一隻召喚物的強度：牠的生命倍率（同等級普通怪的幾倍），至少 1。PoE2 的召喚物強度沒有公開的表，照附魂術的強度算法。 */
    public static double minionPower(final double lifeMultiplier) {
        return Math.max(1, Math.round(lifeMultiplier));
    }

    /** 那一種曝露的效果相對於一般曝露效果的倍率：(1 + 曝露效果 + 那一種) / (1 + 曝露效果)。 */
    public static double exposureScale(final StatSheet sheet, final DamageType type) {
        if (sheet == null) {
            return 1;
        }
        final double typed = sheet.increased(EXPOSURE_EFFECT + type.tag(), Set.of());
        if (typed == 0) {
            return 1;
        }
        final double generic = 1 + sheet.increased(StatKeys.EXPOSURE_EFFECT, Set.of());
        return generic <= 0 ? 0 : Math.max(0, generic + typed) / generic;
    }

    /**
     * 算完屬性表之後加的：忠誠獻祭——不死召喚物的強度換成更多傷害。
     * （2026-10-26 合併 master：盾牌防禦、生命轉能量護盾改用天賦樹那邊的 TreeEffectKeys，同一個屬性鍵，不重複算）
     */
    public static void derive(final StatSheet sheet) {
        if (StatKeys.flag(sheet, LOYALTY)) {
            final double power = sheet.flat(UNDEAD_POWER, Set.of());
            if (power >= 2) {
                sheet.add(Modifier.more(StatKeys.DAMAGE, Math.floor(power / 2) / 100.0, "忠誠獻祭"));
            }
        }
    }

    // ---- 鮮血獻祭：過去 4 秒花的生命 ----

    private static final Map<UUID, Deque<long[]>> SPENT = new ConcurrentHashMap<>();

    /** 技能花了 amount 生命（SkillService 扣生命消耗時呼叫）。 */
    public static void lifeSpent(final UUID player, final double amount, final long now) {
        if (amount <= 0) {
            return;
        }
        final Deque<long[]> log = SPENT.computeIfAbsent(player, key -> new ArrayDeque<>());
        synchronized (log) {
            log.addLast(new long[] {now, Math.round(amount * 100)});
            prune(log, now);
        }
    }

    /** 有鮮血獻祭的話：每秒回復幾點生命（過去 4 秒內每花 16 生命回 1 點）。 */
    public static double bloodRegen(final UUID player, final StatSheet sheet, final long now) {
        final Deque<long[]> log = SPENT.get(player);
        if (log == null) {
            return 0;
        }
        double total = 0;
        synchronized (log) {
            prune(log, now);
            if (log.isEmpty()) {
                SPENT.remove(player, log);
                return 0;
            }
            for (final long[] entry : log) {
                total += entry[1] / 100.0;
            }
        }
        return sheet != null && StatKeys.flag(sheet, BLOOD) ? Math.floor(total / BLOOD_PER) : 0;
    }

    private static void prune(final Deque<long[]> log, final long now) {
        while (!log.isEmpty() && now - log.peekFirst()[0] > BLOOD_WINDOW_TICKS) {
            log.removeFirst();
        }
    }
}
