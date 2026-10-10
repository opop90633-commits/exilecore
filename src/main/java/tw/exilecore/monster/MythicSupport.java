package tw.exilecore.monster;

import org.bukkit.plugin.Plugin;
import tw.exilecore.ExileCore;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Path;
import java.util.Set;

/**
 * 建立 MythicMobs 橋接：有裝而且版本相容就用 {@link MythicBridge}，否則 {@link NoMythic}。
 * 連接時把範例怪物檔（區域 Boss、地圖 Boss 與幾隻小怪）裝進 MythicMobs 的 Mobs 資料夾（{@link MythicSamples}）：
 * 沒有就裝；以前版本的範例沒改過就換成新版；被改過的不動，新版放旁邊的 .new。
 */
public final class MythicSupport {

    private MythicSupport() {
    }

    public static MythicHook create(final ExileCore plugin, final MonsterService monsters) {
        final Plugin mythic = plugin.getServer().getPluginManager().getPlugin("MythicMobs");
        if (mythic == null || !mythic.isEnabled()) {
            plugin.getSLF4JLogger().info("沒有 MythicMobs：區域 Boss 用原版生物，mythic: 的刷怪項目會跳過");
            return NoMythic.INSTANCE;
        }
        final MythicBridge bridge;
        try {
            bridge = new MythicBridge(plugin, monsters);
            bridge.register();
        } catch (final ReflectiveOperationException | RuntimeException | LinkageError e) {
            plugin.getSLF4JLogger().warn("MythicMobs {} 的 API 對不上（ExileCore 對照 5.13），改用原版生物：{}",
                    mythic.getPluginMeta().getVersion(), e.toString());
            return NoMythic.INSTANCE;
        }
        plugin.getSLF4JLogger().info("已連接 MythicMobs {}（目前 {} 種怪物）", mythic.getPluginMeta().getVersion(), bridge.mobIds().size());
        installSamples(plugin, mythic);
        return bridge;
    }

    /** 範例檔不存在就裝、舊版沒改過就換新，有變動就請 MythicMobs 重新載入（你改過的內容不會被蓋掉）。 */
    private static void installSamples(final ExileCore plugin, final Plugin mythic) {
        boolean changed = false;
        for (final String name : MythicSamples.FILES) {
            final Path target = mythic.getDataFolder().toPath().resolve("Mobs").resolve(name);
            try (InputStream in = plugin.getResource("mythicmobs/" + name)) {
                if (in == null) {
                    continue;
                }
                switch (MythicSamples.sync(target, in.readAllBytes(), MythicSamples.PREVIOUS.getOrDefault(name, Set.of()))) {
                    case ADDED -> {
                        changed = true;
                        plugin.getSLF4JLogger().info("已安裝 MythicMobs 範例怪物：{}", target);
                    }
                    case UPDATED -> {
                        changed = true;
                        plugin.getSLF4JLogger().info("MythicMobs 範例怪物 {} 換成新版：Boss 只留外觀，招式都交給 Boss 招式引擎（不會再跟招式同時出手）", name);
                    }
                    case KEPT -> plugin.getSLF4JLogger().warn("MythicMobs 的 Mobs/{} 你改過，沒有自動更新；新版放在旁邊的 {}.new："
                            + "Boss 拿掉了會打人、推人、召喚的計時技能（throw、leap、pull…），不然會跟 Boss 招式同時出現", name, name);
                    case CURRENT -> {
                    }
                }
            } catch (final IOException e) {
                plugin.getSLF4JLogger().warn("安裝 MythicMobs 範例檔 {} 失敗：{}", name, e.toString());
            }
        }
        if (changed) {
            plugin.getSLF4JLogger().info("重新載入 MythicMobs……");
            // 伺服器啟動完成後才執行（排程在第一個 tick 才開始跑）
            plugin.getServer().getScheduler().runTask(plugin, () ->
                    plugin.getServer().dispatchCommand(plugin.getServer().getConsoleSender(), "mm reload"));
        }
    }
}
