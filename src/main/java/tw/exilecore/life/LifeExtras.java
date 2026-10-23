package tw.exilecore.life;

import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.Particle;
import org.bukkit.Sound;
import org.bukkit.block.Block;
import org.bukkit.entity.Entity;
import org.bukkit.entity.LivingEntity;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;
import org.bukkit.persistence.PersistentDataType;
import tw.exilecore.ExileCore;
import tw.exilecore.item.ItemRenderer;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;
import java.util.random.RandomGenerator;

/**
 * 星骸之海 2026-10-23 的新東西：魚圖鑑與釣魚大賽、生活委託、魚籠、按讚與人氣榜、渦齒鯊王、筏的設施加成（筏燈、船帆、吊床）。
 * 個人的記在帳號進度（{@link LifeService.Profile}）；跨帳號的（大賽、人氣榜、魚籠、待領的獎）記在伺服器資料夾的 life_*.txt。
 */
final class LifeExtras {

    private final ExileCore plugin;
    private final LifeService service;
    private final RandomGenerator random;

    LifeExtras(final ExileCore plugin, final LifeService service) {
        this.plugin = plugin;
        this.service = service;
        this.random = plugin.items().random();
    }

    private LifeConfig config() {
        return service.config();
    }

    // ---- 筏上的設施（每塊筏掃一次，100 輪（10 秒）內不重掃）----

    /** 一塊筏上有幾盞筏燈、有沒有船帆、幾個魚籠。 */
    record RaftScan(int lamps, boolean sail, int traps, int at) {
    }

    private final Map<Integer, RaftScan> scans = new HashMap<>();

    RaftScan scan(final int plot, final int tick) {
        final RaftScan cached = scans.get(plot);
        if (cached != null && tick - cached.at() < 100) {
            return cached;
        }
        final RaftScan fresh = scanNow(plot, tick);
        scans.put(plot, fresh);
        return fresh;
    }

    void forgetScan(final int plot) {
        scans.remove(plot);
    }

    private RaftScan scanNow(final int plot, final int tick) {
        final org.bukkit.World world = service.world();
        if (world == null || plot < 0) {
            return new RaftScan(0, false, 0, tick);
        }
        final LifeConfig.Item lampItem = config().station("lamp");
        final LifeConfig.Item sailItem = config().station("sail");
        final LifeConfig.Item trapItem = config().station("fish_trap");
        final Material lamp = lampItem == null ? null : LifeService.material(lampItem.block(), Material.LANTERN);
        final Material sail = sailItem == null ? null : LifeService.material(sailItem.block(), Material.WHITE_BANNER);
        final String wallSail = sail == null ? null : sail.name().replace("_BANNER", "_WALL_BANNER");
        final Material trap = trapItem == null ? null : LifeService.material(trapItem.block(), Material.BARREL);
        final int[] center = service.plotCenter(plot);
        final int[] range = LifeService.extent(service.raftSize(plot));
        final int sea = config().seaLevel();
        int lamps = 0;
        int traps = 0;
        boolean hasSail = false;
        for (int dx = range[0]; dx <= range[1]; dx++) {
            for (int dz = range[0]; dz <= range[1]; dz++) {
                for (int y = sea + 1; y <= sea + config().buildHeight(); y++) {
                    final Material here = world.getBlockAt(center[0] + dx, y, center[1] + dz).getType();
                    if (here == lamp) {
                        lamps++;
                    } else if (here == trap) {
                        traps++;
                    } else if (here == sail || wallSail != null && here.name().equals(wallSail)) {
                        hasSail = true;
                    }
                }
            }
        }
        return new RaftScan(lamps, hasSail, traps, tick);
    }

    /** 筏燈（晚上）、船帆的漂流物倍率。 */
    double raftDrift(final int plot, final boolean night, final int tick) {
        final RaftScan scan = scan(plot, tick);
        double factor = 1;
        if (night) {
            factor += config().lampDrift() * Math.min(config().lampMax(), scan.lamps());
        }
        if (scan.sail()) {
            factor += config().sailDrift();
        }
        return factor;
    }

    // ---- 吊床 ----

    /** 右鍵吊床：每天一次回精力（不佔淡水、料理的上限）；回傳要不要重畫（一律 true）。 */
    void useBed(final Player player, final LifeService.Profile profile) {
        final String today = config().day(System.currentTimeMillis());
        if (today.equals(profile.bedDay)) {
            service.bar(player, "<gray>今天已經在吊床躺過了（半夜之後再來）");
            return;
        }
        if (!today.equals(profile.staminaDay)) {
            profile.staminaDay = today;
            profile.staminaUsed = 0;
            profile.staminaRestored = 0;
        }
        final int bonus = plugin.sponsors() == null ? 0 : plugin.sponsors().bedStaminaBonus(player); // 2026-10-23 晚：最高階多回
        final int restored = Math.min(profile.staminaUsed, config().bedStamina() + bonus);
        profile.bedDay = today;
        profile.staminaUsed -= restored;
        profile.dirty = true;
        player.playSound(player.getLocation(), Sound.BLOCK_WOOL_PLACE, 0.8f, 0.8f);
        service.bar(player, restored > 0 ? "<#7fd4ff>躺了一下：精力 +" + restored + " <dark_gray>｜ <gray>精力 " + service.stamina(player)
                : "<gray>躺了一下……精力本來就是滿的（今天的機會用掉了）");
    }

    // ---- 魚籠（life_traps.txt：「x,y,z=上次算到的時間」）----

    private final Map<String, Long> traps = new HashMap<>();
    private String savedTraps = "";

    private Path trapFile() {
        return plugin.dataFolder().resolve("life_traps.txt");
    }

    void loadTraps() {
        traps.clear();
        savedTraps = "";
        try {
            if (!Files.exists(trapFile())) {
                return;
            }
            for (final String line : Files.readAllLines(trapFile())) {
                final int eq = line.indexOf('=');
                if (eq > 0) {
                    try {
                        traps.put(line.substring(0, eq).trim(), Long.parseLong(line.substring(eq + 1).trim()));
                    } catch (final NumberFormatException ignored) {
                        // 壞掉的一行跳過
                    }
                }
            }
        } catch (final java.io.IOException e) {
            plugin.getSLF4JLogger().warn("{}：讀不到 life_traps.txt：{}", config().name(), e.toString());
        }
    }

    void saveTraps() {
        final StringBuilder out = new StringBuilder();
        for (final Map.Entry<String, Long> entry : new java.util.TreeMap<>(traps).entrySet()) {
            out.append(entry.getKey()).append('=').append(entry.getValue()).append(System.lineSeparator());
        }
        final String text = out.toString();
        if (text.equals(savedTraps)) {
            return;
        }
        try {
            final Path temp = trapFile().resolveSibling("life_traps.txt.tmp");
            Files.writeString(temp, text);
            Files.move(temp, trapFile(), java.nio.file.StandardCopyOption.REPLACE_EXISTING, java.nio.file.StandardCopyOption.ATOMIC_MOVE);
            savedTraps = text;
        } catch (final java.io.IOException e) {
            plugin.getSLF4JLogger().warn("{}：寫不進 life_traps.txt：{}", config().name(), e.toString());
        }
    }

