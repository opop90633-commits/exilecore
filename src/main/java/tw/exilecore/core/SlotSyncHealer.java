package tw.exilecore.core;

import org.bukkit.entity.Player;
import org.slf4j.Logger;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.List;
import java.util.Map;

/**
 * 2026-10 效能（spark：原版比對背包的 ItemLore.equals 佔主執行緒兩成）。
 * <p>
 * 原版每 tick 拿「上次送給客戶端的那一份」（lastSlots、remoteSlots、身上裝備的 lastEquipmentItems）跟格子裡現在的物品比，
 * 兩邊的資料元件是同一份就一下比完；只有「內容一樣、但物品被重寫過」（editMeta、setItemMeta、選單重畫）時要一行一行比說明。
 * 比完一樣原版<b>不會</b>更新它記的那一份，所以這一格之後每個 tick 都要整段重比，直到內容真的變了——背包、選單裡說明很長的
 * 東西一多，就是兩成的主執行緒。
 * <p>
 * 這裡每秒掃一次：內容一樣、但不是同一份的格子，把原版記的那一份換成現在這件的複本（複本跟原本共用資料元件），
 * 之後就回到一下比完。內容一樣才換，所以不會漏送任何變化給客戶端。
 * <p>
 * 用反射碰原版的欄位（Paper 執行時是 Mojang 的名稱）；對不上（之後的版本改名）只記一次、之後不再試，最多就是沒有這個優化。
 */
public final class SlotSyncHealer {

    private final Logger logger;
    private boolean broken;
    private boolean ready;

    private Method getHandle;
    private Field containerMenu;
    private Field inventoryMenu;
    private Field slots;
    private Field lastSlots;
    private Field remoteSlots;
    private Method slotGetItem;
    private Method matches;
    private Method copy;
    private Method remoteMatches;
    private Method remoteForce;
    private Field lastEquipment; // 可能沒有（版本不同）：沒有就只處理選單

    private long healed;

    public SlotSyncHealer(final Logger logger) {
        this.logger = logger;
    }

    /** 到目前為止換掉幾格（/ec perf 看得到效果用）。 */
    public long healed() {
        return healed;
    }

    public void tick(final Iterable<? extends Player> players) {
        if (broken) {
            return;
        }
        for (final Player player : players) {
            try {
                heal(player);
            } catch (final ReflectiveOperationException | RuntimeException | LinkageError e) {
                broken = true;
                logger.warn("背包比對的優化關掉了（這個 Paper 版本對不上：{}）：不影響遊戲，只是少了這個優化", e.toString());
                return;
            }
        }
    }

    private void heal(final Player player) throws ReflectiveOperationException {
        if (!ready) {
            resolve(player);
        }
        final Object handle = getHandle.invoke(player);
        final Object inventory = inventoryMenu.get(handle);
        healMenu(inventory);
        final Object open = containerMenu.get(handle);
        if (open != null && open != inventory) {
            healMenu(open);
        }
        if (lastEquipment != null) {
            healEquipment(player, handle);
        }
    }

    @SuppressWarnings("unchecked")
    private void healMenu(final Object menu) throws ReflectiveOperationException {
        final List<Object> slotList = (List<Object>) slots.get(menu);
        final List<Object> last = (List<Object>) lastSlots.get(menu);
        final List<Object> remote = (List<Object>) remoteSlots.get(menu);
        final int size = Math.min(slotList.size(), Math.min(last.size(), remote.size()));
        for (int i = 0; i < size; i++) {
            final Object current = slotGetItem.invoke(slotList.get(i));
            final Object before = last.get(i);
            if (before != current && !sameData(before, current) && (boolean) matches.invoke(null, before, current)) {
                last.set(i, copy.invoke(current));
                final Object remoteSlot = remote.get(i);
                if ((boolean) remoteMatches.invoke(remoteSlot, current)) {
                    remoteForce.invoke(remoteSlot, current);
                }
                healed++;
            }
        }
    }

