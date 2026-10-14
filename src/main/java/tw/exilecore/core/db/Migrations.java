package tw.exilecore.core.db;

import org.slf4j.Logger;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;

/**
 * 資料表遷移：每個版本一組 SQL，依序套用，已套用的版本記在 {@code schema_version}。
 * SQL 同時能在 H2（MariaDB 模式）與 MariaDB 上跑，所以只用兩邊都有的型別：
 * BIGINT AUTO_INCREMENT、VARCHAR、INT、DOUBLE PRECISION、DATETIME、LONGBLOB、LONGTEXT。
 * <p>
 * 要改資料表：新增一個版本（不要改舊的），舊資料庫升級時只會跑新的那段。
 * MariaDB 的 DDL 會自動提交、不能回滾，所以每句都寫成 IF NOT EXISTS，失敗重跑也不會撞到已建好的表。
 */
public final class Migrations {

    /** 一個版本的遷移：SQL，再加上（需要的話）用程式做的資料轉換。 */
    private record Migration(int version, String description, List<String> statements, Job job) {
        Migration(final int version, final String description, final List<String> statements) {
            this(version, description, statements, null);
        }
    }

    /** 用程式做的資料轉換（SQL 寫不出來的，例如照表換算每個角色的等級）；跟 SQL 在同一個交易裡。回傳給主控台的摘要。 */
    @FunctionalInterface
    interface Job {
        String run(Connection connection) throws SQLException;
    }

