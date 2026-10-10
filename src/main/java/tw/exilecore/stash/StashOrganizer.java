package tw.exilecore.stash;

import net.kyori.adventure.text.serializer.plain.PlainTextComponentSerializer;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;
import tw.exilecore.ExileCore;
import tw.exilecore.currency.CurrencyType;
import tw.exilecore.item.ItemBase;
import tw.exilecore.item.ItemData;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * 整理與「收進倉庫」：把物品分類（{@link StashCategory}）、疊在一起、照 {@link StashLayout} 排好或分到各頁。
 * 倉庫與隨身背包共用。只在主執行緒使用。
 */
public final class StashOrganizer {

    /** 一排格子（背包、倉庫的一頁、隨身背包的一頁）。 */
    public interface Slots {
        int size();

        ItemStack get(int index);

        void set(int index, ItemStack stack);

        static Slots of(final ItemStack[] array) {
            return new Slots() {
                @Override
                public int size() {
                    return array.length;
                }

                @Override
                public ItemStack get(final int index) {
                    return array[index];
                }

                @Override
                public void set(final int index, final ItemStack stack) {
                    array[index] = stack == null || stack.isEmpty() ? null : stack;
                }
            };
        }
    }

    /**
     * 收進倉庫的結果。
     *
     * @param stacks   搬了幾格物品
     * @param currency 存了幾個通貨
     * @param leftover 放不下的格數（沒有對應的頁、頁滿了、通貨存滿了）
     * @param noPage   放不下的東西裡，沒有對應頁籤的分類（給玩家看的提示）
     */
    public record Deposit(int stacks, int currency, int leftover, List<StashCategory> noPage) {
        public boolean nothing() {
            return stacks == 0 && currency == 0;
        }
    }

    private final ExileCore plugin;

    public StashOrganizer(final ExileCore plugin) {
        this.plugin = plugin;
    }

    // ---- 分類 ----

    /** 物品的分類。 */
    public StashCategory category(final ItemStack stack) {
        if (stack == null || stack.isEmpty()) {
            return StashCategory.OTHER;
        }
        final Optional<ItemData> data = plugin.items().read(stack);
        if (data.isPresent()) {
            final ItemBase base = plugin.items().repository().base(data.get().base());
            if (base == null) {
                return StashCategory.OTHER;
            }
            if (base.isMap()) {
                return StashCategory.MAP;
            }
            if (base.isJewel()) {
                return StashCategory.JEWEL;
            }
            // 2026-10：照裝備欄位分細
            return switch (base.slot()) {
                case MAIN_HAND -> StashCategory.WEAPON;
                case OFF_HAND -> StashCategory.OFF_HAND;
                case HEAD -> StashCategory.HELMET;
                case CHEST -> StashCategory.BODY;
                case LEGS -> StashCategory.LEGS;
                case FEET -> StashCategory.BOOTS;
                case RING -> StashCategory.RING;
                case AMULET -> StashCategory.AMULET;
                case BELT -> StashCategory.BELT;
                default -> base.isWeapon() ? StashCategory.WEAPON : StashCategory.OTHER;
            };
        }
        if (plugin.skills() != null && plugin.skills().gems().isGem(stack)) {
            return StashCategory.GEM;
        }
        if (plugin.flasks() != null && plugin.flasks().charms() != null && plugin.flasks().charms().isCharm(stack)) {
            return StashCategory.CHARM;
        }
        if (plugin.flasks() != null && plugin.flasks().isFlask(stack)) {
            return StashCategory.FLASK;
        }
        if (plugin.currencies().isCurrency(stack)) {
            return StashCategory.CURRENCY;
        }
        return StashCategory.OTHER;
    }

