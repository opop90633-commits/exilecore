package tw.exilecore.quest;

import net.kyori.adventure.title.Title;
import org.bukkit.Color;
import org.bukkit.Location;
import org.bukkit.Particle;
import org.bukkit.Sound;
import org.bukkit.entity.Entity;
import org.bukkit.entity.Player;
import tw.exilecore.ExileCore;
import tw.exilecore.character.PlayerSettings;
import tw.exilecore.character.PlayerState;
import tw.exilecore.item.ItemRenderer;
import tw.exilecore.monster.MonsterRarity;
import tw.exilecore.npc.NpcType;
import tw.exilecore.stats.Modifier;
import tw.exilecore.zone.Zone;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.random.RandomGenerator;

/**
 * 任務的執行期（主執行緒）：把遊戲裡發生的事（說話、殺怪、打倒 Boss、發現檢查點、升級、完成地圖）交給 {@link QuestLog}，
 * 把結果寫回角色進度、在畫面上提示、更新任務追蹤；任務的永久獎勵（天賦點、屬性）由 {@link QuestRewards} 從進度算出來。
 */
public final class QuestService {

    /** NPC 頭上的光點：多遠以內看得到。 */
    private static final double MARKER_RANGE = 40;
    private static final Particle.DustOptions OFFER_DUST = new Particle.DustOptions(Color.fromRGB(0xF5B942), 1.3f);
    private static final Particle.DustOptions TURN_IN_DUST = new Particle.DustOptions(Color.fromRGB(0x5CFF5C), 1.3f);

    /** 計數每幾次寫一次資料庫（中間的只在記憶體；換步、下線、每 30 秒也會寫）。 */
    private static final int SAVE_EVERY = 5;

    /** 還沒寫進資料庫的計數：哪個角色的、鍵 → 值。 */
    private record Unsaved(long character, Map<String, String> values) {
    }

    private final ExileCore plugin;
    private final QuestConfig config;
    private final QuestDialogs dialogs;
    private final Map<java.util.UUID, Unsaved> unsaved = new java.util.HashMap<>();
    /** 接在任務追蹤後面的其他內容（每日委託）。 */
    private final List<java.util.function.Function<Player, List<String>>> trackerSections = new ArrayList<>();
    private StoryNpcs storyNpcs;

    public QuestService(final ExileCore plugin, final QuestConfig config) {
        this.plugin = plugin;
        this.config = config;
        this.dialogs = new QuestDialogs(plugin, this);
    }

    public QuestConfig config() {
        return config;
    }

    QuestDialogs dialogs() {
        return dialogs;
    }

    void storyNpcs(final StoryNpcs placer) {
        this.storyNpcs = placer;
    }

    /** 劇情世界的劇情人物：已經載入的區塊補上、移回去（剛擺好版面時呼叫）。 */
    public void placeStoryNpcs() {
        if (storyNpcs != null) {
            storyNpcs.applyLoaded();
        }
    }

    /**
     * /ec npc move 搬了劇情世界自動擺的劇情人物（2026-10-15）：把新位置寫回 data/quests.yml 的 place
     * （at、y、yaw；只改那一行），之後重讀任務設定——不然區塊重新載入、重開伺服器時會被移回原位。
     *
     * @return null = 存好了（或這個人物不是自動擺的，不用存）；其他 = 存不了的原因
     */
    public String saveStoryPlace(final String id, final org.bukkit.Location to) {
        final QuestConfig.Npc npc = config.npc(id);
        if (npc == null || npc.placement() == null || storyNpcs == null) {
            return null;
        }
        final org.bukkit.Location spot = storyNpcs.spots().get(id);
        if (spot == null) {
            return "找不到 " + id + " 原本的擺放位置（區域或檢查點不見了）";
        }
        if (to.getWorld() == null || !to.getWorld().equals(spot.getWorld())) {
            return "劇情人物只能搬在劇情世界裡（" + spot.getWorld().getName() + "），不然會被收回去";
        }
        final QuestConfig.Placement place = npc.placement();
        final double anchorX = spot.getX() - place.x();
        final double anchorY = spot.getY() - place.y();
        final double anchorZ = spot.getZ() - place.z();
        final java.nio.file.Path file = plugin.dataFolder().resolve("data").resolve(QuestConfig.FILE);
        try {
            if (!java.nio.file.Files.exists(file)) {
                return "data/" + QuestConfig.FILE + " 不存在";
            }
            final String changed = tw.exilecore.core.config.YamlPatch.storyPlacement(java.nio.file.Files.readString(file), id,
                    to.getX() - anchorX, to.getZ() - anchorZ, Math.round(to.getY() - anchorY), Math.round(org.bukkit.Location.normalizeYaw(to.getYaw())));
            if (changed == null) {
                return "data/" + QuestConfig.FILE + " 裡找不到 " + id + " 的 place";
            }
            java.nio.file.Files.writeString(file, changed);
        } catch (final java.io.IOException e) {
            plugin.getSLF4JLogger().error("存劇情人物 {} 的位置失敗", id, e);
            return "寫入 data/" + QuestConfig.FILE + " 失敗：" + e.getMessage();
        }
        return null;
    }

