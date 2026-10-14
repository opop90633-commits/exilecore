package tw.exilecore.discord;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.entity.Player;
import tw.exilecore.ExileCore;
import tw.exilecore.item.ItemRenderer;

import java.security.SecureRandom;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.function.Consumer;

/**
 * Discord 帳號連結與伺服器加成（2026-10）：
 * <ul>
 *   <li>連結：Discord 上的面板按「連結遊戲帳號」，機器人私下給一組代碼（10 分鐘內有效），遊戲裡打 /discord link 代碼。
 *       一個 Discord 帳號只能連一個遊戲帳號。連結記在帳號進度 {@link #KEY}（清進度時留著）。</li>
 *   <li>加成：連結的 Discord 帳號正在加成（Server Boost）這個 Discord 伺服器，就有月卡的功能（傳送免費、裝備組、隨時換昇華…；
 *       不含月卡每天發的月卡幣、時裝幣、免費抽獎箱）。登入、連結、/discord status 時問一次 Discord，
 *       加成開始、結束時 Discord 會即時通知（要開 SERVER MEMBERS INTENT；沒開就只在登入時確認）。</li>
 * </ul>
 */
public final class DiscordLink {

    /** 帳號進度的鍵：連結的 Discord 使用者 ID（sp. 開頭：清進度時留著）。 */
    public static final String KEY = "sp.discord";
    static final String BUTTON = "ecl:link";
    private static final long CODE_MILLIS = 10 * 60_000L;
    private static final String CHARS = "ABCDEFGHJKLMNPQRSTUVWXYZ23456789";

    private record Pending(String userId, String userName, long expires) {
    }

    private final ExileCore plugin;
    private final DiscordRest rest;
    private final SecureRandom random = new SecureRandom();
    private final Map<String, Pending> codes = new ConcurrentHashMap<>();
    /** 線上的人：有沒有在加成（問過 Discord 的）。 */
    private final Map<UUID, Boolean> boosting = new ConcurrentHashMap<>();
    private final ExecutorService worker = Executors.newSingleThreadExecutor(runnable -> {
        final Thread thread = new Thread(runnable, "ExileCore-discord-boost");
        thread.setDaemon(true);
        return thread;
    });
    private volatile boolean enabled;
    private volatile String guildId = "";
    private boolean listening;

    DiscordLink(final ExileCore plugin, final DiscordRest rest) {
        this.plugin = plugin;
        this.rest = rest;
    }

    /** 讀 config.yml 的 discord.boost（沒寫 guild-id 用客服單的）。 */
    void configure(final ConfigurationSection discord) {
        final ConfigurationSection section = discord == null ? null : discord.getConfigurationSection("boost");
        final String own = section == null ? "" : section.getString("guild-id", "").trim();
        final String tickets = discord == null ? "" : discord.getString("tickets.guild-id", "").trim();
        guildId = own.isEmpty() ? tickets : own;
        enabled = (section == null || section.getBoolean("enabled", true)) && !guildId.isEmpty() && rest.hasToken();
        if (!enabled) {
            boosting.clear();
        }
    }

    /** 開始：登入（帳號資料載入好）時問一次 Discord；已經在線上的（/ec reload）現在問。 */
    void start() {
        if (!enabled) {
            return;
        }
        if (!listening && plugin.bounties() != null) {
            listening = true;
            plugin.bounties().addReadyListener(this::onReady);
        }
        for (final Player player : plugin.getServer().getOnlinePlayers()) {
            onReady(player);
        }
    }

    void stop() {
        // 沒有定時工作（2026-10：只在登入、連結、/discord status 時問 Discord）
    }

    /** 有開（指令用）。 */
    public boolean enabledForCommands() {
        return enabled;
    }

    // ---- 加成 ----

    /** 這個人連結的 Discord 帳號正在加成伺服器（問過 Discord 的結果；沒開、沒連結、還沒問過都是 false）。 */
    public boolean boosting(final Player player) {
        return enabled && Boolean.TRUE.equals(boosting.get(player.getUniqueId()));
    }

    /** 連結的 Discord 使用者 ID；沒有 null。 */
    public String linked(final Player player) {
        final String id = plugin.bounties() == null ? null : plugin.bounties().progressValue(player, KEY);
        return id == null || id.isBlank() ? null : id;
    }

