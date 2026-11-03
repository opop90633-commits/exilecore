package tw.exilecore.sponsor;

import org.bukkit.entity.Player;
import org.bukkit.plugin.Plugin;
import tw.exilecore.ExileCore;

import java.io.InputStream;
import java.lang.reflect.Method;
import java.nio.charset.StandardCharsets;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.TreeMap;
import java.util.UUID;
import java.util.function.Consumer;
import java.util.stream.Stream;

/**
 * 3D 寵物與背飾用 BetterModel 演骨架動畫（2026-11-03 照站長）：有裝 BetterModel 時，翅膀、光輪、寵物改成掛在「玩家」實體上的
 * BetterModel 模型（tools/gen_cosmetic_bbmodels.py 產生的 resources/cosmetics_bm/<art>.bbmodel，模型名稱 ec_<art>），
 * 翅膀分 inner／outer 骨頭拍動、寵物浮動＋尾巴／翅膀／觸手自己動；位置由客戶端跟著玩家畫，不用伺服器每 tick 算變形。
 * 這次之前：{@link ModelCosmetics} 把物品模型放在騎在玩家身上的 ItemDisplay、每 4 tick 算一次變形；沒裝 BetterModel、
 * config.yml 的 cosmetics.bettermodel false、或這裡任何一步接不上時，還是走那條（{@link #show} 回傳 false）。
 * <p>
 * 全部用反射（BetterModel 是 Java 25 編的、不一定有裝）。用到的 BetterModel API（v2 分支，api/src/main/java/kr/toxicity/model/api）：
 * <ul>
 *   <li>bukkit.BetterModelBukkit.platform()／eventBus()、bukkit.BukkitModelEventBus.subscribe(Plugin, Class, Consumer)</li>
 *   <li>event.ModelAssetsEvent.type()／addAsset(ModelAsset)、data.renderer.ModelRenderer.Type.GENERAL（玩家模型是 PLAYER）、data.ModelAsset.of(String, byte[])</li>
 *   <li>BetterModelPlatform.addReloadEndHandler(Consumer)、ReloadResult.Success.packResult()、pack.PackResult.stream()、PackByte.path()／bytes()</li>
 *   <li>BetterModel.model(String) → Optional&lt;ModelRenderer&gt;；bukkit.platform.BukkitAdapter.adapt(Player) → PlatformPlayer</li>
 *   <li>ModelRenderer.getOrCreate(PlatformEntity, TrackerModifier, Consumer&lt;EntityTracker&gt;)（沒有這個多載就用 getOrCreate(PlatformEntity)）</li>
 *   <li>tracker.TrackerModifier.builder().damageAnimation(false).damageTint(false).build()（沒有就 DEFAULT）：被打不閃紅、不抖</li>
 *   <li>tracker.EntityTracker.hideOption(EntityHideOption)＋EntityHideOption.FALSE：不把玩家本體藏起來（BetterModel 預設會把本體隱形、只剩模型）</li>
 *   <li>tracker.Tracker.animate(String, AnimationModifier, Runnable)／stopAnimation(String)／close()／isClosed()</li>
 *   <li>animation.AnimationModifier.builder().type(AnimationIterator.Type.LOOP).build()（沒有就 DEFAULT：照 bbmodel 裡寫的 loop）</li>
 * </ul>
 */
final class BetterModelCosmetics {

    /** 模型名稱的前綴（避免跟別人的模型撞名）。 */
    static final String PREFIX = "ec_";
    private static final String INDEX = "cosmetics_bm/index.txt";

    /** 整個伺服器只接一次（/ec reload 重建模組也不重接：ModelAssetsEvent 加兩次同名的模型會丟例外）。 */
    private static BetterModelCosmetics instance;
    private static boolean checked;

    private final ExileCore plugin;
    private final ClassLoader loader;
    /** 代號 → 種類（wings／halo／pet）。 */
    private final Map<String, String> kinds = new LinkedHashMap<>();
    /** 有 move 動畫的代號。 */
    private final Set<String> hasMove = new HashSet<>();
    /** 已經警告過的事（每種只吵一次）。 */
    private final Set<String> warned = new HashSet<>();

