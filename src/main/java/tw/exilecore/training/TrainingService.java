package tw.exilecore.training;

import net.kyori.adventure.bossbar.BossBar;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.NamespacedKey;
import org.bukkit.World;
import org.bukkit.attribute.Attribute;
import org.bukkit.attribute.AttributeInstance;
import org.bukkit.entity.Entity;
import org.bukkit.entity.LivingEntity;
import org.bukkit.entity.Mannequin;
import org.bukkit.entity.Player;
import org.bukkit.inventory.EntityEquipment;
import org.bukkit.inventory.ItemStack;
import org.bukkit.persistence.PersistentDataType;
import tw.exilecore.ExileCore;
import tw.exilecore.item.ItemRenderer;
import tw.exilecore.map.MapStats;
import tw.exilecore.monster.MonsterData;
import tw.exilecore.monster.MonsterRarity;
import tw.exilecore.stats.HitResult;
import tw.exilecore.stats.StatKeys;
import tw.exilecore.stats.StatSheet;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;

/**
 * 營地的訓練假人（data/dummies.yml）：
 * <ul>
 *   <li>假人是原版的人體模型（不會動、沒有 AI），裝扮成怪物（等級、名字、首領），防禦照設定（護甲、閃避、抗性）。</li>
 *   <li>不會死：每一下打之前生命補滿、會打死的那一下只扣到剩 1；點燃不扣血；死亡事件取消。不掉東西、不給經驗。</li>
 *   <li>只有玩家打得到（摔落、火、其他生物都不算）。</li>
 *   <li>不見了補一隻、多的拿掉、跑掉了移回去、設定改了重新裝扮（每 5 秒，只管已經載入的區塊）。</li>
 *   <li>練習場（假人的外框往外幾格）裡可以施放技能；營地其他地方照樣是安全區。</li>
 *   <li>每個人打每一隻各自記一段：畫面上方的計量條（每秒傷害、總傷害、秒數；停手時慢慢變短），停手幾秒後聊天框給總結。</li>
 * </ul>
 */
public final class TrainingService {

    private final ExileCore plugin;
    private final DummyConfig config;
    /** 假人的代號（PDC）。 */
    private final NamespacedKey dummyKey;
    /** 裝扮時的設定（PDC；設定改了就重新裝扮）。 */
    private final NamespacedKey signatureKey;
    /** 找過地面的位置：代號 → 位置。 */
    private final Map<String, Location> spots = new HashMap<>();
    /** 玩家 → （假人 → 這一段）。 */
    private final Map<UUID, Map<UUID, Session>> sessions = new HashMap<>();
    private final Map<UUID, BossBar> bars = new HashMap<>();

    /** 一個人打一隻假人的一段。 */
    private static final class Session {
        final UUID dummy;
        final String name;
        final DpsMeter meter = new DpsMeter();

        Session(final UUID dummy, final String name) {
            this.dummy = dummy;
            this.name = name;
        }
    }

    TrainingService(final ExileCore plugin, final DummyConfig config) {
        this.plugin = plugin;
        this.config = config;
        this.dummyKey = new NamespacedKey(plugin, "training_dummy");
        this.signatureKey = new NamespacedKey(plugin, "training_dummy_look");
    }

    DummyConfig config() {
        return config;
    }

    // ---- 假人 ----

    /** 這隻是不是訓練假人。 */
    public boolean isDummy(final Entity entity) {
        return entity != null && entity.getPersistentDataContainer().has(dummyKey, PersistentDataType.STRING);
    }

    /** 這隻假人的設定（不是假人、或設定裡已經沒有這一隻時是 null）。 */
    DummyConfig.Dummy dummy(final Entity entity) {
        final String id = entity.getPersistentDataContainer().get(dummyKey, PersistentDataType.STRING);
        return id == null ? null : config.dummy(id);
    }

    /** 營地落點（沒有營地時是 null）。 */
    private Location camp() {
        return plugin.zones() == null ? null : plugin.zones().campLocation();
    }

    /** 假人站的位置（營地落點加上偏移，高度找地面；區塊沒載入時是 null）。 */
    Location spot(final DummyConfig.Dummy dummy) {
        final Location cached = spots.get(dummy.id());
        if (cached != null) {
            return cached.clone();
        }
        final Location camp = camp();
        if (camp == null || camp.getWorld() == null) {
            return null;
        }
        final World world = camp.getWorld();
        final double x = camp.getX() + dummy.offsetX();
        final double z = camp.getZ() + dummy.offsetZ();
        final int blockX = (int) Math.floor(x);
        final int blockZ = (int) Math.floor(z);
        if (!world.isChunkLoaded(blockX >> 4, blockZ >> 4)) {
            return null;
        }
        final int y = ground(world, blockX, (int) Math.floor(camp.getY()), blockZ);
        final Location spot = new Location(world, x, y, z, dummy.yaw(), 0f);
        spots.put(dummy.id(), spot);
        return spot.clone();
    }

