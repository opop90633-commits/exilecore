package tw.exilecore.heist;

import net.kyori.adventure.title.Title;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.Particle;
import org.bukkit.Sound;
import org.bukkit.block.Block;
import org.bukkit.boss.BarColor;
import org.bukkit.boss.BarStyle;
import org.bukkit.boss.BossBar;
import org.bukkit.entity.LivingEntity;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.block.Action;
import org.bukkit.event.entity.EntityDeathEvent;
import org.bukkit.event.player.PlayerInteractEvent;
import org.bukkit.inventory.EquipmentSlot;
import tw.exilecore.ExileCore;
import tw.exilecore.currency.CurrencyType;
import tw.exilecore.economy.EconomySources;
import tw.exilecore.item.ItemData;
import tw.exilecore.item.ItemRenderer;
import tw.exilecore.item.Rarity;
import tw.exilecore.item.Veiled;
import tw.exilecore.map.MapDrops;
import tw.exilecore.map.MapInstance;
import tw.exilecore.map.MapLayout;
import tw.exilecore.map.MapRewards;
import tw.exilecore.map.MapService;
import tw.exilecore.map.MapStats;
import tw.exilecore.monster.DropTable;
import tw.exilecore.monster.MonsterData;
import tw.exilecore.monster.MonsterRarity;
import tw.exilecore.stats.StatKeys;
import tw.exilecore.stats.StatSheet;

import java.time.Duration;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.random.RandomGenerator;

/**
 * 劫盜（2026-10，照 PoE 的 Heist；沒有夥伴）：
 * <ul>
 *   <li>劫盜合約：開一處據點（地圖模組的特別實例），裡面散著幾個金庫箱；最裡面的守衛倒下時打開大金庫，每個在裡面的人一份。</li>
 *   <li>劫盜藍圖：大劫盜，三處據點連著打（打完一處直接送進下一處），只有最後一處有大金庫，獎勵 ×grand_multiplier。</li>
 *   <li>警戒值：殺怪、開箱、時間都會加；滿 100 封鎖——增援一直來，大金庫的獎勵減半。</li>
 *   <li>劫盜裡掉的魔法／稀有裝備有機率帶隱匿詞綴（{@link Veiled}）；揭露石只在這裡掉。</li>
 * </ul>
 */
public final class HeistService implements Listener {

    public static final String CONTRACT = "heist_contract";
    public static final String BLUEPRINT = "heist_blueprint";
    public static final String UNVEIL_STONE = "unveil_stone";
    /** 傳奇的 sources 寫這個：劫盜大金庫的專屬傳奇。 */
    public static final String UNIQUE_SOURCE = "heist";
    /** 大劫盜有幾處據點。 */
    public static final int GRAND_WINGS = 3;
    /** 營地傳送門的粒子顏色（金色）。 */
    private static final int COLOR = 0xD4A017;
    private static final double ALARM_MAX = 100;
    /** 金庫箱的獎勵給附近幾格內的人。 */
    private static final double CHEST_RADIUS = 12;

    /** 一次劫盜（大劫盜的三處據點是同一次）。 */
    static final class Run {
        final UUID owner;
        final boolean grand;
        final int level;
        final List<String> visited = new ArrayList<>();
        final BossBar bar;
        MapInstance instance;
        HeistConfig.Site site;
        int wing = 1;
        double alarm;
        boolean lockdown;
        long nextReinforce;
        boolean placed;
        /** 還沒開的金庫箱（方塊位置的 key → 位置）。 */
        final Map<Long, Location> chests = new HashMap<>();
        int chestTotal;

        Run(final UUID owner, final boolean grand, final int level, final BossBar bar) {
            this.owner = owner;
            this.grand = grand;
            this.level = level;
            this.bar = bar;
        }
    }

    private final ExileCore plugin;
    private final HeistConfig config;
    /** 實例代號 → 劫盜。 */
    private final Map<Integer, Run> runs = new HashMap<>();
    private long seconds;

    private final HeistFinale finale;

    HeistService(final ExileCore plugin, final HeistConfig config) {
        this.plugin = plugin;
        this.config = config;
        this.finale = new HeistFinale(plugin, this);
    }

    HeistFinale finale() {
        return finale;
    }

