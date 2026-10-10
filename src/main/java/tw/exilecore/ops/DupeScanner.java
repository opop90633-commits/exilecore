package tw.exilecore.ops;

import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;
import tw.exilecore.ExileCore;
import tw.exilecore.character.Bag;
import tw.exilecore.character.PlayerState;
import tw.exilecore.item.ItemData;
import tw.exilecore.stash.Stash;

import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

/**
 * 找複製（/ec dupes）：同一個物品 UUID 出現在兩個地方就是複製。
 * 在線的玩家看身上的（背包、游標、飾品、隨身背包、倉庫），其他看資料庫（沒在用的角色、離線帳號的倉庫），再加上市集上架中的。
 * 資料庫在背景讀，物品在主執行緒每 tick 解一點，不會卡伺服器。
 */
final class DupeScanner {

    /** 每 tick 解幾筆資料庫的列。 */
    private static final int ROWS_PER_TICK = 20;

    /** 資料庫裡的一欄物品；jewels 是天賦樹珠寶的格式（節點代號＋珠寶）。 */
    private record Row(String where, byte[] bytes, boolean jewels) {
        Row(final String where, final byte[] bytes) {
            this(where, bytes, false);
        }
    }

    private final ExileCore plugin;
    private final CommandSender sender;
    private final Map<String, List<String>> seen = new LinkedHashMap<>();
    private int scanned;

    DupeScanner(final ExileCore plugin, final CommandSender sender) {
        this.plugin = plugin;
        this.sender = sender;
    }

    void start() {
        // 在線的：用身上的（資料庫裡的可能是舊的）
        final Set<Long> liveCharacters = new HashSet<>();
        final Set<UUID> liveStashes = new HashSet<>();
        for (final Player player : plugin.getServer().getOnlinePlayers()) {
            final PlayerState state = plugin.players().get(player);
            if (!state.isLoaded() || !state.hasCharacter()) {
                continue;
            }
            liveCharacters.add(state.character().id());
            final String who = player.getName() + "（在線，角色 " + state.character().name() + "）";
            if (plugin.loot() != null) {
                plugin.loot().syncOpenBag(player);
            }
            for (final ItemStack stack : player.getInventory().getContents()) {
                add(stack, who + " 背包");
            }
            add(player.getItemOnCursor(), who + " 游標");
            for (final ItemStack stack : state.jewellery()) {
                add(stack, who + " 飾品");
            }
            for (final ItemStack stack : state.jewels().values()) {
                add(stack, who + " 天賦樹珠寶");
            }
            final Bag bag = state.bag();
            if (bag.usable()) {
                for (int page = 0; page < bag.storedPages(); page++) {
                    for (final ItemStack stack : bag.page(page)) {
                        add(stack, who + " 隨身背包");
                    }
                }
            }
            if (plugin.stash() != null) {
                plugin.stash().snapshot(player); // 倉庫開著的話先把畫面同步回去
                final Optional<Stash> stash = plugin.stash().stash(player);
                if (stash.isPresent()) {
                    liveStashes.add(player.getUniqueId());
                    for (int page = 0; page < stash.get().pageCount(); page++) {
                        for (final ItemStack stack : stash.get().page(page)) {
                            add(stack, player.getName() + " 的倉庫第 " + (page + 1) + " 頁");
                        }
                    }
                }
            }
        }
        if (plugin.market() != null) {
            for (final Map.Entry<String, String> entry : plugin.market().listedItemUuids().entrySet()) {
                seen.computeIfAbsent(entry.getKey(), key -> new ArrayList<>()).add(entry.getValue());
                scanned++;
            }
        }
        sender.sendMessage(plugin.text().prefixed("<gray>找複製：在線的看完了（" + scanned + " 件），接著讀資料庫…"));
        plugin.database().async(connection -> {
            final List<Row> rows = new ArrayList<>();
            try (PreparedStatement statement = connection.prepareStatement(
                    "SELECT c.id, c.name, a.name AS account_name, i.contents, i.jewellery, i.bag, i.jewels FROM characters c "
                            + "JOIN character_inventory i ON i.character_id = c.id LEFT JOIN accounts a ON a.uuid = c.account_uuid "
                            + "WHERE c.deleted_at IS NULL");
                 ResultSet result = statement.executeQuery()) {
                while (result.next()) {
                    if (liveCharacters.contains(result.getLong("id"))) {
                        continue;
                    }
                    final String who = result.getString("account_name") + "（角色 " + result.getString("name") + "）";
                    rows.add(new Row(who + " 背包", result.getBytes("contents")));
                    rows.add(new Row(who + " 飾品", result.getBytes("jewellery")));
                    rows.add(new Row(who + " 隨身背包", result.getBytes("bag")));
                    rows.add(new Row(who + " 天賦樹珠寶", result.getBytes("jewels"), true));
                }
            }
            try (PreparedStatement statement = connection.prepareStatement(
                    "SELECT s.account_uuid, s.page, s.contents, a.name AS account_name FROM stash_pages s "
                            + "LEFT JOIN accounts a ON a.uuid = s.account_uuid");
                 ResultSet result = statement.executeQuery()) {
                while (result.next()) {
                    if (liveStashes.contains(UUID.fromString(result.getString("account_uuid")))) {
                        continue;
                    }
                    rows.add(new Row(result.getString("account_name") + " 的倉庫第 " + (result.getInt("page") + 1) + " 頁",
                            result.getBytes("contents")));
                }
            }
            return rows;
        }).whenComplete((rows, error) -> plugin.getServer().getScheduler().runTask(plugin, () -> {
            if (error != null) {
                plugin.getSLF4JLogger().error("找複製：讀資料庫失敗", error);
                sender.sendMessage(plugin.text().prefixed("<red>讀資料庫失敗（看主控台）"));
                return;
            }
            decode(rows, 0);
        }));
    }