    /** 落點高度附近的地面：腳下是實心方塊、站的兩格是空的；找不到就用落點的高度。 */
    private static int ground(final World world, final int x, final int nearY, final int z) {
        for (int y = nearY + 6; y >= nearY - 8; y--) {
            if (world.getBlockAt(x, y - 1, z).getType().isSolid() && world.getBlockAt(x, y, z).isPassable()
                    && world.getBlockAt(x, y + 1, z).isPassable()) {
                return y;
            }
        }
        return nearY;
    }

    /** 練習場（所有假人的外框往外 arena_margin 格）：在裡面可以施放技能。 */
    public boolean inArena(final Location location) {
        final Location camp = camp();
        if (camp == null || location.getWorld() == null || !location.getWorld().equals(camp.getWorld()) || config.dummies().isEmpty()) {
            return false;
        }
        double minX = Double.MAX_VALUE;
        double maxX = -Double.MAX_VALUE;
        double minZ = Double.MAX_VALUE;
        double maxZ = -Double.MAX_VALUE;
        for (final DummyConfig.Dummy dummy : config.dummies()) {
            minX = Math.min(minX, dummy.offsetX());
            maxX = Math.max(maxX, dummy.offsetX());
            minZ = Math.min(minZ, dummy.offsetZ());
            maxZ = Math.max(maxZ, dummy.offsetZ());
        }
        final double margin = config.arenaMargin();
        final double x = location.getX() - camp.getX();
        final double z = location.getZ() - camp.getZ();
        final double y = location.getY() - camp.getY();
        return x >= minX - margin && x <= maxX + margin && z >= minZ - margin && z <= maxZ + margin && y >= -12 && y <= 24;
    }

    /** 每 5 秒：每隻假人在不在、位置對不對、裝扮跟設定一樣；設定裡沒有的拿掉（只管已經載入的區塊）。 */
    void maintain() {
        final Location camp = camp();
        if (camp == null || camp.getWorld() == null) {
            return;
        }
        final Map<String, List<Mannequin>> found = new HashMap<>();
        for (final Mannequin mannequin : camp.getWorld().getEntitiesByClass(Mannequin.class)) {
            final String id = mannequin.getPersistentDataContainer().get(dummyKey, PersistentDataType.STRING);
            if (id == null || !mannequin.isValid()) {
                continue;
            }
            final DummyConfig.Dummy dummy = config.dummy(id);
            if (dummy == null) {
                mannequin.remove(); // 設定裡拿掉的假人
                continue;
            }
            found.computeIfAbsent(dummy.id(), key -> new ArrayList<>()).add(mannequin);
        }
        for (final DummyConfig.Dummy dummy : config.dummies()) {
            try {
                maintain(dummy, found.getOrDefault(dummy.id(), List.of()));
            } catch (final RuntimeException e) {
                plugin.getSLF4JLogger().warn("訓練假人 {} 放不出來", dummy.id(), e); // 一隻出錯不影響其他隻
            }
        }
    }

    private void maintain(final DummyConfig.Dummy dummy, final List<Mannequin> mine) {
        final Location spot = spot(dummy);
        if (spot == null) {
            return;
        }
        if (mine.isEmpty()) {
            // 實體還沒載入完時不放（不然載入後會多一隻）
            if (spot.getWorld().isChunkLoaded(spot.getBlockX() >> 4, spot.getBlockZ() >> 4) && spot.getChunk().isEntitiesLoaded()) {
                spawn(dummy, spot);
            }
            return;
        }
        final Mannequin keep = mine.getFirst();
        for (int i = 1; i < mine.size(); i++) {
            mine.get(i).remove();
        }
        if (keep.getLocation().distanceSquared(spot) > 0.25) {
            keep.teleport(spot);
        }
        final int level = levelFor(dummy, currentLevel(keep));
        if (!signature(dummy).equals(keep.getPersistentDataContainer().get(signatureKey, PersistentDataType.STRING))) {
            dress(keep, dummy, level);
        } else if (currentLevel(keep) != level || !plugin.monsters().isMonster(keep)) {
            decorate(keep, dummy, level); // 固定等級、最高階地圖的等級改了（maps.yml）
        }
        // 原版的受傷無敵時間不會存檔：伺服器重開、區塊重載後假人又變回 20 tick，快速連打（瞬影擊連閃）只算第一下
        keep.setMaximumNoDamageTicks(0);
        refill(keep);
        if (!sessionsOn(keep.getUniqueId())) {
            plugin.monsters().ledger().forget(keep.getUniqueId()); // 打假人的紀錄不用留（假人不會死，不分掉落）
        }
    }

