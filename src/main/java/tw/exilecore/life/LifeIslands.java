package tw.exilecore.life;

import net.kyori.adventure.title.Title;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.Particle;
import org.bukkit.Sound;
import org.bukkit.World;
import org.bukkit.WeatherType;
import org.bukkit.block.Block;
import org.bukkit.block.data.BlockData;
import org.bukkit.entity.Entity;
import org.bukkit.entity.EntityType;
import org.bukkit.entity.LivingEntity;
import org.bukkit.entity.Mob;
import org.bukkit.entity.Player;
import org.bukkit.event.entity.CreatureSpawnEvent;
import org.bukkit.persistence.PersistentDataType;
import tw.exilecore.ExileCore;
import tw.exilecore.item.ItemRenderer;
import tw.exilecore.monster.MonsterRarity;

import java.time.Duration;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.random.RandomGenerator;

/**
 * 星骸之海（2026-10）：航行、小島（珊瑚礁、星骸殘島、沉船、星骸環礁）、海象。
 * 小島生在筏的東邊（筏中心往東 {@value #OFFSET} 格），是戰鬥區；用完（或離開筏）就清掉。
 */
final class LifeIslands {

    static final int OFFSET = 48;
    static final int RADIUS = 16;

    /** 一座正在的島。 */
    final class Island {
        final LifeConfig.Island def;
        final UUID owner;
        /** 出航的那塊筏；一起來的人（2026-10-16：同一塊筏上的人一起出航，寶箱每個人各拿一份）。 */
        int plot = -1;
        final Set<UUID> crew = new HashSet<>();
        final int cx;
        final int cz;
        final long expireAt;
        final Set<UUID> mobs = new HashSet<>();
        final Set<String> chests = new HashSet<>();
        final Set<String> pillars = new HashSet<>();
        int lit;
        int wave;
        LivingEntity captain;
        boolean captainOut;
        boolean done;

        Island(final LifeConfig.Island def, final UUID owner, final int cx, final int cz) {
            this.def = def;
            this.owner = owner;
            this.cx = cx;
            this.cz = cz;
            this.expireAt = System.currentTimeMillis() + def.minutes() * 60_000L;
        }

        boolean contains(final double x, final double z) {
            return Math.abs(x - cx) <= RADIUS + 0.5 && Math.abs(z - cz) <= RADIUS + 0.5;
        }
    }

    private record Voyage(LifeConfig.Island def, int arriveTick, int plot) {
    }

    private record ActiveWeather(LifeConfig.Weather def, int untilTick) {
    }

    private final ExileCore plugin;
    private final LifeService service;
    private final RandomGenerator random;
    private final Map<UUID, Island> islands = new HashMap<>();
    private final Map<UUID, Voyage> voyages = new HashMap<>();
    private final Map<UUID, ActiveWeather> weathers = new HashMap<>();
    private final Map<UUID, Integer> nextWeather = new HashMap<>();
    private int tick;

    LifeIslands(final ExileCore plugin, final LifeService service) {
        this.plugin = plugin;
        this.service = service;
        this.random = plugin.items().random();
    }

    private LifeConfig config() {
        return service.config();
    }

    private World world() {
        return service.world();
    }

    // ---- 查詢 ----

    /** 這個位置在某座島上（戰鬥區）。 */
    boolean onIsland(final Location location) {
        for (final Island island : islands.values()) {
            if (island.contains(location.getX(), location.getZ())) {
                return true;
            }
        }
        return false;
    }

    /** 這塊筏有沒有在航行、島還在（同一塊筏一次一趟）。 */
    /** 2026-10-17：這塊筏有別人的航行、或別人開的島還沒打完（自己的不算）。 */
    boolean busyByOthers(final int plot, final UUID self) {
        for (final Map.Entry<UUID, Voyage> entry : voyages.entrySet()) {
            if (entry.getValue().plot() == plot && !entry.getKey().equals(self)) {
                return true;
            }
        }
        for (final Map.Entry<UUID, Island> entry : islands.entrySet()) {
            if (entry.getValue().plot == plot && !entry.getKey().equals(self) && !entry.getValue().done) {
                return true;
            }
        }
        return false;
    }

    /** 島收掉前：還在島上的人送回筏上（以前直接掉進海裡）。 */
    private void sendCrewHome(final Island island) {
        for (final Player member : crewHere(island)) {
            member.teleportAsync(service.raftSpawn(island.plot));
        }
    }

    boolean busy(final int plot) {
        for (final Voyage voyage : voyages.values()) {
            if (voyage.plot() == plot) {
                return true;
            }
        }
        for (final Island island : islands.values()) {
            if (island.plot == plot) {
                return true;
            }
        }
        return false;
    }

    private Voyage voyageOn(final int plot) {
        for (final Voyage voyage : voyages.values()) {
            if (voyage.plot() == plot) {
                return voyage;
            }
        }
        return null;
    }

    /** 這個人在的島（自己開的、跟著來的）。 */
    private Island islandOf(final UUID id) {
        final Island own = islands.get(id);
        if (own != null) {
            return own;
        }
        for (final Island island : islands.values()) {
            if (island.crew.contains(id)) {
                return island;
            }
        }
        return null;
    }

    /** 島上一起的人（在線、在島上、沒倒下）。 */
    private List<Player> crewHere(final Island island) {
        final List<Player> here = new ArrayList<>();
        for (final UUID id : island.crew) {
            final Player player = plugin.getServer().getPlayer(id);
            if (player != null && !player.isDead() && world() != null && world().equals(player.getWorld())
                    && island.contains(player.getLocation().getX(), player.getLocation().getZ())) {
                here.add(player);
            }
        }
        return here;
    }