    private static final List<Migration> MIGRATIONS = List.of(
            new Migration(1, "帳號、角色、背包、天賦配點、進度、通貨紀錄", List.of(
                    """
                    CREATE TABLE IF NOT EXISTS accounts (
                        uuid VARCHAR(36) NOT NULL PRIMARY KEY,
                        name VARCHAR(16) NOT NULL,
                        active_character BIGINT NULL,
                        created_at DATETIME NOT NULL,
                        last_seen DATETIME NOT NULL
                    )""",
                    """
                    CREATE TABLE IF NOT EXISTS characters (
                        id BIGINT AUTO_INCREMENT PRIMARY KEY,
                        account_uuid VARCHAR(36) NOT NULL,
                        slot INT NOT NULL,
                        name VARCHAR(32) NOT NULL,
                        class_id VARCHAR(16) NOT NULL,
                        char_level INT NOT NULL,
                        experience BIGINT NOT NULL,
                        bonus_points INT NOT NULL,
                        refund_points INT NOT NULL,
                        deaths INT NOT NULL,
                        playtime_seconds BIGINT NOT NULL,
                        life DOUBLE PRECISION NOT NULL,
                        mana DOUBLE PRECISION NOT NULL,
                        energy_shield DOUBLE PRECISION NOT NULL,
                        last_zone VARCHAR(32) NULL,
                        created_at DATETIME NOT NULL,
                        updated_at DATETIME NOT NULL,
                        deleted_at DATETIME NULL
                    )""",
                    "CREATE INDEX IF NOT EXISTS idx_characters_account ON characters (account_uuid)",
                    """
                    CREATE TABLE IF NOT EXISTS character_inventory (
                        character_id BIGINT NOT NULL PRIMARY KEY,
                        contents LONGBLOB NULL,
                        jewellery LONGBLOB NULL,
                        updated_at DATETIME NOT NULL
                    )""",
                    """
                    CREATE TABLE IF NOT EXISTS passive_allocations (
                        character_id BIGINT NOT NULL,
                        node_id VARCHAR(48) NOT NULL,
                        PRIMARY KEY (character_id, node_id)
                    )""",
                    """
                    CREATE TABLE IF NOT EXISTS progress (
                        character_id BIGINT NOT NULL,
                        progress_key VARCHAR(64) NOT NULL,
                        progress_value VARCHAR(255) NOT NULL,
                        PRIMARY KEY (character_id, progress_key)
                    )""",
                    """
                    CREATE TABLE IF NOT EXISTS currency_log (
                        id BIGINT AUTO_INCREMENT PRIMARY KEY,
                        character_id BIGINT NULL,
                        player_name VARCHAR(16) NOT NULL,
                        item_uuid VARCHAR(36) NOT NULL,
                        currency VARCHAR(32) NOT NULL,
                        before_json LONGTEXT NOT NULL,
                        after_json LONGTEXT NOT NULL,
                        created_at DATETIME NOT NULL
                    )"""
            )),
            new Migration(2, "角色金幣", List.of(
                    "ALTER TABLE characters ADD COLUMN IF NOT EXISTS gold BIGINT NOT NULL DEFAULT 0"
            )),
            new Migration(3, "帳號倉庫（物品頁與通貨頁）", List.of(
                    """
                    CREATE TABLE IF NOT EXISTS stash_pages (
                        account_uuid VARCHAR(36) NOT NULL,
                        page INT NOT NULL,
                        contents LONGBLOB NULL,
                        updated_at DATETIME NOT NULL,
                        PRIMARY KEY (account_uuid, page)
                    )""",
                    """
                    CREATE TABLE IF NOT EXISTS stash_currency (
                        account_uuid VARCHAR(36) NOT NULL,
                        currency_id VARCHAR(32) NOT NULL,
                        amount INT NOT NULL,
                        PRIMARY KEY (account_uuid, currency_id)
                    )"""
            )),
            new Migration(4, "技能面板（技能與按鍵）", List.of(
                    """
                    CREATE TABLE IF NOT EXISTS character_skills (
                        character_id BIGINT NOT NULL,
                        slot_index INT NOT NULL,
                        skill_id VARCHAR(48) NOT NULL,
                        skill_level INT NOT NULL,
                        supports VARCHAR(1024) NULL,
                        PRIMARY KEY (character_id, slot_index)
                    )""",
                    """
                    CREATE TABLE IF NOT EXISTS character_skill_binds (
                        character_id BIGINT NOT NULL,
                        bind_slot INT NOT NULL,
                        slot_index INT NOT NULL,
                        PRIMARY KEY (character_id, bind_slot)
                    )"""
            )),
            new Migration(5, "隨身背包（物品與通貨袋）、掉落過濾器", List.of(
                    "ALTER TABLE character_inventory ADD COLUMN IF NOT EXISTS bag LONGBLOB NULL",
                    "ALTER TABLE character_inventory ADD COLUMN IF NOT EXISTS bag_currency LONGTEXT NULL",
                    "ALTER TABLE characters ADD COLUMN IF NOT EXISTS loot_filter VARCHAR(1024) NULL"
            )),
            // 先加欄位（DDL 會自動提交），再把兩句 UPDATE 跟版本紀錄放在同一個交易：
            // 中途失敗時兩句 UPDATE 一起還原，重跑時從 characters.gold 重新加總，不會把金幣算成 0。
            // characters.gold 之後不再使用（歸零，避免以為還有錢在角色身上）。
            new Migration(6, "金幣改成帳號共用（現有角色的金幣加總到帳號）", List.of(
                    "ALTER TABLE accounts ADD COLUMN IF NOT EXISTS gold BIGINT NOT NULL DEFAULT 0",
                    "UPDATE accounts SET gold = (SELECT COALESCE(SUM(c.gold), 0) FROM characters c "
                            + "WHERE c.account_uuid = accounts.uuid AND c.deleted_at IS NULL)",
                    "UPDATE characters SET gold = 0"
            )),
            new Migration(7, "交易紀錄、市集（商店頁）、市集收入、通貨交易所", List.of(
                    """
                    CREATE TABLE IF NOT EXISTS trade_log (
                        id BIGINT AUTO_INCREMENT PRIMARY KEY,
                        trade_id VARCHAR(36) NOT NULL,
                        kind VARCHAR(16) NOT NULL,
                        item_uuid VARCHAR(36) NULL,
                        item_desc VARCHAR(255) NOT NULL,
                        amount INT NOT NULL,
                        from_account VARCHAR(36) NULL,
                        from_name VARCHAR(16) NULL,
                        to_account VARCHAR(36) NULL,
                        to_name VARCHAR(16) NULL,
                        created_at DATETIME NOT NULL
                    )""",
                    "CREATE INDEX IF NOT EXISTS idx_trade_log_item ON trade_log (item_uuid)",
                    "CREATE INDEX IF NOT EXISTS idx_trade_log_trade ON trade_log (trade_id)",
                    """
                    CREATE TABLE IF NOT EXISTS market_listings (
                        listing_id VARCHAR(36) NOT NULL PRIMARY KEY,
                        account_uuid VARCHAR(36) NOT NULL,
                        seller_name VARCHAR(16) NOT NULL,
                        item LONGBLOB NOT NULL,
                        item_uuid VARCHAR(36) NULL,
                        search_text LONGTEXT NOT NULL,
                        price_currency VARCHAR(32) NOT NULL,
                        price_amount INT NOT NULL,
                        listed_at DATETIME NOT NULL
                    )""",
                    "CREATE INDEX IF NOT EXISTS idx_market_listings_account ON market_listings (account_uuid)",
                    """
                    CREATE TABLE IF NOT EXISTS market_proceeds (
                        proceed_id VARCHAR(36) NOT NULL PRIMARY KEY,
                        account_uuid VARCHAR(36) NOT NULL,
                        currency_id VARCHAR(32) NOT NULL,
                        amount INT NOT NULL,
                        source VARCHAR(16) NOT NULL,
                        note VARCHAR(255) NOT NULL,
                        created_at DATETIME NOT NULL
                    )""",
                    "CREATE INDEX IF NOT EXISTS idx_market_proceeds_account ON market_proceeds (account_uuid)",
                    """
                    CREATE TABLE IF NOT EXISTS exchange_orders (
                        order_id VARCHAR(36) NOT NULL PRIMARY KEY,
                        account_uuid VARCHAR(36) NOT NULL,
                        player_name VARCHAR(16) NOT NULL,
                        give_currency VARCHAR(32) NOT NULL,
                        give_amount INT NOT NULL,
                        want_currency VARCHAR(32) NOT NULL,
                        want_amount INT NOT NULL,
                        created_at DATETIME NOT NULL
                    )""",
                    """
                    CREATE TABLE IF NOT EXISTS exchange_fills (
                        fill_id VARCHAR(36) NOT NULL PRIMARY KEY,
                        order_id VARCHAR(36) NOT NULL,
                        give_taken INT NOT NULL,
                        created_at DATETIME NOT NULL
                    )""",
                    "CREATE INDEX IF NOT EXISTS idx_exchange_fills_order ON exchange_fills (order_id)"
            )),
            new Migration(8, "倉庫頁籤（名稱與分類）", List.of(
                    """
                    CREATE TABLE IF NOT EXISTS stash_tabs (
                        account_uuid VARCHAR(36) NOT NULL,
                        page INT NOT NULL,
                        name VARCHAR(64) NOT NULL,
                        category VARCHAR(16) NOT NULL,
                        PRIMARY KEY (account_uuid, page)
                    )"""
            )),
            // 日期一律存成 "2026-10-02" 這種字串（伺服器時區的那一天）：備份還原、換資料庫都不會因為時區差一天
            new Migration(9, "經濟報表（每天的產出與消耗、存量、每個帳號的收入）、交易所成交價、參考價值", List.of(
                    """
                    CREATE TABLE IF NOT EXISTS economy_flow (
                        stat_day VARCHAR(10) NOT NULL,
                        kind VARCHAR(32) NOT NULL,
                        source VARCHAR(24) NOT NULL,
                        amount BIGINT NOT NULL,
                        PRIMARY KEY (stat_day, kind, source)
                    )""",
                    """
                    CREATE TABLE IF NOT EXISTS economy_stock (
                        stat_day VARCHAR(10) NOT NULL,
                        kind VARCHAR(32) NOT NULL,
                        amount BIGINT NOT NULL,
                        PRIMARY KEY (stat_day, kind)
                    )""",
                    """
                    CREATE TABLE IF NOT EXISTS economy_income (
                        stat_day VARCHAR(10) NOT NULL,
                        account_uuid VARCHAR(36) NOT NULL,
                        player_name VARCHAR(16) NOT NULL,
                        kills INT NOT NULL,
                        gold BIGINT NOT NULL,
                        currency_value BIGINT NOT NULL,
                        PRIMARY KEY (stat_day, account_uuid)
                    )""",
                    """
                    CREATE TABLE IF NOT EXISTS exchange_trades (
                        id BIGINT AUTO_INCREMENT PRIMARY KEY,
                        traded_at DATETIME NOT NULL,
                        give_currency VARCHAR(32) NOT NULL,
                        give_amount INT NOT NULL,
                        want_currency VARCHAR(32) NOT NULL,
                        want_amount INT NOT NULL,
                        maker_account VARCHAR(36) NULL,
                        taker_account VARCHAR(36) NULL
                    )""",
                    "CREATE INDEX IF NOT EXISTS idx_exchange_trades_time ON exchange_trades (traded_at)",
                    """
                    CREATE TABLE IF NOT EXISTS currency_values (
                        stat_day VARCHAR(10) NOT NULL,
                        currency_id VARCHAR(32) NOT NULL,
                        ref_value BIGINT NOT NULL,
                        pairs INT NOT NULL,
                        PRIMARY KEY (stat_day, currency_id)
                    )"""
            )),
            // 帳號共用的進度（每日委託、每週回響、功勳兌換買過的東西、稱號），跟角色的 progress 一樣是鍵值
            new Migration(10, "功勳（帳號共用）、帳號進度（每日委託、每週回響、兌換紀錄、稱號）", List.of(
                    "ALTER TABLE accounts ADD COLUMN IF NOT EXISTS merits BIGINT NOT NULL DEFAULT 0",
                    """
                    CREATE TABLE IF NOT EXISTS account_progress (
                        account_uuid VARCHAR(36) NOT NULL,
                        progress_key VARCHAR(64) NOT NULL,
                        progress_value VARCHAR(255) NOT NULL,
                        PRIMARY KEY (account_uuid, progress_key)
                    )"""
            )),
            // 天賦樹珠寶孔裡的珠寶（InventoryCodec.encodeJewels：節點代號＋珠寶），跟飾品一樣放在角色的背包列
            new Migration(11, "天賦樹的珠寶", List.of(
                    "ALTER TABLE character_inventory ADD COLUMN IF NOT EXISTS jewels LONGBLOB NULL"
            )),
            // 2026-10-05 經驗表換成 PoE2 的表：舊角色照以前殺怪的量換算等級，降級的天賦全部退回（見 LevelConversion）
            new Migration(12, "經驗表改版：舊角色照以前殺怪的量換算等級，降級的天賦全部退回", List.of(),
                    tw.exilecore.character.LevelConversion::migrate),
            // 2026-10-05 全服經驗加倍：遷移 12 換算過的角色照加倍後的速度補等級（不會比換算前高，見 LevelConversion.boost）
            new Migration(13, "全服經驗加倍：舊角色的等級換算改成照加倍後的速度，補等級", List.of(),
                    tw.exilecore.character.LevelConversion::migrateBonus),
            // 管理網頁：發給玩家的物資（線上直接給、離線的登入時領；放不下的留著等 /claim）與管理員的操作紀錄
            new Migration(14, "管理網頁：發放的物資（待領取）與操作紀錄", List.of(
                    """
                    CREATE TABLE IF NOT EXISTS admin_deliveries (
                        id BIGINT AUTO_INCREMENT PRIMARY KEY,
                        batch_id VARCHAR(36) NOT NULL,
                        account_uuid VARCHAR(36) NOT NULL,
                        player_name VARCHAR(32) NOT NULL,
                        kind VARCHAR(16) NOT NULL,
                        ref VARCHAR(64) NULL,
                        rarity VARCHAR(16) NULL,
                        item_level INT NOT NULL,
                        amount BIGINT NOT NULL,
                        original BIGINT NOT NULL,
                        label VARCHAR(255) NOT NULL,
                        note VARCHAR(255) NOT NULL,
                        created_by VARCHAR(32) NOT NULL,
                        created_at DATETIME NOT NULL,
                        claimed_at DATETIME NULL,
                        claimed_by VARCHAR(32) NULL,
                        cancelled_at DATETIME NULL,
                        cancelled_by VARCHAR(32) NULL
                    )""",
                    "CREATE INDEX IF NOT EXISTS idx_admin_deliveries_account ON admin_deliveries (account_uuid)",
                    "CREATE INDEX IF NOT EXISTS idx_admin_deliveries_batch ON admin_deliveries (batch_id)",
                    """
                    CREATE TABLE IF NOT EXISTS admin_log (
                        id BIGINT AUTO_INCREMENT PRIMARY KEY,
                        created_at DATETIME NOT NULL,
                        admin_name VARCHAR(32) NOT NULL,
                        action VARCHAR(32) NOT NULL,
                        detail VARCHAR(1000) NOT NULL
                    )"""
            )),
            // 2026-10：掉落過濾可以一直加「一定要撿的基底」，1024 字不夠
            new Migration(15, "掉落過濾的欄位加長", List.of(
                    "ALTER TABLE characters MODIFY COLUMN loot_filter LONGTEXT NULL"
            )),
            // 2026-10：星幣券（星幣換成可以交易的物品）：每張一個代號，兌換時照這張表只能用一次（複製出來的券兌換不了）
            new Migration(16, "星幣券", List.of(
                    """
                    CREATE TABLE IF NOT EXISTS star_vouchers (
                        id VARCHAR(36) NOT NULL PRIMARY KEY,
                        amount BIGINT NOT NULL,
                        issuer_uuid VARCHAR(36) NOT NULL,
                        issuer_name VARCHAR(32) NOT NULL,
                        created_at DATETIME NOT NULL,
                        redeemed_uuid VARCHAR(36) NULL,
                        redeemed_name VARCHAR(32) NULL,
                        redeemed_at DATETIME NULL
                    )"""
            )),
            // 2026-10：星幣券下線也有效——delivered 0 = 券還沒交到開券的人手上（上線時補給），credited 0 = 兌換了但星幣還沒加（上線時補）
            new Migration(17, "星幣券：下線時的補發", List.of(
                    "ALTER TABLE star_vouchers ADD COLUMN IF NOT EXISTS delivered INT NOT NULL DEFAULT 1",
                    "ALTER TABLE star_vouchers ADD COLUMN IF NOT EXISTS credited INT NOT NULL DEFAULT 1"
            )),
            // 2026-10：管理網頁發指定詞綴（與數值）的裝備、傳奇——格式見 admin.AffixSpec
            new Migration(18, "管理員發放：指定詞綴", List.of(
                    "ALTER TABLE admin_deliveries ADD COLUMN IF NOT EXISTS affixes TEXT NULL"
            )),
            // 2026-10：網頁角色頁（像 poe.ninja）：每個角色最後的樣子（裝備、天賦、技能、數值；JSON），玩家可以設成不公開
            new Migration(19, "網頁角色頁的快照", List.of(
                    """
                    CREATE TABLE IF NOT EXISTS character_profiles (
                        character_id BIGINT PRIMARY KEY,
                        account_uuid VARCHAR(36) NOT NULL,
                        name VARCHAR(32) NOT NULL,
                        class_id VARCHAR(32) NOT NULL,
                        level INT NOT NULL,
                        ascendancy VARCHAR(64) NULL,
                        hidden INT NOT NULL DEFAULT 0,
                        data LONGTEXT NOT NULL,
                        updated_at DATETIME NOT NULL
                    )""",
                    "CREATE INDEX IF NOT EXISTS idx_character_profiles_name ON character_profiles (name)"
            )),
            // 2026-10：角色進度的值加長——開著的光環、召喚（aura.active）多了會超過 255 字，存不進去（伺服器紀錄裡一直報錯）
            new Migration(20, "角色進度的欄位加長", List.of(
                    "ALTER TABLE progress MODIFY COLUMN progress_value VARCHAR(4096) NOT NULL",
                    "ALTER TABLE account_progress MODIFY COLUMN progress_value VARCHAR(4096) NOT NULL"
            )),
            // 2026-10：公會。代號由程式給（建立那一刻記憶體裡就要有），所以 guilds.id 不是自動編號
            new Migration(21, "公會（公會、成員、公會倉庫、公會紀錄）", List.of(
                    """
                    CREATE TABLE IF NOT EXISTS guilds (
                        id BIGINT NOT NULL PRIMARY KEY,
                        name VARCHAR(32) NOT NULL,
                        tag VARCHAR(16) NOT NULL,
                        leader_uuid VARCHAR(36) NOT NULL,
                        guild_level INT NOT NULL,
                        experience BIGINT NOT NULL,
                        motd VARCHAR(255) NOT NULL,
                        withdraw_rank INT NOT NULL,
                        created_at DATETIME NOT NULL
                    )""",
                    """
                    CREATE TABLE IF NOT EXISTS guild_members (
                        account_uuid VARCHAR(36) NOT NULL PRIMARY KEY,
                        guild_id BIGINT NOT NULL,
                        name VARCHAR(32) NOT NULL,
                        guild_rank INT NOT NULL,
                        contribution BIGINT NOT NULL,
                        joined_at DATETIME NOT NULL
                    )""",
                    "CREATE INDEX IF NOT EXISTS idx_guild_members_guild ON guild_members (guild_id)",
                    """
                    CREATE TABLE IF NOT EXISTS guild_stash (
                        guild_id BIGINT NOT NULL,
                        page INT NOT NULL,
                        contents LONGBLOB NULL,
                        updated_at DATETIME NOT NULL,
                        PRIMARY KEY (guild_id, page)
                    )""",
                    """
                    CREATE TABLE IF NOT EXISTS guild_log (
                        id BIGINT AUTO_INCREMENT PRIMARY KEY,
                        guild_id BIGINT NOT NULL,
                        created_at DATETIME NOT NULL,
                        actor VARCHAR(32) NOT NULL,
                        action VARCHAR(16) NOT NULL,
                        detail VARCHAR(255) NOT NULL
                    )""",
                    "CREATE INDEX IF NOT EXISTS idx_guild_log_guild ON guild_log (guild_id, id)"
            )),
            new Migration(22, "公會：用星幣多買的倉庫頁", List.of(
                    "ALTER TABLE guilds ADD COLUMN IF NOT EXISTS bought_pages INT NOT NULL DEFAULT 0"
            )),
            new Migration(23, "公會：入會申請", List.of(
                    """
                    CREATE TABLE IF NOT EXISTS guild_applications (
                        guild_id BIGINT NOT NULL,
                        account_uuid VARCHAR(36) NOT NULL,
                        name VARCHAR(32) NOT NULL,
                        char_level INT NOT NULL,
                        message VARCHAR(100) NOT NULL,
                        created_at DATETIME NOT NULL,
                        PRIMARY KEY (guild_id, account_uuid)
                    )""",
                    "CREATE INDEX IF NOT EXISTS idx_guild_applications_account ON guild_applications (account_uuid)"
            )),
            // 2026-10：衝等活動——每個昇華第一個升到 100 級的角色（昇華代號是主鍵：只會有一個）
            new Migration(24, "衝等活動", List.of(
                    """
                    CREATE TABLE IF NOT EXISTS level_race (
                        ascendancy VARCHAR(64) NOT NULL PRIMARY KEY,
                        account_uuid VARCHAR(36) NOT NULL,
                        character_id BIGINT NOT NULL,
                        character_name VARCHAR(32) NOT NULL,
                        player_name VARCHAR(32) NOT NULL,
                        level INT NOT NULL,
                        claimed_at DATETIME NOT NULL
                    )"""
            )),
            // 2026-10：贊助商店的訂單（統一金流 PAYUNi）。付款成功時在同一筆訂單編號的發放批次裡發星幣（重送的通知不會發兩次）
            new Migration(25, "贊助商店訂單", List.of(
                    """
                    CREATE TABLE IF NOT EXISTS shop_orders (
                        order_no VARCHAR(25) NOT NULL PRIMARY KEY,
                        account_uuid VARCHAR(36) NOT NULL,
                        player_name VARCHAR(32) NOT NULL,
                        stars BIGINT NOT NULL,
                        bonus BIGINT NOT NULL,
                        amount INT NOT NULL,
                        email VARCHAR(120) NULL,
                        status VARCHAR(16) NOT NULL,
                        trade_no VARCHAR(40) NULL,
                        payment_type INT NULL,
                        remote VARCHAR(64) NULL,
                        created_at DATETIME NOT NULL,
                        paid_at DATETIME NULL,
                        note VARCHAR(255) NULL
                    )""",
                    "CREATE INDEX IF NOT EXISTS idx_shop_orders_account ON shop_orders (account_uuid)"
            ))
    );

