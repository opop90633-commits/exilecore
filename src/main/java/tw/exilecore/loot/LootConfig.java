package tw.exilecore.loot;

import tw.exilecore.core.config.YamlNode;
import tw.exilecore.item.Rarity;
import tw.exilecore.monster.DropTable;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Collection;
import java.util.Collections;
import java.util.EnumMap;
import java.util.EnumSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;

/**
 * data/loot.yml：自動撿取、過濾器的預設組合、被過濾的東西換成多少金幣、哪些東西撿到要提示。
 * 純資料，不依賴 Bukkit。
 */
public final class LootConfig {

    public static final String FILE = "loot.yml";

    /**
     * 過濾器的一組預設（選單上的按鈕）。
     *
     * @param hideEquipment  要隱藏的裝備稀有度（傳奇不會在這裡）
     * @param hideCurrencies 要隱藏的通貨代號
     */
    public record Preset(String id, String name, String description, Set<Rarity> hideEquipment, Set<String> hideCurrencies,
                         boolean hideSkillGems, boolean hideSupportGems, LootFilter.FlaskRule flasks, LootFilter.Rules rules) {
        public Preset {
            final EnumSet<Rarity> rarities = EnumSet.noneOf(Rarity.class);
            rarities.addAll(hideEquipment);
            rarities.remove(Rarity.UNIQUE);
            hideEquipment = Collections.unmodifiableSet(rarities);
            hideCurrencies = Collections.unmodifiableSet(new TreeSet<>(hideCurrencies));
            rules = rules == null ? LootFilter.Rules.NONE : rules;
        }

        /** 沒有進階規則的組合。 */
        public Preset(final String id, final String name, final String description, final Set<Rarity> hideEquipment, final Set<String> hideCurrencies,
                      final boolean hideSkillGems, final boolean hideSupportGems, final LootFilter.FlaskRule flasks) {
            this(id, name, description, hideEquipment, hideCurrencies, hideSkillGems, hideSupportGems, flasks, LootFilter.Rules.NONE);
        }
    }

    private boolean autoPickup;
    private final Map<String, Preset> presets = new LinkedHashMap<>();
    private String defaultPreset;
    private final Map<Rarity, Double> equipmentValue = new EnumMap<>(Rarity.class);
    private double currencyFactor;
    private double skillGemValue;
    private double supportGemValue;
    private double flaskValue;
    private final Set<Rarity> notableEquipment = EnumSet.noneOf(Rarity.class);
    private int notableCurrencyWeight;
    /** 不提示的通貨（掉落權重再低也一樣）。 */
    private final Set<String> quietCurrencies = new TreeSet<>();
    private boolean notableGems;

    public LootConfig() {
        reset();
    }

    /** 沒有 loot.yml 時的設定：自動撿、全部顯示。 */
    private void reset() {
        autoPickup = true;
        presets.clear();
        presets.put("loose", new Preset("loose", "寬鬆", "全部顯示", Set.of(), Set.of(), false, false, LootFilter.FlaskRule.ALL));
        defaultPreset = "loose";
        equipmentValue.clear();
        equipmentValue.put(Rarity.NORMAL, 0.15);
        equipmentValue.put(Rarity.MAGIC, 0.3);
        equipmentValue.put(Rarity.RARE, 0.8);
        equipmentValue.put(Rarity.UNIQUE, 3.0);
        currencyFactor = 300;
        skillGemValue = 0.6;
        supportGemValue = 0.6;
        flaskValue = 0.3;
        notableEquipment.clear();
        notableEquipment.add(Rarity.RARE);
        notableEquipment.add(Rarity.UNIQUE);
        notableCurrencyWeight = 1000;
        quietCurrencies.clear();
        quietCurrencies.addAll(List.of("glimmer_orb", "lustre_orb", "whetstone", "armour_scrap"));
        notableGems = true;
    }

    /**
     * 讀 loot.yml。先讀進一份新的設定，全部檢查過才換上去：寫錯時 /ec reload 失敗，但遊戲裡還是用原本的設定
     * （不會變成一半新一半舊，或是預設組合不見了讓每一次擊殺都出錯）。
     */
    public void load(final Path dataFolder) throws IOException {
        final LootConfig fresh = new LootConfig();
        final Path file = dataFolder.resolve(FILE);
        if (Files.exists(file)) {
            fresh.parse(YamlNode.load(file));
        }
        copyFrom(fresh);
    }

