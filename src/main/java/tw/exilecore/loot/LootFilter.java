package tw.exilecore.loot;

import tw.exilecore.item.Rarity;
import tw.exilecore.monster.DropTable;

import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.EnumSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.TreeMap;
import java.util.TreeSet;

/**
 * 一個角色的掉落過濾器（PoE 的 loot filter）：哪些東西不要掉（換成金幣）。
 * 存在角色列（characters.loot_filter），用 {@link #encode()} 的短字串。純資料，不依賴 Bukkit。
 * <p>
 * 2026-11-03 照站長整個整理過一遍，裝備的判斷順序固定是：
 * <ol>
 *   <li><b>基底</b>（{@link BaseRule}）：每一種基底（武器、防具、盾、箭袋、飾品、珠寶、遺時星晶、劫盜特殊項鍊、地圖）各自是
 *       顯示／隱藏／必撿。<b>隱藏的基底絕對不撿</b>（有 T1 詞綴也不撿）；必撿的看「保留好詞綴」的門檻：有 T 門檻以上詞綴的一律撿
 *       （不看稀有度、等級差、同類同系；門檻是關就全部撿）；顯示的才往下看。</li>
 *   <li><b>傳奇</b>（{@link UniqueGroup}）：照來源分六組各自撿不撿；追逐級永遠撿。傳奇只看這個，不看稀有度、等級差、同類同系。</li>
 *   <li>地圖只看「地圖依稀有度」。</li>
 *   <li>等級差、不同類的武器、不同系的防具：不撿（好詞綴救不回來）。</li>
 *   <li>稀有度被勾成隱藏：不撿，但有好詞綴（保留好詞綴）的撿回來。</li>
 * </ol>
 * 這次之前有「只撿的基底」（白名單，只管有挑的那幾類）、「只撿的基底也要好詞綴」、珠寶依稀有度、遺時星晶依稀有度與種類、
 * 劫盜特殊基底三選一、傳奇一格；全部併進基底與傳奇分組，舊存檔讀進來照 {@link #migrate} 換算。
 * <p>
 * 選了預設組合（寬鬆、標準、嚴格）的角色只記組合代號，讀出來時照 loot.yml 目前的設定；
 * 在選單上自己改過就變成「自訂」，記下每一項。基底、傳奇分組、護符、音效這些是另外記的，換預設組合也留著。
 */
public final class LootFilter {

    /** 在選單上自己改過的過濾器。 */
    public static final String CUSTOM = "custom";
    /** 存檔格式的版本（2026-11-03 起 2；沒寫的是舊格式，讀進來要 {@link #migrate}）。 */
    static final int VERSION = 2;

    /** 藥劑的過濾規則。 */
    public enum FlaskRule {
        ALL("全部顯示"),
        BETTER("只顯示比藥劑欄裡好的"),
        NONE("全部隱藏");

        private final String displayName;

        FlaskRule(final String displayName) {
            this.displayName = displayName;
        }

        public String displayName() {
            return displayName;
        }

        /** 選單上點一下換下一種。 */
        public FlaskRule next() {
            return values()[(ordinal() + 1) % values().length];
        }

        public static FlaskRule parse(final String text, final FlaskRule def) {
            if (text == null) {
                return def;
            }
            for (final FlaskRule rule : values()) {
                if (rule.name().equalsIgnoreCase(text.trim())) {
                    return rule;
                }
            }
            return def;
        }
    }

    /**
     * 一種基底撿不撿（2026-11-03 照站長）。沒設定的是顯示。
     */
    public enum BaseRule {
        /** 照其他規則（稀有度、等級差、同類同系、好詞綴）決定。 */
        SHOW("顯示", "show"),
        /** 絕對不撿（有 T1 詞綴也不撿），換成金幣。傳奇不受影響（傳奇照分組）。 */
        HIDE("隱藏", "hide"),
        /**
         * 有「保留好詞綴」門檻以上的詞綴就一律撿（不看稀有度、等級差、同類同系）；門檻是關的話全部撿。地圖不看詞綴（全部撿）。
         * 2026-11-03 照站長（這次之前同一天的版本是不看詞綴、全部撿）。劫盜特殊項鍊預設是這個。
         */
        ALWAYS("必撿", "always");

        private final String displayName;
        private final String id;

        BaseRule(final String displayName, final String id) {
            this.displayName = displayName;
            this.id = id;
        }

        public String displayName() {
            return displayName;
        }

        public String id() {
            return id;
        }
    }

    /**
     * 傳奇照來源分組（2026-11-03 照站長：分別過濾不同的傳奇）。分法看 {@link #of}。
     */
    public enum UniqueGroup {
        /** 追逐級：永遠撿，不能隱藏。 */
        CHASE("追逐級", "chase", "地圖 Boss 極少掉的那幾件（全服公告）；永遠會撿，不能隱藏"),
        /** 一般傳奇：地圖 Boss、賭博、機會石拿得到的。 */
        COMMON("一般傳奇", "common", "地圖 Boss 會掉、商人賭得到、機會石做得出來的"),
        /** 地圖事件（神龕、寶箱、保險箱、裂隙、流寇、深淵裂縫、祭壇、幻霧）掉的。 */
        EVENT("事件傳奇", "event", "地圖事件（神龕、寶箱、保險箱、裂隙、流寇、深淵裂縫、祭壇、幻霧）掉的"),
        /** 巔峰 Boss 專屬（覺醒之主・虛星、七隻事件巔峰 Boss、迷霧之主、審判者、不朽統帥、淵鯨、世界 Boss）。 */
        PINNACLE("巔峰 Boss 專屬", "pinnacle", "覺醒之主・虛星、七隻事件巔峰 Boss、迷霧之主、審判者、不朽統帥、淵鯨、世界 Boss 才掉的"),
        /** 終局 Boss、極境專屬。 */
        ENDGAME("終局・極境專屬", "endgame", "四個終局 Boss 與極境才掉的"),
        /** 劫盜的大金庫掉的。 */
        HEIST("劫盜傳奇", "heist", "劫盜的大金庫才掉的");

        /** 事件傳奇的來源（uniques.yml 的 sources；map_events.yml 的事件代號）。 */
        static final Set<String> EVENT_SOURCES = Set.of("strongbox", "safe", "shrine", "breach", "exile", "abyss", "ritual", "delirium");
        /** 不在地圖設定檔的巔峰裡、但也算巔峰 Boss 專屬的來源。 */
        // 2026-11-06 照站長「關掉一般傳奇會把一些 Boss 專屬的一起過濾掉」：迷霧回廊、絕境、軍團三隻災厄級 Boss（fog_trial／ultimatum／legion）
        //   的專屬傳奇也算巔峰 Boss 專屬；這次之前不在任何一組的來源裡，被當成一般傳奇
        static final Set<String> EXTRA_PINNACLE_SOURCES = Set.of("abyss_whale", "world_boss", "fog_trial", "ultimatum", "legion");

        private final String displayName;
        private final String id;
        private final String about;

        UniqueGroup(final String displayName, final String id, final String about) {
            this.displayName = displayName;
            this.id = id;
            this.about = about;
        }

        public String displayName() {
            return displayName;
        }

        public String id() {
            return id;
        }

        public String about() {
            return about;
        }

        static UniqueGroup byId(final String id) {
            for (final UniqueGroup group : values()) {
                if (group.id.equalsIgnoreCase(id)) {
                    return group;
                }
            }
            return null;
        }

