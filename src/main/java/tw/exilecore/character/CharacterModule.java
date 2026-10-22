package tw.exilecore.character;

import org.bukkit.entity.Player;
import org.bukkit.scheduler.BukkitTask;
import tw.exilecore.ExileCore;
import tw.exilecore.gui.ChatPrompts;
import tw.exilecore.gui.MenuListener;
import tw.exilecore.module.Module;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * character 模組：玩家狀態、職業與經驗、多角色與持久化、回復排程、角色面板、原版資源系統的接管。
 */
public final class CharacterModule implements Module {

    /** 2026-10：關服時收好合成格的東西用。 */
    private CharacterListener listener;

    /** 每幾 tick 跑一次回復與顯示。 */
    private static final int TICK_PERIOD = 10;
    /** 自動存檔間隔（tick）：5 分鐘。 */

    private final ExileCore plugin;
    private final CharacterConfig config;
    private final PlayerStateService states;
    private final CharacterService characters;
    private final ChatPrompts chatPrompts;
    private BukkitTask tickTask;
    /** 每人上次回復出錯記在哪個 tick（同一個人 10 秒只記一次，不洗版）。 */
    private final Map<UUID, Long> tickErrors = new java.util.HashMap<>();
    private BukkitTask autosaveTask;
    private BukkitTask sidebarTask;
    private BukkitTask gearTask;
    private BukkitTask slotHealTask;
    private BukkitTask cooldownTask;

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
    private OnlineBonus onlineBonus;
    private org.bukkit.scheduler.BukkitTask bonusTask;

