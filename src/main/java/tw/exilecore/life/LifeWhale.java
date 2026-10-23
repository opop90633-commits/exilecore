package tw.exilecore.life;

import net.kyori.adventure.bossbar.BossBar;
import net.kyori.adventure.title.Title;
import org.bukkit.GameMode;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.Particle;
import org.bukkit.Sound;
import org.bukkit.World;
import org.bukkit.attribute.Attribute;
import org.bukkit.attribute.AttributeInstance;
import org.bukkit.block.Block;
import org.bukkit.entity.Entity;
import org.bukkit.entity.EntityType;
import org.bukkit.entity.LivingEntity;
import org.bukkit.entity.Mob;
import org.bukkit.entity.Player;
import org.bukkit.event.entity.CreatureSpawnEvent;
import org.bukkit.inventory.ItemStack;
import org.bukkit.persistence.PersistentDataType;
import tw.exilecore.ExileCore;
import tw.exilecore.item.ItemData;
import tw.exilecore.item.ItemRenderer;
import tw.exilecore.item.UniqueItem;
import tw.exilecore.map.MapConfig;
import tw.exilecore.map.MapStats;
import tw.exilecore.monster.DropTable;
import tw.exilecore.monster.MonsterData;
import tw.exilecore.monster.MonsterListener;
import tw.exilecore.monster.MonsterRarity;
import tw.exilecore.skill.BossHazard;
import tw.exilecore.skill.BossSkillUse;
import tw.exilecore.skill.BossSkills;
import tw.exilecore.stats.ModType;
import tw.exilecore.stats.Modifier;
import tw.exilecore.stats.StatKeys;
import tw.exilecore.stats.StatSheet;

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
 * 深淵巨獸「淵鯨」（星骸之海；2026-10）：深淵號角在自己的筏上右鍵吹，淵鯨在筏的東邊浮上來。
 * 強度比照巔峰（life.yml 的 whale.like 那個巔峰的等級、怪物屬性、玩家受到的詞綴）；每秒傷害上限照終局（MapService.capBossDamage），
 * 掉落不吃物品數量、稀有度（MapService.flatBossLoot）。
 */
final class LifeWhale {

    static final String HORN_ITEM = "abyss_horn";
    static final int RADIUS = 15;
    /**
     * 場地的地面（站的高度）比海平面高幾格（2026-10-20：2 → 1：場地的頂面跟海面齊平，掉進海裡游到邊上就走得上來；
     * 以前頂面高出水面一格，水裡跳不上去，只能等死或回筏）。
     */
    static final int ARENA_TOP = 1;
    private static final String TAG = "whale:";

    private final class Fight {
        final UUID owner;
        /** 吹號角的那塊筏；一起打的人（2026-10-16：筏上的人一起進場，之後走進場地的也算；打倒了每個人各拿一份）。 */
        int plot = -1;
        final Set<UUID> crew = new HashSet<>();
        final int cx;
        final int cz;
        final long startMillis = System.currentTimeMillis();
        final BossBar bar = BossBar.bossBar(net.kyori.adventure.text.Component.empty(), 1f, BossBar.Color.BLUE, BossBar.Overlay.NOTCHED_10);
        final Set<UUID> cores = new HashSet<>();
        /** 招式叫出來的小怪（結束時全部收掉，跑去筏上的也一樣）。 */
        final Set<UUID> adds = new HashSet<>();
        LivingEntity boss;
        int phase;
        /** 2026-10-22：第幾門（從 1 開始；照巔峰、極境：整隊算一門，有人倒下先觀戰，全隊倒下才換下一門）。 */
        int door = 1;
        /** 這一門倒下、在旁邊觀戰的人（倒下的地方、原本的模式）。 */
        final Map<UUID, Down> down = new HashMap<>();
        boolean enraged;
        long shieldUntil;
        LifeConfig.WhalePhase shield;
        int level;
        boolean done;

        Fight(final UUID owner, final int cx, final int cz) {
            this.owner = owner;
            this.cx = cx;
            this.cz = cz;
        }

        boolean contains(final double x, final double z) {
            return Math.abs(x - cx) <= RADIUS + 1.5 && Math.abs(z - cz) <= RADIUS + 1.5;
        }

        Location center() {
            return new Location(world(), cx + 0.5, service.config().seaLevel() + ARENA_TOP, cz + 0.5);
        }
    }

    /** 倒下觀戰的人：重生在哪、原本的遊戲模式。 */
    private record Down(Location anchor, GameMode previous) {
    }

    private final ExileCore plugin;
    private final LifeService service;
    private final RandomGenerator random;
    private final Map<UUID, Fight> fights = new HashMap<>();