    /** 漂流物的生成倍率（海象）。 */
    double driftFactor(final Player player) {
        double factor = voyageOn(service.activePlot(player)) != null ? 1.6 : 1.0; // 航行中漂流物變多（同一塊筏上的人都算）
        final ActiveWeather weather = weathers.get(player.getUniqueId());
        if (weather != null) {
            factor *= weather.def().drift();
        }
        return factor;
    }

    /** 星潮：星骸碎片的權重倍數。 */
    double starShardBoost(final Player player) {
        final ActiveWeather weather = weathers.get(player.getUniqueId());
        return weather == null ? 1 : weather.def().starShard();
    }

    /** 2026-10-23：現在的海象給漂流物的權重倍數（星潮的星骸碎片、漩渦的漂流箱、海鳥群的瓶子）。 */
    Map<String, Double> driftBoost(final Player player) {
        final ActiveWeather weather = weathers.get(player.getUniqueId());
        return weather == null ? Map.of() : weather.def().boost();
    }

    /** 2026-10-23：現在的海象代號（沒有 null）。 */
    String weatherId(final Player player) {
        final ActiveWeather weather = weathers.get(player.getUniqueId());
        return weather == null ? null : weather.def().id();
    }

    /** 暴風：攔截網攔不到東西。 */
    boolean storm(final Player player) {
        final ActiveWeather weather = weathers.get(player.getUniqueId());
        return weather != null && "storm".equals(weather.def().id());
    }

    String status(final Player player) {
        final Voyage voyage = voyageOn(service.activePlot(player));
        if (voyage != null) {
            return "航行中：" + voyage.def().name() + "（" + Math.max(0, (voyage.arriveTick() - tick) / 10) + " 秒）";
        }
        final Island island = islandOf(player.getUniqueId());
        if (island != null) {
            return "停在" + island.def.name() + "（" + Math.max(0, (island.expireAt - System.currentTimeMillis() + 59_999) / 60_000) + " 分鐘後漂走）";
        }
        return null;
    }

    // ---- 航行 ----

    /** 筏上有沒有船帆。 */
    private boolean hasSail(final int[] center, final int size) {
        final LifeConfig.Item sail = config().station("sail");
        if (sail == null) {
            return true;
        }
        final Material type = LifeService.material(sail.block(), Material.WHITE_BANNER);
        final int[] range = LifeService.extent(size);
        final int sea = config().seaLevel();
        for (int dx = range[0]; dx <= range[1]; dx++) {
            for (int dz = range[0]; dz <= range[1]; dz++) {
                for (int y = sea + 1; y <= sea + config().buildHeight(); y++) {
                    final Material here = world().getBlockAt(center[0] + dx, y, center[1] + dz).getType();
                    if (here == type || here.name().equals(type.name().replace("_BANNER", "_WALL_BANNER"))) { // 2026-10：貼牆的也算
                        return true;
                    }
                }
            }
        }
        return false;
    }

    /** 出發（海圖桌選單）；回傳問題，成功 null。 */
    String sail(final Player player, final LifeConfig.Island def) {
        final LifeService.Profile profile = service.profile(player);
        final int plot = service.activePlot(player);
        if (profile == null || plot < 0) {
            return "帳號資料載入中，請稍等";
        }
        if (!service.canBuild(player, profile, plot)) {
            return "只有筏主、共用成員能掌舵";
        }
        final int level = config().levelOf(profile.xp);
        if (level < def.level()) {
            return "要生活等級 " + def.level();
        }
        if (voyages.containsKey(player.getUniqueId()) || voyageOn(plot) != null) {
            return "已經在航行了";
        }
        if (service.whale().fighting(player) || service.whale().busy(plot)) {
            return "正在跟淵鯨打，先打完再出航";
        }
        if (busyByOthers(plot, player.getUniqueId())) {
            return "這塊筏上別人開的島還沒打完（等他們打完、或島漂走）"; // 2026-10-17：以前會直接把別人正在打的島收掉
        }
        if (service.extras().kingOn(plot)) {
            return "鯊王還在，先打完"; // 2026-10-23
        }
        final int[] center = service.plotCenter(plot);
        if (!hasSail(center, config().raftSize(service.plotLevel(plot)))) {
            return "筏上要有一面船帆";
        }
        for (final Map.Entry<String, Integer> need : def.needs().entrySet()) { // 2026-10-23：藏寶圖
            if (profile.materials.getOrDefault(need.getKey(), 0) < need.getValue()) {
                return "要 " + service.materialName(need.getKey()) + " ×" + need.getValue();
            }
        }
        if (!service.spendStamina(player, profile, def.stamina())) {
            return "精力不夠（要 " + def.stamina() + "）";
        }
        for (final Map.Entry<String, Integer> need : def.needs().entrySet()) {
            profile.materials.merge(need.getKey(), -need.getValue(), Integer::sum);
        }
        profile.materials.values().removeIf(count -> count <= 0);
        profile.dirty = true;
        // 這塊筏上的島（自己的、別人開的）先收掉：同一個位置
        for (final java.util.Iterator<Map.Entry<UUID, Island>> it = islands.entrySet().iterator(); it.hasNext(); ) {
            final Island old = it.next().getValue();
            if (old.plot == plot || old.owner.equals(player.getUniqueId())) {
                it.remove();
                sendCrewHome(old);
                clear(old);
            }
        }
        voyages.put(player.getUniqueId(), new Voyage(def, tick + def.travelSeconds() * 10, plot));
        final List<Player> crew = service.crewOn(plot);
        if (!crew.contains(player)) {
            crew.add(player);
        }
        for (final Player member : crew) {
            member.playSound(member.getLocation(), Sound.ENTITY_BOAT_PADDLE_WATER, 1f, 0.8f);
            member.sendMessage(plugin.text().prefixed("<" + config().color() + ">起航：" + ItemRenderer.escape(def.name()) + "<gray>（"
                    + def.travelSeconds() + " 秒；路上漂流物會變多" + (crew.size() > 1 ? "；筏上的人一起上島" : "") + "）"));
        }
        return null;
    }

