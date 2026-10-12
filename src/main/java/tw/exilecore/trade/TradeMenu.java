package tw.exilecore.trade;

import org.bukkit.Material;
import org.bukkit.Sound;
import org.bukkit.entity.Player;
import org.bukkit.event.inventory.ClickType;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.inventory.ItemStack;
import tw.exilecore.ExileCore;
import tw.exilecore.character.CurrencyPouch;
import tw.exilecore.currency.CurrencyType;
import tw.exilecore.economy.EconomyConfig;
import tw.exilecore.gui.Menu;
import tw.exilecore.item.ItemRenderer;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * 面對面交易的視窗（5 列）：左邊 4×4 是你放的，右邊 4×4 是對方放的，最下面一列是按鈕。
 * 點自己背包的東西放上去、點左邊的東西拿回來；「通貨袋」切換成選通貨袋裡的通貨。
 * 兩邊都按確認就成交；任何一邊改了內容，兩邊的確認都會取消。確認鈕上看得到自己要付的手續費（{@link TradeFee}），
 * 對方的確認狀態上看得到對方要付多少。
 */
final class TradeMenu extends Menu {

    static final int SLOT_CONFIRM = 36;
    static final int SLOT_POUCH = 37;
    static final int SLOT_INFO = 40;
    static final int SLOT_CANCEL = 43;
    static final int SLOT_THEIRS = 44;
    /** 每邊 16 格。 */
    static final int CELLS = 16;

    private final TradeService service;
    private final TradeSession session;
    private final TradeSession.Side side;
    /** 通貨袋畫面：格子 → 通貨代號。 */
    private final List<String> pouchOrder = new ArrayList<>();

    TradeMenu(final ExileCore plugin, final Player player, final TradeService service, final TradeSession session,
              final TradeSession.Side side) {
        super(plugin, player, 5, "<dark_gray>交易：你　｜　" + ItemRenderer.escape(plugin.displayName(session.other(side).player)));
        this.service = service;
        this.session = session;
        this.side = side;
    }

    /** 左邊第 i 格（自己的）。 */
    static int mine(final int index) {
        return index / 4 * 9 + index % 4;
    }

    /** 右邊第 i 格（對方的）。 */
    static int theirs(final int index) {
        return index / 4 * 9 + 5 + index % 4;
    }

    /** 選單格子 → 左邊第幾格（不是左邊回傳 -1）。 */
    static int mineIndex(final int slot) {
        if (slot < 0 || slot >= 36 || slot % 9 > 3) {
            return -1;
        }
        return slot / 9 * 4 + slot % 9;
    }

