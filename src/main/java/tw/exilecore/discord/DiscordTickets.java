package tw.exilecore.discord;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.reflect.TypeToken;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.entity.Player;
import tw.exilecore.ExileCore;
import tw.exilecore.character.CharacterRecord;
import tw.exilecore.character.PlayerState;
import tw.exilecore.item.ItemRenderer;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * 客服單（2026-10，不用 Ticket Tool）：
 * <ul>
 *   <li>Discord：管理員在遊戲裡 /ticket panel 面板 頻道ID 發出面板（卡片＋按鈕）；玩家按按鈕 → 跳出表單 → 填完開一個只有他和客服看得到的頻道，
 *       表單內容貼在裡面，下面有「認領」「關閉」。關閉時整段對話存成文字檔送到紀錄頻道，頻道刪掉。</li>
 *   <li>遊戲：/ticket 內容 開單（自動附上角色、等級、職業、位置、贊助狀況）；已經有開著的單就把話接到那張單。
 *       客服在那個頻道說的話轉到遊戲給他（不在線的話下次上線收到）；/ticket close 自己關掉。</li>
 *   <li>單的資料存在 plugins/ExileCore/tickets.json。設定在 config.yml 的 discord.tickets。</li>
 * </ul>
 */
public final class DiscordTickets {

    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().disableHtmlEscaping().create();
    private static final long VIEW = 1L << 10;
    private static final long SEND = 1L << 11;
    private static final long MANAGE_MESSAGES = 1L << 13;
    private static final long EMBED = 1L << 14;
    private static final long ATTACH = 1L << 15;
    private static final long HISTORY = 1L << 16;
    private static final long MANAGE_CHANNELS = 1L << 4;
    private static final long MEMBER = VIEW | SEND | EMBED | ATTACH | HISTORY;
    private static final DateTimeFormatter TIME = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm").withZone(ZoneId.of("Asia/Taipei"));

    /** 一個面板。 */
    record Panel(String id, String title, String description, String button, String emoji, int style, int color, String prefix,
                 List<String> staffRoles, String welcome, List<Question> questions) {
    }

    record Question(String label, String placeholder, boolean paragraph, boolean required) {
    }

    /** 一張單（存檔用，欄位公開給 Gson）。 */
    static final class Ticket {
        int number;
        String channel;
        String panel;
        String user;        // Discord 使用者 ID（遊戲開的是 null）
        String userName;
        String mc;          // 遊戲帳號 UUID（Discord 開的是 null）
        String mcName;
        String claimedBy;
        long created;
        List<String> pending = new ArrayList<>(); // 不在線時客服說的話
    }

    private static final class Store {
        int next = 1;
        List<Ticket> tickets = new ArrayList<>();
    }

    private final ExileCore plugin;
    private final DiscordRest rest;
    /** 背景工作（Discord 互動要 3 秒內回，所以不排隊：每件事自己一條執行緒；存檔與單的資料用 synchronized 保護）。 */
    private final ExecutorService worker = Executors.newCachedThreadPool(runnable -> {
        final Thread thread = new Thread(runnable, "ExileCore-discord-tickets");
        thread.setDaemon(true);
        return thread;
    });
    private final Map<String, Long> cooldown = new HashMap<>();
    private Store store = new Store();

    boolean enabled;
    private String guildId = "";
    private String categoryId = "";
    private String logChannelId = "";
    private List<String> staffRoles = List.of();
    private int maxOpen = 1;
    private String gamePanel = "general";
    private final Map<String, Panel> panels = new LinkedHashMap<>();
    private volatile DiscordGateway gateway;

    DiscordTickets(final ExileCore plugin, final DiscordRest rest) {
        this.plugin = plugin;
        this.rest = rest;
        load();
    }

    void gateway(final DiscordGateway gateway) {
        this.gateway = gateway;
    }

    void stop() {
        worker.shutdown();
    }

    // ---- 設定 ----

    void configure(final ConfigurationSection discord) {
        panels.clear();
        final ConfigurationSection section = discord == null ? null : discord.getConfigurationSection("tickets");
        enabled = section != null && section.getBoolean("enabled", false);
        if (section == null) {
            return;
        }
        guildId = section.getString("guild-id", "").trim();
        categoryId = section.getString("category-id", "").trim();
        logChannelId = section.getString("log-channel-id", "").trim();
        staffRoles = trimmed(section.getStringList("staff-role-ids"));
        maxOpen = Math.max(1, section.getInt("max-open-per-user", 1));
        gamePanel = section.getString("game-panel", "general");
        final ConfigurationSection list = section.getConfigurationSection("panels");
        if (list != null) {
            for (final String id : list.getKeys(false)) {
                final ConfigurationSection p = list.getConfigurationSection(id);
                if (p == null) {
                    continue;
                }
                final List<Question> questions = new ArrayList<>();
                for (final Map<?, ?> q : p.getMapList("questions")) {
                    final Object label = q.get("label");
                    if (label == null) {
                        continue;
                    }
                    final Object placeholder = q.get("placeholder");
                    final Object paragraph = q.get("paragraph");
                    final Object required = q.get("required");
                    questions.add(new Question(cut(String.valueOf(label), 45), placeholder == null ? "" : cut(String.valueOf(placeholder), 100),
                            Boolean.TRUE.equals(paragraph), required == null || Boolean.TRUE.equals(required)));
                    if (questions.size() == 5) {
                        break; // Discord 的表單最多 5 格
                    }
                }
                final List<String> roles = trimmed(p.getStringList("staff-role-ids"));
                panels.put(id, new Panel(id, p.getString("title", id), p.getString("description", ""), cut(p.getString("button", "開啟客服單"), 80),
                        p.getString("emoji", ""), style(p.getString("style", "primary")), color(p.getString("color", "#5865F2")),
                        p.getString("name-prefix", id), roles.isEmpty() ? staffRoles : roles,
                        p.getString("welcome", "感謝你的回報！客服會盡快回覆你。"), questions));
            }
        }
        if (enabled && (guildId.isEmpty() || panels.isEmpty())) {
            plugin.getSLF4JLogger().warn("[Discord] 客服單開了但 guild-id 或 panels 沒設定");
        }
    }

