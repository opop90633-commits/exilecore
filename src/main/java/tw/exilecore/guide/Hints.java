package tw.exilecore.guide;

import tw.exilecore.currency.CurrencyOperation;
import tw.exilecore.currency.CurrencyType;

import java.util.Map;

/**
 * 第一次遇到就提示（2026-10-20）：撿到第一顆未切割寶石、第一件未鑑定裝備、第一顆通貨……聊天欄說一句「這是什麼、怎麼用」，
 * 每個角色每種只說一次（{@link GuideService#hint}）。純資料，不依賴 Bukkit。
 */
public final class Hints {

    public static final String UNCUT_SKILL = "uncut_skill";
    public static final String UNCUT_SUPPORT = "uncut_support";
    public static final String UNIDENTIFIED = "unidentified";
    public static final String CURRENCY = "currency";
    public static final String ESSENCE = "essence";
    public static final String OMEN = "omen";
    public static final String CATALYST = "catalyst";
    public static final String CARD = "card";
    public static final String MAP = "map";
    public static final String FLASK = "flask";
    public static final String JEWEL = "jewel";
    public static final String DEATH = "death";
    public static final String LEVEL10 = "level10";
    public static final String LEVEL20 = "level20";
    // 2026-10-30：新的第一次提示
    public static final String RUNE = "rune";
    public static final String CHARM = "charm";
    public static final String CORRUPT = "corrupt";
    public static final String LEVEL40 = "level40";

    /** 代號 → 提示（MiniMessage，一行）。 */
    public static final Map<String, String> MESSAGES = Map.ofEntries(
            Map.entry(UNCUT_SKILL, "撿到<white>未切割技能寶石</white>：拿在手上右鍵，雕成一顆新技能（通貨袋 /bag 裡 Shift＋右鍵也行）"),
            Map.entry(UNCUT_SUPPORT, "撿到<white>未切割輔助寶石</white>：右鍵切成輔助寶石，再到「技能」選單裝進技能旁的孔，技能會變強"),
            Map.entry(UNIDENTIFIED, "撿到<white>未鑑定的裝備</white>：拿鑑定卷軸左鍵點它，或回營地找鑑定師一次全部鑑定（免費）"),
            Map.entry(CURRENCY, "撿到<white>通貨</white>：會自動進通貨袋（/bag）；拿在游標上點裝備可以改造它，洗裝台 /craft 更方便"),
            Map.entry(ESSENCE, "撿到<white>星魄</white>：用在裝備上，保證加上它寫的那一條詞綴"),
            Map.entry(OMEN, "撿到<white>星籤</white>：先掛在裝備上，下一顆通貨會照籤的規則做（洗裝台 /craft 左邊放籤）"),
            Map.entry(CATALYST, "撿到<white>催化劑</white>：用在戒指、項鍊、護身符上加品質，強化那一類的詞綴"),
            Map.entry(CARD, "撿到<white>星諭卡</white>：集滿一套就能兌換獎勵；在通貨袋或倉庫裡 Shift＋右鍵"),
            Map.entry(MAP, "撿到<white>地圖</white>：主線打完後，放進營地的地圖裝置就能開一張星痕，裡面的怪掉得更好"),
            Map.entry(FLASK, "撿到<white>藥劑</white>：等級比身上的高就換到藥劑欄，回復量更多"),
            Map.entry(JEWEL, "撿到<white>珠寶</white>：鑲在天賦樹的珠寶孔裡（選單 →「天賦」），要先點到那一格"),
            Map.entry(DEATH, "死了會從最後碰到的檢查點復活；在地圖裡死亡會掉經驗，快撐不住就先退回營地"),
            Map.entry(LEVEL10, "10 級了：升級拿到的天賦點記得點（/tree）；營地的委託板每天有委託可以換功勳"),
            Map.entry(LEVEL20, "20 級了：裝備開始掉稀有的，用通貨改造一下；不要的裝備拿去營地拆解，別直接丟"),
            Map.entry(RUNE, "撿到<white>符文</white>：拿在游標上點有符文孔的武器或防具；孔滿了可以選一顆換掉（沒腐化的換下來會還你），洗裝台 /craft 也能取出"),
            Map.entry(CHARM, "撿到<white>護符</white>：護符欄是腰帶給的，裝了腰帶後把護符拿在手上右鍵裝上；條件成立（被凍結、點燃…）時自動觸發"),
            Map.entry(CORRUPT, "撿到<white>腐化通貨</white>：用了結果不能復原，腐化後的裝備不能再改造（符文還能換）；技能寶石也能腐化，有機會 +1 等"),
            Map.entry(LEVEL40, "40 級了：技能寶石的屬性不夠時會自動降到屬性夠的等級，記得在天賦、裝備補力量／敏捷／智慧；/filter 可以藏掉不要的掉落"));

    private Hints() {
    }

    /** 這顆通貨該用哪一種提示（null = 不提示）。 */
    public static String forCurrency(final CurrencyType type) {
        if (type.uncutGem()) {
            return type.id().startsWith(CurrencyType.UNCUT_SKILL_PREFIX) ? UNCUT_SKILL : UNCUT_SUPPORT;
        }
        if (tw.exilecore.currency.CurrencyGroups.card(type)) {
            return CARD;
        }
        if (type.operation() == tw.exilecore.currency.CurrencyOperation.RUNE) {
            return RUNE; // 2026-10-30
        }
        return switch (type.operation()) {
            case CORRUPT -> CORRUPT; // 2026-10-30
            case ESSENCE -> ESSENCE;
            case OMEN -> OMEN;
            case CATALYST -> CATALYST;
            case IDENTIFY, NONE -> null; // 鑑定卷軸、門票：不用教
            default -> CURRENCY;
        };
    }
}
