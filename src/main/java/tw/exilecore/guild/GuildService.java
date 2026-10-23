package tw.exilecore.guild;

import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import org.bukkit.Sound;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;
import tw.exilecore.ExileCore;
import tw.exilecore.ascendancy.StatLines;
import tw.exilecore.character.PlayerState;
import tw.exilecore.economy.EconomySources;
import tw.exilecore.economy.GoldService;
import tw.exilecore.economy.TradeLogRepository;
import tw.exilecore.economy.Wallet;
import tw.exilecore.item.ItemData;
import tw.exilecore.item.ItemRenderer;
import tw.exilecore.monster.MonsterData;
import tw.exilecore.stats.Modifier;

import java.sql.SQLException;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Date;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.function.Consumer;

/**
 * 公會：建立、邀請、入會申請、加入、離開、請出、階級、公告、公會頻道、公會等級與加成、公會倉庫、排行。
 * 名單與規則在 {@link GuildRoster}（純邏輯）；這裡負責找玩家、發訊息、金幣、存檔。只在主執行緒使用（排行的快照除外）。
 * <p>
 * 存檔：公會與成員是「有變就整個公會存一次」（背景執行緒；失敗的下一輪再存）。公會經驗（殺怪）不是每殺一隻就存，
 * 每 {@value #FLUSH_SECONDS} 秒與關服時存。動到物品與金幣的（建立、捐獻、倉庫存取）跟那個玩家的存檔同一個資料庫交易
 * （{@code CharacterService.commit}）：東西不會兩邊都有或兩邊都沒有。
 */
public final class GuildService {

    /** 公會的顏色（頻道、標籤）。 */
    public static final String COLOR = "<#8fd18f>";
    private static final String COLOR_END = "</#8fd18f>";
    /** 交易紀錄（物品追查）裡公會倉庫存取的種類。 */
    private static final String TRADE_KIND = "guild";
    /** 每個公會留幾筆紀錄。 */
    static final int LOG_KEEP = 300;
    /** 選單裡看最近幾筆。 */
    public static final int LOG_SHOWN = 36;
    static final int FLUSH_SECONDS = 30;
    /** 解散的確認幾秒內有效。 */
    private static final int CONFIRM_SECONDS = 30;

    /** 排行榜的一列（給網頁：別的執行緒會讀，所以是不會變的快照）。 */
    public record LadderRow(int rank, String name, String tag, int level, long experience, long toNext, long total,
                            int members, int maxMembers, String leader, int online, long created, List<LadderMember> roster) {
    }

    /** 排行榜上公會的一個成員（2026-10：網頁上點公會看得到成員；階級高、貢獻多的在前）。 */
    public record LadderMember(String name, String rank, long contribution, boolean online) {
    }

    private final ExileCore plugin;
    private final GuildConfig config;
    private final GuildRepository repository;
    private final GuildRoster roster = new GuildRoster();
    /** 有變動、還沒存的公會（公會經驗、貢獻、成員名字）。 */
    private final Set<Long> dirty = new LinkedHashSet<>();
    /** 讀進來的公會倉庫（第一次打開才讀，之後留在記憶體）。 */
    private final Map<Long, GuildStash> stashes = new HashMap<>();
    /** 倉庫還在讀：讀好之後要做的事。 */
    private final Map<Long, List<Consumer<GuildStash>>> stashWaiters = new HashMap<>();
    /** 整理、頁籤設定沒存成功的公會倉庫：下一輪整個重存。 */
    private final Set<Long> stashDirty = new LinkedHashSet<>();
    private tw.exilecore.stash.StashOrganizer organizer;
    /** 開著公會倉庫的人。 */
    private final Map<UUID, GuildStashMenu> openStashes = new HashMap<>();
    /** 等著確認解散的會長 → 期限（毫秒）。 */
    private final Map<UUID, Long> disbandConfirm = new HashMap<>();
    private final Map<Integer, List<Modifier>> modifierCache = new HashMap<>();
    private volatile List<LadderRow> ladder = List.of();
    private long ticks;
    /** 入會申請（2026-10）：公會 → 申請的人 → 申請（舊的在前）。 */
    private final Map<Long, java.util.LinkedHashMap<UUID, Application>> applications = new HashMap<>();

    /** 一個人最多同時申請幾個公會。 */
    static final int APPLY_MAX = 3;
    /** 申請放多久沒人處理就自動取消。 */
    static final long APPLY_MILLIS = 7L * 24 * 60 * 60 * 1000;
    /** 申請留言最多幾個字。 */
    public static final int APPLY_MESSAGE_MAX = 60;

    /** 一筆入會申請。 */
    public record Application(long guildId, UUID uuid, String name, int level, String message, long createdAt) {
    }

    GuildService(final ExileCore plugin, final GuildConfig config, final GuildRepository repository) {
        this.plugin = plugin;
        this.config = config;
        this.repository = repository;
    }

    public GuildConfig config() {
        return config;
    }

    // ---- 啟動、重載、關閉 ----

    /** 從資料庫讀全部的公會（啟動時，同步）。 */
    void load() throws SQLException {
        final List<GuildRepository.GuildRow> rows = plugin.database().sync(repository::loadAll);
        roster.clear();
        int skipped = 0;
        for (final GuildRepository.GuildRow row : rows) {
            final Guild guild = row.toGuild();
            if (guild == null) {
                skipped++;
                plugin.getSLF4JLogger().warn("公會「{}」（#{}）沒有成員，略過", row.name(), row.id());
                continue;
            }
            roster.load(guild);
        }
        for (final Guild guild : roster.all()) {
            final long id = guild.id();
            plugin.database().async(connection -> repository.trimLog(connection, id, LOG_KEEP));
        }
        applications.clear();
        final long now = System.currentTimeMillis();
        for (final GuildRepository.ApplicationRow row : plugin.database().sync(repository::loadApplications)) {
            if (roster.guild(row.guildId()) == null || roster.guildOf(row.uuid()) != null || row.createdAt() + APPLY_MILLIS <= now) {
                continue; // 公會不在了、人已經有公會、過期了：下次有人動到時一起清
            }
            applications.computeIfAbsent(row.guildId(), key -> new java.util.LinkedHashMap<>())
                    .put(row.uuid(), new Application(row.guildId(), row.uuid(), row.name(), row.level(), row.message(), row.createdAt()));
        }
        refreshLadder();
        plugin.getSLF4JLogger().info("公會：{} 個{}；建立要 Lv.{} 與 {} 金幣，最多 {} 人，最高 {} 級", roster.all().size(),
                skipped > 0 ? "（" + skipped + " 個沒有成員的略過）" : "", config.createLevel(), GoldService.format(config.createCost()),
                config.maxMembers(), config.maxLevel());
    }

    /** 設定重載後：加成換新、倉庫頁數補上。 */
    void reloaded() {
        modifierCache.clear();
        for (final Guild guild : roster.all()) {
            final GuildStash stash = stashes.get(guild.id());
            if (stash != null) {
                stash.ensurePages(stashPages(guild));
            }
            for (final Player member : online(guild)) {
                plugin.players().markDirty(member);
            }
        }
        refreshLadder();
    }

    /** 關服：還沒存的公會排進資料庫（資料庫關閉前會等它們寫完）。 */
    void shutdown() {
        flush();
        for (final UUID uuid : new ArrayList<>(openStashes.keySet())) {
            final Player player = plugin.getServer().getPlayer(uuid);
            if (player != null) {
                player.closeInventory();
            }
        }
        openStashes.clear();
    }

    /** 每秒。 */
    void tick() {
        ticks++;
        final long now = System.currentTimeMillis();
        if (ticks % 60 == 0) {
            expireApplications(now);
        }
        for (final Map.Entry<UUID, List<GuildRoster.Invite>> entry : roster.expire(now).entrySet()) {
            final Player invitee = plugin.getServer().getPlayer(entry.getKey());
            for (final GuildRoster.Invite invite : entry.getValue()) {
                final Guild guild = roster.guild(invite.guildId());
                final Player inviter = plugin.getServer().getPlayer(invite.inviter());
                if (guild == null) {
                    continue;
                }
                if (inviter != null && invitee != null) {
                    tell(inviter, "<gray>給 " + shown(invitee) + " 的公會邀請過期了");
                }
                if (invitee != null) {
                    tell(invitee, "<gray>公會「" + ItemRenderer.escape(guild.name()) + "」的邀請過期了");
                }
            }
        }
        // 成員顯示的名字跟著現在的角色（換角色、改名）
        for (final Player player : plugin.getServer().getOnlinePlayers()) {
            final Guild guild = roster.guildOf(player.getUniqueId());
            if (guild == null) {
                continue;
            }
            final PlayerState state = plugin.players().find(player.getUniqueId()).orElse(null);
            if (state != null && state.isLoaded() && state.hasCharacter() && guild.rename(player.getUniqueId(), plugin.displayName(player))) {
                dirty.add(guild.id());
            }
        }
        if (!disbandConfirm.isEmpty()) {
            disbandConfirm.values().removeIf(until -> until < now);
        }
        if (ticks % FLUSH_SECONDS == 0) {
            flush();
            refreshLadder();
        }
    }

    /** 有變動的公會都存一次。 */
    void flush() {
        flushStashes();
        if (dirty.isEmpty()) {
            return;
        }
        for (final long id : new ArrayList<>(dirty)) {
            final Guild guild = roster.guild(id);
            if (guild != null) {
                persist(guild);
            }
        }
        dirty.clear();
    }

    // ---- 查詢（給其他模組） ----

    /** 這個帳號的公會；沒有回傳 null。 */
    public Guild guildOf(final UUID account) {
        return roster.guildOf(account);
    }

    public Guild guild(final long id) {
        return roster.guild(id);
    }

    /** 名字旁邊的公會標籤（MiniMessage，例如 [星骸]）；沒有公會回傳空字串。 */
    public String tag(final Player player) {
        final Guild guild = roster.guildOf(player.getUniqueId());
        return guild == null ? "" : COLOR + "[" + ItemRenderer.escape(guild.tag()) + "]" + COLOR_END;
    }

    /** 公會加成（屬性表的來源）。 */
    public List<Modifier> modifiers(final Player player) {
        final Guild guild = roster.guildOf(player.getUniqueId());
        if (guild == null) {
            return List.of();
        }
        return modifierCache.computeIfAbsent(guild.level(), config::modifiers);
    }

    /** 這個代號的公會；不在了回 null。 */
    public Guild guildById(final long id) {
        return roster.guild(id);
    }

    /** 全部的公會（排行的順序）。 */
    public List<Guild> top() {
        return roster.top(config);
    }

    /** 這個人手上的邀請（公會名稱；新的在後）。 */
    public List<String> inviteNames(final Player player) {
        final List<String> names = new ArrayList<>();
        for (final GuildRoster.Invite invite : roster.invitesFor(player.getUniqueId(), System.currentTimeMillis())) {
            final Guild guild = roster.guild(invite.guildId());
            if (guild != null) {
                names.add(guild.name());
            }
        }
        return names;
    }

    /** 公會裡在線的人。 */
    public List<Player> online(final Guild guild) {
        final List<Player> result = new ArrayList<>();
        if (guild != null) {
            for (final Guild.Member member : guild.members()) {
                final Player player = plugin.getServer().getPlayer(member.uuid());
                if (player != null) {
                    result.add(player);
                }
            }
        }
        return result;
    }

    /** 排行榜的快照（任何執行緒都可以讀）。 */
    public List<LadderRow> ladder() {
        return ladder;
    }

    private void refreshLadder() {
        final List<LadderRow> rows = new ArrayList<>();
        int rank = 0;
        for (final Guild guild : roster.top(config)) {
            final Guild.Member leader = guild.member(guild.leader());
            final List<Guild.Member> ordered = new ArrayList<>(guild.members());
            ordered.sort(java.util.Comparator.comparingInt((Guild.Member member) -> member.rank().power()).reversed()
                    .thenComparing(java.util.Comparator.comparingLong(Guild.Member::contribution).reversed()));
            final List<LadderMember> members = new ArrayList<>();
            for (final Guild.Member member : ordered) {
                members.add(new LadderMember(member.name(), member.rank().displayName(), member.contribution(),
                        plugin.getServer().getPlayer(member.uuid()) != null));
            }
            rows.add(new LadderRow(++rank, guild.name(), guild.tag(), guild.level(), guild.experience(), config.xpToNext(guild.level()),
                    guild.totalExperience(config), guild.size(), config.maxMembers(), leader == null ? "" : leader.name(), online(guild).size(),
                    guild.createdAt(), List.copyOf(members)));
        }
        ladder = List.copyOf(rows);
    }

    // ---- 建立 ----

    /** 建立公會（要等級與金幣）。 */
    public void create(final Player player, final String tagInput, final String nameInput) {
        final String tag = tagInput == null ? "" : tagInput.strip();
        final String name = nameInput == null ? "" : nameInput.strip();
        final String problem = plugin.characters().transactionProblem(player);
        if (problem != null) {
            tell(player, "<red>" + problem);
            return;
        }
        if (roster.guildOf(player.getUniqueId()) != null) {
            tell(player, "<red>你已經在公會裡了（先 /guild leave）");
            return;
        }
        final PlayerState state = plugin.players().get(player);
        if (state.level() < config.createLevel()) {
            tell(player, "<red>建立公會要 Lv." + config.createLevel() + "（你現在 Lv." + state.level() + "）");
            return;
        }
        String textProblem = GuildRoster.textProblem(name, config.nameMin(), config.nameMax());
        if (textProblem != null) {
            tell(player, "<red>公會名稱" + textProblem);
            return;
        }
        textProblem = GuildRoster.textProblem(tag, config.tagMin(), config.tagMax());
        if (textProblem != null) {
            tell(player, "<red>公會標籤" + textProblem);
            return;
        }
        for (final String text : List.of(name, tag)) {
            final String blocked = plugin.characters().config().nameFilter().problem(text);
            if (blocked != null) {
                tell(player, "<red>「" + ItemRenderer.escape(text) + "」不能用：" + ItemRenderer.escape(blocked));
                return;
            }
        }
        final long cost = config.createCost();
        if (plugin.gold().balance(player) < cost) {
            tell(player, "<red>建立公會要 " + GoldService.format(cost) + " 金幣（你有 " + GoldService.format(plugin.gold().balance(player)) + "）");
            return;
        }
        final long now = System.currentTimeMillis();
        final String leaderName = plugin.displayName(player);
        final GuildRoster.Joined joined = roster.create(player.getUniqueId(), leaderName, name, tag, now, config);
        switch (joined.result()) {
            case OK -> {
            }
            case NAME_TAKEN -> {
                tell(player, "<red>已經有公會叫「" + ItemRenderer.escape(name) + "」了");
                return;
            }
            case TAG_TAKEN -> {
                tell(player, "<red>標籤 [" + ItemRenderer.escape(tag) + "] 已經有公會用了");
                return;
            }
            default -> {
                tell(player, "<red>沒辦法建立公會（" + joined.result() + "）");
                return;
            }
        }
        final Guild guild = joined.guild();
        if (!plugin.gold().take(player, cost, EconomySources.GUILD)) {
            roster.remove(guild.id());
            tell(player, "<red>金幣不夠");
            return;
        }
        final GuildRepository.GuildRow row = GuildRepository.GuildRow.of(guild);
        final long id = guild.id();
        clearApplications(player.getUniqueId());
        plugin.players().markDirty(player);
        // 2026-10-18：卡住重試時用當下的公會資料（期間有人加入、改公告都在），不重放建立那一刻的；解散了就不寫公會的部分
        final java.util.function.Function<GuildRepository.GuildRow, tw.exilecore.character.CharacterRepository.ExtraWork> work =
                current -> current == null ? null : connection -> {
                    repository.save(connection, current);
                    repository.insertLog(connection, id, now, leaderName, "建立", "建立公會（" + GoldService.format(cost) + " 金幣）");
                };
        plugin.characters().commit(List.of(player), work.apply(row), () -> work.apply(currentRow(id)),
                null, "建立公會 " + name + "（" + player.getName() + "）");
        refresh(player);
        refreshLadder();
        tell(player, "<green>公會「" + ItemRenderer.escape(name) + "」建立了<gray>（花了 " + GoldService.format(cost) + " 金幣）　"
                + "<white>/guild invite 名字<gray> 邀請人，<white>/g 訊息<gray> 公會頻道");
        player.playSound(player.getLocation(), Sound.UI_TOAST_CHALLENGE_COMPLETE, 0.7f, 1.2f);
        plugin.getServer().broadcast(plugin.text().prefixed("<white>" + ItemRenderer.escape(leaderName) + "<gray> 建立了公會 " + COLOR + "["
                + ItemRenderer.escape(tag) + "] " + ItemRenderer.escape(name)));
        plugin.getSLF4JLogger().info("[公會] {}（{}）建立了公會 [{}] {}（#{}）", leaderName, player.getName(), tag, name, id);
    }

