package tw.exilecore.ops;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonNull;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.google.gson.JsonPrimitive;

import java.io.BufferedReader;
import java.io.BufferedWriter;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.io.OutputStreamWriter;
import java.nio.charset.StandardCharsets;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.ResultSetMetaData;
import java.sql.SQLException;
import java.sql.Statement;
import java.sql.Timestamp;
import java.sql.Types;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Base64;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * 資料庫備份的格式：一行一個 JSON（呼叫端決定要不要 gzip）。H2 與 MariaDB 都能讀寫，還原用 PreparedStatement，
 * 不用解析 SQL，所以中文、換行、二進位資料（背包）都不會出錯。純 JDBC，不依賴 Bukkit。
 * <pre>
 * {"format":"exilecore-backup","version":1,"created":1790000000000}
 * {"table":"accounts","columns":["uuid","name",...]}
 * {"row":["...","...",null,{"ts":1790000000000},{"b64":"..."}]}
 * </pre>
 */
public final class DatabaseDump {

    public static final String FORMAT = "exilecore-backup";

    /** 還原的結果。 */
    public record Restored(int tables, int rows, int skippedColumns) {
    }

    private DatabaseDump() {
    }

    /** 把這些資料表（不存在的略過）寫出去；回傳寫了幾列。 */
    public static int dump(final Connection connection, final List<String> tables, final OutputStream out) throws SQLException, IOException {
        final BufferedWriter writer = new BufferedWriter(new OutputStreamWriter(out, StandardCharsets.UTF_8));
        final JsonObject header = new JsonObject();
        header.addProperty("format", FORMAT);
        header.addProperty("version", 1);
        header.addProperty("created", System.currentTimeMillis());
        line(writer, header);
        int rows = 0;
        for (final String table : tables) {
            if (!exists(connection, table)) {
                continue;
            }
            try (Statement statement = connection.createStatement();
                 ResultSet result = statement.executeQuery("SELECT * FROM " + table)) {
                final ResultSetMetaData meta = result.getMetaData();
                final int count = meta.getColumnCount();
                final JsonObject start = new JsonObject();
                start.addProperty("table", table);
                final JsonArray columns = new JsonArray();
                for (int i = 1; i <= count; i++) {
                    columns.add(meta.getColumnLabel(i).toLowerCase(Locale.ROOT));
                }
                start.add("columns", columns);
                line(writer, start);
                while (result.next()) {
                    final JsonArray values = new JsonArray();
                    for (int i = 1; i <= count; i++) {
                        values.add(read(result, i, meta.getColumnType(i)));
                    }
                    final JsonObject row = new JsonObject();
                    row.add("row", values);
                    line(writer, row);
                    rows++;
                }
            }
        }
        writer.flush();
        return rows;
    }

    private static JsonElement read(final ResultSet result, final int index, final int type) throws SQLException {
        switch (type) {
            case Types.BLOB, Types.BINARY, Types.VARBINARY, Types.LONGVARBINARY -> {
                final byte[] bytes = result.getBytes(index);
                if (bytes == null) {
                    return JsonNull.INSTANCE;
                }
                final JsonObject value = new JsonObject();
                value.addProperty("b64", Base64.getEncoder().encodeToString(bytes));
                return value;
            }
            case Types.TIMESTAMP, Types.TIMESTAMP_WITH_TIMEZONE, Types.DATE, Types.TIME -> {
                final Timestamp timestamp = result.getTimestamp(index);
                if (timestamp == null) {
                    return JsonNull.INSTANCE;
                }
                final JsonObject value = new JsonObject();
                value.addProperty("ts", timestamp.getTime());
                return value;
            }
            case Types.BIGINT, Types.INTEGER, Types.SMALLINT, Types.TINYINT -> {
                final long value = result.getLong(index);
                return result.wasNull() ? JsonNull.INSTANCE : new JsonPrimitive(value);
            }
            case Types.DOUBLE, Types.FLOAT, Types.REAL, Types.DECIMAL, Types.NUMERIC -> {
                final double value = result.getDouble(index);
                return result.wasNull() ? JsonNull.INSTANCE : new JsonPrimitive(value);
            }
            case Types.BOOLEAN, Types.BIT -> {
                final boolean value = result.getBoolean(index);
                return result.wasNull() ? JsonNull.INSTANCE : new JsonPrimitive(value);
            }
            default -> {
                final String value = result.getString(index);
                return value == null ? JsonNull.INSTANCE : new JsonPrimitive(value);
            }
        }
    }

    /**
     * 還原：先清空備份裡有的資料表，再一列一列寫回去（呼叫端負責交易：要嘛全部成功，要嘛全部還原）。
     * 備份裡有、現在的資料表沒有的欄位略過（計數）；現在多出來的欄位用預設值。
     */
    public static Restored restore(final Connection connection, final InputStream in) throws SQLException, IOException {
        return restore(connection, in, List.of());
    }

