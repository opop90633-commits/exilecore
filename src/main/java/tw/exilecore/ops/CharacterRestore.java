package tw.exilecore.ops;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;
import tw.exilecore.ExileCore;
import tw.exilecore.core.db.Migrations;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.SecureRandom;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.zip.GZIPInputStream;

/**
 * 2026-10-31 照站長：從資料庫備份還原單一隻角色（其他角色、帳號、倉庫、市集都不動）。
 * <pre>
 * ec backup character 檔名 角色名稱（或 #角色編號）   預覽：備份裡與現在的那隻角色，給一組確認碼（60 秒內有效）
 * ec backup character confirm 碼                      真的還原：那個帳號在線就先踢下線，存檔寫完後換成備份的樣子
 * </pre>
 * 還原的資料表（跟著角色走的）：characters、character_inventory（身上、珠寶、隨身背包）、passive_allocations（天賦）、
 * progress（任務、昇華、各種角色進度）、character_skills、character_skill_binds（技能面板與按鍵）、character_profiles（網頁角色頁）。
 * 不動的：帳號金幣與功勳、倉庫、市集、公會、帳號進度（都是整個帳號共用的）。
 * 還原中那個帳號不能登入（AsyncPlayerPreLoginEvent 擋掉，{@link #locked}）；在一個交易裡做完，失敗就整筆退回。
 */
public final class CharacterRestore {

    /** 跟著角色走的資料表與它們的角色編號欄位。 */
    static final Map<String, String> TABLES;

    static {
        final Map<String, String> tables = new LinkedHashMap<>();
        tables.put("characters", "id");
        tables.put("character_inventory", "character_id");
        tables.put("passive_allocations", "character_id");
        tables.put("progress", "character_id");
        tables.put("character_skills", "character_id");
        tables.put("character_skill_binds", "character_id");
        tables.put("character_profiles", "character_id");
        TABLES = Collections.unmodifiableMap(tables);
    }

    /** 確認碼多久有效。 */
    private static final long CODE_MILLIS = 60_000;
    private static final String CHARS = "ABCDEFGHJKLMNPQRSTUVWXYZ23456789";
    private static final DateTimeFormatter TIME = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss");

    /** 一隻角色在備份裡的資料：每個資料表的欄位與列。 */
    record Snapshot(long id, String account, int slot, String name, String classId, int level, boolean deleted,
                    Map<String, List<String>> columns, Map<String, List<JsonArray>> rows, long created) {
    }

    /** 等確認的還原。 */
    private record Pending(String code, String file, Snapshot snapshot, long expires) {
    }

    private final ExileCore plugin;
    private final SecureRandom random = new SecureRandom();
    private final Map<String, Pending> pending = new ConcurrentHashMap<>();
    /** 還原中的帳號（不能登入）。 */
    private final Set<UUID> locked = ConcurrentHashMap.newKeySet();

    public CharacterRestore(final ExileCore plugin) {
        this.plugin = plugin;
    }

    /** 這個帳號正在還原角色：不能登入。 */
    public boolean locked(final UUID account) {
        return account != null && locked.contains(account);
    }

    private Path folder() {
        return plugin.ops().backups().folder();
    }

    // ---- 預覽 ----

    public void preview(final CommandSender sender, final String fileName, final String who) {
        final Path file = folder().resolve(fileName).normalize();
        if (!file.startsWith(folder()) || !Files.isRegularFile(file) || !fileName.endsWith(".json.gz")) {
            sender.sendMessage(plugin.text().prefixed("<red>找不到資料庫備份 " + fileName + "（/ec backup list 看有哪些）"));
            return;
        }
        if (plugin.ops().backups().restoring()) {
            sender.sendMessage(plugin.text().prefixed("<red>正在還原整個資料庫或清檔：重新啟動伺服器後再試"));
            return;
        }
        sender.sendMessage(plugin.text().prefixed("<gray>讀取 " + fileName + " 中…"));
        plugin.getServer().getScheduler().runTaskAsynchronously(plugin, () -> {
            final Snapshot snapshot;
            final int version;
            try {
                version = BackupVerifier.version(file);
                snapshot = read(file, who);
            } catch (final IOException | RuntimeException e) {
                plugin.getServer().getScheduler().runTask(plugin, () -> sender.sendMessage(plugin.text().prefixed(
                        "<red>讀不了備份：" + tw.exilecore.item.ItemRenderer.escape(String.valueOf(e.getMessage())))));
                return;
            }
            if (version > Migrations.currentVersion()) {
                plugin.getServer().getScheduler().runTask(plugin, () -> sender.sendMessage(plugin.text().prefixed(
                        "<red>這是比較新的插件做的備份（資料表版本 " + version + "，現在只到 " + Migrations.currentVersion() + "）：要用新版的插件還原")));
                return;
            }
            // 現在資料庫裡的樣子
            plugin.database().async(connection -> current(connection, snapshot)).whenComplete((now, error) ->
                    plugin.getServer().getScheduler().runTask(plugin, () -> {
                        if (error != null) {
                            plugin.getSLF4JLogger().error("讀取角色現況失敗", error);
                            sender.sendMessage(plugin.text().prefixed("<red>讀取現在的資料失敗（看主控台）"));
                            return;
                        }
                        showPreview(sender, fileName, snapshot, version, now);
                    }));
        });
    }