    // ---- 邀請、加入 ----

    public void invite(final Player from, final String targetName) {
        final Guild guild = roster.guildOf(from.getUniqueId());
        if (guild == null) {
            tell(from, "<gray>你不在公會裡");
            return;
        }
        final Player target = findOnline(from, targetName);
        if (target == null) {
            return;
        }
        final GuildRoster.Result result = roster.invite(from.getUniqueId(), target.getUniqueId(), System.currentTimeMillis(), config);
        switch (result) {
            case OK -> {
                tell(from, "<gray>邀請了 <white>" + shown(target) + "<gray> 加入公會（" + config.inviteSeconds() + " 秒內有效）");
                final String name = guild.name(); // 只有文字與數字，放進指令、MiniMessage 都安全
                tell(target, "<white>" + shown(from) + "<gray> 邀請你加入公會 " + COLOR + "[" + ItemRenderer.escape(guild.tag()) + "] "
                        + ItemRenderer.escape(name) + COLOR_END + "<gray>（Lv." + guild.level() + "，" + guild.size() + " 人）　"
                        + "<click:run_command:'/guild accept " + name + "'><hover:show_text:'<gray>加入 " + name + "'><green><b>[接受]</b></green></hover></click>"
                        + "　<click:run_command:'/guild decline " + name + "'><hover:show_text:'<gray>拒絕'><red>[拒絕]</red></hover></click>");
                target.playSound(target.getLocation(), Sound.BLOCK_NOTE_BLOCK_PLING, 0.6f, 1.4f);
            }
            case SELF -> tell(from, "<red>不能邀請自己");
            case NO_PERMISSION -> tell(from, "<red>只有會長與幹部能邀請人");
            case TARGET_IN_GUILD -> tell(from, "<red>" + shown(target) + " 已經在公會裡了");
            case FULL -> tell(from, "<red>公會滿了（最多 " + config.maxMembers() + " 人）");
            default -> tell(from, "<red>沒辦法邀請（" + result + "）");
        }
    }

    /** 接受邀請（guildName 是 null = 最新的一筆）。 */
    public void accept(final Player player, final String guildName) {
        final long now = System.currentTimeMillis();
        Long guildId = null;
        if (guildName != null) {
            guildId = invitedGuild(player, guildName, now);
            if (guildId == null) {
                tell(player, "<red>公會「" + ItemRenderer.escape(guildName.strip()) + "」沒有邀請你（或邀請過期了）");
                return;
            }
        }
        final GuildRoster.Joined joined = roster.accept(player.getUniqueId(), plugin.displayName(player), guildId, now, config);
        switch (joined.result()) {
            case OK -> {
                final Guild guild = joined.guild();
                persist(guild);
                clearApplications(player.getUniqueId());
                log(guild.id(), plugin.displayName(player), "加入", "加入公會");
                refresh(player);
                refreshLadder();
                for (final Player member : online(guild)) {
                    if (member.equals(player)) {
                        tell(member, "<green>加入了公會 " + label(guild) + "<gray>（" + guild.size() + " 人）　<white>/g 訊息<gray> 公會頻道，<white>/guild<gray> 公會選單");
                        if (!guild.motd().isEmpty()) {
                            member.sendMessage(plugin.text().parse(COLOR + "[公會公告] " + COLOR_END + "<white>" + ItemRenderer.escape(guild.motd())));
                        }
                    } else {
                        member.sendMessage(channel("<white>" + shown(player) + "<gray> 加入了公會（" + guild.size() + " 人）"));
                    }
                }
            }
            case ALREADY_IN_GUILD -> tell(player, "<red>你已經在公會裡了，先 /guild leave");
            case FULL -> tell(player, "<red>那個公會滿了");
            case NO_INVITE -> tell(player, "<red>沒有可以接受的邀請（可能過期了）");
            default -> tell(player, "<red>沒辦法加入（" + joined.result() + "）");
        }
    }

    public void decline(final Player player, final String guildName) {
        final long now = System.currentTimeMillis();
        Long guildId = null;
        if (guildName != null) {
            guildId = invitedGuild(player, guildName, now);
            if (guildId == null) {
                tell(player, "<gray>公會「" + ItemRenderer.escape(guildName.strip()) + "」的邀請已經過期了");
                return;
            }
        }
        final List<GuildRoster.Invite> before = roster.invitesFor(player.getUniqueId(), now);
        if (roster.decline(player.getUniqueId(), guildId) != GuildRoster.Result.OK || before.isEmpty()) {
            tell(player, "<gray>沒有要拒絕的邀請");
            return;
        }
        tell(player, "<gray>拒絕了公會邀請");
        for (final GuildRoster.Invite invite : before) {
            if (guildId == null || invite.guildId() == guildId) {
                final Player inviter = plugin.getServer().getPlayer(invite.inviter());
                if (inviter != null) {
                    tell(inviter, "<gray>" + shown(player) + " 拒絕了公會邀請");
                }
            }
        }
    }

    private Long invitedGuild(final Player player, final String guildName, final long now) {
        final Guild wanted = roster.byName(guildName);
        if (wanted == null) {
            return null;
        }
        for (final GuildRoster.Invite invite : roster.invitesFor(player.getUniqueId(), now)) {
            if (invite.guildId() == wanted.id()) {
                return wanted.id();
            }
        }
        return null;
    }

    // ---- 入會申請（2026-10） ----

    private static final String REVIEW_BUTTON = "<click:run_command:'/guild applications'><hover:show_text:'<gray>打開申請名單'>"
            + "<green><b>[查看申請]</b></green></hover></click>";

    /** 申請加入（沒有公會的人；guildName 是名稱或標籤）。同一個公會再申請一次 = 更新留言。 */
    public void apply(final Player player, final String guildName, final String messageInput) {
        if (roster.guildOf(player.getUniqueId()) != null) {
            tell(player, "<red>你已經在公會裡了，先 /guild leave");
            return;
        }
        final Guild guild = roster.byName(guildName);
        if (guild == null) {
            tell(player, "<red>找不到公會「" + ItemRenderer.escape(guildName == null ? "" : guildName.strip()) + "」<gray>（/guild top 看所有公會）");
            return;
        }
        if (guild.size() >= config.maxMembers()) {
            tell(player, "<red>" + label(guild) + "<red> 滿了（" + config.maxMembers() + " 人）");
            return;
        }
        String message = messageInput == null ? "" : messageInput.strip().replaceAll("\\s+", " ");
        if (message.codePointCount(0, message.length()) > APPLY_MESSAGE_MAX) {
            message = message.substring(0, message.offsetByCodePoints(0, APPLY_MESSAGE_MAX));
        }
        final UUID uuid = player.getUniqueId();
        final java.util.LinkedHashMap<UUID, Application> pending = applications.computeIfAbsent(guild.id(), key -> new java.util.LinkedHashMap<>());
        final boolean again = pending.containsKey(uuid);
        if (!again && myApplications(uuid).size() >= APPLY_MAX) {
            tell(player, "<red>最多同時申請 " + APPLY_MAX + " 個公會<gray>（/guild unapply 公會 撤回一個）");
            return;
        }
        final PlayerState state = plugin.players().get(player);
        final int level = state == null ? 0 : state.level();
        final Application application = new Application(guild.id(), uuid, plugin.displayName(player), level, message, System.currentTimeMillis());
        pending.remove(uuid); // 重新申請：排到最後
        pending.put(uuid, application);
        final GuildRepository.ApplicationRow row = new GuildRepository.ApplicationRow(application.guildId(), uuid, application.name(),
                level, message, application.createdAt());
        plugin.database().async(connection -> {
            if (!plugin.characters().writesPaused()) {
                repository.saveApplication(connection, row);
            }
            return null;
        }).whenComplete((ignored, error) -> {
            if (error != null) {
                plugin.getSLF4JLogger().warn("入會申請存檔失敗（{} → {}）", application.name(), guild.name(), error);
            }
        });
        tell(player, (again ? "<green>更新了給 " : "<green>送出了給 ") + label(guild) + "<green> 的入會申請"
                + "<gray>（會長、幹部審核；" + APPLY_MILLIS / 86_400_000L + " 天內沒處理會自動取消）");
        if (!again) {
            for (final Player officer : online(guild)) {
                if (guild.rank(officer.getUniqueId()).atLeast(GuildRank.OFFICER)) {
                    officer.sendMessage(channel("<white>" + shown(player) + "<gray>（Lv." + level + "）申請加入公會"
                            + (message.isEmpty() ? "" : "：<white>" + ItemRenderer.escape(message)) + "　" + REVIEW_BUTTON));
                    officer.playSound(officer.getLocation(), Sound.BLOCK_NOTE_BLOCK_PLING, 0.6f, 1.4f);
                }
            }
        }
    }

    /** 撤回申請（guildName 是 null = 全部）。 */
    public void withdraw(final Player player, final String guildName) {
        final UUID uuid = player.getUniqueId();
        if (guildName == null) {
            final List<Application> mine = myApplications(uuid);
            if (mine.isEmpty()) {
                tell(player, "<gray>你沒有送出的入會申請");
                return;
            }
            clearApplications(uuid);
            tell(player, "<gray>撤回了 " + mine.size() + " 筆入會申請");
            return;
        }
        final Guild guild = roster.byName(guildName);
        if (guild == null || removeApplication(guild.id(), uuid) == null) {
            tell(player, "<gray>你沒有申請「" + ItemRenderer.escape(guildName.strip()) + "」");
            return;
        }
        tell(player, "<gray>撤回了給 " + label(guild) + "<gray> 的入會申請");
    }

    /** 批准（會長、幹部）：applicant 是申請時的角色名稱。申請的人不在線也可以。 */
    public void approve(final Player actor, final String applicant) {
        final Guild guild = reviewGuild(actor);
        final Application application = guild == null ? null : findApplication(actor, guild, applicant);
        if (application == null) {
            return;
        }
        approve(actor, guild, application);
    }

    /** 2026-10-18：選單用（直接用 UUID，名字重複時不會批准到別人）。 */
    public void approve(final Player actor, final UUID applicant) {
        final Guild guild = reviewGuild(actor);
        final Application application = guild == null ? null : findApplication(actor, guild, applicant);
        if (application == null) {
            return;
        }
        approve(actor, guild, application);
    }

    private void approve(final Player actor, final Guild guild, final Application application) {
        final GuildRoster.Joined joined = roster.adminAdd(guild.id(), application.uuid(), application.name(), System.currentTimeMillis(), config);
        switch (joined.result()) {
            case OK -> {
                clearApplications(application.uuid());
                persist(guild);
                refreshLadder();
                log(guild.id(), plugin.displayName(actor), "加入", "批准 " + application.name() + " 的入會申請");
                final Player target = plugin.getServer().getPlayer(application.uuid());
                if (target != null) {
                    refresh(target);
                    tell(target, "<green>你加入了公會 " + label(guild) + "<gray>（" + shown(actor) + " 批准了你的申請）　"
                            + "<white>/g 訊息<gray> 公會頻道，<white>/guild<gray> 公會選單");
                    target.playSound(target.getLocation(), Sound.UI_TOAST_CHALLENGE_COMPLETE, 0.7f, 1.2f);
                    if (!guild.motd().isEmpty()) {
                        target.sendMessage(plugin.text().parse(COLOR + "[公會公告] " + COLOR_END + "<white>" + ItemRenderer.escape(guild.motd())));
                    }
                }
                for (final Player member : online(guild)) {
                    if (!member.getUniqueId().equals(application.uuid())) {
                        member.sendMessage(channel("<white>" + ItemRenderer.escape(application.name()) + "<gray> 加入了公會（" + shown(actor)
                                + " 批准；" + guild.size() + " 人）"));
                    }
                }
            }
            case TARGET_IN_GUILD -> {
                clearApplications(application.uuid());
                tell(actor, "<gray>" + ItemRenderer.escape(application.name()) + " 已經加入別的公會了，申請拿掉了");
            }
            case FULL -> tell(actor, "<red>公會滿了（最多 " + config.maxMembers() + " 人）");
            default -> tell(actor, "<red>沒辦法加入（" + joined.result() + "）");
        }
    }

    /** 拒絕（會長、幹部）。 */
    public void reject(final Player actor, final String applicant) {
        final Guild guild = reviewGuild(actor);
        final Application application = guild == null ? null : findApplication(actor, guild, applicant);
        if (application == null) {
            return;
        }
        reject(actor, guild, application);
    }

    /** 2026-10-18：選單用（直接用 UUID，名字重複時不會拒絕到別人）。 */
    public void reject(final Player actor, final UUID applicant) {
        final Guild guild = reviewGuild(actor);
        final Application application = guild == null ? null : findApplication(actor, guild, applicant);
        if (application == null) {
            return;
        }
        reject(actor, guild, application);
    }

    private void reject(final Player actor, final Guild guild, final Application application) {
        removeApplication(guild.id(), application.uuid());
        tell(actor, "<gray>拒絕了 <white>" + ItemRenderer.escape(application.name()) + "<gray> 的入會申請");
        final Player target = plugin.getServer().getPlayer(application.uuid());
        if (target != null) {
            tell(target, "<gray>公會 " + label(guild) + "<gray> 沒有通過你的入會申請");
        }
    }

    /** 打開公會選單的「申請」頁。 */
    public void openApplications(final Player player) {
        new GuildMenu(plugin, player, this, GuildMenu.Tab.APPLY, 0).open();
    }

    /** 這個公會還沒處理的申請（舊的在前）。 */
    public List<Application> applicationsOf(final Guild guild) {
        final java.util.LinkedHashMap<UUID, Application> pending = guild == null ? null : applications.get(guild.id());
        return pending == null ? List.of() : List.copyOf(pending.values());
    }

