package tw.exilecore.economy;

import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;
import tw.exilecore.ExileCore;
import tw.exilecore.character.Bag;
import tw.exilecore.character.PlayerState;
import tw.exilecore.item.ItemData;
import tw.exilecore.item.UniqueItem;
import tw.exilecore.stash.Stash;

import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.function.Consumer;

/**
 * 2026-11-02 照站長：全服現在有幾件追逐級傳奇（市場報告追逐級那一段的「全服現有」）。
 * 在線的看身上（背包、游標、飾品、天賦樹珠寶、隨身背包、倉庫），離線的讀資料庫（角色的背包、飾品、隨身背包、天賦樹珠寶、倉庫），
 * 再加上市集上架的、公會倉庫的。同一件（物品 UUID）只算一次；管理員（op、economy.yml 的 report.exclude_players）的不算，
 * 公會倉庫不分是誰的照算。資料庫在資料庫執行緒讀，物品在主執行緒每 tick 解一點（跟找複製一樣）。
 */
final class ChaseCensus {

    private static final int ROWS_PER_TICK = 20;

    private record Row(byte[] bytes, int format) {
    }

    /** 列的格式：一串物品、天賦樹珠寶（節點＋珠寶）、一件物品（市集）。 */
    private static final int ITEMS = 0;
    private static final int JEWELS = 1;
    private static final int SINGLE = 2;

    private final ExileCore plugin;
    private final Consumer<Map<String, Long>> done;
    private final Map<String, Long> counts = new HashMap<>();
    private final Set<String> seen = new HashSet<>();

    ChaseCensus(final ExileCore plugin, final Consumer<Map<String, Long>> done) {
        this.plugin = plugin;
        this.done = done;
    }

    void start() {
        final EconomyLedger ledger = plugin.economy().ledger();
        final EconomyConfig config = plugin.economy().config();
        final Set<String> opAccounts = new HashSet<>();
        for (final org.bukkit.OfflinePlayer op : plugin.getServer().getOperators()) {
            opAccounts.add(op.getUniqueId().toString());
        }
        final Set<Long> liveCharacters = new HashSet<>();
        final Set<String> liveStashes = new HashSet<>();
        for (final Player player : plugin.getServer().getOnlinePlayers()) {
            final PlayerState state = plugin.players().get(player);
            if (!state.isLoaded() || !state.hasCharacter()) {
                continue;
            }
            liveCharacters.add(state.character().id());
            liveStashes.add(player.getUniqueId().toString());
            if (ledger.excluded(player)) {
                continue;
            }
            for (final ItemStack stack : player.getInventory().getContents()) {
                add(stack);
            }
            add(player.getItemOnCursor());
            for (final ItemStack stack : state.jewellery()) {
                add(stack);
            }
            for (final ItemStack stack : state.jewels().values()) {
                add(stack);
            }
            final Bag bag = state.bag();
            if (bag.usable()) {
                for (int page = 0; page < bag.storedPages(); page++) {
                    for (final ItemStack stack : bag.page(page)) {
                        add(stack);
                    }
                }
            }
            if (plugin.stash() != null) {
                final Optional<Stash> stash = plugin.stash().stash(player);
                if (stash.isPresent()) {
                    for (int page = 0; page < stash.get().pageCount(); page++) {
                        for (final ItemStack stack : stash.get().page(page)) {
                            add(stack);
                        }
                    }
                }
            }
        }
        final Set<String> excludedNames = config.reportExcludedNames();
        plugin.database().async(connection -> {
            final Set<String> skip = new HashSet<>(opAccounts);
            try (PreparedStatement statement = connection.prepareStatement("SELECT uuid, name FROM accounts");
                 ResultSet result = statement.executeQuery()) {
                while (result.next()) {
                    final String name = result.getString(2);
                    if (name != null && excludedNames.contains(name.toLowerCase(java.util.Locale.ROOT))) {
                        skip.add(result.getString(1));
                    }
                }
            }
            final List<Row> rows = new ArrayList<>();
            try (PreparedStatement statement = connection.prepareStatement(
                    "SELECT c.id, c.account_uuid, i.contents, i.jewellery, i.bag, i.jewels FROM characters c "
                            + "JOIN character_inventory i ON i.character_id = c.id WHERE c.deleted_at IS NULL");
                 ResultSet result = statement.executeQuery()) {
                while (result.next()) {
                    if (liveCharacters.contains(result.getLong(1)) || skip.contains(result.getString(2))) {
                        continue;
                    }
                    rows.add(new Row(result.getBytes(3), ITEMS));
                    rows.add(new Row(result.getBytes(4), ITEMS));
                    rows.add(new Row(result.getBytes(5), ITEMS));
                    rows.add(new Row(result.getBytes(6), JEWELS));
                }
            }
            try (PreparedStatement statement = connection.prepareStatement("SELECT account_uuid, page, contents FROM stash_pages");
                 ResultSet result = statement.executeQuery()) {
                while (result.next()) {
                    final String account = result.getString(1);
                    if (liveStashes.contains(account) || skip.contains(account) || Stash.mapShardOf(result.getInt(2)) >= 0
                            || result.getInt(2) == Stash.MAP_STORE_KEY) {
                        continue; // 地圖頁沒有傳奇
                    }
                    rows.add(new Row(result.getBytes(3), ITEMS));
                }
            }
            try (PreparedStatement statement = connection.prepareStatement("SELECT account_uuid, item FROM market_listings");
                 ResultSet result = statement.executeQuery()) {
                while (result.next()) {
                    if (!skip.contains(result.getString(1))) {
                        rows.add(new Row(result.getBytes(2), SINGLE));
                    }
                }
            } catch (final java.sql.SQLException e) {
                plugin.getSLF4JLogger().warn("追逐級傳奇統計：市集讀不到（跳過）：{}", e.toString());
            }
            try (PreparedStatement statement = connection.prepareStatement("SELECT contents FROM guild_stash WHERE page >= 0");
                 ResultSet result = statement.executeQuery()) {
                while (result.next()) {
                    rows.add(new Row(result.getBytes(1), ITEMS));
                }
            } catch (final java.sql.SQLException e) {
                plugin.getSLF4JLogger().warn("追逐級傳奇統計：公會倉庫讀不到（跳過）：{}", e.toString());
            }
            return rows;
        }).whenComplete((rows, error) -> plugin.getServer().getScheduler().runTask(plugin, () -> {
            if (error != null) {
                plugin.getSLF4JLogger().warn("追逐級傳奇統計：讀資料庫失敗：{}", error.getMessage());
                done.accept(Map.copyOf(counts));
                return;
            }
            decode(rows, 0);
        }));
    }

