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
    /** 戰鬥數據改成「技能明細」：每個技能各打多少、各是什麼傷害類型（2026-10）。預設關（{@link #on}）。 */
    public static final String COMBAT_SKILLS = "setting.combat_skills";
    /** 看得到別的玩家的技能特效與圖騰（2026-10；外觀特效、Boss 的警告照常）。預設開。 */
    public static final String OTHERS_FX = "setting.others_fx";
    /** 自己放技能的聲音（2026-10；別人的技能本來就小聲，跟著「別人的技能特效」關）。預設開。 */
    public static final String SKILL_SOUNDS = "setting.skill_sounds";

    /** 背景音樂（2026-10-23）：關掉就不下載音樂那包（已經套用的拿掉）、也不放。預設開。 */
    public static final String MUSIC = "setting.music";

    /** 畫面左邊的隊伍框：隊友的頭像、名字、生命（護盾）、魔力（2026-10-18；要套資源包）。預設開。 */
    public static final String PARTY_FRAMES = "setting.party_frames";

    /** 過濾死亡訊息：聊天欄不顯示別的玩家的死亡訊息（2026-10-22）。預設關（{@link #on}）。 */
    public static final String HIDE_DEATHS = "setting.hide_deaths";
    /** 過濾加入、離開訊息：聊天欄不顯示誰上線、下線（2026-10-22）。預設關（{@link #on}）。 */
    public static final String HIDE_JOIN_LEAVE = "setting.hide_join_leave";
    /** 隱藏怪物對話：聊天欄不顯示 Boss 的喊話、登場與倒下的台詞（2026-10-22；招式警告、畫面中間的大字照常）。預設關（{@link #on}）。 */
    public static final String HIDE_MONSTER_TALK = "setting.hide_monster_talk";

    private PlayerSettings() {
    }

    /** 這個設定是否開著（沒設定過算開）。 */
    public static boolean enabled(final PlayerState state, final String key) {
        return state == null || !"0".equals(state.progress().get(key));
    }

    /** 預設關的設定是否開著（沒設定過算關）。 */
    public static boolean on(final PlayerState state, final String key) {
        return state != null && "1".equals(state.progress().get(key));
    }
}