    // ---- 每 2 tick（LifeService 的筏主那一輪呼叫）----

    /** 共用的時鐘：LifeService 每 2 tick 叫一次（不是每個人各叫一次）。 */
    void advance() {
        tick++;
    }

    void tick(final Player player, final LifeService.Profile profile, final int[] center) {
        final UUID id = player.getUniqueId();
        final Voyage voyage = voyages.get(id);
        if (voyage != null) {
            final int left = (voyage.arriveTick() - tick) / 10;
            if (tick % 10 == 0) {
                for (final Player member : service.crewOn(voyage.plot())) {
                    tw.exilecore.gui.ActionBar.send(member, plugin.text().parse("<" + config().color() + ">⛵ 航向" + ItemRenderer.escape(voyage.def().name())
                            + " <white>" + Math.max(0, left) + " 秒"));
                }
                world().spawnParticle(Particle.SPLASH, player.getLocation().add(2, -0.5, 0), 20, 2, 0.1, 2, 0.1);
            }
            if (tick >= voyage.arriveTick()) {
                voyages.remove(id);
                arrive(player, profile, service.plotCenter(voyage.plot()), voyage.def(), voyage.plot());
            }
        }
        final Island island = islands.get(id);
        if (island != null) {
            tickIsland(player, island);
        }
        tickWeather(player);
    }

    private void tickWeather(final Player player) {
        final UUID id = player.getUniqueId();
        final ActiveWeather active = weathers.get(id);
        if (active != null) {
            if (tick >= active.untilTick()) {
                weathers.remove(id);
                player.resetPlayerWeather();
                tw.exilecore.gui.ActionBar.send(player, plugin.text().parse("<gray>" + ItemRenderer.escape(active.def().name()) + "過去了"));
            } else if (tick % 10 == 0) {
                final Location at = player.getLocation();
                switch (active.def().id()) {
                    case "fog" -> player.spawnParticle(Particle.CLOUD, at.clone().add(0, 1, 0), 30, 8, 1.5, 8, 0);
                    case "star_tide" -> player.spawnParticle(Particle.END_ROD, at.clone().add(0, 0.5, 0), 20, 10, 0.3, 10, 0.01);
                    case "glow_tide" -> player.spawnParticle(Particle.GLOW, at.clone().add(0, 0.2, 0), 25, 10, 0.2, 10, 0); // 2026-10-23
                    case "whirlpool" -> player.spawnParticle(Particle.BUBBLE_COLUMN_UP, at.clone().add(6, -0.3, 0), 20, 2, 0.2, 2, 0.05);
                    case "seabirds" -> player.spawnParticle(Particle.CLOUD, at.clone().add(0, 6, 0), 6, 6, 1, 6, 0);
                    default -> {
                    }
                }
            }
            return;
        }
        if (config().weathers().isEmpty()) {
            return;
        }
        final int[] range = config().weatherInterval();
        final int next = nextWeather.computeIfAbsent(id, k -> tick + (range[0] + random.nextInt(Math.max(1, range[1] - range[0] + 1))) * 10);
        if (tick < next) {
            return;
        }
        nextWeather.remove(id);
        final LifeConfig.Weather def = config().rollWeather(random.nextDouble(), service.night()); // 2026-10-23：夜光潮只在晚上
        if (def == null) {
            return;
        }
        weathers.put(id, new ActiveWeather(def, tick + def.seconds() * 10));
        if ("storm".equals(def.id())) {
            player.setPlayerWeather(WeatherType.DOWNFALL);
        }
        player.showTitle(Title.title(plugin.text().parse("<#bfe6ff>" + ItemRenderer.escape(def.name())),
                plugin.text().parse("<gray>" + (!def.description().isBlank() ? ItemRenderer.escape(def.description()) : switch (def.id()) {
                    case "fog" -> "霧裡漂來的東西變多了";
                    case "storm" -> "風浪太大，攔截網攔不住東西";
                    case "star_tide" -> "星骸碎片大量漂來";
                    default -> config().name();
                })), Title.Times.times(Duration.ofMillis(300), Duration.ofMillis(1800), Duration.ofMillis(500))));
    }

    /** 離開筏（下線、傳走）：自己開的島清掉、航行取消、天氣恢復；跟別人來的就只是不跟了。 */
    void leave(final Player player) {
        final UUID id = player.getUniqueId();
        voyages.remove(id);
        final Island island = islands.remove(id);
        if (island != null) {
            for (final Player member : crewHere(island)) {
                if (!member.equals(player)) {
                    member.sendMessage(plugin.text().prefixed("<gray>開船的人離開了，" + ItemRenderer.escape(island.def.name()) + "漂走了"));
                    member.teleportAsync(service.raftSpawn(island.plot));
                }
            }
            clear(island);
        }
        for (final Island other : islands.values()) {
            other.crew.remove(id);
        }
        if (weathers.remove(id) != null) {
            player.resetPlayerWeather();
        }
        nextWeather.remove(id);
    }

    /** 收掉島、取消航行（淵鯨要用這個位置）；海象不動。 */
    void dismiss(final Player player) {
        voyages.remove(player.getUniqueId());
        final Island island = islands.remove(player.getUniqueId());
        if (island != null) {
            sendCrewHome(island);
            clear(island);
        }
    }

    void shutdown() {
        for (final Island island : islands.values()) {
            clear(island);
        }
        islands.clear();
        voyages.clear();
        for (final UUID id : weathers.keySet()) {
            final Player player = plugin.getServer().getPlayer(id);
            if (player != null) {
                player.resetPlayerWeather();
            }
        }
        weathers.clear();
        nextWeather.clear();
    }

    // ---- 小島 ----

