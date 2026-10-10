package tw.exilecore.stash;

import java.util.ArrayList;
import java.util.Collection;
import java.util.Comparator;
import java.util.List;

/**
 * 倉庫與隨身背包的「整理」和「收進倉庫」要怎麼排、放哪一頁。純邏輯（不碰 Bukkit 的物品，測得到）。
 * <ul>
 *   <li>排序：分類（武器、防具、飾品、寶石、藥劑、地圖、通貨、其他）→ 稀有度高的在前 → 等級高的在前 → 名稱 → 數量多的在前。</li>
 *   <li>放哪一頁：先放分類相同的頁，再放「全部」的頁（照頁的順序）。</li>
 *   <li>整理全部頁：照上面的規則分到各頁；分類頁放不下的才放到其他有空位的頁，東西一件都不會少。</li>
 * </ul>
 */
public final class StashLayout {

    /**
     * 一格東西的排序資料。
     *
     * @param item     原本的東西（Bukkit 的 ItemStack；這裡不看）
     * @param category 分類
     * @param rank     稀有度（越大越稀有）
     * @param level    物品等級（寶石等級、地圖階級…）
     * @param name     名稱（同分類、同稀有度、同等級時照名稱排）
     * @param amount   數量
     */
    public record Entry<T>(T item, StashCategory category, int rank, int level, String name, int amount) {
    }

    private StashLayout() {
    }

    /** 頁籤名稱：去掉頭尾空白與控制字元、最多 max 個字。 */
    public static String cleanTabName(final String name, final int max) {
        String clean = name == null ? "" : name.replaceAll("\\p{Cntrl}", "").strip();
        if (clean.codePointCount(0, clean.length()) > max) {
            clean = clean.substring(0, clean.offsetByCodePoints(0, max)).strip();
        }
        return clean;
    }

    /** 排序的規則。 */
    public static <T> Comparator<Entry<T>> order() {
        return Comparator.<Entry<T>>comparingInt(entry -> entry.category().ordinal())
                .thenComparing(Comparator.<Entry<T>>comparingInt(Entry::rank).reversed())
                .thenComparing(Comparator.<Entry<T>>comparingInt(Entry::level).reversed())
                .thenComparing(entry -> entry.name() == null ? "" : entry.name())
                .thenComparing(Comparator.<Entry<T>>comparingInt(Entry::amount).reversed());
    }

    /** 照規則排好（不改原本的清單）。 */
    public static <T> List<Entry<T>> sorted(final Collection<Entry<T>> entries) {
        final List<Entry<T>> list = new ArrayList<>(entries);
        list.sort(order());
        return list;
    }

    /**
     * 一件東西收進倉庫時，依序試哪幾頁：分類相同的頁，再來是「全部」的頁。都沒有就是空清單（這一類沒有地方放）。
     *
     * @param tabs 每一頁的分類（頁的順序）
     */
    public static List<Integer> depositOrder(final StashCategory category, final List<StashCategory> tabs) {
        final List<Integer> pages = new ArrayList<>();
        if (category != StashCategory.ALL) {
            for (int page = 0; page < tabs.size(); page++) {
                if (tabs.get(page) == category) {
                    pages.add(page);
                }
            }
            // 2026-10：再來是收這一類的大類頁（防具、飾品、其他）
            for (int page = 0; page < tabs.size(); page++) {
                if (tabs.get(page).isGroup() && tabs.get(page) != category && tabs.get(page).accepts(category)) {
                    pages.add(page);
                }
            }
        }
        for (int page = 0; page < tabs.size(); page++) {
            if (tabs.get(page) == StashCategory.ALL) {
                pages.add(page);
            }
        }
        return pages;
    }

    /**
     * 整理全部頁：把所有東西（已經疊好）分到各頁，每頁照規則排好。
     * 先放分類相同的頁，再放「全部」的頁，還有剩的放到任何有空位的頁（照頁的順序）。
     *
     * @param pageSize 每頁幾格
     * @return 每一頁的內容（照擺放的順序）
     * @throws IllegalArgumentException 東西比所有頁的格子還多
     */
    public static <T> List<List<Entry<T>>> distribute(final Collection<Entry<T>> entries, final List<StashCategory> tabs,
                                                      final int pageSize) {
        if (entries.size() > tabs.size() * pageSize) {
            throw new IllegalArgumentException("東西（" + entries.size() + "）比格子（" + tabs.size() * pageSize + "）多");
        }
        final List<List<Entry<T>>> pages = new ArrayList<>();
        for (int i = 0; i < tabs.size(); i++) {
            pages.add(new ArrayList<>());
        }
        List<Entry<T>> left = new ArrayList<>();
        for (final Entry<T> entry : sorted(entries)) {
            if (!place(entry, pages, pageSize, page -> entry.category() != StashCategory.ALL && tabs.get(page) == entry.category())) {
                left.add(entry);
            }
        }
        // 2026-10：放不進同一類的，再放收這一類的大類頁（防具、飾品、其他）
        final List<Entry<T>> grouped = new ArrayList<>();
        for (final Entry<T> entry : left) {
            if (!place(entry, pages, pageSize, page -> tabs.get(page).isGroup() && tabs.get(page).accepts(entry.category()))) {
                grouped.add(entry);
            }
        }
        left = grouped;
        final List<Entry<T>> rest = new ArrayList<>();
        for (final Entry<T> entry : left) {
            if (!place(entry, pages, pageSize, page -> tabs.get(page) == StashCategory.ALL)) {
                rest.add(entry);
            }
        }
        left = rest;
        for (final Entry<T> entry : left) {
            place(entry, pages, pageSize, page -> true);
        }
        // 每一頁照規則排好（分到同一頁的東西可能是不同階段放進來的）
        for (final List<Entry<T>> page : pages) {
            page.sort(order());
        }
        return pages;
    }

    private static <T> boolean place(final Entry<T> entry, final List<List<Entry<T>>> pages, final int pageSize,
                                     final java.util.function.IntPredicate allowed) {
        for (int page = 0; page < pages.size(); page++) {
            if (allowed.test(page) && pages.get(page).size() < pageSize) {
                pages.get(page).add(entry);
                return true;
            }
        }
        return false;
    }
}
