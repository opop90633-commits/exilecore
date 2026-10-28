package tw.exilecore.hook;

import org.bukkit.entity.LivingEntity;
import org.bukkit.entity.Player;
import tw.exilecore.ExileCore;

import java.lang.reflect.Constructor;
import java.lang.reflect.Method;
import java.util.Collection;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/**
 * 2026-10-28 照 PoE2 Killing Palm「你周圍的可撲殺敵人會被重點標示」（原作是發光的外框）：可以撲殺的敵人只在你的畫面上發光。
 * <p>
 * 發光是實體的共用旗標（{@code setGlowing} 大家都看得到），所以改成只送給這個玩家一個實體資料封包（entity metadata，
 * 第 0 格的旗標加上 0x40 發光），別人收到的照舊。要裝 PacketEvents（跟物品的「對你的技能」提示一樣，見 {@link TooltipHook}）；
 * 沒裝、或這版 PacketEvents 對不上時 {@link #active()} 是 false，OriginalSkills 照舊在頭上冒紅點。
 * <p>
 * 這裡用反射呼叫 PacketEvents（不 import 它的型別）：沒裝也能載入這個類別、編譯也不需要 PacketEvents，
 * 版本不合（類別、方法找不到）就記一行警告、退回紅點，不會讓插件壞掉。
 * 伺服器自己送的實體資料（例如著火、隱形變了）會把發光蓋掉，所以每 {@link tw.exilecore.skill.KillingPalm#HIGHLIGHT_TICKS} tick 重送一次；
 * 不能撲殺了（或走遠了）就送一次原本的旗標把外框拿掉。
 */
public final class CullGlow {

    /** 實體旗標（metadata 第 0 格）的各個位元。 */
    static final byte ON_FIRE = 0x01;
    static final byte SNEAKING = 0x02;
    static final byte SPRINTING = 0x08;
    static final byte SWIMMING = 0x10;
    static final byte INVISIBLE = 0x20;
    static final byte GLOWING = 0x40;
    static final byte GLIDING = (byte) 0x80;

    /** 看的人 → 這個人畫面上我們讓它發光的實體（實體編號 → 實體）。主執行緒用。 */
    private static final Map<UUID, Map<Integer, LivingEntity>> SHOWN = new HashMap<>();

    private static volatile Handles handles;

    private CullGlow() {
    }

    /** 反射拿到的 PacketEvents 方法。 */
    private record Handles(Object playerManager, Method sendPacket, Constructor<?> metadata, Constructor<?> entityData, Object byteType) {
    }

    /** 有裝 PacketEvents 就啟用；回傳有沒有啟用。 */
    public static boolean start(final ExileCore core) {
        if (!core.getServer().getPluginManager().isPluginEnabled("packetevents")) {
            core.getSLF4JLogger().info("斷魂掌可撲殺敵人的外框：沒有裝 PacketEvents，照舊用頭上的紅點");
            return false;
        }
        try {
            handles = resolve();
        } catch (final Throwable e) { // 版本對不上：不影響其他功能
            handles = null;
            core.getSLF4JLogger().warn("斷魂掌可撲殺敵人的外框：PacketEvents 對不上（{}），照舊用頭上的紅點", e.toString());
            return false;
        }
        core.getSLF4JLogger().info("斷魂掌可撲殺敵人的外框：已啟用（PacketEvents）");
        return true;
    }

    /** 關閉（外框不另外清：玩家重新看到實體時伺服器會送原本的資料）。 */
    public static void stop() {
        handles = null;
        SHOWN.clear();
    }

    /** 能不能用外框（有 PacketEvents 而且對得上）。 */
    public static boolean active() {
        return handles != null;
    }

    /** 下線：清掉記錄。 */
    public static void forget(final UUID viewer) {
        SHOWN.remove(viewer);
    }

    /**
     * 主執行緒：這個玩家現在看得到外框的實體換成 {@code glowing}（新的、還在的都重送一次發光；不在裡面了的送原本的旗標拿掉外框）。
     * 送失敗（PacketEvents 中途出問題）就關掉外框，之後退回紅點。
     */
    public static void update(final Player viewer, final Collection<LivingEntity> glowing) {
        final Handles h = handles;
        if (h == null || viewer == null) {
            return;
        }
        final Map<Integer, LivingEntity> before = SHOWN.getOrDefault(viewer.getUniqueId(), Map.of());
        final Map<Integer, LivingEntity> now = new HashMap<>();
        for (final LivingEntity entity : glowing) {
            now.put(entity.getEntityId(), entity);
        }
        try {
            for (final Integer id : cleared(before.keySet(), now.keySet())) {
                final LivingEntity entity = before.get(id);
                if (entity != null && entity.isValid() && entity.getWorld().equals(viewer.getWorld())) {
                    send(h, viewer, id, flags(entity, false));
                }
            }
            for (final Map.Entry<Integer, LivingEntity> entry : now.entrySet()) {
                send(h, viewer, entry.getKey(), flags(entry.getValue(), true));
            }
        } catch (final Throwable e) {
            handles = null;
            SHOWN.clear();
            return;
        }
        if (now.isEmpty()) {
            SHOWN.remove(viewer.getUniqueId());
        } else {
            SHOWN.put(viewer.getUniqueId(), now);
        }
    }

