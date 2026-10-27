package tw.exilecore.bounty;

import java.util.LinkedHashSet;
import java.util.Map;
import java.util.Set;

/**
 * 功勳兌換在帳號進度裡記的東西（純邏輯）：多買了幾頁倉庫、這週換了幾片天穹碎片、有哪些稱號、戴著哪一個。
 */
public final class MeritShop {

    /** 多買了幾頁倉庫（整數）。 */
    public static final String STASH = "shop.stash";
    /** 這週換了幾片天穹碎片：「2026-W40|1」。 */
    public static final String FRAGMENTS = "shop.fragment";
    /** 買過的稱號：「exile,hunter」。 */
    public static final String TITLES = "shop.titles";
    /** 戴著的稱號（沒有這一筆 = 不戴）。 */
    public static final String TITLE = "title";

    private MeritShop() {
    }

    /** 多買了幾頁倉庫（最多 {@link BountyConfig#MAX_STASH_PAGES}）。 */
    public static int extraStashPages(final Map<String, String> progress) {
        final String value = progress.get(STASH);
        if (value == null) {
            return 0;
        }
        try {
            return Math.max(0, Math.min(BountyConfig.MAX_STASH_PAGES, Integer.parseInt(value.trim())));
        } catch (final NumberFormatException e) {
            return 0;
        }
    }

    /** 帳號進度：多買的角色格數。 */
    public static final String CHARACTER_SLOTS = "shop.char_slots";

    /** 多買了幾個角色格（最多 {@link BountyConfig#MAX_CHARACTER_SLOTS}）。 */
    public static int extraCharacterSlots(final Map<String, String> progress) {
        final String value = progress.get(CHARACTER_SLOTS);
        if (value == null) {
            return 0;
        }
        try {
            return Math.max(0, Math.min(BountyConfig.MAX_CHARACTER_SLOTS, Integer.parseInt(value.trim())));
        } catch (final NumberFormatException e) {
            return 0;
        }
    }

    /** 下一個角色格的價格；買完了（或沒有賣）回傳 −1。 */
    public static long nextCharacterSlotPrice(final BountyConfig.Shop shop, final Map<String, String> progress) {
        final int bought = extraCharacterSlots(progress);
        return bought < shop.characterSlots().size() ? shop.characterSlots().get(bought) : -1;
    }

    /** 下一頁倉庫的價格；買完了（或沒有賣）回傳 −1。 */
    public static long nextStashPrice(final BountyConfig.Shop shop, final Map<String, String> progress) {
        final int bought = extraStashPages(progress);
        return bought < shop.stashPages().size() ? shop.stashPages().get(bought) : -1;
    }

    /** 這週換了幾片天穹碎片。 */
    public static int fragmentsThisWeek(final Map<String, String> progress, final String week) {
        final String value = progress.get(FRAGMENTS);
        if (value == null) {
            return 0;
        }
        final String[] parts = value.split("\\|", -1);
        if (parts.length != 2 || !parts[0].equals(week)) {
            return 0;
        }
        try {
            return Math.max(0, Integer.parseInt(parts[1]));
        } catch (final NumberFormatException e) {
            return 0;
        }
    }

    /** 「週|個數」這種值在這週是幾（不是這週的算 0）。 */
    public static int weeklyCount(final String value, final String week) {
        if (value == null) {
            return 0;
        }
        final String[] parts = value.split("\\|", -1);
        if (parts.length != 2 || !parts[0].equals(week)) {
            return 0;
        }
        try {
            return Math.max(0, Integer.parseInt(parts[1]));
        } catch (final NumberFormatException e) {
            return 0;
        }
    }

    public static String fragmentValue(final String week, final int count) {
        return week + "|" + count;
    }

    /** 買過的稱號（照買的順序）。 */
    public static Set<String> ownedTitles(final Map<String, String> progress) {
        final Set<String> owned = new LinkedHashSet<>();
        final String value = progress.get(TITLES);
        if (value != null) {
            for (final String id : value.split(",")) {
                if (!id.isBlank()) {
                    owned.add(id.trim());
                }
            }
        }
        return owned;
    }

    /**
     * 存得下的長度。2026-10-26：account_progress.progress_value 在第 20 版資料庫已經加長到 4096 字，這裡卻還是 250——
     * 商店、活動、擊殺稱號（光擊殺稱號的代號就兩百多字）拿多了，新的稱號記不下，發不出去（「達成目標沒有發放」）；
     * 這次之前 250。
     */
    static final int VALUE_MAX = 4000;

    public static String titlesValue(final Set<String> owned) {
        return String.join(",", owned);
    }

    /**
     * 買過的稱號寫成一筆：設定檔裡還有的優先，已經拿掉的稱號放得下才留（設定改過幾次也不會超過欄位長度）。
     *
     * @param known 設定檔裡現在有的稱號
     */
    public static String titlesValue(final Set<String> owned, final Set<String> known) {
        final StringBuilder text = new StringBuilder();
        for (final boolean keepKnown : new boolean[] {true, false}) {
            for (final String id : owned) {
                if (known.contains(id) != keepKnown) {
                    continue;
                }
                if (text.length() + id.length() + 1 > VALUE_MAX) {
                    continue;
                }
                if (!text.isEmpty()) {
                    text.append(',');
                }
                text.append(id);
            }
        }
        return text.toString();
    }

    /** 戴著的稱號（沒戴、沒買過、設定檔裡已經沒有的回傳 null）。 */
    public static BountyConfig.Title equipped(final BountyConfig.Shop shop, final Map<String, String> progress) {
        final String id = progress.get(TITLE);
        if (id == null || !ownedTitles(progress).contains(id)) {
            return null;
        }
        return shop.titles().get(id);
    }

    /** 一個稱號的永久加成寫成一行（MiniMessage 已跳脫：「+10 最大生命、增加 2% 移動速度」）；沒有加成回傳 null。 */
    public static String bonusText(final BountyConfig.Title title) {
        if (title == null || title.stats().isEmpty()) {
            return null;
        }
        return String.join("、", tw.exilecore.ascendancy.StatLines.describeAll(title.stats())).replace("\\", "\\\\").replace("<", "\\<");
    }

    /** 名字前面的稱號（MiniMessage）：「「星痕獵人」」。 */
    public static String tag(final BountyConfig.Title title) {
        final String name = title.name().replace("\\", "\\\\").replace("<", "\\<");
        // 2026-10：自訂稱號可以是漸層（gradient:#a:#b:…）或彩虹（rainbow）
        final String color = title.color();
        final String close = color.startsWith("gradient:") ? "gradient" : color.startsWith("rainbow") ? "rainbow" : color;
        return "<" + color + ">【" + name + "】</" + close + ">";
    }
}
