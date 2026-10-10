package tw.exilecore.gui;

import io.papermc.paper.event.player.AsyncChatEvent;
import net.kyori.adventure.text.serializer.plain.PlainTextComponentSerializer;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerQuitEvent;
import tw.exilecore.ExileCore;

import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Consumer;

/**
 * 「請在聊天輸入…」的擷取：選單裡按下按鈕後關掉視窗，玩家下一句聊天不會廣播，而是交給回呼。
 * 輸入「取消」放棄；60 秒沒回應就作廢。回呼在主執行緒上執行。
 */
public final class ChatPrompts implements Listener {

    private record Prompt(Consumer<String> handler, Runnable onCancel, long expiresAt) {
    }

    private static final long PROMPT_MILLIS = 60_000;

    private final ExileCore plugin;
    /** 聊天事件在非同步執行緒上觸發，所以用執行緒安全的 Map。 */
    private final Map<UUID, Prompt> prompts = new ConcurrentHashMap<>();

    public ChatPrompts(final ExileCore plugin) {
        this.plugin = plugin;
    }

    /**
     * 關掉視窗並請玩家輸入。
     *
     * @param question MiniMessage 提示（會加前綴）
     * @param handler  玩家輸入的文字（已去頭尾空白）
     * @param onCancel 取消或逾時時做的事（可為 null）
     */
    public void ask(final Player player, final String question, final Consumer<String> handler, final Runnable onCancel) {
        // 用 lambda 而不是 player::closeInventory：closeInventory 有兩個多載，方法參照會讓 runTask 的兩個多載都符合而編譯失敗
        plugin.getServer().getScheduler().runTask(plugin, () -> player.closeInventory());
        prompts.put(player.getUniqueId(), new Prompt(handler, onCancel, System.currentTimeMillis() + PROMPT_MILLIS));
        player.sendMessage(plugin.text().prefixed(question + " <dark_gray>（60 秒內有效，輸入「取消」放棄）"));
    }

    public boolean isWaiting(final Player player) {
        return prompts.containsKey(player.getUniqueId());
    }

    @EventHandler(priority = EventPriority.LOWEST)
    public void onChat(final AsyncChatEvent event) {
        final Player player = event.getPlayer();
        final Prompt prompt = prompts.remove(player.getUniqueId());
        if (prompt == null) {
            return;
        }
        // 這個事件在非同步執行緒上：這裡只擷取文字，其他事回主執行緒做
        event.setCancelled(true);
        final String text = PlainTextComponentSerializer.plainText().serialize(event.message()).trim();
        plugin.getServer().getScheduler().runTask(plugin, () -> {
            if (!player.isOnline()) {
                return;
            }
            if (System.currentTimeMillis() > prompt.expiresAt()) {
                player.sendMessage(plugin.text().prefixed("<red>逾時了，請重新操作"));
                if (prompt.onCancel() != null) {
                    prompt.onCancel().run();
                }
                return;
            }
            if (text.equals("取消") || text.equalsIgnoreCase("cancel")) {
                player.sendMessage(plugin.text().prefixed("<gray>已取消"));
                if (prompt.onCancel() != null) {
                    prompt.onCancel().run();
                }
                return;
            }
            prompt.handler().accept(text);
        });
    }

    @EventHandler
    public void onQuit(final PlayerQuitEvent event) {
        prompts.remove(event.getPlayer().getUniqueId());
    }
}
