package tw.exilecore.balance;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import tw.exilecore.ExileCore;
import tw.exilecore.combat.DpsRecords;
import tw.exilecore.item.UniqueItem;
import tw.exilecore.passive.PassiveNode;
import tw.exilecore.passive.PassiveTree;
import tw.exilecore.passive.TreeCode;
import tw.exilecore.skill.SkillDefinition;
import tw.exilecore.skill.SupportDefinition;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.Timestamp;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.TreeMap;
import java.util.concurrent.CompletableFuture;

/**
 * 平衡報表（2026-10-25）：把真實數據彙整成一份給每月大平衡看的檔案（{@code plugins/ExileCore/balance/YYYY-MM-DD.json} 與 {@code .md}，
 * 另外一份 {@code latest.*}）。每月 1 號自動產生一次（{@link #monthly}），也可以 {@code /ec balance export} 手動產生。
 * <ul>
 *   <li>角色：資料庫 character_profiles 的快照（網頁角色頁那份：等級、昇華、技能與輔助、裝備的傳奇、天賦代碼、估計每秒傷害、假人實測）。
 *       只算 {@value #ACTIVE_DAYS} 天內更新過的（有上線）。</li>
 *   <li>戰鬥：輸出紀錄（{@link DpsRecords}）最近 {@value #RECORD_DAYS} 天的 Boss 場次：每個主技能、昇華、Boss 的中位數與 90 百分位。</li>
 *   <li>靜態：所有技能的係數（20 級）與 PoE2 的比值（{@code balance/poe2_skills.csv}）、輔助、傳奇的清單，讓報表自己就能對照。</li>
 *   <li>異常：實測每秒傷害超過同等級區間中位數 3 倍的技能、沒人用的技能與輔助。</li>
 * </ul>
 * 玩家名字會列出來（看異常值要追人）；這份檔只有管理員拿得到。
 */
public final class BalanceExport {

    public static final String FOLDER = "balance";
    static final int ACTIVE_DAYS = 60;
    static final int RECORD_DAYS = 30;
    /** 等級區間（上限）：1–40、41–70、71–90、91+。 */
    static final int[] BANDS = {40, 70, 90, Integer.MAX_VALUE};
    static final String[] BAND_NAMES = {"1-40", "41-70", "71-90", "91+"};
    /** 異常：實測每秒傷害超過同區間中位數幾倍。 */
    static final double OUTLIER_FACTOR = 3.0;

    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().disableHtmlEscaping().create();

    private final ExileCore plugin;

    public BalanceExport(final ExileCore plugin) {
        this.plugin = plugin;
    }

    /** 一個角色的快照（從 character_profiles 讀出來）。 */
    record Character(String name, String classId, int level, String ascendancy, long updated, JsonObject data) {
        int band() {
            return bandOf(level);
        }
    }

    static int bandOf(final int level) {
        for (int i = 0; i < BANDS.length; i++) {
            if (level <= BANDS[i]) {
                return i;
            }
        }
        return BANDS.length - 1;
    }

    // ---- 排程 ----

    /** 每月 1 號（或那之後第一次啟動）自動產生一份；每小時看一次這個月的檔有沒有。 */
    public void monthly() {
        plugin.repeat("balance-export", () -> {
            final LocalDate today = LocalDate.now(ZoneId.systemDefault());
            final Path monthFile = folder().resolve(String.format(Locale.ROOT, "%04d-%02d.md", today.getYear(), today.getMonthValue()));
            if (Files.exists(monthFile)) {
                return;
            }
            export(null).whenComplete((files, error) -> {
                if (error == null && files != null && files.length > 1) {
                    try {
                        Files.copy(files[1], monthFile);
                        plugin.getSLF4JLogger().info("平衡報表：這個月的寫在 {}", monthFile);
                    } catch (final IOException e) {
                        plugin.getSLF4JLogger().warn("平衡報表：{} 寫不進去", monthFile, e);
                    }
                }
            });
        }, 20L * 60, 20L * 60 * 60);
    }

    private Path folder() {
        return plugin.dataFolder().resolve(FOLDER);
    }

    // ---- 產生 ----

    /**
     * 產生一份：讀資料庫（背景）→ 彙整 → 寫檔。回傳 {json 檔, md 檔}。
     *
     * @param notify 做完要回報的人（null = 只寫 log）
     */
    public CompletableFuture<Path[]> export(final java.util.function.Consumer<String> notify) {
        final List<DpsRecords.Entry> records = DpsRecords.get(plugin).recent(RECORD_DAYS);
        final JsonObject statics = statics();
        final PassiveTree tree = plugin.passives() == null ? null : plugin.passives().tree();
        return plugin.database().async(this::loadCharacters).thenApply(characters -> {
            final JsonObject root = new JsonObject();
            root.addProperty("generated", System.currentTimeMillis());
            root.addProperty("activeDays", ACTIVE_DAYS);
            root.addProperty("recordDays", RECORD_DAYS);
            final List<String> md = new ArrayList<>();
            md.add("# 星骸 平衡報表　" + LocalDate.now(ZoneId.systemDefault()));
            md.add("");
            md.add("活躍角色＝" + ACTIVE_DAYS + " 天內上線過；實測＝輸出紀錄最近 " + RECORD_DAYS + " 天的 Boss 場次（每秒傷害＝傷害÷秒數）。"
                    + "等級區間：1-40、41-70、71-90、91+。");
            overview(characters, root, md);
            skills(characters, records, root, md);
            supports(characters, root, md);
            uniques(characters, root, md);
            passives(characters, tree, root, md);
            bosses(records, root, md);
            outliers(characters, records, root, md);
            root.add("static", statics);
            poe2Table(statics, md);
            mapRuns(BalanceRecords.get(plugin).recentMaps(RECORD_DAYS), root, md); // 2026-10-25：第二步
            deaths(BalanceRecords.get(plugin).recentDeaths(RECORD_DAYS), root, md);
            return write(root, md);
        }).whenComplete((files, error) -> {
            if (error != null) {
                plugin.getSLF4JLogger().warn("平衡報表產生失敗", error);
                if (notify != null) {
                    notify.accept("<red>平衡報表產生失敗：" + error.getMessage());
                }
            } else if (notify != null) {
                notify.accept("<gray>平衡報表寫在 <white>plugins/ExileCore/" + FOLDER + "/" + files[1].getFileName()
                        + "<gray>（JSON：" + files[0].getFileName() + "；latest.md 是同一份）");
            }
        });
    }