    @SuppressWarnings("unchecked")
    private void healEquipment(final Player player, final Object handle) throws ReflectiveOperationException {
        final Map<Object, Object> map = (Map<Object, Object>) lastEquipment.get(handle);
        if (map == null || map.isEmpty()) {
            return;
        }
        final Method getItemBySlot = itemBySlot(handle);
        for (final Map.Entry<Object, Object> entry : map.entrySet()) {
            final Object before = entry.getValue();
            final Object current = getItemBySlot.invoke(handle, entry.getKey());
            if (before != current && !sameData(before, current) && (boolean) matches.invoke(null, before, current)) {
                entry.setValue(copy.invoke(current));
                healed++;
            }
        }
    }

    private Method bySlot;

    private Method itemBySlot(final Object handle) throws ReflectiveOperationException {
        if (bySlot == null) {
            final Class<?> slotType = Class.forName("net.minecraft.world.entity.EquipmentSlot", false, handle.getClass().getClassLoader());
            bySlot = findMethod(handle.getClass(), "getItemBySlot", slotType);
        }
        return bySlot;
    }

    /** 兩件的資料元件是不是同一份（複本共用同一份 patch：是的話原版本來就一下比完，不用換）。 */
    private Field components;
    private Field patch;

    private boolean sameData(final Object a, final Object b) throws ReflectiveOperationException {
        if (components == null) {
            return false;
        }
        final Object left = components.get(a);
        final Object right = components.get(b);
        if (left == right) {
            return true;
        }
        if (left == null || right == null || left.getClass() != right.getClass()) {
            return false;
        }
        if (patch == null) {
            try {
                patch = findField(left.getClass(), "patch");
            } catch (final NoSuchFieldException e) {
                components = null; // 對不上：每一格照換（多做一點事，結果一樣）
                return false;
            }
        }
        return patch.get(left) == patch.get(right);
    }

    private void resolve(final Player player) throws ReflectiveOperationException {
        getHandle = player.getClass().getMethod("getHandle");
        final Object handle = getHandle.invoke(player);
        final ClassLoader loader = handle.getClass().getClassLoader();
        final Class<?> menuType = Class.forName("net.minecraft.world.inventory.AbstractContainerMenu", false, loader);
        final Class<?> stackType = Class.forName("net.minecraft.world.item.ItemStack", false, loader);
        final Class<?> slotType = Class.forName("net.minecraft.world.inventory.Slot", false, loader);
        final Class<?> remoteType = Class.forName("net.minecraft.world.inventory.RemoteSlot", false, loader);
        containerMenu = findField(handle.getClass(), "containerMenu");
        inventoryMenu = findField(handle.getClass(), "inventoryMenu");
        slots = findField(menuType, "slots");
        lastSlots = findField(menuType, "lastSlots");
        remoteSlots = findField(menuType, "remoteSlots");
        slotGetItem = slotType.getMethod("getItem");
        matches = stackType.getMethod("matches", stackType, stackType);
        copy = stackType.getMethod("copy");
        remoteMatches = remoteType.getMethod("matches", stackType);
        remoteForce = remoteType.getMethod("force", stackType);
        try {
            components = findField(stackType, "components");
        } catch (final NoSuchFieldException ignored) {
            components = null;
        }
        try {
            lastEquipment = findField(handle.getClass(), "lastEquipmentItems");
            if (!Map.class.isAssignableFrom(lastEquipment.getType())) {
                lastEquipment = null;
            }
        } catch (final NoSuchFieldException ignored) {
            lastEquipment = null;
        }
        ready = true;
    }

    private static Field findField(final Class<?> start, final String name) throws NoSuchFieldException {
        for (Class<?> type = start; type != null; type = type.getSuperclass()) {
            try {
                final Field field = type.getDeclaredField(name);
                field.setAccessible(true);
                return field;
            } catch (final NoSuchFieldException ignored) {
                // 往上找
            }
        }
        throw new NoSuchFieldException(start.getName() + "." + name);
    }

    private static Method findMethod(final Class<?> start, final String name, final Class<?>... parameters) throws NoSuchMethodException {
        for (Class<?> type = start; type != null; type = type.getSuperclass()) {
            try {
                final Method method = type.getDeclaredMethod(name, parameters);
                method.setAccessible(true);
                return method;
            } catch (final NoSuchMethodException ignored) {
                // 往上找
            }
        }
        throw new NoSuchMethodException(start.getName() + "." + name);
    }
}