    private static List<String> trimmed(final List<String> list) {
        final List<String> out = new ArrayList<>();
        for (final String each : list) {
            if (each != null && !each.isBlank()) {
                out.add(each.trim());
            }
        }
        return out;
    }

    private static int style(final String name) {
        return switch (name.toLowerCase(Locale.ROOT)) {
            case "secondary", "gray", "grey" -> 2;
            case "success", "green" -> 3;
            case "danger", "red" -> 4;
            default -> 1;
        };
    }

    private static int color(final String hex) {
        try {
            return Integer.parseInt(hex.replace("#", "").trim(), 16);
        } catch (final NumberFormatException e) {
            return 0x5865F2;
        }
    }

    private static String cut(final String text, final int max) {
        return text.length() > max ? text.substring(0, max) : text;
    }

    Set<String> panelIds() {
        return panels.keySet();
    }

    // ---- 存檔 ----

    private Path file() {
        return plugin.getDataFolder().toPath().resolve("tickets.json");
    }

    private synchronized void load() {
        try {
            if (Files.exists(file())) {
                final Store loaded = GSON.fromJson(Files.readString(file(), StandardCharsets.UTF_8), new TypeToken<Store>() { }.getType());
                if (loaded != null) {
                    store = loaded;
                    if (store.tickets == null) {
                        store.tickets = new ArrayList<>();
                    }
                }
            }
        } catch (final Exception e) {
            plugin.getSLF4JLogger().warn("[Discord] tickets.json 讀不到：{}", e.toString());
        }
    }

    private synchronized void save() {
        try {
            Files.createDirectories(file().getParent());
            Files.writeString(file(), GSON.toJson(store), StandardCharsets.UTF_8);
        } catch (final Exception e) {
            plugin.getSLF4JLogger().warn("[Discord] tickets.json 存不了：{}", e.toString());
        }
    }

    private synchronized Ticket byChannel(final String channel) {
        for (final Ticket ticket : store.tickets) {
            if (ticket.channel != null && ticket.channel.equals(channel)) {
                return ticket;
            }
        }
        return null;
    }

    private synchronized List<Ticket> openBy(final String user, final String mc) {
        final List<Ticket> out = new ArrayList<>();
        for (final Ticket ticket : store.tickets) {
            if (user != null && user.equals(ticket.user) || mc != null && mc.equals(ticket.mc)) {
                out.add(ticket);
            }
        }
        return out;
    }

    private synchronized Ticket create(final String panel) {
        final Ticket ticket = new Ticket();
        ticket.number = store.next++;
        ticket.panel = panel;
        ticket.created = System.currentTimeMillis();
        return ticket;
    }

    private synchronized void add(final Ticket ticket) {
        store.tickets.add(ticket);
        save();
    }

    private synchronized void remove(final Ticket ticket) {
        store.tickets.remove(ticket);
        save();
    }

    // ---- 面板 ----

    /** 把面板發到頻道（遊戲裡的管理員指令；結果回報給 sender）。 */
    void postPanel(final String panelId, final String channelId, final java.util.function.Consumer<String> reply) {
        final Panel panel = panels.get(panelId);
        if (panel == null) {
            reply.accept("<red>沒有這個面板：" + ItemRenderer.escape(panelId) + "（config.yml 的 discord.tickets.panels）");
            return;
        }
        worker.execute(() -> {
            final JsonObject embed = new JsonObject();
            embed.addProperty("title", panel.title());
            embed.addProperty("description", panel.description());
            embed.addProperty("color", panel.color());
            final JsonObject footer = new JsonObject();
            footer.addProperty("text", "星骸客服");
            embed.add("footer", footer);
            final JsonObject message = new JsonObject();
            message.add("embeds", array(embed));
            message.add("components", array(row(button(panel.style(), panel.button(), panel.emoji(), "ect:open:" + panel.id()))));
            final DiscordRest.Result result = rest.call("POST", "/channels/" + channelId + "/messages", message);
            reply.accept(result != null && result.ok() ? "<green>面板「" + ItemRenderer.escape(panel.title()) + "」發出去了"
                    : "<red>發不出去（主控台有原因；機器人要看得到、能發言在那個頻道）");
        });
    }

