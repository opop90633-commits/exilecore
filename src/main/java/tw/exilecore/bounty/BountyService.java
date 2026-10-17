package tw.exilecore.bounty;

import net.kyori.adventure.title.Title;
import org.bukkit.Sound;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;
import tw.exilecore.ExileCore;
import tw.exilecore.character.PlayerState;
import tw.exilecore.currency.CurrencyType;
import tw.exilecore.economy.EconomyLedger;
import tw.exilecore.economy.TradeLogRepository;
import tw.exilecore.economy.Wallet;
import tw.exilecore.flask.FlaskBase;
import tw.exilecore.flask.FlaskKind;
import tw.exilecore.item.ItemRenderer;
import tw.exilecore.map.MapConfig;
import tw.exilecore.map.MapInstance;
import tw.exilecore.map.MapService;
import tw.exilecore.map.MapStats;
import tw.exilecore.monster.MonsterConfig;
import tw.exilecore.monster.MonsterRarity;
import tw.exilecore.stash.Stash;
import tw.exilecore.zone.Zone;
import tw.exilecore.zone.ZoneConfig;

import java.time.Duration;
import java.time.LocalDate;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/**
 * 每日委託、每週回響、功勳兌換的執行期（主執行緒）。
 * <p>
 * 帳號的資料（功勳、帳號進度）登入時讀進來放在記憶體。委託的計數晚一點寫（每 10 次、每 30 秒、存檔前、下線）；
 * 完成委託、完成回響、兌換東西時，帳號進度與功勳跟角色存檔在同一個資料庫交易裡寫（經驗、東西、功勳同時成功或同時失敗）。
 */
public final class BountyService {

    /** 計數每幾次寫一次資料庫。 */
    private static final int SAVE_EVERY = 10;
    /** 回響的傳送門粒子顏色。 */
    private static final int ECHO_COLOR = 0xC77DFF;
    static final String COLOR = "<#9fd3ff>";
    static final String ECHO = "<#c77dff>";
    static final String MERIT = "<#f5b942>";

    /** 一個帳號在記憶體裡的資料。 */
    static final class Account {
        final UUID uuid;
        long merits;
        final Map<String, String> progress;
        /** 還沒寫進資料庫的帳號進度（委託的計數）。 */
        final Map<String, String> unsaved = new LinkedHashMap<>();
        int unsavedCounts;
        /** 什麼時候可以再開回響（毫秒）。 */
        long echoReadyAt;
        /** 這次登入提示過今天的委託了。 */
        boolean greeted;

        Account(final UUID uuid, final AccountRepository.Data data) {
            this.uuid = uuid;
            this.merits = data.merits();
            this.progress = new HashMap<>(data.progress());
        }
    }

    private final ExileCore plugin;
    private final BountyConfig config;
    private final AccountRepository repository = new AccountRepository();
    private final Map<UUID, Account> accounts = new HashMap<>();
    private final MapService.Special echo = new MapService.Special("echo", ECHO_COLOR, this::onEchoComplete);

    BountyService(final ExileCore plugin, final BountyConfig config) {
        this.plugin = plugin;
        this.config = config;
        registerBossTitles();
        registerAdminTitle();
    }

    // ---- 管理員稱號（2026-10）----

    /** 管理員稱號的代號：有管理員權限（OP）的人自動擁有，沒戴別的稱號時自動顯示。 */
    public static final String ADMIN_TITLE = "admin";

    /** 新手指導員（2026-10-17）：管理員用指令發給帶新人的玩家（/ec bounty title mentor 玩家）。沒有加成，只是名牌。 */
    public static final String MENTOR_TITLE = "mentor";

    private void registerAdminTitle() {
        registerTitles(Map.of(ADMIN_TITLE, new BountyConfig.Title(ADMIN_TITLE, "管理員", "gradient:#ff5555:#ffaa00", 0),
                MENTOR_TITLE, new BountyConfig.Title(MENTOR_TITLE, "新手指導員", "gradient:#7fe0a8:#5fb4ff", 0)));
        setTitleSource("mentor", new TitleSource() {
            @Override
            public Set<String> owned(final Map<String, String> progress) {
                return Set.of(); // 發了就記在帳號的稱號清單裡（grantTitle）
            }

            @Override
            public List<String> howTo(final Player player, final String id) {
                return MENTOR_TITLE.equals(id) ? List.of("<gray>管理員發給幫忙帶新人的玩家") : null;
            }

            @Override
            public boolean listed(final Player player, final String id) {
                return !MENTOR_TITLE.equals(id); // 沒有的人不列
            }

            @Override
            public String sourceName(final String id) {
                return MENTOR_TITLE.equals(id) ? "管理員發放" : null;
            }
        });
        setTitleSource("admin", new TitleSource() {
            @Override
            public Set<String> owned(final Map<String, String> progress) {
                return Set.of(); // 照權限算（{@link #owned(Account)}），帳號進度裡沒有
            }

            @Override
            public List<String> howTo(final Player player, final String id) {
                return ADMIN_TITLE.equals(id) ? List.of("<gray>管理員（OP）專用") : null;
            }

            @Override
            public boolean listed(final Player player, final String id) {
                return isAdmin(player.getUniqueId());
            }

            @Override
            public String sourceName(final String id) {
                return ADMIN_TITLE.equals(id) ? "管理員權限" : null;
            }
        });
    }

    /** 在線、有管理員權限（OP）。 */
    private boolean isAdmin(final UUID uuid) {
        final Player player = plugin.getServer().getPlayer(uuid);
        return player != null && player.hasPermission(tw.exilecore.ExileCore.PERMISSION_ADMIN);
    }

    /** 帳號擁有的稱號：帳號進度算的，加上管理員稱號（有權限的話）。 */
    private Set<String> owned(final Account account) {
        final Set<String> owned = owned(account.progress);
        if (isAdmin(account.uuid)) {
            owned.add(ADMIN_TITLE);
        }
        return owned;
    }

    // ---- 擊殺稱號（2026-10）----

    /** 帳號進度裡記某隻 Boss 打倒幾次的鍵。 */
    static final String BOSS_KILLS = "bosskills.";

    /** 擊殺稱號登記成限定稱號（啟用與重讀時）。 */
    void registerBossTitles() {
        final Map<String, BountyConfig.Title> titles = new LinkedHashMap<>();
        for (final BountyConfig.BossTitle boss : config.bossTitles().values()) {
            titles.put(boss.title().id(), boss.title());
        }
        if (!titles.isEmpty()) {
            registerTitles(titles);
        }
    }

    /** 這個稱號是哪隻 Boss 的擊殺稱號；不是 null。 */
    BountyConfig.BossTitle bossTitleFor(final String titleId) {
        for (final BountyConfig.BossTitle boss : config.bossTitles().values()) {
            if (boss.title().id().equals(titleId)) {
                return boss;
            }
        }
        return null;
    }

    /** 這個帳號打倒這隻 Boss 幾次。 */
    public long bossKills(final Player player, final String boss) {
        final Account account = accounts.get(player.getUniqueId());
        if (account == null) {
            return 0;
        }
        try {
            return Long.parseLong(account.progress.getOrDefault(BOSS_KILLS + boss, "0"));
        } catch (final NumberFormatException e) {
            return 0;
        }
    }

