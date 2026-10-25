package tw.exilecore.balance;

import com.google.gson.Gson;
import tw.exilecore.ExileCore;

import java.io.BufferedWriter;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.List;
import java.util.function.ToLongFunction;

/**
 * 一行一筆的 JSON 紀錄檔（2026-10-25，平衡報表的死亡紀錄、地圖紀錄用；跟 {@link tw.exilecore.combat.DpsRecords} 同一個做法）：
 * 只往後加，啟動時讀最後 {@code keep} 筆，檔案太大就整理成最後 keep 筆。加紀錄在主執行緒，寫檔在背景。
 */
public final class RecordLog<T> {

    private static final Gson GSON = new Gson();

    private final ExileCore plugin;
    private final Path file;
    private final Class<T> type;
    private final int keep;
    private final ToLongFunction<T> at;
    private final Deque<T> entries = new ArrayDeque<>();
    private final Object writeLock = new Object();

    public RecordLog(final ExileCore plugin, final String fileName, final Class<T> type, final int keep, final ToLongFunction<T> at) {
        this.plugin = plugin;
        this.file = plugin.getDataFolder().toPath().resolve(fileName);
        this.type = type;
        this.keep = keep;
        this.at = at;
        load();
    }

    private void load() {
        if (!Files.exists(file)) {
            return;
        }
        try {
            final List<String> lines = Files.readAllLines(file, StandardCharsets.UTF_8);
            for (int i = Math.max(0, lines.size() - keep); i < lines.size(); i++) {
                try {
                    final T entry = GSON.fromJson(lines.get(i), type);
                    if (entry != null) {
                        entries.addLast(entry);
                    }
                } catch (final RuntimeException ignored) {
                    // 壞掉的一行跳過
                }
            }
            if (lines.size() > keep * 6) {
                final List<String> rewrite = new ArrayList<>();
                for (final T entry : entries) {
                    rewrite.add(GSON.toJson(entry));
                }
                Files.write(file, rewrite, StandardCharsets.UTF_8);
            }
        } catch (final IOException e) {
            plugin.getSLF4JLogger().warn("讀不到紀錄 {}", file, e);
        }
    }

    /** 加一筆（主執行緒）；寫檔在背景。 */
    public void add(final T entry) {
        synchronized (entries) {
            entries.addLast(entry);
            while (entries.size() > keep) {
                entries.removeFirst();
            }
        }
        final String line = GSON.toJson(entry);
        final Runnable write = () -> {
            synchronized (writeLock) {
                try {
                    Files.createDirectories(file.getParent());
                    try (BufferedWriter out = Files.newBufferedWriter(file, StandardCharsets.UTF_8, StandardOpenOption.CREATE, StandardOpenOption.APPEND)) {
                        out.write(line);
                        out.newLine();
                    }
                } catch (final IOException e) {
                    plugin.getSLF4JLogger().warn("寫不進紀錄 {}", file, e);
                }
            }
        };
        if (plugin.isEnabled()) {
            plugin.getServer().getScheduler().runTaskAsynchronously(plugin, write);
        } else {
            write.run();
        }
    }

    /** 最近幾天的（0 = 全部），舊的在前。 */
    public List<T> recent(final int days) {
        final long since = days <= 0 ? 0 : System.currentTimeMillis() - days * 86_400_000L;
        final List<T> list = new ArrayList<>();
        synchronized (entries) {
            for (final T entry : entries) {
                if (at.applyAsLong(entry) >= since) {
                    list.add(entry);
                }
            }
        }
        return list;
    }
}
