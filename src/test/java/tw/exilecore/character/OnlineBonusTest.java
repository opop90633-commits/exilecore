package tw.exilecore.character;

import org.bukkit.configuration.file.YamlConfiguration;
import org.junit.jupiter.api.Test;

import java.io.File;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

/** 在線人數加成（2026-10）：config.yml 的門檻照人數挑對一階，數值照「總共」寫。 */
class OnlineBonusTest {

    @Test
    void tiersFromDefaultConfig() {
        final YamlConfiguration config = YamlConfiguration.loadConfiguration(new File("src/main/resources/config.yml"));
        final List<OnlineBonus.Tier> tiers = OnlineBonus.parse(config.getMapList("online_bonus.tiers"));
        // 2026-10-21：每 10 人一階，四種各 +2%（10 人 2%……100 人 20%、120 人 24%）
        assertEquals(12, tiers.size());
        assertNull(OnlineBonus.tierFor(tiers, 9));
        assertEquals(2, OnlineBonus.tierFor(tiers, 10).experience());
        assertEquals(2, OnlineBonus.tierFor(tiers, 19).quantity());
        assertEquals(4, OnlineBonus.tierFor(tiers, 29).quantity());
        assertEquals(6, OnlineBonus.tierFor(tiers, 30).rarity());
        final OnlineBonus.Tier fifty = OnlineBonus.tierFor(tiers, 55);
        assertEquals(10, fifty.experience());
        assertEquals(10, fifty.gold());
        final OnlineBonus.Tier hundred = OnlineBonus.tierFor(tiers, 100);
        assertEquals(20, hundred.experience());
        assertEquals(20, hundred.quantity());
        assertEquals(20, hundred.rarity());
        assertEquals(20, hundred.gold());
        final OnlineBonus.Tier full = OnlineBonus.tierFor(tiers, 140);
        assertEquals(24, full.experience());
        assertEquals(24, full.gold());
        assertEquals("經驗 +10%、掉落數量 +10%、稀有度 +10%、金幣 +10%", OnlineBonus.describe(fifty));
    }
}
