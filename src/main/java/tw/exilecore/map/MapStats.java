package tw.exilecore.map;

import tw.exilecore.item.Affix;
import tw.exilecore.item.AffixMod;
import tw.exilecore.item.ItemBase;
import tw.exilecore.item.ItemData;
import tw.exilecore.item.ItemRepository;
import tw.exilecore.item.ItemStats;
import tw.exilecore.item.RolledAffix;
import tw.exilecore.stats.Modifier;

import java.util.ArrayList;
import java.util.List;

/**
 * 一張地圖物品的總效果：階級（決定怪物等級，每高一階多一點物品數量與稀有度），
 * 加上地圖詞綴（data/affixes/maps.yml）的修飾器，依屬性鍵的前綴分成
 * <ul>
 *   <li>{@code map.quantity}、{@code map.rarity}、{@code map.pack_size}、{@code map.magic_packs}、{@code map.rare_packs}：地圖本身（百分比點數，加總）</li>
 *   <li>{@code monster:屬性}：這張圖裡所有怪物（含 Boss）</li>
 *   <li>{@code boss:屬性}：只有 Boss</li>
 *   <li>{@code player:屬性}：在這張圖裡的玩家</li>
 * </ul>
 * 品質每 1% 給 1% 物品數量（星盤刻刀）。純邏輯，不依賴 Bukkit。
 *
 * @param tier        階級（T1–T16；特別的實例是 0）
 * @param monsterLevel 怪物等級
 * @param quantity    物品數量加成（百分比點數）
 * @param rarity      物品稀有度加成
 * @param packSize    怪物群大小加成
 * @param magicPacks  魔法怪物群增加（百分比點數：權重乘上 1 + x/100）
 * @param rarePacks   稀有怪物群增加
 */