    /** 開劫盜終局（金庫之主）；成功回傳 null，不然回傳原因。 */
    public String startFinale(final Player player) {
        return finale.start(player);
    }

    public HeistConfig config() {
        return config;
    }

    private RandomGenerator random() {
        return plugin.random();
    }

    /** Lv.21 的未切割技能寶石（2026-10：只有劫盜大金庫、金庫之主掉）。 */
    public static final String UNCUT_21 = "uncut_skill_21";

    public CurrencyType currency(final String id) {
        return plugin.currencies() == null ? null : plugin.currencies().repository().get(id);
    }

    /** 背包＋通貨袋裡有幾個。 */
    public int count(final Player player, final String currencyId) {
        final CurrencyType type = currency(currencyId);
        return type == null || plugin.maps() == null ? 0 : plugin.maps().countCurrency(player, type);
    }

    /** 這個角色開劫盜時的怪物等級：打過地圖照最高階的地圖等級，不然照角色等級（最高 60）。 */
    public int levelFor(final Player player) {
        final MapService maps = plugin.maps();
        final int best = maps == null ? 0 : maps.bestTier(player);
        if (best > 0) {
            return maps.config().tiers().level(best);
        }
        return Math.max(1, Math.min(60, plugin.players().get(player).level()));
    }

    // ---- 開始 ----

    /** 開一次劫盜（grand = 用藍圖的大劫盜）；成功回傳 null，不然回傳原因。 */
    public String start(final Player player, final boolean grand) {
        if (!plugin.players().get(player).hasCharacter()) {
            return "先建立角色（/char）";
        }
        final MapService maps = plugin.maps();
        if (maps == null || maps.world() == null) {
            return "地圖模組沒有啟用（或地圖世界沒有載入），開不了劫盜";
        }
        if (config.sites().isEmpty()) {
            return "heist.yml 沒有據點";
        }
        final int characterLevel = plugin.players().get(player).level();
        if (characterLevel < config.settings().minLevel()) {
            return "角色要 " + config.settings().minLevel() + " 級才能接劫盜（你現在 " + characterLevel + " 級）";
        }
        final String currencyId = grand ? BLUEPRINT : CONTRACT;
        final CurrencyType key = currency(currencyId);
        if (key == null) {
            return "currencies.yml 沒有 " + currencyId;
        }
        if (maps.countCurrency(player, key) < 1) {
            return grand ? "需要一張" + key.name() + "（劫盜的大金庫掉，金庫箱也有小機率）" : "需要一張" + key.name() + "（怪物 " + key.dropLevel() + " 級起會掉）";
        }
        final int level = levelFor(player);
        final BossBar bar = plugin.getServer().createBossBar("警戒", BarColor.YELLOW, BarStyle.SEGMENTED_10);
        final Run run = new Run(player.getUniqueId(), grand, level, bar);
        final String problem = openWing(player, run);
        if (problem != null) {
            bar.removeAll();
            return problem;
        }
        maps.takeCurrency(player, key, 1, EconomySources.HEIST);
        player.sendMessage(plugin.text().prefixed("<#d4a017>盜賊首領：<gray>" + (grand
                ? "大劫盜，三處據點一路打進去，最後一處才有大金庫。別讓警戒滿了。"
                : "地點拿到了：<white>" + ItemRenderer.escape(run.site.name()) + "<gray>。開金庫箱、幹掉守衛，大金庫就是你的。")));
        plugin.getSLF4JLogger().info("{} 開了{}：{}（Lv.{}）", player.getName(), grand ? "大劫盜" : "劫盜", run.site.name(), level);
        return null;
    }

    /** 開下一處據點（第一處也是）：挑一處沒去過的、開實例、記起來。 */
    private String openWing(final Player owner, final Run run) {
        final HeistConfig.Settings settings = config.settings();
        final HeistConfig.Site site = config.pick(random(), run.visited);
        if (site == null) {
            return "heist.yml 沒有據點";
        }
        final MapStats stats = new MapStats(0, run.level, settings.quantity(), settings.rarity(), 0, 0, 0, List.of(), List.of(), List.of());
        final MapService.Special special = new MapService.Special("heist", COLOR, this::onComplete, this::onLoot);
        final MapInstance instance = plugin.maps().openSpecialInstance(owner, site.map(run.level), stats, settings.rooms(), special);
        if (instance == null) {
            final String problem = plugin.maps().specialProblem();
            return problem == null ? "地圖世界現在開不了新的實例，等一下再試" : problem;
        }
        if (run.instance != null) {
            runs.remove(run.instance.id());
        }
        run.instance = instance;
        run.site = site;
        run.visited.add(site.id());
        run.placed = false;
        run.chests.clear();
        run.chestTotal = 0;
        runs.put(instance.id(), run);
        return null;
    }

