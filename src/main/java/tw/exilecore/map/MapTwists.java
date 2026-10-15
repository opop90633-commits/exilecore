package tw.exilecore.map;

import net.kyori.adventure.bossbar.BossBar;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.title.Title;
import org.bukkit.GameMode;
import org.bukkit.Location;
import org.bukkit.Material;
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
import org.bukkit.potion.PotionEffect;
import org.bukkit.potion.PotionEffectType;
import tw.exilecore.ExileCore;
import tw.exilecore.item.ItemData;
import tw.exilecore.item.ItemRenderer;
import tw.exilecore.monster.DropTable;
import tw.exilecore.monster.MonsterData;
import tw.exilecore.monster.MonsterRarity;
import tw.exilecore.stats.StatKeys;
import tw.exilecore.stats.StatSheet;
import tw.exilecore.zone.ZoneConfig;

import java.time.Duration;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.random.RandomGenerator;

/**
 * 地圖變化（2026-10）：讓同一張圖每次打起來不一樣。設定在 map_events.yml 的 {@code twists}（{@link MapEventConfig.Twists}），
 * 每一種都有自己的機率，寫 0 就關掉。地圖蓋好時（{@link MapEvents#place} 最後）決定這張圖有哪些變化：
 * <ul>
 *   <li><b>房間主題</b>：沒有事件的房間照機率變成精英巢穴（只有稀有怪）、怪物潮（一大群普通怪）、黑暗房（看不清楚、怪物稀有度高）、
 *       寶庫（沒有怪群，只有一個寶箱）。走進去時畫面中間提示。刷怪由 {@link MapSpawner#spawnPack} 問 {@link #packTheme}。</li>
 *   <li><b>怪物混搭</b>：照機率從別張地圖借一個族群，每一群照 mix_share 的機率用它（{@link #chooseEntry}）。</li>
 *   <li><b>流浪精英</b>：一隻生命很厚、掉落很多的稀有怪，有人走近起始房間才出現，沒在打人時在房間之間走來走去。</li>
 *   <li><b>事件疊加</b>：{@link MapEvents#place} 照 stack_chance 在同一個房間多放一個不同的事件。</li>
 *   <li><b>Boss 變體</b>（一般地圖）：強化（生命 ×1.5、傷害 ×1.25、掉落 +50%）或雙生（開打時多一隻分身，兩隻生命各 ×0.6）。</li>
 *   <li><b>Boss 房干擾</b>（一般地圖）：開打後場地危害（墜星、冥河之手、流火）、援軍、或守護之柱（開打先張護盾）。</li>
 *   <li><b>命運岔路</b>：兩根石碑二選一——血路（怪物群 +50%、物品數量 +30%）或王道（Boss 生命傷害 +30%、Boss 掉落 +50%）。</li>
 *   <li><b>詛咒寶匣</b>：接受就馬上拿一份好東西，但之後刷的怪更硬；拒絕就消失。</li>
 *   <li><b>限時挑戰</b>：第一次走進普通房間時照機率觸發：房間裡的怪一次全出來，時間內殺光給獎勵。</li>
 *   <li><b>隱藏房</b>：一間死路房間的入口被裂開的牆封住，敲幾下打破，裡面有精英和寶箱。</li>
 * </ul>
 * 只放在一般的地圖（回響、試煉、極境、巔峰、金庫之主不放）；終局的 Boss 不會有變體與干擾。
 */
final class MapTwists implements Listener {

    /** 房間主題。 */
    enum Theme {
        ELITE("精英巢穴", "<#ffd27f>", "這裡只有稀有怪"),
        SWARM("怪物潮", "<#ff7070>", "一大群怪湧上來"),
        DARK("黑暗房", "<#9a9ad0>", "看不清楚，怪物更強"),
        VAULT("寶庫", "<#f5b942>", "沒有怪群，只有一個寶箱");

        final String name;
        final String color;
        final String hint;

        Theme(final String name, final String color, final String hint) {
            this.name = name;
            this.color = color;
            this.hint = hint;
        }

        static Theme parse(final String id) {
            return id == null ? null : switch (id) {
                case "elite" -> ELITE;
                case "swarm" -> SWARM;
                case "dark" -> DARK;
                case "vault" -> VAULT;
                default -> null;
            };
        }
    }

    /** Boss 變體。 */
    enum BossVariant {
        EMPOWERED("強化", 1.5, 1.25, 0.5),
        TWIN("雙生", 0.6, 1.0, 0.25);

        final String name;
        final double life;
        final double damage;
        final double loot;

        BossVariant(final String name, final double life, final double damage, final double loot) {
            this.name = name;
            this.life = life;
            this.damage = damage;
            this.loot = loot;
        }

        static BossVariant parse(final String id) {
            return "empowered".equals(id) ? EMPOWERED : "twin".equals(id) ? TWIN : null;
        }
    }

    /** Boss 房的干擾。 */
    enum BossRoom {
        METEORS("墜星", "hazard_star_meteor", 7),
        HANDS("冥河之手", "hazard_grave_hands", 9),
        EMBERS("流火", "hazard_ember_orbs", 10),
        REINFORCE("援軍", null, 0),
        PILLAR("守護之柱", null, 0);

        final String name;
        /** 場地危害用的招式（boss_skills.yml）；null = 不是場地危害。 */
        final String hazard;
        final double every;

        BossRoom(final String name, final String hazard, final double every) {
            this.name = name;
            this.hazard = hazard;
            this.every = every;
        }

        static BossRoom parse(final String id) {
            return id == null ? null : switch (id) {
                case "meteors" -> METEORS;
                case "hands" -> HANDS;
                case "embers" -> EMBERS;
                case "reinforce" -> REINFORCE;
                case "pillar" -> PILLAR;
                default -> null;
            };
        }
    }

    /** 守護之柱：開打時張的護盾（兩根柱子、40 秒、沒打掉回 25%）。 */
    private static final MapConfig.Shield PILLAR_SHIELD = new MapConfig.Shield(2, "BLAZE", "守護之柱", 4, 40, 25);
    /** 援軍：每幾秒一波、一波幾隻、同時最多幾隻。 */
    private static final long REINFORCE_TICKS = 20 * 20;
    private static final int REINFORCE_WAVE = 3;
    private static final int REINFORCE_MAX = 9;
    /** 雙生不能用的生物（原版的 Boss 行為：凋零的爆炸與血條、巨人沒有 AI、終界龍）。 */
    private static final Set<String> NO_TWIN = Set.of("WITHER", "ENDER_DRAGON", "GIANT", "WARDEN", "ELDER_GUARDIAN");
    /** 岔路、詛咒寶匣：這張圖的怪物群刷出來超過這個比例（或 Boss 已經出現）就關掉，免得清完圖再回來選。 */
    private static final double LATE_SHARE = 0.5;
    /** 隱藏房：敲幾下牆才破。 */
    private static final int HIDDEN_HITS = 6;
    /** 隱藏房：牆最多幾格高。 */
    private static final int HIDDEN_HEIGHT = 5;
    /** 限時挑戰：一張圖最多幾次、房間裡至少要有幾隻怪。 */
    private static final int TIMED_MAX = 2;
    private static final int TIMED_MIN_MONSTERS = 4;
    /** 流浪精英：多久換一個目的地、離起始房間幾格內有人才出現。 */
    private static final long WANDER_TICKS = 12 * 20;
    private static final double WANDER_SPAWN = 40;
    /** 拿獎勵的範圍（格）。 */
    private static final double REWARD_RADIUS = 24;

