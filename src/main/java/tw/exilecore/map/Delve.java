package tw.exilecore.map;

import net.kyori.adventure.bossbar.BossBar;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.title.Title;
import org.bukkit.GameMode;
import org.bukkit.Location;
import org.bukkit.Particle;
import org.bukkit.Sound;
import org.bukkit.attribute.Attribute;
import org.bukkit.attribute.AttributeInstance;
import org.bukkit.entity.LivingEntity;
import org.bukkit.entity.Mob;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;
import org.bukkit.potion.PotionEffect;
import org.bukkit.potion.PotionEffectType;
import tw.exilecore.ExileCore;
import tw.exilecore.character.PlayerState;
import tw.exilecore.currency.CurrencyType;
import tw.exilecore.item.ItemRenderer;
import tw.exilecore.monster.MonsterData;
import tw.exilecore.monster.MonsterRarity;
import tw.exilecore.zone.ZoneConfig;

import java.io.IOException;
import java.time.Duration;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.random.RandomGenerator;

/**
 * 2026-11-07 照站長「地心礦坑有辦法簡化嗎、新增一種深坑鑰匙、能組隊、排行不重置」：星脈深坑（PoE 的 Delve 簡化版）。
 * 用深坑鑰匙在地圖裝置開一個獨立的場地，一層一層往下打：
 * <ul>
 *   <li>每一層的怪物照一張隨機地圖換，怪物等級 +1（最多 +20）、生命與傷害 +5%、更多魔法與稀有怪；每 10 層一隻深坑守衛。</li>
 *   <li>殺光這一層，場地中間打開往下的裂口，走進去就是下一層；每過一層每個人各拿獎勵與藍晶。</li>
 *   <li>黑暗：每個人自己一條，一直往上漲（越深漲越快），在附近殺怪回光；滿了每秒扣最大生命（2026-11-07：最多扣到剩 1 點，不會死）。</li>
 *   <li>每個人只能進去一次（死了、離開就出局，已經拿到的照拿）；全部的人都出局這一場就結束。</li>
 *   <li>角色記住最深到第幾層，下次從「最深 − 5」層開始（深掘升級少退）；帳號的最深紀錄進排行（{@link DelveLadder}，清檔也不重置）。</li>
 * </ul>
 * 藍晶在地圖裝置的深坑按鈕右鍵（{@link DelveMenu}）買升級：燈芯（黑暗漲得慢）、聚光（殺怪回更多光）、深掘（起始層更深）。
 * 設定在 {@code map_events.yml} 的 {@code delve}。
 */
final class Delve {

    /** 實例的種類（{@link MapService.Special#kind()}）。 */
    static final String KIND = "delve";
    /**
     * 2026-11-07 照站長「守衛新增掉落星坑 BOSS 鑰匙，然後再做一隻 BOSS」：晶淵王座（深坑首領的場地）的實例種類；
     * 也是 maps.yml event_bosses 的代號與首領專屬傳奇的來源（uniques.yml 的 sources 寫 delve_boss）。
     */
    static final String BOSS_KIND = "delve_boss";
    /** 角色進度：最深過到第幾層。 */
    static final String BEST_KEY = "delve.best";
    /** 角色進度：升級的等級（delve.up.＜代號＞）。 */
    static final String UPGRADE_PREFIX = "delve.up.";
    /** 2026-11-07 照站長「選檢查點」：玩家選的起始層（角色進度；沒選或已經不能選照最深算的那層）。 */
    static final String START_KEY = "delve.start";
    private static final int COLOR = 0x4fa3ff;
    private static final double ARENA_RADIUS = 16;
    private static final double FIRST_DELAY_TICKS = 60;
    /** 走進裂口中心幾格內算進去。 */
    private static final double EXIT_RADIUS = 2.2;
    /** 黑暗多少以上畫面變暗（原版的黑暗效果）。 */
    private static final double DIM_AT = 80;
    /** 全部的人出局後幾 tick 結束這一場。 */
    private static final long EMPTY_END_TICKS = 100;
    /** 2026-11-07 照站長：場上同時最多幾隻（守衛也算一隻，但守衛自己一定刷得出來），殺掉了才補；這次之前一層的怪一口氣全部刷出來。 */
    static final int MAX_ALIVE = 60;

    /** 藍晶買的升級。 */
    enum Upgrade {
        WICK("wick", "燈芯", "黑暗漲得慢 8%"),
        LIGHT("light", "聚光", "殺怪回的光 +15%"),
        DEEP("deep", "深掘", "從更深的地方開始（少退 1 層）");

        final String id;
        final String label;
        final String perLevel;

        Upgrade(final String id, final String label, final String perLevel) {
            this.id = id;
            this.label = label;
            this.perLevel = perLevel;
        }
    }

    private enum Phase { WAITING, FIGHT, EXIT, DONE }

    /** 一場深坑。 */
    private static final class Run {
        final MapInstance instance;
        final int startFloor;
        Phase phase = Phase.WAITING;
        int floor;
        int deepest;
        long startAt = -1;
        /** 裂口從這個 tick 起才吸人（過關後等 3 秒，站在中間的人不會馬上被吸下去）。 */
        long exitArmAt;
        long fightStart;
        long nextSpawn;
        int target;
        int spawned;
        boolean guardianSpawned;
        long emptyTicks;
        List<ZoneConfig.SpawnEntry> monsters = List.of();
        String theme = "";
        final List<LivingEntity> alive = new ArrayList<>();
        final Map<UUID, Double> darkness = new HashMap<>();
        final Map<UUID, BossBar> darkBars = new HashMap<>();
        final Set<UUID> joined = new HashSet<>();
        /** 2026-11-07：這一場的深坑守衛（掉守衛專屬的東西）。 */
        final Set<UUID> guardians = new HashSet<>();
        BossBar bar;
        final Set<UUID> watching = new HashSet<>();

        Run(final MapInstance instance, final int startFloor) {
            this.instance = instance;
            this.startFloor = startFloor;
            this.floor = startFloor - 1;
        }
    }

    private final ExileCore plugin;
    private final MapService service;
    private final MapEventConfig config;
    private final Map<Integer, Run> runs = new HashMap<>();
    private final DelveLadder ladder;

