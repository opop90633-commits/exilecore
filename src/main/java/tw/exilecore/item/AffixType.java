package tw.exilecore.item;

/** 前綴或後綴。 */
public enum AffixType {
    PREFIX("前綴"),
    SUFFIX("後綴");

    private final String displayName;

    AffixType(final String displayName) {
        this.displayName = displayName;
    }

    public String displayName() {
        return displayName;
    }

    public AffixType other() {
        return this == PREFIX ? SUFFIX : PREFIX;
    }

    public static AffixType parse(final String text) {
        return valueOf(text.trim().toUpperCase(java.util.Locale.ROOT));
    }
}
