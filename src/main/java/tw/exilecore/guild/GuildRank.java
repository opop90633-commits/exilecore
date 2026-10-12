package tw.exilecore.guild;

/**
 * 公會裡的階級：成員、幹部、副會長（2026-10）、會長。code 是存檔用的數字（副會長是後來加的，所以是 3），
 * 權限高低看 {@link #power()}。
 */
public enum GuildRank {
    MEMBER(0, "成員", 0), OFFICER(1, "幹部", 1), VICE(3, "副會長", 2), LEADER(2, "會長", 3);

    private final int code;
    private final String displayName;
    private final int power;

    GuildRank(final int code, final String displayName, final int power) {
        this.code = code;
        this.displayName = displayName;
        this.power = power;
    }

    /** 權限高低（越大越高）：成員 0、幹部 1、副會長 2、會長 3。 */
    public int power() {
        return power;
    }

    public int code() {
        return code;
    }

    public String displayName() {
        return displayName;
    }

    /** 這個階級有沒有到 other（含）以上。 */
    public boolean atLeast(final GuildRank other) {
        return power >= other.power;
    }

    /** 存檔的數字換回階級（不認得的當成員）。 */
    public static GuildRank of(final int code) {
        for (final GuildRank rank : values()) {
            if (rank.code == code) {
                return rank;
            }
        }
        return MEMBER;
    }

    /** 設定檔寫的 member／officer／leader（不認得回傳 fallback）。 */
    public static GuildRank parse(final String text, final GuildRank fallback) {
        if (text == null) {
            return fallback;
        }
        return switch (text.trim().toLowerCase(java.util.Locale.ROOT)) {
            case "member", "成員" -> MEMBER;
            case "officer", "幹部" -> OFFICER;
            case "vice", "副會長" -> VICE;
            case "leader", "會長" -> LEADER;
            default -> fallback;
        };
    }
}