    private void copyFrom(final LootConfig other) {
        autoPickup = other.autoPickup;
        presets.clear();
        presets.putAll(other.presets);
        defaultPreset = other.defaultPreset;
        equipmentValue.clear();
        equipmentValue.putAll(other.equipmentValue);
        currencyFactor = other.currencyFactor;
        skillGemValue = other.skillGemValue;
        supportGemValue = other.supportGemValue;
        flaskValue = other.flaskValue;
        notableEquipment.clear();
        notableEquipment.addAll(other.notableEquipment);
        notableCurrencyWeight = other.notableCurrencyWeight;
        quietCurrencies.clear();
        quietCurrencies.addAll(other.quietCurrencies);
        notableGems = other.notableGems;
    }

    /** 讀進這份（剛建立、還是預設值的）設定。 */
    private void parse(final YamlNode root) {
        autoPickup = root.bool("auto_pickup", true);

        final YamlNode filter = root.node("filter");
        final YamlNode presetsNode = filter.node("presets");
        if (!presetsNode.keys().isEmpty()) {
            presets.clear();
            for (final String id : presetsNode.keys()) {
                if (LootFilter.CUSTOM.equals(id)) {
                    throw new IllegalArgumentException(presetsNode.path() + "：「" + LootFilter.CUSTOM + "」是保留字（自己改過的過濾器），換一個代號");
                }
                presets.put(id, parsePreset(id, presetsNode.node(id)));
            }
        }
        defaultPreset = filter.string("default", presets.keySet().iterator().next());
        if (!presets.containsKey(defaultPreset)) {
            throw new IllegalArgumentException(filter.path() + ".default：沒有「" + defaultPreset + "」這組（可用："
                    + String.join("、", presets.keySet()) + "）");
        }

        final YamlNode gold = root.node("gold_value");
        final YamlNode equipment = gold.node("equipment");
        for (final String key : equipment.keys()) {
            equipmentValue.put(rarity(equipment.path(), key), nonNegative(equipment, key));
        }
        currencyFactor = gold.has("currency") ? nonNegative(gold, "currency") : currencyFactor;
        skillGemValue = gold.has("skill_gem") ? nonNegative(gold, "skill_gem") : skillGemValue;
        supportGemValue = gold.has("support_gem") ? nonNegative(gold, "support_gem") : supportGemValue;
        flaskValue = gold.has("flask") ? nonNegative(gold, "flask") : flaskValue;

        final YamlNode notable = root.node("notable");
        if (notable.has("equipment")) {
            notableEquipment.clear();
            for (final String key : notable.strings("equipment")) {
                notableEquipment.add(rarity(notable.path() + ".equipment", key));
            }
        }
        notableCurrencyWeight = notable.integer("currency_weight", notableCurrencyWeight);
        if (notable.has("quiet_currencies")) {
            quietCurrencies.clear();
            for (final String id : notable.strings("quiet_currencies")) {
                if (id != null && !id.isBlank()) {
                    quietCurrencies.add(id.trim());
                }
            }
        }
        notableGems = notable.bool("gems", notableGems);
    }

    private static Preset parsePreset(final String id, final YamlNode node) {
        final EnumSet<Rarity> rarities = EnumSet.noneOf(Rarity.class);
        for (final String key : node.strings("hide_equipment")) {
            final Rarity rarity = rarity(node.path() + ".hide_equipment", key);
            if (rarity == Rarity.UNIQUE) {
                throw new IllegalArgumentException(node.path() + ".hide_equipment：傳奇裝備不能隱藏");
            }
            rarities.add(rarity);
        }
        final Set<String> currencies = new TreeSet<>();
        for (final String key : node.strings("hide_currencies")) {
            if (!key.isBlank()) {
                currencies.add(key.trim());
            }
        }
        final String flaskText = node.string("flasks", "all");
        final LootFilter.FlaskRule flasks = LootFilter.FlaskRule.parse(flaskText, null);
        if (flasks == null) {
            throw new IllegalArgumentException(node.path() + ".flasks：要寫 all、better 或 none，卻是「" + flaskText + "」");
        }
        final LootFilter.Rules rules = new LootFilter.Rules(node.bool("weapons_like_mine", false), node.bool("armour_like_mine", false),
                Math.max(0, node.integer("level_gap", 0)), Math.max(0, node.integer("min_skill_gem_level", 0)),
                Math.max(0, node.integer("min_support_tier", 0)), mapRarity(node.path(), node.string("hide_maps", "none")),
                Math.max(0, Math.min(LootFilter.AFFIX_TIERS[LootFilter.AFFIX_TIERS.length - 1], node.integer("keep_affix_tier", 0))));
        return new Preset(id, node.string("name", id), node.string("description", ""), rarities, currencies,
                node.bool("hide_skill_gems", false), node.bool("hide_support_gems", false), flasks, rules);
    }

