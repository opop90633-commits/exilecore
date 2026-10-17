package tw.exilecore.ops;

import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;
import tw.exilecore.ExileCore;
import tw.exilecore.character.InventoryCodec;
import tw.exilecore.character.PlayerState;

import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * 補正離線的物品（/ec refreshitems，2026-10）：傳奇改了詞綴（uniques.yml 的 version）、物品的畫法改版之後，
 * 把資料庫裡沒在線的角色（背包、飾品、隨身背包、天賦樹珠寶與其他裝備組）與離線帳號的倉庫一次補正、寫回去。
 * <p>
 * 在線的人不用：登入時本來就會補（屬性也一直照新的算），這裡跳過他們正在用的角色與倉庫。
 * 寫回時看 updated_at 有沒有變——讀完之後那個角色或倉庫被存過（有人上線動過），那一筆就不寫，不會蓋掉新的東西。
 * 資料庫在背景讀寫，物品在主執行緒每 tick 補一點，不會卡伺服器。
 */
public final class ItemRefresher {

    private static final int ROWS_PER_TICK = 20;

    /** 一欄物品；kind：contents、jewellery、bag、jewels（天賦樹珠寶的格式）、stash。 */
    private record Row(String kind, long characterId, String account, int page, Timestamp updatedAt, byte[] bytes) {
    }

    private record Change(Row row, byte[] bytes) {
    }

    private final ExileCore plugin;
    private final CommandSender sender;
    private final List<Change> changes = new ArrayList<>();
    private int items;
    private int fixed;
    private int broken;

    public ItemRefresher(final ExileCore plugin, final CommandSender sender) {
        this.plugin = plugin;
        this.sender = sender;
    }

    public void start() {
        final Set<Long> liveCharacters = new HashSet<>();
        final Set<String> liveStashes = new HashSet<>();
        for (final Player player : plugin.getServer().getOnlinePlayers()) {
            final PlayerState state = plugin.players().get(player);
            liveStashes.add(player.getUniqueId().toString());
            if (state.isLoaded() && state.hasCharacter()) {
                liveCharacters.add(state.character().id());
            }
        }
        sender.sendMessage(plugin.text().prefixed("<gray>補正離線物品：讀資料庫…（在線的人登入時本來就會補，跳過）"));
        plugin.database().async(connection -> {
            final List<Row> rows = new ArrayList<>();
            try (PreparedStatement statement = connection.prepareStatement(
                    "SELECT i.character_id, i.contents, i.jewellery, i.bag, i.jewels, i.updated_at FROM character_inventory i "
                            + "JOIN characters c ON c.id = i.character_id WHERE c.deleted_at IS NULL");
                 ResultSet result = statement.executeQuery()) {
                while (result.next()) {
                    final long id = result.getLong("character_id");
                    if (liveCharacters.contains(id)) {
                        continue;
                    }
                    final Timestamp updated = result.getTimestamp("updated_at");
                    for (final String column : new String[] {"contents", "jewellery", "bag", "jewels"}) {
                        final byte[] bytes = result.getBytes(column);
                        if (bytes != null && bytes.length > 0) {
                            rows.add(new Row(column, id, null, 0, updated, bytes));
                        }
                    }
                }
            }
            try (PreparedStatement statement = connection.prepareStatement(
                    "SELECT account_uuid, page, contents, updated_at FROM stash_pages");
                 ResultSet result = statement.executeQuery()) {
                while (result.next()) {
                    final String account = result.getString("account_uuid");
                    if (liveStashes.contains(account)) {
                        continue;
                    }
                    final byte[] bytes = result.getBytes("contents");
                    if (bytes != null && bytes.length > 0) {
                        rows.add(new Row("stash", 0, account, result.getInt("page"), result.getTimestamp("updated_at"), bytes));
                    }
                }
            }
            return rows;
        }).whenComplete((rows, error) -> plugin.getServer().getScheduler().runTask(plugin, () -> {
            if (error != null) {
                plugin.getSLF4JLogger().error("補正離線物品：讀資料庫失敗", error);
                sender.sendMessage(plugin.text().prefixed("<red>讀資料庫失敗（看主控台）"));
                return;
            }
            sender.sendMessage(plugin.text().prefixed("<gray>讀到 " + rows.size() + " 欄，開始補正…"));
            process(rows, 0);
        }));
    }

    private void process(final List<Row> rows, final int from) {
        final int to = Math.min(rows.size(), from + ROWS_PER_TICK);
        for (int i = from; i < to; i++) {
            final Row row = rows.get(i);
            try {
                boolean changed = false;
                final byte[] out;
                if (row.kind().equals("jewels")) {
                    final Map<String, ItemStack> jewels = InventoryCodec.decodeJewels(row.bytes());
                    for (final ItemStack stack : jewels.values()) {
                        changed |= refresh(stack);
                    }
                    out = changed ? InventoryCodec.encodeJewels(jewels) : null;
                } else {
                    final ItemStack[] stacks = ItemStack.deserializeItemsFromBytes(row.bytes());
                    if (plugin.skills() != null) {
                        final java.util.List<ItemStack> leftover = plugin.skills().gems().migrateArrays(java.util.List.<ItemStack[]>of(stacks));
                        if (!leftover.isEmpty()) {
                            plugin.getSLF4JLogger().warn("寶石補正（離線）：{} 放不下拆下來的輔助 {}（請手動補給）",
                                    row.account() == null ? "角色 " + row.characterId() : "帳號 " + row.account(), plugin.skills().gems().describe(leftover));
                        }
                    }
                    final int retired = plugin.items().replaceRetired(stacks); // 退役的傳奇換成傳奇兌換券（2026-10 PoE2 改版）
                    changed |= retired > 0;
                    fixed += retired;
                    for (final ItemStack stack : stacks) {
                        changed |= refresh(stack);
                    }
                    out = changed ? ItemStack.serializeItemsAsBytes(stacks) : null;
                }
                if (out != null) {
                    changes.add(new Change(row, out));
                }
            } catch (final RuntimeException e) {
                broken++;
                plugin.getSLF4JLogger().warn("補正離線物品：{} {}{} 讀不出來，略過", row.kind(),
                        row.account() == null ? "角色 " + row.characterId() : "帳號 " + row.account(),
                        row.account() == null ? "" : " 第 " + (row.page() + 1) + " 頁", e);
            }
        }
        if (to < rows.size()) {
            plugin.getServer().getScheduler().runTaskLater(plugin, () -> process(rows, to), 1);
            return;
        }
        write();
    }

