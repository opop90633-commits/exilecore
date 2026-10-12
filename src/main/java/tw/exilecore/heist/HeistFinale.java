package tw.exilecore.heist;

import net.kyori.adventure.text.Component;
import net.kyori.adventure.title.Title;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.Particle;
import org.bukkit.Sound;
import org.bukkit.block.Block;
import org.bukkit.entity.Display;
import org.bukkit.entity.Player;
import org.bukkit.entity.TextDisplay;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.block.Action;
import org.bukkit.event.player.PlayerInteractEvent;
import org.bukkit.inventory.EquipmentSlot;
import tw.exilecore.ExileCore;
import tw.exilecore.currency.CurrencyType;
import tw.exilecore.economy.EconomySources;
import tw.exilecore.item.ItemData;
import tw.exilecore.item.ItemRenderer;
import tw.exilecore.item.UniqueItem;
import tw.exilecore.map.MapInstance;
import tw.exilecore.map.MapLayout;
import tw.exilecore.map.MapRewards;
import tw.exilecore.map.MapService;
import tw.exilecore.map.MapStats;
import tw.exilecore.monster.DropTable;
import tw.exilecore.monster.MonsterData;
import tw.exilecore.monster.MonsterRarity;
import tw.exilecore.skill.SkillDefinition;

import java.time.Duration;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/**
 * 劫盜終局「金庫之主」（2026-10）：用大劫盜大金庫掉的鑰印開（heist.yml 的 vault.finale_key_chance）。
 * 打倒之後 Boss 房中間放一排寶箱（finale.choices，預設 5 個），上面的全息文字寫著裡面是哪一件（2026-10：每個有份的人各自一組、
 * 各自擲數值，全息文字只有本人看得到）——
 * 從劫盜的隱匿傳奇裡隨機挑（有機率多一個 Lv.21 未切割技能寶石）。每個人只能開一個，開了之後其他的對他就封鎖了（全息文字也看不到了）。
 */
final class HeistFinale implements Listener {

    public static final String KEY = "heist_finale_key";
    private static final int COLOR = 0x8B0000;

    /**
     * 一個寶箱裡是什麼：傳奇（unique）或 Lv.21 未切割技能寶石（skill = uncut_skill_21）。
     * seed：2026-10 傳奇的數值照這個擲（全息文字上看到的就是拿到的那一件；每個人拿到的數值一樣、物品 UUID 各自新的）。
     */
    private record Choice(String unique, String skill, String label, long seed) {
    }

    private static final class Run {
        final MapInstance instance;
        final int level;
        final Map<Long, Integer> chests = new HashMap<>();
        /** 2026-10：每個人自己的一組獎品（挑哪幾件、數值都各自擲）；寶箱位置共用，全息文字只給本人看。 */
        final Map<UUID, List<Choice>> choices = new HashMap<>();
        final Map<UUID, List<TextDisplay>> labels = new HashMap<>();
        final Set<UUID> taken = new HashSet<>();
        final List<Block> chestBlocks = new ArrayList<>();
        boolean placed;
        /** 打 Boss 打了幾秒（有人在裡面、Boss 還活著）。 */
        int fightSeconds;
        /** 場上的警報機關（方塊 key → 全息文字）與拆除期限（fightSeconds）。 */
        final Map<Long, TextDisplay> alarms = new HashMap<>();
        final Map<Long, Block> alarmBlocks = new HashMap<>();
        int alarmDeadline = -1;
        /** 2026-10：金庫之主改成 2 門：換門的時候警報機關、計時從頭來。 */
        int attempt;

        Run(final MapInstance instance, final int level) {
            this.instance = instance;
            this.level = level;
        }
    }

    private final ExileCore plugin;
    private final HeistService heists;
    private final Map<Integer, Run> runs = new HashMap<>();

    HeistFinale(final ExileCore plugin, final HeistService heists) {
        this.plugin = plugin;
        this.heists = heists;
    }

    // ---- 開始 ----