    // 反射拿到的方法（hook 時一次解析，之後每 tick 直接用）
    private Method modelOf, adaptPlayer, getOrCreate, getOrCreateSimple, hideOption, animate, stopAnimation, close, isClosed;
    /** 2026-11-03：EntityTracker.registry()、EntityTrackerRegistry.spawnIfNotSpawned(PlatformPlayer)（本人也看得到；沒有就 null）。 */
    private Method trackerRegistry, spawnIfNotSpawned;
    private Object trackerModifier, loopModifier, hideFalse;

    /** 正在掛著的：玩家 → 部位（back／pet）→ 掛了什麼。 */
    private static final class Shown {
        final String art;
        final Object tracker;
        String animation;
        /**
         * 2026-11-03：上次補送給本人的時間（呼叫次數）。-1000 = 還沒送過（第一次馬上送）；
         * 這次之前寫 Long.MIN_VALUE，calls − MIN_VALUE 溢位變負數，永遠不送——本人一直看不到自己的翅膀、寵物
         */
        long selfAt = -1000;

        Shown(final String art, final Object tracker, final String animation) {
            this.art = art;
            this.tracker = tracker;
            this.animation = animation;
        }
    }

    private final Map<UUID, Map<String, Shown>> shown = new HashMap<>();

    private BetterModelCosmetics(final ExileCore plugin, final ClassLoader loader) {
        this.plugin = plugin;
        this.loader = loader;
    }

    /** 有裝、有啟用 BetterModel、config 沒關就接上；接不上回傳 null（照舊用 ItemDisplay）。 */
    static synchronized BetterModelCosmetics create(final ExileCore plugin) {
        if (checked) {
            return instance;
        }
        checked = true;
        if (!plugin.getConfig().getBoolean("cosmetics.bettermodel", true)) {
            plugin.getSLF4JLogger().info("3D 時裝：cosmetics.bettermodel 關著，背飾與寵物照舊用展示實體");
            return null;
        }
        final Plugin bm = plugin.getServer().getPluginManager().getPlugin("BetterModel");
        if (bm == null || !bm.isEnabled()) {
            return null;
        }
        final BetterModelCosmetics cosmetics = new BetterModelCosmetics(plugin, bm.getClass().getClassLoader());
        try {
            cosmetics.hook();
            instance = cosmetics;
            plugin.getSLF4JLogger().info("3D 時裝：接上 BetterModel {}（{} 個背飾與寵物模型掛在玩家身上演動畫）", bm.getPluginMeta().getVersion(),
                    cosmetics.kinds.size());
            return cosmetics;
        } catch (final ReflectiveOperationException | RuntimeException | LinkageError e) {
            plugin.getSLF4JLogger().warn("3D 時裝：接不上 BetterModel（版本不合？），背飾與寵物照舊用展示實體：{}", e.toString());
            return null;
        }
    }

    private Class<?> type(final String name) throws ClassNotFoundException {
        return Class.forName(name, true, loader);
    }

    private static Method method(final Class<?> type, final String name, final Class<?>... params) throws NoSuchMethodException {
        return type.getMethod(name, params);
    }

    /** 試幾個方法簽名，都沒有回傳 null。 */
    private static Method methodOrNull(final Class<?> type, final String name, final Class<?>... params) {
        try {
            return type.getMethod(name, params);
        } catch (final NoSuchMethodException e) {
            return null;
        }
    }

    private void warnOnce(final String key, final String message, final Object... args) {
        if (warned.add(key)) {
            plugin.getSLF4JLogger().warn(message, args);
        }
    }