    /** 現在資料庫裡的這隻角色與衝突：{name, class, level, deleted, problem}；沒有這隻角色 name 是 null。 */
    private record Current(String name, String classId, int level, boolean deleted, String accountName, String problem) {
    }

    private Current current(final Connection connection, final Snapshot snapshot) throws SQLException {
        String name = null;
        String classId = null;
        int level = 0;
        boolean deleted = false;
        String account = null;
        try (PreparedStatement statement = connection.prepareStatement(
                "SELECT name, class_id, char_level, deleted_at, account_uuid FROM characters WHERE id = ?")) {
            statement.setLong(1, snapshot.id());
            try (ResultSet result = statement.executeQuery()) {
                if (result.next()) {
                    name = result.getString(1);
                    classId = result.getString(2);
                    level = result.getInt(3);
                    deleted = result.getTimestamp(4) != null;
                    account = result.getString(5);
                }
            }
        }
        String problem = null;
        if (account != null && !account.equalsIgnoreCase(snapshot.account())) {
            problem = "現在這個角色編號屬於另一個帳號（備份之後被轉移過）";
        }
        if (problem == null && !snapshot.deleted()) {
            try (PreparedStatement statement = connection.prepareStatement(
                    "SELECT id, name FROM characters WHERE account_uuid = ? AND slot = ? AND deleted_at IS NULL AND id <> ?")) {
                statement.setString(1, snapshot.account());
                statement.setInt(2, snapshot.slot());
                statement.setLong(3, snapshot.id());
                try (ResultSet result = statement.executeQuery()) {
                    if (result.next()) {
                        problem = "那個帳號的第 " + (snapshot.slot() + 1) + " 格現在是另一隻角色「" + result.getString(2)
                                + "」（#" + result.getLong(1) + "）：先刪掉或換格";
                    }
                }
            }
        }
        if (problem == null && !snapshot.deleted()) {
            try (PreparedStatement statement = connection.prepareStatement(
                    "SELECT id FROM characters WHERE LOWER(name) = LOWER(?) AND deleted_at IS NULL AND id <> ?")) {
                statement.setString(1, snapshot.name());
                statement.setLong(2, snapshot.id());
                try (ResultSet result = statement.executeQuery()) {
                    if (result.next()) {
                        problem = "名稱「" + snapshot.name() + "」現在被另一隻角色（#" + result.getLong(1) + "）用掉了";
                    }
                }
            }
        }
        String accountName = null;
        try (PreparedStatement statement = connection.prepareStatement("SELECT name FROM accounts WHERE uuid = ?")) {
            statement.setString(1, snapshot.account());
            try (ResultSet result = statement.executeQuery()) {
                if (result.next()) {
                    accountName = result.getString(1);
                }
            }
        }
        if (problem == null && accountName == null) {
            problem = "那個帳號現在不在資料庫裡（被清掉了）";
        }
        return new Current(name, classId, level, deleted, accountName, problem);
    }

