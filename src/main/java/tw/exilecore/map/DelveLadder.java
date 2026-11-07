package tw.exilecore.map;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.reflect.TypeToken;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * 2026-11-07 照站長「排行不重置」：星脈深坑的排行（每個帳號打到最深的一層）。存成 plugins/ExileCore/delve_ladder.json，
 * 不放資料庫，清檔（WipeService）不會動到。純 Java，不依賴 Bukkit（可以直接測）。
 */
final class DelveLadder {

    /**
     * 一個帳號的紀錄。
     *
     * @param name      玩家名字（最後一次破紀錄時的）
     * @param character 破紀錄的角色名字
     * @param className 職業
     * @param best      最深到第幾層（過關的那一層）
     * @param at        破紀錄的時間（毫秒）
     */
    record Entry(String name, String character, String className, int best, long at) {
    }

    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();

    private final Path file;
    private final Map<UUID, Entry> entries = new LinkedHashMap<>();

    DelveLadder(final Path file) {
        this.file = file;
    }

    /** 讀檔（沒有檔案就是空的；壞掉的檔案留著、改名備份）。 */
    void load() {
        entries.clear();
        if (file == null || !Files.isRegularFile(file)) {
            return;
        }
        try {
            final Map<String, Entry> read = GSON.fromJson(Files.readString(file, StandardCharsets.UTF_8), new TypeToken<Map<String, Entry>>() { }.getType());
            if (read != null) {
                for (final Map.Entry<String, Entry> entry : read.entrySet()) {
                    if (entry.getValue() != null && entry.getValue().best() > 0) {
                        entries.put(UUID.fromString(entry.getKey()), entry.getValue());
                    }
                }
            }
        } catch (final IOException | RuntimeException e) {
            try {
                Files.copy(file, file.resolveSibling(file.getFileName() + ".broken"), StandardCopyOption.REPLACE_EXISTING);
            } catch (final IOException ignored) {
                // 備份不了就算了
            }
        }
    }

    /** 寫檔（先寫暫存檔再換過去）。 */
    synchronized void save() throws IOException {
        if (file == null) {
            return;
        }
        final Map<String, Entry> out = new LinkedHashMap<>();
        for (final Map.Entry<UUID, Entry> entry : entries.entrySet()) {
            out.put(entry.getKey().toString(), entry.getValue());
        }
        Files.createDirectories(file.getParent());
        final Path temp = file.resolveSibling(file.getFileName() + ".tmp");
        Files.writeString(temp, GSON.toJson(out), StandardCharsets.UTF_8);
        Files.move(temp, file, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
    }

    /** 這個帳號最深到第幾層（沒打過 0）。 */
    int best(final UUID account) {
        final Entry entry = entries.get(account);
        return entry == null ? 0 : entry.best();
    }

    /** 過了第 floor 層：比紀錄深才記，回傳有沒有破紀錄。 */
    // 2026-11-07 修正（檢查發現）：跟非同步的 save() 同一把鎖——存檔走訪時主執行緒新增一筆會丟 ConcurrentModificationException、這次存檔失敗
    synchronized boolean record(final UUID account, final String name, final String character, final String className, final int floor, final long at) {
        final Entry old = entries.get(account);
        if (old != null && old.best() >= floor) {
            return false;
        }
        entries.put(account, new Entry(name, character, className, floor, at));
        return true;
    }

    /** 前 limit 名：層數深的在前，一樣深的先打到的在前。 */
    List<Map.Entry<UUID, Entry>> top(final int limit) {
        final List<Map.Entry<UUID, Entry>> list = new ArrayList<>(entries.entrySet());
        list.sort(Comparator.<Map.Entry<UUID, Entry>>comparingInt(entry -> -entry.getValue().best())
                .thenComparingLong(entry -> entry.getValue().at()));
        return list.subList(0, Math.min(limit, list.size()));
    }

    /** 這個帳號排第幾（1 起算；沒上榜 0）。 */
    int rank(final UUID account) {
        if (!entries.containsKey(account)) {
            return 0;
        }
        final List<Map.Entry<UUID, Entry>> all = top(entries.size());
        for (int i = 0; i < all.size(); i++) {
            if (all.get(i).getKey().equals(account)) {
                return i + 1;
            }
        }
        return 0;
    }

    /** 全服最深（沒人打過 0）。 */
    int serverBest() {
        int best = 0;
        for (final Entry entry : entries.values()) {
            best = Math.max(best, entry.best());
        }
        return best;
    }
}
