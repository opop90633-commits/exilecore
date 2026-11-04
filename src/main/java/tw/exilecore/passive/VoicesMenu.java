package tw.exilecore.passive;

import org.bukkit.Material;
import org.bukkit.Sound;
import org.bukkit.entity.Player;
import org.bukkit.event.inventory.ClickType;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.inventory.ItemStack;
import tw.exilecore.ExileCore;
import tw.exilecore.character.PlayerState;
import tw.exilecore.gui.Menu;
import tw.exilecore.item.ItemData;
import tw.exilecore.item.ItemRenderer;

import java.util.List;
import java.util.Set;

/**
 * 2026-11-04 照站長「嗓音（配置珠寶插槽）做出來」：嗓音（PoE2 Voices）多開的珠寶孔。在天賦樹點嵌著嗓音的珠寶孔會打開：
 * 上面一排是嗓音開的 N 個孔（空的點了選珠寶放進去、有的點了拿回來），下面可以把嗓音本身拿出來（孔裡要先清空）。
 * 嗓音的孔不在天賦樹上：只能放基礎珠寶（半徑、恆時珠寶沒有範圍可以影響），效果跟放在一般珠寶孔一樣。
 */
public final class VoicesMenu extends Menu {

    private static final int ROWS = 3;
    private static final int FIRST = 11;
    private static final int SLOT_VOICES = 22;
    private static final int SLOT_BACK = 18;
    private static final int SLOT_CLOSE = 26;

    private final PassiveTree tree;
    private final PassiveNode node;
    private final Set<String> highlights;

    public VoicesMenu(final ExileCore plugin, final Player player, final PassiveTree tree, final PassiveNode node, final Set<String> highlights) {
        super(plugin, player, ROWS, "<dark_gray>嗓音的珠寶孔");
        this.tree = tree;
        this.node = node;
        this.highlights = highlights;
    }

    /** 這個珠寶孔嵌著的是不是嗓音（有多開孔的傳奇珠寶）。 */
    static boolean holdsVoices(final ExileCore plugin, final PlayerState state, final PassiveNode node) {
        final ItemStack jewel = state.jewel(node.id());
        final ItemData data = jewel == null ? null : plugin.items().read(jewel).orElse(null);
        return JewelSockets.voiceCount(plugin.items().repository(), data) > 0;
    }

    private int count() {
        final ItemStack jewel = plugin.players().get(player).jewel(node.id());
        return JewelSockets.voiceCount(plugin.items().repository(), jewel == null ? null : plugin.items().read(jewel).orElse(null));
    }

    @Override
    protected void draw() {
        final PlayerState state = plugin.players().get(player);
        for (int slot = 0; slot < ROWS * 9; slot++) {
            set(slot, filler());
        }
        final int count = Math.min(5, count());
        for (int i = 1; i <= count; i++) {
            final ItemStack held = state.jewel(JewelSockets.voiceKey(node.id(), i));
            if (held == null) {
                set(FIRST + i - 1, icon(Material.LIGHT_GRAY_STAINED_GLASS_PANE, "<#c77dff>嗓音的孔 " + i + "（空的）",
                        "<gray>只能放基礎珠寶（赤星晶、翠星晶、蒼星晶、白星晶）", "<gray>半徑、恆時珠寶不行", "", "<yellow>左鍵：選一顆珠寶放進去"));
            } else {
                set(FIRST + i - 1, appendLore(held.clone(), List.of("", "<dark_gray>嗓音的孔 " + i, "<yellow>左鍵：拿回背包")));
            }
        }
        final ItemStack voices = state.jewel(node.id());
        if (voices != null) {
            set(SLOT_VOICES, appendLore(voices.clone(), List.of("", "<yellow>左鍵：把嗓音拿回背包（孔裡要先清空）")));
        }
        set(SLOT_BACK, icon(Material.ARROW, "<white>回到天賦樹"));
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
        if (!acceptAction(click)) {
            return;
        }
        final PlayerState state = plugin.players().get(player);
        if (slot == SLOT_VOICES) {
            final PassiveState.Result result = JewelSockets.unsocket(plugin, player, node);
            if (!result.ok()) {
                fail(result.message());
                redraw();
                return;
            }
            plugin.characters().save(player);
            tw.exilecore.gui.ActionBar.send(player, plugin.text().parse("<yellow>嗓音拿回背包了"));
            back();
            return;
        }
        final int index = slot - FIRST + 1;
        if (index < 1 || index > Math.min(5, count())) {
            return;
        }
        final String key = JewelSockets.voiceKey(node.id(), index);
        if (state.jewel(key) == null) {
            new JewelPickMenu(plugin, player, tree, node, highlights, key).open();
            return;
        }
        final PassiveState.Result result = JewelSockets.unsocketVoice(plugin, player, key);
        if (!result.ok()) {
            fail(result.message());
        } else {
            plugin.characters().save(player);
            player.playSound(player.getLocation(), Sound.BLOCK_AMETHYST_BLOCK_BREAK, 0.8f, 1.2f);
        }
        redraw();
    }

    private void fail(final String message) {
        player.playSound(player.getLocation(), Sound.BLOCK_NOTE_BLOCK_BASS, 0.8f, 0.6f);
        tw.exilecore.gui.ActionBar.send(player, plugin.text().parse("<red>" + ItemRenderer.escape(message)));
    }

    private void back() {
        if (TreeFeed.openNative(plugin, player, null)) {
            return;
        }
        new PassiveMenu(plugin, player, tree, highlights, node).open();
    }
}