    /** hide_maps：none（全部顯示）、normal（隱藏普通地圖）、magic（普通與魔法都隱藏，只撿稀有）。舊的 hide_maps_up_to（地圖階級）已經不用。 */
    static int mapRarity(final String where, final String text) {
        return switch (text == null ? "" : text.trim().toLowerCase(Locale.ROOT)) {
            case "", "none", "off", "false" -> 0;
            case "normal" -> 1;
            case "magic" -> 2;
            case "all" -> 3; // 2026-10-24：全部隱藏（傳奇地圖照撿）
            default -> throw new IllegalArgumentException(where + ".hide_maps：要寫 none、normal（隱藏普通地圖）、magic（普通、魔法地圖都隱藏）或 all（全部隱藏），卻是「"
                    + text + "」");
        };
    }

    private static Rarity rarity(final String where, final String text) {
        for (final Rarity rarity : Rarity.values()) {
            if (rarity.name().equalsIgnoreCase(text.trim())) {
                return rarity;
            }
        }
        throw new IllegalArgumentException(where + "：不認得的稀有度「" + text + "」（normal、magic、rare、unique）");
    }

    private static double nonNegative(final YamlNode node, final String key) {
        final double value = node.decimal(key, 0);
        if (value < 0) {
            throw new IllegalArgumentException(node.path() + "." + key + "：不能是負的");
        }
        return value;
    }

    // ---- 查詢 ----

    public boolean autoPickup() {
        return autoPickup;
    }

    public Collection<Preset> presets() {
        return Collections.unmodifiableCollection(presets.values());
    }

    public Preset preset(final String id) {
        return presets.get(id);
    }

    public String defaultPresetId() {
        return defaultPreset;
    }

    /** 沒設定過過濾器的角色用的。 */
    public LootFilter defaultFilter() {
        final Preset preset = presets.get(defaultPreset);
        return preset == null ? new LootFilter() : LootFilter.of(preset);
    }

    /** 預設組合裡寫到的通貨代號（給模組檢查拼錯）。 */
    public Set<String> mentionedCurrencies() {
        final Set<String> ids = new TreeSet<>();
        for (final Preset preset : presets.values()) {
            ids.addAll(preset.hideCurrencies());
        }
        ids.addAll(quietCurrencies); // notable.quiet_currencies 也檢查（拼錯的話那種通貨撿到又會跳提示）
        return ids;
    }

    /**
     * 被過濾的一件掉落換成多少金幣：同等級怪物平均掉的金幣 × 倍率，四捨五入，至少 1。
     *
     * @param kind           掉落種類
     * @param rarity         裝備的稀有度（其他種類不看）
     * @param currencyWeight 通貨的掉落權重（其他種類不看）
     * @param averageGold    同等級怪物平均掉的金幣
     */
    public long goldValue(final DropTable.Kind kind, final Rarity rarity, final int currencyWeight, final double averageGold) {
        final double factor = switch (kind) {
            case EQUIPMENT -> equipmentValue.getOrDefault(rarity == null ? Rarity.NORMAL : rarity, 0.0);
            case CURRENCY -> currencyWeight <= 0 ? 0 : Math.max(0.05, Math.min(5.0, currencyFactor / currencyWeight));
            case SKILL_GEM -> skillGemValue;
            case SUPPORT_GEM -> supportGemValue;
            case FLASK -> flaskValue;
        };
        return Math.max(1, Math.round(Math.max(0, averageGold) * factor));
    }

    /** 撿到這件要不要提示（右邊側欄的「最近撿到」＋音效）。 */
    public boolean notable(final DropTable.Kind kind, final Rarity rarity, final int currencyWeight) {
        return notable(kind, rarity, currencyWeight, null);
    }

    /** 同上；currencyId 在 notable.quiet_currencies 裡的通貨不提示。 */
    public boolean notable(final DropTable.Kind kind, final Rarity rarity, final int currencyWeight, final String currencyId) {
        return switch (kind) {
            case EQUIPMENT -> rarity != null && notableEquipment.contains(rarity);
            case CURRENCY -> currencyWeight > 0 && currencyWeight <= notableCurrencyWeight
                    && (currencyId == null || !quietCurrencies.contains(currencyId));
            case SKILL_GEM, SUPPORT_GEM -> notableGems;
            case FLASK -> false;
        };
    }

    /** 除錯與說明用：預設組合的名稱清單。 */
    public List<String> presetNames() {
        return presets.values().stream().map(preset -> preset.name() + "（" + preset.id().toLowerCase(Locale.ROOT) + "）").toList();
    }
}
