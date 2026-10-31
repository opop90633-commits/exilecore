package tw.exilecore.core.db;

import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.slf4j.LoggerFactory;
import tw.exilecore.character.CharacterClass;
import tw.exilecore.character.CharacterRecord;
import tw.exilecore.character.CharacterRepository;
import tw.exilecore.character.SkillLoadout;

import java.sql.SQLException;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** 用記憶體 H2（MariaDB 模式）跑真正的遷移與 SQL。 */
class DatabaseTest {

    static Database database;
    static final CharacterRepository repository = new CharacterRepository();

    @BeforeAll
    static void connect() throws SQLException {
        database = new Database(LoggerFactory.getLogger("test"), java.nio.file.Path.of("build/tmp"));
        database.connect("jdbc:h2:mem:exilecore_test;MODE=MariaDB;DATABASE_TO_LOWER=TRUE;DB_CLOSE_DELAY=-1");
    }

    @AfterAll
    static void close() {
        database.close();
    }

    @Test
    void migrationsAreIdempotent() throws SQLException {
        assertTrue(database.isConnected());
        assertEquals(0, Migrations.apply(database, LoggerFactory.getLogger("test")), "第二次不該再套用");
        assertEquals(26, Migrations.currentVersion());
    }

    @Test
    void createCharacterIsOneTransaction() throws Exception {
        final UUID account = UUID.randomUUID();
        database.sync(c -> repository.loadOrCreateAccount(c, account, "Starter"));
        final CharacterRecord record = new CharacterRecord(0, account, 1, "新手", CharacterClass.RANGER);
        final byte[] contents = {7, 7, 7};
        database.sync(c -> {
            repository.createCharacter(c, record, new CharacterRepository.InventoryBlob(contents, null),
                    java.util.Map.of("starter.weapon", "bow", "starter.skill", "split_arrow"));
            return null;
        });
        assertTrue(record.id() > 0);
        final CharacterRepository.Loaded loaded = database.sync(c -> repository.loadCharacter(c, record.id())).orElseThrow();
        assertArrayEquals(contents, loaded.inventory().contents());
        assertEquals("bow", loaded.progress().get("starter.weapon"));
        assertEquals("split_arrow", loaded.progress().get("starter.skill"));
        assertEquals(record.id(), database.sync(c -> repository.loadOrCreateAccount(c, account, "Starter")).activeCharacter());

        // 中途失敗（進度的鍵太長）：整個還原，不留半個角色、使用中的角色不變
        final CharacterRecord broken = new CharacterRecord(0, account, 2, "壞掉", CharacterClass.WITCH);
        boolean failed = false;
        try {
            database.sync(c -> {
                repository.createCharacter(c, broken, new CharacterRepository.InventoryBlob(null, null),
                        java.util.Map.of("x".repeat(100), "1"));
                return null;
            });
        } catch (final Exception expected) {
            failed = true;
        }
        assertTrue(failed, "鍵超過 64 字要失敗");
        final List<CharacterRecord> roster = database.sync(c -> repository.listCharacters(c, account));
        assertEquals(1, roster.size(), "失敗的角色不能留下來");
        assertEquals(record.id(), database.sync(c -> repository.loadOrCreateAccount(c, account, "Starter")).activeCharacter());
    }