    private Path[] write(final JsonObject root, final List<String> md) {
        try {
            final Path folder = folder();
            Files.createDirectories(folder);
            final String stamp = LocalDate.now(ZoneId.systemDefault()).toString();
            final Path json = folder.resolve(stamp + ".json");
            final Path text = folder.resolve(stamp + ".md");
            Files.writeString(json, GSON.toJson(root), StandardCharsets.UTF_8);
            Files.write(text, md, StandardCharsets.UTF_8);
            Files.writeString(folder.resolve("latest.json"), GSON.toJson(root), StandardCharsets.UTF_8);
            Files.write(folder.resolve("latest.md"), md, StandardCharsets.UTF_8);
            return new Path[] {json, text};
        } catch (final IOException e) {
            throw new java.io.UncheckedIOException(e);
        }
    }

    // ---- 讀角色 ----

    private List<Character> loadCharacters(final java.sql.Connection connection) throws java.sql.SQLException {
        final List<Character> list = new ArrayList<>();
        final long since = System.currentTimeMillis() - ACTIVE_DAYS * 86_400_000L;
        try (PreparedStatement select = connection.prepareStatement("SELECT p.name, p.class_id, p.level, p.ascendancy, p.updated_at, p.data "
                + "FROM character_profiles p JOIN characters c ON c.id = p.character_id WHERE c.deleted_at IS NULL");
             ResultSet result = select.executeQuery()) {
            while (result.next()) {
                final Timestamp updated = result.getTimestamp("updated_at");
                final long at = updated == null ? 0 : updated.getTime();
                if (at < since) {
                    continue;
                }
                JsonObject data;
                try {
                    data = JsonParser.parseString(result.getString("data")).getAsJsonObject();
                } catch (final RuntimeException e) {
                    data = new JsonObject();
                }
                list.add(new Character(result.getString("name"), result.getString("class_id"), result.getInt("level"),
                        result.getString("ascendancy"), at, data));
            }
        }
        return list;
    }

    // ---- 各段 ----

    private void overview(final List<Character> characters, final JsonObject root, final List<String> md) {
        final Map<String, List<Integer>> byClass = new TreeMap<>();
        final Map<String, List<Integer>> byAsc = new TreeMap<>();
        final int[] bands = new int[BANDS.length];
        for (final Character c : characters) {
            byClass.computeIfAbsent(className(c.classId()), k -> new ArrayList<>()).add(c.level());
            byAsc.computeIfAbsent(c.ascendancy() == null ? "（未昇華）" : c.ascendancy(), k -> new ArrayList<>()).add(c.level());
            bands[c.band()]++;
        }
        final JsonObject json = new JsonObject();
        json.addProperty("characters", characters.size());
        json.add("bands", GSON.toJsonTree(bands));
        json.add("classes", levelStats(byClass));
        json.add("ascendancies", levelStats(byAsc));
        root.add("overview", json);
        md.add("");
        md.add("## 1. 概況");
        md.add("");
        md.add("活躍角色 " + characters.size() + " 個：等級 1-40 " + bands[0] + "、41-70 " + bands[1] + "、71-90 " + bands[2] + "、91+ " + bands[3]);
        md.add("");
        md.add("| 職業 | 人數 | 等級中位 | 最高 |");
        md.add("|---|---|---|---|");
        for (final Map.Entry<String, List<Integer>> e : byClass.entrySet()) {
            md.add("| " + e.getKey() + " | " + e.getValue().size() + " | " + median(e.getValue()) + " | " + Collections.max(e.getValue()) + " |");
        }
        md.add("");
        md.add("| 昇華 | 人數 | 等級中位 | 最高 |");
        md.add("|---|---|---|---|");
        for (final Map.Entry<String, List<Integer>> e : byAsc.entrySet()) {
            md.add("| " + e.getKey() + " | " + e.getValue().size() + " | " + median(e.getValue()) + " | " + Collections.max(e.getValue()) + " |");
        }
    }

    private JsonObject levelStats(final Map<String, List<Integer>> groups) {
        final JsonObject json = new JsonObject();
        for (final Map.Entry<String, List<Integer>> e : groups.entrySet()) {
            final JsonObject g = new JsonObject();
            g.addProperty("count", e.getValue().size());
            g.addProperty("medianLevel", median(e.getValue()));
            g.addProperty("maxLevel", Collections.max(e.getValue()));
            json.add(e.getKey(), g);
        }
        return json;
    }

