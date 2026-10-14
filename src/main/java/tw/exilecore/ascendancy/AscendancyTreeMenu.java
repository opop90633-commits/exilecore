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
import java.util.Locale;
import java.util.Map;

/**
 * 昇華天賦（/ascend tree）：選的昇華職業的小樹。每條分支一列，從左邊的徽記往右一個接一個；
 * 左鍵配點、右鍵退點（花金幣）。
 */
final class AscendancyTreeMenu extends Menu {

    private static final int SLOT_HEADER = 4;
    private static final int SLOT_BACK = 45;
    private static final int SLOT_PREVIEW = 47;
    private static final int SLOT_INFO = 49;
    private static final int SLOT_CLOSE = 53;
    /** 月卡福利（2026-10）：隨時更換昇華職業（主選單拿掉「昇華」之後，這裡也要能換）。 */
    private static final int SLOT_RESPEC = 51;

    private final AscendancyService service;
    private final Map<Integer, AscendancyConfig.Node> nodes = new HashMap<>();

    AscendancyTreeMenu(final ExileCore plugin, final Player player, final AscendancyService service) {
        super(plugin, player, 6, "<dark_gray>昇華天賦");
        this.service = service;
    }

    @Override
    protected void draw() {
        for (int i = 0; i < 54; i++) {
            set(i, filler());
        }
        nodes.clear();
        final Ascension ascension = service.ascension(player);
        final AscendancyConfig.Ascendancy chosen = ascension.chosen();
        set(SLOT_BACK, backButton("昇華與試煉"));
        set(SLOT_CLOSE, closeButton());
        if (chosen == null) {
            set(22, icon(Material.GRAY_DYE, "<gray>還沒有選昇華職業", "<dark_gray>完成第一試煉之後選擇"));
            return;
        }
        set(SLOT_HEADER, glowing(icon(Menu.material(chosen.icon(), Material.NETHER_STAR), chosen.colored(),
                "<gray><i>" + AscendancyService.escape(chosen.summary()),
                "",
                "<gray>昇華點：共 <white>" + ascension.points() + "</white>，已配 <white>" + ascension.spent()
                        + "</white>，可配 <white>" + ascension.available(),
                "<dark_gray>每個試煉第一次完成給昇華點")));

        // 2026-10：每條分支最左邊是標籤（整條在做什麼），天賦之間用鏈子連（以前灰色玻璃跟背景分不出來）
        final List<List<AscendancyConfig.Node>> branches = chosen.branches();
        for (int b = 0; b < branches.size() && b < AscendancyConfig.MAX_BRANCHES; b++) {
            final int row = AscendancyLayout.FIRST_ROW + b;
            final List<AscendancyConfig.Node> branch = branches.get(b);
            int owned = 0;
            for (final AscendancyConfig.Node node : branch) {
                if (ascension.isAllocated(node)) {
                    owned++;
                }
            }
            set(row * 9, AscendancyLayout.branchLabel(chosen, b, branch, owned));
            set(row * 9 + 1, AscendancyLayout.link(!branch.isEmpty() && ascension.isAllocated(branch.getFirst())));
            for (int i = 0; i < branch.size() && i < AscendancyLayout.NODE_COLUMNS.length; i++) {
                final AscendancyConfig.Node node = branch.get(i);
                final int slot = row * 9 + AscendancyLayout.NODE_COLUMNS[i];
                nodes.put(slot, node);
                set(slot, node(branch, i, ascension));
                if (i + 1 < branch.size() && i + 1 < AscendancyLayout.NODE_COLUMNS.length) {
                    set(slot + 1, AscendancyLayout.link(ascension.isAllocated(branch.get(i + 1))));
                }
            }
        }

        final boolean card = plugin.sponsors() != null && plugin.sponsors().cardActive(player);
        set(SLOT_RESPEC, icon(Material.CLOCK, "<#bcd4ff>更換昇華職業（月卡）",
                "<gray>清掉昇華職業與昇華天賦（點數全部拿回來，試煉不用重打），",
                "<gray>重新選這個職業的昇華",
                card ? "<green>月卡：隨時可以換，不花錢" : "<dark_gray>要有月卡才能換（沒有月卡選了就不能換）",
                "", card ? "<yellow>Shift＋左鍵：更換" : ""));
        set(SLOT_PREVIEW, icon(Material.SPYGLASS, "<white>昇華預覽", "<gray>看其他昇華職業的天賦樹（也可以看別的職業的）",
                "", "<yellow>左鍵：打開預覽"));
        final long cost = plugin.players().config().respecCost(plugin.players().get(player).level());
        set(SLOT_INFO, icon(Material.WRITABLE_BOOK, "<white>怎麼配",
                "<gray>左鍵配點：每條分支從第一個配起",
                "<gray>右鍵退點：要從分支的最後面退",
                cost <= 0 ? "<gray>退點免費" : String.format(Locale.ROOT, "<gray>退點花金幣：每 1 點 <#f5b942>%,d<gray>（跟天賦樹一樣）", cost),
                String.format(Locale.ROOT, "<gray>你有 <#f5b942>%,d<gray> 金幣", plugin.players().get(player).gold())));
    }

