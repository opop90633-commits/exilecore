package tw.exilecore.client.core;

import java.util.ArrayList;
import java.util.List;

/**
 * 2026-11-02 照站長（D 組）：原生畫面共用的框：滑鼠說明、左上角的資訊面板、下面的提示列、動作結果。純 Java（遊戲與預覽圖同一份）。
 */
public final class UiPanels {

    public static final int TOOLTIP_BG = 0xF0100A18;
    public static final int TOOLTIP_BORDER = 0xFF5B4A8C;
    public static final int PANEL_BG = 0xC8080A12;
    public static final int PANEL_BORDER = 0xFF3A3D58;
    public static final int GOLD = 0xFFF5C04A;

    private UiPanels() {
    }

    /** 每一行折好（空字串是空一行）。 */
    static List<List<Rich.Run>> layout(final UiCanvas canvas, final List<String> lines, final int maxWidth, final int fallback) {
        final List<List<Rich.Run>> out = new ArrayList<>();
        for (final String line : lines) {
            final List<Rich.Run> runs = Rich.parse(line, fallback);
            if (runs.isEmpty()) {
                out.add(List.of());
                continue;
            }
            out.addAll(Rich.wrap(runs, maxWidth, canvas::width));
        }
        return out;
    }

    /** 滑鼠說明：第一行是標題，放在滑鼠右下（放不下就往左、往上移）。 */
    public static void tooltip(final UiCanvas canvas, final List<String> lines, final double mx, final double my, final int screenW,
                               final int screenH) {
        if (lines.isEmpty()) {
            return;
        }
        final int maxWidth = Math.max(120, Math.min(280, screenW / 2 - 20));
        final List<List<Rich.Run>> laid = layout(canvas, lines, maxWidth, 0xFFFFFF);
        final int lineHeight = canvas.fontHeight() + 1;
        int width = 0;
        for (final List<Rich.Run> line : laid) {
            width = Math.max(width, canvas.width(line));
        }
        final int height = laid.size() * lineHeight + 2;
        double x = mx + 12;
        double y = my - 12;
        if (x + width + 8 > screenW) {
            x = Math.max(4, mx - 16 - width);
        }
        if (y + height + 8 > screenH) {
            y = Math.max(4, screenH - height - 8);
        }
        y = Math.max(4, y);
        canvas.rect(x - 4, y - 4, x + width + 4, y + height + 2, TOOLTIP_BG);
        canvas.frame(x - 4, y - 4, x + width + 4, y + height + 2, TOOLTIP_BORDER);
        double at = y;
        for (int i = 0; i < laid.size(); i++) {
            canvas.runs(laid.get(i), x, at, 1, true);
            at += lineHeight + (i == 0 ? 2 : 0);
        }
    }

    /** 左上角的面板：標題（金色）＋幾行；回傳面板下緣的 y。 */
    public static double panel(final UiCanvas canvas, final String title, final List<String> lines, final double x, final double y,
                               final int maxWidth) {
        final List<String> all = new ArrayList<>();
        all.add(title);
        all.addAll(lines);
        final List<List<Rich.Run>> laid = layout(canvas, all, maxWidth, 0xAAAAAA);
        final int lineHeight = canvas.fontHeight() + 1;
        int width = 0;
        for (final List<Rich.Run> line : laid) {
            width = Math.max(width, canvas.width(line));
        }
        final double bottom = y + laid.size() * lineHeight + 8;
        canvas.rect(x, y, x + width + 10, bottom, PANEL_BG);
        canvas.frame(x, y, x + width + 10, bottom, PANEL_BORDER);
        double at = y + 5;
        for (final List<Rich.Run> line : laid) {
            canvas.runs(line, x + 5, at, 1, true);
            at += lineHeight;
        }
        return bottom;
    }

    /** 最下面一行的操作提示（置中、半透明底）。 */
    public static void hint(final UiCanvas canvas, final String text, final int screenW, final int screenH) {
        final List<Rich.Run> runs = Rich.parse(text, 0xAAAAAA);
        final int width = canvas.width(runs);
        final double x = (screenW - width) / 2.0;
        final double y = screenH - canvas.fontHeight() - 6;
        canvas.rect(x - 6, y - 3, x + width + 6, y + canvas.fontHeight() + 3, PANEL_BG);
        canvas.runs(runs, x, y, 1, true);
    }

    /** 動作的結果（下面提示列的上面，置中；alpha 0～1 漸漸消失）。 */
    public static void toast(final UiCanvas canvas, final String message, final int screenW, final int screenH, final double alpha) {
        if (message == null || message.isEmpty() || alpha <= 0) {
            return;
        }
        final List<Rich.Run> runs = Rich.parse(message, 0xFFFFFF);
        final int width = canvas.width(runs);
        final double x = (screenW - width) / 2.0;
        final double y = screenH - 2 * canvas.fontHeight() - 22;
        final int a = (int) Math.round(Math.max(0, Math.min(1, alpha)) * 0xC8);
        canvas.rect(x - 8, y - 4, x + width + 8, y + canvas.fontHeight() + 4, (a << 24) | 0x080A12);
        final List<Rich.Run> faded = new ArrayList<>();
        for (final Rich.Run run : runs) {
            faded.add(run);
        }
        canvas.runs(faded, x, y, 1, true);
    }

    /** 一個假的按鈕（預覽圖用；遊戲裡是原版的 Button）。 */
    public static void button(final UiCanvas canvas, final String label, final double x, final double y, final double w, final double h) {
        canvas.rect(x, y, x + w, y + h, 0xFF2B2B3A);
        canvas.frame(x, y, x + w, y + h, 0xFF6A6A80);
        final int textWidth = canvas.width(label);
        canvas.text(label, x + (w - textWidth) / 2, y + (h - canvas.fontHeight()) / 2 + 1, 0xFFFFFFFF, 1, true);
    }