    /** 所有資料表（備份與還原的順序：被參照的在前面）。新增資料表時也要加進來。 */
    public static final List<String> TABLES = List.of(
            "schema_version", "accounts", "characters", "character_inventory", "passive_allocations", "progress",
            "currency_log", "stash_pages", "stash_currency", "character_skills", "character_skill_binds",
            "trade_log", "market_listings", "market_proceeds", "exchange_orders", "exchange_fills", "stash_tabs",
            "economy_flow", "economy_stock", "economy_income", "exchange_trades", "currency_values", "account_progress",
            "admin_deliveries", "admin_log", "star_vouchers", "character_profiles",
            "guilds", "guild_members", "guild_stash", "guild_log", "guild_applications", "level_race", "shop_orders");

    private Migrations() {
    }

    public static int currentVersion() {
        return MIGRATIONS.get(MIGRATIONS.size() - 1).version();
    }

    /** 某個版本的 SQL（單元測試用：重跑一次看結果）。 */
    static List<String> statements(final int version) {
        for (final Migration migration : MIGRATIONS) {
            if (migration.version() == version) {
                return migration.statements();
            }
        }
        return List.of();
    }

    /** 套用還沒跑過的版本；回傳這次套用了幾個。 */
    public static int apply(final Database database, final Logger log) throws SQLException {
        return apply(database, log, Integer.MAX_VALUE);
    }

