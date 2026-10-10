package tw.exilecore.quest;

import org.bukkit.World;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.scheduler.BukkitTask;
import tw.exilecore.ExileCore;
import tw.exilecore.module.Module;
import tw.exilecore.npc.NpcType;
import tw.exilecore.zone.Zone;

import java.util.ArrayList;
import java.util.List;

/**
 * quest 模組：劇情與任務（data/quests.yml）——主線、支線、劇情人物、對話視窗、任務追蹤、任務日誌、永久獎勵。
 * 要在區域、NPC、地圖、昇華模組之後啟用（任務的目標用到它們）。
 */
public final class QuestModule implements Module, Listener {

    /** NPC 頭上的光點多久更新一次（tick）。 */
    private static final long MARKER_TICKS = 20;
    /** 還沒寫進資料庫的任務計數多久寫一次（tick）。 */
    private static final long FLUSH_TICKS = 30 * 20;

    private final ExileCore plugin;
    private final QuestConfig config = new QuestConfig();
    private QuestService service;
    private StoryNpcs storyNpcs;
    private BukkitTask markerTask;
    private BukkitTask flushTask;

    public QuestModule(final ExileCore plugin) {
        this.plugin = plugin;
    }

    @Override
    public String id() {
        return "quest";
    }

    @Override
    public void enable() throws Exception {
        load();
        service = new QuestService(plugin, config);
        plugin.getServer().getPluginManager().registerEvents(this, plugin);
        // 永久獎勵：屬性與天賦點從完成的任務算出來
        plugin.players().addStateModifierSource((player, state) -> service == null ? List.of() : service.modifiers(state));
        plugin.players().addPointSource(state -> service == null ? 0 : service.passivePoints(state));
        // 任務追蹤：昇華的提醒（可以昇華了、昇華點還沒配）接在任務後面、每日委託前面
        service.addTrackerSection(player -> plugin.ascendancy() == null ? List.of() : plugin.ascendancy().trackerLines(player));
        // 存檔（自動存檔、換角色、下線）之前：還沒寫的任務計數先寫進去
        plugin.characters().addBeforeSave(player -> {
            if (service != null) {
                service.flush(player);
            }
        });
        markerTask = plugin.repeat("quest.markers", service::tickMarkers, MARKER_TICKS, MARKER_TICKS);
        flushTask = plugin.repeat("quest.flush", service::flushAll, FLUSH_TICKS, FLUSH_TICKS);
        final World campaign = plugin.world() == null || plugin.world().worlds() == null ? null : plugin.world().worlds().campaign();
        if (campaign != null) {
            storyNpcs = new StoryNpcs(plugin, service, campaign);
            service.storyNpcs(storyNpcs);
            plugin.getServer().getPluginManager().registerEvents(storyNpcs, plugin);
            storyNpcs.applyLoaded();
        }
        // 已經在線上的玩家（/reload）
        for (final Player player : plugin.getServer().getOnlinePlayers()) {
            service.onCharacterApplied(player);
        }
        plugin.getSLF4JLogger().info("任務：{} 個（主線 {}、支線 {}），劇情人物 {} 個", config.quests().size(),
                config.quests(QuestConfig.Kind.MAIN).size(), config.quests(QuestConfig.Kind.SIDE).size(), config.npcs().size());
    }

    @Override
    public void reload() throws Exception {
        load();
        if (storyNpcs != null) {
            storyNpcs.applyLoaded();
        }
        for (final Player player : plugin.getServer().getOnlinePlayers()) {
            plugin.players().markDirty(player);
            service.onCharacterApplied(player);
        }
    }

    @Override
    public void disable() {
        for (final BukkitTask task : new BukkitTask[] {markerTask, flushTask}) {
            if (task != null) {
                task.cancel();
            }
        }
        markerTask = null;
        flushTask = null;
        if (service != null) {
            service.flushAll();
            service.hideAll();
        }
    }

    /** 讀 quests.yml 並檢查；有問題就不換（啟用時直接失敗）。 */
    private void load() throws Exception {
        final QuestConfig fresh = new QuestConfig();
        fresh.load(plugin.dataFolder().resolve("data"));
        final List<String> zones = new ArrayList<>();
        final List<String> bossZones = new ArrayList<>();
        if (plugin.zones() != null) {
            for (final Zone zone : plugin.zones().zones()) {
                zones.add(zone.id());
                if (zone.boss() != null) {
                    bossZones.add(zone.id());
                }
            }
        }
        final List<String> functional = new ArrayList<>();
        for (final NpcType type : NpcType.values()) {
            if (type.functional()) {
                functional.add(type.id());
            }
        }
        // 昇華模組沒啟用時不檢查「完成試煉」的試煉代號（那一步永遠不會完成，其他任務照常）
        List<String> trials = null;
        if (plugin.ascendancy() != null) {
            trials = new ArrayList<>();
            for (final tw.exilecore.ascendancy.AscendancyConfig.Trial trial : plugin.ascendancy().config().trials()) {
                trials.add(trial.id());
            }
        }
        final List<String> problems = fresh.validate(zones, bossZones, functional, plugin.players().config().levelCap(), trials);
        if (!problems.isEmpty()) {
            throw new IllegalArgumentException("quests.yml 有 " + problems.size() + " 個問題：\n  " + String.join("\n  ", problems));
        }
        config.copyFrom(fresh);
    }

    /** 任務的執行期；模組沒啟用時為 null。 */
    public QuestService service() {
        return service;
    }

    /** 下線：先把還沒寫的任務計數寫進去（在角色模組存檔、清掉狀態之前）。 */
    @EventHandler(priority = org.bukkit.event.EventPriority.LOWEST)
    public void onQuit(final PlayerQuitEvent event) {
        if (service != null) {
            service.forget(event.getPlayer());
        }
    }
}
