package tw.exilecore.stats;

import java.util.HashSet;
import java.util.Locale;
import java.util.Set;

/**
 * 天賦樹的條件（2026-10-26 照 PoE2 0.5 的樹：while stationary／while moving／while Surrounded／on Low Life／vs Blinded…）。
 * <p>
 * tree.json 的一條 stat 可以帶 {@code "condition": "stationary"}，{@link tw.exilecore.passive.PassiveTree} 讀進來時用
 * {@link #apply} 換成引擎看得懂的寫法：
 * <ul>
 *   <li><b>自己的狀態</b>（{@link #SELF}）：屬性代號後面接 {@code @條件}（跟裝備詞綴的 {@link Recently} 同一套），
 *       屬性表重算時條件成立才算（{@link tw.exilecore.character.PlayerStateService}），條件變了就重算（每 10 tick 檢查一次）。
 *       前面加 {@code no} 是反過來（{@code nofulllife}＝不在滿血）。「最近…」的事件（{@link Recently#EVENTS} 與這裡的 {@link #EVENTS}）也能用。</li>
 *   <li><b>敵人的狀態</b>（{@code vs_} 開頭，{@link #ENEMY}）：變成修飾器的標籤；擊中時戰鬥那邊照目標的狀態把這些標籤加進那一下的情境
 *       （{@link tw.exilecore.combat.TargetConditions}），所以只有打到符合的敵人才吃得到（傷害、暴擊率、暴擊加成、獲得額外傷害…）。</li>
 * </ul>
 * 還沒有系統支撐的條件放在 {@link #RESERVED}：寫得進樹，但條件一律不成立，等那些系統接上
 * （{@link tw.exilecore.character.ConditionTracker#register}）。2026-10-26：變身、夥伴已經接上（RESERVED 現在是空的）。
 * <p>
 * 2026-10-26：「每 x 點怒氣」「每個圖騰」「最近每消耗一顆磐星」這種照數量的寫成<b>門檻條件</b>（{@link #COUNTED}）：
 * 代號後面接數字（{@code rage10}＝怒氣至少 10、{@code totems2}＝至少 2 個圖騰），樹上一行拆成好幾個門檻、各給一份。
 */
public final class TreeConditions {

