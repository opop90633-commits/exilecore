package tw.exilecore.passive;

import tw.exilecore.core.config.YamlNode;

import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.Reader;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 2026-10-27 照 PoE2 0.5：塗油限定核心的注入配方（data/passives/anoint_only.yml，tools/gen_poe2_tree.py 產生，跟著插件 jar，不是可調的設定檔）。
 * 每個核心是三種蒸餾情緒，照原作寫的順序（原作的配方有順序：有兩對核心用的是同樣三種情緒、只差順序）。
 * 核心本身（效果、說明）在 tree.json 的 anoint（{@link PassiveTree#anointable()}）；這裡只有配方，給注入（通貨那邊）接。
 * 純資料，不依賴 Bukkit。
 */
public final class AnointRecipes {

    private static final String RESOURCE = "/data/passives/anoint_only.yml";

    /** 一個塗油限定核心的配方：核心代號（tree.json 的 id）、名稱、三種情緒的代號（照順序）。 */
    public record Recipe(String nodeId, String name, List<String> emotions) {
        public Recipe {
            emotions = List.copyOf(emotions);
        }
    }

    private static volatile AnointRecipes loaded;

    private final Map<String, Recipe> recipes;
    private final Map<String, String> emotionNames;

    private AnointRecipes(final Map<String, Recipe> recipes, final Map<String, String> emotionNames) {
        this.recipes = recipes;
        this.emotionNames = emotionNames;
    }

    /** 插件 jar 裡的那一份（第一次用時讀）。 */
    public static AnointRecipes get() {
        AnointRecipes current = loaded;
        if (current == null) {
            try (InputStream in = AnointRecipes.class.getResourceAsStream(RESOURCE)) {
                if (in == null) {
                    throw new IllegalStateException("找不到 " + RESOURCE);
                }
                current = load(new InputStreamReader(in, StandardCharsets.UTF_8));
            } catch (final IOException e) {
                throw new UncheckedIOException(e);
            }
            loaded = current;
        }
        return current;
    }

    public static AnointRecipes load(final Reader reader) {
        final YamlNode root = YamlNode.load("anoint_only.yml", reader);
        final Map<String, String> names = new LinkedHashMap<>();
        for (final String key : root.node("emotions").keys()) {
            names.put(key, root.node("emotions").node(key).string("name", key));
        }
        final Map<String, Recipe> recipes = new LinkedHashMap<>();
        for (final String id : root.node("notables").keys()) {
            final YamlNode node = root.node("notables").node(id);
            final List<String> emotions = node.strings("recipe");
            if (emotions.size() != 3) {
                throw new IllegalArgumentException("anoint_only.yml：" + id + " 的配方要剛好三種情緒");
            }
            for (final String emotion : emotions) {
                if (!names.containsKey(emotion)) {
                    throw new IllegalArgumentException("anoint_only.yml：" + id + " 的情緒 " + emotion + " 不在 emotions");
                }
            }
            recipes.put(id, new Recipe(id, node.string("name", id), emotions));
        }
        return new AnointRecipes(java.util.Collections.unmodifiableMap(recipes), java.util.Collections.unmodifiableMap(names));
    }

    /** 這個核心的配方；不是塗油限定的核心回傳 null。 */
    public Recipe recipe(final String nodeId) {
        return recipes.get(nodeId);
    }

    /** 全部的配方（核心代號 → 配方）。 */
    public Map<String, Recipe> all() {
        return recipes;
    }

    /** 情緒代號 → 名稱（配方用到的全部情緒）。 */
    public Map<String, String> emotionNames() {
        return emotionNames;
    }
}
