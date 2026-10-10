package tw.exilecore.worldgen;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * 方塊狀態字串（例如 {@code minecraft:oak_log[axis=y]}）↔ 整數代號。0 永遠是空氣。
 * <p>
 * 世界生成的純邏輯（地形、結構、地圖版面）只處理整數，Bukkit 端（{@code CampaignGenerator}、地圖實例）
 * 再用 {@link #state(int)} 換成 BlockData，所以這一層可以直接寫單元測試。
 * 區塊生成是多執行緒的，這裡全部同步處理。
 */
public final class Palette {

    public static final int AIR = 0;

    private static final Map<String, Integer> IDS = new HashMap<>();
    private static final List<String> STATES = new ArrayList<>();

    static {
        STATES.add("minecraft:air");
        IDS.put("minecraft:air", AIR);
    }

    private Palette() {
    }

    /** 取得（或登記）一個方塊狀態的代號。沒寫命名空間時補 minecraft:。 */
    public static synchronized int id(final String state) {
        final String normalized = normalize(state);
        final Integer existing = IDS.get(normalized);
        if (existing != null) {
            return existing;
        }
        final int id = STATES.size();
        STATES.add(normalized);
        IDS.put(normalized, id);
        return id;
    }

    /** 代號對應的方塊狀態字串。 */
    public static synchronized String state(final int id) {
        if (id < 0 || id >= STATES.size()) {
            throw new IllegalArgumentException("沒有這個方塊代號：" + id);
        }
        return STATES.get(id);
    }

    /** 目前登記了幾種方塊狀態（含空氣）。 */
    public static synchronized int size() {
        return STATES.size();
    }

    /** 方塊名稱（去掉命名空間與方括號裡的狀態），例如 oak_log。 */
    public static String blockName(final int id) {
        final String state = state(id);
        final int colon = state.indexOf(':');
        final int bracket = state.indexOf('[');
        return state.substring(colon + 1, bracket < 0 ? state.length() : bracket);
    }

    private static String normalize(final String state) {
        final String trimmed = state.trim().toLowerCase(Locale.ROOT);
        return trimmed.indexOf(':') >= 0 && (trimmed.indexOf('[') < 0 || trimmed.indexOf(':') < trimmed.indexOf('['))
                ? trimmed : "minecraft:" + trimmed;
    }
}
