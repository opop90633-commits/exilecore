package tw.exilecore.training;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import tw.exilecore.core.config.YamlNode;

import java.io.IOException;
import java.io.StringReader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** 營地的訓練假人：設定檔與每秒傷害的算法。 */
class TrainingLogicTest {

    static final Path DATA = Path.of("src/main/resources/data");

    @Test
    void shippedFileMatchesTheDefaults() throws IOException {
        final DummyConfig config = new DummyConfig();
        config.load(DATA);
        assertEquals(DummyConfig.defaults(), config.dummies(), "dummies.yml 跟程式裡的預設一樣（沒有檔案時用預設）");
        assertTrue(config.dummies().size() >= 5, "至少五隻");
        assertEquals(6, config.arenaMargin(), 1e-9);
        assertEquals(4, config.idleSeconds());
        final Set<String> spots = new HashSet<>();
        for (final DummyConfig.Dummy dummy : config.dummies()) {
            assertTrue(spots.add(dummy.offsetX() + "," + dummy.offsetZ()), dummy.id() + "：跟別隻站在同一格");
            for (final DummyConfig.Dummy other : config.dummies()) {
                if (other != dummy) {
                    final double dx = other.offsetX() - dummy.offsetX();
                    final double dz = other.offsetZ() - dummy.offsetZ();
                    assertTrue(dx * dx + dz * dz >= 25, dummy.id() + " 跟 " + other.id() + " 太近（範圍技能會一起打到）");
                }
            }
        }
        final DummyConfig.Dummy boss = config.dummy("boss");
        assertEquals(DummyConfig.LEVEL_MAP, boss.level());
        assertTrue(boss.boss());
        assertEquals(40, config.dummy("resistant").fire(), 1e-9);
        assertEquals(DummyConfig.LEVEL_PLAYER, config.dummy("plain").level());
        assertEquals(0, config.dummy("plain").armour(), 1e-9);
    }

    @Test
    void badFilesNameTheField(@TempDir final Path dir) throws IOException {
        final String shipped = Files.readString(DATA.resolve(DummyConfig.FILE), StandardCharsets.UTF_8);
        assertRejected(dir, shipped.replace("look: netherite", "look: wood"), "look");
        assertRejected(dir, shipped.replace("offset: [12, 10]", "offset: [12]"), "offset");
        assertRejected(dir, shipped.replace("level: map", "level: 120"), "level");
        assertRejected(dir, shipped.replace("arena_margin: 6", "arena_margin: 99"), "arena_margin");
        assertRejected(dir, shipped.replace("fire: 40, cold: 40, lightning: 40, chaos: 25}\n    offset: [20, 20]",
                "fire: 95, cold: 40, lightning: 40, chaos: 25}\n    offset: [20, 20]"), "fire");
    }

    @Test
    void levelsAcceptPlayerMapOrNumbers() {
        final DummyConfig config = new DummyConfig();
        config.parse(YamlNode.load("test", new StringReader("""
                dummies:
                  a: { level: player, offset: [1, 2] }
                  b: { level: map, offset: [3, 4] }
                  c: { level: 70, offset: [5, 6], look: iron }
                  d: { offset: [7, 8] }
                """)));
        final List<DummyConfig.Dummy> dummies = config.dummies();
        assertEquals(List.of(DummyConfig.LEVEL_PLAYER, DummyConfig.LEVEL_MAP, 70, DummyConfig.LEVEL_PLAYER),
                dummies.stream().map(DummyConfig.Dummy::level).toList());
        assertEquals("iron", dummies.get(2).look());
        assertEquals(1, dummies.get(3).armour(), 1e-9, "沒寫：跟一般怪物一樣");
    }

    @Test
    void dpsCountsTheLastHitsInterval() {
        final DpsMeter meter = new DpsMeter();
        for (int i = 0; i < 10; i++) {
            meter.hit(1000L * i, 100, i % 2 == 0);
        }
        assertEquals(10, meter.seconds(), 1e-9, "打 10 下、每秒一下＝10 秒");
        assertEquals(100, meter.dps(), 1e-9);
        assertEquals(1000, meter.total(), 1e-9);
        assertEquals(5, meter.crits());
        assertEquals(0.5, meter.critRate(), 1e-9);
        assertEquals(1.0, meter.hitRate(), 1e-9);
    }

    @Test
    void missesBlocksAndIgniteCount() {
        final DpsMeter meter = new DpsMeter();
        meter.hit(0, 300, true);
        meter.miss(500);
        meter.block(1000);
        meter.hit(1500, 100, false);
        meter.dot(2000, 50);
        meter.dot(3500, 50);
        assertEquals(4, meter.hits() + meter.misses() + meter.blocks());
        assertEquals(0.5, meter.hitRate(), 1e-9);
        assertEquals(300, meter.highest(), 1e-9);
        assertEquals(100, meter.dotTotal(), 1e-9);
        assertEquals(500, meter.total(), 1e-9);
        assertEquals(4.0, meter.seconds(), 1e-9, "點燃燒到最後一跳（3.5 秒）再加半秒");
        assertEquals(3500, meter.lastActivity());
    }

    @Test
    void oneHitCountsAsOneSecond() {
        final DpsMeter meter = new DpsMeter();
        assertTrue(meter.isEmpty());
        assertEquals(0, meter.dps(), 1e-9);
        meter.hit(5000, 777, false);
        assertEquals(1, meter.seconds(), 1e-9);
        assertEquals(777, meter.dps(), 1e-9);
    }

    @Test
    void numbersUseThousandsSeparators() {
        assertEquals("1,234,568", DpsMeter.number(1234567.6));
        assertEquals("0", DpsMeter.number(0.2));
    }

    private static void assertRejected(final Path dir, final String content, final String mention) throws IOException {
        Files.writeString(dir.resolve(DummyConfig.FILE), content, StandardCharsets.UTF_8);
        final DummyConfig broken = new DummyConfig();
        final IllegalArgumentException error = assertThrows(IllegalArgumentException.class, () -> broken.load(dir));
        assertTrue(error.getMessage().contains(mention), error.getMessage());
    }

    @Test
    void dummyRecordsConvertToBossDps() throws IOException {
        // 2026-10：打假人的紀錄換成打 Boss 的每秒傷害（終局、極境、巔峰的上限用）：白怪假人比首領好打很多
        final DummyConfig config = new DummyConfig();
        config.load(DATA);
        assertEquals(55000, config.bossDps(new DummyRecord(100000, 10, "訓練假人・無防禦（Lv.90）", 0, 0)), 1e-6);
        assertEquals(60000, config.bossDps(new DummyRecord(100000, 10, "訓練假人・一般（Lv.90）", 0, 0)), 1e-6);
        assertEquals(100000, config.bossDps(new DummyRecord(100000, 10, "訓練假人・首領（Lv.87）", 0, 0)), 1e-6, "打首領假人照算");
        assertEquals(100000 * DummyConfig.DEFAULT_BOSS_FACTOR, config.bossDps(new DummyRecord(100000, 10, "別的假人", 0, 0)), 1e-6);
        assertEquals(0, config.bossDps(null), 1e-9);
    }
}
