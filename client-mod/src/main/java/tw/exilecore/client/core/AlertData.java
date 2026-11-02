package tw.exilecore.client.core;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 2026-11-02 照站長（C 組）：伺服器送來的 Boss 預警（exilecore:alert，格式見插件的 FeedProtocol）與畫面上現在有哪些。純 Java（測試用）。
 * 這次之前預警是原版的大標題：只有一個槽，後送的蓋掉前面的。
 * <p>
 * 時間都用模組自己的 tick（收到的時候記下）：伺服器每 5 tick 重送一次同代號的那種，只是把留的時間往後延，不會疊好幾則。
 */
public final class AlertData {

    public enum Lane { TOP, SIDE }

    /** 一則預警。at 是 {x, y, z}（沒有 null）；safe = at 是安全區（箭頭往那裡指），不然是危險區（箭頭往外指）。 */
    public record Alert(String id, Lane lane, int priority, List<HudData.Part> title, List<HudData.Part> sub, List<List<HudData.Part>> lines,
                        int ticks, int countdown, double[] at, boolean safe) {
    }

    /** 一則畫面上的：收到的 tick、到哪個 tick 收掉（Long.MAX_VALUE = 一直留著）。 */
    public record Shown(Alert alert, long since, long until) {

        /** 倒數還剩幾 tick（沒有倒數 0）。 */
        public long countdownLeft(final long now) {
            return alert.countdown() <= 0 ? 0 : Math.max(0, alert.countdown() - (now - since));
        }
    }

    /** 一個封包：show 或 clear。 */
    public record Packet(boolean clear, String id, Alert alert) {
    }

    /** 上面那排最多同時幾則（再多的照優先度丟掉）。 */
    public static final int MAX_TOP = 4;
    /** 留著不收的（d = 0）最多留多久（10 分鐘）：Boss 打完伺服器沒送 clear 也不會一直掛著。 */
    public static final long MAX_KEEP = 20L * 60 * 10;

    private final Map<String, Shown> shown = new LinkedHashMap<>();

    public static Packet parse(final byte[] data) {
        try {
            final JsonObject root = JsonParser.parseString(new String(data, StandardCharsets.UTF_8)).getAsJsonObject();
            final String id = root.has("id") ? root.get("id").getAsString() : "";
            if ("clear".equals(str(root, "op"))) {
                return new Packet(true, id, null);
            }
            final List<List<HudData.Part>> lines = new ArrayList<>();
            for (final JsonElement line : array(root, "l")) {
                lines.add(parts(line.getAsJsonArray()));
            }
            double[] at = null;
            if (root.has("at") && root.get("at").isJsonArray() && root.getAsJsonArray("at").size() == 3) {
                final JsonArray a = root.getAsJsonArray("at");
                at = new double[] {a.get(0).getAsDouble(), a.get(1).getAsDouble(), a.get(2).getAsDouble()};
            }
            final Alert alert = new Alert(id, "side".equals(str(root, "lane")) ? Lane.SIDE : Lane.TOP, Math.max(0, Math.min(3, integer(root, "p", 1))),
                    parts(array(root, "t")), parts(array(root, "s")), lines, Math.max(0, integer(root, "d", 40)), Math.max(0, integer(root, "cd", 0)),
                    at, "safe".equals(str(root, "k")));
            return new Packet(false, id, alert);
        } catch (final RuntimeException e) {
            return null;
        }
    }

    /** 收到一個封包（now = 模組的 tick）。 */
    public void apply(final Packet packet, final long now) {
        if (packet == null) {
            return;
        }
        if (packet.clear()) {
            if ("*".equals(packet.id())) {
                shown.clear();
            } else {
                shown.remove(packet.id());
            }
            return;
        }
        final Alert alert = packet.alert();
        final Shown before = shown.get(alert.id());
        final long until = alert.ticks() <= 0 ? now + MAX_KEEP : now + alert.ticks();
        // 同代號再送一次：留的時間往後延；倒數照新的（重算起點），收到的時間不變（字不會一直閃）
        shown.remove(alert.id());
        shown.put(alert.id(), new Shown(alert, before == null || alert.countdown() > 0 ? now : before.since(), until));
    }