    LifeWhale(final ExileCore plugin, final LifeService service) {
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

    boolean inArena(final Location location) {
        for (final Fight fight : fights.values()) {
            if (fight.contains(location.getX(), location.getZ())) {
                return true;
            }
        }
        return false;
    }

    boolean fighting(final Player player) {
        return fightOf(player.getUniqueId()) != null;
    }

    /** 這個人在打的那一場（吹號角的、一起打的）。 */
    private Fight fightOf(final UUID id) {
        final Fight own = fights.get(id);
        if (own != null) {
            return own;
        }
        for (final Fight fight : fights.values()) {
            if (fight.crew.contains(id)) {
                return fight;
            }
        }
        return null;
    }

    /** 這塊筏的淵鯨在不在。 */
    boolean busy(final int plot) {
        for (final Fight fight : fights.values()) {
            if (fight.plot == plot) {
                return true;
            }
        }
        return false;
    }

    /** 這一場的人（吹號角的＋一起打的，在線上）。 */
    private List<Player> audience(final Fight fight) {
        final List<Player> list = new ArrayList<>();
        final Player owner = plugin.getServer().getPlayer(fight.owner);
        if (owner != null) {
            list.add(owner);
        }
        for (final UUID id : fight.crew) {
            final Player member = plugin.getServer().getPlayer(id);
            if (member != null && !list.contains(member)) {
                list.add(member);
            }
        }
        return list;
    }

    /** 在場地上、沒倒下的人（觀戰的不算）。 */
    private List<Player> onArena(final Fight fight) {
        final List<Player> list = new ArrayList<>();
        for (final Player player : audience(fight)) {
            if (!player.isDead() && !fight.down.containsKey(player.getUniqueId()) && player.getGameMode() != GameMode.SPECTATOR
                    && world().equals(player.getWorld()) && fight.contains(player.getLocation().getX(), player.getLocation().getZ())) {
                list.add(player);
            }
        }
        return list;
    }

    /** 這是不是淵鯨（MapService：每秒傷害上限、掉落不吃加成）。 */
    boolean isWhale(final Entity entity) {
        final String tag = entity.getPersistentDataContainer().get(service.driftKey(), PersistentDataType.STRING);
        if (tag == null || !tag.startsWith(TAG)) {
            return false;
        }
        for (final Fight fight : fights.values()) {
            if (fight.boss != null && fight.boss.getUniqueId().equals(entity.getUniqueId())) {
                return true;
            }
        }
        return false;
    }

    /** 比照的巔峰（maps.yml）的地圖數值；沒有地圖模組 null。 */
    private MapStats like() {
        final LifeConfig.Whale def = config().whale();
        if (def == null || plugin.maps() == null) {
            return null;
        }
        final MapConfig maps = plugin.maps().config();
        final MapConfig.Pinnacle pinnacle = maps.pinnacle(def.like());
        return pinnacle == null ? null : maps.pinnacleStats(def.like(), pinnacle);
    }

    /** 怪物的屬性表（MonsterService 的 augmenter）：淵鯨吃巔峰的怪物、Boss 詞綴，階段與狂暴的傷害。 */
    void augment(final LivingEntity entity, final StatSheet sheet) {
        Fight fight = null;
        for (final Fight candidate : fights.values()) {
            if (candidate.boss != null && candidate.boss.getUniqueId().equals(entity.getUniqueId())) {
                fight = candidate;
            }
        }
        if (fight == null) {
            return;
        }
        final MapStats stats = like();
        if (stats != null) {
            sheet.addAll(stats.monsterModifiers());
            sheet.addAll(stats.bossModifiers());
        }
        final LifeConfig.Whale def = config().whale();
        if (def == null) {
            return;
        }
        double more = 0;
        for (int i = 0; i < fight.phase && i < def.phases().size(); i++) {
            more = def.phases().get(i).damage(); // 每個階段寫的是「這個階段起」的
        }
        if (more > 0) {
            sheet.add(new Modifier(StatKeys.DAMAGE, ModType.MORE, more, Set.of(), def.name()));
        }
        if (fight.enraged && def.enrageDamage() > 0) {
            sheet.add(new Modifier(StatKeys.DAMAGE, ModType.MORE, def.enrageDamage(), Set.of(), def.name() + "（狂暴）"));
        }
    }

    /** 在淵鯨場地上的玩家：吃巔峰的玩家詞綴（看場合的增益來源）。 */
    List<Modifier> playerModifiers(final Player player) {
        if (fights.isEmpty() || !inArena(player.getLocation()) || world() == null || !world().equals(player.getWorld())) {
            return List.of();
        }
        final MapStats stats = like();
        return stats == null ? List.of() : stats.playerModifiers();
    }

    // ---- 號角 ----

    ItemStack hornItem(final int amount) {
        final LifeConfig.Whale def = config().whale();
        final ItemStack stack = new ItemStack(Material.GOAT_HORN, Math.max(1, amount));
        stack.editMeta(meta -> {
            meta.displayName(ItemRenderer.line("<#5fd3ff>" + ItemRenderer.escape(def == null ? "深淵號角" : def.hornName())));
            meta.lore(List.of(ItemRenderer.line("<gray>在" + ItemRenderer.escape(config().name()) + "自己的筏上右鍵吹響："),
                    ItemRenderer.line("<gray>" + ItemRenderer.escape(def == null ? "淵鯨" : def.name()) + "會從筏的東邊浮上來"),
                    ItemRenderer.line("<red>巔峰級的 Boss：共 " + (def == null ? 2 : def.attempts()) + " 門，有人倒下先在旁邊觀戰，"),
                    ItemRenderer.line("<red>全隊倒下才進下一門（" + (def == null ? "淵鯨" : def.name()) + "回滿）；門用完就失敗（號角吹了就沒了）"),
                    ItemRenderer.line("<dark_gray>可以交易")));
            meta.getPersistentDataContainer().set(service.itemKey(), PersistentDataType.STRING, HORN_ITEM);
        });
        return stack;
    }

    /** 做一支號角（海圖桌）；回傳問題，成功 null。 */
    String craftHorn(final Player player) {
        final LifeConfig.Whale def = config().whale();
        if (def == null) {
            return "沒有開放";
        }
        final LifeService.Profile profile = service.profile(player);
        if (profile == null) {
            return "帳號資料載入中，請稍等";
        }
        if (config().levelOf(profile.xp) < def.hornLevel()) {
            return "要生活等級 " + def.hornLevel();
        }
        for (final Map.Entry<String, Integer> cost : def.hornCost().entrySet()) {
            final int have = profile.materials.getOrDefault(cost.getKey(), 0);
            if (have < cost.getValue()) {
                return "材料不夠：" + service.materialName(cost.getKey()) + " 要 " + cost.getValue() + "，你有 " + have;
            }
        }
        for (final Map.Entry<String, Integer> cost : def.hornCost().entrySet()) {
            profile.materials.merge(cost.getKey(), -cost.getValue(), Integer::sum);
            if (profile.materials.getOrDefault(cost.getKey(), 0) <= 0) {
                profile.materials.remove(cost.getKey());
            }
        }
        profile.dirty = true;
        service.giveItem(player, hornItem(1));
        service.flush(player);
        player.playSound(player.getLocation(), Sound.ITEM_GOAT_HORN_SOUND_0, 0.6f, 0.6f);
        return null;
    }

    /** 右鍵吹號角（LifeModule）；回傳要不要取消事件。 */
    boolean blow(final Player player, final ItemStack horn) {
        final LifeConfig.Whale def = config().whale();
        if (def == null) {
            return true;
        }
        final String problem = summon(player);
        if (problem != null) {
            tw.exilecore.gui.ActionBar.send(player, plugin.text().parse("<red>" + ItemRenderer.escape(problem)));
            return true;
        }
        final ItemStack held = player.getInventory().getItemInMainHand();
        if (HORN_ITEM.equals(service.itemId(held))) {
            held.setAmount(held.getAmount() - 1);
        } else {
            horn.setAmount(horn.getAmount() - 1);
        }
        return true;
    }

    private String summon(final Player player) {
        final LifeConfig.Whale def = config().whale();
        final LifeService.Profile profile = service.profile(player);
        final int plot = service.activePlot(player);
        if (profile == null || plot < 0) {
            return "帳號資料載入中，請稍等";
        }
        if (world() == null || !world().equals(player.getWorld()) || service.plotAt(player.getLocation().getX(), player.getLocation().getZ()) != plot
                || !service.lifeMode(player.getLocation())) {
            return "要在筏上吹";
        }
        if (!service.canBuild(player, profile, plot)) {
            return "只有筏主、共用成員能在這塊筏上吹";
        }
        if (fightOf(player.getUniqueId()) != null || busy(plot)) {
            return def.name() + "已經在了";
        }
        final int[] center = service.plotCenter(plot);
        // 2026-10-17：先確認別人的船、島不在，才收掉自己的（以前先收掉自己的島，召喚失敗也回不來）
        if (service.islands().busyByOthers(plot, player.getUniqueId())) {
            return "這塊筏的船還在外面（等島漂走、或開船的人回來）";
        }
        service.islands().dismiss(player); // 自己開的島、航行收掉（同一個位置）
        if (service.islands().busy(plot)) {
            return "這塊筏的船還在外面（等島漂走、或開船的人回來）";
        }
        final Fight fight = new Fight(player.getUniqueId(), center[0] + LifeIslands.OFFSET, center[1]);
        fight.plot = plot;
        for (final Player member : service.crewOn(plot)) {
            if (!member.equals(player) && fightOf(member.getUniqueId()) == null) {
                fight.crew.add(member.getUniqueId());
            }
        }
        LifeService.holdChunks(plugin, world(), fight.cx, fight.cz, RADIUS, true); // 打的時候場地的區塊不卸載
        buildArena(fight);
        final MapStats stats = like();
        fight.level = stats != null ? stats.monsterLevel() : Math.max(90, plugin.players().get(player).level() + 5);
        // 貼著場地（招式從牠的位置放：飄太高的話地上的圈、光束打不到人）
        final LivingEntity boss = spawn(def.type(), new Location(world(), fight.cx + 0.5, config().seaLevel() + ARENA_TOP, fight.cz + 0.5));
        if (boss == null) {
            clearArena(fight);
            LifeService.holdChunks(plugin, world(), fight.cx, fight.cz, RADIUS, false);
            return def.name() + "生不出來（type 寫錯？）";
        }
        final tw.exilecore.monster.MonsterConfig.TypeSettings type = plugin.monsters().config().type(def.type());
        final double tierLife = plugin.maps() == null ? 1 : plugin.maps().config().finalLifeMultiplier(plugin.maps().config().tiers().max());
        try {
            boss.getPersistentDataContainer().set(service.driftKey(), PersistentDataType.STRING, TAG + player.getUniqueId());
            fight.boss = boss;
            fights.put(player.getUniqueId(), fight); // 先登記：decorateBoss 算屬性表時 augment 認得
            plugin.monsters().fixPlayers(boss, 1 + fight.crew.size()); // 一起打的人多，血量照隊伍的規則加
            plugin.monsters().decorateBoss(boss, fight.level, def.name(), def.mods(), def.life() * tierLife / Math.max(0.1, type.lifeMultiplier()),
                    def.damage() / Math.max(0.1, type.damageMultiplier()), def.scale());
        } catch (final RuntimeException e) {
            fights.remove(player.getUniqueId());
            boss.remove();
            clearArena(fight);
            LifeService.holdChunks(plugin, world(), fight.cx, fight.cz, RADIUS, false);
            plugin.getSLF4JLogger().error("{}：等級化失敗", def.name(), e);
            return def.name() + "等級化失敗（看主控台）";
        }
        boss.setGravity(false);
        boss.setPersistent(false);
        boss.setRemoveWhenFarAway(false);
        final AttributeInstance follow = boss.getAttribute(Attribute.FOLLOW_RANGE);
        if (follow != null && follow.getBaseValue() < 48) {
            follow.setBaseValue(48);
        }
        final AttributeInstance knockback = boss.getAttribute(Attribute.KNOCKBACK_RESISTANCE);
        if (knockback != null) {
            knockback.setBaseValue(1.0);
        }
        attachSkills(fight);
        int slot = 0;
        for (final Player member : audience(fight)) {
            plugin.getServer().getScheduler().runTaskLater(plugin, () -> plugin.players().markDirty(member), 10L); // 巔峰的玩家詞綴
            member.teleportAsync(new Location(world(), fight.cx - RADIUS + 3.5, config().seaLevel() + ARENA_TOP + 0.1, fight.cz + 0.5 + (slot++ % 5 - 2), -90, 0));
            member.showBossBar(fight.bar);
            member.showTitle(Title.title(plugin.text().parse("<#5fd3ff>" + ItemRenderer.escape(def.name())), plugin.text().parse("<gray>深淵巨獸"),
                    Title.Times.times(Duration.ofMillis(300), Duration.ofMillis(2400), Duration.ofMillis(600))));
            if (!def.intro().isBlank()) {
                member.sendMessage(plugin.text().parse("<#5fd3ff>" + ItemRenderer.escape(def.intro())));
            }
        }
        world().playSound(fight.center(), Sound.ENTITY_ELDER_GUARDIAN_CURSE, 1.2f, 0.5f);
        world().spawnParticle(Particle.SPLASH, fight.center().add(0, 4, 0), 300, 6, 2, 6, 0.4);
        plugin.getSLF4JLogger().info("[Boss 紀錄] {}：{} Lv.{} 出現（{}）", config().name(), def.name(), fight.level, player.getName());
        return null;
    }

    private void attachSkills(final Fight fight) {
        if (plugin.skills() == null) {
            return;
        }
        final LifeConfig.Whale def = config().whale();
        final List<BossSkillUse> uses = new ArrayList<>();
        for (final BossSkillUse use : def.skills()) {
            if (plugin.skills().config().bossSkills().skill(use.id()) != null) {
                uses.add(use);
            } else {
                plugin.getSLF4JLogger().warn("{}：boss_skills.yml 沒有招式 {}", def.name(), use.id());
            }
        }
        final List<BossHazard> hazards = new ArrayList<>();
        for (final String[] hazard : def.hazards()) {
            if (plugin.skills().config().bossSkills().skill(hazard[0]) != null) {
                hazards.add(new BossHazard(hazard[0], Double.parseDouble(hazard[1]), Integer.parseInt(hazard[2])));
            }
        }
        plugin.skills().bossSkills().attach(fight.boss, uses, new BossSkills.Host() {
            @Override
            public String name() {
                return def.name();
            }

            @Override
            public int phase() {
                return fight.phase;
            }

            @Override
            public LivingEntity summon(final String type, final Location where) {
                final LivingEntity minion = spawn(type.toUpperCase(Locale.ROOT), where);
                if (minion == null) {
                    return null;
                }
                plugin.monsters().markSummoned(minion);
                plugin.monsters().decorate(minion, fight.level, MonsterRarity.NORMAL, List.of());
                tagMinion(minion, fight);
                return minion;
            }

            @Override
            public List<BossHazard> hazards() {
                return hazards;
            }

            @Override
            public boolean alerts() {
                return true;
            }

            @Override
            public String tempoKind() {
                return "pinnacle";
            }

            @Override
            public boolean dormant() {
                // 護盾中、挑戰的人不在場地上（回到筏上不能放技能）：不找人、不出招
                return fight.shieldUntil > 0 || onArena(fight).isEmpty();
            }
        });
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
            return living;
        }
        entity.remove();
        return null;
    }

