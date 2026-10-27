package tw.exilecore.ascendancy;

import org.bukkit.Material;
import org.bukkit.Sound;
import org.bukkit.entity.Player;
import org.bukkit.event.inventory.ClickType;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.inventory.ItemStack;
import tw.exilecore.ExileCore;
import tw.exilecore.character.Bag;
import tw.exilecore.character.PlayerState;
import tw.exilecore.gui.Menu;
import tw.exilecore.item.ItemRenderer;
import tw.exilecore.passive.JewelSockets;
import tw.exilecore.passive.PassiveState;

import java.util.ArrayList;
import java.util.List;

/**
 * 2026-10-27 照 PoE2 昇華・晶化護符（Crystalline Phylactery）：選一顆非傳奇的基礎珠寶放進命匣（昇華天賦上的珠寶孔）。
 * 在昇華天賦（/ascend tree）點命匣打開；列出背包與隨身背包裡的珠寶，點一顆就放進去、回到昇華天賦。不能放的（傳奇、遺時、永恆、未鑑定）寫原因。
 */
final class PhylacteryMenu extends Menu {

    private static final int ROWS = 6;
    private static final int SLOT_BACK = 49;
    private static final int SLOT_CLOSE = 53;
    private static final int LIST_SIZE = (ROWS - 1) * 9;

    /** 一顆候選的珠寶：在背包的第幾格（page 為 -1），或隨身背包第幾頁第幾格。 */
    private record Candidate(ItemStack stack, int slot, int page) {
    }

    private final AscendancyService service;
    private final List<Candidate> candidates = new ArrayList<>();

    PhylacteryMenu(final ExileCore plugin, final Player player, final AscendancyService service) {
        super(plugin, player, ROWS, "<dark_gray>選一顆珠寶放進命匣");
        this.service = service;
    }

    @Override
    protected void draw() {
        candidates.clear();
        final ItemStack[] contents = player.getInventory().getContents();
        for (int slot = 0; slot < Math.min(36, contents.length); slot++) {
            if (JewelSockets.isJewel(plugin, contents[slot])) {
                candidates.add(new Candidate(contents[slot], slot, -1));
            }
        }
        final PlayerState state = plugin.players().get(player);
        final Bag bag = state.bag();
        if (bag.usable()) {
            for (int page = 0; page < bag.pages(); page++) {
                final ItemStack[] items = bag.page(page);
                for (int slot = 0; slot < items.length; slot++) {
                    if (JewelSockets.isJewel(plugin, items[slot])) {
                        candidates.add(new Candidate(items[slot], slot, page));
                    }
                }
            }
        }
        for (int i = 0; i < Math.min(LIST_SIZE, candidates.size()); i++) {
            final Candidate candidate = candidates.get(i);
            final String problem = JewelSockets.phylacteryProblem(plugin, candidate.stack());
            final List<String> extra = new ArrayList<>();
            extra.add("");
            extra.add(candidate.page() < 0 ? "<dark_gray>在背包" : "<dark_gray>在隨身背包第 " + (candidate.page() + 1) + " 頁");
            extra.add(problem == null ? "<yellow>左鍵：放進命匣（效果增加 100%）" : "<red>不能放：" + ItemRenderer.escape(problem));
            final ItemStack shown = appendLore(candidate.stack().clone(), extra);
            set(i, problem == null ? targetHighlight(shown) : shown);
        }
        for (int slot = LIST_SIZE; slot < ROWS * 9; slot++) {
            set(slot, filler());
        }
        if (candidates.isEmpty()) {
            set(22, icon(Material.BARRIER, "<red>背包裡沒有珠寶",
                    "<gray>命匣只能放非傳奇的基礎珠寶（赤星晶、翠星晶、蒼星晶、白星晶）"));
        }
        set(SLOT_BACK, icon(Material.ARROW, "<white>回到昇華天賦", "<gray>不放，回去"));
        set(SLOT_CLOSE, closeButton());
    }

    @Override
    protected void onClick(final int slot, final ClickType click, final InventoryClickEvent event) {
        if (slot == SLOT_CLOSE) {
            goBack();
            return;
        }
        if (slot == SLOT_BACK) {
            back();
            return;
        }
        if (slot >= LIST_SIZE || slot >= candidates.size() || !acceptAction(click)) {
            return;
        }
        final Candidate candidate = candidates.get(slot);
        final ItemStack current = candidate.page() < 0 ? player.getInventory().getItem(candidate.slot())
                : plugin.players().get(player).bag().page(candidate.page())[candidate.slot()];
        if (current == null || !current.equals(candidate.stack())) {
            fail("那顆珠寶已經不在原來的位置了，重新選一次");
            redraw();
            return;
        }
        final PassiveState.Result result = JewelSockets.socketPhylactery(plugin, player, current);
        if (!result.ok()) {
            fail(result.message());
            return;
        }
        if (candidate.page() < 0) {
            player.getInventory().setItem(candidate.slot(), null);
        } else {
            plugin.players().get(player).bag().page(candidate.page())[candidate.slot()] = null;
            plugin.players().get(player).markDirty();
        }
        plugin.characters().save(player);
        player.playSound(player.getLocation(), Sound.BLOCK_AMETHYST_BLOCK_PLACE, 0.9f, 1.2f);
        tw.exilecore.gui.ActionBar.send(player, plugin.text().parse("<green>珠寶放進命匣了，效果增加 100%"));
        back();
    }

    private void fail(final String message) {
        player.playSound(player.getLocation(), Sound.BLOCK_NOTE_BLOCK_BASS, 0.8f, 0.6f);
        tw.exilecore.gui.ActionBar.send(player, plugin.text().parse("<red>" + ItemRenderer.escape(message)));
    }

    private void back() {
        new AscendancyTreeMenu(plugin, player, service).open();
    }
}