    // ---- 自己的狀態 ----
    /** 靜止（這半秒內沒有移動）。 */
    public static final String STATIONARY = "stationary";
    /** 移動中。 */
    public static final String MOVING = "moving";
    /** 被包圍：身邊 {@value #SURROUNDED_RADIUS} 格內至少 {@value #SURROUNDED_COUNT} 隻敵人。 */
    public static final String SURROUNDED = "surrounded";
    /** 低血：生命在 {@value #LOW_FRACTION} 以下（PoE2 的 Low Life＝35%）。 */
    public static final String LOW_LIFE = "lowlife";
    /** 滿血。 */
    public static final String FULL_LIFE = "fulllife";
    /** 能量護盾全滿（而且有護盾）。 */
    public static final String FULL_ES = "fulles";
    /** 身上還有能量護盾。 */
    public static final String HAS_ES = "hases";
    /** 低魔力：魔力在 35% 以下。 */
    public static final String LOW_MANA = "lowmana";
    /** 偷取中（生命或魔力偷取還沒回完）。 */
    public static final String LEECHING = "leeching";
    /** 引導中（蓄力技能按著）。 */
    public static final String CHANNELLING = "channelling";
    /** 自己流血中。 */
    public static final String BLEEDING = "bleeding";
    /** 自己凍結中。 */
    public static final String FROZEN = "frozen";
    /** 雙持（主手與副手都是武器）。 */
    public static final String DUAL_WIELD = "dualwield";
    /** 副手拿盾。 */
    public static final String SHIELD = "shield";
    /** 主手拿近戰武器。 */
    public static final String MELEE_WEAPON = "meleeweapon";
    /** 2026-10-28 照 PoE2（轟天雷、毀面者、磐石之拳的北方之爪）：空手（主手、副手都沒拿武器）。 */
    public static final String UNARMED = "unarmed";
    /** 任何藥劑效果中（生命或魔力藥劑的回復還在進行）。 */
    public static final String ANY_FLASK = "anyflask";
    /** 臨在範圍裡有盟友（自己的召喚物、同隊的玩家）。 */
    public static final String ALLY = "ally";
    /** 臨在範圍裡有稀有或傳奇敵人（含 Boss）。 */
    public static final String RARE_NEARBY = "raremob";
    /** 變身中（德魯伊的熊／狼人／飛龍形態：SkillService 的形態增益還在）。 */
    public static final String SHAPESHIFTED = "shapeshifted";
    /** 夥伴（帶 companion 標籤的召喚物：荒原狼伴、灰狼隨…）活著而且在臨在範圍裡。 */
    public static final String COMPANION = "companion";
    /** 2026-10-26：受統治者增益影響（元素統治者、不死統治者；tw.exilecore.skill.TreeMechanics 接上）。 */
    public static final String ARCHON = "archon";
    // 2026-10-26 新增的自己的狀態
    /** 衝刺中（PoE2 Sprint：翻滾後按住翻滾鍵繼續跑；這裡＝按住 Ctrl 而且在移動、不在翻滾動作中）。 */
    public static final String SPRINTING = "sprinting";
    /** 翻滾動作中。 */
    public static final String ROLLING = "dodgerolling";
    /** 招架中（舉著帶招架的盾：舉盾增益有 flag.parry）。 */
    public static final String PARRYING = "parrying";
    /** 舉盾中（主動格擋：舉盾增益還在）。 */
    public static final String RAISING_SHIELD = "raisingshield";
    /** 自己被點燃。 */
    public static final String IGNITED = "ignited";
    /** 自己被冰緩。 */
    public static final String CHILLED = "chilled";
    /** 2026-10-28 照 PoE2（貝雷克的火與雷之樂「被感電時」）：自己被感電。 */
    public static final String SHOCKED = "shocked";
    /** 自己身上有傷害型異常（點燃、流血、中毒）。 */
    public static final String DAMAGING_AILMENT = "damagingailment";
    /** 魔力全滿。 */
    public static final String FULL_MANA = "fullmana";
    /** 生命偷取中。 */
    public static final String LIFE_LEECHING = "lifeleech";
    /** 魔力偷取中。 */
    public static final String MANA_LEECHING = "manaleech";
    /** 護符效果中（任何護符的增益還在）。 */
    public static final String CHARM_ACTIVE = "charmactive";
    /** 生命藥劑一次都喝不了（充能不夠）。 */
    public static final String NO_LIFE_FLASK = "nolifeflask";
    /** 受到任何先鋒（光環開著）影響。 */
    public static final String HERALD = "herald";
    public static final String HERALD_ASH = "heraldash";
    public static final String HERALD_ICE = "heraldice";
    public static final String HERALD_THUNDER = "heraldthunder";
    public static final String HERALD_PLAGUE = "heraldplague";
    public static final String HERALD_BLOOD = "heraldblood";
    /** 至少有一個圖騰（{@code totems1}）。 */
    public static final String TOTEM = "totem";
    /** 主手拿斧。 */
    public static final String WIELD_AXE = "wieldaxe";
    /** 主手拿長杖。 */
    public static final String WIELD_QUARTERSTAFF = "wieldquarterstaff";
    /** 3 格內沒有敵人。 */
    public static final String NO_ENEMY_NEAR = "noenemynear";
    /** 最近 10 秒內喝過生命藥劑。 */
    public static final String LIFE_FLASK_10S = "lifeflask10s";
    // 2026-10-27 照 PoE2：天賦樹最後一批（TreeLastKeys；成立與否在 tw.exilecore.skill.TreeLeftovers 用 ConditionTracker#register 接上，
    // 低能量護盾在 ConditionTracker）
    /** 有秘術增強（PoE2 while you have Arcane Surge）。 */
    public static final String ARCANE_SURGE = "arcanesurge";
    /** 低能量護盾：護盾在 35% 以下（沒有護盾也算）。 */
    public static final String LOW_ES = "lowes";
    /** 你的召喚物全部都是夥伴（至少一隻）。 */
    public static final String ALL_COMPANIONS = "allcompanions";
    /** 強化攻擊中（強化的那一下出手後 1 秒內）。 */
    public static final String EMPOWERED_ATTACK = "empoweredattack";
    /** 過去 20 秒用過需要榮耀的技能。 */
    public static final String GLORY_20S = "glory20s";
    /** 過去 8 秒用投射物攻擊擊中過、近戰擊中過。 */
    public static final String PROJECTILE_HIT_8S = "projhit8s";
    public static final String MELEE_HIT_8S = "meleehit8s";
    /** 能量護盾在過去 2 秒內開始回充。 */
    public static final String ES_RECHARGE_2S = "esrecharge2s";
    /** 站在感電、冰緩、點燃地面上。 */
    public static final String ON_SHOCKED_GROUND = "onshockedground";
    public static final String ON_CHILLED_GROUND = "onchilledground";
    public static final String ON_IGNITED_GROUND = "onignitedground";
    /** 最近獲得過怒氣。 */
    public static final String RAGE_GAINED = "ragegained";
    /** 2026-10-28 照 PoE2 Trinity 的品質：鼎元訣的三種親和加起來超過 {@link #TRINITY_HIGH_TOTAL}（SkillModule 接上）。 */
    public static final String TRINITY_HIGH = "trinityhigh";
    public static final double TRINITY_HIGH_TOTAL = 250;

