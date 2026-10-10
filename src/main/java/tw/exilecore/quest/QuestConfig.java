package tw.exilecore.quest;

import tw.exilecore.core.config.YamlNode;
import tw.exilecore.stats.ModType;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * 劇情與任務的設定（data/quests.yml；純資料，不依賴 Bukkit）：劇情人物（名字、站的位置、閒聊）與任務
 * （誰給、前置、一步一步的目標與台詞、永久獎勵、幾選一）。
 * <p>
 * 先讀進一份新的，{@link #validate} 檢查過（NPC、區域、屬性的代號都對得上）才換上去。
 */
public final class QuestConfig {

    public static final String FILE = "quests.yml";
    /** 營地：劇情人物的位置寫 camp 時，是相對於營地落點。 */
    public static final String CAMP = "camp";
    /** 代號最長幾個字（角色進度的鍵是 q.代號，最長 64）。 */
    static final int MAX_ID = 40;
    private static final Pattern ID = Pattern.compile("[a-z0-9_]+");
    /** 劇情世界的檢查點代號。 */
    public static final Set<String> CHECKPOINTS = Set.of("entrance", "midway", "boss");
    /** 劇情人物可以打開的功能（opens）→ 對話視窗上那顆按鈕的字。 */
    public static final Map<String, String> FUNCTIONS = Map.of("bounty", "看看委託板", "trial", "昇華與試煉");

    /** 任務種類。 */
    public enum Kind {
        MAIN("main", "主線", "<#f5b942>"),
        SIDE("side", "支線", "<#7fd4ff>");

        private final String id;
        private final String displayName;
        private final String color;

        Kind(final String id, final String displayName, final String color) {
            this.id = id;
            this.displayName = displayName;
            this.color = color;
        }

        public String id() {
            return id;
        }

        public String displayName() {
            return displayName;
        }

        /** MiniMessage 顏色標籤。 */
        public String color() {
            return color;
        }

        static Kind parse(final String text, final String where) {
            for (final Kind kind : values()) {
                if (kind.id.equals(text)) {
                    return kind;
                }
            }
            throw new IllegalArgumentException(where + "：kind 要是 main 或 side，卻是「" + text + "」");
        }
    }

    /** 一步的目標種類。 */
    public enum Goal {
        /** 跟某個 NPC 說話。 */
        TALK,
        /** 走到檢查點（發現過就算）。 */
        CHECKPOINT,
        /** 進入區域（進去過就算）。 */
        ZONE,
        /** 打倒區域 Boss（打倒過就算）。 */
        BOSS,
        /** 在區域裡擊殺。 */
        KILL,
        /** 從區域的怪物身上撿任務物品（機率，不佔背包）。 */
        COLLECT,
        /** 到達等級。 */
        LEVEL,
        /** 完成幾張不同的地圖。 */
        MAPS,
        /** 完成試煉（ascendancy.yml；完成過就算）。 */
        TRIAL;

        /** 已經做過的事直接算（不用在接任務之後才做）。 */
        public boolean retroactive() {
            return this == CHECKPOINT || this == ZONE || this == BOSS || this == LEVEL || this == MAPS || this == TRIAL;
        }

        /** 有計數（進度 3/6 這種）。 */
        public boolean counted() {
            return this == KILL || this == COLLECT;
        }
    }

    /** 擊殺、撿任務物品算哪些怪。 */
    public enum Filter {
        ANY("any", ""),
        MAGIC("magic", "魔法以上的"),
        RARE("rare", "稀有");

        private final String id;
        private final String displayName;

        Filter(final String id, final String displayName) {
            this.id = id;
            this.displayName = displayName;
        }

        public String displayName() {
            return displayName;
        }

        static Filter parse(final String text, final String where) {
            for (final Filter filter : values()) {
                if (filter.id.equals(text)) {
                    return filter;
                }
            }
            throw new IllegalArgumentException(where + "：rarity 要是 any、magic 或 rare，卻是「" + text + "」");
        }
    }

    /**
     * 劇情人物站的位置。
     *
     * @param zone       camp（相對於營地落點）或區域代號（相對於那個區域的檢查點）
     * @param checkpoint 區域的檢查點代號（camp 時為 null）
     * @param x          往東幾格
     * @param z          往南幾格
     * @param yaw        面向（0 = 南、90 = 西、180 = 北、-90 = 東）
     */
    public record Placement(String zone, String checkpoint, double x, double z, float yaw) {
    }

    /**
     * 劇情人物。
     *
     * @param hold      手上拿的東西（原版物品名稱；null = 空手）
     * @param idle      沒有任務的事時說的話（隨機一句）
     * @param placement 在劇情世界裡站的位置（null = 不自動擺，用 /ec npc story 放）
     * @param opens     右鍵時打開的功能（{@link #FUNCTIONS}；null = 只有說話）
     */
    public record Npc(String id, String name, String title, String hold, List<String> idle, Placement placement, String opens) {
        public Npc {
            idle = List.copyOf(idle);
        }
    }

    /**
     * 一步的目標。
     *
     * @param target     TALK：NPC 代號；CHECKPOINT、ZONE、BOSS、KILL、COLLECT：區域代號；TRIAL：試煉代號
     * @param checkpoint CHECKPOINT 的檢查點代號
     * @param count      KILL、COLLECT：要幾個；LEVEL：等級；MAPS：張數；其他 1
     * @param item       COLLECT：任務物品的名字
     * @param chance     COLLECT：普通怪掉的機率（魔法怪 2 倍、稀有以上必掉）
     */
    public record Objective(Goal goal, String target, String checkpoint, int count, Filter filter, String item, double chance) {

        /** 這一步要累積幾個才算完成（沒有計數的是 1）。 */
        public int needed() {
            return goal.counted() ? Math.max(1, count) : 1;
        }
    }

    /**
     * 一步。
     *
     * @param text  任務追蹤上的一行
     * @param lines TALK：說話時 NPC 說的話；其他：完成這一步時聊天欄出現的旁白（可以沒有）
     */
    public record Stage(String text, Objective objective, List<String> lines) {
        public Stage {
            lines = List.copyOf(lines);
        }
    }

    /**
     * 一個永久加成。
     *
     * @param value FLAT：照寫的數字（抗性 10 = 10%）；INCREASED：小數（0.05 = 5%）
     */
    public record Bonus(String stat, ModType type, double value) {
    }

    /** 幾選一的其中一個。 */
    public record Choice(String id, String label, List<Bonus> bonuses) {
        public Choice {
            bonuses = List.copyOf(bonuses);
        }
    }

    /**
     * 一個任務。
     *
     * @param zone          所屬的區域（camp 或區域代號；任務日誌分組用）
     * @param giver         誰給（劇情人物或營地 NPC 的代號）
     * @param requires      要先完成的任務
     * @param after         要先接過（或完成）的任務（null = 不用）
     * @param level         最低等級
     * @param auto          符合條件就自動開始
     * @param summary       任務日誌裡的一句話
     * @param offer         給任務時說的話
     * @param passivePoints 完成給幾個天賦點
     * @param bonuses       完成給的永久加成
     * @param choices       幾選一（空的 = 沒有）
     */
    public record Quest(String id, Kind kind, String name, String zone, String giver, List<String> requires, String after,
                        int level, boolean auto, String summary, List<String> offer, List<Stage> stages, int passivePoints,
                        List<Bonus> bonuses, List<Choice> choices) {
        public Quest {
            requires = List.copyOf(requires);
            offer = List.copyOf(offer);
            stages = List.copyOf(stages);
            bonuses = List.copyOf(bonuses);
            choices = List.copyOf(choices);
        }

        public Stage stage(final int index) {
            return stages.get(Math.max(0, Math.min(stages.size() - 1, index)));
        }

        public boolean isLast(final int index) {
            return index >= stages.size() - 1;
        }

        public Choice choice(final String id) {
            for (final Choice choice : choices) {
                if (choice.id().equals(id)) {
                    return choice;
                }
            }
            return null;
        }
    }

    private final Map<String, Npc> npcs = new LinkedHashMap<>();
    private final Map<String, Quest> quests = new LinkedHashMap<>();

    /** 讀 data/quests.yml（不存在 = 沒有任務）。 */
    public void load(final Path dataFolder) throws IOException {
        final Path file = dataFolder.resolve(FILE);
        final QuestConfig fresh = new QuestConfig();
        if (Files.exists(file)) {
            fresh.parse(YamlNode.load(file));
        }
        copyFrom(fresh);
    }

    /** 從已經讀好的節點載入（單元測試用）。 */
    public void load(final YamlNode root) {
        final QuestConfig fresh = new QuestConfig();
        fresh.parse(root);
        copyFrom(fresh);
    }

    /** 換成另一份（模組先讀進新的一份、檢查過才換）。 */
    public void copyFrom(final QuestConfig other) {
        npcs.clear();
        npcs.putAll(other.npcs);
        quests.clear();
        quests.putAll(other.quests);
    }

    private void parse(final YamlNode root) {
        final YamlNode npcNode = root.node("npcs");
        for (final String id : npcNode.keys()) {
            final YamlNode node = npcNode.node(id);
            checkId(id, node.path());
            npcs.put(id, new Npc(id, node.string("name", "").trim(), node.string("title", "").trim(),
                    blankToNull(node.string("hold", null)), node.strings("idle"), placement(node), blankToNull(node.string("opens", null))));
        }
        final YamlNode questNode = root.node("quests");
        for (final String id : questNode.keys()) {
            final YamlNode node = questNode.node(id);
            checkId(id, node.path());
            if ("track".equals(id)) {
                throw new IllegalArgumentException(node.path() + "：track 是保留的代號");
            }
            quests.put(id, quest(id, node));
        }
    }

    private static Placement placement(final YamlNode node) {
        if (!node.has("place")) {
            return null;
        }
        final YamlNode place = node.node("place");
        final String zone = place.requireString("zone").trim();
        final String checkpoint = CAMP.equals(zone) ? null : place.string("checkpoint", "entrance").trim();
        final Object at = place.raw("at");
        double x = 0;
        double z = 0;
        if (at instanceof List<?> list && list.size() == 2 && list.get(0) instanceof Number dx && list.get(1) instanceof Number dz) {
            x = dx.doubleValue();
            z = dz.doubleValue();
        } else if (at != null) {
            throw new IllegalArgumentException(place.path() + ".at：要寫成 [往東, 往南]，卻是「" + at + "」");
        }
        return new Placement(zone, checkpoint, x, z, (float) place.decimal("yaw", 0));
    }

    private static Quest quest(final String id, final YamlNode node) {
        final Kind kind = Kind.parse(node.string("kind", "side").trim(), node.path());
        final List<Stage> stages = new ArrayList<>();
        for (final YamlNode stage : node.list("stages")) {
            stages.add(new Stage(stage.requireString("text").trim(), objective(stage), stage.strings("lines")));
        }
        int points = 0;
        final List<Bonus> bonuses = new ArrayList<>();
        for (final YamlNode reward : node.list("rewards")) {
            if (reward.has("passive_points")) {
                points += reward.integer("passive_points", 0);
            } else {
                bonuses.add(bonus(reward));
            }
        }
        final List<Choice> choices = new ArrayList<>();
        final YamlNode choose = node.node("choose");
        for (final String choiceId : choose.keys()) {
            final YamlNode choice = choose.node(choiceId);
            checkId(choiceId, choice.path());
            final List<Bonus> choiceBonuses = new ArrayList<>();
            for (final YamlNode reward : choice.list("rewards")) {
                choiceBonuses.add(bonus(reward));
            }
            choices.add(new Choice(choiceId, choice.string("label", "").trim(), choiceBonuses));
        }
        final List<String> requires = new ArrayList<>();
        for (final String required : node.strings("requires")) {
            requires.add(required.trim());
        }
        return new Quest(id, kind, node.requireString("name").trim(), node.string("zone", CAMP).trim(),
                node.requireString("giver").trim(), requires, blankToNull(node.string("after", null)),
                Math.max(1, node.integer("level", 1)), node.bool("auto", false), node.string("summary", "").trim(),
                node.strings("offer"), stages, points, bonuses, choices);
    }

    private static Objective objective(final YamlNode stage) {
        final String where = stage.path();
        final List<Objective> found = new ArrayList<>();
        if (stage.has("talk")) {
            found.add(new Objective(Goal.TALK, stage.requireString("talk").trim(), null, 1, Filter.ANY, null, 0));
        }
        if (stage.has("checkpoint")) {
            final List<String> parts = stage.strings("checkpoint");
            if (parts.size() != 2) {
                throw new IllegalArgumentException(where + ".checkpoint：要寫成 [區域, 檢查點]");
            }
            found.add(new Objective(Goal.CHECKPOINT, parts.get(0).trim(), parts.get(1).trim(), 1, Filter.ANY, null, 0));
        }
        if (stage.has("zone")) {
            found.add(new Objective(Goal.ZONE, stage.requireString("zone").trim(), null, 1, Filter.ANY, null, 0));
        }
        if (stage.has("boss")) {
            found.add(new Objective(Goal.BOSS, stage.requireString("boss").trim(), null, 1, Filter.ANY, null, 0));
        }
        if (stage.has("kill")) {
            final YamlNode kill = stage.node("kill");
            found.add(new Objective(Goal.KILL, kill.requireString("zone").trim(), null, kill.integer("count", 1),
                    Filter.parse(kill.string("rarity", "any").trim(), kill.path()), null, 0));
        }
        if (stage.has("collect")) {
            final YamlNode collect = stage.node("collect");
            found.add(new Objective(Goal.COLLECT, collect.requireString("zone").trim(), null, collect.integer("count", 1),
                    Filter.parse(collect.string("rarity", "any").trim(), collect.path()), collect.requireString("item").trim(),
                    collect.decimal("chance", 0.15)));
        }
        if (stage.has("level")) {
            found.add(new Objective(Goal.LEVEL, null, null, stage.integer("level", 1), Filter.ANY, null, 0));
        }
        if (stage.has("maps")) {
            found.add(new Objective(Goal.MAPS, null, null, stage.integer("maps", 1), Filter.ANY, null, 0));
        }
        if (stage.has("trial")) {
            found.add(new Objective(Goal.TRIAL, stage.requireString("trial").trim(), null, 1, Filter.ANY, null, 0));
        }
        if (found.size() != 1) {
            throw new IllegalArgumentException(where + "：每一步要剛好一個目標（talk、checkpoint、zone、boss、kill、collect、level、maps、trial），"
                    + (found.isEmpty() ? "卻沒有" : "卻有 " + found.size() + " 個"));
        }
        return found.getFirst();
    }

    private static Bonus bonus(final YamlNode node) {
        final String stat = node.requireString("stat").trim();
        if (node.has("flat")) {
            return new Bonus(stat, ModType.FLAT, node.decimal("flat", 0));
        }
        if (node.has("increased")) {
            return new Bonus(stat, ModType.INCREASED, node.decimal("increased", 0) / 100.0);
        }
        throw new IllegalArgumentException(node.path() + "：獎勵要寫 flat（固定值）或 increased（百分比）");
    }

    private static void checkId(final String id, final String where) {
        if (!ID.matcher(id).matches() || id.length() > MAX_ID) {
            throw new IllegalArgumentException(where + "：代號只能用小寫英文、數字、底線，最多 " + MAX_ID + " 個字");
        }
    }

    private static String blankToNull(final String text) {
        return text == null || text.isBlank() ? null : text.trim();
    }

    // ---- 檢查 ----

    /** 檢查代號都對得上（不檢查試煉的代號）。 */
    public List<String> validate(final Collection<String> zones, final Collection<String> bossZones,
                                 final Collection<String> functionalNpcs, final int levelCap) {
        return validate(zones, bossZones, functionalNpcs, levelCap, null);
    }

    /**
     * 檢查代號都對得上。
     *
     * @param zones          zones.yml 的區域代號
     * @param bossZones      有區域 Boss 的區域
     * @param functionalNpcs 營地 NPC 的代號（identifier、stash…）
     * @param levelCap       等級上限
     * @param trials         ascendancy.yml 的試煉代號（null = 昇華模組沒啟用，不檢查）
     * @return 問題（空的 = 沒問題）
     */
    public List<String> validate(final Collection<String> zones, final Collection<String> bossZones,
                                 final Collection<String> functionalNpcs, final int levelCap, final Collection<String> trials) {
        final List<String> problems = new ArrayList<>();
        final Set<String> speakers = new HashSet<>(npcs.keySet());
        speakers.addAll(functionalNpcs);
        for (final Npc npc : npcs.values()) {
            if (functionalNpcs.contains(npc.id())) {
                problems.add("npcs." + npc.id() + "：跟營地 NPC 的代號重複");
            }
            if (npc.name().isBlank() && (npc.opens() != null || !npc.idle().isEmpty())) {
                problems.add("npcs." + npc.id() + "：沒有名字（只是擺設的人不要寫 opens、idle）");
            }
            if (npc.opens() != null && !FUNCTIONS.containsKey(npc.opens())) {
                problems.add("npcs." + npc.id() + ".opens：不認得的功能 " + npc.opens() + "（可以用 " + String.join("、", FUNCTIONS.keySet()) + "）");
            }
            final Placement place = npc.placement();
            if (place != null && !CAMP.equals(place.zone())) {
                if (!zones.contains(place.zone())) {
                    problems.add("npcs." + npc.id() + ".place：沒有區域 " + place.zone());
                }
                if (!CHECKPOINTS.contains(place.checkpoint())) {
                    problems.add("npcs." + npc.id() + ".place：檢查點要是 entrance、midway 或 boss");
                }
            }
        }
        for (final Quest quest : quests.values()) {
            final String where = "quests." + quest.id();
            if (!speakers.contains(quest.giver())) {
                problems.add(where + ".giver：沒有 NPC " + quest.giver());
            }
            if (!CAMP.equals(quest.zone()) && !zones.contains(quest.zone())) {
                problems.add(where + ".zone：沒有區域 " + quest.zone());
            }
            if (quest.stages().isEmpty()) {
                problems.add(where + "：沒有任何一步（stages）");
            }
            for (final String required : quest.requires()) {
                if (!quests.containsKey(required) || required.equals(quest.id())) {
                    problems.add(where + ".requires：沒有任務 " + required);
                }
            }
            if (quest.after() != null && (!quests.containsKey(quest.after()) || quest.after().equals(quest.id()))) {
                problems.add(where + ".after：沒有任務 " + quest.after());
            }
            if (quest.level() > levelCap) {
                problems.add(where + ".level：超過等級上限 " + levelCap);
            }
            if (quest.passivePoints() < 0) {
                problems.add(where + "：天賦點不能是負的");
            }
            for (int i = 0; i < quest.stages().size(); i++) {
                checkObjective(problems, where + ".stages[" + i + "]", quest.stages().get(i).objective(), speakers, zones, bossZones,
                        levelCap, trials);
            }
            for (final Bonus bonus : quest.bonuses()) {
                checkBonus(problems, where + ".rewards", bonus);
            }
            if (!quest.choices().isEmpty()) {
                if (quest.choices().size() < 2) {
                    problems.add(where + ".choose：至少要兩個選項");
                }
                if (!quest.stages().isEmpty() && quest.stages().getLast().objective().goal() != Goal.TALK) {
                    problems.add(where + ".choose：有幾選一的任務，最後一步要是 talk（在對話視窗裡選）");
                }
                for (final Choice choice : quest.choices()) {
                    if (choice.label().isBlank()) {
                        problems.add(where + ".choose." + choice.id() + "：沒有 label");
                    }
                    if (choice.bonuses().isEmpty()) {
                        problems.add(where + ".choose." + choice.id() + "：沒有獎勵");
                    }
                    for (final Bonus bonus : choice.bonuses()) {
                        checkBonus(problems, where + ".choose." + choice.id(), bonus);
                    }
                }
            }
        }
        // 前置任務不能繞一圈
        for (final Quest quest : quests.values()) {
            if (cycle(quest.id(), new HashSet<>())) {
                problems.add("quests." + quest.id() + "：requires／after 繞成一圈了");
            }
        }
        return problems;
    }

    private boolean cycle(final String id, final Set<String> visiting) {
        if (!visiting.add(id)) {
            return true;
        }
        final Quest quest = quests.get(id);
        if (quest != null) {
            final List<String> parents = new ArrayList<>(quest.requires());
            if (quest.after() != null) {
                parents.add(quest.after());
            }
            for (final String parent : parents) {
                if (quests.containsKey(parent) && cycle(parent, visiting)) {
                    return true;
                }
            }
        }
        visiting.remove(id);
        return false;
    }

    private static void checkObjective(final List<String> problems, final String where, final Objective objective,
                                       final Set<String> speakers, final Collection<String> zones,
                                       final Collection<String> bossZones, final int levelCap, final Collection<String> trials) {
        switch (objective.goal()) {
            case TALK -> {
                if (!speakers.contains(objective.target())) {
                    problems.add(where + ".talk：沒有 NPC " + objective.target());
                }
            }
            case CHECKPOINT, ZONE, KILL, COLLECT -> {
                if (!zones.contains(objective.target())) {
                    problems.add(where + "：沒有區域 " + objective.target());
                }
            }
            case BOSS -> {
                if (!bossZones.contains(objective.target())) {
                    problems.add(where + ".boss：區域 " + objective.target() + " 沒有區域 Boss");
                }
            }
            case LEVEL -> {
                if (objective.count() < 1 || objective.count() > levelCap) {
                    problems.add(where + ".level：要在 1 到 " + levelCap + " 之間");
                }
            }
            case MAPS -> {
                if (objective.count() < 1) {
                    problems.add(where + ".maps：至少 1 張");
                }
            }
            case TRIAL -> {
                if (trials != null && !trials.contains(objective.target())) {
                    problems.add(where + ".trial：沒有試煉 " + objective.target() + "（ascendancy.yml）");
                }
            }
        }
        if (objective.goal() == Goal.CHECKPOINT && !CHECKPOINTS.contains(objective.checkpoint())) {
            problems.add(where + ".checkpoint：檢查點要是 entrance、midway 或 boss，卻是「" + objective.checkpoint() + "」");
        }
        if (objective.goal().counted() && objective.count() < 1) {
            problems.add(where + "：count 至少 1");
        }
        if (objective.goal() == Goal.COLLECT && (objective.chance() <= 0 || objective.chance() > 1)) {
            problems.add(where + ".collect.chance：要在 0 到 1 之間（不含 0）");
        }
    }

    private static void checkBonus(final List<String> problems, final String where, final Bonus bonus) {
        if (!QuestRewards.STATS.containsKey(bonus.stat())) {
            problems.add(where + "：不支援的屬性 " + bonus.stat() + "（可以用：" + String.join("、", QuestRewards.STATS.keySet()) + "）");
        } else if (!QuestRewards.allowed(bonus.stat(), bonus.type())) {
            problems.add(where + "：" + bonus.stat() + " 要寫" + (bonus.type() == ModType.FLAT ? " increased（百分比）" : " flat（固定值）")
                    + "，寫成另一種不會有效果");
        }
        if (bonus.value() <= 0) {
            problems.add(where + "：" + bonus.stat() + " 的數值要是正的");
        }
    }

    // ---- 查詢 ----

    public Map<String, Npc> npcs() {
        return Collections.unmodifiableMap(npcs);
    }

    public Npc npc(final String id) {
        return id == null ? null : npcs.get(id.toLowerCase(Locale.ROOT));
    }

    /** 全部任務（照設定檔的順序）。 */
    public Collection<Quest> quests() {
        return Collections.unmodifiableCollection(quests.values());
    }

    public Quest quest(final String id) {
        return id == null ? null : quests.get(id);
    }

    public List<Quest> quests(final Kind kind) {
        final List<Quest> list = new ArrayList<>();
        for (final Quest quest : quests.values()) {
            if (quest.kind() == kind) {
                list.add(quest);
            }
        }
        return list;
    }

    /** 所有任務加起來最多給幾個天賦點（網頁規劃器用）。 */
    public int totalPassivePoints() {
        int total = 0;
        for (final Quest quest : quests.values()) {
            total += quest.passivePoints();
        }
        return total;
    }
}