    // ---- 每秒 ----

    void tick() {
        seconds++;
        finale.tick();
        if (runs.isEmpty()) {
            return;
        }
        final HeistConfig.Settings settings = config.settings();
        for (final Run run : new ArrayList<>(new LinkedHashSet<>(runs.values()))) {
            final MapInstance instance = run.instance;
            if (instance == null || instance.state() == MapInstance.State.CLOSING || plugin.maps() == null
                    || !plugin.maps().instances().contains(instance)) {
                end(run);
                continue;
            }
            if (instance.state() != MapInstance.State.OPEN) {
                continue;
            }
            if (!run.placed) {
                run.placed = true;
                placeChests(run);
            }
            final List<Player> inside = playersIn(instance);
            // 有人在裡面才算時間（傳送門開好、還沒進去的時候不加）
            if (!inside.isEmpty() && !instance.bossKilled()) {
                addAlarm(run, settings.alarm().perSecond());
            }
            updateBar(run, inside);
            if (run.lockdown && !inside.isEmpty() && seconds >= run.nextReinforce && !instance.bossKilled()) {
                run.nextReinforce = seconds + settings.lockdown().reinforceSeconds();
                reinforce(run, inside);
            }
            // 還沒開的金庫箱冒光（看得出來在哪）
            for (final Location chest : run.chests.values()) {
                chest.getWorld().spawnParticle(Particle.WAX_ON, chest.getX() + 0.5, chest.getY() + 1.2, chest.getZ() + 0.5, 3, 0.3, 0.3, 0.3, 0);
            }
        }
    }

    private List<Player> playersIn(final MapInstance instance) {
        final List<Player> list = new ArrayList<>();
        for (final Player player : instance.world().getPlayers()) {
            if (instance.contains(player.getLocation()) && !player.isDead()) {
                list.add(player);
            }
        }
        return list;
    }

    private void updateBar(final Run run, final List<Player> inside) {
        final Set<Player> viewers = new java.util.HashSet<>(run.bar.getPlayers());
        for (final Player player : inside) {
            if (!viewers.remove(player)) {
                run.bar.addPlayer(player);
            }
        }
        for (final Player gone : viewers) {
            run.bar.removePlayer(gone);
        }
        final String wing = run.grand ? "（" + run.wing + "/" + GRAND_WINGS + "）" : "";
        final int opened = run.chestTotal - run.chests.size();
        if (run.lockdown) {
            run.bar.setColor(BarColor.RED);
            run.bar.setProgress(1);
            run.bar.setTitle("§c封鎖！增援不斷湧入 §7" + run.site.name() + wing + "・金庫箱 " + opened + "/" + run.chestTotal);
        } else {
            run.bar.setColor(run.alarm >= 70 ? BarColor.RED : run.alarm >= 40 ? BarColor.YELLOW : BarColor.GREEN);
            run.bar.setProgress(Math.max(0, Math.min(1, run.alarm / ALARM_MAX)));
            run.bar.setTitle("§6警戒 " + (int) Math.floor(run.alarm) + "% §7" + run.site.name() + wing + "・金庫箱 " + opened + "/" + run.chestTotal);
        }
    }

    private void addAlarm(final Run run, final double amount) {
        if (run.lockdown || amount <= 0) {
            return;
        }
        run.alarm = Math.min(ALARM_MAX, run.alarm + amount);
        if (run.alarm >= ALARM_MAX) {
            run.lockdown = true;
            run.nextReinforce = seconds + 3;
            for (final Player player : playersIn(run.instance)) {
                player.showTitle(Title.title(plugin.text().parse("<red>封鎖！"), plugin.text().parse("<gray>警戒滿了：增援會一直來，大金庫的獎勵減半"),
                        Title.Times.times(Duration.ofMillis(100), Duration.ofMillis(2500), Duration.ofMillis(500))));
                player.playSound(player.getLocation(), Sound.BLOCK_BELL_USE, 1.0f, 0.6f);
            }
        }
    }

