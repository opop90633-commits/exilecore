package tw.exilecore.ascendancy;

import org.bukkit.Material;
import org.bukkit.Sound;
import org.bukkit.entity.Player;
import org.bukkit.event.inventory.ClickType;
import org.bukkit.event.inventory.InventoryClickEvent;
import tw.exilecore.ExileCore;
import tw.exilecore.character.PlayerState;
import tw.exilecore.currency.CurrencyOperation;
import tw.exilecore.currency.CurrencyType;
import tw.exilecore.gui.Menu;
import tw.exilecore.item.ItemRenderer;
import tw.exilecore.item.Runes;

import java.util.ArrayList;
import java.util.List;

/**
 * 2026-10-27 照 PoE2 昇華・符文子午線（Runic Meridians）：把符文紋在身上（頭 1、胸 2、腿 1、腳 1 個只能嵌符文的孔）。
 * 在昇華天賦（/ascend tree）點「符文子午線」打開：上面一排是 5 個孔（點一下選要紋哪一孔），下面列出通貨袋、背包、倉庫裡有的符文，
 * 點一顆就紋上去（用掉一顆；那一孔原本的符文會消失，跟裝備上的符文孔滿了覆蓋一樣）。紋的符文記在角色進度 asc.meridians。
 */
final class MeridiansMenu extends Menu {

    private static final int ROWS = 6;
    private static final int[] SOCKET_SLOTS = {11, 12, 13, 14, 15};
    private static final int LIST_START = 27;
    private static final int LIST_SIZE = 18;
    private static final int SLOT_BACK = 45;
    private static final int SLOT_CLOSE = 53;
    private static final String[] SOCKET_NAMES = {"頭盔", "胸甲（一）", "胸甲（二）", "腿甲", "鞋子"};

    private final AscendancyService service;
    private final List<CurrencyType> runes = new ArrayList<>();
    private int selected;

    MeridiansMenu(final ExileCore plugin, final Player player, final AscendancyService service) {
        super(plugin, player, ROWS, "<dark_gray>符文子午線");
        this.service = service;
    }

    @Override
    protected void draw() {
        for (int i = 0; i < ROWS * 9; i++) {
            set(i, filler());
        }
        final PlayerState state = plugin.players().get(player);
        final String[] tattoos = AscBuild.meridianRunes(state);
        set(4, icon(Material.PAPER, "<#d9b44a>符文子午線",
                "<gray>把符文紋在身上，照防具上的符文生效",
                "<gray>護甲、閃避、能量護盾的增加套用在那一格穿著的防具上",
                "<gray>先點上面的孔選位置，再點下面的符文紋上去",
                "<red>紋上去的符文拿不下來；同一孔再紋會蓋掉原本的"));
        for (int i = 0; i < SOCKET_SLOTS.length; i++) {
            final Runes.Rune rune = tattoos[i].isEmpty() || plugin.items() == null ? null : plugin.items().repository().rune(tattoos[i]);
            final List<String> lore = new ArrayList<>();
            if (rune == null) {
                lore.add("<dark_gray>（空的）");
            } else {
                for (final Runes.Line line : rune.armour()) {
                    lore.add("<white>" + ItemRenderer.escape(line.text()));
                }
            }
            lore.add("");
            lore.add(i == selected ? "<green>▶ 下面點一顆符文紋在這一孔" : "<yellow>左鍵：選這一孔");
            final String title = "<#d9b44a>" + SOCKET_NAMES[i] + (rune == null ? "" : "：<white>" + ItemRenderer.escape(rune.name()));
            final org.bukkit.inventory.ItemStack stack = icon(rune == null ? Material.GRAY_DYE : Material.LAPIS_LAZULI, title, lore);
            set(SOCKET_SLOTS[i], i == selected ? glowing(stack) : stack);
        }
        runes.clear();
        if (plugin.currencies() != null) {
            for (final CurrencyType type : plugin.currencies().repository().all()) {
                if (type.operation() == CurrencyOperation.RUNE && plugin.economy() != null
                        && plugin.economy().wallet().available(player, type.id()) > 0) {
                    runes.add(type);
                }
            }
        }
        for (int i = 0; i < Math.min(LIST_SIZE, runes.size()); i++) {
            final CurrencyType type = runes.get(i);
            final Runes.Rune rune = plugin.items() == null ? null : plugin.items().repository().rune(type.affix());
            final List<String> lore = new ArrayList<>();
            if (rune != null) {
                for (final Runes.Line line : rune.armour()) {
                    lore.add("<white>" + ItemRenderer.escape(line.text()));
                }
            }
            lore.add("<dark_gray>你有 " + plugin.economy().wallet().available(player, type.id()) + " 個");
            lore.add("");
            lore.add("<yellow>左鍵：紋在「" + SOCKET_NAMES[selected] + "」");
            set(LIST_START + i, highlight(icon(Menu.material(type.material(), Material.LAPIS_LAZULI), "<white>" + ItemRenderer.escape(type.name()), lore)));
        }
        if (runes.isEmpty()) {
            set(LIST_START + 4, icon(Material.BARRIER, "<red>你沒有符文", "<gray>通貨袋、背包、倉庫裡的符文都會列在這裡"));
        }
        set(SLOT_BACK, icon(Material.ARROW, "<white>回到昇華天賦"));
        set(SLOT_CLOSE, closeButton());
    }

    @Override
    protected void onClick(final int slot, final ClickType click, final InventoryClickEvent event) {
        if (slot == SLOT_CLOSE) {
            goBack();
            return;
        }
        if (slot == SLOT_BACK) {
            new AscendancyTreeMenu(plugin, player, service).open();
            return;
        }
        if (!acceptAction(click)) {
            return;
        }
        for (int i = 0; i < SOCKET_SLOTS.length; i++) {
            if (SOCKET_SLOTS[i] == slot) {
                selected = i;
                player.playSound(player.getLocation(), Sound.UI_BUTTON_CLICK, 0.6f, 1.2f);
                redraw();
                return;
            }
        }
        final int index = slot - LIST_START;
        if (index < 0 || index >= Math.min(LIST_SIZE, runes.size())) {
            return;
        }
        final PlayerState state = plugin.players().get(player);
        if (AscBuild.ascFlat(plugin, state, AscKeys.MERIDIANS) <= 0) {
            fail("要先配昇華天賦「符文子午線」");
            return;
        }
        final CurrencyType type = runes.get(index);
        if (plugin.items() == null || plugin.items().repository().rune(type.affix()) == null) {
            fail("這種符文不能紋");
            return;
        }
        if (!plugin.economy().wallet().take(player, type.id(), 1)) {
            fail("你沒有這種符文了");
            redraw();
            return;
        }
        final String[] tattoos = AscBuild.meridianRunes(state);
        tattoos[selected] = type.affix();
        plugin.characters().setProgress(player, AscBuild.MERIDIANS, String.join("|", tattoos));
        plugin.players().markDirty(player);
        player.playSound(player.getLocation(), Sound.BLOCK_ENCHANTMENT_TABLE_USE, 0.8f, 1.3f);
        tw.exilecore.gui.ActionBar.send(player, plugin.text().parse("<#d9b44a>符文子午線<gray>：" + ItemRenderer.escape(type.name())
                + " 紋在" + SOCKET_NAMES[selected]));
        redraw();
    }

    private void fail(final String message) {
        player.playSound(player.getLocation(), Sound.BLOCK_NOTE_BLOCK_BASS, 0.8f, 0.6f);
        tw.exilecore.gui.ActionBar.send(player, plugin.text().parse("<red>" + ItemRenderer.escape(message)));
    }
}