    /** 一張圖的變化。 */
    private final class State {
        final MapInstance instance;
        /** 房間（layout.rooms() 的位置）→ 主題。 */
        final Map<Integer, Theme> themes = new HashMap<>();
        /** 每一個怪物群在哪個房間（-1 = 走道上）。 */
        int[] packRoom;
        /** 混進來的第二族群（空 = 沒有）。 */
        List<ZoneConfig.SpawnEntry> mixed = List.of();
        String mixedFrom;
        // 流浪精英
        int wandererRoom = -1;
        boolean wandererSpawned;
        boolean wandererDead;
        UUID wanderer;
        int wanderTarget = -1;
        long nextWander;
        // 命運岔路
        Location forkBlood;
        Location forkKing;
        boolean forkChosen;
        // 詛咒寶匣
        Location cursed;
        boolean cursedDone;
        // 限時挑戰
        int timedUsed;
        Timed timed;
        final Set<Integer> visited = new HashSet<>();
        // 隱藏房
        int hiddenRoom = -1;
        final List<Block> hiddenWall = new ArrayList<>();
        boolean hiddenOpen;
        int hiddenHits;
        final Set<UUID> hiddenHinted = new HashSet<>();
        // Boss
        boolean bossFightStarted;
        long nextReinforce;
        final List<LivingEntity> reinforcements = new ArrayList<>();
        UUID twin;
        // 主題房的提示：每個人上次在哪個房間
        final Map<UUID, Integer> lastRoom = new HashMap<>();
        final List<Label> labels = new ArrayList<>();

        State(final MapInstance instance) {
            this.instance = instance;
        }
    }

    /**
     * 浮在石碑、寶匣上的說明（2026-10）：地圖蓋好時區塊還沒有人，當下生出來的文字會跟著區塊卸載消失
     * （岔路的石碑上面空空的）→ 只記位置與文字，有人走近、區塊載入時才生出來，不見了再補。
     */
    private static final class Label {
        final Location at;
        String text;
        TextDisplay entity;
        boolean removed;

        Label(final Location at, final String text) {
            this.at = at;
            this.text = text;
        }

        boolean near(final Location base, final double dy) {
            return at.getWorld().equals(base.getWorld()) && at.distanceSquared(base.clone().add(0.5, dy, 0.5)) < 1;
        }

        void remove() {
            removed = true;
            if (entity != null && entity.isValid()) {
                entity.remove();
            }
            entity = null;
        }
    }

    /** 說明文字：玩家幾格內才生出來。 */
    private static final double LABEL_RANGE = 48;

    /** 進行中的限時挑戰。 */
    private static final class Timed {
        final int room;
        final Location center;
        /** 還沒死的怪（用 UUID 記：區塊卸載又載入後 Bukkit 物件會換，onDeath 才拿掉）。 */
        final Set<UUID> monsters;
        final long startTick;
        final long endTick;
        final BossBar bar;
        final Set<UUID> watching = new HashSet<>();

        Timed(final int room, final Location center, final Set<UUID> monsters, final long startTick, final long endTick, final BossBar bar) {
            this.room = room;
            this.center = center;
            this.monsters = monsters;
            this.startTick = startTick;
            this.endTick = endTick;
            this.bar = bar;
        }
    }

    private final ExileCore plugin;
    private final MapService service;
    private final MapEventConfig config;
    private final MapEvents events;
    private final Map<Integer, State> byInstance = new HashMap<>();
    /** 互動方塊 → 這張圖（岔路的石碑、詛咒寶匣、隱藏房的牆）。 */
    private final Map<Long, State> blocks = new HashMap<>();

    MapTwists(final ExileCore plugin, final MapService service, final MapEventConfig config, final MapEvents events) {
        this.plugin = plugin;
        this.service = service;
        this.config = config;
        this.events = events;
    }

    private MapEventConfig.Twists settings() {
        return config.twists();
    }

    // ---- 放置（地圖蓋好時） ----

    /**
     * 決定這張圖有哪些變化。{@code free} 是還沒放任何事件的房間（會被拿走）。
     * 呼叫的人保證不是回響、試煉、極境、巔峰（{@link MapEvents#place} 一開始就擋掉了）。
     */
    void place(final MapInstance instance, final List<MapLayout.Room> free) {
        final MapEventConfig.Twists settings = settings();
        final RandomGenerator random = service.random();
        final State state = new State(instance);
        final List<MapLayout.Room> rooms = instance.layout().rooms();
        state.packRoom = packRooms(instance.layout());
        byInstance.put(instance.id(), state); // 先登記：下面放到一半出錯，方塊與標籤也收得掉
        final List<String> notes = new ArrayList<>();

        // 隱藏房：一間死路（SIDE）房間，入口被裂開的牆封住，裡面放一個寶箱、怪群變精英
        if (random.nextDouble() < settings.hiddenChance()) {
            final List<MapLayout.Room> sides = new ArrayList<>();
            for (final MapLayout.Room room : free) {
                if (room.kind() == MapLayout.Kind.SIDE) {
                    sides.add(room);
                }
            }
            if (!sides.isEmpty()) {
                final MapLayout.Room room = sides.get(random.nextInt(sides.size()));
                if (sealRoom(state, room)) {
                    free.remove(room);
                    state.hiddenRoom = rooms.indexOf(room);
                    state.themes.put(state.hiddenRoom, Theme.ELITE);
                    events.placeIn(instance, room, MapEventConfig.Kind.STRONGBOX, random);
                    notes.add("隱藏房");
                }
            }
        }
        // 命運岔路：一間主路上的普通房間（先挑前半段的）
        if (random.nextDouble() < settings.forkChance()) {
            final MapLayout.Room room = pickMainRoom(free, rooms, random);
            if (room != null && placeFork(state, room)) {
                free.remove(room);
                notes.add("命運岔路");
            }
        }
        // 詛咒寶匣
        if (random.nextDouble() < settings.cursedChance() && !free.isEmpty()) {
            final MapLayout.Room early = pickMainRoom(free, rooms, random); // 前半段的房間（要早點決定）
            final MapLayout.Room room = early != null ? early : free.get(random.nextInt(free.size()));
            final MapLayout.Spot spot = MapEvents.spotIn(instance.layout(), room);
            if (spot != null) {
                free.remove(room);
                placeCursed(state, spot);
                notes.add("詛咒寶匣");
            }
        }
        // 房間主題（剩下沒事件的房間）
        for (final MapLayout.Room room : List.copyOf(free)) {
            if (random.nextDouble() >= settings.roomThemeChance()) {
                continue;
            }
            final Theme theme = Theme.parse(MapEventConfig.Twists.pick(settings.roomThemes(), random));
            if (theme == null) {
                continue;
            }
            if (theme == Theme.VAULT) {
                if (!events.placeIn(instance, room, MapEventConfig.Kind.STRONGBOX, random)) {
                    continue; // 放不下寶箱就不要變成空房間
                }
                free.remove(room);
            }
            state.themes.put(rooms.indexOf(room), theme);
        }
        if (!state.themes.isEmpty()) {
            notes.add("主題房 " + state.themes.size() + " 間");
        }
        // 怪物混搭：從別張（不是終局的）地圖借一個族群
        if (random.nextDouble() < settings.mixChance()) {
            final Set<String> mine = new HashSet<>();
            for (final ZoneConfig.SpawnEntry entry : instance.map().monsters()) {
                mine.add(entry.id());
            }
            final List<MapConfig.MapDef> donors = new ArrayList<>();
            for (final MapConfig.MapDef other : service.config().all()) {
                if (other.isFinal() || other.monsters().isEmpty() || other.id().equals(instance.map().id())) {
                    continue;
                }
                boolean different = false;
                for (final ZoneConfig.SpawnEntry entry : other.monsters()) {
                    different |= !mine.contains(entry.id());
                }
                if (different) {
                    donors.add(other);
                }
            }
            if (!donors.isEmpty()) {
                final MapConfig.MapDef donor = donors.get(random.nextInt(donors.size()));
                state.mixed = donor.monsters();
                state.mixedFrom = donor.name();
                notes.add("混進" + donor.name() + "的怪");
            }
        }
        // 流浪精英：起始位置是一間普通或死路房間（不是起點、Boss 房、隱藏房）
        if (random.nextDouble() < settings.wandererChance()) {
            final List<Integer> candidates = new ArrayList<>();
            for (int i = 0; i < rooms.size(); i++) {
                final MapLayout.Kind kind = rooms.get(i).kind();
                if ((kind == MapLayout.Kind.NORMAL || kind == MapLayout.Kind.SIDE) && i != state.hiddenRoom) {
                    candidates.add(i);
                }
            }
            if (!candidates.isEmpty()) {
                state.wandererRoom = candidates.get(random.nextInt(candidates.size()));
                notes.add("流浪精英");
            }
        }
        // Boss 變體與 Boss 房干擾：只有一般地圖（終局的 Boss 有自己的機制）
        if (!instance.map().isFinal()) {
            if (random.nextDouble() < settings.bossVariantChance()) {
                BossVariant variant = BossVariant.parse(MapEventConfig.Twists.pick(settings.bossVariants(), random));
                if (variant == BossVariant.TWIN && NO_TWIN.contains(instance.bossDef().type().toUpperCase(java.util.Locale.ROOT))) {
                    variant = BossVariant.EMPOWERED; // 這種生物不能有分身
                }
                instance.twist().bossVariant = variant;
                if (instance.twist().bossVariant != null) {
                    instance.twist().bossLoot += instance.twist().bossVariant.loot;
                    notes.add("Boss " + instance.twist().bossVariant.name);
                }
            }
            if (random.nextDouble() < settings.bossRoomChance()) {
                BossRoom bossRoom = BossRoom.parse(MapEventConfig.Twists.pick(settings.bossRooms(), random));
                if (bossRoom != null && bossRoom.hazard != null) {
                    for (final tw.exilecore.skill.BossHazard own : instance.bossDef().hazards()) {
                        if (own.skill().equals(bossRoom.hazard)) {
                            bossRoom = null; // Boss 本來就有這個場地危害：不再多一份（會落兩倍快）
                            break;
                        }
                    }
                }
                instance.twist().bossRoom = bossRoom;
                if (instance.twist().bossRoom != null) {
                    notes.add("Boss 房" + instance.twist().bossRoom.name);
                }
            }
        }
        if (!notes.isEmpty()) {
            plugin.getSLF4JLogger().info("地圖 #{}（{}）的變化：{}", instance.id(), instance.label(), String.join("、", notes));
        }
    }

