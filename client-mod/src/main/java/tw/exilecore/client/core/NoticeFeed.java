package tw.exilecore.client.core;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;

/**
 * 2026-11-02 照站長（1.4）：提示列。伺服器的提示（撿到金幣、魔力不足、委託進度……，以前是動作列那一行、新的蓋掉舊的）
 * 改送 exilecore:notice，這裡疊起來：最多 {@link #MAX} 則（新的在最下面），快到時間的淡出；警告（紅字）停久一點。
 * 同一種提示（拿掉數字以後一樣，例如「+120 金幣」→「+80 金幣」、「開鎖中：3／10」→「4／10」）換掉舊的那則，不會一直往上疊。
 * 時間單位是 tick（double：畫的時候加上這一幀過了多少）。純 Java（測試用）。
 */
public final class NoticeFeed {

    /** 最多記幾則（畫幾則看畫面高度，{@link HudLayout#noticeLines}）。 */
    public static final int MAX = 4;
    /** 淡入、淡出幾 tick。 */
    public static final double FADE_IN = 3;
    public static final double FADE_OUT = 12;
    /** 警告停的時間乘上多少。 */
    public static final double WARN_STRETCH = 1.6;
    /** 一則最多停多久（tick）。 */
    public static final double MAX_TICKS = 20 * 20;

    /** 一則提示：字、等級（0 一般、1 警告）、開始與結束的時間。 */
    public record Entry(List<HudData.Part> parts, String key, int level, double start, double end) {
    }

    /** 伺服器送來的一則（{@code {"v":1,"p":[…],"d":停幾 tick,"lv":等級}}）。 */
    public record Packet(List<HudData.Part> parts, long ticks, int level) {
    }

    /** 一則要畫的：alpha 是 0～1。 */
    public record Shown(Entry entry, double alpha) {
    }

    private final List<Entry> entries = new ArrayList<>();

    /** 讀封包；格式不對回傳 null。 */
    public static Packet parse(final byte[] data) {
        try {
            final JsonObject root = JsonParser.parseString(new String(data, StandardCharsets.UTF_8)).getAsJsonObject();
            final List<HudData.Part> parts = HudData.parts(root.getAsJsonArray("p"));
            if (plain(parts).isBlank()) {
                return null;
            }
            return new Packet(parts, root.has("d") ? root.get("d").getAsLong() : 60, root.has("lv") ? root.get("lv").getAsInt() : 0);
        } catch (final RuntimeException e) {
            return null;
        }
    }

    public static String plain(final List<HudData.Part> parts) {
        final StringBuilder out = new StringBuilder();
        for (final HudData.Part part : parts) {
            out.append(part.text());
        }
        return out.toString();
    }

    /** 同一種提示的代號：拿掉數字、小數點、逗號、百分比、正負號、斜線與空白。 */
    public static String key(final String plain) {
        return plain.replaceAll("[0-9.,%+\\-／/×\\s]", "");
    }

    /** 這則停多久（tick）：警告拉長，最多 {@link #MAX_TICKS}。 */
    public static double duration(final long ticks, final int level) {
        final double base = Math.max(10, ticks);
        return Math.min(MAX_TICKS, level > 0 ? base * WARN_STRETCH : base);
    }

    public void add(final Packet packet, final double now) {
        final String key = key(plain(packet.parts()));
        double start = now;
        for (int i = 0; i < entries.size(); i++) {
            final Entry old = entries.get(i);
            if (old.key().equals(key) && old.end() > now) {
                start = Math.min(now, old.start() + FADE_IN) - FADE_IN; // 換掉同一種的：不再淡入一次
                entries.remove(i);
                break;
            }
        }
        entries.add(new Entry(packet.parts(), key, packet.level(), start, now + duration(packet.ticks(), packet.level())));
        while (entries.size() > MAX) {
            entries.removeFirst();
        }
    }

    /** 現在要畫的（舊的在前、最新的在最後），最多 limit 則（多的從舊的那頭讓）；過期的順便清掉。 */
    public List<Shown> visible(final double now, final int limit) {
        entries.removeIf(entry -> entry.end() <= now);
        final List<Shown> out = new ArrayList<>();
        for (int i = Math.max(0, entries.size() - Math.max(0, limit)); i < entries.size(); i++) {
            final Entry entry = entries.get(i);
            out.add(new Shown(entry, alpha(now, entry.start(), entry.end())));
        }
        return out;
    }

    public void clear() {
        entries.clear();
    }

    public int size() {
        return entries.size();
    }

    /** 透明度：開始的 {@link #FADE_IN} tick 淡入、最後 {@link #FADE_OUT} tick 淡出。 */
    public static double alpha(final double now, final double start, final double end) {
        if (now >= end || now < start) {
            return 0;
        }
        final double in = Math.min(1, (now - start) / FADE_IN);
        final double out = Math.min(1, (end - now) / FADE_OUT);
        return Math.max(0, Math.min(in, out));
    }
}