    // ---- 「最近…」的新事件（照 Recently 的 4 秒） ----
    /** 最近重暈過敵人。 */
    public static final String STUNNED_ENEMY = "stunnedenemy";
    /** 引導的那一下（記最後一次引導的 tick；「引導中」看這個，不是 4 秒）。 */
    public static final String CHANNEL_EVENT = "channel";
    // 2026-10-26 新增的「最近…」
    /** 最近變成獸形（熊、狼人、飛龍）。 */
    public static final String SHAPESHIFT_EVENT = "shapeshift";
    /** 最近變回人形。 */
    public static final String REVERT_EVENT = "revert";
    /** 最近消耗過元素灌注。 */
    public static final String INFUSION_EVENT = "infusion";
    /** 最近獲得過銳星。 */
    public static final String POWER_GAIN = "powergain";
    /** 最近攻擊過（放過攻擊技能）。 */
    public static final String ATTACK_EVENT = "attack";
    /** 最近觸發過技能。 */
    public static final String TRIGGERED_EVENT = "triggered";
    /** 最近有召喚物死掉。 */
    public static final String MINION_DIED = "miniondied";
    /** 最近用過印記。 */
    public static final String MARK_EVENT = "markused";
    /** 最近成功招架。 */
    public static final String PARRIED_EVENT = "parried";
    /** 最近舉盾擋下過。 */
    public static final String ACTIVE_BLOCK_EVENT = "activeblock";
    /** 最近冰緩、感電、點燃、釘住過敵人。 */
    public static final String CHILLED_ENEMY = "chilledenemy";
    public static final String SHOCKED_ENEMY = "shockedenemy";
    public static final String IGNITED_ENEMY = "ignitedenemy";
    public static final String PINNED_ENEMY = "pinnedenemy";
    /** 最近有一下沒暴擊的擊中。 */
    public static final String NON_CRIT = "noncrit";
    /** 最近用過魔力藥劑（只算魔力的）。 */
    public static final String MANA_FLASK_USED = "manaflaskused";
    /** 生命藥劑喝的那一下（「10 秒內」看這個的 tick）。 */
    public static final String LIFE_FLASK_USED = "lifeflaskused";

