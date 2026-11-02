package tw.exilecore.client;

import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.network.chat.MutableComponent;
import tw.exilecore.client.core.HudLayout;
import tw.exilecore.client.core.NoticeFeed;

import java.util.List;

/**
 * 2026-11-02 照站長（1.4）：提示列——伺服器的提示（exilecore:notice）疊在動作列上方、技能列更上面，最多 4 則（畫面矮的時候少一點，
 * 不碰到準心），新的在最下面，快到時間的淡出；警告（紅字）停久一點、左邊有一條紅線。這次之前是動作列那一行，新的提示整行蓋掉。
 */
final class NoticeRenderer {

    private NoticeRenderer() {
    }

    static final NoticeFeed FEED = new NoticeFeed();

    static void onPacket(final byte[] data) {
        final NoticeFeed.Packet packet = NoticeFeed.parse(data);
        if (packet != null) {
            FEED.add(packet, ExileCoreClient.clock(0));
        }
    }

    static void clear() {
        FEED.clear();
    }

    static void extract(final GuiGraphicsExtractor g, final Font font, final float partial) {
        final int lines = HudLayout.noticeLines(g.guiHeight());
        final List<NoticeFeed.Shown> shown = FEED.visible(ExileCoreClient.clock(partial), lines);
        if (shown.isEmpty()) {
            return;
        }
        final int maxWidth = HudLayout.noticeWidth(g.guiWidth()) - 8;
        final int cx = g.guiWidth() / 2;
        int y = g.guiHeight() - HudLayout.NOTICE_BOTTOM - HudLayout.NOTICE_LINE;
        for (int i = shown.size() - 1; i >= 0; i--, y -= HudLayout.NOTICE_LINE) {
            final NoticeFeed.Shown item = shown.get(i);
            final int text = Parts.alpha(0xFFFFFFFF, item.alpha());
            if (text == 0) {
                continue;
            }
            final MutableComponent line = Parts.fit(font, item.entry().parts(), maxWidth);
            final int width = font.width(line);
            final int left = cx - width / 2 - 4;
            g.fill(left, y, cx + (width + 1) / 2 + 4, y + HudLayout.NOTICE_LINE - 1, Parts.alpha(0x90000000, item.alpha()));
            if (item.entry().level() > 0) {
                g.fill(left, y, left + 2, y + HudLayout.NOTICE_LINE - 1, Parts.alpha(0xFFE03030, item.alpha()));
            }
            g.text(font, line, cx - width / 2, y + 1, text, true);
        }
    }
}