    /** 技能：裝備率（活躍角色裝著的）、估計每秒傷害（角色頁的估計，分等級區間）、實測（輸出紀錄的主技能）。 */
    private void skills(final List<Character> characters, final List<DpsRecords.Entry> records, final JsonObject root, final List<String> md) {
        final Map<String, String> names = new HashMap<>();
        if (plugin.skills() != null) {
            for (final SkillDefinition d : plugin.skills().config().all()) {
                names.put(d.id(), d.name());
            }
        }
        final Map<String, Integer> equipped = new HashMap<>();              // 技能代號 → 幾個角色裝著
        final Map<String, Map<String, Integer>> withSupports = new HashMap<>(); // 技能代號 → 輔助代號 → 次數
        final Map<String, List<Double>[]> estimated = new HashMap<>();      // 技能名 → 各等級區間的估計每秒傷害
        for (final Character c : characters) {
            for (final String[] pair : equippedSkills(c)) {
                equipped.merge(pair[0], 1, Integer::sum);
                for (final String support : pair[1].split(",")) {
                    if (!support.isBlank()) {
                        withSupports.computeIfAbsent(pair[0], k -> new HashMap<>()).merge(support, 1, Integer::sum);
                    }
                }
            }
            if (c.data().has("dps")) {
                for (final JsonElement each : c.data().getAsJsonArray("dps")) {
                    final JsonObject row = each.getAsJsonObject();
                    if (!row.has("name") || !row.has("dps")) {
                        continue;
                    }
                    @SuppressWarnings("unchecked")
                    final List<Double>[] bands = estimated.computeIfAbsent(row.get("name").getAsString(), k -> new List[BANDS.length]);
                    if (bands[c.band()] == null) {
                        bands[c.band()] = new ArrayList<>();
                    }
                    bands[c.band()].add(row.get("dps").getAsDouble());
                }
            }
        }
        // 實測：主技能
        final Map<String, List<Double>[]> measured = new HashMap<>();
        final Map<String, Integer> measuredFights = new HashMap<>();
        final Map<String, Integer> measuredInstant = new HashMap<>(); // 2026-10-25：3 秒內打死的場次
        for (final DpsRecords.Entry e : records) {
            if (!"boss".equals(e.kind()) || e.mainSkill() == null) {
                continue;
            }
            @SuppressWarnings("unchecked")
            final List<Double>[] bands = measured.computeIfAbsent(e.mainSkill(), k -> new List[BANDS.length]);
            final int band = bandOf(e.level());
            if (bands[band] == null) {
                bands[band] = new ArrayList<>();
            }
            bands[band].add(e.trueDps()); // 2026-10-25：不套 3 秒地板
            measuredFights.merge(e.mainSkill(), 1, Integer::sum);
            if (e.instant()) {
                measuredInstant.merge(e.mainSkill(), 1, Integer::sum);
            }
        }
        final JsonObject json = new JsonObject();
        final List<String> ids = new ArrayList<>(names.keySet());
        ids.sort((a, b) -> equipped.getOrDefault(b, 0) - equipped.getOrDefault(a, 0));
        md.add("");
        md.add("## 2. 技能");
        md.add("");
        md.add("裝備率＝活躍角色裝著這個技能的比例（0% 但有實測＝角色頁快照沒更新）；估計＝角色頁的每秒傷害估計（中位數，按等級區間）；"
                + "實測＝輸出紀錄主技能的每秒傷害（中位／90%，用實際秒數算、不套 3 秒地板）；秒殺＝3 秒內打死的場次比例（高的話實測只是下限）。"
                + "「宿主＞技能」＝放在英靈圖騰、星咒圖騰裡的。");
        md.add("");
        md.add("| 技能 | 裝備率 | 估計 1-40 | 41-70 | 71-90 | 91+ | 實測場數 | 秒殺 | 實測 41-70 中位／90% | 71-90 | 91+ | 常見輔助 |");
        md.add("|---|---|---|---|---|---|---|---|---|---|---|---|");
        for (final String id : ids) {
            final SkillDefinition d = plugin.skills().config().skill(id);
            if (d == null || d.hidden()) {
                continue;
            }
            final String name = d.name();
            final int count = equipped.getOrDefault(id, 0);
            final List<Double>[] est = estimated.get(name);
            final List<Double>[] mea = measured.get(name);
            final JsonObject s = new JsonObject();
            s.addProperty("name", name);
            s.addProperty("equipped", count);
            s.addProperty("equippedRate", characters.isEmpty() ? 0 : (double) count / characters.size());
            s.add("estimatedMedian", bandArray(est, false));
            s.add("measuredMedian", bandArray(mea, false));
            s.add("measuredP90", bandArray(mea, true));
            s.addProperty("measuredFights", measuredFights.getOrDefault(name, 0));
            s.addProperty("measuredInstant", measuredInstant.getOrDefault(name, 0));
            final JsonObject sup = new JsonObject();
            final List<Map.Entry<String, Integer>> top = topEntries(withSupports.getOrDefault(id, Map.of()), 5);
            final List<String> supNames = new ArrayList<>();
            for (final Map.Entry<String, Integer> e : top) {
                sup.addProperty(e.getKey(), e.getValue());
                supNames.add(supportName(e.getKey()) + "×" + e.getValue());
            }
            s.add("supports", sup);
            json.add(id, s);
            if (count == 0 && measuredFights.getOrDefault(name, 0) == 0) {
                continue; // 沒人用的另外列在「異常」
            }
            md.add("| " + name + " | " + percent(count, characters.size()) + (count == 0 ? "（快照沒更新）" : "") + " | " + fmt(med(est, 0)) + " | "
                    + fmt(med(est, 1)) + " | " + fmt(med(est, 2)) + " | " + fmt(med(est, 3)) + " | " + measuredFights.getOrDefault(name, 0) + " | "
                    + percent(measuredInstant.getOrDefault(name, 0), measuredFights.getOrDefault(name, 0)) + " | "
                    + fmt(med(mea, 1)) + "／" + fmt(p90(mea, 1)) + " | " + fmt(med(mea, 2)) + "／" + fmt(p90(mea, 2)) + " | "
                    + fmt(med(mea, 3)) + "／" + fmt(p90(mea, 3)) + " | " + String.join("、", supNames) + " |");
        }
        // 2026-10-25：「宿主＞技能」（英靈圖騰＞千鈞砸…）：不是技能定義的名字，另外列
        final List<String> hosted = new ArrayList<>();
        for (final String key : measured.keySet()) {
            if (key.contains("＞")) {
                hosted.add(key);
            }
        }
        hosted.sort((a, b) -> measuredFights.getOrDefault(b, 0) - measuredFights.getOrDefault(a, 0));
        for (final String key : hosted) {
            final List<Double>[] mea = measured.get(key);
            final JsonObject s = new JsonObject();
            s.addProperty("name", key);
            s.addProperty("hosted", true);
            s.add("measuredMedian", bandArray(mea, false));
            s.add("measuredP90", bandArray(mea, true));
            s.addProperty("measuredFights", measuredFights.getOrDefault(key, 0));
            s.addProperty("measuredInstant", measuredInstant.getOrDefault(key, 0));
            json.add(key, s);
            md.add("| " + key + " | - | - | - | - | - | " + measuredFights.getOrDefault(key, 0) + " | "
                    + percent(measuredInstant.getOrDefault(key, 0), measuredFights.getOrDefault(key, 0)) + " | "
                    + fmt(med(mea, 1)) + "／" + fmt(p90(mea, 1)) + " | " + fmt(med(mea, 2)) + "／" + fmt(p90(mea, 2)) + " | "
                    + fmt(med(mea, 3)) + "／" + fmt(p90(mea, 3)) + " | （宿主的輔助見異常值表） |");
        }
        root.add("skills", json);
    }

    /** 角色裝著的技能：{技能代號, "輔助代號,輔助代號…"}（calc.skills；共鳴的 ~、宿主孔裡的 @… 去掉）。 */
    private static List<String[]> equippedSkills(final Character c) {
        final List<String[]> list = new ArrayList<>();
        if (!c.data().has("calc") || !c.data().getAsJsonObject("calc").has("skills")) {
            return list;
        }
        for (final JsonElement each : c.data().getAsJsonObject("calc").getAsJsonArray("skills")) {
            final JsonObject skill = each.getAsJsonObject();
            if (!skill.has("id")) {
                continue;
            }
            final List<String> supports = new ArrayList<>();
            if (skill.has("supports")) {
                for (final JsonElement s : skill.getAsJsonArray("supports")) {
                    String id = s.getAsString();
                    if (id.startsWith("@")) {
                        continue;
                    }
                    if (id.endsWith("~")) {
                        id = id.substring(0, id.length() - 1);
                    }
                    if (!id.isBlank()) {
                        supports.add(id);
                    }
                }
            }
            list.add(new String[] {skill.get("id").getAsString(), String.join(",", supports)});
        }
        return list;
    }

