package tw.exilecore.character;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.util.ArrayList;
import java.util.List;

/**
 * 2026-10-05 經驗表換成 PoE2 的表時，舊角色的等級換算（資料庫遷移 12，伺服器啟動時跑一次）。
 * <p>
 * 照「殺怪的量」換算：舊系統每一級要殺幾隻普通怪（舊的升級經驗 ÷ 舊的怪物經驗，含舊的等級差懲罰），
 * 把角色以前殺的量加起來，看在新系統（PoE2 的經驗表、PoE 的怪物經驗 × 2、怪物最高算 80 級、95 級以上的倍率）
 * 能升到幾級、這一級的進度多少。58 級以前假設打同等級的怪，58 級以後在地圖裡挑經驗最多的那一階。
 * 例：100 級 → 65 級、90 級 → 51 級、60 級 → 34 級。
 * <p>
 * 這是一次性的換算：舊的數字與新的怪物經驗、懲罰照那天的設定寫在這裡；新的升級經驗用 {@link CharacterConfig#DEFAULT_EXPERIENCE}
 * （PoE2 的表）。之後改 character.yml、monsters.yml 不影響這次換算。
 * 降級的角色天賦全部退回（點數照新等級重算；昇華天賦不動），進度記一筆 {@link #PROGRESS_KEY}，登入時說明。
 * <p>
 * 同一天開了全服經驗加倍（character.yml 的 experience_bonus: 2），換算也改成照加倍後的速度：資料庫遷移 13（{@link #boost}、
 * {@link #migrateBonus}）把遷移 12 換算過的角色補上去（100 級 → 78 級、90 → 66、60 → 46），不會比換算前的等級與進度高。
 * 遷移 12 照原本的樣子留著（已經跑過的伺服器跟新的伺服器結果一樣：先換、再補）。
 */
public final class LevelConversion {

    /** 換算過、等級有變的角色記「舊等級>新等級」，下次登入說明之後刪掉。 */
    public static final String PROGRESS_KEY = "level.converted";
    /** 遷移 13 補了等級、而且已經看過換算說明的角色記「補之前>補之後」，下次登入說明之後刪掉。 */
    public static final String BOOST_KEY = "level.boosted";

    /** 換算前（2026-10-04 以前）的升級經驗：從 1 級起，升到下一級要的經驗。 */
    static final long[] OLD_EXPERIENCE = {
            350, 460, 590, 740, 910, 1100, 1300, 1550, 1800, 2100,
            2400, 2700, 3100, 3450, 3900, 4350, 4850, 5350, 5900, 6500,
            7100, 7800, 8500, 9200, 10000, 10800, 11700, 12600, 13600, 14600,
            15600, 16700, 17900, 19100, 20300, 21600, 23000, 24400, 25800, 27400,
            29000, 30600, 32300, 34100, 35900, 37800, 39700, 41800, 43800, 46000,
            48200, 50500, 52900, 55300, 57800, 60400, 63100, 65800, 68600, 71500,
            77000, 82800, 89000, 95500, 102000, 110000, 118000, 126000, 135000, 144000,
            155000, 166000, 178000, 190000, 203000, 217000, 232000, 248000, 265000, 282000,
            301000, 322000, 343000, 373000, 397000, 423000, 451000, 480000, 511000, 549000,
            591000, 635000, 683000, 734000, 789000, 848000, 912000, 980000, 1050000
    };

    /** 換算前的怪物經驗（monsters.yml 的錨點，中間線性內插）。 */
    private static final int[] OLD_MONSTER_LEVELS = {1, 10, 20, 30, 40, 50, 60, 70, 80, 90, 100};
    private static final double[] OLD_MONSTER_XP = {13, 52, 118, 208, 322, 460, 622, 808, 1018, 1240, 1480};