    private void arrive(final Player player, final LifeService.Profile profile, final int[] center, final LifeConfig.Island def, final int plot) {
        final Island island = new Island(def, player.getUniqueId(), center[0] + OFFSET, center[1]);
        island.plot = plot;
        island.crew.add(player.getUniqueId());
        final List<Player> others = new ArrayList<>();
        for (final Player member : service.crewOn(plot)) {
            if (!member.equals(player)) {
                island.crew.add(member.getUniqueId());
                others.add(member);
            }
        }
        LifeService.holdChunks(plugin, world(), island.cx, island.cz, RADIUS, true); // 島存在的時候區塊不卸載（怪不會被收掉）
        clearRegion(island);
        switch (def.shape()) { // 2026-10-23：照形狀蓋（新的島沿用舊的形狀）
            case "reef" -> buildReef(island);
            case "ruin" -> buildRuin(island);
            case "wreck" -> buildWreck(island);
            default -> buildAtoll(island);
        }
        islands.put(player.getUniqueId(), island);
        final int level = islandLevel(player);
        if (!"ruin".equals(def.shape())) {
            spawnWave(island, level);
        }
        if ("wreck".equals(def.shape())) {
            spawnCaptain(island, level, new Location(world(), island.cx - 1.5, config().seaLevel() + 3, island.cz + 0.5)); // 桅杆旁邊
        }
        // 送上島（西邊的岸上，面向東）；回筏：選單「前往我的木筏」
        final double landing = switch (def.shape()) {
            case "reef" -> 4;
            case "wreck" -> 7.5;
            case "atoll" -> 12;
            default -> 8;
        };
        player.teleportAsync(new Location(world(), island.cx - landing + 0.5, config().seaLevel() + 2.1, island.cz + 0.5, -90, 0));
        for (final Player member : others) {
            member.teleportAsync(new Location(world(), island.cx - landing + 0.5, config().seaLevel() + 2.1, island.cz + 1.5, -90, 0));
            member.sendMessage(plugin.text().prefixed("<" + config().color() + ">跟著 " + ItemRenderer.escape(player.getName()) + " 抵達"
                    + ItemRenderer.escape(def.name()) + "<gray>：" + hint(def) + "；寶箱每個人各拿一份"));
        }
        player.playSound(player.getLocation(), Sound.ENTITY_PLAYER_SPLASH_HIGH_SPEED, 0.8f, 1f);
        player.showTitle(Title.title(plugin.text().parse("<" + config().color() + ">" + ItemRenderer.escape(def.name())),
                plugin.text().parse("<gray>島上可以放技能；回筏：/sea → 前往我的木筏"), Title.Times.times(Duration.ofMillis(300), Duration.ofMillis(2000), Duration.ofMillis(500))));
        player.sendMessage(plugin.text().prefixed("<" + config().color() + ">抵達" + ItemRenderer.escape(def.name()) + "<gray>（筏的東邊 "
                + OFFSET + " 格）：" + hint(def) + "；" + def.minutes() + " 分鐘後島會漂走"));
    }

    private static String hint(final LifeConfig.Island def) {
        if (def.count()[1] <= 0 && def.captain() == null) {
            return "沒有守衛：寶箱在水底的珊瑚旁邊，直接拿"; // 2026-10-23：補給島
        }
        return switch (def.shape()) {
            case "reef" -> "寶箱在水底的珊瑚旁邊，先打倒沉溺者";
            case "ruin" -> "右鍵點亮四根星柱（每根都會叫出守衛），中間的寶箱就會打開";
            case "wreck" -> "打倒船員和幽靈船長，船艙裡的寶箱就能開";
            default -> "擋下兩波怪潮，再打倒礁石守衛";
        };
    }

    private void tickIsland(final Player player, final Island island) {
        island.mobs.removeIf(id -> {
            final Entity entity = plugin.getServer().getEntity(id);
            return entity == null || !entity.isValid() || entity.isDead();
        });
        if (System.currentTimeMillis() >= island.expireAt) {
            islands.remove(island.owner);
            for (final UUID id : island.crew) {
                final Player member = plugin.getServer().getPlayer(id);
                if (member != null) {
                    member.sendMessage(plugin.text().prefixed("<gray>" + ItemRenderer.escape(island.def.name()) + "漂走了"));
                }
            }
            clear(island);
            return;
        }
        // 怪去追島上的人（只有人在島上的時候；回到筏上就不打了——筏上不能放技能）
        if (tick % 20 == 0) {
            final List<Player> crew = crewHere(island);
            final boolean here = !crew.isEmpty();
            for (final UUID id : island.mobs) {
                if (plugin.getServer().getEntity(id) instanceof Mob mob) {
                    if (here && !(mob.getTarget() instanceof Player)) {
                        Player nearest = crew.get(0);
                        for (final Player member : crew) {
                            if (member.getLocation().distanceSquared(mob.getLocation()) < nearest.getLocation().distanceSquared(mob.getLocation())) {
                                nearest = member;
                            }
                        }
                        mob.setTarget(nearest);
                    } else if (!here && mob.getTarget() != null) {
                        mob.setTarget(null);
                    }
                    if (!island.contains(mob.getLocation().getX(), mob.getLocation().getZ())) {
                        // 不准離開島（游去筏上）
                        mob.teleport(new Location(world(), island.cx + 0.5, config().seaLevel() + 2.2, island.cz + 0.5));
                    }
                }
            }
        }
        // 環礁：第一波清完來第二波，第二波清完守衛出來
        if ("atoll".equals(island.def.shape()) && island.mobs.isEmpty() && !island.captainOut) {
            final int level = islandLevel(player);
            if (island.wave < 2) {
                spawnWave(island, level);
                tw.exilecore.gui.ActionBar.send(player, plugin.text().parse("<#ff9b6b>第 " + island.wave + " 波！"));
            } else {
                spawnCaptain(island, level, new Location(world(), island.cx + 0.5, config().seaLevel() + 2, island.cz + 0.5));
            }
        }
        if ("atoll".equals(island.def.shape()) && island.captainOut && island.mobs.isEmpty() && island.chests.isEmpty() && !island.done) {
            final Location chest = new Location(world(), island.cx, config().seaLevel() + 2, island.cz);
            place(chest.getBlock(), Material.CHEST.createBlockData());
            island.chests.add(LifeService.key(chest.getBlockX(), chest.getBlockY(), chest.getBlockZ()));
            world().spawnParticle(Particle.TOTEM_OF_UNDYING, chest.clone().add(0.5, 1, 0.5), 40, 0.5, 0.5, 0.5, 0.2);
            player.sendMessage(plugin.text().prefixed("<" + config().color() + ">礁石守衛倒下了，環礁中央浮出一個寶箱"));
        }
    }

