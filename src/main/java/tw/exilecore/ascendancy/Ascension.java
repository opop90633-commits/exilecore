package tw.exilecore.ascendancy;

import tw.exilecore.stats.Modifier;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Function;

/**
 * 一個角色的昇華狀態（純邏輯，看角色進度算出來）：完成了哪些試煉、有幾個昇華點、選了哪個昇華職業、配了哪些昇華天賦。
 * <p>
 * 進度的寫法：{@code trial.試煉代號} = 第一次完成的時間（秒）、{@code asc} = 昇華職業代號、
 * {@code asc.nodes} = 配的昇華天賦「a,b,c」（照配的順序）、{@code asc.week} = 這週開了幾次試煉「2026-W40|3」。
 * 配的天賦每次都重新核對（設定檔改過也不會出錯）：不是這個昇華職業的、前一個沒配的、點數超過的，照順序略過。
 */
public final class Ascension {

    /** 選的昇華職業。 */
    public static final String CHOSEN = "asc";
    /** 配的昇華天賦。 */
    public static final String NODES = "asc.nodes";
    /** 完成的試煉：trial.代號。 */
    public static final String TRIAL_PREFIX = "trial.";
    /** 這週開了幾次試煉：「2026-W40|3」。 */
    public static final String WEEK = "asc.week";

    private final AscendancyConfig config;
    private final Map<String, String> progress;
    private final String classId;
    private final int level;
    private List<AscendancyConfig.Node> allocated;

    /**
     * @param progress 角色進度（唯讀）
     * @param classId  職業代號（沒角色 = null）
     * @param level    角色等級
     */
    public Ascension(final AscendancyConfig config, final Map<String, String> progress, final String classId, final int level) {
        this.config = config;
        this.progress = progress;
        this.classId = classId;
        this.level = level;
    }

    public static String trialKey(final String trialId) {
        return TRIAL_PREFIX + trialId;
    }

    /** 這週（week，例如 2026-W40）開了幾次試煉。 */
    public static int opensThisWeek(final Map<String, String> progress, final String week) {
        final String value = progress.get(WEEK);
        if (value == null) {
            return 0;
        }
        final String[] parts = value.split("\\|", -1);
        if (parts.length != 2 || !parts[0].equals(week)) {
            return 0;
        }
        try {
            return Math.max(0, Integer.parseInt(parts[1].trim()));
        } catch (final NumberFormatException e) {
            return 0;
        }
    }

    public static String opensValue(final String week, final int count) {
        return week + "|" + count;
    }

    // ---- 試煉 ----

    public boolean completed(final AscendancyConfig.Trial trial) {
        return progress.containsKey(trialKey(trial.id()));
    }

    /** 完成的試煉數。 */
    public int completedCount() {
        int count = 0;
        for (final AscendancyConfig.Trial trial : config.trials()) {
            if (completed(trial)) {
                count++;
            }
        }
        return count;
    }

    /** 昇華點：完成的試煉給的加起來。 */
    public int points() {
        int total = 0;
        for (final AscendancyConfig.Trial trial : config.trials()) {
            if (completed(trial)) {
                total += trial.points();
            }
        }
        return total;
    }

    /** 完成過幾張不同的地圖（星圖的進度 atlas.地圖）。 */
    public static int mapsCompleted(final Map<String, String> progress) {
        int count = 0;
        for (final String key : progress.keySet()) {
            if (key.startsWith("atlas.")) {
                count++;
            }
        }
        return count;
    }

    /**
     * 還不能進這個試煉的原因；可以回傳 null。
     *
     * @param bossName 區域代號 → 那個區域 Boss 的名字（找不到就回傳區域代號）
     */
    public String lockReason(final AscendancyConfig.Trial trial, final Function<String, String> bossName) {
        final AscendancyConfig.Requirements requires = trial.requires();
        if (requires.trial() != null) {
            final AscendancyConfig.Trial before = config.trial(requires.trial());
            if (before == null || !completed(before)) {
                return "先完成「" + (before == null ? requires.trial() : before.name()) + "」";
            }
        }
        if (requires.boss() != null && !progress.containsKey("boss." + requires.boss())) {
            return "先用這個角色打倒" + bossName.apply(requires.boss());
        }
        if (requires.maps() > 0 && mapsCompleted(progress) < requires.maps()) {
            return "先完成 " + requires.maps() + " 張不同的地圖（現在 " + mapsCompleted(progress) + " 張）";
        }
        if (level < requires.level()) {
            return "需要等級 " + requires.level();
        }
        return null;
    }

    // ---- 昇華職業 ----

    /** 這個職業可以選的昇華職業。 */
    public List<AscendancyConfig.Ascendancy> options() {
        return classId == null ? List.of() : config.forClass(classId);
    }

    /** 選的昇華職業（沒選、或設定檔裡已經沒有、不是這個職業的回傳 null）。 */
    public AscendancyConfig.Ascendancy chosen() {
        final AscendancyConfig.Ascendancy ascendancy = config.ascendancy(progress.get(CHOSEN));
        return ascendancy != null && ascendancy.classId().equals(classId) ? ascendancy : null;
    }

