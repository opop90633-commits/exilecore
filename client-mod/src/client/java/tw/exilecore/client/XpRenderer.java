package tw.exilecore.client;

import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import tw.exilecore.client.core.HudData;
import tw.exilecore.client.core.XpRate;

/**
 * 2026-11-02 照站長（1.4）：有球時原版經驗條是角色經驗（1.3）：條上面用小字寫百分比（右邊）與模組自己算的每小時經驗（左邊，看了 30 秒以上才寫）。
 */
final class XpRenderer {

    private XpRenderer() {
    }

    static final XpRate RATE = new XpRate();

    static void observe(final HudData data) {
        if (data != null && data.self().level() > 0) {
            RATE.observe(System.currentTimeMillis(), data.self().level(), data.self().xp(), data.self().xpNeeded());
        }
    }

    static void extract(final GuiGraphicsExtractor g, final Font font, final HudData.Self self) {
        if (self.level() <= 0) {
            return;
        }
        final int left = g.guiWidth() / 2 - 91;
        final int y = g.guiHeight() - 29;
        final String percent = self.xpNeeded() <= 0 ? "滿級" : XpRate.percent(self.progress());
        text(g, font, percent, left + 181 - font.width(percent) * 0.5f, y, 0xFFFFFFFF);
        final double perHour = self.xpNeeded() <= 0 ? -1 : RATE.perHour(System.currentTimeMillis());
        if (perHour > 0) {
            text(g, font, XpRate.format(perHour) + "/時", left + 1, y, 0xFFC8F0A0);
        }
    }

    private static void text(final GuiGraphicsExtractor g, final Font font, final String text, final float x, final float y, final int color) {
        g.pose().pushMatrix();
        g.pose().translate(x, y);
        g.pose().scale(0.5f, 0.5f);
        g.text(font, text, 0, 0, color, true);
        g.pose().popMatrix();
    }
}
