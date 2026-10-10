package tw.exilecore.salvage;

import tw.exilecore.core.config.YamlNode;
import tw.exilecore.item.ItemBase;
import tw.exilecore.item.ItemData;
import tw.exilecore.item.Rarity;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.EnumMap;
import java.util.Locale;
import java.util.Map;
import java.util.random.RandomGenerator;

/**
 * data/salvage.yml：拆解一件裝備給多少金幣與品質通貨。純資料與計算，不依賴 Bukkit。
 */
public final class SalvageConfig {

    /**
     * 拆解一件裝備的結果。
     *
     * @param gold           金幣
     * @param currencyId     品質通貨代號（沒有為 null）
     * @param currencyAmount 品質通貨數量
     */
    public record Reward(long gold, String currencyId, int currencyAmount) {
    }

    private double goldBase = 4;
    private double goldPerLevel = 1.5;
    private final Map<Rarity, Double> goldRarity = new EnumMap<>(Rarity.class);
    private String weaponCurrency = "whetstone";
    private String armourCurrency = "armour_scrap";
    /** 地圖拆出來的品質通貨（星盤刻刀）。 */
    private String mapCurrency = "chisel";
    /** 地圖的金幣倍率（地圖的物品等級是那一階的怪物等級，跟同等級裝備一樣算，再乘這個）。 */
    private double mapGoldMultiplier = 1.0;
    private int qualityStep = 10;
    private final Map<Rarity, Double> currencyChance = new EnumMap<>(Rarity.class);
    // 寶石與藥劑（只給金幣）
    private double gemBase = 8;
    private double gemPerLevel = 6;
    private double supportBase = 15;
    private double supportPerTier = 25;
    private double flaskBase = 3;
    private double flaskPerLevel = 1.0;
    /** 通貨（2026-10）：參考價值的幾 %（一個至少 1 金幣）。 */
    private double currencyPercent = 10;
    /** 傳奇三換一（unique_recipe）。 */
    private UniqueRecipe.Settings recipe = UniqueRecipe.Settings.defaults();

    public SalvageConfig() {
        defaults();
    }

    private void defaults() {
        goldBase = 4;
        goldPerLevel = 1.5;
        goldRarity.put(Rarity.NORMAL, 1.0);
        goldRarity.put(Rarity.MAGIC, 2.0);
        goldRarity.put(Rarity.RARE, 4.0);
        goldRarity.put(Rarity.UNIQUE, 10.0);
        weaponCurrency = "whetstone";
        armourCurrency = "armour_scrap";
        mapCurrency = "chisel";
        mapGoldMultiplier = 1.0;
        qualityStep = 10;
        currencyChance.put(Rarity.NORMAL, 0.1);
        currencyChance.put(Rarity.MAGIC, 0.25);
        currencyChance.put(Rarity.RARE, 0.5);
        currencyChance.put(Rarity.UNIQUE, 1.0);
        gemBase = 8;
        gemPerLevel = 6;
        supportBase = 15;
        supportPerTier = 25;
        flaskBase = 3;
        flaskPerLevel = 1.0;
        recipe = UniqueRecipe.Settings.defaults();
    }

    public void load(final Path dataFolder) throws IOException {
        final Path file = dataFolder.resolve("salvage.yml");
        if (!Files.exists(file)) {
            defaults();
            return;
        }
        final YamlNode root = YamlNode.load(file);
        // 傳奇三換一先讀：寫錯直接丟出去、原本的設定都不動（關掉的不會因為讀到一半出錯又變回開著）
        final UniqueRecipe.Settings parsedRecipe = root.has("unique_recipe")
                ? UniqueRecipe.Settings.parse(root.node("unique_recipe")) : UniqueRecipe.Settings.defaults();
        defaults();
        final YamlNode gold = root.node("gold");
        goldBase = Math.max(0, gold.decimal("base", goldBase));
        goldPerLevel = Math.max(0, gold.decimal("per_ilvl", goldPerLevel));
        readRarityMap(gold.node("rarity"), goldRarity);
        final YamlNode currency = root.node("quality_currency");
        weaponCurrency = currency.string("weapon", weaponCurrency);
        armourCurrency = currency.string("armour", armourCurrency);
        mapCurrency = currency.string("map", mapCurrency);
        qualityStep = Math.max(1, currency.integer("quality_step", qualityStep));
        readRarityMap(currency.node("chance"), currencyChance);
        mapGoldMultiplier = Math.max(0, root.node("maps").decimal("gold_multiplier", mapGoldMultiplier));
        final YamlNode gems = root.node("gems");
        gemBase = Math.max(0, gems.decimal("base", gemBase));
        gemPerLevel = Math.max(0, gems.decimal("per_level", gemPerLevel));
        supportBase = Math.max(0, gems.decimal("support_base", supportBase));
        supportPerTier = Math.max(0, gems.decimal("support_per_tier", supportPerTier));
        final YamlNode flasks = root.node("flasks");
        flaskBase = Math.max(0, flasks.decimal("base", flaskBase));
        flaskPerLevel = Math.max(0, flasks.decimal("per_level", flaskPerLevel));
        currencyPercent = Math.max(0, root.node("currency").decimal("percent", 10));
        recipe = parsedRecipe;
    }

