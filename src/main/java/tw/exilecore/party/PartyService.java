package tw.exilecore.party;

import org.bukkit.GameMode;
import org.bukkit.Location;
import org.bukkit.Sound;
import org.bukkit.entity.LivingEntity;
import org.bukkit.entity.Player;
import tw.exilecore.ExileCore;
import tw.exilecore.item.ItemRenderer;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.OptionalInt;
import java.util.UUID;

/**
 * 隊伍：邀請、加入、離開、踢人、交隊長、隊伍聊天，以及給其他模組的查詢（同一隊？隊員有誰？）。
 * 名單在 {@link PartyRoster}（純邏輯）；這裡負責找玩家、發訊息。
 */
public final class PartyService {

    private final ExileCore plugin;
    private final PartyConfig config;
    private final PartyRoster roster;
    /** 看過的玩家名字（離線的隊員顯示用）。 */
    private final Map<UUID, String> names = new HashMap<>();

    PartyService(final ExileCore plugin, final PartyConfig config) {
        this.plugin = plugin;
        this.config = config;
        this.roster = new PartyRoster(config.maxSize(), config.inviteSeconds() * 20L);
    }

    public PartyConfig config() {
        return config;
    }

    /** 設定重載後：人數上限、邀請期限換新（現有的隊伍保留；已經超過上限的不會被拆散，只是不能再加人）。 */
    void reloaded() {
        roster.setLimits(config.maxSize(), config.inviteSeconds() * 20L);
    }

    private long now() {
        return plugin.getServer().getCurrentTick();
    }

    // ---- 查詢（給地圖、掉落、經驗） ----

    public boolean inParty(final Player player) {
        return roster.partyOf(player.getUniqueId()) != null;
    }

    /** 這個玩家的隊伍；沒有回傳 null。 */
    public PartyRoster.Party party(final UUID player) {
        return roster.partyOf(player);
    }

    /** 同一個人，或同一隊。 */
    public boolean sameGroup(final UUID a, final UUID b) {
        return roster.sameGroup(a, b);
    }

    /** 群組代號：隊伍的代號，沒有隊伍就是玩家自己。 */
    public UUID groupOf(final UUID player) {
        return roster.groupOf(player);
    }

    /** 同一組的人（包含自己）。 */
    public List<UUID> groupMembers(final UUID player) {
        return roster.groupMembers(player);
    }

    /** 名字（別人看到的角色名稱；在線用現在的名字，離線用記下來的）。 */
    public String name(final UUID player) {
        final Player online = plugin.getServer().getPlayer(player);
        if (online != null) {
            final String shown = display(online);
            names.put(player, shown);
            return shown;
        }
        final String known = names.get(player);
        if (known != null) {
            return known;
        }
        final String offline = plugin.getServer().getOfflinePlayer(player).getName();
        return offline == null ? player.toString().substring(0, 8) : offline;
    }

    /** 這個人收到、還沒過期的邀請的邀請人名字（新的在後）。 */
    public List<String> inviterNames(final Player player) {
        final List<String> result = new ArrayList<>();
        for (final PartyRoster.Invite invite : roster.invitesFor(player.getUniqueId(), now())) {
            result.add(name(invite.inviter()));
        }
        return result;
    }

    /**
     * 怪物刷出來那一刻「附近有幾個玩家」（人多時怪物變硬）：地圖怪算整張地圖的玩家，其他算 count_radius 格內的。
     * 隊友或路人都算；旁觀、創造模式、死掉的不算。
     */
    /**
     * 每秒：Boss 附近（地圖裡是整張地圖）的人數比 Boss 生出來時多，就照新的人數補生命，比例不變（2026-10：
     * 以前先一個人叫出 Boss、其他人再進去，Boss 一直是一人份的血）。最多算 max_players 人，只往上加。
     */
    private void rescaleBosses() {
        if (plugin.skills() == null || plugin.skills().bossSkills() == null) {
            return;
        }
        for (final LivingEntity boss : plugin.skills().bossSkills().bosses()) {
            if (plugin.worldEvents() != null && plugin.worldEvents().role(boss) != null) {
                continue; // 世界 Boss：活動模組照在場的高等級玩家自己算（2026-10）
            }
            final int now = Math.min(config.maxCountedPlayers(), countPlayers(boss));
            final int before = plugin.monsters().playersAround(boss);
            if (now > Math.min(config.maxCountedPlayers(), before) && plugin.monsters().raisePlayers(boss, now)) {
                for (final Player player : boss.getWorld().getPlayers()) {
                    if (player.getLocation().distanceSquared(boss.getLocation()) <= 64 * 64) {
                        player.sendMessage(plugin.text().prefixed("<#ffd27f>有人加入：Boss 的生命照 " + now + " 人重算（血量比例不變）"));
                    }
                }
            }
        }
    }

