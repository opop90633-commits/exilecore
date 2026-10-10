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
        // 聊天管理（2026-10）：禁言、洗頻、髒話（換成＊，拆開寫的不送出）
        final ChatModeration.Verdict verdict = chat.moderation().check(event.getPlayer(), raw);
        if (!verdict.allowed()) {
            event.setCancelled(true);
            event.getPlayer().sendMessage(chat.plugin().text().prefixed("<red>" + tw.exilecore.item.ItemRenderer.escape(verdict.reason())));
            return;
        }
        // 2026-10：聊天的名字「【稱號】角色名稱: 訊息」，不要原版的 <>（等級、職業只在滑過名字的說明裡）
        event.renderer((source, sourceDisplayName, message, viewer) -> net.kyori.adventure.text.Component.text()
                .append(sourceDisplayName)
                .append(net.kyori.adventure.text.Component.text(": ", net.kyori.adventure.text.format.NamedTextColor.GRAY))
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