    String start(final Player player) {
        final HeistConfig config = heists.config();
        final HeistConfig.Site site = config.finale();
        if (site == null) {
            return "heist.yml 沒有寫 finale（金庫之主）";
        }
        if (!plugin.players().get(player).hasCharacter()) {
            return "先建立角色（/char）";
        }
        final MapService maps = plugin.maps();
        if (maps == null || maps.world() == null) {
            return "地圖模組沒有啟用，開不了";
        }
        final CurrencyType key = heists.currency(KEY);
        if (key == null) {
            return "currencies.yml 沒有 " + KEY;
        }
        if (maps.countCurrency(player, key) < 1) {
            return "需要一個" + key.name() + "（大劫盜的大金庫有機率掉）";
        }
        final int level = heists.levelFor(player) + config.finaleLevelBonus();
        final HeistConfig.Settings settings = config.settings();
        // 階級＝開的人打過的最高階：Boss 生命跟極境一樣照階級再乘（maps.yml 的 final.tier_life）
        final int tier = Math.max(1, maps.bestTier(player));
        final MapStats stats = new MapStats(tier, level, settings.quantity(), settings.rarity(), 0, 0, 0, List.of(), List.of(), List.of());
        final MapService.Special special = new MapService.Special("heist_finale", COLOR, this::onComplete);
        final MapInstance instance = maps.openSpecialInstance(player, site.map(level), stats, MapService.UBER_ROOMS, special);
        if (instance == null) {
            final String problem = maps.specialProblem();
            return problem == null ? "地圖世界現在開不了新的實例，等一下再試" : problem;
        }
        maps.takeCurrency(player, key, 1, EconomySources.HEIST);
        runs.put(instance.id(), new Run(instance, level));
        player.sendMessage(plugin.text().prefixed("<#8b0000>盜賊首領：<gray>你真的要去？金庫之主從來沒輸過。"
                + "打倒他，他的收藏讓你挑一件。<dark_gray>（傳送門在地圖裝置後面）"));
        plugin.getSLF4JLogger().info("{} 開了劫盜終局（Lv.{}）", player.getName(), level);
        return null;
    }

    // ---- 打倒之後 ----

    private void onComplete(final MapInstance instance, final List<Player> players) {
        final Run run = runs.get(instance.id());
        if (run == null || run.placed) {
            return;
        }
        run.placed = true;
        int count = 0;
        for (final Player player : players) {
            final List<Choice> mine = rollChoices(run);
            run.choices.put(player.getUniqueId(), mine);
            count = Math.max(count, mine.size());
        }
        if (count == 0) {
            for (final Player player : players) {
                player.sendMessage(plugin.text().prefixed("<red>金庫之主的收藏是空的（uniques.yml、skills.yml 沒有劫盜專屬的東西）"));
            }
            return;
        }
        placeChests(run, count);
        for (final Player player : players) {
            showLabels(run, player);
            player.showTitle(Title.title(plugin.text().parse("<#8b0000>金庫之主倒下了"),
                    plugin.text().parse("<gray>他的收藏就在中間：<white>只能挑一個寶箱</white>"),
                    Title.Times.times(Duration.ofMillis(100), Duration.ofMillis(3000), Duration.ofMillis(600))));
            player.playSound(player.getLocation(), Sound.UI_TOAST_CHALLENGE_COMPLETE, 1.0f, 0.8f);
        }
    }

    /** 從劫盜的隱匿傳奇裡不重複地挑 finale.choices 件（一個人一組，各自擲）。 */
    private List<Choice> rollChoices(final Run run) {
        final List<Choice> pool = new ArrayList<>();
        if (plugin.items() != null) {
            for (final UniqueItem unique : plugin.items().repository().uniquesFor(HeistService.UNIQUE_SOURCE, Math.max(run.level, 100))) {
                pool.add(new Choice(unique.id(), null, "<#af6025>" + ItemRenderer.escape(unique.name()), plugin.random().nextLong()));
            }
        }
        Collections.shuffle(pool, new java.util.Random(plugin.random().nextLong()));
        final List<Choice> picked = new ArrayList<>(pool.subList(0, Math.min(pool.size(), heists.config().finaleChoices())));
        // 2026-10：收藏裡有機率多一個 Lv.21 未切割技能寶石的寶箱（以前是異質技能寶石）
        if (plugin.random().nextDouble() < heists.config().settings().vault().finaleGemChance() && heists.currency(HeistService.UNCUT_21) != null) {
            final Choice gem = new Choice(null, HeistService.UNCUT_21, "<#ff7a6a>未切割技能寶石 Lv.21", 0);
            if (picked.size() >= heists.config().finaleChoices() && !picked.isEmpty()) {
                picked.set(picked.size() - 1, gem);
            } else {
                picked.add(gem);
            }
        }
        return picked;
    }

