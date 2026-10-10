package tw.exilecore.ops;

import org.bukkit.World;
import org.bukkit.command.CommandSender;
import org.bukkit.command.ConsoleCommandSender;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.entity.Player;
import tw.exilecore.ExileCore;
import tw.exilecore.core.db.Migrations;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.file.FileVisitResult;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.SimpleFileVisitor;
import java.nio.file.StandardCopyOption;
import java.nio.file.attribute.BasicFileAttributes;
import java.sql.Connection;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.time.format.DateTimeFormatter;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.stream.Stream;
import java.util.zip.GZIPInputStream;
import java.util.zip.GZIPOutputStream;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

/**
 * 備份（config.yml 的 backup）：每天固定時間把資料庫寫成 backups/db-時間.json.gz，劇情世界壓成 backups/world-時間.zip，
 * 超過 keep-days 天的刪掉。/ec backup 馬上備份一次；/ec backup verify 檔名 在記憶體裡試還原、確認備份是好的（不動現在的資料）；
 * /ec backup restore 檔名（只能在主控台）把資料庫還原成備份的樣子。
 * 資料庫備份排在資料庫執行緒上（所有存檔都在同一條執行緒依序寫），所以備份到的是同一個時間點的樣子。
 */
public final class BackupService {

    private static final DateTimeFormatter STAMP = DateTimeFormatter.ofPattern("yyyyMMdd-HHmmss");
    private static final DateTimeFormatter DAY = DateTimeFormatter.ofPattern("yyyyMMdd");

    private final ExileCore plugin;
    private LocalTime dailyTime;
    private int keepDays;
    private boolean worlds;
    private boolean running;
    /** 還原中（或還原完了等重開）：不讓人登入。 */
    private volatile boolean restoring;
    /** 清檔中（或清完了等重開，2026-10）：不讓人登入、不自動備份。 */
    private volatile boolean wiping;

    public BackupService(final ExileCore plugin) {
        this.plugin = plugin;
        reload();
    }

    /** 讀 config.yml 的 backup 區塊。 */
    public void reload() {
        final ConfigurationSection section = plugin.getConfig().getConfigurationSection("backup");
        final String time = section == null ? "05:00" : section.getString("daily-time", "05:00");
        LocalTime parsed = null;
        if (time != null && !time.isBlank()) {
            try {
                parsed = LocalTime.parse(time.trim().length() == 4 ? "0" + time.trim() : time.trim());
            } catch (final RuntimeException e) {
                plugin.getSLF4JLogger().warn("config.yml 的 backup.daily-time 看不懂：{}（要像 05:00），自動備份先關掉", time);
            }
        }
        dailyTime = parsed;
        keepDays = section == null ? 14 : Math.max(1, section.getInt("keep-days", 14));
        worlds = section == null || section.getBoolean("worlds", true);
    }

    public Path folder() {
        return plugin.dataFolder().resolve("backups");
    }

    public boolean restoring() {
        return restoring || wiping;
    }

    /** 登入被擋時給玩家看的原因。 */
    public String lockMessage() {
        return wiping ? "<yellow>伺服器正在清檔，重新啟動後才能登入" : "<yellow>伺服器正在還原備份，重新啟動後才能登入";
    }

    /** 清檔用（{@link WipeService}）：擋登入、停自動備份。 */
    void wiping(final boolean value) {
        wiping = value;
    }

    /** 每分鐘：到了每天的時間、今天還沒備份過就備份。 */
    public void tick() {
        if (dailyTime == null || running || restoring()) {
            return;
        }
        final LocalDateTime now = LocalDateTime.now();
        if (now.toLocalTime().isBefore(dailyTime)) {
            return;
        }
        if (doneToday(now.toLocalDate())) {
            return;
        }
        run(plugin.getServer().getConsoleSender());
    }