    /** 每一個怪物群在哪個房間（layout.rooms() 的位置；走道上 -1）。 */
    private static int[] packRooms(final MapLayout layout) {
        final List<MapLayout.Spot> spots = layout.packSpots();
        final List<MapLayout.Room> rooms = layout.rooms();
        final int[] result = new int[spots.size()];
        for (int i = 0; i < spots.size(); i++) {
            final MapLayout.Room room = layout.roomAt(spots.get(i).x(), spots.get(i).z());
            result[i] = room == null ? -1 : rooms.indexOf(room);
        }
        return result;
    }

    /** 主路上的普通房間，越前面越優先（從起點算第幾間；只看 free 裡的）。 */
    private static MapLayout.Room pickMainRoom(final List<MapLayout.Room> free, final List<MapLayout.Room> rooms,
                                               final RandomGenerator random) {
        final List<MapLayout.Room> normal = new ArrayList<>();
        for (final MapLayout.Room room : free) {
            if (room.kind() == MapLayout.Kind.NORMAL) {
                normal.add(room);
            }
        }
        if (normal.isEmpty()) {
            return null;
        }
        normal.sort(java.util.Comparator.comparingInt(rooms::indexOf)); // rooms 的主路照順序排在前面
        return normal.get(random.nextInt(Math.max(1, (normal.size() + 1) / 2)));
    }

    // ---- 刷怪的掛勾（MapSpawner 問） ----

    /** 這一群在哪種主題的房間（null = 一般）。 */
    Theme packTheme(final MapInstance instance, final int index) {
        final State state = byInstance.get(instance.id());
        if (state == null || state.packRoom == null || index < 0 || index >= state.packRoom.length) {
            return null;
        }
        return state.themes.get(state.packRoom[index]);
    }

    /** 這一群先不要刷（隱藏房還沒打開）。 */
    boolean packHeld(final MapInstance instance, final int index) {
        final State state = byInstance.get(instance.id());
        return state != null && !state.hiddenOpen && state.hiddenRoom >= 0 && state.packRoom != null
                && index >= 0 && index < state.packRoom.length && state.packRoom[index] == state.hiddenRoom;
    }

    /** 混搭：這一群照機率用第二族群；不用回傳 null（照這張圖的）。 */
    ZoneConfig.SpawnEntry chooseEntry(final MapInstance instance, final RandomGenerator random) {
        final State state = byInstance.get(instance.id());
        if (state == null || state.mixed.isEmpty() || random.nextDouble() >= settings().mixShare()) {
            return null;
        }
        return service.spawner().chooseMonster(state.mixed, random);
    }

    double swarmSize() {
        return settings().swarmSize();
    }

    // ---- Boss 的掛勾（MapBosses 問） ----

    /** Boss 房的場地危害（開打之後才有；沒有回傳 null）。 */
    tw.exilecore.skill.BossHazard bossHazard(final MapInstance instance) {
        final BossRoom room = instance.twist().bossRoom;
        final State state = byInstance.get(instance.id());
        if (room == null || room.hazard == null || state == null || !state.bossFightStarted) {
            return null;
        }
        return new tw.exilecore.skill.BossHazard(room.hazard, room.every, 0);
    }

    /** Boss 開打了（有人走進 Boss 房或打到它）：雙生的分身出來、守護之柱張盾、援軍開始計時。 */
    void onBossFight(final MapInstance instance, final LivingEntity boss) {
        final State state = byInstance.get(instance.id());
        if (state == null || state.bossFightStarted) {
            return;
        }
        state.bossFightStarted = true;
        final MapInstance.Twist twist = instance.twist();
        final List<String> lines = new ArrayList<>();
        if (twist.bossVariant == BossVariant.TWIN) {
            spawnTwin(state, boss);
            lines.add("<#ff7070>雙生：" + ItemRenderer.escape(instance.bossDef().name()) + "有一隻分身");
        }
        if (twist.bossRoom != null) {
            switch (twist.bossRoom) {
                case PILLAR -> {
                    service.bosses().raiseShield(instance, boss, PILLAR_SHIELD);
                    lines.add("<#ffd27f>守護之柱：先打掉柱子，Boss 才打得動");
                }
                case REINFORCE -> {
                    state.nextReinforce = plugin.getServer().getCurrentTick() + REINFORCE_TICKS / 2;
                    lines.add("<#ff7070>援軍：怪物會一波一波湧進 Boss 房");
                }
                default -> lines.add("<#c48bff>" + twist.bossRoom.name + "：Boss 房會一直落下" + twist.bossRoom.name);
            }
        }
        if (!lines.isEmpty()) {
            broadcast(instance, "<gray>Boss 房的變化：" + String.join("<gray>；", lines));
        }
    }

    /** Boss 重置（大家離開太久）：下次開打再來一次（分身、援軍收掉，守護之柱重新張）。 */
    void onBossReset(final MapInstance instance) {
        final State state = byInstance.get(instance.id());
        if (state == null || !state.bossFightStarted) {
            return;
        }
        state.bossFightStarted = false;
        final Entity twin = state.twin == null ? null : plugin.getServer().getEntity(state.twin);
        if (twin != null && twin.isValid()) {
            twin.remove();
        }
        state.twin = null;
        for (final LivingEntity entity : state.reinforcements) {
            if (entity.isValid()) {
                entity.remove();
            }
        }
        state.reinforcements.clear();
    }

