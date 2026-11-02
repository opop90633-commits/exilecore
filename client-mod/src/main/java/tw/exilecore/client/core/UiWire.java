package tw.exilecore.client.core;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;

import java.io.ByteArrayOutputStream;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.zip.DataFormatException;
import java.util.zip.Inflater;

/**
 * 2026-11-02 照站長（D 組）：天賦樹、星圖、星圖天賦、昇華天賦的原生畫面跟插件（tw.exilecore.clientmod.UiWire）同一套封包格式。純 Java（測試用）。
 * <ul>
 *   <li>S→C {@value #DATA}：一段資料 {@code [版本 1][名稱長度 1][名稱][雜湊 8][第幾段 2][共幾段 2][壓縮後總長 4][內容]}；
 *       收齊了接起來、解壓縮（deflate）成 UTF-8 JSON，雜湊是沒壓縮的 JSON 的 SHA-256 前 8 個位元組（{@link Assembler}）。</li>
 *   <li>C→S {@value #ACTION}：UTF-8 JSON（{@link #action}），Minecraft 的上限是 32767 位元組，我們最多送 {@link #MAX_ACTION}。</li>
 * </ul>
 * 舊插件不認得這兩個頻道：模組按開畫面的鍵時 {@code canSend} 是 false，就提示伺服器還不支援，箱子選單照舊。
 */
public final class UiWire {

    public static final String NAMESPACE = "exilecore";
    public static final String DATA = "ui";
    public static final String ACTION = "uiact";
    public static final int VERSION = 1;
    public static final int MAX_ACTION = 16384;
    /** 一份資料解開最多多大（防亂送；整棵天賦樹大約 0.6 MB）。 */
    public static final int MAX_RAW = 16 * 1024 * 1024;

    private UiWire() {
    }

    /** 一段。 */
    public record Frame(String name, long hash, int index, int count, int total, byte[] data) {
    }

    /** 收齊、解開的一份資料。 */
    public record Blob(String name, long hash, byte[] json) {
        public String text() {
            return new String(json, StandardCharsets.UTF_8);
        }
    }

    public static long hash(final byte[] raw) {
        try {
            return ByteBuffer.wrap(MessageDigest.getInstance("SHA-256").digest(raw), 0, 8).getLong();
        } catch (final NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }

    public static String hex(final long hash) {
        return String.format("%016x", hash);
    }

    /** 十六進位字讀回雜湊；格式不對回傳 0。 */
    public static long parseHex(final String text) {
        try {
            return text == null || text.isEmpty() ? 0 : Long.parseUnsignedLong(text, 16);
        } catch (final NumberFormatException e) {
            return 0;
        }
    }

    public static Frame decode(final byte[] message) {
        if (message == null || message.length < 19 || message[0] != VERSION) {
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
        if (count == 0 || index >= count || total < 0 || total > MAX_RAW) {
            return null;
        }
        final byte[] data = new byte[buffer.remaining()];
        buffer.get(data);
        return new Frame(new String(name, StandardCharsets.US_ASCII), hash, index, count, total, data);
    }

    /** 測試用：編一段（跟插件的寫法一樣）。 */
    public static byte[] encodeFrame(final String name, final long hash, final int index, final int count, final int total, final byte[] data) {
        final byte[] nameBytes = name.getBytes(StandardCharsets.US_ASCII);
        final ByteBuffer buffer = ByteBuffer.allocate(1 + 1 + nameBytes.length + 16 + data.length);
        buffer.put((byte) VERSION).put((byte) nameBytes.length).put(nameBytes).putLong(hash).putShort((short) index).putShort((short) count)
                .putInt(total).put(data);
        return buffer.array();
    }

    public static byte[] inflate(final byte[] packed, final int limit) {
        final Inflater inflater = new Inflater();
        try {
            inflater.setInput(packed);
            final ByteArrayOutputStream out = new ByteArrayOutputStream(Math.max(64, packed.length * 4));
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

    /**
     * 把一段一段的資料接起來。同一個名稱收到新的雜湊就重來（舊的丟掉）；收齊了解壓縮、對雜湊，對不上的不要。
     * 不是執行緒安全的（在遊戲主執行緒上用）。
     */
    public static final class Assembler {
        private static final class Partial {
            final long hash;
            final int count;
            final int total;
            final byte[][] parts;
            int received;

            Partial(final Frame frame) {
                hash = frame.hash();
                count = frame.count();
                total = frame.total();
                parts = new byte[count][];
            }
        }

        private final Map<String, Partial> partials = new HashMap<>();

        /** 收一段；收齊了回傳整份資料，不然回傳 null。 */
        public Blob accept(final Frame frame) {
            if (frame == null) {
                return null;
            }
            Partial partial = partials.get(frame.name());
            if (partial == null || partial.hash != frame.hash() || partial.count != frame.count() || partial.total != frame.total()) {
                partial = new Partial(frame);
                partials.put(frame.name(), partial);
            }
            if (partial.parts[frame.index()] == null) {
                partial.parts[frame.index()] = frame.data();
                partial.received++;
            }
            if (partial.received < partial.count) {
                return null;
            }
            partials.remove(frame.name());
            final ByteArrayOutputStream packed = new ByteArrayOutputStream(partial.total);
            for (final byte[] part : partial.parts) {
                packed.writeBytes(part);
            }
            if (packed.size() != partial.total) {
                return null;
            }
            final byte[] raw = inflate(packed.toByteArray(), MAX_RAW);
            if (raw == null || hash(raw) != partial.hash) {
                return null;
            }
            return new Blob(frame.name(), partial.hash, raw);
        }

        public void clear() {
            partials.clear();
        }

        /** 還在等的份數（測試用）。 */
        public int pending() {
            return partials.size();
        }
    }

    // ---- 動作 ----

    public static byte[] action(final String kind, final String action) {
        return action(kind, action, null);
    }

    /** 一個動作；extra 的欄位照抄（id、path、q、r、mode…）。 */
    public static byte[] action(final String kind, final String action, final JsonObject extra) {
        final JsonObject root = extra == null ? new JsonObject() : extra.deepCopy();
        root.addProperty("t", kind);
        root.addProperty("a", action);
        return root.toString().getBytes(StandardCharsets.UTF_8);
    }

    public static byte[] actionId(final String kind, final String action, final String id) {
        final JsonObject extra = new JsonObject();
        extra.addProperty("id", id);
        return action(kind, action, extra);
    }

    public static byte[] actionPath(final String kind, final List<String> path) {
        final JsonObject extra = new JsonObject();
        final JsonArray array = new JsonArray();
        path.forEach(array::add);
        extra.add("path", array);
        return action(kind, "alloc", extra);
    }
}
