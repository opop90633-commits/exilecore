package tw.exilecore.quest;

import tw.exilecore.monster.MonsterRarity;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.random.RandomGenerator;

/**
 * 一個角色的任務狀態機（純邏輯）：讀角色進度（{@code q.任務代號}），做一件事（接任務、說話、殺怪、打倒 Boss…），
 * 算出要寫回角色進度的變動（{@link #writes()}）與發生了什麼（{@link #events()}，給畫面上的提示與對話視窗用）。
 * <p>
 * 狀態的寫法：進行中 {@code a.第幾步.計數}、完成 {@code d.完成的時間（秒）[.選的獎勵]}，沒開始過就沒有這一筆。
 * 打過的 Boss、發現過的檢查點、去過的區域、等級、完成過的地圖與試煉直接看角色進度（{@code boss.}、{@code cp.}、{@code seen.}、
 * {@code atlas.}、{@code trial.}），所以舊角色接了任務就能往前走。
 */
public final class QuestLog {

    public static final String PREFIX = "q.";
    /** 追蹤中的任務（任務追蹤排第一個）。 */
    public static final String TRACK = PREFIX + "track";

    /** 任務在這個角色身上的階段。 */
    public enum Phase {
        /** 還不能接（前置任務沒完成、等級不夠）。 */
        LOCKED,
        /** 可以接了。 */
        AVAILABLE,
        /** 進行中。 */
        ACTIVE,
        /** 完成了。 */
        DONE
    }

    /**
     * 讀出來的狀態（沒開始過的是 LOCKED；能不能接要看 {@link #phase}）。
     *
     * @param stage  進行中：第幾步（從 0 開始）
     * @param count  進行中：這一步的計數
     * @param doneAt 完成：完成的時間（秒）
     * @param choice 完成：選的獎勵（沒有幾選一是 null）
     */
    public record State(Phase phase, int stage, int count, long doneAt, String choice) {
        static final State NONE = new State(Phase.LOCKED, 0, 0, 0, null);

        public boolean started() {
            return phase == Phase.ACTIVE || phase == Phase.DONE;
        }
    }

    /** 要寫回角色進度的一筆（value 為 null = 刪掉）。 */
    public record Write(String key, String value) {
    }

    /** 發生了什麼。 */
    public sealed interface Event permits Accepted, Advanced, Counted, Completed, Spoke, Waiting {
        QuestConfig.Quest quest();
    }

    /** 接了任務（自動開始的也算）。 */
    public record Accepted(QuestConfig.Quest quest) implements Event {
    }

    /** 做完了第 done 步，接下來是第 next 步。 */
    public record Advanced(QuestConfig.Quest quest, int done, int next) implements Event {
    }

    /** 計數：第 stage 步殺了幾隻、撿到幾個（到了也會有一次，接著是 Advanced 或 Completed）。 */
    public record Counted(QuestConfig.Quest quest, int stage, int count, int needed) implements Event {
    }

    /** 任務完成（choice：選的獎勵）。 */
    public record Completed(QuestConfig.Quest quest, String choice) implements Event {
    }

    /** 跟 NPC 說話完成了第 stage 步（對話視窗要顯示那一步的台詞）。 */
    public record Spoke(QuestConfig.Quest quest, int stage) implements Event {
    }

    /** 最後一步是跟這個 NPC 說話、而且要選獎勵：等玩家在對話視窗裡選。 */
    public record Waiting(QuestConfig.Quest quest) implements Event {
    }

    private final QuestConfig config;
    private final Map<String, String> progress;
    private final int level;
    private final String zone;
    private final long now;
    private final Map<String, String> writes = new LinkedHashMap<>();
    private final List<Event> events = new ArrayList<>();

    /**
     * @param progress 角色進度（複製一份來改，不會動到原本的）
     * @param level    角色等級
     * @param zone     角色現在所在的區域代號（不在區域 = null）
     * @param now      現在（秒；完成的時間）
     */
    public QuestLog(final QuestConfig config, final Map<String, String> progress, final int level, final String zone, final long now) {
        this.config = config;
        this.progress = new HashMap<>(progress);
        this.level = level;
        this.zone = zone;
        this.now = now;
    }

    // ---- 讀寫狀態 ----

    public static String key(final String questId) {
        return PREFIX + questId;
    }

    public static String active(final int stage, final int count) {
        return "a." + stage + "." + count;
    }

    public static String done(final long at, final String choice) {
        return "d." + at + (choice == null ? "" : "." + choice);
    }