    /** 雙生：在 Boss 旁邊生一隻一樣的分身（不是 Boss：打死它不算通關，照傳奇怪掉東西）。 */
    private void spawnTwin(final State state, final LivingEntity boss) {
        final MapInstance instance = state.instance;
        final MapConfig.Boss definition = instance.bossDef();
        if (NO_TWIN.contains(boss.getType().name())) {
            return;
        }
        final Location at = boss.getLocation().clone().add(3, 0, 0);
        if (!instance.layout().isFloor(at.getBlockX() - instance.originX(), at.getBlockZ() - instance.originZ())) {
            at.setX(boss.getLocation().getX() - 3);
        }
        LivingEntity twin = null;
        if (definition.mythic() != null && plugin.mythic().exists(definition.mythic())) {
            twin = plugin.mythic().spawn(definition.mythic(), at, instance.level()); // MythicMobs 的 Boss：分身也用它的模型與招式
            if (twin != null) {
                service.tag(twin, instance, false);
            }
        }
        if (twin == null) {
            twin = service.spawner().spawnVanilla(boss.getType().name(), at, created -> service.tag(created, instance, false));
        }
        if (twin == null) {
            return;
        }
        // 不是 Boss（boss = false）：打死它不算通關、不算每月挑戰的 Boss；照傳奇怪掉東西
        final MonsterData bossData = plugin.monsters().data(boss).orElse(null);
        final List<String> mods = new ArrayList<>();
        for (final String id : definition.mods()) {
            if (plugin.monsters().config().mod(id) != null) {
                mods.add(id);
            }
        }
        plugin.monsters().decorateData(twin, new MonsterData(instance.level(), MonsterRarity.UNIQUE, mods, definition.name() + "的分身",
                bossData == null ? 1 : bossData.lifeMultiplier(), bossData == null ? 1 : bossData.damageMultiplier(), false));
        final org.bukkit.attribute.AttributeInstance size = twin.getAttribute(org.bukkit.attribute.Attribute.SCALE);
        if (size != null) {
            size.setBaseValue(definition.scale());
        }
        MapSpawner.prepare(twin);
        twin.setGlowing(true);
        state.twin = twin.getUniqueId();
        instance.extras().add(twin);
        at.getWorld().spawnParticle(Particle.SOUL_FIRE_FLAME, at, 40, 1.0, 1.0, 1.0, 0.05);
    }

    // ---- 每 10 tick ----

    void tick(final long now) {
        if (byInstance.isEmpty()) {
            return;
        }
        final Iterator<Map.Entry<Integer, State>> iterator = byInstance.entrySet().iterator();
        while (iterator.hasNext()) {
            final State state = iterator.next().getValue();
            final MapInstance instance = service.instance(state.instance.id());
            if (instance == null || instance.state() == MapInstance.State.CLOSING) {
                dispose(state);
                iterator.remove();
                continue;
            }
            if (instance.state() != MapInstance.State.OPEN) {
                continue;
            }
            try {
                tickState(state, now);
            } catch (final RuntimeException e) {
                plugin.getSLF4JLogger().error("地圖 #{} 的變化出錯", instance.id(), e);
            }
        }
    }

    private void tickState(final State state, final long now) {
        final MapInstance instance = state.instance;
        final List<Player> players = players(instance);
        final MapLayout layout = instance.layout();
        final List<MapLayout.Room> rooms = layout.rooms();
        for (final Player player : players) {
            final int x = player.getLocation().getBlockX() - instance.originX();
            final int z = player.getLocation().getBlockZ() - instance.originZ();
            final MapLayout.Room room = layout.roomAt(x, z);
            final int index = room == null ? -1 : rooms.indexOf(room);
            final Integer before = state.lastRoom.put(player.getUniqueId(), index);
            if (index >= 0 && index == state.hiddenRoom && !state.hiddenOpen) {
                openHidden(state, player); // 繞過牆進去了（位移技能）：當成打開，不然裡面的怪永遠不出來
            }
            final Theme theme = index < 0 ? null : state.themes.get(index);
            if (theme == Theme.DARK) {
                player.addPotionEffect(new PotionEffect(PotionEffectType.DARKNESS, 50, 0, false, false, false));
            }
            if (before == null || before != index) {
                if (theme != null && !(index == state.hiddenRoom && !state.hiddenOpen)) {
                    player.showTitle(Title.title(Component.empty(), plugin.text().parse(theme.color + theme.name + "<gray>：" + theme.hint),
                            Title.Times.times(Duration.ofMillis(150), Duration.ofMillis(1600), Duration.ofMillis(400))));
                }
                if (room != null && room.kind() == MapLayout.Kind.NORMAL && state.visited.add(index)) {
                    maybeStartTimed(state, index, now);
                }
            }
        }
        tickLabels(state, players);
        tickWanderer(state, players, now);
        tickTimed(state, now);
        tickHidden(state, players, now);
        tickReinforcements(state, now);
    }

    // ---- 流浪精英 ----

    private void tickWanderer(final State state, final List<Player> players, final long now) {
        if (state.wandererRoom < 0) {
            return;
        }
        final MapInstance instance = state.instance;
        final List<MapLayout.Room> rooms = instance.layout().rooms();
        if (!state.wandererSpawned) {
            final Location start = roomCenter(instance, rooms.get(state.wandererRoom));
            boolean near = false;
            for (final Player player : players) {
                near |= player.getLocation().distanceSquared(start) <= WANDER_SPAWN * WANDER_SPAWN;
            }
            if (!near || !start.getWorld().isChunkLoaded(start.getBlockX() >> 4, start.getBlockZ() >> 4)) {
                return;
            }
            state.wandererSpawned = true;
            spawnWanderer(state, start);
            return;
        }
        final Entity found = state.wanderer == null ? null : plugin.getServer().getEntity(state.wanderer);
        if (!(found instanceof LivingEntity wanderer) || !wanderer.isValid() || wanderer.isDead()) {
            return; // 倒下了，或區塊沒載入
        }
        if (!(wanderer instanceof Mob mob) || mob.getTarget() instanceof Player) {
            return; // 在打人就不走
        }
        final Location here = wanderer.getLocation();
        final boolean arrived = state.wanderTarget >= 0
                && here.distanceSquared(roomCenter(instance, rooms.get(state.wanderTarget))) <= 16;
        if (now < state.nextWander && !arrived) {
            return;
        }
        // 下一個目的地：離這裡最近的三間房間（不是 Boss 房、起點、還沒打開的隱藏房）隨機一間
        final List<Integer> options = new ArrayList<>();
        for (int i = 0; i < rooms.size(); i++) {
            final MapLayout.Room room = rooms.get(i);
            if (room.kind() == MapLayout.Kind.BOSS || room.kind() == MapLayout.Kind.START || i == state.wanderTarget
                    || (i == state.hiddenRoom && !state.hiddenOpen)) {
                continue;
            }
            options.add(i);
        }
        if (options.isEmpty()) {
            return;
        }
        options.sort(java.util.Comparator.comparingDouble(i -> roomCenter(instance, rooms.get(i)).distanceSquared(here)));
        state.wanderTarget = options.get(service.random().nextInt(Math.min(3, options.size())));
        state.nextWander = now + WANDER_TICKS;
        mob.getPathfinder().moveTo(roomCenter(instance, rooms.get(state.wanderTarget)), 1.0);
    }

    private void spawnWanderer(final State state, final Location at) {
        final MapInstance instance = state.instance;
        final RandomGenerator random = service.random();
        final ZoneConfig.SpawnEntry entry = service.spawner().chooseMonster(instance.map().monsters(), random);
        if (entry == null) {
            return;
        }
        final LivingEntity wanderer = service.spawner().spawn(instance, entry, at, MonsterRarity.RARE, null);
        if (wanderer == null) {
            return;
        }
        final MonsterData data = plugin.monsters().data(wanderer).orElse(null);
        if (data != null) {
            final MapEventConfig.Twists settings = settings();
            plugin.monsters().decorateData(wanderer, new MonsterData(data.level(), data.rarity(), data.mods(), "流浪的" + data.name(),
                    data.lifeMultiplier() * settings.wandererLife(), data.damageMultiplier() * settings.wandererDamage(), false));
            events.markQuantity(wanderer, settings.wandererQuantity() / 100.0);
        }
        wanderer.setGlowing(true);
        state.wanderer = wanderer.getUniqueId();
        instance.extras().add(wanderer);
        broadcast(instance, "<#ffd27f>⚠ 一隻流浪精英出現了：牠會在房間之間遊蕩，打倒牠物品數量 +" + Math.round(settings().wandererQuantity()) + "%");
        at.getWorld().playSound(at, Sound.ENTITY_RAVAGER_ROAR, 1.0f, 0.8f);
    }

