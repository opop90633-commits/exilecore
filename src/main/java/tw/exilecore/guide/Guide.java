package tw.exilecore.guide;

import java.util.ArrayList;
import java.util.List;
import java.util.function.Predicate;

/**
 * 新手引導的純邏輯：幾個步驟、每一步怎麼算完成、側欄要怎麼寫。不依賴 Bukkit。
 * 步驟照順序，第一個「已經解鎖、還沒完成」的就是「現在這一步」；全部完成（或玩家 /guide off）就不再顯示。
 * <p>
 * 2026-10：寫得更白話——每一步只講「做什麼」和「按哪裡」，一句話；能用指令的在聊天欄給可以點的按鈕；
 * 第二步先教「蹲下＋F 打開選單」（其他功能幾乎都在選單裡），後面的步驟都從選單講。
 * <p>
 * 2026-10-20 第二階段：5 級之後接著教寶石、通貨、倉庫、拆解、藥劑、委託板、試煉與昇華、星圖、地圖，
 * 每一步跟主線章節連動（{@link Step#unlock}）：還沒到那一章的步驟先不出現，也不擋住後面的。
 */
public final class Guide {

    /** 主線的章節順序（quests.yml 的代號）：{@link Snapshot#storyStarted}、{@link Snapshot#storyDone} 是這裡的索引。 */
    public static final List<String> MAIN_QUESTS = List.of("prologue", "ch1_coast", "ch2_forest", "ch3_ruins", "ch4_swamp", "ch5_mountain", "epilogue");

    /** 「換更好的藥劑」：藥劑欄裡有這個等級以上的藥劑就算（上等藥劑；粗製 1、一般 4）。 */
    public static final int FLASK_LEVEL = 10;

    /**
     * 判斷步驟用的狀態快照。
     *
     * @param storyStarted     接過的主線走到第幾章（{@link #MAIN_QUESTS} 的索引；-1 = 還沒接）
     * @param storyDone        完成的主線走到第幾章（-1 = 還沒完成任何一章）
     * @param skills           技能有幾個（起始套件給 1 個；第 2 個一定是自己雕的）
     * @param supportSocketed  任一技能裝了輔助寶石
     * @param currencyUsed     對裝備用過通貨（{@link GuideService#milestone}）
     * @param stashUsed        倉庫裡有東西
     * @param salvaged         拆解過（{@link GuideService#milestone}）
     * @param flaskLevel       藥劑欄裡最高的藥劑等級
     * @param boardSeen        打開過委託板（每日委託產生過）
     * @param trialDone        完成過任一個試煉
     * @param ascended         選了昇華
     * @param atlasAllocated   星圖點過
     * @param mapsDone         完成過幾張不同的地圖
     */
    public record Snapshot(boolean hasCharacter, boolean menuOpened, boolean questStarted, boolean skillBound, boolean flasksPlaced,
                           boolean checkpoint, boolean passiveAllocated, int level,
                           int storyStarted, int storyDone, int skills, boolean supportSocketed, boolean currencyUsed, boolean stashUsed,
                           boolean salvaged, int flaskLevel, boolean boardSeen, boolean trialDone, boolean ascended, boolean atlasAllocated,
                           int mapsDone) {

        /** 只有第一階段的狀態（測試、舊呼叫）：第二階段全部當作還沒開始。 */
        public Snapshot(final boolean hasCharacter, final boolean menuOpened, final boolean questStarted, final boolean skillBound,
                        final boolean flasksPlaced, final boolean checkpoint, final boolean passiveAllocated, final int level) {
            this(hasCharacter, menuOpened, questStarted, skillBound, flasksPlaced, checkpoint, passiveAllocated, level,
                    -1, -1, 0, false, false, false, false, 0, false, false, false, false, 0);
        }
    }

    /**
     * 一步：代號、標題、怎麼做（側欄與聊天欄）、可以點的指令（null = 沒有）、什麼時候出現、完成條件。
     */
    public record Step(String id, String title, String hint, String command, Predicate<Snapshot> unlock, Predicate<Snapshot> done) {
        public Step(final String id, final String title, final String hint, final String command, final Predicate<Snapshot> done) {
            this(id, title, hint, command, snapshot -> true, done);
        }
    }

    private static Predicate<Snapshot> started(final int chapter) {
        return snapshot -> snapshot.storyStarted() >= chapter;
    }

    private static Predicate<Snapshot> finished(final int chapter) {
        return snapshot -> snapshot.storyDone() >= chapter;
    }

