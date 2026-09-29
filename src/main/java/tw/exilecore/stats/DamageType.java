package tw.exilecore.stats;

import java.util.Set;

/** 五種傷害類型與它們在修飾器查詢時帶的標籤。 */
public enum DamageType {
    PHYSICAL("物理", Tags.PHYSICAL, Set.of(Tags.PHYSICAL), StatKeys.ARMOUR),
    FIRE("火焰", Tags.FIRE, Set.of(Tags.FIRE, Tags.ELEMENTAL), StatKeys.RESIST_FIRE),
    COLD("冰冷", Tags.COLD, Set.of(Tags.COLD, Tags.ELEMENTAL), StatKeys.RESIST_COLD),
    LIGHTNING("閃電", Tags.LIGHTNING, Set.of(Tags.LIGHTNING, Tags.ELEMENTAL), StatKeys.RESIST_LIGHTNING),
    CHAOS("混沌", Tags.CHAOS, Set.of(Tags.CHAOS), StatKeys.RESIST_CHAOS);

    private final String displayName;
    private final String tag;
    private final Set<String> tags;
    private final String mitigationStat;

    DamageType(final String displayName, final String tag, final Set<String> tags, final String mitigationStat) {
        this.displayName = displayName;
        this.tag = tag;
        this.tags = tags;
        this.mitigationStat = mitigationStat;
    }

    public String displayName() {
        return displayName;
    }

    /** 這個類型自己的標籤（fire、cold …）。 */
    public String tag() {
        return tag;
    }

    /** 計算這個類型的傷害時要加進情境的標籤（元素傷害會多一個 elemental）。 */
    public Set<String> tags() {
        return tags;
    }

    /** 防禦方用哪個屬性減免：物理用護甲，其餘用對應抗性。 */
    public String mitigationStat() {
        return mitigationStat;
    }

    public boolean isElemental() {
        return this == FIRE || this == COLD || this == LIGHTNING;
    }

    /** 由標籤字串找類型；找不到回傳 null。 */
    public static DamageType fromTag(final String tag) {
        for (final DamageType type : values()) {
            if (type.tag.equalsIgnoreCase(tag)) {
                return type;
            }
        }
        return null;
    }
}
