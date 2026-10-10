package tw.exilecore.combat;

import tw.exilecore.stats.DamageType;

/** 傷害類型的顏色（十六進位，給 MiniMessage 的 {@code <#rrggbb>}）：傷害數字、死亡回顧、/stats、戰鬥數據共用。純邏輯。 */
public final class DamageColors {

    private DamageColors() {
    }

    public static String of(final DamageType type) {
        return switch (type) {
            case PHYSICAL -> "#ececec";
            case FIRE -> "#ff8040";
            case COLD -> "#80d8ff";
            case LIGHTNING -> "#ffe066";
            case CHAOS -> "#c48bff";
        };
    }
}