    private void supports(final List<Character> characters, final JsonObject root, final List<String> md) {
        final Map<String, Integer> used = new HashMap<>();
        final Map<String, Map<String, Integer>> onSkills = new HashMap<>();
        for (final Character c : characters) {
            for (final String[] pair : equippedSkills(c)) {
                for (final String support : pair[1].split(",")) {
                    if (!support.isBlank()) {
                        used.merge(support, 1, Integer::sum);
                        onSkills.computeIfAbsent(support, k -> new HashMap<>()).merge(pair[0], 1, Integer::sum);
                    }
                }
            }
        }
        final JsonObject json = new JsonObject();
        md.add("");
        md.add("## 3. 輔助寶石");
        md.add("");
        md.add("使用次數＝活躍角色掛著這顆的技能數（同一個人掛在兩個技能算 2）。");
        md.add("");
        md.add("| 輔助 | 階 | 使用次數 | 掛在哪些技能 |");
        md.add("|---|---|---|---|");
        final List<SupportDefinition> all = new ArrayList<>(plugin.skills().config().supports());
        all.sort((a, b) -> used.getOrDefault(b.id(), 0) - used.getOrDefault(a.id(), 0));
        for (final SupportDefinition s : all) {
            final int count = used.getOrDefault(s.id(), 0);
            final JsonObject j = new JsonObject();
            j.addProperty("name", s.name());
            j.addProperty("tier", s.tier());
            j.addProperty("used", count);
            final JsonObject on = new JsonObject();
            final List<String> onNames = new ArrayList<>();
            for (final Map.Entry<String, Integer> e : topEntries(onSkills.getOrDefault(s.id(), Map.of()), 5)) {
                on.addProperty(e.getKey(), e.getValue());
                onNames.add(skillName(e.getKey()) + "×" + e.getValue());
            }
            j.add("onSkills", on);
            json.add(s.id(), j);
            if (count > 0) {
                md.add("| " + s.name() + " | " + s.tier() + " | " + count + " | " + String.join("、", onNames) + " |");
            }
        }
        root.add("supports", json);
    }

    private void uniques(final List<Character> characters, final JsonObject root, final List<String> md) {
        final Map<String, Integer> worn = new HashMap<>();
        for (final Character c : characters) {
            for (final String id : wornUniques(c)) {
                worn.merge(id, 1, Integer::sum);
            }
        }
        final JsonObject json = new JsonObject();
        md.add("");
        md.add("## 4. 傳奇");
        md.add("");
        md.add("| 傳奇 | 穿著的角色 | 比例 |");
        md.add("|---|---|---|");
        final List<UniqueItem> all = new ArrayList<>(plugin.items().repository().uniques());
        all.sort((a, b) -> worn.getOrDefault(b.id(), 0) - worn.getOrDefault(a.id(), 0));
        int unused = 0;
        for (final UniqueItem u : all) {
            final int count = worn.getOrDefault(u.id(), 0);
            final JsonObject j = new JsonObject();
            j.addProperty("name", u.name());
            j.addProperty("worn", count);
            json.add(u.id(), j);
            if (count > 0) {
                md.add("| " + u.name() + " | " + count + " | " + percent(count, characters.size()) + " |");
            } else {
                unused++;
            }
        }
        md.add("");
        md.add("沒人穿的傳奇：" + unused + " 件（清單在 JSON 的 uniques）。");
        root.add("uniques", json);
    }

    /** 角色身上的傳奇（裝備欄＋珠寶；icon 是 u:代號）。 */
    private static List<String> wornUniques(final Character c) {
        final List<String> list = new ArrayList<>();
        for (final String key : new String[] {"gear", "charms", "flasks"}) {
            if (!c.data().has(key)) {
                continue;
            }
            for (final JsonElement each : c.data().getAsJsonArray(key)) {
                final JsonObject slot = each.getAsJsonObject();
                if (slot.has("item")) {
                    addUnique(slot.getAsJsonObject("item"), list);
                }
            }
        }
        if (c.data().has("jewels")) {
            for (final JsonElement each : c.data().getAsJsonArray("jewels")) {
                addUnique(each.getAsJsonObject(), list);
            }
        }
        return list;
    }

    private static void addUnique(final JsonObject item, final List<String> list) {
        if (item.has("icon") && item.get("icon").getAsString().startsWith("u:")) {
            list.add(item.get("icon").getAsString().substring(2));
        }
    }

    private void passives(final List<Character> characters, final PassiveTree tree, final JsonObject root, final List<String> md) {
        if (tree == null) {
            return;
        }
        final Map<String, Integer> allocated = new HashMap<>();
        int decoded = 0;
        for (final Character c : characters) {
            if (!c.data().has("treeCode")) {
                continue;
            }
            try {
                final TreeCode.Decoded d = TreeCode.decode(tree, c.data().get("treeCode").getAsString());
                decoded++;
                for (final String id : d.nodes()) {
                    allocated.merge(id, 1, Integer::sum);
                }
            } catch (final TreeCode.InvalidCode | RuntimeException ignored) {
                // 舊版的代碼解不開就跳過
            }
        }
        final JsonObject json = new JsonObject();
        md.add("");
        md.add("## 5. 天賦（基石、核心的配置率；" + decoded + " 個角色的天賦代碼解得開）");
        md.add("");
        md.add("| 類型 | 天賦 | 配置人數 | 比例 |");
        md.add("|---|---|---|---|");
        final List<PassiveNode> nodes = new ArrayList<>();
        for (final PassiveNode node : tree.nodes()) {
            if (node.type() == PassiveNode.Type.KEYSTONE || node.type() == PassiveNode.Type.NOTABLE) {
                nodes.add(node);
            }
        }
        nodes.sort((a, b) -> allocated.getOrDefault(b.id(), 0) - allocated.getOrDefault(a.id(), 0));
        int unusedNotables = 0;
        for (final PassiveNode node : nodes) {
            final int count = allocated.getOrDefault(node.id(), 0);
            final JsonObject j = new JsonObject();
            j.addProperty("name", node.name());
            j.addProperty("type", node.type().name().toLowerCase(Locale.ROOT));
            j.addProperty("allocated", count);
            json.add(node.id(), j);
            if (count > 0) {
                md.add("| " + node.type().displayName() + " | " + node.name() + " | " + count + " | " + percent(count, decoded) + " |");
            } else {
                unusedNotables++;
            }
        }
        md.add("");
        md.add("沒人配的基石／核心：" + unusedNotables + " 個（清單在 JSON 的 passives）。");
        root.add("passives", json);
    }

