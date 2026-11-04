package tw.exilecore.map;

import net.kyori.adventure.bossbar.BossBar;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.title.Title;
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
import org.bukkit.event.entity.EntityDeathEvent;
import org.bukkit.inventory.ItemStack;
import org.bukkit.persistence.PersistentDataType;
import tw.exilecore.ExileCore;
import tw.exilecore.ailment.AilmentType;
import tw.exilecore.combat.Debuffs;
import tw.exilecore.gui.Dialogs;
import tw.exilecore.item.ItemRenderer;
import tw.exilecore.monster.MonsterData;
import tw.exilecore.monster.MonsterRarity;
import tw.exilecore.stats.Modifier;
import tw.exilecore.stats.StatKeys;
import tw.exilecore.stats.TreeEffectKeys;
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
 * 2026-11-04 照站長「先做絕境」：絕境（照 PoE 的 Ultimatum）。地圖裡一座審判之壇，右鍵開始：一回合一回合打，每一回合先從三個懲罰挑一個
 * （挑過的整場都疊著），回合有三種（殲滅、生存、守圈），打完可以「收手」拿累積的獎勵，或「續押」下一回合——失敗（時間到、人都離開場地或倒下）
 * 全部沒收。打完全部回合，獎勵乘 final_multiplier。波次的刷怪照迷霧回廊那一套（{@link FogTrial}）。設定在 {@code map_events.yml} 的 {@code ultimatum}。
 */
final class Ultimatum implements Listener {

    /** 回合的種類。 */
    enum Kind { KILL, SURVIVE, CIRCLE }

    /**
     * 懲罰（一回合挑一個，之後全部疊著）。怪物側的在刷怪時乘上去；玩家側的用減益掛在場地裡的人身上；地面的在場地裡撒一塊塊地面效果。
     * 代號寫在 map_events.yml 的 ultimatum.modifiers（權重 0 = 不出）。
     */
    enum Penalty {
        MONSTER_DAMAGE("monster_damage", "怪物狂怒", "怪物傷害 +40%"),
        MONSTER_LIFE("monster_life", "怪物鐵壁", "怪物生命 +60%"),
        MONSTER_SPEED("monster_speed", "怪物疾行", "怪物全部是極速的（移動、攻擊、施放速度 +30%）"),
        MONSTER_MORE("monster_more", "怪物增援", "怪物數量 +50%"),
        RECOVERY("recovery", "回復受阻", "你的生命、魔力回復速度更少 50%"),
        SLOW("slow", "步履維艱", "你的移動速度 −25%"),
        VULNERABLE("vulnerable", "脆弱", "你受到的傷害增加 25%"),
        RESIST("resist", "抗性削弱", "你的火焰、冰冷、閃電抗性 −20%"),
        COOLDOWN("cooldown", "冷卻遲滯", "你的技能冷卻增加 35%"),
        FIRE_GROUND("fire_ground", "烈焰地面", "場地裡一塊塊點燃的地面"),
        CHILL_GROUND("chill_ground", "霜寒地面", "場地裡一塊塊冰緩的地面"),
        SHOCK_GROUND("shock_ground", "感電地面", "場地裡一塊塊感電的地面（受到的傷害增加 20%）");

        final String id;
        final String display;
        final String description;

        Penalty(final String id, final String display, final String description) {
            this.id = id;
            this.display = display;
            this.description = description;
        }

        boolean ground() {
            return this == FIRE_GROUND || this == CHILL_GROUND || this == SHOCK_GROUND;
        }
    }

    private enum Phase { IDLE, CHOOSING, PREPARE, FIGHT, DECIDE, FINAL, DONE }

    /** 審判者的實例種類（{@link MapService.Special#kind()}）；也是 maps.yml event_bosses 的代號。 */
    static final String BOSS_KIND = "ultimatum";

    /** 一張圖的絕境。 */
    final class Trial {
        final MapInstance instance;
        final MapLayout.Room room;
        final Location altar;
        TextDisplay label;
        Phase phase = Phase.IDLE;
        /** 打完的回合數。 */
        int round;
        boolean failed;
        final List<Penalty> chosen = new ArrayList<>();
        List<Penalty> offered = List.of();
        /** 下一回合（還沒開打）的種類與獎勵。 */
        Kind nextKind;
        MapEventConfig.Reward nextReward;
        /** 打完的每一回合的獎勵（種類、份數）。 */
        final List<Map.Entry<MapEventConfig.Reward, Integer>> earned = new ArrayList<>();
        long phaseStart;
        long deadline;
        long nextSpawn;
        int needed;
        int kills;
        int spawned;
        /** 守圈：圈的中心、下一次換位置、累積到幾 tick。 */
        Location circle;
        long circleMove;
        long circleTicks;
        /** 場地裡沒有活人從哪一 tick 開始（−1 = 有人）。 */
        long emptySince = -1;
        final List<LivingEntity> monsters = new ArrayList<>();
        BossBar bar;
        final Set<UUID> watching = new HashSet<>();

        Trial(final MapInstance instance, final MapLayout.Room room, final Location altar) {
            this.instance = instance;
            this.room = room;
            this.altar = altar;
        }

        boolean inArena(final Location at) {
            return at.getWorld() == altar.getWorld() && room.contains(at.getBlockX() - instance.originX(), at.getBlockZ() - instance.originZ());
        }

        boolean has(final Penalty modifier) {
            return chosen.contains(modifier);
        }
    }

    /** 玩家側懲罰的減益來源（固定的，不是哪個玩家放的）。 */
    static final UUID SOURCE = UUID.nameUUIDFromBytes("exilecore:ultimatum".getBytes(java.nio.charset.StandardCharsets.UTF_8));
    private static final String DEBUFF_ID = "ultimatum";
    private static final int MAX_ALIVE = 40;
    private static final int GROUND_PATCHES = 8;

    private final ExileCore plugin;
    private final MapService service;
    private final MapEventConfig config;
    private final Map<Integer, Trial> byInstance = new HashMap<>();
    private final NamespacedKey monsterKey;

    Ultimatum(final ExileCore plugin, final MapService service, final MapEventConfig config) {
        this.plugin = plugin;
        this.service = service;
        this.config = config;
        this.monsterKey = new NamespacedKey(plugin, "ultimatum_monster");
    }

    MapEventConfig.UltimatumSettings settings() {
        return config.ultimatum();
    }

    // ---- 放置 ----

