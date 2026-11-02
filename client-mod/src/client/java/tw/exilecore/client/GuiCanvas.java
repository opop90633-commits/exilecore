package tw.exilecore.client;

import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import tw.exilecore.client.core.UiCanvas;

/**
 * 2026-11-02 照站長（D 組）：{@link UiCanvas} 接到 26.2 的 GuiGraphicsExtractor（原生畫面的天賦樹、星圖都照 core 的 Painter 畫）。
 * 斜的線、六角形用 pose 轉一個 1×1 的方塊畫（fill 只收整數座標，用 pose 的縮放、旋轉才畫得出小數與角度）。
 */
final class GuiCanvas implements UiCanvas {

    private final GuiGraphicsExtractor g;
    private final Font font;

    GuiCanvas(final GuiGraphicsExtractor g, final Font font) {
        this.g = g;
        this.font = font;
    }

    @Override
    public void rect(final double x0, final double y0, final double x1, final double y1, final int argb) {
        if (x1 <= x0 || y1 <= y0) {
            return;
        }
        if (x0 == Math.rint(x0) && y0 == Math.rint(y0) && x1 == Math.rint(x1) && y1 == Math.rint(y1)) {
            g.fill((int) x0, (int) y0, (int) x1, (int) y1, argb);
            return;
        }
        g.pose().pushMatrix();
        g.pose().translate((float) x0, (float) y0);
        g.pose().scale((float) (x1 - x0), (float) (y1 - y0));
        g.fill(0, 0, 1, 1, argb);
        g.pose().popMatrix();
    }

    @Override
    public void quad(final double cx, final double cy, final double w, final double h, final double angle, final int argb) {
        if (w <= 0 || h <= 0) {
            return;
        }
        g.pose().pushMatrix();
        g.pose().translate((float) cx, (float) cy);
        if (angle != 0) {
            g.pose().rotate((float) angle);
        }
        g.pose().scale((float) w, (float) h);
        g.pose().translate(-0.5f, -0.5f);
        g.fill(0, 0, 1, 1, argb);
        g.pose().popMatrix();
    }

    @Override
    public void text(final String text, final double x, final double y, final int argb, final double scale, final boolean shadow) {
        if (text.isEmpty()) {
            return;
        }
        g.pose().pushMatrix();
        g.pose().translate((float) x, (float) y);
        if (scale != 1) {
            g.pose().scale((float) scale, (float) scale);
        }
        g.text(font, text, 0, 0, argb, shadow);
        g.pose().popMatrix();
    }

    @Override
    public int width(final String text) {
        return font.width(text);
    }

    @Override
    public int fontHeight() {
        return font.lineHeight;
    }

    @Override
    public void clip(final int x0, final int y0, final int x1, final int y1) {
        g.enableScissor(x0, y0, x1, y1);
    }

    @Override
    public void unclip() {
        g.disableScissor();
    }
}