    /** 極境 Boss、金庫之主倒下時，場上的每個人記一次；到了次數發擊殺稱號。沒有設定擊殺稱號的 Boss 不記。 */
    public void recordBossKill(final Player player, final String boss) {
        final BountyConfig.BossTitle def = config.bossTitles().get(boss);
        final Account account = accounts.get(player.getUniqueId());
        if (def == null || account == null) {
            return;
        }
        final long kills = bossKills(player, boss) + 1;
        final String value = Long.toString(kills);
        account.progress.put(BOSS_KILLS + boss, value);
        commit(player, account, Map.of(BOSS_KILLS + boss, value), List.of(), "擊殺紀錄");
        final boolean owned = MeritShop.ownedTitles(account.progress).contains(def.title().id());
        if (!owned && kills >= def.kills()) {
            final String problem = grantTitle(player, def.title().id());
            if (problem == null) {
                final String tag = "<" + def.title().color() + ">【" + escape(def.title().name()) + "】</" + def.title().color() + ">";
                player.sendMessage(plugin.text().prefixed("<green>打倒 " + escape(def.bossName()) + " " + def.kills() + " 次！擊殺稱號 " + tag + " 已經戴上"));
                final String bonus = MeritShop.bonusText(def.title());
                if (bonus != null) {
                    player.sendMessage(plugin.text().prefixed("<#7fe07f>稱號的永久加成：<white>" + bonus + "<gray>（擁有就有，不用戴；帳號裡每個角色都有）"));
                }
                for (final Player other : plugin.getServer().getOnlinePlayers()) {
                    if (other != player) {
                        other.sendMessage(plugin.text().prefixed("<white>" + player.getName() + " <gray>打倒 " + escape(def.bossName()) + " " + def.kills()
                                + " 次，拿到擊殺稱號 " + tag));
                    }
                }
                player.playSound(player.getLocation(), org.bukkit.Sound.UI_TOAST_CHALLENGE_COMPLETE, 0.8f, 1.0f);
            } else if (!problem.startsWith("已經有")) {
                plugin.getSLF4JLogger().warn("發擊殺稱號給 {} 失敗：{}", player.getName(), problem);
            }
        } else if (!owned) {
            player.sendMessage(plugin.text().prefixed("<gray>" + escape(def.bossName()) + " 擊殺 <white>" + kills + "／" + def.kills()
                    + "<gray>（滿了拿擊殺稱號 <" + def.title().color() + ">【" + escape(def.title().name()) + "】</" + def.title().color() + "><gray>）"));
        }
    }

    public BountyConfig config() {
        return config;
    }

    // ---- 時間 ----

    private ZoneId zone() {
        return plugin.economy() == null ? ZoneId.systemDefault() : plugin.economy().ledger().zone();
    }

    ZonedDateTime now() {
        return ZonedDateTime.now(zone());
    }

    private String today() {
        return BountyClock.day(LocalDate.now(zone()));
    }

    private String week() {
        return BountyClock.week(LocalDate.now(zone()));
    }

    // ---- 載入 ----

    /** 登入：讀帳號的功勳與進度（背景執行緒）。 */
    void load(final Player player) {
        final UUID uuid = player.getUniqueId();
        accounts.remove(uuid);
        plugin.database().async(connection -> repository.load(connection, uuid)).whenComplete((data, error) ->
                plugin.getServer().getScheduler().runTask(plugin, () -> {
                    if (plugin.getServer().getPlayer(uuid) != player) {
                        return; // 已經登出（或重新登入，新的那次會自己載入）
                    }
                    if (error != null) {
                        plugin.getSLF4JLogger().error("載入 {} 的委託與功勳失敗", player.getName(), error);
                        player.sendMessage(plugin.text().prefixed("<red>委託與功勳的資料載入失敗，請通知管理員（主控台有錯誤訊息）"));
                        return;
                    }
                    accounts.put(uuid, new Account(uuid, data));
                    onReady(player);
                }));
    }

    /** 下線：計數寫進去、清掉記憶體。 */
    void unload(final Player player) {
        flush(player);
        accounts.remove(player.getUniqueId());
    }

    /** 帳號資料載入好了、有角色（委託要算在角色身上：等級、經驗）。 */
    private Account ready(final Player player) {
        final Account account = accounts.get(player.getUniqueId());
        if (account == null) {
            return null;
        }
        final PlayerState state = plugin.players().get(player);
        return state.isLoaded() && state.hasCharacter() ? account : null;
    }

    /** 角色載入（登入、換角色）：今天的委託、提示、任務追蹤、稱號。 */
    public void onCharacterApplied(final Player player) {
        onReady(player);
    }

    private void onReady(final Player player) {
        final Account account = ready(player);
        if (account != null) {
            final boolean generated = refreshDay(player, account);
            if (!account.greeted) {
                account.greeted = true;
                if (!generated) {
                    remind(player, account); // 剛產生的那一批已經列出來了
                }
                if (plugin.events() != null) {
                    plugin.events().greet(player); // 每月活動：這個月的主題與進度一行
                }
            }
        }
        applyTitle(player);
        updateTracker(player);
        if (account != null) {
            for (final java.util.function.Consumer<Player> listener : readyListeners) {
                listener.accept(player);
            }
        }
    }

    /** 今天的委託還沒產生（新的一天、第一次用到）就產生；回傳有沒有產生。 */
    private boolean refreshDay(final Player player, final Account account) {
        final BountyBoard board = board(account);
        if (!board.needsGeneration()) {
            return false;
        }
        board.generate(BountyBoard.seed(account.uuid, board.day()), mapsUnlocked(player));
        apply(player, account, board);
        return true;
    }

    private BountyBoard board(final Account account) {
        return new BountyBoard(config, account.progress, today(), week());
    }

    /** 能不能跑地圖：打倒過劇情最後一個區域的 Boss（拿到 T1 地圖），或完成過任何一張地圖。 */
    private boolean mapsUnlocked(final Player player) {
        if (plugin.maps() == null) {
            return false;
        }
        final Map<String, String> progress = plugin.players().get(player).progress();
        if (progress.containsKey("boss." + plugin.maps().config().drops().campaignBossZone())) {
            return true;
        }
        for (final String key : progress.keySet()) {
            if (key.startsWith("atlas.")) {
                return true;
            }
        }
        return false;
    }

    /** 登入時一句：今天的委託做到哪了。 */
    private void remind(final Player player, final Account account) {
        final BountyBoard board = board(account);
        final List<BountyBoard.Daily> dailies = board.today();
        if (dailies.isEmpty()) {
            return;
        }
        final int done = board.doneToday();
        if (done >= dailies.size()) {
            if (!board.weeklyDone() && config.weekly().enabled()) {
                player.sendMessage(plugin.text().prefixed("<gray>今天的委託都完成了；這週的" + ECHO + "回響</#c77dff>還沒打（/bounty）"));
            }
            return;
        }
        player.sendMessage(plugin.text().prefixed(COLOR + "每日委託</#9fd3ff> <white>" + done + "/" + dailies.size()
                + "<gray>　功勳 " + MERIT + format(account.merits) + "</#f5b942>（/bounty 委託板）"));
    }

    /** 每 20 秒：過了午夜就換新的一批（在線上的人）。 */
    void tick() {
        for (final Player player : plugin.getServer().getOnlinePlayers()) {
            final Account account = ready(player);
            if (account != null && board(account).needsGeneration()) {
                refreshDay(player, account);
                updateTracker(player);
            }
        }
    }

    // ---- 遊戲裡發生的事 ----

    /**
     * 殺了一隻怪（有份的每個人各算一次）。
     *
     * @param boss 是不是 Boss（區域 Boss、地圖 Boss、回響）
     */
    public void onKill(final Player player, final int monsterLevel, final MonsterRarity rarity, final boolean boss) {
        final Account account = ready(player);
        if (account == null) {
            return;
        }
        refreshDay(player, account);
        final BountyBoard board = board(account);
        board.kill(monsterLevel, comparedLevel(player), rarity, boss, plugin.items().random());
        if (!board.writes().isEmpty()) {
            apply(player, account, board);
        }
    }

    /** 完成了一張地圖（地圖 Boss 倒下時在地圖裡；回響不算）。 */
    public void onMapComplete(final Player player, final int mapLevel) {
        final Account account = ready(player);
        if (account == null) {
            return;
        }
        refreshDay(player, account);
        final BountyBoard board = board(account);
        board.mapCompleted(mapLevel, comparedLevel(player));
        if (!board.writes().isEmpty()) {
            apply(player, account, board);
        }
    }

