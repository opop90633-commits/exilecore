package tw.exilecore.client.core;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 2026-11-02 照站長（C 組）：裝備、寶石說明的三種樣子。純 Java（測試用）。
 * <ul>
 *   <li>平常（{@link #compact}）：基底那一行、固定詞綴、腐化詞綴、主要詞綴（拿掉範圍與階級，只留數值）、未鑑定／已腐化、比較那幾行，
 *       最後一行提示 Shift／Alt。</li>
 *   <li>按住 Shift：完整說明（伺服器的 lore 原封不動）。</li>
 *   <li>按住 Alt（{@link #tiers}）：每條詞綴的階級、數值、範圍與擲到範圍的幾成（一條小長條）。</li>
 * </ul>
 * 伺服器的 lore 不改（沒裝模組的人照舊看完整版）；這裡只是照 lore 的排法（ItemRenderer、GemService）挑出要顯示的行，
 * 再用物品的 custom_data 裡的 {@code exilecore:data}（{@link #parseEquipment}）補上稀有度、物品等級、前後綴數。
 */
public final class TooltipModel {

    /** 一段字。color 是 0xRRGGBB，-1 = 沒有寫（跟著外層）。 */
    public record Seg(String text, int color) {
    }

    /** 一行。 */
    public record Line(List<Seg> segs) {
        public String plain() {
            final StringBuilder out = new StringBuilder();
            for (final Seg seg : segs) {
                out.append(seg.text());
            }
            return out.toString();
        }

        /** 第一段有字的顏色（-1 = 沒有）。 */
        public int firstColor() {
            for (final Seg seg : segs) {
                if (!seg.text().isBlank()) {
                    return seg.color();
                }
            }
            return -1;
        }

        public static Line of(final String text, final int color) {
            return new Line(List.of(new Seg(text, color)));
        }
    }

    /** 一條詞綴的資料（custom_data）：階級、前綴或後綴、擲出的變數、這一階的範圍（舊物品沒記是空的）。 */
    public record AffixInfo(String id, int tier, String side, Map<String, Integer> vars, Map<String, int[]> ranges) {
    }

    /** 物品的資料（custom_data 的 exilecore:data 或 exilecore:gem）。 */
    public record ItemInfo(boolean gem, String rarity, int ilvl, boolean identified, List<AffixInfo> affixes) {
        public boolean unique() {
            return "UNIQUE".equals(rarity);
        }
    }

    /** 範圍的灰色（插件 Affix.RANGE_COLOR）。 */
    public static final int RANGE_COLOR = 0x8C8C8C;
    /** 傳奇詞綴的顏色。 */
    public static final int UNIQUE_AFFIX = 0x8888FF;
    public static final int DARK_GRAY = 0x555555;
    public static final int GRAY = 0xAAAAAA;
    static final String SEPARATOR_CHAR = "━";
    static final Pattern TIER = Pattern.compile("\\(T(\\d+)\\)");
    /** 數值後面接著範圍：「25(20–30)」「1.5(1–2)」「-1(-1～-5)」。 */
    static final Pattern ROLL = Pattern.compile("(-?\\d+(?:\\.\\d+)?)\\((-?\\d+(?:\\.\\d+)?)[–～](-?\\d+(?:\\.\\d+)?)\\)");

    private TooltipModel() {
    }

    // ---- custom_data ----

    /** 讀 exilecore:data（ItemData 的 JSON）；格式不對回傳 null。 */
    public static ItemInfo parseEquipment(final String json) {
        if (json == null || json.isBlank()) {
            return null;
        }
        try {
            final JsonObject root = JsonParser.parseString(json).getAsJsonObject();
            if (!root.has("base")) {
                return null;
            }
            final List<AffixInfo> affixes = new ArrayList<>();
            if (root.has("affixes") && root.get("affixes").isJsonArray()) {
                for (final JsonElement element : root.getAsJsonArray("affixes")) {
                    final JsonObject o = element.getAsJsonObject();
                    affixes.add(new AffixInfo(o.has("id") ? o.get("id").getAsString() : "", o.has("tier") ? o.get("tier").getAsInt() : 0,
                            o.has("side") ? o.get("side").getAsString().toLowerCase(Locale.ROOT) : "", ints(o, "vars"), ranges(o)));
                }
            }
            return new ItemInfo(false, root.has("rarity") ? root.get("rarity").getAsString() : "NORMAL", root.has("ilvl") ? root.get("ilvl").getAsInt() : 1,
                    !root.has("identified") || root.get("identified").getAsBoolean(), affixes);
        } catch (final RuntimeException e) {
            return null;
        }
    }

    /** 寶石（exilecore:gem）：內容不用讀，有就是寶石。 */
    public static ItemInfo gem() {
        return new ItemInfo(true, "GEM", 0, true, List.of());
    }

    private static Map<String, Integer> ints(final JsonObject o, final String key) {
        final Map<String, Integer> out = new LinkedHashMap<>();
        if (o.has(key) && o.get(key).isJsonObject()) {
            for (final Map.Entry<String, JsonElement> entry : o.getAsJsonObject(key).entrySet()) {
                out.put(entry.getKey(), entry.getValue().getAsInt());
            }
        }
        return out;
    }

    private static Map<String, int[]> ranges(final JsonObject o) {
        final Map<String, int[]> out = new LinkedHashMap<>();
        if (o.has("ranges") && o.get("ranges").isJsonObject()) {
            for (final Map.Entry<String, JsonElement> entry : o.getAsJsonObject("ranges").entrySet()) {
                final var array = entry.getValue().getAsJsonArray();
                if (array.size() == 2) {
                    out.put(entry.getKey(), new int[] {array.get(0).getAsInt(), array.get(1).getAsInt()});
                }
            }
        }
        return out;
    }

    // ---- 分類 ----

    static boolean separator(final Line line) {
        final String text = line.plain().trim();
        return !text.isEmpty() && text.replace(SEPARATOR_CHAR, "").isEmpty();
    }

    /** 一般裝備的詞綴行（◆ 前綴、◇ 後綴，後面有 (T階)）。 */
    static boolean affixLine(final Line line) {
        final String text = line.plain();
        return (text.startsWith("◆ ") || text.startsWith("◇ ")) && TIER.matcher(text).find();
    }

    static boolean implicitLine(final Line line) {
        return line.plain().contains("(固定)");
    }

    static boolean enchantLine(final Line line) {
        return line.plain().startsWith("腐化：");
    }

    static boolean uniqueAffixLine(final Line line) {
        return line.firstColor() == UNIQUE_AFFIX;
    }

    static boolean flagLine(final Line line) {
        final String text = line.plain();
        return text.startsWith("未鑑定") || text.startsWith("已腐化") || text.startsWith("🔒 已鎖定");
    }

    /** 比較那幾行：「比身上的…」接著兩個空白開頭的。 */
    static List<Line> compareLines(final List<Line> lore) {
        final List<Line> out = new ArrayList<>();
        for (int i = 0; i < lore.size(); i++) {
            if (lore.get(i).plain().startsWith("比身上的")) {
                out.add(lore.get(i));
                for (int k = i + 1; k < lore.size() && lore.get(k).plain().startsWith("  "); k++) {
                    out.add(lore.get(k));
                }
                break;
            }
        }
        return out;
    }

    /** 拿掉範圍（灰色的 (20–30)）與階級（(T3)）：精簡版只留數值。 */
    static Line strip(final Line line) {
        final List<Seg> segs = new ArrayList<>();
        for (final Seg seg : line.segs()) {
            if (seg.color() == RANGE_COLOR && seg.text().startsWith("(")) {
                continue;
            }
            String text = seg.text();
            if (seg.color() == DARK_GRAY) {
                text = TIER.matcher(text).replaceAll("").stripTrailing();
                if (text.isBlank()) {
                    continue;
                }
            }
            segs.add(new Seg(text, seg.color()));
        }
        // 詞綴標籤（伺服器 AffixChips）留著：一眼看得出是哪一類
        return new Line(segs);
    }

    /** 最後那一行提示。 */
    public static Line hint(final boolean tiers) {
        return Line.of(tiers ? "按住 Shift：完整說明　Alt：詞綴階級" : "按住 Shift：完整說明", DARK_GRAY);
    }

    /** 平常的精簡版（lore 不含名稱那一行）。 */
    public static List<Line> compact(final List<Line> lore, final ItemInfo info) {
        if (lore.isEmpty() || info == null) {
            return lore;
        }
        final List<Line> out = new ArrayList<>();
        if (info.gem()) {
            // 寶石：第一段（屬性、標籤、說明）最多 3 行＋需要的角色等級
            for (final Line line : lore) {
                if (line.plain().isBlank()) {
                    break;
                }
                if (out.size() < 3 || line.plain().startsWith("需要角色等級")) {
                    out.add(line);
                }
            }
            out.add(hint(false));
            return out;
        }
        out.add(lore.getFirst()); // 基底＋稀有度
        for (int i = 1; i < lore.size(); i++) {
            final Line line = lore.get(i);
            if (implicitLine(line) || enchantLine(line)) {
                out.add(strip(line));
            } else if (info.unique() ? uniqueAffixLine(line) && !line.plain().startsWith("品質") : affixLine(line)) {
                out.add(strip(line));
            } else if (flagLine(line) && !line.plain().startsWith("🔒")) {
                out.add(line);
            }
        }
        final List<Line> compare = compareLines(lore);
        if (!compare.isEmpty()) {
            out.add(Line.of("", -1));
            out.addAll(compare);
        }
        out.add(hint(!rows(lore, info).isEmpty()));
        return out;
    }

    // ---- 階級與範圍 ----

    /** 一個數值與它的範圍。 */
    public record Roll(double value, double low, double high) {
        /** 擲到範圍的幾成（0～1；範圍是負的照絕對值，越大越好）。 */
        public double fraction() {
            final double lo = Math.min(Math.abs(low), Math.abs(high));
            final double hi = Math.max(Math.abs(low), Math.abs(high));
            return hi - lo < 1e-9 ? 1 : Math.max(0, Math.min(1, (Math.abs(value) - lo) / (hi - lo)));
        }
    }

    /** 一條詞綴的那一列：說明（不含範圍、階級）、階級（0 = 沒有）、前綴／後綴／固定／傳奇／腐化、每個數值的範圍。 */
    public record Row(Line text, int tier, String kind, List<Roll> rolls) {
    }

    /** lore 一行裡的「數值(範圍)」。 */
    public static List<Roll> rolls(final String text) {
        final List<Roll> out = new ArrayList<>();
        final Matcher matcher = ROLL.matcher(text);
        while (matcher.find()) {
            out.add(new Roll(Double.parseDouble(matcher.group(1)), Double.parseDouble(matcher.group(2)), Double.parseDouble(matcher.group(3))));
        }
        return out;
    }

    /** 每條詞綴一列（lore 的順序）。 */
    public static List<Row> rows(final List<Line> lore, final ItemInfo info) {
        final List<Row> rows = new ArrayList<>();
        if (info == null || info.gem()) {
            return rows;
        }
        for (final Line line : lore) {
            final String text = line.plain();
            final String kind;
            if (implicitLine(line)) {
                kind = "固定";
            } else if (enchantLine(line)) {
                kind = "腐化";
            } else if (!info.unique() && affixLine(line)) {
                kind = text.startsWith("◆") ? "前綴" : "後綴";
            } else if (info.unique() && uniqueAffixLine(line) && !text.startsWith("品質")) {
                kind = "傳奇";
            } else {
                continue;
            }
            final Matcher tier = TIER.matcher(text);
            final int t = tier.find() ? Integer.parseInt(tier.group(1)) : 0;
            List<Roll> rolls = rolls(text);
            if (rolls.isEmpty()) {
                rolls = fromData(text, t, info); // lore 沒寫範圍（上下限一樣、舊物品）：用 custom_data 的範圍
            }
            rows.add(new Row(strip(line), t, kind, rolls));
        }
        return rows;
    }

    /** custom_data 裡階級一樣、變數的值有出現在這一行的那條詞綴的範圍。 */
    static List<Roll> fromData(final String text, final int tier, final ItemInfo info) {
        for (final AffixInfo affix : info.affixes()) {
            if (affix.tier() != tier || affix.vars().isEmpty() || affix.ranges().isEmpty()) {
                continue;
            }
            boolean all = true;
            for (final int value : affix.vars().values()) {
                if (!text.contains(Integer.toString(Math.abs(value)))) {
                    all = false;
                    break;
                }
            }
            if (!all) {
                continue;
            }
            final List<Roll> out = new ArrayList<>();
            for (final Map.Entry<String, Integer> entry : affix.vars().entrySet()) {
                final int[] range = affix.ranges().get(entry.getKey());
                if (range != null && range[0] != range[1]) {
                    out.add(new Roll(entry.getValue(), range[0], range[1]));
                }
            }
            return out;
        }
        return List.of();
    }

    /** 按住 Alt 的樣子：每條詞綴兩行（「T3 前綴　說明」、「　數值 25（20–30）▮▮▮▯▯ 50%」）。 */
    public static List<Line> tiers(final List<Line> lore, final ItemInfo info) {
        final List<Row> rows = rows(lore, info);
        if (rows.isEmpty()) {
            return lore;
        }
        final List<Line> out = new ArrayList<>();
        out.add(lore.getFirst());
        final StringBuilder head = new StringBuilder("詞綴階級與數值範圍");
        if (info.ilvl() > 0) {
            head.append("・物品等級 ").append(info.ilvl());
        }
        out.add(Line.of(head.toString(), GRAY));
        for (final Row row : rows) {
            final List<Seg> segs = new ArrayList<>();
            segs.add(new Seg(row.tier() > 0 ? "T" + row.tier() + " " : "", tierColor(row.tier())));
            segs.add(new Seg(row.kind() + "　", DARK_GRAY));
            for (final Seg seg : row.text().segs()) {
                if (!seg.text().startsWith("◆ ") && !seg.text().startsWith("◇ ")) {
                    segs.add(seg);
                }
            }
            out.add(new Line(segs));
            for (final Roll roll : row.rolls()) {
                final double fraction = roll.fraction();
                final int filled = (int) Math.round(fraction * 5);
                out.add(new Line(List.of(new Seg("　" + number(roll.value()) + " ", 0xFFFFFF),
                        new Seg("（" + number(Math.min(roll.low(), roll.high())) + "–" + number(Math.max(roll.low(), roll.high())) + "） ", RANGE_COLOR),
                        new Seg("▮".repeat(filled), fraction >= 0.8 ? 0x55FF55 : fraction >= 0.4 ? 0xFFD24A : 0xFF7070),
                        new Seg("▮".repeat(5 - filled), 0x404040),
                        new Seg(" " + Math.round(fraction * 100) + "%", GRAY))));
            }
        }
        out.add(hint(true));
        return out;
    }

    /** 階級的顏色：T1 金、T2～3 亮、其他灰。 */
    static int tierColor(final int tier) {
        return tier == 1 ? 0xFFD27F : tier > 0 && tier <= 3 ? 0xE0E0FF : 0xA0A0A0;
    }

    static String number(final double value) {
        return value == Math.rint(value) ? Long.toString((long) value) : String.format(Locale.ROOT, "%.1f", value);
    }
}
