package tw.exilecore.map;

import org.bukkit.Material;
import org.bukkit.Sound;
import org.bukkit.entity.Player;
import org.bukkit.event.inventory.ClickType;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.inventory.ItemStack;
import tw.exilecore.ExileCore;
import tw.exilecore.gui.Menu;
import tw.exilecore.item.ItemRenderer;

import java.util.ArrayList;
import java.util.List;

/**
 * 祭壇的獎勵（PoE2 的 Ritual）：你這組的每一件寫著要幾貢品，買得起的有綠框；點一件就買走。
 * 「重擲」把整組換成新的（花貢品，每次更貴）。貢品只在這張圖有效，離開前花掉。
 */
final class RitualMenu extends Menu {

    private static final int ROWS = 4;
    private static final int LIST_SIZE = (ROWS - 1) * 9;
    private static final int SLOT_INFO = 27;
    private static final int SLOT_REROLL = 31;
    private static final int SLOT_CLOSE = 35;

    private final RitualEvents rituals;
    private final RitualEvents.Ritual ritual;

    RitualMenu(final ExileCore plugin, final RitualEvents rituals, final RitualEvents.Ritual ritual, final Player player) {
        super(plugin, player, ROWS, "<dark_gray>祭壇的獎勵");
        this.rituals = rituals;
        this.ritual = ritual;
    }

    @Override
    protected void draw() {
        final int tribute = ritual.tributeOf(player);
        final List<RitualEvents.Offer> offers = rituals.offers(ritual, player);
        for (int i = 0; i < LIST_SIZE; i++) {
            if (i >= offers.size()) {
                set(i, null);
                continue;
            }
            final RitualEvents.Offer offer = offers.get(i);
            if (offer.bundle == null) {
                set(i, icon(Material.GRAY_STAINED_GLASS_PANE, "<dark_gray>已換走"));
                continue;
            }
            final ItemStack shown = offer.bundle.display();
            final List<String> extra = new ArrayList<>();
            extra.add("");
            extra.add(rituals.formatRarity(offer.bundle.kind()) + offer.bundle.kind().displayName()
                    + (offer.bundle.items().size() > 1 ? " <gray>×" + offer.bundle.items().size() + " 件" : ""));
            final boolean affordable = tribute >= offer.price;
            extra.add(affordable ? "<yellow>左鍵：花 " + offer.price + " 貢品換走" : "<red>要 " + offer.price + " 貢品（你有 " + tribute + "）");
            set(i, affordable ? targetHighlight(appendLore(shown, extra)) : appendLore(shown, extra));
        }
        for (int slot = LIST_SIZE; slot < ROWS * 9; slot++) {
            set(slot, filler());
        }
        final int rerollPrice = rituals.rerollPrice(ritual, player);
        set(SLOT_INFO, glowing(icon(Material.REDSTONE, "<#ff5c6a>貢品 <white>" + tribute,
                "<gray>這張圖總共拿到 <white>" + ritual.earned.getOrDefault(player.getUniqueId(), 0),
                "<gray>貢品只在這張圖有效，離開前花掉",
                "<dark_gray>每個人的獎勵各自一組，別人換走的不影響你")));
        set(SLOT_REROLL, icon(Material.NETHER_STAR, "<#c48bff>重擲整組",
                tribute >= rerollPrice ? "<yellow>左鍵：花 " + rerollPrice + " 貢品換一組新的" : "<red>要 " + rerollPrice + " 貢品（你有 " + tribute + "）",
                "<dark_gray>每重擲一次更貴；已經換走的不會回來"));
        set(SLOT_CLOSE, closeButton());
    }

    @Override
    protected void onClick(final int slot, final ClickType click, final InventoryClickEvent event) {
        if (slot == SLOT_CLOSE) {
            goBack();
            return;
        }
        if (!acceptAction(click)) {
            return;
        }
        if (!rituals.canShop(ritual, player)) {
            player.sendActionBar(plugin.text().parse("<red>祭壇已經不在了（地圖關了或你離開了）"));
            player.closeInventory();
            return;
        }
        if (slot == SLOT_REROLL) {
            final String problem = rituals.reroll(ritual, player);
            if (problem != null) {
                fail(problem);
            } else {
                player.playSound(player.getLocation(), Sound.BLOCK_ENCHANTMENT_TABLE_USE, 0.8f, 0.9f);
            }
            redraw();
            return;
        }
        if (slot >= LIST_SIZE) {
            return;
        }
        final String problem = rituals.buy(ritual, player, slot);
        if (problem != null) {
            fail(problem);
        } else {
            player.playSound(player.getLocation(), Sound.ENTITY_PLAYER_LEVELUP, 0.7f, 1.4f);
            player.sendActionBar(plugin.text().parse("<green>換到了，剩 " + ritual.tributeOf(player) + " 貢品"));
        }
        redraw();
    }

    private void fail(final String message) {
        player.playSound(player.getLocation(), Sound.BLOCK_NOTE_BLOCK_BASS, 0.8f, 0.6f);
        player.sendActionBar(plugin.text().parse("<red>" + ItemRenderer.escape(message)));
    }
}
