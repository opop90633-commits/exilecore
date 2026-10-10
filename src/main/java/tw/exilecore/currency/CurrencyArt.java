package tw.exilecore.currency;

import tw.exilecore.item.UniqueArt;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.Map;

/**
 * 通貨的像素圖（web/currency_art.txt，格式同 unique_art.txt；由 tools/gen_currency_art.py 產生）。
 * 介面資源包用它產生每一種通貨的貼圖與物品模型（currencies.yml 的 model: exilecore:currency/代號）。
 */
public final class CurrencyArt {

    public static final String RESOURCE = "/web/currency_art.txt";
    /** currencies.yml 裡 model 的前綴。 */
    public static final String MODEL_PREFIX = "exilecore:currency/";
    /** 物品上 custom_model_data 字串的前綴。 */
    public static final String KEY_PREFIX = "currency:";

    private static volatile Map<String, UniqueArt.Sprite> cached;

    private CurrencyArt() {
    }

    /** 內建的那份（讀不到就是空的）：代號 → 圖。 */
    public static Map<String, UniqueArt.Sprite> sprites() {
        Map<String, UniqueArt.Sprite> result = cached;
        if (result == null) {
            final Map<String, UniqueArt.Sprite> all = new java.util.LinkedHashMap<>(load(RESOURCE));
            all.putAll(load(tw.exilecore.skill.SkillArt.RESOURCE)); // 技能圖示（2026-10）跟通貨同一套
            result = java.util.Collections.unmodifiableMap(all);
            cached = result;
        }
        return result;
    }

    private static Map<String, UniqueArt.Sprite> load(final String resource) {
        try (InputStream in = CurrencyArt.class.getResourceAsStream(resource)) {
            return in == null ? Map.of() : UniqueArt.parse(new String(in.readAllBytes(), StandardCharsets.UTF_8));
        } catch (final IOException | IllegalArgumentException e) {
            return Map.of();
        }
    }

    public static boolean has(final String currencyId) {
        return currencyId != null && sprites().containsKey(currencyId);
    }

    /** 這個 model 字串是不是我們的通貨模型（exilecore:currency/代號）；回傳代號，不是回傳 null。 */
    public static String idOf(final String model) {
        return model != null && model.startsWith(MODEL_PREFIX) ? model.substring(MODEL_PREFIX.length()) : null;
    }

    /**
     * 這種通貨要畫哪一張圖（回傳圖的代號；不用我們的圖回傳 null）。currencies.yml 的 model 寫 exilecore:currency/代號 就用那張
     * （寫別的模型＝伺服器自己有美術，不管）；沒寫 model 的話，有跟自己同代號的圖就用那張。
     */
    public static String artOf(final CurrencyType type) {
        if (type == null) {
            return null;
        }
        if (type.model() == null || type.model().isBlank()) {
            return has(type.id()) ? type.id() : null;
        }
        final String id = idOf(type.model());
        return id != null && has(id) ? id : null;
    }

    /**
     * 通貨物品上 custom_model_data 的字串（資源包用它挑貼圖）：{@code currency:圖的代號}。
     * 掛在物品上的是這個標記、不是 item_model，所以沒套資源包的玩家看到的是原本的物品（不會變成紫黑方塊），
     * 也不用管 config.yml 的 resource-pack.enabled（跟傳奇的專屬外觀同一個做法）。
     */
    public static String modelKey(final String artId) {
        return KEY_PREFIX + artId;
    }
}