        /**
         * 一件傳奇是哪一組：追逐級 → 巔峰（來源在 pinnacleIds、淵鯨、世界 Boss）→ 終局・極境（來源在 finalIds 或 uber_ 開頭）
         * → 劫盜 → 事件（來源在 {@link #EVENT_SOURCES}）→ 其他都是一般（map_boss、gamble、chance、某張地圖的 Boss、禁止掉落的）。
         *
         * @param chase       uniques.yml 的 chase
         * @param sources     uniques.yml 的 sources
         * @param finalIds    終局的代號（maps.yml 的 finals，不含巔峰）
         * @param pinnacleIds 巔峰的代號（maps.yml 的 finals 裡有 pinnacle 的）
         */
        public static UniqueGroup of(final boolean chase, final Set<String> sources, final Set<String> finalIds, final Set<String> pinnacleIds) {
            if (chase) {
                return CHASE;
            }
            final Set<String> from = sources == null ? Set.of() : sources;
            for (final String source : from) {
                if (pinnacleIds != null && pinnacleIds.contains(source) || EXTRA_PINNACLE_SOURCES.contains(source)) {
                    return PINNACLE;
                }
            }
            for (final String source : from) {
                if (source.startsWith("uber_") || "final".equals(source) || finalIds != null && finalIds.contains(source)) {
                    return ENDGAME;
                }
            }
            if (from.contains("heist")) {
                return HEIST;
            }
            for (final String source : from) {
                if (EVENT_SOURCES.contains(source)) {
                    return EVENT;
                }
            }
            return COMMON;
        }
    }

    /** 武器的種類標籤（「只撿跟手上同類的武器」看這些）。 */
    public static final Set<String> WEAPON_CLASSES = Set.of("sword", "axe", "mace", "dagger", "claw", "wand", "staff", "bow",
            "sceptre", "crossbow", "quarterstaff", "spear", "talisman"); // 2026-10：漏了獸魂符（拿巨錘也撿得到）
    /** 防具的系（力量／敏捷／智慧系；「只撿跟身上同系的防具」看這些）。 */
    public static final Set<String> ARMOUR_FAMILIES = Set.of("str_armour", "dex_armour", "int_armour");
    /** 「等級差」的選項（0 = 關）：物品等級比角色低超過這麼多級的裝備隱藏。 */
    public static final int[] LEVEL_GAPS = {0, 10, 20, 30};
    /** 「未切割技能寶石至少幾級」的選項（0 = 關）。 */
    public static final int[] GEM_LEVELS = {0, 5, 10, 15};
    /** 「未切割輔助寶石至少第幾階」的選項（0 = 關）。 */
    public static final int[] SUPPORT_TIERS = {0, 2, 3};
    /** 「地圖依稀有度隱藏」的選項：0 = 全部顯示、1 = 隱藏普通（白）地圖、2 = 只撿稀有（黃）地圖、3 = 全部隱藏（傳奇地圖照分組）。 */
    public static final int[] MAP_RARITIES = {0, 1, 2, 3};
    /** 「保留有 T 幾以上詞綴的裝備」的選項（0 = 關；1 = 有 T1 詞綴才保留，數字越大越寬）。 */
    public static final int[] AFFIX_TIERS = {0, 1, 2, 3, 4, 5};

    /** 地圖稀有度規則的名稱（選單與摘要用）。 */
    public static String mapRarityName(final int value) {
        return switch (value) {
            case 1 -> "隱藏普通（白）地圖";
            case 2 -> "只撿稀有（黃）地圖";
            case 3 -> "全部隱藏";
            default -> "全部顯示";
        };
    }

    /**
     * 進階規則（預設組合與自訂都有）。
     *
     * @param weaponsLikeMine  只撿跟手上同類的武器（手上拿弓就只撿弓；雙手武器的人不撿盾；沒拿弓的人不撿箭袋）；沒拿武器不管
     * @param armourLikeMine   只撿跟身上同系的防具與盾（力量系／敏捷系／智慧系，看身上穿的）；沒穿不管
     * @param levelGap         物品等級比角色低超過這麼多級的裝備隱藏（0 = 關；傳奇、珠寶、地圖不管）
     * @param minSkillGemLevel 未切割技能寶石低於這個等級隱藏（0 = 關）
     * @param minSupportTier   未切割輔助寶石低於這一階隱藏（0 = 關）
     * @param mapRarity        地圖依稀有度隱藏：0 = 關、1 = 隱藏普通（白）地圖、2 = 普通與魔法（藍）都隱藏、3 = 全部隱藏
     * @param keepAffixTier    保留有 T 幾以上詞綴的裝備：身上有一條詞綴的階級是 T1～T這個數字，被稀有度藏起來的照樣撿
     *                         （基底隱藏、等級差、不同類的不救；0 = 關；未鑑定的也看得到，過濾器知道它的詞綴）
     */
    public record Rules(boolean weaponsLikeMine, boolean armourLikeMine, int levelGap, int minSkillGemLevel, int minSupportTier,
                        int mapRarity, int keepAffixTier) {
        public static final Rules NONE = new Rules(false, false, 0, 0, 0, 0, 0);

        /** 沒有「保留詞綴階級」（舊的六項）。 */
        public Rules(final boolean weaponsLikeMine, final boolean armourLikeMine, final int levelGap, final int minSkillGemLevel,
                     final int minSupportTier, final int mapRarity) {
            this(weaponsLikeMine, armourLikeMine, levelGap, minSkillGemLevel, minSupportTier, mapRarity, 0);
        }

        Rules withWeaponsLikeMine(final boolean value) {
            return new Rules(value, armourLikeMine, levelGap, minSkillGemLevel, minSupportTier, mapRarity, keepAffixTier);
        }

        Rules withArmourLikeMine(final boolean value) {
            return new Rules(weaponsLikeMine, value, levelGap, minSkillGemLevel, minSupportTier, mapRarity, keepAffixTier);
        }

        Rules withLevelGap(final int value) {
            return new Rules(weaponsLikeMine, armourLikeMine, Math.max(0, value), minSkillGemLevel, minSupportTier, mapRarity, keepAffixTier);
        }

        Rules withMinSkillGemLevel(final int value) {
            return new Rules(weaponsLikeMine, armourLikeMine, levelGap, Math.max(0, value), minSupportTier, mapRarity, keepAffixTier);
        }

        Rules withMinSupportTier(final int value) {
            return new Rules(weaponsLikeMine, armourLikeMine, levelGap, minSkillGemLevel, Math.max(0, value), mapRarity, keepAffixTier);
        }

        Rules withMapRarity(final int value) {
            return new Rules(weaponsLikeMine, armourLikeMine, levelGap, minSkillGemLevel, minSupportTier, Math.max(0, Math.min(3, value)), keepAffixTier);
        }

        Rules withKeepAffixTier(final int value) {
            return new Rules(weaponsLikeMine, armourLikeMine, levelGap, minSkillGemLevel, minSupportTier, mapRarity,
                    Math.max(0, Math.min(AFFIX_TIERS[AFFIX_TIERS.length - 1], value)));
        }
    }

    /** 2026-10-22 的舊記號：候選物的標籤裡有這個 = 追逐級（舊呼叫法；現在請用 {@link Candidate#uniqueGroup()}）。 */
    public static final String ALWAYS_KEEP_TAG = "exilecore:keep_unique";