    @Test
    void accountAndCharacterRoundTrip() throws Exception {
        final UUID account = UUID.randomUUID();
        final CharacterRepository.Account created = database.sync(c -> repository.loadOrCreateAccount(c, account, "Tester"));
        assertNull(created.activeCharacter());
        assertEquals("Tester", created.name());

        final CharacterRecord record = new CharacterRecord(0, account, 1, "流放者", CharacterClass.WITCH);
        record.setLevel(7);
        record.setExperience(123);
        database.sync(c -> repository.insertCharacter(c, record));
        assertTrue(record.id() > 0, "要回填 id");

        // 背包與配點
        final byte[] contents = {1, 2, 3, 4, 5};
        final byte[] jewellery = {9, 8};
        record.setRefundPoints(2);
        record.setResources(55.5, 20, 0);
        database.sync(c -> {
            repository.saveCharacter(c, record, new CharacterRepository.InventoryBlob(contents, jewellery), Set.of("n0_m8", "n0_m7"));
            repository.setActiveCharacter(c, account, record.id());
            return null;
        });

        final CharacterRepository.Account reloaded = database.sync(c -> repository.loadOrCreateAccount(c, account, "Tester2"));
        assertEquals(record.id(), reloaded.activeCharacter());
        final Optional<CharacterRepository.Loaded> loaded = database.sync(c -> repository.loadCharacter(c, record.id()));
        assertTrue(loaded.isPresent());
        assertEquals("流放者", loaded.get().record().name());
        assertEquals(CharacterClass.WITCH, loaded.get().record().characterClass());
        assertEquals(7, loaded.get().record().level());
        assertEquals(123, loaded.get().record().experience());
        assertEquals(2, loaded.get().record().refundPoints());
        assertEquals(0L, reloaded.gold(), "金幣在帳號上，還沒存過是 0");
        assertEquals(55.5, loaded.get().record().life(), 1e-9);
        assertArrayEquals(contents, loaded.get().inventory().contents());
        assertArrayEquals(jewellery, loaded.get().inventory().jewellery());
        assertEquals(Set.of("n0_m8", "n0_m7"), loaded.get().passives());

        assertNull(loaded.get().inventory().bag(), "沒存過隨身背包");
        assertNull(loaded.get().record().lootFilter(), "沒設定過過濾器");

        // 隨身背包（物品＋通貨袋）與掉落過濾器（批次 3）
        record.setLootFilter("p=strict");
        database.sync(c -> {
            repository.saveCharacter(c, record, new CharacterRepository.InventoryBlob(contents, jewellery, new byte[] {4, 2},
                    "glimmer_orb=12,whetstone=40"), Set.of("n0_m8"));
            return null;
        });
        final CharacterRepository.Loaded withBag = database.sync(c -> repository.loadCharacter(c, record.id())).orElseThrow();
        assertArrayEquals(new byte[] {4, 2}, withBag.inventory().bag());
        assertEquals("glimmer_orb=12,whetstone=40", withBag.inventory().bagCurrency());
        assertEquals("p=strict", withBag.record().lootFilter());
        assertNull(withBag.inventory().jewels(), "沒存過天賦樹的珠寶");

        // 天賦樹的珠寶（遷移 11 的欄位）
        database.sync(c -> {
            repository.saveCharacter(c, record, new CharacterRepository.InventoryBlob(contents, jewellery, new byte[] {4, 2},
                    "glimmer_orb=12", new byte[] {7, 7, 7}), Set.of("n0_m8"));
            return null;
        });
        assertArrayEquals(new byte[] {7, 7, 7}, database.sync(c -> repository.loadCharacter(c, record.id())).orElseThrow().inventory().jewels());

        // 第二次存檔要覆蓋而不是新增
        record.setLootFilter(null);
        database.sync(c -> {
            repository.saveCharacter(c, record, new CharacterRepository.InventoryBlob(new byte[] {7}, null), Set.of());
            return null;
        });
        final CharacterRepository.Loaded again = database.sync(c -> repository.loadCharacter(c, record.id())).orElseThrow();
        assertArrayEquals(new byte[] {7}, again.inventory().contents());
        assertNull(again.inventory().jewellery());
        assertNull(again.inventory().bag());
        assertNull(again.record().lootFilter());
        assertTrue(again.passives().isEmpty());

        // 技能面板：跟著角色存檔一起存，整個覆蓋
        final SkillLoadout skills = new SkillLoadout();
        final int fireball = skills.put("blazing_star_shell", 5);
        final int arc = skills.put("chain_thunder", 3);
        // 輔助孔（鑿星石開到 4 孔）與掛的輔助寶石也要存
        skills.set(fireball, skills.skill(fireball).withSockets(4).withSupport(0, "pierce").withSupport(2, "chain"));
        skills.bind((0 * SkillLoadout.KEYS + SkillLoadout.KEY_RIGHT), fireball);
        skills.bind((1 * SkillLoadout.KEYS + SkillLoadout.KEY_SNEAK_LEFT), arc);
        database.sync(c -> {
            repository.saveCharacter(c, record, null, null, skills, null);
            return null;
        });
        final SkillLoadout loadedSkills = database.sync(c -> repository.loadCharacter(c, record.id())).orElseThrow().skills();
        assertEquals(2, loadedSkills.size());
        assertEquals(new SkillLoadout.Entry("blazing_star_shell", 5, 4, List.of("pierce", "", "chain")), loadedSkills.skill(fireball));
        assertEquals(SkillLoadout.BASE_SOCKETS, loadedSkills.skill(arc).sockets());
        assertEquals(List.of("pierce", "chain"), loadedSkills.skill(fireball).supportIds());
        assertEquals("chain_thunder", loadedSkills.boundSkill((1 * SkillLoadout.KEYS + SkillLoadout.KEY_SNEAK_LEFT)).skillId());
        assertEquals(-1, loadedSkills.bound((0 * SkillLoadout.KEYS + SkillLoadout.KEY_LEFT)));
        skills.remove(arc);
        database.sync(c -> {
            repository.saveCharacter(c, record, null, null, skills, null);
            return null;
        });
        final SkillLoadout afterRemove = database.sync(c -> repository.loadSkills(c, record.id()));
        assertEquals(1, afterRemove.size());
        assertEquals(-1, afterRemove.bound((1 * SkillLoadout.KEYS + SkillLoadout.KEY_SNEAK_LEFT)), "移除的技能的按鍵也要清掉");
        assertTrue(database.sync(c -> repository.loadSkills(c, 999_999)).isEmpty(), "沒存過的角色是空的面板");

        // 清單、軟刪除
        final CharacterRecord second = new CharacterRecord(0, account, 2, "第二個", CharacterClass.MARAUDER);
        database.sync(c -> repository.insertCharacter(c, second));
        List<CharacterRecord> roster = database.sync(c -> repository.listCharacters(c, account));
        assertEquals(2, roster.size());
        assertEquals(1, roster.get(0).slot());
        database.sync(c -> {
            repository.deleteCharacter(c, second.id());
            return null;
        });
        roster = database.sync(c -> repository.listCharacters(c, account));
        assertEquals(1, roster.size());
        assertFalse(database.sync(c -> repository.findCharacter(c, second.id())).isPresent());

        // 進度與通貨紀錄
        database.sync(c -> {
            repository.setProgress(c, record.id(), "boss.first_kill.ashen_king", "1");
            repository.setProgress(c, record.id(), "boss.first_kill.ashen_king", "2");
            repository.logCurrency(c, record.id(), "Tester", "item-uuid", "turmoil_orb", "{}", "{\"a\":1}");
            repository.logCurrency(c, null, "Tester", "item-uuid", "glimmer_orb", "{}", "{}");
            return null;
        });
        assertEquals(Optional.of("2"), database.sync(c -> repository.progress(c, record.id(), "boss.first_kill.ashen_king")));

        // 非同步版本
        final Long count = database.async(c -> {
            try (var statement = c.createStatement(); var result = statement.executeQuery("SELECT COUNT(*) FROM currency_log")) {
                result.next();
                return result.getLong(1);
            }
        }).get(5, TimeUnit.SECONDS);
        assertEquals(2L, count);
    }

