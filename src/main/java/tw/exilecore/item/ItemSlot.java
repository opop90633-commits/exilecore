package tw.exilecore.item;

/** 裝備欄位。前六個對應原版欄位；戒指、項鍊、腰帶是階段 2 角色面板的虛擬欄位。 */
public enum ItemSlot {
    MAIN_HAND("主手"),
    OFF_HAND("副手"),
    HEAD("頭部"),
    CHEST("身體"),
    LEGS("腿部"),
    FEET("腳部"),
    RING("戒指"),
    AMULET("項鍊"),
    BELT("腰帶");

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
