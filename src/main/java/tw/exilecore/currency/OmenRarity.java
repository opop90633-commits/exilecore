package tw.exilecore.currency;

import java.util.Locale;

/**
 * 星籤的稀有度（2026-10-27 站長要的：照稀有度分外框，讓珍貴的一眼看得出來）。
 * 照 currencies.yml 的 {@code omen.weight}（流寇、祭壇給星籤時抽到這種的權重；2026-10-26 照 poe2db 市價訂的）分四級，
 * 每級一種顏色：物品名稱、說明裡的「稀有度」那一行、資源包在格子裡畫的外框（UiPack，漸層底框＋實線外框）都用這個顏色。
 */
public enum OmenRarity {
    /** 權重 180 以上（豐饒前後綴、揭露、星火、必變、同類、雙湮滅、雙詞綴、催化…；2026-10-27 便宜的權重 ×3，這次之前 60）：銀白。 */
    COMMON("普通", 0xC9D3DE, 180),
    /** 權重 25～179（至臻前後綴、古代、恩澤、星魄去前後綴；現在 75～135）：藍。 */
    RARE("稀有", 0x4FA8FF, 25),
    /** 權重 6～24（聖化、光明；2026-10-29 起這兩個權重調高，改照 {@link #FIXED} 標示）：紫。 */
    PRECIOUS("珍貴", 0xC77DFF, 6),
    /** 權重 5 以下（去劣、湮滅／亂流去前後綴、不碎；2026-10-29 起權重調高，改照 {@link #FIXED} 標示）：金橘。 */
    EXALTED("極稀有", 0xFFB43C, 0);

    /**
     * 2026-10-29 照站長：最貴的 8 種星籤權重調高（×12），掉落變多，但稀有度標示照原本的（照 poe2db 市價）：
     * 這幾個不照權重算。這次之前（同日稍早 ×4 時）是把門檻改成 20／50。
     */
    static final java.util.Map<String, OmenRarity> FIXED = java.util.Map.of(
            "omen_whittling", EXALTED, "omen_sinistral_guard", EXALTED, "omen_dextral_guard", EXALTED,
            "omen_dextral_erasure", EXALTED, "omen_sinistral_erasure", EXALTED, "omen_chance", EXALTED,
            "omen_light", PRECIOUS, "omen_sanctification", PRECIOUS);

    private final String label;
    private final int rgb;
    private final int minWeight;

    OmenRarity(final String label, final int rgb, final int minWeight) {
        this.label = label;
        this.rgb = rgb;
        this.minWeight = minWeight;
    }

    /** 玩家看到的名稱（普通／稀有／珍貴／極稀有）。 */
    public String label() {
        return label;
    }

    /** 外框與名稱的顏色（0xRRGGBB）。 */
    public int rgb() {
        return rgb;
    }

    /** MiniMessage 的顏色標籤，例如 {@code <#ffb43c>}。 */
    public String tag() {
        return String.format(Locale.ROOT, "<#%06x>", rgb);
    }

    /** 資源包外框的代號（物品 custom_model_data 字串用：沒有通貨圖的星籤掛 {@code currency:omen_tier_<key>}）。 */
    public String key() {
        return name().toLowerCase(Locale.ROOT);
    }

    /** 沒有自己的通貨圖時，物品上掛的「圖的代號」（資源包照它畫外框＋原版的樣子）。 */
    public String artId() {
        return "omen_tier_" + key();
    }

    public static OmenRarity ofWeight(final int weight) {
        for (final OmenRarity rarity : values()) {
            if (weight >= rarity.minWeight) {
                return rarity;
            }
        }
        return EXALTED;
    }

    /** 這種通貨是星籤的話回傳它的稀有度，不是回傳 null。 */
    public static OmenRarity of(final CurrencyType type) {
        if (type == null || type.operation() != CurrencyOperation.OMEN || type.omen() == null) {
            return null;
        }
        final OmenRarity fixed = FIXED.get(type.id());
        return fixed != null ? fixed : ofWeight(type.omen().weight());
    }
}
