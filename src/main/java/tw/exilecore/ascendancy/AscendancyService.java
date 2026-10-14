package tw.exilecore.ascendancy;

import net.kyori.adventure.title.Title;
import org.bukkit.Sound;
import org.bukkit.entity.Player;
import tw.exilecore.ExileCore;
import tw.exilecore.character.CharacterClass;
import tw.exilecore.character.CharacterRecord;
import tw.exilecore.character.PlayerState;
import tw.exilecore.economy.EconomyLedger;
import tw.exilecore.item.ItemRenderer;
import tw.exilecore.map.MapInstance;
import tw.exilecore.map.MapService;
import tw.exilecore.map.MapStats;
import tw.exilecore.stats.Modifier;
import tw.exilecore.zone.Zone;

import java.time.Duration;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

/**
 * 昇華的執行期（主執行緒）：開試煉（地圖世界的特別實例）、試煉完成給昇華點、選昇華職業、配點與退點、
 * 昇華天賦與分身獎勵的修飾器（重算屬性表時）、任務追蹤上的提醒。狀態都在角色進度裡（見 {@link Ascension}）。
 */
public final class AscendancyService {

    /** 昇華的顏色。 */
    static final String COLOR = "<#c77dff>";
    /** 試煉傳送門的粒子顏色（熔爐的火）。 */
    private static final int TRIAL_COLOR = 0xFF8A3D;

    private final ExileCore plugin;
    private final AscendancyConfig config;
    private final LevelRaceService race;
    /** 開了試煉之後，什麼時候才能再開（毫秒）。 */
    private final Map<UUID, Long> trialReadyAt = new HashMap<>();

    public AscendancyService(final ExileCore plugin, final AscendancyConfig config) {
        this.plugin = plugin;
        this.config = config;
        this.race = new LevelRaceService(plugin, config);
    }

    /** 衝等活動（2026-10）。 */
    public LevelRaceService race() {
        return race;
    }

    public AscendancyConfig config() {
        return config;
    }

    // ---- 狀態 ----

    /** 角色載入好了才有昇華。 */
    boolean ready(final Player player) {
        final PlayerState state = plugin.players().get(player);
        return state.isLoaded() && state.hasCharacter();
    }

    Ascension ascension(final Player player) {
        return ascension(plugin.players().get(player));
    }

    /** 職業分成兩個以前建的混沌術士還沒選要當元素法師還是混沌術士（選之前不能選昇華職業）。 */
    private boolean splitPending(final Player player) {
        final PlayerState state = plugin.players().get(player);
        return state.hasCharacter() && tw.exilecore.character.ClassSplit.pending(state.character().characterClass(), state.progress());
    }

    public Ascension ascension(final PlayerState state) {
        final CharacterRecord character = state.character();
        return new Ascension(config, state.progress(), character == null ? null : character.characterClass().id(), state.level());
    }

    /** 重算屬性表時：配好的昇華天賦＋分身獎勵（state 是正在重算的那個）。 */
    public List<Modifier> modifiers(final Player player, final PlayerState state) {
        if (!state.hasCharacter()) {
            return List.of();
        }
        final List<Modifier> list = new ArrayList<>(ascension(state).modifiers());
        if (player != null) { // 網頁計算器的虛擬角色（player 是 null）：分身獎勵用快照記下來的（legacyModifiers）
            list.addAll(Legacy.modifiers(legacy(player, state), AscendancyService::className));
        }
        return list;
    }

    /** 分身獎勵的修飾器（帳號的其他角色給的；2026-10 網頁計算器的快照記下來）。 */
    public List<Modifier> legacyModifiers(final Player player, final PlayerState state) {
        if (player == null || !state.hasCharacter()) {
            return List.of();
        }
        return Legacy.modifiers(legacy(player, state), AscendancyService::className);
    }

    /** 職業的名字（分身獎勵的說明）。 */
    static String className(final String classId) {
        final CharacterClass characterClass = CharacterClass.parse(classId, null);
        return characterClass == null ? classId : characterClass.displayName();
    }

    static String classColor(final String classId) {
        final CharacterClass characterClass = CharacterClass.parse(classId, null);
        return characterClass == null ? "<white>" : characterClass.color();
    }

