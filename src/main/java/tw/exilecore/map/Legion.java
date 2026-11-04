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
import org.bukkit.event.entity.EntityDamageEvent;
import org.bukkit.event.entity.EntityDeathEvent;
import org.bukkit.inventory.ItemStack;
import org.bukkit.persistence.PersistentDataType;
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
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.random.RandomGenerator;

/**
 * 2026-11-04 照站長「軍團」：軍團（照 PoE 的 Legion）。地圖裡一座時沉石碑，右鍵敲開：兩支軍隊凍在時間裡出現在房間兩側，
 * 限時內你打到的怪才會在時間到時醒過來（沒打到的消失），醒來的殺了才掉東西——帶著獎勵的（名字寫著種類）、軍曹（稀有、獎勵兩份）、
 * 將領（傳奇、獎勵三份）。凍著的時候打死也算。設定在 {@code map_events.yml} 的 {@code legion}。
 */
final class Legion implements Listener {

    private enum Phase { IDLE, FROZEN, ACTIVE, DONE }

    /** 一隻軍團的怪：哪一邊、帶什麼獎勵、幾份、稀有度（將領、軍曹掉徽記）。 */
    private record Soldier(int side, MapEventConfig.Reward reward, int shares, MonsterRarity rarity) {
    }

    /** 時沉領域（Boss 場）的實例種類；也是 maps.yml event_bosses 的代號。 */
    static final String DOMAIN_KIND = "legion";

    /** 一張圖的軍團。 */
    final class Monolith {
        final MapInstance instance;
        final MapLayout.Room room;
        final Location stone;
        TextDisplay label;
        Phase phase = Phase.IDLE;
        long releaseAt;
        final Map<UUID, Soldier> soldiers = new HashMap<>();
        final List<LivingEntity> monsters = new ArrayList<>();
        final Set<UUID> touched = new HashSet<>();
        int released;
        int rewardsGiven;
        BossBar bar;
        final Set<UUID> watching = new HashSet<>();

        Monolith(final MapInstance instance, final MapLayout.Room room, final Location stone) {
            this.instance = instance;
            this.room = room;
            this.stone = stone;
        }
    }

    private static final String[] ARMIES = {"霜骸軍", "焰鐵軍"};
    private static final int[] ARMY_COLORS = {0x9FD3FF, 0xFF8A65};

    private final ExileCore plugin;
    private final MapService service;
    private final MapEventConfig config;
    private final Map<Integer, Monolith> byInstance = new HashMap<>();
    private final NamespacedKey monsterKey;

    Legion(final ExileCore plugin, final MapService service, final MapEventConfig config) {
        this.plugin = plugin;
        this.service = service;
        this.config = config;
        this.monsterKey = new NamespacedKey(plugin, "legion_monster");
    }

    MapEventConfig.LegionSettings settings() {
        return config.legion();
    }

    // ---- 放置 ----

    void place(final MapInstance instance, final List<MapLayout.Room> rooms) {
        final MapEventConfig.LegionSettings settings = settings();
        final RandomGenerator random = service.random();
        final double chance = Math.min(1.0, settings.chance() * (1 + instance.atlas().increased("atlas.legion.chance")));
        if (instance.atlas().get("atlas.other_events_off") > 0 || instance.special() != null || chance <= 0 || random.nextDouble() >= chance || rooms.isEmpty()) {
            return;
        }
        for (int attempt = 0; attempt < 3 && !rooms.isEmpty(); attempt++) {
            final MapLayout.Room room = rooms.remove(random.nextInt(rooms.size()));
            final MapLayout.Spot spot = MapEvents.spotIn(instance.layout(), room);
            if (spot == null) {
                continue;
            }
            final Location stone = new Location(instance.world(), instance.originX() + spot.x() + 0.5, MapLayout.FLOOR_Y + 1, instance.originZ() + spot.z() + 0.5);
            stone.getBlock().setType(Material.CHISELED_TUFF);
            stone.getBlock().getRelative(0, 1, 0).setType(Material.CHISELED_TUFF_BRICKS);
            byInstance.put(instance.id(), new Monolith(instance, room, stone));
            return;
        }
    }

    // ---- 每 10 tick ----

