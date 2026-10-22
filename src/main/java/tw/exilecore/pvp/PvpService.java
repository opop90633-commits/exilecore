package tw.exilecore.pvp;

import net.kyori.adventure.title.Title;
import org.bukkit.Chunk;
import org.bukkit.GameMode;
import org.bukkit.Location;
import org.bukkit.Sound;
import org.bukkit.World;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.entity.PlayerDeathEvent;
import org.bukkit.event.player.PlayerChangedWorldEvent;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.event.player.PlayerRespawnEvent;
import org.bukkit.scheduler.BukkitTask;
import tw.exilecore.ExileCore;
import tw.exilecore.character.PlayerState;
import tw.exilecore.item.ItemRenderer;
import tw.exilecore.world.BlockPalette;
import tw.exilecore.worldgen.BufferSink;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;

/**
 * PvP 競技場：只有競技場的世界（{@code exilecore:arena}）開 PvP，誰能打誰在 {@link #canAttack}；
 * 傷害由戰鬥模組乘上 {@link #damageMultiplier()}（專屬的 PvP 減傷）。
 * <ul>
 *   <li>進出：/pvp（選單）、/pvp join、/pvp leave；用 /town、傳送點離開也可以。進場時生命、魔力、護盾、藥劑補滿，清掉異常。</li>
 *   <li>倒下：不扣經驗、不算死亡次數（角色模組看 {@link #inArena}），重生在離其他人最遠的出生點（有入場保護）；
 *       擊倒的訊息只給場內的人，擊倒的人藥劑充能。</li>
 *   <li>擊倒算誰的：10 秒內最後一個打到他的玩家（持續傷害燒死的也算）。</li>
 *   <li>場地：第一次啟動自動蓋（{@link ArenaLayout}），版本變了先清掉再蓋；/ec pvp rebuild 重蓋。</li>
 * </ul>
 * 只在主執行緒用。
 */
public final class PvpService implements Listener {

    /** 世界資料夾裡記蓋好的場地版本的檔案。 */
    static final String MARKER = "exilecore-arena.txt";
    /** 多久內最後打到的人算擊倒（tick）。 */
    static final long CREDIT_TICKS = 200;
    /** 一個 tick 最多放幾格方塊（蓋場地時）。 */
    private static final int BLOCKS_PER_TICK = 20_000;

    /** 這次在競技場的戰績（離開就清掉）。 */
    static final class Session {
        int kills;
        int deaths;
        int streak;
        int bestStreak;
    }

    private record Hit(UUID attacker, long tick) {
    }

    private final ExileCore plugin;
    private final PvpConfig config;
    private final ArenaLayout layout = ArenaLayout.standard();
    private final Map<UUID, Session> sessions = new HashMap<>();
    private final Map<UUID, Hit> lastHits = new HashMap<>();
    /** 保底（2026-10）：每個人最近一秒被玩家打掉多少。 */
    private final Map<UUID, PvpDamageCap> caps = new HashMap<>();
    /** 在競技場倒下、等著重生在場內的人。 */
    private final Set<UUID> fallen = new HashSet<>();
    /** 進場前的位置（劇情世界裡的才記；離開時回那裡）。 */
    private final Map<UUID, Location> returnTo = new HashMap<>();
    private boolean building;
    /** 場地是現在的版本（蓋好了）。 */
    private boolean ready;
    private BukkitTask buildTask;

    PvpService(final ExileCore plugin, final PvpConfig config) {
        this.plugin = plugin;
        this.config = config;
    }

    public PvpConfig config() {
        return config;
    }

    ArenaLayout layout() {
        return layout;
    }

    /** 競技場的世界；沒有載入回傳 null。 */
    public World arena() {
        return plugin.world() == null || plugin.world().worlds() == null ? null : plugin.world().worlds().arena();
    }

    /** 這個人在競技場裡。 */
    public boolean inArena(final Player player) {
        final World arena = arena();
        return arena != null && player != null && player.getWorld().equals(arena);
    }

    /** 這個位置在競技場的世界。 */
    public boolean inArena(final Location location) {
        final World arena = arena();
        return arena != null && location != null && arena.equals(location.getWorld());
    }