    public static final List<Step> STEPS = List.of(
            new Step("character", "選一個職業", "輸入 /char，挑一個喜歡的職業建立角色", "/char", Snapshot::hasCharacter),
            new Step("menu", "打開選單", "蹲下（Shift）再按 F：技能、背包、倉庫、天賦都在這裡", null, Snapshot::menuOpened),
            new Step("skill", "裝上技能", "選單 →「技能」→ 把技能放到「左鍵」，就能用左鍵放技能", "/skills", Snapshot::skillBound),
            new Step("flask", "放好藥劑", "紅藥水、藍藥水放快捷欄的藥劑欄（預設 1、2；設定裡可以改到 1–8 任一格），按那一格的數字鍵喝", null, Snapshot::flasksPlaced),
            new Step("quest", "接任務", "找頭上有光點的 NPC，右鍵跟他說話", null, Snapshot::questStarted),
            new Step("checkpoint", "出發冒險", "走出營地，碰到第一個發光的檢查點（會記住重生點）", null, Snapshot::checkpoint),
            new Step("passive", "點天賦", "升級會拿到天賦點：選單 →「天賦」→ 點亮一格", "/tree", Snapshot::passiveAllocated),
            new Step("level5", "升到 5 級", "照任務打怪就會升級；不知道去哪就看側欄的任務", null, snapshot -> snapshot.level() >= 5),
            // ---- 第二階段（2026-10-20）：跟主線章節連動 ----
            new Step("gem", "雕琢技能寶石", "撿到的未切割技能寶石拿在手上右鍵，雕一顆新技能（通貨袋 /bag 裡 Shift＋右鍵也行）", "/bag",
                    started(1), snapshot -> snapshot.skills() >= 2),
            new Step("support", "裝一顆輔助寶石", "未切割輔助寶石右鍵切成輔助，再到選單 →「技能」→ 點技能旁的孔裝上，技能會變強", "/skills",
                    started(1), Snapshot::supportSocketed),
            new Step("currency", "用通貨改造裝備", "打開背包，把微光石或添輝石拿在游標上點一下裝備：白色變魔法、魔法多一條詞綴", null,
                    finished(1), Snapshot::currencyUsed),
            new Step("stash", "把東西存進倉庫", "營地的倉庫或輸入 /stash：通貨、地圖有自己的分頁，換角色也拿得到", "/stash",
                    finished(1), Snapshot::stashUsed),
            new Step("salvage", "拆解一件裝備", "營地的拆解師：不要的裝備拆成通貨與金幣，比丟掉划算", null,
                    finished(2), Snapshot::salvaged),
            new Step("flask_upgrade", "換更好的藥劑", "怪會掉更高等級的藥劑，放進藥劑欄換掉粗製藥劑（委託板的功勳也能換）", null,
                    finished(2), snapshot -> snapshot.flaskLevel() >= FLASK_LEVEL),
            new Step("bounty", "看看委託板", "營地的委託板：每日委託換功勳，功勳能買倉庫頁、角色格、稱號", null,
                    finished(3), Snapshot::boardSeen),
            new Step("trial", "完成第一個試煉", "找營地的試煉 NPC 打開試煉，通過就能選昇華職業（昇華天賦很強）", null,
                    finished(4), Snapshot::trialDone),
            new Step("ascend", "選一個昇華", "回試煉 NPC 選昇華，再到選單 →「天賦」點昇華天賦", null,
                    Snapshot::trialDone, Snapshot::ascended),
            new Step("atlas", "點一格星圖", "完成地圖會拿到星圖點數：輸入 /atlas 點亮一格，地圖會掉更多東西", "/atlas",
                    started(6), Snapshot::atlasAllocated),
            new Step("maps3", "完成 3 張地圖", "地圖裝置放進地圖、打完 Boss 就算完成；地圖裡會掉更多地圖，越打越深", null,
                    finished(6), snapshot -> snapshot.mapsDone() >= 3));

    /** 另一個角色到這個等級就不用再看引導（分身）。 */
    public static final int VETERAN_LEVEL = 10;

    private Guide() {
    }

    /** 第一個「已經解鎖、還沒完成」的步驟（索引）；全部完成（或剩下的都還沒解鎖）回傳 STEPS.size()。 */
    public static int current(final Snapshot snapshot) {
        for (int i = 0; i < STEPS.size(); i++) {
            final Step step = STEPS.get(i);
            if (step.unlock().test(snapshot) && !step.done().test(snapshot)) {
                return i;
            }
        }
        return STEPS.size();
    }

    /** 每一步都做完了（跟 {@link #current} 不同：還沒解鎖的也算沒做完，所以引導不會提早結束）。 */
    public static boolean allDone(final Snapshot snapshot) {
        for (final Step step : STEPS) {
            if (!step.done().test(snapshot)) {
                return false;
            }
        }
        return true;
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
        // 2026-10-22：提示照側欄的寬度斷行（以前整句一行，側欄被撐得很寬、右邊跑出螢幕）；完整的一句在聊天欄與標題
        for (final String line : wrap(step.hint(), SIDEBAR_WIDTH, SIDEBAR_HINT_LINES)) {
            lines.add("<gray>" + line);
        }
        return lines;
    }