    /** 金幣改成帳號共用（遷移 6）：沒刪除的角色的金幣加總到帳號，角色上的歸零。 */
    @Test
    void goldMovesToTheAccount() throws Exception {
        final UUID account = UUID.randomUUID();
        database.sync(c -> repository.loadOrCreateAccount(c, account, "Rich"));
        final CharacterRecord a = new CharacterRecord(0, account, 1, "金甲", CharacterClass.MARAUDER);
        final CharacterRecord b = new CharacterRecord(0, account, 2, "金乙", CharacterClass.RANGER);
        final CharacterRecord gone = new CharacterRecord(0, account, 3, "金丙", CharacterClass.WITCH);
        database.sync(c -> {
            repository.insertCharacter(c, a);
            repository.insertCharacter(c, b);
            repository.insertCharacter(c, gone);
            try (var statement = c.prepareStatement("UPDATE characters SET gold = ? WHERE id = ?")) {
                for (final long[] pair : new long[][] {{a.id(), 100}, {b.id(), 250}, {gone.id(), 999}}) {
                    statement.setLong(1, pair[1]);
                    statement.setLong(2, pair[0]);
                    statement.executeUpdate();
                }
            }
            repository.deleteCharacter(c, gone.id());
            try (var statement = c.createStatement()) {
                for (final String sql : Migrations.statements(6)) {
                    statement.executeUpdate(sql);
                }
            }
            return null;
        });
        assertEquals(350L, (long) database.sync(c -> repository.accountGold(c, account)), "100 + 250，刪掉的角色不算");
        final Long left = database.sync(c -> {
            try (var statement = c.prepareStatement("SELECT SUM(gold) FROM characters WHERE account_uuid = ?")) {
                statement.setString(1, account.toString());
                try (var result = statement.executeQuery()) {
                    result.next();
                    return result.getLong(1);
                }
            }
        });
        assertEquals(0L, (long) left, "角色上的金幣歸零");
    }