    /** Boss 房中間排一排寶箱（半圓）。全息文字每個人各自的（{@link #showLabels}）。 */
    private void placeChests(final Run run, final int count) {
        final MapInstance instance = run.instance;
        final MapLayout layout = instance.layout();
        MapLayout.Room boss = null;
        for (final MapLayout.Room room : layout.rooms()) {
            if (room.kind() == MapLayout.Kind.BOSS) {
                boss = room;
            }
        }
        final int cx = boss == null ? 0 : boss.cx();
        final int cz = boss == null ? 0 : boss.cz();
        for (int i = 0; i < count; i++) {
            final double angle = Math.PI * (i + 0.5) / count; // 半圓
            int x = cx + (int) Math.round(Math.cos(angle) * 5);
            int z = cz + (int) Math.round(Math.sin(angle) * 5);
            if (!layout.isFloor(x, z)) {
                x = cx + (i - count / 2) * 2;
                z = cz;
            }
            final Block block = instance.world().getBlockAt(instance.originX() + x, MapLayout.FLOOR_Y + 1, instance.originZ() + z);
            block.setType(Material.CHEST, false);
            run.chests.put(key(block), i);
            run.chestBlocks.add(block);
            instance.world().spawnParticle(Particle.END_ROD, block.getLocation().add(0.5, 1.15, 0.5), 20, 0.3, 0.4, 0.3, 0.02);
        }
    }

    /** 這個人的全息文字（只有他看得到）：每個寶箱上寫他自己那一件的完整資訊（名稱、詞綴與數值；就是他會拿到的）。 */
    private void showLabels(final Run run, final Player player) {
        final List<Choice> mine = run.choices.get(player.getUniqueId());
        if (mine == null) {
            return;
        }
        final List<TextDisplay> shown = new ArrayList<>();
        for (int i = 0; i < Math.min(mine.size(), run.chestBlocks.size()); i++) {
            final Location above = run.chestBlocks.get(i).getLocation().add(0.5, 1.15, 0.5);
            final Component text = describe(run, mine.get(i));
            final TextDisplay label = run.instance.world().spawn(above, TextDisplay.class, display -> {
                display.setVisibleByDefault(false);
                display.text(text);
                display.setBillboard(Display.Billboard.CENTER);
                display.setPersistent(false);
                display.setShadowed(true);
                display.setSeeThrough(false);
                display.setLineWidth(260);
                display.setBackgroundColor(org.bukkit.Color.fromARGB(200, 16, 6, 24));
                display.setTransformation(new org.bukkit.util.Transformation(new org.joml.Vector3f(), new org.joml.Quaternionf(),
                        new org.joml.Vector3f(0.5f, 0.5f, 0.5f), new org.joml.Quaternionf())); // 縮小：五個箱子的字才不會疊在一起
            });
            player.showEntity(plugin, label);
            shown.add(label);
        }
        run.labels.put(player.getUniqueId(), shown);
    }