    /**
     * 套用一次動作的結果：計數晚一點寫、產生與清掉的馬上寫、完成的給獎勵並跟角色存檔一起寫；提示、任務追蹤。
     */
    private void apply(final Player player, final Account account, final BountyBoard board) {
        final Set<String> counted = new HashSet<>();
        final Set<String> committed = new LinkedHashSet<>();
        for (final BountyBoard.Event event : board.events()) {
            switch (event) {
                case BountyBoard.Counted c -> counted.add(BountyBoard.key(c.daily().slot()));
                case BountyBoard.Completed c -> committed.add(BountyBoard.key(c.daily().slot()));
                case BountyBoard.WeeklyCompleted w -> committed.add(BountyBoard.WEEKLY);
                case BountyBoard.Generated g -> {
                }
            }
        }
        final Map<String, String> immediate = new LinkedHashMap<>();
        final Map<String, String> withRewards = new LinkedHashMap<>();
        for (final BountyBoard.Write write : board.writes()) {
            if (write.value() == null) {
                account.progress.remove(write.key());
            } else {
                account.progress.put(write.key(), write.value());
            }
            if (committed.contains(write.key())) {
                withRewards.put(write.key(), write.value());
                account.unsaved.remove(write.key());
            } else if (counted.contains(write.key())) {
                account.unsaved.put(write.key(), write.value());
                account.unsavedCounts++;
            } else {
                immediate.put(write.key(), write.value());
                account.unsaved.remove(write.key());
            }
        }
        // 獎勵：功勳（帳號）與經驗（這個角色）
        long merits = 0;
        final List<String> done = new ArrayList<>();
        for (final BountyBoard.Event event : board.events()) {
            switch (event) {
                case BountyBoard.Completed c -> {
                    final long xp = experience(player, c.template().experience());
                    merits += c.template().merits();
                    account.merits += c.template().merits();
                    plugin.players().awardExperience(player, xp);
                    announceDaily(player, board, c, xp);
                    done.add(c.template().describe());
                }
                case BountyBoard.WeeklyCompleted w -> {
                    final long xp = experience(player, config.weekly().experience());
                    merits += config.weekly().merits();
                    account.merits += config.weekly().merits();
                    plugin.players().awardExperience(player, xp);
                    announceWeekly(player, xp);
                    done.add("每週回響");
                }
                case BountyBoard.Generated g -> announceGenerated(player, g);
                case BountyBoard.Counted c -> notice(player, c);
            }
        }
        if (!withRewards.isEmpty()) {
            commit(player, account, withRewards, List.of(), "完成" + String.join("、", done) + "（功勳 +" + merits + "）");
        }
        if (!immediate.isEmpty()) {
            write(account.uuid, immediate);
        }
        if (account.unsavedCounts >= SAVE_EVERY) {
            flush(player);
        }
        updateTracker(player);
    }

    /**
     * 升到下一級所需經驗的幾分之幾；超過 experience_level_cap 級照那一級算（滿級 0），再乘全服經驗加倍（character.yml 的 experience_bonus）。
     * 選單也用這個寫實際給多少。
     */
    public long experience(final Player player, final double fraction) {
        final int level = plugin.players().get(player).level();
        final long base = config.rewardExperience(fraction, level, plugin.players().config()::experienceToNext);
        // 2026-10-16：個人經驗加倍藥也吃（跟全服加起來算：全服 ×2.1、個人 ×2 → ×3.1）
        final double personal = plugin.sponsors() == null ? 0 : plugin.sponsors().personalExperience(player);
        return Math.round(base * plugin.players().config().experienceBonus() * (1 + personal));
    }

    private void notice(final Player player, final BountyBoard.Counted counted) {
        final BountyConfig.Template template = counted.template();
        final PlayerState state = plugin.players().get(player);
        final long until = plugin.getServer().getCurrentTick() + 40;
        switch (template.goal()) {
            case COLLECT -> {
                state.notice(COLOR + "撿到 " + escape(template.item()) + " <white>" + counted.daily().count() + "/" + template.count(), until);
                player.playSound(player.getLocation(), Sound.ENTITY_ITEM_PICKUP, 0.5f, 1.5f);
            }
            case BOSS, MAPS -> state.notice(COLOR + "委託 " + escape(template.describe()) + " <white>" + counted.daily().count() + "/"
                    + template.count(), until);
            default -> {
                // 擊殺：任務追蹤上看得到，不洗動作列
            }
        }
    }

    private void announceGenerated(final Player player, final BountyBoard.Generated generated) {
        final BountyBoard board = board(accounts.get(player.getUniqueId()));
        final List<String> texts = new ArrayList<>();
        for (final BountyBoard.Daily daily : board.today()) {
            texts.add(escape(board.template(daily).describe()));
        }
        if (texts.isEmpty()) {
            return;
        }
        player.sendMessage(plugin.text().prefixed(COLOR + "今天的每日委託</#9fd3ff><gray>：<white>" + String.join("<gray>、<white>", texts)
                + "<gray>（/bounty；" + levelGapText() + "）"));
        player.playSound(player.getLocation(), Sound.BLOCK_NOTE_BLOCK_CHIME, 0.5f, 1.4f);
    }

    private void announceDaily(final Player player, final BountyBoard board, final BountyBoard.Completed completed, final long xp) {
        final BountyConfig.Template template = completed.template();
        final List<BountyBoard.Daily> today = board.today();
        int done = 0;
        for (final BountyBoard.Daily daily : today) {
            if (daily.done()) {
                done++;
            }
        }
        player.showTitle(Title.title(plugin.text().parse(COLOR + "委託完成"), plugin.text().parse("<white>" + escape(template.describe())
                        + "　" + MERIT + "功勳 +" + template.merits()),
                Title.Times.times(Duration.ofMillis(200), Duration.ofMillis(1800), Duration.ofMillis(500))));
        player.playSound(player.getLocation(), Sound.ENTITY_PLAYER_LEVELUP, 0.6f, 1.6f);
        player.sendMessage(plugin.text().prefixed(COLOR + "每日委託完成：<white>" + escape(template.describe()) + "<gray>　功勳 "
                + MERIT + "+" + template.merits() + "</#f5b942>" + (xp > 0 ? "<gray>、經驗 <white>+" + format(xp) : "")
                + "<gray>（今天 " + done + "/" + today.size() + "）"));
        if (done >= today.size()) {
            player.sendMessage(plugin.text().prefixed("<gray>今天的委託都完成了，明天 0 點換新的。功勳到營地找" + escape(keeper())
                    + "換東西（/bounty）"));
        }
    }

    /** 委託板在誰身上（quests.yml 裡 opens: bounty 的劇情人物）。 */
    private String keeper() {
        if (plugin.quests() != null) {
            for (final tw.exilecore.quest.QuestConfig.Npc npc : plugin.quests().config().npcs().values()) {
                if ("bounty".equals(npc.opens())) {
                    return (npc.title().isBlank() ? "" : npc.title()) + npc.name();
                }
            }
        }
        return "軍需官";
    }

    private void announceWeekly(final Player player, final long xp) {
        player.showTitle(Title.title(plugin.text().parse(ECHO + "回響平息"), plugin.text().parse("<white>每週回響完成　" + MERIT
                        + "功勳 +" + config.weekly().merits()),
                Title.Times.times(Duration.ofMillis(300), Duration.ofMillis(2600), Duration.ofMillis(700))));
        player.playSound(player.getLocation(), Sound.UI_TOAST_CHALLENGE_COMPLETE, 0.7f, 1.2f);
        player.sendMessage(plugin.text().prefixed(ECHO + "每週回響完成</#c77dff><gray>：功勳 " + MERIT + "+" + config.weekly().merits()
                + "</#f5b942>" + (xp > 0 ? "<gray>、經驗 <white>+" + format(xp) : "") + "<gray>（下週一 0 點可以再打一次）"));
    }

    // ---- 寫入 ----

    /** 帳號進度馬上寫（背景執行緒；還原備份中不寫）。 */
    private void write(final UUID uuid, final Map<String, String> values) {
        final Map<String, String> copy = new LinkedHashMap<>(values);
        plugin.database().async(connection -> {
            if (!plugin.characters().writesPaused()) {
                repository.setProgress(connection, uuid, copy);
            }
            return null;
        }).whenComplete((ignored, error) -> {
            if (error != null) {
                plugin.getSLF4JLogger().error("寫入帳號進度 {} 失敗", copy.keySet(), error);
            }
        });
    }

    /** 還沒寫的計數寫進去（每 30 秒、存檔前、下線）。 */
    public void flush(final Player player) {
        final Account account = accounts.get(player.getUniqueId());
        if (account == null || account.unsaved.isEmpty()) {
            return;
        }
        write(account.uuid, account.unsaved);
        account.unsaved.clear();
        account.unsavedCounts = 0;
    }

    void flushAll() {
        for (final Player player : plugin.getServer().getOnlinePlayers()) {
            flush(player);
        }
    }

