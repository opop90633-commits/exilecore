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
 * 鑑定師的選單（PoE2 的 NPC 對話選項）：鑑定所有物品、免費領鑑定卷軸、洗裝台（2026-10-19）、映星鏡兌換追逐級傳奇（2026-11-11）。
 */
public final class IdentifierMenu extends Menu {

    // 2026-11-11：多了映星鏡兌換，四個按鈕平均排開（這次之前鑑定 11、洗裝台 13、卷軸 15）
    private static final int SLOT_IDENTIFY = 10;
    private static final int SLOT_SCROLLS = 14;
    private static final int SLOT_CRAFT = 12; // 2026-10-19 洗裝台
    private static final int SLOT_MIRROR = 16; // 2026-11-11 照站長：映星鏡換自選的追逐級傳奇
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
        set(SLOT_CRAFT, glowing(icon(Material.SMITHING_TABLE, "<#f5b942>洗裝台",
                "<gray>左邊選星籤、右邊選通貨、中間放裝備，",
                "<gray>按「使用」先掛星籤再用通貨，可以一直按",
                "<dark_gray>也可以打 /craft",
                "",
                "<yellow>左鍵：打開")));
        set(SLOT_MIRROR, glowing(icon(Material.NAUTILUS_SHELL, "<#9fd8ff>映星鏡兌換",
                "<gray>用一顆映星鏡換一件<white>自己選的追逐級傳奇",
                "<gray>數值在範圍裡隨機擲，跟掉出來的一樣",
                "<dark_gray>也可以打 /starmirror",
                "",
                "<yellow>左鍵：打開")));
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
            case SLOT_CRAFT -> new tw.exilecore.currency.CraftBenchMenu(plugin, player,
                    "鑑定師", () -> new IdentifierMenu(plugin, player).open()).open(); // 2026-10-19
            case SLOT_MIRROR -> tw.exilecore.currency.StarMirrorExchangeMenu.open(plugin, player,
                    () -> new IdentifierMenu(plugin, player).open()); // 2026-11-11
            case SLOT_CLOSE -> goBack();
            default -> {
            }
        }
    }
}