    /** 玩家打玩家的傷害乘上這個（1 − PvP 減傷）。 */
    public double damageMultiplier() {
        return config.multiplier();
    }

    /**
     * 這一下 PvP 傷害乘多少（2026-10）：PvP 減傷，召喚物、圖騰、陷阱打的再乘 minion_damage，
     * 挨打的人拿近戰武器再少 melee_reduction %（召喚物挨打不算：defender 傳 null）。
     */
    public double damageMultiplier(final boolean indirect, final Player defender) {
        double scale = config.multiplier();
        if (indirect) {
            scale *= config.minionDamage();
        }
        if (defender != null && config.meleeReduction() > 0 && meleeWeapon(plugin.players().get(defender).weaponTags())) {
            scale *= 1 - config.meleeReduction() / 100.0;
        }
        return scale;
    }

    /** 拿的是近戰武器（劍、斧、錘、矛、長棍、爪、匕首、獸魂符）；權杖是召喚、施法用的，不算。 */
    static boolean meleeWeapon(final Set<String> weaponTags) {
        return weaponTags != null && weaponTags.contains(tw.exilecore.stats.Tags.MELEE) && !weaponTags.contains("sceptre");
    }

    /**
     * 保底（2026-10）：victim 這一次被玩家（或他的召喚物、持續傷害）打，實際扣多少。
     * 單一下最多總血量的 max_hit_percent %、最近一秒最多 max_per_second_percent %；總血量 = 最大生命＋最大能量護盾，
     * 「受到的傷害先從魔力扣」的人把那一份最大魔力也算進去（不然他們的上限會特別低）。
     *
     * @param amount 原本要扣的（護盾＋生命加起來）
     * @param hit    是擊中（false = 點燃、流血、中毒的一跳：只看每秒的上限）
     */
    public double limit(final Player victim, final double amount, final boolean hit) {
        if (amount <= 0 || (config.hitCap() <= 0 && config.secondCap() <= 0)) {
            return amount;
        }
        final PlayerState state = plugin.players().get(victim);
        if (!state.isLoaded() || !state.hasCharacter()) {
            return amount;
        }
        final double fromMana = Math.max(0, Math.min(100, plugin.players().sheet(victim)
                .flat(tw.exilecore.stats.StatKeys.DAMAGE_FROM_MANA, Set.of()))) / 100.0;
        final double pool = state.maxLife() + state.maxEnergyShield() + state.maxMana() * fromMana;
        if (pool <= 0) {
            return amount;
        }
        final long tick = plugin.getServer().getCurrentTick();
        return caps.computeIfAbsent(victim.getUniqueId(), uuid -> new PvpDamageCap())
                .allow(amount, pool * config.hitCap() / 100.0, pool * config.secondCap() / 100.0, tick, hit);
    }

    /**
     * attacker 能不能打 target：兩個人都在競技場、都活著、都有角色、不是同一個人也不同隊，target 不在入場保護中，
     * 而且都不是創造或旁觀模式。
     */
    public boolean canAttack(final Player attacker, final Player target) {
        if (!config.enabled() || attacker == null || target == null || attacker.equals(target)) {
            return false;
        }
        if (!inArena(attacker) || !inArena(target) || attacker.isDead() || target.isDead()) {
            return false;
        }
        if (!playing(attacker) || !playing(target)) {
            return false; // 觀看席上的人（playing 是 false）打不到人、也不會被打
        }
        if (plugin.party() != null && plugin.party().sameGroup(attacker.getUniqueId(), target.getUniqueId())) {
            return false;
        }
        return !plugin.players().grace().isSafe(target);
    }

    private boolean playing(final Player player) {
        final GameMode mode = player.getGameMode();
        return mode != GameMode.CREATIVE && mode != GameMode.SPECTATOR && plugin.players().get(player).hasCharacter() && !watching(player);
    }

    /** 在觀看席上（2026-10）：打不到場內的人、也不會被打，不算場上的人。 */
    public boolean watching(final Player player) {
        return inArena(player) && ArenaLayout.onStands(player.getLocation().getX(), player.getLocation().getZ());
    }