    /** 今天已經有完整的資料庫備份（寫到一半的 .part 不算）。 */
    private boolean doneToday(final LocalDate today) {
        final String prefix = "db-" + today.format(DAY);
        try (Stream<Path> files = Files.exists(folder()) ? Files.list(folder()) : Stream.empty()) {
            return files.map(path -> path.getFileName().toString())
                    .anyMatch(name -> name.startsWith(prefix) && name.endsWith(".json.gz"));
        } catch (final IOException e) {
            return false;
        }
    }

    /** 馬上備份一次（資料庫，然後劇情世界）。 */
    public void run(final CommandSender sender) {
        run(sender, null);
    }

    /**
     * 馬上備份一次；afterDatabase（可以是 null）在資料庫備份寫完後（主執行緒）收到備份檔，失敗或沒開始收到 null。
     */
    public void run(final CommandSender sender, final java.util.function.Consumer<Path> afterDatabase) {
        if (running) {
            sender.sendMessage(plugin.text().prefixed("<yellow>備份正在進行中"));
            if (afterDatabase != null) {
                afterDatabase.accept(null);
            }
            return;
        }
        if (!plugin.database().isConnected()) {
            sender.sendMessage(plugin.text().prefixed("<red>資料庫沒有連線，不能備份"));
            if (afterDatabase != null) {
                afterDatabase.accept(null);
            }
            return;
        }
        running = true;
        final String stamp = LocalDateTime.now().format(STAMP);
        final Path target = folder().resolve("db-" + stamp + ".json.gz");
        final Path temp = folder().resolve("db-" + stamp + ".json.gz.part");
        final long start = System.currentTimeMillis();
        plugin.database().async(connection -> {
            try {
                Files.createDirectories(folder());
                final int rows;
                try (OutputStream out = new GZIPOutputStream(Files.newOutputStream(temp))) {
                    rows = DatabaseDump.dump(connection, Migrations.TABLES, out);
                }
                // 關掉（gzip 的結尾寫完）才改名：寫到一半當機只會留下 .part
                Files.move(temp, target, StandardCopyOption.REPLACE_EXISTING);
                return rows;
            } catch (final IOException | java.sql.SQLException | RuntimeException e) {
                deleteQuietly(temp);
                throw new java.sql.SQLException("寫入備份檔失敗：" + e.getMessage(), e);
            }
        }).whenComplete((rows, error) -> plugin.getServer().getScheduler().runTask(plugin, () -> {
            if (error != null) {
                running = false;
                plugin.getSLF4JLogger().error("資料庫備份失敗", error);
                sender.sendMessage(plugin.text().prefixed("<red>資料庫備份失敗（看主控台）"));
                if (afterDatabase != null) {
                    afterDatabase.accept(null);
                }
                return;
            }
            final String size = size(target);
            plugin.getSLF4JLogger().info("資料庫備份完成：{}（{} 列，{}，{} 毫秒）", target.getFileName(), rows, size,
                    System.currentTimeMillis() - start);
            sender.sendMessage(plugin.text().prefixed("<green>資料庫備份完成：<white>" + target.getFileName() + "</white>（" + rows + " 列，" + size + "）"));
            if (afterDatabase != null) {
                afterDatabase.accept(target);
            }
            final World campaign = plugin.world() == null || plugin.world().worlds() == null ? null : plugin.world().worlds().campaign();
            if (worlds && campaign != null) {
                backupWorld(campaign, stamp, sender);
            } else {
                running = false;
                prune();
            }
        }));
    }

    /** 劇情世界：先存檔、暫停自動存檔，背景壓縮，壓完恢復自動存檔。 */
    private void backupWorld(final World world, final String stamp, final CommandSender sender) {
        world.save();
        final boolean autoSave = world.isAutoSave();
        world.setAutoSave(false);
        final Path source = world.getWorldPath();
        final Path target = folder().resolve("world-" + stamp + ".zip");
        plugin.getServer().getScheduler().runTaskAsynchronously(plugin, () -> {
            Exception failure = null;
            try {
                zip(source, target);
            } catch (final IOException e) {
                failure = e;
            }
            final Exception error = failure;
            plugin.getServer().getScheduler().runTask(plugin, () -> {
                world.setAutoSave(autoSave);
                running = false;
                if (error != null) {
                    plugin.getSLF4JLogger().error("世界備份失敗", error);
                    sender.sendMessage(plugin.text().prefixed("<red>世界備份失敗（看主控台）"));
                } else {
                    plugin.getSLF4JLogger().info("世界備份完成：{}（{}）", target.getFileName(), size(target));
                    sender.sendMessage(plugin.text().prefixed("<green>世界備份完成：<white>" + target.getFileName() + "</white>（" + size(target) + "）"));
                }
                prune();
            });
        });
    }

