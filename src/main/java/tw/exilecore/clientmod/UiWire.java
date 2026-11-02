package tw.exilecore.clientmod;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

import java.io.ByteArrayOutputStream;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.List;
import java.util.zip.DataFormatException;
import java.util.zip.Deflater;
import java.util.zip.Inflater;

/**
 * 2026-11-02 照站長（D 組）：天賦樹、星圖、星圖天賦、昇華天賦的原生畫面用的封包格式。純函式，不依賴 Bukkit（測試用）；
 * 模組那邊（client-mod 的 tw.exilecore.client.core.UiWire）是同一份寫法。這次之前這些畫面只有箱子選單。
 * <ul>
 *   <li>S→C {@value #DATA}：一段「資料」（名稱＋雜湊＋第幾段／共幾段＋內容）。資料本身是 UTF-8 JSON 壓縮（deflate）之後的位元組，
 *       太大的切成好幾段（每段最多 {@link #DEFAULT_CHUNK} 位元組，Minecraft 自訂封包的上限是 1 MiB、我們留很多餘裕），模組收齊了再解開。
 *       雜湊是「沒壓縮的 JSON」的 SHA-256 前 8 個位元組：樹的靜態資料（節點、座標、連線、說明）模組照這個快取，伺服器改版時雜湊變了才重送。</li>
 *   <li>C→S {@value #ACTION}：UTF-8 JSON {@code {"t":"passive","a":"alloc","path":["p1","p2"]}}（打開、要靜態資料、配點、退點、關掉…），
 *       最多 {@link #MAX_ACTION} 位元組（Minecraft 自訂封包 C→S 上限 32767）。伺服器照現有的規則驗證、做完回最新的狀態。</li>
 * </ul>
 * 一段的格式：{@code [版本 1][名稱長度 1][名稱 ASCII][雜湊 8][第幾段 2][共幾段 2][壓縮後總長 4][這一段的內容]}（大端序）。
 */
public final class UiWire {

    public static final String DATA = "exilecore:ui";
    public static final String ACTION = "exilecore:uiact";
    public static final int VERSION = 1;
    /** 一段最多幾位元組（內容；不含表頭）。 */
    public static final int DEFAULT_CHUNK = 30000;
    public static final int MIN_CHUNK = 1024;
    public static final int MAX_CHUNK = 900_000;
    /** 最多切幾段（2 位元組）。 */
    public static final int MAX_PARTS = 65535;
    /** 動作封包最多幾位元組。 */
    public static final int MAX_ACTION = 16384;

    private UiWire() {
    }

    /** 一段。 */
    public record Frame(String name, long hash, int index, int count, int total, byte[] data) {
    }

