package tw.exilecore.client.core;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;

/**
 * 2026-11-02 照站長（1.4）：右邊的側邊面板（以前是記分板的側欄）。伺服器的 exilecore:panels（戰鬥數據、狀態、新手引導、任務、最近撿到），
 * 加上 hud 裡的召喚物與剛撿到的東西（以前接在球外側），每一個是獨立、可以收起的小面板，由上往下排。純 Java（測試用）。
 */
public final class Panels {

    /** 一個面板。 */
    public record Panel(String id, List<HudData.Part> title, List<List<HudData.Part>> lines) {
    }

    /** 排好的一個面板：畫幾行、還有幾行沒畫（寫「…+N」）、收起來了沒。 */
    public record Placed(Panel panel, int y, int height, int lines, int hidden, boolean collapsed) {
    }

    /** 標題列、內容每行的高度、面板之間的空隙、上下的內距。 */
    public static final int HEADER = 11;
    public static final int LINE = 9;
    public static final int GAP = 3;
    public static final int PAD = 2;

    /** 排在伺服器的面板之間的順序（沒列到的排最後）。 */
    static final List<String> ORDER = List.of("combat", "status", "auras", "minions", "guide", "quest", "loot", "drops");

    private Panels() {
    }

    /** 讀伺服器送來的面板；格式不對回傳 null。 */
    public static List<Panel> parse(final byte[] data) {
        try {
            final JsonObject root = JsonParser.parseString(new String(data, StandardCharsets.UTF_8)).getAsJsonObject();
            final List<Panel> panels = new ArrayList<>();
            for (final JsonElement element : root.getAsJsonArray("panels")) {
                final JsonObject o = element.getAsJsonObject();
                final List<List<HudData.Part>> lines = new ArrayList<>();
                if (o.has("l")) {
                    for (final JsonElement line : o.getAsJsonArray("l")) {
                        lines.add(HudData.parts(line.isJsonArray() ? line.getAsJsonArray() : new JsonArray()));
                    }
                }
                panels.add(new Panel(o.get("id").getAsString(), HudData.parts(o.has("t") ? o.getAsJsonArray("t") : new JsonArray()), lines));
            }
            return panels;
        } catch (final RuntimeException e) {
            return null;
        }
    }

    /** 2026-11-02：增益列放不下時，光環改成一個面板（青色，一行一個）。 */
    public static List<Panel> withAuras(final List<Panel> server, final List<String> auras) {
        if (auras.isEmpty()) {
            return server;
        }
        final List<Panel> out = new ArrayList<>(server);
        final List<List<HudData.Part>> lines = new ArrayList<>();
        for (final String aura : auras) {
            lines.add(List.of(new HudData.Part(aura, 0x7FE8D8)));
        }
        out.add(new Panel("auras", List.of(new HudData.Part("光環", 0x4FD8C8)), lines));
        return out;
    }

    /**
     * 伺服器的面板加上 hud 裡的召喚物（「骷髏 3/4」）與剛撿到的東西（「最近撿到」；快消失的變灰），照 {@link #ORDER} 排。
     *
     * @param elapsed 收到 hud 之後過了幾 tick（撿到的東西照這個倒數）
     */
    public static List<Panel> merge(final List<Panel> server, final HudData.Sides sides, final long elapsed) {
        final List<Panel> all = new ArrayList<>(server);
        if (!sides.pets().isEmpty()) {
            final List<List<HudData.Part>> lines = new ArrayList<>();
            for (final HudData.Pet pet : sides.pets()) {
                lines.add(List.of(new HudData.Part(pet.name() + " ", 0xD8D8D8),
                        new HudData.Part(pet.limit() > 0 ? pet.count() + "/" + pet.limit() : Integer.toString(pet.count()),
                                pet.limit() > 0 && pet.count() >= pet.limit() ? 0x9FE8C8 : 0xFFFFFF)));
            }
            all.add(new Panel("minions", List.of(new HudData.Part("召喚物", 0x9FE8C8)), lines));
        }
        final List<List<HudData.Part>> drops = new ArrayList<>();
        for (int i = sides.loot().size() - 1; i >= 0; i--) {
            final HudData.Loot loot = sides.loot().get(i);
            final long left = loot.left(elapsed);
            if (left <= 0) {
                continue;
            }
            final boolean fading = left <= 30;
            drops.add(List.of(new HudData.Part(loot.name(), fading ? 0x707070 : loot.color()),
                    new HudData.Part(loot.count() > 1 ? " ×" + loot.count() : "", fading ? 0x606060 : 0xAAAAAA)));
        }
        if (!drops.isEmpty()) {
            all.add(new Panel("drops", List.of(new HudData.Part("撿到", 0xF5B942)), drops));
        }
        all.sort(java.util.Comparator.comparingInt(panel -> {
            final int index = ORDER.indexOf(panel.id());
            return index < 0 ? ORDER.size() : index;
        }));
        return all;
    }