    private void tagMinion(final LivingEntity minion, final Fight fight) {
        minion.getPersistentDataContainer().set(service.driftKey(), PersistentDataType.STRING, "whale_add:" + fight.owner);
        minion.setPersistent(false);
        fight.adds.add(minion.getUniqueId());
    }

    // ---- 每 10 tick ----

    void tick() {
        if (fights.isEmpty()) {
            return;
        }
        for (final Fight fight : new ArrayList<>(fights.values())) {
            tickFight(fight);
        }
    }

    private void tickFight(final Fight fight) {
        final LifeConfig.Whale def = config().whale();
        final Player owner = plugin.getServer().getPlayer(fight.owner);
        if (def == null || owner == null || !owner.isOnline() || !world().equals(owner.getWorld())) {
            end(fight, def == null ? "" : def.name() + "沉回了深海（挑戰的人離開了海域）");
            return;
        }
        if (fight.done) {
            return;
        }
        final LivingEntity boss = fight.boss;
        if (boss == null || !boss.isValid()) {
            end(fight, def.name() + "不見了");
            return;
        }
        keepSpectators(fight);
        final long now = System.currentTimeMillis();
        if (now - fight.startMillis > def.minutes() * 60_000L) {
            end(fight, "時間到，" + def.name() + "沉回了深海");
            return;
        }
        // 離開場地太遠（回筏、被沖回去）也算放棄——在筏上（場地西邊）還算在場；觀戰中的不算（keepSpectators 拉回來）
        final Location at = owner.getLocation();
        if (!owner.isDead() && !fight.down.containsKey(owner.getUniqueId()) && (Math.abs(at.getZ() - fight.cz) > 48 || at.getX() < fight.cx - LifeIslands.OFFSET - 24 || at.getX() > fight.cx + 40)) {
            end(fight, def.name() + "沉回了深海（離開了戰場）");
            return;
        }
        // 淵鯨不亂飄：拉回場地中間的上空
        final Location home = new Location(world(), fight.cx + 0.5, config().seaLevel() + ARENA_TOP, fight.cz + 0.5);
        if (boss.getLocation().distanceSquared(home) > 7 * 7 || Math.abs(boss.getLocation().getY() - home.getY()) > 2) {
            boss.teleport(home.setDirection(at.toVector().subtract(home.toVector())));
        }
        // 後來走進（游進）場地、待在這塊筏上的人也算一起打
        // 2026-10：淵鯨還有 90% 以上的生命才能加進來（以前打到剩一點再叫人進來，每個人都拿一整份巔峰獎勵）
        final AttributeInstance lateMax = boss.getAttribute(Attribute.MAX_HEALTH);
        final boolean lateJoin = lateMax == null || boss.getHealth() >= lateMax.getValue() * 0.9;
        for (final Player nearby : lateJoin ? world().getPlayers() : List.<Player>of()) {
            if (!nearby.isDead() && !nearby.equals(owner) && !fight.crew.contains(nearby.getUniqueId()) && service.activePlot(nearby) == fight.plot
                    && fight.contains(nearby.getLocation().getX(), nearby.getLocation().getZ()) && fightOf(nearby.getUniqueId()) == null) {
                fight.crew.add(nearby.getUniqueId());
                nearby.showBossBar(fight.bar);
                plugin.players().markDirty(nearby);
            }
        }
        // 只打場地上的人；回到筏上（不能放技能）就不追
        final List<Player> fighters = onArena(fight);
        final boolean onArena = !fighters.isEmpty();
        if (boss instanceof Mob mob) {
            if (onArena && !(mob.getTarget() instanceof Player target && fighters.contains(target))) {
                Player nearest = fighters.get(0);
                for (final Player member : fighters) {
                    if (member.getLocation().distanceSquared(boss.getLocation()) < nearest.getLocation().distanceSquared(boss.getLocation())) {
                        nearest = member;
                    }
                }
                mob.setTarget(nearest);
            } else if (!onArena && mob.getTarget() != null) {
                mob.setTarget(null);
            }
        }
        for (final UUID id : fight.adds) {
            final Entity entity = plugin.getServer().getEntity(id);
            if (entity instanceof Mob add && !onArena && add.getTarget() != null) {
                add.setTarget(null);
            }
            if (entity != null && !fight.contains(entity.getLocation().getX(), entity.getLocation().getZ())) {
                entity.teleport(fight.center()); // 不准離開場地（游去筏上）
            }
        }
        // 狂暴
        if (!fight.enraged && now - fight.startMillis > def.enrageAfter() * 1000L) {
            fight.enraged = true;
            plugin.monsters().invalidate(boss.getUniqueId());
            for (final Player member : audience(fight)) {
                member.sendMessage(plugin.text().prefixed("<red>" + ItemRenderer.escape(def.name()) + "狂暴了：傷害更多 " + Math.round(def.enrageDamage()) + "%"));
            }
            world().playSound(boss.getLocation(), Sound.ENTITY_ELDER_GUARDIAN_AMBIENT, 1.5f, 0.5f);
        }
        // 換階段
        final AttributeInstance max = boss.getAttribute(Attribute.MAX_HEALTH);
        final double fraction = Math.max(0, Math.min(1, boss.getHealth() / Math.max(1, max == null ? 1 : max.getValue())));
        if (fight.shieldUntil == 0 && fight.phase < def.phases().size() && fraction <= def.phases().get(fight.phase).at()) {
            final LifeConfig.WhalePhase next = def.phases().get(fight.phase);
            fight.phase++;
            plugin.monsters().invalidate(boss.getUniqueId());
            for (final Player member : audience(fight)) {
                member.showTitle(Title.title(plugin.text().parse("<#5fd3ff>" + ItemRenderer.escape(next.name())),
                        plugin.text().parse("<gray>" + ItemRenderer.escape(next.message())),
                        Title.Times.times(Duration.ofMillis(200), Duration.ofMillis(2000), Duration.ofMillis(400))));
            }
            if (next.shieldCount() > 0) {
                raiseShield(fight, next);
            }
        }
        updateShield(fight);
        fight.bar.progress((float) fraction);
        final long left = Math.max(0, (fight.startMillis + def.minutes() * 60_000L - now) / 1000);
        fight.bar.name(plugin.text().parse("<#5fd3ff>" + ItemRenderer.escape(def.name()) + " <gray>Lv." + fight.level
                + (fight.shieldUntil > 0 ? " <#ffd27f>⛨" : "") + (fight.enraged ? " <red>狂暴" : "")
                + " <dark_gray>｜ <white>" + String.format(Locale.ROOT, "%d:%02d", left / 60, left % 60)
                + " <dark_gray>｜ <gray>第 " + fight.door + "／" + def.attempts() + " 門"));
        if (random.nextInt(4) == 0) {
            world().spawnParticle(Particle.DRIPPING_WATER, boss.getLocation().add(0, 1, 0), 30, 2, 1, 2, 0);
        }
    }