    /** 地圖蓋好：照機率放一座審判之壇（從 rooms 裡拿一個房間當場地）。 */
    void place(final MapInstance instance, final List<MapLayout.Room> rooms) {
        final MapEventConfig.UltimatumSettings settings = settings();
        final RandomGenerator random = service.random();
        final double chance = Math.min(1.0, settings.chance() * (1 + instance.atlas().increased("atlas.ultimatum.chance")));
        if (instance.atlas().get("atlas.other_events_off") > 0 || instance.special() != null || chance <= 0 || random.nextDouble() >= chance || rooms.isEmpty()) {
            return;
        }
        for (int attempt = 0; attempt < 3 && !rooms.isEmpty(); attempt++) {
            final MapLayout.Room room = rooms.remove(random.nextInt(rooms.size()));
            final MapLayout.Spot spot = MapEvents.spotIn(instance.layout(), room);
            if (spot == null) {
                continue;
            }
            final Location altar = new Location(instance.world(), instance.originX() + spot.x() + 0.5, MapLayout.FLOOR_Y + 1, instance.originZ() + spot.z() + 0.5);
            altar.getBlock().setType(Material.LODESTONE);
            byInstance.put(instance.id(), new Trial(instance, room, altar));
            return;
        }
    }

    // ---- 每 10 tick ----

