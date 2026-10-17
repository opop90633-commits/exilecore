package tw.exilecore.character;

import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.PlayerInventory;

import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 背包、飾品欄位、隨身背包、天賦樹珠寶與資料庫 byte[] 之間的轉換。用原版的 ItemStack 序列化（含資料版本），
 * 伺服器升版時原版會自己轉換舊物品。
 */
public final class InventoryCodec {

    private InventoryCodec() {
    }

    /**
     * 主執行緒呼叫：把整個背包（含防具、副手）轉成 byte[]。
     * 游標上拿著的東西（開著倉庫等視窗時）放進第一個空格一起存：存檔後剛好當機，它也不會因為「拿在手上」而消失。
     */
    public static byte[] encodeInventory(final Player player) {
        final ItemStack[] contents = player.getInventory().getContents();
        final ItemStack cursor = player.getItemOnCursor();
        if (!cursor.isEmpty()) {
            for (int i = 0; i < Math.min(36, contents.length); i++) {
                if (contents[i] == null || contents[i].isEmpty()) {
                    contents[i] = cursor.clone();
                    break;
                }
            }
        }
        return ItemStack.serializeItemsAsBytes(nullsToEmpty(contents));
    }

    public static byte[] encodeJewellery(final ItemStack[] jewellery) {
        return ItemStack.serializeItemsAsBytes(nullsToEmpty(Arrays.copyOf(jewellery, jewellery.length)));
    }

    /** 主執行緒呼叫：把 byte[] 套回背包；null 代表沒存過，背包清空。 */
    public static void applyInventory(final Player player, final byte[] bytes) {
        final PlayerInventory inventory = player.getInventory();
        inventory.clear();
        player.setItemOnCursor(null);
        if (bytes == null || bytes.length == 0) {
            return;
        }
        final ItemStack[] items = ItemStack.deserializeItemsFromBytes(bytes);
        final int size = Math.min(items.length, inventory.getSize());
        for (int i = 0; i < size; i++) {
            final ItemStack item = items[i];
            inventory.setItem(i, item == null || item.isEmpty() ? null : item);
        }
    }

    public static ItemStack[] decodeJewellery(final byte[] bytes, final int slots) {
        final ItemStack[] result = new ItemStack[slots];
        if (bytes == null || bytes.length == 0) {
            return result;
        }
        final ItemStack[] items = ItemStack.deserializeItemsFromBytes(bytes);
        for (int i = 0; i < Math.min(slots, items.length); i++) {
            result[i] = items[i] == null || items[i].isEmpty() ? null : items[i];
        }
        return result;
    }

    /** 隨身背包的物品（所有頁攤平）轉成 byte[]；通貨袋另外存成字串（{@link CurrencyPouch#encode()}）。 */
    public static byte[] encodeBag(final Bag bag) {
        return encodeBag(bag, null);
    }

    /**
     * 同上，extra（游標上放不進背包的東西）放進存檔裡第一個空格；隨身背包本身不動。
     * 隨身背包也滿了就只好不存它（跟原版一樣，關掉視窗時會掉在腳下）。
     */
    public static byte[] encodeBag(final Bag bag, final ItemStack extra) {
        final ItemStack[] all = bag.flatten();
        if (extra != null && !extra.isEmpty()) {
            for (int i = 0; i < all.length; i++) {
                if (all[i] == null) {
                    all[i] = extra.clone();
                    break;
                }
            }
        }
        return ItemStack.serializeItemsAsBytes(nullsToEmpty(all));
    }

    /**
     * 天賦樹珠寶孔裡的珠寶（節點代號 → 珠寶）轉成 byte[]：
     * 先是「節點代號」的清單（UTF-8，用換行分開、長度在前），再接著用原版序列化的珠寶陣列（順序一樣）。
     * 空的回傳 null（資料庫欄位 NULL）。
     */
    public static byte[] encodeJewels(final Map<String, ItemStack> jewels) {
        final List<String> ids = new ArrayList<>();
        final List<ItemStack> stacks = new ArrayList<>();
        for (final Map.Entry<String, ItemStack> entry : jewels.entrySet()) {
            if (entry.getValue() != null && !entry.getValue().isEmpty()) {
                ids.add(entry.getKey());
                stacks.add(entry.getValue());
            }
        }
        if (ids.isEmpty()) {
            return null;
        }
        final byte[] names = String.join("\n", ids).getBytes(StandardCharsets.UTF_8);
        final byte[] items = ItemStack.serializeItemsAsBytes(stacks.toArray(ItemStack[]::new));
        final ByteBuffer buffer = ByteBuffer.allocate(4 + names.length + items.length);
        buffer.putInt(names.length);
        buffer.put(names);
        buffer.put(items);
        return buffer.array();
    }

