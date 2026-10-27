package tw.exilecore.passive;

import tw.exilecore.stats.Modifier;

import java.util.List;
import java.util.Locale;

/**
 * 天賦樹的一個節點（data/passives/tree.json）。
 * 座標是格子座標，y 向下。相連照 tree.json 的 links（2026-10-26 PoE2 的樹）；舊格式沒有 links 時，上下左右相鄰就是相連。
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
 * @param variants    2026-10-26（PoE2 0.5）：某些職業配到這個節點時拿到別的效果（職業代號 → 變體）；沒有是空的
 */
public record PassiveNode(String id, Type type, String name, int x, int y, String icon,
                          List<Modifier> modifiers, List<String> description, String startClass, java.util.Map<String, Variant> variants) {

    /** 職業變體（PoE2：女巫、德魯伊、女獵人在某些節點上拿到不同的效果）。 */
    public record Variant(String name, List<Modifier> modifiers, List<String> description) {
        public Variant {
            modifiers = List.copyOf(modifiers);
            description = List.copyOf(description);
        }
    }

    public PassiveNode(final String id, final Type type, final String name, final int x, final int y, final String icon,
                       final List<Modifier> modifiers, final List<String> description, final String startClass) {
        this(id, type, name, x, y, icon, modifiers, description, startClass, java.util.Map.of());
    }

    /** 這個職業看到的節點（有變體就換成變體的名稱、效果、說明；位置、代號不變）。classId 為 null 時原樣。 */
    public PassiveNode forClass(final String classId) {
        if (classId == null || variants.isEmpty()) {
            return this;
        }
        final Variant variant = variants.get(classId.toLowerCase(Locale.ROOT));
        return variant == null ? this
                : new PassiveNode(id, type, variant.name(), x, y, icon, variant.modifiers(), variant.description(), startClass, java.util.Map.of());
    }

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
        variants = variants == null ? java.util.Map.of() : java.util.Map.copyOf(variants);
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
