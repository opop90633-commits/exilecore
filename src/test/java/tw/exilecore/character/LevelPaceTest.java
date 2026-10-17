package tw.exilecore.character;

import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import tw.exilecore.monster.MonsterConfig;
import tw.exilecore.monster.MonsterRarity;

import java.io.IOException;
import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** 練等的速度：出貨的 character.yml 與 monsters.yml 搭得上；其中一個是舊版時啟動會提醒；地圖裝置寫的經驗剩多少。 */
class LevelPaceTest {

    static final Path DATA = Path.of("src/main/resources/data");
    static CharacterConfig character;
    static MonsterConfig monsters;

    @BeforeAll
    static void load() throws IOException {
        character = new CharacterConfig();
        character.load(DATA);
        monsters = new MonsterConfig();
        monsters.load(DATA);
    }

    @Test
    void shippedFilesMatch() {
        assertEquals(List.of(), LevelPace.problems(character, monsters::experienceAt));
        // 每一級要殺的同等級普通怪：1 級 13 隻、10 級 70 隻、60 級約 870 隻、80 級約 2,900 隻
        assertEquals(13, Math.round(LevelPace.sameLevelKills(character, monsters::experienceAt, 1)));
        assertEquals(70, Math.round(LevelPace.sameLevelKills(character, monsters::experienceAt, 10)));
        assertEquals(874, Math.round(LevelPace.sameLevelKills(character, monsters::experienceAt, 60)));
        assertEquals(2916, Math.round(LevelPace.sameLevelKills(character, monsters::experienceAt, 80)));
        assertEquals(0, LevelPace.sameLevelKills(character, monsters::experienceAt, character.levelCap()), "滿級");
        assertEquals("60 級升一級要殺 874 隻同等級的普通怪（全服經驗 +100%：實際 437 隻）", LevelPace.summary(character, monsters::experienceAt),
                "出貨的 character.yml 有全服經驗 ×2；上面的檢查不算加倍（看的是兩個檔案搭不搭）");
        // 高等級在 T16（84 級）打，加上等級差與高等級的減少：90 級約 6,600 隻、99 級約 20 萬隻
        assertEquals(6622, Math.round(character.experienceToNext(90) / (monsters.experienceAt(84) * character.levelPenalty(90, 84))));
        final double at99 = character.experienceToNext(99) / (monsters.experienceAt(84) * character.levelPenalty(99, 84));
        assertTrue(at99 > 190_000 && at99 < 220_000, "99 級：" + at99);
        // 魔法怪 3 隻、稀有怪 12 隻
        assertEquals(3.0, monsters.rarity(MonsterRarity.MAGIC).experienceMultiplier(), 1e-9);
    }

    @Test
    void mismatchedFilesAreReported() {
        // 新的經驗表 + 舊的怪物經驗（60 級 622）：要殺 2 萬多隻
        final List<String> slow = LevelPace.problems(character, level -> 622);
        assertEquals(1, slow.size());
        assertTrue(slow.getFirst().contains("monsters.yml.new"), slow.getFirst());
        // 舊的經驗表（60 級 71,500）+ 新的怪物經驗：4 隻多就升級
        final List<String> fast = LevelPace.problems(character, level -> 14_441_758 / 4.3);
        assertEquals(1, fast.size());
        assertTrue(fast.getFirst().contains("character.yml.new"), fast.getFirst());
        // 怪物不給經驗
        assertTrue(LevelPace.problems(character, level -> 0).getFirst().contains("不給經驗"));
        // 舊版兩個檔案一起（60 級約 115 隻）不算錯
        assertEquals(List.of(), LevelPace.problems(character, level -> 14_441_758 / 115.0));
    }

    @Test
    void penaltyNoteExplainsWhy() {
        assertNull(LevelPace.penaltyNote(character, 70, 70), "沒有減少就不寫");
        assertNull(LevelPace.penaltyNote(character, 88, 84));
        assertEquals("經驗 92%（比怪物高 10 級，怪物最高算 80 級）", LevelPace.penaltyNote(character, 90, 84));
        assertEquals("經驗 25%（比怪物高 15 級，怪物最高算 80 級；高等級的經驗減少）", LevelPace.penaltyNote(character, 95, 84));
        assertEquals("經驗 5.9%（比怪物高 19 級，怪物最高算 80 級；高等級的經驗減少）", LevelPace.penaltyNote(character, 99, 84));
        assertTrue(LevelPace.penaltyNote(character, 30, 61).startsWith("經驗 "), "等級太低進高階地圖也會寫");
        assertTrue(LevelPace.penaltyNote(character, 30, 61).contains("比怪物低 31 級"), LevelPace.penaltyNote(character, 30, 61));
        assertEquals("10%", LevelPace.percent(0.10));
        assertEquals("5.9%", LevelPace.percent(0.0586));
    }
}