    void tick(final long now) {
        if (byInstance.isEmpty()) {
            return;
        }
        final Iterator<Map.Entry<Integer, Trial>> iterator = byInstance.entrySet().iterator();
        while (iterator.hasNext()) {
            final Map.Entry<Integer, Trial> entry = iterator.next();
            final Trial trial = entry.getValue();
            final MapInstance instance = service.instance(entry.getKey());
            if (instance == null || instance.state() == MapInstance.State.CLOSING) {
                dispose(trial);
                iterator.remove();
                continue;
            }
            if (instance.state() != MapInstance.State.OPEN || trial.phase == Phase.DONE) {
                continue;
            }
            try {
                tickTrial(trial, now);
            } catch (final RuntimeException e) {
                plugin.getSLF4JLogger().error("地圖 #{} 的絕境出錯", instance.id(), e);
                fail(trial, "出錯了（已經記在主控台）");
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

    /** 場地裡活著的人。 */
    private List<Player> inArena(final Trial trial) {
        final List<Player> list = new ArrayList<>();
        for (final Player player : players(trial.instance)) {
            if (trial.inArena(player.getLocation())) {
                list.add(player);
            }
        }
        return list;
    }

    private void tickTrial(final Trial trial, final long now) {
        final Location altar = trial.altar;
        if (trial.phase == Phase.IDLE) {
            if (!altar.getWorld().isChunkLoaded(altar.getBlockX() >> 4, altar.getBlockZ() >> 4)) {
                return;
            }
            if (trial.label == null || !trial.label.isValid()) {
                trial.label = label(trial);
            }
            tw.exilecore.skill.Fx.spawn(altar.getWorld(), Particle.END_ROD, altar.getX(), altar.getY() + 1.2, altar.getZ(), 3, 0.3, 0.5, 0.3, 0.01);
            return;
        }
        trial.monsters.removeIf(monster -> !monster.isValid() || monster.isDead());
        final List<Player> arena = inArena(trial);
        switch (trial.phase) {
            case PREPARE -> {
                if (now >= trial.deadline) {
                    startRound(trial, now);
                }
            }
            case FIGHT -> tickFight(trial, now, arena);
            default -> {
                // CHOOSING、DECIDE：等人在對話框裡選（人走光也等著，回來再右鍵祭壇；實例關掉就沒了）
            }
        }
        if (trial.phase != Phase.DONE) {
            applyDebuffs(trial, arena, now);
            updateBar(trial, players(trial.instance), now);
        }
    }

    // ---- 開始、選懲罰 ----

    /** 右鍵審判之壇：還沒開始就開始（出第一回合的選單）；在選的時候再看一次選單。 */
    private void interact(final Trial trial, final Player player) {
        switch (trial.phase) {
            case IDLE -> {
                trial.phase = Phase.CHOOSING;
                trial.round = 0;
                if (trial.label != null && trial.label.isValid()) {
                    trial.label.remove();
                    trial.label = null;
                }
                offer(trial);
                player.getWorld().playSound(trial.altar, Sound.BLOCK_BEACON_ACTIVATE, 1.0f, 0.8f);
                plugin.getSLF4JLogger().info("地圖 #{}：{} 開始了絕境", trial.instance.id(), player.getName());
                showChoice(trial, player);
            }
            case CHOOSING -> showChoice(trial, player);
            case DECIDE -> showDecision(trial, player);
            case FINAL -> showFinal(trial, player);
            case PREPARE, FIGHT -> player.sendMessage(plugin.text().prefixed("<gray>絕境進行中"));
            default -> player.sendMessage(plugin.text().prefixed("<gray>這座審判之壇已經結束了"));
        }
    }

    /** 下一回合：抽種類、獎勵，從還沒挑過的懲罰裡抽三個。 */
    private void offer(final Trial trial) {
        final MapEventConfig.UltimatumSettings settings = settings();
        final RandomGenerator random = service.random();
        final String type = MapRewards.pickString(settings.typeWeights(), random);
        trial.nextKind = type == null ? Kind.KILL : switch (type) {
            case "survive" -> Kind.SURVIVE;
            case "circle" -> Kind.CIRCLE;
            default -> Kind.KILL;
        };
        final MapEventConfig.Reward reward = MapRewards.pick(settings.rewardWeights(), random);
        trial.nextReward = reward == null ? MapEventConfig.Reward.CURRENCY : reward;
        final List<Penalty> pool = new ArrayList<>();
        final List<Integer> weights = new ArrayList<>();
        for (final Penalty modifier : Penalty.values()) {
            final int weight = settings.modifierWeight(modifier.id, 10);
            if (weight > 0 && !trial.has(modifier)) {
                pool.add(modifier);
                weights.add(weight);
            }
        }
        final List<Penalty> offered = new ArrayList<>();
        while (offered.size() < 3 && !pool.isEmpty()) {
            int total = 0;
            for (final int weight : weights) {
                total += weight;
            }
            int roll = random.nextInt(total);
            int index = 0;
            while (index < weights.size() - 1 && (roll -= weights.get(index)) >= 0) {
                index++;
            }
            offered.add(pool.remove(index));
            weights.remove(index);
        }
        trial.offered = offered;
    }

    private String kindText(final Trial trial, final Kind kind, final int round, final int players) {
        final MapEventConfig.UltimatumSettings settings = settings();
        return switch (kind) {
            case KILL -> "<#ff8a65>殲滅<gray>：" + Math.round(settings.killSeconds()) + " 秒內殺死 <white>" + settings.killCount(round, Math.max(1, players)) + "</white> 隻";
            case SURVIVE -> "<#9fd3ff>生存<gray>：撐過 <white>" + Math.round(settings.surviveSeconds(round)) + "</white> 秒";
            case CIRCLE -> "<#f5b942>守圈<gray>：站在光圈裡累積 <white>" + Math.round(settings.circleSeconds(round)) + "</white> 秒（圈每 "
                    + Math.round(settings.circleMoveSeconds()) + " 秒換位置，時限 " + Math.round(settings.circleSeconds(round) * 2) + " 秒）";
        };
    }

    private void showChoice(final Trial trial, final Player player) {
        final MapEventConfig.UltimatumSettings settings = settings();
        final int next = trial.round + 1;
        final List<String> body = new ArrayList<>();
        body.add("<gray>第 <white>" + next + "</white>／" + settings.rounds() + " 回合　" + kindText(trial, trial.nextKind, next, inArena(trial).size()));
        body.add("<gray>這回合的獎勵：<white>" + ItemRenderer.escape(trial.nextReward.displayName()) + " ×" + settings.rewards(next)
                + "<dark_gray>（打完才算，收手時一起拿）");
        if (!trial.chosen.isEmpty()) {
            body.add("<gray>已疊的懲罰：<#ff5c6a>" + String.join("、", trial.chosen.stream().map(modifier -> modifier.display).toList()));
        }
        body.add("<red>離開場地超過 " + Math.round(settings.leaveSeconds()) + " 秒、全員倒下或時間到：失敗，累積的獎勵全部沒收");
        body.add(trial.offered.isEmpty() ? "<yellow>懲罰都挑完了：直接開打" : "<yellow>挑一個懲罰（之後的回合都疊著）");
        final List<Dialogs.Button> buttons = new ArrayList<>();
        if (trial.offered.isEmpty()) {
            buttons.add(new Dialogs.Button("<green>開打", null, () -> choose(trial, player, null), null));
        }
        for (final Penalty modifier : trial.offered) {
            buttons.add(new Dialogs.Button("<#ff5c6a>" + ItemRenderer.escape(modifier.display), "<gray>" + ItemRenderer.escape(modifier.description),
                    () -> choose(trial, player, modifier), null));
        }
        plugin.dialogs().notice(player, "<#c77dff>絕境：第 " + next + " 回合", body, buttons, buttons.size() >= 3 ? 3 : buttons.size());
    }

    private void choose(final Trial trial, final Player player, final Penalty modifier) {
        if (trial.phase != Phase.CHOOSING || modifier != null && !trial.offered.contains(modifier)) {
            return; // 別人先選了（或對話框過期）
        }
        if (modifier != null) {
            trial.chosen.add(modifier);
        }
        trial.offered = List.of();
        trial.phase = Phase.PREPARE;
        trial.phaseStart = plugin.getServer().getCurrentTick();
        trial.deadline = trial.phaseStart + Math.round(settings().prepareSeconds() * 20);
        final int next = trial.round + 1;
        for (final Player each : players(trial.instance)) {
            each.showTitle(Title.title(plugin.text().parse("<#c77dff>第 " + next + " 回合"),
                    plugin.text().parse(modifier == null ? "<gray>" + Math.round(settings().prepareSeconds()) + " 秒後開始"
                            : "<gray>" + ItemRenderer.escape(player.getName()) + " 選了 <#ff5c6a>" + ItemRenderer.escape(modifier.display) + "<gray>・"
                            + Math.round(settings().prepareSeconds()) + " 秒後開始，待在場地裡"),
                    Title.Times.times(Duration.ofMillis(200), Duration.ofMillis(2500), Duration.ofMillis(500))));
            each.playSound(each.getLocation(), Sound.BLOCK_NOTE_BLOCK_BELL, 0.8f, 0.6f);
        }
    }

    // ---- 回合 ----

    private void startRound(final Trial trial, final long now) {
        final MapEventConfig.UltimatumSettings settings = settings();
        final int round = trial.round + 1;
        final int players = Math.max(1, inArena(trial).size());
        trial.phase = Phase.FIGHT;
        trial.phaseStart = now;
        trial.nextSpawn = now;
        trial.kills = 0;
        trial.spawned = 0;
        trial.circleTicks = 0;
        trial.emptySince = -1;
        switch (trial.nextKind) {
            case KILL -> {
                trial.needed = settings.killCount(round, players);
                trial.deadline = now + Math.round(settings.killSeconds() * 20);
            }
            case SURVIVE -> {
                trial.needed = (int) Math.round(settings.surviveSeconds(round) * 20);
                trial.deadline = now + trial.needed;
            }
            case CIRCLE -> {
                trial.needed = (int) Math.round(settings.circleSeconds(round) * 20);
                trial.deadline = now + trial.needed * 2L;
                trial.circle = circleSpot(trial);
                trial.circleMove = now + Math.round(settings.circleMoveSeconds() * 20);
            }
        }
        placeGround(trial);
        for (final Player player : players(trial.instance)) {
            player.playSound(player.getLocation(), Sound.ENTITY_ELDER_GUARDIAN_CURSE, 0.5f, 1.4f);
        }
        broadcast(trial, "<#c77dff>第 " + round + " 回合開始　" + kindText(trial, trial.nextKind, round, players));
    }

    private void tickFight(final Trial trial, final long now, final List<Player> arena) {
        final MapEventConfig.UltimatumSettings settings = settings();
        final RandomGenerator random = service.random();
        final int round = trial.round + 1;
        // 場地裡沒有活人：倒數；超過就失敗
        if (arena.isEmpty()) {
            if (trial.emptySince < 0) {
                trial.emptySince = now;
            } else if (now - trial.emptySince >= Math.round(settings.leaveSeconds() * 20)) {
                fail(trial, "場地裡沒有人了");
                return;
            }
        } else {
            trial.emptySince = -1;
        }
        // 刷怪（殲滅：刷到夠殺為止；生存、守圈：一直刷，但場上最多 MAX_ALIVE 隻）
        if (!arena.isEmpty() && now >= trial.nextSpawn) {
            trial.nextSpawn = now + Math.round((trial.nextKind == Kind.KILL ? settings.killSpawnSeconds() : settings.spawnSeconds()) * 20); // 2026-11-04 照站長：殲滅 0.5 秒一群
            final double scale = settings.spawnScale(round) * (trial.has(Penalty.MONSTER_MORE) ? 1.5 : 1);
            for (final Player player : arena) {
                final int base = settings.groupMin() + (settings.groupMax() > settings.groupMin() ? random.nextInt(settings.groupMax() - settings.groupMin() + 1) : 0);
                final int group = (int) Math.max(1, Math.round(base * scale));
                for (int i = 0; i < group; i++) {
                    if (trial.monsters.size() >= MAX_ALIVE || trial.nextKind == Kind.KILL && trial.spawned >= trial.needed + trial.needed / 5 + 2) {
                        break;
                    }
                    final double roll = random.nextDouble();
                    final MonsterRarity rarity = roll < settings.rareChance(round) ? MonsterRarity.RARE
                            : roll < settings.rareChance(round) + settings.magicChance(round) ? MonsterRarity.MAGIC : MonsterRarity.NORMAL;
                    spawnOne(trial, player, rarity, random);
                    trial.spawned++;
                }
            }
        }
        // 回合目標
        switch (trial.nextKind) {
            case KILL -> {
                if (trial.kills >= trial.needed) {
                    clearRound(trial, now);
                    return;
                }
                if (now >= trial.deadline) {
                    fail(trial, "時間到（殺了 " + trial.kills + "／" + trial.needed + " 隻）");
                    return;
                }
            }
            case SURVIVE -> {
                if (now >= trial.deadline) {
                    clearRound(trial, now);
                    return;
                }
            }
            case CIRCLE -> {
                if (trial.circle == null || now >= trial.circleMove) {
                    trial.circle = circleSpot(trial);
                    trial.circleMove = now + Math.round(settings.circleMoveSeconds() * 20);
                    for (final Player player : arena) {
                        player.playSound(player.getLocation(), Sound.BLOCK_AMETHYST_BLOCK_CHIME, 1.0f, 1.2f);
                    }
                }
                tw.exilecore.skill.Fx.ring(trial.circle.getWorld(), trial.circle.clone().add(0, 0.1, 0), settings.circleRadius(), Particle.END_ROD, null, null, true);
                tw.exilecore.skill.Fx.spawn(trial.circle.getWorld(), Particle.END_ROD, trial.circle.getX(), trial.circle.getY() + 2.5, trial.circle.getZ(), 4, 0.2, 1.5, 0.2, 0.0);
                boolean inside = false;
                for (final Player player : arena) {
                    if (player.getLocation().distanceSquared(trial.circle) <= settings.circleRadius() * settings.circleRadius()) {
                        inside = true;
                        break;
                    }
                }
                if (inside) {
                    trial.circleTicks += 10;
                }
                if (trial.circleTicks >= trial.needed) {
                    clearRound(trial, now);
                    return;
                }
                if (now >= trial.deadline) {
                    fail(trial, "時間到（圈裡累積了 " + trial.circleTicks / 20 + "／" + trial.needed / 20 + " 秒）");
                    return;
                }
            }
        }
    }

    /** 守圈：場地裡隨機一個地板位置（離祭壇至少 3 格）。 */
    private Location circleSpot(final Trial trial) {
        final RandomGenerator random = service.random();
        final MapInstance instance = trial.instance;
        for (int attempt = 0; attempt < 30; attempt++) {
            final int x = trial.room.cx() + random.nextInt(trial.room.halfX() * 2 + 1) - trial.room.halfX();
            final int z = trial.room.cz() + random.nextInt(trial.room.halfZ() * 2 + 1) - trial.room.halfZ();
            if (!trial.room.contains(x, z) || !instance.layout().isFloor(x, z)) {
                continue;
            }
            final Location at = new Location(instance.world(), instance.originX() + x + 0.5, MapLayout.FLOOR_Y + 1, instance.originZ() + z + 0.5);
            if (at.distanceSquared(trial.altar) >= 9) {
                return at;
            }
        }
        return trial.altar.clone();
    }

    /** 在玩家附近 4–8 格（場地裡）刷一隻；懲罰的倍率在這裡乘上去。 */
    private LivingEntity spawnOne(final Trial trial, final Player player, final MonsterRarity rarity, final RandomGenerator random) {
        final MapInstance instance = trial.instance;
        final ZoneConfig.SpawnEntry entry = service.spawner().chooseMonster(instance.map().monsters(), random);
        if (entry == null) {
            return null;
        }
        Location at = null;
        for (int attempt = 0; attempt < 12 && at == null; attempt++) {
            final double angle = random.nextDouble() * Math.PI * 2;
            final double distance = 4 + random.nextDouble() * 4;
            final int x = (int) Math.floor(player.getLocation().getX() + Math.cos(angle) * distance);
            final int z = (int) Math.floor(player.getLocation().getZ() + Math.sin(angle) * distance);
            if (instance.layout().isFloor(x - instance.originX(), z - instance.originZ()) && trial.room.contains(x - instance.originX(), z - instance.originZ())) {
                at = new Location(instance.world(), x + 0.5, MapLayout.FLOOR_Y + 1, z + 0.5, random.nextFloat() * 360f, 0f);
            }
        }
        if (at == null) {
            at = trial.altar.clone();
        }
        final List<String> mods = new ArrayList<>(rarity == MonsterRarity.NORMAL ? List.of() : plugin.monsters().rollMods(rarity, instance.level()));
        if (trial.has(Penalty.MONSTER_SPEED) && !mods.contains("swift") && plugin.monsters().config().mod("swift") != null) {
            mods.add("swift");
        }
        final LivingEntity monster = service.spawner().spawn(instance, entry, at, rarity, mods);
        if (monster == null) {
            return null;
        }
        monster.getPersistentDataContainer().set(monsterKey, PersistentDataType.BYTE, (byte) 1);
        final double life = trial.has(Penalty.MONSTER_LIFE) ? 1.6 : 1;
        final double damage = trial.has(Penalty.MONSTER_DAMAGE) ? 1.4 : 1;
        plugin.monsters().data(monster).ifPresent(data -> plugin.monsters().decorateData(monster, new MonsterData(data.level(), data.rarity(),
                data.mods(), "絕境・" + data.name(), data.lifeMultiplier() * life, data.damageMultiplier() * damage, false)));
        trial.monsters.add(monster);
        tw.exilecore.skill.Fx.spawn(monster.getWorld(), Particle.REVERSE_PORTAL, monster.getLocation().add(0, 1, 0), 20, 0.4, 0.6, 0.4, 0.03);
        if (monster instanceof Mob mob) {
            mob.setTarget(player);
        }
        return monster;
    }

    /** 地面懲罰：場地裡撒一塊塊地面效果（每回合開打時重撒）。 */
    private void placeGround(final Trial trial) {
        if (plugin.monsterModule() == null || plugin.monsterModule().mechanics() == null) {
            return;
        }
        final String owner = owner(trial);
        plugin.monsterModule().mechanics().clearMapPatches(owner);
        final List<Penalty> grounds = trial.chosen.stream().filter(Penalty::ground).toList();
        if (grounds.isEmpty()) {
            return;
        }
        final RandomGenerator random = service.random();
        final MapInstance instance = trial.instance;
        final long until = plugin.getServer().getCurrentTick() + 20L * 60 * 30;
        for (final Penalty ground : grounds) {
            final AilmentType type = ground == Penalty.FIRE_GROUND ? AilmentType.IGNITE : ground == Penalty.CHILL_GROUND ? AilmentType.CHILL : AilmentType.SHOCK;
            final double value;
            if (type == AilmentType.IGNITE) {
                final double[] damage = plugin.monsters() == null ? new double[] {1, 1} : plugin.monsters().config().damageAt(instance.level());
                value = MapHazards.groundFire(4000, damage); // 照地圖詞綴 T2 的點燃地面
            } else {
                value = type == AilmentType.CHILL ? 0.3 : 0.2;
            }
            for (int i = 0; i < GROUND_PATCHES; i++) {
                final Location at = circleSpot(trial);
                plugin.monsterModule().mechanics().addMapPatch(owner, at, type, value, 2.0, until);
            }
        }
    }

    private static String owner(final Trial trial) {
        return "ultimatum:" + trial.instance.id();
    }

    /** 玩家側懲罰：掛在場地裡的人身上（每次刷新 2 秒，離開場地或結束就自己消掉）。 */
    private void applyDebuffs(final Trial trial, final List<Player> arena, final long now) {
        if (trial.phase != Phase.FIGHT || plugin.combat() == null || plugin.combat().debuffs() == null) {
            return;
        }
        final List<Modifier> mods = new ArrayList<>();
        if (trial.has(Penalty.RECOVERY)) {
            // 回復速度的 more（TreeEffectKeys.recoveryRate 讀；用建構子不用 Modifier.more，StatConsumersTest 不會把這裡算成那個鍵的讀法）
            mods.add(new Modifier(TreeEffectKeys.LIFE_RECOVERY_RATE, tw.exilecore.stats.ModType.MORE, -0.5, Set.of(), "絕境：回復受阻"));
            mods.add(new Modifier(TreeEffectKeys.MANA_RECOVERY_RATE, tw.exilecore.stats.ModType.MORE, -0.5, Set.of(), "絕境：回復受阻"));
        }
        if (trial.has(Penalty.RESIST)) {
            mods.add(Modifier.flat(StatKeys.RESIST_FIRE, -20, "絕境：抗性削弱"));
            mods.add(Modifier.flat(StatKeys.RESIST_COLD, -20, "絕境：抗性削弱"));
            mods.add(Modifier.flat(StatKeys.RESIST_LIGHTNING, -20, "絕境：抗性削弱"));
        }
        if (trial.has(Penalty.COOLDOWN)) {
            mods.add(Modifier.increased(StatKeys.SKILL_COOLDOWN, 0.35, "絕境：冷卻遲滯"));
        }
        final double taken = trial.has(Penalty.VULNERABLE) ? 0.25 : 0;
        final double slow = trial.has(Penalty.SLOW) ? 0.25 : 0;
        if (mods.isEmpty() && taken == 0 && slow == 0) {
            return;
        }
        for (final Player player : arena) {
            plugin.combat().debuffs().apply(player.getUniqueId(), new Debuffs.Debuff(DEBUFF_ID, SOURCE, now + 40, taken, false, null, mods, slow));
        }
    }

    /** 這一回合過關：記獎勵、收怪；最後一回合直接結算，不然問收手還是續押。 */
    private void clearRound(final Trial trial, final long now) {
        final MapEventConfig.UltimatumSettings settings = settings();
        trial.round++;
        trial.earned.add(Map.entry(trial.nextReward, settings.rewards(trial.round)));
        removeMonsters(trial);
        if (plugin.monsterModule() != null && plugin.monsterModule().mechanics() != null) {
            plugin.monsterModule().mechanics().clearMapPatches(owner(trial));
        }
        for (final Player player : players(trial.instance)) {
            player.playSound(player.getLocation(), Sound.ENTITY_PLAYER_LEVELUP, 0.7f, 1.3f);
        }
        if (trial.round >= settings.rounds()) {
            // 2026-11-04 照站長「審判者 Boss」：全部回合打完，可以收手（×final_multiplier）或挑戰審判者（再 ×boss_multiplier＋牠的掉落；輸了全沒收）
            if (service.config().eventBoss(BOSS_KIND) == null) {
                cashOut(trial, settings.finalMultiplier(), players(trial.instance), "<#f5b942>絕境通關", "通關");
                return;
            }
            trial.phase = Phase.FINAL;
            trial.phaseStart = now;
            for (final Player player : inArena(trial)) {
                showFinal(trial, player);
            }
            return;
        }
        trial.phase = Phase.DECIDE;
        trial.phaseStart = now;
        offer(trial);
        for (final Player player : inArena(trial)) {
            showDecision(trial, player);
        }
    }

    private String earnedText(final Trial trial) {
        final List<String> parts = new ArrayList<>();
        for (final Map.Entry<MapEventConfig.Reward, Integer> entry : trial.earned) {
            parts.add(ItemRenderer.escape(entry.getKey().displayName()) + " ×" + entry.getValue());
        }
        return String.join("、", parts);
    }

    private void showDecision(final Trial trial, final Player player) {
        final MapEventConfig.UltimatumSettings settings = settings();
        final int next = trial.round + 1;
        final List<String> body = List.of(
                "<green>第 " + trial.round + " 回合過關　<gray>累積的獎勵：<white>" + earnedText(trial),
                "<gray>下一回合（" + next + "／" + settings.rounds() + "）：" + kindText(trial, trial.nextKind, next, inArena(trial).size()),
                "<gray>下一回合的獎勵：<white>" + ItemRenderer.escape(trial.nextReward.displayName()) + " ×" + settings.rewards(next)
                        + (next >= settings.rounds() && settings.finalMultiplier() != 1 ? "<gray>・打完全部回合獎勵 <#f5b942>×" + number(settings.finalMultiplier()) : ""),
                "<red>續押失敗會把累積的全部沒收");
        final List<Dialogs.Button> buttons = List.of(
                new Dialogs.Button("<green>收手（拿走累積的獎勵）", null, () -> {
                    if (trial.phase == Phase.DECIDE) {
                        cashOut(trial, 1, players(trial.instance), "<green>絕境：收手", "收手");
                    }
                }, null),
                new Dialogs.Button("<#ff5c6a>續押（第 " + next + " 回合）", "<gray>接著挑懲罰", () -> {
                    if (trial.phase == Phase.DECIDE) {
                        trial.phase = Phase.CHOOSING;
                        showChoice(trial, player);
                    }
                }, null));
        plugin.dialogs().notice(player, "<#c77dff>絕境：收手或續押", body, buttons, 2);
    }

    /** 全部回合打完：收手或挑戰審判者。 */
    private void showFinal(final Trial trial, final Player player) {
        final MapEventConfig.UltimatumSettings settings = settings();
        final MapConfig.EventBoss boss = service.config().eventBoss(BOSS_KIND);
        final String bossName = boss == null ? "審判者" : boss.boss().name();
        final List<String> body = List.of(
                "<#f5b942>" + settings.rounds() + " 回合全部打完　<gray>累積的獎勵：<white>" + earnedText(trial),
                "<gray>收手：獎勵 <#f5b942>×" + number(settings.finalMultiplier()),
                "<gray>挑戰" + ItemRenderer.escape(bossName) + "：贏了獎勵 <#f5b942>×" + number(settings.finalMultiplier() * settings.bossMultiplier())
                        + "<gray>，再加牠自己的掉落；會把場地裡的人直接送進牠的試場",
                MapService.eventBossNote(boss),
                "<red>輸了（沒打倒就離開、全員倒下）累積的全部沒收");
        final List<Dialogs.Button> buttons = List.of(
                new Dialogs.Button("<green>收手（×" + number(settings.finalMultiplier()) + "）", null, () -> {
                    if (trial.phase == Phase.FINAL) {
                        cashOut(trial, settings.finalMultiplier(), players(trial.instance), "<#f5b942>絕境通關", "通關");
                    }
                }, null),
                new Dialogs.Button("<#ff5c6a>挑戰" + ItemRenderer.escape(bossName), "<gray>贏了 ×" + number(settings.finalMultiplier() * settings.bossMultiplier()), () -> {
                    if (trial.phase == Phase.FINAL) {
                        challengeBoss(trial, player);
                    }
                }, null));
        plugin.dialogs().notice(player, "<#c77dff>絕境：收手或挑戰審判者", body, buttons, 2);
    }

    /** 開審判者的試場（特別的實例），把場地裡的人送進去；打倒牠才結算（×final×boss），沒打倒就沒了。 */
    private void challengeBoss(final Trial trial, final Player player) {
        final MapEventConfig.UltimatumSettings settings = settings();
        final MapConfig.EventBoss boss = service.config().eventBoss(BOSS_KIND);
        if (boss == null) {
            return;
        }
        final MapConfig.MapDef from = trial.instance.map();
        final MapConfig.MapDef arena = new MapConfig.MapDef(BOSS_KIND, "審判者的試場", null, 0, from.level(), from.theme(), 0, 0, List.of(), false,
                from.monsters(), boss.boss(), "秤的兩端：你拿走的，和你還欠的。", false, MapLayout.Style.OPEN);
        final double multiplier = settings.finalMultiplier() * settings.bossMultiplier();
        final MapService.Special special = new MapService.Special(BOSS_KIND, 0xC77DFF, (instance, players) ->
                cashOut(trial, multiplier, players, "<#f5b942>審判者倒下", "打倒審判者"));
        final MapStats stats = boss.fixedLevel() ? service.eventBossStats(boss, player, 0) : trial.instance.stats(); // 2026-11-04：災厄級固定等級
        final MapInstance opened = service.openSpecialInstance(player, arena, stats, MapService.UBER_ROOMS, special);
        if (opened == null) {
            final String problem = service.specialProblem();
            player.sendMessage(plugin.text().prefixed("<red>" + ItemRenderer.escape(problem == null ? "現在開不了試場，等一下再試" : problem)));
            return;
        }
        final List<UUID> going = new ArrayList<>();
        for (final Player each : inArena(trial)) {
            going.add(each.getUniqueId());
        }
        if (!going.contains(player.getUniqueId())) {
            going.add(player.getUniqueId());
        }
        service.sendInWhenOpen(opened, going);
        trial.phase = Phase.DONE;
        removeMonsters(trial);
        clearPatches(trial);
        hideBar(trial);
        trial.altar.getBlock().setType(Material.CHISELED_DEEPSLATE);
        broadcast(trial, "<#c77dff>" + ItemRenderer.escape(boss.boss().name()) + "在等你：試場蓋好就把你送過去，打倒牠獎勵 ×" + number(multiplier));
        plugin.getSLF4JLogger().info("地圖 #{}：{} 選了挑戰審判者（試場 #{}）", trial.instance.id(), player.getName(), opened.id());
    }

    /** 審判者的試場：路上不刷怪。 */
    static boolean isBossArena(final MapInstance instance) {
        return instance.special() != null && BOSS_KIND.equals(instance.special().kind());
    }

    /** 結算：這些人各拿累積的獎勵 × multiplier（吃物品數量）。 */
    private void cashOut(final Trial trial, final double multiplier, final List<Player> receivers, final String title, final String word) {
        trial.phase = Phase.DONE;
        removeMonsters(trial);
        clearPatches(trial);
        hideBar(trial);
        trial.altar.getBlock().setType(Material.CHISELED_DEEPSLATE);
        final RandomGenerator random = service.random();
        for (final Player player : receivers) {
            final tw.exilecore.loot.LootBonus.Bonus bonus = tw.exilecore.loot.LootBonus.of(plugin, player)
                    .plus(trial.instance.stats().quantity() / 100.0, trial.instance.stats().rarity() / 100.0);
            List<ItemStack> items = new ArrayList<>();
            for (final Map.Entry<MapEventConfig.Reward, Integer> entry : trial.earned) {
                final int shares = (int) Math.round(entry.getValue() * multiplier);
                final int times = bonus.amount(shares, random);
                for (int i = 0; i < times; i++) {
                    final MapRewards.Bundle bundle = MapRewards.roll(plugin, service, entry.getKey(), trial.instance, player, config.delirium(),
                            MapEventConfig.DELIRIUM_SOURCE, random);
                    if (bundle != null) {
                        items.addAll(bundle.items());
                    }
                }
            }
            if (plugin.loot() != null) {
                items = plugin.loot().filterRewards(player, items, trial.instance.level());
            }
            final boolean dropped = MapRewards.give(plugin, player, items);
            player.showTitle(Title.title(plugin.text().parse(title),
                    plugin.text().parse("<gray>" + trial.round + " 回合・拿到 <white>" + items.size() + "</white> 件" + (dropped ? "（背包滿了，有的掉在腳下）" : "")),
                    Title.Times.times(Duration.ofMillis(200), Duration.ofMillis(3000), Duration.ofMillis(600))));
            player.playSound(player.getLocation(), multiplier > 1 ? Sound.UI_TOAST_CHALLENGE_COMPLETE : Sound.ENTITY_PLAYER_LEVELUP, 1.0f, 1.0f);
            if (plugin.events() != null) {
                plugin.events().onMapEvent(player, trial.instance.level()); // 每月挑戰：算一個地圖事件
            }
        }
        plugin.getSLF4JLogger().info("地圖 #{}：絕境{}（{} 回合，×{}）", trial.instance.id(), word, trial.round, number(multiplier));
    }

    private void fail(final Trial trial, final String why) {
        trial.phase = Phase.DONE;
        trial.failed = true;
        removeMonsters(trial);
        clearPatches(trial);
        hideBar(trial);
        trial.altar.getBlock().setType(Material.CHISELED_DEEPSLATE);
        for (final Player player : players(trial.instance)) {
            player.showTitle(Title.title(plugin.text().parse("<red>絕境失敗"),
                    plugin.text().parse("<gray>" + ItemRenderer.escape(why) + (trial.earned.isEmpty() ? "" : "・累積的獎勵沒收（" + earnedText(trial) + "）")),
                    Title.Times.times(Duration.ofMillis(200), Duration.ofMillis(3000), Duration.ofMillis(600))));
            player.playSound(player.getLocation(), Sound.ENTITY_WITHER_DEATH, 0.4f, 0.6f);
        }
        plugin.getSLF4JLogger().info("地圖 #{}：絕境失敗（{}，打完 {} 回合）", trial.instance.id(), why, trial.round);
    }

    // ---- 殺怪 ----

    /** 絕境的怪死了：殲滅回合算一隻（誰殺的都算）。 */
    @EventHandler(priority = EventPriority.MONITOR)
    public void onDeath(final EntityDeathEvent event) {
        final LivingEntity entity = event.getEntity();
        if (byInstance.isEmpty() || !entity.getPersistentDataContainer().has(monsterKey)) {
            return;
        }
        final MapInstance instance = service.instanceOf(entity);
        final Trial trial = instance == null ? null : byInstance.get(instance.id());
        if (trial == null || trial.phase != Phase.FIGHT || trial.nextKind != Kind.KILL) {
            return;
        }
        trial.kills++;
    }

    /** 右鍵審判之壇（跟幻霧鏡一樣用 LOWEST、不 ignoreCancelled）。 */
    @EventHandler(priority = EventPriority.LOWEST)
    public void onInteract(final org.bukkit.event.player.PlayerInteractEvent event) {
        if (event.getAction() != org.bukkit.event.block.Action.RIGHT_CLICK_BLOCK || event.getClickedBlock() == null
                || event.getHand() != org.bukkit.inventory.EquipmentSlot.HAND || byInstance.isEmpty()) {
            return;
        }
        final Block clicked = event.getClickedBlock();
        for (final Trial trial : byInstance.values()) {
            final Block base = trial.altar.getBlock();
            if (!clicked.getWorld().equals(base.getWorld()) || clicked.getX() != base.getX() || clicked.getZ() != base.getZ() || clicked.getY() != base.getY()) {
                continue;
            }
            if (!MapEvents.withinReach(event.getPlayer(), clicked) || trial.instance.state() != MapInstance.State.OPEN) {
                return;
            }
            event.setCancelled(true);
            event.setUseItemInHand(org.bukkit.event.Event.Result.DENY);
            if (plugin.skills() != null) {
                plugin.skills().consumeRightClick(event.getPlayer());
            }
            interact(trial, event.getPlayer());
            return;
        }
    }

    // ---- 畫面 ----

    private void updateBar(final Trial trial, final List<Player> players, final long now) {
        final MapEventConfig.UltimatumSettings settings = settings();
        if (trial.bar == null) {
            trial.bar = BossBar.bossBar(Component.empty(), 1f, BossBar.Color.PURPLE, BossBar.Overlay.NOTCHED_6);
        }
        final int round = trial.round + 1;
        final String text;
        float progress;
        switch (trial.phase) {
            case CHOOSING -> {
                text = "<#c77dff>絕境　<gray>第 " + round + "／" + settings.rounds() + " 回合：右鍵審判之壇挑懲罰";
                progress = 0;
            }
            case PREPARE -> {
                text = "<#c77dff>絕境　<gray>第 " + round + "／" + settings.rounds() + " 回合 <white>" + Math.max(0, (trial.deadline - now + 19) / 20) + "</white> 秒後開始";
                progress = 0;
            }
            case FIGHT -> {
                final long left = Math.max(0, (trial.deadline - now + 19) / 20);
                final String goal = switch (trial.nextKind) {
                    case KILL -> "殺 <white>" + trial.kills + "</white>／" + trial.needed + " 隻";
                    case SURVIVE -> "撐住";
                    case CIRCLE -> "圈裡 <white>" + trial.circleTicks / 20 + "</white>／" + trial.needed / 20 + " 秒";
                };
                text = "<#c77dff>絕境　<white>第 " + round + "／" + settings.rounds() + " 回合　<gray>" + goal + "　剩 " + left + " 秒"
                        + (trial.chosen.isEmpty() ? "" : "　<#ff5c6a>" + trial.chosen.size() + " 個懲罰");
                progress = switch (trial.nextKind) {
                    case KILL -> trial.needed <= 0 ? 1 : trial.kills / (float) trial.needed;
                    case SURVIVE -> trial.needed <= 0 ? 1 : (now - trial.phaseStart) / (float) trial.needed;
                    case CIRCLE -> trial.needed <= 0 ? 1 : trial.circleTicks / (float) trial.needed;
                };
            }
            case DECIDE -> {
                text = "<#c77dff>絕境　<green>第 " + trial.round + " 回合過關　<gray>右鍵審判之壇：收手或續押（累積 " + earnedText(trial) + "）";
                progress = 1;
            }
            case FINAL -> {
                text = "<#c77dff>絕境　<#f5b942>全部打完　<gray>右鍵審判之壇：收手或挑戰審判者（累積 " + earnedText(trial) + "）";
                progress = 1;
            }
            default -> {
                text = "<#c77dff>絕境";
                progress = 0;
            }
        }
        trial.bar.progress(Math.max(0, Math.min(1, progress)));
        trial.bar.name(plugin.text().parse(text));
        final Set<UUID> seen = new HashSet<>();
        for (final Player player : players) {
            if (MapHudFeed.drawn(plugin, player)) {
                continue; // 模組的地圖資訊框已經寫了
            }
            seen.add(player.getUniqueId());
            if (trial.watching.add(player.getUniqueId())) {
                player.showBossBar(trial.bar);
            }
        }
        for (final UUID uuid : Set.copyOf(trial.watching)) {
            if (!seen.contains(uuid)) {
                final Player player = plugin.getServer().getPlayer(uuid);
                if (player != null) {
                    player.hideBossBar(trial.bar);
                }
                trial.watching.remove(uuid);
            }
        }
    }

    /** 模組的地圖資訊框那一行（沒有絕境回傳 null）。 */
    tw.exilecore.clientmod.WorldHudProtocol.EventLine hudLine(final MapInstance instance, final long now) {
        final Trial trial = byInstance.get(instance.id());
        if (trial == null) {
            return null;
        }
        final int color = 0xC77DFF;
        return switch (trial.phase) {
            case IDLE -> new tw.exilecore.clientmod.WorldHudProtocol.EventLine("絕境", color, "右鍵審判之壇", -1, tw.exilecore.clientmod.WorldHudProtocol.EVENT_IDLE);
            case DONE -> new tw.exilecore.clientmod.WorldHudProtocol.EventLine("絕境", color, trial.failed ? "失敗" : "結束・" + trial.round + " 回合", -1,
                    trial.failed ? tw.exilecore.clientmod.WorldHudProtocol.EVENT_FAILED : tw.exilecore.clientmod.WorldHudProtocol.EVENT_DONE);
            case FIGHT -> {
                final String goal = switch (trial.nextKind) {
                    case KILL -> "殺 " + trial.kills + "／" + trial.needed;
                    case SURVIVE -> "撐住";
                    case CIRCLE -> "圈裡 " + trial.circleTicks / 20 + "／" + trial.needed / 20 + " 秒";
                };
                yield new tw.exilecore.clientmod.WorldHudProtocol.EventLine("絕境", color, "第 " + (trial.round + 1) + " 回合・" + goal + "・剩 "
                        + Math.max(0, (trial.deadline - now + 19) / 20) + " 秒", MapEvents.percent(1 - (now - trial.phaseStart) / (double) Math.max(1, trial.deadline - trial.phaseStart)),
                        tw.exilecore.clientmod.WorldHudProtocol.EVENT_ACTIVE);
            }
            default -> new tw.exilecore.clientmod.WorldHudProtocol.EventLine("絕境", color, "第 " + (trial.round + 1) + " 回合・右鍵審判之壇", -1,
                    tw.exilecore.clientmod.WorldHudProtocol.EVENT_ACTIVE);
        };
    }

    private TextDisplay label(final Trial trial) {
        final Location at = trial.altar.clone().add(0, 2.2, 0);
        final TextDisplay display = at.getWorld().spawn(at, TextDisplay.class, created -> {
            created.setBillboard(Display.Billboard.CENTER);
            created.setPersistent(false);
            created.setSeeThrough(false);
            created.setShadowed(true);
        });
        display.text(plugin.text().parse("<#c77dff>審判之壇<newline><gray>右鍵開始絕境：" + settings().rounds() + " 回合，每回合挑一個懲罰疊上去"
                + "<newline><gray>打完一回合可以收手拿獎勵，或續押——失敗全部沒收"));
        return display;
    }

    private void broadcast(final Trial trial, final String miniMessage) {
        for (final Player player : players(trial.instance)) {
            player.sendMessage(plugin.text().prefixed(miniMessage));
        }
    }

    private static String number(final double value) {
        return value == Math.rint(value) ? String.valueOf((long) value) : String.valueOf(value);
    }

    // ---- 清理 ----

    private void removeMonsters(final Trial trial) {
        for (final LivingEntity monster : trial.monsters) {
            if (monster.isValid()) {
                monster.remove();
            }
        }
        trial.monsters.clear();
    }

    private void clearPatches(final Trial trial) {
        if (plugin.monsterModule() != null && plugin.monsterModule().mechanics() != null) {
            plugin.monsterModule().mechanics().clearMapPatches(owner(trial));
        }
    }

    private void hideBar(final Trial trial) {
        if (trial.bar == null) {
            return;
        }
        for (final UUID uuid : trial.watching) {
            final Player player = plugin.getServer().getPlayer(uuid);
            if (player != null) {
                player.hideBossBar(trial.bar);
            }
        }
        trial.watching.clear();
    }

    private void dispose(final Trial trial) {
        if (trial.label != null && trial.label.isValid()) {
            trial.label.remove();
        }
        trial.label = null;
        removeMonsters(trial);
        clearPatches(trial);
        hideBar(trial);
    }

    void clear(final MapInstance instance) {
        final Trial trial = byInstance.remove(instance.id());
        if (trial != null) {
            dispose(trial);
        }
    }

    void clearAll() {
        for (final Trial trial : byInstance.values()) {
            dispose(trial);
        }
        byInstance.clear();
    }

    /** /ec map 的狀態。 */
    String summary(final MapInstance instance) {
        final Trial trial = byInstance.get(instance.id());
        if (trial == null) {
            return null;
        }
        return "絕境" + switch (trial.phase) {
            case IDLE -> "（祭壇還沒碰）";
            case DONE -> trial.failed ? "（失敗）" : "（結束，" + trial.round + " 回合）";
            default -> "（第 " + (trial.round + 1) + " 回合）";
        };
    }

    boolean isUltimatumMonster(final Entity entity) {
        return entity.getPersistentDataContainer().has(monsterKey);
    }
}
