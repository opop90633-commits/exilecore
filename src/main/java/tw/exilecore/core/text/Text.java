package tw.exilecore.core.text;

import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.minimessage.MiniMessage;

/**
 * 訊息工具：所有給玩家看的文字都走 MiniMessage 標籤，
 * 之後多語系與顏色調整只改設定檔、不改程式。
 */
public final class Text {

    private static final MiniMessage MINI = MiniMessage.miniMessage();

    private final String prefix;

    public Text(final String prefix) {
        this.prefix = prefix;
    }

    /** 前面加上 ExileCore 前綴。 */
    public Component prefixed(final String miniMessage) {
        return MINI.deserialize(prefix + miniMessage);
    }

    /** 不加前綴，直接解析。 */
    public Component parse(final String miniMessage) {
        return MINI.deserialize(miniMessage);
    }
}