    /** 換算後的怪物經驗：PoE 的怪物經驗表（1–91 級，之後不變），再乘 experience_multiplier 2。 */
    private static final int[] NEW_MONSTER_XP = {
            20, 20, 25, 38, 51, 67, 88, 113, 141, 173, 211, 250, 297, 347, 402,
            461, 526, 596, 668, 748, 831, 921, 1013, 1113, 1216, 1325, 1437, 1560, 1685, 1812,
            1950, 2089, 2235, 2386, 2543, 2705, 2873, 3045, 3227, 3407, 3597, 3796, 3993, 4203, 4415,
            4634, 4854, 5086, 5318, 5561, 5808, 6064, 6324, 6585, 6859, 7134, 7413, 7703, 7996, 8265,
            8525, 8781, 9032, 9283, 9532, 9773, 10014, 10255, 10487, 10720, 10949, 11170, 11388, 11601, 11804,
            12004, 12192, 12377, 12549, 12712, 12863, 13006, 13132, 13250, 13352, 13440, 13512, 13569, 13609, 13633,
            13639
    };
    private static final double NEW_MULTIPLIER = 2;
    /** 全服經驗加倍（遷移 13）：新系統的怪物經驗再乘這個（character.yml 的 experience_bonus，2026-10-05 起 2）。 */
    private static final double BONUS = 2;
    /** 換算後的高等級倍率（character.yml 的 level_penalty.high_level）。 */
    private static final double[] NEW_HIGH_LEVEL = {0.854, 0.748, 0.648, 0.562, 0.476}; // 95–99 級
    private static final int NEW_MONSTER_CAP = 80;
    /** 地圖每一階的怪物等級（maps.yml 的 tiers）：58 級以後在地圖裡挑經驗最多的那一階。 */
    private static final int[] MAP_TIERS = {61, 62, 63, 64, 65, 66, 68, 70, 71, 73, 75, 77, 78, 80, 82, 84};
    private static final int MAPS_FROM = 58;
    private static final int CAP = 100;

    /** 每一級要殺的同等級普通怪（舊、新、全服加倍之後）；索引 0 = 1 級升 2 級。 */
    private static final double[] OLD_KILLS = new double[CAP - 1];
    private static final double[] NEW_KILLS = new double[CAP - 1];
    private static final double[] BOOSTED_KILLS = new double[CAP - 1];

    static {
        for (int level = 1; level < CAP; level++) {
            OLD_KILLS[level - 1] = OLD_EXPERIENCE[level - 1] / bestXp(level, false);
            NEW_KILLS[level - 1] = CharacterConfig.DEFAULT_EXPERIENCE[level - 1] / bestXp(level, true);
            BOOSTED_KILLS[level - 1] = NEW_KILLS[level - 1] / BONUS;
        }
    }

    /** 換算結果：新的等級與這一級已經有的經驗（滿級 0）。 */
    public record Result(int level, long experience) {
    }

    private LevelConversion() {
    }

    /**
     * 照殺怪的量換算一個舊角色。經驗比舊表那一級要的還多（不可能是舊的數字：已經是新的表了）時不換，原樣回傳。
     *
     * @param level      舊的等級（1–100）
     * @param experience 舊的、這一級已經有的經驗
     */
    public static Result convert(final int level, final long experience) {
        final int old = Math.max(1, Math.min(CAP, level));
        final long exp = Math.max(0, experience);
        if (old < CAP && exp >= OLD_EXPERIENCE[old - 1]) {
            return new Result(level, experience);
        }
        double kills = 0;
        for (int l = 1; l < old; l++) {
            kills += OLD_KILLS[l - 1];
        }
        if (old < CAP) {
            kills += OLD_KILLS[old - 1] * exp / (double) OLD_EXPERIENCE[old - 1];
        }
        int converted = 1;
        while (converted < CAP && kills >= NEW_KILLS[converted - 1]) {
            kills -= NEW_KILLS[converted - 1];
            converted++;
        }
        if (converted >= CAP) {
            return new Result(CAP, 0);
        }
        final long need = CharacterConfig.DEFAULT_EXPERIENCE[converted - 1];
        final long newExperience = Math.min(need - 1, Math.max(0, (long) Math.floor(kills / NEW_KILLS[converted - 1] * need)));
        return new Result(converted, newExperience);
    }