    /** 排序用的資料。 */
    public StashLayout.Entry<ItemStack> entry(final ItemStack stack) {
        final StashCategory category = category(stack);
        final String name = plainName(stack);
        final Optional<ItemData> data = plugin.items().read(stack);
        if (data.isPresent()) {
            return new StashLayout.Entry<>(stack, category, data.get().rarity().ordinal(), data.get().ilvl(), name, stack.getAmount());
        }
        if (category == StashCategory.GEM) {
            final int level = plugin.skills().gems().read(stack).map(gem -> gem.level()).orElse(0);
            return new StashLayout.Entry<>(stack, category, 0, level, name, stack.getAmount());
        }
        if (category == StashCategory.FLASK) {
            final int level = plugin.flasks().read(stack).map(flask -> plugin.flasks().config().base(flask.base()))
                    .map(base -> base == null ? 0 : base.level()).orElse(0);
            return new StashLayout.Entry<>(stack, category, 0, level, name, stack.getAmount());
        }
        return new StashLayout.Entry<>(stack, category, 0, 0, category == StashCategory.OTHER ? stack.getType().name() + name : name,
                stack.getAmount());
    }

    public static String plainName(final ItemStack item) {
        final ItemMeta meta = item.getItemMeta();
        if (meta != null && meta.hasDisplayName() && meta.displayName() != null) {
            return PlainTextComponentSerializer.plainText().serialize(meta.displayName());
        }
        return item.getType().name();
    }

    // ---- 整理 ----

    /** 拿出這幾排格子裡所有東西、同樣的疊在一起（格子會清空）。 */
    public static List<ItemStack> takeAll(final List<Slots> rows) {
        final List<ItemStack> items = new ArrayList<>();
        for (final Slots row : rows) {
            for (int i = 0; i < row.size(); i++) {
                final ItemStack stack = row.get(i);
                if (stack == null || stack.isEmpty()) {
                    continue;
                }
                ItemStack rest = stack;
                for (int j = 0; j < items.size() && rest != null; j++) {
                    final ItemStack existing = items.get(j);
                    if (existing.isSimilar(rest) && existing.getAmount() < existing.getMaxStackSize()) {
                        final int moved = Math.min(existing.getMaxStackSize() - existing.getAmount(), rest.getAmount());
                        items.set(j, existing.asQuantity(existing.getAmount() + moved));
                        rest = moved >= rest.getAmount() ? null : rest.asQuantity(rest.getAmount() - moved);
                    }
                }
                if (rest != null) {
                    items.add(rest);
                }
                row.set(i, null);
            }
        }
        return items;
    }

    /** 這幾排格子（隨身背包的兩頁、倉庫的一頁）：疊在一起、照規則排好，從第一排開始放。 */
    public void sort(final List<Slots> rows) {
        final List<StashLayout.Entry<ItemStack>> entries = new ArrayList<>();
        for (final ItemStack item : takeAll(rows)) {
            entries.add(entry(item));
        }
        int row = 0;
        int index = 0;
        for (final StashLayout.Entry<ItemStack> entry : StashLayout.sorted(entries)) {
            while (index >= rows.get(row).size()) {
                row++;
                index = 0;
            }
            rows.get(row).set(index++, entry.item());
        }
    }

    /** 整理倉庫全部的物品頁：照頁籤的分類分到各頁，每頁排好。 */
    public void sortAll(final Stash stash) {
        final List<Slots> rows = new ArrayList<>();
        for (int page = 0; page < stash.pageCount(); page++) {
            rows.add(Slots.of(stash.page(page)));
        }
        final List<StashLayout.Entry<ItemStack>> entries = new ArrayList<>();
        for (final ItemStack item : takeAll(rows)) {
            entries.add(entry(item));
        }
        final List<List<StashLayout.Entry<ItemStack>>> pages = StashLayout.distribute(entries, stash.tabCategories(), Stash.PAGE_SIZE);
        for (int page = 0; page < pages.size(); page++) {
            final ItemStack[] target = stash.page(page);
            int index = 0;
            for (final StashLayout.Entry<ItemStack> entry : pages.get(page)) {
                target[index++] = entry.item();
            }
        }
    }

    // ---- 收進倉庫 ----

