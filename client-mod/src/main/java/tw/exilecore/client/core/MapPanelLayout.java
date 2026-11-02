package tw.exilecore.client.core;

import java.util.ArrayList;
import java.util.List;
import java.util.function.ToIntFunction;

/**
 * 2026-11-02 照站長（模組 1.4 B 組）：地圖資訊框的排版（右上角、可以收起）。算出一行一行要畫什麼，畫的人照著畫。
 * 純 Java（測試用；字寬由外面給）。這次之前沒有地圖資訊框。
 */
public final class MapPanelLayout {

    /** 框的寬（GUI 像素）。 */
    public static final int WIDTH = 150;
    /** 小字的縮放（詞綴、事件）。 */
    public static final float SMALL = 0.75f;
    public static final int GRAY = 0xA0A0A0;
    public static final int DIM = 0x707070;

    private MapPanelLayout() {
    }

    /**
     * 一行。left 靠左、right 靠右；progress 0–100 時下面畫一條進度（-1 不畫）；small 用小字；dim 已經結束的（暗一點）。
     */
    public record Row(List<HudData.Part> left, List<HudData.Part> right, int progress, int barColor, boolean small, boolean dim) {
    }

    private static List<HudData.Part> one(final String text, final int color) {
        return text == null || text.isEmpty() ? List.of() : List.of(new HudData.Part(text, color));
    }

    /** Boss 那一段的字與顏色。 */
    static HudData.Part bossStatus(final WorldCodec.MapPanel p) {
        return switch (p.boss()) {
            case WorldCodec.BOSS_FIGHTING -> new HudData.Part(p.bossLife() >= 0 ? "戰鬥中 " + p.bossLife() + "%" : "戰鬥中", 0xFF6060);
            case WorldCodec.BOSS_KILLED -> new HudData.Part("已擊殺", 0x60E060);
            default -> new HudData.Part("還沒到", GRAY);
        };
    }

    /** 剩幾門／幾次（不適用 = 空字串）。 */
    static String portals(final WorldCodec.MapPanel p) {
        return p.portals() < 0 ? "" : "剩 " + p.portals() + " " + (p.unit() == null || p.unit().isEmpty() ? "次" : p.unit());
    }

    /**
     * 排版。
     *
     * @param width 字寬（一般大小，GUI 像素）
     */
    public static List<Row> layout(final WorldCodec.MapPanel p, final boolean collapsed, final ToIntFunction<String> width) {
        final List<Row> rows = new ArrayList<>();
        if (p == null || p == WorldCodec.CLEAR) {
            return rows;
        }
        final String tier = (p.tier() > 0 ? "T" + p.tier() + " " : "") + (p.level() > 0 ? "Lv." + p.level() : "");
        rows.add(new Row(one(p.name(), p.color()), one(tier, GRAY), -1, 0, false, false));
        final boolean hasBoss = p.boss() != WorldCodec.BOSS_NONE;
        if (collapsed) {
            final List<HudData.Part> summary = new ArrayList<>();
            final String portals = portals(p);
            if (!portals.isEmpty()) {
                summary.add(new HudData.Part(portals + "・", GRAY));
            }
            summary.add(new HudData.Part("清除 " + p.cleared() + "%", GRAY));
            if (hasBoss) {
                summary.add(new HudData.Part("・Boss ", GRAY));
                summary.add(bossStatus(p));
            }
            rows.add(new Row(summary, List.of(), -1, 0, true, false));
            return rows;
        }
        rows.add(new Row(one(portals(p), 0xF5B942), one("探索 " + p.explored() + "%", GRAY), -1, 0, true, false));
        rows.add(new Row(one("清除 " + p.cleared() + "%", 0xE0E0E0), List.of(), p.cleared(), 0xFF5FC85F, true, false));
        if (hasBoss) {
            rows.add(new Row(one("Boss " + (p.bossName() == null ? "" : p.bossName()), 0xE0E0E0), List.of(bossStatus(p)),
                    p.boss() == WorldCodec.BOSS_FIGHTING ? Math.max(0, p.bossLife()) : -1, 0xFFD03030, true, p.boss() == WorldCodec.BOSS_KILLED));
        }
        if (!p.mods().isEmpty()) {
            rows.add(new Row(one("地圖詞綴", GRAY), List.of(), -1, 0, true, false));
            final int max = Math.round((WIDTH - 6) / SMALL);
            for (final List<HudData.Part> line : p.mods()) {
                for (final List<HudData.Part> wrapped : wrap(line, width, max)) {
                    rows.add(new Row(wrapped, List.of(), -1, 0, true, false));
                }
            }
        }
        if (!p.events().isEmpty()) {
            rows.add(new Row(one("事件", GRAY), List.of(), -1, 0, true, false));
            final int max = Math.round((WIDTH - 6) / SMALL);
            for (final WorldCodec.EventLine e : p.events()) {
                final boolean over = e.state() == WorldCodec.EVENT_DONE || e.state() == WorldCodec.EVENT_FAILED;
                final int statusColor = e.state() == WorldCodec.EVENT_FAILED ? 0xFF6060 : over ? DIM
                        : e.state() == WorldCodec.EVENT_ACTIVE ? 0xFFFFFF : GRAY;
                final List<HudData.Part> left = one(e.name(), over ? DIM : e.color());
                final List<HudData.Part> right = one(e.status(), statusColor);
                final int bar = 0xFF000000 | (e.color() == 0xFFFFFF ? 0xD0D0D0 : e.color());
                if (width.applyAsInt(e.name()) + width.applyAsInt(e.status()) + 8 > max) {
                    // 放不下一行：狀態換到下一行
                    rows.add(new Row(left, List.of(), -1, 0, true, over));
                    for (final List<HudData.Part> wrapped : wrap(right, width, max)) {
                        rows.add(new Row(indent(wrapped), List.of(), -1, 0, true, over));
                    }
                    if (e.progress() >= 0 && !over) {
                        rows.set(rows.size() - 1, withBar(rows.getLast(), e.progress(), bar));
                    }
                } else {
                    rows.add(new Row(left, right, over ? -1 : e.progress(), bar, true, over));
                }
            }
        }
        return rows;
    }