    /**
     * 全服經驗加倍之後補等級（資料庫遷移 13）：遷移 12 換算過的角色現在的等級與經驗（新表），換回殺怪的量（遷移 12 的速度），
     * 再照加倍後的速度換成等級。不會比換算前（舊系統同樣的殺怪量）的等級與進度高，也不會比現在低；不用補的原樣回傳。
     * 只能對遷移 12 的結果用一次（輸入當作是遷移 12 的速度）：遷移 13 只跑一次。
     */
    public static Result boost(final int level, final long experience) {
        final int current = Math.max(1, Math.min(CAP, level));
        if (current >= CAP) {
            return new Result(level, experience);
        }
        final long need = CharacterConfig.DEFAULT_EXPERIENCE[current - 1];
        final long exp = Math.max(0, Math.min(need - 1, experience));
        double kills = 0;
        for (int l = 1; l < current; l++) {
            kills += NEW_KILLS[l - 1];
        }
        kills += NEW_KILLS[current - 1] * exp / (double) need;
        final double now = current + exp / (double) need;
        // 換算前的位置用多 1 點經驗的殺怪量算：遷移 12 的經驗是無條件捨去的，不然整級（經驗 0）的角色會補到差幾點經驗的上一級
        final double before = position(kills + NEW_KILLS[current - 1] / need, OLD_KILLS);
        final double target = Math.min(position(kills, BOOSTED_KILLS), before);
        if (target <= now) {
            return new Result(level, experience);
        }
        final Result boosted = toResult(target);
        return boosted.level() < current || (boosted.level() == current && boosted.experience() <= exp)
                ? new Result(level, experience) : boosted;
    }

    /** 殺怪的量 → 等級 + 這一級的進度（小數；滿級 = 100）。 */
    private static double position(final double totalKills, final double[] table) {
        double kills = totalKills;
        int level = 1;
        while (level < CAP && kills >= table[level - 1]) {
            kills -= table[level - 1];
            level++;
        }
        return level >= CAP ? CAP : level + kills / table[level - 1];
    }

    /** 等級 + 進度（小數）→ 新表的等級與經驗。 */
    private static Result toResult(final double position) {
        final int level = (int) Math.floor(position);
        if (level >= CAP) {
            return new Result(CAP, 0);
        }
        final long need = CharacterConfig.DEFAULT_EXPERIENCE[level - 1];
        return new Result(level, Math.min(need - 1, Math.max(0, (long) Math.floor((position - level) * need))));
    }

    /** 舊系統從 level 升到下一級要殺幾隻普通怪（說明與測試用）。 */
    public static double oldKills(final int level) {
        return level >= 1 && level < CAP ? OLD_KILLS[level - 1] : 0;
    }

    /** 新系統從 level 升到下一級要殺幾隻普通怪（說明與測試用）。 */
    public static double newKills(final int level) {
        return level >= 1 && level < CAP ? NEW_KILLS[level - 1] : 0;
    }

    // ---- 每一級打得到的最好的怪 ----

    /** 這個等級的角色殺一隻普通怪最多拿多少經驗：58 級以前打同等級的，之後在地圖裡挑最好的一階。 */
    private static double bestXp(final int level, final boolean converted) {
        if (level < MAPS_FROM) {
            return xp(level, level, converted);
        }
        double best = 0;
        for (final int tier : MAP_TIERS) {
            best = Math.max(best, xp(level, tier, converted));
        }
        return best;
    }

    private static double xp(final int playerLevel, final int monsterLevel, final boolean converted) {
        if (!converted) {
            return oldMonsterXp(monsterLevel) * penalty(playerLevel, monsterLevel);
        }
        final int index = Math.min(monsterLevel, NEW_MONSTER_XP.length) - 1;
        final double high = playerLevel >= 95 ? NEW_HIGH_LEVEL[Math.min(4, playerLevel - 95)] : 1;
        return NEW_MONSTER_XP[index] * NEW_MULTIPLIER * Math.max(0.01, penalty(playerLevel, Math.min(monsterLevel, NEW_MONSTER_CAP)) * high);
    }

