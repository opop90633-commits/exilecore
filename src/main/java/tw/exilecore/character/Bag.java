package tw.exilecore.character;

import org.bukkit.inventory.ItemStack;

/**
 * 隨身背包（每個角色一個）：2 頁（贊助多的另外算，2026-10）、每頁 45 格的物品，加上不佔格子的通貨袋。頁用到才建。
 * 跟著角色存在 character_inventory（bag、bag_currency 欄位）。只在主執行緒使用。
 */
public final class Bag {

    /** 一般的頁數。 */
    public static final int BASE_PAGES = 2;
    /** 頁數上限（防手滑；2026-10）。 */
    public static final int MAX_PAGES = BASE_PAGES + tw.exilecore.sponsor.SponsorConfig.MAX_EXTRA_BAG;
    public static final int PAGE_SIZE = 45;

    private final java.util.List<ItemStack[]> pages = new java.util.ArrayList<>();
    private final CurrencyPouch pouch;
    /** 讀不出來的物品頁存檔（原封不動寫回去）；null = 正常。 */
    private byte[] unreadable;

    public Bag() {
        this(new CurrencyPouch());
    }

    public Bag(final CurrencyPouch pouch) {
        this.pouch = pouch;
        for (int i = 0; i < BASE_PAGES; i++) {
            pages.add(new ItemStack[PAGE_SIZE]);
        }
    }

    /** 建了幾頁（存檔、掃描用；可能比能用的多：贊助的頁變少時東西還在）。 */
    public int storedPages() {
        return pages.size();
    }

    /** 能用幾頁（一般 2 頁，累積贊助多的另外算）。 */
    private int unlocked = BASE_PAGES;

    /** 2026-10-26（昇華・巨量承載）：額外多幾頁（不超過 {@link #MAX_PAGES}）。 */
    private int bonus;

    /** 能用幾頁。 */
    public int pages() {
        return Math.min(MAX_PAGES, unlocked + bonus);
    }

    /** 2026-10-26（昇華・巨量承載）：攜帶一個箱子＝多一頁（贊助的頁已經滿了就沒有）。 */
    public void setBonusPages(final int count) {
        bonus = Math.max(0, count);
    }

    /** 能用的頁數（贊助模組在角色載入、累積贊助變了的時候設；只會在 BASE_PAGES 到 MAX_PAGES 之間）。 */
    public void setPages(final int count) {
        unlocked = Math.max(BASE_PAGES, Math.min(MAX_PAGES, count));
    }

    /** 看得到幾頁（能用的頁，加上收回之後還有東西的頁；2026-10）。 */
    public int viewPages() {
        int last = -1;
        for (int i = pages.size() - 1; i >= pages(); i--) {
            if (!emptyPage(i)) {
                last = i;
                break;
            }
        }
        return Math.max(pages(), last + 1);
    }

    /** 這一頁收回了（只能拿出來）。 */
    public boolean locked(final int page) {
        return page >= pages();
    }

    /**
     * 收回的頁（2026-10，管理員收回累積贊助、買的頁）：後面的東西搬到能用的頁的空格，搬空的頁拿掉；
     * 放不下的留在原本的頁（看得到、只能拿出來）。回傳還有東西的收回頁有幾頁。
     */
    public int compact() {
        for (int from = pages(); from < pages.size(); from++) {
            final ItemStack[] source = pages.get(from);
            for (int i = 0; i < source.length; i++) {
                if (source[i] == null || source[i].isEmpty()) {
                    continue;
                }
                boolean moved = false;
                for (int to = 0; to < pages() && !moved; to++) {
                    final ItemStack[] target = page(to);
                    for (int j = 0; j < target.length; j++) {
                        if (target[j] == null || target[j].isEmpty()) {
                            target[j] = source[i];
                            source[i] = null;
                            moved = true;
                            break;
                        }
                    }
                }
                if (!moved) {
                    break;
                }
            }
        }
        while (pages.size() > Math.max(BASE_PAGES, pages()) && emptyPage(pages.size() - 1)) {
            pages.remove(pages.size() - 1);
        }
        return viewPages() - pages();
    }

    private boolean emptyPage(final int index) {
        for (final ItemStack item : pages.get(index)) {
            if (item != null && !item.isEmpty()) {
                return false;
            }
        }
        return true;
    }

    /** 一頁的內容（直接是內部陣列；null = 空格）。 */
    public ItemStack[] page(final int index) {
        if (index < 0 || index >= MAX_PAGES) {
            throw new IndexOutOfBoundsException(index);
        }
        while (pages.size() <= index) {
            pages.add(new ItemStack[PAGE_SIZE]);
        }
        return pages.get(index);
    }

    public CurrencyPouch pouch() {
        return pouch;
    }

    /** 物品頁的存檔讀不出來：這次登入不能用物品頁，存檔時把原本的資料寫回去。 */
    public void markUnreadable(final byte[] raw) {
        this.unreadable = raw;
    }

    /** 讀不出來的原始資料；null = 正常。 */
    public byte[] unreadable() {
        return unreadable;
    }

    /** 物品頁能不能用（存檔讀得出來）。 */
    public boolean usable() {
        return unreadable == null;
    }

    /** 這一頁有幾格有東西。 */
    public int itemCount(final int index) {
        if (index >= pages.size()) {
            return 0; // 還沒建的頁是空的
        }
        int count = 0;
        for (final ItemStack stack : pages.get(index)) {
            if (stack != null && !stack.isEmpty()) {
                count++;
            }
        }
        return count;
    }

    /** 全部頁面合起來有幾個空格（物品頁不能用時是 0）。 */
    public int freeSlots() {
        if (!usable()) {
            return 0;
        }
        int free = 0;
        for (int page = 0; page < pages(); page++) {
            free += PAGE_SIZE - itemCount(page);
        }
        return free;
    }

    /**
     * 放進第一個空格（從第 1 頁開始找）；物品頁不能用或全滿回傳 false。
     * 只能在隨身背包視窗沒開著時用（開著的那一頁關視窗時會被畫面上的內容蓋掉；掉落物走 LootService.stow）。
     */
    public boolean add(final ItemStack stack) {
        if (!usable() || stack == null || stack.isEmpty()) {
            return false;
        }
        for (int page = 0; page < pages(); page++) {
            for (int i = 0; i < PAGE_SIZE; i++) {
                final ItemStack there = page(page)[i];
                if (there == null || there.isEmpty()) {
                    page(page)[i] = stack;
                    return true;
                }
            }
        }
        return false;
    }

    /** 所有格子攤平（頁 0 的 45 格、頁 1 的 45 格…），存檔用；空格是 null。 */
    public ItemStack[] flatten() {
        // 存全部的頁（沒解鎖的頁也存：裡面的東西不會不見）；後面整頁都空的不存，舊存檔跟只有 2 頁的一樣大
        int last = BASE_PAGES;
        for (int page = pages.size() - 1; page >= BASE_PAGES; page--) {
            if (itemCount(page) > 0) {
                last = page + 1;
                break;
            }
        }
        final ItemStack[] all = new ItemStack[last * PAGE_SIZE];
        for (int page = 0; page < last; page++) {
            for (int i = 0; i < PAGE_SIZE; i++) {
                final ItemStack stack = pages.get(page)[i];
                all[page * PAGE_SIZE + i] = stack == null || stack.isEmpty() ? null : stack;
            }
        }
        return all;
    }
}