    @Override
    protected void draw() {
        for (int i = 0; i < 45; i++) {
            set(i, null);
        }
        for (int row = 0; row < 4; row++) {
            set(row * 9 + 4, icon(Material.GRAY_STAINED_GLASS_PANE, "<dark_gray>← 你　｜　對方 →"));
        }
        final TradeSession.Side other = session.other(side);
        if (side.pouchView) {
            drawPouch();
        } else {
            drawOffers(side, true);
        }
        drawOffers(other, false);
        for (int slot = 36; slot < 45; slot++) {
            set(slot, filler());
        }
        final long now = plugin.getServer().getCurrentTick();
        final long wait = service.confirmWaitTicks(session, side, now);
        final TradeFee.Quote fee = service.fee(session, side);
        final long gold = plugin.gold().balance(player);
        side.shownGold = gold;
        final EconomyConfig.Fee rule = plugin.economy().config().tradeFee();
        final List<String> lore = new ArrayList<>();
        if (side.confirmed) {
            lore.add("<gray>等對方也確認就成交");
            lore.add("");
            lore.addAll(feeLines(fee, gold));
            lore.add("");
            lore.add("<yellow>左鍵：取消確認");
            set(SLOT_CONFIRM, glowing(icon(Material.LIME_CONCRETE, "<green>你已經確認", lore)));
        } else if (wait > 0) {
            lore.add("<gray>先看清楚對方放了什麼");
            lore.add(String.format(Locale.ROOT, "<gray>%.1f 秒後才能確認", wait / 20.0));
            lore.add("");
            lore.addAll(feeLines(fee, gold));
            set(SLOT_CONFIRM, icon(Material.YELLOW_CONCRETE, "<yellow>對方剛改過內容", lore));
        } else if (gold < fee.fee()) {
            lore.addAll(feeLines(fee, gold));
            lore.add("");
            lore.add(fee.fee() > rule.min() ? "<gray>請對方少放一點，或先去賺一些金幣" : "<gray>先去賺一些金幣再來確認");
            set(SLOT_CONFIRM, icon(Material.GOLD_NUGGET, "<red>金幣不夠付手續費", lore));
        } else {
            lore.add("<gray>兩邊都確認才會成交");
            lore.add("<gray>任何一邊改了內容，確認都會取消");
            lore.add("");
            lore.addAll(feeLines(fee, gold));
            lore.add("");
            lore.add("<yellow>左鍵：確認");
            set(SLOT_CONFIRM, icon(Material.RED_CONCRETE, "<white>確認交易", lore));
        }
        final CurrencyPouch pouch = plugin.players().get(player).bag().pouch();
        set(SLOT_POUCH, icon(Material.BUNDLE, side.pouchView ? "<#7fd4ff>回到你放的東西" : "<#7fd4ff>放通貨袋裡的通貨",
                side.pouchView ? "<gray>左邊回去顯示你放的東西" : "<gray>通貨袋裡有 " + pouch.kinds() + " 種通貨",
                "", "<yellow>左鍵：切換"));
        set(SLOT_INFO, icon(Material.BOOK, "<white>怎麼交易",
                "<gray>點你背包裡的東西：放上去",
                "<gray>點左邊你放的東西：拿回來",
                "<gray>通貨袋：選通貨袋裡的通貨放上去",
                "<gray>兩邊都按確認就成交（東西直接進背包）",
                "",
                rule.percent() <= 0 && rule.min() <= 0 ? "<gray>不收手續費"
                        : String.format(Locale.ROOT, "<gray>手續費：各付自己收到的東西估價的 %s%%（至少 %,d 金幣）",
                        rule.percentText(), rule.min()),
                "<dark_gray>通貨照市集的價值、裝備照商人的價格估；沒收到東西不用付；有月卡的人免付",
                "<dark_gray>金幣、收納袋、界伏盒不能交易；離太遠或關掉視窗就取消"));
        set(SLOT_CANCEL, icon(Material.BARRIER, "<red>取消交易"));
        final TradeFee.Quote theirFee = service.fee(session, other);
        final String theirFeeLine = theirFee.nothing() ? "<gray>你沒放東西，對方不用付手續費"
                : theirFee.fee() <= 0 ? (service.feeFree(other.player) ? "<gray>對方有月卡，不用付手續費" : "<gray>對方不用付手續費")
                : String.format(Locale.ROOT, "<gray>對方收下你放的東西要付 <#f5b942>%,d 金幣<gray> 手續費", theirFee.fee());
        set(SLOT_THEIRS, other.confirmed
                ? glowing(icon(Material.LIME_CONCRETE, "<green>" + ItemRenderer.escape(plugin.displayName(other.player)) + " 已經確認", theirFeeLine))
                : icon(Material.GRAY_CONCRETE, "<gray>" + ItemRenderer.escape(plugin.displayName(other.player)) + " 還沒確認", theirFeeLine));
    }

    /** 確認鈕上的手續費說明。 */
    private List<String> feeLines(final TradeFee.Quote fee, final long gold) {
        if (fee.nothing()) {
            return List.of("<gray>你沒有收到東西，不用付手續費");
        }
        if (fee.fee() <= 0) {
            return List.of(service.feeFree(player) ? "<#bcd4ff>月卡：<gray>不用付手續費" : "<gray>不用付手續費");
        }
        final EconomyConfig.Fee rule = plugin.economy().config().tradeFee();
        return List.of(
                String.format(Locale.ROOT, "<gray>你要付的手續費：<#f5b942>%,d 金幣<gray>（你有 %s%,d<gray>）",
                        fee.fee(), gold < fee.fee() ? "<red>" : "<white>", gold),
                String.format(Locale.ROOT, "<dark_gray>照你收到的東西估價收 %s%%（至少 %,d）",
                        rule.percentText(), rule.min()));
    }

    private void drawOffers(final TradeSession.Side owner, final boolean left) {
        for (int i = 0; i < CELLS; i++) {
            final int slot = left ? mine(i) : theirs(i);
            if (i >= owner.offers.size()) {
                set(slot, null);
                continue;
            }
            set(slot, display(owner, owner.offers.get(i), left));
        }
    }