    /** 好幾個玩家的存檔（面對面交易的兩個人）在同一個交易：任何一段失敗全部不寫，成功全部寫。 */
    @Test
    void saveAllIsAllOrNothing() throws Exception {
        final UUID first = UUID.randomUUID();
        final UUID second = UUID.randomUUID();
        final CharacterRecord one = new CharacterRecord(0, first, 1, "交易甲", CharacterClass.MARAUDER);
        final CharacterRecord two = new CharacterRecord(0, second, 1, "交易乙", CharacterClass.RANGER);
        database.sync(c -> {
            repository.loadOrCreateAccount(c, first, "TraderA");
            repository.loadOrCreateAccount(c, second, "TraderB");
            repository.insertCharacter(c, one);
            repository.insertCharacter(c, two);
            return null;
        });
        one.setExperience(111);
        two.setExperience(222);
        final List<CharacterRepository.SaveJob> jobs = List.of(
                new CharacterRepository.SaveJob(first, one, new CharacterRepository.InventoryBlob(new byte[] {1}, null), Set.of(), null, 50L, null),
                new CharacterRepository.SaveJob(second, two, new CharacterRepository.InventoryBlob(new byte[] {2}, null), Set.of(), null, 60L, null));
        boolean failed = false;
        try {
            database.sync(c -> {
                repository.saveAll(c, jobs, connection -> {
                    throw new SQLException("模擬交易紀錄寫入失敗");
                });
                return null;
            });
        } catch (final SQLException expected) {
            failed = true;
        }
        assertTrue(failed);
        assertEquals(0, database.sync(c -> repository.findCharacter(c, one.id())).orElseThrow().experience(), "甲沒有寫進去");
        assertEquals(0, database.sync(c -> repository.findCharacter(c, two.id())).orElseThrow().experience(), "乙也沒有");
        assertEquals(0L, (long) database.sync(c -> repository.accountGold(c, first)));

        database.sync(c -> {
            repository.saveAll(c, jobs, null);
            return null;
        });
        assertEquals(111, database.sync(c -> repository.findCharacter(c, one.id())).orElseThrow().experience());
        assertEquals(222, database.sync(c -> repository.findCharacter(c, two.id())).orElseThrow().experience());
        assertEquals(50L, (long) database.sync(c -> repository.accountGold(c, first)));
        assertEquals(60L, (long) database.sync(c -> repository.accountGold(c, second)));
        assertArrayEquals(new byte[] {2}, database.sync(c -> repository.loadInventory(c, two.id())).contents());
    }
}
