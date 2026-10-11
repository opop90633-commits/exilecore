package tw.exilecore.guild;

/** 公會裡的階級：成員、幹部、會長。數字越大權限越高（存檔用數字）。 */
public enum GuildRank {
    MEMBER(0, "成員"), OFFICER(1, "幹部"), LEADER(2, "會長");

    private final int code;
    private final String displayName;

    GuildRank(final int code, final String displayName) {
        this.code = code;
        this.displayName = displayName;
    }

    public int code() {
        return code;
    }

    public String displayName() {
        return displayName;
    }

    /** 這個階級有沒有到 other（含）以上。 */
    public boolean atLeast(final GuildRank other) {
        return code >= other.code;
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
            case "leader", "會長" -> LEADER;
            default -> fallback;
        };
    }
}
