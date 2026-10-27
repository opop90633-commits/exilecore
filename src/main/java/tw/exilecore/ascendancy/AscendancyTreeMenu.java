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
    /** 2026-10-27 照 PoE2 昇華・晶化護符：命匣（昇華天賦上的珠寶孔）。 */
    private static final int SLOT_PHYLACTERY = 46;
    /** 2026-10-27 照 PoE2 昇華・符文子午線：紋在身上的符文。 */
    private static final int SLOT_MERIDIANS = 52;

    private final AscendancyService service;
    private final Map<Integer, AscendancyConfig.Node> nodes = new HashMap<>();
    /** 2026-10-26：樹從第幾排開始看（▲ ▼ 捲動）。 */
    private int offset;

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

        // 2026-10-26：照 PoE2 的昇華樹畫成往右長的樹（會分岔，樹太高的時候 ▲ ▼ 捲動）；這次之前每條分支一排
        offset = AscendancyLayout.clampOffset(offset, chosen.rows());
        final AscendancyLayout.Page page = AscendancyLayout.page(chosen, offset, node -> node(chosen, node, ascension), ascension::isAllocated);
        for (final Map.Entry<Integer, ItemStack> entry : page.items().entrySet()) {
            set(entry.getKey(), entry.getValue());
        }
        nodes.putAll(page.nodes());
        final ItemStack up = AscendancyLayout.scroll(true, offset, page.rows());
        final ItemStack down = AscendancyLayout.scroll(false, offset, page.rows());
        if (up != null) {
            set(AscendancyLayout.SLOT_UP, up);
        }
        if (down != null) {
            set(AscendancyLayout.SLOT_DOWN, down);
        }

        final boolean card = plugin.sponsors() != null && plugin.sponsors().cardActive(player);
        set(SLOT_RESPEC, icon(Material.CLOCK, "<#bcd4ff>更換昇華職業（月卡）",
                "<gray>清掉昇華職業與昇華天賦（點數全部拿回來，試煉不用重打），",
                "<gray>重新選這個職業的昇華",
                card ? "<green>月卡：隨時可以換，不花錢" : "<dark_gray>要有月卡才能換（沒有月卡選了就不能換）",
                "", card ? "<yellow>Shift＋左鍵：更換" : ""));
        drawPhylactery(chosen);
        drawMeridians(chosen);
        set(SLOT_PREVIEW, icon(Material.SPYGLASS, "<white>昇華預覽", "<gray>看其他昇華職業的天賦樹（也可以看別的職業的）",
                "", "<yellow>左鍵：打開預覽"));
        final long cost = plugin.players().config().respecCost(plugin.players().get(player).level());
        set(SLOT_INFO, icon(Material.WRITABLE_BOOK, "<white>怎麼配",
                "<gray>左鍵配點：要跟已配的天賦相連（第一欄的直接接在起點）",
                "<gray>分岔的往下一排；「選一個」的天賦配了之後從選項挑一個",
                "<gray>右鍵退點：退掉之後剩下的要都還接得到起點",
                cost <= 0 ? "<gray>退點免費" : String.format(Locale.ROOT, "<gray>退點花金幣：每 1 點 <#f5b942>%,d<gray>（跟天賦樹一樣）", cost),
                String.format(Locale.ROOT, "<gray>你有 <#f5b942>%,d<gray> 金幣", plugin.players().get(player).gold())));
    }

    /** 2026-10-27：這個昇華有命匣（有晶化護符那種天賦），或命匣裡還放著珠寶（退掉之後拿回來用）才畫。 */
    private void drawPhylactery(final AscendancyConfig.Ascendancy chosen) {
        final tw.exilecore.character.PlayerState state = plugin.players().get(player);
        final ItemStack held = state.jewel(tw.exilecore.passive.JewelSockets.PHYLACTERY);
        boolean has = false;
        for (final AscendancyConfig.Node node : chosen.nodes()) {
            for (final tw.exilecore.stats.Modifier mod : node.stats()) {
                has |= AscKeys.PHYLACTERY.equals(mod.stat());
            }
        }
        if (!has && held == null) {
            return;
        }
        final boolean active = AscBuild.ascFlat(plugin, state, AscKeys.PHYLACTERY) > 0;
        final List<String> lore = new ArrayList<>();
        lore.add("<gray>可以鑲嵌一顆非傳奇的基礎珠寶，效果增加 100%");
        lore.add(active ? "<green>晶化護符已配置" : "<red>要先配「晶化護符」才有效果");
        lore.add("");
        if (held == null) {
            lore.add("<dark_gray>（空的）");
            if (active) {
                lore.add("<yellow>左鍵：選一顆珠寶放進去");
            }
            set(SLOT_PHYLACTERY, icon(Material.GLASS_BOTTLE, "<#bcd4ff>命匣", lore));
            return;
        }
        lore.addAll(tw.exilecore.passive.JewelSockets.describe(plugin, held));
        lore.add("");
        lore.add("<yellow>左鍵：拿回背包");
        set(SLOT_PHYLACTERY, glowing(icon(Material.EXPERIENCE_BOTTLE, "<#bcd4ff>命匣", lore)));
    }

    /** 2026-10-27：這個昇華有符文子午線那種天賦才畫。 */
    private void drawMeridians(final AscendancyConfig.Ascendancy chosen) {
        boolean has = false;
        for (final AscendancyConfig.Node node : chosen.nodes()) {
            for (final tw.exilecore.stats.Modifier mod : node.stats()) {
                has |= AscKeys.MERIDIANS.equals(mod.stat());
            }
        }
        if (!has) {
            return;
        }
        final tw.exilecore.character.PlayerState state = plugin.players().get(player);
        final boolean active = AscBuild.ascFlat(plugin, state, AscKeys.MERIDIANS) > 0;
        int count = 0;
        for (final String rune : AscBuild.meridianRunes(state)) {
            count += rune.isEmpty() ? 0 : 1;
        }
        final ItemStack stack = icon(Material.PAPER, "<#d9b44a>符文子午線",
                "<gray>把符文紋在身上：頭盔 1、胸甲 2、腿甲 1、鞋子 1 孔",
                active ? "<green>符文子午線已配置" : "<red>要先配「符文子午線」才有效果",
                "<gray>已紋 <white>" + count + "</white>／5",
                "", "<yellow>左鍵：打開");
        set(SLOT_MERIDIANS, active ? glowing(stack) : stack);
    }

    private void phylactery() {
        final tw.exilecore.character.PlayerState state = plugin.players().get(player);
        if (state.jewel(tw.exilecore.passive.JewelSockets.PHYLACTERY) != null) {
            final tw.exilecore.passive.PassiveState.Result result = tw.exilecore.passive.JewelSockets.unsocketPhylactery(plugin, player);
            result(result.ok() ? null : result.message(), Sound.BLOCK_AMETHYST_BLOCK_BREAK);
            return;
        }
        if (AscBuild.ascFlat(plugin, state, AscKeys.PHYLACTERY) <= 0) {
            result("要先配昇華天賦「晶化護符」", null);
            return;
        }
        new PhylacteryMenu(plugin, player, service).open();
    }

    private ItemStack node(final AscendancyConfig.Ascendancy chosen, final AscendancyConfig.Node node, final Ascension ascension) {
        final boolean owned = ascension.isAllocated(node);
        final String problem = owned ? null : ascension.allocateProblem(node);
        final List<String> lore = AscendancyLayout.describe(chosen, node, owned || problem == null, service.skillLookup());
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
            return glowing(icon(material, "<green>✔ " + ascensionColor(ascension) + (node.notable() ? "<b>" : "")
                    + AscendancyService.escape(node.name()), lore));
        }
        if (problem == null) {
            lore.add(node.option() ? "<yellow>左鍵：選這個" : "<yellow>左鍵：配點");
            return highlight(icon(material, "<yellow>" + (node.notable() ? "<b>" : "") + AscendancyService.escape(node.name()), lore));
        }
        lore.add("<red>" + AscendancyService.escape(problem));
        return icon(material, (node.notable() ? "<white>" : "<gray>") + AscendancyService.escape(node.name()), lore);
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
            case SLOT_PHYLACTERY -> {
                if (click.isLeftClick() && service.ascension(player).chosen() != null) {
                    phylactery();
                }
            }
            case SLOT_MERIDIANS -> {
                if (click.isLeftClick() && service.ascension(player).chosen() != null) {
                    new MeridiansMenu(plugin, player, service).open();
                }
            }
            case AscendancyLayout.SLOT_UP, AscendancyLayout.SLOT_DOWN -> {
                offset += slot == AscendancyLayout.SLOT_UP ? -AscendancyLayout.STEP : AscendancyLayout.STEP;
                player.playSound(player.getLocation(), Sound.ITEM_BOOK_PAGE_TURN, 0.6f, 1.0f);
                redraw();
            }
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
                if (click.isShiftClick()) {
                    // 2026-10-27 照站長：Shift＋點擊看賦予的技能（不配點、不退點）
                    service.openSkillPreview(player, node, () -> new AscendancyTreeMenu(plugin, player, service).open());
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
