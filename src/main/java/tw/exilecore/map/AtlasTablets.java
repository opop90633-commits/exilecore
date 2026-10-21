package tw.exilecore.map;

import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * 先驅石板（2026-10-21，PoE2 的 Precursor Tablet）：完成一座塔之後插一片，效果罩到塔周圍（atlas.yml 的 tower.radius，星圖天賦可以加）
 * 的所有地圖；2026-10-21：罩到的地圖每開一張用掉 1 次，{@link #USES} 次用完塔空出來可以再插。純資料：種類、通貨代號、效果；插了哪片存在角色進度 {@code tablet.q:r} → 通貨代號。
 */
public final class AtlasTablets {

    /** 角色進度的鍵：tablet.q:r。 */
    public static final String PROGRESS_PREFIX = "tablet.";
    /** 通貨代號的前綴：tablet_<種類>。 */
    public static final String CURRENCY_PREFIX = "tablet_";
    /** 事件完成時掉那種事件的石板的基本機率。 */
    public static final double EVENT_DROP_CHANCE = 0.03;
    /** 2026-10-21：一片石板能用幾次（罩到的地圖每開一張用掉 1 次，用完塔空出來可以再插）。 */
    public static final int USES = 3;
    /** 角色進度的鍵：tabletuses.q:r → 剩幾次（沒有 = 還沒用過，{@link #USES}）。 */
    public static final String USES_PREFIX = "tabletuses.";
    /** 地圖 Boss 掉 Boss 石板的機率。 */
    public static final double BOSS_DROP_CHANCE = 0.02;

    /** 石板的種類。 */
    public enum Kind {
        SHRINE("shrine", "神龕石板", "罩到的地圖保證多 1 座神龕、神龕的機會 +100%"),
        STRONGBOX("strongbox", "寶箱石板", "罩到的地圖保證多 1 個寶箱、寶箱的機會 +100%"),
        BREACH("breach", "裂隙石板", "罩到的地圖保證多 1 個裂隙、裂隙的機會 +100%"),
        EXILE("exile", "流寇石板", "罩到的地圖保證多 1 個流寇、流寇的機會 +100%"),
        ABYSS("abyss", "深淵石板", "罩到的地圖保證多 1 條深淵裂縫、深淵的機會 +100%"),
        RITUAL("ritual", "祭壇石板", "罩到的地圖祭壇出現的機率 +100%、貢品 +25%"),
        DELIRIUM("delirium", "幻霧石板", "罩到的地圖幻霧出現的機率 +100%、獎勵進度 +25%"),
        BOSS("boss", "首領石板", "罩到的一般格子當作 Boss 格（Boss 更強、掉得更多）"),
        BOUNTY("bounty", "豐饒石板", "罩到的地圖物品數量 +15%、稀有度 +15%");

        private final String source;
        private final String displayName;
        private final String description;

        Kind(final String source, final String displayName, final String description) {
            this.source = source;
            this.displayName = displayName;
            this.description = description;
        }

        /** 事件的來源代號（shrine、ritual…；boss、bounty 不是事件）。 */
        public String source() {
            return source;
        }

        public String displayName() {
            return displayName;
        }

        public String description() {
            return description;
        }

        /** 通貨代號（tablet_shrine…）。 */
        public String currencyId() {
            return CURRENCY_PREFIX + source;
        }

        /** 通貨代號 → 種類；不是石板回傳 null。 */
        public static Kind ofCurrency(final String currencyId) {
            if (currencyId == null || !currencyId.startsWith(CURRENCY_PREFIX)) {
                return null;
            }
            final String source = currencyId.substring(CURRENCY_PREFIX.length()).toLowerCase(Locale.ROOT);
            for (final Kind kind : values()) {
                if (kind.source.equals(source)) {
                    return kind;
                }
            }
            return null;
        }

        /** 事件來源代號 → 掉的石板；沒有這種事件的石板回傳 null。 */
        public static Kind ofSource(final String source) {
            for (final Kind kind : values()) {
                if (kind.source.equals(source) && kind != BOSS && kind != BOUNTY) {
                    return kind;
                }
            }
            return null;
        }
    }

    private AtlasTablets() {
    }

    /** 角色進度裡這座塔的鍵。 */
    public static String key(final int q, final int r) {
        return PROGRESS_PREFIX + q + ":" + r;
    }

    /** 角色進度裡這座塔的石板剩幾次的鍵。 */
    public static String usesKey(final int q, final int r) {
        return USES_PREFIX + q + ":" + r;
    }

    /** 剩幾次（沒記錄或看不懂當作全新的 {@link #USES}，最少 1）。 */
    public static int parseUses(final String value) {
        if (value == null) {
            return USES;
        }
        try {
            return Math.max(1, Math.min(USES, Integer.parseInt(value.strip())));
        } catch (final NumberFormatException e) {
            return USES;
        }
    }

    /**
     * 這張圖吃到的石板效果（開圖時算）：罩到這格的每片石板，照種類加到星圖天賦的效果上（{@link AtlasBonus} 的鍵），
     * effect 是石板效果的倍率（1 = 原本；星圖天賦 atlas.tablet.effect +25% 就是 1.25）。
     * Boss、豐饒石板不是 AtlasBonus 的鍵，回傳在 {@link Applied} 裡另外處理。
     */
    public record Applied(AtlasBonus bonus, int bossTablets, double bountyQuantity, double bountyRarity, List<Kind> kinds) {
    }

    public static Applied apply(final AtlasBonus base, final List<Kind> covering, final double effect) {
        AtlasBonus bonus = base;
        int boss = 0;
        double quantity = 0;
        double rarity = 0;
        for (final Kind kind : covering) {
            switch (kind) {
                case SHRINE, STRONGBOX, BREACH, EXILE, ABYSS -> {
                    bonus = bonus.plus("atlas." + kind.source() + ".extra", 1);
                    bonus = bonus.plus("atlas." + kind.source() + ".weight", 100 * effect);
                }
                case RITUAL -> {
                    bonus = bonus.plus("atlas.ritual.chance", 100 * effect);
                    bonus = bonus.plus("atlas.ritual.tribute", 25 * effect);
                }
                case DELIRIUM -> {
                    bonus = bonus.plus("atlas.delirium.chance", 100 * effect);
                    bonus = bonus.plus("atlas.delirium.progress", 25 * effect);
                }
                case BOSS -> boss++;
                case BOUNTY -> {
                    quantity += 15 * effect;
                    rarity += 15 * effect;
                }
            }
        }
        return new Applied(bonus, boss, quantity, rarity, List.copyOf(covering));
    }

    /** 介面用：每種石板的名字（通貨代號 → 名稱）。 */
    public static Map<String, String> names() {
        final Map<String, String> map = new java.util.LinkedHashMap<>();
        for (final Kind kind : Kind.values()) {
            map.put(kind.currencyId(), kind.displayName());
        }
        return map;
    }
}