    /** 島上的怪幾級（2026-10-17）：開船的人的等級減 10，最高照 T16 地圖（84）——生活是休閒內容，掉落的物品等級不會比打地圖高。 */
    private int islandLevel(final Player player) {
        return Math.max(1, Math.min(ISLAND_LEVEL_CAP, plugin.players().get(player).level() - 10));
    }

    static final int ISLAND_LEVEL_CAP = 84;

    private void spawnWave(final Island island, final int level) {
        island.wave++;
        final LifeConfig.Island def = island.def;
        final int count = def.count()[0] + random.nextInt(Math.max(1, def.count()[1] - def.count()[0] + 1));
        for (int i = 0; i < count; i++) {
            final String typeName = def.monsterTypes().get(random.nextInt(def.monsterTypes().size()));
            final MonsterRarity rarity = i < def.rare() ? MonsterRarity.RARE : i < def.rare() + def.magic() ? MonsterRarity.MAGIC : MonsterRarity.NORMAL;
            final double angle = random.nextDouble() * Math.PI * 2;
            final boolean guardian = "GUARDIAN".equals(typeName);
            final boolean swimmer = "DOLPHIN".equals(typeName); // 2026-10-23：鯊巢礁的渦齒鯊在潟湖裡
            final double distance = "reef".equals(def.shape()) ? 9 + random.nextDouble() * 2
                    : "atoll".equals(def.shape()) ? (guardian || swimmer ? 5 + random.nextDouble() * 3 : 12)
                    : "wreck".equals(def.shape()) ? 7 + random.nextDouble() // 船身外面的沙灘上（船身最遠 6.3 格），不會卡在甲板裡
                    : 2 + random.nextDouble() * 5;
            // 珊瑚礁的沉溺者在水裡；環礁的守護者在中間的潟湖
            final boolean inWater = "reef".equals(def.shape()) && "DROWNED".equals(typeName) || "atoll".equals(def.shape()) && (guardian || swimmer);
            final double y = inWater ? config().seaLevel() - 1.5 : config().seaLevel() + 2.2;
            final Location at = new Location(world(), island.cx + 0.5 + Math.cos(angle) * distance, y, island.cz + 0.5 + Math.sin(angle) * distance);
            final LivingEntity mob = spawn(typeName, at);
            if (mob == null) {
                continue;
            }
            try {
                plugin.monsters().decorate(mob, level, rarity, List.of());
            } catch (final RuntimeException e) {
                mob.remove();
                continue;
            }
            if (swimmer) {
                mob.customName(plugin.text().parse("<#ff6b6b>渦齒鯊"));
                mob.setCustomNameVisible(true);
            }
            tag(mob, island);
            island.mobs.add(mob.getUniqueId());
        }
    }

    private void spawnCaptain(final Island island, final int level, final Location at) {
        final LifeConfig.Captain def = island.def.captain();
        island.captainOut = true;
        if (def == null) {
            return;
        }
        final LivingEntity boss = spawn(def.type(), at);
        if (boss == null) {
            return;
        }
        try {
            plugin.monsters().fixPlayers(boss, 1);
            plugin.monsters().decorateBoss(boss, level, def.name(), List.of(), def.life(), def.damage(), 1.3);
        } catch (final RuntimeException e) {
            boss.remove();
            return;
        }
        tag(boss, island);
        island.mobs.add(boss.getUniqueId());
        island.captain = boss;
        if (plugin.skills() != null && !def.skills().isEmpty()) {
            final List<tw.exilecore.skill.BossSkillUse> uses = new ArrayList<>();
            for (final String skill : def.skills()) {
                if (plugin.skills().config().bossSkills().skill(skill) != null) {
                    uses.add(tw.exilecore.skill.BossSkillUse.of(skill));
                }
            }
            plugin.skills().bossSkills().attach(boss, uses, new tw.exilecore.skill.BossSkills.Host() {
                @Override
                public String name() {
                    return def.name();
                }

                @Override
                public int phase() {
                    return 0;
                }

                @Override
                public boolean dormant() {
                    // 一起來的人都不在島上（回到筏上不能放技能）：不找人、不出招
                    return crewHere(island).isEmpty();
                }

                @Override
                public LivingEntity summon(final String type, final Location where) {
                    final LivingEntity minion = spawn(type.toUpperCase(Locale.ROOT), where);
                    if (minion == null) {
                        return null;
                    }
                    plugin.monsters().markSummoned(minion);
                    plugin.monsters().decorate(minion, level, MonsterRarity.NORMAL, List.of());
                    tag(minion, island);
                    island.mobs.add(minion.getUniqueId());
                    return minion;
                }
            });
        }
        for (final Player member : crewHere(island)) {
            member.showTitle(Title.title(plugin.text().parse("<#ff9b6b>" + ItemRenderer.escape(def.name())), plugin.text().parse("<gray>" + ItemRenderer.escape(island.def.name())),
                    Title.Times.times(Duration.ofMillis(200), Duration.ofMillis(1800), Duration.ofMillis(400))));
        }
    }