    private void bosses(final List<DpsRecords.Entry> records, final JsonObject root, final List<String> md) {
        final Map<String, List<DpsRecords.Entry>> byBoss = new TreeMap<>();
        final Map<String, List<Double>[]> byAsc = new TreeMap<>(); // 2026-10-25：按等級區間分
        for (final DpsRecords.Entry e : records) {
            if (!"boss".equals(e.kind())) {
                continue;
            }
            byBoss.computeIfAbsent(e.target() == null ? "?" : e.target(), k -> new ArrayList<>()).add(e);
            @SuppressWarnings("unchecked")
            final List<Double>[] bands = byAsc.computeIfAbsent(e.ascendancy() == null ? "（未昇華）" : e.ascendancy(), k -> new List[BANDS.length]);
            final int band = bandOf(e.level());
            if (bands[band] == null) {
                bands[band] = new ArrayList<>();
            }
            bands[band].add(e.trueDps());
        }
        final JsonObject json = new JsonObject();
        md.add("");
        md.add("## 6. Boss（最近 " + RECORD_DAYS + " 天）");
        md.add("");
        md.add("秒數與每秒傷害用實際秒數（不套 3 秒地板）；秒殺率＝3 秒內打死的場次比例。");
        md.add("");
        md.add("| Boss | 場次（人次） | 秒數中位 | 秒殺率 | 平均人數 | 每人每秒傷害中位 | 最高每秒傷害（誰） |");
        md.add("|---|---|---|---|---|---|---|");
        for (final Map.Entry<String, List<DpsRecords.Entry>> e : byBoss.entrySet()) {
            final List<Double> seconds = new ArrayList<>();
            final List<Double> dps = new ArrayList<>();
            double players = 0;
            int instant = 0;
            DpsRecords.Entry best = null;
            for (final DpsRecords.Entry r : e.getValue()) {
                seconds.add(r.rawSeconds() > 0 ? r.rawSeconds() : r.seconds());
                dps.add(r.trueDps());
                players += r.players();
                if (r.instant()) {
                    instant++;
                }
                if (best == null || r.trueDps() > best.trueDps()) {
                    best = r;
                }
            }
            final JsonObject j = new JsonObject();
            j.addProperty("fights", e.getValue().size());
            j.addProperty("medianSeconds", medianD(seconds));
            j.addProperty("instant", instant);
            j.addProperty("avgPlayers", players / e.getValue().size());
            j.addProperty("medianDps", medianD(dps));
            j.addProperty("bestDps", best.trueDps());
            j.addProperty("bestPlayer", best.player());
            j.addProperty("bestSkill", best.mainSkill());
            json.add(e.getKey(), j);
            md.add("| " + e.getKey() + " | " + e.getValue().size() + " | " + fmt(medianD(seconds)) + " | " + percent(instant, e.getValue().size()) + " | "
                    + String.format(Locale.ROOT, "%.1f", players / e.getValue().size()) + " | " + fmt(medianD(dps)) + " | "
                    + fmt(best.trueDps()) + "（" + best.player() + "・" + (best.mainSkill() == null ? "?" : best.mainSkill()) + "） |");
        }
        md.add("");
        md.add("| 昇華 | 人次 | 41-70 中位／90% | 71-90 | 91+ |");
        md.add("|---|---|---|---|---|");
        final JsonObject asc = new JsonObject();
        for (final Map.Entry<String, List<Double>[]> e : byAsc.entrySet()) {
            int fights = 0;
            for (final List<Double> band : e.getValue()) {
                fights += band == null ? 0 : band.size();
            }
            final JsonObject j = new JsonObject();
            j.addProperty("fights", fights);
            j.add("medianDps", bandArray(e.getValue(), false));
            j.add("p90Dps", bandArray(e.getValue(), true));
            asc.add(e.getKey(), j);
            md.add("| " + e.getKey() + " | " + fights + " | " + fmt(med(e.getValue(), 1)) + "／" + fmt(p90(e.getValue(), 1)) + " | "
                    + fmt(med(e.getValue(), 2)) + "／" + fmt(p90(e.getValue(), 2)) + " | " + fmt(med(e.getValue(), 3)) + "／" + fmt(p90(e.getValue(), 3)) + " |");
        }
        root.add("bosses", json);
        root.add("bossesByAscendancy", asc);
    }

    private void outliers(final List<Character> characters, final List<DpsRecords.Entry> records, final JsonObject root, final List<String> md) {
        md.add("");
        md.add("## 7. 異常值");
        md.add("");
        // 實測：同等級區間所有場次的中位數 × 3 以上
        @SuppressWarnings("unchecked")
        final List<Double>[] all = new List[BANDS.length];
        for (final DpsRecords.Entry e : records) {
            if ("boss".equals(e.kind())) {
                final int band = bandOf(e.level());
                if (all[band] == null) {
                    all[band] = new ArrayList<>();
                }
                all[band].add(e.trueDps());
            }
        }
        final JsonArray high = new JsonArray();
        md.add("### 實測每秒傷害超過同等級區間中位數 " + (int) OUTLIER_FACTOR + " 倍的場次（最多 30 筆）");
        md.add("");
        md.add("| 玩家 | 角色 | 等級 | 昇華 | 主技能 | 輔助 | 傳奇 | Boss | 每秒傷害 | 區間中位 |"); // 每秒傷害用實際秒數
        md.add("|---|---|---|---|---|---|---|---|---|---|");
        final List<DpsRecords.Entry> sorted = new ArrayList<>(records);
        sorted.sort((a, b) -> Double.compare(b.trueDps(), a.trueDps()));
        int shown = 0;
        for (final DpsRecords.Entry e : sorted) {
            if (!"boss".equals(e.kind())) {
                continue;
            }
            final int band = bandOf(e.level());
            final double median = all[band] == null ? 0 : medianD(all[band]);
            if (median <= 0 || e.trueDps() < median * OUTLIER_FACTOR) {
                continue;
            }
            final JsonObject j = new JsonObject();
            j.addProperty("player", e.player());
            j.addProperty("character", e.character());
            j.addProperty("level", e.level());
            j.addProperty("ascendancy", e.ascendancy());
            j.addProperty("mainSkill", e.mainSkill());
            j.add("supports", GSON.toJsonTree(e.supports()));
            j.add("uniques", GSON.toJsonTree(e.uniques()));
            j.addProperty("target", e.target());
            j.addProperty("dps", e.trueDps());
            j.addProperty("seconds", e.rawSeconds() > 0 ? e.rawSeconds() : e.seconds());
            j.addProperty("bandMedian", median);
            high.add(j);
            if (shown++ < 30) {
                md.add("| " + e.player() + " | " + e.character() + " | " + e.level() + " | " + nz(e.ascendancy()) + " | " + nz(e.mainSkill()) + " | "
                        + String.join("、", e.supports() == null ? List.of() : e.supports()) + " | "
                        + String.join("、", e.uniques() == null ? List.of() : e.uniques()) + " | " + nz(e.target()) + " | " + fmt(e.trueDps()) + " | " + fmt(median) + " |");
            }
        }
        // 沒人用的技能、輔助
        final Map<String, Integer> equipped = new HashMap<>();
        final Map<String, Integer> used = new HashMap<>();
        for (final Character c : characters) {
            for (final String[] pair : equippedSkills(c)) {
                equipped.merge(pair[0], 1, Integer::sum);
                for (final String s : pair[1].split(",")) {
                    if (!s.isBlank()) {
                        used.merge(s, 1, Integer::sum);
                    }
                }
            }
        }
        final List<String> unusedSkills = new ArrayList<>();
        for (final SkillDefinition d : plugin.skills().config().all()) {
            if (!d.hidden() && equipped.getOrDefault(d.id(), 0) == 0) {
                unusedSkills.add(d.name());
            }
        }
        final List<String> unusedSupports = new ArrayList<>();
        for (final SupportDefinition s : plugin.skills().config().supports()) {
            if (used.getOrDefault(s.id(), 0) == 0) {
                unusedSupports.add(s.name() + (s.cuttable() ? "" : "（專屬）"));
            }
        }
        md.add("");
        md.add("### 沒有活躍角色裝著的技能（" + unusedSkills.size() + "）");
        md.add("");
        md.add(String.join("、", unusedSkills));
        md.add("");
        md.add("### 沒有活躍角色掛著的輔助（" + unusedSupports.size() + "）");
        md.add("");
        md.add(String.join("、", unusedSupports));
        final JsonObject json = new JsonObject();
        json.add("highDps", high);
        json.add("unusedSkills", GSON.toJsonTree(unusedSkills));
        json.add("unusedSupports", GSON.toJsonTree(unusedSupports));
        root.add("outliers", json);
    }