    /** 放魚籠之前：這塊筏的魚籠滿了沒（回傳問題，可以放 null）。 */
    String canPlaceTrap(final int plot, final int tick, final Block placing) {
        scans.remove(plot);
        final RaftScan scan = scan(plot, tick);
        // 放方塊的事件觸發時，方塊已經在世界上了：掃到的數量包含正在放的這一個，要扣掉（以前第 4 個放不了）
        final LifeConfig.Item trapItem = config().station("fish_trap");
        final boolean counted = trapItem != null && placing.getType() == LifeService.material(trapItem.block(), Material.BARREL);
        if (scan.traps() - (counted ? 1 : 0) >= config().trapPerRaft()) {
            return "一塊筏最多 " + config().trapPerRaft() + " 個魚籠";
        }
        return null;
    }

    /** 魚籠放下去（開始計時）。 */
    void trapPlaced(final Block block) {
        traps.put(LifeService.key(block.getX(), block.getY(), block.getZ()), System.currentTimeMillis());
        scans.remove(service.plotAt(block.getX(), block.getZ()));
    }

    void trapRemoved(final Block block) {
        traps.remove(LifeService.key(block.getX(), block.getY(), block.getZ()));
        scans.remove(service.plotAt(block.getX(), block.getZ()));
    }

    /** 魚籠裡現在有幾條（不收）。 */
    int trapStored(final Block block) {
        final Long since = traps.get(LifeService.key(block.getX(), block.getY(), block.getZ()));
        if (since == null) {
            return 0;
        }
        final long each = config().trapMinutes() * 60_000L;
        return (int) Math.min(config().trapMax(), (System.currentTimeMillis() - since) / each);
    }

    /** 右鍵魚籠：收魚。 */
    void useTrap(final Player player, final LifeService.Profile profile, final Block block) {
        final String at = LifeService.key(block.getX(), block.getY(), block.getZ());
        final long now = System.currentTimeMillis();
        final Long since = traps.get(at);
        if (since == null) {
            traps.put(at, now); // 重開伺服器前放的、紀錄不見了：從現在開始算
            service.bar(player, "<gray>魚籠開始工作了：每 " + config().trapMinutes() + " 分鐘抓一條（最多存 " + config().trapMax() + " 條）");
            return;
        }
        if (!service.canBuild(player, profile, service.plotAt(block.getX(), block.getZ()))) {
            service.bar(player, "<gray>只有筏主、共用成員能收魚籠");
            return;
        }
        final long each = config().trapMinutes() * 60_000L;
        final int stored = (int) Math.min(config().trapMax(), (now - since) / each);
        if (stored <= 0) {
            service.bar(player, "<gray>魚籠還是空的（" + ((since + each - now + 59_999) / 60_000) + " 分鐘後有一條）");
            return;
        }
        // 滿了之後多出來的時間不算（上限）：從現在扣掉沒滿的那段零頭重新算
        traps.put(at, stored >= config().trapMax() ? now : since + stored * each);
        final Map<String, Integer> got = new LinkedHashMap<>();
        for (int i = 0; i < stored; i++) {
            final String fish = rollTrapFish();
            if (fish != null) {
                got.merge(fish, 1, Integer::sum);
            }
        }
        final StringBuilder text = new StringBuilder();
        for (final Map.Entry<String, Integer> entry : got.entrySet()) {
            service.addMaterial(profile, entry.getKey(), entry.getValue());
            service.feedMaterial(player, entry.getKey(), entry.getValue());
            recordDex(player, profile, entry.getKey(), 0, entry.getValue(), false);
            text.append(text.isEmpty() ? "" : "、").append(service.materialName(entry.getKey())).append(" +").append(entry.getValue());
        }
        service.addXp(player, profile, config().trapXp() * stored);
        player.playSound(block.getLocation(), Sound.BLOCK_BARREL_OPEN, 0.8f, 1.1f);
        service.bar(player, "<#7fd4ff>魚籠：" + ItemRenderer.escape(text.toString()), LifeService.CATCH_TICKS);
    }

    private String rollTrapFish() {
        double total = 0;
        for (final double weight : config().trapFish().values()) {
            total += weight;
        }
        if (total <= 0) {
            return null;
        }
        double at = random.nextDouble() * total;
        String last = null;
        for (final Map.Entry<String, Double> entry : config().trapFish().entrySet()) {
            last = entry.getKey();
            at -= entry.getValue();
            if (at < 0) {
                return entry.getKey();
            }
        }
        return last;
    }

    // ---- 魚圖鑑 ----

    /**
     * 記一條魚進圖鑑：sizeTenths 長度（0.1 公分；魚籠的不量 0）、count 幾條；tournament 要不要算進釣魚大賽。
     * 第一次釣到這種魚給生活經驗；每一種都釣過 → 稱號「全魚圖鑑」。
     */
    void recordDex(final Player player, final LifeService.Profile profile, final String fish, final int sizeTenths, final int count, final boolean tournament) {
        final long[] entry = profile.dex.get(fish);
        final boolean fresh = entry == null;
        final long[] record = fresh ? new long[] {0, 0} : entry;
        record[0] += count;
        final boolean bigger = sizeTenths > record[1];
        if (bigger) {
            record[1] = sizeTenths;
        }
        profile.dex.put(fish, record);
        profile.dirty = true;
        if (fresh) {
            service.addXp(player, profile, config().dexNewXp());
            player.sendMessage(plugin.text().prefixed("<#7fd4ff>📖 圖鑑新增：" + ItemRenderer.escape(service.materialName(fish))
                    + "<gray>（" + dexCount(profile) + "／" + config().fishing().fish().size() + " 種；生活經驗 +" + config().dexNewXp() + "）"));
            if (dexCount(profile) >= config().fishing().fish().size()) {
                service.flush(player);
                final LifeConfig.Title title = config().titles().get("angler");
                if (title != null) {
                    plugin.players().markDirty(player);
                    player.sendMessage(plugin.text().prefixed("<#7fd4ff>圖鑑全部集齊了！獲得稱號「" + ItemRenderer.escape(title.name()) + "」（稱號選單可以戴）"));
                    player.playSound(player.getLocation(), Sound.UI_TOAST_CHALLENGE_COMPLETE, 0.8f, 1.2f);
                }
            }
        } else if (bigger && sizeTenths > 0) {
            player.sendMessage(plugin.text().prefixed("<#7fd4ff>📖 " + ItemRenderer.escape(service.materialName(fish)) + "的新紀錄：<white>"
                    + String.format(Locale.ROOT, "%.1f", sizeTenths / 10.0) + " 公分"));
        }
        if (tournament && sizeTenths > 0) {
            recordTournament(player, fish, sizeTenths);
        }
    }

    /** 圖鑑釣過幾種（只算設定裡還有的魚）。 */
    int dexCount(final LifeService.Profile profile) {
        int count = 0;
        for (final LifeConfig.Fish fish : config().fishing().fish()) {
            if (profile.dex.containsKey(fish.material())) {
                count++;
            }
        }
        return count;
    }

    static String encodeDex(final Map<String, long[]> dex) {
        final StringBuilder out = new StringBuilder();
        for (final Map.Entry<String, long[]> entry : dex.entrySet()) {
            out.append(out.isEmpty() ? "" : ";").append(entry.getKey()).append(':').append(entry.getValue()[0]).append(':').append(entry.getValue()[1]);
        }
        return out.isEmpty() ? null : out.toString();
    }