    /**
     * 一件要判斷的掉落（純資料，撿取模組從掉落與做好的物品組出來）。
     *
     * @param kind          掉落種類
     * @param rarity        裝備的稀有度（其他種類 null）
     * @param currencyId    通貨代號（其他種類 null）
     * @param flaskUpgrade  藥劑比藥劑欄裡同一種的好
     * @param itemLevel     裝備的物品等級（不知道 0）
     * @param tags          裝備基底的標籤（weapon、sword、shield、armour、str_armour、map、jewel…）
     * @param mapTier       地圖的階級（不是地圖 0）
     * @param gemLevel      未切割技能寶石的等級／輔助寶石的階級（不知道 0）
     * @param bestAffixRank 裝備身上最好的詞綴是 T 幾（1 = T1；沒有詞綴或不知道 0）
     * @param baseId        基底代號（不知道 null）
     * @param uniqueGroup   傳奇是哪一組（不是傳奇或不知道 null；不知道的傳奇當一般傳奇，標籤有 {@link #ALWAYS_KEEP_TAG} 的當追逐級）
     */
    public record Candidate(DropTable.Kind kind, Rarity rarity, String currencyId, boolean flaskUpgrade, int itemLevel,
                            Set<String> tags, int mapTier, int gemLevel, int bestAffixRank, String baseId, UniqueGroup uniqueGroup,
                            String uniqueId) {
        public Candidate {
            tags = tags == null ? Set.of() : Set.copyOf(tags);
        }

        /** 2026-11-04：不知道是哪一件傳奇的（uniqueId 是傳奇代號，每種傳奇各自隱藏用；舊的呼叫法）。 */
        public Candidate(final DropTable.Kind kind, final Rarity rarity, final String currencyId, final boolean flaskUpgrade,
                         final int itemLevel, final Set<String> tags, final int mapTier, final int gemLevel, final int bestAffixRank,
                         final String baseId, final UniqueGroup uniqueGroup) {
            this(kind, rarity, currencyId, flaskUpgrade, itemLevel, tags, mapTier, gemLevel, bestAffixRank, baseId, uniqueGroup, null);
        }

        /** 不知道傳奇分組的（舊的呼叫法）。 */
        public Candidate(final DropTable.Kind kind, final Rarity rarity, final String currencyId, final boolean flaskUpgrade,
                         final int itemLevel, final Set<String> tags, final int mapTier, final int gemLevel, final int bestAffixRank,
                         final String baseId) {
            this(kind, rarity, currencyId, flaskUpgrade, itemLevel, tags, mapTier, gemLevel, bestAffixRank, baseId, null, null);
        }

        /** 不知道基底的（舊的呼叫法）。 */
        public Candidate(final DropTable.Kind kind, final Rarity rarity, final String currencyId, final boolean flaskUpgrade,
                         final int itemLevel, final Set<String> tags, final int mapTier, final int gemLevel, final int bestAffixRank) {
            this(kind, rarity, currencyId, flaskUpgrade, itemLevel, tags, mapTier, gemLevel, bestAffixRank, null);
        }

        /** 不知道詞綴階級的（舊的呼叫法）。 */
        public Candidate(final DropTable.Kind kind, final Rarity rarity, final String currencyId, final boolean flaskUpgrade,
                         final int itemLevel, final Set<String> tags, final int mapTier, final int gemLevel) {
            this(kind, rarity, currencyId, flaskUpgrade, itemLevel, tags, mapTier, gemLevel, 0);
        }

        /** 只有種類與稀有度／通貨的（舊的呼叫法、測試用）。 */
        public static Candidate simple(final DropTable.Kind kind, final Rarity rarity, final String currencyId, final boolean flaskUpgrade) {
            return new Candidate(kind, rarity, currencyId, flaskUpgrade, 0, Set.of(), 0, 0);
        }

        boolean map() {
            return tags.contains("map");
        }

        boolean jewel() {
            return tags.contains("jewel");
        }

        /** 傳奇是哪一組（不知道的：標籤有舊記號的追逐級，其他一般傳奇）。 */
        UniqueGroup groupOrDefault() {
            if (uniqueGroup != null) {
                return uniqueGroup;
            }
            return tags.contains(ALWAYS_KEEP_TAG) ? UniqueGroup.CHASE : UniqueGroup.COMMON;
        }
    }

    /**
     * 遺時星晶（半徑珠寶）的四種：照基底的標籤分（bases/jewels.yml：radius ＋ r_ruby／r_emerald／r_sapphire，三種都有的是白的）。
     * 2026-11-03 起只有舊存檔轉換用（th= 記的是這個的代號）。
     */
    public enum TimeLost {
        RUBY("ruby", "遺時赤星晶"), EMERALD("emerald", "遺時翠星晶"), SAPPHIRE("sapphire", "遺時蒼星晶"), DIAMOND("diamond", "遺時白星晶");

        private final String id;
        private final String displayName;

        TimeLost(final String id, final String displayName) {
            this.id = id;
            this.displayName = displayName;
        }

        public String id() {
            return id;
        }

        public String displayName() {
            return displayName;
        }

        /** 基底的標籤是哪一種遺時星晶；不是遺時星晶（沒有 radius 標籤）回傳 null。 */
        public static TimeLost of(final Set<String> tags) {
            if (tags == null || !tags.contains("radius")) {
                return null;
            }
            final boolean ruby = tags.contains("r_ruby");
            final boolean emerald = tags.contains("r_emerald");
            final boolean sapphire = tags.contains("r_sapphire");
            if (ruby && emerald && sapphire) {
                return DIAMOND;
            }
            return emerald ? EMERALD : sapphire ? SAPPHIRE : RUBY;
        }

        static TimeLost byId(final String id) {
            for (final TimeLost each : values()) {
                if (each.id.equalsIgnoreCase(id)) {
                    return each;
                }
            }
            return null;
        }
    }

    /**
     * 看掉落的那個角色（進階規則用）。
     *
     * @param level          角色等級
     * @param weaponTags     手上武器的基底標籤（沒拿是空的）
     * @param armourFamilies 身上防具的系（str_armour…；沒穿是空的）
     */
    public record Viewer(int level, Set<String> weaponTags, Set<String> armourFamilies) {
        public static final Viewer NONE = new Viewer(0, Set.of(), Set.of());

        public Viewer {
            weaponTags = weaponTags == null ? Set.of() : Set.copyOf(weaponTags);
            armourFamilies = armourFamilies == null ? Set.of() : Set.copyOf(armourFamilies);
        }
    }

    /**
     * 舊存檔轉換要用的一種基底（{@link #migrate}）。
     *
     * @param id        基底代號
     * @param tags      基底標籤
     * @param heistOnly 只在劫盜掉的特殊基底
     */
    public record BaseInfo(String id, Set<String> tags, boolean heistOnly) {
        public BaseInfo {
            tags = tags == null ? Set.of() : Set.copyOf(tags);
        }
    }

    /**
     * 舊存檔（2026-11-03 之前的格式）裡還沒換算的設定；讀進來後由撿取模組拿基底清單呼叫 {@link #migrate}。
     *
     * @param keepBases      只撿的基底（白名單）
     * @param jewelRarity    珠寶依稀有度（0 全部撿、2 只撿稀有、3 全部不撿）
     * @param timeLostRarity 遺時星晶依稀有度（0、1 只撿稀有、2 全部不撿）
     * @param hiddenTimeLost 不撿的遺時星晶種類
     * @param heistRule      劫盜特殊基底（always／normal／hide；null = 沒寫 = always）
     * @param hideUniques    傳奇那一格勾成隱藏
     */
    record Legacy(Set<String> keepBases, int jewelRarity, int timeLostRarity, Set<TimeLost> hiddenTimeLost, String heistRule, boolean hideUniques) {
        static final Legacy EMPTY = new Legacy(Set.of(), 0, 0, Set.of(), null, false);
    }

    private final EnumSet<Rarity> hiddenEquipment = EnumSet.noneOf(Rarity.class);
    private final TreeSet<String> hiddenCurrencies = new TreeSet<>();
    private boolean hideSkillGems;
    /** 2026-10：護符全部隱藏（換成金幣）。 */
    private boolean hideCharms;
    /** 2026-10：「保留好詞綴」也只撿跟我同類的（武器種類、防具系別）。 */
    private boolean keepAffixLikeMine;
    private boolean hideSupportGems;
    private FlaskRule flasks = FlaskRule.ALL;
    private Rules rules = Rules.NONE;
    private String preset = CUSTOM;
    /** 2026-11-03：每一種基底的規則（只存隱藏與必撿；沒有的是顯示）。 */
    private final TreeMap<String, BaseRule> bases = new TreeMap<>();
    /** 2026-11-03：不撿的傳奇分組（追逐級不會在裡面）。 */
    private final EnumSet<UniqueGroup> hiddenUniques = EnumSet.noneOf(UniqueGroup.class);
    /**
     * 2026-11-04 照站長「傳奇要能手動選擇每種傳奇那些要隱藏」：每一種傳奇的規則（只存隱藏與必撿；沒有的照分組）。
     * 隱藏＝這一種不撿（分組會撿也不撿）、必撿＝這一種一定撿（分組隱藏也撿）。追逐級永遠撿，不能隱藏。
     */
    private final TreeMap<String, UniqueRule> uniques = new TreeMap<>();
    /** 稀有掉落的音效（2026-10）：傳奇、追逐級、映星鏡、Boss 入場材料、大鑿星石、Lv.21 寶石、終局以上 Boss 的掉落。預設開。 */
    private boolean dropSounds = true;
    /** 舊存檔還沒換算的部分（null = 不用換算）。不存檔、不算進 equals。 */
    private Legacy legacy;

