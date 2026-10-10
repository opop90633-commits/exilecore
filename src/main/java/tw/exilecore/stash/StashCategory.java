package tw.exilecore.stash;

import java.util.List;
import java.util.Locale;

/**
 * 物品的分類：倉庫每一頁可以指定放哪一類（PoE 的分頁），收進倉庫、整理時照這個分。純邏輯。
 * <p>
 * {@link #ALL} 只用在頁籤上（什麼都放）；{@link #CURRENCY}、{@link #MAP} 只用在分類物品上（通貨、地圖有自己的頁）。
 * 2026-10：分得更細——武器、副手、頭、身、腿、腳、戒指、項鍊、腰帶、珠寶、護符各自一類；
 * 頁籤另外可以選「防具」（副手＋頭身腿腳）、「飾品」（戒指＋項鍊＋腰帶）兩個大類，「其他」也收寶石、藥劑。
 * 排序時照這裡的順序。
 */
public enum StashCategory {
    ALL("全部", "CHEST"),
    WEAPON("武器", "IRON_SWORD"),
    OFF_HAND("副手（盾、箭袋、法器）", "SHIELD"),
    HELMET("頭部", "IRON_HELMET"),
    BODY("身體", "IRON_CHESTPLATE"),
    LEGS("腿部", "IRON_LEGGINGS"),
    BOOTS("腳部", "IRON_BOOTS"),
    /** 頁籤用的大類：副手＋頭身腿腳。 */
    ARMOUR("防具（副手、頭、身、腿、腳）", "IRON_CHESTPLATE"),
    RING("戒指", "GOLD_NUGGET"),
    AMULET("項鍊", "GOLD_INGOT"),
    BELT("腰帶", "LEATHER"),
    /** 頁籤用的大類：戒指＋項鍊＋腰帶。 */
    JEWELLERY("飾品（戒指、項鍊、腰帶）", "GOLD_NUGGET"),
    JEWEL("珠寶", "EMERALD"),
    CHARM("護符", "TOTEM_OF_UNDYING"),
    GEM("寶石", "AMETHYST_SHARD"),
    FLASK("藥劑", "POTION"),
    MAP("地圖", "MAP"),
    CURRENCY("通貨", "SUNFLOWER"),
    OTHER("其他（寶石、藥劑等）", "BUNDLE");

    /** 頁籤可以選的分類（通貨、地圖有自己的頁；寶石、藥劑放「其他」）。 */
    private static final List<StashCategory> TAB_CHOICES = List.of(ALL, WEAPON, OFF_HAND, HELMET, BODY, LEGS, BOOTS, ARMOUR,
            RING, AMULET, BELT, JEWELLERY, JEWEL, CHARM, OTHER);

    private final String displayName;
    private final String icon;

    StashCategory(final String displayName, final String icon) {
        this.displayName = displayName;
        this.icon = icon;
    }

    public String displayName() {
        return displayName;
    }

    /** 頁籤的圖示（原版物品名稱）。 */
    public String icon() {
        return icon;
    }

    /** 頁籤可以選的分類。 */
    public static List<StashCategory> tabChoices() {
        return TAB_CHOICES;
    }

    /** 是不是大類（防具、飾品、其他這種收好幾類的）。 */
    public boolean isGroup() {
        return this == ARMOUR || this == JEWELLERY || this == OTHER;
    }

    /** 這一頁（這個分類）收不收這類物品：同一類，或大類包含它。「全部」什麼都收。 */
    public boolean accepts(final StashCategory item) {
        if (this == ALL || this == item) {
            return true;
        }
        return switch (this) {
            case ARMOUR -> item == OFF_HAND || item == HELMET || item == BODY || item == LEGS || item == BOOTS;
            case JEWELLERY -> item == RING || item == AMULET || item == BELT;
            case OTHER -> item == GEM || item == FLASK;
            default -> false;
        };
    }

    /** 讀存檔或輸入；看不懂的當成「全部」。舊的頁籤：寶石 → 珠寶、藥劑 → 護符、地圖 → 全部（2026-10）。 */
    public static StashCategory parseTab(final String text) {
        if (text == null || text.isBlank()) {
            return ALL;
        }
        try {
            final StashCategory category = valueOf(text.trim().toUpperCase(Locale.ROOT));
            return switch (category) {
                case GEM -> JEWEL;
                case FLASK -> CHARM;
                case MAP, CURRENCY -> ALL;
                default -> category;
            };
        } catch (final IllegalArgumentException e) {
            return ALL;
        }
    }
}
