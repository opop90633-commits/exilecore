package tw.exilecore.zone;

import net.kyori.adventure.title.Title;
import org.bukkit.Location;
import org.bukkit.NamespacedKey;
import org.bukkit.Particle;
import org.bukkit.Sound;
import org.bukkit.World;
import org.bukkit.entity.Display;
import org.bukkit.entity.Enemy;
import org.bukkit.entity.Entity;
import org.bukkit.entity.LivingEntity;
import org.bukkit.entity.Player;
import org.bukkit.entity.TextDisplay;
import org.bukkit.persistence.PersistentDataType;
import org.bukkit.util.BoundingBox;
import tw.exilecore.ExileCore;
import tw.exilecore.character.PlayerState;
import tw.exilecore.item.ItemRenderer;

import java.io.IOException;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.OptionalInt;
import java.util.UUID;

/**
 * 區域的執行期：哪個位置屬於哪個區域、怪物等級、進出區域的提示、營地安全區、
 * 檢查點（發現、重生點、地上的標記）、傳送與回城。
 * <p>
 * 角色進度的鍵：{@code cp.<區域>.<檢查點>}（發現過）、{@code cp.last}（最後碰到的檢查點＝重生點）、
 * {@code boss.<區域>}（打倒過的區域 Boss）。
 */
public final class ZoneService {

    /** 碰到檢查點的水平距離。 */
    private static final double CHECKPOINT_RADIUS = 3.0;
    /** 受到傷害後幾 tick 內不能傳送（檢查點、回到剛才的位置；回營地不受限制）。 */
    private static final long TELEPORT_COMBAT_TICKS = 60;
    /** 標記文字離地高度。 */
    private static final double MARKER_HEIGHT = 2.3;

    public static final String PROGRESS_LAST = "cp.last";

    private final ExileCore plugin;
    private final ZoneConfig config;
    private final ZoneLayout layout = new ZoneLayout();
    private final Path layoutFile;
    private final Map<String, Zone> zones = new LinkedHashMap<>();
    /** 玩家目前所在的區域（"" = 不在任何區域）。 */
    private final Map<UUID, String> current = new HashMap<>();
    /** /town 之前的位置（從營地傳回去用）。 */
    private final Map<UUID, Location> returnPoints = new HashMap<>();
    /** 檢查點標記：區域.檢查點 → 文字實體。 */
    private final Map<String, TextDisplay> markers = new HashMap<>();
    /** 管理員的選取範圍（pos1、pos2）。 */
    private final Map<UUID, Location[]> selections = new HashMap<>();
    /** 防刷：每個帳號在每個區域刷了多少。 */
    private final ZoneFatigue fatigue = new ZoneFatigue();
    private final NamespacedKey markerKey;

    public ZoneService(final ExileCore plugin, final ZoneConfig config) {
        this.plugin = plugin;
        this.config = config;
        this.layoutFile = plugin.dataFolder().resolve("zone-layout.yml");
        this.markerKey = new NamespacedKey(plugin, "zone_marker");
    }

    // ---- 載入 ----

    /** 讀 data/zones.yml 與 zone-layout.yml。 */
    public void load() throws IOException {
        config.load(plugin.dataFolder().resolve("data"));
        layout.load(layoutFile);
        rebuild();
    }

    /** 設定或版面改過之後重建區域清單。 */
    public void rebuild() {
        zones.clear();
        for (final ZoneConfig.ZoneDefinition definition : config.zones()) {
            zones.put(definition.id(), new Zone(definition, layout.getOrCreate(definition.id())));
        }
        removeMarkers();
    }

    public void saveLayout() throws IOException {
        layout.save(layoutFile);
    }

    public ZoneConfig config() {
        return config;
    }

    public ZoneLayout layout() {
        return layout;
    }

    // ---- 查詢 ----

    public Collection<Zone> zones() {
        return zones.values();
    }

    public Optional<Zone> zone(final String id) {
        return Optional.ofNullable(id == null ? null : zones.get(id));
    }

