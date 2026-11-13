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
import org.bukkit.block.BlockFace;
import org.bukkit.block.data.BlockData;
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
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;
import java.util.random.RandomGenerator;

/**
 * 地圖事件：每張地圖開出來時在幾個普通房間的中央放一個——神龕（右鍵石柱給附近的人增益，旁邊有守衛）、寶箱（點開跳出一群怪，殺光才開）、
 * 裂隙（踩進去會一波一波湧出怪）、流寇（穿裝備、會招式的稀有怪）、深淵裂縫（2026-10-18：地上裂開一條縫往前延伸、盡頭一隻小 Boss 與寶箱）、
 * 保險箱（2026-10-18：拿劫盜合約右鍵開鎖，一張圖最多一個）；另外 PoE2 的祭壇（{@link RitualEvents}）與幻霧
 * （{@link DeliriumEvents}）各照機率放。設定在 {@code data/map_events.yml}。
 * 每 10 tick 由 {@link MapService#tick} 呼叫一次 {@link #tick}；實例關閉時 {@link #clear}。
 */
final class MapEvents implements Listener {

    /** 寶箱跳出來的怪沒殺完時，最多等幾秒就開（怪跑掉、卡住）。 */
    private static final long BOX_TIMEOUT_TICKS = 90 * 20;
    private static final Particle.DustOptions BREACH_DUST = new Particle.DustOptions(Color.fromRGB(0xC48BFF), 1.3f);
    private static final Particle.DustOptions SHRINE_DUST = new Particle.DustOptions(Color.fromRGB(0x9FE8C8), 1.0f);
    /** 2026-10-18：深淵裂縫的顏色（深紫）、保險箱的顏色（鐵灰）。 */
    private static final Particle.DustOptions ABYSS_DUST = new Particle.DustOptions(Color.fromRGB(0x8A5CFF), 1.2f);
    private static final String ABYSS_COLOR = "<#8a5cff>";
    private static final String SAFE_COLOR = "<#c8d0d8>";

    /** 一個事件。 */
    /** 2026-11-12 照站長「裂痕以 PoE1 手感為主」：緊握之手（藏在裂隙範圍裡，圈長到才露出來，走到旁邊打開）。 */
    private static final class BreachHand {
        final Location at;
        final double distance;
        boolean revealed;
        boolean opened;
        TextDisplay label;

        BreachHand(final Location at, final double distance) {
            this.at = at;
            this.distance = distance;
        }
    }