    // ---- Discord 的事件（Gateway 執行緒） ----

    void onInteraction(final JsonObject interaction) {
        final int type = interaction.get("type").getAsInt();
        final JsonObject data = interaction.has("data") ? interaction.getAsJsonObject("data") : new JsonObject();
        final String custom = data.has("custom_id") ? data.get("custom_id").getAsString() : "";
        if (!custom.startsWith("ect:")) {
            return;
        }
        final String id = interaction.get("id").getAsString();
        final String token = interaction.get("token").getAsString();
        final JsonObject member = interaction.has("member") ? interaction.getAsJsonObject("member") : null;
        final JsonObject user = member != null ? member.getAsJsonObject("user")
                : interaction.has("user") ? interaction.getAsJsonObject("user") : null;
        if (user == null) {
            return;
        }
        final String userId = user.get("id").getAsString();
        final String userName = user.has("global_name") && !user.get("global_name").isJsonNull()
                ? user.get("global_name").getAsString() : user.get("username").getAsString();
        final List<String> roles = new ArrayList<>();
        if (member != null && member.has("roles")) {
            for (final JsonElement role : member.getAsJsonArray("roles")) {
                roles.add(role.getAsString());
            }
        }
        if (member != null && member.has("permissions")) { // 2026-10：有「管理者」權限的（含伺服器擁有者）也算客服
            try {
                if ((new java.math.BigInteger(member.get("permissions").getAsString()).longValue() & ADMINISTRATOR) != 0) {
                    roles.add(ADMIN_MARK);
                }
            } catch (final RuntimeException ignored) {
                // 權限欄位看不懂就只看身分組
            }
        }
        final String[] parts = custom.split(":", 3);
        worker.execute(() -> {
            if (type == 3 && "open".equals(parts[1]) && parts.length == 3) {
                openForm(id, token, parts[2], userId);
            } else if (type == 5 && "form".equals(parts[1]) && parts.length == 3) {
                submitForm(id, token, interaction, parts[2], userId, userName, data);
            } else if (type == 3 && "claim".equals(parts[1])) {
                claim(id, token, interaction.get("channel_id").getAsString(), userId, userName, roles);
            } else if (type == 3 && "close".equals(parts[1])) {
                close(id, token, interaction.get("channel_id").getAsString(), userId, userName, roles);
            }
        });
    }

    /** 客服單頻道裡有人說話：連到遊戲的單轉給那個玩家。 */
    void onMessage(final JsonObject message) {
        final String channel = message.get("channel_id").getAsString();
        final Ticket ticket = byChannel(channel);
        if (ticket == null || ticket.mc == null) {
            return;
        }
        final JsonObject author = message.getAsJsonObject("author");
        if (author == null || message.has("webhook_id") || author.has("bot") && author.get("bot").getAsBoolean()) {
            return;
        }
        String content = message.has("content") && !message.get("content").isJsonNull() ? message.get("content").getAsString() : "";
        if (message.has("attachments") && message.getAsJsonArray("attachments").size() > 0) {
            content = (content + " [附件]").trim();
        }
        content = content.replaceAll("<a?:(\\w+):\\d+>", ":$1:").replaceAll("\\s+", " ").trim();
        if (content.isEmpty()) {
            return;
        }
        final String name = author.has("global_name") && !author.get("global_name").isJsonNull()
                ? author.get("global_name").getAsString() : author.get("username").getAsString();
        final String line = "<#5865f2>[客服單 #" + ticket.number + "]</#5865f2> <white>" + ItemRenderer.escape(name) + "</white><gray>: </gray>"
                + ItemRenderer.escape(cut(content, 400));
        plugin.getServer().getScheduler().runTask(plugin, () -> {
            final Player player = plugin.getServer().getPlayer(UUID.fromString(ticket.mc));
            if (player != null) {
                player.sendMessage(plugin.text().parse(line));
                player.sendMessage(plugin.text().parse("<dark_gray>　回覆：/ticket 訊息　關掉：/ticket close"));
                player.playSound(player.getLocation(), org.bukkit.Sound.BLOCK_NOTE_BLOCK_BELL, 0.8f, 1.4f);
            } else {
                synchronized (this) {
                    if (ticket.pending.size() < 30) {
                        ticket.pending.add(line);
                        save();
                    }
                }
            }
        });
    }

    /** 上線時把不在線時客服說的話給他（主執行緒）。 */
    void deliverPending(final Player player) {
        final List<String> lines = new ArrayList<>();
        synchronized (this) {
            for (final Ticket ticket : openBy(null, player.getUniqueId().toString())) {
                lines.addAll(ticket.pending);
                ticket.pending.clear();
            }
            if (!lines.isEmpty()) {
                save();
            }
        }
        if (!lines.isEmpty()) {
            player.sendMessage(plugin.text().prefixed("<#5865f2>你不在的時候，客服回覆了你的客服單："));
            for (final String line : lines) {
                player.sendMessage(plugin.text().parse(line));
            }
            player.sendMessage(plugin.text().parse("<dark_gray>　回覆：/ticket 訊息　關掉：/ticket close"));
        }
    }

