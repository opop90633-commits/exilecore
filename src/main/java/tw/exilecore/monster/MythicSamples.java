package tw.exilecore.monster;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.Map;
import java.util.Set;

/**
 * MythicMobs 範例怪物檔的同步（純邏輯，不依賴 Bukkit，單元測試直接呼叫）：jar 裡的 mythicmobs/檔名 → MythicMobs/Mobs/ 的同一個檔名。
 * 沒有就裝；是以前版本的範例檔（沒被改過）就換成新版；被改過的不動，新版放旁邊的 .new（MythicMobs 只讀 .yml，不會載入它）。
 */
final class MythicSamples {

    /** 範例檔：jar 裡的資源 mythicmobs/檔名 → MythicMobs/Mobs/ 底下的同一個檔名。 */
    static final String[] FILES = {"ExileCore.yml", "ExileCore-maps.yml"};

    /**
     * 以前版本的範例檔內容（{@link #hash}）：伺服器上的檔案是其中之一就是沒改過，直接換成新版。
     * 2026-10：Boss 只留外觀的粒子，拿掉會打人、推人、召喚的計時技能（throw、leap、pull、閃電、範圍藥水、召喚）——
     * Boss 的招式由 Boss 招式引擎負責，兩邊都出招看起來就像同時放兩招。
     */
    static final Map<String, Set<String>> PREVIOUS = Map.of(
            "ExileCore.yml", Set.of("4362ac99c66ffa41b560404609def26aa74ffb5bfb0b7aba8e7b687c175e8139"),
            "ExileCore-maps.yml", Set.of("9261514cefcc9685e3ce7a01db71a39850f2c4a5d9fd7c8f6c17c3e8b5d5c37b",
                    "4eab7a8fe325679e0363f7ecf840f409970adb72e741c484ea152f96214036a2")); // 2026-10：加了萬界覺醒者之前的版本

    /** 一個範例檔同步的結果。 */
    enum Sync {
        /** 原本沒有，裝進去了。 */
        ADDED,
        /** 已經是新版。 */
        CURRENT,
        /** 是以前版本的範例檔（沒改過），換成新版了。 */
        UPDATED,
        /** 被改過：不動，新版放旁邊的 .new。 */
        KEPT
    }

    private MythicSamples() {
    }

    /**
     * 同步一個範例檔。
     *
     * @param target   MythicMobs/Mobs/ 底下的檔案
     * @param shipped  這一版 jar 裡的內容
     * @param previous 以前版本的內容雜湊：伺服器上的是其中之一就直接換新
     */
    static Sync sync(final Path target, final byte[] shipped, final Set<String> previous) throws IOException {
        if (!Files.exists(target)) {
            Files.createDirectories(target.getParent());
            Files.write(target, shipped);
            return Sync.ADDED;
        }
        final String disk = hash(Files.readAllBytes(target));
        final String fresh = hash(shipped);
        if (disk.equals(fresh)) {
            return Sync.CURRENT;
        }
        if (previous.contains(disk)) {
            Files.write(target, shipped);
            return Sync.UPDATED;
        }
        final Path side = target.resolveSibling(target.getFileName() + ".new");
        if (!Files.exists(side) || !hash(Files.readAllBytes(side)).equals(fresh)) {
            Files.write(side, shipped);
        }
        return Sync.KEPT;
    }

    /** 內容的 SHA-256（換行統一成 \n 再算：在 Windows 上存過一次、換行變成 \r\n 也認得）。 */
    static String hash(final byte[] bytes) {
        final byte[] normalized = new String(bytes, StandardCharsets.UTF_8).replace("\r\n", "\n").getBytes(StandardCharsets.UTF_8);
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(normalized));
        } catch (final NoSuchAlgorithmException e) {
            throw new IllegalStateException("JVM 沒有 SHA-256", e);
        }
    }
}
