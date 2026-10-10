package tw.exilecore.flask;

import java.util.Locale;

/** 藥劑回復什麼（PoE2 只有生命與魔力兩種）。 */
public enum FlaskKind {
    LIFE("生命", "#ff5555"),
    MANA("魔力", "#5599ff");

    private final String displayName;
    private final String color;

    FlaskKind(final String displayName, final String color) {
        this.displayName = displayName;
        this.color = color;
    }

    public String displayName() {
        return displayName;
    }

    /** 說明文字用的顏色（MiniMessage 的色碼）。 */
    public String color() {
        return color;
    }

    public static FlaskKind parse(final String where, final String text) {
        if (text != null) {
            for (final FlaskKind kind : values()) {
                if (kind.name().equalsIgnoreCase(text.trim())) {
                    return kind;
                }
            }
        }
        throw new IllegalArgumentException(where + "：藥劑種類要寫 life 或 mana，卻是「" + text + "」");
    }

    public String key() {
        return name().toLowerCase(Locale.ROOT);
    }
}