    private static double oldMonsterXp(final int level) {
        for (int i = 1; i < OLD_MONSTER_LEVELS.length; i++) {
            if (level <= OLD_MONSTER_LEVELS[i]) {
                final double t = (level - OLD_MONSTER_LEVELS[i - 1]) / (double) (OLD_MONSTER_LEVELS[i] - OLD_MONSTER_LEVELS[i - 1]);
                return OLD_MONSTER_XP[i - 1] + (OLD_MONSTER_XP[i] - OLD_MONSTER_XP[i - 1]) * t;
            }
        }
        return OLD_MONSTER_XP[OLD_MONSTER_XP.length - 1];
    }

    /** 等級差懲罰（兩邊一樣的公式；安全範圍 3 + 等級 ÷ 16）。 */
    private static double penalty(final int playerLevel, final int monsterLevel) {
        final int safe = 3 + playerLevel / 16;
        final int diff = Math.max(0, Math.abs(playerLevel - monsterLevel) - safe);
        if (diff == 0) {
            return 1;
        }
        final double base = playerLevel + 5.0;
        return Math.max(0.01, Math.pow(base / (base + Math.pow(diff, 2.5)), 1.5));
    }

    // ---- 資料庫遷移 ----

    /**
     * 資料庫遷移 12：每個角色照殺怪的量換算等級與經驗；降級的天賦配點全部刪掉（珠寶孔裡的珠寶登入時自動還給玩家），
     * 記一筆進度讓玩家登入時看到說明。在 {@code Migrations} 的交易裡跑，失敗整個回滾。
     *
     * @return 給主控台的摘要
     */
    public static String migrate(final Connection connection) throws SQLException {
        final List<long[]> rows = new ArrayList<>();
        try (PreparedStatement select = connection.prepareStatement("SELECT id, char_level, experience, deleted_at FROM characters");
             ResultSet result = select.executeQuery()) {
            while (result.next()) {
                final boolean deleted = result.getTimestamp(4) != null;
                rows.add(new long[] {result.getLong(1), result.getInt(2), result.getLong(3), deleted ? 1 : 0});
            }
        }
        int active = 0;
        int changed = 0;
        int lowered = 0;
        try (PreparedStatement update = connection.prepareStatement("UPDATE characters SET char_level = ?, experience = ? WHERE id = ?");
             PreparedStatement clear = connection.prepareStatement("DELETE FROM passive_allocations WHERE character_id = ?");
             PreparedStatement forget = connection.prepareStatement("DELETE FROM progress WHERE character_id = ? AND progress_key = ?");
             PreparedStatement note = connection.prepareStatement(
                     "INSERT INTO progress (character_id, progress_key, progress_value) VALUES (?, ?, ?)")) {
            for (final long[] row : rows) {
                // 刪掉的角色也換（資料一致），但不算進摘要、也不留登入說明
                final boolean deleted = row[3] != 0;
                if (!deleted) {
                    active++;
                }
                final int level = (int) row[1];
                final Result result = convert(level, row[2]);
                if (result.level() == level && result.experience() == row[2]) {
                    continue;
                }
                update.setInt(1, result.level());
                update.setLong(2, result.experience());
                update.setLong(3, row[0]);
                update.executeUpdate();
                if (!deleted) {
                    changed++;
                }
                if (result.level() < level) {
                    if (!deleted) {
                        lowered++;
                    }
                    clear.setLong(1, row[0]);
                    clear.executeUpdate();
                }
                if (result.level() != level && !deleted) {
                    forget.setLong(1, row[0]);
                    forget.setString(2, PROGRESS_KEY);
                    forget.executeUpdate();
                    note.setLong(1, row[0]);
                    note.setString(2, PROGRESS_KEY);
                    note.setString(3, level + ">" + result.level());
                    note.executeUpdate();
                }
            }
        }
        return active + " 個角色（不含刪掉的），" + changed + " 個換算了等級或經驗，" + lowered + " 個降級（天賦全部退回）";
    }

