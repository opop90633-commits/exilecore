package tw.exilecore.map;

import com.google.gson.Gson;
import tw.exilecore.ExileCore;
import tw.exilecore.monster.DropTable;

import java.io.BufferedWriter;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.UUID;

/**
 * 2026-11-11 照站長「有地方能檢查災厄級有沒有人有掉東西嗎」：Boss 掉落紀錄。
 * 災厄級（迷霧之主、審判者、不朽統帥、晶淵王座）、終局、極境、巔峰每倒下一次、每個有份的人一筆：
 * 掉了哪些傳奇、專屬寶石、通貨（各幾個）。是「掉出來的」（過濾器換成金幣之前），管理網頁「Boss 掉落」看；
 * 有傳奇或專屬寶石的那一筆另外寫進伺服器日誌（{@code [boss-drop]}）。這次之前一般的傳奇、專屬寶石掉了沒有任何紀錄。
 * 存在 plugins/ExileCore/boss_drops.jsonl（一行一筆），記憶體只留最近 {@link #KEEP} 筆。
 */
public final class BossDropRecords {
    static final int KEEP = 30000;
    private static final int COMPACT_LINES = 45000;
    private static final Gson GSON = new Gson();
    private static BossDropRecords instance;

    /** 一筆：誰、哪隻 Boss（種類、名字、代號、階級）、掉了什麼。 */
    public record Entry(long at, String uuid, String player, String kind, String boss, String bossId, int tier,
                        List<String> uniques, List<String> gems, Map<String, Integer> currency) {
    }

    private final ExileCore plugin;
    private final Path file;
    private final Deque<Entry> entries = new ArrayDeque<>();
    private final Object writeLock = new Object();

    private BossDropRecords(final ExileCore plugin) {
        this(plugin, plugin.getDataFolder().toPath().resolve("boss_drops.jsonl"));
    }

    /** 測試用：plugin 可以是 null（寫檔直接在呼叫的執行緒做）。 */
    BossDropRecords(final ExileCore plugin, final Path file) {
        this.plugin = plugin;
        this.file = file;
        load();
    }

    public static synchronized BossDropRecords get(final ExileCore plugin) {
        if (instance == null || instance.plugin != plugin) {
            instance = new BossDropRecords(plugin);
        }
        return instance;
    }

    /** 這場算不算要記的 Boss；回傳種類（災厄級／終局／極境／巔峰），不記回傳 null。 */
    static String kind(final MapInstance instance, final MapConfig config) {
        if (instance.isUber()) {
            return "極境";
        }
        if (instance.isPinnacle()) {
            return "巔峰";
        }
        if (instance.special() != null && config.eventBoss(instance.map().id()) != null) {
            return "災厄級";
        }
        if (instance.map().isFinal()) {
            return "終局";
        }
        return null;
    }

    /** MapService 擲完一個人的 Boss 掉落之後叫（主執行緒）。 */
    public void record(final MapInstance instance, final MapConfig config, final UUID uuid, final String player,
                       final List<DropTable.Drop> drops) {
        final String kind = kind(instance, config);
        if (kind == null || uuid == null) {
            return;
        }
        final List<String> uniques = new ArrayList<>();
        final List<String> gems = new ArrayList<>();
        final Map<String, Integer> currency = new TreeMap<>();
        for (final DropTable.Drop drop : drops) {
            if (drop.isEquipment() && drop.item() != null && drop.item().unique() != null) {
                uniques.add(uniqueName(drop.item().unique()));
            } else if (drop.isSpecificGem()) {
                gems.add(gemName(drop));
            } else if (drop.isCurrency() && drop.currency() != null) {
                currency.merge(drop.currency().name(), 1, Integer::sum);
            }
        }
        final String bossName = instance.bossDef() == null ? instance.map().name() : instance.bossDef().name();
        final Entry entry = new Entry(System.currentTimeMillis(), uuid.toString(), player, kind, bossName, instance.map().id(),
                instance.stats().tier(), uniques, gems, currency);
        add(entry);
        if (plugin != null && (!uniques.isEmpty() || !gems.isEmpty())) {
            plugin.getSLF4JLogger().info("[boss-drop] {} 打倒 {}（{}）：傳奇 {}、專屬寶石 {}", player, bossName, kind, uniques, gems);
        }
    }

    private String uniqueName(final String id) {
        if (plugin == null || plugin.items() == null) {
            return id;
        }
        final tw.exilecore.item.UniqueItem unique = plugin.items().repository().unique(id);
        return unique == null ? id : unique.name();
    }

