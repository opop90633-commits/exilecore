package tw.exilecore.gui;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;

/**
 * 畫面右邊側欄的排版（純邏輯，不依賴 Bukkit）：上面是任務追蹤，下面是「最近撿到」（撿到值得注意的東西時出現，幾秒後收起來）。
 * 每一行都是 MiniMessage；回傳的第一行是側欄的標題，其他是內容（最多 {@link #MAX_ROWS} 行，計分板側欄的上限）。
 */
public final class SidebarLayout {

    /** 側欄最多幾行（不含標題）。 */
    public static final int MAX_ROWS = 15;
    /** 只有撿到的東西、沒有任務追蹤時的標題。 */
    public static final String LOOT_TITLE = "<#f5b942><b>最近撿到</b>";
    /** 接在任務追蹤下面那一段的小標題。 */
    public static final String LOOT_HEADER = "<#f5b942>最近撿到";

    private SidebarLayout() {
    }

    /**
     * 組出側欄。
     *
     * @param quest 任務追蹤（第一行是標題；空的 = 沒有任務追蹤）
     * @param loot  「最近撿到」的幾行（{@link Feed#rows()}；空的 = 沒有）
     * @return 第一行是標題，其他是內容；兩個都空回傳空的（側欄收起來）
     */
    /**
     * 戰鬥中最上面再放戰鬥數據（第一行是標題；空的 = 沒在戰鬥）：其他的往下排，放不下的從最下面讓。
     */
    public static List<String> compose(final List<String> combat, final List<String> guide, final List<String> quest,
                                       final List<String> loot) {
        if (combat.isEmpty()) {
            return compose(guide, quest, loot);
        }
        final List<String> lines = new ArrayList<>(combat);
        final List<String> rest = compose(guide, quest, loot);
        if (!rest.isEmpty()) {
            lines.add("");
            lines.addAll(rest);
        }
        return lines.subList(0, Math.min(lines.size(), MAX_ROWS + 1));
    }

    /**
     * 同上，最上面再放新手引導（第一行是標題；空的 = 沒有）：引導在最上面，任務追蹤的標題變成一行小標題，放不下的從最下面讓。
     */
    public static List<String> compose(final List<String> guide, final List<String> quest, final List<String> loot) {
        if (guide.isEmpty()) {
            return compose(quest, loot);
        }
        final List<String> lines = new ArrayList<>(guide);
        final List<String> rest = compose(quest, loot);
        if (!rest.isEmpty()) {
            lines.add("");
            lines.addAll(rest);
        }
        return lines.subList(0, Math.min(lines.size(), MAX_ROWS + 1));
    }

    public static List<String> compose(final List<String> quest, final List<String> loot) {
        final List<String> lines = new ArrayList<>();
        if (loot.isEmpty()) {
            if (!quest.isEmpty()) {
                lines.addAll(quest.subList(0, Math.min(quest.size(), MAX_ROWS + 1)));
            }
            return lines;
        }
        if (quest.isEmpty()) {
            lines.add(LOOT_TITLE);
            lines.addAll(loot.subList(0, Math.min(loot.size(), MAX_ROWS)));
            return lines;
        }
        // 任務在上面；放不下時任務從最後一行開始讓（最後一行是「/quests 任務日誌」的提示）
        final List<String> block = new ArrayList<>();
        block.add("");
        block.add(LOOT_HEADER);
        block.addAll(loot);
        final int questRows = Math.min(quest.size() - 1, Math.max(0, MAX_ROWS - block.size()));
        lines.addAll(quest.subList(0, 1 + questRows));
        lines.addAll(block.subList(0, Math.min(block.size(), MAX_ROWS - questRows)));
        return lines;
    }

    /** 側欄「狀態」的標題。 */
    public static final String STATUS_TITLE = "<#8fd3ff><b>狀態</b>";
    public static final String STATUS_HEADER = "<#8fd3ff>狀態";

    /**
     * 最上面放「狀態」（開著的光環、召喚物數量，不含標題；2026-10 從動作列搬過來），其他的往下排，放不下的從最下面讓。
     */
    public static List<String> withStatus(final List<String> status, final List<String> rest) {
        if (status.isEmpty()) {
            return rest;
        }
        final List<String> lines = new ArrayList<>();
        lines.add(STATUS_TITLE);
        lines.addAll(status);
        if (!rest.isEmpty()) {
            lines.add("");
            lines.addAll(rest);
        }
        return lines.subList(0, Math.min(lines.size(), MAX_ROWS + 1));
    }