    /** 讀 index.txt 與每個 bbmodel、訂閱 ModelAssetsEvent（GENERAL）、接重讀完成（資源包）、解析演動畫要用的方法。 */
    private void hook() throws ReflectiveOperationException {
        final Map<String, byte[]> files = new TreeMap<>();
        try (InputStream in = plugin.getResource(INDEX)) {
            if (in == null) {
                throw new IllegalStateException("插件裡沒有 " + INDEX + "（tools/gen_cosmetic_bbmodels.py 沒跑？）");
            }
            for (final String line : new String(in.readAllBytes(), StandardCharsets.UTF_8).split("\n")) {
                final String[] parts = line.trim().split("\\s+");
                if (parts.length < 2 || parts[0].isEmpty()) {
                    continue;
                }
                final String art = parts[0];
                try (InputStream model = plugin.getResource("cosmetics_bm/" + art + ".bbmodel")) {
                    if (model == null) {
                        plugin.getSLF4JLogger().warn("3D 時裝：index.txt 有 {} 但沒有 cosmetics_bm/{}.bbmodel", art, art);
                        continue;
                    }
                    files.put(art, model.readAllBytes());
                }
                kinds.put(art, parts[1]);
                if (parts.length > 2 && List.of(parts[2].split(",")).contains("move")) {
                    hasMove.add(art);
                }
            }
        } catch (final java.io.IOException e) {
            throw new IllegalStateException(e);
        }
        if (files.isEmpty()) {
            throw new IllegalStateException(INDEX + " 是空的");
        }

        // 加模型：ModelAssetsEvent（type 是 GENERAL 那次）
        final Class<?> bukkit = type("kr.toxicity.model.api.bukkit.BetterModelBukkit");
        final Object platform = method(bukkit, "platform").invoke(null);
        final Object bus = method(bukkit, "eventBus").invoke(platform);
        final Class<?> assetsEvent = type("kr.toxicity.model.api.event.ModelAssetsEvent");
        final Class<?> rendererType = type("kr.toxicity.model.api.data.renderer.ModelRenderer$Type");
        final Class<?> modelAsset = type("kr.toxicity.model.api.data.ModelAsset");
        Object general;
        try {
            general = rendererType.getField("GENERAL").get(null);
        } catch (final NoSuchFieldException e) {
            general = null; // 舊版沒有這個名字：用「不是 PLAYER 的那個」
        }
        final Object generalType = general;
        final Object playerType = rendererType.getField("PLAYER").get(null);
        final Method ofBytes = method(modelAsset, "of", String.class, byte[].class);
        final Method subscribe = method(type("kr.toxicity.model.api.bukkit.BukkitModelEventBus"), "subscribe", Plugin.class, Class.class, Consumer.class);
        final Method eventType = method(assetsEvent, "type");
        final Method addAsset = method(assetsEvent, "addAsset", modelAsset);
        final Consumer<Object> onAssets = event -> {
            try {
                final Object eventKind = eventType.invoke(event);
                final boolean ours = generalType != null ? eventKind == generalType : eventKind != playerType;
                if (!ours) {
                    return;
                }
                for (final Map.Entry<String, byte[]> file : files.entrySet()) {
                    try {
                        addAsset.invoke(event, ofBytes.invoke(null, PREFIX + file.getKey(), file.getValue()));
                    } catch (final java.lang.reflect.InvocationTargetException e) {
                        warnOnce("asset:" + file.getKey(), "3D 時裝：加不進 BetterModel 的模型 {}：{}", file.getKey(), String.valueOf(e.getCause()));
                    }
                }
            } catch (final ReflectiveOperationException | RuntimeException e) {
                warnOnce("assets", "3D 時裝：加不進 BetterModel 的模型：{}", e.toString());
            }
        };
        subscribe.invoke(bus, plugin, assetsEvent, onAssets);

        // 重讀完：產生的資源包檔案交給網頁模組。跟 BetterModelEmotes 用同一個 key「bettermodel」：BetterModel 產生的是「一整包」
        // （玩家模型＋這些模型都在裡面），兩邊拿到的檔案一模一樣，同一個 key 第二次放進去 sha1 沒變就不會重送、也不會互相記成衝突。
        final Class<?> success = type("kr.toxicity.model.api.BetterModelPlatform$ReloadResult$Success");
        final Method packResult = method(success, "packResult");
        final Method stream = method(type("kr.toxicity.model.api.pack.PackResult"), "stream");
        final Class<?> packByte = type("kr.toxicity.model.api.pack.PackByte");
        final Method path = method(packByte, "path");
        final Method bytes = method(packByte, "bytes");
        final Method pathString = method(type("kr.toxicity.model.api.pack.PackPath"), "path");
        final Consumer<Object> onReload = result -> {
            if (!success.isInstance(result)) {
                return;
            }
            try {
                final Map<String, byte[]> pack = new TreeMap<>();
                final Object packed = packResult.invoke(result);
                try (Stream<?> all = (Stream<?>) stream.invoke(packed)) {
                    for (final Object each : all.toList()) {
                        pack.put((String) pathString.invoke(path.invoke(each)), (byte[]) bytes.invoke(each));
                    }
                }
                plugin.getServer().getScheduler().runTask(plugin, () -> {
                    // 重讀過：BetterModel 把舊的 tracker 都收掉了，我們記的也清掉（下一 tick 重掛）
                    shown.clear();
                    if (plugin.web() != null) {
                        plugin.web().setExternalPack("bettermodel", pack);
                    }
                });
            } catch (final ReflectiveOperationException | RuntimeException e) {
                warnOnce("pack", "3D 時裝：讀不到 BetterModel 產生的資源包：{}", e.toString());
            }
        };
        method(type("kr.toxicity.model.api.BetterModelPlatform"), "addReloadEndHandler", Consumer.class).invoke(platform, onReload);

        // 演動畫要用的
        modelOf = method(type("kr.toxicity.model.api.BetterModel"), "model", String.class);
        adaptPlayer = method(type("kr.toxicity.model.api.bukkit.platform.BukkitAdapter"), "adapt", Player.class);
        final Class<?> renderer = type("kr.toxicity.model.api.data.renderer.ModelRenderer");
        final Class<?> platformEntity = type("kr.toxicity.model.api.platform.PlatformEntity");
        final Class<?> modifierType = type("kr.toxicity.model.api.tracker.TrackerModifier");
        getOrCreate = methodOrNull(renderer, "getOrCreate", platformEntity, modifierType, Consumer.class);
        getOrCreateSimple = method(renderer, "getOrCreate", platformEntity);
        final Class<?> trackerType = type("kr.toxicity.model.api.tracker.Tracker");
        final Class<?> animationModifier = type("kr.toxicity.model.api.animation.AnimationModifier");
        animate = method(trackerType, "animate", String.class, animationModifier, Runnable.class);
        stopAnimation = methodOrNull(trackerType, "stopAnimation", String.class);
        close = method(trackerType, "close");
        isClosed = methodOrNull(trackerType, "isClosed");
        // TrackerModifier：被打不閃紅、不抖（沒有 builder 就用 DEFAULT）
        try {
            final Object builder = method(modifierType, "builder").invoke(null);
            final Class<?> builderType = builder.getClass();
            Object b = builder;
            final Method damageAnimation = methodOrNull(builderType, "damageAnimation", boolean.class);
            if (damageAnimation != null) {
                b = damageAnimation.invoke(b, false);
            }
            final Method damageTint = methodOrNull(builderType, "damageTint", boolean.class);
            if (damageTint != null) {
                b = damageTint.invoke(b, false);
            }
            trackerModifier = method(builderType, "build").invoke(b);
        } catch (final ReflectiveOperationException | RuntimeException e) {
            trackerModifier = modifierType.getField("DEFAULT").get(null);
        }
        // AnimationModifier：loop（沒有 builder 就 DEFAULT，照 bbmodel 裡寫的 loop）
        try {
            final Class<?> loopType = type("kr.toxicity.model.api.animation.AnimationIterator$Type");
            final Object loop = loopType.getField("LOOP").get(null); // enum 常數有自己的匿名類別，method 要用 Type 本身找
            final Object builder = method(animationModifier, "builder").invoke(null);
            final Object b = method(builder.getClass(), "type", loopType).invoke(builder, loop);
            loopModifier = method(b.getClass(), "build").invoke(b);
        } catch (final ReflectiveOperationException | RuntimeException e) {
            loopModifier = animationModifier.getField("DEFAULT").get(null);
        }
        // 2026-11-03 照站長（自己第三人稱看不到翅膀）：BetterModel 只送給「追蹤著這個實體」的人，玩家不會追蹤自己，
        //   所以本人看不到掛在自己身上的模型。用 registry().spawnIfNotSpawned(本人) 補送一份給本人（沒有這兩個方法就算了，別人照樣看得到）
        try {
            final Class<?> registryType = type("kr.toxicity.model.api.tracker.EntityTrackerRegistry");
            trackerRegistry = methodOrNull(type("kr.toxicity.model.api.tracker.EntityTracker"), "registry");
            spawnIfNotSpawned = methodOrNull(registryType, "spawnIfNotSpawned", type("kr.toxicity.model.api.platform.PlatformPlayer"));
        } catch (final ReflectiveOperationException | RuntimeException e) {
            trackerRegistry = null;
            spawnIfNotSpawned = null;
        }
        // 不隱藏本體：EntityHideOption.FALSE（沒有就整個不接，照舊用展示實體）
        try {
            final Class<?> hideType = type("kr.toxicity.model.api.tracker.EntityHideOption");
            hideFalse = hideType.getField("FALSE").get(null);
            hideOption = method(type("kr.toxicity.model.api.tracker.EntityTracker"), "hideOption", hideType);
        } catch (final ReflectiveOperationException | RuntimeException e) {
            // 2026-11-03：沒有「不隱藏本體」就不能掛在玩家身上（人會不見），整個不用、照舊用展示實體
            throw new IllegalStateException("這版 BetterModel 沒有 EntityHideOption.FALSE（掛模型時玩家本體會被藏起來）", e);
        }
    }

