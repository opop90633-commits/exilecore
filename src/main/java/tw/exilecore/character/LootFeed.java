package tw.exilecore.character;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.HashMap;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * 撿到東西的角落提示（2026-10）：每個玩家最近撿到的幾樣東西（同一樣的合併、數量加起來），幾秒後消失。
 * 畫在快捷欄右邊（{@link HotbarSides#renderLoot}）。純邏輯，不依賴 Bukkit。
 */
public final class LootFeed {

    /** 一樣東西在角落停多久（tick）。 */
    public static final long SHOW_TICKS = 120;
    /** 最後幾 tick 變暗。 */
    public static final long FADE_TICKS = 30;
    /** 最多幾行。 */
    public static final int MAX = 5;

    /** 一行：名稱（純文字）、數量、圖示代號、顏色（#rrggbb）、什麼時候消失。 */
    public record Entry(String name, int count, String icon, String color, long until) {
    }

    private final Map<UUID, Deque<Entry>> feeds = new HashMap<>();

    /** 加一樣東西：同名同圖示的合併成一行（數量加起來、時間重算、移到最新）。 */
    public void add(final UUID player, final String name, final int count, final String icon, final String color, final long now) {
        if (name == null || name.isBlank() || count <= 0) {
            return;
        }
        final Deque<Entry> feed = feeds.computeIfAbsent(player, key -> new ArrayDeque<>());
        int total = count;
        for (final Iterator<Entry> iterator = feed.iterator(); iterator.hasNext(); ) {
            final Entry entry = iterator.next();
            if (entry.name().equals(name) && java.util.Objects.equals(entry.icon(), icon) && entry.until() > now) {
                total += entry.count();
                iterator.remove();
                break;
            }
        }
        feed.addLast(new Entry(name, total, icon, color == null ? "#c8c8c8" : color, now + SHOW_TICKS));
        while (feed.size() > MAX) {
            feed.removeFirst();
        }
    }

    /** 還在顯示的（舊的在前、最新的在最後）；過期的順便清掉。 */
    public List<Entry> active(final UUID player, final long now) {
        final Deque<Entry> feed = feeds.get(player);
        if (feed == null) {
            return List.of();
        }
        feed.removeIf(entry -> entry.until() <= now);
        if (feed.isEmpty()) {
            feeds.remove(player);
            return List.of();
        }
        return new ArrayList<>(feed);
    }

    public void clear(final UUID player) {
        feeds.remove(player);
    }
}
