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
        currencies.clear();
        final Path file = dataFolder.resolve("currencies.yml");
        if (!Files.exists(file)) {
            return;
        }
        final YamlNode root = YamlNode.load(file);
        for (final String id : root.keys()) {
            currencies.put(id, CurrencyType.parse(id, root.node(id)));
        }
    }

    public CurrencyType get(final String id) {
        return currencies.get(id);
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