    /** 有這個代號的模型嗎（index.txt 列的）。 */
    boolean has(final String art) {
        return kinds.containsKey(art);
    }

    private String slotOf(final String art) {
        return "pet".equals(kinds.get(art)) ? "pet" : "back";
    }

    private boolean closed(final Object tracker) {
        if (isClosed == null) {
            return false;
        }
        try {
            return (Boolean) isClosed.invoke(tracker);
        } catch (final ReflectiveOperationException | RuntimeException e) {
            return true;
        }
    }

    private void closeQuietly(final Object tracker) {
        try {
            close.invoke(tracker);
        } catch (final ReflectiveOperationException | RuntimeException ignored) {
            // 已經關了
        }
    }

    private void unhide(final Object tracker) {
        if (hideOption == null) {
            return;
        }
        try {
            hideOption.invoke(tracker, hideFalse);
        } catch (final ReflectiveOperationException | RuntimeException e) {
            warnOnce("hide", "3D 時裝：設不了 BetterModel 的「不隱藏本體」：{}", e.toString());
        }
    }

    /**
     * 把 art 掛在玩家身上演動畫（moving 時演 move，沒有 move 或停下來演 idle）；同一個部位（背飾／寵物）換了代號就把舊的收掉。
     * 每 tick 叫也沒關係：已經掛著、動畫也對就直接回 true。回傳 false = 掛不上（叫的人照舊用展示實體）。
     */
    boolean show(final Player player, final String art, final boolean moving) {
        if (!kinds.containsKey(art)) {
            return false;
        }
        final String slot = slotOf(art);
        final Map<String, Shown> slots = shown.computeIfAbsent(player.getUniqueId(), k -> new HashMap<>());
        final Shown current = slots.get(slot);
        final String animation = moving && hasMove.contains(art) ? "move" : "idle";
        try {
            if (current != null && current.art.equals(art) && !closed(current.tracker)) {
                showSelf(player, current); // 換世界、重生之後本人那份會不見：隔一陣子補一次
                if (!animation.equals(current.animation)) {
                    if (stopAnimation != null) {
                        stopAnimation.invoke(current.tracker, current.animation);
                    }
                    animate.invoke(current.tracker, animation, loopModifier, (Runnable) () -> { });
                    current.animation = animation;
                }
                return true;
            }
            if (current != null) {
                slots.remove(slot);
                closeQuietly(current.tracker);
            }
            final Optional<?> renderer = (Optional<?>) modelOf.invoke(null, PREFIX + art);
            if (renderer.isEmpty()) {
                warnOnce("model:" + art, "3D 時裝：BetterModel 裡沒有模型 {}{}（還沒重讀完？），{} 先用展示實體", PREFIX, art, art);
                return false;
            }
            final Object platformPlayer = adaptPlayer.invoke(null, player);
            final Object tracker;
            if (getOrCreate != null) {
                final Consumer<Object> preUpdate = this::unhide; // 第一次送出去之前就設好：不把玩家本體藏起來
                tracker = getOrCreate.invoke(renderer.get(), platformPlayer, trackerModifier, preUpdate);
            } else {
                tracker = getOrCreateSimple.invoke(renderer.get(), platformPlayer);
            }
            unhide(tracker); // 已經存在的 tracker（getOrCreate 拿到舊的）也設一次
            final boolean started = (Boolean) animate.invoke(tracker, animation, loopModifier, (Runnable) () -> { });
            if (!started) {
                warnOnce("anim:" + art + ":" + animation, "3D 時裝：模型 {}{} 沒有動畫 {}", PREFIX, art, animation);
                if (!"idle".equals(animation) && (Boolean) animate.invoke(tracker, "idle", loopModifier, (Runnable) () -> { })) {
                    slots.put(slot, new Shown(art, tracker, "idle"));
                    return true;
                }
                closeQuietly(tracker);
                return false;
            }
            final Shown fresh = new Shown(art, tracker, animation);
            slots.put(slot, fresh);
            showSelf(player, fresh);
            return true;
        } catch (final ReflectiveOperationException | RuntimeException | LinkageError e) {
            warnOnce("show:" + art, "3D 時裝：BetterModel 掛不上 {}（照舊用展示實體）：{}", art, e.toString());
            slots.remove(slot);
            return false;
        }
    }