    /** 封鎖時的一波增援：在某個人附近刷幾隻魔法怪。 */
    private void reinforce(final Run run, final List<Player> inside) {
        final int count = config.settings().lockdown().reinforceCount();
        final Player target = inside.get(random().nextInt(inside.size()));
        int spawned = 0;
        for (int i = 0; i < count; i++) {
            final Location at = nearFloor(run.instance, target.getLocation(), 6);
            final LivingEntity monster = plugin.maps().spawnExtra(run.instance, at, MonsterRarity.MAGIC);
            if (monster != null) {
                spawned++;
                if (monster instanceof org.bukkit.entity.Mob mob) {
                    mob.setTarget(target);
                }
            }
        }
        if (spawned > 0) {
            target.getWorld().playSound(target.getLocation(), Sound.EVENT_RAID_HORN, 0.8f, 1.2f);
        }
    }

    /** 這個人附近 radius 格內、版面上是地板的一個位置（找不到就是他自己的位置）。 */
    private Location nearFloor(final MapInstance instance, final Location around, final int radius) {
        final MapLayout layout = instance.layout();
        for (int attempt = 0; attempt < 12; attempt++) {
            final int x = around.getBlockX() - instance.originX() + random().nextInt(radius * 2 + 1) - radius;
            final int z = around.getBlockZ() - instance.originZ() + random().nextInt(radius * 2 + 1) - radius;
            if (layout.isFloor(x, z)) {
                return new Location(instance.world(), instance.originX() + x + 0.5, MapLayout.FLOOR_Y + 1, instance.originZ() + z + 0.5);
            }
        }
        return around.clone();
    }

    // ---- 金庫箱 ----

    private void placeChests(final Run run) {
        final MapInstance instance = run.instance;
        final MapLayout layout = instance.layout();
        final List<MapLayout.Room> rooms = new ArrayList<>();
        for (final MapLayout.Room room : layout.rooms()) {
            if (room.kind() == MapLayout.Kind.NORMAL || room.kind() == MapLayout.Kind.SIDE) {
                rooms.add(room);
            }
        }
        Collections.shuffle(rooms, new java.util.Random(random().nextLong()));
        final int[] range = config.settings().chests();
        final int want = range[0] + (range[1] > range[0] ? random().nextInt(range[1] - range[0] + 1) : 0);
        for (final MapLayout.Room room : rooms) {
            if (run.chests.size() >= want) {
                break;
            }
            final MapLayout.Spot spot = spotIn(layout, room);
            if (spot == null) {
                continue;
            }
            final Block block = instance.world().getBlockAt(instance.originX() + spot.x(), MapLayout.FLOOR_Y + 1, instance.originZ() + spot.z());
            block.setType(Material.VAULT, false);
            run.chests.put(key(block), block.getLocation());
        }
        run.chestTotal = run.chests.size();
        final String bossName = run.site.boss().name();
        for (final Player player : plugin.getServer().getOnlinePlayers()) {
            if (plugin.maps().canEnter(player, instance)) {
                player.sendMessage(plugin.text().prefixed("<#d4a017>劫盜：<white>" + ItemRenderer.escape(run.site.name())
                        + (run.grand ? "<gray>（第 " + run.wing + "/" + GRAND_WINGS + " 處）" : "")
                        + "<gray>——" + run.chestTotal + " 個金庫箱（右鍵打開），打倒<white>" + ItemRenderer.escape(bossName)
                        + "<gray>" + (run.grand && run.wing < GRAND_WINGS ? "就進下一處" : "打開大金庫") + "。警戒滿了會封鎖！"));
            }
        }
    }

    /** 房間中央附近一格是地板、旁邊沒有刷怪點的位置。 */
    private static MapLayout.Spot spotIn(final MapLayout layout, final MapLayout.Room room) {
        for (final int[] offset : MapLayout.EVENT_OFFSETS) {
            final int x = room.cx() + offset[0];
            final int z = room.cz() + offset[1];
            if (!layout.isFloor(x, z) || !room.contains(x, z)) {
                continue;
            }
            boolean clear = true;
            for (final MapLayout.Spot pack : layout.packSpots()) {
                if (Math.abs(pack.x() - x) <= 2 && Math.abs(pack.z() - z) <= 2) {
                    clear = false;
                    break;
                }
            }
            if (clear) {
                return new MapLayout.Spot(x, z);
            }
        }
        return null;
    }