public record MapStats(int tier, int monsterLevel, double quantity, double rarity, double packSize, double magicPacks,
                       double rarePacks, List<Modifier> monsterModifiers, List<Modifier> bossModifiers,
                       List<Modifier> playerModifiers) {

    /**
     * 地圖的階級表（maps.yml 的 settings.tiers、tier_quantity、tier_rarity）：PoE2 換界石的做法，
     * 每張地圖都能開任何一階，階級寫在地圖物品上。
     *
     * @param levels          每一階的怪物等級（索引 = 階級 − 1）
     * @param quantityPerTier T2 起每高一階多的物品數量（百分比點數）
     * @param rarityPerTier   T2 起每高一階多的物品稀有度
     */
    public record Tiers(List<Integer> levels, double quantityPerTier, double rarityPerTier) {

        public Tiers {
            levels = List.copyOf(levels);
            if (levels.isEmpty()) {
                throw new IllegalArgumentException("地圖至少要有一個階級");
            }
        }

        /** 最高階（T16）。 */
        public int max() {
            return levels.size();
        }

        /** 夾在 1 到最高階之間。 */
        public int clamp(final int tier) {
            return Math.max(1, Math.min(max(), tier));
        }

        /** 這一階的怪物等級。 */
        public int level(final int tier) {
            return levels.get(clamp(tier) - 1);
        }

        /** 這一階本身的物品數量加成（T1 是 0）。 */
        public double quantity(final int tier) {
            return quantityPerTier * (clamp(tier) - 1);
        }

        /** 這一階本身的物品稀有度加成（T1 是 0）。 */
        public double rarity(final int tier) {
            return rarityPerTier * (clamp(tier) - 1);
        }

        /** 最高階的怪物等級（地圖怪物最高幾級）。 */
        public int topLevel() {
            return level(max());
        }
    }

    /** 跟出貨的 maps.yml 一樣：T1 = 61 … T16 = 84，每高一階物品數量 +2%、稀有度 +3%。 */
    public static final Tiers DEFAULT_TIERS = new Tiers(List.of(61, 62, 63, 64, 65, 66, 68, 70, 71, 73, 75, 77, 78, 80, 82, 84), 2, 3);

    /** 目前的階級表（地圖模組載入 maps.yml 時設定；物品描述、每日委託也用它）。 */
    private static volatile Tiers tiers = DEFAULT_TIERS;

    public static final String MONSTER_PREFIX = "monster:";
    public static final String BOSS_PREFIX = "boss:";
    public static final String PLAYER_PREFIX = "player:";

    public MapStats {
        monsterModifiers = List.copyOf(monsterModifiers);
        bossModifiers = List.copyOf(bossModifiers);
        playerModifiers = List.copyOf(playerModifiers);
    }

    public static Tiers tiers() {
        return tiers;
    }

    public static void setTiers(final Tiers value) {
        tiers = value;
    }

    /** 沒有詞綴、也沒有階級加成的實例（管理指令、特別的實例）。 */
    public static MapStats plain(final int tier, final int monsterLevel) {
        return new MapStats(tier, monsterLevel, 0, 0, 0, 0, 0, List.of(), List.of(), List.of());
    }

    /** 沒有詞綴、照階級表的某一階（終局：怪物等級再加 levelBonus）。 */
    public static MapStats forTier(final Tiers table, final int tier, final int levelBonus) {
        final int clamped = table.clamp(tier);
        return new MapStats(clamped, table.level(clamped) + levelBonus, table.quantity(clamped), table.rarity(clamped), 0, 0, 0,
                List.of(), List.of(), List.of());
    }

    /** 用目前的階級表（物品描述用）。 */
    public static MapStats of(final ItemRepository repository, final ItemData data) {
        return of(repository, data, tiers);
    }

    /** 一張地圖物品：階級（物品上寫的，舊地圖照基底）→ 怪物等級與階級加成，再加上品質與詞綴。 */
    public static MapStats of(final ItemRepository repository, final ItemData data, final Tiers table) {
        final ItemBase base = repository.base(data.base());
        final int tier = table.clamp(base == null ? 1 : ItemStats.mapTier(base, data));
        double quantity = data.quality() + table.quantity(tier);
        double rarity = table.rarity(tier);
        double packSize = 0;
        double magicPacks = 0;
        double rarePacks = 0;
        final List<Modifier> monster = new ArrayList<>();
        final List<Modifier> boss = new ArrayList<>();
        final List<Modifier> player = new ArrayList<>();
        for (final RolledAffix rolled : data.affixes()) {
            final Affix affix = repository.affix(rolled.id());
            if (affix == null) {
                continue;
            }
            for (final AffixMod mod : affix.mods()) {
                final Modifier modifier = mod.toModifier(rolled.vars(), "地圖詞綴 " + affix.id());
                final String stat = modifier.stat();
                switch (stat) {
                    case "map.quantity" -> quantity += modifier.value();
                    case "map.rarity" -> rarity += modifier.value();
                    case "map.pack_size" -> packSize += modifier.value();
                    case "map.magic_packs" -> magicPacks += modifier.value();
                    case "map.rare_packs" -> rarePacks += modifier.value();
                    default -> {
                        if (stat.startsWith(MONSTER_PREFIX)) {
                            monster.add(strip(modifier, MONSTER_PREFIX));
                        } else if (stat.startsWith(BOSS_PREFIX)) {
                            boss.add(strip(modifier, BOSS_PREFIX));
                        } else if (stat.startsWith(PLAYER_PREFIX)) {
                            player.add(strip(modifier, PLAYER_PREFIX));
                        }
                    }
                }
            }
        }
        return new MapStats(tier, table.level(tier), quantity, rarity, packSize, magicPacks, rarePacks, monster, boss, player);
    }

    /**
     * 加上星圖天賦的地圖效果（物品數量、稀有度、怪物群大小、魔法／稀有怪物群；開地圖時算一次）。
     * 沒有天賦回傳自己。
     */
    public MapStats plus(final AtlasBonus bonus) {
        if (bonus == null || bonus.isEmpty()) {
            return this;
        }
        return new MapStats(tier, monsterLevel, quantity + bonus.get("map.quantity"), rarity + bonus.get("map.rarity"),
                packSize + bonus.get("map.pack_size"), magicPacks + bonus.get("map.magic_packs"), rarePacks + bonus.get("map.rare_packs"),
                monsterModifiers, bossModifiers, playerModifiers);
    }

    private static Modifier strip(final Modifier modifier, final String prefix) {
        return new Modifier(modifier.stat().substring(prefix.length()), modifier.type(), modifier.value(), modifier.tags(), modifier.source());
    }

    /** 一行摘要（純文字）：物品數量、稀有度、怪物群大小。 */
    public String summary() {
        final StringBuilder text = new StringBuilder();
        text.append("物品數量 +").append(Math.round(quantity)).append("%");
        text.append("　物品稀有度 +").append(Math.round(rarity)).append("%");
        if (packSize != 0) {
            text.append("　怪物群大小 +").append(Math.round(packSize)).append("%");
        }
        return text.toString();
    }
}