    private long calls;

    /** 2026-11-03：把掛在玩家身上的模型也送給本人（第三人稱看得到自己的翅膀、寵物）；同一個最多每 20 次呼叫補一次。 */
    private void showSelf(final Player player, final Shown shown) {
        calls++;
        if (trackerRegistry == null || spawnIfNotSpawned == null || calls - shown.selfAt < 20) {
            return;
        }
        shown.selfAt = calls;
        try {
            final Object registry = trackerRegistry.invoke(shown.tracker);
            if (registry != null) {
                spawnIfNotSpawned.invoke(registry, adaptPlayer.invoke(null, player));
            }
        } catch (final ReflectiveOperationException | RuntimeException | LinkageError e) {
            warnOnce("self", "3D 時裝：BetterModel 的模型送不到本人（自己看不到，別人照樣看得到）：{}", e.toString());
            spawnIfNotSpawned = null;
        }
    }

    /** 收掉這個玩家身上的 art（沒掛著就什麼都不做）。 */
    void hide(final Player player, final String art) {
        final Map<String, Shown> slots = shown.get(player.getUniqueId());
        if (slots == null) {
            return;
        }
        final String slot = slotOf(art);
        final Shown current = slots.get(slot);
        if (current != null && current.art.equals(art)) {
            slots.remove(slot);
            closeQuietly(current.tracker);
        }
        if (slots.isEmpty()) {
            shown.remove(player.getUniqueId());
        }
    }

    /** 這個玩家身上的全部收掉（離線、模組停掉）。 */
    void forget(final UUID player) {
        final Map<String, Shown> slots = shown.remove(player);
        if (slots != null) {
            for (final Shown each : slots.values()) {
                closeQuietly(each.tracker);
            }
        }
    }

    void stop() {
        for (final UUID id : List.copyOf(shown.keySet())) {
            forget(id);
        }
    }

    /** 給 /ec 查看：現在有幾個玩家用 BetterModel 掛著。 */
    int active() {
        return shown.size();
    }
}