    private void respond(final String id, final String token, final JsonObject body) {
        rest.call("POST", "/interactions/" + id + "/" + token + "/callback", body);
    }

    private void ephemeral(final String id, final String token, final String text) {
        final JsonObject data = new JsonObject();
        data.addProperty("content", text);
        data.addProperty("flags", 64);
        final JsonObject body = new JsonObject();
        body.addProperty("type", 4);
        body.add("data", data);
        respond(id, token, body);
    }

    private void editOriginal(final String token, final String text) {
        final DiscordGateway gw = gateway;
        if (gw == null || gw.applicationId == null) {
            return;
        }
        final JsonObject body = new JsonObject();
        body.addProperty("content", text);
        rest.call("PATCH", "/webhooks/" + gw.applicationId + "/" + token + "/messages/@original", body);
    }

    private void openForm(final String id, final String token, final String panelId, final String userId) {
        final Panel panel = panels.get(panelId);
        if (!enabled || panel == null) {
            ephemeral(id, token, "客服單現在沒有開放。");
            return;
        }
        if (openBy(userId, null).size() >= maxOpen) {
            ephemeral(id, token, "你已經有開著的客服單了，請到那個頻道繼續說明（或請客服關掉後再開新的）。");
            return;
        }
        if (panel.questions().isEmpty()) {
            // 沒有表單：直接開
            final JsonObject defer = new JsonObject();
            defer.addProperty("type", 5);
            final JsonObject flags = new JsonObject();
            flags.addProperty("flags", 64);
            defer.add("data", flags);
            respond(id, token, defer);
            openTicket(panel, userId, null, null, null, new LinkedHashMap<>(), token);
            return;
        }
        final JsonArray rows = new JsonArray();
        for (int i = 0; i < panel.questions().size(); i++) {
            final Question question = panel.questions().get(i);
            final JsonObject input = new JsonObject();
            input.addProperty("type", 4);
            input.addProperty("custom_id", "q" + i);
            input.addProperty("label", question.label());
            input.addProperty("style", question.paragraph() ? 2 : 1);
            input.addProperty("required", question.required());
            input.addProperty("max_length", question.paragraph() ? 1500 : 200);
            if (!question.placeholder().isEmpty()) {
                input.addProperty("placeholder", question.placeholder());
            }
            rows.add(row(input));
        }
        final JsonObject data = new JsonObject();
        data.addProperty("custom_id", "ect:form:" + panel.id());
        data.addProperty("title", cut(panel.title(), 45));
        data.add("components", rows);
        final JsonObject body = new JsonObject();
        body.addProperty("type", 9);
        body.add("data", data);
        respond(id, token, body);
    }

    private void submitForm(final String id, final String token, final JsonObject interaction, final String panelId, final String userId,
                            final String userName, final JsonObject data) {
        final Panel panel = panels.get(panelId);
        if (!enabled || panel == null) {
            ephemeral(id, token, "客服單現在沒有開放。");
            return;
        }
        if (openBy(userId, null).size() >= maxOpen) {
            ephemeral(id, token, "你已經有開著的客服單了。");
            return;
        }
        final JsonObject defer = new JsonObject();
        defer.addProperty("type", 5);
        final JsonObject flags = new JsonObject();
        flags.addProperty("flags", 64);
        defer.add("data", flags);
        respond(id, token, defer);
        final Map<String, String> answers = new LinkedHashMap<>();
        if (data.has("components")) {
            for (final JsonElement rowElement : data.getAsJsonArray("components")) {
                for (final JsonElement inputElement : rowElement.getAsJsonObject().getAsJsonArray("components")) {
                    final JsonObject input = inputElement.getAsJsonObject();
                    final int index = Integer.parseInt(input.get("custom_id").getAsString().substring(1));
                    final String value = input.has("value") && !input.get("value").isJsonNull() ? input.get("value").getAsString() : "";
                    if (index < panel.questions().size() && !value.isBlank()) {
                        answers.put(panel.questions().get(index).label(), value);
                    }
                }
            }
        }
        openTicket(panel, userId, userName, null, null, answers, token);
    }

    /** Discord 的「管理者」權限位元。 */
    private static final long ADMINISTRATOR = 1L << 3;
    /** 互動的人有管理者權限時放進身分組清單的記號（不是真的身分組 ID）。 */
    private static final String ADMIN_MARK = "*administrator";