    private void raiseShield(final Fight fight, final LifeConfig.WhalePhase phase) {
        fight.shield = phase;
        fight.shieldUntil = System.currentTimeMillis() + phase.shieldSeconds() * 1000L;
        fight.boss.setInvulnerable(true);
        final double tierLife = plugin.maps() == null ? 1
                : plugin.maps().config().shieldLifeMultiplier(plugin.maps().config().finalLifeMultiplier(plugin.maps().config().tiers().max()));
        final double offset = random.nextDouble() * Math.PI * 2;
        // 2026-10-18：潮核數量照場上的人數乘（life.yml 寫的 count 是一個人的份，最多算 6 人）
        final int count = Math.max(1, phase.shieldCount() * Math.max(1, Math.min(6, onArena(fight).size())));
        for (int i = 0; i < count; i++) {
            final double angle = offset + Math.PI * 2 * i / count;
            final Location spot = new Location(world(), fight.cx + 0.5 + Math.cos(angle) * 8, config().seaLevel() + ARENA_TOP, fight.cz + 0.5 + Math.sin(angle) * 8);
            final LivingEntity core = spawn("SHULKER", spot);
            if (core == null) {
                continue;
            }
            plugin.monsters().markSummoned(core);
            plugin.monsters().decorateData(core, new MonsterData(fight.level, MonsterRarity.MAGIC, List.of(), phase.shieldName(),
                    phase.shieldLife() * tierLife, 0.1, false));
            if (core instanceof Mob mob) {
                mob.setAI(false);
            }
            core.setGlowing(true);
            core.setSilent(true);
            tagMinion(core, fight);
            fight.cores.add(core.getUniqueId());
            world().spawnParticle(Particle.BUBBLE_COLUMN_UP, spot.clone().add(0, 1, 0), 30, 0.3, 0.6, 0.3, 0.05);
        }
        if (fight.cores.isEmpty()) {
            fight.shieldUntil = 0;
            fight.boss.setInvulnerable(false);
            return;
        }
        for (final Player owner : audience(fight)) {
            owner.sendMessage(plugin.text().prefixed("<#ffd27f>⛨ " + ItemRenderer.escape(config().whale().name()) + "被潮水包住了：" + phase.shieldSeconds()
                    + " 秒內打掉 " + fight.cores.size() + " 顆「" + ItemRenderer.escape(phase.shieldName()) + "」，不然牠會回復 " + Math.round(phase.shieldHeal()) + "% 生命"));
        }
        world().playSound(fight.boss.getLocation(), Sound.BLOCK_BEACON_ACTIVATE, 1.0f, 0.6f);
    }