    private ItemStack display(final TradeSession.Side owner, final TradeSession.Offer offer, final boolean mine) {
        final ItemStack stack;
        if (offer instanceof TradeSession.ItemOffer item) {
            stack = item.snapshot().clone();
        } else if (offer instanceof TradeSession.PouchOffer pouch) {
            final CurrencyType type = plugin.currencies().repository().get(pouch.currencyId());
            if (type == null) {
                return icon(Material.BARRIER, "<red>" + pouch.currencyId() + " ×" + pouch.amount());
            }
            stack = plugin.currencies().create(type, Math.min(type.stack(), pouch.amount()));
            stack.setAmount(Math.max(1, Math.min(stack.getMaxStackSize(), pouch.amount())));
            appendLore(stack, "", String.format(Locale.ROOT, "<#7fd4ff>通貨袋裡的 %,d 個", pouch.amount()));
        } else {
            return null;
        }
        if (mine) {
            appendLore(stack, "", "<yellow>左鍵：拿回來");
        } else if (owner != side) {
            appendLore(stack, "", "<gray>" + ItemRenderer.escape(plugin.displayName(owner.player)) + " 放的");
        }
        return stack;
    }

    private void drawPouch() {
        pouchOrder.clear();
        final CurrencyPouch pouch = plugin.players().get(player).bag().pouch();
        for (final CurrencyType type : plugin.currencies().repository().all()) {
            if (pouch.amount(type.id()) > 0) {
                pouchOrder.add(type.id());
            }
        }
        for (int i = 0; i < CELLS; i++) {
            final int slot = mine(i);
            if (i >= pouchOrder.size()) {
                set(slot, i == 0 ? icon(Material.GLASS_PANE, "<gray>通貨袋是空的") : null);
                continue;
            }
            final CurrencyType type = plugin.currencies().repository().get(pouchOrder.get(i));
            final int have = pouch.amount(type.id());
            final int offered = side.pouchOffered(type.id());
            final ItemStack stack = plugin.currencies().create(type, Math.min(type.stack(), have));
            stack.setAmount(Math.max(1, Math.min(stack.getMaxStackSize(), have)));
            appendLore(stack, "", String.format(Locale.ROOT, "<gray>通貨袋裡有 <white>%,d</white>，已經放了 <white>%,d", have, offered),
                    "<yellow>左鍵：放 1 個　Shift＋左鍵：放 10 個", "<yellow>右鍵：全部放上去");
            if (offered <= 0) {
                stack.editMeta(meta -> meta.setEnchantmentGlintOverride(false)); // 通貨本來就會發光：放了的才發光
            }
            set(slot, stack);
        }
        if (pouchOrder.size() > CELLS) {
            set(mine(CELLS - 1), icon(Material.PAPER, "<gray>還有 " + (pouchOrder.size() - CELLS + 1) + " 種",
                    "<gray>先在 /bag 拿出來放背包，再點背包放上去"));
        }
    }

    @Override
    protected void onClick(final int slot, final ClickType click, final InventoryClickEvent event) {
        if (session.finished) {
            return;
        }
        if (slot == SLOT_CONFIRM) {
            if (acceptAction(click)) { // 雙擊不會「確認又取消」
                service.toggleConfirm(session, side);
            }
            return;
        }
        if (slot == SLOT_POUCH) {
            side.pouchView = !side.pouchView;
            redraw();
            player.playSound(player.getLocation(), Sound.UI_BUTTON_CLICK, 0.4f, 1.3f);
            return;
        }
        if (slot == SLOT_CANCEL) {
            service.cancel(session, ItemRenderer.escape(plugin.displayName(player)) + " 取消了交易");
            return;
        }
        final int index = mineIndex(slot);
        if (index < 0) {
            return;
        }
        if (side.pouchView) {
            if (click == ClickType.DOUBLE_CLICK) {
                return; // 雙擊多送的那一下不算（連點放好幾個是可以的）
            }
            if (index < pouchOrder.size() && !(pouchOrder.size() > CELLS && index == CELLS - 1)) {
                final int amount = switch (click) {
                    case SHIFT_LEFT -> 10;
                    case RIGHT, SHIFT_RIGHT -> Integer.MAX_VALUE;
                    default -> 1;
                };
                service.offerPouch(session, side, pouchOrder.get(index), amount);
            }
            return;
        }
        if (index < side.offers.size() && acceptAction(click)) {
            service.removeOffer(session, side, index);
        }
    }

    @Override
    protected void onBottomClick(final int slot, final ClickType click, final InventoryClickEvent event) {
        if (session.finished) {
            return;
        }
        if (slot < 0 || slot >= 36) {
            return; // 只能放背包（快捷列＋主背包）的東西
        }
        service.offerItem(session, side, slot);
    }

    @Override
    protected void onClose() {
        if (!session.finished) {
            service.cancel(session, ItemRenderer.escape(plugin.displayName(player)) + " 關掉了交易視窗");
        }
    }
}
