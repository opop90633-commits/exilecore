package tw.exilecore.passive;

import tw.exilecore.stats.Modifier;

import java.util.List;
import java.util.Locale;

/**
 * 天賦樹的一個節點（data/passives/tree.json）。
 * 座標是格子座標，y 向下；相鄰（上下左右）的節點就是相連。
 *
 * @param id          代號，存進資料庫
 * @param type        小點／核心／基石／起點
 * @param name        名稱
 * @param x           格子座標
 * @param y           格子座標
 * @param icon        原版材質名稱（GUI 用）
 * @param modifiers   給屬性表的修飾器（值已經是引擎格式，百分比是小數）
 * @param description 顯示用的說明行
 * @param startClass  起點節點對應的職業代號（其他節點為 null）
 */
public record PassiveNode(String id, Type type, String name, int x, int y, String icon,
                          List<Modifier> modifiers, List<String> description, String startClass) {

    public enum Type {
        SMALL("小點"), NOTABLE("核心"), KEYSTONE("基石"), START("起點"),
        /** 珠寶孔（PoE2 的珠寶插槽）：配了之後可以放一顆珠寶，珠寶的詞綴加在角色身上。 */
        SOCKET("珠寶孔");

        private final String displayName;

        Type(final String displayName) {
            this.displayName = displayName;
        }

        public String displayName() {
            return displayName;
        }

        public static Type parse(final String text) {
            return valueOf(text.trim().toUpperCase(Locale.ROOT));
        }
    }

    public PassiveNode {
        modifiers = List.copyOf(modifiers);
        description = List.copyOf(description);
    }

    public boolean isStart() {
        return type == Type.START;
    }

    /** 珠寶孔。 */
    public boolean isSocket() {
        return type == Type.SOCKET;
    }

    /**
     * 屬性點（2026-10 PoE2）：「+x 任一屬性」，玩家自己選力量／敏捷／智慧。回傳 x；不是屬性點回傳 0。
     * 網頁規劃器、規劃代碼（{@link TreeCode}）都靠這個認屬性點。
     */
    public int attributeChoice() {
        double total = 0;
        for (final Modifier modifier : modifiers) {
            if (tw.exilecore.stats.StatKeys.ATTR_CHOICE.equals(modifier.stat())) {
                total += modifier.value();
            }
        }
        return (int) Math.round(total);
    }
}
