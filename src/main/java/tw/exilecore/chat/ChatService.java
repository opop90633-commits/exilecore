package tw.exilecore.chat;

import net.kyori.adventure.key.Key;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.TextComponent;
import net.kyori.adventure.text.event.ClickEvent;
import net.kyori.adventure.text.event.DataComponentValue;
import net.kyori.adventure.text.event.HoverEvent;
import net.kyori.adventure.text.format.NamedTextColor;
import net.kyori.adventure.text.format.TextColor;
import net.kyori.adventure.text.serializer.plain.PlainTextComponentSerializer;
import org.bukkit.Bukkit;
import org.bukkit.Sound;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.PlayerInventory;
import tw.exilecore.ExileCore;
import tw.exilecore.item.ItemRenderer;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;

/**
 * 聊天：大家看到的名字是角色名稱（TAB、聊天、隊伍、交易、私訊都一樣），私訊用角色名稱（/w 角色名稱 訊息、/r 回覆），
 * 聊天裡的 [i] 換成手上的物品（{@link ItemLinks}）。
 * <p>
 * 在線的人的名字記在一張執行緒安全的表（聊天事件與指令補完不在主執行緒）；選角色、升級、改名時由角色模組更新。
 */
public final class ChatService {

    /** 私訊的顏色（PoE 的私訊是紫色）。 */
    static final TextColor WHISPER = TextColor.color(0xD6A8FF);
    private static final Key CUSTOM_DATA = Key.key("minecraft", "custom_data");

    private final ExileCore plugin;
    /** 在線的人：玩家 → 角色名稱與玩家 ID。 */
    private final Map<UUID, NameMatch.Candidate<UUID>> online = new ConcurrentHashMap<>();
    /** /r 回覆的對象：最後一個跟你私訊（你送給他或他送給你）的人。 */
    private final Map<UUID, UUID> replies = new ConcurrentHashMap<>();

    /** 聊天管理：禁言、洗頻、髒話、檢舉（2026-10）。 */
    private final ChatModeration moderation;
    /** 頭上的名牌（2026-10；模組啟用時設）。 */
    Nameplates nameplates;
    /** 剛送出的公開聊天貼的物品（Discord 同步拿去顯示；拿一次就清掉）。 */
    private final Map<UUID, List<ItemStack>> linked = new ConcurrentHashMap<>();

    public ChatService(final ExileCore plugin) {
        this.plugin = plugin;
        this.moderation = new ChatModeration(plugin);
    }

    ExileCore plugin() {
        return plugin;
    }

    public ChatModeration moderation() {
        return moderation;
    }

    /** 頭上的名牌：名字、稱號、公會變了之後重畫。 */
    public void refreshNameplate(final Player player) {
        if (nameplates != null) {
            nameplates.refresh(player);
        }
    }

    // ---- 名字 ----

    /** 更新這個人的名字（上線、選角色、升級、改名；characterName 是 null＝還沒選角色）。 */
    public void refresh(final Player player, final String characterName) {
        online.put(player.getUniqueId(), new NameMatch.Candidate<>(player.getUniqueId(), characterName, player.getName()));
    }

    /** 下線。 */
    void forget(final UUID player) {
        online.remove(player);
        replies.remove(player);
        moderation.forget(player);
    }

    /** 別人看到的名字：角色名稱（還沒選角色時是玩家 ID）。 */
    public String display(final Player player) {
        final NameMatch.Candidate<UUID> entry = online.get(player.getUniqueId());
        return entry == null ? player.getName() : entry.shown();
    }

    // ---- 死亡訊息 ----

