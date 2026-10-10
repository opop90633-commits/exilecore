package tw.exilecore.character;

import java.util.Locale;

/**
 * 八個職業（2026-10 加了德魯伊；照天賦樹上的位置排：力量 → 力敏 → 敏 → 敏智 → 智）。差在起始屬性、天賦樹起點、新手武器與起始技能、昇華職業，
 * 沒有職業限定技能：想玩什麼都靠天賦樹與寶石決定。新手裝備在 character.yml 的 starter（{@link StarterKits}）。
 * <p>
 * 照 PoE2 的職業分法（名字是自己的）：重弩手（弩，Mercenary）、棍僧（長棍，Monk）、長矛獵手（矛，Huntress）、元素法師（元素法術，Sorceress）；混沌術士（Witch）改走混沌與詛咒。
 * 每個職業的起始屬性加起來都是 60。
 */
public enum CharacterClass {
    MARAUDER("marauder", "重甲鬥士", 32, 14, 14, "MACE", "<#ff6655>", "力量起點：生命、護甲與近戰傷害；錘"),
    MERCENARY("mercenary", "重弩手", 23, 23, 14, "CROSSBOW", "<#d9a066>", "力量與敏捷：護甲與閃避、投射物；弩"),
    HUNTRESS("huntress", "長矛獵手", 18, 28, 14, "IRON_SPEAR", "<#7ed957>", "敏捷偏力量：近戰與投擲、暴擊；矛與小盾"),
    RANGER("ranger", "弓箭手", 14, 32, 14, "BOW", "<#66dd66>", "敏捷起點：閃避、攻速與暴擊；弓"),
    MONK("monk", "棍僧", 14, 23, 23, "BAMBOO", "<#5fd3c7>", "敏捷與智慧：閃避與能量護盾、閃電與冰冷的攻擊；長棍"),
    SORCERESS("sorceress", "元素法師", 14, 14, 32, "BLAZE_ROD", "<#6699ff>", "智慧起點：能量護盾、元素法術；法杖與長杖"),
    WITCH("witch", "混沌術士", 14, 14, 32, "WITHER_ROSE", "<#b07cff>", "智慧起點（靠力量那一側）：混沌法術、生命與能量護盾；法杖與長杖"),
    /** 2026-10：照 PoE2 的德魯伊（力量與智慧，變身與自然法術；獸魂符）。天賦樹起點在重甲鬥士與混沌術士之間。 */
    DRUID("druid", "德魯伊", 23, 14, 23, "OAK_SAPLING", "<#8fc75a>", "力量與智慧：變身成熊、狼人、飛龍，或放自然法術；獸魂符");

    private final String id;
    private final String displayName;
    private final int strength;
    private final int dexterity;
    private final int intelligence;
    private final String icon;
    private final String color;
    private final String description;

    CharacterClass(final String id, final String displayName, final int strength, final int dexterity,
                   final int intelligence, final String icon, final String color, final String description) {
        this.id = id;
        this.displayName = displayName;
        this.strength = strength;
        this.dexterity = dexterity;
        this.intelligence = intelligence;
        this.icon = icon;
        this.color = color;
        this.description = description;
    }

    public String id() {
        return id;
    }

    public String displayName() {
        return displayName;
    }

    public int strength() {
        return strength;
    }

    public int dexterity() {
        return dexterity;
    }

    public int intelligence() {
        return intelligence;
    }

    /** 選單裡代表這個職業的原版材質。 */
    public String icon() {
        return icon;
    }

    /** MiniMessage 顏色標籤。 */
    public String color() {
        return color;
    }

    public String description() {
        return description;
    }

    /** 所有職業的代號，用「、」連起來（錯誤訊息用）。 */
    public static String idList() {
        final StringBuilder builder = new StringBuilder();
        for (final CharacterClass value : values()) {
            if (!builder.isEmpty()) {
                builder.append('、');
            }
            builder.append(value.id);
        }
        return builder.toString();
    }

    public static CharacterClass parse(final String text, final CharacterClass def) {
        if (text == null) {
            return def;
        }
        final String normalized = text.trim().toLowerCase(Locale.ROOT);
        for (final CharacterClass value : values()) {
            if (value.id.equals(normalized) || value.name().toLowerCase(Locale.ROOT).equals(normalized)
                    || value.displayName.equals(text.trim())) {
                return value;
            }
        }
        return def;
    }
}