    /** 到觀看席看（不在場內的人；場內打的人要先離場）。不能去回傳原因。 */
    public String watch(final Player player) {
        if (!config.enabled()) {
            return "競技場目前沒有開放";
        }
        if (arena() == null || building || !ready) {
            return "競技場還沒準備好";
        }
        if (watching(player)) {
            return "你已經在觀看席了";
        }
        if (inArena(player)) {
            return "你在場內：先離場（/pvp leave）再去觀看席";
        }
        if (plugin.maps() != null && plugin.maps().inUber(player)) {
            return "極境裡不能直接去競技場";
        }
        final String combat = combatProblem(player);
        if (combat != null) {
            return combat;
        }
        if (fromCampaign(player)) { // 2026-10-22 主城分流：分流世界的營地也算
            returnTo.put(player.getUniqueId(), player.getLocation().clone());
        } else {
            returnTo.remove(player.getUniqueId());
        }
        final double[] spot = ArenaLayout.standSpot(plugin.items().random().nextBoolean());
        player.teleportAsync(new Location(arena(), spot[0], spot[1], spot[2], (float) spot[3], 10f));
        player.sendMessage(plugin.text().prefixed("<gray>在觀看席：打不到場內的人、也不會被打。想下場打 /pvp join，離開 /pvp leave"));
        return null;
    }

    /** 戰鬥模組：target 被 attacker 打到了（擊倒算誰的）。 */
    public void recordHit(final Player target, final Player attacker) {
        if (target != null && attacker != null && !target.equals(attacker)) {
            lastHits.put(target.getUniqueId(), new Hit(attacker.getUniqueId(), plugin.getServer().getCurrentTick()));
        }
    }

    // ---- 進出 ----

    /** 不能進場的原因；可以回傳 null。 */
    public String joinProblem(final Player player) {
        if (!config.enabled()) {
            return "競技場目前沒有開放";
        }
        if (arena() == null) {
            return "競技場的世界沒有載入（看主控台）";
        }
        if (building) {
            return "競技場正在蓋，等一下再來";
        }
        if (!ready) {
            return "競技場還沒蓋好（管理員：/ec pvp rebuild）";
        }
        final PlayerState state = plugin.players().get(player);
        if (!state.hasCharacter()) {
            return "先建立角色";
        }
        if (inArena(player) && !watching(player)) {
            return "你已經在競技場了";
        }
        if (state.level() < config.minLevel()) {
            return "要 " + config.minLevel() + " 級才能進競技場（你 " + state.level() + " 級）";
        }
        if (plugin.maps() != null && plugin.maps().inUber(player)) {
            return "極境裡不能直接去競技場（極境每個人只能進一次：打完或回營地再來）";
        }
        return null;
    }

    /**
     * 2026-10：剛受到攻擊（3 秒內）不能去競技場——進競技場會補滿生命、藥劑、清掉異常，離場又回到原地，
     * 打 Boss 打到一半可以拿來免費回滿（跟傳送到檢查點一樣擋 3 秒）。
     */
    private String combatProblem(final Player player) {
        final tw.exilecore.character.PlayerState state = plugin.players().get(player);
        if (state != null && plugin.getServer().getCurrentTick() - state.lastDamageTick() < 60) {
            return "剛受到攻擊，3 秒內不能去競技場";
        }
        return null;
    }

    /** 進場；不能進回傳原因。 */
    public String join(final Player player) {
        String problem = joinProblem(player);
        if (problem == null && !watching(player)) {
            problem = combatProblem(player);
        }
        if (problem != null) {
            return problem;
        }
        if (fromCampaign(player)) {
            returnTo.put(player.getUniqueId(), player.getLocation().clone());
        } else if (!watching(player)) {
            returnTo.remove(player.getUniqueId()); // 從觀看席下場：離場時照樣回進觀看席之前的地方
        }
        final Location spawn = spawnFor(player);
        player.teleportAsync(spawn).thenAccept(success -> {
            if (Boolean.TRUE.equals(success) && player.isOnline()) {
                plugin.players().grace().grant(player); // 入場保護：移動或攻擊才解除
            }
        });
        return null;
    }