    /** TAB 列表、選單上的職業名：昇華了寫昇華職業（帶顏色）；還沒有回傳 null。 */
    public String ascendancyName(final PlayerState state) {
        if (!state.hasCharacter()) {
            return null;
        }
        final AscendancyConfig.Ascendancy chosen = ascension(state).chosen();
        return chosen == null ? null : chosen.colored();
    }

    // ---- 分身獎勵 ----

    /** 帳號裡的角色（使用中的那個用現在的等級，其他的用存檔的等級）。 */
    List<Legacy.Member> members(final Player player, final PlayerState state) {
        final CharacterRecord active = state.character();
        return members(player, state, active == null ? 0 : active.level());
    }

    /** 同上，使用中的那個角色用 activeLevel 級算。 */
    private List<Legacy.Member> members(final Player player, final PlayerState state, final int activeLevel) {
        final CharacterRecord active = state.character();
        final List<Legacy.Member> list = new ArrayList<>();
        boolean found = false;
        for (final CharacterRecord record : plugin.characters().roster(player)) {
            if (active != null && record.id() == active.id()) {
                list.add(new Legacy.Member(active.characterClass().id(), active.name(), activeLevel));
                found = true;
            } else {
                list.add(new Legacy.Member(record.characterClass().id(), record.name(), record.level()));
            }
        }
        if (!found && active != null) {
            list.add(new Legacy.Member(active.characterClass().id(), active.name(), activeLevel));
        }
        return list;
    }

    /** 升級（from → to）讓帳號的分身獎勵多一階時，告訴玩家（屬性表升級時已經重算了）。 */
    public void onLevelUp(final Player player, final int from, final int to) {
        race.check(player); // 衝等活動：第一個到 100 級的昇華拿稱號（2026-10：還有百星之首、燃星者）
        race.levelUp(player, from, to); // 2026-10：升到 100、110、120 級全服廣播
        final PlayerState state = plugin.players().get(player);
        final CharacterRecord active = state.character();
        if (active == null || from >= to) {
            return;
        }
        final String classId = active.characterClass().id();
        final Legacy.Bonus before = bonusOf(Legacy.bonuses(config, members(player, state, from)), classId);
        final Legacy.Bonus after = bonusOf(Legacy.bonuses(config, members(player, state, to)), classId);
        if (after == null || after.tier() == null || before != null && before.tier() != null && before.tier().level() >= after.tier().level()) {
            return;
        }
        player.sendMessage(plugin.text().prefixed("<#f5b942>分身獎勵：<gray>帳號裡的" + classColor(classId) + className(classId)
                + "<gray>到了 Lv." + after.tier().level() + "，帳號裡的每個角色 <white>"
                + escape(String.join("、", StatLines.describeAll(after.tier().stats()))) + "<dark_gray>（/ascend）"));
        player.playSound(player.getLocation(), Sound.BLOCK_BEACON_ACTIVATE, 0.6f, 1.4f);
    }

    private static Legacy.Bonus bonusOf(final List<Legacy.Bonus> bonuses, final String classId) {
        for (final Legacy.Bonus bonus : bonuses) {
            if (bonus.classId().equals(classId)) {
                return bonus;
            }
        }
        return null;
    }

    public List<Legacy.Bonus> legacy(final Player player) {
        return legacy(player, plugin.players().get(player));
    }

    List<Legacy.Bonus> legacy(final Player player, final PlayerState state) {
        return Legacy.bonuses(config, members(player, state));
    }

    // ---- 試煉 ----

    /** 區域 Boss 的名字（解鎖條件的說明）。 */
    String bossName(final String zoneId) {
        if (plugin.zones() != null) {
            final Optional<Zone> zone = plugin.zones().zone(zoneId);
            if (zone.isPresent() && zone.get().boss() != null) {
                return zone.get().boss().name();
            }
        }
        return zoneId + " 的 Boss";
    }

    String lockReason(final Player player, final AscendancyConfig.Trial trial) {
        return ascension(player).lockReason(trial, this::bossName);
    }

    /** 還要等幾秒才能再開試煉（0 = 可以開）。 */
    long cooldownSeconds(final Player player) {
        final Long readyAt = trialReadyAt.get(player.getUniqueId());
        final long now = System.currentTimeMillis();
        return readyAt == null || now >= readyAt ? 0 : (readyAt - now) / 1000 + 1;
    }