    /** 這個人送出、還沒處理的申請（舊的在前）。 */
    public List<Application> myApplications(final UUID uuid) {
        final List<Application> list = new ArrayList<>();
        for (final java.util.LinkedHashMap<UUID, Application> pending : applications.values()) {
            final Application application = pending.get(uuid);
            if (application != null) {
                list.add(application);
            }
        }
        list.sort(java.util.Comparator.comparingLong(Application::createdAt));
        return list;
    }

    /** 這個人有沒有申請這個公會。 */
    public boolean applied(final UUID uuid, final Guild guild) {
        final java.util.LinkedHashMap<UUID, Application> pending = applications.get(guild.id());
        return pending != null && pending.containsKey(uuid);
    }

    /** 審核的人的公會（不是會長、幹部回 null，已經說了）。 */
    private Guild reviewGuild(final Player actor) {
        final Guild guild = roster.guildOf(actor.getUniqueId());
        if (guild == null) {
            tell(actor, "<gray>你不在公會裡");
            return null;
        }
        if (!guild.rank(actor.getUniqueId()).atLeast(GuildRank.OFFICER)) {
            tell(actor, "<red>只有會長、副會長與幹部能審核入會申請");
            return null;
        }
        return guild;
    }

    private Application findApplication(final Player actor, final Guild guild, final String query) {
        final String wanted = query == null ? "" : query.strip();
        for (final Application application : applicationsOf(guild)) {
            final org.bukkit.OfflinePlayer account = plugin.getServer().getOfflinePlayer(application.uuid());
            if (application.name().equalsIgnoreCase(wanted) || wanted.equalsIgnoreCase(account.getName())) {
                return application;
            }
        }
        tell(actor, "<red>沒有「" + ItemRenderer.escape(wanted) + "」的入會申請<gray>（/guild applications 看名單）");
        return null;
    }

    /** 2026-10-18：用 UUID 找申請（選單用）。 */
    private Application findApplication(final Player actor, final Guild guild, final UUID uuid) {
        final java.util.LinkedHashMap<UUID, Application> pending = applications.get(guild.id());
        final Application application = pending == null ? null : pending.get(uuid);
        if (application == null) {
            tell(actor, "<red>這筆入會申請已經不在了<gray>（/guild applications 看名單）");
        }
        return application;
    }

    /** 申請的人的名字（指令補完）。 */
    public List<String> applicantNames(final Player player) {
        final List<String> names = new ArrayList<>();
        for (final Application application : applicationsOf(roster.guildOf(player.getUniqueId()))) {
            names.add(application.name());
        }
        return names;
    }

    /** 送出申請的公會名稱（指令補完）。 */
    public List<String> appliedGuildNames(final Player player) {
        final List<String> names = new ArrayList<>();
        for (final Application application : myApplications(player.getUniqueId())) {
            final Guild guild = roster.guild(application.guildId());
            if (guild != null) {
                names.add(guild.name());
            }
        }
        return names;
    }

    private Application removeApplication(final long guildId, final UUID uuid) {
        final java.util.LinkedHashMap<UUID, Application> pending = applications.get(guildId);
        final Application removed = pending == null ? null : pending.remove(uuid);
        if (pending != null && pending.isEmpty()) {
            applications.remove(guildId);
        }
        if (removed != null) {
            plugin.database().async(connection -> {
                if (!plugin.characters().writesPaused()) {
                    repository.deleteApplication(connection, guildId, uuid);
                }
                return null;
            });
        }
        return removed;
    }

    /** 這個人加入公會了（或撤回全部）：所有申請拿掉。 */
    private void clearApplications(final UUID uuid) {
        boolean any = false;
        final java.util.Iterator<java.util.LinkedHashMap<UUID, Application>> each = applications.values().iterator();
        while (each.hasNext()) {
            final java.util.LinkedHashMap<UUID, Application> pending = each.next();
            any |= pending.remove(uuid) != null;
            if (pending.isEmpty()) {
                each.remove();
            }
        }
        if (any) {
            plugin.database().async(connection -> {
                if (!plugin.characters().writesPaused()) {
                    repository.deleteApplications(connection, uuid);
                }
                return null;
            });
        }
    }

    /** 放太久的申請自動取消。 */
    private void expireApplications(final long now) {
        final List<Application> expired = new ArrayList<>();
        for (final java.util.LinkedHashMap<UUID, Application> pending : applications.values()) {
            for (final Application application : pending.values()) {
                if (application.createdAt() + APPLY_MILLIS <= now) {
                    expired.add(application);
                }
            }
        }
        for (final Application application : expired) {
            removeApplication(application.guildId(), application.uuid());
        }
    }

    // ---- 離開、請出、階級 ----

    public void leave(final Player player) {
        final Guild guild = roster.guildOf(player.getUniqueId());
        if (guild == null) {
            tell(player, "<gray>你不在公會裡");
            return;
        }
        if (guild.leader().equals(player.getUniqueId())) {
            if (guild.size() > 1) {
                tell(player, "<red>你是會長：先把會長交給別人（/guild leader 名字），或解散公會（/guild disband）");
            } else {
                disband(player, false); // 只剩自己：離開就是解散，一樣要確認、倉庫要是空的
            }
            return;
        }
        final String name = plugin.displayName(player);
        if (roster.leave(player.getUniqueId()) != GuildRoster.Result.OK) {
            tell(player, "<red>沒辦法離開公會");
            return;
        }
        departed(guild, player.getUniqueId());
        log(guild.id(), name, "離開", "離開公會");
        tell(player, "<gray>離開了公會 " + label(guild));
        broadcast(guild, "<white>" + ItemRenderer.escape(name) + "<gray> 離開了公會（剩 " + guild.size() + " 人）");
    }

    public void kick(final Player actor, final String targetName) {
        final Guild guild = roster.guildOf(actor.getUniqueId());
        if (guild == null) {
            tell(actor, "<gray>你不在公會裡");
            return;
        }
        final Guild.Member target = findMember(guild, targetName);
        if (target == null) {
            tell(actor, "<red>公會裡沒有「" + ItemRenderer.escape(targetName.strip()) + "」");
            return;
        }
        kick(actor, guild, target);
    }

    /** 2026-10-18：選單用（直接用 UUID，名字重複時不會請出別人）。 */
    public void kick(final Player actor, final UUID targetId) {
        final Guild guild = roster.guildOf(actor.getUniqueId());
        if (guild == null) {
            tell(actor, "<gray>你不在公會裡");
            return;
        }
        final Guild.Member target = guild.member(targetId);
        if (target == null) {
            tell(actor, "<red>這個人已經不在公會裡了");
            return;
        }
        kick(actor, guild, target);
    }

    private void kick(final Player actor, final Guild guild, final Guild.Member target) {
        final GuildRoster.Result result = roster.kick(actor.getUniqueId(), target.uuid());
        switch (result) {
            case OK -> {
                departed(guild, target.uuid());
                log(guild.id(), plugin.displayName(actor), "請出", "把 " + target.name() + " 請出公會");
                final Player kicked = plugin.getServer().getPlayer(target.uuid());
                if (kicked != null) {
                    tell(kicked, "<gray>你被 " + shown(actor) + " 請出了公會 " + label(guild));
                }
                broadcast(guild, "<white>" + ItemRenderer.escape(target.name()) + "<gray> 被 " + shown(actor) + " 請出了公會（剩 " + guild.size() + " 人）");
            }
            case SELF -> tell(actor, "<red>要離開公會用 /guild leave");
            case NO_PERMISSION -> tell(actor, "<red>你不能請出這個人（幹部只能請出成員，會長可以請出幹部與成員）");
            default -> tell(actor, "<red>沒辦法請出（" + result + "）");
        }
    }

    /** 升降階級：會長在成員、幹部、副會長之間；副會長在成員、幹部之間（2026-10）。 */
    public void setRank(final Player actor, final String targetName, final GuildRank rank) {
        final Guild guild = roster.guildOf(actor.getUniqueId());
        if (guild == null) {
            tell(actor, "<gray>你不在公會裡");
            return;
        }
        final Guild.Member target = findMember(guild, targetName);
        if (target == null) {
            tell(actor, "<red>公會裡沒有「" + ItemRenderer.escape(targetName.strip()) + "」");
            return;
        }
        setRank(actor, guild, target, rank);
    }

    /** 2026-10-18：選單用（直接用 UUID，名字重複時不會升降到別人）。 */
    public void setRank(final Player actor, final UUID targetId, final GuildRank rank) {
        final Guild guild = roster.guildOf(actor.getUniqueId());
        if (guild == null) {
            tell(actor, "<gray>你不在公會裡");
            return;
        }
        final Guild.Member target = guild.member(targetId);
        if (target == null) {
            tell(actor, "<red>這個人已經不在公會裡了");
            return;
        }
        setRank(actor, guild, target, rank);
    }

    private void setRank(final Player actor, final Guild guild, final Guild.Member target, final GuildRank rank) {
        final GuildRank before = target.rank();
        final GuildRoster.Result result = roster.setRank(actor.getUniqueId(), target.uuid(), rank);
        switch (result) {
            case OK -> {
                persist(guild);
                final boolean up = rank.power() > before.power();
                log(guild.id(), plugin.displayName(actor), up ? "升階" : "降階", target.name() + " → " + rank.displayName());
                broadcast(guild, "<white>" + ItemRenderer.escape(target.name()) + "<gray> " + (up ? "升為" : "降為") + " <white>" + rank.displayName());
                closeStashIfNotAllowed(guild, target.uuid());
            }
            case NO_PERMISSION -> tell(actor, "<red>沒有權限：會長可以升降成員、幹部、副會長；副會長只能把成員、幹部升降（不能動副會長）");
            case SELF -> tell(actor, "<red>不能改自己的階級（交出會長用 /guild leader 名字）");
            case ALREADY_RANK -> tell(actor, "<gray>" + ItemRenderer.escape(target.name()) + " 已經是" + rank.displayName() + "了");
            default -> tell(actor, "<red>沒辦法改階級（" + result + "）");
        }
    }

    /** 交出會長（原本的會長變成幹部）。 */
    public void transfer(final Player actor, final String targetName) {
        final Guild guild = roster.guildOf(actor.getUniqueId());
        if (guild == null) {
            tell(actor, "<gray>你不在公會裡");
            return;
        }
        final Guild.Member target = findMember(guild, targetName);
        if (target == null) {
            tell(actor, "<red>公會裡沒有「" + ItemRenderer.escape(targetName.strip()) + "」");
            return;
        }
        final GuildRoster.Result result = roster.transfer(actor.getUniqueId(), target.uuid());
        switch (result) {
            case OK -> {
                persist(guild);
                refreshLadder();
                log(guild.id(), plugin.displayName(actor), "會長", "把會長交給 " + target.name());
                broadcast(guild, "<#f5b942>★ <white>" + ItemRenderer.escape(target.name()) + "<gray> 成為會長（" + shown(actor) + " 轉為幹部）");
            }
            case NO_PERMISSION -> tell(actor, "<red>只有會長能交出會長");
            case SELF -> tell(actor, "<gray>你已經是會長了");
            default -> tell(actor, "<red>沒辦法交出會長（" + result + "）");
        }
    }

    /** 有人離開公會之後：存檔、名字與加成更新、關掉他開著的公會倉庫。 */
    private void departed(final Guild guild, final UUID who) {
        persist(guild);
        refreshLadder();
        final Player player = plugin.getServer().getPlayer(who);
        if (player != null) {
            closeStash(player);
            refresh(player);
        }
    }

    // ---- 解散 ----

    /** 解散（只有會長；倉庫要是空的；第一次只是問，confirmed 才真的解散）。 */
    public void disband(final Player actor, final boolean confirmed) {
        final Guild guild = roster.guildOf(actor.getUniqueId());
        if (guild == null) {
            tell(actor, "<gray>你不在公會裡");
            return;
        }
        if (!guild.leader().equals(actor.getUniqueId())) {
            tell(actor, "<red>只有會長能解散公會");
            return;
        }
        final long id = guild.id();
        withStash(guild, actor, stash -> {
            final Guild current = roster.guild(id);
            if (current == null || !current.leader().equals(actor.getUniqueId()) || !actor.isOnline()) {
                return;
            }
            if (!stash.isEmpty()) {
                tell(actor, "<red>公會倉庫裡還有 " + stash.itemCount() + " 件東西：先拿出來才能解散（/guild stash）");
                return;
            }
            final long now = System.currentTimeMillis();
            final Long until = disbandConfirm.get(actor.getUniqueId());
            if (!confirmed || until == null || until < now) {
                disbandConfirm.put(actor.getUniqueId(), now + CONFIRM_SECONDS * 1000L);
                tell(actor, "<red>解散公會 " + label(current) + "<red>？<gray>等級、經驗、紀錄全部消失，救不回來。"
                        + (current.boughtPages() > 0 ? "<red>用星幣買的 " + current.boughtPages() + " 頁倉庫也會一起消失，不會退。" : "") + "　"
                        + "<click:run_command:'/guild disband confirm'><hover:show_text:'<red>真的解散'><red><b>[確認解散]</b></red></hover></click>"
                        + "<gray>（" + CONFIRM_SECONDS + " 秒內有效）");
                return;
            }
            disbandConfirm.remove(actor.getUniqueId());
            final String who = plugin.displayName(actor);
            final List<Player> members = online(current);
            if (roster.disband(actor.getUniqueId()) != GuildRoster.Result.OK) {
                tell(actor, "<red>沒辦法解散");
                return;
            }
            removed(current, members);
            for (final Player member : members) {
                tell(member, "<gray>公會 " + label(current) + "<gray> 被會長 " + ItemRenderer.escape(who) + " 解散了");
            }
            plugin.getSLF4JLogger().info("[公會] {}（{}）解散了公會 [{}] {}（#{}）", who, actor.getName(), current.tag(), current.name(), id);
        });
    }

    /** 已經在確認框按過「解散」了（公會選單）：直接解散。 */
    public void disbandNow(final Player actor) {
        disbandConfirm.put(actor.getUniqueId(), System.currentTimeMillis() + CONFIRM_SECONDS * 1000L);
        disband(actor, true);
    }

    /** 公會已經從名單拿掉：刪資料、清記憶體、原本的成員更新名字與加成。 */
    private void removed(final Guild guild, final List<Player> members) {
        final long id = guild.id();
        dirty.remove(id);
        applications.remove(id);
        stashDirty.remove(id);
        stashes.remove(id);
        stashWaiters.remove(id);
        plugin.database().async(connection -> {
            if (!plugin.characters().writesPaused()) {
                repository.delete(connection, id);
            }
            return null;
        }).whenComplete((ignored, error) -> {
            if (error != null) {
                plugin.getSLF4JLogger().error("刪除公會 {}（#{}）失敗：資料庫裡還留著，下次啟動會再出現", guild.name(), id, error);
            }
        });
        for (final Player member : members) {
            closeStash(member);
            refresh(member);
        }
        refreshLadder();
    }

    // ---- 公告、倉庫權限 ----