    private static void zip(final Path source, final Path target) throws IOException {
        Files.createDirectories(target.getParent());
        final Path temp = target.resolveSibling(target.getFileName() + ".part");
        final Path base = source.getParent() == null ? source : source.getParent();
        try {
            zipInto(source, base, temp);
        } catch (final IOException | RuntimeException e) {
            deleteQuietly(temp);
            throw e;
        }
        Files.move(temp, target, StandardCopyOption.REPLACE_EXISTING);
    }

    private static void zipInto(final Path source, final Path base, final Path temp) throws IOException {
        try (ZipOutputStream zip = new ZipOutputStream(Files.newOutputStream(temp))) {
            Files.walkFileTree(source, new SimpleFileVisitor<>() {
                @Override
                public FileVisitResult visitFile(final Path file, final BasicFileAttributes attributes) throws IOException {
                    if (file.getFileName().toString().equals("session.lock")) {
                        return FileVisitResult.CONTINUE;
                    }
                    zip.putNextEntry(new ZipEntry(base.relativize(file).toString().replace('\\', '/')));
                    try {
                        Files.copy(file, zip);
                    } catch (final IOException e) {
                        // 剛好在寫的檔（很少見）：略過這一個，不讓整個備份失敗
                    }
                    zip.closeEntry();
                    return FileVisitResult.CONTINUE;
                }
            });
        }
    }

    private static void deleteQuietly(final Path path) {
        try {
            Files.deleteIfExists(path);
        } catch (final IOException ignored) {
            // 刪不掉就留著，下次清舊備份時再刪
        }
    }

    /** 刪掉超過 keep-days 天的備份，以及當機留下來、超過一天的半成品（.part）。 */
    private void prune() {
        final Instant limit = Instant.now().minus(keepDays, ChronoUnit.DAYS);
        int removed = 0;
        for (final Path path : list()) {
            try {
                if (Files.getLastModifiedTime(path).toInstant().isBefore(limit)) {
                    Files.deleteIfExists(path);
                    removed++;
                }
            } catch (final IOException e) {
                plugin.getSLF4JLogger().warn("刪除舊備份 {} 失敗", path.getFileName(), e);
            }
        }
        if (removed > 0) {
            plugin.getSLF4JLogger().info("刪掉 {} 個超過 {} 天的備份", removed, keepDays);
        }
        final Instant stale = Instant.now().minus(1, ChronoUnit.DAYS);
        try (Stream<Path> files = Files.exists(folder()) ? Files.list(folder()) : Stream.empty()) {
            files.filter(path -> path.getFileName().toString().endsWith(".part")).forEach(path -> {
                try {
                    if (Files.getLastModifiedTime(path).toInstant().isBefore(stale)) {
                        Files.deleteIfExists(path);
                        plugin.getSLF4JLogger().info("刪掉沒寫完的備份 {}", path.getFileName());
                    }
                } catch (final IOException e) {
                    plugin.getSLF4JLogger().warn("刪除沒寫完的備份 {} 失敗", path.getFileName(), e);
                }
            });
        } catch (final IOException e) {
            plugin.getSLF4JLogger().warn("讀取備份資料夾失敗", e);
        }
    }

