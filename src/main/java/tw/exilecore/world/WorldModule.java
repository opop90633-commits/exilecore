package tw.exilecore.world;

import net.kyori.adventure.text.Component;
import net.kyori.adventure.title.Title;
import org.bukkit.Sound;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.event.server.ServerListPingEvent;
import tw.exilecore.ExileCore;
import tw.exilecore.module.Module;

import java.time.Duration;
import java.util.List;

/**
 * world 模組：《星骸》的世界觀文字——伺服器列表的說明（MOTD）與新角色登場的旁白（data/world.yml）；
 * 啟動時確認出生點底下不是虛空（{@link VoidSpawn}）；
 * 載入程式生成的劇情世界與地圖世界、保護它們不被破壞（{@link CampaignWorld}、{@link WorldProtection}）。
 */
public final class WorldModule implements Module, Listener {

    /** 登場標題延後幾 tick（讓營地的進區標題先跑完）。 */
    private static final long TITLE_DELAY = 60;
    /** 登場旁白延後幾 tick（等傳送到營地）。 */
    private static final long LINES_DELAY = 20;

    private final ExileCore plugin;
    private final WorldLore lore = new WorldLore();
    private Component motd;
    private CampaignWorld worlds;

    public WorldModule(final ExileCore plugin) {
        this.plugin = plugin;
    }

    @Override
    public String id() {
        return "world";
    }

    @Override
    public void enable() throws Exception {
        load();
        plugin.getServer().getPluginManager().registerEvents(this, plugin);
        try {
            VoidSpawn.ensure(plugin.getServer().getRespawnWorld(), plugin.getSLF4JLogger());
        } catch (final RuntimeException e) {
            plugin.getSLF4JLogger().warn("檢查虛空地圖的出生點失敗", e);
        }
        final CampaignWorld created = new CampaignWorld(plugin, lore);
        created.enable();
        worlds = created;
        plugin.getServer().getPluginManager().registerEvents(new WorldProtection(plugin, created), plugin);
        if (created.campaign() != null) {
            // 路上的路標（發光的箭頭）：區塊載入時鋪上去，已經載入的先做一次
            final RoadGuideListener guides = new RoadGuideListener(created.campaign());
            plugin.getServer().getPluginManager().registerEvents(guides, plugin);
            guides.applyLoaded();
            // 碰得到的蜘蛛網、甜莓叢（跑起來像被拉住；舊區塊地上有）：區塊載入時清一次，清過的記在區塊上
            final WalkableCleanup cleanup = new WalkableCleanup(plugin, created.campaign());
            plugin.getServer().getPluginManager().registerEvents(cleanup, plugin);
            final int cleaned = cleanup.applyLoaded();
            if (cleaned > 0) {
                plugin.getSLF4JLogger().info("劇情世界：清掉 {} 格地上的蜘蛛網與甜莓叢（之後載入的區塊也會清）", cleaned);
            }
        }
    }

    @Override
    public void reload() throws Exception {
        load();
        if (worlds != null) {
            worlds.reload();
        }
    }

    @Override
    public void disable() {
        if (worlds != null) {
            worlds.disable();
        }
    }

    /** 劇情世界與地圖世界；模組還沒啟用時為 null。 */
    public CampaignWorld worlds() {
        return worlds;
    }

    private void load() throws Exception {
        lore.load(plugin.dataFolder().resolve("data"));
        final List<String> lines = lore.motd();
        // 2026-10：{version} 換成伺服器的 Minecraft 版本（例如 26.2），伺服器升級後不用改設定檔
        motd = lines.isEmpty() ? null : plugin.text().parse(String.join("<newline>", lines)
                .replace("{version}", plugin.getServer().getMinecraftVersion()));
    }

    public WorldLore lore() {
        return lore;
    }

    @EventHandler
    public void onQuit(final PlayerQuitEvent event) {
        if (worlds != null) {
            worlds.forget(event.getPlayer());
        }
    }

    @EventHandler
    public void onPing(final ServerListPingEvent event) {
        if (motd != null) {
            event.motd(motd);
        }
    }

    /** 新角色登場：旁白＋標題（每個新角色都是剛被押送船丟上岸的流放者）。 */
    public void greet(final Player player) {
        plugin.getServer().getScheduler().runTaskLater(plugin, () -> {
            if (!player.isOnline()) {
                return;
            }
            for (final String line : lore.arrivalLines()) {
                player.sendMessage(plugin.text().parse(line));
            }
        }, LINES_DELAY);
        if (lore.arrivalTitle().isEmpty()) {
            return;
        }
        plugin.getServer().getScheduler().runTaskLater(plugin, () -> {
            if (!player.isOnline()) {
                return;
            }
            player.showTitle(Title.title(plugin.text().parse(lore.arrivalTitle()), plugin.text().parse(lore.arrivalSubtitle()),
                    Title.Times.times(Duration.ofMillis(800), Duration.ofMillis(3500), Duration.ofMillis(1200))));
            player.playSound(player.getLocation(), Sound.BLOCK_BEACON_POWER_SELECT, 0.7f, 0.8f);
        }, TITLE_DELAY);
    }
}