    private static long key(final Block block) {
        return ((long) block.getX() & 0x3FFFFFFL) << 38 | ((long) block.getZ() & 0x3FFFFFFL) << 12 | (block.getY() & 0xFFFL);
    }

    /** 右鍵金庫箱：附近的每個人各一份；警戒上升。世界保護會把方塊互動標成拒絕，所以用 LOWEST 自己看。 */
    @EventHandler(priority = EventPriority.LOWEST)
    public void onInteract(final PlayerInteractEvent event) {
        if (event.getAction() != Action.RIGHT_CLICK_BLOCK || event.getClickedBlock() == null
                || event.getClickedBlock().getType() != Material.VAULT || event.getHand() != EquipmentSlot.HAND || runs.isEmpty()) {
            return;
        }
        final Block block = event.getClickedBlock();
        Run found = null;
        for (final Run run : runs.values()) {
            if (run.chests.containsKey(key(block))) {
                found = run;
                break;
            }
        }
        if (found == null) {
            return;
        }
        final Player player = event.getPlayer();
        final Location eye = player.getEyeLocation();
        if (!tw.exilecore.skill.SkillMath.withinVanillaReach(eye.getX(), eye.getY(), eye.getZ(), block.getX(), block.getY(), block.getZ())) {
            return;
        }
        event.setCancelled(true);
        event.setUseItemInHand(org.bukkit.event.Event.Result.DENY);
        if (plugin.skills() != null) {
            plugin.skills().consumeRightClick(player);
        }
        final Location at = found.chests.remove(key(block));
        block.setType(Material.AIR, false);
        openChest(found, at == null ? block.getLocation() : at);
        addAlarm(found, config.settings().alarm().chest());
    }

    private void openChest(final Run run, final Location at) {
        final HeistConfig.Settings settings = config.settings();
        final Location center = at.clone().add(0.5, 0.5, 0.5);
        final MonsterData data = new MonsterData(run.level, MonsterRarity.RARE, List.of(), "金庫箱");
        int takers = 0;
        for (final Player player : playersIn(run.instance)) {
            if (player.getLocation().distanceSquared(center) > CHEST_RADIUS * CHEST_RADIUS) {
                continue;
            }
            final List<DropTable.Drop> rolled = rollDrops(player, run.level, settings.chestRolls(), randomIn(settings.chestCurrency()), 1.0);
            if (random().nextDouble() < settings.unveilStoneChance()) {
                addCurrency(rolled, UNVEIL_STONE, 1);
            }
            // 2026-10：金庫箱小機率掉劫盜藍圖（跟大金庫一樣：大劫盜裡不掉，免得一直接著打）
            if (!run.grand && random().nextDouble() < settings.chestBlueprintChance()) {
                addCurrency(rolled, BLUEPRINT, 1);
            }
            veil(rolled);
            // 2026-10：遺時珠寶只在劫盜掉（合約、藍圖的金庫箱都有小機率）
            if (random().nextDouble() < settings.timeLostChance()) {
                addTimeLostJewel(rolled, run.level);
            }
            MapRewards.deliver(plugin, player, center, data, rolled, plugin.monsterModule().drops().rollGold(run.level, MonsterRarity.RARE,
                    quantity(player), random()) * Math.max(1, settings.chestRolls()));
            takers++;
        }
        center.getWorld().spawnParticle(Particle.TOTEM_OF_UNDYING, center, 30, 0.5, 0.5, 0.5, 0.15);
        center.getWorld().playSound(center, Sound.BLOCK_VAULT_OPEN_SHUTTER, 1.0f, 1.0f);
        plugin.getSLF4JLogger().debug("劫盜 #{}：金庫箱開了，{} 人拿到東西", run.instance.id(), takers);
    }

    private double quantity(final Player player) {
        final StatSheet sheet = plugin.players().sheet(player);
        return config.settings().quantity() / 100.0 + sheet.increased(StatKeys.DROP_QUANTITY, Set.of());
    }