    /**
     * 原版的死亡訊息寫的是玩家 ID（「teddys.jb 被 Lv.65 潮汐祭司 殺死了」）：死掉的人（與殺他的玩家）換成角色名稱，
     * 滑過去看等級與職業、按一下私訊（跟聊天的名字一樣）。還沒選角色的人照舊寫玩家 ID。
     */
    public Component deathMessage(final Component message, final Player victim, final Player killer) {
        final Map<String, Component> names = new HashMap<>();
        for (final Player player : new Player[] {victim, killer}) {
            if (player != null && plugin.players() != null) {
                final Component name = plugin.players().nameComponent(player);
                if (name != null) {
                    names.put(player.getName(), name);
                }
            }
        }
        return names.isEmpty() ? message : PlayerNames.rename(message, names);
    }

    /** 用角色名稱或玩家 ID 找在線的人（主執行緒；看不到的人，例如隱身的管理員，不算）。 */
    public NameMatch.Result<Player> find(final CommandSender viewer, final String query) {
        final List<NameMatch.Candidate<Player>> candidates = new ArrayList<>();
        for (final NameMatch.Candidate<UUID> entry : online.values()) {
            final Player player = plugin.getServer().getPlayer(entry.value());
            if (player != null && visible(viewer, player)) {
                candidates.add(new NameMatch.Candidate<>(player, entry.character(), entry.account()));
            }
        }
        return NameMatch.resolve(candidates, query);
    }

    private static boolean visible(final CommandSender viewer, final Player target) {
        return !(viewer instanceof Player player) || player.getUniqueId().equals(target.getUniqueId()) || player.canSee(target);
    }

    /** 找不到或同名時告訴找的人；找到就回傳。 */
    public Player findOrTell(final CommandSender viewer, final String query) {
        final NameMatch.Result<Player> result = find(viewer, query);
        if (result.found() != null) {
            return result.found();
        }
        if (result.ambiguous().isEmpty()) {
            viewer.sendMessage(plugin.text().prefixed("<red>找不到在線的角色「" + ItemRenderer.escape(query) + "」"));
            return null;
        }
        final TextComponent.Builder line = Component.text().append(plugin.text().prefixed(
                "<gray>有 " + result.ambiguous().size() + " 個角色叫「" + ItemRenderer.escape(query) + "」，請改打玩家 ID："));
        boolean first = true;
        for (final NameMatch.Candidate<Player> candidate : result.ambiguous()) {
            if (!first) {
                line.append(Component.text("、", NamedTextColor.GRAY));
            }
            first = false;
            line.append(Component.text(candidate.account(), NamedTextColor.WHITE));
        }
        viewer.sendMessage(line.build());
        return null;
    }

    /** 指令補完：在線的名字（不含自己、看不到的人）。 */
    public List<String> suggest(final String typed, final CommandSender self) {
        final List<NameMatch.Candidate<UUID>> others = new ArrayList<>();
        for (final NameMatch.Candidate<UUID> entry : online.values()) {
            if (self instanceof Player player) {
                if (player.getUniqueId().equals(entry.value())) {
                    continue;
                }
                final Player target = plugin.getServer().getPlayer(entry.value());
                if (target == null || !player.canSee(target)) {
                    continue;
                }
            }
            others.add(entry);
        }
        return NameMatch.suggest(others, typed);
    }

    // ---- 物品連結 ----

    /** 玩家打的字轉成訊息：[i] 換成物品連結（任何執行緒；物品在主執行緒讀）。 */
    public Component message(final Player sender, final String text) {
        final List<ItemLinks.Part> parts = ItemLinks.parse(text);
        if (!ItemLinks.hasLinks(parts)) {
            return Component.text(text);
        }
        final Map<Integer, ItemStack> items = snapshot(sender, ItemLinks.slots(parts));
        final List<ItemStack> shown = new ArrayList<>();
        for (final ItemLinks.Part part : parts) {
            if (part instanceof ItemLinks.Link link && items.get(link.slot()) != null) {
                shown.add(items.get(link.slot()));
            }
        }
        if (shown.isEmpty()) {
            linked.remove(sender.getUniqueId());
        } else {
            linked.put(sender.getUniqueId(), shown);
        }
        return render(parts, items);
    }