    /** 登入（換角色）時：問一次 Discord 有沒有在加成（加成中途結束的，下次登入就沒有了）。 */
    private void onReady(final Player player) {
        if (!enabled) {
            return;
        }
        final String userId = linked(player);
        if (userId == null) {
            boosting.remove(player.getUniqueId());
            return;
        }
        check(player.getUniqueId(), userId, null);
    }

    /** 問 Discord 這個使用者有沒有在加成（背景執行緒），問到了在主執行緒更新；done 收結果（問不到是 null）。 */
    private void check(final UUID account, final String userId, final Consumer<Boolean> done) {
        final String guild = guildId;
        worker.execute(() -> {
            final DiscordRest.Result result = rest.call("GET", "/guilds/" + guild + "/members/" + userId, null);
            Boolean value = null;
            if (result != null && result.ok()) {
                final JsonElement json = result.json();
                value = json.isJsonObject() && json.getAsJsonObject().has("premium_since")
                        && !json.getAsJsonObject().get("premium_since").isJsonNull();
            } else if (result != null && result.status() == 404) {
                value = false; // 不在這個 Discord 伺服器
            }
            final Boolean answer = value;
            plugin.getServer().getScheduler().runTask(plugin, () -> {
                if (answer != null) {
                    final Boolean before = boosting.put(account, answer);
                    final Player player = plugin.getServer().getPlayer(account);
                    if (player != null && !answer.equals(before) && (before != null || answer)) {
                        changed(player, answer);
                    }
                }
                if (done != null) {
                    done.accept(answer);
                }
            });
        });
    }

    private void changed(final Player player, final boolean now) {
        player.sendMessage(plugin.text().prefixed(now
                ? "<#ff73fa>Discord 伺服器加成中：<gray>你有月卡的功能（傳送免費、裝備組、隨時換昇華…），加成結束就自動停止。感謝支持！"
                : "<gray>Discord 伺服器加成結束了：月卡的功能跟著停止（有買月卡的照月卡算）"));
        plugin.players().updateListName(player); // 名字旁的月亮
        plugin.players().markDirty(player);
    }

    // ---- 連結（遊戲這邊，主執行緒）----

    /** /discord link 代碼。 */
    public void link(final Player player, final String raw) {
        if (!enabled) {
            player.sendMessage(plugin.text().prefixed("<red>Discord 連結現在沒有開放"));
            return;
        }
        if (plugin.bounties() == null || !plugin.bounties().accountLoaded(player)) {
            player.sendMessage(plugin.text().prefixed("<red>帳號資料載入中，請稍等"));
            return;
        }
        final String code = raw == null ? "" : raw.trim().toUpperCase(java.util.Locale.ROOT);
        final Pending pending = codes.get(code);
        if (pending == null || pending.expires() < System.currentTimeMillis()) {
            player.sendMessage(plugin.text().prefixed("<red>代碼不對或過期了：到 Discord 的面板按「連結遊戲帳號」拿一組新的（10 分鐘內有效）"));
            return;
        }
        if (pending.userId().equals(linked(player))) {
            codes.remove(code);
            player.sendMessage(plugin.text().prefixed("<gray>已經連結這個 Discord 帳號了"));
            return;
        }
        final UUID account = player.getUniqueId();
        // 一個 Discord 帳號只能連一個遊戲帳號：先查資料庫
        plugin.database().async(connection -> {
            try (PreparedStatement statement = connection.prepareStatement(
                    "SELECT account_uuid FROM account_progress WHERE progress_key = ? AND progress_value = ?")) {
                statement.setString(1, KEY);
                statement.setString(2, pending.userId());
                try (ResultSet result = statement.executeQuery()) {
                    while (result.next()) {
                        if (!result.getString(1).equals(account.toString())) {
                            return result.getString(1);
                        }
                    }
                }
            }
            return null;
        }).whenComplete((other, error) -> plugin.getServer().getScheduler().runTask(plugin, () -> {
            final Player online = plugin.getServer().getPlayer(account);
            if (online == null) {
                return;
            }
            if (error != null) {
                online.sendMessage(plugin.text().prefixed("<red>資料庫忙碌中，請稍後再試"));
                return;
            }
            if (other != null) {
                online.sendMessage(plugin.text().prefixed("<red>這個 Discord 帳號已經連結到別的遊戲帳號了（要換的話先在那個帳號打 /discord unlink）"));
                return;
            }
            if (codes.remove(code) == null) {
                online.sendMessage(plugin.text().prefixed("<red>代碼已經用掉了，請重新拿一組"));
                return;
            }
            if (!plugin.bounties().commitAccount(online, Map.of(KEY, pending.userId()), "連結 Discord")) {
                online.sendMessage(plugin.text().prefixed("<red>帳號資料載入中，請稍等"));
                return;
            }
            boosting.remove(account);
            online.sendMessage(plugin.text().prefixed("<green>已連結 Discord 帳號 <white>" + ItemRenderer.escape(pending.userName())
                    + "</white><gray>。在 Discord 伺服器加成（Server Boost）期間就有月卡的功能，正在確認加成狀態…"));
            plugin.getSLF4JLogger().info("Discord 連結：{} ↔ {}（{}）", online.getName(), pending.userName(), pending.userId());
            check(account, pending.userId(), answer -> {
                final Player again = plugin.getServer().getPlayer(account);
                if (again != null && answer != null && !answer) {
                    again.sendMessage(plugin.text().prefixed("<gray>目前沒有在加成：加成之後幾分鐘內就會生效（不用重新連結）"));
                }
            });
        }));
    }

