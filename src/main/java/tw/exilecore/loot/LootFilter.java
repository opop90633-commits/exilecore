package tw.exilecore.loot;

import tw.exilecore.item.Rarity;
import tw.exilecore.monster.DropTable;

import java.util.ArrayList;
import java.util.Collections;
import java.util.EnumSet;
import java.util.List;
import java.util.Locale;
import java.util.Objects;
import java.util.Set;
import java.util.TreeSet;

/**
 * 一個角色的掉落過濾器（PoE 的 loot filter）：哪些東西不要掉（換成金幣）。
 * 稀有度、每種通貨、寶石、藥劑，加上進階規則 {@link Rules}（只撿跟手上同類的武器、同系的防具、等級差、寶石等級、地圖稀有度）。
 * 存在角色列（characters.loot_filter），用 {@link #encode()} 的短字串。純資料，不依賴 Bukkit。
 * <p>
 * 選了預設組合（寬鬆、標準、嚴格）的角色只記組合代號，讀出來時照 loot.yml 目前的設定；
 * 在選單上自己改過就變成「自訂」，記下每一項。
 */
public final class LootFilter {

    /** 在選單上自己改過的過濾器。 */
    public static final String CUSTOM = "custom";

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
    /** 「地圖依稀有度隱藏」的選項：0 = 全部顯示、1 = 隱藏普通（白）地圖、2 = 只撿稀有（黃）地圖（普通、魔法都隱藏）。 */
    public static final int[] MAP_RARITIES = {0, 1, 2};
    /** 「保留有 T 幾以上詞綴的裝備」的選項（0 = 關；1 = 有 T1 詞綴才保留，數字越大越寬）。 */
    public static final int[] AFFIX_TIERS = {0, 1, 2, 3, 4, 5};

    /** 地圖稀有度規則的名稱（選單與摘要用）。 */
    public static String mapRarityName(final int value) {
        return switch (value) {
            case 1 -> "隱藏普通（白）地圖";
            case 2 -> "只撿稀有（黃）地圖";
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
     * @param mapRarity        地圖依稀有度隱藏：0 = 關（地圖本來永遠顯示）、1 = 隱藏普通（白）地圖、2 = 普通與魔法（藍）都隱藏，只撿稀有（黃）
     * @param keepAffixTier    保留有 T 幾以上詞綴的裝備（2026-10）：身上有一條詞綴的階級是 T1～T這個數字，就算被上面的規則或稀有度藏起來
     *                         也照樣撿（0 = 關；未鑑定的也看得到，過濾器知道它的詞綴）
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
            return new Rules(weaponsLikeMine, armourLikeMine, levelGap, minSkillGemLevel, minSupportTier, Math.max(0, Math.min(2, value)), keepAffixTier);
        }

        Rules withKeepAffixTier(final int value) {
            return new Rules(weaponsLikeMine, armourLikeMine, levelGap, minSkillGemLevel, minSupportTier, mapRarity,
                    Math.max(0, Math.min(AFFIX_TIERS[AFFIX_TIERS.length - 1], value)));
        }
    }

