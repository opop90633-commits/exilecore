package tw.exilecore.ascendancy;

import org.bukkit.Material;
import org.bukkit.Sound;
import org.bukkit.entity.Player;
import org.bukkit.event.inventory.ClickType;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.inventory.ItemStack;
import tw.exilecore.ExileCore;
import tw.exilecore.gui.Menu;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * 選擇昇華職業（/ascend choose）：這個職業的兩條路，每條列出全部的昇華天賦。選了會先問一次（選了不能換）。
 */
final class AscendancyChoiceMenu extends Menu {

    private static final int SLOT_HEADER = 4;
    private static final int SLOT_BACK = 27;
    private static final int SLOT_CLOSE = 35;

    private final AscendancyService service;
    private final Map<Integer, AscendancyConfig.Ascendancy> options = new HashMap<>();

    AscendancyChoiceMenu(final ExileCore plugin, final Player player, final AscendancyService service) {
        super(plugin, player, 4, "<dark_gray>選擇昇華職業");
        this.service = service;
    }

    static int[] optionSlots(final int count) {
        return switch (count) {
            case 1 -> new int[] {13};
            case 2 -> new int[] {11, 15};
            case 3 -> new int[] {11, 13, 15};
            default -> new int[] {10, 12, 14, 16};
        };
    }

    @Override
    protected void draw() {
        for (int i = 0; i < 36; i++) {
            set(i, filler());
        }
        options.clear();
        final Ascension ascension = service.ascension(player);
        set(SLOT_HEADER, glowing(icon(Material.NETHER_STAR, "<#f5b942>選擇昇華職業",
                "<gray>每種流放者有好幾條路。走過熔爐的門，星骸會把你變成其中一種。",
                "<red>選了就不能換（這個角色）",
                "<gray>之後用昇華點配那個昇華職業的天賦（一個 1 點，四個試煉一共 8 點）",
                "",
                "<gray>你有 <white>" + ascension.points() + "</white> 個昇華點")));
        final List<AscendancyConfig.Ascendancy> list = ascension.options();
        final int[] slots = optionSlots(list.size());
        for (int i = 0; i < list.size() && i < slots.length; i++) {
            options.put(slots[i], list.get(i));
            set(slots[i], option(list.get(i), ascension.canChoose()));
        }
        set(SLOT_BACK, icon(Material.ARROW, "<white>← 昇華與試煉"));
        set(SLOT_CLOSE, closeButton());
    }

    private ItemStack option(final AscendancyConfig.Ascendancy ascendancy, final boolean canChoose) {
        final List<String> lore = new ArrayList<>();
        lore.add("<gray><i>" + AscendancyService.escape(ascendancy.summary()));
        // 2026-10-26：照 PoE2 的昇華樹（20 個上下的天賦）：這裡只列核心天賦的名稱與第一行，完整的樹看右鍵的預覽
        lore.add("");
        lore.add("<gray>核心天賦：");
        for (final AscendancyConfig.Node node : ascendancy.nodes()) {
            if (!node.notable() && !node.choice()) {
                continue;
            }
            final List<String> lines = node.display();
            final String first = lines.isEmpty() ? "" : lines.getFirst().split("\n")[0];
            lore.add("<" + ascendancy.color() + ">◆ " + AscendancyService.escape(node.name())
                    + (first.isEmpty() ? "" : " <dark_gray>" + AscendancyService.escape(first)));
        }
        lore.add("");
        lore.add(canChoose ? "<yellow>左鍵：昇華為" + AscendancyService.escape(ascendancy.name()) + "（會先問一次）" : "<dark_gray>完成第一試煉之後才能選");
        lore.add("<yellow>右鍵：看天賦樹（預覽）");
        final ItemStack icon = icon(Menu.material(ascendancy.icon(), Material.NETHER_STAR), ascendancy.colored(), lore);
        return canChoose ? glowing(icon) : icon;
    }

    @Override
    protected void onClick(final int slot, final ClickType click, final InventoryClickEvent event) {
        switch (slot) {
            case SLOT_BACK -> service.open(player);
            case SLOT_CLOSE -> goBack();
            default -> {
                final AscendancyConfig.Ascendancy ascendancy = options.get(slot);
                if (ascendancy == null || !acceptAction(click)) {
                    return;
                }
                if (click.isRightClick()) {
                    service.openPreview(player, ascendancy.id(), false);
                    return;
                }
                final String problem = service.ascension(player).chooseProblem(ascendancy);
                if (problem != null) {
                    fail(problem);
                    return;
                }
                plugin.dialogs().confirm(player, "<#f5b942>昇華為" + ascendancy.colored() + "？",
                        List.of("<gray>" + AscendancyService.escape(ascendancy.summary()),
                                "",
                                "<red>選了就不能換：這個角色之後只能配" + AscendancyService.escape(ascendancy.name()) + "的昇華天賦"),
                        "昇華", () -> {
                            final String failed = service.choose(player, ascendancy.id());
                            if (failed != null) {
                                fail(failed);
                                new AscendancyChoiceMenu(plugin, player, service).open();
                                return;
                            }
                            new AscendancyTreeMenu(plugin, player, service).open();
                        }, () -> new AscendancyChoiceMenu(plugin, player, service).open());
            }
        }
    }

    private void fail(final String problem) {
        player.sendMessage(plugin.text().prefixed("<red>" + AscendancyService.escape(problem)));
        player.playSound(player.getLocation(), Sound.BLOCK_NOTE_BLOCK_BASS, 0.6f, 0.8f);
    }
}
