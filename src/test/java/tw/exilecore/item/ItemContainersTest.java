package tw.exilecore.item;

import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertFalse;

/** 2026-11-01：空的收納袋內容（腰帶）不算裝了東西。 */
class ItemContainersTest {
    @Test
    void emptyContentsHoldNothing() {
        assertFalse(ItemContainers.anyItem(null));
        assertFalse(ItemContainers.anyItem(List.of()));
        final List<org.bukkit.inventory.ItemStack> nulls = new ArrayList<>();
        nulls.add(null);
        assertFalse(ItemContainers.anyItem(nulls));
    }
}