    private final class Event {
        final MapInstance instance;
        final MapEventConfig.Kind kind;
        /** 站的位置（地板上那一格的中心）。 */
        final Location center;
        final MapEventConfig.Shrine shrine;
        final MapEventConfig.Box box;
        final String exileName;
        /** 星圖天賦、石板、單一信仰保證多出來的事件（2026-10-21：入場券機率只算 {@link #BONUS_TICKET}）。 */
        boolean bonus;
        boolean revealed;
        boolean started;
        boolean done;
        TextDisplay label;
        final List<LivingEntity> monsters = new ArrayList<>();
        long startTick;
        long nextWaveTick;
        /** 流寇本人。 */
        LivingEntity exile;
        // 2026-10-18：深淵裂縫
        /** 縫的中線（相對座標，照延伸的順序）。 */
        List<MapLayout.Spot> rift = List.of();
        /** 已經裂開到第幾格。 */
        int riftCarved;
        /** 延伸了幾段（每三段一隻魔法怪）。 */
        int riftSegments;
        /** 裂開的方塊原本的樣子（還原用）。 */
        final Map<Block, BlockData> riftBlocks = new LinkedHashMap<>();
        /** 沒人跟著是從哪一 tick 開始（0 = 有人跟著）。 */
        long stallSince;
        /** 盡頭的小 Boss。 */
        LivingEntity miniBoss;
        /** 深淵寶箱／保險箱的箱子。 */
        Block chest;
        /** 幾 tick 之後把縫的方塊還原（0 = 還沒排）。 */
        long restoreTick;
        // 2026-10-18：保險箱
        /** 開鎖的進度（tick）、暫停了多久（tick）、上一次 tick、湧了幾波。 */
        long progressTicks;
        long pausedTicks;
        long lastTick;
        int wavesSpawned;
        /** 2026-10-18：開鎖的人（出錯、重載時退合約用；退過就清掉）。 */
        UUID safeOpener;
        /** 這個結構的方塊（還原用）。 */
        final List<Block> structure = new ArrayList<>();
        // 2026-11-12 照站長「裂痕以 PoE1 手感為主」
        /** 這個裂隙的領主（怪的元素、圈的顏色）；null = 沒設定領主。 */
        MapEventConfig.BreachLord lord;
        /** 現在的半徑；收縮開始時的半徑。 */
        double breachRadius;
        double collapseFrom;
        /** 上一次算擴張的 tick；開始收縮的 tick（0 = 還在擴張）。 */
        long breachTick;
        long collapseTick;
        // 2026-11-12 照站長「進度條、穩定裂隙、維倫」（PoE2 0.5）
        /** 還剩幾秒（殺怪會加）；穩定進度（點）。 */
        double breachLeft;
        double breachPoints;
        /** 變成穩定裂隙了沒、從哪一 tick 開始；出到第幾波、這一波從哪一 tick 開始。 */
        boolean stabilised;
        long stableTick;
        int stableWave;
        long stableWaveTick;
        /** 穩定裂隙這一波的稀有怪＋維倫（還活著的）。 */
        final List<LivingEntity> stableMonsters = new ArrayList<>();
        boolean vruunSpawned;
        /** 2026-11-12：這次關上算不算打完穩定裂隙（給穩定的獎勵）。 */
        boolean stableCleared;
        /** 2026-11-12 照站長「裂痕要以殺死裂痕產生的怪物來拿到獎勵，在圈外打也行」：殺過（有份）這個裂隙的怪的人。 */
        final java.util.Set<UUID> breachKillers = new java.util.HashSet<>();
        /** 裂隙湧出來、還活著的怪 → 稀有度（死了推擴張用；首領算稀有）。 */
        final Map<LivingEntity, MonsterRarity> breachSpawned = new java.util.IdentityHashMap<>();
        /** 緊握之手。 */
        final List<BreachHand> hands = new ArrayList<>();

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
                case BREACH -> lord == null ? "裂隙" : lord.name() + "的裂隙"; // 2026-11-12
                case EXILE -> "流寇";
                case ABYSS -> "深淵裂縫"; // 2026-10-18
                case SAFE -> "保險箱";
            };
        }

        /** 縫尾端（世界座標、地板上）；還沒裂開就是中心。 */
        Location head() {
            if (riftCarved <= 0 || rift.isEmpty()) {
                return center;
            }
            final MapLayout.Spot spot = rift.get(Math.min(riftCarved, rift.size()) - 1);
            return new Location(instance.world(), instance.originX() + spot.x() + 0.5, MapLayout.FLOOR_Y + 1, instance.originZ() + spot.z() + 0.5);
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
    /** 2026-10-18：保險箱的方塊、深淵寶箱 → 事件（右鍵）。 */
    private final Map<Long, Event> blocks = new HashMap<>();
    /** 事件刷出來的怪的物品數量加成（裂隙）。 */
    private final NamespacedKey quantityKey;
    /** 2026-10-18：深淵裂縫的怪的物品數量加成（跟裂隙分開：不掉催化劑）。 */
    private final NamespacedKey abyssQuantityKey;
    /** 地圖變化的怪（流浪精英）的物品數量加成：跟上面分開，不會掉裂隙的催化劑。 */
    private final NamespacedKey twistQuantityKey;
    /** 地圖變化（2026-10）：房間主題、Boss 變體、岔路……。 */
    private final MapTwists twists;
    /** 2026-11-04 照站長「波次引擎＋15 層迷霧」：迷霧回廊。 */
    private final FogTrial fogTrial;
    /** 2026-11-04 照站長「先做絕境」：絕境。 */
    private final Ultimatum ultimatum;
    /** 2026-11-04 照站長「軍團」：軍團。 */
    private final Legion legion;
    /** 2026-11-07 照站長「地心礦坑簡化版」：星脈深坑。 */
    private final Delve delve;

    MapEvents(final ExileCore plugin, final MapService service, final MapEventConfig config) {
        this.plugin = plugin;
        this.service = service;
        this.config = config;
        this.quantityKey = new NamespacedKey(plugin, "map_event_quantity");
        this.abyssQuantityKey = new NamespacedKey(plugin, "map_abyss_quantity");
        this.rituals = new RitualEvents(plugin, service, config);
        this.delirium = new DeliriumEvents(plugin, service, config);
        this.twistQuantityKey = new NamespacedKey(plugin, "map_twist_quantity");
        this.twists = new MapTwists(plugin, service, config, this);
        this.fogTrial = new FogTrial(plugin, service, config);
        this.ultimatum = new Ultimatum(plugin, service, config);
        this.legion = new Legion(plugin, service, config);
        this.delve = new Delve(plugin, service, config);
    }

    Delve delve() {
        return delve;
    }

    FogTrial fogTrial() {
        return fogTrial;
    }

    Ultimatum ultimatum() {
        return ultimatum;
    }

    Legion legion() {
        return legion;
    }

    MapTwists twists() {
        return twists;
    }

    /** 地圖變化的怪多掉一點（小數，0.35 = +35%）。 */
    void markQuantity(final LivingEntity entity, final double bonus) {
        entity.getPersistentDataContainer().set(twistQuantityKey, PersistentDataType.DOUBLE, bonus);
    }

    RitualEvents rituals() {
        return rituals;
    }

    DeliriumEvents delirium() {
        return delirium;
    }

    /** 2026-10-30：裂隙、深淵裂縫湧出來的怪。 */
    boolean riftMonster(final LivingEntity entity) {
        return entity.getPersistentDataContainer().has(quantityKey, PersistentDataType.DOUBLE)
                || entity.getPersistentDataContainer().has(abyssQuantityKey, PersistentDataType.DOUBLE);
    }

    /** 事件怪的額外物品數量加成（小數；沒有是 0）。 */
    double quantityBonus(final LivingEntity entity) {
        final Double bonus = entity.getPersistentDataContainer().get(quantityKey, PersistentDataType.DOUBLE);
        final Double twist = entity.getPersistentDataContainer().get(twistQuantityKey, PersistentDataType.DOUBLE);
        final Double abyss = entity.getPersistentDataContainer().get(abyssQuantityKey, PersistentDataType.DOUBLE); // 2026-10-18
        return (bonus == null ? 0 : bonus) + (twist == null ? 0 : twist) + (abyss == null ? 0 : abyss);
    }

    // ---- 放置 ----

    /** 地圖蓋好：挑幾個普通房間放事件（祭壇、幻霧先挑，剩下的房間給神龕、寶箱、裂隙、流寇）。 */
    void place(final MapInstance instance) {
        if (instance.special() != null || instance.bossOnly()) {
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
        try {
            ultimatum.place(instance, rooms); // 2026-11-04：絕境
        } catch (final RuntimeException e) {
            plugin.getSLF4JLogger().error("地圖 #{} 放絕境出錯", instance.id(), e);
        }
        try {
            legion.place(instance, rooms); // 2026-11-04：軍團
        } catch (final RuntimeException e) {
            plugin.getSLF4JLogger().error("地圖 #{} 放軍團出錯", instance.id(), e);
        }
        if (config.totalWeight() <= 0 || config.maxPerMap() <= 0) {
            plugin.getSLF4JLogger().warn("地圖 #{}：map_events.yml 的 settings.weights 全是 0 或 per_map 是 0，不放事件", instance.id());
        } else {
            placeEvents(instance, rooms, random);
        }
        // 地圖變化：剩下沒有事件的房間（出錯不影響上面放好的）
        try {
            twists.place(instance, rooms);
        } catch (final RuntimeException e) {
            plugin.getSLF4JLogger().error("地圖 #{} 放地圖變化出錯", instance.id(), e);
        }
    }

    /** 神龕、寶箱、裂隙、流寇（用掉的房間從 rooms 拿掉）；照 twists.stack_chance 在同一個房間多疊一個不同的事件。 */
    private void placeEvents(final MapInstance instance, final List<MapLayout.Room> rooms, final RandomGenerator random) {
        final int count = Math.min(rooms.size(), config.minPerMap() + (config.maxPerMap() > config.minPerMap()
                ? random.nextInt(config.maxPerMap() - config.minPerMap() + 1) : 0));
        // 星圖天賦：保證多幾個某種事件（先放保證的，剩下的房間再放隨機的）
        final AtlasBonus atlas = instance.atlas();
        final List<MapEventConfig.Kind> kinds = new ArrayList<>();
        for (final MapEventConfig.Kind kind : MapEventConfig.Kind.values()) {
            if (kind == MapEventConfig.Kind.SAFE) {
                continue; // 2026-10-18：保險箱一張圖最多一個，照 safe_chance 另外放（下面）
            }
            if (faithBlocks(atlas, kind)) {
                continue; // 2026-10-21 修正：單一信仰：其他種類的保證事件（天賦、石板、隱藏格）也不放
            }
            for (int i = 0; i < atlas.extraEvents(kind); i++) {
                kinds.add(kind);
            }
        }
        final List<Boolean> bonus = new ArrayList<>(); // 跟 kinds 一一對應：是不是保證多出來的
        for (int i = 0; i < kinds.size(); i++) {
            bonus.add(true);
        }
        for (int i = 0; i < count; i++) {
            kinds.add(null); // 隨機
            bonus.add(false);
        }
        final List<Event> events = new ArrayList<>();
        final List<String> placed = new ArrayList<>();
        int noSpot = 0;
        for (int i = 0; i < kinds.size() && !rooms.isEmpty(); i++) {
            // 2026-10-21 單一信仰改：隨機的照原本的權重抽，抽到別種就不放（不是換成選的那種）
            final MapEventConfig.Kind rolled = kinds.get(i) != null ? kinds.get(i) : pickKind(random, atlas, false);
            if (kinds.get(i) == null && rolled != null && faithBlocks(atlas, rolled)) {
                continue;
            }
            final MapLayout.Room room = rooms.remove(random.nextInt(rooms.size()));
            final MapLayout.Spot spot = spotIn(instance.layout(), room);
            if (spot == null) {
                noSpot++;
                if (rolled != null) {
                    kinds.add(i + 1, rolled); // 換下一個房間再放（隨機的保留抽到的那種）
                    bonus.add(i + 1, bonus.get(i));
                }
                continue;
            }
            final MapEventConfig.Kind kind = rolled;
            if (kind == null) {
                continue; // 權重全是 0：隨機的不放，保證的照放
            }
            final Event event = create(instance, kind, spot, random);
            if (event == null) {
                continue;
            }
            event.bonus = bonus.get(i);
            events.add(event);
            placed.add(event.title() + "(" + event.center.getBlockX() + "," + event.center.getBlockZ() + ")");
            // 事件疊加：同一個房間再放一個不同種類的（離第一個至少 3 格）
            if (random.nextDouble() < config.twists().stackChance()) {
                final MapEventConfig.Kind second = pickOther(kind, random, atlas);
                final MapLayout.Spot other = second == null ? null : spotIn(instance.layout(), room, spot, 3);
                final Event stacked = other == null ? null : create(instance, second, other, random);
                if (stacked != null) {
                    events.add(stacked);
                    placed.add(stacked.title() + "(疊加)");
                }
            }
        }
        // 2026-10-18：保險箱：per_map 之外另外算，一張圖最多一個，放在剩下的房間
        if (!rooms.isEmpty() && !faithBlocks(atlas, MapEventConfig.Kind.SAFE) && random.nextDouble() < config.safe().chance()) {
            final MapLayout.Room room = rooms.remove(random.nextInt(rooms.size()));
            final MapLayout.Spot spot = spotIn(instance.layout(), room);
            final Event safe = spot == null ? null : create(instance, MapEventConfig.Kind.SAFE, spot, random);
            if (safe != null) {
                events.add(safe);
                placed.add(safe.title() + "(" + safe.center.getBlockX() + "," + safe.center.getBlockZ() + ")");
            }
        }
        if (!events.isEmpty()) {
            byInstance.computeIfAbsent(instance.id(), id -> new ArrayList<>()).addAll(events);
        }
        plugin.getSLF4JLogger().info("地圖 #{}（{}）：放了 {} 個事件 {}{}", instance.id(), instance.label(), events.size(), placed,
                noSpot > 0 ? "，" + noSpot + " 個房間找不到位置" : "");
    }

    /** 在這個房間放一個事件（地圖變化用：寶庫、隱藏房的寶箱）；放不下回傳 false。 */
    boolean placeIn(final MapInstance instance, final MapLayout.Room room, final MapEventConfig.Kind kind, final RandomGenerator random) {
        if (faithBlocks(instance.atlas(), kind)) {
            return false; // 2026-10-21：單一信仰：地圖變化的寶庫、隱藏房也不放別種事件
        }
        final MapLayout.Spot spot = spotIn(instance.layout(), room);
        final Event event = spot == null ? null : create(instance, kind, spot, random);
        if (event == null) {
            return false;
        }
        byInstance.computeIfAbsent(instance.id(), id -> new ArrayList<>()).add(event);
        return true;
    }

    /** 蓋一個事件；出錯回傳 null。 */
    private Event create(final MapInstance instance, final MapEventConfig.Kind kind, final MapLayout.Spot spot, final RandomGenerator random) {
        final Location center = new Location(instance.world(), instance.originX() + spot.x() + 0.5, MapLayout.FLOOR_Y + 1,
                instance.originZ() + spot.z() + 0.5);
        try {
            final Event event = switch (kind) {
                case SHRINE -> new Event(instance, kind, center, config.shrines().get(random.nextInt(config.shrines().size())), null, null);
                case STRONGBOX -> new Event(instance, kind, center, null, pickBox(random), null);
                case BREACH -> { // 2026-11-12 照站長「裂痕以 PoE1 手感為主」：放的時候抽一個領主
                    final Event breach = new Event(instance, kind, center, null, null, null);
                    breach.lord = config.breachFeel().pickLord(random);
                    yield breach;
                }
                case EXILE -> new Event(instance, kind, center, null, null,
                        config.exileNames().get(random.nextInt(config.exileNames().size())));
                case ABYSS, SAFE -> new Event(instance, kind, center, null, null, null); // 2026-10-18
            };
            build(event);
            return event;
        } catch (final RuntimeException e) {
            plugin.getSLF4JLogger().error("地圖 #{} 放{}出錯", instance.id(), kind, e);
            return null;
        }
    }

    /** 疊加用：抽一種跟 first 不一樣的事件（試幾次都一樣就不疊）。 */
    private MapEventConfig.Kind pickOther(final MapEventConfig.Kind first, final RandomGenerator random, final AtlasBonus atlas) {
        for (int attempt = 0; attempt < 6; attempt++) {
            final MapEventConfig.Kind kind = pickKind(random, atlas, false);
            if (kind != null && kind != first) {
                return faithBlocks(atlas, kind) ? null : kind; // 單一信仰：抽到別種就不疊
            }
        }
        return null;
    }

    /** 房間中央（或旁邊一點）一個是地板、不跟怪物群的刷怪點重疊的位置；沒有回傳 null。 */
    static MapLayout.Spot spotIn(final MapLayout layout, final MapLayout.Room room) {
        return spotIn(layout, room, null, 0);
    }

    /** 同上，但離 away 至少 distance 格（away 是 null 就不管）。 */
    static MapLayout.Spot spotIn(final MapLayout layout, final MapLayout.Room room, final MapLayout.Spot away, final int distance) {
        for (final int[] offset : MapLayout.EVENT_OFFSETS) {
            final int x = room.cx() + offset[0];
            final int z = room.cz() + offset[1];
            if (!layout.isFloor(x, z) || !room.contains(x, z)) {
                continue;
            }
            if (away != null && Math.max(Math.abs(away.x() - x), Math.abs(away.z() - z)) < distance) {
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

    /**
     * 起點房間裡的一個固定位置（相對起點房間中心 dx、dz）；不是地板、不在房間裡就回傳 null（2026-10-21：幻霧鏡、命運岔路、詛咒寶匣放在地圖開頭）。
     * 起點房間 13×13：玩家出生在中心往東 2 格、回營地的門在西邊 4 格，所以用南北兩側 z ±4。
     */
    static MapLayout.Spot startSpot(final MapLayout layout, final int dx, final int dz) {
        final MapLayout.Room start = layout.start();
        if (start == null) {
            return null;
        }
        final int x = start.cx() + dx;
        final int z = start.cz() + dz;
        return layout.isFloor(x, z) && start.contains(x, z) ? new MapLayout.Spot(x, z) : null;
    }

    /** 星圖核心「單一信仰」點了，而且這種不是選的那種（2026-10-21）。 */
    static boolean faithBlocks(final AtlasBonus atlas, final MapEventConfig.Kind kind) {
        return atlas.get("atlas.other_events_off") > 0 && atlas.get(AtlasBonus.faithKey(kind)) <= 0;
    }

    /** faith = false：不管單一信仰，照原本的權重抽（2026-10-21：抽到別種由呼叫的人決定不放，不換成選的那種）。 */
    private MapEventConfig.Kind pickKind(final RandomGenerator random, final AtlasBonus atlas, final boolean faith) {
        // 每月活動的主題可以讓某種事件更常出現（權重直接加上去）；星圖天賦再乘上 1 + 增加的
        final Map<MapEventConfig.Kind, Double> weights = new java.util.LinkedHashMap<>();
        double total = 0;
        for (final Map.Entry<MapEventConfig.Kind, Integer> entry : config.weights().entrySet()) {
            final int boost = plugin.events() == null ? 0 : plugin.events().eventBoost(entry.getKey().name().toLowerCase(java.util.Locale.ROOT));
            double weight = (Math.max(0, entry.getValue()) + (entry.getValue() > 0 ? boost : 0)) * (1 + atlas.eventWeight(entry.getKey()));
            if (faith && faithBlocks(atlas, entry.getKey())) {
                weight = 0; // 2026-10-21 星圖核心「單一信仰」：只有選的那種事件
            }
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
                // 2026-10-24：右鍵石柱才用（以前碰到就觸發，路過就誤觸）
                for (int dy = 0; dy <= 2; dy++) {
                    blocks.put(key(base.getRelative(0, dy, 0)), event);
                }
            }
            case STRONGBOX -> {
                base.setType(Material.CHEST);
                boxes.put(key(base), event);
            }
            case ABYSS -> carve(event, base.getRelative(0, -1, 0), Material.OBSIDIAN); // 2026-10-18：起點先裂一格（走近才延伸）
            case SAFE -> buildSafe(event, base); // 2026-10-18
            default -> {
            }
        }
    }

    /** 2026-10-18：保險箱的結構——後面一排鐵塊（兩層）、兩側鐵塊、中間一個箱子、前面一扇鐵門。 */
    private void buildSafe(final Event event, final Block base) {
        for (int dx = -1; dx <= 1; dx++) {
            place(event, base.getRelative(dx, 0, -1), Material.IRON_BLOCK);
            place(event, base.getRelative(dx, 1, -1), Material.IRON_BLOCK);
        }
        place(event, base.getRelative(-1, 0, 0), Material.IRON_BLOCK);
        place(event, base.getRelative(1, 0, 0), Material.IRON_BLOCK);
        place(event, base.getRelative(0, 1, 0), Material.IRON_BLOCK);
        place(event, base, Material.CHEST);
        if (base.getBlockData() instanceof org.bukkit.block.data.Directional chest) {
            chest.setFacing(BlockFace.SOUTH);
            base.setBlockData(chest, false);
        }
        event.chest = base;
        final Block lower = base.getRelative(0, 0, 1);
        final Block upper = base.getRelative(0, 1, 1);
        place(event, lower, Material.IRON_DOOR);
        place(event, upper, Material.IRON_DOOR);
        if (lower.getBlockData() instanceof org.bukkit.block.data.type.Door door) {
            door.setFacing(BlockFace.SOUTH);
            door.setHalf(org.bukkit.block.data.Bisected.Half.BOTTOM);
            lower.setBlockData(door, false);
        }
        if (upper.getBlockData() instanceof org.bukkit.block.data.type.Door door) {
            door.setFacing(BlockFace.SOUTH);
            door.setHalf(org.bukkit.block.data.Bisected.Half.TOP);
            upper.setBlockData(door, false);
        }
    }

    private void place(final Event event, final Block block, final Material material) {
        block.setType(material, false);
        event.structure.add(block);
        blocks.put(key(block), event);
    }

    /** 2026-10-18：縫裂開一格（記住原本的方塊，事件結束 linger 秒後還原）。 */
    private void carve(final Event event, final Block block, final Material material) {
        if (!event.riftBlocks.containsKey(block)) {
            event.riftBlocks.put(block, block.getBlockData());
        }
        block.setType(material, false);
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
        twists.tick(now);
        fogTrial.tick(now);
        ultimatum.tick(now);
        legion.tick(now);
        delve.tick(now);
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
                if (event.restoreTick > 0 && now >= event.restoreTick) {
                    restoreRift(event); // 2026-10-18：深淵裂縫結束 linger 秒後把縫還原
                }
                if (!event.done || event.kind == MapEventConfig.Kind.BREACH) {
                    try {
                        tickEvent(event, players, now);
                    } catch (final RuntimeException e) {
                        refundSafeContract(event); // 2026-10-18：保險箱開鎖中出錯：合約退回去
                        event.done = true;
                        if (event.kind == MapEventConfig.Kind.ABYSS && event.restoreTick <= 0) {
                            event.restoreTick = now + Math.round(config.abyss().linger() * 20); // 2026-10-18：出錯也要把縫還原
                        }
                        plugin.getSLF4JLogger().error("地圖 #{} 的{}出錯", instance.id(), event.title(), e);
                    }
                }
            }
        }
    }

    /**
     * 2026-11-02 照站長（模組 1.4 B 組）：地圖資訊框的事件清單（這張圖的事件照放的順序，再加祭壇、幻霧）。
     * 這次之前事件的狀態只寫在事件上方的字、血條、動作列。
     */
    List<tw.exilecore.clientmod.WorldHudProtocol.EventLine> hudLines(final MapInstance instance, final UUID viewer, final long now) {
        final List<tw.exilecore.clientmod.WorldHudProtocol.EventLine> lines = new ArrayList<>();
        for (final Event event : byInstance.getOrDefault(instance.id(), List.of())) {
            final tw.exilecore.clientmod.WorldHudProtocol.EventLine line = hudLine(event, now);
            if (line != null) {
                lines.add(line);
            }
        }
        final tw.exilecore.clientmod.WorldHudProtocol.EventLine ritual = rituals.hudLine(instance, viewer);
        if (ritual != null) {
            lines.add(ritual);
        }
        final tw.exilecore.clientmod.WorldHudProtocol.EventLine fog = delirium.hudLine(instance, viewer, now);
        if (fog != null) {
            lines.add(fog);
        }
        final tw.exilecore.clientmod.WorldHudProtocol.EventLine trial = ultimatum.hudLine(instance, now);
        if (trial != null) {
            lines.add(trial);
        }
        final tw.exilecore.clientmod.WorldHudProtocol.EventLine monolith = legion.hudLine(instance, now);
        if (monolith != null) {
            lines.add(monolith);
        }
        return lines;
    }

    private tw.exilecore.clientmod.WorldHudProtocol.EventLine hudLine(final Event event, final long now) {
        final int idle = tw.exilecore.clientmod.WorldHudProtocol.EVENT_IDLE;
        final int active = tw.exilecore.clientmod.WorldHudProtocol.EVENT_ACTIVE;
        final int done = tw.exilecore.clientmod.WorldHudProtocol.EVENT_DONE;
        final int failed = tw.exilecore.clientmod.WorldHudProtocol.EVENT_FAILED;
        return switch (event.kind) {
            case SHRINE -> line(event.shrine.name(), MapHudFeed.color(event.shrine.color(), 0x9FE8C8),
                    event.started || event.done ? "已使用" : "右鍵石柱", -1, event.started || event.done ? done : idle);
            case STRONGBOX -> event.done ? line(event.box.name(), 0xF5B942, "已開", -1, done)
                    : event.started ? line(event.box.name(), 0xF5B942, "殺光跳出來的怪（剩 " + event.monsters.size() + " 隻）", -1, active)
                    : line(event.box.name(), 0xF5B942, "點開寶箱", -1, idle);
            case BREACH -> {
                if (event.done) {
                    yield line("裂隙", 0xC48BFF, "已關閉", -1, done);
                }
                if (!event.started) {
                    yield line("裂隙", 0xC48BFF, "走進去打開", -1, idle);
                }
                // 2026-11-12 照站長「進度條，殺怪會延長；滿 100% 變成穩定裂隙」：進度條是穩定進度，後面寫剩幾秒；這次之前顯示擴張了幾成
                final int color = event.lord == null ? 0xC48BFF : event.lord.color();
                if (event.collapseTick > 0) {
                    yield line(event.title(), color, "收縮中（圈外的怪會被吞掉）", 100, active);
                }
                if (event.stabilised) {
                    final MapEventConfig.BreachFeel feel = config.breachFeel();
                    yield line("穩定裂隙", color, (event.vruunSpawned ? feel.vruunName() : "第 " + event.stableWave + "／" + feel.stableWaves() + " 波")
                            + "（剩 " + event.stableMonsters.size() + " 隻）", 100, active);
                }
                final double progress = breachProgress(event);
                yield line(event.title(), color, "穩定 " + Math.round(progress * 100) + "%・剩 " + Math.max(0, Math.round(event.breachLeft)) + " 秒（殺怪延長）",
                        percent(progress), active);
            }
            case EXILE -> event.done ? line("流寇", 0xFF7B3D, "已擊敗", -1, done)
                    : event.started || event.revealed ? line("流寇", 0xFF7B3D, event.exileName == null ? "還在" : event.exileName, -1, active)
                    : line("流寇", 0xFF7B3D, "還沒遇到", -1, idle);
            case ABYSS -> {
                if (event.done) {
                    yield line("深淵裂縫", 0x8A5CFF, "已結束", -1, done);
                }
                if (!event.started) {
                    yield line("深淵裂縫", 0x8A5CFF, "還沒發現", -1, idle);
                }
                if (event.riftCarved >= event.rift.size()) {
                    yield line("深淵裂縫", 0x8A5CFF, "打倒縫裡爬出來的那隻", 100, active);
                }
                yield line("深淵裂縫", 0x8A5CFF, "跟著縫走（還有 " + (event.rift.size() - event.riftCarved) + " 格）",
                        event.rift.isEmpty() ? -1 : percent((double) event.riftCarved / event.rift.size()), active);
            }
            case SAFE -> {
                final MapEventConfig.Safe settings = config.safe();
                if (event.done) {
                    yield event.wavesSpawned < 0 ? line("保險箱", 0xC8D0D8, "開鎖失敗", -1, failed) : line("保險箱", 0xC8D0D8, "已開", -1, done);
                }
                if (!event.started) {
                    yield line("保險箱", 0xC8D0D8, "要一張劫盜合約", -1, idle);
                }
                final String status = event.pausedTicks > 0 ? "沒人守著，進度暫停" : "開鎖中 " + Math.round(event.progressTicks / 20.0) + "／"
                        + Math.round(settings.unlockSeconds()) + " 秒";
                yield line("保險箱", 0xC8D0D8, status, percent(event.progressTicks / (settings.unlockSeconds() * 20)), active);
            }
        };
    }

    private static tw.exilecore.clientmod.WorldHudProtocol.EventLine line(final String name, final int color, final String status, final int progress,
                                                                         final int state) {
        return new tw.exilecore.clientmod.WorldHudProtocol.EventLine(name, color, status, progress, state);
    }

    static int percent(final double fraction) {
        return (int) Math.round(Math.max(0, Math.min(1, fraction)) * 100);
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
                if (!event.started) { // 2026-10-24：改成右鍵石柱才用（onInteract）
                    tw.exilecore.skill.Fx.spawn(center.getWorld(), Particle.DUST, center.getX(), center.getY() + 1.5, center.getZ(), 6, 0.4, 0.6, 0.4, 0, SHRINE_DUST);
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
            case ABYSS -> tickAbyss(event, players, closest, now); // 2026-10-18
            case SAFE -> tickSafe(event, players, now);
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
            case ABYSS -> openRift(event, now); // 2026-10-18
            default -> {
            }
        }
    }

    /** 事件中心附近、在地板上的位置。 */
    private Location around(final Event event, final double min, final double max, final RandomGenerator random) {
        return around(event.instance, event.center, min, max, random);
    }

    /** 某個點附近、在地板上的位置（2026-10-18：深淵裂縫的尾端也用）。 */
    private Location around(final MapInstance instance, final Location center, final double min, final double max, final RandomGenerator random) {
        for (int attempt = 0; attempt < 12; attempt++) {
            final double angle = random.nextDouble() * Math.PI * 2;
            final double radius = min + random.nextDouble() * (max - min);
            final double x = center.getX() + Math.cos(angle) * radius;
            final double z = center.getZ() + Math.sin(angle) * radius;
            if (instance.layout().isFloor((int) Math.floor(x) - instance.originX(), (int) Math.floor(z) - instance.originZ())) {
                return new Location(instance.world(), Math.floor(x) + 0.5, MapLayout.FLOOR_Y + 1, Math.floor(z) + 0.5, random.nextFloat() * 360f, 0f);
            }
        }
        return center.clone().add(1.5, 0, 0);
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
            final List<DropTable.Drop> uniques = eventUniques(MapEventConfig.Kind.SHRINE, event.instance, player, random);
            if (!uniques.isEmpty()) {
                deliver(player, event.center, new MonsterData(event.instance.level(), MonsterRarity.MAGIC, List.of(), shrine.name()), uniques, 0);
            }
            rollTicket(MapEventConfig.Kind.SHRINE, player, event.center, event.bonus ? BONUS_TICKET : 1.0); // 2026-10-18：入場券
        }
        tw.exilecore.skill.Fx.spawn(event.center.getWorld(), Particle.END_ROD, event.center.getX(), event.center.getY() + 1.5, event.center.getZ(), 40, 0.8, 1.0, 0.8, 0.05);
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
        if (event.getAction() != Action.RIGHT_CLICK_BLOCK || event.getClickedBlock() == null || event.getHand() != EquipmentSlot.HAND) {
            return;
        }
        // 2026-10-18：保險箱（鐵塊、鐵門、箱子都算）與深淵寶箱
        final Event structure = blocks.isEmpty() ? null : blocks.get(key(event.getClickedBlock()));
        if (structure != null) {
            if (!withinReach(event.getPlayer(), event.getClickedBlock())) {
                return;
            }
            event.setCancelled(true);
            event.setUseItemInHand(org.bukkit.event.Event.Result.DENY);
            if (plugin.skills() != null) {
                plugin.skills().consumeRightClick(event.getPlayer());
            }
            if (structure.kind == MapEventConfig.Kind.SHRINE) {
                if (structure.started) {
                    tw.exilecore.gui.ActionBar.send(event.getPlayer(), plugin.text().parse("<gray>這座神龕已經用過了"));
                } else {
                    useShrine(structure, event.getPlayer(), players(structure.instance), plugin.getServer().getCurrentTick());
                }
            } else if (structure.kind == MapEventConfig.Kind.SAFE) {
                useSafe(structure, event.getPlayer());
            } else if (!structure.done) {
                tw.exilecore.gui.ActionBar.send(event.getPlayer(), plugin.text().parse("<red>先打倒縫裡爬出來的那隻，寶箱才會開"));
            } else {
                tw.exilecore.gui.ActionBar.send(event.getPlayer(), plugin.text().parse("<gray>這個寶箱已經開過了"));
            }
            return;
        }
        if (event.getClickedBlock().getType() != Material.CHEST) {
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
            tw.exilecore.gui.ActionBar.send(player, plugin.text().parse("<gray>這個寶箱已經開過了"));
            return;
        }
        if (box.started) {
            tw.exilecore.gui.ActionBar.send(player, plugin.text().parse("<red>先殺光跳出來的怪（剩 " + box.monsters.size() + " 隻）"));
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
        tw.exilecore.skill.Fx.spawn(box.center.getWorld(), Particle.CLOUD, box.center.getX(), box.center.getY() + 1, box.center.getZ(), 30, 1.5, 0.5, 1.5, 0.02);
        box.center.getWorld().playSound(box.center, Sound.BLOCK_CHEST_OPEN, 1.0f, 0.6f);
        box.center.getWorld().playSound(box.center, Sound.ENTITY_EVOKER_PREPARE_SUMMON, 1.0f, 0.8f);
        for (final Player near : players(box.instance)) {
            if (near.getLocation().distanceSquared(box.center) <= config.boxRadius() * config.boxRadius()) {
                // 2026-10-26：大標題不留空（文字背景開「所有地方」會留一個空框）
                tw.exilecore.gui.Titles.show(near, plugin.text().parse("<#f5b942>" + ItemRenderer.escape(box.box.name())),
                        plugin.text().parse("<red>怪物跳出來了！殺光才會開"), Title.Times.times(Duration.ofMillis(100), Duration.ofMillis(2000), Duration.ofMillis(500)));
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
            final double month = plugin.events() == null ? 0 : plugin.events().quantityBonus(); // 2026-10：這個月的主題是全域的
            final double monthRarity = plugin.events() == null ? 0 : plugin.events().rarityBonus();
            final double quantity = box.quantityBonus() + instance.stats().quantity() / 100.0 + tw.exilecore.loot.LootBonus.personalQuantity(plugin, player)
                    + instance.atlas().increased("atlas.strongbox.quantity") + month; // 星圖天賦：寶箱的物品數量
            final double rarity = box.rarityBonus() + instance.stats().rarity() / 100.0 + tw.exilecore.loot.LootBonus.personalRarity(plugin, player)
                    + monthRarity;
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
                if (random.nextDouble() >= config.essenceChance()) {
                    continue; // 2026-10-24：每一顆照機率留下
                }
                final tw.exilecore.currency.CurrencyType essence = pickCurrency(tw.exilecore.currency.CurrencyOperation.ESSENCE, level, random);
                if (essence != null) {
                    rolled.add(DropTable.Drop.currency(essence));
                }
            }
            // 寶箱的專屬傳奇（每個有份的人各擲一次）
            rolled.addAll(eventUniques(MapEventConfig.Kind.STRONGBOX, instance, player, random));
            final long gold = drops.rollGold(level, MonsterRarity.RARE, quantity, random) * Math.max(1, box.rolls());
            deliver(player, event.center, data, rolled, gold);
            rollTicket(MapEventConfig.Kind.STRONGBOX, player, event.center, event.bonus ? BONUS_TICKET : 1.0); // 2026-10-18：入場券
            takers++;
        }
        tw.exilecore.skill.Fx.spawn(event.center.getWorld(), Particle.TOTEM_OF_UNDYING, event.center.getX(), event.center.getY() + 1, event.center.getZ(), 40, 0.6, 0.6, 0.6, 0.15);
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

    /**
     * 2026-11-12 照站長「進度條，殺怪會延長；進度條滿 100% 變成穩定裂隙，分波冒稀有怪（每波 5 隻），首領是費許的元帥維倫，打完才崩解；
     * 專屬的巢生怪；維倫只在穩定裂隙出」（PoE2 0.5 Breach，設定見 {@link MapEventConfig.BreachFeel}）：
     * 踩進去打開，巢生怪從邊緣一波一波湧出；計時一直往下掉、每殺一隻加幾秒，同時累積穩定進度（圈跟著進度變大）。
     * 計時歸零：收縮，圈外的怪被吞掉。進度滿：穩定裂隙——不再湧一般的怪，從起點分波出稀有的巢生怪，最後是維倫；全部打完才崩解、給穩定的獎勵。
     * 這次之前（同一天稍早，PoE1 手感版）：圈每秒自己長、殺怪推，到最大就收縮；裂痕首領 30% 在擴張途中出現；怪是地圖本身的。
     */
    private void tickBreach(final Event event, final List<Player> players, final double nearest, final long now) {
        final Location center = event.center;
        final MapEventConfig.BreachFeel feel = config.breachFeel();
        final double longer = event.instance.atlas().increased("atlas.breach.duration"); // 星圖天賦「開得更久」：起始時間、上限、每殺一隻加的秒數都多
        if (!event.started) {
            if (nearest <= config.breachStart() - 1) {
                event.started = true;
                event.startTick = now;
                event.nextWaveTick = now;
                event.breachTick = now;
                event.breachRadius = config.breachStart();
                event.breachLeft = feel.startSeconds() * (1 + Math.max(0, longer));
                placeHands(event, feel, service.random());
                center.getWorld().playSound(center, Sound.BLOCK_END_PORTAL_SPAWN, 1.0f, 0.7f);
                for (final Player player : players) {
                    if (player.getLocation().distanceSquared(center) <= 30 * 30) {
                        tw.exilecore.gui.Titles.show(player, plugin.text().parse(breachColor(event) + ItemRenderer.escape(event.title()) + "打開了"),
                                plugin.text().parse("<gray>殺怪延長時間；進度滿 100% 變成穩定裂隙"), Title.Times.times(Duration.ofMillis(100), Duration.ofMillis(2000), Duration.ofMillis(500)));
                    }
                }
            } else {
                ring(event, config.breachStart(), 16);
                tw.exilecore.skill.Fx.spawn(center.getWorld(), Particle.REVERSE_PORTAL, center.getX(), center.getY() + 0.5, center.getZ(), 8, 0.6, 0.4, 0.6, 0.02);
            }
            return;
        }
        tickHands(event, feel, players);
        if (event.done) {
            return;
        }
        final double dt = Math.max(0, (now - event.breachTick) / 20.0);
        event.breachTick = now;
        if (event.collapseTick > 0) {
            collapseBreach(event, feel, players, now);
            return;
        }
        // 殺掉的怪：加時間、加穩定進度（穩定之後只看那幾波稀有怪與維倫）
        final Iterator<Map.Entry<LivingEntity, MonsterRarity>> spawned = event.breachSpawned.entrySet().iterator();
        while (spawned.hasNext()) {
            final Map.Entry<LivingEntity, MonsterRarity> entry = spawned.next();
            final LivingEntity monster = entry.getKey();
            if (monster.isValid()) {
                continue;
            }
            if (monster.getHealth() <= 0 && !event.stabilised) {
                event.breachLeft = feel.addTime(event.breachLeft, feel.killTime(entry.getValue(), longer), longer);
                event.breachPoints += feel.killPoints(entry.getValue());
            }
            spawned.remove();
        }
        if (event.stabilised) {
            tickStable(event, feel, players, now);
            return;
        }
        event.breachLeft -= dt;
        event.breachRadius = config.breachStart() + (config.breachEnd() - config.breachStart()) * breachProgress(event);
        if (breachProgress(event) >= 1) {
            stabilise(event, feel, players, now);
            return;
        }
        if (event.breachLeft <= 0 || (now - event.startTick) / 20.0 >= feel.maxSeconds()) {
            startCollapse(event, feel, players, now);
            return;
        }
        final double radius = event.breachRadius;
        ring(event, radius, (int) Math.max(16, radius * 4));
        if (event.label != null && event.label.isValid()) {
            event.label.text(plugin.text().parse(breachColor(event) + ItemRenderer.escape(event.title()) + "<newline><gray>穩定 "
                    + Math.round(breachProgress(event) * 100) + "%・剩 <white>" + Math.max(0, Math.round(event.breachLeft)) + "</white> 秒（殺怪延長）"));
        }
        final RandomGenerator random = service.random();
        if (now >= event.nextWaveTick) {
            event.nextWaveTick = now + Math.round(config.waveSeconds() * 20);
            final MapEventConfig.Hiveborn kind = feel.pickHiveborn(random);
            if (kind == null) {
                return;
            }
            final int count = config.minWave() + (config.maxWave() > config.minWave() ? random.nextInt(config.maxWave() - config.minWave() + 1) : 0);
            final MapStats stats = event.instance.stats();
            final boolean magicWave = random.nextDouble() < 0.25 * (1 + stats.magicPacks() / 100.0);
            final List<String> mods = breachMods(event, magicWave ? plugin.monsters().rollMods(MonsterRarity.MAGIC, event.instance.level()) : List.of());
            final MonsterRarity rarity = magicWave ? MonsterRarity.MAGIC : MonsterRarity.NORMAL;
            for (int i = 0; i < count; i++) {
                final LivingEntity monster = spawnHiveborn(event, kind, around(event, Math.max(1.5, radius - 1.5), radius + 0.5, random), rarity, mods,
                        config.breachQuantity(), null, 1);
                if (monster != null) {
                    event.breachSpawned.put(monster, rarity);
                }
            }
            center.getWorld().playSound(center, Sound.ENTITY_ENDERMAN_TELEPORT, 0.8f, 0.6f);
        }
    }

    /** 2026-11-12：穩定進度（0～1）。 */
    private double breachProgress(final Event event) {
        return Math.max(0, Math.min(1, event.breachPoints / Math.max(1, config.breachFeel().needPoints())));
    }

    /**
     * 2026-11-12 照站長「專屬的巢生怪」：刷一隻巢生怪（原版生物 kind.type，名字 kind.name，稀有的叫「巢生精英・」），帶 mods，
     * 物品數量 +quantity（小數）；name 不是 null 就用它當名字（維倫），生命再乘 life。刷不出來回傳 null。
     */
    private LivingEntity spawnHiveborn(final Event event, final MapEventConfig.Hiveborn kind, final Location at, final MonsterRarity rarity,
                                       final List<String> mods, final double quantity, final String name, final double life) {
        final LivingEntity monster = service.spawner().spawn(event.instance, new tw.exilecore.zone.ZoneConfig.SpawnEntry(kind.type(), 1), at, rarity, mods);
        if (monster == null) {
            return null;
        }
        final MonsterData data = plugin.monsters().data(monster).orElse(null);
        if (data != null) {
            final String shown = name != null ? name : rarity == MonsterRarity.RARE ? "巢生精英・" + kind.name() : kind.name();
            plugin.monsters().decorateData(monster, new MonsterData(data.level(), data.rarity(), data.mods(), shown,
                    data.lifeMultiplier() * life, data.damageMultiplier(), data.boss()));
        }
        monster.getPersistentDataContainer().set(quantityKey, PersistentDataType.DOUBLE, quantity);
        event.monsters.add(monster);
        tw.exilecore.skill.Fx.spawn(monster.getWorld(), Particle.REVERSE_PORTAL, monster.getLocation().add(0, 1, 0), 12, 0.3, 0.5, 0.3, 0.05);
        return monster;
    }

    private static List<String> breachMods(final Event event, final List<String> rolled) {
        if (event.lord == null || event.lord.mods().isEmpty()) {
            return rolled;
        }
        final List<String> mods = new ArrayList<>(event.lord.mods());
        for (final String mod : rolled) {
            if (!mods.contains(mod)) {
                mods.add(mod);
            }
        }
        return mods;
    }

    private static String breachColor(final Event event) {
        return String.format("<#%06x>", event.lord == null ? 0xC48BFF : event.lord.color());
    }

    /** 2026-11-12：進度滿了，變成穩定裂隙（圈停在最大；還沒死的一般巢生怪留著、不再算進度）。 */
    private void stabilise(final Event event, final MapEventConfig.BreachFeel feel, final List<Player> players, final long now) {
        event.stabilised = true;
        event.stableTick = now;
        event.stableWave = 0;
        event.breachRadius = config.breachEnd();
        event.center.getWorld().playSound(event.center, Sound.BLOCK_END_PORTAL_FRAME_FILL, 1.0f, 0.5f);
        event.center.getWorld().playSound(event.center, Sound.ENTITY_WITHER_SPAWN, 0.5f, 1.6f);
        for (final Player player : players) {
            if (player.getLocation().distanceSquared(event.center) <= 40 * 40) {
                tw.exilecore.gui.Titles.show(player, plugin.text().parse(breachColor(event) + "裂隙穩定了"),
                        plugin.text().parse("<gray>起點會分 " + feel.stableWaves() + " 波湧出稀有的巢生怪，最後是" + ItemRenderer.escape(feel.vruunName())),
                        Title.Times.times(Duration.ofMillis(100), Duration.ofMillis(2500), Duration.ofMillis(500)));
            }
        }
        spawnStableWave(event, feel, now);
    }

    /** 2026-11-12：穩定裂隙的下一波：stableWaveSize 隻稀有的巢生怪，從起點附近出來。 */
    private void spawnStableWave(final Event event, final MapEventConfig.BreachFeel feel, final long now) {
        event.stableWave++;
        event.stableWaveTick = now;
        final RandomGenerator random = service.random();
        for (int i = 0; i < feel.stableWaveSize(); i++) {
            final MapEventConfig.Hiveborn kind = feel.pickHiveborn(random);
            if (kind == null) {
                return;
            }
            final LivingEntity rare = spawnHiveborn(event, kind, around(event, 1.5, 4.5, random), MonsterRarity.RARE,
                    breachMods(event, plugin.monsters().rollMods(MonsterRarity.RARE, event.instance.level())), config.breachQuantity(), null, 1);
            if (rare != null) {
                event.stableMonsters.add(rare);
                event.breachSpawned.put(rare, MonsterRarity.RARE);
            }
        }
        event.center.getWorld().playSound(event.center, Sound.ENTITY_ENDERMAN_SCREAM, 0.9f, 0.6f);
    }

    /** 2026-11-12：維倫（穩定裂隙最後一隻）：稀有、生命 ×vruunLife、物品數量 +vruunQuantity%，帶領主的詞綴＋vruunMods＋稀有詞綴。 */
    private void spawnVruun(final Event event, final MapEventConfig.BreachFeel feel, final List<Player> players) {
        event.vruunSpawned = true;
        final RandomGenerator random = service.random();
        final MapEventConfig.Hiveborn kind = new MapEventConfig.Hiveborn("ENDERMAN", feel.vruunName(), 1);
        final List<String> mods = new ArrayList<>(breachMods(event, plugin.monsters().rollMods(MonsterRarity.RARE, event.instance.level())));
        for (final String mod : feel.vruunMods()) {
            if (!mods.contains(mod)) {
                mods.add(mod);
            }
        }
        final LivingEntity vruun = spawnHiveborn(event, kind, around(event, 1.0, 2.5, random), MonsterRarity.RARE, mods,
                feel.vruunQuantity() / 100.0, feel.vruunName(), feel.vruunLife());
        if (vruun == null) {
            return;
        }
        final org.bukkit.attribute.AttributeInstance size = vruun.getAttribute(org.bukkit.attribute.Attribute.SCALE);
        if (size != null) {
            size.setBaseValue(1.4);
        }
        event.stableMonsters.add(vruun);
        event.breachSpawned.put(vruun, MonsterRarity.RARE);
        vruun.getWorld().playSound(vruun.getLocation(), Sound.ENTITY_WITHER_SPAWN, 0.7f, 1.2f);
        for (final Player player : players) {
            if (player.getLocation().distanceSquared(event.center) <= 40 * 40) {
                tw.exilecore.gui.ActionBar.send(player, plugin.text().parse(breachColor(event) + "✦ " + ItemRenderer.escape(feel.vruunName()) + "從裂隙裡出來了"));
            }
        }
    }

    /**
     * 2026-11-12：穩定裂隙每 tick——這一波殺光（或過了 stableWaveSeconds 秒）出下一波；波數出完、這一波也殺光了出維倫；
     * 維倫與剩下的都死了：崩解（給穩定的獎勵）。stableSeconds 秒內沒打完就收縮（沒有穩定獎勵）。
     */
    private void tickStable(final Event event, final MapEventConfig.BreachFeel feel, final List<Player> players, final long now) {
        event.stableMonsters.removeIf(monster -> !monster.isValid());
        final double radius = config.breachEnd();
        ring(event, radius, (int) Math.max(16, radius * 4));
        if ((now - event.stableTick) / 20.0 >= feel.stableSeconds()) {
            startCollapse(event, feel, players, now);
            return;
        }
        final boolean waveClear = event.stableMonsters.isEmpty();
        if (event.stableWave < feel.stableWaves()) {
            if (waveClear || (now - event.stableWaveTick) / 20.0 >= feel.stableWaveSeconds()) {
                spawnStableWave(event, feel, now);
            }
        } else if (!event.vruunSpawned) {
            if (waveClear) {
                spawnVruun(event, feel, players);
            }
        } else if (waveClear) {
            event.stableCleared = true;
            closeBreach(event, players);
            return;
        }
        if (event.label != null && event.label.isValid()) {
            final long left = Math.max(0, Math.round(feel.stableSeconds() - (now - event.stableTick) / 20.0));
            final String what = event.vruunSpawned ? ItemRenderer.escape(feel.vruunName()) : "第 " + event.stableWave + "／" + feel.stableWaves() + " 波";
            event.label.text(plugin.text().parse(breachColor(event) + "穩定裂隙<newline><gray>" + what + "（剩 " + event.stableMonsters.size()
                    + " 隻）・<white>" + left + "</white> 秒內打完"));
        }
    }

    /** 2026-11-12：開始收縮（計時歸零、開太久、穩定裂隙沒打完）。 */
    private void startCollapse(final Event event, final MapEventConfig.BreachFeel feel, final List<Player> players, final long now) {
        event.collapseTick = now;
        event.collapseFrom = event.stabilised ? config.breachEnd() : event.breachRadius;
        event.center.getWorld().playSound(event.center, Sound.BLOCK_RESPAWN_ANCHOR_DEPLETE, 1.0f, 0.5f);
        event.center.getWorld().playSound(event.center, Sound.ENTITY_WARDEN_SONIC_CHARGE, 0.8f, 0.6f);
        collapseBreach(event, feel, players, now);
    }

    /**
     * 收縮：collapseSeconds 秒內從 collapseFrom 收到 0，還在圈外的裂隙怪被吞掉（直接消失，沒有掉落）；
     * 收完就關上（附近的人各擲專屬傳奇、入場券、裂隙指環）。
     */
    private void collapseBreach(final Event event, final MapEventConfig.BreachFeel feel, final List<Player> players, final long now) {
        final double t = Math.min(1, (now - event.collapseTick) / 20.0 / feel.collapseSeconds());
        final double radius = event.collapseFrom * (1 - t);
        final Iterator<LivingEntity> spawned = event.breachSpawned.keySet().iterator();
        while (spawned.hasNext()) {
            final LivingEntity monster = spawned.next();
            if (!monster.isValid()) {
                spawned.remove();
                continue;
            }
            final double dx = monster.getLocation().getX() - event.center.getX();
            final double dz = monster.getLocation().getZ() - event.center.getZ();
            if (t >= 1 || dx * dx + dz * dz > (radius + 0.5) * (radius + 0.5)) {
                tw.exilecore.skill.Fx.spawn(monster.getWorld(), Particle.REVERSE_PORTAL, monster.getLocation().add(0, 1, 0), 16, 0.3, 0.6, 0.3, 0.08);
                monster.remove();
                spawned.remove();
            }
        }
        if (t < 1) {
            ring(event, Math.max(0.5, radius), (int) Math.max(12, radius * 4));
            if (event.label != null && event.label.isValid()) {
                event.label.text(plugin.text().parse(breachColor(event) + ItemRenderer.escape(event.title()) + "<newline><red>收縮中（圈外的怪會被吞掉）"));
            }
            return;
        }
        closeBreach(event, players);
    }

    /**
     * 裂隙關上：殺過這個裂隙的怪（有份）的人各擲一次裂隙的專屬傳奇、入場券、裂隙指環；打完穩定裂隙的再各拿 stableRolls 次稀有怪的掉落
     * （物品數量 +stableQuantity%）。2026-11-12 照站長「裂痕要以殺死裂痕產生的怪物來拿到獎勵，在圈外打也行」：
     * 照 {@link Event#breachKillers}（站在哪都算，人要在這張圖裡）；這次之前是關上時裂隙中心 30 格內的人。
     */
    private void closeBreach(final Event event, final List<Player> players) {
        final Location center = event.center;
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
        final RandomGenerator random = service.random();
        final MapEventConfig.BreachFeel feel = config.breachFeel();
        for (final Player player : players) {
            if (!event.breachKillers.contains(player.getUniqueId())) {
                continue;
            }
            final List<DropTable.Drop> uniques = eventUniques(MapEventConfig.Kind.BREACH, event.instance, player, random);
            if (!uniques.isEmpty()) {
                deliver(player, center, new MonsterData(event.instance.level(), MonsterRarity.MAGIC, List.of(), "裂隙"), uniques, 0);
            }
            rollTicket(MapEventConfig.Kind.BREACH, player, center, event.bonus ? BONUS_TICKET : 1.0); // 2026-10-18：入場券
            // 2026-11-04 照站長「裂隙指環放進裂痕掉落」：各擲一次（吃物品稀有度），魔法或稀有、未鑑定；這次之前裂隙指環在劫盜掉
            final DropTable.Drop ring = eventBase(MapEventConfig.Kind.BREACH, event.instance, player, random);
            if (ring != null) {
                deliver(player, center, new MonsterData(event.instance.level(), MonsterRarity.MAGIC, List.of(), "裂隙"), List.of(ring), 0);
            }
            if (event.stableCleared) {
                breachLoot(event, player, center, "穩定裂隙", feel.stableRolls(), feel.stableQuantity(), 0, random);
            }
        }
        if (event.stableCleared) {
            for (final Player player : players) {
                if (event.breachKillers.contains(player.getUniqueId())) {
                    tw.exilecore.gui.Titles.show(player, plugin.text().parse(breachColor(event) + "穩定裂隙崩解了"), plugin.text().parse("<gray>打完了，獎勵送到你身上"),
                            Title.Times.times(Duration.ofMillis(100), Duration.ofMillis(2000), Duration.ofMillis(500)));
                }
            }
        }
    }

    /** 2026-11-12：一個人拿 rolls 次稀有怪的掉落（物品數量 +quantityPct%）＋catalystChance 機率一顆催化劑（緊握之手、穩定裂隙共用）。 */
    private void breachLoot(final Event event, final Player player, final Location at, final String source, final int rolls, final double quantityPct,
                            final double catalystChance, final RandomGenerator random) {
        final MapInstance instance = event.instance;
        final DropTable drops = plugin.monsterModule().drops();
        final int level = instance.level();
        final double month = plugin.events() == null ? 0 : plugin.events().quantityBonus();
        final double monthRarity = plugin.events() == null ? 0 : plugin.events().rarityBonus();
        final double quantity = quantityPct / 100.0 + instance.stats().quantity() / 100.0
                + tw.exilecore.loot.LootBonus.personalQuantity(plugin, player) + month;
        final double rarity = instance.stats().rarity() / 100.0 + tw.exilecore.loot.LootBonus.personalRarity(plugin, player) + monthRarity;
        final List<DropTable.Drop> rolled = new ArrayList<>();
        for (int i = 0; i < rolls; i++) {
            rolled.addAll(drops.roll(level, MonsterRarity.RARE, quantity + drops.serverQuantity(), rarity + drops.serverRarity(),
                    plugin.items().generator(), plugin.currencies().repository(), random, plugin.characters().lootBias(player)));
        }
        if (catalystChance > 0 && random.nextDouble() < catalystChance) {
            final tw.exilecore.currency.CurrencyType catalyst = pickCurrency(tw.exilecore.currency.CurrencyOperation.CATALYST, level, random);
            if (catalyst != null) {
                rolled.add(DropTable.Drop.currency(catalyst));
            }
        }
        final long gold = drops.rollGold(level, MonsterRarity.RARE, quantity, random) * Math.max(1, rolls);
        deliver(player, at, new MonsterData(level, MonsterRarity.RARE, List.of(), source), rolled, gold);
    }

    /** 2026-11-12：緊握之手藏在圈裡（離中心 30%～95% 的地方），圈長到那裡才露出來。 */
    private void placeHands(final Event event, final MapEventConfig.BreachFeel feel, final RandomGenerator random) {
        final int count = feel.minHands() + (feel.maxHands() > feel.minHands() ? random.nextInt(feel.maxHands() - feel.minHands() + 1) : 0);
        final double span = config.breachEnd() - config.breachStart();
        for (int i = 0; i < count; i++) {
            final Location at = around(event, config.breachStart() + span * 0.3, config.breachEnd() * 0.95, random);
            final double distance = Math.hypot(at.getX() - event.center.getX(), at.getZ() - event.center.getZ());
            event.hands.add(new BreachHand(at, distance));
        }
    }

    /**
     * 2026-11-12 照站長「裂痕以 PoE1 手感為主」：緊握之手——圈長過它就露出來（字＋粒子），有人走到 2 格內就打開：
     * handRadius 格內的人各拿 handRolls 次稀有怪的掉落（物品數量 +handQuantity%），再擲 handCatalyst 機率一顆催化劑。
     * 裂隙關上之後沒開的照樣能開（地圖關了就沒了）。
     */
    private void tickHands(final Event event, final MapEventConfig.BreachFeel feel, final List<Player> players) {
        for (final BreachHand hand : event.hands) {
            if (hand.opened) {
                continue;
            }
            if (!hand.revealed) {
                if (event.breachRadius + 0.5 < hand.distance) {
                    continue;
                }
                hand.revealed = true;
                hand.label = hand.at.getWorld().spawn(hand.at.clone().add(0, 1.2, 0), TextDisplay.class, created -> {
                    created.setBillboard(Display.Billboard.CENTER);
                    created.setPersistent(false);
                    created.setSeeThrough(false);
                    created.setShadowed(true);
                });
                hand.label.text(plugin.text().parse(breachColor(event) + "✋ 緊握之手<newline><gray>走過去打開"));
                hand.at.getWorld().playSound(hand.at, Sound.BLOCK_SCULK_SHRIEKER_SHRIEK, 0.6f, 1.4f);
            }
            tw.exilecore.skill.Fx.spawn(hand.at.getWorld(), Particle.REVERSE_PORTAL, hand.at.getX(), hand.at.getY() + 0.4, hand.at.getZ(), 6, 0.25, 0.3, 0.25, 0.02);
            Player opener = null;
            for (final Player player : players) {
                if (player.getWorld() == hand.at.getWorld() && player.getLocation().distanceSquared(hand.at) <= 4) {
                    opener = player;
                    break;
                }
            }
            if (opener != null) {
                openHand(event, hand, feel, players);
            }
        }
    }

    private void openHand(final Event event, final BreachHand hand, final MapEventConfig.BreachFeel feel, final List<Player> players) {
        hand.opened = true;
        if (hand.label != null && hand.label.isValid()) {
            hand.label.remove();
        }
        hand.label = null;
        final RandomGenerator random = service.random();
        for (final Player player : players) {
            if (player.getWorld() != hand.at.getWorld() || player.getLocation().distanceSquared(hand.at) > feel.handRadius() * feel.handRadius()) {
                continue;
            }
            breachLoot(event, player, hand.at, "緊握之手", feel.handRolls(), feel.handQuantity(), feel.handCatalyst(), random);
        }
        tw.exilecore.skill.Fx.spawn(hand.at.getWorld(), Particle.REVERSE_PORTAL, hand.at.getX(), hand.at.getY() + 0.6, hand.at.getZ(), 40, 0.4, 0.5, 0.4, 0.15);
        hand.at.getWorld().playSound(hand.at, Sound.BLOCK_ENDER_CHEST_OPEN, 1.0f, 0.7f);
    }

    /** 2026-11-12：裂隙的圈用領主的顏色。 */
    private void ring(final Event event, final double radius, final int points) {
        final int color = event.lord == null ? 0xC48BFF : event.lord.color();
        ring(event.center, radius, points, color, color == 0xC48BFF ? BREACH_DUST : new Particle.DustOptions(Color.fromRGB(color), 1.3f));
    }

    /** 地上一圈紫色的點（套了資源包的人看平面的圈，粒子只送給沒套的人）。 */
    private void ring(final Location center, final double radius, final int points, final int color, final Particle.DustOptions dust) {
        final GroundDecals decals = plugin.skills() == null ? null : plugin.skills().decals();
        final List<Player> viewers;
        if (decals != null) {
            decals.ring(decalId(center), center, radius, color);
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
                    center.getZ() + Math.sin(angle) * radius), 1, 0, 0, 0, 0, dust);
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
        tw.exilecore.skill.Fx.spawn(exile.getWorld(), Particle.SOUL, exile.getLocation().add(0, 1, 0), 30, 0.6, 0.8, 0.6, 0.03);
        for (final Player player : players(instance)) {
            if (player.getLocation().distanceSquared(event.center) <= 32 * 32 && !plugin.players().hidesMonsterTalk(player)) { // 2026-10-22：隱藏怪物對話
                player.sendMessage(plugin.text().prefixed("<#ffff77>流寇·" + ItemRenderer.escape(event.exileName) + "<gray>：「這張圖是我的。」"));
            }
        }
    }

    /**
     * 這種事件的專屬傳奇（uniques.yml 的 sources 寫 shrine、strongbox、breach、exile、abyss）：每一件各自照 map_events.yml 的 unique_chance 擲
     * （2026-10-16：以前整個池子擲一次再抽一件）；沒中的是空的。
     */
    private List<DropTable.Drop> eventUniques(final MapEventConfig.Kind kind, final MapInstance instance, final Player player,
                                              final RandomGenerator random) {
        // 星圖天賦：這種事件掉專屬傳奇的機率增加 x%；2026-10：也吃這個人的物品稀有度（自己的＋全服＋主題＋地圖的）
        final tw.exilecore.loot.LootBonus.Bonus bonus = tw.exilecore.loot.LootBonus.of(plugin, player).plus(0, instance.stats().rarity() / 100.0);
        final double chance = bonus.rare(config.uniqueChance(kind) * (1 + instance.atlas().eventUnique(kind)));
        final List<DropTable.Drop> drops = new java.util.ArrayList<>();
        for (final tw.exilecore.item.UniqueItem unique : plugin.items().repository().uniquesFor(MapEventConfig.uniqueSource(kind), instance.level())) {
            if (random.nextDouble() < chance) {
                drops.add(DropTable.Drop.equipment(plugin.items().generator().generateUnique(unique, instance.level(), random)));
            }
        }
        return drops;
    }

    /** 2026-11-04：事件的特殊基底（map_events.yml 的 breach.ring 裂隙指環、abyss.ring 淵印指環）；沒中、沒設定或這張圖等級不夠回傳 null。 */
    private DropTable.Drop eventBase(final MapEventConfig.Kind kind, final MapInstance instance, final Player player, final RandomGenerator random) {
        final MapEventConfig.EventBase setting = config.eventBase(kind);
        final tw.exilecore.item.ItemBase base = setting == null ? null : plugin.items().repository().base(setting.base());
        if (base == null || base.dropLevel() > instance.level()) {
            return null;
        }
        final tw.exilecore.loot.LootBonus.Bonus bonus = tw.exilecore.loot.LootBonus.of(plugin, player)
                .plus(instance.stats().quantity() / 100.0, instance.stats().rarity() / 100.0);
        if (random.nextDouble() >= Math.min(1, bonus.rare(setting.chance()))) {
            return null;
        }
        return DropTable.Drop.equipment(MapRewards.specialBase(plugin, base, instance.level(), random));
    }

    /** 隨機一種這類操作的通貨（掉落等級夠的）；沒有回傳 null。 */
    private tw.exilecore.currency.CurrencyType pickCurrency(final tw.exilecore.currency.CurrencyOperation operation, final int level,
                                                          final RandomGenerator random) {
        return MapRewards.pickCurrency(plugin, operation, level, random);
    }

    /** 2026-10-30 照站長：裂隙怪掉催化劑的機率 ×（1 + 物品數量）×（1 + 物品稀有度）（自己的＋全服＋主題＋地圖的），最多 100%。 */
    private double catalystChance(final Player killer, final MapInstance instance) {
        tw.exilecore.loot.LootBonus.Bonus bonus = tw.exilecore.loot.LootBonus.of(plugin, killer);
        if (instance != null) {
            bonus = bonus.plus(instance.stats().quantity() / 100.0, instance.stats().rarity() / 100.0);
        }
        return Math.min(1, bonus.rare(bonus.more(config.catalystChance())));
    }

    /** 2026-10-30 照站長：流寇的稀有裝備每件有（物品稀有度 × 10%，最多 50%）的機率換成一件共通傳奇。 */
    static double exileUniqueChance(final double rarity) {
        return Math.max(0, Math.min(0.5, rarity * 0.1));
    }

    /** 流寇死了：殺的人（與有份的隊友）各多拿幾件稀有裝備與星籤；裂隙的怪死了有機率掉催化劑。 */
    @EventHandler(priority = EventPriority.MONITOR)
    public void onDeath(final EntityDeathEvent event) {
        final LivingEntity entity = event.getEntity();
        final Player killer = entity.getKiller();
        final RandomGenerator random = service.random();
        final Event found = exiles.remove(entity.getUniqueId());
        if (found == null && entity.getPersistentDataContainer().has(quantityKey)) {
            // 2026-11-12 照站長「裂痕要以殺死裂痕產生的怪物來拿到獎勵，在圈外打也行」：記下這隻裂隙怪有份的人（不管站在哪）
            final MapInstance where = service.instanceOf(entity);
            for (final Event breach : where == null ? List.<Event>of() : byInstance.getOrDefault(where.id(), List.of())) {
                if (breach.kind == MapEventConfig.Kind.BREACH && breach.breachSpawned.containsKey(entity)) {
                    for (final Player sharer : plugin.monsterModule().credit().sharers(entity, killer)) {
                        breach.breachKillers.add(sharer.getUniqueId());
                    }
                    if (killer != null) {
                        breach.breachKillers.add(killer.getUniqueId());
                    }
                    break;
                }
            }
        }
        if (found == null) {
            // 裂隙的怪：催化劑
            if (killer != null && entity.getPersistentDataContainer().has(quantityKey)
                    && random.nextDouble() < catalystChance(killer, service.instanceOf(entity))) { // 2026-10：吃物品數量；2026-10-30 照站長：也吃物品稀有度
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
            // 2026-10：件數、星籤數吃這個人的物品數量（自己的＋全服＋主題＋地圖的）
            final tw.exilecore.loot.LootBonus.Bonus bonus = tw.exilecore.loot.LootBonus.of(plugin, player)
                    .plus(found.instance.stats().quantity() / 100.0, found.instance.stats().rarity() / 100.0);
            final int pieces = bonus.amount(config.exileDrops() + extra, random);
            final double uniqueChance = exileUniqueChance(bonus.rarity()); // 2026-10-30 照站長：流寇也吃物品稀有度
            for (int i = 0; i < pieces && anyBase; i++) {
                if (random.nextDouble() < uniqueChance) {
                    final tw.exilecore.item.UniqueItem unique = tw.exilecore.item.ItemGenerator.pickUnique(
                            plugin.items().repository().uniquesFor("map_boss", level), random);
                    if (unique != null) {
                        rolled.add(DropTable.Drop.equipment(plugin.items().generator().generateUnique(unique, level, random)));
                        continue;
                    }
                }
                final ItemBase base = plugin.items().repository().pickSold(level, random, bias);
                if (base == null) {
                    break;
                }
                rolled.add(DropTable.Drop.equipment(plugin.items().generator().generate(base, Rarity.RARE, level, random)));
            }
            // 流寇的招牌：星籤。2026-10-27 照站長：每隻流寇都必掉星籤（這次之前同一張圖只有第一個保證、之後 20% 機率；計數一併拿掉）
            final int omens = bonus.amount(config.minOmens() + (config.maxOmens() > config.minOmens() ? random.nextInt(config.maxOmens() - config.minOmens() + 1) : 0), random);
            for (int i = 0; i < omens; i++) {
                final tw.exilecore.currency.CurrencyType omen = pickCurrency(tw.exilecore.currency.CurrencyOperation.OMEN, level, random);
                if (omen != null) {
                    rolled.add(DropTable.Drop.currency(omen));
                }
            }
            // 流寇的專屬傳奇（每個有份的人各擲一次）
            rolled.addAll(eventUniques(MapEventConfig.Kind.EXILE, found.instance, player, random));
            if (!rolled.isEmpty()) {
                deliver(player, entity.getLocation(), data, rolled, 0);
            }
            rollTicket(MapEventConfig.Kind.EXILE, player, entity.getLocation(), found.bonus ? BONUS_TICKET : 1.0); // 2026-10-18：入場券
        }
    }

    // ---- 深淵裂縫（2026-10-18）----

    /** 走近：規劃縫的路線，先裂開第一段。 */
    private void openRift(final Event event, final long now) {
        final MapEventConfig.Abyss settings = config.abyss();
        final RandomGenerator random = service.random();
        final MapInstance instance = event.instance;
        final MapLayout.Spot start = new MapLayout.Spot(event.center.getBlockX() - instance.originX(), event.center.getBlockZ() - instance.originZ());
        final int length = settings.minLength() + (settings.maxLength() > settings.minLength() ? random.nextInt(settings.maxLength() - settings.minLength() + 1) : 0);
        event.rift = planRift(instance.layout(), start, length, random);
        event.started = true;
        event.startTick = now;
        event.nextWaveTick = now + Math.round(settings.advanceSeconds() * 20);
        event.center.getWorld().playSound(event.center, Sound.BLOCK_RESPAWN_ANCHOR_AMBIENT, 1.0f, 0.5f);
        event.center.getWorld().playSound(event.center, Sound.ENTITY_WARDEN_HEARTBEAT, 1.0f, 0.6f);
        for (final Player player : players(instance)) {
            if (player.getLocation().distanceSquared(event.center) <= 30 * 30) {
                tw.exilecore.gui.Titles.show(player, plugin.text().parse(ABYSS_COLOR + "地裂開了"), plugin.text().parse(ABYSS_COLOR + "跟著縫走到盡頭"),
                        Title.Times.times(Duration.ofMillis(100), Duration.ofMillis(2000), Duration.ofMillis(500)));
            }
        }
        advanceRift(event, now, null);
    }

    /**
     * 縫的路線（相對座標）：從事件的位置沿著這個房間的主路走道（{@link MapLayout#links}）往下一個房間走；死路房沒有走道，
     * 就挑四個方向裡能走最遠的一條直線。只走地板，碰到牆就提早結束。
     */
    static List<MapLayout.Spot> planRift(final MapLayout layout, final MapLayout.Spot start, final int length, final RandomGenerator random) {
        final List<MapLayout.Spot> path = new ArrayList<>();
        MapLayout.Room room = null;
        for (final MapLayout.Room candidate : layout.rooms()) {
            if (candidate.contains(start.x(), start.z())) {
                room = candidate;
                break;
            }
        }
        final List<List<MapLayout.Spot>> routes = new ArrayList<>();
        if (room != null) {
            for (final MapLayout.Link link : layout.links()) {
                // 2026-10-18：只走兩頭都是一般房／死路房的走道（不裂進起點、Boss 房）
                if (!riftRoom(link.from()) || !riftRoom(link.to())) {
                    continue;
                }
                if (link.from().equals(room)) {
                    routes.add(link.points());
                } else if (link.to().equals(room)) {
                    final List<MapLayout.Spot> reversed = new ArrayList<>(link.points());
                    java.util.Collections.reverse(reversed);
                    routes.add(reversed);
                }
            }
        }
        if (!routes.isEmpty()) {
            final List<MapLayout.Spot> route = routes.get(random.nextInt(routes.size()));
            int x = start.x();
            int z = start.z();
            path.add(start);
            outer:
            for (final MapLayout.Spot target : route) {
                while (x != target.x() || z != target.z()) {
                    // 一次走一格（先走差得多的那個軸：走道的每一段都是直的，這樣會沿著走道的中線）
                    if (Math.abs(target.x() - x) >= Math.abs(target.z() - z)) {
                        x += Integer.compare(target.x(), x);
                    } else {
                        z += Integer.compare(target.z(), z);
                    }
                    if (!riftCell(layout, room, x, z)) { // 2026-10-18：踩進起點／Boss 房、別的房間中心也提早結束
                        break outer;
                    }
                    path.add(new MapLayout.Spot(x, z));
                    if (path.size() >= length) {
                        break outer;
                    }
                }
            }
            // 2026-10-18：尾端剪掉 4 格，縫不會剛好裂到下一個房間的中心（寶箱、事件都放在那）
            for (int i = 0; i < RIFT_TRIM && path.size() > 1; i++) {
                path.removeLast();
            }
        }
        if (path.size() < Math.min(length, 8)) {
            // 沒有走道、或走沒幾格就撞牆：改走能走最遠的直線
            path.clear();
            int bestDx = 1;
            int bestDz = 0;
            int best = -1;
            for (final int[] direction : new int[][] {{1, 0}, {-1, 0}, {0, 1}, {0, -1}}) {
                int run = 0;
                while (run < length - 1 && riftCell(layout, room, start.x() + direction[0] * (run + 1), start.z() + direction[1] * (run + 1))) { // 2026-10-18
                    run++;
                }
                if (run > best) {
                    best = run;
                    bestDx = direction[0];
                    bestDz = direction[1];
                }
            }
            for (int i = 0; i <= Math.max(0, best); i++) {
                path.add(new MapLayout.Spot(start.x() + bestDx * i, start.z() + bestDz * i));
            }
        }
        return path;
    }

    /** 2026-10-18：縫的尾端剪掉幾格（不裂到房間中心）。 */
    static final int RIFT_TRIM = 4;

    /** 2026-10-18：縫可以裂進去的房間：一般房、死路房（起點與 Boss 房不行）。 */
    static boolean riftRoom(final MapLayout.Room room) {
        return room.kind() == MapLayout.Kind.NORMAL || room.kind() == MapLayout.Kind.SIDE;
    }

    /** 2026-10-18：這一格縫走得過去：是地板，而且不在起點／Boss 房裡。 */
    static boolean riftCell(final MapLayout layout, final int x, final int z) {
        return riftCell(layout, null, x, z);
    }

    /** 2026-10-18：同上，另外「別的房間的中心」（事件、寶箱放在那）也不走；origin 是縫起點所在的房間（自己房間的中心可以）。 */
    static boolean riftCell(final MapLayout layout, final MapLayout.Room origin, final int x, final int z) {
        if (!layout.isFloor(x, z)) {
            return false;
        }
        final MapLayout.Room room = layout.roomAt(x, z);
        if (room == null) {
            return true;
        }
        if (!riftRoom(room)) {
            return false;
        }
        return room == origin || x != room.cx() || z != room.cz();
    }

    private void tickAbyss(final Event event, final List<Player> players, final Player closest, final long now) {
        if (!event.started || event.done) {
            if (!event.started) {
                final Location center = event.center;
                tw.exilecore.skill.Fx.spawn(center.getWorld(), Particle.DUST, center.getX(), center.getY() + 0.3, center.getZ(), 4, 0.5, 0.1, 0.5, 0, ABYSS_DUST);
            }
            return;
        }
        final MapEventConfig.Abyss settings = config.abyss();
        final Location head = event.head();
        // 盡頭：小 Boss 倒下寶箱才開
        if (event.riftCarved >= event.rift.size()) {
            if (event.miniBoss == null || !event.miniBoss.isValid() || event.miniBoss.isDead()) {
                openAbyssChest(event, players, now);
            } else if (event.label != null && event.label.isValid()) {
                event.label.text(plugin.text().parse(ABYSS_COLOR + "深淵裂縫<newline><red>打倒縫裡爬出來的那隻，寶箱就會開"));
            }
            return;
        }
        tw.exilecore.skill.Fx.spawn(head.getWorld(), Particle.DUST, head.getX(), head.getY() + 0.3, head.getZ(), 6, 0.8, 0.2, 0.8, 0, ABYSS_DUST);
        tw.exilecore.skill.Fx.spawn(head.getWorld(), Particle.REVERSE_PORTAL, head.getX(), head.getY() + 0.4, head.getZ(), 6, 0.5, 0.3, 0.5, 0.02);
        // 有人在縫尾端 follow 格內才繼續延伸；沒人超過 stall_seconds 就合上（怪留著、沒獎勵）
        Player follower = null;
        double nearest = Double.MAX_VALUE;
        for (final Player player : players) {
            final double distance = Math.hypot(player.getLocation().getX() - head.getX(), player.getLocation().getZ() - head.getZ());
            if (distance <= settings.follow() && distance < nearest) {
                nearest = distance;
                follower = player;
            }
        }
        if (follower == null) {
            if (event.stallSince == 0) {
                event.stallSince = now;
            } else if (now - event.stallSince >= settings.stallSeconds() * 20) {
                closeRift(event, players, now);
                return;
            }
            if (event.label != null && event.label.isValid()) {
                final long left = Math.max(0, Math.round(settings.stallSeconds() - (now - event.stallSince) / 20.0));
                event.label.text(plugin.text().parse(ABYSS_COLOR + "深淵裂縫<newline><red>沒人跟著：" + left + " 秒後合上"));
            }
            return;
        }
        event.stallSince = 0;
        if (event.label != null && event.label.isValid()) {
            event.label.text(plugin.text().parse(ABYSS_COLOR + "深淵裂縫<newline><gray>跟著縫走到盡頭（還有 " + (event.rift.size() - event.riftCarved) + " 格）"));
        }
        if (now >= event.nextWaveTick) {
            event.nextWaveTick = now + Math.round(settings.advanceSeconds() * 20);
            advanceRift(event, now, follower);
        }
    }

    /** 再裂開 advance 格，從新裂開的地方湧一波怪；到長度上限就冒出小 Boss 與寶箱。 */
    private void advanceRift(final Event event, final long now, final Player target) {
        final MapEventConfig.Abyss settings = config.abyss();
        final RandomGenerator random = service.random();
        final MapInstance instance = event.instance;
        final List<MapLayout.Spot> fresh = new ArrayList<>();
        final int end = Math.min(event.rift.size(), event.riftCarved + settings.advance());
        for (int i = event.riftCarved; i < end; i++) {
            final MapLayout.Spot spot = event.rift.get(i);
            final MapLayout.Spot previous = event.rift.get(Math.max(0, i - 1));
            final Block floor = instance.world().getBlockAt(instance.originX() + spot.x(), MapLayout.FLOOR_Y, instance.originZ() + spot.z());
            carve(event, floor, Material.OBSIDIAN);
            // 寬 2–3 格：往延伸方向的兩側各有七成的機會也裂開
            final boolean alongX = spot.x() != previous.x() || spot.z() == previous.z();
            for (final int side : new int[] {-1, 1}) {
                final int sx = alongX ? spot.x() : spot.x() + side;
                final int sz = alongX ? spot.z() + side : spot.z();
                if (random.nextDouble() < 0.7 && instance.layout().isFloor(sx, sz)) {
                    carve(event, instance.world().getBlockAt(instance.originX() + sx, MapLayout.FLOOR_Y, instance.originZ() + sz),
                            random.nextBoolean() ? Material.OBSIDIAN : Material.CRYING_OBSIDIAN);
                }
            }
            fresh.add(spot);
            final Location at = new Location(instance.world(), instance.originX() + spot.x() + 0.5, MapLayout.FLOOR_Y + 1, instance.originZ() + spot.z() + 0.5);
            tw.exilecore.skill.Fx.spawn(at.getWorld(), Particle.SOUL, at, 6, 0.4, 0.3, 0.4, 0.02);
            tw.exilecore.skill.Fx.spawn(at.getWorld(), Particle.BLOCK, at, 12, 0.5, 0.2, 0.5, 0, Material.OBSIDIAN.createBlockData());
        }
        event.riftCarved = end;
        event.riftSegments++;
        final Location head = event.head();
        head.getWorld().playSound(head, Sound.BLOCK_DEEPSLATE_BREAK, 1.0f, 0.5f);
        if (event.label != null && event.label.isValid()) {
            event.label.teleport(head.clone().add(0, 1.4, 0));
        }
        // 這一段湧出的怪（照地圖的怪物種類；每三段一隻魔法怪）
        if (!fresh.isEmpty()) {
            final ZoneConfig.SpawnEntry entry = service.spawner().chooseMonster(instance.map().monsters(), random);
            final int count = settings.minWave() + (settings.maxWave() > settings.minWave() ? random.nextInt(settings.maxWave() - settings.minWave() + 1) : 0);
            if (entry != null) {
                final boolean magic = event.riftSegments % 3 == 0;
                final List<String> mods = magic ? plugin.monsters().rollMods(MonsterRarity.MAGIC, instance.level()) : List.of();
                for (int i = 0; i < count; i++) {
                    final MapLayout.Spot spot = fresh.get(random.nextInt(fresh.size()));
                    final Location at = new Location(instance.world(), instance.originX() + spot.x() + 0.5, MapLayout.FLOOR_Y + 1,
                            instance.originZ() + spot.z() + 0.5, random.nextFloat() * 360f, 0f);
                    final LivingEntity monster = service.spawner().spawn(instance, entry, at, magic && i == 0 ? MonsterRarity.MAGIC : MonsterRarity.NORMAL,
                            magic && i == 0 ? mods : List.of());
                    if (monster != null) {
                        monster.getPersistentDataContainer().set(abyssQuantityKey, PersistentDataType.DOUBLE, settings.quantityBonus());
                        event.monsters.add(monster);
                        if (target != null && monster instanceof Mob mob) {
                            mob.setTarget(target);
                        }
                        tw.exilecore.skill.Fx.spawn(monster.getWorld(), Particle.SCULK_SOUL, monster.getLocation().add(0, 1, 0), 10, 0.3, 0.5, 0.3, 0.03);
                    }
                }
            }
        }
        if (event.riftCarved >= event.rift.size()) {
            spawnAbyssBoss(event, now);
        }
    }

    /** 盡頭：一隻小 Boss（稀有怪、生命 ×life、詞綴 mods，跟流寇一樣的做法）＋一個深淵寶箱。 */
    private void spawnAbyssBoss(final Event event, final long now) {
        final MapEventConfig.Abyss settings = config.abyss();
        final RandomGenerator random = service.random();
        final MapInstance instance = event.instance;
        final Location head = event.head();
        // 寶箱放在縫的尾端
        // 2026-10-18：尾端那格要是空的、而且不是別的事件的方塊（寶箱、保險箱…）；不行就沿著縫往回找第一格合格的
        Block chest = head.getBlock();
        for (int i = Math.min(event.riftCarved, event.rift.size()) - 1; i >= 0 && !chestSpot(chest); i--) {
            final MapLayout.Spot spot = event.rift.get(i);
            chest = instance.world().getBlockAt(instance.originX() + spot.x(), MapLayout.FLOOR_Y + 1, instance.originZ() + spot.z());
        }
        if (!chestSpot(chest)) {
            chest = head.getBlock(); // 整條縫都不合格（不太可能）：照舊放尾端
        }
        chest.setType(Material.CHEST, false);
        event.chest = chest;
        blocks.put(key(chest), event);
        final ZoneConfig.SpawnEntry entry = service.spawner().chooseMonster(instance.map().monsters(), random);
        final List<String> mods = new ArrayList<>();
        for (final String id : settings.bossMods()) {
            if (plugin.monsters().config().mod(id) != null) {
                mods.add(id);
            }
        }
        final LivingEntity boss = entry == null ? null : service.spawner().spawn(instance, entry, around(instance, head, 1.5, 3.5, random), MonsterRarity.RARE, mods);
        if (boss == null) {
            openAbyssChest(event, players(instance), now); // 刷不出來：直接開
            return;
        }
        final MonsterData data = plugin.monsters().data(boss).orElse(null);
        if (data != null) {
            plugin.monsters().decorateData(boss, new MonsterData(data.level(), data.rarity(), data.mods(), "淵底的" + data.name(),
                    data.lifeMultiplier() * settings.bossLife(), data.damageMultiplier(), false));
        }
        boss.getPersistentDataContainer().set(abyssQuantityKey, PersistentDataType.DOUBLE, settings.quantityBonus());
        boss.setGlowing(true);
        event.miniBoss = boss;
        event.monsters.add(boss);
        head.getWorld().playSound(head, Sound.ENTITY_WARDEN_EMERGE, 1.0f, 0.8f);
        tw.exilecore.skill.Fx.spawn(head.getWorld(), Particle.SCULK_SOUL, boss.getLocation().add(0, 1, 0), 40, 0.8, 1.0, 0.8, 0.05);
        for (final Player player : players(instance)) {
            if (player.getLocation().distanceSquared(head) <= 32 * 32) {
                tw.exilecore.gui.Titles.show(player, plugin.text().parse(ABYSS_COLOR + "縫的盡頭爬出了什麼"),
                        plugin.text().parse(ABYSS_COLOR + "打倒它，寶箱就會開"), Title.Times.times(Duration.ofMillis(100), Duration.ofMillis(2000), Duration.ofMillis(500)));
            }
        }
    }

    /** 2026-10-18：這一格可以放深淵寶箱：是空氣，而且不是寶箱／保險箱／深淵寶箱登記過的方塊。 */
    private boolean chestSpot(final Block block) {
        return block.getType() == Material.AIR && !boxes.containsKey(key(block)) && !blocks.containsKey(key(block));
    }

    /** 沒人跟著：縫合上，怪留著、沒獎勵。 */
    private void closeRift(final Event event, final List<Player> players, final long now) {
        event.done = true;
        event.restoreTick = now + Math.round(config.abyss().linger() * 20);
        final Location head = event.head();
        head.getWorld().playSound(head, Sound.BLOCK_RESPAWN_ANCHOR_DEPLETE, 1.0f, 0.5f);
        if (event.label != null && event.label.isValid()) {
            event.label.text(plugin.text().parse("<dark_gray>深淵裂縫（合上了：沒有人跟著）"));
        }
        plugin.getSLF4JLogger().info("地圖 #{}：深淵裂縫沒人跟著，在第 {}／{} 格合上", event.instance.id(), event.riftCarved, event.rift.size());
    }

    /** 小 Boss 倒了：寶箱打開，radius 格內的人各拿一份（掉落表 rolls 次＋通貨＋專屬傳奇＋入場券）。 */
    private void openAbyssChest(final Event event, final List<Player> players, final long now) {
        event.done = true;
        event.restoreTick = now + Math.round(config.abyss().linger() * 20);
        countEvent(event, players);
        final MapEventConfig.Abyss settings = config.abyss();
        final MapInstance instance = event.instance;
        final RandomGenerator random = service.random();
        final DropTable drops = plugin.monsterModule().drops();
        final int level = instance.level();
        final Location at = event.chest == null ? event.head() : event.chest.getLocation().add(0.5, 0, 0.5);
        final MonsterData data = new MonsterData(level, MonsterRarity.RARE, List.of(), "深淵裂縫");
        final tw.exilecore.currency.CurrencyType shard = plugin.currencies().repository().get(settings.shardCurrency());
        int takers = 0;
        for (final Player player : players) {
            if (player.getLocation().distanceSquared(at) > settings.radius() * settings.radius()) {
                continue;
            }
            final StatSheet sheet = plugin.players().sheet(player);
            final double month = plugin.events() == null ? 0 : plugin.events().quantityBonus();
            final double monthRarity = plugin.events() == null ? 0 : plugin.events().rarityBonus();
            final double quantity = settings.chestQuantity() + instance.stats().quantity() / 100.0 + tw.exilecore.loot.LootBonus.personalQuantity(plugin, player) + month;
            final double rarity = settings.chestRarity() + instance.stats().rarity() / 100.0 + tw.exilecore.loot.LootBonus.personalRarity(plugin, player) + monthRarity;
            final List<DropTable.Drop> rolled = new ArrayList<>();
            for (int i = 0; i < settings.rolls(); i++) {
                rolled.addAll(drops.roll(level, MonsterRarity.RARE, quantity + drops.serverQuantity(), rarity + drops.serverRarity(),
                        plugin.items().generator(), plugin.currencies().repository(), random, plugin.characters().lootBias(player)));
            }
            // 深淵的招牌通貨（shard_currency；currencies.yml 沒有這個代號就照掉落表抽）
            final int shards = settings.minShards() + (settings.maxShards() > settings.minShards() ? random.nextInt(settings.maxShards() - settings.minShards() + 1) : 0);
            for (int i = 0; i < shards; i++) {
                final tw.exilecore.currency.CurrencyType currency = shard != null ? shard
                        : drops.rollCurrency(level, plugin.currencies().repository(), random, rarity + drops.serverRarity());
                if (currency != null) {
                    rolled.add(DropTable.Drop.currency(currency));
                }
            }
            // 深淵裂縫的專屬傳奇（有份的人各擲一次）
            rolled.addAll(eventUniques(MapEventConfig.Kind.ABYSS, instance, player, random));
            // 2026-11-04 照站長「淵印指環放進深淵」：有份的人各擲一次（魔法或稀有、未鑑定）
            final DropTable.Drop signet = eventBase(MapEventConfig.Kind.ABYSS, instance, player, random);
            if (signet != null) {
                rolled.add(signet);
            }
            final long gold = drops.rollGold(level, MonsterRarity.RARE, quantity, random) * Math.max(1, settings.rolls());
            deliver(player, at, data, rolled, gold);
            rollTicket(MapEventConfig.Kind.ABYSS, player, at, event.bonus ? BONUS_TICKET : 1.0); // 2026-10-18：入場券
            takers++;
        }
        tw.exilecore.skill.Fx.spawn(at.getWorld(), Particle.TOTEM_OF_UNDYING, at.getX(), at.getY() + 1, at.getZ(), 40, 0.6, 0.6, 0.6, 0.15);
        at.getWorld().playSound(at, Sound.BLOCK_ENDER_CHEST_OPEN, 1.0f, 0.8f);
        if (event.label != null && event.label.isValid()) {
            event.label.text(plugin.text().parse("<dark_gray>深淵裂縫（已開）"));
        }
        plugin.getSLF4JLogger().info("地圖 #{}：深淵裂縫走到盡頭（{} 格），{} 人拿到東西", instance.id(), event.rift.size(), takers);
    }

    /**
     * 2026-10-18：事件巔峰的入場券——事件完成時「有份的人」各擲一次 map_events.yml 的 ticket.chance（預設 2%）掉一張 ticket.currency。
     * 固定機率：不吃物品稀有度、星圖天賦、每月加成（跟專屬傳奇的 {@link #eventUniques} 不一樣）。掉了走一般的掉落流程
     * （撿取、通貨袋；流水帳由撿取那邊記）並在聊天室提醒一句。
     */
    void rollTicket(final MapEventConfig.Kind kind, final Player player, final Location at) {
        rollTicket(MapEventConfig.uniqueSource(kind), player, at, 1.0);
    }

    /** 同上；factor 乘在入場券機率上（保證多出來的事件 {@link #BONUS_TICKET}）。 */
    void rollTicket(final MapEventConfig.Kind kind, final Player player, final Location at, final double factor) {
        rollTicket(MapEventConfig.uniqueSource(kind), player, at, factor);
    }

    /** 同上，用事件的來源代號（祭壇 "ritual"、幻霧 "delirium" 不是 {@link MapEventConfig.Kind}）。 */
    void rollTicket(final String source, final Player player, final Location at) {
        rollTicket(source, player, at, 1.0);
    }

    /**
     * 2026-10-21：星圖天賦、石板、單一信仰保證多出來的事件，入場券機率只算這個比例
     * （以前點滿事件線一張圖多 8 個事件，入場券快 3～4 倍；流寇線＋單一信仰約 14 倍）。
     */
    static final double BONUS_TICKET = 0.3;

    void rollTicket(final String source, final Player player, final Location at, final double factor) {
        rollCard(source, player, at); // 2026-10-19 星諭卡：完成事件也擲一次（跟入場券分開擲）
        rollTablet(source, player, at); // 2026-10-21 先驅石板：完成事件 3%（星圖天賦可以加）掉那種事件的石板
        final MapEventConfig.Ticket ticket = config.ticket(source);
        if (ticket == null || ticket.chance() <= 0 || plugin.currencies() == null || player == null || !player.isConnected()) {
            return;
        }
        final MapInstance instance = service.instanceAt(at);
        if (instance != null && ticketTaken.getOrDefault(instance.id(), java.util.Set.of()).contains(player.getUniqueId())) {
            return; // 2026-10-21：入場券每張圖每人最多 1 張
        }
        if (service.random().nextDouble() >= ticket.chance() * factor) {
            return;
        }
        final tw.exilecore.currency.CurrencyType type = plugin.currencies().repository().get(ticket.currency());
        if (type == null) {
            plugin.getSLF4JLogger().warn("map_events.yml：{} 的入場券 {} 不在 currencies.yml 裡，沒有掉", source, ticket.currency());
            return;
        }
        if (instance != null) {
            ticketTaken.computeIfAbsent(instance.id(), id -> new java.util.HashSet<>()).add(player.getUniqueId());
        }
        final int level = instance != null ? instance.level() : plugin.players().get(player).level();
        final List<DropTable.Drop> rolled = new java.util.ArrayList<>();
        rolled.add(DropTable.Drop.currency(type));
        deliver(player, at, new MonsterData(level, MonsterRarity.RARE, List.of(), MapEventConfig.sourceName(source)), rolled, 0);
        player.sendMessage(plugin.text().prefixed("<#c77dff>✦ 掉落：<white>" + ItemRenderer.escape(type.name())
                + "<gray>（地圖裝置的「事件巔峰」用它開 Boss）"));
        player.playSound(player.getLocation(), Sound.UI_TOAST_CHALLENGE_COMPLETE, 0.6f, 1.4f);
        plugin.getSLF4JLogger().info("[入場券] {} 完成{}事件掉了 {}", player.getName(), MapEventConfig.sourceName(source), type.name());
    }

    /** 2026-10-21：完成地圖事件時擲一片那種事件的先驅石板（AtlasTablets）：固定 3%，星圖天賦 atlas.tablet.drop 加成。 */
    private void rollTablet(final String source, final Player player, final Location at) {
        final AtlasTablets.Kind kind = AtlasTablets.Kind.ofSource(source);
        if (kind == null || plugin.currencies() == null || player == null || !player.isConnected()) {
            return;
        }
        final MapInstance instance = service.instanceAt(at);
        final double bonus = instance == null ? 0 : instance.atlas().increased("atlas.tablet.drop");
        if (service.random().nextDouble() >= AtlasTablets.EVENT_DROP_CHANCE * (1 + bonus)) {
            return;
        }
        final tw.exilecore.currency.CurrencyType type = plugin.currencies().repository().get(kind.currencyId());
        if (type == null) {
            return;
        }
        final int level = instance != null ? instance.level() : plugin.players().get(player).level();
        final List<DropTable.Drop> rolled = new java.util.ArrayList<>();
        rolled.add(DropTable.Drop.currency(type));
        deliver(player, at, new MonsterData(level, MonsterRarity.RARE, List.of(), MapEventConfig.sourceName(source)), rolled, 0);
        player.sendMessage(plugin.text().prefixed("<#d8c8a0>✦ 掉落：<white>" + ItemRenderer.escape(type.name()) + "<gray>（插進星圖上完成的塔）"));
        player.playSound(player.getLocation(), Sound.UI_TOAST_CHALLENGE_COMPLETE, 0.6f, 1.3f);
    }

    /** 2026-10-19：完成地圖事件時擲一張那個事件的星諭卡（cards.yml 的 event:）；擲到照一般掉落交給他。 */
    private void rollCard(final String source, final Player player, final Location at) {
        if (plugin.cards() == null || player == null || !player.isConnected()) {
            return;
        }
        final tw.exilecore.currency.CurrencyType card = plugin.cards().rollEvent(source, tw.exilecore.card.CardService.tierAt(plugin, at));
        if (card == null) {
            return;
        }
        final MapInstance instance = service.instanceAt(at);
        final int level = instance != null ? instance.level() : plugin.players().get(player).level();
        final List<DropTable.Drop> rolled = new java.util.ArrayList<>();
        rolled.add(DropTable.Drop.currency(card));
        deliver(player, at, new MonsterData(level, MonsterRarity.RARE, List.of(), MapEventConfig.sourceName(source)), rolled, 0);
    }

    /** 事件結束 linger 秒後（或實例關閉時）：縫的方塊還原、寶箱收掉。 */
    private void restoreRift(final Event event) {
        event.restoreTick = 0;
        if (event.chest != null) {
            blocks.remove(key(event.chest));
            if (event.chest.getType() == Material.CHEST) {
                event.chest.setType(Material.AIR, false);
            }
            event.chest = null;
        }
        for (final Map.Entry<Block, BlockData> entry : event.riftBlocks.entrySet()) {
            final Block block = entry.getKey();
            if (block.getWorld().isChunkLoaded(block.getX() >> 4, block.getZ() >> 4)) {
                block.setBlockData(entry.getValue(), false);
            }
        }
        event.riftBlocks.clear();
        if (event.label != null && event.label.isValid()) {
            event.label.remove();
            event.label = null;
        }
    }

    // ---- 保險箱（2026-10-18）----

    /** 右鍵保險箱：身上要有一張劫盜合約（通貨袋、背包、倉庫都算），吃掉一張開始開鎖。 */
    private void useSafe(final Event event, final Player player) {
        final MapEventConfig.Safe settings = config.safe();
        if (event.done) {
            tw.exilecore.gui.ActionBar.send(player, plugin.text().parse(event.wavesSpawned < 0 ? "<gray>這個保險箱開鎖失敗了（沒人守著）" : "<gray>這個保險箱已經開過了"));
            return;
        }
        if (event.started) {
            tw.exilecore.gui.ActionBar.send(player, plugin.text().parse(SAFE_COLOR + "開鎖中：" + Math.round(event.progressTicks / 20.0) + "／" + Math.round(settings.unlockSeconds())
                    + " 秒<gray>（站在保險箱 " + Math.round(settings.radius()) + " 格內進度才會走）"));
            return;
        }
        if (event.instance.state() != MapInstance.State.OPEN) {
            return;
        }
        final tw.exilecore.currency.CurrencyType contract = plugin.currencies().repository().get(tw.exilecore.heist.HeistService.CONTRACT);
        // 2026-10-18：地圖裡通貨在通貨袋、快捷列又被技能占住，拿在手上右鍵做不到——改成跟劫盜一樣：身上（通貨袋、背包、倉庫）有合約就直接扣一張
        if (contract == null) {
            tw.exilecore.gui.ActionBar.send(player, plugin.text().parse("<red>currencies.yml 沒有劫盜合約"));
            return;
        }
        if (service.countCurrency(player, contract) < 1) {
            tw.exilecore.gui.ActionBar.send(player, plugin.text().parse("<red>要有一張" + ItemRenderer.escape(contract.name()) + "才能開鎖（會吃掉一張；通貨袋、背包、倉庫裡的都算）"));
            return;
        }
        // 流水帳記 consumed（來源 HEIST）：takeCurrency 裡記
        service.takeCurrency(player, contract, 1, tw.exilecore.economy.EconomySources.HEIST);
        final long now = plugin.getServer().getCurrentTick();
        event.safeOpener = player.getUniqueId(); // 2026-10-18
        event.started = true;
        event.startTick = now;
        event.lastTick = now;
        event.progressTicks = 0;
        event.pausedTicks = 0;
        event.nextWaveTick = 0; // 第一波馬上來
        event.center.getWorld().playSound(event.center, Sound.BLOCK_IRON_DOOR_OPEN, 1.0f, 0.6f);
        event.center.getWorld().playSound(event.center, Sound.ENTITY_EVOKER_PREPARE_SUMMON, 1.0f, 0.8f);
        for (final Player near : players(event.instance)) {
            if (near.getLocation().distanceSquared(event.center) <= settings.rewardRadius() * settings.rewardRadius()) {
                tw.exilecore.gui.Titles.show(near, plugin.text().parse(SAFE_COLOR + "開鎖開始"),
                        plugin.text().parse("<red>守住保險箱 " + Math.round(settings.unlockSeconds()) + " 秒"), Title.Times.times(Duration.ofMillis(100), Duration.ofMillis(2000), Duration.ofMillis(500)));
            }
        }
        plugin.getSLF4JLogger().info("地圖 #{}：{} 用一張{}開始開保險箱", event.instance.id(), player.getName(), contract.name());
    }

    /** 2026-10-18：保險箱開鎖中（吃了合約、還沒開完也沒失敗）就出錯／重載／實例關掉：把那張合約退給開鎖的人。 */
    private void refundSafeContract(final Event event) {
        if (event.kind != MapEventConfig.Kind.SAFE || !event.started || event.done || event.safeOpener == null) {
            return;
        }
        final UUID opener = event.safeOpener;
        event.safeOpener = null;
        final tw.exilecore.currency.CurrencyType contract = plugin.currencies().repository().get(tw.exilecore.heist.HeistService.CONTRACT);
        service.refundLater(opener, contract, 1, tw.exilecore.economy.EconomySources.HEIST); // 2026-11-02：合約扣的時候記 HEIST
        plugin.getSLF4JLogger().info("地圖 #{}：保險箱開鎖中被中斷，合約退給 {}", event.instance.id(), opener);
    }

    private void tickSafe(final Event event, final List<Player> players, final long now) {
        if (!event.started || event.done) {
            return;
        }
        final MapEventConfig.Safe settings = config.safe();
        final long delta = event.lastTick <= 0 ? 0 : Math.max(0, now - event.lastTick);
        event.lastTick = now;
        boolean guarded = false;
        for (final Player player : players) {
            if (player.getLocation().distanceSquared(event.center) <= settings.radius() * settings.radius()) {
                guarded = true;
                break;
            }
        }
        event.monsters.removeIf(monster -> !monster.isValid() || monster.isDead());
        if (!guarded) {
            event.pausedTicks += delta;
            if (event.pausedTicks >= settings.grace() * 20) {
                event.done = true;
                event.wavesSpawned = -1; // 失敗的記號（合約沒了）
                removeDoor(event);
                event.center.getWorld().playSound(event.center, Sound.BLOCK_IRON_DOOR_CLOSE, 1.0f, 0.5f);
                if (event.label != null && event.label.isValid()) {
                    event.label.text(plugin.text().parse("<dark_gray>保險箱（開鎖失敗：沒人守著）"));
                }
                plugin.getSLF4JLogger().info("地圖 #{}：保險箱沒人守著，開鎖失敗", event.instance.id());
                return;
            }
            if (event.label != null && event.label.isValid()) {
                event.label.text(plugin.text().parse(SAFE_COLOR + "保險箱<newline><red>沒人守著，進度暫停：" + Math.round((settings.grace() * 20 - event.pausedTicks) / 20.0) + " 秒後失敗"));
            }
            return;
        }
        event.pausedTicks = 0;
        event.progressTicks += delta;
        final double total = settings.unlockSeconds() * 20;
        if (event.progressTicks >= total) {
            openSafe(event, players, now);
            return;
        }
        if (event.label != null && event.label.isValid()) {
            event.label.text(plugin.text().parse(SAFE_COLOR + "保險箱<newline><gray>開鎖中 " + Math.round(event.progressTicks / 20.0) + "／"
                    + Math.round(settings.unlockSeconds()) + " 秒（還有 " + event.monsters.size() + " 隻）"));
        }
        tw.exilecore.skill.Fx.spawn(event.center.getWorld(), Particle.ELECTRIC_SPARK, event.center.getX(), event.center.getY() + 1.2, event.center.getZ(), 3, 0.3, 0.3, 0.3, 0.02);
        // 每 wave_seconds 一波（照進度算，暫停時不湧）
        final int waves = Math.max(1, (int) Math.ceil(settings.unlockSeconds() / settings.waveSeconds()));
        if (event.wavesSpawned < waves && event.progressTicks >= event.wavesSpawned * settings.waveSeconds() * 20) {
            final boolean last = event.wavesSpawned == waves - 1;
            event.wavesSpawned++;
            spawnSafeWave(event, players, last);
        }
    }

    /** 一波怪從房間邊緣湧進來：每波一隻魔法怪、最後一波一隻稀有怪。 */
    private void spawnSafeWave(final Event event, final List<Player> players, final boolean last) {
        final MapEventConfig.Safe settings = config.safe();
        final RandomGenerator random = service.random();
        final MapInstance instance = event.instance;
        final ZoneConfig.SpawnEntry entry = service.spawner().chooseMonster(instance.map().monsters(), random);
        if (entry == null) {
            return;
        }
        final int count = settings.minWave() + (settings.maxWave() > settings.minWave() ? random.nextInt(settings.maxWave() - settings.minWave() + 1) : 0);
        final List<String> magicMods = plugin.monsters().rollMods(MonsterRarity.MAGIC, instance.level());
        Player target = null;
        for (final Player player : players) {
            if (player.getLocation().distanceSquared(event.center) <= settings.radius() * settings.radius()) {
                target = player;
                break;
            }
        }
        for (int i = 0; i < count; i++) {
            final MonsterRarity rarity = i == 0 ? (last ? MonsterRarity.RARE : MonsterRarity.MAGIC) : MonsterRarity.NORMAL;
            final LivingEntity monster = service.spawner().spawn(instance, entry, around(event, 6.0, 10.0, random), rarity,
                    rarity == MonsterRarity.MAGIC ? magicMods : rarity == MonsterRarity.RARE ? null : List.of());
            if (monster != null) {
                event.monsters.add(monster);
                if (target != null && monster instanceof Mob mob) {
                    mob.setTarget(target);
                }
                tw.exilecore.skill.Fx.spawn(monster.getWorld(), Particle.CLOUD, monster.getLocation().add(0, 1, 0), 10, 0.3, 0.5, 0.3, 0.02);
            }
        }
        event.center.getWorld().playSound(event.center, Sound.ENTITY_PILLAGER_CELEBRATE, 1.0f, 0.7f);
    }

    /** 開鎖完成：reward_radius 格內的人各拿一份（掉落表 rolls 次＋通貨＋機率一張劫盜藍圖）。 */
    private void openSafe(final Event event, final List<Player> players, final long now) {
        event.done = true;
        countEvent(event, players);
        final MapEventConfig.Safe settings = config.safe();
        final MapInstance instance = event.instance;
        final RandomGenerator random = service.random();
        final DropTable drops = plugin.monsterModule().drops();
        final int level = instance.level();
        final MonsterData data = new MonsterData(level, MonsterRarity.RARE, List.of(), "保險箱");
        final tw.exilecore.currency.CurrencyType blueprint = plugin.currencies().repository().get(tw.exilecore.heist.HeistService.BLUEPRINT);
        removeDoor(event);
        int takers = 0;
        for (final Player player : players) {
            if (player.getLocation().distanceSquared(event.center) > settings.rewardRadius() * settings.rewardRadius()) {
                continue;
            }
            final StatSheet sheet = plugin.players().sheet(player);
            final double month = plugin.events() == null ? 0 : plugin.events().quantityBonus();
            final double monthRarity = plugin.events() == null ? 0 : plugin.events().rarityBonus();
            final double quantity = settings.quantityBonus() + instance.stats().quantity() / 100.0 + tw.exilecore.loot.LootBonus.personalQuantity(plugin, player) + month;
            final double rarity = settings.rarityBonus() + instance.stats().rarity() / 100.0 + tw.exilecore.loot.LootBonus.personalRarity(plugin, player) + monthRarity;
            final List<DropTable.Drop> rolled = new ArrayList<>();
            final int rolls = settings.minRolls() + (settings.maxRolls() > settings.minRolls() ? random.nextInt(settings.maxRolls() - settings.minRolls() + 1) : 0);
            for (int i = 0; i < rolls; i++) {
                rolled.addAll(drops.roll(level, MonsterRarity.RARE, quantity + drops.serverQuantity(), rarity + drops.serverRarity(),
                        plugin.items().generator(), plugin.currencies().repository(), random, plugin.characters().lootBias(player)));
            }
            // 通貨（照掉落表的通貨池抽，等級＝地圖等級：合約沒有階級）
            final int currencies = settings.minCurrency() + (settings.maxCurrency() > settings.minCurrency() ? random.nextInt(settings.maxCurrency() - settings.minCurrency() + 1) : 0);
            for (int i = 0; i < currencies; i++) {
                final tw.exilecore.currency.CurrencyType currency = drops.rollCurrency(level, plugin.currencies().repository(), random, rarity + drops.serverRarity());
                if (currency != null) {
                    rolled.add(DropTable.Drop.currency(currency));
                }
            }
            if (blueprint != null && random.nextDouble() < settings.blueprintChance()) {
                rolled.add(DropTable.Drop.currency(blueprint));
            }
            final long gold = drops.rollGold(level, MonsterRarity.RARE, quantity, random) * Math.max(1, rolls);
            deliver(player, event.center, data, rolled, gold);
            takers++;
        }
        tw.exilecore.skill.Fx.spawn(event.center.getWorld(), Particle.TOTEM_OF_UNDYING, event.center.getX(), event.center.getY() + 1, event.center.getZ(), 40, 0.6, 0.6, 0.6, 0.15);
        event.center.getWorld().playSound(event.center, Sound.BLOCK_ENDER_CHEST_OPEN, 1.0f, 1.2f);
        if (event.label != null && event.label.isValid()) {
            event.label.text(plugin.text().parse("<dark_gray>保險箱（已開）"));
        }
        plugin.getSLF4JLogger().info("地圖 #{}：保險箱開了，{} 人拿到東西", instance.id(), takers);
    }

    /** 鐵門拿掉（開了或失敗了都不用再點）。 */
    private void removeDoor(final Event event) {
        final Block base = event.center.getBlock();
        for (final Block door : List.of(base.getRelative(0, 0, 1), base.getRelative(0, 1, 1))) {
            if (door.getType() == Material.IRON_DOOR) {
                door.setType(Material.AIR, false);
            }
        }
    }

    // ---- 標籤與清理 ----

    private TextDisplay label(final Event event) {
        final double height = switch (event.kind) {
            case SHRINE -> 3.6;
            case STRONGBOX -> 1.6;
            case SAFE -> 2.8; // 2026-10-18
            default -> 1.4;
        };
        final Location at = (event.kind == MapEventConfig.Kind.ABYSS ? event.head() : event.center).clone().add(0, height, 0); // 2026-10-18：縫的標籤跟著尾端
        final TextDisplay display = at.getWorld().spawn(at, TextDisplay.class, created -> {
            created.setBillboard(Display.Billboard.CENTER);
            created.setPersistent(false);
            created.setSeeThrough(false);
            created.setShadowed(true);
        });
        display.text(plugin.text().parse(switch (event.kind) {
            case SHRINE -> event.shrine.color() + "✦ " + ItemRenderer.escape(event.shrine.name()) + "<newline><gray>右鍵石柱：附近的人一起拿 "
                    + Math.round(config.shrineDuration() * (1 + event.instance.atlas().increased("atlas.shrine.duration"))) + " 秒增益";
            case STRONGBOX -> "<#f5b942>" + ItemRenderer.escape(event.box.name()) + "<newline><gray>右鍵打開（會跳出一群怪，殺光才開）";
            case BREACH -> breachColor(event) + ItemRenderer.escape(event.title()) // 2026-11-12：領主的名字、顏色，說明照 PoE1 的玩法
                    + "<newline><gray>踩進去開啟：巢生怪從邊緣湧出，殺怪延長時間；進度滿了變成穩定裂隙（掉的東西多 "
                    + Math.round(config.breachQuantity() * 100) + "%）";
            case EXILE -> "";
            // 2026-10-18
            case ABYSS -> ABYSS_COLOR + "深淵裂縫<newline><gray>走近它：地會裂開往前延伸，跟著縫走到盡頭（縫裡的怪掉的東西多 "
                    + Math.round(config.abyss().quantityBonus() * 100) + "%）";
            case SAFE -> SAFE_COLOR + "保險箱<newline><gray>右鍵（身上要有劫盜合約）：吃掉一張，守著它開鎖 " + Math.round(config.safe().unlockSeconds())
                    + " 秒（會一波一波湧怪）";
        }));
        return display;
    }

    private void dispose(final Event event) {
        refundSafeContract(event); // 2026-10-18：重載／實例關閉時開鎖中的保險箱：合約退回去
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
        event.breachSpawned.clear();
        event.stableMonsters.clear();
        event.breachKillers.clear();
        for (final BreachHand hand : event.hands) { // 2026-11-12：緊握之手的字
            if (hand.label != null && hand.label.isValid()) {
                hand.label.remove();
            }
        }
        event.hands.clear();
        if (event.kind == MapEventConfig.Kind.STRONGBOX) {
            boxes.values().removeIf(other -> other == event);
        }
        if (event.exile != null) {
            exiles.remove(event.exile.getUniqueId());
        }
        // 2026-10-18：深淵裂縫的方塊還原、保險箱的方塊不管（跟寶箱一樣由清格子的人清）
        if (event.kind == MapEventConfig.Kind.ABYSS) {
            try {
                restoreRift(event);
            } catch (final RuntimeException e) {
                plugin.getSLF4JLogger().warn("地圖 #{} 還原深淵裂縫的方塊出錯", event.instance.id(), e);
            }
        }
        blocks.values().removeIf(other -> other == event);
    }

    /** 這張圖已經拿過入場券的人（實例 → 玩家；2026-10-21 每張圖每人最多 1 張）。 */
    private final Map<Integer, java.util.Set<java.util.UUID>> ticketTaken = new java.util.HashMap<>();

    /** 實例關閉：標籤、刷出來的怪都收掉（方塊由清格子的人清）。 */
    void clear(final MapInstance instance) {
        ticketTaken.remove(instance.id());
        rituals.clear(instance);
        delirium.clear(instance);
        twists.clear(instance);
        fogTrial.clear(instance);
        ultimatum.clear(instance);
        legion.clear(instance);
        delve.clear(instance);
        final List<Event> events = byInstance.remove(instance.id());
        if (events != null) {
            for (final Event event : events) {
                dispose(event);
            }
        }
    }

    void clearAll() {
        ticketTaken.clear();
        rituals.clearAll();
        delirium.clearAll();
        twists.clearAll();
        fogTrial.clearAll();
        ultimatum.clearAll();
        legion.clearAll();
        delve.clearAll();
        for (final List<Event> events : byInstance.values()) {
            for (final Event event : events) {
                dispose(event);
            }
        }
        byInstance.clear();
        boxes.clear();
        exiles.clear();
        blocks.clear();
    }

    /**
     * 2026-11-02 照站長（C 組小地圖）：這張圖的事件標記（位置、種類、名稱、狀態），給模組的小地圖畫圖示；這次之前只有 {@link #summary} 的文字。
     * 祭壇、幻霧不在這裡（它們自己管位置）。
     */
    List<tw.exilecore.clientmod.FeedProtocol.Marker> markers(final MapInstance instance) {
        final List<tw.exilecore.clientmod.FeedProtocol.Marker> out = new ArrayList<>();
        final List<Event> events = byInstance.get(instance.id());
        if (events == null) {
            return out;
        }
        for (int i = 0; i < events.size(); i++) {
            final Event event = events.get(i);
            if (event.center == null) {
                continue;
            }
            out.add(new tw.exilecore.clientmod.FeedProtocol.Marker(i, event.center.getX(), event.center.getZ(),
                    event.kind.name().toLowerCase(Locale.ROOT), event.title(), markerColor(event.kind), event.done ? 2 : event.started ? 1 : 0));
        }
        return out;
    }

    /** 小地圖上事件圖示的顏色（跟各事件的粒子、字同一個顏色）。 */
    static int markerColor(final MapEventConfig.Kind kind) {
        return switch (kind) {
            case SHRINE -> 0x9FE8C8;
            case STRONGBOX -> 0xF5B942;
            case BREACH -> 0xC48BFF;
            case EXILE -> 0xFF7070;
            case ABYSS -> 0x8A5CFF;
            case SAFE -> 0xC8D0D8;
        };
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
        final String trial = ultimatum.summary(instance);
        if (trial != null) {
            parts.add(trial);
        }
        final String monolith = legion.summary(instance);
        if (monolith != null) {
            parts.add(monolith);
        }
        final String twist = twists.summary(instance);
        final String base = parts.isEmpty() ? "沒有事件" : String.format(Locale.ROOT, "事件 %d 個：%s", parts.size(), String.join("、", parts));
        return twist == null ? base : base + "；" + twist;
    }

    /** 這個實體是不是某個事件刷出來的（守衛、裂隙怪、流寇、儀式的怪、幻霧怪）。 */
    boolean isEventMonster(final Entity entity) {
        return entity.getPersistentDataContainer().has(quantityKey) || entity.getPersistentDataContainer().has(abyssQuantityKey) // 2026-10-18
                || exiles.containsKey(entity.getUniqueId()) || rituals.isRitualMonster(entity) || delirium.isDeliriumMonster(entity) || ultimatum.isUltimatumMonster(entity) || legion.isLegionMonster(entity);
    }
}
