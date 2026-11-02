package tw.exilecore.clientmod;

import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.flattener.ComponentFlattener;
import net.kyori.adventure.text.flattener.FlattenerListener;
import net.kyori.adventure.text.format.Style;
import net.kyori.adventure.text.format.TextColor;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.List;

/**
 * 2026-11-02 照站長（模組 1.4）：已經解析好的文字元件（動作列的提示，{@link tw.exilecore.gui.ActionBar#send}）拆成「一段字、一個顏色」，
 * 跟 {@link MiniParts} 一樣的格式（送給模組的提示列）。顏色照外層繼承，沒有顏色的是白色。只依賴 Adventure（測試用）。
 */
public final class ComponentParts {

    private ComponentParts() {
    }

    public static List<MiniParts.Part> of(final Component component) {
        final List<MiniParts.Part> parts = new ArrayList<>();
        if (component == null) {
            return parts;
        }
        final Deque<Integer> colors = new ArrayDeque<>();
        colors.push(MiniParts.DEFAULT_COLOR);
        ComponentFlattener.basic().flatten(component, new FlattenerListener() {
            @Override
            public void pushStyle(final Style style) {
                final TextColor color = style.color();
                colors.push(color == null ? colors.peek() : color.value());
            }

            @Override
            public void popStyle(final Style style) {
                if (colors.size() > 1) {
                    colors.pop();
                }
            }

            @Override
            public void component(final String text) {
                if (text.isEmpty()) {
                    return;
                }
                final int color = colors.peek();
                if (!parts.isEmpty() && parts.getLast().color() == color) {
                    final MiniParts.Part last = parts.removeLast();
                    parts.add(new MiniParts.Part(last.text() + text, color));
                } else {
                    parts.add(new MiniParts.Part(text, color));
                }
            }
        });
        return parts;
    }
}