    Delve(final ExileCore plugin, final MapService service, final MapEventConfig config) {
        this.plugin = plugin;
        this.service = service;
        this.config = config;
        this.ladder = new DelveLadder(plugin.getDataFolder().toPath().resolve("delve_ladder.json"));
        this.ladder.load();
    }

    MapEventConfig.DelveSettings settings() {
        return config.delve();
    }

    DelveLadder ladder() {
        return ladder;
    }

    CurrencyType key() {
        return plugin.currencies() == null ? null : plugin.currencies().repository().get(settings().key());
    }

    /** 2026-11-07：深坑首領鑰匙與晶淵王座的設定（map_events.yml 的 delve.boss_key、delve.boss）。 */
    MapEventConfig.DelveBossSettings bossSettings() {
        return config.delveBoss();
    }

    /** 2026-11-07：深坑首領鑰匙（守衛掉；map_events.yml 的 delve.boss_key.id）。 */
    CurrencyType bossKey() {
        return plugin.currencies() == null ? null : plugin.currencies().repository().get(config.delveBoss().key());
    }

    CurrencyType azurite() {
        return plugin.currencies() == null ? null : plugin.currencies().repository().get(settings().azurite());
    }

    // ---- 角色的紀錄與升級 ----

    /** 這個角色最深過到第幾層。 */
    int best(final Player player) {
        return parse(plugin.players().get(player).progress().get(BEST_KEY));
    }

    int upgrade(final Player player, final Upgrade upgrade) {
        return parse(plugin.players().get(player).progress().get(UPGRADE_PREFIX + upgrade.id));
    }

    /**
     * 下一場從第幾層開始：2026-11-07 照站長「選檢查點」——玩家選的檢查點（{@link #checkpoints}），沒選或不在清單裡照最深的那層；
     * 這次之前一律照最深 − 退的層數。
     */
    int startFloor(final Player player) {
        final List<Integer> options = checkpoints(player);
        final int chosen = parse(plugin.players().get(player).progress().get(START_KEY));
        return options.contains(chosen) ? chosen : options.getLast();
    }

    /** 2026-11-07：這個角色能選的起始層（由淺到深，最後一個是照最深算的那層）。 */
    List<Integer> checkpoints(final Player player) {
        return settings().checkpoints(best(player), upgrade(player, Upgrade.DEEP));
    }

    /** 2026-11-07：換到下一個（step 1）或上一個（step −1）檢查點，繞一圈；回傳新的起始層。 */
    int cycleStart(final Player player, final int step) {
        final List<Integer> options = checkpoints(player);
        final int at = options.indexOf(startFloor(player));
        final int next = options.get(Math.floorMod(at + step, options.size()));
        plugin.characters().setProgress(player, START_KEY, String.valueOf(next));
        return next;
    }

    /** 買一級升級：成功回傳 null，不然回傳原因。 */
    String buy(final Player player, final Upgrade upgrade) {
        final CurrencyType azurite = azurite();
        if (azurite == null) {
            return "currencies.yml 沒有 " + settings().azurite();
        }
        final int level = upgrade(player, upgrade);
        final int cost = settings().upgradeCost(level);
        if (cost < 0) {
            return upgrade.label + "已經滿級了";
        }
        final int have = service.countCurrency(player, azurite);
        if (have < cost) {
            return upgrade.label + " " + (level + 1) + " 級要 " + cost + " 個" + azurite.name() + "（你有 " + have + " 個）";
        }
        service.takeCurrency(player, azurite, cost, tw.exilecore.economy.EconomySources.MAP_DEVICE);
        plugin.characters().setProgress(player, UPGRADE_PREFIX + upgrade.id, String.valueOf(level + 1));
        return null;
    }

    private static int parse(final String text) {
        if (text == null) {
            return 0;
        }
        try {
            return Math.max(0, Integer.parseInt(text.trim()));
        } catch (final NumberFormatException e) {
            return 0;
        }
    }

    // ---- 開場 ----

    /** 開一場：用掉鑰匙、開一個只有入口與場地的實例。成功回傳 null，不然回傳原因。 */
    String open(final Player player) {
        final MapEventConfig.DelveSettings settings = settings();
        if (!plugin.players().get(player).hasCharacter()) {
            return "先建立角色（/char）";
        }
        if (service.world() == null) {
            return "地圖世界沒有載入（看主控台）";
        }
        final CurrencyType key = key();
        if (key == null) {
            return "currencies.yml 沒有 " + settings.key();
        }
        final int have = service.countCurrency(player, key);
        if (have < settings.cost()) {
            return settings.name() + "要 " + settings.cost() + " 把" + key.name() + "（你有 " + have + " 把；地圖 Boss 有機率掉）";
        }
        final MapConfig.MapDef from = pickMap(service.random());
        if (from == null) {
            return "maps.yml 沒有可以當場地的地圖（要有怪物與 Boss）";
        }
        // base、ring 不抄（不要被當成那張地圖來掉地圖、算星圖）；Boss 不會出來（holdsBoss）
        final MapConfig.MapDef arena = new MapConfig.MapDef(KIND, settings.name(), null, 0, from.level(), from.theme(), 0, 0, List.of(), false,
                from.monsters(), from.boss(), "星脈往下延伸，越深越暗。", false, from.layout());
        final MapStats stats = service.eventBossStats(null, player, settings.levelBonus());
        final MapService.Special special = new MapService.Special(KIND, COLOR, (instance, players) -> { });
        final MapInstance instance = service.openSpecialInstance(player, arena, stats, MapService.UBER_ROOMS, special);
        if (instance == null) {
            final String problem = service.specialProblem();
            return problem == null ? "地圖世界現在開不了新的實例，等一下再試" : problem;
        }
        service.takeCurrency(player, key, settings.cost(), tw.exilecore.economy.EconomySources.MAP_DEVICE);
        final UUID ownerId = player.getUniqueId();
        instance.paidRefund(() -> service.refundLater(ownerId, key, settings.cost())); // 蓋圖失敗、重開伺服器時還沒進去就退鑰匙
        final int start = startFloor(player);
        runs.put(instance.id(), new Run(instance, start));
        player.sendMessage(plugin.text().prefixed("<#7cc4ff>" + ItemRenderer.escape(settings.name()) + "<gray>開好了（怪物等級 " + stats.monsterLevel()
                + "）：從<white>第 " + start + " 層</white>開始，走進場地中間就開打。每個人只能進去一次，隊友也能一起進。"
                + "<dark_gray>（傳送門在地圖裝置後面）"));
        plugin.getSLF4JLogger().info("{} 開了{}（Lv.{}，從第 {} 層）", player.getName(), settings.name(), stats.monsterLevel(), start);
        return null;
    }

