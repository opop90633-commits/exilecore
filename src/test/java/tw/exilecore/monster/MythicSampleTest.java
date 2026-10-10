package tw.exilecore.monster;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.yaml.snakeyaml.Yaml;

import java.io.IOException;
import java.io.Reader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * MythicMobs 範例怪物檔（2026-10，「終局 Boss 好像會同時放兩個招式」）：Boss 只留外觀的粒子，招式交給 Boss 招式引擎；
 * 伺服器上以前版本的範例沒改過就自動換成新版，改過的不動、新版放旁邊的 .new。
 */
class MythicSampleTest {

    static final Path SAMPLES = Path.of("src/main/resources/mythicmobs");

    /**
     * 這一版範例檔的雜湊。範例檔改了這裡會失敗：把這裡舊的雜湊加進 {@link MythicSamples#PREVIOUS}（伺服器上沒改過的才會自動換新），
     * 再把這裡換成新的。
     */
    static final Map<String, String> SHIPPED = Map.of(
            "ExileCore.yml", "a42132e032b6fe09a59dc59e0efc7e324c9750090276b8751a41ba5ecf03b87d",
            "ExileCore-maps.yml", "4eab7a8fe325679e0363f7ecf840f409970adb72e741c484ea152f96214036a2");

    @TempDir
    Path dir;

    @Test
    void installsMissingSamples() throws IOException {
        final Path target = dir.resolve("Mobs").resolve("ExileCore.yml");
        assertEquals(MythicSamples.Sync.ADDED, MythicSamples.sync(target, bytes("new"), Set.of()));
        assertEquals("new", Files.readString(target));
        assertEquals(MythicSamples.Sync.CURRENT, MythicSamples.sync(target, bytes("new"), Set.of()));
    }

    @Test
    void replacesUntouchedOldSamples() throws IOException {
        final Path target = dir.resolve("ExileCore.yml");
        Files.writeString(target, "old\nversion\n");
        assertEquals(MythicSamples.Sync.UPDATED, MythicSamples.sync(target, bytes("new"), Set.of(MythicSamples.hash(bytes("old\nversion\n")))));
        assertEquals("new", Files.readString(target));
        assertFalse(Files.exists(dir.resolve("ExileCore.yml.new")));
        // Windows 上存過一次（換行變成 \r\n）也認得是沒改過的舊版
        Files.writeString(target, "old\r\nversion\r\n");
        assertEquals(MythicSamples.Sync.UPDATED, MythicSamples.sync(target, bytes("new"), Set.of(MythicSamples.hash(bytes("old\nversion\n")))));
        assertEquals("new", Files.readString(target));
    }

    @Test
    void keepsEditedSamplesAndPutsTheNewOneBeside() throws IOException {
        final Path target = dir.resolve("ExileCore-maps.yml");
        Files.writeString(target, "my own boss");
        final Set<String> previous = Set.of(MythicSamples.hash(bytes("old")));
        assertEquals(MythicSamples.Sync.KEPT, MythicSamples.sync(target, bytes("new"), previous));
        assertEquals("my own boss", Files.readString(target), "你改過的不動");
        final Path side = dir.resolve("ExileCore-maps.yml.new");
        assertEquals("new", Files.readString(side));
        final long written = Files.getLastModifiedTime(side).toMillis();
        assertEquals(MythicSamples.Sync.KEPT, MythicSamples.sync(target, bytes("new"), previous));
        assertEquals(written, Files.getLastModifiedTime(side).toMillis(), ".new 已經是新版就不再寫");
        assertFalse(side.getFileName().toString().endsWith(".yml"), "MythicMobs 只讀 .yml：旁邊的 .new 不會被當成怪物載入");
    }

    @Test
    void hashIgnoresLineEndings() {
        assertEquals(MythicSamples.hash(bytes("a\nb\n")), MythicSamples.hash(bytes("a\r\nb\r\n")));
        assertFalse(MythicSamples.hash(bytes("a\nb\n")).equals(MythicSamples.hash(bytes("a\nc\n"))));
    }

    @Test
    void shippedSamplesAreKnown() throws IOException {
        for (final Map.Entry<String, String> entry : SHIPPED.entrySet()) {
            final byte[] shipped = Files.readAllBytes(SAMPLES.resolve(entry.getKey()));
            assertEquals(entry.getValue(), MythicSamples.hash(shipped), entry.getKey() + " 改了：舊的雜湊加進 MythicSamples.PREVIOUS，再更新這個測試");
            assertFalse(MythicSamples.PREVIOUS.getOrDefault(entry.getKey(), Set.of()).contains(MythicSamples.hash(shipped)),
                    "這一版不能列在「以前的版本」裡");
            assertFalse(MythicSamples.PREVIOUS.getOrDefault(entry.getKey(), Set.of()).isEmpty(),
                    entry.getKey() + "：以前版本的範例（伺服器上已經裝的）要能自動換新");
        }
    }

    /** Boss（區域、地圖、終局）只留外觀的粒子：會打人、推人、召喚的計時技能會跟 Boss 招式同時出現。 */
    @Test
    void bossesOnlyKeepCosmeticTimers() throws IOException {
        final Set<String> bosses = Set.of("ExileCore_TideMother", "ExileCore_Broodmother", "ExileCore_Warlord", "ExileCore_BogWitch",
                "ExileCore_FrostRavager", "ExileCore_TombGeneral", "ExileCore_StarPriest", "ExileCore_StarEmperor");
        int checked = 0;
        for (final String file : SHIPPED.keySet()) {
            final Map<String, Object> mobs;
            try (Reader reader = Files.newBufferedReader(SAMPLES.resolve(file), StandardCharsets.UTF_8)) {
                mobs = new Yaml().load(reader);
            }
            for (final Map.Entry<String, Object> mob : mobs.entrySet()) {
                if (!bosses.contains(mob.getKey())) {
                    continue;
                }
                checked++;
                final Object skills = ((Map<?, ?>) mob.getValue()).get("Skills");
                if (skills == null) {
                    continue;
                }
                for (final Object line : (List<?>) skills) {
                    final String skill = String.valueOf(line).trim();
                    assertTrue(!skill.contains("~onTimer") || skill.startsWith("effect:particles"),
                            mob.getKey() + " 的計時技能不是粒子：" + skill);
                    assertTrue(!skill.contains("~onSpawn") && !skill.contains("~onDamaged"), mob.getKey() + "：" + skill);
                }
            }
        }
        assertEquals(bosses.size(), checked, "每隻範例 Boss 都檢查到");
    }

    @Test
    void previousHashesAreHex() {
        for (final Set<String> hashes : MythicSamples.PREVIOUS.values()) {
            for (final String hash : hashes) {
                assertTrue(hash.matches("[0-9a-f]{64}"), hash);
            }
        }
    }

    private static byte[] bytes(final String text) {
        return text.getBytes(StandardCharsets.UTF_8);
    }
}
