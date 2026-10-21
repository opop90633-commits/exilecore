package tw.exilecore.item;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.Map;
import java.util.Set;

/**
 * 一般裝備、地圖、藥劑、護符、珠寶的圖（2026-10-21，{@code web/base_art.txt}，tools/gen_base_art.py 產生）：
 * 每個基底一張 16×16（格式跟 {@link UniqueArt} 一樣；弓、弩另外有拉弓的幾張）。
 * 物品上用 custom_model_data 的字串 0 {@code base:基底代號} 選到它（字串 1 是魔法、稀有的外框 {@code rarity:…}），
 * 沒套資源包的玩家看到的還是原本的物品。有專屬外觀的傳奇照舊用 {@code unique:代號}。純資料，不依賴 Bukkit。
 */
public final class BaseArt {

    public static final String RESOURCE = "/web/base_art.txt";
    /** 物品上 custom_model_data 字串的前綴。 */
    public static final String KEY_PREFIX = "base:";

    private static volatile Map<String, UniqueArt.Sprite> cached;

    private BaseArt() {
    }

    /** 內建的那份（讀不到、格式不對就是空的：照舊用原版物品）。 */
    public static Map<String, UniqueArt.Sprite> sprites() {
        Map<String, UniqueArt.Sprite> result = cached;
        if (result == null) {
            try (InputStream in = BaseArt.class.getResourceAsStream(RESOURCE)) {
                result = in == null ? Map.of() : UniqueArt.parse(new String(in.readAllBytes(), StandardCharsets.UTF_8));
            } catch (final IOException | IllegalArgumentException e) {
                result = Map.of();
            }
            cached = result;
        }
        return result;
    }

    /** 有圖的基底代號（不含拉弓的那幾張）。 */
    public static Set<String> ids() {
        return sprites().keySet().stream().filter(id -> !UniqueArt.isFrame(id)).collect(java.util.stream.Collectors.toUnmodifiableSet());
    }

    /** 這個基底有沒有圖。 */
    public static boolean has(final String baseId) {
        return baseId != null && sprites().containsKey(baseId) && !UniqueArt.isFrame(baseId);
    }

    /** 物品上 custom_model_data 的字串。 */
    public static String modelKey(final String baseId) {
        return KEY_PREFIX + baseId;
    }
}