    private static Row withBar(final Row row, final int progress, final int color) {
        return new Row(row.left(), row.right(), progress, color, row.small(), row.dim());
    }

    private static List<HudData.Part> indent(final List<HudData.Part> parts) {
        final List<HudData.Part> out = new ArrayList<>();
        out.add(new HudData.Part("  ", 0xFFFFFF));
        out.addAll(parts);
        return out;
    }

    /** 一行太長就照字切（中文沒有空白，一個字一個字量）。 */
    public static List<List<HudData.Part>> wrap(final List<HudData.Part> line, final ToIntFunction<String> width, final int max) {
        final List<List<HudData.Part>> out = new ArrayList<>();
        List<HudData.Part> current = new ArrayList<>();
        int used = 0;
        for (final HudData.Part part : line) {
            final StringBuilder piece = new StringBuilder();
            for (int i = 0; i < part.text().length(); ) {
                final int cp = part.text().codePointAt(i);
                final String ch = new String(Character.toChars(cp));
                final int w = width.applyAsInt(ch);
                if (used + w > max && (used > 0 || piece.length() > 0)) {
                    if (piece.length() > 0) {
                        current.add(new HudData.Part(piece.toString(), part.color()));
                        piece.setLength(0);
                    }
                    out.add(current);
                    current = new ArrayList<>();
                    used = 0;
                }
                piece.append(ch);
                used += w;
                i += Character.charCount(cp);
            }
            if (piece.length() > 0) {
                current.add(new HudData.Part(piece.toString(), part.color()));
            }
        }
        if (!current.isEmpty()) {
            out.add(current);
        }
        return out;
    }

    /** 框的總高（上下內距 4 ＋ 每一行）。 */
    public static int totalHeight(final List<Row> rows) {
        int height = 4;
        for (final Row row : rows) {
            height += height(row);
        }
        return height;
    }

    /**
     * 2026-11-02 照站長：框不能超過 maxHeight（下面是側邊面板，版面一律不能疊）。放得下照原樣；放不下時前面那幾行（名稱、傳送門、清除、Boss）
     * 一定留，地圖詞綴、事件從後面開始拿掉，最後一行寫「…{hint}」（例如「…F6 展開」）；連這樣都放不下就改成收起的摘要。純函式（測試用）。
     *
     * @param collapsed 收起來的樣子（{@link #layout} 的 collapsed = true）
     * @param keep      前面一定要留的行數
     */
    public static List<Row> fit(final List<Row> rows, final List<Row> collapsed, final int keep, final int maxHeight, final String hint) {
        if (totalHeight(rows) <= maxHeight) {
            return rows;
        }
        final Row more = new Row(one("…" + hint, DIM), List.of(), -1, 0, true, true);
        final List<Row> out = new ArrayList<>();
        int used = 4 + height(more);
        for (int i = 0; i < rows.size(); i++) {
            final int h = height(rows.get(i));
            if (used + h > maxHeight) {
                if (i < keep) {
                    return collapsed; // 連必要的都放不下：摘要
                }
                break;
            }
            out.add(rows.get(i));
            used += h;
        }
        // 最後一行是「地圖詞綴」「事件」這種小標題（下面的內容都拿掉了）：一起拿掉
        while (out.size() > keep && out.getLast().right().isEmpty() && out.getLast().progress() < 0 && isTitle(out.getLast())) {
            out.removeLast();
        }
        out.add(more);
        return out;
    }

    private static boolean isTitle(final Row row) {
        if (row.left().size() != 1) {
            return false;
        }
        final String text = row.left().getFirst().text();
        return "地圖詞綴".equals(text) || "事件".equals(text);
    }

    /** 前面一定要留的行數：名稱、傳送門與探索、清除，有 Boss 再一行。 */
    public static int keepRows(final WorldCodec.MapPanel p) {
        return p == null || p == WorldCodec.CLEAR ? 0 : p.boss() != WorldCodec.BOSS_NONE ? 4 : 3;
    }

    /** 一行的高（GUI 像素）。 */
    public static int height(final Row row) {
        final int text = row.small() ? 7 : 10;
        return text + (row.progress() >= 0 ? 3 : 0);
    }
}