    // ---- 狀態 ----

    /** 角色載入好了才有任務。 */
    boolean ready(final Player player) {
        final PlayerState state = plugin.players().get(player);
        return state.isLoaded() && state.hasCharacter();
    }

    /** 這個玩家現在的任務狀態（一份新的，做完動作再 {@link #apply}）。 */
    QuestLog log(final Player player) {
        final PlayerState state = plugin.players().get(player);
        String zone = null;
        if (plugin.zones() != null) {
            zone = plugin.zones().zoneAt(player.getLocation()).map(Zone::id).orElse(null);
        }
        return new QuestLog(config, state.progress(), state.level(), zone, System.currentTimeMillis() / 1000);
    }

    /** 完成的任務給的屬性（重算屬性表時呼叫；state 是正在重算的那個）。 */
    public List<Modifier> modifiers(final PlayerState state) {
        if (!state.hasCharacter()) {
            return List.of();
        }
        return QuestRewards.modifiers(config, state.progress());
    }

    /** 完成的任務給的天賦點。 */
    public int passivePoints(final PlayerState state) {
        if (!state.hasCharacter()) {
            return 0;
        }
        return QuestRewards.passivePoints(config, state.progress());
    }

    /**
     * 套用一次動作的結果：寫進度、提示、更新任務追蹤與屬性。
     *
     * @param chat false = 不在聊天欄重複說（對話視窗已經顯示了）
     */
    void apply(final Player player, final QuestLog log, final boolean chat) {
        final PlayerState state = plugin.players().get(player);
        // 完成與否變了（完成、管理員重設）：永久獎勵跟著變，屬性要重算
        boolean rewardsChanged = false;
        for (final QuestLog.Write write : log.writes()) {
            if (write.key().startsWith(QuestLog.PREFIX)) {
                final boolean before = QuestLog.parse(state.progress().get(write.key())).phase() == QuestLog.Phase.DONE;
                final boolean after = QuestLog.parse(write.value()).phase() == QuestLog.Phase.DONE;
                rewardsChanged |= before != after;
            }
        }
        for (final QuestLog.Write write : log.writes()) {
            if (write.value() != null && throttled(write.value()) && state.character() != null) {
                // 殺怪的計數：先記在記憶體，每 5 次（或換步、下線、每 30 秒）才寫進資料庫
                state.progress().put(write.key(), write.value());
                unsaved.computeIfAbsent(player.getUniqueId(), uuid -> new Unsaved(state.character().id(), new java.util.HashMap<>()))
                        .values().put(write.key(), write.value());
                continue;
            }
            final Unsaved pending = unsaved.get(player.getUniqueId());
            if (pending != null) {
                pending.values().remove(write.key());
            }
            if (write.value() == null) {
                plugin.characters().removeProgress(player, write.key());
            } else {
                plugin.characters().setProgress(player, write.key(), write.value());
            }
        }
        for (final QuestLog.Event event : log.events()) {
            final QuestConfig.Quest quest = event.quest();
            final String name = quest.kind().color() + ItemRenderer.escape(quest.name());
            switch (event) {
                case QuestLog.Accepted accepted -> {
                    player.sendMessage(plugin.text().prefixed((quest.auto() ? "<#f5b942>新任務" : "<#f5b942>接下任務")
                            + "<gray>（" + quest.kind().displayName() + "）：" + name
                            + "<gray> — " + ItemRenderer.escape(quest.stage(0).text())));
                    player.playSound(player.getLocation(), Sound.BLOCK_NOTE_BLOCK_CHIME, 0.6f, 1.2f);
                }
                case QuestLog.Advanced advanced -> {
                    narrate(player, quest, advanced.done());
                    player.sendMessage(plugin.text().prefixed("<gray>任務更新 " + name + "<gray>：<white>"
                            + ItemRenderer.escape(quest.stage(advanced.next()).text())));
                    player.playSound(player.getLocation(), Sound.BLOCK_NOTE_BLOCK_BELL, 0.5f, 1.6f);
                }
                case QuestLog.Counted counted -> {
                    final QuestConfig.Objective objective = quest.stage(counted.stage()).objective();
                    if (objective.goal() == QuestConfig.Goal.COLLECT) {
                        state.notice("<#7fd4ff>撿到 " + ItemRenderer.escape(objective.item()) + " <white>" + counted.count() + "/"
                                + counted.needed(), plugin.getServer().getCurrentTick() + 50);
                        player.playSound(player.getLocation(), Sound.ENTITY_ITEM_PICKUP, 0.5f, 1.4f);
                    } else {
                        state.notice("<gray>" + ItemRenderer.escape(quest.name()) + " <white>" + counted.count() + "/" + counted.needed(),
                                plugin.getServer().getCurrentTick() + 40);
                    }
                }
                case QuestLog.Completed done -> {
                    if (quest.stages().getLast().objective().goal() != QuestConfig.Goal.TALK) {
                        narrate(player, quest, quest.stages().size() - 1);
                    }
                    final List<String> rewards = QuestRewards.describe(quest, done.choice());
                    player.showTitle(Title.title(plugin.text().parse("<#f5b942>任務完成"), plugin.text().parse(name),
                            Title.Times.times(Duration.ofMillis(300), Duration.ofMillis(2600), Duration.ofMillis(700))));
                    player.playSound(player.getLocation(), Sound.UI_TOAST_CHALLENGE_COMPLETE, 0.7f, 1.0f);
                    player.sendMessage(plugin.text().prefixed("<#f5b942>任務完成：" + name
                            + (rewards.isEmpty() ? "" : "<gray>　獎勵：<white>" + ItemRenderer.escape(String.join("、", rewards)))
                            + (quest.passivePoints() > 0 ? "<gray>（<white>/tree</white> 配點）" : "")));
                }
                case QuestLog.Spoke spoke -> {
                    if (chat) {
                        speak(player, spoke.quest().stage(spoke.stage()));
                    }
                }
                case QuestLog.Waiting waiting -> {
                }
            }
        }
        if (rewardsChanged) {
            // 永久獎勵：屬性與天賦點跟著完成的任務算
            plugin.players().markDirty(player);
            plugin.players().display(player, state);
        }
        updateTracker(player);
    }

