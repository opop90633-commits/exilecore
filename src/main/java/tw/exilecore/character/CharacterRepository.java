package tw.exilecore.character;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

/**
 * 帳號與角色的 SQL 存取。每個方法都拿一條連線進來、在呼叫端的執行緒上跑，
 * 不碰 Bukkit，所以單元測試可以用記憶體 H2 直接跑。
 */
public final class CharacterRepository {

    /** 帳號列（金幣是帳號共用的，照 PoE2）。 */
    public record Account(UUID uuid, String name, Long activeCharacter, long gold) {
    }

    /**
     * 背包、飾品、隨身背包、天賦樹珠寶的序列化結果（原版 ItemStack 序列化的 byte[]，null = 沒存過）。
     *
     * @param bag         隨身背包的物品（所有頁攤平）
     * @param bagCurrency 隨身背包的通貨袋（{@link CurrencyPouch#encode()}）
     * @param jewels      天賦樹珠寶孔裡的珠寶（{@link InventoryCodec#encodeJewels}：節點代號＋珠寶）
     */
    public record InventoryBlob(byte[] contents, byte[] jewellery, byte[] bag, String bagCurrency, byte[] jewels) {

        /** 沒有隨身背包的版本（新角色、舊測試）。 */
        public InventoryBlob(final byte[] contents, final byte[] jewellery) {
            this(contents, jewellery, null, null, null);
        }

        /** 沒有珠寶的版本（舊測試）。 */
        public InventoryBlob(final byte[] contents, final byte[] jewellery, final byte[] bag, final String bagCurrency) {
            this(contents, jewellery, bag, bagCurrency, null);
        }
    }

    /** 載入一個角色需要的全部資料。 */
    public record Loaded(CharacterRecord record, InventoryBlob inventory, Set<String> passives, Map<String, String> progress,
                         SkillLoadout skills) {
    }

    /** 和角色存檔放在同一個交易裡的額外工作（例如倉庫、交易紀錄、市集的上架）。 */
    @FunctionalInterface
    public interface ExtraWork {
        void run(Connection connection) throws SQLException;

        /** 依序做 a 再做 b（兩個都可以是 null）。 */
        static ExtraWork both(final ExtraWork a, final ExtraWork b) {
            if (a == null) {
                return b;
            }
            if (b == null) {
                return a;
            }
            return connection -> {
                a.run(connection);
                b.run(connection);
            };
        }
    }

    /**
     * 一個玩家的存檔快照（主執行緒拍、背景執行緒寫）：角色列、背包、配點、技能面板、帳號金幣、倉庫。
     *
     * @param account   帳號
     * @param record    角色列的快照
     * @param inventory 背包；null = 不動
     * @param passives  天賦配點；null = 不動
     * @param skills    技能面板；null = 不動
     * @param gold      帳號的金幣；null = 不動
     * @param extra     一起寫的額外工作（倉庫）；可為 null
     */
    public record SaveJob(UUID account, CharacterRecord record, InventoryBlob inventory, Set<String> passives,
                          SkillLoadout skills, Long gold, ExtraWork extra) {
    }

    // ---- 帳號 ----

