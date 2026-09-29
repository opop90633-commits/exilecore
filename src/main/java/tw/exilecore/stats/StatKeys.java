package tw.exilecore.stats;

/**
 * 屬性鍵。設定檔可以直接引用這些字串；新增屬性只要加常數（或直接在設定檔用新字串）。
 */
public final class StatKeys {

    private StatKeys() {
    }

    // 資源
    public static final String LIFE_MAX = "life.max";
    public static final String LIFE_REGEN = "life.regen";          // 每秒回復量
    public static final String MANA_MAX = "mana.max";
    public static final String MANA_REGEN = "mana.regen";          // 每秒回復量
    public static final String ES_MAX = "es.max";
    public static final String ES_RECHARGE = "es.recharge";        // 每秒回充量（延遲後）

    // 防禦
    public static final String ARMOUR = "armour";
    public static final String EVASION = "evasion";
    public static final String BLOCK_CHANCE = "block.chance";      // 百分比點數（25 = 25%）
    public static final String RESIST_FIRE = "resist.fire";        // 百分比點數
    public static final String RESIST_COLD = "resist.cold";
    public static final String RESIST_LIGHTNING = "resist.lightning";
    public static final String RESIST_CHAOS = "resist.chaos";
    public static final String RESIST_MAX = "resist.max";          // 抗性上限，預設 75

    // 傷害：增加／更多用 DAMAGE 配標籤；附加固定值用 DAMAGE_ADDED_*
    public static final String DAMAGE = "damage";
    public static final String DAMAGE_ADDED_MIN = "damage.added.min";  // 配 damageType 標籤
    public static final String DAMAGE_ADDED_MAX = "damage.added.max";

    // 武器基礎（由裝備的武器提供，FLAT）
    public static final String WEAPON_MIN = "weapon.min";          // 配 damageType 標籤
    public static final String WEAPON_MAX = "weapon.max";
    public static final String WEAPON_APS = "weapon.aps";          // 每秒攻擊次數
    public static final String WEAPON_CRIT = "weapon.crit";        // 百分比點數

    // 速度與暴擊
    public static final String ATTACK_SPEED = "attack.speed";      // 只用 INCREASED／MORE
    public static final String CAST_SPEED = "cast.speed";
    public static final String CRIT_CHANCE = "crit.chance";        // INCREASED，基礎來自武器
    public static final String CRIT_MULTI = "crit.multi";          // 百分比點數，基礎 150
    public static final String ACCURACY = "accuracy";
    public static final String MOVEMENT_SPEED = "movement.speed";  // 只用 INCREASED

    // 屬性值
    public static final String STR = "attr.str";
    public static final String DEX = "attr.dex";
    public static final String INT = "attr.int";

    // 掉落
    public static final String DROP_QUANTITY = "drop.quantity";    // INCREASED
    public static final String DROP_RARITY = "drop.rarity";
}