    /** 擲 rolls 次掉落表（稀有怪）＋ currencies 個通貨；scale 乘在件數上（封鎖減半）。 */
    private List<DropTable.Drop> rollDrops(final Player player, final int level, final int rolls, final int currencies, final double scale) {
        final DropTable drops = plugin.monsterModule().drops();
        final StatSheet sheet = plugin.players().sheet(player);
        final double quantity = quantity(player) + drops.serverQuantity();
        final double rarity = config.settings().rarity() / 100.0 + sheet.increased(StatKeys.DROP_RARITY, Set.of()) + drops.serverRarity();
        final List<DropTable.Drop> rolled = new ArrayList<>();
        final int times = scaled(rolls, scale);
        for (int i = 0; i < times; i++) {
            rolled.addAll(drops.roll(level, MonsterRarity.RARE, quantity, rarity, plugin.items().generator(), plugin.currencies().repository(), random(),
                    plugin.characters().lootBias(player)));
        }
        final int currencyCount = scaled(currencies, scale);
        for (int i = 0; i < currencyCount; i++) {
            final CurrencyType currency = drops.rollCurrency(level, plugin.currencies().repository(), random(), rarity);
            if (currency != null) {
                rolled.add(DropTable.Drop.currency(currency));
            }
        }
        return rolled;
    }

    /**
     * 加一顆遺時珠寶（半徑珠寶；2026-10：只在劫盜的金庫箱與大金庫掉，一般掉落、商人、賭博都拿不到）。
     * 魔法或稀有（settings.time_lost_jewel_rare），跟其他掉落一樣要鑑定；這個等級還掉不出來（基底的 drop_level）就不加。
     */
    private boolean addTimeLostJewel(final List<DropTable.Drop> drops, final int level) {
        final tw.exilecore.item.ItemBase base = plugin.items().repository().pickRadiusJewel(level, random());
        if (base == null) {
            return false;
        }
        final Rarity rarity = random().nextDouble() < config.settings().timeLostRare() ? Rarity.RARE : Rarity.MAGIC;
        final ItemData item = plugin.items().generator().generate(base, rarity, level, random());
        item.setIdentified(false);
        drops.add(DropTable.Drop.equipment(item));
        return true;
    }

    /** count × scale，小數的部分用機率（0.5 × 3 = 1 個，另外 50% 再一個）。 */
    private int scaled(final int count, final double scale) {
        final double value = count * scale;
        final int whole = (int) Math.floor(value);
        return whole + (random().nextDouble() < value - whole ? 1 : 0);
    }

    private int randomIn(final int[] range) {
        return range[0] + (range[1] > range[0] ? random().nextInt(range[1] - range[0] + 1) : 0);
    }

    private void addCurrency(final List<DropTable.Drop> drops, final String id, final int amount) {
        final CurrencyType type = currency(id);
        if (type == null) {
            return;
        }
        for (int i = 0; i < amount; i++) {
            drops.add(DropTable.Drop.currency(type));
        }
    }

    /** 劫盜裡掉的魔法／稀有裝備：照機率加一條隱匿詞綴。 */
    private void veil(final List<DropTable.Drop> drops) {
        final double chance = config.settings().veiledChance();
        if (chance <= 0) {
            return;
        }
        for (final DropTable.Drop drop : drops) {
            final ItemData item = drop.item();
            if (drop.kind() != DropTable.Kind.EQUIPMENT || item == null || item.unique() != null
                    || item.rarity() != Rarity.MAGIC && item.rarity() != Rarity.RARE) {
                continue;
            }
            if (random().nextDouble() < chance && Veiled.veiledOn(item).isEmpty()) {
                Veiled.addVeiled(item, plugin.items().generator(), random());
            }
        }
    }

    /** 劫盜實例裡的怪物掉落（地圖模組在掉落表擲完之後交過來）。 */
    private void onLoot(final MapInstance instance, final List<DropTable.Drop> drops) {
        if (runs.containsKey(instance.id())) {
            veil(drops);
        }
    }

    // ---- 殺怪的警戒 ----

