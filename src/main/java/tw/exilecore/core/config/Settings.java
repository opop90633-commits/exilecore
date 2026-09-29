package tw.exilecore.core.config;

import org.bukkit.configuration.ConfigurationSection;

import java.util.Locale;

/**
 * config.yml 讀進來後的不可變快照。要改設定就改檔案再 {@code /ec reload}，
 * 其他模組拿到的永遠是完整的一份，不會讀到改一半的值。
 */
public record Settings(Storage storage, String prefix) {

    /** 資料庫種類。 */
    public enum StorageType { H2, MARIADB }

    /** storage 區塊。 */
    public record Storage(
            StorageType type,
            String h2File,
            String host,
            int port,
            String database,
            String user,
            String password,
            int poolSize
    ) {
    }

    /** 從 Bukkit 的設定物件讀出快照；缺的欄位一律用預設值，不會因為少一行就啟動失敗。 */
    public static Settings load(final ConfigurationSection config) {
        final String typeRaw = config.getString("storage.type", "h2");
        final StorageType type = switch (typeRaw.trim().toLowerCase(Locale.ROOT)) {
            case "mariadb", "mysql" -> StorageType.MARIADB;
            default -> StorageType.H2;
        };

        final Storage storage = new Storage(
                type,
                config.getString("storage.h2.file", "exilecore"),
                config.getString("storage.mariadb.host", "127.0.0.1"),
                config.getInt("storage.mariadb.port", 3306),
                config.getString("storage.mariadb.database", "exilecore"),
                config.getString("storage.mariadb.user", "exilecore"),
                config.getString("storage.mariadb.password", "exilecore"),
                Math.max(1, config.getInt("storage.mariadb.pool-size", 8))
        );

        final String prefix = config.getString("messages.prefix",
                "<gradient:#f5b942:#e07a1f>ExileCore</gradient> <dark_gray>»</dark_gray> ");

        return new Settings(storage, prefix);
    }
}
