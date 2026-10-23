package tw.exilecore.currency;

import tw.exilecore.core.config.YamlNode;
import tw.exilecore.item.ItemData;
import tw.exilecore.item.ItemGenerator;
import tw.exilecore.item.Rarity;

import java.util.EnumSet;
import java.util.Locale;
import java.util.Set;

/**
 * 一種通貨的定義（data/currencies.yml）：
 * <pre>
 * reroll_rare:
 *   name: 亂流石
 *   material: ENDER_EYE
 *   model: exilecore:currency/reroll_rare
 *   stack: 10
 *   operation: REROLL_RARE
 *   applies_to: { rarity: [RARE] }
 *   drop_weight: 300
 *   drop_level: 1           # 幾級以上的怪物才會掉（可省略）
 *   description: 重新隨機一件稀有物品的所有詞綴
 *   flavor: 光在裡面亂竄的星骸（世界觀描述，可省略）
 * </pre>
 * 開輔助孔的通貨（operation: SKILL_SOCKET）另外寫 {@code sockets: 3}：把技能開到第幾個孔（技能要剛好少一孔）。
 * 僥倖石（operation: CHANCE）另外寫 {@code success: 0.1}：變成傳奇的機率（其他的就碎掉）。
 *
 * @param id          代號
 * @param name        顯示名稱
 * @param material    原版材質
 * @param model       資源包模型（可選）
 * @param stack       堆疊上限
 * @param operation   對物品做的操作
 * @param rarities    可用在哪些稀有度（空 = 全部）
 * @param dropWeight  掉落權重
 * @param description 一句話說明
 * @param flavor      世界觀描述（這是哪一種星骸；可為空字串）
 * @param dropLevel   幾級以上的怪物才會掉
 * @param sockets     SKILL_SOCKET：把技能的輔助孔開到第幾個（其他通貨為 0）
 * @param success     CHANCE：變成傳奇的機率（其他通貨為 0）
 * @param affix       ESSENCE：保證給的詞綴代號（其他通貨為 null）
 * @param catalyst    CATALYST：催化劑種類（{@link tw.exilecore.item.Catalyst}；其他通貨為 null）
 * @param omen        OMEN：星籤的規則（其他通貨為 null）
 */