    // ---- 第二步（2026-10-25）：地圖清圖、死亡 ----

    /** 每階的清圖秒數（開圖到 Boss 倒下）、擊殺數、每場死亡；再按昇華。 */
    private void mapRuns(final List<BalanceRecords.MapRun> runs, final JsonObject root, final List<String> md) {
        md.add("");
        md.add("## 9. 地圖清圖（最近 " + RECORD_DAYS + " 天，每人每張一筆；清圖秒數＝開圖到 Boss 倒下）");
        md.add("");
        if (runs.isEmpty()) {
            md.add("（還沒有紀錄：這一版之後打完的地圖才會記）");
            root.add("maps", new JsonObject());
            return;
        }
        final Map<Integer, List<BalanceRecords.MapRun>> byTier = new TreeMap<>();
        final Map<String, List<BalanceRecords.MapRun>> byAsc = new TreeMap<>();
        final Map<String, List<BalanceRecords.MapRun>> byMap = new TreeMap<>();
        for (final BalanceRecords.MapRun r : runs) {
            byTier.computeIfAbsent(r.tier(), k -> new ArrayList<>()).add(r);
            byAsc.computeIfAbsent(r.ascendancy() == null ? "（未昇華）" : r.ascendancy(), k -> new ArrayList<>()).add(r);
            byMap.computeIfAbsent(r.map() + (r.isFinal() ? "（終局）" : ""), k -> new ArrayList<>()).add(r);
        }
        final JsonObject json = new JsonObject();
        md.add("| 階級 | 場次 | 清圖秒數中位／90% | 擊殺中位 | 每場死亡平均 | 死亡 ≥1 次的比例 |");
        md.add("|---|---|---|---|---|---|");
        final JsonObject tiers = new JsonObject();
        for (final Map.Entry<Integer, List<BalanceRecords.MapRun>> e : byTier.entrySet()) {
            tiers.add("T" + e.getKey(), runStats(e.getValue()));
            md.add("| T" + e.getKey() + " | " + runLine(e.getValue()) + " |");
        }
        json.add("byTier", tiers);
        md.add("");
        md.add("| 昇華 | 場次 | 清圖秒數中位／90% | 擊殺中位 | 每場死亡平均 | 死亡 ≥1 次的比例 |");
        md.add("|---|---|---|---|---|---|");
        final JsonObject asc = new JsonObject();
        for (final Map.Entry<String, List<BalanceRecords.MapRun>> e : byAsc.entrySet()) {
            asc.add(e.getKey(), runStats(e.getValue()));
            md.add("| " + e.getKey() + " | " + runLine(e.getValue()) + " |");
        }
        json.add("byAscendancy", asc);
        md.add("");
        md.add("| 地圖 | 場次 | 清圖秒數中位／90% | 擊殺中位 | 每場死亡平均 | 死亡 ≥1 次的比例 |");
        md.add("|---|---|---|---|---|---|");
        final JsonObject maps = new JsonObject();
        final List<Map.Entry<String, List<BalanceRecords.MapRun>>> mapRows = new ArrayList<>(byMap.entrySet());
        mapRows.sort((a, b) -> b.getValue().size() - a.getValue().size());
        for (final Map.Entry<String, List<BalanceRecords.MapRun>> e : mapRows) {
            maps.add(e.getKey(), runStats(e.getValue()));
            md.add("| " + e.getKey() + " | " + runLine(e.getValue()) + " |");
        }
        json.add("byMap", maps);
        root.add("maps", json);
    }

    private static JsonObject runStats(final List<BalanceRecords.MapRun> runs) {
        final List<Double> seconds = new ArrayList<>();
        final List<Double> kills = new ArrayList<>();
        double deaths = 0;
        int died = 0;
        for (final BalanceRecords.MapRun r : runs) {
            seconds.add(r.clearSeconds());
            kills.add((double) r.kills());
            deaths += r.deaths();
            if (r.deaths() > 0) {
                died++;
            }
        }
        final JsonObject j = new JsonObject();
        j.addProperty("runs", runs.size());
        j.addProperty("medianSeconds", medianD(seconds));
        j.addProperty("p90Seconds", percentileD(seconds, 0.9));
        j.addProperty("medianKills", medianD(kills));
        j.addProperty("deathsPerRun", deaths / runs.size());
        j.addProperty("diedShare", (double) died / runs.size());
        return j;
    }

