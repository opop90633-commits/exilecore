package tw.exilecore.sponsor;

import org.bukkit.entity.Player;
import org.bukkit.plugin.Plugin;
import tw.exilecore.ExileCore;

import java.io.InputStream;
import java.lang.reflect.Method;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.Optional;
import java.util.TreeMap;
import java.util.function.Consumer;
import java.util.stream.Stream;

/**
 * 動作用 BetterModel 演（2026-10）：有裝 BetterModel 時，動作改成玩家自己的皮膚做的 12 部位動畫（手腳都會動）。
 * <ul>
 *   <li>玩家模型 {@value #MODEL}（resources/emotes/exile_emotes.bbmodel，tools/gen_emotes.py 產生），BetterModel 讀資料時加進去。</li>
 *   <li>動畫名稱就是動作代號：sidestep、kick_ball、bear_shake、head_bob。</li>
 *   <li>BetterModel 自己不送資源包：它每次重讀完，把產生的檔案交給網頁模組併進我們的介面資源包。</li>
 * </ul>
 * 全部用反射：BetterModel 是 Java 25 編的、也不一定有裝，沒裝時這個類別什麼都不做（{@link #create} 回傳 null）。
 */
final class BetterModelEmotes {

    static final String MODEL = "exile_emotes";

    private final ExileCore plugin;
    private final ClassLoader loader;
    /** 正在演的動畫怎麼收掉（2026-10：王之坐姿走開就站起來）。 */
    private final Map<UUID, Runnable> closers = new ConcurrentHashMap<>();

    private BetterModelEmotes(final ExileCore plugin, final ClassLoader loader) {
        this.plugin = plugin;
        this.loader = loader;
    }

    /** 有裝、有啟用 BetterModel 就接上；沒有回傳 null。 */
    static BetterModelEmotes create(final ExileCore plugin) {
        final Plugin bm = plugin.getServer().getPluginManager().getPlugin("BetterModel");
        if (bm == null || !bm.isEnabled()) {
            return null;
        }
        final BetterModelEmotes emotes = new BetterModelEmotes(plugin, bm.getClass().getClassLoader());
        try {
            emotes.hook();
            plugin.getSLF4JLogger().info("動作：接上 BetterModel {}（玩家模型 {}，用玩家自己的皮膚演）", bm.getPluginMeta().getVersion(), MODEL);
            return emotes;
        } catch (final ReflectiveOperationException | RuntimeException | LinkageError e) {
            plugin.getSLF4JLogger().warn("動作：接不上 BetterModel（版本不合？），照舊用原版的動作：{}", e.toString());
            return null;
        }
    }

    private Class<?> type(final String name) throws ClassNotFoundException {
        return Class.forName(name, true, loader);
    }

    private static Method method(final Class<?> type, final String name, final Class<?>... params) throws NoSuchMethodException {
        return type.getMethod(name, params);
    }