    public int countPlayers(final LivingEntity entity) {
        if (plugin.maps() != null) {
            final OptionalInt inMap = plugin.maps().playersIn(entity);
            if (inMap.isPresent()) {
                return inMap.getAsInt();
            }
        }
        final Location location = entity.getLocation();
        final double radius = config.countRadius();
        int count = 0;
        for (final Player player : location.getWorld().getPlayers()) {
            if (player.isDead() || player.getGameMode() == GameMode.SPECTATOR || player.getGameMode() == GameMode.CREATIVE) {
                continue;
            }
            if (player.getLocation().distanceSquared(location) <= radius * radius) {
                count++;
            }
        }
        return Math.max(1, count);
    }

    // ---- 操作 ----

    public void invite(final Player from, final String targetName) {
        final Player target;
        if (plugin.chat() != null) {
            target = plugin.chat().findOrTell(from, targetName); // 角色名稱或玩家 ID
            if (target == null) {
                return;
            }
        } else {
            target = plugin.getServer().getPlayerExact(targetName.strip());
            if (target == null) {
                from.sendMessage(plugin.text().prefixed("<red>找不到在線的玩家「" + ItemRenderer.escape(targetName) + "」"));
                return;
            }
        }
        remember(from);
        remember(target);
        final PartyRoster.Result result = roster.invite(from.getUniqueId(), target.getUniqueId(), now());
        switch (result) {
            case OK -> {
                from.sendMessage(plugin.text().prefixed("<gray>邀請了 <white>" + shown(target) + "<gray>（" + config.inviteSeconds() + " 秒內有效）"));
                final String name = display(from);
                target.sendMessage(plugin.text().prefixed("<#7fd4ff>" + name + "<gray> 邀請你加入隊伍　"
                        + "<click:run_command:'/party accept " + name + "'><hover:show_text:'<gray>加入 " + name + " 的隊伍'><green><b>[接受]</b></green></hover></click>"
                        + "　<click:run_command:'/party decline " + name + "'><hover:show_text:'<gray>拒絕'><red>[拒絕]</red></hover></click>"));
                target.playSound(target.getLocation(), Sound.BLOCK_NOTE_BLOCK_PLING, 0.6f, 1.4f);
            }
            case SELF -> from.sendMessage(plugin.text().prefixed("<red>不能邀請自己"));
            case ALREADY_MEMBER -> from.sendMessage(plugin.text().prefixed("<gray>" + shown(target) + " 已經是你的隊員了"));
            case IN_OTHER_PARTY -> from.sendMessage(plugin.text().prefixed("<red>" + shown(target) + " 已經在別的隊伍裡"));
            case NOT_LEADER -> from.sendMessage(plugin.text().prefixed("<red>只有隊長能邀請人"));
            case FULL -> from.sendMessage(plugin.text().prefixed("<red>隊伍滿了（最多 " + roster.maxSize() + " 人）"));
            default -> from.sendMessage(plugin.text().prefixed("<red>沒辦法邀請（" + result + "）"));
        }
    }

    /** 接受邀請（inviterName 為 null 時接受最新的一筆）。 */
    public void accept(final Player player, final String inviterName) {
        final UUID inviter = inviterName == null ? null : findInviter(player, inviterName);
        if (inviterName != null && inviter == null) {
            player.sendMessage(plugin.text().prefixed("<red>" + ItemRenderer.escape(inviterName) + " 沒有邀請你（或邀請過期了）"));
            return;
        }
        remember(player);
        final PartyRoster.Result result = roster.accept(player.getUniqueId(), inviter, now());
        switch (result) {
            case OK -> {
                final PartyRoster.Party party = roster.partyOf(player.getUniqueId());
                for (final Player member : online(party)) {
                    if (member.equals(player)) {
                        member.sendMessage(plugin.text().prefixed("<green>加入了 " + ItemRenderer.escape(name(party.leader()))
                                + " 的隊伍<gray>（" + party.size() + " 人）　<white>/p 訊息<gray> 隊伍聊天"));
                    } else {
                        member.sendMessage(plugin.text().prefixed("<#7fd4ff>" + shown(player) + "<gray> 加入了隊伍（" + party.size() + " 人）"));
                    }
                }
            }
            case ALREADY_IN_PARTY -> player.sendMessage(plugin.text().prefixed("<red>你已經在一個隊伍裡了，先 /party leave"));
            case FULL -> player.sendMessage(plugin.text().prefixed("<red>那個隊伍滿了"));
            case NO_INVITE -> player.sendMessage(plugin.text().prefixed("<red>沒有可以接受的邀請（可能過期了）"));
            default -> player.sendMessage(plugin.text().prefixed("<red>沒辦法加入（" + result + "）"));
        }
    }

