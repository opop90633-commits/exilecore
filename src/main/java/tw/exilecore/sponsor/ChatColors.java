package tw.exilecore.sponsor;

import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.serializer.legacy.LegacyComponentSerializer;

import java.util.regex.Pattern;

/** 累積贊助的聊天上色：&0–&f、&l、&o、&n、&m、&r、&#rrggbb（&k 亂碼拿掉）。 */
public final class ChatColors {

    private static final LegacyComponentSerializer SERIALIZER = LegacyComponentSerializer.builder().character('&').hexColors().build();
    private static final Pattern OBFUSCATED = Pattern.compile("(?i)&k");

    private ChatColors() {
    }

    public static Component colorize(final String text) {
        return SERIALIZER.deserialize(OBFUSCATED.matcher(text).replaceAll(""));
    }
}