    /** 戰鬥區域（不是城鎮），依設定檔順序。 */
    public List<Zone> combatZones() {
        final List<Zone> list = new ArrayList<>();
        for (final Zone zone : zones.values()) {
            if (!zone.town()) {
                list.add(zone);
            }
        }
        return list;
    }

    public Optional<Zone> zoneAt(final Location location) {
        if (location == null || location.getWorld() == null) {
            return Optional.empty();
        }
        final String world = location.getWorld().getName();
        for (final Zone zone : zones.values()) {
            if (zone.contains(world, location.getX(), location.getY(), location.getZ())) {
                return Optional.of(zone);
            }
        }
        return Optional.empty();
    }

    /** 這個位置在區域裡的怪物等級；不在任何區域回傳空（由呼叫端決定）。 */
    public OptionalInt levelAt(final Location location) {
        final Optional<Zone> zone = zoneAt(location);
        return zone.map(value -> OptionalInt.of(value.levelAt(location.getX(), location.getZ()))).orElseGet(OptionalInt::empty);
    }

    public boolean inTown(final Location location) {
        return zoneAt(location).map(Zone::town).orElse(false);
    }

    // ---- 防刷 ----

    /**
     * 這個玩家在 location 殺了一隻怪：掉落要乘幾倍（防刷，見 {@link ZoneFatigue}）。
     * 不在劇情區域（例如在地圖裡）、或角色等級還沒到這個區域的最高等級（還在這裡升級）：1。
     * 掉落剛好又少了一級時提示一次。
     *
     * @param boss 是不是區域 Boss（算 boss_weight 隻）
     */
    public double lootFactor(final Player player, final Location location, final boolean boss) {
        final ZoneFatigue.Settings settings = config.fatigue();
        if (!settings.enabled()) {
            return 1;
        }
        final Zone zone = zoneAt(location).orElse(null);
        if (zone == null || zone.town()) {
            return 1;
        }
        final PlayerState state = plugin.players().get(player);
        if (!state.hasCharacter() || state.level() < zone.maxLevel()) {
            return 1;
        }
        final ZoneFatigue.Kill kill = fatigue.onKill(player.getUniqueId(), zone.id(), boss ? settings.bossWeight() : 1,
                System.currentTimeMillis(), settings);
        if (kill.announce()) {
            final int percent = (int) Math.round(kill.multiplier() * 100);
            final boolean floor = kill.multiplier() <= settings.floor() + 1e-9;
            player.sendMessage(plugin.text().prefixed("<#c9a0ff>這一帶的星骸被你吸得差不多了<gray>：在" + ItemRenderer.escape(zone.name())
                    + "的掉落剩 <white>" + percent + "%</white>" + (floor ? "（最低了）" : "")
                    + "。<white>地圖</white>不受影響；離開這個區域一陣子就會恢復"));
            player.playSound(player.getLocation(), Sound.BLOCK_AMETHYST_BLOCK_RESONATE, 0.6f, 0.7f);
        }
        return kill.multiplier();
    }

    /** 防刷的狀態（管理指令看、重設用）。 */
    public ZoneFatigue fatigue() {
        return fatigue;
    }

    /** 這個人在這個區域現在的掉落倍率（不記擊殺；管理指令看）。關掉防刷、城鎮回傳 1；不看等級（等級不到時實際上不會打折）。 */
    public double currentLootFactor(final Player player, final Zone zone) {
        final ZoneFatigue.Settings settings = config.fatigue();
        if (!settings.enabled() || zone.town()) {
            return 1;
        }
        return fatigue.current(player.getUniqueId(), zone.id(), System.currentTimeMillis(), settings);
    }

    /** 營地（第一個有落點的城鎮）。 */
    public Optional<Zone> camp() {
        for (final Zone zone : zones.values()) {
            if (zone.town() && zone.layout().entry() != null) {
                return Optional.of(zone);
            }
        }
        return Optional.empty();
    }

    public Location campLocation() {
        return camp().map(zone -> toLocation(zone.layout().entry())).orElse(null);
    }