    /** 讀一筆狀態；寫壞的當作沒開始過。 */
    public static State parse(final String value) {
        if (value == null || value.isBlank()) {
            return State.NONE;
        }
        final String[] parts = value.split("\\.", -1);
        try {
            if ("a".equals(parts[0]) && parts.length >= 2) {
                return new State(Phase.ACTIVE, Math.max(0, Integer.parseInt(parts[1])),
                        parts.length > 2 ? Math.max(0, Integer.parseInt(parts[2])) : 0, 0, null);
            }
            if ("d".equals(parts[0])) {
                return new State(Phase.DONE, 0, 0, parts.length > 1 && !parts[1].isBlank() ? Long.parseLong(parts[1]) : 0,
                        parts.length > 2 && !parts[2].isBlank() ? parts[2] : null);
            }
        } catch (final NumberFormatException e) {
            return State.NONE;
        }
        return State.NONE;
    }

    public State state(final QuestConfig.Quest quest) {
        final State state = parse(progress.get(key(quest.id())));
        if (state.phase() == Phase.ACTIVE && state.stage() >= quest.stages().size()) {
            // 任務改短了：停在最後一步
            return new State(Phase.ACTIVE, Math.max(0, quest.stages().size() - 1), 0, 0, null);
        }
        return state;
    }

    public Phase phase(final QuestConfig.Quest quest) {
        final State state = state(quest);
        if (state.started()) {
            return state.phase();
        }
        return lockReason(quest) == null ? Phase.AVAILABLE : Phase.LOCKED;
    }

    /** 為什麼還不能接（任務日誌用）；可以接回傳 null。 */
    public String lockReason(final QuestConfig.Quest quest) {
        for (final String required : quest.requires()) {
            final QuestConfig.Quest before = config.quest(required);
            if (before == null || state(before).phase() != Phase.DONE) {
                return "先完成「" + (before == null ? required : before.name()) + "」";
            }
        }
        if (quest.after() != null) {
            final QuestConfig.Quest before = config.quest(quest.after());
            if (before == null || !state(before).started()) {
                return "先接「" + (before == null ? quest.after() : before.name()) + "」";
            }
        }
        if (level < quest.level()) {
            return "需要等級 " + quest.level();
        }
        return null;
    }

    private void set(final QuestConfig.Quest quest, final String value) {
        put(key(quest.id()), value);
    }

    private void put(final String key, final String value) {
        if (value == null) {
            progress.remove(key);
        } else {
            progress.put(key, value);
        }
        writes.put(key, value);
    }

    /** 要寫回角色進度的變動（同一個鍵只留最後一次）。 */
    public List<Write> writes() {
        final List<Write> list = new ArrayList<>();
        for (final Map.Entry<String, String> entry : writes.entrySet()) {
            list.add(new Write(entry.getKey(), entry.getValue()));
        }
        return list;
    }

    public List<Event> events() {
        return List.copyOf(events);
    }

    /** 改過之後的角色進度（測試、預覽用）。 */
    public Map<String, String> progress() {
        return java.util.Collections.unmodifiableMap(progress);
    }

    // ---- 動作 ----

    /** 接任務（要是 AVAILABLE）；已經做過的目標直接算。 */
    public boolean accept(final QuestConfig.Quest quest) {
        if (phase(quest) != Phase.AVAILABLE) {
            return false;
        }
        set(quest, active(0, 0));
        events.add(new Accepted(quest));
        settle(quest);
        settleAll();
        return true;
    }

    /** 自動開始的任務接起來、已經做到的目標往下走（登入、換角色、發現檢查點、升級、完成地圖…之後呼叫）。 */
    public void settleAll() {
        for (int round = 0; round < 64; round++) {
            boolean changed = false;
            for (final QuestConfig.Quest quest : config.quests()) {
                final Phase phase = phase(quest);
                if (phase == Phase.AVAILABLE && quest.auto()) {
                    set(quest, active(0, 0));
                    events.add(new Accepted(quest));
                    settle(quest);
                    changed = true;
                } else if (phase == Phase.ACTIVE && settle(quest)) {
                    changed = true;
                }
            }
            if (!changed) {
                return;
            }
        }
    }

    /** 目前這一步已經做到了（打過的 Boss…）就往下走；回傳有沒有動。 */
    private boolean settle(final QuestConfig.Quest quest) {
        boolean moved = false;
        for (int guard = 0; guard < 64; guard++) {
            final State state = state(quest);
            if (state.phase() != Phase.ACTIVE) {
                break;
            }
            final QuestConfig.Objective objective = quest.stage(state.stage()).objective();
            if (!objective.goal().retroactive() || !satisfied(objective)) {
                break;
            }
            advance(quest, state.stage(), null);
            moved = true;
        }
        return moved;
    }