    /** 離場：回到進場前的地方（劇情世界裡的），不然回營地。不在場內回傳原因。 */
    public String leave(final Player player) {
        if (!inArena(player)) {
            return "你不在競技場";
        }
        Location target = returnTo.remove(player.getUniqueId());
        if (target == null || target.getWorld() == null || !campaignOrTown(target.getWorld())) {
            target = plugin.zones() == null ? null : plugin.zones().campLocationFor(player); // 2026-10-22：回他那條線
        }
        if (target == null) {
            return "伺服器還沒設定營地，離不開（管理員：/ec zone autolayout）";
        }
        final Location destination = target;
        player.teleportAsync(destination).thenAccept(success -> {
            if (Boolean.TRUE.equals(success) && player.isOnline()) {
                plugin.players().grace().grant(player);
            }
        });
        return null;
    }

    /** 出生點：離其他還活著的人最遠的那一個（只有自己就隨便一個）。 */
    Location spawnFor(final Player player) {
        final World arena = arena();
        final List<Player> others = new ArrayList<>();
        for (final Player other : arena.getPlayers()) {
            if (!other.equals(player) && !other.isDead() && playing(other)) {
                others.add(other);
            }
        }
        ArenaLayout.Spawn best = null;
        double bestDistance = -1;
        final List<ArenaLayout.Spawn> spawns = new ArrayList<>(layout.spawns());
        java.util.Collections.shuffle(spawns, new java.util.Random(plugin.items().random().nextLong()));
        for (final ArenaLayout.Spawn spawn : spawns) {
            double nearest = Double.MAX_VALUE;
            for (final Player other : others) {
                nearest = Math.min(nearest, Math.hypot(other.getLocation().getX() - spawn.x() - 0.5, other.getLocation().getZ() - spawn.z() - 0.5));
            }
            if (nearest > bestDistance) {
                bestDistance = nearest;
                best = spawn;
            }
        }
        final ArenaLayout.Spawn chosen = best == null ? layout.spawns().getFirst() : best;
        return new Location(arena, chosen.x() + 0.5, ArenaLayout.FLOOR_Y + 1, chosen.z() + 0.5, chosen.yaw(), 0f);
    }

    /** 這次的戰績（沒在場內是 null）。 */
    Session session(final Player player) {
        return inArena(player) ? sessions.computeIfAbsent(player.getUniqueId(), uuid -> new Session()) : null;
    }

    /** 場內的人（活著或倒下的都算；創造、旁觀的不算）。 */
    List<Player> players() {
        final World arena = arena();
        final List<Player> list = new ArrayList<>();
        if (arena != null) {
            for (final Player player : arena.getPlayers()) {
                if (playing(player)) {
                    list.add(player);
                }
            }
        }
        return list;
    }

    /** 場內幾個人（主選單用）。 */
    public int playerCount() {
        return players().size();
    }

    /** 給玩家看的名字（角色名稱）。 */
    String name(final Player player) {
        return plugin.chat() == null ? player.getName() : plugin.chat().display(player);
    }

    // ---- 事件 ----

    @EventHandler
    public void onChangedWorld(final PlayerChangedWorldEvent event) {
        final Player player = event.getPlayer();
        final World arena = arena();
        if (arena == null) {
            return;
        }
        if (player.getWorld().equals(arena)) {
            entered(player);
        } else if (event.getFrom().equals(arena)) {
            left(player);
        }
    }