    // ---- 門檻條件（代號＋數字） ----
    /** 怒氣至少 N。 */
    public static final String RAGE_AT_LEAST = "rage";
    /** 至少 N 個圖騰。 */
    public static final String TOTEMS_AT_LEAST = "totems";
    /** 臨在範圍裡自己的召喚物至少 N 隻。 */
    public static final String MINIONS_AT_LEAST = "minions";
    /** 近距離內至少 N 隻敵人。 */
    public static final String CLOSE_ENEMIES_AT_LEAST = "closeenemies";
    /** 最近消耗過至少 N 顆磐星。 */
    public static final String ENDURANCE_SPENT = "endurancespent";
    /** 最近戰吼過至少 N 次。 */
    public static final String WARCRIES = "warcries";
    /** 最近用過至少 N 種不同的戰吼。 */
    public static final String WARCRY_TYPES = "warcrytypes";
    /** 最近被重暈至少 N 次。 */
    public static final String STUNS = "stuns";
    /** 最近 8 秒放過至少 N 種不同的法術。 */
    public static final String SPELL_TYPES = "spelltypes";
    /** 最近 8 秒放過至少 N 種不同的非瞬發法術。 */
    public static final String SLOW_SPELL_TYPES = "slowspelltypes";
    /** 最近 8 秒用過至少 N 種不同的非瞬發攻擊。 */
    public static final String SLOW_ATTACK_TYPES = "slowattacktypes";
    /** 最近 8 秒點燃過至少 N 個敵人。 */
    public static final String IGNITED_ENEMIES = "ignitedenemies";
    /** 最近 8 秒冰凍過至少 N 個敵人。 */
    public static final String FROZEN_ENEMIES = "frozenenemies";
    /** 最近被敵人擊中至少 N 次。 */
    public static final String HITS_TAKEN = "hitstaken";
    // 2026-10-27 照 PoE2：天賦樹最後一批的「每…」
    /** 最近回響過至少 N 次法術（法術回響）。 */
    public static final String ECHOES = "echoes";
    /** 過去 10 秒射過至少 N 種不同的弩彈藥（弩的攻擊、榴彈）。 */
    public static final String AMMO_TYPES = "ammotypes";
    /** 過去 8 秒丟過至少 N 種不同的榴彈。 */
    public static final String GRENADE_TYPES = "grenadetypes";
    /** 最近讓敵人中毒至少 N 次。 */
    public static final String POISONS = "poisons";
    /** 最近觸發過至少 N 次危害。 */
    public static final String HAZARDS = "hazards";
    /** 你身上至少 N 個詛咒（TreeLeftovers 接上）。 */
    public static final String SELF_CURSES = "selfcurses";
    /** 至少 N 種不同的祭品（祭柱）還在（TreeLeftovers 接上）。 */
    public static final String OFFERINGS = "offerings";
    public static final Set<String> COUNTED = Set.of(RAGE_AT_LEAST, TOTEMS_AT_LEAST, MINIONS_AT_LEAST, CLOSE_ENEMIES_AT_LEAST, ENDURANCE_SPENT,
            HITS_TAKEN, WARCRIES, WARCRY_TYPES, STUNS, SPELL_TYPES, SLOW_SPELL_TYPES, SLOW_ATTACK_TYPES, IGNITED_ENEMIES, FROZEN_ENEMIES,
            ECHOES, AMMO_TYPES, GRENADE_TYPES, POISONS, HAZARDS, SELF_CURSES, OFFERINGS);
    /** 敵人身上至少 N 種元素異常（點燃、冰緩、凍結、感電、電擊）。 */
    public static final String VS_ELEMENTAL_AILMENTS = "vs_eleail";

    /** 門檻條件：代號與數字。 */
    public record Counted(String base, int at) {
    }

    /** 「rage10」→ (rage, 10)；不是門檻條件回傳 null。 */
    public static Counted counted(final String condition) {
        int digits = condition.length();
        while (digits > 0 && Character.isDigit(condition.charAt(digits - 1))) {
            digits--;
        }
        if (digits == condition.length() || digits == 0 || condition.length() - digits > 3) {
            return null;
        }
        final String base = condition.substring(0, digits);
        return COUNTED.contains(base) ? new Counted(base, Integer.parseInt(condition.substring(digits))) : null;
    }