    private boolean refresh(final ItemStack stack) {
        if (stack == null || stack.isEmpty()) {
            return false;
        }
        items++;
        boolean changed = plugin.items().refresh(stack);
        if (plugin.currencies() != null) {
            changed |= plugin.currencies().refresh(stack);
        }
        if (plugin.skills() != null) {
            changed |= plugin.skills().gems().refresh(stack);
        }
        if (changed) {
            fixed++;
        }
        return changed;
    }

    private void write() {
        if (changes.isEmpty()) {
            sender.sendMessage(plugin.text().prefixed("<green>補正離線物品：看了 " + items + " 件，都是最新的" + (broken > 0 ? "<red>（" + broken + " 欄讀不出來，看主控台）" : "")));
            return;
        }
        final List<Change> pending = List.copyOf(changes);
        plugin.database().async(connection -> {
            int written = 0;
            int skipped = 0;
            final Timestamp now = Timestamp.from(Instant.now());
            // 2026-10-18：同一個角色的幾欄合成一個 UPDATE（以前每欄各寫一次、都比對同一個舊 updated_at，第一欄寫完其他欄全被判成「有人動過」）
            final Map<Long, List<Change>> byCharacter = new java.util.LinkedHashMap<>();
            for (final Change change : pending) {
                if (!change.row().kind().equals("stash")) {
                    byCharacter.computeIfAbsent(change.row().characterId(), id -> new ArrayList<>()).add(change);
                }
            }
            for (final List<Change> group : byCharacter.values()) {
                final StringBuilder sql = new StringBuilder("UPDATE character_inventory SET ");
                for (final Change change : group) {
                    sql.append(change.row().kind()).append(" = ?, ");
                }
                sql.append("updated_at = ? WHERE character_id = ? AND updated_at = ?");
                try (PreparedStatement statement = connection.prepareStatement(sql.toString())) {
                    int index = 1;
                    for (final Change change : group) {
                        statement.setBytes(index++, change.bytes());
                    }
                    statement.setTimestamp(index++, now);
                    statement.setLong(index++, group.getFirst().row().characterId());
                    statement.setTimestamp(index, group.getFirst().row().updatedAt());
                    if (statement.executeUpdate() == 1) {
                        written += group.size();
                    } else {
                        skipped += group.size(); // 讀完之後被存過：不寫
                    }
                }
            }
            for (final Change change : pending) {
                final Row row = change.row();
                if (!row.kind().equals("stash")) {
                    continue; // 角色的欄位上面一起寫了
                }
                final String sql = row.kind().equals("stash")
                        ? "UPDATE stash_pages SET contents = ?, updated_at = ? WHERE account_uuid = ? AND page = ? AND updated_at = ?"
                        : "UPDATE character_inventory SET " + row.kind() + " = ?, updated_at = ? WHERE character_id = ? AND updated_at = ?";
                try (PreparedStatement statement = connection.prepareStatement(sql)) {
                    statement.setBytes(1, change.bytes());
                    statement.setTimestamp(2, now);
                    if (row.kind().equals("stash")) {
                        statement.setString(3, row.account());
                        statement.setInt(4, row.page());
                        statement.setTimestamp(5, row.updatedAt());
                    } else {
                        statement.setLong(3, row.characterId());
                        statement.setTimestamp(4, row.updatedAt());
                    }
                    if (statement.executeUpdate() == 1) {
                        written++;
                    } else {
                        skipped++; // 讀完之後被存過：不寫
                    }
                }
            }
            return new int[] {written, skipped};
        }).whenComplete((result, error) -> plugin.getServer().getScheduler().runTask(plugin, () -> {
            if (error != null) {
                plugin.getSLF4JLogger().error("補正離線物品：寫回資料庫失敗", error);
                sender.sendMessage(plugin.text().prefixed("<red>寫回資料庫失敗（看主控台）"));
                return;
            }
            sender.sendMessage(plugin.text().prefixed(String.format(java.util.Locale.ROOT,
                    "<green>補正離線物品：看了 %,d 件，補正 %,d 件，寫回 %d 欄<gray>（%d 欄剛好有人動過，下次登入時會自己補）%s",
                    items, fixed, result[0], result[1], broken > 0 ? "<red>　" + broken + " 欄讀不出來（看主控台）" : "")));
            plugin.getSLF4JLogger().info("補正離線物品：{} 件中補正 {} 件，寫回 {} 欄、略過 {} 欄", items, fixed, result[0], result[1]);
        }));
    }

    /** 給指令用。 */
    public static void run(final ExileCore plugin, final CommandSender sender) {
        new ItemRefresher(plugin, sender).start();
    }
}
