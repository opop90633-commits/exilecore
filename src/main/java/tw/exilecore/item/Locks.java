package tw.exilecore.item;

import org.bukkit.inventory.ItemStack;
import tw.exilecore.ExileCore;

/**
 * 2026-10-31：鎖定（/lock、拆解台右鍵）統一的判斷：裝備記在物品資料上（{@link ItemData#locked}），
 * 技能、輔助寶石記在寶石物品上（{@link tw.exilecore.skill.GemService#locked}）。
 * 拆解台、市集、交易、垃圾桶、公會倉庫都用這個看。
 */
public final class Locks {

    private Locks() {
    }

    /** 這一格鎖定了沒（裝備或技能、輔助寶石）。 */
    public static boolean locked(final ExileCore plugin, final ItemStack stack) {
        if (stack == null || stack.isEmpty()) {
            return false;
        }
        if (plugin.items().read(stack).map(ItemData::locked).orElse(false)) {
            return true;
        }
        return plugin.skills() != null && plugin.skills().gems().locked(stack);
    }

    /** 能不能上鎖（有物品資料的裝備，或技能、輔助寶石）。 */
    public static boolean lockable(final ExileCore plugin, final ItemStack stack) {
        if (stack == null || stack.isEmpty()) {
            return false;
        }
        return plugin.items().read(stack).isPresent() || plugin.skills() != null && plugin.skills().gems().lockable(stack);
    }

    /** 鎖定或解除鎖定（原地改）。不能鎖的回傳 false。 */
    public static boolean set(final ExileCore plugin, final ItemStack stack, final boolean locked) {
        if (stack == null || stack.isEmpty()) {
            return false;
        }
        final java.util.Optional<ItemData> data = plugin.items().read(stack);
        if (data.isPresent()) {
            data.get().setLocked(locked);
            plugin.items().update(stack, data.get());
            return true;
        }
        return plugin.skills() != null && plugin.skills().gems().setLocked(stack, locked);
    }
}