    /** 所有備份檔（新的在前）。 */
    public List<Path> list() {
        final List<Path> files = new ArrayList<>();
        if (!Files.exists(folder())) {
            return files;
        }
        try (Stream<Path> stream = Files.list(folder())) {
            stream.filter(path -> {
                final String name = path.getFileName().toString();
                return name.endsWith(".json.gz") || name.endsWith(".zip");
            }).forEach(files::add);
        } catch (final IOException e) {
            plugin.getSLF4JLogger().warn("讀取備份資料夾失敗", e);
        }
        files.sort((a, b) -> b.getFileName().toString().compareTo(a.getFileName().toString()));
        return files;
    }

    /**
     * 還原資料庫（只能在主控台）：先把所有人踢下線（登出存檔會先寫完），再把資料庫換成備份的樣子。
     * 記憶體裡的市集、角色都還是舊的，所以還原完不讓人登入，要重新啟動伺服器。
     */
    public void restore(final CommandSender sender, final String name) {
        if (!(sender instanceof ConsoleCommandSender)) {
            sender.sendMessage(plugin.text().prefixed("<red>還原只能在伺服器主控台執行"));
            return;
        }
        if (restoring()) {
            sender.sendMessage(plugin.text().prefixed("<red>正在還原或清檔（或做完了等重開）：重新啟動伺服器後再試"));
            return;
        }
        final Path file = folder().resolve(name).normalize();
        if (!file.startsWith(folder()) || !Files.isRegularFile(file) || !name.endsWith(".json.gz")) {
            sender.sendMessage(plugin.text().prefixed("<red>找不到資料庫備份 " + name + "（/ec backup list 看有哪些）"));
            return;
        }
        final int version;
        try {
            version = BackupVerifier.version(file);
        } catch (final IOException | RuntimeException e) {
            sender.sendMessage(plugin.text().prefixed("<red>讀不了備份 " + name + "：" + e.getMessage()));
            return;
        }
        if (version > Migrations.currentVersion()) {
            sender.sendMessage(plugin.text().prefixed("<red>這是比較新的插件做的備份（資料表版本 " + version + "，現在只到 "
                    + Migrations.currentVersion() + "）：要用新版的插件還原"));
            return;
        }
        if (version > 0 && version < Migrations.currentVersion()) {
            // 舊版的備份：先在記憶體裡升到現在的版本，寫成一份新的再還原（不然重開時遷移會在已經是新版的資料表上再跑一次）
            sender.sendMessage(plugin.text().prefixed("<yellow>這是舊版的備份（資料表版本 " + version + "），先升到現在的版本 "
                    + Migrations.currentVersion() + "…（還沒踢人、還沒動資料）"));
            final Path upgraded = folder().resolve("tmp").resolve("upgraded-" + name);
            restoring = true; // 升級中也不要讓人重複還原、自動備份
            plugin.getServer().getScheduler().runTaskAsynchronously(plugin, () -> {
                Exception failure = null;
                try {
                    Files.createDirectories(upgraded.getParent());
                    BackupVerifier.upgrade(file, version, upgraded);
                } catch (final IOException | java.sql.SQLException | RuntimeException e) {
                    failure = e;
                }
                final Exception error = failure;
                plugin.getServer().getScheduler().runTask(plugin, () -> {
                    restoring = false;
                    if (error != null) {
                        plugin.getSLF4JLogger().error("舊版備份 {} 升級失敗", name, error);
                        sender.sendMessage(plugin.text().prefixed("<red>升級失敗，沒有還原（資料庫維持原樣，看主控台）"));
                        return;
                    }
                    apply(sender, upgraded, name);
                });
            });
            return;
        }
        apply(sender, file, name);
    }

