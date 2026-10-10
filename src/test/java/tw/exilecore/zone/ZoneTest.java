package tw.exilecore.zone;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import tw.exilecore.core.config.YamlNode;
import tw.exilecore.monster.MonsterConfig;

import java.io.IOException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** 區域設定、版面檔、區域等級的計算。 */
class ZoneTest {

    static final Path DATA = Path.of("src/main/resources/data");
    static final Path MYTHIC_SAMPLE = Path.of("src/main/resources/mythicmobs/ExileCore.yml");

    @Test
    void shippedZonesLoad() throws IOException {
        final ZoneConfig config = new ZoneConfig();
        config.load(DATA);
        assertEquals(6, config.zones().size());
        assertTrue(config.zone("camp").town());

        final List<String> ids = new ArrayList<>();
        for (final ZoneConfig.ZoneDefinition zone : config.combatZones()) {
            ids.add(zone.id());
        }
        assertEquals(List.of("coast", "forest", "ruins", "swamp", "mountain"), ids);

        // 等級一區接一區：1–12、12–24、…、48–60
        int previousMax = 1;
        for (final ZoneConfig.ZoneDefinition zone : config.combatZones()) {
            assertEquals(previousMax, zone.minLevel(), zone.id() + " 的最低等級要接上一區");
            assertEquals(zone.minLevel() + 11 + (zone.minLevel() == 1 ? 0 : 1), zone.maxLevel(), zone.id());
            previousMax = zone.maxLevel();
            assertFalse(zone.monsters().isEmpty(), zone.id() + " 沒有怪物");
            assertNotNull(zone.boss(), zone.id() + " 沒有 Boss");
            assertTrue(zone.boss().mythicId().startsWith("ExileCore_"));
            assertNotNull(zone.icon());
            assertNotNull(zone.intro(), zone.id() + " 沒有進場旁白");
            assertNotNull(zone.boss().intro(), zone.id() + " 的 Boss 沒有登場台詞");
            assertNotNull(zone.boss().defeat(), zone.id() + " 的 Boss 沒有倒下旁白");
        }
        assertNotNull(config.zone("camp").intro());
        assertEquals(60, previousMax);

        final ZoneConfig.SpawnEntry mythic = config.zone("coast").monsters().stream()
                .filter(ZoneConfig.SpawnEntry::isMythic).findFirst().orElseThrow();
        assertEquals("ExileCore_Tidecaller", mythic.mythicId());
        assertEquals("ZOMBIE", config.zone("coast").monsters().get(0).id());

        final ZoneConfig.SpawnerSettings spawner = config.spawner();
        assertEquals(40, spawner.intervalTicks());
        assertEquals(16, spawner.minDistance());
        assertEquals(28, spawner.maxDistance());
        assertEquals(70, spawner.normal().weight());
        assertEquals(3, spawner.normal().minSize());
        assertEquals(5, spawner.normal().maxSize());
        assertEquals(2, spawner.rare().minSize());
        assertEquals(3, spawner.rare().maxSize());
        assertEquals(20, config.boss().radius());
        assertEquals(1, config.boss().firstKillPoints());
    }

    @Test
    void bossModsExistInMonsterConfig() throws IOException {
        final ZoneConfig zones = new ZoneConfig();
        zones.load(DATA);
        final MonsterConfig monsters = new MonsterConfig();
        monsters.load(DATA);
        for (final ZoneConfig.ZoneDefinition zone : zones.combatZones()) {
            for (final String mod : zone.boss().mods()) {
                assertNotNull(monsters.mod(mod), zone.id() + " 的 Boss 詞綴 " + mod + " 不在 monsters.yml");
            }
            for (final ZoneConfig.SpawnEntry entry : zone.monsters()) {
                if (!entry.isMythic()) {
                    assertNotNull(monsters.type(entry.id()).name(), zone.id() + " 的 " + entry.id() + " 在 monsters.yml 沒有中文名");
                }
            }
        }
    }

    /** zones.yml 用到的 MythicMobs 代號（含技能召喚的）都要在範例檔裡。 */
    @Test
    void mythicSampleCoversZones() throws IOException {
        final YamlNode sample = YamlNode.load(MYTHIC_SAMPLE);
        final ZoneConfig zones = new ZoneConfig();
        zones.load(DATA);
        for (final ZoneConfig.ZoneDefinition zone : zones.combatZones()) {
            assertTrue(sample.has(zone.boss().mythicId()), "範例檔缺 " + zone.boss().mythicId());
            for (final ZoneConfig.SpawnEntry entry : zone.monsters()) {
                if (entry.isMythic()) {
                    assertTrue(sample.has(entry.mythicId()), "範例檔缺 " + entry.mythicId());
                }
            }
        }
        final Pattern summon = Pattern.compile("summon\\{type=([A-Za-z0-9_]+)");
        for (final String mob : sample.keys()) {
            assertNotNull(sample.node(mob).string("Type", null), mob + " 沒有 Type");
            for (final String skill : sample.node(mob).strings("Skills")) {
                final Matcher matcher = summon.matcher(skill);
                while (matcher.find()) {
                    assertTrue(sample.has(matcher.group(1)), mob + " 召喚的 " + matcher.group(1) + " 不在範例檔");
                }
            }
        }
    }