    /** 開一個試煉（地圖裝置後面的傳送門）；成功回傳 null。 */
    String openTrial(final Player player, final AscendancyConfig.Trial trial) {
        if (!ready(player)) {
            return "先選一個角色（/char）";
        }
        final MapService maps = plugin.maps();
        if (maps == null || maps.world() == null) {
            return "地圖模組沒有啟用（或地圖世界沒有載入），開不了試煉";
        }
        final String reason = lockReason(player, trial);
        if (reason != null) {
            return reason;
        }
        if (ascension(player).completed(trial)) {
            // 不用地圖物品就能開：完成過的人不能再開（不能自己一直刷），跟著還沒完成的隊友進去幫忙
            return "你已經完成過" + trial.name() + "了：隊友開的試煉可以跟著進去幫忙，自己不能再開";
        }
        final int cap = config.settings().weeklyOpens();
        final String week = week();
        final int opened = Ascension.opensThisWeek(plugin.players().get(player).progress(), week);
        if (cap > 0 && opened >= cap) {
            return "這週已經開了 " + opened + " 次試煉（每週最多 " + cap + " 次），" + untilNextWeek() + "後重置";
        }
        final long wait = cooldownSeconds(player);
        if (wait > 0) {
            return "剛開過試煉，" + wait + " 秒後才能再開";
        }
        final MapStats stats = new MapStats(0, trial.level(), trial.quantity(), trial.rarity(), 0, 0, 0, List.of(), List.of(), List.of());
        final String trialId = trial.id();
        final String problem = maps.openSpecial(player, trial.map(), stats, trial.rooms(),
                new MapService.Special("trial", TRIAL_COLOR, (instance, players) -> onTrialComplete(trialId, instance, players)));
        if (problem == null) {
            trialReadyAt.put(player.getUniqueId(), System.currentTimeMillis() + config.settings().cooldownSeconds() * 1000L);
            // 每週的次數存在角色進度裡（重新登入不會歸零）：打倒前可以重開，但不能拿不用地圖物品的試煉一直刷
            plugin.characters().setProgress(player, Ascension.WEEK, Ascension.opensValue(week, opened + 1));
            plugin.getSLF4JLogger().info("{} 開了試煉：{}（Lv.{}，這週第 {} 次）", player.getName(), trial.name(), trial.level(), opened + 1);
        }
        return problem;
    }

    /** 這一週（伺服器時區，星期一換週；跟每週回響同一個）。 */
    String week() {
        return tw.exilecore.bounty.BountyClock.week(java.time.LocalDate.now(zone()));
    }

    /** 這週開了幾次試煉。 */
    int opensThisWeek(final Player player) {
        return Ascension.opensThisWeek(plugin.players().get(player).progress(), week());
    }

    /** 離下週一 0 點還有多久（「1 天 3 小時」）。 */
    String untilNextWeek() {
        return tw.exilecore.bounty.BountyClock.describe(tw.exilecore.bounty.BountyClock.untilNextWeek(java.time.ZonedDateTime.now(zone())));
    }

    private java.time.ZoneId zone() {
        return plugin.economy() == null ? java.time.ZoneId.systemDefault() : plugin.economy().ledger().zone();
    }

