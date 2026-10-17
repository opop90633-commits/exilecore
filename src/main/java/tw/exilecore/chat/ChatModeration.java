package tw.exilecore.chat;

import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.entity.Player;
import tw.exilecore.ExileCore;
import tw.exilecore.character.NameFilter;

import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.StandardOpenOption;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayDeque;
import java.util.Deque;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 聊天管理（2026-10）：禁言、洗頻限制、髒話過濾、檢舉。公開聊天與私訊都會經過 {@link #check}。
 * <ul>
 *   <li>禁言：/mute 玩家 分鐘 [原因]（0 = 永久）、/unmute 玩家；記在 plugins/ExileCore/mutes.yml，重開伺服器也在。</li>
 *   <li>洗頻：{@link #BURST_WINDOW_MS} 毫秒內超過 {@link #BURST_COUNT} 句，冷卻 {@link #BURST_COOLDOWN_MS} 毫秒；
 *       {@link #REPEAT_WINDOW_MS} 毫秒內同一句話不能重複說。</li>
 *   <li>髒話：跟角色名稱同一份字表（{@link NameFilter}，不含「冒充管理員」那部分）。直接寫出來的換成＊；
 *       故意拆開、用數字代字母的寫法換不掉，那句就不送出。</li>
 *   <li>檢舉：/report 玩家 原因：在線的管理員馬上看到，也寫進 plugins/ExileCore/reports.log；每個人 60 秒能檢舉一次。</li>
 * </ul>
 * 管理員（exilecore.admin）不受洗頻與髒話限制。AsyncChatEvent 在別的執行緒，這裡的資料都用執行緒安全的容器。
 */
public final class ChatModeration {

    static final int BURST_COUNT = 5;
    static final long BURST_WINDOW_MS = 8_000;
    static final long BURST_COOLDOWN_MS = 15_000;
    static final long REPEAT_WINDOW_MS = 30_000;
    static final long REPORT_COOLDOWN_MS = 60_000;
    /** 貼物品（[i]）的訊息不算洗頻，但兩則之間至少隔這麼久。 */
    static final long LINK_COOLDOWN_MS = 2_000;

    /** 一則檢查的結果：送不送、送的話用哪段文字（髒話換成＊之後）、不送的話跟發話的人說什麼。 */
    public record Verdict(boolean allowed, String text, String reason) {
        static Verdict allow(final String text) {
            return new Verdict(true, text, null);
        }

        static Verdict deny(final String reason) {
            return new Verdict(false, null, reason);
        }
    }

    /** 一筆禁言：到期時間（毫秒；0 = 永久）、原因、誰禁的。 */
    record Mute(long until, String reason, String by) {
        boolean active(final long now) {
            return until == 0 || until > now;
        }
    }

    private final ExileCore plugin;
    private final NameFilter words = new NameFilter();
    private final Map<UUID, Mute> mutes = new ConcurrentHashMap<>();
    private final Map<UUID, Deque<Long>> recent = new ConcurrentHashMap<>();
    private final Map<UUID, Long> cooldownUntil = new ConcurrentHashMap<>();
    private final Map<UUID, String> lastText = new ConcurrentHashMap<>();
    private final Map<UUID, Long> lastTextAt = new ConcurrentHashMap<>();
    private final Map<UUID, Long> lastReport = new ConcurrentHashMap<>();
    private final Map<UUID, Long> lastLinkAt = new ConcurrentHashMap<>();
    private final File muteFile;
    private final File reportFile;

    ChatModeration(final ExileCore plugin) {
        this.plugin = plugin;
        this.muteFile = new File(plugin.getDataFolder(), "mutes.yml");
        this.reportFile = new File(plugin.getDataFolder(), "reports.log");
        loadMutes();
    }

    // ---- 檢查 ----

    /** 公開聊天與私訊送出前：禁言、洗頻、髒話。 */
    public Verdict check(final Player player, final String text) {
        final long now = System.currentTimeMillis();
        final Mute mute = mutes.get(player.getUniqueId());
        if (mute != null) {
            if (mute.active(now)) {
                return Verdict.deny("你被禁言了" + (mute.until() == 0 ? "（永久）" : "，還有 " + remaining(mute.until() - now))
                        + (mute.reason().isBlank() ? "" : "：" + mute.reason()));
            }
            mutes.remove(player.getUniqueId());
            saveMutesLater();
        }
        if (player.hasPermission(ExileCore.PERMISSION_ADMIN)) {
            return Verdict.allow(text);
        }
        // 2026-10-17：洗頻冷卻中連貼物品的訊息也不行（以前加個 [i] 就能繞過冷卻；貼物品本身還是不限）
        final Long burstCooldown = cooldownUntil.get(player.getUniqueId());
        if (burstCooldown != null && burstCooldown > now) {
            return Verdict.deny("說太快了，" + remaining(burstCooldown - now) + "後再說");
        }
        if (ChatService.hasLinks(text)) {
            // 2026-10：貼物品（[i]）不算洗頻、不算重複；只有每 LINK_COOLDOWN_MS 一則的限制，免得整排都是物品
            final Long lastLink = lastLinkAt.get(player.getUniqueId());
            if (lastLink != null && now - lastLink < LINK_COOLDOWN_MS) {
                return Verdict.deny("貼物品太快了，" + remaining(lastLink + LINK_COOLDOWN_MS - now) + "後再貼");
            }
            lastLinkAt.put(player.getUniqueId(), now);
            return filtered(text);
        }
        final Long cooldown = cooldownUntil.get(player.getUniqueId());
        if (cooldown != null && cooldown > now) {
            return Verdict.deny("說太快了，" + remaining(cooldown - now) + "後再說");
        }
        final String normalized = text.strip().toLowerCase(java.util.Locale.ROOT);
        final String previous = lastText.get(player.getUniqueId());
        final Long previousAt = lastTextAt.get(player.getUniqueId());
        if (previous != null && previous.equals(normalized) && previousAt != null && now - previousAt < REPEAT_WINDOW_MS) {
            return Verdict.deny("同一句話不要一直重複");
        }
        final Deque<Long> times = recent.computeIfAbsent(player.getUniqueId(), uuid -> new ArrayDeque<>());
        synchronized (times) {
            while (!times.isEmpty() && now - times.peekFirst() > BURST_WINDOW_MS) {
                times.pollFirst();
            }
            if (times.size() >= BURST_COUNT) {
                cooldownUntil.put(player.getUniqueId(), now + BURST_COOLDOWN_MS);
                times.clear();
                return Verdict.deny("說太快了（洗頻），" + (BURST_COOLDOWN_MS / 1000) + " 秒後再說");
            }
            times.addLast(now);
        }
        lastText.put(player.getUniqueId(), normalized);
        lastTextAt.put(player.getUniqueId(), now);
        return filtered(text);
    }

    /** 髒話過濾：換成＊；拆開寫的不送出。 */
    private Verdict filtered(final String text) {
        final NameFilter filter = filter();
        final String masked = filter.maskChat(text);
        if (filter.disguisedInChat(masked)) {
            return Verdict.deny("訊息含有不雅、歧視或敏感的字，沒有送出");
        }
        return Verdict.allow(masked);
    }

    /** 字表：跟角色名稱共用（character.yml 的 name_filter 加的字也算）。 */
    private NameFilter filter() {
        return plugin.characters() != null ? plugin.characters().config().nameFilter() : words;
    }

    private static String remaining(final long ms) {
        final long seconds = Math.max(1, (ms + 999) / 1000);
        if (seconds < 60) {
            return seconds + " 秒";
        }
        final long minutes = (seconds + 59) / 60;
        if (minutes < 60) {
            return minutes + " 分鐘";
        }
        final long hours = minutes / 60;
        return hours < 24 ? hours + " 小時" + (minutes % 60 > 0 ? " " + minutes % 60 + " 分鐘" : "") : (hours / 24) + " 天";
    }

    // ---- 禁言 ----

    /** 禁言（minutes = 0 是永久）。 */
    public void mute(final UUID player, final long minutes, final String reason, final String by) {
        // 2026-10：分鐘數太大（溢位）會變成馬上解除：最多 100 年
        final long until = minutes <= 0 ? 0 : System.currentTimeMillis() + Math.min(minutes, 52_560_000L) * 60_000L;
        mutes.put(player, new Mute(until, reason == null ? "" : reason.strip(), by));
        saveMutesLater();
    }

    /** 解除禁言；本來就沒有回傳 false。 */
    public boolean unmute(final UUID player) {
        final boolean had = mutes.remove(player) != null;
        if (had) {
            saveMutesLater();
        }
        return had;
    }

    public boolean muted(final UUID player) {
        final Mute mute = mutes.get(player);
        return mute != null && mute.active(System.currentTimeMillis());
    }

    private void loadMutes() {
        if (!muteFile.exists()) {
            return;
        }
        final YamlConfiguration yaml = YamlConfiguration.loadConfiguration(muteFile);
        for (final String key : yaml.getKeys(false)) {
            try {
                final UUID uuid = UUID.fromString(key);
                final Mute mute = new Mute(yaml.getLong(key + ".until"), yaml.getString(key + ".reason", ""), yaml.getString(key + ".by", ""));
                if (mute.active(System.currentTimeMillis())) {
                    mutes.put(uuid, mute);
                }
            } catch (final IllegalArgumentException e) {
                plugin.getSLF4JLogger().warn("mutes.yml：看不懂的玩家代號 {}", key);
            }
        }
    }

    private void saveMutesLater() {
        plugin.getServer().getScheduler().runTask(plugin, this::saveMutes);
    }

    private void saveMutes() {
        final YamlConfiguration yaml = new YamlConfiguration();
        for (final Map.Entry<UUID, Mute> entry : mutes.entrySet()) {
            final String key = entry.getKey().toString();
            yaml.set(key + ".until", entry.getValue().until());
            yaml.set(key + ".reason", entry.getValue().reason());
            yaml.set(key + ".by", entry.getValue().by());
        }
        try {
            yaml.save(muteFile);
        } catch (final IOException e) {
            plugin.getSLF4JLogger().error("存不了 mutes.yml", e);
        }
    }

    // ---- 檢舉 ----

    /** 檢舉：在線的管理員看到、寫進 reports.log；回傳給檢舉人的話。 */
    public String report(final Player reporter, final Player target, final String reason) {
        final long now = System.currentTimeMillis();
        final Long last = lastReport.get(reporter.getUniqueId());
        if (last != null && now - last < REPORT_COOLDOWN_MS) {
            return "<red>檢舉太頻繁了，" + remaining(REPORT_COOLDOWN_MS - (now - last)) + "後再試";
        }
        if (target.getUniqueId().equals(reporter.getUniqueId())) {
            return "<red>不能檢舉自己";
        }
        lastReport.put(reporter.getUniqueId(), now);
        final String time = LocalDateTime.now().format(DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss"));
        final String where = target.getWorld().getName() + " " + target.getLocation().getBlockX() + "," + target.getLocation().getBlockY() + ","
                + target.getLocation().getBlockZ();
        final String line = time + "\t" + reporter.getName() + "\t→\t" + target.getName() + "（" + target.getUniqueId() + "）\t" + where + "\t" + reason + "\n";
        try {
            Files.writeString(reportFile.toPath(), line, StandardCharsets.UTF_8, StandardOpenOption.CREATE, StandardOpenOption.APPEND);
        } catch (final IOException e) {
            plugin.getSLF4JLogger().error("寫不了 reports.log", e);
        }
        plugin.getSLF4JLogger().info("[檢舉] {} 檢舉 {}：{}", reporter.getName(), target.getName(), reason);
        final String escapedReason = tw.exilecore.item.ItemRenderer.escape(reason);
        int admins = 0;
        for (final Player online : plugin.getServer().getOnlinePlayers()) {
            if (online.hasPermission(ExileCore.PERMISSION_ADMIN)) {
                admins++;
                online.sendMessage(plugin.text().prefixed("<#ff7777>[檢舉] <white>" + reporter.getName() + "<gray> 檢舉 <white>" + target.getName()
                        + "<gray>：" + escapedReason + " <dark_gray>（" + where + "）"));
            }
        }
        return "<green>已經送出檢舉，謝謝回報" + (admins == 0 ? "<gray>（管理員目前不在線上，上線後會看到紀錄）" : "<gray>（管理員已經收到）");
    }

    /** 下線：洗頻的計數不用留（禁言留著）。 */
    void forget(final UUID player) {
        recent.remove(player);
        cooldownUntil.remove(player);
        lastText.remove(player);
        lastTextAt.remove(player);
        lastLinkAt.remove(player);
    }
}
