package tw.exilecore.item;

/** 裝備欄位。前六個對應原版欄位；戒指、項鍊、腰帶是階段 2 角色面板的虛擬欄位；地圖不是裝備，只是借用同一套物品引擎。 */
public enum ItemSlot {
    MAIN_HAND("主手"),
    OFF_HAND("副手"),
    HEAD("頭部"),
    CHEST("身體"),
    LEGS("腿部"),
    FEET("腳部"),
    RING("戒指"),
    AMULET("項鍊"),
    BELT("腰帶"),
    /** 地圖（星痕）：不能裝備，放進地圖裝置用。 */
    MAP("地圖"),
    /** 珠寶：不能裝備，放進天賦樹的珠寶孔（PoE2 做法）。 */
    JEWEL("珠寶"),
    /** 藥劑（2026-10）：放快捷欄的藥劑欄；借用物品引擎擲詞綴（PoE2：藥劑最多魔法）。 */
    FLASK("藥劑"),
    /** 護符（2026-10）：裝進角色面板的護符欄；同上。 */
    CHARM("護符");

    private final String displayName;

    ItemSlot(final String displayName) {
        this.displayName = displayName;
    }

    public String displayName() {
        return displayName;
    }

    public boolean isVanilla() {
        return ordinal() <= FEET.ordinal();
    }

    public static ItemSlot parse(final String text) {
        return valueOf(text.trim().toUpperCase(java.util.Locale.ROOT));
    }
}