    /** 這個玩家剛剛那則公開聊天貼的物品（照訊息裡的順序；拿一次就清掉，沒有回傳空的）。 */
    public List<ItemStack> takeLinked(final UUID player) {
        final List<ItemStack> items = linked.remove(player);
        return items == null ? List.of() : items;
    }

    /** 這則訊息有沒有物品連結。 */
    static boolean hasLinks(final String text) {
        return ItemLinks.hasLinks(ItemLinks.parse(text));
    }

    /** 讀那幾格的物品（複製一份；不在主執行緒時回主執行緒讀，最多等 2 秒）。 */
    private Map<Integer, ItemStack> snapshot(final Player player, final Set<Integer> slots) {
        if (Bukkit.isPrimaryThread()) {
            return read(player, slots);
        }
        try {
            return plugin.getServer().getScheduler().callSyncMethod(plugin, () -> read(player, slots)).get(2, TimeUnit.SECONDS);
        } catch (final Exception e) {
            return Map.of();
        }
    }

    private Map<Integer, ItemStack> read(final Player player, final Set<Integer> slots) {
        final Map<Integer, ItemStack> items = new HashMap<>();
        if (!player.isOnline()) {
            return items;
        }
        final PlayerInventory inventory = player.getInventory();
        for (final int slot : slots) {
            ItemStack item = slot == ItemLinks.MAIN_HAND ? inventory.getItemInMainHand() : inventory.getItem(slot);
            if (tw.exilecore.character.Gear.isDisplay(item)) {
                // 顯示用的複本：連結裝在角色面板上的那件
                item = tw.exilecore.character.Gear.realAt(plugin.players().get(player),
                        slot == ItemLinks.MAIN_HAND ? tw.exilecore.character.Gear.HAND_SLOT : slot);
            }
            if (item != null && !item.isEmpty()) {
                items.put(slot, item.clone());
            }
        }
        return items;
    }

    private Component render(final List<ItemLinks.Part> parts, final Map<Integer, ItemStack> items) {
        final TextComponent.Builder out = Component.text();
        for (final ItemLinks.Part part : parts) {
            if (part instanceof ItemLinks.Text text) {
                out.append(Component.text(text.text()));
            } else if (part instanceof ItemLinks.Link link) {
                final ItemStack item = items.get(link.slot());
                out.append(item == null ? Component.text(link.token()) : link(item));
            }
        }
        return out.build();
    }

    /** 「[物品名稱]」：名稱的顏色（稀有度），滑過去是完整的物品說明（跟背包裡看到的一樣）。 */
    static Component link(final ItemStack item) {
        final Component name = itemName(item);
        final TextColor found = firstColor(name);
        final TextColor color = found == null ? NamedTextColor.WHITE : found;
        final TextComponent.Builder text = Component.text().color(color)
                .append(Component.text("["))
                .append(name);
        if (item.getAmount() > 1) {
            text.append(Component.text(" ×" + item.getAmount()));
        }
        text.append(Component.text("]"));
        // 插件自己的資料（custom_data）不送給別人：說明一樣，訊息小很多
        final HoverEvent<HoverEvent.ShowItem> hover = item.asHoverEvent(show -> {
            final Map<Key, DataComponentValue> components = new LinkedHashMap<>(show.dataComponents());
            components.remove(CUSTOM_DATA);
            return show.dataComponents(components);
        });
        return text.build().hoverEvent(hover);
    }

    /** 物品自己的名字（ItemRenderer 寫的，帶稀有度的顏色；不是斜體）；原版物品用原版的名字。沒有中括號。 */
    public static Component itemName(final ItemStack item) {
        Component name = item.getData(io.papermc.paper.datacomponent.DataComponentTypes.CUSTOM_NAME);
        if (name == null) {
            name = item.getData(io.papermc.paper.datacomponent.DataComponentTypes.ITEM_NAME);
        }
        if (name == null) {
            name = item.effectiveName();
        }
        return name.decorationIfAbsent(net.kyori.adventure.text.format.TextDecoration.ITALIC,
                net.kyori.adventure.text.format.TextDecoration.State.FALSE);
    }

