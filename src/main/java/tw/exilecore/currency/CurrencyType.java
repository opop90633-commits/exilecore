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
 * @param crafts      2026-10-26：高階／完美通貨的最低詞綴等級、星魄的階級（{@link Crafts}）
 */
public record CurrencyType(String id, String name, String material, String model, int stack,
                           CurrencyOperation operation, Set<Rarity> rarities, int dropWeight, String description,
                           String flavor, int dropLevel, int sockets, double success, String affix, tw.exilecore.item.Catalyst catalyst,
                           Omen omen, Crafts crafts) {

    /**
     * 2026-10-26 照 PoE2 0.5：
     * <ul>
     *   <li>{@code min_mod_level}：高階（Greater）／完美（Perfect）的蛻變、增幅（44／70）與富豪、崇高、混沌（35／50）——
     *       加的詞綴只從需求等級至少這麼高的階級抽。0 = 一般的。</li>
     *   <li>{@code essence_tier}：星魄的階級——次級（lesser）、一般（normal）、高階（greater）是「魔法 → 稀有，加一條固定階級的詞綴」；
     *       完美（perfect）與腐化（corrupted）是「稀有：隨機移除一條，再加那條詞綴」。沒寫是舊的規則（照物品等級抽、不是最差的那階）。</li>
     *   <li>{@code essence_level}：次級、一般、高階星魄給的那條詞綴是「需求等級 ≤ 這個數字的最好那一階」（不管物品等級，照 PoE2 的精髓）；
     *       沒寫照階級的預設（次級 16、一般 46、高階 60）。</li>
     *   <li>{@code affix_random: true}：affix 寫的幾條裡隨機挑一條這個基底出得了的（PoE2 的 Essence of the Infinite：力量、敏捷或智慧）。</li>
     *   <li>{@code pick_weight}：寶箱、精髓怪、裂隙、流寇、祭壇給這一類通貨時抽到這一種的權重（星籤照 omen.weight；沒寫照預設；
     *       寫 0 = 不會給，例如 2026-10-26 之前的舊催化劑）。</li>
     * </ul>
     */
    public record Crafts(int minModLevel, EssenceTier essenceTier, int essenceLevel, boolean affixRandom, int pickWeight) {
        public static final Crafts NONE = new Crafts(0, null, 0, false, -1);
    }

    /** 2026-10-26：星魄的階級（PoE2 的 Lesser／一般／Greater／Perfect 精髓與腐化精髓）。 */
    public enum EssenceTier {
        LESSER("次級", 16, 100),
        NORMAL("一般", 46, 60),
        GREATER("高階", 60, 25),
        PERFECT("完美", 0, 4),
        CORRUPTED("腐化", 0, 2);

        private final String displayName;
        private final int defaultLevel;
        private final int defaultWeight;

        EssenceTier(final String displayName, final int defaultLevel, final int defaultWeight) {
            this.displayName = displayName;
            this.defaultLevel = defaultLevel;
            this.defaultWeight = defaultWeight;
        }

        public String displayName() {
            return displayName;
        }

        /** 次級、一般、高階：給的那條詞綴最高到需求等級幾的階級（沒寫 essence_level 時）。 */
        public int defaultLevel() {
            return defaultLevel;
        }

        /** 寶箱、精髓怪給星魄時抽到這一階的預設權重。 */
        public int defaultWeight() {
            return defaultWeight;
        }

        /** 完美、腐化：稀有物品「移除一條再加一條」。 */
        public boolean replaces() {
            return this == PERFECT || this == CORRUPTED;
        }
    }

    /** 2026-10-26：給高階／完美通貨的最低詞綴等級（0 = 一般的）。 */
    public int minModLevel() {
        return crafts == null ? 0 : crafts.minModLevel();
    }

    /** 2026-10-26：星魄的階級；舊的（沒寫 essence_tier）是 null。 */
    public EssenceTier essenceTier() {
        return crafts == null ? null : crafts.essenceTier();
    }

    /** 2026-10-26：次級、一般、高階星魄給的詞綴最高到需求等級幾（0 = 舊的規則，照物品等級）。 */
    public int essenceLevel() {
        if (crafts == null || crafts.essenceTier() == null || crafts.essenceTier().replaces()) {
            return 0;
        }
        return crafts.essenceLevel() > 0 ? crafts.essenceLevel() : crafts.essenceTier().defaultLevel();
    }

    /**
     * 寶箱、精髓怪、裂隙、流寇、祭壇給「這一類」通貨時抽到這一種的權重（2026-10-26：以前寫在 MapRewards 裡，星籤照 omen.weight、其他都 1）。
     */
    public int pickWeight() {
        if (crafts != null && crafts.pickWeight() >= 0) {
            return crafts.pickWeight();
        }
        if (omen != null) {
            return omen.weight();
        }
        if (crafts != null && crafts.essenceTier() != null) {
            return crafts.essenceTier().defaultWeight();
        }
        return 1;
    }

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

        /** 2026-10-26：只有完美、腐化星魄觸發的星籤（PoE2 的 Crystallisation）寫 triggers: [ESSENCE]。 */
        public boolean firesOn(final CurrencyType used) {
            return triggers.contains(used.operation())
                    && (used.operation() != CurrencyOperation.ESSENCE || used.essenceTier() != null && used.essenceTier().replaces());
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
            KEEP_SUFFIXES,
            // ---- 2026-10-26 照 PoE2 0.5 補上的星籤 ----
            /** 湮滅石移除兩條（Omen of Greater Annulment）。 */
            REMOVE_TWO,
            /** 星火石做出來的前綴最多（3 前 1 後；Omen of Sinistral Alchemy）。 */
            MAX_PREFIXES,
            /** 星火石做出來的後綴最多（1 前 3 後；Omen of Dextral Alchemy）。 */
            MAX_SUFFIXES,
            /** 加的那條跟物品上已經有的詞綴同一類（標籤相同；Omen of Homogenising Exaltation／Coronation）。 */
            HOMOGENISE,
            /** 蝕變石一定有變化（Omen of Corruption）。 */
            ALWAYS_CHANGE,
            /** 天啟石只重骰固定詞綴（Omen of the Blessed）。 */
            IMPLICIT_ONLY,
            /** 天啟石用在稀有物品上時聖化：數值各乘 0.78～1.22，之後不能再改造（Omen of Sanctification）。 */
            SANCTIFY,
            /** 僥倖石失敗不會碎掉（Omen of Chance）。 */
            NO_DESTROY,
            /** 僥倖石成功時變成同一類物品的隨機傳奇（Omen of the Ancients）。 */
            ANCIENTS,
            /** 至臻石用掉飾品的催化劑品質，讓那一類詞綴比較容易出現（Omen of Catalysing Exaltation）。 */
            CATALYSING,
            /** 湮滅石只拿隱匿／揭露詞綴（PoE2 的褻瀆詞綴；Omen of Light）。 */
            REMOVE_VEILED
        }
    }

    /** 2026-10-26 之前的欄位（沒有 crafts）。 */
    public CurrencyType(final String id, final String name, final String material, final String model, final int stack,
                        final CurrencyOperation operation, final Set<Rarity> rarities, final int dropWeight, final String description,
                        final String flavor, final int dropLevel, final int sockets, final double success, final String affix,
                        final tw.exilecore.item.Catalyst catalyst, final Omen omen) {
        this(id, name, material, model, stack, operation, rarities, dropWeight, description, flavor, dropLevel, sockets, success, affix,
                catalyst, omen, Crafts.NONE);
    }

    /** 沒有星魄、催化劑、星籤欄位的建構（舊的呼叫端與測試）。 */
    public CurrencyType(final String id, final String name, final String material, final String model, final int stack,
                        final CurrencyOperation operation, final Set<Rarity> rarities, final int dropWeight, final String description,
                        final String flavor, final int dropLevel, final int sockets, final double success) {
        this(id, name, material, model, stack, operation, rarities, dropWeight, description, flavor, dropLevel, sockets, success, null, null, null);
    }

    public CurrencyType {
        rarities = Set.copyOf(rarities);
        crafts = crafts == null ? Crafts.NONE : crafts;
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
                        + "（life、mana、defence、physical、fire、cold、lightning、chaos、attack、caster、speed、attribute、minion）");
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
                        + "（" + String.join("、", java.util.Arrays.stream(Omen.Effect.values()).map(Enum::name).toList()) + "）");
            }
            omen = new Omen(triggers, effect, rule.integer("weight", Omen.DEFAULT_WEIGHT));
        }
        // 2026-10-26：高階／完美通貨、星魄的階級
        EssenceTier essenceTier = null;
        final String tierText = node.string("essence_tier", null);
        if (tierText != null && !tierText.isBlank()) {
            if (operation != CurrencyOperation.ESSENCE) {
                throw new IllegalArgumentException(node.path() + ".essence_tier：只有星魄（operation: ESSENCE）能寫");
            }
            try {
                essenceTier = EssenceTier.valueOf(tierText.trim().toUpperCase(Locale.ROOT));
            } catch (final IllegalArgumentException e) {
                throw new IllegalArgumentException(node.path() + ".essence_tier：要寫 lesser、normal、greater、perfect 或 corrupted");
            }
        }
        final int minModLevel = Math.max(0, node.integer("min_mod_level", 0));
        final Crafts crafts = new Crafts(minModLevel, essenceTier, Math.max(0, node.integer("essence_level", 0)),
                node.bool("affix_random", false), Math.max(-1, node.integer("pick_weight", -1)));
        final boolean uncut = id.startsWith(UNCUT_SKILL_PREFIX) || id.startsWith(UNCUT_SUPPORT_PREFIX);
        return new CurrencyType(id, node.requireString("name"),
                node.requireString("material").trim().toUpperCase(Locale.ROOT),
                node.string("model", null), uncut ? UNCUT_STACK : Math.max(1, Math.min(99, node.integer("stack", 20))),
                operation, rarities, node.integer("drop_weight", 0), node.string("description", ""),
                node.string("flavor", ""), Math.max(1, node.integer("drop_level", 1)), sockets, success, affix, catalyst, omen, crafts);
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
