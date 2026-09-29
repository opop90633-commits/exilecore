package tw.exilecore.core;

import org.bukkit.plugin.java.JavaPlugin;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

/**
 * 第一次啟動時把 jar 裡的預設資料檔複製到 plugins/ExileCore/data/。
 * 清單在 jar 的 data/index.txt（一行一個路徑）；已存在的檔案不覆蓋，玩家改過的設定不會被洗掉。
 */
public final class DataFiles {

    private DataFiles() {
    }

    public static List<String> copyDefaults(final JavaPlugin plugin) throws IOException {
        final List<String> copied = new ArrayList<>();
        try (InputStream index = plugin.getResource("data/index.txt")) {
            if (index == null) {
                return copied;
            }
            try (BufferedReader reader = new BufferedReader(new InputStreamReader(index, StandardCharsets.UTF_8))) {
                String line;
                while ((line = reader.readLine()) != null) {
                    final String path = line.trim();
                    if (path.isEmpty() || path.startsWith("#")) {
                        continue;
                    }
                    final Path target = plugin.getDataFolder().toPath().resolve(path);
                    if (Files.exists(target)) {
                        continue;
                    }
                    plugin.saveResource(path, false);
                    copied.add(path);
                }
            }
        }
        return copied;
    }
}
