package tw.exilecore.pvp;

import org.junit.jupiter.api.Test;
import tw.exilecore.core.config.YamlNode;

import java.io.IOException;
import java.io.StringReader;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** pvp.yml：出貨的值、夾範圍、沒有檔案時的預設。 */
class PvpConfigTest {

    static final Path DATA = Path.of("src/main/resources/data");

    @Test
    void shippedConfig() throws IOException {
        final PvpConfig config = new PvpConfig();
        config.load(DATA);
        assertTrue(config.problems().isEmpty(), config.problems().toString());
        assertTrue(config.enabled());
        assertEquals(85, config.reduction(), 1e-9, "專屬的 PvP 減傷 85%（2026-10：70 → 85）");
        assertEquals(0.15, config.multiplier(), 1e-9);
        assertEquals(30, config.hitCap(), 1e-9);
        assertEquals(25, config.secondCap(), 1e-9);
        assertEquals(0.5, config.minionDamage(), 1e-9);
        assertEquals(20, config.meleeReduction(), 1e-9);
        assertEquals(0, config.minLevel());
        assertEquals(5, config.flaskCharges());
        assertTrue(config.killFeed());
        assertTrue(config.respawnInArena());
        assertTrue(Files.readString(DATA.resolve("index.txt")).contains("data/pvp.yml"), "新資料檔要列在 index.txt 才會同步到伺服器");
    }

    @Test
    void reductionIsClamped() {
        final PvpConfig high = PvpConfig.parse(YamlNode.load("pvp.yml", new StringReader("damage_reduction: 120\n")));
        assertEquals(PvpConfig.MAX_REDUCTION, high.reduction(), 1e-9);
        assertEquals(0.05, high.multiplier(), 1e-9, "最少還是吃 5%");
        assertFalse(high.problems().isEmpty());
        final PvpConfig low = PvpConfig.parse(YamlNode.load("pvp.yml", new StringReader("damage_reduction: -10\nmin_level: -3\n")));
        assertEquals(0, low.reduction(), 1e-9);
        assertEquals(1, low.multiplier(), 1e-9);
        assertEquals(0, low.minLevel());
        final PvpConfig off = PvpConfig.parse(YamlNode.load("pvp.yml", new StringReader("enabled: false\nrespawn_in_arena: false\n")));
        assertFalse(off.enabled());
        assertFalse(off.respawnInArena());
        assertEquals(PvpConfig.DEFAULT_REDUCTION, off.reduction(), 1e-9);
    }

    @Test
    void missingFileUsesDefaults() throws IOException {
        final PvpConfig config = new PvpConfig();
        config.load(Files.createTempDirectory("pvp"));
        assertTrue(config.enabled());
        assertEquals(PvpConfig.DEFAULT_REDUCTION, config.reduction(), 1e-9);
        assertTrue(config.problems().isEmpty());
    }

    @Test
    void capsAreClamped() {
        final PvpConfig config = PvpConfig.parse(YamlNode.load("pvp.yml", new StringReader(
                "max_hit_percent: 150\nmax_per_second_percent: -5\nminion_damage: 3\nmelee_reduction: 99\n")));
        assertEquals(100, config.hitCap(), 1e-9);
        assertEquals(0, config.secondCap(), 1e-9, "0 = 不限");
        assertEquals(1, config.minionDamage(), 1e-9);
        assertEquals(90, config.meleeReduction(), 1e-9);
        assertEquals(4, config.problems().size(), config.problems().toString());
    }

    @Test
    void damageCapLimitsBurstAndSustain() {
        // 總血量 1000：單一下 300、每秒 250
        final PvpDamageCap cap = new PvpDamageCap();
        assertEquals(300, cap.allow(5000, 300, 250, 100, true), 1e-9, "這一秒的第一下：最多單一下的上限");
        assertEquals(0, cap.allow(5000, 300, 250, 105, true), 1e-9, "這一秒已經超過了");
        assertEquals(0, cap.allow(80, 300, 250, 110, false), 1e-9, "持續傷害也一樣");
        assertEquals(300, cap.allow(5000, 300, 250, 120, true), 1e-9, "過了一秒");
        // 連續的小傷害：一秒內加起來 250
        final PvpDamageCap stream = new PvpDamageCap();
        double total = 0;
        for (long tick = 0; tick < 20; tick++) {
            total += stream.allow(40, 300, 250, tick, true);
        }
        assertEquals(250, total, 1e-9);
        assertEquals(40, stream.allow(40, 300, 250, 20, true), 1e-9, "最舊的那一筆過期了，又有 40 的額度");
        // 沒超過就原樣
        final PvpDamageCap small = new PvpDamageCap();
        assertEquals(100, small.allow(100, 300, 250, 0, true), 1e-9);
        assertEquals(100, small.allow(100, 300, 250, 1, true), 1e-9);
        assertEquals(50, small.allow(100, 300, 250, 2, true), 1e-9, "剩下 50");
        // 都不限
        assertEquals(9999, new PvpDamageCap().allow(9999, 0, 0, 0, true), 1e-9);
        // 只有每秒的上限：第一下也不能超過
        assertEquals(250, new PvpDamageCap().allow(9999, 0, 250, 0, true), 1e-9);
        // 持續傷害不看單一下的上限，但看每秒的
        assertEquals(250, new PvpDamageCap().allow(9999, 300, 250, 0, false), 1e-9);
        assertTrue(new PvpDamageCap().idle(0));
        assertFalse(cap.idle(125));
        assertTrue(cap.idle(140));
    }

    @Test
    void meleeWeaponsGetTheBonusButSceptresDoNot() {
        assertTrue(PvpService.meleeWeapon(java.util.Set.of("weapon", "two_hand", "mace", "melee", "melee_2h")));
        assertTrue(PvpService.meleeWeapon(java.util.Set.of("weapon", "one_hand", "spear", "melee")));
        assertFalse(PvpService.meleeWeapon(java.util.Set.of("weapon", "one_hand", "mace", "sceptre", "melee")));
        assertFalse(PvpService.meleeWeapon(java.util.Set.of("weapon", "two_hand", "bow", "ranged")));
        assertFalse(PvpService.meleeWeapon(java.util.Set.of()));
    }
}