    private static String runLine(final List<BalanceRecords.MapRun> runs) {
        final JsonObject j = runStats(runs);
        return runs.size() + " | " + fmt(j.get("medianSeconds").getAsDouble()) + "／" + fmt(j.get("p90Seconds").getAsDouble()) + " | "
                + Math.round(j.get("medianKills").getAsDouble()) + " | " + String.format(Locale.ROOT, "%.2f", j.get("deathsPerRun").getAsDouble()) + " | "
                + String.format(Locale.ROOT, "%.0f%%", j.get("diedShare").getAsDouble() * 100);
    }

    /** 死亡：按等級區間、按地點（地圖階級／區域）、死因（來源＋招式）、傷害類型、昇華、死最多的人。 */
    private void deaths(final List<BalanceRecords.Death> list, final JsonObject root, final List<String> md) {
        md.add("");
        md.add("## 10. 死亡（最近 " + RECORD_DAYS + " 天）");
        md.add("");
        if (list.isEmpty()) {
            md.add("（還沒有紀錄：這一版之後的死亡才會記）");
            root.add("deaths", new JsonObject());
            return;
        }
        final int[] bands = new int[BANDS.length];
        final Map<String, Integer> byWhere = new TreeMap<>();
        final Map<String, Integer> byKiller = new HashMap<>();
        final Map<String, Integer> byType = new TreeMap<>();
        final Map<String, int[]> byAsc = new TreeMap<>();
        final Map<String, Integer> byPlayer = new HashMap<>();
        int dot = 0;
        for (final BalanceRecords.Death d : list) {
            bands[bandOf(d.level())]++;
            byWhere.merge(d.tier() > 0 ? "地圖 T" + String.format(Locale.ROOT, "%02d", d.tier()) : nz(d.where()), 1, Integer::sum);
            byKiller.merge(nz(d.killer()) + "・" + nz(d.skill()), 1, Integer::sum);
            byType.merge(nz(d.type()), 1, Integer::sum);
            byAsc.computeIfAbsent(d.ascendancy() == null ? "（未昇華）" : d.ascendancy(), k -> new int[BANDS.length])[bandOf(d.level())]++;
            byPlayer.merge(d.player() + "（" + d.character() + "）", 1, Integer::sum);
            if (d.dot()) {
                dot++;
            }
        }
        final JsonObject json = new JsonObject();
        json.addProperty("total", list.size());
        json.add("bands", GSON.toJsonTree(bands));
        json.addProperty("dotShare", (double) dot / list.size());
        json.add("byWhere", GSON.toJsonTree(byWhere));
        json.add("byType", GSON.toJsonTree(byType));
        md.add("共 " + list.size() + " 次：等級 1-40 " + bands[0] + "、41-70 " + bands[1] + "、71-90 " + bands[2] + "、91+ " + bands[3]
                + "；最後一下是持續傷害的 " + String.format(Locale.ROOT, "%.0f%%", dot * 100.0 / list.size()));
        md.add("");
        md.add("| 地點 | 死亡 |");
        md.add("|---|---|");
        for (final Map.Entry<String, Integer> e : byWhere.entrySet()) {
            md.add("| " + e.getKey() + " | " + e.getValue() + " |");
        }
        md.add("");
        md.add("| 最後一下的傷害類型 | 死亡 |");
        md.add("|---|---|");
        for (final Map.Entry<String, Integer> e : byType.entrySet()) {
            md.add("| " + e.getKey() + " | " + e.getValue() + " |");
        }
        md.add("");
        md.add("| 死因（來源・招式）前 25 | 死亡 |");
        md.add("|---|---|");
        final JsonObject killers = new JsonObject();
        for (final Map.Entry<String, Integer> e : topEntries(byKiller, 25)) {
            killers.addProperty(e.getKey(), e.getValue());
            md.add("| " + e.getKey() + " | " + e.getValue() + " |");
        }
        json.add("byKiller", killers);
        md.add("");
        md.add("| 昇華 | 1-40 | 41-70 | 71-90 | 91+ |");
        md.add("|---|---|---|---|---|");
        final JsonObject asc = new JsonObject();
        for (final Map.Entry<String, int[]> e : byAsc.entrySet()) {
            asc.add(e.getKey(), GSON.toJsonTree(e.getValue()));
            md.add("| " + e.getKey() + " | " + e.getValue()[0] + " | " + e.getValue()[1] + " | " + e.getValue()[2] + " | " + e.getValue()[3] + " |");
        }
        json.add("byAscendancy", asc);
        md.add("");
        md.add("| 死最多的人前 15 | 死亡 |");
        md.add("|---|---|");
        final JsonObject players = new JsonObject();
        for (final Map.Entry<String, Integer> e : topEntries(byPlayer, 15)) {
            players.addProperty(e.getKey(), e.getValue());
            md.add("| " + e.getKey() + " | " + e.getValue() + " |");
        }
        json.add("byPlayer", players);
        root.add("deaths", json);
    }

    // ---- 靜態資料 ----

    /** 所有技能（20 級係數、PoE2 對照）、輔助、傳奇。 */
    private JsonObject statics() {
        final JsonObject json = new JsonObject();
        final Map<String, String[]> poe2 = poe2();
        final JsonObject skills = new JsonObject();
        if (plugin.skills() != null) {
            for (final SkillDefinition d : plugin.skills().config().all()) {
                if (d.hidden()) {
                    continue;
                }
                final JsonObject s = new JsonObject();
                s.addProperty("name", d.name());
                s.addProperty("type", d.isAttack() ? "attack" : "spell");
                s.addProperty("tier", d.tier());
                s.addProperty("effectiveness20", d.effectiveness().at(20));
                s.addProperty("effectiveness1", d.effectiveness().at(1));
                s.add("tags", GSON.toJsonTree(d.tags()));
                final String[] p = poe2.get(d.id());
                if (p != null) {
                    // 2026-10-26：攻擊比傷害效益 %；法術比 20 級的平均基礎傷害（CSV 的法術一直填的是平均傷害，以前誤拿效益去除）
                    final double ours = d.isAttack() ? d.effectiveness().at(20) * 100 : spellAverage(d, 20);
                    s.addProperty("ours20", ours);
                    s.addProperty("poe2Name", p[0]);
                    s.addProperty("poe2Lv20", Double.parseDouble(p[1]));
                    s.addProperty("ratioToPoe2", ours / Double.parseDouble(p[1]));
                    if (!p[2].isBlank()) {
                        s.addProperty("poe2Note", p[2]);
                    }
                }
                skills.add(d.id(), s);
            }
            json.add("skills", skills);
            final JsonObject supports = new JsonObject();
            for (final SupportDefinition s : plugin.skills().config().supports()) {
                final JsonObject j = new JsonObject();
                j.addProperty("name", s.name());
                j.addProperty("tier", s.tier());
                j.addProperty("cuttable", s.cuttable());
                j.add("requires", GSON.toJsonTree(s.requires()));
                final JsonArray mods = new JsonArray();
                for (final tw.exilecore.stats.Modifier m : s.modifiers()) {
                    mods.add(m.stat() + " " + m.type() + " " + m.value() + (m.tags().isEmpty() ? "" : " " + m.tags()));
                }
                j.add("modifiers", mods);
                supports.add(s.id(), j);
            }
            json.add("supports", supports);
        }
        if (plugin.items() != null) {
            final JsonObject uniques = new JsonObject();
            for (final UniqueItem u : plugin.items().repository().uniques()) {
                final JsonObject j = new JsonObject();
                j.addProperty("name", u.name());
                j.addProperty("base", u.base());
                uniques.add(u.id(), j);
            }
            json.add("uniques", uniques);
        }
        return json;
    }

