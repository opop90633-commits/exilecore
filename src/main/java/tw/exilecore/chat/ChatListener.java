package tw.exilecore.chat;

import io.papermc.paper.event.player.AsyncChatEvent;
import net.kyori.adventure.text.serializer.plain.PlainTextComponentSerializer;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.entity.PlayerDeathEvent;
import org.bukkit.event.player.PlayerQuitEvent;

/** 公開聊天的物品連結（[i]）；死亡訊息寫角色名稱；下線時忘掉名字。 */
final class ChatListener implements Listener {

    private final ChatService chat;

    ChatListener(final ChatService chat) {
        this.chat = chat;
    }

    /** 對話框等著玩家打字時（gui/ChatPrompts，LOWEST）會取消事件，這裡就不管。 */
    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onChat(final AsyncChatEvent event) {
        final String raw = PlainTextComponentSerializer.plainText().serialize(event.message());
        // 2026-10：固定頻道（/p、/g 不帶訊息切換）：直接打字送到隊伍或公會，不進公開聊天
        final ChatService.Channel channel = chat.channel(event.getPlayer().getUniqueId());
        if (channel != ChatService.Channel.ALL) {
            event.setCancelled(true);
            final org.bukkit.entity.Player player = event.getPlayer();
            chat.plugin().getServer().getScheduler().runTask(chat.plugin(), () -> {
                if (!player.isOnline()) {
                    return;
                }
                if (channel == ChatService.Channel.GUILD) {
                    if (chat.plugin().guilds() == null || chat.plugin().guilds().guildOf(player.getUniqueId()) == null) {
                        chat.setChannel(player, ChatService.Channel.ALL);
                        player.sendMessage(chat.plugin().text().prefixed("<red>你不在公會裡，切回公開頻道了<gray>（剛剛那則沒送出）"));
                        return;
                    }
                    chat.plugin().guilds().chat(player, raw); // 公會頻道自己會檢查髒話、洗頻
                    return;
                }
                if (chat.plugin().party() == null || !chat.plugin().party().inParty(player)) {
                    chat.setChannel(player, ChatService.Channel.ALL);
                    player.sendMessage(chat.plugin().text().prefixed("<red>你不在隊伍裡，切回公開頻道了<gray>（剛剛那則沒送出）"));
                    return;
                }
                final ChatModeration.Verdict check = chat.moderation().check(player, raw);
                if (!check.allowed()) {
                    player.sendMessage(chat.plugin().text().prefixed("<red>" + tw.exilecore.item.ItemRenderer.escape(check.reason())));
                    return;
                }
                chat.plugin().party().chat(player, check.text());
            });
            return;
        }
        // 2026-10：不看公開聊天的人（/ch hide）收不到別人的公開聊天
        event.viewers().removeIf(viewer -> viewer instanceof org.bukkit.entity.Player other
                && !other.equals(event.getPlayer()) && chat.hidesPublic(other.getUniqueId()));
        // 聊天管理（2026-10）：禁言、洗頻、髒話（換成＊，拆開寫的不送出）
        final ChatModeration.Verdict verdict = chat.moderation().check(event.getPlayer(), raw);
        if (!verdict.allowed()) {
            event.setCancelled(true);
            event.getPlayer().sendMessage(chat.plugin().text().prefixed("<red>" + tw.exilecore.item.ItemRenderer.escape(verdict.reason())));
            return;
        }
        // 2026-10：聊天的名字「【稱號】角色名稱 » 訊息」，不要原版的 <>（等級、職業只在滑過名字的說明裡）；
        // 分隔用暗灰色的 »（原本的「: 」跟名字、英文訊息黏在一起不好分）
        event.renderer((source, sourceDisplayName, message, viewer) -> net.kyori.adventure.text.Component.text()
                .append(sourceDisplayName)
                .append(net.kyori.adventure.text.Component.text(" » ", net.kyori.adventure.text.format.NamedTextColor.DARK_GRAY))
                .append(message.colorIfAbsent(net.kyori.adventure.text.format.NamedTextColor.WHITE))
                .build());
        final String text = verdict.text();
        if (!ChatService.hasLinks(text)) {
            chat.takeLinked(event.getPlayer().getUniqueId()); // 上一則沒送出的物品不要留給這一則（Discord 同步用）
        }
        if (ChatService.hasLinks(text)) {
            event.message(chat.message(event.getPlayer(), text));
        } else if (text.indexOf('&') >= 0 && chat.plugin().sponsors() != null
                && chat.plugin().sponsors().chatColor(event.getPlayer().getUniqueId())) {
            // 2026-10：累積贊助的聊天上色（&a、&l、&#rrggbb；不給 &k 亂碼）
            event.message(tw.exilecore.sponsor.ChatColors.colorize(text));
        } else if (!text.equals(raw)) {
            event.message(net.kyori.adventure.text.Component.text(text));
        }
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onQuit(final PlayerQuitEvent event) {
        chat.forget(event.getPlayer().getUniqueId());
    }

    /**
     * 死亡訊息寫角色名稱（原版寫玩家 ID）。在競技場的 PvP（HIGH）之後：那裡把訊息拿掉了（不廣播）就不管。
     */
    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void onDeath(final PlayerDeathEvent event) {
        final net.kyori.adventure.text.Component message = event.deathMessage();
        if (message == null) {
            return;
        }
        final org.bukkit.entity.Player killer = event.getEntity().getKiller();
        event.deathMessage(chat.deathMessage(message, event.getEntity(), killer == null || killer.equals(event.getEntity()) ? null : killer));
    }
}