    /** 不用另外做就已經達成的目標。 */
    boolean satisfied(final QuestConfig.Objective objective) {
        return switch (objective.goal()) {
            case CHECKPOINT -> progress.containsKey("cp." + objective.target() + "." + objective.checkpoint());
            case ZONE -> progress.containsKey("seen." + objective.target()) || objective.target().equals(zone);
            case BOSS -> progress.containsKey("boss." + objective.target());
            case LEVEL -> level >= objective.count();
            case MAPS -> mapsCompleted() >= objective.count();
            case TRIAL -> progress.containsKey("trial." + objective.target()); // 昇華模組記的（Ascension.trialKey）
            default -> false;
        };
    }

    /** 完成過幾張不同的地圖（星圖的進度 atlas.地圖）。 */
    int mapsCompleted() {
        int count = 0;
        for (final String key : progress.keySet()) {
            if (key.startsWith("atlas.")) {
                count++;
            }
        }
        return count;
    }

    /** 完成目前這一步：還有下一步就換到下一步，沒有就完成任務。 */
    private void advance(final QuestConfig.Quest quest, final int stage, final String choice) {
        if (quest.isLast(stage)) {
            set(quest, done(now, choice));
            events.add(new Completed(quest, choice));
            if (quest.id().equals(progress.get(TRACK))) {
                put(TRACK, null); // 追蹤中的任務完成了：換回預設（第一個進行中的主線）
            }
        } else {
            set(quest, active(stage + 1, 0));
            events.add(new Advanced(quest, stage, stage + 1));
        }
    }

    /** 跟 npc 說話：所有正在等這個 NPC 的那一步都完成（最後一步要選獎勵的先等著）。 */
    public void talk(final String npc) {
        for (final QuestConfig.Quest quest : config.quests()) {
            final State state = state(quest);
            if (state.phase() != Phase.ACTIVE) {
                continue;
            }
            final QuestConfig.Objective objective = quest.stage(state.stage()).objective();
            if (objective.goal() != QuestConfig.Goal.TALK || !objective.target().equals(npc)) {
                continue;
            }
            if (quest.isLast(state.stage()) && !quest.choices().isEmpty()) {
                events.add(new Waiting(quest));
                continue;
            }
            events.add(new Spoke(quest, state.stage()));
            advance(quest, state.stage(), null);
            settle(quest);
        }
        settleAll();
    }

    /** 在對話視窗裡選了獎勵：完成最後一步（要是正在等這個 NPC）。 */
    public boolean choose(final QuestConfig.Quest quest, final String npc, final String choice) {
        final State state = state(quest);
        if (state.phase() != Phase.ACTIVE || !quest.isLast(state.stage()) || quest.choice(choice) == null) {
            return false;
        }
        final QuestConfig.Objective objective = quest.stage(state.stage()).objective();
        if (objective.goal() != QuestConfig.Goal.TALK || !objective.target().equals(npc)) {
            return false;
        }
        events.add(new Spoke(quest, state.stage()));
        advance(quest, state.stage(), choice);
        settleAll();
        return true;
    }

    /** 在 zone 殺了一隻 rarity 的怪：算擊殺、撿任務物品（random 擲機率）。 */
    public void kill(final String killZone, final MonsterRarity rarity, final RandomGenerator random) {
        if (killZone == null) {
            return;
        }
        for (final QuestConfig.Quest quest : config.quests()) {
            final State state = state(quest);
            if (state.phase() != Phase.ACTIVE) {
                continue;
            }
            final QuestConfig.Objective objective = quest.stage(state.stage()).objective();
            if (!objective.goal().counted() || !killZone.equals(objective.target()) || !matches(objective.filter(), rarity)) {
                continue;
            }
            if (objective.goal() == QuestConfig.Goal.COLLECT && random.nextDouble() >= dropChance(objective, rarity)) {
                continue;
            }
            final int count = Math.min(objective.needed(), state.count() + 1);
            events.add(new Counted(quest, state.stage(), count, objective.needed()));
            if (count >= objective.needed()) {
                advance(quest, state.stage(), null);
                settle(quest);
            } else {
                set(quest, active(state.stage(), count));
            }
        }
        settleAll();
    }

