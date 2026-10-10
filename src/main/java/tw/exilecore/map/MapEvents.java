package tw.exilecore.map;

import net.kyori.adventure.text.Component;
import net.kyori.adventure.title.Title;
import org.bukkit.Color;
import org.bukkit.GameMode;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.NamespacedKey;
import org.bukkit.Particle;
import org.bukkit.Sound;
import org.bukkit.block.Block;
import org.bukkit.entity.Display;
import org.bukkit.entity.Entity;
import org.bukkit.entity.LivingEntity;
import org.bukkit.entity.Mob;
import org.bukkit.entity.Player;
import org.bukkit.entity.TextDisplay;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.block.Action;
import org.bukkit.event.entity.EntityDeathEvent;
import org.bukkit.event.player.PlayerInteractEvent;
import org.bukkit.inventory.EquipmentSlot;
import org.bukkit.inventory.ItemStack;
import org.bukkit.persistence.PersistentDataType;
import tw.exilecore.ExileCore;
import tw.exilecore.item.ItemBase;
import tw.exilecore.item.ItemData;
import tw.exilecore.item.ItemRenderer;
import tw.exilecore.item.Rarity;
import tw.exilecore.monster.DropTable;
import tw.exilecore.monster.MonsterData;
import tw.exilecore.monster.MonsterRarity;
import tw.exilecore.skill.GroundDecals;
import tw.exilecore.stats.StatKeys;
import tw.exilecore.stats.StatSheet;
import tw.exilecore.zone.ZoneConfig;

import java.time.Duration;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.Iterator;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;
import java.util.random.RandomGenerator;

/**
 * 地圖事件：每張地圖開出來時在幾個普通房間的中央放一個——神龕（碰到就給隊伍增益，旁邊有守衛）、寶箱（點開跳出一群怪，殺光才開）、
 * 裂隙（踩進去會一波一波湧出怪）、流寇（穿裝備、會招式的稀有怪）；另外 PoE2 的祭壇（{@link RitualEvents}）與幻霧
 * （{@link DeliriumEvents}）各照機率放。設定在 {@code data/map_events.yml}。
 * 每 10 tick 由 {@link MapService#tick} 呼叫一次 {@link #tick}；實例關閉時 {@link #clear}。
 */
final class MapEvents implements Listener {

    /** 寶箱跳出來的怪沒殺完時，最多等幾秒就開（怪跑掉、卡住）。 */
    private static final long BOX_TIMEOUT_TICKS = 90 * 20;
    /** 碰到神龕：離石柱幾格。 */
    private static final double TOUCH = 1.9;
    private static final Particle.DustOptions BREACH_DUST = new Particle.DustOptions(Color.fromRGB(0xC48BFF), 1.3f);
    private static final Particle.DustOptions SHRINE_DUST = new Particle.DustOptions(Color.fromRGB(0x9FE8C8), 1.0f);

    /** 一個事件。 */
    private final class Event {
        final MapInstance instance;
        final MapEventConfig.Kind kind;
        /** 站的位置（地板上那一格的中心）。 */
        final Location center;
        final MapEventConfig.Shrine shrine;
        final MapEventConfig.Box box;
        final String exileName;
        boolean revealed;
        boolean started;
        boolean done;
        TextDisplay label;
        final List<LivingEntity> monsters = new ArrayList<>();
        long startTick;
        long nextWaveTick;
        /** 流寇本人。 */
        LivingEntity exile;

        Event(final MapInstance instance, final MapEventConfig.Kind kind, final Location center, final MapEventConfig.Shrine shrine,
              final MapEventConfig.Box box, final String exileName) {
            this.instance = instance;
            this.kind = kind;
            this.center = center;
            this.shrine = shrine;
            this.box = box;
            this.exileName = exileName;
        }

        String title() {
            return switch (kind) {
                case SHRINE -> shrine.name();
                case STRONGBOX -> box.name();
                case BREACH -> "裂隙";
                case EXILE -> "流寇";
            };
        }
    }

    private final ExileCore plugin;
    private final MapService service;
    private final MapEventConfig config;
    /** PoE2 的機制：祭壇、幻霧（跟上面四種事件一起放、一起 tick、一起清）。 */
    private final RitualEvents rituals;
    private final DeliriumEvents delirium;
    private final Map<Integer, List<Event>> byInstance = new HashMap<>();
    /** 寶箱方塊 → 事件（右鍵打開）。 */
    private final Map<Long, Event> boxes = new HashMap<>();
    /** 流寇 → 事件（死亡時多掉東西）。 */
    private final Map<UUID, Event> exiles = new HashMap<>();
    /** 事件刷出來的怪的物品數量加成（裂隙）。 */
    private final NamespacedKey quantityKey;

    MapEvents(final ExileCore plugin, final MapService service, final MapEventConfig config) {
        this.plugin = plugin;
        this.service = service;
        this.config = config;
        this.quantityKey = new NamespacedKey(plugin, "map_event_quantity");
        this.rituals = new RitualEvents(plugin, service, config);
        this.delirium = new DeliriumEvents(plugin, service, config);
    }

    RitualEvents rituals() {
        return rituals;
    }

    DeliriumEvents delirium() {
        return delirium;
    }

    /** 事件怪的額外物品數量加成（小數；沒有是 0）。 */
    double quantityBonus(final LivingEntity entity) {
        final Double bonus = entity.getPersistentDataContainer().get(quantityKey, PersistentDataType.DOUBLE);
        return bonus == null ? 0 : bonus;
    }

    // ---- 放置 ----

