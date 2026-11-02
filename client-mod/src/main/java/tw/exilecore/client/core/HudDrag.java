package tw.exilecore.client.core;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 2026-11-02 照站長（C 組）：可以在設定畫面拖動位置的 HUD 元件（通用的註冊機制）。純 Java（測試用）。
 * <p>
 * 每個畫 HUD 的地方註冊自己（{@link #register}）：代號、顯示的名字、「照原本的算法這一幀畫在哪」（{@link Bounds}，沒拖過的位置）。
 * 玩家拖過的元件存一個偏移（GUI 像素，{@link ModConfig#layout}）；畫的時候整個元件平移這個偏移（{@link #offset}），
 * 所以各 renderer 原本怎麼算位置（快捷欄兩側、球的外側、技能列在快捷欄正上方…）都不用改，只要在畫之前、之後各叫一次平移。
 * <p>
 * 別的元件要排在某個元件下面（例如 B 組的地圖資訊框接在小地圖下面）用 {@link #bottomOf}：那個元件這一幀的下緣（含拖過的偏移）。
 */
public final class HudDrag {

    /** 一個矩形（GUI 像素）。 */
    public record Rect(int x, int y, int width, int height) {
        public int right() {
            return x + width;
        }

        public int bottom() {
            return y + height;
        }

        public boolean contains(final double px, final double py) {
            return px >= x && py >= y && px < x + width && py < y + height;
        }

        public Rect moved(final int dx, final int dy) {
            return new Rect(x + dx, y + dy, width, height);
        }
    }

    /** 照原本的算法，這個元件在這個畫面大小下畫在哪（沒有要畫 = null；編輯畫面還是要一個框，所以最好都給）。 */
    @FunctionalInterface
    public interface Bounds {
        Rect at(int guiWidth, int guiHeight);
    }

    /**
     * 一個元件。home = 拖過以後偏移是從哪裡算起（2026-11-02：側邊面板一個一個拖——沒拖過時排在面板群裡、位置跟著別的面板變；
     * 拖過以後改從固定的 home 算，才不會因為上面的面板出現、消失跟著跑）；null = 從原本的位置算。
     */
    public record Element(String id, String label, Bounds bounds, Bounds home) {
        public Element(final String id, final String label, final Bounds bounds) {
            this(id, label, bounds, null);
        }
    }

    private static final Map<String, Element> ELEMENTS = new LinkedHashMap<>();
    private static ModConfig config = new ModConfig();

    private HudDrag() {
    }

    /** 用哪一份設定（偏移存在裡面）。 */
    public static void use(final ModConfig modConfig) {
        config = modConfig == null ? new ModConfig() : modConfig;
    }

    /** 註冊（同一個代號再註冊一次會換掉）。 */
    public static void register(final String id, final String label, final Bounds bounds) {
        ELEMENTS.put(id, new Element(id, label, bounds));
    }

    /**
     * 2026-11-02 照站長：註冊一個「拖出來就脫離原本那一群」的元件（側邊面板的每一個）：沒拖過時在 bounds（排在群裡的位置），
     * 拖過以後在 home ＋ 偏移。
     */
    public static void register(final String id, final String label, final Bounds bounds, final Bounds home) {
        ELEMENTS.put(id, new Element(id, label, bounds, home));
    }

    /** 拖過了（有偏移）。 */
    public static boolean moved(final String id) {
        final int[] offset = offset(id);
        return offset[0] != 0 || offset[1] != 0;
    }

    /** 偏移是從哪裡算起的（沒拖過的位置；有 home 的元件是 home）；沒註冊、不畫 null。 */
    public static Rect base(final String id, final int guiWidth, final int guiHeight) {
        final Element element = ELEMENTS.get(id);
        if (element == null) {
            return null;
        }
        final Rect own = element.bounds().at(guiWidth, guiHeight);
        if (element.home() == null) {
            return own;
        }
        final Rect home = element.home().at(guiWidth, guiHeight);
        return home == null ? own : own == null ? home : new Rect(home.x(), home.y(), own.width(), own.height());
    }

    /**
     * 開始拖的時候偏移從多少開始（純函式，測試用）：一般是現在的偏移；有 home、還沒拖過的元件是「現在的位置 − home」（不會一拖就跳到 home）。
     */
    public static int[] dragStart(final String id, final int guiWidth, final int guiHeight) {
        final Element element = ELEMENTS.get(id);
        if (element == null || element.home() == null || moved(id)) {
            return offset(id).clone();
        }
        final Rect own = element.bounds().at(guiWidth, guiHeight);
        final Rect home = element.home().at(guiWidth, guiHeight);
        if (own == null || home == null) {
            return new int[] {0, 0};
        }
        return new int[] {own.x() - home.x(), own.y() - home.y()};
    }

    public static List<Element> elements() {
        return Collections.unmodifiableList(new ArrayList<>(ELEMENTS.values()));
    }

    public static Element element(final String id) {
        return ELEMENTS.get(id);
    }

    /** 拖過的偏移 {dx, dy}（沒拖過 {0, 0}）。 */
    public static int[] offset(final String id) {
        final int[] value = config.layout == null ? null : config.layout.get(id);
        return value == null || value.length != 2 ? new int[] {0, 0} : value;
    }

    public static void setOffset(final String id, final int dx, final int dy) {
        if (config.layout == null) {
            config.layout = new LinkedHashMap<>();
        }
        if (dx == 0 && dy == 0) {
            config.layout.remove(id);
        } else {
            config.layout.put(id, new int[] {dx, dy});
        }
    }

    public static void reset(final String id) {
        if (config.layout != null) {
            config.layout.remove(id);
        }
    }

    public static void resetAll() {
        if (config.layout != null) {
            config.layout.clear();
        }
    }

    /** 這一幀畫在哪（原本的位置＋拖過的偏移）；沒註冊或不畫 null。 */
    public static Rect bounds(final String id, final int guiWidth, final int guiHeight) {
        final Element element = ELEMENTS.get(id);
        if (element == null) {
            return null;
        }
        if (!moved(id)) {
            return element.bounds().at(guiWidth, guiHeight);
        }
        final Rect base = base(id, guiWidth, guiHeight);
        if (base == null) {
            return null;
        }
        final int[] offset = offset(id);
        return base.moved(offset[0], offset[1]);
    }

    private static int frameWidth = 640;
    private static int frameHeight = 360;

    /** 每一幀畫 HUD 之前記下畫面大小（{@link #bottomOf(String)} 用）。 */
    public static void frame(final int guiWidth, final int guiHeight) {
        frameWidth = guiWidth;
        frameHeight = guiHeight;
    }

    /** 這一幀這個元件的下緣（GUI 像素）；沒註冊、不畫回傳 0（就是畫面最上面）。 */
    public static int bottomOf(final String id) {
        return bottomOf(id, frameWidth, frameHeight, 0);
    }

    /** 這個元件的下緣（GUI 像素）；沒註冊、這一幀不畫回傳 fallback。B 組的地圖資訊框用 {@code bottomOf("minimap", 0)} 接在小地圖下面。 */
    public static int bottomOf(final String id, final int guiWidth, final int guiHeight, final int fallback) {
        final Rect rect = bounds(id, guiWidth, guiHeight);
        return rect == null ? fallback : rect.bottom();
    }

    /**
     * 拖到新的位置：偏移夾住，整個框留在畫面裡（至少留 margin 格看得到）。純函式。
     *
     * @param base 原本的位置（沒偏移）
     */
    public static int[] clampOffset(final Rect base, final int dx, final int dy, final int guiWidth, final int guiHeight) {
        final int x = Math.max(0, Math.min(guiWidth - base.width(), base.x() + dx));
        final int y = Math.max(0, Math.min(guiHeight - base.height(), base.y() + dy));
        return new int[] {x - base.x(), y - base.y()};
    }

    /** 拖的時候吸附到 grid 格（按著 Shift 不吸）。 */
    public static int snap(final int value, final int grid) {
        return grid <= 1 ? value : Math.round(value / (float) grid) * grid;
    }

    /** 清掉註冊（測試用）。 */
    static void clearForTest() {
        ELEMENTS.clear();
    }
}