    // ---- 敵人的狀態（擊中時的標籤） ----
    public static final String VS_FAR = "vs_far";
    public static final String VS_NEAR = "vs_near";
    public static final String VS_CLOSE = "vs_close";
    public static final String VS_BLINDED = "vs_blinded";
    public static final String VS_IMMOBILISED = "vs_immobilised";
    public static final String VS_DAZED = "vs_dazed";
    public static final String VS_HINDERED = "vs_hindered";
    public static final String VS_HEAVY_STUNNED = "vs_heavystunned";
    public static final String VS_MARKED = "vs_marked";
    public static final String VS_RARE = "vs_rare";
    public static final String VS_FULL_LIFE = "vs_fulllife";
    public static final String VS_LOW_LIFE = "vs_lowlife";
    public static final String VS_AILMENT = "vs_ailment";
    /** 2026-10-26：敵人有元素異常（點燃、冰緩、凍結、感電、電擊；PoE2 Elemental Ailments）。 */
    public static final String VS_ELEMENTAL_AILMENT = "vs_elementalailment";
    public static final String VS_BROKEN_ARMOUR = "vs_brokenarmour";
    public static final String VS_CURSED = "vs_cursed";
    public static final String VS_FROZEN = "vs_frozen";
    public static final String VS_CHILLED = "vs_chilled";
    public static final String VS_SHOCKED = "vs_shocked";
    public static final String VS_ELECTROCUTED = "vs_electrocuted";
    public static final String VS_IGNITED = "vs_ignited";
    public static final String VS_BLEEDING = "vs_bleeding";
    public static final String VS_POISONED = "vs_poisoned";
    // 2026-10-26 新增
    public static final String VS_PINNED = "vs_pinned";
    public static final String VS_EXPOSED = "vs_exposed";
    public static final String VS_MAIMED = "vs_maimed";
    public static final String VS_SLOWED = "vs_slowed";
    public static final String VS_NOT_POISONED = "vs_notpoisoned";
    public static final String VS_NO_ELEMENTAL_AILMENT = "vs_noeleail";
    /** 怪物的種類：照原版的生物種類（亡靈、野獸、惡魔、構造體、人形）。 */
    public static final String VS_UNDEAD = "vs_undead";
    public static final String VS_BEAST = "vs_beast";
    public static final String VS_DEMON = "vs_demon";
    public static final String VS_CONSTRUCT = "vs_construct";
    public static final String VS_HUMANOID = "vs_humanoid";
    /** 2026-10-27：敵人身上有切割（PoE2 Incision；切割在被流血時用掉）。 */
    public static final String VS_INCISION = "vs_incision";
    /** 2026-10-27：敵人最近（4 秒內）進入、離開了你的存在範圍（tw.exilecore.skill.TreeLeftovers 記）。 */
    public static final String VS_ENTERED_PRESENCE = "vs_enteredpresence";
    public static final String VS_EXITED_PRESENCE = "vs_exitedpresence";

    /** 被包圍：幾格內（PoE2 是 1.5 格內；方塊世界的怪有身體寬度，放寬到 2.5 格）。 */
    public static final double SURROUNDED_RADIUS = 2.5;
    /** 被包圍：至少幾隻敵人（PoE2：5 隻）。 */
    public static final int SURROUNDED_COUNT = 5;
    /** 「遠處」：超過 6 格（＝6 格）。 */
    public static final double FAR_RANGE = 6;
    /** 「2 格內」。 */
    public static final double NEAR_RANGE = 2;
    /** 低血、低魔力的比例。 */
    public static final double LOW_FRACTION = 0.35;
    /** 「引導中」：最後一次引導的那一下之後多久還算（tick）。 */
    public static final long CHANNEL_GRACE = 20;

    public static final Set<String> SELF = Set.of(STATIONARY, MOVING, SURROUNDED, LOW_LIFE, FULL_LIFE, FULL_ES, HAS_ES, LOW_MANA,
            LEECHING, CHANNELLING, BLEEDING, FROZEN, DUAL_WIELD, SHIELD, MELEE_WEAPON, ANY_FLASK, ALLY, RARE_NEARBY, SHAPESHIFTED, COMPANION,
            SPRINTING, ROLLING, PARRYING, RAISING_SHIELD, IGNITED, CHILLED, DAMAGING_AILMENT, FULL_MANA, LIFE_LEECHING, MANA_LEECHING,
            CHARM_ACTIVE, NO_LIFE_FLASK, HERALD, HERALD_ASH, HERALD_ICE, HERALD_THUNDER, HERALD_PLAGUE, HERALD_BLOOD, TOTEM, WIELD_AXE,
            WIELD_QUARTERSTAFF, NO_ENEMY_NEAR, LIFE_FLASK_10S, ARCHON,
            ARCANE_SURGE, LOW_ES, ALL_COMPANIONS, EMPOWERED_ATTACK, GLORY_20S, PROJECTILE_HIT_8S, MELEE_HIT_8S, ES_RECHARGE_2S, // 2026-10-27
            ON_SHOCKED_GROUND, ON_CHILLED_GROUND, ON_IGNITED_GROUND, RAGE_GAINED,
            TRINITY_HIGH, UNARMED, SHOCKED); // 2026-10-28
    /** 還沒有系統支撐的條件（一律不成立）。2026-10-26：變身、夥伴接上了，現在沒有。 */
    public static final Set<String> RESERVED = Set.of();
    public static final Set<String> EVENTS = Set.of(STUNNED_ENEMY, SHAPESHIFT_EVENT, REVERT_EVENT, INFUSION_EVENT, POWER_GAIN, ATTACK_EVENT,
            TRIGGERED_EVENT, MINION_DIED, MARK_EVENT, PARRIED_EVENT, ACTIVE_BLOCK_EVENT, CHILLED_ENEMY, SHOCKED_ENEMY, IGNITED_ENEMY,
            PINNED_ENEMY, NON_CRIT, MANA_FLASK_USED,
            KeystoneKeys.RECENT_ATTACK, KeystoneKeys.RECENT_SPELL, // 2026-10-26：最近攻擊過、施放過法術（KeystoneKeys）
            TreeLastKeys.EVENT_STUNNED, TreeLastKeys.EVENT_STUNNED_TWO_HAND, TreeLastKeys.EVENT_HIT_FIRE, TreeLastKeys.EVENT_HIT_COLD, TreeLastKeys.EVENT_HIT_LIGHTNING); // 2026-10-27
    public static final Set<String> ENEMY;

