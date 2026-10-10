package tw.exilecore.character;

/**
 * 玩家的個人設定（存在角色進度裡，鍵是 {@code setting.*}，值 "1" 開、"0" 關；沒設定過用預設值）。
 * 選單的「設定」改這些。
 */
public final class PlayerSettings {

    /** 打怪時在怪物頭上顯示傷害數字（只有自己看得到）。預設開。 */
    public static final String DAMAGE_NUMBERS = "setting.damage_numbers";
    /** 被打到時在眼前顯示受到的傷害與屬性（「-123 火焰」）。預設開。 */
    public static final String DAMAGE_TAKEN = "setting.damage_taken";
    /** 接受別人的交易邀請。預設開。 */
    public static final String TRADE_REQUESTS = "setting.trade_requests";
    /** 介面資源包（星盤式天賦樹）。預設開；關掉就不送、已經套用的拿掉。 */
    public static final String UI_PACK = "setting.ui_pack";
    /** 畫面右邊的任務追蹤。預設開。 */
    public static final String QUEST_TRACKER = "setting.quest_tracker";
    /** 打怪時畫面右邊的戰鬥數據（每秒輸出、每秒承受與類型、最大一擊）。預設開。 */
    public static final String COMBAT_METER = "setting.combat_meter";
    /** 看得到別的玩家的技能特效與圖騰（2026-10；外觀特效、Boss 的警告照常）。預設開。 */
    public static final String OTHERS_FX = "setting.others_fx";

    private PlayerSettings() {
    }

    /** 這個設定是否開著（沒設定過算開）。 */
    public static boolean enabled(final PlayerState state, final String key) {
        return state == null || !"0".equals(state.progress().get(key));
    }
}
