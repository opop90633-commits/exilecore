package tw.exilecore.skill;

import java.util.Locale;

/** 技能的種類：攻擊（用武器傷害、吃攻擊速度）或法術（用自己的基礎傷害、吃施法速度）。 */
public enum SkillType {
    ATTACK("攻擊"),
    SPELL("法術");

    private final String displayName;

    SkillType(final String displayName) {
        this.displayName = displayName;
    }

    public String displayName() {
        return displayName;
    }

    public static SkillType parse(final String where, final String text) {
        try {
            return valueOf(text.trim().toUpperCase(Locale.ROOT));
        } catch (final IllegalArgumentException e) {
            throw new IllegalArgumentException(where + "：type 要寫 attack 或 spell，卻是「" + text + "」");
        }
    }
}