    /** 讀天賦樹的珠寶；null 或空代表沒有。壞掉的資料丟 RuntimeException（呼叫端決定怎麼辦）。 */
    public static Map<String, ItemStack> decodeJewels(final byte[] bytes) {
        final Map<String, ItemStack> result = new LinkedHashMap<>();
        if (bytes == null || bytes.length < 4) {
            return result;
        }
        final ByteBuffer buffer = ByteBuffer.wrap(bytes);
        final int length = buffer.getInt();
        if (length < 0 || length > buffer.remaining()) {
            throw new IllegalArgumentException("珠寶資料的長度不對：" + length);
        }
        final byte[] names = new byte[length];
        buffer.get(names);
        final byte[] items = new byte[buffer.remaining()];
        buffer.get(items);
        final String[] ids = new String(names, StandardCharsets.UTF_8).split("\n");
        final ItemStack[] stacks = ItemStack.deserializeItemsFromBytes(items);
        for (int i = 0; i < Math.min(ids.length, stacks.length); i++) {
            if (!ids[i].isEmpty() && stacks[i] != null && !stacks[i].isEmpty()) {
                result.put(ids[i], stacks[i]);
            }
        }
        return result;
    }

    /** 背包（含快捷列）有沒有空格：有的話存檔時游標上的東西算在背包裡。 */
    public static boolean hasEmptySlot(final Player player) {
        final ItemStack[] contents = player.getInventory().getContents();
        for (int i = 0; i < Math.min(36, contents.length); i++) {
            if (contents[i] == null || contents[i].isEmpty()) {
                return true;
            }
        }
        return false;
    }

    /**
     * 讀隨身背包；null 代表沒存過（空背包）。存檔的格子比現在多時（頁數改少了），
     * 多出來的東西依序塞進現在的空格（存檔的頁數上限 {@link Bag#MAX_PAGES}，只會往上加）。
     */
    public static Bag decodeBag(final byte[] items, final String currency) {
        final Bag bag = new Bag(CurrencyPouch.decode(currency));
        if (items == null || items.length == 0) {
            return bag;
        }
        final ItemStack[] decoded = ItemStack.deserializeItemsFromBytes(items);
        final List<ItemStack> overflow = new ArrayList<>();
        for (int i = 0; i < decoded.length; i++) {
            final ItemStack item = decoded[i];
            if (item == null || item.isEmpty()) {
                continue;
            }
            final int page = i / Bag.PAGE_SIZE;
            if (page < Bag.MAX_PAGES) {
                bag.page(page)[i % Bag.PAGE_SIZE] = item;
            } else {
                overflow.add(item);
            }
        }
        int lost = 0;
        for (final ItemStack item : overflow) {
            boolean placed = false;
            // 2026-10-18：所有頁都找（以前只找前 2 頁，滿了就無聲不見；放到收回的頁也看得到、拿得出來）
            place:
            for (int page = 0; page < Bag.MAX_PAGES; page++) {
                final ItemStack[] slots = bag.page(page);
                for (int i = 0; i < slots.length; i++) {
                    if (slots[i] == null) {
                        slots[i] = item;
                        placed = true;
                        break place;
                    }
                }
            }
            if (!placed) {
                lost++;
            }
        }
        if (lost > 0) {
            // 2026-10-18：還是放不下就當讀不出來（呼叫端 markUnreadable 保留原始資料、記 log），不丟東西
            throw new IllegalStateException("隨身背包存檔有 " + lost + " 件超出頁數上限、放不下");
        }
        return bag;
    }

    private static ItemStack[] nullsToEmpty(final ItemStack[] items) {
        final ItemStack[] copy = new ItemStack[items.length];
        for (int i = 0; i < items.length; i++) {
            copy[i] = items[i] == null ? ItemStack.empty() : items[i];
        }
        return copy;
    }
}