    /** 傳奇三換一的設定（salvage.yml 的 unique_recipe）。 */
    public UniqueRecipe.Settings recipe() {
        return recipe;
    }

    /**
     * 一顆寶石拆解可得的金幣：技能寶石（含未切割）照寶石等級，輔助寶石（含未切割）照階級。
     *
     * @param support 是不是輔助寶石（含未切割的輔助）
     * @param level   技能寶石的等級，或輔助寶石的階級
     */
    public long goldForGem(final boolean support, final int level) {
        return Math.round(support ? supportBase + supportPerTier * Math.max(1, level) : gemBase + gemPerLevel * Math.max(1, level));
    }

    /** 一疊通貨拆解可得的金幣（2026-10）：value = 一個的參考價值（金幣），拿它的 percent%，一個至少 1 金幣。 */
    public long goldForCurrency(final long value, final int amount) {
        return Math.max(1, Math.round(Math.max(0, value) * currencyPercent / 100.0)) * Math.max(0, amount);
    }

    /** 一瓶藥劑拆解可得的金幣（照基底的需求等級）。 */
    public long goldForFlask(final int level) {
        return Math.round(flaskBase + flaskPerLevel * Math.max(1, level));
    }

    private static void readRarityMap(final YamlNode node, final Map<Rarity, Double> target) {
        for (final String key : node.keys()) {
            final Rarity rarity = Rarity.parse(key.toUpperCase(Locale.ROOT), null);
            if (rarity == null) {
                throw new IllegalArgumentException(node.path() + "：不認得的稀有度 " + key);
            }
            target.put(rarity, Math.max(0, node.decimal(key, 0)));
        }
    }

    /** 拆解可得的金幣（固定值）。 */
    public long gold(final ItemData data) {
        return gold(null, data);
    }

    /** 拆解可得的金幣；地圖（base 是地圖）再乘地圖的倍率。 */
    /** 裝備拆出來的金幣怎麼算（掉落過濾的換算；沒設定、算不出來就用下面 salvage.yml 的 gold）。 */
    private java.util.function.ToLongBiFunction<Rarity, Integer> equipmentGold;

    public void equipmentGold(final java.util.function.ToLongBiFunction<Rarity, Integer> function) {
        this.equipmentGold = function;
    }

    public long gold(final ItemBase base, final ItemData data) {
        if (data.starter()) {
            return 0; // 新手裝備：建角色、拆掉、刪角色不能拿來刷金幣
        }
        final double map = base != null && base.isMap() ? mapGoldMultiplier : 1.0;
        if (equipmentGold != null) {
            // 2026-10：跟被過濾掉換的金幣一樣（loot.yml 的 gold_value）
            final long value = equipmentGold.applyAsLong(data.rarity(), data.ilvl());
            if (value > 0) {
                return Math.round(value * map);
            }
        }
        return Math.round((goldBase + goldPerLevel * data.ilvl()) * goldRarity.getOrDefault(data.rarity(), 1.0) * map);
    }

    /** 這件拆出來的是哪種品質通貨（飾品沒有；地圖是星盤刻刀）。 */
    public String currencyFor(final ItemBase base) {
        if (base == null) {
            return null;
        }
        if (base.isMap()) {
            return mapCurrency;
        }
        if (base.isWeapon()) {
            return weaponCurrency;
        }
        if (base.isArmour()) {
            return armourCurrency;
        }
        return null;
    }

    /** 品質給的保底數量。 */
    public int guaranteedCurrency(final ItemData data) {
        return Math.max(0, data.quality()) / qualityStep;
    }

    public double currencyChance(final Rarity rarity) {
        return currencyChance.getOrDefault(rarity, 0.0);
    }

    /** 實際拆解（品質通貨的機率在這裡擲）。 */
    public Reward reward(final ItemBase base, final ItemData data, final RandomGenerator random) {
        if (data.starter()) {
            return new Reward(0, null, 0);
        }
        final String currency = currencyFor(base);
        int amount = 0;
        if (currency != null) {
            amount = guaranteedCurrency(data) + (random.nextDouble() < currencyChance(data.rarity()) ? 1 : 0);
        }
        return new Reward(gold(base, data), amount > 0 ? currency : null, amount);
    }
}
