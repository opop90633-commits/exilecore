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
    CRIT("暴擊", "暴擊率與暴擊傷害加成詞綴"),
    ELEMENTAL("元素", "元素傷害詞綴（火焰、冰冷、閃電）"),
    PHYS_CHAOS("物理與混沌", "物理傷害與混沌傷害詞綴"),
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
        // 2026-10-20：有條件的版本（evasion@nohit、life.regen_percent@hit）照本來的屬性算
        final String full = mod.stat();
        final int at = full.indexOf('@');
        final String stat = at < 0 ? full : full.substring(0, at);
        final java.util.Set<String> tags = mod.tags();
        final boolean spell = tags.contains("spell");
        final boolean elementTag = tags.contains("elemental") || tags.contains("fire") || tags.contains("cold") || tags.contains("lightning");
        return switch (this) {
            case RESIST -> stat.startsWith("resist.");
            // 2026-10：受到的傷害回復為生命（life.recoup）、擊中／擊殺回復生命也算生命詞綴
            // 2026-10-20：每擊殺回復生命／魔力（life.per_kill、mana.per_kill、mana.on_kill）也算
            case LIFE -> stat.equals(StatKeys.LIFE_MAX) || stat.equals(StatKeys.LIFE_REGEN) || stat.equals(StatKeys.LIFE_REGEN_PERCENT)
                    || stat.equals(StatKeys.MANA_MAX) || stat.equals(StatKeys.MANA_REGEN) || stat.equals(StatKeys.LIFE_RECOUP)
                    || stat.equals(StatKeys.LIFE_ON_HIT) || stat.equals(StatKeys.LIFE_ON_KILL) || stat.equals(StatKeys.MANA_ON_KILL)
                    || stat.equals(StatKeys.LIFE_PER_KILL) || stat.equals(StatKeys.MANA_PER_KILL)
                    || stat.equals(StatKeys.LEECH_LIFE) || stat.equals(StatKeys.LEECH_MANA); // 2026-10-19：偷取為生命／魔力（PoE2 的標籤有生命）
            // 攻擊：攻擊專用的（tags 有 attack）與陷阱、手雷的傷害類；通用的元素、混沌傷害不算；攻速歸速度、暴擊歸暴擊（2026-10）
            // 2026-10-19：攻擊造成的物理傷害偷取為生命／魔力也算（PoE2 的標籤有攻擊）；2026-10-20：命中（PoE2 的標籤是攻擊）
            case ATTACK -> damage(mod) && (tags.contains("attack") || tags.contains("trap") || tags.contains("grenade"))
                    || leech(mod) && tags.contains("attack") || stat.equals(StatKeys.ACCURACY);
            // 法術：法術專用的（tags 有 spell）傷害類；施法速度歸速度、法術暴擊歸暴擊
            case CASTER -> damage(mod) && spell;
            // 2026-10-16：「增加 x% 暴擊傷害加成」（crit.bonus，飾品上的暴傷詞綴都是這個）也算——以前只認 crit.multi，暴傷詞綴吃不到
            case CRIT -> stat.equals(StatKeys.CRIT_CHANCE) || stat.equals(StatKeys.CRIT_MULTI) || stat.equals(StatKeys.CRIT_BONUS);
            // 元素、物理與混沌：看傷害種類（攻擊、法術都算）；「額外火焰傷害」（damage.gain.火焰）也算
            // 2026-10-20：「獲得 x% 傷害為額外的冰冷傷害」（damage.extra.cold，傳奇上的寫法）以前漏了；元素穿透、
            // 點燃／冰緩／凍結／感電／電擊的機率與效果（PoE2 的標籤有那種元素）也算
            case ELEMENTAL -> damage(mod) && elementTag
                    || element(stat, StatKeys.DAMAGE_GAIN) || element(stat, "damage.extra.")
                    || stat.equals(StatKeys.PENETRATION) && elementTag
                    || stat.startsWith("ignite.") || stat.startsWith("chill.") || stat.startsWith("freeze.") || stat.startsWith("shock.")
                    || stat.startsWith("electrocute.");
            // 2026-10-20：「獲得 x% 傷害為額外的混沌傷害」（damage.extra.chaos）以前漏了；混沌穿透、流血與中毒的機率與幅度、荊棘（物理）也算
            case PHYS_CHAOS -> damage(mod) && (tags.contains("physical") || tags.contains("chaos"))
                    || stat.equals(StatKeys.DAMAGE_GAIN + "chaos") || stat.equals("damage.extra.chaos") || stat.equals("damage.extra.physical")
                    || stat.equals(StatKeys.PENETRATION) && tags.contains("chaos")
                    || stat.startsWith("bleed.") || stat.startsWith("poison.") || stat.equals("thorns.min") || stat.equals("thorns.max");
            case ATTRIBUTE -> stat.startsWith("attr.");
            case SPEED -> stat.equals(StatKeys.ATTACK_SPEED) || stat.equals(StatKeys.CAST_SPEED) || stat.equals(StatKeys.MOVEMENT_SPEED);
            // 2026-10-20：能量護盾回充、護甲也擋元素、偏斜、物理傷害減免也算防禦
            case DEFENCE -> stat.equals(StatKeys.ARMOUR) || stat.equals(StatKeys.EVASION) || stat.equals(StatKeys.ES_MAX) || stat.equals(StatKeys.BLOCK_CHANCE)
                    || stat.equals(StatKeys.ES_RECHARGE) || stat.equals(StatKeys.ARMOUR_ELEMENTAL) || stat.equals(StatKeys.DEFLECTION)
                    || stat.equals(StatKeys.PHYS_REDUCTION);
        };
    }

    /** prefix 後面接火焰、冰冷、閃電（damage.gain.fire、damage.extra.cold…）。 */
    private static boolean element(final String stat, final String prefix) {
        return stat.equals(prefix + "fire") || stat.equals(prefix + "cold") || stat.equals(prefix + "lightning");
    }

    /** 2026-10-19：偷取為生命／魔力。 */
    private static boolean leech(final Modifier mod) {
        return mod.stat().equals(StatKeys.LEECH_LIFE) || mod.stat().equals(StatKeys.LEECH_MANA);
    }

    /** 傷害類的修飾器：附加傷害、增加／更多傷害（固定值的「傷害」不算）。 */
    private static boolean damage(final Modifier mod) {
        final String stat = mod.stat();
        return stat.startsWith("damage.added.") || stat.equals(StatKeys.DAMAGE) && mod.type() != ModType.FLAT;
    }
}