    /** 沒壓縮的 JSON 的雜湊（SHA-256 的前 8 個位元組）。 */
    public static long hash(final byte[] raw) {
        try {
            final byte[] digest = MessageDigest.getInstance("SHA-256").digest(raw);
            return ByteBuffer.wrap(digest, 0, 8).getLong();
        } catch (final NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }

    /** 雜湊寫成 16 個十六進位字。 */
    public static String hex(final long hash) {
        return String.format("%016x", hash);
    }

    public static byte[] deflate(final byte[] raw) {
        final Deflater deflater = new Deflater(Deflater.BEST_COMPRESSION);
        try {
            deflater.setInput(raw);
            deflater.finish();
            final ByteArrayOutputStream out = new ByteArrayOutputStream(Math.max(64, raw.length / 4));
            final byte[] buffer = new byte[8192];
            while (!deflater.finished()) {
                out.write(buffer, 0, deflater.deflate(buffer));
            }
            return out.toByteArray();
        } finally {
            deflater.end();
        }
    }

    /** 解壓縮；解出來超過 limit 位元組（亂送的）或格式不對回傳 null。 */
    public static byte[] inflate(final byte[] packed, final int limit) {
        final Inflater inflater = new Inflater();
        try {
            inflater.setInput(packed);
            final ByteArrayOutputStream out = new ByteArrayOutputStream(packed.length * 4);
            final byte[] buffer = new byte[8192];
            while (!inflater.finished()) {
                final int n = inflater.inflate(buffer);
                if (n == 0 && (inflater.needsInput() || inflater.needsDictionary())) {
                    return null;
                }
                out.write(buffer, 0, n);
                if (out.size() > limit) {
                    return null;
                }
            }
            return out.toByteArray();
        } catch (final DataFormatException e) {
            return null;
        } finally {
            inflater.end();
        }
    }

    /** 把一份 JSON 壓縮、切段、編成封包（每段一個）。 */
    public static List<byte[]> encode(final String name, final byte[] rawJson, final int chunk) {
        return encode(name, hash(rawJson), deflate(rawJson), chunk);
    }

    public static List<byte[]> encode(final String name, final long hash, final byte[] packed, final int chunk) {
        final byte[] nameBytes = name.getBytes(StandardCharsets.US_ASCII);
        if (nameBytes.length == 0 || nameBytes.length > 64) {
            throw new IllegalArgumentException("名稱長度不對：" + name);
        }
        final int size = Math.max(MIN_CHUNK, Math.min(MAX_CHUNK, chunk));
        final int count = Math.max(1, (packed.length + size - 1) / size);
        if (count > MAX_PARTS) {
            throw new IllegalArgumentException("資料太大：" + packed.length);
        }
        final List<byte[]> frames = new ArrayList<>(count);
        for (int i = 0; i < count; i++) {
            final int from = i * size;
            final int length = Math.min(size, packed.length - from);
            final ByteBuffer buffer = ByteBuffer.allocate(1 + 1 + nameBytes.length + 8 + 2 + 2 + 4 + length);
            buffer.put((byte) VERSION).put((byte) nameBytes.length).put(nameBytes).putLong(hash)
                    .putShort((short) i).putShort((short) count).putInt(packed.length).put(packed, from, length);
            frames.add(buffer.array());
        }
        return frames;
    }

    /** 讀一段；格式不對回傳 null。 */
    public static Frame decode(final byte[] message) {
        if (message == null || message.length < 1 + 1 + 1 + 8 + 2 + 2 + 4 || message[0] != VERSION) {
            return null;
        }
        final ByteBuffer buffer = ByteBuffer.wrap(message);
        buffer.get();
        final int nameLength = buffer.get() & 0xff;
        if (nameLength == 0 || buffer.remaining() < nameLength + 16) {
            return null;
        }
        final byte[] name = new byte[nameLength];
        buffer.get(name);
        final long hash = buffer.getLong();
        final int index = buffer.getShort() & 0xffff;
        final int count = buffer.getShort() & 0xffff;
        final int total = buffer.getInt();
        if (count == 0 || index >= count || total < 0) {
            return null;
        }
        final byte[] data = new byte[buffer.remaining()];
        buffer.get(data);
        return new Frame(new String(name, StandardCharsets.US_ASCII), hash, index, count, total, data);
    }

    /** 讀模組送來的動作；格式不對（不是 JSON 物件、沒有 t 或 a、太長）回傳 null。 */
    public static JsonObject decodeAction(final byte[] data) {
        if (data == null || data.length == 0 || data.length > MAX_ACTION) {
            return null;
        }
        try {
            final JsonElement parsed = JsonParser.parseString(new String(data, StandardCharsets.UTF_8));
            if (!parsed.isJsonObject()) {
                return null;
            }
            final JsonObject root = parsed.getAsJsonObject();
            if (!root.has("t") || !root.has("a") || !root.get("t").isJsonPrimitive() || !root.get("a").isJsonPrimitive()) {
                return null;
            }
            return root;
        } catch (final RuntimeException e) {
            return null;
        }
    }

    /** 模組的招呼裡有沒有寫這個旗標（{@code "treeui":true}）；招呼格式不對、沒寫都是 false。 */
    public static boolean helloFlag(final byte[] hello, final String key) {
        if (hello == null || hello.length == 0 || hello.length > ClientModProtocol.MAX_HELLO) {
            return false;
        }
        try {
            final JsonElement parsed = JsonParser.parseString(new String(hello, StandardCharsets.UTF_8));
            if (!parsed.isJsonObject()) {
                return false;
            }
            final JsonElement value = parsed.getAsJsonObject().get(key);
            return value != null && value.isJsonPrimitive() && value.getAsJsonPrimitive().isBoolean() && value.getAsBoolean();
        } catch (final RuntimeException e) {
            return false;
        }
    }
}