    /** 加玩家模型、接重讀完成（資源包）。 */
    private void hook() throws ReflectiveOperationException {
        final Class<?> bukkit = type("kr.toxicity.model.api.bukkit.BetterModelBukkit");
        final Object platform = method(bukkit, "platform").invoke(null);
        final Object bus = method(bukkit, "eventBus").invoke(platform);
        final Class<?> assetsEvent = type("kr.toxicity.model.api.event.ModelAssetsEvent");
        final Class<?> rendererType = type("kr.toxicity.model.api.data.renderer.ModelRenderer$Type");
        final Class<?> modelAsset = type("kr.toxicity.model.api.data.ModelAsset");
        final Object playerType = rendererType.getField("PLAYER").get(null);
        final Method ofBytes = method(modelAsset, "of", String.class, byte[].class);
        final byte[] bbmodel;
        try (InputStream in = plugin.getResource("emotes/" + MODEL + ".bbmodel")) {
            if (in == null) {
                throw new IllegalStateException("插件裡沒有 emotes/" + MODEL + ".bbmodel");
            }
            bbmodel = in.readAllBytes();
        } catch (final java.io.IOException e) {
            throw new IllegalStateException(e);
        }
        final Method subscribe = method(type("kr.toxicity.model.api.bukkit.BukkitModelEventBus"), "subscribe", Plugin.class, Class.class, Consumer.class);
        final Method eventType = method(assetsEvent, "type");
        final Method addAsset = method(assetsEvent, "addAsset", modelAsset);
        final Consumer<Object> onAssets = event -> {
            try {
                if (eventType.invoke(event) == playerType) {
                    addAsset.invoke(event, ofBytes.invoke(null, MODEL, bbmodel));
                }
            } catch (final ReflectiveOperationException e) {
                plugin.getSLF4JLogger().warn("動作：加不進 BetterModel 的玩家模型：{}", e.toString());
            }
        };
        subscribe.invoke(bus, plugin, assetsEvent, onAssets);
        // 重讀完：產生的資源包檔案交給網頁模組
        final Class<?> success = type("kr.toxicity.model.api.BetterModelPlatform$ReloadResult$Success");
        final Method packResult = method(success, "packResult");
        final Class<?> packResultType = type("kr.toxicity.model.api.pack.PackResult");
        final Method stream = method(packResultType, "stream");
        final Class<?> packByte = type("kr.toxicity.model.api.pack.PackByte");
        final Method path = method(packByte, "path");
        final Method bytes = method(packByte, "bytes");
        final Method pathString = method(type("kr.toxicity.model.api.pack.PackPath"), "path");
        final Consumer<Object> onReload = result -> {
            if (!success.isInstance(result)) {
                return;
            }
            try {
                final Map<String, byte[]> files = new TreeMap<>();
                final Object pack = packResult.invoke(result);
                try (Stream<?> all = (Stream<?>) stream.invoke(pack)) {
                    for (final Object each : all.toList()) {
                        files.put((String) pathString.invoke(path.invoke(each)), (byte[]) bytes.invoke(each));
                    }
                }
                plugin.getServer().getScheduler().runTask(plugin, () -> {
                    if (plugin.web() != null) {
                        plugin.web().setExternalPack("bettermodel", files);
                    }
                });
            } catch (final ReflectiveOperationException | RuntimeException e) {
                plugin.getSLF4JLogger().warn("動作：讀不到 BetterModel 產生的資源包：{}", e.toString());
            }
        };
        method(type("kr.toxicity.model.api.BetterModelPlatform"), "addReloadEndHandler", Consumer.class).invoke(platform, onReload);
    }

    /** 演一次（有這個動畫才演）；演不了回傳 false（照舊用原版的動作）。 */
    boolean play(final Player player, final String animation) {
        try {
            final Class<?> api = type("kr.toxicity.model.api.BetterModel");
            final Optional<?> renderer = (Optional<?>) method(api, "limb", String.class).invoke(null, MODEL);
            if (renderer.isEmpty()) {
                return false;
            }
            final Class<?> adapter = type("kr.toxicity.model.api.bukkit.platform.BukkitAdapter");
            final Object platformPlayer = method(adapter, "adapt", Player.class).invoke(null, player);
            final Class<?> platformEntity = type("kr.toxicity.model.api.platform.PlatformEntity");
            final Object renderObject = renderer.get();
            final Object tracker = method(type("kr.toxicity.model.api.data.renderer.ModelRenderer"), "getOrCreate", platformEntity)
                    .invoke(renderObject, platformPlayer);
            final Class<?> trackerType = type("kr.toxicity.model.api.tracker.Tracker");
            final Class<?> modifier = type("kr.toxicity.model.api.animation.AnimationModifier");
            final Object once = modifier.getField("DEFAULT_WITH_PLAY_ONCE").get(null);
            final Method close = method(trackerType, "close");
            final UUID id = player.getUniqueId();
            final Runnable[] self = new Runnable[1];
            final Runnable done = () -> {
                closers.remove(id, self[0]);
                try {
                    close.invoke(tracker);
                } catch (final ReflectiveOperationException | RuntimeException ignored) {
                    // 已經關了
                }
            };
            self[0] = done;
            closers.put(id, done);
            final boolean started = (Boolean) method(trackerType, "animate", String.class, modifier, Runnable.class)
                    .invoke(tracker, animation, once, done);
            if (!started) {
                done.run();
            }
            return started;
        } catch (final ReflectiveOperationException | RuntimeException | LinkageError e) {
            plugin.getSLF4JLogger().warn("動作：BetterModel 演不出 {}：{}", animation, e.toString());
            return false;
        }
    }

    /** 這個玩家正在演的動畫馬上收掉（回到原本的樣子）；沒在演就什麼都不做。 */
    void stop(final Player player) {
        final Runnable closer = closers.remove(player.getUniqueId());
        if (closer != null) {
            closer.run();
        }
    }
}
