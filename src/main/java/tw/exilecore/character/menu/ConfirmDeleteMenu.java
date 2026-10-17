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

    // 2026-10-18：確認／取消放第一列（角色選擇的角色格只會在第二列以下：3 格排 11/13/15、更多格排滿 9–44），
    // 對角色連點右鍵時第二下不會剛好落在「確定刪除」
    private static final int SLOT_CONFIRM = 2;
    private static final int SLOT_CANCEL = 6;
    /** 2026-10-18：剛打開多久內不接受「確定刪除」（從角色選擇連點過來的那一下不算）。 */
    private static final long OPEN_GRACE_MILLIS = 500;

    private final long openedMillis = System.currentTimeMillis();

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
        if (!acceptAction(click)) {
            return; // 2026-10-18：擋連點
        }
        if (slot == SLOT_CONFIRM) {
            if (System.currentTimeMillis() - openedMillis < OPEN_GRACE_MILLIS) {
                return; // 2026-10-18：剛打開就點到的不算
            }
            player.closeInventory();
            plugin.characters().delete(player, record.id(),
                    error -> player.sendMessage(plugin.text().prefixed("<red>" + error)));
        } else if (slot == SLOT_CANCEL) {
            plugin.characterMenus().openSelect(player);
        }
    }
}