    /** 寶箱上方的全息文字：物品的名稱與說明（跟拿到時一樣），最後一行寫怎麼拿。 */
    private Component describe(final Run run, final Choice choice) {
        final Component footer = plugin.text().parse("<gray>右鍵：拿這一件<dark_gray>（只能挑一個）");
        org.bukkit.inventory.ItemStack stack = null;
        try {
            if (choice.unique() != null) {
                final ItemData item = rollUnique(run, choice);
                stack = item == null ? null : plugin.items().renderer().create(item);
            } else if (HeistService.UNCUT_21.equals(choice.skill()) && plugin.skills() != null) {
                stack = plugin.skills().gems().create(tw.exilecore.skill.GemData.uncutSkill(tw.exilecore.character.SkillLoadout.GEM_CAP), 1);
            }
        } catch (final RuntimeException e) {
            stack = null;
        }
        final org.bukkit.inventory.meta.ItemMeta meta = stack == null ? null : stack.getItemMeta();
        if (meta == null) {
            return plugin.text().parse(choice.label()).append(Component.newline()).append(footer);
        }
        Component text = meta.hasDisplayName() ? meta.displayName() : meta.hasItemName() ? meta.itemName() : plugin.text().parse(choice.label());
        final List<Component> lore = meta.lore();
        if (lore != null) {
            for (final Component line : lore) {
                text = text.append(Component.newline()).append(line);
            }
        }
        return text.append(Component.newline()).append(Component.newline()).append(footer);
    }

    /** 這個寶箱的傳奇（照 seed 擲：看到的跟拿到的一樣）。 */
    private ItemData rollUnique(final Run run, final Choice choice) {
        final UniqueItem unique = plugin.items() == null ? null : plugin.items().repository().unique(choice.unique());
        if (unique == null) {
            return null;
        }
        final ItemData item = plugin.items().generator().generateUnique(unique, Math.max(run.level, unique.dropLevel()), new java.util.Random(choice.seed()));
        item.setIdentified(true);
        return item;
    }

    private static long key(final Block block) {
        return ((long) block.getX() & 0x3FFFFFFL) << 38 | ((long) block.getZ() & 0x3FFFFFFL) << 12 | (block.getY() & 0xFFFL);
    }

    // ---- 警報機關（打 Boss 的時候） ----

    private void alarmTick(final Run run) {
        final MapInstance instance = run.instance;
        final HeistConfig.FinaleAlarm config = heists.config().finaleAlarm();
        if (config.every() <= 0 || config.count() <= 0 || instance.state() != MapInstance.State.OPEN || instance.bossKilled()
                || instance.boss() == null || !instance.boss().isValid()) {
            clearAlarms(run);
            return;
        }
        final List<Player> inside = new ArrayList<>();
        for (final Player player : instance.world().getPlayers()) {
            // 2026-10：旁觀（死掉後觀戰）、創造模式的人不算人數：機關數、增援群數、機關放在誰旁邊都只看在打的人
            if (instance.contains(player.getLocation()) && !player.isDead() && fighting(player)
                    && player.getLocation().distanceSquared(instance.boss().getLocation()) < 40 * 40) {
                inside.add(player);
            }
        }
        if (run.attempt != instance.attemptsUsed()) {
            run.attempt = instance.attemptsUsed(); // 新的一門：機關、計時重來
            clearAlarms(run);
            run.fightSeconds = 0;
            run.alarmDeadline = -1;
        }
        if (inside.isEmpty()) {
            return; // 沒人在打：不算時間
        }
        run.fightSeconds++;
        if (run.alarmDeadline >= 0 && run.fightSeconds >= run.alarmDeadline) {
            final int left = run.alarms.size();
            clearAlarms(run);
            if (left > 0) {
                alarm(run, inside, left);
            }
        }
        if (run.alarms.isEmpty() && run.fightSeconds % config.every() == 0) {
            placeAlarms(run, inside, config);
        }
    }

    private static boolean fighting(final Player player) {
        return player.getGameMode() == org.bukkit.GameMode.SURVIVAL || player.getGameMode() == org.bukkit.GameMode.ADVENTURE;
    }