    // ---- 限時挑戰 ----

    private void maybeStartTimed(final State state, final int room, final long now) {
        if (state.timed != null || state.timedUsed >= TIMED_MAX || state.themes.containsKey(room)
                || service.random().nextDouble() >= settings().timedChance()) {
            return;
        }
        final MapInstance instance = state.instance;
        final List<Integer> indices = new ArrayList<>();
        for (int i = 0; i < state.packRoom.length; i++) {
            if (state.packRoom[i] == room) {
                indices.add(i);
            }
        }
        if (indices.size() < 2) {
            return;
        }
        final Set<UUID> monsters = new HashSet<>();
        for (final int i : indices) {
            if (!instance.packSpawned(i)) {
                service.spawner().spawnPack(instance, i);
            }
            for (final LivingEntity entity : instance.pack(i)) {
                if (entity.isValid() && !entity.isDead()) {
                    monsters.add(entity.getUniqueId());
                }
            }
        }
        if (monsters.size() < TIMED_MIN_MONSTERS) {
            return;
        }
        state.timedUsed++;
        final long ticks = Math.round(settings().timedSeconds() * 20);
        final BossBar bar = BossBar.bossBar(plugin.text().parse("<#ffd27f>限時挑戰：殺光這個房間的怪"), 1f, BossBar.Color.YELLOW,
                BossBar.Overlay.PROGRESS);
        final Location center = roomCenter(instance, instance.layout().rooms().get(room));
        state.timed = new Timed(room, center, monsters, now, now + ticks, bar);
        for (final Player player : players(instance)) {
            if (player.getLocation().distanceSquared(center) <= REWARD_RADIUS * REWARD_RADIUS) {
                player.showTitle(Title.title(plugin.text().parse("<#ffd27f>限時挑戰"),
                        plugin.text().parse("<gray>" + Math.round(settings().timedSeconds()) + " 秒內殺光這個房間的 " + monsters.size() + " 隻怪"),
                        Title.Times.times(Duration.ofMillis(150), Duration.ofMillis(2000), Duration.ofMillis(400))));
                player.playSound(player.getLocation(), Sound.BLOCK_BELL_USE, 1.0f, 1.2f);
            }
        }
    }

    private void tickTimed(final State state, final long now) {
        final Timed timed = state.timed;
        if (timed == null) {
            return;
        }
        final List<Player> near = new ArrayList<>();
        for (final Player player : players(state.instance)) {
            if (player.getLocation().distanceSquared(timed.center) <= (REWARD_RADIUS + 16) * (REWARD_RADIUS + 16)) {
                near.add(player);
            }
        }
        if (timed.monsters.isEmpty()) {
            endTimed(state, timed);
            final long seconds = Math.max(1, (now - timed.startTick) / 20);
            broadcast(state.instance, "<green>✔ 限時挑戰成功（" + seconds + " 秒）：附近的人各拿一份獎勵");
            reward(state.instance, timed.center, "限時挑戰", 2, 0.5, 2, 1);
            return;
        }
        if (now >= timed.endTick) {
            endTimed(state, timed);
            broadcast(state.instance, "<red>✘ 限時挑戰失敗：時間到了（剩 " + timed.monsters.size() + " 隻）");
            return;
        }
        final double left = (timed.endTick - now) / (double) Math.max(1, timed.endTick - timed.startTick);
        timed.bar.progress((float) Math.max(0, Math.min(1, left)));
        timed.bar.name(plugin.text().parse("<#ffd27f>限時挑戰<gray>：剩 <white>" + timed.monsters.size() + "</white> 隻、<white>"
                + Math.max(0, (timed.endTick - now) / 20) + "</white> 秒"));
        final Set<UUID> keep = new HashSet<>();
        for (final Player player : near) {
            keep.add(player.getUniqueId());
            if (timed.watching.add(player.getUniqueId())) {
                player.showBossBar(timed.bar);
            }
        }
        for (final UUID uuid : List.copyOf(timed.watching)) {
            if (!keep.contains(uuid)) {
                final Player player = plugin.getServer().getPlayer(uuid);
                if (player != null) {
                    player.hideBossBar(timed.bar);
                }
                timed.watching.remove(uuid);
            }
        }
    }

    private void endTimed(final State state, final Timed timed) {
        for (final UUID uuid : timed.watching) {
            final Player player = plugin.getServer().getPlayer(uuid);
            if (player != null) {
                player.hideBossBar(timed.bar);
            }
        }
        timed.watching.clear();
        state.timed = null;
    }

    // ---- 隱藏房 ----

    /** 把一間房間的入口用裂開的牆封起來；封不了（找不到入口）回傳 false。 */
    private boolean sealRoom(final State state, final MapLayout.Room room) {
        final MapInstance instance = state.instance;
        final MapLayout layout = instance.layout();
        final List<int[]> cells = new ArrayList<>();
        for (int x = room.cx() - room.halfX() - 2; x <= room.cx() + room.halfX() + 2; x++) {
            for (int z = room.cz() - room.halfZ() - 2; z <= room.cz() + room.halfZ() + 2; z++) {
                if (room.contains(x, z) || !layout.isFloor(x, z)) {
                    continue;
                }
                boolean touches = false;
                for (int dx = -1; dx <= 1 && !touches; dx++) {
                    for (int dz = -1; dz <= 1 && !touches; dz++) {
                        touches = room.contains(x + dx, z + dz) && layout.isFloor(x + dx, z + dz);
                    }
                }
                if (touches) {
                    cells.add(new int[] {x, z});
                }
            }
        }
        if (cells.isEmpty() || cells.size() > 40) {
            return false; // 入口太寬（曠野那種一大片）就不封
        }
        for (final MapLayout.Spot pack : layout.packSpots()) {
            for (final int[] cell : cells) {
                if (Math.abs(pack.x() - cell[0]) <= 1 && Math.abs(pack.z() - cell[1]) <= 1) {
                    return false; // 走道的怪物群就在門口：封起來怪會卡在牆裡
                }
            }
        }
        for (final int[] cell : cells) {
            for (int dy = 1; dy <= HIDDEN_HEIGHT; dy++) {
                final Block block = instance.world().getBlockAt(instance.originX() + cell[0], MapLayout.FLOOR_Y + dy, instance.originZ() + cell[1]);
                if (!block.getType().isAir() && !block.isPassable()) {
                    break; // 碰到天花板就停（火把、地毯這種走得過去的也封掉，不留縫）
                }
                block.setType(Material.CRACKED_STONE_BRICKS, false);
                state.hiddenWall.add(block);
                blocks.put(MapEvents.key(block), state);
            }
        }
        return !state.hiddenWall.isEmpty();
    }

    private void tickHidden(final State state, final List<Player> players, final long now) {
        if (state.hiddenRoom < 0 || state.hiddenOpen || state.hiddenWall.isEmpty()) {
            return;
        }
        final Block sample = state.hiddenWall.get(state.hiddenWall.size() / 2);
        final Location wall = sample.getLocation().add(0.5, 0.5, 0.5);
        if ((now / 10) % 4 == 0) {
            wall.getWorld().spawnParticle(Particle.ASH, wall, 12, 1.2, 1.0, 1.2, 0);
        }
        for (final Player player : players) {
            if (player.getLocation().distanceSquared(wall) <= 36 && state.hiddenHinted.add(player.getUniqueId())) {
                player.sendActionBar(plugin.text().parse("<gray>這面牆好像有裂縫……<yellow>左鍵敲幾下看看"));
            }
        }
    }

