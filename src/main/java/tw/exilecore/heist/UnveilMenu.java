package tw.exilecore.heist;

import org.bukkit.Material;
import org.bukkit.Sound;
import org.bukkit.entity.Player;
import org.bukkit.event.inventory.ClickType;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.inventory.ItemStack;
import tw.exilecore.ExileCore;
import tw.exilecore.gui.Menu;
import tw.exilecore.item.Affix;
import tw.exilecore.item.AffixType;
import tw.exilecore.item.ItemData;
import tw.exilecore.item.ItemRenderer;
import tw.exilecore.item.RolledAffix;
import tw.exilecore.item.Veiled;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * 揭露隱匿詞綴（盜賊首領）：先選背包裡一件有隱匿詞綴的裝備，再選一條隱匿詞綴，從三個揭露詞綴挑一個換上去。
 * 三個選項同一件物品同一條只擲一次（關掉再打開還是那三個），免費。
 */
public final class UnveilMenu extends Menu {

    private static final int SLOT_ITEM = 4;
    private static final int[] OPTION_SLOTS = {29, 31, 33};
    private static final int SLOT_BACK = 45;
    private static final int SLOT_CLOSE = 49;

    /** 選單格子 → 背包格子（選物品的畫面）。 */
    private final Map<Integer, Integer> itemSlots = new HashMap<>();
    /** 選單格子 → 物品上的第幾條詞綴（選隱匿詞綴的畫面）。 */
    private final Map<Integer, Integer> veiledSlots = new HashMap<>();
    /** 選的背包格子（-1 = 還沒選）。 */
    private int inventorySlot = -1;
    private String itemUuid;
    /** 選的隱匿詞綴是物品上的第幾條（-1 = 還沒選）。 */
    private int affixIndex = -1;

    public UnveilMenu(final ExileCore plugin, final Player player) {
        super(plugin, player, 6, "<dark_gray>揭露隱匿詞綴");
    }

    private HeistService heists() {
        return plugin.heists();
    }

    @Override
    protected void draw() {
        for (int i = 0; i < 54; i++) {
            set(i, filler());
        }
        itemSlots.clear();
        veiledSlots.clear();
        set(SLOT_CLOSE, closeButton());
        final ItemData data = selected();
        if (data == null) {
            inventorySlot = -1;
            affixIndex = -1;
            drawItems();
            return;
        }
        set(SLOT_BACK, backButton("選別件裝備"));
        set(SLOT_ITEM, player.getInventory().getItem(inventorySlot).clone());
        final List<Integer> veiled = veiledIndexes(data);
        if (veiled.size() == 1) {
            affixIndex = veiled.getFirst();
        }
        if (affixIndex < 0 || !veiled.contains(affixIndex)) {
            affixIndex = -1;
            set(13, icon(Material.PAPER, "<#d4a017>選一條要揭露的隱匿詞綴"));
            int slot = 20;
            for (final int index : veiled) {
                final RolledAffix rolled = data.affixes().get(index);
                set(slot, icon(Material.ENDER_EYE, "<#d4a017>" + (Veiled.typeOf(rolled) == AffixType.PREFIX ? "隱匿前綴" : "隱匿後綴"),
                        "<yellow>左鍵：看揭露的選項"));
                veiledSlots.put(slot, index);
                slot += 2;
            }
            return;
        }
        final RolledAffix target = data.affixes().get(affixIndex);
        set(13, icon(Material.ENDER_EYE, "<#d4a017>揭露" + (Veiled.typeOf(target) == AffixType.PREFIX ? "隱匿前綴" : "隱匿後綴"),
                "<gray>從下面三個挑一個換上去（不能反悔）",
                "<gray>選項只擲一次：關掉再打開還是這三個"));
        final List<RolledAffix> options = heists().offers(data, affixIndex);
        if (options.isEmpty()) {
            set(31, icon(Material.BARRIER, "<red>沒有能揭露的詞綴",
                    "<gray>這個基底、這個物品等級沒有能出的揭露詞綴（或同群組的都已經有了）"));
            return;
        }
        for (int i = 0; i < options.size() && i < OPTION_SLOTS.length; i++) {
            final RolledAffix option = options.get(i);
            final Affix affix = plugin.items().repository().affix(option.id());
            if (affix == null) {
                continue;
            }
            set(OPTION_SLOTS[i], glowing(icon(Material.ENCHANTED_BOOK, "<#b3a1ff>" + ItemRenderer.escape(affix.render(option.vars())),
                    "<dark_gray>揭露" + (affix.type() == AffixType.PREFIX ? "前綴" : "後綴") + "・T" + affix.rank(option.tier()),
                    "",
                    "<yellow>左鍵：選這個")));
        }
    }

