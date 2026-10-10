package tw.exilecore.ailment;

/** 異常狀態：四種元素異常加上流血、中毒、暈眩、電擊。顯示名稱、一個字的標記（怪物名字後面、動作列）與顏色。 */
public enum AilmentType {
    IGNITE("點燃", "燃", "#ff7a1a"),
    CHILL("冰緩", "寒", "#9fd6ff"),
    FREEZE("凍結", "凍", "#55ffff"),
    SHOCK("感電", "電", "#ffe066"),
    /** 流血（物理，PoE2）：持續傷害，目標移動時更痛。 */
    BLEED("流血", "血", "#ff4a4a"),
    /** 中毒（物理與混沌，PoE2）：持續傷害，可以疊加。 */
    POISON("中毒", "毒", "#7fd13b"),
    /** 暈眩（任何傷害，物理最多，PoE2 的重暈）：累積滿了一段時間不能動、不能攻擊。 */
    STUN("暈眩", "暈", "#ffd27f"),
    /** 電擊（閃電，PoE2）：要有電擊機率的來源才累積；滿了一段時間不能動、不能攻擊。 */
    ELECTROCUTE("電擊", "麻", "#9fe8ff");

    private final String displayName;
    private final String mark;
    private final String color;

    AilmentType(final String displayName, final String mark, final String color) {
        this.displayName = displayName;
        this.mark = mark;
        this.color = color;
    }

    public String displayName() {
        return displayName;
    }

    /** 一個字的標記。 */
    public String mark() {
        return mark;
    }

    /** MiniMessage 用的十六進位顏色。 */
    public String color() {
        return color;
    }
}