    /** 2026-11-07：這個實例是不是晶淵王座（深坑首領的場地；路上不刷怪）。 */
    static boolean isBossArena(final MapInstance instance) {
        return instance.special() != null && BOSS_KIND.equals(instance.special().kind());
    }

    /**
     * 2026-11-07 照站長「守衛新增掉落星坑 BOSS 鑰匙，然後再做一隻 BOSS，把暮色瓣落、遺忘看守都放進這隻裡面」：
     * 開一場晶淵王座——用掉深坑首領鑰匙、開一個只有入口與首領房的實例（照時沉領域；災厄級固定等級、開門就是 2 門）。
     * 成功回傳 null，不然回傳原因。
     */
    String openBoss(final Player player) {
        final MapEventConfig.DelveBossSettings settings = config.delveBoss();
        final MapConfig.EventBoss boss = service.config().eventBoss(BOSS_KIND);
        if (boss == null) {
            return "maps.yml 的 event_bosses 沒有 " + BOSS_KIND;
        }
        if (!plugin.players().get(player).hasCharacter()) {
            return "先建立角色（/char）";
        }
        if (service.world() == null) {
            return "地圖世界沒有載入（看主控台）";
        }
        final CurrencyType key = bossKey();
        if (key == null) {
            return "currencies.yml 沒有 " + settings.key();
        }
        final int have = service.countCurrency(player, key);
        if (have < settings.cost()) {
            return settings.name() + "要 " + settings.cost() + " 把" + key.name() + "（你有 " + have + " 把；星脈深坑的守衛 "
                    + Math.round(settings.keyChance() * 100) + "% 掉）";
        }
        final MapConfig.MapDef from = pickMap(service.random());
        if (from == null) {
            return "maps.yml 沒有可以當場地的地圖（要有怪物與 Boss）";
        }
        final MapConfig.MapDef arena = new MapConfig.MapDef(BOSS_KIND, settings.name(), null, 0, from.level(), from.theme(), 0, 0, List.of(), false,
                from.monsters(), boss.boss(), "星脈的最底下沒有光，只有一顆還在跳的藍色心臟。", false, MapLayout.Style.OPEN);
        final MapStats stats = service.eventBossStats(boss, player, settings().levelBonus()); // 災厄級固定等級
        final MapService.Special special = new MapService.Special(BOSS_KIND, COLOR, (instance, players) -> {
            for (final Player each : players) {
                if (plugin.events() != null) {
                    plugin.events().onMapEvent(each, instance.level()); // 每月挑戰：算一個地圖事件
                }
            }
            plugin.getSLF4JLogger().info("地圖 #{}：{}的首領倒下", instance.id(), settings.name());
        });
        final MapInstance instance = service.openSpecialInstance(player, arena, stats, MapService.UBER_ROOMS, special);
        if (instance == null) {
            final String problem = service.specialProblem();
            return problem == null ? "地圖世界現在開不了新的實例，等一下再試" : problem;
        }
        service.takeCurrency(player, key, settings.cost(), tw.exilecore.economy.EconomySources.MAP_DEVICE);
        final UUID ownerId = player.getUniqueId();
        instance.paidRefund(() -> service.refundLater(ownerId, key, settings.cost())); // 蓋圖失敗、重開伺服器時還沒開打就退鑰匙
        player.sendMessage(plugin.text().prefixed("<#7cc4ff>" + ItemRenderer.escape(settings.name()) + "<gray>開好了（T" + stats.tier() + "・怪物等級 "
                + stats.monsterLevel() + "）：下一間就是 " + ItemRenderer.escape(boss.boss().name()) + "<dark_gray>（傳送門在地圖裝置後面）"));
        plugin.getSLF4JLogger().info("{} 開了{}（T{}，Lv.{}）", player.getName(), settings.name(), stats.tier(), stats.monsterLevel());
        return null;
    }

    /** 隨機挑一張一般地圖（怪物與主題）。 */
    private MapConfig.MapDef pickMap(final RandomGenerator random) {
        final List<MapConfig.MapDef> pool = new ArrayList<>();
        for (final MapConfig.MapDef map : service.config().maps()) {
            if (!map.isFinal() && map.boss() != null && !map.monsters().isEmpty()) {
                pool.add(map);
            }
        }
        return pool.isEmpty() ? null : pool.get(random.nextInt(pool.size()));
    }

    // ---- 2026-11-07 照站長「星脈深坑裡的怪物要會掉 PoE2 Fate of the Vaal 的通貨」----