    /** 每 tick 解一點。 */
    private void decode(final List<Row> rows, final int from) {
        final int to = Math.min(rows.size(), from + ROWS_PER_TICK);
        for (int i = from; i < to; i++) {
            final Row row = rows.get(i);
            if (row.bytes() == null || row.bytes().length == 0) {
                continue;
            }
            try {
                final java.util.Collection<ItemStack> stacks = row.jewels()
                        ? tw.exilecore.character.InventoryCodec.decodeJewels(row.bytes()).values()
                        : List.of(ItemStack.deserializeItemsFromBytes(row.bytes()));
                for (final ItemStack stack : stacks) {
                    add(stack, row.where());
                }
            } catch (final RuntimeException e) {
                sender.sendMessage(plugin.text().prefixed("<red>讀不出來：" + row.where()));
            }
        }
        if (to < rows.size()) {
            plugin.getServer().getScheduler().runTaskLater(plugin, () -> decode(rows, to), 1);
            return;
        }
        report();
    }

    private void add(final ItemStack stack, final String where) {
        if (stack == null || stack.isEmpty()) {
            return;
        }
        final Optional<ItemData> data = plugin.items().read(stack);
        if (data.isEmpty() || data.get().uuid() == null) {
            return; // 通貨、寶石這類沒有 UUID 的不算
        }
        scanned++;
        seen.computeIfAbsent(data.get().uuid(), key -> new ArrayList<>()).add(where);
    }

    private void report() {
        int duplicates = 0;
        final List<String> lines = new ArrayList<>();
        for (final Map.Entry<String, List<String>> entry : seen.entrySet()) {
            if (entry.getValue().size() < 2) {
                continue;
            }
            duplicates++;
            if (lines.size() < 20) {
                lines.add("<red>" + entry.getKey() + "</red> <gray>× " + entry.getValue().size() + "：" + String.join("、", entry.getValue()));
            }
        }
        if (duplicates == 0) {
            sender.sendMessage(plugin.text().prefixed("<green>找複製：看了 " + scanned + " 件裝備，沒有重複的 UUID"));
            return;
        }
        sender.sendMessage(plugin.text().prefixed("<red>找複製：看了 " + scanned + " 件，有 " + duplicates + " 個 UUID 重複（/ec trace UUID 查來源）"));
        for (final String line : lines) {
            sender.sendMessage(plugin.text().parse("  " + line));
        }
        plugin.getSLF4JLogger().warn("找複製：{} 個 UUID 重複", duplicates);
    }
}