    /**
     * 跟角色存檔在同一個交易裡寫：帳號進度（完成的委託、回響、兌換紀錄）、功勳、交易紀錄。
     * 角色身上的變動（經驗、拿到的東西、倉庫）在角色存檔的快照裡。
     * <p>
     * 只放「之後不會再變」的值（完成的那一格、這週完成、買過幾頁…）：寫入失敗時這筆會排隊重試，
     * 重試時寫的是當時的值；還在變的計數另外寫（先寫掉），重試就不會用舊的計數蓋掉新的。
     */
    private void commit(final Player player, final Account account, final Map<String, String> values,
                        final List<TradeLogRepository.Entry> entries, final String what) {
        flush(player);
        final UUID uuid = account.uuid;
        final long merits = account.merits;
        final Map<String, String> all = new LinkedHashMap<>(values);
        final String opId = entries.isEmpty() ? null : entries.getFirst().tradeId();
        plugin.characters().commit(List.of(player), connection -> {
            repository.setProgress(connection, uuid, all);
            repository.setMerits(connection, uuid, merits);
            if (!entries.isEmpty()) {
                plugin.economy().tradeLog().insert(connection, entries);
            }
        }, opId, what + "（" + player.getName() + "）");
    }

    // ---- 每週回響 ----

    /** 這個角色打倒過、可以挑戰回響的區域（照區域的順序）。 */
    List<Zone> echoZones(final Player player) {
        final List<Zone> list = new ArrayList<>();
        if (plugin.zones() == null) {
            return list;
        }
        final Map<String, String> progress = plugin.players().get(player).progress();
        for (final Zone zone : plugin.zones().zones()) {
            if (zone.boss() != null && progress.containsKey("boss." + zone.id())) {
                list.add(zone);
            }
        }
        return list;
    }

    /** 回響的怪物等級：那個區域的最高等級與角色等級取高的。 */
    int echoLevel(final Player player, final Zone zone) {
        return Echoes.level(zone.maxLevel(), plugin.players().get(player).level(), MapStats.tiers().topLevel());
    }

    /** 跟怪物比等級時的角色等級（最高到地圖最高階的怪物等級）。 */
    int comparedLevel(final Player player) {
        return Echoes.comparedLevel(plugin.players().get(player).level(), MapStats.tiers().topLevel());
    }

    /** 這隻怪的等級算不算數（不能比角色低超過 level_gap 級；每月活動也用這條）。 */
    public boolean countsAtLevel(final Player player, final int monsterLevel) {
        return monsterLevel >= minMonsterLevel(player);
    }

    /** 這個角色現在要打幾級以上的怪（地圖）才算數：每日委託、每月挑戰都用這條；選單與提示寫給玩家看。 */
    public int minMonsterLevel(final Player player) {
        return Echoes.minMonsterLevel(plugin.players().get(player).level(), MapStats.tiers().topLevel(), config.levelGap());
    }

    /** 「怪物等級不能比你低超過幾級」的級距。 */
    public int levelGap() {
        return config.levelGap();
    }

    /** 「怪物等級不能比你低超過幾級」的說明。 */
    public String levelGapText() {
        return "怪物等級不能比你低超過 " + config.levelGap() + " 級（" + MapStats.tiers().topLevel() + " 級以上的角色當作 "
                + MapStats.tiers().topLevel() + " 級）";
    }

    boolean weeklyDone(final Player player) {
        final Account account = accounts.get(player.getUniqueId());
        return account != null && board(account).weeklyDone();
    }

    /** 開一張回響（地圖裝置後面的傳送門）；成功回傳 null。 */
    String openEcho(final Player player, final Zone zone) {
        final BountyConfig.Weekly weekly = config.weekly();
        if (!weekly.enabled()) {
            return "每週回響沒有開放（bounties.yml 的 weekly.enabled）";
        }
        final MapService maps = plugin.maps();
        if (maps == null || maps.world() == null) {
            return "地圖模組沒有啟用（或地圖世界沒有載入），開不了回響";
        }
        final Account account = ready(player);
        if (account == null) {
            return accounts.containsKey(player.getUniqueId()) ? "先選一個角色（/char）" : "帳號資料載入中，請稍等";
        }
        final BountyBoard board = board(account);
        if (board.weeklyDone()) {
            return "這週的回響已經完成了，下週一 0 點再來";
        }
        if (board.echoAttempts() >= weekly.attempts()) {
            return "這週已經開了 " + board.echoAttempts() + " 次回響（每週最多 " + weekly.attempts() + " 次），下週一 0 點再來";
        }
        if (zone.boss() == null || !echoZones(player).contains(zone)) {
            return "要先用這個角色打倒過" + (zone.boss() == null ? "那個區域的 Boss" : zone.boss().name());
        }
        final long now = System.currentTimeMillis();
        if (now < account.echoReadyAt) {
            return "剛開過回響，" + ((account.echoReadyAt - now) / 1000 + 1) + " 秒後才能再開";
        }
        final int level = echoLevel(player, zone);
        final MapStats stats = new MapStats(0, level, weekly.quantity(), weekly.rarity(), 0, 0, 0, List.of(), List.of(), List.of());
        final String problem = maps.openSpecial(player, echoMap(zone, level), stats, weekly.rooms(), echo);
        if (problem == null) {
            account.echoReadyAt = now + weekly.cooldownSeconds() * 1000L;
            board.useEchoAttempt(); // 每週有上限：開了就算一次（打倒前可以重開）
            apply(player, account, board);
            plugin.getSLF4JLogger().info("{} 開了回響：{}（Lv.{}，這週第 {} 次）", player.getName(), zone.name(), level, board.echoAttempts());
        }
        return problem;
    }

    /** 回響的「地圖」：那個區域的怪與 Boss（強化版）、對應的版面主題。 */
    MapConfig.MapDef echoMap(final Zone zone, final int level) {
        final ZoneConfig.BossDefinition boss = zone.boss();
        final MonsterConfig.TypeSettings type = boss.vanillaType() == null ? null : plugin.monsters().config().type(boss.vanillaType());
        return Echoes.map(config.weekly(), zone.definition(), type == null ? 1 : type.lifeMultiplier(),
                type == null ? 1 : type.damageMultiplier(), level);
    }

    /** 回響的 Boss 倒下：在裡面的人，這週還沒完成的拿獎勵。 */
    private void onEchoComplete(final MapInstance instance, final List<Player> players) {
        for (final Player player : players) {
            final Account account = ready(player);
            if (account == null) {
                player.sendMessage(plugin.text().prefixed("<gray>帳號資料還沒載入，這次的回響沒有算到你"));
                continue;
            }
            final BountyBoard board = board(account);
            if (!board.atLevel(instance.level(), comparedLevel(player))) {
                player.sendMessage(plugin.text().prefixed("<gray>回響的等級（Lv." + instance.level() + "）比你低太多，這週的回響不算你的"));
                continue;
            }
            if (!board.completeWeekly()) {
                player.sendMessage(plugin.text().prefixed("<gray>這週的回響你已經完成過了（掉落照拿），下週一 0 點再來"));
                continue;
            }
            apply(player, account, board);
        }
    }

    // ---- 功勳兌換 ----

    /** 兌換前的共同檢查；null = 可以。 */
    private String purchaseProblem(final Player player) {
        if (!config.shop().enabled()) {
            return "功勳兌換沒有開放（bounties.yml 的 shop.enabled）";
        }
        final String problem = plugin.characters().transactionProblem(player);
        if (problem != null) {
            return problem;
        }
        return accounts.containsKey(player.getUniqueId()) ? null : "帳號資料載入中，請稍等";
    }

    private static String notEnough(final Account account, final long price) {
        return account.merits < price ? "功勳不夠：要 " + format(price) + "，你有 " + format(account.merits) : null;
    }

    /** 多一頁倉庫；成功回傳 null。 */
    String buyStashPage(final Player player) {
        final String problem = purchaseProblem(player);
        if (problem != null) {
            return problem;
        }
        final Account account = accounts.get(player.getUniqueId());
        final long price = MeritShop.nextStashPrice(config.shop(), account.progress);
        if (price < 0) {
            return "倉庫頁已經換完了";
        }
        final String poor = notEnough(account, price);
        if (poor != null) {
            return poor;
        }
        if (plugin.stash() == null || plugin.stash().stash(player).isEmpty()) {
            return "倉庫還沒載入，請稍等";
        }
        final int pages = MeritShop.extraStashPages(account.progress) + 1;
        account.merits -= price;
        account.progress.put(MeritShop.STASH, String.valueOf(pages));
        plugin.stash().ensurePages(player);
        final int total = plugin.stash().stash(player).map(Stash::pageCount).orElse(Stash.BASE_PAGES + pages);
        commit(player, account, Map.of(MeritShop.STASH, String.valueOf(pages)),
                List.of(entry(player, null, "倉庫頁 +1（第 " + total + " 頁，" + price + " 功勳）", 1)), "兌換倉庫頁");
        player.sendMessage(plugin.text().prefixed("<green>倉庫多了一頁（現在 " + total + " 頁）<gray>　功勳 −" + format(price) + "，剩 "
                + MERIT + format(account.merits)));
        return null;
    }