    /** 真的還原：踢人、把 file（現在版本的備份）寫進資料庫。name 是給人看的檔名。 */
    private void apply(final CommandSender sender, final Path file, final String name) {
        restoring = true;
        for (final Player player : List.copyOf(plugin.getServer().getOnlinePlayers())) {
            player.kick(plugin.text().parse("<yellow>伺服器正在還原備份，請稍後再登入"));
        }

        sender.sendMessage(plugin.text().prefixed("<yellow>所有人已經踢下線，開始還原 " + name));
        // 等一下，讓登出的存檔先排進資料庫執行緒（還原排在它們後面，最後的結果是備份的樣子）
        plugin.getServer().getScheduler().runTaskLater(plugin, () -> plugin.database().async(connection -> {
            // 在資料庫執行緒上暫停存檔：排在前面的登出存檔都已經寫完了，之後的都不寫
            // （還原完的資料不能被記憶體裡的舊資料蓋掉）
            plugin.characters().pauseWrites();
            final boolean autoCommit = connection.getAutoCommit();
            connection.setAutoCommit(false);
            try (InputStream in = new GZIPInputStream(Files.newInputStream(file))) {
                final DatabaseDump.Restored restored = DatabaseDump.restore(connection, in, Migrations.TABLES);
                connection.commit();
                return restored;
            } catch (final IOException | java.sql.SQLException | RuntimeException e) {
                rollback(connection);
                throw new java.sql.SQLException("還原失敗：" + e.getMessage(), e);
            } finally {
                connection.setAutoCommit(autoCommit);
            }
        }).whenComplete((restored, error) -> plugin.getServer().getScheduler().runTask(plugin, () -> {
            if (error != null) {
                plugin.getSLF4JLogger().error("還原備份失敗（資料庫維持原樣）", error);
                sender.sendMessage(plugin.text().prefixed("<red>還原失敗，資料庫維持原樣（看主控台）。現在開放登入"));
                restoring = false;
                plugin.characters().resumeWrites();
                return;
            }
            sender.sendMessage(plugin.text().prefixed(String.format(Locale.ROOT,
                    "<green>還原完成：%d 個資料表、%d 列%s。請重新啟動伺服器（重開前不能登入）",
                    restored.tables(), restored.rows(), restored.skippedColumns() > 0 ? "（略過 " + restored.skippedColumns() + " 個現在沒有的欄位）" : "")));
        })), 40);
    }