    static Map<String, long[]> decodeDex(final String text) {
        final Map<String, long[]> dex = new LinkedHashMap<>();
        if (text == null || text.isBlank()) {
            return dex;
        }
        for (final String part : text.split(";")) {
            final String[] bits = part.split(":");
            if (bits.length == 3) {
                try {
                    dex.put(bits[0], new long[] {Long.parseLong(bits[1]), Long.parseLong(bits[2])});
                } catch (final NumberFormatException ignored) {
                    // 壞掉的跳過
                }
            }
        }
        return dex;
    }

    // ---- 釣魚大賽（life_tournament.txt：第一行 week=這一週；之後每行「UUID,名字,魚,長度(0.1cm),比例(0.0001)」；
    //      life_awards.txt：待領「UUID,名次,週」；settled=最近結算的那一週、champion=上一週第一名）----

    /** 一個人這一週最好的一條。 */
    record Entry(UUID id, String name, String fish, int sizeTenths, int ratio, long at) {
    }

    private String tournamentWeek = "";
    private final Map<UUID, Entry> tournament = new LinkedHashMap<>();
    /** 待領的獎：UUID → {名次, 週}。 */
    private final Map<UUID, String[]> awards = new HashMap<>();
    private String settledWeek = "";
    private UUID champion;
    private String championName = "";

    private Path tournamentFile() {
        return plugin.dataFolder().resolve("life_tournament.txt");
    }

    private Path awardFile() {
        return plugin.dataFolder().resolve("life_awards.txt");
    }

    void loadTournament() {
        tournament.clear();
        tournamentWeek = "";
        awards.clear();
        settledWeek = "";
        champion = null;
        try {
            if (Files.exists(tournamentFile())) {
                for (final String raw : Files.readAllLines(tournamentFile())) {
                    final String line = raw.trim();
                    if (line.startsWith("week=")) {
                        tournamentWeek = line.substring(5).trim();
                        continue;
                    }
                    final String[] parts = line.split(",", 6);
                    if (parts.length >= 5) {
                        try {
                            tournament.put(UUID.fromString(parts[0].trim()), new Entry(UUID.fromString(parts[0].trim()), parts[1].trim(), parts[2].trim(),
                                    Integer.parseInt(parts[3].trim()), Integer.parseInt(parts[4].trim()),
                                    parts.length > 5 ? Long.parseLong(parts[5].trim()) : 0));
                        } catch (final RuntimeException ignored) {
                            // 壞掉的一行跳過
                        }
                    }
                }
            }
            if (Files.exists(awardFile())) {
                for (final String raw : Files.readAllLines(awardFile())) {
                    final String line = raw.trim();
                    if (line.startsWith("settled=")) {
                        settledWeek = line.substring(8).trim();
                    } else if (line.startsWith("champion=")) {
                        final String[] parts = line.substring(9).split(",", 2);
                        try {
                            champion = UUID.fromString(parts[0].trim());
                            championName = parts.length > 1 ? parts[1].trim() : "";
                        } catch (final IllegalArgumentException ignored) {
                            champion = null;
                        }
                    } else {
                        final String[] parts = line.split(",", 3);
                        if (parts.length == 3) {
                            try {
                                awards.put(UUID.fromString(parts[0].trim()), new String[] {parts[1].trim(), parts[2].trim()});
                            } catch (final IllegalArgumentException ignored) {
                                // 壞掉的一行跳過
                            }
                        }
                    }
                }
            }
        } catch (final java.io.IOException e) {
            plugin.getSLF4JLogger().warn("{}：讀不到釣魚大賽的紀錄：{}", config().name(), e.toString());
        }
        settleIfNewWeek();
    }

    private void saveTournament() {
        final StringBuilder out = new StringBuilder("week=").append(tournamentWeek).append(System.lineSeparator());
        for (final Entry entry : tournament.values()) {
            out.append(entry.id()).append(',').append(entry.name().replace(',', ' ')).append(',').append(entry.fish()).append(',')
                    .append(entry.sizeTenths()).append(',').append(entry.ratio()).append(',').append(entry.at()).append(System.lineSeparator());
        }
        final StringBuilder aw = new StringBuilder("settled=").append(settledWeek).append(System.lineSeparator());
        if (champion != null) {
            aw.append("champion=").append(champion).append(',').append(championName.replace(',', ' ')).append(System.lineSeparator());
        }
        for (final Map.Entry<UUID, String[]> entry : awards.entrySet()) {
            aw.append(entry.getKey()).append(',').append(entry.getValue()[0]).append(',').append(entry.getValue()[1]).append(System.lineSeparator());
        }
        try {
            Files.writeString(tournamentFile(), out.toString());
            Files.writeString(awardFile(), aw.toString());
        } catch (final java.io.IOException e) {
            plugin.getSLF4JLogger().warn("{}：寫不進釣魚大賽的紀錄：{}", config().name(), e.toString());
        }
    }

    /** 過了一週：上一週結算（前三名記成待領的獎、第一名是釣王），這一週重新開始。每分鐘、讀檔時叫。 */
    void settleIfNewWeek() {
        final String now = config().week(System.currentTimeMillis());
        if (now.equals(tournamentWeek)) {
            return;
        }
        if (!tournamentWeek.isEmpty() && !tournament.isEmpty()) {
            final List<Entry> ranked = ranking();
            for (int i = 0; i < Math.min(3, ranked.size()); i++) {
                awards.put(ranked.get(i).id(), new String[] {String.valueOf(i + 1), tournamentWeek});
            }
            champion = ranked.getFirst().id();
            championName = ranked.getFirst().name();
            settledWeek = tournamentWeek;
            plugin.getSLF4JLogger().info("{}：釣魚大賽 {} 結算，第一名 {}", config().name(), tournamentWeek, championName);
            // 在線的人馬上領（下線的上線時領）
            for (final Player online : plugin.getServer().getOnlinePlayers()) {
                claimAward(online);
            }
            for (final Player online : plugin.getServer().getOnlinePlayers()) {
                plugin.players().markDirty(online); // 釣王換人了：稱號重算
            }
        }
        tournamentWeek = now;
        tournament.clear();
        saveTournament();
    }

    /** 這一週的排名：比例高的在前；一樣的比公分數；還一樣的先釣到的在前（2026-10-23 晚：平手的規則寫清楚）。 */
    List<Entry> ranking() {
        final List<Entry> ranked = new ArrayList<>(tournament.values());
        ranked.sort((a, b) -> a.ratio() != b.ratio() ? Integer.compare(b.ratio(), a.ratio())
                : a.sizeTenths() != b.sizeTenths() ? Integer.compare(b.sizeTenths(), a.sizeTenths())
                : Long.compare(a.at() == 0 ? Long.MAX_VALUE : a.at(), b.at() == 0 ? Long.MAX_VALUE : b.at()));
        return ranked;
    }

    Entry myEntry(final UUID id) {
        return tournament.get(id);
    }

    String tournamentWeek() {
        return tournamentWeek;
    }

    /** 上一週第一名（釣王）；沒有 null。 */
    UUID champion() {
        return champion;
    }

    /** 最近結算的那一週（釣王的稱號看這個）。 */
    String settledWeek() {
        return settledWeek;
    }

    String championName() {
        return championName;
    }