    /** 這個等級用得到的最好那一種藥劑；沒有回傳 null。 */
    FlaskBase bestFlask(final Player player, final FlaskKind kind) {
        if (plugin.flasks() == null) {
            return null;
        }
        final int level = Math.max(1, plugin.players().get(player).level());
        FlaskBase best = null;
        for (final FlaskBase base : plugin.flasks().config().bases()) {
            if (base.kind() == kind && base.level() <= level && (best == null || base.level() > best.level())) {
                best = base;
            }
        }
        return best;
    }

    /** 一瓶藥劑；成功回傳 null。 */
    String buyFlask(final Player player, final FlaskKind kind) {
        final String problem = purchaseProblem(player);
        if (problem != null) {
            return problem;
        }
        final Account account = accounts.get(player.getUniqueId());
        final long price = config.shop().flask();
        final FlaskBase base = bestFlask(player, kind);
        if (base == null || price <= 0) {
            return "這裡沒有換藥劑";
        }
        final String poor = notEnough(account, price);
        if (poor != null) {
            return poor;
        }
        final ItemStack item = plugin.flasks().create(base, base.charges());
        // 藥劑欄空著就直接放進去（跟撿到一樣），不用背包有空位
        final boolean toFlaskSlot = plugin.flasks().canEquip(player, item);
        if (!toFlaskSlot && !wallet().fits(player, item)) {
            return "背包和隨身背包都放不下";
        }
        account.merits -= price;
        if (!toFlaskSlot || !plugin.flasks().equipIfEmpty(player, item)) {
            wallet().dropFor(player, wallet().give(player, item));
        }
        plugin.players().markDirty(player);
        commit(player, account, Map.of(), List.of(entry(player, null, Wallet.describe(item) + "（功勳兌換，" + price + " 功勳）", 1)),
                "兌換藥劑");
        player.sendMessage(plugin.text().prefixed("<green>換到 " + escape(base.name()) + "<gray>　功勳 −" + format(price) + "，剩 "
                + MERIT + format(account.merits)));
        return null;
    }

    /** 天穹碎片（開終局用）。 */
    CurrencyType fragment() {
        return plugin.maps() == null ? null : plugin.currencies().repository().get(plugin.maps().config().drops().fragment());
    }

    int fragmentsThisWeek(final Player player) {
        final Account account = accounts.get(player.getUniqueId());
        return account == null ? 0 : MeritShop.fragmentsThisWeek(account.progress, week());
    }

    /** 一片天穹碎片；成功回傳 null。 */
    String buyFragment(final Player player) {
        final String problem = purchaseProblem(player);
        if (problem != null) {
            return problem;
        }
        final Account account = accounts.get(player.getUniqueId());
        final BountyConfig.Shop shop = config.shop();
        final CurrencyType fragment = fragment();
        if (fragment == null || shop.fragmentWeekly() <= 0 || shop.fragment() <= 0) {
            return "這裡沒有換天穹碎片";
        }
        final int bought = MeritShop.fragmentsThisWeek(account.progress, week());
        // 2026-10：拿掉每週上限（weekly 只當「有沒有賣」，記數照舊）
        final String poor = notEnough(account, shop.fragment());
        if (poor != null) {
            return poor;
        }
        // 先放倉庫的通貨頁，滿了放通貨袋、背包（跟市集收錢一樣）
        final boolean stashRoom = plugin.stash() != null && plugin.stash().stash(player)
                .map(stash -> stash.currency(fragment.id()) < stash.currencyCap()).orElse(false);
        if (!stashRoom && !wallet().fits(player, plugin.currencies().create(fragment, 1))) {
            return "倉庫的通貨頁、通貨袋、背包都放不下";
        }
        account.merits -= shop.fragment();
        final int leftover = wallet().deposit(player, fragment.id(), 1);
        if (leftover > 0) {
            wallet().dropFor(player, plugin.currencies().create(fragment, leftover));
        }
        plugin.economy().ledger().produced(fragment.id(), EconomyLedger.MERITS, 1);
        final String value = MeritShop.fragmentValue(week(), bought + 1);
        account.progress.put(MeritShop.FRAGMENTS, value);
        commit(player, account, Map.of(MeritShop.FRAGMENTS, value),
                List.of(entry(player, null, fragment.name() + "（功勳兌換，" + shop.fragment() + " 功勳）", 1)), "兌換天穹碎片");
        player.sendMessage(plugin.text().prefixed("<green>換到 1 片" + escape(fragment.name()) + "<gray>（放在倉庫的通貨頁或通貨袋）　功勳 −" + format(shop.fragment()) + "，剩 "
                + MERIT + format(account.merits)));
        return null;
    }

    // ---- 鑰匙類通貨（2026-10：深淵星核、劫盜藍圖、金庫之主的鑰印）----

    /** 帳號進度裡記「這週換了幾個」的鍵（值跟天穹碎片一樣是「週|個數」）。 */
    static String shopKeyProgress(final BountyConfig.ShopKey key) {
        return "shop.key." + key.currency();
    }

    int keysThisWeek(final Player player, final BountyConfig.ShopKey key) {
        final Account account = accounts.get(player.getUniqueId());
        return account == null ? 0 : MeritShop.weeklyCount(account.progress.get(shopKeyProgress(key)), week());
    }

    /** 換一個鑰匙類通貨；成功回傳 null。 */
    String buyKey(final Player player, final BountyConfig.ShopKey key) {
        final String problem = purchaseProblem(player);
        if (problem != null) {
            return problem;
        }
        final Account account = accounts.get(player.getUniqueId());
        final CurrencyType type = plugin.currencies().repository().get(key.currency());
        if (type == null) {
            return "沒有這種通貨（bounties.yml 的 shop.keys：" + key.currency() + "）";
        }
        final int bought = MeritShop.weeklyCount(account.progress.get(shopKeyProgress(key)), week());
        // 2026-10：拿掉每週上限
        final String poor = notEnough(account, key.price());
        if (poor != null) {
            return poor;
        }
        final boolean stashRoom = plugin.stash() != null && plugin.stash().stash(player)
                .map(stash -> stash.currency(type.id()) < stash.currencyCap()).orElse(false);
        if (!stashRoom && !wallet().fits(player, plugin.currencies().create(type, 1))) {
            return "倉庫的通貨頁、通貨袋、背包都放不下";
        }
        account.merits -= key.price();
        final int leftover = wallet().deposit(player, type.id(), 1);
        if (leftover > 0) {
            wallet().dropFor(player, plugin.currencies().create(type, leftover));
        }
        plugin.economy().ledger().produced(type.id(), EconomyLedger.MERITS, 1);
        final String value = MeritShop.fragmentValue(week(), bought + 1);
        account.progress.put(shopKeyProgress(key), value);
        commit(player, account, Map.of(shopKeyProgress(key), value),
                List.of(entry(player, null, type.name() + "（功勳兌換，" + key.price() + " 功勳）", 1)), "兌換" + type.name());
        player.sendMessage(plugin.text().prefixed("<green>換到 1 個" + escape(type.name()) + "<gray>（放在倉庫的通貨頁或通貨袋）　功勳 −" + format(key.price()) + "，剩 " + MERIT + format(account.merits)));
        return null;
    }

    /** 這個帳號擁有的稱號代號（買過的、拿過的）。 */
    public Set<String> ownedTitles(final Player player) {
        final Account account = accounts.get(player.getUniqueId());
        return account == null ? Set.of() : owned(account);
    }

    // ---- 別的模組給的稱號（2026-10，贊助：累積贊助、登入天數、月卡幣兌換）----

    /** 別的模組算出來的稱號：擁有哪些（從帳號進度算）、還沒有的怎麼拿、要不要列出來。 */
    public interface TitleSource {
        /** 這個帳號從這裡擁有的稱號代號。 */
        Set<String> owned(Map<String, String> progress);

        /** 還沒有的這個稱號怎麼拿（MiniMessage 的幾行）；不是這裡的稱號回傳 null。 */
        List<String> howTo(Player player, String id);

        /** 還沒有的這個稱號要不要列在稱號選單（例如登入天數只列下一個）。 */
        default boolean listed(final Player player, final String id) {
            return true;
        }
        /** 擁有的這個稱號的來源（「累積贊助 1,000 星幣」）；不是這裡的回傳 null。 */
        default String sourceName(final String id) {
            return null;
        }
    }