    private String gemName(final DropTable.Drop drop) {
        if (plugin == null || plugin.skills() == null) {
            return drop.gem();
        }
        if (drop.kind() == DropTable.Kind.SUPPORT_GEM) {
            final tw.exilecore.skill.SupportDefinition support = plugin.skills().config().support(drop.gem());
            return support == null ? drop.gem() : support.name() + "（輔助）";
        }
        final tw.exilecore.skill.SkillDefinition skill = plugin.skills().config().skill(drop.gem());
        return skill == null ? drop.gem() : skill.name() + "（技能）";
    }

    void add(final Entry entry) {
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
                    if (plugin != null) {
                        plugin.getSLF4JLogger().warn("寫不進 Boss 掉落紀錄 {}", file, e);
                    }
                }
            }
        };
        if (plugin != null && plugin.isEnabled()) {
            plugin.getServer().getScheduler().runTaskAsynchronously(plugin, write);
        } else {
            write.run();
        }
    }

    private void load() {
        if (!Files.exists(file)) {
            return;
        }
        try {
            final List<String> lines = Files.readAllLines(file, StandardCharsets.UTF_8);
            for (int i = Math.max(0, lines.size() - KEEP); i < lines.size(); i++) {
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
            if (plugin != null) {
                plugin.getSLF4JLogger().warn("讀不到 Boss 掉落紀錄 {}", file, e);
            }
        }
    }

    /**
     * 管理網頁：最近 days 天（0 = 全部）。summary 每隻 Boss 一列：倒下幾份（每個有份的人一份）、幾個人、
     * 掉了幾件傳奇／專屬寶石、每一件各幾次；rows 是每一筆（新的在前，最多 3000 筆）。
     */
    public Map<String, Object> json(final int days) {
        final long since = days <= 0 ? 0 : System.currentTimeMillis() - days * 86_400_000L;
        final List<Entry> list = new ArrayList<>();
        synchronized (entries) {
            for (final Entry entry : entries) {
                if (entry.at() >= since) {
                    list.add(entry);
                }
            }
        }
        final Map<String, Summary> byBoss = new LinkedHashMap<>();
        for (final Entry entry : list) {
            byBoss.computeIfAbsent(entry.kind() + "|" + entry.boss(), k -> new Summary(entry.kind(), entry.boss())).add(entry);
        }
        final List<Map<String, Object>> summary = new ArrayList<>();
        byBoss.values().stream().sorted((a, b) -> Integer.compare(b.kills, a.kills)).forEach(s -> summary.add(s.json()));
        final List<Map<String, Object>> rows = new ArrayList<>();
        for (int i = list.size() - 1; i >= 0 && rows.size() < 3000; i--) {
            final Entry e = list.get(i);
            final Map<String, Object> row = new LinkedHashMap<>();
            row.put("at", e.at());
            row.put("player", e.player());
            row.put("kind", e.kind());
            row.put("boss", e.boss());
            row.put("tier", e.tier());
            row.put("uniques", e.uniques() == null ? List.of() : e.uniques());
            row.put("gems", e.gems() == null ? List.of() : e.gems());
            row.put("currency", e.currency() == null ? Map.of() : e.currency());
            rows.add(row);
        }
        final Map<String, Object> out = new LinkedHashMap<>();
        out.put("summary", summary);
        out.put("rows", rows);
        out.put("total", list.size());
        return out;
    }

    private static final class Summary {
        final String kind;
        final String boss;
        int kills;
        int uniques;
        int gems;
        final Set<String> players = new HashSet<>();
        final Map<String, Integer> items = new TreeMap<>();

        Summary(final String kind, final String boss) {
            this.kind = kind;
            this.boss = boss;
        }

        void add(final Entry e) {
            kills++;
            players.add(e.uuid());
            if (e.uniques() != null) {
                uniques += e.uniques().size();
                e.uniques().forEach(name -> items.merge(name, 1, Integer::sum));
            }
            if (e.gems() != null) {
                gems += e.gems().size();
                e.gems().forEach(name -> items.merge(name, 1, Integer::sum));
            }
        }

        Map<String, Object> json() {
            final Map<String, Object> map = new LinkedHashMap<>();
            map.put("kind", kind);
            map.put("boss", boss);
            map.put("kills", kills);
            map.put("players", players.size());
            map.put("uniques", uniques);
            map.put("gems", gems);
            map.put("items", items);
            return map;
        }
    }
}