    /** 套用還沒跑過、版本 ≤ upTo 的（還原舊版的備份：先建到備份當時的版本）；回傳這次套用了幾個。 */
    public static int apply(final Database database, final Logger log, final int upTo) throws SQLException {
        return database.sync(connection -> {
            try (Statement statement = connection.createStatement()) {
                statement.executeUpdate("CREATE TABLE IF NOT EXISTS schema_version ("
                        + "version INT NOT NULL PRIMARY KEY, description VARCHAR(255) NOT NULL, applied_at DATETIME NOT NULL)");
            }
            int installed = 0;
            try (Statement statement = connection.createStatement();
                 ResultSet result = statement.executeQuery("SELECT MAX(version) FROM schema_version")) {
                if (result.next()) {
                    installed = result.getInt(1);
                }
            }
            int applied = 0;
            for (final Migration migration : MIGRATIONS) {
                if (migration.version() <= installed || migration.version() > upTo) {
                    continue;
                }
                final String summary = runMigration(connection, migration);
                log.info("資料表遷移 {}：{}", migration.version(), migration.description());
                if (summary != null && !summary.isBlank()) {
                    log.info("資料表遷移 {}：{}", migration.version(), summary);
                }
                applied++;
            }
            return applied;
        });
    }

    /** 跑一個版本（同一個交易）；回傳程式轉換的摘要（沒有是 null）。 */
    private static String runMigration(final Connection connection, final Migration migration) throws SQLException {
        final boolean autoCommit = connection.getAutoCommit();
        connection.setAutoCommit(false);
        try {
            try (Statement statement = connection.createStatement()) {
                for (final String sql : migration.statements()) {
                    statement.executeUpdate(sql);
                }
            }
            final String summary = migration.job() == null ? null : migration.job().run(connection);
            try (PreparedStatement statement = connection.prepareStatement(
                    "INSERT INTO schema_version (version, description, applied_at) VALUES (?, ?, ?)")) {
                statement.setInt(1, migration.version());
                statement.setString(2, migration.description());
                statement.setTimestamp(3, Timestamp.from(Instant.now()));
                statement.executeUpdate();
            }
            connection.commit();
            return summary;
        } catch (final Throwable e) {
            // 任何錯誤都回滾（不然 finally 打開自動提交時，做到一半的轉換會被提交）；回滾失敗不蓋掉原本的錯誤
            try {
                connection.rollback();
            } catch (final SQLException rollback) {
                e.addSuppressed(rollback);
            }
            if (e instanceof Error error) {
                throw error;
            }
            throw new SQLException("資料表遷移 " + migration.version() + " 失敗：" + e.getMessage(), e);
        } finally {
            connection.setAutoCommit(autoCommit);
        }
    }
}