    private boolean sessionsOn(final UUID dummy) {
        for (final Map<UUID, Session> mine : sessions.values()) {
            if (mine.containsKey(dummy)) {
                return true;
            }
        }
        return false;
    }

    private Mannequin spawn(final DummyConfig.Dummy dummy, final Location spot) {
        final Mannequin mannequin = spot.getWorld().spawn(spot, Mannequin.class, entity -> {
            entity.getPersistentDataContainer().set(dummyKey, PersistentDataType.STRING, dummy.id());
            entity.setImmovable(true);
            entity.setSilent(true);
            entity.setPersistent(true);
            entity.setRemoveWhenFarAway(false);
        });
        dress(mannequin, dummy, levelFor(dummy, 1));
        return mannequin;
    }

    private int currentLevel(final LivingEntity entity) {
        return plugin.monsters().data(entity).map(MonsterData::level).orElse(1);
    }

    private static int levelFor(final DummyConfig.Dummy dummy, final int current) {
        return switch (dummy.level()) {
            case DummyConfig.LEVEL_PLAYER -> Math.max(1, current);
            case DummyConfig.LEVEL_MAP -> MapStats.tiers().topLevel();
            default -> dummy.level();
        };
    }

    /** 裝扮：怪物資料、說明、外觀、體型；生命補滿。 */
    private void dress(final Mannequin entity, final DummyConfig.Dummy dummy, final int level) {
        decorate(entity, dummy, level);
        final AttributeInstance scale = entity.getAttribute(Attribute.SCALE);
        if (scale != null) {
            scale.setBaseValue(dummy.scale());
        }
        equip(entity, dummy.look());
        entity.getPersistentDataContainer().set(signatureKey, PersistentDataType.STRING, signature(dummy));
        refill(entity);
    }

    /** 怪物資料（等級、名字、首領、生命倍率；附近的玩家數固定 1）與名字下面的說明；生命補滿。 */
    private void decorate(final Mannequin entity, final DummyConfig.Dummy dummy, final int level) {
        final MonsterData data = new MonsterData(level, dummy.boss() ? MonsterRarity.UNIQUE : MonsterRarity.NORMAL, List.of(),
                dummy.name(), dummy.life(), 1.0, dummy.boss());
        plugin.monsters().decorateExact(entity, data, 1);
        entity.setDescription(plugin.text().parse("<gray>" + ItemRenderer.escape(dummy.note())
                + (dummy.level() == DummyConfig.LEVEL_PLAYER ? "<dark_gray>・等級跟著打它的人" : "")));
        refill(entity);
    }

    private static String signature(final DummyConfig.Dummy dummy) {
        return Integer.toHexString(dummy.toString().hashCode());
    }

    private static void equip(final Mannequin entity, final String look) {
        final EntityEquipment equipment = entity.getEquipment();
        final String prefix = look.toLowerCase(Locale.ROOT);
        equipment.setHelmet(piece(prefix, "helmet"));
        equipment.setChestplate(piece(prefix, "chestplate"));
        equipment.setLeggings(piece(prefix, "leggings"));
        equipment.setBoots(piece(prefix, "boots"));
    }

    private static ItemStack piece(final String look, final String slot) {
        if ("none".equals(look)) {
            return null;
        }
        final Material material = Material.matchMaterial(look + "_" + slot);
        return material == null ? null : new ItemStack(material);
    }

    private static void refill(final LivingEntity entity) {
        final AttributeInstance max = entity.getAttribute(Attribute.MAX_HEALTH);
        if (max != null && entity.getHealth() < max.getValue()) {
            entity.setHealth(max.getValue());
        }
    }