    private void openHidden(final State state, final Player who) {
        state.hiddenOpen = true;
        for (final Block block : state.hiddenWall) {
            blocks.remove(MapEvents.key(block));
            if (block.getType() == Material.CRACKED_STONE_BRICKS) {
                block.setType(Material.AIR, false);
            }
        }
        final Location at = state.hiddenWall.get(state.hiddenWall.size() / 2).getLocation().add(0.5, 0.5, 0.5);
        at.getWorld().spawnParticle(Particle.BLOCK, at, 80, 1.5, 1.2, 1.5, 0, Material.STONE_BRICKS.createBlockData());
        at.getWorld().playSound(at, Sound.ENTITY_ZOMBIE_BREAK_WOODEN_DOOR, 1.0f, 0.7f);
        state.hiddenWall.clear();
        broadcast(state.instance, "<#f5b942>" + ItemRenderer.escape(who.getName()) + " 打破了一面裂開的牆：裡面是一間隱藏房（精英與寶箱）");
    }

    // ---- 援軍 ----

    private void tickReinforcements(final State state, final long now) {
        final MapInstance instance = state.instance;
        if (instance.twist().bossRoom != BossRoom.REINFORCE || !state.bossFightStarted || instance.bossKilled()) {
            return;
        }
        final LivingEntity boss = instance.boss();
        if (boss == null || !boss.isValid() || instance.fightStartTick() < 0 || now < state.nextReinforce) {
            return;
        }
        state.nextReinforce = now + REINFORCE_TICKS;
        state.reinforcements.removeIf(entity -> !entity.isValid() || entity.isDead());
        if (state.reinforcements.size() >= REINFORCE_MAX) {
            return;
        }
        final RandomGenerator random = service.random();
        final ZoneConfig.SpawnEntry entry = service.spawner().chooseMonster(instance.map().monsters(), random);
        if (entry == null) {
            return;
        }
        final MapLayout.Room room = instance.layout().boss();
        final List<String> mods = plugin.monsters().rollMods(MonsterRarity.MAGIC, instance.level());
        final double angle = random.nextDouble() * Math.PI * 2;
        for (int i = 0; i < REINFORCE_WAVE && state.reinforcements.size() < REINFORCE_MAX; i++) {
            final double a = angle + (i - 1) * 0.25;
            final double r = Math.max(3, room.halfX() - 3);
            final int x = (int) Math.floor(room.cx() + Math.cos(a) * r);
            final int z = (int) Math.floor(room.cz() + Math.sin(a) * r);
            if (!instance.layout().isFloor(x, z)) {
                continue;
            }
            final Location at = new Location(instance.world(), instance.originX() + x + 0.5, MapLayout.FLOOR_Y + 1, instance.originZ() + z + 0.5);
            final LivingEntity monster = service.spawner().spawn(instance, entry, at, MonsterRarity.MAGIC, mods);
            if (monster != null) {
                state.reinforcements.add(monster);
                instance.extras().add(monster);
                at.getWorld().spawnParticle(Particle.PORTAL, at.clone().add(0, 1, 0), 30, 0.4, 0.8, 0.4, 0.2);
            }
        }
    }

    // ---- 命運岔路 ----

    private boolean placeFork(final State state, final MapLayout.Room room) {
        final MapInstance instance = state.instance;
        final MapLayout layout = instance.layout();
        MapLayout.Spot left = null;
        MapLayout.Spot right = null;
        for (final int[] offsets : new int[][] {{-3, 0, 3, 0}, {0, -3, 0, 3}, {-2, -2, 2, 2}}) {
            final int lx = room.cx() + offsets[0];
            final int lz = room.cz() + offsets[1];
            final int rx = room.cx() + offsets[2];
            final int rz = room.cz() + offsets[3];
            if (layout.isFloor(lx, lz) && layout.isFloor(rx, rz) && room.contains(lx, lz) && room.contains(rx, rz)) {
                left = new MapLayout.Spot(lx, lz);
                right = new MapLayout.Spot(rx, rz);
                break;
            }
        }
        if (left == null) {
            return false;
        }
        state.forkBlood = pillar(state, left, Material.REDSTONE_BLOCK,
                "<#ff5c5c>血路<newline><gray>這張圖剩下的怪物群 +50%<newline><gray>物品數量 +30%<newline><yellow>右鍵選這條");
        state.forkKing = pillar(state, right, Material.GOLD_BLOCK,
                "<#f5b942>王道<newline><gray>Boss 生命與傷害 +30%<newline><gray>Boss 掉落的數量與稀有度 +50%<newline><yellow>右鍵選這條");
        return true;
    }

    /** 一根石碑（底座＋發光的方塊）與上面的說明；回傳底座的位置。 */
    private Location pillar(final State state, final MapLayout.Spot spot, final Material core, final String text) {
        final MapInstance instance = state.instance;
        final Location base = new Location(instance.world(), instance.originX() + spot.x(), MapLayout.FLOOR_Y + 1, instance.originZ() + spot.z());
        final Block bottom = base.getBlock();
        bottom.setType(Material.POLISHED_BLACKSTONE, false);
        bottom.getRelative(0, 1, 0).setType(core, false);
        blocks.put(MapEvents.key(bottom), state);
        blocks.put(MapEvents.key(bottom.getRelative(0, 1, 0)), state);
        state.labels.add(new Label(base.clone().add(0.5, 2.6, 0.5), text));
        return base;
    }

    /** 太晚了：Boss 已經出現，或怪物群刷出來超過一半（清完圖再回來選就沒有代價）。 */
    private static boolean late(final MapInstance instance) {
        if (instance.boss() != null || instance.bossKilled()) {
            return true;
        }
        final int count = instance.packCount();
        int spawned = 0;
        for (int i = 0; i < count; i++) {
            spawned += instance.packSpawned(i) ? 1 : 0;
        }
        return count > 0 && spawned > count * LATE_SHARE;
    }

    private void chooseFork(final State state, final Player who, final boolean blood) {
        if (state.forkChosen) {
            return;
        }
        if (late(state.instance)) {
            closeFork(state);
            who.sendMessage(plugin.text().prefixed("<gray>太晚了：這張圖已經走了一大半，命運岔路關上了"));
            return;
        }
        state.forkChosen = true;
        final MapInstance.Twist twist = state.instance.twist();
        if (blood) {
            twist.packSize += 50;
            twist.quantity += 0.30;
        } else {
            twist.bossPower += 0.30;
            twist.bossLoot += 0.50;
        }
        // 選完兩根都消失：選中的那根冒出它的顏色，沒選的冒煙
        final Location chosen = blood ? state.forkBlood : state.forkKing;
        final Location other = blood ? state.forkKing : state.forkBlood;
        closeFork(state);
        chosen.getWorld().spawnParticle(Particle.DUST, chosen.clone().add(0.5, 1, 0.5), 40, 0.4, 0.8, 0.4, 0,
                new Particle.DustOptions(blood ? org.bukkit.Color.fromRGB(255, 70, 70) : org.bukkit.Color.fromRGB(245, 185, 66), 1.4f));
        other.getWorld().spawnParticle(Particle.SMOKE, other.clone().add(0.5, 1, 0.5), 30, 0.3, 0.6, 0.3, 0.02);
        chosen.getWorld().playSound(chosen, Sound.BLOCK_BEACON_POWER_SELECT, 1.0f, blood ? 0.7f : 1.3f);
        broadcast(state.instance, ItemRenderer.escape(who.getName()) + " 選了" + (blood
                ? "<#ff5c5c>血路</#ff5c5c><gray>：之後刷的怪物群 +50%、物品數量 +30%"
                : "<#f5b942>王道</#f5b942><gray>：Boss 生命與傷害 +30%、Boss 掉落 +50%"));
    }