    private void placeAlarms(final Run run, final List<Player> inside, final HeistConfig.FinaleAlarm config) {
        final MapInstance instance = run.instance;
        final MapLayout layout = instance.layout();
        final int want = config.countFor(inside.size()); // 人越多放越多
        for (int i = 0; i < want; i++) {
            final Player near = inside.get(plugin.random().nextInt(inside.size()));
            for (int attempt = 0; attempt < 16; attempt++) {
                final int x = near.getLocation().getBlockX() - instance.originX() + plugin.random().nextInt(17) - 8;
                final int z = near.getLocation().getBlockZ() - instance.originZ() + plugin.random().nextInt(17) - 8;
                if (!layout.isFloor(x, z)) {
                    continue;
                }
                final Block block = instance.world().getBlockAt(instance.originX() + x, MapLayout.FLOOR_Y + 1, instance.originZ() + z);
                if (!block.getType().isAir()) {
                    continue;
                }
                block.setType(Material.REDSTONE_LAMP, false);
                final TextDisplay label = instance.world().spawn(block.getLocation().add(0.5, 1.4, 0.5), TextDisplay.class, display -> {
                    display.text(plugin.text().parse("<red>⚠ 警報機關\n<yellow>右鍵：拆掉"));
                    display.setBillboard(Display.Billboard.CENTER);
                    display.setPersistent(false);
                });
                run.alarms.put(key(block), label);
                run.alarmBlocks.put(key(block), block);
                break;
            }
        }
        if (run.alarms.isEmpty()) {
            return;
        }
        run.alarmDeadline = run.fightSeconds + config.time();
        for (final Player player : inside) {
            player.showTitle(Title.title(plugin.text().parse("<red>⚠ 警報機關"),
                    plugin.text().parse("<gray>" + config.time() + " 秒內拆掉全部 " + run.alarms.size() + " 個（右鍵），不然增援會來"),
                    Title.Times.times(Duration.ofMillis(100), Duration.ofMillis(2000), Duration.ofMillis(400))));
            player.playSound(player.getLocation(), Sound.BLOCK_NOTE_BLOCK_BELL, 1.0f, 0.6f);
        }
    }

    /** 沒拆完：警報響起，叫 packs 群增援（每群稀有、魔法、普通照設定），散在打王的人周圍。 */
    private void alarm(final Run run, final List<Player> inside, final int left) {
        final HeistConfig.FinaleAlarm config = heists.config().finaleAlarm();
        int spawned = 0;
        final int packs = config.packsFor(inside.size()); // 人越多叫越多群
        for (int pack = 0; pack < packs; pack++) {
            final Player target = inside.get(plugin.random().nextInt(inside.size()));
            final Location center = floorNear(run.instance, target.getLocation(), 8, 14);
            spawned += spawnPack(run, center, target, MonsterRarity.RARE, config.rare());
            spawned += spawnPack(run, center, target, MonsterRarity.MAGIC, config.magic());
            spawned += spawnPack(run, center, target, MonsterRarity.NORMAL, config.normal());
            center.getWorld().spawnParticle(Particle.LARGE_SMOKE, center.clone().add(0, 1, 0), 30, 1.2, 0.6, 1.2, 0.02);
        }
        for (final Player player : inside) {
            player.showTitle(Title.title(plugin.text().parse("<red>警報響起！"),
                    plugin.text().parse("<gray>還有 " + left + " 個機關沒拆：" + packs + " 群守衛衝進來了"),
                    Title.Times.times(Duration.ofMillis(100), Duration.ofMillis(2000), Duration.ofMillis(400))));
            player.sendMessage(plugin.text().prefixed("<red>警報響起！<gray>還有 " + left + " 個機關沒拆，" + spawned + " 個守衛衝進來了"));
            player.playSound(player.getLocation(), Sound.EVENT_RAID_HORN, 1.0f, 1.0f);
        }
    }

    private int spawnPack(final Run run, final Location center, final Player target, final MonsterRarity rarity, final int count) {
        int spawned = 0;
        for (int i = 0; i < count; i++) {
            final Location at = floorNear(run.instance, center, 0, 3);
            final org.bukkit.entity.LivingEntity monster = plugin.maps().spawnExtra(run.instance, at, rarity);
            if (monster != null) {
                spawned++;
                if (monster instanceof org.bukkit.entity.Mob mob) {
                    mob.setTarget(target);
                }
            }
        }
        return spawned;
    }

