package tw.exilecore.stash;

import org.bukkit.inventory.ItemStack;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * 一個帳號的倉庫（所有角色共用）：物品頁（一開始 4 頁，每頁 45 格；功勳可以多換幾頁）＋通貨頁（每種通貨最多 5000 個）。
 * 頁數只會變多：資料庫裡有幾頁就有幾頁（就算比買的多也不會少），買了新的頁再補上。只在主執行緒使用。
 */
public final class Stash {

    /** 一開始有幾頁。 */
    public static final int BASE_PAGES = 4;
    /** 最多幾頁（資料庫裡頁碼比這個大的不讀、也不會被蓋掉）。 */
    public static final int MAX_PAGES = 500; // 2026-10：16 → 500（贊助的倉庫頁沒有上限，這只是防手滑）
    public static final int PAGE_SIZE = 45;
    public static final int CURRENCY_CAP = 5000;
    /** 頁籤名稱最多幾個字。 */
    public static final int TAB_NAME_MAX = 12;
    /** 地圖頁最多放幾張地圖（PoE 的地圖倉庫頁：照階級分開放，不佔物品頁的格子）。 */
    public static final int MAP_CAP = 1000;
    /** 地圖頁在資料庫裡用的頁碼（比物品頁的上限大，舊版讀不到也不會蓋掉）。 */
    public static final int MAP_STORE_KEY = 100;

    /**
     * 資料庫裡的頁碼（2026-10）：地圖頁一直存在 {@value #MAP_STORE_KEY}，以前倉庫最多 16 頁碰不到；
     * 現在頁數沒上限，第 101 頁（索引 100）以後往後挪一格存，跟地圖頁錯開。
     */
    public static int storageKey(final int page) {
        return page < MAP_STORE_KEY ? page : page + 1;
    }

    /**
     * 2026-10-23 效能：地圖頁改成分段存（一段 {@value #MAP_SHARD_SIZE} 張），頁碼用負數：第 n 段存在 −1−n。
     * 以前整份存在 {@value #MAP_STORE_KEY}，多一張少一張就要把上千張全部重新序列化（spark：主執行緒 1.6%）。
     * 讀的時候有負數頁碼就用新格式，沒有才讀舊的那一份；存過一次之後舊的那一列會刪掉。
     */
    public static final int MAP_SHARD_SIZE = 45;

    public static int mapShardKey(final int shard) {
        return -1 - shard;
    }

    /** 負數頁碼換回地圖段的索引；不是地圖段回傳 -1。 */
    public static int mapShardOf(final int key) {
        return key < 0 ? -1 - key : -1;
    }

    /** 資料庫的頁碼換回頁的索引（地圖頁回傳 -1）。 */
    public static int pageOfKey(final int key) {
        if (key == MAP_STORE_KEY || key < 0) {
            return -1;
        }
        return key < MAP_STORE_KEY ? key : key - 1;
    }

    private final List<ItemStack[]> pages = new ArrayList<>();
    private final Map<String, Integer> currency = new HashMap<>();
    /** 頁籤名稱（空字串 = 用「第 N 頁」）。 */
    private final List<String> tabNames = new ArrayList<>();
    /** 每一頁放哪一類（收進倉庫、整理全部頁用）。 */
    private final List<StashCategory> tabCategories = new ArrayList<>();
    /** 地圖頁的地圖（存放的順序；顯示時照階級分組）。 */
    private final List<ItemStack> maps = new ArrayList<>();

    public Stash() {
        ensurePages(BASE_PAGES);
    }

    /** 能用的頁數（2026-10，收回累積贊助、買的頁之後變少）；後面多的頁只能拿出來，拿空就消失。 */
    private int usable = MAX_PAGES;

    /** 能放東西的頁數（不會比 {@link #pageCount()} 多）。 */
    public int usablePages() {
        return Math.min(usable, pages.size());
    }

    /** 這一頁收回了（只能拿出來）。 */
    public boolean locked(final int page) {
        return page >= usable;
    }

    /**
     * 頁數變少（2026-10，管理員收回累積贊助、買的頁）：後面那幾頁的東西先搬到前面的空格，搬空的頁拿掉；
     * 前面放不下的頁留著、鎖起來（只能拿出來，拿空了下次打開倉庫就消失）。回傳還鎖著幾頁。
     */
    public int shrinkTo(final int count) {
        usable = Math.max(BASE_PAGES, Math.min(MAX_PAGES, count));
        if (pages.size() <= usable) {
            return 0;
        }
        for (int from = usable; from < pages.size(); from++) {
            final ItemStack[] source = pages.get(from);
            for (int i = 0; i < source.length; i++) {
                if (source[i] == null || source[i].isEmpty()) {
                    continue;
                }
                boolean moved = false;
                for (int to = 0; to < usable && !moved; to++) {
                    final ItemStack[] target = pages.get(to);
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
                    break; // 前面都滿了
                }
            }
        }
        // 後面搬空的頁拿掉（從最後面拿，中間還有東西的留著）
        while (pages.size() > usable && isEmptyPage(pages.size() - 1)) {
            final int last = pages.size() - 1;
            pages.remove(last);
            tabNames.remove(last);
            tabCategories.remove(last);
        }
        return pages.size() - usable;
    }