    /** 試煉的 Boss 倒下：在裡面的人，解鎖了、還沒完成的，算第一次完成。 */
    private void onTrialComplete(final String trialId, final MapInstance instance, final List<Player> players) {
        final AscendancyConfig.Trial trial = config.trial(trialId);
        for (final Player player : players) {
            if (!ready(player)) {
                continue;
            }
            if (trial == null) {
                player.sendMessage(plugin.text().prefixed("<gray>這個試煉已經從設定檔拿掉了，這次不算"));
                continue;
            }
            final String name = escape(trial.name());
            final Ascension before = ascension(player);
            if (before.completed(trial)) {
                player.sendMessage(plugin.text().prefixed("<gray>" + name + " 你已經完成過了（昇華點只給第一次，掉落照拿）"));
                continue;
            }
            final String reason = before.lockReason(trial, this::bossName);
            if (reason != null) {
                player.sendMessage(plugin.text().prefixed("<gray>你還沒解鎖" + name + "（" + escape(reason) + "），這次不算"));
                continue;
            }
            plugin.characters().setProgress(player, Ascension.trialKey(trial.id()), String.valueOf(System.currentTimeMillis() / 1000));
            final Ascension after = ascension(player);
            player.showTitle(Title.title(plugin.text().parse("<#f5b942>試煉完成"),
                    plugin.text().parse(COLOR + name + "　<white>昇華點 +" + trial.points()),
                    Title.Times.times(Duration.ofMillis(300), Duration.ofMillis(3200), Duration.ofMillis(800))));
            player.playSound(player.getLocation(), Sound.UI_TOAST_CHALLENGE_COMPLETE, 0.8f, 1.0f);
            player.sendMessage(plugin.text().prefixed("<#f5b942>試煉完成：" + COLOR + name + "<gray>，昇華點 +" + trial.points()
                    + "（共 <white>" + after.points() + "</white> 點，可配 <white>" + after.available() + "</white> 點）"));
            if (after.canChoose()) {
                player.sendMessage(plugin.text().parse("<gray>  你可以昇華了：" + link("/ascend choose", "[選擇昇華職業]", "選了不能換")
                        + " <dark_gray>（或回營地找" + escape(keeper()) + "）"));
            } else if (after.chosen() != null && after.canAllocateAny()) {
                player.sendMessage(plugin.text().parse("<gray>  " + link("/ascend tree", "[配昇華天賦]", "打開" + after.chosen().name() + "的昇華天賦")));
            }
            plugin.getSLF4JLogger().info("{} 完成試煉 {}（昇華點 {}）", player.getName(), trial.name(), after.points());
            if (plugin.quests() != null) {
                plugin.quests().refresh(player); // 任務：完成試煉
            }
            updateTracker(player);
        }
    }

    /** 聊天欄裡點了會執行指令的字。 */
    private static String link(final String command, final String label, final String hover) {
        return "<click:run_command:'" + command + "'><hover:show_text:'<gray>" + escape(hover).replace("'", "’") + "'>" + COLOR + "<u>"
                + label + "</u></hover></click>";
    }

    /** 營地裡打開試煉的劇情人物（quests.yml 裡 opens: trial 的那一個）。 */
    String keeper() {
        if (plugin.quests() != null) {
            for (final tw.exilecore.quest.QuestConfig.Npc npc : plugin.quests().config().npcs().values()) {
                if ("trial".equals(npc.opens())) {
                    return npc.name();
                }
            }
        }
        return "營地的星衛";
    }

    // ---- 昇華職業 ----

    /** 選昇華職業（選了不能換）；成功回傳 null。 */
    String choose(final Player player, final String ascendancyId) {
        if (!ready(player)) {
            return "先選一個角色（/char）";
        }
        if (splitPending(player)) {
            return "這個角色要先選職業：元素法師還是混沌術士（/char witch）";
        }
        final AscendancyConfig.Ascendancy ascendancy = config.ascendancy(ascendancyId);
        final String problem = ascension(player).chooseProblem(ascendancy);
        if (problem != null) {
            return problem;
        }
        if (plugin.characters().hasUnsavedTrades(player)) {
            return "存檔中，請稍等一下再選"; // 卡住重試的退點會把配點寫回去
        }
        // 舊的配點不帶過來（管理員重設過、或之前的昇華職業從設定檔拿掉了：代號一樣的天賦不能白拿）
        plugin.characters().removeProgress(player, Ascension.NODES);
        plugin.characters().setProgress(player, Ascension.CHOSEN, ascendancy.id());
        refreshStats(player);
        player.showTitle(Title.title(plugin.text().parse(ascendancy.colored()), plugin.text().parse("<gray>" + escape(ascendancy.summary())),
                Title.Times.times(Duration.ofMillis(400), Duration.ofMillis(3600), Duration.ofMillis(900))));
        player.playSound(player.getLocation(), Sound.ENTITY_PLAYER_LEVELUP, 0.8f, 0.7f);
        player.playSound(player.getLocation(), Sound.BLOCK_BEACON_POWER_SELECT, 0.7f, 1.0f);
        final String characterName = escape(plugin.players().get(player).character().name());
        for (final Player online : plugin.getServer().getOnlinePlayers()) {
            online.sendMessage(plugin.text().prefixed("<gray>流放者 <white>" + characterName + "</white>（" + escape(player.getName())
                    + "）走過了熔爐的門，昇華為 " + ascendancy.colored()));
        }
        plugin.getSLF4JLogger().info("{} 的角色 {} 昇華為 {}", player.getName(), characterName, ascendancy.name());
        if (plugin.discord() != null) {
            plugin.discord().achievement("流放者 " + characterName + "（" + escape(player.getName()) + "）走過了熔爐的門，昇華為 " + ascendancy.colored());
        }
        updateTracker(player);
        race.check(player); // 衝等活動：先到 100 級才昇華的也算
        return null;
    }

