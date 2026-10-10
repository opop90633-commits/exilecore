package tw.exilecore.event;

import tw.exilecore.quest.QuestText;

import java.util.ArrayList;
import java.util.List;

/**
 * 每月活動的文字（純函式，不依賴 Bukkit；任務追蹤、選單、單元測試用）。文字都是 MiniMessage。
 */
public final class EventText {

    /** 任務追蹤最多列幾個還沒做完的挑戰（再多的寫「還有幾個」）。 */
    public static final int TRACKED = 2;

    /** 一次擊殺（完成地圖、完成地圖事件）的結果。 */
    public enum Result {
        /** 有計入。 */
        COUNTED,
        /** 怪物（地圖）等級比下限低，沒計入。 */
        TOO_WEAK,
        /** 帳號資料還沒載入好，沒計入。 */
        NOT_LOADED
    }

    /**
     * 最近一次擊殺的結果（/event 的「最近一次擊殺」那行：玩家覺得沒計數時，看這一行就知道為什麼）。
     *
     * @param level    怪物（地圖）的等級
     * @param minLevel 當時要幾級以上才算
     * @param kinds    算進哪幾種挑戰（沒計入時是空的）
     * @param atMillis 什麼時候（System.currentTimeMillis）
     */
    public record Note(Result result, int level, int minLevel, List<EventConfig.Kind> kinds, long atMillis) {
        public Note {
            kinds = List.copyOf(kinds);
        }
    }

    private EventText() {
    }

    /** 主題的名字（MiniMessage 上色）。 */
    public static String themeName(final EventConfig.Theme theme) {
        return "<" + theme.color() + ">" + QuestText.escape(theme.name()) + "</" + theme.color() + ">";
    }

    /** 「Lv.74 以上」：等級下限寫給玩家看。 */
    public static String atLeast(final int minLevel) {
        return "Lv." + minLevel + " 以上";
    }

    /**
     * 任務追蹤（側欄）上的每月活動：標題一行（主題、做完幾個、要幾級以上的怪才算），接著還沒做完的前 {@link #TRACKED} 個挑戰的進度，
     * 再多的寫「還有幾個」。全部做完了、沒有挑戰回傳空的（不佔側欄）。
     */
    public static List<String> trackerLines(final EventConfig.Theme theme, final EventProgress progress, final int minLevel) {
        final List<EventConfig.Challenge> open = new ArrayList<>();
        for (final EventConfig.Challenge challenge : theme.challenges()) {
            if (!progress.done(challenge.id())) {
                open.add(challenge);
            }
        }
        if (open.isEmpty()) {
            return List.of();
        }
        final List<String> lines = new ArrayList<>();
        lines.add("<#c77dff>每月活動 " + themeName(theme) + " <white>" + progress.doneCount() + "/" + theme.challenges().size()
                + " <dark_gray>Lv." + minLevel + "+");
        for (int i = 0; i < Math.min(TRACKED, open.size()); i++) {
            final EventConfig.Challenge challenge = open.get(i);
            lines.add("<gray> " + QuestText.escape(challenge.kind().displayName()) + " <white>" + progress.count(challenge.id()) + "/" + challenge.count());
        }
        if (open.size() > TRACKED) {
            lines.add("<dark_gray> …還有 " + (open.size() - TRACKED) + " 個（/event）");
        }
        return lines;
    }

    /** 最近一次擊殺的一行說明（/event 用）：算進哪幾種、或是為什麼沒計入。 */
    public static String describe(final Note note, final long nowMillis) {
        final String when = "<dark_gray>　" + ago(nowMillis - note.atMillis());
        return switch (note.result()) {
            case COUNTED -> "<green>有計入<gray>：Lv." + note.level() + " 的怪（" + kindNames(note.kinds()) + "）" + when;
            case TOO_WEAK -> "<red>沒計入<gray>：怪物 Lv." + note.level() + " 太弱，要 " + atLeast(note.minLevel()) + "才算" + when;
            case NOT_LOADED -> "<red>沒計入<gray>：帳號資料還沒載入好（等幾秒，或重新登入）" + when;
        };
    }

    /** 提醒用的一句話：怪物（地圖）太弱沒計入。what 是「這隻怪」「這張地圖」。 */
    public static String tooWeak(final String what, final int level, final int minLevel) {
        return "<gray>" + what + " Lv." + level + " 太弱，不計入每月挑戰（要 " + atLeast(minLevel) + "；每日委託也是同樣的規則，<yellow>/event<gray> 看詳情）";
    }

    /** 算進哪幾種挑戰的名字（「擊殺、擊殺稀有」）。 */
    public static String kindNames(final List<EventConfig.Kind> kinds) {
        final List<String> names = new ArrayList<>();
        for (final EventConfig.Kind kind : kinds) {
            names.add(kind.displayName());
        }
        return String.join("、", names);
    }

    /** 多久以前：「剛剛」「12 秒前」「3 分鐘前」「2 小時前」。 */
    public static String ago(final long millis) {
        final long seconds = Math.max(0, millis / 1000);
        if (seconds < 5) {
            return "剛剛";
        }
        if (seconds < 60) {
            return seconds + " 秒前";
        }
        if (seconds < 3600) {
            return seconds / 60 + " 分鐘前";
        }
        return seconds / 3600 + " 小時前";
    }
}