    /** 改公告（會長、幹部；空的 = 清掉）。 */
    public void motd(final Player actor, final String text) {
        final Guild guild = roster.guildOf(actor.getUniqueId());
        if (guild == null) {
            tell(actor, "<gray>你不在公會裡");
            return;
        }
        if (!guild.rank(actor.getUniqueId()).atLeast(GuildRank.OFFICER)) {
            tell(actor, "<red>只有會長與幹部能改公告");
            return;
        }
        String motd = text == null ? "" : text.strip().replace('\n', ' ');
        if (motd.codePointCount(0, motd.length()) > config.motdLength()) {
            tell(actor, "<red>公告最多 " + config.motdLength() + " 個字");
            return;
        }
        if (!motd.isEmpty()) {
            final tw.exilecore.character.NameFilter filter = plugin.characters().config().nameFilter();
            motd = filter.maskChat(motd);
            if (filter.disguisedInChat(motd)) {
                tell(actor, "<red>公告含有不雅、歧視或敏感的字，沒有改");
                return;
            }
        }
        guild.setMotd(motd);
        persist(guild);
        log(guild.id(), plugin.displayName(actor), "公告", motd.isEmpty() ? "清掉公告" : motd);
        broadcast(guild, motd.isEmpty() ? "<gray>" + shown(actor) + " 清掉了公告" : "<gray>" + shown(actor) + " 改了公告：<white>" + ItemRenderer.escape(motd));
    }

    /** 誰能從倉庫拿東西：成員 → 幹部 → 副會長 → 會長 → 成員…（會長、副會長能改）。 */
    public void cycleWithdrawRank(final Player actor) {
        final Guild guild = roster.guildOf(actor.getUniqueId());
        if (guild == null || !guild.rank(actor.getUniqueId()).atLeast(GuildRank.VICE)) {
            tell(actor, "<red>只有會長、副會長能改倉庫的權限");
            return;
        }
        final GuildRank next = switch (guild.withdrawRank()) {
            case MEMBER -> GuildRank.OFFICER;
            case OFFICER -> GuildRank.VICE;
            case VICE -> GuildRank.LEADER;
            case LEADER -> GuildRank.MEMBER;
        };
        guild.setWithdrawRank(next);
        persist(guild);
        log(guild.id(), plugin.displayName(actor), "設定", "倉庫改成" + withdrawText(next) + "才能拿");
        broadcast(guild, "<gray>公會倉庫現在 <white>" + withdrawText(next) + "<gray> 才能拿東西（放進去每個人都可以）");
    }

    static String withdrawText(final GuildRank rank) {
        return switch (rank) {
            case MEMBER -> "所有成員";
            case OFFICER -> "幹部以上";
            case VICE -> "副會長以上";
            case LEADER -> "只有會長";
        };
    }

    // ---- 公會經驗 ----

    /** 掉落有份的玩家殺了一隻怪（怪物模組呼叫）。 */
    public void onKill(final Player player, final MonsterData data) {
        final Guild guild = roster.guildOf(player.getUniqueId());
        if (guild == null) {
            return;
        }
        final int gap = config.killLevelGap();
        if (gap > 0 && plugin.players().get(player).level() - data.level() > gap) {
            return; // 比自己低太多的怪不算（擋回低等區刷）
        }
        addExperience(guild, player.getUniqueId(), config.killXp(data.rarity(), data.boss()));
    }

    /** 捐金幣換公會經驗。 */
    public void donate(final Player player, final long gold) {
        final Guild guild = roster.guildOf(player.getUniqueId());
        if (guild == null) {
            tell(player, "<gray>你不在公會裡");
            return;
        }
        final String problem = plugin.characters().transactionProblem(player);
        if (problem != null) {
            tell(player, "<red>" + problem);
            return;
        }
        if (gold < config.donateMin()) {
            tell(player, "<red>一次最少捐 " + GoldService.format(config.donateMin()) + " 金幣");
            return;
        }
        final long experience = config.donateXp(gold);
        if (experience <= 0) {
            tell(player, "<red>現在不能用金幣捐獻");
            return;
        }
        if (!plugin.gold().take(player, gold, EconomySources.GUILD)) {
            tell(player, "<red>金幣不夠（你有 " + GoldService.format(plugin.gold().balance(player)) + "）");
            return;
        }
        final String name = plugin.displayName(player);
        final boolean maxed = guild.level() >= config.maxLevel();
        addExperience(guild, player.getUniqueId(), experience);
        dirty.remove(guild.id());
        final GuildRepository.GuildRow row = GuildRepository.GuildRow.of(guild);
        final long id = guild.id();
        final long now = System.currentTimeMillis();
        plugin.players().markDirty(player);
        // 2026-10-18：卡住重試時用當下的公會資料（不把成員、等級、經驗蓋回捐獻那一刻的）；解散了就不寫公會的部分
        final java.util.function.Function<GuildRepository.GuildRow, tw.exilecore.character.CharacterRepository.ExtraWork> work =
                current -> current == null ? null : connection -> {
                    repository.save(connection, current);
                    repository.insertLog(connection, id, now, name, "捐獻", GoldService.format(gold) + " 金幣 → " + GoldService.format(experience) + " 公會經驗");
                };
        plugin.characters().commit(List.of(player), work.apply(row), () -> work.apply(currentRow(id)),
                null, "公會捐獻（" + player.getName() + "）");
        broadcast(guild, "<white>" + ItemRenderer.escape(name) + "<gray> 捐了 <#f5b942>" + GoldService.format(gold) + " 金幣<gray>（公會經驗 +"
                + GoldService.format(experience) + (maxed ? "；公會已經滿級，只算進排行" : "") + "）");
    }

    private void addExperience(final Guild guild, final UUID contributor, final long amount) {
        if (amount <= 0) {
            return;
        }
        final int before = guild.level();
        final int gained = guild.addExperience(amount, config);
        guild.contribute(contributor, amount);
        dirty.add(guild.id());
        if (gained > 0) {
            levelUp(guild, before);
        }
    }

    private void levelUp(final Guild guild, final int before) {
        final int level = guild.level();
        persist(guild);
        refreshLadder();
        log(guild.id(), "公會", "升級", "升到 " + level + " 級");
        final List<String> lines = new ArrayList<>();
        for (final GuildConfig.Bonus bonus : config.bonuses()) {
            if (bonus.level() > before && bonus.level() <= level) {
                for (final String line : StatLines.describeAll(bonus.stats())) {
                    lines.add("<gray>　新的公會加成：<white>" + line);
                }
            }
        }
        final int pages = stashPages(guild);
        if (config.stashPages(level) > config.stashPages(before)) {
            lines.add("<gray>　公會倉庫變成 <white>" + pages + " 頁");
        }
        final GuildStash stash = stashes.get(guild.id());
        if (stash != null) {
            stash.ensurePages(pages);
            redrawStash(guild.id());
        }
        for (final Player member : online(guild)) {
            member.sendMessage(channel("<#f5b942><b>公會升到 " + level + " 級！</b>"));
            for (final String line : lines) {
                member.sendMessage(plugin.text().parse(line));
            }
            member.playSound(member.getLocation(), Sound.UI_TOAST_CHALLENGE_COMPLETE, 0.7f, 1.0f);
            plugin.players().markDirty(member);
        }
        plugin.getSLF4JLogger().info("[公會] [{}] {} 升到 {} 級", guild.tag(), guild.name(), level);
    }

    // ---- 公會頻道 ----

    /** 公會頻道（/g 訊息）。 */
    public void chat(final Player player, final String message) {
        final Guild guild = roster.guildOf(player.getUniqueId());
        if (guild == null) {
            tell(player, "<gray>你不在公會裡（/guild 看怎麼建立、加入）");
            return;
        }
        String text = message == null ? "" : message.strip();
        if (text.isEmpty()) {
            return;
        }
        if (plugin.chat() != null) {
            final tw.exilecore.chat.ChatModeration.Verdict verdict = plugin.chat().moderation().check(player, text);
            if (!verdict.allowed()) {
                tell(player, "<red>" + ItemRenderer.escape(verdict.reason()));
                return;
            }
            text = verdict.text();
        }
        // 訊息裡的 [i] 換成手上的物品（聊天模組）
        final Component body = plugin.chat() == null ? Component.text(text) : plugin.chat().message(player, text);
        final GuildRank rank = guild.rank(player.getUniqueId());
        final String mark = rank == GuildRank.LEADER ? "<#f5b942>★</#f5b942>" : rank == GuildRank.VICE ? "<#c9a0ff>✦</#c9a0ff>"
                : rank == GuildRank.OFFICER ? "<#7fd4ff>◆</#7fd4ff>" : "";
        final Component line = Component.text()
                .append(plugin.text().parse(COLOR + "[公會] " + COLOR_END + mark + "<white>" + shown(player) + "<gray>："))
                .append(body.colorIfAbsent(NamedTextColor.GRAY))
                .build();
        for (final Player member : online(guild)) {
            member.sendMessage(line);
        }
        plugin.getSLF4JLogger().info("[公會 {}] {}：{}", guild.tag(), plugin.displayName(player), text);
    }

    // ---- 資訊、排行 ----

    /** 公會的資料（guildName 是 null = 自己的公會）。 */
    public void info(final CommandSender viewer, final String guildName) {
        final Guild guild = guildName == null
                ? viewer instanceof Player player ? roster.guildOf(player.getUniqueId()) : null
                : roster.byName(guildName);
        if (guild == null) {
            viewer.sendMessage(plugin.text().prefixed(guildName == null ? "<gray>你不在公會裡（/guild info 公會名稱 看別的公會）"
                    : "<red>沒有公會叫「" + ItemRenderer.escape(guildName.strip()) + "」"));
            return;
        }
        for (final String line : describe(guild)) {
            viewer.sendMessage(plugin.text().parse(line));
        }
    }

    /** 公會的說明（選單、/guild info 共用）。 */
    public List<String> describe(final Guild guild) {
        final List<String> lines = new ArrayList<>();
        final Guild.Member leader = guild.member(guild.leader());
        lines.add(COLOR + "<b>[" + ItemRenderer.escape(guild.tag()) + "] " + ItemRenderer.escape(guild.name()) + "</b>" + COLOR_END
                + " <#f5b942>Lv." + guild.level());
        lines.add("<gray>公會經驗：" + experienceText(guild));
        lines.add("<gray>成員：<white>" + guild.size() + " / " + config.maxMembers() + "<gray>（在線 " + online(guild).size() + "）　會長：<white>"
                + ItemRenderer.escape(leader == null ? "？" : leader.name()));
        lines.add("<gray>成立：<white>" + date(guild.createdAt()) + "<gray>　倉庫：<white>" + stashPages(guild) + " 頁");
        final List<Modifier> bonus = config.modifiers(guild.level());
        if (!bonus.isEmpty()) {
            lines.add("<gray>公會加成：<white>" + String.join("<gray>、<white>", StatLines.describeAll(bonus)));
        }
        if (!guild.motd().isEmpty()) {
            lines.add("<gray>公告：<white>" + ItemRenderer.escape(guild.motd()));
        }
        return lines;
    }

    /** 「12,345 / 20,000（61.7%）」；滿級寫總經驗。 */
    public String experienceText(final Guild guild) {
        final long need = config.xpToNext(guild.level());
        if (need <= 0) {
            return "<white>滿級<gray>（累積 " + GoldService.format(guild.totalExperience(config)) + "）";
        }
        return String.format(Locale.ROOT, "<white>%,d / %,d<gray>（%.1f%%）", guild.experience(), need, guild.experience() * 100.0 / need);
    }

    /** 聊天欄的公會排行。 */
    public void top(final CommandSender viewer) {
        final List<Guild> list = roster.top(config);
        final Guild mine = viewer instanceof Player player ? roster.guildOf(player.getUniqueId()) : null;
        viewer.sendMessage(plugin.text().parse("<#f5b942><bold>公會排行</bold> <dark_gray>等級高、經驗多的在前"));
        if (list.isEmpty()) {
            viewer.sendMessage(plugin.text().parse("<gray>  還沒有公會（/guild create 標籤 名稱）"));
        }
        for (int i = 0; i < list.size(); i++) {
            final Guild guild = list.get(i);
            if (i >= 10 && guild != mine) {
                continue;
            }
            final int rank = i + 1;
            final String medal = rank == 1 ? "<#f5b942>①" : rank == 2 ? "<#c0c0c0>②" : rank == 3 ? "<#cd7f32>③" : "<gray>" + rank + ".";
            final Guild.Member leader = guild.member(guild.leader());
            viewer.sendMessage(plugin.text().parse("  " + medal + " " + (guild == mine ? "<green>➜ " : "") + label(guild)
                    + " <#f5b942>Lv." + guild.level() + " <dark_gray>" + guild.size() + " 人　會長 " + ItemRenderer.escape(leader == null ? "？" : leader.name())
                    + (guild == mine ? " <green>（你的公會）" : "")));
        }
        viewer.sendMessage(plugin.text().parse("<dark_gray>  /guild info 公會名稱 看詳細；網頁版在排行榜的「公會」"));
    }

    static String date(final long millis) {
        return new SimpleDateFormat("yyyy-MM-dd", Locale.ROOT).format(new Date(millis));
    }

    static String dateTime(final long millis) {
        return new SimpleDateFormat("MM-dd HH:mm", Locale.ROOT).format(new Date(millis));
    }

    // ---- 上線、下線 ----

    /** 上線幾秒後：公告與手上的邀請。 */
    void joined(final Player player) {
        plugin.getServer().getScheduler().runTaskLater(plugin, () -> {
            if (!player.isOnline()) {
                return;
            }
            final Guild guild = roster.guildOf(player.getUniqueId());
            if (guild != null && !guild.motd().isEmpty()) {
                player.sendMessage(plugin.text().parse(COLOR + "[公會公告] " + COLOR_END + "<white>" + ItemRenderer.escape(guild.motd())));
            }
            if (guild != null && guild.rank(player.getUniqueId()).atLeast(GuildRank.OFFICER)) {
                final int pending = applicationsOf(guild).size();
                if (pending > 0) {
                    player.sendMessage(channel("<#f5b942>有 " + pending + " 筆入會申請等你審核　" + REVIEW_BUTTON));
                }
            }
        }, 80L);
    }

    void quit(final Player player) {
        openStashes.remove(player.getUniqueId());
        disbandConfirm.remove(player.getUniqueId());
    }

    // ---- 選單 ----

    public void openMenu(final Player player) {
        new GuildMenu(plugin, player, this, GuildMenu.Tab.OVERVIEW, 0).open();
    }

    /** 最近的紀錄（背景讀，讀好在主執行緒回來；公會不在了回空的）。 */
    void loadLog(final long guildId, final Consumer<List<GuildRepository.LogRow>> callback) {
        plugin.database().async(connection -> repository.loadLog(connection, guildId, LOG_SHOWN)).whenComplete((rows, error) -> sync(() -> {
            if (error != null) {
                plugin.getSLF4JLogger().warn("公會紀錄讀取失敗", error);
            }
            callback.accept(rows == null ? List.of() : rows);
        }));
    }

    // ---- 公會倉庫 ----

    /** 能不能在這裡開公會倉庫：營地（跟個人倉庫一樣），或管理員。 */
    private boolean canUseStashHere(final Player player) {
        return player.hasPermission(ExileCore.PERMISSION_ADMIN) || plugin.zones() != null && plugin.zones().inTown(player.getLocation());
    }

    public void openStash(final Player player, final int page) {
        final Guild guild = roster.guildOf(player.getUniqueId());
        if (guild == null) {
            tell(player, "<gray>你不在公會裡");
            return;
        }
        final String problem = plugin.characters().transactionProblem(player);
        if (problem != null) {
            tell(player, "<red>" + problem);
            return;
        }
        if (!canUseStashHere(player)) {
            tell(player, "<red>公會倉庫只能在營地打開");
            return;
        }
        final long id = guild.id();
        withStash(guild, player, stash -> {
            if (player.isOnline() && roster.guildOf(player.getUniqueId()) == roster.guild(id) && roster.guild(id) != null) {
                new GuildStashMenu(plugin, player, this, id, Math.max(0, Math.min(page, stash.pageCount() - 1))).open();
            }
        });
    }