    private ItemStack node(final List<AscendancyConfig.Node> branch, final int i, final Ascension ascension) {
        final AscendancyConfig.Node node = branch.get(i);
        final boolean owned = ascension.isAllocated(node);
        final String problem = owned ? null : ascension.allocateProblem(node);
        final List<String> lore = new ArrayList<>();
        for (final String line : StatLines.describeAll(node.stats())) {
            lore.add((owned || problem == null ? "<white>" : "<gray>") + AscendancyService.escape(line));
        }
        if (node.note() != null) {
            lore.add("<dark_gray><i>" + AscendancyService.escape(node.note()));
        }
        lore.add("");
        lore.add("<gray>" + node.cost() + " 點<dark_gray>　" + AscendancyLayout.position(branch, i));
        final Material material = Menu.material(node.icon(), Material.AMETHYST_SHARD);
        if (owned) {
            final String refund = ascension.refundProblem(node);
            if (refund != null) {
                lore.add("<dark_gray>" + AscendancyService.escape(refund) + "才能退這個");
            } else {
                final long cost = service.refundCost(player, node);
                lore.add(cost <= 0 ? "<yellow>右鍵：退掉（免費）"
                        : String.format(Locale.ROOT, "<yellow>右鍵：退掉（<#f5b942>%,d<yellow> 金幣）", cost));
            }
            return glowing(icon(material, "<green>✔ " + ascensionColor(ascension) + AscendancyService.escape(node.name()), lore));
        }
        if (problem == null) {
            lore.add("<yellow>左鍵：配點");
            return highlight(icon(material, "<yellow>" + AscendancyService.escape(node.name()), lore));
        }
        lore.add("<red>" + AscendancyService.escape(problem));
        return icon(material, "<gray>" + AscendancyService.escape(node.name()), lore);
    }

    private static String ascensionColor(final Ascension ascension) {
        final AscendancyConfig.Ascendancy chosen = ascension.chosen();
        return chosen == null ? "<white>" : "<" + chosen.color() + ">";
    }

    @Override
    protected void onClick(final int slot, final ClickType click, final InventoryClickEvent event) {
        if (click == ClickType.DOUBLE_CLICK) {
            return; // 雙擊多送的那一下不算
        }
        switch (slot) {
            case SLOT_BACK -> service.open(player);
            case SLOT_CLOSE -> goBack();
            case SLOT_PREVIEW -> service.openPreview(player, null, true);
            case SLOT_RESPEC -> {
                if (click != ClickType.SHIFT_LEFT) {
                    return;
                }
                if (plugin.sponsors() == null || !plugin.sponsors().cardActive(player)) {
                    player.sendMessage(plugin.text().prefixed("<red>更換昇華職業是月卡的福利"));
                    return;
                }
                if (service.ascension(plugin.players().get(player)).chosen() == null) {
                    return;
                }
                service.adminReset(player);
                player.sendMessage(plugin.text().prefixed("<#bcd4ff>月卡<gray>：清掉了昇華職業與昇華天賦（點數拿回來了），重新選一個"));
                player.playSound(player.getLocation(), Sound.BLOCK_ENCHANTMENT_TABLE_USE, 0.7f, 1.2f);
                new AscendancyChoiceMenu(plugin, player, service).open();
            }
            default -> {
                final AscendancyConfig.Node node = nodes.get(slot);
                if (node == null || !acceptAction(click)) {
                    return;
                }
                if (click.isRightClick()) {
                    refund(node);
                } else if (click.isLeftClick()) {
                    result(service.allocate(player, node.id()), Sound.BLOCK_AMETHYST_BLOCK_CHIME);
                }
            }
        }
    }

    private void refund(final AscendancyConfig.Node node) {
        final Ascension ascension = service.ascension(player);
        final String problem = ascension.refundProblem(node);
        if (problem != null) {
            result(problem, null);
            return;
        }
        final long cost = service.refundCost(player, node);
        if (cost <= 0) {
            result(service.refund(player, node.id()), Sound.UI_BUTTON_CLICK);
            return;
        }
        plugin.dialogs().confirm(player, AscendancyService.COLOR + "退掉昇華天賦",
                List.of("<gray>退掉 <white>" + AscendancyService.escape(node.name()) + "<gray>，拿回 " + node.cost() + " 個昇華點？",
                        String.format(Locale.ROOT, "<gray>要花 <#f5b942>%,d<gray> 金幣（你有 %,d）", cost, plugin.players().get(player).gold())),
                "退掉", () -> {
                    final String failed = service.refund(player, node.id());
                    if (failed != null) {
                        fail(failed);
                    } else {
                        player.playSound(player.getLocation(), Sound.UI_BUTTON_CLICK, 0.6f, 1.0f);
                    }
                    new AscendancyTreeMenu(plugin, player, service).open();
                }, () -> new AscendancyTreeMenu(plugin, player, service).open());
    }

    private void result(final String problem, final Sound success) {
        if (problem != null) {
            fail(problem);
            redraw();
            return;
        }
        if (success != null) {
            player.playSound(player.getLocation(), success, 0.8f, 1.2f);
        }
        redraw();
    }

    private void fail(final String problem) {
        player.sendMessage(plugin.text().prefixed("<red>" + AscendancyService.escape(problem)));
        player.playSound(player.getLocation(), Sound.BLOCK_NOTE_BLOCK_BASS, 0.6f, 0.8f);
    }
}
