package tw.exilecore.character;

import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class CharacterConfigTest {

    static CharacterConfig config;

    @BeforeAll
    static void load() throws IOException {
        config = new CharacterConfig();
        config.load(Path.of("src/main/resources/data"));
    }

    @Test
    void experienceTableIsThePoe2Table() {
        assertEquals(100, config.levelCap());
        assertEquals(525, config.experienceToNext(1));
        long previous = 0;
        long total = 0;
        long to60 = 0;
        long to90 = 0;
        for (int level = 1; level < config.levelCap(); level++) {
            final long need = config.experienceToNext(level);
            assertEquals(CharacterConfig.DEFAULT_EXPERIENCE[level - 1], need, "出貨的 character.yml 跟內建的表一樣：等級 " + level);
            assertTrue(need > previous, "等級 " + level + " 需要的經驗要遞增");
            if (level > 1) {
                assertTrue(need >= previous * 1.07, "每一級至少多 7%：等級 " + level);
            }
            previous = need;
            total += need;
            if (level < 60) {
                to60 += need;
            }
            if (level < 90) {
                to90 += need;
            }
        }
        assertEquals(0, config.experienceToNext(100), "滿級不再需要經驗");
        // PoE2 的累計經驗：60 級 146,749,362、90 級 1,934,009,687、100 級 4,250,334,444
        assertEquals(146_749_362L, to60);
        assertEquals(1_934_009_687L, to90);
        assertEquals(4_250_334_444L, total);
        assertEquals(317_515_914L, config.experienceToNext(99), "99 → 100");
        assertEquals(99, config.pointsFromLevel(100));
        assertEquals(0, config.pointsFromLevel(1));
        // 沒有 character.yml 時用內建的表
        assertEquals(525, new CharacterConfig().experienceToNext(1));
        assertEquals(317_515_914L, new CharacterConfig().experienceToNext(99));
    }

    @Test
    void levelPenaltyFollowsPoeFormula() {
        // 安全範圍：3 + 等級 ÷ 16
        assertEquals(1.0, config.levelPenalty(10, 13), 1e-9);   // 差 3，在安全範圍內
        assertEquals(1.0, config.levelPenalty(40, 45), 1e-9);   // 40 級安全範圍 5
        final double diffOne = config.levelPenalty(10, 14);     // 超出 1 級
        final double diffFive = config.levelPenalty(10, 18);    // 超出 5 級
        assertTrue(diffOne < 1.0 && diffOne > 0.8, "超出 1 級 " + diffOne);
        assertTrue(diffFive < diffOne && diffFive < 0.3, "超出 5 級 " + diffFive);
        assertEquals(config.levelPenalty(10, 18), config.levelPenalty(10, 2), 1e-9, "上下對稱");
        assertTrue(config.levelPenalty(60, 1) >= 0.01, "最低 1%");
        assertEquals(1.0, config.levelPenalty(85, 84), 1e-9);
        assertEquals(1.0, config.levelPenalty(88, 84), 1e-9, "怪物算 80 級：差 8 級，88 級的安全範圍 8");
    }

    @Test
    void highLevelsAreAsHardAsPoe2() {
        assertEquals(80, config.monsterLevelCap(), "算等級差時怪物最高算 80 級（PoE2 的地圖上限）");
        // 90 級打 T16（84 級，算 80 級）：差 10 級，安全範圍 8，超出 2 級
        assertEquals(config.levelDifferenceFactor(90, 80), config.levelDifferenceFactor(90, 84), 1e-12);
        assertEquals(0.9169, config.levelPenalty(90, 84), 1e-4);
        assertEquals(0.9169, config.levelPenalty(90, 87), 1e-4, "終局（87 級）也算 80 級");
        // 95 級起另外乘 PoE 的高等級倍率
        assertEquals(1.0, config.highLevelMultiplier(94), 1e-12);
        assertEquals(0.854, config.highLevelMultiplier(95), 1e-12);
        assertEquals(0.476, config.highLevelMultiplier(99), 1e-12);
        assertEquals(0.476, config.highLevelMultiplier(100), 1e-12, "寫的那一級起都乘");
        assertEquals(0.2454, config.levelPenalty(95, 84), 1e-4);
        assertEquals(0.0586, config.levelPenalty(99, 84), 1e-4);
        // 高等級的減少跟等級差無關：95 級打 90 級以上的怪也一樣乘
        assertEquals(0.854, config.levelPenalty(95, 90) / config.levelDifferenceFactor(95, 90), 1e-9);
        // 練到 95 級以後，每一級要打的怪急速變多（95→96 是 90→91 的 5 倍以上）
        final double at90 = config.experienceToNext(90) / config.levelPenalty(90, 84);
        final double at95 = config.experienceToNext(95) / config.levelPenalty(95, 84);
        assertTrue(at95 / at90 > 5, "95 級 / 90 級：" + at95 / at90);
    }

    @Test
    void penaltyOptionsCanBeTurnedOff(@org.junit.jupiter.api.io.TempDir final Path dir) throws IOException {
        java.nio.file.Files.writeString(dir.resolve("character.yml"), """
                level_penalty:
                  monster_level_cap: 0
                  high_level: {}
                """);
        final CharacterConfig custom = new CharacterConfig();
        custom.load(dir);
        assertEquals(0, custom.monsterLevelCap());
        assertEquals(1.0, custom.highLevelMultiplier(99), 1e-12);
        // 99 級打 84 級，不夾 80 級：差 15 級，安全範圍 9，超出 6 級
        assertEquals(Math.pow(104 / (104 + Math.pow(6, 2.5)), 1.5), custom.levelPenalty(99, 84), 1e-9);
        assertEquals(525, custom.experienceToNext(1), "沒寫 experience 用內建的表");

        // 舊的 character.yml（沒有這兩個設定）：照原本的規則，不夾怪物等級、不乘高等級的倍率
        java.nio.file.Files.writeString(dir.resolve("character.yml"), """
                level_cap: 100
                level_penalty:
                  safe_base: 3
                """);
        final CharacterConfig old = new CharacterConfig();
        old.load(dir);
        assertEquals(0, old.monsterLevelCap());
        assertEquals(1.0, old.highLevelMultiplier(99), 1e-12);
        assertEquals(custom.levelPenalty(99, 84), old.levelPenalty(99, 84), 1e-12);

        java.nio.file.Files.writeString(dir.resolve("character.yml"), """
                level_penalty:
                  enabled: false
                """);
        final CharacterConfig off = new CharacterConfig();
        off.load(dir);
        assertEquals(1.0, off.levelPenalty(99, 1), 1e-12, "整個關掉：沒有等級差也沒有高等級的減少");

        java.nio.file.Files.writeString(dir.resolve("character.yml"), """
                level_penalty:
                  high_level: { 95: 1.5 }
                """);
        org.junit.jupiter.api.Assertions.assertThrows(IllegalArgumentException.class, () -> new CharacterConfig().load(dir),
                "倍率要在 0 到 1 之間");
    }

    @Test
    void serverWideExperienceBonus(@org.junit.jupiter.api.io.TempDir final Path dir) throws IOException {
        assertEquals(2.0, config.experienceBonus(), 1e-12, "出貨的 character.yml：全服經驗 ×2");
        assertEquals("×2", config.experienceBonusText());
        assertEquals("×1.5", CharacterConfig.bonusText(1.5));
        assertEquals("×1.25", CharacterConfig.bonusText(1.25));
        assertEquals("×3", CharacterConfig.bonusText(3.0));
        assertEquals(null, CharacterConfig.bonusText(1.0), "沒加倍不寫");
        // 舊的 character.yml（沒有這一行）：不加倍
        java.nio.file.Files.writeString(dir.resolve("character.yml"), "level_cap: 100\n");
        final CharacterConfig old = new CharacterConfig();
        old.load(dir);
        assertEquals(1.0, old.experienceBonus(), 1e-12);
        assertEquals(null, old.experienceBonusText());
        // /ec reload 拿掉這一行：回到不加倍
        final CharacterConfig reloaded = new CharacterConfig();
        java.nio.file.Files.writeString(dir.resolve("character.yml"), "experience_bonus: 3\n");
        reloaded.load(dir);
        assertEquals(3.0, reloaded.experienceBonus(), 1e-12);
        java.nio.file.Files.writeString(dir.resolve("character.yml"), "level_cap: 100\n");
        reloaded.load(dir);
        assertEquals(1.0, reloaded.experienceBonus(), 1e-12);
        for (final String bad : new String[] {"0", "0.05", "11", "-2", ".nan"}) {
            java.nio.file.Files.writeString(dir.resolve("character.yml"), "experience_bonus: " + bad + "\n");
            org.junit.jupiter.api.Assertions.assertThrows(IllegalArgumentException.class, () -> new CharacterConfig().load(dir), bad);
        }
    }

    @Test
    void deathPenaltyOnlyInMapsAndRespecCostsGold() {
        // PoE2 做法：劇情區域不扣經驗，地圖才扣
        assertEquals(0.0, config.deathPenalty(1), 1e-9);
        assertEquals(0.0, config.deathPenalty(60), 1e-9);
        assertEquals(0.10, config.mapDeathPenalty(), 1e-9);
        assertEquals(20, config.respecFreeUntilLevel());
        assertEquals(0, config.respecCost(1));
        assertEquals(0, config.respecCost(19));
        assertEquals(60, config.respecCost(20));
        assertEquals(230, config.respecCost(30));   // 20 級 60 → 40 級 400，線性內插
        assertEquals(400, config.respecCost(40));
        assertEquals(1500, config.respecCost(60));
        assertEquals(2150, config.respecCost(70));
        assertEquals(2800, config.respecCost(80));
        assertEquals(4500, config.respecCost(100));
    }

    @Test
    void classesHaveDistinctPrimaryAttributes() {
        assertEquals(32, CharacterClass.MARAUDER.strength());
        assertEquals(32, CharacterClass.RANGER.dexterity());
        assertEquals(32, CharacterClass.WITCH.intelligence());
        for (final CharacterClass value : CharacterClass.values()) {
            assertEquals(60, value.strength() + value.dexterity() + value.intelligence());
        }
        assertEquals(CharacterClass.WITCH, CharacterClass.parse("witch", null));
        assertEquals(CharacterClass.RANGER, CharacterClass.parse("弓箭手", null));
        assertEquals(CharacterClass.MARAUDER, CharacterClass.parse("nope", CharacterClass.MARAUDER));
    }
}