    private void updateShield(final Fight fight) {
        if (fight.shieldUntil == 0) {
            return;
        }
        fight.cores.removeIf(id -> {
            final Entity entity = plugin.getServer().getEntity(id);
            return entity == null || !entity.isValid() || entity.isDead();
        });
        if (fight.cores.isEmpty()) {
            fight.shieldUntil = 0;
            fight.boss.setInvulnerable(false);
            for (final Player owner : audience(fight)) {
                owner.sendMessage(plugin.text().prefixed("<#ffd27f>⛨ 潮核全部打掉了，護盾破了！"));
            }
            world().playSound(fight.boss.getLocation(), Sound.BLOCK_GLASS_BREAK, 1.2f, 0.6f);
            return;
        }
        if (System.currentTimeMillis() >= fight.shieldUntil) {
            final AttributeInstance max = fight.boss.getAttribute(Attribute.MAX_HEALTH);
            if (max != null && fight.shield != null) {
                fight.boss.setHealth(Math.min(max.getValue(), fight.boss.getHealth() + max.getValue() * fight.shield.shieldHeal() / 100.0));
            }
            removeCores(fight);
            fight.shieldUntil = 0;
            fight.boss.setInvulnerable(false);
            for (final Player owner : audience(fight)) {
                owner.sendMessage(plugin.text().prefixed("<red>⛨ 時間到：" + ItemRenderer.escape(config().whale().name()) + "回復了 "
                        + Math.round(fight.shield == null ? 0 : fight.shield.shieldHeal()) + "% 生命"));
            }
        }
    }

    private void removeCores(final Fight fight) {
        for (final UUID id : fight.cores) {
            final Entity entity = plugin.getServer().getEntity(id);
            if (entity != null) {
                entity.remove();
            }
        }
        fight.cores.clear();
    }

    // ---- 倒下、打倒 ----