    /** 這個狀態可以晚一點寫：進行中、有計數、不是 5 的倍數。 */
    static boolean throttled(final String value) {
        final QuestLog.State state = QuestLog.parse(value);
        return state.phase() == QuestLog.Phase.ACTIVE && state.count() > 0 && state.count() % SAVE_EVERY != 0;
    }

    /** 把還沒寫的計數寫進資料庫（要還是同一個角色）。 */
    public void flush(final Player player) {
        final Unsaved pending = unsaved.remove(player.getUniqueId());
        if (pending == null || pending.values().isEmpty()) {
            return;
        }
        final PlayerState state = plugin.players().find(player.getUniqueId()).orElse(null);
        if (state == null || state.character() == null || state.character().id() != pending.character()) {
            return; // 換了角色：上一個角色最後幾次計數不要了（最多 30 秒）
        }
        for (final Map.Entry<String, String> entry : pending.values().entrySet()) {
            if (entry.getValue().equals(state.progress().get(entry.getKey()))) {
                plugin.characters().setProgress(player, entry.getKey(), entry.getValue());
            }
        }
    }

    /** 每 30 秒：所有人還沒寫的計數。 */
    public void flushAll() {
        for (final Player player : plugin.getServer().getOnlinePlayers()) {
            flush(player);
        }
    }

    /** 不是說話的那一步完成時的旁白。 */
    private void narrate(final Player player, final QuestConfig.Quest quest, final int stage) {
        final QuestConfig.Stage done = quest.stage(stage);
        if (done.objective().goal() == QuestConfig.Goal.TALK) {
            return;
        }
        for (final String line : done.lines()) {
            player.sendMessage(plugin.text().parse("<gray><i>" + line));
        }
    }