    /**
     * 一件要判斷的掉落（純資料，撿取模組從掉落與做好的物品組出來）。
     *
     * @param kind         掉落種類
     * @param rarity       裝備的稀有度（其他種類 null）
     * @param currencyId   通貨代號（其他種類 null）
     * @param flaskUpgrade 藥劑比藥劑欄裡同一種的好
     * @param itemLevel    裝備的物品等級（不知道 0）
     * @param tags         裝備基底的標籤（weapon、sword、shield、armour、str_armour、map、jewel…）
     * @param mapTier      地圖的階級（不是地圖 0）
     * @param gemLevel     未切割技能寶石的等級／輔助寶石的階級（不知道 0）
     * @param bestAffixRank 裝備身上最好的詞綴是 T 幾（1 = T1；沒有詞綴或不知道 0）
     */
    public record Candidate(DropTable.Kind kind, Rarity rarity, String currencyId, boolean flaskUpgrade, int itemLevel,
                            Set<String> tags, int mapTier, int gemLevel, int bestAffixRank, String baseId) {
        public Candidate {
            tags = tags == null ? Set.of() : Set.copyOf(tags);
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

        /** 遺時星晶（半徑珠寶）是哪一種；不是遺時星晶回傳 null。 */
        TimeLost timeLost() {
            return TimeLost.of(tags);
        }
    }

    /**
     * 遺時星晶（半徑珠寶，2026-10）的四種：照基底的標籤分（bases/jewels.yml：radius ＋ r_ruby／r_emerald／r_sapphire，三種都有的是白的）。
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
    /** 只撿的基底（2026-10，基底代號，可以一直加）：有設定就只撿這些基底的裝備，稀有度照「依稀有度」的設定。 */
    private final TreeSet<String> keepBases = new TreeSet<>();
    /**
     * 珠寶依稀有度（2026-10）：0 全部撿、2 只撿稀有、3 全部不撿。
     * （1「隱藏普通」拿掉了：珠寶掉出來至少是魔法，那一格藏不到任何東西；舊存檔的 1 讀成 0）
     */
    private int jewelRarity;
    public static final int[] JEWEL_RARITIES = {0, 2, 3};
    /** 稀有掉落的音效（2026-10）：傳奇、追逐級、映星鏡、Boss 入場材料、大鑿星石、Lv.21 寶石、終局以上 Boss 的掉落。預設開。 */
    private boolean dropSounds = true;

    public boolean dropSounds() {
        return dropSounds;
    }

    public void toggleDropSounds() {
        dropSounds = !dropSounds;
    }

    public LootFilter withDropSounds(final boolean on) {
        dropSounds = on;
        return this;
    }

    public static String jewelRarityName(final int value) {
        return switch (value) {
            case 1 -> "隱藏普通珠寶";
            case 2 -> "只撿稀有珠寶";
            case 3 -> "全部不撿";
            default -> "全部撿";
        };
    }

    public int jewelRarity() {
        return jewelRarity;
    }

    public void cycleJewelRarity(final boolean backwards) {
        jewelRarity = next(JEWEL_RARITIES, jewelRarity, backwards);
    }

    /** 遺時星晶依稀有度（2026-10）：0 全部撿、1 只撿稀有、2 全部不撿。跟一般珠寶分開設定。 */
    private int timeLostRarity;
    /** 不撿的遺時星晶種類（2026-10）。 */
    private final EnumSet<TimeLost> hiddenTimeLost = EnumSet.noneOf(TimeLost.class);
    public static final int[] TIME_LOST_RARITIES = {0, 1, 2};

    public static String timeLostRarityName(final int value) {
        return switch (value) {
            case 1 -> "只撿稀有的";
            case 2 -> "全部不撿";
            default -> "全部撿";
        };
    }

    public int timeLostRarity() {
        return timeLostRarity;
    }

    public void cycleTimeLostRarity(final boolean backwards) {
        timeLostRarity = next(TIME_LOST_RARITIES, timeLostRarity, backwards);
    }

    /** 這一種遺時星晶撿不撿。 */
    public boolean hidesTimeLost(final TimeLost kind) {
        return hiddenTimeLost.contains(kind);
    }

    public void toggleTimeLost(final TimeLost kind) {
        if (!hiddenTimeLost.remove(kind)) {
            hiddenTimeLost.add(kind);
        }
    }

    /** 遺時星晶有沒有設定過濾（給總覽的說明）。 */
    public boolean filtersTimeLost() {
        return timeLostRarity > 0 || !hiddenTimeLost.isEmpty();
    }

    /** 換預設組合時把遺時星晶的設定帶過去。 */
    public LootFilter withTimeLost(final LootFilter from) {
        timeLostRarity = from.timeLostRarity;
        hiddenTimeLost.clear();
        hiddenTimeLost.addAll(from.hiddenTimeLost);
        return this;
    }

    /** 遺時星晶：先看種類、再看稀有度（傳奇永遠撿）；不看一般珠寶的設定。 */
    private boolean hidesTimeLost(final TimeLost kind, final Rarity rarity) {
        if (rarity == Rarity.UNIQUE) {
            return false;
        }
        if (hiddenTimeLost.contains(kind)) {
            return true;
        }
        return switch (timeLostRarity) {
            case 1 -> rarity != Rarity.RARE;
            case 2 -> true;
            default -> false;
        };
    }

    private boolean hidesJewel(final Rarity rarity) {
        return switch (jewelRarity) {
            case 1 -> rarity == Rarity.NORMAL;
            case 2 -> rarity == Rarity.NORMAL || rarity == Rarity.MAGIC;
            case 3 -> rarity != Rarity.UNIQUE;
            default -> false;
        };
    }

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

    /** 這個稀有度的裝備要不要隱藏（傳奇永遠顯示）。 */
    public boolean hidesEquipment(final Rarity rarity) {
        return rarity != Rarity.UNIQUE && hiddenEquipment.contains(rarity);
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

    /** 一定要撿的基底。 */
    public Set<String> keepBases() {
        return Collections.unmodifiableSet(keepBases);
    }

    public boolean keepsBase(final String baseId) {
        return keepBases.contains(baseId);
    }

    /** 加入或拿掉一個一定要撿的基底（不會讓預設組合變成自訂：存檔時另外記）。 */
    public void toggleKeepBase(final String baseId) {
        if (!keepBases.remove(baseId)) {
            keepBases.add(baseId);
        }
    }

    public void clearKeepBases() {
        keepBases.clear();
    }

    /** 換預設組合時把珠寶的設定帶過去。 */
    public LootFilter withJewelRarity(final int value) {
        jewelRarity = normalizeJewelRarity(value);
        return this;
    }

    /** 換預設組合時把一定要撿的基底帶過去。 */
    public LootFilter withKeepBases(final Set<String> bases) {
        keepBases.clear();
        keepBases.addAll(bases);
        return this;
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
     * 一件掉落要不要隱藏（含進階規則）。傳奇、珠寶永遠顯示；地圖只看「地圖依稀有度隱藏」。
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
        if (candidate.map()) {
            return hidesMap(candidate.rarity());
        }
        if (candidate.jewel()) {
            final TimeLost timeLost = candidate.timeLost();
            if (timeLost != null) {
                return hidesTimeLost(timeLost, candidate.rarity()); // 2026-10：遺時星晶有自己的設定（種類、稀有度）
            }
            // 2026-10：珠寶也能過濾（傳奇珠寶永遠撿）；被藏起來的珠寶有夠好的詞綴（保留好詞綴）照樣撿
            return hidesJewel(candidate.rarity()) && !keptByAffixTier(candidate);
        }
        if (candidate.rarity() == Rarity.UNIQUE) {
            return false;
        }
        // 2026-10：優先順序（由上往下，先符合的算數；以前各條規則互相蓋掉，開了「保留好詞綴」或「只撿的基底」幾乎什麼都不藏）
        //   1. 傳奇：永遠撿（上面）
        //   2. 不同類的武器／不同系的防具、等級差太多、不在「只撿的基底」裡：不撿，好詞綴也救不回來
        //   3. 稀有度被勾成隱藏：不撿，但有好詞綴（保留好詞綴）的撿回來
        if (unlikeMine(candidate.tags(), who, rules.weaponsLikeMine(), rules.armourLikeMine())
                || rules.levelGap() > 0 && candidate.itemLevel() > 0 && who.level() > 0 && candidate.itemLevel() < who.level() - rules.levelGap()
                || !keepBases.isEmpty() && (candidate.baseId() == null || !keepBases.contains(candidate.baseId()))) {
            return true;
        }
        final boolean hidden = candidate.rarity() != null && hiddenEquipment.contains(candidate.rarity());
        return hidden && !keptByAffixTier(candidate, who);
    }

    /** 有夠好的詞綴（T1～T「保留詞綴階級」）：就算稀有度、等級差、不同類的規則要藏，也照樣撿。 */
    public boolean keptByAffixTier(final Candidate candidate) {
        return rules.keepAffixTier() > 0 && candidate.bestAffixRank() > 0 && candidate.bestAffixRank() <= rules.keepAffixTier();
    }

    /** 同上；開了「好詞綴也只撿同類」時，跟我不同類（武器種類、防具系別）的不保留。 */
    public boolean keptByAffixTier(final Candidate candidate, final Viewer who) {
        return keptByAffixTier(candidate) && !(keepAffixLikeMine && unlikeMine(candidate.tags(), who, true, true));
    }

    /** 這個稀有度的地圖要不要隱藏（「地圖依稀有度隱藏」；傳奇地圖、不知道稀有度的永遠顯示）。 */
    public boolean hidesMap(final Rarity rarity) {
        if (rarity == null || rarity == Rarity.UNIQUE || rules.mapRarity() <= 0) {
            return false;
        }
        return switch (rarity) {
            case NORMAL -> true;
            case MAGIC -> rules.mapRarity() >= 2;
            default -> false;
        };
    }

    /**
     * 這件裝備（基底標籤）是不是「跟我不同類」：手上拿弓就只有弓是同類（雙手武器的人盾也不同類；沒拿弓的人箭袋也不同類）、
     * 身上穿力量系防具就只有力量系的防具與盾是同類。沒拿武器／沒穿防具時不管（都算同類）。
     * 掉落過濾器的「只撿跟我同類的」與商人賭博的「只看我這類」共用。
     *
     * @param checkWeapons 要不要看武器（與盾對雙手武器）
     * @param checkArmour  要不要看防具與盾的系別
     */
    /** 武器是單手還是雙手（基底標籤 one_hand／two_hand；都沒寫回傳 null）。 */
    private static String handedness(final Set<String> tags) {
        return tags.contains("two_hand") ? "two_hand" : tags.contains("one_hand") ? "one_hand" : null;
    }

    /** 珠寶依稀有度只剩 0、2、3（1 跟 0 一樣，讀成 0）。 */
    private static int normalizeJewelRarity(final int value) {
        final int clamped = Math.max(0, Math.min(3, value));
        return clamped == 1 ? 0 : clamped;
    }

    public static boolean unlikeMine(final Set<String> tags, final Viewer who, final boolean checkWeapons, final boolean checkArmour) {
        if (checkWeapons && !who.weaponTags().isEmpty()) {
            if (tags.contains("weapon")) {
                for (final String tag : tags) {
                    if (WEAPON_CLASSES.contains(tag) && !who.weaponTags().contains(tag)) {
                        return true; // 跟手上的不同類
                    }
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

    public boolean hidesCharms() {
        return hideCharms;
    }

    public boolean keepAffixLikeMine() {
        return keepAffixLikeMine;
    }

    public void toggleKeepAffixLikeMine() {
        keepAffixLikeMine = !keepAffixLikeMine;
        preset = CUSTOM;
    }

    public void toggleCharms() {
        hideCharms = !hideCharms;
        preset = CUSTOM;
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

    /** 地圖依稀有度：全部顯示 → 隱藏普通 → 只撿稀有 → 全部顯示（反方向就倒著走）。 */
    public void cycleMapRarity(final boolean backwards) {
        rules = rules.withMapRarity(next(MAP_RARITIES, rules.mapRarity(), backwards));
        preset = CUSTOM;
    }

    /** 保留詞綴階級：關 → T1 → T2 → … → T5 → 關（反方向就倒著走）。 */
    public void cycleKeepAffixTier(final boolean backwards) {
        rules = rules.withKeepAffixTier(next(AFFIX_TIERS, rules.keepAffixTier(), backwards));
        preset = CUSTOM;
    }

    /** 全部顯示（清掉所有隱藏與規則）。 */
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
        // 2026-10：以前漏了這幾項（只撿的基底、珠寶、遺時星晶），按了「全部顯示」還是會藏東西
        keepBases.clear();
        jewelRarity = 0;
        timeLostRarity = 0;
        hiddenTimeLost.clear();
    }

    /** 一次設定好幾種通貨（選單上「這一級全部隱藏／顯示」）。 */
    public void setCurrenciesHidden(final java.util.Collection<String> ids, final boolean hidden) {
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

    // ---- 存檔 ----

    /**
     * 存檔用的短字串。預設組合只記代號（{@code p=standard}），自訂記每一項：
     * {@code p=custom;eq=normal,magic;cur=armour_scrap,whetstone;sg=0;ug=1;fl=better}。
     * 進階規則的地圖是 {@code mr}（稀有度）；舊版的 {@code mt}（地圖階級）已經不用，讀到就略過。
     */
    public String encode() {
        final List<String> timeLostHidden = new ArrayList<>();
        for (final TimeLost kind : hiddenTimeLost) {
            timeLostHidden.add(kind.id());
        }
        final String kept = (keepBases.isEmpty() ? "" : ";kb=" + String.join(",", keepBases)) + (jewelRarity > 0 ? ";jr=" + jewelRarity : "")
                + (timeLostRarity > 0 ? ";tr=" + timeLostRarity : "") + (timeLostHidden.isEmpty() ? "" : ";th=" + String.join(",", timeLostHidden))
                + (dropSounds ? "" : ";ds=0");
        if (!CUSTOM.equals(preset)) {
            return "p=" + preset + kept;
        }
        final List<String> rarities = new ArrayList<>();
        for (final Rarity rarity : hiddenEquipment) {
            rarities.add(rarity.name().toLowerCase(Locale.ROOT));
        }
        return "p=" + CUSTOM
                + ";eq=" + String.join(",", rarities)
                + ";cur=" + String.join(",", hiddenCurrencies)
                + ";sg=" + (hideSkillGems ? 1 : 0)
                + (hideCharms ? ";ch=1" : "")
                + (keepAffixLikeMine ? ";kl=1" : "")
                + ";ug=" + (hideSupportGems ? 1 : 0)
                + ";fl=" + flasks.name().toLowerCase(Locale.ROOT)
                + (rules.equals(Rules.NONE) ? "" : ";wl=" + (rules.weaponsLikeMine() ? 1 : 0) + ";al=" + (rules.armourLikeMine() ? 1 : 0)
                + ";lg=" + rules.levelGap() + ";gl=" + rules.minSkillGemLevel() + ";gt=" + rules.minSupportTier() + ";mr=" + rules.mapRarity()
                + (rules.keepAffixTier() > 0 ? ";kt=" + rules.keepAffixTier() : ""))
                + kept;
    }

    /**
     * 讀存檔。沒存過（null）或讀不懂的用 loot.yml 的預設組合；
     * 記的是預設組合代號就照目前的設定（loot.yml 改了會跟著變），組合被刪掉了也改用預設。
     */
    public static LootFilter decode(final String text, final LootConfig config) {
        if (text == null || text.isBlank()) {
            return config.defaultFilter();
        }
        String presetId = null;
        final LootFilter filter = new LootFilter();
        for (final String part : text.split(";")) {
            final int equals = part.indexOf('=');
            if (equals <= 0) {
                continue;
            }
            final String key = part.substring(0, equals).trim();
            final String value = part.substring(equals + 1).trim();
            switch (key) {
                case "p" -> presetId = value;
                case "eq" -> {
                    for (final String name : list(value)) {
                        for (final Rarity rarity : Rarity.values()) {
                            if (rarity != Rarity.UNIQUE && rarity.name().equalsIgnoreCase(name)) {
                                filter.hiddenEquipment.add(rarity);
                            }
                        }
                    }
                }
                case "cur" -> filter.hiddenCurrencies.addAll(list(value));
                case "sg" -> filter.hideSkillGems = "1".equals(value);
                case "ch" -> filter.hideCharms = "1".equals(value);
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
                case "kb" -> filter.keepBases.addAll(list(value));
                case "jr" -> filter.jewelRarity = normalizeJewelRarity(integer(value));
                case "ds" -> filter.dropSounds = !"0".equals(value);
                case "tr" -> filter.timeLostRarity = Math.max(0, Math.min(2, integer(value)));
                case "th" -> {
                    for (final String name : list(value)) {
                        final TimeLost kind = TimeLost.byId(name);
                        if (kind != null) {
                            filter.hiddenTimeLost.add(kind);
                        }
                    }
                }
                default -> {
                    // 之後版本加的欄位：略過
                }
            }
        }
        if (presetId == null) {
            return config.defaultFilter().copy().withKeepBases(filter.keepBases).withJewelRarity(filter.jewelRarity).withTimeLost(filter)
                    .withDropSounds(filter.dropSounds);
        }
        if (!CUSTOM.equals(presetId)) {
            final LootConfig.Preset preset = config.preset(presetId);
            return (preset == null ? config.defaultFilter().copy() : of(preset)).withKeepBases(filter.keepBases)
                    .withJewelRarity(filter.jewelRarity).withTimeLost(filter).withDropSounds(filter.dropSounds);
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
        copy.keepBases.addAll(keepBases);
        copy.jewelRarity = jewelRarity;
        copy.timeLostRarity = timeLostRarity;
        copy.hiddenTimeLost.addAll(hiddenTimeLost);
        copy.dropSounds = dropSounds;
        return copy;
    }

    @Override
    public boolean equals(final Object other) {
        if (!(other instanceof LootFilter that)) {
            return false;
        }
        return hiddenEquipment.equals(that.hiddenEquipment) && hiddenCurrencies.equals(that.hiddenCurrencies)
                && hideSkillGems == that.hideSkillGems && hideSupportGems == that.hideSupportGems && hideCharms == that.hideCharms && keepAffixLikeMine == that.keepAffixLikeMine
                && flasks == that.flasks && rules.equals(that.rules) && preset.equals(that.preset) && keepBases.equals(that.keepBases) && jewelRarity == that.jewelRarity
                && timeLostRarity == that.timeLostRarity && hiddenTimeLost.equals(that.hiddenTimeLost) && dropSounds == that.dropSounds;
    }

    @Override
    public int hashCode() {
        return Objects.hash(hiddenEquipment, hiddenCurrencies, hideSkillGems, hideSupportGems, hideCharms, keepAffixLikeMine, flasks, rules, preset, keepBases, jewelRarity, timeLostRarity, hiddenTimeLost, dropSounds);
    }

    @Override
    public String toString() {
        return encode();
    }
}