    private void recordTournament(final Player player, final String fish, final int sizeTenths) {
        settleIfNewWeek();
        final LifeConfig.Fish def = config().fish(fish);
        if (def == null || config().tournamentRewards().isEmpty()) {
            return;
        }
        final int ratio = (int) Math.round(10_000.0 * sizeTenths / (def.size()[1] * 10.0));
        final Entry old = tournament.get(player.getUniqueId());
        if (old != null && (old.ratio() > ratio || old.ratio() == ratio && old.sizeTenths() >= sizeTenths)) {
            // 2026-10-23 晚：沒超過本週的紀錄也說一聲（玩家以為沒算到：105 公分的鰻只佔最大長度 88%，輸給 90% 的燈籠魚）
            player.sendMessage(plugin.text().prefixed("<gray>釣魚大賽：這條佔最大長度 " + ratio / 100 + "%，沒超過你本週的 "
                    + ItemRenderer.escape(service.materialName(old.fish())) + "（" + old.ratio() / 100 + "%）"));
            return;
        }
        tournament.put(player.getUniqueId(), new Entry(player.getUniqueId(), player.getName(), fish, sizeTenths, ratio, System.currentTimeMillis()));
        final List<Entry> ranked = ranking();
        int rank = 0;
        for (int i = 0; i < ranked.size(); i++) {
            if (ranked.get(i).id().equals(player.getUniqueId())) {
                rank = i + 1;
            }
        }
        player.sendMessage(plugin.text().prefixed("<#ffd27f>🏆 釣魚大賽：" + ItemRenderer.escape(service.materialName(fish)) + " "
                + String.format(Locale.ROOT, "%.1f", sizeTenths / 10.0) + " 公分（" + ratio / 100 + "% 的最大長度）<gray>，本週第 " + rank + " 名"));
        saveTournament();
    }

    /** 上線、結算時：有待領的獎就發。 */
    void claimAward(final Player player) {
        final String[] award = awards.get(player.getUniqueId());
        if (award == null) {
            return;
        }
        final LifeService.Profile profile = service.profile(player);
        if (profile == null) {
            return;
        }
        final int rank = Integer.parseInt(award[0]);
        final List<LifeConfig.TournamentReward> rewards = config().tournamentRewards();
        if (rank < 1 || rank > rewards.size()) {
            awards.remove(player.getUniqueId());
            saveTournament();
            return;
        }
        final LifeConfig.TournamentReward reward = rewards.get(rank - 1);
        final StringBuilder got = new StringBuilder();
        for (final Map.Entry<String, Integer> entry : reward.materials().entrySet()) {
            service.addMaterial(profile, entry.getKey(), entry.getValue());
            got.append(got.isEmpty() ? "" : "、").append(service.materialName(entry.getKey())).append(" +").append(entry.getValue());
        }
        service.addXp(player, profile, reward.xp());
        if (rank == 1) {
            profile.champWeek = award[1];
            profile.dirty = true;
        }
        awards.remove(player.getUniqueId());
        saveTournament();
        service.flush(player);
        plugin.players().markDirty(player); // 稱號重算
        player.sendMessage(plugin.text().prefixed("<#ffd27f>🏆 釣魚大賽 " + award[1] + " 第 " + rank + " 名！<gray>" + ItemRenderer.escape(got.toString())
                + "、生活經驗 +" + reward.xp() + (rank == 1 ? "；稱號「釣王」是你的了（下一週有人贏就換人）" : "")));
        player.playSound(player.getLocation(), Sound.UI_TOAST_CHALLENGE_COMPLETE, 0.8f, 1.1f);
    }

    // ---- 生活委託 ----

    /** 今天的委託（照帳號＋日期）。 */
    List<LifeConfig.OrderToday> ordersToday(final Player player, final LifeService.Profile profile) {
        final String today = config().day(System.currentTimeMillis());
        if (!today.equals(profile.ordersDay)) {
            profile.ordersDay = today;
            profile.ordersDone = 0;
            profile.dirty = true;
        }
        return config().ordersFor(player.getUniqueId().getLeastSignificantBits() ^ player.getUniqueId().getMostSignificantBits(), today,
                config().levelOf(profile.xp));
    }

    boolean orderDone(final LifeService.Profile profile, final int index) {
        return (profile.ordersDone & 1 << index) != 0;
    }

    /** 背包裡有幾個這種料理。 */
    int foodInInventory(final Player player, final String foodId) {
        int count = 0;
        for (final ItemStack stack : player.getInventory().getContents()) {
            final LifeConfig.Food food = service.foodOf(stack);
            if (food != null && food.id().equals(foodId)) {
                count += stack.getAmount();
            }
        }
        return count;
    }

    /** 交委託（營地的選單）；回傳問題，成功 null。 */
    String turnIn(final Player player, final int index) {
        final LifeService.Profile profile = service.profile(player);
        if (profile == null) {
            return "帳號資料載入中，請稍等";
        }
        if (plugin.zones() == null || !plugin.zones().inTown(player.getLocation())) {
            return "要回營地才能交委託";
        }
        final List<LifeConfig.OrderToday> orders = ordersToday(player, profile);
        if (index < 0 || index >= orders.size()) {
            return "沒有這張委託";
        }
        if (orderDone(profile, index)) {
            return "這張已經交過了";
        }
        final LifeConfig.OrderToday order = orders.get(index);
        final LifeConfig.OrderDef def = order.def();
        if ("food".equals(def.kind())) {
            if (foodInInventory(player, def.need()) < order.amount()) {
                return "背包裡的" + orderNeedName(def) + "不夠（要 " + order.amount() + "）";
            }
            int left = order.amount();
            for (final ItemStack stack : player.getInventory().getContents()) {
                final LifeConfig.Food food = service.foodOf(stack);
                if (left > 0 && food != null && food.id().equals(def.need())) {
                    final int take = Math.min(left, stack.getAmount());
                    stack.setAmount(stack.getAmount() - take);
                    left -= take;
                }
            }
        } else {
            if (profile.materials.getOrDefault(def.need(), 0) < order.amount()) {
                return orderNeedName(def) + "不夠（要 " + order.amount() + "，有 " + profile.materials.getOrDefault(def.need(), 0) + "）";
            }
            profile.materials.merge(def.need(), -order.amount(), Integer::sum);
            profile.materials.values().removeIf(count -> count <= 0);
        }
        profile.ordersDone |= 1 << index;
        profile.dirty = true;
        final StringBuilder got = new StringBuilder("生活經驗 +" + def.xp());
        service.addXp(player, profile, def.xp());
        if (def.gold() > 0 && plugin.gold() != null && plugin.gold().earn(player, def.gold(), tw.exilecore.economy.EconomyLedger.EVENT)) {
            got.append("、金幣 +").append(String.format(Locale.ROOT, "%,d", def.gold()));
        }
        if (!config().bottleCurrencies().isEmpty() && random.nextDouble() < config().orderCurrencyChance() && service.takeCurrencyQuota(profile)) {
            final String id = config().bottleCurrencies().get(random.nextInt(config().bottleCurrencies().size()));
            service.giveCurrency(player, id, 1);
            got.append("、").append(service.currencyName(id));
        }
        service.flush(player);
        player.playSound(player.getLocation(), Sound.ENTITY_VILLAGER_YES, 0.8f, 1.1f);
        player.sendMessage(plugin.text().prefixed("<#f5b942>委託完成：<gray>" + ItemRenderer.escape(got.toString())));
        return null;
    }

    String orderNeedName(final LifeConfig.OrderDef def) {
        if ("food".equals(def.kind())) {
            final LifeConfig.Food food = config().foods().get(def.need());
            return food == null ? def.need() : food.name();
        }
        return service.materialName(def.need());
    }