    /** 全部顯示的過濾器（自訂）。 */
    public LootFilter() {
    }

    /** 照一組預設組合。 */
    public static LootFilter of(final LootConfig.Preset preset) {
        final LootFilter filter = new LootFilter();
        for (final Rarity rarity : preset.hideEquipment()) {
            if (rarity != Rarity.UNIQUE) {
                filter.hiddenEquipment.add(rarity);
            }
        }
        filter.hiddenCurrencies.addAll(preset.hideCurrencies());
        filter.hideSkillGems = preset.hideSkillGems();
        filter.hideSupportGems = preset.hideSupportGems();
        filter.flasks = preset.flasks();
        filter.rules = preset.rules() == null ? Rules.NONE : preset.rules();
        filter.preset = preset.id();
        return filter;
    }

    // ---- 查詢 ----

    /** 這個稀有度的普通、魔法、稀有裝備要不要隱藏（傳奇照 {@link #hidesUniques}）。 */
    public boolean hidesEquipment(final Rarity rarity) {
        return hiddenEquipment.contains(rarity);
    }

    public boolean hidesCurrency(final String id) {
        return hiddenCurrencies.contains(id);
    }

    public boolean hidesSkillGems() {
        return hideSkillGems;
    }

    public boolean hidesSupportGems() {
        return hideSupportGems;
    }

    public FlaskRule flasks() {
        return flasks;
    }

    /** 預設組合的代號；自己改過是 {@link #CUSTOM}。 */
    public String preset() {
        return preset;
    }

    public Set<Rarity> hiddenEquipment() {
        return Collections.unmodifiableSet(hiddenEquipment);
    }

    public Set<String> hiddenCurrencies() {
        return Collections.unmodifiableSet(hiddenCurrencies);
    }

    public Rules rules() {
        return rules;
    }

    public boolean dropSounds() {
        return dropSounds;
    }

    public boolean hidesCharms() {
        return hideCharms;
    }

    public boolean keepAffixLikeMine() {
        return keepAffixLikeMine;
    }

    /** 舊存檔還沒換算（撿取模組要拿基底清單呼叫 {@link #migrate}）。 */
    public boolean needsMigration() {
        return legacy != null;
    }

    // ---- 基底（2026-11-03）----

    /** 這種基底的規則（沒設定的是顯示）。 */
    public BaseRule baseRule(final String baseId) {
        return baseId == null ? BaseRule.SHOW : bases.getOrDefault(baseId, DEFAULT_ALWAYS_BASES.contains(baseId) ? BaseRule.ALWAYS : BaseRule.SHOW);
    }

    /**
     * 2026-11-06 照站長「空無墜飾預設必撿」：沒設定時就是必撿的基底（每個人、每個角色都是，已經有存檔的也一樣）。
     * 玩家改成顯示的話另外記（存檔 sb=），改回必撿就拿掉；這次之前只有劫盜特殊基底在換新格式時補上必撿。
     */
    public static final Set<String> DEFAULT_ALWAYS_BASES = Set.of("jamulet_absent");

    /** 設定一種基底（顯示 = 拿掉設定）。跟護符一樣另外記：不會讓預設組合變成自訂，換預設組合也留著。 */
    public void setBaseRule(final String baseId, final BaseRule rule) {
        final boolean defaultAlways = DEFAULT_ALWAYS_BASES.contains(baseId); // 2026-11-06：預設必撿的，顯示要另外記、必撿是預設
        if (rule == null || rule == BaseRule.SHOW) {
            if (defaultAlways) {
                bases.put(baseId, BaseRule.SHOW);
            } else {
                bases.remove(baseId);
            }
        } else if (rule == BaseRule.ALWAYS && defaultAlways) {
            bases.remove(baseId);
        } else {
            bases.put(baseId, rule);
        }
    }

    /** 顯示 ↔ 隱藏（必撿的點了變隱藏）。 */
    public void toggleBaseHidden(final String baseId) {
        setBaseRule(baseId, baseRule(baseId) == BaseRule.HIDE ? BaseRule.SHOW : BaseRule.HIDE);
    }

    /** 顯示 ↔ 必撿（隱藏的點了變必撿）。 */
    public void toggleBaseAlways(final String baseId) {
        setBaseRule(baseId, baseRule(baseId) == BaseRule.ALWAYS ? BaseRule.SHOW : BaseRule.ALWAYS);
    }

    /** 隱藏的基底。 */
    public Set<String> hiddenBases() {
        return basesWith(BaseRule.HIDE);
    }

    /** 必撿的基底。 */
    public Set<String> alwaysBases() {
        final Set<String> out = basesWith(BaseRule.ALWAYS);
        for (final String id : DEFAULT_ALWAYS_BASES) {
            if (!bases.containsKey(id)) {
                out.add(id); // 2026-11-06：預設必撿（沒改過）的也算
            }
        }
        return out;
    }

    private Set<String> basesWith(final BaseRule rule) {
        final TreeSet<String> out = new TreeSet<>();
        for (final Map.Entry<String, BaseRule> entry : bases.entrySet()) {
            if (entry.getValue() == rule) {
                out.add(entry.getKey());
            }
        }
        return out;
    }

    /** 有設定的基底（代號 → 規則）。 */
    public Map<String, BaseRule> baseRules() {
        return Collections.unmodifiableMap(bases);
    }

    public void clearBaseRules() {
        bases.clear();
    }

    /** 換預設組合時把基底的設定帶過去。 */
    public LootFilter withBaseRules(final Map<String, BaseRule> rules) {
        bases.clear();
        for (final Map.Entry<String, BaseRule> entry : rules.entrySet()) {
            setBaseRule(entry.getKey(), entry.getValue());
        }
        return this;
    }

    // ---- 傳奇分組（2026-11-03）----

    /** 這一組傳奇撿不撿（追逐級永遠撿）。 */
    public boolean hidesUniques(final UniqueGroup group) {
        return group != UniqueGroup.CHASE && hiddenUniques.contains(group);
    }

    public Set<UniqueGroup> hiddenUniques() {
        return Collections.unmodifiableSet(hiddenUniques);
    }

    /** 2026-11-04：一種傳奇自己的規則：照分組、隱藏、必撿。 */
    public enum UniqueRule { FOLLOW, HIDE, ALWAYS }

    /** 這一種傳奇的規則（沒設定或不知道是哪一件：照分組）。 */
    public UniqueRule uniqueRule(final String uniqueId) {
        return uniqueId == null ? UniqueRule.FOLLOW : uniques.getOrDefault(uniqueId, UniqueRule.FOLLOW);
    }

    public void setUniqueRule(final String uniqueId, final UniqueRule rule) {
        if (uniqueId == null || uniqueId.isBlank()) {
            return;
        }
        if (rule == null || rule == UniqueRule.FOLLOW) {
            uniques.remove(uniqueId);
        } else {
            uniques.put(uniqueId, rule);
        }
    }