    /** 讀帳號，沒有就建立。 */
    public Account loadOrCreateAccount(final Connection connection, final UUID uuid, final String name) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement(
                "SELECT name, active_character, gold FROM accounts WHERE uuid = ?")) {
            statement.setString(1, uuid.toString());
            try (ResultSet result = statement.executeQuery()) {
                if (result.next()) {
                    final long active = result.getLong("active_character");
                    final Long activeOrNull = result.wasNull() ? null : active;
                    final long gold = result.getLong("gold");
                    try (PreparedStatement update = connection.prepareStatement(
                            "UPDATE accounts SET name = ?, last_seen = ? WHERE uuid = ?")) {
                        update.setString(1, name);
                        update.setTimestamp(2, now());
                        update.setString(3, uuid.toString());
                        update.executeUpdate();
                    }
                    return new Account(uuid, name, activeOrNull, gold);
                }
            }
        }
        try (PreparedStatement insert = connection.prepareStatement(
                "INSERT INTO accounts (uuid, name, active_character, created_at, last_seen) VALUES (?, ?, NULL, ?, ?)")) {
            insert.setString(1, uuid.toString());
            insert.setString(2, name);
            insert.setTimestamp(3, now());
            insert.setTimestamp(4, now());
            insert.executeUpdate();
        }
        return new Account(uuid, name, null, 0);
    }

    /** 寫帳號的金幣。 */
    public void updateAccountGold(final Connection connection, final UUID uuid, final long gold) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement("UPDATE accounts SET gold = ? WHERE uuid = ?")) {
            statement.setLong(1, Math.max(0, gold));
            statement.setString(2, uuid.toString());
            statement.executeUpdate();
        }
    }

    /** 讀帳號的金幣（沒有這個帳號回傳 0）。 */
    public long accountGold(final Connection connection, final UUID uuid) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement("SELECT gold FROM accounts WHERE uuid = ?")) {
            statement.setString(1, uuid.toString());
            try (ResultSet result = statement.executeQuery()) {
                return result.next() ? result.getLong(1) : 0;
            }
        }
    }

    public void setActiveCharacter(final Connection connection, final UUID uuid, final Long characterId) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement(
                "UPDATE accounts SET active_character = ? WHERE uuid = ?")) {
            if (characterId == null) {
                statement.setNull(1, java.sql.Types.BIGINT);
            } else {
                statement.setLong(1, characterId);
            }
            statement.setString(2, uuid.toString());
            statement.executeUpdate();
        }
    }

    // ---- 角色 ----

    /**
     * 角色名稱有沒有人用（不分大小寫；PoE 的角色名稱全服唯一，私訊、組隊才找得到人）：
     * 別人還在的角色用了，或是別的玩家的玩家 ID（不然可以取一個跟別人玩家 ID 一樣的角色名稱冒充他）。
     *
     * @param excludeId 改名時排除自己的角色（建立時給 0）
     * @param account   自己的帳號（自己的玩家 ID 可以拿來當角色名稱）
     */
    public boolean nameTaken(final Connection connection, final String name, final long excludeId, final UUID account) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement(
                "SELECT 1 FROM characters WHERE LOWER(name) = LOWER(?) AND deleted_at IS NULL AND id <> ?")) {
            statement.setString(1, name.trim());
            statement.setLong(2, excludeId);
            try (ResultSet result = statement.executeQuery()) {
                if (result.next()) {
                    return true;
                }
            }
        }
        try (PreparedStatement statement = connection.prepareStatement(
                "SELECT 1 FROM accounts WHERE LOWER(name) = LOWER(?) AND uuid <> ?")) {
            statement.setString(1, name.trim());
            statement.setString(2, account.toString());
            try (ResultSet result = statement.executeQuery()) {
                return result.next();
            }
        }
    }

    /** 換職業（混沌術士拆成混沌術士與元素法師，玩家選一次）。 */
    public void updateClass(final Connection connection, final long id, final CharacterClass characterClass) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement("UPDATE characters SET class_id = ? WHERE id = ?")) {
            statement.setString(1, characterClass.id());
            statement.setLong(2, id);
            statement.executeUpdate();
        }
    }

    /** 改名（查名稱有沒有人用跟改名要在同一個資料庫工作裡：資料庫只有一條執行緒，中間不會有別人插隊）。 */
    public void renameCharacter(final Connection connection, final long id, final String name) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement("UPDATE characters SET name = ? WHERE id = ?")) {
            statement.setString(1, name);
            statement.setLong(2, id);
            statement.executeUpdate();
        }
    }

    /** 帳號裡所有還在的角色，依格子排序。 */
    public List<CharacterRecord> listCharacters(final Connection connection, final UUID account) throws SQLException {
        final List<CharacterRecord> list = new ArrayList<>();
        try (PreparedStatement statement = connection.prepareStatement(
                "SELECT * FROM characters WHERE account_uuid = ? AND deleted_at IS NULL ORDER BY slot")) {
            statement.setString(1, account.toString());
            try (ResultSet result = statement.executeQuery()) {
                while (result.next()) {
                    list.add(read(result));
                }
            }
        }
        return list;
    }

    public Optional<CharacterRecord> findCharacter(final Connection connection, final long id) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement(
                "SELECT * FROM characters WHERE id = ? AND deleted_at IS NULL")) {
            statement.setLong(1, id);
            try (ResultSet result = statement.executeQuery()) {
                return result.next() ? Optional.of(read(result)) : Optional.empty();
            }
        }
    }

    private static CharacterRecord read(final ResultSet result) throws SQLException {
        final CharacterRecord record = new CharacterRecord(result.getLong("id"),
                UUID.fromString(result.getString("account_uuid")), result.getInt("slot"),
                result.getString("name"), CharacterClass.parse(result.getString("class_id"), CharacterClass.MARAUDER));
        record.setLevel(result.getInt("char_level"));
        record.setExperience(result.getLong("experience"));
        record.setBonusPoints(result.getInt("bonus_points"));
        record.setRefundPoints(result.getInt("refund_points"));
        record.setDeaths(result.getInt("deaths"));
        record.setPlaytimeSeconds(result.getLong("playtime_seconds"));
        record.setResources(result.getDouble("life"), result.getDouble("mana"), result.getDouble("energy_shield"));
        record.setLastZone(result.getString("last_zone"));
        record.setLootFilter(result.getString("loot_filter"));
        return record;
    }

    /** 新增角色並回填 id。 */
    /**
     * 建立角色（同一個交易）：新增角色、起始背包、進度（新手武器…），並設成帳號使用中的角色。
     * 中途失敗全部還原，不會留下沒有背包、不在角色清單裡、之後又在同一格再建一次的半個角色。
     */
    public void createCharacter(final Connection connection, final CharacterRecord record, final InventoryBlob inventory,
                                final Map<String, String> progress) throws SQLException {
        final boolean autoCommit = connection.getAutoCommit();
        connection.setAutoCommit(false);
        try {
            insertCharacter(connection, record);
            saveInventory(connection, record.id(), inventory);
            for (final Map.Entry<String, String> entry : progress.entrySet()) {
                setProgress(connection, record.id(), entry.getKey(), entry.getValue());
            }
            setActiveCharacter(connection, record.account(), record.id());
            connection.commit();
        } catch (final SQLException | RuntimeException e) {
            connection.rollback();
            throw e;
        } finally {
            connection.setAutoCommit(autoCommit);
        }
    }

    public CharacterRecord insertCharacter(final Connection connection, final CharacterRecord record) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement(
                "INSERT INTO characters (account_uuid, slot, name, class_id, char_level, experience, bonus_points, refund_points, "
                        + "deaths, playtime_seconds, life, mana, energy_shield, last_zone, created_at, updated_at, deleted_at, gold, loot_filter) "
                        + "VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, NULL, ?, ?)",
                Statement.RETURN_GENERATED_KEYS)) {
            statement.setString(1, record.account().toString());
            statement.setInt(2, record.slot());
            statement.setString(3, record.name());
            statement.setString(4, record.characterClass().id());
            statement.setInt(5, record.level());
            statement.setLong(6, record.experience());
            statement.setInt(7, record.bonusPoints());
            statement.setInt(8, record.refundPoints());
            statement.setInt(9, record.deaths());
            statement.setLong(10, record.playtimeSeconds());
            statement.setDouble(11, record.life());
            statement.setDouble(12, record.mana());
            statement.setDouble(13, record.energyShield());
            statement.setString(14, record.lastZone());
            statement.setTimestamp(15, now());
            statement.setTimestamp(16, now());
            statement.setLong(17, 0); // 金幣在帳號上（accounts.gold），這個欄位不再使用
            statement.setString(18, record.lootFilter());
            statement.executeUpdate();
            try (ResultSet keys = statement.getGeneratedKeys()) {
                if (keys.next()) {
                    record.setId(keys.getLong(1));
                }
            }
        }
        return record;
    }

    /** 一段要一起成功（或一起還原）的寫入。 */
    @FunctionalInterface
    public interface SqlWork {
        void run() throws SQLException;
    }

    /** 在一個交易裡做完 work（呼叫端已經在資料庫執行緒上）：中途失敗全部還原。 */
    public void inTransaction(final Connection connection, final SqlWork work) throws SQLException {
        final boolean autoCommit = connection.getAutoCommit();
        connection.setAutoCommit(false);
        try {
            work.run();
            connection.commit();
        } catch (final SQLException | RuntimeException e) {
            connection.rollback();
            throw e;
        } finally {
            connection.setAutoCommit(autoCommit);
        }
    }

    public void updateCharacter(final Connection connection, final CharacterRecord record) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement(
                "UPDATE characters SET name = ?, char_level = ?, experience = ?, bonus_points = ?, refund_points = ?, deaths = ?, "
                        + "playtime_seconds = ?, life = ?, mana = ?, energy_shield = ?, last_zone = ?, updated_at = ?, "
                        + "loot_filter = ?, class_id = ? WHERE id = ?")) {
            statement.setString(1, record.name());
            statement.setInt(2, record.level());
            statement.setLong(3, record.experience());
            statement.setInt(4, record.bonusPoints());
            statement.setInt(5, record.refundPoints());
            statement.setInt(6, record.deaths());
            statement.setLong(7, record.playtimeSeconds());
            statement.setDouble(8, record.life());
            statement.setDouble(9, record.mana());
            statement.setDouble(10, record.energyShield());
            statement.setString(11, record.lastZone());
            statement.setTimestamp(12, now());
            statement.setString(13, record.lootFilter());
            statement.setString(14, record.characterClass().id()); // 混沌術士選了元素法師之後，平常的存檔也帶著職業
            statement.setLong(15, record.id());
            statement.executeUpdate();
        }
    }

    /** 軟刪除：資料留著（查作弊、反悔可救回），列表與格子都當它不存在。 */
    public void deleteCharacter(final Connection connection, final long id) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement(
                "UPDATE characters SET deleted_at = ? WHERE id = ? AND deleted_at IS NULL")) {
            statement.setTimestamp(1, now());
            statement.setLong(2, id);
            statement.executeUpdate();
        }
    }

    // ---- 背包 ----

    public InventoryBlob loadInventory(final Connection connection, final long characterId) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement(
                "SELECT contents, jewellery, bag, bag_currency, jewels FROM character_inventory WHERE character_id = ?")) {
            statement.setLong(1, characterId);
            try (ResultSet result = statement.executeQuery()) {
                if (result.next()) {
                    return new InventoryBlob(result.getBytes("contents"), result.getBytes("jewellery"),
                            result.getBytes("bag"), result.getString("bag_currency"), result.getBytes("jewels"));
                }
            }
        }
        return new InventoryBlob(null, null);
    }

    public void saveInventory(final Connection connection, final long characterId, final InventoryBlob blob) throws SQLException {
        try (PreparedStatement update = connection.prepareStatement(
                "UPDATE character_inventory SET contents = ?, jewellery = ?, bag = ?, bag_currency = ?, jewels = ?, updated_at = ? "
                        + "WHERE character_id = ?")) {
            update.setBytes(1, blob.contents());
            update.setBytes(2, blob.jewellery());
            update.setBytes(3, blob.bag());
            update.setString(4, blob.bagCurrency());
            update.setBytes(5, blob.jewels());
            update.setTimestamp(6, now());
            update.setLong(7, characterId);
            if (update.executeUpdate() > 0) {
                return;
            }
        }
        try (PreparedStatement insert = connection.prepareStatement(
                "INSERT INTO character_inventory (character_id, contents, jewellery, bag, bag_currency, jewels, updated_at) "
                        + "VALUES (?, ?, ?, ?, ?, ?, ?)")) {
            insert.setLong(1, characterId);
            insert.setBytes(2, blob.contents());
            insert.setBytes(3, blob.jewellery());
            insert.setBytes(4, blob.bag());
            insert.setString(5, blob.bagCurrency());
            insert.setBytes(6, blob.jewels());
            insert.setTimestamp(7, now());
            insert.executeUpdate();
        }
    }

    // ---- 天賦配點 ----

    public Set<String> loadPassives(final Connection connection, final long characterId) throws SQLException {
        final Set<String> set = new HashSet<>();
        try (PreparedStatement statement = connection.prepareStatement(
                "SELECT node_id FROM passive_allocations WHERE character_id = ?")) {
            statement.setLong(1, characterId);
            try (ResultSet result = statement.executeQuery()) {
                while (result.next()) {
                    set.add(result.getString(1));
                }
            }
        }
        return set;
    }

    public void savePassives(final Connection connection, final long characterId, final Set<String> passives) throws SQLException {
        try (PreparedStatement delete = connection.prepareStatement(
                "DELETE FROM passive_allocations WHERE character_id = ?")) {
            delete.setLong(1, characterId);
            delete.executeUpdate();
        }
        if (passives.isEmpty()) {
            return;
        }
        try (PreparedStatement insert = connection.prepareStatement(
                "INSERT INTO passive_allocations (character_id, node_id) VALUES (?, ?)")) {
            for (final String id : passives) {
                insert.setLong(1, characterId);
                insert.setString(2, id);
                insert.addBatch();
            }
            insert.executeBatch();
        }
    }

    // ---- 技能面板 ----

    /** 角色的技能與按鍵；存檔裡位置或按鍵超出範圍的列略過。 */
    public SkillLoadout loadSkills(final Connection connection, final long characterId) throws SQLException {
        final SkillLoadout loadout = new SkillLoadout();
        try (PreparedStatement statement = connection.prepareStatement(
                "SELECT slot_index, skill_id, skill_level, supports FROM character_skills WHERE character_id = ?")) {
            statement.setLong(1, characterId);
            try (ResultSet result = statement.executeQuery()) {
                while (result.next()) {
                    final int position = result.getInt(1);
                    if (position >= 0 && position < SkillLoadout.MAX_SKILLS) {
                        loadout.set(position, SkillLoadout.Entry.decode(result.getString(2), result.getInt(3), result.getString(4)));
                    }
                }
            }
        }
        try (PreparedStatement statement = connection.prepareStatement(
                "SELECT bind_slot, slot_index FROM character_skill_binds WHERE character_id = ?")) {
            statement.setLong(1, characterId);
            try (ResultSet result = statement.executeQuery()) {
                while (result.next()) {
                    loadout.bind(result.getInt(1), result.getInt(2));
                }
            }
        }
        return loadout;
    }

    /** 整個覆蓋角色的技能與按鍵。 */
    public void saveSkills(final Connection connection, final long characterId, final SkillLoadout loadout) throws SQLException {
        for (final String table : List.of("character_skills", "character_skill_binds")) {
            try (PreparedStatement delete = connection.prepareStatement("DELETE FROM " + table + " WHERE character_id = ?")) {
                delete.setLong(1, characterId);
                delete.executeUpdate();
            }
        }
        try (PreparedStatement insert = connection.prepareStatement(
                "INSERT INTO character_skills (character_id, slot_index, skill_id, skill_level, supports) VALUES (?, ?, ?, ?, ?)")) {
            boolean any = false;
            for (int i = 0; i < SkillLoadout.MAX_SKILLS; i++) {
                final SkillLoadout.Entry entry = loadout.skill(i);
                if (entry == null) {
                    continue;
                }
                insert.setLong(1, characterId);
                insert.setInt(2, i);
                insert.setString(3, entry.skillId());
                insert.setInt(4, entry.level());
                insert.setString(5, entry.encodeSupports()); // 輔助孔數與掛的輔助寶石
                insert.addBatch();
                any = true;
            }
            if (any) {
                insert.executeBatch();
            }
        }
        try (PreparedStatement insert = connection.prepareStatement(
                "INSERT INTO character_skill_binds (character_id, bind_slot, slot_index) VALUES (?, ?, ?)")) {
            boolean any = false;
            for (int slot = 0; slot < SkillLoadout.BIND_SLOTS; slot++) {
                final int position = loadout.bound(slot);
                if (position < 0) {
                    continue;
                }
                insert.setLong(1, characterId);
                insert.setInt(2, slot);
                insert.setInt(3, position);
                insert.addBatch();
                any = true;
            }
            if (any) {
                insert.executeBatch();
            }
        }
    }

    // ---- 組合 ----

    /** 一次載入角色列、背包、配點、進度、技能。 */
    public Optional<Loaded> loadCharacter(final Connection connection, final long id) throws SQLException {
        final Optional<CharacterRecord> record = findCharacter(connection, id);
        if (record.isEmpty()) {
            return Optional.empty();
        }
        return Optional.of(new Loaded(record.get(), loadInventory(connection, id), loadPassives(connection, id),
                loadProgress(connection, id), loadSkills(connection, id)));
    }

    /** 一次存角色列、背包、配點（同一個交易）。 */
    public void saveCharacter(final Connection connection, final CharacterRecord record, final InventoryBlob inventory,
                              final Set<String> passives) throws SQLException {
        saveCharacter(connection, record, inventory, passives, null, null);
    }

    /** 同上，再加一段一起提交的額外工作（技能面板不動）。 */
    public void saveCharacter(final Connection connection, final CharacterRecord record, final InventoryBlob inventory,
                              final Set<String> passives, final ExtraWork extra) throws SQLException {
        saveCharacter(connection, record, inventory, passives, null, extra);
    }

    /**
     * 同上，再加技能面板（null = 不動）與一段一起提交的額外工作
     * （倉庫與背包一起存，物品才不會兩邊都有或兩邊都沒有）。
     */
    public void saveCharacter(final Connection connection, final CharacterRecord record, final InventoryBlob inventory,
                              final Set<String> passives, final SkillLoadout skills, final ExtraWork extra) throws SQLException {
        saveAll(connection, List.of(new SaveJob(record.account(), record, inventory, passives, skills, null, extra)), null);
    }

    /**
     * 一個交易裡存好幾個玩家（面對面交易的兩個人），先做 before（交易紀錄、市集的上架或成交），
     * 再依序寫每個人的存檔。全部成功才提交，任何一步失敗全部還原：物品不會一邊有一邊沒有。
     */
    public void saveAll(final Connection connection, final List<SaveJob> jobs, final ExtraWork before) throws SQLException {
        final boolean autoCommit = connection.getAutoCommit();
        connection.setAutoCommit(false);
        try {
            if (before != null) {
                before.run(connection);
            }
            for (final SaveJob job : jobs) {
                writeJob(connection, job);
            }
            connection.commit();
        } catch (final SQLException | RuntimeException e) {
            connection.rollback();
            throw e;
        } finally {
            connection.setAutoCommit(autoCommit);
        }
    }

    /** 寫一個玩家的存檔（不管交易，由呼叫端提交）。 */
    private void writeJob(final Connection connection, final SaveJob job) throws SQLException {
        final CharacterRecord record = job.record();
        updateCharacter(connection, record);
        if (job.inventory() != null) {
            saveInventory(connection, record.id(), job.inventory());
        }
        if (job.passives() != null) {
            savePassives(connection, record.id(), job.passives());
        }
        if (job.skills() != null) {
            saveSkills(connection, record.id(), job.skills());
        }
        if (job.gold() != null) {
            updateAccountGold(connection, job.account(), job.gold());
        }
        if (job.extra() != null) {
            job.extra().run(connection);
        }
    }

    // ---- 進度 ----

    /** 角色的所有進度（檢查點、Boss 首殺…）。 */
    public Map<String, String> loadProgress(final Connection connection, final long characterId) throws SQLException {
        final Map<String, String> map = new HashMap<>();
        try (PreparedStatement statement = connection.prepareStatement(
                "SELECT progress_key, progress_value FROM progress WHERE character_id = ?")) {
            statement.setLong(1, characterId);
            try (ResultSet result = statement.executeQuery()) {
                while (result.next()) {
                    map.put(result.getString(1), result.getString(2));
                }
            }
        }
        return map;
    }

    public Optional<String> progress(final Connection connection, final long characterId, final String key) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement(
                "SELECT progress_value FROM progress WHERE character_id = ? AND progress_key = ?")) {
            statement.setLong(1, characterId);
            statement.setString(2, key);
            try (ResultSet result = statement.executeQuery()) {
                return result.next() ? Optional.ofNullable(result.getString(1)) : Optional.empty();
            }
        }
    }

    public void setProgress(final Connection connection, final long characterId, final String key, final String value) throws SQLException {
        try (PreparedStatement update = connection.prepareStatement(
                "UPDATE progress SET progress_value = ? WHERE character_id = ? AND progress_key = ?")) {
            update.setString(1, value);
            update.setLong(2, characterId);
            update.setString(3, key);
            if (update.executeUpdate() > 0) {
                return;
            }
        }
        try (PreparedStatement insert = connection.prepareStatement(
                "INSERT INTO progress (character_id, progress_key, progress_value) VALUES (?, ?, ?)")) {
            insert.setLong(1, characterId);
            insert.setString(2, key);
            insert.setString(3, value);
            insert.executeUpdate();
        }
    }

    /** 刪掉一筆進度（管理指令重設星圖用）。 */
    public void deleteProgress(final Connection connection, final long characterId, final String key) throws SQLException {
        try (PreparedStatement delete = connection.prepareStatement(
                "DELETE FROM progress WHERE character_id = ? AND progress_key = ?")) {
            delete.setLong(1, characterId);
            delete.setString(2, key);
            delete.executeUpdate();
        }
    }

    // ---- 通貨紀錄 ----

    public void logCurrency(final Connection connection, final Long characterId, final String playerName, final String itemUuid,
                            final String currency, final String before, final String after) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement(
                "INSERT INTO currency_log (character_id, player_name, item_uuid, currency, before_json, after_json, created_at) "
                        + "VALUES (?, ?, ?, ?, ?, ?, ?)")) {
            if (characterId == null) {
                statement.setNull(1, java.sql.Types.BIGINT);
            } else {
                statement.setLong(1, characterId);
            }
            statement.setString(2, playerName);
            statement.setString(3, itemUuid);
            statement.setString(4, currency);
            statement.setString(5, before);
            statement.setString(6, after);
            statement.setTimestamp(7, now());
            statement.executeUpdate();
        }
    }

    private static Timestamp now() {
        return Timestamp.from(Instant.now());
    }
}
