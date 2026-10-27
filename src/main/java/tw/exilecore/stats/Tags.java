package tw.exilecore.stats;

/** 情境標籤：技能、武器、傷害類型都用這些字串描述自己。 */
public final class Tags {

    private Tags() {
    }

    public static final String ATTACK = "attack";
    public static final String SPELL = "spell";
    public static final String MELEE = "melee";
    public static final String PROJECTILE = "projectile";
    public static final String AREA = "area";
    /** 遠程武器（弓、弩、法杖、長杖）：普攻是投射物。不是 PoE 的傷害標籤，只用來分辨武器。 */
    public static final String RANGED = "ranged";
    public static final String STRIKE = "strike";
    public static final String SLAM = "slam";
    /** 位移技能（疾風步、跳躍重擊、瞬影箭…）。 */
    public static final String MOVEMENT = "movement";
    /** 突進：一路衝過去、跳過去的位移（dash、leap；瞬影擊這種瞬移到敵人身邊的不算）。複擊不能掛（2026-10）。 */
    public static final String TRAVEL = "travel";
    /** 有持續時間的技能（地面效果、印記、增益、圖騰…）：吃「增加持續時間」。 */
    public static final String DURATION = "duration";
    /** 有冷卻時間的技能：吃「減少冷卻時間」。 */
    public static final String COOLDOWN = "cooldown";
    /** 榴彈（有引信的投射物：不直接打中敵人，歧射、回歸對它沒用）。 */
    public static final String GRENADE = "grenade";
    /** 骸爆（要有屍體才能放：圖騰、陷阱不能代放）。 */
    public static final String CORPSE = "corpse";
    /** 印記（單一目標的減益）。 */
    public static final String MARK = "mark";
    /** 召喚物：召喚技能本身，以及召喚物打出去的每一下（召喚物只吃帶這個標籤的修飾器）。 */
    public static final String MINION = "minion";
    public static final String TOTEM = "totem";
    /**
     * 2026-10-18：圖騰打出去的每一下（圖騰自己放的技能、英靈圖騰的英靈、引爆、焚鏈、怨燼）。不是 PoE 的標籤、沒有修飾器看它，
     * 只用來讓 CombatListener 分辨「這一下是圖騰打的」：照 PoE，圖騰命中的擊中回復生命、偷取不回給玩家。
     */
    public static final String TOTEM_HIT = "totem_hit";
    /** 鐘（驚雷之鐘）：算圖騰的一種，但圖騰列陣對它沒用。 */
    public static final String BELL = "bell";
    public static final String TRAP = "trap";
    public static final String AURA = "aura";
    /** 捷光環（2026-10，燼使、雷使、霜使）：保留魔力、放在面板上一直開，但不算光環（光環的輔助與光環效果不吃）。 */
    public static final String HERALD = "herald";
    public static final String WARCRY = "warcry";
    /** 常駐技能（2026-10）：放在技能面板上就一直生效（2026-10-26 焚心業火、星律拿掉了，現在是引星、主要寶石這類 yml 寫的）。 */
    public static final String PERSISTENT = "persistent";
    /** 荊棘（被近戰擊中時的反彈傷害）自己的標籤：「增加荊棘傷害」用。 */
    public static final String THORNS = "thorns";

    public static final String PHYSICAL = "physical";
    /** 2026-10-22：主要寶石（有 meta 區塊的技能：星咒圖騰、暴擊引星…）；輔助的 requires／excludes 不會寫它。 */
    public static final String META = "meta";
    public static final String FIRE = "fire";
    public static final String COLD = "cold";
    public static final String LIGHTNING = "lightning";
    public static final String CHAOS = "chaos";
    public static final String ELEMENTAL = "elemental";

    // 武器種類
    public static final String SWORD = "sword";
    public static final String AXE = "axe";
    public static final String MACE = "mace";
    public static final String DAGGER = "dagger";
    public static final String CLAW = "claw";
    public static final String WAND = "wand";
    public static final String STAFF = "staff";
    public static final String BOW = "bow";
    /** 弩（雙手遠程，重弩手）。 */
    public static final String CROSSBOW = "crossbow";
    /** 長棍（雙手近戰，棍僧）。不是長杖（staff，施法武器）。 */
    public static final String QUARTERSTAFF = "quarterstaff";
    /** 矛（單手近戰，長矛獵手）。 */
    public static final String SPEAR = "spear";
    /** 獸魂符（2026-10，雙手，德魯伊）：變身技能用。 */
    public static final String TALISMAN = "talisman";
    /** 變身技能（2026-10，德魯伊）：放了就變成那種形態（熊、狼人、飛龍）一段時間。 */
    public static final String SHAPESHIFT = "shapeshift";
    public static final String BEAR = "bear";
    public static final String WEREWOLF = "werewolf";
    public static final String WYVERN = "wyvern";
    public static final String ONE_HAND = "one_hand";
    public static final String TWO_HAND = "two_hand";
}
