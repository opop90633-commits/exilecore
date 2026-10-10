package tw.exilecore.ops;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import org.slf4j.helpers.NOPLogger;
import tw.exilecore.core.db.Database;
import tw.exilecore.core.db.Migrations;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.Enumeration;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.zip.GZIPInputStream;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;

/**
 * 備份檢查（/ec backup verify，2026-10）：不動現在的資料，確認備份檔真的還原得回來。純邏輯，不依賴 Bukkit。
 * <ul>
 *   <li>資料庫備份（db-時間.json.gz）：開一個<b>記憶體裡的空資料庫</b>、建好現在版本的資料表，用跟真正還原同一段程式
 *       （{@link DatabaseDump#restore}）把備份寫進去，再一個資料表一個資料表數列數，跟備份檔裡記的對。</li>
 *   <li>世界備份（world-時間.zip）：每個檔案都完整讀一次（壓縮檔的檢查碼不對會讀不出來），確認 level.dat 與區域檔都在。</li>
 * </ul>
 */
public final class BackupVerifier {

    /** 記憶體資料庫的編號（同時檢查兩個也不會撞名）。 */
    private static final AtomicInteger SEQUENCE = new AtomicInteger();

    private BackupVerifier() {
    }

    /** 一個資料表：備份檔裡有幾列、還原後數到幾列。 */
    public record TableCheck(String table, int expected, int restored) {
        public boolean ok() {
            return expected == restored;
        }
    }

    /**
     * 資料庫備份的檢查結果。version 是備份當時的資料表版本（schema_version 最大的那個），
     * 比現在舊的話 upgraded 是升到現在版本之後的列數（遷移有沒有成功）。
     */
    public record DatabaseResult(long created, int version, List<TableCheck> tables, int rows, int skippedColumns, List<String> problems,
                                 Map<String, Integer> upgraded) {
        public boolean ok() {
            return problems.isEmpty();
        }

        /** 某個資料表還原後的列數（沒有是 -1）。 */
        public int count(final String table) {
            if (upgraded.containsKey(table)) {
                return upgraded.get(table);
            }
            for (final TableCheck check : tables) {
                if (check.table().equals(table)) {
                    return check.restored();
                }
            }
            return -1;
        }
    }

    /** 世界備份的檢查結果。 */
    public record WorldResult(int files, long bytes, int regions, boolean levelDat, List<String> problems) {
        public boolean ok() {
            return problems.isEmpty();
        }
    }

