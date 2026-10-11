package tw.exilecore.guild;

import org.bukkit.inventory.ItemStack;
import tw.exilecore.stash.StashCategory;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 一個公會的倉庫：幾頁物品，每頁 {@value #PAGE_SIZE} 格。頁數跟著公會等級變多（data/guilds.yml）。
 * 放東西、拿東西都是「點一下搬一件」（不像個人倉庫可以直接拖）：好幾個人同時開著，每一步都先對過記憶體裡的內容再動，
 * 才不會兩個人拿到同一件。只在主執行緒使用。
 */
public final class GuildStash {

    public static final int PAGE_SIZE = 45;
    /** 最多幾頁（資料庫裡頁碼比這個大的不讀）。2026-10：40 → 500，星幣買的頁沒有上限，這只是防手滑（跟個人倉庫一樣）。 */
    public static final int MAX_PAGES = 500;

    /** 頁籤的名稱與分類存在倉庫表的這一「頁」（不是物品；2026-10）。 */
    public static final int META_PAGE = -1;
    /** 頁籤名稱最多幾個字（跟個人倉庫一樣）。 */
    public static final int TAB_NAME_MAX = 12;

    /** 一頁的頁籤：名稱（空的 = 「第 n 頁」）與放哪一類。 */
    public record Tab(String name, StashCategory category) {
        static final Tab DEFAULT = new Tab("", StashCategory.ALL);
    }

    private final List<ItemStack[]> pages = new ArrayList<>();
    private final Map<Integer, Tab> tabs = new HashMap<>();

    // ---- 頁籤（2026-10：名稱、分類；搜尋、整理、照分類放都看這個） ----

    public String tabName(final int index) {
        return tabs.getOrDefault(index, Tab.DEFAULT).name();
    }

    /** 顯示的名稱：沒取名字就是「第 n 頁」。 */
    public String tabLabel(final int index) {
        final String name = tabName(index);
        return name.isEmpty() ? "第 " + (index + 1) + " 頁" : name;
    }

    public StashCategory tabCategory(final int index) {
        return tabs.getOrDefault(index, Tab.DEFAULT).category();
    }

    /** 前 count 頁的分類（照頁的順序）。 */
    public List<StashCategory> tabCategories(final int count) {
        final List<StashCategory> list = new ArrayList<>();
        for (int i = 0; i < Math.min(count, pages.size()); i++) {
            list.add(tabCategory(i));
        }
        return list;
    }

    /** 改頁籤：名稱去頭尾空白、拿掉換行與定位字元、最多 {@value #TAB_NAME_MAX} 個字；分類看不懂的當「全部」。 */
    public void setTab(final int index, final String name, final StashCategory category) {
        if (index < 0 || index >= MAX_PAGES) {
            return;
        }
        final Tab tab = new Tab(cleanName(name), category == null ? StashCategory.ALL : StashCategory.parseTab(category.name()));
        if (tab.equals(Tab.DEFAULT)) {
            tabs.remove(index);
        } else {
            tabs.put(index, tab);
        }
    }

    static String cleanName(final String name) {
        if (name == null) {
            return "";
        }
        final String text = name.replace('\t', ' ').replace('\n', ' ').replace('\r', ' ').strip();
        final int length = text.codePointCount(0, text.length());
        return length <= TAB_NAME_MAX ? text : text.substring(0, text.offsetByCodePoints(0, TAB_NAME_MAX));
    }

    /** 頁籤的設定序列化（每行「頁碼 TAB 分類 TAB 名稱」；只寫改過的頁）。 */
    public byte[] serializeMeta() {
        final StringBuilder text = new StringBuilder();
        final List<Integer> order = new ArrayList<>(tabs.keySet());
        order.sort(null);
        for (final int index : order) {
            final Tab tab = tabs.get(index);
            text.append(index).append('\t').append(tab.category().name()).append('\t').append(tab.name()).append('\n');
        }
        return text.toString().getBytes(StandardCharsets.UTF_8);
    }

    /** 讀頁籤的設定；看不懂的行跳過。 */
    void readMeta(final byte[] stored) {
        tabs.clear();
        if (stored == null) {
            return;
        }
        for (final String line : new String(stored, StandardCharsets.UTF_8).split("\n")) {
            final String[] parts = line.split("\t", 3);
            if (parts.length < 2) {
                continue;
            }
            try {
                setTab(Integer.parseInt(parts[0].trim()), parts.length > 2 ? parts[2] : "", StashCategory.parseTab(parts[1]));
            } catch (final NumberFormatException e) {
                // 這一行壞了：那一頁用預設
            }
        }
    }

    /** 有幾頁（含設定改小之後還留著東西的頁）。 */
    public int pageCount() {
        return pages.size();
    }

    /** 頁數補到至少 count 頁（不會變少）。 */
    public void ensurePages(final int count) {
        while (pages.size() < Math.min(MAX_PAGES, count)) {
            pages.add(new ItemStack[PAGE_SIZE]);
        }
    }

    /** 一頁的內容（直接是內部陣列；null = 空格）。 */
    public ItemStack[] page(final int index) {
        return pages.get(index);
    }

    public int itemCount(final int index) {
        int count = 0;
        for (final ItemStack stack : pages.get(index)) {
            if (stack != null && !stack.isEmpty()) {
                count++;
            }
        }
        return count;
    }

    /** 全部頁加起來有幾件。 */
    public int itemCount() {
        int count = 0;
        for (int i = 0; i < pages.size(); i++) {
            count += itemCount(i);
        }
        return count;
    }

    public boolean isEmpty() {
        return itemCount() == 0;
    }

    /**
     * 放進一頁：先疊到一樣的東西上，再放空格。
     *
     * @return 放不下的數量（0 = 全部放進去了）
     */
    public static int add(final ItemStack[] page, final ItemStack stack) {
        int rest = stack.getAmount();
        final int max = Math.max(1, stack.getMaxStackSize());
        if (max > 1) {
            for (int i = 0; i < page.length && rest > 0; i++) {
                final ItemStack current = page[i];
                if (current != null && !current.isEmpty() && current.isSimilar(stack) && current.getAmount() < max) {
                    final int moved = Math.min(rest, max - current.getAmount());
                    current.setAmount(current.getAmount() + moved);
                    rest -= moved;
                }
            }
        }
        for (int i = 0; i < page.length && rest > 0; i++) {
            if (page[i] == null || page[i].isEmpty()) {
                final int moved = Math.min(rest, max);
                page[i] = stack.asQuantity(moved);
                rest -= moved;
            }
        }
        return rest;
    }

    /** 一頁序列化（空格用空的物品佔位）。 */
    public byte[] serialize(final int index) {
        final ItemStack[] source = pages.get(index);
        final ItemStack[] items = new ItemStack[PAGE_SIZE];
        for (int i = 0; i < PAGE_SIZE; i++) {
            items[i] = source[i] == null || source[i].isEmpty() ? ItemStack.empty() : source[i];
        }
        return ItemStack.serializeItemsAsBytes(items);
    }

    /** 全部的頁序列化（頁碼 → 內容）。 */
    public Map<Integer, byte[]> serializeAll() {
        final Map<Integer, byte[]> all = new LinkedHashMap<>();
        for (int i = 0; i < pages.size(); i++) {
            all.put(i, serialize(i));
        }
        return all;
    }

    /** 從資料庫讀出來的內容還原；頁數至少 minPages，資料庫裡有東西的頁都留著。 */
    public static GuildStash decode(final Map<Integer, byte[]> stored, final int minPages) {
        final GuildStash stash = new GuildStash();
        stash.ensurePages(minPages);
        stash.readMeta(stored.get(META_PAGE));
        for (final Map.Entry<Integer, byte[]> entry : stored.entrySet()) {
            final int page = entry.getKey();
            if (page < 0 || page >= MAX_PAGES || entry.getValue() == null) {
                continue;
            }
            final ItemStack[] items = ItemStack.deserializeItemsFromBytes(entry.getValue());
            boolean any = false;
            for (final ItemStack item : items) {
                if (item != null && !item.isEmpty()) {
                    any = true;
                    break;
                }
            }
            if (!any && page >= stash.pageCount()) {
                continue; // 空的、而且超出現在的頁數：不用留
            }
            stash.ensurePages(page + 1);
            final ItemStack[] target = stash.page(page);
            for (int i = 0; i < Math.min(PAGE_SIZE, items.length); i++) {
                target[i] = items[i] == null || items[i].isEmpty() ? null : items[i];
            }
        }
        return stash;
    }
}
