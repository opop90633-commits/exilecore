package tw.exilecore.client.core;

/**
 * 2026-11-02 照站長（D 組）：原生畫面的鏡頭（世界座標 ↔ 畫面座標、滾輪縮放、拖曳移動、看全貌）。純 Java（測試用）。
 * 世界座標是插件送的（天賦樹是 PoE2 的座標、星圖是六角格算出來的），畫面座標是 GUI 像素；scale = 一個世界單位幾個像素。
 */
public final class Camera {

    /** 鏡頭中心（世界座標）。 */
    public double cx;
    public double cy;
    public double scale = 1;
    public double minScale = 0.001;
    public double maxScale = 100;
    /** 畫面上的中心點（GUI 像素）。 */
    public double screenCx;
    public double screenCy;

    public Camera() {
    }

    public Camera(final double cx, final double cy, final double scale) {
        this.cx = cx;
        this.cy = cy;
        this.scale = scale;
    }

    /** 畫面的大小（中心點擺在 (x + w/2, y + h/2)）。 */
    public void viewport(final double x, final double y, final double w, final double h) {
        screenCx = x + w / 2;
        screenCy = y + h / 2;
    }

    public double toScreenX(final double wx) {
        return screenCx + (wx - cx) * scale;
    }

    public double toScreenY(final double wy) {
        return screenCy + (wy - cy) * scale;
    }

    public double toWorldX(final double sx) {
        return cx + (sx - screenCx) / scale;
    }

    public double toWorldY(final double sy) {
        return cy + (sy - screenCy) / scale;
    }

    /** 拖曳：畫面移了 (dx, dy) 像素。 */
    public void pan(final double dx, final double dy) {
        cx -= dx / scale;
        cy -= dy / scale;
    }

    /** 以畫面上的 (sx, sy) 為中心縮放 factor 倍（滑鼠指著的地方不動），夾在 min～max 之間。 */
    public void zoomAt(final double sx, final double sy, final double factor) {
        final double wx = toWorldX(sx);
        final double wy = toWorldY(sy);
        scale = clamp(scale * factor, minScale, maxScale);
        cx = wx - (sx - screenCx) / scale;
        cy = wy - (sy - screenCy) / scale;
    }

    /** 看全貌：世界的範圍塞進 w × h 像素（留 margin 的邊）。回傳算出來的 scale。 */
    public double fit(final double minX, final double minY, final double maxX, final double maxY, final double w, final double h,
                      final double margin) {
        final double spanX = Math.max(1e-6, maxX - minX);
        final double spanY = Math.max(1e-6, maxY - minY);
        final double usableW = Math.max(1, w - 2 * margin);
        final double usableH = Math.max(1, h - 2 * margin);
        cx = (minX + maxX) / 2;
        cy = (minY + maxY) / 2;
        scale = clamp(Math.min(usableW / spanX, usableH / spanY), minScale, maxScale);
        return scale;
    }

    /** 鏡頭中心不要跑出世界的範圍太遠（拖到看不見任何東西）。 */
    public void keepInside(final double minX, final double minY, final double maxX, final double maxY) {
        cx = clamp(cx, minX, maxX);
        cy = clamp(cy, minY, maxY);
    }

    public void center(final double wx, final double wy) {
        cx = wx;
        cy = wy;
    }

    static double clamp(final double value, final double min, final double max) {
        return Math.max(min, Math.min(max, value));
    }
}