    private void showPreview(final CommandSender sender, final String fileName, final Snapshot snapshot, final int version, final Current now) {
        final List<String> lines = new ArrayList<>();
        lines.add("<#f5b942>還原角色預覽 <gray>— " + tw.exilecore.item.ItemRenderer.escape(fileName));
        if (snapshot.created() > 0) {
            lines.add("<gray>  備份時間 <white>" + LocalDateTime.ofInstant(Instant.ofEpochMilli(snapshot.created()), ZoneId.systemDefault()).format(TIME));
        }
        lines.add("<gray>  帳號 <white>" + tw.exilecore.item.ItemRenderer.escape(now.accountName() == null ? snapshot.account() : now.accountName())
                + "</white>，角色 #" + snapshot.id());
        lines.add("<gray>  備份裡：<white>" + tw.exilecore.item.ItemRenderer.escape(snapshot.name()) + "</white> " + className(snapshot.classId())
                + " Lv." + snapshot.level() + (snapshot.deleted() ? " <red>（當時已刪除）" : ""));
        lines.add("<gray>  現在　：" + (now.name() == null ? "<yellow>沒有這隻角色（會整隻加回來）"
                : "<white>" + tw.exilecore.item.ItemRenderer.escape(now.name()) + "</white> " + className(now.classId()) + " Lv." + now.level()
                + (now.deleted() ? " <red>（已刪除）" : "")));
        final StringBuilder counts = new StringBuilder("<gray>  會換掉：");
        for (final String table : TABLES.keySet()) {
            counts.append(label(table)).append(' ').append(snapshot.rows().getOrDefault(table, List.of()).size()).append(" 筆　");
        }
        lines.add(counts.toString().trim());
        lines.add("<dark_gray>  不動：帳號金幣與功勳、倉庫、市集、公會、帳號進度、其他角色");
        if (version > 0 && version < Migrations.currentVersion()) {
            lines.add("<yellow>  這是舊版的備份（資料表版本 " + version + "）：現在沒有的欄位略過、新欄位用預設值");
        }
        if (now.problem() != null) {
            lines.add("<red>  不能還原：" + tw.exilecore.item.ItemRenderer.escape(now.problem()));
            for (final String line : lines) {
                sender.sendMessage(plugin.text().parse(line));
            }
            return;
        }
        final String code = code();
        pending.values().removeIf(p -> p.expires() < System.currentTimeMillis());
        pending.put(code, new Pending(code, fileName, snapshot, System.currentTimeMillis() + CODE_MILLIS));
        final Player online = plugin.getServer().getPlayer(UUID.fromString(snapshot.account()));
        if (online != null) {
            lines.add("<yellow>  " + tw.exilecore.item.ItemRenderer.escape(online.getName()) + " 在線上：確認後會先被踢下線（還原完才能再登入）");
        }
        lines.add("<white>  確定要還原：60 秒內輸入 <#f5b942>/ec backup character confirm " + code);
        for (final String line : lines) {
            sender.sendMessage(plugin.text().parse(line));
        }
    }

    private static String className(final String id) {
        final tw.exilecore.character.CharacterClass type = id == null ? null : tw.exilecore.character.CharacterClass.parse(id, null);
        return type == null ? String.valueOf(id) : type.displayName();
    }

    private static String label(final String table) {
        return switch (table) {
            case "characters" -> "角色";
            case "character_inventory" -> "身上物品";
            case "passive_allocations" -> "天賦";
            case "progress" -> "進度";
            case "character_skills" -> "技能";
            case "character_skill_binds" -> "按鍵";
            case "character_profiles" -> "網頁角色頁";
            default -> table;
        };
    }

    private String code() {
        final StringBuilder code = new StringBuilder();
        for (int i = 0; i < 6; i++) {
            code.append(CHARS.charAt(random.nextInt(CHARS.length())));
        }
        return code.toString();
    }

    // ---- 讀備份 ----