    @EventHandler(priority = EventPriority.MONITOR)
    public void onDeath(final EntityDeathEvent event) {
        if (runs.isEmpty() || plugin.maps() == null || event.getEntity() instanceof Player) {
            return;
        }
        final LivingEntity entity = event.getEntity();
        final MapInstance instance = plugin.maps().instanceOf(entity);
        final Run run = instance == null ? null : runs.get(instance.id());
        if (run == null || entity.equals(instance.boss())) {
            return;
        }
        final MonsterRarity rarity = plugin.monsters().data(entity).map(MonsterData::rarity).orElse(MonsterRarity.NORMAL);
        final HeistConfig.Alarm alarm = config.settings().alarm();
        addAlarm(run, switch (rarity) {
            case MAGIC -> alarm.magic();
            case RARE, UNIQUE -> alarm.rare();
            default -> alarm.normal();
        });
    }

    // ---- 守衛倒下 ----

    private void onComplete(final MapInstance instance, final List<Player> players) {
        final Run run = runs.get(instance.id());
        if (run == null) {
            return;
        }
        if (run.grand && run.wing < GRAND_WINGS) {
            nextWing(run, players);
            return;
        }
        vault(run, players);
        end(run);
    }

    /** 大劫盜：打完一處，開下一處，在裡面的人直接送過去（警戒值帶著走）。 */
    private void nextWing(final Run run, final List<Player> players) {
        Player owner = plugin.getServer().getPlayer(run.owner);
        if (owner == null && !players.isEmpty()) {
            owner = players.getFirst();
        }
        if (owner == null) {
            end(run);
            return;
        }
        final MapInstance previous = run.instance;
        run.wing++;
        final String problem = openWing(owner, run);
        if (problem != null) {
            // 開不了下一處：當作打完了（大金庫照給，免得藍圖白費）
            for (final Player player : players) {
                player.sendMessage(plugin.text().prefixed("<red>下一處據點開不了（" + ItemRenderer.escape(problem) + "），大金庫提早打開"));
            }
            runs.put(previous.id(), run);
            run.instance = previous;
            vault(run, players);
            end(run);
            return;
        }
        final List<UUID> uuids = new ArrayList<>();
        for (final Player player : players) {
            uuids.add(player.getUniqueId());
            player.showTitle(Title.title(plugin.text().parse("<#d4a017>第 " + run.wing + "/" + GRAND_WINGS + " 處"),
                    plugin.text().parse("<gray>" + ItemRenderer.escape(run.site.name()) + " 正在成形，等一下會直接送你過去"),
                    Title.Times.times(Duration.ofMillis(100), Duration.ofMillis(2500), Duration.ofMillis(500))));
        }
        plugin.maps().sendInWhenOpen(run.instance, uuids);
    }