    /** 怪物屬性表的加成來源：假人的護甲、閃避（一般怪物的幾倍）與抗性。 */
    void augment(final LivingEntity entity, final StatSheet sheet) {
        final DummyConfig.Dummy dummy = dummy(entity);
        if (dummy == null) {
            return;
        }
        sheet.setBase(StatKeys.ARMOUR, sheet.base(StatKeys.ARMOUR) * dummy.armour());
        sheet.setBase(StatKeys.EVASION, sheet.base(StatKeys.EVASION) * dummy.evasion());
        sheet.setBase(StatKeys.RESIST_FIRE, dummy.fire());
        sheet.setBase(StatKeys.RESIST_COLD, dummy.cold());
        sheet.setBase(StatKeys.RESIST_LIGHTNING, dummy.lightning());
        sheet.setBase(StatKeys.RESIST_CHAOS, dummy.chaos());
    }

    // ---- 打假人 ----

    /** 玩家要打假人（傷害事件的最前面）：等級跟著玩家的換成這個玩家的等級；生命補滿。 */
    void beforeHit(final Player player, final Mannequin entity) {
        final DummyConfig.Dummy dummy = dummy(entity);
        if (dummy == null) {
            return;
        }
        if (dummy.level() == DummyConfig.LEVEL_PLAYER && plugin.players() != null) {
            final int level = Math.max(1, plugin.players().get(player).level());
            if (currentLevel(entity) != level) {
                decorate(entity, dummy, level);
            }
        }
        refill(entity);
    }

    /** 一次命中算好了（打中、沒打中、被格擋）。 */
    void onHit(final Player player, final LivingEntity entity, final HitResult result, final boolean cancelled) {
        final Session session = session(player, entity);
        final long now = System.currentTimeMillis();
        if (result.blocked()) {
            session.meter.block(now);
        } else if (cancelled || !result.hit()) {
            session.meter.miss(now);
        } else {
            session.meter.hit(now, result.total(), result.crit());
        }
        showBar(player, session, now);
    }

    /** 點燃燒到假人：記給點燃的人，假人不扣血。不是假人時回傳 false（照原本的扣血）。 */
    public boolean absorbDot(final LivingEntity entity, final UUID source, final double amount) {
        if (!isDummy(entity)) {
            return false;
        }
        final Player player = source == null ? null : plugin.getServer().getPlayer(source);
        if (player != null && amount > 0) {
            session(player, entity).meter.dot(System.currentTimeMillis(), amount);
        }
        return true;
    }

    private Session session(final Player player, final LivingEntity entity) {
        final Map<UUID, Session> mine = sessions.computeIfAbsent(player.getUniqueId(), key -> new LinkedHashMap<>());
        return mine.computeIfAbsent(entity.getUniqueId(), key -> {
            final DummyConfig.Dummy dummy = dummy(entity);
            final String name = (dummy == null ? "訓練假人" : dummy.name()) + "（Lv." + currentLevel(entity) + "）";
            return new Session(entity.getUniqueId(), name);
        });
    }

    /** 每 5 tick：計量條更新；停手太久的一段結束（聊天框給總結）。 */
    void tick() {
        final long now = System.currentTimeMillis();
        final long idle = config.idleSeconds() * 1000L;
        for (final Iterator<Map.Entry<UUID, Map<UUID, Session>>> players = sessions.entrySet().iterator(); players.hasNext(); ) {
            final Map.Entry<UUID, Map<UUID, Session>> entry = players.next();
            final Player player = plugin.getServer().getPlayer(entry.getKey());
            if (player == null) {
                bars.remove(entry.getKey());
                players.remove();
                continue;
            }
            Session latest = null;
            for (final Iterator<Session> it = entry.getValue().values().iterator(); it.hasNext(); ) {
                final Session session = it.next();
                if (now - session.meter.lastActivity() > idle) {
                    it.remove(); // 先拿掉：總結裡看「還有沒有人在打這隻」才準
                    summary(player, session);
                } else if (latest == null || session.meter.lastActivity() > latest.meter.lastActivity()) {
                    latest = session;
                }
            }
            if (latest == null) {
                hideBar(player);
                players.remove();
            } else {
                showBar(player, latest, now);
            }
        }
    }

