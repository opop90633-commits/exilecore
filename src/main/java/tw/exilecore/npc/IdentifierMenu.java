package tw.exilecore.npc;

import org.bukkit.Material;
import org.bukkit.entity.Player;
import org.bukkit.event.inventory.ClickType;
import org.bukkit.event.inventory.InventoryClickEvent;
import tw.exilecore.ExileCore;
import tw.exilecore.currency.CurrencyType;
import tw.exilecore.gui.Menu;
import tw.exilecore.item.ItemRenderer;

import java.util.Optional;

/**
 * 鑑定師的選單（PoE2 的 NPC 對話選項）：鑑定所有物品、免費領鑑定卷軸。
 */
public final class IdentifierMenu extends Menu {

    private static final int SLOT_IDENTIFY = 11;
    private static final int SLOT_SCROLLS = 15;
    private static final int SLOT_CLOSE = 22;

    public IdentifierMenu(final ExileCore plugin, final Player player) {
        super(plugin, player, 3, "<dark_gray>鑑定師");
    }

    @Override
    protected void draw() {
        for (int i = 0; i < 27; i++) {
            set(i, filler());
        }
        final int unidentified = plugin.npcs().countUnidentified(player);
        if (unidentified > 0) {
            set(SLOT_IDENTIFY, glowing(icon(Material.ENCHANTED_BOOK, "<#f5b942>鑑定背包裡的物品",
                    "<gray>背包裡有 <white>" + unidentified + "</white> 件未鑑定",
                    "<gray>免費",
                    "",
                    "<yellow>左鍵：全部鑑定")));
        } else {
            set(SLOT_IDENTIFY, icon(Material.BOOK, "<gray>鑑定背包裡的物品", "<gray>你背包裡沒有未鑑定的物品"));
        }

        final Optional<CurrencyType> scroll = plugin.npcs().scrollType();
        if (scroll.isEmpty()) {
            set(SLOT_SCROLLS, icon(Material.BARRIER, "<red>沒有鑑定卷軸", "<gray>currencies.yml 裡沒有 operation: IDENTIFY 的通貨"));
        } else {
            final String name = ItemRenderer.escape(scroll.get().name());
            final int have = plugin.npcs().countScrolls(player, scroll.get());
            final int cap = scroll.get().stack();
            final int give = Math.max(0, cap - have);
            set(SLOT_SCROLLS, icon(Material.PAPER, "<#f5b942>領取" + name,
                    "<gray>免費，補滿到 <white>" + cap + "</white> 張（你有 " + have + " 張）",
                    "<gray>在野外用：拿在游標上，左鍵點未鑑定的裝備",
                    "",
                    give > 0 ? "<yellow>左鍵：領 " + give + " 張" : "<gray>已經滿了"));
        }
        set(SLOT_CLOSE, closeButton());
    }

    @Override
    protected void onClick(final int slot, final ClickType click, final InventoryClickEvent event) {
        switch (slot) {
            case SLOT_IDENTIFY -> {
                // 先關視窗，讓玩家看得到聊天欄的鑑定結果
                player.closeInventory();
                plugin.npcs().identify(player);
            }
            case SLOT_SCROLLS -> {
                plugin.npcs().claimScrolls(player);
                redraw();
            }
            case SLOT_CLOSE -> goBack();
            default -> {
            }
        }
    }
}