    /** 選物品的畫面：背包裡有隱匿詞綴的裝備。 */
    private void drawItems() {
        set(4, icon(Material.SPYGLASS, "<#d4a017>選一件有隱匿詞綴的裝備",
                "<gray>只看背包（不含隨身背包、倉庫）",
                "<gray>未鑑定的要先找鑑定師鑑定"));
        int slot = 9;
        final ItemStack[] contents = player.getInventory().getStorageContents();
        for (int i = 0; i < contents.length && slot < 45; i++) {
            final ItemStack stack = contents[i];
            if (stack == null || stack.isEmpty()) {
                continue;
            }
            final ItemData data = plugin.items().read(stack).orElse(null);
            if (data == null || Veiled.veiledOn(data).isEmpty()) {
                continue;
            }
            if (!data.identified()) {
                set(slot++, appendLore(stack.clone(), "", "<red>要先鑑定"));
                continue;
            }
            if (data.mirrored()) {
                set(slot++, appendLore(stack.clone(), "", "<red>鏡像複製品不能再改造")); // 2026-10：映星鏡照出來的
                continue;
            }
            set(slot, appendLore(stack.clone(), "", "<yellow>左鍵：揭露這件"));
            itemSlots.put(slot, i);
            slot++;
        }
        if (slot == 9) {
            set(22, icon(Material.BARRIER, "<gray>背包裡沒有帶隱匿詞綴的裝備", "<gray>劫盜（合約、藍圖）裡掉的魔法／稀有裝備有機率帶"));
        }
    }

    /** 選著的物品（還在那一格、還是同一件、還有隱匿詞綴）；不然 null。 */
    private ItemData selected() {
        if (inventorySlot < 0) {
            return null;
        }
        final ItemStack stack = player.getInventory().getItem(inventorySlot);
        final ItemData data = stack == null ? null : plugin.items().read(stack).orElse(null);
        if (data == null || !data.uuid().equals(itemUuid) || !data.identified() || data.mirrored() || Veiled.veiledOn(data).isEmpty()) {
            return null;
        }
        return data;
    }

    private static List<Integer> veiledIndexes(final ItemData data) {
        final List<Integer> list = new ArrayList<>();
        for (int i = 0; i < data.affixes().size(); i++) {
            if (Veiled.isVeiled(data.affixes().get(i).id())) {
                list.add(i);
            }
        }
        return list;
    }

    @Override
    protected void onClick(final int slot, final ClickType click, final InventoryClickEvent event) {
        if (slot == SLOT_CLOSE) {
            new HeistMenu(plugin, player).open();
            return;
        }
        if (slot == SLOT_BACK && inventorySlot >= 0) {
            inventorySlot = -1;
            affixIndex = -1;
            redraw();
            return;
        }
        if (inventorySlot < 0) {
            final Integer chosen = itemSlots.get(slot);
            if (chosen != null) {
                final ItemStack stack = player.getInventory().getItem(chosen);
                final ItemData data = stack == null ? null : plugin.items().read(stack).orElse(null);
                if (data != null) {
                    inventorySlot = chosen;
                    itemUuid = data.uuid();
                    affixIndex = -1;
                }
                redraw();
            }
            return;
        }
        final Integer veiled = veiledSlots.get(slot);
        if (veiled != null) {
            affixIndex = veiled;
            redraw();
            return;
        }
        for (int i = 0; i < OPTION_SLOTS.length; i++) {
            if (OPTION_SLOTS[i] == slot && affixIndex >= 0) {
                choose(i);
                return;
            }
        }
    }

    private void choose(final int option) {
        final ItemData data = selected();
        if (data == null || affixIndex < 0 || affixIndex >= data.affixes().size() || !Veiled.isVeiled(data.affixes().get(affixIndex).id())) {
            redraw();
            return;
        }
        final List<RolledAffix> options = heists().offers(data, affixIndex);
        if (option >= options.size()) {
            return;
        }
        final RolledAffix chosen = options.get(option);
        final RolledAffix target = data.affixes().get(affixIndex);
        if (!Veiled.replace(data, target, chosen)) {
            redraw();
            return;
        }
        heists().forgetOffer(data, affixIndex);
        final ItemStack stack = player.getInventory().getItem(inventorySlot);
        plugin.items().update(stack, data);
        player.getInventory().setItem(inventorySlot, stack);
        final Affix affix = plugin.items().repository().affix(chosen.id());
        player.sendMessage(plugin.text().prefixed("<#d4a017>盜賊首領：<gray>揭露了——<#b3a1ff>"
                + ItemRenderer.escape(affix == null ? chosen.id() : affix.render(chosen.vars()))));
        player.playSound(player.getLocation(), Sound.BLOCK_ENCHANTMENT_TABLE_USE, 1.0f, 1.2f);
        plugin.players().markDirty(player);
        affixIndex = -1;
        redraw();
    }
}