    /** 走進競技場：補滿、清異常、說明規則。 */
    private void entered(final Player player) {
        sessions.put(player.getUniqueId(), new Session());
        lastHits.remove(player.getUniqueId());
        caps.remove(player.getUniqueId());
        final PlayerState state = plugin.players().get(player);
        if (!state.hasCharacter()) {
            return;
        }
        if (plugin.ailments() != null) {
            plugin.ailments().clear(player);
        }
        state.restoreAll();
        plugin.players().display(player, state);
        if (plugin.flasks() != null) {
            plugin.flasks().refill(player);
        }
        player.showTitle(Title.title(plugin.text().parse("<#ff6b6b>⚔ 競技場"),
                plugin.text().parse("<gray>玩家可以互相攻擊・PvP 減傷 <white>" + Math.round(config.reduction()) + "%"),
                Title.Times.times(Duration.ofMillis(300), Duration.ofMillis(2500), Duration.ofMillis(700))));
        player.playSound(player.getLocation(), Sound.EVENT_RAID_HORN, 0.4f, 1.2f);
        player.sendMessage(plugin.text().prefixed("<#ff6b6b>進入競技場<gray>：同一隊的人不會互打；玩家打玩家的傷害減少 <white>"
                + Math.round(config.reduction()) + "%<gray>（專屬的 PvP 減傷）。倒下不扣經驗、不掉東西"));
        if (config.secondCap() > 0 || config.hitCap() > 0) {
            player.sendMessage(plugin.text().parse("<gray>  保底：" + (config.hitCap() > 0 ? "一下最多打掉總血量的 <white>" + Math.round(config.hitCap()) + "%</white>" : "")
                    + (config.hitCap() > 0 && config.secondCap() > 0 ? "、" : "")
                    + (config.secondCap() > 0 ? "每秒最多 <white>" + Math.round(config.secondCap()) + "%</white>" : "")
                    + (config.meleeReduction() > 0 ? "；拿近戰武器受到的傷害再少 <white>" + Math.round(config.meleeReduction()) + "%</white>" : "")));
        }
        player.sendMessage(plugin.text().parse("<gray>  掩體上有梯子可以爬上去；<white>/pvp leave</white> 或 <white>/town</white> 離開"));
        for (final Player other : players()) {
            if (!other.equals(player)) {
                other.sendMessage(plugin.text().parse("<dark_gray>⚔ <gray>" + ItemRenderer.escape(name(player)) + " 進入了競技場"));
            }
        }
    }

    /** 離開競技場：清掉場內被上的異常與減益（不會在場外被燒死），說一下這次的戰績。 */
    private void left(final Player player) {
        final Session session = sessions.remove(player.getUniqueId());
        lastHits.remove(player.getUniqueId());
        caps.remove(player.getUniqueId());
        fallen.remove(player.getUniqueId());
        if (plugin.ailments() != null) {
            plugin.ailments().clear(player);
        }
        if (plugin.combat() != null && plugin.combat().debuffs() != null) {
            plugin.combat().debuffs().forget(player.getUniqueId());
        }
        if (session != null && session.kills + session.deaths > 0) {
            player.sendMessage(plugin.text().prefixed("<gray>離開競技場：擊倒 <white>" + session.kills + "<gray>、倒下 <white>" + session.deaths
                    + "<gray>、最多連續擊倒 <white>" + session.bestStreak));
        }
    }

    /** 在競技場裡倒下：不廣播給全服、算擊倒、擊倒的人藥劑充能；之後重生在場內。 */
    @EventHandler(priority = EventPriority.HIGH)
    public void onDeath(final PlayerDeathEvent event) {
        final Player victim = event.getEntity();
        if (!inArena(victim)) {
            return;
        }
        event.deathMessage(null);
        final long now = plugin.getServer().getCurrentTick();
        final Hit hit = lastHits.remove(victim.getUniqueId());
        caps.remove(victim.getUniqueId()); // 重生之後重新算
        Player killer = hit != null && now - hit.tick() <= CREDIT_TICKS ? plugin.getServer().getPlayer(hit.attacker()) : null;
        if (killer == null && victim.getKiller() != null && !victim.getKiller().equals(victim)) {
            killer = victim.getKiller();
        }
        if (killer != null && !inArena(killer)) {
            killer = null; // 打完就離場了：不算
        }
        final Session dead = sessions.computeIfAbsent(victim.getUniqueId(), uuid -> new Session());
        dead.deaths++;
        dead.streak = 0;
        String feed;
        if (killer != null) {
            final Session winner = sessions.computeIfAbsent(killer.getUniqueId(), uuid -> new Session());
            winner.kills++;
            winner.streak++;
            winner.bestStreak = Math.max(winner.bestStreak, winner.streak);
            feed = "<#ff6b6b>⚔ <white>" + ItemRenderer.escape(name(killer)) + "<gray> 擊倒了 <white>" + ItemRenderer.escape(name(victim))
                    + (winner.streak >= 2 ? "<gray>（連續擊倒 <#f5b942>" + winner.streak + "<gray>）" : "");
            if (plugin.flasks() != null && config.flaskCharges() > 0 && plugin.flasks().eligible(killer)) {
                plugin.flasks().addCharges(killer, config.flaskCharges());
            }
            killer.playSound(killer.getLocation(), Sound.ENTITY_PLAYER_LEVELUP, 0.5f, 1.6f);
        } else {
            feed = "<#ff6b6b>⚔ <white>" + ItemRenderer.escape(name(victim)) + "<gray> 倒下了";
        }
        if (config.killFeed()) {
            for (final Player player : players()) {
                player.sendMessage(plugin.text().parse(feed));
            }
        }
        victim.sendMessage(plugin.text().prefixed("<gray>競技場裡倒下：不扣經驗、不掉東西、不算死亡次數"
                + (config.respawnInArena() ? "；重生在競技場（<white>/pvp leave</white> 離開）" : "；回營地")));
        fallen.add(victim.getUniqueId());
    }

