package tw.exilecore.guild;

import org.junit.jupiter.api.Test;
import tw.exilecore.core.config.YamlNode;
import tw.exilecore.monster.MonsterRarity;
import tw.exilecore.stats.Modifier;

import java.io.IOException;
import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** data/guilds.yml：預設的資料檔讀得進來、數字合理，寫錯的會說哪裡錯。 */
class GuildConfigTest {

    private static GuildConfig shipped() throws IOException {
        final GuildConfig config = new GuildConfig();
        config.load(Path.of("src/main/resources/data"));
        return config;
    }

    @Test
    void shippedFileLoads() throws IOException {
        final GuildConfig config = shipped();
        assertEquals(30, config.createLevel());
        assertEquals(50_000, config.createCost());
        assertEquals(40, config.maxMembers());
        assertEquals(20, config.maxLevel());
        assertEquals(20_000, config.xpToNext(1));
        assertTrue(config.xpToNext(19) > config.xpToNext(10));
        assertEquals(0, config.xpToNext(20));
        assertEquals(1, config.killXp(MonsterRarity.NORMAL, false));
        assertEquals(50, config.killXp(MonsterRarity.UNIQUE, false));
        assertEquals(200, config.killXp(MonsterRarity.NORMAL, true), "Boss 不看稀有度");
        assertEquals(10, config.killLevelGap());
        assertEquals(500, config.donateXp(50_000));
        assertEquals(GuildRank.MEMBER, config.defaultWithdrawRank());
    }

    @Test
    void bonusesAccumulateWithLevel() throws IOException {
        final GuildConfig config = shipped();
        assertTrue(config.modifiers(1).isEmpty(), "一級沒有加成");
        assertFalse(config.modifiers(2).isEmpty());
        final List<Modifier> top = config.modifiers(config.maxLevel());
        assertTrue(top.size() > config.modifiers(2).size());
        // 同一種加成合成一條：稀有度 2 + 2 + 3 = 7%
        final double rarity = top.stream().filter(modifier -> modifier.stat().equals("drop.rarity")).mapToDouble(Modifier::value).sum();
        assertEquals(1, top.stream().filter(modifier -> modifier.stat().equals("drop.rarity")).count());
        assertEquals(0.07, rarity, 1e-9);
        for (final GuildConfig.Bonus bonus : config.bonuses()) {
            assertTrue(bonus.level() >= 2 && bonus.level() <= config.maxLevel());
        }
    }

    @Test
    void stashPagesGrowWithLevel() throws IOException {
        final GuildConfig config = shipped();
        assertEquals(1, config.stashPages(1));
        assertEquals(1, config.stashPages(4));
        assertEquals(2, config.stashPages(5));
        assertEquals(5, config.stashPages(20));
    }

    @Test
    void missingFileUsesDefaults() throws IOException {
        final GuildConfig config = new GuildConfig();
        config.load(Path.of("build/tmp/no-such-folder"));
        assertEquals(40, config.maxMembers());
        assertEquals(2, config.tagMin());
        assertEquals(4, config.tagMax());
    }

    @Test
    void mistakesAreReported() {
        assertThrows(IllegalArgumentException.class, () -> GuildConfig.of(YamlNode.load("test", new java.io.StringReader("guild:\n  max_members: 1\n"))));
        assertThrows(IllegalArgumentException.class, () -> GuildConfig.of(YamlNode.load("test", new java.io.StringReader("guild:\n  tag_length: [3, 2]\n"))));
        assertThrows(IllegalArgumentException.class, () -> GuildConfig.of(YamlNode.load("test", new java.io.StringReader("level:\n  xp_per_kill:\n    epic: 5\n"))));
        assertThrows(IllegalArgumentException.class, () -> GuildConfig.of(YamlNode.load("test", new java.io.StringReader(
                "bonuses:\n  - level: 99\n    stats:\n      - { stat: life.max, type: increased, value: 2 }\n"))));
        assertThrows(IllegalArgumentException.class, () -> GuildConfig.of(YamlNode.load("test", new java.io.StringReader(
                "bonuses:\n  - level: 2\n    stats:\n      - { stat: no.such.stat, type: increased, value: 2 }\n"))));
        assertThrows(IllegalArgumentException.class, () -> GuildConfig.of(YamlNode.load("test", new java.io.StringReader("stash:\n  withdraw_rank: king\n"))));
    }
}