    /**
     * 一隻怪物給一個人的那份掉落（MapService.rollLoot 的特別實例那段叫的）：照稀有度與層數擲瓦爾通貨（map_events.yml 的 delve.vaal_drops）；
     * 深坑守衛擲 guardian_rolls 次，再擲一次守衛專屬的傳奇（uniques.yml 的 sources 有 delve_guardian）；
     * 2026-11-07：守衛以外的怪再擲一次深坑專屬的基底（vaal_drops.base，祭紋華袍）。
     */
    void loot(final MapInstance instance, final LivingEntity entity, final MonsterData data, final double quantityBonus,
              final List<tw.exilecore.monster.DropTable.Drop> drops) {
        final Run run = runs.get(instance.id());
        if (run == null || plugin.currencies() == null || data == null) {
            return;
        }
        final MapEventConfig.VaalDrops vaal = config.vaalDrops();
        final RandomGenerator random = service.random();
        final boolean guardian = run.guardians.contains(entity.getUniqueId());
        final int rarity = switch (data.rarity()) {
            case NORMAL -> 0;
            case MAGIC -> 1;
            case RARE -> 2;
            case UNIQUE -> 3;
        };
        final int rolls = guardian ? Math.max(1, vaal.guardianRolls()) : 1;
        final double chance = vaal.chanceAt(rarity, Math.max(1, run.floor)) * (1 + Math.max(0, quantityBonus));
        for (int i = 0; i < rolls; i++) {
            if (random.nextDouble() >= chance) {
                continue;
            }
            final tw.exilecore.currency.CurrencyType type = pickVaal(vaal.weights(), random);
            if (type != null) {
                drops.add(tw.exilecore.monster.DropTable.Drop.currency(type));
            }
        }
        // 2026-11-07 照站長「祭紋華袍改成會掉落，只在深坑小怪掉、不能賭博」：守衛以外的怪每隻擲 base.chance（吃物品數量），
        //   魔法或稀有、未鑑定，物品等級照這隻怪的等級（到基底的掉落等級才掉）；這次之前祭紋華袍哪裡都不掉
        if (!guardian && vaal.baseId() != null && vaal.baseChance() > 0) {
            final tw.exilecore.item.ItemBase base = plugin.items().repository().base(vaal.baseId());
            final int level = Math.max(instance.level(), data.level());
            if (base != null && base.dropLevel() <= level
                    && random.nextDouble() < Math.min(1, vaal.baseChance() * (1 + Math.max(0, quantityBonus)))) {
                drops.add(tw.exilecore.monster.DropTable.Drop.equipment(MapRewards.specialBase(plugin, base, level, random)));
            }
        }
        // 2026-11-07 照站長「守衛新增掉落星坑 BOSS 鑰匙」：守衛給每個有份的人各擲一次 delve.boss_key.chance（20%，不吃物品數量）
        if (guardian && config.delveBoss().dropsKey(random.nextDouble())) {
            final CurrencyType bossKey = bossKey();
            if (bossKey != null) {
                drops.add(tw.exilecore.monster.DropTable.Drop.currency(bossKey));
            }
        }
        if (guardian && random.nextDouble() < vaal.guardianUnique()) {
            final tw.exilecore.item.ItemData unique = MapDrops.unique(plugin.items().generator(), GUARDIAN_SOURCE, instance.level(), random);
            if (unique != null) {
                drops.add(tw.exilecore.monster.DropTable.Drop.equipment(unique));
            }
        }
    }

    /** 2026-11-07：深坑守衛專屬傳奇的來源代號（uniques.yml 的 sources）。 */
    static final String GUARDIAN_SOURCE = "delve_guardian";

    private tw.exilecore.currency.CurrencyType pickVaal(final Map<String, Integer> weights, final RandomGenerator random) {
        int total = 0;
        for (final Map.Entry<String, Integer> entry : weights.entrySet()) {
            if (entry.getValue() > 0 && plugin.currencies().repository().get(entry.getKey()) != null) {
                total += entry.getValue();
            }
        }
        if (total <= 0) {
            return null;
        }
        int pick = random.nextInt(total);
        for (final Map.Entry<String, Integer> entry : new java.util.TreeMap<>(weights).entrySet()) {
            final tw.exilecore.currency.CurrencyType type = plugin.currencies().repository().get(entry.getKey());
            if (entry.getValue() <= 0 || type == null) {
                continue;
            }
            pick -= entry.getValue();
            if (pick < 0) {
                return type;
            }
        }
        return null;
    }

    // ---- 給別的地方問 ----

    static boolean is(final MapInstance instance) {
        return instance.special() != null && KIND.equals(instance.special().kind());
    }

    // ---- 每 10 tick ----

    void tick(final long now) {
        if (runs.isEmpty()) {
            return;
        }
        for (final Run run : List.copyOf(runs.values())) {
            final MapInstance instance = run.instance;
            if (service.instance(instance.id()) == null || instance.state() == MapInstance.State.CLOSING) {
                dispose(run);
                runs.remove(instance.id());
                continue;
            }
            if (instance.state() != MapInstance.State.OPEN || run.phase == Phase.DONE) {
                continue;
            }
            tickRun(run, now);
        }
    }

    private void tickRun(final Run run, final long now) {
        final MapEventConfig.DelveSettings settings = settings();
        final List<Player> players = players(run.instance);
        for (final Player player : players) {
            run.joined.add(player.getUniqueId());
        }
        collectKills(run);
        if (run.phase != Phase.WAITING && players.isEmpty()) {
            run.emptyTicks += 10;
            if (run.emptyTicks >= EMPTY_END_TICKS) {
                finish(run, "所有人都出局了");
                return;
            }
        } else {
            run.emptyTicks = 0;
        }
        switch (run.phase) {
            case WAITING -> {
                final Location center = center(run.instance);
                for (final Player player : players) {
                    if (player.getLocation().distanceSquared(center) <= ARENA_RADIUS * ARENA_RADIUS) {
                        run.startAt = now + Math.round(FIRST_DELAY_TICKS);
                        run.phase = Phase.EXIT; // 第一層：倒數完自己開（不用走進裂口）
                        announceFloor(run, run.floor + 1);
                        break;
                    }
                }
            }
            case EXIT -> {
                if (run.startAt >= 0) {
                    if (now >= run.startAt) {
                        run.startAt = -1;
                        startFloor(run, now, players.size());
                    }
                } else if (now >= run.exitArmAt) {
                    drawExit(run);
                    final Location center = center(run.instance);
                    for (final Player player : players) {
                        if (player.getLocation().distanceSquared(center) <= EXIT_RADIUS * EXIT_RADIUS) {
                            descend(run, players);
                            startFloor(run, now, players.size());
                            break;
                        }
                    }
                }
            }
            case FIGHT -> tickFight(run, now, players);
            case DONE -> {
            }
        }
        // 2026-11-07 照站長「層與層之間暫停黑暗」：只有打怪的時候漲；這次之前過關後等裂口、走過去也在漲
        if (run.phase == Phase.FIGHT) {
            tickDarkness(run, players, now);
        }
        updateBar(run, players, now);
        run.instance.setDelirium(run.phase == Phase.FIGHT ? settings.difficulty(Math.max(1, run.floor)) : 0);
    }