    /** /discord unlink。 */
    public void unlink(final Player player) {
        if (linked(player) == null) {
            player.sendMessage(plugin.text().prefixed("<gray>你沒有連結 Discord 帳號"));
            return;
        }
        final Map<String, String> values = new HashMap<>();
        values.put(KEY, null);
        if (!plugin.bounties().commitAccount(player, values, "取消 Discord 連結")) {
            player.sendMessage(plugin.text().prefixed("<red>帳號資料載入中，請稍等"));
            return;
        }
        final Boolean was = boosting.remove(player.getUniqueId());
        player.sendMessage(plugin.text().prefixed("<gray>已取消 Discord 連結"));
        if (Boolean.TRUE.equals(was)) {
            changed(player, false);
        }
    }

    /** /discord status。 */
    public void status(final Player player) {
        final String userId = linked(player);
        if (userId == null) {
            player.sendMessage(plugin.text().prefixed("<gray>還沒連結 Discord：到 Discord 的面板按「連結遊戲帳號」拿代碼，再打 <white>/discord link 代碼"));
            return;
        }
        final UUID account = player.getUniqueId();
        check(account, userId, answer -> {
            final Player online = plugin.getServer().getPlayer(account);
            if (online != null) {
                online.sendMessage(plugin.text().prefixed("<gray>已連結 Discord（ID " + userId + "）　伺服器加成："
                        + (answer == null ? "<white>現在問不到 Discord，稍後再試" : answer ? "<#ff73fa>加成中（有月卡的功能）" : "<white>沒有")));
            }
        });
    }

    // ---- Discord 這邊（Gateway 執行緒）----

    /**
     * 成員資料變了（GUILD_MEMBER_UPDATE／REMOVE，要開 SERVER MEMBERS INTENT）：加成開始、結束即時生效。
     * 只管線上、連結了這個 Discord 帳號的人（離線的下次登入再問）。
     */
    void onMemberUpdate(final JsonObject data, final boolean removed) {
        if (!enabled || data == null || !data.has("user") || !data.has("guild_id") || !guildId.equals(data.get("guild_id").getAsString())) {
            return;
        }
        final String userId = data.getAsJsonObject("user").get("id").getAsString();
        final boolean now = !removed && data.has("premium_since") && !data.get("premium_since").isJsonNull();
        plugin.getServer().getScheduler().runTask(plugin, () -> {
            for (final Player player : plugin.getServer().getOnlinePlayers()) {
                if (!userId.equals(linked(player))) {
                    continue;
                }
                final Boolean before = boosting.put(player.getUniqueId(), now);
                if (!Boolean.valueOf(now).equals(before) && (before != null || now)) {
                    changed(player, now);
                }
            }
        });
    }

