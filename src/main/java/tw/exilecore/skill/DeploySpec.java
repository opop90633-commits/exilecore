package tw.exilecore.skill;

import tw.exilecore.core.config.YamlNode;
import tw.exilecore.item.AffixMod;
import tw.exilecore.stats.Modifier;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * 技能不是自己放，而是放下一個東西讓它放（skills.yml 的 totem／trap／bell 區塊；圖騰施法、陷阱施法輔助也會套預設的）。
 * 純資料，不依賴 Bukkit。
 *
 * @param kind     圖騰（站著一直放）、陷阱（丟出去、敵人靠近就觸發）、鐘（你在它旁邊打中敵人就響，每響一次打周圍一圈）
 * @param duration 放多久（秒）
 * @param interval 圖騰：幾秒放一次；鐘：兩次響之間至少隔幾秒
 * @param range    圖騰：找多遠內的敵人；鐘：你打中的敵人離鐘多近才會響
 * @param max      同時最多幾個（再放就把最舊的收掉）
 * @param arm      陷阱：丟下去幾秒後才會觸發
 * @param trigger  陷阱：敵人靠到多近觸發
 * @param root     陷阱：觸發它的敵人定住幾秒（0 = 不定）
 * @param rings    鐘：最多響幾次
 * @param head     放出來的東西頭上戴什麼（原版物品名稱，顯示用）
 * @param buff     圖騰在的時候加在你身上的修飾器（英靈圖騰的攻擊速度）
 * @param place    2026-10-26 照 PoE2：圖騰的放置時間（秒；原作是技能寶石的施放時間，只吃「放圖騰的速度」，不吃攻擊、施法速度）。
 *                 0 = 照舊（攻擊照武器攻速、法術照施法時間放）
 * @param fixedMax 2026-10-26 照 PoE2：上限不能被改（英靈圖騰的 non_modifiable_totem_limit 10：不吃 +x 圖騰上限、先祖魂約的加倍）
 * @param weaponSpeed 2026-10-26 照 PoE2：圖騰攻擊的頻率照你的武器攻速 × 技能的 attack_speed（崩震圖騰：你的攻擊速度的一半），
 *                 不照 interval（interval 只在拿不到武器攻速時用）
 */
public record DeploySpec(Kind kind, double duration, double interval, double range, int max, double arm, double trigger,
                         double root, int rings, String head, List<Modifier> buff, double place, boolean fixedMax, boolean weaponSpeed) {

    /** 頻率照 interval 的寫法。 */
    public DeploySpec(final Kind kind, final double duration, final double interval, final double range, final int max, final double arm,
                      final double trigger, final double root, final int rings, final String head, final List<Modifier> buff,
                      final double place, final boolean fixedMax) {
        this(kind, duration, interval, range, max, arm, trigger, root, rings, head, buff, place, fixedMax, false);
    }

    /** 舊的寫法（沒有放置時間、上限可以改）。 */
    public DeploySpec(final Kind kind, final double duration, final double interval, final double range, final int max, final double arm,
                      final double trigger, final double root, final int rings, final String head, final List<Modifier> buff) {
        this(kind, duration, interval, range, max, arm, trigger, root, rings, head, buff, 0, false);
    }

    public enum Kind {
        TOTEM("圖騰"), TRAP("陷阱"), BELL("鐘");

        private final String displayName;

        Kind(final String displayName) {
            this.displayName = displayName;
        }

        public String displayName() {
            return displayName;
        }

        public String key() {
            return name().toLowerCase(Locale.ROOT);
        }
    }

    public DeploySpec {
        buff = buff == null ? List.of() : List.copyOf(buff);
    }

    /** 2026-10-22：換一個持續時間（星咒圖騰上的持久、久地只讓圖騰撐更久）。 */
    public DeploySpec withDuration(final double seconds) {
        return new DeploySpec(kind, seconds, interval, range, max, arm, trigger, root, rings, head, buff, place, fixedMax, weaponSpeed);
    }

    /** 圖騰施法輔助套在一般法術上的預設值。 */
    public static DeploySpec spellTotem(final double castTime) {
        return new DeploySpec(Kind.TOTEM, 8, Math.max(0.4, castTime), 12, 1, 0, 0, 0, 0, "TOTEM_OF_UNDYING", List.of());
    }

    /** 陷阱施法輔助套在一般法術上的預設值。 */
    public static DeploySpec trap() {
        return new DeploySpec(Kind.TRAP, 16, 0, 0, 3, 0.5, 2.0, 0, 0, "TRIPWIRE_HOOK", List.of());
    }

    /** skills.yml 裡 totem／trap／bell 其中一個區塊；都沒有回傳 null。 */
    static DeploySpec parse(final YamlNode skill, final String skillName) {
        DeploySpec found = null;
        for (final Kind kind : Kind.values()) {
            if (!skill.has(kind.key())) {
                continue;
            }
            if (found != null) {
                throw new IllegalArgumentException(skill.path() + "：totem、trap、bell 只能寫一種");
            }
            final YamlNode node = skill.node(kind.key());
            final String where = node.path();
            final List<Modifier> buff = new ArrayList<>();
            for (final YamlNode child : node.list("buff")) {
                buff.add(AffixMod.parse(child).toModifier(Map.of(), skillName));
            }
            found = switch (kind) {
                case TOTEM -> new DeploySpec(kind,
                        check(where, "duration", node.decimal("duration", 8), 1, 60),
                        check(where, "interval", node.decimal("interval", 1.0), 0.2, 10),
                        check(where, "range", node.decimal("range", 10), 1, 30),
                        (int) check(where, "max", node.integer("max", 1), 1, 10), 0, 0, 0, 0,
                        node.string("head", "TOTEM_OF_UNDYING").trim().toUpperCase(Locale.ROOT), buff,
                        check(where, "place", node.decimal("place", 0), 0, 5), node.bool("fixed_max", false),
                        node.bool("weapon_speed", false)); // 2026-10-26 照 PoE2（崩震圖騰）
                case TRAP -> new DeploySpec(kind,
                        check(where, "duration", node.decimal("duration", 16), 1, 120), 0, 0,
                        (int) check(where, "max", node.integer("max", 3), 1, 10),
                        check(where, "arm", node.decimal("arm", 0.5), 0, 5),
                        check(where, "trigger", node.decimal("trigger", 2.0), 0.5, 6),
                        check(where, "root", node.decimal("root", 0), 0, 10), 0,
                        node.string("head", "TRIPWIRE_HOOK").trim().toUpperCase(Locale.ROOT), buff);
                case BELL -> new DeploySpec(kind,
                        check(where, "duration", node.decimal("duration", 6), 1, 30),
                        check(where, "interval", node.decimal("interval", 0.25), 0.05, 5),
                        check(where, "range", node.decimal("range", 3.5), 1, 10), 1, 0, 0, 0,
                        (int) check(where, "rings", node.integer("rings", 8), 1, 50),
                        node.string("head", "BELL").trim().toUpperCase(Locale.ROOT), buff);
            };
            if (kind != Kind.TOTEM && !buff.isEmpty()) {
                throw new IllegalArgumentException(where + ".buff：只有圖騰能給施放者增益");
            }
        }
        return found;
    }

    private static double check(final String where, final String key, final double value, final double min, final double max) {
        if (value < min || value > max) {
            throw new IllegalArgumentException(where + "." + key + "：要在 " + min + " 到 " + max + " 之間，卻是 " + value);
        }
        return value;
    }
}