    /** 讀好的公會倉庫；還沒讀回傳 null。 */
    GuildStash stash(final long guildId) {
        return stashes.get(guildId);
    }

    /** 這個公會現在能用幾頁（後面多出來的頁只能拿）。 */
    int usablePages(final Guild guild) {
        return stashPages(guild);
    }

    /** 這個公會的倉庫有幾頁：等級給的＋用星幣買的。 */
    public int stashPages(final Guild guild) {
        return Math.min(GuildStash.MAX_PAGES, config.stashPages(guild.level()) + guild.boughtPages());
    }

    // ---- 贊助：公會倉庫頁、公會改名卡 ----

    /** 這個人的公會還能不能用星幣多買一頁（max：最多買幾頁，0 = 只看總頁數上限）；可以回傳 null。 */
    public String boughtPageProblem(final Player player, final int max) {
        final Guild guild = roster.guildOf(player.getUniqueId());
        if (guild == null) {
            return "你不在公會裡";
        }
        if (max > 0 && guild.boughtPages() >= max) {
            return "你的公會已經買到 " + max + " 頁了";
        }
        if (stashPages(guild) >= GuildStash.MAX_PAGES) {
            return "公會倉庫已經到 " + GuildStash.MAX_PAGES + " 頁的上限了";
        }
        return null;
    }

    /** 贊助買了一頁（星幣已經扣了）：頁數加到公會上、存檔、告訴大家。 */
    public void addBoughtPage(final Player buyer) {
        final Guild guild = roster.guildOf(buyer.getUniqueId());
        if (guild == null) {
            return;
        }
        guild.setBoughtPages(guild.boughtPages() + 1);
        persist(guild);
        final GuildStash stash = stashes.get(guild.id());
        if (stash != null) {
            stash.ensurePages(stashPages(guild));
            redrawStash(guild.id());
        }
        log(guild.id(), plugin.displayName(buyer), "設定", "用星幣買了一頁公會倉庫（現在 " + stashPages(guild) + " 頁）");
        broadcast(guild, "<white>" + shown(buyer) + "<gray> 用星幣幫公會買了一頁倉庫（現在 <white>" + stashPages(guild) + "<gray> 頁）");
        plugin.getSLF4JLogger().info("[公會] {}（{}）幫 [{}] {} 買了一頁倉庫（買的共 {} 頁）", plugin.displayName(buyer), buyer.getName(),
                guild.tag(), guild.name(), guild.boughtPages());
    }

    /**
     * 改公會的名稱與標籤（只有會長；要用一張公會改名卡，管理員不用）。規則跟建立時一樣：長度、只能文字與數字、不能撞名、不能有不雅的字。
     */
    public void rename(final Player actor, final String tagInput, final String nameInput) {
        final Guild guild = roster.guildOf(actor.getUniqueId());
        if (guild == null) {
            tell(actor, "<gray>你不在公會裡");
            return;
        }
        if (!guild.leader().equals(actor.getUniqueId())) {
            tell(actor, "<red>只有會長能改公會的名稱");
            return;
        }
        final String tag = tagInput == null ? "" : tagInput.strip();
        final String name = nameInput == null ? "" : nameInput.strip();
        final tw.exilecore.sponsor.SponsorService sponsors = plugin.sponsors();
        final boolean needsCard = sponsors != null && !actor.hasPermission(ExileCore.PERMISSION_ADMIN);
        if (needsCard && sponsors.guildRenameCards(actor) <= 0) {
            tell(actor, "<red>改公會名稱要用一張公會改名卡（/sponsor 的星幣商城購買）");
            return;
        }
        for (final String text : List.of(name, tag)) {
            if (GuildRoster.textProblem(text, 1, 64) == null) {
                final String blocked = plugin.characters().config().nameFilter().problem(text);
                if (blocked != null) {
                    tell(actor, "<red>「" + ItemRenderer.escape(text) + "」不能用：" + ItemRenderer.escape(blocked));
                    return;
                }
            }
        }
        final String oldName = guild.name();
        final String oldTag = guild.tag();
        final GuildRoster.Result result = roster.rename(actor.getUniqueId(), name, tag, config);
        switch (result) {
            case OK -> {
            }
            case BAD_NAME -> {
                tell(actor, "<red>公會名稱" + GuildRoster.textProblem(name, config.nameMin(), config.nameMax()));
                return;
            }
            case BAD_TAG -> {
                tell(actor, "<red>公會標籤" + GuildRoster.textProblem(tag, config.tagMin(), config.tagMax()));
                return;
            }
            case NAME_TAKEN -> {
                tell(actor, "<red>已經有公會叫「" + ItemRenderer.escape(name) + "」了");
                return;
            }
            case TAG_TAKEN -> {
                tell(actor, "<red>標籤 [" + ItemRenderer.escape(tag) + "] 已經有公會用了");
                return;
            }
            case SAME_NAME -> {
                tell(actor, "<gray>名稱與標籤都跟現在一樣，沒有改（改名卡沒有用掉）");
                return;
            }
            default -> {
                tell(actor, "<red>沒辦法改名（" + result + "）");
                return;
            }
        }
        if (needsCard && !sponsors.useGuildRenameCard(actor)) {
            guild.retitle(oldName, oldTag); // 卡扣不到（帳號資料還沒載入）：改回去
            tell(actor, "<red>公會改名卡扣不到（帳號資料載入中？），沒有改名，請稍後再試");
            return;
        }
        persist(guild);
        refreshLadder();
        log(guild.id(), plugin.displayName(actor), "設定", "公會改名：[" + oldTag + "] " + oldName + " → [" + tag + "] " + name);
        for (final Player member : online(guild)) {
            plugin.players().updateListName(member); // 名字旁邊的標籤換新
        }
        broadcast(guild, "<gray>公會改名了：<white>[" + ItemRenderer.escape(oldTag) + "] " + ItemRenderer.escape(oldName) + "<gray> → " + label(guild)
                + (needsCard ? "<dark_gray>（用掉一張公會改名卡）" : ""));
        plugin.getSLF4JLogger().info("[公會] {}（{}）把公會 [{}] {} 改名成 [{}] {}（#{}）", plugin.displayName(actor), actor.getName(), oldTag, oldName,
                tag, name, guild.id());
    }

    /** 倉庫讀好了就做 action；還沒讀先去讀（背景），讀好再做。 */
    private void withStash(final Guild guild, final CommandSender asker, final Consumer<GuildStash> action) { // 2026-10-18：管理員（可能是主控台）解散也走這裡
        final long id = guild.id();
        final GuildStash loaded = stashes.get(id);
        if (loaded != null) {
            loaded.ensurePages(stashPages(guild));
            action.accept(loaded);
            return;
        }
        final List<Consumer<GuildStash>> waiting = stashWaiters.get(id);
        if (waiting != null) {
            waiting.add(action);
            return;
        }
        final List<Consumer<GuildStash>> waiters = new ArrayList<>();
        waiters.add(action);
        stashWaiters.put(id, waiters);
        plugin.database().async(connection -> repository.loadStash(connection, id)).whenComplete((pages, error) -> sync(() -> {
            final List<Consumer<GuildStash>> pending = stashWaiters.remove(id);
            final Guild current = roster.guild(id);
            if (pending == null || current == null) {
                return; // 這段時間公會解散了
            }
            if (error != null || pages == null) {
                plugin.getSLF4JLogger().error("公會倉庫（#{}）讀取失敗", id, error);
                if (!(asker instanceof Player online) || online.isOnline()) {
                    asker.sendMessage(plugin.text().prefixed("<red>公會倉庫讀取失敗，稍後再試（主控台有錯誤訊息）"));
                }
                return;
            }
            final GuildStash stash;
            try {
                stash = GuildStash.decode(pages, stashPages(current));
            } catch (final RuntimeException e) {
                plugin.getSLF4JLogger().error("公會倉庫（#{}）的內容讀不出來", id, e);
                if (!(asker instanceof Player online) || online.isOnline()) {
                    asker.sendMessage(plugin.text().prefixed("<red>公會倉庫的內容讀不出來（主控台有錯誤訊息），先不要動它"));
                }
                return;
            }
            stashes.put(id, stash);
            for (final Consumer<GuildStash> each : pending) {
                each.accept(stash);
            }
        }));
    }

    void stashOpened(final Player player, final GuildStashMenu menu) {
        openStashes.put(player.getUniqueId(), menu);
    }

    void stashClosed(final Player player, final GuildStashMenu menu) {
        openStashes.remove(player.getUniqueId(), menu);
    }

    private void closeStash(final Player player) {
        final GuildStashMenu menu = openStashes.remove(player.getUniqueId());
        if (menu != null && player.getOpenInventory().getTopInventory().getHolder() == menu) {
            player.closeInventory();
        }
    }

    /** 降階之後：倉庫還開著就重畫（能不能拿的說明變了）。 */
    private void closeStashIfNotAllowed(final Guild guild, final UUID who) {
        final GuildStashMenu menu = openStashes.get(who);
        if (menu != null && menu.guildId() == guild.id()) {
            menu.redraw();
        }
    }

    private void redrawStash(final long guildId) {
        for (final GuildStashMenu menu : new ArrayList<>(openStashes.values())) {
            if (menu.guildId() == guildId && menu.player().getOpenInventory().getTopInventory().getHolder() == menu) {
                menu.redraw();
            }
        }
    }

    /** 這件東西不能放進公會倉庫的原因；可以放回傳 null。 */
    private String depositProblem(final Player player, final int slot, final ItemStack stack) {
        if (plugin.gold().amountOf(stack).isPresent()) {
            return "<red>金幣不能放進倉庫（要捐給公會用「捐獻」）";
        }
        if (tw.exilecore.flask.FlaskService.isPlaceholder(stack)) {
            return "<gray>這是空的藥劑欄（提示），不是東西";
        }
        if (plugin.flasks() != null) {
            for (final int flaskSlot : plugin.flasks().slots(player)) {
                if (flaskSlot == slot) {
                    return "<gray>藥劑欄裡的東西先拿到背包再放";
                }
            }
        }
        if (plugin.items().read(stack).map(ItemData::locked).orElse(false)) {
            return "<#ffd27f>🔒 這件鎖定了（/lock 解除後才能放）";
        }
        if (stack.hasData(io.papermc.paper.datacomponent.DataComponentTypes.BUNDLE_CONTENTS)
                || stack.hasData(io.papermc.paper.datacomponent.DataComponentTypes.CONTAINER)) {
            return "<red>收納袋、界伏盒這類容器不能放（把裡面的東西拿出來再放）";
        }
        return null;
    }

    /** 這次倉庫操作能不能做；不能的話已經跟玩家說了。 */
    private Guild stashGuild(final Player player, final GuildStashMenu menu) {
        final Guild guild = roster.guildOf(player.getUniqueId());
        if (guild == null || guild.id() != menu.guildId() || stashes.get(guild.id()) == null) {
            closeStash(player);
            player.closeInventory();
            return null;
        }
        final String problem = plugin.characters().transactionProblem(player);
        if (problem != null) {
            tw.exilecore.gui.ActionBar.send(player, plugin.text().parse("<red>" + problem));
            return null;
        }
        return guild;
    }

    /** 把背包第 slot 格的東西放進公會倉庫的第 page 頁。 */
    void deposit(final Player player, final GuildStashMenu menu, final int page, final int slot) {
        final Guild guild = stashGuild(player, menu);
        if (guild == null) {
            return;
        }
        final GuildStash stash = stashes.get(guild.id());
        final ItemStack stack = player.getInventory().getItem(slot);
        if (stack == null || stack.isEmpty() || page < 0 || page >= stash.pageCount()) {
            return;
        }
        if (page >= usablePages(guild)) {
            tw.exilecore.gui.ActionBar.send(player, plugin.text().parse("<red>這一頁現在不能放東西（只能拿出來）"));
            return;
        }
        final String problem = depositProblem(player, slot, stack);
        if (problem != null) {
            tw.exilecore.gui.ActionBar.send(player, plugin.text().parse(problem));
            return;
        }
        final int amount = stack.getAmount();
        final int rest = GuildStash.add(stash.page(page), stack.clone());
        if (rest >= amount) {
            tw.exilecore.gui.ActionBar.send(player, plugin.text().parse("<red>這一頁滿了（換一頁再放）"));
            return;
        }
        final ItemStack moved = stack.asQuantity(amount - rest);
        player.getInventory().setItem(slot, rest > 0 ? stack.asQuantity(rest) : null);
        commitStash(player, guild, page, "存入", moved, true);
        player.playSound(player.getLocation(), Sound.ITEM_BUNDLE_INSERT, 0.6f, 1.0f);
        redrawStash(guild.id());
    }

    /** 從公會倉庫第 page 頁的第 slot 格拿出來。 */
    void withdraw(final Player player, final GuildStashMenu menu, final int page, final int slot) {
        final Guild guild = stashGuild(player, menu);
        if (guild == null) {
            return;
        }
        final GuildStash stash = stashes.get(guild.id());
        if (page < 0 || page >= stash.pageCount() || slot < 0 || slot >= GuildStash.PAGE_SIZE) {
            return;
        }
        final ItemStack stack = stash.page(page)[slot];
        if (stack == null || stack.isEmpty()) {
            return;
        }
        if (!guild.rank(player.getUniqueId()).atLeast(guild.withdrawRank())) {
            tw.exilecore.gui.ActionBar.send(player, plugin.text().parse("<red>公會倉庫現在" + withdrawText(guild.withdrawRank()) + "才能拿東西"));
            return;
        }
        final Wallet wallet = plugin.economy().wallet();
        if (!wallet.fits(player, stack)) {
            tw.exilecore.gui.ActionBar.send(player, plugin.text().parse("<red>背包放不下"));
            return;
        }
        stash.page(page)[slot] = null;
        final ItemStack moved = stack.clone();
        wallet.dropFor(player, wallet.give(player, stack)); // 檢查過放得下；萬一放不下掉在腳下（只有自己撿得到）
        commitStash(player, guild, page, "取出", moved, false);
        player.playSound(player.getLocation(), Sound.ITEM_BUNDLE_REMOVE_ONE, 0.6f, 1.0f);
        redrawStash(guild.id());
    }

    // ---- 公會倉庫：搜尋、頁籤、整理（2026-10） ----

    /** 重新打開公會倉庫的某一頁（從全部頁籤一覽、輸入框回來）；search 不是 null 就接著搜尋。 */
    void reopenStash(final Player player, final long guildId, final int page, final String search, final boolean viewOnly) {
        final Guild guild = roster.guild(guildId);
        if (guild == null || !player.isOnline()) {
            return;
        }
        if (viewOnly) {
            if (!player.hasPermission(ExileCore.PERMISSION_ADMIN)) {
                return;
            }
        } else if (roster.guildOf(player.getUniqueId()) != guild) {
            tell(player, "<gray>你不在這個公會裡了");
            return;
        } else if (!canUseStashHere(player)) {
            tell(player, "<red>公會倉庫只能在營地打開");
            return;
        }
        withStash(guild, player, stash -> {
            if (player.isOnline() && roster.guild(guildId) != null && (viewOnly || roster.guildOf(player.getUniqueId()) == roster.guild(guildId))) {
                new GuildStashMenu(plugin, player, this, guildId, Math.max(0, Math.min(page, stash.pageCount() - 1)), viewOnly, search).open();
            }
        });
    }