    /**
     * 挑戰的人倒下（LifeModule）。2026-10-22 照巔峰、極境改成「門」：整隊算一門，倒下的人重生在倒下的地方、變成觀戰
     * （不算人數、不會被打、不分掉落）；場上還有人站著就繼續打。全隊都倒下這一門才結束：還有門就進下一門
     * （淵鯨回滿、觀戰的人回到場地邊上一起重來），門用完就失敗。
     */
    void ownerDied(final Player player) {
        final Fight fight = fightOf(player.getUniqueId());
        final LifeConfig.Whale def = config().whale();
        if (fight == null || def == null || fight.done || fight.down.containsKey(player.getUniqueId())) {
            return;
        }
        Location anchor = player.getLocation().clone().add(0, 1, 0);
        if (!world().equals(anchor.getWorld()) || !fight.contains(anchor.getX(), anchor.getZ())) {
            anchor = edge(fight, 0); // 死在場地外（掉進海裡）：在場地邊上看
        }
        final GameMode previous = player.getGameMode() == GameMode.SPECTATOR ? GameMode.SURVIVAL : player.getGameMode();
        fight.down.put(player.getUniqueId(), new Down(anchor, previous));
        final List<Player> standing = new ArrayList<>(onArena(fight));
        standing.remove(player); // 死亡事件時這個人可能還沒算死
        if (standing.isEmpty()) {
            final String name = player.getName();
            plugin.getServer().getScheduler().runTask(plugin, () -> doorFailed(fight, name));
            return;
        }
        final String text = "<#5fd3ff>" + ItemRenderer.escape(player.getName()) + " 倒下了<gray>：在旁邊觀戰（第 " + fight.door + "／" + def.attempts()
                + " 門，場上還有 " + standing.size() + " 人）";
        for (final Player member : audience(fight)) {
            member.sendMessage(plugin.text().prefixed(text));
        }
    }

    /** 場地西邊（筏那一側）的落點；slot 錯開站。 */
    private Location edge(final Fight fight, final int slot) {
        return new Location(world(), fight.cx - RADIUS + 3.5, config().seaLevel() + ARENA_TOP + 0.1, fight.cz + 0.5 + (slot % 5 - 2), -90, 0);
    }

    /** 這一門全隊倒下：還有門就進下一門（淵鯨回滿、觀戰的人回來），沒有就失敗。 */
    private void doorFailed(final Fight fight, final String lastName) {
        final LifeConfig.Whale def = config().whale();
        if (def == null || fight.done || fights.get(fight.owner) != fight) {
            return;
        }
        if (!onArena(fight).isEmpty()) {
            return; // 這一 tick 之間有人回到場上（例如剛走進來的隊友）：繼續打
        }
        if (fight.door >= def.attempts()) {
            end(fight, ItemRenderer.escape(lastName) + " 倒下了，全隊倒下；" + def.attempts() + " 門都用完了，" + def.name() + "沉回了深海");
            return;
        }
        fight.door++;
        if (fight.boss != null && fight.boss.isValid()) {
            // 下一門：回滿、回到第一個階段、護盾收掉
            removeCores(fight);
            fight.shieldUntil = 0;
            fight.boss.setInvulnerable(false);
            fight.phase = 0; // 狂暴不重來（開打的時間照算）
            plugin.monsters().invalidate(fight.boss.getUniqueId());
            for (final UUID id : fight.adds) {
                final Entity add = plugin.getServer().getEntity(id);
                if (add != null) {
                    add.remove();
                }
            }
            fight.adds.clear();
            final AttributeInstance max = fight.boss.getAttribute(Attribute.MAX_HEALTH);
            if (max != null) {
                fight.boss.setHealth(max.getValue());
            }
            if (plugin.skills() != null) {
                plugin.skills().bossSkills().reset(fight.boss.getUniqueId());
            }
        }
        // 觀戰的人回來：模式改回去、站到場地邊上（還在死亡畫面的，重生時 respawn() 給場地邊上）
        int slot = 0;
        for (final Map.Entry<UUID, Down> entry : new ArrayList<>(fight.down.entrySet())) {
            final Player member = plugin.getServer().getPlayer(entry.getKey());
            if (member != null && member.isOnline() && !member.isDead()) {
                restore(member, entry.getValue());
                member.teleportAsync(edge(fight, slot++));
            }
        }
        fight.down.clear();
        final String text = "<#5fd3ff>全隊倒下：進入第 " + fight.door + "／" + def.attempts() + " 門<gray>，" + ItemRenderer.escape(def.name()) + "回滿了";
        for (final Player member : audience(fight)) {
            member.sendMessage(plugin.text().prefixed(text));
            member.showTitle(Title.title(plugin.text().parse("<#5fd3ff>第 " + fight.door + " 門"),
                    plugin.text().parse("<gray>" + ItemRenderer.escape(def.name()) + "回滿了，再來一次"),
                    Title.Times.times(Duration.ofMillis(300), Duration.ofMillis(2200), Duration.ofMillis(600))));
        }
    }

    /** 觀戰中的人：出界（飛走、換世界）拉回來或還原；下線的拿掉。 */
    private void keepSpectators(final Fight fight) {
        for (final Map.Entry<UUID, Down> entry : new ArrayList<>(fight.down.entrySet())) {
            final Player member = plugin.getServer().getPlayer(entry.getKey());
            if (member == null || !member.isOnline()) {
                continue; // 下線的在 leave() 處理
            }
            if (member.isDead() || member.getGameMode() != GameMode.SPECTATOR) {
                continue; // 還在死亡畫面
            }
            if (!world().equals(member.getWorld())) {
                // 觀戰中用指令離開海域：模式改回去，不再算這一場
                restore(member, entry.getValue());
                fight.down.remove(entry.getKey());
                fight.crew.remove(entry.getKey());
                member.hideBossBar(fight.bar);
                continue;
            }
            final Location at = member.getLocation();
            if (Math.abs(at.getX() - fight.cx) > RADIUS + 8 || Math.abs(at.getZ() - fight.cz) > RADIUS + 8
                    || at.getY() < config().seaLevel() - 8 || at.getY() > config().seaLevel() + 30) {
                member.teleport(entry.getValue().anchor());
            }
        }
    }

    /** 觀戰結束：模式改回去、拿掉標記。 */
    private void restore(final Player player, final Down down) {
        if (player.getGameMode() == GameMode.SPECTATOR) {
            player.setGameMode(down.previous());
        }
        player.getPersistentDataContainer().remove(spectatingKey());
    }