    void tick(final long now) {
        if (byInstance.isEmpty()) {
            return;
        }
        final Iterator<Map.Entry<Integer, Monolith>> iterator = byInstance.entrySet().iterator();
        while (iterator.hasNext()) {
            final Map.Entry<Integer, Monolith> entry = iterator.next();
            final Monolith monolith = entry.getValue();
            final MapInstance instance = service.instance(entry.getKey());
            if (instance == null || instance.state() == MapInstance.State.CLOSING) {
                dispose(monolith);
                iterator.remove();
                continue;
            }
            if (instance.state() != MapInstance.State.OPEN || monolith.phase == Phase.DONE) {
                continue;
            }
            try {
                tickMonolith(monolith, now);
            } catch (final RuntimeException e) {
                plugin.getSLF4JLogger().error("地圖 #{} 的軍團出錯", instance.id(), e);
                finish(monolith);
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

    private void tickMonolith(final Monolith monolith, final long now) {
        final Location stone = monolith.stone;
        if (monolith.phase == Phase.IDLE) {
            if (!stone.getWorld().isChunkLoaded(stone.getBlockX() >> 4, stone.getBlockZ() >> 4)) {
                return;
            }
            if (monolith.label == null || !monolith.label.isValid()) {
                monolith.label = label(monolith);
            }
            tw.exilecore.skill.Fx.spawn(stone.getWorld(), Particle.ENCHANT, stone.getX(), stone.getY() + 1.5, stone.getZ(), 6, 0.4, 0.8, 0.4, 0.3);
            return;
        }
        monolith.monsters.removeIf(monster -> !monster.isValid() || monster.isDead());
        final List<Player> players = players(monolith.instance);
        if (monolith.phase == Phase.FROZEN) {
            for (final LivingEntity monster : monolith.monsters) {
                final boolean touched = monolith.touched.contains(monster.getUniqueId());
                tw.exilecore.skill.Fx.spawn(monster.getWorld(), touched ? Particle.END_ROD : Particle.SNOWFLAKE, monster.getLocation().add(0, 1, 0), 2, 0.3, 0.5, 0.3, 0.0);
            }
            if (now >= monolith.releaseAt) {
                release(monolith);
            }
        } else if (monolith.phase == Phase.ACTIVE && monolith.monsters.isEmpty()) {
            finish(monolith);
            return;
        }
        updateBar(monolith, players, now);
    }

    // ---- 敲開 ----

    private void interact(final Monolith monolith, final Player player) {
        if (monolith.phase != Phase.IDLE) {
            player.sendMessage(plugin.text().prefixed(monolith.phase == Phase.DONE ? "<gray>這座石碑已經結束了" : "<gray>軍團已經出來了"));
            return;
        }
        final MapEventConfig.LegionSettings settings = settings();
        final RandomGenerator random = service.random();
        final MapInstance instance = monolith.instance;
        final long now = plugin.getServer().getCurrentTick();
        monolith.phase = Phase.FROZEN;
        monolith.releaseAt = now + Math.round(settings.freezeSeconds() * 20);
        if (monolith.label != null && monolith.label.isValid()) {
            monolith.label.remove();
            monolith.label = null;
        }
        monolith.stone.getBlock().setType(Material.CRACKED_DEEPSLATE_TILES);
        monolith.stone.getBlock().getRelative(0, 1, 0).setType(Material.AIR);
        monolith.stone.getWorld().playSound(monolith.stone, Sound.BLOCK_GLASS_BREAK, 1.0f, 0.6f);
        monolith.stone.getWorld().playSound(monolith.stone, Sound.BLOCK_BEACON_POWER_SELECT, 1.0f, 0.5f);
        tw.exilecore.skill.Fx.spawn(monolith.stone.getWorld(), Particle.SNOWFLAKE, monolith.stone.getX(), monolith.stone.getY() + 1, monolith.stone.getZ(), 200, 6, 1.5, 6, 0.05);
        // 每一邊幾隻：帶獎勵的、軍曹、將領（一邊一隻）；其他是普通兵
        final int players = Math.max(1, players(instance).size());
        final int total = (int) Math.round(settings.soldiers() * (1 + settings.perPlayer() * (players - 1)));
        final int rewardsEach = settings.rewardCarriers() / 2 + (settings.rewardCarriers() % 2);
        final int sergeantsEach = settings.sergeants() / 2 + (settings.sergeants() % 2);
        for (int side = 0; side < 2; side++) {
            final int count = total / 2 + (side == 0 ? total % 2 : 0);
            for (int i = 0; i < count; i++) {
                final MonsterRarity rarity;
                final MapEventConfig.Reward reward;
                final int shares;
                if (i == 0 && settings.generals() > side) {
                    rarity = MonsterRarity.UNIQUE;
                    reward = MapRewards.pick(settings.rewardWeights(), random);
                    shares = settings.generalShares();
                } else if (i < 1 + sergeantsEach) {
                    rarity = MonsterRarity.RARE;
                    reward = MapRewards.pick(settings.rewardWeights(), random);
                    shares = settings.sergeantShares();
                } else if (i < 1 + sergeantsEach + rewardsEach) {
                    rarity = random.nextDouble() < 0.3 ? MonsterRarity.MAGIC : MonsterRarity.NORMAL;
                    reward = MapRewards.pick(settings.rewardWeights(), random);
                    shares = 1;
                } else {
                    rarity = random.nextDouble() < 0.25 ? MonsterRarity.MAGIC : MonsterRarity.NORMAL;
                    reward = null;
                    shares = 0;
                }
                spawnSoldier(monolith, side, rarity, reward, shares, random);
            }
        }
        for (final Player each : players(instance)) {
            each.showTitle(Title.title(plugin.text().parse("<#b8c4ff>軍團"),
                    plugin.text().parse("<gray>" + Math.round(settings.freezeSeconds()) + " 秒內打到的怪才會醒來：名字寫著獎勵的、軍曹、將領優先"),
                    Title.Times.times(Duration.ofMillis(300), Duration.ofMillis(3000), Duration.ofMillis(800))));
            each.playSound(each.getLocation(), Sound.BLOCK_BELL_RESONATE, 1.0f, 0.6f);
        }
        plugin.getSLF4JLogger().info("地圖 #{}：{} 敲開了時沉石碑（{} 隻）", instance.id(), player.getName(), monolith.monsters.size());
    }

    /** 在房間的一側刷一隻凍著的兵（side 0 西半、1 東半）。 */
    private void spawnSoldier(final Monolith monolith, final int side, final MonsterRarity rarity, final MapEventConfig.Reward reward, final int shares,
                              final RandomGenerator random) {
        final MapInstance instance = monolith.instance;
        final ZoneConfig.SpawnEntry entry = service.spawner().chooseMonster(instance.map().monsters(), random);
        if (entry == null) {
            return;
        }
        final MapLayout.Room room = monolith.room;
        Location at = null;
        for (int attempt = 0; attempt < 20 && at == null; attempt++) {
            final int dx = (1 + random.nextInt(Math.max(1, room.halfX() - 1))) * (side == 0 ? -1 : 1);
            final int z = room.cz() + random.nextInt(room.halfZ() * 2 + 1) - room.halfZ();
            final int x = room.cx() + dx;
            if (room.contains(x, z) && instance.layout().isFloor(x, z)) {
                at = new Location(instance.world(), instance.originX() + x + 0.5, MapLayout.FLOOR_Y + 1, instance.originZ() + z + 0.5,
                        side == 0 ? -90f : 90f, 0f);
            }
        }
        if (at == null) {
            return;
        }
        final List<String> mods = rarity == MonsterRarity.NORMAL ? List.of() : plugin.monsters().rollMods(rarity, instance.level());
        final LivingEntity monster = service.spawner().spawn(instance, entry, at, rarity, mods);
        if (monster == null) {
            return;
        }
        monster.getPersistentDataContainer().set(monsterKey, PersistentDataType.BYTE, (byte) 1);
        final String role = rarity == MonsterRarity.UNIQUE ? "將領" : rarity == MonsterRarity.RARE ? "軍曹" : reward != null ? "持寶" : "";
        final String tag = reward == null ? "" : "〔" + reward.displayName() + (shares > 1 ? " ×" + shares : "") + "〕";
        plugin.monsters().data(monster).ifPresent(data -> plugin.monsters().decorateData(monster, new MonsterData(data.level(), data.rarity(),
                data.mods(), ARMIES[side] + role + tag + "・" + data.name(), data.lifeMultiplier() * (rarity == MonsterRarity.UNIQUE ? settings().generalLife() : 1),
                data.damageMultiplier(), false)));
        if (monster instanceof Mob mob) {
            mob.setAI(false);
        }
        monster.setGlowing(reward != null);
        monolith.monsters.add(monster);
        monolith.soldiers.put(monster.getUniqueId(), new Soldier(side, reward, shares, rarity));
    }

    /** 時間到：打到的醒來（開 AI、找人），沒打到的消失。 */
    private void release(final Monolith monolith) {
        monolith.phase = Phase.ACTIVE;
        final List<Player> players = players(monolith.instance);
        int woke = 0;
        for (final LivingEntity monster : List.copyOf(monolith.monsters)) {
            if (!monolith.touched.contains(monster.getUniqueId())) {
                tw.exilecore.skill.Fx.spawn(monster.getWorld(), Particle.SNOWFLAKE, monster.getLocation().add(0, 1, 0), 15, 0.3, 0.5, 0.3, 0.02);
                monster.remove();
                monolith.monsters.remove(monster);
                monolith.soldiers.remove(monster.getUniqueId());
                continue;
            }
            if (monster instanceof Mob mob) {
                mob.setAI(true);
                Player nearest = null;
                double best = Double.MAX_VALUE;
                for (final Player player : players) {
                    final double distance = player.getLocation().distanceSquared(monster.getLocation());
                    if (distance < best) {
                        best = distance;
                        nearest = player;
                    }
                }
                if (nearest != null) {
                    mob.setTarget(nearest);
                }
            }
            tw.exilecore.skill.Fx.spawn(monster.getWorld(), Particle.END_ROD, monster.getLocation().add(0, 1, 0), 12, 0.3, 0.5, 0.3, 0.05);
            woke++;
        }
        monolith.released = woke;
        for (final Player player : players) {
            player.playSound(player.getLocation(), Sound.BLOCK_GLASS_BREAK, 1.0f, 0.8f);
            player.playSound(player.getLocation(), Sound.ENTITY_RAVAGER_ROAR, 0.6f, 1.2f);
            player.sendMessage(plugin.text().prefixed("<#b8c4ff>軍團醒了：<white>" + woke + "</white> 隻（沒打到的消失了）"));
        }
        if (woke == 0) {
            finish(monolith);
        }
    }

    private void finish(final Monolith monolith) {
        monolith.phase = Phase.DONE;
        hideBar(monolith);
        for (final Player player : players(monolith.instance)) {
            player.sendMessage(plugin.text().prefixed("<#b8c4ff>軍團結束：<gray>醒來 " + monolith.released + " 隻、拿到 " + monolith.rewardsGiven + " 份獎勵"));
            if (plugin.events() != null) {
                plugin.events().onMapEvent(player, monolith.instance.level()); // 每月挑戰：算一個地圖事件
            }
        }
        plugin.getSLF4JLogger().info("地圖 #{}：軍團結束（醒來 {} 隻、{} 份獎勵）", monolith.instance.id(), monolith.released, monolith.rewardsGiven);
    }

    // ---- 打到、打死 ----

    /** 凍著的怪被打到：記下來（時間到才會醒）。 */
    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onDamage(final EntityDamageEvent event) {
        if (byInstance.isEmpty() || !(event.getEntity() instanceof LivingEntity entity) || !entity.getPersistentDataContainer().has(monsterKey)) {
            return;
        }
        final MapInstance instance = service.instanceOf(entity);
        final Monolith monolith = instance == null ? null : byInstance.get(instance.id());
        if (monolith == null || monolith.phase != Phase.FROZEN) {
            return;
        }
        if (monolith.touched.add(entity.getUniqueId())) {
            entity.setGlowing(true);
        }
    }

    /** 軍團的怪死了：帶著獎勵的給有份的人各一份（凍著的時候打死也算）。 */
    @EventHandler(priority = EventPriority.MONITOR)
    public void onDeath(final EntityDeathEvent event) {
        final LivingEntity entity = event.getEntity();
        if (byInstance.isEmpty() || !entity.getPersistentDataContainer().has(monsterKey)) {
            return;
        }
        final MapInstance instance = service.instanceOf(entity);
        final Monolith monolith = instance == null ? null : byInstance.get(instance.id());
        if (monolith == null) {
            return;
        }
        final Soldier soldier = monolith.soldiers.remove(entity.getUniqueId());
        monolith.monsters.remove(entity);
        if (soldier == null || soldier.reward == null || soldier.shares <= 0) {
            return;
        }
        final RandomGenerator random = service.random();
        // 2026-11-04 照站長「軍團也要有 Boss」：將領、軍曹掉時沉徽記（有份的人各擲一次；吃物品數量）
        final double emblemChance = soldier.rarity == MonsterRarity.UNIQUE ? settings().generalEmblem() : soldier.rarity == MonsterRarity.RARE ? settings().sergeantEmblem() : 0;
        final CurrencyType emblem = emblem();
        for (final Player player : plugin.monsterModule().credit().sharers(entity, entity.getKiller())) {
            final tw.exilecore.loot.LootBonus.Bonus bonus = tw.exilecore.loot.LootBonus.of(plugin, player)
                    .plus(instance.stats().quantity() / 100.0, instance.stats().rarity() / 100.0);
            List<ItemStack> items = new ArrayList<>();
            final int times = bonus.amount(soldier.shares, random);
            for (int i = 0; i < times; i++) {
                final MapRewards.Bundle bundle = MapRewards.roll(plugin, service, soldier.reward, instance, player, config.delirium(),
                        MapEventConfig.DELIRIUM_SOURCE, random);
                if (bundle != null) {
                    items.addAll(bundle.items());
                }
            }
            if (plugin.loot() != null) {
                items = plugin.loot().filterRewards(player, items, instance.level());
            }
            if (emblem != null && emblemChance > 0 && random.nextDouble() < bonus.more(emblemChance)) {
                items.add(plugin.currencies().create(emblem, 1));
                player.sendMessage(plugin.text().prefixed("<#b8c4ff>+1 " + ItemRenderer.escape(emblem.name()) + "<gray>（" + settings().domainCost() + " 個在地圖裝置開"
                        + ItemRenderer.escape(settings().domainName()) + "）"));
            }
            MapRewards.give(plugin, player, items);
            monolith.rewardsGiven++;
            tw.exilecore.gui.ActionBar.send(player, plugin.text().parse("<#b8c4ff>軍團：" + ItemRenderer.escape(soldier.reward.displayName()) + " ×" + soldier.shares
                    + " <gray>（" + items.size() + " 件）"));
        }
    }

    /** 右鍵時沉石碑（跟幻霧鏡一樣用 LOWEST、不 ignoreCancelled）。 */
    @EventHandler(priority = EventPriority.LOWEST)
    public void onInteract(final org.bukkit.event.player.PlayerInteractEvent event) {
        if (event.getAction() != org.bukkit.event.block.Action.RIGHT_CLICK_BLOCK || event.getClickedBlock() == null
                || event.getHand() != org.bukkit.inventory.EquipmentSlot.HAND || byInstance.isEmpty()) {
            return;
        }
        final Block clicked = event.getClickedBlock();
        for (final Monolith monolith : byInstance.values()) {
            final Block base = monolith.stone.getBlock();
            if (!clicked.getWorld().equals(base.getWorld()) || clicked.getX() != base.getX() || clicked.getZ() != base.getZ()
                    || clicked.getY() < base.getY() || clicked.getY() > base.getY() + 1) {
                continue;
            }
            if (!MapEvents.withinReach(event.getPlayer(), clicked) || monolith.instance.state() != MapInstance.State.OPEN) {
                return;
            }
            event.setCancelled(true);
            event.setUseItemInHand(org.bukkit.event.Event.Result.DENY);
            if (plugin.skills() != null) {
                plugin.skills().consumeRightClick(event.getPlayer());
            }
            interact(monolith, event.getPlayer());
            return;
        }
    }

    // ---- 畫面 ----

    private void updateBar(final Monolith monolith, final List<Player> players, final long now) {
        if (monolith.bar == null) {
            monolith.bar = BossBar.bossBar(Component.empty(), 1f, BossBar.Color.BLUE, BossBar.Overlay.PROGRESS);
        }
        final String text;
        final float progress;
        if (monolith.phase == Phase.FROZEN) {
            final long left = Math.max(0, (monolith.releaseAt - now + 19) / 20);
            text = "<#b8c4ff>軍團　<gray>凍住 <white>" + left + "</white> 秒　打到的 <white>" + monolith.touched.size() + "</white>／" + monolith.monsters.size() + " 隻會醒來";
            progress = (float) (left / Math.max(1.0, settings().freezeSeconds()));
        } else {
            text = "<#b8c4ff>軍團　<gray>醒來的還剩 <white>" + monolith.monsters.size() + "</white> 隻　拿到 " + monolith.rewardsGiven + " 份";
            progress = monolith.released <= 0 ? 0 : monolith.monsters.size() / (float) monolith.released;
        }
        monolith.bar.progress(Math.max(0, Math.min(1, progress)));
        monolith.bar.name(plugin.text().parse(text));
        final Set<UUID> seen = new HashSet<>();
        for (final Player player : players) {
            if (MapHudFeed.drawn(plugin, player)) {
                continue;
            }
            seen.add(player.getUniqueId());
            if (monolith.watching.add(player.getUniqueId())) {
                player.showBossBar(monolith.bar);
            }
        }
        for (final UUID uuid : Set.copyOf(monolith.watching)) {
            if (!seen.contains(uuid)) {
                final Player player = plugin.getServer().getPlayer(uuid);
                if (player != null) {
                    player.hideBossBar(monolith.bar);
                }
                monolith.watching.remove(uuid);
            }
        }
    }

    tw.exilecore.clientmod.WorldHudProtocol.EventLine hudLine(final MapInstance instance, final long now) {
        final Monolith monolith = byInstance.get(instance.id());
        if (monolith == null) {
            return null;
        }
        final int color = 0xB8C4FF;
        return switch (monolith.phase) {
            case IDLE -> new tw.exilecore.clientmod.WorldHudProtocol.EventLine("軍團", color, "右鍵時沉石碑", -1, tw.exilecore.clientmod.WorldHudProtocol.EVENT_IDLE);
            case FROZEN -> new tw.exilecore.clientmod.WorldHudProtocol.EventLine("軍團", color, "凍住 " + Math.max(0, (monolith.releaseAt - now + 19) / 20)
                    + " 秒・打到 " + monolith.touched.size() + "／" + monolith.monsters.size(),
                    MapEvents.percent((monolith.releaseAt - now) / (20.0 * Math.max(1, settings().freezeSeconds()))), tw.exilecore.clientmod.WorldHudProtocol.EVENT_ACTIVE);
            case ACTIVE -> new tw.exilecore.clientmod.WorldHudProtocol.EventLine("軍團", color, "剩 " + monolith.monsters.size() + " 隻", -1,
                    tw.exilecore.clientmod.WorldHudProtocol.EVENT_ACTIVE);
            default -> new tw.exilecore.clientmod.WorldHudProtocol.EventLine("軍團", color, "結束・" + monolith.rewardsGiven + " 份獎勵", -1,
                    tw.exilecore.clientmod.WorldHudProtocol.EVENT_DONE);
        };
    }

    private TextDisplay label(final Monolith monolith) {
        final Location at = monolith.stone.clone().add(0, 3.0, 0);
        final TextDisplay display = at.getWorld().spawn(at, TextDisplay.class, created -> {
            created.setBillboard(Display.Billboard.CENTER);
            created.setPersistent(false);
            created.setSeeThrough(false);
            created.setShadowed(true);
        });
        display.text(plugin.text().parse("<#b8c4ff>時沉石碑<newline><gray>右鍵敲開：兩支軍隊凍在時間裡，" + Math.round(settings().freezeSeconds())
                + " 秒內打到的才會醒來<newline><gray>名字寫著獎勵的、軍曹、將領殺了才給東西"));
        return display;
    }

    // ---- 清理 ----

    private void hideBar(final Monolith monolith) {
        if (monolith.bar == null) {
            return;
        }
        for (final UUID uuid : monolith.watching) {
            final Player player = plugin.getServer().getPlayer(uuid);
            if (player != null) {
                player.hideBossBar(monolith.bar);
            }
        }
        monolith.watching.clear();
    }

    private void dispose(final Monolith monolith) {
        if (monolith.label != null && monolith.label.isValid()) {
            monolith.label.remove();
        }
        monolith.label = null;
        for (final LivingEntity monster : monolith.monsters) {
            if (monster.isValid()) {
                monster.remove();
            }
        }
        monolith.monsters.clear();
        hideBar(monolith);
    }

    void clear(final MapInstance instance) {
        final Monolith monolith = byInstance.remove(instance.id());
        if (monolith != null) {
            dispose(monolith);
        }
    }

    void clearAll() {
        for (final Monolith monolith : byInstance.values()) {
            dispose(monolith);
        }
        byInstance.clear();
    }

    String summary(final MapInstance instance) {
        final Monolith monolith = byInstance.get(instance.id());
        if (monolith == null) {
            return null;
        }
        return "軍團" + switch (monolith.phase) {
            case IDLE -> "（石碑還沒敲）";
            case FROZEN -> "（凍著）";
            case ACTIVE -> "（醒來，剩 " + monolith.monsters.size() + " 隻）";
            default -> "（結束）";
        };
    }

    boolean isLegionMonster(final Entity entity) {
        return entity.getPersistentDataContainer().has(monsterKey);
    }

    // ---- 時沉領域（2026-11-04 照站長「軍團也要有 Boss」）----

    CurrencyType emblem() {
        return plugin.currencies() == null ? null : plugin.currencies().repository().get(settings().emblem());
    }

    /** 這個實例是不是時沉領域（路上不刷怪）。 */
    static boolean isDomain(final MapInstance instance) {
        return instance.special() != null && DOMAIN_KIND.equals(instance.special().kind());
    }

    /** 開一場時沉領域：用掉徽記、開一個只有入口與 Boss 房的實例。成功回傳 null，不然回傳原因。 */
    String openDomain(final Player player) {
        final MapEventConfig.LegionSettings settings = settings();
        final MapConfig.EventBoss boss = service.config().eventBoss(DOMAIN_KIND);
        if (boss == null) {
            return "maps.yml 的 event_bosses 沒有 legion";
        }
        if (!plugin.players().get(player).hasCharacter()) {
            return "先建立角色（/char）";
        }
        if (service.world() == null) {
            return "地圖世界沒有載入（看主控台）";
        }
        final CurrencyType emblem = emblem();
        if (emblem == null) {
            return "currencies.yml 沒有 " + settings.emblem();
        }
        final int have = service.countCurrency(player, emblem);
        if (have < settings.domainCost()) {
            return settings.domainName() + "要 " + settings.domainCost() + " 個" + emblem.name() + "（你有 " + have + " 個；軍團的將領、軍曹會掉）";
        }
        final List<MapConfig.MapDef> pool = new ArrayList<>();
        for (final MapConfig.MapDef map : service.config().maps()) {
            if (!map.isFinal() && !map.monsters().isEmpty()) {
                pool.add(map);
            }
        }
        if (pool.isEmpty()) {
            return "maps.yml 沒有可以當場地的地圖";
        }
        final MapConfig.MapDef from = pool.get(service.random().nextInt(pool.size()));
        final MapConfig.MapDef arena = new MapConfig.MapDef(DOMAIN_KIND, settings.domainName(), null, 0, from.level(), from.theme(), 0, 0, List.of(), false,
                from.monsters(), boss.boss(), "時間在這裡停了很久，久到連灰都不會落下。", false, MapLayout.Style.OPEN);
        final MapStats stats = service.eventBossStats(boss, player, settings.domainLevelBonus()); // 2026-11-04：災厄級固定等級
        final MapService.Special special = new MapService.Special(DOMAIN_KIND, 0xB8C4FF, (instance, players) -> {
            for (final Player each : players) {
                if (plugin.events() != null) {
                    plugin.events().onMapEvent(each, instance.level()); // 每月挑戰：算一個地圖事件
                }
            }
            plugin.getSLF4JLogger().info("地圖 #{}：{}的 Boss 倒下", instance.id(), settings.domainName());
        });
        final MapInstance instance = service.openSpecialInstance(player, arena, stats, MapService.UBER_ROOMS, special);
        if (instance == null) {
            final String problem = service.specialProblem();
            return problem == null ? "地圖世界現在開不了新的實例，等一下再試" : problem;
        }
        service.takeCurrency(player, emblem, settings.domainCost(), tw.exilecore.economy.EconomySources.MAP_DEVICE);
        final UUID ownerId = player.getUniqueId();
        instance.paidRefund(() -> service.refundLater(ownerId, emblem, settings.domainCost())); // 蓋圖失敗、重開伺服器時還沒開打就退徽記
        player.sendMessage(plugin.text().prefixed("<#b8c4ff>" + ItemRenderer.escape(settings.domainName()) + "<gray>開好了（T" + stats.tier() + "・怪物等級 "
                + stats.monsterLevel() + "）：下一間就是 " + ItemRenderer.escape(boss.boss().name()) + "<dark_gray>（傳送門在地圖裝置後面）"));
        plugin.getSLF4JLogger().info("{} 開了{}（T{}，Lv.{}）", player.getName(), settings.domainName(), stats.tier(), stats.monsterLevel());
        return null;
    }
}