    /** 能不能管頁籤（名稱、分類）與整理全部頁：會長、幹部。 */
    boolean canManageStash(final Guild guild, final Player player) {
        final GuildRank rank = guild == null ? null : guild.rank(player.getUniqueId());
        return rank != null && rank.atLeast(GuildRank.OFFICER);
    }

    /** 物品的分類（頁籤、照分類放）。 */
    tw.exilecore.stash.StashCategory stashCategory(final ItemStack stack) {
        return organizer().category(stack);
    }

    private tw.exilecore.stash.StashOrganizer organizer() {
        if (organizer == null) {
            organizer = new tw.exilecore.stash.StashOrganizer(plugin);
        }
        return organizer;
    }

    /**
     * 存倉庫的幾頁（整理、頁籤設定；不牽涉玩家背包，所以不用跟玩家的存檔綁在一起）：一個交易。
     * 失敗的話記下來，下一輪把整個倉庫重存一次（記憶體裡的才是對的）。
     */
    private void saveStash(final Guild guild, final java.util.Collection<Integer> pages, final boolean meta) {
        final long id = guild.id();
        final GuildStash stash = stashes.get(id);
        if (stash == null) {
            return;
        }
        final Map<Integer, byte[]> contents = new java.util.LinkedHashMap<>();
        for (final int page : pages) {
            if (page >= 0 && page < stash.pageCount()) {
                contents.put(page, stash.serialize(page));
            }
        }
        if (meta) {
            contents.put(GuildStash.META_PAGE, stash.serializeMeta());
        }
        if (contents.isEmpty()) {
            return;
        }
        plugin.database().async(connection -> {
            if (plugin.characters().writesPaused()) {
                return Boolean.FALSE;
            }
            repository.saveStashPages(connection, id, contents);
            return Boolean.TRUE;
        }).whenComplete((saved, error) -> {
            if (error != null) {
                plugin.getSLF4JLogger().error("公會倉庫（#{}）存檔失敗（之後整個重存）", id, error);
                sync(() -> {
                    if (roster.guild(id) != null && stashes.containsKey(id)) {
                        stashDirty.add(id);
                    }
                });
            }
        });
    }

    /** 上次沒存成功的公會倉庫：整個重存。 */
    private void flushStashes() {
        if (stashDirty.isEmpty()) {
            return;
        }
        final List<Long> ids = new ArrayList<>(stashDirty);
        stashDirty.clear();
        for (final long id : ids) {
            final Guild guild = roster.guild(id);
            final GuildStash stash = stashes.get(id);
            if (guild == null || stash == null) {
                continue;
            }
            final List<Integer> all = new ArrayList<>();
            for (int page = 0; page < stash.pageCount(); page++) {
                all.add(page);
            }
            saveStash(guild, all, true);
        }
    }

    /** 改一頁的頁籤（名稱、分類；會長與幹部）：跳出輸入框，改好或取消都跑 back。 */
    void editStashTab(final Player player, final long guildId, final int page, final Runnable back) {
        final Guild guild = roster.guildOf(player.getUniqueId());
        final GuildStash stash = stashes.get(guildId);
        if (guild == null || guild.id() != guildId || stash == null || page < 0 || page >= stash.pageCount()) {
            return;
        }
        if (!canManageStash(guild, player)) {
            tw.exilecore.gui.ActionBar.send(player, plugin.text().parse("<red>只有會長與幹部能改頁籤的名稱與分類"));
            return;
        }
        final List<tw.exilecore.gui.Dialogs.Option> options = new ArrayList<>();
        for (final tw.exilecore.stash.StashCategory category : tw.exilecore.stash.StashCategory.tabChoices()) {
            options.add(new tw.exilecore.gui.Dialogs.Option(category.name(),
                    category.displayName() + (category == tw.exilecore.stash.StashCategory.ALL ? "（什麼都放）" : "")));
        }
        plugin.dialogs().form(player, "<dark_gray>公會倉庫頁籤：" + ItemRenderer.escape(stash.tabLabel(page)),
                List.of("<gray>分類是給「Shift＋點背包的東西」與「全部頁照分類重新分頁」用的：",
                        "<gray>先放分類一樣的頁，再放大類（防具、飾品、其他）的頁，最後放「全部」的頁",
                        "<dark_gray>名稱空白＝第 " + (page + 1) + " 頁"),
                List.of(new tw.exilecore.gui.Dialogs.Text("name", "名稱（最多 " + GuildStash.TAB_NAME_MAX + " 個字）", stash.tabName(page),
                                GuildStash.TAB_NAME_MAX),
                        new tw.exilecore.gui.Dialogs.Choice("category", "放什麼", options, stash.tabCategory(page).name())),
                "好", answers -> {
                    final Guild current = roster.guildOf(player.getUniqueId());
                    final GuildStash now = stashes.get(guildId);
                    if (current == null || current.id() != guildId || now == null || !canManageStash(current, player) || page >= now.pageCount()) {
                        return;
                    }
                    String name = answers.get("name") == null ? "" : answers.get("name").strip();
                    if (!name.isEmpty()) {
                        final tw.exilecore.character.NameFilter filter = plugin.characters().config().nameFilter();
                        name = filter.maskChat(name);
                        if (filter.disguisedInChat(name)) {
                            tell(player, "<red>頁籤名稱含有不雅、歧視或敏感的字，沒有改");
                            back.run();
                            return;
                        }
                    }
                    final tw.exilecore.stash.StashCategory category = tw.exilecore.stash.StashCategory.parseTab(answers.get("category"));
                    now.setTab(page, name, category);
                    saveStash(current, List.of(), true);
                    log(guildId, plugin.displayName(player), "設定", "倉庫第 " + (page + 1) + " 頁的頁籤：" + now.tabLabel(page) + "（放 "
                            + now.tabCategory(page).displayName() + "）");
                    redrawStash(guildId);
                    back.run();
                }, back);
    }

    /** 整理一頁（同樣的疊在一起，照種類、稀有度、等級排好）：能拿東西的人才能整理。 */
    void sortStashPage(final Player player, final GuildStashMenu menu, final int page) {
        final Guild guild = stashGuild(player, menu);
        if (guild == null) {
            return;
        }
        final GuildStash stash = stashes.get(guild.id());
        if (page < 0 || page >= stash.pageCount()) {
            return;
        }
        if (!guild.rank(player.getUniqueId()).atLeast(guild.withdrawRank())) {
            tw.exilecore.gui.ActionBar.send(player, plugin.text().parse("<red>公會倉庫現在" + withdrawText(guild.withdrawRank()) + "才能整理"));
            return;
        }
        organizer().sort(List.of(tw.exilecore.stash.StashOrganizer.Slots.of(stash.page(page))));
        saveStash(guild, List.of(page), false);
        log(guild.id(), plugin.displayName(player), "整理", "整理倉庫第 " + (page + 1) + " 頁");
        player.playSound(player.getLocation(), Sound.ITEM_ARMOR_EQUIP_LEATHER, 0.6f, 1.2f);
        redrawStash(guild.id());
    }

    /**
     * 全部頁照頁籤的分類重新分頁（會長、幹部）：能用的頁全部拿出來、同樣的疊好，先放分類一樣的頁，再放大類的頁，最後放「全部」的頁。
     * 收回的頁（公會等級變低多出來的）不動。
     */
    void sortStashAll(final Player player, final long guildId) {
        final Guild guild = roster.guildOf(player.getUniqueId());
        final GuildStash stash = stashes.get(guildId);
        if (guild == null || guild.id() != guildId || stash == null) {
            return;
        }
        if (!canManageStash(guild, player)) {
            tw.exilecore.gui.ActionBar.send(player, plugin.text().parse("<red>只有會長與幹部能把全部頁重新分頁"));
            return;
        }
        final String problem = plugin.characters().transactionProblem(player);
        if (problem != null) {
            tw.exilecore.gui.ActionBar.send(player, plugin.text().parse("<red>" + problem));
            return;
        }
        final int usable = Math.min(usablePages(guild), stash.pageCount());
        final List<tw.exilecore.stash.StashOrganizer.Slots> rows = new ArrayList<>();
        for (int page = 0; page < usable; page++) {
            rows.add(tw.exilecore.stash.StashOrganizer.Slots.of(stash.page(page)));
        }
        final List<tw.exilecore.stash.StashLayout.Entry<ItemStack>> entries = new ArrayList<>();
        for (final ItemStack item : tw.exilecore.stash.StashOrganizer.takeAll(rows)) {
            entries.add(organizer().entry(item));
        }
        final List<List<tw.exilecore.stash.StashLayout.Entry<ItemStack>>> placed =
                tw.exilecore.stash.StashLayout.distribute(entries, stash.tabCategories(usable), GuildStash.PAGE_SIZE);
        final List<Integer> pages = new ArrayList<>();
        for (int page = 0; page < usable; page++) {
            pages.add(page);
            if (page < placed.size()) {
                final ItemStack[] target = stash.page(page);
                int index = 0;
                for (final tw.exilecore.stash.StashLayout.Entry<ItemStack> entry : placed.get(page)) {
                    target[index++] = entry.item();
                }
            }
        }
        saveStash(guild, pages, false);
        log(guildId, plugin.displayName(player), "整理", "全部頁照分類重新分頁（" + usable + " 頁、" + entries.size() + " 件）");
        broadcast(guild, "<gray>" + shown(player) + " 把公會倉庫照頁籤的分類重新分頁了");
        player.playSound(player.getLocation(), Sound.ITEM_ARMOR_EQUIP_LEATHER, 0.6f, 1.2f);
        redrawStash(guildId);
    }

    /**
     * 背包第 slot 格的東西照分類放進公會倉庫（Shift＋點）：照頁籤的分類找頁——分類一樣的、大類的、「全部」的——放進第一個整疊放得下的頁。
     */
    void depositByCategory(final Player player, final GuildStashMenu menu, final int slot) {
        final Guild guild = stashGuild(player, menu);
        if (guild == null) {
            return;
        }
        final GuildStash stash = stashes.get(guild.id());
        final ItemStack stack = player.getInventory().getItem(slot);
        if (stack == null || stack.isEmpty()) {
            return;
        }
        final int usable = Math.min(usablePages(guild), stash.pageCount());
        final tw.exilecore.stash.StashCategory category = stashCategory(stack);
        for (final int page : tw.exilecore.stash.StashLayout.depositOrder(category, stash.tabCategories(usable))) {
            final ItemStack[] copy = new ItemStack[GuildStash.PAGE_SIZE];
            final ItemStack[] source = stash.page(page);
            for (int i = 0; i < copy.length; i++) {
                copy[i] = source[i] == null ? null : source[i].clone();
            }
            if (GuildStash.add(copy, stack.clone()) == 0) {
                deposit(player, menu, page, slot); // 整疊放得下：照一般的存入走（檢查、存檔、紀錄都一樣）
                tw.exilecore.gui.ActionBar.send(player, plugin.text().parse("<gray>放進 <white>" + ItemRenderer.escape(stash.tabLabel(page))
                        + "<gray>（" + category.displayName() + "）"));
                return;
            }
        }
        tw.exilecore.gui.ActionBar.send(player, plugin.text().parse("<red>放「" + category.displayName() + "」的頁都滿了，或沒有這個分類的頁（直接點＝放進現在這一頁）"));
    }

    /** 倉庫的一頁跟這個玩家的存檔一起寫（同一個資料庫交易），順便記公會紀錄與物品追查用的交易紀錄。 */
    private void commitStash(final Player player, final Guild guild, final int page, final String action, final ItemStack moved,
                             final boolean deposit) {
        final long id = guild.id();
        final byte[] contents = stashes.get(id).serialize(page);
        final long at = System.currentTimeMillis();
        final String actor = plugin.displayName(player);
        final String what = Wallet.describe(moved);
        final String opId = TradeLogRepository.newTradeId();
        final String itemUuid = plugin.items().read(moved).map(ItemData::uuid).orElse(null);
        final String guildName = "公會 " + guild.tag();
        final TradeLogRepository.Entry entry = deposit
                ? new TradeLogRepository.Entry(opId, TRADE_KIND, itemUuid, what + "（存入公會倉庫）", moved.getAmount(),
                        player.getUniqueId(), player.getName(), null, guildName)
                : new TradeLogRepository.Entry(opId, TRADE_KIND, itemUuid, what + "（從公會倉庫取出）", moved.getAmount(),
                        null, guildName, player.getUniqueId(), player.getName());
        plugin.players().markDirty(player);
        // 2026-10-18：卡住重試時重新序列化這一頁當下的內容（期間整理、重新分頁直接寫過的不會被舊快照蓋回去＝不會複製物品）；
        // 公會解散了就只寫交易紀錄
        final java.util.function.Function<byte[], tw.exilecore.character.CharacterRepository.ExtraWork> work = current -> connection -> {
            if (current != null) {
                repository.saveStashPage(connection, id, page, current);
                repository.insertLog(connection, id, at, actor, action, what + "（第 " + (page + 1) + " 頁）");
            }
            plugin.economy().tradeLog().insert(connection, List.of(entry));
        };
        plugin.characters().commit(List.of(player), work.apply(contents), () -> {
            final GuildStash now = roster.guild(id) != null ? stashes.get(id) : null;
            return work.apply(now != null && page < now.pageCount() ? now.serialize(page) : null);
        }, opId, "公會倉庫" + action + "（" + player.getName() + "）");
    }

    // ---- 管理員 ----

    /** 管理員解散公會（force：連倉庫裡的東西一起刪）。 */
    public void adminDisband(final CommandSender sender, final String guildName, final boolean force) {
        final Guild guild = roster.byName(guildName);
        if (guild == null) {
            sender.sendMessage(plugin.text().prefixed("<red>沒有公會叫「" + ItemRenderer.escape(guildName.strip()) + "」"));
            return;
        }
        final long id = guild.id();
        final Consumer<GuildStash> finish = stash -> {
            final Guild current = roster.guild(id);
            if (current == null) {
                return;
            }
            final int items = stash.itemCount();
            if (items > 0 && !force) {
                sender.sendMessage(plugin.text().prefixed("<red>公會倉庫裡還有 " + items + " 件東西；真的要連東西一起刪用 /guild admin forcedisband "
                        + ItemRenderer.escape(current.name())));
                return;
            }
            final List<Player> members = online(current);
            roster.remove(id);
            removed(current, members);
            for (final Player member : members) {
                tell(member, "<gray>公會 " + label(current) + "<gray> 被管理員解散了");
            }
            sender.sendMessage(plugin.text().prefixed("<green>解散了公會 " + label(current) + (items > 0 ? "<gray>（倉庫裡的 " + items + " 件一起刪了）" : "")));
            plugin.getSLF4JLogger().warn("[公會] 管理員 {} 解散了公會 [{}] {}（#{}，倉庫 {} 件）", sender.getName(), current.tag(), current.name(), id, items);
        };
        // 2026-10-18：用 withStash（跟成員共用同一份倉庫）；以前另外讀一份複本，成員剛放進去的東西不算、解散時一起刪掉
        withStash(guild, sender, finish);
    }