    /** 從備份裡找出那隻角色（名稱不分大小寫，或 #編號／純數字）與它在每個資料表的列。 */
    static Snapshot read(final Path file, final String who) throws IOException {
        final String wanted = who.trim();
        final Long wantedId = wanted.startsWith("#") ? parseLong(wanted.substring(1)) : wanted.matches("\\d+") ? parseLong(wanted) : null;
        // 第一遍：找角色
        long created = 0;
        List<String> characterColumns = null;
        final List<JsonArray> matches = new ArrayList<>();
        try (BufferedReader reader = open(file)) {
            final JsonObject header = JsonParser.parseString(reader.readLine()).getAsJsonObject();
            if (!header.has("format") || !DatabaseDump.FORMAT.equals(header.get("format").getAsString())) {
                throw new IOException("不是 ExileCore 的備份檔");
            }
            created = header.has("created") ? header.get("created").getAsLong() : 0;
            String table = null;
            String line;
            while ((line = reader.readLine()) != null) {
                if (line.startsWith("{\"table\"")) {
                    if ("characters".equals(table)) {
                        break;
                    }
                    final JsonObject object = JsonParser.parseString(line).getAsJsonObject();
                    table = object.get("table").getAsString();
                    if ("characters".equals(table)) {
                        characterColumns = columns(object);
                    }
                } else if ("characters".equals(table) && line.startsWith("{\"row\"")) {
                    final JsonArray row = JsonParser.parseString(line).getAsJsonObject().getAsJsonArray("row");
                    final long id = row.get(characterColumns.indexOf("id")).getAsLong();
                    final String name = string(row, characterColumns, "name");
                    if (wantedId != null ? id == wantedId : name != null && name.equalsIgnoreCase(wanted)) {
                        matches.add(row);
                    }
                }
            }
        }
        if (characterColumns == null) {
            throw new IOException("備份裡沒有角色資料表");
        }
        if (matches.isEmpty()) {
            throw new IOException("備份裡沒有「" + wanted + "」這隻角色");
        }
        final List<String> cols = characterColumns;
        JsonArray chosen = null;
        if (matches.size() > 1) {
            final List<JsonArray> alive = matches.stream().filter(row -> isNull(row, cols, "deleted_at")).toList();
            if (alive.size() == 1) {
                chosen = alive.getFirst();
            } else {
                final StringBuilder ids = new StringBuilder();
                for (final JsonArray row : matches) {
                    ids.append(" #").append(row.get(cols.indexOf("id")).getAsLong());
                }
                throw new IOException("備份裡有好幾隻叫「" + wanted + "」：" + ids.toString().trim() + "（用 #編號 指定）");
            }
        } else {
            chosen = matches.getFirst();
        }
        final long id = chosen.get(cols.indexOf("id")).getAsLong();
        // 第二遍：每個資料表裡這隻角色的列
        final Map<String, List<String>> columns = new HashMap<>();
        final Map<String, List<JsonArray>> rows = new HashMap<>();
        try (BufferedReader reader = open(file)) {
            reader.readLine();
            String table = null;
            int key = -1;
            String line;
            while ((line = reader.readLine()) != null) {
                if (line.startsWith("{\"table\"")) {
                    final JsonObject object = JsonParser.parseString(line).getAsJsonObject();
                    table = object.get("table").getAsString();
                    if (TABLES.containsKey(table)) {
                        final List<String> list = columns(object);
                        columns.put(table, list);
                        rows.put(table, new ArrayList<>());
                        key = list.indexOf(TABLES.get(table));
                    } else {
                        key = -1;
                    }
                } else if (key >= 0 && line.startsWith("{\"row\"")) {
                    final JsonArray row = JsonParser.parseString(line).getAsJsonObject().getAsJsonArray("row");
                    final JsonElement value = row.get(key);
                    if (value != null && value.isJsonPrimitive() && value.getAsLong() == id) {
                        rows.get(table).add(row);
                    }
                }
            }
        }
        return new Snapshot(id, string(chosen, cols, "account_uuid"), (int) chosen.get(cols.indexOf("slot")).getAsLong(),
                string(chosen, cols, "name"), string(chosen, cols, "class_id"), (int) chosen.get(cols.indexOf("char_level")).getAsLong(),
                !isNull(chosen, cols, "deleted_at"), columns, rows, created);
    }

    private static BufferedReader open(final Path file) throws IOException {
        return new BufferedReader(new InputStreamReader(new GZIPInputStream(Files.newInputStream(file)), StandardCharsets.UTF_8));
    }

    private static List<String> columns(final JsonObject start) {
        final List<String> list = new ArrayList<>();
        for (final JsonElement column : start.getAsJsonArray("columns")) {
            list.add(column.getAsString().toLowerCase(Locale.ROOT));
        }
        return list;
    }

    private static String string(final JsonArray row, final List<String> columns, final String column) {
        final int index = columns.indexOf(column);
        return index < 0 || row.get(index).isJsonNull() ? null : row.get(index).getAsString();
    }

    private static boolean isNull(final JsonArray row, final List<String> columns, final String column) {
        final int index = columns.indexOf(column);
        return index < 0 || row.get(index).isJsonNull();
    }

    private static Long parseLong(final String text) {
        try {
            return Long.parseLong(text.trim());
        } catch (final NumberFormatException e) {
            return null;
        }
    }

    // ---- 確認、還原 ----