    /**
     * 資料庫遷移 13：全服經驗加倍，遷移 12 換算過的角色（遷移 12 之前就有的角色）照加倍後的速度補等級（{@link #boost}）。
     * 還沒登入看過換算說明的，說明直接改成補完的等級（100>65 → 100>78）；已經看過的另外記一筆 {@link #BOOST_KEY}（65>78）。
     * 刪掉的角色也一起補，不算進摘要、不留說明。不動天賦（等級只會變高：多的點數自己配）。
     *
     * @return 給主控台的摘要
     */
    public static String migrateBonus(final Connection connection) throws SQLException {
        Timestamp cutoff = null;
        try (PreparedStatement select = connection.prepareStatement("SELECT applied_at FROM schema_version WHERE version = 12");
             ResultSet result = select.executeQuery()) {
            if (result.next()) {
                cutoff = result.getTimestamp(1);
            }
        }
        if (cutoff == null) {
            return "沒有換算過的角色（資料庫遷移 12 沒有紀錄）";
        }
        return migrateBonus(connection, cutoff);
    }

    /** 同上，cutoff 之前建立的角色才補（單元測試直接給時間）。 */
    static String migrateBonus(final Connection connection, final Timestamp cutoff) throws SQLException {
        final List<long[]> rows = new ArrayList<>();
        try (PreparedStatement select = connection.prepareStatement(
                "SELECT id, char_level, experience, deleted_at FROM characters WHERE created_at < ?")) {
            select.setTimestamp(1, cutoff);
            try (ResultSet result = select.executeQuery()) {
                while (result.next()) {
                    final boolean deleted = result.getTimestamp(4) != null;
                    rows.add(new long[] {result.getLong(1), result.getInt(2), result.getLong(3), deleted ? 1 : 0});
                }
            }
        }
        int changed = 0;
        int raised = 0;
        try (PreparedStatement update = connection.prepareStatement("UPDATE characters SET char_level = ?, experience = ? WHERE id = ?");
             PreparedStatement read = connection.prepareStatement(
                     "SELECT progress_value FROM progress WHERE character_id = ? AND progress_key = ?");
             PreparedStatement forget = connection.prepareStatement("DELETE FROM progress WHERE character_id = ? AND progress_key = ?");
             PreparedStatement note = connection.prepareStatement(
                     "INSERT INTO progress (character_id, progress_key, progress_value) VALUES (?, ?, ?)")) {
            for (final long[] row : rows) {
                final int level = (int) row[1];
                final Result result = boost(level, row[2]);
                if (result.level() == level && result.experience() == row[2]) {
                    continue;
                }
                update.setInt(1, result.level());
                update.setLong(2, result.experience());
                update.setLong(3, row[0]);
                update.executeUpdate();
                final boolean deleted = row[3] != 0;
                if (deleted) {
                    continue;
                }
                changed++;
                if (result.level() == level) {
                    continue; // 只多了這一級的進度：不用說明
                }
                raised++;
                // 還沒看過換算說明：說明改成補完的等級（舊等級>補完）；看過了：另外記一筆（補之前>補之後）
                read.setLong(1, row[0]);
                read.setString(2, PROGRESS_KEY);
                int[] pending = null;
                try (ResultSet existing = read.executeQuery()) {
                    if (existing.next()) {
                        pending = parseNote(existing.getString(1));
                    }
                }
                final String key = pending != null ? PROGRESS_KEY : BOOST_KEY;
                final String value = pending != null ? pending[0] + ">" + result.level() : level + ">" + result.level();
                forget.setLong(1, row[0]);
                forget.setString(2, key);
                forget.executeUpdate();
                note.setLong(1, row[0]);
                note.setString(2, key);
                note.setString(3, value);
                note.executeUpdate();
            }
        }
        return changed + " 個角色照全服經驗 ×2 的速度補了等級或經驗（不含刪掉的），其中 " + raised + " 個等級變高";
    }

    /** 進度的值「100>65」→ {100, 65}；寫壞回傳 null。 */
    public static int[] parseNote(final String value) {
        if (value == null) {
            return null;
        }
        final String[] parts = value.split(">");
        if (parts.length != 2) {
            return null;
        }
        try {
            return new int[] {Integer.parseInt(parts[0].trim()), Integer.parseInt(parts[1].trim())};
        } catch (final NumberFormatException e) {
            return null;
        }
    }
}