    private LivingEntity spawn(final String typeName, final Location at) {
        final EntityType type;
        try {
            type = EntityType.valueOf(typeName);
        } catch (final IllegalArgumentException e) {
            return null;
        }
        if (!type.isSpawnable() || type.getEntityClass() == null || !LivingEntity.class.isAssignableFrom(type.getEntityClass())) {
            return null;
        }
        final Entity entity = world().spawnEntity(at, type, CreatureSpawnEvent.SpawnReason.CUSTOM, created -> {
            if (created instanceof org.bukkit.entity.Ageable ageable) {
                ageable.setAdult();
            }
        });
        if (entity instanceof LivingEntity living && living.isValid()) {
            living.clearActivePotionEffects();
            living.setPersistent(false);
            living.setRemoveWhenFarAway(false);
            return living;
        }
        entity.remove();
        return null;
    }

    private void tag(final LivingEntity mob, final Island island) {
        mob.getPersistentDataContainer().set(service.driftKey(), PersistentDataType.STRING, "island:" + island.owner);
    }

    /** 島上的怪死了（LifeModule 的死亡事件）。 */
    void onDeath(final LivingEntity entity) {
        if (plugin.skills() != null) {
            plugin.skills().bossSkills().detach(entity.getUniqueId());
        }
        for (final Island island : islands.values()) {
            island.mobs.remove(entity.getUniqueId());
        }
    }

    // ---- 寶箱、星柱 ----

    /** 右鍵島上的寶箱、星柱（LifeModule）；回傳是不是（是就取消事件）。 */
    boolean interact(final Player player, final Block block) {
        Island island = null;
        for (final Island candidate : islands.values()) {
            if (candidate.contains(block.getX(), block.getZ())) {
                island = candidate;
            }
        }
        if (island == null) {
            return false;
        }
        final String at = LifeService.key(block.getX(), block.getY(), block.getZ());
        if (island.pillars.contains(at)) {
            if (!island.crew.contains(player.getUniqueId())) {
                tw.exilecore.gui.ActionBar.send(player, plugin.text().parse("<gray>這是別人的島"));
                return true;
            }
            if (!island.mobs.isEmpty()) {
                tw.exilecore.gui.ActionBar.send(player, plugin.text().parse("<red>先打倒守衛（還有 " + island.mobs.size() + " 隻）"));
                return true;
            }
            island.pillars.remove(at);
            island.lit++;
            place(block, Material.SEA_LANTERN.createBlockData());
            world().playSound(block.getLocation(), Sound.BLOCK_BEACON_ACTIVATE, 1f, 1.4f);
            world().spawnParticle(Particle.END_ROD, block.getLocation().add(0.5, 1, 0.5), 30, 0.3, 1, 0.3, 0.05);
            tw.exilecore.gui.ActionBar.send(player, plugin.text().parse("<#bfe6ff>星柱亮了 " + island.lit + "／4"));
            spawnWave(island, islandLevel(player));
            return true;
        }
        if (!island.chests.contains(at)) {
            return block.getType() == Material.CHEST;
        }
        if (!island.crew.contains(player.getUniqueId())) {
            tw.exilecore.gui.ActionBar.send(player, plugin.text().parse("<gray>這是別人的島"));
            return true;
        }
        if (!island.mobs.isEmpty()) {
            tw.exilecore.gui.ActionBar.send(player, plugin.text().parse("<red>先清掉守衛（還有 " + island.mobs.size() + " 隻）"));
            return true;
        }
        if ("ruin".equals(island.def.shape()) && island.lit < 4) {
            tw.exilecore.gui.ActionBar.send(player, plugin.text().parse("<red>四根星柱還沒全部點亮（" + island.lit + "／4）"));
            return true;
        }
        island.chests.remove(at);
        place(block, Material.AIR.createBlockData());
        // 一起來、在島上的人每個人各拿一份
        final List<Player> crew = crewHere(island);
        if (!crew.contains(player)) {
            crew.add(player);
        }
        for (final Player member : crew) {
            openChest(member, island);
        }
        if (island.chests.isEmpty() && (!"atoll".equals(island.def.shape()) || island.captainOut)) {
            island.done = true;
            for (final Player member : crew) {
                final LifeService.Profile profile = service.profile(member);
                if (profile != null) {
                    service.addXp(member, profile, island.def.xp());
                }
                member.sendMessage(plugin.text().prefixed("<" + config().color() + ">" + ItemRenderer.escape(island.def.name()) + "探索完了！<gray>生活經驗 +" + island.def.xp()));
            }
        }
        return true;
    }

    private void openChest(final Player player, final Island island) {
        final LifeService.Profile profile = service.profile(player);
        if (profile == null) {
            return;
        }
        final LifeConfig.Island def = island.def;
        final StringBuilder got = new StringBuilder();
        for (final Map.Entry<String, int[]> give : def.loot().entrySet()) {
            final int amount = give.getValue()[0] + random.nextInt(Math.max(1, give.getValue()[1] - give.getValue()[0] + 1));
            if (amount > 0) {
                service.addMaterial(profile, give.getKey(), amount);
                got.append(got.isEmpty() ? "" : "、").append(service.materialName(give.getKey())).append(" +").append(amount);
            }
        }
        if (def.hornShard() > 0 && random.nextDouble() < def.hornShard()) {
            service.addMaterial(profile, "horn_shard", 1);
            got.append("、<#ff9be0>").append(service.materialName("horn_shard")).append(" +1<white>");
        }
        if (!def.currencies().isEmpty() && random.nextDouble() < def.currencyChance() && service.takeCurrencyQuota(profile)) {
            final String id = def.currencies().get(random.nextInt(def.currencies().size()));
            service.giveCurrency(player, id, 1);
            got.append("、").append(service.currencyName(id));
        }
        if (def.letter()) {
            service.readLetter(player, profile);
        }
        player.playSound(player.getLocation(), Sound.BLOCK_CHEST_OPEN, 0.8f, 1f);
        player.sendMessage(plugin.text().prefixed("<" + config().color() + ">寶箱：<white>" + got));
    }