public record CurrencyType(String id, String name, String material, String model, int stack,
                           CurrencyOperation operation, Set<Rarity> rarities, int dropWeight, String description,
                           String flavor, int dropLevel, int sockets, double success, String affix, tw.exilecore.item.Catalyst catalyst,
                           Omen omen) {

    /**
     * 星籤的規則：哪些操作會觸發、觸發時改成怎麼做。
     *
     * @param triggers 用哪些操作的通貨時生效
     * @param effect   怎麼改
     */
    /**
     * @param weight 流寇、祭壇給星籤時抽到這種的權重（2026-10；omen 的 weight，沒寫 100）：好用的（去劣、保前後綴）少一點
     */
    public record Omen(Set<CurrencyOperation> triggers, Effect effect, int weight) {
        public Omen {
            triggers = Set.copyOf(triggers);
            weight = Math.max(0, weight);
        }

        public Omen(final Set<CurrencyOperation> triggers, final Effect effect) {
            this(triggers, effect, DEFAULT_WEIGHT);
        }

        public static final int DEFAULT_WEIGHT = 100;

        /** 星籤的效果。 */
        public enum Effect {
            /** 加的那條一定是前綴。 */
            FORCE_PREFIX,
            /** 加的那條一定是後綴。 */
            FORCE_SUFFIX,
            /** 加兩條（有空位的話）。 */
            DOUBLE_ADD,
            /** 移除的是階級最差的那條（而不是隨機）。 */
            REMOVE_LOWEST,
            /** 移除時不動前綴（只拿後綴）。 */
            KEEP_PREFIXES,
            /** 移除時不動後綴（只拿前綴）。 */
            KEEP_SUFFIXES
        }
    }

    /** 沒有星魄、催化劑、星籤欄位的建構（舊的呼叫端與測試）。 */
    public CurrencyType(final String id, final String name, final String material, final String model, final int stack,
                        final CurrencyOperation operation, final Set<Rarity> rarities, final int dropWeight, final String description,
                        final String flavor, final int dropLevel, final int sockets, final double success) {
        this(id, name, material, model, stack, operation, rarities, dropWeight, description, flavor, dropLevel, sockets, success, null, null, null);
    }

    public CurrencyType {
        rarities = Set.copyOf(rarities);
    }

    public static CurrencyType parse(final String id, final YamlNode node) {
        final CurrencyOperation operation = CurrencyOperation.valueOf(node.requireString("operation").trim().toUpperCase(Locale.ROOT));
        final Set<Rarity> rarities = EnumSet.noneOf(Rarity.class);
        for (final String text : node.node("applies_to").strings("rarity")) {
            rarities.add(Rarity.parse(text, Rarity.NORMAL));
        }
        int sockets = 0;
        if (operation == CurrencyOperation.SKILL_SOCKET) {
            sockets = node.integer("sockets", 0);
            if (sockets < 3 || sockets > 5) {
                throw new IllegalArgumentException(node.path() + ".sockets：開輔助孔的通貨要寫開到第幾孔（3 到 5）");
            }
        }
        double success = 0;
        if (operation == CurrencyOperation.CHANCE) {
            success = node.decimal("success", 0.1);
            if (success <= 0 || success > 1) {
                throw new IllegalArgumentException(node.path() + ".success：僥倖石變成傳奇的機率要在 0 到 1 之間（例如 0.1）");
            }
        }
        String affix = null;
        if (operation == CurrencyOperation.ESSENCE) {
            affix = node.requireString("affix").trim();
        }
        if (operation == CurrencyOperation.RUNE) {
            affix = node.requireString("rune").trim(); // 2026-10 符文：runes.yml 的代號（借 affix 欄位放）
        }
        tw.exilecore.item.Catalyst catalyst = null;
        if (operation == CurrencyOperation.CATALYST) {
            catalyst = tw.exilecore.item.Catalyst.parse(node.requireString("catalyst"));
            if (catalyst == null) {
                throw new IllegalArgumentException(node.path() + ".catalyst：不認得的催化劑種類 " + node.string("catalyst", "")
                        + "（resist、life、attack、caster、attribute、speed、defence）");
            }
        }
        Omen omen = null;
        if (operation == CurrencyOperation.OMEN) {
            final YamlNode rule = node.node("omen");
            final Set<CurrencyOperation> triggers = EnumSet.noneOf(CurrencyOperation.class);
            for (final String text : rule.strings("triggers")) {
                try {
                    triggers.add(CurrencyOperation.valueOf(text.trim().toUpperCase(Locale.ROOT)));
                } catch (final IllegalArgumentException e) {
                    throw new IllegalArgumentException(rule.path() + ".triggers：不認得的操作 " + text);
                }
            }
            if (triggers.isEmpty()) {
                throw new IllegalArgumentException(rule.path() + ".triggers：星籤要寫用哪些操作的通貨時生效（例如 [ADD_AFFIX_RARE]）");
            }
            final Omen.Effect effect;
            try {
                effect = Omen.Effect.valueOf(rule.requireString("effect").trim().toUpperCase(Locale.ROOT));
            } catch (final IllegalArgumentException e) {
                throw new IllegalArgumentException(rule.path() + ".effect：不認得的效果 " + rule.string("effect", "")
                        + "（FORCE_PREFIX、FORCE_SUFFIX、DOUBLE_ADD、REMOVE_LOWEST、KEEP_PREFIXES、KEEP_SUFFIXES）");
            }
            omen = new Omen(triggers, effect, rule.integer("weight", Omen.DEFAULT_WEIGHT));
        }
        final boolean uncut = id.startsWith(UNCUT_SKILL_PREFIX) || id.startsWith(UNCUT_SUPPORT_PREFIX);
        return new CurrencyType(id, node.requireString("name"),
                node.requireString("material").trim().toUpperCase(Locale.ROOT),
                node.string("model", null), uncut ? UNCUT_STACK : Math.max(1, Math.min(99, node.integer("stack", 20))),
                operation, rarities, node.integer("drop_weight", 0), node.string("description", ""),
                node.string("flavor", ""), Math.max(1, node.integer("drop_level", 1)), sockets, success, affix, catalyst, omen);
    }

    /** 未切割技能寶石當通貨時的代號前綴（後面接等級 1–20）。 */
    public static final String UNCUT_SKILL_PREFIX = "uncut_skill_";
    /** 未切割輔助寶石當通貨時的代號前綴（後面接階級 1–3）。 */
    public static final String UNCUT_SUPPORT_PREFIX = "uncut_support_";
    /** 未切割寶石的堆疊上限（寶石物品本身就是這麼多；資料檔寫別的也照這個，不然拿出來的數量對不上）。 */
    public static final int UNCUT_STACK = 20;

    /** 用在技能面板上的通貨（開輔助孔、2026-10-23 琢星稜鏡），不能拖到裝備上。 */
    public boolean isSkillCurrency() {
        return operation == CurrencyOperation.SKILL_SOCKET || operation == CurrencyOperation.SKILL_QUALITY;
    }

    /**
     * 未切割寶石（2026-10 算通貨：收進通貨袋、倉庫的通貨頁，在通貨交易所買賣）。
     * 物品本身還是寶石（skill 模組做出來的，右鍵雕琢、切割照舊），這裡只是讓它在通貨的地方有一個代號。
     */
    public boolean uncutGem() {
        return id.startsWith(UNCUT_SKILL_PREFIX) || id.startsWith(UNCUT_SUPPORT_PREFIX);
    }

    /** 這顆通貨能不能用在這件物品上。 */
    public boolean canApply(final ItemData data, final ItemGenerator generator) {
        if (!rarities.isEmpty() && !rarities.contains(data.rarity())) {
            return false;
        }
        if (!operation.canApply(data, generator)) {
            return false;
        }
        return Crafting.problem(this, data, generator) == null;
    }
}