    /** 側欄一行最寬多少（全形字算 2、半形算 1：大約 16 個中文字，跟任務那幾行差不多寬）。 */
    static final int SIDEBAR_WIDTH = 32;
    /** 側欄的提示最多幾行（太長的最後一行用「…」收尾）。 */
    static final int SIDEBAR_HINT_LINES = 4;

    /** 一個字佔的寬度：半形（ASCII）1，其他（中文、全形符號、箭頭）2。 */
    static int width(final int codePoint) {
        return codePoint < 0x80 ? 1 : 2;
    }

    /** 這些字後面適合斷行（標點）；「→」前面適合斷行。 */
    private static boolean breakAfter(final int codePoint) {
        return "，、；：。）」！？ ".indexOf(codePoint) >= 0;
    }

    /**
     * 照寬度斷行：每行不超過 width；行尾附近有標點就在標點後面斷（或「→」前面），沒有就硬斷。
     * 超過 maxLines 行的話最後一行結尾換成「…」。
     */
    static List<String> wrap(final String text, final int width, final int maxLines) {
        final List<String> lines = new ArrayList<>();
        final int[] chars = text.codePoints().toArray();
        int start = 0;
        while (start < chars.length) {
            int used = 0;
            int end = start;
            int lastBreak = -1;
            while (end < chars.length && used + width(chars[end]) <= width) {
                used += width(chars[end]);
                if (breakAfter(chars[end])) {
                    lastBreak = end + 1;
                } else if (chars[end] == '→' && end > start) {
                    lastBreak = end;
                }
                end++;
            }
            if (end < chars.length && lastBreak > start && used - widthOf(chars, lastBreak, end) >= width / 2) {
                end = lastBreak; // 在標點斷（不要斷得太短：至少半行）
            }
            // 避頭尾：下一行不要用「，」「）」開頭、這一行不要用「（」「「」結尾（往前挪一個字）
            while (end < chars.length && end - 1 > start && "，、；：。）」！？".indexOf(chars[end]) >= 0) {
                end--;
            }
            while (end < chars.length && end - 1 > start && "（「".indexOf(chars[end - 1]) >= 0) {
                end--;
            }
            lines.add(new String(chars, start, end - start).strip());
            start = end;
            while (start < chars.length && chars[start] == ' ') {
                start++;
            }
        }
        lines.removeIf(String::isEmpty);
        if (lines.size() > maxLines) {
            final List<String> cut = new ArrayList<>(lines.subList(0, maxLines));
            final String last = cut.get(maxLines - 1);
            final int[] lastChars = last.codePoints().toArray();
            cut.set(maxLines - 1, new String(lastChars, 0, Math.max(0, lastChars.length - 1)) + "…");
            return cut;
        }
        return lines;
    }

    private static int widthOf(final int[] chars, final int from, final int to) {
        int total = 0;
        for (int i = from; i < to; i++) {
            total += width(chars[i]);
        }
        return total;
    }

    /** 這一步的提示（聊天欄一行，有指令的附一個可以點的按鈕）。 */
    public static String hintLine(final Step step) {
        final String button = step.command() == null ? ""
                : " <click:run_command:'" + step.command() + "'><hover:show_text:'<gray>點一下直接執行 " + step.command() + "'>"
                + "<#9fe8c8>[點這裡]</hover></click>";
        return "<yellow>➜ <white>" + step.title() + "<gray>：" + step.hint() + button;
    }

    /** 聊天欄的清單：每一步打勾、箭頭，還沒解鎖的灰掉。 */
    public static List<String> chat(final Snapshot snapshot, final int current) {
        final List<String> lines = new ArrayList<>();
        lines.add("<#9fe8c8><bold>新手教學</bold> <dark_gray>" + (current >= STEPS.size() ? "全部完成！" : "第 " + (current + 1) + "／" + STEPS.size() + " 步"));
        for (int i = 0; i < STEPS.size(); i++) {
            final Step step = STEPS.get(i);
            final boolean done = step.done().test(snapshot);
            if (i == current) {
                lines.add("  " + hintLine(step));
            } else if (done) {
                lines.add("  <green>✔ <dark_gray>" + step.title());
            } else if (!step.unlock().test(snapshot)) {
                lines.add("  <dark_gray>○ " + step.title() + "（主線再往前走就會出現）");
            } else {
                lines.add("  <dark_gray>○ <gray>" + step.title());
            }
        }
        lines.add("<dark_gray>  不想看了：/guide off（之後 /guide on 再打開）");
        return lines;
    }
}