    /**
     * 2026-11-02 照站長（D 組）：確認框（退點、整條路配點）：畫面正中間偏下，下面兩個按鈕「確認」「取消」。
     * 回傳 {框 x0, y0, x1, y1, 確認 x0, y0, x1, y1, 取消 x0, y0, x1, y1}（點擊判斷用）。
     */
    public static double[] confirm(final UiCanvas canvas, final List<String> lines, final int screenW, final int screenH) {
        final List<List<Rich.Run>> laid = layout(canvas, lines, Math.min(320, screenW - 40), 0xFFFFFF);
        final int lineHeight = canvas.fontHeight() + 2;
        int width = 160;
        for (final List<Rich.Run> line : laid) {
            width = Math.max(width, canvas.width(line));
        }
        final double w = width + 20;
        final double h = laid.size() * lineHeight + 40;
        final double x0 = (screenW - w) / 2;
        final double y0 = Math.max(8, screenH * 0.62 - h / 2);
        canvas.rect(x0, y0, x0 + w, y0 + h, 0xF0100A18);
        canvas.frame(x0, y0, x0 + w, y0 + h, GOLD);
        double at = y0 + 8;
        for (final List<Rich.Run> line : laid) {
            canvas.runs(line, x0 + 10, at, 1, true);
            at += lineHeight;
        }
        final double by = y0 + h - 26;
        final double bw = 64;
        final double okX = x0 + w / 2 - bw - 6;
        final double noX = x0 + w / 2 + 6;
        button(canvas, "確認", okX, by, bw, 18);
        button(canvas, "取消", noX, by, bw, 18);
        return new double[] {x0, y0, x0 + w, y0 + h, okX, by, okX + bw, by + 18, noX, by, noX + bw, by + 18};
    }

    /** (x, y) 在 rects 的第 index 個方塊裡（{@link #confirm} 的回傳值：0 框、1 確認、2 取消）。 */
    public static boolean inside(final double[] rects, final int index, final double x, final double y) {
        return rects != null && x >= rects[index * 4] && x < rects[index * 4 + 2] && y >= rects[index * 4 + 1] && y < rects[index * 4 + 3];
    }

    /**
     * 2026-11-02 照站長：在畫面上直接選一樣東西的小清單（天賦樹的珠寶孔選珠寶）：畫面正中間，標題＋每行一個（hover 那一行亮），
     * 最下面「取消」。回傳 {框 x0,y0,x1,y1, 取消 x0,y0,x1,y1, 第 0 行 x0,y0,x1,y1, 第 1 行…}。
     */
    public static double[] picker(final UiCanvas canvas, final String title, final List<String> rows, final int hover, final int screenW,
                                  final int screenH) {
        final int rowH = canvas.fontHeight() + 6;
        int width = Math.max(180, canvas.width(Rich.parse(title, 0xFFFFFF)));
        for (final String row : rows) {
            width = Math.max(width, canvas.width(Rich.parse(row, 0xFFFFFF)) + 8);
        }
        width = Math.min(width, Math.max(180, screenW - 40));
        final int maxRows = Math.max(1, (screenH - 90) / rowH);
        final int shown = Math.min(rows.size(), maxRows);
        final double w = width + 20;
        final double h = 22 + Math.max(1, shown) * rowH + 30;
        final double x0 = (screenW - w) / 2;
        final double y0 = Math.max(8, (screenH - h) / 2);
        canvas.rect(x0, y0, x0 + w, y0 + h, 0xF0100A18);
        canvas.frame(x0, y0, x0 + w, y0 + h, 0xFFC77DFF);
        canvas.runs(Rich.parse(title, 0xFFFFFF), x0 + 10, y0 + 7, 1, true);
        final double[] out = new double[8 + shown * 4];
        double at = y0 + 22;
        for (int i = 0; i < shown; i++) {
            final double rx0 = x0 + 6;
            final double rx1 = x0 + w - 6;
            if (i == hover) {
                canvas.rect(rx0, at, rx1, at + rowH - 1, 0x60C77DFF);
            }
            canvas.runs(Rich.parse(rows.get(i), 0xFFFFFF), rx0 + 4, at + 3, 1, true);
            out[8 + i * 4] = rx0;
            out[9 + i * 4] = at;
            out[10 + i * 4] = rx1;
            out[11 + i * 4] = at + rowH - 1;
            at += rowH;
        }
        if (rows.isEmpty()) {
            canvas.runs(Rich.parse("<gray>背包和隨身背包裡都沒有珠寶", 0xFFFFFF), x0 + 10, at + 3, 1, true);
        }
        final double bw = 64;
        final double bx = x0 + (w - bw) / 2;
        final double by = y0 + h - 24;
        button(canvas, "取消", bx, by, bw, 18);
        out[0] = x0;
        out[1] = y0;
        out[2] = x0 + w;
        out[3] = y0 + h;
        out[4] = bx;
        out[5] = by;
        out[6] = bx + bw;
        out[7] = by + 18;
        return out;
    }

    /** {@link #picker} 的回傳值裡，(x, y) 在第幾行（沒有 -1）。 */
    public static int pickerRow(final double[] rects, final double x, final double y) {
        if (rects == null) {
            return -1;
        }
        for (int i = 0; 8 + i * 4 + 3 < rects.length; i++) {
            if (inside(rects, 2 + i, x, y)) {
                return i;
            }
        }
        return -1;
    }
}
