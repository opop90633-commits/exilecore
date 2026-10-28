package tw.exilecore.character;

import org.bukkit.Material;
import org.bukkit.NamespacedKey;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemFlag;
import org.bukkit.inventory.ItemStack;
import org.bukkit.persistence.PersistentDataType;
import tw.exilecore.item.AccountBound;
import tw.exilecore.item.ItemRenderer;

import java.util.ArrayList;
import java.util.List;

/**
 * 2026-10-28 照站長：職業轉職卷。右鍵打開選單（{@link tw.exilecore.character.menu.ClassChangeMenu}）選別的職業，
 * 確認後換職業（{@link CharacterService#changeClass}），成功了才用掉一張。綁定帳號（{@link AccountBound}）：
 * 自己的角色之間可以放倉庫互用，但不能交易、上架、放公會倉庫、丟在地上。
 * <p>
 * 只有管理員發得出來：管理網頁的「發放」選「職業轉職卷」（DeliveryService.CLASS_SCROLL，離線的下次登入發），
 * 或 /ec classscroll 玩家 [張數]。
 * <p>
 * 圖：紙＋附魔光澤，custom_model_data 掛 {@code currency:class_scroll}（{@link #ART}）。資源包的通貨圖（web/currency_art.txt）
 * 有 class_scroll 這一張就自動用上；沒有就是原版的紙（UiPack 本來就會退回原版的樣子），不用另外做圖也能用。
 */
public final class ClassScroll {

    /** 物品上的記號（值 1）。 */
    public static final NamespacedKey KEY = new NamespacedKey("exilecore", "class_scroll");
    /** 通貨圖的代號（currency_art.txt 有這張就套上）。 */
    public static final String ART = "class_scroll";
    /** 名稱。 */
    public static final String NAME = "職業轉職卷";
    /** 一疊最多幾張。 */
    public static final int STACK = 16;

    private ClassScroll() {
    }

    /** 這是職業轉職卷嗎。 */
    public static boolean is(final ItemStack stack) {
        if (stack == null || stack.isEmpty() || !stack.hasItemMeta()) {
            return false;
        }
        final Byte value = stack.getItemMeta().getPersistentDataContainer().get(KEY, PersistentDataType.BYTE);
        return value != null && value != 0;
    }

    /** 做 amount 張（一疊最多 {@link #STACK}）。 */
    public static ItemStack create(final int amount) {
        final ItemStack stack = ItemStack.of(Material.PAPER, Math.max(1, Math.min(STACK, amount)));
        stack.setData(io.papermc.paper.datacomponent.DataComponentTypes.CUSTOM_MODEL_DATA,
                io.papermc.paper.datacomponent.item.CustomModelData.customModelData()
                        .addString(tw.exilecore.currency.CurrencyArt.modelKey(ART)).build());
        stack.editMeta(meta -> {
            meta.displayName(ItemRenderer.line("<#e8c26a>" + NAME));
            final List<net.kyori.adventure.text.Component> lore = new ArrayList<>();
            for (final String line : lore()) {
                lore.add(ItemRenderer.line(line));
            }
            meta.lore(lore);
            meta.setMaxStackSize(STACK);
            meta.setEnchantmentGlintOverride(true);
            meta.addItemFlags(ItemFlag.HIDE_ATTRIBUTES, ItemFlag.HIDE_ADDITIONAL_TOOLTIP);
            meta.getPersistentDataContainer().set(KEY, PersistentDataType.BYTE, (byte) 1);
            AccountBound.mark(meta);
        });
        return stack;
    }

    /** 物品說明（純文字，測試也看得到）。 */
    public static List<String> lore() {
        return List.of(
                "<gray>把這個角色換成別的職業",
                "<gray>天賦點、昇華點全部退回，珠寶還你",
                "<gray>等級、裝備、技能、通貨都不變",
                "",
                "<yellow>拿在手上按右鍵：選職業（會先確認）",
                "<dark_gray>換成功才會用掉",
                "<#ff9a6b>綁定帳號：不能交易、上架、放公會倉庫、丟在地上");
    }

    /** 背包（含副手）裡第一張轉職卷在哪一格；沒有 -1。 */
    public static int find(final Player player) {
        final org.bukkit.inventory.PlayerInventory inventory = player.getInventory();
        final int held = inventory.getHeldItemSlot();
        if (is(inventory.getItem(held))) {
            return held;
        }
        for (int slot = 0; slot < inventory.getSize(); slot++) {
            if (is(inventory.getItem(slot))) {
                return slot;
            }
        }
        return -1;
    }

    /** 用掉一張（背包裡找）；沒有回傳 false。 */
    public static boolean consume(final Player player) {
        final int slot = find(player);
        if (slot < 0) {
            return false;
        }
        final ItemStack stack = player.getInventory().getItem(slot);
        if (stack.getAmount() <= 1) {
            player.getInventory().setItem(slot, null);
        } else {
            stack.setAmount(stack.getAmount() - 1);
            player.getInventory().setItem(slot, stack);
        }
        return true;
    }
}