    public void decline(final Player player, final String inviterName) {
        final UUID inviter = inviterName == null ? null : findInviter(player, inviterName);
        if (inviterName != null && inviter == null) {
            // 指定的人的邀請已經過期：不要誤拒絕別人的
            player.sendMessage(plugin.text().prefixed("<gray>" + ItemRenderer.escape(inviterName) + " 的邀請已經過期了"));
            return;
        }
        final PartyRoster.Invite invite = roster.decline(player.getUniqueId(), inviter, now());
        if (invite == null) {
            player.sendMessage(plugin.text().prefixed("<gray>沒有要拒絕的邀請"));
            return;
        }
        player.sendMessage(plugin.text().prefixed("<gray>拒絕了 " + ItemRenderer.escape(name(invite.inviter())) + " 的邀請"));
        final Player from = plugin.getServer().getPlayer(invite.inviter());
        if (from != null) {
            from.sendMessage(plugin.text().prefixed("<gray>" + shown(player) + " 拒絕了你的邀請"));
        }
    }

    public void leave(final Player player) {
        final PartyRoster.Departure departure = roster.leave(player.getUniqueId());
        if (departure.result() != PartyRoster.Result.OK) {
            player.sendMessage(plugin.text().prefixed("<gray>你不在隊伍裡"));
            return;
        }
        player.sendMessage(plugin.text().prefixed("<gray>離開了隊伍"));
        announceDeparture(departure, display(player) + " 離開了隊伍");
    }

    public void kick(final Player leader, final String targetName) {
        final UUID target = findMember(leader, targetName);
        if (target == null) {
            leader.sendMessage(plugin.text().prefixed("<red>隊伍裡沒有「" + ItemRenderer.escape(targetName) + "」"));
            return;
        }
        final PartyRoster.Departure departure = roster.kick(leader.getUniqueId(), target);
        switch (departure.result()) {
            case OK -> {
                final String name = name(target);
                final Player kicked = plugin.getServer().getPlayer(target);
                if (kicked != null) {
                    kicked.sendMessage(plugin.text().prefixed("<gray>你被 " + shown(leader) + " 請出了隊伍"));
                }
                announceDeparture(departure, name + " 被請出了隊伍");
            }
            case NOT_LEADER -> leader.sendMessage(plugin.text().prefixed("<red>只有隊長能踢人"));
            case SELF -> leader.sendMessage(plugin.text().prefixed("<red>要離開隊伍用 /party leave"));
            case NOT_IN_PARTY -> leader.sendMessage(plugin.text().prefixed("<gray>你不在隊伍裡"));
            default -> leader.sendMessage(plugin.text().prefixed("<red>隊伍裡沒有這個人"));
        }
    }

    public void promote(final Player leader, final String targetName) {
        final UUID target = findMember(leader, targetName);
        if (target == null) {
            leader.sendMessage(plugin.text().prefixed("<red>隊伍裡沒有「" + ItemRenderer.escape(targetName) + "」"));
            return;
        }
        final PartyRoster.Result result = roster.promote(leader.getUniqueId(), target);
        switch (result) {
            case OK -> {
                for (final Player member : online(roster.partyOf(leader.getUniqueId()))) {
                    member.sendMessage(plugin.text().prefixed("<#7fd4ff>" + ItemRenderer.escape(name(target)) + "<gray> 成為隊長"));
                }
            }
            case NOT_LEADER -> leader.sendMessage(plugin.text().prefixed("<red>只有隊長能交出隊長"));
            case SELF -> leader.sendMessage(plugin.text().prefixed("<gray>你已經是隊長了"));
            case NOT_IN_PARTY -> leader.sendMessage(plugin.text().prefixed("<gray>你不在隊伍裡"));
            default -> leader.sendMessage(plugin.text().prefixed("<red>隊伍裡沒有這個人"));
        }
    }

    /** 隊伍聊天（/p 訊息）。 */
    public void chat(final Player player, final String message) {
        final PartyRoster.Party party = roster.partyOf(player.getUniqueId());
        if (party == null) {
            player.sendMessage(plugin.text().prefixed("<gray>你不在隊伍裡（/party invite 玩家 邀請人）"));
            return;
        }
        // 訊息裡的 [i] 換成手上的物品（聊天模組）
        final net.kyori.adventure.text.Component body = plugin.chat() == null
                ? net.kyori.adventure.text.Component.text(message) : plugin.chat().message(player, message);
        final net.kyori.adventure.text.Component line = net.kyori.adventure.text.Component.text()
                .append(plugin.text().parse("<#7fd4ff>[隊伍] <white>" + shown(player) + "<gray>："))
                .append(body.colorIfAbsent(net.kyori.adventure.text.format.NamedTextColor.GRAY))
                .build();
        for (final Player member : online(party)) {
            member.sendMessage(line);
        }
        plugin.getSLF4JLogger().info("[隊伍] {}：{}", display(player), message);
    }

