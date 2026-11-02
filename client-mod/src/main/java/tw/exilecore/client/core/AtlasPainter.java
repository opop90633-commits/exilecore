package tw.exilecore.client.core;

import java.util.ArrayList;
import java.util.List;

/**
 * 2026-11-02 照站長（D 組）：星圖原生畫面怎麼畫（遊戲與預覽圖同一份）。純 Java。
 * 真正的六角格（尖頂朝上）；顏色跟資源包版的星圖一樣：完成的金色、Boss 偏紅、塔淡褐、城塞紫、迷霧暗灰；
 * 腐化區紫圈、淨化區淡金圈、被石板罩到淡褐圈（塔範圍內但還沒插石板的暗一點）、塔範圍的大外框、指定下次開的格子青色框。
 */
public final class AtlasPainter {

    public static final int GOLD = 0xFFF5C04A;
    public static final int BRIGHT = 0xFFE8E8F2;
    public static final int EDGE_LIT = 0xFF9C9CB8;
    public static final int TOWER = 0xFFD8C8A0;
    public static final int CITADEL = 0xFFC48BFF;
    public static final int BOSS = 0xFFE8604A;
    public static final int CORRUPT = 0xFFB07AFF;
    public static final int PURIFY = 0xFFFFF1B8;
    public static final int TABLET = 0xFFD8C8A0;
    public static final int TOWER_RANGE = 0xFF7A6A48;
    public static final int BORDER = 0xFFFFD27A;
    public static final int CYAN = 0xFF55FFFF;

    private AtlasPainter() {
    }

    static int fill(final AtlasModel.Cell cell) {
        if (cell.fog()) {
            return 0xFF1C1D27;
        }
        return switch (cell.kind()) {
            case "camp" -> 0xFF2E3550;
            case "boss" -> cell.done() ? 0xFF4A3A18 : 0xFF46201A;
            case "tower" -> cell.done() ? 0xFF4A3A18 : 0xFF3A3424;
            case "citadel" -> cell.done() ? 0xFF4A3A18 : 0xFF33244A;
            default -> cell.done() ? 0xFF4A3A18 : 0xFF262839;
        };
    }

    static int symbol(final AtlasModel.Cell cell) {
        if (cell.done() && !"camp".equals(cell.kind())) {
            return GOLD;
        }
        return switch (cell.kind()) {
            case "camp" -> BRIGHT;
            case "boss" -> BOSS;
            case "tower" -> TOWER;
            case "citadel" -> CITADEL;
            case "fog" -> 0xFF3A3B4A;
            default -> BRIGHT;
        };
    }

    static int ring(final String ring) {
        return switch (ring) {
            case "corrupt" -> CORRUPT;
            case "purify" -> PURIFY;
            case "tablet" -> TABLET;
            case "range" -> TOWER_RANGE;
            default -> 0;
        };
    }

    static String glyph(final AtlasModel.Cell cell) {
        return switch (cell.kind()) {
            case "camp" -> "營";
            case "boss" -> "☠";
            case "tower" -> "塔";
            case "citadel" -> "城";
            default -> "";
        };
    }

    public static void paint(final UiCanvas canvas, final AtlasModel model, final Camera camera, final AtlasModel.Cell hover, final int x0,
                             final int y0, final int x1, final int y1) {
        final double r = camera.scale;
        final double width = Math.max(1, r * 0.1);
        // 連線（解鎖的鄰居之間）
        for (final AtlasModel.Cell cell : model.cells) {
            if (cell.fog()) {
                continue;
            }
            final double cx = camera.toScreenX(HexGrid.x(cell.q(), cell.r()));
            final double cy = camera.toScreenY(HexGrid.y(cell.q(), cell.r()));
            if (cx < x0 - 3 * r || cx > x1 + 3 * r || cy < y0 - 3 * r || cy > y1 + 3 * r) {
                continue;
            }
            for (int d = 0; d < 3; d++) {
                final AtlasModel.Cell next = model.at(cell.q() + HexGrid.DIRECTIONS[d][0], cell.r() + HexGrid.DIRECTIONS[d][1]);
                if (!AtlasModel.linked(cell, next)) {
                    continue;
                }
                final double nx = camera.toScreenX(HexGrid.x(next.q(), next.r()));
                final double ny = camera.toScreenY(HexGrid.y(next.q(), next.r()));
                canvas.line(cx, cy, nx, ny, width, cell.done() && next.done() ? 0xFFE6A82E : EDGE_LIT);
            }
        }
        // 格子
        for (final AtlasModel.Cell cell : model.cells) {
            final double cx = camera.toScreenX(HexGrid.x(cell.q(), cell.r()));
            final double cy = camera.toScreenY(HexGrid.y(cell.q(), cell.r()));
            if (cx < x0 - r || cx > x1 + r || cy < y0 - r || cy > y1 + r) {
                continue;
            }
            canvas.hex(cx, cy, r * 0.84, fill(cell));
            if (cell.done() && !cell.fog()) {
                canvas.hexOutline(cx, cy, r * 0.84, Math.max(1, r * 0.06), 0xC0F5C04A);
            }
            final int ringColor = ring(cell.ring());
            if (ringColor != 0 && !cell.fog()) {
                canvas.hexOutline(cx, cy, r * 0.68, Math.max(1, r * 0.08), ringColor);
            }
            if (!cell.fog()) {
                canvas.dot(cx, cy, Math.max(1, r * 0.22), symbol(cell));
            }
            if (cell.chosen()) {
                canvas.hexOutline(cx, cy, r * 0.94, Math.max(1.5, r * 0.09), CYAN);
            }
            if (cell == hover) {
                canvas.hexOutline(cx, cy, r * 0.9, Math.max(1, r * 0.07), 0xFFFFFFFF);
            }
            if (r >= 13 && !cell.fog()) {
                final String glyph = glyph(cell);
                final double scale = r >= 22 ? 1 : 0.75;
                if (!glyph.isEmpty()) {
                    canvas.text(glyph, cx - canvas.width(glyph) * scale / 2, cy - r * 0.62, 0xFFFFFFFF, scale, true);
                }
                if (cell.tier() > 0) {
                    final String tier = "T" + cell.tier();
                    canvas.text(tier, cx - canvas.width(tier) * scale / 2, cy + r * 0.28, cell.done() ? 0xFFF5D58A : 0xFFD0D0E0, scale, true);
                }
            }
        }
        // 塔範圍的大外框
        for (final int[] at : model.rangeCells()) {
            final double cx = camera.toScreenX(HexGrid.x(at[0], at[1]));
            final double cy = camera.toScreenY(HexGrid.y(at[0], at[1]));
            if (cx < x0 - r || cx > x1 + r || cy < y0 - r || cy > y1 + r) {
                continue;
            }
            for (int d = 0; d < 6; d++) {
                if (model.border(at[0], at[1], d)) {
                    final double[] edge = HexGrid.edge(d, r);
                    canvas.line(cx + edge[0], cy + edge[1], cx + edge[2], cy + edge[3], Math.max(1.5, r * 0.1), BORDER);
                }
            }
        }
    }

    /** 滑鼠指著的格子的說明（插件照箱子選單寫好的）。 */
    public static List<String> tooltip(final AtlasModel.Cell cell) {
        final List<String> lines = new ArrayList<>();
        lines.add(cell.name());
        lines.addAll(cell.lore());
        return lines;
    }

    /** 世界座標落在哪一格（沒有格子回傳 null）。 */
    public static AtlasModel.Cell at(final AtlasModel model, final double wx, final double wy) {
        final int[] qr = HexGrid.at(wx, wy);
        return model.at(qr[0], qr[1]);
    }
}
