package tw.exilecore.passive;

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

import java.util.ArrayList;
import java.util.List;
import java.util.Set;

/**
 * 選一顆珠寶放進天賦樹的珠寶孔（在天賦樹點一個空的、已配置的珠寶孔會打開）：
 * 列出背包與隨身背包裡所有的珠寶，點一顆就放進去、回到天賦樹；不能放的（未鑑定）會寫原因。
 */
public final class JewelPickMenu extends Menu {

    private static final int ROWS = 6;
    private static final int SLOT_BACK = 49;
    private static final int SLOT_CLOSE = 53;
    private static final int LIST_SIZE = (ROWS - 1) * 9;

    /** 一顆候選的珠寶：在背包的第幾格（page 為 -1），或隨身背包第幾頁第幾格。 */
    private record Candidate(ItemStack stack, int slot, int page) {
    }

    private final PassiveTree tree;
    private final PassiveNode node;
    private final Set<String> highlights;
    private final List<Candidate> candidates = new ArrayList<>();

    public JewelPickMenu(final ExileCore plugin, final Player player, final PassiveTree tree, final PassiveNode node, final Set<String> highlights) {
        super(plugin, player, ROWS, "<dark_gray>選一顆珠寶放進珠寶孔");
        this.tree = tree;
        this.node = node;
        this.highlights = highlights;
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
            final ItemStack shown = candidate.stack().clone();
            final String problem = JewelSockets.problem(plugin, candidate.stack());
            final List<String> extra = new ArrayList<>();
            extra.add("");
            extra.add(candidate.page() < 0 ? "<dark_gray>在背包" : "<dark_gray>在隨身背包第 " + (candidate.page() + 1) + " 頁");
            extra.add(problem == null ? "<yellow>左鍵：放進珠寶孔" : "<red>不能放：" + ItemRenderer.escape(problem));
            set(i, problem == null ? targetHighlight(appendLore(shown, extra)) : appendLore(shown, extra));
        }
        for (int slot = LIST_SIZE; slot < ROWS * 9; slot++) {
            set(slot, filler());
        }
        if (candidates.isEmpty()) {
            set(22, icon(Material.BARRIER, "<red>背包裡沒有珠寶",
                    "<gray>珠寶（赤星晶、翠星晶、蒼星晶、白星晶）是 20 級以後怪物會掉的",
                    "<gray>放進配了的珠寶孔，詞綴就直接加在角色身上"));
        }
        set(SLOT_BACK, icon(Material.ARROW, "<white>回到天賦樹", "<gray>不放，回去"));
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
        // 畫面畫好之後東西可能動過了（別的視窗、掉落）：來源格子要還是同一顆
        final ItemStack current = candidate.page() < 0 ? player.getInventory().getItem(candidate.slot())
                : plugin.players().get(player).bag().page(candidate.page())[candidate.slot()];
        if (current == null || !current.equals(candidate.stack())) {
            fail("那顆珠寶已經不在原來的位置了，重新選一次");
            redraw();
            return;
        }
        final PassiveState.Result result = JewelSockets.socket(plugin, player, node, current);
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
        player.sendActionBar(plugin.text().parse("<green>珠寶放進去了，詞綴已加在角色身上"));
        back();
    }

    private void fail(final String message) {
        player.playSound(player.getLocation(), Sound.BLOCK_NOTE_BLOCK_BASS, 0.8f, 0.6f);
        player.sendActionBar(plugin.text().parse("<red>" + ItemRenderer.escape(message)));
    }

    private void back() {
        new PassiveMenu(plugin, player, tree, highlights, node).open();
    }
}