    /** 跟地圖觀戰同一個標記：伺服器當掉沒還原時，下次登入改回原本的模式（MapListener.onJoin → MapSpectators.restoreStale）。 */
    private org.bukkit.NamespacedKey spectatingKey() {
        return new org.bukkit.NamespacedKey(plugin, "map_spectating");
    }

    /** 淵鯨或牠的東西死了（LifeModule 的死亡事件）。 */
    void onDeath(final LivingEntity entity) {
        for (final Fight fight : new ArrayList<>(fights.values())) {
            fight.cores.remove(entity.getUniqueId());
            if (fight.boss != null && fight.boss.getUniqueId().equals(entity.getUniqueId()) && !fight.done) {
                fight.done = true;
                if (plugin.skills() != null) {
                    plugin.skills().bossSkills().detach(entity.getUniqueId());
                }
                // 一起打、還在場地或筏上的人每個人各拿一份（全服公告一次）
                final List<Player> winners = new ArrayList<>();
                for (final Player member : audience(fight)) {
                    if (fight.down.containsKey(member.getUniqueId())) {
                        continue; // 2026-10-22：觀戰的不分掉落（照巔峰、極境）
                    }
                    if (world().equals(member.getWorld()) && (fight.contains(member.getLocation().getX(), member.getLocation().getZ())
                            || service.activePlot(member) == fight.plot)) {
                        winners.add(member);
                    }
                }
                final List<String> names = new ArrayList<>();
                for (final Player member : winners) {
                    names.add(member.getName());
                }
                for (int i = 0; i < winners.size(); i++) {
                    reward(winners.get(i), fight, i == 0 ? String.join("、", names) : null);
                }
                plugin.getServer().getScheduler().runTaskLater(plugin, () -> end(fight, null), 20L * 8);
            }
        }
    }

    private void reward(final Player player, final Fight fight, final String announce) {
        final LifeConfig.Whale def = config().whale();
        if (def == null) {
            return;
        }
        final LifeService.Profile profile = service.profile(player);
        final int itemLevel = Math.max(85, fight.level);
        final List<String> got = new ArrayList<>();
        for (final LifeConfig.WhaleDrop drop : def.drops()) {
            // 終局以上的 Boss：不吃物品數量、稀有度
            if (random.nextDouble() >= drop.chance()) {
                continue;
            }
            switch (drop.kind()) {
                case "unique" -> {
                    for (final UniqueItem unique : plugin.items().repository().uniquesFor("abyss_whale", itemLevel)) {
                        if (unique.id().equals(drop.id())) {
                            give(player, DropTable.Drop.equipment(plugin.items().generator().generateUnique(unique, itemLevel, random)), got);
                        }
                    }
                }
                case "pool" -> {
                    final ItemData unique = tw.exilecore.map.MapDrops.unique(plugin.items().generator(), drop.id(), itemLevel, random);
                    if (unique != null) {
                        give(player, DropTable.Drop.equipment(unique), got);
                    }
                }
                case "skill" -> {
                    if (plugin.skills() != null && plugin.skills().config().skill(drop.id()) != null) {
                        give(player, DropTable.Drop.skill(drop.id(), tw.exilecore.character.SkillLoadout.MAX_LEVEL), got);
                    }
                }
                case "support" -> {
                    if (plugin.skills() != null && plugin.skills().config().support(drop.id()) != null) {
                        give(player, DropTable.Drop.support(drop.id(), itemLevel), got);
                    }
                }
                case "currency" -> {
                    service.giveCurrency(player, drop.id(), drop.amount());
                    got.add(service.currencyName(drop.id()) + (drop.amount() > 1 ? " ×" + drop.amount() : ""));
                }
                case "material" -> {
                    if (profile != null) {
                        service.addMaterial(profile, drop.id(), drop.amount());
                        got.add(service.materialName(drop.id()) + " ×" + drop.amount());
                    }
                }
                default -> {
                }
            }
        }
        boolean first = false;
        if (profile != null) {
            first = profile.whaleKills == 0;
            profile.whaleKills++;
            profile.dirty = true;
            service.addXp(player, profile, def.xp());
            service.flush(player);
        }
        final long seconds = (System.currentTimeMillis() - fight.startMillis) / 1000;
        player.showTitle(Title.title(plugin.text().parse("<#5fd3ff>" + ItemRenderer.escape(def.name()) + " 倒下了"),
                plugin.text().parse("<gray>" + String.format(Locale.ROOT, "%d:%02d", seconds / 60, seconds % 60)),
                Title.Times.times(Duration.ofMillis(300), Duration.ofMillis(2600), Duration.ofMillis(600))));
        if (!def.defeat().isBlank()) {
            player.sendMessage(plugin.text().parse("<#5fd3ff>" + ItemRenderer.escape(def.defeat())));
        }
        player.sendMessage(plugin.text().prefixed("<#5fd3ff>" + ItemRenderer.escape(def.name()) + "的戰利品：<white>" + ItemRenderer.escape(String.join("、", got))
                + "<gray>；生活經驗 +" + def.xp()));
        if (first && config().titles().containsKey("abyss_hunter")) {
            plugin.players().markDirty(player);
            player.sendMessage(plugin.text().prefixed("<#5fd3ff>第一次打倒" + ItemRenderer.escape(def.name()) + "！獲得稱號「"
                    + ItemRenderer.escape(config().titles().get("abyss_hunter").name()) + "」（稱號選單可以戴）"));
        }
        if (announce == null) {
            return;
        }
        for (final Player online : plugin.getServer().getOnlinePlayers()) {
            online.sendMessage(plugin.text().prefixed("<#5fd3ff>🐋 " + ItemRenderer.escape(announce) + " 在" + ItemRenderer.escape(config().name())
                    + "打倒了深淵巨獸「" + ItemRenderer.escape(def.name()) + "」！<gray>（" + String.format(Locale.ROOT, "%d:%02d", seconds / 60, seconds % 60) + "）"));
        }
        plugin.getSLF4JLogger().info("[Boss 紀錄] {}：{} 被 {} 打倒（{} 秒、第 {} 門）", config().name(), def.name(), player.getName(), seconds, fight.door);
    }

