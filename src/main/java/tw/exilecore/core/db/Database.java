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

/**
 * 資料庫連線池。開發期預設 H2（單機檔案），正式環境切 MariaDB，
 * 兩者都透過 HikariCP，其他模組只看得到 {@link #connection()}。
 * <p>
 * 階段 0 只負責連線與健康檢查；資料表與遷移在階段 2 加入。
 */
public final class Database implements AutoCloseable {

    private final Logger log;
    private final Path dataFolder;

    private HikariDataSource dataSource;
    private String description = "未連線";

    public Database(final Logger log, final Path dataFolder) {
        this.log = log;
        this.dataFolder = dataFolder;
    }

    /** 依設定建立連線池，並跑一次 {@code SELECT 1} 確認真的連得上。 */
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
        log.info("資料庫已連線：{}", description);
    }

    /** 從連線池借一條連線；用完務必關閉（try-with-resources）。 */
    public Connection connection() throws SQLException {
        final HikariDataSource source = this.dataSource;
        if (source == null) {
            throw new SQLException("資料庫尚未連線");
        }
        return source.getConnection();
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

    @Override
    public void close() {
        final HikariDataSource source = this.dataSource;
        if (source != null) {
            source.close();
            this.dataSource = null;
            this.description = "未連線";
        }
    }
}