    /** 檢查資料庫備份。 */
    public static DatabaseResult verifyDatabase(final Path file) throws IOException, SQLException {
        // 1. 讀一遍：格式、建立時間、每個資料表有幾列
        final Map<String, Integer> expected = new LinkedHashMap<>();
        long created = 0;
        int version;
        try (BufferedReader reader = new BufferedReader(new InputStreamReader(new GZIPInputStream(Files.newInputStream(file)),
                StandardCharsets.UTF_8))) {
            final String first = reader.readLine();
            if (first == null) {
                throw new IOException("備份檔是空的");
            }
            final JsonObject header = JsonParser.parseString(first).getAsJsonObject();
            if (!header.has("format") || !DatabaseDump.FORMAT.equals(header.get("format").getAsString())) {
                throw new IOException("不是 ExileCore 的備份檔");
            }
            created = header.has("created") ? header.get("created").getAsLong() : 0;
            version = 0;
            String table = null;
            String line;
            while ((line = reader.readLine()) != null) {
                if (line.isBlank()) {
                    continue;
                }
                // 只看開頭，不用整行解析（大的備份比較快）
                if (line.startsWith("{\"table\"")) {
                    table = JsonParser.parseString(line).getAsJsonObject().get("table").getAsString();
                    expected.put(table, 0);
                } else if (line.startsWith("{\"row\"") && table != null) {
                    expected.merge(table, 1, Integer::sum);
                    if (table.equals("schema_version")) {
                        // schema_version 的第一欄是版本
                        final var row = JsonParser.parseString(line).getAsJsonObject().getAsJsonArray("row");
                        if (!row.isEmpty() && row.get(0).isJsonPrimitive()) {
                            version = Math.max(version, row.get(0).getAsInt());
                        }
                    }
                }
            }
        }
        // 2. 真的還原一次：記憶體裡的空資料庫，資料表建到備份當時的版本（跟真的還原一樣，見 {@link #upgrade}）
        final List<String> problems = new ArrayList<>();
        if (version > Migrations.currentVersion()) {
            problems.add("備份是比較新的版本（資料表版本 " + version + "，現在的插件只到 " + Migrations.currentVersion() + "）：要用新版的插件還原");
            return new DatabaseResult(created, version, List.of(), 0, 0, List.copyOf(problems), Map.of());
        }
        final Database scratch = new Database(NOPLogger.NOP_LOGGER, file.getParent());
        final List<TableCheck> tables = new ArrayList<>();
        final Map<String, Integer> upgraded = new LinkedHashMap<>();
        final DatabaseDump.Restored restored;
        try {
            restored = restoreInto(scratch, file, version);
            final int backupVersion = version;
            // 3. 數列數
            scratch.sync(connection -> {
                for (final Map.Entry<String, Integer> entry : expected.entrySet()) {
                    final String table = entry.getKey();
                    if (!table.matches("[a-z_]+") || !DatabaseDump.exists(connection, table)) {
                        problems.add("資料表 " + table + " 現在的版本沒有了（還原時會略過它的 " + entry.getValue() + " 列）");
                        continue;
                    }
                    tables.add(new TableCheck(table, entry.getValue(), count(connection, table)));
                }
                return null;
            });
            // 4. 舊版的備份：升到現在的版本（遷移的程式轉換也跑一次），再數一次
            if (backupVersion > 0 && backupVersion < Migrations.currentVersion()) {
                try {
                    Migrations.apply(scratch, NOPLogger.NOP_LOGGER);
                    scratch.sync(connection -> {
                        for (final String table : Migrations.TABLES) {
                            if (DatabaseDump.exists(connection, table)) {
                                upgraded.put(table, count(connection, table));
                            }
                        }
                        return null;
                    });
                } catch (final SQLException | RuntimeException e) {
                    problems.add("舊版（資料表版本 " + backupVersion + "）升到現在的版本 " + Migrations.currentVersion() + " 失敗：" + e.getMessage());
                }
            }
        } finally {
            dropAndClose(scratch);
        }
        int rows = 0;
        for (final TableCheck check : tables) {
            rows += check.restored();
            if (!check.ok()) {
                problems.add("資料表 " + check.table() + "：備份裡有 " + check.expected() + " 列，還原後只有 " + check.restored() + " 列");
            }
        }
        if (!expected.containsKey("characters")) {
            problems.add("備份裡沒有角色資料表（characters）");
        }
        return new DatabaseResult(created, version, List.copyOf(tables), rows, restored.skippedColumns(), List.copyOf(problems), Map.copyOf(upgraded));
    }

    /** 備份當時的資料表版本（schema_version 最大的那個；沒有回傳 0）。 */
    public static int version(final Path file) throws IOException {
        int version = 0;
        try (BufferedReader reader = new BufferedReader(new InputStreamReader(new GZIPInputStream(Files.newInputStream(file)),
                StandardCharsets.UTF_8))) {
            String table = null;
            String line;
            while ((line = reader.readLine()) != null) {
                if (line.startsWith("{\"table\"")) {
                    if ("schema_version".equals(table)) {
                        break; // schema_version 排第一個，讀完就好
                    }
                    table = JsonParser.parseString(line).getAsJsonObject().get("table").getAsString();
                } else if (line.startsWith("{\"row\"") && "schema_version".equals(table)) {
                    final var row = JsonParser.parseString(line).getAsJsonObject().getAsJsonArray("row");
                    if (!row.isEmpty() && row.get(0).isJsonPrimitive()) {
                        version = Math.max(version, row.get(0).getAsInt());
                    }
                }
            }
        }
        return version;
    }

    /**
     * 記憶體資料庫：資料表建到 version 這一版（0 = 現在的版本），把備份寫進去。
     * 備份當時的資料表跟現在不一樣（舊版）時，先建出當時的樣子再寫，之後用遷移升上來，舊資料的轉換（例如金幣搬到帳號）才會照順序跑。
     */
    private static DatabaseDump.Restored restoreInto(final Database scratch, final Path file, final int version) throws SQLException {
        scratch.connect("jdbc:h2:mem:exilecore_verify_" + SEQUENCE.incrementAndGet() + ";MODE=MariaDB;DATABASE_TO_LOWER=TRUE;DB_CLOSE_DELAY=-1",
                "org.h2.Driver", version <= 0 ? Integer.MAX_VALUE : version);
        return scratch.sync(connection -> {
            final boolean autoCommit = connection.getAutoCommit();
            connection.setAutoCommit(false);
            try (InputStream in = new GZIPInputStream(Files.newInputStream(file))) {
                final DatabaseDump.Restored result = DatabaseDump.restore(connection, in);
                connection.commit();
                return result;
            } catch (final IOException e) {
                connection.rollback();
                throw new SQLException("讀備份檔失敗：" + e.getMessage(), e);
            } finally {
                connection.setAutoCommit(autoCommit);
            }
        });
    }