    /** 照分組 ↔ 隱藏（必撿的點了變隱藏）。 */
    public void toggleUniqueHidden(final String uniqueId) {
        setUniqueRule(uniqueId, uniqueRule(uniqueId) == UniqueRule.HIDE ? UniqueRule.FOLLOW : UniqueRule.HIDE);
    }

    /** 照分組 ↔ 必撿（隱藏的點了變必撿）。 */
    public void toggleUniqueAlways(final String uniqueId) {
        setUniqueRule(uniqueId, uniqueRule(uniqueId) == UniqueRule.ALWAYS ? UniqueRule.FOLLOW : UniqueRule.ALWAYS);
    }

    /** 有設定的傳奇（代號 → 規則）。 */
    public Map<String, UniqueRule> uniqueRules() {
        return Collections.unmodifiableMap(uniques);
    }

    public void clearUniqueRules() {
        uniques.clear();
    }

    private Set<String> uniquesWith(final UniqueRule rule) {
        final TreeSet<String> out = new TreeSet<>();
        for (final Map.Entry<String, UniqueRule> entry : uniques.entrySet()) {
            if (entry.getValue() == rule) {
                out.add(entry.getKey());
            }
        }
        return out;
    }

    /** 跟護符一樣另外記：不會讓預設組合變成自訂，換預設組合也留著。追逐級點了沒反應。 */
    public void toggleUniques(final UniqueGroup group) {
        if (group == UniqueGroup.CHASE) {
            return;
        }
        if (!hiddenUniques.remove(group)) {
            hiddenUniques.add(group);
        }
    }

    public LootFilter withDropSounds(final boolean on) {
        dropSounds = on;
        return this;
    }

    public void toggleDropSounds() {
        dropSounds = !dropSounds;
    }

    /**
     * 一件掉落要不要隱藏。
     *
     * @param kind         掉落種類
     * @param rarity       裝備的稀有度（其他種類不看）
     * @param currencyId   通貨代號（其他種類不看）
     * @param flaskUpgrade 藥劑比藥劑欄裡同一種的好（其他種類不看）
     */
    public boolean hides(final DropTable.Kind kind, final Rarity rarity, final String currencyId, final boolean flaskUpgrade) {
        return hides(Candidate.simple(kind, rarity, currencyId, flaskUpgrade), Viewer.NONE);
    }

    /**
     * 一件掉落要不要隱藏（含進階規則）。裝備的順序見類別說明。
     *
     * @param viewer 看掉落的角色（進階規則用；不知道就 {@link Viewer#NONE}）
     */
    public boolean hides(final Candidate candidate, final Viewer viewer) {
        final Viewer who = viewer == null ? Viewer.NONE : viewer;
        return switch (candidate.kind()) {
            case EQUIPMENT -> hidesEquipment(candidate, who);
            case CURRENCY -> candidate.currencyId() != null && hidesCurrency(candidate.currencyId());
            // 劫盜的異質技能寶石（稀有度當成傳奇）永遠顯示
            case SKILL_GEM -> candidate.rarity() != Rarity.UNIQUE && (hideSkillGems || (rules.minSkillGemLevel() > 0 && candidate.gemLevel() > 0 && candidate.gemLevel() < rules.minSkillGemLevel()));
            // 專屬的輔助寶石（終局、極境；稀有度當成傳奇）永遠顯示
            case SUPPORT_GEM -> candidate.rarity() != Rarity.UNIQUE
                    && (hideSupportGems || (rules.minSupportTier() > 0 && candidate.gemLevel() > 0 && candidate.gemLevel() < rules.minSupportTier()));
            case FLASK -> switch (flasks) {
                case ALL -> false;
                case BETTER -> !candidate.flaskUpgrade();
                case NONE -> true;
            };
        };
    }

    private boolean hidesEquipment(final Candidate candidate, final Viewer who) {
        // 2026-11-03 照站長：順序固定——傳奇照分組 → 基底隱藏絕對不撿、必撿看好詞綴門檻 → 地圖只看稀有度 → 等級差、不同類 → 稀有度（好詞綴救回）
        if (candidate.rarity() == Rarity.UNIQUE) {
            // 2026-11-04：先看這一種傳奇自己的規則（隱藏／必撿），沒設定的照分組；追逐級永遠撿
            final UniqueGroup group = candidate.groupOrDefault();
            final UniqueRule own = group == UniqueGroup.CHASE ? UniqueRule.FOLLOW : uniqueRule(candidate.uniqueId());
            return own == UniqueRule.FOLLOW ? hidesUniques(group) : own == UniqueRule.HIDE;
        }
        final BaseRule base = baseRule(candidate.baseId());
        if (base == BaseRule.HIDE) {
            return true; // 有 T1 詞綴也不撿
        }
        if (base == BaseRule.ALWAYS) {
            // 必撿：門檻開著就要有 T 門檻以上的詞綴（未鑑定的也算；不看稀有度、等級差、同類同系、好詞綴也只撿同類）；門檻關、地圖全部撿
            return rules.keepAffixTier() > 0 && !candidate.map() && !keptByAffixTier(candidate);
        }
        if (candidate.map()) {
            return hidesMap(candidate.rarity());
        }
        if (!candidate.jewel()) { // 珠寶沒有等級差、同類同系的問題
            if (unlikeMine(candidate.tags(), who, rules.weaponsLikeMine(), rules.armourLikeMine())
                    || rules.levelGap() > 0 && candidate.itemLevel() > 0 && who.level() > 0 && candidate.itemLevel() < who.level() - rules.levelGap()) {
                return true;
            }
        }
        final boolean hidden = candidate.rarity() != null && hiddenEquipment.contains(candidate.rarity());
        return hidden && !keptByAffixTier(candidate, who);
    }

    /** 有夠好的詞綴（T1～T「保留詞綴階級」）：被稀有度藏起來的照樣撿（基底隱藏、等級差、不同類先判斷，救不回來）。 */
    public boolean keptByAffixTier(final Candidate candidate) {
        return rules.keepAffixTier() > 0 && candidate.bestAffixRank() > 0 && candidate.bestAffixRank() <= rules.keepAffixTier();
    }

    /** 同上；開了「好詞綴也只撿同類」時，跟我不同類（武器種類、防具系別）的不保留。 */
    public boolean keptByAffixTier(final Candidate candidate, final Viewer who) {
        return keptByAffixTier(candidate) && !(keepAffixLikeMine && unlikeMine(candidate.tags(), who, true, true));
    }

    /** 這個稀有度的地圖要不要隱藏（「地圖依稀有度隱藏」；傳奇地圖照分組、不知道稀有度的永遠顯示）。 */
    public boolean hidesMap(final Rarity rarity) {
        if (rarity == null || rarity == Rarity.UNIQUE || rules.mapRarity() <= 0) {
            return false;
        }
        return switch (rarity) {
            case NORMAL -> true;
            case MAGIC -> rules.mapRarity() >= 2;
            case RARE -> rules.mapRarity() >= 3; // 2026-10-24：全部隱藏
            default -> false;
        };
    }

    /** 武器是單手還是雙手（基底標籤 one_hand／two_hand；都沒寫回傳 null）。 */
    private static String handedness(final Set<String> tags) {
        return tags.contains("two_hand") ? "two_hand" : tags.contains("one_hand") ? "one_hand" : null;
    }