    // ---- 昇華天賦 ----

    /** 配一個昇華天賦；成功回傳 null。 */
    String allocate(final Player player, final String nodeId) {
        if (!ready(player)) {
            return "先選一個角色（/char）";
        }
        final Ascension ascension = ascension(player);
        final AscendancyConfig.Ascendancy chosen = ascension.chosen();
        if (chosen == null) {
            return "還沒有選昇華職業";
        }
        final AscendancyConfig.Node node = chosen.node(nodeId);
        if (node == null) {
            return "沒有這個昇華天賦（設定檔可能改過了）";
        }
        final String problem = ascension.allocateProblem(node);
        if (problem != null) {
            return problem;
        }
        if (plugin.characters().hasUnsavedTrades(player)) {
            // 退點（花金幣）的那一筆還在重試：現在寫配點，重試成功時會被舊的那一筆蓋掉
            return "存檔中，請稍等一下再配點";
        }
        plugin.characters().setProgress(player, Ascension.NODES, ascension.withNode(node));
        refreshStats(player);
        updateTracker(player);
        return null;
    }

    /** 退一個昇華天賦要花的金幣：天賦樹退一點的價格 × 這個天賦的點數。 */
    long refundCost(final Player player, final AscendancyConfig.Node node) {
        if (plugin.sponsors() != null && plugin.sponsors().cardActive(player)) {
            return 0; // 2026-10 月卡福利：昇華天賦退點免費
        }
        return plugin.players().config().respecCost(plugin.players().get(player).level()) * node.cost();
    }

    /** 退一個昇華天賦（花金幣）；成功回傳 null。 */
    String refund(final Player player, final String nodeId) {
        if (!ready(player)) {
            return "先選一個角色（/char）";
        }
        final Ascension ascension = ascension(player);
        final AscendancyConfig.Ascendancy chosen = ascension.chosen();
        final AscendancyConfig.Node node = chosen == null ? null : chosen.node(nodeId);
        if (node == null) {
            return "沒有這個昇華天賦（設定檔可能改過了）";
        }
        final String problem = ascension.refundProblem(node);
        if (problem != null) {
            return problem;
        }
        final String value = ascension.withoutNode(node);
        final long cost = refundCost(player, node);
        if (cost <= 0) {
            if (value == null) {
                plugin.characters().removeProgress(player, Ascension.NODES);
            } else {
                plugin.characters().setProgress(player, Ascension.NODES, value);
            }
        } else {
            final String transaction = plugin.characters().transactionProblem(player);
            if (transaction != null) {
                return transaction;
            }
            final PlayerState state = plugin.players().get(player);
            if (state.gold() < cost) {
                return String.format(Locale.ROOT, "金幣不夠：退這個要 %,d，你有 %,d", cost, state.gold());
            }
            // 金幣與配點寫在同一筆資料庫交易裡（當機時不會只寫進去一半）
            state.setGold(state.gold() - cost);
            plugin.economy().ledger().gold(EconomyLedger.RESPEC, -cost);
            if (value == null) {
                state.progress().remove(Ascension.NODES);
            } else {
                state.progress().put(Ascension.NODES, value);
            }
            final long characterId = state.character().id();
            final tw.exilecore.character.CharacterRepository repository = plugin.characters().repository();
            plugin.characters().commit(List.of(player), connection -> {
                if (value == null) {
                    repository.deleteProgress(connection, characterId, Ascension.NODES);
                } else {
                    repository.setProgress(connection, characterId, Ascension.NODES, value);
                }
            }, null, "昇華退點（" + player.getName() + "）");
        }
        refreshStats(player);
        updateTracker(player);
        return null;
    }

