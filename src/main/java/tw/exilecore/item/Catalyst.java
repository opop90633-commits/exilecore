package tw.exilecore.item;

import tw.exilecore.stats.ModType;
import tw.exilecore.stats.Modifier;
import tw.exilecore.stats.StatKeys;

import java.util.Locale;

/**
 * 催化劑的種類（PoE2 的催化劑）：只能打在飾品（戒指、項鍊、腰帶）上，品質變成「這一類詞綴的數值 +品質%」。
 * 打不同種類的催化劑會換掉原本的（品質重新從 5 開始）。
 */
public enum Catalyst {
    RESIST("抗性", "抗性詞綴"),
    LIFE("生命", "生命與魔力詞綴"),
    ATTACK("攻擊", "攻擊詞綴"),
    CASTER("法術", "法術詞綴"),
    CRIT("暴擊", "暴擊詞綴"),
    ELEMENTAL("元素", "元素詞綴"),
    PHYS_CHAOS("物理與混沌", "物理與混沌詞綴"),
    ATTRIBUTE("屬性", "屬性詞綴"),
    SPEED("速度", "速度詞綴"),
    DEFENCE("防禦", "防禦詞綴");

    private final String displayName;
    private final String description;

    Catalyst(final String displayName, final String description) {
        this.displayName = displayName;
        this.description = description;
    }

    public String displayName() {
        return displayName;
    }

    public String description() {
        return description;
    }

    public String id() {
        return name().toLowerCase(Locale.ROOT);
    }

    public static Catalyst parse(final String text) {
        if (text == null) {
            return null;
        }
        for (final Catalyst catalyst : values()) {
            if (catalyst.id().equalsIgnoreCase(text.trim()) || catalyst.displayName.equals(text.trim())) {
                return catalyst;
            }
        }
        return null;
    }

    /** 這條修飾器算不算這一類。 */
    public boolean matches(final Modifier mod) {
        final String stat = mod.stat();
        final boolean spell = mod.tags().contains("spell");
        return switch (this) {
            case RESIST -> stat.startsWith("resist.");
            // 2026-10：受到的傷害回復為生命（life.recoup）、擊中／擊殺回復生命也算生命詞綴
            case LIFE -> stat.equals(StatKeys.LIFE_MAX) || stat.equals(StatKeys.LIFE_REGEN) || stat.equals(StatKeys.LIFE_REGEN_PERCENT)
                    || stat.equals(StatKeys.MANA_MAX) || stat.equals(StatKeys.MANA_REGEN) || stat.equals(StatKeys.LIFE_RECOUP)
                    || stat.equals(StatKeys.LIFE_ON_HIT) || stat.equals(StatKeys.LIFE_ON_KILL);
            // 攻擊：攻擊專用的（tags 有 attack）與陷阱、手雷的傷害類；通用的元素、混沌傷害不算；攻速歸速度、暴擊歸暴擊（2026-10）
            case ATTACK -> damage(mod) && (mod.tags().contains("attack") || mod.tags().contains("trap") || mod.tags().contains("grenade"));
            // 法術：法術專用的（tags 有 spell）傷害類；施法速度歸速度、法術暴擊歸暴擊
            case CASTER -> damage(mod) && spell;
            case CRIT -> stat.equals(StatKeys.CRIT_CHANCE) || stat.equals(StatKeys.CRIT_MULTI);
            // 元素、物理與混沌：看傷害種類（攻擊、法術都算）；「額外火焰傷害」（damage.gain.火焰）也算
            case ELEMENTAL -> damage(mod) && (mod.tags().contains("elemental") || mod.tags().contains("fire")
                    || mod.tags().contains("cold") || mod.tags().contains("lightning"))
                    || stat.equals(StatKeys.DAMAGE_GAIN + "fire") || stat.equals(StatKeys.DAMAGE_GAIN + "cold")
                    || stat.equals(StatKeys.DAMAGE_GAIN + "lightning");
            case PHYS_CHAOS -> damage(mod) && (mod.tags().contains("physical") || mod.tags().contains("chaos"))
                    || stat.equals(StatKeys.DAMAGE_GAIN + "chaos");
            case ATTRIBUTE -> stat.startsWith("attr.");
            case SPEED -> stat.equals(StatKeys.ATTACK_SPEED) || stat.equals(StatKeys.CAST_SPEED) || stat.equals(StatKeys.MOVEMENT_SPEED);
            case DEFENCE -> stat.equals(StatKeys.ARMOUR) || stat.equals(StatKeys.EVASION) || stat.equals(StatKeys.ES_MAX) || stat.equals(StatKeys.BLOCK_CHANCE);
        };
    }

    /** 傷害類的修飾器：附加傷害、增加／更多傷害（固定值的「傷害」不算）。 */
    private static boolean damage(final Modifier mod) {
        final String stat = mod.stat();
        return stat.startsWith("damage.added.") || stat.equals(StatKeys.DAMAGE) && mod.type() != ModType.FLAT;
    }
}