    /** 大金庫：在裡面的每個人一份；封鎖了減半，大劫盜加倍。 */
    private void vault(final Run run, final List<Player> players) {
        final HeistConfig.Settings settings = config.settings();
        final HeistConfig.Vault vault = settings.vault();
        final double lockdown = run.lockdown ? settings.lockdown().rewardMultiplier() : 1.0;
        final double grand = run.grand ? vault.grandMultiplier() : 1.0;
        final MonsterData data = new MonsterData(run.level, MonsterRarity.UNIQUE, List.of(), "大金庫");
        for (final Player player : players) {
            final List<DropTable.Drop> rolled = rollDrops(player, run.level, vault.rolls(), randomIn(vault.currency()), grand * lockdown);
            addCurrency(rolled, UNVEIL_STONE, scaled(randomIn(vault.unveilStones()), grand * lockdown));
            boolean unique = false;
            if (random().nextDouble() < Math.min(1, vault.uniqueChance() * grand * lockdown)) {
                final ItemData item = MapDrops.unique(plugin.items().generator(), UNIQUE_SOURCE, run.level, random());
                if (item != null) {
                    rolled.add(DropTable.Drop.equipment(item));
                    unique = true;
                }
            }
            boolean gem = false;
            if (random().nextDouble() < Math.min(1, vault.gemChance() * grand * lockdown) && currency(UNCUT_21) != null) {
                addCurrency(rolled, UNCUT_21, 1); // 2026-10：Lv.21 未切割技能寶石（以前是異質技能寶石）
                gem = true;
            }
            boolean finaleKey = false;
            if (run.grand && random().nextDouble() < vault.finaleKeyChance() * lockdown) {
                addCurrency(rolled, HeistFinale.KEY, 1);
                finaleKey = true;
            }
            if (!run.grand && random().nextDouble() < vault.blueprintChance() * lockdown) {
                addCurrency(rolled, BLUEPRINT, 1);
            }
            veil(rolled);
            // 2026-10：遺時珠寶（只在劫盜掉）：合約的大金庫 time_lost_jewel_chance，藍圖（大劫盜）×grand_multiplier；封鎖了減半
            final boolean timeLost = random().nextDouble() < Math.min(1, vault.timeLostChance() * grand * lockdown)
                    && addTimeLostJewel(rolled, run.level);
            final long gold = plugin.monsterModule().drops().rollGold(run.level, MonsterRarity.UNIQUE, quantity(player), random())
                    * Math.max(1, Math.round(vault.rolls() * grand * lockdown));
            MapRewards.deliver(plugin, player, player.getLocation(), data, rolled, gold);
            player.showTitle(Title.title(plugin.text().parse("<#d4a017>大金庫打開了！"),
                    plugin.text().parse(run.lockdown ? "<gray>封鎖了，獎勵減半" : "<gray>乾淨俐落"),
                    Title.Times.times(Duration.ofMillis(100), Duration.ofMillis(2500), Duration.ofMillis(500))));
            player.playSound(player.getLocation(), Sound.BLOCK_VAULT_OPEN_SHUTTER, 1.0f, 0.8f);
            if (unique) {
                player.sendMessage(plugin.text().prefixed("<#af6025>大金庫裡有一件劫盜專屬的傳奇！"));
            }
            if (gem) {
                player.sendMessage(plugin.text().prefixed("<#d4a017>大金庫裡有一顆 Lv.21 未切割技能寶石！"));
            }
            if (timeLost) {
                player.sendMessage(plugin.text().prefixed("<#7fd4ff>大金庫裡有一顆遺時星晶！"));
            }
            if (finaleKey) {
                player.sendMessage(plugin.text().prefixed("<#8b0000>大金庫深處有一個鑰印……金庫之主在等你。<gray>（找盜賊首領）"));
            }
        }
        plugin.getSLF4JLogger().info("劫盜：{} 的{}完成（{}，{} 人）", run.site.name(), run.grand ? "大劫盜" : "劫盜",
                run.lockdown ? "封鎖" : "沒封鎖", players.size());
    }

    private void end(final Run run) {
        run.bar.removeAll();
        runs.values().removeIf(other -> other == run);
    }

    void clear() {
        finale.clear();
        for (final Run run : new ArrayList<>(runs.values())) {
            run.bar.removeAll();
        }
        runs.clear();
    }

    /** 選單用：這個玩家現在在不在劫盜裡（顯示警戒）。 */
    public String status(final Player player) {
        if (plugin.maps() == null) {
            return null;
        }
        final MapInstance instance = plugin.maps().instanceAt(player.getLocation());
        final Run run = instance == null ? null : runs.get(instance.id());
        if (run == null) {
            return null;
        }
        return run.lockdown ? "封鎖中" : "警戒 " + (int) run.alarm + "%";
    }

    /** 給選單：揭露的三個選項，同一件物品同一條隱匿詞綴只擲一次（關掉選單再打開還是同樣三個）。 */
    private final Map<String, List<tw.exilecore.item.RolledAffix>> offers = new HashMap<>();

    List<tw.exilecore.item.RolledAffix> offers(final ItemData data, final int affixIndex) {
        final String offerKey = data.uuid() + ":" + affixIndex;
        final List<tw.exilecore.item.RolledAffix> cached = offers.get(offerKey);
        if (cached != null) {
            return cached;
        }
        final tw.exilecore.item.RolledAffix veiled = data.affixes().get(affixIndex);
        final List<tw.exilecore.item.RolledAffix> fresh = Veiled.options(data, Veiled.typeOf(veiled), veiled, plugin.items().generator(),
                random(), Veiled.CHOICES);
        if (offers.size() > 2000) {
            offers.clear();
        }
        offers.put(offerKey, fresh);
        return fresh;
    }

    void forgetOffer(final ItemData data, final int affixIndex) {
        offers.remove(data.uuid() + ":" + affixIndex);
    }
}