    /** 每秒：過期的邀請通知雙方。 */
    void tick() {
        rescaleBosses();
        for (final Map.Entry<UUID, List<PartyRoster.Invite>> entry : roster.expire(now()).entrySet()) {
            final Player invitee = plugin.getServer().getPlayer(entry.getKey());
            for (final PartyRoster.Invite invite : entry.getValue()) {
                final Player inviter = plugin.getServer().getPlayer(invite.inviter());
                if (inviter != null) {
                    inviter.sendMessage(plugin.text().prefixed("<gray>給 " + ItemRenderer.escape(name(entry.getKey())) + " 的邀請過期了"));
                }
                if (invitee != null) {
                    invitee.sendMessage(plugin.text().prefixed("<gray>" + ItemRenderer.escape(name(invite.inviter())) + " 的邀請過期了"));
                }
            }
        }
    }

    /** 上線：告訴隊友。 */
    void joined(final Player player) {
        remember(player);
        final PartyRoster.Party party = roster.partyOf(player.getUniqueId());
        if (party == null) {
            return;
        }
        for (final Player member : online(party)) {
            if (member.equals(player)) {
                member.sendMessage(plugin.text().prefixed("<gray>你還在 " + ItemRenderer.escape(name(party.leader())) + " 的隊伍裡（"
                        + party.size() + " 人；/party 看隊員）"));
            } else {
                member.sendMessage(plugin.text().prefixed("<gray>隊友 <white>" + shown(player) + "<gray> 上線了"));
            }
        }
    }

    /** 離線：告訴隊友（人留在隊伍裡）。 */
    void quit(final Player player) {
        final PartyRoster.Party party = roster.partyOf(player.getUniqueId());
        if (party == null) {
            names.remove(player.getUniqueId()); // 名字只留給離線的隊員顯示用
            return;
        }
        for (final Player member : online(party)) {
            if (!member.equals(player)) {
                member.sendMessage(plugin.text().prefixed("<gray>隊友 <white>" + shown(player) + "<gray> 離線了（還在隊伍裡）"));
            }
        }
    }

    void clear() {
        roster.clear();
    }

    // ---- 內部 ----

    private void remember(final Player player) {
        names.put(player.getUniqueId(), display(player));
    }

    /** 別人看到的名字：角色名稱（聊天模組沒啟用時是玩家 ID）。 */
    private String display(final Player player) {
        return plugin.chat() == null ? player.getName() : plugin.chat().display(player);
    }

    /** 同上，MiniMessage 跳脫過。 */
    private String shown(final Player player) {
        return ItemRenderer.escape(display(player));
    }

    /** 名字對得上：角色名稱或玩家 ID（不分大小寫）。 */
    private boolean matches(final UUID player, final String query) {
        final String wanted = query == null ? "" : query.strip();
        if (name(player).equalsIgnoreCase(wanted)) {
            return true;
        }
        final String account = plugin.getServer().getOfflinePlayer(player).getName();
        return account != null && account.equalsIgnoreCase(wanted);
    }

    private void announceDeparture(final PartyRoster.Departure departure, final String what) {
        final PartyRoster.Party party = departure.party();
        for (final UUID uuid : party.members()) {
            final Player member = plugin.getServer().getPlayer(uuid);
            if (member == null) {
                continue;
            }
            if (departure.disbanded()) {
                member.sendMessage(plugin.text().prefixed("<gray>" + ItemRenderer.escape(what) + "，隊伍解散了"));
            } else {
                member.sendMessage(plugin.text().prefixed("<gray>" + ItemRenderer.escape(what) + "（剩 " + party.size() + " 人）"
                        + (departure.newLeader() != null ? "，<#7fd4ff>" + ItemRenderer.escape(name(departure.newLeader())) + "<gray> 成為隊長" : "")));
            }
        }
    }

    /** 隊伍裡在線的人。 */
    public List<Player> online(final PartyRoster.Party party) {
        final List<Player> result = new ArrayList<>();
        if (party == null) {
            return result;
        }
        for (final UUID uuid : party.members()) {
            final Player player = plugin.getServer().getPlayer(uuid);
            if (player != null) {
                result.add(player);
            }
        }
        return result;
    }

    private UUID findInviter(final Player player, final String name) {
        for (final PartyRoster.Invite invite : roster.invitesFor(player.getUniqueId(), now())) {
            if (matches(invite.inviter(), name)) {
                return invite.inviter();
            }
        }
        return null;
    }

    private UUID findMember(final Player player, final String name) {
        final PartyRoster.Party party = roster.partyOf(player.getUniqueId());
        if (party == null) {
            return null;
        }
        for (final UUID uuid : party.members()) {
            if (matches(uuid, name)) {
                return uuid;
            }
        }
        return null;
    }
}
