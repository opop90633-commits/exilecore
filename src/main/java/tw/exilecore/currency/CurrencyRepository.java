package tw.exilecore.currency;

import tw.exilecore.core.config.YamlNode;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Collection;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;

/** data/currencies.yml 的載入與查詢。 */
public final class CurrencyRepository {

    private final Map<String, CurrencyType> currencies = new LinkedHashMap<>();

    public void load(final Path dataFolder) throws IOException {
        final Path file = dataFolder.resolve("currencies.yml");
        if (!Files.exists(file)) {
            currencies.clear();
            return;
        }
        // 2026-10-18：先解析到新的表，全部成功才換上（原本先清空，寫錯一個字 /ec reload 通貨表就剩一半）
        final Map<String, CurrencyType> parsed = new LinkedHashMap<>();
        final YamlNode root = YamlNode.load(file);
        for (final String id : root.keys()) {
            parsed.put(id, CurrencyType.parse(id, root.node(id)));
        }
        currencies.clear();
        currencies.putAll(parsed);
        GemQuality.load(dataFolder); // 2026-10-26：技能寶石各自的品質效果（琢星稜鏡）
        InstilRecipes.load(dataFolder); // 2026-10-27 照 PoE2：注入每個核心固定的情緒配方
    }

    public CurrencyType get(final String id) {
        return currencies.get(resolve(id));
    }

    /**
     * 2026-10-27 照站長：拿掉的通貨換成哪一種（舊代號 → 新代號）。物品、通貨袋、倉庫裡的舊通貨讀進來就當成新的那種
     * （星紋蝕刻器已經併進礪鋒石，效果一樣）。
     */
    public static final java.util.Map<String, String> ALIASES = java.util.Map.of("arcanist_etcher", "whetstone");

    /** 舊代號換成現在的代號（不是拿掉的就原樣回傳）。 */
    public static String resolve(final String id) {
        return id == null ? null : ALIASES.getOrDefault(id, id);
    }

    public Collection<CurrencyType> all() {
        return Collections.unmodifiableCollection(currencies.values());
    }

    public int size() {
        return currencies.size();
    }

    /** 第一種做某個操作的通貨（例如鑑定卷軸 = IDENTIFY），讓程式不用寫死代號。 */
    public Optional<CurrencyType> firstWith(final CurrencyOperation operation) {
        for (final CurrencyType type : currencies.values()) {
            if (type.operation() == operation) {
                return Optional.of(type);
            }
        }
        return Optional.empty();
    }
}