    /** 地圖蓋好：挑幾個普通房間放事件（祭壇、幻霧先挑，剩下的房間給神龕、寶箱、裂隙、流寇）。 */
    void place(final MapInstance instance) {
        if (instance.special() != null || instance.isUber()) {
            return; // 回響、試煉、極境：只有 Boss
        }
        final RandomGenerator random = service.random();
        final List<MapLayout.Room> rooms = new ArrayList<>();
        for (final MapLayout.Room room : instance.layout().rooms()) {
            if (room.kind() == MapLayout.Kind.NORMAL || room.kind() == MapLayout.Kind.SIDE) {
                rooms.add(room);
            }
        }
        if (rooms.isEmpty()) {
            plugin.getSLF4JLogger().warn("地圖 #{}（{}）沒有可以放事件的房間", instance.id(), instance.label());
            return;
        }
        // 祭壇、幻霧各自出錯不會害神龕、寶箱這些放不出來
        try {
            rituals.place(instance, rooms);
        } catch (final RuntimeException e) {
            plugin.getSLF4JLogger().error("地圖 #{} 放祭壇出錯", instance.id(), e);
        }
        try {
            delirium.place(instance, rooms);
        } catch (final RuntimeException e) {
            plugin.getSLF4JLogger().error("地圖 #{} 放幻霧出錯", instance.id(), e);
        }
        if (config.totalWeight() <= 0 || config.maxPerMap() <= 0) {
            plugin.getSLF4JLogger().warn("地圖 #{}：map_events.yml 的 settings.weights 全是 0 或 per_map 是 0，不放事件", instance.id());
            return;
        }
        final int count = Math.min(rooms.size(), config.minPerMap() + (config.maxPerMap() > config.minPerMap()
                ? random.nextInt(config.maxPerMap() - config.minPerMap() + 1) : 0));
        // 星圖天賦：保證多幾個某種事件（先放保證的，剩下的房間再放隨機的）
        final AtlasBonus atlas = instance.atlas();
        final List<MapEventConfig.Kind> kinds = new ArrayList<>();
        for (final MapEventConfig.Kind kind : MapEventConfig.Kind.values()) {
            for (int i = 0; i < atlas.extraEvents(kind); i++) {
                kinds.add(kind);
            }
        }
        for (int i = 0; i < count; i++) {
            kinds.add(null); // 隨機
        }
        final List<Event> events = new ArrayList<>();
        final List<String> placed = new ArrayList<>();
        int noSpot = 0;
        for (int i = 0; i < kinds.size() && !rooms.isEmpty(); i++) {
            final MapLayout.Room room = rooms.remove(random.nextInt(rooms.size()));
            final MapLayout.Spot spot = spotIn(instance.layout(), room);
            if (spot == null) {
                noSpot++;
                if (kinds.get(i) != null) {
                    kinds.add(i + 1, kinds.get(i)); // 保證的事件換下一個房間再放
                }
                continue;
            }
            final MapEventConfig.Kind kind = kinds.get(i) != null ? kinds.get(i) : pickKind(random, atlas);
            if (kind == null) {
                continue; // 權重全是 0：隨機的不放，保證的照放
            }
            final Location center = new Location(instance.world(), instance.originX() + spot.x() + 0.5, MapLayout.FLOOR_Y + 1,
                    instance.originZ() + spot.z() + 0.5);
            try {
                final Event event = switch (kind) {
                    case SHRINE -> new Event(instance, kind, center, config.shrines().get(random.nextInt(config.shrines().size())), null, null);
                    case STRONGBOX -> new Event(instance, kind, center, null, pickBox(random), null);
                    case BREACH -> new Event(instance, kind, center, null, null, null);
                    case EXILE -> new Event(instance, kind, center, null, null,
                            config.exileNames().get(random.nextInt(config.exileNames().size())));
                };
                build(event);
                events.add(event);
                placed.add(event.title() + "(" + center.getBlockX() + "," + center.getBlockZ() + ")");
            } catch (final RuntimeException e) {
                plugin.getSLF4JLogger().error("地圖 #{} 放{}出錯", instance.id(), kind, e);
            }
        }
        if (!events.isEmpty()) {
            byInstance.put(instance.id(), events);
        }
        plugin.getSLF4JLogger().info("地圖 #{}（{}）：放了 {} 個事件 {}{}", instance.id(), instance.label(), events.size(), placed,
                noSpot > 0 ? "，" + noSpot + " 個房間找不到位置" : "");
    }

