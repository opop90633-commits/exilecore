package tw.exilecore.guide;

import java.util.ArrayList;
import java.util.List;
import java.util.function.Predicate;

/**
 * 新手引導的純邏輯：幾個步驟、每一步怎麼算完成、側欄要怎麼寫。不依賴 Bukkit。
 * 步驟照順序，第一個沒完成的就是「現在這一步」；全部完成（或玩家 /guide off）就不再顯示。
 * <p>
 * 2026-10：寫得更白話——每一步只講「做什麼」和「按哪裡」，一句話；能用指令的在聊天欄給可以點的按鈕；
 * 第二步先教「蹲下＋F 打開選單」（其他功能幾乎都在選單裡），後面的步驟都從選單講。
 */
public final class Guide {

    /** 判斷步驟用的狀態快照。 */
    public record Snapshot(boolean hasCharacter, boolean menuOpened, boolean questStarted, boolean skillBound, boolean flasksPlaced,
                           boolean checkpoint, boolean passiveAllocated, int level) {
    }

    /**
     * 一步：代號、標題、怎麼做（側欄與聊天欄）、可以點的指令（null = 沒有）、完成條件。
     */
    public record Step(String id, String title, String hint, String command, Predicate<Snapshot> done) {
    }

    public static final List<Step> STEPS = List.of(
            new Step("character", "選一個職業", "輸入 /char，挑一個喜歡的職業建立角色", "/char", Snapshot::hasCharacter),
            new Step("menu", "打開選單", "蹲下（Shift）再按 F：技能、背包、倉庫、天賦都在這裡", null, Snapshot::menuOpened),
            new Step("skill", "裝上技能", "選單 →「技能」→ 把技能放到「左鍵」，就能用左鍵放技能", "/skills", Snapshot::skillBound),
            new Step("flask", "放好藥劑", "紅藥水、藍藥水放快捷欄的藥劑欄（預設 1、2；設定裡可以改到 1–8 任一格），按那一格的數字鍵喝", null, Snapshot::flasksPlaced),
            new Step("quest", "接任務", "找頭上有光點的 NPC，右鍵跟他說話", null, Snapshot::questStarted),
            new Step("checkpoint", "出發冒險", "走出營地，碰到第一個發光的檢查點（會記住重生點）", null, Snapshot::checkpoint),
            new Step("passive", "點天賦", "升級會拿到天賦點：選單 →「天賦」→ 點亮一格", "/tree", Snapshot::passiveAllocated),
            new Step("level5", "升到 5 級", "照任務打怪就會升級；不知道去哪就看側欄的任務", null, snapshot -> snapshot.level() >= 5));

    /** 另一個角色到這個等級就不用再看引導（分身）。 */
    public static final int VETERAN_LEVEL = 10;

    private Guide() {
    }

    /** 第一個還沒完成的步驤（索引）；全部完成回傳 STEPS.size()。 */
    public static int current(final Snapshot snapshot) {
        for (int i = 0; i < STEPS.size(); i++) {
            if (!STEPS.get(i).done().test(snapshot)) {
                return i;
            }
        }
        return STEPS.size();
    }

    /** 側欄那幾行（第一行是標題）；做完了回傳空的。 */
    public static List<String> sidebar(final int current) {
        if (current >= STEPS.size()) {
            return List.of();
        }
        final Step step = STEPS.get(current);
        final List<String> lines = new ArrayList<>();
        lines.add("<#9fe8c8><b>新手教學</b> <dark_gray>" + (current + 1) + "/" + STEPS.size());
        lines.add("<yellow>➜ <white>" + step.title());
        lines.add("<gray>" + step.hint());
        return lines;
    }

    /** 這一步的提示（聊天欄一行，有指令的附一個可以點的按鈕）。 */
    public static String hintLine(final Step step) {
        final String button = step.command() == null ? ""
                : " <click:run_command:'" + step.command() + "'><hover:show_text:'<gray>點一下直接執行 " + step.command() + "'>"
                + "<#9fe8c8>[點這裡]</hover></click>";
        return "<yellow>➜ <white>" + step.title() + "<gray>：" + step.hint() + button;
    }

    /** 聊天欄的清單：每一步打勾或箭頭。 */
    public static List<String> chat(final Snapshot snapshot, final int current) {
        final List<String> lines = new ArrayList<>();
        lines.add("<#9fe8c8><bold>新手教學</bold> <dark_gray>" + (current >= STEPS.size() ? "全部完成！" : "第 " + (current + 1) + "／" + STEPS.size() + " 步"));
        for (int i = 0; i < STEPS.size(); i++) {
            final Step step = STEPS.get(i);
            final boolean done = step.done().test(snapshot);
            if (i == current) {
                lines.add("  " + hintLine(step));
            } else {
                lines.add("  " + (done ? "<green>✔ <dark_gray>" : "<dark_gray>○ <gray>") + step.title());
            }
        }
        lines.add("<dark_gray>  不想看了：/guide off（之後 /guide on 再打開）");
        return lines;
    }
}
