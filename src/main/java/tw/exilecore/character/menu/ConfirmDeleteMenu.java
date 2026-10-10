package tw.exilecore.character.menu;

import org.bukkit.Material;
import org.bukkit.entity.Player;
import org.bukkit.event.inventory.ClickType;
import org.bukkit.event.inventory.InventoryClickEvent;
import tw.exilecore.ExileCore;
import tw.exilecore.character.CharacterRecord;
import tw.exilecore.gui.Menu;
import tw.exilecore.item.ItemRenderer;

/** 刪除角色的確認。 */
public final class ConfirmDeleteMenu extends Menu {

    private static final int SLOT_CONFIRM = 11;
    private static final int SLOT_CANCEL = 15;

    private final CharacterRecord record;

    public ConfirmDeleteMenu(final ExileCore plugin, final Player player, final CharacterRecord record) {
        super(plugin, player, 3, "<dark_red>確定要刪除？");
        this.record = record;
    }

    @Override
    protected void draw() {
        for (int i = 0; i < 27; i++) {
            set(i, filler());
        }
        set(4, icon(material(record.characterClass().icon(), Material.IRON_SWORD),
                record.characterClass().color() + ItemRenderer.escape(record.name()),
                "<gray>" + record.characterClass().displayName() + " <white>Lv." + record.level(),
                "<red>刪除後這個角色的背包、天賦、飾品都會一起消失"));
        set(SLOT_CONFIRM, icon(Material.RED_STAINED_GLASS_PANE, "<red>確定刪除", "<gray>資料會保留在資料庫，但遊戲裡找不回來"));
        set(SLOT_CANCEL, icon(Material.LIME_STAINED_GLASS_PANE, "<green>取消"));
    }

    @Override
    protected void onClick(final int slot, final ClickType click, final InventoryClickEvent event) {
        if (slot == SLOT_CONFIRM) {
            player.closeInventory();
            plugin.characters().delete(player, record.id(),
                    error -> player.sendMessage(plugin.text().prefixed("<red>" + error)));
        } else if (slot == SLOT_CANCEL) {
            plugin.characterMenus().openSelect(player);
        }
    }
}
