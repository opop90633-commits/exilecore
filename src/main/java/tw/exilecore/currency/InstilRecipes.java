package tw.exilecore.currency;

import tw.exilecore.core.config.YamlNode;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 2026-10-27 照 PoE2 0.5：注入（蒸餾情緒 → 項鍊）每個核心有固定的配方——三種情緒、照順序（同一種可以重複），
 * 定義在 data/instil.yml（tools/gen_instil_recipes.py 照官方天賦樹匯出產生）。沒有配方的核心不能注入（PoE2 也是）。
 * 這次之前：任何核心都能注入，花 3 顆同一種情緒，哪一種看核心離天賦樹中心多遠。
 * 純邏輯，不依賴 Bukkit。
 */
public final class InstilRecipes {

    /** 一個核心要放幾顆情緒（PoE2：三格）。 */
    public static final int SIZE = 3;

    private static volatile Map<String, List<String>> recipes = Map.of();

    private InstilRecipes() {
    }

    /** 讀 data/instil.yml（沒有這個檔就清空：沒有核心能注入）。 */
    public static void load(final Path dataFolder) throws IOException {
        final Path file = dataFolder.resolve("instil.yml");
        if (!Files.exists(file)) {
            recipes = Map.of();
            return;
        }
        set(parse(YamlNode.load(file)));
    }

    static Map<String, List<String>> parse(final YamlNode root) {
        final Map<String, List<String>> parsed = new LinkedHashMap<>();
        if (!root.has("recipes")) {
            return parsed;
        }
        final YamlNode node = root.node("recipes");
        for (final String id : node.keys()) {
            final List<String> recipe = node.strings(id).stream().map(String::trim).toList();
            if (recipe.size() != SIZE) {
                throw new IllegalArgumentException(node.path() + "." + id + "：配方要剛好 " + SIZE + " 種情緒（照順序）");
            }
            parsed.put(id, recipe);
        }
        return parsed;
    }

    /** 換上一份配方表（測試也用）。 */
    public static void set(final Map<String, List<String>> table) {
        recipes = Map.copyOf(table);
    }

    /** 這個核心的配方（三種情緒的通貨代號，照順序）；不能注入回傳 null。 */
    public static List<String> recipe(final String nodeId) {
        if (nodeId == null) {
            return null;
        }
        final List<String> own = recipes.get(nodeId);
        if (own != null) {
            return own;
        }
        // 2026-10-27：塗油限定的核心（天賦樹上沒有）配方在 data/passives/anoint_only.yml（AnointRecipes）
        try {
            final tw.exilecore.passive.AnointRecipes.Recipe anoint = tw.exilecore.passive.AnointRecipes.get().recipe(nodeId);
            return anoint == null ? null : anoint.emotions();
        } catch (final RuntimeException e) {
            return null;
        }
    }

    /** 這個核心要每一種情緒各幾顆（照配方第一次出現的順序）；不能注入回傳空表。 */
    public static Map<String, Integer> cost(final String nodeId) {
        final List<String> recipe = recipe(nodeId);
        final Map<String, Integer> cost = new LinkedHashMap<>();
        if (recipe != null) {
            for (final String id : recipe) {
                cost.merge(id, 1, Integer::sum);
            }
        }
        return cost;
    }

    /** 有幾個核心能注入。 */
    public static int size() {
        return recipes.size();
    }

    /** 全部的配方（核心代號 → 三種情緒）。 */
    public static Map<String, List<String>> all() {
        return recipes;
    }
}