    @Test
    void loreLinesAreFormattedAsSpeechOrNarration() {
        assertEquals("<#af6025>溺潮之母</#af6025><gray>：</gray><white>「別走。」", Zone.loreLine("溺潮之母", "「別走。」"));
        assertEquals("<gray><i>潮水退了一點。</i>", Zone.loreLine("溺潮之母", "潮水退了一點。"));
        assertEquals("<gray><i>\\<b>不是標籤</i>", Zone.loreLine(null, "<b>不是標籤"), "玩家看不到的 MiniMessage 標籤要跳脫");
    }

    @Test
    void layoutRoundTrip(@TempDir final Path dir) throws IOException {
        final ZoneLayout layout = new ZoneLayout();
        final ZoneLayout.Entry coast = layout.getOrCreate("coast");
        coast.setBox(ZoneLayout.Box.of("world", 319, 320, 95, 0, -64, -96));
        coast.setEntry(new ZoneLayout.Point("world", 8.5, 70, 0.5, -90f));
        coast.setArena(new ZoneLayout.Point("world", 290.5, 66, 0.5, 90f));
        coast.putCheckpoint(new ZoneLayout.Checkpoint("entrance", "入口", new ZoneLayout.Point("world", 8.5, 70, 0.5, -90f)));
        coast.putCheckpoint(new ZoneLayout.Checkpoint("midway", "中途", new ZoneLayout.Point("world", 150.5, 71.25, -3.5, 0f)));
        layout.getOrCreate("empty"); // 什麼都沒設的不會寫進檔案
        final Path file = dir.resolve("zone-layout.yml");
        layout.save(file);

        final ZoneLayout loaded = new ZoneLayout();
        loaded.load(file);
        assertNull(loaded.get("empty"));
        final ZoneLayout.Entry entry = loaded.get("coast");
        assertEquals(ZoneLayout.Box.of("world", 0, -64, -96, 319, 320, 95), entry.box());
        assertEquals(-90f, entry.entry().yaw());
        assertEquals(290.5, entry.arena().x());
        assertEquals(List.of("entrance", "midway"), entry.checkpoints().stream().map(ZoneLayout.Checkpoint::id).toList());
        assertEquals("中途", entry.checkpoint("midway").name());
        assertEquals(71.3, entry.checkpoint("midway").point().y(), 1e-9, "座標存到小數第一位");
    }

    @Test
    void levelRampsFromEntryToArena() {
        final ZoneConfig.ZoneDefinition definition = new ZoneConfig.ZoneDefinition("coast", "潮濕海岸", false, 1, 12,
                List.of(), null, null, null);
        final ZoneLayout.Entry layout = new ZoneLayout.Entry();
        layout.setBox(ZoneLayout.Box.of("world", 0, -64, -96, 319, 320, 95));
        layout.setEntry(new ZoneLayout.Point("world", 0, 64, 0, 0f));
        layout.setArena(new ZoneLayout.Point("world", 300, 64, 0, 0f));
        final Zone zone = new Zone(definition, layout);
        assertEquals(1, zone.levelAt(0, 0));
        assertEquals(7, zone.levelAt(150, 0));
        assertEquals(12, zone.levelAt(300, 0));
        assertEquals(12, zone.levelAt(1000, 0), "超過 Boss 場地不會再高");
        assertEquals("等級 1–12", zone.levelText());

        assertTrue(zone.contains("world", 319.9, 70, 95.9));
        assertFalse(zone.contains("world", 320, 70, 0));
        assertFalse(zone.contains("world_nether", 10, 70, 0));

        // 沒有 Boss 場地：終點是離入口最遠的角落
        layout.setArena(null);
        assertTrue(zone.levelAt(319, 0) >= 11);

        // 城鎮永遠是最低等級
        final Zone town = new Zone(new ZoneConfig.ZoneDefinition("camp", "營地", true, 1, 1, List.of(), null, null, null), null);
        assertEquals(1, town.levelAt(500, 500));
        assertFalse(town.placed());
    }
}
