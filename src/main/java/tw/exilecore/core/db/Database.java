package tw.exilecore.core.db;

import com.zaxxer.hikari.HikariConfig;
import com.zaxxer.hikari.HikariDataSource;
import org.slf4j.Logger;
import tw.exilecore.core.config.Settings;

import java.nio.file.Path;
import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

/**
 * 資料庫連線池。開發期預設 H2（單機檔案），正式環境切 MariaDB，
 * 兩者都透過 HikariCP，其他模組只看得到 {@link #connection()} 與 {@link #async}。
 * <p>
 * 所有非同步存取都排在同一條背景執行緒上，同一個玩家的「先存再讀」順序不會亂；
 * 主執行緒只做序列化（背包轉 byte[]）與套用結果，不等待磁碟。
 * 資料表由 {@link Migrations} 在連線後建立與升級。
 */
public final class Database implements AutoCloseable {

    /** 一段會用到連線的工作。 */
    @FunctionalInterface
    public interface SqlWork<T> {
        T run(Connection connection) throws SQLException;
    }

    private final Logger log;
    private final Path dataFolder;

    private HikariDataSource dataSource;
    private ExecutorService executor;
    private String description = "未連線";
    /** 排隊中（還沒做完）的背景工作數（/ec perf 看）。 */
    private final java.util.concurrent.atomic.AtomicInteger queued = new java.util.concurrent.atomic.AtomicInteger();
    /** 背景工作平均花多久（毫秒，指數移動平均）。 */
    private volatile double averageMillis;
    private volatile double slowestMillis;

    public Database(final Logger log, final Path dataFolder) {
        this.log = log;
        this.dataFolder = dataFolder;
    }

    /** 依設定建立連線池、跑一次 {@code SELECT 1} 確認連得上，再套用資料表遷移。 */
    public void connect(final Settings.Storage storage) throws SQLException {
        close();

        final HikariConfig config = new HikariConfig();
        config.setPoolName("ExileCore");
        config.setConnectionTimeout(10_000);

        switch (storage.type()) {
            case H2 -> {
                // H2 的路徑用正斜線，Windows 也不會出錯
                final String file = dataFolder.resolve(storage.h2File()).toAbsolutePath()
                        .toString().replace('\\', '/');
                config.setDriverClassName("org.h2.Driver");
                config.setJdbcUrl("jdbc:h2:file:" + file + ";MODE=MariaDB;DATABASE_TO_LOWER=TRUE");
                config.setMaximumPoolSize(4);
                this.description = "H2 " + storage.h2File() + ".mv.db";
            }
            case MARIADB -> {
                config.setDriverClassName("org.mariadb.jdbc.Driver");
                config.setJdbcUrl("jdbc:mariadb://" + storage.host() + ":" + storage.port()
                        + "/" + storage.database() + "?useUnicode=true&characterEncoding=utf8mb4");
                config.setUsername(storage.user());
                config.setPassword(storage.password());
                config.setMaximumPoolSize(storage.poolSize());
                this.description = "MariaDB " + storage.host() + ":" + storage.port() + "/" + storage.database();
            }
        }
        connect(config);
    }

    /** 用任意 JDBC 位址連線（單元測試用記憶體 H2）。 */
    public void connect(final String jdbcUrl) throws SQLException {
        connect(jdbcUrl, null);
    }

    /**
     * 同上，指定驅動程式的類別（插件的函式庫在自己的類別載入器裡，JDBC 自己找不到驅動時要指定；
     * 備份檢查用記憶體 H2：{@code org.h2.Driver}）。
     */
    public void connect(final String jdbcUrl, final String driverClass) throws SQLException {
        connect(jdbcUrl, driverClass, Integer.MAX_VALUE);
    }

    /**
     * 同上，資料表只遷移到 upTo 這個版本（0 = 一個都不跑，連 schema_version 都不建）。
     * 還原舊版的備份用：先建出備份當時的資料表、寫進去，再用 {@link Migrations#apply(Database, Logger, int)} 升到現在的版本。
     */
    public void connect(final String jdbcUrl, final String driverClass, final int upTo) throws SQLException {
        close();
        final HikariConfig config = new HikariConfig();
        if (driverClass != null) {
            config.setDriverClassName(driverClass);
        }
        config.setPoolName("ExileCore");
        config.setJdbcUrl(jdbcUrl);
        config.setMaximumPoolSize(2);
        this.description = jdbcUrl;
        connect(config, upTo);
    }

    private void connect(final HikariConfig config) throws SQLException {
        connect(config, Integer.MAX_VALUE);
    }

    private void connect(final HikariConfig config, final int upTo) throws SQLException {
        final HikariDataSource source = new HikariDataSource(config);
        try (Connection connection = source.getConnection();
             Statement statement = connection.createStatement();
             ResultSet result = statement.executeQuery("SELECT 1")) {
            if (!result.next()) {
                throw new SQLException("SELECT 1 沒有回傳資料");
            }
        } catch (final SQLException e) {
            source.close();
            throw e;
        }

        this.dataSource = source;
        this.executor = Executors.newSingleThreadExecutor(runnable -> {
            final Thread thread = new Thread(runnable, "ExileCore-Database");
            thread.setDaemon(true);
            return thread;
        });
        log.info("資料庫已連線：{}", description);

        final int applied;
        try {
            applied = upTo <= 0 ? 0 : Migrations.apply(this, log, upTo);
        } catch (final SQLException | RuntimeException e) {
            // 遷移失敗（例如經驗表改版的等級換算）：不要在半套的資料上繼續跑——關掉連線，核心模組啟用失敗，插件會停用
            close();
            throw e;
        }
        if (applied > 0) {
            log.info("資料表已更新到版本 {}", Migrations.currentVersion());
        }
    }