    /** 在競技場倒下的人：重生在離其他人最遠的出生點，或回營地（respawn_in_arena）；比地圖、區域的重生都晚。 */
    @EventHandler(priority = EventPriority.HIGHEST)
    public void onRespawn(final PlayerRespawnEvent event) {
        final Player player = event.getPlayer();
        if (!fallen.remove(player.getUniqueId()) || arena() == null) {
            return;
        }
        if (config.enabled() && config.respawnInArena() && ready) {
            event.setRespawnLocation(spawnFor(player));
            return;
        }
        final Location camp = plugin.zones() == null ? null : plugin.zones().campLocationFor(player);
        if (camp != null) {
            event.setRespawnLocation(camp);
        }
    }

    /** 劇情世界或分流的營地世界（2026-10-22）。 */
    private boolean campaignOrTown(final World world) {
        final tw.exilecore.world.CampaignWorld worlds = plugin.world() == null ? null : plugin.world().worlds();
        if (worlds == null || world == null) {
            return false;
        }
        return world.equals(worlds.campaign()) || worlds.channels().isTown(world);
    }

    private boolean fromCampaign(final Player player) {
        return campaignOrTown(player.getWorld());
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onJoin(final PlayerJoinEvent event) {
        final Player player = event.getPlayer();
        if (inArena(player)) {
            sessions.put(player.getUniqueId(), new Session());
            plugin.getServer().getScheduler().runTaskLater(plugin, () -> {
                if (player.isOnline() && inArena(player)) {
                    player.sendMessage(plugin.text().prefixed("<gray>你在競技場裡（<white>/pvp leave</white> 離開）"));
                }
            }, 20);
        }
    }

    @EventHandler
    public void onQuit(final PlayerQuitEvent event) {
        final UUID uuid = event.getPlayer().getUniqueId();
        sessions.remove(uuid);
        lastHits.remove(uuid);
        caps.remove(uuid);
        fallen.remove(uuid);
    }

    // ---- 蓋場地 ----

    /** 啟動時：場地還沒蓋、或版本跟現在的不一樣，就（清掉再）蓋。 */
    void ensureBuilt() {
        final World arena = arena();
        if (arena == null || !config.enabled()) {
            return;
        }
        final int version = builtVersion(arena);
        if (version == ArenaLayout.VERSION) {
            ready = true;
            return;
        }
        plugin.getSLF4JLogger().info("競技場：{}，開始蓋（版本 {}）", version < 0 ? "還沒蓋過" : "場地版本 " + version + " 舊了", ArenaLayout.VERSION);
        build(plugin.getServer().getConsoleSender(), true); // 一律先清掉（記號檔被刪掉的舊場地也不會留下殘骸）
    }

    /** 管理指令：重蓋（先清掉再蓋）。 */
    public void rebuild(final CommandSender sender) {
        if (arena() == null) {
            sender.sendMessage(plugin.text().prefixed("<red>競技場的世界沒有載入"));
            return;
        }
        build(sender, true);
    }

    /** 蓋好的版本（世界資料夾的記號檔；沒有是 −1）。 */
    private static int builtVersion(final World world) {
        final Path marker = world.getWorldPath().resolve(MARKER);
        try {
            if (!Files.exists(marker)) {
                return -1;
            }
            final String text = Files.readString(marker).trim();
            final int space = text.indexOf(' ');
            return Integer.parseInt(space < 0 ? text : text.substring(0, space));
        } catch (final IOException | NumberFormatException e) {
            return 0;
        }
    }

    private void build(final CommandSender sender, final boolean clear) {
        if (building) {
            sender.sendMessage(plugin.text().prefixed("<gray>競技場正在蓋了"));
            return;
        }
        final World arena = arena();
        building = true;
        // 先把場地的區塊載入（非同步），再分幾個 tick 放方塊
        final int min = -ArenaLayout.CLEAR >> 4;
        final int max = ArenaLayout.CLEAR >> 4;
        final List<CompletableFuture<Chunk>> futures = new ArrayList<>();
        for (int cx = min; cx <= max; cx++) {
            for (int cz = min; cz <= max; cz++) {
                futures.add(arena.getChunkAtAsync(cx, cz));
            }
        }
        CompletableFuture.allOf(futures.toArray(new CompletableFuture<?>[0])).whenComplete((ignored, error) ->
                plugin.getServer().getScheduler().runTask(plugin, () -> {
                    if (error != null) {
                        building = false;
                        sender.sendMessage(plugin.text().prefixed("<red>載入競技場的區塊失敗：" + error.getMessage()));
                        return;
                    }
                    final List<Chunk> chunks = new ArrayList<>();
                    for (final CompletableFuture<Chunk> future : futures) {
                        final Chunk chunk = future.join();
                        chunk.addPluginChunkTicket(plugin);
                        chunks.add(chunk);
                    }
                    final BufferSink sink = new BufferSink();
                    if (clear) {
                        sink.fill(-ArenaLayout.CLEAR, ArenaLayout.CLEAR_MIN_Y, -ArenaLayout.CLEAR, ArenaLayout.CLEAR, ArenaLayout.BARRIER_TOP,
                                ArenaLayout.CLEAR, 0);
                    }
                    layout.build(sink);
                    sink.compact();
                    place(arena, sink, chunks, sender);
                }));
    }

    /** 分幾個 tick 把方塊放進世界（不觸發物理），放完寫記號檔。 */
    private void place(final World arena, final BufferSink sink, final List<Chunk> chunks, final CommandSender sender) {
        final int[] next = {0};
        buildTask = plugin.getServer().getScheduler().runTaskTimer(plugin, () -> {
            final int end = Math.min(sink.size(), next[0] + BLOCKS_PER_TICK);
            for (int i = next[0]; i < end; i++) {
                arena.getBlockAt(sink.x(i), sink.y(i), sink.z(i)).setBlockData(BlockPalette.get(sink.block(i)), false);
            }
            next[0] = end;
            if (end < sink.size()) {
                return;
            }
            buildTask.cancel();
            buildTask = null;
            for (final Chunk chunk : chunks) {
                chunk.removePluginChunkTicket(plugin);
            }
            final ArenaLayout.Spawn spawn = layout.spawns().getFirst();
            arena.setSpawnLocation(new Location(arena, spawn.x() + 0.5, ArenaLayout.FLOOR_Y + 1, spawn.z() + 0.5, spawn.yaw(), 0f));
            try {
                Files.writeString(arena.getWorldPath().resolve(MARKER), ArenaLayout.VERSION
                        + " ExileCore 競技場的場地版本（刪掉這個檔，下次啟動會清掉重蓋）\n");
            } catch (final IOException e) {
                plugin.getSLF4JLogger().warn("寫入競技場的記號檔失敗", e);
            }
            building = false;
            ready = true;
            sender.sendMessage(plugin.text().prefixed("<green>競技場蓋好了（" + sink.size() + " 格方塊）"));
            plugin.getSLF4JLogger().info("競技場蓋好了（{} 格方塊）", sink.size());
        }, 1, 1);
    }

    /** 模組關閉：停掉還在蓋的工作。 */
    void shutdown() {
        if (buildTask != null) {
            buildTask.cancel();
            buildTask = null;
        }
        building = false;
    }
}
