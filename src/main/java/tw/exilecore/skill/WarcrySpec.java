package tw.exilecore.skill;

import tw.exilecore.core.config.YamlNode;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * 戰吼的設定（skills.yml 裡 mechanic: warcry 的 warcry 區塊）。純資料，不依賴 Bukkit。
 * <p>
 * 戰吼是瞬間的：吼一聲，自己（和範圍內的隊友）得到幾秒的增益、回一點生命、接下來幾次攻擊更痛（強化）、
 * 直接獲得戰星（聚星戰吼），範圍內的敵人受到的傷害增加（誰打都算）並被嘲諷過來打你。有冷卻時間。
 *
 * @param radius        範圍（格）
 * @param duration      增益與減益持續幾秒
 * @param taunt         範圍內的敵人轉過來打你
 * @param heal          立刻回復最大生命的百分比（隨等級）
 * @param allies        隊友也得到增益
 * @param buff          給自己（與隊友）的增益（隨技能等級變）
 * @param taken         範圍內的敵人受到的傷害增加百分比（隨等級；0 = 沒有）
 * @param exerts        接下來幾次攻擊被強化（0 = 沒有）
 * @param exertMore     強化的攻擊更多幾 % 傷害（隨等級）
 * @param charges       吼一聲直接獲得的戰星：代號（charges.yml）→ 幾顆（只給自己；滿了就把最舊的那顆換新）
 * @param minionBuff    2026-10 祭品：範圍內你的召喚物得到的增益（隨等級；持續 duration 秒）
 * @param spike         2026-10 祭柱（PoE2 的祭品）：用掉附近一具屍體（沒有就獻祭一隻自己的召喚物）在那裡立一根祭柱，
 *                      duration 秒內祭柱 radius 格內你的召喚物一直有 minionBuff
 * @param rageExtend    2026-10 銀月庇（PoE2 的月光賜福）：吼的時候用掉全部怒氣，每點怒氣增益多撐幾秒（0 = 不用怒氣）
 * @param recall        2026-10-18 召歸令：發動時先把你所有的召喚物（不管多遠、同一個世界）傳到你身邊，minionBuff 給全部的召喚物
 */
public record WarcrySpec(double radius, double duration, boolean taunt, Scaled heal, boolean allies, List<ScaledMod> buff,
                         Scaled taken, int exerts, Scaled exertMore, Map<String, Integer> charges,
                         List<ScaledMod> minionBuff, boolean spike, double rageExtend, boolean recall) {

    /** 2026-10-18：舊的 13 參數建構式（沒有 recall）。 */
    public WarcrySpec(final double radius, final double duration, final boolean taunt, final Scaled heal, final boolean allies, final List<ScaledMod> buff,
                      final Scaled taken, final int exerts, final Scaled exertMore, final Map<String, Integer> charges,
                      final List<ScaledMod> minionBuff, final boolean spike, final double rageExtend) {
        this(radius, duration, taunt, heal, allies, buff, taken, exerts, exertMore, charges, minionBuff, spike, rageExtend, false);
    }

    public WarcrySpec {
        buff = List.copyOf(buff);
        minionBuff = minionBuff == null ? List.of() : List.copyOf(minionBuff);
        charges = charges == null ? Map.of() : java.util.Collections.unmodifiableMap(new LinkedHashMap<>(charges));
    }

    /** skills.yml 的 warcry 區塊；沒有回傳 null。 */
    static WarcrySpec parse(final YamlNode skill) {
        if (!skill.has("warcry")) {
            return null;
        }
        final YamlNode node = skill.node("warcry");
        final String where = node.path();
        final Scaled heal = scaled(where, node, "heal", 0, 0, 50);
        final List<ScaledMod> buff = ScaledMod.parseList(node, "buff");
        Scaled taken = Scaled.of(0);
        if (node.has("debuff")) {
            final YamlNode sub = node.node("debuff");
            taken = scaled(sub.path(), sub, "taken", 10, 0, 100);
        }
        int exerts = 0;
        Scaled exertMore = Scaled.of(0);
        if (node.has("exert")) {
            final YamlNode sub = node.node("exert");
            exerts = (int) check(sub.path(), "count", sub.integer("count", 3), 1, 10);
            exertMore = scaled(sub.path(), sub, "more", 30, 1, 200);
        }
        final Map<String, Integer> charges = new LinkedHashMap<>();
        if (node.has("charges")) {
            final YamlNode sub = node.node("charges");
            for (final String key : sub.keys()) {
                final String id = key.trim().toLowerCase(Locale.ROOT);
                if (!id.matches("[a-z_]+")) {
                    throw new IllegalArgumentException(sub.path() + "：要寫戰星的代號（charges.yml，例如 frenzy），卻是「" + key + "」");
                }
                charges.put(id, (int) check(sub.path(), key, sub.integer(key, 1), 1, 10));
            }
        }
        final List<ScaledMod> minionBuff = ScaledMod.parseList(node, "minion_buff");
        if (heal.last() <= 0 && buff.isEmpty() && taken.last() <= 0 && exerts == 0 && charges.isEmpty() && minionBuff.isEmpty()) {
            throw new IllegalArgumentException(where + "：戰吼至少要有 heal、buff、debuff、exert、charges 或 minion_buff 其中一個");
        }
        return new WarcrySpec(check(where, "radius", node.decimal("radius", 8), 2, 16),
                check(where, "duration", node.decimal("duration", 6), 1, 30),
                node.bool("taunt", true), heal, node.bool("allies", true), buff, taken, exerts, exertMore, charges, minionBuff,
                node.bool("spike", false), check(where, "rage_extend", node.decimal("rage_extend", 0), 0, 2),
                node.bool("recall", false)); // 2026-10-18：召歸令
    }

    private static Scaled scaled(final String where, final YamlNode node, final String key, final double def,
                                 final double min, final double max) {
        final Scaled value = Scaled.parse(where + "." + key, node.raw(key), def);
        check(where, key, value.first(), min, max);
        check(where, key, value.last(), min, max);
        return value;
    }

    private static double check(final String where, final String key, final double value, final double min, final double max) {
        if (value < min || value > max) {
            throw new IllegalArgumentException(where + "." + key + "：要在 " + min + " 到 " + max + " 之間，卻是 " + value);
        }
        return value;
    }
}