    /** around 附近 min～max 格、版面上是地板的一個位置（找不到就是 around）。 */
    private Location floorNear(final MapInstance instance, final Location around, final int min, final int max) {
        final MapLayout layout = instance.layout();
        for (int attempt = 0; attempt < 20; attempt++) {
            final double angle = plugin.random().nextDouble() * Math.PI * 2;
            final double distance = min + plugin.random().nextDouble() * Math.max(0, max - min);
            final int x = (int) Math.floor(around.getX() + Math.cos(angle) * distance) - instance.originX();
            final int z = (int) Math.floor(around.getZ() + Math.sin(angle) * distance) - instance.originZ();
            if (layout.isFloor(x, z)) {
                return new Location(instance.world(), instance.originX() + x + 0.5, MapLayout.FLOOR_Y + 1, instance.originZ() + z + 0.5);
            }
        }
        return around.clone();
    }

    private void clearAlarms(final Run run) {
        for (final TextDisplay label : run.alarms.values()) {
            if (label.isValid()) {
                label.remove();
            }
        }
        for (final Block block : run.alarmBlocks.values()) {
            if (block.getType() == Material.REDSTONE_LAMP) {
                block.setType(Material.AIR, false);
            }
        }
        run.alarms.clear();
        run.alarmBlocks.clear();
        run.alarmDeadline = -1;
    }

    /** 右鍵警報機關：拆掉。 */
    private boolean disarm(final Player player, final Block block) {
        for (final Run run : runs.values()) {
            final TextDisplay label = run.alarms.remove(key(block));
            if (label == null) {
                continue;
            }
            run.alarmBlocks.remove(key(block));
            label.remove();
            block.setType(Material.AIR, false);
            block.getWorld().spawnParticle(Particle.SMOKE, block.getLocation().add(0.5, 0.5, 0.5), 15, 0.3, 0.3, 0.3, 0.02);
            player.playSound(player.getLocation(), Sound.BLOCK_IRON_TRAPDOOR_CLOSE, 1.0f, 1.4f);
            if (run.alarms.isEmpty()) {
                run.alarmDeadline = -1;
                for (final Player other : run.instance.world().getPlayers()) {
                    if (run.instance.contains(other.getLocation())) {
                        other.sendActionBar(plugin.text().parse("<green>警報機關全部拆掉了"));
                    }
                }
            } else {
                player.sendActionBar(plugin.text().parse("<yellow>拆掉一個，還有 " + run.alarms.size() + " 個"));
            }
            return true;
        }
        return false;
    }

