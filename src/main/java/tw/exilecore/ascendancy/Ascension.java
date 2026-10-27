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
 * 配的天賦每次都重新核對（設定檔改過也不會出錯）：不是這個昇華職業的、接不上已配天賦的、點數超過的，照順序略過。
 * 2026-10-26：{@code asc.version} = 配的時候是哪一版的昇華天賦（改版時 AscendancyService 把 asc.nodes 清掉、點數退回）。
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
    /**
     * 2026-10-26：配昇華天賦時是哪一版的昇華天賦（ascendancy.yml 的 version；比照天賦樹的 tree.version）。
     * 跟現在的版本不一樣（或沒記過、現在不是第 1 版）就把 asc.nodes 清掉、點數退回。
     */
    public static final String VERSION = "asc.version";

    /** 這個角色的昇華天賦是舊版配的嗎（沒記過 = 第 1 版配的）。 */
    public static boolean outdated(final int current, final String recorded) {
        if (recorded == null || recorded.isBlank()) {
            return current != 1;
        }
        return !recorded.trim().equals(String.valueOf(current));
    }

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

    /**
     * 配好的昇華天賦（照配的順序；核對過：要跟已配的相連（或直接接在起點）、「選一個」只算第一個選項、點數不能超過）。
     * 2026-10-26 照 PoE2：相連不分方向（Ritualist、Invoker 有環）；這次之前要先配 requires 那一個。
     */
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
            final List<String> stored = parse(progress.get(NODES));
            // 2026-10-27：一直重掃到沒有新的接得上為止（有環的樹退掉中間一個之後，記錄的順序會變成「後面的先出現」）；
            // 這次之前只掃一遍，跟 refundProblem 的判斷不一致，接在環另一邊的天賦會默默不見
            boolean grew = true;
            while (grew) {
                grew = false;
                for (final String id : stored) {
                    final AscendancyConfig.Node node = ascendancy.node(id);
                    if (node == null || ids.contains(id) || !connected(ascendancy, node, ids) || spent + node.cost() > budget) {
                        continue;
                    }
                    ids.add(id);
                    spent += node.cost();
                    list.add(node);
                    grew = true;
                }
            }
            list.sort(java.util.Comparator.comparingInt(node -> stored.indexOf(node.id()))); // 還是照配的順序
        }
        allocated = List.copyOf(list);
        return allocated;
    }

    /** 這個天賦能不能接上 ids 裡已配的（選項：它的「選一個」已配、同一組還沒選別的）。 */
    static boolean connected(final AscendancyConfig.Ascendancy ascendancy, final AscendancyConfig.Node node, final Set<String> ids) {
        if (node.option()) {
            if (!ids.contains(node.optionOf())) {
                return false;
            }
            for (final AscendancyConfig.Node other : ascendancy.options(node.optionOf())) {
                if (ids.contains(other.id())) {
                    return false;
                }
            }
            return true;
        }
        if (!ids.containsAll(node.unlock())) {
            return false; // 2026-10-27 照 PoE2（聖潔一統）：unlock 寫的都配了才能配
        }
        if (node.root()) {
            return true;
        }
        for (final AscendancyConfig.Node neighbour : ascendancy.neighbours(node.id())) {
            if (!neighbour.option() && ids.contains(neighbour.id())) {
                return true;
            }
        }
        return false;
    }

    /** 拿掉 removed 之後，剩下的還接得到起點嗎；接不到的照配的順序回傳（都接得到 = 空的）。 */
    private List<AscendancyConfig.Node> strandedWithout(final AscendancyConfig.Node removed) {
        final AscendancyConfig.Ascendancy ascendancy = chosen();
        final List<AscendancyConfig.Node> rest = new ArrayList<>(allocated());
        rest.remove(removed);
        final Set<String> reached = new LinkedHashSet<>();
        boolean grew = true;
        while (grew) {
            grew = false;
            for (final AscendancyConfig.Node node : rest) {
                if (!reached.contains(node.id()) && connected(ascendancy, node, reached)) {
                    reached.add(node.id());
                    grew = true;
                }
            }
        }
        final List<AscendancyConfig.Node> stranded = new ArrayList<>();
        for (final AscendancyConfig.Node node : rest) {
            if (!reached.contains(node.id())) {
                stranded.add(node);
            }
        }
        return stranded;
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
        final Set<String> ids = new LinkedHashSet<>();
        for (final AscendancyConfig.Node owned : allocated()) {
            ids.add(owned.id());
        }
        if (!connected(ascendancy, node, ids)) {
            if (!ids.containsAll(node.unlock())) {
                // 2026-10-27 照 PoE2（聖潔一統）
                final List<String> names = new ArrayList<>();
                for (final String id : node.unlock()) {
                    final AscendancyConfig.Node need = ascendancy.node(id);
                    if (!ids.contains(id)) {
                        names.add("「" + (need == null ? id : need.name()) + "」");
                    }
                }
                return "先配" + String.join("、", names);
            }
            if (node.option()) {
                final AscendancyConfig.Node choice = ascendancy.node(node.optionOf());
                final String name = choice == null ? node.optionOf() : choice.name();
                if (!ids.contains(node.optionOf())) {
                    return "先配「" + name + "」";
                }
                for (final AscendancyConfig.Node other : ascendancy.options(node.optionOf())) {
                    if (ids.contains(other.id())) {
                        return "「" + name + "」只能選一個：已經選了「" + other.name() + "」（先退掉才能換）";
                    }
                }
            }
            final List<String> names = new ArrayList<>();
            for (final AscendancyConfig.Node neighbour : ascendancy.neighbours(node.id())) {
                if (!neighbour.option() && node.requires().contains(neighbour.id())) {
                    names.add("「" + neighbour.name() + "」");
                }
            }
            return names.isEmpty() ? "要跟已配的昇華天賦相連" : "先配" + String.join("或", names);
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
        final List<AscendancyConfig.Node> stranded = strandedWithout(node);
        if (!stranded.isEmpty()) {
            // 先退接在它後面的（最後配的那個；PoE2 一樣：退掉之後剩下的要都還接得到起點）
            return "先退後面的「" + stranded.getLast().name() + "」";
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

    /** 2026-10-27：這個昇華天賦給的額外天賦點（武器大師、開路者、時空術士；{@link AscendancyService#EXTRA_PASSIVE_POINTS}）。 */
    public static int extraPassivePoints(final AscendancyConfig.Node node) {
        double total = 0;
        for (final Modifier mod : node.stats()) {
            if (AscendancyService.EXTRA_PASSIVE_POINTS.equals(mod.stat())) {
                total += mod.value();
            }
        }
        return (int) Math.round(total);
    }

    /**
     * 2026-10-27：退掉一個給天賦點的昇華天賦之前，天賦樹上要先退幾點（0 = 可以退）。
     * 這次之前不檢查：昇華給的天賦點拿回去了，用那些點配的天賦還在，可用天賦點變成負的。
     *
     * @param available 現在可用的天賦點
     * @param lost      退掉之後少掉的天賦點
     * @param pruned    退掉之後天賦樹上因為配點規則沒了而自動退回的點數
     */
    public static int refundDebt(final int available, final int lost, final int pruned) {
        return lost <= 0 ? 0 : Math.max(0, lost - Math.max(0, available) - pruned);
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