    /** 上次發光、這次不發光的實體編號（要送原本的旗標拿掉外框）。 */
    static Set<Integer> cleared(final Set<Integer> before, final Set<Integer> now) {
        final Set<Integer> out = new HashSet<>(before);
        out.removeAll(now);
        return out;
    }

    /** 實體旗標：照實體現在的狀態，{@code outline} 再加上發光（實體本來就在發光的也保留）。 */
    static byte flags(final boolean onFire, final boolean sneaking, final boolean sprinting, final boolean swimming,
                      final boolean invisible, final boolean glowing, final boolean gliding, final boolean outline) {
        int flags = 0;
        if (onFire) {
            flags |= ON_FIRE;
        }
        if (sneaking) {
            flags |= SNEAKING;
        }
        if (sprinting) {
            flags |= SPRINTING;
        }
        if (swimming) {
            flags |= SWIMMING;
        }
        if (invisible) {
            flags |= INVISIBLE;
        }
        if (glowing || outline) {
            flags |= GLOWING;
        }
        if (gliding) {
            flags |= GLIDING & 0xFF;
        }
        return (byte) flags;
    }

    private static byte flags(final LivingEntity entity, final boolean outline) {
        return flags(entity.getFireTicks() > 0 || entity.isVisualFire(), entity.isSneaking(), false, entity.isSwimming(),
                entity.isInvisible(), entity.isGlowing(), entity.isGliding(), outline);
    }

    private static void send(final Handles h, final Player viewer, final int entityId, final byte flags) throws ReflectiveOperationException {
        final Object data = h.entityData().newInstance(0, h.byteType(), flags);
        final Object packet = h.metadata().newInstance(entityId, List.of(data));
        h.sendPacket().invoke(h.playerManager(), viewer, packet);
    }

    /** 找 PacketEvents 2.x 的：PacketEvents.getAPI().getPlayerManager().sendPacket(玩家, 封包)、WrapperPlayServerEntityMetadata、EntityData、EntityDataTypes.BYTE。 */
    private static Handles resolve() throws ReflectiveOperationException {
        final ClassLoader loader = CullGlow.class.getClassLoader();
        final Class<?> events = Class.forName("com.github.retrooper.packetevents.PacketEvents", true, loader);
        final Method getApi = events.getMethod("getAPI");
        final Object api = getApi.invoke(null);
        final Method getPlayerManager = getApi.getReturnType().getMethod("getPlayerManager");
        final Object playerManager = getPlayerManager.invoke(api);
        final Class<?> metadataClass = Class.forName("com.github.retrooper.packetevents.wrapper.play.server.WrapperPlayServerEntityMetadata", true, loader);
        final Constructor<?> metadata = metadataClass.getConstructor(int.class, List.class);
        Method sendPacket = null;
        for (final Method method : getPlayerManager.getReturnType().getMethods()) {
            final Class<?>[] params = method.getParameterTypes();
            if (method.getName().equals("sendPacket") && params.length == 2 && params[0] == Object.class
                    && params[1].isAssignableFrom(metadataClass)) {
                sendPacket = method;
                break;
            }
        }
        if (sendPacket == null) {
            throw new NoSuchMethodException("PlayerManager.sendPacket(Object, PacketWrapper)");
        }
        final Class<?> dataClass = Class.forName("com.github.retrooper.packetevents.protocol.entity.data.EntityData", true, loader);
        final Class<?> typeClass = Class.forName("com.github.retrooper.packetevents.protocol.entity.data.EntityDataType", true, loader);
        final Constructor<?> entityData = dataClass.getConstructor(int.class, typeClass, Object.class);
        final Object byteType = Class.forName("com.github.retrooper.packetevents.protocol.entity.data.EntityDataTypes", true, loader)
                .getField("BYTE").get(null);
        return new Handles(playerManager, sendPacket, metadata, entityData, byteType);
    }
}