    private void announceFloor(final Run run, final int floor) {
        final MapEventConfig.DelveSettings settings = settings();
        final String what = settings.guardianFloor(floor) ? "<#ff5c6a>第 " + floor + " 層・守衛層" : "<#7cc4ff>第 " + floor + " 層";
        for (final Player player : players(run.instance)) {
            player.showTitle(Title.title(plugin.text().parse(what),
                    plugin.text().parse("<gray>怪物等級 +" + settings.levelBonus(floor) + "・生命與傷害 +" + Math.round(settings.difficulty(floor) * 100) + "%"),
                    Title.Times.times(Duration.ofMillis(200), Duration.ofMillis(1800), Duration.ofMillis(400))));
        }
    }

    /** 走進裂口：所有人一起往下（傳到場地中間）。 */
    private void descend(final Run run, final List<Player> players) {
        final Location center = center(run.instance);
        for (final Player player : players) {
            final Location to = center.clone();
            to.setYaw(player.getLocation().getYaw());
            player.teleport(to);
            player.playSound(to, Sound.BLOCK_DEEPSLATE_BREAK, 1f, 0.6f);
        }
        announceFloor(run, run.floor + 1);
    }

    private void startFloor(final Run run, final long now, final int playerCount) {
        final MapEventConfig.DelveSettings settings = settings();
        run.floor++;
        run.phase = Phase.FIGHT;
        run.fightStart = now;
        run.nextSpawn = now;
        run.spawned = 0;
        run.guardianSpawned = false;
        run.target = settings.count(run.floor, Math.max(1, playerCount));
        final MapConfig.MapDef from = pickMap(service.random());
        run.monsters = from == null ? run.instance.map().monsters() : from.monsters();
        run.theme = from == null ? "" : from.name();
        for (final Player player : players(run.instance)) {
            player.playSound(player.getLocation(), Sound.AMBIENT_CAVE, 0.8f, 0.8f);
        }
        if (!run.theme.isEmpty()) {
            broadcast(run.instance, "<#7cc4ff>第 " + run.floor + " 層<gray>：這一層是<white>" + ItemRenderer.escape(run.theme) + "</white>的怪物（共 " + run.target + " 隻"
                    + (settings.guardianFloor(run.floor) ? "，還有一隻<#ff5c6a>深坑守衛</#ff5c6a>" : "") + "）");
        }
    }

    private void tickFight(final Run run, final long now, final List<Player> players) {
        final MapEventConfig.DelveSettings settings = settings();
        final RandomGenerator random = service.random();
        if (players.isEmpty()) {
            run.fightStart += 10;
            return;
        }
        if (!run.guardianSpawned && settings.guardianFloor(run.floor)) {
            run.guardianSpawned = true;
            final Player near = players.get(random.nextInt(players.size()));
            final LivingEntity guardian = spawnOne(run, near, MonsterRarity.UNIQUE, random);
            if (guardian != null) {
                run.guardians.add(guardian.getUniqueId());
                plugin.monsters().data(guardian).ifPresent(data -> plugin.monsters().decorateData(guardian, new MonsterData(data.level(), data.rarity(),
                        data.mods(), "深坑守衛・" + data.name(), data.lifeMultiplier() * settings.guardianLife(), data.damageMultiplier(), false)));
                broadcast(run.instance, "<#ff5c6a>深坑守衛從黑暗裡爬出來了");
            }
        }
        if (run.spawned < run.target && now >= run.nextSpawn) {
            run.nextSpawn = now + Math.round(settings.spawnSeconds() * 20);
            for (final Player player : players) {
                final int group = settings.groupMin() + (settings.groupMax() > settings.groupMin() ? random.nextInt(settings.groupMax() - settings.groupMin() + 1) : 0);
                for (int i = 0; i < group && run.spawned < run.target && run.alive.size() < MAX_ALIVE; i++) {
                    final double roll = random.nextDouble();
                    final MonsterRarity rarity = roll < settings.rareChance(run.floor) ? MonsterRarity.RARE
                            : roll < settings.rareChance(run.floor) + settings.magicChance(run.floor) ? MonsterRarity.MAGIC : MonsterRarity.NORMAL;
                    spawnOne(run, player, rarity, random);
                    run.spawned++; // 刷不出來（附近沒有地板）也算，免得卡在這一層
                }
            }
        }
        if (run.spawned >= run.target && run.alive.isEmpty()) {
            clearFloor(run, false);
            run.exitArmAt = now + 60;
            return;
        }
        // 2026-11-07 照站長：時間到算失敗，這一場結束，紀錄只記到上一層（不給這一層的獎勵）；
        //   這次之前時間到剩下的怪散掉、照樣算過關，打不動的躲 150 秒就能往下刷排行
        if (now - run.fightStart > Math.round(settings.floorSeconds() * 20)) {
            broadcast(run.instance, "<red>第 " + run.floor + " 層時間到（" + Math.round(settings.floorSeconds()) + " 秒內沒有殺光）：深坑崩塌了");
            finish(run, "第 " + run.floor + " 層時間到");
        }
    }