    /** 法術在這個等級的平均基礎傷害（沒有 damage 的回傳 0）。 */
    private double spellAverage(final SkillDefinition d, final int level) {
        final double[] range = tw.exilecore.skill.SkillProfiles.spellDamage(plugin.skills().config(), d, level);
        return (range[0] + range[1]) / 2;
    }

    /** balance/poe2_skills.csv：技能代號 → {PoE2 名字, 20 級數值, 備註}。 */
    private Map<String, String[]> poe2() {
        final Map<String, String[]> map = new HashMap<>();
        try (InputStream in = BalanceExport.class.getResourceAsStream("/balance/poe2_skills.csv")) {
            if (in == null) {
                return map;
            }
            for (final String line : new String(in.readAllBytes(), StandardCharsets.UTF_8).split("\\R")) {
                if (line.isBlank() || line.startsWith("#")) {
                    continue;
                }
                final String[] parts = line.split(",", 4);
                if (parts.length >= 3) {
                    map.put(parts[0].trim(), new String[] {parts[1].trim(), parts[2].trim(), parts.length > 3 ? parts[3].trim() : ""});
                }
            }
        } catch (final IOException ignored) {
            // 沒有對照就不列
        }
        return map;
    }

    private void poe2Table(final JsonObject statics, final List<String> md) {
        if (!statics.has("skills")) {
            return;
        }
        md.add("");
        // 對照 = 設計參考的原作數值；字串不寫遊戲名（PlayerTextTest）
        // 2026-10-26：法術改比 20 級平均基礎傷害；武器基底已改成原作數值，拿掉「×0.75」那一欄
        md.add("## 8. 技能係數 vs 對照表（20 級；攻擊比傷害效益 %、法術比平均基礎傷害；1.00 = 跟對照一樣；沒有對照的不列）");
        md.add("");
        md.add("| 技能 | 類型 | 我們 | 對照 | 比值 |");
        md.add("|---|---|---|---|---|");
        final List<Map.Entry<String, JsonElement>> rows = new ArrayList<>(statics.getAsJsonObject("skills").entrySet());
        rows.removeIf(e -> !e.getValue().getAsJsonObject().has("ratioToPoe2"));
        rows.sort((a, b) -> Double.compare(a.getValue().getAsJsonObject().get("ratioToPoe2").getAsDouble(),
                b.getValue().getAsJsonObject().get("ratioToPoe2").getAsDouble()));
        for (final Map.Entry<String, JsonElement> e : rows) {
            final JsonObject s = e.getValue().getAsJsonObject();
            final double ratio = s.get("ratioToPoe2").getAsDouble();
            final boolean attack = "attack".equals(s.get("type").getAsString());
            final String unit = attack ? "%" : "";
            md.add("| " + s.get("name").getAsString() + " | " + (attack ? "攻擊" : "法術") + " | "
                    + Math.round(s.get("ours20").getAsDouble()) + unit + " | " + Math.round(s.get("poe2Lv20").getAsDouble()) + unit + " | "
                    + String.format(Locale.ROOT, "%.2f", ratio) + " |");
        }
    }

    // ---- 小工具 ----

    private String className(final String id) {
        for (final tw.exilecore.character.CharacterClass type : tw.exilecore.character.CharacterClass.values()) {
            if (type.id().equals(id)) {
                return type.displayName();
            }
        }
        return id == null ? "?" : id;
    }

    private String skillName(final String id) {
        final SkillDefinition d = plugin.skills() == null ? null : plugin.skills().config().skill(id);
        return d == null ? id : d.name();
    }

    private String supportName(final String id) {
        final SupportDefinition s = plugin.skills() == null ? null : plugin.skills().config().support(id);
        return s == null ? id : s.name();
    }

    private static List<Map.Entry<String, Integer>> topEntries(final Map<String, Integer> map, final int limit) {
        final List<Map.Entry<String, Integer>> list = new ArrayList<>(map.entrySet());
        list.sort((a, b) -> b.getValue() - a.getValue());
        return list.subList(0, Math.min(limit, list.size()));
    }

    private static JsonArray bandArray(final List<Double>[] bands, final boolean p90) {
        final JsonArray array = new JsonArray();
        for (int i = 0; i < BANDS.length; i++) {
            final double v = p90 ? p90(bands, i) : med(bands, i);
            array.add(v);
        }
        return array;
    }

    private static double med(final List<Double>[] bands, final int band) {
        return bands == null || bands[band] == null || bands[band].isEmpty() ? 0 : medianD(bands[band]);
    }

    private static double p90(final List<Double>[] bands, final int band) {
        return bands == null || bands[band] == null || bands[band].isEmpty() ? 0 : percentileD(bands[band], 0.9);
    }

    static int median(final List<Integer> values) {
        if (values.isEmpty()) {
            return 0;
        }
        final List<Integer> sorted = new ArrayList<>(values);
        Collections.sort(sorted);
        return sorted.get(sorted.size() / 2);
    }

    static double medianD(final List<Double> values) {
        return percentileD(values, 0.5);
    }

    static double percentileD(final List<Double> values, final double p) {
        if (values.isEmpty()) {
            return 0;
        }
        final List<Double> sorted = new ArrayList<>(values);
        Collections.sort(sorted);
        return sorted.get(Math.min(sorted.size() - 1, (int) Math.floor(p * (sorted.size() - 1))));
    }

    static String fmt(final double value) {
        if (value <= 0) {
            return "-";
        }
        return value >= 100 ? String.format(Locale.ROOT, "%,d", Math.round(value)) : String.format(Locale.ROOT, "%.1f", value);
    }

    private static String percent(final int count, final int total) {
        return total <= 0 ? "0%" : String.format(Locale.ROOT, "%.0f%%", count * 100.0 / total);
    }

    private static String nz(final String s) {
        return s == null ? "-" : s;
    }
}