    /** 可以選昇華職業了：完成過一個試煉、還沒選、這個職業有昇華職業。 */
    public boolean canChoose() {
        return chosen() == null && points() > 0 && !options().isEmpty();
    }

    /** 不能選這個昇華職業的原因；可以回傳 null。 */
    public String chooseProblem(final AscendancyConfig.Ascendancy ascendancy) {
        if (chosen() != null) {
            return "已經昇華為" + chosen().name() + "了（選了不能換）";
        }
        if (points() <= 0) {
            return "先完成一個試煉";
        }
        if (ascendancy == null || !ascendancy.classId().equals(classId)) {
            return "這不是你的職業能選的昇華職業";
        }
        return null;
    }

    // ---- 昇華天賦 ----

    /** 配好的昇華天賦（照配的順序；核對過：前一個要先配、點數不能超過）。 */
    public List<AscendancyConfig.Node> allocated() {
        if (allocated != null) {
            return allocated;
        }
        final List<AscendancyConfig.Node> list = new ArrayList<>();
        final AscendancyConfig.Ascendancy ascendancy = chosen();
        if (ascendancy != null) {
            final int budget = points();
            int spent = 0;
            final Set<String> ids = new LinkedHashSet<>();
            for (final String id : parse(progress.get(NODES))) {
                final AscendancyConfig.Node node = ascendancy.node(id);
                if (node == null || ids.contains(id) || node.requires() != null && !ids.contains(node.requires())
                        || spent + node.cost() > budget) {
                    continue;
                }
                ids.add(id);
                spent += node.cost();
                list.add(node);
            }
        }
        allocated = List.copyOf(list);
        return allocated;
    }

    static List<String> parse(final String value) {
        final List<String> ids = new ArrayList<>();
        if (value != null) {
            for (final String id : value.split(",")) {
                if (!id.isBlank()) {
                    ids.add(id.trim());
                }
            }
        }
        return ids;
    }

    public boolean isAllocated(final AscendancyConfig.Node node) {
        return allocated().contains(node);
    }

    /** 用掉的昇華點。 */
    public int spent() {
        int total = 0;
        for (final AscendancyConfig.Node node : allocated()) {
            total += node.cost();
        }
        return total;
    }

    /** 還能配的昇華點。 */
    public int available() {
        return points() - spent();
    }

    /** 有沒有現在就能配的天賦（任務追蹤的提醒用）。 */
    public boolean canAllocateAny() {
        final AscendancyConfig.Ascendancy ascendancy = chosen();
        if (ascendancy == null) {
            return false;
        }
        for (final AscendancyConfig.Node node : ascendancy.nodes()) {
            if (allocateProblem(node) == null) {
                return true;
            }
        }
        return false;
    }

    /** 不能配這個天賦的原因；可以回傳 null。 */
    public String allocateProblem(final AscendancyConfig.Node node) {
        final AscendancyConfig.Ascendancy ascendancy = chosen();
        if (ascendancy == null) {
            return "還沒有選昇華職業";
        }
        if (!node.equals(ascendancy.node(node.id()))) {
            return "這不是" + ascendancy.name() + "的昇華天賦";
        }
        if (isAllocated(node)) {
            return "已經配過了";
        }
        if (node.requires() != null) {
            final AscendancyConfig.Node before = ascendancy.node(node.requires());
            if (before == null || !isAllocated(before)) {
                return "先配「" + (before == null ? node.requires() : before.name()) + "」";
            }
        }
        if (available() < node.cost()) {
            return "昇華點不夠：要 " + node.cost() + " 點，你有 " + available() + " 點（第一次完成試煉會給）";
        }
        return null;
    }

    /** 不能退這個天賦的原因；可以回傳 null。 */
    public String refundProblem(final AscendancyConfig.Node node) {
        if (!isAllocated(node)) {
            return "這個昇華天賦沒有配";
        }
        for (final AscendancyConfig.Node other : allocated()) {
            if (node.id().equals(other.requires())) {
                return "先退後面的「" + other.name() + "」";
            }
        }
        return null;
    }

    /** 配上這個天賦之後的 asc.nodes。 */
    public String withNode(final AscendancyConfig.Node node) {
        final List<String> ids = ids();
        ids.add(node.id());
        return String.join(",", ids);
    }

    /** 退掉這個天賦之後的 asc.nodes（沒有了回傳 null = 刪掉這一筆）。 */
    public String withoutNode(final AscendancyConfig.Node node) {
        final List<String> ids = ids();
        ids.remove(node.id());
        return ids.isEmpty() ? null : String.join(",", ids);
    }

    private List<String> ids() {
        final List<String> ids = new ArrayList<>();
        for (final AscendancyConfig.Node node : allocated()) {
            ids.add(node.id());
        }
        return ids;
    }

    /** 配好的昇華天賦給的修飾器。 */
    public List<Modifier> modifiers() {
        final List<Modifier> list = new ArrayList<>();
        for (final AscendancyConfig.Node node : allocated()) {
            list.addAll(node.stats());
        }
        return list;
    }
}
