package tw.exilecore.clientmod;

import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.TextComponent;
import net.kyori.adventure.text.TranslatableComponent;
import net.kyori.adventure.text.format.TextColor;

import java.util.ArrayList;
import java.util.List;

/**
 * 2026-11-02 照站長（D 組）：選單物品的名稱、說明（Adventure 的 Component）轉成模組看得懂的簡單文字：
 * 只有 {@code <#rrggbb>} 換顏色，{@code <} 寫成 {@code \<}、{@code \} 寫成 {@code \\}（跟模組的 Rich 認得的一樣）。原生畫面的星圖格子說明用這個，
 * 才不用把箱子選單寫好的說明再寫一次。
 */
public final class UiText {

    private UiText() {
    }

    /** 一行。沒有顏色的字照 fallback（0xRRGGBB）。 */
    public static String of(final Component component, final int fallback) {
        final StringBuilder out = new StringBuilder();
        final int[] current = {-1};
        walk(component, fallback, out, current);
        return out.toString();
    }

    public static List<String> lines(final List<Component> components, final int fallback) {
        final List<String> out = new ArrayList<>();
        if (components != null) {
            for (final Component component : components) {
                out.add(of(component, fallback));
            }
        }
        return out;
    }

    private static void walk(final Component component, final int inherited, final StringBuilder out, final int[] current) {
        final TextColor color = component.color();
        final int rgb = color == null ? inherited : color.value();
        final String text = component instanceof TextComponent t ? t.content()
                : component instanceof TranslatableComponent tr ? tr.fallback() == null ? tr.key() : tr.fallback() : "";
        if (!text.isEmpty()) {
            if (rgb != current[0]) {
                out.append(String.format("<#%06x>", rgb & 0xFFFFFF));
                current[0] = rgb;
            }
            out.append(text.replace("\\", "\\\\").replace("<", "\\<"));
        }
        for (final Component child : component.children()) {
            walk(child, rgb, out, current);
        }
    }
}