    // ---- 蓋島、清島 ----

    private void place(final Block block, final BlockData data) {
        block.setBlockData(data, false);
    }

    private void set(final int x, final int y, final int z, final Material material) {
        world().getBlockAt(x, y, z).setType(material, false);
    }

    private void clearRegion(final Island island) {
        final int sea = config().seaLevel();
        for (int x = island.cx - RADIUS; x <= island.cx + RADIUS; x++) {
            for (int z = island.cz - RADIUS; z <= island.cz + RADIUS; z++) {
                for (int y = sea - 6; y <= sea + 14; y++) {
                    final Block block = world().getBlockAt(x, y, z);
                    final Material want = y <= sea ? Material.WATER : Material.AIR;
                    if (block.getType() != want) {
                        block.setType(want, false);
                    }
                }
            }
        }
    }

    private void clear(final Island island) {
        if (world() != null) {
            LifeService.holdChunks(plugin, world(), island.cx, island.cz, RADIUS, false);
        }
        for (final UUID id : island.mobs) {
            final Entity entity = plugin.getServer().getEntity(id);
            if (entity != null) {
                if (plugin.skills() != null) {
                    plugin.skills().bossSkills().detach(id);
                }
                entity.remove();
            }
        }
        island.mobs.clear();
        if (world() != null) {
            clearRegion(island);
            for (final Entity entity : world().getNearbyEntities(new Location(world(), island.cx, config().seaLevel(), island.cz), RADIUS + 4, 16, RADIUS + 4)) {
                final String tag = entity.getPersistentDataContainer().get(service.driftKey(), PersistentDataType.STRING);
                if (tag != null && tag.startsWith("island:")) {
                    entity.remove();
                }
            }
        }
    }

    private int hash(final int x, final int z, final int salt) {
        int h = x * 73_856_093 ^ z * 19_349_663 ^ salt * 83_492_791;
        h ^= h >>> 13;
        h *= 0x5bd1e995;
        return (h ^ h >>> 15) & 0x7fffffff;
    }

    private static final Material[] CORALS = {Material.BRAIN_CORAL_BLOCK, Material.TUBE_CORAL_BLOCK, Material.FIRE_CORAL_BLOCK,
            Material.HORN_CORAL_BLOCK, Material.BUBBLE_CORAL_BLOCK};
    private static final Material[] CORAL_PLANTS = {Material.BRAIN_CORAL, Material.TUBE_CORAL, Material.FIRE_CORAL, Material.HORN_CORAL,
            Material.BUBBLE_CORAL, Material.SEAGRASS};

    private void buildReef(final Island island) {
        final int sea = config().seaLevel();
        for (int dx = -RADIUS; dx <= RADIUS; dx++) {
            for (int dz = -RADIUS; dz <= RADIUS; dz++) {
                final double d = Math.sqrt(dx * dx + dz * dz);
                final int x = island.cx + dx;
                final int z = island.cz + dz;
                if (d <= 7) {
                    for (int y = sea - 6; y <= sea; y++) {
                        set(x, y, z, Material.SAND);
                    }
                    if (d <= 5.5) {
                        set(x, sea + 1, z, Material.SAND);
                    }
                } else if (d <= 13) {
                    for (int y = sea - 6; y <= sea - 4; y++) {
                        set(x, y, z, Material.SAND);
                    }
                    final int h = hash(x, z, 1);
                    if (h % 3 == 0) {
                        set(x, sea - 3, z, CORALS[h % CORALS.length]);
                    } else if (h % 3 == 1) {
                        set(x, sea - 3, z, CORAL_PLANTS[(h / 3) % CORAL_PLANTS.length]);
                    }
                }
            }
        }
        // 小椰子樹
        for (int y = sea + 2; y <= sea + 5; y++) {
            set(island.cx + 1, y, island.cz - 1, Material.STRIPPED_JUNGLE_LOG);
        }
        for (final int[] leaf : new int[][] {{0, 0}, {1, 0}, {-1, 0}, {0, 1}, {0, -1}, {2, 0}, {-2, 0}, {0, 2}, {0, -2}}) {
            final Block block = world().getBlockAt(island.cx + 1 + leaf[0], sea + 6, island.cz - 1 + leaf[1]);
            block.setBlockData(plugin.getServer().createBlockData("minecraft:jungle_leaves[persistent=true]"), false);
        }
        for (int i = 0; i < island.def.chests(); i++) {
            final double angle = Math.PI * 2 * i / Math.max(1, island.def.chests()) + random.nextDouble();
            final int x = island.cx + (int) Math.round(Math.cos(angle) * 10);
            final int z = island.cz + (int) Math.round(Math.sin(angle) * 10);
            final Block block = world().getBlockAt(x, sea - 3, z);
            place(block, plugin.getServer().createBlockData("minecraft:chest[waterlogged=true]"));
            island.chests.add(LifeService.key(x, sea - 3, z));
        }
    }