    /** 版面的點轉成 Bukkit 位置；世界不存在時回傳 null。 */
    public Location toLocation(final ZoneLayout.Point point) {
        if (point == null) {
            return null;
        }
        final World world = plugin.getServer().getWorld(point.world());
        if (world == null) {
            return null;
        }
        return new Location(world, point.x(), point.y(), point.z(), point.yaw(), 0f);
    }

    public static ZoneLayout.Point toPoint(final Location location) {
        return new ZoneLayout.Point(location.getWorld().getName(), location.getX(), location.getY(), location.getZ(), location.getYaw());
    }

    // ---- 檢查點與進度 ----

    public static String discoveryKey(final Zone zone, final ZoneLayout.Checkpoint checkpoint) {
        return "cp." + zone.id() + "." + checkpoint.id();
    }

    public boolean discovered(final Player player, final Zone zone, final ZoneLayout.Checkpoint checkpoint) {
        return plugin.players().get(player).progress().containsKey(discoveryKey(zone, checkpoint));
    }

    public int discoveredCount(final Player player, final Zone zone) {
        int count = 0;
        for (final ZoneLayout.Checkpoint checkpoint : zone.checkpoints()) {
            if (discovered(player, zone, checkpoint)) {
                count++;
            }
        }
        return count;
    }

    public boolean bossDefeated(final Player player, final Zone zone) {
        return plugin.players().get(player).progress().containsKey("boss." + zone.id());
    }

    /** 最後碰到的檢查點（重生點）："區域.檢查點"。 */
    public String lastCheckpoint(final Player player) {
        return plugin.players().get(player).progress().get(PROGRESS_LAST);
    }

    /** 死亡後的重生點：最後碰到的檢查點，沒有就營地；都沒有回傳 null（照原版）。 */
    public Location respawnLocation(final Player player) {
        final String last = lastCheckpoint(player);
        if (last != null) {
            final int dot = last.indexOf('.');
            if (dot > 0) {
                final Zone zone = zones.get(last.substring(0, dot));
                if (zone != null) {
                    final ZoneLayout.Checkpoint checkpoint = zone.layout().checkpoint(last.substring(dot + 1));
                    if (checkpoint != null) {
                        final Location location = toLocation(checkpoint.point());
                        if (location != null) {
                            return location;
                        }
                    }
                }
            }
        }
        return campLocation();
    }

    // ---- 每 10 tick：玩家在哪個區域、有沒有碰到檢查點 ----

    public void trackPlayers() {
        for (final Player player : plugin.getServer().getOnlinePlayers()) {
            final Location location = player.getLocation();
            final Zone zone = zoneAt(location).orElse(null);
            final String id = zone == null ? "" : zone.id();
            final String previous = current.put(player.getUniqueId(), id);
            if (!id.equals(previous)) {
                onEnter(player, zone);
            }
            if (zone != null && !player.isDead()) {
                touchCheckpoints(player, zone, location);
            }
        }
    }

    private void onEnter(final Player player, final Zone zone) {
        final PlayerState state = plugin.players().get(player);
        if (zone == null || !state.hasCharacter()) {
            return;
        }
        introduce(player, state, zone);
        if (plugin.quests() != null) {
            plugin.quests().refresh(player); // 任務：進入區域
        }
        if (zone.town()) {
            // 回到營地：全部回滿，藥劑也補滿
            state.restoreAll();
            plugin.players().display(player, state);
            if (plugin.flasks() != null) {
                plugin.flasks().refill(player);
            }
            showTitle(player, "<#f5b942>" + ItemRenderer.escape(zone.name()), "<gray>安全區・生命、魔力與藥劑已回滿");
            return;
        }
        final String subtitle = state.level() < zone.minLevel() - 3
                ? "<red>" + zone.levelText() + "（你的等級偏低）"
                : "<gray>" + zone.levelText();
        showTitle(player, "<#e07a1f>" + ItemRenderer.escape(zone.name()), subtitle);
    }