    private void showBar(final Player player, final Session session, final long now) {
        final DpsMeter meter = session.meter;
        final float progress = (float) Math.max(0, Math.min(1, 1 - (now - meter.lastActivity()) / (config.idleSeconds() * 1000.0)));
        final net.kyori.adventure.text.Component title = plugin.text().parse("<#f5b942>" + ItemRenderer.escape(session.name)
                + "　<gray>每秒 <white><b>" + number(meter.dps()) + "</b>　<gray>總傷害 <white>" + number(meter.total())
                + "　<gray>" + String.format(Locale.ROOT, "%.1f", meter.seconds()) + " 秒");
        final BossBar bar = bars.get(player.getUniqueId());
        if (bar == null) {
            final BossBar created = BossBar.bossBar(title, progress, BossBar.Color.YELLOW, BossBar.Overlay.PROGRESS);
            bars.put(player.getUniqueId(), created);
            player.showBossBar(created);
        } else {
            bar.name(title);
            bar.progress(progress);
        }
    }

    private void hideBar(final Player player) {
        final BossBar bar = bars.remove(player.getUniqueId());
        if (bar != null) {
            player.hideBossBar(bar);
        }
    }

    /** 管理員觀看（2026-10）：開著的管理員也會收到別人打靶的統計。 */
    private final java.util.Set<java.util.UUID> watchers = new java.util.HashSet<>();

    /** 切換管理員觀看；回傳切換後是不是開著。 */
    public boolean toggleWatch(final Player admin) {
        if (watchers.remove(admin.getUniqueId())) {
            return false;
        }
        watchers.add(admin.getUniqueId());
        return true;
    }

    private void summary(final Player player, final Session session) {
        final DpsMeter meter = session.meter;
        if (meter.isEmpty()) {
            return;
        }
        for (final java.util.UUID id : watchers) {
            final Player admin = plugin.getServer().getPlayer(id);
            if (admin == null || admin.equals(player)) {
                continue;
            }
            final String who = plugin.players().get(player).character() == null ? player.getName()
                    : plugin.players().get(player).character().name() + "（" + player.getName() + "）";
            admin.sendMessage(plugin.text().prefixed("<#8fd3ff>〔打靶觀看〕<white>" + ItemRenderer.escape(who) + "<gray> 打 "
                    + ItemRenderer.escape(session.name) + String.format(Locale.ROOT, "：<white>%.1f<gray> 秒，總傷害 <white>", meter.seconds())
                    + number(meter.total()) + "<gray>，每秒 <#f5b942><b>" + number(meter.dps()) + "</b><gray>（命中 " + meter.hits()
                    + "、暴擊 " + percent(meter.critRate()) + "、最高一擊 " + number(meter.highest()) + "）"));
        }
        player.sendMessage(plugin.text().prefixed("<#f5b942>〔訓練〕" + ItemRenderer.escape(session.name) + "<gray>："
                + String.format(Locale.ROOT, "<white>%.1f<gray> 秒，總傷害 <white>", meter.seconds()) + number(meter.total())
                + (meter.dotTotal() > 0 ? "<gray>（點燃 " + number(meter.dotTotal()) + "）" : "")
                + "<gray>，每秒 <#f5b942><b>" + number(meter.dps()) + "</b>"));
        player.sendMessage(plugin.text().parse("<gray>　命中 <white>" + meter.hits() + "<gray>・暴擊 <white>" + meter.crits()
                + "<gray>（" + percent(meter.critRate()) + "）・未命中 <white>" + meter.misses() + "<gray>・格擋 <white>" + meter.blocks()
                + "<gray>（命中率 " + percent(meter.hitRate()) + "）　最高一擊 <white>" + number(meter.highest())));
        if (!sessionsOn(session.dummy)) {
            plugin.monsters().ledger().forget(session.dummy);
        }
    }

    private static String number(final double value) {
        return DpsMeter.number(value);
    }

    private static String percent(final double fraction) {
        return Math.round(fraction * 100) + "%";
    }

    /** 下線：紀錄丟掉。 */
    void forget(final Player player) {
        sessions.remove(player.getUniqueId());
        hideBar(player);
    }

    /** 重讀設定：位置重找、假人的屬性表重算（下一次維護時照新設定裝扮、移位置）。 */
    void reloaded() {
        spots.clear();
        final Location camp = camp();
        if (camp != null && camp.getWorld() != null) {
            for (final Mannequin mannequin : camp.getWorld().getEntitiesByClass(Mannequin.class)) {
                if (isDummy(mannequin)) {
                    plugin.monsters().invalidate(mannequin.getUniqueId());
                }
            }
        }
        maintain();
    }

    /** 關閉：計量條收起來。 */
    void shutdown() {
        for (final UUID uuid : List.copyOf(bars.keySet())) {
            final Player player = plugin.getServer().getPlayer(uuid);
            if (player != null) {
                hideBar(player);
            }
        }
        bars.clear();
        sessions.clear();
    }
}