    private void buildRuin(final Island island) {
        final int sea = config().seaLevel();
        final Material[] ground = {Material.STONE, Material.ANDESITE, Material.COBBLESTONE, Material.MOSSY_COBBLESTONE, Material.GRAVEL};
        for (int dx = -RADIUS; dx <= RADIUS; dx++) {
            for (int dz = -RADIUS; dz <= RADIUS; dz++) {
                final double d = Math.sqrt(dx * dx + dz * dz);
                final int x = island.cx + dx;
                final int z = island.cz + dz;
                if (d > 11) {
                    continue;
                }
                for (int y = sea - 6; y <= sea; y++) {
                    set(x, y, z, Material.STONE);
                }
                if (d <= 10) {
                    set(x, sea + 1, z, d <= 4 ? (hash(x, z, 2) % 4 == 0 ? Material.CRACKED_STONE_BRICKS : Material.STONE_BRICKS)
                            : ground[hash(x, z, 3) % ground.length]);
                }
            }
        }
        // 斷掉的石柱
        for (int i = 0; i < 6; i++) {
            final double angle = Math.PI * 2 * i / 6 + 0.3;
            final int x = island.cx + (int) Math.round(Math.cos(angle) * 8.5);
            final int z = island.cz + (int) Math.round(Math.sin(angle) * 8.5);
            final int height = 1 + hash(x, z, 4) % 3;
            for (int y = 1; y <= height; y++) {
                set(x, sea + 1 + y, z, hash(x, z + y, 5) % 2 == 0 ? Material.MOSSY_STONE_BRICKS : Material.STONE_BRICKS);
            }
        }
        // 四根星柱
        for (final int[] dir : new int[][] {{5, 0}, {-5, 0}, {0, 5}, {0, -5}}) {
            final int x = island.cx + dir[0];
            final int z = island.cz + dir[1];
            set(x, sea + 2, z, Material.POLISHED_ANDESITE);
            set(x, sea + 3, z, Material.POLISHED_ANDESITE);
            set(x, sea + 4, z, Material.AMETHYST_BLOCK);
            island.pillars.add(LifeService.key(x, sea + 4, z));
        }
        set(island.cx, sea + 2, island.cz, Material.CHEST);
        island.chests.add(LifeService.key(island.cx, sea + 2, island.cz));
    }

    private void buildWreck(final Island island) {
        final int sea = config().seaLevel();
        for (int dx = -RADIUS; dx <= RADIUS; dx++) {
            for (int dz = -RADIUS; dz <= RADIUS; dz++) {
                final double d = Math.sqrt(dx * dx + dz * dz);
                final int x = island.cx + dx;
                final int z = island.cz + dz;
                if (d <= 10) {
                    for (int y = sea - 6; y <= sea; y++) {
                        set(x, y, z, Material.SAND);
                    }
                    if (d <= 8) {
                        set(x, sea + 1, z, Material.SAND);
                    }
                }
            }
        }
        // 船身（沿著東西向，13×5），有破洞
        for (int dx = -6; dx <= 6; dx++) {
            for (int dz = -2; dz <= 2; dz++) {
                final int x = island.cx + dx;
                final int z = island.cz + dz;
                final boolean bowTaper = dx >= 5 && Math.abs(dz) == 2 || dx == 6 && Math.abs(dz) >= 1;
                if (bowTaper) {
                    continue;
                }
                set(x, sea + 2, z, Material.DARK_OAK_PLANKS);
                final boolean wall = Math.abs(dz) == 2 || dx == -6 || dx == 6 || dx == 5 && Math.abs(dz) == 1;
                if (wall && !(dx == -6 && dz == 0)) { // 船尾留一個門
                    for (int y = sea + 3; y <= sea + 4; y++) {
                        if (hash(x, z + y, 6) % 5 != 0) {
                            set(x, y, z, Material.DARK_OAK_PLANKS);
                        }
                    }
                }
            }
        }
        for (int y = sea + 3; y <= sea + 10; y++) {
            set(island.cx, y, island.cz, Material.DARK_OAK_FENCE);
        }
        for (int y = sea + 6; y <= sea + 9; y++) {
            for (int dz = -2; dz <= 2; dz++) {
                if (hash(island.cx, dz * 31 + y, 7) % 4 != 0) {
                    set(island.cx + 1, y, island.cz + dz, Material.WHITE_WOOL);
                }
            }
        }
        for (int i = 0; i < island.def.chests(); i++) {
            final int x = island.cx + (i == 0 ? -3 : 3);
            set(x, sea + 3, island.cz, Material.CHEST);
            island.chests.add(LifeService.key(x, sea + 3, island.cz));
        }
    }

    private void buildAtoll(final Island island) {
        final int sea = config().seaLevel();
        for (int dx = -RADIUS; dx <= RADIUS; dx++) {
            for (int dz = -RADIUS; dz <= RADIUS; dz++) {
                final double d = Math.sqrt(dx * dx + dz * dz);
                final int x = island.cx + dx;
                final int z = island.cz + dz;
                if (d <= 3.5 || d >= 10 && d <= 14.5) {
                    for (int y = sea - 6; y <= sea; y++) {
                        set(x, y, z, Material.SAND);
                    }
                    if (d <= 3 || d >= 10.5 && d <= 14) {
                        set(x, sea + 1, z, hash(x, z, 8) % 7 == 0 ? Material.SMOOTH_SANDSTONE : Material.SAND);
                    }
                } else if (d < 10) {
                    for (int y = sea - 6; y <= sea - 4; y++) {
                        set(x, y, z, Material.SAND);
                    }
                    final int h = hash(x, z, 9);
                    if (h % 4 == 0) {
                        set(x, sea - 3, z, CORALS[h % CORALS.length]);
                    }
                }
            }
        }
        set(island.cx, sea + 1, island.cz, Material.CHISELED_QUARTZ_BLOCK);
        for (final int[] dir : new int[][] {{12, 0}, {-12, 0}, {0, 12}, {0, -12}}) {
            set(island.cx + dir[0], sea + 2, island.cz + dir[1], Material.PRISMARINE_WALL);
            set(island.cx + dir[0], sea + 3, island.cz + dir[1], Material.SEA_LANTERN);
        }
    }
}
