package tw.exilecore.item;

/** 物品稀有度：顏色與可以有幾條前綴／後綴。 */
public enum Rarity {
    NORMAL("普通", "#ffffff", 0, 0, 0),
    MAGIC("魔法", "#8888ff", 1, 1, 1),
    RARE("稀有", "#ffff77", 3, 3, 4),
    UNIQUE("傳奇", "#af6025", 0, 0, 0);

    private final String displayName;
    private final String color;
    private final int maxPrefixes;
    private final int maxSuffixes;
    private final int minAffixes;

    Rarity(final String displayName, final String color, final int maxPrefixes, final int maxSuffixes, final int minAffixes) {
        this.displayName = displayName;
        this.color = color;
        this.maxPrefixes = maxPrefixes;
        this.maxSuffixes = maxSuffixes;
        this.minAffixes = minAffixes;
    }

    public String displayName() {
        return displayName;
    }

    /** MiniMessage 用的十六進位顏色。 */
    public String color() {
        return color;
    }

    public int maxPrefixes() {
        return maxPrefixes;
    }

    public int maxSuffixes() {
        return maxSuffixes;
    }

    public int maxAffixes() {
        return maxPrefixes + maxSuffixes;
    }

    /** 這個稀有度生成時至少幾條詞綴（魔法 1、稀有 4）。 */
    public int minAffixes() {
        return minAffixes;
    }

    public static Rarity parse(final String text, final Rarity def) {
        if (text == null) {
            return def;
        }
        for (final Rarity rarity : values()) {
            if (rarity.name().equalsIgnoreCase(text.trim()) || rarity.displayName.equals(text.trim())) {
                return rarity;
            }
        }
        return def;
    }
}