    /**
     * 這件裝備（基底標籤）是不是「跟我不同類」：手上拿弓就只有弓是同類（雙手武器的人盾也不同類；沒拿弓的人箭袋也不同類）、
     * 身上穿力量系防具就只有力量系的防具與盾是同類。沒拿武器／沒穿防具時不管（都算同類）。
     * 掉落過濾器的「只撿跟我同類的」與商人賭博的「只看我這類」共用。
     *
     * @param checkWeapons 要不要看武器（與盾對雙手武器）
     * @param checkArmour  要不要看防具與盾的系別
     */
    public static boolean unlikeMine(final Set<String> tags, final Viewer who, final boolean checkWeapons, final boolean checkArmour) {
        if (checkWeapons && !who.weaponTags().isEmpty()) {
            if (tags.contains("weapon")) {
                for (final String tag : tags) {
                    if (WEAPON_CLASSES.contains(tag) && !who.weaponTags().contains(tag)) {
                        return true; // 跟手上的不同類
                    }
                }
                final String dropClass = primaryWeaponClass(tags);
                final String myClass = primaryWeaponClass(who.weaponTags());
                if (dropClass != null && myClass != null && !dropClass.equals(myClass)) {
                    return true; // 2026-10：權杖也有錘的標籤，以前拿權杖照樣撿單手錘（拿錘的不撿權杖）；主要種類要一樣
                }
                if (handedness(tags) != null && handedness(who.weaponTags()) != null && !handedness(tags).equals(handedness(who.weaponTags()))) {
                    return true; // 2026-10：同種類但單手／雙手不同（拿雙手錘不撿單手錘），跟選單說明一致
                }
            } else if (tags.contains("shield") && who.weaponTags().contains("two_hand")) {
                return true; // 雙手武器用不到盾
            } else if (tags.contains("quiver") && !who.weaponTags().contains("bow")) {
                return true; // 2026-10：箭袋只有拿弓的人能裝（以前箭袋不算武器也不算盾，拿什麼都照撿）
            }
        }
        if (checkArmour && !who.armourFamilies().isEmpty() && (tags.contains("armour") || tags.contains("shield"))) {
            boolean family = false;
            boolean match = false;
            for (final String tag : tags) {
                if (ARMOUR_FAMILIES.contains(tag)) {
                    family = true;
                    match |= who.armourFamilies().contains(tag);
                }
            }
            return family && !match;
        }
        return false;
    }

    /** 一把武器的主要種類（權杖同時有錘和權杖的標籤：取權杖；固定順序，不會每次重開不一樣）；沒有回傳 null。 */
    public static String primaryWeaponClass(final Set<String> tags) {
        for (final String tag : List.of("sceptre", "talisman", "quarterstaff", "crossbow", "spear", "staff", "wand", "bow", "mace",
                "sword", "axe", "dagger", "claw")) {
            if (tags.contains(tag)) {
                return tag;
            }
        }
        return null;
    }

    /** 武器種類標籤的中文（劍、斧、錘…）；不認得的照標籤。 */
    public static String weaponWord(final String tag) {
        return switch (tag) {
            case "sword" -> "劍";
            case "axe" -> "斧";
            case "mace" -> "錘";
            case "dagger" -> "匕首";
            case "claw" -> "爪";
            case "wand" -> "法杖";
            case "staff" -> "長杖";
            case "bow" -> "弓";
            case "sceptre" -> "權杖";
            case "crossbow" -> "弩";
            case "quarterstaff" -> "長棍";
            case "spear" -> "矛";
            case "talisman" -> "獸魂符";
            default -> tag;
        };
    }

    /** 防具系別標籤的中文（力量系、敏捷系、智慧系）。 */
    public static String familyWord(final String tag) {
        return switch (tag) {
            case "str_armour" -> "力量系";
            case "dex_armour" -> "敏捷系";
            case "int_armour" -> "智慧系";
            default -> tag;
        };
    }

    /**
     * 裝備的大類（基底清單的分頁）：weapon 武器、offhand 盾與箭袋、armour 防具、jewellery 飾品、jewel 珠寶、map 地圖；其他 null。
     */
    public static String gearCategory(final Set<String> tags) {
        if (tags == null) {
            return null;
        }
        if (tags.contains("map")) {
            return "map";
        }
        if (tags.contains("jewel")) {
            return "jewel";
        }
        if (tags.contains("weapon")) {
            return "weapon";
        }
        if (tags.contains("shield") || tags.contains("quiver")) {
            return "offhand";
        }
        if (tags.contains("armour")) {
            return "armour";
        }
        return tags.contains("jewellery") ? "jewellery" : null;
    }

    /** 這個規則有沒有任何一項開著（選單上顯示「進階規則：N 項」）。 */
    public int activeRuleCount() {
        int count = 0;
        count += rules.weaponsLikeMine() ? 1 : 0;
        count += rules.armourLikeMine() ? 1 : 0;
        count += rules.levelGap() > 0 ? 1 : 0;
        count += rules.minSkillGemLevel() > 0 ? 1 : 0;
        count += rules.minSupportTier() > 0 ? 1 : 0;
        count += rules.mapRarity() > 0 ? 1 : 0;
        count += rules.keepAffixTier() > 0 ? 1 : 0;
        return count;
    }

    // ---- 修改（選單上點一下，變成自訂）----

    /** 普通、魔法、稀有：撿或不撿（傳奇請用 {@link #toggleUniques}）。 */
    public void toggleEquipment(final Rarity rarity) {
        if (rarity == Rarity.UNIQUE) {
            return;
        }
        if (!hiddenEquipment.remove(rarity)) {
            hiddenEquipment.add(rarity);
        }
        preset = CUSTOM;
    }

    public void toggleCurrency(final String id) {
        if (!hiddenCurrencies.remove(id)) {
            hiddenCurrencies.add(id);
        }
        preset = CUSTOM;
    }

    /** 跟珠寶、遺時星晶一樣另外記：不會讓預設組合變成自訂，換預設組合也留著。 */
    public void toggleKeepAffixLikeMine() {
        keepAffixLikeMine = !keepAffixLikeMine;
    }

    /** 跟珠寶、遺時星晶一樣另外記：不會讓預設組合變成自訂，換預設組合也留著。 */
    public void toggleCharms() {
        hideCharms = !hideCharms;
    }

    /** 換預設組合時把另外記的帶過去：護符、好詞綴也只撿同類、基底、傳奇分組（音效另外 {@link #withDropSounds}）。 */
    public LootFilter withExtras(final LootFilter from) {
        hideCharms = from.hideCharms;
        keepAffixLikeMine = from.keepAffixLikeMine;
        withBaseRules(from.bases);
        hiddenUniques.clear();
        hiddenUniques.addAll(from.hiddenUniques);
        uniques.clear();
        uniques.putAll(from.uniques); // 2026-11-04：每種傳奇的規則也帶過去
        legacy = from.legacy;
        return this;
    }

    public void toggleSkillGems() {
        hideSkillGems = !hideSkillGems;
        preset = CUSTOM;
    }

    public void toggleSupportGems() {
        hideSupportGems = !hideSupportGems;
        preset = CUSTOM;
    }

    public void cycleFlasks() {
        flasks = flasks.next();
        preset = CUSTOM;
    }

    public void toggleWeaponsLikeMine() {
        rules = rules.withWeaponsLikeMine(!rules.weaponsLikeMine());
        preset = CUSTOM;
    }

    public void toggleArmourLikeMine() {
        rules = rules.withArmourLikeMine(!rules.armourLikeMine());
        preset = CUSTOM;
    }

    /** 等級差：關 → 10 → 20 → 30 → 關（反方向就倒著走）。 */
    public void cycleLevelGap(final boolean backwards) {
        rules = rules.withLevelGap(next(LEVEL_GAPS, rules.levelGap(), backwards));
        preset = CUSTOM;
    }

    public void cycleSkillGemLevel(final boolean backwards) {
        rules = rules.withMinSkillGemLevel(next(GEM_LEVELS, rules.minSkillGemLevel(), backwards));
        preset = CUSTOM;
    }

    public void cycleSupportTier(final boolean backwards) {
        rules = rules.withMinSupportTier(next(SUPPORT_TIERS, rules.minSupportTier(), backwards));
        preset = CUSTOM;
    }

    /** 地圖依稀有度：全部顯示 → 隱藏普通 → 只撿稀有 → 全部隱藏 → 全部顯示（反方向就倒著走）。 */
    public void cycleMapRarity(final boolean backwards) {
        rules = rules.withMapRarity(next(MAP_RARITIES, rules.mapRarity(), backwards));
        preset = CUSTOM;
    }