    /** 管理員加公會經驗（測試、補償）。 */
    public void adminExperience(final CommandSender sender, final long amount, final String guildName) {
        final Guild guild = roster.byName(guildName);
        if (guild == null) {
            sender.sendMessage(plugin.text().prefixed("<red>沒有公會叫「" + ItemRenderer.escape(guildName.strip()) + "」"));
            return;
        }
        if (amount <= 0) {
            sender.sendMessage(plugin.text().prefixed("<red>經驗要是正的數字"));
            return;
        }
        final int before = guild.level();
        final int gained = guild.addExperience(amount, config);
        if (gained > 0) {
            levelUp(guild, before);
        } else {
            persist(guild);
            refreshLadder();
        }
        log(guild.id(), "管理員", "設定", "公會經驗 +" + GoldService.format(amount));
        sender.sendMessage(plugin.text().prefixed("<green>" + label(guild) + "<gray> 公會經驗 +" + GoldService.format(amount) + "，現在 Lv." + guild.level()));
        plugin.getSLF4JLogger().info("[公會] 管理員 {} 給 [{}] {} 公會經驗 {}", sender.getName(), guild.tag(), guild.name(), amount);
    }

    /** 找不到公會時跟管理員說；找到回傳。 */
    private Guild adminGuild(final CommandSender sender, final String guildName) {
        final Guild guild = guildName == null ? null : roster.byName(guildName);
        if (guild == null) {
            sender.sendMessage(plugin.text().prefixed("<red>沒有公會叫「" + ItemRenderer.escape(guildName == null ? "" : guildName.strip())
                    + "」<gray>（名稱或標籤都可以；/guild admin list 看全部）"));
        }
        return guild;
    }

    /** 所有公會裡找成員（記下來的角色名稱、在線的人現在的名字、玩家 ID 都可以）；找不到已經跟管理員說了。 */
    private GuildRoster.Membership adminMember(final CommandSender sender, final String query) {
        if (query != null && !query.isBlank()) {
            final GuildRoster.Membership stored = roster.memberByName(query);
            if (stored != null) {
                return stored;
            }
            for (final Guild guild : roster.all()) {
                final Guild.Member member = findMember(guild, query);
                if (member != null) {
                    return new GuildRoster.Membership(guild, member);
                }
            }
        }
        sender.sendMessage(plugin.text().prefixed("<red>沒有公會成員叫「" + ItemRenderer.escape(query == null ? "" : query.strip()) + "」"));
        return null;
    }

    private void adminDone(final CommandSender sender, final Guild guild, final String action, final String detail) {
        log(guild.id(), "管理員", action, detail);
        plugin.getSLF4JLogger().warn("[公會] 管理員 {} → [{}] {}（#{}）：{}", sender.getName(), guild.tag(), guild.name(), guild.id(), detail);
    }

    /** 管理員：指令一覽。 */
    public void adminHelp(final CommandSender sender) {
        sender.sendMessage(plugin.text().parse("<#f5b942><bold>公會管理員指令</bold> <dark_gray>公會可以寫名稱或標籤"));
        for (final String[] row : ADMIN_HELP) {
            sender.sendMessage(plugin.text().parse("<gray>  <white>/guild admin " + row[0] + "<dark_gray> — <gray>" + row[1]));
        }
    }

    /** 管理員指令的說明（/guild admin 與 /ec help 共用）。 */
    public static final String[][] ADMIN_HELP = {
            {"list", "全部的公會：等級、人數、會長、倉庫頁數"},
            {"info 公會", "詳細資料與成員名單（階級、帳號、貢獻、加入日期、在不在線）"},
            {"log 公會", "最近的公會紀錄（加入、離開、倉庫存取、設定）"},
            {"stash 公會", "打開那個公會的倉庫（只能看，不能拿放）"},
            {"leader 成員", "把會長換成這個成員（原本的會長變成幹部）"},
            {"promote｜demote 成員", "升成幹部、降成成員"},
            {"kick 成員", "請出公會（會長要先換人）"},
            {"add 公會 玩家", "把在線的玩家直接放進公會（不用邀請）"},
            {"rename 公會 新標籤 新名稱", "改名稱與標籤（不用改名卡）"},
            {"motd 公會 [文字]", "改公告；不寫文字 = 清掉"},
            {"xp 數量 公會", "加公會經驗"},
            {"level 等級 公會", "直接設公會等級（這一級的經驗歸零）"},
            {"pages 頁數 公會", "設定「用星幣買的倉庫頁」是幾頁（補償用）"},
            {"disband 公會", "解散（倉庫要是空的）"},
            {"forcedisband 公會", "解散，倉庫裡的東西一起刪"}};

    /** 管理員：全部的公會。 */
    public void adminList(final CommandSender sender) {
        final List<Guild> list = roster.top(config);
        sender.sendMessage(plugin.text().parse("<#f5b942><bold>全部的公會</bold> <dark_gray>" + list.size() + " 個"));
        for (final Guild guild : list) {
            final Guild.Member leader = guild.member(guild.leader());
            sender.sendMessage(plugin.text().parse("<dark_gray>  #" + guild.id() + " " + label(guild) + " <#f5b942>Lv." + guild.level()
                    + " <gray>" + guild.size() + "/" + config.maxMembers() + " 人（在線 " + online(guild).size() + "）　會長 <white>"
                    + ItemRenderer.escape(leader == null ? "？" : leader.name()) + "<gray>　倉庫 " + stashPages(guild) + " 頁"));
        }
        if (list.isEmpty()) {
            sender.sendMessage(plugin.text().parse("<gray>  還沒有公會"));
        }
    }

    /** 管理員：公會的詳細資料與成員名單。 */
    public void adminInfo(final CommandSender sender, final String guildName) {
        final Guild guild = adminGuild(sender, guildName);
        if (guild == null) {
            return;
        }
        for (final String line : describe(guild)) {
            sender.sendMessage(plugin.text().parse(line));
        }
        sender.sendMessage(plugin.text().parse("<gray>編號 <white>#" + guild.id() + "<gray>　倉庫誰能拿：<white>" + withdrawText(guild.withdrawRank())
                + "<gray>　星幣買的倉庫頁：<white>" + guild.boughtPages()));
        final List<Guild.Member> members = new ArrayList<>(guild.members());
        members.sort(java.util.Comparator.comparingInt((Guild.Member member) -> member.rank().power()).reversed()
                .thenComparing(java.util.Comparator.comparingLong(Guild.Member::contribution).reversed()));
        for (final Guild.Member member : members) {
            final boolean here = plugin.getServer().getPlayer(member.uuid()) != null;
            final String account = plugin.getServer().getOfflinePlayer(member.uuid()).getName();
            sender.sendMessage(plugin.text().parse("  " + (here ? "<green>●" : "<dark_gray>○") + " <gray>" + member.rank().displayName() + " <white>"
                    + ItemRenderer.escape(member.name()) + "<dark_gray>（" + ItemRenderer.escape(account == null ? member.uuid().toString() : account)
                    + "）<gray> 貢獻 " + GoldService.format(member.contribution()) + "　加入 " + date(member.joinedAt())));
        }
    }

    /** 管理員：最近的公會紀錄。 */
    public void adminLog(final CommandSender sender, final String guildName) {
        final Guild guild = adminGuild(sender, guildName);
        if (guild == null) {
            return;
        }
        final String title = label(guild);
        loadLog(guild.id(), rows -> {
            sender.sendMessage(plugin.text().parse("<#f5b942><bold>公會紀錄</bold> " + title + " <dark_gray>最近 " + rows.size() + " 筆（新的在上）"));
            for (final GuildRepository.LogRow row : rows) {
                sender.sendMessage(plugin.text().parse("<dark_gray>  " + dateTime(row.at()) + " <gray>" + ItemRenderer.escape(row.actor())
                        + " <white>" + ItemRenderer.escape(row.action()) + "<gray>：" + ItemRenderer.escape(row.detail())));
            }
            if (rows.isEmpty()) {
                sender.sendMessage(plugin.text().parse("<gray>  沒有紀錄"));
            }
        });
    }

    /** 管理員：打開別的公會的倉庫（只能看）。 */
    public void adminStash(final Player admin, final String guildName) {
        final Guild guild = adminGuild(admin, guildName);
        if (guild == null) {
            return;
        }
        final long id = guild.id();
        withStash(guild, admin, stash -> {
            if (admin.isOnline() && roster.guild(id) != null) {
                new GuildStashMenu(plugin, admin, this, id, 0, true, null).open();
            }
        });
    }

    /** 管理員：指定會長。 */
    public void adminLeader(final CommandSender sender, final String memberName) {
        final GuildRoster.Membership found = adminMember(sender, memberName);
        if (found == null) {
            return;
        }
        final Guild guild = found.guild();
        final Guild.Member old = guild.member(guild.leader());
        final GuildRoster.Result result = roster.adminLeader(guild.id(), found.member().uuid());
        if (result == GuildRoster.Result.ALREADY_RANK) {
            sender.sendMessage(plugin.text().prefixed("<gray>" + ItemRenderer.escape(found.member().name()) + " 已經是 " + label(guild) + "<gray> 的會長了"));
            return;
        }
        if (result != GuildRoster.Result.OK) {
            sender.sendMessage(plugin.text().prefixed("<red>沒辦法換會長（" + result + "）"));
            return;
        }
        persist(guild);
        refreshLadder();
        if (old != null) {
            closeStashIfNotAllowed(guild, old.uuid());
        }
        closeStashIfNotAllowed(guild, found.member().uuid());
        adminDone(sender, guild, "會長", "管理員把會長從 " + (old == null ? "？" : old.name()) + " 換成 " + found.member().name());
        broadcast(guild, "<#f5b942>★ <white>" + ItemRenderer.escape(found.member().name()) + "<gray> 成為會長（管理員指定"
                + (old == null ? "" : "；" + ItemRenderer.escape(old.name()) + " 轉為幹部") + "）");
        sender.sendMessage(plugin.text().prefixed("<green>" + label(guild) + "<gray> 的會長換成 <white>" + ItemRenderer.escape(found.member().name())));
    }

    /** 管理員：升成幹部、降成成員。 */
    public void adminRank(final CommandSender sender, final String memberName, final GuildRank rank) {
        final GuildRoster.Membership found = adminMember(sender, memberName);
        if (found == null) {
            return;
        }
        final Guild guild = found.guild();
        final String name = ItemRenderer.escape(found.member().name());
        final GuildRoster.Result result = roster.adminRank(guild.id(), found.member().uuid(), rank);
        switch (result) {
            case OK -> {
                persist(guild);
                final boolean up = rank.atLeast(GuildRank.OFFICER);
                adminDone(sender, guild, up ? "升階" : "降階", "管理員把 " + found.member().name() + " 改成" + rank.displayName());
                broadcast(guild, "<white>" + name + "<gray> " + (up ? "升為" : "降為") + " <white>" + rank.displayName() + "<gray>（管理員）");
                closeStashIfNotAllowed(guild, found.member().uuid());
                sender.sendMessage(plugin.text().prefixed("<green>" + label(guild) + "<gray> 的 <white>" + name + "<gray> 現在是" + rank.displayName()));
            }
            case ALREADY_RANK -> sender.sendMessage(plugin.text().prefixed("<gray>" + name + " 已經是" + rank.displayName() + "了"));
            case LEADER_MUST_TRANSFER -> sender.sendMessage(plugin.text().prefixed("<red>" + name + " 是會長：先用 /guild admin leader 別的成員 換會長"));
            default -> sender.sendMessage(plugin.text().prefixed("<red>沒辦法改階級（" + result + "）"));
        }
    }

    /** 管理員：請出公會。 */
    public void adminKick(final CommandSender sender, final String memberName) {
        final GuildRoster.Membership found = adminMember(sender, memberName);
        if (found == null) {
            return;
        }
        final Guild guild = found.guild();
        final String name = ItemRenderer.escape(found.member().name());
        final GuildRoster.Result result = roster.adminKick(guild.id(), found.member().uuid());
        if (result == GuildRoster.Result.LEADER_MUST_TRANSFER) {
            sender.sendMessage(plugin.text().prefixed("<red>" + name + " 是會長：先用 /guild admin leader 別的成員 換會長，"
                    + "或 /guild admin disband " + ItemRenderer.escape(guild.name()) + " 解散"));
            return;
        }
        if (result != GuildRoster.Result.OK) {
            sender.sendMessage(plugin.text().prefixed("<red>沒辦法請出（" + result + "）"));
            return;
        }
        departed(guild, found.member().uuid());
        adminDone(sender, guild, "請出", "管理員把 " + found.member().name() + " 請出公會");
        final Player kicked = plugin.getServer().getPlayer(found.member().uuid());
        if (kicked != null) {
            tell(kicked, "<gray>你被管理員請出了公會 " + label(guild));
        }
        broadcast(guild, "<white>" + name + "<gray> 被管理員請出了公會（剩 " + guild.size() + " 人）");
        sender.sendMessage(plugin.text().prefixed("<green>把 <white>" + name + "<green> 請出了 " + label(guild)));
    }

    /** 管理員：把在線的玩家直接放進公會（text = 「公會 玩家」）。 */
    public void adminAdd(final CommandSender sender, final String text) {
        final String[] parts = text == null ? new String[0] : text.strip().split("\\s+", 2);
        if (parts.length < 2) {
            sender.sendMessage(plugin.text().prefixed("<gray>用法：<white>/guild admin add 公會 玩家<gray>（公會寫名稱或標籤，玩家要在線）"));
            return;
        }
        final Guild guild = adminGuild(sender, parts[0]);
        if (guild == null) {
            return;
        }
        final Player target = plugin.chat() != null ? plugin.chat().findOrTell(sender, parts[1]) : plugin.getServer().getPlayerExact(parts[1].strip());
        if (target == null) {
            if (plugin.chat() == null) {
                sender.sendMessage(plugin.text().prefixed("<red>找不到在線的玩家「" + ItemRenderer.escape(parts[1]) + "」"));
            }
            return;
        }
        final GuildRoster.Joined joined = roster.adminAdd(guild.id(), target.getUniqueId(), plugin.displayName(target), System.currentTimeMillis(), config);
        switch (joined.result()) {
            case OK -> {
                persist(guild);
                clearApplications(target.getUniqueId());
                refresh(target);
                refreshLadder();
                adminDone(sender, guild, "加入", "管理員把 " + plugin.displayName(target) + " 放進公會");
                tell(target, "<green>管理員把你放進了公會 " + label(guild) + "<gray>　<white>/g 訊息<gray> 公會頻道，<white>/guild<gray> 公會選單");
                for (final Player member : online(guild)) {
                    if (!member.equals(target)) {
                        member.sendMessage(channel("<white>" + shown(target) + "<gray> 加入了公會（管理員；" + guild.size() + " 人）"));
                    }
                }
                sender.sendMessage(plugin.text().prefixed("<green>把 <white>" + shown(target) + "<green> 放進了 " + label(guild)));
            }
            case TARGET_IN_GUILD -> sender.sendMessage(plugin.text().prefixed("<red>" + shown(target) + " 已經在公會 "
                    + (joined.guild() == null ? "" : label(joined.guild())) + "<red> 裡了：先 /guild admin kick"));
            case FULL -> sender.sendMessage(plugin.text().prefixed("<red>" + label(guild) + "<red> 滿了（" + config.maxMembers() + " 人）"));
            default -> sender.sendMessage(plugin.text().prefixed("<red>沒辦法加入（" + joined.result() + "）"));
        }
    }

