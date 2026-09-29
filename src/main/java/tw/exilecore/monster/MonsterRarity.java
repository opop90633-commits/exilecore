package tw.exilecore.monster;

/** 怪物稀有度。倍率與權重在 monsters.yml 設定，這裡只有顏色與名稱。 */
public enum MonsterRarity {
    NORMAL("普通", "#ffffff"),
    MAGIC("魔法", "#8888ff"),
    RARE("稀有", "#ffff77"),
    UNIQUE("傳奇", "#af6025");

    private final String displayName;
    private final String color;

    MonsterRarity(final String displayName, final String color) {
        this.displayName = displayName;
        this.color = color;
    }

    public String displayName() {
        return displayName;
    }

    public String color() {
        return color;
    }

    public static MonsterRarity parse(final String text, final MonsterRarity def) {
        if (text == null) {
            return def;
        }
        for (final MonsterRarity rarity : values()) {
            if (rarity.name().equalsIgnoreCase(text.trim()) || rarity.displayName.equals(text.trim())) {
                return rarity;
            }
        }
        return def;
    }
}
