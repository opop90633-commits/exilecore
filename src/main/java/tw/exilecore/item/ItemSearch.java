package tw.exilecore.item;

import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.serializer.plain.PlainTextComponentSerializer;
import org.bukkit.inventory.ItemStack;

import java.util.List;
import java.util.Locale;
import java.util.Optional;

/** 物品給搜尋用的文字（市集、倉庫）：名稱、說明（含詞綴）、稀有度、基底名稱，全部小寫。 */
public final class ItemSearch {

    private ItemSearch() {
    }

    public static String text(final ItemService items, final ItemStack stack) {
        if (stack == null || stack.isEmpty()) {
            return "";
        }
        final StringBuilder text = new StringBuilder();
        text.append(plain(stack.effectiveName())).append('\n');
        final List<Component> lore = stack.lore();
        if (lore != null) {
            for (final Component line : lore) {
                text.append(plain(line)).append('\n');
            }
        }
        final Optional<ItemData> data = items.read(stack);
        if (data.isPresent()) {
            text.append(data.get().rarity().displayName()).append('\n');
            final ItemBase base = items.repository().base(data.get().base());
            if (base != null) {
                text.append(base.name()).append('\n');
            }
        }
        return text.toString().toLowerCase(Locale.ROOT);
    }

    private static String plain(final Component component) {
        return AffixChips.plain(PlainTextComponentSerializer.plainText().serialize(component)); // 2026-10：詞綴標籤也搜得到
    }
}