    /**
     * 還原，另外把 alsoClear 裡、備份檔沒有的資料表清空（備份之後才新增的資料表：不然會留下現在的資料，跟還原的資料對不上）。
     */
    public static Restored restore(final Connection connection, final InputStream in, final java.util.Collection<String> alsoClear)
            throws SQLException, IOException {
        final java.util.Set<String> seen = new java.util.HashSet<>();
        final BufferedReader reader = new BufferedReader(new InputStreamReader(in, StandardCharsets.UTF_8));
        final String first = reader.readLine();
        if (first == null) {
            throw new IOException("備份檔是空的");
        }
        final JsonObject header = JsonParser.parseString(first).getAsJsonObject();
        if (!header.has("format") || !FORMAT.equals(header.get("format").getAsString())) {
            throw new IOException("不是 ExileCore 的備份檔");
        }
        int tables = 0;
        int rows = 0;
        int skipped = 0;
        String table = null;
        List<String> columns = List.of();
        Map<String, Integer> targetTypes = Map.of();
        PreparedStatement insert = null;
        int[] mapping = new int[0];
        try {
            String line;
            while ((line = reader.readLine()) != null) {
                if (line.isBlank()) {
                    continue;
                }
                final JsonObject object = JsonParser.parseString(line).getAsJsonObject();
                if (object.has("table")) {
                    if (insert != null) {
                        insert.close();
                        insert = null;
                    }
                    table = object.get("table").getAsString();
                    seen.add(table);
                    if (!table.matches("[a-z_]+") || !exists(connection, table)) {
                        table = null; // 現在沒有這個資料表：略過它的列
                        continue;
                    }
                    tables++;
                    columns = new ArrayList<>();
                    for (final JsonElement column : object.getAsJsonArray("columns")) {
                        columns.add(column.getAsString().toLowerCase(Locale.ROOT));
                    }
                    targetTypes = columnTypes(connection, table);
                    final List<String> kept = new ArrayList<>();
                    mapping = new int[columns.size()];
                    for (int i = 0; i < columns.size(); i++) {
                        if (targetTypes.containsKey(columns.get(i)) && columns.get(i).matches("[a-z_0-9]+")) {
                            kept.add(columns.get(i));
                            mapping[i] = kept.size();
                        } else {
                            mapping[i] = 0;
                            skipped++;
                        }
                    }
                    try (Statement statement = connection.createStatement()) {
                        statement.executeUpdate("DELETE FROM " + table);
                    }
                    if (!kept.isEmpty()) {
                        insert = connection.prepareStatement("INSERT INTO " + table + " (" + String.join(", ", kept) + ") VALUES ("
                                + String.join(", ", java.util.Collections.nCopies(kept.size(), "?")) + ")");
                    }
                    continue;
                }
                if (!object.has("row") || table == null || insert == null) {
                    continue;
                }
                final JsonArray values = object.getAsJsonArray("row");
                for (int i = 0; i < Math.min(values.size(), mapping.length); i++) {
                    if (mapping[i] == 0) {
                        continue;
                    }
                    bind(insert, mapping[i], values.get(i), targetTypes.get(columns.get(i)));
                }
                insert.executeUpdate();
                rows++;
            }
        } finally {
            if (insert != null) {
                insert.close();
            }
        }
        for (final String extra : alsoClear) {
            // schema_version 不清：清了下次啟動會把資料遷移（例如金幣搬到帳號）再跑一次
            if (!seen.contains(extra) && !extra.equals("schema_version") && extra.matches("[a-z_]+") && exists(connection, extra)) {
                try (Statement statement = connection.createStatement()) {
                    statement.executeUpdate("DELETE FROM " + extra);
                }
            }
        }
        return new Restored(tables, rows, skipped);
    }

    private static void bind(final PreparedStatement statement, final int index, final JsonElement value, final int type) throws SQLException {
        if (value == null || value.isJsonNull()) {
            statement.setNull(index, type);
            return;
        }
        if (value.isJsonObject()) {
            final JsonObject object = value.getAsJsonObject();
            if (object.has("b64")) {
                statement.setBytes(index, Base64.getDecoder().decode(object.get("b64").getAsString()));
                return;
            }
            if (object.has("ts")) {
                statement.setTimestamp(index, Timestamp.from(Instant.ofEpochMilli(object.get("ts").getAsLong())));
                return;
            }
            statement.setNull(index, type);
            return;
        }
        final JsonPrimitive primitive = value.getAsJsonPrimitive();
        switch (type) {
            case Types.BIGINT, Types.INTEGER, Types.SMALLINT, Types.TINYINT -> statement.setLong(index, primitive.getAsLong());
            case Types.DOUBLE, Types.FLOAT, Types.REAL, Types.DECIMAL, Types.NUMERIC -> statement.setDouble(index, primitive.getAsDouble());
            case Types.BOOLEAN, Types.BIT -> statement.setBoolean(index, primitive.getAsBoolean());
            default -> statement.setString(index, primitive.getAsString());
        }
    }

    /** 資料表在不在。 */
    static boolean exists(final Connection connection, final String table) {
        try (Statement statement = connection.createStatement()) {
            statement.executeQuery("SELECT * FROM " + table + " WHERE 1 = 0").close();
            return true;
        } catch (final SQLException e) {
            return false;
        }
    }

    private static Map<String, Integer> columnTypes(final Connection connection, final String table) throws SQLException {
        final Map<String, Integer> types = new HashMap<>();
        try (Statement statement = connection.createStatement();
             ResultSet result = statement.executeQuery("SELECT * FROM " + table + " WHERE 1 = 0")) {
            final ResultSetMetaData meta = result.getMetaData();
            for (int i = 1; i <= meta.getColumnCount(); i++) {
                types.put(meta.getColumnLabel(i).toLowerCase(Locale.ROOT), meta.getColumnType(i));
            }
        }
        return types;
    }

    private static void line(final BufferedWriter writer, final JsonObject object) throws IOException {
        writer.write(object.toString());
        writer.newLine();
    }
}