    @EventHandler(priority = EventPriority.LOWEST)
    public void onInteract(final PlayerInteractEvent event) {
        if (!runs.isEmpty() && event.getAction() == Action.RIGHT_CLICK_BLOCK && event.getClickedBlock() != null
                && event.getClickedBlock().getType() == Material.REDSTONE_LAMP && event.getHand() == EquipmentSlot.HAND) {
            if (disarm(event.getPlayer(), event.getClickedBlock())) {
                event.setCancelled(true);
                if (plugin.skills() != null) {
                    plugin.skills().consumeRightClick(event.getPlayer());
                }
            }
            return;
        }
        if (runs.isEmpty() || event.getAction() != Action.RIGHT_CLICK_BLOCK || event.getClickedBlock() == null
                || event.getClickedBlock().getType() != Material.CHEST || event.getHand() != EquipmentSlot.HAND) {
            return;
        }
        final Block block = event.getClickedBlock();
        Run found = null;
        Integer index = null;
        for (final Run run : runs.values()) {
            index = run.chests.get(key(block));
            if (index != null) {
                found = run;
                break;
            }
        }
        if (found == null) {
            return;
        }
        event.setCancelled(true);
        event.setUseItemInHand(org.bukkit.event.Event.Result.DENY);
        final Player player = event.getPlayer();
        if (plugin.skills() != null) {
            plugin.skills().consumeRightClick(player);
        }
        final List<Choice> mine = found.choices.get(player.getUniqueId());
        if (mine == null || index >= mine.size()) {
            player.sendActionBar(plugin.text().parse("<red>這些寶箱沒有你的份（金庫之主倒下時你不在旁邊）"));
            player.playSound(player.getLocation(), Sound.BLOCK_CHEST_LOCKED, 1.0f, 1.0f);
            return;
        }
        if (!found.taken.add(player.getUniqueId())) {
            player.sendActionBar(plugin.text().parse("<red>你已經挑過了，其他的寶箱都封鎖了"));
            player.playSound(player.getLocation(), Sound.BLOCK_CHEST_LOCKED, 1.0f, 1.0f);
            return;
        }
        final Choice choice = mine.get(index);
        final DropTable.Drop drop = toDrop(found, choice);
        if (drop == null) {
            found.taken.remove(player.getUniqueId());
            player.sendActionBar(plugin.text().parse("<red>這個寶箱裡的東西已經不存在了（設定檔改過），挑別的"));
            return;
        }
        final MonsterData data = new MonsterData(found.level, MonsterRarity.UNIQUE, List.of(), "金庫之主的收藏");
        MapRewards.deliver(plugin, player, block.getLocation().add(0.5, 1, 0.5), data, new ArrayList<>(List.of(drop)), 0);
        // 對這個人：其他寶箱的全息文字收掉（看得到的只剩他挑的那一個）
        final List<TextDisplay> his = found.labels.getOrDefault(player.getUniqueId(), List.of());
        for (int i = 0; i < his.size(); i++) {
            if (i != index && his.get(i).isValid()) {
                his.get(i).remove();
            }
        }
        player.sendMessage(plugin.text().prefixed("<#8b0000>金庫之主的收藏：<gray>你拿走了 " + choice.label() + "<gray>，其他的寶箱封鎖了。"));
        player.playSound(player.getLocation(), Sound.BLOCK_CHEST_OPEN, 1.0f, 0.9f);
        player.playSound(player.getLocation(), Sound.BLOCK_IRON_DOOR_CLOSE, 0.8f, 0.6f);
        block.getWorld().spawnParticle(Particle.TOTEM_OF_UNDYING, block.getLocation().add(0.5, 1.2, 0.5), 30, 0.3, 0.5, 0.3, 0.2);
    }

    private DropTable.Drop toDrop(final Run run, final Choice choice) {
        if (choice.unique() != null) {
            final ItemData item = rollUnique(run, choice);
            return item == null ? null : DropTable.Drop.equipment(item);
        }
        if (HeistService.UNCUT_21.equals(choice.skill())) {
            final tw.exilecore.currency.CurrencyType type = heists.currency(HeistService.UNCUT_21);
            return type == null ? null : DropTable.Drop.currency(type);
        }
        return null; // 異質技能寶石（另類品質）2026-10 刪掉了
    }

    // ---- 收掉 ----

    /** 每秒：實例關了的收掉（全息文字）。 */
    void tick() {
        if (runs.isEmpty()) {
            return;
        }
        for (final Run run : new ArrayList<>(runs.values())) {
            final MapInstance instance = run.instance;
            if (plugin.maps() == null || instance.state() == MapInstance.State.CLOSING || !plugin.maps().instances().contains(instance)) {
                end(run);
                continue;
            }
            alarmTick(run);
            // 重新登入的人：自己的全息文字再顯示一次（只給本人看的設定跟著舊的連線不見了）
            for (final Map.Entry<UUID, List<TextDisplay>> entry : run.labels.entrySet()) {
                final Player player = plugin.getServer().getPlayer(entry.getKey());
                if (player == null) {
                    continue;
                }
                for (final TextDisplay label : entry.getValue()) {
                    if (label.isValid() && !player.canSee(label)) {
                        player.showEntity(plugin, label);
                    }
                }
            }
        }
    }

    private void end(final Run run) {
        clearAlarms(run);
        for (final List<TextDisplay> list : run.labels.values()) {
            for (final TextDisplay label : list) {
                if (label.isValid()) {
                    label.remove();
                }
            }
        }
        runs.values().removeIf(other -> other == run);
    }

    void clear() {
        for (final Run run : new ArrayList<>(runs.values())) {
            end(run);
        }
        runs.clear();
    }
}