    /** 在玩家附近 6–10 格刷一隻（這一層的怪；等級照層數加）。 */
    private LivingEntity spawnOne(final Run run, final Player player, final MonsterRarity rarity, final RandomGenerator random) {
        final MapInstance instance = run.instance;
        final ZoneConfig.SpawnEntry entry = service.spawner().chooseMonster(run.monsters.isEmpty() ? instance.map().monsters() : run.monsters, random);
        if (entry == null) {
            return null;
        }
        Location at = null;
        for (int attempt = 0; attempt < 12 && at == null; attempt++) {
            final double angle = random.nextDouble() * Math.PI * 2;
            final double distance = 6 + random.nextDouble() * 4;
            final double x = player.getLocation().getX() + Math.cos(angle) * distance;
            final double z = player.getLocation().getZ() + Math.sin(angle) * distance;
            if (instance.layout().isFloor((int) Math.floor(x) - instance.originX(), (int) Math.floor(z) - instance.originZ())) {
                at = new Location(instance.world(), Math.floor(x) + 0.5, MapLayout.FLOOR_Y + 1, Math.floor(z) + 0.5, random.nextFloat() * 360f, 0f);
            }
        }
        if (at == null) {
            return null;
        }
        final int level = Math.min(100, instance.level() + settings().levelBonus(run.floor));
        final List<String> mods = rarity == MonsterRarity.NORMAL ? List.of() : plugin.monsters().rollMods(rarity, level);
        final LivingEntity monster = service.spawner().spawn(instance, entry, at, rarity, mods);
        if (monster == null) {
            return null;
        }
        if (level != instance.level()) {
            plugin.monsters().data(monster).ifPresent(data -> plugin.monsters().decorateData(monster, new MonsterData(level, data.rarity(),
                    data.mods(), data.name(), data.lifeMultiplier(), data.damageMultiplier(), data.boss())));
        }
        run.alive.add(monster);
        tw.exilecore.skill.Fx.spawn(monster.getWorld(), Particle.SQUID_INK, monster.getLocation().add(0, 1, 0), 18, 0.4, 0.6, 0.4, 0.03);
        final AttributeInstance follow = monster.getAttribute(Attribute.FOLLOW_RANGE);
        if (follow != null && follow.getBaseValue() < 40) {
            follow.setBaseValue(40);
        }
        if (monster instanceof Mob mob) {
            mob.setTarget(player);
        }
        return monster;
    }

    /** 死掉的怪：附近的人回光（魔法 ×2、稀有 ×5、傳奇 ×10）。 */
    /** 2026-11-07 照站長「瓦爾傳奇照原作」：一個人的回光半徑——基礎半徑 ×（1 ＋ 增加的光照範圍），最少 0。 */
    static double lightRadius(final double base, final double increased) {
        return Math.max(0, base * (1 + increased));
    }

    private void collectKills(final Run run) {
        if (run.alive.isEmpty()) {
            return;
        }
        final MapEventConfig.DelveSettings settings = settings();
        for (final java.util.Iterator<LivingEntity> it = run.alive.iterator(); it.hasNext(); ) {
            final LivingEntity monster = it.next();
            if (monster.isValid() && !monster.isDead()) {
                continue;
            }
            it.remove();
            if (!monster.isDead()) {
                continue; // 被移除（不是被殺）不給光
            }
            final MonsterRarity rarity = plugin.monsters().data(monster).map(MonsterData::rarity).orElse(MonsterRarity.NORMAL);
            final double base = settings.lightPerKill() * switch (rarity) {
                case MAGIC -> 2;
                case RARE -> 5;
                case UNIQUE -> 10;
                default -> 1;
            };
            final Location at = monster.getLocation();
            for (final Player player : players(run.instance)) {
                // 2026-11-07 照站長「瓦爾傳奇照原作」（赤陽戰冕「增加 25% 光照範圍」）：每個人照自己的光照範圍（light.radius）放大回光的半徑；這次之前大家一樣
                final double radius = lightRadius(settings.lightRadius(),
                        plugin.players().sheet(player).increased(tw.exilecore.item.unique.VaalKeys.LIGHT_RADIUS, java.util.Set.of()));
                if (player.getWorld().equals(at.getWorld()) && player.getLocation().distanceSquared(at) <= radius * radius) {
                    final double gain = base * (1 + 0.15 * upgrade(player, Upgrade.LIGHT));
                    run.darkness.merge(player.getUniqueId(), -gain, (a, b) -> Math.max(0, a + b));
                }
            }
            tw.exilecore.skill.Fx.spawn(at.getWorld(), Particle.END_ROD, at.getX(), at.getY() + 1, at.getZ(), 6, 0.3, 0.4, 0.3, 0.02);
        }
    }

    /** 黑暗：每半秒漲一次；滿了扣最大生命（先扣能量護盾）。 */
    private void tickDarkness(final Run run, final List<Player> players, final long now) {
        final MapEventConfig.DelveSettings settings = settings();
        for (final Player player : players) {
            final UUID uuid = player.getUniqueId();
            final double rate = settings.darkRate(run.floor, upgrade(player, Upgrade.WICK));
            final double dark = Math.min(100, run.darkness.getOrDefault(uuid, 0.0) + rate * 0.5);
            run.darkness.put(uuid, dark);
            if (dark >= DIM_AT) {
                player.addPotionEffect(new PotionEffect(PotionEffectType.DARKNESS, 60, 0, true, false, false));
            }
            if (dark >= 100) {
                hurt(run, player, settings.darkDamage(run.floor) * 0.5, now);
            }
            final Location at = player.getLocation();
            tw.exilecore.skill.Fx.spawn(at.getWorld(), Particle.SQUID_INK, at.getX(), at.getY() + 1, at.getZ(), (int) Math.round(dark / 12), 3, 1, 3, 0.01);
        }
    }

    private void hurt(final Run run, final Player player, final double fraction, final long now) {
        final PlayerState state = plugin.players().get(player);
        if (!state.hasCharacter() || state.isDead() || state.isSafe()) {
            return;
        }
        final double amount = (state.maxLife() + state.maxEnergyShield()) * fraction;
        final double shield = state.energyShield();
        final double absorbed = Math.min(shield, amount);
        state.setEnergyShield(shield - absorbed);
        // 2026-11-07 照站長「黑暗只讓玩家剩最後一滴血，避免死掉，讓玩家有時間退出回營地、不噴經驗」：生命最多扣到剩 1；這次之前扣到 0 會死
        final double rest = Math.max(0, Math.min(amount - absorbed, state.life() - 1));
        if (absorbed <= 0 && rest <= 0) {
            return;
        }
        state.setLife(state.life() - rest);
        state.setLastDamageTick(now);
        if (plugin.combat() != null && plugin.combat().recap() != null) {
            final Map<tw.exilecore.stats.DamageType, Double> dealt = Map.of(tw.exilecore.stats.DamageType.CHAOS, amount);
            plugin.combat().recap().record(player, new tw.exilecore.combat.DeathRecap.Hit(now, "黑暗", settings().name(),
                    dealt, dealt, absorbed, rest, state.life(), state.maxLife(), false));
        }
        if (state.isDead()) {
            player.setHealth(0);
        } else {
            plugin.players().display(player, state);
        }
    }