    /** 每個角色第一次進到這個區域：標題之後在聊天欄出現一句旁白（進度 seen.區域）。 */
    private void introduce(final Player player, final PlayerState state, final Zone zone) {
        final String intro = zone.definition().intro();
        final String key = "seen." + zone.id();
        if (intro == null || intro.isBlank() || state.progress().containsKey(key)) {
            return;
        }
        plugin.characters().setProgress(player, key, "1");
        plugin.getServer().getScheduler().runTaskLater(plugin, () -> {
            if (player.isOnline()) {
                player.sendMessage(plugin.text().parse("<dark_gray>【" + ItemRenderer.escape(zone.name()) + "】</dark_gray> "
                        + Zone.loreLine(null, intro)));
            }
        }, 40);
    }

    private void showTitle(final Player player, final String title, final String subtitle) {
        player.showTitle(Title.title(plugin.text().parse(title), plugin.text().parse(subtitle),
                Title.Times.times(Duration.ofMillis(250), Duration.ofMillis(1800), Duration.ofMillis(500))));
    }

    private void touchCheckpoints(final Player player, final Zone zone, final Location location) {
        final PlayerState state = plugin.players().get(player);
        if (!state.hasCharacter()) {
            return;
        }
        final String world = location.getWorld().getName();
        for (final ZoneLayout.Checkpoint checkpoint : zone.checkpoints()) {
            final ZoneLayout.Point point = checkpoint.point();
            if (!point.world().equals(world) || Math.abs(point.y() - location.getY()) > 3
                    || point.distance2d(location.getX(), location.getZ()) > CHECKPOINT_RADIUS) {
                continue;
            }
            final String key = discoveryKey(zone, checkpoint);
            if (!state.progress().containsKey(key)) {
                plugin.characters().setProgress(player, key, "1");
                player.sendMessage(plugin.text().prefixed("<#7fd4ff>◆ 發現檢查點：<white>" + ItemRenderer.escape(checkpoint.name())
                        + " <gray>（" + ItemRenderer.escape(zone.name()) + "）　<dark_gray>/wp 可以傳送到這裡"));
                player.playSound(location, Sound.BLOCK_BEACON_ACTIVATE, 0.6f, 1.5f);
                if (plugin.quests() != null) {
                    plugin.quests().refresh(player); // 任務：走到檢查點
                }
            }
            final String last = zone.id() + "." + checkpoint.id();
            if (plugin.flasks() != null) {
                plugin.flasks().refillAtCheckpoint(player, last); // PoE2：碰到檢查點藥劑補滿
            }
            if (!last.equals(state.progress().get(PROGRESS_LAST))) {
                plugin.characters().setProgress(player, PROGRESS_LAST, last);
                state.notice("<#7fd4ff>重生點：" + ItemRenderer.escape(checkpoint.name()), plugin.getServer().getCurrentTick() + 60);
            }
            return;
        }
    }

    public void forget(final Player player) {
        current.remove(player.getUniqueId());
        returnPoints.remove(player.getUniqueId());
        selections.remove(player.getUniqueId());
    }

    // ---- 傳送 ----

    /** 傳送到檢查點、回到剛才的位置（受到攻擊後 3 秒內不行）。成功排程回傳 true。 */
    public boolean teleport(final Player player, final Location target, final String label) {
        if (target == null) {
            player.sendMessage(plugin.text().prefixed("<red>目的地的世界不存在"));
            return false;
        }
        final PlayerState state = plugin.players().get(player);
        final long now = plugin.getServer().getCurrentTick();
        if (now - state.lastDamageTick() < TELEPORT_COMBAT_TICKS) {
            player.sendMessage(plugin.text().prefixed("<red>剛受到攻擊，3 秒內不能傳送到檢查點（回營地隨時可以：/town）"));
            return false;
        }
        return move(player, target, label);
    }

    /** 直接傳送（不看有沒有被打）。到了之後有入場保護（營地除外）：移動前不會受傷。 */
    private boolean move(final Player player, final Location target, final String label) {
        player.teleportAsync(target).thenAccept(success -> {
            if (Boolean.TRUE.equals(success) && player.isOnline()) {
                player.playSound(target, Sound.ENTITY_ENDERMAN_TELEPORT, 0.6f, 1.2f);
                plugin.players().get(player).notice("<#7fd4ff>傳送到 " + ItemRenderer.escape(label), plugin.getServer().getCurrentTick() + 60);
                plugin.players().grace().grant(player);
            }
        });
        return true;
    }

