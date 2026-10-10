package tw.exilecore.charge;

import tw.exilecore.core.config.YamlNode;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * data/charges.yml：球的種類（銳星、怒星、磐星）。純函式，不依賴 Bukkit。
 * 讀不到檔案或寫錯時保持原本的設定（/ec reload 才不會把球弄丟）。
 */
public final class ChargeConfig {

    public static final String FILE = "charges.yml";

    private Map<String, ChargeType> types = new LinkedHashMap<>();

    /** 讀 charges.yml；先讀進一份新的，全部檢查過才換上去。 */
    public void load(final Path dataFolder) throws IOException {
        final Path file = dataFolder.resolve(FILE);
        if (!Files.exists(file)) {
            return;
        }
        replace(parse(YamlNode.load(file)));
    }

    /** 解析（測試也用）。 */
    public static Map<String, ChargeType> parse(final YamlNode root) {
        final Map<String, ChargeType> fresh = new LinkedHashMap<>();
        for (final String id : root.keys()) {
            fresh.put(id, ChargeType.parse(id, root.node(id)));
        }
        return fresh;
    }

    public void replace(final Map<String, ChargeType> fresh) {
        this.types = new LinkedHashMap<>(fresh);
        ChargeText.register(fresh.values()); // 天賦、輔助寶石、/stats 的說明用這裡的名字
    }

    public Collection<ChargeType> types() {
        return Collections.unmodifiableCollection(types.values());
    }

    public ChargeType type(final String id) {
        return types.get(id);
    }

    /** 這個時機會擲骰的球。 */
    public List<ChargeType> triggeredBy(final ChargeType.Trigger trigger) {
        final List<ChargeType> list = new ArrayList<>();
        for (final ChargeType type : types.values()) {
            if (type.trigger() == trigger) {
                list.add(type);
            }
        }
        return list;
    }
}
