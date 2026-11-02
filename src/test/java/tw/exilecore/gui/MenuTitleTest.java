package tw.exilecore.gui;

import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import net.kyori.adventure.text.minimessage.MiniMessage;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

/** 2026-11-02 照站長：箱子標題統一顏色；資源包背景（有字型的那段）不染色。 */
class MenuTitleTest {

    @Test
    void recolorsTextButKeepsFontSegments() {
        final Component title = MiniMessage.miniMessage().deserialize("<white><font:exilecore:menu>X</font></white><#d8c8a0>塔與<aqua>城塞");
        final Component out = Menu.uniformTitle(title, false);
        final StringBuilder colors = new StringBuilder();
        walk(out, false, colors);
        assertEquals("font:white;塔與:title;城塞:title;", colors.toString());
    }

    private static void walk(final Component c, final boolean inFont, final StringBuilder out) {
        final boolean font = inFont || c.font() != null;
        if (c instanceof net.kyori.adventure.text.TextComponent text && !text.content().isEmpty()) {
            out.append(font ? "font" : text.content()).append(':')
                    .append(Menu.TITLE_COLOR.equals(c.color()) ? "title" : NamedTextColor.WHITE.equals(c.color()) || c.color() == null ? "white" : c.color().asHexString())
                    .append(';');
        }
        for (final Component child : c.children()) {
            walk(child, font, out);
        }
    }
}