    /** 這一層過關：每個人各拿獎勵、藍晶，記紀錄，打開往下的裂口。 */
    private void clearFloor(final Run run, final boolean timeUp) {
        final MapEventConfig.DelveSettings settings = settings();
        run.phase = Phase.EXIT;
        run.startAt = -1;
        run.deepest = Math.max(run.deepest, run.floor);
        final int serverBest = ladder.serverBest();
        boolean saved = false;
        for (final Player player : players(run.instance)) {
            final int got = reward(run, player, settings.rewards(run.floor));
            final int azurite = giveAzurite(run, player);
            run.darkness.merge(player.getUniqueId(), -settings.clearLight(), (a, b) -> Math.max(0, a + b));
            final boolean record = run.floor > best(player);
            if (record) {
                plugin.characters().setProgress(player, BEST_KEY, String.valueOf(run.floor));
            }
            final PlayerState state = plugin.players().get(player);
            if (ladder.record(player.getUniqueId(), player.getName(), state.hasCharacter() ? state.character().name() : "",
                    state.characterClass() == null ? "" : state.characterClass().displayName(), run.floor, System.currentTimeMillis())) {
                saved = true;
            }
            player.sendMessage(plugin.text().prefixed("<#7cc4ff>第 " + run.floor + " 層" + (timeUp ? "時間到" : "過關")
                    + "<gray>：拿到 <white>" + got + "</white> 件" + (azurite > 0 ? "、<#7cc4ff>" + azurite + " 個" + ItemRenderer.escape(azurite().name()) + "</#7cc4ff>" : "")
                    + (record ? "　<gold>新紀錄！" : "") + "<dark_gray>（場地中間的裂口往下）"));
            player.playSound(player.getLocation(), Sound.ENTITY_PLAYER_LEVELUP, 0.6f, 1.1f);
        }
        if (saved) {
            saveLadder();
        }
        if (run.floor > serverBest && serverBest > 0) {
            final String names = String.join("、", players(run.instance).stream().map(Player::getName).toList());
            for (final Player online : plugin.getServer().getOnlinePlayers()) {
                online.sendMessage(plugin.text().prefixed("<#7cc4ff>" + ItemRenderer.escape(settings.name()) + "全服新紀錄：<white>"
                        + ItemRenderer.escape(names) + "</white> 打到了第 <gold>" + run.floor + "</gold> 層"));
            }
        }
    }

    private void saveLadder() {
        plugin.getServer().getScheduler().runTaskAsynchronously(plugin, () -> {
            try {
                ladder.save();
            } catch (final IOException | RuntimeException e) { // 2026-11-07：執行期錯誤也記下來（這次之前沒接，存檔工作直接中斷）
                plugin.getSLF4JLogger().warn("星脈深坑的排行寫不進去", e);
            }
        });
    }

    private int giveAzurite(final Run run, final Player player) {
        final CurrencyType azurite = azurite();
        final int base = settings().azurite(run.floor);
        if (azurite == null || base <= 0 || plugin.currencies() == null) {
            return 0;
        }
        final tw.exilecore.loot.LootBonus.Bonus bonus = tw.exilecore.loot.LootBonus.of(plugin, player).plus(run.instance.stats().quantity() / 100.0, 0);
        final int count = bonus.amount(base, service.random());
        if (count <= 0) {
            return 0;
        }
        final List<ItemStack> items = new ArrayList<>();
        for (int left = count; left > 0; ) {
            final int n = Math.min(left, Math.max(1, azurite.stack()));
            items.add(plugin.currencies().create(azurite, n));
            left -= n;
        }
        MapRewards.give(plugin, player, items);
        return count;
    }

    /** 給一個人 shares 份獎勵（種類照權重；吃物品數量），回傳拿到幾件。 */
    private int reward(final Run run, final Player player, final int shares) {
        final MapEventConfig.DelveSettings settings = settings();
        if (shares <= 0 || settings.rewardWeights().isEmpty()) {
            return 0;
        }
        final RandomGenerator random = service.random();
        final tw.exilecore.loot.LootBonus.Bonus bonus = tw.exilecore.loot.LootBonus.of(plugin, player)
                .plus(run.instance.stats().quantity() / 100.0, run.instance.stats().rarity() / 100.0);
        final int times = bonus.amount(shares, random);
        List<ItemStack> items = new ArrayList<>();
        for (int i = 0; i < times; i++) {
            final MapEventConfig.Reward kind = MapRewards.pick(settings.rewardWeights(), random);
            if (kind == null) {
                continue;
            }
            final MapRewards.Bundle bundle = MapRewards.roll(plugin, service, kind, run.instance, player, config.delirium(),
                    MapEventConfig.DELIRIUM_SOURCE, random);
            if (bundle != null) {
                items.addAll(bundle.items());
            }
        }
        if (plugin.loot() != null) {
            items = plugin.loot().filterRewards(player, items, run.instance.level());
        }
        MapRewards.give(plugin, player, items);
        return items.size();
    }

    /** 全部的人出局：這一場結束，關掉場地。 */
    private void finish(final Run run, final String reason) {
        run.phase = Phase.DONE;
        run.instance.setDelirium(0);
        final MapEventConfig.DelveSettings settings = settings();
        for (final UUID uuid : run.joined) {
            final Player player = plugin.getServer().getPlayer(uuid);
            if (player != null) {
                player.sendMessage(plugin.text().prefixed("<#7cc4ff>" + ItemRenderer.escape(settings.name()) + "結束<gray>（" + reason + "）：這一場從第 "
                        + run.startFloor + " 層打到第 <white>" + Math.max(run.deepest, run.startFloor - 1) + "</white> 層；角色最深 <white>" + best(player)
                        + "</white> 層、排行第 <white>" + ladder.rank(uuid) + "</white> 名"));
            }
        }
        dispose(run);
        plugin.getSLF4JLogger().info("地圖 #{}：{}結束（第 {}～{} 層）", run.instance.id(), settings.name(), run.startFloor, run.deepest);
        service.close(run.instance, settings.name() + "結束");
    }