    private final Map<String, TitleSource> titleSources = new LinkedHashMap<>();
    private final List<java.util.function.Consumer<Player>> readyListeners = new ArrayList<>();

    /** 登記（同一個 key 再登記就換掉）一個稱號來源。 */
    public void setTitleSource(final String key, final TitleSource source) {
        if (source == null) {
            titleSources.remove(key);
        } else {
            titleSources.put(key, source);
        }
        for (final Player player : plugin.getServer().getOnlinePlayers()) {
            applyTitle(player);
        }
    }

    /** 帳號資料載入好、角色也載入好（登入、換角色）時通知。 */
    public void addReadyListener(final java.util.function.Consumer<Player> listener) {
        readyListeners.add(listener);
    }

    /** 擁有的稱號：買過、拿過的，加上別的模組算出來的。 */
    private Set<String> owned(final Map<String, String> progress) {
        final Set<String> owned = MeritShop.ownedTitles(progress);
        for (final TitleSource source : titleSources.values()) {
            owned.addAll(source.owned(progress));
        }
        return owned;
    }

    /** 還沒有的稱號怎麼拿（別的模組給的）；沒有 null。 */
    List<String> sourceHowTo(final Player player, final String id) {
        for (final TitleSource source : titleSources.values()) {
            final List<String> lines = source.howTo(player, id);
            if (lines != null) {
                return lines;
            }
        }
        return null;
    }

    /** 稱號的來源（別的模組給的）；沒有 null。 */
    String sourceName(final String id) {
        for (final TitleSource source : titleSources.values()) {
            final String name = source.sourceName(id);
            if (name != null) {
                return name;
            }
        }
        return null;
    }

    /** 還沒有的稱號要不要列出來。 */
    boolean sourceListed(final Player player, final String id) {
        for (final TitleSource source : titleSources.values()) {
            if (source.howTo(player, id) != null) {
                return source.listed(player, id);
            }
        }
        return true;
    }

    /** 稱號與名字重新套用（別的模組改了會影響稱號的東西之後）。 */
    public void refreshTitle(final Player player) {
        applyTitle(player);
    }

    /** 帳號進度的值（記憶體）＋寫進資料庫（跟這個玩家的存檔同一個交易）；帳號還沒載入回傳 false。 */
    public boolean commitAccount(final Player player, final Map<String, String> values, final String what) {
        final Account account = accounts.get(player.getUniqueId());
        if (account == null) {
            return false;
        }
        for (final Map.Entry<String, String> entry : values.entrySet()) {
            if (entry.getValue() == null) {
                account.progress.remove(entry.getKey());
            } else {
                account.progress.put(entry.getKey(), entry.getValue());
            }
        }
        commit(player, account, values, List.of(), what);
        return true;
    }

    /**
     * 帳號進度的值改在記憶體，回傳把它寫進資料庫的工作（由呼叫端放進自己的交易，例如管理員發放的領取）。
     * 帳號還沒載入回傳 null。
     */
    public tw.exilecore.character.CharacterRepository.ExtraWork accountWork(final Player player, final Map<String, String> values) {
        final Account account = accounts.get(player.getUniqueId());
        if (account == null) {
            return null;
        }
        flush(player);
        for (final Map.Entry<String, String> entry : values.entrySet()) {
            if (entry.getValue() == null) {
                account.progress.remove(entry.getKey());
            } else {
                account.progress.put(entry.getKey(), entry.getValue());
            }
        }
        final UUID uuid = account.uuid;
        final Map<String, String> copy = new LinkedHashMap<>(values);
        return connection -> repository.setProgress(connection, uuid, copy);
    }

    /** 能不能用錢（交易系統沒卡住、帳號載入了）；可以回傳 null。 */
    public String spendProblem(final Player player) {
        final String problem = plugin.characters().transactionProblem(player);
        if (problem != null) {
            return problem;
        }
        return accounts.containsKey(player.getUniqueId()) ? null : "帳號資料載入中，請稍等";
    }

    /** 戴著的稱號代號（沒戴 null）。 */
    public String wornTitle(final Player player) {
        final Account account = accounts.get(player.getUniqueId());
        final BountyConfig.Title title = account == null ? null : equippedTitle(account);
        return title == null ? null : title.id();
    }

    /** 買一個稱號（買了就戴上）；成功回傳 null。 */
    String buyTitle(final Player player, final String id) {
        final String problem = purchaseProblem(player);
        if (problem != null) {
            return problem;
        }
        final Account account = accounts.get(player.getUniqueId());
        final BountyConfig.Title title = config.shop().titles().get(id);
        if (title == null) {
            return "沒有這個稱號";
        }
        final Set<String> owned = MeritShop.ownedTitles(account.progress);
        if (owned.contains(id)) {
            return "已經有這個稱號了";
        }
        final String poor = notEnough(account, title.price());
        if (poor != null) {
            return poor;
        }
        owned.add(id);
        // 2026-10-17：活動、指導員這些不是商店的稱號也算認得的（以前記不下時會被優先丟掉）
        final Set<String> knownTitles = new LinkedHashSet<>(config.shop().titles().keySet());
        knownTitles.addAll(extraTitles.keySet());
        final String value = MeritShop.titlesValue(owned, knownTitles);
        if (!MeritShop.ownedTitles(Map.of(MeritShop.TITLES, value)).contains(id)) {
            return "稱號太多了，記不下（請通知管理員）";
        }
        account.merits -= title.price();
        account.progress.put(MeritShop.TITLES, value);
        commit(player, account, Map.of(MeritShop.TITLES, value),
                List.of(entry(player, null, "稱號「" + title.name() + "」（" + title.price() + " 功勳）", 1)), "兌換稱號");
        wearTitle(player, id); // 買了就戴上（戴哪一個另外寫，不跟兌換一起重試）
        player.sendMessage(plugin.text().prefixed("<green>換到稱號 " + MeritShop.tag(title) + "<green>，已經戴上<gray>　功勳 −"
                + format(title.price()) + "，剩 " + MERIT + format(account.merits)));
        final String bonus = MeritShop.bonusText(title);
        if (bonus != null) {
            player.sendMessage(plugin.text().prefixed("<#7fe07f>稱號的永久加成：<white>" + bonus + "<gray>（擁有就有，不用戴；帳號裡每個角色都有）"));
        }
        return null;
    }

    /** 戴上（id 為 null = 拿下）一個買過的稱號；成功回傳 null。 */
    String wearTitle(final Player player, final String id) {
        final Account account = accounts.get(player.getUniqueId());
        if (account == null) {
            return "帳號資料載入中，請稍等";
        }
        if (id != null && (!owned(account).contains(id) || title(id) == null)) {
            return "還沒有這個稱號";
        }
        final Map<String, String> values = new LinkedHashMap<>();
        values.put(MeritShop.TITLE, id);
        if (id == null) {
            account.progress.remove(MeritShop.TITLE);
        } else {
            account.progress.put(MeritShop.TITLE, id);
        }
        write(account.uuid, values);
        applyTitle(player);
        return null;
    }

    private TradeLogRepository.Entry entry(final Player player, final String itemUuid, final String what, final int amount) {
        return new TradeLogRepository.Entry(UUID.randomUUID().toString(), TradeLogRepository.MERITS, itemUuid, what, amount,
                null, "軍需官", player.getUniqueId(), player.getName());
    }

    private Wallet wallet() {
        return plugin.economy().wallet();
    }

    // ---- 顯示 ----

    /** 帳號的功勳（還沒載入回傳 −1）。 */
    public long merits(final Player player) {
        final Account account = accounts.get(player.getUniqueId());
        return account == null ? -1 : account.merits;
    }

    /** 多換了幾頁倉庫。 */
    public int extraStashPages(final Player player) {
        final Account account = accounts.get(player.getUniqueId());
        return account == null ? 0 : MeritShop.extraStashPages(account.progress);
    }

    /** 多換了幾個角色格（帳號資料還沒載入 0）。 */
    public int extraCharacterSlots(final Player player) {
        final Account account = accounts.get(player.getUniqueId());
        return account == null ? 0 : MeritShop.extraCharacterSlots(account.progress);
    }

