package tw.exilecore.quest;

import java.util.ArrayList;
import java.util.List;

/** 任務的文字（純函式，不依賴 Bukkit；任務追蹤、任務日誌、單元測試用）。文字都是 MiniMessage。 */
public final class QuestText {

    /** 任務追蹤最多同時顯示幾個任務。 */
    public static final int TRACKED = 3;

    private QuestText() {
    }

    /** MiniMessage 跳脫（設定檔裡的名字、目標）。 */
    public static String escape(final String text) {
        return text == null ? "" : text.replace("\\", "\\\\").replace("<", "\\<");
    }

    /** 這一步的進度（「3/6」；沒有計數的是空的）。 */
    public static String progress(final QuestLog log, final QuestConfig.Quest quest) {
        final QuestLog.State state = log.state(quest);
        if (state.phase() != QuestLog.Phase.ACTIVE) {
            return "";
        }
        final QuestConfig.Objective objective = quest.stage(state.stage()).objective();
        return objective.goal().counted() ? state.count() + "/" + objective.needed() : "";
    }

    /** 任務追蹤的內容（第一行是標題）；沒有進行中的任務回傳空的。 */
    public static List<String> trackerLines(final QuestLog log) {
        return trackerLines(log, List.of());
    }

    /**
     * 任務追蹤的內容：進行中的任務，接著 extra（每日委託）；兩個都沒有回傳空的。
     *
     * @param extra 接在任務後面的幾行（已經是 MiniMessage）
     */
    public static List<String> trackerLines(final QuestLog log, final List<String> extra) {
        final List<QuestConfig.Quest> quests = log.tracked(TRACKED);
        final List<String> lines = new ArrayList<>();
        if (quests.isEmpty() && extra.isEmpty()) {
            return lines;
        }
        lines.add("<#f5b942><b>任務</b>");
        final String pinned = log.progress().get(QuestLog.TRACK);
        for (final QuestConfig.Quest quest : quests) {
            final QuestConfig.Stage stage = quest.stage(log.state(quest).stage());
            lines.add((quest.id().equals(pinned) ? "<white>▶ " : "") + quest.kind().color() + escape(quest.name()));
            final String progress = progress(log, quest);
            lines.add("<gray> " + escape(stage.text()) + (progress.isEmpty() ? "" : " <white>" + progress));
        }
        lines.addAll(extra);
        lines.add(extra.isEmpty() ? "<dark_gray>/quests 任務日誌" : "<dark_gray>/quests 任務・/bounty 委託");
        return lines;
    }
}