    // ---- 按讚、人氣榜（life_likes.txt：第一行 week=；之後「筏的編號=讚數」）----

    private String likesWeek = "";
    private final Map<Integer, Integer> likes = new HashMap<>();

    private Path likesFile() {
        return plugin.dataFolder().resolve("life_likes.txt");
    }

    void loadLikes() {
        likes.clear();
        likesWeek = "";
        try {
            if (Files.exists(likesFile())) {
                for (final String raw : Files.readAllLines(likesFile())) {
                    final String line = raw.trim();
                    if (line.startsWith("week=")) {
                        likesWeek = line.substring(5).trim();
                        continue;
                    }
                    final int eq = line.indexOf('=');
                    if (eq > 0) {
                        try {
                            likes.put(Integer.parseInt(line.substring(0, eq).trim()), Integer.parseInt(line.substring(eq + 1).trim()));
                        } catch (final NumberFormatException ignored) {
                            // 壞掉的一行跳過
                        }
                    }
                }
            }
        } catch (final java.io.IOException e) {
            plugin.getSLF4JLogger().warn("{}：讀不到 life_likes.txt：{}", config().name(), e.toString());
        }
        rollLikesWeek();
    }

    private void rollLikesWeek() {
        final String now = config().week(System.currentTimeMillis());
        if (!now.equals(likesWeek)) {
            likesWeek = now;
            likes.clear();
            saveLikes();
        }
    }

    private void saveLikes() {
        final StringBuilder out = new StringBuilder("week=").append(likesWeek).append(System.lineSeparator());
        for (final Map.Entry<Integer, Integer> entry : new java.util.TreeMap<>(likes).entrySet()) {
            out.append(entry.getKey()).append('=').append(entry.getValue()).append(System.lineSeparator());
        }
        try {
            Files.writeString(likesFile(), out.toString());
        } catch (final java.io.IOException e) {
            plugin.getSLF4JLogger().warn("{}：寫不進 life_likes.txt：{}", config().name(), e.toString());
        }
    }

    /** /sea like：給現在待的這塊筏按讚；回傳問題，成功 null。 */
    String like(final Player player) {
        final LifeService.Profile profile = service.profile(player);
        if (profile == null) {
            return "帳號資料載入中，請稍等";
        }
        final int plot = service.activePlot(player);
        if (plot < 0 || service.world() == null || !service.world().equals(player.getWorld())) {
            return "要在別人的筏上才能按讚";
        }
        if (plot == profile.plot) {
            return "不能給自己的筏按讚";
        }
        rollLikesWeek();
        final String today = config().day(System.currentTimeMillis());
        if (!today.equals(profile.likesDay)) {
            profile.likesDay = today;
            profile.likesCount = 0;
            profile.likedPlots.clear();
        }
        if (profile.likedPlots.contains(plot)) {
            return "今天已經給這塊筏按過讚了";
        }
        if (profile.likesCount >= config().likesPerDay()) {
            return "今天的讚用完了（每天 " + config().likesPerDay() + " 個）";
        }
        profile.likesCount++;
        profile.likedPlots.add(plot);
        profile.dirty = true;
        likes.merge(plot, 1, Integer::sum);
        saveLikes();
        final String owner = service.ownerName(plot);
        player.playSound(player.getLocation(), Sound.ENTITY_PLAYER_LEVELUP, 0.6f, 1.5f);
        player.sendMessage(plugin.text().prefixed("<#ff9be0>❤ 給 " + ItemRenderer.escape(owner) + " 的木筏按了讚<gray>（本週 " + likes.get(plot) + " 個）"));
        final UUID ownerId = service.ownerOf(plot);
        final Player online = ownerId == null ? null : plugin.getServer().getPlayer(ownerId);
        if (online != null && !online.equals(player)) {
            online.sendMessage(plugin.text().prefixed("<#ff9be0>❤ " + ItemRenderer.escape(player.getName()) + " 給你的木筏按了讚<gray>（本週 " + likes.get(plot) + " 個）"));
        }
        return null;
    }

    /** 本週人氣榜：{筏的編號, 讚數}，多的在前。 */
    List<int[]> likeRanking() {
        rollLikesWeek();
        final List<int[]> ranked = new ArrayList<>();
        for (final Map.Entry<Integer, Integer> entry : likes.entrySet()) {
            ranked.add(new int[] {entry.getKey(), entry.getValue()});
        }
        ranked.sort((a, b) -> Integer.compare(b[1], a[1]));
        return ranked;
    }

    int likesOf(final int plot) {
        rollLikesWeek();
        return likes.getOrDefault(plot, 0);
    }

    // ---- 渦齒鯊王 ----

    static final String BAIT_ITEM = "tool:shark_bait";

    /** 鯊王血餌（成品，可以交易）。 */
    ItemStack baitItem(final int amount) {
        final LifeConfig.SharkKing def = config().sharkKing();
        final ItemStack stack = new ItemStack(Material.RED_DYE, Math.max(1, amount));
        stack.editMeta(meta -> {
            meta.displayName(ItemRenderer.line("<#ff6b6b>" + ItemRenderer.escape(def == null ? "鯊王血餌" : def.baitName())));
            meta.lore(List.of(ItemRenderer.line("<gray>在自己的筏上右鍵：叫出" + ItemRenderer.escape(def == null ? "渦齒鯊王" : def.name())),
                    ItemRenderer.line("<gray>牠會跳上筏：" + (def == null ? 420 : def.fleeSeconds()) + " 秒內打倒牠（極境的強度）；筏變成戰鬥區"),
                    ItemRenderer.line("<dark_gray>生活道具（可以交易）")));
            meta.getPersistentDataContainer().set(service.itemKey(), PersistentDataType.STRING, BAIT_ITEM);
        });
        return stack;
    }

    /** 一隻在的鯊王。 */
    private static final class King {
        final LivingEntity entity;
        final int plot;
        final UUID owner;
        int age;
        int phase;
        boolean enraged;
        final java.util.Set<UUID> adds = new java.util.HashSet<>();
        /** 2026-10-24：Boss 血條（跟淵鯨一樣掛在畫面上）；筏上的人都看得到。 */
        final net.kyori.adventure.bossbar.BossBar bar = net.kyori.adventure.bossbar.BossBar.bossBar(net.kyori.adventure.text.Component.empty(), 1f,
                net.kyori.adventure.bossbar.BossBar.Color.RED, net.kyori.adventure.bossbar.BossBar.Overlay.NOTCHED_10);
        final java.util.Set<UUID> watching = new java.util.HashSet<>();

        King(final LivingEntity entity, final int plot, final UUID owner) {
            this.entity = entity;
            this.plot = plot;
            this.owner = owner;
        }
    }

    private final Map<UUID, King> kings = new HashMap<>();

    boolean kingOn(final int plot) {
        for (final King king : kings.values()) {
            if (king.plot == plot) {
                return true;
            }
        }
        return false;
    }

    private King kingOf(final Entity entity) {
        for (final King king : kings.values()) {
            if (king.entity.getUniqueId().equals(entity.getUniqueId())) {
                return king;
            }
        }
        return null;
    }

    /** 這是不是鯊王（MapService：每秒傷害上限照比照的極境）。 */
    boolean isKing(final Entity entity) {
        return kingOf(entity) != null;
    }