    public void confirm(final CommandSender sender, final String code) {
        final Pending plan = pending.remove(code.trim().toUpperCase(Locale.ROOT));
        if (plan == null || plan.expires() < System.currentTimeMillis()) {
            sender.sendMessage(plugin.text().prefixed("<red>確認碼不對或過期了：重新預覽一次（/ec backup character 檔名 角色）"));
            return;
        }
        if (plugin.ops().backups().restoring()) {
            sender.sendMessage(plugin.text().prefixed("<red>正在還原整個資料庫或清檔：重新啟動伺服器後再試"));
            return;
        }
        final Snapshot snapshot = plan.snapshot();
        final UUID account = UUID.fromString(snapshot.account());
        if (!locked.add(account)) {
            sender.sendMessage(plugin.text().prefixed("<red>這個帳號正在還原另一隻角色，等一下再試"));
            return;
        }
        final Player online = plugin.getServer().getPlayer(account);
        if (online != null) {
            online.kick(plugin.text().parse("<yellow>管理員正在還原你的角色備份，請稍後再登入"));
            sender.sendMessage(plugin.text().prefixed("<gray>" + tw.exilecore.item.ItemRenderer.escape(online.getName()) + " 已經踢下線，等存檔寫完…"));
        }
        // 等一下，讓登出存檔先排進資料庫執行緒（還原排在它後面，最後的結果是備份的樣子）
        plugin.getServer().getScheduler().runTaskLater(plugin, () -> plugin.database().async(connection -> {
            final Current now = current(connection, snapshot);
            if (now.problem() != null) {
                throw new SQLException(now.problem());
            }
            final boolean autoCommit = connection.getAutoCommit();
            connection.setAutoCommit(false);
            try {
                final int rows = write(connection, snapshot);
                connection.commit();
                return rows;
            } catch (final SQLException | RuntimeException e) {
                try {
                    connection.rollback();
                } catch (final SQLException ignored) {
                    // 照樣回報原本的錯
                }
                throw e;
            } finally {
                connection.setAutoCommit(autoCommit);
            }
        }).whenComplete((rows, error) -> plugin.getServer().getScheduler().runTask(plugin, () -> {
            locked.remove(account);
            if (error != null) {
                plugin.getSLF4JLogger().error("還原角色 #{} 失敗（資料維持原樣）", snapshot.id(), error);
                final Throwable cause = error.getCause() != null ? error.getCause() : error;
                sender.sendMessage(plugin.text().prefixed("<red>還原失敗，資料維持原樣：" + tw.exilecore.item.ItemRenderer.escape(String.valueOf(cause.getMessage()))));
                return;
            }
            plugin.getSLF4JLogger().info("{} 從 {} 還原了角色 {}（#{}，{} 筆）", sender.getName(), plan.file(), snapshot.name(), snapshot.id(), rows);
            sender.sendMessage(plugin.text().prefixed(String.format(Locale.ROOT, "<green>已經把「%s」（#%d）還原成 %s 的樣子（%d 筆）。可以登入了",
                    tw.exilecore.item.ItemRenderer.escape(snapshot.name()), snapshot.id(), tw.exilecore.item.ItemRenderer.escape(plan.file()), rows)));
        })), 40);
    }

    /** 在交易裡：每個資料表先刪掉這隻角色現在的列，再寫回備份的列。回傳寫了幾列。 */
    static int write(final Connection connection, final Snapshot snapshot) throws SQLException {
        int written = 0;
        for (final Map.Entry<String, String> entry : TABLES.entrySet()) {
            final String table = entry.getKey();
            if (!DatabaseDump.exists(connection, table)) {
                continue;
            }
            try (PreparedStatement delete = connection.prepareStatement("DELETE FROM " + table + " WHERE " + entry.getValue() + " = ?")) {
                delete.setLong(1, snapshot.id());
                delete.executeUpdate();
            }
            final List<String> columns = snapshot.columns().get(table);
            final List<JsonArray> rows = snapshot.rows().getOrDefault(table, List.of());
            if (columns == null || rows.isEmpty()) {
                continue;
            }
            final Map<String, Integer> types = DatabaseDump.columnTypes(connection, table);
            final List<String> kept = new ArrayList<>();
            final int[] mapping = new int[columns.size()];
            for (int i = 0; i < columns.size(); i++) {
                if (types.containsKey(columns.get(i)) && columns.get(i).matches("[a-z_0-9]+")) {
                    kept.add(columns.get(i));
                    mapping[i] = kept.size();
                }
            }
            if (kept.isEmpty()) {
                continue;
            }
            try (PreparedStatement insert = connection.prepareStatement("INSERT INTO " + table + " (" + String.join(", ", kept) + ") VALUES ("
                    + String.join(", ", Collections.nCopies(kept.size(), "?")) + ")")) {
                for (final JsonArray row : rows) {
                    for (int i = 0; i < Math.min(row.size(), mapping.length); i++) {
                        if (mapping[i] > 0) {
                            DatabaseDump.bind(insert, mapping[i], row.get(i), types.get(columns.get(i)));
                        }
                    }
                    insert.executeUpdate();
                    written++;
                }
            }
        }
        return written;
    }
}