    /** 元件裡第一個有設定的顏色（名字的顏色常在子元件上）。 */
    public static TextColor firstColor(final Component component) {
        if (component.color() != null) {
            return component.color();
        }
        for (final Component child : component.children()) {
            final TextColor color = firstColor(child);
            if (color != null) {
                return color;
            }
        }
        return null;
    }

    // ---- 私訊 ----

    /** /w 角色名稱 訊息。 */
    public void whisper(final CommandSender from, final String input) {
        final String[] split = NameMatch.splitTarget(input);
        if (split[0].isEmpty() || split[1].isEmpty()) {
            from.sendMessage(plugin.text().prefixed("<gray>用法：<white>/w 角色名稱 訊息<gray>（訊息裡打 <white>[i]<gray> 可以貼出手上的物品）"));
            return;
        }
        final Player target = findOrTell(from, split[0]);
        if (target != null) {
            send(from, target, split[1]);
        }
    }

    /** /r 訊息：回覆最後一個私訊的人。 */
    public void reply(final Player from, final String message) {
        final UUID last = replies.get(from.getUniqueId());
        final Player target = last == null ? null : plugin.getServer().getPlayer(last);
        if (target == null) {
            from.sendMessage(plugin.text().prefixed(last == null ? "<gray>還沒有人私訊你（/w 角色名稱 訊息）" : "<red>對方已經離線了"));
            return;
        }
        if (message == null || message.isBlank()) {
            from.sendMessage(plugin.text().prefixed("<gray>用法：<white>/r 訊息<gray>（回覆 " + ItemRenderer.escape(display(target)) + "）"));
            return;
        }
        send(from, target, message.strip());
    }

    private void send(final CommandSender from, final Player to, final String text) {
        final Player sender = from instanceof Player player ? player : null;
        if (sender != null && sender.getUniqueId().equals(to.getUniqueId())) {
            from.sendMessage(plugin.text().prefixed("<gray>不能私訊自己"));
            return;
        }
        String sent = text;
        if (sender != null) {
            final ChatModeration.Verdict verdict = moderation.check(sender, text);
            if (!verdict.allowed()) {
                sender.sendMessage(plugin.text().prefixed("<red>" + ItemRenderer.escape(verdict.reason())));
                return;
            }
            sent = verdict.text();
        }
        final Component body = sender == null ? Component.text(sent) : message(sender, sent);
        final String fromName = sender == null ? "伺服器" : display(sender);
        final String toName = display(to);
        to.sendMessage(Component.text().color(WHISPER)
                .append(Component.text("@來自 "))
                .append(name(fromName, sender != null))
                .append(Component.text("："))
                .append(body)
                .build());
        from.sendMessage(Component.text().color(WHISPER)
                .append(Component.text("@給 "))
                .append(name(toName, true))
                .append(Component.text("："))
                .append(body)
                .build());
        to.playSound(to.getLocation(), Sound.BLOCK_NOTE_BLOCK_CHIME, 0.5f, 1.4f);
        if (sender != null) {
            replies.put(to.getUniqueId(), sender.getUniqueId());
            replies.put(sender.getUniqueId(), to.getUniqueId());
        }
        plugin.getSLF4JLogger().info("[私訊] {} → {}：{}", fromName, toName, PlainTextComponentSerializer.plainText().serialize(body));
    }

    /** 名字：按一下開始私訊他。 */
    private static Component name(final String name, final boolean clickable) {
        final Component text = Component.text(name, NamedTextColor.WHITE);
        return clickable
                ? text.clickEvent(ClickEvent.suggestCommand("/w " + name + " ")).hoverEvent(HoverEvent.showText(Component.text("按一下私訊 " + name)))
                : text;
    }
}