    /** 岔路關掉：兩根石碑都拿掉。 */
    private void closeFork(final State state) {
        state.forkChosen = true;
        for (final Location base : new Location[] {state.forkBlood, state.forkKing}) {
            final Block bottom = base.getBlock();
            blocks.remove(MapEvents.key(bottom));
            blocks.remove(MapEvents.key(bottom.getRelative(0, 1, 0)));
            bottom.getRelative(0, 1, 0).setType(Material.AIR, false);
            bottom.setType(Material.AIR, false);
        }
        for (final Label label : state.labels) {
            for (final Location base : new Location[] {state.forkBlood, state.forkKing}) {
                if (label.near(base, 2.6)) {
                    label.remove();
                }
            }
        }
    }

    // ---- 詛咒寶匣 ----

    private void placeCursed(final State state, final MapLayout.Spot spot) {
        final MapInstance instance = state.instance;
        final Location base = new Location(instance.world(), instance.originX() + spot.x(), MapLayout.FLOOR_Y + 1, instance.originZ() + spot.z());
        final Block bottom = base.getBlock();
        bottom.setType(Material.CRYING_OBSIDIAN, false);
        bottom.getRelative(0, 1, 0).setType(Material.SOUL_LANTERN, false);
        blocks.put(MapEvents.key(bottom), state);
        blocks.put(MapEvents.key(bottom.getRelative(0, 1, 0)), state);
        state.cursed = base;
        state.labels.add(new Label(base.clone().add(0.5, 2.4, 0.5), "<#c48bff>詛咒寶匣<newline><gray>右鍵看看裡面有什麼"));
    }

    private void offerCursed(final State state, final Player player) {
        if (late(state.instance)) {
            state.cursedDone = true;
            removeCursed(state, Material.AIR);
            player.sendMessage(plugin.text().prefixed("<gray>太晚了：這張圖已經走了一大半，詛咒寶匣化成了灰"));
            return;
        }
        final int power = (int) Math.round(settings().cursedPower());
        plugin.dialogs().confirm(player, "<#c48bff>詛咒寶匣", List.of(
                "<gray>裡面有一份豐厚的寶物（附近的人各一份）。",
                "<gray>但接受之後，這張圖之後出現的怪生命與傷害 <red>+" + power + "%</red>，Boss <red>+" + power / 2 + "%</red>。",
                "<dark_gray>拒絕的話，寶匣就消失了。"), "接受詛咒", () -> acceptCursed(state, player), () -> refuseCursed(state, player));
    }

    private void acceptCursed(final State state, final Player player) {
        if (!alive(state) || late(state.instance)) {
            return;
        }
        state.cursedDone = true;
        final MapInstance.Twist twist = state.instance.twist();
        twist.monsterPower += settings().cursedPower() / 100.0;
        twist.bossPower += settings().cursedPower() / 200.0;
        removeCursed(state, Material.OBSIDIAN);
        reward(state.instance, state.cursed.clone().add(0.5, 0, 0.5), "詛咒寶匣", 3, 1.0, 3, 1);
        state.cursed.getWorld().playSound(state.cursed, Sound.ENTITY_WITHER_AMBIENT, 0.8f, 0.6f);
        broadcast(state.instance, "<#c48bff>" + ItemRenderer.escape(player.getName()) + " 接受了詛咒<gray>：附近的人各拿一份寶物；之後的怪生命與傷害 <red>+"
                + Math.round(settings().cursedPower()) + "%");
    }

    /** 對話框按下去的時候：這張圖還開著、寶匣還在（格子重用後不會改到別人的圖）。 */
    private boolean alive(final State state) {
        return !state.cursedDone && state.instance.state() == MapInstance.State.OPEN && byInstance.get(state.instance.id()) == state;
    }

    private void refuseCursed(final State state, final Player player) {
        if (!alive(state)) {
            return;
        }
        state.cursedDone = true;
        removeCursed(state, Material.AIR);
        state.cursed.getWorld().spawnParticle(Particle.SMOKE, state.cursed.clone().add(0.5, 1, 0.5), 30, 0.3, 0.5, 0.3, 0.02);
        broadcast(state.instance, "<gray>" + ItemRenderer.escape(player.getName()) + " 拒絕了詛咒寶匣，它化成了灰");
    }

    private void removeCursed(final State state, final Material base) {
        final Block bottom = state.cursed.getBlock();
        blocks.remove(MapEvents.key(bottom));
        blocks.remove(MapEvents.key(bottom.getRelative(0, 1, 0)));
        bottom.getRelative(0, 1, 0).setType(Material.AIR, false);
        bottom.setType(base, false);
        for (final Label label : state.labels) {
            if (label.near(state.cursed, 2.4)) {
                label.remove();
            }
        }
    }

    // ---- 互動 ----

    /**
     * 右鍵岔路的石碑、詛咒寶匣；左鍵隱藏房的牆。跟寶箱一樣用 LOWEST、不 ignoreCancelled（世界保護把地圖世界的方塊互動都標成拒絕）。
     */
    @EventHandler(priority = EventPriority.LOWEST)
    public void onInteract(final PlayerInteractEvent event) {
        if (event.getClickedBlock() == null || event.getHand() != EquipmentSlot.HAND || blocks.isEmpty()) {
            return;
        }
        final Block block = event.getClickedBlock();
        final State state = blocks.get(MapEvents.key(block));
        if (state == null || state.instance.state() != MapInstance.State.OPEN || !MapEvents.withinReach(event.getPlayer(), block)) {
            return;
        }
        final Player player = event.getPlayer();
        if (event.getAction() == Action.LEFT_CLICK_BLOCK && state.hiddenWall.contains(block)) {
            event.setCancelled(true);
            state.hiddenHits++;
            block.getWorld().playSound(block.getLocation(), Sound.BLOCK_STONE_HIT, 1.0f, 0.7f);
            block.getWorld().spawnParticle(Particle.BLOCK, block.getLocation().add(0.5, 0.5, 0.5), 10, 0.3, 0.3, 0.3, 0,
                    Material.CRACKED_STONE_BRICKS.createBlockData());
            if (state.hiddenHits >= HIDDEN_HITS) {
                openHidden(state, player);
            } else {
                player.sendActionBar(plugin.text().parse("<gray>牆裂得更開了（" + state.hiddenHits + "／" + HIDDEN_HITS + "）"));
            }
            return;
        }
        if (event.getAction() != Action.RIGHT_CLICK_BLOCK) {
            return;
        }
        event.setCancelled(true);
        event.setUseItemInHand(org.bukkit.event.Event.Result.DENY);
        if (plugin.skills() != null) {
            plugin.skills().consumeRightClick(player);
        }
        if (state.cursed != null && !state.cursedDone && sameColumn(block, state.cursed)) {
            offerCursed(state, player);
        } else if (state.forkBlood != null && !state.forkChosen && sameColumn(block, state.forkBlood)) {
            chooseFork(state, player, true);
        } else if (state.forkKing != null && !state.forkChosen && sameColumn(block, state.forkKing)) {
            chooseFork(state, player, false);
        }
    }

    private static boolean sameColumn(final Block block, final Location base) {
        return block.getX() == base.getBlockX() && block.getZ() == base.getBlockZ();
    }

    /** 流浪精英、雙生的分身倒下：在圖裡說一聲。 */
    @EventHandler(priority = EventPriority.MONITOR)
    public void onDeath(final EntityDeathEvent event) {
        final LivingEntity entity = event.getEntity();
        final MapInstance instance = service.instanceOf(entity);
        final State state = instance == null ? null : byInstance.get(instance.id());
        if (state == null) {
            return;
        }
        final UUID uuid = entity.getUniqueId();
        if (state.timed != null) {
            state.timed.monsters.remove(uuid);
        }
        if (uuid.equals(state.wanderer)) {
            state.wandererDead = true;
            broadcast(instance, "<#ffd27f>流浪精英倒下了");
        } else if (uuid.equals(state.twin)) {
            state.twin = null;
            broadcast(instance, "<#ff7070>分身倒下了");
        }
    }