    /** 保留詞綴階級：關 → T1 → T2 → … → T5 → 關（反方向就倒著走）。 */
    public void cycleKeepAffixTier(final boolean backwards) {
        rules = rules.withKeepAffixTier(next(AFFIX_TIERS, rules.keepAffixTier(), backwards));
        preset = CUSTOM;
    }

    /** 全部顯示（清掉所有隱藏與規則；必撿的基底也清掉）。 */
    public void showEverything() {
        hiddenEquipment.clear();
        hiddenCurrencies.clear();
        hideSkillGems = false;
        hideCharms = false;
        keepAffixLikeMine = false;
        hideSupportGems = false;
        flasks = FlaskRule.ALL;
        rules = Rules.NONE;
        preset = CUSTOM;
        bases.clear();
        hiddenUniques.clear();
        uniques.clear();
        legacy = null;
    }

    /** 一次設定好幾種通貨（選單上「這一級全部隱藏／顯示」）。 */
    public void setCurrenciesHidden(final Collection<String> ids, final boolean hidden) {
        if (hidden) {
            hiddenCurrencies.addAll(ids);
        } else {
            hiddenCurrencies.removeAll(ids);
        }
        preset = CUSTOM;
    }

    /** 選項表裡目前值的下一個（不在表裡就從頭）。 */
    static int next(final int[] options, final int current, final boolean backwards) {
        int index = -1;
        for (int i = 0; i < options.length; i++) {
            if (options[i] == current) {
                index = i;
            }
        }
        if (index < 0) {
            return options[backwards ? options.length - 1 : 0]; // loot.yml 寫了表裡沒有的值：往前從頭、往後從尾
        }
        final int step = backwards ? -1 : 1;
        return options[((index + step) % options.length + options.length) % options.length];
    }

    // ---- 舊存檔轉換（2026-11-03）----

    /**
     * 把舊格式的設定換算成基底規則與傳奇分組（撿取模組讀過濾器時給全部的基底）。換算完 {@link #needsMigration} 變 false，
     * 下次存檔就是新格式。沒有舊設定的也要叫（劫盜特殊基底預設必撿要在這裡補上）。
     * <ul>
     *   <li>只撿的基底（白名單）：有挑的那幾類裡，不在清單的基底全部隱藏；清單內的顯示</li>
     *   <li>珠寶全部不撿 → 四種一般珠寶隱藏（「只撿稀有珠寶」沒有對應的，改成顯示、照稀有度）</li>
     *   <li>遺時星晶全部不撿 → 四種遺時星晶隱藏；不撿的那幾種 → 那幾種隱藏（「只撿稀有的」沒有對應的）</li>
     *   <li>劫盜特殊基底：一定撿（沒寫也是）→ 必撿；不撿 → 隱藏；照一般規則 → 顯示</li>
     *   <li>傳奇勾成隱藏 → 一般、事件、劫盜傳奇不撿（巔峰、終局・極境以前就是永遠撿的，留著）</li>
     * </ul>
     */
    public void migrate(final Collection<BaseInfo> all) {
        if (legacy == null) {
            return;
        }
        final Legacy old = legacy;
        legacy = null;
        final Set<String> coveredCategories = new TreeSet<>();
        final Map<String, BaseInfo> byId = new TreeMap<>();
        for (final BaseInfo base : all) {
            byId.put(base.id(), base);
        }
        for (final String kept : old.keepBases()) {
            final BaseInfo base = byId.get(kept);
            final String category = base == null ? null : gearCategory(base.tags());
            if (category != null) {
                coveredCategories.add(category);
            }
        }
        for (final BaseInfo base : all) {
            if (bases.containsKey(base.id())) {
                continue; // 新格式已經有的不動
            }
            final Set<String> tags = base.tags();
            if (base.heistOnly()) {
                final String rule = old.heistRule() == null ? "always" : old.heistRule().toLowerCase(Locale.ROOT);
                if ("hide".equals(rule)) {
                    bases.put(base.id(), BaseRule.HIDE);
                } else if (!"normal".equals(rule)) {
                    bases.put(base.id(), BaseRule.ALWAYS);
                }
                continue;
            }
            if (tags.contains("jewel")) {
                final TimeLost timeLost = TimeLost.of(tags);
                if (timeLost != null) {
                    if (old.timeLostRarity() >= 2 || old.hiddenTimeLost().contains(timeLost)) {
                        bases.put(base.id(), BaseRule.HIDE);
                    }
                } else if (old.jewelRarity() >= 3) {
                    bases.put(base.id(), BaseRule.HIDE);
                }
                continue;
            }
            final String category = gearCategory(tags);
            if (category != null && coveredCategories.contains(category) && !old.keepBases().contains(base.id())) {
                bases.put(base.id(), BaseRule.HIDE);
            }
        }
        if (old.hideUniques()) {
            hiddenUniques.add(UniqueGroup.COMMON);
            hiddenUniques.add(UniqueGroup.EVENT);
            hiddenUniques.add(UniqueGroup.HEIST);
        }
    }

    // ---- 存檔 ----

    /**
     * 存檔用的短字串（v=2）。預設組合只記代號加另外記的那幾項（{@code p=standard;v=2;xb=…}），自訂記每一項：
     * {@code p=custom;v=2;eq=normal,magic;cur=armour_scrap,whetstone;sg=0;ug=1;fl=better;…;xb=隱藏的基底;ab=必撿的基底;hu=不撿的傳奇組}。
     * 舊鍵（kb、jr、tr、th、hb、hu=1）讀得到、寫不出來。
     */
    public String encode() {
        final List<String> hiddenGroups = new ArrayList<>();
        for (final UniqueGroup group : hiddenUniques) {
            hiddenGroups.add(group.id());
        }
        final Set<String> hidden = hiddenBases();
        final Set<String> always = basesWith(BaseRule.ALWAYS); // 2026-11-06：只存設定過的（預設必撿的不用存）
        final Set<String> shown = basesWith(BaseRule.SHOW);    // 2026-11-06：預設必撿、玩家改成顯示的
        final String extras = (hidden.isEmpty() ? "" : ";xb=" + String.join(",", hidden)) + (always.isEmpty() ? "" : ";ab=" + String.join(",", always))
                + (shown.isEmpty() ? "" : ";sb=" + String.join(",", shown))
                + (hiddenGroups.isEmpty() ? "" : ";hu=" + String.join(",", hiddenGroups))
                + (uniquesWith(UniqueRule.HIDE).isEmpty() ? "" : ";xu=" + String.join(",", uniquesWith(UniqueRule.HIDE))) // 2026-11-04
                + (uniquesWith(UniqueRule.ALWAYS).isEmpty() ? "" : ";au=" + String.join(",", uniquesWith(UniqueRule.ALWAYS)))
                + (dropSounds ? "" : ";ds=0") + (hideCharms ? ";ch=1" : "") + (keepAffixLikeMine ? ";kl=1" : "");
        if (!CUSTOM.equals(preset)) {
            return "p=" + preset + ";v=" + VERSION + extras;
        }
        final List<String> rarities = new ArrayList<>();
        for (final Rarity rarity : hiddenEquipment) {
            rarities.add(rarity.name().toLowerCase(Locale.ROOT));
        }
        return "p=" + CUSTOM + ";v=" + VERSION
                + ";eq=" + String.join(",", rarities)
                + ";cur=" + String.join(",", hiddenCurrencies)
                + ";sg=" + (hideSkillGems ? 1 : 0)
                + ";ug=" + (hideSupportGems ? 1 : 0)
                + ";fl=" + flasks.name().toLowerCase(Locale.ROOT)
                + (rules.equals(Rules.NONE) ? "" : ";wl=" + (rules.weaponsLikeMine() ? 1 : 0) + ";al=" + (rules.armourLikeMine() ? 1 : 0)
                + ";lg=" + rules.levelGap() + ";gl=" + rules.minSkillGemLevel() + ";gt=" + rules.minSupportTier() + ";mr=" + rules.mapRarity()
                + (rules.keepAffixTier() > 0 ? ";kt=" + rules.keepAffixTier() : ""))
                + extras;
    }