    /**
     * 檢查備份（2026-10）：不動現在的資料。資料庫備份在記憶體裡的空資料庫試還原一次、逐表對列數；
     * 世界備份把每個檔案讀一遍。資料庫備份會順便檢查同一個時間的世界備份。在背景跑，跑完回報。
     */
    public void verify(final CommandSender sender, final String name) {
        final Path file = folder().resolve(name).normalize();
        if (!file.startsWith(folder()) || !Files.isRegularFile(file) || !(name.endsWith(".json.gz") || name.endsWith(".zip"))) {
            sender.sendMessage(plugin.text().prefixed("<red>找不到備份 " + name + "（/ec backup list 看有哪些）"));
            return;
        }
        sender.sendMessage(plugin.text().prefixed("<gray>檢查 " + name + " 中…（在記憶體裡試還原，不會動到現在的資料）"));
        plugin.getServer().getScheduler().runTaskAsynchronously(plugin, () -> {
            final List<String> lines = new ArrayList<>();
            boolean ok = true;
            try {
                Path world = name.endsWith(".zip") ? file : null;
                if (name.endsWith(".json.gz")) {
                    final long start = System.currentTimeMillis();
                    final BackupVerifier.DatabaseResult result = BackupVerifier.verifyDatabase(file);
                    ok = result.ok();
                    lines.add((result.ok() ? "<green>✔ 資料庫備份還原得回來" : "<red>✘ 資料庫備份有問題") + "<gray>：" + name);
                    if (result.created() > 0) {
                        lines.add("<gray>  備份時間 <white>" + LocalDateTime.ofInstant(Instant.ofEpochMilli(result.created()), java.time.ZoneId.systemDefault())
                                .format(DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss")) + "</white>，" + size(file));
                    }
                    if (result.version() > 0 && result.version() < Migrations.currentVersion() && result.upgraded().size() > 0) {
                        lines.add("<gray>  這是舊版的備份（資料表版本 " + result.version() + "），試過升到現在的版本 " + Migrations.currentVersion()
                                + "：成功（真的還原時也會先升級）");
                    }
                    lines.add(String.format(Locale.ROOT, "<gray>  %d 個資料表、%,d 列全部寫回去了%s（%,d 毫秒）", result.tables().size(), result.rows(),
                            result.skippedColumns() > 0 ? "，略過 " + result.skippedColumns() + " 個現在沒有的欄位" : "",
                            System.currentTimeMillis() - start));
                    lines.add(String.format(Locale.ROOT, "<gray>  內容：帳號 <white>%s</white>、角色 <white>%s</white>、身上的物品 <white>%s</white>、倉庫頁 <white>%s</white>、市集掛單 <white>%s</white>",
                            n(result.count("accounts")), n(result.count("characters")), n(result.count("character_inventory")),
                            n(result.count("stash_pages")), n(result.count("market_listings"))));
                    for (final String problem : result.problems()) {
                        lines.add("<red>  · " + problem);
                    }
                    // 同一個時間的世界備份
                    final Path sibling = file.resolveSibling(name.replace("db-", "world-").replace(".json.gz", ".zip"));
                    world = Files.isRegularFile(sibling) ? sibling : null;
                    if (world == null) {
                        lines.add("<dark_gray>  （沒有同一個時間的世界備份）");
                    }
                }
                if (world != null) {
                    final BackupVerifier.WorldResult result = BackupVerifier.verifyWorld(world);
                    ok &= result.ok();
                    lines.add((result.ok() ? "<green>✔ 世界備份完整" : "<red>✘ 世界備份有問題") + "<gray>：" + world.getFileName());
                    lines.add(String.format(Locale.ROOT, "<gray>  %,d 個檔案（區域檔 %d 個）、解壓後 %.1f MB、level.dat %s",
                            result.files(), result.regions(), result.bytes() / 1024.0 / 1024.0, result.levelDat() ? "世界資料在" : "<red>世界資料不在"));
                    final World campaign = plugin.world() == null || plugin.world().worlds() == null ? null : plugin.world().worlds().campaign();
                    if (campaign != null) {
                        lines.add("<dark_gray>  還原世界：關服後把壓縮檔裡的資料夾解壓縮到 " + tw.exilecore.item.ItemRenderer.escape(
                                String.valueOf(campaign.getWorldPath().getParent())) + "（覆蓋同名的 " + campaign.getWorldPath().getFileName() + "）");
                    }
                    for (final String problem : result.problems()) {
                        lines.add("<red>  · " + problem);
                    }
                }
            } catch (final IOException | java.sql.SQLException | RuntimeException e) {
                ok = false;
                plugin.getSLF4JLogger().error("檢查備份 {} 失敗", name, e);
                lines.add("<red>✘ " + name + " 還原不回來：" + tw.exilecore.item.ItemRenderer.escape(String.valueOf(e.getMessage())));
            }
            if (ok) {
                lines.add("<dark_gray>真的要還原：在主控台輸入 ec backup restore 檔名（資料庫）；世界要關服後手動解壓縮覆蓋");
            }
            final boolean passed = ok;
            plugin.getServer().getScheduler().runTask(plugin, () -> {
                for (final String line : lines) {
                    sender.sendMessage(plugin.text().parse(line));
                }
                plugin.getSLF4JLogger().info("檢查備份 {}：{}", name, passed ? "通過" : "有問題");
            });
        });
    }

    private static String n(final int value) {
        return value < 0 ? "—" : String.format(Locale.ROOT, "%,d", value);
    }

    private static void rollback(final Connection connection) {
        try {
            connection.rollback();
        } catch (final java.sql.SQLException ignored) {
            // 已經失敗了，回滾失敗也只能這樣
        }
    }

    static String size(final Path path) {
        try {
            final long bytes = Files.size(path);
            return bytes >= 1024 * 1024 ? String.format(Locale.ROOT, "%.1f MB", bytes / 1024.0 / 1024.0)
                    : String.format(Locale.ROOT, "%.1f KB", bytes / 1024.0);
        } catch (final IOException e) {
            return "?";
        }
    }
}