    /** 對話視窗沒開成時，台詞寫在聊天欄。 */
    private void speak(final Player player, final QuestConfig.Stage stage) {
        final String speaker = speakerName(stage.objective().target());
        for (final String line : stage.lines()) {
            player.sendMessage(plugin.text().parse("<#f5b942>" + ItemRenderer.escape(speaker) + "<gray>：<white>" + line));
        }
    }

    /** NPC 的名字（劇情人物或營地 NPC）。 */
    String speakerName(final String npc) {
        final QuestConfig.Npc story = config.npc(npc);
        if (story != null) {
            return story.name();
        }
        final NpcType type = NpcType.parse(npc);
        return type == null ? npc : type.displayName();
    }

    /** NPC 的頭銜（劇情人物才有）。 */
    String speakerTitle(final String npc) {
        final QuestConfig.Npc story = config.npc(npc);
        return story == null ? "" : story.title();
    }

    // ---- 遊戲裡發生的事 ----

    /** 角色載入（登入、換角色）：自動開始的任務、已經做到的目標，任務追蹤。 */
    public void onCharacterApplied(final Player player) {
        final Unsaved pending = unsaved.get(player.getUniqueId());
        final PlayerState current = plugin.players().get(player);
        if (pending != null && (current.character() == null || current.character().id() != pending.character())) {
            unsaved.remove(player.getUniqueId()); // 上一個角色的
        }
        if (!ready(player)) {
            plugin.sidebar().quests(player, List.of());
            return;
        }
        final QuestLog log = log(player);
        log.settleAll();
        apply(player, log, true);
    }

    /** 發現檢查點、進入區域、升級、完成地圖之後：已經做到的目標往下走。 */
    public void refresh(final Player player) {
        if (!ready(player)) {
            return;
        }
        final QuestLog log = log(player);
        log.settleAll();
        if (!log.writes().isEmpty() || !log.events().isEmpty()) {
            apply(player, log, true);
        }
    }

    /** 殺了一隻怪（有份的每個人各算一次）。 */
    public void onKill(final Player player, final Location where, final MonsterRarity rarity) {
        if (!ready(player) || plugin.zones() == null) {
            return;
        }
        final String zone = plugin.zones().zoneAt(where).map(Zone::id).orElse(null);
        if (zone == null) {
            return;
        }
        final QuestLog log = log(player);
        log.kill(zone, rarity, random());
        if (!log.writes().isEmpty() || !log.events().isEmpty()) {
            apply(player, log, true);
        }
    }

    /** 打倒區域 Boss（有份的每個人各算一次）。 */
    public void onBoss(final Player player, final String zone) {
        if (!ready(player)) {
            return;
        }
        final QuestLog log = log(player);
        log.boss(zone);
        if (!log.writes().isEmpty() || !log.events().isEmpty()) {
            apply(player, log, true);
        }
    }

    private RandomGenerator random() {
        return plugin.items().random();
    }

    // ---- 對話 ----

    /**
     * 右鍵 NPC。有任務的事就跳對話視窗（回傳 true）；營地 NPC 沒事時回傳 false，由呼叫端照舊打開功能。
     *
     * @param function 營地 NPC 的功能（劇情人物是 null）
     */
    public boolean talk(final Player player, final String npc, final Runnable function) {
        if (!ready(player)) {
            if (function == null) {
                player.sendMessage(plugin.text().prefixed("<red>先建立角色（/char）"));
                return true;
            }
            return false;
        }
        final QuestLog log = log(player);
        log.talk(npc);
        final List<QuestLog.Spoke> spoken = new ArrayList<>();
        for (final QuestLog.Event event : log.events()) {
            if (event instanceof QuestLog.Spoke spoke) {
                spoken.add(spoke);
            }
        }
        apply(player, log, false);
        if (spoken.isEmpty() && !hasBusiness(player, npc)) {
            if (function != null) {
                return false;
            }
            dialogs.idle(player, npc);
            return true;
        }
        dialogs.converse(player, npc, spoken, function);
        return true;
    }

    /** 這個 NPC 有沒有任務的事（可以接的任務、要選的獎勵）。 */
    boolean hasBusiness(final Player player, final String npc) {
        final QuestLog log = log(player);
        return !log.offers(npc).isEmpty() || waitingChoice(log, npc) != null;
    }

    /** 最後一步在等這個 NPC、要選獎勵的任務。 */
    static QuestConfig.Quest waitingChoice(final QuestLog log, final String npc) {
        for (final QuestConfig.Quest quest : log.active()) {
            final QuestLog.State state = log.state(quest);
            final QuestConfig.Stage stage = quest.stage(state.stage());
            if (quest.isLast(state.stage()) && !quest.choices().isEmpty() && stage.objective().goal() == QuestConfig.Goal.TALK
                    && stage.objective().target().equals(npc)) {
                return quest;
            }
        }
        return null;
    }

