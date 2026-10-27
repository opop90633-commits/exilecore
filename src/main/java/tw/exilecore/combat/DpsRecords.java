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
import java.nio.file.StandardCopyOption;
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

    /**
     * 一筆：kind = boss／dummy。
     * <p>
     * 2026-10-19 配裝快照（流派平衡用；這之前的紀錄這幾欄是空的）：
     * ascendancy 昇華職業、mainSkill 這一段打最多傷害的技能（持續傷害不算）、mainShare 它佔這一段總傷害的比例、
     * dotShare 持續傷害（點燃、流血、中毒）佔的比例、supports 主技能插的輔助、uniques 身上穿的傳奇、
     * types 各傷害類型的比例（physical、fire、cold、lightning、chaos）。
     * <p>
     * 2026-10-25：rawSeconds 沒套 {@link #MIN_SECONDS} 地板的實際秒數（0 = 舊紀錄沒有）；平衡報表用它算真實每秒傷害與「秒殺率」。
     * 技能放在英靈圖騰、星咒圖騰這種宿主裡的，mainSkill 寫成「宿主＞技能」、supports 是宿主的輔助。
     */
    public record Entry(long at, String kind, String player, String uuid, String character, String className, int level,
                        String target, String where, int players, double damage, double seconds, double share,
                        String ascendancy, String mainSkill, double mainShare, double dotShare, List<String> supports,
                        List<String> uniques, Map<String, Double> types, double rawSeconds) {
        /** 舊的呼叫法（沒有 rawSeconds）。 */
        public Entry(final long at, final String kind, final String player, final String uuid, final String character, final String className,
                     final int level, final String target, final String where, final int players, final double damage, final double seconds,
                     final double share, final String ascendancy, final String mainSkill, final double mainShare, final double dotShare,
                     final List<String> supports, final List<String> uniques, final Map<String, Double> types) {
            this(at, kind, player, uuid, character, className, level, target, where, players, damage, seconds, share, ascendancy, mainSkill,
                    mainShare, dotShare, supports, uniques, types, 0);
        }

        public double dps() {
            return seconds <= 0 ? 0 : damage / seconds;
        }

        /** 2026-10-25：不套 3 秒地板的每秒傷害（最少算 0.5 秒）；舊紀錄沒有實際秒數就回傳 {@link #dps()}。 */
        public double trueDps() {
            return rawSeconds <= 0 ? dps() : damage / Math.max(0.5, rawSeconds);
        }

        /** 2026-10-25：3 秒內打死（秒殺）；舊紀錄看秒數剛好等於地板。 */
        public boolean instant() {
            return rawSeconds > 0 ? rawSeconds < MIN_SECONDS : seconds <= MIN_SECONDS;
        }
    }

    /** 配裝快照（見 {@link Entry}）。 */
    record Build(String ascendancy, String mainSkill, double mainShare, double dotShare, List<String> supports, List<String> uniques,
                 Map<String, Double> types) {
        static final Build NONE = new Build(null, null, 0, 0, List.of(), List.of(), Map.of());
    }

    private final ExileCore plugin;
    private final Path file;
    private final Deque<Entry> entries = new ArrayDeque<>();
    private final Object writeLock = new Object();
    /** 2026-10-27：每筆加進來的序號（鎖 entries）；清除時記下清到第幾號，背景還沒寫進檔的舊紀錄就不寫回去。 */
    private long sequence;
    /** 2026-10-27：做過的清除（鎖 entries）。 */
    private final List<Clear> clears = new ArrayList<>();

    /** 2026-10-27：一次清除：uuid = null 是全部玩家，character = null 是這個人的全部角色；upTo = 清除當下最後一筆的序號。 */
    private record Clear(String uuid, String character, long upTo) {
    }

    private DpsRecords(final ExileCore plugin) {
        this(plugin, plugin.getDataFolder().toPath().resolve("dps_records.jsonl"));
    }

    /** 測試用（2026-10-27）：plugin 可以是 null（寫檔直接在呼叫的執行緒做）。 */
    DpsRecords(final ExileCore plugin, final Path file) {
        this.plugin = plugin;
        this.file = file;
        load();
    }

    private void warn(final String message, final Object arg, final Throwable error) {
        if (plugin != null) {
            plugin.getSLF4JLogger().warn(message, arg, error);
        }
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
            warn("讀不到輸出紀錄 {}", file, e);
        }
    }

    /** 加一筆（主執行緒）；寫檔在背景。 */
    public void add(final Entry entry) {
        final long seq;
        synchronized (entries) {
            seq = ++sequence;
            entries.addLast(entry);
            while (entries.size() > KEEP) {
                entries.removeFirst();
            }
        }
        final String line = GSON.toJson(entry);
        final Runnable write = () -> {
            synchronized (writeLock) {
                if (clearedBeforeWrite(entry, seq)) {
                    return; // 2026-10-27：還沒寫進檔就被管理員清掉了
                }
                try {
                    Files.createDirectories(file.getParent());
                    try (BufferedWriter out = Files.newBufferedWriter(file, StandardCharsets.UTF_8, StandardOpenOption.CREATE,
                            StandardOpenOption.APPEND)) {
                        out.write(line);
                        out.newLine();
                    }
                } catch (final IOException e) {
                    warn("寫不進輸出紀錄 {}", file, e);
                }
            }
        };
        if (plugin != null && plugin.isEnabled()) {
            plugin.getServer().getScheduler().runTaskAsynchronously(plugin, write);
        } else {
            write.run();
        }
    }

    // ---- 清除（2026-10-27，管理網頁）----

    /** 這筆是不是要清的：uuid = null 是全部玩家（不分大小寫比對），character = null 是這個人的全部角色。 */
    static boolean matches(final Entry entry, final String uuid, final String character) {
        if (entry == null) {
            return false;
        }
        if (uuid != null && !uuid.equalsIgnoreCase(entry.uuid())) {
            return false;
        }
        return character == null || character.equals(entry.character());
    }

    private boolean clearedBeforeWrite(final Entry entry, final long seq) {
        synchronized (entries) {
            for (final Clear clear : clears) {
                if (seq <= clear.upTo() && matches(entry, clear.uuid(), clear.character())) {
                    return true;
                }
            }
        }
        return false;
    }

    /**
     * 清掉輸出紀錄（2026-10-27，站長要的）：uuid = null 清全部玩家；character = null 清這個人的全部角色。
     * 記憶體裡的馬上拿掉（管理網頁的排行下次讀就更新），檔案也重寫（寫到暫存檔再換過去）。回傳清掉幾筆（記憶體裡的）。
     */
    public int clear(final String uuid, final String character) {
        final String who = uuid == null || uuid.isBlank() ? null : uuid.strip();
        final String role = who == null || character == null || character.isBlank() ? null : character.strip();
        int removed = 0;
        synchronized (entries) {
            final java.util.Iterator<Entry> it = entries.iterator();
            while (it.hasNext()) {
                if (matches(it.next(), who, role)) {
                    it.remove();
                    removed++;
                }
            }
            clears.add(new Clear(who, role, sequence));
        }
        synchronized (writeLock) {
            try {
                if (Files.exists(file)) {
                    final List<String> keep = new ArrayList<>();
                    for (final String line : Files.readAllLines(file, StandardCharsets.UTF_8)) {
                        Entry entry = null;
                        try {
                            entry = GSON.fromJson(line, Entry.class);
                        } catch (final RuntimeException ignored) {
                            // 壞掉的一行：清全部時一起丟掉，其他時候留著
                        }
                        if (entry == null ? who != null : !matches(entry, who, role)) {
                            keep.add(line);
                        }
                    }
                    final Path temp = file.resolveSibling(file.getFileName() + ".tmp");
                    Files.write(temp, keep, StandardCharsets.UTF_8);
                    try {
                        Files.move(temp, file, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
                    } catch (final java.nio.file.AtomicMoveNotSupportedException e) {
                        Files.move(temp, file, StandardCopyOption.REPLACE_EXISTING);
                    }
                }
            } catch (final IOException e) {
                warn("清除輸出紀錄時寫不進 {}（記憶體裡已經清掉，重開後可能會回來）", file, e);
            }
        }
        return removed;
    }

    /** 這個人（uuid）最後一筆紀錄上的名字；沒有紀錄是 null（管理網頁的操作紀錄用）。 */
    public String playerName(final String uuid) {
        synchronized (entries) {
            final java.util.Iterator<Entry> it = entries.descendingIterator();
            while (it.hasNext()) {
                final Entry entry = it.next();
                if (entry.uuid() != null && entry.uuid().equalsIgnoreCase(uuid)) {
                    return entry.player();
                }
            }
        }
        return null;
    }

    /** 記憶體裡現在有幾筆。 */
    public int size() {
        synchronized (entries) {
            return entries.size();
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
            final double raw = first == null ? 0 : (now - first) / 20.0;
            final double seconds = Math.max(MIN_SECONDS, raw);
            final Player player = plugin.getServer().getPlayer(each.getKey());
            add(entry(at, "boss", each.getKey(), player, bossName, where, players, each.getValue(), seconds, each.getValue() / total, raw));
        }
    }

    /** 打訓練假人夠長的一段（跟角色頁的「假人實測」同一個條件）。 */
    public void dummy(final Player player, final String dummyName, final double damage, final double seconds) {
        if (seconds <= 0 || damage <= 0) {
            return;
        }
        add(entry(System.currentTimeMillis(), "dummy", player.getUniqueId(), player, dummyName, "訓練假人", 1, damage, seconds, 1, seconds));
    }

    private Entry entry(final long at, final String kind, final UUID uuid, final Player player, final String target, final String where,
                        final int players, final double damage, final double seconds, final double share, final double rawSeconds) {
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
        Build build = Build.NONE;
        if (player != null) {
            try {
                build = build(player);
            } catch (final RuntimeException e) {
                plugin.getSLF4JLogger().warn("記錄 {} 的配裝快照時出錯（這筆照記，只是沒有配裝）", name, e);
            }
        }
        return new Entry(at, kind, name == null ? uuid.toString() : name, uuid.toString(), character, className, level, target, where,
                players, Math.round(damage), Math.round(seconds * 10) / 10.0, Math.round(share * 1000) / 1000.0,
                build.ascendancy(), build.mainSkill(), build.mainShare(), build.dotShare(), build.supports(), build.uniques(), build.types());
    }

    /**
     * 2026-10-19：這個人現在的配裝快照。主技能照戰鬥數據（/dps）這一段每個技能打了多少：Boss 倒下、假人那一段結束時剛好就是這場。
     * 持續傷害（「點燃（持續）」這種）不當主技能，另外算佔比。輔助照技能面板上那個技能插的（同名的第一個）。
     */
    private Build build(final Player player) {
        final PlayerState state = plugin.players() == null ? null : plugin.players().get(player);
        if (state == null || !state.hasCharacter()) {
            return Build.NONE;
        }
        String ascendancy = null;
        if (plugin.ascendancy() != null) {
            final tw.exilecore.ascendancy.AscendancyConfig.Ascendancy chosen = plugin.ascendancy().ascension(state).chosen();
            ascendancy = chosen == null ? null : chosen.name();
        }
        String mainSkill = null;
        double mainShare = 0;
        double dotShare = 0;
        final Map<String, Double> types = new LinkedHashMap<>();
        final CombatMeter.Fight fight = plugin.combat() == null || plugin.combat().hud() == null ? null : plugin.combat().hud().fight(player);
        if (fight != null && fight.total() > 0) {
            final double total = fight.total();
            final tw.exilecore.stats.DamageType[] all = tw.exilecore.stats.DamageType.values();
            final double[] byType = new double[all.length];
            for (final CombatMeter.SkillRow row : fight.skills()) {
                if (row.name().endsWith("（持續）")) {
                    dotShare += row.total() / total;
                } else if (mainSkill == null) {
                    mainSkill = row.name(); // 由多到少排好了
                    mainShare = row.total() / total;
                }
                for (int i = 0; i < all.length && i < row.amounts().length; i++) {
                    byType[i] += row.amounts()[i];
                }
            }
            for (int i = 0; i < all.length; i++) {
                if (byType[i] > 0) {
                    types.put(all[i].tag(), Math.round(byType[i] / total * 1000) / 1000.0);
                }
            }
        }
        final List<String> supports = new ArrayList<>();
        if (mainSkill != null && plugin.skills() != null && state.skills() != null) {
            for (final tw.exilecore.character.SkillLoadout.Entry slot : state.skills().entries()) {
                if (slot == null || slot.skillId() == null) {
                    continue;
                }
                final tw.exilecore.skill.SkillDefinition definition = plugin.skills().config().skill(slot.skillId());
                // 2026-10-25：技能放在宿主（英靈圖騰、星咒圖騰…）孔裡：戰鬥數據記的是裡面那個技能的名字，主技能寫成「宿主＞技能」、輔助用宿主的
                final tw.exilecore.skill.SkillDefinition inner = slot.inner() == null ? null : plugin.skills().config().skill(slot.inner().skillId());
                final boolean hosted = definition != null && inner != null && mainSkill.equals(inner.name());
                if (definition == null || !hosted && !mainSkill.equals(definition.name())) {
                    continue;
                }
                if (hosted) {
                    mainSkill = definition.name() + "＞" + inner.name();
                }
                for (final String raw : slot.supports() == null ? List.<String>of() : slot.supports()) {
                    if (raw == null || raw.isBlank() || raw.startsWith(tw.exilecore.character.SkillLoadout.Entry.INNER_PREFIX)) {
                        continue;
                    }
                    final String id = raw.endsWith("~") ? raw.substring(0, raw.length() - 1) : raw; // 共鳴石嵌的專屬輔助存成代號加 ~
                    final tw.exilecore.skill.SupportDefinition support = plugin.skills().config().support(id);
                    supports.add(support == null ? id : support.name());
                }
                break;
            }
        }
        final List<String> uniques = new ArrayList<>();
        if (plugin.items() != null) {
            for (final org.bukkit.inventory.ItemStack stack : tw.exilecore.character.Gear.equipped(state).values()) {
                if (stack == null || stack.isEmpty()) {
                    continue;
                }
                plugin.items().read(stack).ifPresent(data -> {
                    if (data.unique() != null) {
                        final tw.exilecore.item.UniqueItem unique = plugin.items().repository().unique(data.unique());
                        uniques.add(unique == null ? data.unique() : unique.name());
                    }
                });
            }
        }
        return new Build(ascendancy, mainSkill, Math.round(mainShare * 1000) / 1000.0, Math.round(dotShare * 1000) / 1000.0,
                List.copyOf(supports), List.copyOf(uniques), types);
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

    /** 排好的數字取第 p 分位（0.5 = 中位數；線性內插）。 */
    static double percentile(final List<Double> sorted, final double p) {
        if (sorted.isEmpty()) {
            return 0;
        }
        final double index = p * (sorted.size() - 1);
        final int low = (int) Math.floor(index);
        final int high = Math.min(sorted.size() - 1, low + 1);
        return sorted.get(low) + (sorted.get(high) - sorted.get(low)) * (index - low);
    }

    /** 最常見的幾個：「血肉之軀 12、狂戰士 3」。 */
    static String top(final Map<String, Integer> counts, final int limit) {
        final List<Map.Entry<String, Integer>> list = new ArrayList<>(counts.entrySet());
        list.sort((a, b) -> b.getValue() - a.getValue());
        final List<String> parts = new ArrayList<>();
        for (int i = 0; i < list.size() && i < limit; i++) {
            parts.add(list.get(i).getKey() + " " + list.get(i).getValue());
        }
        return String.join("、", parts);
    }

    /**
     * 打這隻假人的每秒傷害換成打「訓練假人・一般」的倍率（2026-10-17）：兩隻的 boss_factor 相除
     * （無防禦 0.55 ÷ 一般 0.6 ≈ 0.92）。對不到假人、沒有訓練模組時是 1。
     */
    private double normalFactor(final String target) {
        if (plugin == null || plugin.training() == null || target == null) {
            return 1;
        }
        final tw.exilecore.training.DummyConfig config = plugin.training().dummyConfig();
        final tw.exilecore.training.DummyConfig.Dummy normal = config.dummy("normal");
        final double base = normal == null ? tw.exilecore.training.DummyConfig.DEFAULT_BOSS_FACTOR : normal.bossFactor();
        for (final tw.exilecore.training.DummyConfig.Dummy dummy : config.dummies()) {
            if (target.startsWith(dummy.name())) {
                return base <= 0 ? 1 : dummy.bossFactor() / base;
            }
        }
        return 1;
    }

    /** 2026-10-25：最近幾天的紀錄（0 = 全部），舊的在前；平衡報表用。 */
    public List<Entry> recent(final int days) {
        final long since = days <= 0 ? 0 : System.currentTimeMillis() - days * 86_400_000L;
        final List<Entry> list = new ArrayList<>();
        synchronized (entries) {
            for (final Entry entry : entries) {
                if (entry.at() >= since) {
                    list.add(entry);
                }
            }
        }
        return list;
    }

    /**
     * 最近的紀錄與每個人的平均。days = 只看最近幾天（0 = 全部）。
     * 平均每秒傷害 = 總傷害 ÷ 總秒數（打得久的場次比重大；只打一下就倒的不會拉高）。
     */
    public JsonObject json(final int days) {
        final long since = days <= 0 ? 0 : System.currentTimeMillis() - days * 86_400_000L;
        final JsonArray rows = new JsonArray();
        final Map<String, double[]> boss = new LinkedHashMap<>();   // uuid → {傷害, 秒數, 場數, 最高每秒}
        final Map<String, double[]> dummy = new HashMap<>();        // uuid → {最近的每秒, 最高的每秒, 次數}（換算成一般靶）
        final Map<String, Entry> dummyBest = new HashMap<>();       // uuid → 換算後最高的那一筆
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
            final double normal = "boss".equals(entry.kind()) ? entry.dps() : entry.dps() * normalFactor(entry.target());
            if (rows.size() < 2000) {
                final JsonObject json = GSON.toJsonTree(entry).getAsJsonObject();
                if (!"boss".equals(entry.kind())) {
                    json.addProperty("normal", Math.round(normal)); // 2026-10-17：換算成一般靶（網頁的排序、「一般靶」括號）
                }
                rows.add(json);
            }
            latest.putIfAbsent(entry.uuid(), entry);
            if ("boss".equals(entry.kind())) {
                final double[] sum = boss.computeIfAbsent(entry.uuid(), key -> new double[4]);
                sum[0] += entry.damage();
                sum[1] += entry.seconds();
                sum[2]++;
                sum[3] = Math.max(sum[3], entry.dps());
            } else {
                final double[] sum = dummy.computeIfAbsent(entry.uuid(), key -> new double[] {normal, 0, 0});
                if (normal > sum[1]) {
                    sum[1] = normal;
                    dummyBest.put(entry.uuid(), entry);
                }
                sum[2]++;
            }
        }
        final JsonArray players = new JsonArray();
        for (final Map.Entry<String, Entry> each : latest.entrySet()) {
            final Entry last = each.getValue();
            final JsonObject row = new JsonObject();
            row.addProperty("uuid", each.getKey()); // 2026-10-17：網頁用 uuid 把假人的欄位對到人（以前沒給，假人欄全擠成一個人）
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
                final Entry best = dummyBest.get(each.getKey());
                if (best != null) {
                    row.addProperty("dummyBestRaw", Math.round(best.dps()));
                    row.addProperty("dummyBestTarget", best.target());
                    row.addProperty("dummyBestCharacter", best.character());
                    row.addProperty("dummyBestAt", best.at());
                }
            }
            players.add(row);
        }
        // 2026-10-19：依主技能分組（流派平衡）：只算 Boss、有配裝快照的紀錄；中位數比平均不怕少數幾個特別強的人
        final Map<String, List<Entry>> bySkill = new LinkedHashMap<>();
        for (final Entry entry : list) {
            if (entry.at() >= since && "boss".equals(entry.kind()) && entry.mainSkill() != null) {
                bySkill.computeIfAbsent(entry.mainSkill(), key -> new ArrayList<>()).add(entry);
            }
        }
        final JsonArray skills = new JsonArray();
        for (final Map.Entry<String, List<Entry>> group : bySkill.entrySet()) {
            final List<Entry> fights = group.getValue();
            final List<Double> dps = new ArrayList<>();
            final java.util.Set<String> people = new java.util.HashSet<>();
            final Map<String, Integer> ascendancies = new HashMap<>();
            final Map<String, Integer> supports = new HashMap<>();
            double share = 0;
            double dot = 0;
            for (final Entry entry : fights) {
                dps.add(entry.dps());
                people.add(entry.uuid());
                share += entry.mainShare();
                dot += entry.dotShare();
                if (entry.ascendancy() != null) {
                    ascendancies.merge(entry.ascendancy(), 1, Integer::sum);
                }
                if (entry.supports() != null) {
                    for (final String support : entry.supports()) {
                        supports.merge(support, 1, Integer::sum);
                    }
                }
            }
            dps.sort(Double::compare);
            final JsonObject row = new JsonObject();
            row.addProperty("skill", group.getKey());
            row.addProperty("fights", fights.size());
            row.addProperty("players", people.size());
            row.addProperty("median", Math.round(percentile(dps, 0.5)));
            row.addProperty("p90", Math.round(percentile(dps, 0.9)));
            row.addProperty("best", Math.round(dps.getLast()));
            row.addProperty("mainShare", Math.round(share / fights.size() * 1000) / 1000.0);
            row.addProperty("dotShare", Math.round(dot / fights.size() * 1000) / 1000.0);
            row.addProperty("ascendancies", top(ascendancies, 3));
            row.addProperty("supports", top(supports, 5));
            skills.add(row);
        }
        final JsonObject root = new JsonObject();
        root.add("skills", skills);
        root.add("players", players);
        root.add("rows", rows);
        root.addProperty("total", list.size());
        return root;
    }
}
