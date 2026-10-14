package tw.exilecore.discord;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.serializer.plain.PlainTextComponentSerializer;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.entity.Player;
import tw.exilecore.ExileCore;
import tw.exilecore.item.ItemRenderer;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

/**
 * 伺服器聊天與 Discord 同步（2026-10）。
 * <ul>
 *   <li>遊戲 → Discord：用頻道的 Webhook 送（名字顯示角色名稱、頭像是玩家的頭）；公告（上下線、世界事件、重大成就、開關服）也用它。
 *       沒設 Webhook 才用機器人送（Discord 規定機器人要先連過一次 Gateway 才能發訊息，所以建議一定要設 Webhook）。</li>
 *   <li>Discord → 遊戲：機器人一直連著 Discord（Gateway；要開 Message Content Intent），聊天頻道的訊息即時轉到遊戲聊天。
 *       機器人與 Webhook 自己發的不轉（不會繞圈）。</li>
 *   <li>客服單（{@link DiscordTickets}）：按鈕、表單、開頻道、關單存紀錄，加上遊戲內的 /ticket。</li>
 *   <li>全部在背景執行緒：送出照順序一則一則來（遇到 Discord 的頻率限制照它說的等），讀到的訊息回主執行緒廣播。</li>
 *   <li>不會 @ 到任何人（allowed_mentions 全關）；玩家打的 Markdown 符號跳脫。</li>
 * </ul>
 */
public final class DiscordBridge {

    private static final String API = "https://discord.com/api/v10";
    private static final int MAX_CONTENT = 1900;