    /** 面板的「連結遊戲帳號」按鈕；是這個按鈕回傳 true。 */
    boolean onInteraction(final JsonObject interaction) {
        final JsonObject data = interaction.has("data") ? interaction.getAsJsonObject("data") : null;
        if (data == null || !data.has("custom_id") || !BUTTON.equals(data.get("custom_id").getAsString())) {
            return false;
        }
        final JsonObject member = interaction.has("member") ? interaction.getAsJsonObject("member") : null;
        final JsonObject user = member != null ? member.getAsJsonObject("user")
                : interaction.has("user") ? interaction.getAsJsonObject("user") : null;
        final String id = interaction.get("id").getAsString();
        final String token = interaction.get("token").getAsString();
        worker.execute(() -> {
            if (user == null) {
                return;
            }
            final String userId = user.get("id").getAsString();
            final String userName = user.has("global_name") && !user.get("global_name").isJsonNull()
                    ? user.get("global_name").getAsString() : user.get("username").getAsString();
            final long now = System.currentTimeMillis();
            codes.values().removeIf(pending -> pending.userId().equals(userId) || pending.expires() < now); // 一個人同時只有一組
            final String code = newCode();
            codes.put(code, new Pending(userId, userName, System.currentTimeMillis() + CODE_MILLIS));
            final JsonObject reply = new JsonObject();
            reply.addProperty("content", "你的連結代碼：**" + code + "**（10 分鐘內有效，只有你看得到）\n"
                    + "在遊戲裡打 `/discord link " + code + "` 就連好了。\n"
                    + "連結之後，在這個 Discord 伺服器加成（Server Boost）期間，遊戲帳號就有月卡的功能；加成結束自動停止。");
            reply.addProperty("flags", 64);
            final JsonObject body = new JsonObject();
            body.addProperty("type", 4);
            body.add("data", reply);
            rest.call("POST", "/interactions/" + id + "/" + token + "/callback", body);
        });
        return true;
    }

    private String newCode() {
        String code;
        do {
            final StringBuilder text = new StringBuilder();
            for (int i = 0; i < 6; i++) {
                text.append(CHARS.charAt(random.nextInt(CHARS.length())));
            }
            code = text.toString();
        } while (codes.containsKey(code));
        return code;
    }

    /** （管理員）把「連結遊戲帳號」的面板發到 Discord 頻道。 */
    public void postPanel(final String channelId, final Consumer<String> reply) {
        worker.execute(() -> {
            final JsonObject embed = new JsonObject();
            embed.addProperty("title", "連結遊戲帳號");
            embed.addProperty("description", "按下面的按鈕拿一組代碼，在遊戲裡打 `/discord link 代碼` 就連好了。\n\n"
                    + "💎 連結之後，在這個 Discord 伺服器加成（Server Boost）期間，你的遊戲帳號就有**月卡的功能**"
                    + "（傳送免費、裝備組與天賦組、隨時換昇華、市集免手續費…；不含月卡每天發的幣與抽獎箱），加成結束自動停止。");
            embed.addProperty("color", 0xFF73FA);
            final JsonArray embeds = new JsonArray();
            embeds.add(embed);
            final JsonObject button = new JsonObject();
            button.addProperty("type", 2);
            button.addProperty("style", 1);
            button.addProperty("label", "連結遊戲帳號");
            button.addProperty("custom_id", BUTTON);
            final JsonObject emoji = new JsonObject();
            emoji.addProperty("name", "🔗");
            button.add("emoji", emoji);
            final JsonArray buttons = new JsonArray();
            buttons.add(button);
            final JsonObject row = new JsonObject();
            row.addProperty("type", 1);
            row.add("components", buttons);
            final JsonArray rows = new JsonArray();
            rows.add(row);
            final JsonObject message = new JsonObject();
            message.add("embeds", embeds);
            message.add("components", rows);
            final DiscordRest.Result result = rest.call("POST", "/channels/" + channelId + "/messages", message);
            reply.accept(result != null && result.ok() ? "<green>連結面板發好了" : "<red>發不出去：機器人有沒有在那個頻道、有沒有發訊息的權限？");
        });
    }
}