    /**
     * 讀存檔。沒存過（null）或讀不懂的用 loot.yml 的預設組合；
     * 記的是預設組合代號就照目前的設定（loot.yml 改了會跟著變），組合被刪掉了也改用預設。
     * 舊格式（沒有 v=2）讀進來會帶著 {@link #needsMigration}，撿取模組要叫 {@link #migrate}。
     */
    public static LootFilter decode(final String text, final LootConfig config) {
        if (text == null || text.isBlank()) {
            final LootFilter fresh = config.defaultFilter().copy();
            fresh.legacy = Legacy.EMPTY; // 新角色：劫盜特殊基底預設必撿要補上
            return fresh;
        }
        String presetId = null;
        int version = 0;
        final LootFilter filter = new LootFilter();
        final Set<String> keepBases = new TreeSet<>();
        int jewelRarity = 0;
        int timeLostRarity = 0;
        final EnumSet<TimeLost> hiddenTimeLost = EnumSet.noneOf(TimeLost.class);
        String heistRule = null;
        boolean hideUniques = false;
        for (final String part : text.split(";")) {
            final int equals = part.indexOf('=');
            if (equals <= 0) {
                continue;
            }
            final String key = part.substring(0, equals).trim();
            final String value = part.substring(equals + 1).trim();
            switch (key) {
                case "p" -> presetId = value;
                case "v" -> version = integer(value);
                case "eq" -> {
                    for (final String name : list(value)) {
                        for (final Rarity rarity : Rarity.values()) {
                            if (rarity.name().equalsIgnoreCase(name)) {
                                if (rarity == Rarity.UNIQUE) {
                                    hideUniques = true; // 舊格式：傳奇那一格
                                } else {
                                    filter.hiddenEquipment.add(rarity);
                                }
                            }
                        }
                    }
                }
                case "cur" -> filter.hiddenCurrencies.addAll(list(value));
                case "sg" -> filter.hideSkillGems = "1".equals(value);
                case "hu" -> {
                    if ("1".equals(value)) {
                        hideUniques = true; // 舊格式：預設組合＋傳奇隱藏
                    } else {
                        for (final String name : list(value)) {
                            final UniqueGroup group = UniqueGroup.byId(name);
                            if (group != null && group != UniqueGroup.CHASE) {
                                filter.hiddenUniques.add(group);
                            }
                        }
                    }
                }
                case "ch" -> filter.hideCharms = "1".equals(value);
                case "xu" -> list(value).forEach(id -> filter.setUniqueRule(id, UniqueRule.HIDE)); // 2026-11-04：每種傳奇隱藏
                case "au" -> list(value).forEach(id -> filter.setUniqueRule(id, UniqueRule.ALWAYS)); // 2026-11-04：每種傳奇必撿
                case "kl" -> filter.keepAffixLikeMine = "1".equals(value);
                case "ug" -> filter.hideSupportGems = "1".equals(value);
                case "fl" -> filter.flasks = FlaskRule.parse(value, FlaskRule.ALL);
                case "wl" -> filter.rules = filter.rules.withWeaponsLikeMine("1".equals(value));
                case "al" -> filter.rules = filter.rules.withArmourLikeMine("1".equals(value));
                case "lg" -> filter.rules = filter.rules.withLevelGap(integer(value));
                case "gl" -> filter.rules = filter.rules.withMinSkillGemLevel(integer(value));
                case "gt" -> filter.rules = filter.rules.withMinSupportTier(integer(value));
                case "mr" -> filter.rules = filter.rules.withMapRarity(integer(value));
                case "kt" -> filter.rules = filter.rules.withKeepAffixTier(integer(value));
                case "ds" -> filter.dropSounds = !"0".equals(value);
                case "xb" -> {
                    for (final String id : list(value)) {
                        filter.bases.put(id, BaseRule.HIDE);
                    }
                }
                case "ab" -> {
                    for (final String id : list(value)) {
                        filter.setBaseRule(id, BaseRule.ALWAYS);
                    }
                }
                case "sb" -> list(value).forEach(id -> filter.setBaseRule(id, BaseRule.SHOW)); // 2026-11-06：預設必撿改成顯示的
                // ---- 舊格式（2026-11-03 之前）----
                case "kb" -> keepBases.addAll(list(value));
                case "jr" -> jewelRarity = integer(value);
                case "tr" -> timeLostRarity = integer(value);
                case "th" -> {
                    for (final String name : list(value)) {
                        final TimeLost kind = TimeLost.byId(name);
                        if (kind != null) {
                            hiddenTimeLost.add(kind);
                        }
                    }
                }
                case "hb" -> heistRule = value;
                default -> {
                    // 之後版本加的欄位（kn 這種拿掉的也在這）：略過
                }
            }
        }
        if (version < VERSION) {
            filter.legacy = new Legacy(keepBases, jewelRarity, timeLostRarity, hiddenTimeLost, heistRule, hideUniques);
        }
        if (presetId == null) {
            return config.defaultFilter().copy().withDropSounds(filter.dropSounds).withExtras(filter);
        }
        if (!CUSTOM.equals(presetId)) {
            final LootConfig.Preset preset = config.preset(presetId);
            return (preset == null ? config.defaultFilter().copy() : of(preset)).withDropSounds(filter.dropSounds).withExtras(filter);
        }
        filter.preset = CUSTOM;
        return filter;
    }

    private static int integer(final String value) {
        try {
            return Integer.parseInt(value.trim());
        } catch (final NumberFormatException e) {
            return 0;
        }
    }

    private static List<String> list(final String value) {
        final List<String> list = new ArrayList<>();
        for (final String item : value.split(",")) {
            final String trimmed = item.trim();
            if (!trimmed.isEmpty()) {
                list.add(trimmed);
            }
        }
        return list;
    }

    public LootFilter copy() {
        final LootFilter copy = new LootFilter();
        copy.hiddenEquipment.addAll(hiddenEquipment);
        copy.hiddenCurrencies.addAll(hiddenCurrencies);
        copy.hideSkillGems = hideSkillGems;
        copy.hideCharms = hideCharms;
        copy.keepAffixLikeMine = keepAffixLikeMine;
        copy.hideSupportGems = hideSupportGems;
        copy.flasks = flasks;
        copy.rules = rules;
        copy.preset = preset;
        copy.bases.putAll(bases);
        copy.hiddenUniques.addAll(hiddenUniques);
        copy.uniques.putAll(uniques);
        copy.dropSounds = dropSounds;
        copy.legacy = legacy;
        return copy;
    }

    @Override
    public boolean equals(final Object other) {
        if (!(other instanceof LootFilter that)) {
            return false;
        }
        return hiddenEquipment.equals(that.hiddenEquipment) && hiddenCurrencies.equals(that.hiddenCurrencies)
                && hideSkillGems == that.hideSkillGems && hideSupportGems == that.hideSupportGems && hideCharms == that.hideCharms
                && keepAffixLikeMine == that.keepAffixLikeMine && flasks == that.flasks && rules.equals(that.rules) && preset.equals(that.preset)
                && bases.equals(that.bases) && hiddenUniques.equals(that.hiddenUniques) && uniques.equals(that.uniques) && dropSounds == that.dropSounds;
    }

    @Override
    public int hashCode() {
        return Objects.hash(hiddenEquipment, hiddenCurrencies, hideSkillGems, hideSupportGems, hideCharms, keepAffixLikeMine, flasks, rules, preset,
                bases, hiddenUniques, uniques, dropSounds);
    }

    @Override
    public String toString() {
        return encode();
    }
}
