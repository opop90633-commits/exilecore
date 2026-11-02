package tw.exilecore.client.core;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.DirectoryStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.HashMap;
import java.util.Map;
import java.util.regex.Pattern;

/**
 * 2026-11-02 照站長（D 組）：樹的靜態資料（天賦樹、星圖天賦）照雜湊快取：記憶體一份，磁碟一份
 * （{@code config/exilecore_client/ui-cache/種類-雜湊.json}，下次開遊戲不用再跟伺服器要）。同一種只留最新的一份。純 Java（測試用）。
 */
public final class TreeCache {

    private static final Pattern KIND = Pattern.compile("[a-z0-9_]{1,32}");

    private final Path folder;
    private final Map<String, TreeModel.Data> memory = new HashMap<>();

    /** folder 是 null 時只用記憶體。 */
    public TreeCache(final Path folder) {
        this.folder = folder;
    }

    static String fileName(final String kind, final long hash) {
        return kind + "-" + UiWire.hex(hash) + ".json";
    }

    /** 拿快取（記憶體沒有就讀磁碟；雜湊對不上的不算）。沒有回傳 null。 */
    public TreeModel.Data get(final String kind, final long hash) {
        if (!KIND.matcher(kind).matches()) {
            return null;
        }
        final TreeModel.Data cached = memory.get(kind);
        if (cached != null && cached.hash == hash) {
            return cached;
        }
        if (folder == null) {
            return null;
        }
        final Path file = folder.resolve(fileName(kind, hash));
        try {
            if (!Files.isRegularFile(file)) {
                return null;
            }
            final byte[] raw = Files.readAllBytes(file);
            if (UiWire.hash(raw) != hash) {
                Files.deleteIfExists(file); // 壞掉的
                return null;
            }
            final TreeModel.Data data = TreeModel.parseData(kind, hash, new String(raw, StandardCharsets.UTF_8));
            memory.put(kind, data);
            return data;
        } catch (final IOException | RuntimeException e) {
            return null;
        }
    }

    /** 收到新的靜態資料：記下來（磁碟上同一種的舊檔刪掉）。回傳讀好的資料；格式不對回傳 null。 */
    public TreeModel.Data put(final String kind, final long hash, final byte[] raw) {
        if (!KIND.matcher(kind).matches()) {
            return null;
        }
        final TreeModel.Data data;
        try {
            data = TreeModel.parseData(kind, hash, new String(raw, StandardCharsets.UTF_8));
        } catch (final RuntimeException e) {
            return null;
        }
        memory.put(kind, data);
        if (folder != null) {
            try {
                Files.createDirectories(folder);
                try (DirectoryStream<Path> old = Files.newDirectoryStream(folder, kind + "-*.json")) {
                    for (final Path file : old) {
                        Files.deleteIfExists(file);
                    }
                }
                final Path target = folder.resolve(fileName(kind, hash));
                final Path temp = folder.resolve(fileName(kind, hash) + ".tmp");
                Files.write(temp, raw);
                Files.move(temp, target, StandardCopyOption.REPLACE_EXISTING);
            } catch (final IOException e) {
                // 存不了磁碟：下次再跟伺服器要
            }
        }
        return data;
    }

    public void clearMemory() {
        memory.clear();
    }
}
