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
 */
public record DeploySpec(Kind kind, double duration, double interval, double range, int max, double arm, double trigger,
                         double root, int rings, String head, List<Modifier> buff) {

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
                        (int) check(where, "max", node.integer("max", 1), 1, 6), 0, 0, 0, 0,
                        node.string("head", "TOTEM_OF_UNDYING").trim().toUpperCase(Locale.ROOT), buff);
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
