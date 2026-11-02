package tw.exilecore.client.core;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * 跟星骸伺服器的插件（tw.exilecore.clientmod.ClientModProtocol）同一套封包格式。純 Java（測試用）。
 * 封包內容就是下面這些位元組（前面沒有長度）：hello 是 UTF-8 JSON、key 是 3 個位元組、hud 是 UTF-8 JSON、fx 是二進位（{@link FxCodec}）。
 *
 * <p>協定 2（模組 1.1，2026-11-02）：招呼多了 {@code effects}（模組自己畫特效，伺服器就不送那些粒子）。我們送 2；伺服器回兩邊都會的最高版。
 * 舊的伺服器（只會 1）看到 2 會當成沒裝模組、回 1——這時改用 1 再打一次招呼（{@link #fallback}），HUD 照常、特效照舊用粒子。
 */
public final class Protocol {

    public static final int PROTOCOL = 2;
    /** 還能用的最舊的伺服器協定。 */
    public static final int MIN_PROTOCOL = 1;
    public static final String NAMESPACE = "exilecore";
    public static final String HELLO = "hello";
    public static final String KEY = "key";
    public static final String HUD = "hud";
    public static final String FX = "fx";
    /** 2026-11-02（1.4）：提示列（S→C，UTF-8 JSON，{@link Notices#parse}）。 */
    public static final String NOTICE = "notice";
    /** 2026-11-02（1.4）：側邊面板（S→C，UTF-8 JSON，{@link Panels#parse}）。 */
    public static final String PANELS = "panels";
    /**
     * 2026-11-02（1.4）招呼的功能旗標（只多欄位、協定不升）：模組說支援，伺服器才停掉原版那一條。
     * notice = 提示列（動作列不寫提示）、panels = 側邊面板（不建記分板）、res = 魔力球旁的戰鬥資源（不掛 Boss 血條）、
     * status = 生命球旁的異常與守護（動作列不寫）、sides = 光環、召喚物、撿到的東西（動作列不接快捷欄兩側的字）。
     */
    public static final String FLAG_NOTICE = "notice";
    public static final String FLAG_PANELS = "panels";
    public static final String FLAG_RES = "res";
    public static final String FLAG_STATUS = "status";
    public static final String FLAG_SIDES = "sides";
    /** 伺服器的按鍵格數（左鍵、右鍵、潛行＋左鍵、潛行＋右鍵、數字鍵格 6 個）。 */
    public static final int SLOTS = 10;
    /** 滑鼠鍵那幾格。 */
    public static final int MOUSE_SLOTS = 4;

    private Protocol() {
    }

    /** 伺服器回的招呼。 */
    public record ServerHello(int protocol, String latestModVersion, String manifestUrl, boolean effects) {

        public ServerHello(final int protocol, final String latestModVersion, final String manifestUrl) {
            this(protocol, latestModVersion, manifestUrl, false);
        }
    }

    /** 協定 1 的招呼（1.0 的格式；舊伺服器用）。 */
    public static byte[] hello(final String modVersion, final boolean hud, final boolean hearts) {
        return hello(1, modVersion, hud, hearts, false);
    }

    /**
     * 招呼。
     *
     * @param effects 模組自己畫特效（協定 2 才寫）
     */
    public static byte[] hello(final int protocol, final String modVersion, final boolean hud, final boolean hearts, final boolean effects) {
        return hello(protocol, modVersion, hud, hearts, effects, false);
    }

    /**
     * 招呼（1.3，2026-11-02）：orbs = 生命、魔力畫成快捷欄兩側的球（伺服器就不在動作列寫生命魔力數字與等級、經驗條改顯示角色經驗、
     * 快捷欄兩側的字往外移）。只多一個欄位、協定不升；舊伺服器不認得就忽略（照舊顯示，只是會跟球重複）。沒有球不寫（跟 1.2 一樣）。
     */
    public static byte[] hello(final int protocol, final String modVersion, final boolean hud, final boolean hearts, final boolean effects,
                               final boolean orbs) {
        return hello(protocol, modVersion, hud, hearts, effects, orbs, java.util.Set.of());
    }

    /**
     * 招呼（1.4，2026-11-02）：flags 是支援的功能（{@link #FLAG_NOTICE}…），每個寫成 {@code "名稱":true}；沒有的不寫。
     * 舊伺服器不認得就忽略（照原版通道，只是會跟模組畫的重複）。
     */
    public static byte[] hello(final int protocol, final String modVersion, final boolean hud, final boolean hearts, final boolean effects,
                               final boolean orbs, final java.util.Set<String> flags) {
        final JsonObject root = new JsonObject();
        root.addProperty("protocol", protocol);
        root.addProperty("modVersion", modVersion);
        root.addProperty("hud", hud);
        root.addProperty("hearts", hearts);
        if (protocol >= 2) {
            root.addProperty("effects", effects);
        }
        if (orbs) {
            root.addProperty("orbs", true);
        }
        for (final String flag : new java.util.TreeSet<>(flags)) {
            root.addProperty(flag, true);
        }
        return root.toString().getBytes(StandardCharsets.UTF_8);
    }

    /** 伺服器的協定我們能不能用。 */
    public static boolean supported(final int serverProtocol) {
        return serverProtocol >= MIN_PROTOCOL && serverProtocol <= PROTOCOL;
    }

    /**
     * 伺服器回的版本比我們這次送的舊（舊伺服器不認得我們的版本、把我們當成沒裝）：要改用哪一版重打招呼；不用重打回傳 0。
     */
    public static int fallback(final int sent, final int serverProtocol) {
        return supported(serverProtocol) && serverProtocol < sent ? serverProtocol : 0;
    }

    /** 讀伺服器的招呼；格式不對回傳 null。 */
    public static ServerHello serverHello(final byte[] data) {
        try {
            final JsonObject root = JsonParser.parseString(new String(data, StandardCharsets.UTF_8)).getAsJsonObject();
            final int protocol = root.get("protocol").getAsInt();
            return new ServerHello(protocol,
                    root.has("latestModVersion") ? root.get("latestModVersion").getAsString() : "",
                    root.has("manifestUrl") ? root.get("manifestUrl").getAsString() : "",
                    protocol >= 2 && root.has("effects") && root.get("effects").getAsBoolean());
        } catch (final RuntimeException e) {
            return null;
        }
    }

    public static byte[] key(final int slot, final boolean pressed) {
        return new byte[] {1, (byte) slot, (byte) (pressed ? 1 : 0)};
    }

    /** 比較版本（跟插件一樣的規則）：負數 a 比較舊、0 一樣、正數 a 比較新。 */
    public static int compareVersions(final String a, final String b) {
        final String[] left = core(a);
        final String[] right = core(b);
        final List<Integer> x = numbers(left[0]);
        final List<Integer> y = numbers(right[0]);
        for (int i = 0; i < Math.max(x.size(), y.size()); i++) {
            final int p = i < x.size() ? x.get(i) : 0;
            final int q = i < y.size() ? y.get(i) : 0;
            if (p != q) {
                return Integer.compare(p, q);
            }
        }
        final boolean preA = !left[1].isEmpty();
        final boolean preB = !right[1].isEmpty();
        if (preA != preB) {
            return preA ? -1 : 1;
        }
        return left[1].compareTo(right[1]);
    }

    private static String[] core(final String version) {
        String text = version == null ? "" : version.trim().toLowerCase(Locale.ROOT);
        if (text.startsWith("v")) {
            text = text.substring(1);
        }
        final int plus = text.indexOf('+');
        if (plus >= 0) {
            text = text.substring(0, plus);
        }
        final int dash = text.indexOf('-');
        return dash < 0 ? new String[] {text, ""} : new String[] {text.substring(0, dash), text.substring(dash + 1)};
    }

    private static List<Integer> numbers(final String text) {
        final List<Integer> out = new ArrayList<>();
        for (final String part : text.split("\\.")) {
            int value = 0;
            for (int i = 0; i < part.length() && Character.isDigit(part.charAt(i)) && i < 9; i++) {
                value = value * 10 + (part.charAt(i) - '0');
            }
            out.add(value);
        }
        while (out.size() > 1 && out.getLast() == 0) {
            out.removeLast();
        }
        return out;
    }
}
