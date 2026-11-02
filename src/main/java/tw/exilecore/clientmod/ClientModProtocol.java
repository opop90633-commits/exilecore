package tw.exilecore.clientmod;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * 2026-11-02 星骸輔助模組（客戶端 Fabric 模組，選用）跟插件之間的封包格式。純函式，不依賴 Bukkit（測試用）。
 * <p>
 * 全部走 plugin messaging（自訂封包），封包內容就是下面這些位元組，前面沒有長度：
 * <ul>
 *   <li>C→S {@value #HELLO}：UTF-8 JSON {@code {"protocol":2,"modVersion":"1.3.0","hud":true,"hearts":true,"effects":true,"orbs":true}}
 *       （進伺服器、改了 HUD、特效、生命球開關時送；effects 是 2026-11-02 協定 2 加的：模組要自己畫特效；
 *       orbs 是 2026-11-02 模組 1.3 加的（只多欄位，協定不升）：模組把生命、魔力畫成快捷欄兩側的球——動作列不寫生命魔力數字與等級、
 *       經驗條改顯示角色經驗、快捷欄兩側的字往外移；舊模組沒寫 = false，舊插件不認得就忽略）</li>
 *   <li>S→C {@value #HELLO}：UTF-8 JSON {@code {"protocol":2,"latestModVersion":"1.1.0","manifestUrl":"https://…/mod/manifest.json","effects":true}}
 *       ——protocol 是兩邊都會的最高版（{@link #negotiate}：1.0 的模組送 1 就回 1，它只認 1）；effects = 伺服器會送 {@value #FX}</li>
 *   <li>C→S {@value #KEY}：3 個位元組 {@code [版本 1, 按鍵格 0～9, 1 按下／0 放開]}</li>
 *   <li>S→C {@value #HUD}：UTF-8 JSON（{@link HudSnapshot#toJson}）</li>
 *   <li>S→C {@value #FX}：二進位，一個 tick 的特效（{@link FxProtocol}）；只送給協定 2 以上而且 effects:true 的模組</li>
 *   <li>2026-11-02（模組 1.4，協定不升）：招呼多了功能旗標 {@code "notice":true}（模組畫提示列）、{@code "panels":true}（模組畫側邊面板）、
 *       {@code "res":true}（模組在魔力球旁邊畫戰鬥資源）、{@code "status":true}（模組在生命球旁邊畫自己的異常、守護）、
 *       {@code "sides":true}（模組畫光環、召喚物、撿到的東西，動作列不接 HotbarSides）；
 *       沒寫 = 不支援（照原版通道），舊插件不認得就忽略。只有模組說支援，插件才停止原版那一條（動作列、記分板、Boss 血條）。</li>
 *   <li>S→C {@value #NOTICE}：UTF-8 JSON {@code {"v":1,"p":[{"t":字,"c":顏色}],"d":停幾 tick,"lv":等級(0 一般／1 警告)}}（{@link #encodeNotice}）</li>
 *   <li>S→C {@value #PANELS}：UTF-8 JSON（{@link HudPanels#toJson}），有變才送</li>
 * </ul>
 * 不認得的欄位一律忽略；protocol 不在 {@link #MIN_PROTOCOL}–{@link #PROTOCOL} 之間時插件當成沒裝模組（照原本的動作列）。
 */
public final class ClientModProtocol {

    /** 協定版本：改了封包的意思（不是只多欄位）才加一。2（2026-11-02）：模組自己畫特效（effects、{@value #FX}）。 */
    public static final int PROTOCOL = 2;
    /** 還接受的最舊的協定（1.0 的模組）。 */
    public static final int MIN_PROTOCOL = 1;
    public static final String HELLO = "exilecore:hello";
    public static final String KEY = "exilecore:key";
    public static final String HUD = "exilecore:hud";
    public static final String FX = "exilecore:fx";
    /** 2026-11-02（模組 1.4）：提示列（以前是動作列的提示）。 */
    public static final String NOTICE = "exilecore:notice";
    /** 2026-11-02（模組 1.4）：側邊面板（以前是記分板的側欄）。 */
    public static final String PANELS = "exilecore:panels";
    /** 2026-11-02（模組 1.4）招呼的功能旗標。 */
    public static final String FLAG_NOTICE = "notice";
    public static final String FLAG_PANELS = "panels";
    public static final String FLAG_RES = "res";
    public static final String FLAG_STATUS = "status";
    /** 2026-11-02：模組畫快捷欄兩側那些字（光環併進增益列、召喚物與撿到的東西是面板）。 */
    public static final String FLAG_SIDES = "sides";
    /** 招呼裡最多記幾個功能旗標（防亂送）。 */
    static final int MAX_FLAGS = 16;
    /** 這些是招呼原本就有的欄位，不算功能旗標。 */
    private static final java.util.Set<String> BASE_KEYS = java.util.Set.of("protocol", "modVersion", "hud", "hearts", "effects", "orbs");
    /** 按鍵封包的版本（第一個位元組）。 */
    static final int KEY_VERSION = 1;
    /** 客戶端送來的 JSON 最多讀多長（防亂送）。 */
    static final int MAX_HELLO = 4096;

    private ClientModProtocol() {
    }

    /**
     * 客戶端打的招呼。
     *
     * @param hud    模組有沒有畫技能列與增益（有的話動作列不再寫增益倒數）
     * @param hearts 模組有沒有畫生命、護盾（有的話伺服器不用原版的吸收愛心顯示能量護盾）
     * @param effects 模組要自己畫特效（協定 2；舊版模組沒寫 = false）
     * @param orbs   2026-11-02（模組 1.3）：模組把生命、魔力畫成快捷欄兩側的球（hearts 也是 true；舊版模組沒寫 = false）
     * @param flags  2026-11-02（模組 1.4）：其他寫成 true 的功能旗標（{@link #FLAG_NOTICE}…）；舊版模組沒有 = 空的
     */
    public record Hello(int protocol, String modVersion, boolean hud, boolean hearts, boolean effects, boolean orbs,
                        java.util.Set<String> flags) {

        public Hello {
            flags = flags == null ? java.util.Set.of() : java.util.Set.copyOf(flags);
        }

        public Hello(final int protocol, final String modVersion, final boolean hud, final boolean hearts) {
            this(protocol, modVersion, hud, hearts, false);
        }

        public Hello(final int protocol, final String modVersion, final boolean hud, final boolean hearts, final boolean effects) {
            this(protocol, modVersion, hud, hearts, effects, false);
        }

        public Hello(final int protocol, final String modVersion, final boolean hud, final boolean hearts, final boolean effects,
                     final boolean orbs) {
            this(protocol, modVersion, hud, hearts, effects, orbs, java.util.Set.of());
        }

        /** 2026-11-02：模組說它支援這個功能（招呼裡寫了 {@code "名稱":true}）。 */
        public boolean has(final String flag) {
            return flags.contains(flag);
        }
    }

    /** 跟這個協定版本的模組講哪一版（兩邊都會的最高版）；不支援回傳 0。 */
    public static int negotiate(final int client) {
        if (client < MIN_PROTOCOL) {
            return 0;
        }
        return Math.min(client, PROTOCOL);
    }

    /** 一個技能鍵按下或放開。 */
    public record Key(int slot, boolean pressed) {
    }

    /** 讀客戶端的招呼；格式不對回傳 null。 */
    public static Hello decodeHello(final byte[] data) {
        if (data == null || data.length == 0 || data.length > MAX_HELLO) {
            return null;
        }
        try {
            final JsonElement parsed = JsonParser.parseString(new String(data, StandardCharsets.UTF_8));
            if (!parsed.isJsonObject()) {
                return null;
            }
            final JsonObject root = parsed.getAsJsonObject();
            if (!root.has("protocol")) {
                return null;
            }
            final int protocol = root.get("protocol").getAsInt();
            final String version = root.has("modVersion") ? root.get("modVersion").getAsString() : "";
            final boolean hud = !root.has("hud") || root.get("hud").getAsBoolean();
            final boolean hearts = !root.has("hearts") || root.get("hearts").getAsBoolean();
            final boolean effects = protocol >= 2 && root.has("effects") && root.get("effects").getAsBoolean();
            final boolean orbs = hearts && root.has("orbs") && root.get("orbs").getAsBoolean();
            // 2026-11-02（模組 1.4）：其他寫成 true 的欄位當成功能旗標（notice、panels、res、status…）；不是 true 的、太長的不算
            final java.util.Set<String> flags = new java.util.TreeSet<>();
            for (final java.util.Map.Entry<String, JsonElement> entry : root.entrySet()) {
                if (flags.size() >= MAX_FLAGS) {
                    break;
                }
                final JsonElement value = entry.getValue();
                if (!BASE_KEYS.contains(entry.getKey()) && entry.getKey().length() <= 24 && value.isJsonPrimitive()
                        && value.getAsJsonPrimitive().isBoolean() && value.getAsBoolean()) {
                    flags.add(entry.getKey());
                }
            }
            return new Hello(protocol, version.length() > 64 ? version.substring(0, 64) : version, hud, hearts, effects, orbs, flags);
        } catch (final RuntimeException e) {
            return null;
        }
    }

    /** 客戶端的招呼（模組那邊用同樣的格式；測試用）。 */
    public static byte[] encodeHello(final Hello hello) {
        final JsonObject root = new JsonObject();
        root.addProperty("protocol", hello.protocol());
        root.addProperty("modVersion", hello.modVersion());
        root.addProperty("hud", hello.hud());
        root.addProperty("hearts", hello.hearts());
        if (hello.protocol() >= 2) {
            root.addProperty("effects", hello.effects());
        }
        if (hello.orbs()) {
            root.addProperty("orbs", true);
        }
        for (final String flag : new java.util.TreeSet<>(hello.flags())) {
            root.addProperty(flag, true);
        }
        return root.toString().getBytes(StandardCharsets.UTF_8);
    }

    /** 2026-11-02（模組 1.4）：提示的等級：一般、警告（紅字；模組停久一點）。 */
    public static final int NOTICE_INFO = 0;
    public static final int NOTICE_WARN = 1;

    /**
     * 2026-11-02（模組 1.4）：一則提示（S→C {@value #NOTICE}）。
     *
     * @param ticks 停幾 tick（模組自己再把警告拉長一點）
     */
    public static byte[] encodeNotice(final List<MiniParts.Part> parts, final long ticks, final int level) {
        final JsonObject root = new JsonObject();
        root.addProperty("v", 1);
        root.add("p", HudSnapshot.partsJson(parts));
        root.addProperty("d", Math.max(1, Math.min(20L * 60, ticks)));
        if (level > 0) {
            root.addProperty("lv", level);
        }
        return root.toString().getBytes(StandardCharsets.UTF_8);
    }

    /**
     * 2026-11-02：提示是不是警告——第一段有字的顏色是紅色系（{@code <red>}、{@code <#ff5f5f>}、{@code <dark_red>}…）。純函式（測試用）。
     */
    public static int noticeLevel(final List<MiniParts.Part> parts) {
        for (final MiniParts.Part part : parts) {
            if (part.text().isBlank()) {
                continue;
            }
            final int r = part.color() >> 16 & 0xff;
            final int g = part.color() >> 8 & 0xff;
            final int b = part.color() & 0xff;
            return r >= 0xa0 && g <= 0x66 && b <= 0x66 && r - Math.max(g, b) >= 0x50 ? NOTICE_WARN : NOTICE_INFO;
        }
        return NOTICE_INFO;
    }

    /** 伺服器回的招呼。latest、manifestUrl 沒有時寫空字串（模組就不檢查更新）。 */
    public static byte[] encodeServerHello(final String latestModVersion, final String manifestUrl) {
        return encodeServerHello(PROTOCOL, latestModVersion, manifestUrl, false);
    }

    /**
     * 伺服器回的招呼（protocol 用 {@link #negotiate} 算好的）。協定 1 不寫 effects（1.0 的模組照舊）。
     *
     * @param effects 伺服器會送 {@value #FX}（模組說要自己畫、而且伺服器開著這個功能）
     */
    public static byte[] encodeServerHello(final int protocol, final String latestModVersion, final String manifestUrl, final boolean effects) {
        final JsonObject root = new JsonObject();
        root.addProperty("protocol", protocol);
        root.addProperty("latestModVersion", latestModVersion == null ? "" : latestModVersion);
        root.addProperty("manifestUrl", manifestUrl == null ? "" : manifestUrl);
        if (protocol >= 2) {
            root.addProperty("effects", effects);
        }
        return root.toString().getBytes(StandardCharsets.UTF_8);
    }

    /** 讀按鍵封包；格式不對（版本、按鍵格超出範圍）回傳 null。 */
    public static Key decodeKey(final byte[] data, final int slots) {
        if (data == null || data.length < 3 || data[0] != KEY_VERSION) {
            return null;
        }
        final int slot = data[1] & 0xff;
        if (slot >= slots) {
            return null;
        }
        return new Key(slot, data[2] != 0);
    }

    public static byte[] encodeKey(final Key key) {
        return new byte[] {KEY_VERSION, (byte) key.slot(), (byte) (key.pressed() ? 1 : 0)};
    }

    /**
     * 比較兩個版本（1.2.10 &gt; 1.2.9；「1.0.0」＝「1.0」；- 或 + 後面的不比，但有 -xxx 的比沒有的舊：1.0.0-beta &lt; 1.0.0）。
     * 看不懂的段落當成 0。
     *
     * @return 負數 a 比較舊、0 一樣、正數 a 比較新
     */
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

    /** {數字的部分, 預覽版的部分（-後面，沒有是空字串）}；+ 後面的建置資訊拿掉。 */
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