    // ---- 獎勵 ----

    /**
     * 附近的人各拿一份：照稀有怪的掉落表擲 rolls 次（物品數量與稀有度照地圖、自己的、全服、這個月的主題，再加 rarityBonus），
     * 另外 currencies 顆通貨、最多 maps 張地圖（各一半機率）。
     */
    private void reward(final MapInstance instance, final Location at, final String name, final int rolls, final double rarityBonus,
                        final int currencies, final int maps) {
        final RandomGenerator random = service.random();
        final DropTable drops = plugin.monsterModule().drops();
        final int level = instance.level();
        final MonsterData data = new MonsterData(level, MonsterRarity.RARE, List.of(), name);
        for (final Player player : players(instance)) {
            if (player.getLocation().distanceSquared(at) > REWARD_RADIUS * REWARD_RADIUS) {
                continue;
            }
            final StatSheet sheet = plugin.players().sheet(player);
            final double month = plugin.events() == null ? 0 : plugin.events().quantityBonus();
            final double monthRarity = plugin.events() == null ? 0 : plugin.events().rarityBonus();
            final double quantity = instance.stats().quantity() / 100.0 + instance.twist().quantity
                    + sheet.increased(StatKeys.DROP_QUANTITY, Set.of()) + month;
            final double rarity = rarityBonus + instance.stats().rarity() / 100.0 + sheet.increased(StatKeys.DROP_RARITY, Set.of()) + monthRarity;
            final List<DropTable.Drop> rolled = new ArrayList<>();
            for (int i = 0; i < rolls; i++) {
                rolled.addAll(drops.roll(level, MonsterRarity.RARE, quantity + drops.serverQuantity(), rarity + drops.serverRarity(),
                        plugin.items().generator(), plugin.currencies().repository(), random, plugin.characters().lootBias(player)));
            }
            for (int i = 0; i < currencies; i++) {
                final tw.exilecore.currency.CurrencyType currency = drops.rollCurrency(level, plugin.currencies().repository(), random,
                        rarity + drops.serverRarity());
                if (currency != null) {
                    rolled.add(DropTable.Drop.currency(currency));
                }
            }
            for (int i = 0; i < maps; i++) {
                if (random.nextBoolean()) {
                    final ItemData map = MapDrops.roll(service.config(), plugin.items().generator(), Math.max(1, instance.stats().tier()),
                            service.completed(player), random, instance.atlas().tierUp());
                    if (map != null) {
                        rolled.add(DropTable.Drop.equipment(map));
                    }
                }
            }
            final long gold = drops.rollGold(level, MonsterRarity.RARE, quantity, random) * Math.max(1, rolls);
            MapRewards.deliver(plugin, player, at, data, rolled, gold);
        }
        at.getWorld().spawnParticle(Particle.TOTEM_OF_UNDYING, at.clone().add(0, 1, 0), 40, 0.6, 0.6, 0.6, 0.15);
        at.getWorld().playSound(at, Sound.ENTITY_PLAYER_LEVELUP, 0.8f, 1.2f);
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

    private void broadcast(final MapInstance instance, final String message) {
        for (final UUID uuid : instance.inside()) {
            final Player player = plugin.getServer().getPlayer(uuid);
            if (player != null) {
                player.sendMessage(plugin.text().prefixed(message));
            }
        }
    }

    private static Location roomCenter(final MapInstance instance, final MapLayout.Room room) {
        return new Location(instance.world(), instance.originX() + room.cx() + 0.5, MapLayout.FLOOR_Y + 1, instance.originZ() + room.cz() + 0.5);
    }

    private TextDisplay spawnLabel(final Location at, final String text) {
        final TextDisplay display = at.getWorld().spawn(at, TextDisplay.class, created -> {
            created.setBillboard(Display.Billboard.CENTER);
            created.setPersistent(false); // 不存檔：區塊卸載就消失，有人走近再補（tickLabels）
            created.setSeeThrough(false);
            created.setShadowed(true);
        });
        display.text(plugin.text().parse(text));
        return display;
    }

    /** 有人在附近、區塊載入了：還沒生出來（或跟著區塊卸載不見了）的說明文字補上。 */
    private void tickLabels(final State state, final List<Player> players) {
        for (final Label label : state.labels) {
            if (label.removed || label.entity != null && label.entity.isValid()) {
                continue;
            }
            boolean near = false;
            for (final Player player : players) {
                near |= player.getWorld().equals(label.at.getWorld()) && player.getLocation().distanceSquared(label.at) <= LABEL_RANGE * LABEL_RANGE;
            }
            if (near && label.at.getWorld().isChunkLoaded(label.at.getBlockX() >> 4, label.at.getBlockZ() >> 4)) {
                label.entity = spawnLabel(label.at, label.text);
            }
        }
    }

    private void dispose(final State state) {
        for (final Label label : state.labels) {
            label.remove();
        }
        state.labels.clear();
        if (state.timed != null) {
            endTimed(state, state.timed);
        }
        blocks.values().removeIf(value -> value == state);
        for (final UUID uuid : new UUID[] {state.wanderer, state.twin}) {
            final Entity entity = uuid == null ? null : plugin.getServer().getEntity(uuid);
            if (entity != null && entity.isValid()) {
                entity.remove();
            }
        }
        for (final LivingEntity entity : state.reinforcements) {
            if (entity.isValid()) {
                entity.remove();
            }
        }
        state.reinforcements.clear();
    }

    void clear(final MapInstance instance) {
        final State state = byInstance.remove(instance.id());
        if (state != null) {
            dispose(state);
        }
    }

    void clearAll() {
        for (final State state : byInstance.values()) {
            dispose(state);
        }
        byInstance.clear();
        blocks.clear();
    }

    /** /ec map 的狀態（沒有變化回傳 null）。 */
    String summary(final MapInstance instance) {
        final State state = byInstance.get(instance.id());
        if (state == null) {
            return null;
        }
        final List<String> parts = new ArrayList<>();
        if (!state.themes.isEmpty()) {
            final Map<Theme, Integer> counts = new java.util.EnumMap<>(Theme.class);
            for (final Theme theme : state.themes.values()) {
                counts.merge(theme, 1, Integer::sum);
            }
            final List<String> each = new ArrayList<>();
            counts.forEach((theme, count) -> each.add(theme.name + "×" + count));
            parts.add("主題房 " + String.join("、", each));
        }
        if (state.mixedFrom != null) {
            parts.add("混進" + state.mixedFrom + "的怪");
        }
        if (state.wandererRoom >= 0) {
            parts.add("流浪精英" + (state.wandererDead ? "（倒下）" : state.wandererSpawned ? "（出現）" : ""));
        }
        if (state.forkBlood != null) {
            parts.add("命運岔路" + (state.forkChosen ? "（已選）" : ""));
        }
        if (state.cursed != null) {
            parts.add("詛咒寶匣" + (state.cursedDone ? "（處理過）" : ""));
        }
        if (state.hiddenRoom >= 0) {
            parts.add("隱藏房" + (state.hiddenOpen ? "（打開）" : ""));
        }
        if (state.timedUsed > 0) {
            parts.add("限時挑戰 ×" + state.timedUsed);
        }
        final MapInstance.Twist twist = instance.twist();
        if (twist.bossVariant != null) {
            parts.add("Boss " + twist.bossVariant.name);
        }
        if (twist.bossRoom != null) {
            parts.add("Boss 房" + twist.bossRoom.name);
        }
        return parts.isEmpty() ? null : "變化：" + String.join("、", parts);
    }
}
