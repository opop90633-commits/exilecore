package tw.exilecore.clientmod;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;

import java.util.ArrayList;
import java.util.List;

/**
 * 2026-11-02 照站長（模組 1.4）：側邊面板（S→C exilecore:panels）。這次之前戰鬥數據、狀態、新手引導、任務追蹤、最近撿到
 * 全部擠在同一張記分板的側欄（最多 15 行，放不下的從下面讓）；招呼寫了 panels 的模組改成一段一段送，模組畫成各自獨立、可以收起的小面板。
 * 純資料、純函式（測試用）。
 * <pre>
 * {"v":1,"panels":[{"id":"combat","t":[{"t":字,"c":顏色}],"l":[[{"t":字,"c":顏色}],…]},…]}
 * </pre>
 * 沒有任何面板時 panels 是空的（模組全部收掉）。順序就是由上往下排的順序。
 */
public final class HudPanels {

    public static final int VERSION = 1;
    /** 每個面板最多幾行（防一次送太多；模組自己再照畫面高度裁）。 */
    public static final int MAX_LINES = 20;

    /** 一個面板。title、lines 都是 MiniMessage（{@link #toJson} 才拆成字段）。 */
    public record Panel(String id, String title, List<String> lines) {
        public Panel {
            lines = List.copyOf(lines);
        }
    }

    private HudPanels() {
    }

    public static String toJson(final List<Panel> panels) {
        final JsonObject root = new JsonObject();
        root.addProperty("v", VERSION);
        final JsonArray array = new JsonArray();
        for (final Panel panel : panels) {
            final JsonObject o = new JsonObject();
            o.addProperty("id", panel.id());
            o.add("t", HudSnapshot.partsJson(MiniParts.parse(panel.title())));
            final JsonArray lines = new JsonArray();
            for (final String line : panel.lines().subList(0, Math.min(panel.lines().size(), MAX_LINES))) {
                lines.add(HudSnapshot.partsJson(MiniParts.parse(line)));
            }
            o.add("l", lines);
            array.add(o);
        }
        root.add("panels", array);
        return root.toString();
    }

    /**
     * 側欄的各段拆成面板（空的那段不列）。combat、guide、quest 的第一行是標題；status、loot 沒有標題（用 statusTitle、lootTitle）。
     * 尾端的空白行拿掉（側欄用空白行隔段，面板不用）。
     */
    public static List<Panel> of(final List<String> combat, final List<String> status, final String statusTitle, final List<String> guide,
                                 final List<String> quest, final List<String> loot, final String lootTitle) {
        final List<Panel> panels = new ArrayList<>();
        titled(panels, "combat", combat);
        if (!status.isEmpty()) {
            panels.add(new Panel("status", statusTitle, trim(status)));
        }
        titled(panels, "guide", guide);
        titled(panels, "quest", quest);
        if (!loot.isEmpty()) {
            panels.add(new Panel("loot", lootTitle, trim(loot)));
        }
        return panels;
    }

    private static void titled(final List<Panel> panels, final String id, final List<String> lines) {
        if (lines.isEmpty()) {
            return;
        }
        panels.add(new Panel(id, lines.getFirst(), trim(lines.subList(1, lines.size()))));
    }

    private static List<String> trim(final List<String> lines) {
        int end = lines.size();
        while (end > 0 && MiniParts.plain(lines.get(end - 1)).isBlank()) {
            end--;
        }
        return lines.subList(0, end);
    }
}