    private void give(final Player player, final DropTable.Drop drop, final List<String> got) {
        final ItemStack stack = MonsterListener.toStack(plugin, drop);
        if (stack == null) {
            return;
        }
        service.giveItem(player, stack);
        final net.kyori.adventure.text.Component name = stack.getItemMeta() == null ? null : stack.getItemMeta().displayName();
        got.add(name == null ? stack.getType().name() : net.kyori.adventure.text.serializer.plain.PlainTextComponentSerializer.plainText().serialize(name));
    }

    /**
     * 挑戰中倒下的重生點：這一門還在打 → 倒下的地方、變成觀戰；已經換到下一門 → 場地邊上；結束了 null（回筏）。
     */
    Location respawn(final Player player) {
        final Fight fight = fightOf(player.getUniqueId());
        if (fight == null || fight.done) {
            return null;
        }
        final Down down = fight.down.get(player.getUniqueId());
        if (down == null) {
            return edge(fight, 0);
        }
        final LifeConfig.Whale def = config().whale();
        plugin.getServer().getScheduler().runTask(plugin, () -> {
            if (!player.isOnline() || fight.done || fight.down.get(player.getUniqueId()) != down) {
                return;
            }
            player.setGameMode(GameMode.SPECTATOR);
            player.getPersistentDataContainer().set(spectatingKey(), PersistentDataType.STRING, down.previous().name());
            if (plugin.skills() != null) {
                plugin.skills().dismissSummons(player); // 召喚物、圖騰一起收掉
            }
            player.showTitle(Title.title(plugin.text().parse("<#ff5555>你倒下了"),
                    plugin.text().parse("<gray>觀戰中：隊友全倒下才進下一門"),
                    Title.Times.times(Duration.ofMillis(300), Duration.ofMillis(3000), Duration.ofMillis(600))));
            player.sendMessage(plugin.text().prefixed("<gray>你在旁邊觀戰（不算人數、不會被打、也不分掉落）；"
                    + (def != null && fight.door < def.attempts() ? "全隊都倒下就一起進第 " + (fight.door + 1) + " 門" : "這是最後一門")
                    + "，打倒或失敗後回到筏上"));
        });
        return down.anchor();
    }

    // ---- 收掉 ----

    /** 離開海域（下線、換世界）：放棄。 */
    void leave(final Player player) {
        for (final Fight any : fights.values()) {
            final Down down = any.down.remove(player.getUniqueId());
            if (down != null) {
                restore(player, down); // 觀戰中下線、換筏：模式改回去
            }
        }
        final Fight fight = fights.get(player.getUniqueId());
        if (fight != null && !fight.done) {
            end(fight, null);
        }
        for (final Fight other : fights.values()) {
            if (other.crew.remove(player.getUniqueId())) {
                player.hideBossBar(other.bar);
                plugin.players().markDirty(player);
            }
        }
    }

    private void end(final Fight fight, final String reason) {
        if (fights.get(fight.owner) != fight) {
            return;
        }
        fights.remove(fight.owner);
        if (fight.boss != null) {
            if (plugin.skills() != null) {
                plugin.skills().bossSkills().detach(fight.boss.getUniqueId());
            }
            if (fight.boss.isValid()) {
                fight.boss.remove();
            }
        }
        removeCores(fight);
        for (final UUID id : fight.adds) {
            final Entity add = plugin.getServer().getEntity(id);
            if (add != null) {
                add.remove();
            }
        }
        fight.adds.clear();
        // 觀戰的人：模式改回去、回到筏上（場地要收掉了）
        for (final Map.Entry<UUID, Down> entry : fight.down.entrySet()) {
            final Player member = plugin.getServer().getPlayer(entry.getKey());
            if (member != null && member.isOnline() && !member.isDead()) {
                restore(member, entry.getValue());
                if (fight.plot >= 0 && world().equals(member.getWorld())) {
                    member.teleportAsync(service.raftSpawn(fight.plot));
                }
            }
        }
        fight.down.clear();
        for (final Player owner : audience(fight)) {
            owner.hideBossBar(fight.bar);
            plugin.players().markDirty(owner);
            if (reason != null && !reason.isBlank()) {
                owner.sendMessage(plugin.text().prefixed("<gray>" + ItemRenderer.escape(reason)));
            }
        }
        if (world() != null) {
            for (final Entity entity : world().getNearbyEntities(fight.center(), RADIUS + 6, 20, RADIUS + 6)) {
                final String tag = entity.getPersistentDataContainer().get(service.driftKey(), PersistentDataType.STRING);
                if (tag != null && (tag.startsWith(TAG) || tag.startsWith("whale_add:"))) {
                    entity.remove();
                }
            }
            clearArena(fight);
            LifeService.holdChunks(plugin, world(), fight.cx, fight.cz, RADIUS, false);
        }
    }

    void shutdown() {
        for (final Fight fight : new ArrayList<>(fights.values())) {
            end(fight, null);
        }
    }

    // ---- 場地 ----

    private void buildArena(final Fight fight) {
        clearArena(fight);
        final int sea = config().seaLevel();
        for (int dx = -RADIUS; dx <= RADIUS; dx++) {
            for (int dz = -RADIUS; dz <= RADIUS; dz++) {
                final double d = Math.sqrt(dx * dx + dz * dz);
                if (d > RADIUS + 0.4) {
                    continue;
                }
                final int x = fight.cx + dx;
                final int z = fight.cz + dz;
                for (int y = sea - 4; y < sea + ARENA_TOP - 1; y++) {
                    world().getBlockAt(x, y, z).setType(Material.PRISMARINE, false);
                }
                final Material top = d > RADIUS - 0.8 ? Material.DARK_PRISMARINE
                        : Math.abs(d - 10) < 0.5 || Math.abs(d - 5) < 0.5 ? Material.PRISMARINE_BRICKS
                        : (dx * 7 + dz * 13 & 15) == 0 ? Material.SEA_LANTERN : Material.DARK_PRISMARINE;
                world().getBlockAt(x, sea + ARENA_TOP - 1, z).setType(top, false); // 頂面那一層（跟海面同一層）
            }
        }
    }

    private void clearArena(final Fight fight) {
        final int sea = config().seaLevel();
        for (int x = fight.cx - RADIUS - 1; x <= fight.cx + RADIUS + 1; x++) {
            for (int z = fight.cz - RADIUS - 1; z <= fight.cz + RADIUS + 1; z++) {
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
}
