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
import tw.exilecore.ExileCore;
import tw.exilecore.currency.CurrencyType;
import tw.exilecore.item.ItemRenderer;
import tw.exilecore.monster.MonsterData;
import tw.exilecore.monster.MonsterRarity;
import tw.exilecore.zone.ZoneConfig;

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
 * 2026-11-04 照站長「波次引擎＋15 層迷霧」：迷霧回廊（照 PoE 的 Simulacrum）。用迷霧碎片在地圖裝置開一個獨立的場地，
 * 霧從頭到尾都在：一波一波打（預設 15 波），每一波怪更多、更硬（生命、傷害照波數往上，走幻霧的 {@link MapInstance#setDelirium}），
 * 菁英波多一隻傳奇怪，最後一波是 Boss（地圖本身的 Boss 機制：招式、階段、血條照舊）。
 * 每過一波每個人各拿獎勵（種類跟幻霧一樣），中途離開或死光，已經拿到的照拿。設定在 {@code map_events.yml} 的 {@code fog_trial}。
 * <p>
 * 波次的流程寫在這裡（之後的絕境也照這個做）：等人走進場地 → 休息倒數 → 刷怪（在每個人附近一群一群刷，刷滿這一波的數量）→
 * 全部打完（或一波的時間到）算過關、發獎勵 → 下一波；Boss 波交給 {@link MapBosses}，Boss 倒下時 {@link #onComplete} 收尾。
 */
final class FogTrial {

    /** 實例的種類（{@link MapService.Special#kind()}）。 */
    static final String KIND = "fog_trial";
    private static final int COLOR = 0xb8b8d0;
    /** 走到 Boss 房中心幾格內才算進了場地（開始第一波）。 */
    private static final double ARENA_RADIUS = 16;
    /** 第一波開始前的倒數（秒）。 */
    private static final double FIRST_DELAY = 5;

    private enum Phase { WAITING, BREAK, FIGHT, BOSS, DONE }

    /** 一場迷霧回廊。 */
    private static final class Run {
        final MapInstance instance;
        Phase phase = Phase.WAITING;
        int wave;
        long breakUntil;
        long fightStart;
        long nextSpawn;
        int target;
        int spawned;
        boolean eliteSpawned;
        final List<LivingEntity> alive = new ArrayList<>();
        BossBar bar;
        final Set<UUID> watching = new HashSet<>();

        Run(final MapInstance instance) {
            this.instance = instance;
        }
    }

    private final ExileCore plugin;
    private final MapService service;
    private final MapEventConfig config;
    private final Map<Integer, Run> runs = new HashMap<>();

    FogTrial(final ExileCore plugin, final MapService service, final MapEventConfig config) {
        this.plugin = plugin;
        this.service = service;
        this.config = config;
    }

    MapEventConfig.FogTrialSettings settings() {
        return config.fogTrial();
    }

    CurrencyType splinter() {
        return plugin.currencies() == null ? null : plugin.currencies().repository().get(settings().splinter());
    }

    // ---- 開場 ----

    /** 開一場：用掉碎片、開一個只有入口與場地的實例。成功回傳 null，不然回傳原因。 */
    String open(final Player player) {
        final MapEventConfig.FogTrialSettings settings = settings();
        if (!plugin.players().get(player).hasCharacter()) {
            return "先建立角色（/char）";
        }
        if (service.world() == null) {
            return "地圖世界沒有載入（看主控台）";
        }
        final CurrencyType splinter = splinter();
        if (splinter == null) {
            return "currencies.yml 沒有 " + settings.splinter();
        }
        final int have = service.countCurrency(player, splinter);
        if (have < settings.cost()) {
            return settings.name() + "要 " + settings.cost() + " 個" + splinter.name() + "（你有 " + have + " 個；地圖裡的幻霧結束時會給）";
        }
        final MapConfig.MapDef arena = arena(service.random());
        if (arena == null) {
            return "maps.yml 沒有可以當場地的地圖（要有怪物與 Boss）";
        }
        final MapStats stats = service.eventBossStats(service.config().eventBoss(KIND), player, settings.levelBonus()); // 2026-11-04：災厄級固定等級
        final MapService.Special special = new MapService.Special(KIND, COLOR, this::onComplete);
        final MapInstance instance = service.openSpecialInstance(player, arena, stats, MapService.UBER_ROOMS, special);
        if (instance == null) {
            final String problem = service.specialProblem();
            return problem == null ? "地圖世界現在開不了新的實例，等一下再試" : problem;
        }
        service.takeCurrency(player, splinter, settings.cost(), tw.exilecore.economy.EconomySources.MAP_DEVICE);
        final UUID ownerId = player.getUniqueId();
        instance.paidRefund(() -> service.refundLater(ownerId, splinter, settings.cost())); // 蓋圖失敗、重開伺服器時還沒開打就退碎片
        runs.put(instance.id(), new Run(instance));
        player.sendMessage(plugin.text().prefixed("<#d0d0e8>" + ItemRenderer.escape(settings.name()) + "<gray>開好了（T" + stats.tier() + "・怪物等級 "
                + stats.monsterLevel() + "）：走進場地中間就開始，總共 " + settings.waves() + " 波，最後一波是 Boss。"
                + "<dark_gray>（傳送門在地圖裝置後面）"));
        plugin.getSLF4JLogger().info("{} 開了{}（T{}，Lv.{}，場地照 {}）", player.getName(), settings.name(), stats.tier(), stats.monsterLevel(), arena.id());
        return null;
    }

    /** 隨機挑一張一般地圖的怪物、主題、版型與 Boss 當場地（名字換成迷霧回廊）。 */
    private MapConfig.MapDef arena(final RandomGenerator random) {
        final List<MapConfig.MapDef> pool = new ArrayList<>();
        for (final MapConfig.MapDef map : service.config().maps()) {
            if (!map.isFinal() && map.boss() != null && !map.monsters().isEmpty()) {
                pool.add(map);
            }
        }
        if (pool.isEmpty()) {
            return null;
        }
        final MapConfig.MapDef from = pool.get(random.nextInt(pool.size()));
        // 2026-11-04 照站長「迷霧也要有 Boss」：最後一波是迷霧之主（maps.yml 的 event_bosses.fog_trial）；沒寫才借那張地圖的 Boss
        final MapConfig.EventBoss own = service.config().eventBoss(KIND);
        // base、ring 不抄（回響、劫盜也是 null、0：不要被當成那張地圖來掉地圖、算星圖）
        return new MapConfig.MapDef(KIND, settings().name(), null, 0, from.level(), from.theme(), 0, 0, List.of(), false,
                from.monsters(), own != null ? own.boss() : from.boss(), "霧裡的每一間房都像去過，又都不太一樣。", false, from.layout());
    }

    // ---- 給別的地方問 ----

    /** 這個實例是不是迷霧回廊（路上不刷怪、Boss 等最後一波）。 */
    static boolean is(final MapInstance instance) {
        return instance.special() != null && KIND.equals(instance.special().kind());
    }

    /** Boss 還不能出來（還沒到最後一波）。 */
    boolean holdsBoss(final MapInstance instance) {
        if (!is(instance)) {
            return false;
        }
        final Run run = runs.get(instance.id());
        return run == null || run.phase != Phase.BOSS;
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
        final MapEventConfig.FogTrialSettings settings = settings();
        final List<Player> players = players(run.instance);
        run.alive.removeIf(monster -> !monster.isValid() || monster.isDead());
        fog(players);
        switch (run.phase) {
            case WAITING -> {
                final Location center = center(run.instance);
                for (final Player player : players) {
                    if (player.getLocation().distanceSquared(center) <= ARENA_RADIUS * ARENA_RADIUS) {
                        startBreak(run, now, FIRST_DELAY);
                        break;
                    }
                }
            }
            case BREAK -> {
                if (players.isEmpty()) {
                    run.breakUntil = Math.max(run.breakUntil, now + 20); // 沒人在裡面：倒數停住
                } else if (now >= run.breakUntil) {
                    startWave(run, now, players.size());
                }
            }
            case FIGHT -> tickFight(run, now, players);
            case BOSS, DONE -> {
            }
        }
        updateBar(run, players, now);
        // 霧：怪物照這一波的難度變硬（MapSpawner 讀 instance.delirium）
        run.instance.setDelirium(run.phase == Phase.FIGHT || run.phase == Phase.BOSS ? settings.difficulty(Math.max(1, run.wave)) : 0);
    }

    private void startBreak(final Run run, final long now, final double seconds) {
        run.phase = Phase.BREAK;
        run.breakUntil = now + Math.round(seconds * 20);
        final int next = run.wave + 1;
        final MapEventConfig.FogTrialSettings settings = settings();
        final String what = settings.bossWave(next) ? "<#ff5c6a>Boss 波" : settings.eliteWaves().contains(next) ? "<#ffb347>菁英波" : "<white>第 " + next + " 波";
        for (final Player player : players(run.instance)) {
            player.showTitle(Title.title(plugin.text().parse(what),
                    plugin.text().parse("<gray>" + (seconds <= 0 ? "馬上開始" : Math.round(seconds) + " 秒後開始") + "（" + next + "／" + settings.waves() + "）"), // 2026-11-04：休息 0 秒時寫「馬上開始」
                    Title.Times.times(Duration.ofMillis(200), Duration.ofMillis(1800), Duration.ofMillis(400))));
        }
    }

    private void startWave(final Run run, final long now, final int playerCount) {
        final MapEventConfig.FogTrialSettings settings = settings();
        run.wave++;
        run.fightStart = now;
        run.nextSpawn = now;
        run.spawned = 0;
        run.eliteSpawned = false;
        run.target = settings.count(run.wave, playerCount);
        if (settings.bossWave(run.wave)) {
            run.phase = Phase.BOSS; // MapBosses 看到 holdsBoss 變 false 就把 Boss 叫出來（有人在場地附近時）
            for (final Player player : players(run.instance)) {
                player.playSound(player.getLocation(), Sound.ENTITY_WITHER_SPAWN, 0.6f, 0.7f);
            }
            broadcast(run.instance, "<#ff5c6a>最後一波：" + ItemRenderer.escape(run.instance.bossDef().name()) + "從霧裡走出來了");
            return;
        }
        run.phase = Phase.FIGHT;
        for (final Player player : players(run.instance)) {
            player.playSound(player.getLocation(), Sound.ENTITY_ELDER_GUARDIAN_CURSE, 0.4f, 1.6f);
        }
    }

    private void tickFight(final Run run, final long now, final List<Player> players) {
        final MapEventConfig.FogTrialSettings settings = settings();
        final RandomGenerator random = service.random();
        if (players.isEmpty()) {
            run.fightStart += 10; // 沒人在裡面：這一波的計時停住、不刷怪
            return;
        }
        if (!run.eliteSpawned && settings.eliteWaves().contains(run.wave)) {
            run.eliteSpawned = true;
            final Player near = players.get(random.nextInt(players.size()));
            final LivingEntity elite = spawnOne(run, near, MonsterRarity.UNIQUE, random);
            if (elite != null) {
                plugin.monsters().data(elite).ifPresent(data -> plugin.monsters().decorateData(elite, new MonsterData(data.level(), data.rarity(),
                        data.mods(), "霧之菁英・" + data.name(), data.lifeMultiplier() * settings.eliteLife(), data.damageMultiplier(), false)));
                broadcast(run.instance, "<#ffb347>霧之菁英出現了");
            }
        }
        if (run.spawned < run.target && now >= run.nextSpawn) {
            run.nextSpawn = now + Math.round(settings.spawnSeconds() * 20);
            for (final Player player : players) {
                final int group = settings.groupMin() + (settings.groupMax() > settings.groupMin() ? random.nextInt(settings.groupMax() - settings.groupMin() + 1) : 0);
                for (int i = 0; i < group && run.spawned < run.target; i++) {
                    final double roll = random.nextDouble();
                    final MonsterRarity rarity = roll < settings.rareChance(run.wave) ? MonsterRarity.RARE
                            : roll < settings.rareChance(run.wave) + settings.magicChance(run.wave) ? MonsterRarity.MAGIC : MonsterRarity.NORMAL;
                    if (spawnOne(run, player, rarity, random) != null) {
                        run.spawned++;
                    } else {
                        run.spawned++; // 刷不出來（附近沒有地板）也算，免得卡在這一波
                    }
                }
            }
        }
        final boolean timeUp = now - run.fightStart > Math.round(settings.waveSeconds() * 20);
        if (run.spawned >= run.target && (run.alive.isEmpty() || timeUp)) {
            if (timeUp) {
                for (final LivingEntity monster : run.alive) {
                    monster.remove(); // 時間到：剩下的怪散掉（不掉東西）
                }
                run.alive.clear();
            }
            clearWave(run, now, timeUp);
        }
    }

    /** 在玩家附近 6–10 格刷一隻（算這一波的怪）。 */
    private LivingEntity spawnOne(final Run run, final Player player, final MonsterRarity rarity, final RandomGenerator random) {
        final MapInstance instance = run.instance;
        final ZoneConfig.SpawnEntry entry = service.spawner().chooseMonster(instance.map().monsters(), random);
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
        final List<String> mods = rarity == MonsterRarity.NORMAL ? List.of() : plugin.monsters().rollMods(rarity, instance.level());
        final LivingEntity monster = service.spawner().spawn(instance, entry, at, rarity, mods);
        if (monster == null) {
            return null;
        }
        run.alive.add(monster);
        tw.exilecore.skill.Fx.spawn(monster.getWorld(), Particle.WHITE_ASH, monster.getLocation().add(0, 1, 0), 25, 0.4, 0.6, 0.4, 0.03);
        final AttributeInstance follow = monster.getAttribute(Attribute.FOLLOW_RANGE);
        if (follow != null && follow.getBaseValue() < 40) {
            follow.setBaseValue(40);
        }
        if (monster instanceof Mob mob) {
            mob.setTarget(player);
        }
        return monster;
    }

    /** 這一波過關：每個人各拿獎勵，接著下一波（下一波是 Boss 也一樣先休息）。 */
    private void clearWave(final Run run, final long now, final boolean timeUp) {
        final MapEventConfig.FogTrialSettings settings = settings();
        for (final Player player : players(run.instance)) {
            final int got = reward(run, player, settings.rewards(run.wave));
            player.sendMessage(plugin.text().prefixed("<#d0d0e8>第 " + run.wave + "／" + settings.waves() + " 波" + (timeUp ? "時間到" : "過關")
                    + "<gray>：拿到 <white>" + got + "</white> 件"));
            player.playSound(player.getLocation(), Sound.ENTITY_PLAYER_LEVELUP, 0.6f, 1.3f);
        }
        startBreak(run, now, settings.breakSeconds());
    }

    /** 給一個人 shares 份獎勵（種類照權重；吃物品數量），回傳拿到幾件。 */
    private int reward(final Run run, final Player player, final int shares) {
        final MapEventConfig.FogTrialSettings settings = settings();
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

    /** Boss 倒下（MapService.complete → Special.onComplete）：最後一波的獎勵，收尾。 */
    private void onComplete(final MapInstance instance, final List<Player> players) {
        final Run run = runs.get(instance.id());
        if (run == null || run.phase == Phase.DONE) {
            return;
        }
        run.phase = Phase.DONE;
        instance.setDelirium(0);
        final MapEventConfig.FogTrialSettings settings = settings();
        for (final Player player : players) {
            final int got = reward(run, player, settings.rewards(settings.waves()));
            player.showTitle(Title.title(plugin.text().parse("<#d0d0e8>" + ItemRenderer.escape(settings.name()) + "通關"),
                    plugin.text().parse("<gray>" + settings.waves() + " 波全部打完：最後一波拿到 <white>" + got + "</white> 件"),
                    Title.Times.times(Duration.ofMillis(200), Duration.ofMillis(3000), Duration.ofMillis(600))));
            player.playSound(player.getLocation(), Sound.UI_TOAST_CHALLENGE_COMPLETE, 1.0f, 1.0f);
            if (plugin.events() != null) {
                plugin.events().onMapEvent(player, instance.level()); // 每月挑戰：算一個地圖事件
            }
        }
        hideBar(run);
        plugin.getSLF4JLogger().info("地圖 #{}：{}通關", instance.id(), settings.name());
    }

    // ---- 幻霧碎片（幻霧結束時給） ----

    /** 幻霧結束時給這個人碎片（吃物品數量）；回傳給了幾個。 */
    int giveSplinters(final Player player, final MapInstance instance) {
        final CurrencyType splinter = splinter();
        final int[] range = settings().splinterDrop();
        if (splinter == null || range[1] <= 0 || plugin.currencies() == null) {
            return 0;
        }
        final RandomGenerator random = service.random();
        final int base = range[0] + (range[1] > range[0] ? random.nextInt(range[1] - range[0] + 1) : 0);
        final tw.exilecore.loot.LootBonus.Bonus bonus = tw.exilecore.loot.LootBonus.of(plugin, player).plus(instance.stats().quantity() / 100.0, 0);
        final int count = bonus.amount(base, random);
        if (count <= 0) {
            return 0;
        }
        final List<ItemStack> items = new ArrayList<>();
        for (int left = count; left > 0; ) {
            final int n = Math.min(left, Math.max(1, splinter.stack()));
            items.add(plugin.currencies().create(splinter, n));
            left -= n;
        }
        MapRewards.give(plugin, player, items);
        return count;
    }

    // ---- 畫面 ----

    private void fog(final List<Player> players) {
        for (final Player player : players) {
            final Location at = player.getLocation();
            tw.exilecore.skill.Fx.spawn(at.getWorld(), Particle.ASH, at.getX(), at.getY() + 1, at.getZ(), 30, 5, 1.5, 5, 0.02);
            tw.exilecore.skill.Fx.spawn(at.getWorld(), Particle.WHITE_ASH, at.getX(), at.getY() + 1.2, at.getZ(), 8, 4, 1, 4, 0.01);
        }
    }

    private void updateBar(final Run run, final List<Player> players, final long now) {
        final MapEventConfig.FogTrialSettings settings = settings();
        if (run.bar == null) {
            run.bar = BossBar.bossBar(Component.empty(), 1f, BossBar.Color.WHITE, BossBar.Overlay.NOTCHED_10);
        }
        final String text;
        float progress;
        switch (run.phase) {
            case WAITING -> {
                text = "<#d0d0e8>" + ItemRenderer.escape(settings.name()) + "　<gray>走進場地中間開始（" + settings.waves() + " 波）";
                progress = 0;
            }
            case BREAK -> {
                final long left = Math.max(0, (run.breakUntil - now + 19) / 20);
                text = "<#d0d0e8>" + ItemRenderer.escape(settings.name()) + "　<gray>第 " + (run.wave + 1) + "／" + settings.waves() + " 波 <white>" + left + "</white> 秒後開始";
                progress = run.wave / (float) settings.waves();
            }
            case FIGHT -> {
                final int remaining = run.target - run.spawned + run.alive.size();
                final long left = Math.max(0, Math.round(settings.waveSeconds()) - (now - run.fightStart) / 20);
                text = "<#d0d0e8>" + ItemRenderer.escape(settings.name()) + "　<white>第 " + run.wave + "／" + settings.waves() + " 波"
                        + "　<gray>剩 <white>" + remaining + "</white> 隻　<gray>" + left + " 秒　怪物 <#ff5c6a>+" + Math.round(settings.difficulty(run.wave) * 100) + "%";
                progress = (run.wave - 1 + (run.target <= 0 ? 1 : 1 - remaining / (float) run.target)) / settings.waves();
            }
            case BOSS -> {
                text = "<#d0d0e8>" + ItemRenderer.escape(settings.name()) + "　<#ff5c6a>最後一波：" + ItemRenderer.escape(run.instance.bossDef().name());
                progress = (settings.waves() - 1) / (float) settings.waves();
            }
            default -> {
                text = "<#d0d0e8>" + ItemRenderer.escape(settings.name()) + "　<green>通關";
                progress = 1;
            }
        }
        run.bar.progress(Math.max(0, Math.min(1, progress)));
        run.bar.name(plugin.text().parse(text));
        final Set<UUID> seen = new HashSet<>();
        for (final Player player : players) {
            seen.add(player.getUniqueId());
            if (run.watching.add(player.getUniqueId())) {
                player.showBossBar(run.bar);
            }
        }
        for (final UUID uuid : Set.copyOf(run.watching)) {
            if (!seen.contains(uuid)) {
                final Player player = plugin.getServer().getPlayer(uuid);
                if (player != null) {
                    player.hideBossBar(run.bar);
                }
                run.watching.remove(uuid);
            }
        }
    }

    private void hideBar(final Run run) {
        if (run.bar == null) {
            return;
        }
        for (final UUID uuid : run.watching) {
            final Player player = plugin.getServer().getPlayer(uuid);
            if (player != null) {
                player.hideBossBar(run.bar);
            }
        }
        run.watching.clear();
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
        hideBar(run);
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