    /** 換一個角色格；成功回傳 null。 */
    String buyCharacterSlot(final Player player) {
        final String problem = purchaseProblem(player);
        if (problem != null) {
            return problem;
        }
        final Account account = accounts.get(player.getUniqueId());
        final long price = MeritShop.nextCharacterSlotPrice(config.shop(), account.progress);
        if (price < 0) {
            return "角色格已經換完了";
        }
        final String poor = notEnough(account, price);
        if (poor != null) {
            return poor;
        }
        final int extra = MeritShop.extraCharacterSlots(account.progress) + 1;
        account.merits -= price;
        account.progress.put(MeritShop.CHARACTER_SLOTS, String.valueOf(extra));
        final int total = plugin.characters().config().characterSlots() + extra;
        commit(player, account, Map.of(MeritShop.CHARACTER_SLOTS, String.valueOf(extra)),
                List.of(entry(player, null, "角色格 +1（第 " + total + " 格，" + price + " 功勳）", 1)), "兌換角色格");
        player.sendMessage(plugin.text().prefixed("<green>多了一個角色格（現在 " + total + " 格；/char 建立新角色）<gray>　功勳 −" + format(price)
                + "，剩 " + MERIT + format(account.merits)));
        return null;
    }

    /** 名字前面的稱號（MiniMessage；沒戴回傳空字串）。 */
    public String titleTag(final Player player) {
        final Account account = accounts.get(player.getUniqueId());
        final BountyConfig.Title title = account == null ? null : equippedTitle(account);
        return title == null ? "" : MeritShop.tag(title);
    }

    /** 功勳商店以外的稱號（每月活動的限定稱號）：代號 → 稱號。 */
    private final Map<String, BountyConfig.Title> extraTitles = new LinkedHashMap<>();

    /** 別的模組（每月活動）登記它會發的稱號；之後戴、顯示都認得（加成可能變了：線上的人重算屬性）。 */
    public void registerTitles(final Map<String, BountyConfig.Title> titles) {
        extraTitles.putAll(titles);
        for (final Player player : plugin.getServer().getOnlinePlayers()) {
            plugin.players().markDirty(player);
        }
    }

    /**
     * 擁有的稱號給的永久加成（像分身獎勵：整個帳號的角色都有，不用戴）；修飾器的來源寫「稱號「名字」」。
     * 屬性表重算時呼叫：只看帳號資料，不碰角色狀態；帳號還沒載入是空的。
     */
    public List<tw.exilecore.stats.Modifier> titleModifiers(final Player player) {
        final Account account = accounts.get(player.getUniqueId());
        if (account == null) {
            return List.of();
        }
        final List<tw.exilecore.stats.Modifier> list = new ArrayList<>();
        for (final String id : owned(account)) {
            final BountyConfig.Title title = title(id);
            if (title != null) {
                list.addAll(title.stats());
            }
        }
        return list;
    }

    /** 擁有的稱號加起來的加成（同一種屬性合成一條；選單顯示用）。 */
    public List<tw.exilecore.stats.Modifier> titleBonus(final Player player) {
        return tw.exilecore.ascendancy.StatLines.merge(titleModifiers(player), "稱號加成");
    }

    /** 代號找稱號：功勳商店的，或登記過的限定稱號；沒有 null。 */
    public BountyConfig.Title title(final String id) {
        final BountyConfig.Title title = id == null ? null : config.shop().titles().get(id);
        return title != null ? title : id == null ? null : extraTitles.get(id);
    }

    /** 戴著的稱號（沒戴、沒有、已經不存在的 null）。 */
    private BountyConfig.Title equippedTitle(final Account account) {
        final Set<String> owned = owned(account);
        final String id = account.progress.get(MeritShop.TITLE);
        if (id == null || !owned.contains(id)) {
            // 2026-10：管理員沒戴別的稱號：自動顯示管理員稱號
            return owned.contains(ADMIN_TITLE) ? title(ADMIN_TITLE) : null;
        }
        return title(id);
    }

    /** 所有認得的稱號：功勳兌換賣的（照 bounties.yml 的順序），再接登記過的限定稱號（每月活動）。 */
    public List<BountyConfig.Title> allTitles() {
        final List<BountyConfig.Title> list = new ArrayList<>(config.shop().titles().values());
        for (final BountyConfig.Title title : extraTitles.values()) {
            if (!config.shop().titles().containsKey(title.id())) {
                list.add(title);
            }
        }
        return list;
    }

    /** 這個稱號是不是功勳兌換賣的（不是的話就是別的模組發的限定稱號）。 */
    public boolean isShopTitle(final String id) {
        return config.shop().titles().containsKey(id);
    }

    /** 選單的「稱號」：擁有的稱號一覽，點一下戴上／拿下，還沒有的寫怎麼拿。 */
    public void openTitles(final Player player) {
        new TitleMenu(plugin, player, this).open();
    }

    /** 擁有的、但不是功勳商店賣的稱號（限定稱號；給兌換選單列出來戴）。 */
    List<BountyConfig.Title> ownedExtraTitles(final Player player) {
        final Account account = accounts.get(player.getUniqueId());
        final List<BountyConfig.Title> list = new ArrayList<>();
        if (account == null) {
            return list;
        }
        // 2026-10：功勳商店只列功勳、活動、擊殺這類「拿過就記下來」的稱號；別的模組算出來的（累積贊助、自訂稱號、
        // 登入天數、月卡）不列（在「稱號」選單看、戴）
        for (final String id : MeritShop.ownedTitles(account.progress)) {
            if (!config.shop().titles().containsKey(id) && extraTitles.containsKey(id)) {
                list.add(extraTitles.get(id));
            }
        }
        return list;
    }

    /**
     * 發一個稱號（每月活動的限定稱號；不用功勳），有了就戴上；成功回傳 null。
     * 稱號要先 {@link #registerTitles} 登記過。
     */
    public String grantTitle(final Player player, final String id) {
        final Account account = accounts.get(player.getUniqueId());
        if (account == null) {
            return "帳號資料載入中，請稍等";
        }
        if (title(id) == null) {
            return "沒有這個稱號";
        }
        if (ADMIN_TITLE.equals(id)) {
            return "管理員稱號跟著權限走（OP 自動有），不能發"; // 2026-10-17：以前發了會永久寫進帳號
        }
        final Set<String> owned = MeritShop.ownedTitles(account.progress);
        if (owned.contains(id)) {
            return "已經有這個稱號了";
        }
        owned.add(id);
        final Set<String> known = new LinkedHashSet<>(config.shop().titles().keySet());
        known.addAll(extraTitles.keySet());
        final String value = MeritShop.titlesValue(owned, known);
        if (!MeritShop.ownedTitles(Map.of(MeritShop.TITLES, value)).contains(id)) {
            return "稱號太多了，記不下（請通知管理員）";
        }
        account.progress.put(MeritShop.TITLES, value);
        commit(player, account, Map.of(MeritShop.TITLES, value), List.of(), "活動稱號");
        wearTitle(player, id);
        return null;
    }

    /** 給功勳（每月活動的獎勵）；寫進帳號。 */
    public void grantMerits(final Player player, final long amount, final String what) {
        final Account account = accounts.get(player.getUniqueId());
        if (account == null || amount <= 0) {
            return;
        }
        account.merits += amount;
        commit(player, account, Map.of(), List.of(entry(player, null, what + "（+" + amount + " 功勳）", 1)), what);
    }

    /**
     * 管理員發的功勳：加到帳號（記憶體），回傳把功勳寫進資料庫的工作——由呼叫端放進領取的那個交易裡，
     * 跟「這筆已經領了」一起寫（當機也不會領兩次、少領）。帳號還沒載入回傳 null（先不發，等下次領）。
     */
    public tw.exilecore.character.CharacterRepository.ExtraWork grantMeritsWork(final Player player, final long amount) {
        final Account account = accounts.get(player.getUniqueId());
        if (account == null || amount <= 0) {
            return null;
        }
        flush(player);
        account.merits += amount;
        final UUID uuid = account.uuid;
        final long merits = account.merits;
        return connection -> repository.setMerits(connection, uuid, merits);
    }

    /** 管理員收回功勳（2026-10-18）：拿了幾點、寫資料庫的工作。 */
    public record TakenMerits(long taken, tw.exilecore.character.CharacterRepository.ExtraWork work) {
    }