    /** 建頻道失敗的原因（照 Discord 回的錯誤代碼翻成設定上要檢查的地方）。 */
    private static String failReason(final DiscordRest.Result result) {
        if (result == null) {
            return "連不上 Discord";
        }
        final String body = result.body() == null ? "" : result.body();
        int code = 0;
        try {
            final JsonElement json = result.json();
            if (json.isJsonObject() && json.getAsJsonObject().has("code")) {
                code = json.getAsJsonObject().get("code").getAsInt();
            }
        } catch (final RuntimeException ignored) {
            // 看不懂就照 HTTP 狀態碼
        }
        if (body.contains("parent_id")) {
            return "config 的 category-id 不是這個伺服器的「分類」——要填分類的 ID，不是頻道的";
        }
        if (body.contains("permission_overwrites")) {
            return "staff-role-ids 裡有不是這個伺服器身分組的 ID";
        }
        return switch (code) {
            case 50013 -> "機器人缺少「管理頻道」「管理身分組」權限，或機器人的身分組排得比客服身分組低";
            case 50001 -> "機器人看不到 category-id 那個分類（分類的權限把機器人擋掉了）";
            case 10003 -> "找不到 category-id 那個分類";
            case 10004 -> "找不到 guild-id 那個伺服器（機器人沒加進去，或 ID 填錯）";
            case 10011 -> "staff-role-ids 裡有找不到的身分組";
            case 30013 -> "這個伺服器的頻道數量已經滿了（500 個）";
            case 50035 -> "設定的 ID 格式不對：Discord 回 " + cut(body, 160);
            default -> "Discord 回 HTTP " + result.status() + (code != 0 ? "、錯誤代碼 " + code : "");
        };
    }

    /**
     * 開一張單（背景執行緒）：建頻道（權限：所有人看不到；開單的人、客服身分組、機器人看得到），貼歡迎訊息、表單內容、按鈕。
     * discordUser 或 mcPlayer 其中一個；token 是 Discord 互動的（回覆「開好了」用），遊戲開的是 null。
     */
    private Ticket openTicket(final Panel panel, final String discordUser, final String discordName, final String mcUuid, final String mcName,
                              final Map<String, String> answers, final String token) {
        // 2026-10：先佔一個位子再建頻道（建頻道要等 Discord，兩次同時送出以前都會過「最多幾張」的檢查）
        final String key = discordUser != null ? "d:" + discordUser : "m:" + mcUuid;
        synchronized (this) {
            final int open = (discordUser != null ? openBy(discordUser, null) : openBy(null, mcUuid)).size();
            if (opening.contains(key) || open >= maxOpen) {
                if (token != null) {
                    editOriginal(token, "你已經有開著（或正在開）的客服單了。");
                }
                return null;
            }
            opening.add(key);
        }
        try {
            return openTicketNow(panel, discordUser, discordName, mcUuid, mcName, answers, token);
        } finally {
            synchronized (this) {
                opening.remove(key);
            }
        }
    }

    /** 正在建頻道的人（d:Discord 帳號、m:遊戲 UUID；鎖 this）。 */
    private final java.util.Set<String> opening = new java.util.HashSet<>();

    private Ticket openTicketNow(final Panel panel, final String discordUser, final String discordName, final String mcUuid, final String mcName,
                                 final Map<String, String> answers, final String token) {
        final DiscordGateway gw = gateway;
        final Ticket ticket = create(panel.id());
        final JsonArray overwrites = new JsonArray();
        overwrites.add(overwrite(guildId, 0, 0, VIEW));
        for (final String role : panel.staffRoles()) {
            overwrites.add(overwrite(role, 0, MEMBER | MANAGE_MESSAGES, 0));
        }
        if (discordUser != null) {
            overwrites.add(overwrite(discordUser, 1, MEMBER, 0));
        }
        if (gw != null && gw.selfId != null) {
            overwrites.add(overwrite(gw.selfId, 1, MEMBER | MANAGE_MESSAGES | MANAGE_CHANNELS, 0));
        }
        final JsonObject channel = new JsonObject();
        channel.addProperty("name", cut(panel.prefix() + "-" + String.format(Locale.ROOT, "%04d", ticket.number), 90));
        channel.addProperty("type", 0);
        if (!categoryId.isEmpty()) {
            channel.addProperty("parent_id", categoryId);
        }
        channel.addProperty("topic", "客服單 #" + ticket.number + "｜" + panel.title() + "｜"
                + (discordName != null ? discordName : "遊戲：" + mcName));
        channel.add("permission_overwrites", overwrites);
        final DiscordRest.Result created = rest.call("POST", "/guilds/" + guildId + "/channels", channel);
        if (created == null || !created.ok()) {
            if (token != null) {
                editOriginal(token, "開不了客服單（" + failReason(created) + "），請直接聯絡管理員。");
            }
            return null;
        }
        ticket.channel = created.json().getAsJsonObject().get("id").getAsString();
        ticket.user = discordUser;
        ticket.userName = discordName;
        ticket.mc = mcUuid;
        ticket.mcName = mcName;
        add(ticket);
        // 歡迎訊息＋表單內容＋按鈕
        final JsonObject embed = new JsonObject();
        embed.addProperty("title", panel.title() + "｜#" + ticket.number);
        embed.addProperty("description", panel.welcome());
        embed.addProperty("color", panel.color());
        final JsonArray fields = new JsonArray();
        for (final Map.Entry<String, String> answer : answers.entrySet()) {
            fields.add(field(answer.getKey(), answer.getValue(), false));
        }
        embed.add("fields", fields);
        final JsonObject footer = new JsonObject();
        footer.addProperty("text", "開單時間 " + TIME.format(Instant.ofEpochMilli(ticket.created)));
        embed.add("footer", footer);
        final JsonObject message = new JsonObject();
        final StringBuilder mentions = new StringBuilder();
        if (discordUser != null) {
            mentions.append("<@").append(discordUser).append("> ");
        }
        for (final String role : panel.staffRoles()) {
            mentions.append("<@&").append(role).append("> ");
        }
        message.addProperty("content", mentions.toString().trim());
        final JsonObject allowed = new JsonObject();
        final JsonArray users = new JsonArray();
        if (discordUser != null) {
            users.add(discordUser);
        }
        final JsonArray rolesAllowed = new JsonArray();
        panel.staffRoles().forEach(rolesAllowed::add);
        allowed.add("parse", new JsonArray());
        allowed.add("users", users);
        allowed.add("roles", rolesAllowed);
        message.add("allowed_mentions", allowed);
        message.add("embeds", array(embed));
        message.add("components", array(row(button(1, "認領", "🙋", "ect:claim"), button(4, "關閉", "🔒", "ect:close"))));
        rest.call("POST", "/channels/" + ticket.channel + "/messages", message);
        if (token != null) {
            editOriginal(token, "客服單開好了：<#" + ticket.channel + ">");
        }
        return ticket;
    }