    private boolean isEmptyPage(final int page) {
        for (final ItemStack item : pages.get(page)) {
            if (item != null && !item.isEmpty()) {
                return false;
            }
        }
        return true;
    }

    /** 有幾頁物品頁。 */
    public int pageCount() {
        return pages.size();
    }

    /** 頁數補到至少 count 頁（最多 {@value #MAX_PAGES}；不會變少）。 */
    public void ensurePages(final int count) {
        // 2026-10：收回之後又給回來（或買了）：那幾頁解鎖（以前要重新上線才能用）
        usable = Math.max(usable, Math.min(MAX_PAGES, count));
        while (pages.size() < Math.min(MAX_PAGES, count)) {
            pages.add(new ItemStack[PAGE_SIZE]);
            tabNames.add("");
            tabCategories.add(StashCategory.ALL);
        }
    }

    /** 頁籤名稱（沒取名回傳空字串）。 */
    public String tabName(final int index) {
        return tabNames.get(index);
    }

    /** 顯示用的頁籤名稱。 */
    public String tabLabel(final int index) {
        return tabNames.get(index).isEmpty() ? "第 " + (index + 1) + " 頁" : tabNames.get(index);
    }

    public StashCategory tabCategory(final int index) {
        return tabCategories.get(index);
    }

    /** 每一頁的分類（頁的順序）。 */
    public List<StashCategory> tabCategories() {
        return List.copyOf(tabCategories);
    }

    /** 改頁籤：名稱去頭尾空白、最多 {@value #TAB_NAME_MAX} 個字；分類不能是通貨。 */
    public void setTab(final int index, final String name, final StashCategory category) {
        tabNames.set(index, StashLayout.cleanTabName(name, TAB_NAME_MAX));
        tabCategories.set(index, category == null || category == StashCategory.CURRENCY ? StashCategory.ALL : category);
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

    public int currency(final String id) {
        return currency.getOrDefault(id, 0);
    }

    public Map<String, Integer> currencies() {
        return currency;
    }

    public void setCurrency(final String rawId, final int amount) {
        // 2026-10-27：拿掉的通貨（星紋蝕刻器）讀進來併進現在的那種（礪鋒石）
        final String id = tw.exilecore.currency.CurrencyRepository.resolve(rawId);
        if (!id.equals(rawId)) {
            if (amount > 0) {
                currency.merge(id, amount, Integer::sum);
            }
            return;
        }
        if (amount <= 0) {
            currency.remove(id);
        } else {
            currency.put(id, amount); // 2026-10：上限可以加大（贊助），讀進來不截
        }
    }

    /** 存入通貨，回傳實際收下的數量（超過上限的部分不收）。 */
    public int depositCurrency(final String id, final int amount) {
        final int have = currency(id);
        final int accepted = Math.max(0, Math.min(amount, currencyCap - have));
        if (accepted > 0) {
            currency.put(id, have + accepted);
        }
        return accepted;
    }

    // ---- 地圖頁 ----

    /** 地圖頁的地圖（直接是內部清單）。 */
    public List<ItemStack> maps() {
        return maps;
    }

    /** 通貨頁每種最多幾個（2026-10：贊助可以用星幣加大）。 */
    private int currencyCap = CURRENCY_CAP;

    public int currencyCap() {
        return currencyCap;
    }

    public void setCurrencyCap(final int cap) {
        this.currencyCap = Math.max(CURRENCY_CAP, cap);
    }

    /** 地圖頁最多幾張（2026-10：贊助可以用星幣加大）。 */
    private int mapCap = MAP_CAP;

    public int mapCap() {
        return mapCap;
    }

    public void setMapCap(final int cap) {
        this.mapCap = Math.max(MAP_CAP, cap);
    }

    public int mapCount() {
        return maps.size();
    }

    /** 放進地圖頁；滿了回傳 false（東西留在原地）。 */
    public boolean depositMap(final ItemStack map) {
        if (map == null || map.isEmpty() || maps.size() >= mapCap) {
            return false;
        }
        maps.add(map.clone());
        return true;
    }

    /** 從地圖頁拿走第 index 張（沒有回傳 null）。 */
    public ItemStack takeMap(final int index) {
        if (index < 0 || index >= maps.size()) {
            return null;
        }
        return maps.remove(index);
    }
}