    /** 新角色與切換角色後回到營地（有設定的話）。 */
    public void sendToCamp(final Player player, final boolean announce) {
        returnPoints.remove(player.getUniqueId()); // 換角色後不能用上一個角色的回程位置
        final Location camp = campLocation();
        if (camp == null) {
            if (announce) {
                player.sendMessage(plugin.text().prefixed("<red>伺服器還沒設定營地（管理員：/ec zone autolayout）"));
            }
            return;
        }
        player.teleportAsync(camp);
        if (announce) {
            player.sendMessage(plugin.text().prefixed("<gray>回到營地"));
        }
    }

    /** /town：免費回營地，隨時都可以（被打也可以，照 PoE 的回城）；記住原本的位置，之後可以從傳送選單回去。 */
    public void goTown(final Player player) {
        final Location camp = campLocation();
        if (camp == null) {
            player.sendMessage(plugin.text().prefixed("<red>伺服器還沒設定營地（管理員：/ec zone autolayout）"));
            return;
        }
        if (inTown(player.getLocation())) {
            player.sendMessage(plugin.text().prefixed("<gray>你已經在營地了"));
            return;
        }
        final boolean uber = plugin.maps() != null && plugin.maps().inUber(player);
        // 2026-10：極境裡回營地不再要求確認（離開就不能再回去，訊息照樣提醒）
        final Location from = player.getLocation().clone();
        // 競技場：不記回程位置（回去要走 /pvp，進場的檢查才會照做）
        final boolean arena = plugin.pvp() != null && plugin.pvp().inArena(player);
        if (move(player, camp, camp().map(Zone::name).orElse("營地"))) {
            if (arena) {
                player.sendMessage(plugin.text().prefixed("<gray>回到營地。回競技場用 <white>/pvp</white>"));
                return;
            }
            returnPoints.put(player.getUniqueId(), from);
            player.sendMessage(plugin.text().prefixed(uber ? "<gray>回到營地<dark_gray>（極境每個人只能進一次，不能再回去了）"
                    : "<gray>回到營地。<white>/wp</white> 可以傳回剛才的位置"));
        }
    }

    /** 掛機太久（{@link AfkWatcher}）：不問直接傳回營地（極境也一樣），不記回程位置（/wp 傳不回去）。 */
    void afkToTown(final Player player) {
        final Location camp = campLocation();
        if (camp == null || inTown(player.getLocation())) {
            return;
        }
        if (move(player, camp, camp().map(Zone::name).orElse("營地"))) {
            returnPoints.remove(player.getUniqueId()); // 之前 /town 記的回程位置也不留
            player.sendMessage(plugin.text().prefixed("<#ffd27f>掛機超過 " + (AfkWatcher.KICK_SECONDS / 60) + " 分鐘，傳回營地了"));
        }
    }

    public Location returnPoint(final Player player) {
        return returnPoints.get(player.getUniqueId());
    }

    public void returnFromTown(final Player player) {
        final Location target = returnPoints.get(player.getUniqueId());
        if (target == null) {
            player.sendMessage(plugin.text().prefixed("<gray>沒有可以回去的位置"));
            return;
        }
        final String blocked = plugin.maps() == null ? null : plugin.maps().returnProblem(player, target);
        if (blocked != null) {
            returnPoints.remove(player.getUniqueId());
            player.sendMessage(plugin.text().prefixed("<gray>" + blocked));
            return;
        }
        if (teleport(player, target, "剛才的位置")) {
            returnPoints.remove(player.getUniqueId());
        }
    }

    // ---- 檢查點標記（地上的字與粒子） ----