    /** 2026-10-23 晚：比照的極境（maps.yml）的設定；沒寫 like、沒有地圖模組 null。 */
    tw.exilecore.map.MapConfig.Uber likeUber() {
        final LifeConfig.SharkKing def = config().sharkKing();
        if (def == null || def.like().isEmpty() || plugin.maps() == null) {
            return null;
        }
        return plugin.maps().config().uber(def.like());
    }

    private tw.exilecore.map.MapStats likeStats() {
        final tw.exilecore.map.MapConfig.Uber uber = likeUber();
        return uber == null ? null : plugin.maps().config().uberStats(config().sharkKing().like(), uber);
    }

    /** 怪物的屬性表（MonsterService 的 augmenter）：鯊王吃極境的怪物、Boss 詞綴，階段與狂暴的傷害。 */
    void augment(final LivingEntity entity, final tw.exilecore.stats.StatSheet sheet) {
        final King king = kingOf(entity);
        final LifeConfig.SharkKing def = config().sharkKing();
        if (king == null || def == null) {
            return;
        }
        final tw.exilecore.map.MapStats stats = likeStats();
        if (stats != null) {
            sheet.addAll(stats.monsterModifiers());
            sheet.addAll(stats.bossModifiers());
        }
        double more = 0;
        for (int i = 0; i < king.phase && i < def.phases().size(); i++) {
            more = def.phases().get(i).damage(); // 每個階段寫的是「這個階段起」的
        }
        if (more > 0) {
            sheet.add(new tw.exilecore.stats.Modifier(tw.exilecore.stats.StatKeys.DAMAGE, tw.exilecore.stats.ModType.MORE, more / 100.0,
                    java.util.Set.of(), def.name()));
        }
        if (king.enraged && def.enrageDamage() > 0) {
            sheet.add(new tw.exilecore.stats.Modifier(tw.exilecore.stats.StatKeys.DAMAGE, tw.exilecore.stats.ModType.MORE, def.enrageDamage() / 100.0,
                    java.util.Set.of(), def.name() + "（狂暴）"));
        }
    }

    /** 鯊王在的筏上的玩家：吃極境的玩家詞綴（看場合的增益來源）。 */
    List<tw.exilecore.stats.Modifier> playerModifiers(final Player player) {
        if (kings.isEmpty() || service.world() == null || !service.world().equals(player.getWorld())) {
            return List.of();
        }
        final int here = service.plotAt(player.getLocation().getX(), player.getLocation().getZ());
        if (!kingOn(here)) {
            return List.of();
        }
        final tw.exilecore.map.MapStats stats = likeStats();
        return stats == null ? List.of() : stats.playerModifiers();
    }

    /** 筏上可以站的高度（地板上面一格）。 */
    private double deckY() {
        return config().seaLevel() + 1.0;
    }

    /** 把位置夾在筏的範圍裡（鯊王跳不出筏）。 */
    private Location clampToRaft(final int plot, final Location at) {
        final int[] center = service.plotCenter(plot);
        final int[] range = LifeService.extent(service.raftSize(plot));
        final double x = Math.max(center[0] + range[0] + 0.5, Math.min(center[0] + range[1] + 0.5, at.getX()));
        final double z = Math.max(center[1] + range[0] + 0.5, Math.min(center[1] + range[1] + 0.5, at.getZ()));
        final Location out = new Location(at.getWorld(), x, deckY(), z, at.getYaw(), at.getPitch());
        return out;
    }

    /** 右鍵鯊王血餌；回傳問題，成功 null（血餌用掉一顆）。 */
    String useBait(final Player player, final ItemStack stack) {
        final LifeConfig.SharkKing def = config().sharkKing();
        final LifeService.Profile profile = service.profile(player);
        if (def == null) {
            return "現在沒有鯊王";
        }
        if (profile == null) {
            return "帳號資料載入中，請稍等";
        }
        final int plot = service.activePlot(player);
        if (service.world() == null || !service.world().equals(player.getWorld()) || plot < 0 || plot != profile.plot
                || service.plotAt(player.getLocation().getX(), player.getLocation().getZ()) != plot) {
            return "要在自己的筏上用";
        }
        if (kingOn(plot) || kings.containsKey(player.getUniqueId())) {
            return "鯊王已經在了";
        }
        if (service.islands().busy(plot) || service.whale().busy(plot)) {
            return "航行中、島還開著、或正在打淵鯨：先弄完";
        }
        final org.bukkit.entity.EntityType type;
        try {
            type = org.bukkit.entity.EntityType.valueOf(def.type());
        } catch (final IllegalArgumentException e) {
            return "life.yml 的 shark_king.type 寫錯了（" + def.type() + "）";
        }
        // 2026-10-23 晚：跳上筏打（以前在海裡游，筏上的人打不太到）：從筏的另一邊跳上來
        final int[] center = service.plotCenter(plot);
        final Location from = player.getLocation();
        final Location at = clampToRaft(plot, new Location(service.world(), center[0] * 2 - from.getX() + 0.5, deckY(), center[1] * 2 - from.getZ() + 0.5));
        at.setDirection(from.toVector().subtract(at.toVector()).setY(0));
        final Entity entity = service.world().spawnEntity(at, type, org.bukkit.event.entity.CreatureSpawnEvent.SpawnReason.CUSTOM);
        if (!(entity instanceof LivingEntity king)) {
            entity.remove();
            return "叫不出來（看主控台）";
        }
        king.setAI(false); // 不用原版的 AI：程式帶著牠在筏上追人（海豚沒 AI 也不會乾掉）
        king.setGravity(false);
        king.setPersistent(false);
        king.setRemoveWhenFarAway(false);
        final King fight = new King(king, plot, player.getUniqueId());
        kings.put(player.getUniqueId(), fight); // 先登記：decorateBoss 算屬性表時 augment 認得
        final List<Player> crew = service.crewOn(plot);
        try {
            final tw.exilecore.map.MapStats stats = likeStats();
            final int level = stats != null ? stats.monsterLevel() : Math.max(1, plugin.players().get(player).level() + 5);
            final tw.exilecore.monster.MonsterConfig.TypeSettings settings = plugin.monsters().config().type(def.type());
            final double tierLife = plugin.maps() == null ? 1 : plugin.maps().config().finalLifeMultiplier(plugin.maps().config().tiers().max());
            plugin.monsters().fixPlayers(king, Math.max(1, crew.size())); // 一起打的人多，血量照隊伍的規則加
            plugin.monsters().decorateBoss(king, level, def.name(), def.mods(), def.life() * tierLife / Math.max(0.1, settings.lifeMultiplier()),
                    def.damage() / Math.max(0.1, settings.damageMultiplier()), def.scale());
            plugin.monsters().markSummoned(king); // 不掉一般的東西、不給經驗（獎勵是下面的掉落）
        } catch (final RuntimeException e) {
            kings.remove(player.getUniqueId());
            king.remove();
            plugin.getSLF4JLogger().error("{}：等級化失敗", def.name(), e);
            return "叫不出來（看主控台）";
        }
        king.getPersistentDataContainer().set(service.driftKey(), PersistentDataType.STRING, "king:" + player.getUniqueId());
        if (plugin.skills() != null) {
            final List<tw.exilecore.skill.BossSkillUse> uses = new ArrayList<>();
            for (final tw.exilecore.skill.BossSkillUse use : def.skills()) {
                if (plugin.skills().config().bossSkills().skill(use.id()) != null) {
                    uses.add(use);
                }
            }
            // 極境的招式冷卻（cooldown 0.75）在 augment 加的怪物屬性裡（skill.cooldown）
            plugin.skills().bossSkills().attach(king, uses, new tw.exilecore.skill.BossSkills.Host() {
                @Override
                public String name() {
                    return def.name();
                }

                @Override
                public int phase() {
                    return fight.phase;
                }

                @Override
                public boolean dormant() {
                    return service.crewOn(fight.plot).isEmpty();
                }

                @Override
                public boolean alerts() {
                    return true;
                }

                @Override
                public String tempoKind() {
                    return "pinnacle";
                }

                @Override
                public Location arenaCenter() {
                    final int[] c = service.plotCenter(fight.plot);
                    return new Location(service.world(), c[0] + 0.5, deckY(), c[1] + 0.5);
                }

                @Override
                public LivingEntity summon(final String summonType, final Location where) {
                    return summonAdd(fight, summonType, where);
                }
            });
        }
        stack.setAmount(stack.getAmount() - 1);
        service.markCombat(plot, true);
        service.world().playSound(at, Sound.ENTITY_PLAYER_SPLASH_HIGH_SPEED, 1.5f, 0.6f);
        service.world().spawnParticle(Particle.SPLASH, at, 80, 1.5, 0.5, 1.5, 0.2);
        for (final Player member : crew) {
            member.showTitle(net.kyori.adventure.title.Title.title(plugin.text().parse("<#ff6b6b>" + ItemRenderer.escape(def.name()) + "跳上筏了！"),
                    plugin.text().parse("<gray>極境的強度——" + def.fleeSeconds() + " 秒內打倒牠"),
                    net.kyori.adventure.title.Title.Times.times(java.time.Duration.ofMillis(300), java.time.Duration.ofMillis(2000), java.time.Duration.ofMillis(500))));
            member.playSound(member.getLocation(), Sound.ENTITY_ELDER_GUARDIAN_CURSE, 0.8f, 0.8f);
            if (!def.intro().isEmpty()) {
                member.sendMessage(plugin.text().prefixed("<#ff6b6b>" + ItemRenderer.escape(def.intro())));
            }
        }
        return null;
    }