    static {
        final Set<String> enemy = new HashSet<>(Set.of(VS_FAR, VS_NEAR, VS_CLOSE, VS_BLINDED, VS_IMMOBILISED, VS_DAZED, VS_HINDERED,
                VS_HEAVY_STUNNED, VS_MARKED, VS_RARE, VS_FULL_LIFE, VS_LOW_LIFE, VS_AILMENT, VS_BROKEN_ARMOUR, VS_CURSED, VS_FROZEN,
                VS_CHILLED, VS_SHOCKED, VS_ELECTROCUTED, VS_IGNITED, VS_BLEEDING, VS_POISONED, VS_PINNED, VS_EXPOSED, VS_MAIMED, VS_SLOWED,
                VS_NOT_POISONED, VS_NO_ELEMENTAL_AILMENT, VS_ELEMENTAL_AILMENT, VS_UNDEAD, VS_BEAST, VS_DEMON, VS_CONSTRUCT, VS_HUMANOID,
                VS_INCISION, VS_ENTERED_PRESENCE, VS_EXITED_PRESENCE)); // 2026-10-27
        for (int i = 1; i <= 5; i++) {
            enemy.add(VS_ELEMENTAL_AILMENTS + i);
        }
        ENEMY = Set.copyOf(enemy);
    }

    private TreeConditions() {
    }

    /** 自己的狀態或「最近」事件（不含 no 前綴）。 */
    public static boolean isSelf(final String condition) {
        return SELF.contains(condition) || EVENTS.contains(condition) || Recently.EVENTS.contains(condition) || counted(condition) != null;
    }

    /** 這個條件（可以有 no 前綴）認不認得。 */
    public static boolean known(final String condition) {
        if (condition == null) {
            return false;
        }
        final String text = condition.trim().toLowerCase(Locale.ROOT);
        if (ENEMY.contains(text) || isSelf(text)) {
            return true;
        }
        return text.startsWith("no") && isSelf(text.substring(2));
    }

    /**
     * 把條件套到修飾器上：自己的狀態 → 屬性代號接 {@code @條件}；敵人的狀態 → 加一個標籤。沒有條件原樣回傳；
     * 不認得的條件當成「永遠不成立」（屬性代號接 {@code @條件}，屬性表那邊不認得就不算，寫錯不會偷偷生效）。
     */
    public static Modifier apply(final Modifier modifier, final String condition) {
        if (condition == null || condition.isBlank()) {
            return modifier;
        }
        final String text = condition.trim().toLowerCase(Locale.ROOT);
        if (ENEMY.contains(text)) {
            final Set<String> tags = new HashSet<>(modifier.tags());
            tags.add(text);
            return modifier.withTags(tags);
        }
        return new Modifier(modifier.stat() + "@" + text, modifier.type(), modifier.value(), modifier.tags(), modifier.source());
    }

    /** 修飾器有沒有敵人狀態的標籤（戰鬥那邊要不要看目標的狀態）。 */
    public static boolean hasEnemyCondition(final Modifier modifier) {
        for (final String tag : modifier.tags()) {
            if (tag.startsWith("vs_") && ENEMY.contains(tag)) {
                return true;
            }
        }
        return false;
    }
}