    public void clear() {
        shown.clear();
    }

    /** 收掉過期的。 */
    public void expire(final long now) {
        shown.values().removeIf(each -> now >= each.until());
    }

    /** 上面那排要畫的：優先度高的在前、一樣的新的在前，最多 {@link #MAX_TOP} 則。 */
    public List<Shown> top(final long now) {
        expire(now);
        final List<Shown> list = new ArrayList<>();
        for (final Shown each : shown.values()) {
            if (each.alert().lane() == Lane.TOP) {
                list.add(each);
            }
        }
        list.sort(Comparator.comparingInt((Shown each) -> -each.alert().priority()).thenComparingLong(each -> -each.since()));
        return list.size() > MAX_TOP ? new ArrayList<>(list.subList(0, MAX_TOP)) : list;
    }

    /** 側邊的機制清單（新的在前）。 */
    public List<Shown> side(final long now) {
        expire(now);
        final List<Shown> list = new ArrayList<>();
        for (final Shown each : shown.values()) {
            if (each.alert().lane() == Lane.SIDE) {
                list.add(0, each);
            }
        }
        return list;
    }

    public boolean isEmpty() {
        return shown.isEmpty();
    }

    /**
     * 箭頭要轉幾度（0 = 正前方、正的往右轉，跟原版的 yaw 一樣是順時針）：安全區指向它、危險區指向反方向（往外逃）。
     * 原版 yaw 0 是面向 +z（南）、90 是 -x（西）。
     */
    public static double arrowDegrees(final double px, final double pz, final float yaw, final double tx, final double tz, final boolean safe) {
        double dx = tx - px;
        double dz = tz - pz;
        if (!safe) {
            dx = -dx;
            dz = -dz;
        }
        final double world = Math.toDegrees(Math.atan2(-dx, dz));
        return wrap(world - yaw);
    }

    /** 換成 -180～180。 */
    static double wrap(final double degrees) {
        double value = degrees % 360;
        if (value > 180) {
            value -= 360;
        } else if (value <= -180) {
            value += 360;
        }
        return value;
    }

    /** 上面那排的排法：第 k 則（照優先度）放在第幾個位置（0 在中間、1 右、2 左…）。 */
    public static int[] seats(final int count) {
        final int[] seat = new int[count];
        final int middle = (count - 1) / 2;
        for (int k = 0; k < count; k++) {
            final int step = (k + 1) / 2;
            seat[k] = k == 0 ? middle : k % 2 == 1 ? middle + step : middle - step;
        }
        return seat;
    }

    /** 倒數的字：「3.4」（10 秒以上整數）。 */
    public static String countdownText(final long ticks) {
        return HudData.seconds(ticks);
    }

    private static List<HudData.Part> parts(final JsonArray array) {
        final List<HudData.Part> parts = new ArrayList<>();
        for (final JsonElement element : array) {
            final JsonObject o = element.getAsJsonObject();
            parts.add(new HudData.Part(o.has("t") ? o.get("t").getAsString() : "", o.has("c") ? o.get("c").getAsInt() : 0xFFFFFF));
        }
        return parts;
    }

    private static JsonArray array(final JsonObject o, final String key) {
        return o.has(key) && o.get(key).isJsonArray() ? o.getAsJsonArray(key) : new JsonArray();
    }

    private static String str(final JsonObject o, final String key) {
        return o.has(key) && !o.get(key).isJsonNull() ? o.get(key).getAsString() : "";
    }

    private static int integer(final JsonObject o, final String key, final int fallback) {
        return o.has(key) ? o.get(key).getAsInt() : fallback;
    }
}