    /** 撿到的一樣東西：名稱（MiniMessage，帶稀有度的顏色）與數量；icon、color 是卡片的圖示代號與框的顏色（2026-10，可以 null）。 */
    public record Item(String text, int amount, String icon, String color) {
        public Item(final String text, final int amount) {
            this(text, amount, null, null);
        }
    }

    /** 畫一張卡片（套了資源包的人看；2026-10）。 */
    @FunctionalInterface
    public interface CardRenderer {
        /**
         * @param name  名稱（含數量）
         * @param faded 快要消失了
         */
        String render(Item item, String name, boolean faded);
    }

    /**
     * 「最近撿到」：新的排最上面，每一樣到期就拿掉；同一個名稱再撿到就合併數量、移到最上面、重新計時。
     */
    public static final class Feed {

        /** 最多列幾樣（再多的寫成「…還有 N 樣」）。 */
        public static final int SHOWN = 5;
        /** 最多記幾樣（更舊的直接丟掉）。 */
        static final int MAX_ENTRIES = 30;

        private record Entry(String text, int amount, long expires, String icon, String color) {
        }

        /** 最後幾 tick 變暗（淡出）。 */
        public static final long FADE_TICKS = 40;

        /** 新的在前面。 */
        private final ArrayDeque<Entry> entries = new ArrayDeque<>();

        /**
         * 加一樣。
         *
         * @param expires 到期的時間（跟 {@link #expire} 的 now 同一種單位，例如伺服器 tick）
         */
        public void add(final Item item, final long expires) {
            int amount = Math.max(1, item.amount());
            for (final Iterator<Entry> it = entries.iterator(); it.hasNext(); ) {
                final Entry entry = it.next();
                if (entry.text().equals(item.text())) {
                    amount += entry.amount();
                    it.remove();
                    break;
                }
            }
            entries.addFirst(new Entry(item.text(), amount, expires, item.icon(), item.color()));
            while (entries.size() > MAX_ENTRIES) {
                entries.removeLast();
            }
        }

        /** 拿掉到期的；有拿掉回傳 true（要重畫）。 */
        public boolean expire(final long now) {
            return entries.removeIf(entry -> entry.expires() <= now);
        }

        public boolean isEmpty() {
            return entries.isEmpty();
        }

        public int size() {
            return entries.size();
        }

        public void clear() {
            entries.clear();
        }

        /**
         * 卡片版（2026-10）：舊的在上、新的在最下面（一件一件往上疊），最後 {@link #FADE_TICKS} tick 變暗；
         * 只列最新的 {@link #SHOWN} 樣，更早的寫在最上面一行「…還有 N 樣」。renderer 是 null 就是文字版（一樣的順序）。
         */
        public List<String> rows(final long now, final CardRenderer renderer) {
            final List<Entry> newest = new ArrayList<>();
            for (final Entry entry : entries) {
                if (newest.size() >= SHOWN) {
                    break;
                }
                newest.add(entry);
            }
            final List<String> rows = new ArrayList<>();
            if (entries.size() > SHOWN) {
                rows.add("<gray> …還有 " + (entries.size() - SHOWN) + " 樣");
            }
            for (int i = newest.size() - 1; i >= 0; i--) {
                final Entry entry = newest.get(i);
                final boolean faded = entry.expires() - now <= FADE_TICKS;
                // 圖示代號 $ 開頭：數量是金幣（過濾換的金幣那張卡片）
                final boolean gold = entry.icon() != null && entry.icon().startsWith("$");
                final String icon = gold ? entry.icon().substring(1) : entry.icon();
                final String name = entry.text() + (gold ? String.format(java.util.Locale.ROOT, " <#f5b942>+%,d", entry.amount())
                        : entry.amount() > 1 ? "<gray> ×" + entry.amount() : "");
                if (renderer != null) {
                    rows.add(renderer.render(new Item(entry.text(), entry.amount(), icon, entry.color()), name, faded));
                } else {
                    rows.add(" " + (faded ? "<dark_gray>" + name.replaceAll("<[^>]*>", "") : name));
                }
            }
            return rows;
        }

        /** 側欄上的幾行：前 {@link #SHOWN} 樣（數量大於 1 寫「×N」），更多的寫成一行「…還有 N 樣」。 */
        public List<String> rows() {
            final List<String> rows = new ArrayList<>();
            int index = 0;
            for (final Entry entry : entries) {
                if (index++ >= SHOWN) {
                    break;
                }
                rows.add(" " + entry.text() + (entry.amount() > 1 ? "<gray> ×" + entry.amount() : ""));
            }
            if (entries.size() > SHOWN) {
                rows.add("<gray> …還有 " + (entries.size() - SHOWN) + " 樣");
            }
            return rows;
        }
    }
}