    private void decode(final List<Row> rows, final int from) {
        final int to = Math.min(rows.size(), from + ROWS_PER_TICK);
        for (int i = from; i < to; i++) {
            final Row row = rows.get(i);
            if (row.bytes() == null || row.bytes().length == 0) {
                continue;
            }
            try {
                final Collection<ItemStack> stacks = switch (row.format()) {
                    case JEWELS -> tw.exilecore.character.InventoryCodec.decodeJewels(row.bytes()).values();
                    case SINGLE -> List.of(ItemStack.deserializeBytes(row.bytes()));
                    default -> List.of(ItemStack.deserializeItemsFromBytes(row.bytes()));
                };
                for (final ItemStack stack : stacks) {
                    add(stack);
                }
            } catch (final RuntimeException e) {
                // 讀不出來的跳過（找複製會報）
            }
        }
        if (to < rows.size()) {
            plugin.getServer().getScheduler().runTaskLater(plugin, () -> decode(rows, to), 1);
            return;
        }
        done.accept(Map.copyOf(counts));
    }

    private void add(final ItemStack stack) {
        if (stack == null || stack.isEmpty() || plugin.items() == null) {
            return;
        }
        final Optional<ItemData> data = plugin.items().read(stack);
        if (data.isEmpty() || data.get().unique() == null) {
            return;
        }
        final UniqueItem unique = plugin.items().repository().unique(data.get().unique());
        if (unique == null || !unique.chase()) {
            return;
        }
        final String uuid = data.get().uuid();
        if (uuid != null && !seen.add(uuid)) {
            return; // 同一件（例如在線的人倉庫開著、資料庫也有）只算一次
        }
        counts.merge(unique.id(), 1L, Long::sum);
    }
}