    /** 每 40 tick：區塊載入中的檢查點補上文字標記，附近有玩家就冒粒子。 */
    public void tickMarkers() {
        for (final Zone zone : zones.values()) {
            for (final ZoneLayout.Checkpoint checkpoint : zone.checkpoints()) {
                final Location location = toLocation(checkpoint.point());
                if (location == null) {
                    continue;
                }
                final World world = location.getWorld();
                if (!world.isChunkLoaded(location.getBlockX() >> 4, location.getBlockZ() >> 4)) {
                    continue;
                }
                final String key = zone.id() + "." + checkpoint.id();
                final TextDisplay marker = markers.get(key);
                if (marker == null || !marker.isValid()) {
                    markers.put(key, spawnMarker(location, zone, checkpoint, key));
                }
                if (!world.getNearbyPlayers(location, 24).isEmpty()) {
                    world.spawnParticle(Particle.END_ROD, location.getX(), location.getY() + 0.6, location.getZ(),
                            6, 0.35, 0.7, 0.35, 0.01);
                }
            }
        }
    }

    private TextDisplay spawnMarker(final Location location, final Zone zone, final ZoneLayout.Checkpoint checkpoint, final String key) {
        final Location at = location.clone().add(0, MARKER_HEIGHT, 0);
        at.setYaw(0f);
        return at.getWorld().spawn(at, TextDisplay.class, display -> {
            display.text(plugin.text().parse("<#7fd4ff>◆ " + ItemRenderer.escape(checkpoint.name())
                    + "\n<gray>" + ItemRenderer.escape(zone.name()) + "・檢查點"));
            display.setBillboard(Display.Billboard.CENTER);
            display.setShadowed(true);
            display.setSeeThrough(false);
            display.setDefaultBackground(false);
            display.setPersistent(false); // 不存進區塊：插件重開或區塊卸載就消失，不會留下重複的標記
            display.getPersistentDataContainer().set(markerKey, PersistentDataType.STRING, key);
        });
    }

    public void removeMarkers() {
        for (final TextDisplay marker : markers.values()) {
            if (marker.isValid()) {
                marker.remove();
            }
        }
        markers.clear();
    }

    // ---- 營地：清掉跑進來的怪物 ----

    /** 每 40 tick：營地範圍內的敵對生物移除（管理員用 /ec spawn 生的、玩家的召喚物除外）。 */
    public void sweepTowns() {
        final tw.exilecore.combat.MinionHooks minions = plugin.combat() == null ? null : plugin.combat().minions();
        for (final Zone zone : zones.values()) {
            if (!zone.town() || !zone.placed()) {
                continue;
            }
            final ZoneLayout.Box box = zone.layout().box();
            final World world = plugin.getServer().getWorld(box.world());
            if (world == null) {
                continue;
            }
            final BoundingBox bounds = new BoundingBox(box.minX(), box.minY(), box.minZ(), box.maxX() + 1, box.maxY() + 1, box.maxZ() + 1);
            for (final Entity entity : world.getNearbyEntities(bounds, entity -> entity instanceof Enemy)) {
                if (entity instanceof LivingEntity living && !(entity instanceof Player) && !plugin.monsters().isPinned(living)
                        && !plugin.npcs().isNpc(entity) && (minions == null || !minions.isMinion(entity))) {
                    entity.remove();
                }
            }
        }
    }

    // ---- 管理員選取 ----

    public void select(final Player player, final int index, final Location location) {
        selections.computeIfAbsent(player.getUniqueId(), uuid -> new Location[2])[index] = location.clone();
    }

    public Location[] selection(final Player player) {
        return selections.get(player.getUniqueId());
    }

    /** 目前所有玩家所在區域（除錯用）。 */
    public Map<UUID, String> current() {
        return current;
    }

    /** 移除所有標記（模組停用時）。 */
    public void shutdown() {
        removeMarkers();
        current.clear();
    }

    /** 列出版面缺什麼（/ec zone list 用）。 */
    public List<String> problems(final Zone zone) {
        final List<String> list = new ArrayList<>();
        if (!zone.placed()) {
            list.add("沒有範圍");
        }
        if (zone.layout().entry() == null) {
            list.add("沒有落點");
        }
        if (!zone.town()) {
            if (zone.checkpoints().isEmpty()) {
                list.add("沒有檢查點");
            }
            if (zone.boss() != null && zone.layout().arena() == null) {
                list.add("沒有 Boss 場地");
            }
        }
        return list;
    }
}