    /** 管理員收回功勳（2026-10-18）：有多少拿多少（扣到 0 為止）；帳號還沒載入回傳 null。 */
    public TakenMerits revokeMeritsWork(final Player player, final long amount) {
        final Account account = accounts.get(player.getUniqueId());
        if (account == null || amount <= 0) {
            return null;
        }
        flush(player);
        final long taken = Math.min(amount, Math.max(0, account.merits));
        account.merits -= taken;
        final UUID uuid = account.uuid;
        final long merits = account.merits;
        return new TakenMerits(taken, connection -> repository.setMerits(connection, uuid, merits));
    }

    /** 帳號進度裡的一個值（別的模組存自己的東西；沒有 null、帳號還沒載入也 null）。 */
    public String progressValue(final Player player, final String key) {
        final Account account = accounts.get(player.getUniqueId());
        return account == null ? null : account.progress.get(key);
    }

    /** 帳號載入好了沒（別的模組要存東西前先問）。 */
    public boolean accountLoaded(final Player player) {
        return accounts.containsKey(player.getUniqueId());
    }

    /** 寫一個帳號進度的值（馬上寫；null = 刪掉）。 */
    public void writeProgress(final Player player, final String key, final String value) {
        final Account account = accounts.get(player.getUniqueId());
        if (account == null) {
            return;
        }
        if (value == null) {
            account.progress.remove(key);
        } else {
            account.progress.put(key, value);
        }
        final Map<String, String> values = new LinkedHashMap<>();
        values.put(key, value);
        write(account.uuid, values);
    }

    /** 稱號套到 TAB 列表與聊天的名字上（角色模組的 updateListName 會問 titleTag）。 */
    void applyTitle(final Player player) {
        plugin.players().updateListName(player);
        plugin.players().markDirty(player); // 稱號的永久加成（擁有的稱號變了、帳號剛載入、設定重讀）
    }

    /** 今天的委託（給選單；還沒產生、沒有角色回傳空的）。 */
    List<BountyBoard.Daily> today(final Player player) {
        final Account account = ready(player);
        return account == null ? List.of() : board(account).today();
    }

    /** 任務追蹤上的每日委託（都完成了就不顯示）。 */
    public List<String> trackerLines(final Player player) {
        final Account account = ready(player);
        if (account == null) {
            return List.of();
        }
        final BountyBoard board = board(account);
        final List<BountyBoard.Daily> dailies = board.today();
        final int done = board.doneToday();
        if (dailies.isEmpty() || done >= dailies.size()) {
            return List.of();
        }
        final List<String> lines = new ArrayList<>();
        lines.add(COLOR + "每日委託 <white>" + done + "/" + dailies.size());
        for (final BountyBoard.Daily daily : dailies) {
            if (!daily.done()) {
                final BountyConfig.Template template = board.template(daily);
                lines.add("<gray> " + escape(template.describe()) + " <white>" + daily.count() + "/" + template.count());
            }
        }
        return lines;
    }

    private void updateTracker(final Player player) {
        if (plugin.quests() != null) {
            plugin.quests().updateTracker(player);
        }
    }

    /** 打開委託板。 */
    public void open(final Player player) {
        if (!plugin.players().get(player).hasCharacter()) {
            player.sendMessage(plugin.text().prefixed("<red>先選一個角色（/char）"));
            return;
        }
        if (!accounts.containsKey(player.getUniqueId())) {
            player.sendMessage(plugin.text().prefixed("<gray>帳號資料載入中，請稍等"));
            return;
        }
        refreshDay(player, accounts.get(player.getUniqueId()));
        new BountyMenu(plugin, player, this).open();
    }

    String untilTomorrow() {
        return BountyClock.describe(BountyClock.untilNextDay(now()));
    }

    String untilNextWeek() {
        return BountyClock.describe(BountyClock.untilNextWeek(now()));
    }

    // ---- 管理員 ----

    /** 管理員：給（負的扣）功勳。 */
    void adminMerits(final Player player, final long delta, final boolean set) {
        final Account account = accounts.get(player.getUniqueId());
        if (account == null) {
            return;
        }
        account.merits = Math.max(0, set ? delta : account.merits + delta);
        // 2026-10-18：跟其他功勳變動走同一條 commit（以前直接寫 DB，可能被重試中的舊快照蓋回）
        commit(player, account, Map.of(), List.of(), "管理員改功勳");
    }

    /** 管理員：拿掉一個稱號（測試稱號的加成用；戴著的話一起拿下）；成功回傳 null。 */
    String adminRemoveTitle(final Player player, final String id) {
        final Account account = accounts.get(player.getUniqueId());
        if (account == null) {
            return "帳號資料載入中，請稍等";
        }
        final Set<String> owned = MeritShop.ownedTitles(account.progress);
        if (!owned.remove(id)) {
            return "沒有這個稱號";
        }
        final String value = MeritShop.titlesValue(owned);
        account.progress.put(MeritShop.TITLES, value);
        final Map<String, String> values = new LinkedHashMap<>();
        values.put(MeritShop.TITLES, value);
        if (id.equals(account.progress.get(MeritShop.TITLE))) {
            account.progress.remove(MeritShop.TITLE);
            values.put(MeritShop.TITLE, null);
        }
        commit(player, account, values, List.of(), "管理員拿掉稱號");
        applyTitle(player);
        return null;
    }

    /** 管理員：今天的委託重抽（不同的一批）。 */
    void adminReroll(final Player player) {
        final Account account = ready(player);
        if (account == null) {
            return;
        }
        BountyBoard board = board(account);
        board.clearToday();
        apply(player, account, board);
        board = board(account);
        board.generate(plugin.items().random().nextLong(), mapsUnlocked(player));
        apply(player, account, board);
    }

    /** 管理員：直接完成第 slot 格（從 0 開始）。 */
    boolean adminComplete(final Player player, final int slot) {
        final Account account = ready(player);
        if (account == null) {
            return false;
        }
        refreshDay(player, account);
        final BountyBoard board = board(account);
        if (!board.forceComplete(slot)) {
            return false;
        }
        apply(player, account, board);
        return true;
    }

    /** 管理員：這週的回響變回沒完成（開回響的冷卻也清掉）。 */
    void adminResetWeekly(final Player player) {
        final Account account = accounts.get(player.getUniqueId());
        if (account == null) {
            return;
        }
        account.echoReadyAt = 0;
        if (account.progress.remove(BountyBoard.WEEKLY) != null) {
            final Map<String, String> values = new LinkedHashMap<>();
            values.put(BountyBoard.WEEKLY, null);
            write(account.uuid, values);
        }
    }

    /** 管理員看的狀態。 */
    List<String> adminInfo(final Player player) {
        final List<String> lines = new ArrayList<>();
        final Account account = accounts.get(player.getUniqueId());
        if (account == null) {
            lines.add("<red>帳號資料沒有載入");
            return lines;
        }
        final BountyBoard board = board(account);
        lines.add("<white>功勳 " + format(account.merits) + "<gray>　今天 " + board.day() + "、這週 " + board.week()
                + "　每週回響：" + (board.weeklyDone() ? "<green>完成" : "<yellow>還沒"));
        for (int slot = 0; slot < config.dailyCount(); slot++) {
            final BountyBoard.Daily daily = board.slot(slot);
            if (daily == null) {
                lines.add("<gray> " + (slot + 1) + ". <dark_gray>（今天還沒產生）");
                continue;
            }
            final BountyConfig.Template template = board.template(daily);
            lines.add("<gray> " + (slot + 1) + ". <white>" + escape(template.describe()) + " <gray>" + daily.count() + "/" + template.count()
                    + (daily.done() ? " <green>完成" : ""));
        }
        lines.add("<gray>倉庫多 " + MeritShop.extraStashPages(account.progress) + " 頁、這週換了 " + MeritShop.fragmentsThisWeek(account.progress,
                board.week()) + " 片碎片、稱號 " + String.join("、", MeritShop.ownedTitles(account.progress))
                + "、戴著 " + (account.progress.getOrDefault(MeritShop.TITLE, "（沒有）")));
        return lines;
    }

    // ---- 工具 ----

    static String escape(final String text) {
        return ItemRenderer.escape(text == null ? "" : text);
    }

    static String format(final long amount) {
        return String.format(Locale.ROOT, "%,d", amount);
    }

    /** 重新讀設定後：在線的人重新對一次（委託種類可能變了）。 */
    void reloaded() {
        registerBossTitles();
        for (final Player player : plugin.getServer().getOnlinePlayers()) {
            final Account account = ready(player);
            if (account != null) {
                refreshDay(player, account);
            }
            applyTitle(player);
            updateTracker(player);
        }
    }
}