    /** 屬性表重算、畫面上的生命與 TAB 列表跟著變。 */
    private void refreshStats(final Player player) {
        final PlayerState state = plugin.players().get(player);
        state.markDirty();
        plugin.players().sheet(player);
        plugin.players().display(player, state);
        plugin.players().updateListName(player);
    }

    // ---- 畫面 ----

    /** 昇華與試煉的選單。 */
    public void open(final Player player) {
        if (!ready(player)) {
            player.sendMessage(plugin.text().prefixed("<red>先選一個角色（/char）"));
            return;
        }
        new AscendancyMenu(plugin, player, this).open();
    }

    /** 昇華天賦：選了昇華職業就打開小樹；可以選了就打開選擇畫面；不然打開試煉的選單。 */
    public void openTree(final Player player) {
        if (!ready(player)) {
            player.sendMessage(plugin.text().prefixed("<red>先選一個角色（/char）"));
            return;
        }
        if (splitPending(player)) {
            plugin.characters().offerWitchSplit(player); // 先選元素法師還是混沌術士：昇華職業跟著職業走
            return;
        }
        final Ascension ascension = ascension(player);
        if (ascension.chosen() != null) {
            new AscendancyTreeMenu(plugin, player, this).open();
        } else if (ascension.canChoose()) {
            new AscendancyChoiceMenu(plugin, player, this).open();
        } else if (ascension.options().isEmpty()) {
            player.sendMessage(plugin.text().prefixed("<gray>你的職業還沒有昇華職業"));
            new AscendancyMenu(plugin, player, this).open();
        } else {
            // 還不能選：先看看兩條路（預覽）
            player.sendMessage(plugin.text().prefixed("<gray>完成第一試煉之後才能選擇昇華職業；先看看有哪幾條路"));
            new AscendancyPreviewMenu(plugin, player, this, null, false).open();
        }
    }

    /**
     * 昇華預覽：還沒選、還沒解鎖也能看每個昇華職業的完整天賦樹。
     *
     * @param startId    先看哪一個（null = 自己的昇華職業，沒有就自己職業的第一個）
     * @param allClasses 一開始就看全部職業的昇華
     */
    public void openPreview(final Player player, final String startId, final boolean allClasses) {
        if (!ready(player)) {
            player.sendMessage(plugin.text().prefixed("<red>先選一個角色（/char）"));
            return;
        }
        new AscendancyPreviewMenu(plugin, player, this, startId, allClasses).open();
    }

    /**
     * 建立角色時的昇華預覽（2026-10）：還沒有角色也能看某個職業的昇華職業與完整的昇華天賦。
     *
     * @param back 「返回」回到哪裡（選職業的畫面）
     */
    public void openClassPreview(final Player player, final String classId, final Runnable back) {
        new AscendancyPreviewMenu(plugin, player, this, classId, back).open();
    }

    /** 這個職業能走的昇華職業名稱（帶顏色的 MiniMessage；沒有是空的）。 */
    public List<String> ascendancyNames(final String classId) {
        final List<String> names = new ArrayList<>();
        for (final AscendancyConfig.Ascendancy ascendancy : config.forClass(classId)) {
            names.add(ascendancy.colored());
        }
        return names;
    }

    /** 任務追蹤上的提醒：可以選昇華職業了、有昇華點還沒配。 */
    public List<String> trackerLines(final Player player) {
        if (!ready(player)) {
            return List.of();
        }
        if (splitPending(player)) {
            return List.of(COLOR + "◆ 先選職業：元素法師或混沌術士 <dark_gray>/char witch");
        }
        final Ascension ascension = ascension(player);
        if (ascension.canChoose()) {
            return List.of(COLOR + "◆ 可以昇華了 <dark_gray>/ascend");
        }
        if (ascension.chosen() != null && ascension.canAllocateAny()) {
            return List.of(COLOR + "◆ 昇華點 " + ascension.available() + " 點可配 <dark_gray>/ascend");
        }
        return List.of();
    }