    /**
     * 舊版的備份升到現在的版本（真的還原前用）：在記憶體裡建出備份當時的資料表、寫進去、跑遷移升上來，
     * 再寫成一份現在版本的備份 out。這樣還原時資料表的版本跟資料對得上，重開伺服器不會把遷移再跑一次。
     */
    public static void upgrade(final Path file, final int version, final Path out) throws IOException, SQLException {
        final Database scratch = new Database(NOPLogger.NOP_LOGGER, file.getParent());
        try {
            restoreInto(scratch, file, version);
            Migrations.apply(scratch, NOPLogger.NOP_LOGGER);
            scratch.sync(connection -> {
                try (java.io.OutputStream stream = new java.util.zip.GZIPOutputStream(Files.newOutputStream(out))) {
                    DatabaseDump.dump(connection, Migrations.TABLES, stream);
                } catch (final IOException e) {
                    throw new SQLException("寫入升級後的備份失敗：" + e.getMessage(), e);
                }
                return null;
            });
        } finally {
            dropAndClose(scratch);
        }
    }

    private static int count(final Connection connection, final String table) throws SQLException {
        try (Statement statement = connection.createStatement(); ResultSet result = statement.executeQuery("SELECT COUNT(*) FROM " + table)) {
            return result.next() ? result.getInt(1) : 0;
        }
    }

    /** 記憶體資料庫用完就丟（DB_CLOSE_DELAY=-1 要自己 SHUTDOWN 才會釋放記憶體）。 */
    private static void dropAndClose(final Database scratch) {
        try {
            if (scratch.isConnected()) {
                scratch.sync(connection -> {
                    try (Statement statement = connection.createStatement()) {
                        statement.execute("SHUTDOWN");
                    }
                    return null;
                });
            }
        } catch (final SQLException | RuntimeException ignored) {
            // 已經關了
        }
        scratch.close();
    }

    /** 檢查世界備份：每個檔案讀一遍（檢查碼不對會丟例外），數區域檔、看 level.dat 在不在。 */
    public static WorldResult verifyWorld(final Path zip) throws IOException {
        final List<String> problems = new ArrayList<>();
        int files = 0;
        int regions = 0;
        long bytes = 0;
        boolean levelDat = false;
        final byte[] buffer = new byte[64 * 1024];
        try (ZipFile archive = new ZipFile(zip.toFile(), StandardCharsets.UTF_8)) {
            final Enumeration<? extends ZipEntry> entries = archive.entries();
            while (entries.hasMoreElements()) {
                final ZipEntry entry = entries.nextElement();
                if (entry.isDirectory()) {
                    continue;
                }
                files++;
                final String name = entry.getName();
                // 26.x 的額外世界沒有 level.dat，基本資料在 data/ 底下
                if (name.endsWith("/level.dat") || name.equals("level.dat") || name.contains("/data/")) {
                    levelDat = true;
                }
                if (name.contains("/region/") && name.endsWith(".mca")) {
                    regions++;
                }
                try (InputStream in = archive.getInputStream(entry)) {
                    int read;
                    while ((read = in.read(buffer)) > 0) {
                        bytes += read;
                    }
                } catch (final IOException e) {
                    problems.add(name + " 讀不出來（" + e.getMessage() + "）");
                    if (problems.size() >= 5) {
                        break;
                    }
                }
            }
        }
        if (files == 0) {
            problems.add("壓縮檔裡沒有檔案");
        } else if (!levelDat) {
            problems.add("沒有世界的基本資料（data/ 或 level.dat）");
        }
        if (files > 0 && regions == 0) {
            problems.add("沒有區域檔（region/*.mca，地形）");
        }
        return new WorldResult(files, bytes, regions, levelDat, List.copyOf(problems));
    }
}
