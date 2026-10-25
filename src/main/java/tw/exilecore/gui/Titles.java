package tw.exilecore.gui;

import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import net.kyori.adventure.text.format.TextColor;
import net.kyori.adventure.text.serializer.plain.PlainTextComponentSerializer;
import net.kyori.adventure.title.Title;
import net.kyori.adventure.title.TitlePart;
import org.bukkit.entity.Player;

/**
 * 畫面中間的大標題＋小標題（2026-10-26）。
 * <p>
 * 客戶端「協助工具 → 文字背景」設成「所有地方」的玩家，每一段標題後面都會畫一塊半透明的底——
 * 空的那一段也照畫，畫面正中間就留一個沒有字的框。所以：
 * <ul>
 *   <li>小標題是空的：只送大標題（先清掉上一個的小標題），不送空的小標題。</li>
 *   <li>大標題是空的：原版沒有大標題就不畫小標題，只能放東西——放一個 ✦（小標題的顏色），不要留空框。
 *       呼叫端最好自己把一句話拆成「短的大標題＋說明的小標題」。</li>
 * </ul>
 */
public final class Titles {

    private Titles() {
    }

    /** 沒有字（null、空字串、只有空白）。 */
    public static boolean blank(final Component component) {
        return component == null || PlainTextComponentSerializer.plainText().serialize(component).isBlank();
    }

    public static void show(final Player player, final Component main, final Component sub, final Title.Times times) {
        final boolean noMain = blank(main);
        final boolean noSub = blank(sub);
        if (noMain && noSub) {
            return;
        }
        if (noSub) {
            player.resetTitle(); // 清掉上一個標題留下來的小標題（不然會跟著這個大標題一起出現）
            player.sendTitlePart(TitlePart.TIMES, times);
            player.sendTitlePart(TitlePart.TITLE, main);
            return;
        }
        final Component shownMain = noMain ? Component.text("✦", colorOf(sub)) : main;
        player.showTitle(Title.title(shownMain, sub, times));
    }

    public static void show(final Player player, final Title title) {
        show(player, title.title(), title.subtitle(), title.times() == null ? Title.DEFAULT_TIMES : title.times());
    }

    /** 小標題第一個有顏色的地方的顏色（拿來畫 ✦）；都沒有用灰色。 */
    private static TextColor colorOf(final Component component) {
        if (component.color() != null) {
            return component.color();
        }
        for (final Component child : component.children()) {
            final TextColor color = child.color();
            if (color != null) {
                return color;
            }
        }
        return NamedTextColor.GRAY;
    }
}