    /** 從連線池借一條連線；用完務必關閉（try-with-resources）。 */
    public Connection connection() throws SQLException {
        final HikariDataSource source = this.dataSource;
        if (source == null) {
            throw new SQLException("資料庫尚未連線");
        }
        return source.getConnection();
    }

    /** 在目前執行緒上跑一段工作（啟動、關閉、單元測試用）。 */
    public <T> T sync(final SqlWork<T> work) throws SQLException {
        try (Connection connection = connection()) {
            return work.run(connection);
        }
    }

    /** 在背景執行緒上跑一段工作；結果用 CompletableFuture 回來，呼叫端要自己跳回主執行緒。 */
    public <T> CompletableFuture<T> async(final SqlWork<T> work) {
        final ExecutorService service = this.executor;
        if (service == null || service.isShutdown()) {
            return CompletableFuture.failedFuture(new SQLException("資料庫尚未連線"));
        }
        final CompletableFuture<T> future = new CompletableFuture<>();
        queued.incrementAndGet();
        try {
            service.execute(() -> {
                final long start = System.nanoTime();
                try {
                    future.complete(sync(work));
                } catch (final Throwable e) {
                    future.completeExceptionally(e);
                } finally {
                    queued.decrementAndGet();
                    final double millis = (System.nanoTime() - start) / 1e6;
                    averageMillis = averageMillis <= 0 ? millis : averageMillis * 0.95 + millis * 0.05;
                    slowestMillis = Math.max(slowestMillis * 0.999, millis);
                }
            });
        } catch (final RuntimeException e) {
            queued.decrementAndGet(); // 執行緒已經關掉（關伺服器中）
            future.completeExceptionally(e);
        }
        return future;
    }

    /**
     * 同 {@link #async(SqlWork)}，但失敗時（連線拿不到、SQL 失敗、任何例外）在<b>資料庫執行緒上</b>呼叫 onFailure，
     * 用它的回傳值完成（不會以例外完成）。給「失敗時要在同一條執行緒上記下來、排在後面的工作之前」的地方用。
     */
    public <T> CompletableFuture<T> async(final SqlWork<T> work, final java.util.function.Function<Throwable, T> onFailure) {
        final ExecutorService service = this.executor;
        if (service == null || service.isShutdown()) {
            return CompletableFuture.failedFuture(new SQLException("資料庫尚未連線"));
        }
        final CompletableFuture<T> future = new CompletableFuture<>();
        queued.incrementAndGet();
        try {
            service.execute(() -> {
                final long start = System.nanoTime();
                try {
                    future.complete(sync(work));
                } catch (final Throwable e) {
                    try {
                        future.complete(onFailure.apply(e));
                    } catch (final Throwable second) {
                        second.addSuppressed(e);
                        future.completeExceptionally(second);
                    }
                } finally {
                    queued.decrementAndGet();
                    final double millis = (System.nanoTime() - start) / 1e6;
                    averageMillis = averageMillis <= 0 ? millis : averageMillis * 0.95 + millis * 0.05;
                    slowestMillis = Math.max(slowestMillis * 0.999, millis);
                }
            });
        } catch (final RuntimeException e) {
            queued.decrementAndGet();
            future.completeExceptionally(e);
        }
        return future;
    }

    /** 排隊中（還沒做完）的背景工作數。 */
    public int queued() {
        return queued.get();
    }

    /** 背景工作平均花多久（毫秒）。 */
    public double averageMillis() {
        return averageMillis;
    }

    /** 最近最慢的一次（毫秒，慢慢衰減）。 */
    public double slowestMillis() {
        return slowestMillis;
    }

    /** 量一次來回時間（毫秒），給 /ec db 用；連不上回傳 -1。 */
    public long ping() {
        final long start = System.nanoTime();
        try (Connection connection = connection();
             Statement statement = connection.createStatement();
             ResultSet result = statement.executeQuery("SELECT 1")) {
            result.next();
            return (System.nanoTime() - start) / 1_000_000;
        } catch (final SQLException e) {
            return -1;
        }
    }

    public boolean isConnected() {
        final HikariDataSource source = this.dataSource;
        return source != null && source.isRunning();
    }

    /** 給人看的說明，例如「H2 exilecore.mv.db」或「MariaDB 127.0.0.1:3306/exilecore」。 */
    public String description() {
        return description;
    }

    /** 等背景工作全部做完（最多 seconds 秒）；關伺服器前呼叫，避免最後一筆存檔沒寫進去。 */
    public void flush(final int seconds) {
        final ExecutorService service = this.executor;
        if (service == null) {
            return;
        }
        service.shutdown();
        try {
            if (!service.awaitTermination(seconds, TimeUnit.SECONDS)) {
                // 2026-10-18：記下還有幾件沒寫進去（之後關連線池，這些都會失敗）
                log.warn("資料庫背景工作 {} 秒內沒做完，還有 {} 件沒寫入，可能有存檔遺失", seconds, queued());
            }
        } catch (final InterruptedException e) {
            Thread.currentThread().interrupt();
        }
        this.executor = null;
    }

    @Override
    public void close() {
        flush(45); // 2026-10-18：10 秒太短（MariaDB 關服時慢一點最後一批存檔就沒了）；做完會提早結束，45 秒留在 watchdog 預設 60 秒內
        final HikariDataSource source = this.dataSource;
        if (source != null) {
            source.close();
            this.dataSource = null;
            this.description = "未連線";
        }
    }
}