    /** 打倒了 zone 的區域 Boss（不管是不是第一次）。 */
    public void boss(final String bossZone) {
        for (final QuestConfig.Quest quest : config.quests()) {
            final State state = state(quest);
            if (state.phase() != Phase.ACTIVE) {
                continue;
            }
            final QuestConfig.Objective objective = quest.stage(state.stage()).objective();
            if (objective.goal() == QuestConfig.Goal.BOSS && objective.target().equals(bossZone)) {
                advance(quest, state.stage(), null);
                settle(quest);
            }
        }
        settleAll();
    }

    static boolean matches(final QuestConfig.Filter filter, final MonsterRarity rarity) {
        return switch (filter) {
            case ANY -> true;
            case MAGIC -> rarity != MonsterRarity.NORMAL;
            case RARE -> rarity == MonsterRarity.RARE || rarity == MonsterRarity.UNIQUE;
        };
    }

    /** 撿任務物品的機率：普通怪照設定、魔法怪 2 倍、稀有以上必掉。 */
    static double dropChance(final QuestConfig.Objective objective, final MonsterRarity rarity) {
        return switch (rarity) {
            case RARE, UNIQUE -> 1;
            case MAGIC -> Math.min(1, objective.chance() * 2);
            default -> objective.chance();
        };
    }

    // ---- 管理員 ----

    /** 直接設成進行中的第 stage 步（不管前置）。 */
    public void forceStage(final QuestConfig.Quest quest, final int stage) {
        set(quest, active(Math.max(0, Math.min(quest.stages().size() - 1, stage)), 0));
        settle(quest);
        settleAll();
    }

    /** 直接完成（有幾選一時用 choice，沒給就選第一個）。 */
    public void forceComplete(final QuestConfig.Quest quest, final String choice) {
        String picked = null;
        if (!quest.choices().isEmpty()) {
            picked = choice != null && quest.choice(choice) != null ? choice : quest.choices().getFirst().id();
        }
        set(quest, done(now, picked));
        events.add(new Completed(quest, picked));
        settleAll();
    }

    /** 清掉一個任務的狀態（變回沒開始過）。 */
    public void reset(final QuestConfig.Quest quest) {
        set(quest, null);
        if (quest.id().equals(progress.get(TRACK))) {
            put(TRACK, null);
        }
    }

    // ---- 查詢 ----

    /** 進行中的任務（照設定檔的順序）。 */
    public List<QuestConfig.Quest> active() {
        final List<QuestConfig.Quest> list = new ArrayList<>();
        for (final QuestConfig.Quest quest : config.quests()) {
            if (state(quest).phase() == Phase.ACTIVE) {
                list.add(quest);
            }
        }
        return list;
    }

    /** 任務追蹤要顯示的：追蹤中的排第一，其他進行中的主線、支線照設定檔順序；最多 max 個。 */
    public List<QuestConfig.Quest> tracked(final int max) {
        final List<QuestConfig.Quest> list = new ArrayList<>();
        final QuestConfig.Quest pinned = config.quest(progress.get(TRACK));
        if (pinned != null && state(pinned).phase() == Phase.ACTIVE) {
            list.add(pinned);
        }
        for (final QuestConfig.Kind kind : QuestConfig.Kind.values()) {
            for (final QuestConfig.Quest quest : config.quests(kind)) {
                if (list.size() >= max) {
                    return list;
                }
                if (!list.contains(quest) && state(quest).phase() == Phase.ACTIVE) {
                    list.add(quest);
                }
            }
        }
        return list;
    }

    /** 追蹤這個任務（要在進行中）。 */
    public boolean track(final QuestConfig.Quest quest) {
        if (state(quest).phase() != Phase.ACTIVE) {
            return false;
        }
        put(TRACK, quest.id());
        return true;
    }

    /** 這個 NPC 給、現在可以接的任務（不含自動開始的）。 */
    public List<QuestConfig.Quest> offers(final String npc) {
        final List<QuestConfig.Quest> list = new ArrayList<>();
        for (final QuestConfig.Quest quest : config.quests()) {
            if (!quest.auto() && quest.giver().equals(npc) && phase(quest) == Phase.AVAILABLE) {
                list.add(quest);
            }
        }
        return list;
    }

    /** 有沒有哪個任務的這一步是跟這個 NPC 說話。 */
    public boolean waitsFor(final String npc) {
        for (final QuestConfig.Quest quest : config.quests()) {
            final State state = state(quest);
            if (state.phase() != Phase.ACTIVE) {
                continue;
            }
            final QuestConfig.Objective objective = quest.stage(state.stage()).objective();
            if (objective.goal() == QuestConfig.Goal.TALK && objective.target().equals(npc)) {
                return true;
            }
        }
        return false;
    }
}
