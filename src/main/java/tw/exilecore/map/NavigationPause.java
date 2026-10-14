package tw.exilecore.map;

import org.bukkit.World;
import org.slf4j.Logger;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;

/**
 * 2026-10 效能（spark：地圖施工放方塊佔主執行緒 9%，其中一大半是這個）：原版每改一格擋路的方塊，都會問這個世界裡<b>每一隻</b>
 * 正在尋路的怪「你的路線要不要重算」。蓋新地圖、清舊格子的範圍裡沒有在走路的怪（清格子時先把怪移掉了），
 * 所以施工的那一小段時間把世界的「尋路中的怪」清單先拿開，做完放回去——別的地圖裡的怪照常走，只是不會為了遠方在蓋房子重算路線。
 * <p>
 * 用反射碰原版的欄位（Paper 執行時是 Mojang 的名稱）；對不上（之後的版本改名）只記一次、之後照舊（只是慢）。
 */
final class NavigationPause {

    private static Method getHandle;
    private static Field navigatingMobs;
    private static Method isRemoved;
    private static boolean broken;

    private NavigationPause() {
    }

    /** 在「尋路中的怪」清單拿開的情況下做 work（主執行緒）。 */
    @SuppressWarnings("unchecked")
    static void run(final World world, final Logger logger, final Runnable work) {
        Set<Object> set = null;
        List<Object> saved = null;
        if (!broken) {
            try {
                if (navigatingMobs == null) {
                    resolve(world);
                }
                set = (Set<Object>) navigatingMobs.get(getHandle.invoke(world));
                saved = new ArrayList<>(set);
                set.clear();
            } catch (final ReflectiveOperationException | RuntimeException | LinkageError e) {
                broken = true;
                set = null;
                logger.warn("地圖施工的尋路優化關掉了（這個 Paper 版本對不上：{}）：不影響遊戲，只是蓋地圖比較慢", e.toString());
            }
        }
        try {
            work.run();
        } finally {
            if (set != null) {
                for (final Object mob : saved) {
                    if (!removed(mob)) {
                        set.add(mob); // 施工中被移掉的（清格子）不放回去
                    }
                }
            }
        }
    }

    private static boolean removed(final Object mob) {
        try {
            return isRemoved != null && (boolean) isRemoved.invoke(mob);
        } catch (final ReflectiveOperationException | RuntimeException e) {
            return false;
        }
    }

    private static void resolve(final World world) throws ReflectiveOperationException {
        getHandle = world.getClass().getMethod("getHandle");
        final Object level = getHandle.invoke(world);
        Field found = null;
        for (Class<?> type = level.getClass(); type != null && found == null; type = type.getSuperclass()) {
            try {
                found = type.getDeclaredField("navigatingMobs");
            } catch (final NoSuchFieldException ignored) {
                // 往上找
            }
        }
        if (found == null || !Set.class.isAssignableFrom(found.getType())) {
            throw new NoSuchFieldException("ServerLevel.navigatingMobs");
        }
        found.setAccessible(true);
        final Class<?> entity = Class.forName("net.minecraft.world.entity.Entity", false, level.getClass().getClassLoader());
        isRemoved = entity.getMethod("isRemoved");
        navigatingMobs = found;
    }
}
