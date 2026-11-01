package tw.exilecore.item;

import io.papermc.paper.datacomponent.DataComponentTypes;
import org.bukkit.inventory.ItemStack;

import java.util.List;

/**
 * 2026-11-01：物品裡面有沒有裝東西（收納袋、界伏盒）。腰帶的原版物品是收納袋（BUNDLE），本身就帶「收納袋內容」這個資料，
 * 只看有沒有這個資料會把每一條腰帶都當成容器（交易、市集、公會倉庫都擋掉）；這裡改成真的有裝東西才算。
 */
public final class ItemContainers {

    private ItemContainers() {
    }

    /** 裡面有沒有裝東西。 */
    public static boolean holdsItems(final ItemStack stack) {
        if (stack == null || stack.isEmpty()) {
            return false;
        }
        if (stack.hasData(DataComponentTypes.BUNDLE_CONTENTS)) {
            final var bundle = stack.getData(DataComponentTypes.BUNDLE_CONTENTS);
            if (bundle != null && anyItem(bundle.contents())) {
                return true;
            }
        }
        if (stack.hasData(DataComponentTypes.CONTAINER)) {
            final var container = stack.getData(DataComponentTypes.CONTAINER);
            return container != null && anyItem(container.contents());
        }
        return false;
    }

    /** 純邏輯：清單裡有沒有不是空的物品。 */
    static boolean anyItem(final List<ItemStack> contents) {
        if (contents == null) {
            return false;
        }
        for (final ItemStack each : contents) {
            if (each != null && !each.isEmpty()) {
                return true;
            }
        }
        return false;
    }
}
