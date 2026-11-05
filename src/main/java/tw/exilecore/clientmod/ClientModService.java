package tw.exilecore.clientmod;

import org.bukkit.entity.Entity;
import org.bukkit.entity.LivingEntity;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.HandlerList;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerChangedWorldEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.event.player.PlayerRegisterChannelEvent;
import org.bukkit.event.player.PlayerUnregisterChannelEvent;
import org.bukkit.plugin.messaging.PluginMessageListener;
import org.bukkit.scheduler.BukkitTask;
import tw.exilecore.ExileCore;
import tw.exilecore.character.HotbarLayout;
import tw.exilecore.character.PlayerState;
import tw.exilecore.character.SkillLoadout;
import tw.exilecore.module.Module;
import tw.exilecore.monster.MonsterData;
import tw.exilecore.skill.Fx;
import tw.exilecore.skill.SkillArt;
import tw.exilecore.skill.SkillDefinition;
import tw.exilecore.skill.SkillService;
import tw.exilecore.stats.HitEvent;
import tw.exilecore.web.WebServer;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 2026-11-02 clientmod 模組：選用的客戶端模組「星骸輔助模組」（Fabric，client-mod/）的伺服器這一邊。
 * <ul>
 *   <li>exilecore:hello：裝了模組的玩家進來時打招呼，記下他有裝（離線清掉），回最新的模組版本與更新說明檔的網址。</li>
 *   <li>exilecore:key：模組的技能鍵按下／放開，走跟滑鼠、數字鍵一樣的施放（{@link SkillService#pressBind}）。</li>
 *   <li>exilecore:hud：只送給有裝模組的人：技能格（冷卻、次數、魔力、放不了的原因）、增益倒數、目標框、自己的生命／護盾／魔力。
 *       有變才送，最快每 {@code client-mod.hud-interval} tick 一次。</li>
 *   <li>網頁：/mod/manifest.json 與 /mod/檔名.jar（plugins/ExileCore/clientmod/ 裡最新的 jar；管理員丟新的 jar 進去就好）。</li>
 *   <li>exilecore:fx（2026-11-02，模組 1.1／協定 2）：招呼說 effects:true 的模組改由模組畫技能／世界特效（{@link FxRouter} 接在
 *       {@link Fx#setRouter}）；每個人每 tick 一個封包，tick 結束時送。1.0 的模組、沒裝模組的人照常收粒子。
 *       {@code client-mod.effects: false} 關掉、{@code client-mod.fx-max-bytes} 每人每 tick 最多幾位元組（超過的改回送粒子）。</li>
 *   <li>2026-11-02 照站長（模組 1.4 B 組，{@link WorldHudProtocol}）：招呼說 mapbox／dmgnum／mobbars 的模組另外收
 *       exilecore:map（地圖資訊框，每 {@link #MAP_INTERVAL} tick 看一次、有變才送）、傷害數字（exilecore:fx 的 NUMBER）、
 *       exilecore:mobs（怪物頭上的血條，{@link MobBarFeed}）；模組送 exilecore:aim（準心指著誰），目標框照它優先。
 *       config：{@code client-mod.map-panel}、{@code client-mod.damage-numbers}、{@code client-mod.mob-bars}（預設都開）、
 *       {@code client-mod.mob-bars-interval}（5～10 tick，預設 6）、{@code client-mod.mob-bars-range}（預設 28 格）、
 *       {@code client-mod.mob-bars-max}（預設 40 隻）。</li>
 * </ul>
 * <ul>
 *   <li>2026-11-02 照站長（模組 1.4，協定不升，招呼的功能旗標）：notice → 提示（{@link PlayerState#notice}、{@link tw.exilecore.gui.ActionBar#send}）
 *       改送 {@value ClientModProtocol#NOTICE}，模組疊成提示列，動作列不再被提示蓋掉；panels → 側欄（{@link tw.exilecore.gui.Sidebar}）
 *       改送 {@value ClientModProtocol#PANELS}，不建記分板；res → 戰鬥資源寫在 hud 的 res，不掛 Boss 血條（{@link tw.exilecore.character.ResourceBar}）；
 *       status（有球時）→ 自己的異常、詛咒、守護、入場保護寫在 hud 的 vit，動作列不寫。</li>
 * </ul>
 * 沒裝模組的玩家完全照舊（動作列、愛心照原本的）。
 */
public final class ClientModService implements Module, Listener, PluginMessageListener {

    /** 目標框：最後打到的怪物多久之內還算（tick）。 */
    static final long LAST_HIT_TICKS = 20L * 8;
    /** 目標框：準心看得到幾格內的怪物。 */
    static final int LOOK_RANGE = 20;
    /** 最後打到的怪物離開幾格就不算。 */
    static final double LAST_HIT_RANGE = 32;
    /** 2026-11-02：地圖資訊框多久看一次有沒有變（tick）。 */
    static final int MAP_INTERVAL = 10;

    /** 一個裝了模組的玩家。 */
    private static final class Client {
        final ClientModProtocol.Hello hello;
        /** 講好的協定版本。 */
        int protocol = 1;
        final HeldKeys held = new HeldKeys(SkillLoadout.BIND_SLOTS, SkillLoadout.KEYS);
        final BuffTotals totals = new BuffTotals();
        String lastSignature;
        long lastSent = Long.MIN_VALUE / 2;
        UUID lastHit;
        long lastHitTick;
        /** 2026-11-02（1.4）：送過的最後一則提示（PlayerState.noticeSerial）。 */
        long noticeSerial = -1;
        /** 2026-11-02（1.4）：上次送的面板（一樣就不重送）。 */
        String lastPanels;
        /** 2026-11-02（模組 1.4 B 組）：招呼裡的 mapbox／dmgnum／mobbars。 */
        WorldHudProtocol.Features features = WorldHudProtocol.Features.NONE;
        /** 模組說準心指著的實體 id（-1 = 沒有；null = 這個模組不送，照舊用伺服器量的視線）。 */
        Integer aim;
        final MobBarFeed.Tracker mobs = new MobBarFeed.Tracker();
        /** 上次送的地圖資訊框（null = 沒送過或送過清除）。 */
        String lastMap;

        Client(final ClientModProtocol.Hello hello) {
            this.hello = hello;
        }
    }

    private final ExileCore plugin;
    private final Map<UUID, Client> clients = new ConcurrentHashMap<>();
    private BukkitTask task;
    private BukkitTask releaseTask;
    private boolean enabled;
    private int hudInterval = 3;
    private String publicUrl = "";
    private String changelog = "";
    private volatile ModRelease release;
    /** 網頁伺服器送 jar 用的快取（檔名、修改時間、內容）。 */
    private volatile Object[] jarCache;
    private boolean registered;
    /** 特效改由模組畫（config client-mod.effects）。 */
    private boolean effects = true;
    private final FxRouter fx = new FxRouter();
    /** 2026-11-02：模組 1.4 B 組的伺服器開關與血條的範圍。 */
    private boolean mapPanel = true;
    private boolean damageNumbers = true;
    private boolean mobBars = true;
    private int mobInterval = MobBarFeed.DEFAULT_INTERVAL;
    private double mobRange = MobBarFeed.DEFAULT_RANGE;
    private int mobMax = MobBarFeed.DEFAULT_MAX;
    /** 2026-11-02 照站長（D 組）：天賦樹、星圖、星圖天賦、昇華天賦的原生畫面（{@link UiFeed}）；這次之前只有箱子選單。 */
    private UiFeed ui;
    /** 2026-11-02（C 組）：模組畫的預警、小地圖、死亡回顧。 */
    private final ClientFeeds feeds;

    public ClientModService(final ExileCore plugin) {
        this.plugin = plugin;
        this.feeds = new ClientFeeds(plugin);
    }

    /** 2026-11-02（C 組）：預警、小地圖、死亡回顧（{@link ClientFeeds}）。 */
    public ClientFeeds feeds() {
        return feeds;
    }

    @Override
    public String id() {
        return "clientmod";
    }

    @Override
    public void enable() throws Exception {
        readConfig();
        Files.createDirectories(folder());
        refreshRelease();
        final var messenger = plugin.getServer().getMessenger();
        messenger.registerIncomingPluginChannel(plugin, ClientModProtocol.HELLO, this);
        messenger.registerIncomingPluginChannel(plugin, ClientModProtocol.KEY, this);
        messenger.registerOutgoingPluginChannel(plugin, ClientModProtocol.HELLO);
        messenger.registerOutgoingPluginChannel(plugin, ClientModProtocol.HUD);
        messenger.registerOutgoingPluginChannel(plugin, ClientModProtocol.FX);
        messenger.registerOutgoingPluginChannel(plugin, ClientModProtocol.NOTICE);
        messenger.registerOutgoingPluginChannel(plugin, ClientModProtocol.PANELS);
        messenger.registerIncomingPluginChannel(plugin, WorldHudProtocol.AIM, this);
        messenger.registerOutgoingPluginChannel(plugin, WorldHudProtocol.MAP);
        messenger.registerOutgoingPluginChannel(plugin, WorldHudProtocol.MOBS);
        feeds.enable(messenger); // 2026-11-02（C 組）
        registered = true;
        ui = new UiFeed(plugin); // 2026-11-02 照站長（D 組）：原生畫面
        ui.enable();
        Fx.setRouter((viewer, effect) -> enabled && fx.route(viewer.getUniqueId(), effect));
        plugin.getServer().getPluginManager().registerEvents(this, plugin);
        task = plugin.getServer().getScheduler().runTaskTimer(plugin, this::tick, 1L, 1L);
        // 模組檔每 10 秒看一次有沒有換（在別的執行緒算雜湊，不卡主執行緒）
        releaseTask = plugin.getServer().getScheduler().runTaskTimerAsynchronously(plugin, this::refreshRelease, 200L, 200L);
        if (plugin.web() != null) {
            plugin.web().extend(this::install);
        }
        final ModRelease current = release;
        plugin.getSLF4JLogger().info("星骸輔助模組：{}（plugins/ExileCore/clientmod/）", current == null
                ? "資料夾裡沒有模組檔，玩家不會收到自動更新" : "最新版 " + current.version() + "（" + current.fileName() + "）");
    }

    @Override
    public void reload() {
        MobBarFeed.clearCache(); // 2026-11-02：monsters.yml 可能改了
        readConfig();
        refreshRelease();
        for (final Map.Entry<UUID, Client> entry : clients.entrySet()) {
            final Player player = plugin.getServer().getPlayer(entry.getKey());
            if (player != null) {
                updateEffects(player, entry.getValue()); // 改了 client-mod.effects：下一個特效開始照新的
            }
        }
    }

    @Override
    public void disable() {
        if (task != null) {
            task.cancel();
            task = null;
        }
        if (releaseTask != null) {
            releaseTask.cancel();
            releaseTask = null;
        }
        Fx.setRouter(null);
        fx.clear();
        if (ui != null) {
            ui.disable(); // 2026-11-02（D 組）
            ui = null;
        }
        if (registered) {
            final var messenger = plugin.getServer().getMessenger();
            messenger.unregisterIncomingPluginChannel(plugin, ClientModProtocol.HELLO, this);
            messenger.unregisterIncomingPluginChannel(plugin, ClientModProtocol.KEY, this);
            messenger.unregisterOutgoingPluginChannel(plugin, ClientModProtocol.HELLO);
            messenger.unregisterOutgoingPluginChannel(plugin, ClientModProtocol.HUD);
            messenger.unregisterOutgoingPluginChannel(plugin, ClientModProtocol.FX);
            messenger.unregisterOutgoingPluginChannel(plugin, ClientModProtocol.NOTICE);
            messenger.unregisterOutgoingPluginChannel(plugin, ClientModProtocol.PANELS);
            messenger.unregisterIncomingPluginChannel(plugin, WorldHudProtocol.AIM, this);
            messenger.unregisterOutgoingPluginChannel(plugin, WorldHudProtocol.MAP);
            messenger.unregisterOutgoingPluginChannel(plugin, WorldHudProtocol.MOBS);
            feeds.disable(messenger); // 2026-11-02（C 組）
            registered = false;
        }
        HandlerList.unregisterAll(this);
        clients.clear();
    }

    private void readConfig() {
        enabled = plugin.getConfig().getBoolean("client-mod.enabled", true);
        hudInterval = Math.max(2, Math.min(20, plugin.getConfig().getInt("client-mod.hud-interval", 3)));
        publicUrl = plugin.getConfig().getString("client-mod.public-url", "");
        changelog = plugin.getConfig().getString("client-mod.changelog", "");
        effects = plugin.getConfig().getBoolean("client-mod.effects", true);
        fx.maxBytes(plugin.getConfig().getInt("client-mod.fx-max-bytes", FxRouter.DEFAULT_MAX_BYTES));
        mapPanel = plugin.getConfig().getBoolean("client-mod.map-panel", true);
        damageNumbers = plugin.getConfig().getBoolean("client-mod.damage-numbers", true);
        mobBars = plugin.getConfig().getBoolean("client-mod.mob-bars", true);
        mobInterval = Math.max(5, Math.min(10, plugin.getConfig().getInt("client-mod.mob-bars-interval", MobBarFeed.DEFAULT_INTERVAL)));
        mobRange = Math.max(8, Math.min(48, plugin.getConfig().getDouble("client-mod.mob-bars-range", MobBarFeed.DEFAULT_RANGE)));
        mobMax = Math.max(1, Math.min(WorldHudProtocol.MAX_MOBS, plugin.getConfig().getInt("client-mod.mob-bars-max", MobBarFeed.DEFAULT_MAX)));
    }

    /** plugins/ExileCore/clientmod/。 */
    Path folder() {
        return plugin.dataFolder().resolve("clientmod");
    }

    // ---- 給別的模組看 ----

    /** 2026-11-02（D 組）：原生畫面（天賦樹、星圖…）；模組沒啟用時是 null。 */
    public UiFeed ui() {
        return ui;
    }

    /** 這個玩家有裝模組（招呼打過、協定對得上）。 */
    public boolean modded(final Player player) {
        return player != null && clients.containsKey(player.getUniqueId());
    }

    /** 2026-11-05：這個玩家裝的模組是 version 以上（沒裝 false）。 */
    public boolean modAtLeast(final Player player, final String version) {
        final Client client = player == null ? null : clients.get(player.getUniqueId());
        return client != null && client.hello.modVersion() != null && ClientModProtocol.compareVersions(client.hello.modVersion(), version) >= 0;
    }

    /** 模組會畫技能列與增益倒數：動作列不用再寫增益倒數。 */
    public boolean drawsBuffs(final Player player) {
        final Client client = player == null ? null : clients.get(player.getUniqueId());
        return client != null && client.hello.hud();
    }

    /** 2026-11-02：這個玩家的特效現在由模組畫（新的特效）。 */
    public boolean drawsEffects(final Player player) {
        return player != null && fx.capable(player.getUniqueId());
    }

    /** 2026-11-02：特效封包的總計 {位元組, 封包數, 改回送粒子的特效數}。 */
    public long[] fxTotals() {
        return fx.totals();
    }

    /** 模組會畫生命與能量護盾：不用拿原版的吸收愛心顯示能量護盾。 */
    public boolean drawsHearts(final Player player) {
        final Client client = player == null ? null : clients.get(player.getUniqueId());
        return client != null && client.hello.hearts();
    }

    /**
     * 2026-11-02：模組把生命、魔力畫成快捷欄兩側的球（1.3 起；按 F9 切回生命條、F7 關掉 HUD 時是 false）：
     * 動作列不寫生命魔力數字與等級、經驗條顯示角色經驗、快捷欄兩側的字往外移到球的外側。
     */
    public boolean drawsOrbs(final Player player) {
        final Client client = player == null ? null : clients.get(player.getUniqueId());
        return client != null && client.hello.orbs();
    }

    /** 2026-11-02（1.4）：這個人的模組說它支援這個功能（招呼的旗標）。 */
    private boolean flag(final Player player, final String flag) {
        final Client client = player == null ? null : clients.get(player.getUniqueId());
        return client != null && client.hello.has(flag);
    }

    /**
     * 2026-11-02 照站長（模組 1.4）：模組畫提示列：提示（PlayerState.notice、ActionBar.send）改送 {@value ClientModProtocol#NOTICE}，
     * 動作列不寫提示、也不會被提示蓋掉。
     */
    public boolean drawsNotices(final Player player) {
        return flag(player, ClientModProtocol.FLAG_NOTICE) && listens(player, ClientModProtocol.NOTICE);
    }

    /** 2026-11-02（1.4）：模組畫側邊面板：側欄改送 {@value ClientModProtocol#PANELS}，不建記分板。 */
    public boolean drawsPanels(final Player player) {
        return flag(player, ClientModProtocol.FLAG_PANELS) && listens(player, ClientModProtocol.PANELS);
    }

    /** 2026-11-02（1.4）：模組在魔力球旁邊畫戰鬥資源（hud 的 res）：不掛戰鬥資源的 Boss 血條。 */
    public boolean drawsResources(final Player player) {
        return flag(player, ClientModProtocol.FLAG_RES);
    }

    /** 2026-11-02（1.4）：模組在生命球旁邊畫自己的異常、詛咒、守護、入場保護（有球時才會說）：動作列不寫這些。 */
    public boolean drawsStatus(final Player player) {
        return flag(player, ClientModProtocol.FLAG_STATUS) && drawsOrbs(player);
    }

    /**
     * 2026-11-02 照站長（模組 1.4）：模組畫快捷欄兩側那些字（光環、召喚物、撿到的東西）：動作列不接 HotbarSides。
     */
    public boolean drawsSides(final Player player) {
        return flag(player, ClientModProtocol.FLAG_SIDES);
    }

    /** 模組註冊了這個頻道（還沒註冊的話送了也收不到：照原版通道）。 */
    private static boolean listens(final Player player, final String channel) {
        try {
            return player.getListeningPluginChannels().contains(channel);
        } catch (final RuntimeException e) {
            return false;
        }
    }

    /**
     * 2026-11-02 照站長（模組 1.4）：送一則提示給模組的提示列（{@link #drawsNotices} 要是 true）。
     *
     * @param ticks 停幾 tick
     */
    public void notice(final Player player, final List<MiniParts.Part> parts, final long ticks) {
        if (player == null || parts.isEmpty() || MiniParts.plainOf(parts).isBlank()) {
            return;
        }
        player.sendPluginMessage(plugin, ClientModProtocol.NOTICE, ClientModProtocol.encodeNotice(parts, ticks,
                ClientModProtocol.noticeLevel(parts)));
    }

    /** 2026-11-02 照站長（模組 1.4）：側欄改成模組的面板（{@link #drawsPanels} 要是 true）；跟上次一樣不送。 */
    public void panels(final Player player, final List<HudPanels.Panel> panels) {
        final Client client = player == null ? null : clients.get(player.getUniqueId());
        if (client == null) {
            return;
        }
        final String json = HudPanels.toJson(panels);
        if (json.equals(client.lastPanels)) {
            return;
        }
        client.lastPanels = json;
        player.sendPluginMessage(plugin, ClientModProtocol.PANELS, json.getBytes(StandardCharsets.UTF_8));
    }

    /** 2026-11-02（模組 1.4）：模組畫地圖資訊框（地圖事件的進度不用再另外用血條、動作列寫）。 */
    public boolean drawsMapPanel(final Player player) {
        final Client client = player == null ? null : clients.get(player.getUniqueId());
        return enabled && mapPanel && client != null && client.features.mapbox();
    }

    /** 2026-11-02（模組 1.4）：模組畫怪物頭上的血條。 */
    public boolean drawsMobBars(final Player player) {
        final Client client = player == null ? null : clients.get(player.getUniqueId());
        return enabled && mobBars && client != null && client.features.mobbars();
    }

    /**
     * 2026-11-02（模組 1.4）：傷害數字交給模組畫（true = 模組會畫，伺服器不用生文字顯示實體）。
     * 沒裝、模組沒說 dmgnum、沒註冊 exilecore:fx、這個 tick 超過上限都是 false。
     */
    public boolean routeNumber(final Player viewer, final FxProtocol.Number number) {
        return enabled && viewer != null && fx.number(viewer.getUniqueId(), number);
    }

    // ---- 封包 ----

    @Override
    public void onPluginMessageReceived(final String channel, final Player player, final byte[] message) {
        if (!enabled) {
            return;
        }
        if (ClientModProtocol.HELLO.equals(channel)) {
            final ClientModProtocol.Hello hello = ClientModProtocol.decodeHello(message);
            if (hello == null) {
                return;
            }
            final int protocol = ClientModProtocol.negotiate(hello.protocol());
            boolean drawsEffects = false;
            if (protocol > 0) {
                final Client before = clients.get(player.getUniqueId());
                final Client client = new Client(hello);
                client.protocol = protocol;
                client.features = WorldHudProtocol.decodeFeatures(message); // 2026-11-02（模組 1.4 B 組）
                if (before != null) {
                    client.lastHit = before.lastHit;
                    client.lastHitTick = before.lastHitTick;
                    client.noticeSerial = before.noticeSerial;
                    client.aim = before.aim;
                    client.lastMap = before.lastMap;
                }
                clients.put(player.getUniqueId(), client);
                drawsEffects = updateEffects(player, client);
                refreshSidebar(player); // 2026-11-02（1.4）：panels 換了：記分板 ↔ 模組的面板
            } else {
                clients.remove(player.getUniqueId());
                fx.setCapable(player.getUniqueId(), false);
                refreshSidebar(player);
                fx.setNumbers(player.getUniqueId(), false);
            }
            if (protocol > 0) {
                ensureChannels(player, hello); // 2026-11-02 照站長：經過 Velocity 時模組的頻道登記可能沒傳到，回招呼前先補上
            }
            if (ui != null) {
                ui.hello(player, message, protocol > 0); // 2026-11-02（D 組）：招呼的 treeui、atlasui
            }
            feeds.hello(player, message, protocol > 0); // 2026-11-02（C 組）：招呼裡的 alerts、minimap、death、tooltip
            final ModRelease current = release;
            // 回兩邊都會的最高版（1.0 的模組只認 1）；不支援的版本回我們的版本，讓模組提示要更新
            player.sendPluginMessage(plugin, ClientModProtocol.HELLO, ClientModProtocol.encodeServerHello(protocol > 0 ? protocol
                    : ClientModProtocol.PROTOCOL, current == null ? "" : current.version(), current == null ? "" : manifestUrl(), drawsEffects));
            return;
        }
        if (WorldHudProtocol.AIM.equals(channel)) {
            final Client client = clients.get(player.getUniqueId());
            final Integer aim = WorldHudProtocol.decodeAim(message);
            if (client != null && aim != null) {
                client.aim = aim;
            }
            return;
        }
        if (ClientModProtocol.KEY.equals(channel)) {
            final Client client = clients.get(player.getUniqueId());
            final ClientModProtocol.Key key = ClientModProtocol.decodeKey(message, SkillLoadout.BIND_SLOTS);
            if (client == null || key == null || plugin.skills() == null) {
                return;
            }
            if (!key.pressed()) {
                client.held.release(key.slot());
                return;
            }
            if (client.held.press(key.slot(), plugin.getServer().getCurrentTick())) {
                plugin.skills().pressBind(player, key.slot());
            }
        }
    }

    /**
     * 這個人的特效要不要由模組畫：模組說要（協定 2、effects:true）、伺服器開著、而且模組註冊了 exilecore:fx 頻道
     * （還沒註冊的話等 {@link #onRegister}）。
     */
    private boolean updateEffects(final Player player, final Client client) {
        final boolean wants = effects && client.protocol >= 2 && client.hello.effects();
        final boolean listening = player.getListeningPluginChannels().contains(ClientModProtocol.FX);
        final boolean capable = wants && listening;
        fx.setCapable(player.getUniqueId(), capable);
        fx.setNumbers(player.getUniqueId(), enabled && damageNumbers && client.features.dmgnum() && listening); // 2026-11-02（模組 1.4）
        return wants;
    }

    /** 2026-11-02（1.4）：側欄照新的招呼重畫（記分板 ↔ 模組的面板）。 */
    private void refreshSidebar(final Player player) {
        if (plugin.sidebar() != null) {
            try {
                plugin.sidebar().refresh(player);
            } catch (final RuntimeException e) {
                plugin.getSLF4JLogger().warn("星骸輔助模組：重畫 {} 的側欄出錯", player.getName(), e);
            }
        }
    }

    @EventHandler
    public void onRegister(final PlayerRegisterChannelEvent event) {
        if (ClientModProtocol.PANELS.equals(event.getChannel()) && clients.containsKey(event.getPlayer().getUniqueId())) {
            refreshSidebar(event.getPlayer()); // 2026-11-02（1.4）：頻道比招呼晚註冊
            return;
        }
        if (!ClientModProtocol.FX.equals(event.getChannel())) {
            return;
        }
        final Client client = clients.get(event.getPlayer().getUniqueId());
        if (client != null) {
            updateEffects(event.getPlayer(), client);
        }
    }

    /**
     * 2026-11-02 照站長：模組打了招呼，代表它聽得懂我們全部的頻道；可是 Paper 只送給「登記過那個頻道」的玩家，
     * 經過 Velocity 時模組的頻道登記（minecraft:register）有時沒傳到後端，回招呼、HUD、面板、天賦樹全部被默默丟掉
     * （玩家看到的是：動作列、記分板被伺服器停掉了，模組卻什麼都沒畫、K 打不開）。這裡直接替玩家補登記我們的送出頻道。
     * 這次之前只靠用戶端的登記。
     */
    private void ensureChannels(final Player player, final ClientModProtocol.Hello hello) {
        final java.util.Set<String> listening = player.getListeningPluginChannels();
        final List<String> missing = new ArrayList<>();
        for (final String channel : plugin.getServer().getMessenger().getOutgoingChannels(plugin)) {
            if (channel.startsWith("exilecore:") && !listening.contains(channel)) {
                missing.add(channel);
            }
        }
        if (missing.isEmpty()) {
            plugin.getSLF4JLogger().info("星骸輔助模組：{} 連上了（模組 {}）", player.getName(), hello.modVersion());
            return;
        }
        try {
            final java.lang.reflect.Method add = player.getClass().getMethod("addChannel", String.class);
            for (final String channel : missing) {
                add.invoke(player, channel);
            }
            plugin.getSLF4JLogger().info("星骸輔助模組：{} 連上了（模組 {}）；頻道登記沒傳到伺服器（通常是經過 Velocity），補上 {} 個",
                    player.getName(), hello.modVersion(), missing.size());
        } catch (final ReflectiveOperationException | RuntimeException e) {
            plugin.getSLF4JLogger().warn("星骸輔助模組：{} 的頻道登記沒傳到伺服器（{}），補不上：{}——模組會收不到資料",
                    player.getName(), missing, e.toString());
        }
    }

    @EventHandler
    public void onUnregister(final PlayerUnregisterChannelEvent event) {
        if (ClientModProtocol.FX.equals(event.getChannel())) {
            fx.setCapable(event.getPlayer().getUniqueId(), false);
            fx.setNumbers(event.getPlayer().getUniqueId(), false);
        }
    }

    /** tick 結束：這個 tick 每個人的特效各送一個封包。 */
    @EventHandler
    public void onTickEnd(final com.destroystokyo.paper.event.server.ServerTickEndEvent event) {
        for (final Map.Entry<UUID, byte[]> entry : fx.drain()) {
            final Player player = plugin.getServer().getPlayer(entry.getKey());
            if (player != null && player.isConnected()) {
                player.sendPluginMessage(plugin, ClientModProtocol.FX, entry.getValue());
            }
        }
    }

    /** 更新說明檔的網址（沒有對外的網址時是空字串）。 */
    String manifestUrl() {
        final String base = baseUrl();
        return base.isEmpty() ? "" : base + "/mod/manifest.json";
    }

    private String baseUrl() {
        String base = publicUrl == null ? "" : publicUrl.trim();
        if (base.isEmpty() && plugin.web() != null && plugin.web().available()) {
            base = plugin.web().publicUrl();
        }
        while (base.endsWith("/")) {
            base = base.substring(0, base.length() - 1);
        }
        return base;
    }

    @EventHandler
    public void onQuit(final PlayerQuitEvent event) {
        clients.remove(event.getPlayer().getUniqueId());
        fx.remove(event.getPlayer().getUniqueId());
        feeds.remove(event.getPlayer().getUniqueId()); // 2026-11-02（C 組）
    }

    @EventHandler
    public void onWorld(final PlayerChangedWorldEvent event) {
        final Client client = clients.get(event.getPlayer().getUniqueId());
        if (client != null) {
            client.held.clear(); // 換世界（進地圖）時不要一直按著
            client.lastHit = null;
            client.aim = client.aim == null ? null : -1;
            client.mobs.reset(); // 2026-11-02：模組換世界時清掉血條，下一包送完整的
        }
        fx.worldChanged(event.getPlayer().getUniqueId()); // 模組換世界時清掉畫面上的範圍
    }

    /** 記下有裝模組的玩家最後打到的怪物（目標框）。 */
    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onHit(final HitEvent event) {
        final Player credit = event.credit();
        final Client client = credit == null ? null : clients.get(credit.getUniqueId());
        if (client == null || event.defender() instanceof Player) {
            return;
        }
        client.lastHit = event.defender().getUniqueId();
        client.lastHitTick = plugin.getServer().getCurrentTick();
    }

    // ---- 每 tick ----

    private void tick() {
        if (clients.isEmpty()) {
            return;
        }
        final long now = plugin.getServer().getCurrentTick();
        for (final Map.Entry<UUID, Client> entry : clients.entrySet()) {
            final Player player = plugin.getServer().getPlayer(entry.getKey());
            if (player == null) {
                continue;
            }
            final Client client = entry.getValue();
            try {
                if (plugin.skills() != null) {
                    for (final int slot : client.held.repeats(now)) {
                        plugin.skills().pressBind(player, slot); // 按住：照原版滑鼠按住的頻率一直按
                    }
                }
                if (now - client.lastSent >= hudInterval) {
                    sendHud(player, client, now);
                }
                pollNotice(player, client, now);
                if (client.features.any()) {
                    tickWorld(player, client, now); // 2026-11-02（模組 1.4 B 組）
                }
            } catch (final RuntimeException e) {
                plugin.getSLF4JLogger().warn("星骸輔助模組：處理 {} 的畫面資料出錯", player.getName(), e);
            }
        }
    }

    /** 2026-11-02（1.4）：PlayerState 有新的提示：送給模組的提示列（不用等動作列下一次刷新）。 */
    private void pollNotice(final Player player, final Client client, final long now) {
        final PlayerState state = plugin.players().get(player);
        if (state == null || state.noticeSerial() == client.noticeSerial) {
            return;
        }
        final boolean fresh = client.noticeSerial >= 0; // 剛打招呼：上線前的那則不補送
        client.noticeSerial = state.noticeSerial();
        final String text = state.lastNotice();
        if (fresh && text != null && state.noticeExpiresTick() > now && drawsNotices(player)) {
            notice(player, MiniParts.parse(text), state.noticeExpiresTick() - now);
        }
    }

    /** 2026-11-02（模組 1.4 B 組）：怪物頭上的血條（每 mobInterval tick）、地圖資訊框（每 {@link #MAP_INTERVAL} tick，有變才送）。 */
    private void tickWorld(final Player player, final Client client, final long now) {
        if (mobBars && client.features.mobbars() && Math.floorMod(now + player.getEntityId(), mobInterval) == 0) {
            final byte[] packet = client.mobs.next(MobBarFeed.collect(plugin, player, mobRange, mobMax));
            if (packet != null) {
                player.sendPluginMessage(plugin, WorldHudProtocol.MOBS, packet);
            }
        }
        if (client.features.mapbox() && Math.floorMod(now + player.getEntityId(), MAP_INTERVAL) == 0) {
            final WorldHudProtocol.MapPanel panel = mapPanel && plugin.maps() != null ? tw.exilecore.map.MapHudFeed.panel(plugin, player) : null;
            if (panel == null && client.lastMap == null) {
                return;
            }
            final String json = WorldHudProtocol.encodeMap(panel);
            if (!json.equals(client.lastMap)) {
                client.lastMap = panel == null ? null : json;
                player.sendPluginMessage(plugin, WorldHudProtocol.MAP, json.getBytes(StandardCharsets.UTF_8));
            }
        }
    }

    private void sendHud(final Player player, final Client client, final long now) {
        final HudSnapshot snapshot = snapshot(player, client, now);
        final String signature = snapshot.signature();
        if (signature.equals(client.lastSignature)) {
            return;
        }
        client.lastSignature = signature;
        client.lastSent = now;
        player.sendPluginMessage(plugin, ClientModProtocol.HUD, snapshot.toJson(now).getBytes(StandardCharsets.UTF_8));
    }

    HudSnapshot snapshot(final Player player, final Client client, final long now) {
        final PlayerState state = plugin.players().get(player);
        final List<HudSnapshot.Slot> slots = new ArrayList<>();
        final List<HudSnapshot.Buff> buffs = new ArrayList<>();
        final SkillService skills = plugin.skills();
        if (skills != null && state.hasCharacter()) {
            for (final SkillService.SlotView view : skills.slotViews(player)) {
                final SkillDefinition definition = view.definition();
                final String[] icon = icon(definition);
                final int[] glory = view.glory(); // 2026-11-02（1.4）：榮耀（sides 的人動作列不寫了，改畫在模組的技能格裡）
                slots.add(new HudSnapshot.Slot(view.bind(), definition.id(), definition.name(), icon[0], icon[1], view.bound(),
                        now + view.cooldownLeft(), view.cooldownTotal(), view.usesLeft(), view.maxUses(), view.manaCost(), view.problem(),
                        glory == null ? 0 : glory[0], glory == null ? 0 : glory[1]));
            }
            final boolean fighting = plugin.combat() != null && plugin.combat().hud() != null && plugin.combat().hud().fighting(player.getUniqueId());
            final SkillService.HudBuffs hud = skills.hudBuffs(player, fighting); // 跟快捷欄的增益倒數同一份
            final HotbarLayout layout = HotbarLayout.of(state);
            int index = 0;
            for (final SkillService.SlotTimer timer : hud.slots()) {
                if (timer.ticks() <= 0) {
                    continue;
                }
                final long total = client.totals.observe("s" + timer.hotbarSlot() + ":" + timer.name(), timer.ticks());
                buffs.add(new HudSnapshot.Buff(timer.name(), now + timer.ticks(), total, 0, layout.bindForSlot(timer.hotbarSlot())));
            }
            for (final SkillService.BuffTimer timer : hud.others()) {
                if (timer.ticks() <= 0 || timer.ticks() > 20L * 60 * 60 * 24) {
                    continue; // 沒有期限的（一直刷新）不列
                }
                final long total = client.totals.observe("o" + (index++) + ":" + timer.name(), timer.ticks());
                buffs.add(new HudSnapshot.Buff(timer.name(), now + timer.ticks(), total, 0, -1, timer.important()));
            }
            client.totals.endRound();
        }
        return new HudSnapshot(slots, buffs, target(player, client, now), vitals(state), self(player, client, state, now),
                resources(player, client, state), sides(player, client, now));
    }

    /** 2026-11-02 照站長（模組 1.4）：光環、召喚物、撿到的東西（只給招呼寫了 sides 的模組）。 */
    private HudSnapshot.Sides sides(final Player player, final Client client, final long now) {
        if (!client.hello.has(ClientModProtocol.FLAG_SIDES)) {
            return null;
        }
        final List<String> auras = plugin.skills() == null ? List.of() : plugin.skills().auraNames(player);
        final List<HudSnapshot.Pet> pets = new ArrayList<>();
        if (plugin.skills() != null) {
            for (final tw.exilecore.skill.MinionService.HudCount count : plugin.skills().minionCounts(player)) {
                pets.add(new HudSnapshot.Pet(count.name(), count.count(), count.limit()));
            }
        }
        final List<HudSnapshot.Loot> loot = new ArrayList<>();
        for (final tw.exilecore.character.LootFeed.Entry entry : plugin.players().lootFeed().active(player.getUniqueId(), now)) {
            int color = 0xC8C8C8;
            try {
                color = Integer.parseInt(entry.color().replace("#", ""), 16);
            } catch (final RuntimeException ignored) {
                // 顏色寫壞：灰色
            }
            loot.add(new HudSnapshot.Loot(entry.name(), entry.count(), color, entry.until(), tw.exilecore.character.LootFeed.SHOW_TICKS));
        }
        return new HudSnapshot.Sides(auras, pets, loot);
    }

    /**
     * 2026-11-02 照站長（模組 1.4）：自己身上的異常、詛咒（只給招呼寫了 status、有球的模組）、守護、入場保護、等級與經驗。
     * 這次之前異常、守護、入場保護只寫在動作列。
     */
    private HudSnapshot.Self self(final Player player, final Client client, final PlayerState state, final long now) {
        if (!state.isLoaded()) {
            return null;
        }
        List<MiniParts.Part> status = List.of();
        if (client.hello.has(ClientModProtocol.FLAG_STATUS) && plugin.ailments() != null) {
            status = MiniParts.parse(join(plugin.ailments().statusText(player), plugin.ailments().hexText(player.getUniqueId(), now, true)));
        }
        int level = 0;
        long xp = 0;
        long need = 0;
        final tw.exilecore.character.CharacterRecord character = state.character();
        if (state.hasCharacter() && character != null) {
            level = character.level();
            xp = character.experience();
            need = level >= plugin.players().config().levelCap() ? 0 : plugin.players().config().experienceToNext(level);
        }
        return new HudSnapshot.Self(status, state.guard(now), state.isSafe(), level, xp, need);
    }

    /** 2026-11-02 照站長（模組 1.4）：戰鬥資源（只給招呼寫了 res 的模組；ResourceBar 那條血條的同一份資料）。 */
    private HudSnapshot.Resources resources(final Player player, final Client client, final PlayerState state) {
        if (!client.hello.has(ClientModProtocol.FLAG_RES) || !state.hasCharacter() || player.isDead()) {
            return null;
        }
        final tw.exilecore.character.ResourceBar.Sources sources = tw.exilecore.character.ResourceBar.read(plugin, player);
        final List<HudSnapshot.Charge> charges = plugin.combat() != null && plugin.combat().charges() != null
                ? plugin.combat().charges().hudCharges(player) : List.of();
        return new HudSnapshot.Resources(sources.rage(), sources.rageMax(), sources.decaying(), charges,
                MiniParts.parse(String.join("  ", sources.moddedOthers())));
    }

    /** 技能圖示：{原版物品, custom_model_data 字串（沒有是 null）}。資源包只在部分原版物品上掛了技能圖的挑選，掛不到的改用書。 */
    static String[] icon(final SkillDefinition definition) {
        String item = SkillArt.vanillaItem(definition.icon());
        final String art = SkillArt.artOf(definition);
        if (art != null && (item == null || ClientModService.class.getResource("/web/vanilla_items/" + item + ".json") == null)) {
            item = "book";
        }
        return new String[] {"minecraft:" + (item == null ? "book" : item), art == null ? null : tw.exilecore.currency.CurrencyArt.modelKey(art)};
    }

    private HudSnapshot.Vitals vitals(final PlayerState state) {
        if (!state.isLoaded()) {
            return null;
        }
        return new HudSnapshot.Vitals(state.life(), state.maxLife(), state.reservedLife(), state.energyShield(), state.maxEnergyShield(),
                state.mana(), state.maxMana(), manaShare(state), state.reservedFraction() * state.maxMana());
    }

    /**
     * 受到的傷害有多少比例先扣魔力（0～1）：模組的生命條、魔力球、資源包版的魔力球（2026-11-02）共用。
     * 這次之前寫在 vitals 裡面。
     */
    public static double manaShare(final PlayerState state) {
        final tw.exilecore.stats.StatSheet sheet = state.sheet();
        final double fromMana = sheet == null ? 0 : sheet.flat(tw.exilecore.stats.StatKeys.DAMAGE_FROM_MANA, java.util.Set.of());
        final double elemental = sheet == null ? 0 : sheet.flat(tw.exilecore.ascendancy.AscKeys.ELEMENTAL_FROM_MANA, java.util.Set.of());
        final boolean harmony = sheet != null && sheet.flat(tw.exilecore.ascendancy.AscKeys.HARMONY, java.util.Set.of()) != 0
                && state.mana() > state.life();
        return HudSnapshot.manaShare(fromMana, elemental, harmony, state.mana());
    }

    /** 目標框：準心指著的怪物（20 格內），不然最後打到的那隻（8 秒內、32 格內）。 */
    private HudSnapshot.Target target(final Player player, final Client client, final long now) {
        if (plugin.monsters() == null) {
            return null;
        }
        LivingEntity living = null;
        // 2026-11-02 照站長（模組 1.4）：模組說準心指著誰就用那隻（client.aim）；這次之前（與舊模組）用伺服器自己量的視線
        final Entity looked = client.aim == null ? player.getTargetEntity(LOOK_RANGE, false) : aimed(player, client.aim);
        if (looked instanceof LivingEntity candidate && !(looked instanceof Player) && plugin.monsters().data(candidate).isPresent()) {
            living = candidate;
        } else if (client.lastHit != null && now - client.lastHitTick <= LAST_HIT_TICKS) {
            final Entity hit = plugin.getServer().getEntity(client.lastHit);
            if (hit instanceof LivingEntity candidate && hit.getWorld().equals(player.getWorld())
                    && hit.getLocation().distanceSquared(player.getLocation()) <= LAST_HIT_RANGE * LAST_HIT_RANGE) {
                living = candidate;
            }
        }
        if (living == null || !living.isValid() || living.isDead()) {
            return null;
        }
        final java.util.Optional<MonsterData> found = plugin.monsters().data(living);
        if (found.isEmpty()) {
            return null;
        }
        final MonsterData data = found.get();
        final var maxAttribute = living.getAttribute(org.bukkit.attribute.Attribute.MAX_HEALTH);
        final double scale = plugin.monsters().healthScale(living, data);
        final double divisor = scale > 0 ? scale : 1;
        final double life = living.getHealth() / divisor;
        final double maxLife = (maxAttribute == null ? living.getHealth() : maxAttribute.getValue()) / divisor;
        int shield = -1;
        if (plugin.monsterModule() != null && plugin.monsterModule().mechanics() != null) {
            final double[] left = plugin.monsterModule().mechanics().shieldLeft(living.getUniqueId());
            if (left != null && left[1] > 0) {
                shield = (int) Math.round(Math.max(0, Math.min(1, left[0] / left[1])) * 100);
            }
        }
        final List<MiniParts.Part> status = new ArrayList<>();
        if (plugin.ailments() != null) {
            final String ailments = plugin.ailments().statusText(living.getUniqueId());
            final String hex = plugin.ailments().hexText(living.getUniqueId(), now, true);
            status.addAll(MiniParts.parse(join(ailments, hex)));
        }
        // 2026-11-02：稀有、傳奇怪的詞綴列在狀態那一行（魔法怪的詞綴本來就寫在名字裡）；這次之前看不到稀有怪有哪些詞綴
        final String mods = modNames(data);
        if (!mods.isEmpty()) {
            status.addAll(MiniParts.parse(status.isEmpty() ? mods : "  " + mods));
        }
        int color = 0xFFFFFF;
        try {
            color = Integer.parseInt(data.rarity().color().substring(1), 16);
        } catch (final RuntimeException ignored) {
            // 顏色寫壞：白色
        }
        final String name = data.name() == null || data.name().isBlank() ? living.getName() : data.name();
        return new HudSnapshot.Target(MiniParts.plain(name), color, data.rarity().displayName(), data.level(), Math.max(0, life),
                Math.max(1, maxLife), shield, data.boss(), status, targetInvulnerable(living));
    }

    /**
     * 2026-11-02 照站長（模組 1.4）：目標現在打不動（目標框畫成灰色加鎖頭）：實體無敵、庇護光環保護（MonsterMechanics.sanctuaryProtected）、
     * 地圖開場無敵（MapService.openingInvulnerable）。
     */
    boolean targetInvulnerable(final LivingEntity living) {
        if (living.isInvulnerable()) {
            return true;
        }
        if (plugin.monsterModule() != null && plugin.monsterModule().mechanics() != null
                && plugin.monsterModule().mechanics().sanctuaryProtected(living.getUniqueId())) {
            return true;
        }
        return plugin.maps() != null && plugin.maps().openingInvulnerable(living);
    }

    /** 2026-11-02：模組說準心指著的實體（{@link #LOOK_RANGE} 格內、同一個世界；沒有、太遠回傳 null）。 */
    private static Entity aimed(final Player player, final int entityId) {
        if (entityId < 0) {
            return null;
        }
        for (final Entity entity : player.getNearbyEntities(LOOK_RANGE, LOOK_RANGE, LOOK_RANGE)) {
            if (entity.getEntityId() == entityId) {
                return entity;
            }
        }
        return null;
    }

    /** 2026-11-02：稀有、傳奇怪的詞綴名（灰色、用「・」隔開；隱藏的、讀不到的不列）。 */
    private String modNames(final MonsterData data) {
        if (data.rarity() != tw.exilecore.monster.MonsterRarity.RARE && data.rarity() != tw.exilecore.monster.MonsterRarity.UNIQUE
                || data.mods().isEmpty()) {
            return "";
        }
        final List<String> names = new ArrayList<>();
        for (final String id : data.mods()) {
            final tw.exilecore.monster.MonsterConfig.MonsterMod mod = plugin.monsters().config().mod(id);
            if (mod != null && !names.contains(mod.name())) {
                names.add(mod.name());
            }
        }
        return names.isEmpty() ? "" : "<gray>" + String.join("・", names);
    }

    /** 純函式：異常與詛咒兩段接起來（中間兩個空白；空的不接）。 */
    static String join(final String a, final String b) {
        final String left = a == null ? "" : a;
        final String right = b == null ? "" : b;
        return left.isEmpty() ? right : right.isEmpty() ? left : left + "  " + right;
    }

    // ---- 網頁：更新說明檔與模組檔 ----

    /** 重新看一次資料夾裡最新的模組檔（任何執行緒都可以叫）。 */
    synchronized void refreshRelease() {
        try {
            final ModRelease before = release;
            final ModRelease scanned = ModRelease.latest(folder()); // jar 只有幾百 KB，10 秒算一次雜湊很便宜
            if (!java.util.Objects.equals(scanned, before)) {
                release = scanned;
                jarCache = null;
                if (scanned != null && before != null && !scanned.version().equals(before.version())) {
                    plugin.getSLF4JLogger().info("星骸輔助模組：換成 {}（{}）", scanned.version(), scanned.fileName());
                }
            }
        } catch (final IOException | RuntimeException e) {
            plugin.getSLF4JLogger().warn("星骸輔助模組：讀不到 plugins/ExileCore/clientmod/ 的模組檔（{}）", e.toString());
        }
    }

    private void install(final WebServer server) {
        server.route("/mod/manifest.json", path -> {
            refreshRelease();
            final ModRelease current = release;
            if (current == null) {
                return WebServer.Response.text(404, "伺服器還沒放星骸輔助模組");
            }
            return new WebServer.Response(200, "application/json; charset=utf-8",
                    current.manifestJson(baseUrl(), changelog).getBytes(StandardCharsets.UTF_8), Map.of("Cache-Control", "no-cache"));
        });
        server.prefix("/mod/", path -> {
            final ModRelease current = release;
            final String name = path.substring("/mod/".length());
            if (current == null || !name.equals(current.fileName())) {
                return null;
            }
            final byte[] body = jarBytes(current);
            return body == null ? WebServer.Response.text(500, "讀不到模組檔")
                    : new WebServer.Response(200, "application/java-archive", body, Map.of("Cache-Control", "no-cache",
                            "Content-Disposition", "attachment; filename=\"" + current.fileName().replace("\"", "") + "\""));
        });
    }

    private byte[] jarBytes(final ModRelease current) {
        final Object[] cache = jarCache;
        if (cache != null && cache[0].equals(current.fileName()) && (long) cache[1] == current.modified()) {
            return (byte[]) cache[2];
        }
        try {
            final byte[] body = Files.readAllBytes(current.file());
            if (!ModRelease.sha256Of(body).equals(current.sha256())) {
                refreshRelease(); // 檔案剛被換掉：雜湊對不上的不送
                return null;
            }
            jarCache = new Object[] {current.fileName(), current.modified(), body};
            return body;
        } catch (final IOException e) {
            return null;
        }
    }
}