    private void updateTracker(final Player player) {
        if (plugin.quests() != null) {
            plugin.quests().updateTracker(player);
        }
    }

    /** 設定檔重載：屬性（昇華天賦、分身獎勵）、TAB 列表、任務追蹤跟著變。 */
    public void reloaded() {
        for (final Player player : plugin.getServer().getOnlinePlayers()) {
            if (!ready(player)) {
                continue;
            }
            refreshStats(player);
            updateTracker(player);
        }
    }

    void forget(final Player player) {
        trialReadyAt.remove(player.getUniqueId());
    }

    // ---- 管理員 ----

    /** 直接完成一個試煉（不管解鎖條件）。 */
    void adminTrial(final Player player, final AscendancyConfig.Trial trial) {
        plugin.characters().setProgress(player, Ascension.trialKey(trial.id()), String.valueOf(System.currentTimeMillis() / 1000));
        if (plugin.quests() != null) {
            plugin.quests().refresh(player);
        }
        refreshStats(player);
        updateTracker(player);
    }

    /** 把一個試煉變回沒完成（昇華點跟著少，配不下的天賦照順序拿掉）。 */
    void adminUntrial(final Player player, final AscendancyConfig.Trial trial) {
        plugin.characters().removeProgress(player, Ascension.trialKey(trial.id()));
        refreshStats(player);
        updateTracker(player);
    }

    /** 清掉昇華職業與配點（試煉不動）：可以重選。 */
    void adminReset(final Player player) {
        plugin.characters().removeProgress(player, Ascension.CHOSEN);
        plugin.characters().removeProgress(player, Ascension.NODES);
        refreshStats(player);
        updateTracker(player);
    }

    /** 連試煉一起清掉。 */
    void adminWipe(final Player player) {
        adminReset(player);
        for (final AscendancyConfig.Trial trial : config.trials()) {
            plugin.characters().removeProgress(player, Ascension.trialKey(trial.id()));
        }
        plugin.characters().removeProgress(player, Ascension.WEEK);
        trialReadyAt.remove(player.getUniqueId());
        refreshStats(player);
        updateTracker(player);
    }

    List<String> adminInfo(final Player player) {
        final Ascension ascension = ascension(player);
        final List<String> lines = new ArrayList<>();
        final AscendancyConfig.Ascendancy chosen = ascension.chosen();
        lines.add("<gray>昇華職業：" + (chosen == null ? "<dark_gray>沒有" + (ascension.canChoose() ? "（可以選了）" : "") : chosen.colored()
                + " <dark_gray>" + chosen.id()));
        lines.add("<gray>昇華點：共 <white>" + ascension.points() + "</white>，已配 <white>" + ascension.spent() + "</white>，可配 <white>"
                + ascension.available() + "</white>　這週開了 <white>" + opensThisWeek(player) + "</white> 次試煉"
                + (config.settings().weeklyOpens() > 0 ? "（上限 " + config.settings().weeklyOpens() + "）" : ""));
        final List<String> nodes = new ArrayList<>();
        for (final AscendancyConfig.Node node : ascension.allocated()) {
            nodes.add(node.name() + "（" + node.id() + "）");
        }
        lines.add("<gray>昇華天賦：<white>" + (nodes.isEmpty() ? "—" : escape(String.join("、", nodes))));
        for (final AscendancyConfig.Trial trial : config.trials()) {
            final String reason = ascension.lockReason(trial, this::bossName);
            lines.add(" " + (ascension.completed(trial) ? "<green>✔ " : reason == null ? "<yellow>○ " : "<dark_gray>✘ ") + trial.id()
                    + " <gray>" + escape(trial.name()) + (reason != null && !ascension.completed(trial) ? " <dark_gray>" + escape(reason) : ""));
        }
        for (final Legacy.Bonus bonus : legacy(player)) {
            lines.add(" <gray>分身獎勵 " + className(bonus.classId()) + "：" + (bonus.tier() == null ? "<dark_gray>沒有"
                    : "<white>Lv." + bonus.tier().level() + "（" + escape(bonus.best().name()) + "）"));
        }
        return lines;
    }

    static String escape(final String text) {
        return ItemRenderer.escape(text);
    }
}