    @Override
    public void enable() throws Exception {
        final Path data = plugin.dataFolder().resolve("data");
        config.load(data);
        listener = new CharacterListener(plugin, states, characters);
        plugin.getServer().getPluginManager().registerEvents(listener, plugin);
        plugin.getServer().getPluginManager().registerEvents(new GearListener(plugin, characters.gear()), plugin); // 角色面板的裝備欄
        plugin.getServer().getPluginManager().registerEvents(states.grace(), plugin); // 入場保護
        plugin.getServer().getPluginManager().registerEvents(new MenuListener(), plugin);
        plugin.getServer().getPluginManager().registerEvents(plugin.personalDrops(), plugin); // 地上有主人的東西
        plugin.getServer().getPluginManager().registerEvents(chatPrompts, plugin);
        plugin.getServer().getPluginManager().registerEvents(plugin.sidebar(), plugin); // 右邊側欄（任務追蹤、最近撿到）
        bonusBar = new ServerBonusBar(plugin);
        onlineBonus = new OnlineBonus(plugin); // 2026-10：在線人數加成（config.yml 的 online_bonus）
        plugin.getServer().getPluginManager().registerEvents(onlineBonus, plugin);
        bonusBar.online(onlineBonus);
        final TabList tabList = new TabList(plugin);
        bonusTask = plugin.repeat("bonus_bar", () -> {
            onlineBonus.tick(); // 在線人數加成：數人、換階
            bonusBar.tick(); // 全服加成的公告（畫面最上面的金色血條）
            tabList.tick(); // TAB 列表的伺服器名稱、在線人數、PING、TPS
        }, 40, 40);
        sidebarTask = plugin.repeat("sidebar", () -> {
            plugin.sidebar().tick();
            tabList.refresh(); // 2026-10-20：TAB 下面的增益倒數（每半秒）
        }, tw.exilecore.gui.Sidebar.TICK_PERIOD, tw.exilecore.gui.Sidebar.TICK_PERIOD);
        tickTask = plugin.repeat("character", () -> {
            final long currentTick = plugin.getServer().getCurrentTick();
            for (final Map.Entry<UUID, PlayerState> entry : states.all().entrySet()) {
                final Player player = plugin.getServer().getPlayer(entry.getKey());
                if (player != null && player.isOnline()) {
                    try {
                        states.tick(player, entry.getValue(), currentTick, TICK_PERIOD);
                    } catch (final RuntimeException e) {
                        // 2026-10：一個人的屬性表算不出來（例如身上有壞掉的物品）只跳過他——
                        // 以前這裡一丟錯，排在他後面的人這一輪的生命、魔力、護盾回復全都不跑
                        final Long last = tickErrors.get(entry.getKey());
                        if (last == null || currentTick - last >= 200) {
                            tickErrors.put(entry.getKey(), currentTick);
                            plugin.getSLF4JLogger().error("{} 的回復與屬性更新出錯", player.getName(), e);
                        }
                    }
                }
            }
        }, TICK_PERIOD, TICK_PERIOD);
        // 裝備欄的顯示用格子：每秒對一次（別的插件、原版行為塞進去的東西收進裝備欄或放回背包）。
        // 2026-10-22 效能：以前每秒在同一個 tick 把所有人做完（spark：100 人時每秒一個 15 毫秒的尖峰），現在每 tick 做 1/20 的人
        final tw.exilecore.core.Staggered gearRound = new tw.exilecore.core.Staggered(20);
        gearTask = plugin.repeat("gear", () -> {
            for (final UUID id : gearRound.next(onlineIds())) {
                final Player player = plugin.getServer().getPlayer(id);
                if (player != null && player.isOnline() && characters.gear().sync(player)) {
                    states.sheet(player);
                }
            }
        }, 20L, 1L);
        // 2026-10 效能：原版比對背包「內容一樣但被重寫過」的格子每 tick 都要整段重比（spark 兩成）：換成同一份。
        // 2026-10-22：每人從每秒一次改成每 5 tick 一次（攤在每個 tick 做）——被重寫的格子越早換掉，原版白比的 tick 越少（spark 還有 3.7%）
        final tw.exilecore.core.SlotSyncHealer healer = new tw.exilecore.core.SlotSyncHealer(plugin.getSLF4JLogger());
        final tw.exilecore.core.Staggered healRound = new tw.exilecore.core.Staggered(5);
        slotHealTask = plugin.repeat("slot_heal", () -> {
            final List<Player> batch = new ArrayList<>();
            for (final UUID id : healRound.next(onlineIds())) {
                final Player player = plugin.getServer().getPlayer(id);
                if (player != null && player.isOnline()) {
                    batch.add(player);
                }
            }
            healer.tick(batch);
        }, 30L, 1L);
        // 2026-10：快捷列技能的冷卻倒數（圖示數量＝剩幾秒）
        cooldownTask = plugin.repeat("skill_cooldowns", () -> {
            for (final Player player : plugin.getServer().getOnlinePlayers()) {
                characters.gear().cooldowns(player);
            }
        }, 4L, 4L);
        autosaveTask = plugin.repeat("autosave", characters::saveSlice, 200L, 10L); // 2026-10-20：攤開存（CharacterService.AUTOSAVE_SLOTS × 10 tick = 5 分鐘一輪）
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
        if (onlineBonus != null) {
            onlineBonus.readConfig();
        }
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
        states.hideResourceBars(); // 2026-10-18：戰鬥資源血條也收掉
        if (tickTask != null) {
            tickTask.cancel();
            tickTask = null;
        }
        if (autosaveTask != null) {
            autosaveTask.cancel();
            autosaveTask = null;
        }
        if (slotHealTask != null) {
            slotHealTask.cancel();
            slotHealTask = null;
        }
        if (gearTask != null) {
            gearTask.cancel();
            gearTask = null;
        }
        if (cooldownTask != null) {
            cooldownTask.cancel();
            cooldownTask = null;
        }
        if (sidebarTask != null) {
            sidebarTask.cancel();
            sidebarTask = null;
        }
        plugin.sidebar().clearAll();
        // 關伺服器時插件先停用、玩家才被踢：這裡就是最後一次存檔。
        // 先關掉視窗讓游標上的物品回到背包，再排進資料庫執行緒；core 模組關閉資料庫前會等它做完。
        for (final Player player : plugin.getServer().getOnlinePlayers()) {
            if (listener != null) {
                listener.settle(player); // 2026-10：2×2 合成格、游標上的東西（關服時收不到下線事件）
            }
            player.closeInventory();
        }
        characters.saveAll();
    }

    /** 在線玩家的 UUID（給攤開做的工作排隊用）。 */
    private List<UUID> onlineIds() {
        final List<UUID> ids = new ArrayList<>();
        for (final Player player : plugin.getServer().getOnlinePlayers()) {
            ids.add(player.getUniqueId());
        }
        return ids;
    }
}