    /** 擠的時候每個面板至少畫幾行內容（後面的面板就只剩標題列）。 */
    public static final int MIN_LINES = 3;

    /**
     * 由 top 往下排、底不超過 bottom：照順序排，每個面板先留後面幾個面板的標題列，剩下的給內容（上面的先拿；最上面那個至少 {@link #MIN_LINES} 行）；
     * 放不下的寫「…+N」，一行都放不下的只剩標題列（寫「▸ N」）。收起來的（collapsedAll，或 id 在 collapsed 裡）只有標題列。
     */
    public static List<Placed> layout(final List<Panel> panels, final int top, final int bottom, final boolean collapsedAll,
                                      final Set<String> collapsed) {
        final List<Placed> out = new ArrayList<>();
        int y = top;
        for (int index = 0; index < panels.size(); index++) {
            final Panel panel = panels.get(index);
            if (y + HEADER > bottom) {
                break;
            }
            final boolean shut = collapsedAll || collapsed.contains(panel.id());
            int lines = 0;
            int hidden = 0;
            if (!shut && !panel.lines().isEmpty()) {
                final int reserve = (panels.size() - index - 1) * (HEADER + GAP);
                final int fit = Math.max(0, (bottom - y - HEADER - PAD * 2) / LINE); // 不管後面的，最多放得下幾行
                int room = Math.max(0, (bottom - y - HEADER - PAD * 2 - reserve) / LINE);
                if (out.isEmpty()) {
                    room = Math.min(fit, Math.max(room, Math.min(MIN_LINES, panel.lines().size()))); // 最上面那個至少幾行
                }
                lines = Math.min(panel.lines().size(), room);
                if (lines < panel.lines().size() && lines > 0) {
                    lines--; // 最後一行寫「…+N」
                }
                hidden = panel.lines().size() - lines;
            } else if (shut) {
                hidden = panel.lines().size();
            }
            int rows = shut || lines == 0 ? 0 : lines + (hidden > 0 ? 1 : 0);
            if (rows > 0 && y + HEADER + rows * LINE + PAD * 2 > bottom) {
                rows = 0; // 連「…+N」都放不下：只留標題列
                lines = 0;
                hidden = panel.lines().size();
            }
            final int height = HEADER + (rows > 0 ? rows * LINE + PAD * 2 : 0);
            out.add(new Placed(panel, y, height, lines, hidden, shut || rows == 0 && hidden > 0));
            y += height + GAP;
        }
        return out;
    }

    /**
     * 2026-11-02 照站長：可以一個一個拖的面板（id → 拖動畫面寫的名字），照 {@link #ORDER}。
     * 這次之前側邊面板只能整群一起拖。
     */
    public static final java.util.Map<String, String> LABELS = labels();

    private static java.util.Map<String, String> labels() {
        final java.util.Map<String, String> out = new java.util.LinkedHashMap<>();
        out.put("combat", "面板：戰鬥數據");
        out.put("status", "面板：狀態");
        out.put("minions", "面板：召喚物");
        out.put("guide", "面板：新手引導");
        out.put("quest", "面板：任務");
        out.put("loot", "面板：最近撿到");
        out.put("drops", "面板：撿到東西");
        return java.util.Collections.unmodifiableMap(out);
    }

    /** 拖動畫面用：沒有內容的面板（{@link #LABELS}）補一個示意的（標題＋一行灰字），才拖得到。純函式（測試用）。 */
    public static List<Panel> placeholders(final List<Panel> panels) {
        final List<Panel> all = new ArrayList<>(panels);
        final Set<String> have = new java.util.HashSet<>();
        for (final Panel panel : panels) {
            have.add(panel.id());
        }
        for (final java.util.Map.Entry<String, String> entry : LABELS.entrySet()) {
            if (have.contains(entry.getKey()) || entry.getKey().equals("loot")) {
                continue; // 「最近撿到」是記分板版（沒有「撿到」面板的人）：有內容才畫
            }
            all.add(new Panel(entry.getKey(), List.of(new HudData.Part(entry.getValue().substring("面板：".length()), 0x8A8A8A)),
                    List.of(List.of(new HudData.Part("（示意：拖動位置用）", 0x707070)))));
        }
        all.sort(java.util.Comparator.comparingInt(panel -> {
            final int index = ORDER.indexOf(panel.id());
            return index < 0 ? ORDER.size() : index;
        }));
        return all;
    }
}
