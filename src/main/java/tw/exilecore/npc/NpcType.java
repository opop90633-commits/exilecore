package tw.exilecore.npc;

import java.util.Locale;

/**
 * NPC 的種類（營地裡的功能）。
 */
public enum NpcType {
    IDENTIFIER("identifier", "鑑定師", "右鍵：免費鑑定・領取鑑定卷軸", "鑑定物品"),
    STASH("stash", "倉庫管理員", "右鍵：打開倉庫", "打開倉庫"),
    SALVAGE("salvage", "拆解師", "右鍵：把裝備拆成金幣與通貨", "拆解裝備"),
    WAYPOINT("waypoint", "傳送師", "右鍵：傳送到發現過的檢查點", "傳送"),
    MAP_DEVICE("map_device", "地圖裝置", "右鍵：放入地圖、打開傳送門・看星圖", "打開地圖裝置"),
    VENDOR("vendor", "商人", "右鍵：買裝備・賭博・賣東西", "看看貨"),
    MARKET("market", "市集掌櫃", "右鍵：市集・商店頁・通貨交易所・領收入", "去市集"),
    HEIST("heist", "盜賊首領", "右鍵：劫盜合約・大劫盜・揭露隱匿詞綴", "談生意"),
    /** 劇情人物（名字、頭銜在 quests.yml；實體另外記著是哪一個人物）。 */
    STORY("story", "劇情人物", "右鍵：說話", "說話");

    private final String id;
    private final String displayName;
    private final String hint;
    private final String action;

    NpcType(final String id, final String displayName, final String hint, final String action) {
        this.id = id;
        this.displayName = displayName;
        this.hint = hint;
        this.action = action;
    }

    /** 對話視窗上打開功能的按鈕。 */
    public String action() {
        return action;
    }

    /**
     * 只是站著的人（2026-10）：倉庫管理員、拆解師、傳送師、市集掌櫃的功能都在選單裡，人留著當營地的擺設，
     * 沒有名字、右鍵沒有反應。
     */
    public boolean decorative() {
        return this == STASH || this == SALVAGE || this == WAYPOINT || this == MARKET;
    }

    /** 營地的功能 NPC（劇情人物以外的）。 */
    public boolean functional() {
        return this != STORY;
    }

    public String id() {
        return id;
    }

    public String displayName() {
        return displayName;
    }

    /** 名字底下那行說明。 */
    public String hint() {
        return hint;
    }

    public static NpcType parse(final String text) {
        if (text == null) {
            return null;
        }
        final String normalized = text.trim().toLowerCase(Locale.ROOT);
        for (final NpcType type : values()) {
            if (type.id.equals(normalized) || type.displayName.equals(text.trim())) {
                return type;
            }
        }
        return null;
    }
}