    /** 招式叫出來的小怪：放在筏上、跟著鯊王一起收掉。 */
    private LivingEntity summonAdd(final King king, final String typeName, final Location where) {
        final org.bukkit.entity.EntityType type;
        try {
            type = org.bukkit.entity.EntityType.valueOf(typeName.toUpperCase(Locale.ROOT));
        } catch (final IllegalArgumentException e) {
            return null;
        }
        final Entity entity = service.world().spawnEntity(clampToRaft(king.plot, where), type, org.bukkit.event.entity.CreatureSpawnEvent.SpawnReason.CUSTOM);
        if (!(entity instanceof LivingEntity add)) {
            entity.remove();
            return null;
        }
        add.setPersistent(false);
        try {
            plugin.monsters().markSummoned(add);
            plugin.monsters().decorate(add, plugin.monsters().data(king.entity).map(tw.exilecore.monster.MonsterData::level).orElse(80),
                    tw.exilecore.monster.MonsterRarity.NORMAL, List.of());
        } catch (final RuntimeException e) {
            add.remove();
            return null;
        }
        add.getPersistentDataContainer().set(service.driftKey(), PersistentDataType.STRING, "king_add:" + king.owner);
        king.adds.add(add.getUniqueId());
        return add;
    }

    /** 每 2 tick：鯊王在筏上追人、換階段、狂暴、時間到游走。 */
    void tickKings() {
        final LifeConfig.SharkKing def = config().sharkKing();
        for (final java.util.Iterator<Map.Entry<UUID, King>> it = kings.entrySet().iterator(); it.hasNext(); ) {
            final King king = it.next().getValue();
            final Player owner = plugin.getServer().getPlayer(king.owner);
            final List<Player> crew = service.crewOn(king.plot);
            if (!king.entity.isValid() || king.entity.isDead() || owner == null || service.world() == null || !service.world().equals(owner.getWorld())
                    || service.activePlot(owner) != king.plot && crew.isEmpty()) {
                removeKing(king);
                it.remove();
                continue;
            }
            king.age++;
            king.entity.setRemainingAir(king.entity.getMaximumAir());
            if (def != null) {
                updateKingBar(king, def, crew);
            }
            if (def != null && king.age > def.fleeSeconds() * 10) {
                for (final Player member : crew) {
                    member.sendMessage(plugin.text().prefixed("<gray>" + ItemRenderer.escape(def.name()) + "跳回海裡了（血餌沒了）"));
                }
                removeKing(king);
                it.remove();
                continue;
            }
            if (def != null) {
                // 狂暴
                if (!king.enraged && def.enrageAfter() > 0 && king.age >= def.enrageAfter() * 10) {
                    king.enraged = true;
                    plugin.monsters().invalidate(king.entity.getUniqueId());
                    for (final Player member : crew) {
                        member.sendMessage(plugin.text().prefixed("<red>" + ItemRenderer.escape(def.name()) + "狂暴了：傷害更多 " + Math.round(def.enrageDamage()) + "%"));
                    }
                }
                // 換階段（生命低於 at）
                final org.bukkit.attribute.AttributeInstance max = king.entity.getAttribute(org.bukkit.attribute.Attribute.MAX_HEALTH);
                final double ratio = max == null || max.getValue() <= 0 ? 1 : king.entity.getHealth() / max.getValue();
                while (king.phase < def.phases().size() && ratio <= def.phases().get(king.phase).at()) {
                    final LifeConfig.KingPhase phase = def.phases().get(king.phase);
                    king.phase++;
                    plugin.monsters().invalidate(king.entity.getUniqueId());
                    for (final Player member : crew) {
                        member.sendMessage(plugin.text().prefixed("<#ff6b6b>" + ItemRenderer.escape(phase.name().isEmpty() ? "第 " + (king.phase + 1) + " 階段" : phase.name())
                                + (phase.message().isEmpty() ? "" : "<gray> " + ItemRenderer.escape(phase.message()))
                                + (phase.damage() > 0 ? "<red>（傷害更多 " + Math.round(phase.damage()) + "%）" : "")));
                        member.playSound(member.getLocation(), Sound.ENTITY_RAVAGER_ROAR, 0.8f, 0.8f);
                    }
                }
            }
            // 在筏上追最近的人（跳不出筏）
            Player target = null;
            for (final Player member : crew) {
                if (target == null || member.getLocation().distanceSquared(king.entity.getLocation()) < target.getLocation().distanceSquared(king.entity.getLocation())) {
                    target = member;
                }
            }
            final Location at = king.entity.getLocation();
            if (target == null) {
                continue;
            }
            final org.bukkit.util.Vector to = target.getLocation().toVector().setY(deckY()).subtract(at.toVector());
            final double distance = to.clone().setY(0).length();
            Location next = at.clone();
            if (distance > 2.5) {
                final org.bukkit.util.Vector step = to.clone().setY(0).normalize().multiply(Math.min(distance - 2, king.enraged ? 0.32 : 0.24));
                next = at.clone().add(step);
            }
            next.setY(deckY());
            next = clampToRaft(king.plot, next);
            if (to.lengthSquared() > 1e-4) {
                next.setDirection(to.clone().setY(0));
            }
            if (next.distanceSquared(at) > 1e-4 || Math.abs(next.getYaw() - at.getYaw()) > 1) {
                king.entity.teleport(next);
            }
            if (king.age % 10 == 0) {
                service.world().spawnParticle(Particle.FALLING_WATER, at.clone().add(0, 1, 0), 8, 0.8, 0.4, 0.8, 0);
            }
            king.adds.removeIf(id -> {
                final Entity add = plugin.getServer().getEntity(id);
                return add == null || !add.isValid() || add.isDead();
            });
        }
    }

