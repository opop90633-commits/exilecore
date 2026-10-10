package tw.exilecore.character;

import org.bukkit.entity.Player;
import org.bukkit.scheduler.BukkitTask;
import tw.exilecore.ExileCore;
import tw.exilecore.gui.ChatPrompts;
import tw.exilecore.gui.MenuListener;
import tw.exilecore.module.Module;

import java.nio.file.Path;
import java.util.Map;
import java.util.UUID;

/**
 * character 模組：玩家狀態、職業與經驗、多角色與持久化、回復排程、角色面板、原版資源系統的接管。
 */
public final class CharacterModule implements Module {

    /** 每幾 tick 跑一次回復與顯示。 */
    private static final int TICK_PERIOD = 10;
    /** 自動存檔間隔（tick）：5 分鐘。 */
    private static final long AUTOSAVE_PERIOD = 20L * 60 * 5;

    private final ExileCore plugin;
    private final CharacterConfig config;
    private final PlayerStateService states;
    private final CharacterService characters;
    private final ChatPrompts chatPrompts;
    private BukkitTask tickTask;
    private BukkitTask autosaveTask;
    private BukkitTask sidebarTask;
    private BukkitTask gearTask;

    public CharacterModule(final ExileCore plugin, final CharacterConfig config, final PlayerStateService states,
                           final CharacterService characters, final ChatPrompts chatPrompts) {
        this.plugin = plugin;
        this.config = config;
        this.states = states;
        this.characters = characters;
        this.chatPrompts = chatPrompts;
    }

    @Override
    public String id() {
        return "character";
    }

    private ServerBonusBar bonusBar;
    private org.bukkit.scheduler.BukkitTask bonusTask;

    @Override
    public void enable() throws Exception {
        final Path data = plugin.dataFolder().resolve("data");
        config.load(data);
        plugin.getServer().getPluginManager().registerEvents(new CharacterListener(plugin, states, characters), plugin);
        plugin.getServer().getPluginManager().registerEvents(new GearListener(plugin, characters.gear()), plugin); // 角色面板的裝備欄
        plugin.getServer().getPluginManager().registerEvents(states.grace(), plugin); // 入場保護
        plugin.getServer().getPluginManager().registerEvents(new MenuListener(), plugin);
        plugin.getServer().getPluginManager().registerEvents(plugin.personalDrops(), plugin); // 地上有主人的東西
        plugin.getServer().getPluginManager().registerEvents(chatPrompts, plugin);
        plugin.getServer().getPluginManager().registerEvents(plugin.sidebar(), plugin); // 右邊側欄（任務追蹤、最近撿到）
        bonusBar = new ServerBonusBar(plugin);
        final TabList tabList = new TabList(plugin);
        bonusTask = plugin.repeat("bonus_bar", () -> {
            bonusBar.tick(); // 全服加成的公告（畫面最上面的金色血條）
            tabList.tick(); // TAB 列表的伺服器名稱、在線人數、PING、TPS
        }, 40, 40);
        sidebarTask = plugin.repeat("sidebar", plugin.sidebar()::tick, tw.exilecore.gui.Sidebar.TICK_PERIOD, tw.exilecore.gui.Sidebar.TICK_PERIOD);
        tickTask = plugin.repeat("character", () -> {
            final long currentTick = plugin.getServer().getCurrentTick();
            for (final Map.Entry<UUID, PlayerState> entry : states.all().entrySet()) {
                final Player player = plugin.getServer().getPlayer(entry.getKey());
                if (player != null && player.isOnline()) {
                    states.tick(player, entry.getValue(), currentTick, TICK_PERIOD);
                }
            }
        }, TICK_PERIOD, TICK_PERIOD);
        // 裝備欄的顯示用格子：每秒對一次（別的插件、原版行為塞進去的東西收進裝備欄或放回背包）
        gearTask = plugin.repeat("gear", () -> {
            for (final Player player : plugin.getServer().getOnlinePlayers()) {
                if (characters.gear().sync(player)) {
                    states.sheet(player);
                }
            }
        }, 20L, 20L);
        autosaveTask = plugin.getServer().getScheduler().runTaskTimer(plugin, characters::saveAll, AUTOSAVE_PERIOD, AUTOSAVE_PERIOD);
        // 插件重載時已經在線的玩家：像剛登入一樣載入
        for (final Player player : plugin.getServer().getOnlinePlayers()) {
            states.get(player);
            characters.onJoin(player);
        }
        plugin.getSLF4JLogger().info("角色設定：等級上限 {}，每帳號 {} 個角色", config.levelCap(), config.characterSlots());
        warnStarterProblems();
    }

    @Override
    public void reload() throws Exception {
        config.load(plugin.dataFolder().resolve("data"));
        warnStarterProblems();
        for (final Player player : plugin.getServer().getOnlinePlayers()) {
            states.markDirty(player);
        }
    }

    /** 新手裝備設定裡對不到的基底（打錯字、職業拿不動）。 */
    private void warnStarterProblems() {
        if (plugin.items() == null) {
            return;
        }
        for (final String problem : StarterKits.problems(plugin.items().repository(), config)) {
            plugin.getSLF4JLogger().warn(problem);
        }
    }

    @Override
    public void disable() {
        if (bonusTask != null) {
            bonusTask.cancel();
            bonusTask = null;
        }
        if (bonusBar != null) {
            bonusBar.clear();
        }
        if (tickTask != null) {
            tickTask.cancel();
            tickTask = null;
        }
        if (autosaveTask != null) {
            autosaveTask.cancel();
            autosaveTask = null;
        }
        if (gearTask != null) {
            gearTask.cancel();
            gearTask = null;
        }
        if (sidebarTask != null) {
            sidebarTask.cancel();
            sidebarTask = null;
        }
        plugin.sidebar().clearAll();
        // 關伺服器時插件先停用、玩家才被踢：這裡就是最後一次存檔。
        // 先關掉視窗讓游標上的物品回到背包，再排進資料庫執行緒；core 模組關閉資料庫前會等它做完。
        for (final Player player : plugin.getServer().getOnlinePlayers()) {
            player.closeInventory();
        }
        characters.saveAll();
    }
}
