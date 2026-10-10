package tw.exilecore.chat;

import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.ComponentLike;
import net.kyori.adventure.text.TextComponent;
import net.kyori.adventure.text.TranslatableComponent;
import net.kyori.adventure.text.TranslationArgument;
import net.kyori.adventure.text.serializer.plain.PlainTextComponentSerializer;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * 原版訊息（死亡訊息）裡的玩家 ID 換成角色名稱。只用 Adventure，不依賴 Bukkit。
 */
public final class PlayerNames {

    private PlayerNames() {
    }

    /**
     * 把訊息裡「整段文字剛好是某個玩家 ID」的部分換成那個人的名字（翻譯的參數、子節點都會找；
     * 不會動到剛好含有那幾個字的其他文字）。換掉的那段保留原本的顏色與粗體這些（隊伍色），
     * 文字、滑過去、點擊、Shift 點擊照新的名字。沒有要換的回傳原本那個物件。
     *
     * @param names 玩家 ID → 要顯示的名字（角色名稱的元件，帶滑過去與點擊）
     */
    public static Component rename(final Component component, final Map<String, Component> names) {
        Component result = component;
        if (component instanceof TextComponent text && names.containsKey(text.content())) {
            final Component name = names.get(text.content());
            final String shown = name instanceof TextComponent plain && plain.children().isEmpty() ? plain.content()
                    : PlainTextComponentSerializer.plainText().serialize(name);
            result = text.content(shown).hoverEvent(name.hoverEvent()).clickEvent(name.clickEvent()).insertion(shown);
        }
        if (result instanceof TranslatableComponent translatable && !translatable.arguments().isEmpty()) {
            final List<ComponentLike> arguments = new ArrayList<>();
            boolean changed = false;
            for (final TranslationArgument argument : translatable.arguments()) {
                if (argument.value() instanceof Component value) {
                    final Component renamed = rename(value, names);
                    changed |= renamed != value;
                    arguments.add(TranslationArgument.component(renamed));
                } else {
                    arguments.add(argument);
                }
            }
            if (changed) {
                result = translatable.arguments(arguments);
            }
        }
        final List<Component> children = result.children();
        if (!children.isEmpty()) {
            final List<Component> renamedChildren = new ArrayList<>(children.size());
            boolean changed = false;
            for (final Component child : children) {
                final Component renamed = rename(child, names);
                changed |= renamed != child;
                renamedChildren.add(renamed);
            }
            if (changed) {
                result = result.children(renamedChildren);
            }
        }
        return result;
    }
}