    // ---- 畫面 ----

    private void drawExit(final Run run) {
        final Location center = center(run.instance);
        for (int i = 0; i < 16; i++) {
            final double angle = Math.PI * 2 * i / 16 + run.floor;
            tw.exilecore.skill.Fx.spawn(center.getWorld(), Particle.SOUL_FIRE_FLAME, center.getX() + Math.cos(angle) * EXIT_RADIUS, center.getY() + 0.1,
                    center.getZ() + Math.sin(angle) * EXIT_RADIUS, 1, 0, 0, 0, 0);
        }
        tw.exilecore.skill.Fx.spawn(center.getWorld(), Particle.REVERSE_PORTAL, center.getX(), center.getY() + 0.5, center.getZ(), 20, 0.6, 0.6, 0.6, 0.02);
    }

    private void updateBar(final Run run, final List<Player> players, final long now) {
        final MapEventConfig.DelveSettings settings = settings();
        if (run.bar == null) {
            run.bar = BossBar.bossBar(Component.empty(), 1f, BossBar.Color.BLUE, BossBar.Overlay.PROGRESS);
        }
        final String name = "<#7cc4ff>" + ItemRenderer.escape(settings.name()) + "　";
        final String text;
        float progress = 1;
        switch (run.phase) {
            case WAITING -> text = name + "<gray>走進場地中間開始（從第 " + run.startFloor + " 層）";
            case EXIT -> text = run.startAt >= 0
                    ? name + "<white>第 " + (run.floor + 1) + " 層 <gray>" + Math.max(0, (run.startAt - now + 19) / 20) + " 秒後開始"
                    : name + "<green>第 " + run.floor + " 層過關<gray>：走進場地中間的裂口往下（黑暗暫停）";
            case FIGHT -> {
                final int remaining = run.target - run.spawned + run.alive.size();
                final long left = Math.max(0, Math.round(settings.floorSeconds()) - (now - run.fightStart) / 20);
                text = name + "<white>第 " + run.floor + " 層　<gray>剩 <white>" + remaining + "</white> 隻　" + left + " 秒　怪物 <#ff5c6a>+"
                        + Math.round(settings.difficulty(run.floor) * 100) + "%";
                progress = run.target <= 0 ? 0 : 1 - Math.min(1, remaining / (float) run.target);
            }
            default -> text = name + "<gray>結束";
        }
        run.bar.progress(Math.max(0, Math.min(1, progress)));
        run.bar.name(plugin.text().parse(text));
        final Set<UUID> seen = new HashSet<>();
        for (final Player player : players) {
            seen.add(player.getUniqueId());
            if (run.watching.add(player.getUniqueId())) {
                player.showBossBar(run.bar);
            }
            final BossBar dark = run.darkBars.computeIfAbsent(player.getUniqueId(), uuid -> {
                final BossBar created = BossBar.bossBar(Component.empty(), 0f, BossBar.Color.PURPLE, BossBar.Overlay.NOTCHED_10);
                player.showBossBar(created);
                return created;
            });
            final double value = run.darkness.getOrDefault(player.getUniqueId(), 0.0);
            dark.progress((float) Math.max(0, Math.min(1, value / 100)));
            dark.color(value >= 100 ? BossBar.Color.RED : BossBar.Color.PURPLE);
            dark.name(plugin.text().parse((value >= 100 ? "<red>黑暗 100%：每秒扣 " + Math.round(settings.darkDamage(run.floor) * 100) + "% 最大生命（最多扣到剩 1 點），快殺怪回光或回營地！"
                    : "<#b28cff>黑暗 " + Math.round(value) + "%<gray>（附近殺怪回光）")));
        }
        for (final UUID uuid : Set.copyOf(run.watching)) {
            if (!seen.contains(uuid)) {
                final Player player = plugin.getServer().getPlayer(uuid);
                if (player != null) {
                    player.hideBossBar(run.bar);
                    final BossBar dark = run.darkBars.remove(uuid);
                    if (dark != null) {
                        player.hideBossBar(dark);
                    }
                    player.removePotionEffect(PotionEffectType.DARKNESS);
                }
                run.watching.remove(uuid);
                run.darkBars.remove(uuid);
            }
        }
    }

    private void hideBars(final Run run) {
        for (final UUID uuid : run.watching) {
            final Player player = plugin.getServer().getPlayer(uuid);
            if (player != null) {
                if (run.bar != null) {
                    player.hideBossBar(run.bar);
                }
                final BossBar dark = run.darkBars.get(uuid);
                if (dark != null) {
                    player.hideBossBar(dark);
                }
                player.removePotionEffect(PotionEffectType.DARKNESS);
            }
        }
        run.watching.clear();
        run.darkBars.clear();
    }

    private void broadcast(final MapInstance instance, final String miniMessage) {
        for (final Player player : players(instance)) {
            player.sendMessage(plugin.text().prefixed(miniMessage));
        }
    }

    // ---- 工具 ----

    private List<Player> players(final MapInstance instance) {
        final List<Player> list = new ArrayList<>();
        for (final UUID uuid : instance.inside()) {
            final Player player = plugin.getServer().getPlayer(uuid);
            if (player != null && !player.isDead() && player.getGameMode() != GameMode.SPECTATOR && instance.contains(player.getLocation())) {
                list.add(player);
            }
        }
        return list;
    }

    private static Location center(final MapInstance instance) {
        final MapLayout.Room room = instance.layout().boss();
        return new Location(instance.world(), instance.originX() + room.cx() + 0.5, MapLayout.FLOOR_Y + 1, instance.originZ() + room.cz() + 0.5);
    }

    private void dispose(final Run run) {
        hideBars(run);
        for (final LivingEntity monster : run.alive) {
            if (monster.isValid()) {
                monster.remove();
            }
        }
        run.alive.clear();
    }

    void clear(final MapInstance instance) {
        final Run run = runs.remove(instance.id());
        if (run != null) {
            dispose(run);
        }
    }

    void clearAll() {
        for (final Run run : runs.values()) {
            dispose(run);
        }
        runs.clear();
    }
}