    private void claim(final String id, final String token, final String channel, final String userId, final String userName,
                       final List<String> roles) {
        final Ticket ticket = byChannel(channel);
        if (ticket == null) {
            ephemeral(id, token, "這不是開著的客服單。");
            return;
        }
        if (!isStaff(ticket, roles)) {
            ephemeral(id, token, "只有客服能認領。");
            return;
        }
        synchronized (this) {
            ticket.claimedBy = userName;
            save();
        }
        final JsonObject data = new JsonObject();
        data.addProperty("content", "🙋 這張客服單由 **" + DiscordBridge.escapeMarkdown(userName) + "** 處理");
        final JsonObject allowed = new JsonObject();
        allowed.add("parse", new JsonArray());
        data.add("allowed_mentions", allowed);
        final JsonObject body = new JsonObject();
        body.addProperty("type", 4);
        body.add("data", data);
        respond(id, token, body);
        notifyPlayer(ticket, "<#5865f2>[客服單 #" + ticket.number + "]</#5865f2> <gray>客服 <white>" + ItemRenderer.escape(userName) + "</white> 開始處理你的問題了");
    }

    private void close(final String id, final String token, final String channel, final String userId, final String userName,
                       final List<String> roles) {
        final Ticket ticket = byChannel(channel);
        if (ticket == null) {
            ephemeral(id, token, "這不是開著的客服單。");
            return;
        }
        if (!isStaff(ticket, roles) && !userId.equals(ticket.user)) {
            ephemeral(id, token, "只有客服或開單的人能關閉。");
            return;
        }
        final JsonObject data = new JsonObject();
        data.addProperty("content", "🔒 由 " + DiscordBridge.escapeMarkdown(userName) + " 關閉，5 秒後刪除這個頻道（對話紀錄會保存）");
        final JsonObject allowed = new JsonObject();
        allowed.add("parse", new JsonArray());
        data.add("allowed_mentions", allowed);
        final JsonObject body = new JsonObject();
        body.addProperty("type", 4);
        body.add("data", data);
        respond(id, token, body);
        finish(ticket, userName);
    }

    /** 遊戲裡關自己的單。 */
    void closeFromGame(final Player player) {
        final List<Ticket> mine = openBy(null, player.getUniqueId().toString());
        if (mine.isEmpty()) {
            player.sendMessage(plugin.text().prefixed("<gray>你沒有開著的客服單"));
            return;
        }
        final String name = player.getName();
        for (final Ticket ticket : mine) {
            worker.execute(() -> {
                final JsonObject message = new JsonObject();
                message.addProperty("content", "🔒 玩家 " + DiscordBridge.escapeMarkdown(name) + " 在遊戲裡關閉了客服單，5 秒後刪除這個頻道（對話紀錄會保存）");
                rest.call("POST", "/channels/" + ticket.channel + "/messages", message);
                finish(ticket, name + "（遊戲）");
            });
        }
        player.sendMessage(plugin.text().prefixed("<green>客服單關閉了，謝謝你的回報"));
    }