    /** 管理員：改公會的名稱與標籤（text = 「公會 新標籤 新名稱」；不用改名卡、不過不雅字過濾——管理員自己決定）。 */
    public void adminRename(final CommandSender sender, final String text) {
        final String[] parts = text == null ? new String[0] : text.strip().split("\\s+", 3);
        if (parts.length < 3) {
            sender.sendMessage(plugin.text().prefixed("<gray>用法：<white>/guild admin rename 公會 新標籤 新名稱"));
            return;
        }
        final Guild guild = adminGuild(sender, parts[0]);
        if (guild == null) {
            return;
        }
        final String tag = parts[1];
        final String name = parts[2].strip();
        final String oldName = guild.name();
        final String oldTag = guild.tag();
        final GuildRoster.Result result = roster.adminRename(guild.id(), name, tag, config);
        switch (result) {
            case OK -> {
                persist(guild);
                refreshLadder();
                for (final Player member : online(guild)) {
                    plugin.players().updateListName(member);
                }
                adminDone(sender, guild, "設定", "管理員改名：[" + oldTag + "] " + oldName + " → [" + tag + "] " + name);
                broadcast(guild, "<gray>公會被管理員改名了：<white>[" + ItemRenderer.escape(oldTag) + "] " + ItemRenderer.escape(oldName) + "<gray> → " + label(guild));
                sender.sendMessage(plugin.text().prefixed("<green>改名了：<white>[" + ItemRenderer.escape(oldTag) + "] " + ItemRenderer.escape(oldName)
                        + "<gray> → " + label(guild)));
            }
            case BAD_NAME -> sender.sendMessage(plugin.text().prefixed("<red>公會名稱" + GuildRoster.textProblem(name, config.nameMin(), config.nameMax())));
            case BAD_TAG -> sender.sendMessage(plugin.text().prefixed("<red>公會標籤" + GuildRoster.textProblem(tag, config.tagMin(), config.tagMax())));
            case NAME_TAKEN -> sender.sendMessage(plugin.text().prefixed("<red>已經有公會叫「" + ItemRenderer.escape(name) + "」了"));
            case TAG_TAKEN -> sender.sendMessage(plugin.text().prefixed("<red>標籤 [" + ItemRenderer.escape(tag) + "] 已經有公會用了"));
            case SAME_NAME -> sender.sendMessage(plugin.text().prefixed("<gray>名稱與標籤都跟現在一樣，沒有改"));
            default -> sender.sendMessage(plugin.text().prefixed("<red>沒辦法改名（" + result + "）"));
        }
    }

    /** 管理員：改公告（text = 「公會 [文字]」；沒有文字 = 清掉）。 */
    public void adminMotd(final CommandSender sender, final String text) {
        final String[] parts = text == null ? new String[0] : text.strip().split("\\s+", 2);
        if (parts.length < 1 || parts[0].isEmpty()) {
            sender.sendMessage(plugin.text().prefixed("<gray>用法：<white>/guild admin motd 公會 [文字]<gray>（不寫文字 = 清掉公告）"));
            return;
        }
        final Guild guild = adminGuild(sender, parts[0]);
        if (guild == null) {
            return;
        }
        final String motd = parts.length < 2 ? "" : parts[1].strip().replace('\n', ' ');
        if (motd.codePointCount(0, motd.length()) > config.motdLength()) {
            sender.sendMessage(plugin.text().prefixed("<red>公告最多 " + config.motdLength() + " 個字"));
            return;
        }
        guild.setMotd(motd);
        persist(guild);
        adminDone(sender, guild, "公告", motd.isEmpty() ? "管理員清掉公告" : "管理員改公告：" + motd);
        broadcast(guild, motd.isEmpty() ? "<gray>管理員清掉了公告" : "<gray>管理員改了公告：<white>" + ItemRenderer.escape(motd));
        sender.sendMessage(plugin.text().prefixed("<green>" + label(guild) + "<gray> " + (motd.isEmpty() ? "的公告清掉了" : "的公告改成：<white>" + ItemRenderer.escape(motd))));
    }

    /** 管理員：直接設公會等級（這一級的經驗歸零）。 */
    public void adminLevel(final CommandSender sender, final int level, final String guildName) {
        final Guild guild = adminGuild(sender, guildName);
        if (guild == null) {
            return;
        }
        if (level < 1 || level > config.maxLevel()) {
            sender.sendMessage(plugin.text().prefixed("<red>等級要在 1 到 " + config.maxLevel() + " 之間"));
            return;
        }
        final int before = roster.adminLevel(guild.id(), level, config);
        if (before == level) {
            persist(guild);
            sender.sendMessage(plugin.text().prefixed("<gray>" + label(guild) + "<gray> 本來就是 Lv." + level + "（這一級的經驗歸零了）"));
            return;
        }
        adminDone(sender, guild, "設定", "管理員把公會等級 " + before + " → " + level);
        if (level > before) {
            levelUp(guild, before); // 存檔、排行、新的加成與倉庫頁的通知
        } else {
            persist(guild);
            refreshLadder();
            redrawStash(guild.id());
            for (final Player member : online(guild)) {
                plugin.players().markDirty(member); // 公會加成變少了
            }
            broadcast(guild, "<gray>公會等級被管理員調整為 <#f5b942>Lv." + level + "<gray>（原本 Lv." + before + "；倉庫 " + stashPages(guild)
                    + " 頁，多出來的頁只能拿不能放）");
        }
        sender.sendMessage(plugin.text().prefixed("<green>" + label(guild) + "<gray> 公會等級 " + before + " → <white>" + level));
    }

    /** 管理員：設定用星幣買的倉庫頁是幾頁（補償、退費）。 */
    public void adminPages(final CommandSender sender, final int pages, final String guildName) {
        final Guild guild = adminGuild(sender, guildName);
        if (guild == null) {
            return;
        }
        final int before = guild.boughtPages();
        final int most = Math.max(0, GuildStash.MAX_PAGES - config.stashPages(guild.level()));
        if (pages < 0 || pages > most) {
            sender.sendMessage(plugin.text().prefixed("<red>頁數要在 0 到 " + most + " 之間（倉庫最多 " + GuildStash.MAX_PAGES + " 頁，這個公會等級給了 "
                    + config.stashPages(guild.level()) + " 頁）"));
            return;
        }
        guild.setBoughtPages(pages);
        persist(guild);
        final GuildStash stash = stashes.get(guild.id());
        if (stash != null) {
            stash.ensurePages(stashPages(guild));
        }
        redrawStash(guild.id());
        adminDone(sender, guild, "設定", "管理員把星幣買的倉庫頁 " + before + " → " + pages);
        broadcast(guild, "<gray>管理員調整了公會倉庫：現在 <white>" + stashPages(guild) + "<gray> 頁");
        sender.sendMessage(plugin.text().prefixed("<green>" + label(guild) + "<gray> 星幣買的倉庫頁 " + before + " → <white>" + pages
                + "<gray>（倉庫共 " + stashPages(guild) + " 頁）"));
    }

    /** 管理網頁：全部的公會（排行的順序；主執行緒）。 */
    public List<Map<String, Object>> adminGuildList() {
        final List<Map<String, Object>> list = new ArrayList<>();
        for (final Guild guild : roster.top(config)) {
            final Guild.Member leader = guild.member(guild.leader());
            final Map<String, Object> row = new java.util.LinkedHashMap<>();
            row.put("id", guild.id());
            row.put("name", guild.name());
            row.put("tag", guild.tag());
            row.put("level", guild.level());
            row.put("members", guild.size());
            row.put("maxMembers", config.maxMembers());
            row.put("online", online(guild).size());
            row.put("leader", leader == null ? "" : leader.name());
            row.put("pages", stashPages(guild));
            row.put("created", guild.createdAt());
            list.add(row);
        }
        return list;
    }

    /** 管理網頁：一個公會的詳細資料與成員（主執行緒）；沒有回傳 null。 */
    public Map<String, Object> adminGuildDetail(final long id) {
        final Guild guild = roster.guild(id);
        if (guild == null) {
            return null;
        }
        final Map<String, Object> detail = new java.util.LinkedHashMap<>();
        detail.put("id", guild.id());
        detail.put("name", guild.name());
        detail.put("tag", guild.tag());
        detail.put("level", guild.level());
        detail.put("maxLevel", config.maxLevel());
        detail.put("experience", guild.experience());
        detail.put("toNext", config.xpToNext(guild.level()));
        detail.put("motd", guild.motd());
        detail.put("withdraw", withdrawText(guild.withdrawRank()));
        detail.put("pages", stashPages(guild));
        detail.put("boughtPages", guild.boughtPages());
        detail.put("created", guild.createdAt());
        final GuildStash stash = stashes.get(guild.id());
        detail.put("stashItems", stash == null ? -1 : stash.itemCount());
        final List<Guild.Member> ordered = new ArrayList<>(guild.members());
        ordered.sort(java.util.Comparator.comparingInt((Guild.Member member) -> member.rank().power()).reversed()
                .thenComparing(java.util.Comparator.comparingLong(Guild.Member::contribution).reversed()));
        final List<Map<String, Object>> members = new ArrayList<>();
        for (final Guild.Member member : ordered) {
            final Map<String, Object> row = new java.util.LinkedHashMap<>();
            row.put("uuid", member.uuid().toString());
            row.put("name", member.name());
            final String account = plugin.getServer().getOfflinePlayer(member.uuid()).getName();
            row.put("account", account == null ? "" : account);
            row.put("rank", member.rank().displayName());
            row.put("leader", member.rank() == GuildRank.LEADER);
            row.put("officer", member.rank() == GuildRank.OFFICER);
            row.put("contribution", member.contribution());
            row.put("joined", member.joinedAt());
            row.put("online", plugin.getServer().getPlayer(member.uuid()) != null);
            members.add(row);
        }
        detail.put("members", members);
        return detail;
    }

    /** 管理網頁：最近的公會紀錄（資料庫執行緒）。 */
    public List<GuildRepository.LogRow> adminLogRows(final java.sql.Connection connection, final long guildId, final int limit)
            throws SQLException {
        return repository.loadLog(connection, guildId, limit);
    }

    /** 所有公會成員的名字（管理員指令補完）。 */
    public List<String> allMemberNames() {
        final List<String> names = new ArrayList<>();
        for (final Guild guild : roster.all()) {
            for (final Guild.Member member : guild.members()) {
                names.add(member.name());
            }
        }
        return names;
    }

    /** 全部公會的名稱（指令補完）。 */
    public List<String> guildNames() {
        final List<String> names = new ArrayList<>();
        for (final Guild guild : roster.all()) {
            names.add(guild.name());
        }
        return names;
    }

    // ---- 內部 ----

    /** 2026-10-18：公會當下的資料（卡住的存檔重試時用）；已經解散＝null。 */
    private GuildRepository.GuildRow currentRow(final long id) {
        final Guild guild = roster.guild(id);
        return guild == null ? null : GuildRepository.GuildRow.of(guild);
    }

    /** 整個公會存一次（背景；失敗的話記下來，下一輪再存）。 */
    private void persist(final Guild guild) {
        final long id = guild.id();
        dirty.remove(id);
        final GuildRepository.GuildRow row = GuildRepository.GuildRow.of(guild);
        plugin.database().async(connection -> {
            if (plugin.characters().writesPaused()) {
                return Boolean.FALSE; // 還原備份、清檔中：記憶體裡的舊資料不能寫回去
            }
            repository.saveAtomic(connection, row);
            return Boolean.TRUE;
        }).whenComplete((saved, error) -> {
            if (error != null) {
                plugin.getSLF4JLogger().error("公會 [{}] {} 存檔失敗（之後再試）", row.tag(), row.name(), error);
                sync(() -> {
                    if (roster.guild(id) != null) {
                        dirty.add(id);
                    }
                });
            }
        });
    }

    /** 記一筆公會紀錄（背景）。 */
    private void log(final long guildId, final String actor, final String action, final String detail) {
        final long at = System.currentTimeMillis();
        plugin.database().async(connection -> {
            if (!plugin.characters().writesPaused()) {
                repository.insertLog(connection, guildId, at, actor, action, detail);
            }
            return null;
        }).whenComplete((ignored, error) -> {
            if (error != null) {
                plugin.getSLF4JLogger().warn("公會紀錄寫入失敗（{} {}）", action, detail, error);
            }
        });
    }

    /** 回主執行緒做（插件停用中就不做了）。 */
    private void sync(final Runnable task) {
        if (!plugin.isEnabled()) {
            return;
        }
        try {
            plugin.getServer().getScheduler().runTask(plugin, task);
        } catch (final RuntimeException e) {
            // 正在關服
        }
    }

    /** 名字與加成跟著公會變（加入、離開、解散）。 */
    private void refresh(final Player player) {
        plugin.players().markDirty(player);
        plugin.players().updateListName(player);
    }

    private void tell(final Player player, final String message) {
        player.sendMessage(plugin.text().prefixed(message));
    }

    private Component channel(final String message) {
        return plugin.text().parse(COLOR + "[公會] " + COLOR_END + message);
    }

    /** 跟公會裡在線的人說。 */
    private void broadcast(final Guild guild, final String message) {
        final Component line = channel(message);
        for (final Player member : online(guild)) {
            member.sendMessage(line);
        }
    }

    /** 「[標籤] 名稱」（公會的顏色）。 */
    static String label(final Guild guild) {
        return COLOR + "[" + ItemRenderer.escape(guild.tag()) + "] " + ItemRenderer.escape(guild.name()) + COLOR_END;
    }

    /** 別人看到的名字（MiniMessage 跳脫過）。 */
    private String shown(final Player player) {
        return ItemRenderer.escape(plugin.displayName(player));
    }

    /** 找在線的人（角色名稱或玩家 ID）；找不到已經跟 from 說了。 */
    private Player findOnline(final Player from, final String name) {
        if (plugin.chat() != null) {
            return plugin.chat().findOrTell(from, name);
        }
        final Player target = plugin.getServer().getPlayerExact(name.strip());
        if (target == null) {
            tell(from, "<red>找不到在線的玩家「" + ItemRenderer.escape(name) + "」");
        }
        return target;
    }

    /** 公會成員：記下來的名字（角色名稱）、在線的人現在的名字、玩家 ID 都可以。 */
    private Guild.Member findMember(final Guild guild, final String query) {
        if (query == null || query.isBlank()) {
            return null;
        }
        final String wanted = query.strip();
        final Guild.Member byName = guild.memberByName(wanted);
        if (byName != null) {
            return byName;
        }
        for (final Guild.Member member : guild.members()) {
            final Player online = plugin.getServer().getPlayer(member.uuid());
            if (online != null && (online.getName().equalsIgnoreCase(wanted) || plugin.displayName(online).equalsIgnoreCase(wanted))) {
                return member;
            }
        }
        for (final Guild.Member member : guild.members()) {
            final String account = plugin.getServer().getOfflinePlayer(member.uuid()).getName();
            if (account != null && account.equalsIgnoreCase(wanted)) {
                return member;
            }
        }
        return null;
    }

    /** 公會成員的名字（指令補完；不含自己）。 */
    public List<String> memberNames(final Player player) {
        final List<String> names = new ArrayList<>();
        final Guild guild = roster.guildOf(player.getUniqueId());
        if (guild != null) {
            for (final Guild.Member member : guild.members()) {
                if (!member.uuid().equals(player.getUniqueId())) {
                    names.add(member.name());
                }
            }
        }
        return names;
    }
}
