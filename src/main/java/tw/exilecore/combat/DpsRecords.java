package tw.exilecore.combat;

import com.google.gson.Gson;
import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import org.bukkit.entity.LivingEntity;
import org.bukkit.entity.Player;
import tw.exilecore.ExileCore;
import tw.exilecore.character.PlayerState;

import java.io.BufferedWriter;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * 輸出紀錄（2026-10，只給管理員看）：每場 Boss（區域、地圖、終局、極境、巔峰、世界 Boss）倒下時，每個打過的人
 * 打了多少傷害、從第一下到 Boss 倒下幾秒；打訓練假人夠長的一段也記一筆。管理網頁的「輸出紀錄」分頁看。
 * <p>
 * 存成 plugins/ExileCore/dps_records.jsonl（一行一筆，只往後加；啟動時讀最後 {@link #KEEP} 筆，檔案太大就只留這些）。
 * 紀錄在主執行緒加、管理網頁在網頁的執行緒讀（鎖住 entries），寫檔在背景。
 */
public final class DpsRecords {

    /** 記憶體裡留幾筆（管理網頁看的就是這些）。 */
    static final int KEEP = 5000;
    /** 檔案超過這麼多行，下次啟動時整理成最後 KEEP 筆。 */
    private static final int COMPACT_LINES = 30000;
    /** 打 Boss 至少算幾秒（只打了最後一下的人不會變成每秒幾千萬）。 */
    static final double MIN_SECONDS = 3;

    private static final Gson GSON = new Gson();
    private static DpsRecords instance;

    /** 一筆：kind = boss／dummy。 */
    public record Entry(long at, String kind, String player, String uuid, String character, String className, int level,
                        String target, String where, int players, double damage, double seconds, double share) {
        public double dps() {
            return seconds <= 0 ? 0 : damage / seconds;
        }
    }

    private final ExileCore plugin;
    private final Path file;
    private final Deque<Entry> entries = new ArrayDeque<>();
    private final Object writeLock = new Object();

    private DpsRecords(final ExileCore plugin) {
        this.plugin = plugin;
        this.file = plugin.getDataFolder().toPath().resolve("dps_records.jsonl");
        load();
    }

    /** 共用的一份（第一次用時讀檔）。 */
    public static synchronized DpsRecords get(final ExileCore plugin) {
        if (instance == null || instance.plugin != plugin) {
            instance = new DpsRecords(plugin);
        }
        return instance;
    }

    private void load() {
        if (!Files.exists(file)) {
            return;
        }
        try {
            final List<String> lines = Files.readAllLines(file, StandardCharsets.UTF_8);
            final int from = Math.max(0, lines.size() - KEEP);
            for (int i = from; i < lines.size(); i++) {
                try {
                    final Entry entry = GSON.fromJson(lines.get(i), Entry.class);
                    if (entry != null && entry.kind() != null) {
                        entries.addLast(entry);
                    }
                } catch (final RuntimeException ignored) {
                    // 壞掉的一行跳過
                }
            }
            if (lines.size() > COMPACT_LINES) {
                final List<String> keep = new ArrayList<>();
                for (final Entry entry : entries) {
                    keep.add(GSON.toJson(entry));
                }
                Files.write(file, keep, StandardCharsets.UTF_8);
            }
        } catch (final IOException e) {
            plugin.getSLF4JLogger().warn("讀不到輸出紀錄 {}", file, e);
        }
    }

    /** 加一筆（主執行緒）；寫檔在背景。 */
    public void add(final Entry entry) {
        synchronized (entries) {
            entries.addLast(entry);
            while (entries.size() > KEEP) {
                entries.removeFirst();
            }
        }
        final String line = GSON.toJson(entry);
        final Runnable write = () -> {
            synchronized (writeLock) {
                try {
                    Files.createDirectories(file.getParent());
                    try (BufferedWriter out = Files.newBufferedWriter(file, StandardCharsets.UTF_8, StandardOpenOption.CREATE,
                            StandardOpenOption.APPEND)) {
                        out.write(line);
                        out.newLine();
                    }
                } catch (final IOException e) {
                    plugin.getSLF4JLogger().warn("寫不進輸出紀錄 {}", file, e);
                }
            }
        };
        if (plugin.isEnabled()) {
            plugin.getServer().getScheduler().runTaskAsynchronously(plugin, write);
        } else {
            write.run();
        }
    }

    // ---- 記錄 ----

    /**
     * Boss 倒下：每個打過的人一筆。damage = 傷害紀錄（真正扣掉的生命，含點燃、召喚物），firstHits = 第一次打到的 tick。
     */
    public void bossKilled(final LivingEntity boss, final String bossName, final Map<UUID, Double> damage, final Map<UUID, Long> firstHits,
                           final long now) {
        double total = 0;
        for (final double value : damage.values()) {
            total += Math.max(0, value);
        }
        if (total <= 0) {
            return;
        }
        final String where = where(boss);
        final int players = (int) damage.values().stream().filter(value -> value > 0).count();
        final long at = System.currentTimeMillis();
        for (final Map.Entry<UUID, Double> each : damage.entrySet()) {
            if (each.getValue() == null || each.getValue() <= 0) {
                continue;
            }
            final Long first = firstHits.get(each.getKey());
            final double seconds = Math.max(MIN_SECONDS, first == null ? 0 : (now - first) / 20.0);
            final Player player = plugin.getServer().getPlayer(each.getKey());
            add(entry(at, "boss", each.getKey(), player, bossName, where, players, each.getValue(), seconds, each.getValue() / total));
        }
    }

    /** 打訓練假人夠長的一段（跟角色頁的「假人實測」同一個條件）。 */
    public void dummy(final Player player, final String dummyName, final double damage, final double seconds) {
        if (seconds <= 0 || damage <= 0) {
            return;
        }
        add(entry(System.currentTimeMillis(), "dummy", player.getUniqueId(), player, dummyName, "訓練假人", 1, damage, seconds, 1));
    }

    private Entry entry(final long at, final String kind, final UUID uuid, final Player player, final String target, final String where,
                        final int players, final double damage, final double seconds, final double share) {
        String name = null;
        String character = null;
        String className = null;
        int level = 0;
        if (player != null) {
            name = player.getName();
            final PlayerState state = plugin.players() == null ? null : plugin.players().get(player);
            if (state != null && state.hasCharacter()) {
                character = state.character().name();
                className = state.characterClass() == null ? null : state.characterClass().displayName();
                level = state.level();
            }
        } else {
            name = plugin.getServer().getOfflinePlayer(uuid).getName();
        }
        return new Entry(at, kind, name == null ? uuid.toString() : name, uuid.toString(), character, className, level, target, where,
                players, Math.round(damage), Math.round(seconds * 10) / 10.0, Math.round(share * 1000) / 1000.0);
    }

    /** Boss 在哪：地圖的名稱（「沉船灣 T13」「天穹熔爐・極境」）、世界 Boss、區域。 */
    private String where(final LivingEntity boss) {
        if (plugin.maps() != null) {
            final tw.exilecore.map.MapInstance instance = plugin.maps().instanceOf(boss);
            if (instance != null) {
                return instance.label();
            }
        }
        if (boss.getWorld().getName().equals("exilecore_event")) {
            return "世界 Boss";
        }
        return "區域 Boss";
    }

    // ---- 管理網頁 ----

    /**
     * 最近的紀錄與每個人的平均。days = 只看最近幾天（0 = 全部）。
     * 平均每秒傷害 = 總傷害 ÷ 總秒數（打得久的場次比重大；只打一下就倒的不會拉高）。
     */
    public JsonObject json(final int days) {
        final long since = days <= 0 ? 0 : System.currentTimeMillis() - days * 86_400_000L;
        final JsonArray rows = new JsonArray();
        final Map<String, double[]> boss = new LinkedHashMap<>();   // uuid → {傷害, 秒數, 場數, 最高每秒}
        final Map<String, double[]> dummy = new HashMap<>();        // uuid → {最近的每秒, 最高的每秒, 次數}
        final Map<String, Entry> latest = new HashMap<>();
        final List<Entry> list;
        synchronized (entries) {
            list = new ArrayList<>(entries);
        }
        for (int i = list.size() - 1; i >= 0; i--) {
            final Entry entry = list.get(i);
            if (entry.at() < since) {
                continue;
            }
            if (rows.size() < 2000) {
                rows.add(GSON.toJsonTree(entry));
            }
            latest.putIfAbsent(entry.uuid(), entry);
            if ("boss".equals(entry.kind())) {
                final double[] sum = boss.computeIfAbsent(entry.uuid(), key -> new double[4]);
                sum[0] += entry.damage();
                sum[1] += entry.seconds();
                sum[2]++;
                sum[3] = Math.max(sum[3], entry.dps());
            } else {
                final double[] sum = dummy.computeIfAbsent(entry.uuid(), key -> new double[] {entry.dps(), 0, 0});
                sum[1] = Math.max(sum[1], entry.dps());
                sum[2]++;
            }
        }
        final JsonArray players = new JsonArray();
        for (final Map.Entry<String, Entry> each : latest.entrySet()) {
            final Entry last = each.getValue();
            final JsonObject row = new JsonObject();
            row.addProperty("player", last.player());
            row.addProperty("character", last.character());
            row.addProperty("className", last.className());
            row.addProperty("level", last.level());
            final double[] b = boss.get(each.getKey());
            if (b != null) {
                row.addProperty("bossFights", (int) b[2]);
                row.addProperty("bossDps", Math.round(b[1] <= 0 ? 0 : b[0] / b[1]));
                row.addProperty("bossBest", Math.round(b[3]));
            }
            final double[] d = dummy.get(each.getKey());
            if (d != null) {
                row.addProperty("dummyLast", Math.round(d[0]));
                row.addProperty("dummyBest", Math.round(d[1]));
                row.addProperty("dummyCount", (int) d[2]);
            }
            players.add(row);
        }
        final JsonObject root = new JsonObject();
        root.add("players", players);
        root.add("rows", rows);
        root.addProperty("total", list.size());
        return root;
    }
}
