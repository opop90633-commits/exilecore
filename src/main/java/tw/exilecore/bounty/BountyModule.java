package tw.exilecore.bounty;

import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.scheduler.BukkitTask;
import tw.exilecore.ExileCore;
import tw.exilecore.map.MapConfig;
import tw.exilecore.module.Module;
import tw.exilecore.zone.Zone;

import java.util.ArrayList;
import java.util.List;

/**
 * bounty 模組：每日委託、每週回響、功勳與功勳兌換（data/bounties.yml）。帳號共用。
 * 要在任務模組之後啟用（委託顯示在任務追蹤上、委託板在劇情人物軍需官身上）。
 */
public final class BountyModule implements Module, Listener {

    /** 多久檢查一次換日（tick）。 */
    private static final long DAY_TICKS = 20 * 20;
    /** 還沒寫的計數多久寫一次（tick）。 */
    private static final long FLUSH_TICKS = 30 * 20;

    private final ExileCore plugin;
    private final BountyConfig config = new BountyConfig();
    private BountyService service;
    private BukkitTask dayTask;
    private BukkitTask flushTask;
    private BukkitTask repelTask;

    public BountyModule(final ExileCore plugin) {
        this.plugin = plugin;
    }

    @Override
    public String id() {
        return "bounty";
    }

    @Override
    public void enable() throws Exception {
        load();
        service = new BountyService(plugin, config);
        plugin.getServer().getPluginManager().registerEvents(this, plugin);
        // 存檔（自動存檔、換角色、下線）之前：還沒寫的計數先寫
        plugin.characters().addBeforeSave(player -> {
            if (service != null) {
                service.flush(player);
            }
        });
        if (plugin.quests() != null) {
            plugin.quests().addTrackerSection(player -> plugin.bounties() == null ? List.of() : plugin.bounties().trackerLines(player));
        }
        // 稱號的永久加成（擁有就有，整個帳號）
        plugin.players().addModifierSource(player -> service == null ? List.of() : service.titleModifiers(player));
        dayTask = plugin.repeat("bounty.day", service::tick, DAY_TICKS, DAY_TICKS);
        flushTask = plugin.repeat("bounty.flush", service::flushAll, FLUSH_TICKS, FLUSH_TICKS);
        repelTask = plugin.repeat("bounty.builder", service::repelForBuilders, 10, 10); // 2026-10-20 建築師稱號
        // 已經在線上的玩家（/reload）
        for (final Player player : plugin.getServer().getOnlinePlayers()) {
            service.load(player);
        }
        plugin.getSLF4JLogger().info("委託：每天 {} 個（{} 種）、每週回響 {}、功勳兌換 {}", config.dailyCount(), config.templates().size(),
                config.weekly().enabled() ? "開" : "關", config.shop().enabled() ? "開" : "關");
    }

    @Override
    public void reload() throws Exception {
        load();
        if (service != null) {
            service.reloaded();
        }
    }

    @Override
    public void disable() {
        for (final BukkitTask task : new BukkitTask[] {dayTask, flushTask, repelTask}) {
            if (task != null) {
                task.cancel();
            }
        }
        dayTask = null;
        flushTask = null;
        repelTask = null;
        if (service != null) {
            service.flushAll();
        }
    }

    /** 讀 bounties.yml 並檢查；有問題就不換（啟用時直接失敗）。 */
    private void load() throws Exception {
        final BountyConfig fresh = new BountyConfig();
        fresh.load(plugin.dataFolder().resolve("data"));
        // 區域、怪物模組沒啟用時不檢查回響的區域與詞綴（回響開不了，每日委託與兌換照常）
        final List<String> bossZones = new ArrayList<>(fresh.weekly().themes().keySet());
        if (plugin.zones() != null) {
            bossZones.clear();
            for (final Zone zone : plugin.zones().zones()) {
                if (zone.boss() != null) {
                    bossZones.add(zone.id());
                }
            }
        }
        final List<String> mods = new ArrayList<>(fresh.weekly().mods());
        if (plugin.monsterModule() != null && plugin.monsterModule().service() != null) {
            mods.clear();
            for (final tw.exilecore.monster.MonsterConfig.MonsterMod mod : plugin.monsters().config().mods()) {
                mods.add(mod.id());
            }
        }
        final List<String> problems = fresh.validate(bossZones, mods, MapConfig.THEMES);
        if (!problems.isEmpty()) {
            throw new IllegalArgumentException("bounties.yml 有 " + problems.size() + " 個問題：\n  " + String.join("\n  ", problems));
        }
        config.copyFrom(fresh);
    }

    /** 執行期；模組沒啟用時為 null。 */
    public BountyService service() {
        return service;
    }

    @EventHandler
    public void onJoin(final PlayerJoinEvent event) {
        if (service != null) {
            service.load(event.getPlayer());
        }
    }

    /** 下線：還沒寫的計數寫進去（角色模組的登出存檔之前或之後都可以：分開寫的）。 */
    @EventHandler(priority = EventPriority.MONITOR)
    public void onQuit(final PlayerQuitEvent event) {
        if (service != null) {
            service.unload(event.getPlayer());
        }
    }
}