    /** 2026-10-24：鯊王的血條：生命比例、階段、狂暴、剩幾秒跳回海裡；筏上的人掛上、離開的收掉。 */
    private void updateKingBar(final King king, final LifeConfig.SharkKing def, final List<Player> crew) {
        final org.bukkit.attribute.AttributeInstance max = king.entity.getAttribute(org.bukkit.attribute.Attribute.MAX_HEALTH);
        final double ratio = max == null || max.getValue() <= 0 ? 1 : Math.max(0, Math.min(1, king.entity.getHealth() / max.getValue()));
        king.bar.progress((float) ratio);
        final long left = Math.max(0, def.fleeSeconds() - king.age / 10L);
        final String phase = king.phase <= 0 || king.phase > def.phases().size() ? "" : " <#ff6b6b>"
                + ItemRenderer.escape(def.phases().get(king.phase - 1).name().isEmpty() ? "第 " + (king.phase + 1) + " 階段" : def.phases().get(king.phase - 1).name());
        king.bar.name(plugin.text().parse("<#ff6b6b>" + ItemRenderer.escape(def.name()) + " <white>" + Math.round(ratio * 100) + "%" + phase
                + (king.enraged ? " <red>狂暴" : "") + " <dark_gray>｜ <gray>" + String.format(Locale.ROOT, "%d:%02d", left / 60, left % 60) + " 後跳回海裡"));
        final java.util.Set<UUID> now = new java.util.HashSet<>();
        for (final Player member : crew) {
            now.add(member.getUniqueId());
            if (king.watching.add(member.getUniqueId())) {
                member.showBossBar(king.bar);
            }
        }
        for (final java.util.Iterator<UUID> it = king.watching.iterator(); it.hasNext(); ) {
            final UUID id = it.next();
            if (!now.contains(id)) {
                final Player gone = plugin.getServer().getPlayer(id);
                if (gone != null) {
                    gone.hideBossBar(king.bar);
                }
                it.remove();
            }
        }
    }

    private void removeKing(final King king) {
        for (final UUID id : king.watching) {
            final Player watcher = plugin.getServer().getPlayer(id);
            if (watcher != null) {
                watcher.hideBossBar(king.bar); // 2026-10-24
            }
        }
        king.watching.clear();
        if (plugin.skills() != null) {
            plugin.skills().bossSkills().detach(king.entity.getUniqueId());
        }
        if (king.entity.isValid()) {
            king.entity.remove();
        }
        for (final UUID id : king.adds) {
            final Entity add = plugin.getServer().getEntity(id);
            if (add != null) {
                add.remove();
            }
        }
        king.adds.clear();
        service.markCombat(king.plot, false);
    }

    /** 鯊王被打倒（LifeService.onDeath）。 */
    void onKingDeath(final LivingEntity entity, final String tag) {
        if (plugin.skills() != null) {
            plugin.skills().bossSkills().detach(entity.getUniqueId());
        }
        King dead = null;
        for (final java.util.Iterator<Map.Entry<UUID, King>> it = kings.entrySet().iterator(); it.hasNext(); ) {
            final King king = it.next().getValue();
            if (king.entity.getUniqueId().equals(entity.getUniqueId())) {
                dead = king;
                it.remove();
            }
        }
        final LifeConfig.SharkKing def = config().sharkKing();
        if (dead == null || def == null) {
            return;
        }
        for (final UUID id : dead.adds) {
            final Entity add = plugin.getServer().getEntity(id);
            if (add != null) {
                add.remove();
            }
        }
        service.markCombat(dead.plot, false);
        // 筏上的每個人各拿一份
        final List<Player> crew = service.crewOn(dead.plot);
        final Player owner = plugin.getServer().getPlayer(dead.owner);
        if (owner != null && !crew.contains(owner)) {
            crew.add(owner);
        }
        for (final Player member : crew) {
            final LifeService.Profile profile = service.profile(member);
            if (profile == null) {
                continue;
            }
            final StringBuilder got = new StringBuilder();
            for (final Map.Entry<String, int[]> drop : def.drops().entrySet()) {
                final int amount = drop.getValue()[0] + random.nextInt(Math.max(1, drop.getValue()[1] - drop.getValue()[0] + 1));
                if (amount > 0) {
                    service.addMaterial(profile, drop.getKey(), amount);
                    got.append(got.isEmpty() ? "" : "、").append(service.materialName(drop.getKey())).append(" +").append(amount);
                }
            }
            for (final Map.Entry<String, Integer> sure : def.guaranteed().entrySet()) { // 2026-10-23 晚：保底通貨（照極境）
                service.giveCurrency(member, sure.getKey(), sure.getValue());
                got.append("、").append(service.currencyName(sure.getKey())).append(" ×").append(sure.getValue());
            }
            if (!def.currencies().isEmpty() && random.nextDouble() < def.currencyChance()) {
                final String id = def.currencies().get(random.nextInt(def.currencies().size()));
                service.giveCurrency(member, id, 1);
                got.append("、").append(service.currencyName(id));
            }
            service.addXp(member, profile, def.xp());
            profile.kingKills++;
            profile.dirty = true;
            service.flush(member);
            if (!def.defeat().isEmpty()) {
                member.sendMessage(plugin.text().prefixed("<gray>" + ItemRenderer.escape(def.defeat())));
            }
            member.sendMessage(plugin.text().prefixed("<#ff6b6b>打倒了" + ItemRenderer.escape(def.name()) + "！<gray>" + ItemRenderer.escape(got.toString())
                    + "、生活經驗 +" + def.xp()));
            member.playSound(member.getLocation(), Sound.UI_TOAST_CHALLENGE_COMPLETE, 0.8f, 1f);
            if (profile.kingKills == 1) {
                final LifeConfig.Title title = config().titles().get("shark_slayer");
                if (title != null) {
                    plugin.players().markDirty(member);
                    member.sendMessage(plugin.text().prefixed("<#ff6b6b>獲得稱號「" + ItemRenderer.escape(title.name()) + "」（稱號選單可以戴）"));
                }
            }
        }
    }

    void shutdown() {
        for (final King king : kings.values()) {
            removeKing(king);
        }
        kings.clear();
        saveTraps();
    }

    // ---- 藏寶圖 ----

    /** 撈到瓶中信、漂流箱：有機會附一張藏寶圖。回傳有沒有。 */
    boolean rollTreasureMap(final Player player, final LifeService.Profile profile, final boolean crate) {
        final double chance = crate ? config().treasureFromCrate() : config().treasureFromBottle();
        if (chance <= 0 || !config().materials().containsKey(LifeConfig.TREASURE_MAP) || random.nextDouble() >= chance) {
            return false;
        }
        service.addMaterial(profile, LifeConfig.TREASURE_MAP, 1);
        service.feedMaterial(player, LifeConfig.TREASURE_MAP, 1);
        player.sendMessage(plugin.text().prefixed("<#ffd27f>🗺 撿到一張" + ItemRenderer.escape(service.materialName(LifeConfig.TREASURE_MAP))
                + "！<gray>拿去海圖桌出航「藏寶島」"));
        return true;
    }
}