    /** 房間中央（或旁邊一點）一個是地板、不跟怪物群的刷怪點重疊的位置；沒有回傳 null。 */
    static MapLayout.Spot spotIn(final MapLayout layout, final MapLayout.Room room) {
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

    private MapEventConfig.Kind pickKind(final RandomGenerator random, final AtlasBonus atlas) {
        // 每月活動的主題可以讓某種事件更常出現（權重直接加上去）；星圖天賦再乘上 1 + 增加的
        final Map<MapEventConfig.Kind, Double> weights = new java.util.LinkedHashMap<>();
        double total = 0;
        for (final Map.Entry<MapEventConfig.Kind, Integer> entry : config.weights().entrySet()) {
            final int boost = plugin.events() == null ? 0 : plugin.events().eventBoost(entry.getKey().name().toLowerCase(java.util.Locale.ROOT));
            final double weight = (Math.max(0, entry.getValue()) + (entry.getValue() > 0 ? boost : 0)) * (1 + atlas.eventWeight(entry.getKey()));
            weights.put(entry.getKey(), weight);
            total += weight;
        }
        if (total <= 0) {
            return null;
        }
        double pick = random.nextDouble() * total;
        MapEventConfig.Kind last = null;
        for (final Map.Entry<MapEventConfig.Kind, Double> entry : weights.entrySet()) {
            if (entry.getValue() <= 0) {
                continue;
            }
            last = entry.getKey();
            pick -= entry.getValue();
            if (pick < 0) {
                return entry.getKey();
            }
        }
        return last;
    }

    /** 事件做完了：每個在場的人算一次每月挑戰的「地圖事件」。 */
    private void countEvent(final Event event, final List<Player> players) {
        if (plugin.events() == null) {
            return;
        }
        for (final Player player : players) {
            plugin.events().onMapEvent(player, event.instance.level());
        }
    }

    private MapEventConfig.Box pickBox(final RandomGenerator random) {
        int total = 0;
        for (final MapEventConfig.Box box : config.boxes()) {
            total += box.weight();
        }
        int pick = total <= 0 ? 0 : random.nextInt(total);
        for (final MapEventConfig.Box box : config.boxes()) {
            pick -= box.weight();
            if (pick < 0) {
                return box;
            }
        }
        return config.boxes().getFirst();
    }

    /** 蓋事件的方塊（神龕的石柱、寶箱）。 */
    private void build(final Event event) {
        final Block base = event.center.getBlock();
        switch (event.kind) {
            case SHRINE -> {
                base.setType(Material.POLISHED_DEEPSLATE);
                base.getRelative(0, 1, 0).setType(Material.SEA_LANTERN);
                base.getRelative(0, 2, 0).setType(Material.END_ROD);
            }
            case STRONGBOX -> {
                base.setType(Material.CHEST);
                boxes.put(key(base), event);
            }
            default -> {
            }
        }
    }

    /** 右鍵的方塊在原版的互動距離內（寶箱、祭壇要走到旁邊才能點）。 */
    static boolean withinReach(final Player player, final Block block) {
        final Location eye = player.getEyeLocation();
        return tw.exilecore.skill.SkillMath.withinVanillaReach(eye.getX(), eye.getY(), eye.getZ(), block.getX(), block.getY(), block.getZ());
    }

    static long key(final Block block) {
        return ((long) block.getX() & 0x3FFFFFFL) << 38 | ((long) block.getZ() & 0x3FFFFFFL) << 12 | (block.getY() & 0xFFFL);
    }

    // ---- 每 10 tick ----

    void tick() {
        final long now = plugin.getServer().getCurrentTick();
        rituals.tick(now);
        delirium.tick(now);
        if (byInstance.isEmpty()) {
            return;
        }
        final Iterator<Map.Entry<Integer, List<Event>>> iterator = byInstance.entrySet().iterator();
        while (iterator.hasNext()) {
            final Map.Entry<Integer, List<Event>> entry = iterator.next();
            final MapInstance instance = service.instance(entry.getKey());
            if (instance == null || instance.state() == MapInstance.State.CLOSING) {
                for (final Event event : entry.getValue()) {
                    dispose(event);
                }
                iterator.remove();
                continue;
            }
            if (instance.state() != MapInstance.State.OPEN) {
                continue;
            }
            final List<Player> players = players(instance);
            for (final Event event : entry.getValue()) {
                if (!event.done || event.kind == MapEventConfig.Kind.BREACH) {
                    try {
                        tickEvent(event, players, now);
                    } catch (final RuntimeException e) {
                        event.done = true;
                        plugin.getSLF4JLogger().error("地圖 #{} 的{}出錯", instance.id(), event.title(), e);
                    }
                }
            }
        }
    }

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

    private void tickEvent(final Event event, final List<Player> players, final long now) {
        final Location center = event.center;
        if (!center.getWorld().isChunkLoaded(center.getBlockX() >> 4, center.getBlockZ() >> 4)) {
            return;
        }
        if (!event.done && event.kind != MapEventConfig.Kind.EXILE && (event.label == null || !event.label.isValid())) {
            event.label = label(event);
        }
        double nearest = Double.MAX_VALUE;
        Player closest = null;
        for (final Player player : players) {
            final double distance = Math.hypot(player.getLocation().getX() - center.getX(), player.getLocation().getZ() - center.getZ());
            if (distance < nearest) {
                nearest = distance;
                closest = player;
            }
        }
        if (!event.revealed && nearest <= config.approach()) {
            event.revealed = true;
            reveal(event, now);
        }
        switch (event.kind) {
            case SHRINE -> {
                if (!event.started && closest != null && nearest <= TOUCH) {
                    useShrine(event, closest, players, now);
                } else if (!event.started) {
                    center.getWorld().spawnParticle(Particle.DUST, center.getX(), center.getY() + 1.5, center.getZ(), 6, 0.4, 0.6, 0.4, 0, SHRINE_DUST);
                }
            }
            case STRONGBOX -> {
                if (event.started && !event.done) {
                    event.monsters.removeIf(monster -> !monster.isValid() || monster.isDead());
                    if (event.monsters.isEmpty() || now - event.startTick >= BOX_TIMEOUT_TICKS) {
                        openBox(event, players, now);
                    } else if (event.label != null && event.label.isValid()) {
                        event.label.text(plugin.text().parse("<#f5b942>" + event.box.name() + "<newline><red>殺光跳出來的怪（剩 " + event.monsters.size() + " 隻）"));
                    }
                }
            }
            case BREACH -> tickBreach(event, players, nearest, now);
            case EXILE -> {
                if (event.started && !event.done && (event.exile == null || !event.exile.isValid() || event.exile.isDead())) {
                    event.done = true;
                    exiles.remove(event.exile == null ? null : event.exile.getUniqueId());
                }
            }
        }
    }

    /** 玩家走近：守衛、流寇刷出來。 */
    private void reveal(final Event event, final long now) {
        final RandomGenerator random = service.random();
        switch (event.kind) {
            case SHRINE -> {
                final int count = config.minGuards() + (config.maxGuards() > config.minGuards() ? random.nextInt(config.maxGuards() - config.minGuards() + 1) : 0);
                final ZoneConfig.SpawnEntry entry = service.spawner().chooseMonster(event.instance.map().monsters(), random);
                if (entry == null) {
                    return;
                }
                final List<String> mods = plugin.monsters().rollMods(MonsterRarity.MAGIC, event.instance.level());
                for (int i = 0; i < count; i++) {
                    final LivingEntity guard = service.spawner().spawn(event.instance, entry, around(event, 2.5, 4.5, random),
                            i == 0 ? MonsterRarity.MAGIC : MonsterRarity.NORMAL, i == 0 ? mods : List.of());
                    if (guard != null) {
                        event.monsters.add(guard);
                    }
                }
            }
            case EXILE -> spawnExile(event, now);
            default -> {
            }
        }
    }

    /** 事件中心附近、在地板上的位置。 */
    private Location around(final Event event, final double min, final double max, final RandomGenerator random) {
        final MapInstance instance = event.instance;
        for (int attempt = 0; attempt < 12; attempt++) {
            final double angle = random.nextDouble() * Math.PI * 2;
            final double radius = min + random.nextDouble() * (max - min);
            final double x = event.center.getX() + Math.cos(angle) * radius;
            final double z = event.center.getZ() + Math.sin(angle) * radius;
            if (instance.layout().isFloor((int) Math.floor(x) - instance.originX(), (int) Math.floor(z) - instance.originZ())) {
                return new Location(instance.world(), Math.floor(x) + 0.5, MapLayout.FLOOR_Y + 1, Math.floor(z) + 0.5, random.nextFloat() * 360f, 0f);
            }
        }
        return event.center.clone().add(1.5, 0, 0);
    }

    // ---- 神龕 ----

    private void useShrine(final Event event, final Player who, final List<Player> players, final long now) {
        event.started = true;
        event.done = true;
        countEvent(event, players);
        final MapEventConfig.Shrine shrine = event.shrine;
        final Block base = event.center.getBlock();
        base.getRelative(0, 1, 0).setType(Material.DEEPSLATE_TILES);
        base.getRelative(0, 2, 0).setType(Material.AIR);
        int blessed = 0;
        final RandomGenerator random = service.random();
        for (final Player player : players) {
            if (player.getLocation().distanceSquared(event.center) > config.shrineReach() * config.shrineReach()) {
                continue;
            }
            // 增益的持續時間：傳奇「長明燈」之類的「神龕的增益持續時間增加 x%」
            // 星圖天賦的「神龕增益的持續時間 +x%」跟傳奇的加在一起
            final double longer = Math.max(0, 1 + plugin.players().sheet(player).increased(StatKeys.SHRINE_DURATION, java.util.Set.of())
                    + event.instance.atlas().increased("atlas.shrine.duration"));
            final int seconds = (int) Math.round(config.shrineDuration() * longer);
            if (plugin.skills() != null) {
                plugin.skills().buff(player, "shrine." + shrine.id(), shrine.name(), seconds * 20, shrine.mods());
            }
            player.showTitle(Title.title(plugin.text().parse(shrine.color() + "✦ " + ItemRenderer.escape(shrine.name())),
                    plugin.text().parse("<gray>" + seconds + " 秒"), Title.Times.times(Duration.ofMillis(200), Duration.ofMillis(1800), Duration.ofMillis(500))));
            player.playSound(player.getLocation(), Sound.BLOCK_BEACON_ACTIVATE, 0.8f, 1.3f);
            blessed++;
            // 神龕的專屬傳奇（每個拿到增益的人各擲一次）
            final ItemData unique = eventUnique(MapEventConfig.Kind.SHRINE, event.instance, random);
            if (unique != null) {
                deliver(player, event.center, new MonsterData(event.instance.level(), MonsterRarity.MAGIC, List.of(), shrine.name()),
                        List.of(DropTable.Drop.equipment(unique)), 0);
            }
        }
        event.center.getWorld().spawnParticle(Particle.END_ROD, event.center.getX(), event.center.getY() + 1.5, event.center.getZ(), 40, 0.8, 1.0, 0.8, 0.05);
        if (event.label != null && event.label.isValid()) {
            event.label.text(plugin.text().parse("<dark_gray>" + ItemRenderer.escape(shrine.name()) + "（已使用）"));
        }
        plugin.getSLF4JLogger().info("地圖 #{}：{} 碰了{}（{} 人拿到增益）", event.instance.id(), who.getName(), shrine.name(), blessed);
    }

    // ---- 寶箱 ----

    /**
     * 右鍵寶箱：跳出一群怪；殺光才開。
     * 不能 ignoreCancelled：世界保護（LOWEST）把地圖世界裡所有方塊互動標成拒絕，Bukkit 把那個當成「已取消」，
     * 這裡用 LOWEST 自己看是不是寶箱；接走之後告訴技能模組這一下不施放（不然會順便放一發技能）。
     */
    @EventHandler(priority = EventPriority.LOWEST)
    public void onInteract(final PlayerInteractEvent event) {
        if (event.getAction() != Action.RIGHT_CLICK_BLOCK || event.getClickedBlock() == null || event.getClickedBlock().getType() != Material.CHEST
                || event.getHand() != EquipmentSlot.HAND) {
            return;
        }
        final Event box = boxes.get(key(event.getClickedBlock()));
        if (box == null || !withinReach(event.getPlayer(), event.getClickedBlock())) {
            return; // 太遠的（方塊互動距離拉長後客戶端會送很遠的方塊）當成一般的右鍵：施放技能
        }
        event.setCancelled(true);
        event.setUseItemInHand(org.bukkit.event.Event.Result.DENY);
        final Player player = event.getPlayer();
        if (plugin.skills() != null) {
            plugin.skills().consumeRightClick(player);
        }
        if (box.done) {
            player.sendActionBar(plugin.text().parse("<gray>這個寶箱已經開過了"));
            return;
        }
        if (box.started) {
            player.sendActionBar(plugin.text().parse("<red>先殺光跳出來的怪（剩 " + box.monsters.size() + " 隻）"));
            return;
        }
        if (box.instance.state() != MapInstance.State.OPEN) {
            return;
        }
        box.started = true;
        box.startTick = plugin.getServer().getCurrentTick();
        final RandomGenerator random = service.random();
        final ZoneConfig.SpawnEntry entry = service.spawner().chooseMonster(box.instance.map().monsters(), random);
        final int count = config.minAmbush() + (config.maxAmbush() > config.minAmbush() ? random.nextInt(config.maxAmbush() - config.minAmbush() + 1) : 0);
        if (entry != null) {
            // 跟地圖的怪物群一樣看魔法／稀有群的比例：稀有帶小怪、魔法整群、不然全普通
            final MapStats stats = box.instance.stats();
            final MapConfig.Settings settings = service.config().settings();
            final double normal = settings.normal().weight();
            final double magic = settings.magic().weight() * (1 + stats.magicPacks() / 100.0);
            final double rare = settings.rare().weight() * (1 + stats.rarePacks() / 100.0);
            final double roll = random.nextDouble() * (normal + magic + rare);
            final List<String> magicMods = plugin.monsters().rollMods(MonsterRarity.MAGIC, box.instance.level());
            for (int i = 0; i < count; i++) {
                final MonsterRarity rarity = roll >= normal + magic && i == 0 ? MonsterRarity.RARE
                        : roll >= normal && roll < normal + magic ? MonsterRarity.MAGIC : MonsterRarity.NORMAL;
                final LivingEntity monster = service.spawner().spawn(box.instance, entry, around(box, 2.0, 5.0, random), rarity,
                        rarity == MonsterRarity.MAGIC ? magicMods : rarity == MonsterRarity.RARE ? null : List.of());
                if (monster != null) {
                    box.monsters.add(monster);
                    if (monster instanceof Mob mob) {
                        mob.setTarget(player);
                    }
                }
            }
        }
        box.center.getWorld().spawnParticle(Particle.CLOUD, box.center.getX(), box.center.getY() + 1, box.center.getZ(), 30, 1.5, 0.5, 1.5, 0.02);
        box.center.getWorld().playSound(box.center, Sound.BLOCK_CHEST_OPEN, 1.0f, 0.6f);
        box.center.getWorld().playSound(box.center, Sound.ENTITY_EVOKER_PREPARE_SUMMON, 1.0f, 0.8f);
        for (final Player near : players(box.instance)) {
            if (near.getLocation().distanceSquared(box.center) <= config.boxRadius() * config.boxRadius()) {
                near.showTitle(Title.title(Component.empty(), plugin.text().parse("<#f5b942>" + ItemRenderer.escape(box.box.name())
                        + "<red>：怪物跳出來了！殺光才會開"), Title.Times.times(Duration.ofMillis(100), Duration.ofMillis(2000), Duration.ofMillis(500))));
            }
        }
        if (box.monsters.isEmpty()) {
            openBox(box, players(box.instance), box.startTick);
        }
    }

    /** 跳出來的怪殺光了：附近的每個人各拿一份。 */
    private void openBox(final Event event, final List<Player> players, final long now) {
        event.done = true;
        countEvent(event, players);
        final MapEventConfig.Box box = event.box;
        final MapInstance instance = event.instance;
        final RandomGenerator random = service.random();
        final DropTable drops = plugin.monsterModule().drops();
        final int level = instance.level();
        final MonsterData data = new MonsterData(level, MonsterRarity.RARE, List.of(), box.name());
        int takers = 0;
        for (final Player player : players) {
            if (player.getLocation().distanceSquared(event.center) > config.boxRadius() * config.boxRadius()) {
                continue;
            }
            final StatSheet sheet = plugin.players().sheet(player);
            final double quantity = box.quantityBonus() + instance.stats().quantity() / 100.0 + sheet.increased(StatKeys.DROP_QUANTITY, java.util.Set.of())
                    + instance.atlas().increased("atlas.strongbox.quantity"); // 星圖天賦：寶箱的物品數量
            final double rarity = box.rarityBonus() + instance.stats().rarity() / 100.0 + sheet.increased(StatKeys.DROP_RARITY, java.util.Set.of());
            final List<DropTable.Drop> rolled = new ArrayList<>();
            for (int i = 0; i < box.rolls(); i++) {
                // 全服掉落加成（管理網頁）：數量與稀有度加在物品上；金幣倍數在 rollGold 裡乘
                rolled.addAll(drops.roll(level, MonsterRarity.RARE, quantity + drops.serverQuantity(), rarity + drops.serverRarity(),
                        plugin.items().generator(), plugin.currencies().repository(), random, plugin.characters().lootBias(player)));
            }
            final int currencies = box.minCurrency() + (box.maxCurrency() > box.minCurrency() ? random.nextInt(box.maxCurrency() - box.minCurrency() + 1) : 0);
            for (int i = 0; i < currencies; i++) {
                final tw.exilecore.currency.CurrencyType currency = drops.rollCurrency(level, plugin.currencies().repository(), random,
                        rarity + drops.serverRarity());
                if (currency != null) {
                    rolled.add(DropTable.Drop.currency(currency));
                }
            }
            final int maps = box.minMaps() + (box.maxMaps() > box.minMaps() ? random.nextInt(box.maxMaps() - box.minMaps() + 1) : 0);
            for (int i = 0; i < maps; i++) {
                final ItemData map = MapDrops.roll(service.config(), plugin.items().generator(), Math.max(1, instance.stats().tier()), service.completed(player),
                        random, instance.atlas().tierUp());
                if (map != null) {
                    rolled.add(DropTable.Drop.equipment(map));
                }
            }
            // 寶箱的招牌：星魄（保證詞綴的改造通貨）
            final int essences = box.minEssences() + (box.maxEssences() > box.minEssences() ? random.nextInt(box.maxEssences() - box.minEssences() + 1) : 0);
            for (int i = 0; i < essences; i++) {
                final tw.exilecore.currency.CurrencyType essence = pickCurrency(tw.exilecore.currency.CurrencyOperation.ESSENCE, level, random);
                if (essence != null) {
                    rolled.add(DropTable.Drop.currency(essence));
                }
            }
            // 寶箱的專屬傳奇（每個有份的人各擲一次）
            final ItemData unique = eventUnique(MapEventConfig.Kind.STRONGBOX, instance, random);
            if (unique != null) {
                rolled.add(DropTable.Drop.equipment(unique));
            }
            final long gold = drops.rollGold(level, MonsterRarity.RARE, quantity, random) * Math.max(1, box.rolls());
            deliver(player, event.center, data, rolled, gold);
            takers++;
        }
        event.center.getWorld().spawnParticle(Particle.TOTEM_OF_UNDYING, event.center.getX(), event.center.getY() + 1, event.center.getZ(), 40, 0.6, 0.6, 0.6, 0.15);
        event.center.getWorld().playSound(event.center, Sound.BLOCK_ENDER_CHEST_OPEN, 1.0f, 1.2f);
        if (event.label != null && event.label.isValid()) {
            event.label.text(plugin.text().parse("<dark_gray>" + ItemRenderer.escape(box.name()) + "（已開）"));
        }
        plugin.getSLF4JLogger().info("地圖 #{}：{} 開了，{} 人拿到東西", instance.id(), box.name(), takers);
    }

    /** 把一份掉落交給玩家（撿取、過濾、金幣照殺怪的規則）；撿取模組沒啟用就掉在地上。 */
    private void deliver(final Player player, final Location at, final MonsterData data, final List<DropTable.Drop> rolled, final long gold) {
        MapRewards.deliver(plugin, player, at, data, rolled, gold);
    }

    // ---- 裂隙 ----

    private void tickBreach(final Event event, final List<Player> players, final double nearest, final long now) {
        final Location center = event.center;
        if (!event.started) {
            if (nearest <= config.breachStart() - 1) {
                event.started = true;
                event.startTick = now;
                event.nextWaveTick = now;
                center.getWorld().playSound(center, Sound.BLOCK_END_PORTAL_SPAWN, 1.0f, 0.7f);
                for (final Player player : players) {
                    if (player.getLocation().distanceSquared(center) <= 30 * 30) {
                        player.showTitle(Title.title(Component.empty(), plugin.text().parse("<#c48bff>裂隙打開了——怪會從邊緣湧出 " + Math.round(breachDuration(event)) + " 秒"),
                                Title.Times.times(Duration.ofMillis(100), Duration.ofMillis(2000), Duration.ofMillis(500))));
                    }
                }
            } else {
                ring(center, config.breachStart(), 16);
                center.getWorld().spawnParticle(Particle.REVERSE_PORTAL, center.getX(), center.getY() + 0.5, center.getZ(), 8, 0.6, 0.4, 0.6, 0.02);
            }
            return;
        }
        final double elapsed = (now - event.startTick) / 20.0;
        final double duration = breachDuration(event);
        if (elapsed > duration) {
            if (!event.done) {
                event.done = true;
                countEvent(event, players);
                if (plugin.skills() != null) {
                    plugin.skills().decals().remove(decalId(center));
                }
                if (event.label != null && event.label.isValid()) {
                    event.label.remove();
                    event.label = null;
                }
                center.getWorld().playSound(center, Sound.BLOCK_RESPAWN_ANCHOR_DEPLETE, 1.0f, 0.6f);
                // 裂隙關上：附近（30 格內）的人各擲一次裂隙的專屬傳奇
                final RandomGenerator random = service.random();
                for (final Player player : players) {
                    if (player.getLocation().distanceSquared(center) > 30 * 30) {
                        continue;
                    }
                    final ItemData unique = eventUnique(MapEventConfig.Kind.BREACH, event.instance, random);
                    if (unique != null) {
                        deliver(player, center, new MonsterData(event.instance.level(), MonsterRarity.MAGIC, List.of(), "裂隙"),
                                List.of(DropTable.Drop.equipment(unique)), 0);
                    }
                }
            }
            return;
        }
        final double progress = Math.min(1, elapsed / duration);
        final double radius = config.breachStart() + (config.breachEnd() - config.breachStart()) * progress;
        ring(center, radius, (int) Math.max(16, radius * 4));
        if (event.label != null && event.label.isValid()) {
            event.label.text(plugin.text().parse("<#c48bff>裂隙<newline><gray>還有 " + Math.round(duration - elapsed) + " 秒"));
        }
        if (now >= event.nextWaveTick) {
            event.nextWaveTick = now + Math.round(config.waveSeconds() * 20);
            final RandomGenerator random = service.random();
            final ZoneConfig.SpawnEntry entry = service.spawner().chooseMonster(event.instance.map().monsters(), random);
            if (entry == null) {
                return;
            }
            final int count = config.minWave() + (config.maxWave() > config.minWave() ? random.nextInt(config.maxWave() - config.minWave() + 1) : 0);
            final MapStats stats = event.instance.stats();
            final boolean magicWave = random.nextDouble() < 0.25 * (1 + stats.magicPacks() / 100.0);
            final List<String> mods = magicWave ? plugin.monsters().rollMods(MonsterRarity.MAGIC, event.instance.level()) : List.of();
            for (int i = 0; i < count; i++) {
                final LivingEntity monster = service.spawner().spawn(event.instance, entry, around(event, Math.max(1.5, radius - 1.5), radius + 0.5, random),
                        magicWave ? MonsterRarity.MAGIC : MonsterRarity.NORMAL, mods);
                if (monster != null) {
                    monster.getPersistentDataContainer().set(quantityKey, PersistentDataType.DOUBLE, config.breachQuantity());
                    event.monsters.add(monster);
                    monster.getWorld().spawnParticle(Particle.REVERSE_PORTAL, monster.getLocation().add(0, 1, 0), 12, 0.3, 0.5, 0.3, 0.05);
                }
            }
            center.getWorld().playSound(center, Sound.ENTITY_ENDERMAN_TELEPORT, 0.8f, 0.6f);
        }
    }

    /** 這個裂隙開幾秒（星圖天賦可以拉長，怪多幾波）。 */
    private double breachDuration(final Event event) {
        return config.breachDuration() * (1 + event.instance.atlas().increased("atlas.breach.duration"));
    }

    /** 地上一圈紫色的點（套了資源包的人看平面的圈，粒子只送給沒套的人）。 */
    private void ring(final Location center, final double radius, final int points) {
        final GroundDecals decals = plugin.skills() == null ? null : plugin.skills().decals();
        final List<Player> viewers;
        if (decals != null) {
            decals.ring(decalId(center), center, radius, 0xC48BFF);
            viewers = decals.viewers(center);
            if (viewers.isEmpty()) {
                return;
            }
        } else {
            viewers = center.getWorld().getPlayers();
        }
        for (int i = 0; i < points; i++) {
            final double angle = Math.PI * 2 * i / points;
            GroundDecals.particle(viewers, Particle.DUST, new Location(center.getWorld(), center.getX() + Math.cos(angle) * radius, center.getY() + 0.2,
                    center.getZ() + Math.sin(angle) * radius), 1, 0, 0, 0, 0, BREACH_DUST);
        }
    }

    private static String decalId(final Location center) {
        return "breach:" + center.getWorld().getName() + ":" + center.getBlockX() + ":" + center.getBlockY() + ":" + center.getBlockZ();
    }

    // ---- 流寇 ----

    private void spawnExile(final Event event, final long now) {
        event.started = true;
        final MapInstance instance = event.instance;
        final RandomGenerator random = service.random();
        final LivingEntity exile = service.spawner().spawnVanilla(random.nextBoolean() ? "ZOMBIE" : "HUSK", event.center.clone().add(0, 0, 0),
                created -> service.tag(created, instance, false));
        if (exile == null) {
            event.done = true;
            return;
        }
        final List<String> mods = new ArrayList<>();
        for (final String id : config.exileMods()) {
            if (plugin.monsters().config().mod(id) != null) {
                mods.add(id);
            }
        }
        final MonsterData data = new MonsterData(instance.level(), MonsterRarity.RARE, mods, "流寇·" + event.exileName,
                config.exileLife(), config.exileDamage(), false);
        plugin.monsters().decorateData(exile, data);
        MapSpawner.prepare(exile);
        if (exile instanceof Mob mob && mob.getEquipment() != null) {
            // 穿一套看得出等級的裝備（外觀；掉的是另外生的稀有裝備）
            final boolean high = instance.level() >= 75;
            mob.getEquipment().setHelmet(new ItemStack(high ? Material.NETHERITE_HELMET : Material.DIAMOND_HELMET));
            mob.getEquipment().setChestplate(new ItemStack(high ? Material.NETHERITE_CHESTPLATE : Material.DIAMOND_CHESTPLATE));
            mob.getEquipment().setLeggings(new ItemStack(high ? Material.NETHERITE_LEGGINGS : Material.DIAMOND_LEGGINGS));
            mob.getEquipment().setBoots(new ItemStack(high ? Material.NETHERITE_BOOTS : Material.DIAMOND_BOOTS));
            mob.getEquipment().setItemInMainHand(new ItemStack(high ? Material.NETHERITE_SWORD : Material.DIAMOND_SWORD));
            for (final EquipmentSlot slot : EquipmentSlot.values()) {
                if (slot.isArmor() || slot == EquipmentSlot.HAND) {
                    mob.getEquipment().setDropChance(slot, 0f);
                }
            }
        }
        event.exile = exile;
        event.monsters.add(exile);
        exiles.put(exile.getUniqueId(), event);
        exile.getWorld().playSound(exile.getLocation(), Sound.ENTITY_ZOMBIE_VILLAGER_CURE, 1.0f, 0.5f);
        exile.getWorld().spawnParticle(Particle.SOUL, exile.getLocation().add(0, 1, 0), 30, 0.6, 0.8, 0.6, 0.03);
        for (final Player player : players(instance)) {
            if (player.getLocation().distanceSquared(event.center) <= 32 * 32) {
                player.sendMessage(plugin.text().prefixed("<#ffff77>流寇·" + ItemRenderer.escape(event.exileName) + "<gray>：「這張圖是我的。」"));
            }
        }
    }

    /**
     * 這種事件的專屬傳奇（uniques.yml 的 sources 寫 shrine、strongbox、breach、exile）：照 map_events.yml 的 unique_chance 擲，
     * 沒中或沒有能掉的回傳 null。
     */
    private ItemData eventUnique(final MapEventConfig.Kind kind, final MapInstance instance, final RandomGenerator random) {
        // 星圖天賦：這種事件掉專屬傳奇的機率增加 x%
        if (random.nextDouble() >= config.uniqueChance(kind) * (1 + instance.atlas().eventUnique(kind))) {
            return null;
        }
        return MapDrops.unique(plugin.items().generator(), MapEventConfig.uniqueSource(kind), instance.level(), random);
    }

    /** 隨機一種這類操作的通貨（掉落等級夠的）；沒有回傳 null。 */
    private tw.exilecore.currency.CurrencyType pickCurrency(final tw.exilecore.currency.CurrencyOperation operation, final int level,
                                                          final RandomGenerator random) {
        return MapRewards.pickCurrency(plugin, operation, level, random);
    }

    /** 流寇死了：殺的人（與有份的隊友）各多拿幾件稀有裝備與星籤；裂隙的怪死了有機率掉催化劑。 */
    @EventHandler(priority = EventPriority.MONITOR)
    public void onDeath(final EntityDeathEvent event) {
        final LivingEntity entity = event.getEntity();
        final Player killer = entity.getKiller();
        final RandomGenerator random = service.random();
        final Event found = exiles.remove(entity.getUniqueId());
        if (found == null) {
            // 裂隙的怪：催化劑
            if (killer != null && entity.getPersistentDataContainer().has(quantityKey) && random.nextDouble() < config.catalystChance()) {
                final MapInstance instance = service.instanceOf(entity);
                final int level = instance == null ? plugin.monsters().data(entity).map(MonsterData::level).orElse(1) : instance.level();
                final tw.exilecore.currency.CurrencyType catalyst = pickCurrency(tw.exilecore.currency.CurrencyOperation.CATALYST, level, random);
                if (catalyst != null) {
                    deliver(killer, entity.getLocation(), new MonsterData(level, MonsterRarity.MAGIC, List.of(), "裂隙"),
                            List.of(DropTable.Drop.currency(catalyst)), 0);
                }
            }
            return;
        }
        found.done = true;
        final List<Player> sharers = plugin.monsterModule().credit().sharers(entity, killer);
        countEvent(found, sharers);
        if (sharers.isEmpty()) {
            return;
        }
        final int level = found.instance.level();
        final boolean anyBase = !plugin.items().repository().basesSoldAt(level).isEmpty(); // 流寇掉的是他身上那種能穿的裝備
        final MonsterData data = new MonsterData(level, MonsterRarity.RARE, List.of(), "流寇·" + found.exileName);
        for (final Player player : sharers) {
            final List<DropTable.Drop> rolled = new ArrayList<>();
            final int extra = found.instance.atlas().count("atlas.exile.drops"); // 星圖天賦：多掉幾件
            final tw.exilecore.item.LootBias bias = plugin.characters().lootBias(player); // 2026-10：照職業、照分類抽基底
            for (int i = 0; i < config.exileDrops() + extra && anyBase; i++) {
                final ItemBase base = plugin.items().repository().pickSold(level, random, bias);
                if (base == null) {
                    break;
                }
                rolled.add(DropTable.Drop.equipment(plugin.items().generator().generate(base, Rarity.RARE, level, random)));
            }
            // 流寇的招牌：星籤
            final int omens = config.minOmens() + (config.maxOmens() > config.minOmens() ? random.nextInt(config.maxOmens() - config.minOmens() + 1) : 0);
            for (int i = 0; i < omens; i++) {
                final tw.exilecore.currency.CurrencyType omen = pickCurrency(tw.exilecore.currency.CurrencyOperation.OMEN, level, random);
                if (omen != null) {
                    rolled.add(DropTable.Drop.currency(omen));
                }
            }
            // 流寇的專屬傳奇（每個有份的人各擲一次）
            final ItemData unique = eventUnique(MapEventConfig.Kind.EXILE, found.instance, random);
            if (unique != null) {
                rolled.add(DropTable.Drop.equipment(unique));
            }
            if (!rolled.isEmpty()) {
                deliver(player, entity.getLocation(), data, rolled, 0);
            }
        }
    }

    // ---- 標籤與清理 ----

    private TextDisplay label(final Event event) {
        final double height = switch (event.kind) {
            case SHRINE -> 3.6;
            case STRONGBOX -> 1.6;
            default -> 1.4;
        };
        final Location at = event.center.clone().add(0, height, 0);
        final TextDisplay display = at.getWorld().spawn(at, TextDisplay.class, created -> {
            created.setBillboard(Display.Billboard.CENTER);
            created.setPersistent(false);
            created.setSeeThrough(false);
            created.setShadowed(true);
        });
        display.text(plugin.text().parse(switch (event.kind) {
            case SHRINE -> event.shrine.color() + "✦ " + ItemRenderer.escape(event.shrine.name()) + "<newline><gray>碰到它：附近的人一起拿 "
                    + Math.round(config.shrineDuration() * (1 + event.instance.atlas().increased("atlas.shrine.duration"))) + " 秒增益";
            case STRONGBOX -> "<#f5b942>" + ItemRenderer.escape(event.box.name()) + "<newline><gray>右鍵打開（會跳出一群怪，殺光才開）";
            case BREACH -> "<#c48bff>裂隙<newline><gray>踩進去開啟：怪會一波一波湧出，掉的東西多 " + Math.round(config.breachQuantity() * 100) + "%";
            case EXILE -> "";
        }));
        return display;
    }

    private void dispose(final Event event) {
        if (event.label != null && event.label.isValid()) {
            event.label.remove();
        }
        event.label = null;
        for (final LivingEntity monster : event.monsters) {
            if (monster.isValid()) {
                monster.remove();
            }
        }
        event.monsters.clear();
        if (event.kind == MapEventConfig.Kind.STRONGBOX) {
            boxes.values().removeIf(other -> other == event);
        }
        if (event.exile != null) {
            exiles.remove(event.exile.getUniqueId());
        }
    }

    /** 實例關閉：標籤、刷出來的怪都收掉（方塊由清格子的人清）。 */
    void clear(final MapInstance instance) {
        rituals.clear(instance);
        delirium.clear(instance);
        final List<Event> events = byInstance.remove(instance.id());
        if (events != null) {
            for (final Event event : events) {
                dispose(event);
            }
        }
    }

    void clearAll() {
        rituals.clearAll();
        delirium.clearAll();
        for (final List<Event> events : byInstance.values()) {
            for (final Event event : events) {
                dispose(event);
            }
        }
        byInstance.clear();
        boxes.clear();
        exiles.clear();
    }

    /** 這張圖有幾個事件、幾個還沒處理（/ec map 的狀態）。 */
    String summary(final MapInstance instance) {
        final List<String> parts = new ArrayList<>();
        final List<Event> events = byInstance.get(instance.id());
        if (events != null) {
            for (final Event event : events) {
                parts.add(event.title() + (event.done ? "（完成）" : event.started ? "（進行中）" : ""));
            }
        }
        final String ritual = rituals.summary(instance);
        if (ritual != null) {
            parts.add(ritual);
        }
        final String fog = delirium.summary(instance);
        if (fog != null) {
            parts.add(fog);
        }
        if (parts.isEmpty()) {
            return "沒有事件";
        }
        return String.format(Locale.ROOT, "事件 %d 個：%s", parts.size(), String.join("、", parts));
    }

    /** 這個實體是不是某個事件刷出來的（守衛、裂隙怪、流寇、儀式的怪、幻霧怪）。 */
    boolean isEventMonster(final Entity entity) {
        return entity.getPersistentDataContainer().has(quantityKey) || exiles.containsKey(entity.getUniqueId())
                || rituals.isRitualMonster(entity) || delirium.isDeliriumMonster(entity);
    }
}