    /** 關單（背景執行緒）：對話紀錄存成文字檔送到紀錄頻道，等 5 秒刪掉頻道。 */
    private void finish(final Ticket ticket, final String closedBy) {
        remove(ticket);
        final StringBuilder transcript = new StringBuilder();
        transcript.append("星骸客服單 #").append(ticket.number).append("｜").append(panelTitle(ticket.panel)).append('\n');
        transcript.append("開單：").append(ticket.userName != null ? ticket.userName + "（Discord " + ticket.user + "）" : "遊戲 " + ticket.mcName)
                .append("｜").append(TIME.format(Instant.ofEpochMilli(ticket.created))).append('\n');
        transcript.append("處理：").append(ticket.claimedBy == null ? "（沒有人認領）" : ticket.claimedBy).append("｜關閉：").append(closedBy)
                .append("｜").append(TIME.format(Instant.now())).append("\n\n");
        final List<JsonObject> messages = new ArrayList<>();
        String before = null;
        for (int page = 0; page < 10; page++) {
            final DiscordRest.Result result = rest.call("GET", "/channels/" + ticket.channel + "/messages?limit=100" + (before == null ? "" : "&before=" + before), null);
            if (result == null || !result.ok() || !result.json().isJsonArray()) {
                break;
            }
            final JsonArray array = result.json().getAsJsonArray();
            for (final JsonElement element : array) {
                messages.add(element.getAsJsonObject());
            }
            if (array.size() < 100) {
                break;
            }
            before = array.get(array.size() - 1).getAsJsonObject().get("id").getAsString();
        }
        java.util.Collections.reverse(messages);
        for (final JsonObject message : messages) {
            final JsonObject author = message.getAsJsonObject("author");
            final String name = author == null ? "?" : author.has("global_name") && !author.get("global_name").isJsonNull()
                    ? author.get("global_name").getAsString() : author.get("username").getAsString();
            final String time = message.has("timestamp") ? message.get("timestamp").getAsString().replace('T', ' ') : "";
            transcript.append('[').append(time.length() > 16 ? time.substring(0, 16) : time).append("] ").append(name).append(": ");
            transcript.append(message.has("content") && !message.get("content").isJsonNull() ? message.get("content").getAsString() : "");
            if (message.has("embeds")) {
                for (final JsonElement embed : message.getAsJsonArray("embeds")) {
                    final JsonObject e = embed.getAsJsonObject();
                    if (e.has("title")) {
                        transcript.append(" [").append(e.get("title").getAsString()).append(']');
                    }
                    if (e.has("fields")) {
                        for (final JsonElement f : e.getAsJsonArray("fields")) {
                            transcript.append("\n    ").append(f.getAsJsonObject().get("name").getAsString()).append("：")
                                    .append(f.getAsJsonObject().get("value").getAsString());
                        }
                    }
                }
            }
            if (message.has("attachments")) {
                for (final JsonElement attachment : message.getAsJsonArray("attachments")) {
                    transcript.append("\n    附件：").append(attachment.getAsJsonObject().get("url").getAsString());
                }
            }
            transcript.append('\n');
        }
        if (!logChannelId.isEmpty()) {
            final JsonObject payload = new JsonObject();
            payload.addProperty("content", "📁 客服單 #" + ticket.number + "（" + panelTitle(ticket.panel) + "）關閉｜開單："
                    + DiscordBridge.escapeMarkdown(ticket.userName != null ? ticket.userName : "遊戲 " + ticket.mcName)
                    + "｜處理：" + DiscordBridge.escapeMarkdown(ticket.claimedBy == null ? "—" : ticket.claimedBy)
                    + "｜關閉：" + DiscordBridge.escapeMarkdown(closedBy));
            final JsonObject allowed = new JsonObject();
            allowed.add("parse", new JsonArray());
            payload.add("allowed_mentions", allowed);
            rest.upload(logChannelId, payload, "ticket-" + ticket.number + ".txt", transcript.toString().getBytes(StandardCharsets.UTF_8));
        }
        DiscordRest.sleep(5000);
        rest.call("DELETE", "/channels/" + ticket.channel, null);
        notifyPlayer(ticket, "<#5865f2>[客服單 #" + ticket.number + "]</#5865f2> <gray>已經關閉了，謝謝你的回報");
    }

    private String panelTitle(final String id) {
        final Panel panel = panels.get(id);
        return panel == null ? id : panel.title();
    }

    private boolean isStaff(final Ticket ticket, final List<String> roles) {
        final Panel panel = panels.get(ticket.panel);
        final List<String> staff = panel == null ? staffRoles : panel.staffRoles();
        if (roles.contains(ADMIN_MARK)) {
            return true;
        }
        for (final String role : roles) {
            if (staff.contains(role) || staffRoles.contains(role)) {
                return true;
            }
        }
        return false;
    }

    private void notifyPlayer(final Ticket ticket, final String line) {
        if (ticket.mc == null) {
            return;
        }
        plugin.getServer().getScheduler().runTask(plugin, () -> {
            final Player player = plugin.getServer().getPlayer(UUID.fromString(ticket.mc));
            if (player != null) {
                player.sendMessage(plugin.text().parse(line));
            }
        });
    }

    // ---- 遊戲裡的 /ticket ----

