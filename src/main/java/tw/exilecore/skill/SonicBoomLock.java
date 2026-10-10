package tw.exilecore.skill;

import org.bukkit.entity.Entity;
import org.slf4j.Logger;

import java.lang.reflect.Method;

/**
 * 伏守者原版的音波：掛了 Boss 招式的伏守者（曜帝）不用。原版音波沒有地上的警告、隔著牆也打得到，又跟 Boss 招式裡的
 * 「音爆」重複，跟招式一起出現就像同時放兩招。Paper 沒有關掉它的 API，所以用反射把伏守者腦袋裡「音波冷卻中」的記憶
 * 一直設著（原版伏守者每次近戰打中人也是這樣設）。
 * <p>
 * 反射對不上（之後的版本改了名字）只記一次警告、之後不再試；戰鬥模組另外會擋掉這種伏守者的音波傷害，所以最多是畫面上看得到。
 */
final class SonicBoomLock {

    private final Logger logger;
    private Method handle;
    private Method brain;
    private Method setMemory;
    private Object memory;
    private Object unit;
    private boolean broken;

    SonicBoomLock(final Logger logger) {
        this.logger = logger;
    }

    /** 這隻伏守者接下來 ticks 個 tick 都不能放原版音波（腦袋暫停時不會倒數）；成功回傳 true。 */
    boolean lock(final Entity warden, final long ticks) {
        if (broken) {
            return false;
        }
        try {
            final Object nms = handle(warden);
            setMemory.invoke(brain.invoke(nms), memory, unit, ticks);
            return true;
        } catch (final ReflectiveOperationException | RuntimeException | LinkageError e) {
            broken = true;
            logger.warn("關不掉伏守者 Boss 原版的音波（這個 Paper 版本對不上：{}）：改成只擋掉音波的傷害，畫面上還是看得到", e.toString());
            return false;
        }
    }

    /** 反射對不上、已經放棄。 */
    boolean broken() {
        return broken;
    }

    private Object handle(final Entity warden) throws ReflectiveOperationException {
        if (setMemory != null) {
            return handle.invoke(warden);
        }
        final Method getHandle = warden.getClass().getMethod("getHandle");
        final Object nms = getHandle.invoke(warden);
        final Method getBrain = nms.getClass().getMethod("getBrain");
        final Object brainObject = getBrain.invoke(nms);
        final ClassLoader loader = nms.getClass().getClassLoader();
        final Class<?> memoryType = Class.forName("net.minecraft.world.entity.ai.memory.MemoryModuleType", false, loader);
        final Object cooldown = memoryType.getField("SONIC_BOOM_COOLDOWN").get(null);
        final Object instance = Class.forName("net.minecraft.util.Unit", false, loader).getField("INSTANCE").get(null);
        final Method set = brainObject.getClass().getMethod("setMemoryWithExpiry", memoryType, Object.class, long.class);
        this.handle = getHandle;
        this.brain = getBrain;
        this.memory = cooldown;
        this.unit = instance;
        this.setMemory = set;
        return nms;
    }
}