    /**
     * 把這排格子裡的東西收進倉庫：通貨進通貨頁，其他照分類放（分類相同的頁、再來是「全部」的頁），先疊到同樣的東西上再放空格。
     * 放不下的留在原來的格子。
     */
    public Deposit deposit(final Stash stash, final Slots source) {
        int stacks = 0;
        int currency = 0;
        int leftover = 0;
        final List<StashCategory> noPage = new ArrayList<>();
        // 收回的頁（2026-10）不放東西進去
        final List<StashCategory> tabs = stash.tabCategories().subList(0, stash.usablePages());
        for (int i = 0; i < source.size(); i++) {
            final ItemStack stack = source.get(i);
            if (stack == null || stack.isEmpty()) {
                continue;
            }
            ItemStack rest = stack;
            final Optional<CurrencyType> type = plugin.currencies().read(stack);
            if (type.isPresent()) {
                final int accepted = stash.depositCurrency(type.get().id(), stack.getAmount());
                currency += accepted;
                rest = accepted >= stack.getAmount() ? null : stack.asQuantity(stack.getAmount() - accepted);
            } else {
                final StashCategory category = category(stack);
                if (category == StashCategory.MAP && stash.depositMap(stack)) {
                    // 地圖先進地圖頁（滿了才照分類放物品頁）
                    stacks++;
                    source.set(i, null);
                    continue;
                }
                final List<Integer> pages = StashLayout.depositOrder(category, tabs);
                if (pages.isEmpty() && !noPage.contains(category)) {
                    noPage.add(category);
                }
                for (final int page : pages) {
                    rest = mergeInto(stash.page(page), rest);
                    if (rest == null) {
                        break;
                    }
                }
                for (final int page : pages) {
                    if (rest == null) {
                        break;
                    }
                    rest = fillEmpty(stash.page(page), rest);
                }
                if (rest == null || rest.getAmount() < stack.getAmount()) {
                    stacks++;
                }
            }
            if (rest != null) {
                leftover++;
            }
            source.set(i, rest);
        }
        return new Deposit(stacks, currency, leftover, noPage);
    }

    /** 通貨袋裡的通貨全部存進倉庫，回傳存了幾個（存滿的種類留在袋子裡）。 */
    public static int depositPouch(final Stash stash, final tw.exilecore.character.CurrencyPouch pouch) {
        int total = 0;
        for (final Map.Entry<String, Integer> entry : Map.copyOf(pouch.all()).entrySet()) {
            final int accepted = stash.depositCurrency(entry.getKey(), entry.getValue());
            if (accepted > 0) {
                pouch.withdraw(entry.getKey(), accepted);
                total += accepted;
            }
        }
        return total;
    }

    /** 疊到一頁裡同樣的東西上。 */
    static ItemStack mergeInto(final ItemStack[] slots, final ItemStack stack) {
        ItemStack rest = stack;
        for (int i = 0; i < slots.length && rest != null; i++) {
            final ItemStack current = slots[i];
            if (current == null || current.isEmpty() || !current.isSimilar(rest)) {
                continue;
            }
            final int room = current.getMaxStackSize() - current.getAmount();
            if (room <= 0) {
                continue;
            }
            final int moved = Math.min(room, rest.getAmount());
            slots[i] = current.asQuantity(current.getAmount() + moved);
            rest = moved >= rest.getAmount() ? null : rest.asQuantity(rest.getAmount() - moved);
        }
        return rest;
    }

    /** 放進一頁的空格。 */
    static ItemStack fillEmpty(final ItemStack[] slots, final ItemStack stack) {
        ItemStack rest = stack;
        for (int i = 0; i < slots.length && rest != null; i++) {
            if (slots[i] != null && !slots[i].isEmpty()) {
                continue;
            }
            final int moved = Math.min(rest.getMaxStackSize(), rest.getAmount());
            slots[i] = rest.asQuantity(moved);
            rest = moved >= rest.getAmount() ? null : rest.asQuantity(rest.getAmount() - moved);
        }
        return rest;
    }
}