    /** /ticket 內容（主執行緒）：有開著的單就接上去，沒有就開新的。 */
    void fromGame(final Player player, final String text) {
        if (!enabled || guildId.isEmpty()) {
            player.sendMessage(plugin.text().prefixed("<red>客服單現在沒有開放，請到 Discord 聯絡管理員"));
            return;
        }
        final Panel panel = panels.containsKey(gamePanel) ? panels.get(gamePanel) : panels.isEmpty() ? null : panels.values().iterator().next();
        if (panel == null) {
            player.sendMessage(plugin.text().prefixed("<red>客服單沒有設定面板"));
            return;
        }
        final long now = System.currentTimeMillis();
        final Long last = cooldown.get(player.getName());
        if (last != null && now - last < 5000) {
            player.sendMessage(plugin.text().prefixed("<gray>慢一點，5 秒後再送"));
            return;
        }
        cooldown.put(player.getName(), now);
        final String content = cut(text.trim(), 1500);
        final List<Ticket> open = openBy(null, player.getUniqueId().toString());
        final String display = plugin.chat() == null ? player.getName() : plugin.chat().display(player);
        if (!open.isEmpty()) {
            final Ticket ticket = open.getFirst();
            worker.execute(() -> {
                final JsonObject message = new JsonObject();
                message.addProperty("content", "🎮 **" + DiscordBridge.escapeMarkdown(display) + "**（" + DiscordBridge.escapeMarkdown(player.getName())
                        + "）: " + DiscordBridge.escapeMarkdown(content));
                final JsonObject allowed = new JsonObject();
                allowed.add("parse", new JsonArray());
                message.add("allowed_mentions", allowed);
                rest.call("POST", "/channels/" + ticket.channel + "/messages", message);
            });
            player.sendMessage(plugin.text().prefixed("<gray>已送到你的客服單 #" + ticket.number + "<dark_gray>（關掉：/ticket close）"));
            return;
        }
        // 新單：附上角色與帳號的資料
        final Map<String, String> info = new LinkedHashMap<>();
        info.put("玩家", player.getName() + "（" + player.getUniqueId() + "）");
        final PlayerState state = plugin.players().get(player);
        final CharacterRecord character = state == null ? null : state.character();
        if (character != null) {
            final String ascendancy = plugin.ascendancy() == null ? null : plugin.ascendancy().ascendancyName(state);
            info.put("角色", character.name() + "｜Lv." + state.level() + "｜" + character.characterClass().displayName()
                    + (ascendancy == null ? "" : "（" + plain(ascendancy) + "）"));
        }
        final org.bukkit.Location at = player.getLocation();
        info.put("位置", at.getWorld().getName() + " " + at.getBlockX() + ", " + at.getBlockY() + ", " + at.getBlockZ()
                + "｜Ping " + player.getPing() + " ms");
        if (plugin.sponsors() != null) {
            final StringBuilder sponsor = new StringBuilder();
            for (final String line : plugin.sponsors().describe(player)) {
                sponsor.append(plain(line).trim()).append('\n');
            }
            if (!sponsor.isEmpty()) {
                info.put("贊助狀況", cut(sponsor.toString().trim(), 1000));
            }
        }
        info.put("問題內容", content);
        final String uuid = player.getUniqueId().toString();
        final String name = player.getName();
        worker.execute(() -> {
            final Ticket ticket = openTicket(panel, null, null, uuid, name, info, null);
            plugin.getServer().getScheduler().runTask(plugin, () -> {
                final Player online = plugin.getServer().getPlayer(UUID.fromString(uuid));
                if (online == null) {
                    return;
                }
                online.sendMessage(plugin.text().prefixed(ticket == null ? "<red>客服單開不了，請到 Discord 聯絡管理員"
                        : "<green>客服單 #" + ticket.number + " 開好了！<gray>客服在 Discord 回覆時，這裡會收到訊息"
                        + "<dark_gray>（補充：/ticket 訊息　關掉：/ticket close）"));
            });
        });
        player.sendMessage(plugin.text().prefixed("<gray>正在開客服單…"));
    }

    private String plain(final String miniMessage) {
        return net.kyori.adventure.text.serializer.plain.PlainTextComponentSerializer.plainText().serialize(plugin.text().parse(miniMessage));
    }

    // ---- JSON 小工具 ----

    private static JsonArray array(final JsonObject... items) {
        final JsonArray array = new JsonArray();
        for (final JsonObject item : items) {
            array.add(item);
        }
        return array;
    }

    private static JsonObject row(final JsonObject... components) {
        final JsonObject row = new JsonObject();
        row.addProperty("type", 1);
        row.add("components", array(components));
        return row;
    }

    private static JsonObject button(final int style, final String label, final String emoji, final String custom) {
        final JsonObject button = new JsonObject();
        button.addProperty("type", 2);
        button.addProperty("style", style);
        button.addProperty("label", label);
        button.addProperty("custom_id", custom);
        if (emoji != null && !emoji.isBlank()) {
            final JsonObject e = new JsonObject();
            e.addProperty("name", emoji.trim());
            button.add("emoji", e);
        }
        return button;
    }

    private static JsonObject field(final String name, final String value, final boolean inline) {
        final JsonObject field = new JsonObject();
        field.addProperty("name", cut(name, 256));
        field.addProperty("value", cut(value.isBlank() ? "—" : value, 1024));
        field.addProperty("inline", inline);
        return field;
    }

    private static JsonObject overwrite(final String id, final int type, final long allow, final long deny) {
        final JsonObject overwrite = new JsonObject();
        overwrite.addProperty("id", id);
        overwrite.addProperty("type", type);
        overwrite.addProperty("allow", String.valueOf(allow));
        overwrite.addProperty("deny", String.valueOf(deny));
        return overwrite;
    }
}