    /** 接任務（對話視窗的按鈕）。 */
    boolean accept(final Player player, final QuestConfig.Quest quest) {
        if (!ready(player)) {
            return false;
        }
        final QuestLog log = log(player);
        if (!log.accept(quest)) {
            return false;
        }
        apply(player, log, true);
        return true;
    }

    /** 選獎勵（對話視窗的按鈕）。 */
    boolean choose(final Player player, final QuestConfig.Quest quest, final String npc, final String choice) {
        if (!ready(player)) {
            return false;
        }
        final QuestLog log = log(player);
        if (!log.choose(quest, npc, choice)) {
            return false;
        }
        apply(player, log, false);
        return true;
    }

    /** 追蹤這個任務（任務日誌）。 */
    boolean track(final Player player, final QuestConfig.Quest quest) {
        final QuestLog log = log(player);
        if (!log.track(quest)) {
            return false;
        }
        apply(player, log, false);
        return true;
    }

    // ---- 任務追蹤與 NPC 頭上的光點 ----

    /** 任務追蹤後面接上其他內容（每日委託）；每次重畫時呼叫。 */
    public void addTrackerSection(final java.util.function.Function<Player, List<String>> section) {
        trackerSections.add(section);
    }

    /** 重畫任務追蹤（設定改了、任務變了、每日委託變了）。 */
    public void updateTracker(final Player player) {
        if (!ready(player) || !PlayerSettings.enabled(plugin.players().get(player), PlayerSettings.QUEST_TRACKER)) {
            plugin.sidebar().quests(player, List.of());
            return;
        }
        final List<String> extra = new ArrayList<>();
        for (final java.util.function.Function<Player, List<String>> section : trackerSections) {
            try {
                extra.addAll(section.apply(player));
            } catch (final RuntimeException e) {
                plugin.getSLF4JLogger().error("任務追蹤的其他內容出錯", e);
            }
        }
        plugin.sidebar().quests(player, QuestText.trackerLines(log(player), extra));
    }

    public void forget(final Player player) {
        flush(player); // 側欄自己在下線時清掉
    }

    /** 關掉所有人的任務追蹤（模組停用）。 */
    public void hideAll() {
        for (final Player player : plugin.getServer().getOnlinePlayers()) {
            plugin.sidebar().quests(player, List.of());
        }
    }

    /** 每秒：附近的 NPC 有新任務（金色）、可以回報（綠色）就在它頭上冒光點，只有自己看得到。 */
    public void tickMarkers() {
        for (final Player player : plugin.getServer().getOnlinePlayers()) {
            if (!ready(player) || plugin.npcs() == null) {
                continue;
            }
            QuestLog log = null;
            for (final Entity entity : player.getNearbyEntities(MARKER_RANGE, MARKER_RANGE / 2, MARKER_RANGE)) {
                final String npc = plugin.npcs().questId(entity);
                if (npc == null) {
                    continue;
                }
                if (log == null) {
                    log = log(player);
                }
                final boolean turnIn = log.waitsFor(npc);
                if (!turnIn && log.offers(npc).isEmpty()) {
                    continue;
                }
                final Location at = entity.getLocation().add(0, 2.45, 0);
                player.spawnParticle(Particle.DUST, at, 5, 0.04, 0.22, 0.04, 0, turnIn ? TURN_IN_DUST : OFFER_DUST);
            }
        }
    }

    /** 管理員：設定一個任務的狀態（start／stage／complete／reset）。 */
    void admin(final Player player, final QuestLog log) {
        apply(player, log, true);
    }

    /** 完成的任務數（任務日誌的標題用）。 */
    static int[] counts(final QuestConfig config, final QuestLog log, final QuestConfig.Kind kind) {
        int done = 0;
        int total = 0;
        for (final QuestConfig.Quest quest : config.quests(kind)) {
            total++;
            if (log.state(quest).phase() == QuestLog.Phase.DONE) {
                done++;
            }
        }
        return new int[] {done, total};
    }

    /** 進度的唯讀視圖（任務日誌、獎勵總覽用）。 */
    Map<String, String> progress(final Player player) {
        return plugin.players().get(player).progress();
    }
}