    private final ExileCore plugin;
    private final HttpClient http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(8)).build();
    private ExecutorService sender;
    private DiscordGateway gateway;
    private final DiscordRest rest;
    private final DiscordTickets tickets;
    private final DiscordLink link;

    private boolean enabled;
    private String token = "";
    private String channelId = "";
    private String webhookUrl = "";
    private String serverName = "星骸";
    private String inGameFormat = "";
    boolean syncChat;
    boolean syncJoinLeave;
    boolean syncWorldEvents;
    boolean syncAchievements;
    boolean syncServer;


    DiscordBridge(final ExileCore plugin) {
        this.plugin = plugin;
        this.rest = new DiscordRest(plugin.getSLF4JLogger());
        this.tickets = new DiscordTickets(plugin, rest);
        this.link = new DiscordLink(plugin, rest);
    }

    /** Discord 帳號連結與伺服器加成（2026-10）。 */
    public DiscordLink link() {
        return link;
    }

    /** 客服單（設定沒開也有物件，指令會說沒開放）。 */
    public DiscordTickets tickets() {
        return tickets;
    }

    /** 讀 config.yml 的 discord 段落；回傳是不是有開。 */
    boolean configure() {
        final ConfigurationSection section = plugin.getConfig().getConfigurationSection("discord");
        enabled = section != null && section.getBoolean("enabled", false);
        if (section == null) {
            return false;
        }
        token = section.getString("bot-token", "").trim();
        channelId = section.getString("channel-id", "").trim();
        webhookUrl = section.getString("webhook-url", "").trim();
        serverName = section.getString("server-name", "星骸");
        inGameFormat = section.getString("in-game-format", "<#5865f2>[DC]</#5865f2> <white>{name}</white><gray>: </gray>{message}");
        syncChat = section.getBoolean("sync.chat", true);
        syncJoinLeave = section.getBoolean("sync.join-leave", true);
        syncWorldEvents = section.getBoolean("sync.world-events", true);
        syncAchievements = section.getBoolean("sync.achievements", true);
        syncServer = section.getBoolean("sync.server", true);
        rest.token(token);
        tickets.configure(section);
        link.configure(section);
        return enabled;
    }

    void start() {
        stop(false);
        if (!enabled) {
            return;
        }
        if (webhookUrl.isEmpty() && (token.isEmpty() || channelId.isEmpty())) {
            plugin.getSLF4JLogger().warn("[Discord] 開了但沒有設定 webhook-url（或 bot-token＋channel-id）：遊戲訊息送不出去");
        }
        sender = Executors.newSingleThreadExecutor(runnable -> {
            final Thread thread = new Thread(runnable, "ExileCore-discord-send");
            thread.setDaemon(true);
            return thread;
        });
        if (!token.isEmpty()) {
            // 2026-10：改成一直連著 Discord（Gateway）：聊天即時進遊戲、客服單的按鈕與表單
            gateway = new DiscordGateway(plugin.getSLF4JLogger(), token, this::onDispatch);
            tickets.gateway(gateway);
            gateway.start();
            link.start(); // 2026-10：連結的 Discord 帳號有沒有在加成伺服器
            plugin.getSLF4JLogger().info("[Discord] 已啟用：遊戲 → Discord（{}），Discord → 遊戲（頻道 {}），客服單{}",
                    webhookUrl.isEmpty() ? "機器人" : "Webhook", channelId.isEmpty() ? "沒設" : channelId, tickets.enabled ? "開" : "關");
        } else {
            plugin.getSLF4JLogger().info("[Discord] 已啟用：只有遊戲 → Discord（沒設 bot-token：Discord 的訊息不會轉進遊戲，也沒有客服單）");
        }
    }

    /** Gateway 的事件（Gateway 執行緒）。 */
    private void onDispatch(final String type, final JsonObject data) {
        if ("INTERACTION_CREATE".equals(type)) {
            if (!link.onInteraction(data)) {
                tickets.onInteraction(data);
            }
        } else if ("GUILD_MEMBER_UPDATE".equals(type) || "GUILD_MEMBER_REMOVE".equals(type)) {
            link.onMemberUpdate(data, "GUILD_MEMBER_REMOVE".equals(type)); // 2026-10：加成開始、結束
        } else if ("MESSAGE_CREATE".equals(type)) {
            final String channel = data.has("channel_id") ? data.get("channel_id").getAsString() : "";
            if (channel.equals(channelId)) {
                relayChat(data);
            } else {
                tickets.onMessage(data);
            }
        }
    }

    /** 停掉背景工作；flush = 先把排隊中的送完（最多等幾秒，關服時用）。 */
    void stop(final boolean flush) {
        link.stop();
        if (gateway != null) {
            gateway.stop();
            gateway = null;
            tickets.gateway(null);
        }
        if (sender != null) {
            sender.shutdown();
            if (flush) {
                try {
                    sender.awaitTermination(5, TimeUnit.SECONDS);
                } catch (final InterruptedException e) {
                    Thread.currentThread().interrupt();
                }
            }
            sender.shutdownNow();
            sender = null;
        }
    }

    public boolean active() {
        return enabled && sender != null;
    }

    // ---- 遊戲 → Discord ----

    /** 玩家在公開聊天說話（plain = 純文字的訊息）。 */
    void chat(final Player player, final String plain) {
        chat(player, plain, List.of());
    }

    /** 同上；items 是訊息裡 [i] 貼的物品（各變成一個嵌入：名稱照稀有度上色、底下是物品說明）。 */
    void chat(final Player player, final String plain, final List<org.bukkit.inventory.ItemStack> items) {
        if (!active() || !syncChat || plain == null || plain.isBlank()) {
            return;
        }
        // 2026-10：「【稱號】角色名稱《職業》Lv.等級」，不寫玩家 ID（還沒選角色才用 ID）
        final String full = plugin.players() == null ? null : plugin.players().plainFullName(player);
        final String name = full != null ? full : plugin.chat() == null ? player.getName() : plugin.chat().display(player);
        final JsonArray embeds = new JsonArray();
        for (final org.bukkit.inventory.ItemStack item : items) {
            if (embeds.size() < 10) {
                embeds.add(itemEmbed(item));
            }
        }
        send(name, "https://mc-heads.net/avatar/" + player.getName() + "/64", escapeMarkdown(plain), true, embeds);
    }

    /** 一件物品的嵌入：標題＝物品名稱（顏色＝名稱的顏色），內容＝物品說明（純文字，資源包的圖示字拿掉）。 */
    static JsonObject itemEmbed(final org.bukkit.inventory.ItemStack item) {
        final PlainTextComponentSerializer plain = PlainTextComponentSerializer.plainText();
        final net.kyori.adventure.text.Component nameComponent = tw.exilecore.chat.ChatService.itemName(item);
        String title = clean(plain.serialize(nameComponent));
        if (item.getAmount() > 1) {
            title += " ×" + item.getAmount();
        }
        final JsonObject embed = new JsonObject();
        embed.addProperty("title", title.length() > 250 ? title.substring(0, 250) : title);
        final net.kyori.adventure.text.format.TextColor color = firstColor(nameComponent);
        embed.addProperty("color", color == null ? 0xC8C8C8 : color.value());
        final StringBuilder lore = new StringBuilder();
        final org.bukkit.inventory.meta.ItemMeta meta = item.getItemMeta();
        final List<net.kyori.adventure.text.Component> lines = meta == null || meta.lore() == null ? List.of() : meta.lore();
        for (final net.kyori.adventure.text.Component line : lines) {
            final String text = clean(tw.exilecore.item.AffixChips.strip(plain.serialize(line))).stripTrailing();
            if (text.startsWith("比身上的")) {
                break; // 2026-10：跟身上裝備的比較只給自己看，Discord 不顯示（在說明的最後面）
            }
            if (lore.length() + text.length() + 1 > 3900) {
                lore.append("…");
                break;
            }
            lore.append(escapeMarkdown(text)).append('\n');
        }
        final String description = lore.toString().replaceAll("\n{3,}", "\n\n").strip();
        if (!description.isEmpty()) {
            embed.addProperty("description", description);
        }
        return embed;
    }

    /** 資源包的圖示字（私人使用區）與控制字元拿掉。 */
    private static String clean(final String text) {
        final StringBuilder out = new StringBuilder(text.length());
        text.codePoints().filter(c -> !(c >= 0xE000 && c <= 0xF8FF) && !(c >= 0xF0000) && (c >= 0x20 || c == '\n'))
                .forEach(out::appendCodePoint);
        return out.toString();
    }

    private static net.kyori.adventure.text.format.TextColor firstColor(final net.kyori.adventure.text.Component component) {
        if (component.color() != null) {
            return component.color();
        }
        for (final net.kyori.adventure.text.Component child : component.children()) {
            final net.kyori.adventure.text.format.TextColor found = firstColor(child);
            if (found != null) {
                return found;
            }
        }
        return null;
    }

    /** 公告（MiniMessage 也可以，會轉成純文字）；icon 是前面的表情符號。 */
    public void announce(final String icon, final String miniMessage) {
        if (!active() || miniMessage == null || miniMessage.isBlank()) {
            return;
        }
        final String plain = PlainTextComponentSerializer.plainText().serialize(plugin.text().parse(miniMessage)).trim();
        send(serverName, null, (icon == null || icon.isEmpty() ? "" : icon + " ") + escapeMarkdown(plain), false);
    }

    /** 世界事件的公告（設定可以關）。 */
    public void worldEvent(final String miniMessage) {
        if (syncWorldEvents) {
            announce("🌠", miniMessage);
        }
    }

    /** 重大成就的公告（設定可以關）。 */
    public void achievement(final String miniMessage) {
        if (syncAchievements) {
            announce("🏆", miniMessage);
        }
    }

    void joinLeave(final String text) {
        if (syncJoinLeave) {
            announce(null, text);
        }
    }

    void server(final String text) {
        if (syncServer) {
            announce(null, text);
        }
    }

    private void send(final String username, final String avatar, final String content, final boolean chat) {
        send(username, avatar, content, chat, null);
    }

    private void send(final String username, final String avatar, final String content, final boolean chat, final JsonArray embeds) {
        final ExecutorService queue = sender;
        if (queue == null) {
            return;
        }
        final String body = content.length() > MAX_CONTENT ? content.substring(0, MAX_CONTENT) + "…" : content;
        try {
            queue.execute(() -> deliver(username, avatar, body, chat, embeds));
        } catch (final java.util.concurrent.RejectedExecutionException ignored) {
            // 正在關
        }
    }

    private void deliver(final String username, final String avatar, final String content, final boolean chat, final JsonArray embeds) {
        final JsonObject json = new JsonObject();
        if (embeds != null && !embeds.isEmpty()) {
            json.add("embeds", embeds);
        }
        final JsonObject mentions = new JsonObject();
        mentions.add("parse", new JsonArray());
        json.add("allowed_mentions", mentions);
        final HttpRequest.Builder request;
        if (!webhookUrl.isEmpty()) {
            json.addProperty("content", content);
            json.addProperty("username", username.length() > 80 ? username.substring(0, 80) : username);
            if (avatar != null) {
                json.addProperty("avatar_url", avatar);
            }
            request = HttpRequest.newBuilder(URI.create(webhookUrl));
        } else if (!token.isEmpty() && !channelId.isEmpty()) {
            json.addProperty("content", chat ? "**" + escapeMarkdown(username) + "**: " + content : content);
            request = HttpRequest.newBuilder(URI.create(API + "/channels/" + channelId + "/messages")).header("Authorization", "Bot " + token);
        } else {
            return;
        }
        request.header("Content-Type", "application/json").header("User-Agent", "ExileCore (https://github.com, 1.0)")
                .timeout(Duration.ofSeconds(10)).POST(HttpRequest.BodyPublishers.ofString(json.toString(), StandardCharsets.UTF_8));
        for (int attempt = 0; attempt < 3; attempt++) {
            try {
                final HttpResponse<String> response = http.send(request.build(), HttpResponse.BodyHandlers.ofString());
                if (response.statusCode() == 429) {
                    sleep(retryAfter(response.body()));
                    continue;
                }
                if (response.statusCode() >= 400) {
                    plugin.getSLF4JLogger().warn("[Discord] 送不出去（HTTP {}）：{}", response.statusCode(), shorten(response.body()));
                }
                return;
            } catch (final java.io.IOException e) {
                if (attempt == 2) {
                    plugin.getSLF4JLogger().warn("[Discord] 連不上 Discord：{}", e.toString());
                }
                sleep(1000);
            } catch (final InterruptedException e) {
                Thread.currentThread().interrupt();
                return;
            }
        }
    }

    // ---- Discord → 遊戲 ----

    /** 聊天頻道的一則訊息轉到遊戲（Gateway 執行緒；機器人、Webhook 自己發的不轉）。 */
    private void relayChat(final JsonObject message) {
        if (!syncChat) {
            return;
        }
        final JsonObject author = message.has("author") && message.get("author").isJsonObject() ? message.getAsJsonObject("author") : null;
        if (author == null || message.has("webhook_id") || author.has("bot") && author.get("bot").getAsBoolean()) {
            return;
        }
        if (message.has("type") && message.get("type").getAsInt() != 0 && message.get("type").getAsInt() != 19) {
            return; // 只要一般訊息與回覆
        }
        String content = message.has("content") && !message.get("content").isJsonNull() ? message.get("content").getAsString() : "";
        // 附件（2026-10）：聊天欄裡是可以點的「[圖片]」「[附件]」，點了在瀏覽器打開
        final List<Attachment> attachments = attachments(message);
        if (attachments.isEmpty() && message.has("attachments") && message.getAsJsonArray("attachments").size() > 0) {
            content = (content + " [附件]").trim(); // 網址不認得（不是 Discord 的）：只寫字
        }
        if (message.has("sticker_items") && message.getAsJsonArray("sticker_items").size() > 0) {
            content = (content + " [貼圖]").trim();
        }
        content = content.replaceAll("<a?:(\\w+):\\d+>", ":$1:").replaceAll("\\s+", " ").trim();
        if (content.isEmpty() && attachments.isEmpty()) {
            return;
        }
        if (content.length() > 256) {
            content = content.substring(0, 256) + "…";
        }
        String name = author.has("global_name") && !author.get("global_name").isJsonNull()
                ? author.get("global_name").getAsString() : author.get("username").getAsString();
        if (message.has("member") && message.getAsJsonObject("member").has("nick") && !message.getAsJsonObject("member").get("nick").isJsonNull()) {
            name = message.getAsJsonObject("member").get("nick").getAsString(); // 伺服器暱稱
        }
        final String finalName = name;
        final String finalContent = content;
        plugin.getServer().getScheduler().runTask(plugin, () -> {
            Component line = plugin.text().parse(inGameFormat.replace("{name}", ItemRenderer.escape(finalName))
                    .replace("{message}", ItemRenderer.escape(finalContent)));
            for (final Attachment attachment : attachments) {
                line = line.append(Component.space()).append(attachment.link());
            }
            for (final Player player : plugin.getServer().getOnlinePlayers()) {
                player.sendMessage(line);
            }
            plugin.getSLF4JLogger().info("[Discord] {}: {}{}", finalName, finalContent, attachments.isEmpty() ? "" : " " + attachments);
        });
    }

    /** 一則訊息最多列幾個附件（再多的在 Discord 看）。 */
    static final int MAX_ATTACHMENTS = 4;

    /**
     * Discord 訊息的一個附件。
     *
     * @param kind 圖片、影片、語音、附件
     * @param name 檔名
     * @param url  Discord 的下載網址（https，Discord 自己的網域）
     */
    record Attachment(String kind, String name, String url) {

        /** 聊天欄裡可以點的「[圖片]」：滑鼠移上去看檔名，點了在瀏覽器打開（客戶端會先問要不要開）。 */
        Component link() {
            return Component.text("[" + kind + "]", net.kyori.adventure.text.format.TextColor.color(0x7FD4FF))
                    .decorate(net.kyori.adventure.text.format.TextDecoration.UNDERLINED)
                    .hoverEvent(net.kyori.adventure.text.event.HoverEvent.showText(Component.text(name + "\n點一下在瀏覽器打開")))
                    .clickEvent(net.kyori.adventure.text.event.ClickEvent.openUrl(url));
        }

        @Override
        public String toString() {
            return "[" + kind + " " + name + "]";
        }
    }

    /** 訊息裡認得的附件（最多 {@value #MAX_ATTACHMENTS} 個；網址不是 Discord 的不要）。 */
    static List<Attachment> attachments(final JsonObject message) {
        final List<Attachment> list = new ArrayList<>();
        if (!message.has("attachments") || !message.get("attachments").isJsonArray()) {
            return list;
        }
        for (final JsonElement element : message.getAsJsonArray("attachments")) {
            if (list.size() >= MAX_ATTACHMENTS || !element.isJsonObject()) {
                continue;
            }
            final JsonObject object = element.getAsJsonObject();
            final String url = text(object, "url");
            if (!discordUrl(url)) {
                continue;
            }
            final String type = text(object, "content_type").toLowerCase(Locale.ROOT);
            String name = text(object, "filename");
            if (name.length() > 48) {
                name = name.substring(0, 48) + "…";
            }
            final String kind = type.startsWith("image/") ? "圖片" : type.startsWith("video/") ? "影片" : type.startsWith("audio/") ? "語音" : "附件";
            list.add(new Attachment(kind, name.isEmpty() ? kind : name, url));
        }
        return list;
    }

    private static String text(final JsonObject object, final String key) {
        return object.has(key) && object.get(key).isJsonPrimitive() ? object.get(key).getAsString() : "";
    }

    /** 只收 Discord 自己放附件的網址（https、cdn.discordapp.com 或 media.discordapp.net）：聊天欄不放別的站的連結。 */
    static boolean discordUrl(final String url) {
        if (url == null || url.length() > 1024) {
            return false;
        }
        try {
            final URI uri = URI.create(url);
            final String host = uri.getHost() == null ? "" : uri.getHost().toLowerCase(Locale.ROOT);
            return "https".equalsIgnoreCase(uri.getScheme()) && (host.equals("cdn.discordapp.com") || host.equals("media.discordapp.net"));
        } catch (final IllegalArgumentException e) {
            return false;
        }
    }

    // ---- 小工具 ----

    /** 玩家打的字在 Discord 不要變成 Markdown（粗體、刪除線、引用、遮蔽、程式碼）。 */
    static String escapeMarkdown(final String text) {
        final StringBuilder out = new StringBuilder(text.length() + 8);
        for (int i = 0; i < text.length(); i++) {
            final char c = text.charAt(i);
            if ("\\*_~`|>#-[]()".indexOf(c) >= 0) {
                out.append('\\');
            }
            out.append(c);
        }
        return out.toString();
    }

    private static long retryAfter(final String body) {
        try {
            final JsonObject json = JsonParser.parseString(body).getAsJsonObject();
            return Math.max(200, Math.min(10_000, (long) (json.get("retry_after").getAsDouble() * 1000)));
        } catch (final RuntimeException e) {
            return 2000;
        }
    }

    private static void sleep(final long millis) {
        try {
            Thread.sleep(millis);
        } catch (final InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    private static String shorten(final String body) {
        final String text = body == null ? "" : body.replaceAll("\\s+", " ");
        return text.length() > 200 ? text.substring(0, 200) : text.toLowerCase(Locale.ROOT).contains("token") ? "（略）" : text;
    }
}
